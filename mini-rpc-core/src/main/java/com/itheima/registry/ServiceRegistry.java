package com.itheima.registry;

import com.itheima.common.RpcException;

/**
 * 服务注册接口，由 RPC 服务提供者（Provider）使用。
 *
 * <p>它解决的问题是：告诉注册中心“某项服务目前运行在哪里”。例如：</p>
 *
 * <pre>
 * com.itheima.example.api.HelloService
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
 *     <li>ServiceProvider 保存“接口名 → 当前进程中的实现对象”；</li>
 *     <li>ServiceRegistry 保存“接口名 → Provider 的网络地址”。</li>
 * </ul>
 */
public interface ServiceRegistry {

    /**
     * 注册一个可以提供指定服务的网络地址。
     *
     * <p>同一个 serviceName 以后可以对应多个地址，代表同一服务启动了多个实例。</p>
     *
     * @param serviceName 服务标识；当前主要使用接口全限定名
     * @param address     提供该服务的主机和端口
     * @throws RpcException 参数无效或注册中心操作失败时抛出
     */
    void register(String serviceName, ServiceAddress address);

    /**
     * 注销一个服务地址。
     *
     * <p>Provider 正常关闭时可以主动调用；ZooKeeper 临时节点还会在连接断开后
     * 自动消失，用于处理进程崩溃、断网等来不及主动注销的情况。</p>
     *
     * @param serviceName 服务标识
     * @param address     要移除的 Provider 地址
     * @throws RpcException 参数无效或注册中心操作失败时抛出
     */
    void unregister(String serviceName, ServiceAddress address);
}
