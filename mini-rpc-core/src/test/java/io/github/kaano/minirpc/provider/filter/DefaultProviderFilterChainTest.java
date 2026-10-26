package io.github.kaano.minirpc.provider.filter;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.provider.DefaultServiceProvider;
import io.github.kaano.minirpc.provider.RpcRequestHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Provider Filter 的顺序执行、后置回退和短路语义。 */
class DefaultProviderFilterChainTest {

    @Test
    void shouldExecuteFiltersAroundRealServiceInvocationInOrder() {
        List<String> events = new ArrayList<>();
        RpcRequestHandler terminalHandler = createHandler(events);

        ProviderFilter firstFilter = (request, chain) -> {
            events.add("first-before");
            RpcResponse response = chain.proceed(request);
            events.add("first-after");
            return response;
        };
        ProviderFilter secondFilter = (request, chain) -> {
            events.add("second-before");
            RpcResponse response = chain.proceed(request);
            events.add("second-after");
            return response;
        };

        ProviderFilterChain chain = new DefaultProviderFilterChain(
                List.of(firstFilter, secondFilter),
                terminalHandler
        );
        RpcResponse response = chain.proceed(createRequest());

        assertEquals(RpcResponse.SUCCESS_CODE, response.getCode());
        assertEquals("hello:Tom", response.getData());
        assertEquals(
                List.of(
                        "first-before",
                        "second-before",
                        "service-invoke",
                        "second-after",
                        "first-after"
                ),
                events
        );
    }

    @Test
    void shouldSkipRemainingChainWhenFilterReturnsDirectly() {
        List<String> events = new ArrayList<>();
        RpcRequestHandler terminalHandler = createHandler(events);
        ProviderFilter shortCircuitFilter = (request, chain) -> {
            events.add("short-circuit");
            return RpcResponse.failure(
                    request.getRequestId(),
                    new IllegalStateException("请求被测试过滤器拒绝")
            );
        };
        ProviderFilter neverReachedFilter = (request, chain) -> {
            events.add("never-reached");
            return chain.proceed(request);
        };

        ProviderFilterChain chain = new DefaultProviderFilterChain(
                List.of(shortCircuitFilter, neverReachedFilter),
                terminalHandler
        );
        RpcResponse response = chain.proceed(createRequest());

        assertEquals(RpcResponse.FAILURE_CODE, response.getCode());
        assertEquals(List.of("short-circuit"), events);
    }

    /** 创建真实 RpcRequestHandler，让测试覆盖最终的反射服务调用，而不使用假终点。 */
    private RpcRequestHandler createHandler(List<String> events) {
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        GreetingService service = name -> {
            events.add("service-invoke");
            return "hello:" + name;
        };
        serviceProvider.registerService(GreetingService.class, service);
        return new RpcRequestHandler(serviceProvider);
    }

    private RpcRequest createRequest() {
        return RpcRequest.builder()
                .requestId(701L)
                .interfaceName(GreetingService.class.getName())
                .methodName("hello")
                .parameterTypes(new Class<?>[]{String.class})
                .parameters(new Object[]{"Tom"})
                .build();
    }

    /** 最小测试服务；真正调用时会向 events 写入 service-invoke。 */
    public interface GreetingService {
        String hello(String name);
    }
}
