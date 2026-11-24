# 架构与调用链

本文面向希望阅读 mini-RPC 源码的学习者，说明模块为什么这样拆分，以及一个服务
如何从 Spring 注解最终变成一次网络调用。

## 总体分层

```text
业务层
@RpcReference                         @RpcService
     │                                     │
     ▼                                     ▼
Spring Boot Starter：自动配置、注入、启动与关闭
     │                                     │
     ▼                                     ▼
Consumer Proxy / Filter              Provider Filter / Service Table
     │                                     ▲
     ▼                                     │
服务发现 → 负载均衡 → Netty  ───────────────┘
                自定义协议
```

Spring Boot Starter 是最终推荐入口。业务代码只声明服务和引用，不直接创建 Netty、
ZooKeeper、序列化器、代理工厂或 RPC 服务端。

## Maven 模块

```text
mini-RPC
├── pom.xml
├── mini-rpc-core
├── mini-rpc-spring-boot-starter
├── mini-rpc-example-api
├── mini-rpc-example-provider
└── mini-rpc-example-consumer
```

| 模块 | 依赖与职责 |
|---|---|
| `mini-rpc-core` | 框架主体，不依赖 example 模块，也不依赖 Spring |
| `mini-rpc-spring-boot-starter` | 依赖 core，把 RPC 能力接入 Spring 容器 |
| `mini-rpc-example-api` | 只保存双方共享的接口和 DTO，不依赖框架 |
| `mini-rpc-example-provider` | 依赖 Starter 和 API，保存服务实现 |
| `mini-rpc-example-consumer` | 依赖 Starter 和 API，不依赖 Provider |

依赖方向：

```text
                         ┌──────────────────────┐
                         │ mini-rpc-example-api │
                         └──────────┬───────────┘
                                    │
                      ┌─────────────┴─────────────┐
                      ▼                           ▼
          example-provider              example-consumer
                      │                           │
                      └─────────────┬─────────────┘
                                    ▼
                     mini-rpc-spring-boot-starter
                                    │
                                    ▼
                           mini-rpc-core
```

Provider、Consumer 和 API 应一起升级。接口全限定名是服务身份的一部分，修改 API
包名会产生新的服务标识。

## 双方真正共享什么

Consumer 与 Provider 只共享 API 模块中的接口和 DTO：

```text
Consumer                            Provider
   │                                   │
   ├── 知道 HelloService 接口           ├── 知道 HelloService 接口
   ├── 不知道 HelloServiceImpl          └── 拥有 HelloServiceImpl
   └── 持有 HelloService 动态代理
```

双方通过以下契约协作：

- 接口全限定名；
- group 和 version；
- 方法名和参数类型；
- 参数与返回值结构；
- mini-RPC 网络协议。

客户端能够调用服务端，不是因为本地存在实现类，而是动态代理把接口调用翻译成了
双方都能理解的 `RpcRequest`。

## 服务身份 ServiceKey

`ServiceKey` 由三部分组成：

```text
接口全限定名 + group + version
```

例如：

```text
ServiceKey(HelloService, default, 1.0)
ServiceKey(HelloService, default, 2.0)
ServiceKey(HelloService, gray,    2.0)
```

同一个 ServiceKey 会贯穿 Provider 本地服务表、ZooKeeper 路径、Consumer 地址缓存、
Watch 失效和轮询计数器，避免发现阶段和执行阶段理解成不同的服务。

ZooKeeper 中的结构类似：

```text
/mini-rpc/services
└── io.github.kaano.minirpc.example.api.HelloService
    ├── default
    │   ├── 1.0/providers/127.0.0.1%3A8080
    │   └── 2.0/providers/127.0.0.1%3A8081
    └── gray
        └── 2.0/providers/127.0.0.1%3A9080
```

路径片段使用 UTF-8 百分号编码。服务目录是持久节点，Provider 地址是临时节点；
Provider 会话失效后，ZooKeeper 会删除对应地址。

## Provider 从注解到发布成功

```text
HelloServiceImpl 标记 @RpcService
        ↓
ProviderMain 启动 Spring Boot
        ↓
AutoConfiguration.imports 加载 MiniRpcAutoConfiguration
        ↓
RpcServiceBeanDefinitionRegistryPostProcessor 扫描启动包
        ↓
把 @RpcService 类注册成 BeanDefinition
        ↓
Spring 解析依赖并创建真正的服务 Bean
        ↓
SpringRpcProviderLifecycle.start()
        ↓
RpcProvider.registerService(...)
        ↓
DefaultServiceProvider 保存 ServiceKey → Spring Bean
        ↓
NettyRpcServer 成功绑定端口
        ↓
RpcProvider.publishServices()
        ↓
ZooKeeperServiceRegistry 创建临时地址节点
```

