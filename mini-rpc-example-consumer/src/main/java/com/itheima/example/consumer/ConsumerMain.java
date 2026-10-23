package com.itheima.example.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RPC Consumer 的 Spring Boot 启动入口。
 *
 * <p>启动类只负责启动 Spring 容器，不再同时承担远程服务注入和演示调用。
 * 这种职责拆分更接近实际项目：启动类保持稳定，RPC 引用写在真正需要它的
 * Controller、Service 或 Runner 中。</p>
 */
@SpringBootApplication(proxyBeanMethods = false)
public class ConsumerMain {

    private ConsumerMain() {
    }

    public static void main(String[] args) {
        SpringApplication.run(ConsumerMain.class, args);
    }
}
