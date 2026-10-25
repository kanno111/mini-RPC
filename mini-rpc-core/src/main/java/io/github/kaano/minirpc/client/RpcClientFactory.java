package io.github.kaano.minirpc.client;

import io.github.kaano.minirpc.cluster.DefaultServiceAddressResolver;
import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;
import io.github.kaano.minirpc.loadbalance.LoadBalance;
import io.github.kaano.minirpc.loadbalance.RoundRobinLoadBalance;
import io.github.kaano.minirpc.proxy.RpcClientProxy;
import io.github.kaano.minirpc.registry.zookeeper.ZooKeeperClientFactory;
import io.github.kaano.minirpc.registry.zookeeper.ZooKeeperServiceDiscovery;
import io.github.kaano.minirpc.serialize.JsonSerializer;
import io.github.kaano.minirpc.serialize.Serializer;
import io.github.kaano.minirpc.transport.client.NettyRpcClient;

import java.time.Duration;

/**
 * 面向 RPC 使用者的高层客户端工厂。
 *
 * <p>如果没有这个工厂，Consumer 需要自己了解并组装下面所有组件：</p>
 *
 * <pre>
 * ZooKeeperServiceDiscovery
 *          ↓
 * RoundRobinLoadBalance
 *          ↓
 * DefaultServiceAddressResolver
 *          ↓
 * JsonSerializer + NettyRpcClient
 *          ↓
 * RpcClientProxy
 * </pre>
 *
 * <p>这些都是 RPC 框架的内部细节，不应该让业务开发者重复编写。
 * 本类把它们统一封装后，使用者只需要：</p>
 *
 * <pre>{@code
 * try (RpcClientFactory factory = new RpcClientFactory()) {
 *     HelloService helloService = factory.createProxy(HelloService.class);
 *     String result = helloService.sayHello("world");
 * }
 * }</pre>
 *
 * <p>除了直接调用 {@link #createProxy(Class)}，还可以使用
 * {@link #injectReferences(Object)}，让框架为业务对象中标记了
 * {@code @RpcReference} 的字段自动注入代理。</p>
 */
public final class RpcClientFactory implements AutoCloseable {

    /** 负责从 ZooKeeper 查询服务地址，同时持有需要关闭的 Curator 客户端。 */
    private final ZooKeeperServiceDiscovery serviceDiscovery;

    /** 负责动态选址、建立 Channel、发送请求和接收响应。 */
    private final NettyRpcClient rpcClient;

    /** 负责把 Java 接口转换成 JDK 动态代理对象。 */
    private final RpcClientProxy proxyFactory;

    /** 防止关闭后继续创建代理，也让重复关闭保持安全。 */
    private volatile boolean closed;

    /**
     * 使用本地默认 ZooKeeper 地址 {@code 127.0.0.1:2181} 创建客户端工厂。
     */
    public RpcClientFactory() {
        this(
                ZooKeeperClientFactory.DEFAULT_CONNECT_STRING,
                NettyRpcClient.DEFAULT_REQUEST_TIMEOUT
        );
    }

    /**
     * 使用指定的 ZooKeeper 地址创建客户端工厂。
     *
     * <p>这里是客户端内部组件唯一的默认组装位置：</p>
     *
     * <ul>
     *     <li>注册中心：ZooKeeper；</li>
     *     <li>负载均衡：轮询；</li>
     *     <li>序列化：JSON；</li>
     *     <li>网络通信：Netty。</li>
     * </ul>
     *
     * @param zooKeeperAddress ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     */
    public RpcClientFactory(String zooKeeperAddress) {
        this(
                zooKeeperAddress,
                NettyRpcClient.DEFAULT_REQUEST_TIMEOUT
        );
    }

    /**
     * 使用指定 ZooKeeper 地址和请求超时时间创建客户端工厂。
     *
     * <p>这个兼容构造方法适合非 Spring 的手动入口：请求超时由调用方指定，
     * 心跳继续使用 core 默认值。Spring Starter 会使用下面的完整构造方法。</p>
     *
     * @param zooKeeperAddress ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     * @param requestTimeout   每次请求等待 Provider 响应的最长时间
     */
    public RpcClientFactory(
            String zooKeeperAddress,
            Duration requestTimeout
    ) {
        this(
                zooKeeperAddress,
                requestTimeout,
                new JsonSerializer(),
                new RoundRobinLoadBalance()
        );
    }

    /**
     * 使用指定的序列化器和负载均衡策略组装客户端调用链。
     *
     * <p>该构造方法适合手动选择序列化器和负载均衡器，并继续使用默认心跳时间。
     * core 仍然只依赖 {@link Serializer} 和 {@link LoadBalance} 接口；需要自定义
     * 心跳时使用下面的完整构造方法。</p>
     *
     * @param zooKeeperAddress ZooKeeper 地址
     * @param requestTimeout   默认请求等待时间
     * @param serializer       Consumer 发送请求正文时使用的序列化器
     * @param loadBalance      从服务地址列表中选择 Provider 的策略
     */
    public RpcClientFactory(
            String zooKeeperAddress,
            Duration requestTimeout,
            Serializer serializer,
            LoadBalance loadBalance
    ) {
        this(
                zooKeeperAddress,
                requestTimeout,
                NettyRpcClient.DEFAULT_HEARTBEAT_INTERVAL,
                NettyRpcClient.DEFAULT_HEARTBEAT_TIMEOUT,
                serializer,
                loadBalance
        );
    }

