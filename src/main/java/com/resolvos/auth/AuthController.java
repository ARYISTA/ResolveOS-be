package com.resolvos.auth;

import com.resolvos.common.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * POST /api/auth/login  — returns access token in body, refresh token as httpOnly cookie
 * POST /api/auth/refresh — rotates refresh token
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Cookie name and path
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/auth";

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final JwtProperties jwtProps;

    public AuthController(AuthenticationManager authManager,
                          JwtService jwtService,
                          UserRepository users,
                          RefreshTokenRepository refreshTokens,
                          JwtProperties jwtProps) {
        this.authManager = authManager;
        this.jwtService = jwtService;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.jwtProps = jwtProps;
    }

    // ─── DTOs ─────────────────────────────────────────────────────────────────

    public record LoginRequest(
            @NotBlank @Email String username,
            @NotBlank String password
    ) {}

    public record TokenResponse(String token) {}

    // ─── Endpoints ────────────────────────────────────────────────────────────

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req,
                                                HttpServletResponse response) {
        try {
            authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = users.findByEmail(req.username())
                .orElseThrow(() -> new IllegalStateException("User disappeared after auth"));

        String accessToken = jwtService.generateAccessToken(user);
        String rawRefresh = jwtService.generateRawRefreshToken();
        storeRefreshToken(user, rawRefresh);
        setRefreshCookie(response, rawRefresh);

        return ResponseEntity.ok(new TokenResponse(accessToken));
    }

    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<TokenResponse> refresh(HttpServletRequest request,
                                                  HttpServletResponse response) {
        String rawRefresh = extractRefreshCookie(request);
        if (rawRefresh == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String hash = hashToken(rawRefresh);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElse(null);

        if (stored == null || stored.isRevoked() || stored.isExpired()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Rotate: revoke old, issue new
        stored.revoke();
        refreshTokens.save(stored);

        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for refresh token"));

        String accessToken = jwtService.generateAccessToken(user);
        String newRawRefresh = jwtService.generateRawRefreshToken();
        storeRefreshToken(user, newRawRefresh);
        setRefreshCookie(response, newRawRefresh);

        return ResponseEntity.ok(new TokenResponse(accessToken));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void storeRefreshToken(User user, String rawToken) {
        String hash = hashToken(rawToken);
        Instant expiry = Instant.now().plus(jwtProps.refreshTokenTtlDays(), ChronoUnit.DAYS);
        refreshTokens.save(new RefreshToken(user.getId(), hash, expiry));
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, rawToken);
        cookie.setHttpOnly(true);
        cookie.setPath(REFRESH_PATH);
        cookie.setSecure(false); // set to true in prod (HTTPS)
        cookie.setMaxAge((int) (jwtProps.refreshTokenTtlDays() * 24 * 60 * 60));
        response.addCookie(cookie);
    }

    private String extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (REFRESH_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /** SHA-256 hash of the raw token for safe storage. */
    static String hashToken(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
