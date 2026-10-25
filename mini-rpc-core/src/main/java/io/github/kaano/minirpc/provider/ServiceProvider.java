package io.github.kaano.minirpc.provider;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;

/**
 * 服务端本地服务注册表的统一接口。
 *
 * <p>RPC 服务端收到请求后，会把接口名称、分组和版本组成 {@link ServiceKey}。
 * 本地注册表再根据这个完整身份找到真正负责执行方法的实现对象。</p>
 *
 * <pre>
 * ServiceKey(接口名, group, version)                  实现对象
 * (HelloService, default, 1.0)              ---&gt;   HelloServiceV1Impl
 * (HelloService, default, 2.0)              ---&gt;   HelloServiceV2Impl
 * </pre>
 *
 * <p>这里定义接口而不直接依赖 Map，是为了让上层服务端只关心“注册、查找、移除”
 * 三种能力。默认实现使用内存 Map，未来也可以替换实现而不修改上层代码。</p>
 */
public interface ServiceProvider {

    /**
     * 注册一个服务实现对象。
     *
     * <p>例如：</p>
     *
     * <pre>{@code
     * registerService(HelloService.class, new HelloServiceImpl());
     * }</pre>
     *
     * <p>这个兼容入口会自动补上默认 group/version，再委托给完整
     * {@link ServiceKey} 注册方法。</p>
     *
     * @param serviceInterface 对外提供服务的接口类型，不能为 null
     * @param service          真正执行方法的接口实现对象，不能为 null
     * @throws RpcException 参数无效，或该接口已经注册服务时抛出
     */
    default void registerService(
            Class<?> serviceInterface,
            Object service
    ) {
        registerService(
                ServiceKey.defaultService(serviceInterface),
                serviceInterface,
                service
        );
    }

    /**
     * 使用完整服务身份注册一个实现对象。
     *
     * <p>{@code serviceKey.interfaceName} 必须与 {@code serviceInterface}
     * 一致。保留接口类型参数是为了验证实现对象确实实现了对外契约，不能只凭
     * 一个字符串把任意对象放进服务表。</p>
     *
     * @param serviceKey       接口名、分组和版本组成的唯一身份
     * @param serviceInterface 对外提供服务的接口类型
     * @param service          真正执行方法的接口实现对象
     */
    void registerService(
            ServiceKey serviceKey,
            Class<?> serviceInterface,
            Object service
    );

    /**
     * 按默认 group/version 根据接口全限定名查找服务实现对象。
     *
     * <p>它保留给早期教学代码使用；网络请求处理已经改用完整 ServiceKey。</p>
     *
     * @param serviceName 接口全限定名，例如 io.github.kaano.minirpc.example.api.HelloService
     * @return 已经注册的服务实现对象
     * @throws RpcException serviceName 无效或没有找到对应服务时抛出
     */
    default Object getService(String serviceName) {
        return getService(ServiceKey.defaultService(serviceName));
    }

    /** 根据完整服务身份查找实现对象。 */
    Object getService(ServiceKey serviceKey);

    /**
     * 按默认 group/version 根据接口全限定名移除本地服务。
     *
     * <p>这个方法只会移除默认路由，不会影响同一接口的其他分组或版本。</p>
     *
     * @param serviceName 要移除的接口全限定名
     * @throws RpcException serviceName 无效时抛出
     */
    default void removeService(String serviceName) {
        removeService(ServiceKey.defaultService(serviceName));
    }

    /** 根据完整服务身份移除本地服务。 */
    void removeService(ServiceKey serviceKey);
}
