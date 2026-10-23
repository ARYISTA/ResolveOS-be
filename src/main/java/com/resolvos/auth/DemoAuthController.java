package com.resolvos.auth;

import com.resolvos.common.config.JwtProperties;
import com.resolvos.common.error.ApiException;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Demo login endpoint registered ONLY under the "demo" profile.
 * Allows switching roles and tenants without requiring password authentication.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("demo")
public class DemoAuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/auth";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantRepository tenantRepository;
    private final JwtProperties jwtProperties;

    public DemoAuthController(JwtService jwtService,
                              UserRepository userRepository,
                              RefreshTokenRepository refreshTokenRepository,
                              TenantRepository tenantRepository,
                              JwtProperties jwtProperties) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantRepository = tenantRepository;
        this.jwtProperties = jwtProperties;
    }

    public record DemoLoginRequest(
            String tenantId,
            String companyId,
            String role
    ) {}

    @PostMapping("/demo-login")
    @Transactional
    public ResponseEntity<AuthController.TokenResponse> demoLogin(
            @RequestBody DemoLoginRequest req,
            HttpServletResponse response) {

        Role targetRole = parseRole(req.role());

        UUID targetTenantId = null;
        String tenantSlug = "global";

        if (targetRole != Role.SUPER_ADMIN) {
            String tenantIdentifier = req.tenantId() != null && !req.tenantId().isBlank()
                    ? req.tenantId()
                    : req.companyId();

            if (tenantIdentifier == null || tenantIdentifier.isBlank()) {
                // If not supplied, pick the first tenant available or throw
                Tenant firstTenant = tenantRepository.findAll().stream().findFirst()
                        .orElseThrow(() -> ApiException.notFound("No tenants available for demo login"));
                targetTenantId = firstTenant.getId();
                tenantSlug = firstTenant.getSlug();
            } else {
                Tenant tenant = findTenant(tenantIdentifier)
                        .orElseThrow(() -> ApiException.notFound("Tenant not found: " + tenantIdentifier));
                targetTenantId = tenant.getId();
                tenantSlug = tenant.getSlug();
            }
        }

        String email = "demo-" + targetRole.name().toLowerCase(Locale.ROOT) + "@" + tenantSlug + ".local";
        UUID finalTenantId = targetTenantId;
        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User newUser = new User(
                    UUID.randomUUID(),
                    finalTenantId,
                    email,
                    "{noop}demo",
                    targetRole,
                    "Demo " + targetRole.name()
            );
            return userRepository.save(newUser);
        });

        String accessToken = jwtService.generateAccessToken(user);
        String rawRefresh = jwtService.generateRawRefreshToken();
        storeRefreshToken(user, rawRefresh);
        setRefreshCookie(response, rawRefresh);

        return ResponseEntity.ok(new AuthController.TokenResponse(accessToken));
    }

    private Optional<Tenant> findTenant(String identifier) {
        try {
            UUID id = UUID.fromString(identifier);
            Optional<Tenant> byId = tenantRepository.findById(id);
            if (byId.isPresent()) return byId;
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, try slug
        }
        return tenantRepository.findBySlug(identifier);
    }

    private Role parseRole(String roleStr) {
        if (roleStr == null || roleStr.isBlank()) {
            return Role.SUPPORT_AGENT;
        }
        String normalized = roleStr.trim().toUpperCase(Locale.ROOT).replace("ROLE_", "");
        return switch (normalized) {
            case "SUPER_ADMIN", "SUPERADMIN", "SA" -> Role.SUPER_ADMIN;
            case "TENANT_ADMIN", "ADMIN" -> Role.TENANT_ADMIN;
            case "REVIEWER", "AUDITOR" -> Role.REVIEWER;
            case "SUPPORT_AGENT", "AGENT", "CUSTOMER" -> Role.SUPPORT_AGENT;
            default -> {
                try {
                    yield Role.valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    yield Role.SUPPORT_AGENT;
                }
            }
        };
    }

    private void storeRefreshToken(User user, String rawToken) {
        String hash = AuthController.hashToken(rawToken);
        Instant expiry = Instant.now().plus(jwtProperties.refreshTokenTtlDays(), ChronoUnit.DAYS);
        refreshTokenRepository.save(new RefreshToken(user.getId(), hash, expiry));
    }

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, rawToken);
        cookie.setHttpOnly(true);
        cookie.setPath(REFRESH_PATH);
        cookie.setSecure(false);
        cookie.setMaxAge((int) (jwtProperties.refreshTokenTtlDays() * 24 * 60 * 60));
        response.addCookie(cookie);
    }
}
