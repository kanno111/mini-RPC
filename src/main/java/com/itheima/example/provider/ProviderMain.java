package com.itheima.example.provider;

import com.itheima.example.api.HelloService;
import com.itheima.provider.DefaultServiceProvider;
import com.itheima.provider.RpcRequestHandler;
import com.itheima.provider.ServiceProvider;
import com.itheima.serialize.JdkSerializer;
import com.itheima.serialize.Serializer;
import com.itheima.transport.server.RpcServer;
import com.itheima.transport.server.SocketRpcServer;

/**
 * M1 阶段的 RPC 服务端启动入口。
 *
 * <p>这个类本身不处理网络请求，也不执行业务逻辑。它的作用是把已经写好的
 * 服务注册表、请求处理器、序列化器和 Socket 服务端组装起来，然后启动服务。</p>
 */
public class ProviderMain {

    /** 客户端以后需要连接这个端口。 */
    private static final int RPC_PORT = 8080;

    /**
     * 启动服务端。
     *
     * @param args 命令行参数，本示例暂时不使用
     */
    public static void main(String[] args) {
        /*
         * 第一步：创建服务端本地注册表。
         * 它保存“接口全限定名 -> 真正的服务实现对象”的对应关系。
         */
        ServiceProvider serviceProvider = new DefaultServiceProvider();

        /*
         * 第二步：创建真正执行业务逻辑的对象，并按照它实现的接口进行注册。
         * 注册完成后，服务端收到 HelloService 请求时就能找到 helloService。
         */
        HelloService helloService = new HelloServiceImpl();
        serviceProvider.registerService(HelloService.class, helloService);

        /*
         * 第三步：准备请求处理器和序列化器。
         *
         * requestHandler 负责：找到服务对象并反射调用目标方法；
         * serializer 负责：在 Java 对象与网络字节之间相互转换。
         */
        RpcRequestHandler requestHandler = new RpcRequestHandler(serviceProvider);
        Serializer serializer = new JdkSerializer();

        /*
         * 第四步：把端口、序列化器和请求处理器交给 Socket 服务端。
         * 到这里，SocketRpcServer 才具备完整处理 RPC 请求的条件。
         */
        RpcServer rpcServer = new SocketRpcServer(
                RPC_PORT,
                serializer,
                requestHandler
        );

        /*
         * 在 IDE 中点击停止或操作系统结束 JVM 时，尽量关闭监听端口和线程池。
         * SocketRpcServer.stop() 允许重复调用，因此和 start() 内部的清理不冲突。
         */
        Runtime.getRuntime().addShutdownHook(
                new Thread(rpcServer::stop, "rpc-server-shutdown")
        );

        /*
         * start() 会在当前主线程中持续等待客户端连接。
         * 只要服务端没有停止，程序就会一直停留在这个方法中。
         */
        rpcServer.start();
    }
}
