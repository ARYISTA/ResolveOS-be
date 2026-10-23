-- V5: Human reviews and refunds
CREATE TABLE human_reviews (
    ticket_id      UUID           NOT NULL PRIMARY KEY REFERENCES tickets(id),
    status         TEXT           NOT NULL DEFAULT 'OPEN',  -- OPEN|APPROVED|REJECTED
    reason         TEXT           NOT NULL,
    risk_level     TEXT           NOT NULL,
    reviewer_id    UUID           REFERENCES users(id),
    decision_amount NUMERIC(14,2),
    comment        TEXT,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ
);

CREATE INDEX idx_human_reviews_status ON human_reviews(status);
CREATE INDEX idx_human_reviews_reviewer_id ON human_reviews(reviewer_id);

CREATE TABLE refunds (
    id              UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID           NOT NULL REFERENCES tenants(id),
    ticket_id       UUID           NOT NULL UNIQUE REFERENCES tickets(id),
    amount          NUMERIC(14,2)  NOT NULL,
    gateway_ref     TEXT,
    status          TEXT           NOT NULL DEFAULT 'PENDING',  -- PENDING|SUCCESS|FAILED
    idempotency_key TEXT           NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_refunds_tenant_id ON refunds(tenant_id);
CREATE INDEX idx_refunds_idempotency_key ON refunds(idempotency_key);
