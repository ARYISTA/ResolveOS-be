package com.resolvos.policy.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class PdfTextExtractor {

    public record PageText(int pageNumber, String text) {}
    public record ExtractedPdf(int totalPages, List<PageText> pages) {}

    public ExtractedPdf extract(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int totalPages = doc.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(totalPages);
            PDFTextStripper stripper = new PDFTextStripper();

            for (int p = 1; p <= totalPages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                pages.add(new PageText(p, text != null ? text.trim() : ""));
            }

            return new ExtractedPdf(totalPages, pages);
        }
    }
}
