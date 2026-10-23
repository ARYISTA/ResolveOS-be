package com.resolvos.review;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.common.error.ApiException;
import com.resolvos.outbox.OutboxPoller;
import com.resolvos.refund.RefundRepository;
import com.resolvos.tenant.TenantContext;
import com.resolvos.ticket.CaseDetailResponse;
import com.resolvos.ticket.CreateTicketRequest;
import com.resolvos.ticket.Ticket;
import com.resolvos.ticket.TicketController;
import com.resolvos.ticket.TicketRepository;
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
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EscalationWorkflowTest extends AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private EscalationController escalationController;

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
    private UUID tenantIdB;
    private UUID customerId;
    private UUID transactionId;
    private Authentication supportAgentAuth;
    private Authentication reviewerAuth;
    private Authentication tenantAdminAuth;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenantIdB = UUID.randomUUID();
        customerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();

        // 1. Seed tenant A and settings (auto_approve_limit = 5,000; hitl_trigger_limit = 25,000; max_refund = 50,000)
        jdbc.update("""
            INSERT INTO tenants (id, slug, name, domain, created_at)
            VALUES (?, ?, 'Escalation Test Tenant A', 'banking', now())
            """, tenantId, "tenant-a-" + tenantId.toString().substring(0, 8));

        jdbc.update("""
            INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence)
            VALUES (?, 'INR', 5000.00, 25000.00, 50000.00, 30, 90, 0.700)
            """, tenantId);

        // Seed tenant B for cross-tenant isolation tests
        jdbc.update("""
            INSERT INTO tenants (id, slug, name, domain, created_at)
            VALUES (?, ?, 'Escalation Test Tenant B', 'banking', now())
            """, tenantIdB, "tenant-b-" + tenantIdB.toString().substring(0, 8));

        jdbc.update("""
            INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence)
            VALUES (?, 'INR', 5000.00, 25000.00, 50000.00, 30, 90, 0.700)
            """, tenantIdB);

        // 2. Seed customer and transaction for Tenant A (amount = ₹20,000)
        jdbc.update("""
            INSERT INTO customers (id, tenant_id, name, tier, history_count, created_at)
            VALUES (?, ?, 'Vikram Rao', 'Premium', 5, now())
            """, customerId, tenantId);

        jdbc.update("""
            INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure, created_at)
            VALUES (?, ?, ?, 'UPI', 20000.00, 'Settled', 'Luxury Store', now(), false, now())
            """, transactionId, tenantId, customerId);

        // 3. Seed active policy version and chunk
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO policies (id, tenant_id, name, created_at)
            VALUES (?, ?, 'Dispute Policy', now())
            """, policyId, tenantId);

        jdbc.update("""
            INSERT INTO policy_versions (id, policy_id, version, status, active, file_path, page_count, ingestion_config, uploaded_at)
            VALUES (?, ?, 1, 'INDEXED', true, '/tmp/policy.pdf', 5, '{}'::jsonb, now())
            """, UUID.randomUUID(), policyId);

        String zeroVec = "[" + String.join(",", Collections.nCopies(768, "0.0")) + "]";
        jdbc.update("""
            INSERT INTO policy_chunks (id, tenant_id, policy_id, policy_version, document_name, page_number, section, token_count, content, embedding)
            VALUES (?, ?, ?, 1, 'Dispute Policy', 2, 'Section 4.2 High Value Review', 120, 'Transactions exceeding auto-approval limits undergo human review.', ?::vector)
            """, UUID.randomUUID(), tenantId, policyId, zeroVec);

        supportAgentAuth = new UsernamePasswordAuthenticationToken(
                "agent@bank.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"))
        );
        reviewerAuth = new UsernamePasswordAuthenticationToken(
                "reviewer@bank.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_REVIEWER"))
        );
        tenantAdminAuth = new UsernamePasswordAuthenticationToken(
                "admin@bank.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))
        );

        SecurityContextHolder.getContext().setAuthentication(reviewerAuth);
        TenantContext.setTenantId(tenantId.toString());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();

        for (UUID tId : List.of(tenantId, tenantIdB)) {
            jdbc.update("DELETE FROM refunds WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM human_reviews WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tId);
            jdbc.update("DELETE FROM enforcement_results WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tId);
            jdbc.update("DELETE FROM risk_assessments WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tId);
            jdbc.update("DELETE FROM ai_decisions WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tId);
            jdbc.update("DELETE FROM case_node_runs WHERE ticket_id IN (SELECT id FROM tickets WHERE tenant_id = ?)", tId);
            jdbc.update("DELETE FROM audit_logs WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM outbox_events WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM tickets WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM policy_chunks WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM policy_versions WHERE policy_id IN (SELECT id FROM policies WHERE tenant_id = ?)", tId);
            jdbc.update("DELETE FROM policies WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM customers WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM tenant_settings WHERE tenant_id = ?", tId);
            jdbc.update("DELETE FROM tenants WHERE id = ?", tId);
        }
    }

    private UUID createEscalatedCase(BigDecimal requestedAmount) {
        TenantContext.setTenantId(tenantId.toString());
        SecurityContextHolder.getContext().setAuthentication(supportAgentAuth);

        CreateTicketRequest createReq = new CreateTicketRequest(
                customerId,
                transactionId,
                "Unauthorized charge of ₹" + requestedAmount + " at merchant checkout.",
                requestedAmount,
                "banking",
                null,
                "Vikram Rao",
                "Premium",
                new BigDecimal("20000.00"),
                null,
                null,
                true,
                null,
                null,
                null
        );

        ResponseEntity<CaseDetailResponse> created = ticketController.createTicket(createReq, supportAgentAuth);
        UUID caseId = created.getBody().id();

        ticketController.resolveTicket(caseId, null);
        outboxPoller.pollAndProcess();

        Ticket ticket = ticketRepository.findById(caseId).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo("AWAITING_HUMAN");

        SecurityContextHolder.getContext().setAuthentication(reviewerAuth);
        return caseId;
    }

    @Test
    @DisplayName("GET /api/escalations lists open escalations and respects tenant isolation")
    void testGetOpenEscalationsAndTenantIsolation() {
        // Create an escalated case in Tenant A
        UUID caseIdA = createEscalatedCase(new BigDecimal("15000.00"));

        // Tenant A sees the open escalation
        TenantContext.setTenantId(tenantId.toString());
        ResponseEntity<List<CaseDetailResponse>> responseA = escalationController.getOpenEscalations();
        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseA.getBody()).isNotEmpty();
        assertThat(responseA.getBody().stream().anyMatch(c -> c.id().equals(caseIdA))).isTrue();

        // Tenant B cannot see Tenant A's open escalation
        TenantContext.setTenantId(tenantIdB.toString());
        ResponseEntity<List<CaseDetailResponse>> responseB = escalationController.getOpenEscalations();
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(responseB.getBody().stream().noneMatch(c -> c.id().equals(caseIdA))).isTrue();

        // Detail endpoint in Tenant B returns 404 for Tenant A's case
        assertThatThrownBy(() -> escalationController.getEscalation(caseIdA))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("Approve validation: 422 if amount <= 0, > transaction amount, or > customer cap")
    void testApproveValidationRules() {
        UUID caseId = createEscalatedCase(new BigDecimal("15000.00"));
        TenantContext.setTenantId(tenantId.toString());

        // 1. Amount <= 0 -> 422
        assertThatThrownBy(() -> escalationController.approveEscalation(
                caseId, new ApproveEscalationRequest(BigDecimal.ZERO, null, "Looks good"), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 2. Amount > transaction amount (25,000 > 20,000) -> 422
        assertThatThrownBy(() -> escalationController.approveEscalation(
                caseId, new ApproveEscalationRequest(new BigDecimal("25000.00"), null, "Approve beyond txn"), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 3. Amount > customer period limit (60,000 > 50,000) -> 422
        // Temporarily adjust transaction amount so limit rule triggers
        jdbc.update("UPDATE transactions SET amount = 70000.00 WHERE id = ?", transactionId);
        assertThatThrownBy(() -> escalationController.approveEscalation(
                caseId, new ApproveEscalationRequest(new BigDecimal("60000.00"), null, "Exceeds max customer cap"), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("Reject validation: 422 if comment < 10 characters")
    void testRejectValidationRules() {
        UUID caseId = createEscalatedCase(new BigDecimal("15000.00"));
        TenantContext.setTenantId(tenantId.toString());

        // Comment < 10 characters -> 422
        assertThatThrownBy(() -> escalationController.rejectEscalation(
                caseId, new RejectEscalationRequest("Too short", null), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getMessage()).contains("at least 10 characters");
                });

        // Empty comment -> 422
        assertThatThrownBy(() -> escalationController.rejectEscalation(
                caseId, new RejectEscalationRequest("", null), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("Golden path: Human approval -> resume -> execution -> refund created -> case completed")
    void testHumanApprovalAndResumeExecution() {
        UUID caseId = createEscalatedCase(new BigDecimal("15000.00"));
        TenantContext.setTenantId(tenantId.toString());

        // Approve with modified amount ₹12,000
        BigDecimal approvedAmount = new BigDecimal("12000.00");
        ResponseEntity<CaseDetailResponse> approveResp = escalationController.approveEscalation(
                caseId,
                new ApproveEscalationRequest(null, approvedAmount, "Approved after verifying cardholder identity"),
                reviewerAuth
        );
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Human review row updated to APPROVED
        HumanReview review = humanReviewRepository.findById(caseId).orElseThrow();
        assertThat(review.getStatus()).isEqualTo("APPROVED");
        assertThat(review.getDecisionAmount()).isEqualByComparingTo(approvedAmount);

        // Audit log records HUMAN_APPROVED
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE case_id = ? AND action = 'HUMAN_APPROVED'",
                Integer.class, caseId
        );
        assertThat(auditCount).isGreaterThan(0);

        // Outbox poller processes RESUME_REQUESTED
        outboxPoller.pollAndProcess();
        TenantContext.setTenantId(tenantId.toString());

        // Verify case completed as HUMAN_APPROVED and refund created
        Ticket completedTicket = ticketRepository.findById(caseId).orElseThrow();
        assertThat(completedTicket.getStatus()).isEqualTo("COMPLETED");
        assertThat(completedTicket.getOutcome()).isEqualTo("HUMAN_APPROVED");

        var refunds = refundRepository.findAll().stream().filter(r -> r.getTicketId().equals(caseId)).toList();
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getAmount()).isEqualByComparingTo(approvedAmount);
        assertThat(refunds.get(0).getStatus()).isEqualTo("SUCCESS");

        // Calling approve again returns 409 Conflict
        assertThatThrownBy(() -> escalationController.approveEscalation(
                caseId, new ApproveEscalationRequest(null, approvedAmount, "Double approve"), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Golden path: Human rejection -> resume -> case completed HUMAN_REJECTED -> zero refunds")
    void testHumanRejectionAndResumeExecution() {
        UUID caseId = createEscalatedCase(new BigDecimal("15000.00"));
        TenantContext.setTenantId(tenantId.toString());

        // Reject with valid compliance reason (>= 10 chars)
        String rejectionReason = "Customer dispute documentation does not match merchant transaction logs.";
        ResponseEntity<CaseDetailResponse> rejectResp = escalationController.rejectEscalation(
                caseId,
                new RejectEscalationRequest(rejectionReason, null),
                reviewerAuth
        );
        assertThat(rejectResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Human review row updated to REJECTED
        HumanReview review = humanReviewRepository.findById(caseId).orElseThrow();
        assertThat(review.getStatus()).isEqualTo("REJECTED");
        assertThat(review.getComment()).isEqualTo(rejectionReason);

        // Audit log records HUMAN_REJECTED
        Integer auditCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE case_id = ? AND action = 'HUMAN_REJECTED'",
                Integer.class, caseId
        );
        assertThat(auditCount).isGreaterThan(0);

        // Outbox poller processes RESUME_REQUESTED
        outboxPoller.pollAndProcess();
        TenantContext.setTenantId(tenantId.toString());

        // Verify case completed as HUMAN_REJECTED
        Ticket completedTicket = ticketRepository.findById(caseId).orElseThrow();
        assertThat(completedTicket.getStatus()).isEqualTo("COMPLETED");
        assertThat(completedTicket.getOutcome()).isEqualTo("HUMAN_REJECTED");

        // Zero refunds created for rejected disputes
        var refunds = refundRepository.findAll().stream().filter(r -> r.getTicketId().equals(caseId)).toList();
        assertThat(refunds).isEmpty();

        // Calling reject again returns 409 Conflict
        assertThatThrownBy(() -> escalationController.rejectEscalation(
                caseId, new RejectEscalationRequest("Another rejection attempt", null), reviewerAuth))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("Retry endpoint POST /api/tickets/{id}/retry: 409 if not failed, restarts if failed")
    void testRetryEndpointBehavior() {
        UUID caseId = createEscalatedCase(new BigDecimal("15000.00"));
        TenantContext.setTenantId(tenantId.toString());

        // Ticket retry is an agent/admin action
        SecurityContextHolder.getContext().setAuthentication(supportAgentAuth);

        // Case is in AWAITING_HUMAN (not FAILED) -> retry returns 409 Conflict
        assertThatThrownBy(() -> ticketController.retryTicket(caseId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).contains("no node has failed");
                });

        // Set status to FAILED to simulate node failure
        jdbc.update("UPDATE tickets SET status = 'FAILED' WHERE id = ?", caseId);

        // Retry now succeeds and moves status to PROCESSING
        ResponseEntity<CaseDetailResponse> retryResp = ticketController.retryTicket(caseId);
        assertThat(retryResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Ticket retriedTicket = ticketRepository.findById(caseId).orElseThrow();
        assertThat(retriedTicket.getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("Role security: Reviewers/Admins access escalations; Support agents are denied")
    void testRoleAuthorization() {
        createEscalatedCase(new BigDecimal("15000.00"));
        TenantContext.setTenantId(tenantId.toString());

        // Reviewer can list escalations
        SecurityContextHolder.getContext().setAuthentication(reviewerAuth);
        assertThat(escalationController.getOpenEscalations().getStatusCode()).isEqualTo(HttpStatus.OK);

        // Tenant Admin can list escalations
        SecurityContextHolder.getContext().setAuthentication(tenantAdminAuth);
        assertThat(escalationController.getOpenEscalations().getStatusCode()).isEqualTo(HttpStatus.OK);

        // Support Agent is forbidden from managing escalations
        SecurityContextHolder.getContext().setAuthentication(supportAgentAuth);
        assertThatThrownBy(() -> escalationController.getOpenEscalations())
                .isInstanceOf(org.springframework.security.authorization.AuthorizationDeniedException.class);
    }
}
