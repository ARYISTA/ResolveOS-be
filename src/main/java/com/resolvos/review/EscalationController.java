package com.resolvos.review;

import com.resolvos.common.error.ApiException;
import com.resolvos.tenant.TenantContext;
import com.resolvos.ticket.CaseDetailResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/escalations")
public class EscalationController {

    private final ReviewService reviewService;

    public EscalationController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'REVIEWER')")
    public ResponseEntity<List<CaseDetailResponse>> getOpenEscalations() {
        UUID tenantId = requireTenantId();
        List<CaseDetailResponse> list = reviewService.getOpenEscalations(tenantId);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'REVIEWER')")
    public ResponseEntity<CaseDetailResponse> getEscalation(@PathVariable UUID id) {
        UUID tenantId = requireTenantId();
        CaseDetailResponse response = reviewService.getEscalation(id, tenantId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'REVIEWER')")
    public ResponseEntity<CaseDetailResponse> approveEscalation(
            @PathVariable UUID id,
            @RequestBody(required = false) ApproveEscalationRequest request,
            Authentication auth) {
        UUID tenantId = requireTenantId();
        UUID reviewerId = extractReviewerId(auth);
        String reviewerName = extractReviewerName(auth);

        CaseDetailResponse response = reviewService.approve(id, tenantId, request, reviewerId, reviewerName);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'REVIEWER')")
    public ResponseEntity<CaseDetailResponse> rejectEscalation(
            @PathVariable UUID id,
            @RequestBody(required = false) RejectEscalationRequest request,
            Authentication auth) {
        UUID tenantId = requireTenantId();
        UUID reviewerId = extractReviewerId(auth);
        String reviewerName = extractReviewerName(auth);

        CaseDetailResponse response = reviewService.reject(id, tenantId, request, reviewerId, reviewerName);
        return ResponseEntity.ok(response);
    }

    private UUID requireTenantId() {
        String tenantStr = TenantContext.getTenantId();
        if (tenantStr == null || tenantStr.isBlank()) {
            throw ApiException.unauthorized("No tenant context available");
        }
        return UUID.fromString(tenantStr);
    }

    private UUID extractReviewerId(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof Jwt jwt) {
            String userIdStr = jwt.getClaimAsString("userId");
            if (userIdStr != null && !userIdStr.isBlank()) {
                try {
                    return UUID.fromString(userIdStr);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    private String extractReviewerName(Authentication auth) {
        if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "Compliance Reviewer";
    }
}
