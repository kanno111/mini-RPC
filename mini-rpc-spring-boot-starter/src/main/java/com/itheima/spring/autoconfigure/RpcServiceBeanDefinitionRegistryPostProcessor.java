package com.itheima.spring.autoconfigure;

import com.itheima.annotation.RpcService;
import com.itheima.common.RpcException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.List;

/**
 * 扫描业务包，把带有 {@link RpcService} 的类登记成 Spring Bean。
 *
 * <p>这里要区分两个经常混淆的阶段：</p>
 *
 * <ol>
 *     <li>登记 BeanDefinition：告诉 Spring “将来应该怎样创建这个对象”；</li>
 *     <li>创建 Bean：Spring 真正选择构造方法、准备依赖并调用构造方法。</li>
 * </ol>
 *
 * <p>本类只负责第一步，不会自己调用服务类构造方法。因此服务实现可以使用
 * Spring 构造器注入，不再受“必须有公开无参构造方法”的限制。</p>
 */
public final class RpcServiceBeanDefinitionRegistryPostProcessor
        implements BeanDefinitionRegistryPostProcessor {

    /** Spring Boot 启动类所在包，通常也是业务代码的扫描根包。 */
    private final List<String> basePackages;

    public RpcServiceBeanDefinitionRegistryPostProcessor(
            List<String> basePackages
    ) {
        if (basePackages == null || basePackages.isEmpty()) {
            throw new RpcException("Spring RPC 服务扫描包不能为空");
        }
        this.basePackages = List.copyOf(basePackages);
    }

    /**
     * 在 Spring 创建普通 Bean 之前，登记所有 {@code @RpcService} 服务类。
     */
    @Override
    public void postProcessBeanDefinitionRegistry(
            BeanDefinitionRegistry registry
    ) throws BeansException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);

        // false 表示不使用 Spring 默认的 @Component 规则，只认 @RpcService。
        scanner.addIncludeFilter(new AnnotationTypeFilter(RpcService.class));

        for (String basePackage : basePackages) {
            scanner.findCandidateComponents(basePackage).forEach(definition -> {
                String beanName = AnnotationBeanNameGenerator.INSTANCE
                        .generateBeanName(definition, registry);

                if (registry.containsBeanDefinition(beanName)) {
                    throw new RpcException(
                            "RPC 服务 Bean 名称重复：" + beanName
                    );
                }
                registry.registerBeanDefinition(beanName, definition);
            });
        }
    }

    /**
     * 接口要求提供的方法。本阶段没有需要在 BeanFactory 完成后继续修改的内容。
     */
    @Override
    public void postProcessBeanFactory(
            ConfigurableListableBeanFactory beanFactory
    ) throws BeansException {
        // 不需要额外处理。
    }
}
