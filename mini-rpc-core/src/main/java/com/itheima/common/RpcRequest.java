package com.itheima.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 一次 RPC 方法调用的请求数据。
 *
 * <p>客户端不能直接把“执行某个 Java 方法”这个动作发送到网络中，
 * 必须先把这次调用描述成普通数据。例如：</p>
 *
 * <pre>{@code
 * helloService.sayHello("Tom")
 * }</pre>
 *
 * <p>会被整理成接口名、方法名、参数类型和参数值等字段。
 * 服务端收到这些数据后，才能找到对应的 Java 方法并执行。</p>
 *
 * <p>{@link Data}、{@link Builder} 等 Lombok 注解会在编译时生成
 * getter、setter、构造方法和建造器，减少与 RPC 核心逻辑无关的重复代码。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RpcRequest implements Serializable {

    /**
     * 序列化版本号。
     *
     * <p>JDK 反序列化时会检查这个值，用来判断发送方和接收方使用的类版本是否兼容。</p>
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本次请求的唯一编号。
     *
     * <p>服务端返回响应时会携带相同的编号，客户端据此判断响应属于哪一次请求。</p>
     */
    private long requestId;

    /**
     * 要调用的接口全限定名，例如 {@code com.itheima.example.api.HelloService}。
     */
    private String interfaceName;

    /** 要调用的方法名，例如 {@code sayHello}。 */
    private String methodName;

    /**
     * 方法声明的参数类型。
     *
     * <p>仅有方法名无法区分重载方法，因此还需要参数类型才能准确找到目标方法。</p>
     */
    private Class<?>[] parameterTypes;

    /**
     * 调用方法时实际传入的参数值，例如 {@code new Object[]{"Tom"}}。
     */
    private Object[] parameters;

    /**
     * 服务版本号，用于区分同一个接口的不同实现版本。
     *
     * <p>M0 阶段只保存该信息，真正按版本选择服务会在后续阶段实现。</p>
     */
    private String version;

    /**
     * 本次调用的绝对截止时间，使用 Unix Epoch 毫秒。
     *
     * <p>Consumer 在真正发送请求前，根据本次有效的 request-timeout 计算该值。
     * Provider 收到请求后如果发现当前时间已经达到或超过 deadline，就直接返回
     * 超时响应，不再执行已经失去价值的业务方法。</p>
     *
     * <p>值为 0 表示旧调用方没有提供 deadline，用于兼容早期教学入口。绝对时间
     * 依赖 Consumer 和 Provider 的系统时钟基本同步，这是当前学习实现的限制。</p>
     */
    private long deadlineEpochMillis;
}
