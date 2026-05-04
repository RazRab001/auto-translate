package cz.mendelu.auto.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo data preloader: po startu aplikace v <b>dev</b> profilu (nebo
 * pokud je {@code app.demo.preload-on-startup=true}) automaticky spustí
 * full sync katalogu.
 *
 * <p>Důvod: Swagger UI uživatel pak může <b>okamžitě</b> volat
 * <code>GET /catalogs/maximo-items/records?lang=cs</code> a~vidět hotová
 * data, bez nutnosti nejdřív manuálně volat <code>POST /sync</code>.
 *
 * <p>V produkčním profilu je preloader vypnutý &mdash; sync musí spustit
 * administrátor explicitně (chrání před nechtěným nákladem na NMT API
 * při restartu instance).
 */
@Slf4j
@Component
@Profile("!production")
@RequiredArgsConstructor
public class CatalogDemoPreloader implements ApplicationRunner {

    private final CatalogTranslationService catalogService;
    private final CatalogProperties properties;

    @Value("${app.demo.preload-on-startup:true}")
    private boolean preloadEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!preloadEnabled) {
            log.info("Demo preload DISABLED (app.demo.preload-on-startup=false)");
            return;
        }
        if (properties.getTables() == null || properties.getTables().isEmpty()) {
            log.info("No catalog tables configured; skipping demo preload");
            return;
        }
        try {
            log.info("Demo preload: starting catalog sync for '{}' "
                            + "(targetLangs={}). Swagger UI will have data ready.",
                    properties.getId(), properties.getTargetLangs());
            CatalogTranslationService.CatalogSyncReport report =
                    catalogService.sync(properties.getId());
            log.info("Demo preload DONE: translated={}, skipped={}, errors={}, "
                            + "elapsedMs={}",
                    report.translatedCount(), report.skippedCount(),
                    report.errorCount(), report.elapsedMs());
        } catch (Exception e) {
            // Soft fail: demo preload nesmí blokovat start aplikace
            log.warn("Demo preload failed (continuing without preloaded data): {}",
                    e.getMessage());
        }
    }
}
