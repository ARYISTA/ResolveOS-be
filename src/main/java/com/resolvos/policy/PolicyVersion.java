package com.resolvos.policy;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_versions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"policy_id", "version"})
})
public class PolicyVersion {

    @Id
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(nullable = false)
    private boolean active = false;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "page_count")
    private Integer pageCount;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "ingestion_config", columnDefinition = "jsonb")
    private String ingestionConfig;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    protected PolicyVersion() {}

    public PolicyVersion(UUID id, UUID policyId, int version, String filePath) {
        this.id = id;
        this.policyId = policyId;
        this.version = version;
        this.filePath = filePath;
        this.status = "PENDING";
        this.active = false;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getPolicyId() { return policyId; }
    public int getVersion() { return version; }
    public String getStatus() { return status; }
    public boolean isActive() { return active; }
    public String getFilePath() { return filePath; }
    public Integer getPageCount() { return pageCount; }
    public String getIngestionConfig() { return ingestionConfig; }
    public Instant getUploadedAt() { return uploadedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setActive(boolean active) { this.active = active; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public void setIngestionConfig(String ingestionConfig) { this.ingestionConfig = ingestionConfig; }
}
