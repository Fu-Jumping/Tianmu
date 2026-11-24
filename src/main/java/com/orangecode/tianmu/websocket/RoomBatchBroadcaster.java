package com.orangecode.tianmu.websocket;

import com.orangecode.tianmu.common.DanmakuMetrics;

import cn.hutool.json.JSONUtil;
import com.orangecode.tianmu.constants.WebSocketConstant;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import io.netty.channel.Channel;
import io.netty.channel.group.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded pending batch and at most one timer per active room. */
@Component
@Slf4j
public class RoomBatchBroadcaster implements AutoCloseable {
    private static final String PREFIX = "{\"type\":" + WebSocketConstant.ONLINE_BULLET_BATCH + ",\"data\":[";
    private static final int ENVELOPE_BYTES = PREFIX.getBytes(StandardCharsets.UTF_8).length + 2;
    private final ConcurrentMap<Long, Room> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Limits limits;
    private final AtomicBoolean closed = new AtomicBoolean();

    record Limits(long windowMs, int maxItems, int maxBytes) {
        Limits {
            // Keep the batching window below the existing one-second send confirmation wait.
            if (windowMs < 1 || windowMs > 500 || maxItems < 1 || maxItems > 1000 || maxBytes < 4096 || maxBytes > 65536)
                throw new IllegalArgumentException("Invalid danmaku batch limits");
        }
    }

    @Autowired
    public RoomBatchBroadcaster(@Value("${netty.danmaku-batch.window-ms:100}") long windowMs,
                                @Value("${netty.danmaku-batch.max-items:50}") int maxItems,
                                @Value("${netty.danmaku-batch.max-bytes:16384}") int maxBytes) {
        this(new Limits(windowMs, maxItems, maxBytes), newScheduler());
    }

    RoomBatchBroadcaster(Limits limits, ScheduledExecutorService scheduler) {
        this.limits = limits; this.scheduler = scheduler;
    }

