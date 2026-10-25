package io.github.kaano.minirpc.transport.server;

import io.github.kaano.minirpc.common.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provider 专门执行 {@code @RpcService} 方法的业务线程池。
 *
 * <p>Netty worker EventLoop 应当尽快完成读取、解码和任务分发，不能直接承担
 * 数据库查询、远程接口等待或复杂计算。这个执行器把可能耗时的业务调用放到
 * 独立线程中，避免一个慢请求阻塞同一 EventLoop 管理的其他 Channel。</p>
 *
 * <p>这里主动使用有界队列，而不是 {@code Executors.newFixedThreadPool()} 的
 * 无界队列。即使后续流量持续超过处理能力，待执行任务数量也不会无限增长。
 * Netty 服务端既可以使用框架默认值，也可以接收 Starter 传入的线程数和容量。</p>
 */
final class ProviderBusinessExecutor implements Executor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProviderBusinessExecutor.class);

    /** 用于生成容易在日志和线程转储中识别的线程名称。 */
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ThreadPoolExecutor executor;

    /** 包含线程池关闭期间的拒绝，用于过载日志和后续监控指标。 */
    private final AtomicLong rejectedTaskCount = new AtomicLong();

    /** 使用框架默认线程数和队列容量创建执行器。 */
    ProviderBusinessExecutor() {
        this(
                NettyRpcServer.DEFAULT_BUSINESS_THREAD_COUNT,
                NettyRpcServer.DEFAULT_BUSINESS_QUEUE_CAPACITY
        );
    }

    /**
     * 使用明确参数创建执行器，保留给后续 Starter 配置和单元测试复用。
     *
     * @param threadCount  固定业务线程数，必须大于 0
     * @param queueCapacity 等待队列容量，必须大于 0
     */
    ProviderBusinessExecutor(int threadCount, int queueCapacity) {
        if (threadCount <= 0) {
            throw new RpcException("Provider 业务线程数必须大于 0");
        }
        if (queueCapacity <= 0) {
            throw new RpcException("Provider 业务任务队列容量必须大于 0");
        }

        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "mini-rpc-provider-business-"
                            + THREAD_SEQUENCE.incrementAndGet()
            );
            /*
             * 业务线程不是守护线程：正常退出必须依赖 RpcProvider 的生命周期关闭，
             * 不能用 daemon=true 掩盖线程池泄漏。线程抛出未捕获异常时保留日志线索。
             */
            thread.setUncaughtExceptionHandler((failedThread, throwable) ->
                    LOGGER.error(
                            "Provider 业务线程发生未捕获异常，线程：{}",
                            failedThread.getName(),
                            throwable
                    )
            );
            return thread;
        };

        this.executor = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                /*
                 * AbortPolicy 会让提交方明确收到 RejectedExecutionException。
                 * 不能使用 CallerRunsPolicy，否则队列满时会让 Netty EventLoop
                 * 亲自执行耗时业务，使线程隔离在过载时失效。
                 */
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** 把一次 RPC 业务调用提交给独立线程池。 */
    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new RpcException("Provider 业务任务不能为空");
        }
        try {
            executor.execute(command);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            rejectedTaskCount.incrementAndGet();
            throw exception;
        }
    }

    /**
     * 获取某一时刻的只读运行快照。
     *
     * <p>这些数字用于拒绝日志，帮助区分“所有线程都忙且队列已满”和“Provider
     * 已关闭”两种情况。快照不是事务性统计，读取过程中数值继续变化属于正常现象。</p>
     */
    Statistics snapshot() {
        return new Statistics(
                executor.getCorePoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(),
                rejectedTaskCount.get(),
                executor.isShutdown()
        );
    }

    /**
     * 停止业务线程池。
     *
     * @param drained {@code true} 表示全部在途请求已经完成，可以正常关闭；
     *                {@code false} 表示排空超时，需要向剩余任务发出中断
     */
    void shutdown(boolean drained) {
        if (drained) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }
    }

    /** 仅供测试和运行诊断判断线程池是否已经进入关闭状态。 */
    boolean isShutdown() {
        return executor.isShutdown();
    }

    /** Provider 业务线程池在某一时刻的诊断数据。 */
    record Statistics(
            int configuredThreadCount,
            int activeThreadCount,
            int queuedTaskCount,
            int remainingQueueCapacity,
            long completedTaskCount,
            long rejectedTaskCount,
            boolean shutdown
    ) {
    }
}
