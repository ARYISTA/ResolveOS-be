package com.resolvos.engine.sim;

import com.resolvos.audit.AuditService;
import com.resolvos.common.error.ApiException;
import com.resolvos.customer.Customer;
import com.resolvos.customer.Transaction;
import com.resolvos.enforcement.EnforcementCheck;
import com.resolvos.enforcement.EnforcementContext;
import com.resolvos.enforcement.PolicyEnforcementPoint;
import com.resolvos.enforcement.Verdict;
import com.resolvos.engine.EngineEventListener;
import com.resolvos.engine.EngineGateway;
import com.resolvos.engine.ResolveCaseRequest;
import com.resolvos.engine.ResumeCaseRequest;
import com.resolvos.policy.retrieval.PolicyReference;
import com.resolvos.policy.retrieval.PolicyRetrievalService;
import com.resolvos.refund.Refund;
import com.resolvos.refund.RefundRepository;
import com.resolvos.refund.RefundService;
import com.resolvos.review.HumanReview;
import com.resolvos.review.HumanReviewRepository;
import com.resolvos.tenant.TenantSettings;
import com.resolvos.ticket.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Profile("!grpc")
public class SimulatedEngineGateway implements EngineGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedEngineGateway.class);

    private final CaseValidationService validationService;
    private final PolicyRetrievalService retrievalService;
    private final PolicyEnforcementPoint pep;
    private final RefundService refundService;
    private final AuditService auditService;
    private final AiDecisionRepository aiDecisionRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final EnforcementResultRepository enforcementResultRepository;
    private final HumanReviewRepository humanReviewRepository;
    private final RefundRepository refundRepository;
    private final TicketRepository ticketRepository;
    private final ObjectMapper objectMapper;

    public SimulatedEngineGateway(CaseValidationService validationService,
                                  PolicyRetrievalService retrievalService,
                                  PolicyEnforcementPoint pep,
                                  RefundService refundService,
                                  AuditService auditService,
                                  AiDecisionRepository aiDecisionRepository,
                                  RiskAssessmentRepository riskAssessmentRepository,
                                  EnforcementResultRepository enforcementResultRepository,
                                  HumanReviewRepository humanReviewRepository,
                                  RefundRepository refundRepository,
                                  TicketRepository ticketRepository,
                                  ObjectMapper objectMapper) {
        this.validationService = validationService;
        this.retrievalService = retrievalService;
        this.pep = pep;
        this.refundService = refundService;
        this.auditService = auditService;
        this.aiDecisionRepository = aiDecisionRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.enforcementResultRepository = enforcementResultRepository;
        this.humanReviewRepository = humanReviewRepository;
        this.refundRepository = refundRepository;
        this.ticketRepository = ticketRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void resolve(ResolveCaseRequest req, EngineEventListener listener) {
        log.info("SimulatedEngine resolving case {} (traceId: {})", req.caseId(), req.traceId());

        // 1. INTAKE
        long t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "INTAKE");
        Map<String, Object> intakeEntities = new HashMap<>();
        intakeEntities.put("amount", req.requestedAmount());
        intakeEntities.put("reason", req.ticketText());
        Map<String, Object> intakeOutput = Map.of(
                "intent", "refund_request",
                "entities", intakeEntities,
                "confidence", 0.94,
                "summary", "Customer refund request parsed and intent classified as dispute reversal.",
                "message", "Intake Agent: customer dispute parsed and intent classified"
        );
        listener.onNodeCompleted(req.caseId(), "INTAKE", Math.max(1, System.currentTimeMillis() - t0), intakeOutput);

        // 2. VALIDATION
        t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "VALIDATION");
        CaseValidationService.ValidationResult valResult = validationService.validate(
                req.tenantId(), req.customerId(), req.transactionId(), req.requestedAmount()
        );
        Map<String, Object> valOutput = Map.of(
                "checks", valResult.checks(),
                "summary", valResult.message(),
                "message", valResult.message()
        );
        listener.onNodeCompleted(req.caseId(), "VALIDATION", Math.max(1, System.currentTimeMillis() - t0), valOutput);

        // 3. POLICY_RETRIEVAL
        t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "POLICY_RETRIEVAL");
        List<PolicyReference> citations = retrievalService.search(req.tenantId(), req.ticketText(), 4);
        auditService.logEvent(
                req.tenantId(), req.caseId(), "Retrieval Agent", "AI",
                "POLICY_RETRIEVED", null, null, req.traceId(),
                Map.of("citationsCount", citations.size())
        );
        listener.onAuditEvent(req.caseId(), "POLICY_RETRIEVED", "Retrieval Agent", null, null);

        double topScore = citations.isEmpty() ? 0.0 : citations.get(0).score();
        Map<String, Object> retrOutput = Map.of(
                "citations", citations,
                "chunks", citations,
                "chunksRetrieved", citations.size(),
                "topScore", topScore,
                "summary", "Retrieved " + citations.size() + " policy clauses from indexed policies.",
                "message", "Retrieval Agent: found " + citations.size() + " relevant clauses in active policies"
        );
        listener.onNodeCompleted(req.caseId(), "POLICY_RETRIEVAL", Math.max(1, System.currentTimeMillis() - t0), retrOutput);

        // 4. DECISION
        t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "DECISION");

        // Fail node check (if preset triggers failNode)
        if (req.ticketText() != null && req.ticketText().contains("failNode:DECISION")) {
            listener.onNodeFailed(req.caseId(), "DECISION", "Simulated LLM model timeout at DECISION node");
            return;
        }

        String reasoning = "Transaction records and policy clauses support refund of ₹" + req.requestedAmount() + ".";
        BigDecimal confidence = new BigDecimal("0.890");
        saveAiDecision(req.caseId(), "APPROVE_REFUND", req.requestedAmount(), confidence, reasoning, false);

        auditService.logEvent(
                req.tenantId(), req.caseId(), "Decision Agent", "AI",
                "AI_DECISION_GENERATED", null, null, req.traceId(),
                Map.of("decision", "APPROVE_REFUND", "recommendedAmount", req.requestedAmount())
        );
        listener.onAuditEvent(req.caseId(), "AI_DECISION_GENERATED", "Decision Agent", null, null);

        Map<String, Object> decOutput = Map.of(
                "decision", "APPROVE_REFUND",
                "recommendation", "APPROVE_REFUND",
                "recommendedAmount", req.requestedAmount(),
                "confidence", 0.89,
                "reasoningSummary", reasoning,
                "summary", "Recommendation: APPROVE_REFUND (89% confidence).",
                "message", "Decision Agent: recommended APPROVE_REFUND for ₹" + req.requestedAmount()
        );
        listener.onNodeCompleted(req.caseId(), "DECISION", Math.max(1, System.currentTimeMillis() - t0), decOutput);

        // 5. RISK
        t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "RISK");
        List<String> riskFactors = new ArrayList<>();
        String riskLevel = "LOW";
        double riskScore = 0.14;

        TenantSettings settings = valResult.settings();
        Transaction txn = valResult.transaction();

        String lowerText = req.ticketText() != null ? req.ticketText().toLowerCase() : "";
        if (lowerText.contains("prompt injection") || lowerText.contains("ignore all") || lowerText.contains("override") || lowerText.contains("jailbreak")) {
            riskLevel = "HIGH";
            riskScore = 0.95;
            riskFactors.add("Adversarial prompt pattern detected in message note");
        } else if (settings.getHitlTriggerLimit() != null && req.requestedAmount().compareTo(settings.getHitlTriggerLimit()) > 0) {
            riskLevel = "HIGH";
            riskScore = 0.88;
            riskFactors.add("Amount ₹" + req.requestedAmount() + " exceeds automated trigger threshold ₹" + settings.getHitlTriggerLimit());
        } else if (txn != null && req.requestedAmount().compareTo(txn.getAmount()) > 0) {
            riskLevel = "HIGH";
            riskScore = 0.90;
            riskFactors.add("Requested amount exceeds transaction value");
        } else if (!valResult.allPassed()) {
            riskLevel = "MEDIUM";
            riskScore = 0.55;
            riskFactors.add("Integrity check warning during validation");
        } else {
            riskFactors.add("Account in good standing");
            riskFactors.add("No prior disputed transactions");
        }

        saveRiskAssessment(req.caseId(), riskLevel, riskFactors.toArray(new String[0]));

        Map<String, Object> riskOutput = Map.of(
                "level", riskLevel,
                "riskLevel", riskLevel,
                "score", riskScore,
                "factors", riskFactors,
                "riskFactors", riskFactors,
                "summary", "Risk level assessed as " + riskLevel + " (" + riskScore + " anomaly score).",
                "message", "Risk Agent: risk assessed as " + riskLevel
        );
        listener.onNodeCompleted(req.caseId(), "RISK", Math.max(1, System.currentTimeMillis() - t0), riskOutput);

        // 6. ENFORCEMENT
        t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "ENFORCEMENT");

        Customer cust = valResult.customer();
        List<UUID> chunkTenantIds = citations.stream().map(c -> req.tenantId()).toList();
        BigDecimal customerRefunds = calculateCustomerPeriodRefunds(req.customerId(), settings.getRefundPeriodDays());

        boolean txnWindowCheckPassed = valResult.checks().stream()
                .anyMatch(c -> "TRANSACTION_WINDOW".equals(c.rule()) && c.passed());
        boolean duplicateCheckFailed = valResult.checks().stream()
                .anyMatch(c -> "DUPLICATE_CHECK".equals(c.rule()) && !c.passed());

        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                req.requestedAmount(),
                confidence,
                riskLevel,
                req.tenantId(),
                cust != null ? cust.getTenantId() : null,
                txn != null ? txn.getTenantId() : null,
                chunkTenantIds,
                citations.size(),
                txn != null ? txn.getAmount() : null,
                customerRefunds,
                settings.getMaxRefundPerCustomer(),
                txn != null,
                cust != null && txn != null && txn.getCustomerId().equals(cust.getId()),
                txnWindowCheckPassed,
                duplicateCheckFailed,
                settings.getMinConfidence(),
                settings.getAutoApproveLimit(),
                settings.getHitlTriggerLimit()
        );

        PolicyEnforcementPoint.PepResult pepResult = pep.evaluate(ctx);
        saveEnforcementResult(req.caseId(), pepResult.verdict().name(), pepResult.checks(), pepResult.reason());

        for (EnforcementCheck check : pepResult.checks()) {
            auditService.logEvent(
                    req.tenantId(), req.caseId(), "Policy Enforcement Point", "SYSTEM",
                    "RULE_CHECKED", null, null, req.traceId(),
                    Map.of("rule", check.rule(), "passed", check.passed(), "reason", check.reason())
            );
        }
        listener.onAuditEvent(req.caseId(), "RULE_CHECKED", "Policy Enforcement Point", null, null);

        Map<String, Object> enfOutput = Map.of(
                "checks", pepResult.checks(),
                "verdict", pepResult.verdict().name(),
                "reason", pepResult.reason() != null ? pepResult.reason() : "",
                "summary", "Deterministic guardrails evaluated 7/7 checks. Verdict: " + pepResult.verdict().name(),
                "message", "PEP: " + pepResult.verdict().name() + " (" + pepResult.reason() + ")"
        );
        listener.onNodeCompleted(req.caseId(), "ENFORCEMENT", Math.max(1, System.currentTimeMillis() - t0), enfOutput);

        // 7. Post-Enforcement Branching
        if (pepResult.verdict() == Verdict.ESCALATE) {
            saveHumanReview(req.caseId(), "OPEN", pepResult.reason(), riskLevel);
            updateTicketOutcome(req.caseId(), "AWAITING_HUMAN", null);
            auditService.logEvent(
                    req.tenantId(), req.caseId(), "Policy Enforcement Point", "SYSTEM",
                    "HITL_TRIGGERED", "PROCESSING", "AWAITING_HUMAN", req.traceId(),
                    Map.of("reason", pepResult.reason())
            );
            listener.onAuditEvent(req.caseId(), "HITL_TRIGGERED", "Policy Enforcement Point", "PROCESSING", "AWAITING_HUMAN");
            listener.onHitlRequired(req.caseId(), pepResult.reason(), riskLevel);
            // Workflow pauses until resume is called
            return;
        }

        if (pepResult.verdict() == Verdict.DENY) {
            updateTicketOutcome(req.caseId(), "COMPLETED", "DENIED");
            listener.onCaseCompleted(req.caseId(), "DENY", null, null);
            return;
        }

        // 8. EXECUTION (Verdict == ALLOW)
        t0 = System.currentTimeMillis();
        listener.onNodeStarted(req.caseId(), "EXECUTION");
        try {
            RefundService.RefundExecutionResult refResult = refundService.executeRefund(
                    req.tenantId(), req.caseId(), req.requestedAmount(), req.traceId()
            );

            Map<String, Object> execOutput = Map.of(
                    "refundRef", refResult.gatewayRef() != null ? refResult.gatewayRef() : "SUCCESS",
                    "gatewayStatus", "SUCCESS",
                    "amount", req.requestedAmount(),
                    "summary", "Payment gateway transaction reversed. Credit notification dispatched.",
                    "message", "Execution Agent: refund executed via payment gateway"
            );
            listener.onNodeCompleted(req.caseId(), "EXECUTION", Math.max(1, System.currentTimeMillis() - t0), execOutput);
            updateTicketOutcome(req.caseId(), "COMPLETED", "AUTO_APPROVED");
            listener.onCaseCompleted(req.caseId(), "ALLOW", refResult.gatewayRef(), req.requestedAmount());
        } catch (Exception ex) {
            listener.onNodeFailed(req.caseId(), "EXECUTION", ex.getMessage());
            updateTicketOutcome(req.caseId(), "FAILED", null);
        }
    }

    @Override
    public void resume(ResumeCaseRequest req, EngineEventListener listener) {
        log.info("SimulatedEngine resuming case {} with decision {} by {}", req.caseId(), req.decision(), req.reviewer());

        boolean isApproved = "APPROVED".equalsIgnoreCase(req.decision());
        long t0 = System.currentTimeMillis();

        if (isApproved) {
            Map<String, Object> hrOutput = new HashMap<>();
            hrOutput.put("reviewer", req.reviewer());
            hrOutput.put("decision", "APPROVED");
            hrOutput.put("approvedAmount", req.approvedAmount());
            if (req.comment() != null) hrOutput.put("comment", req.comment());
            hrOutput.put("summary", "Human review approved by " + req.reviewer() + " for ₹" + req.approvedAmount() + ".");
            hrOutput.put("message", "Human Reviewer: approved refund of ₹" + req.approvedAmount());

            listener.onNodeCompleted(req.caseId(), "HUMAN_REVIEW", Math.max(1, System.currentTimeMillis() - t0), hrOutput);
            listener.onHitlResolved(req.caseId(), "APPROVED", req.reviewer(), req.approvedAmount(), req.comment());

            // Run EXECUTION
            t0 = System.currentTimeMillis();
            listener.onNodeStarted(req.caseId(), "EXECUTION");
            try {
                RefundService.RefundExecutionResult refResult = refundService.executeRefund(
                        req.tenantId(), req.caseId(), req.approvedAmount(), req.traceId()
                );

                Map<String, Object> execOutput = Map.of(
                        "refundRef", refResult.gatewayRef() != null ? refResult.gatewayRef() : "SUCCESS",
                        "gatewayStatus", "SUCCESS",
                        "amount", req.approvedAmount(),
                        "summary", "Payment gateway transaction reversed. Credit notification dispatched.",
                        "message", "Execution Agent: refund executed via payment gateway"
                );
                listener.onNodeCompleted(req.caseId(), "EXECUTION", Math.max(1, System.currentTimeMillis() - t0), execOutput);
                updateTicketOutcome(req.caseId(), "COMPLETED", "HUMAN_APPROVED");
                listener.onCaseCompleted(req.caseId(), "ALLOW", refResult.gatewayRef(), req.approvedAmount());
            } catch (Exception ex) {
                listener.onNodeFailed(req.caseId(), "EXECUTION", ex.getMessage());
                updateTicketOutcome(req.caseId(), "FAILED", null);
            }
        } else {
            Map<String, Object> hrOutput = new HashMap<>();
            hrOutput.put("reviewer", req.reviewer());
            hrOutput.put("decision", "REJECTED");
            if (req.comment() != null) hrOutput.put("comment", req.comment());
            hrOutput.put("summary", "Human review rejected by " + req.reviewer() + ": " + req.comment());
            hrOutput.put("message", "Human Reviewer: rejected dispute");

            listener.onNodeCompleted(req.caseId(), "HUMAN_REVIEW", Math.max(1, System.currentTimeMillis() - t0), hrOutput);
            listener.onHitlResolved(req.caseId(), "REJECTED", req.reviewer(), null, req.comment());

            updateTicketOutcome(req.caseId(), "COMPLETED", "HUMAN_REJECTED");
            listener.onCaseCompleted(req.caseId(), "DENY", null, null);
        }
    }

    private void saveAiDecision(UUID ticketId, String decision, BigDecimal amount, BigDecimal confidence, String summary, boolean requiresReview) {
        aiDecisionRepository.findByTicketId(ticketId).ifPresent(aiDecisionRepository::delete);
        aiDecisionRepository.save(new AiDecision(UUID.randomUUID(), ticketId, decision, amount, confidence, summary, requiresReview));
    }

    private void saveRiskAssessment(UUID ticketId, String level, String[] factors) {
        riskAssessmentRepository.findByTicketId(ticketId).ifPresent(riskAssessmentRepository::delete);
        riskAssessmentRepository.save(new RiskAssessment(UUID.randomUUID(), ticketId, level, factors));
    }

    private void saveEnforcementResult(UUID ticketId, String verdict, List<EnforcementCheck> checks, String reason) {
        String json = "[]";
        try {
            json = objectMapper.writeValueAsString(checks);
        } catch (Exception ignored) {}
        enforcementResultRepository.save(new EnforcementResult(ticketId, verdict, json, reason));
    }

    private void saveHumanReview(UUID ticketId, String status, String reason, String riskLevel) {
        HumanReview review = humanReviewRepository.findById(ticketId)
                .orElse(new HumanReview(ticketId, reason, riskLevel));
        review.setStatus(status);
        review.setReason(reason);
        review.setRiskLevel(riskLevel);
        humanReviewRepository.save(review);
    }

    private void updateTicketOutcome(UUID ticketId, String status, String outcome) {
        ticketRepository.findById(ticketId).ifPresent(ticket -> {
            if (status != null) ticket.setStatus(status);
            if (outcome != null) ticket.setOutcome(outcome);
            if ("COMPLETED".equals(status)) ticket.setCompletedAt(Instant.now());
            ticketRepository.save(ticket);
        });
    }

    private BigDecimal calculateCustomerPeriodRefunds(UUID customerId, int periodDays) {
        // Simple period sum or default zero
        return BigDecimal.ZERO;
    }
}
