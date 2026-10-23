package com.resolvos.ticket;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "enforcement_results")
public class EnforcementResult {

    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(nullable = false)
    private String verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String checks;

    @Column
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EnforcementResult() {}

    public EnforcementResult(UUID ticketId, String verdict, String checks, String reason) {
        this.ticketId = ticketId;
        this.verdict = verdict;
        this.checks = checks;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getTicketId() { return ticketId; }
    public String getVerdict() { return verdict; }
    public String getChecks() { return checks; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
