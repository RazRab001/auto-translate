package cz.mendelu.auto.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Vstupní DTO pro překladový požadavek z IBM Maximo.
 *
 * <p>Odpovídá payloadu, který odesílá MIF Invocation Channel.
 */
@Schema(description = "Požadavek na překlad přijímaný z IBM Maximo")
public record TranslationRequestDto(

        @Schema(description = "Identifikátor zdrojového systému",
                example = "MAXIMO_PRD")
        String systemId,

        @Schema(description = "Text k překladu",
                example = "Hydraulic pump 500W, heavy-duty",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 8000)
        String sourceText,

        @Schema(description = "ISO 639-1 kód zdrojového jazyka",
                example = "EN",
                defaultValue = "EN")
        String sourceLang,

        @Schema(description = "ISO 639-1 kód cílového jazyka",
                example = "CS",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String targetLang,

        @Schema(description = "Vynutit volání API i při dostupném cache hit",
                defaultValue = "false")
        Boolean forceApiCall
) {
    public TranslationRequestDto {
        if (sourceLang == null || sourceLang.isBlank()) {
            sourceLang = "EN";
        }
        if (forceApiCall == null) {
            forceApiCall = false;
        }
    }
}
