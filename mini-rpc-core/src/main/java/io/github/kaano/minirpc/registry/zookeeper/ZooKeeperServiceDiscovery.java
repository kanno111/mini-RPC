package io.github.kaano.minirpc.registry.zookeeper;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.registry.ServiceDiscovery;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 ZooKeeper 查询 Provider 地址的服务发现实现。
 *
 * <p>Provider 注册 HelloService 后，ZooKeeper 中的数据类似下面这棵树：</p>
 *
 * <pre>
 * /mini-rpc/services/io.github.kaano.minirpc.example.api.HelloService
 *     /default/1.0/providers
 *         /127.0.0.1%3A8080
 *         /127.0.0.1%3A8081
 * </pre>
 *
 * <p>这里的“发现”并不神秘：Consumer 先根据服务名找到对应目录，再读取该
 * 目录下所有子节点的名字。每一个子节点名字就是一个 Provider 地址。</p>
 *
 * <p>查询结果会在 Consumer 本地短暂缓存。查询 ZooKeeper 时同时注册一次性
 * Watch；Provider 上下线后，ZooKeeper 会通知 Consumer 删除对应缓存。下一次
 * 调用重新查询最新地址，并注册下一次 Watch。3 秒有效期作为监听之外的兜底。</p>
 */
public final class ZooKeeperServiceDiscovery
        implements ServiceDiscovery, AutoCloseable {

    /**
     * 地址缓存默认有效 3 秒。
     *
     * <p>正常情况下 Provider 变化会通过 Watch 立即让缓存失效；较短有效期是
     * 额外兜底，避免极端网络或会话问题导致旧地址长期留在内存中。</p>
     */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(3);

    /** 负责与 ZooKeeper 服务端通信的 Curator 客户端。 */
    private final CuratorFramework client;

    /** 每个 ServiceKey 自己的 Provider 地址快照。 */
    private final Map<ServiceKey, CachedAddresses> addressCache =
            new ConcurrentHashMap<>();

    /** 缓存有效期，使用纳秒便于和 System.nanoTime() 比较经过时间。 */
    private final long cacheTtlNanos;

    /** 标记当前对象是否已经关闭，防止关闭后继续查询。 */
    private volatile boolean closed;

    /** 使用本地默认地址 {@code 127.0.0.1:2181} 创建服务发现对象。 */
    public ZooKeeperServiceDiscovery() {
        this(
                ZooKeeperClientFactory.DEFAULT_CONNECT_STRING,
                DEFAULT_CACHE_TTL
        );
    }

    /**
     * 连接指定的 ZooKeeper 服务。
     *
     * @param connectString ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     */
    public ZooKeeperServiceDiscovery(String connectString) {
        this(connectString, DEFAULT_CACHE_TTL);
    }

    /**
     * 连接指定 ZooKeeper，并设置本地地址缓存有效期。
     *
     * @param connectString ZooKeeper 地址
     * @param cacheTtl      本地地址缓存有效期，最小为 1 毫秒
     */
    public ZooKeeperServiceDiscovery(
            String connectString,
            Duration cacheTtl
    ) {
        this.cacheTtlNanos = validateCacheTtl(cacheTtl);
        this.client = ZooKeeperClientFactory.createAndStart(connectString);
    }

    /**
     * 仅供同包测试注入模拟 Curator 客户端，避免单元测试依赖外部 ZooKeeper。
     */
    ZooKeeperServiceDiscovery(
            CuratorFramework client,
            Duration cacheTtl
    ) {
        if (client == null) {
            throw new RpcException("Curator 客户端不能为空");
        }
        this.cacheTtlNanos = validateCacheTtl(cacheTtl);
        this.client = client;
    }

    /**
     * 查询能够提供指定服务的全部 Provider 地址。
     *
     * <p>{@code getChildren()} 读取的是“直接子节点名称”，不会递归读取更深层
     * 的节点。由于我们把地址直接放在子节点名称中，所以不需要再读取节点内容。</p>
     *
     * @param serviceKey 接口名、分组和版本组成的完整服务身份
     * @return Provider 地址列表；服务尚未注册时返回空列表，不返回 {@code null}
     */
    @Override
    public List<ServiceAddress> discover(ServiceKey serviceKey) {
        validateAvailable();
        validateServiceKey(serviceKey);

        /*
         * compute 会按 ServiceKey 原子地检查和刷新缓存。同一版本同时发生多个请求
         * 时，只有负责刷新缓存的线程查询 ZooKeeper，其余线程复用刷新结果。
         * 不同服务名称仍然可以并行刷新，不会互相阻塞。
         */
        CachedAddresses cachedAddresses = addressCache.compute(
                serviceKey,
                (key, existingCache) -> {
                    long nowNanos = System.nanoTime();
                    if (existingCache != null
                            && existingCache.isValid(
                                    nowNanos,
                                    cacheTtlNanos
                            )) {
                        return existingCache;
                    }

                    List<ServiceAddress> refreshedAddresses =
                            queryZooKeeper(key);
                    return new CachedAddresses(
                            refreshedAddresses,
                            System.nanoTime()
                    );
                }
        );

        return cachedAddresses.addresses();
    }

    /**
     * 真正访问 ZooKeeper，读取 Provider 地址并注册下一次节点变化监听。
     *
     * <p>ZooKeeper Watch 是一次性的：收到一次事件后就失效。因此事件到达时只
     * 删除缓存；下一次 discover() 重新查询时，会顺便注册新的 Watch。</p>
     */
    private List<ServiceAddress> queryZooKeeper(ServiceKey serviceKey) {
        String servicePath = ZooKeeperServiceRegistry.buildServicePath(
                serviceKey
        );

        CuratorWatcher watcher = ignoredEvent ->
                addressCache.remove(serviceKey);

        try {
            List<String> childNames;

            while (true) {
                try {
                    /*
                     * 对已存在的服务目录监听“子节点变化”。新增或删除 Provider
                     * 地址节点时，watcher 会删除当前服务的本地缓存。
                     */
                    childNames = client.getChildren()
                            .usingWatcher(watcher)
                            .forPath(servicePath);
                    break;
                } catch (KeeperException.NoNodeException exception) {
                    /*
                     * 服务目录还不存在时，getChildren 无法注册子节点监听。
                     * 改为监听目录本身是否被创建：第一个 Provider 注册后，
                     * NodeCreated 事件同样会让空列表缓存立即失效。
                     */
                    Stat currentStat = client.checkExists()
                            .usingWatcher(watcher)
                            .forPath(servicePath);
                    if (currentStat == null) {
                        return List.of();
                    }

                    // 检查期间目录已经出现，重新读取子节点并注册子节点监听。
                }
            }

            /*
             * ZooKeeper 返回的是编码后的地址子节点。map 会先解码，再转换成
             * ServiceAddress 对象。
             * 排序不是 RPC 必需条件，只是让测试和日志中的结果顺序保持稳定。
             */
            return childNames.stream()
                    .map(ZooKeeperServicePath::decodeProviderNode)
                    .sorted(
                            Comparator.comparing(ServiceAddress::host)
                                    .thenComparingInt(ServiceAddress::port)
                    )
                    .toList();
        } catch (Exception exception) {
            throw new RpcException(
                    "从 ZooKeeper 发现 RPC 服务失败，服务路径：" + servicePath,
                    exception
            );
        }
    }

    /** 校验缓存有效期并转换成用于计算经过时间的纳秒数。 */
    private long validateCacheTtl(Duration cacheTtl) {
        if (cacheTtl == null) {
            throw new RpcException("服务地址缓存有效期不能为空");
        }

        final long ttlNanos;
        try {
            ttlNanos = cacheTtl.toNanos();
        } catch (ArithmeticException exception) {
            throw new RpcException("服务地址缓存有效期过大", exception);
        }

        if (ttlNanos < 1_000_000) {
            throw new RpcException("服务地址缓存有效期不能小于 1 毫秒");
        }
        return ttlNanos;
    }

    /** ServiceKey 自身已校验三个组成部分，这里只需要拒绝空对象。 */
    private void validateServiceKey(ServiceKey serviceKey) {
        if (serviceKey == null) {
            throw new RpcException("服务发现使用的 ServiceKey 不能为空");
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
        addressCache.clear();
        client.close();
    }

    /**
     * 一次 ZooKeeper 查询得到的不可变地址快照，以及它的加载时间。
     */
    private record CachedAddresses(
            List<ServiceAddress> addresses,
            long loadedAtNanos
    ) {

        private CachedAddresses {
            // 防止调用者持有可修改列表并悄悄改变缓存内容。
            addresses = List.copyOf(addresses);
        }

        /** 使用经过时间而不是系统时钟，避免用户调整电脑时间影响缓存判断。 */
        private boolean isValid(long nowNanos, long ttlNanos) {
            return nowNanos - loadedAtNanos < ttlNanos;
        }
    }
}
