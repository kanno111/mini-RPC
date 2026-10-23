package com.itheima.spring.autoconfigure.fixture;

import com.itheima.annotation.RpcService;

/**
 * 用于验证 {@code @RpcService} 类由 Spring 创建的测试服务。
 *
 * <p>这个类故意只有一个需要参数的构造方法，没有无参构造方法。如果测试能够
 * 成功取得这个 Bean，就能证明 Starter 只是把类注册给 Spring，真正的对象创建
 * 和依赖注入仍由 Spring 完成，而不是旧版扫描器直接反射调用无参构造方法。</p>
 */
@RpcService(serviceInterface = Runnable.class)
public class SpringManagedRpcService implements Runnable {

    /** 由测试 Spring 容器提供的依赖。 */
    private final String injectedValue;

    public SpringManagedRpcService(String injectedValue) {
        this.injectedValue = injectedValue;
    }

    public String getInjectedValue() {
        return injectedValue;
    }

    @Override
    public void run() {
        // 测试只关心 Bean 创建和构造器注入，不需要执行实际业务逻辑。
    }
}
