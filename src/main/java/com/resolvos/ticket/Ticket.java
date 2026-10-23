package com.resolvos.ticket;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_number", insertable = false, updatable = false)
    private String caseNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "requested_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal requestedAmount;

    @Column(nullable = false)
    private String status = "RECEIVED";

    @Column
    private String outcome;

    @Column(name = "trace_id", nullable = false)
    private String traceId;

    @Column(name = "event_seq", nullable = false)
    private long eventSeq = 0L;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Ticket() {}

    public Ticket(UUID id, UUID tenantId, UUID customerId, UUID transactionId,
                  String message, BigDecimal requestedAmount, String traceId, UUID createdBy) {
        this.id = id != null ? id : UUID.randomUUID();
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.transactionId = transactionId;
        this.message = message;
        this.requestedAmount = requestedAmount;
        this.traceId = traceId;
        this.createdBy = createdBy;
        this.status = "RECEIVED";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getCaseNumber() { return caseNumber != null ? caseNumber : "CASE-" + id.toString().substring(0, 8).toUpperCase(); }
    public UUID getCustomerId() { return customerId; }
    public UUID getTransactionId() { return transactionId; }
    public String getMessage() { return message; }
    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public String getStatus() { return status; }
    public String getOutcome() { return outcome; }
    public String getTraceId() { return traceId; }
    public long getEventSeq() { return eventSeq; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public void incrementEventSeq() { this.eventSeq++; }
}
