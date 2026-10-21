package com.itheima.transport.codec;

import com.itheima.common.RpcException;
import com.itheima.common.RpcProtocol;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.serialize.JdkSerializer;
import com.itheima.serialize.JsonSerializer;
import com.itheima.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 把 M3 自定义协议帧解码成 {@link RpcRequest} 或 {@link RpcResponse}。
 *
 * <p>本类直接继承 {@link ByteToMessageDecoder}，因此它现在负责两件事：</p>
 *
 * <ol>
 *     <li>判断 TCP 字节流中是否已经包含一条完整的 RPC 消息；</li>
 *     <li>读取协议头，并把正文反序列化成 Java 对象。</li>
 * </ol>
 *
 * <pre>
 * TCP 字节流
 *    ↓
 * RpcDecoder：先等待完整的 20 字节协议头
 *    ↓
 * 读取并校验：魔数、版本、序列化类型、消息类型、状态、requestId、正文长度
 *    ↓
 * 正文还没收完整：恢复读取位置，继续等待后续网络数据
 *    ↓
 * 正文已经收完整：反序列化成 RpcRequest 或 RpcResponse
 * </pre>
 *
 * <p>M2 中曾在它前面放置 {@code LengthFieldBasedFrameDecoder} 来切分消息。
 * 从 M3 开始，协议头不再只有一个长度字段，因此由本类统一处理协议边界会更直观。</p>
 */
public class RpcDecoder extends ByteToMessageDecoder {

    /** 单条消息允许的最大正文长度，与 {@link RpcEncoder} 保持一致：10 MB。 */
    private static final int MAX_MESSAGE_LENGTH = 10 * 1024 * 1024;

    /**
     * 用来解析 serializerType=1 的 JDK 正文。
     *
     * <p>JdkSerializer 自身不保存某一条消息的状态，因此可以让所有解码器共享。</p>
     */
    private static final Serializer JDK_SERIALIZER = new JdkSerializer();

    /**
     * 用来解析 serializerType=2 的 JSON 正文。
     *
     * <p>JsonSerializer 内部持有创建成本较高的 ObjectMapper。ObjectMapper 在完成
     * 配置后可以被多个线程安全复用，所以这里不需要每收到一条消息就重新创建。</p>
     */
    private static final Serializer JSON_SERIALIZER = new JsonSerializer();

    /**
     * 尝试从当前已经累计的网络字节中解码一条完整消息。
     *
     * <p>这个方法可能被 Netty 调用很多次。TCP 可能把一条消息拆成多次送达，
     * 所以“数据还不够”不是异常，只需要直接返回。Netty 会保存尚未消费的字节，
     * 收到更多网络数据后再调用本方法。</p>
     *
     * @param context 当前 Channel 的处理上下文，本步骤暂时不需要使用
     * @param input   Netty 已累计收到的字节缓冲区
     * @param output  解码结果列表，加入这里后会继续传给下一个入站 Handler
     */
    @Override
    protected void decode(
            ChannelHandlerContext context,
            ByteBuf input,
            List<Object> output
    ) {
        /*
         * 连最小的 20 字节协议头都没有收完整，就无法知道正文有多长。
         * 此时不读取任何内容，等待下一批网络数据。
         */
        if (input.readableBytes() < RpcProtocol.HEADER_LENGTH) {
            return;
        }

        /*
         * 记住这一条消息开始的位置。
         * 如果协议头已经到达、正文却只到了一部分，后面会 resetReaderIndex()，
         * 让下次调用仍然可以从魔数开始重新读取整条消息。
         */
        input.markReaderIndex();

        int magicNumber = input.readInt();
        validateMagicNumber(magicNumber);

        byte version = input.readByte();
        validateVersion(version);

        byte serializerType = input.readByte();
        /*
         * 发送方已经把正文使用的序列化方式写在协议头里。
         * 解码器根据这个数字自己选择实现，不再依赖 Pipeline 提前指定。
         */
        Serializer serializer = selectSerializer(serializerType);

        byte messageType = input.readByte();
        validateMessageType(messageType);

        byte status = input.readByte();
        validateStatus(messageType, status);

        long headerRequestId = input.readLong();
        validateRequestId(headerRequestId);

        int bodyLength = input.readInt();
        validateBodyLength(bodyLength);

        /*
         * 协议头告诉我们正文应该有 bodyLength 个字节，但当前还没有全部到达。
         * 必须把 readerIndex 恢复到本条消息开头，否则下次会从半条正文开始，
         * 把正文内容错误地当成下一条消息的魔数。
         */
        if (input.readableBytes() < bodyLength) {
            input.resetReaderIndex();
            return;
        }

        byte[] body = new byte[bodyLength];
        input.readBytes(body);

        /*
         * 消息类型在协议头中，因此同一个解码器既能识别请求，也能识别响应。
         * 不再需要由客户端、服务端提前传入 targetType。
         */
        Object message = deserializeBody(body, messageType, serializer);

        /*
         * requestId 和响应状态在协议头、正文中各保存了一份。
         * 这里检查两份数据是否一致，可以尽早发现数据损坏或编码错误。
         */
        validateMessageMetadata(message, headerRequestId, status);

        // 加入 output 后，Netty 会把 Java 对象传给 Pipeline 中下一个入站 Handler。
        output.add(message);
    }

