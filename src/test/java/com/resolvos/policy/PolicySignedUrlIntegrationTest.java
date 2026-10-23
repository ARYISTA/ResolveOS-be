package com.resolvos.policy;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.common.error.ApiException;
import com.resolvos.policy.storage.StorageService;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantContext;
import com.resolvos.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PolicySignedUrlIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private PolicyVersionRepository versionRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private StorageService storageService;

    private UUID tenantId;
    private UUID policyId;
    private String storedPath;

    @BeforeEach
    void setUp() throws IOException {
        tenantId = UUID.randomUUID();
        policyId = UUID.randomUUID();

        tenantRepository.save(new Tenant(tenantId, "signed-url-" + tenantId, "Signed URL Co", "BANKING"));
        policyRepository.save(new Policy(policyId, tenantId, "Test Signed Policy"));

        byte[] fakePdf = "%PDF-1.4 test document content".getBytes(StandardCharsets.UTF_8);
        storedPath = storageService.store(tenantId, policyId, 1, "test.pdf", fakePdf);

        PolicyVersion pv = new PolicyVersion(UUID.randomUUID(), policyId, 1, storedPath);
        pv.setActive(true);
        versionRepository.save(pv);
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        storageService.delete(storedPath);
        jdbc.update("DELETE FROM policy_chunks WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policy_versions WHERE policy_id = ?", policyId);
        jdbc.update("DELETE FROM policies WHERE id = ?", policyId);
        tenantRepository.deleteById(tenantId);
    }

    @Test
    void signedUrlGeneratesValidTokenAndAllowsFileStream() {
        TenantContext.setTenantId(tenantId.toString());

        PolicyService.SignedUrlResponse resp = policyService.generateSignedUrl(policyId, 1);
        assertThat(resp.url()).contains("/api/policies/" + policyId + "/versions/1/file?t=");
        assertThat(resp.expiresAt()).isNotBlank();

        // Extract token
        String token = resp.url().substring(resp.url().indexOf("?t=") + 3);

        // Fetch without TenantContext (simulating iframe request)
        TenantContext.clear();
        byte[] bytes = policyService.getFileBytes(policyId, 1, token);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("%PDF-1.4 test document content");

        // Tampered token fails
        assertThatThrownBy(() -> policyService.getFileBytes(policyId, 1, token + "tampered"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid or expired file access token");
    }
}
