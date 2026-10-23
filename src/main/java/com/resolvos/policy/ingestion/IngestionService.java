package com.resolvos.policy.ingestion;

import com.resolvos.common.config.EmbeddingProperties;
import com.resolvos.common.config.RagProperties;
import com.resolvos.policy.PolicyVersion;
import com.resolvos.policy.PolicyVersionRepository;
import com.resolvos.policy.retrieval.EmbeddingClient;
import com.resolvos.policy.retrieval.PolicyRetrievalService;
import com.resolvos.realtime.RealtimeBroadcaster;
import com.resolvos.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final PdfTextExtractor pdfTextExtractor;
    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final PolicyVersionRepository versionRepository;
    private final RealtimeBroadcaster realtimeBroadcaster;
    private final JdbcTemplate jdbcTemplate;
    private final RagProperties ragProperties;
    private final EmbeddingProperties embeddingProperties;

    public IngestionService(PdfTextExtractor pdfTextExtractor,
                            Chunker chunker,
                            EmbeddingClient embeddingClient,
                            PolicyVersionRepository versionRepository,
                            RealtimeBroadcaster realtimeBroadcaster,
                            JdbcTemplate jdbcTemplate,
                            RagProperties ragProperties,
                            EmbeddingProperties embeddingProperties) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.versionRepository = versionRepository;
        this.realtimeBroadcaster = realtimeBroadcaster;
        this.jdbcTemplate = jdbcTemplate;
        this.ragProperties = ragProperties;
        this.embeddingProperties = embeddingProperties;
    }

    @Async
    public void ingestAsync(UUID tenantId, UUID policyId, int version, String documentName, byte[] pdfBytes) {
        ingest(tenantId, policyId, version, documentName, pdfBytes);
    }

    @Transactional
    public void ingest(UUID tenantId, UUID policyId, int version, String documentName, byte[] pdfBytes) {
        TenantContext.setTenantId(tenantId.toString());
        try {
            // Stage 1: PARSING
            publishProgress(tenantId, policyId, version, "PARSING", 20);
            updateVersionStatus(policyId, version, "PARSING");

            PdfTextExtractor.ExtractedPdf extracted = pdfTextExtractor.extract(pdfBytes);

            // Stage 2: CHUNKING
            publishProgress(tenantId, policyId, version, "CHUNKING", 50);
            updateVersionStatus(policyId, version, "CHUNKING");

            List<Chunker.ChunkDraft> drafts = chunker.chunkPages(extracted.pages());

            // Stage 3: EMBEDDING
            publishProgress(tenantId, policyId, version, "EMBEDDING", 80);
            updateVersionStatus(policyId, version, "EMBEDDING");

            List<String> contents = drafts.stream().map(Chunker.ChunkDraft::content).toList();
            List<float[]> embeddings = embeddingClient.embed(contents);

            // Stage 4: INDEXED
            // Clear existing chunks if any for this policy version
            jdbcTemplate.update("DELETE FROM policy_chunks WHERE policy_id = ? AND policy_version = ?", policyId, version);

            String insertSql = """
                INSERT INTO policy_chunks (id, tenant_id, policy_id, policy_version, document_name, page_number, section, token_count, content, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)
                """;

            List<Object[]> batchArgs = new ArrayList<>(drafts.size());
            for (int i = 0; i < drafts.size(); i++) {
                Chunker.ChunkDraft d = drafts.get(i);
                float[] emb = (i < embeddings.size()) ? embeddings.get(i) : new float[embeddingClient.dimensions()];
                String vectorStr = PolicyRetrievalService.formatVector(emb);

                batchArgs.add(new Object[]{
                        UUID.randomUUID(),
                        tenantId,
                        policyId,
                        version,
                        documentName,
                        d.pageNumber(),
                        d.section(),
                        d.tokenCount(),
                        d.content(),
                        vectorStr
                });
            }

            jdbcTemplate.batchUpdate(insertSql, batchArgs);

            // Build ingestion config json
            String ingestionConfigJson = String.format(
                    "{\"chunkSizeTokens\":%d,\"chunkOverlapTokens\":%d,\"provider\":\"%s\",\"model\":\"%s\",\"dimensions\":%d,\"date\":\"%s\"}",
                    ragProperties.chunkSizeTokens(),
                    ragProperties.chunkOverlapTokens(),
                    embeddingProperties.provider(),
                    embeddingProperties.model(),
                    embeddingProperties.dimensions(),
                    Instant.now().toString()
            );

            // Update version status and active flag if first version
            PolicyVersion pv = versionRepository.findByPolicyIdAndVersion(policyId, version)
                    .orElseThrow(() -> new IllegalStateException("Policy version not found: " + policyId + " v" + version));

            pv.setStatus("INDEXED");
            pv.setPageCount(extracted.totalPages());
            pv.setIngestionConfig(ingestionConfigJson);

            // If no version of this policy is currently active, activate this one
            boolean hasActive = versionRepository.findByPolicyIdAndActiveTrue(policyId).isPresent();
            if (!hasActive) {
                pv.setActive(true);
            }
            versionRepository.save(pv);

            publishProgress(tenantId, policyId, version, "INDEXED", 100);
            log.info("Successfully indexed policy {} v{} with {} chunks across {} pages",
                    policyId, version, drafts.size(), extracted.totalPages());

        } catch (Exception e) {
            log.error("Ingestion failed for policy {} v{}: {}", policyId, version, e.getMessage(), e);
            updateVersionStatus(policyId, version, "FAILED");
            realtimeBroadcaster.broadcastToTenant(tenantId, IngestionProgressEvent.failed(policyId, version, e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    private void updateVersionStatus(UUID policyId, int version, String status) {
        versionRepository.findByPolicyIdAndVersion(policyId, version).ifPresent(pv -> {
            pv.setStatus(status);
            versionRepository.save(pv);
        });
    }

    private void publishProgress(UUID tenantId, UUID policyId, int version, String stage, int percent) {
        realtimeBroadcaster.broadcastToTenant(tenantId, new IngestionProgressEvent(policyId, version, stage, percent));
    }
}
