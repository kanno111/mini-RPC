package com.itheima.transport.codec;

import com.itheima.common.RpcException;
import com.itheima.common.RpcProtocol;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 把 RPC Java 对象编码成可以通过网络发送的字节。
 *
 * <p>这个类是 Netty 出站（outbound）处理器。当后续代码执行
 * {@code channel.writeAndFlush(rpcRequest)} 时，消息会沿着 Pipeline 的出站方向
 * 经过这里，再被转换成 M3 自定义协议帧：</p>
 *
 * <pre>
 * ┌────────┬──────┬────────┬────────┬──────┬───────────┬──────────┬──────────┐
 * │ 魔数4B │版本1B│序列化1B│消息类型1B│状态1B│ requestId 8B│正文长度4B│正文 N 字节│
 * └────────┴──────┴────────┴────────┴──────┴───────────┴──────────┴──────────┘
 * </pre>
 *
 * <p>协议头固定占 {@link RpcProtocol#HEADER_LENGTH} 字节。它让接收方在读取正文前，
 * 就能校验报文来源和协议版本，并知道正文使用的序列化方式、消息类型、请求编号
 * 以及正文长度。</p>
 */
public class RpcEncoder extends MessageToByteEncoder<Object> {

    /**
     * M3 单条消息允许的最大正文长度：10 MB。
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
        if (message == null) {
            throw new RpcException("待编码的 RPC 消息不能为空");
        }

        byte messageType;
        byte status;
        long requestId;

        /*
         * 第一步：根据 Java 对象类型生成协议头中的消息元数据。
         * 当前只允许发送 RpcRequest 和 RpcResponse，其他对象没有对应的协议类型。
         */
        if (message instanceof RpcRequest request) {
            messageType = RpcProtocol.MESSAGE_TYPE_REQUEST;
            // 请求还没有执行结果，因此状态字段暂时固定写 0，接收方会忽略它。
            status = RpcProtocol.STATUS_SUCCESS;
            requestId = request.getRequestId();
        } else if (message instanceof RpcResponse response) {
            messageType = RpcProtocol.MESSAGE_TYPE_RESPONSE;
            status = response.getCode() == RpcResponse.SUCCESS_CODE
                    ? RpcProtocol.STATUS_SUCCESS
                    : RpcProtocol.STATUS_FAILURE;
            requestId = response.getRequestId();
        } else {
            throw new RpcException(
                    "不支持编码的 RPC 消息类型：" + message.getClass().getName()
            );
        }

        validateRequestId(requestId);

        // 第二步：复用已有 Serializer，把 Java 对象转换成正文 byte[]。
        byte[] body = serializer.serialize(message);
        validateBodyLength(body.length);

        /*
         * 第三步：严格按照 RpcProtocol 约定的顺序写入 20 字节协议头。
         * 接收方必须使用完全相同的顺序和字节数读取，否则字段会错位。
         */
        output.writeInt(RpcProtocol.MAGIC_NUMBER);     // 0~3：魔数，4 字节
        output.writeByte(RpcProtocol.VERSION);         // 4：协议版本，1 字节
        output.writeByte(serializer.getType());        // 5：序列化类型，1 字节
        output.writeByte(messageType);                 // 6：请求或响应，1 字节
        output.writeByte(status);                      // 7：响应状态，1 字节
        output.writeLong(requestId);                   // 8~15：请求编号，8 字节
        output.writeInt(body.length);                  // 16~19：正文长度，4 字节

        // 第四步：协议头后面紧跟序列化产生的完整正文。
        output.writeBytes(body);
    }

    /** 检查请求编号，避免发送无法进行请求—响应匹配的报文。 */
    private void validateRequestId(long requestId) {
        if (requestId <= 0) {
            throw new RpcException("RPC requestId 必须大于 0：" + requestId);
        }
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
