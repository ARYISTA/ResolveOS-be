package com.resolvos.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    Optional<Ticket> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<Ticket> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
    List<Ticket> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.status = 'PROCESSING' WHERE t.id = :id AND t.tenantId = :tenantId AND t.status IN ('RECEIVED', 'FAILED')")
    int transitionToProcessing(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
