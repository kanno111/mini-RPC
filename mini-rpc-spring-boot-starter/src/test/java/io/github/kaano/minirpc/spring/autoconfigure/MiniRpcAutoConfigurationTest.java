package io.github.kaano.minirpc.spring.autoconfigure;

import io.github.kaano.minirpc.annotation.RpcReference;
import io.github.kaano.minirpc.client.RpcClientFactory;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.loadbalance.LoadBalance;
import io.github.kaano.minirpc.loadbalance.RandomLoadBalance;
import io.github.kaano.minirpc.loadbalance.RoundRobinLoadBalance;
import io.github.kaano.minirpc.metrics.ConsumerMetricsFilter;
import io.github.kaano.minirpc.metrics.ProviderMetricsFilter;
import io.github.kaano.minirpc.provider.filter.ProviderFilter;
import io.github.kaano.minirpc.provider.filter.ProviderFilterChain;
import io.github.kaano.minirpc.proxy.filter.ConsumerFilter;
import io.github.kaano.minirpc.proxy.filter.ConsumerFilterChain;
import io.github.kaano.minirpc.registry.ServiceAddress;
import io.github.kaano.minirpc.serialize.JdkSerializer;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import io.github.kaano.minirpc.serialize.Serializer;
import io.github.kaano.minirpc.transport.client.NettyRpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Starter 最基础的两件事：自动配置可以生效，配置文件中的值可以绑定。
 */
class MiniRpcAutoConfigurationTest {

