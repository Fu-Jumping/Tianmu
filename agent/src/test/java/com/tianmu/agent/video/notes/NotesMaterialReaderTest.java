package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesMaterialReaderTest {
    final MysqlNotesJobStore notes=mock(MysqlNotesJobStore.class);
    final MysqlContentWorkStore work=mock(MysqlContentWorkStore.class);
    final NotesSourceStore objects=mock(NotesSourceStore.class);
    final NotesCoordinator.ProcessingGrants authority=mock(NotesCoordinator.ProcessingGrants.class);
    final UUID id=UUID.randomUUID(); final Instant deadline=Instant.now().plusSeconds(600);
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"version","public",0);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.Create request=new NotesJob.Create("tenant","user",asset,1000,policy,"key",deadline);
    final NotesJob.Job root=new NotesJob.Job(id,request,"a".repeat(64),NotesJob.Status.RUNNING,1,1,"b".repeat(64),0,0,null);
    final List<NotesJob.PlanUnit> plan=NotesJob.sourcePlan(id,1000,policy,"a".repeat(64));
    CallControl control(){return new CallControl(deadline,new CancellationToken());}
    void setup() {
        when(notes.get(id,"tenant","user")).thenReturn(root);
        List<MysqlNotesJobStore.Dependency> dependencies=new ArrayList<>();
        for(var p:plan) {
            UUID workId=UUID.randomUUID(); var unit=new ContentWork.Unit(asset,p.stage(),p.segmentId(id),"analysis",0,1000);
            var receipt=new ContentWork.Receipt("source:"+workId,"c".repeat(64));
            var job=new ContentWork.Job(workId,unit,new ContentWork.Grant(UUID.randomUUID(),asset,deadline),ContentWork.Status.SUCCEEDED,1,2,1,"worker",deadline,deadline,receipt,null);
            dependencies.add(new MysqlNotesJobStore.Dependency(p,workId)); when(work.get(workId)).thenReturn(job);
            var transcript=p.stage()==ContentWork.Stage.TRANSCRIBE?new TimedTranscript(0,1000,"讲解内容",TimedTranscript.State.AVAILABLE,TimedTranscript.Precision.CLIP,List.of(new TimedTranscript.Segment(0,1000,"讲解内容"))):null;
            var visual=p.stage()==ContentWork.Stage.VISION?new NotesSourceProcessor.Visual("板书",List.of(new VideoContent.Moment(0,"可见公式")),List.of(0L)):null;
            when(objects.read(job,receipt)).thenReturn(new NotesSourceProcessor.Evidence(unit,transcript,visual));
        }
        when(notes.dependencies(id,"tenant","user")).thenReturn(dependencies);
    }
    @Test void buildsCoverageOnlyFromVerifiedBoundSourceObjects() {
        setup(); var result=new NotesMaterialReader(notes,work,objects,authority,100,10000).read(id,"tenant","user",control());
        assertTrue(result.coverage().fullyProcessed()); assertEquals(2,result.sources().size());
        assertEquals("c".repeat(64),result.coverage().units().get(0).audioDigest()); verify(authority,times(2)).requireRoot(eq(root),any());
    }
    @Test void unboundUnitCannotBecomeCompleteCoverage() {
        setup(); when(notes.dependencies(id,"tenant","user")).thenReturn(List.of(new MysqlNotesJobStore.Dependency(plan.get(0),null)));
        assertThrows(AgentException.class,()->new NotesMaterialReader(notes,work,objects,authority,100,10000).read(id,"tenant","user",control()));
    }
    @Test void exceedingMaterialLimitIsRejectedRatherThanSilentlyTruncated() {
        setup(); assertThrows(AgentException.class,()->new NotesMaterialReader(notes,work,objects,authority,100,1).read(id,"tenant","user",control()));
    }
    @Test void sourceChangeDuringReadRejectsTheSnapshot() {
        setup(); var changed=new NotesJob.Job(id,request,root.rootKey(),NotesJob.Status.PARTIAL,2,1,root.planDigest(),0,0,null);
        when(notes.get(id,"tenant","user")).thenReturn(root,changed);
        assertThrows(AgentException.class,()->new NotesMaterialReader(notes,work,objects,authority,100,10000).read(id,"tenant","user",control()));
    }
}
