package com.itheima.client;

import com.itheima.cluster.DefaultServiceAddressResolver;
import com.itheima.common.RpcException;
import com.itheima.loadbalance.RoundRobinLoadBalance;
import com.itheima.proxy.RpcClientProxy;
import com.itheima.registry.zookeeper.ZooKeeperClientFactory;
import com.itheima.registry.zookeeper.ZooKeeperServiceDiscovery;
import com.itheima.serialize.JsonSerializer;
import com.itheima.transport.client.NettyRpcClient;

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
     * <p>Spring Starter 会走这个构造方法，把 application.yml 中已经绑定成
     * {@link Duration} 的 request-timeout 一直传到 NettyRpcClient。</p>
     *
     * @param zooKeeperAddress ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     * @param requestTimeout   每次请求等待 Provider 响应的最长时间
     */
    public RpcClientFactory(
            String zooKeeperAddress,
            Duration requestTimeout
    ) {
        this.serviceDiscovery =
                new ZooKeeperServiceDiscovery(zooKeeperAddress);

        try {
            DefaultServiceAddressResolver addressResolver =
                    new DefaultServiceAddressResolver(
                            serviceDiscovery,
                            new RoundRobinLoadBalance()
                    );

            this.rpcClient = new NettyRpcClient(
                    addressResolver,
                    new JsonSerializer(),
                    requestTimeout
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
        if (closed) {
            throw new RpcException("已经关闭的 RpcClientFactory 不能创建代理");
        }
        return new RpcClientProxy(
                rpcClient,
                "1.0",
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
