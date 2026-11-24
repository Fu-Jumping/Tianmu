package com.orangecode.tianmu.consumer;

import com.orangecode.tianmu.common.DanmakuMetrics;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletIngestionService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class BulletWriteScheduler implements AutoCloseable {
    private static final int WORKERS = 4;
    private static final int MAX_BATCH = BulletIngestionService.MAX_BATCH_SIZE;
    private static final int MAX_PER_VIDEO = 4 * MAX_BATCH;
    private static final int MAX_OUTSTANDING = 8 * MAX_BATCH;
    private static final long MERGE_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private record Delivery(SendBulletRequest event, long receivedAt, CompletableFuture<Boolean> committed) {}
    private record Batch(long videoId, List<Delivery> deliveries) {}

    private static final class VideoQueue {
        final ArrayDeque<Delivery> pending = new ArrayDeque<>();
        int outstanding;
        boolean writing;
    }

    private final BulletIngestionService ingestion;
    private final ExecutorService workers;
    private final LinkedHashMap<Long, VideoQueue> videos = new LinkedHashMap<>();
    private int outstanding;
    private boolean closed;

    public BulletWriteScheduler(BulletIngestionService ingestion) {
        this.ingestion = ingestion;
        var nextId = new AtomicInteger();
        workers = Executors.newFixedThreadPool(WORKERS, task -> {
            var thread = new Thread(task, "danmaku-writer-" + nextId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < WORKERS; i++) workers.execute(this::runWorker);
    }

    /** Admission is atomic for the callback. Capacity includes work already writing to MySQL. */
    public synchronized List<CompletableFuture<Boolean>> submit(List<SendBulletRequest> events) {
        if (closed || events.size() > MAX_OUTSTANDING - outstanding) throw saturated();
        var incoming = new HashMap<Long, Integer>();
        for (var event : events) incoming.merge(event.getVideoId(), 1, Integer::sum);
        for (var entry : incoming.entrySet()) {
            var queue = videos.get(entry.getKey());
            int existing = queue == null ? 0 : queue.outstanding;
            if (entry.getValue() > MAX_PER_VIDEO - existing) throw saturated();
        }

        long now = System.nanoTime();
        var results = new ArrayList<CompletableFuture<Boolean>>(events.size());
        for (var event : events) {
            var queue = videos.computeIfAbsent(event.getVideoId(), id -> new VideoQueue());
            var committed = new CompletableFuture<Boolean>();
            queue.pending.addLast(new Delivery(event, now, committed));
            queue.outstanding++;
            results.add(committed);
        }
        outstanding += events.size();
        notifyAll();
        return results;
    }

    private synchronized Batch take() throws InterruptedException {
        while (!closed) {
            long waitNanos = Long.MAX_VALUE;
            long now = System.nanoTime();
            for (var entry : videos.entrySet()) {
                var queue = entry.getValue();
                if (queue.writing || queue.pending.isEmpty()) continue;
                long remaining = MERGE_NANOS - (now - queue.pending.getFirst().receivedAt());
                if (queue.pending.size() < MAX_BATCH && remaining > 0) {
                    waitNanos = Math.min(waitNanos, remaining);
                    continue;
                }
                var deliveries = new ArrayList<Delivery>(Math.min(MAX_BATCH, queue.pending.size()));
                while (deliveries.size() < MAX_BATCH && !queue.pending.isEmpty()) {
                    deliveries.add(queue.pending.removeFirst());
                }
                queue.writing = true;
                return new Batch(entry.getKey(), deliveries);
            }
            if (waitNanos == Long.MAX_VALUE) wait();
            else TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
        }
        return null;
    }

    private void runWorker() {
        try {
            Batch batch;
            while ((batch = take()) != null) {
                List<Boolean> results = Collections.nCopies(batch.deliveries().size(), false);
                try {
                    results = write(batch);
                } finally {
                    release(batch);
                    for (int i = 0; i < batch.deliveries().size(); i++) {
                        batch.deliveries().get(i).committed().complete(results.get(i));
                    }
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Boolean> write(Batch batch) {
        var events = batch.deliveries().stream().map(Delivery::event).toList();
        long started = System.nanoTime();
        for (var delivery : batch.deliveries())
            DanmakuMetrics.observe(DanmakuMetrics.Stage.DB_WRITE_QUEUE, started - delivery.receivedAt());
        try {
            transaction(() -> ingestion.persistBatch(events));
            observeCommit(events);
            log.debug("Danmaku batch committed size={}", events.size());
            return Collections.nCopies(events.size(), true);
        } catch (IllegalArgumentException | DataIntegrityViolationException invalid) {
            if (events.size() == 1) return List.of(false);
            // The batch rolled back. Isolate invalid messages before allowing another batch for this video.
            var results = new ArrayList<Boolean>(events.size());
            for (var event : events) {
                try {
                    transaction(() -> ingestion.persist(event));
                    observeCommit(List.of(event));
                    results.add(true);
                } catch (RuntimeException failure) {
                    results.add(false);
                }
            }
            return results;
        } catch (RuntimeException unavailable) {
            log.warn("Danmaku batch storage failed size={} cause={}", events.size(), unavailable.getClass().getSimpleName());
            return Collections.nCopies(events.size(), false);
        }
    }

    private void transaction(Runnable write) {
        long started = System.nanoTime();
        try {
            write.run();
        } finally {
            DanmakuMetrics.observe(DanmakuMetrics.Stage.DB_TRANSACTION, System.nanoTime() - started);
        }
    }

    private void observeCommit(List<SendBulletRequest> events) {
        long committedAt = System.currentTimeMillis();
        for (var event : events) {
            if (event.getCreateTime() != null) {
                long elapsed = committedAt - event.getCreateTime().getTime();
                if (elapsed >= 0) DanmakuMetrics.observe(DanmakuMetrics.Stage.EVENT_TO_COMMIT, TimeUnit.MILLISECONDS.toNanos(elapsed));
            }
        }
    }

    private synchronized void release(Batch batch) {
        var queue = videos.remove(batch.videoId());
        queue.writing = false;
        queue.outstanding -= batch.deliveries().size();
        outstanding -= batch.deliveries().size();
        if (!queue.pending.isEmpty()) videos.put(batch.videoId(), queue);
        notifyAll();
    }

    private static RejectedExecutionException saturated() {
        return new RejectedExecutionException("Danmaku write queue is full or stopped");
    }

    @PreDestroy
    @Override
    public void close() {
        List<Delivery> abandoned = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (var queue : videos.values()) {
                abandoned.addAll(queue.pending);
                queue.outstanding -= queue.pending.size();
                queue.pending.clear();
            }
            outstanding -= abandoned.size();
            videos.values().removeIf(queue -> !queue.writing);
            notifyAll();
        }
        workers.shutdownNow();
        for (var delivery : abandoned) delivery.committed().complete(false);
    }
}
