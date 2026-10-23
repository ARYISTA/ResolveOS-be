package com.resolvos.ticket;

import java.time.Instant;
import java.util.UUID;

public record AuditLogDto(
        UUID id,
        Instant ts,
        String actor,
        String actorType,
        String action,
        String oldState,
        String newState,
        String traceId,
        Object metadata
) {}
