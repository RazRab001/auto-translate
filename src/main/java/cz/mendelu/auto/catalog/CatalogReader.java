package cz.mendelu.auto.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Čtení katalogových dat a metadat z externí relační DB přes JDBC.
 *
 * <p>Dvě hlavní role:
 * <ul>
 *   <li>{@link #readMetadata()} — introspekce schématu (tabulky a sloupce
 *       dostupné v aktuální DB) přes {@code DatabaseMetaData}.
 *       Slouží pro <code>GET /catalogs/&#123;id&#125;/metadata</code>
 *       a~validaci konfigurace.</li>
 *   <li>{@link #readRows(CatalogProperties.TableConfig)} — vytáhne všechny
 *       řádky konfigurované tabulky včetně překládaných sloupců.</li>
 * </ul>
 *
 * <p>Pro Maximo/MAS produkční nasazení stačí přepnout
 * <code>spring.datasource.url</code> na URL příslušné DB (Db2/Oracle/MSSQL)
 * a~přidat odpovídající JDBC driver. Implementace je <em>read-only</em>:
 * sidecar nikdy nezapisuje zpět do zdrojové DB (architektonický kontrakt
 * Sidecar pattern).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogReader {

    private final DataSource dataSource;

    /**
     * Vrací seznam všech tabulek v aktuální DB s jejich sloupci.
     *
     * @return mapa <i>tableName → seznam názvů sloupců</i>
     *         (zachovává order schématu)
     */
    public Map<String, List<String>> readMetadata() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();
            try (ResultSet tables = md.getTables(catalog, schema, "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    List<String> cols = new ArrayList<>();
                    try (ResultSet columns = md.getColumns(
                            catalog, schema, tableName, "%")) {
                        while (columns.next()) {
                            cols.add(columns.getString("COLUMN_NAME"));
                        }
                    }
                    result.put(tableName, cols);
                }
            }
            log.info("DB metadata: {} tables, schema={}", result.size(), schema);
        } catch (Exception e) {
            log.error("Failed to read DB metadata: {}", e.getMessage(), e);
            throw new IllegalStateException("DB metadata read failed", e);
        }
        return result;
    }

    /**
     * Načte všechny řádky konfigurované tabulky.
     *
     * @param table konfigurace tabulky (id sloupec + text sloupce)
     * @return seznam {@link CatalogRow} se všemi překládanými poli
     */
    public List<CatalogRow> readRows(CatalogProperties.TableConfig table) {
        // Bezpečné quotování identifikátorů (zabraňuje injekci přes config),
        // pro jednoduchost demo: validace whitelistu znaků.
        validateIdentifier(table.getName());
        validateIdentifier(table.getIdColumn());
        for (String c : table.getTextColumns()) {
            validateIdentifier(c);
        }

        StringBuilder sb = new StringBuilder("SELECT ");
        sb.append(table.getIdColumn());
        for (String col : table.getTextColumns()) {
            sb.append(", ").append(col);
        }
        sb.append(" FROM ").append(table.getName());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<CatalogRow> rows = jdbc.query(sb.toString(), (rs, rowNum) -> {
            String id = String.valueOf(rs.getObject(table.getIdColumn()));
            Map<String, String> fields = new LinkedHashMap<>();
            for (String col : table.getTextColumns()) {
                String val = rs.getString(col);
                if (val != null && !val.isBlank()) {
                    fields.put(col, val);
                }
            }
            return new CatalogRow(table.getName(), id, fields);
        });
        log.info("Read {} rows from table {}", rows.size(), table.getName());
        return rows;
    }

    /** Whitelist validace identifikátoru (písmena, číslice, podtržítko). */
    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException(
                    "Invalid SQL identifier (configuration error): " + name);
        }
    }
}
