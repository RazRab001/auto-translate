package cz.mendelu.auto.catalog;

import java.util.Map;

/**
 * Přečtený řádek z externí relační DB (před překladem).
 *
 * <p>Reprezentuje jeden záznam z konfigurované tabulky:
 * <ul>
 *   <li>{@link #tableName} — zdrojová tabulka (např. "ITEM")</li>
 *   <li>{@link #recordId} — primární klíč řádku (string forma)</li>
 *   <li>{@link #fields}   — mapa <i>fieldName → originální text</i> pro každý
 *       konfigurovaný textový sloupec; null hodnoty jsou vynechány</li>
 * </ul>
 *
 * <p>Tato struktura se předává do {@link CatalogTranslationService},
 * který pro každé pole spustí překlad do všech cílových jazyků.
 *
 * @param tableName název zdrojové tabulky
 * @param recordId  primární klíč řádku (jako string, pro univerzálnost)
 * @param fields    mapa <i>název pole → text</i>, hodnoty null jsou vyloučeny
 */
public record CatalogRow(
        String tableName,
        String recordId,
        Map<String, String> fields
) {
}
