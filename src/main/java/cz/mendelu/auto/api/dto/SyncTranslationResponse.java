package cz.mendelu.auto.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Odpověď synchronního endpointu {@code /sync} (pouze pro testy
 * a benchmarky latence).
 */
@Schema(description = "Synchronní výsledek překladu (jen pro testy/bench)")
public record SyncTranslationResponse(
        @Schema(example = "Hydraulické čerpadlo 500 W")
        String translation,
        @Schema(example = "deepl")
        String provider,
        @Schema(example = "287")
        Long latencyMs,
        @Schema(example = "1")
        Integer attempts
) {
}
