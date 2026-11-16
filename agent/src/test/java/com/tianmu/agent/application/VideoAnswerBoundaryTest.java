package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.TaskTypes;
import com.tianmu.agent.runtime.RunState;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoAnswerBoundaryTest {
    @Test void rejectsHostFieldNamesButAcceptsUserFacingRangeAndMath() {
        assertThrows(AgentException.class, () -> VideoAnswerBoundary.verifyVisibleText("依据 04:30–05:00（playback_range 04:30–05:00）"));
        assertThrows(AgentException.class, () -> VideoAnswerBoundary.verifyVisibleText("startMs: 1000"));
        assertDoesNotThrow(() -> VideoAnswerBoundary.verifyVisibleText("这里的定义域记为 $D_f$。参考片段：04:30–05:00。"));
        assertDoesNotThrow(() -> VideoAnswerBoundary.verifyVisibleText("例子中的 my_startMs_value 是变量名。"));
    }
    @Test void buffersVideoTasksWithoutChangingOrdinaryConversation() {
        var run = mock(RunState.class);
        var task = mock(TaskTypes.PreparedTask.class);
        when(run.task()).thenReturn(task);
        when(task.specification()).thenReturn(new TaskTypes.Specification("video.content", Map.of("video_id", 1001L), Map.of()));
        var boundary = new VideoAnswerBoundary();
        assertFalse(boundary.permitsStreaming(run));
        when(task.specification()).thenReturn(new TaskTypes.Specification("conversation", Map.of(), Map.of()));
        assertTrue(boundary.permitsStreaming(run));
    }
}
