package io.github.kaano.minirpc.spring.autoconfigure;

import io.github.kaano.minirpc.annotation.RpcService;
import io.github.kaano.minirpc.server.RpcProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Spring Provider 启动时不会丢失 @RpcService 的分组和版本。 */
class SpringRpcProviderLifecycleRoutingTest {

    @Test
    void shouldRegisterSpringServiceWithAnnotatedGroupAndVersion() {
        RpcProvider rpcProvider = mock(RpcProvider.class);
        ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
        RoutedService serviceBean = new RoutedService();
        RpcService annotation = RoutedService.class.getAnnotation(
                RpcService.class
        );

        when(beanFactory.getBeanNamesForAnnotation(RpcService.class))
                .thenReturn(new String[]{"routedService"});
        when(beanFactory.findAnnotationOnBean(
                "routedService",
                RpcService.class
        )).thenReturn(annotation);
        when(beanFactory.getBean("routedService")).thenReturn(serviceBean);

        SpringRpcProviderLifecycle lifecycle =
                new SpringRpcProviderLifecycle(rpcProvider, beanFactory);
        lifecycle.start();
        lifecycle.stop();

        verify(rpcProvider).registerService(
                Runnable.class,
                "gray",
                "2.0",
                serviceBean
        );
        verify(rpcProvider).start();
        verify(rpcProvider).close();
    }

    @RpcService(
            serviceInterface = Runnable.class,
            group = "gray",
            version = "2.0"
    )
    private static final class RoutedService implements Runnable {

        @Override
        public void run() {
            // 测试只验证注解元数据传递，不执行具体业务。
        }
    }
}
