# mini-RPC 开发记录（二）

本文档从 S5-H-C-B 开始续写。S5-H-C-A 以及更早阶段见
[`DEVELOPMENT_LOG.md`](DEVELOPMENT_LOG.md)，原始规划仍见 [`process.md`](process.md)。

后续每次代码、配置、依赖、模块结构或运行行为变化，都在本文档增加同一阶段的
目标、主要修改、行为变化、设计原因、验证方式、当前限制和下一步；架构与使用方式
变化仍同步更新 README。

## 1. 后续阶段记录

### 3.30 S5-H-C-B：Provider 业务线程池配置与过载诊断

- 日期：2026-10-24
- 对应提交：`a902c8e`
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

后续评审认为 S5-H-C-C 需要同时引入定时任务、`Future.cancel(...)` 和原子响应
竞争控制，而当前执行前 deadline 检查和业务代码协作式检查已经覆盖主要场景。
为避免在学习项目当前阶段过度设计，下一步调整为先补齐长连接心跳基础。

### 3.31 S5-I-A：心跳消息编解码与被动 PING/PONG

- 日期：2026-10-25
- 对应提交：本次心跳阶段提交
- 状态：已实现并验证

#### 目标

协议从 M0 起预留了心跳消息类型，但编解码器仍只认识 `RpcRequest` 和
`RpcResponse`，Provider 与 Consumer 的 Pipeline 也没有心跳分流能力。本步只
建立一条容易验证的被动链路：心跳对象能够跨越现有协议和序列化边界，Provider
收到 PING 后直接回复 PONG，Consumer 在普通响应处理前消费 PONG。

本步不同时加入定时器、读写空闲检测、断线判定和自动重连，避免把“协议是否正确”
与“连接生命周期策略是否正确”混在一次修改中。

#### 主要修改

- 新增 `RpcHeartbeat`，正文保存 `requestId`、PING/PONG 类型和创建时间；
- `RpcEncoder` 支持把心跳写成消息类型 3，并校验 requestId、心跳类型和时间戳；
- `RpcDecoder` 接受消息类型 3，根据协议头反序列化 `RpcHeartbeat`，并校验协议头
  requestId 与正文一致；
- 新增 `NettyRpcServerHeartbeatHandler`：收到 PING 后沿用 requestId 返回 PONG；
- 新增 `NettyRpcClientHeartbeatHandler`：在普通响应 Handler 前消费 PONG；
- 客户端和服务端 Pipeline 均在业务 Handler 前加入独立心跳 Handler；
- 新增 JSON/JDK 编解码往返、Provider PONG 回复、Consumer PONG 消费测试；
- 更新 README 的完成状态、协议说明、当前限制和下一步演进方向。

#### 行为变化与调用链

协议帧结构仍然是原来的“20 字节协议头 + 序列化正文”，没有为心跳新增第二套
传输格式。协议头中的 `messageType=3` 表示正文应还原为 `RpcHeartbeat`，正文中的
`heartbeatType` 再区分 PING 与 PONG。

```text
Consumer
   │
   │ RpcHeartbeat(PING, requestId=H1)
   ▼
RpcEncoder → TCP → RpcDecoder
                       │
                       ▼
          NettyRpcServerHeartbeatHandler
                       │  不进入 InFlightRequestTracker
                       │  不提交 ProviderBusinessExecutor
                       │  不查找或反射调用 @RpcService
                       ▼
             RpcHeartbeat(PONG, requestId=H1)
                       │
RpcDecoder ← TCP ← RpcEncoder
   │
   ▼
NettyRpcClientHeartbeatHandler
   │
   └── 消费 PONG，不进入 PendingRequestManager
```

普通 `RpcRequest` 与 `RpcResponse` 的调用链保持不变。`SimpleChannelInboundHandler`
会按泛型判断消息类型：心跳 Handler 遇到普通请求/响应时自动继续向后传播，遇到
`RpcHeartbeat` 时消费消息，因此不需要在一个大型 Handler 里手写多层类型分支。

#### 设计原因

- 心跳复用已有协议头、序列化器和 Channel，可以继续使用魔数、版本、正文长度和
  半包/粘包处理能力，不引入重复协议；
