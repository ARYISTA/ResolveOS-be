package com.resolvos.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CORS settings bound from resolvos.cors.* */
@ConfigurationProperties(prefix = "resolvos.cors")
public record CorsProperties(String frontendOrigin) {}
