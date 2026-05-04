package cz.mendelu.auto.connectors;

import cz.mendelu.auto.connectors.exceptions.PermanentProviderException;
import cz.mendelu.auto.connectors.exceptions.TransientProviderException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * DeepL NMT konektor.
 *
 * <p>Volá DeepL API v2 pomocí neblokujícího WebClient. Podporuje
 * volitelný glosář — terminologické nahrazení přes parametr
 * {@code glossary_id}.
 *
 * <p>HTML formátování je zachováno přes {@code tag_handling=html}.
 *
 * <p>Aktivuje se při {@code ai.default-provider=deepl}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.default-provider", havingValue = "deepl")
public class DeepLConnector implements TranslationProvider {

    private final WebClient webClient;

    @Value("${ai.deepl.api-key}")
    private String apiKey;

    @Value("${ai.deepl.base-url}")
    private String baseUrl;

    @Value("${ai.deepl.glossary-id:}")
    private String glossaryId;

    @Override
    public String getName() {
        return glossaryId == null || glossaryId.isBlank() ? "deepl" : "deepl_glossary";
    }

    /**
     * Překlad přes DeepL s retry/backoff.
     *
     * <p>Retry: max 3 pokusy, exponenciální backoff (1s → 2s → 4s).
     * Pouze {@link TransientProviderException} se opakuje;
     * {@link PermanentProviderException} skončí okamžitě.
     */
    @Override
    @CircuitBreaker(name = "deepl")  // CB se otevírá při ≥50% failures v 10s
    @Retryable(
            retryFor = TransientProviderException.class,
            noRetryFor = PermanentProviderException.class,
            maxAttemptsExpression = "${ai.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${ai.retry.initial-delay-ms:1000}",
                    multiplierExpression = "${ai.retry.multiplier:2.0}",
                    maxDelayExpression = "${ai.retry.max-delay-ms:8000}"
            )
    )
    public TranslationResult translate(String text, String sourceLang, String targetLang) {
        long startNanos = System.nanoTime();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        form.add("source_lang", sourceLang);
        form.add("target_lang", targetLang);
        form.add("preserve_formatting", "1");
        form.add("tag_handling", "html");
        if (glossaryId != null && !glossaryId.isBlank()) {
            form.add("glossary_id", glossaryId);
        }

        try {
            DeepLResponse resp = webClient.post()
                    .uri(baseUrl + "/translate")
                    .header("Authorization", "DeepL-Auth-Key " + apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::map4xx)
                    .onStatus(HttpStatusCode::is5xxServerError, this::map5xx)
                    .bodyToMono(DeepLResponse.class)
                    .block();

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String translated = resp != null && resp.translations() != null
                    && !resp.translations().isEmpty()
                    ? resp.translations().get(0).text()
                    : "";
            return new TranslationResult(translated, getName(), elapsedMs, 1);
        } catch (WebClientRequestException e) {
            throw new TransientProviderException("DeepL network: " + e.getMessage(), e);
        }
    }

    private reactor.core.publisher.Mono<? extends Throwable> map4xx(
            org.springframework.web.reactive.function.client.ClientResponse resp) {
        int code = resp.statusCode().value();
        if (code == 429) {
            return resp.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(b -> new TransientProviderException(
                            "DeepL HTTP 429 rate limit: " + b));
        }
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(b -> new PermanentProviderException(
                        "DeepL HTTP " + code + ": " + b));
    }

    private reactor.core.publisher.Mono<? extends Throwable> map5xx(
            org.springframework.web.reactive.function.client.ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(b -> new TransientProviderException(
                        "DeepL HTTP " + resp.statusCode() + ": " + b));
    }

    /** DTO pro odpověď DeepL API v2. */
    public record DeepLResponse(List<Translation> translations) {
        public record Translation(
                @com.fasterxml.jackson.annotation.JsonProperty("detected_source_language")
                String detectedSourceLanguage,
                String text
        ) {}
    }
}
