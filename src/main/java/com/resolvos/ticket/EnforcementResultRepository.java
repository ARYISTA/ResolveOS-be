package com.resolvos.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface EnforcementResultRepository extends JpaRepository<EnforcementResult, UUID> {
    Optional<EnforcementResult> findByTicketId(UUID ticketId);
}
