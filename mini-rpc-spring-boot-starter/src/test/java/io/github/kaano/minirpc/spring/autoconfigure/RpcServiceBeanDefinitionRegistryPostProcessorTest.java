package io.github.kaano.minirpc.spring.autoconfigure;

import io.github.kaano.minirpc.spring.autoconfigure.fixture.SpringManagedRpcService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Starter 对 {@code @RpcService} 的扫描只负责注册 BeanDefinition，
 * 服务对象本身仍然由 Spring 创建并完成依赖注入。
 */
class RpcServiceBeanDefinitionRegistryPostProcessorTest {

    @Test
    void shouldRegisterRpcServiceAndLetSpringInjectConstructorDependency() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            /*
             * 先放入一个普通依赖。稍后 Spring 创建测试服务时，会自动把这个
             * String Bean 传给它唯一的有参构造方法。
             */
            context.registerBean(String.class, () -> "由 Spring 注入的值");

            RpcServiceBeanDefinitionRegistryPostProcessor processor =
                    new RpcServiceBeanDefinitionRegistryPostProcessor(
                            List.of(
                                    "io.github.kaano.minirpc.spring.autoconfigure.fixture"
                            )
                    );
            processor.postProcessBeanDefinitionRegistry(context);

            // refresh() 才是 Spring 根据 BeanDefinition 真正创建对象的时刻。
            context.refresh();

            SpringManagedRpcService service =
                    context.getBean(SpringManagedRpcService.class);
            assertThat(service.getInjectedValue())
                    .isEqualTo("由 Spring 注入的值");
        }
    }
}
