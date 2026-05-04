package cz.mendelu.auto.elasticsearch;

import java.time.Instant;

/**
 * Záznam Translation Memory v indexu Elasticsearch.
 *
 * <p>Klíčovou inovací (oproti tradičním TM systémům) je pole
 * {@code sourceVector} — hustá vektorová reprezentace zdrojového
 * textu generovaná embedding modelem (mpnet 768d nebo OpenAI 1536d).
 * To umožňuje sémantické vyhledávání duplicit nezachytitelných
 * pro přesnou textovou shodu.
 *
 * <p>Verzování ({@code version} + {@code timestamp}) implementuje
 * princip <em>append-only log</em> pro auditní stopu.
 *
 * @param id              unikátní identifikátor záznamu (UUID)
 * @param sourceText      původní text v EN
 * @param translatedText  přeložený text v cílovém jazyce
 * @param sourceLang      ISO 639-1 kód zdrojového jazyka
 * @param targetLang      ISO 639-1 kód cílového jazyka
 * @param provider        identifikace NMT poskytovatele
 * @param sourceVector    embedding zdrojového textu (1536 dim)
 * @param version         verze záznamu (auditní stopa)
 * @param timestamp       čas vytvoření záznamu (UTC)
 */
public record TranslationRecord(
        String id,
        String sourceText,
        String translatedText,
        String sourceLang,
        String targetLang,
        String provider,
        float[] sourceVector,
        int version,
        Instant timestamp
) {
}
