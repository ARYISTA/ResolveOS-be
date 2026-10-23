package com.resolvos.policy.ingestion;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.policy.*;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantContext;
import com.resolvos.tenant.TenantRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IngestionServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Autowired
    private PolicyChunkRepository chunkRepository;

    @Autowired
    private TenantRepository tenantRepository;

    private UUID tenantId;
    private UUID policyId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        policyId = UUID.randomUUID();

        tenantRepository.save(new Tenant(tenantId, "ingest-tenant-" + tenantId, "Ingest Co", "BANKING"));
        policyRepository.save(new Policy(policyId, tenantId, "Dispute Resolution Policy"));

        PolicyVersion pv = new PolicyVersion(UUID.randomUUID(), policyId, 1, "/policies/dispute.pdf");
        versionRepository.save(pv);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM policy_chunks WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policy_versions WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policies WHERE id = ?", policyId);
        tenantRepository.deleteById(tenantId);
    }

    @Test
    void ingestionPipelineParsesChunksEmbedsAndIndexesSuccessfully() throws IOException {
        byte[] pdfBytes = createSamplePdf();

        ingestionService.ingest(tenantId, policyId, 1, "Dispute Resolution Policy", pdfBytes);

        // Verify version status and metadata
        PolicyVersion updatedVersion = versionRepository.findByPolicyIdAndVersion(policyId, 1).orElseThrow();
        assertThat(updatedVersion.getStatus()).isEqualTo("INDEXED");
        assertThat(updatedVersion.getPageCount()).isEqualTo(2);
        assertThat(updatedVersion.isActive()).isTrue();
        assertThat(updatedVersion.getIngestionConfig()).contains("chunkSizeTokens");

        // Verify chunks created in database
        List<PolicyChunk> chunks = chunkRepository.findByPolicyIdAndPolicyVersionOrderByPageNumberAsc(policyId, 1);
        assertThat(chunks).isNotEmpty();

        PolicyChunk chunk1 = chunks.stream().filter(c -> c.getPageNumber() == 1).findFirst().orElseThrow();
        assertThat(chunk1.getTenantId()).isEqualTo(tenantId);
        assertThat(chunk1.getDocumentName()).isEqualTo("Dispute Resolution Policy");
        assertThat(chunk1.getSection()).contains("Section 1.0 General Terms");
        assertThat(chunk1.getContent()).contains("Eligible transactions must be submitted within 60 days");
        assertThat(chunk1.getTokenCount()).isGreaterThan(0);

        PolicyChunk chunk2 = chunks.stream().filter(c -> c.getPageNumber() == 2).findFirst().orElseThrow();
        assertThat(chunk2.getTenantId()).isEqualTo(tenantId);
        assertThat(chunk2.getSection()).contains("Section 2.0 Refund Limits");
        assertThat(chunk2.getContent()).contains("Refunds above 25000 require manual review");
    }

    private byte[] createSamplePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Section 1.0 General Terms and Scope");
                cs.newLineAtOffset(0, -20);
                cs.showText("Eligible transactions must be submitted within 60 days of settlement.");
                cs.endText();
            }

            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Section 2.0 Refund Limits and Approvals");
                cs.newLineAtOffset(0, -20);
                cs.showText("Refunds above 25000 require manual review before debit reversal.");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
