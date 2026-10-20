package com.itheima.serialize;

import com.itheima.common.RpcException;
import com.itheima.common.RpcProtocol;

/**
 * RPC 序列化器的统一接口。
 *
 * <p>网络只能传输字节，不能直接传输 Java 对象。序列化器负责在
 * Java 对象和字节数组之间进行双向转换：</p>
 *
 * <pre>
 * Java 对象  --序列化--&gt;    byte[]
 * byte[]     --反序列化--&gt; Java 对象
 * </pre>
 *
 * <p>客户端和服务端只依赖这个接口，不直接依赖某一种具体技术。
 * 后续可以提供 JDK、JSON 等不同实现，而调用方不需要改变使用方式。</p>
 */
public interface Serializer {

    /**
     * 把 Java 对象序列化成字节数组。
     *
     * <p>客户端发送请求前，会把 {@code RpcRequest} 转换成字节数组；
     * 服务端返回结果前，会把 {@code RpcResponse} 转换成字节数组。</p>
     *
     * @param object 要转换的 Java 对象，通常是 RpcRequest 或 RpcResponse
     * @return 可以通过网络发送的字节数组
     * @throws RpcException 对象无法被当前序列化器处理时抛出
     */
    byte[] serialize(Object object);

    /**
     * 把字节数组反序列化成指定类型的 Java 对象。
     *
     * <p>{@code targetType} 告诉序列化器期望得到什么类型。例如客户端
     * 接收响应时会传入 {@code RpcResponse.class}。</p>
     *
     * @param bytes      从网络读取到的正文数据
     * @param targetType 期望转换成的 Java 类型
     * @param <T>        返回对象的类型，由 targetType 决定
     * @return 反序列化后的 Java 对象
     * @throws RpcException 字节数据损坏或无法转换为目标类型时抛出
     */
    <T> T deserialize(byte[] bytes, Class<T> targetType);

    /**
     * 返回当前序列化方式在 RPC 协议中的类型编号。
     *
     * <p>这个编号会写入协议头。接收方读取编号后，才能选择相同的
     * 序列化器解析正文。例如 JDK 序列化器应返回
     * {@link RpcProtocol#SERIALIZER_JDK}。</p>
     *
     * @return 序列化类型编号
     */
    byte getType();
}
