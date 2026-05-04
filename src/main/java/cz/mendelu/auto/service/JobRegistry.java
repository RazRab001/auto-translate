package cz.mendelu.auto.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Registr asynchronních překladových úloh.
 *
 * <p>Implementace přes Caffeine cache s~TTL a~maximumSize, aby se
 * předešlo unbounded memory growth na produkčním deploymentu
 * (anti-pattern: {@code ConcurrentHashMap} bez eviction). Maximo
 * vytváří jobId jen pro asynchronní polling, takže expirace po~1\,h
 * je dostatečná; po~úspěšném zápisu zpět do~Maxima jobId již není
 * potřeba.
 *
 * <p>Konfigurovatelné v~{@code application.yml}:
 * <pre>
 *   app.jobs:
 *     ttl-minutes: 60       # délka života job recordu po~vytvoření
 *     max-entries: 100000   # horní limit (DoS-protection)
 * </pre>
 *
 * <p>Pro multi-instance deployment (Kubernetes ≥2 repliky) je nutno
 * sdílet stav přes Redis nebo Hazelcast~--- aktuální implementace je
 * single-process.
 */
@Slf4j
@Component
public class JobRegistry {

    public enum Status { PROCESSING, DONE, ERROR }

    /**
     * TTL musí být &gt;= app.idempotency.ttl-hours (default 24h = 1440 min);
     * jinak REPLAY-path z~IdempotencyStore vrátí jobId, který už byl evictován
     * z~JobRegistry, a~klient uvidí 404 nebo navždy PROCESSING.
     * Default 1500 min (25 h) drží mírný overhead nad 24h idempotency oknem.
     */
    @Value("${app.jobs.ttl-minutes:1500}")
    private long ttlMinutes = 1500L;

    @Value("${app.jobs.max-entries:100000}")
    private long maxEntries = 100_000L;

    @Getter @Builder
    public static class JobRecord {
        private final String jobId;
        private final Status status;
        private final String translation;
        private final String provider;
        private final Boolean cacheHit;
        private final Double cacheScore;
        private final Long latencyMs;
        private final Integer attempts;
        private final String error;
        private final Instant createdAt;
        private final Instant completedAt;
    }

    private Cache<String, JobRecord> jobs;

    @PostConstruct
    public void init() {
        this.jobs = Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxEntries)
                .recordStats()
                .build();
        log.info("Initialized JobRegistry: ttl={}min, max={}",
                ttlMinutes, maxEntries);
    }

    public String createPending() {
        String jobId = "job-" + UUID.randomUUID();
        put(jobId);
        return jobId;
    }

    /** Zaregistruje job se zadaným ID (pro idempotency atomic flow). */
    public void put(String jobId) {
        jobs.put(jobId, JobRecord.builder()
                .jobId(jobId)
                .status(Status.PROCESSING)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Odstraní záznam (pro rollback při idempotency replay/mismatch).
     *
     * <p>Atomické check-and-remove přes
     * {@link java.util.concurrent.ConcurrentMap#compute Caffeine asMap.compute}
     * eliminuje TOCTOU okno mezi {@code getIfPresent} a~{@code invalidate},
     * kdy by paralelní vlákno mohlo mezitím provést {@code markDone}/{@code markError}
     * a~následný {@code invalidate} by tiše smazal úspěšný výsledek.
     * Pokud je status v~okamžiku compute jiný než PROCESSING, záznam se
     * nemění a~zaloguje se WARN.
     */
    public void remove(String jobId) {
        final boolean[] suppressed = {false};
        final Status[] observedStatus = {null};
        jobs.asMap().compute(jobId, (k, v) -> {
            if (v == null) {
                return null; // neexistuje, no-op
            }
            if (v.getStatus() != Status.PROCESSING) {
                suppressed[0] = true;
                observedStatus[0] = v.getStatus();
                return v; // ponecháme dokončený záznam
            }
            return null; // PROCESSING → atomicky odstraníme
        });
        if (suppressed[0]) {
            log.warn("JobRegistry.remove called on non-PROCESSING job {} "
                    + "(status={}); ignored to avoid losing completion record",
                    jobId, observedStatus[0]);
        }
    }

    public void markDone(String jobId, String translation, String provider,
                         boolean cacheHit, double cacheScore,
                         long latencyMs, int attempts) {
        JobRecord prev = jobs.getIfPresent(jobId);
        Instant created = prev != null ? prev.getCreatedAt() : Instant.now();
        jobs.put(jobId, JobRecord.builder()
                .jobId(jobId)
                .status(Status.DONE)
                .translation(translation)
                .provider(provider)
                .cacheHit(cacheHit)
                .cacheScore(cacheScore)
                .latencyMs(latencyMs)
                .attempts(attempts)
                .createdAt(created)
                .completedAt(Instant.now())
                .build());
    }

    public void markError(String jobId, String error) {
        JobRecord prev = jobs.getIfPresent(jobId);
        Instant created = prev != null ? prev.getCreatedAt() : Instant.now();
        jobs.put(jobId, JobRecord.builder()
                .jobId(jobId)
                .status(Status.ERROR)
                .error(error)
                .createdAt(created)
                .completedAt(Instant.now())
                .build());
    }

    public JobRecord get(String jobId) {
        return jobs.getIfPresent(jobId);
    }

    public boolean exists(String jobId) {
        return jobs.getIfPresent(jobId) != null;
    }

    public long size() {
        return jobs.estimatedSize();
    }

    /** Pouze pro testy. */
    public void clearAll() {
        jobs.invalidateAll();
    }
}
