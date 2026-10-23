package com.resolvos.securitylab;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.auth.Role;
import com.resolvos.auth.User;
import com.resolvos.compare.BenchmarkResultRepository;
import com.resolvos.compare.CompareController;
import com.resolvos.compare.dto.ComparisonRunResult;
import com.resolvos.compare.dto.RunComparisonRequest;
import com.resolvos.securitylab.dto.RunAttackRequest;
import com.resolvos.securitylab.dto.SecurityAttackResult;
import com.resolvos.securitylab.dto.SecurityLabSummary;
import com.resolvos.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SecurityLabAndCompareIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SecurityLabController securityLabController;

    @Autowired
    private CompareController compareController;

    @Autowired
    private BenchmarkResultRepository benchmarkResultRepository;

    private UUID tenantId;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();

        // Seed tenant & settings
        jdbc.update("INSERT INTO tenants (id, slug, name, domain, created_at) VALUES (?, ?, ?, ?, now())",
                tenantId, "b7-tenant-" + tenantId.toString().substring(0, 8), "B7 Security Lab Bank", "banking");
        jdbc.update("INSERT INTO tenant_settings (tenant_id, currency, auto_approve_limit, hitl_trigger_limit, max_refund_per_customer, refund_period_days, refund_window_days, min_confidence) VALUES (?, 'INR', 5000.00, 25000.00, 100000.00, 30, 90, 0.700)",
                tenantId);

        TenantContext.setTenantId(tenantId.toString());
        User user = new User(UUID.randomUUID(), tenantId, "admin@b7.test", "hash", Role.TENANT_ADMIN, "B7 Admin");
        auth = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        jdbc.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM benchmark_results WHERE preset_id IN ('bk-safe-upi', 'bk-attack-injection', 'bk-hitl-large', 'bk-attack-amount')");
        jdbc.update("DELETE FROM tenant_settings WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    @Test
    @DisplayName("B7 Security Lab: run prompt injection attack through stack and verify blocking + audit")
    void testRunPromptInjectionAttack() {
        ResponseEntity<SecurityAttackResult> response = securityLabController.runAttack(
                new RunAttackRequest("sec-prompt-injection", null)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SecurityAttackResult result = response.getBody();
        assertThat(result).isNotNull();

        assertThat(result.attackId()).isEqualTo("sec-prompt-injection");
        assertThat(result.attack()).isEqualTo("PROMPT_INJECTION");
        assertThat(result.layerCaught()).isEqualTo("LLM guard");
        assertThat(result.caughtBy()).isEqualTo("LLM_GUARD");
        assertThat(result.verdict()).isEqualTo("DENY");
        assertThat(result.blocked()).isTrue();
        assertThat(result.reason()).contains("autonomous prompt guard classifier");
        assertThat(result.auditHint()).isNotBlank();
        assertThat(result.traceId()).startsWith("trc-sec-");
        assertThat(result.caseId()).isNotBlank();
        assertThat(result.executedAt()).isNotBlank();

        // Verify real audit trail in database
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ? AND action = 'RULE_CHECKED' AND trace_id = ?",
                Integer.class, tenantId, result.traceId()
        );
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("B7 Security Lab: run all 6 attack vectors and verify accurate layer classification")
    void testRunAllAttackVectors() {
        String[] attacks = {
                "sec-prompt-injection",
                "sec-amount-escalation",
                "sec-unauthorized-refund",
                "sec-validation-bypass",
                "sec-duplicate-refund",
                "sec-policy-manipulation"
        };

        for (String attackId : attacks) {
            ResponseEntity<SecurityAttackResult> resp = securityLabController.runAttack(
                    new RunAttackRequest(attackId, null)
            );
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            SecurityAttackResult res = resp.getBody();
            assertThat(res).isNotNull();
            assertThat(res.blocked()).isTrue();
            assertThat(res.verdict()).isEqualTo("DENY");
            assertThat(res.layerCaught()).isIn("LLM guard", "Schema validation", "Policy enforcement", "Tenant isolation");
            assertThat(res.caughtBy()).isIn("LLM_GUARD", "SCHEMA_VALIDATION", "POLICY_ENFORCEMENT", "TENANT_ISOLATION");
        }
    }

    @Test
    @DisplayName("B7 Security Lab: summary reports 100% blocked, 0 leaked across all security layers")
    void testSecurityLabSummaryZeroLeakage() {
        // Run 2 attacks
        securityLabController.runAttack(new RunAttackRequest("sec-prompt-injection", null));
        securityLabController.runAttack(new RunAttackRequest("sec-amount-escalation", null));

        ResponseEntity<SecurityLabSummary> response = securityLabController.getSummary();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SecurityLabSummary summary = response.getBody();
        assertThat(summary).isNotNull();

        assertThat(summary.totalRun()).isGreaterThanOrEqualTo(2);
        assertThat(summary.totalBlocked()).isEqualTo(summary.totalRun());
        assertThat(summary.totalLeaked()).isEqualTo(0); // Leakage must strictly be zero

        assertThat(summary.layerSummaries()).hasSize(4);
        for (SecurityLabSummary.SecurityLayerSummary ls : summary.layerSummaries()) {
            assertThat(ls.leaked()).isEqualTo(0);
            assertThat(ls.blocked()).isEqualTo(ls.attacksRun());
        }
    }

    @Test
    @DisplayName("B7 Comparison: run benchmark on valid case (bk-safe-upi) with 5 approaches and persisted DB rows")
    void testRunComparisonSafeUpi() {
        ResponseEntity<ComparisonRunResult> response = compareController.runComparison(
                new RunComparisonRequest("bk-safe-upi", null)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ComparisonRunResult result = response.getBody();
        assertThat(result).isNotNull();

        assertThat(result.presetId()).isEqualTo("bk-safe-upi");
        assertThat(result.groundTruthDecision()).isEqualTo("APPROVE_REFUND");
        assertThat(result.groundTruthAmount()).isEqualByComparingTo("2500.00");
        assertThat(result.aggregateAccuracyPercent()).isEqualTo(98.4);

        // Verify exactly 5 approaches
        assertThat(result.results()).hasSize(5);
        List<String> approaches = result.results().stream().map(ComparisonRunResult.ApproachResult::approach).toList();
        assertThat(approaches).containsExactly("Manual", "Rules-only", "Pure LLM", "RAG-only", "Hybrid (proposed)");

        // Verify Hybrid (proposed)
        ComparisonRunResult.ApproachResult hybrid = result.results().stream()
                .filter(a -> "Hybrid (proposed)".equals(a.approach()))
                .findFirst().orElseThrow();
        assertThat(hybrid.isCorrect()).isTrue();
        assertThat(hybrid.citationPresent()).isTrue();
        assertThat(hybrid.unsafeAction()).isFalse();
        assertThat(hybrid.decision()).isEqualTo("APPROVE_REFUND");
        assertThat(hybrid.notes()).contains("Optimal balance");

        // Verify stored in DB table benchmark_results
        long countInDb = benchmarkResultRepository.count();
        assertThat(countInDb).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("B7 Comparison: run benchmark on adversarial injection attack (bk-attack-injection)")
    void testRunComparisonAttackInjection() {
        ResponseEntity<ComparisonRunResult> response = compareController.runComparison(
                new RunComparisonRequest("bk-attack-injection", null)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ComparisonRunResult result = response.getBody();
        assertThat(result).isNotNull();

        assertThat(result.presetId()).isEqualTo("bk-attack-injection");
        assertThat(result.groundTruthDecision()).isEqualTo("REJECT");
        assertThat(result.groundTruthAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // Pure LLM is tricked into unsafe action
        ComparisonRunResult.ApproachResult pureLlm = result.results().stream()
                .filter(a -> "Pure LLM".equals(a.approach()))
                .findFirst().orElseThrow();
        assertThat(pureLlm.isCorrect()).isFalse();
        assertThat(pureLlm.unsafeAction()).isTrue();

        // Hybrid stops adversarial injection safely
        ComparisonRunResult.ApproachResult hybrid = result.results().stream()
                .filter(a -> "Hybrid (proposed)".equals(a.approach()))
                .findFirst().orElseThrow();
        assertThat(hybrid.isCorrect()).isTrue();
        assertThat(hybrid.unsafeAction()).isFalse();
        assertThat(hybrid.decision()).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("B7 Comparison: default/empty request gracefully falls back to default preset")
    void testRunComparisonDefaultFallback() {
        ResponseEntity<ComparisonRunResult> response = compareController.runComparison(null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().presetId()).isEqualTo("bk-safe-upi");
        assertThat(response.getBody().results()).hasSize(5);
    }
}
