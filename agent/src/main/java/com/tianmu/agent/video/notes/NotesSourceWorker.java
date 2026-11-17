package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Source-specific processing; lifetime and capacity are shared through NotesLeaseRunner. */
public final class NotesSourceWorker implements AutoCloseable {
    public enum Outcome { NO_WORK, BUSY, CLOSED, SUCCEEDED, FAILED }
    private final MysqlNotesJobStore notes;
    private final MysqlContentWorkStore content;
    private final ContentWorkService authority;
    private final NotesSourceProcessor processor;
    private final NotesSourceStore results;
    private final NotesCoordinator.ProcessingGrants processing;
    private final long reservationMicros;
    private final NotesLeaseRunner runner;
    public NotesSourceWorker(MysqlNotesJobStore notes,MysqlContentWorkStore content,ContentWorkService authority,
                             NotesSourceProcessor processor,NotesSourceStore results,NotesCoordinator.ProcessingGrants processing,String worker,String version,
                             Duration leaseDuration,Duration heartbeat,long reservationMicros) {
        this.notes=Objects.requireNonNull(notes);this.content=Objects.requireNonNull(content);this.authority=Objects.requireNonNull(authority);
        this.processor=Objects.requireNonNull(processor);this.results=Objects.requireNonNull(results);this.processing=Objects.requireNonNull(processing);
        if(reservationMicros<1)throw new IllegalArgumentException("Invalid reservation");this.reservationMicros=reservationMicros;
        runner=new NotesLeaseRunner(notes,content,authority,worker,version,leaseDuration,heartbeat);
    }
    public Outcome runOnce(ContentWork.Stage stage,CallControl parent) {
        if(stage!=ContentWork.Stage.TRANSCRIBE && stage!=ContentWork.Stage.VISION)throw new IllegalArgumentException("Not a source stage");
        return Outcome.valueOf(runner.runOnce(stage,parent,(job,bound,lease,control)->{
            var root=bound.job();processing.requireRoot(root,control);var call=notes.prepareCall(root.id(),root.request().tenantId(),root.request().userId(),root.generation(),bound.unit().segmentId(root.id()),reservationMicros);
            var dispatched=new AtomicBoolean();
            try{
                if(call.state()==NotesJob.CallState.RESULT_STAGED || call.state()==NotesJob.CallState.COMMITTED){
                    var receipt=new ContentWork.Receipt(call.resultRef(),call.resultDigest());results.read(job,receipt);authority.requireDispatch(lease,control);
                    return content.stageResult(lease,receipt) && notes.commitCall(call.id(),call.reservedMicros()) && authority.complete(lease,receipt,control);
                }
                if(call.state()!=NotesJob.CallState.PREPARED)throw new AgentException("NOTES_CALL_UNKNOWN","Unresolved call must not be resent");
                var media=processor.prepare(job,control);NotesLeaseRunner.check(control);
                var modelControl=control.withDispatchBarrier(()->{
                    NotesLeaseRunner.check(control);parent.beforeDispatch();processing.requireRoot(root,control);authority.requireDispatch(lease,control);
                    if(!dispatched.get()){if(!notes.beginWorkDispatch(call.id(),lease))throw new AgentException("NOTES_DISPATCH_CONFLICT","Dispatch barrier rejected");dispatched.set(true);}
                });
                modelControl.beforeDispatch();var evidence=processor.invoke(job,media,modelControl);NotesLeaseRunner.check(control);processing.requireRoot(root,control);content.requireLease(lease);
                var receipt=results.save(job,lease,evidence);
                return notes.stageWorkResult(call.id(),lease,receipt.reference(),receipt.digest()) && content.stageResult(lease,receipt)
                        && notes.commitCall(call.id(),call.reservedMicros()) && authority.complete(lease,receipt,control);
            }catch(RuntimeException failure){if(dispatched.get())notes.markUnknown(call.id());throw failure;}
        }).name());
    }
    public void close(){runner.close();}
}
