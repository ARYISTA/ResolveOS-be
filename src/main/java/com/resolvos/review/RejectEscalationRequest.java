package com.resolvos.review;

public record RejectEscalationRequest(
        String comment,
        String reason
) {}
