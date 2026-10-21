package com.itheima.serialize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.common.RpcException;
import com.itheima.common.RpcProtocol;

import java.io.IOException;

/**
 * 基于 Jackson 的 JSON 序列化器。
 *
 * <p>它和 {@link JdkSerializer} 完成的是同一件事，只是生成的正文格式不同：</p>
 *
 * <pre>
 * JdkSerializer：Java 对象 → JDK 私有二进制格式
 * JsonSerializer：Java 对象 → JSON 格式的 UTF-8 字节
 * </pre>
 *
 * <p>JSON 的优点是可读性比较好，也更容易与其他语言交换数据。例如一个简单的
 * RpcResponse 正文可能类似：</p>
 *
 * <pre>{@code
 * {"requestId":1,"code":0,"message":"调用成功","data":"hello"}
 * }</pre>
 *
 * <p>注意：网络中最终传输的仍然是 {@code byte[]}。JSON 是这些字节所遵守的
 * 内容格式，并不表示我们直接把 Java 的 String 对象放进网络。</p>
 */
public class JsonSerializer implements Serializer {

    /**
     * Jackson 提供的 JSON 转换工具。
     *
     * <p>{@code ObjectMapper} 创建成本较高，并且在完成配置后可以安全复用，
     * 因此一个 JsonSerializer 只创建一个实例，而不是每次序列化都重新 new。</p>
     */
    private final ObjectMapper objectMapper;

    /** 使用 Jackson 的默认配置创建 JSON 序列化器。 */
    public JsonSerializer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 把 Java 对象转换成 JSON 字节数组。
     *
     * <p>{@link ObjectMapper#writeValueAsBytes(Object)} 会先把对象转换成 JSON，
     * 再直接生成 UTF-8 字节，避免先得到 String 后又手动转换一次。</p>
     *
     * @param object 要转换的对象，通常是 RpcRequest 或 RpcResponse
     * @return JSON 格式的 UTF-8 字节数组
     */
    @Override
    public byte[] serialize(Object object) {
        if (object == null) {
            throw new RpcException("待序列化对象不能为空");
        }

        try {
            return objectMapper.writeValueAsBytes(object);
        } catch (JsonProcessingException exception) {
            /*
             * Serializer 接口对外统一抛出 RpcException。
             * 上层不需要知道当前使用的是 Jackson，仍然只处理 RPC 框架异常。
             */
            throw new RpcException(
                    "JSON 序列化失败，对象类型：" + object.getClass().getName(),
                    exception
            );
        }
    }

    /**
     * 把 JSON 字节数组还原成调用方指定的 Java 类型。
     *
     * <p>{@code targetType} 非常重要：同一段 JSON 本身不会告诉 Jackson 最外层
     * 应该创建 RpcRequest 还是 RpcResponse，因此解码器必须明确传入目标类型。</p>
     *
     * @param bytes      从协议正文中取得的 JSON 字节
     * @param targetType 希望还原出的 Java 类型
     * @param <T>        返回对象类型，由 targetType 决定
     * @return 反序列化得到的 Java 对象
     */
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> targetType) {
        if (bytes == null || bytes.length == 0) {
            throw new RpcException("待反序列化字节数组不能为空");
        }
        if (targetType == null) {
            throw new RpcException("目标类型不能为空");
        }

        try {
            return objectMapper.readValue(bytes, targetType);
        } catch (IOException exception) {
            throw new RpcException(
                    "JSON 反序列化失败，目标类型：" + targetType.getName(),
                    exception
            );
        }
    }

    /**
     * 返回写入协议头的 JSON 序列化类型编号。
     *
     * <p>编码器会把数字 2 写进协议头；接收方看到 2 后，下一步就能选择本类
     * 解析正文。</p>
     */
    @Override
    public byte getType() {
        return RpcProtocol.SERIALIZER_JSON;
    }
}
