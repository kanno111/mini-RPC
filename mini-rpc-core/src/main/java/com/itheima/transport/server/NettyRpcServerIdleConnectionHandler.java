package com.itheima.transport.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provider 侧负责清理静默客户端连接的处理器。
 *
 * <p>前面的 Netty {@code IdleStateHandler} 会为每条客户端 Channel 独立记录最后
 * 读取时间。长期没有收到普通请求或 PING 时会触发 READER_IDLE，本类只关闭这条
 * 客户端 Channel，不会关闭监听端口、workerGroup 或整个 Provider。</p>
 *
 * <p>处理器本身不保存某条连接的状态，因此可以由所有 Channel 共享；真正的
 * 计时状态保存在每条 Pipeline 自己的 IdleStateHandler 中。</p>
 */
@ChannelHandler.Sharable
final class NettyRpcServerIdleConnectionHandler
        extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NettyRpcServerIdleConnectionHandler.class);

    @Override
    public void userEventTriggered(
            ChannelHandlerContext context,
            Object event
    ) {
        if (event instanceof IdleStateEvent idleEvent
                && idleEvent.state() == IdleState.READER_IDLE) {
            LOGGER.warn(
                    "Provider 长时间未收到客户端数据，将关闭静默 Channel，客户端：{}",
                    context.channel().remoteAddress()
            );
            context.close();
            return;
        }

        // 只消费读空闲事件，其余事件继续交给后面的心跳或业务处理器。
        context.fireUserEventTriggered(event);
    }
}
