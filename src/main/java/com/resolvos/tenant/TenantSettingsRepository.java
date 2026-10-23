package com.resolvos.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TenantSettingsRepository extends JpaRepository<TenantSettings, UUID> {
}
