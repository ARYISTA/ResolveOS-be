package com.resolvos.policy.ingestion;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SectionDetector {

    // Matches e.g. "4.2 Refund window", "Section 1.1 Scope", "Clause 3.2 ..."
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(?:(?:Section|Clause|Article)\\s+)?(\\d+(?:\\.\\d+)*\\s+[^\\r\\n]+)$",
            Pattern.CASE_INSENSITIVE
    );

    public String detectHeading(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String trimmed = line.trim();
        Matcher matcher = HEADING_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return trimmed;
        }
        return null;
    }
}
