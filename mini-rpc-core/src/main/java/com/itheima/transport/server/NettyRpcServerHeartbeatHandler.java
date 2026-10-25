package com.itheima.transport.server;

import com.itheima.common.RpcHeartbeat;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider 侧的轻量心跳处理器。
 *
 * <p>它位于 {@link NettyRpcServerHandler} 前面，只接收 {@link RpcHeartbeat}。
 * 普通 {@code RpcRequest} 会自动继续向后传播；PING 则直接在当前 Channel 的
 * EventLoop 中回复 PONG，不进入业务线程池、服务表或在途业务请求计数。</p>
 */
@ChannelHandler.Sharable
final class NettyRpcServerHeartbeatHandler
        extends SimpleChannelInboundHandler<RpcHeartbeat> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NettyRpcServerHeartbeatHandler.class);

    @Override
    protected void channelRead0(
            ChannelHandlerContext context,
            RpcHeartbeat heartbeat
    ) {
        if (heartbeat.getHeartbeatType() == RpcHeartbeat.TYPE_PING) {
            /*
             * PONG 沿用 PING 的 requestId，便于 Consumer 在后续自动心跳阶段
             * 关联一次探测。writeAndFlush 会反向经过 RpcEncoder 后写入网络。
             */
            context.writeAndFlush(RpcHeartbeat.pong(
                    heartbeat.getRequestId(),
                    System.currentTimeMillis()
            ));
            return;
        }

        // Provider 当前不会主动发 PING，因此收到 PONG 只消费，不再回复，避免循环。
        LOGGER.debug(
                "Provider 收到无需处理的 PONG，requestId：{}",
                heartbeat.getRequestId()
        );
    }
}
