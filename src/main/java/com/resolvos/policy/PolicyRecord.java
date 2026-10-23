package com.resolvos.policy;

import java.util.List;

public record PolicyRecord(
        String id,
        String name,
        String companyId,
        String companyName,
        String domain,
        int currentVersion,
        List<VersionSummary> versions,
        List<ChunkSummary> chunks,
        String createdAt
) {
    public record VersionSummary(
            int version,
            String updatedAt,
            int chunkCount,
            String filePath,
            long fileSizeKb,
            boolean active
    ) {}

    public record ChunkSummary(
            String id,
            String section,
            int page,
            int tokenCount,
            String excerpt
    ) {}
}
