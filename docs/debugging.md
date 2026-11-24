# 调试指南

本文给出第一次阅读 mini-RPC 时的推荐断点顺序。不要一开始同时跟踪全部线程；先跑通
Provider 启动，再跟踪 Consumer 的一次调用，最后观察响应如何按 requestId 返回。

开始前请先按[本地运行指南](getting-started.md)启动 ZooKeeper 和 Provider。

## 1. Provider 启动与服务发布

按顺序设置断点：

1. `ProviderMain.main(...)`
2. `MiniRpcAutoConfiguration.rpcServiceBeanDefinitionRegistryPostProcessor(...)`
3. `RpcServiceBeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry(...)`
4. `HelloServiceImpl` 构造方法
5. `SpringRpcProviderLifecycle.start()`
6. `SpringRpcProviderLifecycle.registerService(...)`
7. `RpcProvider.registerService(...)`
8. `DefaultServiceProvider.registerService(...)`
9. `NettyRpcServer.start(...)`
10. `RpcProvider.publishServices()`
11. `ZooKeeperServiceRegistry.register(...)`

重点观察：

- `HelloServiceImpl` 是由 Spring 创建，而不是扫描器反射 `new` 出来；
- `ServiceKey` 中是否包含预期接口、group 和 version；
- Netty 的端口绑定是否先成功；
- ZooKeeper 注册是否由绑定成功回调触发。

如果端口绑定失败，`publishServices()` 不应执行。这能避免注册一个无法连接的地址。

## 2. Consumer 注入远程代理

按顺序设置断点：

1. `RpcReferenceBeanPostProcessor.postProcessProperties(...)`
2. `RpcClientFactory.injectReferences(...)`
3. `RpcReferenceInjector.inject(...)`
4. `RpcReferenceInjector.validateReferenceField(...)`
5. `RpcClientProxy.createProxy(...)`
6. `RpcReferenceInjector.setFieldValue(...)`
7. `HelloConsumerRunner.run(...)`

重点观察：

- `@RpcReference` 所在对象必须由 Spring 管理；
- 字段类型是接口；
- 注入后的 `helloService` 不是 `HelloServiceImpl`，而是 JDK 动态代理；
- 注解未填写超时时，代理使用全局 `request-timeout`。

## 3. Consumer 发出请求

按顺序设置断点：

1. `HelloConsumerRunner` 调用 `helloService.sayHello(...)`
2. `RpcClientProxy.invoke(...)`
3. `DefaultConsumerFilterChain.proceed(...)`
4. `ConsumerMetricsFilter.filter(...)`
5. `NettyRpcClient.sendRequest(...)`
6. `DefaultServiceAddressResolver.resolve(...)`
7. `ZooKeeperServiceDiscovery.discover(...)`
8. 当前 `LoadBalance.select(...)`
9. `PendingRequestManager.register(...)`
10. `RpcEncoder.encode(...)`

重点观察：

- `RpcRequest` 的 interfaceName、methodName、参数、group、version；
- 第一次调用查询 ZooKeeper，短时间重复调用命中本地地址缓存；
- 两个 Provider 存在时，轮询计数按 ServiceKey 分开维护；
- 有效超时如何变成 `deadlineEpochMillis`；
- requestId 如何和 `CompletableFuture` 建立映射；
- 编码后的协议头是否是 20 字节。

## 4. Provider 接收并执行

Provider 与 Consumer 是两个进程，需要切换到 Provider 调试窗口：

1. `RpcDecoder.decode(...)`
2. `NettyRpcServerHandler.channelRead0(...)`
3. `InFlightRequestTracker.tryAcquire()`
4. `ProviderBusinessExecutor.execute(...)`
5. `DefaultProviderFilterChain.proceed(...)`
6. `ProviderDeadlineFilter.filter(...)`
7. `ProviderMetricsFilter.filter(...)`
8. `RpcRequestHandler.handle(...)`
9. `DefaultServiceProvider.getService(...)`
10. `RpcCallContext.open(...)`
11. `HelloServiceImpl.sayHello(...)`
12. `RpcEncoder.encode(...)`

重点观察线程名：

