package com.itheima.example.provider;

import com.itheima.example.api.HelloService;

/**
 * {@link HelloService} 在服务端的真正实现。
 *
 * <p>这个类只会被服务端创建和注册。客户端不会直接创建它，
 * 客户端拿到的是由 {@code RpcClientProxy} 生成的代理对象。</p>
 */
public class HelloServiceImpl implements HelloService {

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
        return "你好，" + name + "！这是来自 RPC 服务端的响应。";
    }
}
