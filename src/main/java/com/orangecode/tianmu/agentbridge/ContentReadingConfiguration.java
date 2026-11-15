package com.orangecode.tianmu.agentbridge;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"agent.bridge.enabled","agent.content-reading.enabled"},havingValue="true")
@EnableConfigurationProperties(ContentReadingProperties.class)
public class ContentReadingConfiguration {
    @Bean BusinessContentIdentityReader businessContentIdentityReader(DataSource source,
            org.springframework.beans.factory.ObjectProvider<io.minio.MinioClient> minio,BridgeProperties bridge,
            ContentReadingProperties policy,@Value("${minio.bucket-name:tianmu-upload}")String bucket){
        return new BusinessContentIdentityReader(source,new BusinessVideoContent(new JdbcTemplate(source),minio.getIfAvailable(),bucket),bridge.tenantId(),policy.querySeconds());
    }
}
