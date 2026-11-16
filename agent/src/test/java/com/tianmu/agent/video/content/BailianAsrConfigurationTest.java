package com.tianmu.agent.video.content;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.video.TranscriptionPort;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class BailianAsrConfigurationTest {
    @TempDir Path directory;
    @Test void explicitlyEnabledIndependentClientWinsOverLegacyPort() throws Exception {
        var key=directory.resolve("key");Files.writeString(key,"test-private-key");
        new ApplicationContextRunner().withUserConfiguration(BailianAsrConfiguration.class)
            .withBean(ObjectMapper.class,ObjectMapper::new)
            .withBean("legacy",TranscriptionPort.class,()->(audio,control)->"legacy")
            .withPropertyValues("agent.video.asr.enabled=true","agent.video.asr.key-file="+key)
            .run(context->{assertNull(context.getStartupFailure());assertEquals(BailianTranscriptionClient.MODEL,context.getBean(TranscriptionPort.class).modelName());});
    }
}
