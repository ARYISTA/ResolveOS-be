package com.resolvos.tenant;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Extends JpaTransactionManager to inject the RLS variable immediately after
 * each transaction begins. Uses set_config('app.tenant_id', ?, true) — the
 * third arg (true) means the setting is local to the transaction.
 *
 * When TenantContext is empty (non-tenant requests), no variable is set,
 * so RLS returns zero rows from protected tables — correct behaviour.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    private final DataSource dataSource;

    public TenantAwareTransactionManager(EntityManagerFactory emf, DataSource dataSource) {
        super(emf);
        this.dataSource = dataSource;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        String tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            try (java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL ROLE resolvos_app");
            } catch (SQLException ignored) {
                // Ignored if role does not exist yet (e.g. before initial migration)
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenantId);
                ps.execute();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to set RLS tenant_id", e);
            }
        }
        // Do NOT release the connection — Spring manages it for the transaction.
    }
}
