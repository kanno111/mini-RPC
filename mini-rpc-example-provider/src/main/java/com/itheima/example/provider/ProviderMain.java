package com.itheima.example.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 形式的 RPC Provider 启动入口。
 *
 * <p>这个类不再创建 RpcProvider、扫描服务或启动 Netty。它只负责启动 Spring：
 * mini-RPC Starter 会读取 application.yml、找到 @RpcService Bean、注册服务并
 * 启动网络服务。</p>
 */
@SpringBootApplication(proxyBeanMethods = false)
public class ProviderMain {

    private ProviderMain() {
    }

    public static void main(String[] args) {
        SpringApplication.run(ProviderMain.class, args);
    }
}
