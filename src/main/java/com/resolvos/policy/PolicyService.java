package com.resolvos.policy;

import com.resolvos.common.error.ApiException;
import com.resolvos.policy.ingestion.IngestionService;
import com.resolvos.policy.retrieval.PolicyReference;
import com.resolvos.policy.retrieval.PolicyRetrievalService;
import com.resolvos.policy.storage.PolicyFileTokenService;
import com.resolvos.policy.storage.StorageService;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantContext;
import com.resolvos.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final PolicyVersionRepository versionRepository;
    private final PolicyChunkRepository chunkRepository;
    private final TenantRepository tenantRepository;
    private final StorageService storageService;
    private final IngestionService ingestionService;
    private final PolicyFileTokenService tokenService;
    private final PolicyRetrievalService retrievalService;

    public record IngestionResponse(UUID policyId, int version, String status) {}
    public record SignedUrlResponse(String url, String expiresAt) {}

    public PolicyService(PolicyRepository policyRepository,
                         PolicyVersionRepository versionRepository,
                         PolicyChunkRepository chunkRepository,
                         TenantRepository tenantRepository,
                         StorageService storageService,
                         IngestionService ingestionService,
                         PolicyFileTokenService tokenService,
                         PolicyRetrievalService retrievalService) {
        this.policyRepository = policyRepository;
        this.versionRepository = versionRepository;
        this.chunkRepository = chunkRepository;
        this.tenantRepository = tenantRepository;
        this.storageService = storageService;
        this.ingestionService = ingestionService;
        this.tokenService = tokenService;
        this.retrievalService = retrievalService;
    }

    public IngestionResponse createPolicy(String name, MultipartFile file) {
        if (name == null || name.isBlank()) {
            throw ApiException.unprocessable("Policy name cannot be blank");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("Policy PDF file is required");
        }

        UUID tenantId = requireTenantId();
        UUID policyId = UUID.randomUUID();
        int version = 1;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        String filePath;
        try {
            filePath = storageService.store(tenantId, policyId, version, file.getOriginalFilename(), bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store uploaded file", e);
        }

        Policy policy = new Policy(policyId, tenantId, name.trim());
        policyRepository.save(policy);

        PolicyVersion pv = new PolicyVersion(UUID.randomUUID(), policyId, version, filePath);
        versionRepository.save(pv);

        // Async ingestion
        ingestionService.ingestAsync(tenantId, policyId, version, name.trim(), bytes);

        return new IngestionResponse(policyId, version, "PENDING");
    }

    public IngestionResponse uploadNewVersion(UUID policyId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("Policy PDF file is required");
        }

        UUID tenantId = requireTenantId();
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("Policy not found: " + policyId));

        if (!policy.getTenantId().equals(tenantId)) {
            throw ApiException.notFound("Policy not found: " + policyId);
        }

        List<PolicyVersion> versions = versionRepository.findByPolicyIdOrderByVersionDesc(policyId);
        int nextVersion = versions.isEmpty() ? 1 : versions.get(0).getVersion() + 1;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        String filePath;
        try {
            filePath = storageService.store(tenantId, policyId, nextVersion, file.getOriginalFilename(), bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store uploaded file", e);
        }

        PolicyVersion pv = new PolicyVersion(UUID.randomUUID(), policyId, nextVersion, filePath);
        versionRepository.save(pv);

        ingestionService.ingestAsync(tenantId, policyId, nextVersion, policy.getName(), bytes);

        return new IngestionResponse(policyId, nextVersion, "PENDING");
    }

    @Transactional(readOnly = true)
    public List<PolicyRecord> listPolicies(String companyId) {
        UUID tenantId = requireTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant not found: " + tenantId));

        return policyRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(p -> p.getTenantId().equals(tenantId))
                .map(p -> toRecord(p, tenant, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyRecord getPolicy(UUID policyId, Integer version) {
        UUID tenantId = requireTenantId();
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("Policy not found: " + policyId));

        if (!policy.getTenantId().equals(tenantId)) {
            throw ApiException.notFound("Policy not found: " + policyId);
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant not found: " + tenantId));

        return toRecord(policy, tenant, version);
    }

    public PolicyRecord activateVersion(UUID policyId, int version) {
        UUID tenantId = requireTenantId();
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("Policy not found: " + policyId));

        if (!policy.getTenantId().equals(tenantId)) {
            throw ApiException.notFound("Policy not found: " + policyId);
        }

        List<PolicyVersion> versions = versionRepository.findByPolicyIdOrderByVersionDesc(policyId);
        boolean found = false;
        for (PolicyVersion v : versions) {
            if (v.getVersion() == version) {
                v.setActive(true);
                found = true;
            } else {
                v.setActive(false);
            }
        }

        if (!found) {
            throw ApiException.notFound("Policy version not found: " + version);
        }

        versionRepository.saveAll(versions);

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Tenant not found: " + tenantId));

        return toRecord(policy, tenant, version);
    }

    public SignedUrlResponse generateSignedUrl(UUID policyId, int version) {
        UUID tenantId = requireTenantId();
        PolicyVersion pv = versionRepository.findByPolicyIdAndVersion(policyId, version)
                .orElseThrow(() -> ApiException.notFound("Policy version not found: " + policyId + " v" + version));

        String token = tokenService.generateToken(tenantId, policyId, version);
        String url = "/api/policies/" + policyId + "/versions/" + version + "/file?t=" + token;
        String expiresAt = Instant.now().plusSeconds(300).toString();

        return new SignedUrlResponse(url, expiresAt);
    }

    @Transactional(readOnly = true)
    public byte[] getFileBytes(UUID policyId, int version, String token) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("Policy not found: " + policyId));

        if (!tokenService.validateToken(token, policy.getTenantId(), policyId, version)) {
            throw ApiException.unauthorized("Invalid or expired file access token");
        }

        PolicyVersion pv = versionRepository.findByPolicyIdAndVersion(policyId, version)
                .orElseThrow(() -> ApiException.notFound("Policy version not found: " + policyId + " v" + version));

        try {
            return storageService.load(pv.getFilePath());
        } catch (IOException e) {
            throw ApiException.notFound("File not found on storage: " + pv.getFilePath());
        }
    }

    @Transactional(readOnly = true)
    public List<PolicyReference> searchPolicies(String query, Integer topK) {
        UUID tenantId = requireTenantId();
        return retrievalService.search(tenantId, query, topK);
    }

    private PolicyRecord toRecord(Policy policy, Tenant tenant, Integer targetVersion) {
        List<PolicyVersion> versions = versionRepository.findByPolicyIdOrderByVersionDesc(policy.getId());

        int currentVersion = versions.stream()
                .filter(PolicyVersion::isActive)
                .map(PolicyVersion::getVersion)
                .findFirst()
                .orElse(versions.isEmpty() ? 1 : versions.get(0).getVersion());

        int versionToLoadChunks = (targetVersion != null) ? targetVersion : currentVersion;

        List<PolicyRecord.VersionSummary> versionSummaries = versions.stream()
                .map(v -> {
                    int chunkCount = chunkRepository.countByPolicyIdAndPolicyVersion(policy.getId(), v.getVersion());
                    return new PolicyRecord.VersionSummary(
                            v.getVersion(),
                            v.getUploadedAt().toString(),
                            chunkCount,
                            v.getFilePath() != null ? v.getFilePath() : "",
                            v.getPageCount() != null ? v.getPageCount() * 15L : 50L,
                            v.isActive()
                    );
                })
                .toList();

        List<PolicyChunk> chunks = chunkRepository.findByPolicyIdAndPolicyVersionOrderByPageNumberAsc(policy.getId(), versionToLoadChunks);
        List<PolicyRecord.ChunkSummary> chunkSummaries = chunks.stream()
                .map(c -> new PolicyRecord.ChunkSummary(
                        c.getId().toString(),
                        c.getSection(),
                        c.getPageNumber(),
                        c.getTokenCount(),
                        c.getContent()
                ))
                .toList();

        return new PolicyRecord(
                policy.getId().toString(),
                policy.getName(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getDomain().toLowerCase(),
                currentVersion,
                versionSummaries,
                chunkSummaries,
                policy.getCreatedAt().toString()
        );
    }

    private UUID requireTenantId() {
        String tenantIdStr = TenantContext.getTenantId();
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw ApiException.unauthorized("No tenant context active");
        }
        return UUID.fromString(tenantIdStr);
    }
}
