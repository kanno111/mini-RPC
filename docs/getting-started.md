# 本地运行指南

本文用于从源码构建 mini-RPC，并在本机跑通 Spring Boot Provider 与 Consumer。
项目当前是 `1.0-SNAPSHOT`，尚未作为可直接下载的正式依赖发布，因此示例应从
仓库根目录整体构建。

## 环境要求

| 工具 | 要求 | 用途 |
|---|---|---|
| JDK | 21 | 编译和运行所有模块 |
| Maven | 3.9 或兼容版本 | 多模块构建与测试 |
| ZooKeeper | 默认 `127.0.0.1:2181` | 服务注册与发现 |
| Docker Desktop | 可选 | 快速启动 ZooKeeper、运行 Testcontainers 测试 |
| IntelliJ IDEA | 当前示例推荐 | 直接运行两个 Spring Boot 主类 |

先确认 Java 和 Maven 版本：

```powershell
java -version
mvn -version
```

## 启动 ZooKeeper

### 第一次创建容器

```powershell
docker run -d --name mini-rpc-zookeeper -p 2181:2181 zookeeper:3.9.6
```

### 已经创建过容器

```powershell
docker start mini-rpc-zookeeper
```

### 检查运行状态

```powershell
docker ps --filter "name=mini-rpc-zookeeper"
```

Provider 和 Consumer 的示例配置都默认连接 `127.0.0.1:2181`。如果使用其他地址，
需要同时调整两个示例模块的 `application.yml`。

## 构建整个项目

在仓库根目录执行：

```powershell
mvn test
```

这条命令会按依赖顺序构建父工程、core、Starter 和三个示例模块，并运行全部
单元测试。

Docker 可用时，Testcontainers 还会临时启动 `zookeeper:3.9.6-jre-17`，执行真实
ZooKeeper + Netty 端到端测试。测试中的容器会自动创建和删除，不使用前面手工
启动的 `mini-rpc-zookeeper` 容器。

端到端测试主要验证：

- Provider 创建和删除 ZooKeeper 临时节点；
- Consumer 发现地址并通过动态代理完成 RPC；
- ZooKeeper Watch 让地址缓存提前失效；
- group/version 不同的 Provider 能被精确隔离；
- 真实 Netty 连接上的 PING/PONG、超时断连与按需重连。

Docker 不可用时，容器测试会标记为跳过，不影响其余单元测试执行。

## 启动 Provider

在 IDEA 中运行：

```text
模块：mini-rpc-example-provider
主类：io.github.kaano.minirpc.example.provider.ProviderMain
```

启动过程如下：

```text
ProviderMain
    → Spring Boot 自动配置
    → 扫描并创建 @RpcService Spring Bean
    → Netty 绑定 127.0.0.1:8080
    → 绑定成功后向 ZooKeeper 发布服务地址
```

默认 Provider 配置位于：

```text
mini-rpc-example-provider/src/main/resources/application.yml
```

如果端口 8080 已被占用，Netty 绑定会失败，Provider 不会把不可连接的地址发布到
ZooKeeper。

## 启动第二个 Provider

要观察轮询负载均衡，可以在 IDEA 中创建两份 `ProviderMain` 运行配置：

1. 允许多个实例，或创建两个独立运行配置；
2. 第一份程序参数填写 `--mini-rpc.server-port=8080`；
3. 第二份程序参数填写 `--mini-rpc.server-port=8081`；
4. 分别启动两个 Provider。

`HelloServiceImpl` 的响应包含当前 Java 进程 ID，便于确认请求落到了哪个实例。

## 启动 Consumer

在 IDEA 中运行：

```text
模块：mini-rpc-example-consumer
主类：io.github.kaano.minirpc.example.consumer.ConsumerMain
```

Consumer 启动后，`HelloConsumerRunner` 使用已经注入的 `HelloService` 动态代理发起
调用。若 8080 和 8081 两个 Provider 都已注册，默认轮询策略会在两个地址之间选择。

推荐启动顺序：

```text
1. ZooKeeper
2. Provider 8080
3. Provider 8081（可选）
4. Consumer
```

只启动 Consumer 而没有可用 Provider 时，服务发现无法得到目标地址，调用会失败；
仅仅创建 Consumer Spring 容器但还没有真正使用远程引用时，延迟创建的客户端工厂
也不会主动连接 ZooKeeper。

## 修改运行参数

Spring Boot 命令行参数可以覆盖 `application.yml`，例如：

```text
--mini-rpc.registry-address=127.0.0.1:2181
--mini-rpc.server-port=8081
--mini-rpc.serializer=json
--mini-rpc.load-balance=random
```

全部配置项、默认值和约束见[Starter 配置参考](configuration.md)。

## 停止顺序

正常关闭 Provider 时，Starter 会自动：

1. 从 ZooKeeper 注销已发布地址；
2. 停止接受新的业务请求和连接；
3. 在 `shutdown-timeout` 内等待在途响应写回；
4. 关闭业务线程池、Netty 和 ZooKeeper 会话。

因此应通过 IDE 的正常停止操作或进程关闭信号结束应用，不要依赖强制终止来观察
优雅停机行为。

## 下一步阅读

- 想理解启动和调用过程：阅读[架构与调用链](architecture.md)；
- 想逐步跟踪源码：阅读[调试指南](debugging.md)；
- 想理解网络报文：阅读[自定义协议](protocol.md)。
