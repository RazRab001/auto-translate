package cz.mendelu.auto.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Rozšířený benchmark CPV klasifikace na n=150 (oproti původnímu n=45).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>GPT-4o vygeneruje 150 trojic
 *       (anglický popis komponenty, CPV kód, český CPV label).</li>
 *   <li>Pro každý unikátní CPV label v~datasetu se získá embedding
 *       přes OpenAI {@code text-embedding-3-small} (1536d).</li>
 *   <li>Pro každý popis se získá embedding a~hledá se top-1 nejbližší
 *       CPV label dle kosinové podobnosti.</li>
 *   <li>Accuracy = kolik popisů našlo správný (ground-truth) CPV kód
 *       jako top-1.</li>
 * </ol>
 *
 * <p>Toto je <em>retrieval-style evaluation</em> na podmnožině CPV
 * (cca 80–100 unikátních kódů z~9 454 možných v~plné nomenklatuře).
 * Plná evaluace na celém katalogu je doporučena jako navazující práce
 * (vyžaduje download oficiálního CPV katalogu z~SIMAP/EU Publications).
 *
 * <p>Spuštění:
 * <pre>{@code
 *   export OPENAI_API_KEY=sk-...
 *   mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
 *   java -cp "target/classes;target/dependency/*" cz.mendelu.auto.bench.CpvBenchmark
 * }</pre>
 */
public final class CpvBenchmark {

    private static final String OPENAI_BASE = "https://api.openai.com/v1";
    private static final String CHAT_MODEL = "gpt-4o";
    private static final String EMBED_MODEL = "text-embedding-3-small";
    private static final int TARGET_SIZE = 150;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record CpvEntry(String description, String code, String labelCs) {}
    /**
     * Evaluation summary. {@code top1Correct}/{@code top1Accuracy} are the
     * primary headline numbers; {@code top3Correct}/{@code top3Accuracy}
     * jsou určené pro human-in-the-loop UX scénář, kde nákupčí vybírá
     * z~tří kandidátů.
     */
    public record EvalResult(int n,
                             int top1Correct, double top1Accuracy,
                             int top3Correct, double top3Accuracy,
                             double meanCosine, int distinctCodes) {}

