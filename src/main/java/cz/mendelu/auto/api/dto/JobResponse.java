package cz.mendelu.auto.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Okamžitá odpověď HTTP 202 Accepted s ID asynchronní úlohy.
 *
 * <p>Middleware vrátí jobId ihned po přijetí požadavku, samotné
 * zpracování probíhá v ThreadPool.
 */
@Schema(description = "HTTP 202 odpověď: úloha přijata k asynchronnímu zpracování")
public record JobResponse(
        @Schema(description = "Unikátní ID úlohy",
                example = "job-a3f9b12c-4d21-4e88-b7c0-9f2ae1d30014")
        String jobId,

        @Schema(description = "Aktuální stav (PROCESSING / DONE / ERROR)",
                example = "PROCESSING")
        String status,

        @Schema(description = "Odhadovaný čas dokončení v ms",
                example = "1500")
        Integer estimatedCompletionMs
) {
}
