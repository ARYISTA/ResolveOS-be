package com.resolvos.review;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "human_reviews")
public class HumanReview {

    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN|APPROVED|REJECTED

    @Column(nullable = false)
    private String reason;

    @Column(name = "risk_level", nullable = false)
    private String riskLevel;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "decision_amount", precision = 14, scale = 2)
    private BigDecimal decisionAmount;

    @Column
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected HumanReview() {}

    public HumanReview(UUID ticketId, String reason, String riskLevel) {
        this(ticketId, "OPEN", reason, riskLevel);
    }

    public HumanReview(UUID ticketId, String status, String reason, String riskLevel) {
        this.ticketId = ticketId;
        this.status = status;
        this.reason = reason;
        this.riskLevel = riskLevel;
        this.createdAt = Instant.now();
    }

    public UUID getTicketId() { return ticketId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public String getRiskLevel() { return riskLevel; }
    public UUID getReviewerId() { return reviewerId; }
    public BigDecimal getDecisionAmount() { return decisionAmount; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setReason(String reason) { this.reason = reason; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public void setReviewerId(UUID reviewerId) { this.reviewerId = reviewerId; }
    public void setDecisionAmount(BigDecimal decisionAmount) { this.decisionAmount = decisionAmount; }
    public void setComment(String comment) { this.comment = comment; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
