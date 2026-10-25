package io.github.kaano.minirpc.transport.server;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.provider.RpcRequestHandler;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 验证 Provider 客户端读空闲配置进入 Netty 服务端并尽早校验。 */
class NettyRpcServerIdleConfigurationTest {

    @Test
    void shouldUseConfiguredClientIdleTimeout() {
        NettyRpcServer server = createServer(Duration.ofSeconds(70));

        try {
            assertEquals(
                    Duration.ofSeconds(70),
                    server.getClientIdleTimeout()
            );
        } finally {
            server.stop();
        }
    }

    @Test
    void shouldRejectClientIdleTimeoutShorterThanOneMillisecond() {
        RpcException exception = assertThrows(
                RpcException.class,
                () -> createServer(Duration.ZERO)
        );

        assertEquals(
                "Provider 客户端读空闲超时不能小于 1 毫秒",
                exception.getMessage()
        );
    }

    private NettyRpcServer createServer(Duration clientIdleTimeout) {
        return new NettyRpcServer(
                8080,
                new JsonSerializer(),
                mock(RpcRequestHandler.class),
                Duration.ofSeconds(10),
                clientIdleTimeout,
                2,
                16
        );
    }
}
