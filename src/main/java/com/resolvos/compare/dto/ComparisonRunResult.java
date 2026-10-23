package com.resolvos.compare.dto;

import java.math.BigDecimal;
import java.util.List;

public record ComparisonRunResult(
        String presetId,
        String presetLabel,
        String groundTruthDecision,
        BigDecimal groundTruthAmount,
        List<ApproachResult> results,
        double aggregateAccuracyPercent
) {
    public record ApproachResult(
            String approach,
            String decision,
            BigDecimal amount,
            boolean isCorrect,
            boolean correct,
            int latencyMs,
            BigDecimal estimatedCostUsd,
            boolean citationPresent,
            boolean unsafeAction,
            String notes
    ) {}
}
