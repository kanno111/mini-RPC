# mini-RPC 项目结构与运行说明

## 1. 项目定位

mini-RPC 是一个使用 Java 21 编写的学习型 RPC 框架。项目从最基础的
Socket 阻塞通信开始，逐步演进到当前的 Netty、自定义协议、JSON 序列化、
ZooKeeper 服务注册与发现、轮询负载均衡和服务端注解扫描。

这个项目当前已经把完整 RPC 链路接入 Spring Boot Starter，正在从“能够自动
装配并跑通”继续演进到更可靠、更可配置的开箱即用体验。最终使用感受希望接近
OpenFeign；它仍然是学习型项目，不宣称替代 Dubbo、gRPC 或 OpenFeign 的生产能力。

文档分工如下：

- [README.md](README.md)：当前结构、使用方法和调用链；
- [process.md](process.md)：项目最初的分阶段规划；
- [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md)：实际完成的每个阶段、修改原因、
  验证结果和后续限制。此后每次修改都必须同步更新该文档。

当前默认调用链使用（序列化器和负载均衡可通过 Starter 切换）：

```text
JDK 动态代理
    + Netty 网络通信
    + mini-RPC 自定义二进制协议
    + Jackson JSON 序列化
    + ZooKeeper 注册与发现
    + Round Robin 轮询负载均衡
    + Spring Boot Starter 自动装配
    + @RpcService / @RpcReference 自动处理
```

早期实现的 Socket 通信和 JDK 序列化代码仍然保留在 core 模块中，用来对比
框架的演进过程，但示例应用当前默认不再使用它们。

---

## 2. 当前完成度

| 阶段 | 内容 | 当前状态 |
|---|---|---|
| M0 | 请求、响应、异常、协议常量、序列化接口 | 已完成 |
| M1 | Socket 直连、服务表、动态代理、完整 RPC 调用 | 已完成，旧实现保留 |
| M2 | Netty 客户端和服务端、Pipeline、异步请求匹配 | 已完成 |
| M3 | 20 字节协议头、编解码器、JSON 序列化 | 已完成 |
| M4 | ZooKeeper 注册发现、轮询负载均衡 | 已完成基础版本 |
| M4 | `@RpcService` 扫描并自动注册服务端实现 | 已完成 |
| M4 | `@RpcReference` 字段代理注入 | 已完成基础版本 |
| M4 | 服务发现本地缓存和 ZooKeeper 节点监听 | 已完成基础版本 |
| S1 | Spring Boot Starter、自动配置和配置绑定骨架 | 已完成 |
| S2 | Spring 管理客户端工厂并自动注入 `@RpcReference` | 已完成基础版本 |
| S3 | Spring Bean 形式的 `@RpcService` 自动发布 | 已完成基础版本 |
| S4 | Provider、Consumer 改造成 Spring Boot 示例并端到端联调 | 已完成 |
| S5-A | Consumer 全局 `request-timeout` 接入底层客户端 | 已完成 |
| S5-B-A | 服务发现 3 秒本地地址缓存 | 已完成 |
| S5-B-B | ZooKeeper Watch 主动失效地址缓存 | 已完成 |
| S5-C | Netty 端口绑定成功后再发布 Provider 地址 | 已完成 |
| S5-D | Testcontainers 真实 ZooKeeper + Netty 端到端测试 | 已完成 |
| S5-E | `@RpcReference` 单引用超时覆盖全局配置 | 已完成 |
| S5-F | 序列化器和负载均衡策略 Starter 配置化 | 已完成 |
| S5-G-A | Provider 主动摘流与 Netty 排空顺序 | 已完成 |
| S5-G-B | 在途请求计数与可配置排空超时 | 已完成 |
| S5-H-A | Consumer deadline 传递与 Provider 执行前检查 | 已完成 |
| S5-H-B | Provider 执行中 deadline 协作式检查 | 已完成 |
| S5-H-C-A | Provider Netty I/O 与业务线程隔离 | 已完成 |
| S5-H-C-B | Provider 业务线程池配置与过载诊断 | 已完成 |
| S5-I-A | 心跳消息编解码与被动 PING/PONG | 已完成 |
| S5-I-B-A | Consumer 写空闲心跳与读空闲断连 | 已完成 |
| S5-I-B-B | Consumer 心跳时间 Starter 配置化 | 已完成 |
| S5-I-C | Provider 静默客户端 Channel 清理 | 已完成 |
| S5-I-D-A | 真实 Netty PING/PONG 往返集成测试 | 已完成 |
| S5-I-D-B | 心跳超时断连与按需重连集成测试 | 已完成 |
| R1-A | ServiceKey 与 Provider 本地版本/分组路由 | 已完成 |
| R1-B | `@RpcService` / `@RpcReference` 版本与分组传递 | 已完成 |
| R1-C | ZooKeeper、缓存、Watch 与负载均衡接入 ServiceKey | 已完成 |
| M5 | SPI、重试、熔断、更多序列化等 | 尚未开始 |

详细的分阶段原始规划参见 [process.md](process.md)，实际演进记录参见
[DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md) 和续篇
[DEVELOPMENT_LOG2.md](DEVELOPMENT_LOG2.md)。基于 `DEVELOPMENT_LOG2.md` 3.37 / S5-I-D-B
阶段的生产成熟度、主流框架差距和后续优先级评估参见
[PRODUCTION_READINESS_GAP.md](PRODUCTION_READINESS_GAP.md)。

---

## 3. Maven 模块结构

项目采用 Maven 多模块结构：

```text
mini-RPC
├── pom.xml
├── process.md
├── README.md
├── mini-rpc-core
├── mini-rpc-spring-boot-starter
├── mini-rpc-example-api
├── mini-rpc-example-provider
└── mini-rpc-example-consumer
```

项目当前使用的发布坐标与 Java 命名空间为：

