package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotesRetryServiceTest {
    @Test void retryCannotBypassLiveReaderAuthorization(){
        var readers=mock(NotesAdmissionService.class);var notes=mock(MysqlNotesJobStore.class);var content=mock(MysqlContentWorkStore.class);
        var work=mock(ContentWorkService.class);var grants=mock(NotesCoordinator.ProcessingGrants.class);
        var viewer=new AccessScope("site","user",Set.of(),"",Set.of(1L));var id=UUID.randomUUID();
        var control=new CallControl(Instant.now().plusSeconds(10),new CancellationToken());
        when(readers.current(id,viewer,control)).thenThrow(new AgentException("FORBIDDEN","denied"));
        assertThrows(AgentException.class,()->new NotesRetryService(readers,notes,content,work,grants).retry(id,viewer,control));
        verifyNoInteractions(notes,content,work,grants);
    }
}
