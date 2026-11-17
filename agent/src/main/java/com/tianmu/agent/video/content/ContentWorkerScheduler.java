package com.tianmu.agent.video.content;

import com.tianmu.agent.runtime.control.CallControl;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Explicit host-owned polling; a tick never overlaps another tick or releases an in-flight worker. */
public final class ContentWorkerScheduler implements AutoCloseable {
    private final ContentTranscriptionWorker worker;
    private final Supplier<CallControl> control;
    private final Consumer<String> report;
    private final Duration interval;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started=new AtomicBoolean(),closed=new AtomicBoolean();
    public ContentWorkerScheduler(ContentTranscriptionWorker worker,Supplier<CallControl> control,Consumer<String> report,Duration interval) {
        this.worker=Objects.requireNonNull(worker);this.control=Objects.requireNonNull(control);this.report=Objects.requireNonNull(report);
        if(interval==null || interval.compareTo(Duration.ofSeconds(1))<0 || interval.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("Invalid content worker poll interval");
        this.interval=interval;
        this.executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"content-worker-poll");t.setDaemon(true);return t;});
    }
    public synchronized void start() {
        if(closed.get())throw new IllegalStateException("Scheduler closed");
        if(started.compareAndSet(false,true))executor.scheduleWithFixedDelay(this::tick,0,interval.toMillis(),TimeUnit.MILLISECONDS);
    }
    private void tick() {
        if(closed.get())return;
        try {
            if(worker.runOnce(Objects.requireNonNull(control.get()))==ContentTranscriptionWorker.Outcome.FAILED)signal("WORK_FAILED");
        } catch(RuntimeException failure){signal("SCHEDULER_ERROR");}
    }
    private void signal(String event){try{report.accept(event);}catch(RuntimeException ignored){/* reporting cannot stop the poll loop */}}
    @Override public synchronized void close() {
        if(closed.compareAndSet(false,true)){worker.close();executor.shutdown();}
    }
    /** Closing requests cancellation; the host must wait before releasing shared dependencies. */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        if(timeout==null || timeout.isNegative())throw new IllegalArgumentException("Invalid shutdown timeout");
        return executor.awaitTermination(timeout.toMillis(),TimeUnit.MILLISECONDS);
    }
}
