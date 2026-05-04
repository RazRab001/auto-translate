package cz.mendelu.auto.catalog;

import cz.mendelu.auto.connectors.TranslationProvider;
import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.connectors.exceptions.PermanentProviderException;
import cz.mendelu.auto.connectors.exceptions.TransientProviderException;
import cz.mendelu.auto.elasticsearch.VectorService;
import cz.mendelu.auto.service.HtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrace plného flow:
 * <em>JDBC zdroj &rarr; multi-jazyčný překlad &rarr; ES storage</em>.
 *
 * <p>Sekvence:
 * <ol>
 *   <li>{@link CatalogReader#readRows} přečte všechny řádky konfigurované
 *       tabulky.</li>
 *   <li>Pro každé pole každého řádku spustí překlad do <em>všech</em>
 *       cílových jazyků (paralelizace přes {@code @Async}).</li>
 *   <li>Před voláním NMT API: HTML sanitizace pro zachování tagů
 *       v <code>LONGDESCRIPTION</code>.</li>
 *   <li>Po překladu: HTML restore + zápis do
 *       {@link CatalogTranslationRepository} (append-only audit log).</li>
 *   <li>Idempotence: pokud klíč
 *       <code>(catalogId, recordId, fieldName, targetLang)</code> už existuje
 *       a~zdrojový text se nezměnil, je překlad přeskočen (úspora API quoty).</li>
 * </ol>
 *
 * <p>Thread-safety: sám orchestrator je stateless; sdílený stav je v
 * {@code CatalogTranslationRepository}, který je thread-safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogTranslationService {

    private final CatalogProperties properties;
    private final CatalogReader catalogReader;
    private final CatalogTranslationRepository catalogRepo;
    private final TranslationProvider provider;
    private final VectorService vectorService;
    private final HtmlSanitizer htmlSanitizer;

    /**
     * Spustí plnou synchronizaci katalogu: přečte všechny tabulky a~přeloží
     * každé pole do všech nakonfigurovaných cílových jazyků.
     *
     * <p>Provádí se synchronně (vrací {@link CatalogSyncReport}); pro
     * dlouhé katalogy doporučeno volat přes
     * <code>POST /catalogs/sync/async</code> (viz
     * {@link CatalogController}), který vrací HTTP 202 a~spustí
     * {@link #syncAsync(String)} ve~vyhrazeném thread poolu.
     */
    public CatalogSyncReport sync(String catalogId) {
        if (!properties.getId().equals(catalogId)) {
            throw new IllegalArgumentException(
                    "Catalog id '" + catalogId + "' is not configured "
                            + "(expected: '" + properties.getId() + "')");
        }
        if (properties.getTargetLangs().isEmpty()) {
            throw new IllegalStateException(
                    "No target languages configured for catalog " + catalogId);
        }

        long start = System.nanoTime();
        AtomicInteger translated = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<String> errorMessages = new ArrayList<>();

        for (CatalogProperties.TableConfig table : properties.getTables()) {
            List<CatalogRow> rows = catalogReader.readRows(table);
            log.info("Catalog '{}' table '{}': {} rows × {} languages = {} units",
                    catalogId, table.getName(), rows.size(),
                    properties.getTargetLangs().size(),
                    rows.size() * properties.getTargetLangs().size());

            for (CatalogRow row : rows) {
                for (Map.Entry<String, String> field : row.fields().entrySet()) {
                    for (String targetLang : properties.getTargetLangs()) {
                        try {
                            ItemOutcome o = translateField(
                                    catalogId, table.getName(),
                                    row.recordId(), field.getKey(),
                                    field.getValue(), targetLang);
                            switch (o) {
                                case TRANSLATED -> translated.incrementAndGet();
                                case SKIPPED_UNCHANGED -> skipped.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            String msg = String.format(
                                    "%s.%s[%s] → %s: %s",
                                    table.getName(), field.getKey(),
                                    row.recordId(), targetLang, e.getMessage());
                            errorMessages.add(msg);
                            log.error("Translation failure: {}", msg);
                        }
                    }
                }
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        CatalogSyncReport report = new CatalogSyncReport(
                catalogId,
                translated.get(),
                skipped.get(),
                errors.get(),
                elapsedMs,
                errorMessages
        );
        log.info("Catalog sync DONE: {}", report);
        return report;
    }

    /**
     * Asynchronní variant {@link #sync(String)} pro dlouhé katalogy.
     * Vrací {@link CompletableFuture} pro pozorování dokončení; samotný
     * job běží v {@code translationTaskExecutor} thread poolu.
     */
    @Async("translationTaskExecutor")
    public CompletableFuture<CatalogSyncReport> syncAsync(String catalogId) {
        try {
            return CompletableFuture.completedFuture(sync(catalogId));
        } catch (Exception e) {
            log.error("Async catalog sync failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Přeloží jedno pole do jednoho cílového jazyka (s~deduplikací).
     */
    private ItemOutcome translateField(
            String catalogId, String tableName, String recordId,
            String fieldName, String sourceText, String targetLang
    ) throws TransientProviderException, PermanentProviderException {

        // Idempotence: pokud existuje předchozí překlad se shodným zdrojovým
        // textem, neprovádět nový API call (úspora kvóty + stabilní výstup).
        CatalogTranslation existing = catalogRepo.findLatest(
                catalogId, recordId, fieldName, targetLang);
        if (existing != null && existing.sourceText().equals(sourceText)) {
            return ItemOutcome.SKIPPED_UNCHANGED;
        }

        // Vlastní překlad: sanitizace → API → restore
        String sanitized = htmlSanitizer.sanitize(sourceText);
        TranslationResult result = provider.translate(
                sanitized, properties.getSourceLang(), targetLang);
        String translated = htmlSanitizer.restore(result.text());

        // Embedding pro auditní stopu + budoucí kNN deduplikaci
        float[] vector;
        try {
            vector = vectorService.embed(sourceText);
        } catch (Exception e) {
            log.warn("Embedding failed for {}/{}/{}: {} (storing without vector)",
                    catalogId, recordId, fieldName, e.getMessage());
            vector = null;
        }

        catalogRepo.save(catalogId, tableName, recordId, fieldName,
                properties.getSourceLang(), targetLang,
                sourceText, translated, result.provider(), vector);
        return ItemOutcome.TRANSLATED;
    }

    /** Výsledek zpracování jednoho překladového jobu. */
    private enum ItemOutcome {
        TRANSLATED,
        SKIPPED_UNCHANGED
    }

    /**
     * Souhrnný report po dokončení {@link #sync}.
     *
     * @param catalogId         identifikátor katalogu
     * @param translatedCount   počet úspěšně přeložených polí
     * @param skippedCount      počet polí přeskočených (idempotence)
     * @param errorCount        počet chyb
     * @param elapsedMs         celkový čas v~milisekundách
     * @param errorMessages     prvních N chybových zpráv (pro debug)
     */
    public record CatalogSyncReport(
            String catalogId,
            int translatedCount,
            int skippedCount,
            int errorCount,
            long elapsedMs,
            List<String> errorMessages
    ) {
    }
}
