package com.resolvos.compare;

import com.resolvos.compare.dto.ComparisonRunResult;
import com.resolvos.compare.dto.RunComparisonRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compare")
public class CompareController {

    private final CompareService compareService;

    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public ResponseEntity<ComparisonRunResult> runComparison(
            @RequestBody(required = false) RunComparisonRequest request) {
        ComparisonRunResult result = compareService.runComparison(request);
        return ResponseEntity.ok(result);
    }
}
