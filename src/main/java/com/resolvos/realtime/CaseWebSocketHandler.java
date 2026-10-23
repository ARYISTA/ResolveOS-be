package com.resolvos.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class CaseWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CaseWebSocketHandler.class);

    private final ConcurrentMap<String, Set<WebSocketSession>> caseSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String caseId = resolveCaseId(session);
        if (caseId != null) {
            caseSessions.computeIfAbsent(caseId, k -> ConcurrentHashMap.newKeySet()).add(session);
            log.debug("WebSocket connected to case channel: {}", caseId);
        } else {
            log.warn("WebSocket connected to case channel without resolvable caseId, closing session");
            try {
                session.close(CloseStatus.BAD_DATA);
            } catch (IOException ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String caseId = resolveCaseId(session);
        if (caseId != null) {
            Set<WebSocketSession> sessions = caseSessions.get(caseId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    caseSessions.remove(caseId);
                }
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String caseId = resolveCaseId(session);
        log.warn("WebSocket transport error for case {}: {}", caseId, exception.getMessage());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {}
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    public void broadcast(String caseId, String payload) {
        if (caseId == null || payload == null) return;
        Set<WebSocketSession> sessions = caseSessions.get(caseId);
        if (sessions == null || sessions.isEmpty()) return;

        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.warn("Failed to send case event to session {}: {}", session.getId(), e.getMessage());
                    try {
                        session.close(CloseStatus.SESSION_NOT_RELIABLE);
                    } catch (IOException ignored) {}
                    sessions.remove(session);
                }
            }
        }
    }

    @Scheduled(fixedDelay = 25000)
    public void sendPings() {
        PingMessage ping = new PingMessage(ByteBuffer.wrap(new byte[]{1}));
        for (Set<WebSocketSession> sessions : caseSessions.values()) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    try {
                        s.sendMessage(ping);
                    } catch (Exception e) {
                        try {
                            s.close();
                        } catch (IOException ignored) {}
                    }
                }
            }
        }
    }

    private String resolveCaseId(WebSocketSession session) {
        Object attr = session.getAttributes().get("caseId");
        if (attr != null) {
            return attr.toString();
        }
        URI uri = session.getUri();
        if (uri != null) {
            String path = uri.getPath();
            if (path != null && path.contains("/ws/cases/")) {
                String sub = path.substring(path.indexOf("/ws/cases/") + "/ws/cases/".length());
                if (sub.contains("/")) {
                    sub = sub.substring(0, sub.indexOf('/'));
                }
                return sub;
            }
        }
        return null;
    }
}
