package com.itheima.transport.client;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
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
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
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
 *      ├── Channel：代表与服务端建立的一条连接
 *      └── Pipeline：负责编码请求、解码响应和匹配 Future
 * </pre>
 */
public class NettyRpcClient implements RpcClient, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRpcClient.class);

    /** M2 单条响应正文最大为 10 MB；完整帧还包含 4 字节长度。 */
    private static final int MAX_FRAME_LENGTH = 10 * 1024 * 1024 + Integer.BYTES;

    /** 连接服务端最多等待 5 秒。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** 一次 RPC 请求最多等待服务端响应 5 秒。 */
    private static final long REQUEST_TIMEOUT_SECONDS = 5;

    /** RPC 服务端主机名或 IP 地址。 */
    private final String host;

    /** RPC 服务端端口。 */
    private final int port;

    /** 请求和响应正文共用的序列化器。 */
    private final Serializer serializer;

    /** 保存“requestId → 等待响应的 Future”的对应关系。 */
    private final PendingRequestManager pendingRequestManager;

    /** 客户端收到 RpcResponse 后使用的最后一个入站 Handler。 */
    private final NettyRpcClientHandler clientHandler;

    /** 客户端的网络事件线程组；当前只维护一条连接，一个线程已经足够。 */
    private final EventLoopGroup eventLoopGroup;

    /** Netty 客户端启动器，可以理解为创建连接时使用的配置模板。 */
    private final Bootstrap bootstrap;

    /**
     * 当前与服务端建立的连接。
     *
     * <p>声明为 volatile，保证业务线程能够看到 Netty 线程更新后的 Channel 状态。</p>
     */
    private volatile Channel channel;

    /** 客户端是否已经被永久关闭。关闭后不允许重新连接。 */
    private volatile boolean closed;

    /**
     * 创建 Netty RPC 客户端，但此时还不会立即连接服务端。
     *
     * <p>采用延迟连接的原因是：创建客户端对象只完成配置，真正需要发送请求时
     * 再建立网络连接。如果用户希望提前连接，也可以主动调用 {@link #connect()}。</p>
     *
     * @param host       服务端主机名或 IP，例如 127.0.0.1
     * @param port       服务端端口，范围是 1 到 65535
     * @param serializer 请求和响应使用的序列化器
     */
    public NettyRpcClient(String host, int port, Serializer serializer) {
        if (host == null || host.isBlank()) {
            throw new RpcException("RPC 服务端地址不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new RpcException("RPC 服务端端口必须在 1 到 65535 之间：" + port);
        }
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }

        this.host = host;
        this.port = port;
        this.serializer = serializer;
        this.pendingRequestManager = new PendingRequestManager();
        this.clientHandler = new NettyRpcClientHandler(pendingRequestManager);

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
     * <p>客户端和服务端使用相同的临时消息格式，但解码目标不同：服务端解码
     * RpcRequest，客户端解码 {@link RpcResponse}。</p>
     */
    private void configurePipeline(SocketChannel socketChannel) {
        socketChannel.pipeline()
                // 入站：根据 4 字节长度字段取得一条完整响应正文。
                .addLast(new LengthFieldBasedFrameDecoder(
                        MAX_FRAME_LENGTH,
                        0,
                        Integer.BYTES,
                        0,
                        Integer.BYTES
                ))
                // 入站：把完整正文反序列化成 RpcResponse。
                .addLast(new RpcDecoder<>(serializer, RpcResponse.class))
                // 出站：以后发送 RpcRequest 时，把它编码成“长度 + 正文”。
                .addLast(new RpcEncoder(serializer))
                // 入站：根据响应 requestId 完成对应 Future。
                .addLast(clientHandler);
    }

    /**
     * 建立连接；如果已有可用连接，则直接复用，不会重复连接。
     *
     * <p>方法使用 synchronized，防止多个业务线程第一次同时发送请求时，
     * 各自创建一条重复连接。</p>
     */
    public synchronized void connect() {
        if (closed) {
            throw new RpcException("已经关闭的 Netty RPC 客户端不能重新连接");
        }

        // Channel 存在并且仍可用时，直接复用已有连接。
        if (channel != null && channel.isActive()) {
            return;
        }

        try {
            /*
             * connect() 发起异步连接，sync() 让当前业务线程等待连接结果。
             * 连接成功后保存 Channel，后续所有请求都通过它发送。
             */
            channel = bootstrap.connect(host, port).sync().channel();
            LOGGER.info("Netty RPC 客户端已连接服务端：{}:{}", host, port);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RpcException("连接 RPC 服务端时线程被中断", exception);
        } catch (RuntimeException exception) {
            throw new RpcException(
                    "连接 RPC 服务端失败，地址：" + host + ":" + port,
                    exception
            );
        }
    }

    /** 判断当前客户端是否拥有一条仍可用的连接。 */
    public boolean isConnected() {
        Channel currentChannel = channel;
        return !closed && currentChannel != null && currentChannel.isActive();
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
        if (request == null) {
            throw new RpcException("RpcRequest 不能为空");
        }

        /*
         * 第一步：保证存在可用连接。
         * 第一次请求会建立 Channel；后续请求会复用已经存在的 Channel。
         */
        connect();

        long requestId = request.getRequestId();

        /*
         * 第二步：必须先登记 Future，再发送请求。
         * 如果先发送，响应速度非常快时，客户端 Handler 可能已经收到响应，
         * 但 Map 中还没有 Future，导致响应无法匹配。
         */
        CompletableFuture<RpcResponse> responseFuture =
                pendingRequestManager.register(requestId);

        Channel currentChannel = channel;
        if (currentChannel == null || !currentChannel.isActive()) {
            RpcException exception = new RpcException(
                    "发送 RPC 请求前连接已经失效，requestId：" + requestId
            );
            pendingRequestManager.completeExceptionally(requestId, exception);
            throw exception;
        }

        /*
         * 第三步：异步发送请求。
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
         * 第四步：业务线程限时等待响应。
         * Netty I/O 线程收到响应后，NettyRpcClientHandler 会调用
         * responseFuture.complete(response)，从而唤醒这里的业务线程。
         */
        try {
            return responseFuture.get(
                    REQUEST_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (TimeoutException exception) {
            RpcException timeoutException = new RpcException(
                    "等待 RPC 响应超时，requestId：" + requestId
                            + "，超时时间：" + REQUEST_TIMEOUT_SECONDS + " 秒",
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
     * 关闭当前连接和客户端网络线程组。
     *
     * <p>该方法实现 {@link AutoCloseable}，以后既可以主动调用，也可以放进
     * try-with-resources 中自动执行。重复关闭是安全的。</p>
     */
    @Override
    public void close() {
        Channel channelToClose;

        synchronized (this) {
            if (closed) {
                return;
            }

            closed = true;
            channelToClose = channel;
            channel = null;
        }

        if (channelToClose != null) {
            channelToClose.close().syncUninterruptibly();
        }

        /*
         * Channel 关闭通常会触发 clientHandler.channelInactive()，从而结束等待请求。
         * 这里再执行一次 failAll，用来覆盖“尚未建立 Channel 就关闭”等边界情况。
         */
        pendingRequestManager.failAll(
                new RpcException("Netty RPC 客户端已经关闭")
        );
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();

        LOGGER.info("Netty RPC 客户端已关闭，服务端地址：{}:{}", host, port);
    }
}
