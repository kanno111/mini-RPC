package com.itheima.spring.autoconfigure;

import com.itheima.client.RpcClientFactory;
import com.itheima.loadbalance.LoadBalance;
import com.itheima.loadbalance.RandomLoadBalance;
import com.itheima.loadbalance.RoundRobinLoadBalance;
import com.itheima.serialize.JdkSerializer;
import com.itheima.serialize.JsonSerializer;
import com.itheima.serialize.Serializer;
import com.itheima.server.RpcProvider;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * mini-RPC 的 Spring Boot 自动配置入口。
 *
 * <p>业务项目只要引入 Starter，Spring Boot 就会根据
 * {@code AutoConfiguration.imports} 找到本类，不需要业务方手动写
 * {@code @Import(MiniRpcAutoConfiguration.class)}。</p>
 *
 * <p>本类目前完成五项装配：</p>
 *
 * <ol>
 *     <li>让 {@link MiniRpcProperties} 成为 Spring 管理的配置 Bean；</li>
 *     <li>注册一个延迟创建的 {@link RpcClientFactory} Bean；</li>
 *     <li>注册处理 {@code @RpcReference} 的 Bean 后置处理器；</li>
 *     <li>把 {@code @RpcService} 类注册成真正的 Spring Bean；</li>
 *     <li>让 {@link RpcProvider} 随 Spring 容器自动启动和关闭。</li>
 * </ol>
 *
 * <p>“延迟创建”表示仅仅启动 Spring 容器时不会连接 ZooKeeper。等后续真的有
 * Consumer 需要 RPC 代理时，Spring 才创建工厂并建立注册中心连接。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(MiniRpcProperties.class)
@ConditionalOnProperty(
        prefix = "mini-rpc",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MiniRpcAutoConfiguration {

    /**
     * 根据配置创建正文序列化器。
     *
     * <p>Consumer 编码请求、Provider 编码响应时都会复用这个 Bean。业务应用如果
     * 自己声明了 {@link Serializer} Bean，{@code ConditionalOnMissingBean} 会让
     * 默认实现退出，为以后接入自定义序列化器保留标准 Spring 扩展入口。</p>
     */
    @Bean
    @ConditionalOnMissingBean(Serializer.class)
    public Serializer rpcSerializer(MiniRpcProperties properties) {
        return switch (properties.getSerializer()) {
            case JSON -> new JsonSerializer();
            case JDK -> new JdkSerializer();
        };
    }

    /**
     * 根据配置创建 Consumer 使用的负载均衡器。
     *
     * <p>Provider 应用本身不需要选择地址，但创建这个轻量、无外部连接的 Bean
     * 不会启动客户端或访问 ZooKeeper，也能让 Provider/Consumer 共享同一套自动
     * 配置。业务应用声明自己的 {@link LoadBalance} Bean 时同样可以覆盖默认值。</p>
     */
    @Bean
    @ConditionalOnMissingBean(LoadBalance.class)
    public LoadBalance rpcLoadBalance(MiniRpcProperties properties) {
        return switch (properties.getLoadBalance()) {
            case ROUND_ROBIN -> new RoundRobinLoadBalance();
            case RANDOM -> new RandomLoadBalance();
        };
    }

    /**
     * 扫描 Spring Boot 启动类所在包，把 @RpcService 类登记成 Spring Bean。
     *
     * <p>返回类型属于 BeanFactoryPostProcessor，必须使用 static @Bean 尽早创建。</p>
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "mini-rpc",
            name = "provider-enabled",
            havingValue = "true"
    )
    public static RpcServiceBeanDefinitionRegistryPostProcessor
            rpcServiceBeanDefinitionRegistryPostProcessor(
            BeanFactory beanFactory
    ) {
        return new RpcServiceBeanDefinitionRegistryPostProcessor(
                AutoConfigurationPackages.get(beanFactory)
        );
    }

    /**
     * 注册 RPC 引用字段处理器。
     *
     * <p>BeanPostProcessor 必须在普通业务 Bean 创建前进入 Spring 容器，所以
     * 这里不能给它加 {@code @Lazy}。但是它拿到的是 ObjectProvider，并不会在
     * 自己创建时连带创建 RpcClientFactory。</p>
     *
     * <p>方法声明为 static，可以让 Spring 在尚未创建自动配置类实例时就注册
     * 这个基础设施处理器，减少“处理器创建得太晚”的风险。</p>
     *
     * @param clientFactoryProvider 需要注入代理时才取出客户端工厂
     * @return RPC 引用字段处理器
     */
    @Bean
    @ConditionalOnMissingBean(RpcReferenceBeanPostProcessor.class)
    public static RpcReferenceBeanPostProcessor
            rpcReferenceBeanPostProcessor(
            ObjectProvider<RpcClientFactory> clientFactoryProvider
    ) {
        return new RpcReferenceBeanPostProcessor(clientFactoryProvider);
    }

    /**
     * 把 RPC 客户端工厂交给 Spring 容器管理。
     *
     * <p>这里几个注解分别负责不同的事情：</p>
     *
     * <ul>
     *     <li>{@code @Bean}：方法返回值会被放入 Spring 容器；</li>
     *     <li>{@code @Lazy}：第一次真正使用这个 Bean 时才调用本方法；</li>
     *     <li>{@code @ConditionalOnMissingBean}：业务方自己提供工厂时，自动配置
     *     主动让位，不创建第二份；</li>
     *     <li>{@code destroyMethod = "close"}：Spring 关闭时调用工厂的 close，
     *     进而关闭 Netty 线程和 ZooKeeper 连接。</li>
     * </ul>
     *
     * @param properties 已经绑定好 application.yml 的 mini-RPC 配置
     * @return 由 Spring 持有的 RPC 客户端工厂
     */
    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(RpcClientFactory.class)
    public RpcClientFactory rpcClientFactory(
            MiniRpcProperties properties,
            Serializer serializer,
            LoadBalance loadBalance
    ) {
        return new RpcClientFactory(
                properties.getRegistryAddress(),
                properties.getRequestTimeout(),
                properties.getHeartbeatInterval(),
                properties.getHeartbeatTimeout(),
                serializer,
                loadBalance
        );
    }

    /** 创建 Spring Provider 使用的高层 RPC 服务端。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(
            prefix = "mini-rpc",
            name = "provider-enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(RpcProvider.class)
    public RpcProvider rpcProvider(
            MiniRpcProperties properties,
            Serializer serializer
    ) {
        return new RpcProvider(
                properties.getServerHost(),
                properties.getServerPort(),
                properties.getRegistryAddress(),
                serializer,
                properties.getShutdownTimeout(),
                properties.getProviderClientIdleTimeout(),
                properties.getProviderBusinessThreads(),
                properties.getProviderBusinessQueueCapacity()
        );
    }

    /** 让 Spring 自动注册服务 Bean、启动 Provider，并在关闭时停止它。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "mini-rpc",
            name = "provider-enabled",
            havingValue = "true"
    )
    @ConditionalOnMissingBean(SpringRpcProviderLifecycle.class)
    public SpringRpcProviderLifecycle springRpcProviderLifecycle(
            RpcProvider rpcProvider,
            ListableBeanFactory beanFactory
    ) {
        return new SpringRpcProviderLifecycle(rpcProvider, beanFactory);
    }
}
