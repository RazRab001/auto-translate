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

import java.util.List;

/**
 * Google Cloud Translation v2 konektor.
 *
 * <p>Pozn.: web UI translate.google.com a Cloud Translation API jsou
 * <b>různé kanály</b> s odlišnou kvalitou (Δ BLEU = 6,5).
 * Tento konektor volá oficiální Cloud Translation API v2.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.default-provider", havingValue = "google")
public class GoogleConnector implements TranslationProvider {

    private final WebClient webClient;

    @Value("${ai.google.api-key}")
    private String apiKey;

    @Value("${ai.google.base-url}")
    private String baseUrl;

    @Override
    public String getName() {
        return "google";
    }

    @Override
    @CircuitBreaker(name = "google")
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
        form.add("q", text);
        form.add("source", sourceLang.toLowerCase());
        form.add("target", targetLang.toLowerCase());
        form.add("format", "html");

        try {
            GoogleResponse resp = webClient.post()
                    .uri(baseUrl + "?key=" + apiKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, r -> r.bodyToMono(String.class)
                            .map(b -> r.statusCode().value() == 429
                                    ? new TransientProviderException("Google 429: " + b)
                                    : new PermanentProviderException("Google HTTP "
                                        + r.statusCode().value() + ": " + b)))
                    .onStatus(HttpStatusCode::is5xxServerError, r -> r.bodyToMono(String.class)
                            .map(b -> new TransientProviderException(
                                    "Google HTTP " + r.statusCode().value() + ": " + b)))
                    .bodyToMono(GoogleResponse.class)
                    .block();

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String translated = resp != null && resp.data() != null
                    && resp.data().translations() != null
                    && !resp.data().translations().isEmpty()
                    ? resp.data().translations().get(0).translatedText()
                    : "";
            return new TranslationResult(translated, getName(), elapsedMs, 1);
        } catch (WebClientRequestException e) {
            throw new TransientProviderException("Google network: " + e.getMessage(), e);
        }
    }

    /** DTO pro Google Cloud Translation v2 odpověď. */
    public record GoogleResponse(Data data) {
        public record Data(List<Translation> translations) {}
        public record Translation(
                @com.fasterxml.jackson.annotation.JsonProperty("translatedText")
                String translatedText
        ) {}
    }
}
