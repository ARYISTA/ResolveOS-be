package com.resolvos.enforcement;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class PolicyEnforcementPoint {

    public record PepResult(
            Verdict verdict,
            List<EnforcementCheck> checks,
            String reason
    ) {}

    public PepResult evaluate(EnforcementContext ctx) {
        List<EnforcementCheck> checks = new ArrayList<>(7);
        List<String> denyReasons = new ArrayList<>();
        List<String> escalateReasons = new ArrayList<>();

        // 1. SCHEMA_VALID (DENY)
        boolean schemaValid = ctx.amount() != null
                && ctx.amount().compareTo(BigDecimal.ZERO) > 0
                && ctx.decision() != null
                && !ctx.decision().isBlank();
        if (schemaValid) {
            checks.add(EnforcementCheck.pass("SCHEMA_VALID", "Decision and positive amount are present"));
        } else {
            String r = "Decision payload invalid or amount not strictly positive";
            checks.add(EnforcementCheck.fail("SCHEMA_VALID", r));
            denyReasons.add(r);
        }

        // 2. TENANT_VALID (DENY)
        boolean tenantValid = ctx.caseTenantId() != null
                && ctx.caseTenantId().equals(ctx.customerTenantId())
                && ctx.caseTenantId().equals(ctx.transactionTenantId())
                && (ctx.chunkTenantIds() == null || ctx.chunkTenantIds().stream().allMatch(id -> ctx.caseTenantId().equals(id)));
        if (tenantValid) {
            checks.add(EnforcementCheck.pass("TENANT_VALID", "All entities belong to the case tenant"));
        } else {
            String r = "Cross-tenant entity mismatch detected";
            checks.add(EnforcementCheck.fail("TENANT_VALID", r));
            denyReasons.add(r);
        }

        // 3. POLICY_CONDITIONS_MET (DENY)
        boolean policyOk = ctx.activePolicyCitationsCount() > 0 && !"REJECT".equalsIgnoreCase(ctx.decision());
        if (policyOk) {
            checks.add(EnforcementCheck.pass("POLICY_CONDITIONS_MET", "Action supported by active policy citation"));
        } else {
            String r = "REJECT".equalsIgnoreCase(ctx.decision())
                    ? "Decision is REJECT: claim does not qualify under policy"
                    : "No active policy citation supports the requested refund";
            checks.add(EnforcementCheck.fail("POLICY_CONDITIONS_MET", r));
            denyReasons.add(r);
        }

        // 4. AMOUNT_WITHIN_LIMIT (DENY)
        BigDecimal totalPeriod = (ctx.customerPeriodRefunds() != null ? ctx.customerPeriodRefunds() : BigDecimal.ZERO)
                .add(ctx.amount() != null ? ctx.amount() : BigDecimal.ZERO);
        boolean amountOk = ctx.amount() != null
                && ctx.transactionAmount() != null
                && ctx.amount().compareTo(ctx.transactionAmount()) <= 0
                && (ctx.maxRefundPerCustomer() == null || totalPeriod.compareTo(ctx.maxRefundPerCustomer()) <= 0);
        if (amountOk) {
            checks.add(EnforcementCheck.pass("AMOUNT_WITHIN_LIMIT", "Amount is within transaction amount and period refund cap"));
        } else {
            String r = (ctx.amount() != null && ctx.transactionAmount() != null && ctx.amount().compareTo(ctx.transactionAmount()) > 0)
                    ? "Requested amount exceeds original transaction amount"
                    : "Requested amount exceeds customer cumulative refund cap";
            checks.add(EnforcementCheck.fail("AMOUNT_WITHIN_LIMIT", r));
            denyReasons.add(r);
        }

        // 5. TRANSACTION_VALID (DENY)
        boolean txnOk = ctx.transactionExists()
                && ctx.transactionBelongsToCustomer()
                && ctx.transactionWithinWindow()
                && !ctx.transactionAlreadyRefunded();
        if (txnOk) {
            checks.add(EnforcementCheck.pass("TRANSACTION_VALID", "Transaction is valid, within window, and not already refunded"));
        } else {
            String r = !ctx.transactionExists() ? "Transaction not found"
                    : !ctx.transactionBelongsToCustomer() ? "Transaction does not belong to the claiming customer"
                    : ctx.transactionAlreadyRefunded() ? "Transaction has already been refunded"
                    : "Transaction occurred outside allowable refund window";
            checks.add(EnforcementCheck.fail("TRANSACTION_VALID", r));
            denyReasons.add(r);
        }

        // 6. RISK_ACCEPTABLE (ESCALATE)
        boolean riskOk = !"HIGH".equalsIgnoreCase(ctx.riskLevel())
                && ctx.confidence() != null
                && ctx.minConfidence() != null
                && ctx.confidence().compareTo(ctx.minConfidence()) >= 0;
        if (riskOk) {
            checks.add(EnforcementCheck.pass("RISK_ACCEPTABLE", "Risk is acceptable and confidence meets threshold"));
        } else {
            String r = "HIGH".equalsIgnoreCase(ctx.riskLevel())
                    ? "High risk assessment requires human review"
                    : "Confidence (" + ctx.confidence() + ") below minimum threshold (" + ctx.minConfidence() + ")";
            checks.add(EnforcementCheck.fail("RISK_ACCEPTABLE", r));
            escalateReasons.add(r);
        }

        // 7. HITL_REQUIRED (ESCALATE)
        boolean hitlOk = ctx.amount() != null
                && ctx.autoApproveLimit() != null
                && ctx.amount().compareTo(ctx.autoApproveLimit()) <= 0
                && (ctx.hitlTriggerLimit() == null || ctx.amount().compareTo(ctx.hitlTriggerLimit()) <= 0);
        if (hitlOk) {
            checks.add(EnforcementCheck.pass("HITL_REQUIRED", "Amount qualifies for auto-approval without HITL"));
        } else {
            String r = (ctx.hitlTriggerLimit() != null && ctx.amount() != null && ctx.amount().compareTo(ctx.hitlTriggerLimit()) > 0)
                    ? "Amount exceeds HITL trigger threshold (" + ctx.hitlTriggerLimit() + ")"
                    : "Amount exceeds auto-approve threshold (" + ctx.autoApproveLimit() + ")";
            checks.add(EnforcementCheck.fail("HITL_REQUIRED", r));
            escalateReasons.add(r);
        }

        // Needs info also triggers escalation
        if ("NEEDS_INFO".equalsIgnoreCase(ctx.decision())) {
            escalateReasons.add("Decision requires additional information from customer");
        }

        // Verdict Resolution
        if (!denyReasons.isEmpty()) {
            return new PepResult(Verdict.DENY, checks, String.join("; ", denyReasons));
        } else if (!escalateReasons.isEmpty()) {
            return new PepResult(Verdict.ESCALATE, checks, String.join("; ", escalateReasons));
        } else {
            return new PepResult(Verdict.ALLOW, checks, "All policy enforcement checks passed");
        }
    }
}