    private CpvBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: OPENAI_API_KEY env variable required.");
            System.exit(1);
        }

        // 1. Vygeneruj 150 (description, code, label) trojic
        System.out.println("Step 1: Generating 150 (description, CPV code, label) pairs via GPT-4o...");
        List<CpvEntry> dataset = generateDataset(apiKey, TARGET_SIZE);
        System.out.printf("  Got %d entries (target %d)%n", dataset.size(), TARGET_SIZE);

        // Zápis do CSV pro reprodukovatelnost
        Path csv = Path.of("data/cpv_extended_dataset.csv");
        Files.createDirectories(csv.getParent());
        try (var w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            w.write("description,code,labelCs\n");
            for (CpvEntry e : dataset) {
                w.write("\"%s\",%s,\"%s\"%n".formatted(
                        e.description.replace("\"", "\"\""),
                        e.code,
                        e.labelCs.replace("\"", "\"\"")));
            }
        }
        System.out.printf("  Saved -> %s%n", csv);

        // 2. Unikátní CPV labels → embeddings
        Map<String, String> codeToLabel = new LinkedHashMap<>();
        for (CpvEntry e : dataset) {
            codeToLabel.put(e.code, e.labelCs);
        }
        System.out.printf("%nStep 2: Embedding %d unique CPV labels...%n",
                codeToLabel.size());
        List<String> codes = new ArrayList<>(codeToLabel.keySet());
        List<String> labels = codes.stream()
                .map(codeToLabel::get)
                .toList();
        float[][] catalogVecs = embedBatch(apiKey, labels);

        // 3. Pro každý popis najdi top-1 retrieval
        System.out.println("\nStep 3: Embedding 150 descriptions and computing top-1 retrieval...");
        List<String> descriptions = dataset.stream()
                .map(CpvEntry::description)
                .toList();
        float[][] descVecs = embedBatch(apiKey, descriptions);

        int top1Correct = 0;
        int top3Correct = 0;
        double sumCosine = 0;
        for (int i = 0; i < dataset.size(); i++) {
            CpvEntry truth = dataset.get(i);
            // Compute top-3 indices by cosine similarity (full sort is fine
            // here — the catalog is on the order of ~100 entries).
            int n = codes.size();
            int[] order = topKByCosine(descVecs[i], catalogVecs, Math.min(3, n));
            int top1Idx = order[0];
            sumCosine += cosine(descVecs[i], catalogVecs[top1Idx]);

            if (codes.get(top1Idx).equals(truth.code)) {
                top1Correct++;
                top3Correct++;
            } else {
                for (int rank = 1; rank < order.length; rank++) {
                    if (codes.get(order[rank]).equals(truth.code)) {
                        top3Correct++;
                        break;
                    }
                }
            }
        }

        EvalResult res = new EvalResult(
                dataset.size(),
                top1Correct, 100.0 * top1Correct / dataset.size(),
                top3Correct, 100.0 * top3Correct / dataset.size(),
                sumCosine / dataset.size(),
                codes.size()
        );
        System.out.println();
        System.out.println("=== RESULTS ===");
        System.out.printf("n total       : %d%n", res.n);
        System.out.printf("Top-1 correct : %d   (Top-1 accuracy: %.1f %%)%n",
                res.top1Correct, res.top1Accuracy);
        System.out.printf("Top-3 correct : %d   (Top-3 accuracy: %.1f %%)%n",
                res.top3Correct, res.top3Accuracy);
        System.out.printf("Distinct CPV  : %d%n", res.distinctCodes);
        System.out.printf("Mean cosine   : %.3f%n", res.meanCosine);
        System.out.println();
        System.out.printf("Wilson 95%% CI Top-1: %s%n", wilsonCi(res.top1Correct, res.n));
        System.out.printf("Wilson 95%% CI Top-3: %s%n", wilsonCi(res.top3Correct, res.n));
        Path out = Path.of("data/cpv_extended_summary.json");
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(res), StandardCharsets.UTF_8);
        System.out.printf("Saved -> %s%n", out);
    }

    /** Wilson score 95% confidence interval (1.96 z-score). */
    private static String wilsonCi(int x, int n) {
        if (n == 0) return "(undef)";
        double p = (double) x / n;
        double z = 1.96;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return "(%.1f %% – %.1f %%)".formatted(
                100.0 * (centre - half), 100.0 * (centre + half));
    }

    /** Generate (description, code, label) triples via GPT-4o (multiple iterations). */
    private static List<CpvEntry> generateDataset(String apiKey, int target)
            throws IOException, InterruptedException {
        List<CpvEntry> all = new ArrayList<>();
        Set<String> seenDesc = new HashSet<>();
        // 30 entries per call × 5 iterations = ~150 (with deduplication)
        int perCall = 30;
        for (int iter = 0; iter < 8 && all.size() < target; iter++) {
            String prompt = String.format(
                    "Generate %d unique industrial component descriptions with their "
                    + "ground-truth CPV (Common Procurement Vocabulary 2008) codes. "
                    + "Categories: pumps, motors, bearings, valves, fasteners, seals, "
                    + "filters, sensors, conveyors, hydraulics, pneumatics, electrical "
                    + "equipment, industrial fluids. Each description: 5-10 English words. "
                    + "Each CPV code: full 8-digit + check digit format (e.g. 42122000-0). "
                    + "Each label: official Czech CPV label (industrial procurement language). "
                    + "Avoid these descriptions already generated: %s",
                    perCall, summarize(seenDesc, 15));

            String content = chatJson(apiKey, prompt);
            Map<String, Object> parsed = MAPPER.readValue(content,
                    new TypeReference<>() {});
            Object termsObj = findArray(parsed);
            if (!(termsObj instanceof List<?> list)) continue;

            int added = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> mp)) continue;
                Object d = mp.get("description"), c = mp.get("code"), l = mp.get("labelCs");
                if (l == null) l = mp.get("label_cs");
                if (l == null) l = mp.get("label");
                if (d == null || c == null || l == null) continue;
                String desc = d.toString().trim();
                String code = c.toString().trim();
                String label = l.toString().trim();
                if (desc.length() < 3 || !code.matches("\\d{8}-\\d") || label.length() < 3) {
                    continue;
                }
                if (seenDesc.contains(desc.toLowerCase())) continue;
                seenDesc.add(desc.toLowerCase());
                all.add(new CpvEntry(desc, code, label));
                added++;
                if (all.size() >= target) break;
            }
            System.out.printf("  iter %d: +%d (total %d/%d)%n",
                    iter + 1, added, all.size(), target);
            Thread.sleep(400);
        }
        return all.subList(0, Math.min(all.size(), target));
    }

    private static String summarize(Set<String> set, int limit) {
        return set.stream().limit(limit)
                .reduce((a, b) -> a + "; " + b)
                .orElse("(none)");
    }

    private static Object findArray(Map<String, Object> root) {
        for (Object v : root.values()) {
            if (v instanceof List) return v;
        }
        return null;
    }

    private static String chatJson(String apiKey, String userPrompt)
            throws IOException {
        Map<String, Object> body = Map.of(
                "model", CHAT_MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Output strictly a JSON object with key \"items\": "
                                + "[{\"description\": ..., \"code\": ..., \"labelCs\": ...}, ...]. "
                                + "No commentary."),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.9,
                "response_format", Map.of("type", "json_object")
        );
        String resp = postJson(OPENAI_BASE + "/chat/completions",
                apiKey, MAPPER.writeValueAsBytes(body));
        Map<String, Object> root = MAPPER.readValue(resp, new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        return (String) msg.get("content");
    }

    /** Batch embedding: po 100 textech (limit OpenAI API). */
    private static float[][] embedBatch(String apiKey, List<String> texts)
            throws IOException {
        float[][] out = new float[texts.size()][];
        int batchSize = 100;
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> chunk = texts.subList(i, end);
            Map<String, Object> body = Map.of(
                    "model", EMBED_MODEL,
                    "input", chunk
            );
            String resp = postJson(OPENAI_BASE + "/embeddings",
                    apiKey, MAPPER.writeValueAsBytes(body));
            Map<String, Object> root = MAPPER.readValue(resp, new TypeReference<>() {});
            List<Map<String, Object>> data = (List<Map<String, Object>>) root.get("data");
            for (int k = 0; k < data.size(); k++) {
                List<Number> e = (List<Number>) data.get(k).get("embedding");
                float[] vec = new float[e.size()];
                for (int j = 0; j < e.size(); j++) {
                    vec[j] = e.get(j).floatValue();
                }
                out[i + k] = vec;
            }
            System.out.printf("    embed %d/%d%n", Math.min(i + batchSize, texts.size()),
                    texts.size());
        }
        return out;
    }

    /**
     * Vrací indexy top-K kandidátů podle kosinové podobnosti, sestupně
     * (tj. order[0] = nejbližší). Implementace: vytvoříme pole skóre,
     * pak provedeme částečné selection-sort pro K iterací (O(N·K)~--- pro
     * N\,$\approx$\,100 a~K\,=\,3 je to triviální).
     */
    private static int[] topKByCosine(float[] queryVec, float[][] catalog, int k) {
        int n = catalog.length;
        double[] scores = new double[n];
        for (int j = 0; j < n; j++) {
            scores[j] = cosine(queryVec, catalog[j]);
        }
        boolean[] used = new boolean[n];
        int[] order = new int[k];
        for (int rank = 0; rank < k; rank++) {
            int bestIdx = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < n; j++) {
                if (!used[j] && scores[j] > bestScore) {
                    bestScore = scores[j];
                    bestIdx = j;
                }
            }
            order[rank] = bestIdx;
            used[bestIdx] = true;
        }
        return order;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String postJson(String url, String bearer, byte[] body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL()
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
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
