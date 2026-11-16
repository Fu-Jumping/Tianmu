package com.tianmu.agent.video;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("agent.video.reuse")
public record VideoReusePolicy(@DefaultValue("false") boolean enabled) {
    public static VideoReusePolicy defaults(){return new VideoReusePolicy(false);}
}
