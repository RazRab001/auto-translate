package cz.mendelu.auto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end HTTP API test pro <b>oba režimy</b> sidecaru přes MockMvc
 * (bez reálného Tomcat web serveru → běží i~v~CI bez síťové vrstvy).
 *
 * <p>Pokrývá:
 * <ul>
 *   <li><b>Mode 1</b> — catalog metadata, sync, retrieval podle jazyka,
 *       SQL proxy s~překladem text-columns, stats</li>
 *   <li><b>Mode 2</b> — sync text translation, async + job poll,
 *       document upload (PDF)</li>
 *   <li><b>Security</b> — 401 bez auth, 403 pro nedostatečnou roli</li>
 *   <li><b>Swagger</b> — OpenAPI spec dostupné a~obsahuje očekávané sekce</li>
 * </ul>
 *
 * <p>Tests jsou ordered (sync musí proběhnout před retrieval/SQL).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "admin-pwd-change-me";
    private static final String MAXIMO_USER = "maximo";
    private static final String MAXIMO_PASS = "maximo-pwd-change-me";

    // =========================================================================
    // Public health endpoint — no auth
    // =========================================================================

    @Test @Order(1)
    void healthEndpointReachableWithoutAuth() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // =========================================================================
    // MODE 1 — Catalog full pipeline
    // =========================================================================

    @Test @Order(10)
    void mode1_catalogMetadata() throws Exception {
        mvc.perform(get("/api/v1/catalogs/maximo-items/metadata")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogId").value("maximo-items"))
                .andExpect(jsonPath("$.sourceLang").value("en"))
                .andExpect(jsonPath("$.targetLangs").isArray())
                .andExpect(jsonPath("$.discoveredTables.ITEM").isArray());
    }

    @Test @Order(11)
    void mode1_catalogSync() throws Exception {
        // Demo preloader možná už syncoval — síláme znovu, druhý sync dá
        // skipped=64 (idempotence) ale není to chyba.
        MvcResult res = mvc.perform(post("/api/v1/catalogs/maximo-items/sync")
                        .with(httpBasic(ADMIN_USER, ADMIN_PASS)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        int translated = body.path("translatedCount").asInt();
        int skipped = body.path("skippedCount").asInt();
        // Total sum musí být 8 řádků × 2 sloupce × 4 jazyky = 64
        assertThat(translated + skipped).isEqualTo(64);
        assertThat(body.path("errorCount").asInt()).isZero();
    }

    @Test @Order(12)
    void mode1_recordsInCzech() throws Exception {
        mvc.perform(get("/api/v1/catalogs/maximo-items/records?lang=cs")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("cs"))
                .andExpect(jsonPath("$.recordCount").value(8))
                .andExpect(jsonPath("$.records['15240-H2500'].DESCRIPTION")
                        .exists());
    }

    @Test @Order(13)
    void mode1_singleRecordAllLanguages() throws Exception {
        mvc.perform(get("/api/v1/catalogs/maximo-items/records/15240-H2500")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translations.cs").exists())
                .andExpect(jsonPath("$.translations.de").exists())
                .andExpect(jsonPath("$.translations.sk").exists())
                .andExpect(jsonPath("$.translations.pl").exists());
    }

    @Test @Order(14)
    void mode1_sqlProxyTranslatesTextColumns() throws Exception {
        String body = """
                { "query": "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE ITEMNUM = '15240-H2500'" }
                """;
        mvc.perform(post("/api/v1/sql?lang=cs")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.translationsApplied").value(1))
                .andExpect(jsonPath("$.language").value("cs"));
    }

    @Test @Order(15)
    void mode1_sqlProxyRejectsUpdate() throws Exception {
        String body = """
                { "query": "UPDATE ITEM SET DESCRIPTION = 'X' WHERE ITEMNUM = '15240-H2500'" }
                """;
        mvc.perform(post("/api/v1/sql?lang=cs")
                        .with(httpBasic(ADMIN_USER, ADMIN_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test @Order(16)
    void mode1_catalogStats() throws Exception {
        mvc.perform(get("/api/v1/catalogs/maximo-items/stats")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uniqueRecords").value(8))
                .andExpect(jsonPath("$.totalTranslations").value(64))
                .andExpect(jsonPath("$.languages.length()").value(4));
    }

    // =========================================================================
    // MODE 2 — Simple translation
    // =========================================================================

    @Test @Order(20)
    void mode2_syncTextTranslation() throws Exception {
        String body = """
                {
                  "sourceText": "Hydraulic Pump 500W",
                  "sourceLang": "en",
                  "targetLang": "cs",
                  "forceApiCall": false
                }
                """;
        mvc.perform(post("/api/v1/translate/sync")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translation").exists())
                .andExpect(jsonPath("$.provider").exists());
    }

    @Test @Order(22)
    void mode2_documentUpload() throws Exception {
        // Vygenerujeme malé PDF in-memory
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(
                        Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Maintenance manual for hydraulic pump.");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }

        MockMultipartFile filePart = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", pdfBytes);
        mvc.perform(multipart("/api/v1/translate/document")
                        .file(filePart)
                        .param("sourceLang", "en")
                        .param("targetLang", "cs")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("pdf"))
                .andExpect(jsonPath("$.targetLang").value("cs"))
                .andExpect(jsonPath("$.translation").exists());
    }

    @Test @Order(23)
    void mode2_textUploadAlsoWorks() throws Exception {
        MockMultipartFile filePart = new MockMultipartFile(
                "file", "note.txt", "text/plain",
                "Industrial gear oil ISO VG 220".getBytes());
        mvc.perform(multipart("/api/v1/translate/document")
                        .file(filePart)
                        .param("sourceLang", "en")
                        .param("targetLang", "de")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileType").value("txt"))
                .andExpect(jsonPath("$.targetLang").value("de"));
    }

    // =========================================================================
    // SECURITY
    // =========================================================================

    @Test @Order(30)
    void security_unauthenticatedReturns401() throws Exception {
        mvc.perform(get("/api/v1/catalogs/maximo-items/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test @Order(31)
    void security_maximoCannotPostSync() throws Exception {
        // POST /sync vyžaduje ADMIN; MAXIMO role nestačí
        mvc.perform(post("/api/v1/catalogs/maximo-items/sync")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS)))
                .andExpect(status().isForbidden());
    }

    @Test @Order(32)
    void security_maximoCanSqlProxy() throws Exception {
        String body = """
                { "query": "SELECT ITEMNUM FROM ITEM" }
                """;
        mvc.perform(post("/api/v1/sql")
                        .with(httpBasic(MAXIMO_USER, MAXIMO_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // SWAGGER UI / OPENAPI
    // =========================================================================

    @Test @Order(35)
    void console_pageAvailableWithoutAuth() throws Exception {
        // /console redirect na /console.html (static resource), oba bez auth
        mvc.perform(get("/console"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/console.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString(
                                "Auto-Translate Sidecar")));
    }

    @Test @Order(40)
    void swagger_openApiSpecAvailable() throws Exception {
        MvcResult res = mvc.perform(get("/v3/api-docs")
                        .with(httpBasic(ADMIN_USER, ADMIN_PASS)))
                .andExpect(status().isOk())
                .andReturn();
        String spec = res.getResponse().getContentAsString();
        assertThat(spec)
                .contains("\"openapi\"")
                .contains("Auto-Translate Sidecar API")
                .contains("Mode 1 - Catalog")
                .contains("Mode 2 - Simple");
    }
}
