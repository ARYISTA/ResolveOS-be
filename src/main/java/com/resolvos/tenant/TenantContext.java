package com.resolvos.tenant;

/**
 * Thread-local tenant context.
 * Request threads: set from JWT by JwtAuthFilter.
 * Non-request threads (outbox poller, orchestrator): set explicitly from the row being processed.
 * TenantAwareTransactionManager reads this to inject app.tenant_id into Postgres.
 */
public final class TenantContext {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    public static String getTenantId() {
        return TENANT_ID.get();
    }

    public static void clear() {
        TENANT_ID.remove();
    }
}
