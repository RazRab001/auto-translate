package cz.mendelu.auto.elasticsearch;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Lokální embedding přes {@code paraphrase-multilingual-mpnet-base-v2}
 * pomocí ONNX Runtime (Java native, bez Pythonu).
 *
 * <p>Empirické měření prokázalo, že tento model dosahuje vyššího
 * recall na~průmyslových duplicitách než {@code text-embedding-3-small}
 * (OpenAI). Pro produkční nasazení v~prostředí
 * s~přísnými požadavky na~ochranu dat (žádné odesílání textů třetí straně)
 * je lokální model preferovaný.
 *
 * <p><b>Předpoklady:</b>
 * <ul>
 *   <li>Konvertovaný ONNX model (1 file: {@code model.onnx}, 768d output)
 *       --- exportováno z~HuggingFace pomocí {@code optimum-cli}.</li>
 *   <li>HuggingFace tokenizer JSON ({@code tokenizer.json}) --- součást
 *       repository sentence-transformers.</li>
 * </ul>
 *
 * <p><b>Build of artefakty:</b> kvůli velikosti (~470\,MB) nejsou ONNX
 * a~tokenizer součástí git repozitáře. Při startu se hledají v~adresáři
 * {@code app.embedding.mpnet.model-dir} ({@code application.yml}); pokud
 * neexistují, {@link VectorService} fallbackne na~{@code openai} nebo
 * {@code hash} mód s~varováním v~logu.
 *
 * <p>Skript pro download ONNX modelu:
 * <pre>{@code
 *   pip install optimum[onnxruntime]
 *   optimum-cli export onnx --model sentence-transformers/paraphrase-multilingual-mpnet-base-v2 ./mpnet-onnx
 * }</pre>
 */
@Slf4j
public class MpnetEmbedder implements AutoCloseable {

    private static final int MAX_LENGTH = 256;
    private static final int EMBEDDING_DIM = 768;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public MpnetEmbedder(Path modelPath, Path tokenizerPath) throws IOException {
        if (!Files.exists(modelPath)) {
            throw new IOException("ONNX model not found: " + modelPath);
        }
        if (!Files.exists(tokenizerPath)) {
            throw new IOException("Tokenizer not found: " + tokenizerPath);
        }
        this.env = OrtEnvironment.getEnvironment();
        try {
            this.session = env.createSession(modelPath.toString(),
                    new OrtSession.SessionOptions());
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
            log.info("MpnetEmbedder loaded: {} ({}d, max_len={})",
                    modelPath.getFileName(), EMBEDDING_DIM, MAX_LENGTH);
        } catch (ai.onnxruntime.OrtException e) {
            throw new IOException("Failed to load ONNX session", e);
        }
    }

    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            return new float[EMBEDDING_DIM];
        }
        Encoding enc = tokenizer.encode(text);
        long[] ids = enc.getIds();
        long[] mask = enc.getAttentionMask();
        // Truncate na MAX_LENGTH
        int len = Math.min(ids.length, MAX_LENGTH);
        long[] inputIds = new long[len];
        long[] attMask = new long[len];
        System.arraycopy(ids, 0, inputIds, 0, len);
        System.arraycopy(mask, 0, attMask, 0, len);

        // Pokud createTensor pro maskTensor vyhodí OrtException
        // až poté, co inputTensor byl alokován, naivní code path by nechal
        // inputTensor neuzavřený. Deklarujeme oba tensory mimo a~uzavíráme
        // bezpečně v~outer finally.
        OnnxTensor inputTensor = null;
        OnnxTensor maskTensor = null;
        try {
            inputTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(inputIds), new long[]{1, len});
            maskTensor = OnnxTensor.createTensor(env,
                    LongBuffer.wrap(attMask), new long[]{1, len});

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputTensor);
            inputs.put("attention_mask", maskTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                // Output shape: [1, len, 768] — bereme mean pooling
                float[][][] tokenEmbeddings = (float[][][]) result.get(0).getValue();
                return meanPool(tokenEmbeddings[0], attMask);
            }
        } catch (ai.onnxruntime.OrtException e) {
            throw new IllegalStateException("ONNX inference failed", e);
        } finally {
            if (inputTensor != null) inputTensor.close();
            if (maskTensor  != null) maskTensor.close();
        }
    }

    /** Mean pooling tokenových embeddings vážený attention mask + L2 normalizace. */
    private float[] meanPool(float[][] tokenEmbeddings, long[] mask) {
        float[] pooled = new float[EMBEDDING_DIM];
        int validTokens = 0;
        for (int t = 0; t < tokenEmbeddings.length; t++) {
            if (mask[t] == 0) continue;
            for (int d = 0; d < EMBEDDING_DIM; d++) {
                pooled[d] += tokenEmbeddings[t][d];
            }
            validTokens++;
        }
        if (validTokens == 0) return pooled;
        for (int d = 0; d < EMBEDDING_DIM; d++) {
            pooled[d] /= validTokens;
        }
        // L2 normalize
        double norm = 0;
        for (float v : pooled) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < pooled.length; i++) {
                pooled[i] = (float) (pooled[i] / norm);
            }
        }
        return pooled;
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
        } catch (ai.onnxruntime.OrtException ignored) {}
    }
}
