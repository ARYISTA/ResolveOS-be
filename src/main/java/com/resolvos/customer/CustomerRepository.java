package com.resolvos.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    List<Customer> findByNameContainingIgnoreCaseOrderByNameAsc(String name);
    List<Customer> findAllByOrderByNameAsc();
}
