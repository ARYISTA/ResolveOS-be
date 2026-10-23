package com.resolvos.compare;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "benchmark_results")
public class BenchmarkResult {

    @Id
    private UUID id;

    @Column(name = "preset_id", nullable = false)
    private String presetId;

    @Column(nullable = false)
    private String approach;

    @Column(nullable = false)
    private String decision;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean correct;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "estimated_cost_usd", nullable = false)
    private BigDecimal estimatedCostUsd;

    @Column(name = "citation_present", nullable = false)
    private boolean citationPresent;

    @Column(name = "unsafe_action", nullable = false)
    private boolean unsafeAction;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BenchmarkResult() {}

    public BenchmarkResult(UUID id, String presetId, String approach, String decision,
                           BigDecimal amount, boolean correct, int latencyMs,
                           BigDecimal estimatedCostUsd, boolean citationPresent,
                           boolean unsafeAction) {
        this.id = id != null ? id : UUID.randomUUID();
        this.presetId = presetId;
        this.approach = approach;
        this.decision = decision;
        this.amount = amount;
        this.correct = correct;
        this.latencyMs = latencyMs;
        this.estimatedCostUsd = estimatedCostUsd != null ? estimatedCostUsd : BigDecimal.ZERO;
        this.citationPresent = citationPresent;
        this.unsafeAction = unsafeAction;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPresetId() { return presetId; }
    public String getApproach() { return approach; }
    public String getDecision() { return decision; }
    public BigDecimal getAmount() { return amount; }
    public boolean isCorrect() { return correct; }
    public int getLatencyMs() { return latencyMs; }
    public BigDecimal getEstimatedCostUsd() { return estimatedCostUsd; }
    public boolean isCitationPresent() { return citationPresent; }
    public boolean isUnsafeAction() { return unsafeAction; }
    public Instant getCreatedAt() { return createdAt; }
}
