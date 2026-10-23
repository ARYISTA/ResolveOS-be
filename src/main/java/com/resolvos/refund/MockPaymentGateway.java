package com.resolvos.refund;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final JdbcTemplate jdbcTemplate;

    public MockPaymentGateway(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PaymentResult processRefund(UUID tenantId, UUID ticketId, BigDecimal amount, String idempotencyKey) {
        String query = """
            SELECT t.force_gateway_failure
            FROM tickets tk
            JOIN transactions t ON t.id = tk.transaction_id
            WHERE tk.id = ?
            """;

        Boolean forceFailure = null;
        try {
            forceFailure = jdbcTemplate.queryForObject(query, Boolean.class, ticketId);
        } catch (Exception ignored) {}

        if (Boolean.TRUE.equals(forceFailure)) {
            log.warn("MockPaymentGateway: simulated failure triggered for ticket {}", ticketId);
            return PaymentResult.failure("Payment gateway simulation failure: forced gateway decline");
        }

        String ref = "REF-MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("MockPaymentGateway: successfully disbursed {} with ref {} for ticket {}", amount, ref, ticketId);
        return PaymentResult.success(ref);
    }
}
