package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesAdmissionServiceTest {
    final ContentReadAuthority authority=mock(ContentReadAuthority.class);
    final MysqlNotesJobStore store=mock(MysqlNotesJobStore.class);
    final NotesJob.Policy policy=new NotesJob.Policy("zh","1","analysis-1","generation-1",60000,5000,1000000);
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"version","public",0);
    final AccessScope viewer=new AccessScope("tenant","viewer",Set.of(),"",Set.of(1L));
    final CallControl control=new CallControl(Instant.now().plusSeconds(60),new CancellationToken());
    final NotesAdmissionService service=new NotesAdmissionService(authority,store,policy,1800000,Duration.ofHours(2),Clock.systemUTC());
    @Test void admitsViewerWithoutCreatorGrantWithFullAuthoritativeDuration() {
        when(authority.current(1,viewer,control)).thenReturn(new ContentReadAuthority.Identity(asset,90000));
        service.create(1,"version","request",viewer,control);
        verify(store).create(argThat(r -> r.durationMs()==90000 && r.asset().equals(asset) && r.userId().equals("viewer") && r.policy().equals(policy)));
    }
    @Test void stalePlayerAndExcessDurationAreRejectedBeforeAnyPersistence() {
        when(authority.current(1,viewer,control)).thenReturn(new ContentReadAuthority.Identity(asset,90000));
        assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->service.create(1,"old","request",viewer,control)).code());
        when(authority.current(1,viewer,control)).thenReturn(new ContentReadAuthority.Identity(asset,1800001));
        assertEquals("NOTES_DURATION_LIMIT",assertThrows(AgentException.class,()->service.create(1,"version","request",viewer,control)).code());
        verifyNoInteractions(store);
    }
    @Test void unreadableVideoNeverReachesAuthorityOrStore() {
        assertThrows(AgentException.class,()->service.create(2,"version","request",viewer,control));
        verifyNoInteractions(authority,store);
    }
    @Test void authorityUnavailableFailsClosed() {
        when(authority.current(1,viewer,control)).thenThrow(new AgentException("CONTENT_AUTHORITY_UNAVAILABLE","unavailable"));
        assertThrows(AgentException.class,()->service.create(1,"version","request",viewer,control));
        verifyNoInteractions(store);
    }
    @Test void everyReadReauthorizesAssetEpochAndDuration() {
        UUID id=UUID.randomUUID(); var request=new NotesJob.Create("tenant","viewer",asset,90000,policy,"key",Instant.now().plusSeconds(600));
        var job=new NotesJob.Job(id,request,"a".repeat(64),NotesJob.Status.QUEUED,0,1,null,0,0,null);
        when(store.get(id,"tenant","viewer")).thenReturn(job);
        when(authority.current(1,viewer,control)).thenReturn(new ContentReadAuthority.Identity(asset,90000));
        assertEquals(job,service.current(id,viewer,control));
        when(authority.current(1,viewer,control)).thenReturn(new ContentReadAuthority.Identity(new ContentAssetKey("tenant",1,"version","public",1),90000));
        assertThrows(AgentException.class,()->service.current(id,viewer,control));
        verify(authority,times(2)).current(1,viewer,control);
    }
}
