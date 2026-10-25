package com.itheima.transport.client;

import com.itheima.common.RpcHeartbeat;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Consumer 心跳响应消费、空闲发送、失效关闭和事件传播。 */
class NettyRpcClientHeartbeatHandlerTest {

    @Test
    void shouldConsumePongWithoutPropagatingToPendingRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcClientHeartbeatHandler()
        );
        RpcHeartbeat pong = RpcHeartbeat.pong(
                901L,
                1_700_000_000_004L
        );

        try {
            channel.writeInbound(pong);
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldSendPingWhenWriterBecomesIdle() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcClientHeartbeatHandler()
        );

        try {
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.WRITER_IDLE_STATE_EVENT
            );
            RpcHeartbeat firstPing = channel.readOutbound();

            assertEquals(RpcHeartbeat.TYPE_PING, firstPing.getHeartbeatType());
            assertTrue(firstPing.getRequestId() > 0);
            assertTrue(firstPing.getTimestampEpochMillis() > 0);
            assertTrue(channel.isActive());

            // 第二次写空闲应产生新的编号，避免连续 PING 无法区分。
            channel.pipeline().fireUserEventTriggered(
                    IdleStateEvent.WRITER_IDLE_STATE_EVENT
            );
            RpcHeartbeat secondPing = channel.readOutbound();
            assertNotEquals(
                    firstPing.getRequestId(),
                    secondPing.getRequestId()
            );
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldCloseChannelWhenReaderBecomesIdle() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcClientHeartbeatHandler()
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
                new NettyRpcClientHeartbeatHandler(),
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
