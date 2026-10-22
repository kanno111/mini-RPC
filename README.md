# mini-RPC 项目结构与运行说明

## 1. 项目定位

mini-RPC 是一个使用 Java 21 编写的学习型 RPC 框架。项目从最基础的
Socket 阻塞通信开始，逐步演进到当前的 Netty、自定义协议、JSON 序列化、
ZooKeeper 服务注册与发现、轮询负载均衡和服务端注解扫描。

这个项目当前关注的是“把 RPC 的完整链路真正跑通并理解每一层为什么存在”，
而不是直接实现一个可以替代 Dubbo 或 OpenFeign 的生产级框架。

当前默认调用链使用：

```text
JDK 动态代理
    + Netty 网络通信
    + mini-RPC 自定义二进制协议
    + Jackson JSON 序列化
    + ZooKeeper 注册与发现
    + Round Robin 轮询负载均衡
    + @RpcService 自动扫描
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
| M4 | `@RpcReference` 自动注入客户端代理 | 尚未实现 |
| M4 | 服务发现本地缓存和 ZooKeeper 节点监听 | 尚未实现 |
| M5 | SPI、重试、心跳、熔断、更多序列化等 | 尚未开始 |

详细的分阶段原始规划参见 [process.md](process.md)。

---

## 3. Maven 模块结构

项目采用 Maven 多模块结构：

```text
mini-RPC
├── pom.xml
├── process.md
├── README.md
├── mini-rpc-core
├── mini-rpc-example-api
├── mini-rpc-example-provider
└── mini-rpc-example-consumer
```

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
       ┌─────────────────────────┐   ┌─────────────────────────┐
       │ mini-rpc-example-provider│   │ mini-rpc-example-consumer│
       │      服务提供者应用       │   │      服务消费者应用       │
       └────────────┬────────────┘   └────────────┬────────────┘
                    │                             │
                    └──────────────┬──────────────┘
                                   ▼
                         ┌──────────────────┐
                         │  mini-rpc-core   │
                         │   RPC 框架实现    │
                         └──────────────────┘
```

需要特别注意：

- provider 依赖 `core + api`；
- consumer 依赖 `core + api`；
- consumer 不依赖 provider，因此客户端看不到 `HelloServiceImpl`；
- core 不依赖任何 example 模块，因此框架不包含具体业务代码；
- api 不依赖 core，也不依赖第三方框架，只保存双方共享的契约。

### 3.1 根项目

根目录的 [pom.xml](pom.xml) 是父工程和聚合工程，本身不包含业务源码。

它主要负责：

- 声明四个子模块；
- 统一 Java 版本为 Java 21；
- 统一管理各项依赖的版本；
- 在根目录执行 Maven 命令时按顺序构建所有模块。

### 3.2 mini-rpc-core

这是框架主体，包含协议、序列化、动态代理、网络通信、注册中心、负载均衡、
服务扫描以及服务端和客户端的高层入口。

真实业务项目以后只需要依赖这个模块，而不应该复制其中的代码。

### 3.3 mini-rpc-example-api

这是客户端和服务端共同依赖的“契约模块”。目前只包含：

```text
HelloService.java
```

如果业务方法使用自定义的请求或响应对象，这些公共 DTO 也应该放在 API 模块。

### 3.4 mini-rpc-example-provider

这是服务提供者示例应用，包含：

- `HelloServiceImpl`：`HelloService` 的真正业务实现；
- `ProviderMain`：创建 `RpcProvider`、扫描服务并启动 Netty。

### 3.5 mini-rpc-example-consumer

这是服务消费者示例应用，目前只包含 `ConsumerMain`。

它通过 `RpcClientFactory` 获得 `HelloService` 的动态代理，然后像调用本地方法
一样发起远程调用。

---

## 4. core 模块包结构

```text
com.itheima
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
| `RpcService` | 标记一个类是 RPC 服务实现，并指定要暴露的公共接口 |

示例：

```java
@RpcService(serviceInterface = HelloService.class)
public class HelloServiceImpl implements HelloService {
}
```

注解只是类上的元数据。真正负责查找注解、创建对象和注册服务的是
`RpcServiceScanner` 与 `RpcProvider`。

### 4.2 common：公共数据和协议

| 类 | 作用 |
|---|---|
| `RpcProtocol` | 保存魔数、版本、消息类型、序列化类型、状态和协议头长度 |
| `RpcRequest` | 描述客户端想调用的接口、方法、参数和请求编号 |
| `RpcResponse` | 描述服务端返回的数据或异常信息 |
| `RpcException` | 框架统一使用的运行时异常 |

一次 Java 方法调用无法直接在网络中传输，因此要先转换成 `RpcRequest`：

```text
helloService.sayHello("Tom")
             ↓
