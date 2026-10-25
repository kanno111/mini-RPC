package io.github.kaano.minirpc.example.api;

/**
 * 客户端和服务端共同遵守的服务接口，也叫“服务契约”。
 *
 * <p>客户端只需要依赖这个接口，不需要拿到服务端的实现类。
 * 客户端调用 {@link #sayHello(String)} 时，动态代理会把这次方法调用
 * 转换成 {@code RpcRequest}，再通过网络发送给服务端。</p>
 *
 * <p>服务端则负责提供这个接口的真正实现。因为两边使用的是同一个接口，
 * 所以它们才能对“方法名、参数类型和返回类型”达成一致。</p>
 */
public interface HelloService {

    /**
     * 根据传入的名字生成一句问候语。
     *
     * @param name 需要问候的人名
     * @return 服务端生成的问候语
     */
    String sayHello(String name);
}
