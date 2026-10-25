package com.itheima.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一条用于检查 TCP 长连接是否仍然可用的心跳消息。
 *
 * <p>心跳和普通 RPC 调用共用同一套 20 字节协议头，但协议头中的消息类型固定为
 * {@link RpcProtocol#MESSAGE_TYPE_HEARTBEAT}。正文中的 {@link #heartbeatType}
 * 再区分这条消息是探测请求 PING，还是探测响应 PONG。</p>
 *
 * <p>当前阶段只建立消息模型和被动 PING/PONG 链路，还不会定时发送 PING。
 * 后续接入 Netty 空闲检测后，Consumer 才会在连接长期没有写数据时创建 PING。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RpcHeartbeat implements Serializable {

    /** JDK 序列化使用的类版本号。 */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Consumer 发出的连接探测请求。 */
    public static final byte TYPE_PING = 1;

    /** Provider 针对 PING 返回的连接探测响应。 */
    public static final byte TYPE_PONG = 2;

    /**
     * 本次心跳的唯一编号。
     *
     * <p>PONG 必须沿用对应 PING 的 requestId。后续自动心跳可以据此判断收到的
     * PONG 是否属于最近一次探测，而不必把心跳塞进普通 RPC 的等待请求表。</p>
     */
    private long requestId;

    /** 心跳方向，取值只能是 {@link #TYPE_PING} 或 {@link #TYPE_PONG}。 */
    private byte heartbeatType;

    /**
     * 消息创建时的 Unix Epoch 毫秒。
     *
     * <p>它主要用于日志和后续延迟诊断，不参与连接存活判断。连接是否失效应由
     * 本地空闲计时决定，避免依赖 Consumer 与 Provider 的系统时钟完全一致。</p>
     */
    private long timestampEpochMillis;

    /** 创建一条 PING；参数合法性会在协议编码阶段统一检查。 */
    public static RpcHeartbeat ping(long requestId, long timestampEpochMillis) {
        return new RpcHeartbeat(requestId, TYPE_PING, timestampEpochMillis);
    }

    /** 创建一条 PONG，并沿用对应 PING 的 requestId。 */
    public static RpcHeartbeat pong(long requestId, long timestampEpochMillis) {
        return new RpcHeartbeat(requestId, TYPE_PONG, timestampEpochMillis);
    }
}
