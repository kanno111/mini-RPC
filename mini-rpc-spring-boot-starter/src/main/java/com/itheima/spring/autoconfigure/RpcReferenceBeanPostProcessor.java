package com.itheima.spring.autoconfigure;

import com.itheima.annotation.RpcReference;
import com.itheima.client.RpcClientFactory;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;

import java.lang.reflect.Field;

/**
 * 在 Spring 创建业务 Bean 的过程中，自动处理 {@link RpcReference} 字段。
 *
 * <p>可以先把 BeanPostProcessor 理解成 Spring 对象生产线上的检查站：</p>
 *
 * <pre>
 * 调用构造方法创建普通对象
 *          ↓
 * RpcReferenceBeanPostProcessor 检查字段
 *          ↓
 * 执行初始化方法（例如 @PostConstruct）
 *          ↓
 * Bean 可以交给业务代码使用
 * </pre>
 *
 * <p>本类使用 {@link InstantiationAwareBeanPostProcessor#postProcessProperties}
 * 这个“属性填充阶段”的扩展点。这样 RPC 代理会在 Bean 初始化之前写入字段，
 * 业务对象以后在初始化方法中访问远程服务时，字段已经不是 {@code null}。</p>
 */
public final class RpcReferenceBeanPostProcessor
        implements InstantiationAwareBeanPostProcessor {

    /**
     * 延迟取得客户端工厂，而不是在后置处理器构造时直接创建工厂。
     *
     * <p>如果整个应用没有任何 {@code @RpcReference}，就永远不会调用
     * {@code getObject()}，因此不会建立无用的 ZooKeeper 连接。</p>
     */
    private final ObjectProvider<RpcClientFactory> clientFactoryProvider;

    public RpcReferenceBeanPostProcessor(
            ObjectProvider<RpcClientFactory> clientFactoryProvider
    ) {
        this.clientFactoryProvider = clientFactoryProvider;
    }

    /**
     * 在 Spring 为 Bean 填充普通属性时，检查并注入 RPC 引用。
     *
     * @param propertyValues Spring 原本准备填充的属性，必须原样返回
     * @param bean           当前正在创建的业务对象
     * @param beanName       当前 Bean 在 Spring 容器中的名称
     * @return 原来的属性集合，让 Spring 继续后面的创建步骤
     */
    @Override
    public PropertyValues postProcessProperties(
            PropertyValues propertyValues,
            Object bean,
            String beanName
    ) {
        /*
         * 一定要先检查注解，再取得客户端工厂。如果把 getObject() 写在检查之前，
         * Spring 创建任意普通 Bean 都会提前连接 ZooKeeper，@Lazy 就失去意义。
         */
        if (containsRpcReference(bean.getClass())) {
            RpcClientFactory clientFactory =
                    clientFactoryProvider.getObject();
            clientFactory.injectReferences(bean);
        }

        return propertyValues;
    }

    /**
     * 检查当前类及其父类是否声明了 {@link RpcReference} 字段。
     *
     * <p>这里仅做一次很轻量的“是否需要处理”判断。真正的字段规则校验、代理
     * 创建和反射赋值仍由 core 中的 RpcReferenceInjector 统一负责。</p>
     */
    private boolean containsRpcReference(Class<?> beanClass) {
        Class<?> currentClass = beanClass;

        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(RpcReference.class)) {
                    return true;
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return false;
    }
}
