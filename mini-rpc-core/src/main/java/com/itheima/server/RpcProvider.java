package com.itheima.server;

import com.itheima.annotation.RpcService;
import com.itheima.common.RpcException;
import com.itheima.provider.DefaultServiceProvider;
import com.itheima.provider.RpcRequestHandler;
import com.itheima.provider.ServiceProvider;
import com.itheima.registry.ServiceAddress;
import com.itheima.registry.ServiceRegistry;
import com.itheima.registry.zookeeper.ZooKeeperClientFactory;
import com.itheima.registry.zookeeper.ZooKeeperServiceRegistry;
import com.itheima.serialize.JsonSerializer;
import com.itheima.serialize.Serializer;
import com.itheima.transport.server.NettyRpcServer;
import com.itheima.transport.server.RpcServer;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 面向 RPC 服务提供者使用者的高层启动入口。
 *
 * <p>如果没有这个类，ProviderMain 需要亲自创建本地服务表、请求处理器、
 * JSON 序列化器、Netty 服务端和 ZooKeeper 注册中心。那些都是框架内部细节，
 * 不应该在每一个业务服务中重复出现。</p>
 *
 * <p>封装之后，Provider 的基本使用方式是：</p>
 *
 * <pre>{@code
 * try (RpcProvider provider = new RpcProvider(8080)) {
 *     provider.scanServices(ProviderMain.class);
 *     provider.start();
 * }
 * }</pre>
 *
 * <p>{@link #scanServices(Class)} 会扫描启动类所在的包，找到带有
 * {@link RpcService} 注解的实现类，创建对象并完成服务注册。</p>
 */
public final class RpcProvider implements AutoCloseable {

    /** 本地学习环境对外公布的默认主机地址。 */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /** 保存“接口名称 -> 实现对象”的当前进程本地服务表。 */
    private final ServiceProvider serviceProvider;

    /** 把 Provider 地址发布到注册中心，并在关闭时释放注册中心连接。 */
    private final ServiceRegistry serviceRegistry;

    /** 监听端口并处理 RPC 网络请求的 Netty 服务端。 */
    private final RpcServer rpcServer;

    /** 当前 Provider 对外公布的网络地址。 */
    private final ServiceAddress serviceAddress;

    /**
     * 当前已经注册的服务名称。
     *
     * <p>它主要用于判断启动前是否至少注册了一个服务，也为后续自动扫描和
     * 优雅注销保留统一的服务清单。</p>
     */
    private final Set<String> serviceNames = new LinkedHashSet<>();

    /**
     * 已经成功发布到注册中心的服务名称。
     *
     * <p>{@link #serviceNames} 表示本地准备提供什么服务，本集合表示哪些地址节点
     * 已经真正创建成功。关闭时只主动注销后者，避免端口绑定失败时对一个从未发布
     * 的地址执行多余删除，也能正确清理“发布到一半失败”的部分结果。</p>
     */
    private final Set<String> publishedServiceNames = new LinkedHashSet<>();

    /** 是否已经进入过 start()；一个 Provider 实例只能启动一次。 */
    private boolean started;

    /** 是否已经关闭；关闭后不能继续注册服务或再次启动。 */
    private boolean closed;

    /**
     * 使用本机地址和本机 ZooKeeper 创建 Provider。
     *
     * @param port Provider 的 Netty 监听端口
     */
    public RpcProvider(int port) {
        this(
                DEFAULT_HOST,
                port,
                ZooKeeperClientFactory.DEFAULT_CONNECT_STRING
        );
    }

    /**
     * 使用指定网络地址和 ZooKeeper 创建 Provider。
     *
     * <p>构造方法会完成框架内部组件的组装，但此时 Netty 还没有开始监听端口。
     * 真正监听端口发生在调用 {@link #start()} 时。</p>
     *
     * @param host             Consumer 能够访问到的 Provider 主机地址
     * @param port             Provider 的 Netty 监听端口
     * @param zooKeeperAddress ZooKeeper 地址，例如 {@code 127.0.0.1:2181}
     */
    public RpcProvider(
            String host,
            int port,
            String zooKeeperAddress
    ) {
        this(host, port, zooKeeperAddress, new JsonSerializer());
    }

    /**
     * 使用指定序列化器创建 Provider。
     *
     * <p>序列化方式决定 Provider 响应正文的编码格式。协议头会携带序列化类型，
     * 接收端据此选择对应解码器。Starter 会把 {@code mini-rpc.serializer} 选中的
     * 实现传到这里，业务服务不需要手动创建序列化器。</p>
     *
     * @param host             Consumer 能访问到的 Provider 主机地址
     * @param port             Provider 的 Netty 监听端口
     * @param zooKeeperAddress ZooKeeper 地址
     * @param serializer       Provider 发送响应正文时使用的序列化器
     */
    public RpcProvider(
            String host,
            int port,
            String zooKeeperAddress,
            Serializer serializer
    ) {
        this(
                host,
                port,
                zooKeeperAddress,
                serializer,
                NettyRpcServer.DEFAULT_SHUTDOWN_TIMEOUT
        );
    }

    /**
     * 使用指定序列化器和关闭排空时间创建 Provider。
     *
     * <p>Starter 使用这个构造方法把 {@code mini-rpc.shutdown-timeout} 一直传到
     * Netty 服务端。超时只控制关闭时等待已开始请求的时间，不影响普通 RPC 请求
     * 的 Consumer 响应超时。</p>
     */
    public RpcProvider(
            String host,
            int port,
            String zooKeeperAddress,
            Serializer serializer,
            Duration shutdownTimeout
    ) {
        this.serviceAddress = new ServiceAddress(host, port);
        this.serviceProvider = new DefaultServiceProvider();

        RpcRequestHandler requestHandler =
                new RpcRequestHandler(serviceProvider);
        this.rpcServer = new NettyRpcServer(
                port,
                serializer,
                requestHandler,
                shutdownTimeout
        );
        this.serviceRegistry =
                new ZooKeeperServiceRegistry(zooKeeperAddress);
    }

    /**
     * 直接注入 Provider 的四个核心组件。
     *
     * <p>这个构造方法只向同包代码开放，主要用于单元测试：测试可以放入内存版
     * ServiceProvider、ServiceRegistry 和 RpcServer，不需要真的启动 Netty 或
     * 连接 ZooKeeper。正式使用仍然通过上面的公开构造方法完成组装。</p>
     */
    RpcProvider(
            ServiceProvider serviceProvider,
            ServiceRegistry serviceRegistry,
            RpcServer rpcServer,
            ServiceAddress serviceAddress
    ) {
        if (serviceProvider == null) {
            throw new RpcException("ServiceProvider 不能为空");
        }
        if (serviceRegistry == null) {
            throw new RpcException("ServiceRegistry 不能为空");
        }
        if (rpcServer == null) {
            throw new RpcException("RpcServer 不能为空");
        }
        if (serviceAddress == null) {
            throw new RpcException("ServiceAddress 不能为空");
        }

        this.serviceProvider = serviceProvider;
        this.serviceRegistry = serviceRegistry;
        this.rpcServer = rpcServer;
        this.serviceAddress = serviceAddress;
    }

    /**
     * 自动扫描并注册指定类所在包中的 RPC 服务。
     *
     * <p>传入的 {@code packageAnchor} 叫作“包定位类”。框架并不是要把这个类
     * 注册成服务，而是借助它确定从哪个包开始扫描。例如传入
     * {@code ProviderMain.class}，框架会扫描 {@code ProviderMain} 所在的
     * {@code com.itheima.example.provider} 包及其子包。</p>
     *
     * <p>扫描器会创建所有带有 {@link RpcService} 注解的对象；随后继续调用
     * {@link #registerService(Object)}，复用已经写好的注解读取、本地服务表注册
     * 和待发布服务记录流程。</p>
     *
     * @param packageAnchor 用来确定扫描起点的类，通常传入启动类
     * @return 当前 RpcProvider
     */
    public synchronized RpcProvider scanServices(Class<?> packageAnchor) {
        validateCanRegister();

        if (packageAnchor == null) {
            throw new RpcException("RPC 服务扫描的包定位类不能为空");
        }

        String basePackage = packageAnchor.getPackageName();
        for (Object service :
                RpcServiceScanner.scanAndCreateServices(basePackage)) {
            registerService(service);
        }
        return this;
    }

    /**
     * 注册一个可以被 Consumer 远程调用的服务实现。
     *
     * <p>调用这个方法时只登记当前 JVM 的本地服务表：</p>
     *
     * <ol>
     *     <li>保存“接口名称 -> 实现对象”，服务端收到请求后才能找到实现对象；</li>
     *     <li>记录待发布的服务名称，但暂时不写入 ZooKeeper。</li>
     * </ol>
     *
     * <p>真正写入 ZooKeeper 会推迟到 {@link #start()} 中，而且必须等 Netty
     * 端口绑定成功。这样 Consumer 不会发现一个尚未能够建立连接的地址。</p>
     *
     * @param serviceInterface 对外共享的服务接口
     * @param service          真正执行业务方法的接口实现对象
     * @param <T>              服务接口类型
     * @return 当前 RpcProvider，便于以后支持链式注册多个服务
     */
    public synchronized <T> RpcProvider registerService(
            Class<T> serviceInterface,
            T service
    ) {
        validateCanRegister();

        return registerServiceInternal(serviceInterface, service);
    }

    /**
     * 根据实现类上的 {@link RpcService} 注解注册服务。
     *
     * <p>调用者只需要传入业务实现对象，不再需要重复传入接口类型。例如：</p>
     *
     * <pre>{@code
     * @RpcService(serviceInterface = HelloService.class)
     * public class HelloServiceImpl implements HelloService {
     * }
     *
     * provider.registerService(new HelloServiceImpl());
     * }</pre>
     *
     * <p>这里的主要步骤是：</p>
     *
     * <ol>
     *     <li>取得实现对象的 {@link Class}；</li>
     *     <li>从 Class 上读取 {@link RpcService} 注解；</li>
     *     <li>从注解中取得要暴露的接口；</li>
     *     <li>复用原来的登记流程，写入本地服务表并记录服务名称。</li>
     * </ol>
     *
     * @param service 带有 {@link RpcService} 注解的服务实现对象
     * @return 当前 RpcProvider，便于链式注册多个服务
     * @throws RpcException 实现对象为空或没有标记 {@link RpcService} 时抛出
     */
    public synchronized RpcProvider registerService(Object service) {
        validateCanRegister();

        if (service == null) {
            throw new RpcException("服务实现对象不能为空");
        }

        Class<?> implementationClass = service.getClass();
        RpcService rpcService = implementationClass.getAnnotation(
                RpcService.class
        );

        if (rpcService == null) {
            throw new RpcException(
                    "服务实现类缺少 @RpcService 注解："
                            + implementationClass.getName()
            );
        }

        Class<?> serviceInterface = rpcService.serviceInterface();
        return registerServiceInternal(serviceInterface, service);
    }

    /**
     * 执行本地服务表登记，并记录启动后需要发布的服务名称。
     *
     * <p>两个公开的 registerService 方法只是用不同方式取得接口类型，真正的
     * 注册动作集中在这里，避免复制同一段代码。</p>
     */
    private RpcProvider registerServiceInternal(
            Class<?> serviceInterface,
            Object service
    ) {

        serviceProvider.registerService(serviceInterface, service);
        String serviceName = serviceInterface.getName();

        serviceNames.add(serviceName);
        return this;
    }

    /**
     * 启动 Netty 服务端并阻塞当前线程，直到 Provider 被关闭。
     *
     * <p>启动前必须至少注册一个服务，否则即使端口监听成功，Consumer 也没有
     * 任何可以调用的接口，这通常意味着使用者遗漏了注册步骤。</p>
     */
    public void start() {
        synchronized (this) {
            if (closed) {
                throw new RpcException("已经关闭的 RpcProvider 不能启动");
            }
            if (started) {
                throw new RpcException("RpcProvider 只能启动一次");
            }
            if (serviceNames.isEmpty()) {
                throw new RpcException("启动 RpcProvider 前至少要注册一个服务");
            }

            started = true;
        }

        /*
         * IDEA 停止进程或操作系统关闭 JVM 时，关闭钩子负责释放端口和
         * ZooKeeper 会话。close() 允许重复调用，因此和下面 finally 不冲突。
         */
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::close, "rpc-provider-shutdown")
        );

        try {
            /*
             * RpcServer 先绑定操作系统端口。绑定成功后，它才会执行
             * publishServices()；所以 ZooKeeper 中出现地址时，Netty 已经具备
             * 接受连接的能力。
             */
            rpcServer.start(this::publishServices);
        } finally {
            /*
             * 无论是正常停止、线程中断，还是端口绑定失败，都要关闭注册中心。
             * ZooKeeper 会话关闭后，当前实例的临时地址节点会被自动删除。
             */
            close();
        }
    }

    /**
     * 把当前 Provider 已登记的全部服务发布到注册中心。
     *
     * <p>该方法由 RpcServer 在端口绑定成功后回调。若任意一次注册失败，异常会
     * 沿调用链返回，RpcServer 会关闭刚绑定的端口，RpcProvider 的 finally 还会
     * 关闭注册中心会话，从而清理本次可能已经创建的临时节点。</p>
     */
    private synchronized void publishServices() {
        if (closed) {
            throw new RpcException("已经关闭的 RpcProvider 不能发布服务");
        }

        for (String serviceName : serviceNames) {
            serviceRegistry.register(serviceName, serviceAddress);
            /*
             * 必须在 register 成功返回后再记录。如果第二个服务发布失败，前面已经
             * 成功的服务仍会留在集合中，随后 close() 可以主动注销这些部分结果。
             */
            publishedServiceNames.add(serviceName);
        }
    }

    /** 注册服务只允许发生在 Provider 启动之前。 */
    private void validateCanRegister() {
        if (closed) {
            throw new RpcException("已经关闭的 RpcProvider 不能注册服务");
        }
        if (started) {
            throw new RpcException("RpcProvider 启动后不能继续注册服务");
        }
    }

    /**
     * 按“先摘流、再停服务、最后关注册中心”的顺序关闭 Provider。
     *
     * <ol>
     *     <li>主动注销已经发布的地址，让 Consumer 的 Watch 尽快失效旧缓存；</li>
     *     <li>停止 Netty 监听，并等待 Netty worker 优雅结束正在执行的任务；</li>
     *     <li>关闭 ZooKeeper 会话，作为临时节点清理的最终兜底。</li>
     * </ol>
     *
     * <p>方法是幂等的，也就是调用多次和调用一次效果相同。即使某一步失败，后续
     * 资源仍会继续释放；最后再抛出第一个异常，并把其他清理异常附加进去。</p>
     */
    @Override
    public void close() {
        List<String> servicesToUnregister;

        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            servicesToUnregister = List.copyOf(publishedServiceNames);
            publishedServiceNames.clear();
        }

        RuntimeException cleanupFailure = null;

        for (String serviceName : servicesToUnregister) {
            try {
                serviceRegistry.unregister(serviceName, serviceAddress);
            } catch (RuntimeException exception) {
                cleanupFailure = mergeCleanupFailure(
                        cleanupFailure,
                        exception
                );
            }
        }

        try {
            rpcServer.stop();
        } catch (RuntimeException exception) {
            cleanupFailure = mergeCleanupFailure(
                    cleanupFailure,
                    exception
            );
        }

        try {
            serviceRegistry.close();
        } catch (RuntimeException exception) {
            cleanupFailure = mergeCleanupFailure(
                    cleanupFailure,
                    exception
            );
        }

        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    /** 保留第一次清理失败，并把后续失败作为 suppressed 异常附加。 */
    private RuntimeException mergeCleanupFailure(
            RuntimeException firstFailure,
            RuntimeException nextFailure
    ) {
        if (firstFailure == null) {
            return nextFailure;
        }

        firstFailure.addSuppressed(nextFailure);
        return firstFailure;
    }
}
