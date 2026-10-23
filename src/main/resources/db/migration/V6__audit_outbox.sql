-- V6: Audit logs and outbox
-- audit_logs is append-only: UPDATE and DELETE are revoked in V8.
CREATE TABLE audit_logs (
    id         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
    tenant_id  UUID        REFERENCES tenants(id),
    case_id    UUID        REFERENCES tickets(id),
    actor      TEXT        NOT NULL,
    actor_type TEXT        NOT NULL,  -- AI|SYSTEM|HUMAN|CUSTOMER
    action     TEXT        NOT NULL,
    old_state  TEXT,
    new_state  TEXT,
    trace_id   TEXT,
    metadata   JSONB
);

CREATE INDEX idx_audit_logs_tenant_id ON audit_logs(tenant_id, ts DESC);
CREATE INDEX idx_audit_logs_case_id ON audit_logs(case_id, ts DESC);
CREATE INDEX idx_audit_logs_trace_id ON audit_logs(trace_id);

-- Outbox for reliable engine dispatch (no broker)
-- Not RLS-protected: poller reads across all tenants.
CREATE TABLE outbox_events (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    type         TEXT        NOT NULL,  -- RESOLVE_REQUESTED|RESUME_REQUESTED
    payload      JSONB       NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING|PROCESSING|DONE|FAILED
    attempts     INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_status ON outbox_events(status, created_at);
