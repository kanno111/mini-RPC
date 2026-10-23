package com.itheima.spring.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * mini-RPC 的 Spring Boot 自动配置入口。
 *
 * <p>业务项目只要引入 Starter，Spring Boot 就会根据
 * {@code AutoConfiguration.imports} 找到本类，不需要业务方手动写
 * {@code @Import(MiniRpcAutoConfiguration.class)}。</p>
 *
 * <p>目前本类只让 {@link MiniRpcProperties} 成为 Spring 容器管理的 Bean，
 * 暂时不会创建客户端、启动服务端或连接 ZooKeeper。后续每增加一种自动能力，
 * 都会从这里逐步装配。</p>
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
}
