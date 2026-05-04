package cz.mendelu.auto;

import cz.mendelu.auto.catalog.CatalogProperties;
import cz.mendelu.auto.catalog.CatalogTranslationRepository;
import cz.mendelu.auto.catalog.SqlProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integrační test SQL-proxy: ověřuje drop-in transparentní vrstvu nad
 * zdrojovou DB s~překladovou substitucí textových sloupců.
 *
 * <p>Pokrývá:
 * <ul>
 *   <li>SELECT všech sloupců → překlad text-columns</li>
 *   <li>SELECT subset (jen textový sloupec) → správná substituce</li>
 *   <li>WHERE klauzule pracuje na zdrojových (anglických) datech</li>
 *   <li>ORDER BY pracuje na zdrojových datech</li>
 *   <li>Bez {@code lang}: pass-through, žádná substituce</li>
 *   <li>Chybějící překlad: graceful fallback na originál</li>
 *   <li>Odmítnutí non-SELECT dotazů (UPDATE/DELETE/DROP)</li>
 * </ul>
 */
class SqlProxyServiceTest {

    private EmbeddedDatabase ds;
    private CatalogProperties props;
    private CatalogTranslationRepository repo;
    private SqlProxyService proxy;

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
                    LONGDESCRIPTION VARCHAR(2000),
                    CATEGORY VARCHAR(50)
                )
                """);
        jdbc.update("INSERT INTO ITEM VALUES (?, ?, ?, ?)",
                "ITM-001", "Hydraulic Pump", "Heavy-duty pump.", "HYDRAULICS");
        jdbc.update("INSERT INTO ITEM VALUES (?, ?, ?, ?)",
                "ITM-002", "M12 Hex Bolt", "Stainless steel.", "FASTENERS");
        jdbc.update("INSERT INTO ITEM VALUES (?, ?, ?, ?)",
                "ITM-003", "Lock Nut M10", "Self-locking nut.", "FASTENERS");

        props = new CatalogProperties();
        props.setId("test-catalog");
        props.setSourceLang("en");
        props.setTargetLangs(List.of("cs", "de"));
        CatalogProperties.TableConfig tc = new CatalogProperties.TableConfig();
        tc.setName("ITEM");
        tc.setIdColumn("ITEMNUM");
        tc.setTextColumns(List.of("DESCRIPTION", "LONGDESCRIPTION"));
        props.setTables(List.of(tc));

        repo = new CatalogTranslationRepository();
        // Naplníme TM hotovými překlady (jako kdyby /sync proběhl)
        repo.save("test-catalog", "ITEM", "ITM-001", "DESCRIPTION",
                "en", "cs", "Hydraulic Pump", "Hydraulické čerpadlo",
                "mock", null);
        repo.save("test-catalog", "ITEM", "ITM-001", "LONGDESCRIPTION",
                "en", "cs", "Heavy-duty pump.", "Čerpadlo pro náročný provoz.",
                "mock", null);
        repo.save("test-catalog", "ITEM", "ITM-002", "DESCRIPTION",
                "en", "cs", "M12 Hex Bolt", "Šestihranný šroub M12",
                "mock", null);
        // ITM-003 schválně nemá překlad (test fallbacku)

        proxy = new SqlProxyService(ds, props, repo);
    }

    @Test
    void selectAllColumnsTranslatesTextColumnsToCzech() {
        var r = proxy.execute(
                "SELECT ITEMNUM, DESCRIPTION, LONGDESCRIPTION, CATEGORY "
                        + "FROM ITEM WHERE ITEMNUM = 'ITM-001'", "cs");
        assertThat(r.columns()).containsExactly(
                "ITEMNUM", "DESCRIPTION", "LONGDESCRIPTION", "CATEGORY");
        assertThat(r.rowCount()).isEqualTo(1);
        var row = r.rows().get(0);
        assertThat(row.get(0)).isEqualTo("ITM-001");
        assertThat(row.get(1)).isEqualTo("Hydraulické čerpadlo");        // přeloženo
        assertThat(row.get(2)).isEqualTo("Čerpadlo pro náročný provoz."); // přeloženo
        assertThat(row.get(3)).isEqualTo("HYDRAULICS");                  // ne-textový sloupec, originál
        assertThat(r.translationsApplied()).isEqualTo(2);
    }

    @Test
    void selectSubsetOnlyTranslatesPresentColumns() {
        var r = proxy.execute(
                "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE ITEMNUM = 'ITM-002'",
                "cs");
        assertThat(r.rows().get(0).get(1)).isEqualTo("Šestihranný šroub M12");
        assertThat(r.translationsApplied()).isEqualTo(1);
    }

    @Test
    void whereClauseAppliesOnSourceDataNotTranslated() {
        // Filter "CATEGORY = 'HYDRAULICS'" je vyhodnocen na zdrojové DB,
        // takže vrací 1 řádek (ITM-001), ale překlad se aplikuje na výstupu
        var r = proxy.execute(
                "SELECT ITEMNUM, DESCRIPTION FROM ITEM "
                        + "WHERE CATEGORY = 'HYDRAULICS'", "cs");
        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.rows().get(0).get(0)).isEqualTo("ITM-001");
        assertThat(r.rows().get(0).get(1)).isEqualTo("Hydraulické čerpadlo");
    }

    @Test
    void orderByPreservesAndAppliesOnSourceData() {
        // ORDER BY DESCRIPTION (anglicky) → ITM-001 (Hydraulic), ITM-003 (Lock), ITM-002 (M12)
        var r = proxy.execute(
                "SELECT ITEMNUM, DESCRIPTION FROM ITEM ORDER BY DESCRIPTION", "cs");
        assertThat(r.rowCount()).isEqualTo(3);
        assertThat(r.rows().get(0).get(0)).isEqualTo("ITM-001");
        assertThat(r.rows().get(1).get(0)).isEqualTo("ITM-003");
        assertThat(r.rows().get(2).get(0)).isEqualTo("ITM-002");
    }

    @Test
    void missingTranslationFallsBackToSource() {
        // ITM-003 nemá překlad — vrací původní anglický text
        var r = proxy.execute(
                "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE ITEMNUM = 'ITM-003'",
                "cs");
        assertThat(r.rows().get(0).get(1)).isEqualTo("Lock Nut M10");
        assertThat(r.translationsApplied()).isZero();
    }

    @Test
    void noLangParameterReturnsOriginalRows() {
        // Žádný target lang = source lang (en) → žádná substituce
        var r = proxy.execute(
                "SELECT ITEMNUM, DESCRIPTION FROM ITEM WHERE ITEMNUM = 'ITM-001'",
                "en");
        assertThat(r.rows().get(0).get(1)).isEqualTo("Hydraulic Pump");
        assertThat(r.translationsApplied()).isZero();
    }

    @Test
    void rejectsNonSelectStatement() {
        assertThatThrownBy(() -> proxy.execute(
                "UPDATE ITEM SET DESCRIPTION = 'X' WHERE ITEMNUM = 'ITM-001'",
                "cs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SELECT");
    }

    @Test
    void rejectsDropStatement() {
        assertThatThrownBy(() -> proxy.execute(
                "DROP TABLE ITEM", "cs"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedSql() {
        assertThatThrownBy(() -> proxy.execute(
                "SELEKT ITEMNUM FROM ITEM", "cs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parse");
    }

    @Test
    void unknownTableReturnsRawRowsWithoutTranslation() {
        // Pomocná tabulka mimo katalogovou konfiguraci
        new JdbcTemplate(ds).execute(
                "CREATE TABLE LOCATION (LOCNUM VARCHAR(32), NAME VARCHAR(200))");
        new JdbcTemplate(ds).update(
                "INSERT INTO LOCATION VALUES ('L1', 'Warehouse A')");
        var r = proxy.execute(
                "SELECT LOCNUM, NAME FROM LOCATION", "cs");
        assertThat(r.rowCount()).isEqualTo(1);
        // Tabulka není konfigurovaná — bez překladu, ale dotaz funguje (pass-through)
        assertThat(r.rows().get(0).get(1)).isEqualTo("Warehouse A");
        assertThat(r.translationsApplied()).isZero();
    }
}
