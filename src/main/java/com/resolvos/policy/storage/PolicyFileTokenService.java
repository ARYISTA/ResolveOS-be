package com.resolvos.policy.storage;

import com.resolvos.common.config.JwtProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PolicyFileTokenService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final long TOKEN_TTL_SECONDS = 300; // 5 minutes validity per PRD

    private final byte[] secretKey;

    public PolicyFileTokenService(JwtProperties jwtProperties) {
        this.secretKey = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
    }

    public String generateToken(UUID tenantId, UUID policyId, int version) {
        long expiresAtEpochMs = Instant.now().plusSeconds(TOKEN_TTL_SECONDS).toEpochMilli();
        String payload = tenantId + ":" + policyId + ":" + version + ":" + expiresAtEpochMs;
        String signature = sign(payload);
        return payload + ":" + signature;
    }

    public boolean validateToken(String token, UUID tenantId, UUID policyId, int version) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String[] parts = token.split(":");
        if (parts.length != 5) {
            return false;
        }

        try {
            UUID tokenTenantId = UUID.fromString(parts[0]);
            UUID tokenPolicyId = UUID.fromString(parts[1]);
            int tokenVersion = Integer.parseInt(parts[2]);
            long expiresAtEpochMs = Long.parseLong(parts[3]);
            String signature = parts[4];

            if (!tokenTenantId.equals(tenantId) || !tokenPolicyId.equals(policyId) || tokenVersion != version) {
                return false;
            }

            if (Instant.now().toEpochMilli() > expiresAtEpochMs) {
                return false;
            }

            String expectedPayload = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
            String expectedSignature = sign(expectedPayload);

            return MessageDigest.isEqual(
                    signature.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate HMAC token", e);
        }
    }
}
