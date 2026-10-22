package com.itheima.registry.zookeeper;

import com.itheima.common.RpcException;
import com.itheima.registry.ServiceAddress;
import com.itheima.registry.ServiceRegistry;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

/**
 * 使用 ZooKeeper 保存 Provider 地址的服务注册实现。
 *
 * <p>ZooKeeper 使用一棵树来组织数据。树上的每一个位置都叫节点（znode），
 * 节点之间通过类似文件路径的形式表示父子关系。例如：</p>
 *
 * <pre>
 * /mini-rpc                                      父节点
 *     /services                                  子节点
 *         /com.itheima.example.api.HelloService 服务名称节点
 *             /127.0.0.1:8080                   Provider 地址节点
 * </pre>
 *
 * <p>这里使用两种生命周期不同的节点：</p>
 *
 * <ul>
 *     <li>服务目录是持久节点：创建它的程序断开后，目录仍然保留；</li>
 *     <li>Provider 地址是临时节点：它和当前 Curator 会话绑定，会话失效后自动删除。</li>
 * </ul>
 *
 * <p>因此 Provider 如果崩溃，虽然来不及主动执行 unregister，ZooKeeper 仍能在
 * 会话失效后删除地址，避免 Consumer 一直取得已经不可用的服务器。</p>
 */
public final class ZooKeeperServiceRegistry
        implements ServiceRegistry, AutoCloseable {

    /**
     * mini-RPC 在 ZooKeeper 中保存服务信息的根路径。
     *
     * <p>所有服务都会放在这个节点下面，避免和 ZooKeeper 中其他系统的数据混在一起。</p>
     */
    public static final String ROOT_PATH = "/mini-rpc/services";

    /** 负责与 ZooKeeper 服务端通信的 Curator 客户端。 */
    private final CuratorFramework client;

    /** 防止关闭后继续注册，也让重复调用 close() 保持安全。 */
    private volatile boolean closed;

    /** 使用本地默认地址 {@code 127.0.0.1:2181} 创建注册中心。 */
    public ZooKeeperServiceRegistry() {
        this(ZooKeeperClientFactory.DEFAULT_CONNECT_STRING);
    }

    /**
     * 连接指定 ZooKeeper 服务并创建注册中心。
     *
     * @param connectString ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     */
    public ZooKeeperServiceRegistry(String connectString) {
        this.client = ZooKeeperClientFactory.createAndStart(connectString);
    }

    /**
     * 把一个 Provider 地址注册为服务名称下面的临时子节点。
     *
     * <p>例如注册 HelloService 和 127.0.0.1:8080，最终节点路径是：</p>
     *
     * <pre>
     * /mini-rpc/services/com.itheima.example.api.HelloService/127.0.0.1:8080
     * </pre>
     *
     * <p>{@code creatingParentsIfNeeded()} 会在父目录不存在时自动创建父目录；
     * 这些自动创建的父目录默认是持久节点。最后一级地址节点通过
     * {@link CreateMode#EPHEMERAL} 明确指定为临时节点。</p>
     */
    @Override
    public void register(String serviceName, ServiceAddress address) {
        validateAvailable();
        validateArguments(serviceName, address);

        String providerPath = buildProviderPath(serviceName, address);

        try {
            client.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(providerPath);
        } catch (KeeperException.NodeExistsException exception) {
            /*
             * 相同会话重复注册相同地址时，节点已经存在。
             * 目标状态已经达到，因此把重复注册当成成功，而不是让 Provider 启动失败。
             */
        } catch (Exception exception) {
            throw new RpcException(
                    "注册 RPC 服务地址失败，节点：" + providerPath,
                    exception
            );
        }
    }

    /**
     * 主动删除一个 Provider 地址节点。
     *
     * <p>临时节点在会话关闭时本来就会自动删除；主动注销的意义是正常停机时
     * 立即让地址消失，不必等待会话超时。</p>
     */
    @Override
    public void unregister(String serviceName, ServiceAddress address) {
        validateAvailable();
        validateArguments(serviceName, address);

        String providerPath = buildProviderPath(serviceName, address);

        try {
            client.delete().forPath(providerPath);
        } catch (KeeperException.NoNodeException exception) {
            // 节点已经不存在，说明注销想要达到的结果已经满足，直接返回即可。
        } catch (Exception exception) {
            throw new RpcException(
                    "注销 RPC 服务地址失败，节点：" + providerPath,
                    exception
            );
        }
    }

    /** 根据服务名称和地址拼出完整 ZooKeeper 节点路径。 */
    private String buildProviderPath(
            String serviceName,
            ServiceAddress address
    ) {
        return ROOT_PATH + "/" + serviceName.trim() + "/" + address.asText();
    }

    /**
     * 校验服务名称和地址，避免把无法组成合法层级的数据写入 ZooKeeper。
     */
    private void validateArguments(
            String serviceName,
            ServiceAddress address
    ) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new RpcException("注册中心的服务名称不能为空");
        }
        if (serviceName.contains("/")) {
            throw new RpcException("服务名称不能包含路径分隔符 /：" + serviceName);
        }
        if (address == null) {
            throw new RpcException("注册中心的服务地址不能为空");
        }
    }

    /** 客户端关闭后不能继续操作，否则临时节点的生命周期将无法保证。 */
    private void validateAvailable() {
        if (closed) {
            throw new RpcException("ZooKeeper 服务注册中心已经关闭");
        }
    }

    /**
     * 关闭 Curator 客户端以及它维护的 ZooKeeper 会话。
     *
     * <p>会话关闭后，本实例创建的所有临时 Provider 地址节点都会由 ZooKeeper
     * 自动删除。父级持久目录不会被删除，可以留给服务下次启动继续使用。</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        client.close();
    }
}
