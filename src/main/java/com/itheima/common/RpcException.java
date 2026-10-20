package com.itheima.common;

import java.io.Serial;

/**
 * mini-RPC 框架统一使用的运行时异常。
 *
 * <p>这个异常表示 RPC 框架在工作过程中出现了问题，例如：</p>
 *
 * <ul>
 *     <li>无法把请求对象序列化成字节；</li>
 *     <li>无法连接服务端；</li>
 *     <li>服务端找不到客户端请求的服务或方法；</li>
 *     <li>收到的网络报文不符合 mini-RPC 协议。</li>
 * </ul>
 *
 * <p>它不用于表示具体业务失败。例如“用户不存在”或“账户余额不足”属于
 * 业务异常，应当由业务代码自己定义和处理。</p>
 *
 * <p>继承 {@link RuntimeException} 后，调用方不必在每一层方法上声明
 * {@code throws RpcException}。当底层发生无法正常恢复的 RPC 错误时，
 * 可以直接抛出该异常，并由最外层统一处理。</p>
 */
public class RpcException extends RuntimeException {

    /** JDK 序列化使用的类版本号。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 使用一段说明文字创建 RPC 异常。
     *
     * <p>适合框架主动发现的问题，例如找不到指定服务。</p>
     *
     * @param message 对异常原因的说明，应该让开发者能够判断问题发生在哪里
     */
    public RpcException(String message) {
        super(message);
    }

    /**
     * 使用说明文字和原始异常创建 RPC 异常。
     *
     * <p>适合包装底层异常。例如 JDK 序列化抛出 {@code IOException} 时，
     * 可以把它包装成 {@code RpcException}，既统一上层看到的异常类型，
     * 又通过 {@code cause} 保留真正的底层原因和堆栈信息。</p>
     *
     * @param message 对当前 RPC 操作失败原因的说明
     * @param cause   导致当前错误的原始异常
     */
    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
