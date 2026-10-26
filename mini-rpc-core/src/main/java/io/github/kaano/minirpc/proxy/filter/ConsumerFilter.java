package io.github.kaano.minirpc.proxy.filter;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;

/**
 * Consumer 侧一次远程调用的过滤器。
 *
 * <p>动态代理已经把 Java 方法调用整理成 {@link RpcRequest} 后，才进入这里；
 * 最后一个过滤器继续执行时，调用链会交给 RpcClient 进行服务发现、连接和发送。
 * 因而本接口适合请求日志、调用耗时和追踪信息等围绕一次调用的逻辑，不负责
 * Netty 编解码或 Channel 读写。</p>
 *
 * <p>过滤器调用 {@code chain.proceed(request)} 才会继续处理请求；直接返回
 * {@link RpcResponse} 可以短路网络调用。一个请求只应调用一次 proceed，避免
 * 在没有幂等保证时重复发送。Filter 实例可以被多个 Consumer 业务线程共享，
 * 单次调用状态应放在局部变量中。</p>
 */
@FunctionalInterface
public interface ConsumerFilter {

    /**
     * 处理当前请求，并自行决定是否继续执行剩余调用链。
     *
     * @param request 当前代理构造的 RPC 请求
     * @param chain   从下一个 Filter 开始的剩余调用链
     * @return 当前节点或后续节点生成的 RPC 响应，不能为 {@code null}
     */
    RpcResponse filter(RpcRequest request, ConsumerFilterChain chain);
}
