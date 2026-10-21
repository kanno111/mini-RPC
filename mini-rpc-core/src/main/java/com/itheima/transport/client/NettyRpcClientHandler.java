package com.itheima.transport.client;

import com.itheima.common.RpcException;
import com.itheima.common.RpcResponse;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty 客户端的响应处理器。
 *
 * <p>客户端 Pipeline 前面的解码器已经把网络字节还原成 {@link RpcResponse}。
 * 本类只负责把响应交给 {@link PendingRequestManager}，由它根据 requestId
 * 找到当初等待该响应的 {@code CompletableFuture}。</p>
 *
 * <pre>
 * 网络字节
 *    ↓
 * RpcDecoder：取得完整协议帧，并把正文还原成 RpcResponse
 *    ↓
 * NettyRpcClientHandler：按 requestId 完成对应 Future
 * </pre>
 */
@ChannelHandler.Sharable
public class NettyRpcClientHandler extends SimpleChannelInboundHandler<RpcResponse> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NettyRpcClientHandler.class);

    /** 保存所有已经发送、但还没有收到响应的请求。 */
    private final PendingRequestManager pendingRequestManager;

    /**
     * 创建客户端响应处理器。
     *
     * @param pendingRequestManager 请求与 Future 的匹配管理器，不能为 {@code null}
     */
    public NettyRpcClientHandler(PendingRequestManager pendingRequestManager) {
        if (pendingRequestManager == null) {
            throw new RpcException("PendingRequestManager 不能为空");
        }
        this.pendingRequestManager = pendingRequestManager;
    }

    /**
     * 处理一条已经解码完成的 RPC 响应。
     *
     * <p>该方法运行在 Netty I/O 线程。{@code complete(response)} 会根据响应中的
     * requestId 找到 Future，并唤醒正在等待结果的业务线程。</p>
     *
     * @param context  当前客户端 Channel 的处理上下文，本方法暂时不需要使用
     * @param response 服务端返回的完整响应对象
     */
    @Override
    protected void channelRead0(ChannelHandlerContext context, RpcResponse response) {
        boolean completed = pendingRequestManager.complete(response);

        if (!completed) {
            /*
             * 没找到 Future 通常表示响应重复到达，或者客户端已经因超时、
             * 写入失败、连接关闭等原因结束并清理了原请求。
             */
            LOGGER.warn(
                    "收到无法匹配等待请求的 RPC 响应，requestId：{}",
                    response.getRequestId()
            );
        }
    }

    /**
     * 当前连接关闭后，结束所有仍在等待该连接响应的 Future。
     *
     * <p>如果不执行 failAll，业务线程可能仍然阻塞在 future.get()，但连接已经
     * 不可能再收到响应。处理完成后继续传播连接关闭事件，方便 Pipeline 中的
     * 其他入站 Handler（如果以后增加）也能收到通知。</p>
     */
    @Override
    public void channelInactive(ChannelHandlerContext context) {
        pendingRequestManager.failAll(
                new RpcException("Netty RPC 客户端连接已经关闭")
        );
        context.fireChannelInactive();
    }

    /**
     * 处理客户端 Channel 上继续传播过来的异常。
     *
     * <p>先让所有等待请求以异常结束，再关闭当前连接。关闭连接还会触发
     * {@link #channelInactive(ChannelHandlerContext)}；此时 Map 已经被清空，
     * 再次调用 failAll 不会重复完成 Future。</p>
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.error("Netty RPC 客户端连接发生异常", cause);
        pendingRequestManager.failAll(cause);
        context.close();
    }
}
