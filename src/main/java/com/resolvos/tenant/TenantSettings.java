package com.resolvos.tenant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-tenant thresholds and settings.
 * Primary key is tenant_id (1:1 with Tenant).
 * Not RLS-protected itself; served only after tenant auth.
 */
@Entity
@Table(name = "tenant_settings")
public class TenantSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /** Refunds below this amount are auto-approved without HITL. */
    @Column(name = "auto_approve_limit", nullable = false, precision = 14, scale = 2)
    private BigDecimal autoApproveLimit;

    /** Refunds above this amount always escalate to human review. */
    @Column(name = "hitl_trigger_limit", nullable = false, precision = 14, scale = 2)
    private BigDecimal hitlTriggerLimit;

    /** Per-customer cumulative refund cap in the refund window. */
    @Column(name = "max_refund_per_customer", nullable = false, precision = 14, scale = 2)
    private BigDecimal maxRefundPerCustomer;

    /** Rolling window in days over which per-customer cap applies. */
    @Column(name = "refund_period_days", nullable = false)
    private int refundPeriodDays = 30;

    /** Days after transaction within which a refund can be requested. */
    @Column(name = "refund_window_days", nullable = false)
    private int refundWindowDays = 30;

    /** Minimum AI confidence [0,1] for auto-approval. */
    @Column(name = "min_confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal minConfidence;

    protected TenantSettings() {}

    public TenantSettings(UUID tenantId, BigDecimal autoApproveLimit,
                          BigDecimal hitlTriggerLimit, BigDecimal maxRefundPerCustomer,
                          BigDecimal minConfidence) {
        this.tenantId = tenantId;
        this.autoApproveLimit = autoApproveLimit;
        this.hitlTriggerLimit = hitlTriggerLimit;
        this.maxRefundPerCustomer = maxRefundPerCustomer;
        this.minConfidence = minConfidence;
    }

    public UUID getTenantId() { return tenantId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAutoApproveLimit() { return autoApproveLimit; }
    public BigDecimal getHitlTriggerLimit() { return hitlTriggerLimit; }
    public BigDecimal getMaxRefundPerCustomer() { return maxRefundPerCustomer; }
    public int getRefundPeriodDays() { return refundPeriodDays; }
    public int getRefundWindowDays() { return refundWindowDays; }
    public BigDecimal getMinConfidence() { return minConfidence; }

    // Setters needed for PUT /tenants/{id}/settings
    public void setAutoApproveLimit(BigDecimal v) { this.autoApproveLimit = v; }
    public void setHitlTriggerLimit(BigDecimal v) { this.hitlTriggerLimit = v; }
    public void setMaxRefundPerCustomer(BigDecimal v) { this.maxRefundPerCustomer = v; }
    public void setRefundPeriodDays(int v) { this.refundPeriodDays = v; }
    public void setRefundWindowDays(int v) { this.refundWindowDays = v; }
    public void setMinConfidence(BigDecimal v) { this.minConfidence = v; }
}
