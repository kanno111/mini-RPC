package com.itheima.example.provider;

import com.itheima.annotation.RpcService;
import com.itheima.example.api.HelloService;

/**
 * {@link HelloService} 在服务端的真正实现。
 *
 * <p>这个类只会被服务端创建和注册。客户端不会直接创建它，
 * 客户端拿到的是由 {@code RpcClientProxy} 生成的代理对象。</p>
 */
@RpcService(serviceInterface = HelloService.class)
public class HelloServiceImpl implements HelloService {

    /** 当前 Provider 的进程 ID，用于区分同时启动的多个服务实例。 */
    private final long providerProcessId;

    /**
     * 公开的无参构造方法，供 RPC 自动扫描器创建服务对象。
     *
     * <p>当前 mini-RPC 还没有 Spring 那样的依赖注入容器，因此扫描器暂时
     * 只能调用公开的无参构造方法。这里记录进程 ID，只是为了启动两个 Provider
     * 时能从响应中看出请求到达了不同的进程，与 RPC 核心逻辑无关。</p>
     */
    public HelloServiceImpl() {
        this.providerProcessId = ProcessHandle.current().pid();
    }

    /**
     * 真正执行业务逻辑的方法。
     *
     * <p>当服务端收到 sayHello 请求后，{@code RpcRequestHandler}
     * 最终会通过反射调用这里。</p>
     *
     * @param name 客户端通过 RPC 传来的名字
     * @return 将要通过 {@code RpcResponse} 返回给客户端的结果
     */
    @Override
    public String sayHello(String name) {
        return "你好，" + name + "！这是 Provider 进程 "
                + providerProcessId
                + " 返回的 RPC 响应。";
    }
}
