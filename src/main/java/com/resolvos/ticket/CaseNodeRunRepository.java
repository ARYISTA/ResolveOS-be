package com.resolvos.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseNodeRunRepository extends JpaRepository<CaseNodeRun, CaseNodeRun.CaseNodeRunId> {
    List<CaseNodeRun> findByTicketIdOrderByStartedAtAsc(UUID ticketId);
    Optional<CaseNodeRun> findByTicketIdAndNode(UUID ticketId, String node);
}
