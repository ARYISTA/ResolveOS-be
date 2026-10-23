package com.resolvos.common.config;

import com.resolvos.tenant.TenantAwareTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class JpaConfig {

    /**
     * Replace the default JpaTransactionManager with our tenant-aware subclass
     * that injects app.tenant_id into every transaction.
     */
    @Bean
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory emf,
            DataSource dataSource) {
        return new TenantAwareTransactionManager(emf, dataSource);
    }
}
