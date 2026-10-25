package io.github.kaano.minirpc.transport.client;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证心跳配置真正进入 Netty 客户端，并在创建线程前完成关系校验。 */
class NettyRpcClientHeartbeatConfigurationTest {

    @Test
    void shouldUseConfiguredHeartbeatDurations() {
        try (NettyRpcClient client = createClient(
                Duration.ofSeconds(7),
                Duration.ofSeconds(21)
        )) {
            assertEquals(
                    Duration.ofSeconds(7),
                    client.getHeartbeatInterval()
            );
            assertEquals(
                    Duration.ofSeconds(21),
                    client.getHeartbeatTimeout()
            );
        }
    }

    @Test
    void shouldRejectHeartbeatIntervalShorterThanOneMillisecond() {
        RpcException exception = assertThrows(
                RpcException.class,
                () -> createClient(Duration.ZERO, Duration.ofSeconds(45))
        );

        assertTrue(exception.getMessage().contains("心跳间隔不能小于 1 毫秒"));
    }

    @Test
    void shouldRejectTimeoutNotGreaterThanHeartbeatInterval() {
        RpcException exception = assertThrows(
                RpcException.class,
                () -> createClient(
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15)
                )
        );

        assertTrue(exception.getMessage().contains("必须大于心跳间隔"));
    }

    private NettyRpcClient createClient(
            Duration heartbeatInterval,
            Duration heartbeatTimeout
    ) {
        return new NettyRpcClient(
                ignoredServiceName ->
                        new ServiceAddress("127.0.0.1", 8080),
                new JsonSerializer(),
                Duration.ofSeconds(5),
                heartbeatInterval,
                heartbeatTimeout
        );
    }
}
