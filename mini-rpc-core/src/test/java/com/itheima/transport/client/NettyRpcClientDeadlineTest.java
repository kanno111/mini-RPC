package com.itheima.transport.client;

import com.itheima.common.RpcRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证本次有效超时能够稳定转换成请求的绝对 deadline。 */
class NettyRpcClientDeadlineTest {

    @Test
    void shouldSetDeadlineFromEffectiveRequestTimeout() {
        RpcRequest request = new RpcRequest();

        NettyRpcClient.applyDeadline(request, 750, 1_000);

        assertEquals(1_750, request.getDeadlineEpochMillis());
    }

    @Test
    void shouldKeepEarlierDeadlineFromUpstreamCall() {
        RpcRequest request = new RpcRequest();
        request.setDeadlineEpochMillis(1_500);

        NettyRpcClient.applyDeadline(request, 750, 1_000);

        assertEquals(1_500, request.getDeadlineEpochMillis());
    }

    @Test
    void shouldShortenLaterDeadlineToLocalTimeout() {
        RpcRequest request = new RpcRequest();
        request.setDeadlineEpochMillis(5_000);

        NettyRpcClient.applyDeadline(request, 750, 1_000);

        assertEquals(1_750, request.getDeadlineEpochMillis());
    }

    @Test
    void shouldSaturateWhenDeadlineAdditionWouldOverflow() {
        RpcRequest request = new RpcRequest();

        NettyRpcClient.applyDeadline(
                request,
                100,
                Long.MAX_VALUE - 50
        );

        assertEquals(Long.MAX_VALUE, request.getDeadlineEpochMillis());
    }
}
