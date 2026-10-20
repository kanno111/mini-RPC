package com.itheima.transport.server;

import com.itheima.common.RpcException;
import com.itheima.common.RpcRequest;
import com.itheima.common.RpcResponse;
import com.itheima.provider.RpcRequestHandler;
import com.itheima.serialize.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * 服务端处理单个 Socket 连接的任务。
 *
 * <p>一个任务只处理当前连接中的一条 RPC 请求，写回响应后关闭连接。
 * 它与 M1 客户端遵守相同的临时消息格式：</p>
 *
 * <pre>
 * ┌──────────────────┬──────────────────────────┐
 * │ 正文长度（4字节） │ Serializer 生成的正文    │
 * └──────────────────┴──────────────────────────┘
 * </pre>
 *
 * <p>实现 {@link Runnable} 后，后续的 {@code SocketRpcServer} 可以把每个
 * 客户端连接包装成一个任务，交给线程池执行。</p>
 */
public class SocketRpcRequestTask implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketRpcRequestTask.class);

    /** 与 M1 客户端保持一致，单条请求或响应正文最大允许 10 MB。 */
    private static final int MAX_MESSAGE_LENGTH = 10 * 1024 * 1024;

    /** 已经和某个客户端建立连接的服务端 Socket。 */
    private final Socket socket;

    /** 请求和响应正文使用的序列化器。 */
    private final Serializer serializer;

    /** 负责查找本地服务、反射执行方法并生成 RpcResponse。 */
    private final RpcRequestHandler requestHandler;

    /**
     * 创建一个单连接处理任务。
     *
     * @param socket         ServerSocket.accept() 返回的客户端连接
     * @param serializer     请求和响应使用的序列化器
     * @param requestHandler 服务端请求执行器
     */
    public SocketRpcRequestTask(
            Socket socket,
            Serializer serializer,
            RpcRequestHandler requestHandler
    ) {
        if (socket == null) {
            throw new RpcException("Socket 不能为空");
        }
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }
        if (requestHandler == null) {
            throw new RpcException("RpcRequestHandler 不能为空");
        }

        this.socket = socket;
        this.serializer = serializer;
        this.requestHandler = requestHandler;
    }

    /**
     * 读取并执行一条请求，然后把响应写回客户端。
     *
     * <p>Runnable 的 run 方法不能声明受检异常，因此网络读写失败时会记录日志。
     * try-with-resources 会保证处理结束后关闭输入流、输出流和 Socket。</p>
     */
    @Override
    public void run() {
        try (socket;
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(socket.getInputStream())
             );
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(socket.getOutputStream())
             )) {

            // 第一步：读取请求正文长度，并检查是否处于允许范围。
            int requestLength = input.readInt();
            validateMessageLength(requestLength, "请求");

            // 第二步：读取完整请求正文，再反序列化成 RpcRequest。
            byte[] requestBytes = new byte[requestLength];
            input.readFully(requestBytes);
            RpcRequest request = serializer.deserialize(requestBytes, RpcRequest.class);

            // 第三步：查找本地服务、反射执行目标方法，得到成功或失败响应。
            RpcResponse response = requestHandler.handle(request);

            // 第四步：序列化响应，先写长度，再写正文，最后刷新缓冲区。
            byte[] responseBytes = serializer.serialize(response);
            validateMessageLength(responseBytes.length, "响应");
            output.writeInt(responseBytes.length);
            output.write(responseBytes);
            output.flush();
        } catch (IOException | RpcException exception) {
            LOGGER.error(
                    "处理 Socket RPC 请求失败，客户端：" + socket.getRemoteSocketAddress(),
                    exception
            );
        }
    }

    /**
     * 检查正文长度，避免错误数据造成过大的内存分配。
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
