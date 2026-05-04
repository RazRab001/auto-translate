package cz.mendelu.auto;

import cz.mendelu.auto.catalog.CatalogProperties;
import cz.mendelu.auto.catalog.CatalogReader;
import cz.mendelu.auto.catalog.CatalogTranslation;
import cz.mendelu.auto.catalog.CatalogTranslationRepository;
import cz.mendelu.auto.catalog.CatalogTranslationService;
import cz.mendelu.auto.connectors.TranslationProvider;
import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.elasticsearch.VectorService;
import cz.mendelu.auto.service.HtmlSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrační test plného catalog pipeline s vestavěným H2:
 * JDBC schema + seed → CatalogReader → translate (mock) → ES storage → retrieval.
 *
 * <p>Pokrývá:
 * <ul>
 *   <li>Inspekci schématu (DatabaseMetaData)</li>
 *   <li>Čtení řádků z konfigurované tabulky</li>
 *   <li>Překlad do více jazyků</li>
 *   <li>Idempotenci (opakovaný sync přeskakuje nezměněné texty)</li>
 *   <li>Retrieval podle (catalogId, recordId, lang)</li>
 *   <li>Multi-language retrieval (jeden záznam ve všech jazycích)</li>
 * </ul>
 */
class CatalogTranslationServiceTest {

    private EmbeddedDatabase ds;
    private CatalogProperties props;
    private CatalogReader reader;
    private CatalogTranslationRepository repo;
    private CatalogTranslationService service;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE ITEM (
                    ITEMNUM VARCHAR(32) PRIMARY KEY,
                    DESCRIPTION VARCHAR(200) NOT NULL,
                    LONGDESCRIPTION VARCHAR(2000)
                )
                """);
        jdbc.update("INSERT INTO ITEM VALUES (?, ?, ?)",
                "ITM-001", "Hydraulic Pump 500W",
                "<p>Heavy-duty pump for industrial use.</p>");
        jdbc.update("INSERT INTO ITEM VALUES (?, ?, ?)",
                "ITM-002", "M12 Hex Bolt", "Stainless steel bolt M12.");

        // Konfigurace katalogu
        props = new CatalogProperties();
        props.setId("test-catalog");
        props.setSourceLang("en");
        props.setTargetLangs(List.of("cs", "de"));
        CatalogProperties.TableConfig tc = new CatalogProperties.TableConfig();
        tc.setName("ITEM");
        tc.setIdColumn("ITEMNUM");
        tc.setTextColumns(List.of("DESCRIPTION", "LONGDESCRIPTION"));
        props.setTables(List.of(tc));

        reader = new CatalogReader(ds);
        repo = new CatalogTranslationRepository();

        // Mock překlad: "TR[lang]:<sourceText>"
        TranslationProvider mockProvider = new TranslationProvider() {
            @Override public String getName() { return "mock"; }
            @Override public TranslationResult translate(
                    String text, String src, String tgt) {
                return new TranslationResult(
                        "TR[" + tgt + "]:" + text, "mock", 5L, 1);
            }
        };
        // Mock embedder: 8-dim hash vector (deterministický)
        VectorService mockVec = new VectorService() {
            @Override public float[] embed(String text) {
                float[] v = new float[8];
                for (int i = 0; i < text.length(); i++) {
                    v[i % 8] += text.charAt(i) / 100f;
                }
                return v;
            }
        };
        service = new CatalogTranslationService(
                props, reader, repo, mockProvider, mockVec, new HtmlSanitizer());
    }

    @Test
    void readsMetadata() {
        Map<String, List<String>> md = reader.readMetadata();
        assertThat(md).containsKey("ITEM");
        assertThat(md.get("ITEM"))
                .contains("ITEMNUM", "DESCRIPTION", "LONGDESCRIPTION");
    }

    @Test
    void syncTranslatesEverythingIntoAllLanguages() {
        var report = service.sync("test-catalog");
        // 2 řádky × 2 sloupce × 2 jazyky = 8
        assertThat(report.translatedCount()).isEqualTo(8);
        assertThat(report.skippedCount()).isZero();
        assertThat(report.errorCount()).isZero();
    }

    @Test
    void syncIsIdempotent() {
        service.sync("test-catalog");
        var second = service.sync("test-catalog");
        // Druhý sync na nezměněných datech: 0 nových, 8 přeskočených
        assertThat(second.translatedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(8);
    }

    @Test
    void retrieveCatalogInSingleLanguage() {
        service.sync("test-catalog");
        Map<String, Map<String, String>> cs =
                repo.findCatalogInLanguage("test-catalog", "cs");
        assertThat(cs).hasSize(2);
        assertThat(cs.get("ITM-001"))
                .containsEntry("DESCRIPTION", "TR[cs]:Hydraulic Pump 500W");
        assertThat(cs.get("ITM-001").get("LONGDESCRIPTION"))
                .startsWith("TR[cs]:")
                .contains("<p>")
                .contains("</p>");
    }

    @Test
    void retrieveOneRecordInAllLanguages() {
        service.sync("test-catalog");
        Map<String, Map<String, String>> langs =
                repo.findAllLanguagesForRecord("test-catalog", "ITM-002");
        assertThat(langs).hasSize(2);
        assertThat(langs).containsKeys("cs", "de");
        assertThat(langs.get("cs"))
                .containsEntry("DESCRIPTION", "TR[cs]:M12 Hex Bolt");
        assertThat(langs.get("de"))
                .containsEntry("DESCRIPTION", "TR[de]:M12 Hex Bolt");
    }

    @Test
    void appendOnlyVersioningOnReTranslate() {
        // Uložíme verzi 1
        repo.save("c", "T", "r1", "f", "en", "cs",
                "src", "v1", "mock", null);
        // Re-translate: stejný klíč, jiný překlad
        repo.save("c", "T", "r1", "f", "en", "cs",
                "src", "v2", "mock", null);
        CatalogTranslation latest = repo.findLatest("c", "r1", "f", "cs");
        assertThat(latest.version()).isEqualTo(2);
        assertThat(latest.translatedText()).isEqualTo("v2");
    }

    @Test
    void statsReportsCorrectly() {
        service.sync("test-catalog");
        var stats = repo.stats("test-catalog");
        assertThat(stats.uniqueRecords()).isEqualTo(2);
        assertThat(stats.totalTranslations()).isEqualTo(8);
        assertThat(stats.languages()).containsExactlyInAnyOrder("cs", "de");
    }
}
