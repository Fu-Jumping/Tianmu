package com.tianmu.agent.video.notes;

import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import com.tianmu.agent.video.content.ContentWork;
import io.opentelemetry.api.trace.Tracer;
import java.time.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A single bounded poller. Provider calls retain the worker slot until they actually return. */
public final class NotesWorkerScheduler implements AutoCloseable {
    private static final Logger LOG=LoggerFactory.getLogger(NotesWorkerScheduler.class);
    private final MysqlNotesJobStore store;
    private final NotesCoordinator coordinator;
    private final NotesSynthesisCoordinator synthesis;
    private final NotesSourceWorker sources;
    private final NotesSynthesisWorker summaries;
    private final Tracer tracer;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"video-notes-worker");t.setDaemon(true);return t;});
    public NotesWorkerScheduler(MysqlNotesJobStore store,NotesCoordinator coordinator,NotesSynthesisCoordinator synthesis,
                                NotesSourceWorker sources,NotesSynthesisWorker summaries,Tracer tracer) {
        this.store=store;this.coordinator=coordinator;this.synthesis=synthesis;this.sources=sources;this.summaries=summaries;this.tracer=tracer;
    }
    public void start(){executor.scheduleWithFixedDelay(this::tick,0,2,TimeUnit.SECONDS);}
    private CallControl control(){return new CallControl(Instant.now().plusSeconds(900),new CancellationToken());}
    private void tick(){
        try {
            store.expireActive(32);store.recoverExpiredDispatches(32);
            for(var job:store.scanActive(32)) {
                var span=tracer.spanBuilder("video.notes.advance").startSpan();
                span.setAttribute("notes.job_id",job.id().toString());span.setAttribute("video.id",job.request().asset().videoId());
                try(var ignored=span.makeCurrent()) {
                    var result=coordinator.advance(job.id(),job.request().tenantId(),job.request().userId(),control());
                    span.setAttribute("notes.outcome",result.name());
                    if(result==NotesCoordinator.Outcome.MATERIALS_READY)
                        synthesis.advance(job.id(),job.request().tenantId(),job.request().userId(),control());
                } catch(RuntimeException failure){
                    String code=failure instanceof com.tianmu.agent.core.AgentException e?e.code():"NOTES_ADVANCEMENT_FAILED";
                    span.setAttribute("notes.error_code",code);span.recordException(failure);
                    LOG.warn("Notes advancement failed for job {}: {}",job.id(),code);
                }
                finally{span.end();}
            }
            runStage("TRANSCRIBE",()->sources.runOnce(ContentWork.Stage.TRANSCRIBE,control()).name());
            runStage("VISION",()->sources.runOnce(ContentWork.Stage.VISION,control()).name());
            runStage("SUMMARY",()->summaries.runOnce(control()).name());
        }catch(RuntimeException failure){LOG.warn("Notes worker polling failed: {}",failure.getClass().getSimpleName());}
    }
    private void runStage(String stage,java.util.function.Supplier<String> action){
        var span=tracer.spanBuilder("video.notes.worker").startSpan();span.setAttribute("notes.stage",stage);
        try(var ignored=span.makeCurrent()){span.setAttribute("notes.outcome",action.get());}
        catch(RuntimeException failure){span.recordException(failure);throw failure;}finally{span.end();}
    }
    @Override public void close(){sources.close();summaries.close();executor.shutdown();}
}
