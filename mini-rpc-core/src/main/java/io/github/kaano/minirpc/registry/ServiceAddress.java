package io.github.kaano.minirpc.registry;

import io.github.kaano.minirpc.common.RpcException;

/**
 * 一台 RPC 服务提供者的网络地址。
 *
 * <p>例如 {@code 127.0.0.1:8080} 可以表示成：</p>
 *
 * <pre>{@code
 * ServiceAddress address = new ServiceAddress("127.0.0.1", 8080);
 * }</pre>
 *
 * <p>{@code record} 是 Java 提供的不可变数据类型。编译器会自动生成：</p>
 *
 * <ul>
 *     <li>{@code host()}：取得主机名或 IP；</li>
 *     <li>{@code port()}：取得端口；</li>
 *     <li>{@code equals()} 和 {@code hashCode()}：比较两个地址；</li>
 *     <li>{@code toString()}：方便调试时查看字段。</li>
 * </ul>
 *
 * <p>它只描述“服务在哪里”，不保存服务名称。一个地址可以同时提供多个
 * RPC 接口，服务名称与地址之间的对应关系由注册中心维护。</p>
 *
 * @param host 服务端主机名或 IP，例如 {@code 127.0.0.1}
 * @param port 服务端监听端口，范围是 1 到 65535
 */
public record ServiceAddress(String host, int port) {

    /**
     * record 的紧凑构造方法。
     *
     * <p>执行 {@code new ServiceAddress(host, port)} 时会先进入这里校验参数，
     * 校验通过后，Java 再自动把参数保存到不可变字段中。</p>
     */
    public ServiceAddress {
        if (host == null || host.isBlank()) {
            throw new RpcException("RPC 服务地址的 host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new RpcException(
                    "RPC 服务地址的端口必须在 1 到 65535 之间：" + port
            );
        }

        // 去掉用户不小心写在主机名前后的空格，避免产生两个看似不同的地址。
        host = host.trim();
    }

    /**
     * 返回适合日志展示和注册中心节点内容使用的 {@code host:port} 形式。
     *
     * @return 例如 {@code 127.0.0.1:8080}
     */
    public String asText() {
        return host + ":" + port;
    }

    /**
     * 把注册中心中的 {@code host:port} 文本还原成服务地址对象。
     *
     * <p>这个方法可以看作 {@link #asText()} 的反向操作：</p>
     *
     * <pre>{@code
     * ServiceAddress address = ServiceAddress.fromText("127.0.0.1:8080");
     * address.host(); // 127.0.0.1
     * address.port(); // 8080
     * }</pre>
     *
     * <p>这里从最后一个冒号处分割，而不是直接使用 {@code split(":")}。
     * 这样即使主机部分是包含冒号的 IPv6 地址，也能把最后一段识别为端口。</p>
     *
     * @param text 注册中心保存的地址文字，例如 {@code 127.0.0.1:8080}
     * @return 包含独立 host 和 port 字段的服务地址
     * @throws RpcException 地址为空、格式不正确或端口不是整数时抛出
     */
    public static ServiceAddress fromText(String text) {
        if (text == null || text.isBlank()) {
            throw new RpcException("RPC 服务地址文本不能为空");
        }

        String normalizedText = text.trim();
        int separatorIndex = normalizedText.lastIndexOf(':');

        // 冒号前面必须有 host，后面必须有 port。
        if (separatorIndex <= 0 || separatorIndex == normalizedText.length() - 1) {
            throw new RpcException("RPC 服务地址格式必须是 host:port：" + text);
        }

        String host = normalizedText.substring(0, separatorIndex);
        String portText = normalizedText.substring(separatorIndex + 1);

        try {
            int port = Integer.parseInt(portText);
            // 复用构造方法中的校验，继续保证端口必须位于 1 到 65535。
            return new ServiceAddress(host, port);
        } catch (NumberFormatException exception) {
            throw new RpcException("RPC 服务地址中的端口不是有效整数：" + text, exception);
        }
    }
}
