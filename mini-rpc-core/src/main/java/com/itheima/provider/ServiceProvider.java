package com.itheima.provider;

import com.itheima.common.RpcException;

/**
 * 服务端本地服务注册表的统一接口。
 *
 * <p>RPC 服务端收到请求后，只能从 {@code RpcRequest} 中得到接口名称，
 * 例如 {@code com.itheima.example.api.HelloService}。它还需要通过本地注册表，
 * 找到真正负责执行方法的实现对象，例如 {@code HelloServiceImpl}。</p>
 *
 * <pre>
 * 接口全限定名                                      实现对象
 * com.itheima.example.api.HelloService   ---&gt;   HelloServiceImpl
 * </pre>
 *
 * <p>这里定义接口而不直接依赖 Map，是为了让上层服务端只关心“注册、查找、移除”
 * 三种能力。M1 的默认实现会使用内存中的 Map，未来也可以替换实现而不修改上层代码。</p>
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
     * <p>实现类会使用 {@code serviceInterface.getName()} 取得接口全限定名，
     * 并把它作为本地 Map 的 key。</p>
     *
     * @param serviceInterface 对外提供服务的接口类型，不能为 null
     * @param service          真正执行方法的接口实现对象，不能为 null
     * @throws RpcException 参数无效，或该接口已经注册服务时抛出
     */
    void registerService(Class<?> serviceInterface, Object service);

    /**
     * 根据接口全限定名查找服务实现对象。
     *
     * <p>服务端处理请求时，会把 {@code RpcRequest.interfaceName} 传给这个方法。</p>
     *
     * @param serviceName 接口全限定名，例如 com.itheima.example.api.HelloService
     * @return 已经注册的服务实现对象
     * @throws RpcException serviceName 无效或没有找到对应服务时抛出
     */
    Object getService(String serviceName);

    /**
     * 根据接口全限定名移除本地服务。
     *
     * <p>M1 中主要用于服务端停止时清理本地注册信息。</p>
     *
     * @param serviceName 要移除的接口全限定名
     * @throws RpcException serviceName 无效时抛出
     */
    void removeService(String serviceName);
}
