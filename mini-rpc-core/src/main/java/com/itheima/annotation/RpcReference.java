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
}
