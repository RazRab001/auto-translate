package cz.mendelu.auto;

import cz.mendelu.auto.api.GlobalExceptionHandler;
import cz.mendelu.auto.connectors.exceptions.PermanentProviderException;
import cz.mendelu.auto.connectors.exceptions.TransientProviderException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prokazuje, že GlobalExceptionHandler:
 * <ul>
 *   <li>vrací 503 + Retry-After:30 pro CB OPEN,</li>
 *   <li>vrací 503 + Retry-After:60 pro Transient,</li>
 *   <li>vrací 502 pro Permanent,</li>
 *   <li>vrací 500 + correlationId pro neznámou výjimku
 *       a~\textbf{nepropaguje} surový {@code ex.getMessage()} klientovi
 *       (sanitization).</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void cbOpenReturns503WithRetryAfter() {
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        cb.transitionToOpenState();
        CallNotPermittedException ex = CallNotPermittedException.createCallNotPermittedException(cb);

        ResponseEntity<Map<String, Object>> resp = handler.handleCircuitBreakerOpen(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(resp.getBody()).containsKey("correlationId");
        assertThat(resp.getBody().get("error")).isEqualTo("service_unavailable");
        assertThat(resp.getBody().get("retryAfterSeconds")).isEqualTo(30);
    }

    @Test
    void transientReturns503WithRetryAfter60() {
        TransientProviderException ex = new TransientProviderException(
                "internal upstream URL https://secret.deepl.example/v2/translate "
                        + "with key sk-abc123-LEAK", null);

        ResponseEntity<Map<String, Object>> resp = handler.handleTransient(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        // Sanitization: raw upstream URL / key MUST NOT be echoed to client.
        String body = resp.getBody().toString();
        assertThat(body).doesNotContain("secret.deepl.example");
        assertThat(body).doesNotContain("sk-abc123-LEAK");
        assertThat(resp.getBody()).containsKey("correlationId");
    }

    @Test
    void permanentReturns502BadGateway() {
        PermanentProviderException ex = new PermanentProviderException(
                "internal stack trace at com.deepl.api.SomeInternalClass:42", null);

        ResponseEntity<Map<String, Object>> resp = handler.handlePermanent(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody().get("error")).isEqualTo("provider_rejected");
        assertThat(resp.getBody().toString()).doesNotContain("com.deepl.api.SomeInternalClass");
    }

    @Test
    void catchAllReturns500WithCorrelationId() {
        ResponseEntity<Map<String, Object>> resp = handler.handleAny(
                new RuntimeException("internal: jdbc:postgresql://prod-db:5432/secrets"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().get("error")).isEqualTo("internal_error");
        // No leak of stack/url:
        assertThat(resp.getBody().toString()).doesNotContain("jdbc:postgresql");
        assertThat(((String) resp.getBody().get("correlationId")))
                .startsWith("err-");
    }
}
