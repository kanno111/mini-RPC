package com.itheima.transport.client;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;

/**
 * RPC 客户端的统一请求发送接口。
 *
 * <p>客户端代理层负责把一次 Java 方法调用组装成 {@link RpcRequest}，
 * 然后把请求交给这个接口。具体实现负责网络通信，并返回服务端发送的
 * {@link RpcResponse}。</p>
 *
 * <pre>
 * Java 方法调用
 *      ↓
 * RpcClientProxy 组装 RpcRequest
 *      ↓
 * RpcClient.sendRequest(request)
 *      ↓
 * 具体网络实现发送请求并接收响应
 *      ↓
 * RpcResponse
 * </pre>
 *
 * <p>M1 会提供基于 JDK Socket 的同步阻塞实现，M2 会替换为 Netty 实现。
 * 代理层只依赖这个接口，因此切换网络实现时不需要修改代理逻辑。</p>
 */
public interface RpcClient {

    /**
     * 向 RPC 服务端发送一次请求，并等待对应响应。
     *
     * <p>M1 采用同步阻塞模型：调用该方法后，当前线程会等待服务端返回结果，
     * 得到完整的 {@code RpcResponse} 后方法才会结束。</p>
     *
     * <p>具体实现需要完成：</p>
     *
     * <ol>
     *     <li>连接服务端；</li>
     *     <li>把 RpcRequest 序列化并写入网络；</li>
     *     <li>从网络读取响应数据；</li>
     *     <li>把响应数据反序列化成 RpcResponse；</li>
     *     <li>关闭或复用连接。</li>
     * </ol>
     *
     * @param request 本次远程方法调用的请求数据，不能为 {@code null}
     * @return 服务端返回的响应，不能为 {@code null}
     * @throws RpcException 请求无效、连接失败、数据读写失败或响应解析失败时抛出
     */
    RpcResponse sendRequest(RpcRequest request);
}
