package cz.mendelu.auto.catalog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Konfigurace katalogu pro plný flow:
 * <em>JDBC zdroj &rarr; multi-jazyčný překlad &rarr; ES storage &rarr; retrieval</em>
 * (služba pro centrální překlad katalogových dat z relační DB
 * do libovolného počtu cílových jazyků).
 *
 * <p>Příklad <code>application.yml</code>:
 * <pre>
 * app:
 *   catalog:
 *     id: maximo-items
 *     source-lang: en
 *     target-langs: [cs, de, sk, pl]
 *     tables:
 *       - name: ITEM
 *         id-column: ITEMNUM
 *         text-columns: [DESCRIPTION, LONGDESCRIPTION]
 * </pre>
 *
 * <p>Jeden běh {@link CatalogTranslationService#sync(String)} pak provede:
 * <ol>
 *   <li>JDBC SELECT všech řádků z konfigurované tabulky</li>
 *   <li>Pro každé textové pole: překlad do všech {@code target-langs}</li>
 *   <li>Zápis do Elastic TM (klíč: catalogId + recordId + fieldName + lang)</li>
 *   <li>Vystavení přes <code>GET /api/v1/catalogs/&#123;id&#125;/records?lang=cs</code></li>
 * </ol>
 */
@ConfigurationProperties(prefix = "app.catalog")
@Data
public class CatalogProperties {

    /** Unikátní identifikátor katalogu (např. "maximo-items", "sap-cpv"). */
    private String id = "default";

    /** ISO 639-1 kód zdrojového jazyka (typicky angličtina v EAM systémech). */
    private String sourceLang = "en";

    /** Cílové jazyky pro překlad. Každý záznam bude přeložen do všech jazyků v seznamu. */
    private List<String> targetLangs = new ArrayList<>();

    /** Definice tabulek pro čtení a překlad. */
    private List<TableConfig> tables = new ArrayList<>();

    /**
     * Maximální počet řádků čtených v jednom batch při {@code sync()}.
     * Při větších katalozích doporučeno zachovat batch ≤ 1000 pro
     * paměťovou stopu a postupný progress reporting.
     */
    private int batchSize = 500;

    /** Konfigurace jedné tabulky v katalogu. */
    @Data
    public static class TableConfig {
        /** Název tabulky / view (case-sensitive dle DB engine). */
        private String name;
        /** Sloupec s primárním klíčem řádku (string nebo numeric). */
        private String idColumn;
        /** Sloupce obsahující překládaný text (každý sloupec je překládán samostatně). */
        private List<String> textColumns = new ArrayList<>();
    }
}
