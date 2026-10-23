package com.resolvos.securitylab.dto;

import java.util.List;

public record SecurityLabSummary(
        int totalRun,
        int totalBlocked,
        int totalLeaked,
        List<SecurityLayerSummary> layerSummaries
) {
    public record SecurityLayerSummary(
            String layer,
            int attacksRun,
            int blocked,
            int leaked
    ) {}
}
