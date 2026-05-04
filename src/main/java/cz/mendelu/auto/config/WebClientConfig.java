package cz.mendelu.auto.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Konfigurace neblokujícího {@link WebClient} pro volání externích
 * NMT API (DeepL, Google, OpenAI).
 *
 * <p>Volba {@code WebClient} (Spring WebFlux) místo blokujícího
 * {@code RestTemplate} je odůvodněna v kap.~5.4: pro souběžné
 * zpracování desítek požadavků by blokující klient vyčerpal Thread Pool;
 * reaktivní WebClient uvolní vlákno okamžitě a zpracuje odpověď
 * v non-blocking režimu.
 *
 * <p>Timeout 30 s odpovídá výchozímu limitu MIF Invocation Channel
 * v IBM Maximo (kap.~4.3).
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}
