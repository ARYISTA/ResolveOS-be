package com.resolvos.ticket;

public record TicketQueryOptions(
        String companyId,
        String status,
        String risk,
        String outcome,
        String q,
        String search,
        String customer,
        String customerId,
        String from,
        String fromDate,
        String to,
        String toDate,
        String sort,
        String sortBy,
        String sortOrder,
        int page,
        int size
) {
    public String effectiveSearch() {
        if (q != null && !q.isBlank()) return q.trim();
        if (search != null && !search.isBlank()) return search.trim();
        return null;
    }

    public String effectiveCustomer() {
        if (customer != null && !customer.isBlank()) return customer.trim();
        if (customerId != null && !customerId.isBlank()) return customerId.trim();
        return null;
    }

    public String effectiveFrom() {
        if (from != null && !from.isBlank()) return from.trim();
        if (fromDate != null && !fromDate.isBlank()) return fromDate.trim();
        return null;
    }

    public String effectiveTo() {
        if (to != null && !to.isBlank()) return to.trim();
        if (toDate != null && !toDate.isBlank()) return toDate.trim();
        return null;
    }

    public String effectiveSortBy() {
        if (sort != null && !sort.isBlank()) return sort.trim().toLowerCase();
        if (sortBy != null && !sortBy.isBlank()) return sortBy.trim().toLowerCase();
        return "date";
    }

    public String effectiveSortOrder() {
        if (sortOrder != null && !sortOrder.isBlank()) return sortOrder.trim().toLowerCase();
        return "desc";
    }
}
