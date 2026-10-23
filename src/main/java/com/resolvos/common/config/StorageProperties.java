package com.resolvos.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "resolvos.storage")
public record StorageProperties(
        String baseDir
) {
    public StorageProperties {
        if (baseDir == null || baseDir.isBlank()) baseDir = "./data/policies";
    }
}
