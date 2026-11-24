package com.orangecode.tianmu.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Process-wide metrics. Logs contain no credentials, user IDs or message bodies. */
@Component
public class DanmakuMetrics {
    public enum Stage {
        WS_QUEUE, WS_HANDLER, JSON_PARSE, AUTH, VIDEO_CHECK, MQ_SEND, MQ_SUBMIT, MQ_CALLBACK_QUEUE, MQ_REORDER_WAIT, WS_SEND_ORDER_WAIT,
        MQ_TO_BATCH, BATCH_WAIT, FLUSH_TIMER_LAG, FLUSH_TIMER_LOCK, FLUSH_PREPARE, FLUSH_DISPATCH,
        BROADCAST_WRITE, WS_TO_WRITE_COMPLETE, DB_WRITE_QUEUE, DB_BATCH_BODY,
        DB_TRANSACTION, DB_VIDEO_LOCK, DB_INSERT, DB_STATS, EVENT_TO_COMMIT
    }

    private static final Logger log = LoggerFactory.getLogger(DanmakuMetrics.class);
    private static final long[] BOUNDS_MS = {1, 2, 5, 10, 20, 50, 100, 150, 200, 300, 500, 750, 1000,
            1500, 2000, 3000, 5000, 8000, 12000, 20000, 30000, 60000};
    private static final long[] BOUNDS_NANOS = Arrays.stream(BOUNDS_MS).map(TimeUnit.MILLISECONDS::toNanos).toArray();
    private static final Histogram[] histograms = Arrays.stream(Stage.values()).map(stage -> new Histogram())
            .toArray(Histogram[]::new);
    private static final AtomicLong lastSlowLog = new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(1));
    public static final LongAdder rejected = new LongAdder(), writeFailures = new LongAdder(), nonWritable = new LongAdder();
    public static final LongAdder frames = new LongAdder(), recipients = new LongAdder(), onlineFrames = new LongAdder();
    public static final LongAdder slowLogsSuppressed = new LongAdder(), broadcastSkipped = new LongAdder();
    public static final LongAdder dbRejected = new LongAdder(), dbAckWaitTimeout = new LongAdder();
    public static final LongAdder mqAdmissionRejected = new LongAdder(), mqFailed = new LongAdder(), mqCallbackRejected = new LongAdder();
    public static final AtomicLong pendingTasks = new AtomicLong();

    private static Map<String, Integer> workerDepths = Map.of();
    private static final Map<String, Integer> workerPeaks = new LinkedHashMap<>();

    public static void observe(Stage stage, long elapsedNanos) {
        if (elapsedNanos >= 0) histograms[stage.ordinal()].add(elapsedNanos);
    }

    public static String workerId(Object executor) {
        return Integer.toHexString(System.identityHashCode(executor));
    }

    public static synchronized void sampleWorkers(Map<String, Integer> depths) {
        workerDepths = new LinkedHashMap<>(depths);
        depths.forEach((id, depth) -> workerPeaks.merge(id, depth, Math::max));
        pendingTasks.set(depths.values().stream().mapToLong(Integer::longValue).sum());
    }

    private static synchronized String workerWindow() {
        String result = "worker_pending=" + workerDepths + " worker_sampled_peaks=" + workerPeaks;
        workerPeaks.clear();
        return result;
    }

    public static boolean allowSlowLog(long elapsedNanos) {
        long second = TimeUnit.SECONDS.toNanos(1);
        if (elapsedNanos < second) return false;
        long now = System.nanoTime();
        long previous = lastSlowLog.get();
        if (now - previous >= second && lastSlowLog.compareAndSet(previous, now)) return true;
        slowLogsSuppressed.increment();
        return false;
    }

    public static Map<String, Long> snapshot() {
        var result = new LinkedHashMap<>(Map.of("rejected", rejected.sum(), "writeFailures", writeFailures.sum(),
                "nonWritableObservations", nonWritable.sum(), "broadcastFrames", frames.sum(),
                "recipientAttempts", recipients.sum(), "onlineBroadcasts", onlineFrames.sum(),
                "pendingTasks", pendingTasks.get(), "slowLogsSuppressed", slowLogsSuppressed.sum(),
                "broadcastSkipped", broadcastSkipped.sum()));
        result.put("dbRejected", dbRejected.sum());
        result.put("dbAckWaitTimeout", dbAckWaitTimeout.sum());
        result.put("mqAdmissionRejected", mqAdmissionRejected.sum());
        result.put("mqFailed", mqFailed.sum());
        result.put("mqCallbackRejected", mqCallbackRejected.sum());
        for (var stage : Stage.values()) histograms[stage.ordinal()].totals(stage, result);
        return result;
    }

    @Scheduled(fixedDelay = 30000)
    public void report() {
        log.info("danmaku_metrics {}", snapshot());
        log.info("danmaku_ws_queue {}", workerWindow());
        var windows = new LinkedHashMap<Stage, String>();
        for (var stage : Stage.values()) {
            String window = histograms[stage.ordinal()].drain();
            if (window != null) windows.put(stage, window);
        }
        if (!windows.isEmpty()) log.info("danmaku_latency_30s {}", windows);
    }

    private static final class Histogram {
        private final long[] buckets = new long[BOUNDS_NANOS.length + 1];
        private long count, totalNanos, windowCount, maxNanos;

        synchronized void add(long nanos) {
            int bucket = Arrays.binarySearch(BOUNDS_NANOS, nanos);
            if (bucket < 0) bucket = -bucket - 1;
            buckets[bucket]++;
            count++;
            totalNanos += nanos;
            windowCount++;
            maxNanos = Math.max(maxNanos, nanos);
        }

        synchronized void totals(Stage stage, Map<String, Long> output) {
            output.put(stage.name() + "_count", count);
            output.put(stage.name() + "_nanos", totalNanos);
        }

        synchronized String drain() {
            if (windowCount == 0) return null;
            String window = "n=" + windowCount + " p50_le_ms=" + percentile(50) + " p95_le_ms=" + percentile(95)
                    + " p99_le_ms=" + percentile(99) + " max_ms=" + millisCeiling(maxNanos);
            windowCount = 0;
            maxNanos = 0;
            Arrays.fill(buckets, 0);
            return window;
        }

        private long percentile(int percent) {
            long target = (windowCount * percent + 99) / 100;
            long seen = 0;
            for (int i = 0; i < BOUNDS_MS.length; i++) {
                seen += buckets[i];
                if (seen >= target) return BOUNDS_MS[i];
            }
            return millisCeiling(maxNanos);
        }

        private static long millisCeiling(long nanos) {
            long unit = TimeUnit.MILLISECONDS.toNanos(1);
            return nanos / unit + (nanos % unit == 0 ? 0 : 1);
        }
    }
}
