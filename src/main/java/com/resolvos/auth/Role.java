package com.resolvos.auth;

/** Roles matching frontend strings exactly. Stored as text in the DB. */
public enum Role {
    SUPER_ADMIN,
    TENANT_ADMIN,
    SUPPORT_AGENT,
    REVIEWER
}
