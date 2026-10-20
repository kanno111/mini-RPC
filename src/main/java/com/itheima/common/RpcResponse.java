package com.itheima.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 一次 RPC 方法调用的响应数据。
 *
 * <p>服务端执行完客户端指定的方法后，需要把执行结果包装成这个对象，
 * 再发送回客户端。响应可能表示调用成功，也可能表示调用失败。</p>
 *
 * <p>响应中的 {@code requestId} 必须与请求中的编号相同。客户端可能同时
 * 发出多次调用，需要依靠这个编号把每个响应交给对应的请求。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcResponse implements Serializable {

    /** JDK 序列化使用的类版本号。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** 表示远程方法调用成功。 */
    public static final int SUCCESS_CODE = 0;

    /** 表示远程方法调用失败。 */
    public static final int FAILURE_CODE = 1;

    /**
     * 对应请求的唯一编号。
     *
     * <p>服务端会把 {@link RpcRequest#getRequestId()} 原样复制到这里。</p>
     */
    private long requestId;

    /**
     * 响应状态码，用来快速判断调用是否成功。
     *
     * <p>{@link #SUCCESS_CODE} 表示成功，{@link #FAILURE_CODE} 表示失败。</p>
     */
    private int code;

    /**
     * 对本次调用结果的简短说明，例如“调用成功”或“服务方法执行失败”。
     */
    private String message;

    /**
     * 服务方法成功执行后的返回值。
     *
     * <p>不同方法可能返回不同类型，因此这里使用 {@link Object}。
     * 如果目标方法的返回类型是 {@code void}，或者调用失败，该字段可以为 {@code null}。</p>
     */
    private Object data;

    /**
     * 服务端异常的完整类名，例如 {@code java.lang.IllegalArgumentException}。
     *
     * <p>调用成功时该字段为 {@code null}。这里只传递异常类型的名称，
     * 不直接传输服务端的异常对象，从而降低客户端与服务端异常类之间的依赖。</p>
     */
    private String exceptionType;

    /**
     * 服务端异常的详细说明，例如“用户 ID 不能为空”。
     *
     * <p>调用成功时该字段为 {@code null}。</p>
     */
    private String exceptionMessage;

    /**
     * 创建一个调用成功的响应。
     *
     * <p>调用方只需要提供请求编号和方法返回值。成功状态码、提示信息以及
     * 空的异常信息由这个方法统一设置，避免每个调用方重复组装。</p>
     *
     * @param requestId 对应请求的唯一编号
     * @param data      服务方法的返回值；方法返回 {@code void} 时可以为 {@code null}
     * @return 字段组合正确的成功响应
     */
    public static RpcResponse success(long requestId, Object data) {
        return RpcResponse.builder()
                .requestId(requestId)
                .code(SUCCESS_CODE)
                .message("调用成功")
                .data(data)
                .build();
    }

    /**
     * 创建一个调用失败的响应。
     *
     * <p>异常对象不会直接放入响应，而是被转换成异常类型名称和异常消息。
     * 客户端即使没有服务端使用的异常类，也能读取这些失败信息。</p>
     *
     * @param requestId 对应请求的唯一编号
     * @param exception 服务端执行方法时捕获到的异常，不能为 {@code null}
     * @return 字段组合正确的失败响应
     */
    public static RpcResponse failure(long requestId, Throwable exception) {
        Objects.requireNonNull(exception, "exception 不能为空");

        return RpcResponse.builder()
                .requestId(requestId)
                .code(FAILURE_CODE)
                .message("服务方法执行失败")
                .exceptionType(exception.getClass().getName())
                .exceptionMessage(exception.getMessage())
                .build();
    }
}
