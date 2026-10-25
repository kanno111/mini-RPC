package io.github.kaano.minirpc.integration;

import io.github.kaano.minirpc.common.RpcHeartbeat;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.proxy.RpcClientProxy;
import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import io.github.kaano.minirpc.transport.client.NettyRpcClient;
import io.github.kaano.minirpc.transport.codec.RpcDecoder;
import io.github.kaano.minirpc.transport.codec.RpcEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 使用真实 TCP Channel 验证“心跳超时 → 断开旧连接 → 下次调用重连”。
 *
 * <p>正常的 {@code NettyRpcServer} 必然会回复 PONG，无法自然进入故障分支。
 * 因此本测试在测试源集中实现一个最小的“可注入心跳故障”Provider：
 * 它复用生产 {@link RpcDecoder} 和 {@link RpcEncoder}，正常回复业务响应，
 * 但第一阶段故意丢弃 PING。这样既能经过真实网络与协议编解码，
 * 又不需要向生产服务端暴露“禁用 PONG”的危险开关。</p>
 */
class NettyHeartbeatTimeoutReconnectIntegrationTest {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMillis(100);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofMillis(600);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECOVERY_OBSERVATION = Duration.ofMillis(1_300);

    @Test
    void shouldCloseSilentChannelAndReconnectOnNextRpcCall() throws Exception {
        JsonSerializer serializer = new JsonSerializer();

        try (FaultInjectingHeartbeatServer server =
                     new FaultInjectingHeartbeatServer(serializer)) {
            int port = server.start();
            ServiceAddress address = new ServiceAddress("127.0.0.1", port);

            try (NettyRpcClient client = new NettyRpcClient(
                    ignoredServiceName -> address,
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

                /*
                 * 第一次业务调用建立 Channel。测试 Provider 会回复 RpcResponse，
                 * 但随后故意丢弃 PING，因此普通业务能力与心跳故障可以被区分。
                 */
                assertEquals("hello-first-1", proxy.hello("first"));
                assertEquals(1, server.acceptedConnectionCount());
                assertTrue(client.isConnected(), "首次 RPC 后应存在活跃 Channel");

                await(
                        "测试 Provider 没有收到 Consumer 的 PING",
                        () -> server.ignoredPingCount() > 0
                );
                await(
                        "Consumer 没有在读空闲超时后关闭静默 Channel",
                        () -> !client.isConnected()
                );
                await(
                        "Provider 没有观察到第一条客户端 Channel 断开",
                        () -> server.closedConnectionCount() >= 1
                );

                /*
                 * 模拟 Provider 心跳能力恢复。客户端不会在后台无限重连；只有下一次
                 * 真实 RPC 到来时，getOrCreateChannel 才会按需建立新连接。
                 */
                server.enableHeartbeatReplies();
                assertEquals("hello-second-2", proxy.hello("second"));
                assertEquals(2, server.acceptedConnectionCount());
                assertTrue(client.isConnected(), "第二次 RPC 应建立新的活跃 Channel");

                /*
                 * 恢复后的观察窗口超过两倍读超时。如果新连接收不到 PONG，它会再次
                 * 被关闭；始终存活说明恢复后的 PING/PONG 往返确实生效。
                 */
                assertChannelRemainsActive(client);
                assertTrue(server.pongReplyCount() > 0, "恢复后 Provider 应至少返回一次 PONG");

                assertEquals("hello-third-3", proxy.hello("third"));
                assertEquals(3, server.businessRequestCount());
                assertEquals(
                        2,
                        server.acceptedConnectionCount(),
                        "恢复后的第三次 RPC 应复用第二条 Channel，而不是再次连接"
                );
            }
        }
    }

    /** 在恢复观察窗口内持续确认第二条 Channel 没有再次被读空闲关闭。 */
    private static void assertChannelRemainsActive(NettyRpcClient client)
            throws InterruptedException {
        long deadline = System.nanoTime() + RECOVERY_OBSERVATION.toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(client.isConnected(), "恢复 PONG 后的新 Channel 被意外关闭");
            TimeUnit.MILLISECONDS.sleep(25);
        }
    }

