package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesSynthesisWorkerTest {
    @TempDir Path directory;
    final MysqlNotesJobStore notes=mock(MysqlNotesJobStore.class);final MysqlContentWorkStore content=mock(MysqlContentWorkStore.class);
    final ContentWorkService authority=mock(ContentWorkService.class);final NotesMaterialReader materials=mock(NotesMaterialReader.class);
    final NotesSynthesisService synthesis=mock(NotesSynthesisService.class);final NotesPublicationService publication=mock(NotesPublicationService.class);
    final NotesCoordinator.ProcessingGrants processing=mock(NotesCoordinator.ProcessingGrants.class);
    final String digest="a".repeat(64);final UUID id=UUID.randomUUID(),workId=UUID.randomUUID(),draftId=UUID.randomUUID(),checkId=UUID.randomUUID();
    final Instant deadline=Instant.now().plusSeconds(600);final ContentAssetKey asset=new ContentAssetKey("tenant",1,"version","public",0);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.Create request=new NotesJob.Create("tenant","user",asset,1000,policy,"key",deadline);
    final NotesJob.Job root=new NotesJob.Job(id,request,digest,NotesJob.Status.RUNNING,1,1,digest,0,0,null);
    final List<MysqlNotesJobStore.SourceToken> tokens=List.of(new MysqlNotesJobStore.SourceToken("audio",UUID.randomUUID(),"source-a",digest),new MysqlNotesJobStore.SourceToken("visual",UUID.randomUUID(),"source-v",digest));
    final String sourceDigest=MysqlNotesJobStore.sourceSnapshotDigest(tokens);
    final NotesJob.PlanUnit plan=new NotesJob.PlanUnit("summary:"+sourceDigest,ContentWork.Stage.SUMMARY,0,1000,0,1000,sourceDigest);
    final ContentWork.Lease lease=new ContentWork.Lease(workId,1,"worker");
    final ContentWork.Job job=new ContentWork.Job(workId,new ContentWork.Unit(asset,ContentWork.Stage.SUMMARY,plan.segmentId(id),"generation",0,1000),new ContentWork.Grant(UUID.randomUUID(),asset,deadline),ContentWork.Status.RUNNING,1,2,1,"worker",deadline,deadline,null,null);
    final NotesCoverage coverage=new NotesCoverage(asset,1000,digest,List.of(new NotesCoverage.Unit("clip",asset,new NotesCoverage.Range(0,1000),new NotesCoverage.Range(0,1000),NotesCoverage.Audio.TRANSCRIBED,NotesCoverage.Visual.SAMPLED,digest,digest,List.of(0L))));
    final NotesMaterialReader.Prepared material=new NotesMaterialReader.Prepared(id,1,1,digest,coverage,List.of());
    final NotesDocument document=new NotesDocument(1,"标题","概览",List.of(),List.of(new NotesDocument.Section("section","定义",new NotesCoverage.Range(0,1000),List.of(new NotesDocument.Block(NotesDocument.Kind.PARAGRAPH,"正文",List.of(),List.of(),List.of(new NotesDocument.Citation("clip",NotesDocument.Modality.AUDIO,new NotesCoverage.Range(0,1000),digest)))),List.of())),List.of(),coverage);
    final NotesDocument.QualityReceipt quality=new NotesDocument.QualityReceipt(document.contentDigest(),digest,true,List.of());
    NotesSynthesisObjects checkpoints;NotesDocumentObjects documents;
    CallControl control(){return new CallControl(deadline,new CancellationToken());}
    NotesJob.Call call(NotesSynthesisObjects.Phase phase,NotesJob.CallState state) {
        boolean draft=phase==NotesSynthesisObjects.Phase.DRAFT;
        return new NotesJob.Call(draft?draftId:checkId,id,1,plan.segmentId(id)+(draft?":draft":":check"),state,100,
                (draft?"notes-draft:":"notes-quality:")+document.contentDigest(),draft?document.contentDigest():MysqlNotesJobStore.qualityReceiptDigest(quality));
    }
    void setup() {
        var objects=new FileObjectStore(directory);checkpoints=new NotesSynthesisObjects(objects,new ObjectMapper());documents=new NotesDocumentObjects(objects,new ObjectMapper());
        when(synthesis.generationVersion()).thenReturn("generation");when(synthesis.qualityPolicyDigest()).thenReturn(digest);
        when(authority.claim(anyString(),any(),eq(ContentWork.Stage.SUMMARY),eq("generation"),any())).thenReturn(Optional.of(lease));
        when(content.requireLease(lease)).thenReturn(job);when(notes.lookupBoundWork(workId)).thenReturn(Optional.of(new MysqlNotesJobStore.BoundUnit(root,plan)));
        when(materials.read(eq(id),eq("tenant"),eq("user"),any())).thenReturn(material);when(notes.sourceSnapshot(id,"tenant","user")).thenReturn(tokens);
        when(notes.prepareCall(eq(id),eq("tenant"),eq("user"),eq(1),anyString(),eq(100L))).thenAnswer(i->call(i.getArgument(4,String.class).endsWith(":draft")?NotesSynthesisObjects.Phase.DRAFT:NotesSynthesisObjects.Phase.CHECK,NotesJob.CallState.PREPARED));
        when(notes.beginWorkDispatch(any(),eq(lease))).thenReturn(true);when(notes.stageSynthesisResult(any(),eq(lease),anyString(),anyString(),any())).thenReturn(true);
        when(notes.commitCall(any(),eq(100L))).thenReturn(true);when(content.stageResult(eq(lease),any())).thenReturn(true);when(authority.complete(eq(lease),any(),any())).thenReturn(true);
        when(synthesis.draft(eq(material),anyString(),any())).thenReturn(document);when(synthesis.check(eq(material),eq(document),any())).thenReturn(quality);
        when(publication.publish(eq(id),eq("tenant"),eq("user"),eq(material),any(),eq(quality),eq(workId),any())).thenReturn(true);
    }
    NotesSynthesisWorker worker(){return new NotesSynthesisWorker(notes,content,authority,materials,synthesis,checkpoints,documents,publication,processing,"worker",Duration.ofSeconds(30),Duration.ofSeconds(5),100);}
    void staged(NotesSynthesisObjects.Phase phase,NotesJob.CallState state) {
        var metadata=checkpoints.stage(root,sourceDigest,phase,document,phase==NotesSynthesisObjects.Phase.CHECK?quality:null,control());var call=call(phase,state);
        when(notes.prepareCall(eq(id),eq("tenant"),eq("user"),eq(1),eq(call.operationKey()),eq(100L))).thenReturn(call);
        when(notes.synthesisCheckpoint(call.id())).thenReturn(Optional.of(metadata));when(notes.call(id,"tenant","user",1,call.operationKey())).thenReturn(Optional.of(call));
    }
    @Test void executesSeparateDraftAndCheckThenCompletesAndPublishes() {
        setup();try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.SUCCEEDED,worker.runOnce(control()));}
        var ordered=inOrder(notes,synthesis,authority,publication);
        ordered.verify(notes).beginWorkDispatch(draftId,lease);ordered.verify(synthesis).draft(eq(material),anyString(),any());ordered.verify(notes).commitCall(draftId,100);
        ordered.verify(notes).beginWorkDispatch(checkId,lease);ordered.verify(synthesis).check(eq(material),eq(document),any());ordered.verify(notes).commitCall(checkId,100);
        ordered.verify(authority).complete(eq(lease),any(),any());ordered.verify(publication).publish(eq(id),anyString(),anyString(),eq(material),any(),eq(quality),eq(workId),any());
    }
    @Test void restartAfterBothCheckpointStagesDoesNotCallEitherModel() {
        setup();staged(NotesSynthesisObjects.Phase.DRAFT,NotesJob.CallState.COMMITTED);staged(NotesSynthesisObjects.Phase.CHECK,NotesJob.CallState.RESULT_STAGED);
        try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.SUCCEEDED,worker.runOnce(control()));}
        verify(synthesis,never()).draft(any(),anyString(),any());verify(synthesis,never()).check(any(),any(),any());verify(notes,never()).beginWorkDispatch(any(),any());
    }
    @Test void providerTimeoutRetainsUnknownAndDoesNotStartCheck() {
        setup();when(synthesis.draft(any(),anyString(),any())).thenThrow(new AgentException("MODEL_ERROR","timeout"));
        try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.FAILED,worker.runOnce(control()));}
        verify(notes).markUnknown(draftId);verify(synthesis,never()).check(any(),any(),any());verifyNoInteractions(publication);
    }
    @Test void unknownDraftCannotBeAutomaticallySentAgain() {
        setup();when(notes.prepareCall(eq(id),eq("tenant"),eq("user"),eq(1),eq(plan.segmentId(id)+":draft"),eq(100L))).thenReturn(call(NotesSynthesisObjects.Phase.DRAFT,NotesJob.CallState.UNKNOWN));
        try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.FAILED,worker.runOnce(control()));}
        verify(synthesis,never()).draft(any(),anyString(),any());verify(notes,never()).beginWorkDispatch(any(),any());
    }
    @Test void failedQualityDoesNotPublishOrCompleteWorkAsSuccess() {
        setup();var rejected=new NotesDocument.QualityReceipt(document.contentDigest(),digest,false,List.of("公式错误"));
        when(synthesis.check(any(),any(),any())).thenReturn(rejected);when(synthesis.repair(any(),any(),any(),any())).thenReturn(document);
        when(synthesis.recheck(any(),any(),any())).thenReturn(rejected);when(synthesis.arbitrate(any(),any(),any(),any())).thenReturn(rejected);
        for(var phase:List.of(NotesSynthesisObjects.Phase.REPAIR,NotesSynthesisObjects.Phase.RECHECK,NotesSynthesisObjects.Phase.ARBITRATE)) {
            String operation=plan.segmentId(id)+":"+phase.name().toLowerCase(Locale.ROOT);
            when(notes.prepareCall(eq(id),eq("tenant"),eq("user"),eq(1),eq(operation),eq(100L)))
                    .thenReturn(new NotesJob.Call(UUID.randomUUID(),id,1,operation,NotesJob.CallState.PREPARED,100,null,null));
        }
        try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.FAILED,worker.runOnce(control()));}
        verify(synthesis).repair(eq(material),eq(document),eq(rejected),any());verify(synthesis).recheck(eq(material),eq(document),any());
        verifyNoInteractions(publication);verify(authority,never()).complete(any(),any(),any());verify(notes).transition(id,"tenant","user",1,NotesJob.Status.PARTIAL,"NOTES_QUALITY_REJECTED");
    }
    @Test void repairedDocumentMustPassASecondCheckBeforePublication() {
        setup();var rejected=new NotesDocument.QualityReceipt(document.contentDigest(),digest,false,List.of("公式错误"));
        when(synthesis.check(any(),any(),any())).thenReturn(rejected);when(synthesis.repair(any(),any(),any(),any())).thenReturn(document);
        when(synthesis.recheck(any(),any(),any())).thenReturn(quality);
        for(var phase:List.of(NotesSynthesisObjects.Phase.REPAIR,NotesSynthesisObjects.Phase.RECHECK)) {
            String operation=plan.segmentId(id)+":"+phase.name().toLowerCase(Locale.ROOT);
            when(notes.prepareCall(eq(id),eq("tenant"),eq("user"),eq(1),eq(operation),eq(100L)))
                    .thenReturn(new NotesJob.Call(UUID.randomUUID(),id,1,operation,NotesJob.CallState.PREPARED,100,null,null));
        }
        try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.SUCCEEDED,worker.runOnce(control()));}
        var order=inOrder(synthesis,publication);order.verify(synthesis).repair(eq(material),eq(document),eq(rejected),any());
        order.verify(synthesis).recheck(eq(material),eq(document),any());order.verify(publication).publish(eq(id),anyString(),anyString(),eq(material),any(),eq(quality),eq(workId),any());
    }
    @Test void crashAfterSummarySuccessBeforeRootReadyCanPublishWithoutNewCalls() {
        setup();staged(NotesSynthesisObjects.Phase.DRAFT,NotesJob.CallState.COMMITTED);staged(NotesSynthesisObjects.Phase.CHECK,NotesJob.CallState.COMMITTED);
        var reference=documents.stage(root,material,document,quality,digest,control());
        when(notes.synthesisDependency(id,"tenant","user")).thenReturn(Optional.of(new MysqlNotesJobStore.Dependency(plan,workId)));
        when(content.get(workId)).thenReturn(new ContentWork.Job(workId,job.unit(),job.grant(),ContentWork.Status.SUCCEEDED,1,2,1,"worker",deadline,deadline,new ContentWork.Receipt(reference.objectKey(),reference.sha256()),null));
        try(var worker=worker()){assertTrue(worker.publishCompleted(root,control()));}
        verify(synthesis,never()).draft(any(),anyString(),any());verify(synthesis,never()).check(any(),any(),any());verify(notes,never()).prepareCall(any(),anyString(),anyString(),anyInt(),anyString(),anyLong());
    }
    @Test void missingCheckpointCannotTriggerAReplacementModelCall() {
        setup();when(notes.prepareCall(eq(id),eq("tenant"),eq("user"),eq(1),eq(plan.segmentId(id)+":draft"),eq(100L)))
                .thenReturn(call(NotesSynthesisObjects.Phase.DRAFT,NotesJob.CallState.COMMITTED));
        when(notes.synthesisCheckpoint(draftId)).thenReturn(Optional.empty());
        try(var worker=worker()){assertEquals(NotesLeaseRunner.Outcome.FAILED,worker.runOnce(control()));}
        verify(synthesis,never()).draft(any(),anyString(),any());verify(notes,never()).beginWorkDispatch(any(),any());
    }
    @Test void checkpointCannotBeReusedForAnotherRootSourceOrPhase() {
        setup();var reference=checkpoints.stage(root,sourceDigest,NotesSynthesisObjects.Phase.DRAFT,document,null,control());
        var foreign=new NotesJob.Job(UUID.randomUUID(),request,digest,NotesJob.Status.RUNNING,1,1,digest,0,0,null);
        assertThrows(IllegalArgumentException.class,()->checkpoints.read(foreign,sourceDigest,NotesSynthesisObjects.Phase.DRAFT,reference,control()));
        assertThrows(IllegalArgumentException.class,()->checkpoints.read(root,"b".repeat(64),NotesSynthesisObjects.Phase.DRAFT,reference,control()));
        assertThrows(IllegalArgumentException.class,()->checkpoints.read(root,sourceDigest,NotesSynthesisObjects.Phase.CHECK,reference,control()));
        assertEquals(reference,checkpoints.stage(root,sourceDigest,NotesSynthesisObjects.Phase.DRAFT,document,null,control()));
    }
}
