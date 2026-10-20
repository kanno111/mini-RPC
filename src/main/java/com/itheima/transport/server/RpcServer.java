package com.itheima.transport.server;

import com.itheima.common.RpcException;

/**
 * RPC 服务端的统一生命周期接口。
 *
 * <p>服务端负责监听网络端口、接收客户端请求并返回响应。这个接口只规定
 * “启动”和“停止”两种能力，不关心底层使用 JDK Socket 还是 Netty。</p>
 *
 * <pre>
 * RpcServer
 *    ├── SocketRpcServer   M1：使用 JDK BIO Socket
 *    └── NettyRpcServer    M2：使用 Netty
 * </pre>
 *
 * <p>上层启动代码面向 {@code RpcServer} 编程后，替换网络实现时不需要改变
 * 调用方式。</p>
 */
public interface RpcServer {

    /**
     * 启动 RPC 服务端。
     *
     * <p>具体实现通常会依次完成：</p>
     *
     * <ol>
     *     <li>绑定配置的网络端口；</li>
     *     <li>进入请求监听状态；</li>
     *     <li>接收连接并把请求交给处理逻辑。</li>
     * </ol>
     *
     * <p>M1 的 Socket 实现会在当前线程中阻塞等待客户端连接，因此通常由
     * {@code ProviderMain} 在完成服务注册后调用。</p>
     *
     * @throws RpcException 端口绑定失败、服务端已经启动或监听过程异常时抛出
     */
    void start();

    /**
     * 停止 RPC 服务端并释放资源。
     *
     * <p>具体实现需要关闭监听端口、已有连接以及内部线程池。重复调用 stop
     * 应当是安全的，避免程序关闭过程中因为重复清理而产生额外异常。</p>
     *
     * @throws RpcException 释放服务端资源失败时抛出
     */
    void stop();
}
