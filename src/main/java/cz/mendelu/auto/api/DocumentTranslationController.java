package cz.mendelu.auto.api;

import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.service.TranslationOrchestrator;
import cz.mendelu.auto.storage.PdfTextExtractor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Mode 2 &mdash; Simple text/document translation API.
 *
 * <p>Pro klienty, kteří nepotřebují plný DB→ES pipeline (Mode 1),
 * ale jen přeložit jednorázový text nebo dokument:
 *
 * <ul>
 *   <li>{@code POST /api/v1/translate/sync} &mdash; <b>plain text</b>
 *       (existující endpoint v {@link TranslationController}; sync
 *       request → sync response s~překladem)</li>
 *   <li>{@code POST /api/v1/translate}      &mdash; <b>plain text async</b>
 *       (existující; vrací jobId pro pollování)</li>
 *   <li>{@code POST /api/v1/translate/document} &mdash; <b>nahrání souboru</b>
 *       (PDF nebo plain text, multipart/form-data); sidecar extrahuje
 *       text, přeloží a~vrátí výsledek ve~stejném API jako sync text</li>
 * </ul>
 *
 * <p>Tento režim je <em>stateless</em>: nic se neukládá do TM (mimo
 * sémantické cache pro deduplikaci) a~nepotřebuje konfiguraci katalogu.
 * Vhodné pro:
 * <ul>
 *   <li>ad-hoc překlad přílohy z~uživatelského UI;</li>
 *   <li>integraci do~workflow, kde dokument vzniká jednou
 *       a~následně se zpracovává;</li>
 *   <li>integrace, které posílají rovnou text bez DB schématu.</li>
 * </ul>
 *
 * <p>Pro plný DB-driven workflow (sjednocení katalogu, retrieval
 * podle jazyka, SQL proxy) viz Mode 1 v
 * {@link cz.mendelu.auto.catalog.CatalogController}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/translate")
@RequiredArgsConstructor
@Tag(name = "Mode 2 — Simple translation",
        description = "Stateless překlad textu nebo nahraného souboru "
                + "(PDF / plain text). Pro plný DB pipeline viz "
                + "/api/v1/catalogs/**.")
public class DocumentTranslationController {

    private final TranslationOrchestrator orchestrator;
    private final PdfTextExtractor pdfExtractor;

    /**
     * Maximální velikost nahraného souboru (10 MB).
     * Pro větší dokumenty doporučeno asynchronní zpracování přes Mode 1
     * nebo splittnout do menších částí.
     */
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    @Operation(summary = "Přeložit nahraný dokument (PDF / plain text)",
            description = "Multipart/form-data upload: pole `file` obsahuje "
                    + "PDF nebo textový soubor. Sidecar extrahuje text, "
                    + "přeloží do `targetLang` a~vrátí výsledek. "
                    + "Pro PDF se použije Apache PDFBox; pro plain text "
                    + "se obsah dekóduje jako UTF-8.")
    @PostMapping(value = "/document",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> translateDocument(
            @Parameter(description = "Nahraný soubor (PDF, TXT, MD)",
                    required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "ISO 639-1 zdrojový jazyk", example = "en")
            @RequestParam(name = "sourceLang", defaultValue = "en") String sourceLang,
            @Parameter(description = "ISO 639-1 cílový jazyk", example = "cs",
                    required = true)
            @RequestParam("targetLang") String targetLang
    ) {
        // 1. Validace
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "empty_file",
                    "message", "Uploaded file is empty."));
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.status(413).body(Map.of(
                    "error", "file_too_large",
                    "message", "Max upload size is "
                            + (MAX_FILE_SIZE_BYTES / 1024 / 1024) + " MB."));
        }
        if (targetLang == null || targetLang.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_target_lang",
                    "message", "Query parameter 'targetLang' is required."));
        }

        // 2. Extrakce textu — podle Content-Type / přípony
        String text;
        String docType;
        try {
            text = extractText(file);
            docType = detectType(file);
        } catch (IOException e) {
            log.error("Failed to extract text from {}: {}",
                    file.getOriginalFilename(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "extraction_failed",
                    "message", "Could not extract text: " + e.getMessage()));
        }

        if (text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "no_text_extracted",
                    "message", "Document contains no extractable text "
                            + "(scanned PDF without OCR layer?)."));
        }

        // 3. Překlad přes existující orchestrátor (jeho cache + retry +
        //    circuit breaker pipeline reused; není nutná duplicita logiky)
        TranslationResult result = orchestrator.processSync(
                text, sourceLang, targetLang);

        // 4. Odpověď ve~stejném tvaru jako /translate/sync + metadata
        //    pro debug (původní velikost, typ, zdrojový jazyk)
        return ResponseEntity.ok(Map.of(
                "fileName", file.getOriginalFilename() != null
                        ? file.getOriginalFilename() : "unknown",
                "fileType", docType,
                "fileSizeBytes", file.getSize(),
                "extractedChars", text.length(),
                "sourceLang", sourceLang,
                "targetLang", targetLang,
                "translation", result.text(),
                "provider", result.provider(),
                "latencyMs", result.latencyMs(),
                "attempts", result.attempts()
        ));
    }

    /** Vyextrahuje text z {@link MultipartFile} podle obsahu/přípony. */
    private String extractText(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        String contentType = file.getContentType();
        boolean isPdf = (name != null && name.toLowerCase().endsWith(".pdf"))
                || (contentType != null && contentType.contains("pdf"));
        if (isPdf) {
            String raw = pdfExtractor.extract(file.getBytes());
            return pdfExtractor.cleanup(raw);
        }
        // Plain text / markdown / cokoli s~UTF-8 textem
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    /** Identifikace typu souboru pro response (informativní). */
    private String detectType(MultipartFile file) {
        String name = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (name != null && name.toLowerCase().endsWith(".pdf")) return "pdf";
        if (contentType != null && contentType.contains("pdf")) return "pdf";
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) return name.substring(dot + 1).toLowerCase();
        }
        return contentType != null ? contentType : "text/plain";
    }
}
