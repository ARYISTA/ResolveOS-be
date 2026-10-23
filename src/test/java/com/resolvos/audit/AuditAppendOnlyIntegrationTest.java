package com.resolvos.audit;

import com.resolvos.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditAppendOnlyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("PRD 13: Audit rows are append-only — UPDATE and DELETE fail for resolvos_app")
    void testAuditRowsAreAppendOnly() throws Exception {
        UUID auditId = UUID.randomUUID();

        // 1. Insert audit log row as owner
        jdbc.update("""
            INSERT INTO audit_logs (id, ts, actor, actor_type, action, old_state, new_state, trace_id)
            VALUES (?, now(), 'System', 'SYSTEM', 'TEST_EVENT', 'NONE', 'OPEN', 'trc-test-01')
            """, auditId);

        // 2. Use dedicated connection with transaction-local role to prevent pool pollution
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET LOCAL ROLE resolvos_app;");

                // SELECT works for resolvos_app
                try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM audit_logs WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }

                // INSERT works for resolvos_app (append-only)
                UUID newId = UUID.randomUUID();
                try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO audit_logs (id, ts, actor, actor_type, action, old_state, new_state, trace_id)
                    VALUES (?, now(), 'System', 'SYSTEM', 'TEST_APPEND', 'NONE', 'OPEN', 'trc-test-02')
                    """)) {
                    ps.setObject(1, newId);
                    int inserted = ps.executeUpdate();
                    assertThat(inserted).isEqualTo(1);
                }

                // UPDATE is revoked and must fail
                java.sql.Savepoint sp1 = conn.setSavepoint();
                try (PreparedStatement ps = conn.prepareStatement("UPDATE audit_logs SET action = 'TAMPERED' WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    assertThatThrownBy(ps::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
                conn.rollback(sp1);

                // DELETE is revoked and must fail
                java.sql.Savepoint sp2 = conn.setSavepoint();
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_logs WHERE id = ?")) {
                    ps.setObject(1, auditId);
                    assertThatThrownBy(ps::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
                conn.rollback(sp2);
            } finally {
                conn.rollback();
            }
        } finally {
            jdbc.update("DELETE FROM audit_logs WHERE action IN ('TEST_EVENT', 'TEST_APPEND')");
        }
    }
}
