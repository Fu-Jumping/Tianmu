package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"agent.bridge.enabled","agent.content-processing.enabled"},havingValue="true")
@EnableConfigurationProperties(ContentProcessingProperties.class)
public class ContentProcessingConfiguration {
    @Bean BusinessContentAuthority businessContentAuthority(DataSource source,
            org.springframework.beans.factory.ObjectProvider<io.minio.MinioClient> minio,
            BridgeProperties bridge,ContentProcessingProperties policy,
            @Value("${minio.bucket-name:tianmu-upload}")String bucket) {
        // Authority reads the primary database, not a potentially lagging read replica.
        var media=new BusinessVideoContent(new JdbcTemplate(source),minio.getIfAvailable(),bucket);
        return new BusinessContentAuthority(source,media,bridge.tenantId(),policy.grantSeconds(),policy.querySeconds());
    }
}
