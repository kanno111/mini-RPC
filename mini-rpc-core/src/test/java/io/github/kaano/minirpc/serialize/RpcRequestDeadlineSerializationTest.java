package io.github.kaano.minirpc.serialize;

import io.github.kaano.minirpc.common.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 deadline 和服务路由字段能跨越 JSON、JDK 两种序列化边界。 */
class RpcRequestDeadlineSerializationTest {

    @Test
    void shouldPreserveDeadlineWithJsonSerializer() {
        assertDeadlineRoundTrip(new JsonSerializer());
    }

    @Test
    void shouldPreserveDeadlineWithJdkSerializer() {
        assertDeadlineRoundTrip(new JdkSerializer());
    }

    private void assertDeadlineRoundTrip(Serializer serializer) {
        RpcRequest request = RpcRequest.builder()
                .requestId(401L)
                .interfaceName("com.example.DeadlineService")
                .methodName("invoke")
                .parameterTypes(new Class<?>[0])
                .parameters(new Object[0])
                .group("gray")
                .version("2.0")
                .deadlineEpochMillis(1_234_567_890L)
                .build();

        byte[] bytes = serializer.serialize(request);
        RpcRequest restored = serializer.deserialize(
                bytes,
                RpcRequest.class
        );

        assertEquals(
                request.getDeadlineEpochMillis(),
                restored.getDeadlineEpochMillis()
        );
        assertEquals(request.getGroup(), restored.getGroup());
        assertEquals(request.getVersion(), restored.getVersion());
    }
}
