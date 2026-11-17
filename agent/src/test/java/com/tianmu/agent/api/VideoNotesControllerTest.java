package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.video.content.ContentAssetKey;
import com.tianmu.agent.video.notes.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoNotesControllerTest {
    final IdentityPort identity=mock(IdentityPort.class);
    final NotesAdmissionService admission=mock(NotesAdmissionService.class);
    final NotesPublicationService publication=mock(NotesPublicationService.class);
    final VideoNotesController controller=new VideoNotesController(identity,admission,publication,mock(NotesRetryService.class));
    final AccessScope viewer=new AccessScope("site","user",Set.of(),"",Set.of(1L,2L));
    final UUID id=UUID.randomUUID();
    NotesJob.Job job(long video){var asset=new ContentAssetKey("site",video,"v1","public",1);var p=new NotesJob.Policy("zh","1","a","g",60000,0,100);var r=new NotesJob.Create("site","user",asset,30000,p,"key",Instant.now().plusSeconds(60));return new NotesJob.Job(id,r,"a".repeat(64),NotesJob.Status.RUNNING,1,1,null,0,0,null);}
    void identity(){when(identity.forCreate(eq("token"),eq(""),isNull(),any(),isNull())).thenReturn(viewer);}
    @Test void anonymousRequestsDoNotReachTheStore(){assertThrows(AgentException.class,()->controller.current(1,id,null));verifyNoInteractions(identity,admission,publication);}
    @Test void createReturnsOnlySafeStatusAndUsesServerAuthority(){identity();when(admission.create(eq(1L),eq("v1"),eq("key"),eq(viewer),any())).thenReturn(job(1));var response=controller.create(1,"Bearer token","key",new VideoNotesController.CreateRequest("v1"));assertEquals(202,response.getStatusCode().value());assertEquals("RUNNING",response.getBody().status());assertEquals("no-store",response.getHeaders().getCacheControl());}
    @Test void otherVideoJobIsRejectedBeforeAnyDocumentRead(){identity();when(admission.current(eq(id),eq(viewer),any())).thenReturn(job(2));assertThrows(AgentException.class,()->controller.document(1,id,"Bearer token"));verifyNoInteractions(publication);}
}
