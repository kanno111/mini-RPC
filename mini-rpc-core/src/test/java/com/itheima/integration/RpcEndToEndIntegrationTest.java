package com.itheima.integration;

import com.itheima.cluster.DefaultServiceAddressResolver;
import com.itheima.loadbalance.RoundRobinLoadBalance;
import com.itheima.proxy.RpcClientProxy;
import com.itheima.registry.ServiceAddress;
import com.itheima.registry.zookeeper.ZooKeeperClientFactory;
import com.itheima.registry.zookeeper.ZooKeeperServiceDiscovery;
import com.itheima.registry.zookeeper.ZooKeeperServiceRegistry;
import com.itheima.serialize.JsonSerializer;
import com.itheima.server.RpcProvider;
import com.itheima.transport.client.NettyRpcClient;
import org.apache.curator.framework.CuratorFramework;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 使用真实 ZooKeeper 容器和真实 Netty 连接验证完整 RPC 调用链。
 *
 * <p>这里没有模拟注册中心、服务端或客户端。唯一由测试提供的是一个很小的
 * {@link GreetingService} 业务接口及实现，目的是让失败能够准确指向框架链路，
 * 而不是依赖 example 模块中的演示代码。</p>
 *
 * <p>{@code disabledWithoutDocker = true} 表示开发机没有启动 Docker 时明确跳过
 * 本测试。这样普通单元测试仍可运行；在本机或 CI 提供 Docker 后，本用例会自动
 * 加入 {@code mvn test}，不需要额外测试命令。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
public class RpcEndToEndIntegrationTest {

    private static final int ZOOKEEPER_CLIENT_PORT = 2181;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 固定镜像小版本，避免同一份代码在不同时间拉取到行为不同的 latest 镜像。
     * Testcontainers 会把容器的 2181 随机映射到宿主机空闲端口，避免占用本机
     * 已有 ZooKeeper 的端口。
     */
    @Container
    private static final GenericContainer<?> ZOOKEEPER =
            new GenericContainer<>(
                    DockerImageName.parse("zookeeper:3.9.6-jre-17")
            )
                    .withExposedPorts(ZOOKEEPER_CLIENT_PORT)
                    .waitingFor(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(1));

    @Test
    void shouldCompleteRpcCallAndInvalidateCacheAfterProviderStops()
            throws Exception {
        String zooKeeperAddress = ZOOKEEPER.getHost()
                + ":"
                + ZOOKEEPER.getMappedPort(ZOOKEEPER_CLIENT_PORT);
        int providerPort = findAvailablePort();
        String serviceName = GreetingService.class.getName();
        ServiceAddress providerAddress =
                new ServiceAddress("127.0.0.1", providerPort);
        String providerPath = ZooKeeperServiceRegistry.ROOT_PATH
                + "/"
                + serviceName
                + "/"
                + providerAddress.asText();

        RpcProvider provider = new RpcProvider(
                providerAddress.host(),
                providerAddress.port(),
                zooKeeperAddress
        );
        provider.registerService(
                GreetingService.class,
                new GreetingServiceImpl()
        );

        AtomicReference<Throwable> providerFailure = new AtomicReference<>();
        Thread providerThread = Thread.ofPlatform()
                .name("rpc-integration-provider")
                .start(() -> {
                    try {
                        provider.start();
                    } catch (Throwable throwable) {
                        providerFailure.set(throwable);
                    }
                });

        /*
         * inspectionClient 只负责观察 ZooKeeper 中的临时节点。RPC Consumer 使用
         * 另一条独立 Curator 会话，确保测试不会绕过真实服务发现逻辑。
         */
        try (CuratorFramework inspectionClient =
                     ZooKeeperClientFactory.createAndStart(zooKeeperAddress)) {
            await(
                    "Provider 启动后没有发布 ZooKeeper 临时节点",
                    () -> inspectionClient.checkExists()
                            .forPath(providerPath) != null
            );

            /*
             * 把 TTL 设置成 30 秒，远大于本测试等待 Watch 的 10 秒。如果关闭
             * Provider 后很快读到空列表，原因只能是 Watch 删除了旧缓存，而不是
             * 缓存自然过期。
             */
            try (ZooKeeperServiceDiscovery discovery =
                         new ZooKeeperServiceDiscovery(
                                 zooKeeperAddress,
                                 Duration.ofSeconds(30)
                         );
                 NettyRpcClient rpcClient = new NettyRpcClient(
                         new DefaultServiceAddressResolver(
                                 discovery,
                                 new RoundRobinLoadBalance()
                         ),
                         new JsonSerializer()
                 )) {
                assertEquals(
                        List.of(providerAddress),
                        discovery.discover(serviceName)
                );

                GreetingService greetingService =
                        new RpcClientProxy(
                                rpcClient,
                                "1.0",
                                Duration.ofSeconds(2)
                        )
                                .createProxy(GreetingService.class);

                /*
                 * 这条断言还使用了单引用 2 秒超时，经过动态代理、服务发现、
                 * Netty、协议编解码和反射调用，覆盖超时值进入真实客户端的路径。
                 */
                assertEquals("你好，mini-RPC", greetingService.hello("mini-RPC"));

                provider.close();
                providerThread.join(WAIT_TIMEOUT.toMillis());
                assertTrue(
                        !providerThread.isAlive(),
                        "关闭 Provider 后，Netty 服务线程仍未退出"
                );
                rethrowProviderFailure(providerFailure.get());

                await(
                        "Provider 关闭后 ZooKeeper 临时节点仍然存在",
                        () -> inspectionClient.checkExists()
                                .forPath(providerPath) == null
                );
                await(
                        "Provider 下线事件没有让 Consumer 地址缓存失效",
                        () -> discovery.discover(serviceName).isEmpty()
                );
                assertNull(inspectionClient.checkExists().forPath(providerPath));
            }
        } finally {
            provider.close();
            providerThread.join(WAIT_TIMEOUT.toMillis());
        }
    }

    /**
     * 先让操作系统分配一个空闲端口，再把该端口交给 Netty。
     *
     * <p>测试不固定使用 8080，避免和开发者正在运行的示例 Provider 冲突。</p>
     */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 在有限时间内等待异步的 Netty/ZooKeeper 状态变化，避免使用固定长等待。 */
    private static void await(
            String failureMessage,
            CheckedCondition condition
    ) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        Throwable lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                if (condition.evaluate()) {
                    return;
                }
            } catch (Throwable throwable) {
                lastFailure = throwable;
            }
            Thread.sleep(100);
        }

        if (lastFailure == null) {
            fail(failureMessage);
        }
        fail(failureMessage, lastFailure);
    }

    /** Provider 后台线程若异常退出，要让测试主线程看到原始失败。 */
    private static void rethrowProviderFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Provider 后台线程异常退出", failure);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    /** 端到端测试专用的最小服务契约。 */
    public interface GreetingService {
        String hello(String name);
    }

    /** 使用公开实现类，保证服务端跨包反射调用时具有合法访问权限。 */
    public static final class GreetingServiceImpl implements GreetingService {

        @Override
        public String hello(String name) {
            return "你好，" + name;
        }
    }
}
