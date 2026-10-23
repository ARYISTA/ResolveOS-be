package com.resolvos.auth;

import com.resolvos.common.config.JwtProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Issues and validates HS256 JWTs using Nimbus via Spring Security's bundled support.
 * Claims: sub (email), tenantId (UUID string), role.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties props;

    public JwtService(JwtEncoder encoder, JwtDecoder decoder, JwtProperties props) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.props = props;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(props.accessTokenTtlMinutes(), ChronoUnit.MINUTES);

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .subject(user.getEmail())
                .issuedAt(now)
                .expiresAt(expiry)
                .claim("role", user.getRole().name())
                .claim("userId", user.getId().toString());

        if (user.getTenantId() != null) {
            claimsBuilder.claim("tenantId", user.getTenantId().toString());
        }

        JwtClaimsSet claims = claimsBuilder.build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Generates a raw opaque refresh token (UUID). The caller stores its hash. */
    public String generateRawRefreshToken() {
        return UUID.randomUUID().toString();
    }

    /** Validates and returns the Jwt claims. Throws JwtException on invalid/expired. */
    public Jwt validateAccessToken(String token) {
        return decoder.decode(token);
    }
}
