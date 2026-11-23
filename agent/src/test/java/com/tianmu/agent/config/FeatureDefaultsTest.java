package com.tianmu.agent.config;

import com.tianmu.agent.adapters.storage.MilvusConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class FeatureDefaultsTest {
    @Test
    void missingProvidersKeepDemoAndReportsUsable() {
        try (var context = application().run()) {
            var env = context.getEnvironment();
            assertFalse(env.getRequiredProperty("agent.memory.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.compaction.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.memory.embedding.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.feedback.embedding.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.feedback.milvus.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.feedback.compression.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.feedback.rerank.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.policy-snapshot.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.feedback.buckets.centering-enabled", Boolean.class));
        }
    }

    @Test
    void configuredProvidersActivateFeaturesAndRespectOptOut() {
        var app = application();
        app.setAdditionalProfiles("persistent");
        String[] settings = {
                "--agent.memory.embedding.endpoint=https://memory.example/embeddings",
                "--agent.memory.embedding.token-file=memory-token",
                "--agent.memory.embedding.revision=memory-v1",
                "--agent.feedback.embedding.token-file=feedback-token",
                "--agent.feedback.embedding.revision=feedback-v1",
                "--agent.feedback.milvus.endpoint=http://milvus.example",
                "--agent.feedback.milvus.token-file=milvus-token",
                "--agent.feedback.milvus.collection=feedback",
                "--agent.feedback.milvus.dimension=2560",
                "--agent.feedback.rerank.endpoint=https://feedback.example/reranks",
                "--agent.feedback.rerank.api-key-file=rerank-token"
        };
        try (var context = app.run(settings)) {
            var env = context.getEnvironment();
            assertTrue(env.getRequiredProperty("agent.memory.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.compaction.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.memory.embedding.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.feedback.embedding.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.feedback.milvus.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.feedback.compression.enabled", Boolean.class));
            assertTrue(env.getRequiredProperty("agent.feedback.rerank.enabled", Boolean.class));
            assertNull(Binder.get(env).bind("agent.feedback.milvus", MilvusConfiguration.Properties.class)
                    .get().provenanceEnabled());
        }
        String[] disabled = java.util.Arrays.copyOf(settings, settings.length + 1);
        disabled[settings.length] = "--agent.feedback.milvus.enabled=false";
        var disabledApp = application();
        disabledApp.setAdditionalProfiles("persistent");
        try (var context = disabledApp.run(disabled)) {
            var env = context.getEnvironment();
            assertFalse(env.getRequiredProperty("agent.feedback.milvus.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.feedback.compression.enabled", Boolean.class));
            assertFalse(env.getRequiredProperty("agent.feedback.rerank.enabled", Boolean.class));
        }
    }

    private static SpringApplication application() {
        var app = new SpringApplication(Fixture.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        app.setEnvironment(environment);
        return app;
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture {}
}
