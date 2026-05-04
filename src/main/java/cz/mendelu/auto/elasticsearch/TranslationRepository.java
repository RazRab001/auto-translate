package cz.mendelu.auto.elasticsearch;

import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Translation Memory repository.
 *
 * <p>Tato implementace používá <b>in-memory store</b> s lineárním
 * vyhledáváním pro jednoduchost prototypu. V produkci je pole
 * {@code sourceVector} indexováno v Elasticsearch
 * (typ {@code dense_vector}, similarity {@code cosine}, HNSW),
 * kde dotaz {@code knn} (k=1) má latence < 50 ms na 100 000 záznamů.
 *
 * <p>Architektura prototypu zachovává stejné rozhraní
 * ({@code lookup}, {@code save}), takže přechod na Elasticsearch
 * je transparentní (jediná změna je injekce {@code ElasticsearchOperations}
 * a překlad metody {@code lookup} na nativní kNN dotaz).
 */
@Slf4j
@Repository
public class TranslationRepository {

    private final List<TranslationRecord> store = new ArrayList<>();

    @Value("${app.cache.in-memory-capacity:10000}")
    private int capacity;

    /**
     * Vyhledá nejvíce podobný záznam v TM dle kosinové podobnosti.
     *
     * <p>V produkci přes Elasticsearch:
     * <pre>{@code
     * { "knn": { "field": "sourceVector",
     *            "query_vector": <queryVector>,
     *            "k": 1, "num_candidates": 100 } }
     * }</pre>
     *
     * @param queryVector vektor dotazu (1536 dim pro OpenAI embedding)
     * @param threshold   minimální kosinová podobnost pro hit
     * @return výsledek lookupu (hit/miss, score, cached translation)
     */
    public synchronized CacheLookupResult lookup(float[] queryVector, double threshold) {
        long start = System.nanoTime();
        if (store.isEmpty()) {
            return new CacheLookupResult(false, 0.0, null, null, elapsed(start));
        }
        TranslationRecord best = null;
        double bestScore = -1.0;
        for (TranslationRecord rec : store) {
            double score = cosine(queryVector, rec.sourceVector());
            if (score > bestScore) {
                bestScore = score;
                best = rec;
            }
        }
        boolean hit = best != null && bestScore >= threshold;
        return new CacheLookupResult(
                hit,
                bestScore,
                hit && best != null ? best.translatedText() : null,
                hit && best != null ? best.id() : null,
                elapsed(start)
        );
    }

    /**
     * Append-only zápis nového záznamu do TM.
     *
     * @return ID nově vytvořeného záznamu
     */
    public synchronized String save(
            String sourceText,
            String translatedText,
            float[] sourceVector,
            String sourceLang,
            String targetLang,
            String provider
    ) {
        if (store.size() >= capacity) {
            log.warn("TM at capacity ({}), evicting oldest entry", capacity);
            store.remove(0);
        }
        String id = UUID.randomUUID().toString();
        store.add(new TranslationRecord(
                id, sourceText, translatedText, sourceLang, targetLang,
                provider, sourceVector, 1, Instant.now()
        ));
        return id;
    }

    /** Vrací aktuální počet záznamů v TM. */
    public synchronized int count() {
        return store.size();
    }

    /** Vyprázdní TM (pouze pro testy a auditní reset). */
    public synchronized void clear() {
        store.clear();
    }

    /** Kosinová podobnost dvou normalizovaných vektorů. */
    private double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