RpcRequest
├── requestId       1
├── interfaceName   com.itheima.example.api.HelloService
├── methodName      sayHello
├── parameterTypes  [java.lang.String]
├── parameters      ["Tom"]
└── version          当前预留，尚未参与路由
```

### 4.3 serialize：序列化

| 类 | 作用 |
|---|---|
| `Serializer` | 序列化器统一接口 |
| `JdkSerializer` | Java 原生序列化，主要用于早期学习版本 |
| `JsonSerializer` | Jackson JSON 序列化，当前默认实现 |

序列化器只负责：

```text
Java 对象 ⇄ byte[]
```

它不负责协议头，也不负责 Socket 或 Netty 网络发送。

### 4.4 provider：服务端本地执行

| 类 | 作用 |
|---|---|
| `ServiceProvider` | 本地服务表抽象 |
| `DefaultServiceProvider` | 使用 ConcurrentHashMap 保存接口名到实现对象的映射 |
| `RpcRequestHandler` | 根据请求查找实现对象并通过反射执行方法 |

本地服务表保存：

```text
com.itheima.example.api.HelloService
                    ↓
          HelloServiceImpl 对象
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
    └── com.itheima.example.api.HelloService
        ├── 127.0.0.1:8080
        └── 127.0.0.1:8081
```

服务目录是持久节点，Provider 地址是临时节点。Provider 会话失效后，对应的
临时地址会由 ZooKeeper 自动删除。

当前 `ZooKeeperServiceDiscovery` 每次调用都会查询一次 ZooKeeper，尚未实现
本地缓存和节点变化监听。

### 4.6 loadbalance：负载均衡

| 类 | 作用 |
|---|---|
| `LoadBalance` | 从地址列表选择一个 Provider 的接口 |
| `RoundRobinLoadBalance` | 按服务名称分别维护计数器，实现轮询选择 |

如果地址列表是：

```text
[127.0.0.1:8080, 127.0.0.1:8081]
```

连续调用时预期选择顺序是：

```text
8080 → 8081 → 8080 → 8081
```

### 4.7 cluster：服务地址解析

| 类 | 作用 |
|---|---|
| `ServiceAddressResolver` | 根据服务名称得到一个最终调用地址的接口 |
| `DefaultServiceAddressResolver` | 先发现全部地址，再交给负载均衡器选择一个 |

这个包把两个动作连接起来：

```text
服务名称
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

客户端业务只需要：

```java
try (RpcClientFactory factory = new RpcClientFactory()) {
    HelloService helloService = factory.createProxy(HelloService.class);
    String result = helloService.sayHello("world");
}
```

当前仍需手动调用 `createProxy()`。后续实现 `@RpcReference` 后，可以进一步
自动向业务字段注入代理对象。

### 4.10 server：服务端高层入口

| 类 | 作用 |
|---|---|
| `RpcProvider` | 组装服务表、请求处理器、Netty、JSON 和 ZooKeeper |
| `RpcServiceScanner` | 扫描 `@RpcService`、加载类并调用无参构造方法创建对象 |

服务端业务只需要：

```java
try (RpcProvider provider = new RpcProvider(rpcPort)) {
    provider.scanServices(ProviderMain.class);
    provider.start();
}
```

当前自动创建服务对象的规则是：服务类必须是具体类，并提供公开的无参构造方法。
原因是 mini-RPC 目前还没有 Spring 那样的依赖注入容器，不知道怎样为任意有参
构造方法准备参数。

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
ProviderMain 调用 provider.scanServices(ProviderMain.class)
               ↓
RpcServiceScanner 确定扫描包
com.itheima.example.provider
               ↓
ClassGraph 找到带 @RpcService 的类
               ↓
Class.forName 加载 HelloServiceImpl
               ↓
调用公开无参构造方法创建 HelloServiceImpl 对象
               ↓
RpcProvider 读取注解中的 HelloService.class
               ↓
DefaultServiceProvider 写入本地映射
HelloService 全限定名 → HelloServiceImpl 对象
               ↓
ZooKeeperServiceRegistry 创建临时地址节点
HelloService 全限定名 → 127.0.0.1:8080
               ↓
NettyRpcServer 监听 8080
               ↓
