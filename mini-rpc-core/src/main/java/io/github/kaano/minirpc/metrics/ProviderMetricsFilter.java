package io.github.kaano.minirpc.metrics;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.provider.filter.ProviderFilter;
import io.github.kaano.minirpc.provider.filter.ProviderFilterChain;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * 记录 Provider 业务责任链处理请求的基础 Micrometer 指标。
 *
 * <p>Starter 会把本 Filter 排在用户 Provider Filter 之前，而框架内置的 deadline
 * Filter 仍然固定排在它之前。因此这里统计的是通过执行前 deadline 检查、真正进入
 * Provider 业务处理链的请求，耗时包含用户 Filter 与服务方法执行。</p>
 *
 * <p>服务方法异常通常会由 {@code RpcRequestHandler} 转换成失败响应；如果后续
 * Filter 意外直接抛出运行时异常，本 Filter 也会把它计为失败并保留原异常传播。</p>
 */
public final class ProviderMetricsFilter implements ProviderFilter {

    /** Provider 进入业务指标 Filter 的请求总数。 */
    public static final String REQUESTS_METRIC =
            "mini.rpc.provider.requests";

    /** Provider 产生成功响应的请求数。 */
    public static final String SUCCESS_METRIC =
            "mini.rpc.provider.requests.success";

    /** Provider 产生失败响应或抛出异常的请求数。 */
    public static final String FAILURE_METRIC =
            "mini.rpc.provider.requests.failure";

    /** Provider 业务责任链处理耗时。 */
    public static final String DURATION_METRIC =
            "mini.rpc.provider.duration";

    private final Counter requests;
    private final Counter successes;
    private final Counter failures;
    private final Timer duration;
    private final MeterRegistry meterRegistry;

    /**
     * 在指定注册表中创建或复用 Provider 指标。
     *
     * @param meterRegistry Micrometer 指标注册表，不能为 {@code null}
     */
    public ProviderMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry 不能为空"
        );
        this.requests = Counter.builder(REQUESTS_METRIC)
                .description("mini-RPC Provider 业务请求总数")
                .register(meterRegistry);
        this.successes = Counter.builder(SUCCESS_METRIC)
                .description("mini-RPC Provider 成功响应数")
                .register(meterRegistry);
        this.failures = Counter.builder(FAILURE_METRIC)
                .description("mini-RPC Provider 失败响应或异常数")
                .register(meterRegistry);
        this.duration = Timer.builder(DURATION_METRIC)
                .description("mini-RPC Provider 业务责任链处理耗时")
                .register(meterRegistry);
    }

    @Override
    public RpcResponse filter(
            RpcRequest request,
            ProviderFilterChain chain
    ) {
        requests.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            RpcResponse response = chain.proceed(request);
            if (response.getCode() == RpcResponse.SUCCESS_CODE) {
                successes.increment();
            } else {
                failures.increment();
            }
            return response;
        } catch (RuntimeException | Error exception) {
            failures.increment();
            throw exception;
        } finally {
            sample.stop(duration);
        }
    }
}
