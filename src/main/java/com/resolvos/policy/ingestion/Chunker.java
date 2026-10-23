package com.resolvos.policy.ingestion;

import com.resolvos.common.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Chunker {

    private final SectionDetector sectionDetector;
    private final int chunkSizeTokens;
    private final int chunkOverlapTokens;

    public record ChunkDraft(
            int pageNumber,
            String section,
            int tokenCount,
            String content
    ) {}

    public Chunker(SectionDetector sectionDetector, RagProperties ragProperties) {
        this.sectionDetector = sectionDetector;
        this.chunkSizeTokens = ragProperties.chunkSizeTokens();
        this.chunkOverlapTokens = ragProperties.chunkOverlapTokens();
    }

    public List<ChunkDraft> chunkPages(List<PdfTextExtractor.PageText> pages) {
        List<ChunkDraft> chunks = new ArrayList<>();
        String currentSection = "General";

        for (PdfTextExtractor.PageText page : pages) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }

            // Check if page starts with or contains any heading
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                String heading = sectionDetector.detectHeading(line);
                if (heading != null) {
                    currentSection = heading;
                    break;
                }
            }

            // Tokenize by whitespace
            String[] words = text.trim().split("\\s+");
            if (words.length == 0 || (words.length == 1 && words[0].isEmpty())) {
                continue;
            }

            if (words.length <= chunkSizeTokens) {
                // Whole page fits in one chunk
                chunks.add(new ChunkDraft(page.pageNumber(), currentSection, words.length, text.trim()));
            } else {
                // Slide window over page words (never crossing page boundaries)
                int step = Math.max(1, chunkSizeTokens - chunkOverlapTokens);
                for (int start = 0; start < words.length; start += step) {
                    int end = Math.min(words.length, start + chunkSizeTokens);
                    StringBuilder sb = new StringBuilder();
                    for (int i = start; i < end; i++) {
                        if (i > start) sb.append(" ");
                        sb.append(words[i]);
                    }
                    String chunkContent = sb.toString();
                    int count = end - start;

                    // Check if chunk slice has a new heading
                    for (String line : chunkContent.split("\\r?\\n")) {
                        String heading = sectionDetector.detectHeading(line);
                        if (heading != null) {
                            currentSection = heading;
                            break;
                        }
                    }

                    chunks.add(new ChunkDraft(page.pageNumber(), currentSection, count, chunkContent));

                    if (end == words.length) {
                        break;
                    }
                }
            }
        }

        return chunks;
    }

    public static int approximateTokenCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }
}