服务可以被远程调用
```

一个服务真正可调用，需要同时满足：

1. 服务实现对象已经创建；
2. 本地服务表已经保存接口到对象的映射；
3. ZooKeeper 已经保存接口到网络地址的映射；
4. Netty 已经成功监听对应端口。

`@RpcService` 自己不会完成以上动作，它只是为扫描器和 Provider 提供元数据。

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
   接口名 + 方法名 + 参数类型 + 参数值 + requestId
                      ↓
4. DefaultServiceAddressResolver 开始选址
                      ↓
5. ZooKeeperServiceDiscovery 查询全部 Provider 地址
                      ↓
6. RoundRobinLoadBalance 轮询选择一个地址
                      ↓
7. NettyRpcClient 建立或复用目标 Channel
                      ↓
8. PendingRequestManager 保存
   requestId → CompletableFuture<RpcResponse>
                      ↓
9. RpcEncoder 编码为“20 字节协议头 + JSON 正文”
                      ↓
10. 网络发送到 Provider
                      ↓
11. 服务端 RpcDecoder 解码成 RpcRequest
                      ↓
12. NettyRpcServerHandler 把请求交给 RpcRequestHandler
                      ↓
13. DefaultServiceProvider 根据接口名找到 HelloServiceImpl 对象
                      ↓
14. RpcRequestHandler 根据方法名和参数类型找到 Method
                      ↓
15. Method.invoke 执行 HelloServiceImpl.sayHello("world")
                      ↓
16. 返回值包装成带有相同 requestId 的 RpcResponse
                      ↓
17. RpcEncoder 编码响应并通过网络返回
                      ↓
18. 客户端 RpcDecoder 得到 RpcResponse
                      ↓
19. NettyRpcClientHandler 根据 requestId 完成对应 future
                      ↓
20. RpcClientProxy 取出 data，作为 sayHello 的返回值
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
| 消息类型 | 请求、响应、预留心跳 |
| 序列化类型 | JDK 或 JSON |
| 状态 | 成功或失败 |
| requestId | 匹配请求和响应 |
| 正文长度 | 处理半包、粘包时判断一帧是否完整 |

正文长度字段只记录正文有多少字节，不代表协议头中包含正文。

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

当前项目还没有自动化测试类，因此这条命令目前主要验证所有模块能否正常编译。

### 9.4 在 IDEA 启动 Provider

运行：

```text
mini-rpc-example-provider
└── com.itheima.example.provider.ProviderMain
```

没有填写程序实参时默认监听 8080。

要启动第二个 Provider：

1. 复制一份 `ProviderMain` 运行配置；
2. 勾选“允许多个实例”或使用两个独立运行配置；
3. 第一份程序实参填写 `8080`；
4. 第二份程序实参填写 `8081`；
5. 分别启动两个配置。

两个 Provider 的响应中会带有不同的 Java 进程 ID，用于观察轮询结果。

### 9.5 启动 Consumer

运行：

```text
mini-rpc-example-consumer
└── com.itheima.example.consumer.ConsumerMain
```

Consumer 会连续调用四次。如果 8080 和 8081 都已注册，可以在响应中的进程 ID
以及客户端连接日志中观察请求被轮询分发。

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

1. `ProviderMain`：`provider.scanServices(...)`；
2. `RpcProvider.scanServices(...)`；
3. `RpcServiceScanner.scanAndCreateServices(...)`；
4. `RpcServiceScanner.createServiceObject(...)`；
5. `RpcProvider.registerService(Object)`；
6. `DefaultServiceProvider.registerService(...)`；
7. `ZooKeeperServiceRegistry.register(...)`；
8. `NettyRpcServer.start()`。

### Consumer 发起请求

1. `ConsumerMain`：`helloService.sayHello(...)`；
2. `RpcClientProxy.invoke(...)`；
3. `NettyRpcClient.sendRequest(...)`；
4. `DefaultServiceAddressResolver.resolve(...)`；
5. `ZooKeeperServiceDiscovery.discover(...)`；
6. `RoundRobinLoadBalance.select(...)`；
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

1. 服务实现类必须提供公开无参构造方法，尚未接入 Spring 依赖注入；
2. 客户端需要手动调用 `factory.createProxy()`，尚未实现 `@RpcReference`；
3. 服务发现每次请求都会访问 ZooKeeper，没有本地缓存和节点监听；
4. `RpcRequest.version` 已预留，但尚未用于服务版本路由；
5. 协议预留了心跳消息类型，但尚未实现心跳检测；
6. 暂无失败重试、熔断、限流和降级；
7. 暂无单元测试和集成测试类；
8. 默认主机、ZooKeeper 地址、序列化方式等配置仍然写在高层工厂中；
9. Provider 当前先注册 ZooKeeper 地址，再启动 Netty，理论上存在极短的地址已可见但端口尚未监听窗口；
10. 还没有封装成 Spring Boot Starter，距离类似 OpenFeign 的开箱即用体验仍有后续工作。

---

## 12. 下一步演进方向

为了逐步接近“类似 OpenFeign、开箱即用”的最终目标，推荐按以下顺序继续：

1. 增加 `@RpcReference` 注解；
2. 扫描 Consumer 中标记的字段并自动注入动态代理；
3. 抽取统一配置对象，集中管理端口、ZooKeeper、超时和序列化方式；
4. 为服务发现增加本地缓存和 ZooKeeper 节点监听；
5. 增加自动化测试；
6. 接入 Spring 容器，让服务对象和客户端代理交给 Spring 管理；
7. 最终封装为 Spring Boot Starter。

目标使用体验可以逐步演进为：

```java
@RpcService
public class HelloServiceImpl implements HelloService {
}
```

客户端：

```java
@RpcReference
private HelloService helloService;
```

业务代码不再手动创建 `RpcProvider`、`RpcClientFactory`、Netty、ZooKeeper 或代理对象。
