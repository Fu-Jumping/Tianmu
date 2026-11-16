package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.TaskConditions;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.spi.ContextAuthority;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Buffers video answers and rejects accidental exposure of host-only fields. */
@Component
public final class VideoAnswerBoundary implements ContextAuthority {
    private static final Pattern INTERNAL_FIELD = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])(?:playback_range|available_prior_range|start_ms|end_ms|startMs|endMs|atMs|evidence_id|sourceVersion)(?![\\p{L}\\p{N}_])");

    static boolean isVideo(RunState run) {
        return run.task() != null && (run.task().specification().typed() instanceof TaskConditions.Video
                || run.task().specification().typed() instanceof TaskConditions.Transcript);
    }

    static void verifyVisibleText(String text) {
        if (text != null && INTERNAL_FIELD.matcher(text).find())
            throw new AgentException("INVALID_ANSWER", "Video answer contains an internal field");
    }

    @Override public void verify(RunState run) { }
    @Override public boolean permitsStreaming(RunState run) { return !isVideo(run); }
}