    private static ScheduledExecutorService newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            var thread = new Thread(runnable, "danmaku-room-flush"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    record SendTiming(long bulletId, String worker, String workerId, long received, long started, long orderWaitNanos, long authNanos,
                      long videoNanos, long mqStarted, long mqDone) {}
    private record BroadcastTiming(SendTiming send, long enqueued) {}
    private record Pending(String json, BroadcastTiming timing) {}

    private static final class Room {
        final long id;
        Room(long id) { this.id = id; }
        final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        final List<Pending> pending = new ArrayList<>();
        int bytes = ENVELOPE_BYTES;
        long generation;
        ScheduledFuture<?> timer;
        ScheduledFuture<?> onlineTimer;
        boolean disposed;
    }

    public void join(long roomId, Channel channel) {
        rooms.compute(roomId, (id, previous) -> {
            if (closed.get()) throw new IllegalStateException("Room broadcaster is closed");
            Room room = previous == null ? new Room(roomId) : previous;
            synchronized (room) {
                room.channels.add(channel);
                // Newcomer gets an immediate snapshot; existing viewers get one coalesced update.
                channel.writeAndFlush(new TextWebSocketFrame("{\"type\":1,\"data\":" + room.channels.size() + "}"));
                if (room.channels.size() > 1) scheduleOnline(room);
            }
            return room;
        });
    }

    public void leave(long roomId, Channel channel) {
        rooms.computeIfPresent(roomId, (id, room) -> {
            synchronized (room) {
                room.channels.remove(channel);
                if (room.channels.isEmpty()) {
                    discard(room); cancelOnline(room); room.disposed = true; return null;
                }
                scheduleOnline(room); return room;
            }
        });
    }

    public void append(long roomId, OnlineBulletResponse item) {
        append(roomId, item, null);
    }

    void append(long roomId, OnlineBulletResponse item, SendTiming timing) {
        Room room = rooms.get(roomId);
        if (room == null || closed.get()) {
            DanmakuMetrics.broadcastSkipped.increment();
            return;
        }
        String json = JSONUtil.toJsonStr(item);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + ENVELOPE_BYTES > limits.maxBytes()) throw new IllegalArgumentException("Danmaku exceeds batch byte limit");
        synchronized (room) {
            if (room.disposed || closed.get()) {
                DanmakuMetrics.broadcastSkipped.increment();
                return;
            }
            int separator = room.pending.isEmpty() ? 0 : 1;
            if (room.bytes + separator + bytes > limits.maxBytes()) flush(room);
            room.bytes += bytes + (room.pending.isEmpty() ? 0 : 1);
            long enqueued = System.nanoTime();
            if (timing != null) DanmakuMetrics.observe(DanmakuMetrics.Stage.MQ_TO_BATCH, enqueued - timing.mqDone());
            room.pending.add(new Pending(json, timing == null ? null : new BroadcastTiming(timing, enqueued)));
            if (room.pending.size() >= limits.maxItems() || room.bytes >= limits.maxBytes()) {
                flush(room);
            } else if (room.timer == null) {
                long generation = ++room.generation;
                long due = enqueued + TimeUnit.MILLISECONDS.toNanos(limits.windowMs());
                room.timer = scheduler.schedule(() -> {
                    long fired = System.nanoTime();
                    synchronized (room) {
                        if (!room.disposed && room.generation == generation) {
                            DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_TIMER_LAG, Math.max(0, fired - due));
                            DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_TIMER_LOCK, System.nanoTime() - fired);
                            flush(room);
                        }
                    }
                }, limits.windowMs(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Caller holds the room lock so timer/count/byte flushes preserve per-room append order. */
    private void flush(Room room) {
        if (room.pending.isEmpty()) return;
        long flushStarted = System.nanoTime();
        int items = room.pending.size();
        int bytes = room.bytes;
        var frame = new StringJoiner(",", PREFIX, "]}");
        var timings = new ArrayList<BroadcastTiming>(items);
        for (Pending pending : room.pending) {
            frame.add(pending.json());
            if (pending.timing() != null) {
                timings.add(pending.timing());
                DanmakuMetrics.observe(DanmakuMetrics.Stage.BATCH_WAIT, flushStarted - pending.timing().enqueued());
            }
        }
        var payload = new TextWebSocketFrame(frame.toString());
        discard(room);
        DanmakuMetrics.frames.increment();
        int viewers = room.channels.size();
        DanmakuMetrics.recipients.add(viewers);
        int blocked = 0;
        for (Channel channel : room.channels) if (!channel.isWritable()) blocked++;
        DanmakuMetrics.nonWritable.add(blocked);
        long dispatchStarted = System.nanoTime();
        DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_PREPARE, dispatchStarted - flushStarted);
        var future = room.channels.writeAndFlush(payload);
        long dispatchDone = System.nanoTime();
        DanmakuMetrics.observe(DanmakuMetrics.Stage.FLUSH_DISPATCH, dispatchDone - dispatchStarted);
        long roomId = room.id;
        int nonWritable = blocked;
        future.addListener((ChannelGroupFutureListener) completed -> {
            long done = System.nanoTime();
            int failed = 0;
            for (var result : completed) if (!result.isSuccess()) failed++;
            DanmakuMetrics.writeFailures.add(failed);
            DanmakuMetrics.observe(DanmakuMetrics.Stage.BROADCAST_WRITE, done - dispatchStarted);
            for (BroadcastTiming timing : timings) {
                SendTiming send = timing.send();
                long elapsed = done - send.received();
                DanmakuMetrics.observe(DanmakuMetrics.Stage.WS_TO_WRITE_COMPLETE, elapsed);
                if (DanmakuMetrics.allowSlowLog(elapsed)) {
                    log.warn("danmaku_slow room_id={} bullet_id={} worker={} worker_id={} ws_queue_ms={} send_order_wait_ms={} auth_ms={} "
                                    + "video_ms={} mq_ms={} mq_to_batch_ms={} batch_wait_ms={} flush_prepare_ms={} "
                                    + "flush_dispatch_ms={} broadcast_write_ms={} total_ms={} items={} bytes={} "
                                    + "viewers={} non_writable={} failed_writes={}",
                            roomId, send.bulletId(), send.worker(), send.workerId(), ms(send.started() - send.received()),
                            ms(send.orderWaitNanos()), ms(send.authNanos()), ms(send.videoNanos()), ms(send.mqDone() - send.mqStarted()),
                            ms(timing.enqueued() - send.mqDone()), ms(flushStarted - timing.enqueued()),
                            ms(dispatchStarted - flushStarted), ms(dispatchDone - dispatchStarted),
                            ms(done - dispatchStarted), ms(elapsed), items, bytes, viewers, nonWritable, failed);
                }
            }
        });
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private void discard(Room room) {
        ++room.generation;
        if (room.timer != null) room.timer.cancel(false);
        room.timer = null; room.pending.clear(); room.bytes = ENVELOPE_BYTES;
    }

    private void online(Room room) {
        DanmakuMetrics.onlineFrames.increment();
        room.channels.writeAndFlush(new TextWebSocketFrame("{\"type\":1,\"data\":" + room.channels.size() + "}"));
    }

    private void scheduleOnline(Room room) {
        if (room.onlineTimer != null) return;
        room.onlineTimer = scheduler.schedule(() -> {
            synchronized (room) {
                room.onlineTimer = null;
                if (!room.disposed && !closed.get()) online(room);
            }
        }, 1000, TimeUnit.MILLISECONDS);
    }
    private void cancelOnline(Room room) {
        if (room.onlineTimer != null) room.onlineTimer.cancel(false);
        room.onlineTimer = null;
    }

    int roomCount() { return rooms.size(); }

    @Override @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Room room : rooms.values()) {
            synchronized (room) { flush(room); discard(room); cancelOnline(room); room.disposed = true; }
            room.channels.close().awaitUninterruptibly();
        }
        rooms.clear(); scheduler.shutdownNow();
    }
}
