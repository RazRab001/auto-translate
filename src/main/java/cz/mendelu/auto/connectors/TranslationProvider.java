package cz.mendelu.auto.connectors;

/**
 * Společné rozhraní všech NMT poskytovatelů (Strategy Pattern).
 *
 * <p>Každá implementace zapouzdřuje volání externího API
 * (DeepL, Google Cloud Translation, OpenAI Chat Completion)
 * a vrací jednotný {@link TranslationResult}.
 *
 * <p>Implementace musí přesně klasifikovat HTTP chyby do dvou tříd:
 * <ul>
 *   <li>{@link cz.mendelu.auto.connectors.exceptions.TransientProviderException}
 *       — 5xx, 429, network timeout (retry je vhodné)</li>
 *   <li>{@link cz.mendelu.auto.connectors.exceptions.PermanentProviderException}
 *       — 4xx (kromě 429), neplatný klíč, validační chyba (retry zbytečné)</li>
 * </ul>
 *
 * <p>Retry/backoff politika je definována na úrovni Spring Retry
 * v každé konkrétní implementaci pomocí {@code @Retryable}.
 *
 * <p>Volba poskytovatele za běhu se provádí přes Spring qualifier nebo
 * {@code application.yml} parametr {@code ai.default-provider}.
 */
public interface TranslationProvider {

    /**
     * Identifikátor poskytovatele používaný v konfiguraci a auditních logech.
     *
     * @return krátké jméno (např. "deepl", "google", "openai", "mock")
     */
    String getName();

    /**
     * Synchronní (blokující) překlad textu.
     *
     * <p>Implementace má interně využít neblokující WebClient a~vrátit
     * výsledek po dokončení (kombinace {@code .block()} při volání
     * z asynchronního kontextu je akceptovatelná).
     *
     * @param text       zdrojový text k překladu
     * @param sourceLang ISO 639-1 kód zdrojového jazyka (např. "EN")
     * @param targetLang ISO 639-1 kód cílového jazyka (např. "CS")
     * @return výsledek překladu včetně latency a počtu pokusů
     * @throws TransientProviderException dočasná chyba (po vyčerpání retry pokusů)
     * @throws PermanentProviderException trvalá chyba (auth, validace)
     */
    TranslationResult translate(String text, String sourceLang, String targetLang);
}
