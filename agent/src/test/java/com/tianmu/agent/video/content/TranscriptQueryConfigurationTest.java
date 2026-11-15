package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.api.TranscriptQueryController;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TranscriptQueryConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner context(){return new ApplicationContextRunner()
            .withUserConfiguration(TranscriptQueryConfiguration.class,TranscriptQueryController.class)
            .withBean(HikariDataSource.class,()->mock(HikariDataSource.class)).withBean(Flyway.class,()->mock(Flyway.class))
            .withBean(ObjectMapper.class,()->new ObjectMapper().findAndRegisterModules())
            .withBean(IdentityPort.class,()->mock(IdentityPort.class)).withBean(ContentReadAuthority.class,()->mock(ContentReadAuthority.class));}
    @Test void defaultOffCreatesNeitherEndpointNorFileReader(){context().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(TranscriptRetrievalService.class);assertThat(c).doesNotHaveBean(TranscriptQueryController.class);});}
    @Test void explicitActivationRequiresPinnedVersionAndCreatesOneReader(){
        context().withPropertyValues("agent.video.transcript-query.enabled=true","agent.video.transcript-query.stage-version=asr-v1","agent.video.transcript-query.result-directory="+directory)
                .run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(TranscriptRetrievalService.class);assertThat(c).hasSingleBean(TranscriptQueryController.class);});
    }
    @Test void invalidQuotaPreventsActivation(){
        context().withPropertyValues("agent.video.transcript-query.enabled=true","agent.video.transcript-query.stage-version=asr-v1","agent.video.transcript-query.result-directory="+directory,"agent.video.transcript-query.concurrent-queries=0")
                .run(c->assertThat(c).hasFailed());
    }
}
