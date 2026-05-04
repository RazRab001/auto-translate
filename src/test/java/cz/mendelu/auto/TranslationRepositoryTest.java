package cz.mendelu.auto;

import cz.mendelu.auto.elasticsearch.CacheLookupResult;
import cz.mendelu.auto.elasticsearch.TranslationRepository;
import cz.mendelu.auto.elasticsearch.VectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pokrývá Smart Cache: cache hit při skóre nad prahem, miss jinak.
 */
class TranslationRepositoryTest {

    private TranslationRepository repository;
    private VectorService vector;

    @BeforeEach
    void setUp() {
        repository = new TranslationRepository();
        ReflectionTestUtils.setField(repository, "capacity", 10000);
        vector = new VectorService();
    }

    @Test
    void emptyTmReturnsMiss() {
        float[] queryVec = vector.embed("Hydraulic pump 500W");
        CacheLookupResult res = repository.lookup(queryVec, 0.82);
        assertThat(res.hit()).isFalse();
        assertThat(res.cachedTranslation()).isNull();
    }

    @Test
    void exactSameTextReturnsHit() {
        String text = "Hydraulic pump 500W";
        float[] vec = vector.embed(text);
        repository.save(text, "Hydraulické čerpadlo 500W", vec, "EN", "CS", "mock");

        CacheLookupResult res = repository.lookup(vec, 0.82);
        assertThat(res.hit()).isTrue();
        assertThat(res.score()).isGreaterThan(0.99); // identical → cosine ≈ 1
        assertThat(res.cachedTranslation()).isEqualTo("Hydraulické čerpadlo 500W");
    }

    @Test
    void differentTextReturnsMiss() {
        repository.save("Hydraulic pump 500W",
                "Hydraulické čerpadlo 500W",
                vector.embed("Hydraulic pump 500W"),
                "EN", "CS", "mock");

        float[] queryVec = vector.embed("Lock nut M10 DIN 985");
        CacheLookupResult res = repository.lookup(queryVec, 0.82);
        assertThat(res.hit()).isFalse(); // různý text → nízká podobnost
    }

    @Test
    void appendOnlyPreservesAllVersions() {
        // Verzování (audit trail): druhý zápis NEpřepisuje první
        String text = "Hydraulic pump 500W";
        float[] vec = vector.embed(text);
        repository.save(text, "v1 překlad", vec, "EN", "CS", "google");
        repository.save(text, "v2 překlad", vec, "EN", "CS", "deepl");
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void countReflectsNumberOfRecords() {
        assertThat(repository.count()).isZero();
        for (int i = 0; i < 5; i++) {
            repository.save("text" + i, "překlad" + i,
                    vector.embed("text" + i), "EN", "CS", "mock");
        }
        assertThat(repository.count()).isEqualTo(5);
    }

    @Test
    void clearRemovesAll() {
        for (int i = 0; i < 3; i++) {
            repository.save("text" + i, "překlad" + i,
                    vector.embed("text" + i), "EN", "CS", "mock");
        }
        repository.clear();
        assertThat(repository.count()).isZero();
    }
}
