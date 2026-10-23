package com.resolvos.refund;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    record PaymentResult(boolean success, String gatewayRef, String errorMessage) {
        public static PaymentResult success(String gatewayRef) {
            return new PaymentResult(true, gatewayRef, null);
        }
        public static PaymentResult failure(String errorMessage) {
            return new PaymentResult(false, null, errorMessage);
        }
    }

    PaymentResult processRefund(UUID tenantId, UUID ticketId, BigDecimal amount, String idempotencyKey);
}
