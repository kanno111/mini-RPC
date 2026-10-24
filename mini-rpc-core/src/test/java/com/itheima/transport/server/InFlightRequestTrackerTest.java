package com.itheima.transport.server;

import com.itheima.common.RpcException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Provider 排空期间的请求准入、计数归还和超时行为。 */
class InFlightRequestTrackerTest {

    @Test
    void shouldRejectNewRequestsAndWaitForStartedRequest() throws Exception {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        assertTrue(tracker.tryStartRequest());
        assertEquals(1, tracker.beginDraining());
        assertFalse(tracker.tryStartRequest());

        /*
         * 等待动作放到另一个线程，模拟 Spring 关闭线程正在等待，而 Netty worker
         * 随后完成响应写入。无论等待线程先运行还是 finishRequest() 先运行，结果
         * 都应该是计数归零并返回 true。
         */
        CompletableFuture<Boolean> drained = CompletableFuture.supplyAsync(
                () -> tracker.awaitCompletion(Duration.ofSeconds(1))
        );
        tracker.finishRequest();

        assertTrue(drained.get(1, TimeUnit.SECONDS));
        assertEquals(0, tracker.getInFlightRequestCount());
    }

    @Test
    void shouldReturnFalseWhenDrainTimeoutExpires() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();
        assertTrue(tracker.tryStartRequest());
        tracker.beginDraining();

        assertFalse(tracker.awaitCompletion(Duration.ofMillis(20)));
        assertEquals(1, tracker.getInFlightRequestCount());

        // 测试结束前归还计数，保持测试对象状态完整。
        tracker.finishRequest();
    }

    @Test
    void shouldRejectTimeoutShorterThanOneMillisecond() {
        InFlightRequestTracker tracker = new InFlightRequestTracker();

        RpcException exception = assertThrows(
                RpcException.class,
                () -> tracker.awaitCompletion(Duration.ofNanos(999_999))
        );

        assertTrue(exception.getMessage().contains("不能小于 1 毫秒"));
    }
}
