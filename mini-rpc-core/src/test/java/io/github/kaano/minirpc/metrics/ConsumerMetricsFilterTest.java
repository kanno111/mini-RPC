package io.github.kaano.minirpc.metrics;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Consumer 指标覆盖成功、失败响应、异常以及完整调用耗时。 */
class ConsumerMetricsFilterTest {

    @Test
    void shouldRecordSuccessFailureAndDuration() {
        MockClock clock = new MockClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry(
                SimpleConfig.DEFAULT,
                clock
        );
        ConsumerMetricsFilter filter = new ConsumerMetricsFilter(registry);
        RpcRequest request = RpcRequest.builder().requestId(801L).build();

        RpcResponse success = filter.filter(request, currentRequest -> {
            clock.add(Duration.ofMillis(12));
            return RpcResponse.success(currentRequest.getRequestId(), "ok");
        });
        RpcResponse failure = filter.filter(request, currentRequest -> {
            clock.add(Duration.ofMillis(8));
            return RpcResponse.failure(
                    currentRequest.getRequestId(),
                    new RpcException("远程业务失败")
            );
        });

        assertEquals(RpcResponse.SUCCESS_CODE, success.getCode());
        assertEquals(RpcResponse.FAILURE_CODE, failure.getCode());
        assertEquals(2.0, counterValue(
                registry,
                ConsumerMetricsFilter.REQUESTS_METRIC
        ));
        assertEquals(1.0, counterValue(
                registry,
                ConsumerMetricsFilter.SUCCESS_METRIC
        ));
        assertEquals(1.0, counterValue(
                registry,
                ConsumerMetricsFilter.FAILURE_METRIC
        ));
        assertEquals(2, registry.get(ConsumerMetricsFilter.DURATION_METRIC)
                .timer().count());
        assertEquals(20.0,
                registry.get(ConsumerMetricsFilter.DURATION_METRIC)
                        .timer().totalTime(TimeUnit.MILLISECONDS),
                0.001);
    }

    @Test
    void shouldRecordThrownExceptionAsFailureAndKeepPropagatingIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerMetricsFilter filter = new ConsumerMetricsFilter(registry);
        RpcException networkFailure = new RpcException("网络连接失败");

        RpcException actual = assertThrows(
                RpcException.class,
                () -> filter.filter(
                        RpcRequest.builder().requestId(802L).build(),
                        ignoredRequest -> {
                            throw networkFailure;
                        }
                )
        );

        assertSame(networkFailure, actual);
        assertEquals(1.0, counterValue(
                registry,
                ConsumerMetricsFilter.REQUESTS_METRIC
        ));
        assertEquals(0.0, counterValue(
                registry,
                ConsumerMetricsFilter.SUCCESS_METRIC
        ));
        assertEquals(1.0, counterValue(
                registry,
                ConsumerMetricsFilter.FAILURE_METRIC
        ));
        assertEquals(1, registry.get(ConsumerMetricsFilter.DURATION_METRIC)
                .timer().count());
    }

    private double counterValue(
            SimpleMeterRegistry registry,
            String metricName
    ) {
        return registry.get(metricName).counter().count();
    }
}
