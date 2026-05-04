package cz.mendelu.auto;

import cz.mendelu.auto.bench.OmegaTFuzzyMatcher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikace replikace OmegaT FuzzyMatcher.
 *
 * <p>Očekávané hodnoty pocházejí ze skutečného běhu OmegaT 6.0.0
 * v režimu {@code console-translate} na stejných 15 párech.
 * Při výchozím prahu 70% identifikuje 0/15 párů (mean ~19%).
 */
class OmegaTFuzzyMatcherTest {

    @Test
    void identicalTextReturns100Percent() {
        int s = OmegaTFuzzyMatcher.score("Hello world", "Hello world");
        assertThat(s).isEqualTo(100);
    }

    @Test
    void completelyDifferentReturnsLow() {
        int s = OmegaTFuzzyMatcher.score(
                "Hex bolt M12 stainless",
                "Centrifugal pump 500W motor");
        assertThat(s).isLessThan(30);
    }

    @Test
    void categoryCMeanScoreIsAround19Percent() {
        int sum = 0;
        for (String[] pair : OmegaTFuzzyMatcher.CATEGORY_C_PAIRS) {
            sum += OmegaTFuzzyMatcher.score(pair[0], pair[1]);
        }
        double mean = (double) sum / OmegaTFuzzyMatcher.CATEGORY_C_PAIRS.size();
        // Skutečný OmegaT na stejných datech: 19.3%
        assertThat(mean).isBetween(15.0, 25.0);
    }

    @Test
    void zeroHitsAtDefaultThreshold() {
        int hits = 0;
        for (String[] pair : OmegaTFuzzyMatcher.CATEGORY_C_PAIRS) {
            if (OmegaTFuzzyMatcher.score(pair[0], pair[1])
                    >= OmegaTFuzzyMatcher.DEFAULT_THRESHOLD) {
                hits++;
            }
        }
        // Skutečný OmegaT 6.0.0 na stejných datech: 0/15 hits at threshold 75%
        assertThat(hits).isZero();
    }

    @Test
    void score100ForSameTextRegardlessOfCase() {
        int s = OmegaTFuzzyMatcher.score("HELLO World", "hello world");
        assertThat(s).isEqualTo(100);
    }

    @Test
    void emptyOrNullReturnsZero() {
        assertThat(OmegaTFuzzyMatcher.score("", "anything")).isZero();
        assertThat(OmegaTFuzzyMatcher.score(null, "anything")).isZero();
        assertThat(OmegaTFuzzyMatcher.score("anything", "")).isZero();
    }
}
