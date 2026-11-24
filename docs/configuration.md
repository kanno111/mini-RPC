# Spring Boot Starter 配置参考

Starter 使用 `mini-rpc` 作为统一配置前缀。Spring Boot 会把 YAML 或 properties
配置绑定到 `MiniRpcProperties`，再由自动配置类组装 Consumer 和 Provider。

## 完整配置示例

```yaml
mini-rpc:
  enabled: true
  registry-address: 127.0.0.1:2181
  request-timeout: 5s
  heartbeat-interval: 15s
  heartbeat-timeout: 45s
  shutdown-timeout: 10s
  provider-client-idle-timeout: 60s
  serializer: json
  load-balance: round-robin
  provider-enabled: false
  server-host: 127.0.0.1
  server-port: 8080
  provider-business-threads: 8
  provider-business-queue-capacity: 1024
```

Consumer 和 Provider 可以使用同一套 Starter，但只需要配置本端实际使用的参数。

## 配置项总览

| 配置 | 默认值 | 作用范围 | 说明 |
|---|---|---|---|
| `enabled` | `true` | 两侧 | 是否启用 mini-RPC 自动配置 |
| `registry-address` | `127.0.0.1:2181` | 两侧 | ZooKeeper 连接地址 |
| `request-timeout` | `5s` | Consumer | 请求发出后等待响应的全局最长时间 |
| `heartbeat-interval` | `15s` | Consumer | Channel 写空闲多久后发送 PING |
| `heartbeat-timeout` | `45s` | Consumer | Channel 读空闲多久后关闭连接 |
| `shutdown-timeout` | `10s` | Provider | 关闭时等待在途响应完成的最长时间 |
| `provider-client-idle-timeout` | `60s` | Provider | 多久没有收到客户端数据后关闭该 Channel |
| `serializer` | `json` | 两侧 | 本端发送正文时使用 `json` 或 `jdk` |
| `load-balance` | `round-robin` | Consumer | 使用 `round-robin` 或 `random` 选址 |
| `provider-enabled` | `false` | Provider | 是否启动 Provider 自动配置链路 |
| `server-host` | `127.0.0.1` | Provider | 注册给 Consumer 访问的主机地址 |
| `server-port` | `8080` | Provider | Netty 监听与注册端口 |
| `provider-business-threads` | `max(CPU, 2)` | Provider | 执行服务方法的固定业务线程数 |
| `provider-business-queue-capacity` | `1024` | Provider | 业务线程忙碌时的有界等待队列容量 |

`Duration` 类型配置可以使用 Spring Boot 支持的 `ms`、`s` 等单位。线程数、队列
容量以及各项时间必须为有效正值；心跳超时还必须严格大于心跳发送间隔。

## Consumer 最小配置

```yaml
mini-rpc:
  registry-address: 127.0.0.1:2181
  request-timeout: 5s
```

`RpcClientFactory` 是延迟创建的。仅启动一个没有远程引用的 Spring 容器不会连接
ZooKeeper；真正创建 RPC 代理时才会初始化客户端资源。Spring 关闭时自动调用
`close()`，释放 Netty 和 ZooKeeper 连接。

## Provider 最小配置

```yaml
mini-rpc:
  provider-enabled: true
  registry-address: 127.0.0.1:2181
  server-host: 127.0.0.1
  server-port: 8080
```

`server-host` 是注册中心公布给 Consumer 的地址，不一定等同于机器所有网卡地址。
部署到容器或远程主机时必须保证 Consumer 能访问这个地址。

