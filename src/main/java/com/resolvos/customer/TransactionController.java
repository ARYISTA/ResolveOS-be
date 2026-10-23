package com.resolvos.customer;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final CustomerService customerService;

    public TransactionController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'SUPPORT_AGENT', 'REVIEWER')")
    public TransactionRecord getTransaction(@PathVariable UUID id) {
        return customerService.getTransactionById(id);
    }
}
