package com.resolvos.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AiDecisionRepository extends JpaRepository<AiDecision, UUID> {
    Optional<AiDecision> findByTicketId(UUID ticketId);
}
