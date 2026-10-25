package com.itheima.spring.autoconfigure;

import com.itheima.transport.client.NettyRpcClient;
import com.itheima.transport.server.NettyRpcServer;
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
 *   heartbeat-interval: 15s
 *   heartbeat-timeout: 45s
 *   shutdown-timeout: 10s
 *   serializer: json
 *   load-balance: round-robin
 *   provider-enabled: false
 *   server-host: 127.0.0.1
 *   server-port: 8080
 *   provider-business-threads: 8
 *   provider-business-queue-capacity: 1024
 * }</pre>
 *
 * <p>自动绑定到本类的各个字段。这里的“绑定”可以简单理解为：Spring 读取
 * 配置文件，然后调用对应的 setter 把值写入 Java 对象。</p>
 *
 * <p>目前注册中心地址、请求超时、心跳时间、关闭排空超时、策略选择、Provider
 * 开关、主机、端口和 Provider 业务线程池参数都已经接入自动配置。</p>
 */
@ConfigurationProperties(prefix = "mini-rpc")
public class MiniRpcProperties {

    /** 是否启用 mini-RPC 自动配置，默认启用。 */
    private boolean enabled = true;

    /** ZooKeeper 地址，格式为“主机:端口”。 */
    private String registryAddress = "127.0.0.1:2181";

    /** Consumer 等待一次 RPC 响应的最长时间。 */
    private Duration requestTimeout = NettyRpcClient.DEFAULT_REQUEST_TIMEOUT;

    /** Consumer Channel 写空闲多久后发送 PING。 */
    private Duration heartbeatInterval =
            NettyRpcClient.DEFAULT_HEARTBEAT_INTERVAL;

    /** Consumer Channel 读空闲多久后关闭失效连接。 */
    private Duration heartbeatTimeout =
            NettyRpcClient.DEFAULT_HEARTBEAT_TIMEOUT;

    /** Provider 关闭时等待在途请求完成的最长时间。 */
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    /** 本端发送 RPC 正文时使用的序列化方式，默认继续使用 JSON。 */
    private SerializerType serializer = SerializerType.JSON;

    /** Consumer 从多个 Provider 中选择地址的策略，默认继续使用轮询。 */
    private LoadBalanceType loadBalance = LoadBalanceType.ROUND_ROBIN;

    /** 是否把当前 Spring Boot 应用作为 RPC Provider 启动。 */
    private boolean providerEnabled;

    /** Provider 注册到 ZooKeeper、供 Consumer 访问的主机地址。 */
    private String serverHost = "127.0.0.1";

    /** Provider 对外监听的 Netty 端口。 */
    private int serverPort = 8080;

    /** Provider 执行 @RpcService 方法的固定业务线程数。 */
    private int providerBusinessThreads =
            NettyRpcServer.DEFAULT_BUSINESS_THREAD_COUNT;

    /** Provider 业务线程全部忙碌时，最多允许多少个请求排队。 */
    private int providerBusinessQueueCapacity =
            NettyRpcServer.DEFAULT_BUSINESS_QUEUE_CAPACITY;

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

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public void setHeartbeatTimeout(Duration heartbeatTimeout) {
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public SerializerType getSerializer() {
        return serializer;
    }

    public void setSerializer(SerializerType serializer) {
        this.serializer = serializer;
    }

    public LoadBalanceType getLoadBalance() {
        return loadBalance;
    }

    public void setLoadBalance(LoadBalanceType loadBalance) {
        this.loadBalance = loadBalance;
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

    public int getProviderBusinessThreads() {
        return providerBusinessThreads;
    }

    public void setProviderBusinessThreads(int providerBusinessThreads) {
        this.providerBusinessThreads = providerBusinessThreads;
    }

    public int getProviderBusinessQueueCapacity() {
        return providerBusinessQueueCapacity;
    }

    public void setProviderBusinessQueueCapacity(
            int providerBusinessQueueCapacity
    ) {
        this.providerBusinessQueueCapacity = providerBusinessQueueCapacity;
    }

    /** Starter 当前内置的序列化器名称。 */
    public enum SerializerType {
        /** Jackson JSON：可读性好，也是项目原来的默认行为。 */
        JSON,
        /** Java 原生对象流：仅建议用于学习和受信任的 Java 环境。 */
        JDK
    }

    /** Starter 当前内置的负载均衡策略名称。 */
    public enum LoadBalanceType {
        /** 按服务分别维护计数器，依次循环选择 Provider。 */
        ROUND_ROBIN,
        /** 每次从候选 Provider 中等概率随机选择。 */
        RANDOM
    }
}
