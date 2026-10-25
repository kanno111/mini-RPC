package io.github.kaano.minirpc.registry;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;

/**
 * 服务注册接口，由 RPC 服务提供者（Provider）使用。
 *
 * <p>它解决的问题是：告诉注册中心“某项服务目前运行在哪里”。例如：</p>
 *
 * <pre>
 * io.github.kaano.minirpc.example.api.HelloService
 *                 ↓ 注册
 *            127.0.0.1:8080
 * </pre>
 *
 * <p>这里只定义能力，不关心数据最终保存在 ZooKeeper、其他注册中心还是测试用
 * 内存实现中。后续的 ZooKeeper 实现会负责真正的网络通信和节点创建。</p>
 *
 * <p>不要和 {@code provider.ServiceProvider} 混淆：</p>
 *
 * <ul>
 *     <li>ServiceProvider 保存“ServiceKey → 当前进程中的实现对象”；</li>
 *     <li>ServiceRegistry 保存“ServiceKey → Provider 的网络地址”。</li>
 * </ul>
 */
public interface ServiceRegistry extends AutoCloseable {

    /**
     * 注册一个可以提供指定服务的网络地址。
     *
     * <p>同一个 ServiceKey 可以对应多个地址，代表同一版本启动了多个实例。</p>
     *
     * @param serviceKey 接口、分组和版本组成的服务身份
     * @param address    提供该服务的主机和端口
     * @throws RpcException 参数无效或注册中心操作失败时抛出
     */
    void register(ServiceKey serviceKey, ServiceAddress address);

    /** 兼容早期只传接口名的教学代码，自动使用 default/1.0。 */
    default void register(String serviceName, ServiceAddress address) {
        register(ServiceKey.defaultService(serviceName), address);
    }

    /**
     * 注销一个服务地址。
     *
     * <p>Provider 正常关闭时可以主动调用；ZooKeeper 临时节点还会在连接断开后
     * 自动消失，用于处理进程崩溃、断网等来不及主动注销的情况。</p>
     *
     * @param serviceKey 服务身份
     * @param address    要移除的 Provider 地址
     * @throws RpcException 参数无效或注册中心操作失败时抛出
     */
    void unregister(ServiceKey serviceKey, ServiceAddress address);

    /** 兼容早期只传接口名的教学代码，自动注销 default/1.0。 */
    default void unregister(String serviceName, ServiceAddress address) {
        unregister(ServiceKey.defaultService(serviceName), address);
    }

    /**
     * 关闭注册中心客户端以及它持有的网络连接。
     *
     * <p>把关闭能力定义在接口上后，RpcProvider 可以只依赖抽象接口，
     * 不必写死 ZooKeeper 实现。这也让测试可以使用不会连接真实 ZooKeeper 的
     * 内存替身。</p>
     */
    @Override
    void close();
}
