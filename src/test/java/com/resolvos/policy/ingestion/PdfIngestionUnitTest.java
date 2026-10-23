package com.resolvos.policy.ingestion;

import com.resolvos.common.config.RagProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfIngestionUnitTest {

    @Test
    void pdfExtractorExtractsPagesAndTextAccurately() throws IOException {
        byte[] pdfBytes = createSamplePdf();

        PdfTextExtractor extractor = new PdfTextExtractor();
        PdfTextExtractor.ExtractedPdf result = extractor.extract(pdfBytes);

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.pages()).hasSize(2);

        PdfTextExtractor.PageText p1 = result.pages().get(0);
        assertThat(p1.pageNumber()).isEqualTo(1);
        assertThat(p1.text()).contains("Section 1.1 Scope");

        PdfTextExtractor.PageText p2 = result.pages().get(1);
        assertThat(p2.pageNumber()).isEqualTo(2);
        assertThat(p2.text()).contains("Section 2.1 Failed UPI Transfer");
    }

    @Test
    void sectionDetectorDetectsNumberedHeadings() {
        SectionDetector detector = new SectionDetector();

        assertThat(detector.detectHeading("4.2 Refund window")).isEqualTo("4.2 Refund window");
        assertThat(detector.detectHeading("Section 1.1 Scope and Eligibility")).isEqualTo("Section 1.1 Scope and Eligibility");
        assertThat(detector.detectHeading("Clause 3.2 Double Debit Reversals")).isEqualTo("Clause 3.2 Double Debit Reversals");
        assertThat(detector.detectHeading("Regular paragraph text with no numbers")).isNull();
        assertThat(detector.detectHeading("")).isNull();
    }

    @Test
    void chunkerDoesNotCrossPageBoundariesAndPreservesMetadata() throws IOException {
        byte[] pdfBytes = createSamplePdf();
        PdfTextExtractor extractor = new PdfTextExtractor();
        PdfTextExtractor.ExtractedPdf extracted = extractor.extract(pdfBytes);

        SectionDetector detector = new SectionDetector();
        RagProperties properties = new RagProperties(400, 60, 4);
        Chunker chunker = new Chunker(detector, properties);

        List<Chunker.ChunkDraft> chunks = chunker.chunkPages(extracted.pages());

        assertThat(chunks).isNotEmpty();
        // Page 1 chunk
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
        assertThat(chunks.get(0).section()).contains("Section 1.1 Scope");
        assertThat(chunks.get(0).tokenCount()).isGreaterThan(0);

        // Page 2 chunk
        assertThat(chunks.get(1).pageNumber()).isEqualTo(2);
        assertThat(chunks.get(1).section()).contains("Section 2.1 Failed UPI Transfer");
        assertThat(chunks.get(1).tokenCount()).isGreaterThan(0);
    }

    private byte[] createSamplePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Section 1.1 Scope and Customer Eligibility");
                cs.newLineAtOffset(0, -20);
                cs.showText("This policy applies to all retail accounts maintained with the bank.");
                cs.endText();
            }

            PDPage page2 = new PDPage();
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Section 2.1 Failed UPI Transfer Reversals");
                cs.newLineAtOffset(0, -20);
                cs.showText("Automated reversal shall execute within 24 hours of settlement reconciliation.");
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
