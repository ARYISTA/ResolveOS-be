package com.resolvos;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for all integration tests requiring PostgreSQL with pgvector.
 * Uses a single shared container per test run (static) for speed.
 * Never uses H2.
 */
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("resolvos_test")
                .withUsername("resolvos_owner")
                .withPassword("test_password");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "resolvos_owner");
        registry.add("spring.datasource.password", () -> "test_password");
        // Flyway also needs owner creds to run migrations
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "resolvos_owner");
        registry.add("spring.flyway.password", () -> "test_password");
        // App connects as owner in tests (resolvos_app role created in V8 may not have login in container)
        registry.add("APP_DB_USER", () -> "resolvos_owner");
        registry.add("APP_DB_PASSWORD", () -> "test_password");
        // JWT secret for tests (must be >= 32 bytes)
        registry.add("JWT_SECRET", () -> "test-secret-at-least-32-bytes-long!!");
        registry.add("resolvos.jwt.secret", () -> "test-secret-at-least-32-bytes-long!!");
        registry.add("FRONTEND_ORIGIN", () -> "http://localhost:3000");
        registry.add("POSTGRES_USER", () -> "resolvos_owner");
        registry.add("POSTGRES_PASSWORD", () -> "test_password");
    }
}
