package com.resolvos.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "resolvos.embedding")
public record EmbeddingProperties(
        String provider,
        String model,
        int dimensions,
        String baseUrl
) {
    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) provider = "sim";
        if (model == null || model.isBlank()) model = "text-embedding-3-small";
        if (dimensions <= 0) dimensions = 768;
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:8000/v1";
    }
}
