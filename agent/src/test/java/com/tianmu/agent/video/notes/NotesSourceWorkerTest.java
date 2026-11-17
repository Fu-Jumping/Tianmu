package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.tianmu.agent.video.content.ContentWork.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesSourceWorkerTest {
    final MysqlNotesJobStore notes=mock(MysqlNotesJobStore.class);
    final MysqlContentWorkStore content=mock(MysqlContentWorkStore.class);
    final ContentWorkService authority=mock(ContentWorkService.class);
    final NotesSourceProcessor processor=mock(NotesSourceProcessor.class);
    final NotesSourceStore results=mock(NotesSourceStore.class);
    final NotesCoordinator.ProcessingGrants processing=mock(NotesCoordinator.ProcessingGrants.class);
    final UUID rootId=UUID.randomUUID(),workId=UUID.randomUUID(),callId=UUID.randomUUID();
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"source","public",0);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.PlanUnit plan=new NotesJob.PlanUnit("audio:0",Stage.TRANSCRIBE,0,1000,0,1000,"a".repeat(64));
    final Unit unit=new Unit(asset,Stage.TRANSCRIBE,plan.segmentId(rootId),"analysis",0,1000);
    final Instant deadline=Instant.now().plusSeconds(300);
    final Lease lease=new Lease(workId,1,"notes-worker");
    final Job job=new Job(workId,unit,new Grant(UUID.randomUUID(),asset,deadline),Status.RUNNING,1,2,1,"notes-worker",deadline,deadline,null,null);
    final Receipt receipt=new Receipt("notes-source:"+workId+":1","b".repeat(64));
    NotesSourceWorker worker() {
        return new NotesSourceWorker(notes,content,authority,processor,results,processing,"notes-worker","analysis",Duration.ofSeconds(30),Duration.ofSeconds(5),1000);
    }
    CallControl control() { return new CallControl(deadline,new CancellationToken()); }
    void setup(NotesJob.CallState state) {
        var request=new NotesJob.Create("tenant","viewer",asset,1000,policy,"request",deadline);
        var root=new NotesJob.Job(rootId,request,"a".repeat(64),NotesJob.Status.RUNNING,1,1,"a".repeat(64),1000,0,null);
        when(authority.claim(anyString(),any(),eq(Stage.TRANSCRIBE),eq("analysis"),any())).thenReturn(Optional.of(lease));
        when(content.requireLease(lease)).thenReturn(job);
        when(notes.lookupBoundWork(workId)).thenReturn(Optional.of(new MysqlNotesJobStore.BoundUnit(root,plan)));
        when(notes.prepareCall(eq(rootId),eq("tenant"),eq("viewer"),eq(1),anyString(),eq(1000L)))
                .thenReturn(new NotesJob.Call(callId,rootId,1,plan.segmentId(rootId),state,1000,receipt.reference(),receipt.digest()));
        when(notes.beginWorkDispatch(callId,lease)).thenReturn(true);
        when(notes.stageWorkResult(eq(callId),eq(lease),anyString(),anyString())).thenReturn(true);
        when(content.stageResult(lease,receipt)).thenReturn(true);
        when(authority.complete(eq(lease),eq(receipt),any())).thenReturn(true);
        when(notes.commitCall(callId,1000)).thenReturn(true);
        when(results.save(eq(job),eq(lease),any())).thenReturn(receipt);
        when(processor.prepare(eq(job),any())).thenReturn(new VideoContent.Media(new VideoContent.Source(1,"source",1000),0,1000,List.of(),"AQ=="));
    }
    @Test void resultIsStagedBeforeCompletionAndCeilingAccounting() {
        setup(NotesJob.CallState.PREPARED);
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.SUCCEEDED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        var order=inOrder(notes,processor,results,content,authority);
        order.verify(notes).beginWorkDispatch(callId,lease);
        order.verify(processor).invoke(eq(job),any(),any());
        order.verify(results).save(eq(job),eq(lease),any());
        order.verify(notes).stageWorkResult(callId,lease,receipt.reference(),receipt.digest());
        order.verify(content).stageResult(lease,receipt);
        order.verify(notes).commitCall(callId,1000);
        order.verify(authority).complete(eq(lease),eq(receipt),any());
    }
    @Test void stagedReceiptRecoveryNeverInvokesProcessor() {
        setup(NotesJob.CallState.RESULT_STAGED);
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.SUCCEEDED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verify(results).read(job,receipt); verifyNoInteractions(processor);
        verify(notes,never()).beginWorkDispatch(any(),any());
    }
    @Test void committedAccountingAfterCrashRecoversWithoutAnotherModelOrCharge() {
        setup(NotesJob.CallState.COMMITTED);
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.SUCCEEDED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verifyNoInteractions(processor); verify(results).read(job,receipt);
        verify(notes).commitCall(callId,1000); verify(authority).complete(eq(lease),eq(receipt),any());
    }
    @Test void accountingFailureCannotMarkContentWorkSucceeded() {
        setup(NotesJob.CallState.PREPARED); when(notes.commitCall(callId,1000)).thenReturn(false);
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.FAILED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verify(authority,never()).complete(any(),any(),any());
    }
    @Test void unknownAndDispatchingAreNeverSentAgain() {
        for(var state:List.of(NotesJob.CallState.UNKNOWN,NotesJob.CallState.DISPATCHING)) {
            setup(state);
            try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.FAILED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        }
        verifyNoInteractions(processor); verify(notes,never()).beginWorkDispatch(any(),any());
    }
    @Test void lostDispatchLeasePreventsModelInvocation() {
        setup(NotesJob.CallState.PREPARED); when(notes.beginWorkDispatch(callId,lease)).thenReturn(false);
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.FAILED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verify(processor,never()).invoke(any(),any(),any()); verify(notes,never()).markUnknown(any());
    }
    @Test void modelFailureBecomesUnknownAndIsNotAutomaticallyRetried() {
        setup(NotesJob.CallState.PREPARED);
        when(processor.invoke(any(),any(),any())).thenThrow(new AgentException("VIDEO_MODEL_TIMEOUT","timeout"));
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.FAILED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verify(notes).markUnknown(callId); verify(content).fail(lease,"VIDEO_MODEL_TIMEOUT",false,Duration.ofSeconds(5));
        verify(notes,never()).commitCall(any(),anyLong());
    }
    @Test void preparingMediaFailureDoesNotMarkProviderUnknown() {
        setup(NotesJob.CallState.PREPARED); when(processor.prepare(any(),any())).thenThrow(new IllegalArgumentException("bad media"));
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.FAILED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verify(notes,never()).beginWorkDispatch(any(),any()); verify(notes,never()).markUnknown(any());
    }
    @Test void revokedOwnerCannotDispatchAfterMediaPreparation() {
        setup(NotesJob.CallState.PREPARED);
        doNothing().doThrow(new AgentException("FORBIDDEN","owner revoked")).when(processing).requireRoot(any(),any());
        try(var worker=worker()) { assertEquals(NotesSourceWorker.Outcome.FAILED,worker.runOnce(Stage.TRANSCRIBE,control())); }
        verify(processor,never()).invoke(any(),any(),any());verify(notes,never()).beginWorkDispatch(any(),any());
    }
    @Test void closeCannotFreeCapacityWhileAnUncooperativeProviderStillRuns() throws Exception {
        setup(NotesJob.CallState.PREPARED); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(processor.invoke(any(),any(),any())).thenAnswer(invocation->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;});
        var worker=worker(); var executor=Executors.newSingleThreadExecutor();
        try {
            var running=executor.submit(()->worker.runOnce(Stage.TRANSCRIBE,control())); assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(NotesSourceWorker.Outcome.BUSY,worker.runOnce(Stage.TRANSCRIBE,control()));
            worker.close(); assertFalse(running.isDone()); release.countDown();
            assertEquals(NotesSourceWorker.Outcome.FAILED,running.get(5,TimeUnit.SECONDS));
            assertEquals(NotesSourceWorker.Outcome.CLOSED,worker.runOnce(Stage.TRANSCRIBE,control()));
        } finally {release.countDown();worker.close();executor.shutdownNow();}
    }
}
