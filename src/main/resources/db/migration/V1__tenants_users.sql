-- V1: Core auth tables (tenants, users, refresh_tokens)
-- Flyway runs as resolvos_owner; app connects as resolvos_app.
-- RLS is enabled in V8 after all tables exist.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ─── Tenants ──────────────────────────────────────────────────────────────────
CREATE TABLE tenants (
    id         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    slug       TEXT        NOT NULL UNIQUE,
    name       TEXT        NOT NULL,
    domain     TEXT        NOT NULL,   -- BANKING|INSURANCE|SUBSCRIPTIONS|ECOMMERCE|TRAVEL
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─── Tenant settings ──────────────────────────────────────────────────────────
CREATE TABLE tenant_settings (
    tenant_id              UUID           NOT NULL PRIMARY KEY REFERENCES tenants(id),
    currency               TEXT           NOT NULL DEFAULT 'INR',
    auto_approve_limit     NUMERIC(14, 2) NOT NULL,
    hitl_trigger_limit     NUMERIC(14, 2) NOT NULL,
    max_refund_per_customer NUMERIC(14, 2) NOT NULL,
    refund_period_days     INT            NOT NULL DEFAULT 30,
    refund_window_days     INT            NOT NULL DEFAULT 30,
    min_confidence         NUMERIC(4, 3)  NOT NULL DEFAULT 0.70
);

-- ─── Users ────────────────────────────────────────────────────────────────────
-- tenant_id is nullable for SUPER_ADMIN (not tenant-owned).
-- Not RLS-protected: login lookup must work without tenant context.
CREATE TABLE users (
    id            UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id     UUID        REFERENCES tenants(id),
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL,   -- SUPER_ADMIN|TENANT_ADMIN|SUPPORT_AGENT|REVIEWER
    display_name  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─── Refresh tokens ───────────────────────────────────────────────────────────
-- Stored as hashed values; raw tokens never persisted.
CREATE TABLE refresh_tokens (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
