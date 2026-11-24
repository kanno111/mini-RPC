package io.github.kaano.minirpc.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 把 Provider 业务线程池的关键运行状态注册到 Micrometer。
 *
 * <p>活跃线程数和排队任务数会随运行状态上下变化，因此使用 Gauge；拒绝次数从
 * Provider 启动后只会递增，因此使用 FunctionCounter。三个指标都直接读取业务
 * 线程池已有的运行快照，不在指标层维护第二份状态。</p>
 */
public final class ProviderBusinessExecutorMetrics {

    /** 当前正在执行 RPC 业务任务的线程数。 */
    public static final String ACTIVE_THREADS_METRIC =
            "mini.rpc.provider.executor.active";

    /** 当前仍在有界队列中等待执行的 RPC 业务任务数。 */
    public static final String QUEUED_TASKS_METRIC =
            "mini.rpc.provider.executor.queued";

    /** Provider 启动以来业务线程池累计拒绝的任务数。 */
    public static final String REJECTED_TASKS_METRIC =
            "mini.rpc.provider.executor.rejected";

    /*
     * 显式保存查询函数的强引用。Gauge 和 FunctionCounter 采样时会回调本对象，
     * 再读取 Spring 管理的同一个 RpcProvider 中的实时线程池状态。
     */
    private final IntSupplier activeThreadCountSupplier;
    private final IntSupplier queuedTaskCountSupplier;
    private final LongSupplier rejectedTaskCountSupplier;

    /**
     * 注册两个当前值 Gauge 和一个累计拒绝 FunctionCounter。
     *
     * @param meterRegistry Micrometer 指标注册表
     * @param activeThreadCountSupplier 当前活跃业务线程数查询函数
     * @param queuedTaskCountSupplier 当前排队业务任务数查询函数
     * @param rejectedTaskCountSupplier 累计拒绝业务任务数查询函数
     */
    public ProviderBusinessExecutorMetrics(
            MeterRegistry meterRegistry,
            IntSupplier activeThreadCountSupplier,
            IntSupplier queuedTaskCountSupplier,
            LongSupplier rejectedTaskCountSupplier
    ) {
        Objects.requireNonNull(meterRegistry, "meterRegistry 不能为空");
        this.activeThreadCountSupplier = Objects.requireNonNull(
                activeThreadCountSupplier,
                "活跃业务线程数查询函数不能为空"
        );
        this.queuedTaskCountSupplier = Objects.requireNonNull(
                queuedTaskCountSupplier,
                "排队业务任务数查询函数不能为空"
        );
        this.rejectedTaskCountSupplier = Objects.requireNonNull(
                rejectedTaskCountSupplier,
                "拒绝业务任务数查询函数不能为空"
        );

        Gauge.builder(
                        ACTIVE_THREADS_METRIC,
                        this,
                        ProviderBusinessExecutorMetrics::activeThreadCount
                )
                .description("mini-RPC Provider 当前活跃业务线程数")
                .register(meterRegistry);

        Gauge.builder(
                        QUEUED_TASKS_METRIC,
                        this,
                        ProviderBusinessExecutorMetrics::queuedTaskCount
                )
                .description("mini-RPC Provider 当前排队业务任务数")
                .register(meterRegistry);

        FunctionCounter.builder(
                        REJECTED_TASKS_METRIC,
                        this,
                        ProviderBusinessExecutorMetrics::rejectedTaskCount
                )
                .description("mini-RPC Provider 累计拒绝业务任务数")
                .register(meterRegistry);
    }

    private int activeThreadCount() {
        return activeThreadCountSupplier.getAsInt();
    }

    private int queuedTaskCount() {
        return queuedTaskCountSupplier.getAsInt();
    }

    private long rejectedTaskCount() {
        return rejectedTaskCountSupplier.getAsLong();
    }
}
