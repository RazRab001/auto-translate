package cz.mendelu.auto.service;

import cz.mendelu.auto.connectors.TranslationProvider;
import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.connectors.exceptions.PermanentProviderException;
import cz.mendelu.auto.connectors.exceptions.TransientProviderException;
import cz.mendelu.auto.elasticsearch.CacheLookupResult;
import cz.mendelu.auto.elasticsearch.TranslationRepository;
import cz.mendelu.auto.elasticsearch.VectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Centrální orchestrátor překladového pipeline.
 *
 * <p>Plní celý workflow:
 * <ol>
 *   <li>Embedding zdrojového textu (VectorService)</li>
 *   <li>Vyhledání v sémantické TM (TranslationRepository.lookup)</li>
 *   <li>Při HIT (score ≥ threshold): vrátit cached překlad bez volání API</li>
 *   <li>Při MISS: HTML sanitizace → volání NMT provider → restore HTML
 *       → zápis do TM (append-only)</li>
 *   <li>Aktualizace stavu úlohy v {@link JobRegistry}</li>
 * </ol>
 *
 * <p>Anotace {@code @Async("translationTaskExecutor")} zajišťuje, že
 * metoda běží v dedikovaném {@code ThreadPool} (viz
 * {@link cz.mendelu.auto.config.AsyncConfig}), a nikoli na
 * hlavním HTTP request vlákně. Návratový typ {@link CompletableFuture}
 * umožňuje volajícímu sledovat dokončení úlohy.
 *
 * <p><b>Strategie zpracování chyb (fail-safe):</b> jakákoliv výjimka
 * z volání NMT API je převedena na stav {@code ERROR} v auditním logu;
 * výjimka NESMÍ probublat na hlavní vlákno MIF, aby nezpůsobila
 * chybné ukončení transakce v Maximu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationOrchestrator {

    private final VectorService vectorService;
    private final TranslationRepository repository;
    private final TranslationProvider provider;
    private final HtmlSanitizer htmlSanitizer;
    private final JobRegistry jobRegistry;

    @Value("${app.cache.similarity-threshold:0.82}")
    private double similarityThreshold;

    /**
     * Asynchronní zpracování překladové úlohy.
     *
     * @param jobId       unikátní ID úlohy (vrácené v HTTP\,202)
     * @param sourceText  zdrojový text
     * @param sourceLang  zdrojový jazyk
     * @param targetLang  cílový jazyk
     * @param forceApi    pokud true, obejít cache a vždy volat API
     * @return CompletableFuture pro sledování dokončení
     */
    @Async("translationTaskExecutor")
    public CompletableFuture<Void> processAsync(
            String jobId,
            String sourceText,
            String sourceLang,
            String targetLang,
            boolean forceApi
    ) {
        MDC.put("jobId", jobId);
        long startNanos = System.nanoTime();
        try {
            // 1. Embedding zdrojového textu
            float[] vector = vectorService.embed(sourceText);

            // 2. Lookup v sémantické TM
            CacheLookupResult lookup = forceApi
                    ? new CacheLookupResult(false, 0.0, null, null, 0L)
                    : repository.lookup(vector, similarityThreshold);

            String translation;
            String providerUsed;
            int attempts = 0;

            if (lookup.hit()) {
                // 3a. Cache HIT — vrátit bez volání API
                translation = lookup.cachedTranslation();
                providerUsed = "cache";
                MDC.put("cacheHit", "true");
                log.info("Cache HIT (score={}): jobId={}", lookup.score(), jobId);
            } else {
                // 3b. Cache MISS — sanitizace, volání API, obnovení, zápis
                MDC.put("cacheHit", "false");
                String sanitized = htmlSanitizer.sanitize(sourceText);
                TranslationResult result;
                try {
                    result = provider.translate(sanitized, sourceLang, targetLang);
                } catch (TransientProviderException | PermanentProviderException e) {
                    log.error("Provider {} failed for jobId={}: {}",
                              provider.getName(), jobId, e.getMessage());
                    jobRegistry.markError(jobId, e.getMessage());
                    return CompletableFuture.completedFuture(null);
                }
                translation = htmlSanitizer.restore(result.text());
                providerUsed = result.provider();
                attempts = result.attempts();
                MDC.put("provider", providerUsed);
                MDC.put("latencyMs", String.valueOf(result.latencyMs()));

                // 4. Append-only zápis do TM (auditní stopa)
                repository.save(sourceText, translation, vector,
                        sourceLang, targetLang, providerUsed);
            }

            long totalMs = (System.nanoTime() - startNanos) / 1_000_000L;
            jobRegistry.markDone(jobId, translation, providerUsed,
                    lookup.hit(), lookup.score(), totalMs, attempts);
            log.info("Job DONE: jobId={}, provider={}, totalMs={}, cacheHit={}",
                    jobId, providerUsed, totalMs, lookup.hit());
        } catch (Exception unexpected) {
            log.error("Unexpected error in jobId=" + jobId, unexpected);
            jobRegistry.markError(jobId, unexpected.getMessage());
        } finally {
            MDC.clear();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Synchronní variant pro testy a HTTP {@code /sync} endpoint.
     */
    public TranslationResult processSync(
            String sourceText, String sourceLang, String targetLang
    ) {
        float[] vector = vectorService.embed(sourceText);
        CacheLookupResult lookup = repository.lookup(vector, similarityThreshold);
        if (lookup.hit()) {
            return new TranslationResult(
                    lookup.cachedTranslation(), "cache",
                    lookup.latencyMs(), 1
            );
        }
        String sanitized = htmlSanitizer.sanitize(sourceText);
        TranslationResult result = provider.translate(sanitized, sourceLang, targetLang);
        String restored = htmlSanitizer.restore(result.text());
        repository.save(sourceText, restored, vector,
                sourceLang, targetLang, result.provider());
        return new TranslationResult(restored, result.provider(),
                result.latencyMs(), result.attempts());
    }
}
