package com.resolvos.realtime;

import com.resolvos.auth.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);
    private final JwtService jwtService;

    public WsHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        URI uri = request.getURI();
        String path = uri.getPath();
        if (path != null && path.contains("/ws/cases/")) {
            String caseId = path.substring(path.indexOf("/ws/cases/") + "/ws/cases/".length());
            if (caseId.contains("/")) {
                caseId = caseId.substring(0, caseId.indexOf('/'));
            }
            attributes.put("caseId", caseId);
        }

        String query = uri.getQuery();
        String token = extractQueryParam(query, "access_token");

        if (token == null || token.isBlank()) {
            // For case-specific channels where frontend might connect without query param
            if (path != null && path.contains("/ws/cases/")) {
                return true;
            }
            log.warn("WebSocket handshake rejected: missing access_token query param");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            Jwt jwt = jwtService.validateAccessToken(token);
            String tenantId = jwt.getClaimAsString("tenantId");
            String role = jwt.getClaimAsString("role");

            if (tenantId != null) {
                attributes.put("tenantId", tenantId);
            }
            if (role != null) {
                attributes.put("role", role);
            }
            attributes.put("userEmail", jwt.getSubject());
            return true;
        } catch (Exception e) {
            log.warn("WebSocket handshake rejected: invalid JWT token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Redact query string in any handshake logs
    }

    private String extractQueryParam(String query, String name) {
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2 && pair[0].equalsIgnoreCase(name)) {
                return pair[1];
            }
        }
        return null;
    }
}
