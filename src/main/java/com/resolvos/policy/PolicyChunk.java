package com.resolvos.policy;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "policy_chunks")
public class PolicyChunk {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_version", nullable = false)
    private int policyVersion;

    @Column(name = "document_name", nullable = false)
    private String documentName;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(nullable = false)
    private String section;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    protected PolicyChunk() {}

    public PolicyChunk(UUID id, UUID tenantId, UUID policyId, int policyVersion,
                       String documentName, int pageNumber, String section,
                       int tokenCount, String content) {
        this.id = id;
        this.tenantId = tenantId;
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.documentName = documentName;
        this.pageNumber = pageNumber;
        this.section = section;
        this.tokenCount = tokenCount;
        this.content = content;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPolicyId() { return policyId; }
    public int getPolicyVersion() { return policyVersion; }
    public String getDocumentName() { return documentName; }
    public int getPageNumber() { return pageNumber; }
    public String getSection() { return section; }
    public int getTokenCount() { return tokenCount; }
    public String getContent() { return content; }
}
