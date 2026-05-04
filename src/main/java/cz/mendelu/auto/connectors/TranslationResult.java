package cz.mendelu.auto.connectors;

/**
 * Výsledek překladového volání včetně metrik pro audit.
 *
 * @param text       přeložený text
 * @param provider   identifikace poskytovatele (např. "deepl", "mock")
 * @param latencyMs  doba zpracování v milisekundách (server-side)
 * @param attempts   počet pokusů (1 při úspěchu na první pokus,
 *                   až {@code max-attempts} při retry)
 */
public record TranslationResult(
        String text,
        String provider,
        long latencyMs,
        int attempts
) {
}
