package com.resolvos.compare.dto;

public record RunComparisonRequest(
        String presetId,
        String caseId
) {
    public String effectivePresetId() {
        if (presetId != null && !presetId.isBlank()) return presetId.trim();
        if (caseId != null && !caseId.isBlank()) return caseId.trim();
        return "bk-safe-upi";
    }
}