```text
Maven groupId：io.github.kaano
Java 基础包：io.github.kaano.minirpc
```

这两个名称分别解决“Maven 依赖属于谁”和“Java 类放在哪个命名空间”。
它们已从早期教学包名迁移为项目维护者的 GitHub 命名空间。由于接口全限定名
同时是当前 RPC 服务标识的一部分，旧包名版本与新包名版本不直接互通；
Provider、Consumer 和共享 API 模块应一起升级。

模块依赖关系：

```text
                         ┌──────────────────────┐
                         │ mini-rpc-example-api │
                         │  公共接口、公共 DTO   │
                         └──────────┬───────────┘
                                    │
                      ┌─────────────┴─────────────┐
                      │                           │
                      ▼                           ▼
       ┌─────────────────────────┐   ┌──────────────────────────┐
       │ mini-rpc-example-provider│   │ mini-rpc-example-consumer│
       │      服务提供者应用       │   │      服务消费者应用       │
       └────────────┬────────────┘   └─────────────┬────────────┘
                    │                             │
                    └──────────────┬──────────────┘
                                   ▼
                    ┌──────────────────────────┐
                    │ mini-rpc-spring-boot-    │
                    │ starter：Spring 自动集成 │
                    └─────────────┬────────────┘
                                  ▼
                         ┌──────────────────┐
                         │  mini-rpc-core   │
                         │   RPC 框架实现    │
                         └──────────────────┘
```

需要特别注意：

- provider 依赖 `starter + api`；
- consumer 依赖 `starter + api`；
- consumer 不依赖 provider，因此客户端看不到 `HelloServiceImpl`；
- core 不依赖任何 example 模块，因此框架不包含具体业务代码；
- starter 依赖 core 和 Spring Boot，负责把现有 RPC 能力接入 Spring 容器；
- api 不依赖 core，也不依赖第三方框架，只保存双方共享的契约。

### 3.1 根项目

根目录的 [pom.xml](pom.xml) 是父工程和聚合工程，本身不包含业务源码。

它主要负责：

- 声明五个子模块（加上父工程本身，Maven Reactor 共构建六个项目）；
- 统一 Java 版本为 Java 21；
- 统一管理各项依赖的版本；
- 在根目录执行 Maven 命令时按顺序构建所有模块。

### 3.2 mini-rpc-core

这是框架主体，包含协议、序列化、动态代理、网络通信、注册中心、负载均衡、
服务扫描以及服务端和客户端的高层入口。

非 Spring 项目可以直接依赖这个模块；Spring Boot 业务项目应优先依赖 Starter。

### 3.3 mini-rpc-spring-boot-starter

这是 Spring 集成层。当前已经包含：

- `MiniRpcProperties`：接收 `mini-rpc.*` 配置；
- `MiniRpcAutoConfiguration`：Spring Boot 自动配置入口；
- `RpcReferenceBeanPostProcessor`：在 Spring Bean 初始化前注入 RPC 代理；
- `RpcServiceBeanDefinitionRegistryPostProcessor`：扫描 `@RpcService` 并注册
  BeanDefinition；
- `SpringRpcProviderLifecycle`：注册 Spring 服务对象，并管理 Provider 的启动关闭；
- `AutoConfiguration.imports`：让 Spring Boot 能自动发现配置入口；
- 自动配置、延迟创建和服务扫描测试。

这个模块已经会向 Spring 注册一个延迟创建的 `RpcClientFactory`。仅仅启动应用
不会连接 ZooKeeper；第一次真正需要客户端工厂时才会创建连接。Spring 容器关闭
时会自动调用工厂的 `close()`，释放 Netty 和 ZooKeeper 资源。

Spring 管理的业务 Bean 中如果存在 `@RpcReference`，后置处理器会在 Bean 初始化
前调用客户端工厂完成代理注入。Provider 开启后，Starter 还会把 `@RpcService`
类交给 Spring 创建，因此服务实现可以正常使用构造器注入、`@Autowired` 等能力。

当前已经可以识别的配置形式如下：

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

这些配置都已经接入实际装配。`request-timeout` 控制请求发出后等待 Provider
响应的最长时间，不等同于建立 TCP 连接时使用的连接超时。它是 Consumer 的全局
默认值，单个远程引用可以通过 `@RpcReference(timeoutMillis = ...)` 覆盖：

```java
@RpcReference(timeoutMillis = 750)
private HelloService helloService;
```

没有填写 `timeoutMillis` 时继续使用全局配置。覆盖值只改变这个代理每次调用等待
响应的时间；不同代理仍然共享同一个 Netty 客户端、Channel 和 ZooKeeper 连接。

同一个接口存在多个版本或分组时，Provider 和 Consumer 可以使用注解选择相同的
服务身份：

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "gray",
        version = "2.0"
)

@RpcReference(group = "gray", version = "2.0")
private HelloService helloService;
```

group/version 省略时默认使用 `default/1.0`，显式值不能为空。它们已经贯通
Spring 注入、动态代理、ZooKeeper 注册发现、负载均衡和 Provider 本地服务表；
不同 Provider 分别部署不同版本时，Consumer 只会发现自己选择的版本地址。

`heartbeat-interval` 控制 Consumer Channel 写空闲多久后发送 PING，默认 15 秒；
`heartbeat-timeout` 控制连续多久没有读到 PONG 或普通响应后关闭 Channel，默认
45 秒。两个值都必须至少为 1 毫秒，并且超时时间必须严格大于发送间隔，例如：

```yaml
mini-rpc:
  heartbeat-interval: 10s
  heartbeat-timeout: 30s
```

这两个值属于整个 Consumer Netty 客户端，而不是某个 `@RpcReference`。不同远程
引用共享 Channel，因此也共享同一套连接存活判断。

Consumer 示例默认将 `io.github.kaano.minirpc.transport.client` 日志调为 `DEBUG`，
便于在控制台观察写空闲后发送 PING 和收到 PONG。这只是示例应用的
观察配置，不是 Starter 强制的日志级别；实际应用可按需移除或改为
`INFO`：

```yaml
logging:
  level:
    io.github.kaano.minirpc.transport.client: DEBUG
