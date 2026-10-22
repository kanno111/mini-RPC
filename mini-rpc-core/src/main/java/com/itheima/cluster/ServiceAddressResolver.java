package com.itheima.cluster;

import com.itheima.common.RpcException;
import com.itheima.registry.ServiceAddress;

/**
 * 把“服务名称”解析成“本次调用应该使用的具体 Provider 地址”。
 *
 * <p>客户端代理生成的 {@code RpcRequest} 只携带接口名称，例如：</p>
 *
 * <pre>
 * com.itheima.example.api.HelloService
 * </pre>
 *
 * <p>但 Netty 建立连接时必须知道具体的 host 和 port，例如：</p>
 *
 * <pre>
 * 127.0.0.1:8080
 * </pre>
 *
 * <p>该接口负责完成两者之间的转换。具体实现通常会先进行服务发现，
 * 得到多个地址，再通过负载均衡选出一个地址。</p>
 *
 * <p>将这段逻辑单独抽象出来后，Netty 客户端只负责连接和网络收发，
 * 不需要知道地址来自 ZooKeeper、配置文件还是其他注册中心。</p>
 */
public interface ServiceAddressResolver {

    /**
     * 为一次 RPC 调用解析出一个具体的 Provider 地址。
     *
     * @param serviceName 服务标识，当前使用接口的全限定名
     * @return 本次调用应连接的 Provider 地址
     * @throws RpcException 服务名称无效、没有可用 Provider 或地址解析失败时抛出
     */
    ServiceAddress resolve(String serviceName);
}
