package io.github.kaano.minirpc.transport.client;

import io.github.kaano.minirpc.common.RpcException;
import io.github.kaano.minirpc.common.RpcRequest;
import io.github.kaano.minirpc.common.RpcResponse;
import io.github.kaano.minirpc.serialize.Serializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * 基于 JDK Socket 的同步阻塞 RPC 客户端。
 *
 * <p>M1 阶段为了保持简单，每发送一次请求就创建一个 Socket 连接，收到响应后
 * 立即关闭。连接复用和异步请求会在后续阶段考虑。</p>
 *
 * <p>TCP 只提供连续字节流，没有“一条消息到此结束”的天然边界。因此 M1
 * 使用一个简单临时格式：</p>
 *
 * <pre>
 * ┌──────────────────┬──────────────────────────┐
 * │ 正文长度（4字节） │ Serializer 生成的正文    │
 * └──────────────────┴──────────────────────────┘
 * </pre>
 *
 * <p>这还不是 M3 的完整 RPC 协议头，只用于先跑通最小调用链路。</p>
 */
public class SocketRpcClient implements RpcClient {

    /**
     * M1 单条请求或响应允许的最大正文长度：10 MB。
     *
     * <p>读取响应前必须检查长度，避免错误或恶意数据要求客户端分配过大的数组。</p>
     */
    private static final int MAX_MESSAGE_LENGTH = 10 * 1024 * 1024;

    /** RPC 服务端主机名或 IP 地址。 */
    private final String host;

    /** RPC 服务端监听端口。 */
    private final int port;

    /** 请求和响应正文使用的序列化器。 */
    private final Serializer serializer;

    /**
     * 创建一个连接固定地址的 Socket RPC 客户端。
     *
     * @param host       服务端主机名或 IP，例如 127.0.0.1
     * @param port       服务端端口，范围是 1 到 65535
     * @param serializer 请求和响应使用的序列化器
     */
    public SocketRpcClient(String host, int port, Serializer serializer) {
        if (host == null || host.isBlank()) {
            throw new RpcException("RPC 服务端地址不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new RpcException("RPC 服务端端口必须在 1 到 65535 之间：" + port);
        }
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }

        this.host = host;
        this.port = port;
        this.serializer = serializer;
    }

    /**
     * 发送请求并同步等待响应。
     *
     * <p>该方法按照“创建连接 → 写请求 → 读响应 → 关闭连接”的顺序执行。
     * 当前线程在读取响应时会阻塞，直到服务端返回数据或连接发生错误。</p>
     */
    @Override
    public RpcResponse sendRequest(RpcRequest request) {
        if (request == null) {
            throw new RpcException("RpcRequest 不能为空");
        }

        byte[] requestBytes = serializer.serialize(request);
        validateMessageLength(requestBytes.length, "请求");

        /*
         * try-with-resources 保证正常返回或发生异常时，Socket 和输入输出流
         * 都会自动关闭。M1 每次调用只使用一个连接。
         */
        try (Socket socket = new Socket(host, port);
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream())
             );
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream())
             )) {

            // 先写 4 字节正文长度，再写完整请求正文。
            output.writeInt(requestBytes.length);
            output.write(requestBytes);
            output.flush();

            // 先读取响应长度，检查安全范围后再分配数组。
            int responseLength = input.readInt();
            validateMessageLength(responseLength, "响应");

            byte[] responseBytes = new byte[responseLength];
            input.readFully(responseBytes);

            RpcResponse response = serializer.deserialize(
                    responseBytes,
                    RpcResponse.class
            );
            if (response == null) {
                throw new RpcException("服务端返回的 RpcResponse 不能为空");
            }

            /*
             * 响应必须属于当前请求。M1 一次连接只有一个请求，这项检查仍然可以
             * 及早发现服务端组装错误，并为后续并发请求匹配打下基础。
             */
            if (response.getRequestId() != request.getRequestId()) {
                throw new RpcException(
                        "响应 requestId 与请求不一致，请求："
                                + request.getRequestId()
                                + "，响应："
                                + response.getRequestId()
                );
            }

            return response;
        } catch (IOException exception) {
            throw new RpcException(
                    "Socket RPC 请求失败，服务端地址：" + host + ":" + port,
                    exception
            );
        }
    }

    /**
     * 检查消息长度是否可以安全处理。
     *
     * @param length      正文长度
     * @param messageType “请求”或“响应”，用于生成清晰的错误信息
     */
    private void validateMessageLength(int length, String messageType) {
        if (length <= 0 || length > MAX_MESSAGE_LENGTH) {
            throw new RpcException(
                    messageType + "正文长度不合法：" + length
                            + "，允许范围：1 到 " + MAX_MESSAGE_LENGTH + " 字节"
            );
        }
    }
}
