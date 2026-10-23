package com.resolvos.ticket;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.auth.Role;
import com.resolvos.auth.User;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaseSequenceAndReconnectIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TicketController ticketController;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private CaseEventHandler caseEventHandler;

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
            """, tenantId, "tenant-" + tenantId.toString().substring(0, 8), "Reconnect Tenant", "banking");

        jdbc.update("""
            INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence)
            VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)
            """, tenantId);

        // 2. Seed customer and transaction
        jdbc.update("""
            INSERT INTO customers (id, tenant_id, name, tier, history_count, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            """, customerId, tenantId, "Rahul Verma", "VIP", 5);

        jdbc.update("""
            INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), false, now())
            """, transactionId, tenantId, customerId, "UPI", new BigDecimal("1500.00"), "Settled", "QuickPay");

        TenantContext.setTenantId(tenantId.toString());

        User agent = new User(UUID.randomUUID(), tenantId, "agent@reconnect.com", "hash", Role.SUPPORT_AGENT, "Agent Smith");
        auth = new UsernamePasswordAuthenticationToken(agent, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("B8 - Monotonic sequence counter progresses strictly on each workflow event")
    void testMonotonicSequenceDuringCaseExecution() {
        CreateTicketRequest request = new CreateTicketRequest(
                customerId, transactionId, "Payment deduction issue", new BigDecimal("1500.00"),
                "banking", null, "Rahul Verma", "VIP", new BigDecimal("1500.00"),
                null, null, false, null, null, null
        );

        ResponseEntity<CaseDetailResponse> createResponse = ticketController.createTicket(request, auth);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID ticketId = createResponse.getBody().caseId();
        assertThat(createResponse.getBody().eventSeq()).isEqualTo(0L);

        // Step 1: INTAKE started -> seq 1
        caseEventHandler.onNodeStarted(ticketId, "INTAKE");
        Ticket t1 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t1.getEventSeq()).isEqualTo(1L);

        // Step 2: INTAKE completed -> seq 2
        caseEventHandler.onNodeCompleted(ticketId, "INTAKE", 110, Map.of("parsed", true));
        Ticket t2 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t2.getEventSeq()).isEqualTo(2L);

        // Step 3: VALIDATION started -> seq 3
        caseEventHandler.onNodeStarted(ticketId, "VALIDATION");
        Ticket t3 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t3.getEventSeq()).isEqualTo(3L);

        // Step 4: VALIDATION completed -> seq 4
        caseEventHandler.onNodeCompleted(ticketId, "VALIDATION", 95, Map.of("valid", true));
        Ticket t4 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t4.getEventSeq()).isEqualTo(4L);

        // Step 5: HITL required -> seq 5
        caseEventHandler.onHitlRequired(ticketId, "Manual review needed", "MEDIUM");
        Ticket t5 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t5.getEventSeq()).isEqualTo(5L);
        assertThat(t5.getStatus()).isEqualTo("AWAITING_HUMAN");

        // Step 6: HITL resolved -> seq 6
        caseEventHandler.onHitlResolved(ticketId, "APPROVED", "senior_reviewer", new BigDecimal("1500.00"), "Verified");
        Ticket t6 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t6.getEventSeq()).isEqualTo(6L);

        // Step 7: Case completed -> seq 7
        caseEventHandler.onCaseCompleted(ticketId, "ALLOW", "REF-998877", new BigDecimal("1500.00"));
        Ticket t7 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t7.getEventSeq()).isEqualTo(7L);
        assertThat(t7.getStatus()).isEqualTo("COMPLETED");

        // Step 8: Audit event -> seq 8
        caseEventHandler.onAuditEvent(ticketId, "REFUND_EXECUTED", "PaymentGateway", "PENDING", "SUCCESS");
        Ticket t8 = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(t8.getEventSeq()).isEqualTo(8L);
    }

    @Test
    @DisplayName("B8 - Client reconnect returns gapless snapshot with lastSeq matching eventSeq")
    void testGaplessReconnectSnapshot() {
        CreateTicketRequest request = new CreateTicketRequest(
                customerId, transactionId, "Double charge check", new BigDecimal("1500.00"),
                "banking", null, "Rahul Verma", "VIP", new BigDecimal("1500.00"),
                null, null, false, null, null, null
        );

        ResponseEntity<CaseDetailResponse> createResponse = ticketController.createTicket(request, auth);
        UUID ticketId = createResponse.getBody().caseId();

        // Simulate streaming node execution
        caseEventHandler.onNodeStarted(ticketId, "INTAKE");
        caseEventHandler.onNodeCompleted(ticketId, "INTAKE", 100, Map.of("step", "intake"));
        caseEventHandler.onNodeStarted(ticketId, "POLICY_RETRIEVAL");
        caseEventHandler.onNodeCompleted(ticketId, "POLICY_RETRIEVAL", 150, Map.of("chunks", 2));

        // Client reconnects: performs GET /api/tickets/{id}
        ResponseEntity<CaseDetailResponse> snapshotResponse = ticketController.getTicket(ticketId);
        assertThat(snapshotResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        CaseDetailResponse snapshot = snapshotResponse.getBody();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.caseId()).isEqualTo(ticketId);
        assertThat(snapshot.eventSeq()).isEqualTo(4L);
        assertThat(snapshot.lastSeq()).isEqualTo(4L);

        // Verify snapshot contains the nodes executed so far
        assertThat(snapshot.nodes()).hasSize(2);
        assertThat(snapshot.nodes().stream().map(CaseNodeRunDto::node).toList())
                .containsExactlyInAnyOrder("INTAKE", "POLICY_RETRIEVAL");

        // Client deduplicates buffered events: any event with seq <= snapshot.lastSeq() (<= 4) is dropped
        long lastSeenSeq = snapshot.lastSeq();
        assertThat(lastSeenSeq).isEqualTo(4L);

        // Subsequent live events continue monotonically from seq = 5
        caseEventHandler.onNodeStarted(ticketId, "DECISION");
        Ticket afterNewEvent = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(afterNewEvent.getEventSeq()).isGreaterThan(lastSeenSeq);
        assertThat(afterNewEvent.getEventSeq()).isEqualTo(5L);

        // Fetching snapshot again reflects the new sequence without gaps
        ResponseEntity<CaseDetailResponse> updatedSnapshot = ticketController.getTicket(ticketId);
        assertThat(updatedSnapshot.getBody().lastSeq()).isEqualTo(5L);
        assertThat(updatedSnapshot.getBody().nodes()).hasSize(3);
    }
}
