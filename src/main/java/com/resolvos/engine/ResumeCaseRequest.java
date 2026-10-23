package com.resolvos.engine;

import java.math.BigDecimal;
import java.util.UUID;

public record ResumeCaseRequest(
        UUID tenantId,
        UUID caseId,
        String traceId,
        String reviewer,
        String decision,
        BigDecimal approvedAmount,
        String comment
) {}
