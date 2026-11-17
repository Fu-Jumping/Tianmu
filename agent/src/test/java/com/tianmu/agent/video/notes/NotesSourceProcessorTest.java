package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.*;
import com.tianmu.agent.video.content.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotesSourceProcessorTest {
    @Test void retryUsesBoundedFramesAndRecordsOnlyActualSampleTimes(){
        var media=mock(ContentProcessingMediaPort.class);var asr=mock(TranscriptionPort.class);var vision=mock(VisionModelPort.class);
        var asset=new ContentAssetKey("site",7,"v1","public",1);var source=new VideoContent.Source(7,"v1",30000);
        var frames=new ArrayList<VideoContent.Frame>();for(int i=0;i<12;i++)frames.add(new VideoContent.Frame(1000+i*2000,"valid"));
        var clip=new VideoContent.Media(source,0,30000,frames,"");var grant=new ContentWork.Grant(UUID.randomUUID(),asset,Instant.now().plusSeconds(300));
        var unit=new ContentWork.Unit(asset,ContentWork.Stage.VISION,"segment","v1",0,30000);
        var job=new ContentWork.Job(UUID.randomUUID(),unit,grant,ContentWork.Status.RUNNING,2,3,1,"worker",Instant.now().plusSeconds(60),Instant.now().plusSeconds(300),null,null);
        var control=new CallControl(Instant.now().plusSeconds(10),new CancellationToken());when(media.media(eq(grant),eq(0L),eq(30000L),eq(control))).thenReturn(clip);
        var result=new NotesSourceProcessor(media,asr,vision).prepare(job,control);
        assertEquals(List.of(3000L,9000L,15000L,21000L),result.frames().stream().map(VideoContent.Frame::atMs).toList());
        assertTrue(result.frames().stream().allMatch(frames::contains));
        var first=new ContentWork.Job(job.id(),unit,grant,ContentWork.Status.RUNNING,1,3,1,"worker",job.leaseUntil(),job.deadline(),null,null);
        var normal=new NotesSourceProcessor(media,asr,vision).prepare(first,control);
        assertEquals(4,normal.frames().size());assertTrue(normal.frames().stream().allMatch(frames::contains));
    }
}
