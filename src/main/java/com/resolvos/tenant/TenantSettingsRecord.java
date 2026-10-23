package com.resolvos.tenant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record TenantSettingsRecord(
        UUID tenantId,
        String currency,
        BigDecimal autoApproveLimit,
        BigDecimal hitlTriggerLimit,
        BigDecimal maxRefundPerCustomer,
        int refundPeriodDays,
        int refundWindowDays,
        BigDecimal minConfidence
) {
    public static TenantSettingsRecord from(TenantSettings s) {
        return new TenantSettingsRecord(
                s.getTenantId(),
                s.getCurrency(),
                s.getAutoApproveLimit(),
                s.getHitlTriggerLimit(),
                s.getMaxRefundPerCustomer(),
                s.getRefundPeriodDays(),
                s.getRefundWindowDays(),
                s.getMinConfidence()
        );
    }

    public record UpdateRequest(
            @NotNull @Positive BigDecimal autoApproveLimit,
            @NotNull @Positive BigDecimal hitlTriggerLimit,
            @NotNull @Positive BigDecimal maxRefundPerCustomer,
            @Positive int refundPeriodDays,
            @Positive int refundWindowDays,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal minConfidence
    ) {}
}
