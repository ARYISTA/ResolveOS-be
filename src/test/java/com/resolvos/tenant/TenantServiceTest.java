package com.resolvos.tenant;

import com.resolvos.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private TenantSettingsRepository settingsRepository;

    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(tenantRepository, settingsRepository);
    }

    @Test
    void findAllReturnsMappedRecords() {
        Tenant t1 = new Tenant(UUID.randomUUID(), "tenant-1", "Tenant 1", "BANKING");
        Tenant t2 = new Tenant(UUID.randomUUID(), "tenant-2", "Tenant 2", "ECOMMERCE");

        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));

        List<TenantRecord> result = tenantService.findAll();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).slug()).isEqualTo("tenant-1");
        assertThat(result.get(1).slug()).isEqualTo("tenant-2");
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(tenantRepository.existsBySlug("existing-slug")).thenReturn(true);

        TenantRecord.CreateRequest req = new TenantRecord.CreateRequest("existing-slug", "Name", "BANKING");
        assertThatThrownBy(() -> tenantService.create(req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createSavesTenantAndDefaultSettings() {
        when(tenantRepository.existsBySlug("new-slug")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantRecord.CreateRequest req = new TenantRecord.CreateRequest("new-slug", "New Company", "TRAVEL");
        TenantRecord created = tenantService.create(req);

        assertThat(created.slug()).isEqualTo("new-slug");
        assertThat(created.name()).isEqualTo("New Company");
        verify(tenantRepository).save(any(Tenant.class));
        verify(settingsRepository).save(any(TenantSettings.class));
    }

    @Test
    void updateSettingsValidatesAutoApproveLimitNotExceedingHitl() {
        UUID tenantId = UUID.randomUUID();
        TenantSettingsRecord.UpdateRequest invalidReq = new TenantSettingsRecord.UpdateRequest(
                new BigDecimal("30000.00"), // auto approve > hitl trigger
                new BigDecimal("20000.00"),
                new BigDecimal("100000.00"),
                30,
                30,
                new BigDecimal("0.700")
        );

        assertThatThrownBy(() -> tenantService.updateSettings(tenantId, invalidReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Auto-approve limit cannot exceed HITL trigger limit");
    }

    @Test
    void updateSettingsUpdatesExistingSettings() {
        UUID tenantId = UUID.randomUUID();
        TenantSettings existing = new TenantSettings(
                tenantId,
                new BigDecimal("5000.00"),
                new BigDecimal("25000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("0.700")
        );

        when(settingsRepository.findById(tenantId)).thenReturn(Optional.of(existing));
        when(settingsRepository.save(any(TenantSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantSettingsRecord.UpdateRequest validReq = new TenantSettingsRecord.UpdateRequest(
                new BigDecimal("6000.00"),
                new BigDecimal("30000.00"),
                new BigDecimal("120000.00"),
                45,
                15,
                new BigDecimal("0.850")
        );

        TenantSettingsRecord updated = tenantService.updateSettings(tenantId, validReq);

        assertThat(updated.autoApproveLimit()).isEqualByComparingTo("6000.00");
        assertThat(updated.hitlTriggerLimit()).isEqualByComparingTo("30000.00");
        assertThat(updated.refundPeriodDays()).isEqualTo(45);
        assertThat(updated.refundWindowDays()).isEqualTo(15);
        assertThat(updated.minConfidence()).isEqualByComparingTo("0.850");
    }
}
