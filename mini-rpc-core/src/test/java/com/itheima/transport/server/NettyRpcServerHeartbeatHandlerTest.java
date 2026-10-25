package com.itheima.transport.server;

import com.itheima.common.RpcHeartbeat;
import com.itheima.provider.RpcRequestHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证 Provider 在 EventLoop 处理 PING，并直接返回同 requestId 的 PONG。 */
class NettyRpcServerHeartbeatHandlerTest {

    @Test
    void shouldReplyPongWithoutPropagatingPing() {
        RpcRequestHandler requestHandler = mock(RpcRequestHandler.class);
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHeartbeatHandler(),
                new NettyRpcServerHandler(
                        requestHandler,
                        tracker,
                        Runnable::run
                )
        );
        RpcHeartbeat ping = RpcHeartbeat.ping(801L, 1_700_000_000_003L);

        try {
            channel.writeInbound(ping);

            assertNull(channel.readInbound());
            RpcHeartbeat pong = channel.readOutbound();
            assertEquals(ping.getRequestId(), pong.getRequestId());
            assertEquals(RpcHeartbeat.TYPE_PONG, pong.getHeartbeatType());
            assertTrue(pong.getTimestampEpochMillis() > 0);
            assertEquals(0, tracker.getInFlightRequestCount());
            verifyNoInteractions(requestHandler);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
