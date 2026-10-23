package com.resolvos.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from resolvos.jwt.* in application.yml.
 * Startup fails if JWT_SECRET is absent or shorter than 32 bytes.
 */
@ConfigurationProperties(prefix = "resolvos.jwt")
public record JwtProperties(
        String secret,
        long accessTokenTtlMinutes,
        long refreshTokenTtlDays
) {
    // Compact canonical constructor — validates at bind time
    public JwtProperties {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                "JWT_SECRET must be set and at least 32 bytes. " +
                "Add JWT_SECRET to your environment or .env file."
            );
        }
    }
}
