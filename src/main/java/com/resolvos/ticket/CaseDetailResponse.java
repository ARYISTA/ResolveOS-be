package com.resolvos.ticket;

import com.resolvos.customer.Customer;
import com.resolvos.customer.Transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CaseDetailResponse(
        UUID id,
        UUID caseId,
        String caseNumber,
        String domain,
        String companyId,
        UUID customerId,
        String customerName,
        String customerTier,
        UUID transactionId,
        BigDecimal transactionAmount,
        BigDecimal requestedAmount,
        BigDecimal approvedAmount,
        String rejectionReason,
        String message,
        String presetId,
        String presetTag,
        boolean triggersHitl,
        String failNode,
        BigDecimal hitlModifiedAmount,
        String status,
        String ticketStatus,
        String outcome,
        String ticketOutcome,
        String riskLevel,
        String createdAt,
        String completedAt,
        String traceId,
        long eventSeq,
        long lastSeq,
        Customer customer,
        Transaction transaction,
        List<CaseNodeRunDto> nodes,
        ResolutionResultDto resolution,
        List<AuditLogDto> audit
) {}
