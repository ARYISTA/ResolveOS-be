package com.resolvos.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class RealtimeBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcaster.class);

    private final TenantWebSocketHandler tenantWebSocketHandler;
    private final CaseWebSocketHandler caseWebSocketHandler;
    private final ObjectMapper objectMapper;

    public RealtimeBroadcaster(TenantWebSocketHandler tenantWebSocketHandler,
                               CaseWebSocketHandler caseWebSocketHandler,
                               ObjectMapper objectMapper) {
        this.tenantWebSocketHandler = tenantWebSocketHandler;
        this.caseWebSocketHandler = caseWebSocketHandler;
        this.objectMapper = objectMapper;
    }

    public void broadcastToTenant(UUID tenantId, Object event) {
        if (tenantId == null || event == null) return;
        executeAfterCommit(() -> {
            try {
                String json = objectMapper.writeValueAsString(event);
                tenantWebSocketHandler.broadcast(tenantId, json);
            } catch (Exception e) {
                log.warn("Failed to serialize WebSocket event for tenant {}: {}", tenantId, e.getMessage());
            }
        });
    }

    public void broadcastToCase(String caseId, Object event) {
        if (caseId == null || event == null) return;
        executeAfterCommit(() -> {
            try {
                String json = objectMapper.writeValueAsString(event);
                caseWebSocketHandler.broadcast(caseId, json);
            } catch (Exception e) {
                log.warn("Failed to serialize WebSocket event for case {}: {}", caseId, e.getMessage());
            }
        });
    }

    public void broadcastToCaseAndTenant(String caseId, UUID tenantId, Object event) {
        if (event == null) return;
        executeAfterCommit(() -> {
            try {
                String json = objectMapper.writeValueAsString(event);
                if (caseId != null) {
                    caseWebSocketHandler.broadcast(caseId, json);
                }
                if (tenantId != null) {
                    tenantWebSocketHandler.broadcast(tenantId, json);
                }
            } catch (Exception e) {
                log.warn("Failed to serialize WebSocket event: {}", e.getMessage());
            }
        });
    }

    private void executeAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
