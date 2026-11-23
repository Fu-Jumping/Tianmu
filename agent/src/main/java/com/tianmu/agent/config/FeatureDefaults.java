package com.tianmu.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/** Resolve provider-backed feature defaults after loading environment and profile configuration. */
public final class FeatureDefaults implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var resolved = new LinkedHashMap<String, Object>();
        String memoryEmbedding = "agent.memory.embedding";
        String feedbackEmbedding = "agent.feedback.embedding";
        String milvus = "agent.feedback.milvus";
        String rerank = "agent.feedback.rerank";

        resolve(environment, resolved, memoryEmbedding,
                () -> environment.getProperty("agent.memory.enabled", Boolean.class, false)
                        && configured(environment, memoryEmbedding, "endpoint", "token-file", "revision"));
        boolean embeddings = resolve(environment, resolved, feedbackEmbedding,
                () -> configured(environment, feedbackEmbedding, "endpoint", "token-file", "revision"));
        boolean vectors = resolve(environment, resolved, milvus,
                () -> configured(environment, milvus, "endpoint", "token-file", "collection")
                        && environment.getProperty(milvus + ".dimension", Integer.class, 0) > 0);
        boolean compression = resolve(environment, resolved, "agent.feedback.compression", () -> embeddings && vectors);
        resolve(environment, resolved, rerank, () -> compression && configured(environment, rerank, "endpoint")
                && (configured(environment, rerank, "api-key-file") || configured(environment, rerank, "api-key")));

        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("providerFeatureDefaults", resolved));
        }
    }

    private static boolean resolve(ConfigurableEnvironment environment, Map<String, Object> resolved,
                                   String prefix, BooleanSupplier ready) {
        String key = prefix + ".enabled";
        String value = environment.getProperty(key, "auto");
        if ("auto".equalsIgnoreCase(value.strip())) {
            boolean enabled = ready.getAsBoolean();
            resolved.put(key, enabled);
            return enabled;
        }
        return environment.getProperty(key, Boolean.class, false);
    }

    private static boolean configured(ConfigurableEnvironment environment, String prefix, String... fields) {
        for (String field : fields) {
            if (!StringUtils.hasText(environment.getProperty(prefix + "." + field))) {
                return false;
            }
        }
        return true;
    }
}
