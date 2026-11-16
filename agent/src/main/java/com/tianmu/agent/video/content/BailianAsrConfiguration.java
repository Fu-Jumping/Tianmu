package com.tianmu.agent.video.content;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.video.TranscriptionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.nio.file.*;
import java.time.Duration;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.video.asr.enabled",havingValue="true")
@EnableConfigurationProperties(BailianAsrProperties.class)
public class BailianAsrConfiguration {
    @Bean @Primary TranscriptionPort bailianTranscription(BailianAsrProperties policy,ObjectMapper json)throws java.io.IOException {
        var path=Path.of(policy.keyFile());
        if(Files.size(path)>4096)throw new IllegalArgumentException("Invalid ASR credential file");
        return BailianTranscriptionClient.create(policy.region(),Files.readString(path).strip(),json,Duration.ofMillis(policy.timeoutMillis()));
    }
}
