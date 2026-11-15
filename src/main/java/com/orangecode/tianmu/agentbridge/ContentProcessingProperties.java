package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.content-processing")
public record ContentProcessingProperties(String tokenFile,@DefaultValue("900") int grantSeconds,
                                          @DefaultValue("5") int querySeconds) {
    public ContentProcessingProperties {
        if(tokenFile==null || tokenFile.isBlank() || grantSeconds<30 || grantSeconds>3600 || querySeconds<1 || querySeconds>10)
            throw new IllegalArgumentException("Configure bounded content processing authority and token file");
    }
}
