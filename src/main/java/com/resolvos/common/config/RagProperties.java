package com.resolvos.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "resolvos.rag")
public record RagProperties(
        int chunkSizeTokens,
        int chunkOverlapTokens,
        int topK
) {
    public RagProperties {
        if (chunkSizeTokens <= 0) chunkSizeTokens = 400;
        if (chunkOverlapTokens < 0) chunkOverlapTokens = 60;
        if (topK <= 0) topK = 4;
    }
}
