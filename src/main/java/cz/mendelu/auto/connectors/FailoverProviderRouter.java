package cz.mendelu.auto.connectors;

import cz.mendelu.auto.connectors.exceptions.PermanentProviderException;
import cz.mendelu.auto.connectors.exceptions.TransientProviderException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Multi-provider router s failover kaskádou.
 *
 * <p>Aktivuje se přes {@code ai.default-provider=failover}; vyžaduje, aby
 * v kontextu byly registrovány alespoň dva konkrétní {@link TranslationProvider}
 * beans. Router je injektován jako {@code @Primary}, aby ho
 * {@link cz.mendelu.auto.service.TranslationOrchestrator} používal jako
 * jediný provider.
 *
 * <p>Postup výběru provider'a:
 * <ol>
 *   <li>Seřaď providery dle priority z~konfigurace
 *       ({@code ai.failover.priority: [deepl, openai, google]}).</li>
 *   <li>Zavolej první provider; pokud uspěje, vrať výsledek.</li>
 *   <li>Při {@link TransientProviderException} (po vyčerpání retry)
 *       nebo {@link CallNotPermittedException} (CB OPEN) přejdi
 *       na~další provider v~pořadí.</li>
 *   <li>{@link PermanentProviderException} (4xx auth) nepokračuje na další~--- chyba
 *       je u~klienta, ne u~provideru.</li>
 *   <li>Pokud selhaly všichni, propaguj poslední transient výjimku.</li>
 * </ol>
 *
 * <p>Ve výsledku {@link TranslationResult#provider()} je uveden skutečně
 * použitý provider, takže auditní log zachytí failover chain.
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "ai.default-provider", havingValue = "failover")
public class FailoverProviderRouter implements TranslationProvider {

    @Value("${ai.failover.priority:deepl,openai,google}")
    private String priorityCsv = "deepl,openai,google";

    private final Map<String, TranslationProvider> providersByName;
    private List<TranslationProvider> orderedProviders;

    public FailoverProviderRouter(List<TranslationProvider> providers) {
        // Filtrujeme self-reference (Spring by jinak injektoval router do sebe)
        this.providersByName = providers.stream()
                .filter(p -> !(p instanceof FailoverProviderRouter))
                .collect(java.util.stream.Collectors.toMap(
                        TranslationProvider::getName, p -> p,
                        (a, b) -> a));
    }

    @PostConstruct
    public void init() {
        String[] order = priorityCsv.split(",");
        var found = new java.util.ArrayList<TranslationProvider>();
        for (String name : order) {
            String n = name.trim().toLowerCase();
            TranslationProvider p = providersByName.get(n);
            if (p != null) {
                found.add(p);
            } else {
                log.warn("Failover priority lists '{}' but bean is not in context", n);
            }
        }
        this.orderedProviders = found;
        if (orderedProviders.isEmpty()) {
            throw new IllegalStateException(
                    "FailoverProviderRouter: no providers available in context. "
                    + "Check ai.default-provider and that DeepL/OpenAI/Google "
                    + "are wired (their @ConditionalOnProperty must allow failover).");
        }
        log.info("Failover order: {}",
                orderedProviders.stream().map(TranslationProvider::getName).toList());
    }

    @Override
    public String getName() {
        return "failover";
    }

    @Override
    public TranslationResult translate(String text, String sourceLang, String targetLang) {
        TransientProviderException lastTransient = null;
        for (TranslationProvider p : orderedProviders) {
            try {
                TranslationResult result = p.translate(text, sourceLang, targetLang);
                log.info("Failover: success on provider={} (attempt order)",
                        result.provider());
                return result;
            } catch (PermanentProviderException permanent) {
                // 4xx auth/payload — chyba u klienta, žádný další provider nepomůže
                log.warn("Failover: permanent error on {}: {}",
                        p.getName(), permanent.getMessage());
                throw permanent;
            } catch (CallNotPermittedException cb) {
                log.info("Failover: circuit breaker OPEN on {}, trying next",
                        p.getName());
            } catch (TransientProviderException transient_) {
                log.warn("Failover: transient error on {}: {}, trying next",
                        p.getName(), transient_.getMessage());
                lastTransient = transient_;
            }
        }
        throw lastTransient != null
                ? new TransientProviderException(
                    "All " + orderedProviders.size() + " providers failed: "
                    + lastTransient.getMessage(), lastTransient)
                : new TransientProviderException(
                    "All " + orderedProviders.size() + " providers were unavailable "
                    + "(circuit breakers OPEN)");
    }
}