一个服务真正可调用，需要同时满足：

1. Spring 已经创建服务对象；
2. Provider 本地服务表已经保存对象；
3. Netty 已经成功监听端口；
4. ZooKeeper 已经发布网络地址。

端口绑定必须早于地址发布。如果端口被占用，成功回调不会执行，ZooKeeper 中也不会
出现一个实际无法连接的 Provider。

## Provider 正常关闭

```text
SpringRpcProviderLifecycle.stop()
        ↓
RpcProvider.close()
        ↓
注销已经发布的 ZooKeeper 地址
        ↓
停止接受新的业务请求
        ↓
关闭 Netty 监听端口，不再接受新连接
        ↓
等待在途响应写回，最长 shutdown-timeout
        ↓
关闭业务线程池和 Netty EventLoop
        ↓
关闭 ZooKeeper 会话
```

只有成功发布过的服务才会主动注销。即使清理过程中某一步失败，后续资源释放仍会
继续执行，避免一个异常阻断整个关闭链路。

## 一次完整 RPC 调用

以 Consumer 调用为例：

```java
String result = helloService.sayHello("world");
```

完整链路：

```text
1. 业务代码调用 HelloService 动态代理
        ↓
2. RpcClientProxy 构造 RpcRequest
   接口名 + group + version + 方法名 + 参数 + requestId
        ↓
3. ConsumerMetricsFilter 和用户 Consumer Filter
        ↓
4. DefaultServiceAddressResolver 解析地址
        ↓
5. ZooKeeperServiceDiscovery 查询匹配 ServiceKey 的 Provider
        ↓
6. LoadBalance 选择本次调用地址
        ↓
7. NettyRpcClient 建立或复用 Channel
        ↓
8. 根据有效超时计算 deadlineEpochMillis
        ↓
9. PendingRequestManager 保存 requestId → CompletableFuture
        ↓
10. RpcEncoder 编码“20 字节协议头 + 正文”并发送
        ↓
11. Provider RpcDecoder 解码 RpcRequest
        ↓
12. NettyRpcServerHandler 取得在途请求许可
        ↓
13. 请求提交到 ProviderBusinessExecutor
    Netty EventLoop 立即返回处理其他网络事件
        ↓
14. ProviderDeadlineFilter 拒绝已经过期的请求
        ↓
15. ProviderMetricsFilter 和用户 Provider Filter
        ↓
16. RpcRequestHandler 按 ServiceKey 查找服务 Bean
        ↓
17. 绑定 RpcCallContext，反射执行服务方法
        ↓
18. 清理 RpcCallContext，生成相同 requestId 的 RpcResponse
        ↓
19. 编码响应并写回 Consumer
        ↓
20. NettyRpcClientHandler 按 requestId 完成对应 Future
        ↓
21. 响应沿 Consumer Filter 返回
        ↓
22. RpcClientProxy 返回 data
```

### requestId 的作用

同一个 Channel 可以同时承载多个请求，响应也不一定严格按请求顺序返回：

```text
请求 101 ─┐                         101 → futureA
请求 102 ─┼── 同一条连接 ── Provider  102 → futureB
请求 103 ─┘                         103 → futureC
```

`PendingRequestManager` 使用 requestId 把响应交给正确的调用者。超时、连接关闭或
编码异常时，对应 Future 也必须结束，避免调用线程永久等待。

## 线程模型

```text
Netty worker EventLoop
    → 解码请求
    → 取得在途许可
    → 提交 ProviderBusinessExecutor
    → 立即返回处理其他 Channel

Provider 业务线程
    → deadline Filter
    → 指标和用户 Filter
    → RpcRequestHandler
    → @RpcService 方法
    → writeAndFlush(response)

Netty EventLoop
    → 编码响应
    → 写入 Socket
```

服务方法不能直接运行在 EventLoop 上，否则一个慢方法会阻塞该线程管理的其他连接。
业务执行器使用固定线程数和有界队列；线程与队列都满时快速返回失败，不使用
`CallerRunsPolicy` 把 Netty I/O 线程退化为业务线程。

## Filter 调用顺序

