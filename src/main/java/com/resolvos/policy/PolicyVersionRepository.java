package com.resolvos.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {
    List<PolicyVersion> findByPolicyIdOrderByVersionDesc(UUID policyId);
    Optional<PolicyVersion> findByPolicyIdAndVersion(UUID policyId, int version);
    Optional<PolicyVersion> findByPolicyIdAndActiveTrue(UUID policyId);
    int countByPolicyId(UUID policyId);
}
