package com.resolvos.engine;

import java.math.BigDecimal;
import java.util.UUID;

public record ResolveCaseRequest(
        UUID tenantId,
        UUID caseId,
        UUID customerId,
        UUID transactionId,
        String ticketText,
        BigDecimal requestedAmount,
        String traceId
) {}
