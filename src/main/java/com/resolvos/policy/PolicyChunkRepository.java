package com.resolvos.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, UUID> {
    List<PolicyChunk> findByPolicyIdAndPolicyVersionOrderByPageNumberAsc(UUID policyId, int policyVersion);
    int countByPolicyIdAndPolicyVersion(UUID policyId, int policyVersion);
    void deleteByPolicyIdAndPolicyVersion(UUID policyId, int policyVersion);
}
