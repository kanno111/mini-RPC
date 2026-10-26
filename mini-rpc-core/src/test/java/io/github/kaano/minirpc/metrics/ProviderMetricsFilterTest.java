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

/** 验证 Provider 指标覆盖业务响应结果、异常和责任链处理耗时。 */
class ProviderMetricsFilterTest {

    @Test
    void shouldRecordSuccessFailureAndDuration() {
        MockClock clock = new MockClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry(
                SimpleConfig.DEFAULT,
                clock
        );
        ProviderMetricsFilter filter = new ProviderMetricsFilter(registry);
        RpcRequest request = RpcRequest.builder().requestId(901L).build();

        RpcResponse success = filter.filter(request, currentRequest -> {
            clock.add(Duration.ofMillis(7));
            return RpcResponse.success(currentRequest.getRequestId(), "ok");
        });
        RpcResponse failure = filter.filter(request, currentRequest -> {
            clock.add(Duration.ofMillis(13));
            return RpcResponse.failure(
                    currentRequest.getRequestId(),
                    new RpcException("服务执行失败")
            );
        });

        assertEquals(RpcResponse.SUCCESS_CODE, success.getCode());
        assertEquals(RpcResponse.FAILURE_CODE, failure.getCode());
        assertEquals(2.0, counterValue(
                registry,
                ProviderMetricsFilter.REQUESTS_METRIC
        ));
        assertEquals(1.0, counterValue(
                registry,
                ProviderMetricsFilter.SUCCESS_METRIC
        ));
        assertEquals(1.0, counterValue(
                registry,
                ProviderMetricsFilter.FAILURE_METRIC
        ));
        assertEquals(2, registry.get(ProviderMetricsFilter.DURATION_METRIC)
                .timer().count());
        assertEquals(20.0,
                registry.get(ProviderMetricsFilter.DURATION_METRIC)
                        .timer().totalTime(TimeUnit.MILLISECONDS),
                0.001);
    }

    @Test
    void shouldRecordThrownExceptionAsFailureAndKeepPropagatingIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProviderMetricsFilter filter = new ProviderMetricsFilter(registry);
        RpcException filterFailure = new RpcException("Provider Filter 异常");

        RpcException actual = assertThrows(
                RpcException.class,
                () -> filter.filter(
                        RpcRequest.builder().requestId(902L).build(),
                        ignoredRequest -> {
                            throw filterFailure;
                        }
                )
        );

        assertSame(filterFailure, actual);
        assertEquals(1.0, counterValue(
                registry,
                ProviderMetricsFilter.REQUESTS_METRIC
        ));
        assertEquals(0.0, counterValue(
                registry,
                ProviderMetricsFilter.SUCCESS_METRIC
        ));
        assertEquals(1.0, counterValue(
                registry,
                ProviderMetricsFilter.FAILURE_METRIC
        ));
        assertEquals(1, registry.get(ProviderMetricsFilter.DURATION_METRIC)
                .timer().count());
    }

    private double counterValue(
            SimpleMeterRegistry registry,
            String metricName
    ) {
        return registry.get(metricName).counter().count();
    }
}
