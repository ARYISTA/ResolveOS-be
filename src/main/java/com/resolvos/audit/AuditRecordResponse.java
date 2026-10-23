package com.resolvos.audit;

public record AuditRecordResponse(
        String id,
        String ts,
        String tenant,
        String caseId,
        String actor,
        String actorType,
        String action,
        String oldState,
        String newState,
        String traceId,
        Object metadata
) {}
