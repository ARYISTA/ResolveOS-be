package com.resolvos.tenant;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents one company (tenant) in the system.
 * No RLS on this table — it is used for login lookups before the tenant is known.
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    /** Domain enum: BANKING|INSURANCE|SUBSCRIPTIONS|ECOMMERCE|TRAVEL. Stored as text. */
    @Column(nullable = false)
    private String domain;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Tenant() {}

    public Tenant(UUID id, String slug, String name, String domain) {
        this.id = id;
        this.slug = slug;
        this.name = name;
        this.domain = domain;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getDomain() { return domain; }
    public Instant getCreatedAt() { return createdAt; }
}
