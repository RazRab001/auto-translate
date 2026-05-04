package cz.mendelu.auto;

import cz.mendelu.auto.api.DocumentTranslationController;
import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.service.TranslationOrchestrator;
import cz.mendelu.auto.storage.PdfTextExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test Mode 2 — Simple document translation endpoint.
 *
 * <p>Pokrývá:
 * <ul>
 *   <li>Plain text upload → překlad</li>
 *   <li>PDF upload → extrakce + překlad</li>
 *   <li>Prázdný soubor → HTTP 400</li>
 *   <li>Chybějící targetLang → HTTP 400</li>
 *   <li>Soubor přes 10 MB → HTTP 413</li>
 * </ul>
 */
class DocumentTranslationControllerTest {

    private TranslationOrchestrator orchestrator;
    private DocumentTranslationController controller;

    @BeforeEach
    void setUp() {
        orchestrator = mock(TranslationOrchestrator.class);
        when(orchestrator.processSync(any(), any(), any()))
                .thenAnswer(inv -> {
                    String text = inv.getArgument(0);
                    String tgt = inv.getArgument(2);
                    return new TranslationResult(
                            "TR[" + tgt + "]:" + text, "mock", 5L, 1);
                });
        controller = new DocumentTranslationController(
                orchestrator, new PdfTextExtractor());
    }

    @Test
    @SuppressWarnings("unchecked")
    void plainTextUploadIsTranslated() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain",
                "Hydraulic Pump 500W".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<?> resp = controller.translateDocument(file, "en", "cs");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body)
                .containsEntry("translation", "TR[cs]:Hydraulic Pump 500W")
                .containsEntry("sourceLang", "en")
                .containsEntry("targetLang", "cs")
                .containsEntry("fileType", "txt")
                .containsEntry("provider", "mock");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pdfUploadIsExtractedAndTranslated() throws Exception {
        // Vygenerujeme malé PDF in-memory
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(
                        Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello world from PDF");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", pdfBytes);

        ResponseEntity<?> resp = controller.translateDocument(file, "en", "de");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("fileType", "pdf");
        assertThat((String) body.get("translation"))
                .startsWith("TR[de]:")
                .contains("Hello world from PDF");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyFileReturns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        ResponseEntity<?> resp = controller.translateDocument(file, "en", "cs");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("error", "empty_file");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingTargetLangReturns400() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain",
                "abc".getBytes());
        ResponseEntity<?> resp = controller.translateDocument(file, "en", "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("error", "missing_target_lang");
    }

    @Test
    @SuppressWarnings("unchecked")
    void oversizedFileReturns413() {
        byte[] tooBig = new byte[11 * 1024 * 1024]; // 11 MB > 10 MB limit
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.txt", "text/plain", tooBig);

        ResponseEntity<?> resp = controller.translateDocument(file, "en", "cs");
        assertThat(resp.getStatusCode().value()).isEqualTo(413);
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("error", "file_too_large");
    }

    @Test
    @SuppressWarnings("unchecked")
    void utf8TextIsPreserved() {
        // Český vstupní text (UTF-8 multi-byte chars)
        MockMultipartFile file = new MockMultipartFile(
                "file", "cz.txt", "text/plain; charset=UTF-8",
                "Šroub M12 nerezový".getBytes(StandardCharsets.UTF_8));
        when(orchestrator.processSync(eq("Šroub M12 nerezový"), any(), any()))
                .thenReturn(new TranslationResult(
                        "M12 stainless steel bolt", "mock", 3L, 1));

        ResponseEntity<?> resp = controller.translateDocument(file, "cs", "en");
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("translation", "M12 stainless steel bolt");
    }
}
