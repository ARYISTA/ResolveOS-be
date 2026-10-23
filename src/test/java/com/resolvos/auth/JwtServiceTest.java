package com.resolvos.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.resolvos.common.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET_32 = "01234567890123456789012345678901";
    private static final String OTHER_SECRET_32 = "abcdefghijklmnopqrstuvwxyz123456";

    private JwtService jwtService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(SECRET_32, 15, 7);
        SecretKeySpec secretKey = new SecretKeySpec(SECRET_32.getBytes(), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        var decoder = NimbusJwtDecoder.withSecretKey(secretKey).build();
        jwtService = new JwtService(encoder, decoder, jwtProperties);
    }

    @Test
    void generatesAndValidatesAccessToken() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        User user = new User(userId, tenantId, "admin@resolvos.internal", "hashed_password",
                Role.TENANT_ADMIN, "Admin User");

        String token = jwtService.generateAccessToken(user);
        assertThat(token).isNotBlank();

        Jwt jwt = jwtService.validateAccessToken(token);
        assertThat(jwt.getSubject()).isEqualTo("admin@resolvos.internal");
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(tenantId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("TENANT_ADMIN");
        assertThat(jwt.getClaimAsString("userId")).isEqualTo(userId.toString());
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        SecretKeySpec otherKey = new SecretKeySpec(OTHER_SECRET_32.getBytes(), "HmacSHA256");
        var otherEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(otherKey));
        JwtService otherJwtService = new JwtService(otherEncoder, null, jwtProperties);

        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        User user = new User(userId, tenantId, "attacker@test.com", "pw",
                Role.SUPPORT_AGENT, "Attacker");

        String rogueToken = otherJwtService.generateAccessToken(user);

        assertThatThrownBy(() -> jwtService.validateAccessToken(rogueToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        SecretKeySpec secretKey = new SecretKeySpec(SECRET_32.getBytes(), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));

        Instant pastIssuedAt = java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.MINUTES);
        Instant pastExpiresAt = java.time.Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES);

        org.springframework.security.oauth2.jwt.JwsHeader header =
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        org.springframework.security.oauth2.jwt.JwtClaimsSet claims =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .subject("expired@resolvos.internal")
                        .issuedAt(pastIssuedAt)
                        .expiresAt(pastExpiresAt)
                        .claim("tenantId", UUID.randomUUID().toString())
                        .claim("role", "REVIEWER")
                        .claim("userId", UUID.randomUUID().toString())
                        .build();

        String expiredToken = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();

        assertThatThrownBy(() -> jwtService.validateAccessToken(expiredToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void generatesValidOpaqueRefreshToken() {
        String refreshToken = jwtService.generateRawRefreshToken();
        assertThat(refreshToken).isNotBlank();
        UUID uuid = UUID.fromString(refreshToken);
        assertThat(uuid).isNotNull();
    }
}
