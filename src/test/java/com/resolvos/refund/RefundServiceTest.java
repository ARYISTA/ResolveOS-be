package com.resolvos.refund;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.common.error.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundServiceTest extends AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private RefundService refundService;

    @Autowired
    private RefundRepository refundRepository;

    private UUID tenantId;
    private UUID customerId;
    private UUID transactionId;
    private UUID ticketId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
        ticketId = UUID.randomUUID();

        // Seed tenant
        jdbc.update("""
            INSERT INTO tenants (id, slug, name, domain, created_at)
            VALUES (?, ?, ?, ?, now())
            """, tenantId, "tenant-" + tenantId.toString().substring(0, 8), "Refund Test Tenant", "banking");

        // Seed customer
        jdbc.update("""
            INSERT INTO customers (id, tenant_id, name, tier, history_count, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            """, customerId, tenantId, "Test Customer", "Standard", 1);

        // Seed transaction
        jdbc.update("""
            INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), false, now())
            """, transactionId, tenantId, customerId, "UPI", new BigDecimal("5000.00"), "Settled", "TestMerchant");

        // Seed ticket
        jdbc.update("""
            INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, trace_id, event_seq, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, now())
            """, ticketId, tenantId, "CASE-" + ticketId.toString().substring(0, 8), customerId, transactionId, "Refund test", new BigDecimal("1000.00"), "PROCESSING", "trc-test");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM refunds WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM human_reviews WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM enforcement_results WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM ai_decisions WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tickets WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM customers WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("RefundService refuses refund without stored ALLOW or approved review")
    void testRefusesWithoutAllowOrApprovedReview() {
        assertThatThrownBy(() ->
                refundService.executeRefund(tenantId, ticketId, new BigDecimal("1000.00"), "trc-unauthorized")
        ).isInstanceOf(ApiException.class)
         .satisfies(ex -> {
             ApiException apiEx = (ApiException) ex;
             assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
             assertThat(apiEx.getMessage()).contains("refused");
         });
    }

    @Test
    @DisplayName("RefundService refuses refund when amount exceeds approved limit")
    void testRefusesAmountAboveApproval() {
        // Authorize with ALLOW for up to 1000.00
        jdbc.update("""
            INSERT INTO enforcement_results (ticket_id, verdict, checks, reason, created_at)
            VALUES (?, 'ALLOW', '[]'::jsonb, 'Approved', now())
            """, ticketId);

        jdbc.update("""
            INSERT INTO ai_decisions (id, ticket_id, decision, recommended_amount, confidence, reasoning_summary, requires_human_review, created_at)
            VALUES (?, ?, 'APPROVE_REFUND', ?, 0.900, 'Ok', false, now())
            """, UUID.randomUUID(), ticketId, new BigDecimal("1000.00"));

        // Request 1500.00 (exceeding 1000.00 approved)
        assertThatThrownBy(() ->
                refundService.executeRefund(tenantId, ticketId, new BigDecimal("1500.00"), "trc-overlimit")
        ).isInstanceOf(ApiException.class)
         .satisfies(ex -> {
             ApiException apiEx = (ApiException) ex;
             assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
             assertThat(apiEx.getMessage()).contains("exceeds approved limit");
         });
    }

    @Test
    @DisplayName("10 concurrent identical refund requests produce exactly ONE refund")
    void testConcurrencyProducesExactlyOneRefund() throws Exception {
        // Authorize with ALLOW
        jdbc.update("""
            INSERT INTO enforcement_results (ticket_id, verdict, checks, reason, created_at)
            VALUES (?, 'ALLOW', '[]'::jsonb, 'Approved', now())
            """, ticketId);

        jdbc.update("""
            INSERT INTO ai_decisions (id, ticket_id, decision, recommended_amount, confidence, reasoning_summary, requires_human_review, created_at)
            VALUES (?, ?, 'APPROVE_REFUND', ?, 0.900, 'Ok', false, now())
            """, UUID.randomUUID(), ticketId, new BigDecimal("1000.00"));

        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrency);

        AtomicInteger successCount = new AtomicInteger(0);
        List<RefundService.RefundExecutionResult> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    RefundService.RefundExecutionResult res = refundService.executeRefund(
                            tenantId, ticketId, new BigDecimal("1000.00"), "trc-concurrent"
                    );
                    results.add(res);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        startGate.countDown();
        boolean completed = doneGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(concurrency);

        // Verify database: exactly 1 refund row exists for this ticket
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM refunds WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(count).isEqualTo(1);

        // All returned results point to the same gatewayRef and refund ID
        UUID firstRefundId = results.get(0).refundId();
        assertThat(results).allMatch(r -> r.refundId().equals(firstRefundId));
        assertThat(results).allMatch(r -> "SUCCESS".equals(r.status()));
    }
}
