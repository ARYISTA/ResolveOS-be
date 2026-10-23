package com.resolvos.ticket;

import com.resolvos.audit.AuditLog;
import com.resolvos.audit.AuditLogRepository;
import com.resolvos.audit.AuditService;
import com.resolvos.common.error.ApiException;
import com.resolvos.customer.Customer;
import com.resolvos.customer.CustomerRepository;
import com.resolvos.customer.Transaction;
import com.resolvos.customer.TransactionRepository;
import com.resolvos.enforcement.EnforcementCheck;
import com.resolvos.outbox.OutboxService;
import com.resolvos.policy.retrieval.PolicyReference;
import com.resolvos.refund.Refund;
import com.resolvos.refund.RefundRepository;
import com.resolvos.review.HumanReview;
import com.resolvos.review.HumanReviewRepository;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantContext;
import com.resolvos.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private final TicketRepository ticketRepository;
    private final CaseNodeRunRepository caseNodeRunRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final TenantRepository tenantRepository;
    private final AiDecisionRepository aiDecisionRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final EnforcementResultRepository enforcementResultRepository;
    private final HumanReviewRepository humanReviewRepository;
    private final RefundRepository refundRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public TicketService(TicketRepository ticketRepository,
                         CaseNodeRunRepository caseNodeRunRepository,
                         CustomerRepository customerRepository,
                         TransactionRepository transactionRepository,
                         TenantRepository tenantRepository,
                         AiDecisionRepository aiDecisionRepository,
                         RiskAssessmentRepository riskAssessmentRepository,
                         EnforcementResultRepository enforcementResultRepository,
                         HumanReviewRepository humanReviewRepository,
                         RefundRepository refundRepository,
                         AuditLogRepository auditLogRepository,
                         AuditService auditService,
                         OutboxService outboxService,
                         ObjectMapper objectMapper,
                         org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.ticketRepository = ticketRepository;
        this.caseNodeRunRepository = caseNodeRunRepository;
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.tenantRepository = tenantRepository;
        this.aiDecisionRepository = aiDecisionRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.enforcementResultRepository = enforcementResultRepository;
        this.humanReviewRepository = humanReviewRepository;
        this.refundRepository = refundRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public CaseDetailResponse createTicket(CreateTicketRequest req, UUID callerUserId, String callerRole) {
        UUID tenantId = resolveTenantId(req.companyId());

        if (req.requestedAmount() == null || req.requestedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("requestedAmount must be strictly positive");
        }
        if (req.customerId() == null || req.transactionId() == null) {
            throw ApiException.badRequest("customerId and transactionId are required");
        }

        UUID ticketId = UUID.randomUUID();
        String traceId = "trc-" + UUID.randomUUID().toString().substring(0, 8);
        String message = req.message() != null ? req.message() : "";

        Ticket ticket = new Ticket(
                ticketId,
                tenantId,
                req.customerId(),
                req.transactionId(),
                message,
                req.requestedAmount(),
                traceId,
                callerUserId
        );

        ticket = ticketRepository.save(ticket);

        auditService.logEvent(
                tenantId,
                ticketId,
                callerRole != null ? callerRole : "Support Agent",
                "HUMAN",
                "CASE_CREATED",
                null,
                "RECEIVED",
                traceId,
                Map.of("requestedAmount", req.requestedAmount())
        );

        return getCaseSnapshot(ticketId, tenantId);
    }

    @Transactional
    public CaseDetailResponse resolveTicket(UUID ticketId, UUID callerTenantId) {
        Ticket ticket = ticketRepository.findByIdAndTenantId(ticketId, callerTenantId)
                .orElseThrow(() -> ApiException.notFound("Ticket not found: " + ticketId));

        int updated = ticketRepository.transitionToProcessing(ticketId, callerTenantId);
        if (updated > 0) {
            ticket.setStatus("PROCESSING");
            Map<String, Object> payload = Map.of(
                    "caseId", ticketId.toString(),
                    "customerId", ticket.getCustomerId().toString(),
                    "transactionId", ticket.getTransactionId().toString(),
                    "message", ticket.getMessage() != null ? ticket.getMessage() : "",
                    "requestedAmount", ticket.getRequestedAmount().toString(),
                    "traceId", ticket.getTraceId()
            );

            outboxService.recordEvent(callerTenantId, "RESOLVE_REQUESTED", payload);
            log.info("Ticket {} atomically transitioned to PROCESSING; outbox event written", ticketId);
        } else {
            log.info("Ticket {} was not in RECEIVED/FAILED status, idempotent resolve returned", ticketId);
        }

        return getCaseSnapshot(ticketId, callerTenantId);
    }

    public CaseDetailResponse retryTicket(UUID ticketId, UUID callerTenantId) {
        Ticket ticket = ticketRepository.findByIdAndTenantId(ticketId, callerTenantId)
                .orElseThrow(() -> ApiException.notFound("Ticket not found: " + ticketId));

        if (!"FAILED".equals(ticket.getStatus())) {
            throw ApiException.conflict("Cannot retry case: no node has failed (current status: " + ticket.getStatus() + ")");
        }

        ticket.setStatus("PROCESSING");
        ticketRepository.save(ticket);

        HumanReview review = humanReviewRepository.findById(ticketId).orElse(null);
        if (review != null && "APPROVED".equals(review.getStatus())) {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("caseId", ticketId.toString());
            payload.put("traceId", ticket.getTraceId() != null ? ticket.getTraceId() : "");
            payload.put("reviewer", "System Retry");
            payload.put("decision", "APPROVED");
            payload.put("approvedAmount", review.getDecisionAmount() != null ? review.getDecisionAmount().toString() : ticket.getRequestedAmount().toString());
            if (review.getComment() != null) payload.put("comment", review.getComment());

            outboxService.recordEvent(callerTenantId, "RESUME_REQUESTED", payload);
            log.info("Ticket {} retry initiated via RESUME_REQUESTED (human review was APPROVED)", ticketId);
        } else {
            Map<String, Object> payload = Map.of(
                    "caseId", ticketId.toString(),
                    "customerId", ticket.getCustomerId().toString(),
                    "transactionId", ticket.getTransactionId().toString(),
                    "message", ticket.getMessage() != null ? ticket.getMessage() : "",
                    "requestedAmount", ticket.getRequestedAmount().toString(),
                    "traceId", ticket.getTraceId()
            );

            outboxService.recordEvent(callerTenantId, "RESOLVE_REQUESTED", payload);
            log.info("Ticket {} retry initiated via RESOLVE_REQUESTED", ticketId);
        }

        return getCaseSnapshot(ticketId, callerTenantId);
    }

    @Transactional(readOnly = true)
    public CaseDetailResponse getCaseSnapshot(UUID ticketId, UUID callerTenantId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .filter(t -> callerTenantId == null || t.getTenantId().equals(callerTenantId))
                .orElseThrow(() -> ApiException.notFound("Case not found: " + ticketId));

        Customer customer = customerRepository.findById(ticket.getCustomerId()).orElse(null);
        Transaction transaction = transactionRepository.findById(ticket.getTransactionId()).orElse(null);
        Tenant tenant = tenantRepository.findById(ticket.getTenantId()).orElse(null);

        List<CaseNodeRun> runs = caseNodeRunRepository.findByTicketIdOrderByStartedAtAsc(ticketId);
        List<CaseNodeRunDto> nodeDtos = runs.stream().map(this::toNodeDto).toList();

        List<AuditLog> auditLogs = auditLogRepository.findByCaseIdOrderByTsAsc(ticketId);
        List<AuditLogDto> auditDtos = auditLogs.stream().map(this::toAuditDto).toList();

        AiDecision aiDecision = aiDecisionRepository.findByTicketId(ticketId).orElse(null);
        RiskAssessment riskAssessment = riskAssessmentRepository.findByTicketId(ticketId).orElse(null);
        EnforcementResult enforcementResult = enforcementResultRepository.findById(ticketId).orElse(null);
        Refund refund = refundRepository.findByTicketId(ticketId).orElse(null);
        HumanReview humanReview = humanReviewRepository.findById(ticketId).orElse(null);

        ResolutionResultDto resolutionDto = buildResolutionResultDto(
                ticket, aiDecision, riskAssessment, enforcementResult, runs
        );

        String mappedStatus = mapTicketStatusToFrontend(ticket.getStatus(), ticket.getOutcome());
        String mappedOutcome = mapOutcomeToFrontend(ticket.getOutcome(), enforcementResult);

        BigDecimal approvedAmount = null;
        if (refund != null && "SUCCESS".equals(refund.getStatus())) {
            approvedAmount = refund.getAmount();
        } else if (humanReview != null && "APPROVED".equals(humanReview.getStatus()) && humanReview.getDecisionAmount() != null) {
            approvedAmount = humanReview.getDecisionAmount();
        } else if ("AUTO_APPROVED".equals(ticket.getOutcome()) || "HUMAN_APPROVED".equals(ticket.getOutcome())) {
            approvedAmount = aiDecision != null ? aiDecision.getRecommendedAmount() : ticket.getRequestedAmount();
        }

        String rejectionReason = null;
        if (humanReview != null && "REJECTED".equals(humanReview.getStatus())) {
            rejectionReason = humanReview.getComment();
        } else if (enforcementResult != null && "DENY".equalsIgnoreCase(enforcementResult.getVerdict())) {
            rejectionReason = enforcementResult.getReason();
        }

        boolean triggersHitl = humanReview != null
                || (riskAssessment != null && "HIGH".equalsIgnoreCase(riskAssessment.getRiskLevel()))
                || (enforcementResult != null && "ESCALATE".equalsIgnoreCase(enforcementResult.getVerdict()));

        String companySlug = tenant != null ? tenant.getSlug() : ticket.getTenantId().toString();
        String domain = tenant != null && tenant.getDomain() != null ? tenant.getDomain() : "banking";

        return new CaseDetailResponse(
                ticket.getId(),
                ticket.getId(),
                ticket.getCaseNumber(),
                domain,
                companySlug,
                ticket.getCustomerId(),
                customer != null ? customer.getName() : "Customer",
                customer != null ? customer.getTier() : "Standard",
                ticket.getTransactionId(),
                transaction != null ? transaction.getAmount() : ticket.getRequestedAmount(),
                ticket.getRequestedAmount(),
                approvedAmount,
                rejectionReason,
                ticket.getMessage(),
                null,
                null,
                triggersHitl,
                null,
                null,
                mappedStatus,
                ticket.getStatus(),
                mappedOutcome,
                ticket.getOutcome(),
                riskAssessment != null ? riskAssessment.getRiskLevel() : "LOW",
                ticket.getCreatedAt() != null ? ticket.getCreatedAt().toString() : Instant.now().toString(),
                ticket.getCompletedAt() != null ? ticket.getCompletedAt().toString() : null,
                ticket.getTraceId(),
                ticket.getEventSeq(),
                ticket.getEventSeq(),
                customer,
                transaction,
                nodeDtos,
                resolutionDto,
                auditDtos
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listTickets(UUID callerTenantId, int page, int size) {
        return listTickets(callerTenantId, new TicketQueryOptions(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, page, size
        ));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listTickets(UUID callerTenantId, TicketQueryOptions options) {
        StringBuilder whereSql = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (callerTenantId != null) {
            whereSql.append(" AND t.tenant_id = ? ");
            params.add(callerTenantId);
        }

        // 1. Status filter
        if (options.status() != null && !options.status().isBlank() && !"ALL".equalsIgnoreCase(options.status())) {
            String st = options.status().trim();
            if (st.equalsIgnoreCase("OPEN") || st.equalsIgnoreCase("RECEIVED")) {
                whereSql.append(" AND (t.status = 'RECEIVED' OR t.status = 'OPEN') ");
            } else if (st.equalsIgnoreCase("PROCESSING")) {
                whereSql.append(" AND t.status = 'PROCESSING' ");
            } else if (st.equalsIgnoreCase("ESCALATED") || st.equalsIgnoreCase("AWAITING_HUMAN")) {
                whereSql.append(" AND (t.status = 'AWAITING_HUMAN' OR t.status = 'ESCALATED') ");
            } else if (st.equalsIgnoreCase("RESOLVED")) {
                whereSql.append(" AND (t.status = 'COMPLETED' AND (t.outcome IS NULL OR (t.outcome != 'DENIED' AND t.outcome != 'HUMAN_REJECTED'))) ");
            } else if (st.equalsIgnoreCase("REJECTED")) {
                whereSql.append(" AND (t.status = 'FAILED' OR t.outcome = 'DENIED' OR t.outcome = 'HUMAN_REJECTED') ");
            } else if (st.equalsIgnoreCase("COMPLETED")) {
                whereSql.append(" AND t.status = 'COMPLETED' ");
            } else if (st.equalsIgnoreCase("FAILED")) {
                whereSql.append(" AND (t.status = 'FAILED' OR t.outcome = 'DENIED' OR t.outcome = 'HUMAN_REJECTED') ");
            } else {
                whereSql.append(" AND UPPER(t.status) = UPPER(?) ");
                params.add(st);
            }
        }

        // 2. Risk filter
        if (options.risk() != null && !options.risk().isBlank() && !"ALL".equalsIgnoreCase(options.risk())) {
            String rk = options.risk().trim();
            if (rk.equalsIgnoreCase("LOW")) {
                whereSql.append(" AND COALESCE(UPPER(r.risk_level), 'LOW') = 'LOW' ");
            } else {
                whereSql.append(" AND UPPER(r.risk_level) = UPPER(?) ");
                params.add(rk);
            }
        }

        // 3. Outcome filter
        if (options.outcome() != null && !options.outcome().isBlank() && !"ALL".equalsIgnoreCase(options.outcome())) {
            String oc = options.outcome().trim();
            if (oc.equalsIgnoreCase("ALLOW")) {
                whereSql.append(" AND (t.outcome IN ('AUTO_APPROVED', 'HUMAN_APPROVED', 'ALLOW') OR (t.outcome IS NULL AND (e.verdict = 'ALLOW' OR e.verdict IS NULL))) ");
            } else if (oc.equalsIgnoreCase("DENY")) {
                whereSql.append(" AND (t.outcome IN ('DENIED', 'HUMAN_REJECTED', 'DENY') OR e.verdict = 'DENY') ");
            } else if (oc.equalsIgnoreCase("ESCALATE")) {
                whereSql.append(" AND (t.outcome = 'ESCALATE' OR e.verdict = 'ESCALATE') ");
            } else {
                whereSql.append(" AND (UPPER(t.outcome) = UPPER(?) OR UPPER(e.verdict) = UPPER(?)) ");
                params.add(oc);
                params.add(oc);
            }
        }

        // 4. Search filter (ID, case_number, traceId, message, customer name)
        String search = options.effectiveSearch();
        if (search != null) {
            whereSql.append(" AND (t.id::text ILIKE ? OR t.case_number ILIKE ? OR t.trace_id ILIKE ? OR t.message ILIKE ? OR c.name ILIKE ?) ");
            String pattern = "%" + search + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        // 5. Customer filter
        String cust = options.effectiveCustomer();
        if (cust != null) {
            try {
                UUID custId = UUID.fromString(cust);
                whereSql.append(" AND (t.customer_id = ? OR c.name ILIKE ?) ");
                params.add(custId);
                params.add("%" + cust + "%");
            } catch (IllegalArgumentException ex) {
                whereSql.append(" AND c.name ILIKE ? ");
                params.add("%" + cust + "%");
            }
        }

        // 6. Date range
        String from = options.effectiveFrom();
        if (from != null) {
            Instant fromInstant = parseDateTime(from, false);
            if (fromInstant != null) {
                whereSql.append(" AND t.created_at >= ? ");
                params.add(java.sql.Timestamp.from(fromInstant));
            }
        }

        String to = options.effectiveTo();
        if (to != null) {
            Instant toInstant = parseDateTime(to, true);
            if (toInstant != null) {
                whereSql.append(" AND t.created_at <= ? ");
                params.add(java.sql.Timestamp.from(toInstant));
            }
        }

        String countSql = "SELECT COUNT(DISTINCT t.id) FROM tickets t " +
                "LEFT JOIN customers c ON c.id = t.customer_id " +
                "LEFT JOIN risk_assessments r ON r.ticket_id = t.id " +
                "LEFT JOIN enforcement_results e ON e.ticket_id = t.id " +
                whereSql;

        Long totalCountLong = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = totalCountLong != null ? totalCountLong : 0L;

        // Sorting
        String sortBy = options.effectiveSortBy();
        String sortOrder = options.effectiveSortOrder();
        String sortColumn = switch (sortBy) {
            case "amount" -> "t.requested_amount";
            case "status" -> "t.status";
            default -> "t.created_at";
        };
        String orderDir = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";

        int page = Math.max(1, options.page());
        int size = options.size() > 0 ? options.size() : 20;
        int offset = (page - 1) * size;

        String querySql = "SELECT t.id FROM tickets t " +
                "LEFT JOIN customers c ON c.id = t.customer_id " +
                "LEFT JOIN risk_assessments r ON r.ticket_id = t.id " +
                "LEFT JOIN enforcement_results e ON e.ticket_id = t.id " +
                whereSql +
                " ORDER BY " + sortColumn + " " + orderDir + ", t.id DESC " +
                " LIMIT ? OFFSET ?";

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(size);
        queryParams.add(offset);

        List<UUID> ticketIds = jdbcTemplate.query(querySql, (rs, rowNum) -> rs.getObject("id", UUID.class), queryParams.toArray());

        List<CaseDetailResponse> items = ticketIds.stream()
                .map(id -> getCaseSnapshot(id, callerTenantId))
                .toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 1;
        if (totalPages == 0 && totalElements == 0) totalPages = 1;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("cases", items);
        result.put("totalElements", totalElements);
        result.put("totalCount", totalElements);
        result.put("page", page);
        result.put("size", size);
        result.put("limit", size);
        result.put("totalPages", totalPages);
        return result;
    }

    private Instant parseDateTime(String text, boolean endOfDay) {
        if (text == null || text.isBlank()) return null;
        try {
            if (text.length() == 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
                java.time.LocalDate ld = java.time.LocalDate.parse(text);
                return endOfDay
                        ? ld.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusNanos(1000)
                        : ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(text);
        } catch (Exception e) {
            log.warn("Unable to parse date filter {}: {}", text, e.getMessage());
            return null;
        }
    }

    private CaseNodeRunDto toNodeDto(CaseNodeRun run) {
        Object output = null;
        if (run.getOutput() != null && !run.getOutput().isBlank()) {
            try {
                output = objectMapper.readValue(run.getOutput(), Object.class);
            } catch (Exception e) {
                output = run.getOutput();
            }
        }
        return new CaseNodeRunDto(
                run.getNode(),
                run.getStatus(),
                run.getDurationMs(),
                output,
                run.getStartedAt(),
                run.getCompletedAt()
        );
    }

    private AuditLogDto toAuditDto(AuditLog logEntry) {
        Object meta = null;
        if (logEntry.getMetadata() != null && !logEntry.getMetadata().isBlank()) {
            try {
                meta = objectMapper.readValue(logEntry.getMetadata(), Object.class);
            } catch (Exception e) {
                meta = logEntry.getMetadata();
            }
        }
        return new AuditLogDto(
                logEntry.getId(),
                logEntry.getTs(),
                logEntry.getActor(),
                logEntry.getActorType(),
                logEntry.getAction(),
                logEntry.getOldState(),
                logEntry.getNewState(),
                logEntry.getTraceId(),
                meta
        );
    }

    private ResolutionResultDto buildResolutionResultDto(Ticket ticket,
                                                         AiDecision aiDecision,
                                                         RiskAssessment riskAssessment,
                                                         EnforcementResult enforcementResult,
                                                         List<CaseNodeRun> runs) {
        if (aiDecision == null && enforcementResult == null) {
            return null;
        }

        List<PolicyReference> citations = new ArrayList<>();
        runs.stream()
                .filter(r -> "POLICY_RETRIEVAL".equals(r.getNode()) && r.getOutput() != null)
                .findFirst()
                .ifPresent(r -> {
                    try {
                        JsonNode node = objectMapper.readTree(r.getOutput());
                        if (node.has("citations") && node.get("citations").isArray()) {
                            for (JsonNode c : node.get("citations")) {
                                citations.add(objectMapper.treeToValue(c, PolicyReference.class));
                            }
                        }
                    } catch (Exception ignored) {}
                });

        List<EnforcementCheck> checks = new ArrayList<>();
        if (enforcementResult != null && enforcementResult.getChecks() != null) {
            try {
                checks = objectMapper.readValue(enforcementResult.getChecks(), new TypeReference<List<EnforcementCheck>>() {});
            } catch (Exception ignored) {}
        }

        return new ResolutionResultDto(
                ticket.getId(),
                aiDecision != null ? aiDecision.getDecision() : "APPROVE_REFUND",
                aiDecision != null ? aiDecision.getRecommendedAmount() : ticket.getRequestedAmount(),
                aiDecision != null ? aiDecision.getConfidence() : new BigDecimal("0.890"),
                riskAssessment != null ? riskAssessment.getRiskLevel() : "LOW",
                citations,
                aiDecision != null && aiDecision.isRequiresHumanReview(),
                aiDecision != null ? aiDecision.getReasoningSummary() : "",
                checks,
                enforcementResult != null ? enforcementResult.getVerdict() : "ALLOW",
                ticket.getTraceId()
        );
    }

    private String mapTicketStatusToFrontend(String status, String outcome) {
        if (status == null) return "OPEN";
        return switch (status) {
            case "RECEIVED" -> "OPEN";
            case "PROCESSING" -> "PROCESSING";
            case "AWAITING_HUMAN" -> "ESCALATED";
            case "FAILED" -> "REJECTED";
            case "COMPLETED" -> {
                if ("DENIED".equals(outcome) || "HUMAN_REJECTED".equals(outcome)) {
                    yield "REJECTED";
                } else {
                    yield "RESOLVED";
                }
            }
            default -> status;
        };
    }

    private String mapOutcomeToFrontend(String outcome, EnforcementResult enforcementResult) {
        if (outcome != null) {
            return switch (outcome) {
                case "AUTO_APPROVED", "HUMAN_APPROVED" -> "ALLOW";
                case "DENIED", "HUMAN_REJECTED" -> "DENY";
                default -> outcome;
            };
        }
        if (enforcementResult != null) {
            return enforcementResult.getVerdict();
        }
        return null;
    }

    public UUID resolveTenantId(String companyIdOrSlug) {
        String ctxTenant = TenantContext.getTenantId();
        if (ctxTenant != null && !ctxTenant.isBlank()) {
            return UUID.fromString(ctxTenant);
        }
        if (companyIdOrSlug != null && !companyIdOrSlug.isBlank()) {
            try {
                return UUID.fromString(companyIdOrSlug);
            } catch (IllegalArgumentException e) {
                return tenantRepository.findBySlug(companyIdOrSlug)
                        .map(Tenant::getId)
                        .orElseThrow(() -> ApiException.badRequest("Invalid company id or slug: " + companyIdOrSlug));
            }
        }
        throw ApiException.unauthorized("No tenant context available");
    }
}
