package io.github.kaano.minirpc.cluster;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.loadbalance.LoadBalance;
import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.registry.ServiceDiscovery;

import java.util.List;

/**
 * “服务发现 + 负载均衡”组成的默认地址解析器。
 *
 * <p>一次解析会依次经过下面两步：</p>
 *
 * <pre>
 * HelloService
 *      ↓ ServiceDiscovery：查询全部可用地址
 * [8080, 8081, 8082]
 *      ↓ LoadBalance：从列表中选择一个
 * 8080
 * </pre>
 *
 * <p>这个类只负责组织调用流程，不关心具体使用 ZooKeeper 还是其他注册中心，
 * 也不关心采用轮询还是随机算法。这些变化分别由两个接口的实现负责。</p>
 */
public final class DefaultServiceAddressResolver
        implements ServiceAddressResolver {

    /** 查询一个服务当前拥有的全部 Provider 地址。 */
    private final ServiceDiscovery serviceDiscovery;

    /** 从全部可用地址中选择本次调用的目标地址。 */
    private final LoadBalance loadBalance;

    /**
     * 创建默认地址解析器。
     *
     * @param serviceDiscovery 服务发现实现，不能为 {@code null}
     * @param loadBalance      负载均衡实现，不能为 {@code null}
     */
    public DefaultServiceAddressResolver(
            ServiceDiscovery serviceDiscovery,
            LoadBalance loadBalance
    ) {
        if (serviceDiscovery == null) {
            throw new RpcException("ServiceDiscovery 不能为空");
        }
        if (loadBalance == null) {
            throw new RpcException("LoadBalance 不能为空");
        }

        this.serviceDiscovery = serviceDiscovery;
        this.loadBalance = loadBalance;
    }

    /**
     * 查询全部可用 Provider，再让负载均衡器选择一个。
     */
    @Override
    public ServiceAddress resolve(ServiceKey serviceKey) {
        if (serviceKey == null) {
            throw new RpcException("解析 Provider 地址时，ServiceKey 不能为空");
        }

        List<ServiceAddress> addresses =
                serviceDiscovery.discover(serviceKey);

        /*
         * ServiceDiscovery 约定不返回 null，但这里仍然做防御性校验。
         * 如果某个自定义实现违反接口约定，调用方可以得到更清楚的错误信息。
         */
        if (addresses == null || addresses.isEmpty()) {
            throw new RpcException(
                    "没有发现可用的 RPC Provider，服务：" + serviceKey
            );
        }

        return loadBalance.select(serviceKey, addresses);
    }
}
