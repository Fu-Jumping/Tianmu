package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.FileObjectStore;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.ContentAssetKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotesDocumentObjectsTest {
    @TempDir Path directory;
    final String digest="a".repeat(64);
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"version","public",0);
    final UUID id=UUID.randomUUID();
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis","generation",60000,5000,100000);
    final NotesJob.Create request=new NotesJob.Create("tenant","user",asset,1000,policy,"key",Instant.now().plusSeconds(600));
    final NotesJob.Job root=new NotesJob.Job(id,request,digest,NotesJob.Status.RUNNING,1,1,digest,0,0,null);
    final NotesCoverage coverage=new NotesCoverage(asset,1000,digest,List.of(new NotesCoverage.Unit("clip",asset,new NotesCoverage.Range(0,1000),new NotesCoverage.Range(0,1000),NotesCoverage.Audio.TRANSCRIBED,NotesCoverage.Visual.SAMPLED,digest,digest,List.of(0L))));
    final NotesDocument.Citation citation=new NotesDocument.Citation("clip",NotesDocument.Modality.AUDIO,new NotesCoverage.Range(0,1000),digest);
    final NotesDocument document=new NotesDocument(1,"课程","内容概览",List.of("要点"),List.of(new NotesDocument.Section("section","定义",new NotesCoverage.Range(0,1000),List.of(new NotesDocument.Block(NotesDocument.Kind.PARAGRAPH,"定义内容",List.of(),List.of(),List.of(citation))),List.of())),List.of(),coverage);
    final NotesMaterialReader.Prepared material=new NotesMaterialReader.Prepared(id,1,1,digest,coverage,List.of());
    CallControl control(){return new CallControl(Instant.now().plusSeconds(60),new CancellationToken());}
    NotesDocument.QualityReceipt quality(){return new NotesDocument.QualityReceipt(document.contentDigest(),digest,true,List.of());}
    @Test void reusesExistingPrivateObjectAdapterAndRecoversDuplicateWriteAcknowledgement() {
        var objects=new NotesDocumentObjects(new FileObjectStore(directory),new ObjectMapper());
        var reference=objects.stage(root,material,document,quality(),digest,control());
        assertEquals(document,objects.read(root,reference,control()));
        assertEquals(reference,objects.stage(root,material,document,quality(),digest,control()));
        assertTrue(reference.objectKey().matches("notes-[a-f0-9]{64}\\.json"));
    }
    @Test void staleSnapshotCannotCreateAnObject() {
        var objects=new NotesDocumentObjects(new FileObjectStore(directory),new ObjectMapper());
        var stale=new NotesMaterialReader.Prepared(id,1,0,digest,coverage,List.of());
        assertThrows(IllegalArgumentException.class,()->objects.stage(root,stale,document,quality(),digest,control()));
    }
    @Test void wrongQualityReceiptOrForeignRootCannotBeReadAsAValidDocument() {
        var objects=new NotesDocumentObjects(new FileObjectStore(directory),new ObjectMapper());
        var failed=new NotesDocument.QualityReceipt(document.contentDigest(),digest,false,List.of("遗漏"));
        assertThrows(IllegalArgumentException.class,()->objects.stage(root,material,document,failed,digest,control()));
        var reference=objects.stage(root,material,document,quality(),digest,control());
        assertThrows(IllegalArgumentException.class,()->objects.read(root,reference,"b".repeat(64),control()));
        var other=new NotesJob.Job(UUID.randomUUID(),request,digest,NotesJob.Status.RUNNING,1,1,digest,0,0,null);
        assertThrows(IllegalArgumentException.class,()->objects.read(other,reference,control()));
    }
}
