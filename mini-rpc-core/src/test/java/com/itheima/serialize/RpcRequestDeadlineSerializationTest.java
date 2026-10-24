package com.itheima.serialize;

import com.itheima.common.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 deadline 能随 RpcRequest 正文跨越 JSON 和 JDK 两种序列化边界。 */
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
                .version("1.0")
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
    }
}
