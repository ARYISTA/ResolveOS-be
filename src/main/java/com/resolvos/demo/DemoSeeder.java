package com.resolvos.demo;

import com.resolvos.auth.Role;
import com.resolvos.auth.User;
import com.resolvos.auth.UserRepository;
import com.resolvos.customer.Customer;
import com.resolvos.customer.CustomerRepository;
import com.resolvos.customer.Transaction;
import com.resolvos.customer.TransactionRepository;
import com.resolvos.tenant.Tenant;
import com.resolvos.tenant.TenantRepository;
import com.resolvos.tenant.TenantSettings;
import com.resolvos.tenant.TenantSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Seeds fixture data from frontend domain definitions (banking, ecommerce, insurance, subscriptions, travel).
 * Active only under the "demo" profile.
 */
@Component
@Profile("demo")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoSeeder(TenantRepository tenantRepository,
                      TenantSettingsRepository settingsRepository,
                      UserRepository userRepository,
                      CustomerRepository customerRepository,
                      TransactionRepository transactionRepository,
                      PasswordEncoder passwordEncoder) {
        this.tenantRepository = tenantRepository;
        this.settingsRepository = settingsRepository;
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.transactionRepository = transactionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (tenantRepository.count() == 0) {
            log.info("Demo profile active and database empty: seeding demo data...");
            seed();
            log.info("Demo data seeding completed.");
        }
    }

    @Transactional
    public void reset() {
        log.info("Resetting demo data...");
        transactionRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        settingsRepository.deleteAllInBatch();
        tenantRepository.deleteAllInBatch();

        seed();
        log.info("Demo data reset completed.");
    }

    @Transactional
    public void seed() {
        String encodedPassword = passwordEncoder.encode("password123");

        // 1. Super Admin (Global, no tenant)
        userRepository.save(new User(
                UUID.nameUUIDFromBytes("user:superadmin".getBytes(StandardCharsets.UTF_8)),
                null,
                "superadmin@resolvos.com",
                encodedPassword,
                Role.SUPER_ADMIN,
                "Super Admin"
        ));

        // 2. BANKING
        Tenant meridian = createTenant("meridian-bank", "Meridian Bank", "BANKING",
                "5000", "25000", "100000", "0.700");
        createTenantUsers(meridian, "meridian.com", encodedPassword);
        seedBankingData(meridian.getId());

        Tenant northwind = createTenant("northwind-cards", "Northwind Cards", "BANKING",
                "3000", "20000", "75000", "0.700");
        createTenantUsers(northwind, "northwind.com", encodedPassword);

        // 3. ECOMMERCE
        Tenant shopkart = createTenant("shopkart", "ShopKart", "ECOMMERCE",
                "3000", "15000", "50000", "0.700");
        createTenantUsers(shopkart, "shopkart.com", encodedPassword);
        seedEcommerceData(shopkart.getId());

        Tenant urbancart = createTenant("urbancart", "UrbanCart", "ECOMMERCE",
                "4000", "20000", "60000", "0.700");
        createTenantUsers(urbancart, "urbancart.com", encodedPassword);

        // 4. INSURANCE
        Tenant safeguard = createTenant("safeguard-life", "SafeGuard Life", "INSURANCE",
                "10000", "50000", "200000", "0.700");
        createTenantUsers(safeguard, "safeguard.com", encodedPassword);
        seedInsuranceData(safeguard.getId());

        Tenant horizon = createTenant("horizon-general", "Horizon General", "INSURANCE",
                "8000", "40000", "150000", "0.700");
        createTenantUsers(horizon, "horizon.com", encodedPassword);

        // 5. SUBSCRIPTIONS
        Tenant streambox = createTenant("streambox", "StreamBox", "SUBSCRIPTIONS",
                "2000", "10000", "20000", "0.700");
        createTenantUsers(streambox, "streambox.com", encodedPassword);
        seedSubscriptionsData(streambox.getId());

        Tenant clouddesk = createTenant("clouddesk", "CloudDesk SaaS", "SUBSCRIPTIONS",
                "5000", "30000", "60000", "0.700");
        createTenantUsers(clouddesk, "clouddesk.com", encodedPassword);

        // 6. TRAVEL
        Tenant skyway = createTenant("skyway", "SkyWay Travels", "TRAVEL",
                "5000", "30000", "100000", "0.700");
        createTenantUsers(skyway, "skyway.com", encodedPassword);
        seedTravelData(skyway.getId());

        Tenant railmate = createTenant("railmate", "RailMate", "TRAVEL",
                "3000", "15000", "50000", "0.700");
        createTenantUsers(railmate, "railmate.com", encodedPassword);
    }

    private Tenant createTenant(String slug, String name, String domain,
                                String autoApprove, String hitl, String maxRefund, String minConf) {
        UUID id = UUID.nameUUIDFromBytes(("tenant:" + slug).getBytes(StandardCharsets.UTF_8));
        Tenant tenant = new Tenant(id, slug, name, domain);
        tenantRepository.save(tenant);

        TenantSettings settings = new TenantSettings(
                id,
                new BigDecimal(autoApprove),
                new BigDecimal(hitl),
                new BigDecimal(maxRefund),
                new BigDecimal(minConf)
        );
        settingsRepository.save(settings);
        return tenant;
    }

    private void createTenantUsers(Tenant tenant, String domain, String encodedPassword) {
        String slug = tenant.getSlug();
        userRepository.save(new User(
                UUID.nameUUIDFromBytes(("user:" + slug + ":admin").getBytes(StandardCharsets.UTF_8)),
                tenant.getId(),
                "admin@" + domain,
                encodedPassword,
                Role.TENANT_ADMIN,
                tenant.getName() + " Admin"
        ));
        userRepository.save(new User(
                UUID.nameUUIDFromBytes(("user:" + slug + ":agent").getBytes(StandardCharsets.UTF_8)),
                tenant.getId(),
                "agent@" + domain,
                encodedPassword,
                Role.SUPPORT_AGENT,
                tenant.getName() + " Support Agent"
        ));
        userRepository.save(new User(
                UUID.nameUUIDFromBytes(("user:" + slug + ":reviewer").getBytes(StandardCharsets.UTF_8)),
                tenant.getId(),
                "reviewer@" + domain,
                encodedPassword,
                Role.REVIEWER,
                tenant.getName() + " Reviewer"
        ));
    }

    private void seedBankingData(UUID tenantId) {
        UUID c1 = UUID.nameUUIDFromBytes(("customer:meridian:cust-bk-01").getBytes(StandardCharsets.UTF_8));
        UUID c2 = UUID.nameUUIDFromBytes(("customer:meridian:cust-bk-02").getBytes(StandardCharsets.UTF_8));
        UUID c3 = UUID.nameUUIDFromBytes(("customer:meridian:cust-bk-03").getBytes(StandardCharsets.UTF_8));

        customerRepository.saveAll(List.of(
                new Customer(c1, tenantId, "Arjun Sharma", "Premium", 4),
                new Customer(c2, tenantId, "Priya Nair", "Standard", 1),
                new Customer(c3, tenantId, "Vikram Das", "VIP", 12)
        ));

        transactionRepository.saveAll(List.of(
                new Transaction(UUID.nameUUIDFromBytes("txn:meridian:txn-bk-01".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "UPI transfer", new BigDecimal("2500.00"), "Failed", "ShopMart",
                        parseDate("2026-10-18"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:meridian:txn-bk-02".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c2, "Card payment", new BigDecimal("3600.00"), "Disputed", "TechZone Electronics",
                        parseDate("2026-10-15"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:meridian:txn-bk-03".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Card payment", new BigDecimal("42000.00"), "Disputed", "Unknown Merchant",
                        parseDate("2026-10-20"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:meridian:txn-bk-04".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c3, "UPI transfer", new BigDecimal("1000.00"), "Completed", "Online Store",
                        parseDate("2026-10-21"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:meridian:txn-bk-05".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c2, "NEFT", new BigDecimal("4200.00"), "Disputed", "Reliance Mart",
                        parseDate("2026-10-19"), false)
        ));
    }

    private void seedEcommerceData(UUID tenantId) {
        UUID c1 = UUID.nameUUIDFromBytes(("customer:shopkart:cust-ec-01").getBytes(StandardCharsets.UTF_8));
        UUID c2 = UUID.nameUUIDFromBytes(("customer:shopkart:cust-ec-02").getBytes(StandardCharsets.UTF_8));
        UUID c3 = UUID.nameUUIDFromBytes(("customer:shopkart:cust-ec-03").getBytes(StandardCharsets.UTF_8));

        customerRepository.saveAll(List.of(
                new Customer(c1, tenantId, "Rohit Verma", "Standard", 2),
                new Customer(c2, tenantId, "Sneha Menon", "Premium", 6),
                new Customer(c3, tenantId, "Amit Joshi", "VIP", 15)
        ));

        transactionRepository.saveAll(List.of(
                new Transaction(UUID.nameUUIDFromBytes("txn:shopkart:txn-ec-01".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Order payment", new BigDecimal("2200.00"), "Disputed", "ShopKart — ORD-8821",
                        parseDate("2026-10-17"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:shopkart:txn-ec-02".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c2, "Order payment", new BigDecimal("1499.00"), "Disputed", "UrbanCart — ORD-9234",
                        parseDate("2026-10-12"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:shopkart:txn-ec-03".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Order payment", new BigDecimal("4500.00"), "Completed", "ShopKart — ORD-7710",
                        parseDate("2026-09-20"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:shopkart:txn-ec-04".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c3, "Order payment", new BigDecimal("62000.00"), "Disputed", "ShopKart — ORD-6601",
                        parseDate("2026-10-10"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:shopkart:txn-ec-05".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Order payment", new BigDecimal("3800.00"), "Completed", "UrbanCart — ORD-5540",
                        parseDate("2026-10-05"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:shopkart:txn-ec-06".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c3, "COD refund", new BigDecimal("850.00"), "Disputed", "UrbanCart — ORD-9900",
                        parseDate("2026-10-19"), true)
        ));
    }

    private void seedInsuranceData(UUID tenantId) {
        UUID c1 = UUID.nameUUIDFromBytes(("customer:safeguard:cust-in-01").getBytes(StandardCharsets.UTF_8));
        UUID c2 = UUID.nameUUIDFromBytes(("customer:safeguard:cust-in-02").getBytes(StandardCharsets.UTF_8));

        customerRepository.saveAll(List.of(
                new Customer(c1, tenantId, "Rajesh Kumar", "Premium", 3),
                new Customer(c2, tenantId, "Ananya Patel", "Standard", 1)
        ));

        transactionRepository.saveAll(List.of(
                new Transaction(UUID.nameUUIDFromBytes("txn:safeguard:txn-in-01".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Premium payment", new BigDecimal("12500.00"), "Completed", "SafeGuard Term Life",
                        parseDate("2026-10-01"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:safeguard:txn-in-02".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c2, "Claim settlement", new BigDecimal("45000.00"), "Disputed", "Horizon Health Shield",
                        parseDate("2026-09-28"), false)
        ));
    }

    private void seedSubscriptionsData(UUID tenantId) {
        UUID c1 = UUID.nameUUIDFromBytes(("customer:streambox:cust-sub-01").getBytes(StandardCharsets.UTF_8));
        UUID c2 = UUID.nameUUIDFromBytes(("customer:streambox:cust-sub-02").getBytes(StandardCharsets.UTF_8));

        customerRepository.saveAll(List.of(
                new Customer(c1, tenantId, "Deepak Rao", "VIP", 8),
                new Customer(c2, tenantId, "Meera Iyer", "Standard", 2)
        ));

        transactionRepository.saveAll(List.of(
                new Transaction(UUID.nameUUIDFromBytes("txn:streambox:txn-sub-01".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Annual charge", new BigDecimal("1499.00"), "Disputed", "StreamBox Premium Annual",
                        parseDate("2026-10-14"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:streambox:txn-sub-02".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c2, "Monthly charge", new BigDecimal("499.00"), "Completed", "CloudDesk Professional",
                        parseDate("2026-10-18"), false)
        ));
    }

    private void seedTravelData(UUID tenantId) {
        UUID c1 = UUID.nameUUIDFromBytes(("customer:skyway:cust-tr-01").getBytes(StandardCharsets.UTF_8));
        UUID c2 = UUID.nameUUIDFromBytes(("customer:skyway:cust-tr-02").getBytes(StandardCharsets.UTF_8));

        customerRepository.saveAll(List.of(
                new Customer(c1, tenantId, "Karan Kapoor", "Premium", 5),
                new Customer(c2, tenantId, "Shalini Bose", "Standard", 2)
        ));

        transactionRepository.saveAll(List.of(
                new Transaction(UUID.nameUUIDFromBytes("txn:skyway:txn-tr-01".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c1, "Flight ticket", new BigDecimal("7800.00"), "Disputed", "SkyWay Flight 6E-204",
                        parseDate("2026-10-16"), false),
                new Transaction(UUID.nameUUIDFromBytes("txn:skyway:txn-tr-02".getBytes(StandardCharsets.UTF_8)),
                        tenantId, c2, "Train ticket", new BigDecimal("1250.00"), "Completed", "RailMate Rajdhani Express",
                        parseDate("2026-10-19"), false)
        ));
    }

    private Instant parseDate(String yyyyMmDd) {
        return LocalDate.parse(yyyyMmDd).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