## `@RpcService`

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "gray",
        version = "2.0"
)
public class HelloServiceImpl implements HelloService {
}
```

| 属性 | 默认值 | 含义 |
|---|---|---|
| `serviceInterface` | 无，必须填写 | 对 Consumer 暴露的接口 |
| `group` | `default` | 服务分组 |
| `version` | `1.0` | 服务版本 |

Provider 开启后，Starter 会扫描启动类所在的基础包，把服务类注册成真正的 Spring
Bean，再把 Spring 创建的对象放入 RPC 本地服务表。因此服务实现可以使用构造器
注入，不需要公开无参构造方法。

## `@RpcReference`

```java
@RpcReference(
        group = "gray",
        version = "2.0",
        timeoutMillis = 750
)
private HelloService helloService;
```

| 属性 | 默认值 | 含义 |
|---|---|---|
| `group` | `default` | 要调用的服务分组 |
| `version` | `1.0` | 要调用的服务版本 |
| `timeoutMillis` | 继承全局配置 | 当前引用等待一次响应的最长毫秒数 |

group/version 必须和 Provider 完全一致，显式值不能为空。`timeoutMillis` 显式配置时
必须大于 0；省略时使用 `mini-rpc.request-timeout`。覆盖超时只改变该代理等待响应
的时间，不会创建独立的 Netty 客户端、Channel 或 ZooKeeper 连接。

## 超时与 deadline

`request-timeout` 描述 Consumer 在请求已经发出后最多等待 Provider 响应多久，
不等同于 TCP 建连超时。

Netty 客户端真正发送请求前，会把有效超时换算成绝对 Epoch 毫秒并写入：

```text
RpcRequest.deadlineEpochMillis
```

Provider 在查找服务和执行方法前检查该值。请求已经过期时直接返回失败，不执行服务
方法。已有更早 deadline 时只会保留或收紧，不会被更长的下游超时延长。

长时间循环或分批处理的业务可以在安全点协作检查：

```java
while (hasMoreData()) {
    RpcCallContext.checkDeadline();
    processNextBatch();
}
```

到期后会抛出 `RpcException` 并转换成失败响应。框架不会强杀任意 Java 线程；上下文
在服务方法结束后清理，也不会自动传播到业务自行创建的异步线程。绝对 deadline
依赖 Consumer 与 Provider 系统时钟基本同步。

## 心跳与空闲连接

Consumer 示例：

```yaml
mini-rpc:
  heartbeat-interval: 15s
  heartbeat-timeout: 45s
```

- 写空闲达到 `heartbeat-interval` 时发送 PING；
- 连续 `heartbeat-timeout` 没有收到普通响应或 PONG 时关闭 Channel；
- 下次 RPC 调用按需建立新连接，不在后台无限重连。

两个值属于整个 Consumer Netty 客户端，不属于某一个 `@RpcReference`。必须先发起
一次 RPC 建立 Channel，写空闲计时才会开始；只启动 Consumer 而没有连接 Provider
时，不会出现 PING/PONG 日志。

可以临时打开传输层 DEBUG 日志观察心跳：

```yaml
logging:
  level:
    io.github.kaano.minirpc.transport.client: DEBUG
```

Provider 使用独立阈值清理静默客户端：

```yaml
mini-rpc:
  provider-client-idle-timeout: 60s
```

该值应明显大于 Consumer 心跳间隔，例如 Consumer 15 秒、Provider 60 秒。两端属于
不同进程，Provider 无法自动读取 Consumer 配置。

## Provider 业务线程池

```yaml
mini-rpc:
  provider-business-threads: 8
  provider-business-queue-capacity: 1024
```

Provider 收到请求后，Netty EventLoop 只负责解码和分发，真正的 `@RpcService`
方法在独立 `ProviderBusinessExecutor` 中执行。

线程和队列都满时，Provider 快速返回失败响应。实现使用有界队列，不使用无界队列；
也不使用 `CallerRunsPolicy`，避免 Netty I/O 线程在过载时变成业务线程。两个值都必须
大于 0，非法值会在 Provider 创建阶段失败。

## 优雅停机

```yaml
mini-rpc:
  shutdown-timeout: 10s
```

Provider 关闭时先从 ZooKeeper 摘流，再停止接受新业务请求和连接，并在配置时间内
等待已接受请求完成响应写入。超时后记录剩余请求数，继续关闭线程池和 Netty。

`shutdown-timeout` 管理 Provider 关闭，`request-timeout` 管理 Consumer 等待响应，
两者属于不同方向，不能互相替代。

## 序列化

```yaml
mini-rpc:
  serializer: json
