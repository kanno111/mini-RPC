package io.github.kaano.minirpc.provider;

import io.github.kaano.minirpc.common.RpcException;

/**
 * Provider 业务线程当前正在执行的 RPC 调用上下文。
 *
 * <p>框架在反射调用服务方法前绑定 requestId 和 deadline，并在方法结束后清理。
 * 耗时业务可以在循环、批处理分段或其他安全停止点调用 {@link #checkDeadline()}。
 * deadline 到达后，该方法会抛出 {@link RpcException}，由 RPC 调用链转换成失败响应。</p>
 *
 * <p>这里使用 {@link ThreadLocal}，因此不同 Provider 工作线程之间不会互相覆盖。
 * 上下文只属于执行服务方法的当前线程，不会自动传播到业务代码自己创建的线程
 * 或线程池任务中。</p>
 */
public final class RpcCallContext {

    /** 当前线程绑定的调用信息；没有执行 RPC 服务方法时保持为空。 */
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RpcCallContext() {
    }

    /**
     * 判断当前线程是否正在执行由 mini-RPC 发起的 Provider 服务调用。
     *
     * @return 已绑定调用上下文时返回 {@code true}
     */
    public static boolean hasContext() {
        return CURRENT.get() != null;
    }

    /**
     * 取得当前 RPC 请求编号。
     *
     * @return 当前请求的 requestId
     * @throws RpcException 当前线程不在 RPC 服务方法调用中时抛出
     */
    public static long getRequestId() {
        State state = requireState();
        return state.requestId();
    }

    /**
     * 取得 Consumer 传来的绝对截止时间。
     *
     * @return Epoch 毫秒；返回 0 表示当前请求没有携带 deadline
     * @throws RpcException 当前线程不在 RPC 服务方法调用中时抛出
     */
    public static long getDeadlineEpochMillis() {
        State state = requireState();
        return state.deadlineEpochMillis();
    }

    /**
     * 判断当前调用是否已经超过 deadline。
     *
     * <p>没有 deadline 的兼容请求永远返回 {@code false}。这个方法只负责观察状态，
     * 不会自动结束业务方法；需要立即停止时应调用 {@link #checkDeadline()}。</p>
     *
     * @return 已到达或超过 deadline 时返回 {@code true}
     * @throws RpcException 当前线程不在 RPC 服务方法调用中时抛出
     */
    public static boolean isDeadlineExceeded() {
        return isDeadlineExceeded(System.currentTimeMillis());
    }

    /**
     * 在业务代码选择的安全点检查 deadline。
     *
     * <p>框架不会使用 {@code Thread.stop()} 等危险方式强行终止任意 Java 代码。
     * 因此，可能长时间运行的业务需要周期性调用本方法，主动结束已经失去返回价值
     * 的工作。</p>
     *
     * @throws RpcException 当前调用已经到达或超过 deadline 时抛出
     */
    public static void checkDeadline() {
        checkDeadline(System.currentTimeMillis());
    }

    /**
     * 由请求执行器在进入服务方法前绑定上下文。
     *
     * <p>该入口只开放给同包框架代码。返回的作用域必须关闭，确保线程被下一次
     * 请求复用时不会读到旧 requestId 或 deadline。</p>
     */
    static Scope bind(long requestId, long deadlineEpochMillis) {
        State previous = CURRENT.get();
        CURRENT.set(new State(requestId, deadlineEpochMillis));
        return new Scope(previous);
    }

    /** 使用传入时间判断，单元测试可以不依赖真实等待。 */
    static boolean isDeadlineExceeded(long currentEpochMillis) {
        State state = requireState();
        long deadlineEpochMillis = state.deadlineEpochMillis();
        return deadlineEpochMillis > 0
                && currentEpochMillis >= deadlineEpochMillis;
    }

    /** 使用传入时间执行检查，保证边界语义可以被稳定测试。 */
    static void checkDeadline(long currentEpochMillis) {
        State state = requireState();
        if (state.deadlineEpochMillis() <= 0
                || currentEpochMillis < state.deadlineEpochMillis()) {
            return;
        }

        throw new RpcException(
                "RPC 请求在 Provider 执行期间已超过 deadline，requestId："
                        + state.requestId()
                        + "，deadline：" + state.deadlineEpochMillis()
                        + "，当前时间：" + currentEpochMillis
        );
    }

    /** 统一校验上下文，避免业务代码在普通线程误读到伪造的默认值。 */
    private static State requireState() {
        State state = CURRENT.get();
        if (state == null) {
            throw new RpcException("当前线程没有 RPC 调用上下文");
        }
        return state;
    }

    /** 当前线程的一次调用快照，绑定后不再修改。 */
    private record State(long requestId, long deadlineEpochMillis) {
    }

    /**
     * 与一次绑定配对的清理作用域。
     *
     * <p>保存旧值而不是一律 remove，可正确处理同一线程上的嵌套绑定。</p>
     */
    static final class Scope implements AutoCloseable {

        private final State previous;
        private boolean closed;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
