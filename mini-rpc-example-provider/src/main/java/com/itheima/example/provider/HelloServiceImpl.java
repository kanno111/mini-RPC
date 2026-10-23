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

    /** 由 Spring 通过构造方法注入的业务依赖。 */
    private final ProviderIdentity providerIdentity;

    /**
     * Spring 创建 HelloServiceImpl 时，会自动查找 ProviderIdentity Bean 并传入。
     *
     * <p>这个类故意不再提供无参构造方法，用来证明服务对象已经由 Spring
     * 创建，而不是由旧版 RpcServiceScanner 反射 new 出来。</p>
     */
    public HelloServiceImpl(ProviderIdentity providerIdentity) {
        this.providerIdentity = providerIdentity;
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
                + providerIdentity.getProcessId()
                + " 返回的 RPC 响应。";
    }
}
