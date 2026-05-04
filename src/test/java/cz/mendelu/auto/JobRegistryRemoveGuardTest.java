package cz.mendelu.auto;

import cz.mendelu.auto.service.JobRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JobRegistry.remove() must NOT silently delete a completed
 * job (DONE/ERROR), to protect against accidental rollback after orchestrator
 * has already finished. Only PROCESSING jobs may be invalidated.
 */
class JobRegistryRemoveGuardTest {

    private JobRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new JobRegistry();
        ReflectionTestUtils.setField(registry, "ttlMinutes", 60L);
        ReflectionTestUtils.setField(registry, "maxEntries", 1000L);
        registry.init();
    }

    @Test
    void removeOnProcessingJobInvalidates() {
        registry.put("job-A");
        assertThat(registry.exists("job-A")).isTrue();

        registry.remove("job-A");

        assertThat(registry.exists("job-A")).isFalse();
    }

    @Test
    void removeOnDoneJobIsNoOp() {
        registry.put("job-B");
        registry.markDone("job-B", "translated", "deepl",
                false, 0.0, 100L, 1);
        assertThat(registry.get("job-B").getStatus())
                .isEqualTo(JobRegistry.Status.DONE);

        // Accidental rollback after completion: must NOT delete the record.
        registry.remove("job-B");

        assertThat(registry.exists("job-B")).isTrue();
        assertThat(registry.get("job-B").getStatus())
                .isEqualTo(JobRegistry.Status.DONE);
        assertThat(registry.get("job-B").getTranslation()).isEqualTo("translated");
    }

    @Test
    void removeOnErrorJobIsNoOp() {
        registry.put("job-C");
        registry.markError("job-C", "upstream timeout");

        registry.remove("job-C");

        assertThat(registry.exists("job-C")).isTrue();
        assertThat(registry.get("job-C").getStatus())
                .isEqualTo(JobRegistry.Status.ERROR);
    }

    @Test
    void removeOnUnknownJobIsSilent() {
        // Should not throw or log loudly.
        registry.remove("does-not-exist");
        assertThat(registry.exists("does-not-exist")).isFalse();
    }
}
