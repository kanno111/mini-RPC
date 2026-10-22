package com.itheima.registry.zookeeper;

import com.itheima.common.RpcException;
import com.itheima.registry.ServiceAddress;
import com.itheima.registry.ServiceDiscovery;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;

import java.util.Comparator;
import java.util.List;

/**
 * 从 ZooKeeper 查询 Provider 地址的服务发现实现。
 *
 * <p>Provider 注册 HelloService 后，ZooKeeper 中的数据类似下面这棵树：</p>
 *
 * <pre>
 * /mini-rpc/services/com.itheima.example.api.HelloService
 *     /127.0.0.1:8080
 *     /127.0.0.1:8081
 * </pre>
 *
 * <p>这里的“发现”并不神秘：Consumer 先根据服务名找到对应目录，再读取该
 * 目录下所有子节点的名字。每一个子节点名字就是一个 Provider 地址。</p>
 *
 * <p>当前是最容易理解的基础版本：每调用一次 {@link #discover(String)}，
 * 都会向 ZooKeeper 查询一次。后面可以再加入本地缓存和节点变化监听，减少
 * 对 ZooKeeper 的重复访问。</p>
 */
public final class ZooKeeperServiceDiscovery
        implements ServiceDiscovery, AutoCloseable {

    /** 负责与 ZooKeeper 服务端通信的 Curator 客户端。 */
    private final CuratorFramework client;

    /** 标记当前对象是否已经关闭，防止关闭后继续查询。 */
    private volatile boolean closed;

    /** 使用本地默认地址 {@code 127.0.0.1:2181} 创建服务发现对象。 */
    public ZooKeeperServiceDiscovery() {
        this(ZooKeeperClientFactory.DEFAULT_CONNECT_STRING);
    }

    /**
     * 连接指定的 ZooKeeper 服务。
     *
     * @param connectString ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     */
    public ZooKeeperServiceDiscovery(String connectString) {
        this.client = ZooKeeperClientFactory.createAndStart(connectString);
    }

    /**
     * 查询能够提供指定服务的全部 Provider 地址。
     *
     * <p>{@code getChildren()} 读取的是“直接子节点名称”，不会递归读取更深层
     * 的节点。由于我们把地址直接放在子节点名称中，所以不需要再读取节点内容。</p>
     *
     * @param serviceName 服务标识，当前使用接口的全限定名
     * @return Provider 地址列表；服务尚未注册时返回空列表，不返回 {@code null}
     */
    @Override
    public List<ServiceAddress> discover(String serviceName) {
        validateAvailable();
        validateServiceName(serviceName);

        String servicePath = ZooKeeperServiceRegistry.ROOT_PATH
                + "/"
                + serviceName.trim();

        try {
            List<String> childNames = client.getChildren().forPath(servicePath);

            /*
             * ZooKeeper 返回的是 ["127.0.0.1:8080", "127.0.0.1:8081"]。
             * map 会把每段文字转换成 ServiceAddress 对象。
             * 排序不是 RPC 必需条件，只是让测试和日志中的结果顺序保持稳定。
             */
            return childNames.stream()
                    .map(ServiceAddress::fromText)
                    .sorted(
                            Comparator.comparing(ServiceAddress::host)
                                    .thenComparingInt(ServiceAddress::port)
                    )
                    .toList();
        } catch (KeeperException.NoNodeException exception) {
            /*
             * 服务目录不存在，说明现在没有任何 Provider 注册该服务。
             * 这是正常的“没有发现服务”结果，所以返回空列表，而不是当成系统故障。
             */
            return List.of();
        } catch (Exception exception) {
            throw new RpcException(
                    "从 ZooKeeper 发现 RPC 服务失败，服务路径：" + servicePath,
                    exception
            );
        }
    }

    /** 校验服务名，防止服务名意外改变 ZooKeeper 的路径层级。 */
    private void validateServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new RpcException("服务发现使用的服务名称不能为空");
        }
        if (serviceName.contains("/")) {
            throw new RpcException("服务名称不能包含路径分隔符 /：" + serviceName);
        }
    }

    /** 已关闭的发现对象不能继续访问 ZooKeeper。 */
    private void validateAvailable() {
        if (closed) {
            throw new RpcException("ZooKeeper 服务发现对象已经关闭");
        }
    }

    /**
     * 关闭 Curator 客户端及其后台线程。
     *
     * <p>服务发现对象只读取数据，没有创建临时节点，因此关闭它不会删除 Provider
     * 的注册地址。Provider 的临时节点由 Provider 自己的注册会话负责维护。</p>
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