```

必须先发起一次 RPC 调用建立 Channel，之后写空闲计时器才会开始工作；
仅启动 Consumer 但没有连接 Provider 时，不会产生心跳日志。

Netty 客户端真正发送请求前，还会把本次最终采用的超时换算成绝对 deadline，并
写入 `RpcRequest.deadlineEpochMillis`。Provider 会在查找服务和反射调用之前检查
该值；请求如果已经过期，就直接返回失败，不执行对应业务方法。已有更早 deadline
时只会保留或收紧，不会因为下游超时更长而延长上游期限。

请求开始执行后，框架会在服务方法所在的当前线程绑定 `RpcCallContext`。普通的短时
服务方法不需要改动；可能长时间循环或分批处理的业务，可以在不会破坏数据一致性的
安全点主动检查 deadline：

```java
while (hasMoreData()) {
    RpcCallContext.checkDeadline();
    processNextBatch();
}
```

deadline 已到达时，检查会抛出 `RpcException`，随后由 Provider 转换成失败响应。
这是协作式取消：框架不会使用危险的线程强杀，业务需要自行选择可安全停止的位置。
上下文在服务方法返回或抛出异常后都会清理，也不会自动传播到业务自行创建的异步
线程。

Provider 收到并解码请求后，不再由 Netty worker EventLoop 直接执行服务方法，而是
提交到独立的 `ProviderBusinessExecutor`。`provider-business-threads` 配置固定业务
线程数，默认取“可用处理器数量”和 2 的较大值；
`provider-business-queue-capacity` 配置有界等待队列容量，默认 1,024。两个值都
必须大于 0，非法值会在 Provider 创建阶段失败。

队列和工作线程都满时会快速返回失败响应；不会使用 `CallerRunsPolicy` 让 Netty
I/O 线程退化成业务线程，也不会用无界队列持续占用内存。拒绝日志会记录线程数、
活跃线程、排队数量、剩余容量、累计完成、累计拒绝和线程池关闭状态，便于区分真实
过载与停机期间的提交竞争。

`shutdown-timeout` 只用于 Provider 关闭，默认 10 秒。Provider 摘流后不再让新请求
进入业务方法，并在这段时间内等待已经开始的请求完成响应写入；超时后记录剩余
请求数、发起 Netty worker 的立即关闭并继续返回。它和 Consumer 的
`request-timeout` 是两个不同方向的时间限制，前者管理服务关闭，后者管理客户端
等待响应。

`provider-client-idle-timeout` 控制 Provider 连续多久没有从某条客户端 Channel
读到普通请求或 PING 后关闭该连接，默认 60 秒，必须至少为 1 毫秒。这个值属于
Provider，无法自动读取另一台 Consumer 的 `heartbeat-interval`，部署时应保证它
明显大于 Consumer 心跳间隔，例如 Consumer 15 秒、Provider 60 秒。

`serializer` 决定本应用发送 RPC 正文时使用的编码方式，可选 `json`（默认）或
`jdk`。Consumer 用它编码请求，Provider 用它编码响应；接收方根据 20 字节协议头
中的序列化类型自动选择对应解码器。JDK 原生反序列化只适合受信任的 Java 环境，
不应处理来源不可信的数据。

`load-balance` 决定 Consumer 发现多个 Provider 时如何选址，可选
`round-robin`（默认轮询）或 `random`（等概率随机）。业务应用也可以直接声明
`Serializer` 或 `LoadBalance` Bean，让自动配置主动让位；但当前协议解码器只认识
JSON 和 JDK 两种类型，自定义序列化器暂时必须与其中一种内置协议类型兼容。

### 3.4 mini-rpc-example-api

这是客户端和服务端共同依赖的“契约模块”。目前只包含：

```text
HelloService.java
```

如果业务方法使用自定义的请求或响应对象，这些公共 DTO 也应该放在 API 模块。

### 3.5 mini-rpc-example-provider

这是服务提供者示例应用，包含：

- `HelloServiceImpl`：`HelloService` 的真正业务实现；
- `ProviderIdentity`：一个普通 Spring Bean，用来演示构造器注入；
- `ProviderMain`：只启动 Spring Boot，底层 RPC 组件由 Starter 自动装配。

### 3.6 mini-rpc-example-consumer

这是服务消费者示例应用，包含：

- `ConsumerMain`：只负责启动 Spring Boot；
- `HelloConsumerRunner`：持有 `@RpcReference` 并执行四次演示调用。

它在字段上声明 `@RpcReference HelloService`。Starter 自动注入动态代理，业务
代码随后像调用本地方法一样发起远程调用。把启动和调用拆开后，真实项目可以
直接用自己的 Controller 或 Service 替换演示 Runner，无需改动启动类。

---

## 4. core 模块包结构

```text
io.github.kaano.minirpc
├── annotation     服务端和客户端使用的 RPC 注解
├── client         客户端高层入口
├── cluster        服务发现和负载均衡的组合层
├── common         RPC 公共数据模型和协议常量
├── loadbalance    负载均衡抽象与实现
├── provider       服务端本地服务表和反射调用
├── proxy          客户端动态代理
├── registry       注册中心抽象、地址模型和 ZooKeeper 实现
├── serialize      序列化抽象与实现
├── server         服务端高层入口和注解扫描
└── transport      Socket/Netty 网络传输和协议编解码
```

### 4.1 annotation：注解

| 类 | 作用 |
|---|---|
| `RpcService` | 标记 RPC 服务实现，并声明接口、group 和 version |
| `RpcReference` | 标记 Consumer 代理字段，并选择 group、version 和可选超时 |

示例：

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "gray",
        version = "2.0"
)
public class HelloServiceImpl implements HelloService {
}

@RpcReference(group = "gray", version = "2.0")
private HelloService helloService;
```