- PING/PONG 使用同一个 requestId，后续可以关联一次探测，但不会占用普通 RPC 的
  `PendingRequestManager`，避免心跳与业务 Future 混在一起；
- Provider 回复心跳只是一次很小的内存操作和网络写入，放在 EventLoop 中更合适；
  若进入有界业务线程池，业务高峰时心跳会跟着排队，可能把“业务繁忙”误判为
  “TCP 连接死亡”；
- Provider 收到 PONG、Consumer 收到 PING 时只消费而不再回复，避免异常方向的
  心跳形成无限 PING/PONG 循环；
- 时间戳只用于日志和后续延迟诊断，不用于跨机器比较超时。真正的失效判断应基于
  每个 Channel 本地的读空闲时间，避免要求两台机器时钟完全一致。

#### 验证方式

- `RpcHeartbeatCodecTest` 验证 JSON PING 和 JDK PONG 都能写入消息类型 3，并在
  解码后保留 requestId、心跳类型和时间戳；
- `NettyRpcServerHeartbeatHandlerTest` 验证 PING 返回相同 requestId 的 PONG，
  且不调用 `RpcRequestHandler`、不增加在途业务请求；
- `NettyRpcClientHeartbeatHandlerTest` 验证 PONG 被独立消费，不继续传播到普通
  RPC 响应匹配链；
- 已执行上述 4 个定向测试，全部通过；
- 执行根工程 `mvn test`，core 40 个测试中 39 个通过、Testcontainers 用例因
  Docker 未运行跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功。

#### 当前限制与下一步

- Consumer 还不会主动创建或定时发送 PING；
- Consumer 尚未保存最近一次 PONG，也没有“连续多少时间未读到数据”的判断；
- 连接长期无响应时仍不会主动关闭，自动重连也尚未由心跳触发；
- 当前没有把心跳次数和断线次数接入 Micrometer；
- 下一步建议实现 S5-I-B：在 Consumer Pipeline 使用 Netty `IdleStateHandler`，
  写空闲时自动发 PING，读空闲达到阈值时关闭 Channel；关闭事件继续复用现有
  `failAll` 和 Channel 缓存删除逻辑，使下一次业务调用按需建立新连接。

### 3.32 S5-I-B-A：Consumer 写空闲心跳与读空闲断连

- 日期：2026-10-25
- 对应提交：本次心跳阶段提交
- 状态：已实现并验证

#### 目标

S5-I-A 已经打通 PING/PONG 的协议、编解码和处理器链路，但生产运行时仍没有组件
主动发出 PING。本步让每条已经建立的 Consumer Channel 独立观察读写空闲：没有
业务数据可写时发送心跳，长期没有读到 PONG 或普通响应时关闭失效连接。

本步只补齐 Consumer 连接存活检测，不同时加入 Starter 配置、Provider 空闲清理、
后台无限重连或失败请求自动重试。

#### 主要修改

- `NettyRpcClient` 新增默认 15 秒心跳间隔和 45 秒读空闲超时常量；
- 每条新建的客户端 Channel 在 Pipeline 头部创建独立 `IdleStateHandler`；
- `NettyRpcClientHeartbeatHandler` 处理 `WRITER_IDLE`，生成正数心跳 requestId 并
  写出 PING；
- 同一 Handler 处理 `READER_IDLE`，关闭长期未收到任何数据的 Channel；
- PING 写入失败时使用 Netty `CLOSE_ON_FAILURE` 关闭当前连接；
- 与心跳无关的用户事件继续向 Pipeline 后方传播，避免吞掉其他扩展事件；
- 扩展 `NettyRpcClientHeartbeatHandlerTest`，覆盖写空闲、读空闲、连续心跳编号和
  非心跳事件传播；
- README 同步更新阶段状态、运行链路、当前限制和下一步。

#### 行为变化与调用链

客户端只有在第一次业务调用建立 Channel 后，才会为该连接启动空闲检测；没有
任何 Provider Channel 时不会凭空启动心跳。

正常存活链路：

