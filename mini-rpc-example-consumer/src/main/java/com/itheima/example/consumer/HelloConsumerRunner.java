package com.itheima.example.consumer;

import com.itheima.annotation.RpcReference;
import com.itheima.example.api.HelloService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Consumer 启动后的 RPC 调用演示。
 *
 * <p>在真实 Web 项目中，这个类通常会被 Controller 或业务 Service 替代。
 * 无论对象是哪一种，只要它由 Spring 管理，mini-RPC Starter 就会在初始化前
 * 找到 {@link RpcReference} 字段并注入远程代理。</p>
 */
@Component
public class HelloConsumerRunner implements CommandLineRunner {

    /**
     * 这里保存的不是服务端实现对象，而是 mini-RPC 创建的 JDK 动态代理。
     * 调用它的方法时，代理会把本地方法调用转换成网络请求。
     */
    @RpcReference
    private HelloService helloService;

    /**
     * Spring 完成所有 Bean 创建和 RPC 代理注入后，自动执行本方法。
     *
     * <p>连续调用四次是为了方便以后同时启动两个 Provider 时观察轮询结果，
     * 并不是使用 mini-RPC 的强制要求。</p>
     */
    @Override
    public void run(String... args) {
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
