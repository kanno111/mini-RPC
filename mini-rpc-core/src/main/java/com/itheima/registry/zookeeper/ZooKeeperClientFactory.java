package com.itheima.registry.zookeeper;

import com.itheima.common.RpcException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.concurrent.TimeUnit;

/**
 * 创建并启动 Curator ZooKeeper 客户端的工具类。
 *
 * <p>这个类暂时只负责连接生命周期，不负责注册或发现服务。后续的
 * ZooKeeperServiceRegistry 和 ZooKeeperServiceDiscovery 都会复用这里的连接方式，
 * 避免各自重复编写超时、重试和等待连接的代码。</p>
 *
 * <pre>
 * Java 代码
 *    ↓
 * ZooKeeperClientFactory
 *    ↓ 创建并启动
 * CuratorFramework
 *    ↓ TCP 连接
 * 127.0.0.1:2181 的 ZooKeeper 服务
 * </pre>
 */
public final class ZooKeeperClientFactory {

    /** 本地学习环境中的默认 ZooKeeper 地址。 */
    public static final String DEFAULT_CONNECT_STRING = "127.0.0.1:2181";

    /** ZooKeeper 会话超时时间：30 秒。 */
    private static final int SESSION_TIMEOUT_MILLIS = 30_000;

    /** 首次建立连接最多等待 5 秒。 */
    private static final int CONNECTION_TIMEOUT_MILLIS = 5_000;

    /** 第一次重试前等待 1 秒，后续失败会逐步增加等待时间。 */
    private static final int RETRY_BASE_SLEEP_MILLIS = 1_000;

    /** 单次操作失败后最多重试 3 次。 */
    private static final int MAX_RETRIES = 3;

    /** 工具类只提供静态方法，不允许外部创建没有意义的实例。 */
    private ZooKeeperClientFactory() {
    }

    /**
     * 使用本地默认地址创建并连接 Curator 客户端。
     *
     * @return 已经连接到 {@code 127.0.0.1:2181} 的客户端
     */
    public static CuratorFramework createAndStart() {
        return createAndStart(DEFAULT_CONNECT_STRING);
    }

    /**
     * 创建客户端、启动后台线程，并限时等待 ZooKeeper 连接建立。
     *
     * <p>{@link CuratorFrameworkFactory#builder()} 只是组装客户端配置；
     * {@code build()} 创建对象；{@code start()} 才真正启动连接相关的后台线程。</p>
     *
     * <p>方法只会返回已经成功连接的客户端。如果 5 秒内没有连上，会先关闭客户端，
     * 再抛出 RpcException，避免调用方拿到一个表面存在、实际不可用的对象。</p>
     *
     * @param connectString ZooKeeper 地址；多个节点时可以用逗号分隔
     * @return 已经启动并成功连接的 Curator 客户端
     */
    public static CuratorFramework createAndStart(String connectString) {
        if (connectString == null || connectString.isBlank()) {
            throw new RpcException("ZooKeeper 连接地址不能为空");
        }

        String normalizedConnectString = connectString.trim();

        /*
         * ExponentialBackoffRetry 表示指数退避重试：
         * 第一次失败大约等 1 秒，后续等待时间逐步增加，最多重试 3 次。
         * 这样能应对短暂网络抖动，又不会无休止地快速重试。
         */
        ExponentialBackoffRetry retryPolicy = new ExponentialBackoffRetry(
                RETRY_BASE_SLEEP_MILLIS,
                MAX_RETRIES
        );

        CuratorFramework client = CuratorFrameworkFactory.builder()
                .connectString(normalizedConnectString)
                .sessionTimeoutMs(SESSION_TIMEOUT_MILLIS)
                .connectionTimeoutMs(CONNECTION_TIMEOUT_MILLIS)
                .retryPolicy(retryPolicy)
                .build();

        try {
            // start() 不会一直阻塞等待连接，它会启动 Curator 的后台连接线程。
            client.start();

            /*
             * 主动等待连接完成，避免后续注册代码刚取得 client 就立即操作，
             * 此时底层连接却还没有建立。
             */
            boolean connected = client.blockUntilConnected(
                    CONNECTION_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS
            );

            if (!connected) {
                client.close();
                throw new RpcException(
                        "连接 ZooKeeper 超时，地址：" + normalizedConnectString
                );
            }

            return client;
        } catch (InterruptedException exception) {
            client.close();

            // 恢复中断标记，让更上层代码仍然知道该线程曾被请求停止。
            Thread.currentThread().interrupt();
            throw new RpcException("等待 ZooKeeper 连接时线程被中断", exception);
        } catch (RpcException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            client.close();
            throw new RpcException(
                    "启动 ZooKeeper 客户端失败，地址：" + normalizedConnectString,
                    exception
            );
        }
    }
}
