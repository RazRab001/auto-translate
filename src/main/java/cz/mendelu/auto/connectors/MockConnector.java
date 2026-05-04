package cz.mendelu.auto.connectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock NMT poskytovatel pro vývoj a testy.
 *
 * <p>Imituje volání externího API simulací log-normální latence
 * (μ ≈ 287 ms, std ≈ 100 ms) odpovídající empiricky naměřeným
 * hodnotám DeepL Free API. Neprovádí skutečný překlad — vrací
 * prefixovaný zdrojový text.
 *
 * <p>Aktivuje se automaticky při {@code ai.default-provider=mock}
 * v {@code application.yml}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.default-provider", havingValue = "mock", matchIfMissing = true)
public class MockConnector implements TranslationProvider {

    @Override
    public String getName() {
        return "mock";
    }

    @Override
    public TranslationResult translate(String text, String sourceLang, String targetLang) {
        long startNanos = System.nanoTime();
        // Simulace latence odpovídající empiricky naměřeným hodnotám DeepL
        long delayMs = sampleLogNormalLatency();
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.debug("[MOCK] translate '{}' ({}->{}): {} ms",
                  text.length() > 50 ? text.substring(0, 50) + "..." : text,
                  sourceLang, targetLang, elapsedMs);
        return new TranslationResult(
                "[MOCK-" + targetLang + "] " + text,
                getName(),
                elapsedMs,
                1
        );
    }

    private long sampleLogNormalLatency() {
        double mean = 287.0;
        double sigma = 0.2;
        double normal = ThreadLocalRandom.current().nextGaussian() * sigma;
        long sample = Math.round(mean * Math.exp(normal));
        return Math.max(50L, Math.min(sample, 1500L));
    }
}
