package io.github.kaano.minirpc.cluster;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;

/**
 * 把“服务名称”解析成“本次调用应该使用的具体 Provider 地址”。
 *
 * <p>客户端代理生成的 {@code RpcRequest} 会把接口、group 和 version 组成
 * {@link ServiceKey}，例如：</p>
 *
 * <pre>
 * HelloService + gray + 2.0
 * </pre>
 *
 * <p>但 Netty 建立连接时必须知道具体的 host 和 port，例如：</p>
 *
 * <pre>
 * 127.0.0.1:8080
 * </pre>
 *
 * <p>该接口负责完成服务身份到地址的转换。具体实现会先发现完全匹配该
 * ServiceKey 的地址，再通过负载均衡选出一个地址。</p>
 *
 * <p>将这段逻辑单独抽象出来后，Netty 客户端只负责连接和网络收发，
 * 不需要知道地址来自 ZooKeeper、配置文件还是其他注册中心。</p>
 */
public interface ServiceAddressResolver {

    /**
     * 为一次 RPC 调用解析出一个具体的 Provider 地址。
     *
     * @param serviceKey 接口、分组和版本组成的服务身份
     * @return 本次调用应连接的 Provider 地址
     * @throws RpcException 服务名称无效、没有可用 Provider 或地址解析失败时抛出
     */
    ServiceAddress resolve(ServiceKey serviceKey);

    /** 兼容早期固定地址和只传接口名的教学代码。 */
    default ServiceAddress resolve(String serviceName) {
        return resolve(ServiceKey.defaultService(serviceName));
    }
}
