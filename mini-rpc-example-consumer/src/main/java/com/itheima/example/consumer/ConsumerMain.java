package com.itheima.example.consumer;

import com.itheima.client.RpcClientFactory;
import com.itheima.example.api.HelloService;

/**
 * M4 阶段使用注册中心的 RPC 客户端启动入口。
 *
 * <p>客户端只依赖 {@link HelloService} 接口，并不知道服务端使用的是
 * {@code HelloServiceImpl}。接口方法真正在哪里执行，由 RPC 框架屏蔽。</p>
 *
 * <p>与 M3 的固定地址版本相比，这里不再写死 Provider 的 IP 和端口，
 * 也不再由业务代码手动组装序列化器、Netty 客户端、服务发现和负载均衡器。
 * 这些内部组件统一由 {@link RpcClientFactory} 管理。</p>
 */
public class ConsumerMain {

    /**
     * 启动客户端并完成一次远程调用。
     *
     * @param args 命令行参数，本示例暂时不使用
     */
    public static void main(String[] args) {
        /*
         * 第一步：创建 RPC 客户端工厂。
         *
         * 无参构造方法默认连接本机 ZooKeeper：127.0.0.1:2181。
         * 工厂内部已经准备好：
         * 1. ZooKeeper 服务发现；
         * 2. 轮询负载均衡；
         * 3. JSON 序列化；
         * 4. Netty 网络客户端；
         * 5. JDK 动态代理。
         *
         * try-with-resources 会在代码块结束时自动关闭 Netty 和 ZooKeeper 资源。
         */
        try (RpcClientFactory factory = new RpcClientFactory()) {

            /*
             * 第二步：只告诉框架“我需要 HelloService”。
             *
             * 框架会创建一个实现了 HelloService 的 JDK 动态代理对象。
             * helloService 不是 HelloServiceImpl，也不包含真正的问候业务逻辑。
             * 此时只是创建代理，还没有向 Provider 发送请求。
             */
            HelloService helloService = factory.createProxy(HelloService.class);

            /*
             * 第三步：像调用本地 Java 方法一样调用代理对象。
             *
             * 实际过程是：
             * 代理拦截方法
             * -> 生成 RpcRequest
             * -> 根据接口全限定名查询 ZooKeeper
             * -> 轮询选择一个 Provider
             * -> 建立或复用 Netty Channel
             * -> 服务端执行真正的方法
             * -> 客户端按 requestId 匹配 RpcResponse
             * -> 把响应数据作为 sayHello 的返回值。
             */
            /*
             * 连续调用四次，用来观察轮询负载均衡。
             *
             * 如果 ZooKeeper 中同时存在 8080 和 8081 两个 Provider，预期结果是：
             * 第 1 次 -> 8080
             * 第 2 次 -> 8081
             * 第 3 次 -> 8080
             * 第 4 次 -> 8081
             *
             * HelloServiceImpl 会在响应文字中写出自己的进程 ID。两个 Provider
             * 是两个不同的 Java 进程，因此可以根据进程 ID 判断本次请求由
             * 哪个 Provider 处理。
             */
            for (int callNumber = 1; callNumber <= 4; callNumber++) {
                String result = helloService.sayHello(
                        "world-" + callNumber
                );
                System.out.println(
                        "第 " + callNumber + " 次 RPC 调用结果：" + result
                );
            }
        }
    }
}
