package com.resolvos.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {
    List<Policy> findAllByOrderByCreatedAtDesc();
    Optional<Policy> findByIdAndTenantId(UUID id, UUID tenantId);
}
