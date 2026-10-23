package com.resolvos.securitylab;

import com.resolvos.securitylab.dto.RunAttackRequest;
import com.resolvos.securitylab.dto.SecurityAttackResult;
import com.resolvos.securitylab.dto.SecurityLabSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/security-lab")
public class SecurityLabController {

    private final SecurityLabService securityLabService;

    public SecurityLabController(SecurityLabService securityLabService) {
        this.securityLabService = securityLabService;
    }

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public ResponseEntity<SecurityAttackResult> runAttack(
            @RequestBody(required = false) RunAttackRequest request) {
        RunAttackRequest req = request != null ? request : new RunAttackRequest(null, null);
        SecurityAttackResult result = securityLabService.runAttack(req);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public ResponseEntity<SecurityLabSummary> getSummary() {
        SecurityLabSummary summary = securityLabService.getSummary();
        return ResponseEntity.ok(summary);
    }
}
