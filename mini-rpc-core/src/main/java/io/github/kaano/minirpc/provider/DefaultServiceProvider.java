package io.github.kaano.minirpc.provider;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.ServiceKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ServiceProvider} 的默认内存实现。
 *
 * <p>服务信息保存在当前服务端进程的内存中：</p>
 *
 * <pre>
 * key：ServiceKey，例如 (HelloService, default, 1.0)
 * value：接口实现对象，例如 HelloServiceImpl 对象
 * </pre>
 *
 * <p>M1 的 Socket 服务端会使用多个线程处理请求，因此这里使用
 * {@link ConcurrentHashMap}，保证多个线程同时查询或修改注册表时的基本安全。</p>
 */
public class DefaultServiceProvider implements ServiceProvider {

    /**
     * 当前服务端已经注册的所有本地服务。
     *
     * <p>字段声明为 {@link Map}，表示上层逻辑只依赖 Map 的通用能力；
     * 创建对象时使用 ConcurrentHashMap，提供线程安全的具体实现。</p>
     */
    private final Map<ServiceKey, Object> services = new ConcurrentHashMap<>();

    /**
     * 注册一个服务实现对象。
     *
     * <p>注册之前会检查接口和实现对象是否有效，并确认实现对象确实实现了
     * 指定接口。完全相同的 ServiceKey 不能重复注册；同一接口的不同 group/version
     * 可以并存。</p>
     */
    @Override
    public void registerService(
            ServiceKey serviceKey,
            Class<?> serviceInterface,
            Object service
    ) {
        if (serviceKey == null) {
            throw new RpcException("ServiceKey 不能为空");
        }
        if (serviceInterface == null) {
            throw new RpcException("服务接口不能为空");
        }
        if (!serviceInterface.isInterface()) {
            throw new RpcException("注册服务时必须提供接口类型：" + serviceInterface.getName());
        }
        if (service == null) {
            throw new RpcException("服务实现对象不能为空");
        }
        if (!serviceInterface.isInstance(service)) {
            throw new RpcException(
                    "服务实现对象没有实现指定接口：" + serviceInterface.getName()
            );
        }
        if (!serviceKey.interfaceName().equals(serviceInterface.getName())) {
            throw new RpcException(
                    "ServiceKey 中的接口名与注册接口不一致："
                            + serviceKey.interfaceName()
                            + " != "
                            + serviceInterface.getName()
            );
        }

        /*
         * putIfAbsent 表示“只有 key 不存在时才放入”。
         * 它把检查和写入作为一次原子操作，能够避免两个线程同时注册时互相覆盖。
         */
        Object existingService = services.putIfAbsent(serviceKey, service);
        if (existingService != null) {
            throw new RpcException(
                    "服务已经注册，不能重复注册：" + serviceKey
            );
        }
    }

    /**
     * 根据完整服务身份取得服务实现对象。
     */
    @Override
    public Object getService(ServiceKey serviceKey) {
        validateServiceKey(serviceKey);

        Object service = services.get(serviceKey);
        if (service == null) {
            throw new RpcException("没有找到已注册的服务：" + serviceKey);
        }
        return service;
    }

    /**
     * 根据完整服务身份移除服务。
     *
     * <p>如果服务本来就不存在，Map 的 remove 方法不会报错，因此重复移除是安全的。</p>
     */
    @Override
    public void removeService(ServiceKey serviceKey) {
        validateServiceKey(serviceKey);
        services.remove(serviceKey);
    }

    /**
     * 检查服务身份是否可以用于查询或移除。
     *
     * <p>把公共检查提取到一个私有方法中，避免 getService 和 removeService
     * 重复编写相同判断。</p>
     *
     * @param serviceKey 完整服务身份
     */
    private void validateServiceKey(ServiceKey serviceKey) {
        if (serviceKey == null) {
            throw new RpcException("ServiceKey 不能为空");
        }
    }
}
