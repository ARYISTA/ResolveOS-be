package com.resolvos.review;

import com.resolvos.audit.AuditService;
import com.resolvos.common.error.ApiException;
import com.resolvos.customer.Transaction;
import com.resolvos.customer.TransactionRepository;
import com.resolvos.outbox.OutboxService;
import com.resolvos.tenant.TenantSettings;
import com.resolvos.tenant.TenantSettingsRepository;
import com.resolvos.ticket.CaseDetailResponse;
import com.resolvos.ticket.Ticket;
import com.resolvos.ticket.TicketRepository;
import com.resolvos.ticket.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final HumanReviewRepository humanReviewRepository;
    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final TransactionRepository transactionRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public ReviewService(HumanReviewRepository humanReviewRepository,
                         TicketRepository ticketRepository,
                         TicketService ticketService,
                         TransactionRepository transactionRepository,
                         TenantSettingsRepository tenantSettingsRepository,
                         AuditService auditService,
                         OutboxService outboxService) {
        this.humanReviewRepository = humanReviewRepository;
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.transactionRepository = transactionRepository;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public List<CaseDetailResponse> getOpenEscalations(UUID tenantId) {
        List<Ticket> awaitingTickets = ticketRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, "AWAITING_HUMAN");
        return awaitingTickets.stream()
                .map(t -> ticketService.getCaseSnapshot(t.getId(), tenantId))
                .toList();
    }

    @Transactional(readOnly = true)
    public CaseDetailResponse getEscalation(UUID caseId, UUID tenantId) {
        Ticket ticket = ticketRepository.findByIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Escalation not found: " + caseId));

        return ticketService.getCaseSnapshot(ticket.getId(), tenantId);
    }

    @Transactional
    public CaseDetailResponse approve(UUID caseId, UUID tenantId, ApproveEscalationRequest request, UUID reviewerId, String reviewerName) {
        Ticket ticket = ticketRepository.findByIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Case not found: " + caseId));

        HumanReview review = humanReviewRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("No human review found for case: " + caseId));

        if (!"OPEN".equals(review.getStatus()) || !"AWAITING_HUMAN".equals(ticket.getStatus())) {
            throw ApiException.conflict("Case " + caseId + " is not awaiting human review (current status: "
                    + ticket.getStatus() + ", review status: " + review.getStatus() + ")");
        }

        BigDecimal amount = (request != null && request.amount() != null) ? request.amount()
                : ((request != null && request.modifiedAmount() != null) ? request.modifiedAmount()
                : ticket.getRequestedAmount());

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.unprocessable("Approved amount must be greater than 0");
        }

        Transaction txn = transactionRepository.findById(ticket.getTransactionId()).orElse(null);
        if (txn != null && amount.compareTo(txn.getAmount()) > 0) {
            throw ApiException.unprocessable("Approved amount (" + amount + ") exceeds transaction amount (" + txn.getAmount() + ")");
        }

        TenantSettings settings = tenantSettingsRepository.findById(tenantId).orElse(null);
        if (settings != null && settings.getMaxRefundPerCustomer() != null && amount.compareTo(settings.getMaxRefundPerCustomer()) > 0) {
            throw ApiException.unprocessable("Approved amount (" + amount + ") exceeds customer period limit (" + settings.getMaxRefundPerCustomer() + ")");
        }

        String comment = (request != null && request.comment() != null && !request.comment().isBlank())
                ? request.comment().trim()
                : "Approved by reviewer";

        review.setStatus("APPROVED");
        review.setDecisionAmount(amount);
        review.setComment(comment);
        review.setReviewerId(reviewerId);
        review.setResolvedAt(Instant.now());
        humanReviewRepository.save(review);

        ticket.setStatus("PROCESSING");
        ticketRepository.save(ticket);

        String actor = (reviewerName != null && !reviewerName.isBlank()) ? reviewerName : "Compliance Reviewer";
        auditService.logEvent(
                tenantId, caseId, actor, "HUMAN",
                "HUMAN_APPROVED", "AWAITING_HUMAN", "PROCESSING", ticket.getTraceId(),
                Map.of("approvedAmount", amount, "comment", comment)
        );

        Map<String, Object> outboxPayload = Map.of(
                "caseId", caseId.toString(),
                "traceId", ticket.getTraceId() != null ? ticket.getTraceId() : "",
                "reviewer", actor,
                "decision", "APPROVED",
                "approvedAmount", amount.toString(),
                "comment", comment
        );
        outboxService.recordEvent(tenantId, "RESUME_REQUESTED", outboxPayload);
        log.info("Escalation for case {} APPROVED for ₹{} by {}", caseId, amount, actor);

        return ticketService.getCaseSnapshot(caseId, tenantId);
    }

    @Transactional
    public CaseDetailResponse reject(UUID caseId, UUID tenantId, RejectEscalationRequest request, UUID reviewerId, String reviewerName) {
        Ticket ticket = ticketRepository.findByIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Case not found: " + caseId));

        HumanReview review = humanReviewRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("No human review found for case: " + caseId));

        if (!"OPEN".equals(review.getStatus()) || !"AWAITING_HUMAN".equals(ticket.getStatus())) {
            throw ApiException.conflict("Case " + caseId + " is not awaiting human review (current status: "
                    + ticket.getStatus() + ", review status: " + review.getStatus() + ")");
        }

        String comment = null;
        if (request != null) {
            if (request.comment() != null && !request.comment().isBlank()) {
                comment = request.comment().trim();
            } else if (request.reason() != null && !request.reason().isBlank()) {
                comment = request.reason().trim();
            }
        }

        if (comment == null || comment.length() < 10) {
            throw ApiException.unprocessable("Rejection comment is required and must be at least 10 characters");
        }

        review.setStatus("REJECTED");
        review.setComment(comment);
        review.setReviewerId(reviewerId);
        review.setResolvedAt(Instant.now());
        humanReviewRepository.save(review);

        ticket.setStatus("PROCESSING");
        ticketRepository.save(ticket);

        String actor = (reviewerName != null && !reviewerName.isBlank()) ? reviewerName : "Compliance Reviewer";
        auditService.logEvent(
                tenantId, caseId, actor, "HUMAN",
                "HUMAN_REJECTED", "AWAITING_HUMAN", "PROCESSING", ticket.getTraceId(),
                Map.of("comment", comment)
        );

        Map<String, Object> outboxPayload = new HashMap<>();
        outboxPayload.put("caseId", caseId.toString());
        outboxPayload.put("traceId", ticket.getTraceId() != null ? ticket.getTraceId() : "");
        outboxPayload.put("reviewer", actor);
        outboxPayload.put("decision", "REJECTED");
        outboxPayload.put("comment", comment);
        outboxService.recordEvent(tenantId, "RESUME_REQUESTED", outboxPayload);
        log.info("Escalation for case {} REJECTED by {}: {}", caseId, actor, comment);

        return ticketService.getCaseSnapshot(caseId, tenantId);
    }
}
