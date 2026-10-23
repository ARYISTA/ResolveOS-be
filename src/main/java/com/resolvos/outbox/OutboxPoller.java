package com.resolvos.outbox;

import com.resolvos.ticket.ResolutionOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepository;
    private final ResolutionOrchestrator resolutionOrchestrator;

    public OutboxPoller(OutboxRepository outboxRepository,
                        ResolutionOrchestrator resolutionOrchestrator) {
        this.outboxRepository = outboxRepository;
        this.resolutionOrchestrator = resolutionOrchestrator;
    }

    @Scheduled(fixedDelay = 200)
    public void pollAndProcess() {
        List<OutboxEvent> events = fetchPendingEvents();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            processSingleEvent(event.getId());
        }
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> fetchPendingEvents() {
        try {
            return outboxRepository.findPendingEventsForProcessing();
        } catch (Exception e) {
            log.warn("Failed to fetch pending outbox events: {}", e.getMessage());
            return List.of();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(UUID eventId) {
        OutboxEvent event = outboxRepository.findById(eventId).orElse(null);
        if (event == null || !"PENDING".equals(event.getStatus())) {
            return;
        }

        try {
            event.setStatus("PROCESSING");
            outboxRepository.saveAndFlush(event);

            resolutionOrchestrator.dispatch(event);

            event.setStatus("DONE");
            event.setProcessedAt(Instant.now());
            outboxRepository.save(event);
            log.debug("Successfully processed outbox event {}", eventId);
        } catch (Exception e) {
            log.error("Outbox poller failed to process event {}: {}", eventId, e.getMessage());
            event.incrementAttempts();
            if (event.getAttempts() >= 5) {
                event.setStatus("FAILED");
            } else {
                event.setStatus("PENDING");
            }
            event.setProcessedAt(Instant.now());
            outboxRepository.save(event);
        }
    }
}
