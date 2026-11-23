package com.tianmu.agent.config;

/** Reader is always available. New runs capture a snapshot by default. */
@org.springframework.boot.context.properties.ConfigurationProperties("agent.policy-snapshot")
public record PolicySnapshotProperties(@org.springframework.boot.context.properties.bind.DefaultValue("true") boolean enabled) {}