注解只是类上的元数据，不会自己执行代码。Spring Boot 模式下，Starter 负责
查找注解、把服务类交给 Spring 创建，再把 Spring Bean 注册到 `RpcProvider`；
旧的纯 Java 教学模式仍可使用 `RpcServiceScanner`。

### 4.2 common：公共数据和协议

| 类 | 作用 |
|---|---|
| `RpcProtocol` | 保存魔数、版本、消息类型、序列化类型、状态和协议头长度 |
| `ServiceKey` | 用接口全限定名、group、version 唯一标识一个 RPC 服务 |
| `RpcRequest` | 描述接口、方法、参数、请求编号、分组、版本和 deadline |
| `RpcResponse` | 描述服务端返回的数据或异常信息 |
| `RpcException` | 框架统一使用的运行时异常 |

一次 Java 方法调用无法直接在网络中传输，因此要先转换成 `RpcRequest`：

```text
helloService.sayHello("Tom")
             ↓
RpcRequest
├── requestId       1
├── interfaceName   io.github.kaano.minirpc.example.api.HelloService
├── methodName      sayHello
├── parameterTypes  [java.lang.String]
├── parameters      ["Tom"]
├── group            default
├── version          1.0
└── deadlineEpochMillis  Consumer 等待响应的绝对截止时间
```

Provider 收到请求后，会把 `interfaceName + group + version` 组成不可变
`ServiceKey`，再从本地服务表精确查找实现。旧 Consumer 没有携带 group 或 version
时，会兼容到 `default/1.0`。同一个 ServiceKey 同时用于注册中心路径、地址缓存、
Watch 失效和轮询状态，避免发现阶段与 Provider 执行阶段选择不同服务身份。

### 4.3 serialize：序列化

| 类 | 作用 |
|---|---|
| `Serializer` | 序列化器统一接口 |
| `JdkSerializer` | Java 原生序列化，主要用于早期学习版本 |
| `JsonSerializer` | Jackson JSON 序列化，Starter 默认实现 |

序列化器只负责：

```text
Java 对象 ⇄ byte[]
```

它不负责协议头，也不负责 Socket 或 Netty 网络发送。

### 4.4 provider：服务端本地执行

| 类 | 作用 |
|---|---|
| `ServiceProvider` | 本地服务表抽象 |
| `DefaultServiceProvider` | 使用 ConcurrentHashMap 保存 ServiceKey 到实现对象的映射 |
| `RpcRequestHandler` | 根据请求查找实现对象并通过反射执行方法 |

本地服务表保存：

```text
ServiceKey(HelloService, default, 1.0) → HelloServiceV1Impl
ServiceKey(HelloService, default, 2.0) → HelloServiceV2Impl
ServiceKey(HelloService, gray,    2.0) → HelloServiceGrayV2Impl
```

这里的 `ServiceProvider` 不要和“远程 Provider 地址”混淆。它只存在于服务端
当前 JVM 的内存中，不会把 Java 对象放入 ZooKeeper。

### 4.5 registry：注册中心

| 类 | 作用 |
|---|---|
| `ServiceAddress` | 不可变的 `host + port` 地址对象 |
| `ServiceRegistry` | 服务端注册和注销地址的接口 |
| `ServiceDiscovery` | 客户端查询服务地址列表的接口 |
| `ZooKeeperClientFactory` | 创建并启动 Curator 客户端 |
| `ZooKeeperServiceRegistry` | 在 ZooKeeper 中创建 Provider 临时地址节点 |
| `ZooKeeperServiceDiscovery` | 读取某个服务节点下面的全部 Provider 地址 |

ZooKeeper 中保存的结构：

```text
/mini-rpc
└── services
    └── io.github.kaano.minirpc.example.api.HelloService
        ├── default
        │   ├── 1.0
        │   │   └── providers
        │   │       └── 127.0.0.1%3A8080
        │   └── 2.0
        │       └── providers
        │           └── 127.0.0.1%3A8081
        └── gray
            └── 2.0
                └── providers
                    └── 127.0.0.1%3A9080
```

接口、group、version 和地址都是独立路径片段，并经过 UTF-8 百分号编码；例如
地址中的冒号会表示为 `%3A`。服务目录是持久节点，Provider 地址是临时节点。
Provider 会话失效后，对应临时地址会由 ZooKeeper 自动删除。

当前 `ZooKeeperServiceDiscovery` 按完整 ServiceKey 把查询结果在 Consumer 内存中
缓存 3 秒，并为每个版本目录注册一次性 ZooKeeper Watch。某个版本的 Provider
上下线后，Watch 只删除该 ServiceKey 的缓存；下一次调用重新查询并注册新的
Watch。3 秒有效期保留为网络或会话异常时的兜底。

### 4.6 loadbalance：负载均衡

| 类 | 作用 |
|---|---|
| `LoadBalance` | 从地址列表选择一个 Provider 的接口 |
| `RoundRobinLoadBalance` | 按 ServiceKey 分别维护计数器，实现轮询选择 |
| `RandomLoadBalance` | 从候选 Provider 中等概率随机选择一个地址 |

如果地址列表是：

```text
[127.0.0.1:8080, 127.0.0.1:8081]
```

连续调用时预期选择顺序是：

```text
8080 → 8081 → 8080 → 8081
```

这是默认 `round-robin` 的行为；配置为 `random` 后，每次调用会独立随机选择候选
地址，不保证短时间内请求数量完全平均。

### 4.7 cluster：服务地址解析

| 类 | 作用 |
|---|---|
| `ServiceAddressResolver` | 根据完整 ServiceKey 得到一个最终调用地址的接口 |
| `DefaultServiceAddressResolver` | 先发现全部地址，再交给负载均衡器选择一个 |

