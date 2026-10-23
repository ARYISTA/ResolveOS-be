package com.resolvos.auth;

import com.resolvos.common.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthenticationManager authManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository users;

    @Mock
    private RefreshTokenRepository refreshTokens;

    private JwtProperties jwtProperties;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties("01234567890123456789012345678901", 15, 7);
        controller = new AuthController(authManager, jwtService, users, refreshTokens, jwtProperties);
    }

    @Test
    void loginSuccessReturnsTokenAndSetsCookie() {
        String email = "admin@resolvos.internal";
        String password = "securePassword123!";
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        User user = new User(userId, tenantId, email, "hashed_pw", Role.TENANT_ADMIN, "Admin User");

        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("mock-access-token");
        when(jwtService.generateRawRefreshToken()).thenReturn(UUID.randomUUID().toString());

        MockHttpServletResponse response = new MockHttpServletResponse();
        var req = new AuthController.LoginRequest(email, password);

        ResponseEntity<AuthController.TokenResponse> result = controller.login(req, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().token()).isEqualTo("mock-access-token");

        Cookie refreshCookie = response.getCookie("refresh_token");
        assertThat(refreshCookie).isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();
        assertThat(refreshCookie.getPath()).isEqualTo("/api/auth");

        verify(refreshTokens).save(any(RefreshToken.class));
    }

    @Test
    void loginFailureReturns401() {
        when(authManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        var req = new AuthController.LoginRequest("unknown@test.com", "wrong");

        ResponseEntity<AuthController.TokenResponse> result = controller.login(req, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void refreshRotatesTokenSuccessfully() {
        String rawRefresh = UUID.randomUUID().toString();
        String hash = AuthController.hashToken(rawRefresh);
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        User user = new User(userId, tenantId, "admin@resolvos.internal", "pw", Role.TENANT_ADMIN, "Admin User");

        RefreshToken storedToken = new RefreshToken(userId, hash, Instant.now().plus(7, ChronoUnit.DAYS));
        when(refreshTokens.findByTokenHash(hash)).thenReturn(Optional.of(storedToken));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("new-access-token");
        when(jwtService.generateRawRefreshToken()).thenReturn(UUID.randomUUID().toString());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", rawRefresh));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AuthController.TokenResponse> result = controller.refresh(request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().token()).isEqualTo("new-access-token");
        assertThat(storedToken.isRevoked()).isTrue();

        Cookie newCookie = response.getCookie("refresh_token");
        assertThat(newCookie).isNotNull();
        assertThat(newCookie.getValue()).isNotEqualTo(rawRefresh);
    }

    @Test
    void refreshWithoutCookieReturns401() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AuthController.TokenResponse> result = controller.refresh(request, response);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshWithRevokedTokenReturns401() {
        String rawRefresh = UUID.randomUUID().toString();
        String hash = AuthController.hashToken(rawRefresh);
        UUID userId = UUID.randomUUID();

        RefreshToken revoked = new RefreshToken(userId, hash, Instant.now().plus(7, ChronoUnit.DAYS));
        revoked.revoke();
        when(refreshTokens.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refresh_token", rawRefresh));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AuthController.TokenResponse> result = controller.refresh(request, response);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
