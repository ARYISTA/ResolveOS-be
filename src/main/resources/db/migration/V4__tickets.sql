-- V4: Tickets (cases) and pipeline tracking
CREATE SEQUENCE ticket_number_seq START 1000 INCREMENT 1;

CREATE TABLE tickets (
    id              UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID           NOT NULL REFERENCES tenants(id),
    case_number     TEXT           NOT NULL DEFAULT ('CASE-' || nextval('ticket_number_seq')),
    customer_id     UUID           NOT NULL REFERENCES customers(id),
    transaction_id  UUID           NOT NULL REFERENCES transactions(id),
    message         TEXT           NOT NULL,
    requested_amount NUMERIC(14,2) NOT NULL,
    status          TEXT           NOT NULL DEFAULT 'RECEIVED',
    -- RECEIVED|PROCESSING|AWAITING_HUMAN|COMPLETED|FAILED
    outcome         TEXT,
    -- AUTO_APPROVED|HUMAN_APPROVED|DENIED|HUMAN_REJECTED
    trace_id        TEXT           NOT NULL,
    event_seq       BIGINT         NOT NULL DEFAULT 0,
    created_by      UUID           REFERENCES users(id),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tickets_case_number ON tickets(case_number);
CREATE INDEX idx_tickets_tenant_id ON tickets(tenant_id);
CREATE INDEX idx_tickets_customer_id ON tickets(customer_id);
CREATE INDEX idx_tickets_status ON tickets(tenant_id, status);
CREATE INDEX idx_tickets_created_at ON tickets(tenant_id, created_at DESC);

CREATE TABLE case_node_runs (
    ticket_id    UUID        NOT NULL REFERENCES tickets(id),
    node         TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'PENDING',
    -- PENDING|RUNNING|SUCCESS|WARNING|FAILED|SKIPPED|WAITING_HUMAN
    duration_ms  BIGINT,
    output       JSONB,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (ticket_id, node)
);

CREATE TABLE ai_runs (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    ticket_id    UUID        NOT NULL REFERENCES tickets(id),
    trace_id     TEXT        NOT NULL,
    model_config JSONB,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

CREATE INDEX idx_ai_runs_ticket_id ON ai_runs(ticket_id);

CREATE TABLE ai_decisions (
    id                UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    ticket_id         UUID           NOT NULL UNIQUE REFERENCES tickets(id),
    decision          TEXT           NOT NULL,
    recommended_amount NUMERIC(14,2) NOT NULL,
    confidence        NUMERIC(4,3)   NOT NULL,
    reasoning_summary TEXT           NOT NULL,
    requires_human_review BOOLEAN    NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE risk_assessments (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    ticket_id   UUID        NOT NULL UNIQUE REFERENCES tickets(id),
    risk_level  TEXT        NOT NULL,   -- LOW|MEDIUM|HIGH
    risk_factors TEXT[]     NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE enforcement_results (
    ticket_id  UUID        NOT NULL PRIMARY KEY REFERENCES tickets(id),
    verdict    TEXT        NOT NULL,   -- ALLOW|DENY|ESCALATE
    checks     JSONB       NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