Consumer：

```text
RpcClientProxy
    → ConsumerMetricsFilter
    → 用户 ConsumerFilter（按 @Order）
    → RpcClient.sendRequest(...)
```

Provider：

```text
ProviderBusinessExecutor
    → ProviderDeadlineFilter
    → ProviderMetricsFilter
    → 用户 ProviderFilter（按 @Order）
    → RpcRequestHandler
    → @RpcService 方法
```

Filter 可以调用一次 `chain.proceed(request)` 继续执行，也可以直接返回响应实现短路。
Filter Bean 会被并发请求共享，所以单次调用数据应保存在局部变量中，不能写进普通
成员变量；重复调用 `proceed` 可能导致服务方法或非幂等请求被执行多次。

## core 包职责

| 包 | 关键类 | 职责 |
|---|---|---|
| `annotation` | `RpcService`、`RpcReference` | 声明远程服务和引用 |
| `common` | `RpcRequest`、`RpcResponse`、`ServiceKey`、`RpcProtocol` | 公共模型与协议常量 |
| `serialize` | `Serializer`、`JsonSerializer`、`JdkSerializer` | Java 对象与正文 `byte[]` 互转 |
| `provider` | `DefaultServiceProvider`、`RpcRequestHandler`、Provider Filter | 本地服务表与反射执行 |
| `registry` | `ServiceRegistry`、`ServiceDiscovery`、ZooKeeper 实现 | 服务地址注册和发现 |
| `loadbalance` | `RoundRobinLoadBalance`、`RandomLoadBalance` | 从候选地址中选择一个 Provider |
| `cluster` | `DefaultServiceAddressResolver` | 串联服务发现与负载均衡 |
| `proxy` | `RpcClientProxy`、Consumer Filter | 拦截接口方法并组织 Consumer 调用链 |
| `client` | `RpcClientFactory`、`RpcReferenceInjector` | 客户端高层组装与代理注入 |
| `server` | `RpcProvider`、`RpcServiceScanner` | 服务端高层组装；保留旧教学扫描入口 |
| `transport.codec` | `RpcEncoder`、`RpcDecoder` | 自定义协议编解码 |
| `transport.client` | `NettyRpcClient`、`PendingRequestManager` | 连接复用、发送与异步请求匹配 |
| `transport.server` | `NettyRpcServer`、`ProviderBusinessExecutor` | 监听端口、网络事件和业务线程隔离 |
| `metrics` | 两侧指标 Filter、Provider 运行指标 | Micrometer 请求与运行状态观测 |

## Starter 的职责

`mini-rpc-spring-boot-starter` 主要完成：

- 绑定 `mini-rpc.*` 配置；
- 根据配置选择 Serializer 和 LoadBalance；
- 延迟创建并自动关闭 `RpcClientFactory`；
- 在 Spring Bean 初始化前注入 `@RpcReference`；
- 把 `@RpcService` 类交给 Spring 创建；
- 自动启动和关闭 Provider；
- 收集并排序 Consumer/Provider Filter Bean；
- 提供默认 `SimpleMeterRegistry` 和基础指标。

客户端工厂使用延迟创建。只启动 Spring 容器不会连接 ZooKeeper；第一次真正需要
远程代理时才创建客户端资源。

## 地址缓存与 Watch

`ZooKeeperServiceDiscovery` 按完整 ServiceKey 把地址结果缓存在 Consumer 内存中，
并为对应版本目录注册一次性 Watch：

```text
首次调用 → 查询 ZooKeeper → 缓存地址 → 注册 Watch
Provider 变化 → Watch 回调删除该 ServiceKey 缓存
下次调用 → 重新查询 → 建立新缓存和新 Watch
```

短期有效期是网络或会话异常时的兜底。当前实现是“变化后失效、下次调用再查询”，
不是后台持续维护的地址快照。

## 旧教学入口的边界

`SocketRpcClient`、`SocketRpcServer`、`RpcServiceScanner` 和 JDK 序列化代码用于展示
早期演进过程，不作为最终 API 继续扩展：

- 旧 `RpcServiceScanner` 通过无参构造方法创建实现，不支持完整 Spring 注入；
- 旧 Socket Provider 不经过当前 Netty Provider Filter 链；
- Spring Boot 业务应依赖 Starter，不应手动组装 core 组件。

网络帧的详细格式见[自定义协议](protocol.md)，所有 Starter 参数见
[配置参考](configuration.md)。
