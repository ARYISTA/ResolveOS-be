package com.resolvos.ticket;

import com.resolvos.engine.EngineGateway;
import com.resolvos.engine.ResolveCaseRequest;
import com.resolvos.engine.ResumeCaseRequest;
import com.resolvos.outbox.OutboxEvent;
import com.resolvos.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class ResolutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ResolutionOrchestrator.class);

    private final EngineGateway engineGateway;
    private final CaseEventHandler caseEventHandler;
    private final ObjectMapper objectMapper;

    public ResolutionOrchestrator(EngineGateway engineGateway,
                                  CaseEventHandler caseEventHandler,
                                  ObjectMapper objectMapper) {
        this.engineGateway = engineGateway;
        this.caseEventHandler = caseEventHandler;
        this.objectMapper = objectMapper;
    }

    public void dispatch(OutboxEvent event) {
        UUID tenantId = event.getTenantId();
        TenantContext.setTenantId(tenantId.toString());
        org.slf4j.MDC.put("tenantId", tenantId.toString());
        log.info("ResolutionOrchestrator dispatching event {} of type {} for tenant {}",
                event.getId(), event.getType(), tenantId);

        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());

            if ("RESOLVE_REQUESTED".equals(event.getType())) {
                UUID caseId = UUID.fromString(payload.get("caseId").asText());
                UUID customerId = UUID.fromString(payload.get("customerId").asText());
                UUID transactionId = UUID.fromString(payload.get("transactionId").asText());
                String ticketText = payload.hasNonNull("message") ? payload.get("message").asText() : "";
                BigDecimal requestedAmount = new BigDecimal(payload.get("requestedAmount").asText());
                String traceId = payload.hasNonNull("traceId") ? payload.get("traceId").asText() : "trc-" + caseId.toString().substring(0, 8);
                org.slf4j.MDC.put("traceId", traceId);

                ResolveCaseRequest request = new ResolveCaseRequest(
                        tenantId, caseId, customerId, transactionId, ticketText, requestedAmount, traceId
                );
                engineGateway.resolve(request, caseEventHandler);
            } else if ("RESUME_REQUESTED".equals(event.getType())) {
                UUID caseId = UUID.fromString(payload.get("caseId").asText());
                String traceId = payload.hasNonNull("traceId") ? payload.get("traceId").asText() : "";
                if (!traceId.isBlank()) org.slf4j.MDC.put("traceId", traceId);
                String reviewer = payload.hasNonNull("reviewer") ? payload.get("reviewer").asText() : "Reviewer";
                String decision = payload.hasNonNull("decision") ? payload.get("decision").asText() : "APPROVED";
                BigDecimal approvedAmount = payload.hasNonNull("approvedAmount") ? new BigDecimal(payload.get("approvedAmount").asText()) : null;
                String comment = payload.hasNonNull("comment") ? payload.get("comment").asText() : null;

                ResumeCaseRequest request = new ResumeCaseRequest(
                        tenantId, caseId, traceId, reviewer, decision, approvedAmount, comment
                );
                engineGateway.resume(request, caseEventHandler);
            } else {
                log.warn("Unknown outbox event type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Error processing outbox event {}: {}", event.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to dispatch outbox event", e);
        } finally {
            TenantContext.clear();
            org.slf4j.MDC.remove("traceId");
            org.slf4j.MDC.remove("tenantId");
        }
    }
}
