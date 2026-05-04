package cz.mendelu.auto.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizace průmyslových zkratek a~unit-symbolů před sémantickým
 * vyhledáváním v~Translation Memory.
 *
 * <p><b>Motivace.</b> Threshold-sweep experiment ($n=507$, F1\,=\,0,90
 * při prahu 0,80) odhalil dva páry, které sémantická TM nedokázala
 * rozpoznat ani při nízkém prahu:
 * <ul>
 *   <li><i>"SiC mechanical seal"</i> ↔ <i>"silicon carbide mechanical
 *       seal"</i> (chemická zkratka),</li>
 *   <li><i>"10 micron filter"</i> ↔ <i>"10µ filter"</i> (Unicode μ vs.
 *       slovní zápis),</li>
 *   <li><i>"Ø50 mm shaft"</i> ↔ <i>"50mm diameter shaft"</i>,</li>
 *   <li><i>"PN16"</i> ↔ <i>"pressure rating 16 bar"</i>,</li>
 *   <li>a~další~--- všechny vedly k~poklesu recall na~$\approx0{,}43$.</li>
 * </ul>
 *
 * <p><b>Strategie.</b> Bezpečné, idempotentní substituce ve dvou krocích:
 * <ol>
 *   <li>Unicode unit-symbol → ASCII slovní expanze
 *       (μ → "micron", Ø → "diameter", °C → "deg C"),</li>
 *   <li>Doménově specifické chemické / inženýrské zkratky → plné názvy
 *       (SiC → "silicon carbide", PTFE → "polytetrafluoroethylene", ...).</li>
 * </ol>
 *
 * <p>Substituce běží pouze při \emph{indexaci a~lookup} v~TM (tj. před
 * embeddingem); překládaný text do~NMT API odchází nezměněn, aby se
 * neztratila terminologická přesnost překladu.
 *
 * <p>Pravidla jsou \emph{přidávací}, nikoli nahrazovací: výsledek vždy
 * obsahuje původní zkratku <em>i</em> rozšíření, oddělené mezerou. Tím
 * se zachová recall pro vstupy obsahující zkratku <em>i</em> pro vstupy
 * obsahující plné slovo:
 * <pre>
 *   "SiC mechanical seal"  → "SiC silicon carbide mechanical seal"
 *   "silicon carbide seal" → "silicon carbide seal"  (no-op, plný název již přítomen)
 * </pre>
 */
@Component
public class TextNormalizer {

    /** Industrial chemistry / material abbreviations. */
    private static final Map<Pattern, String> ABBREVIATIONS = new LinkedHashMap<>();
    /** Unit symbol → spelled-out form. */
    private static final Map<Pattern, String> UNIT_SYMBOLS = new LinkedHashMap<>();

    static {
        // Word-boundary regex (Unicode-aware) so we don't accidentally
        // touch substrings inside other words.
        ABBREVIATIONS.put(wb("SiC"),    "silicon carbide");
        ABBREVIATIONS.put(wb("PTFE"),   "polytetrafluoroethylene");
        ABBREVIATIONS.put(wb("PVC"),    "polyvinyl chloride");
        ABBREVIATIONS.put(wb("PVDF"),   "polyvinylidene fluoride");
        ABBREVIATIONS.put(wb("EPDM"),   "ethylene propylene rubber");
        ABBREVIATIONS.put(wb("NBR"),    "nitrile butadiene rubber");
        ABBREVIATIONS.put(wb("FKM"),    "fluoroelastomer");
        ABBREVIATIONS.put(wb("HDPE"),   "high-density polyethylene");
        ABBREVIATIONS.put(wb("LDPE"),   "low-density polyethylene");
        ABBREVIATIONS.put(wb("ABS"),    "acrylonitrile butadiene styrene");
        ABBREVIATIONS.put(wb("AISI"),   "American Iron and Steel Institute");
        ABBREVIATIONS.put(wb("SAE"),    "Society of Automotive Engineers");
        // DN/PN are typically followed immediately by digits (DN50, PN16);
        // standard \b word boundary fails between N and digit because both
        // are \w chars. Use lookaheads instead.
        ABBREVIATIONS.put(Pattern.compile("(?U)\\bDN(?=\\d)"), "nominal diameter");
        ABBREVIATIONS.put(Pattern.compile("(?U)\\bPN(?=\\d)"), "nominal pressure");

        // Unicode unit symbols (μ = U+03BC, µ = U+00B5 micro sign — both must match).
        UNIT_SYMBOLS.put(Pattern.compile("[μµ]m?\\b"), "micron");
        UNIT_SYMBOLS.put(Pattern.compile("Ø"),         "diameter");
        UNIT_SYMBOLS.put(Pattern.compile("°C\\b"),    "deg C");
        UNIT_SYMBOLS.put(Pattern.compile("°F\\b"),    "deg F");
        UNIT_SYMBOLS.put(Pattern.compile("Ω\\b"),     "ohm");
    }

    private static Pattern wb(String token) {
        // Use Unicode-aware word boundary.
        return Pattern.compile("(?U)\\b" + Pattern.quote(token) + "\\b");
    }

    /**
     * Vrátí normalizovanou variantu vstupu pro embedding/lookup.
     * Idempotent: opakované volání nemění výsledek.
     */
    public String normalizeForEmbedding(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        // 1. Unit symbols (always inject the expansion if not already present).
        for (Map.Entry<Pattern, String> e : UNIT_SYMBOLS.entrySet()) {
            result = appendIfMissing(result, e.getKey(), e.getValue());
        }
        // 2. Industrial abbreviations.
        for (Map.Entry<Pattern, String> e : ABBREVIATIONS.entrySet()) {
            result = appendIfMissing(result, e.getKey(), e.getValue());
        }
        // Collapse runs of whitespace introduced by appends.
        return result.replaceAll("\\s{2,}", " ").trim();
    }

    /**
     * Pokud {@code matcher} najde výskyt v~textu a~text ještě neobsahuje
     * {@code expansion} (case-insensitive), připojíme expansion jako
     * volný token za~prvním výskytem. Tím se zachová původní zkratka
     * pro doslovný překlad i~obohatí text o~rozšířený tvar pro embedding.
     */
    private static String appendIfMissing(String text, Pattern p, String expansion) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return text;
        }
        if (text.toLowerCase().contains(expansion.toLowerCase())) {
            return text;  // already expanded — idempotent no-op
        }
        // Insert expansion after first match.
        int end = m.end();
        return text.substring(0, end) + " " + expansion + text.substring(end);
    }
}
