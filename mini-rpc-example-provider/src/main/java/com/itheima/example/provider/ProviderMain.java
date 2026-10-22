package com.itheima.example.provider;

import com.itheima.server.RpcProvider;

/**
 * M4 阶段的 RPC 服务端启动入口。
 *
 * <p>这个类现在只保留业务启动所需的信息：监听端口和需要暴露的服务实现。
 * 本地服务表、ZooKeeper、JSON 序列化和 Netty 等框架细节，都已经封装在
 * {@link RpcProvider} 内部。</p>
 */
public class ProviderMain {

    /** 启动时没有传入端口参数，就使用 8080 作为默认端口。 */
    private static final int DEFAULT_RPC_PORT = 8080;

    /**
     * 启动服务端。
     *
     * @param args 可选的第一个参数是 Provider 监听端口，例如 {@code 8081}
     */
    public static void main(String[] args) {
        /*
         * 同一个端口不能同时被两个进程监听，因此测试多 Provider 时必须让
         * 每个进程使用不同端口：
         *
         * Provider-8080 的程序实参：8080
         * Provider-8081 的程序实参：8081
         *
         * 没有填写程序实参时仍使用默认端口 8080，方便单实例测试。
         */
        int rpcPort = resolveRpcPort(args);

        /*
         * RpcProvider 是服务端的高层入口。构造方法内部已经准备好：
         * 1. 本地服务表；
         * 2. 请求反射处理器；
         * 3. JSON 序列化器；
         * 4. Netty 服务端；
         * 5. ZooKeeper 服务注册。
         *
         * try-with-resources 会在服务停止或启动失败时统一释放所有资源。
         */
        try (RpcProvider provider = new RpcProvider(rpcPort)) {
            /*
             * ProviderMain.class 在这里是“包定位类”。框架会取得它所在的
             * com.itheima.example.provider 包，然后扫描这个包及其子包。
             *
             * 扫描到 HelloServiceImpl 上的 @RpcService 后，框架会自动创建
             * HelloServiceImpl 对象并注册服务，不再需要手写 registerService。
             */
            provider.scanServices(ProviderMain.class);

            // 启动 Netty，并阻塞主线程直到 Provider 被关闭。
            provider.start();
        }
    }

    /**
     * 从启动参数中取得 Provider 要监听的端口。
     *
     * <p>IDEA 中“程序实参”填写的内容会进入 {@code args} 数组。例如填写
     * {@code 8081} 后，{@code args[0]} 就是字符串 {@code "8081"}。</p>
     *
     * @param args main 方法收到的程序参数
     * @return 校验后的端口号
     */
    private static int resolveRpcPort(String[] args) {
        if (args == null || args.length == 0) {
            return DEFAULT_RPC_PORT;
        }

        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "ProviderMain 只接受一个程序参数：监听端口"
            );
        }

        String portText = args[0].trim();
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        "Provider 端口必须在 1 到 65535 之间：" + port
                );
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Provider 端口必须是整数，例如 8080；当前输入：" + portText,
                    exception
            );
        }
    }
}
