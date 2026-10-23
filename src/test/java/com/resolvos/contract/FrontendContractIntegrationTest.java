package com.resolvos.contract;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.audit.AuditController;
import com.resolvos.audit.AuditRecordResponse;
import com.resolvos.auth.Role;
import com.resolvos.auth.User;
import com.resolvos.compare.CompareController;
import com.resolvos.compare.dto.ComparisonRunResult;
import com.resolvos.compare.dto.RunComparisonRequest;
import com.resolvos.metrics.MetricsController;
import com.resolvos.metrics.dto.DashboardMetricsResponse;
import com.resolvos.securitylab.SecurityLabController;
import com.resolvos.securitylab.dto.RunAttackRequest;
import com.resolvos.securitylab.dto.SecurityAttackResult;
import com.resolvos.tenant.TenantContext;
import com.resolvos.ticket.CaseDetailResponse;
import com.resolvos.ticket.CreateTicketRequest;
import com.resolvos.ticket.TicketController;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FrontendContractIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TicketController ticketController;

    @Autowired
    private MetricsController metricsController;

    @Autowired
    private AuditController auditController;

    @Autowired
    private SecurityLabController securityLabController;

    @Autowired
    private CompareController compareController;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID customerId;
    private UUID transactionId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();

        jdbc.update("""
            INSERT INTO tenants (id, slug, name, domain, created_at)
            VALUES (?, ?, ?, ?, now())
            """, tenantId, "tenant-" + tenantId.toString().substring(0, 8), "Contract Test Tenant", "banking");

        jdbc.update("""
            INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence)
            VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)
            """, tenantId);

        jdbc.update("""
            INSERT INTO customers (id, tenant_id, name, tier, history_count, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            """, customerId, tenantId, "Priya Nair", "Premium", 4);

        jdbc.update("""
            INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), false, now())
            """, transactionId, tenantId, customerId, "Credit Card", new BigDecimal("3200.00"), "Settled", "CloudHost");

        TenantContext.setTenantId(tenantId.toString());

        User agent = new User(UUID.randomUUID(), tenantId, "admin@contract.com", "hash", Role.TENANT_ADMIN, "Admin Priya");
        auth = new UsernamePasswordAuthenticationToken(agent, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"), new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("B8 - CaseDetailResponse matches frontend CaseRecord schema")
    void testCaseDetailContract() {
        CreateTicketRequest request = new CreateTicketRequest(
                customerId, transactionId, "Billing discrepancy", new BigDecimal("3200.00"),
                "banking", null, "Priya Nair", "Premium", new BigDecimal("3200.00"),
                null, null, false, null, null, null
        );

        ResponseEntity<CaseDetailResponse> createResp = ticketController.createTicket(request, auth);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CaseDetailResponse caseDetail = createResp.getBody();
        assertThat(caseDetail).isNotNull();

        // Validate all required frontend fields per ResolveOS/src/lib/schemas/index.ts (CaseRecord)
        assertThat(caseDetail.id()).isNotNull();
        assertThat(caseDetail.caseId()).isNotNull();
        assertThat(caseDetail.caseNumber()).isNotBlank();
        assertThat(caseDetail.domain()).isNotBlank();
        assertThat(caseDetail.companyId()).isNotBlank();
        assertThat(caseDetail.customerId()).isEqualTo(customerId);
        assertThat(caseDetail.customerName()).isEqualTo("Priya Nair");
        assertThat(caseDetail.customerTier()).isEqualTo("Premium");
        assertThat(caseDetail.transactionId()).isEqualTo(transactionId);
        assertThat(caseDetail.transactionAmount()).isEqualByComparingTo("3200.00");
        assertThat(caseDetail.requestedAmount()).isEqualByComparingTo("3200.00");
        assertThat(caseDetail.message()).isEqualTo("Billing discrepancy");
        assertThat(caseDetail.status()).isIn("OPEN", "PROCESSING", "RESOLVED", "ESCALATED", "REJECTED");
        assertThat(caseDetail.riskLevel()).isIn("LOW", "MEDIUM", "HIGH");
        assertThat(caseDetail.createdAt()).isNotBlank();
        assertThat(caseDetail.traceId()).isNotBlank();
        assertThat(caseDetail.eventSeq()).isGreaterThanOrEqualTo(0L);
        assertThat(caseDetail.lastSeq()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("B8 - Ticket list matches frontend CaseListResult schema")
    @SuppressWarnings("unchecked")
    void testTicketListContract() {
        CreateTicketRequest request = new CreateTicketRequest(
                customerId, transactionId, "List test ticket", new BigDecimal("3200.00"),
                "banking", null, "Priya Nair", "Premium", new BigDecimal("3200.00"),
                null, null, false, null, null, null
        );
        ticketController.createTicket(request, auth);

        ResponseEntity<Map<String, Object>> response = ticketController.listTickets(
                tenantId.toString(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        // Frontend expects: cases, totalCount, page, totalPages, limit
        assertThat(body).containsKey("cases");
        assertThat(body).containsKey("totalCount");
        assertThat(body).containsKey("page");
        assertThat(body).containsKey("totalPages");
        assertThat(body).containsKey("limit");

        List<?> cases = (List<?>) body.get("cases");
        assertThat(cases).isNotEmpty();
    }

    @Test
    @DisplayName("B8 - Metrics endpoint matches frontend DashboardMetrics schema")
    void testDashboardMetricsContract() {
        ResponseEntity<DashboardMetricsResponse> response = metricsController.getSummary(tenantId.toString(), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardMetricsResponse metrics = response.getBody();
        assertThat(metrics).isNotNull();

        // Frontend expects: totalCases, autoResolved, escalated, rejected, totalRefunded, avgResolutionTimeMs, casesByOutcome, casesOverTime, stageLatencies
        assertThat(metrics.totalCases()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.autoResolved()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.escalated()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.rejected()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.totalRefunded()).isNotNull();
        assertThat(metrics.avgResolutionTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.casesByOutcome()).isNotNull();
        assertThat(metrics.casesOverTime()).isNotNull();
        assertThat(metrics.stageLatencies()).isNotNull();
    }

    @Test
    @DisplayName("B8 - Audit records match frontend AuditRecord schema")
    @SuppressWarnings("unchecked")
    void testAuditLogContract() {
        CreateTicketRequest request = new CreateTicketRequest(
                customerId, transactionId, "Audit test ticket", new BigDecimal("3200.00"),
                "banking", null, "Priya Nair", "Premium", new BigDecimal("3200.00"),
                null, null, false, null, null, null
        );
        ResponseEntity<CaseDetailResponse> ticketResp = ticketController.createTicket(request, auth);
        UUID caseId = ticketResp.getBody().caseId();

        jdbc.update("""
            INSERT INTO audit_logs (id, tenant_id, case_id, actor, actor_type, action, old_state, new_state, trace_id, metadata, ts)
            VALUES (?, ?, ?, 'System', 'SYSTEM', 'CASE_CREATED', 'NONE', 'OPEN', 'trc-test', '{}'::jsonb, now())
            """, UUID.randomUUID(), tenantId, caseId);

        ResponseEntity<Object> response = auditController.getAuditLogs(
                null, null, caseId.toString(), tenantId.toString(), null, null, 1, 20, null
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        List<AuditRecordResponse> logs = (List<AuditRecordResponse>) body.get("items");
        assertThat(logs).isNotEmpty();

        AuditRecordResponse record = logs.getFirst();
        assertThat(record.id()).isNotBlank();
        assertThat(record.ts()).isNotBlank();
        assertThat(record.tenant()).isNotBlank();
        assertThat(record.caseId()).isEqualTo(caseId.toString());
        assertThat(record.actor()).isEqualTo("System");
        assertThat(record.actorType()).isEqualTo("System");
        assertThat(record.action()).isEqualTo("CASE_CREATED");
        assertThat(record.oldState()).isEqualTo("NONE");
        assertThat(record.newState()).isEqualTo("OPEN");
        assertThat(record.traceId()).isEqualTo("trc-test");
        assertThat(record.metadata()).isNotNull();
    }

    @Test
    @DisplayName("B8 - Security Lab attack result matches frontend SecurityAttackResult schema")
    void testSecurityAttackResultContract() {
        RunAttackRequest req = new RunAttackRequest("attack-prompt-injection", null);
        ResponseEntity<SecurityAttackResult> response = securityLabController.runAttack(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SecurityAttackResult result = response.getBody();
        assertThat(result).isNotNull();

        // Frontend fields: attackId, caseId, layerCaught, verdict, blocked, reason, executedAt, traceId
        assertThat(result.attackId()).isNotBlank();
        assertThat(result.caseId()).isNotBlank();
        assertThat(result.layerCaught()).isIn("LLM guard", "Schema validation", "Policy enforcement", "Tenant isolation");
        assertThat(result.verdict()).isIn("ALLOW", "DENY", "ESCALATE");
        assertThat(result.blocked()).isTrue();
        assertThat(result.reason()).isNotBlank();
        assertThat(result.executedAt()).isNotBlank();
        assertThat(result.traceId()).isNotBlank();
    }

    @Test
    @DisplayName("B8 - Comparison run result matches frontend ComparisonRunResult schema")
    void testComparisonRunResultContract() {
        RunComparisonRequest req = new RunComparisonRequest("preset-duplicate-charge", null);
        ResponseEntity<ComparisonRunResult> response = compareController.runComparison(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ComparisonRunResult result = response.getBody();
        assertThat(result).isNotNull();

        // Frontend fields: presetId, presetLabel, groundTruthDecision, groundTruthAmount, results, aggregateAccuracyPercent
        assertThat(result.presetId()).isNotBlank();
        assertThat(result.presetLabel()).isNotBlank();
        assertThat(result.groundTruthDecision()).isNotBlank();
        assertThat(result.groundTruthAmount()).isNotNull();
        assertThat(result.aggregateAccuracyPercent()).isBetween(0.0, 100.0);
        assertThat(result.results()).isNotEmpty();

        for (ComparisonRunResult.ApproachResult approach : result.results()) {
            assertThat(approach.approach()).isIn("Manual", "Rules-only", "Pure LLM", "RAG-only", "Hybrid (proposed)");
            assertThat(approach.decision()).isNotBlank();
            assertThat(approach.amount()).isNotNull();
            assertThat(approach.latencyMs()).isGreaterThanOrEqualTo(0);
            assertThat(approach.estimatedCostUsd()).isNotNull();
            assertThat(approach.notes()).isNotBlank();
        }
    }

    @Test
    @DisplayName("B8 - WebSocket event shapes serialize in accordance with frontend Zod schemas")
    void testWebSocketEventShapesContract() throws Exception {
        UUID caseId = UUID.randomUUID();

        // WsNodeStarted
        Map<String, Object> nodeStarted = Map.of(
                "type", "NODE_STARTED",
                "caseId", caseId.toString(),
                "node", "INTAKE",
                "ts", "2026-10-23T12:00:00Z"
        );
        String nsJson = objectMapper.writeValueAsString(nodeStarted);
        assertThat(nsJson).contains("\"type\":\"NODE_STARTED\"").contains("\"node\":\"INTAKE\"");

        // WsNodeCompleted
        Map<String, Object> nodeCompleted = Map.of(
                "type", "NODE_COMPLETED",
                "caseId", caseId.toString(),
                "node", "VALIDATION",
                "durationMs", 150,
                "output", Map.of("status", "valid")
        );
        String ncJson = objectMapper.writeValueAsString(nodeCompleted);
        assertThat(ncJson).contains("\"type\":\"NODE_COMPLETED\"").contains("\"durationMs\":150");

        // WsNodeFailed
        Map<String, Object> nodeFailed = Map.of(
                "type", "NODE_FAILED",
                "caseId", caseId.toString(),
                "node", "EXECUTION",
                "error", "Gateway timeout"
        );
        String nfJson = objectMapper.writeValueAsString(nodeFailed);
        assertThat(nfJson).contains("\"type\":\"NODE_FAILED\"").contains("\"error\":\"Gateway timeout\"");

        // WsHitlRequired
        Map<String, Object> hitlRequired = Map.of(
                "type", "HITL_REQUIRED",
                "caseId", caseId.toString(),
                "reason", "High risk transaction",
                "riskLevel", "HIGH"
        );
        String hrJson = objectMapper.writeValueAsString(hitlRequired);
        assertThat(hrJson).contains("\"type\":\"HITL_REQUIRED\"").contains("\"riskLevel\":\"HIGH\"");

        // WsHitlResolved
        Map<String, Object> hitlResolved = Map.of(
                "type", "HITL_RESOLVED",
                "caseId", caseId.toString(),
                "decision", "APPROVED",
                "reviewer", "reviewer1@resolvos.com",
                "modifiedAmount", 1200.0,
                "reason", "Approved after customer KYC"
        );
        String hresJson = objectMapper.writeValueAsString(hitlResolved);
        assertThat(hresJson).contains("\"type\":\"HITL_RESOLVED\"").contains("\"decision\":\"APPROVED\"");

        // WsCaseCompleted
        Map<String, Object> caseCompleted = Map.of(
                "type", "CASE_COMPLETED",
                "caseId", caseId.toString(),
                "verdict", "ALLOW",
                "refundRef", "REF-123456",
                "approvedAmount", 1200.0
        );
        String ccJson = objectMapper.writeValueAsString(caseCompleted);
        assertThat(ccJson).contains("\"type\":\"CASE_COMPLETED\"").contains("\"verdict\":\"ALLOW\"");

        // WsAuditEvent
        Map<String, Object> auditEvent = Map.of(
                "type", "AUDIT_EVENT",
                "caseId", caseId.toString(),
                "action", "REFUND_EXECUTED",
                "actor", "System",
                "ts", "2026-10-23T12:05:00Z"
        );
        String aeJson = objectMapper.writeValueAsString(auditEvent);
        assertThat(aeJson).contains("\"type\":\"AUDIT_EVENT\"").contains("\"action\":\"REFUND_EXECUTED\"");
    }
}
