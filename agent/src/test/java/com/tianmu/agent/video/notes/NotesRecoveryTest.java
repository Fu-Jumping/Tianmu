package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesRecoveryTest {
    final MysqlNotesJobStore notes=mock(MysqlNotesJobStore.class);
    final MysqlContentWorkStore content=mock(MysqlContentWorkStore.class);
    final ContentWorkService work=mock(ContentWorkService.class);
    final NotesCoordinator.ProcessingGrants grants=mock(NotesCoordinator.ProcessingGrants.class);
    final UUID id=UUID.randomUUID(),workId=UUID.randomUUID();
    final Instant now=Instant.now();
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"source","public",0);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.Create request=new NotesJob.Create("tenant","user",asset,1000,policy,"key",now.plusSeconds(3600));
    final List<NotesJob.PlanUnit> plan=NotesJob.sourcePlan(id,1000,policy,"a".repeat(64));
    final NotesCoordinator coordinator=new NotesCoordinator(notes,content,work,grants,Clock.fixed(now,ZoneOffset.UTC),Duration.ofSeconds(120));
    CallControl control() {return new CallControl(now.plusSeconds(60),new CancellationToken());}
    NotesJob.Job root(NotesJob.Status status) {return new NotesJob.Job(id,request,"a".repeat(64),status,1,1,"b".repeat(64),0,0,null);}
    void running() {
        when(notes.get(id,"tenant","user")).thenReturn(root(NotesJob.Status.RUNNING));
        when(notes.plan(id,"tenant","user")).thenReturn(plan);
        when(notes.transition(eq(id),eq("tenant"),eq("user"),eq(1L),any(),anyString())).thenReturn(true);
    }
    ContentWork.Job source(NotesJob.PlanUnit unit,ContentWork.Status status) {
        var grant=new ContentWork.Grant(UUID.randomUUID(),asset,now.plusSeconds(300));
        return new ContentWork.Job(workId,new ContentWork.Unit(asset,unit.stage(),unit.segmentId(id),"analysis",unit.dependencyStartMs(),unit.dependencyEndMs()),grant,
                status,1,2,1,"worker",now.plusSeconds(30),now.plusSeconds(120),status==ContentWork.Status.SUCCEEDED?new ContentWork.Receipt("result","c".repeat(64)):null,null);
    }
    @Test void queuedRootInstallsDurablePlanInsteadOfEnqueuingEphemeralWork() {
        when(notes.get(id,"tenant","user")).thenReturn(root(NotesJob.Status.QUEUED));
        when(notes.installPlan(eq(id),eq("tenant"),eq("user"),eq(1L),anyList())).thenReturn(true);
        assertEquals(NotesCoordinator.Outcome.PLANNED,coordinator.advance(id,"tenant","user",control())); verifyNoInteractions(work);
        verify(grants).requireRoot(eq(root(NotesJob.Status.QUEUED)),any());
    }
    @Test void atomicEnqueueBindingRetryUsesIdenticalWorkIdentity() {
        running(); var unit=plan.get(0); var source=source(unit,ContentWork.Status.QUEUED);
        when(notes.dependencies(id,"tenant","user")).thenReturn(plan.stream().map(p->new MysqlNotesJobStore.Dependency(p,null)).toList());
        when(grants.issue(any(),eq(unit),any(),any())).thenReturn(source.grant());
        when(notes.enqueueBound(eq(id),eq("tenant"),eq("user"),eq(1L),eq(1),eq(unit.unitKey()),eq(source.grant()),any(),eq(2)))
                .thenReturn(Optional.empty(),Optional.of(source));
        assertEquals(NotesCoordinator.Outcome.RACE,coordinator.advance(id,"tenant","user",control()));
        assertEquals(NotesCoordinator.Outcome.ENQUEUED,coordinator.advance(id,"tenant","user",control()));
        var captured=org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(grants,times(2)).issue(any(),eq(unit),captured.capture(),any());
        assertEquals(captured.getAllValues().get(0),captured.getAllValues().get(1));
        verify(work,times(2)).requireAdmission(eq(source.unit()),eq(source.grant()),any());
    }
    @Test void activeBoundWorkBlocksFurtherPaidAdmission() {
        running(); when(notes.dependencies(id,"tenant","user")).thenReturn(List.of(new MysqlNotesJobStore.Dependency(plan.get(0),null),new MysqlNotesJobStore.Dependency(plan.get(1),workId)));
        when(content.get(workId)).thenReturn(source(plan.get(1),ContentWork.Status.RUNNING));
        assertEquals(NotesCoordinator.Outcome.WAITING,coordinator.advance(id,"tenant","user",control()));
        verifyNoInteractions(work); verify(grants,never()).issue(any(),any(),any(),any());
    }
    @Test void neverClaimedExpiredWorkRenewsWithNewIdempotentGrant() {
        running(); var previous=source(plan.get(0),ContentWork.Status.EXPIRED);
        previous=new ContentWork.Job(previous.id(),previous.unit(),previous.grant(),ContentWork.Status.EXPIRED,0,2,2,null,null,now.minusSeconds(1),null,null);
        when(notes.dependencies(id,"tenant","user")).thenReturn(List.of(new MysqlNotesJobStore.Dependency(plan.get(0),workId),new MysqlNotesJobStore.Dependency(plan.get(1),null)));
        when(content.get(workId)).thenReturn(previous);var renewed=new ContentWork.Grant(UUID.randomUUID(),asset,now.plusSeconds(300));
        when(grants.issue(eq(root(NotesJob.Status.RUNNING)),eq(plan.get(0)),any(),any())).thenReturn(renewed);
        when(work.renewUnclaimed(eq(workId),eq(previous.grant().id()),eq(2L),eq(renewed),any(),any())).thenReturn(true);
        assertEquals(NotesCoordinator.Outcome.WAITING,coordinator.advance(id,"tenant","user",control()));
        verify(work).renewUnclaimed(eq(workId),eq(previous.grant().id()),eq(2L),eq(renewed),any(),any());
    }
    @Test void missingEarlierBindingCannotHideAFailedTail() {
        running(); when(notes.dependencies(id,"tenant","user")).thenReturn(List.of(new MysqlNotesJobStore.Dependency(plan.get(0),null),new MysqlNotesJobStore.Dependency(plan.get(1),workId)));
        when(content.get(workId)).thenReturn(source(plan.get(1),ContentWork.Status.FAILED));
        assertEquals(NotesCoordinator.Outcome.PARTIAL,coordinator.advance(id,"tenant","user",control())); verifyNoInteractions(work);
    }
    @Test void unknownDispatchBlocksNewWorkAndNeverReleasesBudget() {
        running(); when(notes.hasUnknownCalls(id,"tenant","user")).thenReturn(true);
        assertEquals(NotesCoordinator.Outcome.PARTIAL,coordinator.advance(id,"tenant","user",control())); verifyNoInteractions(work);
        verify(notes).transition(id,"tenant","user",1,NotesJob.Status.PARTIAL,"NOTES_CALL_UNKNOWN");
    }
    @Test void authorityUnavailableDoesNotBecomePermissionOrCreateWork() {
        running(); doThrow(new AgentException("CONTENT_AUTHORITY_UNAVAILABLE","unavailable")).when(grants).requireRoot(any(),any());
        assertThrows(AgentException.class,()->coordinator.advance(id,"tenant","user",control())); verifyNoInteractions(work);
    }
}
