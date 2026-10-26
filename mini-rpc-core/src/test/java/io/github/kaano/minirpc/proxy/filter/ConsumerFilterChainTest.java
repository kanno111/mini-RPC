package io.github.kaano.minirpc.proxy.filter;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.proxy.RpcClientProxy;
import io.github.kaano.minirpc.transport.client.RpcClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Consumer 链确实处于动态代理与 RpcClient 之间。 */
class ConsumerFilterChainTest {

    @Test
    void shouldRunFiltersAroundTransportAndPreserveReferenceTimeout() {
        List<String> events = new ArrayList<>();
        Duration referenceTimeout = Duration.ofMillis(750);
        RpcClient client = new RpcClient() {
            @Override
            public RpcResponse sendRequest(RpcRequest request) {
                throw new AssertionError("指定单引用超时时不应使用默认发送入口");
            }

            @Override
            public RpcResponse sendRequest(RpcRequest request, Duration timeout) {
                events.add("transport");
                assertEquals(referenceTimeout, timeout);
                assertEquals("gray", request.getGroup());
                assertEquals("2.0", request.getVersion());
                return RpcResponse.success(request.getRequestId(), "hello:Tom");
            }
        };
        ConsumerFilter first = (request, chain) -> {
            events.add("first-before");
            try {
                return chain.proceed(request);
            } finally {
                events.add("first-after");
            }
        };
        ConsumerFilter second = (request, chain) -> {
            events.add("second-before");
            try {
                return chain.proceed(request);
            } finally {
                events.add("second-after");
            }
        };

        GreetingService service = new RpcClientProxy(
                client, "gray", "2.0", referenceTimeout,
                List.of(first, second)
        ).createProxy(GreetingService.class);

        assertEquals("hello:Tom", service.hello("Tom"));
        assertEquals(List.of(
                "first-before", "second-before", "transport",
                "second-after", "first-after"
        ), events);
    }

    @Test
    void shouldShortCircuitBeforeNetworkAndKeepFailureSemantics() {
        AtomicInteger transportCalls = new AtomicInteger();
        RpcClient client = request -> {
            transportCalls.incrementAndGet();
            return RpcResponse.success(request.getRequestId(), "unexpected");
        };
        ConsumerFilter reject = (request, chain) -> RpcResponse.failure(
                request.getRequestId(),
                new RpcException("测试过滤器拒绝请求")
        );
        GreetingService service = new RpcClientProxy(
                client, "default", "1.0", null, List.of(reject)
        ).createProxy(GreetingService.class);

        RpcException exception = assertThrows(
                RpcException.class,
                () -> service.hello("Tom")
        );

        assertEquals(0, transportCalls.get());
        assertTrue(exception.getMessage().contains("测试过滤器拒绝请求"));
    }

    @Test
    void shouldPropagateTransportExceptionAfterFilterCleanup() {
        RpcException networkFailure = new RpcException("网络连接失败");
        List<String> events = new ArrayList<>();
        RpcClient client = request -> {
            events.add("transport");
            throw networkFailure;
        };
        ConsumerFilter cleanup = (request, chain) -> {
            events.add("before");
            try {
                return chain.proceed(request);
            } finally {
                events.add("after");
            }
        };
        GreetingService service = new RpcClientProxy(
                client, "default", "1.0", null, List.of(cleanup)
        ).createProxy(GreetingService.class);

        RpcException actual = assertThrows(
                RpcException.class,
                () -> service.hello("Tom")
        );

        assertSame(networkFailure, actual);
        assertEquals(List.of("before", "transport", "after"), events);
    }

    private interface GreetingService {
        String hello(String name);
    }
}
