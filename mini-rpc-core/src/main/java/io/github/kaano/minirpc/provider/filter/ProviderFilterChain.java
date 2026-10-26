package io.github.kaano.minirpc.provider.filter;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;

/**
 * Provider 过滤器调用链。
 *
 * <p>{@link #proceed(RpcRequest)} 表示“当前过滤器处理完成，继续执行剩余节点”。
 * 当过滤器已经全部执行后，默认实现会进入 RpcRequestHandler，查找服务并反射调用
 * 真正的业务方法。</p>
 */
@FunctionalInterface
public interface ProviderFilterChain {

    /**
     * 继续执行下一个过滤器；如果没有剩余过滤器，则执行最终服务调用。
     *
     * @param request 要继续处理的 RPC 请求，不能为 {@code null}
     * @return 后续链路产生的 RPC 响应，不能为 {@code null}
     */
    RpcResponse proceed(RpcRequest request);
}
