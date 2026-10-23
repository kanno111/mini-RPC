package com.itheima.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 接收 application.yml 或 application.properties 中的 mini-rpc 配置。
 *
 * <p>Spring Boot 启动时会把下面这样的配置：</p>
 *
 * <pre>{@code
 * mini-rpc:
 *   enabled: true
 *   registry-address: 127.0.0.1:2181
 *   request-timeout: 5s
 *   server-port: 8080
 * }</pre>
 *
 * <p>自动绑定到本类的四个字段。这里的“绑定”可以简单理解为：Spring 读取
 * 配置文件，然后调用对应的 setter 把值写入 Java 对象。</p>
 *
 * <p>当前 S1 只证明配置能够被读取，还不会使用这些配置启动 ZooKeeper 或
 * Netty。后续阶段再逐项接入，避免一次引入太多逻辑。</p>
 */
@ConfigurationProperties(prefix = "mini-rpc")
public class MiniRpcProperties {

    /** 是否启用 mini-RPC 自动配置，默认启用。 */
    private boolean enabled = true;

    /** ZooKeeper 地址，格式为“主机:端口”。 */
    private String registryAddress = "127.0.0.1:2181";

    /** Consumer 等待一次 RPC 响应的最长时间。 */
    private Duration requestTimeout = Duration.ofSeconds(5);

    /** Provider 对外监听的 Netty 端口。 */
    private int serverPort = 8080;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegistryAddress() {
        return registryAddress;
    }

    public void setRegistryAddress(String registryAddress) {
        this.registryAddress = registryAddress;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }
}
