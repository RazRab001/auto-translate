package cz.mendelu.auto.catalog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Multi-language Translation Memory pro katalogová data.
 *
 * <p>Oproti základnímu {@link cz.mendelu.auto.elasticsearch.TranslationRepository}
 * (anonymní TM s~lookupem přes vektorovou podobnost) tato implementace
 * poskytuje <em>strukturovaný retrieval</em> klíčovaný čtveřicí
 * <code>(catalogId, recordId, fieldName, targetLang)</code>.
 *
 * <p>Append-only sémantika: opakovaný překlad téhož klíče vytvoří novou
 * verzi (pro auditní stopu a compliance), ale
 * "aktivní" je vždy nejvyšší {@code version}. Tato in-memory implementace
 * používá {@code ConcurrentHashMap} (klíč → seznam verzí); v produkci je
 * mapována na Elasticsearch index s~term-level filtery a~řazením podle
 * {@code version DESC}.
 *
 * <p>Thread-safety: všechny operace jsou thread-safe (CHM + immutable
 * record kopie).
 */
@Slf4j
@Repository
public class CatalogTranslationRepository {

    /** key = compositeKey, value = seznam verzí (append-only). */
    private final ConcurrentMap<String, List<CatalogTranslation>> store =
            new ConcurrentHashMap<>();

    /**
     * Uloží nový záznam (nebo novou verzi existujícího klíče).
     *
     * @return uložený {@link CatalogTranslation} s~přiděleným UUID a~verzí
     */
    public CatalogTranslation save(
            String catalogId, String tableName, String recordId,
            String fieldName, String sourceLang, String targetLang,
            String sourceText, String translatedText,
            String provider, float[] sourceVector
    ) {
        String key = CatalogTranslation.key(catalogId, recordId, fieldName, targetLang);
        // compute() je atomic vůči CHM bucketu — bezpečně určí příští verzi
        // i~při concurrent zápisech ze~sync()/translate jobs.
        List<CatalogTranslation> versions = store.compute(key, (k, list) -> {
            List<CatalogTranslation> updated = list != null
                    ? new ArrayList<>(list) : new ArrayList<>();
            int nextVer = updated.isEmpty()
                    ? 1 : updated.get(updated.size() - 1).version() + 1;
            CatalogTranslation rec = new CatalogTranslation(
                    UUID.randomUUID().toString(),
                    catalogId, tableName, recordId, fieldName,
                    sourceLang, targetLang,
                    sourceText, translatedText, provider, sourceVector,
                    nextVer, Instant.now()
            );
            updated.add(rec);
            return updated;
        });
        return versions.get(versions.size() - 1);
    }

    /**
     * Vrátí nejnovější verzi pro daný klíč nebo {@code null} pokud neexistuje.
     */
    public CatalogTranslation findLatest(
            String catalogId, String recordId,
            String fieldName, String targetLang
    ) {
        List<CatalogTranslation> versions = store.get(
                CatalogTranslation.key(catalogId, recordId, fieldName, targetLang));
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        return versions.get(versions.size() - 1);
    }

    /**
     * Vrátí všechny záznamy katalogu v daném jazyce, seskupené podle recordId.
     *
     * <p>Užitečné pro endpoint
     * <code>GET /catalogs/&#123;id&#125;/records?lang=cs</code>
     * (vrací kompletní katalog v zvoleném jazyce).
     *
     * @return mapa <i>recordId → mapa fieldName → překlad</i>
     */
    public Map<String, Map<String, String>> findCatalogInLanguage(
            String catalogId, String targetLang
    ) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (List<CatalogTranslation> versions : store.values()) {
            if (versions.isEmpty()) continue;
            CatalogTranslation latest = versions.get(versions.size() - 1);
            if (!latest.catalogId().equals(catalogId)) continue;
            if (!latest.targetLang().equals(targetLang)) continue;
            result.computeIfAbsent(latest.recordId(), k -> new LinkedHashMap<>())
                    .put(latest.fieldName(), latest.translatedText());
        }
        return result;
    }

    /**
     * Vrátí všechny překlady jednoho zdrojového záznamu napříč jazyky.
     *
     * @return mapa <i>targetLang → mapa fieldName → překlad</i>
     */
    public Map<String, Map<String, String>> findAllLanguagesForRecord(
            String catalogId, String recordId
    ) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (List<CatalogTranslation> versions : store.values()) {
            if (versions.isEmpty()) continue;
            CatalogTranslation latest = versions.get(versions.size() - 1);
            if (!latest.catalogId().equals(catalogId)) continue;
            if (!latest.recordId().equals(recordId)) continue;
            result.computeIfAbsent(latest.targetLang(), k -> new LinkedHashMap<>())
                    .put(latest.fieldName(), latest.translatedText());
        }
        return result;
    }

    /**
     * Statistiky katalogu: počet unikátních recordId, polí a~jazyků.
     */
    public CatalogStats stats(String catalogId) {
        long records = store.values().stream()
                .filter(v -> !v.isEmpty())
                .map(v -> v.get(v.size() - 1))
                .filter(r -> r.catalogId().equals(catalogId))
                .map(CatalogTranslation::recordId)
                .distinct().count();
        long translations = store.values().stream()
                .filter(v -> !v.isEmpty())
                .map(v -> v.get(v.size() - 1))
                .filter(r -> r.catalogId().equals(catalogId))
                .count();
        List<String> langs = store.values().stream()
                .filter(v -> !v.isEmpty())
                .map(v -> v.get(v.size() - 1))
                .filter(r -> r.catalogId().equals(catalogId))
                .map(CatalogTranslation::targetLang)
                .distinct()
                .collect(Collectors.toList());
        return new CatalogStats(catalogId, records, translations, langs);
    }

    /** Vyčistí celý store (pro testy / re-sync). */
    public void clear() {
        store.clear();
    }

    /** Souhrnná statistika katalogu pro zdravotní endpoint. */
    public record CatalogStats(
            String catalogId,
            long uniqueRecords,
            long totalTranslations,
            List<String> languages
    ) {
    }
}
