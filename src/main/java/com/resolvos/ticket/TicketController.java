package com.resolvos.ticket;

import com.resolvos.common.error.ApiException;
import com.resolvos.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT')")
    public ResponseEntity<CaseDetailResponse> createTicket(
            @Valid @RequestBody CreateTicketRequest request,
            Authentication auth) {
        String role = auth != null && !auth.getAuthorities().isEmpty()
                ? auth.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "")
                : "SUPPORT_AGENT";
        CaseDetailResponse response = ticketService.createTicket(request, null, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT')")
    public ResponseEntity<CaseDetailResponse> resolveTicket(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        UUID tenantId = requireTenantId();
        CaseDetailResponse response = ticketService.resolveTicket(id, tenantId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT')")
    public ResponseEntity<CaseDetailResponse> retryTicket(@PathVariable UUID id) {
        UUID tenantId = requireTenantId();
        CaseDetailResponse response = ticketService.retryTicket(id, tenantId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public ResponseEntity<CaseDetailResponse> getTicket(@PathVariable UUID id) {
        UUID tenantId = getTenantIdOrNull();
        CaseDetailResponse response = ticketService.getCaseSnapshot(id, tenantId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public ResponseEntity<Map<String, Object>> listTickets(
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String risk,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String customer,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "limit", required = false) Integer limit) {
        UUID tenantId = ticketService.resolveTenantId(companyId);
        int effectiveSize = limit != null && limit > 0 ? limit : size;
        TicketQueryOptions options = new TicketQueryOptions(
                companyId, status, risk, outcome, q, search, customer, customerId,
                from, fromDate, to, toDate, sort, sortBy, sortOrder, page, effectiveSize
        );
        Map<String, Object> response = ticketService.listTickets(tenantId, options);
        return ResponseEntity.ok(response);
    }

    private UUID requireTenantId() {
        String tenantStr = TenantContext.getTenantId();
        if (tenantStr == null || tenantStr.isBlank()) {
            throw ApiException.unauthorized("No tenant context available");
        }
        return UUID.fromString(tenantStr);
    }

    private UUID getTenantIdOrNull() {
        String tenantStr = TenantContext.getTenantId();
        return (tenantStr != null && !tenantStr.isBlank()) ? UUID.fromString(tenantStr) : null;
    }
}
