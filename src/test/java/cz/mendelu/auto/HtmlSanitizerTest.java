package cz.mendelu.auto;

import cz.mendelu.auto.service.HtmlSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ověřuje, že HTML tagy jsou zachovány po sanitizaci → překladu → restore.
 */
class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    @Test
    void htmlTagsPreservedAfterRoundTrip() {
        String input = "<b>Max. pressure</b> 40 bar";

        String sanitized = sanitizer.sanitize(input);
        assertThat(sanitized).doesNotContain("<b>");
        assertThat(sanitized).doesNotContain("</b>");
        assertThat(sanitized).contains("{B}");
        assertThat(sanitized).contains("{/B}");

        String restored = sanitizer.restore(sanitized);
        assertThat(restored).isEqualTo(input);
    }

    @Test
    void multipleTagsPreserved() {
        String input = "<p>First.</p><br><div>Second</div>";
        String restored = sanitizer.restore(sanitizer.sanitize(input));
        assertThat(restored).isEqualTo(input);
    }

    @Test
    void nullSafe() {
        assertThat(sanitizer.sanitize(null)).isNull();
        assertThat(sanitizer.restore(null)).isNull();
    }

    @Test
    void emptyStringSafe() {
        assertThat(sanitizer.sanitize("")).isEmpty();
        assertThat(sanitizer.restore("")).isEmpty();
    }

    @Test
    void plainTextUnchanged() {
        String plain = "No HTML here, just plain text 123.";
        assertThat(sanitizer.sanitize(plain)).isEqualTo(plain);
        assertThat(sanitizer.restore(plain)).isEqualTo(plain);
    }
}
