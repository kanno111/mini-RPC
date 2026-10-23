package com.itheima.example.consumer;

import com.itheima.annotation.RpcReference;
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
     * 需要调用的远程服务。
     *
     * <p>这里不创建 HelloServiceImpl，也不手动调用 createProxy。字段开始时是
     * {@code null}，RpcClientFactory 会根据字段类型 HelloService 创建动态代理，
     * 再通过反射把代理写入这个字段。</p>
     */
    @RpcReference
    private HelloService helloService;

    /**
     * 启动客户端并完成一次远程调用。
     *
     * @param args 命令行参数，本示例暂时不使用
     */
    public static void main(String[] args) {
        ConsumerMain application = new ConsumerMain();

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
             * 第二步：扫描 application 对象中的 @RpcReference 字段并注入代理。
             *
             * 注入完成后，application.helloService 不再是 null，而是实现了
             * HelloService 接口的 JDK 动态代理。此时还没有发送网络请求。
             */
            factory.injectReferences(application);

            // 第三步：让已经完成注入的业务对象开始执行自己的业务逻辑。
            application.run();
        }
    }

    /**
     * 使用已经注入的 HelloService 代理执行业务逻辑。
     *
     * <p>调用 helloService.sayHello 时，动态代理会构造 RpcRequest、查询
     * ZooKeeper、选择 Provider、通过 Netty 发送请求，并把响应数据作为方法
     * 返回值。</p>
     */
    private void run() {
        /*
         * 连续调用四次，用来观察轮询负载均衡。
         *
         * 如果 ZooKeeper 中同时存在 8080 和 8081 两个 Provider，预期会在
         * 两个地址之间轮流选择。响应中的进程 ID 可以用来区分 Provider。
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