这个包把两个动作连接起来：

```text
ServiceKey(interfaceName, group, version)
   ↓ ServiceDiscovery
全部 Provider 地址
   ↓ LoadBalance
本次调用的一个 Provider 地址
```

### 4.8 proxy：动态代理

| 类 | 作用 |
|---|---|
| `RpcClientProxy` | 使用 JDK 动态代理拦截接口方法，构造请求并发送 |

客户端得到的 `HelloService` 不是服务端实现，而是代理对象：

```text
helloService.sayHello("Tom")
          ↓ 被代理拦截
构造 RpcRequest
          ↓
rpcClient.sendRequest(request)
```

### 4.9 client：客户端高层入口

| 类 | 作用 |
|---|---|
| `RpcClientFactory` | 统一组装发现、负载均衡、Netty、序列化和动态代理 |
| `RpcReferenceInjector` | 查找 `@RpcReference` 字段并通过反射注入动态代理 |

Spring Boot 客户端业务只需要：

```java
@SpringBootApplication
public class ConsumerApplication implements CommandLineRunner {

    @RpcReference(
            group = "gray",
            version = "2.0",
            timeoutMillis = 750
    )
    private HelloService helloService;

    @Override
    public void run(String... args) {
        helloService.sayHello("world");
    }
}
```

`ConsumerApplication` 由 Spring 创建时，`RpcReferenceBeanPostProcessor` 会在
初始化之前注入代理。只有直接使用 core 的非 Spring 项目，才需要手动创建
`RpcClientFactory` 并调用 `injectReferences()`。group/version 必须和 Provider 一致；
`timeoutMillis` 必须是正数，省略时使用全局 `mini-rpc.request-timeout`，配置为 0
或其他负数会在注入阶段报错。

### 4.10 server：服务端高层入口

| 类 | 作用 |
|---|---|
| `RpcProvider` | 组装服务表、请求处理器、Netty、所选序列化器和 ZooKeeper |
| `RpcServiceBeanDefinitionRegistryPostProcessor` | 扫描 `@RpcService` 并把类注册给 Spring |
| `SpringRpcProviderLifecycle` | 取得 Spring 创建的服务 Bean，注册并启停 Provider |
| `RpcServiceScanner` | 旧纯 Java 教学入口：扫描并通过无参构造方法创建服务 |

Spring Boot 服务端业务只需要声明实现类：

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "gray",
        version = "2.0"
)
public class HelloServiceImpl implements HelloService {

    private final SomeBusinessDependency dependency;

    public HelloServiceImpl(SomeBusinessDependency dependency) {
        this.dependency = dependency;
    }
}
```

Starter 只注册类的“创建说明”，实际对象由 Spring 构造，所以服务实现不需要
无参构造方法，可以使用正常的 Spring 依赖注入。旧 `RpcServiceScanner` 的无参
构造限制只存在于保留的非 Spring 教学入口中。

### 4.11 transport：网络传输

#### transport.codec

| 类 | 方向 | 作用 |
|---|---|---|
| `RpcEncoder` | 出站 | Java 请求/响应对象 → 协议头和正文 |
| `RpcDecoder` | 入站 | 网络字节 → 完整请求/响应对象 |

#### transport.client

| 类 | 作用 |
|---|---|
| `RpcClient` | 客户端传输接口 |
| `SocketRpcClient` | M1 阻塞 Socket 客户端，学习代码保留 |
| `NettyRpcClient` | 当前客户端实现，动态选址、缓存 Channel、发送并等待响应 |
| `NettyRpcClientHandler` | 接收服务端响应并完成对应的等待任务 |
| `PendingRequestManager` | 保存 `requestId → CompletableFuture` 的映射 |

#### transport.server

| 类 | 作用 |
|---|---|
| `RpcServer` | 服务端传输接口 |
| `SocketRpcServer` | M1 阻塞 Socket 服务端，学习代码保留 |
| `SocketRpcRequestTask` | M1 单连接请求处理任务 |
| `NettyRpcServer` | 当前服务端实现，监听端口并配置 Pipeline |
| `NettyRpcServerHandler` | 接收解码后的请求，调用业务处理器并写回响应 |

当前 Netty 服务端为每条连接配置：

```text
入站：网络字节
   ↓
RpcDecoder
   ↓
NettyRpcServerHandler
   ↓
RpcRequestHandler
   ↓
HelloServiceImpl.sayHello(...)

出站：RpcResponse
   ↓
RpcEncoder
   ↓
网络字节
```

---

## 5. 模块之间真正共享什么

客户端与服务端只共享 API 模块中的接口和 DTO：

```text
Consumer                            Provider
   │                                   │
   ├── 知道 HelloService 接口           ├── 知道 HelloService 接口
   │                                   │
   ├── 不知道 HelloServiceImpl          └── 拥有 HelloServiceImpl
   │
   └── 拿到 HelloService 动态代理
```

客户端之所以能调用服务端，不是因为客户端拥有实现类，而是因为双方约定了相同的：

- 接口全限定名；
- 方法名；
- 参数类型；
- 参数数据；
- 返回值结构；
- 网络协议。

---

## 6. 服务从注解到注册成功

```text
HelloServiceImpl
标记 @RpcService(serviceInterface = HelloService.class)
               ↓
ProviderMain 启动 SpringApplication
               ↓
Spring Boot 读取 AutoConfiguration.imports
加载 MiniRpcAutoConfiguration
               ↓
RpcServiceBeanDefinitionRegistryPostProcessor
扫描启动类所在的基础包
               ↓
发现 HelloServiceImpl 上的 @RpcService
把它注册成 BeanDefinition（对象创建说明）
               ↓
Spring 解析构造方法和依赖
先取得 ProviderIdentity，再创建 HelloServiceImpl Bean
               ↓
SpringRpcProviderLifecycle.start()
从 Spring 容器取得 HelloServiceImpl 对象
               ↓