    /**
     * ApplicationContextRunner 会创建一个很小的 Spring 容器，特别适合测试
     * 自动配置，不需要真的启动 Provider、Consumer、Netty 或 ZooKeeper。
     */
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestApplication.class);

    /**
     * 这个测试应用没有手动导入 MiniRpcAutoConfiguration。
     *
     * <p>{@code @EnableAutoConfiguration} 会让 Spring Boot 去读取所有依赖中的
     * AutoConfiguration.imports。如果配置文件路径或类名写错，下面的测试就
     * 找不到 MiniRpcProperties，从而证明测试覆盖了“自动发现”这一步。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Test
    void shouldBindMiniRpcProperties() {
        contextRunner
                .withPropertyValues(
                        "mini-rpc.registry-address=192.168.1.10:2181",
                        "mini-rpc.request-timeout=8s",
                        "mini-rpc.heartbeat-interval=7s",
                        "mini-rpc.heartbeat-timeout=21s",
                        "mini-rpc.shutdown-timeout=12s",
                        "mini-rpc.provider-client-idle-timeout=70s",
                        "mini-rpc.serializer=jdk",
                        "mini-rpc.load-balance=random",
                        "mini-rpc.server-port=9090",
                        "mini-rpc.provider-business-threads=6",
                        "mini-rpc.provider-business-queue-capacity=300"
                )
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(MiniRpcProperties.class);

                    MiniRpcProperties properties =
                            context.getBean(MiniRpcProperties.class);

                    assertThat(properties.getRegistryAddress())
                            .isEqualTo("192.168.1.10:2181");
                    assertThat(properties.getRequestTimeout())
                            .isEqualTo(Duration.ofSeconds(8));
                    assertThat(properties.getHeartbeatInterval())
                            .isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.getHeartbeatTimeout())
                            .isEqualTo(Duration.ofSeconds(21));
                    assertThat(properties.getShutdownTimeout())
                            .isEqualTo(Duration.ofSeconds(12));
                    assertThat(properties.getProviderClientIdleTimeout())
                            .isEqualTo(Duration.ofSeconds(70));
                    assertThat(properties.getSerializer())
                            .isEqualTo(
                                    MiniRpcProperties.SerializerType.JDK
                            );
                    assertThat(properties.getLoadBalance())
                            .isEqualTo(
                                    MiniRpcProperties.LoadBalanceType.RANDOM
                            );
                    assertThat(properties.getServerPort())
                            .isEqualTo(9090);
                    assertThat(properties.getProviderBusinessThreads())
                            .isEqualTo(6);
                    assertThat(
                            properties.getProviderBusinessQueueCapacity()
                    ).isEqualTo(300);
                });
    }

    @Test
    void shouldCreateDefaultSerializerAndLoadBalance() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Serializer.class);
            assertThat(context.getBean(Serializer.class))
                    .isInstanceOf(JsonSerializer.class);

            assertThat(context).hasSingleBean(LoadBalance.class);
            assertThat(context.getBean(LoadBalance.class))
                    .isInstanceOf(RoundRobinLoadBalance.class);
        });
    }

    @Test
    void shouldCreateConfiguredSerializerAndLoadBalance() {
        contextRunner
                .withPropertyValues(
                        "mini-rpc.serializer=jdk",
                        "mini-rpc.load-balance=random"
                )
                .run(context -> {
                    assertThat(context.getBean(Serializer.class))
                            .isInstanceOf(JdkSerializer.class);
                    assertThat(context.getBean(LoadBalance.class))
                            .isInstanceOf(RandomLoadBalance.class);
                });
    }

    @Test
    void shouldBindKebabCaseRoundRobinNameUsedByYaml() {
        contextRunner
                .withPropertyValues("mini-rpc.load-balance=round-robin")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LoadBalance.class))
                            .isInstanceOf(RoundRobinLoadBalance.class);
                });
    }

    @Test
    void shouldUseApplicationProvidedStrategyBeans() {
        Serializer customSerializer = new JsonSerializer();
        LoadBalance customLoadBalance = (serviceName, addresses) ->
                addresses.getFirst();

        contextRunner
                .withBean(Serializer.class, () -> customSerializer)
                .withBean(LoadBalance.class, () -> customLoadBalance)
                .run(context -> {
                    assertThat(context).hasSingleBean(Serializer.class);
                    assertThat(context.getBean(Serializer.class))
                            .isSameAs(customSerializer);
                    assertThat(context).hasSingleBean(LoadBalance.class);
                    assertThat(context.getBean(LoadBalance.class))
                            .isSameAs(customLoadBalance);
                });
    }

    @Test
    void shouldCreateInMemoryMetricsRegistryAndBuiltInFilters() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MeterRegistry.class);
            assertThat(context.getBean(MeterRegistry.class))
                    .isInstanceOf(SimpleMeterRegistry.class);
            assertThat(context).hasSingleBean(ConsumerMetricsFilter.class);
            assertThat(context).hasSingleBean(ProviderMetricsFilter.class);
        });
    }

    @Test
    void shouldUseApplicationProvidedMeterRegistry() {
        SimpleMeterRegistry customRegistry = new SimpleMeterRegistry();

        contextRunner
                .withBean(MeterRegistry.class, () -> customRegistry)
                .run(context -> {
                    assertThat(context).hasSingleBean(MeterRegistry.class);
                    assertThat(context.getBean(MeterRegistry.class))
                            .isSameAs(customRegistry);
                    assertThat(context).hasSingleBean(
                            ConsumerMetricsFilter.class
                    );
                    assertThat(context).hasSingleBean(
                            ProviderMetricsFilter.class
                    );
                });
    }

    @Test
    void shouldCollectConsumerAndProviderFiltersBySpringOrder() {
        contextRunner
                // 故意按“晚、早”的顺序注册，证明结果由 @Order 而非注册顺序决定。
                .withBean(LateConsumerFilter.class, LateConsumerFilter::new)
                .withBean(EarlyConsumerFilter.class, EarlyConsumerFilter::new)
                .withBean(LateProviderFilter.class, LateProviderFilter::new)
                .withBean(EarlyProviderFilter.class, EarlyProviderFilter::new)
                .run(context -> {
                    List<ConsumerFilter> consumerFilters =
                            MiniRpcAutoConfiguration.collectOrderedFilters(
                                    context.getBeanProvider(ConsumerFilter.class)
                            );
                    assertThat(consumerFilters)
                            .extracting(Object::getClass)
                            .containsExactly(
                                    ConsumerMetricsFilter.class,
                                    EarlyConsumerFilter.class,
                                    LateConsumerFilter.class
                            );

                    List<ProviderFilter> providerFilters =
                            MiniRpcAutoConfiguration.collectOrderedFilters(
                                    context.getBeanProvider(ProviderFilter.class)
                            );
                    assertThat(providerFilters)
                            .extracting(Object::getClass)
                            .containsExactly(
                                    ProviderMetricsFilter.class,
                                    EarlyProviderFilter.class,
                                    LateProviderFilter.class
                            );
                });
    }

    @Test
    void shouldDisableAutoConfigurationWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("mini-rpc.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MiniRpcProperties.class));
    }

    @Test
    void shouldRegisterLazyRpcClientFactoryWithoutConnectingZooKeeper() {
        contextRunner.run(context -> {
            /*
             * containsBean 只确认 Spring 已经保存了 Bean 定义，不会取得实例。
             * 因为 Bean 是 @Lazy 的，所以本测试不需要真的启动 ZooKeeper。
             */
            assertThat(context.containsBean("rpcClientFactory"))
                    .isTrue();

            assertThat(
                    context.getBeanFactory()
                            .getBeanDefinition("rpcClientFactory")
                            .isLazyInit()
            ).isTrue();

            /*
             * 单例池中还没有实例，证明 RpcClientFactory 构造方法尚未执行，
             * 因此也没有创建 Netty 线程或连接 ZooKeeper。
             */
            assertThat(
                    context.getBeanFactory()
                            .containsSingleton("rpcClientFactory")
            ).isFalse();

            assertThat(context)
                    .hasSingleBean(RpcReferenceBeanPostProcessor.class);
        });
    }

    @Test
    void shouldUseCustomRpcClientFactoryWhenApplicationProvidesOne() {
        /*
         * 注册一个延迟创建的“业务自定义工厂”定义。Supplier 如果被调用会立刻
         * 让测试失败，因此也能证明检查条件时不需要真正创建工厂或连接 ZooKeeper。
         */
        contextRunner
                .withBean(
                        "customRpcClientFactory",
                        RpcClientFactory.class,
                        () -> {
                            throw new AssertionError(
                                    "条件装配测试不应该实例化客户端工厂"
                            );
                        },
                        beanDefinition ->
                                beanDefinition.setLazyInit(true)
                )
                .run(context -> {
                    assertThat(context.containsBean("customRpcClientFactory"))
                            .isTrue();
                    assertThat(context.containsBean("rpcClientFactory"))
                            .isFalse();
                    assertThat(
                            context.getBeanFactory().containsSingleton(
                                    "customRpcClientFactory"
                            )
                    ).isFalse();
                });
    }

    @Test
    void shouldRequestClientFactoryOnlyForBeanWithRpcReference() {
        RuntimeException factoryRequested = new RuntimeException(
                "客户端工厂已被请求"
        );

        /*
         * ObjectProvider 的方法都有默认实现，因此它不是只有一个抽象方法的
         * “函数式接口”，不能使用 Lambda。这里用匿名类覆盖 getObject()。
         * 特定异常可以在完全不连接 ZooKeeper 的情况下，告诉测试客户端工厂
         * 是否真的被索取。
         */
        ObjectProvider<RpcClientFactory> provider = new ObjectProvider<>() {
            @Override
            public RpcClientFactory getObject() {
                throw factoryRequested;
            }
        };
        RpcReferenceBeanPostProcessor processor =
                new RpcReferenceBeanPostProcessor(provider);

        assertThatCode(() -> processor.postProcessProperties(
                null,
                new BeanWithoutRpcReference(),
                "beanWithoutRpcReference"
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> processor.postProcessProperties(
                null,
                new BeanWithRpcReference(),
                "beanWithRpcReference"
        )).isSameAs(factoryRequested);
    }

    @Test
    void shouldLetNettyClientUseConfiguredRequestTimeout() {
        /*
         * 构造客户端不会连接 Provider；真正 sendRequest() 时才会连接。
         * 因此这里可以只验证 750ms 已经成为底层实际保存的等待时间。
         */
        try (NettyRpcClient client = new NettyRpcClient(
                ignoredServiceName ->
                        new ServiceAddress("127.0.0.1", 8080),
                new JsonSerializer(),
                Duration.ofMillis(750)
        )) {
            assertThat(client.getRequestTimeout())
                    .isEqualTo(Duration.ofMillis(750));
        }
    }

    @Test
    void shouldRejectRequestTimeoutShorterThanOneMillisecond() {
        assertThatThrownBy(() -> new NettyRpcClient(
                ignoredServiceName ->
                        new ServiceAddress("127.0.0.1", 8080),
                new JsonSerializer(),
                Duration.ZERO
        ))
                .hasMessageContaining("不能小于 1 毫秒");
    }

    /** 没有 RPC 引用的普通 Spring Bean。 */
    static class BeanWithoutRpcReference {
    }

    /** 用来验证后置处理器能够发现注解的测试 Bean。 */
    static class BeanWithRpcReference {

        @RpcReference
        private Runnable remoteService;
    }

    @Order(10)
    static final class EarlyConsumerFilter implements ConsumerFilter {
        @Override
        public RpcResponse filter(RpcRequest request, ConsumerFilterChain chain) {
            return chain.proceed(request);
        }
    }

    @Order(20)
    static final class LateConsumerFilter implements ConsumerFilter {
        @Override
        public RpcResponse filter(RpcRequest request, ConsumerFilterChain chain) {
            return chain.proceed(request);
        }
    }

    @Order(10)
    static final class EarlyProviderFilter implements ProviderFilter {
        @Override
        public RpcResponse filter(RpcRequest request, ProviderFilterChain chain) {
            return chain.proceed(request);
        }
    }

    @Order(20)
    static final class LateProviderFilter implements ProviderFilter {
        @Override
        public RpcResponse filter(RpcRequest request, ProviderFilterChain chain) {
            return chain.proceed(request);
        }
    }
}
