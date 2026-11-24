# mini-RPC

mini-RPC 是一个使用 Java 21 编写的学习型 RPC 框架。项目从阻塞 Socket 教学实现
逐步演进到 Netty、自定义协议、ZooKeeper 注册发现和 Spring Boot Starter，目标是
用一条可以运行、调试和测试的完整链路理解 RPC 与 Spring 自动配置原理。

> mini-RPC 适合源码学习和本地实验，目前不具备 Dubbo、gRPC 等成熟框架的生产能力。

## 核心能力

- 使用 JDK 动态代理把本地接口调用转换成远程调用；
- 使用 Netty 维护 Consumer 与 Provider 的网络连接；
- 使用 20 字节自定义协议头以及 JSON/JDK 正文序列化；
- 使用 ZooKeeper 完成服务注册、发现、本地缓存和 Watch 失效；
- 支持轮询、随机负载均衡以及 group/version 精确路由；
- 通过 `@RpcService` 暴露 Spring Bean，通过 `@RpcReference` 注入远程代理；
- 支持请求超时、deadline、心跳、Provider 业务线程池和优雅停机；
- 支持 Consumer/Provider Filter 与 Micrometer 基础指标。

当前默认调用链：

```text
@RpcReference 动态代理
    → Consumer Filter
    → ZooKeeper 服务发现
    → Round Robin 负载均衡
    → Netty + mini-RPC 自定义协议
    → Provider 业务线程池
    → Provider Filter
    → @RpcService Spring Bean
```

早期 Socket 通信和 JDK 序列化实现仍保留在 `mini-rpc-core` 中，用于观察框架的
演进过程；Spring Boot 示例默认使用 Netty、JSON 和 ZooKeeper。

## 快速开始

### 环境要求

- JDK 21；
- Maven 3.9 或兼容版本；
- ZooKeeper，默认地址为 `127.0.0.1:2181`；
- Docker Desktop，可选，用于启动 ZooKeeper 和运行 Testcontainers 集成测试。

### 1. 启动 ZooKeeper

第一次创建容器：

```powershell
docker run -d --name mini-rpc-zookeeper -p 2181:2181 zookeeper:3.9.6
```

已有容器时：

```powershell
docker start mini-rpc-zookeeper
```

### 2. 构建并测试

在项目根目录执行：

```powershell
mvn test
```

Docker 可用时，Testcontainers 会额外启动临时 ZooKeeper，验证真实 ZooKeeper +
Netty 端到端链路；Docker 不可用时该容器测试会跳过，其余单元测试仍会执行。

### 3. 启动 Provider

当前示例以 IDEA 运行配置作为最直接的启动入口：

```text
mini-rpc-example-provider
└── io.github.kaano.minirpc.example.provider.ProviderMain
```

Provider 默认监听 `127.0.0.1:8080`。Starter 会自动发现 `@RpcService`、启动
Netty，并在端口绑定成功后把服务地址注册到 ZooKeeper。

### 4. 启动 Consumer

```text
mini-rpc-example-consumer
└── io.github.kaano.minirpc.example.consumer.ConsumerMain
```

Starter 会在 Spring Bean 初始化期间向 `@RpcReference` 字段注入代理。Consumer
调用 `HelloService` 时，不需要手动创建 Netty、ZooKeeper、序列化器或代理工厂。

更完整的双 Provider 运行方法和预期现象见
[本地运行指南](docs/getting-started.md)。

## 基本用法

### 定义共享接口

Provider 和 Consumer 共同依赖 API 模块，只共享接口与 DTO，不共享服务实现：

```java
public interface HelloService {

    String sayHello(String name);
}
```

### 暴露 Provider 服务

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "default",
        version = "1.0"
)
public class HelloServiceImpl implements HelloService {

    @Override
    public String sayHello(String name) {
        return "你好，" + name;
    }
}
```

`HelloServiceImpl` 是 Spring Bean，可以正常使用构造器注入。业务代码不负责启动
RPC 服务端，也不负责向 ZooKeeper 注册地址。

Provider 最小配置：

```yaml
mini-rpc:
  provider-enabled: true
  registry-address: 127.0.0.1:2181
  server-host: 127.0.0.1
  server-port: 8080
```

### 注入 Consumer 代理

```java
@Component
public class HelloFacade {

    @RpcReference(
            group = "default",
            version = "1.0",
            timeoutMillis = 750
    )
    private HelloService helloService;

