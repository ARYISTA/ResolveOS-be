package com.resolvos.ticket;

import java.time.Instant;
import java.util.Map;

public record CaseNodeRunDto(
        String node,
        String status,
        Long durationMs,
        Object output,
        Instant startedAt,
        Instant completedAt
) {}
