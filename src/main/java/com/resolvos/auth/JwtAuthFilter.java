package com.resolvos.auth;

import com.resolvos.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the Bearer token, validates it, sets SecurityContext and TenantContext.
 * Clears TenantContext after the request completes.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            Jwt jwt = jwtService.validateAccessToken(token);

            String role = jwt.getClaimAsString("role");
            String tenantId = jwt.getClaimAsString("tenantId");
            String userId = jwt.getClaimAsString("userId");

            // Set tenant context for RLS
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
                MDC.put("tenantId", tenantId);
            }

            MDC.put("userId", userId);

            var auth = new UsernamePasswordAuthenticationToken(
                    jwt.getSubject(),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            auth.setDetails(jwt);
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (JwtException e) {
            // Invalid/expired token — clear context and let Spring Security handle 401
            log.debug("JWT validation failed: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }
}
