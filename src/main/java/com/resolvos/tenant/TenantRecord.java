package com.resolvos.tenant;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public record TenantRecord(
        UUID id,
        String slug,
        String name,
        String domain,
        Instant createdAt
) {
    public static TenantRecord from(Tenant t) {
        return new TenantRecord(
                t.getId(),
                t.getSlug(),
                t.getName(),
                t.getDomain(),
                t.getCreatedAt()
        );
    }

    public record CreateRequest(
            @NotBlank String slug,
            @NotBlank String name,
            @NotBlank String domain
    ) {}
}
