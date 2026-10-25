package com.itheima.transport.server;

import com.itheima.common.RpcException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Provider 业务线程与有界任务队列的基础行为。 */
class ProviderBusinessExecutorTest {

    @Test
    void shouldExecuteTaskOnNamedBusinessThread() throws Exception {
        ProviderBusinessExecutor executor =
                new ProviderBusinessExecutor(1, 1);
        String callerThreadName = Thread.currentThread().getName();
        CompletableFuture<String> executionThreadName =
                new CompletableFuture<>();

        try {
            executor.execute(() -> executionThreadName.complete(
                    Thread.currentThread().getName()
            ));

            String actualThreadName = executionThreadName.get(
                    1,
                    TimeUnit.SECONDS
            );
            assertNotEquals(callerThreadName, actualThreadName);
            assertTrue(actualThreadName.startsWith(
                    "mini-rpc-provider-business-"
            ));
        } finally {
            executor.shutdown(true);
        }

        assertTrue(executor.isShutdown());
    }

    @Test
    void shouldRejectWhenWorkerAndBoundedQueueAreBothFull() throws Exception {
        ProviderBusinessExecutor executor =
                new ProviderBusinessExecutor(1, 1);
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstTask = new CountDownLatch(1);
        CountDownLatch secondTaskCompleted = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                firstTaskStarted.countDown();
                try {
                    releaseFirstTask.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstTaskStarted.await(1, TimeUnit.SECONDS));

            // 唯一工作线程正在执行第一个任务，第二个任务会占满容量为 1 的队列。
            executor.execute(secondTaskCompleted::countDown);

            assertThrows(
                    RejectedExecutionException.class,
                    () -> executor.execute(() -> {
                    })
            );

            ProviderBusinessExecutor.Statistics statistics =
                    executor.snapshot();
            assertEquals(1, statistics.configuredThreadCount());
            assertEquals(1, statistics.activeThreadCount());
            assertEquals(1, statistics.queuedTaskCount());
            assertEquals(0, statistics.remainingQueueCapacity());
            assertEquals(1, statistics.rejectedTaskCount());

            releaseFirstTask.countDown();
            assertTrue(secondTaskCompleted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirstTask.countDown();
            executor.shutdown(false);
        }
    }

    @Test
    void shouldRejectInvalidThreadAndQueueConfiguration() {
        RpcException invalidThreads = assertThrows(
                RpcException.class,
                () -> new ProviderBusinessExecutor(0, 10)
        );
        RpcException invalidQueue = assertThrows(
                RpcException.class,
                () -> new ProviderBusinessExecutor(2, 0)
        );

        assertTrue(invalidThreads.getMessage().contains("线程数"));
        assertTrue(invalidQueue.getMessage().contains("队列容量"));
    }
}
