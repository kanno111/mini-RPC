package com.itheima.transport.server;

import com.itheima.common.RpcException;
import com.itheima.provider.RpcRequestHandler;
import com.itheima.serialize.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JDK ServerSocket 的同步阻塞 RPC 服务端。
 *
 * <p>主线程只负责循环调用 {@link ServerSocket#accept()} 接收连接。每得到一个
 * 客户端 Socket，就创建 {@link SocketRpcRequestTask} 并交给工作线程池，随后
 * 主线程立即继续等待下一个连接。</p>
 *
 * <pre>
 * 监听线程：accept() ──┬── 客户端 A → 工作线程处理任务 A
 *                      ├── 客户端 B → 工作线程处理任务 B
 *                      └── 客户端 C → 工作线程处理任务 C
 * </pre>
 */
public class SocketRpcServer implements RpcServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocketRpcServer.class);

    /** 服务端监听端口。 */
    private final int port;

    /** 请求和响应正文使用的序列化器。 */
    private final Serializer serializer;

    /** 负责执行已经反序列化的 RpcRequest。 */
    private final RpcRequestHandler requestHandler;

    /** 负责并发执行每个客户端连接对应的处理任务。 */
    private final ExecutorService workerPool;

    /**
     * volatile 保证调用 stop 的线程修改状态后，正在 accept 循环中的线程能够看到。
     */
    private volatile boolean running;

    /** 服务端一旦停止就不能再次启动，重复 stop 则直接返回。 */
    private volatile boolean stopped;

    /**
     * 正在监听端口的 ServerSocket。stop 会从另一个线程关闭它，从而唤醒
     * 阻塞在 accept() 上的启动线程。
     */
    private volatile ServerSocket serverSocket;

    /**
     * 创建 Socket RPC 服务端。
     *
     * @param port           监听端口，范围是 1 到 65535
     * @param serializer     请求和响应使用的序列化器
     * @param requestHandler 服务端请求执行器
     */
    public SocketRpcServer(
            int port,
            Serializer serializer,
            RpcRequestHandler requestHandler
    ) {
        if (port < 1 || port > 65535) {
            throw new RpcException("RPC 服务端端口必须在 1 到 65535 之间：" + port);
        }
        if (serializer == null) {
            throw new RpcException("Serializer 不能为空");
        }
        if (requestHandler == null) {
            throw new RpcException("RpcRequestHandler 不能为空");
        }

        this.port = port;
        this.serializer = serializer;
        this.requestHandler = requestHandler;

        /*
         * Socket 是阻塞 I/O，一个工作线程处理一个连接。
         * M1 使用固定线程池，避免客户端过多时无限制创建平台线程。
         */
        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.workerPool = Executors.newFixedThreadPool(workerCount);
    }

    /**
     * 绑定端口并持续接收客户端连接。
     *
     * <p>该方法会阻塞当前线程，直到其他线程调用 {@link #stop()}，或者监听过程
     * 发生异常。一个 SocketRpcServer 实例停止后不能再次启动。</p>
     */
    @Override
    public void start(Runnable onStarted) {
        if (onStarted == null) {
            throw new RpcException("Socket RPC 服务端启动回调不能为空");
        }

        synchronized (this) {
            if (running) {
                throw new RpcException("Socket RPC 服务端已经启动");
            }
            if (stopped) {
                throw new RpcException("已经停止的 Socket RPC 服务端不能再次启动");
            }

            try {
                serverSocket = new ServerSocket(port);
                running = true;
            } catch (IOException exception) {
                throw new RpcException("绑定 RPC 服务端端口失败：" + port, exception);
            }
        }

        LOGGER.info("Socket RPC 服务端已启动，监听端口：{}", port);

        try {
            /*
             * new ServerSocket(port) 已经成功，说明端口现在可以接受连接。
             * 先通知上层“网络已准备好”，上层才可以把地址发布到注册中心。
             */
            onStarted.run();

            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    submitRequestTask(socket);
                } catch (SocketException exception) {
                    /*
                     * stop() 会关闭 ServerSocket，让阻塞的 accept() 抛出
                     * SocketException。这属于正常停止流程，不需要当作错误。
                     */
                    if (running) {
                        throw exception;
                    }
                }
            }
        } catch (IOException exception) {
            throw new RpcException("Socket RPC 服务端监听失败，端口：" + port, exception);
        } finally {
            stop();
        }
    }

    /**
     * 停止监听并关闭工作线程池。
     *
     * <p>方法可以重复调用。第一次调用负责释放资源，之后调用直接返回。</p>
     */
    @Override
    public void stop() {
        synchronized (this) {
            if (stopped) {
                return;
            }

            running = false;
            stopped = true;

            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException exception) {
                    LOGGER.warn("关闭 ServerSocket 失败，端口：" + port, exception);
                }
            }
        }

        shutdownWorkerPool();
        LOGGER.info("Socket RPC 服务端已停止，端口：{}", port);
    }

    /**
     * 把一个客户端连接包装成任务并提交给工作线程池。
     */
    private void submitRequestTask(Socket socket) {
        try {
            workerPool.execute(
                    new SocketRpcRequestTask(socket, serializer, requestHandler)
            );
        } catch (RuntimeException exception) {
            /*
             * 如果线程池已关闭或拒绝任务，这个 Socket 不会再有任务负责关闭，
             * 因此必须在这里主动释放连接。
             */
            try {
                socket.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw new RpcException("提交 Socket RPC 请求任务失败", exception);
        }
    }

    /**
     * 先等待正在执行的任务自然结束；等待 5 秒仍未结束时，再请求立即停止。
     */
    private void shutdownWorkerPool() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException exception) {
            workerPool.shutdownNow();
            // 恢复中断标记，让上层代码知道当前线程曾经被要求中断。
            Thread.currentThread().interrupt();
        }
    }
}
