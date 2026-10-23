package com.resolvos.enforcement;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record EnforcementContext(
        String decision,                     // APPROVE_REFUND|PARTIAL_REFUND|REJECT|NEEDS_INFO
        BigDecimal amount,
        BigDecimal confidence,
        String riskLevel,                    // LOW|MEDIUM|HIGH
        UUID caseTenantId,
        UUID customerTenantId,
        UUID transactionTenantId,
        List<UUID> chunkTenantIds,
        int activePolicyCitationsCount,
        BigDecimal transactionAmount,
        BigDecimal customerPeriodRefunds,
        BigDecimal maxRefundPerCustomer,
        boolean transactionExists,
        boolean transactionBelongsToCustomer,
        boolean transactionWithinWindow,
        boolean transactionAlreadyRefunded,
        BigDecimal minConfidence,
        BigDecimal autoApproveLimit,
        BigDecimal hitlTriggerLimit
) {}
