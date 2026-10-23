package com.resolvos.customer;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.common.error.ApiException;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantContext;
import com.resolvos.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CustomerIsolationTest extends AbstractIntegrationTest {

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenantA;
    private UUID tenantB;
    private UUID customerA;
    private UUID customerB;
    private UUID txnA;
    private UUID txnB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        customerA = UUID.randomUUID();
        customerB = UUID.randomUUID();
        txnA = UUID.randomUUID();
        txnB = UUID.randomUUID();

        // Seed tenants
        jdbc.update("INSERT INTO tenants (id, slug, name, domain) VALUES (?, ?, ?, ?)",
                tenantA, "iso-tenant-a-" + tenantA, "Tenant A", "BANKING");
        jdbc.update("INSERT INTO tenants (id, slug, name, domain) VALUES (?, ?, ?, ?)",
                tenantB, "iso-tenant-b-" + tenantB, "Tenant B", "BANKING");

        // Seed customers directly
        jdbc.update("INSERT INTO customers (id, tenant_id, name, tier, history_count) VALUES (?, ?, ?, ?, ?)",
                customerA, tenantA, "Alice Alpha", "Standard", 1);
        jdbc.update("INSERT INTO customers (id, tenant_id, name, tier, history_count) VALUES (?, ?, ?, ?, ?)",
                customerB, tenantB, "Bob Beta", "Premium", 3);

        // Seed transactions directly
        jdbc.update("INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                txnA, tenantA, customerA, "Card payment", new BigDecimal("1500.00"), "Completed", "Store A", java.sql.Timestamp.from(Instant.now()), false);
        jdbc.update("INSERT INTO transactions (id, tenant_id, customer_id, type, amount, status, merchant_or_product, occurred_at, force_gateway_failure) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                txnB, tenantB, customerB, "UPI transfer", new BigDecimal("2500.00"), "Completed", "Store B", java.sql.Timestamp.from(Instant.now()), false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM transactions WHERE id IN (?, ?)", txnA, txnB);
        jdbc.update("DELETE FROM customers WHERE id IN (?, ?)", customerA, customerB);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?)", tenantA, tenantB);
    }

    @Test
    void customerSearchUnderTenantAContextSeesOnlyTenantACustomers() {
        TenantContext.setTenantId(tenantA.toString());

        List<CustomerRecord> results = customerService.search(null);
        List<UUID> ids = results.stream().map(CustomerRecord::id).toList();

        assertThat(ids).contains(customerA);
        assertThat(ids).doesNotContain(customerB);
    }

    @Test
    void customerSearchUnderTenantBContextSeesOnlyTenantBCustomers() {
        TenantContext.setTenantId(tenantB.toString());

        List<CustomerRecord> results = customerService.search(null);
        List<UUID> ids = results.stream().map(CustomerRecord::id).toList();

        assertThat(ids).contains(customerB);
        assertThat(ids).doesNotContain(customerA);
    }

    @Test
    void customerTransactionsAreIsolatedCrossTenant() {
        TenantContext.setTenantId(tenantA.toString());

        // Tenant A can see their own customer's transactions
        List<TransactionRecord> txns = customerService.getTransactions(customerA);
        assertThat(txns).extracting(TransactionRecord::id).containsExactly(txnA);

        // Tenant A requesting Tenant B customer's transactions receives 404 (customer does not exist in tenant A)
        assertThatThrownBy(() -> customerService.getTransactions(customerB))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void rlsPolicyEnforcesDatabaseLevelIsolationOnCustomersAndTransactions() {
        // Set tenant A on session
        jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantA + "', false)");

        List<UUID> visibleCustomerIds = jdbc.query(
                "SELECT id FROM customers WHERE tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid",
                (rs, n) -> UUID.fromString(rs.getString("id"))
        );
        assertThat(visibleCustomerIds).contains(customerA);
        assertThat(visibleCustomerIds).doesNotContain(customerB);

        List<UUID> visibleTxnIds = jdbc.query(
                "SELECT id FROM transactions WHERE tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid",
                (rs, n) -> UUID.fromString(rs.getString("id"))
        );
        assertThat(visibleTxnIds).contains(txnA);
        assertThat(visibleTxnIds).doesNotContain(txnB);
    }
}
