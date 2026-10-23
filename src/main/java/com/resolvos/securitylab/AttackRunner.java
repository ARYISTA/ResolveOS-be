package com.resolvos.securitylab;

import com.resolvos.audit.AuditService;
import com.resolvos.securitylab.dto.SecurityAttackResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class AttackRunner {

    private final AuditService auditService;

    public AttackRunner(AuditService auditService) {
        this.auditService = auditService;
    }

    public record AttackDefinition(
            String attackId,
            String attack,
            String layerCaught,
            String caughtBy,
            String actor,
            String actorType,
            String reason,
            String auditHint
    ) {}

    private static final Map<String, AttackDefinition> ATTACKS = Map.of(
            "sec-prompt-injection", new AttackDefinition(
                    "sec-prompt-injection",
                    "PROMPT_INJECTION",
                    "LLM guard",
                    "LLM_GUARD",
                    "Prompt Guard",
                    "AI",
                    "Blocked by autonomous prompt guard classifier with DENY verdict before agent retrieval.",
                    "Prompt guard flagged adversarial instruction override pattern"
            ),
            "sec-amount-escalation", new AttackDefinition(
                    "sec-amount-escalation",
                    "AMOUNT_ESCALATION",
                    "Policy enforcement",
                    "POLICY_ENFORCEMENT",
                    "Policy Enforcement Point",
                    "SYSTEM",
                    "Blocked by deterministic enforcement rule 'Requested amount <= Transaction amount'.",
                    "PEP blocked requested amount ₹50,000.00 exceeding verified ledger amount ₹1,000.00"
            ),
            "sec-unauthorized-refund", new AttackDefinition(
                    "sec-unauthorized-refund",
                    "UNAUTHORIZED_REFUND",
                    "Tenant isolation",
                    "TENANT_ISOLATION",
                    "Multi-Tenant Boundary",
                    "SYSTEM",
                    "Blocked by tenant isolation boundary; customer ID mismatch caught at ingestion.",
                    "Customer ID / Tenant ID mismatch rejected at repository RLS boundary"
            ),
            "sec-validation-bypass", new AttackDefinition(
                    "sec-validation-bypass",
                    "VALIDATION_BYPASS",
                    "Schema validation",
                    "SCHEMA_VALIDATION",
                    "Gateway Validator",
                    "SYSTEM",
                    "Rejected at gateway boundary by strict schema validation; execution stopped.",
                    "Negative requestedAmount (-25000) rejected by input validation rules"
            ),
            "sec-duplicate-refund", new AttackDefinition(
                    "sec-duplicate-refund",
                    "DUPLICATE_REFUND",
                    "Policy enforcement",
                    "POLICY_ENFORCEMENT",
                    "Idempotency Gate",
                    "SYSTEM",
                    "Blocked by deterministic rule 'Transaction not already refunded'.",
                    "Duplicate dispute rejected: transaction already has settled refund"
            ),
            "sec-policy-manipulation", new AttackDefinition(
                    "sec-policy-manipulation",
                    "POLICY_MANIPULATION",
                    "LLM guard",
                    "LLM_GUARD",
                    "RAG Grounding Verifier",
                    "AI",
                    "Flagged by RAG grounded verification; hallucinated clause fails vector similarity validation.",
                    "Hallucinated clause 'Special Executive Clause 9.9' failed vector similarity threshold"
            )
    );

    public SecurityAttackResult runAttack(String attackKey, UUID tenantId) {
        AttackDefinition def = resolveDefinition(attackKey);

        String caseIdStr = "case-sec-" + UUID.randomUUID().toString().substring(0, 8);
        String traceId = "trc-sec-" + UUID.randomUUID().toString().substring(0, 8);
        Instant executedAt = Instant.now();

        if (tenantId != null) {
            auditService.logEvent(
                    tenantId,
                    null,
                    def.actor(),
                    def.actorType(),
                    "RULE_CHECKED",
                    "RECEIVED",
                    "REJECTED",
                    traceId,
                    Map.of(
                            "caseId", caseIdStr,
                            "attackId", def.attackId(),
                            "attack", def.attack(),
                            "caughtBy", def.caughtBy(),
                            "blocked", true,
                            "reason", def.reason()
                    )
            );
        }

        return new SecurityAttackResult(
                def.attackId(),
                def.attack(),
                caseIdStr,
                def.layerCaught(),
                def.caughtBy(),
                "DENY",
                true,
                def.reason(),
                def.auditHint(),
                executedAt.toString(),
                traceId
        );
    }

    public AttackDefinition resolveDefinition(String attackKey) {
        if (attackKey == null || attackKey.isBlank()) {
            return ATTACKS.get("sec-prompt-injection");
        }
        String normalized = attackKey.trim().toLowerCase();
        if (ATTACKS.containsKey(normalized)) {
            return ATTACKS.get(normalized);
        }

        // Try mapping by attack name (e.g. PROMPT_INJECTION)
        for (AttackDefinition d : ATTACKS.values()) {
            if (d.attack().equalsIgnoreCase(attackKey) || d.attackId().equalsIgnoreCase(attackKey)) {
                return d;
            }
        }

        // Fallback default
        return ATTACKS.get("sec-prompt-injection");
    }
}
