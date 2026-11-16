package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AnalysisContract;
import com.tianmu.agent.core.TaskTypes;
import com.tianmu.agent.runtime.EvidenceContext;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentVideoWindowTest {
    private TaskTypes.Specification spec(String provenance, long allowedStart, long end) {
        return new TaskTypes.Specification("video.content", Map.of("video_id", 1001L, "start_ms", 270000L, "end_ms", 300000L),
                Map.of("start_ms", provenance, "end_ms", provenance),
                new AnalysisContract(Set.of(AnalysisContract.Source.VIDEO), false, allowedStart, end));
    }
    @Test void permitsOnlyFrozenVideoAndPriorWindow() {
        var window = CurrentVideoWindow.from(spec("current_playback_default", 210000, 300000)).orElseThrow();
        assertEquals(270000, window.initialStartMs());
        assertDoesNotThrow(() -> window.check(1001, 210000L, 270000L));
        assertThrows(AgentException.class, () -> window.check(1001, 209999L, 270000L));
        assertThrows(AgentException.class, () -> window.check(1001, 270000L, 300001L));
        assertThrows(AgentException.class, () -> window.check(1002, 210000L, 270000L));
        assertThrows(AgentException.class, () -> window.check(1001, null, null));
    }
    @Test void explicitlySelectedRangeDoesNotBecomeExpandable() {
        assertTrue(CurrentVideoWindow.from(spec("user_input", 210000, 300000)).isEmpty());
        var task=mock(TaskTypes.PreparedTask.class); when(task.specification()).thenReturn(spec("user_input", 210000, 300000));
        var context=mock(EvidenceContext.class); when(context.task()).thenReturn(task);
        when(context.scope()).thenReturn(new AccessScope("t", "u", Set.of(1001L)));
        assertThrows(AgentException.class, () -> new BusinessEvidencePolicy().checkVideoQuery(context,1001,210000L,270000L));
    }
    @Test void admissionUsesFrozenWindowAndStillEnforcesScope() {
        var task=mock(TaskTypes.PreparedTask.class); when(task.specification()).thenReturn(spec("current_playback_default", 210000, 300000));
        var context=mock(EvidenceContext.class); when(context.task()).thenReturn(task);
        when(context.scope()).thenReturn(new AccessScope("t", "u", Set.of(1001L)));
        var policy = new BusinessEvidencePolicy();
        assertDoesNotThrow(() -> policy.checkVideoQuery(context,1001,210000L,270000L));
        assertThrows(AgentException.class, () -> policy.checkVideoQuery(context,1001,209999L,270000L));
        assertThrows(AgentException.class, () -> policy.checkVideoQuery(context,1002,210000L,270000L));
    }
    @Test void malformedOrOverwideFrozenWindowsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CurrentVideoWindow.from(spec("current_playback_default", 209999, 300000)));
        assertThrows(IllegalArgumentException.class, () -> CurrentVideoWindow.from(spec("current_playback_default", 210000, 299999)));
    }
}
