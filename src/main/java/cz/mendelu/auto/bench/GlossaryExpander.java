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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rozšiřovač terminologického glosáře EN→CS pro DeepL Glossary API.
 *
 * <p>Vstup: existující ručně sestavený glosář (35 entries),
 * kategorizovaný do tematických clusterů: bearings, valves, fasteners,
 * seals, motors, pumps, sensors, fluids, mechanical assemblies, electrical.
 *
 * <p>Postup:
 * <ol>
 *   <li>Pro každou tematickou kategorii zavoláme OpenAI Chat Completions
 *       s instrukcí vygenerovat N termínů jako TSV (EN&lt;TAB&gt;CS).</li>
 *   <li>Termíny se filtrují (duplicity, malformované řádky).</li>
 *   <li>Sjednoceno s původním 35-entry glosářem do TSV souboru
 *       {@code data/glossary_en_cs_extended.tsv}.</li>
 * </ol>
 *
 * <p>Cílový rozsah: ≥200 entries pro snížení glossary leakage.
 *
 * <p>Spuštění:
 * <pre>{@code
 *   export OPENAI_API_KEY=sk-...
 *   java -cp target/classes cz.mendelu.auto.bench.GlossaryExpander
 * }</pre>
 */
public final class GlossaryExpander {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o";
    private static final int TERMS_PER_CATEGORY = 25;

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry("bearings", "ball bearings, roller bearings, thrust bearings"),
            Map.entry("valves",   "ball valves, butterfly valves, check valves, "
                                + "pressure relief valves, solenoid valves"),
            Map.entry("fasteners","hex bolts, lock nuts, washers, screws, "
                                + "studs, threaded rods"),
            Map.entry("seals",    "O-rings, mechanical seals, gaskets, oil seals"),
            Map.entry("motors",   "electric motors, induction motors, "
                                + "frequency converters, gearboxes"),
            Map.entry("pumps",    "centrifugal pumps, hydraulic pumps, "
                                + "vacuum pumps, gear pumps"),
            Map.entry("sensors",  "proximity sensors, pressure sensors, "
                                + "flow meters, temperature probes"),
            Map.entry("fluids",   "lubricating oils, greases, coolants, "
                                + "hydraulic fluids"),
            Map.entry("electrical", "PLC modules, contactors, relays, "
                                + "circuit breakers, frequency inverters"),
            Map.entry("piping",   "pipe fittings, flanges, elbows, "
                                + "expansion joints, couplings"),
            Map.entry("filters",  "filter cartridges, filter housings, "
                                + "industrial air filters"),
            Map.entry("belts",    "V-belts, timing belts, conveyor belts")
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a professional Czech industrial maintenance terminologist
            following ČSN EN 13306. You generate JSON arrays of EN→CS term pairs
            for a DeepL Glossary API used in IBM Maximo EAM integration.

            CRITICAL: You MUST return exactly the requested number of terms.
            Do not return fewer. Do not return commentary.

            Few-shot examples (for category "bearings"):
            [
              {"en": "ball bearing",        "cs": "kuličkové ložisko"},
              {"en": "roller bearing",      "cs": "válečkové ložisko"},
              {"en": "thrust bearing",      "cs": "axiální ložisko"},
              {"en": "needle bearing",      "cs": "jehlové ložisko"},
              {"en": "self-aligning bearing","cs": "naklápěcí ložisko"}
            ]

