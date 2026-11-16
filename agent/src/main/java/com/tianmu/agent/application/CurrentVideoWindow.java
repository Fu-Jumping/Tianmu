package com.tianmu.agent.application;

import com.tianmu.agent.core.TaskConditions;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.util.Optional;

/** Windows are host-frozen task data, not model-selected authorization. */
public record CurrentVideoWindow(long videoId, long initialStartMs, long endMs, long allowedStartMs) {
    public CurrentVideoWindow {
        if (videoId <= 0 || allowedStartMs < 0 || initialStartMs < allowedStartMs || endMs <= initialStartMs
                || endMs - initialStartMs > 30_000 || endMs - allowedStartMs > 90_000)
            throw new IllegalArgumentException("Invalid current-video windows");
    }
    public static Optional<CurrentVideoWindow> from(Specification spec) {
        if (spec == null || !"video.content".equals(spec.type())
                || !"current_playback_default".equals(spec.provenance().get("start_ms"))
                || !"current_playback_default".equals(spec.provenance().get("end_ms"))) return Optional.empty();
        var video = (TaskConditions.Video) spec.typed();
        var reading = spec.analysis();
        if (video.startMs() == null || reading == null || reading.videoStartMs() == null
                || !video.endMs().equals(reading.videoEndMs()))
            throw new IllegalArgumentException("Current video task has inconsistent frozen bounds");
        return Optional.of(new CurrentVideoWindow(video.videoId(), video.startMs(), video.endMs(), reading.videoStartMs()));
    }
    public void check(long videoId, Long startMs, Long endMs) {
        new TaskConditions.Video(this.videoId, allowedStartMs, this.endMs).check(videoId, startMs, endMs);
    }
}
