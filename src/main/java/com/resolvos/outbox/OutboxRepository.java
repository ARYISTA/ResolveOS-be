package com.resolvos.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE status = 'PENDING' AND attempts < 5
        ORDER BY created_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT 10
        """, nativeQuery = true)
    List<OutboxEvent> findPendingEventsForProcessing();
}
