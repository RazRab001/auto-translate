package cz.mendelu.auto;

import cz.mendelu.auto.storage.PdfTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy pro PdfTextExtractor (DOCLINKS pipeline).
 *
 * <p>Generuje malé PDF programaticky pomocí PDFBox API, extrahuje
 * z~něj text, ověřuje očekávaný výsledek.
 */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extractsBasicTextFromPdf() throws Exception {
        byte[] pdf = createPdf(
                "Hydraulic pump H-2500",
                "Heavy-duty centrifugal pump, 500 W",
                "Pressure rating: PN16, ISO 4014 compliant"
        );
        String text = extractor.extract(pdf);

        assertThat(text).contains("Hydraulic pump H-2500");
        assertThat(text).contains("Heavy-duty centrifugal pump, 500 W");
        assertThat(text).contains("Pressure rating: PN16, ISO 4014 compliant");
    }

    @Test
    void emptyByteArrayReturnsEmptyString() throws Exception {
        assertThat(extractor.extract(new byte[0])).isEmpty();
        assertThat(extractor.extract((byte[]) null)).isEmpty();
    }

    @Test
    void invalidPdfThrows() {
        byte[] notPdf = "This is not a PDF".getBytes();
        assertThatThrownBy(() -> extractor.extract(notPdf))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void cleanupRemovesTrailingWhitespaceAndCompactsBlankLines() {
        String dirty = "Line 1   \n   \n\nLine 2  \n  Line 3";
        String cleaned = extractor.cleanup(dirty);
        assertThat(cleaned).contains("Line 1");
        assertThat(cleaned).contains("Line 2");
        assertThat(cleaned).contains("Line 3");
        // Žádné více než dva za sebou newlines
        assertThat(cleaned).doesNotContain("\n\n\n");
    }

    /** Vytvoří jednoduché PDF s několika řádky textu. */
    private static byte[] createPdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(50, 750);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -20);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