```

可选值：

- `json`：默认，使用 Jackson JSON；
- `jdk`：Java 原生对象流，只适合学习或完全受信任的 Java 环境。

配置决定本端发送正文的方式：Consumer 用它编码请求，Provider 用它编码响应。
接收方根据 20 字节协议头中的序列化编号选择解码器。

业务应用可以声明自己的 `Serializer` Bean 覆盖默认 Bean，但当前协议解码器只认识
JSON 和 JDK 两个类型编号，因此自定义实现必须兼容其中一种协议类型。通用序列化
SPI 尚未实现。

## 负载均衡

```yaml
mini-rpc:
  load-balance: round-robin
```

可选值：

- `round-robin`：默认，按 ServiceKey 分别维护轮询计数；
- `random`：每次从候选地址中等概率随机选择。

业务应用可以声明自己的 `LoadBalance` Bean 覆盖默认实现。当前尚未提供统一 SPI，
也没有权重、最少活跃或一致性哈希策略。

## 自定义 Consumer Filter

```java
@Component
@Order(10)
public class ConsumerTimingFilter implements ConsumerFilter {

    @Override
    public RpcResponse filter(
            RpcRequest request,
            ConsumerFilterChain chain
    ) {
        long startNanos = System.nanoTime();
        try {
            return chain.proceed(request);
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            // 在这里记录调用耗时。
        }
    }
}
```

Provider Filter 的注册方式相同：把 `ProviderFilter` 实现声明为 Spring Bean。
Starter 使用 Spring 顺序规则收集 Filter；数值更小的 `@Order` 更早执行前置逻辑、
更晚执行后置逻辑。

Filter 会被多个请求共享，必须保证线程安全，并且应只调用一次 `chain.proceed()`。
内置 deadline Filter 固定先于 Provider 指标和用户 Filter。

## Micrometer 指标

Starter 会自动提供 `ConsumerMetricsFilter` 和 `ProviderMetricsFilter`。应用没有声明
`MeterRegistry` 时使用内存中的 `SimpleMeterRegistry`；业务提供自己的注册表时，
默认 Bean 自动让位，所有内置指标复用业务注册表。

| 指标名 | 含义 |
|---|---|
| `mini.rpc.consumer.requests` | Consumer 进入完整远程调用链的请求总数 |
| `mini.rpc.consumer.requests.success` | Consumer 收到成功响应的数量 |
| `mini.rpc.consumer.requests.failure` | Consumer 收到失败响应或调用链异常的数量 |
| `mini.rpc.consumer.duration` | Consumer 完整远程调用耗时 |
| `mini.rpc.provider.requests` | 通过 deadline 检查并进入 Provider 业务链的请求总数 |
| `mini.rpc.provider.requests.success` | Provider 产生成功响应的数量 |
| `mini.rpc.provider.requests.failure` | Provider 产生失败响应或调用链异常的数量 |
| `mini.rpc.provider.duration` | Provider Filter 与服务方法的处理耗时 |
| `mini.rpc.provider.requests.inflight` | 已接受但响应尚未写回的请求数 |
| `mini.rpc.provider.executor.active` | 正在执行任务的业务线程数 |
| `mini.rpc.provider.executor.queued` | 有界队列中等待执行的任务数 |
| `mini.rpc.provider.executor.rejected` | Provider 启动以来累计拒绝的任务数 |

`requests.inflight`、`executor.active` 和 `executor.queued` 是会随运行状态上下变化的
Gauge；`executor.rejected` 是只增不减的 FunctionCounter。在途 Gauge 读取的正是
优雅关闭所使用的真实计数，统计范围包含排队、业务执行和响应写回，不包含心跳。

默认 `SimpleMeterRegistry` 不持久化、不开放 HTTP 端点，也不会把数据发送到
Prometheus。当前指标没有接口、方法、group/version 标签和现成告警规则。
