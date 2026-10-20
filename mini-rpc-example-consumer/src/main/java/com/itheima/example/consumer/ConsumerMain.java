package com.itheima.example.consumer;

import com.itheima.example.api.HelloService;
import com.itheima.proxy.RpcClientProxy;
import com.itheima.serialize.JdkSerializer;
import com.itheima.serialize.Serializer;
import com.itheima.transport.client.RpcClient;
import com.itheima.transport.client.SocketRpcClient;

/**
 * M1 阶段的 RPC 客户端启动入口。
 *
 * <p>客户端只依赖 {@link HelloService} 接口，并不知道服务端使用的是
 * {@code HelloServiceImpl}。接口方法真正在哪里执行，由 RPC 框架屏蔽。</p>
 */
public class ConsumerMain {

    /** 127.0.0.1 表示当前电脑；M1 暂时把服务地址直接写在代码中。 */
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
         * 第一步：创建与服务端相同的序列化器。
         * 服务端使用 JDK 方式解码，因此客户端也必须使用 JDK 方式编码。
         */
        Serializer serializer = new JdkSerializer();

        /*
         * 第二步：创建真正负责网络通信的客户端。
         * 此时还没有建立连接；M1 会在每次 sendRequest 时创建一个 Socket。
         */
        RpcClient rpcClient = new SocketRpcClient(
                SERVER_HOST,
                SERVER_PORT,
                serializer
        );

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
         * 代理拦截方法 -> 生成 RpcRequest -> Socket 发送 -> 服务端执行
         * -> 收到 RpcResponse -> 取出 data 并作为 sayHello 的返回值。
         */
        String result = helloService.sayHello("world");

        // 如果控制台打印出服务端生成的问候语，说明 M1 全链路已经跑通。
        System.out.println("RPC 调用结果：" + result);
    }
}
