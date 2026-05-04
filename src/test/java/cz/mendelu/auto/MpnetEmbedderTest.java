package cz.mendelu.auto;

import cz.mendelu.auto.elasticsearch.MpnetEmbedder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pokud ONNX model existuje na~standardní cestě, otestuje skutečný embedding.
 * Jinak ověří chování fallback (IOException při chybějícím modelu).
 *
 * <p>Stažení modelu pro plný test:
 * <pre>{@code
 *   pip install optimum[onnxruntime]
 *   optimum-cli export onnx --model \
 *     sentence-transformers/paraphrase-multilingual-mpnet-base-v2 \
 *     ./models/mpnet
 * }</pre>
 */
class MpnetEmbedderTest {

    private static final Path MODEL = Paths.get("models/mpnet/model.onnx");
    private static final Path TOKENIZER = Paths.get("models/mpnet/tokenizer.json");

    static boolean hasModel() {
        return Files.exists(MODEL) && Files.exists(TOKENIZER);
    }

    @Test
    void missingModelThrowsIoException() {
        Path nonExistent = Paths.get("/tmp/no-such-model.onnx");
        Path tokenizer = Paths.get("/tmp/no-such-tokenizer.json");
        assertThatThrownBy(() -> new MpnetEmbedder(nonExistent, tokenizer))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @EnabledIf("cz.mendelu.auto.MpnetEmbedderTest#hasModel")
    void embedReturnsNormalized768dVector() throws IOException {
        try (MpnetEmbedder e = new MpnetEmbedder(MODEL, TOKENIZER)) {
            float[] vec = e.embed("Hydraulic pump 500W");
            assertThat(vec).hasSize(768);

            // L2 norm should be ~1.0 (normalized output)
            double norm = 0;
            for (float v : vec) norm += v * v;
            norm = Math.sqrt(norm);
            assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    @EnabledIf("cz.mendelu.auto.MpnetEmbedderTest#hasModel")
    void semanticallySimilarTextsHaveHighCosine() throws IOException {
        try (MpnetEmbedder e = new MpnetEmbedder(MODEL, TOKENIZER)) {
            float[] a = e.embed("Hex bolt M12");
            float[] b = e.embed("M12 hexagonal screw");
            // Synonyms should have cosine > 0.7
            double cos = 0;
            for (int i = 0; i < a.length; i++) cos += a[i] * b[i];
            assertThat(cos).isGreaterThan(0.7);
        }
    }
}
