package com.resolvos.tenant;

import com.resolvos.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;

    public TenantService(TenantRepository tenantRepository, TenantSettingsRepository settingsRepository) {
        this.tenantRepository = tenantRepository;
        this.settingsRepository = settingsRepository;
    }

    @Transactional(readOnly = true)
    public List<TenantRecord> findAll() {
        return tenantRepository.findAll().stream()
                .map(TenantRecord::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantRecord findById(UUID id) {
        return tenantRepository.findById(id)
                .map(TenantRecord::from)
                .orElseThrow(() -> ApiException.notFound("Tenant not found: " + id));
    }

    public TenantRecord create(TenantRecord.CreateRequest req) {
        if (tenantRepository.existsBySlug(req.slug())) {
            throw ApiException.conflict("Tenant slug already exists: " + req.slug());
        }
        UUID id = UUID.randomUUID();
        Tenant tenant = new Tenant(id, req.slug(), req.name(), req.domain());
        Tenant saved = tenantRepository.save(tenant);

        // Create default settings for new tenant
        TenantSettings defaultSettings = new TenantSettings(
                id,
                new BigDecimal("5000.00"),
                new BigDecimal("25000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("0.700")
        );
        settingsRepository.save(defaultSettings);

        return TenantRecord.from(saved);
    }

    @Transactional(readOnly = true)
    public TenantSettingsRecord getSettings(UUID tenantId) {
        return settingsRepository.findById(tenantId)
                .map(TenantSettingsRecord::from)
                .orElseThrow(() -> ApiException.notFound("Settings not found for tenant: " + tenantId));
    }

    public TenantSettingsRecord updateSettings(UUID tenantId, TenantSettingsRecord.UpdateRequest req) {
        if (req.autoApproveLimit().compareTo(req.hitlTriggerLimit()) > 0) {
            throw ApiException.unprocessable("Auto-approve limit cannot exceed HITL trigger limit");
        }

        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("Settings not found for tenant: " + tenantId));

        settings.setAutoApproveLimit(req.autoApproveLimit());
        settings.setHitlTriggerLimit(req.hitlTriggerLimit());
        settings.setMaxRefundPerCustomer(req.maxRefundPerCustomer());
        settings.setRefundPeriodDays(req.refundPeriodDays());
        settings.setRefundWindowDays(req.refundWindowDays());
        settings.setMinConfidence(req.minConfidence());

        return TenantSettingsRecord.from(settingsRepository.save(settings));
    }

    @Transactional(readOnly = true)
    public TenantRecord getCurrentTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw ApiException.unauthorized("No tenant context found");
        }
        return findById(UUID.fromString(tenantId));
    }

    @Transactional(readOnly = true)
    public TenantSettingsRecord getCurrentSettings() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw ApiException.unauthorized("No tenant context found");
        }
        return getSettings(UUID.fromString(tenantId));
    }

    public TenantSettingsRecord updateCurrentSettings(TenantSettingsRecord.UpdateRequest req) {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw ApiException.unauthorized("No tenant context found");
        }
        return updateSettings(UUID.fromString(tenantId), req);
    }
}
