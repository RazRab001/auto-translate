package cz.mendelu.auto.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Pomocný redirect: <code>GET /console</code> &rarr;
 * <code>/console.html</code> (statický soubor v
 * {@code src/main/resources/static/}).
 *
 * <p>Konzole je jednoduchá single-page aplikace bez build-step (vanilla
 * HTML+JS). Volá existující REST endpointy a~ukazuje:
 * <ul>
 *   <li>Health stav služby</li>
 *   <li>Metadata zdrojové DB (schéma, sloupce)</li>
 *   <li>Statistiky katalogu (počet záznamů, jazyků, překladů)</li>
 *   <li>Obsah Elastic-TM po jazycích (browser)</li>
 *   <li>SQL-proxy demo (Mode 1)</li>
 *   <li>Simple translate demo (Mode 2)</li>
 * </ul>
 */
@Controller
public class ConsoleController {

    @GetMapping({"/", "/console"})
    public RedirectView console() {
        return new RedirectView("/console.html");
    }
}
