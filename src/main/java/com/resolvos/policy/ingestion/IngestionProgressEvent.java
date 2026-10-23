package com.resolvos.policy.ingestion;

import java.util.UUID;

public record IngestionProgressEvent(
        String type,
        UUID policyId,
        int version,
        String stage,
        int percent,
        String error
) {
    public IngestionProgressEvent(UUID policyId, int version, String stage, int percent) {
        this("INGESTION_PROGRESS", policyId, version, stage, percent, null);
    }

    public static IngestionProgressEvent failed(UUID policyId, int version, String error) {
        return new IngestionProgressEvent("INGESTION_PROGRESS", policyId, version, "FAILED", 0, error);
    }
}
