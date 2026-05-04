package cz.mendelu.auto;

import cz.mendelu.auto.connectors.TranslationProvider;
import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.elasticsearch.TranslationRepository;
import cz.mendelu.auto.elasticsearch.VectorService;
import cz.mendelu.auto.service.HtmlSanitizer;
import cz.mendelu.auto.service.JobRegistry;
import cz.mendelu.auto.service.TranslationOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pokrývá orchestraci pipeline: cache hit obchází provider,
 * cache miss volá provider + uloží do TM.
 */
class TranslationOrchestratorTest {

    private TranslationRepository repository;
    private VectorService vector;
    private TranslationProvider provider;
    private HtmlSanitizer sanitizer;
    private JobRegistry jobs;
    private TranslationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        repository = new TranslationRepository();
        ReflectionTestUtils.setField(repository, "capacity", 10000);
        vector = new VectorService();
        provider = mock(TranslationProvider.class);
        when(provider.getName()).thenReturn("mock");
        sanitizer = new HtmlSanitizer();
        jobs = new JobRegistry();
        orchestrator = new TranslationOrchestrator(
                vector, repository, provider, sanitizer, jobs);
        ReflectionTestUtils.setField(orchestrator, "similarityThreshold", 0.82);
    }

    @Test
    void cacheMissCallsProviderAndStoresResult() {
        when(provider.translate(anyString(), eq("EN"), eq("CS")))
                .thenReturn(new TranslationResult(
                        "Hydraulické čerpadlo 500W", "mock", 287L, 1));

        TranslationResult res = orchestrator.processSync(
                "Hydraulic pump 500W", "EN", "CS");

        assertThat(res.text()).isEqualTo("Hydraulické čerpadlo 500W");
        assertThat(res.provider()).isEqualTo("mock");
        verify(provider, times(1)).translate(anyString(), anyString(), anyString());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void cacheHitDoesNotCallProvider() {
        // Předem populate TM
        String text = "Hydraulic pump 500W";
        repository.save(text, "Hydraulické čerpadlo 500W",
                vector.embed(text), "EN", "CS", "deepl");

        TranslationResult res = orchestrator.processSync(text, "EN", "CS");

        assertThat(res.text()).isEqualTo("Hydraulické čerpadlo 500W");
        assertThat(res.provider()).isEqualTo("cache");
        verify(provider, never()).translate(any(), any(), any());
    }

    @Test
    void htmlIsRestoredAfterTranslation() {
        // Provider vrátí placeholder, orchestrator musí obnovit HTML
        when(provider.translate(anyString(), eq("EN"), eq("CS")))
                .thenAnswer(inv -> {
                    String s = inv.getArgument(0);
                    // V sanitized formě nesmí být <b>; má být {B}
                    assertThat(s).doesNotContain("<b>");
                    assertThat(s).contains("{B}");
                    return new TranslationResult(s, "mock", 100L, 1);
                });

        TranslationResult res = orchestrator.processSync(
                "<b>Max</b> 40", "EN", "CS");

        assertThat(res.text()).contains("<b>");
        assertThat(res.text()).contains("</b>");
    }
}
