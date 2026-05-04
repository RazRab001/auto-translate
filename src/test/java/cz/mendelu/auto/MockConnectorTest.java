package cz.mendelu.auto;

import cz.mendelu.auto.connectors.MockConnector;
import cz.mendelu.auto.connectors.TranslationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikace MockConnector pro vývoj/testy bez závislosti na externím API.
 */
class MockConnectorTest {

    @Test
    void translateReturnsExpectedFormat() {
        MockConnector mock = new MockConnector();
        TranslationResult res = mock.translate("Hydraulic pump 500W", "EN", "CS");

        assertThat(res.text()).contains("[MOCK-CS]");
        assertThat(res.text()).contains("Hydraulic pump 500W");
        assertThat(res.provider()).isEqualTo("mock");
        assertThat(res.attempts()).isEqualTo(1);
        assertThat(res.latencyMs()).isGreaterThan(0);
    }

    @Test
    void latencyInExpectedRange() {
        MockConnector mock = new MockConnector();
        // Log-normal kolem 287 ms; clamp 50–1500 ms
        for (int i = 0; i < 5; i++) {
            TranslationResult res = mock.translate("test", "EN", "CS");
            assertThat(res.latencyMs())
                    .as("latency in clamp range [50, 1500]")
                    .isBetween(40L, 1600L);
        }
    }

    @Test
    void getNameReturnsMock() {
        assertThat(new MockConnector().getName()).isEqualTo("mock");
    }
}
