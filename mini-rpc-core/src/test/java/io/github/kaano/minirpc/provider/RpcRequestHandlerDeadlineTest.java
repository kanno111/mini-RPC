package io.github.kaano.minirpc.provider;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.common.ServiceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Provider 不会执行在到达业务处理阶段前已经过期的请求。 */
class RpcRequestHandlerDeadlineTest {

    @Test
    void shouldRejectExpiredRequestBeforeLookingUpService() {
        ServiceProvider serviceProvider = mock(ServiceProvider.class);
        RpcRequestHandler requestHandler =
                new RpcRequestHandler(serviceProvider);
        RpcRequest expiredRequest = RpcRequest.builder()
                .requestId(301L)
                .interfaceName("com.example.PaymentService")
                .methodName("pay")
                .parameterTypes(new Class<?>[0])
                .parameters(new Object[0])
                .version("1.0")
                .deadlineEpochMillis(System.currentTimeMillis() - 1)
                .build();

        RpcResponse response = requestHandler.handle(expiredRequest);

        assertEquals(RpcResponse.FAILURE_CODE, response.getCode());
        assertEquals(301L, response.getRequestId());
        assertTrue(response.getExceptionMessage().contains("超过 deadline"));
        verifyNoInteractions(serviceProvider);
    }

    @Test
    void shouldExecuteRequestWhoseDeadlineHasNotExpired() {
        ServiceProvider serviceProvider = mock(ServiceProvider.class);
        GreetingService service = () -> "hello";
        ServiceKey serviceKey = ServiceKey.defaultService(
                GreetingService.class
        );
        when(serviceProvider.getService(serviceKey))
                .thenReturn(service);
        RpcRequestHandler requestHandler =
                new RpcRequestHandler(serviceProvider);
        RpcRequest validRequest = RpcRequest.builder()
                .requestId(302L)
                .interfaceName(GreetingService.class.getName())
                .methodName("hello")
                .parameterTypes(new Class<?>[0])
                .parameters(new Object[0])
                .version("1.0")
                .deadlineEpochMillis(System.currentTimeMillis() + 10_000)
                .build();

        RpcResponse response = requestHandler.handle(validRequest);

        assertEquals(RpcResponse.SUCCESS_CODE, response.getCode());
        assertEquals("hello", response.getData());
        verify(serviceProvider).getService(serviceKey);
    }

    @Test
    void shouldExposeDeadlineOnlyWhileServiceMethodIsRunning() {
        ServiceProvider serviceProvider = mock(ServiceProvider.class);
        long deadlineEpochMillis = System.currentTimeMillis() + 10_000;
        GreetingService service = () -> RpcCallContext.getRequestId()
                + ":" + RpcCallContext.getDeadlineEpochMillis();
        ServiceKey serviceKey = ServiceKey.defaultService(
                GreetingService.class
        );
        when(serviceProvider.getService(serviceKey))
                .thenReturn(service);
        RpcRequestHandler requestHandler =
                new RpcRequestHandler(serviceProvider);
        RpcRequest request = RpcRequest.builder()
                .requestId(303L)
                .interfaceName(GreetingService.class.getName())
                .methodName("hello")
                .parameterTypes(new Class<?>[0])
                .parameters(new Object[0])
                .version("1.0")
                .deadlineEpochMillis(deadlineEpochMillis)
                .build();

        RpcResponse response = requestHandler.handle(request);

        assertEquals(RpcResponse.SUCCESS_CODE, response.getCode());
        assertEquals("303:" + deadlineEpochMillis, response.getData());
        assertFalse(RpcCallContext.hasContext(),
                "服务方法结束后必须清理 ThreadLocal 上下文");
    }

    /** 测试接口同时充当 Class.forName() 和反射方法查找使用的服务契约。 */
    private interface GreetingService {
        String hello();
    }
}
