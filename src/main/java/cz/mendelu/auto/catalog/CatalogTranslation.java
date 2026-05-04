package cz.mendelu.auto.catalog;

import java.time.Instant;

/**
 * Per-record-per-language překladový záznam ukládaný v Elastic-TM.
 *
 * <p>Klíč pro retrieval: trojice (catalogId, recordId, fieldName) plus
 * cílový jazyk. Stejný řádek zdrojové DB může mít až N překladů
 * (jeden na každý {@code targetLang} z {@link CatalogProperties}).
 *
 * <p>Struktura odpovídá produkčnímu Elasticsearch indexu, kde:
 * <ul>
 *   <li>{@code catalogId/recordId/fieldName/targetLang} jsou indexovaná
 *       keyword pole pro přesný retrieval</li>
 *   <li>{@code sourceText/translatedText} jsou typu text pro full-text search</li>
 *   <li>{@code sourceVector} je {@code dense_vector(1536, cosine)} pro kNN</li>
 *   <li>{@code version + timestamp} implementují append-only audit log</li>
 * </ul>
 *
 * @param id              UUID záznamu
 * @param catalogId       identifikátor katalogu (z konfigurace)
 * @param tableName       zdrojová tabulka v externí DB
 * @param recordId        primární klíč zdrojového řádku
 * @param fieldName       název překládaného sloupce
 * @param sourceLang      ISO 639-1 zdrojový jazyk
 * @param targetLang      ISO 639-1 cílový jazyk
 * @param sourceText      originální text
 * @param translatedText  přeložený text
 * @param provider        identifikace NMT poskytovatele (deepl/google/openai/cache)
 * @param sourceVector    embedding zdrojového textu (může být null pro hash režim)
 * @param version         verze záznamu (auditní stopa, inkrementuje se při retranslate)
 * @param timestamp       čas vytvoření (UTC)
 */
public record CatalogTranslation(
        String id,
        String catalogId,
        String tableName,
        String recordId,
        String fieldName,
        String sourceLang,
        String targetLang,
        String sourceText,
        String translatedText,
        String provider,
        float[] sourceVector,
        int version,
        Instant timestamp
) {
    /** Kompozitní klíč pro retrieval (catalog + record + field + target lang). */
    public String compositeKey() {
        return key(catalogId, recordId, fieldName, targetLang);
    }

    /** Statická factory pro stejný klíč při lookupu. */
    public static String key(String catalogId, String recordId,
                             String fieldName, String targetLang) {
        return catalogId + "::" + recordId + "::" + fieldName + "::" + targetLang;
    }
}
