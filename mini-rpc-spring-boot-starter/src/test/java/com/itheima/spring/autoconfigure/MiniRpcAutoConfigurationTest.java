package com.itheima.spring.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

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
                        "mini-rpc.server-port=9090"
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
                    assertThat(properties.getServerPort())
                            .isEqualTo(9090);
                });
    }

    @Test
    void shouldDisableAutoConfigurationWhenExplicitlyDisabled() {
        contextRunner
                .withPropertyValues("mini-rpc.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MiniRpcProperties.class));
    }
}
