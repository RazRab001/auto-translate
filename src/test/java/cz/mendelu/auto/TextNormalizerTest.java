package cz.mendelu.auto;

import cz.mendelu.auto.service.TextNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link TextNormalizer} resolves the two specific
 * threshold-sweep failure cases (kapitola~6.5):
 * <ul>
 *   <li>"SiC mechanical seal" ↔ "silicon carbide mechanical seal",</li>
 *   <li>"10 micron filter" ↔ "10µ filter",</li>
 * </ul>
 * by ensuring the normalized forms become token-overlapping.
 */
class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void sicAbbreviationGetsExpansionAppended() {
        String norm = normalizer.normalizeForEmbedding("SiC mechanical seal PN16");
        assertThat(norm).contains("SiC");
        assertThat(norm).containsIgnoringCase("silicon carbide");
        // PN abbreviation also expanded (PN → nominal pressure).
        assertThat(norm).containsIgnoringCase("nominal pressure");
    }

    @Test
    void microSignUnitGetsExpansionAppended() {
        String norm1 = normalizer.normalizeForEmbedding("10µ filter cartridge");
        assertThat(norm1).containsIgnoringCase("micron");
        // The Greek mu Unicode variant should also work.
        String norm2 = normalizer.normalizeForEmbedding("10μ filter");
        assertThat(norm2).containsIgnoringCase("micron");
    }

    @Test
    void normalizationIsIdempotent() {
        String input = "SiC mechanical seal";
        String once = normalizer.normalizeForEmbedding(input);
        String twice = normalizer.normalizeForEmbedding(once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void textAlreadyContainingExpansionIsNotDuplicated() {
        // If full word "silicon carbide" is already in text, abbreviation
        // expansion is suppressed.
        String input = "silicon carbide seal";
        String norm = normalizer.normalizeForEmbedding(input);
        long count = countOccurrences(norm.toLowerCase(), "silicon carbide");
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void diameterSymbolExpansion() {
        String norm = normalizer.normalizeForEmbedding("Ø50 mm shaft");
        assertThat(norm).containsIgnoringCase("diameter");
    }

    @Test
    void emptyInputUnchanged() {
        assertThat(normalizer.normalizeForEmbedding(null)).isNull();
        assertThat(normalizer.normalizeForEmbedding("")).isEqualTo("");
    }

    @Test
    void plainTextWithNoTriggersUnchanged() {
        String input = "Hydraulic pump for water";
        assertThat(normalizer.normalizeForEmbedding(input)).isEqualTo(input);
    }

    /**
     * Demonstrates the headline failure case: after normalization, the two
     * variants become token-overlapping, which is what the embedding
     * similarity then leverages. Cosine similarity itself isn't computed
     * here (that's MpnetEmbedderTest's job); this test only proves the
     * normalizer creates the precondition.
     */
    @Test
    void thresholdSweepFailureCasesNormalizeToCommonVocabulary() {
        String left = normalizer.normalizeForEmbedding("SiC mechanical seal").toLowerCase();
        String right = normalizer.normalizeForEmbedding("silicon carbide mechanical seal").toLowerCase();
        // Both variants now share the "silicon carbide" sub-string.
        assertThat(left).contains("silicon carbide");
        assertThat(right).contains("silicon carbide");

        String left2 = normalizer.normalizeForEmbedding("10 micron filter").toLowerCase();
        String right2 = normalizer.normalizeForEmbedding("10µ filter").toLowerCase();
        assertThat(left2).contains("micron");
        assertThat(right2).contains("micron");
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
