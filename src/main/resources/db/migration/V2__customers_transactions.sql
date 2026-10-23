-- V2: Customers and transactions
CREATE TABLE customers (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenants(id),
    name          TEXT        NOT NULL,
    tier          TEXT        NOT NULL DEFAULT 'Standard',  -- Standard|Premium|VIP
    history_count INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customers_tenant_id ON customers(tenant_id);

CREATE TABLE transactions (
    id                    UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id             UUID           NOT NULL REFERENCES tenants(id),
    customer_id           UUID           NOT NULL REFERENCES customers(id),
    type                  TEXT           NOT NULL,
    amount                NUMERIC(14, 2) NOT NULL,
    status                TEXT           NOT NULL,  -- Completed|Failed|Disputed|Refunded
    merchant_or_product   TEXT,
    occurred_at           TIMESTAMPTZ    NOT NULL,
    force_gateway_failure BOOLEAN        NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_transactions_tenant_id ON transactions(tenant_id);
CREATE INDEX idx_transactions_customer_id ON transactions(customer_id);
