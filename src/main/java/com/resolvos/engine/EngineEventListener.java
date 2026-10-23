package com.resolvos.engine;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface EngineEventListener {
    void onNodeStarted(UUID caseId, String node);
    void onNodeCompleted(UUID caseId, String node, long durationMs, Map<String, Object> output);
    void onNodeFailed(UUID caseId, String node, String error);
    void onHitlRequired(UUID caseId, String reason, String riskLevel);
    void onHitlResolved(UUID caseId, String decision, String reviewer, BigDecimal modifiedAmount, String reason);
    void onCaseCompleted(UUID caseId, String verdict, String refundRef, BigDecimal approvedAmount);
    void onAuditEvent(UUID caseId, String action, String actor, String oldState, String newState);
}