- `RpcDecoder` 和 `NettyRpcServerHandler` 先运行在 Netty EventLoop；
- 服务查找、Filter 和 `HelloServiceImpl` 运行在
  `mini-rpc-provider-business-*` 业务线程；
- `writeAndFlush()` 可以从业务线程发起，真正编码和 Socket 写入会回到 Channel
  所属 EventLoop。

如果在 EventLoop 中长时间停住断点，会影响该线程管理的其他 Channel，所以调试
网络事件时应尽快继续。

## 5. Consumer 接收响应

切回 Consumer 进程，按顺序观察：

1. 客户端 `RpcDecoder.decode(...)`
2. `NettyRpcClientHandler.channelRead0(...)`
3. `PendingRequestManager.complete(...)`
4. Consumer Filter 后置逻辑
5. `RpcClientProxy.invoke(...)` 返回结果

确认响应 requestId 与发送请求一致，并且只完成对应 Future。若同一连接存在多个并发
请求，不能用“响应到达顺序”推断它属于哪个调用者。

## 6. 心跳调试

Consumer 示例已经可以打开传输层 DEBUG 日志：

```yaml
logging:
  level:
    io.github.kaano.minirpc.transport.client: DEBUG
```

先完成至少一次普通 RPC，让 Consumer 建立 Channel，然后观察：

1. `NettyRpcClientHeartbeatHandler.userEventTriggered(...)`
2. `RpcEncoder.encode(...)` 编码 PING
3. `NettyRpcServerHeartbeatHandler.channelRead0(...)`
4. Provider 写回 PONG
5. `NettyRpcClientHeartbeatHandler.channelRead0(...)` 消费 PONG

心跳不进入 `PendingRequestManager`、Provider 业务线程池或业务在途计数。仅启动
Consumer、尚未连接任何 Provider 时看不到心跳是正常现象。

## 7. 优雅停机调试

保持一个 Provider 请求正在处理，然后正常停止 Provider，依次观察：

1. `SpringRpcProviderLifecycle.stop()`
2. `RpcProvider.close()`
3. `RpcProvider.unpublishServices()`
4. `InFlightRequestTracker.stopAccepting()`
5. `NettyRpcServer.stopAcceptingConnections()`
6. `InFlightRequestTracker.awaitZero(...)`
7. `ProviderBusinessExecutor.close(...)`
8. Netty EventLoop 与 ZooKeeper 客户端关闭

在途计数会覆盖业务线程池排队、执行和响应写回。只有响应写入完成或失败后，计数才
归还；PING/PONG 不进入该计数。

## 常见现象

### 断点停留后 Consumer 超时

Consumer 的 `request-timeout` 仍在计时。断点暂停超过有效超时并不一定表示 RPC
逻辑错误。调试时可以临时增大全局超时或单引用 `timeoutMillis`。

### 找不到 Provider 地址

依次检查：

1. ZooKeeper 是否运行且地址一致；
2. Provider 的 Netty 端口是否绑定成功；
3. Provider 是否已经执行地址发布；
4. Consumer 和 Provider 的接口全限定名、group、version 是否完全一致；
5. `server-host` 是否是 Consumer 可以访问的地址。

### 两个 Provider 没有轮询

检查两个实例是否使用不同端口并都已注册到同一个 ServiceKey。轮询状态按 ServiceKey
分别维护，不同 group/version 的地址不会混在一起选择。

### Docker 测试被跳过

Testcontainers 找不到可用 Docker 引擎时会跳过容器端到端用例。先检查 Docker
Desktop 是否启动；普通单元测试和不依赖 Docker 的真实 Netty 心跳测试仍可运行。

### 看不到 Actuator 或 Prometheus 指标端点

当前 Starter 默认只提供 `SimpleMeterRegistry`，不会自动安装 Actuator、Prometheus
Registry 或开放 HTTP 端点。这是当前阶段边界，不是自动配置失败。

## 推荐阅读顺序

调试完成后，可以按以下顺序回看设计：

1. [架构与调用链](architecture.md)
2. [自定义协议](protocol.md)
3. [Starter 配置参考](configuration.md)
4. [开发日志续篇](../DEVELOPMENT_LOG2.md)
