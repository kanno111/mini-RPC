package com.itheima.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个字段需要注入 RPC 客户端代理。
 *
 * <p>这个注解用在 Consumer 一侧。例如：</p>
 *
 * <pre>{@code
 * @RpcReference
 * private HelloService helloService;
 * }</pre>
 *
 * <p>字段类型 {@code HelloService} 就是客户端想调用的服务接口。注入器会读取
 * 字段类型，为该接口创建 JDK 动态代理，再把代理对象写入字段。</p>
 *
 * <p>注解本身仍然只是一张标签。真正完成读取和赋值的是
 * {@code RpcReferenceInjector}。</p>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RpcReference {

    /**
     * 表示当前引用没有单独指定超时，应继续使用 Starter 的全局
     * {@code mini-rpc.request-timeout} 配置。
     */
    long USE_GLOBAL_TIMEOUT = -1L;

    /**
     * 当前远程引用等待一次响应的最长毫秒数。
     *
     * <p>默认值 {@link #USE_GLOBAL_TIMEOUT} 表示继承全局超时。显式配置时必须
     * 大于 0，例如 {@code @RpcReference(timeoutMillis = 750)}。超时只限制请求
     * 发出后等待响应的时间，不等同于 TCP 建立连接的超时。</p>
     */
    long timeoutMillis() default USE_GLOBAL_TIMEOUT;
}
