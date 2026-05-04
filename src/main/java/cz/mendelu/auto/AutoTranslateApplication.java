package cz.mendelu.auto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Auto-Translate Sidecar Middleware — vstupní bod aplikace.
 *
 * <p>Implementuje architekturu Sidecar Pattern (Microsoft Azure
 * Architecture Center) pro integraci s IBM Maximo Application Suite.
 * Pět hlavních modulů:
 * <ul>
 *   <li>{@code api/}        — REST kontrolér + DTO (komunikace s Maximo)</li>
 *   <li>{@code service/}    — orchestrace, cache, sanitizace HTML</li>
 *   <li>{@code connectors/} — Strategy pattern pro NMT providers
 *                              (DeepL, Google, OpenAI, Mock)</li>
 *   <li>{@code elasticsearch/} — vektorové úložiště (Translation Memory)</li>
 *   <li>{@code config/}     — konfigurace asynchronního zpracování,
 *                              WebClient, retry mechanismu</li>
 * </ul>
 *
 * @author Fedor Baskaev
 */
@SpringBootApplication
@EnableAsync           // asynchronní zpracování
@EnableRetry           // retry s exponenciálním backoffem
@ConfigurationPropertiesScan("cz.mendelu.auto.catalog")
public class AutoTranslateApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoTranslateApplication.class, args);
    }
}
