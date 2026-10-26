package io.github.kaano.minirpc.transport.server;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.provider.RpcRequestHandler;
import io.github.kaano.minirpc.provider.filter.DefaultProviderFilterChain;
import io.github.kaano.minirpc.provider.filter.ProviderDeadlineFilter;
import io.github.kaano.minirpc.provider.filter.ProviderFilterChain;
import io.github.kaano.minirpc.provider.filter.ProviderFilter;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Netty 服务端的 RPC 业务处理器。
 *
 * <p>前面的解码器已经把网络字节还原成 {@link RpcRequest}，本类不再处理字节，
 * 只负责把请求交给 Provider Filter 调用链执行，并把得到的
 * {@link RpcResponse} 写回客户端。</p>
 *
 * <pre>
 * 网络字节
 *    ↓
 * RpcDecoder：切出完整协议帧，并把正文还原成 RpcRequest
 *    ↓
 * NettyRpcServerHandler：把请求提交给 Provider 业务线程池
 *    ↓
 * Provider 业务线程：deadline Filter → RpcRequestHandler，得到 RpcResponse
 *    ↓
 * RpcEncoder：RpcResponse 变成网络字节
 * </pre>
 *
 * <p>{@link ChannelHandler.Sharable} 表示同一个处理器实例可以安全地放入多个
 * Channel 的 Pipeline。这个类没有保存某个客户端的临时状态，所以可以共享。</p>
 */
