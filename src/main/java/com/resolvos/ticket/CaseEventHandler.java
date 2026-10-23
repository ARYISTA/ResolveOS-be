package com.resolvos.ticket;

import com.resolvos.engine.EngineEventListener;
import com.resolvos.realtime.RealtimeBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CaseEventHandler implements EngineEventListener {

    private static final Logger log = LoggerFactory.getLogger(CaseEventHandler.class);

    private final TicketRepository ticketRepository;
    private final CaseNodeRunRepository caseNodeRunRepository;
    private final RealtimeBroadcaster realtimeBroadcaster;
    private final ObjectMapper objectMapper;

    public CaseEventHandler(TicketRepository ticketRepository,
                            CaseNodeRunRepository caseNodeRunRepository,
                            RealtimeBroadcaster realtimeBroadcaster,
                            ObjectMapper objectMapper) {
        this.ticketRepository = ticketRepository;
        this.caseNodeRunRepository = caseNodeRunRepository;
        this.realtimeBroadcaster = realtimeBroadcaster;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNodeStarted(UUID caseId, String node) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        UUID tenantId = null;
        if (ticket != null) {
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            tenantId = ticket.getTenantId();
            ticketRepository.save(ticket);
        }

        CaseNodeRun run = caseNodeRunRepository.findByTicketIdAndNode(caseId, node)
                .orElse(new CaseNodeRun(caseId, node, "RUNNING"));
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        caseNodeRunRepository.save(run);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "NODE_STARTED");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("node", node);
        event.put("ts", Instant.now().toString());

        realtimeBroadcaster.broadcastToCase(caseId.toString(), event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNodeCompleted(UUID caseId, String node, long durationMs, Map<String, Object> output) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        if (ticket != null) {
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            ticketRepository.save(ticket);
        }

        String outputJson = null;
        if (output != null) {
            try {
                outputJson = objectMapper.writeValueAsString(output);
            } catch (Exception e) {
                log.warn("Failed to serialize node output json: {}", e.getMessage());
            }
        }

        CaseNodeRun run = caseNodeRunRepository.findByTicketIdAndNode(caseId, node)
                .orElse(new CaseNodeRun(caseId, node, "SUCCESS"));
        run.setStatus("SUCCESS");
        run.setDurationMs(durationMs);
        run.setOutput(outputJson);
        run.setCompletedAt(Instant.now());
        caseNodeRunRepository.save(run);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "NODE_COMPLETED");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("node", node);
        event.put("durationMs", durationMs);
        event.put("output", output != null ? output : Map.of());

        realtimeBroadcaster.broadcastToCase(caseId.toString(), event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNodeFailed(UUID caseId, String node, String error) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        if (ticket != null) {
            ticket.setStatus("FAILED");
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            ticketRepository.save(ticket);
        }

        CaseNodeRun run = caseNodeRunRepository.findByTicketIdAndNode(caseId, node)
                .orElse(new CaseNodeRun(caseId, node, "FAILED"));
        run.setStatus("FAILED");
        run.setCompletedAt(Instant.now());
        run.setOutput("{\"error\":\"" + (error != null ? error.replace("\"", "'") : "Unknown failure") + "\"}");
        caseNodeRunRepository.save(run);

        Map<String, Object> event = new HashMap<>();
        event.put("type", "NODE_FAILED");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("node", node);
        event.put("error", error != null ? error : "Execution failed");

        realtimeBroadcaster.broadcastToCase(caseId.toString(), event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onHitlRequired(UUID caseId, String reason, String riskLevel) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        UUID tenantId = null;
        if (ticket != null) {
            ticket.setStatus("AWAITING_HUMAN");
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            tenantId = ticket.getTenantId();
            ticketRepository.save(ticket);
        }

        Map<String, Object> event = new HashMap<>();
        event.put("type", "HITL_REQUIRED");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("reason", reason);
        event.put("riskLevel", riskLevel);

        realtimeBroadcaster.broadcastToCaseAndTenant(caseId.toString(), tenantId, event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onHitlResolved(UUID caseId, String decision, String reviewer, BigDecimal modifiedAmount, String reason) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        UUID tenantId = null;
        if (ticket != null) {
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            tenantId = ticket.getTenantId();
            ticketRepository.save(ticket);
        }

        Map<String, Object> event = new HashMap<>();
        event.put("type", "HITL_RESOLVED");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("decision", decision);
        event.put("reviewer", reviewer);
        if (modifiedAmount != null) {
            event.put("modifiedAmount", modifiedAmount);
        }
        if (reason != null) {
            event.put("reason", reason);
        }

        realtimeBroadcaster.broadcastToCaseAndTenant(caseId.toString(), tenantId, event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCaseCompleted(UUID caseId, String verdict, String refundRef, BigDecimal approvedAmount) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        UUID tenantId = null;
        if (ticket != null) {
            ticket.setStatus("COMPLETED");
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            tenantId = ticket.getTenantId();
            ticketRepository.save(ticket);
        }

        Map<String, Object> event = new HashMap<>();
        event.put("type", "CASE_COMPLETED");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("verdict", verdict);
        if (refundRef != null) {
            event.put("refundRef", refundRef);
        }
        if (approvedAmount != null) {
            event.put("approvedAmount", approvedAmount);
        }

        realtimeBroadcaster.broadcastToCaseAndTenant(caseId.toString(), tenantId, event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuditEvent(UUID caseId, String action, String actor, String oldState, String newState) {
        Ticket ticket = getTicketOrNull(caseId);
        long seq = 1L;
        if (ticket != null) {
            ticket.incrementEventSeq();
            seq = ticket.getEventSeq();
            ticketRepository.save(ticket);
        }

        Map<String, Object> event = new HashMap<>();
        event.put("type", "AUDIT_EVENT");
        event.put("caseId", caseId.toString());
        event.put("seq", seq);
        event.put("action", action);
        event.put("actor", actor != null ? actor : "System");
        event.put("ts", Instant.now().toString());
        if (oldState != null) event.put("oldState", oldState);
        if (newState != null) event.put("newState", newState);

        realtimeBroadcaster.broadcastToCase(caseId.toString(), event);
    }

    private Ticket getTicketOrNull(UUID caseId) {
        return ticketRepository.findById(caseId).orElse(null);
    }
}
