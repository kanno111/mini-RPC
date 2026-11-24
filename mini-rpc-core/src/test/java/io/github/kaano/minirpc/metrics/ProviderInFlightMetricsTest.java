package io.github.kaano.minirpc.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Gauge 每次采样当前值，而不是像 Counter 一样累计历史值。 */
class ProviderInFlightMetricsTest {

    @Test
    void shouldExposeCurrentValueThatCanIncreaseAndDecrease() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger currentInFlightRequests = new AtomicInteger();
        new ProviderInFlightMetrics(
                registry,
                currentInFlightRequests::get
        );

        assertEquals(0.0, gaugeValue(registry));

        currentInFlightRequests.set(4);
        assertEquals(4.0, gaugeValue(registry));

        currentInFlightRequests.set(1);
        assertEquals(1.0, gaugeValue(registry));

        currentInFlightRequests.set(0);
        assertEquals(0.0, gaugeValue(registry));
    }

    private double gaugeValue(SimpleMeterRegistry registry) {
        return registry.get(
                ProviderInFlightMetrics.IN_FLIGHT_REQUESTS_METRIC
        ).gauge().value();
    }
}