```text
Channel 连续 15 秒没有写数据
        ↓
IdleStateHandler 触发 WRITER_IDLE
        ↓
NettyRpcClientHeartbeatHandler 创建 PING
        ↓
RpcEncoder 编码并写入原 Channel
        ↓
Provider EventLoop 直接回复 PONG
        ↓
Consumer 收到 PONG，读空闲时间重新计算
```

失效连接清理链路：

```text
Channel 连续 45 秒没有读到任何数据
        ↓
IdleStateHandler 触发 READER_IDLE
        ↓
NettyRpcClientHeartbeatHandler 关闭 Channel
        ↓
NettyRpcClientHandler.channelInactive(...)
        ↓
PendingRequestManager.failAll(...)
        ↓
closeFuture listener 删除 channels 中的旧连接
        ↓
下一次 sendRequest(...) 通过 getOrCreateChannel(...) 按需重连
```

普通请求写出也会重新计算写空闲时间，普通响应和 PONG 都会重新计算读空闲时间。
因此业务流量活跃时无需额外 PING；耗时业务即使尚未返回结果，Provider EventLoop
仍可绕过业务线程池回复 PONG，避免把“业务执行时间长”误判成“连接死亡”。

#### 设计原因

- 使用 Netty `IdleStateHandler`，而不是自己维护 `ScheduledExecutorService`；每条
  Channel 的定时任务由所属 EventLoop 调度，并随 Channel 关闭而取消；
- `IdleStateHandler` 内部保存最后读写时间和定时状态，因此每条 Channel 创建一个，
  不能共享；心跳业务 Handler 只有线程安全的 `AtomicLong`，可以跨 Channel 共享；
- 采用“写空闲才发送”而不是固定频率无条件发送，正常 RPC 流量本身即可证明连接
  正在使用，避免额外网络消息；
- 读空闲 45 秒是心跳间隔 15 秒的三倍，允许一次或两次短暂延迟，不因单个 PONG
  抖动立即断开；
- 关闭后只在下一次业务调用时按需重连，不做后台无限重连，避免 Provider 长期
  不可用时形成重连风暴；
- 不自动重试当前失败请求，因为创建订单、扣款等非幂等操作可能已经在 Provider
  执行成功，盲目重试会造成重复副作用。

#### 验证方式

- `NettyRpcClientHeartbeatHandlerTest` 验证 PONG 仍被独立消费；
- 模拟 `WRITER_IDLE`，验证产生 PING、requestId 与时间戳为正数、Channel 保持活动；
- 连续触发两次写空闲，验证 PING requestId 不重复；
- 模拟 `READER_IDLE`，验证 Channel 被关闭；
- 发送普通用户事件，验证心跳 Handler 会继续向后传播；
- 执行 7 个心跳相关定向测试，全部通过；
- 执行根工程 `mvn test`，core 43 个测试中 42 个通过、Testcontainers 用例因
  Docker 未运行跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功。

#### 当前限制与下一步

- 15 秒心跳间隔和 45 秒读空闲超时目前是 core 默认值，尚未接入
  `application.yml`；
- 目前只由 Consumer 检测 Provider 连接，Provider 还没有读空闲清理客户端连接；
- 没有记录心跳次数、断线次数、最近 PONG 时间等 Micrometer 指标；
- Channel 失效会让当前等待请求失败，框架不会自动重试该请求；
- 下一步建议实现 S5-I-B-B：增加 `mini-rpc.heartbeat-interval` 和
  `mini-rpc.heartbeat-timeout`，通过 Starter 传入 `NettyRpcClient`，并校验超时
  必须大于心跳间隔。

### 3.33 S5-I-B-B：Consumer 心跳时间 Starter 配置化

- 日期：2026-10-25
- 对应提交：本次心跳阶段提交
- 状态：已实现并验证

#### 目标

S5-I-B-A 已经让 Consumer 按默认 15 秒/45 秒执行写空闲心跳和读空闲断连，但
不同部署环境对网络抖动、空闲连接数量和故障发现速度的要求不同。本步把两个时间
接入 Spring Boot Starter，同时保留安全默认值和原有 core 构造方法兼容性。

