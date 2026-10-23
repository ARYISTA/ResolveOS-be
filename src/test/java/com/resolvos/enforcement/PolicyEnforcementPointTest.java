package com.resolvos.enforcement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyEnforcementPointTest {

    private PolicyEnforcementPoint pep;
    private UUID tenantId;
    private UUID otherTenantId;

    @BeforeEach
    void setUp() {
        pep = new PolicyEnforcementPoint();
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
    }

    private EnforcementContext validContext() {
        return new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("2500.00"),
                new BigDecimal("0.890"),
                "LOW",
                tenantId,
                tenantId,
                tenantId,
                List.of(tenantId),
                3,
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true,
                true,
                true,
                false,
                new BigDecimal("0.700"),
                new BigDecimal("5000.00"),
                new BigDecimal("25000.00")
        );
    }

    @Test
    @DisplayName("Golden path: all 7 rules pass -> ALLOW")
    void testAllPassYieldsAllow() {
        EnforcementContext ctx = validContext();
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.ALLOW);
        assertThat(result.checks()).hasSize(7);
        assertThat(result.checks()).allMatch(EnforcementCheck::passed);
    }

    @Test
    @DisplayName("Rule 1 SCHEMA_VALID: negative or zero amount -> DENY")
    void testSchemaValidFailsOnZeroOrNegativeAmount() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                BigDecimal.ZERO,
                new BigDecimal("0.890"),
                "LOW",
                tenantId, tenantId, tenantId, List.of(tenantId),
                3, new BigDecimal("3000.00"), BigDecimal.ZERO, new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.DENY);
        assertThat(result.checks().get(0).rule()).isEqualTo("SCHEMA_VALID");
        assertThat(result.checks().get(0).passed()).isFalse();
    }

    @Test
    @DisplayName("Rule 2 TENANT_VALID: cross-tenant entity mismatch -> DENY")
    void testTenantValidFailsOnCrossTenantMismatch() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("2500.00"),
                new BigDecimal("0.890"),
                "LOW",
                tenantId,
                otherTenantId, // Cross-tenant customer!
                tenantId,
                List.of(tenantId),
                3, new BigDecimal("3000.00"), BigDecimal.ZERO, new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.DENY);
        assertThat(result.checks().get(1).rule()).isEqualTo("TENANT_VALID");
        assertThat(result.checks().get(1).passed()).isFalse();
    }

    @Test
    @DisplayName("Rule 3 POLICY_CONDITIONS_MET: no active citations or REJECT decision -> DENY")
    void testPolicyConditionsMetFailsWhenNoCitations() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("2500.00"),
                new BigDecimal("0.890"),
                "LOW",
                tenantId, tenantId, tenantId, List.of(tenantId),
                0, // 0 citations
                new BigDecimal("3000.00"), BigDecimal.ZERO, new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.DENY);
        assertThat(result.checks().get(2).rule()).isEqualTo("POLICY_CONDITIONS_MET");
        assertThat(result.checks().get(2).passed()).isFalse();
    }

    @Test
    @DisplayName("Rule 4 AMOUNT_WITHIN_LIMIT: requested amount exceeds transaction amount -> DENY")
    void testAmountWithinLimitFailsWhenExceedingTransaction() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("5000.00"), // 5000 > 3000
                new BigDecimal("0.890"),
                "LOW",
                tenantId, tenantId, tenantId, List.of(tenantId),
                3,
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.DENY);
        assertThat(result.checks().get(3).rule()).isEqualTo("AMOUNT_WITHIN_LIMIT");
        assertThat(result.checks().get(3).passed()).isFalse();
    }

    @Test
    @DisplayName("Rule 5 TRANSACTION_VALID: transaction already refunded -> DENY")
    void testTransactionValidFailsWhenAlreadyRefunded() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("2500.00"),
                new BigDecimal("0.890"),
                "LOW",
                tenantId, tenantId, tenantId, List.of(tenantId),
                3,
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true, true, true,
                true, // already refunded!
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.DENY);
        assertThat(result.checks().get(4).rule()).isEqualTo("TRANSACTION_VALID");
        assertThat(result.checks().get(4).passed()).isFalse();
    }

    @Test
    @DisplayName("Rule 6 RISK_ACCEPTABLE: high risk -> ESCALATE")
    void testRiskAcceptableFailsOnHighRisk() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("2500.00"),
                new BigDecimal("0.890"),
                "HIGH", // HIGH risk!
                tenantId, tenantId, tenantId, List.of(tenantId),
                3,
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.ESCALATE);
        assertThat(result.checks().get(5).rule()).isEqualTo("RISK_ACCEPTABLE");
        assertThat(result.checks().get(5).passed()).isFalse();
    }

    @Test
    @DisplayName("Rule 7 HITL_REQUIRED: amount exceeds autoApproveLimit -> ESCALATE")
    void testHitlRequiredFailsWhenAmountExceedsAutoApproveLimit() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("15000.00"), // 15,000 > autoApproveLimit (5,000)
                new BigDecimal("0.890"),
                "LOW",
                tenantId, tenantId, tenantId, List.of(tenantId),
                3,
                new BigDecimal("20000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.ESCALATE);
        assertThat(result.checks().get(6).rule()).isEqualTo("HITL_REQUIRED");
        assertThat(result.checks().get(6).passed()).isFalse();
    }

    @Test
    @DisplayName("Verdict combinations: DENY takes precedence over ESCALATE")
    void testDenyTakesPrecedenceOverEscalate() {
        EnforcementContext ctx = new EnforcementContext(
                "APPROVE_REFUND",
                new BigDecimal("35000.00"), // Exceeds autoApprove AND hitlTriggerLimit (ESCALATE)
                new BigDecimal("0.890"),
                "HIGH",                     // HIGH risk (ESCALATE)
                tenantId, tenantId, tenantId, List.of(tenantId),
                0,                          // 0 citations (DENY!)
                new BigDecimal("50000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.DENY);
    }

    @Test
    @DisplayName("Decision NEEDS_INFO always yields ESCALATE")
    void testNeedsInfoYieldsEscalate() {
        EnforcementContext ctx = new EnforcementContext(
                "NEEDS_INFO",
                new BigDecimal("2500.00"),
                new BigDecimal("0.890"),
                "LOW",
                tenantId, tenantId, tenantId, List.of(tenantId),
                3,
                new BigDecimal("3000.00"),
                BigDecimal.ZERO,
                new BigDecimal("100000.00"),
                true, true, true, false,
                new BigDecimal("0.700"), new BigDecimal("5000.00"), new BigDecimal("25000.00")
        );
        PolicyEnforcementPoint.PepResult result = pep.evaluate(ctx);

        assertThat(result.verdict()).isEqualTo(Verdict.ESCALATE);
    }
}
