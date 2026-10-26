package io.github.kaano.minirpc.proxy.filter;

import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;

/**
 * Consumer 过滤器收到的“剩余调用链”。
 *
 * <p>调用 {@link #proceed(RpcRequest)} 会进入下一个 Filter；全部 Filter 执行完后，
 * 默认实现调用 RpcClient，把同一份请求发送到 Provider。返回的响应会逐层交还
 * 给前面的 Filter，最后由动态代理转换为接口方法的返回值或异常。</p>
 */
@FunctionalInterface
public interface ConsumerFilterChain {

    /** 继续执行下一个 Filter 或最终的 RpcClient 网络调用。 */
    RpcResponse proceed(RpcRequest request);
}
