package io.github.kaano.minirpc.transport.client;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理“已经发出、但还没有收到响应”的 RPC 请求。
 *
 * <p>Netty 的网络读写是异步的。业务线程发送请求后，响应不会立刻作为
 * {@code writeAndFlush} 的返回值出现，而是在未来某个时刻由 Netty I/O 线程收到。
 * 因此需要通过 requestId，把响应交回当初等待它的调用。</p>
 *
 * <pre>
 * 业务线程发送请求 1001
 *     ↓
 * pendingRequests.put(1001, future)
 *     ↓
 * 服务端返回 RpcResponse(requestId=1001)
 *     ↓
 * Netty I/O 线程取出 future，并调用 future.complete(response)
 *     ↓
 * 等待 future 的业务线程取得响应
 * </pre>
 */
public class PendingRequestManager {

    /**
     * key 是请求编号，value 是等待该请求响应的 Future。
     *
     * <p>登记请求和完成响应发生在不同线程，所以不能使用普通 HashMap；
     * ConcurrentHashMap 可以支持这些并发操作。</p>
     */
    private final Map<Long, CompletableFuture<RpcResponse>> pendingRequests =
            new ConcurrentHashMap<>();

    /**
     * 在请求发送前登记一次等待任务。
     *
     * @param requestId 即将发送的请求编号，必须大于 0
     * @return 调用方用于等待响应的 CompletableFuture
     * @throws RpcException 请求编号无效或已经存在相同编号时抛出
     */
    public CompletableFuture<RpcResponse> register(long requestId) {
        validateRequestId(requestId);

        CompletableFuture<RpcResponse> future = new CompletableFuture<>();

        /*
         * putIfAbsent 表示：只有该 requestId 还不存在时才放入。
         * “检查是否存在”和“放入”是一个原子操作，可以防止并发情况下重复登记。
         */
        CompletableFuture<RpcResponse> existingFuture =
                pendingRequests.putIfAbsent(requestId, future);

        if (existingFuture != null) {
            throw new RpcException("请求已经在等待响应，requestId：" + requestId);
        }

        return future;
    }

    /**
     * 收到服务端响应后，完成与它 requestId 相同的等待任务。
     *
     * <p>这里使用 {@code remove} 而不是 {@code get}。一次请求只能收到一次响应，
     * 完成后立即从 Map 中删除，可以避免等待记录一直占用内存。</p>
     *
     * @param response 服务端返回的响应，不能为 {@code null}
     * @return 找到并完成了等待任务时返回 {@code true}；没有对应请求时返回 {@code false}
     */
    public boolean complete(RpcResponse response) {
        if (response == null) {
            throw new RpcException("RpcResponse 不能为空");
        }

        long requestId = response.getRequestId();
        validateRequestId(requestId);

        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future == null) {
            // 可能是重复响应，或者原请求已经超时并被清理。
            return false;
        }

        return future.complete(response);
    }

    /**
     * 让某一个等待中的请求以异常结束。
     *
     * <p>例如请求写入网络失败时，不能让业务线程无限等待响应，而应把写入异常
     * 传递给对应 Future。</p>
     *
     * @param requestId 发生失败的请求编号
     * @param cause     导致请求失败的异常，不能为 {@code null}
     * @return 找到并结束了等待任务时返回 {@code true}
     */
    public boolean completeExceptionally(long requestId, Throwable cause) {
        validateRequestId(requestId);
        if (cause == null) {
            throw new RpcException("请求失败原因不能为空");
        }

        CompletableFuture<RpcResponse> future = pendingRequests.remove(requestId);
        if (future == null) {
            return false;
        }

        return future.completeExceptionally(cause);
    }

    /**
     * 让当前所有等待中的请求都以同一个异常结束。
     *
     * <p>客户端连接突然断开时，旧连接上的响应不可能再到达。此时必须唤醒所有
     * 等待线程，让它们收到明确异常，而不是永远阻塞。</p>
     *
     * @param cause 连接关闭等公共失败原因，不能为 {@code null}
     */
    public void failAll(Throwable cause) {
        if (cause == null) {
            throw new RpcException("请求失败原因不能为空");
        }

        pendingRequests.forEach((requestId, future) -> {
            /*
             * remove(key, value) 只有在 key 仍然对应当前 future 时才删除。
             * 删除成功后再完成 Future，避免和正常响应线程重复处理同一请求。
             */
            if (pendingRequests.remove(requestId, future)) {
                future.completeExceptionally(cause);
            }
        });
    }

    /** 检查 requestId 是否符合当前项目的编号规则。 */
    private void validateRequestId(long requestId) {
        if (requestId <= 0) {
            throw new RpcException("requestId 必须大于 0：" + requestId);
        }
    }
}
