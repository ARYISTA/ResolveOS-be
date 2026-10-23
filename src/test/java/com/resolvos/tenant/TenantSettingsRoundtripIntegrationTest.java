package com.resolvos.tenant;

import com.resolvos.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TenantSettingsRoundtripIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantSettingsRepository settingsRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        Tenant tenant = new Tenant(tenantId, "roundtrip-" + tenantId, "Roundtrip Co", "BANKING");
        tenantRepository.save(tenant);

        TenantSettings initialSettings = new TenantSettings(
                tenantId,
                new BigDecimal("5000.00"),
                new BigDecimal("25000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("0.700")
        );
        settingsRepository.save(initialSettings);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        settingsRepository.deleteById(tenantId);
        tenantRepository.deleteById(tenantId);
    }

    @Test
    void settingsPutAndGetRoundtripUnderTenantContext() {
        TenantContext.setTenantId(tenantId.toString());

        TenantSettingsRecord current = tenantService.getCurrentSettings();
        assertThat(current.autoApproveLimit()).isEqualByComparingTo("5000.00");
        assertThat(current.hitlTriggerLimit()).isEqualByComparingTo("25000.00");
        assertThat(current.currency()).isEqualTo("INR");

        TenantSettingsRecord.UpdateRequest update = new TenantSettingsRecord.UpdateRequest(
                new BigDecimal("7500.00"),
                new BigDecimal("35000.00"),
                new BigDecimal("150000.00"),
                60,
                14,
                new BigDecimal("0.850")
        );

        TenantSettingsRecord updated = tenantService.updateCurrentSettings(update);
        assertThat(updated.autoApproveLimit()).isEqualByComparingTo("7500.00");
        assertThat(updated.hitlTriggerLimit()).isEqualByComparingTo("35000.00");
        assertThat(updated.maxRefundPerCustomer()).isEqualByComparingTo("150000.00");
        assertThat(updated.refundPeriodDays()).isEqualTo(60);
        assertThat(updated.refundWindowDays()).isEqualTo(14);
        assertThat(updated.minConfidence()).isEqualByComparingTo("0.850");

        // Fetch again to ensure persistence
        TenantSettingsRecord fetchedAgain = tenantService.getCurrentSettings();
        assertThat(fetchedAgain.autoApproveLimit()).isEqualByComparingTo("7500.00");
        assertThat(fetchedAgain.hitlTriggerLimit()).isEqualByComparingTo("35000.00");
        assertThat(fetchedAgain.maxRefundPerCustomer()).isEqualByComparingTo("150000.00");
        assertThat(fetchedAgain.refundPeriodDays()).isEqualTo(60);
        assertThat(fetchedAgain.refundWindowDays()).isEqualTo(14);
        assertThat(fetchedAgain.minConfidence()).isEqualByComparingTo("0.850");
    }
}
