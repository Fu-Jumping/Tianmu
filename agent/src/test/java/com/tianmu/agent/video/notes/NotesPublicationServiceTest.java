package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesPublicationServiceTest {
    final MysqlNotesJobStore notes=mock(MysqlNotesJobStore.class);final NotesDocumentObjects objects=mock(NotesDocumentObjects.class);
    final NotesCoordinator.ProcessingGrants processing=mock(NotesCoordinator.ProcessingGrants.class);final NotesAdmissionService readers=mock(NotesAdmissionService.class);
    final UUID id=UUID.randomUUID(),summary=UUID.randomUUID();final String digest="a".repeat(64);final Instant deadline=Instant.now().plusSeconds(600);
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"source","public",0);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.Create request=new NotesJob.Create("tenant","user",asset,1000,policy,"key",deadline);
    final NotesCoverage coverage=new NotesCoverage(asset,1000,digest,List.of(new NotesCoverage.Unit("clip",asset,new NotesCoverage.Range(0,1000),new NotesCoverage.Range(0,1000),NotesCoverage.Audio.TRANSCRIBED,NotesCoverage.Visual.SAMPLED,digest,digest,List.of(0L))));
    final NotesDocument doc=new NotesDocument(1,"标题","概览",List.of(),List.of(new NotesDocument.Section("section","主题",new NotesCoverage.Range(0,1000),List.of(new NotesDocument.Block(NotesDocument.Kind.PARAGRAPH,"正文",List.of(),List.of(),List.of(new NotesDocument.Citation("clip",NotesDocument.Modality.AUDIO,new NotesCoverage.Range(0,1000),digest)))),List.of())),List.of(),coverage);
    final NotesDocumentObjects.Reference reference=new NotesDocumentObjects.Reference("notes-"+digest+".json",100,digest,doc.contentDigest());
    final AccessScope viewer=new AccessScope("tenant","user",Set.of(),"",Set.of(1L));
    final NotesPublicationService service=new NotesPublicationService(notes,objects,processing,readers,digest);
    NotesJob.Job root(NotesJob.Status status,long version){return new NotesJob.Job(id,request,digest,status,version,1,digest,0,0,doc.contentDigest());}
    CallControl control(){return new CallControl(deadline,new CancellationToken());}
    @Test void publishedReadReauthorizesBeforeAndAfterReadingAndCopyUsesSameGate() {
        when(readers.current(eq(id),eq(viewer),any())).thenReturn(root(NotesJob.Status.READY,2));
        when(notes.publishedReference(id,"tenant","user")).thenReturn(Optional.of(reference));when(objects.read(eq(root(NotesJob.Status.READY,2)),eq(reference),any())).thenReturn(doc);
        assertEquals(doc,service.read(id,viewer,control()));verify(readers,times(2)).current(eq(id),eq(viewer),any());
        assertTrue(service.copy(id,viewer,control()).contains("正文"));verify(readers,times(4)).current(eq(id),eq(viewer),any());
    }
    @Test void revocationDuringObjectReadCannotReturnTheDocument() {
        when(readers.current(eq(id),eq(viewer),any())).thenReturn(root(NotesJob.Status.READY,2)).thenThrow(new AgentException("FORBIDDEN","revoked"));
        when(notes.publishedReference(id,"tenant","user")).thenReturn(Optional.of(reference));when(objects.read(any(),eq(reference),any())).thenReturn(doc);
        assertThrows(AgentException.class,()->service.read(id,viewer,control()));
    }
    @Test void onlyAuthoritativelyPublishedReferenceCanBeRead() {
        when(readers.current(eq(id),eq(viewer),any())).thenReturn(root(NotesJob.Status.RUNNING,1));
        assertThrows(AgentException.class,()->service.read(id,viewer,control()));verifyNoInteractions(objects);
    }
    @Test void sourceReceiptChangeBlocksPublicationBeforeObjectRead() {
        var root=root(NotesJob.Status.RUNNING,1);when(notes.get(id,"tenant","user")).thenReturn(root);
        var material=new NotesMaterialReader.Prepared(id,1,1,digest,coverage,List.of());
        when(notes.sourceSnapshot(id,"tenant","user")).thenReturn(List.of(new MysqlNotesJobStore.SourceToken("audio",UUID.randomUUID(),"source",digest)));
        assertThrows(AgentException.class,()->service.publish(id,"tenant","user",material,reference,new NotesDocument.QualityReceipt(doc.contentDigest(),digest,true,List.of()),summary,control()));
        verifyNoInteractions(objects);verify(notes,never()).publish(any(),anyString(),anyString(),anyLong(),anyInt(),anyString(),any(),anyList(),anyString(),any(),any());
    }
    @Test void staleGenerationCannotStagePublication() {
        when(notes.get(id,"tenant","user")).thenReturn(root(NotesJob.Status.RUNNING,2));
        var material=new NotesMaterialReader.Prepared(id,1,1,digest,coverage,List.of());
        assertThrows(AgentException.class,()->service.publish(id,"tenant","user",material,reference,new NotesDocument.QualityReceipt(doc.contentDigest(),digest,true,List.of()),summary,control()));
        verifyNoInteractions(processing,objects);
    }
}
