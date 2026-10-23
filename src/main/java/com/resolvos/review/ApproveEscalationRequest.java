package com.resolvos.review;

import java.math.BigDecimal;

public record ApproveEscalationRequest(
        BigDecimal amount,
        BigDecimal modifiedAmount,
        String comment
) {}
