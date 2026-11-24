package io.github.kaano.minirpc.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证业务线程池指标始终读取当前线程池状态，并使用正确的指标类型。 */
class ProviderBusinessExecutorMetricsTest {

    @Test
    void shouldExposeActiveQueuedAndRejectedValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger activeThreads = new AtomicInteger();
        AtomicInteger queuedTasks = new AtomicInteger();
        AtomicLong rejectedTasks = new AtomicLong();

        new ProviderBusinessExecutorMetrics(
                registry,
                activeThreads::get,
                queuedTasks::get,
                rejectedTasks::get
        );

        assertEquals(0.0, activeThreads(registry));
        assertEquals(0.0, queuedTasks(registry));
        assertEquals(0.0, rejectedTasks(registry));

        activeThreads.set(2);
        queuedTasks.set(5);
        rejectedTasks.set(3);

        assertEquals(2.0, activeThreads(registry));
        assertEquals(5.0, queuedTasks(registry));
        assertEquals(3.0, rejectedTasks(registry));

        activeThreads.set(1);
        queuedTasks.set(0);
        rejectedTasks.set(4);

        assertEquals(1.0, activeThreads(registry));
        assertEquals(0.0, queuedTasks(registry));
        assertEquals(4.0, rejectedTasks(registry));
    }

    private double activeThreads(SimpleMeterRegistry registry) {
        return registry.get(
                ProviderBusinessExecutorMetrics.ACTIVE_THREADS_METRIC
        ).gauge().value();
    }

    private double queuedTasks(SimpleMeterRegistry registry) {
        return registry.get(
                ProviderBusinessExecutorMetrics.QUEUED_TASKS_METRIC
        ).gauge().value();
    }

    private double rejectedTasks(SimpleMeterRegistry registry) {
        return registry.get(
                ProviderBusinessExecutorMetrics.REJECTED_TASKS_METRIC
        ).functionCounter().count();
    }
}
