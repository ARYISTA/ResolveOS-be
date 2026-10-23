-- V8: Roles, RLS, and grants
-- Creates the two database roles and sets up all Row-Level Security policies.
-- resolvos_owner: runs migrations (superuser-equivalent for schema ops)
-- resolvos_app:   application role, no BYPASSRLS, owns nothing

-- ─── Create roles (idempotent) ────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'resolvos_owner') THEN
        CREATE ROLE resolvos_owner WITH LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'resolvos_app') THEN
        CREATE ROLE resolvos_app WITH LOGIN NOINHERIT NOBYPASSRLS;
    END IF;
END
$$;

-- Grant schema usage to app role
GRANT USAGE ON SCHEMA public TO resolvos_app;

-- Grant table-level privileges (SELECT, INSERT on most; no UPDATE/DELETE on audit)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO resolvos_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO resolvos_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO resolvos_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO resolvos_app;

-- ─── Audit logs: append-only for resolvos_app ─────────────────────────────────
-- Revoke UPDATE and DELETE that were granted above
REVOKE UPDATE, DELETE ON audit_logs FROM resolvos_app;

-- ─── Enable RLS on tenant-owned tables ───────────────────────────────────────
-- Non-RLS tables (documented): tenants, users, outbox_events, benchmark_results
ALTER TABLE customers         ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers         FORCE ROW LEVEL SECURITY;

ALTER TABLE transactions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions      FORCE ROW LEVEL SECURITY;

ALTER TABLE policies          ENABLE ROW LEVEL SECURITY;
ALTER TABLE policies          FORCE ROW LEVEL SECURITY;

ALTER TABLE policy_chunks     ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_chunks     FORCE ROW LEVEL SECURITY;

ALTER TABLE tickets           ENABLE ROW LEVEL SECURITY;
ALTER TABLE tickets           FORCE ROW LEVEL SECURITY;

ALTER TABLE refunds           ENABLE ROW LEVEL SECURITY;
ALTER TABLE refunds           FORCE ROW LEVEL SECURITY;

ALTER TABLE audit_logs        ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs        FORCE ROW LEVEL SECURITY;

ALTER TABLE tenant_settings   ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_settings   FORCE ROW LEVEL SECURITY;

-- ─── RLS Policies ─────────────────────────────────────────────────────────────
-- All policies share the same pattern:
--   USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
-- The second arg 'true' means return NULL (not error) if setting is absent,
-- which causes zero rows to be returned when tenant context is not set.

CREATE POLICY customers_tenant_isolation ON customers
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY transactions_tenant_isolation ON transactions
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY policies_tenant_isolation ON policies
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY policy_chunks_tenant_isolation ON policy_chunks
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tickets_tenant_isolation ON tickets
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY refunds_tenant_isolation ON refunds
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY audit_logs_tenant_isolation ON audit_logs
    FOR ALL TO resolvos_app
    USING (tenant_id IS NULL OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id IS NULL OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_settings_tenant_isolation ON tenant_settings
    FOR ALL TO resolvos_app
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
