package com.itheima.transport.codec;

import com.itheima.common.RpcException;
import com.itheima.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 把 RPC Java 对象编码成可以通过网络发送的字节。
 *
 * <p>这个类是 Netty 出站（outbound）处理器。当后续代码执行
 * {@code channel.writeAndFlush(rpcRequest)} 时，消息会沿着 Pipeline 的出站方向
 * 经过这里，再被转换成下面的字节格式：</p>
 *
 * <pre>
 * ┌──────────────────┬──────────────────────────┐
 * │ 正文长度（4字节） │ Serializer 生成的正文    │
 * └──────────────────┴──────────────────────────┘
 * </pre>
 *
 * <p>TCP 只是一条连续的字节流，不会告诉接收方“一条消息在哪里结束”。
 * 因此必须先写入正文长度，接收方才知道后面应该读取多少个字节。</p>
 *
 * <p>M2 暂时沿用 M1 的“长度 + 正文”格式，方便只关注 Netty 的工作方式。
 * M3 才会加入魔数、协议版本、消息类型、序列化类型等完整协议头。</p>
 */
public class RpcEncoder extends MessageToByteEncoder<Object> {

    /**
     * M2 单条消息允许的最大正文长度：10 MB。
     *
     * <p>限制长度可以避免错误对象被序列化成过大的消息，占用过多内存。</p>
     */
    private static final int MAX_MESSAGE_LENGTH = 10 * 1024 * 1024;

    /** 负责把 RpcRequest 或 RpcResponse 转换成 byte[]。 */
    private final Serializer serializer;

    /**
     * 创建 RPC 编码器。
     *
     * @param serializer 消息正文使用的序列化器，不能为 {@code null}
     */
    public RpcEncoder(Serializer serializer) {
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }
        this.serializer = serializer;
    }

    /**
     * 编码一条准备发送的 RPC 消息。
     *
     * <p>Netty 会为本次编码准备好 {@code output}，我们只需要按照约定顺序
     * 向里面写数据，不需要自己创建、发送或释放这个 ByteBuf。</p>
     *
     * @param context 当前 Channel 对应的处理器上下文，本步骤暂时不需要使用
     * @param message 准备发送的 Java 消息对象，例如 RpcRequest 或 RpcResponse
     * @param output  Netty 提供的输出缓冲区
     */
    @Override
    protected void encode(
            ChannelHandlerContext context,
            Object message,
            ByteBuf output
    ) {
        // 第一步：复用已有 Serializer，把 Java 对象转换成正文 byte[]。
        byte[] body = serializer.serialize(message);
        validateBodyLength(body.length);

        /*
         * 第二步：先写入一个 int，固定占 4 字节，用来表示正文长度。
         * ByteBuf.writeInt 与 M1 的 DataOutputStream.writeInt 都使用大端字节序，
         * 因此当前 Netty 编码结果与 M1 的临时消息格式保持一致。
         */
        output.writeInt(body.length);

        // 第三步：紧接着写入完整正文，最终得到“4 字节长度 + N 字节正文”。
        output.writeBytes(body);
    }

    /**
     * 检查序列化后的正文长度是否在允许范围内。
     *
     * @param bodyLength 正文字节数
     */
    private void validateBodyLength(int bodyLength) {
        if (bodyLength <= 0 || bodyLength > MAX_MESSAGE_LENGTH) {
            throw new RpcException(
                    "RPC 正文长度不合法：" + bodyLength
                            + "，允许范围：1 到 " + MAX_MESSAGE_LENGTH + " 字节"
            );
        }
    }
}
