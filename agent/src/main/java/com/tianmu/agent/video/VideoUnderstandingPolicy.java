package com.tianmu.agent.video;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Startup-only experiment; changing mode invalidates the facts-cache strategy. */
@ConfigurationProperties("agent.video.understanding")
public record VideoUnderstandingPolicy(@DefaultValue("false") boolean parallel,
                                      @DefaultValue("4") int workers) {
    public VideoUnderstandingPolicy {
        if (workers < 2 || workers > 32) throw new IllegalArgumentException("Video understanding workers must be 2..32");
    }
    public static VideoUnderstandingPolicy defaults() { return new VideoUnderstandingPolicy(false, 4); }
}