@ChannelHandler.Sharable
public class NettyRpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NettyRpcServerHandler.class);

    /** 业务线程执行的根调用链：deadline Filter → 服务查找与方法调用。 */
    private final ProviderFilterChain requestChain;

    /** 统计已进入业务执行、但响应写入尚未完成的请求。 */
    private final InFlightRequestTracker requestTracker;

    /** 把耗时服务方法从 Netty worker EventLoop 转移到独立业务线程。 */
    private final Executor businessExecutor;

    /**
     * 让 NettyRpcServer、Handler 和业务线程池共享同一条请求生命周期。
     * 测试可以传入同步执行器或可控执行器，生产环境传入有界业务线程池。
     */
    NettyRpcServerHandler(
            RpcRequestHandler requestHandler,
            InFlightRequestTracker requestTracker,
            Executor businessExecutor
    ) {
        this(requestHandler, requestTracker, businessExecutor, List.of());
    }

    /**
     * 使用用户 Filter 创建 Handler。内置 deadline Filter 固定排在首位，保证
     * 过期请求不会先进入日志、鉴权等用户扩展，更不会到达业务方法。
     */
    NettyRpcServerHandler(
            RpcRequestHandler requestHandler,
            InFlightRequestTracker requestTracker,
            Executor businessExecutor,
            List<ProviderFilter> userFilters
    ) {
        if (requestHandler == null) {
            throw new RpcException("RpcRequestHandler 不能为空");
        }
        this.requestChain = new DefaultProviderFilterChain(
                createProviderFilters(userFilters),
                requestHandler
        );
        if (requestTracker == null) {
            throw new RpcException("InFlightRequestTracker 不能为空");
        }
        if (businessExecutor == null) {
            throw new RpcException("Provider 业务执行器不能为空");
        }
        this.requestTracker = requestTracker;
        this.businessExecutor = businessExecutor;
    }

    /** 校验用户列表并在其前面安装不可移除的内置 deadline Filter。 */
    private static List<ProviderFilter> createProviderFilters(
            List<ProviderFilter> userFilters
    ) {
        if (userFilters == null) {
            throw new RpcException("Provider Filter 列表不能为空");
        }
        if (userFilters.stream().anyMatch(filter -> filter == null)) {
            throw new RpcException("Provider Filter 列表不能包含 null");
        }

        List<ProviderFilter> filters = new ArrayList<>(userFilters.size() + 1);
        filters.add(new ProviderDeadlineFilter());
        filters.addAll(userFilters);
        return List.copyOf(filters);
    }

    /**
     * 处理 Pipeline 传来的一个完整 RPC 请求。
     *
     * <p>方法名 {@code channelRead0} 可以理解成“读取一条类型已经确定的消息”。
     * 因为父类的泛型写的是 {@code RpcRequest}，所以这里收到的已经不是
     * {@code ByteBuf}，而是解码完成的请求对象。</p>
     *
     * @param context 当前 Channel 的处理上下文，可用于向客户端写数据
     * @param request 解码器输出的 RPC 请求
     */
    @Override
    protected void channelRead0(ChannelHandlerContext context, RpcRequest request) {
        if (!requestTracker.tryStartRequest()) {
            /*
             * 连接可能在 Provider 摘流前已经建立。开始排空后，这些旧连接仍可能
             * 发来请求；这里返回失败响应，而不是继续执行新的业务方法。
             */
            context.writeAndFlush(RpcResponse.failure(
                    request.getRequestId(),
                    new RpcException("RPC Provider 正在关闭，拒绝新的请求")
            ));
            return;
        }

        /*
         * channelRead0 运行在 Netty worker EventLoop。这里只提交任务便立即返回，
         * 让 EventLoop 能继续处理同一线程管理的其他 Channel，不能在这里直接
         * 调用 requestChain.proceed(request)。
         */
        try {
            businessExecutor.execute(
                    () -> executeAndRespond(context, request)
            );
        } catch (RejectedExecutionException exception) {
            /*
             * 有界线程池达到容量或已经关闭时，AbortPolicy 会走到这里。
             * 明确返回失败比 CallerRunsPolicy、静默丢弃或无限排队更安全。
             */
            logRejectedRequest(request.getRequestId());
            writeTrackedResponse(
                    context,
                    RpcResponse.failure(
                            request.getRequestId(),
                            new RpcException(
                                    "RPC Provider 业务线程池繁忙，拒绝新的请求",
                                    exception
                            )
                    )
            );
        }
    }

    /** 记录过载现场；生产执行器可以提供比通用 Executor 更完整的线程池数据。 */
    private void logRejectedRequest(long requestId) {
        if (businessExecutor instanceof ProviderBusinessExecutor executor) {
            ProviderBusinessExecutor.Statistics statistics =
                    executor.snapshot();
            LOGGER.warn(
                    "Provider 业务任务被拒绝，requestId：{}，线程数：{}，活跃：{}，"
                            + "排队：{}，队列剩余：{}，累计完成：{}，累计拒绝：{}，"
                            + "已关闭：{}",
                    requestId,
                    statistics.configuredThreadCount(),
                    statistics.activeThreadCount(),
                    statistics.queuedTaskCount(),
                    statistics.remainingQueueCapacity(),
                    statistics.completedTaskCount(),
                    statistics.rejectedTaskCount(),
                    statistics.shutdown()
            );
            return;
        }

        LOGGER.warn("Provider 业务任务被拒绝，requestId：{}", requestId);
    }

    /** 在 Provider 业务线程中执行 Filter 和服务方法，再安排响应写回。 */
    private void executeAndRespond(
            ChannelHandlerContext context,
            RpcRequest request
    ) {
        RpcResponse response;
        try {
            response = requestChain.proceed(request);
        } catch (RuntimeException exception) {
            /*
             * 该方法现在运行在普通业务线程中，异常不能再依赖 Netty Pipeline
             * 自动捕获。因此把 Filter 或请求执行器的意外异常也转换成失败响应，确保
             * Consumer 得到结果，并让在途计数最终能够归零。
             */
            response = RpcResponse.failure(request.getRequestId(), exception);
        }

        writeTrackedResponse(context, response);
    }

    /**
     * 写回一个已经计入在途数量的响应，并在写操作完成后归还计数。
     *
     * <p>{@link ChannelHandlerContext#writeAndFlush(Object)} 可以从业务线程调用；
     * Netty 会把真正的编码和 Socket 写入调度回该 Channel 所属的 EventLoop。</p>
     */
    private void writeTrackedResponse(
            ChannelHandlerContext context,
            RpcResponse response
    ) {
        /*
         * 把响应写回当前客户端。
         * writeAndFlush 并不是直接把 Java 对象塞进网络：响应会沿着 Pipeline 的
         * 出站方向经过 RpcEncoder，编码成 [20 字节协议头 + 正文] 后才真正发送。
         *
         * write：把消息写入 Netty 的发送缓冲区。
         * flush：立刻要求 Netty 尝试把缓冲区里的消息发送出去。
         */
        try {
            context.writeAndFlush(response).addListener(
                    ignoredFuture -> requestTracker.finishRequest()
            );
        } catch (RuntimeException exception) {
            // writeAndFlush 同步失败时不会触发 Future listener，需要在这里归还计数。
            requestTracker.finishRequest();
            LOGGER.error("提交 RPC 响应写入失败，将关闭当前连接", exception);
            context.close();
        }
    }

    /**
     * 处理当前 Channel 在读取、解码或业务执行过程中继续传播过来的异常。
     *
     * <p>连接发生异常后通常已经不适合继续使用，因此记录原因并关闭连接。
     * 关闭的是当前客户端连接，不会关闭整个 RPC 服务端。</p>
     *
     * @param context 当前 Channel 的处理上下文
     * @param cause   异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.error(
                "Netty RPC 服务端处理连接失败，客户端：{}",
                context.channel().remoteAddress(),
                cause
        );
        context.close();
    }
}
