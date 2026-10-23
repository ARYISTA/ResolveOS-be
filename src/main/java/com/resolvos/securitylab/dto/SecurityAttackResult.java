package com.resolvos.securitylab.dto;

public record SecurityAttackResult(
        String attackId,
        String attack,
        String caseId,
        String layerCaught,
        String caughtBy,
        String verdict,
        boolean blocked,
        String reason,
        String auditHint,
        String executedAt,
        String traceId
) {}
