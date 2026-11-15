package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.video.transcript-query.enabled",havingValue="true")
@EnableConfigurationProperties(TranscriptQueryProperties.class)
public class TranscriptQueryConfiguration {
    @Bean TranscriptRetrievalService transcriptRetrievalService(HikariDataSource source,Flyway migrations,
            ObjectMapper json,ContentReadAuthority authority,TranscriptQueryProperties policy)throws Exception {
        return new TranscriptRetrievalService(new MysqlContentWorkStore(source,json),
                new FileContentTranscriptStore(Path.of(policy.resultDirectory()),json),authority,policy);
    }
}