#### 主要修改

- `MiniRpcProperties` 新增 `heartbeatInterval` 和 `heartbeatTimeout`，分别绑定
  `mini-rpc.heartbeat-interval` 与 `mini-rpc.heartbeat-timeout`；
- 两个属性默认复用 `NettyRpcClient` 的 15 秒和 45 秒常量，避免 Starter 与 core
  各写一份容易漂移的默认值；
- `MiniRpcAutoConfiguration` 把两个 Duration 传给 `RpcClientFactory`；
- `RpcClientFactory` 增加完整构造方法，把请求超时、心跳间隔、心跳超时继续传到
  `NettyRpcClient`，旧构造方法仍使用默认心跳参数；
- `NettyRpcClient` 增加完整构造方法、毫秒字段和诊断 getter，Pipeline 创建
  `IdleStateHandler` 时改用实例配置，不再直接读取固定常量；
- Netty 客户端在创建 EventLoopGroup 之前校验心跳间隔、超时时间至少为 1 毫秒，
  并校验 `heartbeatTimeout > heartbeatInterval`；
- Consumer 示例 `application.yml` 增加心跳配置示例；
- 新增 core 心跳配置测试，并扩展 Starter kebab-case 属性绑定测试；
- README 同步更新配置示例、约束、当前限制和下一步。

#### 行为变化与配置链

Consumer 现在可以按部署环境调整心跳：

```yaml
mini-rpc:
  heartbeat-interval: 10s
  heartbeat-timeout: 30s
```

配置传递链为：

```text
application.yml
        ↓
MiniRpcProperties
        ↓
MiniRpcAutoConfiguration
        ↓
RpcClientFactory
        ↓
NettyRpcClient
        ↓
每条 Channel 自己的 IdleStateHandler
```

`heartbeat-interval` 是写空闲阈值，达到后发送 PING；`heartbeat-timeout` 是读空闲
阈值，达到后关闭 Channel。配置只影响以后新建 Channel 安装的空闲处理器；
Spring 管理的 Netty 客户端在应用运行期间不会动态替换这两个值。

#### 设计原因

- 心跳属于连接级能力：多个 `@RpcReference` 可能复用同一条 Provider Channel，
  因此参数放在全局 `mini-rpc` 配置，而不是加入单引用注解；
- 超时时间必须大于心跳间隔，否则 Channel 可能还没获得一次合理的 PING/PONG
  机会就先被判死；
- 使用 Spring Boot 原生 `Duration` 绑定，配置可以写成 `500ms`、`15s`、`1m`，
  不需要业务方记住裸数字单位；
- 校验放在 `NettyRpcClient`，这样 Spring 与非 Spring 入口遵守同一约束，且非法
  配置不会在创建 Netty 线程后才失败；
- 保留旧构造方法并委托到完整构造方法，原生 Socket 教学代码和早期手动组装入口
  不需要跟着修改，也不会丢失默认心跳能力。

#### 验证方式

- `NettyRpcClientHeartbeatConfigurationTest` 验证自定义 7 秒/21 秒真正进入客户端；
- 验证 0 秒心跳间隔被拒绝；
- 验证心跳超时等于间隔时被拒绝；
- `MiniRpcAutoConfigurationTest` 验证 kebab-case 的 7 秒/21 秒配置正确绑定；
- 执行 core 与 Starter 共 18 个定向测试，全部通过；
- 执行根工程 `mvn test`，core 46 个测试中 45 个通过、Testcontainers 用例因
  Docker 未运行跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功。

#### 当前限制与下一步

- 心跳参数是应用启动时读取的全局值，暂不支持运行期动态刷新；
- 同一个 Consumer 的所有 Provider Channel 使用相同时间，不能按服务或地址覆盖；
- Provider 仍未主动关闭长期没有收到 Consumer 数据的半开连接；
- 心跳与断线次数仍只体现在日志和行为中，没有接入 Micrometer；
- 下一步建议实现 S5-I-C：Provider 使用读空闲检测清理静默客户端 Channel，且
  Provider 的读空闲阈值应明显大于 Consumer 的心跳间隔。
