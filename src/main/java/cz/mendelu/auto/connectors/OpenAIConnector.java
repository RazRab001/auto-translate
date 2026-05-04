package cz.mendelu.auto.connectors;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions konektor pro překlad přes GPT-4o-mini.
 *
 * <p>Klíčový prvek je <b>HTML-aware systémový prompt</b>:
 * bez explicitní instrukce GPT modely systematicky odstraňují HTML
 * tagy, což pro pole {@code LONGDESCRIPTION} v Maximo katastrofálně
 * snižuje BLEU (z ~36 na ~6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.default-provider", havingValue = "openai")
public class OpenAIConnector implements TranslationProvider {

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are a professional Czech technical translator specializing in
        industrial maintenance terminology. Translate the following text
        from %s to %s.

        Rules:
        1. Preserve ALL HTML tags (<b>, <p>, <div>, <br>) EXACTLY as in source.
        2. Preserve ALL numbers, units, and standard codes (ISO 4014, PN16,
           M10, DIN 985) UNCHANGED.
        3. Use established Czech industrial terminology per CSN EN 13306.
        4. Use natural Czech sentence structure, not literal word-by-word.
        5. Return ONLY the translation, no explanation, no quotes.
        """;

    private final WebClient webClient;

    @Value("${ai.openai.api-key}")
    private String apiKey;

    @Value("${ai.openai.base-url}")
    private String baseUrl;

    @Value("${ai.openai.chat-model}")
    private String chatModel;

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    @CircuitBreaker(name = "openai")
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
        String system = SYSTEM_PROMPT_TEMPLATE.formatted(sourceLang, targetLang);

        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user",   "content", text)
                ),
                "temperature", 0.0
        );

        try {
            ChatResponse resp = webClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, r -> r.bodyToMono(String.class)
                            .map(b -> r.statusCode().value() == 429
                                    ? new TransientProviderException("OpenAI 429: " + b)
                                    : new PermanentProviderException("OpenAI HTTP "
                                        + r.statusCode().value() + ": " + b)))
                    .onStatus(HttpStatusCode::is5xxServerError, r -> r.bodyToMono(String.class)
                            .map(b -> new TransientProviderException(
                                    "OpenAI HTTP " + r.statusCode().value() + ": " + b)))
                    .bodyToMono(ChatResponse.class)
                    .block();

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String translated = resp != null && resp.choices() != null
                    && !resp.choices().isEmpty()
                    ? resp.choices().get(0).message().content().trim()
                    : "";
            return new TranslationResult(translated, getName(), elapsedMs, 1);
        } catch (WebClientRequestException e) {
            throw new TransientProviderException("OpenAI network: " + e.getMessage(), e);
        }
    }

    /** DTO pro Chat Completions odpověď. */
    public record ChatResponse(List<Choice> choices) {
        public record Choice(Message message) {}
        public record Message(String role, String content) {}
    }
}
