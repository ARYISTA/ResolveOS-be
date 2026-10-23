package com.resolvos.outbox;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String type; // RESOLVE_REQUESTED|RESUME_REQUESTED

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING|PROCESSING|DONE|FAILED

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, UUID tenantId, String type, String payload) {
        this.id = id != null ? id : UUID.randomUUID();
        this.tenantId = tenantId;
        this.type = type;
        this.payload = payload;
        this.status = "PENDING";
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void setStatus(String status) { this.status = status; }
    public void incrementAttempts() { this.attempts++; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
