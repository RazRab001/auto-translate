package cz.mendelu.auto.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * Idempotency store pro {@code POST /api/v1/translate}.
 *
 * <p>Sleduje dvojici (Idempotency-Key, payload-fingerprint, jobId).
 * Při opakovaném volání:
 * <ul>
 *   <li>Stejný klíč &amp; stejný payload-fingerprint → vrátí původní jobId
 *       (legitimate retry)</li>
 *   <li>Stejný klíč &amp; jiný payload → indikace neshody (controller
 *       vrátí HTTP 422 Unprocessable Entity, dle <a
 *       href="https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-04.html">
 *       IETF idempotency-key-header draft</a>)</li>
 *   <li>Nový klíč → nová úloha</li>
 * </ul>
 *
 * <p>Window 24~h pokrývá realistické MIF retry scénáře.
 */
@Slf4j
@Component
public class IdempotencyStore {

    @Value("${app.idempotency.ttl-hours:24}")
    private long ttlHours = 24L;

    @Value("${app.idempotency.max-entries:1000000}")
    private long maxEntries = 1_000_000L;

    @Getter @Builder
    public static class Record {
        private final String jobId;
        private final String payloadFingerprint;
    }

    /** Outcome of {@link #findOrRegister}. */
    public enum Outcome {
        NEW,            // klíč neviděn, vytvořena nová úloha
        REPLAY,         // stejný klíč + stejný payload → vrátit jobId
        KEY_MISMATCH    // stejný klíč + JINÝ payload → HTTP 422
    }

    public record Resolution(Outcome outcome, String jobId, String storedFingerprint) {}

    private Cache<String, Record> store;

    @PostConstruct
    public void init() {
        this.store = Caffeine.newBuilder()
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .maximumSize(maxEntries)
                .recordStats()
                .build();
        log.info("Initialized IdempotencyStore: ttl={}h, max={}",
                ttlHours, maxEntries);
    }

    /**
     * Atomicky vyhodnotí klíč a~vrátí outcome.
     *
     * @param idempotencyKey klíč z~hlavičky (nebo SHA-256 fingerprint payloadu)
     * @param payloadFingerprint SHA-256 obsahu zprávy (pro detekci neshody)
     * @param newJobId       jobId k~zaregistrování pokud klíč neviděn
     * @return rozhodnutí: NEW (zaregistrováno), REPLAY (vraťme jobId),
     *         KEY_MISMATCH (controller musí vrátit 422)
     */
    public synchronized Resolution findOrRegister(
            String idempotencyKey, String payloadFingerprint, String newJobId
    ) {
        if (idempotencyKey == null) {
            return new Resolution(Outcome.NEW, newJobId, payloadFingerprint);
        }
        Record existing = store.getIfPresent(idempotencyKey);
        if (existing == null) {
            store.put(idempotencyKey,
                    Record.builder()
                            .jobId(newJobId)
                            .payloadFingerprint(payloadFingerprint)
                            .build());
            return new Resolution(Outcome.NEW, newJobId, payloadFingerprint);
        }
        if (existing.getPayloadFingerprint().equals(payloadFingerprint)) {
            return new Resolution(Outcome.REPLAY, existing.getJobId(),
                    existing.getPayloadFingerprint());
        }
        return new Resolution(Outcome.KEY_MISMATCH, existing.getJobId(),
                existing.getPayloadFingerprint());
    }

    /**
     * Vrací deterministický fingerprint payloadu pro detekci shody.
     */
    public static String fingerprint(String sourceText, String sourceLang,
                                     String targetLang, boolean forceApi) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String key = sourceText + "|" + sourceLang + "|" + targetLang
                       + "|" + forceApi;
            byte[] hash = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void clear() {
        store.invalidateAll();
    }

    public long size() {
        return store.estimatedSize();
    }
}
