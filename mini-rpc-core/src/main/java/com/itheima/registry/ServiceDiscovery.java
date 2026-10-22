package com.itheima.registry;

import com.itheima.common.RpcException;

import java.util.List;

/**
 * 服务发现接口，由 RPC 服务消费者（Consumer）使用。
 *
 * <p>客户端只知道想调用的服务名称，不应该提前写死某一台 Provider 的地址。
 * 服务发现负责查询当前所有可用地址：</p>
 *
 * <pre>
 * com.itheima.example.api.HelloService
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
     * @param serviceName 服务标识；当前主要使用接口全限定名
     * @return 当前发现的 Provider 地址列表
     * @throws RpcException 参数无效或查询注册中心失败时抛出
     */
    List<ServiceAddress> discover(String serviceName);
}
