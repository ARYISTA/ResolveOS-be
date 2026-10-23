package com.resolvos.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditLog logEvent(UUID tenantId, UUID caseId, String actor, String actorType,
                             String action, String oldState, String newState,
                             String traceId, Map<String, Object> metadata) {
        String metaJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metaJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("Failed to serialize audit metadata: {}", e.getMessage());
            }
        }

        AuditLog entry = new AuditLog(
                UUID.randomUUID(),
                tenantId,
                caseId,
                actor != null ? actor : "System",
                actorType != null ? actorType : "SYSTEM",
                action,
                oldState,
                newState,
                traceId,
                metaJson
        );

        return auditLogRepository.save(entry);
    }
}
