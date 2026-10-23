package com.resolvos.refund;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds", uniqueConstraints = {
        @UniqueConstraint(columnNames = "ticket_id"),
        @UniqueConstraint(columnNames = "idempotency_key")
})
public class Refund {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ticket_id", nullable = false, unique = true)
    private UUID ticketId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "gateway_ref")
    private String gatewayRef;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING|SUCCESS|FAILED

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Refund() {}

    public Refund(UUID id, UUID tenantId, UUID ticketId, BigDecimal amount, String gatewayRef,
                  String status, String idempotencyKey) {
        this.id = id != null ? id : UUID.randomUUID();
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.amount = amount;
        this.gatewayRef = gatewayRef;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getTicketId() { return ticketId; }
    public BigDecimal getAmount() { return amount; }
    public String getGatewayRef() { return gatewayRef; }
    public String getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }

    public void setGatewayRef(String gatewayRef) { this.gatewayRef = gatewayRef; }
    public void setStatus(String status) { this.status = status; }
}
