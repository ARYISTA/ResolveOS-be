package com.resolvos.customer;

import java.util.UUID;

public record CustomerRecord(
        UUID id,
        UUID tenantId,
        String name,
        String tier,
        int historyCount
) {
    public static CustomerRecord from(Customer c) {
        return new CustomerRecord(
                c.getId(),
                c.getTenantId(),
                c.getName(),
                c.getTier(),
                c.getHistoryCount()
        );
    }
}
