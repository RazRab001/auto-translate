package cz.mendelu.auto.bench;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Replikace algoritmu OmegaT FuzzyMatcher (Levenshtein na tokenech).
 *
 * <p>Třída {@code org.omegat.core.matching.FuzzyMatcher} v OmegaT 6.0.0
 * implementuje stejnou logiku. Tato replikace slouží k získání
 * konkrétních fuzzy match skóre per pár (kategorie C, n=15). Skutečný
 * benchmark byl proveden přímým spuštěním OmegaT 6.0.0 v režimu
 * {@code console-translate}.
 *
 * <p>Spuštění:
 * <pre>{@code
 *   mvn -B exec:java -Dexec.mainClass=cz.mendelu.auto.bench.OmegaTFuzzyMatcher
 * }</pre>
 */
public final class OmegaTFuzzyMatcher {

    private static final Pattern TOKEN_RE = Pattern.compile("[^A-Za-z0-9]+");

    /** Výchozí fuzzy threshold v OmegaT (ekvivalent FuzzyMatcher.MIN_SCORE). */
    public static final int DEFAULT_THRESHOLD = 70;

    private OmegaTFuzzyMatcher() {
    }

    /** Tokenizace odpovídající LuceneEnglishTokenizer v OmegaT. */
    static String[] tokenize(String s) {
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        return Arrays.stream(TOKEN_RE.split(s.toLowerCase()))
                .filter(t -> !t.isEmpty())
                .toArray(String[]::new);
    }

    /** Levenshtein distance mezi dvěma sekvencemi tokenů. */
    static int levenshtein(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int sub = a[i - 1].equals(b[j - 1]) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + sub
                );
            }
        }
        return dp[m][n];
    }

    /**
     * Fuzzy match skóre v procentech (0..100).
     * Odpovídá metodě {@code FuzzyMatcher.score()}:
     * {@code similarity = 1 - distance / max(|a|, |b|)}.
     */
    public static int score(String query, String tmEntry) {
        String[] q = tokenize(query);
        String[] t = tokenize(tmEntry);
        if (q.length == 0 || t.length == 0) {
            return 0;
        }
        int distance = levenshtein(q, t);
        int maxLen = Math.max(q.length, t.length);
        return Math.round((1.0f - (float) distance / maxLen) * 100);
    }

    /** Všech 15 párů Kategorie C použitých v benchmarku. */
    public static final List<String[]> CATEGORY_C_PAIRS = Arrays.asList(
            new String[]{"Bearing 6204, ball, 20x47x14",
                         "Ball bearing 6204-2RS, inner bore 20mm, outer 47mm"},
            new String[]{"Water pump centrifugal 500W",
                         "Centrifugal pump for water, 0.5 kW motor"},
            new String[]{"Hex bolt M12x60 stainless",
                         "Screw M12, length 60mm, A2-70, hexagon head"},
            new String[]{"Oil seal shaft 40mm NBR",
                         "Radial shaft seal ring, 40x62x10, NBR material"},
            new String[]{"Filter 10 micron, cartridge type",
                         "Replacement filter element 10um, replaceable cartridge"},
            new String[]{"Electric motor 7.5kW 4-pole",
                         "Induction motor, 7500W, 1450 rpm, 400V 3-phase"},
            new String[]{"V-belt SPB2500",
                         "Drive belt type SPB, effective length 2500mm"},
            new String[]{"Mechanical seal SiC",
                         "Shaft seal, silicon carbide faces, single spring"},
            new String[]{"Grease NLGI 2, 400g",
                         "Lithium grease consistency grade 2, cartridge 400g"},
            new String[]{"Proximity switch 24V NPN",
                         "Inductive sensor, NPN output, 24VDC, M18"},
            new String[]{"Solenoid valve 24V DC, normally closed, G1/4",
                         "Electromagnetic valve G1/4, 24VDC, NC type"},
            new String[]{"Pressure gauge glycerine, 0-16 bar, G1/4",
                         "Glycerine manometer 16 bar, G1/4 connection"},
            new String[]{"Roller chain 12.7mm pitch, 08B-1, DIN 8187",
                         "Drive chain 08B single strand, pitch 1/2 inch"},
            new String[]{"Pneumatic cylinder bore 63mm, stroke 200mm",
                         "Double acting air cylinder D63, 200mm stroke"},
            new String[]{"PTFE flat seal DN50 PN16, thickness 2mm",
                         "Flat gasket PTFE, DN50 PN16, 2mm thick"}
    );

    public static void main(String[] args) {
        int threshold = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_THRESHOLD;
        int hits = 0;
        int sum = 0;
        System.out.printf("OmegaT FuzzyMatcher replication, threshold=%d%%%n", threshold);
        System.out.println("-".repeat(80));
        for (int i = 0; i < CATEGORY_C_PAIRS.size(); i++) {
            String[] pair = CATEGORY_C_PAIRS.get(i);
            int s = score(pair[0], pair[1]);
            sum += s;
            if (s >= threshold) {
                hits++;
            }
            System.out.printf("Pair %2d: %3d%% %s | %s%n", i + 1, s,
                    truncate(pair[0], 35), truncate(pair[1], 35));
        }
        System.out.println("-".repeat(80));
        System.out.printf("Mean: %d%%, Hits: %d/%d at threshold %d%%%n",
                sum / CATEGORY_C_PAIRS.size(), hits, CATEGORY_C_PAIRS.size(), threshold);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 2) + "..";
    }
}
