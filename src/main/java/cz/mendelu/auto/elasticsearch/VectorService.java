package cz.mendelu.auto.elasticsearch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Embedding service — generuje sémantické vektory pro vstupní texty.
 *
 * <p>Tři režimy (volba přes {@code application.yml}):
 * <ul>
 *   <li><b>{@code openai}</b> (výchozí pro produkci) — volá OpenAI
 *       endpoint {@code POST /v1/embeddings} s modelem
 *       {@code text-embedding-3-small} (1536d). Reálná sémantická
 *       reprezentace dle interní architektury.</li>
 *   <li><b>{@code mpnet}</b> — místo držitel pro lokální Sentence
 *       Transformer model {@code paraphrase-multilingual-mpnet-base-v2}
 *       (768d), který empiricky dosahuje vyššího recall na
 *       průmyslových duplicitách. Java integrace přes ONNX Runtime
 *       je dostupná jako rozšíření.</li>
 *   <li><b>{@code hash}</b> (POUZE pro offline testy) — deterministická
 *       SHA-256 → 384-d pseudo-vektor. <em>NENÍ</em> sémantický:
 *       dva věty se shodným významem ale jinou syntaxí dají různé vektory.
 *       Slouží jen k~validaci pipeline a~exact-match TM logiky bez
 *       závislosti na externím API.</li>
 * </ul>
 *
 * <p>OpenAI volání jsou cachována v paměti (in-process LRU; klíč =
 * SHA-256 textu) pro snížení API nákladů během opakovaných benchmarků.
 */
@Slf4j
@Service
public class VectorService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int HASH_DIM = 384;

    @Value("${ai.embedding.provider:hash}")
    private String provider = "hash";

    @Value("${ai.openai.api-key:}")
    private String openaiKey = "";

    @Value("${ai.openai.embedding-model:text-embedding-3-small}")
    private String openaiModel = "text-embedding-3-small";

    @Value("${ai.openai.base-url:https://api.openai.com/v1}")
    private String openaiBaseUrl = "https://api.openai.com/v1";

    @Value("${ai.embedding.mpnet.model-path:./models/mpnet/model.onnx}")
    private String mpnetModelPath = "./models/mpnet/model.onnx";

    @Value("${ai.embedding.mpnet.tokenizer-path:./models/mpnet/tokenizer.json}")
    private String mpnetTokenizerPath = "./models/mpnet/tokenizer.json";

    /** Cache (text → vector) pro snížení API nákladů. */
    private final ConcurrentMap<String, float[]> cache = new ConcurrentHashMap<>();

    /** Lazy-loaded mpnet ONNX session (jen pokud provider=mpnet). */
    private MpnetEmbedder mpnet;

    @PostConstruct
    public void init() {
        if ("mpnet".equalsIgnoreCase(provider)) {
            try {
                mpnet = new MpnetEmbedder(
                        Path.of(mpnetModelPath),
                        Path.of(mpnetTokenizerPath));
                log.info("Loaded mpnet ONNX model: {}", mpnetModelPath);
            } catch (Exception e) {
                log.warn("mpnet ONNX model nedostupný ({}), fallback na hash mode "
                        + "(stáhnout: optimum-cli export onnx --model "
                        + "sentence-transformers/paraphrase-multilingual-mpnet-base-v2 "
                        + "./models/mpnet)", e.getMessage());
                mpnet = null;
            }
        }
    }

    @PreDestroy
    public void close() {
        if (mpnet != null) {
            mpnet.close();
        }
    }

    /**
     * Vrátí embedding zdrojového textu dle nakonfigurovaného režimu.
     */
    public float[] embed(String text) {
        if (text == null) text = "";
        String key = text.trim();
        if (key.isEmpty()) {
            return normalize(new float[HASH_DIM]);
        }
        return cache.computeIfAbsent(key, this::doEmbed);
    }

    private float[] doEmbed(String text) {
        return switch (provider.toLowerCase()) {
            case "openai" -> openaiEmbed(text);
            case "mpnet" -> mpnet != null ? mpnet.embed(text) : hashEmbed(text);
            default -> hashEmbed(text);
        };
    }

    /** Reálný OpenAI embedding přes /v1/embeddings. */
    private float[] openaiEmbed(String text) {
        if (openaiKey == null || openaiKey.isBlank()) {
            log.warn("OPENAI_API_KEY není nastaven, fallback na hash mode");
            return hashEmbed(text);
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", openaiModel,
                    "input", text
            );
            String resp = postJson(openaiBaseUrl + "/embeddings",
                    openaiKey, MAPPER.writeValueAsBytes(body));
            Map<String, Object> root = MAPPER.readValue(resp, new TypeReference<>() {});
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
            List<Number> e = (List<Number>) data.get(0).get("embedding");
            float[] vec = new float[e.size()];
            for (int i = 0; i < e.size(); i++) {
                vec[i] = e.get(i).floatValue();
            }
            return normalize(vec);
        } catch (IOException ex) {
            log.error("OpenAI embed failed, fallback to hash: {}", ex.getMessage());
            return hashEmbed(text);
        }
    }

    /**
     * <b>Deterministický fallback</b> na bázi SHA-256 hash.
     *
     * <p><em>Důležité:</em> tento režim NENÍ sémantický embedding;
     * dva texty s podobným významem ale jinou syntaxí ({@code "Hex bolt"}
     * vs {@code "M12 bolt, hex head"}) dostanou nesouvisející vektory.
     * Slouží výhradně pro: (a)~unit testy bez závislosti na API,
     * (b)~ověření identity-lookup TM (cache hit u zcela shodného textu).
     *
     * <p>Pro semantic deduplication je nutný režim
     * {@code openai} nebo {@code mpnet}.
     */
    private float[] hashEmbed(String text) {
        try {
            float[] vec = new float[HASH_DIM];
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] seed = md.digest(text.getBytes(StandardCharsets.UTF_8));
            int idx = 0;
            for (int i = 0; i < HASH_DIM; i++) {
                byte b = seed[idx];
                vec[i] = (b & 0xff) / 255.0f - 0.5f;
                idx = (idx + 1) % seed.length;
                if (idx == 0) {
                    seed = md.digest(seed);
                }
            }
            return normalize(vec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** L2-normalizace vektoru (cosine-ready). */
    private float[] normalize(float[] vec) {
        double norm = 0.0;
        for (float v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) return vec;
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) (vec[i] / norm);
        }
        return vec;
    }

    /** Synchronní POST přes {@link HttpURLConnection}. */
    private static String postJson(String url, String bearer, byte[] body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL()
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Authorization", "Bearer " + bearer);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        String resp = buf.toString(StandardCharsets.UTF_8);
        if (code != 200) {
            throw new IOException("HTTP " + code + ": "
                    + resp.substring(0, Math.min(resp.length(), 300)));
        }
        return resp;
    }
}
