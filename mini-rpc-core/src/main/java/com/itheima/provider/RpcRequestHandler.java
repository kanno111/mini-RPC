package com.itheima.provider;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 服务端的 RPC 请求执行器。
 *
 * <p>网络层把字节反序列化成 {@link RpcRequest} 后，会把请求交给这个类。
 * 它负责找到本地服务对象、通过反射执行目标方法，并把结果包装成
 * {@link RpcResponse}。这个类不负责 Socket 读写。</p>
 */
public class RpcRequestHandler {

    /** 用于根据请求中的接口名查找已经注册的服务实现对象。 */
    private final ServiceProvider serviceProvider;

    /**
     * 创建请求执行器。
     *
     * @param serviceProvider 服务端本地服务注册表，不能为 {@code null}
     */
    public RpcRequestHandler(ServiceProvider serviceProvider) {
        if (serviceProvider == null) {
            throw new RpcException("ServiceProvider 不能为空");
        }
        this.serviceProvider = serviceProvider;
    }

    /**
     * 执行一条 RPC 请求并返回响应。
     *
     * <p>执行成功时，方法返回值会放入 {@code RpcResponse.data}；执行失败时，
     * 异常类型和异常消息会放入失败响应。无论成功还是失败，响应都会携带
     * 与请求相同的 requestId。</p>
     *
     * @param request 已经反序列化的 RPC 请求，不能为 {@code null}
     * @return 成功或失败的 RPC 响应
     * @throws RpcException request 为 {@code null}，无法取得请求编号时抛出
     */
    public RpcResponse handle(RpcRequest request) {
        if (request == null) {
            throw new RpcException("RpcRequest 不能为空");
        }

        long requestId = request.getRequestId();

        long deadlineEpochMillis = request.getDeadlineEpochMillis();
        long currentEpochMillis = System.currentTimeMillis();
        if (deadlineEpochMillis > 0
                && currentEpochMillis >= deadlineEpochMillis) {
            /*
             * Consumer 已经没有必要继续等待这次调用。这里必须放在服务查找和反射
             * 执行之前，确保过期请求不会产生数据库写入等业务副作用。
             */
            return RpcResponse.failure(
                    requestId,
                    new RpcException(
                            "RPC 请求在 Provider 执行前已超过 deadline，requestId："
                                    + requestId
                                    + "，deadline：" + deadlineEpochMillis
                                    + "，当前时间：" + currentEpochMillis
                    )
            );
        }

        try {
            // 第一步：根据接口全限定名找到真正的服务实现对象。
            Object service = serviceProvider.getService(request.getInterfaceName());

            /*
             * 无参数方法应使用空数组。这里兼容手工构造请求时传入 null 的情况，
             * 避免反射 API 因为数组为空指针而失败。
             */
            Class<?>[] parameterTypes = request.getParameterTypes() == null
                    ? new Class<?>[0]
                    : request.getParameterTypes();
            Object[] parameters = request.getParameters() == null
                    ? new Object[0]
                    : request.getParameters();

            /*
             * 第二步：接口名在这里用于取得接口的类型信息，而注册表提供实现对象。
             * 从接口上查找方法，可以防止客户端调用实现类额外公开但接口未声明的方法。
             */
            Class<?> serviceInterface = Class.forName(request.getInterfaceName());
            Method method = serviceInterface.getMethod(
                    request.getMethodName(),
                    parameterTypes
            );

            // 第三步：在服务实现对象上执行方法，并传入客户端提供的参数值。
            Object result = method.invoke(service, parameters);

            // void 方法的 result 是 null，也属于执行成功。
            return RpcResponse.success(requestId, result);
        } catch (InvocationTargetException exception) {
            /*
             * 目标方法自己抛出的异常会被反射包装成 InvocationTargetException。
             * 对客户端有意义的是内部的真实异常，因此这里取出 cause。
             */
            Throwable cause = exception.getCause() == null
                    ? exception
                    : exception.getCause();
            return RpcResponse.failure(requestId, cause);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            /*
             * 找不到方法、无法访问方法、服务未注册或请求参数不正确等问题，
             * 都转换成失败响应，让客户端能够收到明确结果。
             */
            return RpcResponse.failure(requestId, exception);
        }
    }
}
