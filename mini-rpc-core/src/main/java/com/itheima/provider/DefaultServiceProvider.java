package com.itheima.provider;

import com.itheima.common.RpcException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ServiceProvider} 的默认内存实现。
 *
 * <p>服务信息保存在当前服务端进程的内存中：</p>
 *
 * <pre>
 * key：接口全限定名，例如 com.itheima.example.api.HelloService
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
    private final Map<String, Object> services = new ConcurrentHashMap<>();

    /**
     * 注册一个服务实现对象。
     *
     * <p>注册之前会检查接口和实现对象是否有效，并确认实现对象确实实现了
     * 指定接口。相同接口不能重复注册，避免后注册的对象意外覆盖原对象。</p>
     */
    @Override
    public void registerService(Class<?> serviceInterface, Object service) {
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

        String serviceName = serviceInterface.getName();

        /*
         * putIfAbsent 表示“只有 key 不存在时才放入”。
         * 它把检查和写入作为一次原子操作，能够避免两个线程同时注册时互相覆盖。
         */
        Object existingService = services.putIfAbsent(serviceName, service);
        if (existingService != null) {
            throw new RpcException("服务已经注册，不能重复注册：" + serviceName);
        }
    }

    /**
     * 根据接口全限定名取得服务实现对象。
     */
    @Override
    public Object getService(String serviceName) {
        validateServiceName(serviceName);

        Object service = services.get(serviceName);
        if (service == null) {
            throw new RpcException("没有找到已注册的服务：" + serviceName);
        }
        return service;
    }

    /**
     * 根据接口全限定名移除服务。
     *
     * <p>如果服务本来就不存在，Map 的 remove 方法不会报错，因此重复移除是安全的。</p>
     */
    @Override
    public void removeService(String serviceName) {
        validateServiceName(serviceName);
        services.remove(serviceName);
    }

    /**
     * 检查服务名称是否可以用于查询或移除。
     *
     * <p>把公共检查提取到一个私有方法中，避免 getService 和 removeService
     * 重复编写相同判断。</p>
     *
     * @param serviceName 接口全限定名
     */
    private void validateServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new RpcException("服务名称不能为空");
        }
    }
}