RpcProvider.registerService(...)
DefaultServiceProvider 写入当前 JVM 的本地映射
ServiceKey(HelloService, group, version) → HelloServiceImpl 对象
               ↓
后台线程运行 RpcProvider.start()
NettyRpcServer 绑定并开始监听 8080
               ↓ 绑定成功回调
RpcProvider.publishServices()
ZooKeeperServiceRegistry 创建临时地址节点
ServiceKey(HelloService, group, version) → 127.0.0.1:8080
               ↓
服务可以被远程调用
```

一个服务真正可调用，需要同时满足：

1. 服务实现对象已经创建；
2. 本地服务表已经保存 ServiceKey 到对象的映射；
3. Netty 已经成功监听对应端口；
4. ZooKeeper 已经保存 ServiceKey 到网络地址的映射。

第 3 步必须先于第 4 步。如果端口被其他程序占用，Netty 绑定失败，启动成功
回调不会执行，ZooKeeper 中也就不会出现这个无法连接的 Provider 地址。

正常关闭时采用与启动相反的安全顺序：

```text
SpringRpcProviderLifecycle.stop()
               ↓
RpcProvider.close()
               ↓
主动注销实际已经发布的 ZooKeeper 地址
Consumer Watch 尽快失效旧缓存
               ↓
InFlightRequestTracker 停止接受新业务请求
旧连接上的新请求返回 Provider 正在关闭
               ↓
关闭 Netty 监听端口
不再接受新连接
               ↓
等待在途请求计数归零
响应写入完成后计数 -1
最多等待 shutdown-timeout
               ↓
Netty worker shutdownGracefully()
               ↓
关闭 ZooKeeper 会话
兜底删除当前会话的临时节点
```

只有成功发布过的服务才会主动注销。端口绑定失败时不会执行无意义的注销；多个
服务发布到一半失败时，前面已经成功发布的地址仍能在清理阶段被删除。即使注销、
Netty 停止或注册中心关闭中的某一步失败，后续清理动作也会继续执行。

`@RpcService` 自己不会完成以上动作，它只是一个“标记”。Starter、Spring 容器、
`RpcProvider`、ZooKeeper 和 Netty 共同完成后面的工作。

---

## 7. 一次完整 RPC 调用链

以这行代码为例：

```java
String result = helloService.sayHello("world");
```

完整执行顺序如下：

```text
1. Consumer 调用 HelloService 动态代理
                      ↓
2. RpcClientProxy 拦截 sayHello 方法
                      ↓
3. 构造 RpcRequest
   接口名 + group + version + 方法名 + 参数 + requestId
                      ↓
4. DefaultServiceAddressResolver 开始选址
                      ↓
5. ZooKeeperServiceDiscovery 只查询匹配 ServiceKey 的 Provider 地址
                      ↓
6. 配置的 LoadBalance 策略选择一个地址
                      ↓
7. NettyRpcClient 建立或复用目标 Channel
                      ↓
8. 根据本次有效超时计算 deadlineEpochMillis
                      ↓
9. PendingRequestManager 保存
   requestId → CompletableFuture<RpcResponse>
                      ↓
10. RpcEncoder 编码为“20 字节协议头 + 配置的序列化正文”
                      ↓
11. 网络发送到 Provider
                      ↓
12. 服务端 RpcDecoder 解码成 RpcRequest
                      ↓
13. NettyRpcServerHandler 取得在途请求许可
                      ↓
14. 把请求提交到 ProviderBusinessExecutor
    Netty worker EventLoop 立即返回继续处理网络事件
                      ↓
15. Provider 业务线程调用 RpcRequestHandler
                      ↓
16. RpcRequestHandler 检查 deadline
    已过期：直接返回失败，不执行服务方法
                      ↓ 未过期
17. DefaultServiceProvider 根据接口名找到 HelloServiceImpl 对象
                      ↓
18. RpcRequestHandler 根据方法名和参数类型找到 Method
                      ↓
19. 在 Provider 业务线程绑定 RpcCallContext
                      ↓
20. Method.invoke 执行 HelloServiceImpl.sayHello("world")
    耗时业务可在安全点调用 checkDeadline()
                      ↓
21. 清理 RpcCallContext
                      ↓
22. 返回值包装成带有相同 requestId 的 RpcResponse
                      ↓
23. RpcEncoder 编码响应并通过网络返回
                      ↓
24. 客户端 RpcDecoder 得到 RpcResponse
                      ↓
25. NettyRpcClientHandler 根据 requestId 完成对应 future
                      ↓
26. RpcClientProxy 取出 data，作为 sayHello 的返回值
```

### requestId 为什么重要

同一个 Channel 上可能连续发送多个请求，响应也可能不是严格按调用顺序返回：

```text
请求 101 ─┐
请求 102 ─┼── 同一条连接 ── Provider
请求 103 ─┘
```

客户端通过：

```text
101 → futureA
102 → futureB
103 → futureC
```

把每个响应交给正确的调用者，而不是简单认为“第一个响应一定属于第一个请求”。

---

## 8. 自定义协议

一条网络消息由固定 20 字节协议头和变长正文组成：

```text
┌────────┬──────┬────────┬────────┬──────┬──────────┬──────────┐
│ 魔数4B │版本1B│序列化1B│消息型1B│状态1B│请求ID 8B │正文长度4B│
└────────┴──────┴────────┴────────┴──────┴──────────┴──────────┘
                                                               │
                                                               ▼
                                                   ┌────────────────────┐
                                                   │  正文 body：N 字节 │
                                                   └────────────────────┘
