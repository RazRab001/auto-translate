package cz.mendelu.auto.elasticsearch;

/**
 * Výsledek vyhledání v sémantické Translation Memory.
 *
 * @param hit               true pokud je nalezený záznam nad prahem podobnosti
 * @param score             kosinová podobnost (0..1)
 * @param cachedTranslation přeložený text (pouze pokud {@code hit=true})
 * @param cachedId          ID nalezeného záznamu
 * @param latencyMs         doba vyhledávání v ms
 */
public record CacheLookupResult(
        boolean hit,
        double score,
        String cachedTranslation,
        String cachedId,
        long latencyMs
) {
}
