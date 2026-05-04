package cz.mendelu.auto.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API pro full-catalog překladový flow:
 * <em>JDBC zdroj &rarr; multi-jazyčný překlad &rarr; ES storage &rarr; retrieval</em>.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>{@code GET  /api/v1/catalogs/&#123;id&#125;/metadata}
 *       — vrací schéma zdrojové DB (tabulky a~sloupce)</li>
 *   <li>{@code POST /api/v1/catalogs/&#123;id&#125;/sync}
 *       — spustí překlad do všech nakonfigurovaných cílových jazyků
 *       (synchronně, vrací sync report)</li>
 *   <li>{@code POST /api/v1/catalogs/&#123;id&#125;/sync/async}
 *       — totéž asynchronně, vrací HTTP 202</li>
 *   <li>{@code GET  /api/v1/catalogs/&#123;id&#125;/records}
 *       — vrací všechny řádky katalogu v~zadaném jazyce
 *       ({@code ?lang=cs|de|sk|...})</li>
 *   <li>{@code GET  /api/v1/catalogs/&#123;id&#125;/records/&#123;recordId&#125;}
 *       — vrátí překlady jednoho záznamu napříč všemi uloženými jazyky</li>
 *   <li>{@code GET  /api/v1/catalogs/&#123;id&#125;/stats}
 *       — souhrnné statistiky katalogu</li>
 * </ul>
 *
 * <p>Stejná zdrojová DB může být přeložena do libovolného počtu jazyků
 * (každý {@code targetLang} z~konfigurace je samostatný retrieval keyspace).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/catalogs")
@RequiredArgsConstructor
@Tag(name = "Catalog Translation API",
        description = "Full DB-driven multi-language translation pipeline")
public class CatalogController {

    private final CatalogReader catalogReader;
    private final CatalogTranslationService translationService;
    private final CatalogTranslationRepository repository;
    private final CatalogProperties properties;

    @Operation(summary = "Schéma zdrojové DB",
            description = "Vrací mapu tabulek a~jejich sloupců. Užitečné "
                    + "pro inspekci zdrojové DB a~validaci konfigurace.")
    @GetMapping("/{catalogId}/metadata")
    public ResponseEntity<Map<String, Object>> metadata(
            @PathVariable String catalogId
    ) {
        if (!properties.getId().equals(catalogId)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, List<String>> tables = catalogReader.readMetadata();
        return ResponseEntity.ok(Map.of(
                "catalogId", catalogId,
                "sourceLang", properties.getSourceLang(),
                "targetLangs", properties.getTargetLangs(),
                "configuredTables", properties.getTables(),
                "discoveredTables", tables
        ));
    }

    @Operation(summary = "Spustit synchronizaci katalogu",
            description = "Synchronně přečte zdrojovou DB a~přeloží každé "
                    + "konfigurované pole do všech target-langs. Existující "
                    + "překlady se shodným zdrojovým textem jsou přeskočeny "
                    + "(idempotence). Pro velké katalogy použít async variantu.")
    @PostMapping("/{catalogId}/sync")
    public ResponseEntity<CatalogTranslationService.CatalogSyncReport> sync(
            @PathVariable String catalogId
    ) {
        if (!properties.getId().equals(catalogId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(translationService.sync(catalogId));
    }

    @Operation(summary = "Asynchronní synchronizace katalogu",
            description = "Vrací HTTP 202 a~spustí synchronizaci ve~vyhrazeném "
                    + "thread poolu. Klient může později dotazovat "
                    + "/stats pro progress.")
    @PostMapping("/{catalogId}/sync/async")
    public ResponseEntity<Map<String, String>> syncAsync(
            @PathVariable String catalogId
    ) {
        if (!properties.getId().equals(catalogId)) {
            return ResponseEntity.notFound().build();
        }
        translationService.syncAsync(catalogId);
        return ResponseEntity.accepted().body(Map.of(
                "catalogId", catalogId,
                "status", "PROCESSING",
                "message", "Catalog sync running asynchronously. "
                        + "Poll GET /catalogs/" + catalogId + "/stats for progress."
        ));
    }

    @Operation(summary = "Vrátit katalog v~jednom cílovém jazyce",
            description = "Vrací všechny záznamy katalogu se~zvoleným "
                    + "cílovým jazykem ve~tvaru "
                    + "<i>recordId &rarr; { fieldName: translatedText }</i>.")
    @GetMapping("/{catalogId}/records")
    public ResponseEntity<Map<String, Object>> getCatalogInLanguage(
            @PathVariable String catalogId,
            @Parameter(description = "ISO 639-1 cílový jazyk", example = "cs")
            @RequestParam String lang
    ) {
        if (!properties.getId().equals(catalogId)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Map<String, String>> records =
                repository.findCatalogInLanguage(catalogId, lang);
        return ResponseEntity.ok(Map.of(
                "catalogId", catalogId,
                "language", lang,
                "recordCount", records.size(),
                "records", records
        ));
    }

    @Operation(summary = "Vrátit jeden záznam ve~všech jazycích",
            description = "Vrací mapu <i>targetLang &rarr; { fieldName: text }</i> "
                    + "pro jeden zdrojový záznam. Užitečné pro side-by-side "
                    + "zobrazení překladů v~UI.")
    @GetMapping("/{catalogId}/records/{recordId}")
    public ResponseEntity<Map<String, Object>> getRecordAllLanguages(
            @PathVariable String catalogId,
            @PathVariable String recordId
    ) {
        if (!properties.getId().equals(catalogId)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Map<String, String>> langs =
                repository.findAllLanguagesForRecord(catalogId, recordId);
        if (langs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "catalogId", catalogId,
                "recordId", recordId,
                "translations", langs
        ));
    }

    @Operation(summary = "Statistiky katalogu",
            description = "Souhrn počtu záznamů, překladů a~jazyků v~katalogu.")
    @GetMapping("/{catalogId}/stats")
    public ResponseEntity<CatalogTranslationRepository.CatalogStats> stats(
            @PathVariable String catalogId
    ) {
        if (!properties.getId().equals(catalogId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.stats(catalogId));
    }
}
