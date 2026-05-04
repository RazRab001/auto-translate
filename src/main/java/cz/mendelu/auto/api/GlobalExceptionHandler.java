package cz.mendelu.auto.api;

import cz.mendelu.auto.connectors.exceptions.PermanentProviderException;
import cz.mendelu.auto.connectors.exceptions.TransientProviderException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Centrální obsluha výjimek z~REST endpointů.
 *
 * <p>Zásady:
 * <ul>
 *   <li>Klientovi se nikdy nevrací surový {@code ex.getMessage()}~--- mohl by
 *       obsahovat upstream URL, fragmenty API klíčů nebo interní stack
 *       informace. Místo toho je do~odpovědi vložen \emph{correlation~ID}
 *       (UUID), pod kterým je celá výjimka (včetně stack trace) zalogována;
 *       provozní operátor podle ID propojí klientskou stížnost se serverovým
 *       logem.</li>
 *   <li>Každá známá kategorie výjimek má vlastní handler s~explicitním HTTP
 *       statusem; všechny ostatní {@link Exception} skončí v~catch-all
 *       handleru s~HTTP~500 a~bezpečnou generickou zprávou.</li>
 *   <li>Pro CB OPEN / transient failures se vrací header {@code Retry-After}
 *       v~sekundách dle doporučení RFC~9110.</li>
 * </ul>
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    private static String newCorrelationId() {
        return "err-" + UUID.randomUUID();
    }

    private static Map<String, Object> body(String error, String message,
                                            String correlationId, Object... extras) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error);
        m.put("message", message);
        m.put("correlationId", correlationId);
        for (int i = 0; i + 1 < extras.length; i += 2) {
            m.put(String.valueOf(extras[i]), extras[i + 1]);
        }
        return m;
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleCircuitBreakerOpen(
            CallNotPermittedException ex
    ) {
        String cid = newCorrelationId();
        log.warn("[{}] Circuit breaker OPEN: {}", cid, ex.getMessage(), ex);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");  // sekund
        return new ResponseEntity<>(
                body("service_unavailable",
                        "Translation provider circuit breaker is OPEN. "
                        + "Retry recommended after the time in Retry-After header.",
                        cid,
                        "retryAfterSeconds", 30),
                headers,
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    @ExceptionHandler(TransientProviderException.class)
    public ResponseEntity<Map<String, Object>> handleTransient(
            TransientProviderException ex
    ) {
        String cid = newCorrelationId();
        log.error("[{}] Transient provider failure (after retries): {}",
                cid, ex.getMessage(), ex);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "60");
        return new ResponseEntity<>(
                body("provider_unavailable",
                        "Upstream translation provider is temporarily unavailable. "
                        + "See server logs by correlationId for details.",
                        cid,
                        "retryAfterSeconds", 60),
                headers,
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }

    @ExceptionHandler(PermanentProviderException.class)
    public ResponseEntity<Map<String, Object>> handlePermanent(
            PermanentProviderException ex
    ) {
        String cid = newCorrelationId();
        log.error("[{}] Permanent provider failure (no retry): {}",
                cid, ex.getMessage(), ex);
        return new ResponseEntity<>(
                body("provider_rejected",
                        "Translation provider rejected the request "
                        + "(authentication, quota or payload error). "
                        + "Retry will not help; contact administrator.",
                        cid),
                HttpStatus.BAD_GATEWAY
        );
    }

    /** {@code @Valid} body validation errors → 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex
    ) {
        String cid = newCorrelationId();
        // List of (field: defaultMessage) — safe to expose, doesn't include stack.
        StringBuilder sb = new StringBuilder();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                sb.append(fe.getField()).append(": ")
                  .append(fe.getDefaultMessage()).append("; "));
        log.info("[{}] Bad request (validation): {}", cid, sb);
        return new ResponseEntity<>(
                body("bad_request", "Request validation failed: " + sb, cid),
                HttpStatus.BAD_REQUEST
        );
    }

    /** Malformed JSON body → 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleParse(
            HttpMessageNotReadableException ex
    ) {
        String cid = newCorrelationId();
        log.info("[{}] Malformed request body: {}", cid, ex.getMessage());
        return new ResponseEntity<>(
                body("bad_request", "Malformed JSON request body.", cid),
                HttpStatus.BAD_REQUEST
        );
    }

    /** Catch-all: never let raw exception bubble to default Spring handler
     *  (which can leak stack traces depending on server.error.include-message). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex) {
        String cid = newCorrelationId();
        log.error("[{}] Unhandled exception: {}", cid, ex.getMessage(), ex);
        return new ResponseEntity<>(
                body("internal_error",
                        "Internal server error. See server logs by correlationId.",
                        cid),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }
}
