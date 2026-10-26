package io.github.kaano.minirpc.provider.filter;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;

/**
 * 在 Provider 真正执行服务方法前检查请求的绝对截止时间。
 *
 * <p>这个 Filter 在业务线程中运行，因此能覆盖请求已经在有界队列中等待了一段
 * 时间、到实际开始处理时才过期的情况。过期时直接返回失败响应，不进入后续
 * Filter，也不查找或执行服务对象；未过期时继续调用链。</p>
 *
 * <p>deadline 为 0 表示早期请求没有携带截止时间，保持兼容并继续执行。
 * 此检查只发生在执行前，业务方法运行期间的协作式检查仍由
 * {@code RpcCallContext.checkDeadline()} 负责。</p>
 */
public final class ProviderDeadlineFilter implements ProviderFilter {

    @Override
    public RpcResponse filter(RpcRequest request, ProviderFilterChain chain) {
        long deadlineEpochMillis = request.getDeadlineEpochMillis();
        long currentEpochMillis = System.currentTimeMillis();
        if (deadlineEpochMillis > 0
                && currentEpochMillis >= deadlineEpochMillis) {
            /*
             * 必须在链的终点 RpcRequestHandler 之前短路，避免超时请求仍触发
             * 数据库写入等业务副作用。保留原响应的 requestId 和错误信息，
             * 便于 Consumer 按请求编号关联失败结果。
             */
            return RpcResponse.failure(
                    request.getRequestId(),
                    new RpcException(
                            "RPC 请求在 Provider 执行前已超过 deadline，requestId："
                                    + request.getRequestId()
                                    + "，deadline：" + deadlineEpochMillis
                                    + "，当前时间：" + currentEpochMillis
                    )
            );
        }
        return chain.proceed(request);
    }
}
