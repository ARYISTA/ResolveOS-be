package com.resolvos.realtime;

import com.resolvos.auth.JwtService;
import com.resolvos.auth.Role;
import com.resolvos.auth.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WsHandshakeInterceptorTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    private WsHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WsHandshakeInterceptor(jwtService);
    }

    @Test
    void validTokenAcceptsHandshakeAndSetsAttributes() {
        UUID tenantId = UUID.randomUUID();
        String validToken = "valid.jwt.token";

        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/ws/tenant?access_token=" + validToken));

        Jwt mockJwt = new Jwt(
                validToken,
                Instant.now(),
                Instant.now().plusSeconds(900),
                Map.of("alg", "HS256"),
                Map.of(
                        "sub", "user@meridian.com",
                        "tenantId", tenantId.toString(),
                        "role", "SUPPORT_AGENT"
                )
        );
        when(jwtService.validateAccessToken(validToken)).thenReturn(mockJwt);

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, null, attributes);

        assertThat(result).isTrue();
        assertThat(attributes.get("tenantId")).isEqualTo(tenantId.toString());
        assertThat(attributes.get("role")).isEqualTo("SUPPORT_AGENT");
        assertThat(attributes.get("userEmail")).isEqualTo("user@meridian.com");
    }

    @Test
    void missingTokenRejectsHandshakeWith401() {
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/ws/tenant"));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, null, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidTokenRejectsHandshakeWith401() {
        String badToken = "bad.token";
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/ws/tenant?access_token=" + badToken));
        when(jwtService.validateAccessToken(badToken)).thenThrow(new RuntimeException("Signature invalid"));

        Map<String, Object> attributes = new HashMap<>();
        boolean result = interceptor.beforeHandshake(request, response, null, attributes);

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }
}
