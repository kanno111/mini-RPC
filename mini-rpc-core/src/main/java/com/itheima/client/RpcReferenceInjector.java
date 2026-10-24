package com.itheima.client;

import com.itheima.annotation.RpcReference;
import com.itheima.common.RpcException;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 RPC 动态代理注入带有 {@link RpcReference} 注解的字段。
 *
 * <p>这个类处理的是“已有对象中的字段”，与服务端扫描器的职责不同：</p>
 *
 * <ul>
 *     <li>服务端扫描器负责找到 {@code @RpcService} 类并创建服务对象；</li>
 *     <li>客户端注入器负责检查一个已经存在的对象，并为其中的
 *     {@code @RpcReference} 字段赋值。</li>
 * </ul>
 */
final class RpcReferenceInjector {

    /**
     * 注入器只需要“按接口和可选超时创建代理”，不需要知道工厂怎样管理网络资源。
     */
    private final ProxyCreator proxyCreator;

    /**
     * 创建字段注入器。
     *
     * @param clientFactory 已经组装好网络客户端和代理工厂的客户端入口
     */
    RpcReferenceInjector(RpcClientFactory clientFactory) {
        if (clientFactory == null) {
            throw new RpcException("RpcClientFactory 不能为空");
        }
        this.proxyCreator = (serviceInterface, requestTimeout) ->
                requestTimeout == null
                        ? clientFactory.createProxy(serviceInterface)
                        : clientFactory.createProxy(
                                serviceInterface,
                                requestTimeout
                        );
    }

    /**
     * 仅供同包测试替换“创建代理”动作，不需要模拟 final RpcClientFactory，也不会
     * 为测试连接 ZooKeeper。生产代码仍通过上面的构造方法使用真正客户端工厂。
     */
    RpcReferenceInjector(ProxyCreator proxyCreator) {
        if (proxyCreator == null) {
            throw new RpcException("RPC 代理创建器不能为空");
        }
        this.proxyCreator = proxyCreator;
    }

    /**
     * 扫描目标对象中的字段并注入 RPC 代理。
     *
     * <p>不仅检查当前类，还会继续检查它的父类。这样以后业务类继承某个
     * 基础类时，基础类中声明的 RPC 引用也可以得到注入。</p>
     *
     * @param target 需要注入 RPC 引用的业务对象
     */
    void inject(Object target) {
        if (target == null) {
            throw new RpcException("需要注入 RPC 引用的目标对象不能为空");
        }

        List<Field> referenceFields = findReferenceFields(
                target.getClass()
        );

        /*
         * 先校验所有字段，再真正赋值。这样常见的注解使用错误可以在创建任何
         * 代理之前被发现，避免对象只完成一半注入。
         */
        for (Field field : referenceFields) {
            validateReferenceField(target, field);
        }

        for (Field field : referenceFields) {
            Object proxy = createProxy(field);
            setFieldValue(target, field, proxy);
        }
    }

    /** 根据注解是否覆盖超时，选择全局默认代理或单引用超时代理。 */
    private Object createProxy(Field field) {
        long timeoutMillis = field.getAnnotation(RpcReference.class)
                .timeoutMillis();
        if (timeoutMillis == RpcReference.USE_GLOBAL_TIMEOUT) {
            return proxyCreator.createProxy(field.getType(), null);
        }
        return proxyCreator.createProxy(
                field.getType(),
                Duration.ofMillis(timeoutMillis)
        );
    }

    /** 查找当前类及其父类中所有带有 {@link RpcReference} 的字段。 */
    private List<Field> findReferenceFields(Class<?> targetClass) {
        List<Field> fields = new ArrayList<>();
        Class<?> currentClass = targetClass;

        while (currentClass != null && currentClass != Object.class) {
            for (Field field : currentClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(RpcReference.class)) {
                    fields.add(field);
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        return fields;
    }

    /**
     * 校验被标记字段是否适合注入动态代理。
     *
     * <p>JDK 动态代理只能代理接口，所以字段类型必须是接口。静态字段属于类，
     * 不属于当前业务对象；final 字段在对象构造完成后不应该再被修改，因此这
     * 两类字段也不允许注入。</p>
     */
    private void validateReferenceField(Object target, Field field) {
        int modifiers = field.getModifiers();
        String fieldDescription = describeField(field);

        if (Modifier.isStatic(modifiers)) {
            throw new RpcException(
                    "@RpcReference 不能标记 static 字段："
                            + fieldDescription
            );
        }
        if (Modifier.isFinal(modifiers)) {
            throw new RpcException(
                    "@RpcReference 不能标记 final 字段："
                            + fieldDescription
            );
        }
        if (!field.getType().isInterface()) {
            throw new RpcException(
                    "@RpcReference 字段类型必须是接口："
                            + fieldDescription
            );
        }

        long timeoutMillis = field.getAnnotation(RpcReference.class)
                .timeoutMillis();
        if (timeoutMillis != RpcReference.USE_GLOBAL_TIMEOUT
                && timeoutMillis <= 0) {
            throw new RpcException(
                    "@RpcReference timeoutMillis 必须大于 0，"
                            + "或使用默认值 -1："
                            + fieldDescription
            );
        }

        try {
            if (!field.canAccess(target) && !field.trySetAccessible()) {
                throw new RpcException(
                        "无法访问 @RpcReference 字段："
                                + fieldDescription
                );
            }

            if (field.get(target) != null) {
                throw new RpcException(
                        "@RpcReference 字段已经有值，不能覆盖："
                                + fieldDescription
                );
            }
        } catch (IllegalAccessException exception) {
            throw new RpcException(
                    "读取 @RpcReference 字段失败："
                            + fieldDescription,
                    exception
            );
        }
    }

    /** 使用反射把动态代理写入业务对象的字段。 */
    private void setFieldValue(
            Object target,
            Field field,
            Object proxy
    ) {
        try {
            field.set(target, proxy);
        } catch (IllegalAccessException exception) {
            throw new RpcException(
                    "注入 RPC 动态代理失败：" + describeField(field),
                    exception
            );
        }
    }

    /** 生成方便定位错误的“类全限定名.字段名”文本。 */
    private String describeField(Field field) {
        return field.getDeclaringClass().getName()
                + "."
                + field.getName();
    }

    /** 注入器与完整客户端工厂之间的最小内部边界。 */
    @FunctionalInterface
    interface ProxyCreator {
        Object createProxy(
                Class<?> serviceInterface,
                Duration requestTimeout
        );
    }
}
