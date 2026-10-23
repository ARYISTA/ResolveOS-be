package com.resolvos.auth;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("demo")
class DemoAuthTest extends AbstractIntegrationTest {

    @Autowired
    private DemoAuthController demoAuthController;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private com.resolvos.tenant.TenantSettingsRepository settingsRepository;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenantRepository.save(new Tenant(tenantId, "demo-meridian-" + tenantId, "Meridian Demo", "BANKING"));
    }

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAll();
        if (tenantId != null) {
            userRepository.findAll().stream()
                    .filter(u -> tenantId.equals(u.getTenantId()))
                    .forEach(userRepository::delete);
            tenantRepository.deleteById(tenantId);
        }
        userRepository.findByEmail("demo-super_admin@global.local").ifPresent(userRepository::delete);
    }

    @Test
    void demoLoginIssuesValidJwtWithRoleAndTenant() {
        var req = new DemoAuthController.DemoLoginRequest(
                tenantId.toString(),
                null,
                "TENANT_ADMIN"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        var res = demoAuthController.demoLogin(req, response);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();

        String token = res.getBody().token();
        assertThat(token).isNotBlank();

        // Validate token claims using JwtService
        Jwt jwt = jwtService.validateAccessToken(token);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("TENANT_ADMIN");
        assertThat(jwt.getClaimAsString("tenantId")).isEqualTo(tenantId.toString());
        assertThat(jwt.getSubject()).startsWith("demo-tenant_admin@");
    }

    @Test
    void demoLoginSuperAdminHasNoTenantClaim() {
        var req = new DemoAuthController.DemoLoginRequest(
                null,
                null,
                "SUPER_ADMIN"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        var res = demoAuthController.demoLogin(req, response);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        String token = res.getBody().token();
        Jwt jwt = jwtService.validateAccessToken(token);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("SUPER_ADMIN");
        assertThat(jwt.getClaimAsString("tenantId")).isNull();
    }
}
