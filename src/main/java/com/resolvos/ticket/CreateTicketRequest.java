package com.resolvos.ticket;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTicketRequest(
        UUID customerId,
        UUID transactionId,
        String message,
        @NotNull(message = "requestedAmount is required")
        @Positive(message = "requestedAmount must be strictly positive")
        BigDecimal requestedAmount,
        String domain,
        String companyId,
        String customerName,
        String customerTier,
        BigDecimal transactionAmount,
        String presetId,
        String presetTag,
        Boolean triggersHitl,
        String failNode,
        BigDecimal hitlModifiedAmount,
        String attachmentName
) {}
