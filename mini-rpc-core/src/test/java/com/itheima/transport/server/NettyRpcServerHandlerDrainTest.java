package com.itheima.transport.server;

import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.provider.RpcRequestHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 Netty Handler 把请求执行和响应写入正确接入排空计数。 */
class NettyRpcServerHandlerDrainTest {

    @Test
    void shouldReleaseCountAfterResponseWriteCompletes() {
        RpcRequest request = RpcRequest.builder()
                .requestId(101L)
                .build();
        RpcResponse expectedResponse = RpcResponse.success(101L, "ok");
        RpcRequestHandler requestHandler = mock(RpcRequestHandler.class);
        when(requestHandler.handle(request)).thenReturn(expectedResponse);
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(requestHandler, tracker)
        );

        try {
            channel.writeInbound(request);

            assertEquals(expectedResponse, channel.readOutbound());
            assertEquals(0, tracker.getInFlightRequestCount());
            verify(requestHandler).handle(request);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldRejectRequestWithoutExecutingBusinessAfterDrainStarts() {
        RpcRequest request = RpcRequest.builder()
                .requestId(202L)
                .build();
        RpcRequestHandler requestHandler = mock(RpcRequestHandler.class);
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        tracker.beginDraining();
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(requestHandler, tracker)
        );

        try {
            channel.writeInbound(request);

            RpcResponse response = channel.readOutbound();
            assertEquals(202L, response.getRequestId());
            assertEquals(RpcResponse.FAILURE_CODE, response.getCode());
            assertEquals(0, tracker.getInFlightRequestCount());
            verifyNoInteractions(requestHandler);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
