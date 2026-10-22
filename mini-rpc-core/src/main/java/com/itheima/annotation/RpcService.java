package com.itheima.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类是可以被远程调用的 RPC 服务实现。
 *
 * <p>注解本身只是一张“标签”，真正执行工作的仍然是 {@code RpcProvider}：
 * 它扫描业务包、找到带有这张标签的类、创建对象，并读取标签中指定的接口
 * 完成注册。</p>
 *
 * <p>使用示例：</p>
 *
 * <pre>{@code
 * @RpcService(serviceInterface = HelloService.class)
 * public class HelloServiceImpl implements HelloService {
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcService {

    /**
     * 这个实现类准备通过 RPC 暴露的服务接口。
     *
     * <p>一个 Java 类可以同时实现多个接口，仅仅查看 {@code implements}
     * 无法保证框架知道应该暴露哪一个。因此在注解里明确指定接口，扫描器
     * 后续就可以准确执行：</p>
     *
     * <pre>{@code
     * rpcProvider.registerService(这里指定的接口, 当前实现对象);
     * }</pre>
     *
     * @return 对客户端公开的服务接口类型
     */
    Class<?> serviceInterface();
}
