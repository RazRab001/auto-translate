package cz.mendelu.auto.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Detailní stav úlohy vracený přes {@code GET /api/v1/jobs/{jobId}}.
 *
 * <p>Při {@code status=DONE} obsahuje přeložený text + audit info
 * (provider, cache hit/miss, latence, počet pokusů).
 */
@Schema(description = "Detailní stav úlohy")
public record JobStatusResponse(
        @Schema(example = "job-a3f9b12c-4d21-4e88-b7c0-9f2ae1d30014")
        String jobId,
        @Schema(example = "DONE")
        String status,
        @Schema(example = "Hydraulické čerpadlo 500 W, těžký provoz")
        String translation,
        @Schema(example = "deepl")
        String provider,
        @Schema(example = "true")
        Boolean cacheHit,
        @Schema(example = "0.987")
        Double cacheScore,
        @Schema(example = "146")
        Long latencyMs,
        @Schema(example = "1")
        Integer attempts,
        @Schema(description = "Chybová zpráva (pouze při status=ERROR)")
        String error
) {
}