            Format requirements:
            - Output is a JSON array of objects with keys "en" and "cs".
            - English terms: 1-4 words, lowercase, no numbers, no brand names.
            - Czech terms: established industrial Czech, no English loanwords
              unless universally accepted (e.g. "ventilátor" OK, "valve" not).
            - Avoid duplicates of these terms which are already in the glossary:
              %s
            """;

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: OPENAI_API_KEY environment variable not set.");
            System.exit(1);
        }

        // 1. Načíst existující glosář (35 entries)
        Path baseGlossary = Path.of("data/glossary_en_cs.tsv");
        Map<String, String> merged = new LinkedHashMap<>();
        if (Files.exists(baseGlossary)) {
            for (String line : Files.readAllLines(baseGlossary, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    merged.put(parts[0].trim().toLowerCase(), parts[1].trim());
                }
            }
            System.out.printf("Loaded %d existing entries from %s%n",
                    merged.size(), baseGlossary);
        }

        // 2. Pro každou kategorii vygenerovat ~25 termínů (2 iterace pro
        //    diverzitu)
        for (Map.Entry<String, String> cat : CATEGORIES.entrySet()) {
            System.out.printf("Generating ~%d entries for category '%s'...%n",
                    TERMS_PER_CATEGORY, cat.getKey());
            int before = merged.size();
            for (int iter = 0; iter < 2; iter++) {
                try {
                    List<String[]> generated = generateForCategory(
                            apiKey, cat.getKey(), cat.getValue(),
                            TERMS_PER_CATEGORY, merged.keySet());
                    for (String[] pair : generated) {
                        merged.putIfAbsent(pair[0].toLowerCase(), pair[1]);
                    }
                } catch (Exception e) {
                    System.err.printf("  iter %d FAIL: %s%n", iter, e.getMessage());
                }
                Thread.sleep(400);
            }
            System.out.printf("  +%d new (%d total)%n",
                    merged.size() - before, merged.size());
        }

        // 3. Zápis výsledku
        Path out = Path.of("data/glossary_en_cs_extended.tsv");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder();
        merged.forEach((en, cs) -> sb.append(en).append('\t').append(cs).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.printf("%nDone. %d entries → %s%n", merged.size(), out);
    }

    private static List<String[]> generateForCategory(
            String apiKey, String category, String description, int count,
            Set<String> existingTerms
    ) throws IOException, InterruptedException {
        // Limit "avoid these" na 30 ukazek aby nebyl prompt obriy
        String existingPreview = existingTerms.stream()
                .limit(30)
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
        String system = SYSTEM_PROMPT.formatted(existingPreview);
        String userPrompt = String.format(
                "Category: \"%s\" (%s).%nGenerate %d term pairs as JSON array.%n"
                + "Return ONLY a JSON object: {\"terms\": [{\"en\":..., \"cs\":...}, ...]}",
                category, description, count);
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "temperature", 0.9,
                "response_format", Map.of("type", "json_object")
        );
        byte[] json = MAPPER.writeValueAsBytes(body);
        String responseBody = postJson(OPENAI_URL, apiKey, json);

        // Vytáhneme content z chat completions JSON
        Map<String, Object> root = MAPPER.readValue(responseBody,
                new TypeReference<>() {});
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");

        // Parse JSON: očekáváme {"terms": [{"en":..., "cs":...}, ...]}
        Map<String, Object> parsed = MAPPER.readValue(content,
                new TypeReference<>() {});
        Object termsObj = parsed.get("terms");
        if (!(termsObj instanceof List)) {
            // Některé modely vracejí top-level array; zkusme alternativní klíče
            for (Object v : parsed.values()) {
                if (v instanceof List) {
                    termsObj = v;
                    break;
                }
            }
        }
        List<String[]> result = new ArrayList<>();
        if (termsObj instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> mp)) continue;
                Object enObj = mp.get("en");
                Object csObj = mp.get("cs");
                if (enObj == null || csObj == null) continue;
                String en = enObj.toString().trim().replaceAll("[\"']", "");
                String cs = csObj.toString().trim().replaceAll("[\"']", "");
                if (en.isBlank() || cs.isBlank()
                        || en.length() > 80 || cs.length() > 100) {
                    continue;
                }
                result.add(new String[]{en, cs});
            }
        }
        return result;
    }

    /**
     * Synchronní POST přes {@link HttpURLConnection} (kompatibilita
     * s prostředím bez podpory NIO selectors).
     */
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
