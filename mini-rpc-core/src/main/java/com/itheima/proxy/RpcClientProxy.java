package com.itheima.proxy;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.transport.client.RpcClient;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 使用 JDK 动态代理，把普通 Java 接口调用转换成 RPC 请求。
 *
 * <p>调用方拿到的不是服务实现对象，而是 JDK 在运行时创建的代理对象。
 * 当调用代理对象的方法时，代理会收集接口名、方法名和参数等信息，组装
 * {@link RpcRequest}，再交给 {@link RpcClient} 发送。</p>
 *
 * <pre>
 * helloService.sayHello("world")
 *              ↓ 动态代理拦截
 * RpcRequest(interfaceName, methodName, parameterTypes, parameters...)
 *              ↓
 * RpcClient.sendRequest(request)
 *              ↓
 * RpcResponse.data
 *              ↓
 * 调用方得到方法返回值
 * </pre>
 */
public class RpcClientProxy {

    /**
     * 为当前 JVM 中的每次 RPC 调用生成递增请求编号。
     * AtomicLong 能保证多个线程同时发起请求时不会得到重复编号。
     */
    private static final AtomicLong REQUEST_ID_GENERATOR = new AtomicLong();

    /** 真正负责网络收发的客户端实现。 */
    private final RpcClient rpcClient;

    /** 当前代理调用的服务版本；M1 只携带该字段，暂不按版本路由。 */
    private final String version;

    /**
     * 当前引用单独指定的响应超时；为 {@code null} 时使用 RpcClient 的全局默认值。
     */
    private final Duration requestTimeoutOverride;

    /**
     * 使用默认服务版本 1.0 创建代理工厂。
     *
     * @param rpcClient RPC 网络客户端
     */
    public RpcClientProxy(RpcClient rpcClient) {
        this(rpcClient, "1.0", null);
    }

    /**
     * 使用指定服务版本创建代理工厂。
     *
     * @param rpcClient RPC 网络客户端，不能为 {@code null}
     * @param version   服务版本，不能为空
     */
    public RpcClientProxy(RpcClient rpcClient, String version) {
        this(rpcClient, version, null);
    }

    /**
     * 使用指定服务版本和可选的单引用超时创建代理工厂。
     *
     * <p>{@code requestTimeoutOverride == null} 表示继续使用客户端全局超时；
     * 非空值必须至少为 1 毫秒。</p>
     *
     * @param rpcClient             RPC 网络客户端
     * @param version               服务版本
     * @param requestTimeoutOverride 当前引用单独指定的响应超时，可为 {@code null}
     */
    public RpcClientProxy(
            RpcClient rpcClient,
            String version,
            Duration requestTimeoutOverride
    ) {
        if (rpcClient == null) {
            throw new RpcException("RpcClient 不能为空");
        }
        if (version == null || version.isBlank()) {
            throw new RpcException("服务版本不能为空");
        }

        this.rpcClient = rpcClient;
        this.version = version;
        this.requestTimeoutOverride = validateRequestTimeout(
                requestTimeoutOverride
        );
    }

    /** 校验单引用超时；null 是“继承全局配置”的合法状态。 */
    private Duration validateRequestTimeout(Duration requestTimeout) {
        if (requestTimeout == null) {
            return null;
        }

        final long timeoutMillis;
        try {
            timeoutMillis = requestTimeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new RpcException("RPC 单引用超时时间过大", exception);
        }
        if (timeoutMillis <= 0) {
            throw new RpcException("RPC 单引用超时时间不能小于 1 毫秒");
        }
        return requestTimeout;
    }

    /**
     * 为一个服务接口创建动态代理对象。
     *
     * <p>返回对象在类型上实现了 {@code serviceInterface}，但没有编写对应的
     * 实现类。方法调用会被 JDK Proxy 转交给下面的拦截逻辑。</p>
     *
     * @param serviceInterface 客户端希望调用的服务接口
     * @param <T>              服务接口类型
     * @return 实现了该接口的动态代理对象
     */
    public <T> T createProxy(Class<T> serviceInterface) {
        if (serviceInterface == null) {
            throw new RpcException("服务接口不能为空");
        }
        if (!serviceInterface.isInterface()) {
            throw new RpcException("只能为接口创建 RPC 代理：" + serviceInterface.getName());
        }

        Object proxy = Proxy.newProxyInstance(
                serviceInterface.getClassLoader(),
                new Class<?>[]{serviceInterface},
                (proxyObject, method, arguments) -> invoke(
                        proxyObject,
                        serviceInterface,
                        method,
                        arguments
                )
        );

        return serviceInterface.cast(proxy);
    }

    /**
     * 拦截一次代理方法调用。
     */
    private Object invoke(
            Object proxyObject,
            Class<?> serviceInterface,
            Method method,
            Object[] arguments
    ) {
        /*
         * toString、hashCode 和 equals 来自 Object，不是远程服务方法。
         * 它们必须在客户端本地处理，否则打印代理对象也会错误地发起 RPC。
         */
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxyObject, serviceInterface, method, arguments);
        }

        Object[] parameters = arguments == null
                ? new Object[0]
                : arguments;

        RpcRequest request = RpcRequest.builder()
                .requestId(REQUEST_ID_GENERATOR.incrementAndGet())
                .interfaceName(serviceInterface.getName())
                .methodName(method.getName())
                .parameterTypes(method.getParameterTypes())
                .parameters(parameters)
                .version(version)
                .build();

        RpcResponse response = requestTimeoutOverride == null
                ? rpcClient.sendRequest(request)
                : rpcClient.sendRequest(request, requestTimeoutOverride);
        if (response == null) {
            throw new RpcException("RpcClient 返回的 RpcResponse 不能为空");
        }

        if (response.getCode() != RpcResponse.SUCCESS_CODE) {
            throw createRemoteException(response);
        }

        /*
         * Proxy 会按照接口方法的声明处理返回值。方法返回基本类型时，JDK 会把
         * data 中的包装类型自动拆箱；方法返回 void 时，data 为 null。
         */
        return response.getData();
    }

    /**
     * 在客户端本地处理 Object 定义的基础方法。
     */
    private Object invokeObjectMethod(
            Object proxyObject,
            Class<?> serviceInterface,
            Method method,
            Object[] arguments
    ) {
        return switch (method.getName()) {
            case "toString" -> "RpcClientProxy(" + serviceInterface.getName() + ")";
            case "hashCode" -> System.identityHashCode(proxyObject);
            case "equals" -> proxyObject == arguments[0];
            default -> throw new RpcException("不支持的 Object 方法：" + method.getName());
        };
    }

    /**
     * 把服务端失败响应转换为客户端可以抛出的统一 RpcException。
     */
    private RpcException createRemoteException(RpcResponse response) {
        String exceptionType = response.getExceptionType() == null
                ? "未知异常"
                : response.getExceptionType();
        String exceptionMessage = response.getExceptionMessage() == null
                ? response.getMessage()
                : response.getExceptionMessage();

        return new RpcException(
                "远程调用失败，异常类型：" + exceptionType
                        + "，异常信息：" + exceptionMessage
        );
    }
}
