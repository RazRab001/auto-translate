package cz.mendelu.auto.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Konfigurace asynchronního zpracování.
 *
 * <p>Vytváří dedikovaný ThreadPool pro překladové úlohy oddělený
 * od HTTP request threads, aby dlouhé volání externích NMT API
 * (typicky 200–900 ms) neblokovala přijímání nových požadavků.
 *
 * <p>Velikost poolu je konfigurovatelná v {@code application.yml}.
 * Výchozí hodnoty (4/16) jsou kompromisem mezi paralelizací volání
 * NMT API a zatížením testovacího hostitele.
 */
@Slf4j
@Configuration
public class AsyncConfig {

    @Value("${app.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:100}")
    private int queueCapacity;

    @Bean(name = "translationTaskExecutor")
    public Executor translationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("trans-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        log.info("Initialized translation task executor: core={}, max={}, queue={}",
                 corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * Propaguje SLF4J MDC kontext (např. correlationId
     * z~{@code GlobalExceptionHandler}, jobId, requestId) z~REST vlákna
     * na~asynchronní worker, aby všechny logy patřící k~jednomu request
     * sdílely stejný correlation kontext. Bez decoratoru by orchestrator
     * logy běžely bez MDC a~operátor by je nemohl propojit s~chybou viděnou
     * klientem.
     *
     * <p>Finally vždy vyčistí MDC ({@code clear()}),
     * nikdy se neobnovuje &bdquo;previous&ldquo; kontext. Worker thread
     * v~poolu může být znovu použit pro úplně nesouvisející úlohu, takže
     * MDC z~předchozí úlohy nesmí přežít~--- jinak by sentinel klíč utekl
     * do~logů následujícího requestu.
     */
    static final class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> snapshot = MDC.getCopyOfContextMap();
            return () -> {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    // Always clear; pooled worker may be reused for an
                    // unrelated next task whose submitter expects empty MDC.
                    MDC.clear();
                }
            };
        }
    }
}
