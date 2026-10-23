package com.resolvos.policy.retrieval;

import com.resolvos.common.config.RagProperties;
import com.resolvos.common.error.ApiException;
import com.resolvos.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PolicyRetrievalService {

    private final EmbeddingClient embeddingClient;
    private final JdbcTemplate jdbcTemplate;
    private final int defaultTopK;

    public PolicyRetrievalService(EmbeddingClient embeddingClient,
                                  JdbcTemplate jdbcTemplate,
                                  RagProperties ragProperties) {
        this.embeddingClient = embeddingClient;
        this.jdbcTemplate = jdbcTemplate;
        this.defaultTopK = ragProperties.topK();
    }

    public List<PolicyReference> search(UUID tenantId, String queryText, Integer topK) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        UUID targetTenantId = tenantId;
        if (targetTenantId == null) {
            String ctxTenant = TenantContext.getTenantId();
            if (ctxTenant == null || ctxTenant.isBlank()) {
                throw ApiException.unauthorized("No tenant context for policy search");
            }
            targetTenantId = UUID.fromString(ctxTenant);
        }

        int k = (topK != null && topK > 0) ? topK : defaultTopK;
        float[] queryVector = embeddingClient.embedQuery(queryText);
        String vectorStr = formatVector(queryVector);

        String sql = """
            SELECT pc.policy_id,
                   pc.policy_version,
                   pc.document_name,
                   pc.page_number,
                   pc.section,
                   pc.id AS chunk_id,
                   pc.content AS excerpt,
                   (1.0 - (pc.embedding <=> ?::vector)) AS score
            FROM policy_chunks pc
            JOIN policy_versions pv ON pv.policy_id = pc.policy_id AND pv.version = pc.policy_version
            WHERE pc.tenant_id = ?
              AND pv.active = true
            ORDER BY pc.embedding <=> ?::vector ASC
            LIMIT ?
            """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new PolicyReference(
                        (UUID) rs.getObject("policy_id"),
                        rs.getInt("policy_version"),
                        rs.getString("document_name"),
                        rs.getInt("page_number"),
                        rs.getString("section"),
                        (UUID) rs.getObject("chunk_id"),
                        rs.getString("excerpt"),
                        rs.getDouble("score")
                ),
                vectorStr, targetTenantId, vectorStr, k
        );
    }

    public static String formatVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
