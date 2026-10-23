package com.resolvos.ticket;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.refund.RefundService;
import com.resolvos.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefundService refundService;

    @Autowired
    private TicketService ticketService;

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

        // 1. Seed tenant and settings
        jdbc.update("INSERT INTO tenants (id, slug, name, domain, created_at) VALUES (?, ?, ?, ?, now())",
                tenantId, "concurrency-" + tenantId.toString().substring(0, 8), "Concurrency Bank", "banking");
        jdbc.update("INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence) VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)",
                tenantId);

        // 2. Seed customer & transaction
        jdbc.update("INSERT INTO customers (id, tenant_id, name, tier, history_count) VALUES (?, ?, ?, ?, ?)",
                customerId, tenantId, "Concurrent Customer", "Standard", 1);
        jdbc.update("INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                transactionId, tenantId, customerId, "DEBIT", new BigDecimal("2500.00"), "SETTLED", "Concurrent Merchant");

        // 3. Seed ticket with ALLOW verdict
        jdbc.update("INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                ticketId, tenantId, "CAS-CONC-01", customerId, transactionId, "Double charge", new BigDecimal("2500.00"), "COMPLETED", "AUTO_APPROVED", "trc-conc-01");
        jdbc.update("INSERT INTO enforcement_results (ticket_id, verdict, checks, reason) VALUES (?, 'ALLOW', '[]'::jsonb, 'Passed all PEP rules')",
                ticketId);

        TenantContext.setTenantId(tenantId.toString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM refunds WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM enforcement_results WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM tickets WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM transactions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM customers WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant_settings WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("PRD 13: 10 concurrent identical refund requests produce exactly one refund")
    void testTenConcurrentRefundRequestsProduceExactlyOneRefund() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<RefundService.RefundExecutionResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TenantContext.setTenantId(tenantId.toString());
                    RefundService.RefundExecutionResult res = refundService.executeRefund(
                            tenantId, ticketId, new BigDecimal("2500.00"), "trc-conc-01"
                    );
                    results.add(res);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    TenantContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(results.size() + errorCount.get()).isEqualTo(threadCount);

        // Crucial PRD requirement: Exactly ONE refund row in the database table
        Integer countInDb = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refunds WHERE ticket_id = ?", Integer.class, ticketId
        );
        assertThat(countInDb).isEqualTo(1);

        // Verify status and amount
        String status = jdbc.queryForObject("SELECT status FROM refunds WHERE ticket_id = ?", String.class, ticketId);
        assertThat(status).isEqualTo("SUCCESS");

        BigDecimal amount = jdbc.queryForObject("SELECT amount FROM refunds WHERE ticket_id = ?", BigDecimal.class, ticketId);
        assertThat(amount).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("PRD 13: 10 concurrent identical resolve requests on a RECEIVED ticket transition once")
    void testTenConcurrentResolveRequests() throws Exception {
        UUID newTicketId = UUID.randomUUID();
        jdbc.update("INSERT INTO tickets (id, tenant_id, case_number, customer_id, transaction_id, message, requested_amount, status, outcome, trace_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                newTicketId, tenantId, "CAS-CONC-02", customerId, transactionId, "Charge error", new BigDecimal("1000.00"), "RECEIVED", null, "trc-conc-02");

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TenantContext.setTenantId(tenantId.toString());
                    ticketService.resolveTicket(newTicketId, tenantId);
                } catch (Exception ignored) {
                } finally {
                    TenantContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one outbox event written for RESOLVE_REQUESTED for this ticket
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE tenant_id = ? AND payload->>'caseId' = ? AND type = 'RESOLVE_REQUESTED'",
                Integer.class, tenantId, newTicketId.toString()
        );
        assertThat(outboxCount).isEqualTo(1);

        // Status is PROCESSING
        String status = jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, newTicketId);
        assertThat(status).isEqualTo("PROCESSING");

        jdbc.update("DELETE FROM case_node_runs WHERE ticket_id = ?", newTicketId);
        jdbc.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tickets WHERE id = ?", newTicketId);
    }
}
