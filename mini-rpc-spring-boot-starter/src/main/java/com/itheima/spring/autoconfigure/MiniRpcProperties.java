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
 *   provider-enabled: false
 *   server-host: 127.0.0.1
 *   server-port: 8080
 * }</pre>
 *
 * <p>自动绑定到本类的各个字段。这里的“绑定”可以简单理解为：Spring 读取
 * 配置文件，然后调用对应的 setter 把值写入 Java 对象。</p>
 *
 * <p>目前注册中心地址、Provider 开关、主机和端口已经接入自动配置；
 * {@code requestTimeout} 已能被读取，但还没有传入底层客户端。</p>
 */
@ConfigurationProperties(prefix = "mini-rpc")
public class MiniRpcProperties {

    /** 是否启用 mini-RPC 自动配置，默认启用。 */
    private boolean enabled = true;

    /** ZooKeeper 地址，格式为“主机:端口”。 */
    private String registryAddress = "127.0.0.1:2181";

    /** Consumer 等待一次 RPC 响应的最长时间。 */
    private Duration requestTimeout = Duration.ofSeconds(5);

    /** 是否把当前 Spring Boot 应用作为 RPC Provider 启动。 */
    private boolean providerEnabled;

    /** Provider 注册到 ZooKeeper、供 Consumer 访问的主机地址。 */
    private String serverHost = "127.0.0.1";

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

    public boolean isProviderEnabled() {
        return providerEnabled;
    }

    public void setProviderEnabled(boolean providerEnabled) {
        this.providerEnabled = providerEnabled;
    }

    public String getServerHost() {
        return serverHost;
    }

    public void setServerHost(String serverHost) {
        this.serverHost = serverHost;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }
}
