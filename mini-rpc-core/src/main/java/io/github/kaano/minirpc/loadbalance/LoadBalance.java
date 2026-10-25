package io.github.kaano.minirpc.loadbalance;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;

import java.util.List;

/**
 * RPC 客户端的负载均衡接口。
 *
 * <p>服务发现可能找到多个能够提供同一服务的 Provider：</p>
 *
 * <pre>
 * HelloService
 *     ├── 127.0.0.1:8080
 *     ├── 127.0.0.1:8081
 *     └── 127.0.0.1:8082
 * </pre>
 *
 * <p>一次 RPC 请求只需要发送给其中一个 Provider。负载均衡器的职责，
 * 就是从候选地址列表中选出本次请求要使用的那个地址。</p>
 *
 * <p>把它定义为接口，是为了让选择策略可以替换。例如可以有轮询、随机、
 * 加权轮询等不同实现。业务代码不会直接调用该接口，它最终会被封装在
 * RPC 客户端内部。</p>
 */
public interface LoadBalance {

    /**
     * 从一个服务的候选 Provider 地址中选择一个地址。
     *
     * @param serviceKey 完整服务身份；不同版本可以维护各自的选择状态
     * @param addresses  服务发现返回的全部可用 Provider 地址
     * @return 本次 RPC 请求应当连接的 Provider 地址
     * @throws RpcException 服务名称无效或候选地址列表为空时抛出
     */
    ServiceAddress select(
            ServiceKey serviceKey,
            List<ServiceAddress> addresses
    );

    /** 兼容早期只传接口名的教学代码。 */
    default ServiceAddress select(
            String serviceName,
            List<ServiceAddress> addresses
    ) {
        return select(ServiceKey.defaultService(serviceName), addresses);
    }
}
