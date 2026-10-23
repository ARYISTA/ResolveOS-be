package com.resolvos.customer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String tier;

    @Column(name = "history_count", nullable = false)
    private int historyCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {}

    public Customer(UUID id, UUID tenantId, String name, String tier, int historyCount) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.tier = tier;
        this.historyCount = historyCount;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getTier() { return tier; }
    public int getHistoryCount() { return historyCount; }
    public Instant getCreatedAt() { return createdAt; }
}
