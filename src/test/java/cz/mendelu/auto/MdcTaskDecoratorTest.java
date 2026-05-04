package cz.mendelu.auto;

import cz.mendelu.auto.config.AsyncConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link AsyncConfig#translationTaskExecutor()}
 * propagates SLF4J MDC from the submitting thread to the @Async worker AND
 * that MDC is cleared between two tasks reusing the same pooled worker
 * thread (no leak from prior task).
 */
class MdcTaskDecoratorTest {

    private Executor newExecutor() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "corePoolSize", 1);   // single thread → forces reuse
        ReflectionTestUtils.setField(config, "maxPoolSize", 1);
        ReflectionTestUtils.setField(config, "queueCapacity", 8);
        Executor exec = config.translationTaskExecutor();
        return exec;
    }

    @Test
    void mdcFromSubmitterPropagatesToWorker() throws Exception {
        Executor exec = newExecutor();
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        MDC.put("correlationId", "err-test-123");
        try {
            exec.execute(() -> {
                seen.set(MDC.get("correlationId"));
                done.countDown();
            });
        } finally {
            MDC.clear();
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo("err-test-123");

        ((ThreadPoolTaskExecutor) exec).shutdown();
    }

    @Test
    void mdcDoesNotLeakBetweenPooledTasks() throws Exception {
        Executor exec = newExecutor();
        CountDownLatch task1 = new CountDownLatch(1);
        CountDownLatch task2 = new CountDownLatch(1);
        AtomicReference<String> seenInTask2 = new AtomicReference<>("__sentinel__");

        // Task 1 submitted with MDC populated.
        MDC.put("correlationId", "first");
        try {
            exec.execute(() -> {
                // Worker sees "first" — but we don't assert that here, only that
                // task 2 (without MDC) doesn't see leftover "first".
                task1.countDown();
            });
        } finally {
            MDC.clear();
        }
        assertThat(task1.await(5, TimeUnit.SECONDS)).isTrue();

        // Task 2 submitted with NO MDC — must see empty MDC, not leftover "first".
        // (Submitter MDC is empty here, so snapshot == null → worker MDC.clear().)
        exec.execute(() -> {
            seenInTask2.set(MDC.get("correlationId"));  // expect null
            task2.countDown();
        });
        assertThat(task2.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seenInTask2.get()).isNull();

        ((ThreadPoolTaskExecutor) exec).shutdown();
    }
}
