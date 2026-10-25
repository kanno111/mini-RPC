package io.github.kaano.minirpc.transport.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证 Provider 只关闭读空闲客户端，并保留其他用户事件的传播语义。 */
class NettyRpcServerIdleConnectionHandlerTest {

    @Test
    void shouldCloseOnlyCurrentChannelWhenReaderBecomesIdle() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerIdleConnectionHandler()
        );

        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.READER_IDLE_STATE_EVENT
            );

            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldPropagateUnrelatedUserEvent() {
        AtomicReference<Object> forwardedEvent = new AtomicReference<>();
        ChannelInboundHandlerAdapter eventRecorder =
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(
                            ChannelHandlerContext context,
                            Object event
                    ) {
                        forwardedEvent.set(event);
                    }
                };
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerIdleConnectionHandler(),
                eventRecorder
        );
        Object customEvent = new Object();

        try {
            channel.pipeline().fireUserEventTriggered(customEvent);
            assertSame(customEvent, forwardedEvent.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