    /**
     * 在有限时间内等待异步网络状态变化。
     *
     * <p>这里等待的是明确条件，而不是用固定 sleep 猜测关闭时机；条件一旦成立就
     * 立即继续，超出上限则给出与当前阶段对应的失败信息。</p>
     */
    private static void await(String failureMessage, CheckedCondition condition)
            throws Exception {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        Throwable lastFailure = null;

        while (System.nanoTime() < deadline) {
            try {
                if (condition.evaluate()) {
                    return;
                }
            } catch (Throwable throwable) {
                lastFailure = throwable;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }

        if (lastFailure == null) {
            fail(failureMessage);
        }
        fail(failureMessage, lastFailure);
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    /** 集成测试使用的最小服务契约。 */
    public interface GreetingService {
        String hello(String name);
    }

    /**
     * 只存在于测试代码中的故障注入服务端。
     *
     * <p>它不是第二套 RPC Server 实现，也不会进入 main 产物。它只承担三件事：
     * 使用真实协议编解码、按 requestId 返回业务响应，以及按测试阶段丢弃或回复
     * PING。</p>
     */
    private static final class FaultInjectingHeartbeatServer
            implements AutoCloseable {

        private final JsonSerializer serializer;
        private final AtomicBoolean replyHeartbeats = new AtomicBoolean();
        private final AtomicInteger acceptedConnections = new AtomicInteger();
        private final AtomicInteger closedConnections = new AtomicInteger();
        private final AtomicInteger ignoredPings = new AtomicInteger();
        private final AtomicInteger pongReplies = new AtomicInteger();
        private final AtomicInteger businessRequests = new AtomicInteger();

        private MultiThreadIoEventLoopGroup bossGroup;
        private MultiThreadIoEventLoopGroup workerGroup;
        private Channel serverChannel;

        private FaultInjectingHeartbeatServer(JsonSerializer serializer) {
            this.serializer = serializer;
        }

        /** 启动真实 Netty 监听端口，并返回操作系统实际分配的端口号。 */
        private int start() throws InterruptedException {
            bossGroup = new MultiThreadIoEventLoopGroup(
                    1,
                    NioIoHandler.newFactory()
            );
            workerGroup = new MultiThreadIoEventLoopGroup(
                    1,
                    NioIoHandler.newFactory()
            );

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new RpcDecoder())
                                    .addLast(new RpcEncoder(serializer))
                                    .addLast(new FaultInjectingHandler());
                        }
                    });

            /*
             * 绑定 0 表示让操作系统原子选择空闲端口，比“先探测再绑定”更适合
             * 测试夹具，也不会与开发者正在运行的 8080 示例冲突。
             */
            serverChannel = bootstrap.bind(0).sync().channel();
            InetSocketAddress localAddress =
                    (InetSocketAddress) serverChannel.localAddress();
            return localAddress.getPort();
        }

        private void enableHeartbeatReplies() {
            replyHeartbeats.set(true);
        }

        private int acceptedConnectionCount() {
            return acceptedConnections.get();
        }

        private int closedConnectionCount() {
            return closedConnections.get();
        }

        private int ignoredPingCount() {
            return ignoredPings.get();
        }

        private int pongReplyCount() {
            return pongReplies.get();
        }

        private int businessRequestCount() {
            return businessRequests.get();
        }

        @Override
        public void close() {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
        }

        /** 每条真实客户端连接各自创建一个 Handler，连接计数状态由外层夹具共享。 */
        private final class FaultInjectingHandler
                extends SimpleChannelInboundHandler<Object> {

            @Override
            public void channelActive(ChannelHandlerContext context) {
                acceptedConnections.incrementAndGet();
                context.fireChannelActive();
            }

            @Override
            public void channelInactive(ChannelHandlerContext context) {
                closedConnections.incrementAndGet();
                context.fireChannelInactive();
            }

            @Override
            protected void channelRead0(
                    ChannelHandlerContext context,
                    Object message
            ) {
                if (message instanceof RpcRequest request) {
                    int invocation = businessRequests.incrementAndGet();
                    String name = String.valueOf(request.getParameters()[0]);
                    context.writeAndFlush(RpcResponse.success(
                            request.getRequestId(),
                            "hello-" + name + "-" + invocation
                    ));
                    return;
                }

                if (message instanceof RpcHeartbeat heartbeat
                        && heartbeat.getHeartbeatType()
                        == RpcHeartbeat.TYPE_PING) {
                    if (!replyHeartbeats.get()) {
                        ignoredPings.incrementAndGet();
                        return;
                    }

                    pongReplies.incrementAndGet();
                    context.writeAndFlush(RpcHeartbeat.pong(
                            heartbeat.getRequestId(),
                            System.currentTimeMillis()
                    ));
                }
            }
        }
    }
}
