package com.itheima.transport.codec;

import com.itheima.common.RpcException;
import com.itheima.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * 把一条完整的 RPC 正文字节解码成 Java 对象。
 *
 * <p>这个类是 Netty 入站（inbound）处理器。它不会直接面对任意长度的 TCP
 * 字节流，而是放在 {@code LengthFieldBasedFrameDecoder} 后面：</p>
 *
 * <pre>
 * TCP 字节流
 *    ↓
 * LengthFieldBasedFrameDecoder：读取 4 字节长度，拼出一条完整正文
 *    ↓
 * RpcDecoder：完整正文 → Serializer.deserialize() → Java 对象
 * </pre>
 *
 * <p>这样分工后，“一条消息在哪里结束”由 Netty 内置帧解码器负责；
 * “正文代表哪个 Java 对象”由本类和 Serializer 负责。</p>
 *
 * @param <T> 期望解码得到的消息类型，例如 RpcRequest 或 RpcResponse
 */
public class RpcDecoder<T> extends MessageToMessageDecoder<ByteBuf> {

    /** M2 单条消息允许的最大正文长度，与 RpcEncoder 保持一致：10 MB。 */
    private static final int MAX_MESSAGE_LENGTH = 10 * 1024 * 1024;

    /** 负责把正文字节还原成 Java 对象。 */
    private final Serializer serializer;

    /**
     * 告诉 Serializer 本次应该还原成什么类型。
     *
     * <p>服务端收到的是请求，因此传 RpcRequest.class；客户端收到的是响应，
     * 因此传 RpcResponse.class。</p>
     */
    private final Class<T> targetType;

    /**
     * 创建 RPC 解码器。
     *
     * @param serializer 正文使用的序列化器，必须与发送方一致
     * @param targetType  希望得到的 Java 消息类型，不能为 {@code null}
     */
    public RpcDecoder(Serializer serializer, Class<T> targetType) {
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }
        if (targetType == null) {
            throw new RpcException("解码目标类型不能为空");
        }

        this.serializer = serializer;
        this.targetType = targetType;
    }

    /**
     * 解码一条已经切分完整的 RPC 正文。
     *
     * <p>{@code input} 中已经不包含前面的 4 字节长度，因为前一个
     * {@code LengthFieldBasedFrameDecoder} 会在输出完整帧时去掉长度字段。</p>
     *
     * @param context 当前 Channel 的处理器上下文，本步骤暂时不需要使用
     * @param input   只包含一条完整正文的 ByteBuf
     * @param output  解码结果列表；加入这里的对象会继续传给下一个入站 Handler
     */
    @Override
    protected void decode(
            ChannelHandlerContext context,
            ByteBuf input,
            List<Object> output
    ) {
        int bodyLength = input.readableBytes();
        validateBodyLength(bodyLength);

        /*
         * Serializer 接收 byte[]，而 Netty 当前提供的是 ByteBuf，
         * 因此先把全部可读正文复制到 byte[] 中。
         */
        byte[] body = new byte[bodyLength];
        input.readBytes(body);

        // 把正文字节还原成调用方指定的 RpcRequest 或 RpcResponse。
        T message = serializer.deserialize(body, targetType);

        /*
         * 加入 output 后，Netty 会把 message 自动传给 Pipeline 中的
         * 下一个入站 Handler。本类不需要自己查找或调用下一个 Handler。
         */
        output.add(message);
    }

    /** 检查收到的正文长度是否在允许范围内。 */
    private void validateBodyLength(int bodyLength) {
        if (bodyLength <= 0 || bodyLength > MAX_MESSAGE_LENGTH) {
            throw new RpcException(
                    "RPC 正文长度不合法：" + bodyLength
                            + "，允许范围：1 到 " + MAX_MESSAGE_LENGTH + " 字节"
            );
        }
    }
}
