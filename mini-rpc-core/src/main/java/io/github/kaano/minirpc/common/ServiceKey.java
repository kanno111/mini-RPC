package io.github.kaano.minirpc.common;

import java.io.Serial;
import java.io.Serializable;

/**
 * RPC 服务在框架内部的唯一身份。
 *
 * <p>只有接口全限定名时，同一个接口只能注册一个实现。引入 group 和 version 后，
 * 同一个接口可以同时存在多份实现，例如：</p>
 *
 * <pre>
 * PaymentService + default + 1.0
 * PaymentService + default + 2.0
 * PaymentService + gray    + 2.0
 * </pre>
 *
 * <p>这个对象只描述“要找哪一个服务”，不包含 Provider 地址。当前阶段先把它用于
 * Provider 本地服务表；后续再复用到 ZooKeeper 路径和 Consumer 地址缓存中。</p>
 *
 * @param interfaceName 服务接口全限定名
 * @param group         业务分组
 * @param version       服务版本
 */
public record ServiceKey(
        String interfaceName,
        String group,
        String version
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 没有显式选择分组时使用的默认分组。 */
    public static final String DEFAULT_GROUP = "default";

    /** 没有显式选择版本时使用的默认版本。 */
    public static final String DEFAULT_VERSION = "1.0";

    /**
     * 创建不可变服务身份，并在进入 Map 之前统一清理和校验三个组成部分。
     *
     * <p>在构造阶段保证字段有效，后续注册、查询和移除就不需要分别处理
     * {@code null}、空字符串或首尾空格，也可以避免“注册时一个 key、查询时
     * 另一个 key”的隐蔽问题。</p>
     */
    public ServiceKey {
        interfaceName = requireText(interfaceName, "服务接口名称");
        group = requireText(group, "服务分组");
        version = requireText(version, "服务版本");
    }

    /** 为现有未配置路由信息的服务创建默认身份。 */
    public static ServiceKey defaultService(Class<?> serviceInterface) {
        if (serviceInterface == null) {
            throw new RpcException("服务接口不能为空");
        }
        return defaultService(serviceInterface.getName());
    }

    /** 为接口全限定名创建默认身份。 */
    public static ServiceKey defaultService(String interfaceName) {
        return new ServiceKey(
                interfaceName,
                DEFAULT_GROUP,
                DEFAULT_VERSION
        );
    }

    /**
     * 根据网络请求恢复服务身份。
     *
     * <p>早期请求没有 group 字段，version 也可能没有赋值。字段为 {@code null}
     * 时回退到默认值，使升级后的 Provider 仍能处理旧请求；显式传入空字符串则
     * 仍视为调用错误，避免错误配置悄悄落到默认服务。</p>
     */
    public static ServiceKey fromRequest(
            String interfaceName,
            String group,
            String version
    ) {
        return new ServiceKey(
                interfaceName,
                group == null ? DEFAULT_GROUP : group,
                version == null ? DEFAULT_VERSION : version
        );
    }

    /** 校验并返回去除首尾空格后的标识文本。 */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new RpcException(fieldName + "不能为空");
        }
        return value.trim();
    }
}
