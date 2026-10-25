package io.github.kaano.minirpc.provider;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.common.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证接口名、分组和版本共同决定 Provider 最终执行的服务实现。 */
class ServiceVersionGroupRoutingTest {

    @Test
    void shouldRouteSameInterfaceToExactGroupAndVersion() {
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        register(
                serviceProvider,
                "default",
                "1.0",
                name -> "stable-v1:" + name
        );
        register(
                serviceProvider,
                "default",
                "2.0",
                name -> "stable-v2:" + name
        );
        register(
                serviceProvider,
                "gray",
                "2.0",
                name -> "gray-v2:" + name
        );
        RpcRequestHandler requestHandler =
                new RpcRequestHandler(serviceProvider);

        assertEquals(
                "stable-v1:Tom",
                invoke(requestHandler, "default", "1.0").getData()
        );
        assertEquals(
                "stable-v2:Tom",
                invoke(requestHandler, "default", "2.0").getData()
        );
        assertEquals(
                "gray-v2:Tom",
                invoke(requestHandler, "gray", "2.0").getData()
        );
    }

    @Test
    void shouldUseDefaultRouteForRequestWithoutGroupAndVersion() {
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        serviceProvider.registerService(
                GreetingService.class,
                (GreetingService) name -> "default:" + name
        );
        RpcRequest request = requestBuilder()
                // 模拟旧 Consumer：请求正文中没有 group 和 version。
                .group(null)
                .version(null)
                .build();

        RpcResponse response = new RpcRequestHandler(serviceProvider)
                .handle(request);

        assertEquals(RpcResponse.SUCCESS_CODE, response.getCode());
        assertEquals("default:Tom", response.getData());
    }

    @Test
    void shouldRejectDuplicateServiceWithSameKey() {
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        register(serviceProvider, "gray", "2.0", name -> "first");

        RpcException exception = assertThrows(
                RpcException.class,
                () -> register(
                        serviceProvider,
                        "gray",
                        "2.0",
                        name -> "second"
                )
        );

        assertTrue(exception.getMessage().contains("服务已经注册"));
    }

    @Test
    void shouldReturnFailureWhenRequestedRouteDoesNotExist() {
        DefaultServiceProvider serviceProvider = new DefaultServiceProvider();
        register(serviceProvider, "default", "1.0", name -> "v1");

        RpcResponse response = new RpcRequestHandler(serviceProvider)
                .handle(requestBuilder()
                        .group("default")
                        .version("2.0")
                        .build());

        assertEquals(RpcResponse.FAILURE_CODE, response.getCode());
        assertTrue(response.getExceptionMessage().contains("没有找到已注册的服务"));
        assertTrue(response.getExceptionMessage().contains("version=2.0"));
    }

    /** 使用完整 ServiceKey 注册测试服务。 */
    private void register(
            DefaultServiceProvider serviceProvider,
            String group,
            String version,
            GreetingService service
    ) {
        serviceProvider.registerService(
                new ServiceKey(
                        GreetingService.class.getName(),
                        group,
                        version
                ),
                GreetingService.class,
                service
        );
    }

    /** 构造除路由信息外完全相同的请求，突出测试中的路由差异。 */
    private RpcRequest.RpcRequestBuilder requestBuilder() {
        return RpcRequest.builder()
                .requestId(501L)
                .interfaceName(GreetingService.class.getName())
                .methodName("hello")
                .parameterTypes(new Class<?>[]{String.class})
                .parameters(new Object[]{"Tom"});
    }

    private RpcResponse invoke(
            RpcRequestHandler requestHandler,
            String group,
            String version
    ) {
        return requestHandler.handle(
                requestBuilder()
                        .group(group)
                        .version(version)
                        .build()
        );
    }

    /** 一个接口对应三份实现，便于证明路由键而不是 Java 类型决定最终实现。 */
    public interface GreetingService {
        String hello(String name);
    }
}
