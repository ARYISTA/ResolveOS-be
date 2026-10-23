package com.resolvos.metrics.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardMetricsResponse(
        long totalCases,
        long autoResolved,
        long escalated,
        long rejected,
        BigDecimal totalRefunded,
        long avgResolutionTimeMs,
        long avgResolutionMs,
        List<OutcomeCountDto> casesByOutcome,
        List<DateCountDto> casesOverTime,
        List<StageLatencyDto> stageLatencies,
        List<StageLatencyDto> avgLatencyByStage
) {
    public record OutcomeCountDto(
            String outcome,
            String label,
            long count,
            int percentage,
            String color
    ) {}

    public record DateCountDto(
            String date,
            long count
    ) {}

    public record StageLatencyDto(
            String stage,
            String node,
            String label,
            long latencyMs,
            long avgMs
    ) {}
}
