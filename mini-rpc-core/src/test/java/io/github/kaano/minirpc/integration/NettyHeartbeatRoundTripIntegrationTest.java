package io.github.kaano.minirpc.integration;

import io.github.kaano.minirpc.provider.DefaultServiceProvider;
import io.github.kaano.minirpc.provider.RpcRequestHandler;
import io.github.kaano.minirpc.proxy.RpcClientProxy;
import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import io.github.kaano.minirpc.transport.client.NettyRpcClient;
import io.github.kaano.minirpc.transport.server.NettyRpcServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用真实 TCP Channel 验证 Consumer 与 Provider 的心跳往返。
 *
 * <p>编解码器和心跳 Handler 的单元测试只能证明各零件单独工作；
 * 本测试在本机随机端口启动真实 {@link NettyRpcServer}，让
 * {@link NettyRpcClient} 通过操作系统 TCP 协议栈完成“写空闲 → PING →
 * PONG”。这能发现 Pipeline 顺序、编解码或空闲时间配合上的问题。</p>
 *
 * <p>这一小步只验证正常心跳会让已有 Channel 保持存活。“不回 PONG
 * 时超时断连并在下次调用重连”会在下一个独立测试中完成，避免
 * 一个用例同时承担过多状态转换。</p>
 */
class NettyHeartbeatRoundTripIntegrationTest {

    /**
     * 测试中使用较短时间，避免等待生产默认的 15/45 秒。
     * 读超时是发送间隔的六倍，为忙碌 CI 留出调度余量。
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMillis(100);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofMillis(600);

    /**
     * 观察时间超过两倍读超时。如果 PING/PONG 没有真正往返，
     * Consumer Channel 必然会在观察结束前被 READER_IDLE 关闭。
     */
    private static final Duration OBSERVATION_WINDOW = Duration.ofMillis(1_300);
    private static final Duration START_STOP_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldKeepRealChannelAliveWithPingPongAndContinueRpcCalls()
            throws Exception {
        int port = findAvailablePort();
        JsonSerializer serializer = new JsonSerializer();

        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        CountingGreetingService greetingService = new CountingGreetingService();
        serviceProvider.registerService(GreetingService.class, greetingService);

        /*
         * Provider 读空闲设为 2 秒，明显大于 Consumer 100ms 的 PING 间隔。
         * 正常 PING 既会让 Provider 保留 Channel，返回的 PONG 也会刷新
         * Consumer 的读空闲计时。
         */
        NettyRpcServer server = new NettyRpcServer(
                port,
                serializer,
                new RpcRequestHandler(serviceProvider),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                2,
                16
        );

        CountDownLatch serverStarted = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Thread serverThread = Thread.ofPlatform()
                .name("rpc-heartbeat-integration-provider")
                .start(() -> {
                    try {
                        server.start(serverStarted::countDown);
                    } catch (Throwable throwable) {
                        serverFailure.set(throwable);
                    }
                });

        try {
            assertTrue(
                    serverStarted.await(
                            START_STOP_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS
                    ),
                    "Netty Provider 没有在期限内绑定端口"
            );
            rethrowServerFailure(serverFailure.get());

            ServiceAddress providerAddress =
                    new ServiceAddress("127.0.0.1", port);

            /*
             * 地址解析器直接返回本次测试服务端，不引入 ZooKeeper。
             * 这样测试失败时，原因能聚焦在 Netty 连接和心跳链路。
             */
            try (NettyRpcClient client = new NettyRpcClient(
                    ignoredServiceName -> providerAddress,
                    serializer,
                    Duration.ofSeconds(2),
                    HEARTBEAT_INTERVAL,
                    HEARTBEAT_TIMEOUT
            )) {
                GreetingService proxy = new RpcClientProxy(
                        client,
                        "1.0",
                        Duration.ofSeconds(2)
                ).createProxy(GreetingService.class);

                assertEquals("hello-first-1", proxy.hello("first"));
                assertTrue(client.isConnected(), "首次 RPC 后应已建立 Channel");

                assertChannelRemainsActive(client);

                /*
                 * 观察窗口内没有业务请求，连接仍存活只能依靠心跳往返。
                 * 窗口结束后再次调用，还能同时证明心跳消息没有误入普通
                 * RpcResponse 匹配表，也没有破坏后续业务帧。
                 */
                assertEquals("hello-second-2", proxy.hello("second"));
                assertEquals(2, greetingService.invocationCount());
            }
        } finally {
            server.stop();
            serverThread.join(START_STOP_TIMEOUT.toMillis());
            assertTrue(!serverThread.isAlive(), "Netty Provider 线程没有在期限内退出");
            rethrowServerFailure(serverFailure.get());
        }
    }

    /**
     * 在整个观察窗口中反复检查连接，而不是只在结束时偶然取样。
     *
     * <p>25ms 是测试轮询粒度，不是用来猜测“心跳何时完成”的
     * 固定等待。测试要求 Channel 始终存活；任意一次检查发现断连都会
     * 立即失败。</p>
     */
    private static void assertChannelRemainsActive(NettyRpcClient client)
            throws InterruptedException {
        long deadline = System.nanoTime() + OBSERVATION_WINDOW.toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(
                    client.isConnected(),
                    "PING/PONG 观察期间 Channel 被意外关闭"
            );
            TimeUnit.MILLISECONDS.sleep(25);
        }
    }

    /** 由操作系统分配空闲端口，避免与本机示例服务冲突。 */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 把 Provider 后台线程的异常传回 JUnit 主线程。 */
    private static void rethrowServerFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Netty Provider 后台线程异常退出", failure);
    }

    /** 集成测试使用的最小服务契约。 */
    public interface GreetingService {
        String hello(String name);
    }

    /**
     * 计数服务能证明观察心跳前后的两次业务请求都确实进入 Provider。
     */
    public static final class CountingGreetingService
            implements GreetingService {

        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public String hello(String name) {
            return "hello-" + name + "-" + invocationCount.incrementAndGet();
        }

        int invocationCount() {
            return invocationCount.get();
        }
    }
}
