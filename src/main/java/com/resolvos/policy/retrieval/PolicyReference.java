package com.resolvos.policy.retrieval;

import java.util.UUID;

public record PolicyReference(
        UUID policyId,
        int version,
        String documentName,
        int page,
        String section,
        UUID chunkId,
        String excerpt,
        double score
) {}
