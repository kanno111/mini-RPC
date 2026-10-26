package io.github.kaano.minirpc.transport.server;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.provider.RpcRequestHandler;
import io.github.kaano.minirpc.provider.filter.ProviderFilter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                new NettyRpcServerHandler(
                        requestHandler,
                        tracker,
                        Runnable::run
                )
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
                new NettyRpcServerHandler(
                        requestHandler,
                        tracker,
                        Runnable::run
                )
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

    @Test
    void shouldReturnFailureAndReleaseCountWhenExecutorRejectsRequest() {
        RpcRequest request = RpcRequest.builder()
                .requestId(303L)
                .build();
        RpcRequestHandler requestHandler = mock(RpcRequestHandler.class);
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(
                        requestHandler,
                        tracker,
                        ignoredTask -> {
                            throw new RejectedExecutionException("full");
                        }
                )
        );

        try {
            channel.writeInbound(request);

            RpcResponse response = channel.readOutbound();
            assertEquals(303L, response.getRequestId());
            assertEquals(RpcResponse.FAILURE_CODE, response.getCode());
            assertEquals(0, tracker.getInFlightRequestCount());
            verifyNoInteractions(requestHandler);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldSubmitBusinessWorkInsteadOfRunningItInChannelRead() {
        RpcRequest request = RpcRequest.builder()
                .requestId(404L)
                .build();
        RpcResponse expectedResponse = RpcResponse.success(404L, "async");
        RpcRequestHandler requestHandler = mock(RpcRequestHandler.class);
        when(requestHandler.handle(request)).thenReturn(expectedResponse);
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(
                        requestHandler,
                        tracker,
                        task -> {
                            if (!submittedTask.compareAndSet(null, task)) {
                                throw new AssertionError("只应提交一个业务任务");
                            }
                        }
                )
        );

        try {
            channel.writeInbound(request);

            assertNotNull(submittedTask.get());
            assertNull(channel.readOutbound(),
                    "任务尚未执行时不应提前产生响应");
            assertEquals(1, tracker.getInFlightRequestCount());
            verifyNoInteractions(requestHandler);

            submittedTask.get().run();
            channel.runPendingTasks();

            assertEquals(expectedResponse, channel.readOutbound());
            assertEquals(0, tracker.getInFlightRequestCount());
            verify(requestHandler).handle(request);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldRejectExpiredRequestInBusinessTaskBeforeInvokingService() {
        RpcRequest request = RpcRequest.builder()
                .requestId(505L)
                .deadlineEpochMillis(System.currentTimeMillis() - 1)
                .build();
        RpcRequestHandler requestHandler = mock(RpcRequestHandler.class);
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        AtomicInteger userFilterCalls = new AtomicInteger();
        ProviderFilter userFilter = (currentRequest, chain) -> {
            userFilterCalls.incrementAndGet();
            return chain.proceed(currentRequest);
        };
        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyRpcServerHandler(
                        requestHandler,
                        tracker,
                        submittedTask::set,
                        List.of(userFilter)
                )
        );

        try {
            channel.writeInbound(request);

            assertNotNull(submittedTask.get());
            assertNull(channel.readOutbound(),
                    "进入业务线程前，EventLoop 不应提前执行 deadline Filter");
            assertEquals(1, tracker.getInFlightRequestCount());
            verifyNoInteractions(requestHandler);

            submittedTask.get().run();
            channel.runPendingTasks();

            RpcResponse response = channel.readOutbound();
            assertEquals(505L, response.getRequestId());
            assertEquals(RpcResponse.FAILURE_CODE, response.getCode());
            assertEquals(0, tracker.getInFlightRequestCount());
            assertEquals(0, userFilterCalls.get(),
                    "内置 deadline Filter 必须在用户 Filter 前短路");
            verifyNoInteractions(requestHandler);
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
