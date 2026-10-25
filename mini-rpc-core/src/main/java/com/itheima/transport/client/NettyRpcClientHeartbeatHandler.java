package com.itheima.transport.client;

import com.itheima.common.RpcHeartbeat;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer 侧的心跳与连接空闲事件处理器。
 *
 * <p>该处理器在普通 {@link NettyRpcClientHandler} 前消费心跳消息。PONG 不属于
 * 业务响应，因此不会进入 {@link PendingRequestManager}，也不会出现“找不到普通
 * RPC Future”的误报警。</p>
 *
 * <p>前面的 Netty {@code IdleStateHandler} 会把一段时间没有写数据或没有读到数据
 * 转换成 {@link IdleStateEvent}：写空闲时本类发送 PING；读空闲时关闭 Channel。
 * Channel 关闭后，已有 {@link NettyRpcClientHandler#channelInactive} 会结束等待请求，
 * closeFuture listener 会删除连接缓存，下一次业务调用再按需连接。</p>
 */
@ChannelHandler.Sharable
final class NettyRpcClientHeartbeatHandler
        extends SimpleChannelInboundHandler<RpcHeartbeat> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NettyRpcClientHeartbeatHandler.class);

    /**
     * 为心跳生成正数 requestId。
     *
     * <p>同一个 Handler 会被多个 Channel 共享，因此使用 AtomicLong 保证不同
     * EventLoop 并发触发写空闲时仍能安全递增。心跳和业务请求由协议消息类型区分，
     * 不会进入同一张等待请求表，所以二者的数字偶尔相同也不会互相匹配。</p>
     */
    private final AtomicLong heartbeatRequestId = new AtomicLong();

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            RpcHeartbeat heartbeat
    ) {
        if (heartbeat.getHeartbeatType() == RpcHeartbeat.TYPE_PONG) {
            LOGGER.debug(
                    "Consumer 收到 PONG，requestId：{}",
                    heartbeat.getRequestId()
            );
            return;
        }

        // 当前心跳方向固定为 Consumer PING、Provider PONG，意外 PING 只消费不回环。
        LOGGER.warn(
                "Consumer 收到方向异常的 PING，requestId：{}",
                heartbeat.getRequestId()
        );
    }

    /**
     * 处理 IdleStateHandler 产生的空闲事件。
     *
     * <p>WRITER_IDLE 只说明连接暂时没有业务流量，不代表故障，因此发送轻量 PING；
     * READER_IDLE 表示在更长时间内连 PONG、普通响应等任何数据都没有收到，此时
     * 关闭连接，让现有连接生命周期代码统一完成清理。</p>
     */
    @Override
    public void userEventTriggered(
            ChannelHandlerContext context,
            Object event
    ) {
        if (!(event instanceof IdleStateEvent idleEvent)) {
            // 不是本类负责的事件，必须继续传播，不能破坏其他 Netty 扩展点。
            context.fireUserEventTriggered(event);
            return;
        }

        if (idleEvent.state() == IdleState.WRITER_IDLE) {
            long requestId = nextHeartbeatRequestId();
            RpcHeartbeat ping = RpcHeartbeat.ping(
                    requestId,
                    System.currentTimeMillis()
            );

            LOGGER.debug(
                    "Consumer Channel 写空闲，发送 PING，requestId：{}，Provider：{}",
                    requestId,
                    context.channel().remoteAddress()
            );

            /*
             * 从当前 Handler 发起出站写入时，消息会反向经过前面的 RpcEncoder。
             * 如果写入本身失败，立即关闭 Channel；后续仍由 channelInactive 和
             * closeFuture listener 负责结束等待请求、删除连接缓存。
             */
            context.writeAndFlush(ping)
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            return;
        }

        if (idleEvent.state() == IdleState.READER_IDLE) {
            LOGGER.warn(
                    "Consumer Channel 长时间未收到任何数据，将关闭失效连接，Provider：{}",
                    context.channel().remoteAddress()
            );
            context.close();
            return;
        }

        // 当前没有配置 ALL_IDLE，但仍保留事件传播语义，方便以后扩展。
        context.fireUserEventTriggered(event);
    }

    /** 原子生成下一个正数心跳编号，并在极端溢出时从 1 重新开始。 */
    private long nextHeartbeatRequestId() {
        return heartbeatRequestId.updateAndGet(
                current -> current == Long.MAX_VALUE ? 1L : current + 1L
        );
    }
}
