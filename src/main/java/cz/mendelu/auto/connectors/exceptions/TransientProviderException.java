package cz.mendelu.auto.connectors.exceptions;

/**
 * Přechodná chyba externího NMT poskytovatele.
 *
 * <p>Patří sem: HTTP 5xx, HTTP 429 (rate limit), network timeout,
 * dočasná nedostupnost. Tato třída výjimky je {@link
 * org.springframework.retry.annotation.Retryable retryována}
 * s exponenciálním backoffem.
 */
public class TransientProviderException extends RuntimeException {

    public TransientProviderException(String message) {
        super(message);
    }

    public TransientProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
