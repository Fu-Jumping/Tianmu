package com.tianmu.agent.video.content;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.TranscriptionPort;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.tianmu.agent.video.content.ContentWork.*;

/** One actual in-flight invocation per instance; cancellation does not free its slot before exit. */
public final class ContentTranscriptionWorker implements AutoCloseable {
    public enum Outcome { NO_WORK,BUSY,CLOSED,SUCCEEDED,FAILED }
    private final MysqlContentWorkStore store;
    private final ContentWorkService service;
    private final ContentProcessingMediaPort media;
    private final TranscriptionPort transcription;
    private final ContentTranscriptStore results;
    private final String worker,stageVersion;
    private final Duration leaseDuration,heartbeat;
    private final AtomicBoolean busy=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicReference<CancellationToken> active=new AtomicReference<>();
    public ContentTranscriptionWorker(MysqlContentWorkStore store,ContentWorkService service,ContentProcessingMediaPort media,
            TranscriptionPort transcription,ContentTranscriptStore results,String worker,String acceptedStageVersion,
            Duration leaseDuration,Duration heartbeat) {
        ContentWork.identifier(worker);ContentWork.identifier(acceptedStageVersion);
        if(leaseDuration==null || heartbeat==null || leaseDuration.getNano()!=0 || heartbeat.getNano()!=0
                || heartbeat.getSeconds()<1 || leaseDuration.getSeconds()>600 || leaseDuration.compareTo(heartbeat.multipliedBy(3))<0)
            throw new IllegalArgumentException("Invalid worker lease policy");
        this.store=Objects.requireNonNull(store);this.service=Objects.requireNonNull(service);this.media=Objects.requireNonNull(media);
        this.transcription=Objects.requireNonNull(transcription);this.results=Objects.requireNonNull(results);
        this.worker=worker;this.stageVersion=acceptedStageVersion;this.leaseDuration=leaseDuration;this.heartbeat=heartbeat;
    }
    public Outcome runOnce(CallControl parent) {
        if(closed.get())return Outcome.CLOSED;
        if(!busy.compareAndSet(false,true))return Outcome.BUSY;
        CancellationToken token=new CancellationToken();active.set(token);
        ScheduledExecutorService renew=null;Lease lease=null;
        try(var registration=parent.cancellation().onCancel(()->token.cancel(parent.cancellation().reason()))) {
            if(closed.get())return Outcome.CLOSED;
            CallControl claimControl=new CallControl(parent.deadline(),token,parent.budget(),parent.externalCallStarted());
            Optional<Lease> next=service.claim(worker,leaseDuration,Stage.TRANSCRIBE,stageVersion,claimControl);
            if(next.isEmpty())return Outcome.NO_WORK;
            lease=next.get();Job job=store.requireLease(lease);
            Instant deadline=Collections.min(List.of(parent.deadline(),job.deadline(),job.grant().expiresAt()));
            CallControl base=new CallControl(deadline,token,parent.budget(),parent.externalCallStarted());
            Lease owned=lease;
            renew=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"content-lease");t.setDaemon(true);return t;});
            renew.scheduleWithFixedDelay(()->{
                try {
                    if(!Instant.now().isBefore(deadline))throw new IllegalStateException("Worker deadline reached");
                    service.requireDispatch(owned,base);
                    if(!store.heartbeat(owned,leaseDuration))throw new IllegalStateException("Lease lost");
                }catch(Exception lost){token.cancel(StopReason.ADAPTER_TIMEOUT);}
            },heartbeat.toMillis(),heartbeat.toMillis(),TimeUnit.MILLISECONDS);
            checkDeadline(base);
            if(job.result()!=null) {
                results.verify(job,job.result());
                return service.complete(lease,job.result(),base)?Outcome.SUCCEEDED:Outcome.FAILED;
            }
            var clip=media.audio(job.grant(),job.unit().startMs(),job.unit().endMs(),base);
            checkDeadline(base);
            if(clip==null || clip.source()==null || clip.source().videoId()!=job.unit().asset().videoId() || !job.unit().asset().sourceVersion().equals(clip.source().sourceVersion())
                    || clip.source().durationMs()<job.unit().endMs()
                    || clip.startMs()!=job.unit().startMs() || clip.endMs()!=job.unit().endMs() || clip.audioBase64()==null || clip.audioBase64().isBlank())
                throw new AgentException("CONTENT_AUDIO_UNAVAILABLE","No usable audio for this media range");
            byte[] audio=Base64.getDecoder().decode(clip.audioBase64());
            if(audio.length==0 || audio.length>4_000_000)throw new AgentException("CONTENT_AUDIO_UNAVAILABLE","Audio size is invalid");
            WaveClip.requireNormalized(audio,clip.endMs()-clip.startMs());
            service.requireDispatch(lease,base);
            CallControl modelControl=base.withDispatchBarrier(()->{parent.beforeDispatch();service.requireDispatch(owned,base);});
            modelControl.beforeDispatch();
            TimedTranscript transcript=transcription.transcribeTimed(clip.audioBase64(),clip.startMs(),clip.endMs(),modelControl);
            checkDeadline(base);store.requireLease(lease);
            Receipt receipt=results.save(job,lease,transcript);
            if(!store.stageResult(lease,receipt))return Outcome.FAILED;
            return service.complete(lease,receipt,base)?Outcome.SUCCEEDED:Outcome.FAILED;
        }catch(Exception failure) {
            if(lease!=null) {
                String code=failure instanceof AgentException a?a.code():"CONTENT_PROCESSING_FAILED";
                if(!code.matches("[A-Z0-9_]{1,64}"))code="CONTENT_PROCESSING_FAILED";
                boolean retryable=token.reason()==StopReason.SHUTDOWN || Set.of("CONTENT_AUTHORITY_UNAVAILABLE","BUSINESS_UNAVAILABLE","VIDEO_MODEL_UNAVAILABLE","VIDEO_TRANSCRIPTION_FAILED").contains(code);
                store.fail(lease,code,retryable,heartbeat);
            }
            return Outcome.FAILED;
        }finally {
            token.cancel(StopReason.SHUTDOWN);
            if(renew!=null) {
                renew.shutdownNow();boolean interrupted=false;
                while(!renew.isTerminated())try{renew.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
            active.compareAndSet(token,null);busy.set(false);
        }
    }
    public void close(){closed.set(true);CancellationToken token=active.get();if(token!=null)token.cancel(StopReason.SHUTDOWN);}
    private static void checkDeadline(CallControl control) {
        if(!Instant.now().isBefore(control.deadline()))control.cancellation().cancel(StopReason.DEADLINE_EXCEEDED);
        control.checkpoint();
    }
}
