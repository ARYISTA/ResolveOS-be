package com.resolvos.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    Optional<Refund> findByTicketId(UUID ticketId);
    Optional<Refund> findByIdempotencyKey(String idempotencyKey);
    boolean existsByTicketId(UUID ticketId);
}
