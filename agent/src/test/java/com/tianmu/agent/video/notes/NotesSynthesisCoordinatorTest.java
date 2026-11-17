package com.tianmu.agent.video.notes;

import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesSynthesisCoordinatorTest {
    final MysqlNotesJobStore notes=mock(MysqlNotesJobStore.class);final MysqlContentWorkStore content=mock(MysqlContentWorkStore.class);
    final ContentWorkService authority=mock(ContentWorkService.class);final NotesCoordinator.ProcessingGrants processing=mock(NotesCoordinator.ProcessingGrants.class);
    final NotesSynthesisWorker worker=mock(NotesSynthesisWorker.class);final UUID id=UUID.randomUUID(),workId=UUID.randomUUID();
    final Instant deadline=Instant.now().plusSeconds(600);final String digest="a".repeat(64);
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"source","public",0);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.Create request=new NotesJob.Create("tenant","user",asset,1000,policy,"key",deadline);
    final NotesJob.Job root=new NotesJob.Job(id,request,digest,NotesJob.Status.RUNNING,1,1,digest,0,0,null);
    final List<MysqlNotesJobStore.SourceToken> tokens=List.of(new MysqlNotesJobStore.SourceToken("audio",UUID.randomUUID(),"source",digest));
    final String sourceDigest=MysqlNotesJobStore.sourceSnapshotDigest(tokens);
    final NotesJob.PlanUnit unit=new NotesJob.PlanUnit("summary:"+sourceDigest,ContentWork.Stage.SUMMARY,0,1000,0,1000,sourceDigest);
    final ContentWork.Grant grant=new ContentWork.Grant(UUID.randomUUID(),asset,deadline);
    final ContentWork.Job task=new ContentWork.Job(workId,new ContentWork.Unit(asset,ContentWork.Stage.SUMMARY,unit.segmentId(id),"generation",0,1000),grant,ContentWork.Status.SUCCEEDED,1,2,1,"worker",deadline,deadline,new ContentWork.Receipt("result",digest),null);
    final NotesSynthesisCoordinator coordinator=new NotesSynthesisCoordinator(notes,content,authority,processing,worker,Duration.ofSeconds(120));
    CallControl control(){return new CallControl(deadline,new CancellationToken());}
    @Test void materialReadyUsesExistingAtomicSummaryEnqueueAndGenerationVersion() {
        when(notes.get(id,"tenant","user")).thenReturn(root);when(notes.synthesisDependency(id,"tenant","user")).thenReturn(Optional.empty());
        when(notes.sourceSnapshot(id,"tenant","user")).thenReturn(tokens);when(notes.ensureSynthesisUnit(id,"tenant","user",1,sourceDigest)).thenReturn(Optional.of(new MysqlNotesJobStore.Dependency(unit,null)));
        when(processing.issue(eq(root),eq(unit),any(),any())).thenReturn(grant);
        when(notes.enqueueSynthesisBound(eq(id),eq("tenant"),eq("user"),eq(1L),eq(1),eq(grant),any(),eq(2),eq(tokens))).thenReturn(Optional.of(task));
        assertEquals(NotesSynthesisCoordinator.Outcome.ENQUEUED,coordinator.advance(id,"tenant","user",control()));
        verify(authority).requireAdmission(eq(task.unit()),eq(grant),any());verifyNoInteractions(worker);
    }
    @Test void completedSummaryIsPublishedWithoutAnyNewModelAdmission() {
        when(notes.get(id,"tenant","user")).thenReturn(root);when(notes.synthesisDependency(id,"tenant","user")).thenReturn(Optional.of(new MysqlNotesJobStore.Dependency(unit,workId)));
        when(content.get(workId)).thenReturn(task);when(worker.publishCompleted(eq(root),any())).thenReturn(true);
        assertEquals(NotesSynthesisCoordinator.Outcome.PUBLISHED,coordinator.advance(id,"tenant","user",control()));
        verifyNoInteractions(authority);verify(processing,never()).issue(any(),any(),any(),any());
    }
    @Test void unknownPreventsSummaryAdmissionAndDegradesRoot() {
        when(notes.get(id,"tenant","user")).thenReturn(root);when(notes.hasUnknownCalls(id,"tenant","user")).thenReturn(true);
        when(notes.transition(id,"tenant","user",1,NotesJob.Status.PARTIAL,"NOTES_CALL_UNKNOWN")).thenReturn(true);
        assertEquals(NotesSynthesisCoordinator.Outcome.PARTIAL,coordinator.advance(id,"tenant","user",control()));verifyNoInteractions(authority,worker);
    }
    @Test void expiredNeverClaimedSummaryCanRenewWithoutModelDispatch() {
        when(notes.get(id,"tenant","user")).thenReturn(root);when(notes.synthesisDependency(id,"tenant","user")).thenReturn(Optional.of(new MysqlNotesJobStore.Dependency(unit,workId)));
        var expired=new ContentWork.Job(workId,task.unit(),grant,ContentWork.Status.EXPIRED,0,2,2,null,null,Instant.now().minusSeconds(1),null,null);
        when(content.get(workId)).thenReturn(expired);var renewed=new ContentWork.Grant(UUID.randomUUID(),asset,Instant.now().plusSeconds(600));
        when(processing.issue(eq(root),eq(unit),any(),any())).thenReturn(renewed);
        when(authority.renewUnclaimed(eq(workId),eq(grant.id()),eq(2L),eq(renewed),any(),any())).thenReturn(true);
        assertEquals(NotesSynthesisCoordinator.Outcome.WAITING,coordinator.advance(id,"tenant","user",control()));verifyNoInteractions(worker);
    }
}