    /**
     * 根据协议头中的序列化类型编号选择正文翻译器。
     *
     * <p>当前只有两种实现，因此直接使用 switch 最容易理解。以后序列化实现非常多、
     * 或需要让用户自行扩展时，再考虑使用注册表或 SPI。</p>
     */
    private Serializer selectSerializer(byte serializerType) {
        return switch (serializerType) {
            case RpcProtocol.SERIALIZER_JDK -> JDK_SERIALIZER;
            case RpcProtocol.SERIALIZER_JSON -> JSON_SERIALIZER;
            default -> throw new RpcException(
                    "不支持的 RPC 序列化类型：" + serializerType
            );
        };
    }

    /** 根据协议头中的消息类型，决定正文应该还原成哪个 Java 类。 */
    private Object deserializeBody(
            byte[] body,
            byte messageType,
            Serializer serializer
    ) {
        return switch (messageType) {
            case RpcProtocol.MESSAGE_TYPE_REQUEST ->
                    serializer.deserialize(body, RpcRequest.class);
            case RpcProtocol.MESSAGE_TYPE_RESPONSE ->
                    serializer.deserialize(body, RpcResponse.class);
            default -> throw new RpcException(
                    "当前阶段不支持解码的 RPC 消息类型：" + messageType
            );
        };
    }

    /** 魔数相当于协议的“暗号”，可避免把其他协议的数据误当成 RPC 消息。 */
    private void validateMagicNumber(int magicNumber) {
        if (magicNumber != RpcProtocol.MAGIC_NUMBER) {
            throw new RpcException(
                    "RPC 协议魔数不正确，收到：0x"
                            + Integer.toHexString(magicNumber).toUpperCase()
            );
        }
    }

    /** 当前代码只认识一个协议版本，版本不同就拒绝继续解析。 */
    private void validateVersion(byte version) {
        if (version != RpcProtocol.VERSION) {
            throw new RpcException(
                    "不支持的 RPC 协议版本：" + version
                            + "，当前版本：" + RpcProtocol.VERSION
            );
        }
    }

    /** 当前协议只定义了请求和响应两种普通消息。 */
    private void validateMessageType(byte messageType) {
        if (messageType != RpcProtocol.MESSAGE_TYPE_REQUEST
                && messageType != RpcProtocol.MESSAGE_TYPE_RESPONSE) {
            throw new RpcException("不支持的 RPC 消息类型：" + messageType);
        }
    }

    /**
     * 请求没有成功或失败的概念，因此请求状态固定为 0；
     * 响应状态只能是成功或失败。
     */
    private void validateStatus(byte messageType, byte status) {
        if (messageType == RpcProtocol.MESSAGE_TYPE_REQUEST) {
            if (status != RpcProtocol.STATUS_SUCCESS) {
                throw new RpcException("RPC 请求的状态字段必须为 0：" + status);
            }
            return;
        }

        if (status != RpcProtocol.STATUS_SUCCESS
                && status != RpcProtocol.STATUS_FAILURE) {
            throw new RpcException("不支持的 RPC 响应状态：" + status);
        }
    }

    /** requestId 用来匹配请求与响应，当前约定它必须是正数。 */
    private void validateRequestId(long requestId) {
        if (requestId <= 0) {
            throw new RpcException("RPC requestId 必须大于 0：" + requestId);
        }
    }

    /** 拒绝空正文和异常大的正文，避免错误数据导致不合理的内存分配。 */
    private void validateBodyLength(int bodyLength) {
        if (bodyLength <= 0 || bodyLength > MAX_MESSAGE_LENGTH) {
            throw new RpcException(
                    "RPC 正文长度不合法：" + bodyLength
                            + "，允许范围：1 到 " + MAX_MESSAGE_LENGTH + " 字节"
            );
        }
    }

    /**
     * 对照协议头与反序列化后的正文，确认关键元数据没有前后矛盾。
     */
    private void validateMessageMetadata(
            Object message,
            long headerRequestId,
            byte headerStatus
    ) {
        if (message instanceof RpcRequest request) {
            if (request.getRequestId() != headerRequestId) {
                throw new RpcException(
                        "RPC 请求头与正文的 requestId 不一致，协议头："
                                + headerRequestId + "，正文：" + request.getRequestId()
                );
            }
            return;
        }

        RpcResponse response = (RpcResponse) message;
        if (response.getRequestId() != headerRequestId) {
            throw new RpcException(
                    "RPC 响应头与正文的 requestId 不一致，协议头："
                            + headerRequestId + "，正文：" + response.getRequestId()
            );
        }

        byte bodyStatus = response.getCode() == RpcResponse.SUCCESS_CODE
                ? RpcProtocol.STATUS_SUCCESS
                : RpcProtocol.STATUS_FAILURE;
        if (bodyStatus != headerStatus) {
            throw new RpcException(
                    "RPC 响应头与正文的状态不一致，协议头："
                            + headerStatus + "，正文：" + bodyStatus
            );
        }
    }
}
