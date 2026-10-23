package com.resolvos.customer;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(name = "merchant_or_product")
    private String merchantOrProduct;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "force_gateway_failure", nullable = false)
    private boolean forceGatewayFailure;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transaction() {}

    public Transaction(UUID id, UUID tenantId, UUID customerId, String type,
                       BigDecimal amount, String status, String merchantOrProduct,
                       Instant occurredAt, boolean forceGatewayFailure) {
        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.merchantOrProduct = merchantOrProduct;
        this.occurredAt = occurredAt;
        this.forceGatewayFailure = forceGatewayFailure;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCustomerId() { return customerId; }
    public String getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getMerchantOrProduct() { return merchantOrProduct; }
    public Instant getOccurredAt() { return occurredAt; }
    public boolean isForceGatewayFailure() { return forceGatewayFailure; }
    public Instant getCreatedAt() { return createdAt; }
}
