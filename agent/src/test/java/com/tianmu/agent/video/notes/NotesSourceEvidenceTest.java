package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.video.content.ContentWork.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesSourceEvidenceTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"source","public",0);
    final ContentProcessingMediaPort media=mock(ContentProcessingMediaPort.class);
    final TranscriptionPort transcription=mock(TranscriptionPort.class);
    final VisionModelPort vision=mock(VisionModelPort.class);
    final NotesSourceProcessor processor=new NotesSourceProcessor(media,transcription,vision);
    Job job(Stage stage) {
        var deadline=Instant.now().plusSeconds(60);
        return new Job(UUID.randomUUID(),new Unit(asset,stage,"notes:abc","analysis",0,1000),new Grant(UUID.randomUUID(),asset,deadline),
                Status.RUNNING,1,2,1,"worker",deadline,deadline,null,null);
    }
    CallControl control(){return new CallControl(Instant.now().plusSeconds(60),new CancellationToken());}
    VideoContent.Media clip(long video,String version) {
        return new VideoContent.Media(new VideoContent.Source(video,version,1000),0,1000,List.of(new VideoContent.Frame(0,"AQ==")),"AQ==");
    }
    NotesSourceProcessor.Evidence audio(Job job,String text) {
        return new NotesSourceProcessor.Evidence(job.unit(),new TimedTranscript(0,1000,text,TimedTranscript.State.AVAILABLE,TimedTranscript.Precision.CLIP,
                List.of(new TimedTranscript.Segment(0,1000,text))),null);
    }
    @Test void preparationReadsMediaButNeverInvokesModels() {
        var job=job(Stage.TRANSCRIBE); when(media.audio(eq(job.grant()),eq(0L),eq(1000L),any())).thenReturn(clip(1,"source"));
        assertEquals(clip(1,"source"),processor.prepare(job,control())); verifyNoInteractions(vision,transcription);
    }
    @Test void mediaIdentityMismatchFailsBeforeProvider() {
        var job=job(Stage.VISION); when(media.media(eq(job.grant()),anyLong(),anyLong(),any())).thenReturn(clip(2,"source"));
        assertThrows(RuntimeException.class,()->processor.prepare(job,control())); verifyNoInteractions(vision,transcription);
    }
    @Test void visionObservationMustReferenceAnActualSample() throws Exception {
        var job=job(Stage.VISION);
        when(vision.understand(any(),anyString(),eq(""),any())).thenReturn(json.readTree("{\"summary\":\"主题\",\"moments\":[{\"atMs\":500,\"observation\":\"推测事件\"}]}"));
        assertThrows(IllegalArgumentException.class,()->processor.invoke(job,clip(1,"source"),control()));
    }
    @Test void emptyTranscriptionCannotBePublishedAsSilence() {
        var job=job(Stage.TRANSCRIBE);
        when(transcription.transcribeTimed(anyString(),anyLong(),anyLong(),any())).thenReturn(new TimedTranscript(0,1000,"",TimedTranscript.State.NO_TRANSCRIPT,TimedTranscript.Precision.CLIP,List.of()));
        assertThrows(IllegalArgumentException.class,()->processor.invoke(job,clip(1,"source"),control()));
    }
    @Test void immutablePrivateEvidenceRoundTripsAndRejectsForeignReceipt() throws Exception {
        var job=job(Stage.TRANSCRIBE); var lease=new Lease(job.id(),1,"worker"); var store=new FileNotesSourceStore(directory,json);
        var receipt=store.save(job,lease,audio(job,"原始讲解"));
        assertEquals(audio(job,"原始讲解"),store.read(job,receipt));
        assertEquals(receipt,store.save(job,lease,audio(job,"原始讲解")));
        assertThrows(IllegalArgumentException.class,()->store.save(job,lease,audio(job,"不同内容")));
        assertThrows(IllegalArgumentException.class,()->store.read(job(Stage.TRANSCRIBE),receipt));
    }
    @Test void changedBytesAndForeignWriterAreRejected() throws Exception {
        var job=job(Stage.TRANSCRIBE); var store=new FileNotesSourceStore(directory,json);
        assertThrows(IllegalArgumentException.class,()->store.save(job,new Lease(job.id(),1,"other"),audio(job,"内容")));
        var receipt=store.save(job,new Lease(job.id(),1,"worker"),audio(job,"内容"));
        Files.writeString(directory.resolve(job.id()+"-1.json"),"{}");
        assertThrows(IllegalArgumentException.class,()->store.read(job,receipt));
    }
}
