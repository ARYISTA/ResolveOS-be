package com.resolvos.tenant;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public List<TenantRecord> listTenants() {
        return tenantService.findAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public TenantRecord createTenant(@Valid @RequestBody TenantRecord.CreateRequest req) {
        return tenantService.create(req);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public TenantRecord getTenantById(@PathVariable UUID id) {
        return tenantService.findById(id);
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public TenantRecord getCurrentTenant() {
        return tenantService.getCurrentTenant();
    }

    @GetMapping("/current/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public TenantSettingsRecord getCurrentSettings() {
        return tenantService.getCurrentSettings();
    }

    @PutMapping("/current/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public TenantSettingsRecord updateCurrentSettings(@Valid @RequestBody TenantSettingsRecord.UpdateRequest req) {
        return tenantService.updateCurrentSettings(req);
    }

    @GetMapping("/{id}/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public TenantSettingsRecord getSettings(@PathVariable UUID id) {
        return tenantService.getSettings(id);
    }

    @PutMapping("/{id}/settings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public TenantSettingsRecord updateSettings(
            @PathVariable UUID id,
            @Valid @RequestBody TenantSettingsRecord.UpdateRequest req) {
        return tenantService.updateSettings(id, req);
    }
}
