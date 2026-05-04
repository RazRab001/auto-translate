package cz.mendelu.auto.api;

import cz.mendelu.auto.api.dto.JobResponse;
import cz.mendelu.auto.api.dto.JobStatusResponse;
import cz.mendelu.auto.api.dto.SyncTranslationResponse;
import cz.mendelu.auto.api.dto.TranslationRequestDto;
import cz.mendelu.auto.connectors.TranslationProvider;
import cz.mendelu.auto.connectors.TranslationResult;
import cz.mendelu.auto.elasticsearch.TranslationRepository;
import cz.mendelu.auto.service.IdempotencyStore;
import cz.mendelu.auto.service.JobRegistry;
import cz.mendelu.auto.service.TranslationOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST kontrolér přijímající požadavky z IBM Maximo.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>{@code POST /api/v1/translate} → 202 Accepted + jobId
 *       (asynchronní zpracování)</li>
 *   <li>{@code GET  /api/v1/jobs/{jobId}} → status úlohy</li>
 *   <li>{@code POST /api/v1/translate/sync} → synchronní (pro
 *       benchmarky a development testy)</li>
 *   <li>{@code GET  /health} → liveness probe pro Kubernetes</li>
 *   <li>{@code GET  /metrics} → agregované runtime metriky</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Translation API", description = "Sidecar middleware pro IBM Maximo")
public class TranslationController {

    private final TranslationOrchestrator orchestrator;
    private final JobRegistry jobs;
    private final IdempotencyStore idempotency;
    private final TranslationRepository tmRepository;
    private final TranslationProvider provider;

    @Operation(summary = "Přijmout překladový požadavek",
            description = "Asynchronní endpoint: vrací HTTP 202 + jobId, "
                    + "samotné zpracování probíhá ve vyhrazeném ThreadPool. "
                    + "Podporuje header `Idempotency-Key` pro retry-safe "
                    + "deduplication: opakované volání se shodným klíčem "
                    + "v okně 24h vrátí původní jobId. Při shodném klíči "
                    + "ale odlišném payloadu vrací HTTP 422 (key collision).")
    @PostMapping("/translate")
    public ResponseEntity<?> submit(
            @org.springframework.web.bind.annotation.RequestHeader(
                    name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TranslationRequestDto req
    ) {
        // 1. Spočítáme payload fingerprint (pro detekci key collision)
        String payloadFp = IdempotencyStore.fingerprint(
                req.sourceText(), req.sourceLang(), req.targetLang(),
                Boolean.TRUE.equals(req.forceApiCall()));

        // 2. Pokud client neposlal explicit klíč, použijeme payload-fingerprint
        //    jako implicitní idempotency key (chrání proti duplicate retry
        //    bez vyžadování change na straně Maxima).
        String dedupKey = idempotencyKey != null ? idempotencyKey : payloadFp;

        // 3. Atomic flow: nejdřív zaregistrujeme job (prevent race kde
        //    konkurenční request s~tím samým klíčem narazí na neexistující
        //    originalJobId), pak idempotency check; při REPLAY/MISMATCH
        //    rollback nezúčastněného candidate job.
        String candidateJobId = "job-" + java.util.UUID.randomUUID();
        jobs.put(candidateJobId);
        IdempotencyStore.Resolution res = idempotency.findOrRegister(
                dedupKey, payloadFp, candidateJobId);

        return switch (res.outcome()) {
            case REPLAY -> {
                jobs.remove(candidateJobId);  // rollback: candidate nepoužit
                log.info("Idempotency REPLAY: key={} → jobId={}",
                        idempotencyKey != null ? idempotencyKey : "(impl)",
                        res.jobId());
                JobRegistry.JobRecord rec = jobs.get(res.jobId());
                String status = rec != null ? rec.getStatus().name() : "PROCESSING";
                yield ResponseEntity.accepted().body(
                        new JobResponse(res.jobId(), status, 0));
            }
            case KEY_MISMATCH -> {
                jobs.remove(candidateJobId);  // rollback: candidate nepoužit
                log.warn("Idempotency KEY_MISMATCH: key={} stored payload={} "
                        + "but received payload={}",
                        idempotencyKey, res.storedFingerprint(), payloadFp);
                yield ResponseEntity.unprocessableEntity().body(java.util.Map.of(
                        "error", "idempotency_key_mismatch",
                        "message", "Idempotency-Key already used with a different "
                                + "payload. Use a fresh key for new requests.",
                        "originalJobId", res.jobId()
                ));
            }
            case NEW -> {
                // candidateJobId už zaregistrován výše, jen spustíme zpracování
                orchestrator.processAsync(
                        candidateJobId, req.sourceText(), req.sourceLang(),
                        req.targetLang(), Boolean.TRUE.equals(req.forceApiCall()));
                yield ResponseEntity.accepted().body(
                        new JobResponse(candidateJobId, "PROCESSING", 1500));
            }
        };
    }

    @Operation(summary = "Zjistit stav úlohy")
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobStatusResponse> getStatus(
            @PathVariable String jobId
    ) {
        JobRegistry.JobRecord rec = jobs.get(jobId);
        if (rec == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new JobStatusResponse(
                rec.getJobId(),
                rec.getStatus().name(),
                rec.getTranslation(),
                rec.getProvider(),
                rec.getCacheHit(),
                rec.getCacheScore(),
                rec.getLatencyMs(),
                rec.getAttempts(),
                rec.getError()
        ));
    }

    @Operation(summary = "Synchronní překlad (pouze pro testy/benchmarky)")
    @PostMapping("/translate/sync")
    public SyncTranslationResponse translateSync(
            @Valid @RequestBody TranslationRequestDto req
    ) {
        TranslationResult res = orchestrator.processSync(
                req.sourceText(), req.sourceLang(), req.targetLang()
        );
        return new SyncTranslationResponse(
                res.text(), res.provider(), res.latencyMs(), res.attempts()
        );
    }

    @Operation(summary = "Health check (Kubernetes liveness)")
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("provider", provider.getName());
        body.put("tmRecords", tmRepository.count());
        body.put("activeJobs", jobs.size());  // Caffeine estimatedSize
        return body;
    }

    @Operation(summary = "Vyprázdnění Translation Memory (pouze pro testy)")
    @PostMapping("/cache/clear")
    public Map<String, Object> clearCache() {
        int before = tmRepository.count();
        tmRepository.clear();
        jobs.clearAll();
        Map<String, Object> body = new HashMap<>();
        body.put("cleared", true);
        body.put("recordsRemoved", before);
        return body;
    }
}
