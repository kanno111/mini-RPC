package io.github.kaano.minirpc.proxy.filter;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.transport.client.RpcClient;

import java.time.Duration;
import java.util.List;

/**
 * 按固定列表顺序运行 Consumer Filter，最后执行一次网络发送。
 *
 * <p>根链在代理创建时生成，保存过滤器的不可变快照、共享 RpcClient 和当前服务
 * 引用的超时覆盖值。每次执行到下一个 Filter，都会生成一个仅包含新位置的轻量
 * 链节点；多个业务线程复用代理时，不会共享可变的执行下标。</p>
 *
 * <p>超时覆盖值属于服务引用，不属于单个 Filter。即使 Filter 列表为空，终点
 * 仍须像原来的动态代理一样区分：未覆盖时调用单参数 sendRequest；指定覆盖值时
 * 调用带 Duration 的 sendRequest，不能悄悄退化为全局超时。</p>
 */
public final class DefaultConsumerFilterChain implements ConsumerFilterChain {

    /** 创建根链时复制的过滤器快照，后续轻量节点共享此列表。 */
    private final List<ConsumerFilter> filters;

    /** 所有过滤器执行结束后的网络调用终点。 */
    private final RpcClient rpcClient;

    /** 当前代理引用单独设置的请求超时；null 表示交由 RpcClient 使用默认值。 */
    private final Duration requestTimeoutOverride;

    /** 当前节点应执行的过滤器下标。 */
    private final int nextFilterIndex;

    /**
     * 创建从第一个 Filter 开始的根链。空列表表示直接进入 RpcClient。
     * 超时有效性由创建代理时的校验保证；这里仅保存已验证的覆盖值。
     */
    public DefaultConsumerFilterChain(
            List<ConsumerFilter> filters,
            RpcClient rpcClient,
            Duration requestTimeoutOverride
    ) {
        this(copyAndValidateFilters(filters), rpcClient, requestTimeoutOverride, 0);
    }

    /** 内部节点复用根链不可变快照，只推进自己的执行位置。 */
    private DefaultConsumerFilterChain(
            List<ConsumerFilter> filters,
            RpcClient rpcClient,
            Duration requestTimeoutOverride,
            int nextFilterIndex
    ) {
        if (rpcClient == null) {
            throw new RpcException("RpcClient 不能为空");
        }
        this.filters = filters;
        this.rpcClient = rpcClient;
        this.requestTimeoutOverride = requestTimeoutOverride;
        this.nextFilterIndex = nextFilterIndex;
    }

    @Override
    public RpcResponse proceed(RpcRequest request) {
        if (request == null) {
            throw new RpcException("Consumer Filter 处理的 RpcRequest 不能为空");
        }

        RpcResponse response;
        if (nextFilterIndex < filters.size()) {
            ConsumerFilter currentFilter = filters.get(nextFilterIndex);
            ConsumerFilterChain remainingChain = new DefaultConsumerFilterChain(
                    filters,
                    rpcClient,
                    requestTimeoutOverride,
                    nextFilterIndex + 1
            );
            response = currentFilter.filter(request, remainingChain);
        } else {
            // 沿用原代理的重载选择，避免单引用超时在接入 Filter 后失效。
            response = requestTimeoutOverride == null
                    ? rpcClient.sendRequest(request)
                    : rpcClient.sendRequest(request, requestTimeoutOverride);
        }

        if (response == null) {
            throw new RpcException("Consumer Filter 调用链不能返回 null");
        }
        return response;
    }

    /** 防止调用方在代理使用期间修改顺序，或在链中留下空节点。 */
    private static List<ConsumerFilter> copyAndValidateFilters(
            List<ConsumerFilter> filters
    ) {
        if (filters == null) {
            throw new RpcException("Consumer Filter 列表不能为空");
        }
        if (filters.stream().anyMatch(filter -> filter == null)) {
            throw new RpcException("Consumer Filter 列表不能包含 null");
        }
        return List.copyOf(filters);
    }
}
