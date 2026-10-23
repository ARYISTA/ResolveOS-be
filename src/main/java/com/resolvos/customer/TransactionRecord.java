package com.resolvos.customer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionRecord(
        UUID id,
        UUID tenantId,
        UUID customerId,
        String type,
        BigDecimal amount,
        String status,
        String merchantOrProduct,
        Instant occurredAt,
        boolean forceGatewayFailure
) {
    public static TransactionRecord from(Transaction t) {
        return new TransactionRecord(
                t.getId(),
                t.getTenantId(),
                t.getCustomerId(),
                t.getType(),
                t.getAmount(),
                t.getStatus(),
                t.getMerchantOrProduct(),
                t.getOccurredAt(),
                t.isForceGatewayFailure()
        );
    }
}
