package com.itheima.transport.server;

import com.itheima.common.RpcException;
import com.itheima.provider.RpcRequestHandler;
import com.itheima.serialize.Serializer;
import com.itheima.transport.codec.RpcDecoder;
import com.itheima.transport.codec.RpcEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Netty 的 RPC 服务端。
 *
 * <p>这个类负责监听端口、接收客户端连接，并为每条连接组装处理链。
 * 真正的服务查找和方法调用仍然交给 {@link RpcRequestHandler}，避免网络层
 * 和业务执行逻辑耦合在一起。</p>
 *
 * <pre>
 * bossGroup：接收新连接
 *      ↓
 * workerGroup：处理连接上的读写事件
 *      ↓
 * 每条连接自己的 Pipeline
 *      ↓
 * RpcDecoder → RpcEncoder → NettyRpcServerHandler
 * </pre>
 */
public class NettyRpcServer implements RpcServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(NettyRpcServer.class);

    /** 服务端要监听的端口。 */
    private final int port;

    /** 请求和响应共用的正文序列化器。 */
    private final Serializer serializer;

    /** 可以被所有客户端连接共享的服务端业务处理器。 */
    private final NettyRpcServerHandler serverHandler;

    /**
     * boss 线程组只负责接受新连接，不执行具体请求。
     * 一个监听端口只需要一个 boss 线程。
     */
    private EventLoopGroup bossGroup;

    /** worker 线程组负责已经建立连接上的读取、解码、处理和写回。 */
    private EventLoopGroup workerGroup;

    /** 代表服务端监听端口的 Channel，不是某个具体客户端的 Channel。 */
    private volatile Channel serverChannel;

    /** volatile 让启动线程和关闭钩子线程都能看到最新状态。 */
    private volatile boolean running;

    /** 一个服务端实例停止后不再允许重新启动。 */
    private volatile boolean stopped;

    /**
     * 创建 Netty RPC 服务端。
     *
     * @param port           监听端口，范围是 1 到 65535
     * @param serializer     请求和响应使用的序列化器
     * @param requestHandler 负责查找服务并执行方法的请求处理器
     */
    public NettyRpcServer(
            int port,
            Serializer serializer,
            RpcRequestHandler requestHandler
    ) {
        if (port < 1 || port > 65535) {
            throw new RpcException("RPC 服务端端口必须在 1 到 65535 之间：" + port);
        }
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }
        if (requestHandler == null) {
            throw new RpcException("RpcRequestHandler 不能为空");
        }

        this.port = port;
        this.serializer = serializer;
        this.serverHandler = new NettyRpcServerHandler(requestHandler);
    }

    /**
     * 启动服务端、绑定端口，并阻塞等待服务端 Channel 关闭。
     *
     * <p>{@code start()} 阻塞当前线程的行为与 M1 的 SocketRpcServer 保持一致，
     * 因此 ProviderMain 的启动方式以后不需要改变。</p>
     */
    @Override
    public void start(Runnable onStarted) {
        if (onStarted == null) {
            throw new RpcException("Netty RPC 服务端启动回调不能为空");
        }

        try {
            synchronized (this) {
                if (running) {
                    throw new RpcException("Netty RPC 服务端已经启动");
                }
                if (stopped) {
                    throw new RpcException("已经停止的 Netty RPC 服务端不能再次启动");
                }

                /*
                 * Netty 4.2 把“事件循环线程组”和“具体 I/O 实现”拆开配置。
                 * NioIoHandler.newFactory() 表示底层使用 Java NIO 的 Selector。
                 */
                // boss 只接收连接，因此固定使用一个线程。
                bossGroup = new MultiThreadIoEventLoopGroup(
                        1,
                        NioIoHandler.newFactory()
                );
                // worker 不指定数量，让 Netty 按运行环境选择合适的线程数。
                workerGroup = new MultiThreadIoEventLoopGroup(
                        NioIoHandler.newFactory()
                );

                ServerBootstrap bootstrap = createServerBootstrap();

                /*
                 * bind(port) 发起异步端口绑定；sync() 等待绑定完成。
                 * 绑定成功后得到的 Channel 代表服务端监听端口。
                 */
                serverChannel = bootstrap.bind(port).sync().channel();
                running = true;
            }

            LOGGER.info("Netty RPC 服务端已启动，监听端口：{}", port);

            /*
             * 此时 bind(...).sync() 已经成功返回，说明操作系统端口已经真正绑定。
             * RpcProvider 会在这个回调中把服务地址发布到 ZooKeeper。
             *
             * 如果发布失败，回调会抛出异常；下面的 finally 仍会执行 stop()，
             * 因此不会留下一个“端口开着但 Provider 启动失败”的残缺实例。
             */
            onStarted.run();

            /*
             * closeFuture() 只有在服务端 Channel 被关闭时才会完成。
             * 在这里等待，可以防止 ProviderMain 的主线程直接运行结束。
             */
            serverChannel.closeFuture().sync();
        } catch (InterruptedException exception) {
            // 恢复中断标记，让更上层代码知道当前线程曾经被要求停止。
            Thread.currentThread().interrupt();
            throw new RpcException("启动 Netty RPC 服务端时线程被中断", exception);
        } catch (RpcException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RpcException("启动 Netty RPC 服务端失败，端口：" + port, exception);
        } finally {
            stop();
        }
    }

    /**
     * 创建并配置服务端启动器。
     *
     * <p>ServerBootstrap 可以理解为 Netty 服务端的“装配说明书”：它记录使用
     * 哪些线程、哪种 Channel，以及每个客户端连接应当安装哪些 Handler。</p>
     */
    private ServerBootstrap createServerBootstrap() {
        ServerBootstrap bootstrap = new ServerBootstrap();

        bootstrap
                // 第一个线程组接收连接，第二个线程组处理连接上的读写事件。
                .group(bossGroup, workerGroup)
                // 使用 Java NIO 实现的服务端 Channel。
                .channel(NioServerSocketChannel.class)
                // 等待服务端接受的连接队列最多暂存 128 个连接。
                .option(ChannelOption.SO_BACKLOG, 128)
                // 小消息尽快发送，适合 RPC 请求和响应。
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 每当接受一条客户端连接，就为它初始化一条 Pipeline。
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        configurePipeline(channel);
                    }
                });

        return bootstrap;
    }

    /**
     * 为一条新建立的客户端连接安装处理器。
     *
     * <p>入站事件按照添加顺序向后传播；出站事件按照相反方向传播。
     * 因此业务 Handler 写回 RpcResponse 时，会反向经过放在它前面的
     * {@link RpcEncoder}。</p>
     */
    private void configurePipeline(SocketChannel channel) {
        channel.pipeline()
                // 入站：等待完整的 M3 协议帧，并把正文反序列化成 RpcRequest。
                .addLast(new RpcDecoder())
                // 出站：把 RpcResponse 编码成“20 字节协议头 + 正文”。
                .addLast(new RpcEncoder(serializer))
                // 入站：执行请求并写回响应。
                .addLast(serverHandler);
    }

    /**
     * 停止监听，并优雅关闭 boss、worker 线程组。
     *
     * <p>方法允许重复调用。关闭监听 Channel 后，阻塞在 start() 中的
     * {@code closeFuture().sync()} 会被唤醒。</p>
     */
    @Override
    public void stop() {
        Channel channelToClose;
        EventLoopGroup bossToShutdown;
        EventLoopGroup workerToShutdown;

        synchronized (this) {
            if (stopped) {
                return;
            }

            running = false;
            stopped = true;

            // 先复制引用，再到同步代码块外等待关闭，避免长时间占用对象锁。
            channelToClose = serverChannel;
            bossToShutdown = bossGroup;
            workerToShutdown = workerGroup;
        }

        if (channelToClose != null) {
            channelToClose.close().syncUninterruptibly();
        }
        if (bossToShutdown != null) {
            bossToShutdown.shutdownGracefully().syncUninterruptibly();
        }
        if (workerToShutdown != null) {
            workerToShutdown.shutdownGracefully().syncUninterruptibly();
        }

        LOGGER.info("Netty RPC 服务端已停止，端口：{}", port);
    }
}
