package cz.mendelu.auto.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL-proxy: <em>drop-in transparentní překladová vrstva</em>.
 *
 * <p>Existující aplikace (např. Maximo report, BI nástroj, custom dashboard)
 * posílá svůj originální {@code SELECT} dotaz na sidecar místo na zdrojovou DB.
 * Sidecar:
 * <ol>
 *   <li>Naparsuje SQL přes JSqlParser a~zjistí, které tabulky a~sloupce
 *       jsou v~odpovědi,</li>
 *   <li>Vykoná dotaz <em>beze změny</em> na zdrojové DB (filtry,
 *       agregace, ORDER BY zůstávají),</li>
 *   <li>Pro každý řádek odpovědi nahradí hodnoty <em>textových sloupců</em>
 *       (definovaných v {@link CatalogProperties}) překladem z~Elastic-TM
 *       v~požadovaném jazyce. Pokud překlad chybí, vrací originál
 *       (graceful degradation),</li>
 *   <li>Vrátí výsledek v~JSON formátu kompatibilním s~Elasticsearch SQL
 *       ({@code columns + rows}).</li>
 * </ol>
 *
 * <p>Tím lze existující aplikaci přepnout na lokalizovaný výstup změnou
 * jediného endpointu, bez modifikace jejích SQL dotazů. WHERE/JOIN/ORDER BY
 * pracují na <em>kanonických</em> (zdrojových) datech, takže filtr-semantika
 * zůstává konzistentní napříč jazyky.
 *
 * <p><b>Bezpečnost:</b> JSqlParser nejdřív validuje, že jde o {@code SELECT}
 * (DDL/DML/DROP/UPDATE/INSERT jsou odmítnuty). Sidecar nikdy nezapisuje
 * do zdrojové DB (architektonický kontrakt Sidecar pattern).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlProxyService {

    private final DataSource dataSource;
    private final CatalogProperties properties;
    private final CatalogTranslationRepository repository;

    /**
     * Vykoná SQL dotaz se substitucí překladů textových sloupců.
     *
     * @param sql        originální SELECT dotaz
     * @param targetLang ISO 639-1 cílový jazyk pro substituci
     * @return výsledek se sloupci, řádky a~metadaty
     */
    public SqlProxyResult execute(String sql, String targetLang) {
        long start = System.nanoTime();

        // 1. Parse + validace: jen SELECT
        Select select = parseSelect(sql);
        String tableName = extractMainTable(select);

        // 2. Najdeme konfiguraci tabulky (id-column + text-columns)
        CatalogProperties.TableConfig tableCfg = properties.getTables().stream()
                .filter(t -> t.getName().equalsIgnoreCase(tableName))
                .findFirst()
                .orElse(null);

        // 3. Vykonání originálního SQL na zdrojové DB (read-only)
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rawRows = jdbc.query(sql, new ColumnMapRowMapper());

        if (rawRows.isEmpty()) {
            return new SqlProxyResult(
                    List.of(), List.of(), tableName, targetLang,
                    0, 0, elapsedMs(start));
        }

        // 4. Substituce překladů — jen pokud máme konfiguraci tabulky
        int translationsApplied = 0;
        if (tableCfg != null && targetLang != null && !targetLang.isBlank()) {
            translationsApplied = applyTranslations(
                    rawRows, tableCfg, targetLang);
        }

        // 5. Sestavení výsledku (sloupce z první řádky preserve order)
        List<String> columnNames = new ArrayList<>(rawRows.get(0).keySet());
        List<List<Object>> rows = new ArrayList<>(rawRows.size());
        for (Map<String, Object> r : rawRows) {
            List<Object> row = new ArrayList<>(columnNames.size());
            for (String col : columnNames) {
                row.add(r.get(col));
            }
            rows.add(row);
        }

        return new SqlProxyResult(
                columnNames, rows, tableName, targetLang,
                rawRows.size(), translationsApplied, elapsedMs(start));
    }

    /**
     * Pro každý řádek a~každý překládaný sloupec se pokusí najít
     * překlad v~ES-TM podle (catalogId, recordId, fieldName, targetLang).
     * Pokud překlad existuje, nahradí hodnotu; jinak ponechá originál.
     */
    private int applyTranslations(
            List<Map<String, Object>> rows,
            CatalogProperties.TableConfig tableCfg,
            String targetLang
    ) {
        // Case-insensitive lookup mapy (DB může vrátit COL nebo col)
        Map<String, String> normalizedKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rows.get(0).entrySet()) {
            normalizedKeys.put(e.getKey().toUpperCase(), e.getKey());
        }
        String idKey = normalizedKeys.get(tableCfg.getIdColumn().toUpperCase());
        if (idKey == null) {
            log.debug("ID column {} not in result set, skipping translation",
                    tableCfg.getIdColumn());
            return 0;
        }

        int applied = 0;
        for (Map<String, Object> row : rows) {
            Object idVal = row.get(idKey);
            if (idVal == null) continue;
            String recordId = String.valueOf(idVal);

            for (String textColumn : tableCfg.getTextColumns()) {
                String resultKey = normalizedKeys.get(textColumn.toUpperCase());
                if (resultKey == null) continue;          // sloupec není v SELECT
                if (row.get(resultKey) == null) continue; // NULL hodnota

                CatalogTranslation t = repository.findLatest(
                        properties.getId(), recordId, textColumn, targetLang);
                if (t != null) {
                    row.put(resultKey, t.translatedText());
                    applied++;
                }
                // jinak ponecháme originál (graceful degradation)
            }
        }
        return applied;
    }

    /** Naparsuje SQL a~ověří, že je to SELECT. */
    private Select parseSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Empty SQL query");
        }
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException(
                    "SQL parse error: " + e.getMessage(), e);
        }
        if (!(stmt instanceof Select sel)) {
            throw new IllegalArgumentException(
                    "Only SELECT statements are allowed (got: "
                    + stmt.getClass().getSimpleName() + ")");
        }
        return sel;
    }

    /** Vrátí název hlavní tabulky (FROM clause) nebo {@code null}. */
    private String extractMainTable(Select select) {
        TablesNamesFinder finder = new TablesNamesFinder();
        // Cast na Statement disambiguuje overload getTables(Statement)
        // vs getTables(Expression) — potřebujeme tu pro Statement.
        java.util.Set<String> tables = finder.getTables((Statement) select);
        if (tables == null || tables.isEmpty()) return null;
        return tables.iterator().next();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Výsledek SQL proxy v~Elasticsearch-SQL kompatibilním formátu.
     *
     * @param columns               názvy sloupců v~pořadí jak vrátil zdroj
     * @param rows                  data ve~tvaru list-of-list (row-major)
     * @param table                 hlavní tabulka FROM clause (informativní)
     * @param language              použitý cílový jazyk pro překlad
     * @param rowCount              celkový počet řádků
     * @param translationsApplied   počet substitucí (přeložené buňky)
     * @param elapsedMs             celkový čas v~milisekundách
     */
    public record SqlProxyResult(
            List<String> columns,
            List<List<Object>> rows,
            String table,
            String language,
            int rowCount,
            int translationsApplied,
            long elapsedMs
    ) {
    }
}
