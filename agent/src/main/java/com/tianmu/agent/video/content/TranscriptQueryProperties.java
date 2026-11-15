package com.tianmu.agent.video.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.video.transcript-query")
public record TranscriptQueryProperties(String stageVersion,String resultDirectory,
        @DefaultValue("60000") int lookbackMillis,@DefaultValue("128") int candidateLimit,
        @DefaultValue("12") int excerptLimit,@DefaultValue("8000") int textLimit,
        @DefaultValue("4194304") int readBytes,@DefaultValue("4") int concurrentQueries,
        @DefaultValue("15") int requestSeconds) {
    public TranscriptQueryProperties {
        ContentWork.identifier(stageVersion);
        if(resultDirectory==null || resultDirectory.isBlank() || lookbackMillis<1000 || lookbackMillis>300000
                || candidateLimit<1 || candidateLimit>256 || excerptLimit<1 || excerptLimit>32
                || textLimit<500 || textLimit>12000 || readBytes<262144 || readBytes>16777216
                || concurrentQueries<1 || concurrentQueries>32 || requestSeconds<1 || requestSeconds>60)
            throw new IllegalArgumentException("Invalid transcript query policy");
    }
}