```

当前重要取值：

| 字段 | 当前值或含义 |
|---|---|
| 魔数 | `0xCAFEBABE` |
| 版本 | `1` |
| 消息类型 | 请求、响应、心跳 |
| 序列化类型 | JDK 或 JSON |
| 状态 | 成功或失败 |
| requestId | 匹配请求和响应 |
| 正文长度 | 处理半包、粘包时判断一帧是否完整 |

正文长度字段只记录正文有多少字节，不代表协议头中包含正文。

心跳与普通调用复用这套协议，但使用独立的 `RpcHeartbeat` 正文区分 PING 和
PONG。Provider 收到 PING 后直接在 Channel 所属的 EventLoop 中写回 PONG，
不会进入服务表查找、业务线程池或在途业务请求计数；Consumer 也会在普通
`RpcResponse` 匹配之前消费 PONG，因此心跳不会占用普通 RPC 的 Future。

```text
Consumer Channel                         Provider Channel
      │                                        │
      ├──────── RpcHeartbeat(PING) ───────────>│
      │                              心跳 Handler 直接处理
      │                              不进入业务线程池
      │<──────── RpcHeartbeat(PONG) ───────────┤
 心跳 Handler 消费 PONG
 不进入 PendingRequestManager
```

Consumer 每建立一条 Provider Channel，都会安装属于该连接的 Netty
`IdleStateHandler`：

```text
连续 15 秒没有向 Provider 写数据
        ↓
触发 WRITER_IDLE
        ↓
发送一次 PING

连续 45 秒没有从 Provider 读到任何数据
        ↓
触发 READER_IDLE
        ↓
关闭失效 Channel
        ↓
PendingRequestManager.failAll(...)
        ↓
从连接缓存删除旧 Channel
        ↓
