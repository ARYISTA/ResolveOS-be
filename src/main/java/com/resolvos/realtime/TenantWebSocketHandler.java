package com.resolvos.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TenantWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantWebSocketHandler.class);

    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> tenantSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String tenantIdStr = (String) session.getAttributes().get("tenantId");
        if (tenantIdStr != null) {
            try {
                UUID tenantId = UUID.fromString(tenantIdStr);
                tenantSessions.computeIfAbsent(tenantId, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                        .add(session);
                log.debug("WebSocket connection opened for tenant {}", tenantId);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String tenantIdStr = (String) session.getAttributes().get("tenantId");
        if (tenantIdStr != null) {
            try {
                UUID tenantId = UUID.fromString(tenantIdStr);
                Set<WebSocketSession> sessions = tenantSessions.get(tenantId);
                if (sessions != null) {
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        tenantSessions.remove(tenantId);
                    }
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void broadcast(UUID tenantId, String messageJson) {
        Set<WebSocketSession> sessions = tenantSessions.get(tenantId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        TextMessage textMessage = new TextMessage(messageJson);
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(textMessage);
                } catch (IOException e) {
                    log.warn("Failed to send WebSocket message to session {}, closing", s.getId());
                    try {
                        s.close(CloseStatus.SERVER_ERROR);
                    } catch (IOException ignored) {}
                    sessions.remove(s);
                }
            }
        }
    }
}
