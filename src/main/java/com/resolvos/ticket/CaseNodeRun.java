package com.resolvos.ticket;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "case_node_runs")
@IdClass(CaseNodeRun.CaseNodeRunId.class)
public class CaseNodeRun {

    public static class CaseNodeRunId implements Serializable {
        private UUID ticketId;
        private String node;

        public CaseNodeRunId() {}

        public CaseNodeRunId(UUID ticketId, String node) {
            this.ticketId = ticketId;
            this.node = node;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CaseNodeRunId that)) return false;
            return Objects.equals(ticketId, that.ticketId) && Objects.equals(node, that.node);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ticketId, node);
        }
    }

    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Id
    @Column(nullable = false)
    private String node;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING|RUNNING|SUCCESS|WARNING|FAILED|SKIPPED|WAITING_HUMAN

    @Column(name = "duration_ms")
    private Long durationMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String output;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected CaseNodeRun() {}

    public CaseNodeRun(UUID ticketId, String node, String status) {
        this.ticketId = ticketId;
        this.node = node;
        this.status = status;
        this.startedAt = Instant.now();
    }

    public UUID getTicketId() { return ticketId; }
    public String getNode() { return node; }
    public String getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
    public String getOutput() { return output; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public void setOutput(String output) { this.output = output; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
