package io.github.kaano.minirpc.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * 把 Provider 当前在途请求数注册为 Micrometer Gauge。
 *
 * <p>Counter 适合累计“从启动以来发生过多少次”，Gauge 则适合读取“此刻还有
 * 多少”。在途请求会随着请求进入而增加、响应写回完成而减少，因此不能使用只增
 * 不减的 Counter。</p>
 *
 * <p>本类不维护第二份计数，只保存一个只读查询函数。Micrometer 读取 Gauge 时，
 * 查询函数会返回传输层 {@code InFlightRequestTracker} 当时的真实值。这样监控数据
 * 与 Provider 优雅关闭等待的计数始终来自同一个状态源。</p>
 */
public final class ProviderInFlightMetrics {

    /** Provider 当前已接收、但响应写回尚未完成的普通 RPC 请求数。 */
    public static final String IN_FLIGHT_REQUESTS_METRIC =
            "mini.rpc.provider.requests.inflight";

    /**
     * 强引用查询函数，避免 Gauge 使用的目标在应用运行期间被垃圾回收。
     * Spring 模式下本对象本身也是单例 Bean，会与 Provider 生命周期一致。
     */
    private final IntSupplier inFlightRequestCountSupplier;

    /**
     * 注册只读 Gauge。
     *
     * @param meterRegistry               Micrometer 指标注册表
     * @param inFlightRequestCountSupplier 返回当前在途请求数的只读函数
     */
    public ProviderInFlightMetrics(
            MeterRegistry meterRegistry,
            IntSupplier inFlightRequestCountSupplier
    ) {
        Objects.requireNonNull(meterRegistry, "meterRegistry 不能为空");
        this.inFlightRequestCountSupplier = Objects.requireNonNull(
                inFlightRequestCountSupplier,
                "在途请求数查询函数不能为空"
        );

        Gauge.builder(
                        IN_FLIGHT_REQUESTS_METRIC,
                        this,
                        ProviderInFlightMetrics::currentInFlightRequestCount
                )
                .description("mini-RPC Provider 当前在途请求数")
                .register(meterRegistry);
    }

    /** Micrometer 每次采样 Gauge 时都读取当前值，而不是返回历史累计值。 */
    private int currentInFlightRequestCount() {
        return inFlightRequestCountSupplier.getAsInt();
    }
}