    /**
     * 使用完整的 Consumer 超时与策略配置组装客户端调用链。
     *
     * <p>这个构造方法是 Spring Boot Starter 到 core 的配置边界。Starter 只负责
     * 把文本配置绑定成 {@link Duration} 和策略对象，真正的心跳参数校验以及
     * IdleStateHandler 装配仍由 {@link NettyRpcClient} 负责。</p>
     *
     * @param zooKeeperAddress  ZooKeeper 地址
     * @param requestTimeout    默认请求等待时间
     * @param heartbeatInterval Channel 写空闲多久后发送 PING
     * @param heartbeatTimeout  Channel 读空闲多久后关闭连接
     * @param serializer        Consumer 发送消息正文时使用的序列化器
     * @param loadBalance       从服务地址列表中选择 Provider 的策略
     */
    public RpcClientFactory(
            String zooKeeperAddress,
            Duration requestTimeout,
            Duration heartbeatInterval,
            Duration heartbeatTimeout,
            Serializer serializer,
            LoadBalance loadBalance
    ) {
        this.serviceDiscovery =
                new ZooKeeperServiceDiscovery(zooKeeperAddress);

        try {
            DefaultServiceAddressResolver addressResolver =
                    new DefaultServiceAddressResolver(
                            serviceDiscovery,
                            loadBalance
                    );

            this.rpcClient = new NettyRpcClient(
                    addressResolver,
                    serializer,
                    requestTimeout,
                    heartbeatInterval,
                    heartbeatTimeout
            );
            this.proxyFactory = new RpcClientProxy(rpcClient);
        } catch (RuntimeException exception) {
            /*
             * ZooKeeper 已经连接成功，但后面的组件创建失败时，也必须关闭
             * Curator 客户端，避免它的后台线程和网络连接泄漏。
             */
            serviceDiscovery.close();
            throw exception;
        }
    }

    /**
     * 为一个服务接口创建可以直接调用的 RPC 代理对象。
     *
     * <p>创建代理时不会立即连接 Provider。第一次真正调用接口方法时，
     * NettyRpcClient 才会查询 ZooKeeper、选择地址并建立连接。</p>
     *
     * @param serviceInterface 客户端与服务端共享的服务接口
     * @param <T>              服务接口类型
     * @return 实现了该接口的动态代理对象
     */
    public <T> T createProxy(Class<T> serviceInterface) {
        if (closed) {
            throw new RpcException("已经关闭的 RpcClientFactory 不能创建代理");
        }
        return proxyFactory.createProxy(serviceInterface);
    }

    /**
     * 为一个服务接口创建带有单引用超时的代理。
     *
     * <p>这里新建的只有轻量级 {@link RpcClientProxy} 代理工厂；底层仍复用当前
     * {@code RpcClientFactory} 持有的同一个 NettyRpcClient、Channel 缓存和
     * ZooKeeper 服务发现连接。</p>
     *
     * @param serviceInterface 服务接口
     * @param requestTimeout   当前引用等待响应的最长时间
     * @param <T>              服务接口类型
     * @return 使用指定超时的动态代理
     */
    public <T> T createProxy(
            Class<T> serviceInterface,
            Duration requestTimeout
    ) {
        return createProxy(
                serviceInterface,
                ServiceKey.DEFAULT_GROUP,
                ServiceKey.DEFAULT_VERSION,
                requestTimeout
        );
    }

    /** 为指定服务分组和版本创建代理，并继承客户端全局请求超时。 */
    public <T> T createProxy(
            Class<T> serviceInterface,
            String group,
            String version
    ) {
        return createProxy(serviceInterface, group, version, null);
    }

    /**
     * 为完整服务身份创建代理，并可覆盖当前引用的请求超时。
     *
     * <p>底层 Netty 客户端、Channel 缓存和 ZooKeeper 会话仍由当前工厂共享；
     * 新建的只是保存 group/version/timeout 的轻量级代理工厂。</p>
     */
    public <T> T createProxy(
            Class<T> serviceInterface,
            String group,
            String version,
            Duration requestTimeout
    ) {
        if (closed) {
            throw new RpcException("已经关闭的 RpcClientFactory 不能创建代理");
        }
        return new RpcClientProxy(
                rpcClient,
                group,
                version,
                requestTimeout
        ).createProxy(serviceInterface);
    }

    /**
     * 为业务对象中带有 {@code @RpcReference} 的字段注入 RPC 动态代理。
     *
     * <p>例如业务对象中声明：</p>
     *
     * <pre>{@code
     * @RpcReference
     * private HelloService helloService;
     * }</pre>
     *
     * <p>调用本方法后，{@code helloService} 会指向一个 JDK 动态代理。注入过程
     * 本身不会连接 Provider；第一次调用代理方法时才会查询 ZooKeeper 并建立
     * 或复用 Netty 连接。</p>
     *
     * @param target 包含 RPC 引用字段的业务对象
     */
    public void injectReferences(Object target) {
        if (closed) {
            throw new RpcException("已经关闭的 RpcClientFactory 不能注入 RPC 引用");
        }
        new RpcReferenceInjector(this).inject(target);
    }

    /**
     * 关闭客户端持有的全部资源。
     *
     * <p>先关闭 Provider Channel 和 Netty 线程，再关闭 ZooKeeper 服务发现连接。
     * 即使关闭 Netty 时发生异常，finally 也会保证 ZooKeeper 客户端得到关闭。</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        try {
            rpcClient.close();
        } finally {
            serviceDiscovery.close();
        }
    }
}
