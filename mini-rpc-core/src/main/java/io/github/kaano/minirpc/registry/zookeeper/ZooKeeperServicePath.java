package io.github.kaano.minirpc.registry.zookeeper;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 统一生成和解析 mini-RPC 的 ZooKeeper 路径。
 *
 * <p>接口名、group、version 和地址都来自框架外部，可能包含斜杠、空格、中文等
 * 字符。直接拼接会改变 ZooKeeper 树层级，因此每一段都使用 UTF-8 百分号编码。</p>
 */
final class ZooKeeperServicePath {

    private static final String PROVIDERS_NODE = "providers";

    private ZooKeeperServicePath() {
    }

    /** 返回某个完整服务身份的 Provider 地址目录。 */
    static String servicePath(ServiceKey serviceKey) {
        if (serviceKey == null) {
            throw new RpcException("生成 ZooKeeper 路径时 ServiceKey 不能为空");
        }
        return ZooKeeperServiceRegistry.ROOT_PATH
                + "/" + encode(serviceKey.interfaceName())
                + "/" + encode(serviceKey.group())
                + "/" + encode(serviceKey.version())
                + "/" + PROVIDERS_NODE;
    }

    /** 返回某个 Provider 临时地址节点的完整路径。 */
    static String providerPath(
            ServiceKey serviceKey,
            ServiceAddress address
    ) {
        if (address == null) {
            throw new RpcException("生成 ZooKeeper 路径时服务地址不能为空");
        }
        return servicePath(serviceKey) + "/" + encode(address.asText());
    }

    /** 把 Provider 子节点名称还原为 host:port。 */
    static ServiceAddress decodeProviderNode(String nodeName) {
        if (nodeName == null || nodeName.isBlank()) {
            throw new RpcException("ZooKeeper Provider 节点名称不能为空");
        }
        return ServiceAddress.fromText(
                URLDecoder.decode(nodeName, StandardCharsets.UTF_8)
        );
    }

    /** URL 编码中的加号容易被误读为空格，统一使用更明确的 %20。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
