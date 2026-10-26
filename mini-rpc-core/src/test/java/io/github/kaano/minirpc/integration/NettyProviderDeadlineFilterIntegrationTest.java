package io.github.kaano.minirpc.integration;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.provider.DefaultServiceProvider;
import io.github.kaano.minirpc.provider.RpcRequestHandler;
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

/** 使用真实 TCP 连接验证 Provider 的默认 deadline Filter 已进入业务执行路径。 */
class NettyProviderDeadlineFilterIntegrationTest {

    @Test
    void shouldRejectExpiredRequestAndContinueServingValidRequests()
            throws Exception {
        int port = findAvailablePort();
        AtomicInteger invocationCount = new AtomicInteger();
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        GreetingService service = name -> {
            invocationCount.incrementAndGet();
            return "hello:" + name;
        };
        serviceProvider.registerService(GreetingService.class, service);

        JsonSerializer serializer = new JsonSerializer();
        NettyRpcServer server = new NettyRpcServer(
                port,
                serializer,
                new RpcRequestHandler(serviceProvider)
        );
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        Thread serverThread = Thread.ofPlatform()
                .name("rpc-deadline-filter-integration-provider")
                .start(() -> {
                    try {
                        server.start(started::countDown);
                    } catch (Throwable throwable) {
                        serverFailure.set(throwable);
                    }
                });

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS),
                    "Netty Provider 没有及时绑定端口");
            rethrowServerFailure(serverFailure.get());

            try (NettyRpcClient client = new NettyRpcClient(
                    "127.0.0.1",
                    port,
                    serializer,
                    Duration.ofSeconds(5)
            )) {
                /*
                 * NettyRpcClient 会保留请求中更早的 deadline。故意发送已过期
                 * 请求，可以验证服务器返回的是 Provider Filter 的失败响应，
                 * 同时业务实现没有被调用。
                 */
                RpcRequest expired = request(801L);
                expired.setDeadlineEpochMillis(System.currentTimeMillis() - 1);
                RpcResponse rejected = client.sendRequest(expired);
                assertEquals(RpcResponse.FAILURE_CODE, rejected.getCode());
                assertEquals(801L, rejected.getRequestId());
                assertTrue(rejected.getExceptionMessage().contains("超过 deadline"));
                assertEquals(0, invocationCount.get());

                // 同一条连接随后仍能处理正常请求，证明短路没有破坏响应匹配。
                RpcResponse accepted = client.sendRequest(request(802L));
                assertEquals(RpcResponse.SUCCESS_CODE, accepted.getCode());
                assertEquals("hello:Tom", accepted.getData());
                assertEquals(1, invocationCount.get());
            }
        } finally {
            server.stop();
            serverThread.join(TimeUnit.SECONDS.toMillis(10));
            assertTrue(!serverThread.isAlive(), "Netty Provider 线程没有及时退出");
            rethrowServerFailure(serverFailure.get());
        }
    }

    private static RpcRequest request(long requestId) {
        return RpcRequest.builder()
                .requestId(requestId)
                .interfaceName(GreetingService.class.getName())
                .methodName("hello")
                .parameterTypes(new Class<?>[]{String.class})
                .parameters(new Object[]{"Tom"})
                .build();
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void rethrowServerFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        throw new AssertionError("Netty Provider 后台线程异常退出", failure);
    }

    /** 服务接口必须公开，便于另一个包中的 RpcRequestHandler 反射调用。 */
    public interface GreetingService {
        String hello(String name);
    }
}
