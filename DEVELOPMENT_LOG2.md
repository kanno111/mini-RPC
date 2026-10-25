# mini-RPC 开发记录（二）

本文档从 S5-H-C-B 开始续写。S5-H-C-A 以及更早阶段见
[`DEVELOPMENT_LOG.md`](DEVELOPMENT_LOG.md)，原始规划仍见 [`process.md`](process.md)。

后续每次代码、配置、依赖、模块结构或运行行为变化，都在本文档增加同一阶段的
目标、主要修改、行为变化、设计原因、验证方式、当前限制和下一步；架构与使用方式
变化仍同步更新 README。

## 1. 后续阶段记录

### 3.30 S5-H-C-B：Provider 业务线程池配置与过载诊断

- 日期：2026-10-24
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

S5-H-C-A 已完成 Netty I/O 与业务线程隔离，但业务线程数和队列容量还是 core 内部
默认值。不同机器和业务负载无法调整，线程池拒绝任务时也只能看到通用失败响应，
缺少判断“容量不足”还是“Provider 正在关闭”的运行数据。本步把关键容量接入
Spring Boot Starter，并补充拒绝现场诊断。

#### 主要修改

- `MiniRpcProperties` 新增 `providerBusinessThreads` 和
  `providerBusinessQueueCapacity`，分别绑定
  `mini-rpc.provider-business-threads` 与
  `mini-rpc.provider-business-queue-capacity`；
- `MiniRpcAutoConfiguration` 将两个参数继续传给 `RpcProvider`，再传到
  `NettyRpcServer` 和 `ProviderBusinessExecutor`；业务应用仍不直接创建线程池；
- `NettyRpcServer` 公开默认线程数和默认队列容量常量，旧构造方法继续使用默认值，
  保持早期教学入口兼容；
- `ProviderBusinessExecutor` 在提交被拒绝时累计拒绝次数，并提供不可变运行快照：
  配置线程数、活跃线程数、排队数、队列剩余容量、完成数、拒绝数和关闭状态；
- `NettyRpcServerHandler` 捕获拒绝后记录上述快照，同时继续向 Consumer 返回快速
  失败响应并归还在途计数；
- Provider 示例 `application.yml` 增加线程数和队列容量示例；
- 新增属性绑定、非法容量和过载快照测试；README 同步更新配置、能力和限制。

#### 行为变化

Provider 现在可以按部署机器和业务特点配置：

```yaml
mini-rpc:
  provider-business-threads: 8
  provider-business-queue-capacity: 1024
```

调用链为：

```text
application.yml
        ↓
MiniRpcProperties
        ↓
MiniRpcAutoConfiguration
        ↓
RpcProvider
        ↓
NettyRpcServer
        ↓
ProviderBusinessExecutor
```

当工作线程和队列全部占满时，新请求不会进入 Netty EventLoop 执行业务，也不会
无限排队。Provider 会快速返回失败，并在日志中输出类似以下诊断维度：

```text
线程数 / 活跃线程 / 排队任务 / 队列剩余 / 累计完成 / 累计拒绝 / 是否关闭
```

#### 设计原因

- 线程数没有适合所有应用的固定答案：CPU 密集任务和大量外部 I/O 等待需要不同
  配置，因此框架提供安全默认值，同时允许部署环境覆盖；
- 队列容量决定 Provider 最多接受多少排队工作，显式配置可以在吞吐、等待时间和
  内存占用之间做出清晰取舍；
- 拒绝计数在执行器内部累加，既覆盖容量饱和，也覆盖关闭期间的提交竞争；
- 诊断数据只写 Provider 日志，Consumer 仍收到稳定、简短的失败信息，避免把内部
  容量细节暴露到远程协议；
- 本步仍使用固定大小业务线程池，只开放当前确实生效的两个参数，不提前暴露尚未
  实现语义的 core/max/keep-alive 配置。

#### 验证方式、当前限制与下一步

- `MiniRpcAutoConfigurationTest` 验证 kebab-case 配置正确绑定线程数和队列容量；
- `ProviderBusinessExecutorTest` 验证非法线程数/队列容量启动失败，并验证队列满时
  活跃数、排队数、剩余容量和累计拒绝数；
- `NettyRpcServerHandlerDrainTest` 继续验证拒绝后快速失败且在途计数归零；
- 已执行 core 与 Starter 定向测试，全部通过；
- 执行根工程 `mvn test`，core 36 个测试中 35 个通过、容器测试因 Docker 未运行
  跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功；
- 当前日志统计是发生拒绝时的即时快照，尚未接入 Micrometer 持续指标；
- 当前只有全局 Provider 线程池，还没有按服务隔离的线程池；
- deadline 到达后还不会自动取消已经提交或正在执行的业务 `Future`；
- 下一步建议实现 S5-H-C-C：为单次服务端执行建立原子完成状态，处理业务完成与
  deadline 到达的竞争，并保证每个 requestId 最多写回一次响应。
