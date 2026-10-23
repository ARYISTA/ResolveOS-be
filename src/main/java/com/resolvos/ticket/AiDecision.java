package com.resolvos.ticket;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_decisions")
public class AiDecision {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false, unique = true)
    private UUID ticketId;

    @Column(nullable = false)
    private String decision;

    @Column(name = "recommended_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal recommendedAmount;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "reasoning_summary", nullable = false, columnDefinition = "TEXT")
    private String reasoningSummary;

    @Column(name = "requires_human_review", nullable = false)
    private boolean requiresHumanReview = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AiDecision() {}

    public AiDecision(UUID id, UUID ticketId, String decision, BigDecimal recommendedAmount,
                      BigDecimal confidence, String reasoningSummary, boolean requiresHumanReview) {
        this.id = id != null ? id : UUID.randomUUID();
        this.ticketId = ticketId;
        this.decision = decision;
        this.recommendedAmount = recommendedAmount;
        this.confidence = confidence;
        this.reasoningSummary = reasoningSummary;
        this.requiresHumanReview = requiresHumanReview;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTicketId() { return ticketId; }
    public String getDecision() { return decision; }
    public BigDecimal getRecommendedAmount() { return recommendedAmount; }
    public BigDecimal getConfidence() { return confidence; }
    public String getReasoningSummary() { return reasoningSummary; }
    public boolean isRequiresHumanReview() { return requiresHumanReview; }
    public Instant getCreatedAt() { return createdAt; }
}
