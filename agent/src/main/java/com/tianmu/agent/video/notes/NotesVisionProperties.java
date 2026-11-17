package com.tianmu.agent.video.notes;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Optional notes-only compatible vision endpoint; blank defaults reuse the foreground adapter. */
@ConfigurationProperties("agent.video.notes.vision")
public record NotesVisionProperties(@DefaultValue("") String model,@DefaultValue("") String baseUrl,@DefaultValue("") String keyFile) {
    public NotesVisionProperties {
        if(model==null || baseUrl==null || keyFile==null)throw new IllegalArgumentException("Invalid notes vision configuration");
        if(!model.isEmpty() || !baseUrl.isEmpty() || !keyFile.isEmpty()) {
            if(!model.matches("[A-Za-z0-9_.:/-]{1,100}") || keyFile.isBlank())throw new IllegalArgumentException("Configure complete notes vision identity");
            URI endpoint=URI.create(baseUrl);
            if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null)
                throw new IllegalArgumentException("Configure an HTTPS notes vision endpoint");
        }
    }
    public boolean configured(){return !model.isEmpty();}
    @Override public String toString(){return "NotesVisionProperties[configured="+configured()+"]";}
}
