package com.tianmu.agent.video.content;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("agent.video.asr")
public record BailianAsrProperties(String keyFile,@DefaultValue("beijing") String region,@DefaultValue("60000") int timeoutMillis) {
    public BailianAsrProperties {
        if(keyFile==null || keyFile.isBlank() || !java.util.Set.of("beijing","singapore").contains(region) || timeoutMillis<1000 || timeoutMillis>90000)
            throw new IllegalArgumentException("Invalid independent ASR configuration");
    }
}
