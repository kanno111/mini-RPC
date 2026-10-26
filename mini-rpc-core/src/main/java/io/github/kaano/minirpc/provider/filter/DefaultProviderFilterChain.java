package io.github.kaano.minirpc.provider.filter;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.provider.RpcRequestHandler;

import java.util.List;

/**
 * {@link ProviderFilterChain} 的默认顺序执行实现。
 *
 * <p>调用链由两部分组成：</p>
 *
 * <pre>
 * ProviderFilter[0] → ProviderFilter[1] → ... → RpcRequestHandler
 * </pre>
 *
 * <p>本类没有“当前执行到哪里”的共享可变下标。每次进入下一个过滤器时，都会创建
 * 一个只保存下一个位置的轻量链节点；过滤器列表和最终处理器则由所有节点共享。
 * 因此同一个根调用链可以被多个 Provider 业务线程并发使用，不会让两个请求互相
 * 修改执行位置。链中共享的 {@link ProviderFilter} 实例仍需由实现者保证线程安全。</p>
 */
public final class DefaultProviderFilterChain
        implements ProviderFilterChain {

    /** 按执行顺序保存的不可变过滤器快照。 */
    private final List<ProviderFilter> filters;

    /** 所有过滤器执行完以后，真正查找并调用服务方法的终点。 */
    private final RpcRequestHandler terminalHandler;

    /** 当前节点下一次应执行的过滤器下标。 */
    private final int nextFilterIndex;

    /**
     * 创建一条从第一个过滤器开始的 Provider 调用链。
     *
     * <p>构造时会复制过滤器列表，避免外部在运行期间修改原列表，导致不同请求看到
     * 不同顺序。空列表是合法的，此时 {@link #proceed(RpcRequest)} 会直接进入
     * {@link RpcRequestHandler}。</p>
     *
     * @param filters         按执行顺序排列的过滤器，可以为空列表但不能为 {@code null}
     * @param terminalHandler 调用链终点，不能为 {@code null}
     */
    public DefaultProviderFilterChain(
            List<ProviderFilter> filters,
            RpcRequestHandler terminalHandler
    ) {
        this(copyAndValidateFilters(filters), terminalHandler, 0);
    }

    /**
     * 创建表示“剩余调用链”的内部节点。
     *
     * <p>这里不重复复制列表；公开构造方法已经生成不可变快照，后续节点可以安全共享。</p>
     */
    private DefaultProviderFilterChain(
            List<ProviderFilter> filters,
            RpcRequestHandler terminalHandler,
            int nextFilterIndex
    ) {
        if (terminalHandler == null) {
            throw new RpcException("Provider 调用链终点不能为空");
        }
        this.filters = filters;
        this.terminalHandler = terminalHandler;
        this.nextFilterIndex = nextFilterIndex;
    }

    /**
     * 执行当前链节点。
     *
     * <p>还有过滤器时，把“下一个位置”封装成新的 chain 交给当前过滤器；没有过滤器
     * 时进入 RpcRequestHandler。过滤器可以选择调用下一节点，也可以直接返回响应
     * 实现短路。</p>
     */
    @Override
    public RpcResponse proceed(RpcRequest request) {
        if (request == null) {
            throw new RpcException("Provider Filter 处理的 RpcRequest 不能为空");
        }

        RpcResponse response;
        if (nextFilterIndex < filters.size()) {
            ProviderFilter currentFilter = filters.get(nextFilterIndex);
            ProviderFilterChain remainingChain =
                    new DefaultProviderFilterChain(
                            filters,
                            terminalHandler,
                            nextFilterIndex + 1
                    );
            response = currentFilter.filter(request, remainingChain);
        } else {
            response = terminalHandler.handle(request);
        }

        if (response == null) {
            throw new RpcException(
                    "Provider Filter 调用链不能返回 null，当前下标："
                            + nextFilterIndex
            );
        }
        return response;
    }

    /** 复制并校验外部列表，防止运行期间被修改或包含空过滤器。 */
    private static List<ProviderFilter> copyAndValidateFilters(
            List<ProviderFilter> filters
    ) {
        if (filters == null) {
            throw new RpcException("Provider Filter 列表不能为空");
        }
        if (filters.stream().anyMatch(filter -> filter == null)) {
            throw new RpcException("Provider Filter 列表不能包含 null");
        }
        return List.copyOf(filters);
    }
}
