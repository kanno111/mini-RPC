package com.itheima.transport.client;

import com.itheima.cluster.ServiceAddressResolver;
import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.registry.ServiceAddress;
import com.itheima.serialize.Serializer;
import com.itheima.transport.codec.RpcDecoder;
import com.itheima.transport.codec.RpcEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Netty 的 RPC 客户端。
 *
 * <p>当前先实现客户端连接的生命周期：配置 Pipeline、连接服务端、复用 Channel
 * 和释放资源。请求发送与 Future 等待逻辑会在此基础上继续补充。</p>
 *
 * <pre>
 * NettyRpcClient
 *      ├── EventLoopGroup：执行客户端网络事件
 *      ├── Bootstrap：保存客户端连接配置
 *      ├── ServiceAddressResolver：为每次请求选择 Provider 地址
 *      ├── Channel 缓存：每个 Provider 地址复用一条连接
 *      └── Pipeline：负责编码请求、解码响应和匹配 Future
 * </pre>
 */
public class NettyRpcClient implements RpcClient, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRpcClient.class);

    /** 连接服务端最多等待 5 秒。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /**
     * 没有显式配置时，一次 RPC 请求默认最多等待服务端响应 5 秒。
     *
     * <p>这个常量是默认值，不再直接写死在等待逻辑中。Spring Starter 可以把
     * application.yml 中的 request-timeout 通过构造方法传进来。</p>
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT =
            Duration.ofSeconds(5);

    /**
     * Channel 上用于保存 PendingRequestManager 的属性键。
     *
     * <p>Netty 允许给每条 Channel 附加自己的属性。以后客户端同时连接多个
     * Provider 时，每条连接都会通过这个 key 取得属于自己的等待请求表。</p>
     */
    private static final AttributeKey<PendingRequestManager>
            PENDING_REQUEST_MANAGER_KEY =
            AttributeKey.valueOf("miniRpcPendingRequestManager");

    /** 根据请求中的接口名称，找到本次调用应使用的 Provider 地址。 */
    private final ServiceAddressResolver addressResolver;

    /** 请求和响应正文共用的序列化器。 */
    private final Serializer serializer;

    /**
     * 业务线程等待一次 RPC 响应的最长毫秒数。
     *
     * <p>它只限制“请求发出后等待响应”的时间，不等同于上面的 TCP 连接超时。</p>
     */
    private final long requestTimeoutMillis;

    /**
     * 已经建立的连接缓存：key 是 Provider 地址，value 是对应的 Netty Channel。
     *
     * <p>{@link ConcurrentHashMap} 允许多个业务线程并发读取已经建立的连接。</p>
     */
    private final Map<ServiceAddress, Channel> channels =
            new ConcurrentHashMap<>();

    /**
     * 创建连接和关闭客户端时共用的锁。
     *
     * <p>它可以防止两个线程同时为同一个地址建立重复连接，也可以防止客户端
     * 正在关闭时，另一个线程又把一条新连接放进缓存。</p>
     */
    private final Object connectionLock = new Object();

    /** 客户端的网络事件线程组；当前只维护一条连接，一个线程已经足够。 */
    private final EventLoopGroup eventLoopGroup;

    /** Netty 客户端启动器，可以理解为创建连接时使用的配置模板。 */
    private final Bootstrap bootstrap;

    /** 客户端是否已经被永久关闭。关闭后不允许重新连接。 */
    private volatile boolean closed;

    /**
     * 创建 Netty RPC 客户端，但此时还不会立即连接服务端。
     *
     * <p>这是为了兼容 M1～M3 的固定地址用法。内部会把固定地址包装成一个
     * {@link ServiceAddressResolver}；无论请求什么服务，它都返回同一个地址。</p>
     *
     * @param host       服务端主机名或 IP，例如 127.0.0.1
     * @param port       服务端端口，范围是 1 到 65535
     * @param serializer 请求和响应使用的序列化器
     */
    public NettyRpcClient(String host, int port, Serializer serializer) {
        this(
                createFixedAddressResolver(host, port),
                serializer,
                DEFAULT_REQUEST_TIMEOUT
        );
    }

    /**
     * 使用固定服务端地址，并指定一次请求等待响应的最长时间。
     *
     * @param host           服务端主机名或 IP
     * @param port           服务端端口
     * @param serializer     请求和响应使用的序列化器
     * @param requestTimeout 请求响应超时时间，最小为 1 毫秒
     */
    public NettyRpcClient(
            String host,
            int port,
            Serializer serializer,
            Duration requestTimeout
    ) {
        this(
                createFixedAddressResolver(host, port),
                serializer,
                requestTimeout
        );
    }

    /**
     * 创建能够动态发现 Provider 的 Netty RPC 客户端。
     *
     * <p>构造时不会连接任何 Provider。真正发送请求时，客户端才会根据请求中的
     * 接口名解析地址，并按需建立或复用该地址的 Channel。</p>
     *
     * @param addressResolver 服务地址解析器，负责服务发现和负载均衡
     * @param serializer      请求和响应使用的序列化器
     */
    public NettyRpcClient(
            ServiceAddressResolver addressResolver,
            Serializer serializer
    ) {
        this(addressResolver, serializer, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 创建能够动态发现 Provider、并使用指定请求超时的 Netty 客户端。
     *
     * @param addressResolver 服务地址解析器
     * @param serializer      请求和响应使用的序列化器
     * @param requestTimeout  请求发出后等待响应的最长时间，最小为 1 毫秒
     */
    public NettyRpcClient(
            ServiceAddressResolver addressResolver,
            Serializer serializer,
            Duration requestTimeout
    ) {
        if (addressResolver == null) {
            throw new RpcException("ServiceAddressResolver 不能为空");
        }
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }

        this.addressResolver = addressResolver;
        this.serializer = serializer;
        this.requestTimeoutMillis = validateRequestTimeout(requestTimeout);

        /*
         * Netty 4.2 把事件循环线程组和具体 I/O 实现分开配置。
         * NioIoHandler 表示使用 Java NIO Selector 处理网络事件。
         */
        this.eventLoopGroup = new MultiThreadIoEventLoopGroup(
                1,
                NioIoHandler.newFactory()
        );
        this.bootstrap = createBootstrap();
    }

    /**
     * 校验超时配置并转换成 CompletableFuture.get() 使用的毫秒数。
     */
    private static long validateRequestTimeout(Duration requestTimeout) {
        if (requestTimeout == null) {
            throw new RpcException("RPC 请求超时时间不能为空");
        }

        final long timeoutMillis;
        try {
            timeoutMillis = requestTimeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new RpcException("RPC 请求超时时间过大", exception);
        }

        if (timeoutMillis <= 0) {
            throw new RpcException("RPC 请求超时时间不能小于 1 毫秒");
        }
        return timeoutMillis;
    }

    /**
     * 返回当前真正参与请求等待的超时时间，便于测试和运行诊断。
     */
    public Duration getRequestTimeout() {
        return Duration.ofMillis(requestTimeoutMillis);
    }

    /** 把旧版的固定 host、port 包装成地址解析器。 */
    private static ServiceAddressResolver createFixedAddressResolver(
            String host,
            int port
    ) {
        ServiceAddress fixedAddress = new ServiceAddress(host, port);
        return ignoredServiceName -> fixedAddress;
    }

    /**
     * 创建客户端连接配置。
     *
     * <p>Bootstrap 与服务端的 ServerBootstrap 作用相似，但 Bootstrap 创建的是
     * 主动连接服务端的客户端 Channel，而不是监听端口的服务端 Channel。</p>
     */
    private Bootstrap createBootstrap() {
        Bootstrap clientBootstrap = new Bootstrap();

        clientBootstrap
                // 指定负责客户端网络事件的线程组。
                .group(eventLoopGroup)
                // 使用基于 Java NIO 的客户端 Socket Channel。
                .channel(NioSocketChannel.class)
                // 连接服务端超过 5 秒仍未成功，就让连接操作失败。
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                // RPC 通常发送小消息，尽量不等待合并数据包。
                .option(ChannelOption.TCP_NODELAY, true)
                // 每次建立 Channel 时，为它安装客户端 Pipeline。
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        configurePipeline(socketChannel);
                    }
                });

        return clientBootstrap;
    }

    /**
     * 为客户端连接安装处理器。
     *
     * <p>客户端和服务端使用同一套 M3 自定义协议。解码器会读取协议头中的消息类型，
     * 自动判断正文应该还原成 RpcRequest 还是 {@link RpcResponse}。</p>
     */
    private void configurePipeline(SocketChannel socketChannel) {
        /*
         * 每建立一条 Channel，就创建一份独立的等待请求表。
         *
         * 假设以后客户端同时连接 8080 和 8081：
         * - 8080 的 Channel 只保存发往 8080 的等待请求；
         * - 8081 的 Channel 只保存发往 8081 的等待请求。
         *
         * 因此 8080 断开时，它的 Handler 只会 failAll() 自己这份表，
         * 不会错误地让 8081 上仍在正常等待的请求失败。
         */
        PendingRequestManager channelPendingRequests =
                new PendingRequestManager();

        // 把请求表附加到当前 Channel，发送请求时可以从同一条 Channel 上取回。
        socketChannel.attr(PENDING_REQUEST_MANAGER_KEY)
                .set(channelPendingRequests);

        socketChannel.pipeline()
                // 入站：等待完整的 M3 协议帧，并把正文反序列化成 RpcResponse。
                .addLast(new RpcDecoder())
                // 出站：发送 RpcRequest 时，把它编码成“20 字节协议头 + 正文”。
                .addLast(new RpcEncoder(serializer))
                // 入站：只在当前 Channel 的等待请求表中匹配 response。
                .addLast(new NettyRpcClientHandler(channelPendingRequests));
    }

    /**
     * 取得指定 Provider 的连接；如果已有可用连接，则直接复用。
     *
     * <p>只有第一次访问某个地址，或者旧连接已经失效时，才会建立新 Channel。</p>
     */
    private Channel getOrCreateChannel(ServiceAddress address) {
        if (address == null) {
            throw new RpcException("解析得到的 Provider 地址不能为空");
        }

        Channel cachedChannel = channels.get(address);
        if (!closed && cachedChannel != null && cachedChannel.isActive()) {
            return cachedChannel;
        }

        synchronized (connectionLock) {
            if (closed) {
                throw new RpcException("已经关闭的 Netty RPC 客户端不能重新连接");
            }

            // 获取锁后再次检查，避免等待锁期间其他线程已经建立了连接。
            cachedChannel = channels.get(address);
            if (cachedChannel != null && cachedChannel.isActive()) {
                return cachedChannel;
            }

            if (cachedChannel != null) {
                channels.remove(address, cachedChannel);
                cachedChannel.close();
            }

            try {
                Channel newChannel = bootstrap
                        .connect(address.host(), address.port())
                        .sync()
                        .channel();

                channels.put(address, newChannel);

                /*
                 * Channel 将来如果被服务端关闭，就自动从缓存中删除。
                 * remove(address, newChannel) 只在 Map 中仍是这条旧连接时才删除，
                 * 不会误删该地址后来重新建立的新 Channel。
                 */
                newChannel.closeFuture().addListener(
                        ignored -> channels.remove(address, newChannel)
                );

                LOGGER.info("Netty RPC 客户端已连接 Provider：{}", address.asText());
                return newChannel;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RpcException("连接 RPC Provider 时线程被中断", exception);
            } catch (RuntimeException exception) {
                throw new RpcException(
                        "连接 RPC Provider 失败，地址：" + address.asText(),
                        exception
                );
            }
        }
    }

    /** 判断当前客户端是否至少拥有一条仍可用的 Provider 连接。 */
    public boolean isConnected() {
        return !closed && channels.values().stream().anyMatch(Channel::isActive);
    }

    /**
     * 发送一条 RPC 请求，并等待与它 requestId 相同的响应。
     *
     * <p>虽然 Netty 的网络写入和响应接收都是异步的，但 {@link RpcClient} 当前
     * 定义的是同步返回接口。因此这里使用 CompletableFuture 把异步响应转换成
     * 调用方可以同步取得的 RpcResponse。</p>
     *
     * @param request 本次远程方法调用的请求，不能为 {@code null}
     * @return 服务端返回的对应响应
     */
    @Override
    public RpcResponse sendRequest(RpcRequest request) {
        return sendRequest(request, requestTimeoutMillis);
    }

    /**
     * 使用当前代理指定的超时发送请求。
     *
     * <p>这里只覆盖本次等待时间，不修改客户端保存的全局默认值。因此不同
     * {@code @RpcReference} 创建的代理可以共享同一个 Netty 客户端和 Channel，
     * 同时分别使用不同的响应超时。</p>
     */
    @Override
    public RpcResponse sendRequest(
            RpcRequest request,
            Duration requestTimeout
    ) {
        return sendRequest(request, validateRequestTimeout(requestTimeout));
    }

    /** 真正执行网络发送，effectiveTimeoutMillis 是本次调用最终采用的超时。 */
    private RpcResponse sendRequest(
            RpcRequest request,
            long effectiveTimeoutMillis
    ) {
        if (request == null) {
            throw new RpcException("RpcRequest 不能为空");
        }

        /*
         * 第一步：用接口名解析出本次调用的 Provider 地址。
         * 动态解析器会在内部完成“服务发现 + 负载均衡”；固定地址解析器则始终
         * 返回同一个地址。
         */
        ServiceAddress targetAddress =
                addressResolver.resolve(request.getInterfaceName());

        /*
         * 第二步：取得该地址对应的 Channel。
         * 已连接过就复用；第一次使用或旧连接失效时才新建。
         */
        Channel currentChannel = getOrCreateChannel(targetAddress);
        if (!currentChannel.isActive()) {
            long requestId = request.getRequestId();
            throw new RpcException(
                    "发送 RPC 请求前连接已经失效，requestId：" + requestId
                            + "，Provider：" + targetAddress.asText()
            );
        }

        /*
         * 第三步：从“本次真正使用的 Channel”上取得它自己的等待请求表。
         * 这能确保请求只受当前 Provider 连接状态的影响。
         */
        PendingRequestManager pendingRequestManager =
                currentChannel.attr(PENDING_REQUEST_MANAGER_KEY).get();
        if (pendingRequestManager == null) {
            throw new RpcException("当前 Channel 缺少等待请求管理器");
        }

        long requestId = request.getRequestId();

        /*
         * 此时服务发现和建连已经完成，接下来才真正发送请求并开始等待响应。
         * 因此 deadline 与当前 request-timeout 的原有语义一致，不把首次建连时间
         * 计算在响应等待窗口中。已有更早 deadline 时只收紧、不延长，给以后重试
         * 或上游 deadline 继续向下传播保留正确语义。
         */
        applyDeadline(
                request,
                effectiveTimeoutMillis,
                System.currentTimeMillis()
        );

        /*
         * 必须先登记 Future，再发送请求。
         * 如果先发送，响应速度非常快时，客户端 Handler 可能已经收到响应，
         * 但 Map 中还没有 Future，导致响应无法匹配。
         */
        CompletableFuture<RpcResponse> responseFuture =
                pendingRequestManager.register(requestId);

        /*
         * 第四步：异步发送请求。
         * writeAndFlush 会立即返回一个 Netty Future，不会等待服务端响应。
         * 如果连“写入网络”这一步都失败，就让对应 CompletableFuture 异常完成。
         */
        currentChannel.writeAndFlush(request).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                pendingRequestManager.completeExceptionally(
                        requestId,
                        new RpcException(
                                "写入 RPC 请求失败，requestId：" + requestId,
                                writeFuture.cause()
                        )
                );
            }
        });

        /*
         * 第五步：业务线程限时等待响应。
         * Netty I/O 线程收到响应后，NettyRpcClientHandler 会调用
         * responseFuture.complete(response)，从而唤醒这里的业务线程。
         */
        try {
            return responseFuture.get(
                    effectiveTimeoutMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (TimeoutException exception) {
            RpcException timeoutException = new RpcException(
                    "等待 RPC 响应超时，requestId：" + requestId
                            + "，超时时间：" + effectiveTimeoutMillis + " 毫秒",
                    exception
            );
            pendingRequestManager.completeExceptionally(requestId, timeoutException);
            throw timeoutException;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            RpcException interruptedException = new RpcException(
                    "等待 RPC 响应时线程被中断，requestId：" + requestId,
                    exception
            );
            pendingRequestManager.completeExceptionally(requestId, interruptedException);
            throw interruptedException;
        } catch (ExecutionException exception) {
            /*
             * Future 异常完成时，真正原因被放在 ExecutionException.cause 中。
             * 如果它已经是 RpcException，就直接向上抛出，保留原始错误信息。
             */
            Throwable cause = exception.getCause();
            if (cause instanceof RpcException rpcException) {
                throw rpcException;
            }
            throw new RpcException(
                    "等待 RPC 响应失败，requestId：" + requestId,
                    cause
            );
        }
    }

    /**
     * 把本次有效超时转换成绝对 deadline，并保留请求中已经存在的更早期限。
     *
     * <p>方法保持包可见，便于单元测试使用固定时间验证计算，不需要真的建立
     * Netty 连接。加法溢出时使用 {@link Long#MAX_VALUE}，表示一个极远的期限。</p>
     */
    static void applyDeadline(
            RpcRequest request,
            long effectiveTimeoutMillis,
            long currentEpochMillis
    ) {
        if (request == null) {
            throw new RpcException("RpcRequest 不能为空");
        }
        if (effectiveTimeoutMillis <= 0) {
            throw new RpcException("RPC 请求超时时间不能小于 1 毫秒");
        }

        long candidateDeadline = currentEpochMillis
                > Long.MAX_VALUE - effectiveTimeoutMillis
                ? Long.MAX_VALUE
                : currentEpochMillis + effectiveTimeoutMillis;
        long existingDeadline = request.getDeadlineEpochMillis();

        if (existingDeadline <= 0 || candidateDeadline < existingDeadline) {
            request.setDeadlineEpochMillis(candidateDeadline);
        }
    }

    /**
     * 关闭全部 Provider 连接和客户端网络线程组。
     *
     * <p>该方法实现 {@link AutoCloseable}，以后既可以主动调用，也可以放进
     * try-with-resources 中自动执行。重复关闭是安全的。</p>
     */
    @Override
    public void close() {
        List<Channel> channelsToClose;

        synchronized (connectionLock) {
            if (closed) {
                return;
            }

            closed = true;
            channelsToClose = new ArrayList<>(channels.values());
            channels.clear();
        }

        for (Channel channelToClose : channelsToClose) {
            channelToClose.close().syncUninterruptibly();
        }

        /*
         * Channel 关闭会触发属于该 Channel 的 Handler.channelInactive()，
         * 由 Handler 结束这条连接上仍在等待的所有请求。
         */
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();

        LOGGER.info("Netty RPC 客户端已关闭，共关闭 {} 条 Provider 连接", channelsToClose.size());
    }
}
