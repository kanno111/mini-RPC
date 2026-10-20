package com.itheima.serialize;

import com.itheima.common.RpcException;
import com.itheima.common.RpcProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 基于 JDK 原生对象流的序列化器。
 *
 * <p>JDK 序列化要求被转换的对象实现 {@link java.io.Serializable} 接口。
 * 当前项目中的 {@code RpcRequest} 和 {@code RpcResponse} 已经满足这个要求。</p>
 *
 * <p>这种方式适合当前学习阶段，因为不需要引入额外组件，能够直接观察
 * “对象与字节相互转换”的过程。实际生产环境通常不会反序列化不可信数据，
 * 也可能选择 JSON、Protobuf 等更容易跨语言或控制数据结构的方案。</p>
 */
public class JdkSerializer implements Serializer {

    /**
     * 使用 {@link ObjectOutputStream} 把 Java 对象写入内存中的字节数组。
     *
     * @param object 要转换的 Java 对象，不能为 {@code null}
     * @return 包含对象完整信息的字节数组
     * @throws RpcException 对象为空、没有实现 Serializable 或写入失败时抛出
     */
    @Override
    public byte[] serialize(Object object) {
        if (object == null) {
            throw new RpcException("待序列化对象不能为空");
        }

        /*
         * ByteArrayOutputStream 把数据暂存在内存里；
         * ObjectOutputStream 负责按照 JDK 序列化格式写入对象。
         * try-with-resources 会在方法结束时自动关闭这两个流。
         */
        try (ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
             ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteOutputStream)) {
            objectOutputStream.writeObject(object);
            objectOutputStream.flush();
            return byteOutputStream.toByteArray();
        } catch (IOException exception) {
            throw new RpcException(
                    "JDK 序列化失败，对象类型：" + object.getClass().getName(),
                    exception
            );
        }
    }

    /**
     * 使用 {@link ObjectInputStream} 从字节数组中还原 Java 对象。
     *
     * @param bytes      序列化产生的字节数组，不能为空
     * @param targetType 期望得到的对象类型，不能为 {@code null}
     * @param <T>        返回对象的具体类型
     * @return 还原后的 Java 对象
     * @throws RpcException 数据为空、数据损坏、缺少相关类或类型不匹配时抛出
     */
    @Override
    public <T> T deserialize(byte[] bytes, Class<T> targetType) {
        if (bytes == null || bytes.length == 0) {
            throw new RpcException("待反序列化字节数组不能为空");
        }
        if (targetType == null) {
            throw new RpcException("目标类型不能为空");
        }

        /*
         * ByteArrayInputStream 让程序能够像读取普通输入流一样读取 byte[]；
         * ObjectInputStream 负责按照 JDK 序列化格式还原对象。
         */
        try (ByteArrayInputStream byteInputStream = new ByteArrayInputStream(bytes);
             ObjectInputStream objectInputStream = new ObjectInputStream(byteInputStream)) {
            Object object = objectInputStream.readObject();

            // Class.cast 会检查实际对象是否属于调用方要求的类型。
            return targetType.cast(object);
        } catch (IOException | ClassNotFoundException | ClassCastException exception) {
            throw new RpcException(
                    "JDK 反序列化失败，目标类型：" + targetType.getName(),
                    exception
            );
        }
    }

    /**
     * 返回写入 RPC 协议头的 JDK 序列化类型编号。
     */
    @Override
    public byte getType() {
        return RpcProtocol.SERIALIZER_JDK;
    }
}
