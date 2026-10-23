package com.resolvos.ticket;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_assessments")
public class RiskAssessment {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false, unique = true)
    private UUID ticketId;

    @Column(name = "risk_level", nullable = false)
    private String riskLevel;

    @Column(name = "risk_factors", columnDefinition = "text[]")
    private String[] riskFactors = new String[0];

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RiskAssessment() {}

    public RiskAssessment(UUID id, UUID ticketId, String riskLevel, String[] riskFactors) {
        this.id = id != null ? id : UUID.randomUUID();
        this.ticketId = ticketId;
        this.riskLevel = riskLevel;
        this.riskFactors = riskFactors != null ? riskFactors : new String[0];
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public String getRiskLevel() { return riskLevel; }
    public String[] getRiskFactors() { return riskFactors; }
    public Instant getCreatedAt() { return createdAt; }
}
