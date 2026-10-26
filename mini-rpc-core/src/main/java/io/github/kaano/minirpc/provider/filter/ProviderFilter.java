package io.github.kaano.minirpc.provider.filter;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;

/**
 * Provider 侧 RPC 请求过滤器。
 *
 * <p>过滤器位于 Netty 已经解码出的 {@link RpcRequest} 与真正的服务方法调用之间，
 * 适合承载日志、deadline 检查、指标、鉴权和限流等“每次调用都要经过”的横切逻辑。
 * 它不负责字节编解码、Channel 管理或业务线程调度，这些仍属于 Netty 传输层。</p>
 *
 * <p>实现类通常先执行自己的前置逻辑，再调用 {@link ProviderFilterChain#proceed(RpcRequest)}
 * 进入下一个节点，最后执行后置逻辑。例如：</p>
 *
 * <pre>{@code
 * long start = System.nanoTime();
 * try {
 *     return chain.proceed(request);
 * } finally {
 *     recordElapsedTime(System.nanoTime() - start);
 * }
 * }</pre>
 *
 * <p>过滤器也可以不调用 {@code proceed} 而直接返回失败响应，这叫作“短路”。
 * deadline 已过期、鉴权失败或触发限流时，都可以用短路阻止真正的服务方法执行。</p>
 *
 * <p>同一个过滤器实例会被多个 Provider 业务线程并发调用，因此实现类不应把单次
 * 请求数据保存在普通成员变量中；确实需要共享计数时，应使用线程安全的数据结构。</p>
 */
@FunctionalInterface
public interface ProviderFilter {

    /**
     * 处理一次 Provider 调用。
     *
     * @param request 当前 RPC 请求，不能为 {@code null}
     * @param chain   从下一个过滤器开始的剩余调用链
     * @return 当前过滤器或后续调用链产生的 RPC 响应，不能为 {@code null}
     */
    RpcResponse filter(
            RpcRequest request,
            ProviderFilterChain chain
    );
}
