package com.resolvos.policy.retrieval;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.policy.Policy;
import com.resolvos.policy.PolicyRepository;
import com.resolvos.policy.PolicyVersion;
import com.resolvos.policy.PolicyVersionRepository;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantContext;
import com.resolvos.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PolicyRetrievalIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PolicyRetrievalService retrievalService;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenantA;
    private UUID tenantB;
    private UUID policyA;
    private UUID policyB;
    private UUID chunkA1;
    private UUID chunkA2;
    private UUID chunkB1;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        policyA = UUID.randomUUID();
        policyB = UUID.randomUUID();
        chunkA1 = UUID.randomUUID();
        chunkA2 = UUID.randomUUID();
        chunkB1 = UUID.randomUUID();

        // Seed tenants
        tenantRepository.save(new Tenant(tenantA, "rag-tenant-a-" + tenantA, "Tenant A", "BANKING"));
        tenantRepository.save(new Tenant(tenantB, "rag-tenant-b-" + tenantB, "Tenant B", "ECOMMERCE"));

        // Seed Policy A (Tenant A)
        policyRepository.save(new Policy(policyA, tenantA, "Tenant A Refund Policy"));

        // Seed Version 1 (ACTIVE) for Policy A
        PolicyVersion v1 = new PolicyVersion(UUID.randomUUID(), policyA, 1, "/policies/v1.pdf");
        v1.setActive(true);
        v1.setStatus("INDEXED");
        versionRepository.save(v1);

        // Seed Version 2 (INACTIVE) for Policy A
        PolicyVersion v2 = new PolicyVersion(UUID.randomUUID(), policyA, 2, "/policies/v2.pdf");
        v2.setActive(false);
        v2.setStatus("INDEXED");
        versionRepository.save(v2);

        // Seed Policy B (Tenant B, ACTIVE)
        policyRepository.save(new Policy(policyB, tenantB, "Tenant B Return Policy"));
        PolicyVersion vB = new PolicyVersion(UUID.randomUUID(), policyB, 1, "/policies/vb.pdf");
        vB.setActive(true);
        vB.setStatus("INDEXED");
        versionRepository.save(vB);

        // Insert chunks with embeddings
        float[] vecA1 = embeddingClient.embedQuery("For technical UPI transfer declines, automated reversal shall execute within 24 hours.");
        float[] vecA2 = embeddingClient.embedQuery("old inactive refund clause");
        float[] vecB1 = embeddingClient.embedQuery("ecommerce return damaged item");

        String insertChunk = """
            INSERT INTO policy_chunks (id, tenant_id, policy_id, policy_version, document_name, page_number, section, token_count, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)
            """;

        jdbc.update(insertChunk, chunkA1, tenantA, policyA, 1, "Tenant A Refund Policy", 1, "Section 2.1 Failed UPI", 50,
                "For technical UPI transfer declines, automated reversal shall execute within 24 hours.",
                PolicyRetrievalService.formatVector(vecA1));

        jdbc.update(insertChunk, chunkA2, tenantA, policyA, 2, "Tenant A Refund Policy", 2, "Section 5.0 Deprecated", 30,
                "Old policy clause from inactive version 2.",
                PolicyRetrievalService.formatVector(vecA2));

        jdbc.update(insertChunk, chunkB1, tenantB, policyB, 1, "Tenant B Return Policy", 1, "Section 1.0 Returns", 40,
                "Damaged ecommerce items receive instant wallet refund.",
                PolicyRetrievalService.formatVector(vecB1));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM policy_chunks WHERE id IN (?, ?, ?)", chunkA1, chunkA2, chunkB1);
        jdbc.update("DELETE FROM policy_versions WHERE policy_id IN (?, ?)", policyA, policyB);
        jdbc.update("DELETE FROM policies WHERE id IN (?, ?)", policyA, policyB);
        tenantRepository.deleteById(tenantA);
        tenantRepository.deleteById(tenantB);
    }

    @Test
    void searchReturnsOnlyActiveVersionsOfCallerTenantWithAllRequiredFields() {
        TenantContext.setTenantId(tenantA.toString());

        List<PolicyReference> results = retrievalService.search(tenantA, "UPI transfer refund", 5);

        assertThat(results).isNotEmpty();

        // 1. Must contain Tenant A's active chunk
        List<UUID> chunkIds = results.stream().map(PolicyReference::chunkId).toList();
        assertThat(chunkIds).contains(chunkA1);

        // 2. Must NOT contain chunk from inactive version 2
        assertThat(chunkIds).doesNotContain(chunkA2);

        // 3. Must NOT contain chunk from Tenant B
        assertThat(chunkIds).doesNotContain(chunkB1);

        // 4. Verify all contract fields in PolicyReference
        PolicyReference ref = results.stream().filter(r -> r.chunkId().equals(chunkA1)).findFirst().orElseThrow();
        assertThat(ref.policyId()).isEqualTo(policyA);
        assertThat(ref.version()).isEqualTo(1);
        assertThat(ref.documentName()).isEqualTo("Tenant A Refund Policy");
        assertThat(ref.page()).isEqualTo(1);
        assertThat(ref.section()).isEqualTo("Section 2.1 Failed UPI");
        assertThat(ref.excerpt()).contains("automated reversal shall execute within 24 hours");
        assertThat(ref.score()).isGreaterThan(0.0);
    }
}
