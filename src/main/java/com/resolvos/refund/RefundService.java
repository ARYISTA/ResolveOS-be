package com.resolvos.refund;

import com.resolvos.audit.AuditService;
import com.resolvos.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refundRepository;
    private final PaymentGateway paymentGateway;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public record RefundExecutionResult(
            UUID refundId,
            UUID ticketId,
            BigDecimal amount,
            String gatewayRef,
            String status
    ) {}

    public RefundService(RefundRepository refundRepository,
                         PaymentGateway paymentGateway,
                         AuditService auditService,
                         JdbcTemplate jdbcTemplate) {
        this.refundRepository = refundRepository;
        this.paymentGateway = paymentGateway;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public RefundExecutionResult executeRefund(UUID tenantId, UUID ticketId, BigDecimal amount, String traceId) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.unprocessable("Refund amount must be strictly positive");
        }

        // Idempotency: return existing successful refund if already processed
        Optional<Refund> existing = refundRepository.findByTicketId(ticketId);
        if (existing.isPresent()) {
            Refund r = existing.get();
            if ("SUCCESS".equals(r.getStatus())) {
                log.info("Refund already executed for ticket {}: returning existing refund {}", ticketId, r.getId());
                return new RefundExecutionResult(r.getId(), ticketId, r.getAmount(), r.getGatewayRef(), r.getStatus());
            }
        }

        // Independent validation: verify stored ALLOW verdict or approved human review
        verifyAuthorization(ticketId, amount);

        String idempotencyKey = "refund:" + ticketId;

        // Atomically ensure row exists using PostgreSQL ON CONFLICT DO NOTHING
        jdbcTemplate.update("""
            INSERT INTO refunds (id, tenant_id, ticket_id, amount, status, idempotency_key, created_at)
            VALUES (?, ?, ?, ?, 'PENDING', ?, now())
            ON CONFLICT (ticket_id) DO NOTHING
            """, UUID.randomUUID(), tenantId, ticketId, amount, idempotencyKey);

        // Lock row FOR UPDATE to coordinate concurrent executions cleanly
        Map<String, Object> lockedRow = jdbcTemplate.queryForMap(
                "SELECT id, amount, status, gateway_ref FROM refunds WHERE ticket_id = ? FOR UPDATE", ticketId
        );

        UUID refundId = (UUID) lockedRow.get("id");
        String currentStatus = (String) lockedRow.get("status");
        String existingGatewayRef = (String) lockedRow.get("gateway_ref");
        BigDecimal rowAmount = (BigDecimal) lockedRow.get("amount");

        if ("SUCCESS".equals(currentStatus)) {
            log.info("Refund already executed for ticket {}: returning existing refund {}", ticketId, refundId);
            return new RefundExecutionResult(refundId, ticketId, rowAmount, existingGatewayRef, currentStatus);
        }

        // Call payment gateway
        PaymentGateway.PaymentResult result = paymentGateway.processRefund(tenantId, ticketId, amount, idempotencyKey);

        if (!result.success()) {
            jdbcTemplate.update("UPDATE refunds SET status = 'FAILED' WHERE id = ?", refundId);

            auditService.logEvent(
                    tenantId, ticketId, "System", "SYSTEM",
                    "REFUND_FAILED", "PROCESSING", "FAILED",
                    traceId, Map.of("amount", amount, "error", result.errorMessage() != null ? result.errorMessage() : "Gateway error")
            );

            throw ApiException.unprocessable("Refund execution failed: " + result.errorMessage());
        }

        jdbcTemplate.update("UPDATE refunds SET status = 'SUCCESS', gateway_ref = ? WHERE id = ?", result.gatewayRef(), refundId);

        auditService.logEvent(
                tenantId, ticketId, "System", "SYSTEM",
                "REFUND_EXECUTED", "PROCESSING", "COMPLETED",
                traceId, Map.of("amount", amount, "gatewayRef", result.gatewayRef() != null ? result.gatewayRef() : "SUCCESS")
        );

        return new RefundExecutionResult(refundId, ticketId, amount, result.gatewayRef(), "SUCCESS");
    }

    private void verifyAuthorization(UUID ticketId, BigDecimal amount) {
        // 1. Check stored enforcement result
        String enforcementSql = "SELECT verdict FROM enforcement_results WHERE ticket_id = ?";
        String verdict = null;
        try {
            verdict = jdbcTemplate.queryForObject(enforcementSql, String.class, ticketId);
        } catch (Exception ignored) {}

        if ("ALLOW".equals(verdict)) {
            // Fetch maximum allowable amount (AI decision amount or ticket requested amount)
            String allowedSql = """
                SELECT COALESCE(
                    (SELECT recommended_amount FROM ai_decisions WHERE ticket_id = ?),
                    (SELECT requested_amount FROM tickets WHERE id = ?)
                )
                """;
            BigDecimal maxAllowed = null;
            try {
                maxAllowed = jdbcTemplate.queryForObject(allowedSql, BigDecimal.class, ticketId, ticketId);
            } catch (Exception ignored) {}

            if (maxAllowed != null && amount.compareTo(maxAllowed) <= 0) {
                return; // Authorized by ALLOW
            }
            if (maxAllowed != null) {
                throw ApiException.unprocessable("Refund refused: requested amount " + amount + " exceeds approved limit " + maxAllowed);
            }
        }

        // 2. Check approved human review
        String reviewSql = "SELECT status, decision_amount FROM human_reviews WHERE ticket_id = ?";
        try {
            var reviewData = jdbcTemplate.queryForMap(reviewSql, ticketId);
            String reviewStatus = (String) reviewData.get("status");
            BigDecimal decisionAmount = (BigDecimal) reviewData.get("decision_amount");

            if ("APPROVED".equals(reviewStatus)) {
                if (decisionAmount != null && amount.compareTo(decisionAmount) <= 0) {
                    return; // Authorized by approved review
                }
                throw ApiException.unprocessable("Refund refused: amount " + amount + " exceeds human-approved amount " + decisionAmount);
            }
        } catch (Exception ignored) {}

        throw ApiException.unprocessable("Refund refused: no ALLOW verdict or approved human review exists for ticket " + ticketId);
    }
}
