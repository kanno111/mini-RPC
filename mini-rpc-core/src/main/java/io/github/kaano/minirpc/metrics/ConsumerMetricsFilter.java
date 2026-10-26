package io.github.kaano.minirpc.metrics;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.proxy.filter.ConsumerFilter;
import io.github.kaano.minirpc.proxy.filter.ConsumerFilterChain;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * 记录 Consumer 一次完整远程调用的基础 Micrometer 指标。
 *
 * <p>本 Filter 位于动态代理构造完 {@link RpcRequest} 之后、真正发送请求之前，
 * 因而耗时包含服务发现、选择 Provider、建立或复用连接、网络往返以及等待响应。
 * 请求正常返回失败响应和调用链直接抛出异常都会计入失败次数，耗时也都会记录。</p>
 *
 * <p>Micrometer 提供的 {@link Counter} 和 {@link Timer} 可以被多个业务线程安全
 * 共享；单次调用的计时样本只保存在 {@link #filter(RpcRequest, ConsumerFilterChain)}
 * 的局部变量中，不会在并发请求之间串数据。</p>
 */
public final class ConsumerMetricsFilter implements ConsumerFilter {

    /** Consumer 进入指标 Filter 的请求总数。 */
    public static final String REQUESTS_METRIC =
            "mini.rpc.consumer.requests";

    /** Consumer 得到成功响应的请求数。 */
    public static final String SUCCESS_METRIC =
            "mini.rpc.consumer.requests.success";

    /** Consumer 得到失败响应或抛出异常的请求数。 */
    public static final String FAILURE_METRIC =
            "mini.rpc.consumer.requests.failure";

    /** Consumer 完整远程调用耗时。 */
    public static final String DURATION_METRIC =
            "mini.rpc.consumer.duration";

    private final Counter requests;
    private final Counter successes;
    private final Counter failures;
    private final Timer duration;
    private final MeterRegistry meterRegistry;

    /**
     * 在指定注册表中创建或复用 Consumer 指标。
     *
     * @param meterRegistry Micrometer 指标注册表，不能为 {@code null}
     */
    public ConsumerMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry 不能为空"
        );
        this.requests = Counter.builder(REQUESTS_METRIC)
                .description("mini-RPC Consumer 请求总数")
                .register(meterRegistry);
        this.successes = Counter.builder(SUCCESS_METRIC)
                .description("mini-RPC Consumer 成功响应数")
                .register(meterRegistry);
        this.failures = Counter.builder(FAILURE_METRIC)
                .description("mini-RPC Consumer 失败响应或异常数")
                .register(meterRegistry);
        this.duration = Timer.builder(DURATION_METRIC)
                .description("mini-RPC Consumer 完整远程调用耗时")
                .register(meterRegistry);
    }

    @Override
    public RpcResponse filter(
            RpcRequest request,
            ConsumerFilterChain chain
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
            /*
             * 网络异常、超时以及后续 Filter 抛出的运行时异常都没有 RpcResponse，
             * 但对调用方仍然是失败，必须在异常继续向上传播前完成计数。
             */
            failures.increment();
            throw exception;
        } finally {
            sample.stop(duration);
        }
    }
}
