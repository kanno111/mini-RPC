package com.itheima.transport.server;

import com.itheima.common.RpcException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 记录 Provider 当前尚未完成响应的 RPC 请求。
 *
 * <p>“在途请求”从服务端业务 Handler 接受请求时开始，到响应写入操作完成时结束。
 * Provider 关闭时先调用 {@link #beginDraining()}，此后新的请求不能再取得执行许可；
 * 已经取得许可的请求仍可以继续执行并完成响应。</p>
 *
 * <p>所有状态都由对象自身的监视器保护。计数变化和关闭状态检查因此是一个原子
 * 操作，不会出现“关闭线程刚判断为 0，另一个线程又把计数加一”的竞态。</p>
 */
final class InFlightRequestTracker {

    /** true 表示仍允许新请求进入业务执行阶段。 */
    private boolean acceptingRequests = true;

    /** 已经开始处理、但响应写入尚未完成的请求数量。 */
    private int inFlightRequestCount;

    /**
     * 尝试为一个新请求取得执行许可。
     *
     * @return true 表示请求已计入在途数量，可以执行；false 表示 Provider 正在关闭
     */
    synchronized boolean tryStartRequest() {
        if (!acceptingRequests) {
            return false;
        }

        inFlightRequestCount++;
        return true;
    }

    /**
     * 标记一个在途请求已经完成响应写入。
     *
     * <p>计数变成 0 时唤醒关闭线程。使用 {@code notifyAll()} 是因为将来可能同时有
     * Spring 生命周期线程和测试线程等待排空，不能假设永远只有一个等待者。</p>
     */
    synchronized void finishRequest() {
        if (inFlightRequestCount <= 0) {
            throw new RpcException("在途 RPC 请求计数不能小于 0");
        }

        inFlightRequestCount--;
        if (inFlightRequestCount == 0) {
            notifyAll();
        }
    }

    /** 停止接受新业务请求，并返回开始排空时的在途数量。 */
    synchronized int beginDraining() {
        acceptingRequests = false;
        return inFlightRequestCount;
    }

    /**
     * 等待所有在途请求完成，但不会超过调用方给出的最长时间。
     *
     * @param timeout 最大等待时间，必须至少为 1 毫秒
     * @return true 表示计数已经归零；false 表示等待超时后仍有请求未完成
     */
    synchronized boolean awaitCompletion(Duration timeout) {
        validateTimeout(timeout);

        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;

        while (inFlightRequestCount > 0 && remainingNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RpcException("等待在途 RPC 请求完成时线程被中断", exception);
            }
            remainingNanos = deadline - System.nanoTime();
        }

        return inFlightRequestCount == 0;
    }

    /** 返回当前在途数量，主要用于关闭超时日志和测试断言。 */
    synchronized int getInFlightRequestCount() {
        return inFlightRequestCount;
    }

    private void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.toMillis() < 1) {
            throw new RpcException("Provider 排空超时不能小于 1 毫秒");
        }
    }
}
