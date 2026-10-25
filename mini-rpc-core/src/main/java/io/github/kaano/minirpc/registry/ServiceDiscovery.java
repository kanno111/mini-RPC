package io.github.kaano.minirpc.registry;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;

import java.util.List;

/**
 * 服务发现接口，由 RPC 服务消费者（Consumer）使用。
 *
 * <p>客户端只知道想调用的服务名称，不应该提前写死某一台 Provider 的地址。
 * 服务发现负责查询当前所有可用地址：</p>
 *
 * <pre>
 * io.github.kaano.minirpc.example.api.HelloService
 *                 ↓ 查询
 * ┌──────────────────────────────────┐
 * │ 127.0.0.1:8080                   │
 * │ 127.0.0.1:8081                   │
 * └──────────────────────────────────┘
 * </pre>
 *
 * <p>发现多个地址后，客户端不会同时调用所有地址。后续会交给负载均衡器，
 * 从列表中选择一个地址建立连接。</p>
 */
public interface ServiceDiscovery {

    /**
     * 查询能够提供指定服务的全部网络地址。
     *
     * <p>实现不应返回 {@code null}。如果当前没有可用实例，可以返回空列表，
     * 或直接抛出带有明确说明的 RpcException；具体规则会在实现 ZooKeeper 时统一。</p>
     *
     * @param serviceKey 接口、分组和版本组成的服务身份
     * @return 当前发现的 Provider 地址列表
     * @throws RpcException 参数无效或查询注册中心失败时抛出
     */
    List<ServiceAddress> discover(ServiceKey serviceKey);

    /** 兼容早期只传接口名的教学代码，自动发现 default/1.0。 */
    default List<ServiceAddress> discover(String serviceName) {
        return discover(ServiceKey.defaultService(serviceName));
    }
}
