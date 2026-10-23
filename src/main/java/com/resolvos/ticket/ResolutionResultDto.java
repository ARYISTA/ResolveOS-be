package com.resolvos.ticket;

import com.resolvos.enforcement.EnforcementCheck;
import com.resolvos.policy.retrieval.PolicyReference;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ResolutionResultDto(
        UUID caseId,
        String decision,
        BigDecimal recommendedAmount,
        BigDecimal confidence,
        String riskLevel,
        List<PolicyReference> policyReferences,
        boolean requiresHumanReview,
        String reasoningSummary,
        List<EnforcementCheck> enforcement,
        String verdict,
        String traceId
) {}
