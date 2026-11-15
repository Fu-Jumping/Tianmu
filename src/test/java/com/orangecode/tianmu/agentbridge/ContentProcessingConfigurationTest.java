package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContentProcessingConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner context(){return new ApplicationContextRunner()
            .withUserConfiguration(ContentProcessingConfiguration.class,ContentProcessingController.class)
            .withBean(DataSource.class,()->mock(DataSource.class))
            .withBean(BridgeProperties.class,()->new BridgeProperties("site",Set.of("1"),"Asia/Shanghai",2));}
    @Test void featureIsOffWithoutExplicitActivation(){context().run(c->{assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(BusinessContentAuthority.class);assertThat(c).doesNotHaveBean(ContentProcessingController.class);});}
    @Test void enabledFeatureCreatesExactlyOneController()throws Exception {
        Path token=directory.resolve("service-token");Files.writeString(token,"local-test-platform-token-32-characters");
        context().withPropertyValues("agent.bridge.enabled=true","agent.content-processing.enabled=true","agent.content-processing.token-file="+token)
                .run(c->{assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(ContentProcessingController.class);assertThat(c).hasSingleBean(BusinessContentAuthority.class);});
    }
    @Test void weakTokenPreventsActivation()throws Exception {
        Path token=directory.resolve("weak-token");Files.writeString(token,"weak");
        context().withPropertyValues("agent.bridge.enabled=true","agent.content-processing.enabled=true","agent.content-processing.token-file="+token)
                .run(c->assertThat(c).hasFailed());
    }
}
