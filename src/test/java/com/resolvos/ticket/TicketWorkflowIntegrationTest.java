package com.resolvos.ticket;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.auth.Role;
import com.resolvos.auth.User;
import com.resolvos.outbox.OutboxPoller;
import com.resolvos.refund.RefundRepository;
import com.resolvos.review.HumanReviewRepository;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TicketWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private TicketController ticketController;

    @Autowired
    private OutboxPoller outboxPoller;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private HumanReviewRepository humanReviewRepository;

    private UUID tenantId;
    private UUID customerId;
    private UUID transactionId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();

        // 1. Seed tenant and tenant settings
        jdbc.update("""
            INSERT INTO tenants (id, slug, name, domain, created_at)
            VALUES (?, ?, ?, ?, now())
            """, tenantId, "tenant-" + tenantId.toString().substring(0, 8), "Workflow Test Tenant", "banking");

        jdbc.update("""
            INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence)
            VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)
            """, tenantId);

        // 2. Seed customer and transaction
        jdbc.update("""
            INSERT INTO customers (id, tenant_id, name, tier, history_count, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            """, customerId, tenantId, "Arjun Sharma", "Premium", 3);

        jdbc.update("""
            INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), false, now())
            """, transactionId, tenantId, customerId, "UPI", new BigDecimal("2500.00"), "Settled", "ShopMart");

        // 3. Seed active policy version and chunk
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO policies (id, tenant_id, name, created_at)
            VALUES (?, ?, 'Refund Policy', now())
            """, policyId, tenantId);

        jdbc.update("""
            INSERT INTO policy_versions (id, policy_id, version, status, active, file_path, page_count, ingestion_config, uploaded_at)
            VALUES (?, ?, 1, 'INDEXED', true, '/tmp/policy.pdf', 5, '{}'::jsonb, now())
            """, UUID.randomUUID(), policyId);

        String zeroVec = "[" + String.join(",", Collections.nCopies(768, "0.0")) + "]";
        jdbc.update("""
            INSERT INTO policy_chunks (id, tenant_id, policy_id, policy_version, document_name, page_number, section, token_count, content, embedding)
            VALUES (?, ?, ?, 1, 'Refund Policy', 1, 'Section 3.2 Turnaround Time', 100, 'UPI auto-reversal within T+1 working days', ?::vector)
            """, UUID.randomUUID(), tenantId, policyId, zeroVec);

        auth = new UsernamePasswordAuthenticationToken(
                "agent@test.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"))
        );
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        TenantContext.setTenantId(tenantId.toString());
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM refunds WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM human_reviews WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM enforcement_results WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM risk_assessments WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM ai_decisions WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM case_node_runs WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tickets WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM policy_chunks WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM policy_versions WHERE policy_id IN (SELECT id FROM policies WHERE tenant_id = ?)", tenantId);
        jdbc.update("DELETE FROM policies WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM customers WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant_settings WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("Golden path: Create ticket -> resolve -> outbox poller -> ALLOW -> refund executed -> case snapshot")
    void testGoldenPathSafeCase() {
        TenantContext.setTenantId(tenantId.toString());

        // 1. POST /api/tickets
        CreateTicketRequest createReq = new CreateTicketRequest(
                customerId,
                transactionId,
                "UPI payment failed at merchant checkout but funds debited.",
                new BigDecimal("2500.00"),
                "banking",
                null,
                "Arjun Sharma",
                "Premium",
                new BigDecimal("2500.00"),
                "bk-safe-upi",
                "Valid",
                false,
                null,
                null,
                null
        );

        ResponseEntity<CaseDetailResponse> createRes = ticketController.createTicket(createReq, auth);
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        CaseDetailResponse caseCreated = createRes.getBody();
        assertThat(caseCreated).isNotNull();
        UUID caseId = caseCreated.id();
        assertThat(caseCreated.ticketStatus()).isEqualTo("RECEIVED");
        assertThat(caseCreated.traceId()).isNotBlank();

        // 2. POST /api/tickets/{id}/resolve
        ResponseEntity<CaseDetailResponse> resolveRes = ticketController.resolveTicket(caseId, null);
        assertThat(resolveRes.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        CaseDetailResponse caseResolving = resolveRes.getBody();
        assertThat(caseResolving).isNotNull();
        assertThat(caseResolving.ticketStatus()).isEqualTo("PROCESSING");

        // 3. Process Outbox Event
        outboxPoller.pollAndProcess();

        // 4. GET /api/tickets/{id}
        TenantContext.setTenantId(tenantId.toString());
        ResponseEntity<CaseDetailResponse> getRes = ticketController.getTicket(caseId);
        assertThat(getRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        CaseDetailResponse snapshot = getRes.getBody();
        assertThat(snapshot).isNotNull();

        assertThat(snapshot.ticketStatus()).isEqualTo("COMPLETED");
        assertThat(snapshot.status()).isEqualTo("RESOLVED");
        assertThat(snapshot.ticketOutcome()).isEqualTo("AUTO_APPROVED");
        assertThat(snapshot.outcome()).isEqualTo("ALLOW");
        assertThat(snapshot.approvedAmount()).isEqualByComparingTo(new BigDecimal("2500.00"));

        // Verify resolution
        assertThat(snapshot.resolution()).isNotNull();
        assertThat(snapshot.resolution().verdict()).isEqualTo("ALLOW");
        assertThat(snapshot.resolution().decision()).isEqualTo("APPROVE_REFUND");
        assertThat(snapshot.resolution().enforcement()).isNotEmpty();

        // Verify nodes
        assertThat(snapshot.nodes()).isNotEmpty();
        assertThat(snapshot.nodes()).anyMatch(n -> "INTAKE".equals(n.node()) && "SUCCESS".equals(n.status()));
        assertThat(snapshot.nodes()).anyMatch(n -> "EXECUTION".equals(n.node()) && "SUCCESS".equals(n.status()));

        // Verify refund in database
        assertThat(refundRepository.existsByTicketId(caseId)).isTrue();
    }

    @Test
    @DisplayName("High amount (> autoApproveLimit) triggers HITL escalation, case pauses AWAITING_HUMAN, no refund")
    void testHighAmountTriggersHitlEscalation() {
        TenantContext.setTenantId(tenantId.toString());
        jdbc.update("UPDATE transactions SET amount = 20000.00 WHERE id = ?", transactionId);

        // 1. Create ticket with ₹15,000 (> 5,000 autoApproveLimit)
        CreateTicketRequest createReq = new CreateTicketRequest(
                customerId,
                transactionId,
                "Unauthorized charge requiring human sign-off",
                new BigDecimal("15000.00"),
                "banking", null, null, null, null, null, null, true, null, null, null
        );

        ResponseEntity<CaseDetailResponse> createRes = ticketController.createTicket(createReq, auth);
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID caseId = createRes.getBody().id();

        // 2. Resolve
        ticketController.resolveTicket(caseId, null);

        // 3. Process Outbox
        outboxPoller.pollAndProcess();

        // 4. Verify snapshot
        TenantContext.setTenantId(tenantId.toString());
        ResponseEntity<CaseDetailResponse> getRes = ticketController.getTicket(caseId);
        CaseDetailResponse snapshot = getRes.getBody();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.ticketStatus()).isEqualTo("AWAITING_HUMAN");
        assertThat(snapshot.status()).isEqualTo("ESCALATED");
        assertThat(snapshot.triggersHitl()).isTrue();

        // Verify human_reviews row exists in OPEN status
        assertThat(humanReviewRepository.findById(caseId)).isPresent();
        assertThat(humanReviewRepository.findById(caseId).get().getStatus()).isEqualTo("OPEN");

        // Verify no refund was executed!
        assertThat(refundRepository.existsByTicketId(caseId)).isFalse();
    }

    @Test
    @DisplayName("Attack case: Amount exceeds transaction value -> PEP evaluates DENY, no refund executed")
    void testAmountExceedingTransactionEndsWithDeny() {
        TenantContext.setTenantId(tenantId.toString());

        // Transaction is 2500, requested is 50000
        CreateTicketRequest createReq = new CreateTicketRequest(
                customerId,
                transactionId,
                "Demanded 20x escalation for service inconvenience.",
                new BigDecimal("50000.00"),
                "banking", null, null, null, null, "bk-attack-amount", "Attack", false, null, null, null
        );

        ResponseEntity<CaseDetailResponse> createRes = ticketController.createTicket(createReq, auth);
        assertThat(createRes.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID caseId = createRes.getBody().id();

        // Resolve
        ticketController.resolveTicket(caseId, null);

        // Process Outbox
        outboxPoller.pollAndProcess();

        // Verify snapshot
        TenantContext.setTenantId(tenantId.toString());
        ResponseEntity<CaseDetailResponse> getRes = ticketController.getTicket(caseId);
        CaseDetailResponse snapshot = getRes.getBody();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.ticketStatus()).isEqualTo("COMPLETED");
        assertThat(snapshot.ticketOutcome()).isEqualTo("DENIED");
        assertThat(snapshot.status()).isEqualTo("REJECTED");
        assertThat(snapshot.outcome()).isEqualTo("DENY");

        // Verify no refund was executed
        assertThat(refundRepository.existsByTicketId(caseId)).isFalse();
    }
}
