package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranscriptPreparationTest {
    @Test void grantsOnlyTheAuthorizedAlreadyWatchedClip() {
        var reader=mock(ContentReadAuthority.class);var authority=mock(HttpContentProcessingAuthority.class);var work=mock(ContentWorkService.class);
        var asset=new ContentAssetKey("site",123,"v1","public",1);
        var viewer=new AccessScope("site","reader",Set.of(),"",Set.of(123L));
        var control=new CallControl(Instant.now().plusSeconds(60),new CancellationToken());
        var deadline=Instant.now().plusSeconds(30);
        when(reader.current(eq(123L),eq(viewer),any())).thenReturn(new ContentReadAuthority.Identity(asset,60000));
        when(authority.issue(any(),eq(123L),any())).thenAnswer(i->new ContentWork.Grant(i.getArgument(0),asset,Instant.now().plusSeconds(60)));
        var service=new TranscriptPreparation(reader,authority,work,mock(ContentRequestBinding.class));
        var requestId=UUID.randomUUID();
        service.admit(requestId,123,"v1",27000,30000,30000,viewer,deadline,control);
        verify(authority).issue(eq(requestId),eq(123L),eq(control));
        var unit=org.mockito.ArgumentCaptor.forClass(ContentWork.Unit.class);
        verify(work).admit(unit.capture(),any(),eq(deadline),eq(2),eq(control));
        assertEquals(TranscriptPreparation.STAGE_VERSION,unit.getValue().stageVersion());
        assertEquals(27000,unit.getValue().startMs());assertEquals(30000,unit.getValue().endMs());
        reset(authority,work);
        assertEquals("INVALID_CONTENT_REQUEST",assertThrows(AgentException.class,()->service.admit(requestId,123,"v1",27000,30000,29999,viewer,deadline,control)).code());
        assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->service.admit(requestId,123,"v2",27000,30000,30000,viewer,deadline,control)).code());
        verifyNoInteractions(authority,work);
    }

    @Test void rejectsMismatchedGrantsAndDeadlinesBeforeEnqueue() {
        var reader=mock(ContentReadAuthority.class);var authority=mock(HttpContentProcessingAuthority.class);var work=mock(ContentWorkService.class);
        var asset=new ContentAssetKey("site",123,"v1","public",1);
        var changed=new ContentAssetKey("site",123,"v1","public",2);
        var viewer=new AccessScope("site","reader",Set.of(),"",Set.of(123L));
        var control=new CallControl(Instant.now().plusSeconds(60),new CancellationToken());
        var deadline=Instant.now().plusSeconds(30);var requestId=UUID.randomUUID();
        when(reader.current(eq(123L),eq(viewer),any())).thenReturn(new ContentReadAuthority.Identity(asset,60000));
        when(authority.issue(eq(requestId),eq(123L),any())).thenReturn(new ContentWork.Grant(requestId,changed,Instant.now().plusSeconds(60)));
        var service=new TranscriptPreparation(reader,authority,work,mock(ContentRequestBinding.class));
        assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->service.admit(requestId,123,"v1",0,30000,30000,viewer,deadline,control)).code());
        when(authority.issue(eq(requestId),eq(123L),any())).thenReturn(new ContentWork.Grant(requestId,asset,Instant.now().plusSeconds(10)));
        assertEquals("CONTENT_DEADLINE_EXCEEDS_GRANT",assertThrows(AgentException.class,()->service.admit(requestId,123,"v1",0,30000,30000,viewer,deadline,control)).code());
        verifyNoInteractions(work);
    }
    @Test void returnsExistingFailedJobWithoutPretendingToRetry() {
        var reader=mock(ContentReadAuthority.class);var authority=mock(HttpContentProcessingAuthority.class);var work=mock(ContentWorkService.class);
        var asset=new ContentAssetKey("site",123,"v1","public",1);
        var viewer=new AccessScope("site","reader",Set.of(),"",Set.of(123L));
        var control=new CallControl(Instant.now().plusSeconds(60),new CancellationToken());
        var deadline=Instant.now().plusSeconds(30);var requestId=UUID.randomUUID();
        var failed=mock(ContentWork.Job.class);when(failed.status()).thenReturn(ContentWork.Status.FAILED);
        when(reader.current(eq(123L),eq(viewer),any())).thenReturn(new ContentReadAuthority.Identity(asset,60000));
        when(authority.issue(eq(requestId),eq(123L),any())).thenReturn(new ContentWork.Grant(requestId,asset,Instant.now().plusSeconds(60)));
        when(work.admit(any(),any(),eq(deadline),eq(2),eq(control))).thenReturn(failed);
        var result=new TranscriptPreparation(reader,authority,work,mock(ContentRequestBinding.class)).admit(requestId,123,"v1",0,30000,30000,viewer,deadline,control);
        assertSame(failed,result);assertEquals(ContentWork.Status.FAILED,result.status());
        verify(work,never()).retry(any(),any(),any(),anyInt(),any());
    }
}
