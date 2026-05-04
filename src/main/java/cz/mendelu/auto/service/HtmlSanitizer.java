package cz.mendelu.auto.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ochrana HTML formátování při překladu.
 *
 * <p>Pole {@code LONGDESCRIPTION} v IBM Maximo často obsahuje
 * HTML tagy ({@code <b>}, {@code <p>}, {@code <div>}, {@code <br>}),
 * které musí být zachovány v cílovém textu. Některé NMT služby
 * (zejm. OpenAI bez explicitní instrukce) tagy systematicky
 * odstraňují, což pro kategorii B znamená pokles BLEU z ~36 na ~6.
 *
 * <p>Mechanismus:
 * <ol>
 *   <li>Před překladem nahrazení tagů jednoznačnými placeholdery
 *       ({@code <br>} → {@code {BR}})</li>
 *   <li>Bezpečné odeslání textu bez HTML do API</li>
 *   <li>Po překladu obnovení původních tagů</li>
 * </ol>
 *
 * <p>Pro DeepL a Google jsou nativně dostupné parametry
 * {@code tag_handling=html} a {@code format=html}, které tagy
 * zachovávají automaticky (DeepL 100 %, Google 83 %).
 * Tato třída slouží primárně pro OpenAI a fallback scénáře.
 */
@Component
public class HtmlSanitizer {

    private static final Map<String, String> TAG_MAP = new LinkedHashMap<>();

    static {
        // Pořadí důležité: nejdříve uzavírací tagy (pak otevírací),
        // aby '</b>' nebylo nahrazeno jako '<b>' + '/'.
        TAG_MAP.put("</b>",   "{/B}");
        TAG_MAP.put("</p>",   "{/P}");
        TAG_MAP.put("</div>", "{/DIV}");
        TAG_MAP.put("</i>",   "{/I}");
        TAG_MAP.put("<br>",   "{BR}");
        TAG_MAP.put("<br/>",  "{BR}");
        TAG_MAP.put("<br />", "{BR}");
        TAG_MAP.put("<b>",    "{B}");
        TAG_MAP.put("<p>",    "{P}");
        TAG_MAP.put("<div>",  "{DIV}");
        TAG_MAP.put("<i>",    "{I}");
    }

    /**
     * Nahrazení HTML tagů placeholdery před voláním NMT API.
     *
     * @param html vstupní text s HTML
     * @return text s placeholdery místo tagů
     */
    public String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String result = html;
        for (Map.Entry<String, String> e : TAG_MAP.entrySet()) {
            result = result.replace(e.getKey(), e.getValue());
        }
        return result;
    }

    /**
     * Obnovení původních HTML tagů po dokončení překladu.
     *
     * @param textWithPlaceholders text s placeholdery
     * @return text s původními HTML tagy
     */
    public String restore(String textWithPlaceholders) {
        if (textWithPlaceholders == null || textWithPlaceholders.isEmpty()) {
            return textWithPlaceholders;
        }
        String result = textWithPlaceholders;
        for (Map.Entry<String, String> e : TAG_MAP.entrySet()) {
            result = result.replace(e.getValue(), e.getKey());
        }
        return result;
    }
}
