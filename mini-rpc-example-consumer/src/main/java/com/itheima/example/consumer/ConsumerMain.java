package com.itheima.example.consumer;

import com.itheima.example.api.HelloService;
import com.itheima.proxy.RpcClientProxy;
import com.itheima.serialize.JsonSerializer;
import com.itheima.serialize.Serializer;
import com.itheima.transport.client.NettyRpcClient;

/**
 * M3 阶段的 RPC 客户端启动入口。
 *
 * <p>客户端只依赖 {@link HelloService} 接口，并不知道服务端使用的是
 * {@code HelloServiceImpl}。接口方法真正在哪里执行，由 RPC 框架屏蔽。</p>
 */
public class ConsumerMain {

    /** 127.0.0.1 表示当前电脑；M3 暂时仍把服务地址直接写在代码中。 */
    private static final String SERVER_HOST = "127.0.0.1";

    /** 必须与 ProviderMain 中服务端监听的端口保持一致。 */
    private static final int SERVER_PORT = 8080;

    /**
     * 启动客户端并完成一次远程调用。
     *
     * @param args 命令行参数，本示例暂时不使用
     */
    public static void main(String[] args) {
        /*
         * 第一步：选择客户端发送请求时使用的序列化器。
         *
         * JsonSerializer 会把 RpcRequest 转换成 JSON 正文；RpcEncoder 还会把
         * JSON 的类型编号 2 写进协议头。服务端的 RpcDecoder 读取编号后，
         * 会自动选择 JsonSerializer，不需要在 Pipeline 中提前指定。
         */
        Serializer serializer = new JsonSerializer();

        /*
         * 第二步：创建真正负责网络通信的 Netty 客户端。
         * 构造方法只准备线程组和 Bootstrap，第一次发送请求时才建立 Channel。
         * try-with-resources 会在调用结束后自动执行 rpcClient.close()。
         */
        try (NettyRpcClient rpcClient = new NettyRpcClient(
                SERVER_HOST,
                SERVER_PORT,
                serializer
        )) {

            /*
             * 第三步：创建代理工厂，再让它生成一个实现了 HelloService 的代理对象。
             * helloService 不是 HelloServiceImpl，也不包含真正的问候业务逻辑。
             */
            RpcClientProxy proxyFactory = new RpcClientProxy(rpcClient);
            HelloService helloService = proxyFactory.createProxy(HelloService.class);

            /*
             * 第四步：像调用本地 Java 方法一样调用代理对象。
             *
             * 实际过程是：
             * 代理拦截方法 -> 生成 RpcRequest -> Netty 发送 -> 服务端执行
             * -> 异步收到 RpcResponse -> 根据 requestId 完成 Future
             * -> 取出 data 并作为 sayHello 的返回值。
             */
            String result = helloService.sayHello("world");

            // 如果控制台打印出服务端生成的问候语，说明 M3 JSON 全链路已经跑通。
            System.out.println("RPC 调用结果：" + result);
        }
    }
}
