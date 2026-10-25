package io.github.kaano.minirpc.common;

/**
 * RPC 通信协议中使用的公共常量。
 *
 * <p>RPC 的客户端和服务端通过网络传递一串二进制数据。双方必须提前约定：
 * 数据以什么标记开头、采用哪个协议版本、消息表示请求还是响应，以及正文使用
 * 哪种方式序列化。这个类集中保存这些约定，避免客户端和服务端分别写“魔法数字”。</p>
 *
 * <p>注意：这个类只描述协议约定，目前并不负责网络传输、编码或解码。
 * 真正把 Java 对象转换成协议报文的功能会在后续阶段实现。</p>
 */
public final class RpcProtocol {

    /**
     * 协议魔数，占 4 字节。
     *
     * <p>接收方读取报文时会先检查这个值。值正确，说明这段数据有可能是
     * mini-RPC 报文；值不正确，则应当拒绝继续解析。</p>
     */
    public static final int MAGIC_NUMBER = 0xCAFEBABE;

    /**
     * 当前协议版本，占 1 字节。
     *
     * <p>以后协议格式发生不兼容变化时，可以增加版本号，让接收方选择正确的解析方式。</p>
     */
    public static final byte VERSION = 1;

    /**
     * 协议头的固定长度，单位是字节。
     *
     * <p>计算方式：魔数 4 + 版本 1 + 序列化类型 1 + 消息类型 1 + 状态 1
     * + 请求 ID 8 + “正文长度”字段 4 = 20 字节。</p>
     *
     * <p>这里的 4 字节只用于记录正文有多长，并不包含正文内容本身。
     * 一条完整报文的结构是：固定长度的协议头 + 长度不固定的正文。</p>
     */
    public static final int HEADER_LENGTH = 20;

    /** 表示当前消息是一条 RPC 请求。 */
    public static final byte MESSAGE_TYPE_REQUEST = 1;

    /** 表示当前消息是一条 RPC 响应。 */
    public static final byte MESSAGE_TYPE_RESPONSE = 2;

    /**
     * 表示当前消息是一条心跳消息。
     *
     * <p>心跳用于确认连接是否仍然可用。S5-I-A 开始支持被动 PING/PONG，自动
     * 发送和超时断连会在后续阶段接入。</p>
     */
    public static final byte MESSAGE_TYPE_HEARTBEAT = 3;

    /** 表示消息正文使用 Java 原生序列化；M0 阶段会首先实现这种方式。 */
    public static final byte SERIALIZER_JDK = 1;

    /** 表示消息正文使用 JSON 序列化；该方式会在后续阶段实现。 */
    public static final byte SERIALIZER_JSON = 2;

    /**
     * 表示 RPC 响应执行成功。
     *
     * <p>请求报文中的状态字段暂时没有业务含义，也固定写入这个值。</p>
     */
    public static final byte STATUS_SUCCESS = 0;

    /** 表示 RPC 响应执行失败。 */
    public static final byte STATUS_FAILURE = 1;

    /**
     * 工具类不需要创建对象，因此使用私有构造方法阻止外部执行 new RpcProtocol()。
     */
    private RpcProtocol() {
    }
}
