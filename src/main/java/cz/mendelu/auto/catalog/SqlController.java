package cz.mendelu.auto.catalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SQL-proxy REST endpoint &mdash; <em>drop-in</em> překladový shim pro
 * existující aplikace.
 *
 * <p>Příklad použití:
 * <pre>
 * POST /api/v1/sql?lang=cs
 * Content-Type: application/json
 *
 * { "query": "SELECT ITEMNUM, DESCRIPTION FROM ITEM
 *             WHERE CATEGORY = 'HYDRAULICS'" }
 * </pre>
 *
 * <p>Odpověď je kompatibilní s~Elasticsearch SQL ({@code columns} +
 * {@code rows}); textové sloupce jsou nahrazeny překladem do {@code lang},
 * filtry/JOIN/ORDER BY zůstávají vyhodnoceny na zdrojové DB beze změny.
 *
 * <p>Migrace existující aplikace: stačí přepnout SQL endpoint URL
 * z~přímé DB na <code>http://sidecar/api/v1/sql</code>; SQL dotazy
 * není třeba upravovat.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sql")
@RequiredArgsConstructor
@Tag(name = "SQL Proxy",
        description = "Drop-in transparentní překladová vrstva nad zdrojovou DB")
public class SqlController {

    private final SqlProxyService sqlProxy;
    private final CatalogProperties properties;

    @Operation(summary = "Spustit SELECT s~překladem textových sloupců",
            description = "Vykoná originální SQL na zdrojové DB; textové "
                    + "sloupce v~odpovědi nahradí překladem do {@code lang}. "
                    + "WHERE / JOIN / ORDER BY pracují na kanonických (zdrojových) "
                    + "datech. Akceptuje pouze SELECT (DDL/DML jsou odmítnuty).",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "query": "SELECT ITEMNUM, DESCRIPTION, LONGDESCRIPTION FROM ITEM WHERE CATEGORY = 'HYDRAULICS'"
                            }
                            """))))
    @PostMapping
    public ResponseEntity<?> executeSql(
            @Parameter(description = "ISO 639-1 cílový jazyk překladu",
                    example = "cs", required = false)
            @RequestParam(required = false) String lang,
            @Parameter(hidden = true)
            @RequestHeader(value = "Accept-Language", required = false)
            String acceptLanguage,
            @org.springframework.web.bind.annotation.RequestBody SqlRequest body
    ) {
        if (body == null || body.query() == null || body.query().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing_query",
                    "message", "Field 'query' is required."));
        }
        String effectiveLang = lang != null && !lang.isBlank()
                ? lang
                : (acceptLanguage != null ? firstAcceptedLang(acceptLanguage) : null);
        if (effectiveLang == null) {
            // Není zadán jazyk — vrátíme originál (transparentní pass-through)
            effectiveLang = properties.getSourceLang();
        }
        try {
            SqlProxyService.SqlProxyResult result = sqlProxy.execute(
                    body.query(), effectiveLang);
            log.info("SQL proxy: lang={}, rows={}, translations={}, elapsedMs={}",
                    effectiveLang, result.rowCount(),
                    result.translationsApplied(), result.elapsedMs());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("SQL proxy bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "bad_request",
                    "message", e.getMessage()));
        } catch (Exception e) {
            log.error("SQL proxy execution failure", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "execution_error",
                    "message", e.getMessage()));
        }
    }

    /** Vyzvedne první ISO kód z {@code Accept-Language: cs-CZ,cs;q=0.9,en;q=0.8}. */
    private String firstAcceptedLang(String header) {
        String first = header.split(",")[0].trim();
        int dash = first.indexOf('-');
        if (dash > 0) {
            first = first.substring(0, dash);
        }
        int semi = first.indexOf(';');
        if (semi > 0) {
            first = first.substring(0, semi);
        }
        return first.isBlank() ? null : first.toLowerCase();
    }

    /**
     * Body request pro SQL endpoint.
     *
     * @param query SELECT dotaz (bez středníku není povinný)
     */
    public record SqlRequest(@NotBlank String query) {
    }
}
