package com.itheima.example.provider;

import com.itheima.example.api.HelloService;
import com.itheima.provider.DefaultServiceProvider;
import com.itheima.provider.RpcRequestHandler;
import com.itheima.provider.ServiceProvider;
import com.itheima.registry.ServiceAddress;
import com.itheima.registry.zookeeper.ZooKeeperServiceRegistry;
import com.itheima.serialize.JsonSerializer;
import com.itheima.serialize.Serializer;
import com.itheima.transport.server.NettyRpcServer;
import com.itheima.transport.server.RpcServer;

/**
 * M4 阶段的 RPC 服务端启动入口。
 *
 * <p>这个类本身不处理网络请求，也不执行业务逻辑。它的作用是把已经写好的
 * 本地服务表、ZooKeeper 注册中心、请求处理器、序列化器和 Netty 服务端
 * 组装起来，然后启动服务。</p>
 */
public class ProviderMain {

    /**
     * 当前 Provider 对外公布的主机地址。
     *
     * <p>本地学习时 Consumer 和 Provider 在同一台电脑上，因此使用 127.0.0.1。
     * 部署到不同机器时，这里必须换成 Consumer 能够访问到的真实 IP。</p>
     */
    private static final String RPC_HOST = "127.0.0.1";

    /** 客户端以后需要连接这个端口。 */
    private static final int RPC_PORT = 8080;

    /**
     * 启动服务端。
     *
     * @param args 命令行参数，本示例暂时不使用
     */
    public static void main(String[] args) {
        /*
         * 第一步：创建服务端本地服务表。
         * 它保存“接口全限定名 -> 真正的服务实现对象”的对应关系。
         * 这个 Map 只存在于当前 Provider 进程中，不会写入 ZooKeeper。
         */
        ServiceProvider serviceProvider = new DefaultServiceProvider();

        /*
         * 第二步：创建真正执行业务逻辑的对象，并注册到本地服务表。
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
        /*
         * M3 在这里切换为 JSON：服务端返回响应时，RpcEncoder 会使用它生成正文，
         * 并在协议头的 serializerType 字段写入数字 2。
         */
        Serializer serializer = new JsonSerializer();

        /*
         * 第四步：把端口、序列化器和请求处理器交给 Netty 服务端。
         * 到这里，NettyRpcServer 才具备完整处理 RPC 请求的条件。
         */
        RpcServer rpcServer = new NettyRpcServer(
                RPC_PORT,
                serializer,
                requestHandler
        );

        /*
         * 第五步：连接 ZooKeeper，并把“服务名称 -> 当前 Provider 地址”公布出去。
         *
         * try-with-resources 保证 main 方法离开这个代码块时调用 close()。
         * close() 会结束 ZooKeeper 会话，对应的临时地址节点也会自动消失。
         */
        try (ZooKeeperServiceRegistry serviceRegistry =
                     new ZooKeeperServiceRegistry()) {
            ServiceAddress serviceAddress = new ServiceAddress(
                    RPC_HOST,
                    RPC_PORT
            );

            serviceRegistry.register(
                    HelloService.class.getName(),
                    serviceAddress
            );

            /*
             * 在 IDE 中点击停止或操作系统结束 JVM 时：
             * 1. 停止 Netty，释放监听端口和线程池；
             * 2. 关闭注册中心连接，让临时服务地址立即从 ZooKeeper 消失。
             *
             * 两个 close/stop 方法都允许重复调用，所以它们和 try-with-resources、
             * RpcServer.start() 内部的清理逻辑不会冲突。
             */
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> {
                        rpcServer.stop();
                        serviceRegistry.close();
                    }, "rpc-provider-shutdown")
            );

            /*
             * 第六步：启动 Netty 服务端。
             * start() 会阻塞当前主线程，直到服务端被关闭。
             */
            rpcServer.start();
        }
    }
}
