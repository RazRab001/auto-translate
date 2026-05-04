package cz.mendelu.auto.connectors.exceptions;

/**
 * Trvalá chyba externího NMT poskytovatele.
 *
 * <p>Patří sem: HTTP 4xx (kromě 429), neplatný API klíč, formátová
 * chyba payloadu. Tato chyba se {@link
 * org.springframework.retry.annotation.Retryable NEopakuje}, protože
 * by opakování pouze plýtvalo kvótou API.
 */
public class PermanentProviderException extends RuntimeException {

    public PermanentProviderException(String message) {
        super(message);
    }

    public PermanentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