下一次 RPC 调用按需建立新连接
```

正常 RPC 请求也会重置写空闲时间，普通响应和 PONG 都会重置读空闲时间，因此
业务流量活跃时不会额外发送无意义的心跳。默认 45 秒读空闲是 15 秒心跳间隔的
三倍，用于容忍短暂抖动。空闲定时任务由每条 Channel 所属的 EventLoop 调度，
框架没有为心跳再创建线程池。

Provider 也会为每条已接受的客户端 Channel 安装独立读空闲检测。普通请求和 PING
都会证明客户端仍然存活；如果默认 60 秒内没有收到任何入站数据，Provider 只关闭
这条静默子 Channel，不会关闭监听端口、其他客户端连接或业务线程池。

`NettyHeartbeatRoundTripIntegrationTest` 会在本机随机端口启动真实
Netty Provider 和 Consumer，使用短心跳参数验证“写空闲 → PING →
PONG”后 Channel 仍然存活，且后续 RPC 仍能正常执行。该测试不依赖
ZooKeeper 或 Docker，它聚焦验证 Netty Pipeline、协议编解码和心跳空闲计时
能否在真实 TCP 连接上协同工作。

`NettyHeartbeatTimeoutReconnectIntegrationTest` 则使用只存在于测试源集的
故障注入 Provider：它正常处理业务请求，但第一阶段故意丢弃 PING。
测试验证 Consumer 读空闲超时后会关闭旧 Channel，不会在后台无限重连；
当 PONG 恢复且下一次 RPC 到来时，客户端才建立新 Channel，后续调用
会继续复用这条恢复后的连接。故障开关没有进入生产代码或 Starter 配置。

---

## 9. 本地运行

### 9.1 环境要求

- JDK 21；
- Maven 3.9 或兼容版本；
- Docker Desktop；
- ZooKeeper，默认地址 `127.0.0.1:2181`。

### 9.2 启动 ZooKeeper

已有容器时：

```powershell
docker start mini-rpc-zookeeper
```

第一次创建容器时：

```powershell
docker run -d --name mini-rpc-zookeeper -p 2181:2181 zookeeper:3.9.6
```

检查状态：

```powershell
docker ps --filter "name=mini-rpc-zookeeper"
```

### 9.3 编译整个项目

在项目根目录执行：

```powershell
mvn test
```

这条命令会编译全部模块，并运行 Starter 自动配置、延迟创建、注解处理和
Spring 构造器注入测试。Docker 可用时，还会由 Testcontainers 临时启动一个
`zookeeper:3.9.6-jre-17` 容器，执行真实 ZooKeeper + Netty 端到端测试；测试结束
后容器自动删除，不依赖 9.2 中手工创建的 `mini-rpc-zookeeper` 容器。

端到端测试会验证：Provider 注册临时节点、Consumer 发现地址并完成动态代理
RPC、Provider 关闭后临时节点消失、ZooKeeper Watch 让 Consumer 的 30 秒地址缓存
提前失效，以及同一接口的 V1/V2 Provider 能被两个版本代理精确隔离。若 Docker
未启动，该容器用例会明确标记为跳过，其余单元测试仍会继续执行。

### 9.4 在 IDEA 启动 Provider

运行：

```text
mini-rpc-example-provider
└── io.github.kaano.minirpc.example.provider.ProviderMain
```

Provider 是 Spring Boot 应用，默认从 `application.yml` 读取端口 8080。启动后
Starter 会自动扫描 `@RpcService`、注册 ZooKeeper 地址并启动 Netty。

要启动第二个 Provider：

1. 复制一份 `ProviderMain` 运行配置；
2. 勾选“允许多个实例”或使用两个独立运行配置；
3. 第一份程序实参填写 `--mini-rpc.server-port=8080`；
4. 第二份程序实参填写 `--mini-rpc.server-port=8081`；
5. 分别启动两个配置。

两个 Provider 的响应中会带有不同的 Java 进程 ID，用于观察轮询结果。

### 9.5 启动 Consumer

运行：

```text
mini-rpc-example-consumer
└── io.github.kaano.minirpc.example.consumer.ConsumerMain
```

`HelloConsumerRunner` 会连续调用四次。如果 8080 和 8081 都已注册，可以在
响应中的进程 ID 以及客户端连接日志中观察请求被轮询分发。

推荐启动顺序：

```text
1. ZooKeeper
2. Provider 8080
3. Provider 8081（可选）
4. Consumer
```

---

## 10. 推荐断点顺序

第一次完整调试请求时，可以按下面顺序打断点：

### Provider 启动和服务注册

1. `ProviderMain.main(...)`：`SpringApplication.run(...)`；
2. `RpcServiceBeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry(...)`；
3. `HelloServiceImpl` 构造方法：观察 Spring 注入 `ProviderIdentity`；
4. `SpringRpcProviderLifecycle.start()`；
5. `SpringRpcProviderLifecycle.registerService(...)`；
6. `RpcProvider.registerService(...)`；
7. `DefaultServiceProvider.registerService(...)`；
8. `NettyRpcServer.start(...)`：观察 `bind(port).sync()` 完成；
9. `RpcProvider.publishServices()`：由端口绑定成功回调触发；
10. `ZooKeeperServiceRegistry.register(...)`。

### Consumer 注入代理

1. `RpcReferenceBeanPostProcessor.postProcessProperties(...)`；
2. `RpcClientFactory.injectReferences(...)`；
3. `RpcReferenceInjector.inject(...)`；
4. `RpcReferenceInjector.validateReferenceField(...)`；
5. `RpcClientProxy.createProxy(...)`；
6. `RpcReferenceInjector.setFieldValue(...)`；
7. `HelloConsumerRunner.run(...)`：观察字段已经不是 `null`。

### Consumer 发起请求

1. `HelloConsumerRunner`：`helloService.sayHello(...)`；
2. `RpcClientProxy.invoke(...)`；
3. `NettyRpcClient.sendRequest(...)`；
4. `DefaultServiceAddressResolver.resolve(...)`；
5. `ZooKeeperServiceDiscovery.discover(...)`；
6. 当前 `LoadBalance.select(...)`；
7. `PendingRequestManager.register(...)`；
8. `RpcEncoder.encode(...)`。

### Provider 接收并执行

1. `RpcDecoder.decode(...)`；
2. `NettyRpcServerHandler.channelRead0(...)`；
3. `RpcRequestHandler.handle(...)`；
4. `DefaultServiceProvider.getService(...)`；
5. `HelloServiceImpl.sayHello(...)`。

### Consumer 接收响应

1. 客户端 `RpcDecoder.decode(...)`；
2. `NettyRpcClientHandler.channelRead0(...)`；
3. `PendingRequestManager.complete(...)`；
4. `RpcClientProxy.invoke(...)` 返回结果。

调试时如果停留太久，客户端调用可能触发超时。这不一定表示 RPC 代码错误，
而可能只是断点暂停时间超过了客户端配置的等待时间。

---

## 11. 当前限制

当前实现适合学习和本地演示，还存在以下限制：

1. 服务发现已有本地缓存和一次性 ZooKeeper Watch，但当前是“变化后失效、下次
   调用再查询”，尚未在后台主动构建最新地址快照；
2. group/version 已贯通完整分布式调用链，但当前只支持精确匹配，没有版本范围、
   通配分组、自动降级或运行时动态流量比例；切换注解值仍需要重新发布 Consumer；
3. Consumer 和 Provider 的空闲时间都已支持 Starter 全局配置，但还不能按服务或
   单个 `@RpcReference` 覆盖；两端属于不同进程，Provider 阈值与 Consumer 心跳
   间隔目前需要部署者自行协调；
4. 暂无失败重试、熔断、限流和降级；
5. 当前只内置 JSON、JDK 两种序列化方式和轮询、随机两种负载均衡策略；还没有
   SPI、权重、最少活跃或一致性哈希等扩展；
6. 容器端到端测试需要本机或 CI 提供可用的 Docker 引擎；没有 Docker 时该用例
   会跳过，但不影响其余单元测试；
7. Provider 已使用可配置的有界业务线程池隔离 Netty I/O 与服务方法，但目前只有
   全局线程池，还没有按服务隔离和 Micrometer 持续指标；任意 Java 业务代码如果
   永久阻塞且忽略线程中断，框架仍无法安全地强制终止；
8. deadline 使用 Consumer 的绝对 Epoch 毫秒，要求 Consumer 与 Provider 系统时钟
   基本同步；执行中的请求支持通过 `RpcCallContext.checkDeadline()` 协作式结束，
   但框架尚不会自动中断未主动检查的业务方法，上下文也不会自动传播到异步线程；
9. 旧的非 Spring `RpcServiceScanner` 仍要求服务实现提供公开无参构造方法；
10. 当前是单注册中心、单协议的学习实现，距离生产级可观测性和容错能力仍有距离。

---

## 12. 下一步演进方向

Spring 基础链路和第一批运行可靠性能力已经完整跑通。接下来优先补齐
已有调用模型中的语义、观测和扩展缺口：

1. 建立 Consumer/Provider Filter 调用链，为 Metrics、Tracing、鉴权和限流
   提供统一扩展位置；
2. 增加 Micrometer 核心指标，再实现 Serializer/LoadBalance 的最小 SPI；
3. 默认不做透明业务重试，重试由业务根据幂等性显式决定；
4. 补充 CI、性能基线和弱网/长稳测试；
5. deadline 自动中断与“响应只完成一次”涉及业务完成和定时任务竞争，当前已有
   执行前检查与协作式检查，因此暂缓到确有需求时再实现，避免过度设计。

更细的 Spring 分步计划参见
[DEVELOPMENT_LOG.md 的“接下来如何接入 Spring”](DEVELOPMENT_LOG.md#4-接下来如何接入-spring)。

目标使用体验可以逐步演进为：

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "default",
        version = "1.0"
)
public class HelloServiceImpl implements HelloService {
}
```

客户端：

```java
@RpcReference(
        group = "default",
        version = "1.0",
        timeoutMillis = 750
)
private HelloService helloService;
```

业务代码不再手动创建 `RpcProvider`、`RpcClientFactory`、Netty、ZooKeeper 或代理对象。
