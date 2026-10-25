package io.github.kaano.minirpc.loadbalance;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按顺序循环选择 Provider 的轮询负载均衡器。
 *
 * <p>假设 HelloService 有三个地址，连续调用时的选择顺序是：</p>
 *
 * <pre>
 * 第 1 次 -> 8080
 * 第 2 次 -> 8081
 * 第 3 次 -> 8082
 * 第 4 次 -> 8080（重新从第一个地址开始）
 * </pre>
 *
 * <p>轮询不能保证每个 Provider 的实时压力完全相同，但规则简单、结果直观，
 * 非常适合作为 mini-RPC 的第一个负载均衡实现。</p>
 */
public final class RoundRobinLoadBalance implements LoadBalance {

    /**
     * 保存每个服务“下一次轮到第几次”的计数器。
     *
     * <p>key 是完整 ServiceKey，value 是该路由自己的调用次数。同一接口的
     * 1.0 和 2.0 各自拥有计数器，不会互相影响轮询顺序。</p>
     *
     * <p>{@link ConcurrentHashMap} 允许多个业务线程安全地同时访问不同服务；
     * {@link AtomicInteger} 保证多个线程同时调用同一个服务时，递增操作不会丢失。</p>
     */
    private final ConcurrentHashMap<ServiceKey, AtomicInteger> counters =
            new ConcurrentHashMap<>();

    /**
     * 根据当前服务的调用次数，按顺序选择一个 Provider 地址。
     */
    @Override
    public ServiceAddress select(
            ServiceKey serviceKey,
            List<ServiceAddress> addresses
    ) {
        validateArguments(serviceKey, addresses);

        /*
         * computeIfAbsent 的含义是：
         * 1. Map 中已经有该服务的计数器，就直接取出来；
         * 2. 还没有，就创建一个从 0 开始的新计数器并放入 Map。
         */
        AtomicInteger counter = counters.computeIfAbsent(
                serviceKey,
                ignoredServiceKey -> new AtomicInteger(0)
        );

        /*
         * getAndIncrement() 会先返回当前数字，再把计数器加 1。
         * floorMod(调用次数, 地址数量) 会把结果稳定限制在 0 到 size - 1：
         *
         * 调用次数：0  1  2  3  4  5 ...
         * 地址下标：0  1  2  0  1  2 ...（假设一共有 3 个地址）
         *
         * 使用 floorMod 而不是简单的 %，还能正确处理计数器运行很久以后
         * 从 Integer.MAX_VALUE 溢出为负数的情况。
         */
        int selectedIndex = Math.floorMod(
                counter.getAndIncrement(),
                addresses.size()
        );

        return addresses.get(selectedIndex);
    }

    /** 在真正选择前给出明确错误，避免出现难以理解的下标越界异常。 */
    private void validateArguments(
            ServiceKey serviceKey,
            List<ServiceAddress> addresses
    ) {
        if (serviceKey == null) {
            throw new RpcException("负载均衡使用的 ServiceKey 不能为空");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(
                    "没有可供负载均衡选择的 Provider，服务：" + serviceKey
            );
        }
        if (addresses.stream().anyMatch(address -> address == null)) {
            throw new RpcException(
                    "负载均衡的 Provider 地址列表中不能包含 null，服务：" + serviceKey
            );
        }
    }
}
