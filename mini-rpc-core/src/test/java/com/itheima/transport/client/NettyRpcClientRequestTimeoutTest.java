package com.itheima.transport.client;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.serialize.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Netty 客户端等待响应时真正使用本次请求覆盖的超时。 */
class NettyRpcClientRequestTimeoutTest {

    @Test
    void shouldUsePerRequestTimeoutWhileWaitingForResponse() throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();

        /*
         * 这个最小 TCP 服务端只接受连接，不返回 RPC 响应。客户端因此一定会走到
         * CompletableFuture 的超时分支，适合验证实际等待值，而不是只检查字段。
         */
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread serverThread = Thread.ofPlatform()
                    .name("rpc-timeout-test-server")
                    .start(() -> {
                        try (Socket ignored = serverSocket.accept()) {
                            Thread.sleep(5_000);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        } catch (Throwable throwable) {
                            serverFailure.set(throwable);
                        }
                    });

            try (NettyRpcClient client = new NettyRpcClient(
                    "127.0.0.1",
                    serverSocket.getLocalPort(),
                    new JsonSerializer(),
                    Duration.ofSeconds(5)
            )) {
                RpcRequest request = RpcRequest.builder()
                        .requestId(1L)
                        .interfaceName("com.itheima.TimeoutService")
                        .methodName("waitForResponse")
                        .parameterTypes(new Class<?>[0])
                        .parameters(new Object[0])
                        .version("1.0")
                        .build();

                long startedAt = System.nanoTime();
                RpcException exception = assertThrows(
                        RpcException.class,
                        () -> client.sendRequest(
                                request,
                                Duration.ofMillis(100)
                        )
                );
                long elapsedMillis = Duration.ofNanos(
                        System.nanoTime() - startedAt
                ).toMillis();

                assertTrue(exception.getMessage().contains("100 毫秒"));
                assertTrue(
                        elapsedMillis < 2_000,
                        "单次 100ms 超时不应继续等待全局默认的 5 秒"
                );
            } finally {
                serverThread.interrupt();
                serverThread.join(2_000);
                assertFalse(serverThread.isAlive());
            }

            Throwable failure = serverFailure.get();
            if (failure != null) {
                throw new AssertionError("超时测试服务端异常退出", failure);
            }
        }
    }
}
