package io.github.kaano.minirpc.proxy;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.transport.client.RpcClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证动态代理会为不同引用选择全局默认超时或单独覆盖值。 */
class RpcClientProxyTimeoutTest {

    @Test
    void shouldUseClientDefaultTimeoutWhenReferenceDoesNotOverrideIt() {
        RecordingRpcClient rpcClient = new RecordingRpcClient();
        GreetingService service = new RpcClientProxy(rpcClient)
                .createProxy(GreetingService.class);

        assertEquals("你好，默认配置", service.hello("默认配置"));
        assertEquals(1, rpcClient.defaultRequestCount);
        assertEquals(0, rpcClient.overrideRequestCount);
        assertNull(rpcClient.lastRequestTimeout);
        assertEquals(ServiceKey.DEFAULT_GROUP, rpcClient.lastRequest.getGroup());
        assertEquals(ServiceKey.DEFAULT_VERSION, rpcClient.lastRequest.getVersion());
    }

    @Test
    void shouldPassReferenceTimeoutToRpcClient() {
        RecordingRpcClient rpcClient = new RecordingRpcClient();
        Duration referenceTimeout = Duration.ofMillis(750);
        GreetingService service = new RpcClientProxy(
                rpcClient,
                "gray",
                "2.0",
                referenceTimeout
        ).createProxy(GreetingService.class);

        assertEquals("你好，独立配置", service.hello("独立配置"));
        assertEquals(0, rpcClient.defaultRequestCount);
        assertEquals(1, rpcClient.overrideRequestCount);
        assertEquals(referenceTimeout, rpcClient.lastRequestTimeout);
        assertEquals("gray", rpcClient.lastRequest.getGroup());
        assertEquals("2.0", rpcClient.lastRequest.getVersion());
    }

    /** 测试替身只记录代理选择了哪个发送入口，不执行任何网络操作。 */
    private static final class RecordingRpcClient implements RpcClient {

        private int defaultRequestCount;
        private int overrideRequestCount;
        private Duration lastRequestTimeout;
        private RpcRequest lastRequest;

        @Override
        public RpcResponse sendRequest(RpcRequest request) {
            defaultRequestCount++;
            lastRequest = request;
            return successResponse(request);
        }

        @Override
        public RpcResponse sendRequest(
                RpcRequest request,
                Duration requestTimeout
        ) {
            overrideRequestCount++;
            lastRequest = request;
            lastRequestTimeout = requestTimeout;
            return successResponse(request);
        }

        private RpcResponse successResponse(RpcRequest request) {
            String name = (String) request.getParameters()[0];
            return RpcResponse.success(request.getRequestId(), "你好，" + name);
        }
    }

    private interface GreetingService {
        String hello(String name);
    }
}
