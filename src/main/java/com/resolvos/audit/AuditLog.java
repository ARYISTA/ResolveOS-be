package com.resolvos.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant ts = Instant.now();

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(nullable = false)
    private String actor;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(nullable = false)
    private String action;

    @Column(name = "old_state")
    private String oldState;

    @Column(name = "new_state")
    private String newState;

    @Column(name = "trace_id")
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    protected AuditLog() {}

    public AuditLog(UUID id, UUID tenantId, UUID caseId, String actor, String actorType,
                    String action, String oldState, String newState, String traceId, String metadata) {
        this.id = id != null ? id : UUID.randomUUID();
        this.ts = Instant.now();
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.actor = actor;
        this.actorType = actorType;
        this.action = action;
        this.oldState = oldState;
        this.newState = newState;
        this.traceId = traceId;
        this.metadata = metadata;
    }

    public UUID getId() { return id; }
    public Instant getTs() { return ts; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCaseId() { return caseId; }
    public String getActor() { return actor; }
    public String getActorType() { return actorType; }
    public String getAction() { return action; }
    public String getOldState() { return oldState; }
    public String getNewState() { return newState; }
    public String getTraceId() { return traceId; }
    public String getMetadata() { return metadata; }
}
