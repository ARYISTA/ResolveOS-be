package com.resolvos.policy;

import com.resolvos.policy.retrieval.PolicyReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public PolicyService.IngestionResponse createPolicy(
            @RequestParam("name") String name,
            @RequestParam("file") MultipartFile file) {
        return policyService.createPolicy(name, file);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public List<PolicyRecord> listPolicies(@RequestParam(name = "companyId", required = false) String companyId) {
        return policyService.listPolicies(companyId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public PolicyRecord getPolicy(
            @PathVariable UUID id,
            @RequestParam(name = "version", required = false) Integer version) {
        return policyService.getPolicy(id, version);
    }

    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public PolicyService.IngestionResponse uploadNewVersion(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        return policyService.uploadNewVersion(id, file);
    }

    @PostMapping("/{id}/versions/{v}/activate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public PolicyRecord activateVersion(
            @PathVariable UUID id,
            @PathVariable int v) {
        return policyService.activateVersion(id, v);
    }

    @PostMapping("/{id}/versions/{v}/file-url")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public PolicyService.SignedUrlResponse getSignedFileUrl(
            @PathVariable UUID id,
            @PathVariable int v) {
        return policyService.generateSignedUrl(id, v);
    }

    @GetMapping("/{id}/versions/{v}/file")
    public ResponseEntity<byte[]> getFile(
            @PathVariable UUID id,
            @PathVariable int v,
            @RequestParam("t") String token) {
        byte[] pdfBytes = policyService.getFileBytes(id, v, token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"policy-v" + v + ".pdf\"")
                .body(pdfBytes);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public List<PolicyReference> searchPolicies(
            @RequestParam("q") String query,
            @RequestParam(name = "topK", required = false) Integer topK) {
        return policyService.searchPolicies(query, topK);
    }
}
