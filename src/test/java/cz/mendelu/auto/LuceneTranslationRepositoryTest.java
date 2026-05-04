package cz.mendelu.auto;

import cz.mendelu.auto.elasticsearch.CacheLookupResult;
import cz.mendelu.auto.elasticsearch.LuceneTranslationRepository;
import cz.mendelu.auto.elasticsearch.VectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy pro Lucene-based Translation Memory.
 *
 * <p>Verifikuje že kNN cosine vyhledávání pomocí Apache Lucene
 * funguje shodně jako (zjednodušený) in-memory přístup —
 * cache hit/miss, append-only, persistence.
 */
class LuceneTranslationRepositoryTest {

    private LuceneTranslationRepository repository;
    private VectorService vector;

    @BeforeEach
    void setUp() throws IOException {
        repository = new LuceneTranslationRepository();
        // In-memory directory — nezasahujeme do file system
        ReflectionTestUtils.setField(repository, "inMemory", true);
        ReflectionTestUtils.setField(repository, "indexPath", "/tmp/unused");
        repository.init();
        vector = new VectorService();
    }

    @AfterEach
    void tearDown() throws IOException {
        repository.close();
    }

    @Test
    void emptyTmReturnsMiss() throws IOException {
        float[] queryVec = vector.embed("Hydraulic pump 500W");
        CacheLookupResult res = repository.lookup(queryVec, 0.82);
        assertThat(res.hit()).isFalse();
        assertThat(res.cachedTranslation()).isNull();
    }

    @Test
    void exactSameTextReturnsHit() throws IOException {
        String text = "Hydraulic pump 500W";
        float[] vec = vector.embed(text);
        repository.save(text, "Hydraulické čerpadlo 500W", vec,
                "EN", "CS", "deepl");

        CacheLookupResult res = repository.lookup(vec, 0.82);
        assertThat(res.hit()).isTrue();
        // Identický vektor → cosine ≈ 1
        assertThat(res.score()).isGreaterThan(0.99);
        assertThat(res.cachedTranslation()).isEqualTo("Hydraulické čerpadlo 500W");
    }

    @Test
    void differentTextReturnsMiss() throws IOException {
        repository.save("Hydraulic pump 500W",
                "Hydraulické čerpadlo 500W",
                vector.embed("Hydraulic pump 500W"),
                "EN", "CS", "deepl");

        float[] queryVec = vector.embed("Lock nut M10 DIN 985");
        CacheLookupResult res = repository.lookup(queryVec, 0.82);
        assertThat(res.hit()).isFalse(); // různý text → nízká podobnost
    }

    @Test
    void countReflectsAppendedRecords() throws IOException {
        assertThat(repository.count()).isZero();
        for (int i = 0; i < 5; i++) {
            repository.save("text" + i, "překlad" + i,
                    vector.embed("text" + i), "EN", "CS", "deepl");
        }
        assertThat(repository.count()).isEqualTo(5);
    }

    @Test
    void clearRemovesAll() throws IOException {
        repository.save("text", "překlad", vector.embed("text"),
                "EN", "CS", "deepl");
        repository.save("text2", "překlad2", vector.embed("text2"),
                "EN", "CS", "deepl");
        repository.clear();
        assertThat(repository.count()).isZero();
    }
}
