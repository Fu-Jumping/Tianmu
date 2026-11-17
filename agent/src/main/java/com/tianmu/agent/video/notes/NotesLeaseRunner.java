package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Shared notes worker lifetime; scheduling and leases still belong to ContentWork. */
public final class NotesLeaseRunner implements AutoCloseable {
    public enum Outcome { NO_WORK, BUSY, CLOSED, SUCCEEDED, FAILED }
    @FunctionalInterface public interface Action {
        boolean run(ContentWork.Job work,MysqlNotesJobStore.BoundUnit bound,ContentWork.Lease lease,CallControl control);
    }
    private final MysqlNotesJobStore notes;
    private final MysqlContentWorkStore content;
    private final ContentWorkService authority;
    private final String worker,version;
    private final Duration leaseDuration,heartbeat;
    private final AtomicBoolean busy=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicReference<CancellationToken> active=new AtomicReference<>();
    public NotesLeaseRunner(MysqlNotesJobStore notes,MysqlContentWorkStore content,ContentWorkService authority,
                            String worker,String version,Duration leaseDuration,Duration heartbeat) {
        this.notes=Objects.requireNonNull(notes);this.content=Objects.requireNonNull(content);this.authority=Objects.requireNonNull(authority);
        if(worker==null || !worker.matches("[A-Za-z0-9_.:-]{1,128}") || version==null || !version.matches("[A-Za-z0-9_.:-]{1,128}")
                || leaseDuration==null || heartbeat==null || heartbeat.getNano()!=0 || heartbeat.getSeconds()<1
                || leaseDuration.getNano()!=0 || leaseDuration.getSeconds()>600 || leaseDuration.compareTo(heartbeat.multipliedBy(3))<0)
            throw new IllegalArgumentException("Invalid notes lease policy");
        this.worker=worker;this.version=version;this.leaseDuration=leaseDuration;this.heartbeat=heartbeat;
    }
    public Outcome runOnce(ContentWork.Stage stage,CallControl parent,Action action) {
        Objects.requireNonNull(stage);Objects.requireNonNull(parent);Objects.requireNonNull(action);
        if(closed.get())return Outcome.CLOSED;if(!busy.compareAndSet(false,true))return Outcome.BUSY;
        var token=new CancellationToken();active.set(token);ContentWork.Lease lease=null;ScheduledExecutorService renew=null;
        try(var registration=parent.cancellation().onCancel(()->token.cancel(parent.cancellation().reason()))) {
            if(closed.get())return Outcome.CLOSED;
            var next=authority.claim(worker,leaseDuration,stage,version,new CallControl(parent.deadline(),token,parent.budget(),parent.externalCallStarted()));
            if(next.isEmpty())return Outcome.NO_WORK;
            lease=next.get();var job=content.requireLease(lease);
            var bound=notes.lookupBoundWork(job.id()).orElseThrow(()->new AgentException("NOTES_UNBOUND_WORK","Work not bound to active notes"));
            io.opentelemetry.api.trace.Span.current().setAttribute("notes.job_id",bound.job().id().toString());
            io.opentelemetry.api.trace.Span.current().setAttribute("notes.work_id",job.id().toString());
            Instant deadline=Collections.min(List.of(parent.deadline(),job.deadline(),job.grant().expiresAt(),bound.job().request().deadline()));
            var control=new CallControl(deadline,token,parent.budget(),parent.externalCallStarted());var owned=lease;
            renew=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"notes-work-lease");t.setDaemon(true);return t;});
            renew.scheduleWithFixedDelay(()->{
                try{check(control);authority.requireDispatch(owned,control);if(!content.heartbeat(owned,leaseDuration))throw new IllegalStateException("Lease lost");}
                catch(Exception failure){token.cancel(StopReason.ADAPTER_TIMEOUT);}
            },heartbeat.toMillis(),heartbeat.toMillis(),TimeUnit.MILLISECONDS);
            check(control);return action.run(job,bound,lease,control)?Outcome.SUCCEEDED:Outcome.FAILED;
        }catch(Exception failure){
            io.opentelemetry.api.trace.Span.current().setAttribute("notes.error_code",failure instanceof AgentException e?e.code():"NOTES_WORK_FAILED");
            if(lease!=null){String code=failure instanceof AgentException e?e.code():"NOTES_WORK_FAILED";if(!code.matches("[A-Z0-9_]{1,64}"))code="NOTES_WORK_FAILED";content.fail(lease,code,false,heartbeat);}
            return Outcome.FAILED;
        }finally{
            token.cancel(StopReason.SHUTDOWN);
            if(renew!=null){renew.shutdownNow();boolean interrupted=false;while(!renew.isTerminated())try{renew.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            active.compareAndSet(token,null);busy.set(false);
        }
    }
    public static void check(CallControl control){
        if(!Instant.now().isBefore(control.deadline()))control.cancellation().cancel(StopReason.DEADLINE_EXCEEDED);control.checkpoint();
    }
    public void close(){closed.set(true);var token=active.get();if(token!=null)token.cancel(StopReason.SHUTDOWN);}
}