    public String hello(String name) {
        return helloService.sayHello(name);
    }
}
```

Consumer 最小配置：

```yaml
mini-rpc:
  registry-address: 127.0.0.1:2181
  request-timeout: 5s
```

`group` 和 `version` 省略时使用 `default/1.0`；`timeoutMillis` 省略时使用全局
`mini-rpc.request-timeout`。全部配置、默认值和约束见
[Starter 配置参考](docs/configuration.md)。

## 模块结构

| 模块 | 职责 |
|---|---|
| `mini-rpc-core` | 协议、序列化、代理、Netty、注册中心、负载均衡和调用链实现 |
| `mini-rpc-spring-boot-starter` | 自动配置、注解处理和 Spring 生命周期集成 |
| `mini-rpc-example-api` | Provider 与 Consumer 共享的接口契约 |
| `mini-rpc-example-provider` | Spring Boot Provider 示例 |
| `mini-rpc-example-consumer` | Spring Boot Consumer 示例 |

依赖方向保持为：

```text
example-provider ─┐
                  ├─→ spring-boot-starter ─→ core
example-consumer ─┘
         │
         └─→ example-api ←─ example-provider
```

Consumer 不依赖 Provider，因此客户端无法直接访问 `HelloServiceImpl`。更完整的
模块边界、包职责和启动/调用链见[架构与调用链](docs/architecture.md)。

## 文档导航

| 文档 | 适合解决的问题 |
|---|---|
| [本地运行指南](docs/getting-started.md) | 如何构建、启动 ZooKeeper、Provider 和 Consumer |
| [架构与调用链](docs/architecture.md) | 模块如何协作，一次 RPC 如何完成 |
| [Starter 配置参考](docs/configuration.md) | 每个 `mini-rpc.*` 配置和扩展点有什么作用 |
| [自定义协议](docs/protocol.md) | 20 字节协议头、编解码和心跳消息如何工作 |
| [调试指南](docs/debugging.md) | 第一次阅读源码应按什么顺序打断点 |
| [生产成熟度差距](PRODUCTION_READINESS_GAP.md) | 当前实现与生产级 RPC 框架还有哪些差距 |
| [原始规划](process.md) | 项目最初的分阶段学习计划 |
| [开发日志](DEVELOPMENT_LOG.md) / [续篇](DEVELOPMENT_LOG2.md) | 每个阶段的修改、原因、验证与限制 |

## 当前状态

Spring Boot 主链路已经跑通，当前已完成：

- Provider/Consumer 自动配置和资源自动关闭；
- 服务注册发现、缓存、Watch 与负载均衡；
- group/version 路由和单引用超时；
- Provider 优雅停机、业务线程池隔离和过载拒绝；
- Consumer 心跳、Provider 空闲连接清理和按需重连；
- 两侧 Filter 链以及基础请求、耗时、在途请求和线程池指标；
- 单元测试与真实 Netty/Testcontainers 集成测试。

详细阶段记录以 `DEVELOPMENT_LOG.md` 和 `DEVELOPMENT_LOG2.md` 为准，README 不再
维护逐阶段完成度大表。

## 当前限制

- 当前只有单注册中心、单协议，内置 JSON/JDK 两种序列化和轮询/随机两种负载均衡；
- 暂无通用失败重试、熔断、限流、降级和 Serializer/LoadBalance SPI；
- deadline 的协作式结束需要业务主动调用 `RpcCallContext.checkDeadline()`，不会
  强制终止任意 Java 方法，也不会自动传播到业务创建的异步线程；
- Provider 只有全局业务线程池，Filter 也只能全局作用于本侧全部调用；
- 默认 `SimpleMeterRegistry` 不持久化，也不自动开放 Actuator/Prometheus 端点；
- 容器端到端测试依赖可用的 Docker 引擎；
- 旧的非 Spring `RpcServiceScanner` 仍要求服务实现提供公开无参构造方法。

完整限制、风险和后续优先级见
[PRODUCTION_READINESS_GAP.md](PRODUCTION_READINESS_GAP.md)。

## 下一步

当前优先方向是：

1. 接入 Spring Boot Actuator 与 Prometheus Registry；
2. 实现 Serializer/LoadBalance 的最小 SPI；
3. 补充 CI、性能基线和弱网/长稳测试；
4. 继续以可审核的小步骤完善容错能力，不默认对非幂等业务做透明重试。
