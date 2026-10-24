package com.itheima.loadbalance;

import com.itheima.common.RpcException;
import com.itheima.registry.ServiceAddress;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 从候选 Provider 中等概率随机选择一个地址。
 *
 * <p>随机策略不需要像轮询策略一样保存调用次数，因此实现比较简单。它适合各个
 * Provider 处理能力接近、请求耗时差异不大的场景；它只保证长期概率接近，不保证
 * 短时间内每个 Provider 收到完全相同的请求数。</p>
 */
public final class RandomLoadBalance implements LoadBalance {

    /**
     * 使用当前线程的随机数生成器选择一个合法下标。
     *
     * <p>{@link ThreadLocalRandom} 避免多个业务线程争用同一个随机数生成器。
     * {@code nextInt(size)} 返回的范围是 0（包含）到 size（不包含），刚好对应
     * List 的合法下标。</p>
     */
    @Override
    public ServiceAddress select(
            String serviceName,
            List<ServiceAddress> addresses
    ) {
        validateArguments(serviceName, addresses);
        int selectedIndex = ThreadLocalRandom.current()
                .nextInt(addresses.size());
        return addresses.get(selectedIndex);
    }

    /** 在随机选择前报告明确的 RPC 配置错误。 */
    private void validateArguments(
            String serviceName,
            List<ServiceAddress> addresses
    ) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new RpcException("负载均衡使用的服务名称不能为空");
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(
                    "没有可供负载均衡选择的 Provider，服务：" + serviceName
            );
        }
        if (addresses.stream().anyMatch(address -> address == null)) {
            throw new RpcException(
                    "负载均衡的 Provider 地址列表中不能包含 null，服务："
                            + serviceName
            );
        }
    }
}
