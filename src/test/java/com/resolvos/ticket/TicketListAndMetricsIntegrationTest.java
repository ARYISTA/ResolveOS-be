package com.resolvos.ticket;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.audit.AuditController;
import com.resolvos.audit.AuditRecordResponse;
import com.resolvos.audit.AuditService;
import com.resolvos.auth.Role;
import com.resolvos.auth.User;
import com.resolvos.metrics.MetricsController;
import com.resolvos.metrics.dto.DashboardMetricsResponse;
import com.resolvos.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TicketListAndMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TicketController ticketController;

    @Autowired
    private AuditController auditController;

    @Autowired
    private MetricsController metricsController;

    @Autowired
    private AuditService auditService;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID customerId1;
    private UUID customerId2;
    private UUID transactionId1;
    private UUID transactionId2;
    private Authentication auth;

    private UUID ticket1;
    private UUID ticket2;
    private UUID ticket3;
    private UUID ticketOther;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        customerId1 = UUID.randomUUID();
        customerId2 = UUID.randomUUID();
        transactionId1 = UUID.randomUUID();
        transactionId2 = UUID.randomUUID();

        // 1. Seed primary tenant
        jdbc.update("INSERT INTO tenants (id, slug, name, domain, created_at) VALUES (?, ?, ?, ?, now())",
                tenantId, "b6-tenant-" + tenantId.toString().substring(0, 8), "B6 Main Bank", "banking");
        jdbc.update("INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence) VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)",
                tenantId);

        // Seed secondary tenant
        jdbc.update("INSERT INTO tenants (id, slug, name, domain, created_at) VALUES (?, ?, ?, ?, now())",
                otherTenantId, "b6-other-" + otherTenantId.toString().substring(0, 8), "B6 Other Bank", "banking");
        jdbc.update("INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence) VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)",
                otherTenantId);

        // 2. Seed customers
        jdbc.update("INSERT INTO customers (id, tenant_id, name, tier, history_count) VALUES (?, ?, ?, ?, ?)",
                customerId1, tenantId, "Alice Wonderland", "Premium", 5);
        jdbc.update("INSERT INTO customers (id, tenant_id, name, tier, history_count) VALUES (?, ?, ?, ?, ?)",
                customerId2, tenantId, "Bob Builder", "Standard", 1);

        // 3. Seed transactions
        jdbc.update("INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                transactionId1, tenantId, customerId1, "DEBIT", new BigDecimal("3500.00"), "SETTLED", "SuperMart");
        jdbc.update("INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                transactionId2, tenantId, customerId2, "DEBIT", new BigDecimal("12000.00"), "SETTLED", "TechZone");

        // 4. Seed tickets
        ticket1 = UUID.randomUUID(); // COMPLETED / AUTO_APPROVED, amount 3500, risk LOW
        Instant t1Time = Instant.now().minus(2, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ticket1, tenantId, "CAS-00101", customerId1, transactionId1, "Double billed at SuperMart", new BigDecimal("3500.00"), "COMPLETED", "AUTO_APPROVED", "trc-00101",
                java.sql.Timestamp.from(t1Time), java.sql.Timestamp.from(t1Time.plusSeconds(4)));
        jdbc.update("INSERT INTO risk_assessments (ticket_id, risk_level, risk_factors) VALUES (?, ?, ARRAY['trusted_merchant']::text[])",
                ticket1, "LOW");
        jdbc.update("INSERT INTO enforcement_results (ticket_id, verdict, checks, reason) VALUES (?, ?, ?::jsonb, ?)",
                ticket1, "ALLOW", "[]", "Passed all rules");
        jdbc.update("INSERT INTO refunds (id, tenant_id, ticket_id, amount, gateway_ref, status, idempotency_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenantId, ticket1, new BigDecimal("3500.00"), "ref-001", "SUCCESS", "idem-001");

        ticket2 = UUID.randomUUID(); // AWAITING_HUMAN, amount 12000, risk HIGH
        Instant t2Time = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ticket2, tenantId, "CAS-00102", customerId2, transactionId2, "High value unknown charge", new BigDecimal("12000.00"), "AWAITING_HUMAN", null, "trc-00102",
                java.sql.Timestamp.from(t2Time));
        jdbc.update("INSERT INTO risk_assessments (ticket_id, risk_level, risk_factors) VALUES (?, ?, ARRAY['high_amount']::text[])",
                ticket2, "HIGH");
        jdbc.update("INSERT INTO enforcement_results (ticket_id, verdict, checks, reason) VALUES (?, ?, ?::jsonb, ?)",
                ticket2, "ESCALATE", "[]", "Exceeds auto-approve threshold");

        ticket3 = UUID.randomUUID(); // FAILED / DENIED, amount 500, risk LOW
        Instant t3Time = Instant.now();
        jdbc.update("INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ticket3, tenantId, "CAS-00103", customerId1, transactionId1, "Expired request", new BigDecimal("500.00"), "FAILED", "DENIED", "trc-00103",
                java.sql.Timestamp.from(t3Time), java.sql.Timestamp.from(t3Time.plusSeconds(2)));
        jdbc.update("INSERT INTO risk_assessments (ticket_id, risk_level, risk_factors) VALUES (?, ?, ARRAY[]::text[])",
                ticket3, "LOW");
        jdbc.update("INSERT INTO enforcement_results (ticket_id, verdict, checks, reason) VALUES (?, ?, ?::jsonb, ?)",
                ticket3, "DENY", "[]", "Exceeded return window");

        // Seed other tenant's ticket
        ticketOther = UUID.randomUUID();
        jdbc.update("INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ticketOther, otherTenantId, "CAS-09999", customerId1, transactionId1, "Other tenant case", new BigDecimal("100.00"), "RECEIVED", null, "trc-09999",
                java.sql.Timestamp.from(Instant.now()));

        // Seed audit logs
        auditService.logEvent(tenantId, ticket1, "Support Agent", "HUMAN", "CASE_CREATED", null, "RECEIVED", "trc-00101", Map.of("amount", 3500));
        auditService.logEvent(tenantId, ticket1, "AI Engine", "AI", "AI_DECISION_GENERATED", "RECEIVED", "PROCESSING", "trc-00101", Map.of("confidence", 0.95));
        auditService.logEvent(tenantId, ticket2, "System", "SYSTEM", "HITL_TRIGGERED", "PROCESSING", "AWAITING_HUMAN", "trc-00102", Map.of("reason", "high amount"));
        auditService.logEvent(otherTenantId, ticketOther, "Other Agent", "HUMAN", "CASE_CREATED", null, "RECEIVED", "trc-09999", Map.of());

        // Context & Auth
        TenantContext.setTenantId(tenantId.toString());
        User user = new User(UUID.randomUUID(), tenantId, "admin@b6.test", "hash", Role.TENANT_ADMIN, "B6 Admin");
        auth = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM audit_logs WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM outbox_events WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM refunds WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM human_reviews WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id IN (?, ?))", tenantId, otherTenantId);
        jdbc.update("DELETE FROM enforcement_results WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id IN (?, ?))", tenantId, otherTenantId);
        jdbc.update("DELETE FROM risk_assessments WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id IN (?, ?))", tenantId, otherTenantId);
        jdbc.update("DELETE FROM ai_decisions WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id IN (?, ?))", tenantId, otherTenantId);
        jdbc.update("DELETE FROM ai_runs WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id IN (?, ?))", tenantId, otherTenantId);
        jdbc.update("DELETE FROM case_node_runs WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id IN (?, ?))", tenantId, otherTenantId);
        jdbc.update("DELETE FROM tickets WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM transactions WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM customers WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM tenant_settings WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantId, otherTenantId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("B6 Ticket list: default query returns all tickets for tenant with dual envelope keys")
    void testListTicketsDefault() {
        ResponseEntity<Map<String, Object>> response = ticketController.listTickets(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 1, 20, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        // Check dual keys
        assertThat(body).containsKeys("items", "cases", "totalElements", "totalCount", "page", "size", "limit", "totalPages");
        assertThat(body.get("totalElements")).isEqualTo(3L);
        assertThat(body.get("totalCount")).isEqualTo(3L);

        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> items = (List<CaseDetailResponse>) body.get("items");
        assertThat(items).hasSize(3);

        List<UUID> ids = items.stream().map(CaseDetailResponse::id).toList();
        assertThat(ids).containsExactlyInAnyOrder(ticket1, ticket2, ticket3);
        assertThat(ids).doesNotContain(ticketOther); // Strict tenant isolation
    }

    @Test
    @DisplayName("B6 Ticket list: filter by status (RESOLVED vs ESCALATED)")
    void testListTicketsFilterByStatus() {
        ResponseEntity<Map<String, Object>> resolvedResp = ticketController.listTickets(
                null, "RESOLVED", null, null, null, null, null, null, null, null, null, null, null, null, null, 1, 20, null
        );
        assertThat(resolvedResp.getBody()).isNotNull();
        assertThat(resolvedResp.getBody().get("totalCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> resolvedItems = (List<CaseDetailResponse>) resolvedResp.getBody().get("cases");
        assertThat(resolvedItems.get(0).id()).isEqualTo(ticket1);

        ResponseEntity<Map<String, Object>> escalatedResp = ticketController.listTickets(
                null, "ESCALATED", null, null, null, null, null, null, null, null, null, null, null, null, null, 1, 20, null
        );
        assertThat(escalatedResp.getBody()).isNotNull();
        assertThat(escalatedResp.getBody().get("totalCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> escalatedItems = (List<CaseDetailResponse>) escalatedResp.getBody().get("cases");
        assertThat(escalatedItems.get(0).id()).isEqualTo(ticket2);
    }

    @Test
    @DisplayName("B6 Ticket list: filter by risk level")
    void testListTicketsFilterByRisk() {
        ResponseEntity<Map<String, Object>> highRiskResp = ticketController.listTickets(
                null, null, "HIGH", null, null, null, null, null, null, null, null, null, null, null, null, 1, 20, null
        );
        assertThat(highRiskResp.getBody()).isNotNull();
        assertThat(highRiskResp.getBody().get("totalCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> items = (List<CaseDetailResponse>) highRiskResp.getBody().get("cases");
        assertThat(items.get(0).id()).isEqualTo(ticket2);
    }

    @Test
    @DisplayName("B6 Ticket list: search by message, case number or customer name")
    void testListTicketsSearch() {
        ResponseEntity<Map<String, Object>> searchResp = ticketController.listTickets(
                null, null, null, null, "SuperMart", null, null, null, null, null, null, null, null, null, null, 1, 20, null
        );
        assertThat(searchResp.getBody()).isNotNull();
        assertThat(searchResp.getBody().get("totalCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> items = (List<CaseDetailResponse>) searchResp.getBody().get("cases");
        assertThat(items.get(0).id()).isEqualTo(ticket1);

        ResponseEntity<Map<String, Object>> custSearchResp = ticketController.listTickets(
                null, null, null, null, null, "Builder", null, null, null, null, null, null, null, null, null, 1, 20, null
        );
        assertThat(custSearchResp.getBody()).isNotNull();
        assertThat(custSearchResp.getBody().get("totalCount")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> custItems = (List<CaseDetailResponse>) custSearchResp.getBody().get("cases");
        assertThat(custItems.get(0).id()).isEqualTo(ticket2);
    }

    @Test
    @DisplayName("B6 Ticket list: sort by amount asc and desc")
    void testListTicketsSort() {
        ResponseEntity<Map<String, Object>> ascResp = ticketController.listTickets(
                null, null, null, null, null, null, null, null, null, null, null, null, null, "amount", "asc", 1, 20, null
        );
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> ascItems = (List<CaseDetailResponse>) ascResp.getBody().get("cases");
        assertThat(ascItems).hasSize(3);
        assertThat(ascItems.get(0).requestedAmount()).isEqualByComparingTo("500.00");
        assertThat(ascItems.get(1).requestedAmount()).isEqualByComparingTo("3500.00");
        assertThat(ascItems.get(2).requestedAmount()).isEqualByComparingTo("12000.00");

        ResponseEntity<Map<String, Object>> descResp = ticketController.listTickets(
                null, null, null, null, null, null, null, null, null, null, null, null, null, "amount", "desc", 1, 20, null
        );
        @SuppressWarnings("unchecked")
        List<CaseDetailResponse> descItems = (List<CaseDetailResponse>) descResp.getBody().get("cases");
        assertThat(descItems.get(0).requestedAmount()).isEqualByComparingTo("12000.00");
        assertThat(descItems.get(2).requestedAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("B6 Audit logs: unpaged call returns JSON array matching frontend AuditRecord[]")
    void testAuditLogsUnpagedArray() {
        ResponseEntity<Object> response = auditController.getAuditLogs(
                null, null, null, null, null, null, null, null, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<AuditRecordResponse> list = (List<AuditRecordResponse>) response.getBody();
        assertThat(list).hasSize(3); // Only logs for tenantId

        AuditRecordResponse first = list.get(0);
        assertThat(first.id()).isNotBlank();
        assertThat(first.tenant()).isNotBlank();
        assertThat(first.actorType()).isIn("AI", "Human", "System", "Customer");
    }

    @Test
    @DisplayName("B6 Audit logs: filter by action and actorType")
    void testAuditLogsFiltering() {
        ResponseEntity<Object> actionResp = auditController.getAuditLogs(
                "CASE_CREATED", null, null, null, null, null, null, null, null
        );
        @SuppressWarnings("unchecked")
        List<AuditRecordResponse> actionList = (List<AuditRecordResponse>) actionResp.getBody();
        assertThat(actionList).hasSize(1);
        assertThat(actionList.get(0).action()).isEqualTo("CASE_CREATED");

        ResponseEntity<Object> actorResp = auditController.getAuditLogs(
                null, "AI", null, null, null, null, null, null, null
        );
        @SuppressWarnings("unchecked")
        List<AuditRecordResponse> actorList = (List<AuditRecordResponse>) actorResp.getBody();
        assertThat(actorList).hasSize(1);
        assertThat(actorList.get(0).actorType()).isEqualTo("AI");
    }

    @Test
    @DisplayName("B6 Audit logs: paged call returns pagination envelope")
    void testAuditLogsPagedEnvelope() {
        ResponseEntity<Object> response = auditController.getAuditLogs(
                null, null, null, null, null, null, 1, 2, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) response.getBody();
        assertThat(map).containsKeys("items", "totalElements", "page", "size", "totalPages");
        assertThat(map.get("totalElements")).isEqualTo(3L);
        assertThat(map.get("page")).isEqualTo(1);
        assertThat(map.get("size")).isEqualTo(2);
        assertThat(map.get("totalPages")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        List<AuditRecordResponse> items = (List<AuditRecordResponse>) map.get("items");
        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("B6 Metrics summary: computes live aggregates, dual field names, and connection health check")
    void testMetricsSummaryLive() {
        ResponseEntity<DashboardMetricsResponse> response = metricsController.getSummary(
                tenantId.toString(), null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardMetricsResponse m = response.getBody();
        assertThat(m).isNotNull();

        assertThat(m.totalCases()).isEqualTo(3L);
        assertThat(m.autoResolved()).isEqualTo(1L);
        assertThat(m.escalated()).isEqualTo(1L);
        assertThat(m.rejected()).isEqualTo(1L);
        assertThat(m.totalRefunded()).isEqualByComparingTo("3500.00");

        // Dual field aliases
        assertThat(m.avgResolutionMs()).isEqualTo(m.avgResolutionTimeMs());
        assertThat(m.avgLatencyByStage()).isEqualTo(m.stageLatencies());

        // Breakdown counts
        assertThat(m.casesByOutcome()).hasSize(3);
        assertThat(m.casesByOutcome()).extracting("outcome").containsExactly("ALLOW", "DENY", "ESCALATE");

        // 7-day timeline
        assertThat(m.casesOverTime()).hasSize(7);

        // Stage latencies
        assertThat(m.stageLatencies()).hasSize(7);
        assertThat(m.stageLatencies()).extracting("stage")
                .containsExactly("INTAKE", "VALIDATION", "POLICY_RETRIEVAL", "DECISION", "RISK", "ENFORCEMENT", "EXECUTION");
    }

    @Test
    @DisplayName("B6 Metrics summary: unauthenticated connection health check returns 200 OK")
    void testMetricsConnectionHealthCheck() {
        TenantContext.clear();
        ResponseEntity<DashboardMetricsResponse> response = metricsController.getSummary(
                null, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardMetricsResponse m = response.getBody();
        assertThat(m).isNotNull();
        // Returns baseline / empty without failing
        assertThat(m.totalCases()).isGreaterThanOrEqualTo(0L);
    }
}
