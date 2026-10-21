package com.itheima.transport.server;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.provider.RpcRequestHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty 服务端的 RPC 业务处理器。
 *
 * <p>前面的解码器已经把网络字节还原成 {@link RpcRequest}，本类不再处理字节，
 * 只负责把请求交给 {@link RpcRequestHandler} 执行，并把得到的
 * {@link RpcResponse} 写回客户端。</p>
 *
 * <pre>
 * 网络字节
 *    ↓
 * RpcDecoder：切出完整协议帧，并把正文还原成 RpcRequest
 *    ↓
 * NettyRpcServerHandler：执行请求，得到 RpcResponse
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

    /** 负责真正的服务查找、反射调用和响应封装。 */
    private final RpcRequestHandler requestHandler;

    /**
     * 创建 Netty 服务端业务处理器。
     *
     * @param requestHandler 已经配置好服务注册表的请求执行器，不能为 {@code null}
     */
    public NettyRpcServerHandler(RpcRequestHandler requestHandler) {
        if (requestHandler == null) {
            throw new RpcException("RpcRequestHandler 不能为空");
        }
        this.requestHandler = requestHandler;
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
        // 第一步：复用 M1 已有逻辑，查找服务并反射执行目标方法。
        RpcResponse response = requestHandler.handle(request);

        /*
         * 第二步：把响应写回当前客户端。
         * writeAndFlush 并不是直接把 Java 对象塞进网络：响应会沿着 Pipeline 的
         * 出站方向经过 RpcEncoder，编码成 [20 字节协议头 + 正文] 后才真正发送。
         *
         * write：把消息写入 Netty 的发送缓冲区。
         * flush：立刻要求 Netty 尝试把缓冲区里的消息发送出去。
         */
        context.writeAndFlush(response);
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
