package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.content-reading")
public record ContentReadingProperties(@DefaultValue("5") int querySeconds) {
    public ContentReadingProperties {if(querySeconds<1 || querySeconds>10)throw new IllegalArgumentException("Invalid content read query timeout");}
}
