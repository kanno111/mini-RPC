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
- 对应提交：`5411ad8`
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
- 对应提交：`5411ad8`
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
- 对应提交：`5411ad8`
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

### 3.34 S5-I-C：Provider 静默客户端 Channel 清理

- 日期：2026-10-25
- 对应提交：本次 Provider 连接清理与心跳可观察性阶段提交
- 状态：已实现并验证

#### 目标

Consumer 已能发现 Provider 长期无响应并关闭连接，但反方向仍不完整：Consumer
进程崩溃、机器断网或中间网络设备静默丢弃连接时，Provider 可能暂时保留一条
表面未关闭、实际不会再发送数据的半开 Channel。本步让 Provider 按每条客户端
连接的读空闲时间清理这类静默连接。

#### 主要修改

- `NettyRpcServer` 新增默认 60 秒 `DEFAULT_CLIENT_IDLE_TIMEOUT` 和实例毫秒字段；
- 服务端每条客户端 Channel 的 Pipeline 头部创建独立 `IdleStateHandler`，只启用
  reader-idle，不观察 Provider 自己的写空闲；
- 新增共享的 `NettyRpcServerIdleConnectionHandler`，收到 READER_IDLE 时只关闭
  当前客户端 Channel，其他用户事件继续传播；
- `NettyRpcServer` 新增完整构造方法、正数校验和诊断 getter，旧构造方法继续使用
  60 秒默认值；
- `RpcProvider` 增加完整构造方法，把客户端读空闲参数传到 Netty 服务端，同时
  保留旧构造方法兼容；
- `MiniRpcProperties` 新增 `providerClientIdleTimeout`，绑定
  `mini-rpc.provider-client-idle-timeout`；
- `MiniRpcAutoConfiguration` 将配置继续传给 `RpcProvider`；
- Provider 示例 `application.yml` 增加 60 秒配置与说明；
- 新增 Provider 读空闲处理器、配置生效和非法配置测试，并扩展 Starter 属性绑定；
- README 同步更新完成状态、配置说明、运行行为和限制。

#### 行为变化与调用链

Provider 现在可以配置：

```yaml
mini-rpc:
  provider-client-idle-timeout: 60s
```

配置传递链为：

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
每条客户端 Channel 自己的 IdleStateHandler
```

运行链路为：

```text
普通 RpcRequest 或 PING 到达 Provider
        ↓
该 Channel 的最后读取时间刷新

连续 60 秒没有任何入站数据
        ↓
IdleStateHandler 触发 READER_IDLE
        ↓
NettyRpcServerIdleConnectionHandler
        ↓
只关闭当前客户端 Channel
```

服务端监听 Channel、其他 Consumer Channel、worker EventLoopGroup 和业务线程池
不会被这个事件关闭。若该连接仍有业务任务执行，任务完成后的响应写入会失败并
通过原有 listener 归还在途计数；Java 框架不会强制终止用户业务代码。

#### 设计原因

- `IdleStateHandler` 放在解码器之前观察实际入站字节，普通请求和 PING 都可以证明
  客户端仍存活，不需要额外维护“最后一次心跳时间”字段；
- Provider 只需要 reader-idle：心跳由 Consumer 发起，Provider 没有必要再维护
  第二套主动 PING，避免双向心跳增加协议和状态复杂度；
- 每条 Channel 创建独立 IdleStateHandler，因为其中保存连接级时间与定时任务；
  关闭动作处理器无连接状态，可以安全共享；
- 默认 60 秒是 Consumer 默认 15 秒心跳间隔的四倍，允许短暂抖动和少量心跳丢失；
- Provider 与 Consumer 属于不同进程，无法在启动时可靠比较双方配置，因此只校验
  Provider 阈值为正数，并在文档中要求部署者让它明显大于 Consumer 心跳间隔；
- 清理只针对子 Channel，不调用 `NettyRpcServer.stop()`，防止一个异常客户端影响
  整个 Provider 和其他 Consumer。

#### 验证方式

- `NettyRpcServerIdleConnectionHandlerTest` 验证 READER_IDLE 关闭当前 Channel；
- 验证非读空闲用户事件继续向 Pipeline 后方传播；
- `NettyRpcServerIdleConfigurationTest` 验证自定义 70 秒进入 Netty 服务端；
- 验证 0 秒读空闲配置在创建线程和绑定端口前被拒绝；
- `MiniRpcAutoConfigurationTest` 验证 kebab-case 的 70 秒配置正确绑定；
- 执行 Provider core 与 Starter 共 16 个定向测试，全部通过；
- Docker 启动后再次执行根工程 `mvn test`，core 50 个测试全部通过，
  包含真实 ZooKeeper + Netty 的 Testcontainers 用例；Starter 12 个测试全部
  通过，六个 Maven 模块构建成功。

#### 当前限制与下一步

- Consumer 与 Provider 空闲参数需要部署者自行协调，协议尚未协商心跳能力；
- Provider 关闭静默 Channel 时只记录日志，没有持续的活跃连接数和断线指标；
- 已经进入业务线程的用户代码不会因 Channel 关闭而自动取消；
- 当前测试分别验证编解码、空闲事件和 Handler 行为，尚缺真实 Netty 连接下的
  “空闲 → 关闭 → 下一次调用重连”集成测试；
- 下一步建议实现 S5-I-D：使用短心跳参数和真实 Netty Channel 编写无需
  ZooKeeper 的连接生命周期集成测试，再开始设计有限重试。

### 3.35 Consumer 示例开启心跳 DEBUG 日志

- 日期：2026-10-25
- 对应提交：本次 Provider 连接清理与心跳可观察性阶段提交
- 状态：已实现并验证

#### 目标

让学习者运行 Consumer 示例时，可以直接在控制台观察自动发送 PING 与
收到 PONG 的过程，无需把整个应用或 Netty 的日志级别调为 DEBUG。

#### 主要修改

- 在 Consumer 示例 `application.yml` 中将
  `io.github.kaano.minirpc.transport.client` 日志级别设为 `DEBUG`；
- 在 README 中说明该配置只用于示例观察，不是 Starter 强制的
  全局日志策略。

#### 行为变化

Consumer 完成首次 RPC 调用并建立 Channel 后，如果连续 15 秒没有写业务
数据，控制台现在会输出“发送 PING”；Provider 回复后会输出“收到
PONG”。心跳发送频率、协议和连接生命周期都没有变化。

#### 设计原因

- 只打开 mini-RPC Consumer 传输包，避免 Spring、ZooKeeper 和 Netty 内部
  DEBUG 日志淹没心跳主线；
- 保留框架代码中正常心跳的 DEBUG 级别，避免真实项目在 INFO 级别下
  每隔数秒输出两条日志；
- 把可观察性选择放在示例应用，不让 Starter 擅自修改业务应用的
  日志策略。

#### 验证方式

- 检查 YAML 层级为 `logging.level.io.github.kaano.minirpc.transport.client: DEBUG`；
- 启动 Provider 和 Consumer，完成一次 RPC 调用并等待一个
  `heartbeat-interval`，实际观察到约 15 秒和 30 秒时分别完成
  requestId 为 1、2 的 PING/PONG 往返；
- 执行 `mvn -q -pl mini-rpc-example-consumer -am -DskipTests package`，
  Consumer 及其依赖模块构建成功；
- 本次只修改示例日志配置和文档，不修改 Java 调用链。

#### 当前限制与下一步

- 只有已建立的 Channel 会进入写空闲计时，启动 Consumer 但从未调用
  Provider 时不会出现心跳日志；
- Provider 正常回复 PONG 暂时没有独立日志，但 Consumer 收到 PONG 已能
  证明完整往返链路；
- 下一步仍建议实现 S5-I-D 真实 Netty 心跳生命周期集成测试。

### 3.36 S5-I-D-A：真实 Netty PING/PONG 往返集成测试

- 日期：2026-10-25
- 对应提交：本次真实 Netty 心跳生命周期测试阶段提交
- 状态：已实现并验证

#### 目标

不再只依赖 `EmbeddedChannel` 分别测试心跳 Handler，而是启动真实
Netty Provider 和 Consumer，自动证明协议编解码、Pipeline 顺序、空闲事件
和 PING/PONG Handler 能在操作系统 TCP 连接上完整协作。

#### 主要修改

- 新增 `NettyHeartbeatRoundTripIntegrationTest`；
- 通过本机随机端口启动真实 `NettyRpcServer`，避免占用示例的
  8080 端口；
- 使用固定地址解析器将 `NettyRpcClient` 直接指向测试 Provider，
  不引入 ZooKeeper 或 Docker；
- 心跳间隔缩短为 100ms，Consumer 读空闲超时设为 600ms，
  Provider 读空闲设为 2s；
- 在 1.3s 观察窗口内反复检查 Channel 存活，然后再次发起真实
  RPC 并验证 Provider 业务方法共执行两次。

#### 行为变化与调用链

本步只增加测试和文档，不改变生产运行行为。测试覆盖的真实链路是：

```text
第一次 RPC 调用
        ↓
建立真实 TCP Channel 并完成请求/响应
        ↓
停止发送业务请求
        ↓
Consumer 100ms 写空闲 → PING
        ↓
Provider 解码 PING → 返回 PONG
        ↓
Consumer 收到 PONG 并刷新读空闲计时
        ↓
连续 1.3s 观察期间 Channel 始终存活
        ↓
第二次 RPC 调用仍成功
```

#### 设计原因

- 不使用 ZooKeeper，使失败范围聚焦在 Netty 和心跳链路，同时让没有
  Docker 的开发机和 CI 也能执行；
- 观察窗口大于两倍 Consumer 读超时：如果 PING/PONG 没有往返，
  Channel 会在测试结束前被关闭；
- 观察期间不发业务请求，避免普通响应刷新读计时，确保连接
  存活确实来自 PONG；
- 不为了测试暴露生产 Channel 或增加测试钩子，使用已有
  `isConnected()` 与第二次 RPC 证明连接和业务链路都正常；
- 本步只测正常往返，将超时断连和重连留给下一个小步骤，
  避免一个测试同时包含太多状态转换。

#### 验证方式

- 执行
  `mvn -q -pl mini-rpc-core -Dtest=NettyHeartbeatRoundTripIntegrationTest test`；
- 新测试通过：首次 RPC、1.3s Channel 存活观察、第二次 RPC 与
  Provider 执行次数断言均成功。
- 执行根工程 `mvn test`，core 51 个测试全部通过，包含新心跳测试与
  Testcontainers 端到端测试；Starter 12 个测试全部通过，六个 Maven
  模块全部构建成功。

#### 当前限制与下一步

- 当前用例验证了正常 PING/PONG 保活，尚未覆盖 Provider 不回 PONG
  的静默故障；
- `isConnected()` 只表示客户端当前存在活跃 Channel，不是生产级心跳
  计数器或可观测指标；
- 下一步建议实现 S5-I-D-B：真实验证心跳超时关闭旧 Channel，
  以及 Provider 恢复后下一次 RPC 建立新连接。

### 3.37 S5-I-D-B：心跳超时断连与按需重连集成测试

- 日期：2026-10-25
- 对应提交：本次真实 Netty 心跳生命周期测试阶段提交
- 状态：已实现并验证

#### 目标

在真实 TCP 连接上验证心跳故障分支：Provider 长期不回 PONG 时，
Consumer 应在读空闲超时后关闭并移除旧 Channel；当 Provider 恢复且
下一次 RPC 到来时，Consumer 应按需建立新 Channel，而不是在无业务
请求时不断后台重连。

#### 主要修改

- 新增 `NettyHeartbeatTimeoutReconnectIntegrationTest`；
- 在测试源集中实现最小 `FaultInjectingHeartbeatServer`，复用生产
  `RpcDecoder` 和 `RpcEncoder`；
- 故障注入服务端始终回复普通 `RpcRequest`，但第一阶段故意
  丢弃 PING，用计数器记录连接、断连、PING、PONG 和业务请求；
- Consumer 使用 100ms 心跳间隔和 600ms 读空闲超时；
- 故障恢复后再观察 1.3s，验证新 Channel 会因 PONG 持续到达而保持
  存活，并被第三次 RPC 复用。

#### 行为变化与调用链

本步仍只新增测试和文档，不修改生产运行行为，也没有向 Starter
增加故障开关。测试覆盖的状态转换是：

```text
第一次 RPC 建立 Channel-1 并成功返回
        ↓
Consumer 写空闲后发送 PING
        ↓
测试 Provider 故意丢弃 PING
        ↓
Consumer 600ms 未收到任何数据
        ↓
READER_IDLE 关闭 Channel-1，连接缓存移除旧 Channel
        ↓
测试 Provider 恢复回复 PONG
        ↓
第二次 RPC 到来时建立 Channel-2
        ↓
Channel-2 通过 PING/PONG 持续存活
        ↓
第三次 RPC 复用 Channel-2
```

#### 设计原因

- 不修改生产 `NettyRpcServer` 来关闭 PONG，避免为测试暴露危险配置；
- 故障服务端仍使用真实 Netty、TCP 和生产协议编解码，不是
  `EmbeddedChannel` 或 Mockito 模拟；
- 故障期仍正常回复普通 RPC，能把“服务不可用”与“心跳回复静默”
  区分开；
- 等待断连使用有上限的条件轮询，条件成立立即继续，不用固定
  长时间 sleep 猜测异步事件何时完成；
- 连接超时后不主动后台重连，等待下一次真实请求再建连，避免
  Provider 故障时大量 Consumer 形成无限重连风暴。

#### 验证方式

- 执行
  `mvn -q -pl mini-rpc-core -Dtest=NettyHeartbeatTimeoutReconnectIntegrationTest test`；
- 新测试通过：观察到至少一次被丢弃的 PING、Channel-1 超时关闭、
  Channel-2 新建、恢复后 PONG 到达，以及第三次 RPC 复用 Channel-2。
- 执行根工程 `mvn test`，core 52 个测试全部通过，包含两个真实
  Netty 心跳集成测试和 Testcontainers 端到端测试；Starter 12 个
  测试全部通过，六个 Maven 模块全部构建成功。

#### 当前限制与下一步

- 故障注入 Provider 是测试夹具，不会验证生产 Provider 的业务线程池；
  生产 Provider 的正常 PONG 往返已由 S5-I-D-A 覆盖；
- 当前的“重连”指旧 Channel 失效后，下一次业务调用新建 Channel，
  不是后台定时无限重连；
- 框架仍没有“失败后自动重试当前 RPC”，这与连接重建是两个不同概念；
- 下一步建议进入 M5-A：先定义只针对“明确未到达 Provider”失败的
  有限重试边界，避免非幂等业务被重复执行。

### 3.38 生产成熟度与主流 RPC 框架差距评估

- 日期：2026-10-25
- 评估基线：3.37 / S5-I-D-B，对应提交 `8cb35ed`
- 状态：文档已完成

#### 目标

在已经完成真实 Netty 心跳生命周期测试的基础上，独立评估 mini-RPC
与通用生产级 RPC 框架的差距。文档要明确区分“已经完成核心调用链”
和“已经达到生产级”，避免因功能列表较长而高估工程成熟度。

#### 主要修改

- 根目录新增 `PRODUCTION_READINESS_GAP.md`；
- 文档顶部明确标注评估基于 `DEVELOPMENT_LOG2.md` 3.37 / S5-I-D-B，
  并记录提交和测试基线；
- 从 Spring 使用体验、服务模型、协议契约、发现路由、可靠性、
  可观测性、安全、扩展性、云原生和工程验证等维度逐项对照；
- 补充 mini-RPC、Dubbo 类框架和 gRPC 类框架的直接对照表；
- 对每个主要缺口说明当前状态、生产风险、建议和部分验收条件；
- 给出面向简历项目的推荐路线：版本路由、Filter 调用链、Metrics、
  最小 SPI、CI 与性能基线；
- 单独记录不建议当前立即实现的能力，包括透明业务重试、完整
  流式 RPC、跨语言 IDL、控制台和自研容错算法库；
- 在 README 增加评估文档入口，并根据当前的设计判断更新下一步路线。

#### 行为变化

本步只新增评估文档并调整 README 中的文档入口和规划，不修改 Java 代码、
配置、协议、启动流程或运行行为。

规划上的变化是：不再把透明失败重试作为紧接心跳之后的默认实现目标。
当前保留“失效 Channel 由下一次业务调用按需重建”，业务调用重试由上层
根据幂等性显式决定。

#### 设计原因

- Dubbo 与 gRPC 的成熟度来自协议、治理、工具链、运维和长期生态，
  不能只通过复制几个功能类判断距离；
- 对简历项目而言，服务版本、统一 Filter、Metrics、最小 SPI 和自动化
  验证比完整跨语言或控制面更有投入产出比；
- 生产级结论必须有指标、性能、弱网、长稳、安全和兼容验证支撑，
  不能只根据正常路径测试推断；
- 文档中同时列出“不建议实现”的范围，防止项目为追求功能数量而
  过度设计。

#### 验证方式

- 检查评估文档顶部已明确标注 3.37 / S5-I-D-B 基线；
- 检查 README 中的相对链接可以指向根目录新文档；
- 检查文档同时包含当前能力、差距矩阵、详细风险、演进顺序、
  生产检查表和简历表述边界；
- 对本次 Markdown 改动执行 `git diff --check`；
- 本步不修改运行代码，因此不重复执行 Java 测试。

#### 当前限制与下一步

- 成熟度百分比只是用于表达相对位置的粗略判断，不是性能或功能数量的
  精确数学计算；
- 本文评估的是 3.37 阶段，后续能力变化时应增加新基线，不能静默改变原有
  阶段结论；
- 如继续开发，下一个推荐小步骤是定义统一 `ServiceKey`，让已有
  `RpcRequest.version` 开始参与 Provider 本地服务查找，暂不同时修改 ZooKeeper 路径。

### 3.39 项目命名空间迁移到 kaano

- 日期：2026-10-25
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

移除早期教学项目留下的 `com.itheima` 命名空间，改用项目维护者
`kaano` 的 GitHub 命名空间，让 Java 包名、Maven 坐标、Spring Boot 自动配置
入口和文档示例保持一致。

#### 主要修改

- Maven `groupId` 从 `com.itheima` 改为 `io.github.kaano`；
- Java 基础包从 `com.itheima` 改为 `io.github.kaano.minirpc`；
- 迁移 core、Starter、example-api、example-provider、example-consumer 的所有
  main/test Java 源码目录；
- 机械替换所有 Java `package`、`import`、注解类名和测试中的接口全限定名；
- Spring Boot `AutoConfiguration.imports` 改为新自动配置类名；
- Consumer 心跳 DEBUG 日志包名改为
  `io.github.kaano.minirpc.transport.client`；
- README、开发记录和原始规划中的代码示例同步更新；
- README 增加 Maven 坐标、Java 基础包和升级兼容性说明。

#### 行为变化

RPC 网络协议、线程模型、心跳、超时和服务治理行为本身不变，但这次是
一次源码和服务身份不兼容迁移：

- 业务代码必须把旧 Java import 改为 `io.github.kaano.minirpc...`；
- Maven 依赖坐标必须改为 `io.github.kaano:*`；
- 当前 ZooKeeper 服务名包含 Java 接口全限定名，因此新包名 Provider 不会与
  旧包名 Consumer 直接互通；
- Provider、Consumer 和共享 API 应一起重新构建和启动，旧 ZooKeeper 临时
  节点会随旧 Provider 会话关闭而消失。

#### 设计原因

- `io.github.<username>` 是个人 GitHub 项目常见的反向域名命名方式；
- Maven 坐标与 Java 包名分开：坐标使用 `io.github.kaano`，包名再增加
  `minirpc` 避免与用户名下的其他项目冲突；
- 同步迁移生产代码、测试、示例和自动配置资源，防止“编译成功但
  Starter 无法发现自动配置”这类运行期遗漏；
- 包名迁移是一次明确的断式更名，不保留双包兼容类，避免长期维护
  两份公开 API。

#### 验证方式

- 搜索所有源码、POM、资源和文档：除本节用于解释迁移的旧名称外，运行文件
  不再引用 `com.itheima`；
- 83 个 Java 文件均已使用 `io.github.kaano.minirpc` 包声明，七个 main/test
  Java 源码根均已迁移到 `io/github/kaano/minirpc`；
- 先执行 `mvn clean test`，确认旧 `target` 被删除、core 的 47 个生产类和
  22 个测试类能够从新路径重新编译；首次测试中 Mockito 尝试通过外部进程
  挂载 Agent，该外部 JVM 因本机可用内存不足而中止，该错误与包名迁移无关；
- 使用
  `mvn test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`
  复跑完整 Reactor：core 52 个测试、Starter 12 个测试全部通过，0 失败、
  0 错误，六个 Maven 模块全部构建成功；
- Spring Boot 自动配置测试和真实 Netty 心跳、Testcontainers 端到端测试均在
  新命名空间下通过；
- 执行 `git diff --check` 检查迁移后的空白错误。

#### 当前限制与下一步

- 这次不提供旧 `com.itheima` 公开 API 的兼容层；
- 如果已经有外部项目依赖旧坐标，需要同步修改 POM 和 import；
- 当前机器直接执行 Mockito 测试时，外部 Agent 挂载进程可能受可用内存影响；
  本次通过允许测试 JVM 自挂载完成验证，不把 Mockito 构建配置调整混入包名迁移；
- 下一步仍建议定义统一 `ServiceKey`，先让 version 参与 Provider 本地服务查找。

### 3.40 R1-A：ServiceKey 与 Provider 本地版本/分组路由

- 日期：2026-10-25
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

开始服务版本与分组路由的第一步：先建立统一服务身份，让 Provider 本地服务表
能够区分同一个 Java 接口下的不同 group/version 实现。本步不同时修改 ZooKeeper
路径、注解和 Consumer 地址缓存，避免一次改动跨越全部注册发现链路。

#### 主要修改

- 新增不可变 `ServiceKey`，由 `interfaceName + group + version` 组成；
- 统一默认值：`group=default`、`version=1.0`；
- `RpcRequest` 新增 group 字段，已有 version 字段开始参与 Provider 本地查找；
- `ServiceProvider` 增加基于完整 `ServiceKey` 的注册、查询和移除方法，旧接口名方法
  保留为默认路由的兼容入口；
- `DefaultServiceProvider` 的本地表从 `Map<String, Object>` 改为
  `Map<ServiceKey, Object>`；
- `RpcRequestHandler` 根据请求构造 ServiceKey 后精确查找实现；
- `RpcClientProxy` 当前显式发送默认 group，已有 version 继续随请求发送；
- `RpcProvider` 增加显式 group/version 的注册重载，原注册方法继续使用默认路由；
- 新增 `ServiceVersionGroupRoutingTest`，覆盖同接口三种路由、旧请求默认值、精确
  重复注册拒绝和不存在路由失败；
- JSON、JDK 请求序列化测试增加 group/version 往返断言；
- README 同步说明新的本地服务表结构和当前边界。

#### 行为变化与调用链

过去 Provider 本地服务表只有：

```text
interfaceName → service
```

现在变为：

```text
RpcRequest(interfaceName, group, version)
        ↓
ServiceKey.fromRequest(...)
        ↓
Map<ServiceKey, Object>
        ↓
精确服务实现
```

因此同一个接口的 `default/1.0`、`default/2.0` 和 `gray/2.0` 可以在同一个
Provider JVM 中同时注册，不再互相覆盖。请求没有 group/version 时，为兼容旧
Consumer，会自动使用 `default/1.0`；显式空字符串仍会被拒绝，不会悄悄降级。

#### 设计原因

- 使用不可变值对象集中表达服务身份，避免注册中心、服务表和缓存分别拼接字符串；
- ServiceKey 只保存接口名称而不保存 `Class<?>`，便于后续进入网络请求、ZooKeeper
  路径和缓存键，也减少类加载器耦合；
- 注册时仍额外接收接口类型，用于验证实现对象确实实现了公开契约；
- 旧请求只对缺失字段使用默认值，显式空值视为配置错误，防止错误流量落到默认实现；
- 当前注册中心仍按接口名发布，先证明 Provider 本地查找正确，再单独修改分布式
  注册发现语义，降低排查范围。

#### 验证方式

- 执行 core 定向测试：`ServiceVersionGroupRoutingTest`、
  `RpcRequestHandlerDeadlineTest`、`RpcRequestDeadlineSerializationTest` 和
  `RpcProviderStartupOrderTest`；
- 定向测试通过，证明新路由、旧请求兼容、deadline 和 Provider 启动顺序可以共存；
- 使用
  `mvn test -DargLine="-Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`
  执行根工程完整回归：core 56 个测试、Starter 12 个测试全部通过，0 失败、
  0 错误；
- 真实 ZooKeeper + Netty 端到端测试、两条真实 Netty 心跳测试均通过，六个 Maven
  模块全部构建成功。

#### 当前限制与下一步

- `@RpcService` 和 `@RpcReference` 暂时还没有 group/version 属性，Spring 业务代码
  当前只能使用默认路由；
- ZooKeeper 注册、发现、缓存和 Watch 仍按接口全限定名工作，不能把只部署某个版本
  的 Provider 地址提前过滤掉；
- 因此本阶段只能称为“Provider 本地版本/分组路由”，还不是完整分布式路由；
- 下一步建议实施 R1-B：给两端注解和代理创建链路增加 group/version，让 Spring
  使用方式能够显式选择服务身份，但仍先保持注册中心路径不变并清楚标注限制。

### 3.41 R1-B：Spring 注解传递服务版本与分组

- 日期：2026-10-25
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

把 R1-A 已经建立的本地 `ServiceKey` 接到最终的 Spring Boot 使用入口，使 Provider
可以通过 `@RpcService` 声明 group/version，Consumer 可以通过 `@RpcReference`
选择同一个服务身份。业务代码仍不需要手动创建代理、Netty 或注册中心组件。

#### 主要修改

- `@RpcService` 新增 `group`、`version` 属性，默认值为 `default/1.0`；
- `@RpcReference` 新增相同属性，同时保留已有 `timeoutMillis`；
- `RpcProvider.registerService(Object)` 读取服务注解中的完整路由信息；
- `SpringRpcProviderLifecycle` 把服务注解的 group/version 传入 RpcProvider；
- `RpcClientProxy` 新增 group 字段和完整路由构造方法，旧构造方法继续委托到
  默认 group，保持原有 core 调用兼容；
- `RpcClientFactory` 新增按 group/version 和可选单引用超时创建代理的重载；
- `RpcReferenceInjector` 同时传递接口、group、version、timeout，并在创建任何
  代理前用 ServiceKey 规则校验路由值；
- 扩展 Consumer 单元测试，验证默认路由、自定义 `gray/2.0`、单引用超时和空
  group 拒绝；
- 新增 `SpringRpcProviderLifecycleRoutingTest`，验证 Spring Provider 注册时不会
  丢失服务注解中的 group/version；
- README 增加两端注解示例，并同步当前限制与下一步。

#### 行为变化与调用链

业务代码现在可以声明：

```java
@RpcService(
        serviceInterface = HelloService.class,
        group = "gray",
        version = "2.0"
)

@RpcReference(group = "gray", version = "2.0")
private HelloService helloService;
```

Consumer 调用链：

```text
@RpcReference(group, version, timeout)
        ↓ RpcReferenceInjector
RpcClientFactory.createProxy(...)
        ↓ RpcClientProxy
RpcRequest(group, version)
        ↓ Netty
Provider ServiceKey 精确查找
```

Provider 启动链：

```text
@RpcService(group, version)
        ↓ SpringRpcProviderLifecycle
RpcProvider.registerService(...)
        ↓
Map<ServiceKey, Object>
```

省略属性时保持原有 `default/1.0` 行为；两端显式值不一致时，Provider 本地查找
不到对应实现并返回失败，不会自动回退到其他版本。

#### 设计原因

- group/version 属于单个服务引用，而不是整个 Consumer 的全局配置，因此放在注解
  和轻量代理中，不放进 `MiniRpcProperties`；
- 保留 RpcClientProxy 和 RpcClientFactory 的旧方法，避免教学入口及已有测试被迫
  一次性迁移；
- 注入器先校验所有字段再创建代理，任何错误都不会让一个 Bean 只完成一半注入；
- Provider 与 Consumer 使用同一组 ServiceKey 默认值，避免两端各自维护字符串；
- 本步继续不修改 ZooKeeper，把“注解参数传递错误”和“分布式路径设计错误”分开
  验证。

#### 验证方式

- 执行 `RpcReferenceInjectorTimeoutTest`、`RpcClientProxyTimeoutTest`、
  `ServiceVersionGroupRoutingTest` 和 `SpringRpcProviderLifecycleRoutingTest`；
- 定向测试通过，证明 Consumer 注解、请求对象、Provider 注解和本地服务表之间的
  路由参数能够完整传递；
- 首次完整测试在 Surefire 测试 JVM 启动阶段因本机可用提交内存不足而退出，尚未
  执行测试用例；生产类和测试类编译均已成功，该问题不是代码断言失败；
- 使用
  `mvn test -DargLine="-Xms64m -Xmx384m -XX:MaxMetaspaceSize=256m -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading"`
  限制测试 JVM 内存后完整回归通过：core 57 个测试、Starter 13 个测试全部通过，
  0 失败、0 错误；
- 真实 ZooKeeper + Netty 端到端测试、两条真实 Netty 心跳测试均通过，六个 Maven
  模块全部构建成功。

#### 当前限制与下一步

- ZooKeeper 仍以接口全限定名作为服务路径，Consumer 发现阶段不能只返回匹配
  group/version 的 Provider；
- 当不同 Provider 只部署部分版本时，当前负载均衡仍可能先选中不含目标 ServiceKey
  的地址，随后由 Provider 返回“服务不存在”；
- 当前没有通配版本、版本范围或自动降级，Consumer 必须选择一个精确身份；
- 下一步建议实施 R1-C：设计可安全用于 ZooKeeper 节点的 ServiceKey 路径格式，
  同步升级注册、发现缓存、Watch 和端到端测试，完成真正的分布式路由。

### 3.42 R1-C：分布式服务版本与分组路由

- 日期：2026-10-25
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

把完整 `ServiceKey` 接入 ZooKeeper 注册发现、Consumer 地址缓存、Watch、负载均衡
和 Netty 选址，使不同 Provider 分别部署同一接口的不同 group/version 时，Consumer
在建立连接前就只获得目标版本地址，而不是连接后再由 Provider 拒绝。

#### 主要修改

- `ServiceRegistry`、`ServiceDiscovery`、`ServiceAddressResolver` 和 `LoadBalance`
  的主接口参数从字符串升级为 `ServiceKey`；
- 为早期只传接口名的教学代码保留字符串默认方法，自动映射到 `default/1.0`；
- 新增 `ZooKeeperServicePath`，集中生成和解析注册路径；
- 新路径使用
  `/mini-rpc/services/{interface}/{group}/{version}/providers/{address}`；
- 每个动态路径片段使用 UTF-8 百分号编码，避免斜杠、空格、中文、IPv6 冒号等
  字符改变 ZooKeeper 层级；
- `RpcProvider` 从服务名集合升级为 ServiceKey 集合，为每个版本分别发布、记录和
  主动注销地址；
- `ZooKeeperServiceDiscovery` 缓存键改为 ServiceKey，Watch 只失效发生变化的版本；
- `RoundRobinLoadBalance` 按 ServiceKey 维护独立计数器，版本之间不共享轮询进度；
- `NettyRpcClient` 从请求恢复完整 ServiceKey 后再进行地址解析；
- 新增路径编码、版本缓存隔离和轮询状态隔离测试；
- 扩展真实 ZooKeeper + Netty 端到端测试：同时启动 V1、V2 Provider，验证两个
  Consumer 代理分别发现并调用对应版本；
- README 同步更新 ZooKeeper 树、调用链、完成状态和当前限制。

#### 行为变化与调用链

注册路径由：

```text
/mini-rpc/services/{interface}/{address}
```

变为：

```text
/mini-rpc/services/{interface}/{group}/{version}/providers/{encoded-address}
```

完整调用链现在是：

```text
@RpcReference(group, version)
        ↓
RpcRequest(interfaceName, group, version)
        ↓
NettyRpcClient 构造 ServiceKey
        ↓
ZooKeeperServiceDiscovery 查询该 ServiceKey 的地址目录
        ↓
LoadBalance 使用该 ServiceKey 的独立状态选择地址
        ↓
目标 Provider 用相同 ServiceKey 查找本地实现
```

V1 Consumer 不会看到 V2 Provider 地址，V2 Consumer 也不会看到 V1 地址。某个
版本上下线时，只失效自己的地址缓存，不影响同一接口的其他版本。

#### 设计原因

- 服务身份必须贯穿注册、发现和执行阶段；只在请求到达 Provider 后检查版本会造成
  错误连接、无效请求和发布期间的随机失败；
- 路径片段统一编码比禁止所有特殊字符更稳健，也为中文分组和 IPv6 地址留出空间；
- 增加固定 `providers` 层，使服务身份目录和临时实例目录职责清晰，后续可在版本
  节点下增加元数据而不与地址节点混淆；
- Watch 和 TTL 缓存都以 ServiceKey 为粒度，避免 V1 上下线导致 V2 缓存无效；
- 轮询计数按 ServiceKey 隔离，避免不同版本的调用节奏互相影响；
- 保留字符串默认入口只用于源码兼容，不让框架内部继续退化为接口名路由。

#### 验证方式

- 执行 `ZooKeeperServicePathTest`，验证 group 中的斜杠、version 中的空格和 IPv6
  地址可以安全编码并恢复；
- 执行 `ZooKeeperServiceDiscoveryTest`，验证缓存复用、Watch 失效和不同版本缓存
  彼此独立；
- 执行 `RoundRobinLoadBalanceServiceKeyTest`，验证 V1、V2 分别从自己的轮询下标
  开始；
- 执行真实 `RpcEndToEndIntegrationTest`：V1、V2 Provider 使用不同端口和路径，
  V1/V2 代理分别返回 `v1:mini-RPC` 与 `v2:mini-RPC`；
- core 定向测试通过；
- 首次完整测试启动时，Maven JVM 因本机 Windows 页面文件不足而无法保留默认
  G1 内存区域，尚未进入项目编译或测试；
- 使用本次命令级 `MAVEN_OPTS=-Xms64m -Xmx384m -XX:MaxMetaspaceSize=256m`，并为
  Surefire 设置相同内存上限及 JDK 21 动态 Agent 参数后完整回归通过；这些参数
  没有写入项目配置；
- core 62 个测试、Starter 13 个测试全部通过，共 75 个测试，0 失败、0 错误；
- 两条真实 Netty 心跳测试和包含两个版本 Provider 的真实 ZooKeeper + Netty
  端到端测试均通过，六个 Maven 模块全部构建成功。

#### 当前限制与下一步

- 新路径与早期只按接口名的 ZooKeeper 地址路径不兼容，Provider、Consumer 和共享
  API 应一起升级；旧持久空目录不会自动删除，但不包含临时地址，不影响发现；
- 当前只支持精确 group/version，不支持通配、版本范围、自动降级和百分比流量；
- `@RpcReference` 是静态注解，切换版本需要重新发布 Consumer，尚无动态路由规则；
- 路径下暂时只保存地址，没有权重、区域、健康度等 Provider 元数据；
- 下一步建议进入 Filter 调用链：先定义 Consumer/Provider 两侧统一拦截边界，为
  Metrics、Tracing、鉴权和限流提供扩展位置。

### 3.43 R2-A：Provider Filter 最小责任链骨架

- 日期：2026-10-26
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

先在 core 中建立 Provider Filter 的最小抽象和可验证的顺序执行语义，为后续把
deadline、日志、指标、鉴权和限流从 `RpcRequestHandler` 等核心类中拆出提供统一
扩展位置。本步只实现 Provider 骨架，不接入真实 Netty 请求路径，不同时实现
Consumer Filter、Spring Bean 自动收集或 SPI。

#### 主要修改

- 新增 `ProviderFilter`，定义单个 Provider 横切处理节点；
- 新增 `ProviderFilterChain`，用 `proceed(request)` 表示继续执行剩余调用链；
- 新增 `DefaultProviderFilterChain`，按列表顺序执行 Filter，全部完成后进入
  `RpcRequestHandler.handle(request)`；
- 构造时对 Filter 列表进行防御性复制和空值校验，避免外部运行时修改顺序；
- 调用链不保存共享可变下标，每个下一节点只保存自己的不可变位置，使根链可以由
  多个 Provider 业务线程并发复用；
- 明确 Filter 实例会被并发共享，实现类仍须自行保证成员状态线程安全；
- 对 Filter 返回 `null`、空请求、空终点和包含空元素的列表提供明确失败信息；
- 新增 `DefaultProviderFilterChainTest`，覆盖嵌套前置/后置顺序以及短路行为。

#### 行为变化与调用链

core 现在可以独立组装并执行下面的 Provider 调用链：

```text
Filter A before
    ↓
Filter B before
    ↓
RpcRequestHandler → 真实服务方法
    ↓
Filter B after
    ↓
Filter A after
```

Filter 不调用 `chain.proceed(request)` 而直接返回响应时，后续 Filter 和真实服务方法
都不会执行。当前生产运行行为尚未改变：`NettyRpcServerHandler` 仍然通过业务线程池
直接调用 `RpcRequestHandler`，因此本步新增的链只有显式组装时才会生效。

#### 设计原因

- 横切逻辑需要围绕服务调用执行，但不应继续堆积在反射处理器或 Netty Handler 中；
- Provider Filter 与 Netty Pipeline 职责不同：前者处理已经解码的 RPC 调用语义，
  后者处理字节、Channel、空闲事件和网络收发；
- 不可变链节点比共享递增下标更适合并发 Provider，同一条根链不会因为并发请求而
  串改执行位置；
- 链结构线程安全不等于任意 Filter 自动线程安全，因此扩展约定明确禁止用普通成员
  变量保存单次请求状态；
- 第一小步使用固定列表而不做 SPI，先把责任链语义、短路行为和线程安全边界验证
  清楚，避免一次引入过多扩展机制；
- 终点继续复用现有 `RpcRequestHandler`，不在骨架阶段改写已经验证的服务查找和反射
  调用逻辑。

#### 验证方式

- 执行 `DefaultProviderFilterChainTest`；
- 验证正常链严格产生
  `first-before → second-before → service-invoke → second-after → first-after`；
- 验证首个 Filter 直接返回失败响应时，后续 Filter 和真实服务方法都不执行；
- 第一次测试因测试夹具中的嵌套服务接口为 `private`，跨包反射返回访问失败；将测试
  接口改为 `public` 后重新执行，2 个测试全部通过。该问题未涉及生产代码行为。
- 使用命令级 JVM 内存上限执行 `mvn test`，六个 Maven 模块全部构建成功；
- core 运行 64 个测试，0 失败、0 错误，其中需要 Docker 的 2 个真实 ZooKeeper
  端到端用例因当前环境没有可用 Docker 而跳过；Starter 13 个测试全部通过；
- 两条不依赖 Docker 的真实 Netty 心跳集成测试均通过。

#### 当前限制和下一步

- 调用链尚未接入 `NettyRpcServerHandler` 的真实业务执行路径；
- deadline 执行前检查仍在 `RpcRequestHandler` 中，尚未成为 Filter；
- 暂无 Consumer Filter，也没有 Spring Bean 自动收集与顺序配置；
- 当前 Filter 若主动多次调用 `proceed`，可能多次执行服务方法；这是责任链扩展者必须
  遵守的约束，后续文档和内置实现都应保持一次调用语义；
- 下一步建议实施 R2-B：把 Provider 根调用链接入业务线程执行路径，并将 deadline
  检查迁移为第一个内置 `ProviderDeadlineFilter`，使用测试保证迁移前后行为一致。

### 3.44 R2-B：Provider Filter 接入真实 Netty 业务路径

- 日期：2026-10-26
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

让 R2-A 建立的责任链真正参与 Netty Provider 的每一次普通 RPC 请求，并把执行前
的 deadline 检查从 `RpcRequestHandler` 迁移为第一个内置 Filter。保持业务线程池隔离、
请求排空计数、响应写回以及执行期间的协作式 deadline 检查行为。

#### 主要修改

- 新增 `ProviderDeadlineFilter`：deadline 大于 0 且当前时间已达到截止时间时，
  直接返回携带原 requestId 的失败响应；旧请求的 deadline 为 0 时继续执行；
- `NettyRpcServerHandler` 构造时组装一次
  `ProviderDeadlineFilter → RpcRequestHandler`，业务线程执行任务时调用根链的
  `proceed(request)`；Netty EventLoop 仍只负责投递任务；
- 移除 `RpcRequestHandler` 内部的执行前 deadline 判断；它保留服务查找、方法反射、
  响应封装，以及执行方法时的 `RpcCallContext` 绑定和清理；
- 更新原 deadline 测试，使过期和未过期请求通过真实 Filter 链验证；
- 为 `NettyRpcServerHandlerDrainTest` 增加过期请求测试，验证业务任务运行前不执行
  Filter，运行后短路并正确归还在途计数；
- 新增真实 TCP 集成测试 `NettyProviderDeadlineFilterIntegrationTest`：使用真正的
  Netty Consumer/Provider，先发送过期请求，再在同一连接上发送正常请求；
- 同步更新 README 和根开发记录索引；`PRODUCTION_READINESS_GAP.md` 保持
  3.37 阶段的历史评估快照，不把当前实现误写进当时的结论。

#### 行为变化与调用链

```text
Netty worker：RpcDecoder → NettyRpcServerHandler → 投递到业务线程池
业务线程：DefaultProviderFilterChain
                  ↓
           ProviderDeadlineFilter
                  ↓ 未过期
           RpcRequestHandler → @RpcService 方法
                  ↓
           RpcResponse → Netty Channel 写回
```

如果请求在业务线程池排队期间到达 deadline，Filter 在任务真正开始时短路，服务
方法不会执行。失败响应仍使用请求的 requestId；写回完成后仍通过原有逻辑减少在途
计数。正常请求仍会进入原处理器，服务方法运行期间仍可主动调用
`RpcCallContext.checkDeadline()`。

#### 设计原因

- 执行前 deadline 是所有 Provider 方法共有的检查，放在 Filter 更便于以后加入
  指标、鉴权等横切节点，同时让 `RpcRequestHandler` 聚焦服务定位和方法调用；
- 在业务线程而不是 EventLoop 中检查，才能覆盖排队造成的过期，也不会把未来可能
  耗时的 Filter 放在 Netty I/O 线程上；
- 根链在 Handler 构造时组装一次，列表和链位置不可变，多个 Channel 的业务任务可
  复用它；Filter 自身若将来保存共享状态，仍须自行保证线程安全；
- 只调整 `executeAndRespond` 内的业务执行入口，保留现有过载拒绝、异常转响应和
  响应写回后的排空计数规则。

#### 验证方式

- 定向运行 `RpcRequestHandlerDeadlineTest`、`NettyRpcServerHandlerDrainTest`、
  `DefaultProviderFilterChainTest` 和 `NettyProviderDeadlineFilterIntegrationTest`，
  全部通过；
- 真实 TCP 集成测试确认过期请求返回 deadline 失败且业务方法执行次数为 0；
  同一连接上的下一次正常请求成功，业务方法执行次数变为 1；
- 使用命令级 JVM 内存上限运行根目录 `mvn test`，六个 Maven 模块构建成功；
- core 66 个测试，0 失败、0 错误，其中 2 个需要 Docker 的 ZooKeeper 端到端
  用例因当前 Docker 不可用而跳过；Starter 13 个测试全部通过；
- 两条原有真实 Netty 心跳集成测试继续通过。

#### 当前限制和下一步

- 目前只有内置 `ProviderDeadlineFilter` 被自动组装；用户 Filter 尚不能通过 Spring
  Bean 加入运行链，也没有排序与启用规则；
- Consumer Filter 尚未实现；旧 Socket 教学入口直接调用 `RpcRequestHandler`，
  不经过 Netty 的 Provider Filter 链；
- 绝对 deadline 仍要求 Consumer 和 Provider 系统时钟基本同步；执行中的业务方法
  只有主动调用 `RpcCallContext.checkDeadline()` 才能协作终止；
- 下一步建议 R2-C：在 `RpcClientProxy` 与 `RpcClient` 之间建立 Consumer Filter
  调用链，保持单引用超时、异常传播和代理返回值行为不变。

### 3.45 R2-C：Consumer Filter 接入动态代理调用路径

- 日期：2026-10-26
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

在 Consumer 动态代理完成 `RpcRequest` 构造后、交给 `RpcClient` 发送前建立一条
可短路、可执行前后置逻辑的责任链。保持原有默认超时、单引用超时、异常向上传递和
代理方法返回值的行为。该阶段只提供 core 手动组装入口，不同时接入 Spring Bean。

#### 主要修改

- 新增 `ConsumerFilter` 和 `ConsumerFilterChain`，用 `proceed(request)` 表示进入
  下一个节点或最终网络发送；
- 新增 `DefaultConsumerFilterChain`：根链持有不可变的 Filter 列表和当前代理引用的
  超时覆盖值，每个剩余节点使用自己的不可变下标；
- 链的终点沿用原来的两个 `RpcClient.sendRequest` 入口：没有单引用覆盖值时调用
  单参数方法，有覆盖值时调用带 `Duration` 的方法；
- `RpcClientProxy` 现在把构造完成的请求交给根链；原有构造方法全部委托到空 Filter
  列表，并新增可传入固定顺序 Filter 列表的五参数 core 构造方法；
- 新增 `ConsumerFilterChainTest`，覆盖两层 Filter 的前后置执行顺序、单引用超时
  传递、Filter 短路以及网络异常仍触发 Filter 清理逻辑；
- README 同步更新模块说明、完整调用链、当前限制和下一步。

#### 行为变化与调用链

```text
业务方法 → RpcClientProxy 构造 RpcRequest
              ↓
          ConsumerFilter[0] → ConsumerFilter[1] → ...
              ↓
          RpcClient.sendRequest(request[, timeout])
              ↓
          响应反向经过 Filter，最后由代理返回 data 或抛出 RpcException
```

Filter 不调用 `chain.proceed(request)` 时，可以直接返回失败响应并阻止网络发送。
Filter 调用下一节点后可在返回路径记录耗时；网络调用抛出运行时异常时，异常会原样
向上传播，Filter 使用 `finally` 仍可完成清理。`toString`、`equals` 和 `hashCode`
等代理对象本地方法仍不会进入 RPC 链。

#### 设计原因

- 日志、指标和追踪需要围绕完整的 Consumer 远程调用执行，放在代理和 RpcClient
  之间可以覆盖服务发现、建连、等待响应等环节；
- 超时覆盖值属于单个服务引用，保存在该代理专属的根链中，避免多个引用共享一个
  RpcClient 时互相覆盖配置；
- 与 Provider 链一致，使用不可变过滤器快照和不可变位置，多个业务线程可复用
  同一代理；Filter 实例自己的可变状态仍由实现者负责线程安全；
- 空链保留所有已有入口语义，先验证责任链的行为，再单独实现 Starter 的 Spring
  Bean 收集和排序，方便逐步审核。

#### 验证方式

- 定向运行 `ConsumerFilterChainTest` 和 `RpcClientProxyTimeoutTest`，均通过；
- 验证 `first-before → second-before → transport → second-after → first-after`
  的执行顺序，以及单引用 750ms 超时仍传到 RpcClient 的带 Duration 重载；
- 验证短路后 RpcClient 调用次数为 0，失败响应仍由代理转换成 RpcException；
- 验证网络异常向上传递时，同一个异常对象被调用方接收，Filter 的 `finally`
  清理逻辑正常执行；
- 使用命令级 JVM 内存上限执行根目录 `mvn test`，六个 Maven 模块全部成功；
- core 69 个测试中 67 个通过、2 个 Docker 用例因当前 Docker 不可用而跳过；
  Starter 13 个测试全部通过，总计 80 个通过、2 个跳过，0 失败、0 错误。

#### 当前限制和下一步

- Starter 尚未自动收集用户声明的 Consumer/Provider Filter Bean；当前 Spring
  业务代码使用空 Consumer 链和仅包含内置 deadline 的 Provider 链；
- core 的五参数构造方法可手动指定 Consumer Filter 列表，`RpcClientFactory`
  尚未提供 Filter 配置入口；直接调用 `RpcClient.sendRequest` 不经过代理链；
- 当前 Filter 不应多次调用 `proceed`，否则可能重复发送非幂等请求；框架没有
  自动重试，也不提供隐式业务幂等保证；
- 下一步建议 R2-D：让 Starter 按确定顺序收集两侧 Spring Bean Filter，并保证
  Provider 内置 deadline Filter 始终位于用户 Filter 前面。

### 3.46 R2-D：Starter 自动收集并排序 Filter Bean

- 日期：2026-10-26
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

让 Spring Boot 业务项目只需声明 `ConsumerFilter` 或 `ProviderFilter` Bean，就能把
自定义横切逻辑接入真实 RPC 调用链；通过 Spring `@Order`/`Ordered` 得到确定顺序，
同时保证 Provider 内置 deadline 检查始终先于用户 Filter。

#### 主要修改

- `MiniRpcAutoConfiguration` 使用 `ObjectProvider.orderedStream()` 分别收集两侧
  Filter Bean，形成一次性有序列表快照；没有相应 Bean 时得到空列表；
- `RpcClientFactory` 新增接收 `List<ConsumerFilter>` 的完整构造方法，并把同一份
  不可变列表交给默认路由代理以及每个带 group/version/timeout 的引用代理；
- `RpcProvider` 和 `NettyRpcServer` 新增接收 `List<ProviderFilter>` 的构造入口，
  最终传给 `NettyRpcServerHandler`；
- `NettyRpcServerHandler` 将内置 `ProviderDeadlineFilter` 放在用户 Filter 列表最前面，
  再构造一次可并发复用的 `DefaultProviderFilterChain`；
- 所有旧构造方法继续委托到空用户 Filter 列表，避免破坏纯 Java 教学入口；
- 新增 Starter 排序测试，故意按“晚、早”的顺序注册 Bean，验证最终按
  `@Order(10)`、`@Order(20)` 排列；
- 扩展 Provider Handler 测试，验证过期请求在第一个内置 Filter 短路，用户 Filter
  与真实请求处理器均不会执行；
- 同步更新 README 的扩展方式、完整调用链、当前限制和下一步。

#### 行为变化与调用链

Consumer：

```text
@RpcReference 动态代理
    → 用户 ConsumerFilter（按 @Order 从小到大）
    → RpcClient.sendRequest(...)
    → 响应按相反嵌套方向经过 Filter 后置逻辑
```

Provider：

```text
NettyRpcServerHandler → Provider 业务线程池
    → ProviderDeadlineFilter（框架固定第一位）
    → 用户 ProviderFilter（按 @Order 从小到大）
    → RpcRequestHandler → @RpcService 方法
```

Provider 请求已经过期时，不进入任何用户 Filter；未过期时才继续执行用户节点。
用户没有声明 Filter Bean 时，Consumer 直接发送，Provider 仍保留内置 deadline
检查，原有项目行为不变。

#### 设计原因

- 自动收集属于 Spring 装配职责，放在 Starter；core 只接收普通 `List`，继续保持
  不依赖 Spring，纯 Java 用户也可以手动组装；
- 使用 Spring 已有的 `@Order`/`Ordered` 规则，避免框架重复设计一套顺序注解；
- 构造时生成不可变快照，而不是每次请求查询容器，避免在高频调用路径引入 Bean
  查找和排序开销；
- deadline 固定在 Provider 首位，保证已经没有执行价值的请求不会触发用户鉴权、
  日志或其他可能产生副作用的 Filter；
- 两侧 Filter Bean 都是单例并被并发调用，因此单次请求状态必须使用局部变量，
  Filter 自身的共享可变状态需要实现者保证线程安全。

#### 验证方式

- 定向执行 `ConsumerFilterChainTest`、`DefaultProviderFilterChainTest`、
  `NettyRpcServerHandlerDrainTest` 和 `MiniRpcAutoConfigurationTest`，全部通过；
- 验证 Spring 注册顺序与最终 Filter 顺序不同时，`@Order` 仍决定调用顺序；
- 验证过期请求的用户 Provider Filter 调用次数和请求处理器调用次数均为 0；
- 执行 `git diff --check`，未发现空白错误；换行符提示是仓库当前 Windows
  工作区的 LF/CRLF 转换提醒，不是代码错误；
- 执行根目录 `mvn clean test`，六个 Maven 模块构建成功；core 69 个测试中
  67 个通过、2 个依赖 Docker 的 ZooKeeper 端到端测试跳过，Starter 14 个测试
  全部通过，总计 81 个通过、2 个跳过，0 失败、0 错误。

#### 当前限制和下一步

- Filter 当前是全局扩展：某一侧声明的 Bean 会作用于该进程的全部 RPC 调用，尚无
  按服务、方法、group/version 或配置条件匹配的激活规则；
- 框架只定义扩展链，尚未提供开箱即用的日志、指标、链路追踪或鉴权 Filter；
- Filter 必须只调用一次 `chain.proceed(request)`；多次调用可能重复发送请求或执行
  服务方法，框架暂不为自定义错误用法增加额外状态机；
- 旧 Socket 教学入口仍直接调用 `RpcRequestHandler`，不经过 Netty Provider 链；
- 下一步建议进入 R3-A：基于现有 Filter 链增加最小 Micrometer 指标，先统计
  Consumer/Provider 请求总数、成功/失败和调用耗时，不同时加入追踪、熔断等功能。

### 3.47 R3-A：Consumer/Provider 最小 Micrometer 指标

- 日期：2026-10-26
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

复用 R2 已建立的两侧 Filter 链，为每次真实 RPC 调用记录最基础、可自动装配的
Micrometer 指标：Consumer/Provider 请求总数、成功数、失败数和耗时。本步只使用
JVM 内存注册表验证采集语义，不同时接入 Actuator、Prometheus、Grafana、追踪、
熔断、重试或线程池 Gauge。

#### 主要修改

- `mini-rpc-core` 增加 `micrometer-core` 依赖，版本由父工程已有的 Spring Boot BOM
  统一管理；core 不依赖 Actuator 或具体监控后端；
- 新增 `ConsumerMetricsFilter`，记录 `mini.rpc.consumer.requests`、成功/失败计数和
  `mini.rpc.consumer.duration`；网络异常、超时和后续 Filter 抛出的运行时异常同样
  计为失败，并保持原异常对象继续传播；
- 新增 `ProviderMetricsFilter`，记录 `mini.rpc.provider.requests`、成功/失败计数和
  `mini.rpc.provider.duration`；失败响应和业务链直接抛出的异常均计为失败；
- 两个 Filter 都使用 Micrometer 线程安全的 `Counter`/`Timer`，单次请求的
  `Timer.Sample` 只保存在方法局部变量中；无论成功、失败响应还是异常都会在
  `finally` 中停止计时；
- Starter 在业务未提供 `MeterRegistry` 时自动创建 `SimpleMeterRegistry`；业务声明
  自己的注册表 Bean 时，默认实现通过 `@ConditionalOnMissingBean` 自动让位；
- Starter 自动注册两侧指标 Filter，并使用最高 Spring 顺序包住全部用户 Filter；
  core 原有组装规则继续保证 `ProviderDeadlineFilter` 固定在 Provider 指标之前；
- 新增两侧指标单元测试和 Starter 自动配置测试，使用 `MockClock` 精确验证计时，
  同时验证默认注册表、自定义注册表让位以及内置 Filter 的顺序；
- README 同步更新完成度、包结构、指标名称、调用链、当前限制和下一步。

#### 行为变化与调用链

Consumer：

```text
@RpcReference 动态代理
    → ConsumerMetricsFilter（请求总数 + 开始计时）
    → 用户 ConsumerFilter
    → 服务发现、建连、发送并等待 RpcResponse
    → 按响应码记录成功/失败，或把抛出的异常记为失败
    → 停止计时
```

因此 Consumer 耗时覆盖一次调用的完整远程路径，而不只是 Netty 写操作。

Provider：

```text
Provider 业务线程
    → ProviderDeadlineFilter
    → ProviderMetricsFilter（请求总数 + 开始计时）
    → 用户 ProviderFilter
    → RpcRequestHandler → @RpcService 方法
    → 记录响应结果并停止计时
```

已过 deadline 的请求在第一个 Filter 就被短路，不会计入“实际进入 Provider 业务链”
的指标。正常进入业务链后，用户 Filter 和服务方法的执行时间都会计入 Provider 耗时。

#### 设计原因

- 指标是围绕调用的横切逻辑，复用已验证的 Filter 扩展点可以避免把计数代码散落到
  动态代理、Netty Handler 和反射处理器中；
- Consumer 指标放在用户链最外层，才能得到调用方真实感受到的完整耗时；Provider
  指标放在 deadline 之后，避免把没有执行业务价值的过期请求混入业务处理数据；
- 成功/失败以最终 `RpcResponse.code` 为准，链直接抛出异常时再走异常分支，保持
  指标分类与调用方看到的结果一致；
- 默认使用 `SimpleMeterRegistry`，让当前阶段无需外部服务即可学习和测试 Micrometer；
  同时依赖 `MeterRegistry` 抽象，为后续替换成 Actuator/Prometheus 注册表保留入口；
- 本步使用固定聚合指标名，不加入接口、方法、group/version 等标签，先避免高基数
  标签和指标数量膨胀问题，后续有明确查询需求时再设计标签边界。

#### 验证方式

- 定向执行 `ConsumerMetricsFilterTest` 和 `ProviderMetricsFilterTest`，4 个测试全部
  通过；验证成功响应、失败响应、异常传播、请求计数和确定的模拟时钟耗时；
- 定向执行 `MiniRpcAutoConfigurationTest`，验证默认内存注册表、自定义注册表让位、
  两侧指标 Filter 自动创建和排序，全部通过；
- 执行根目录 `mvn clean test`，六个 Maven 模块全部构建成功；core 73 个测试中
  71 个通过、2 个依赖 Docker 的 ZooKeeper 端到端测试跳过，Starter 16 个测试
  全部通过，总计 87 个通过、2 个跳过，0 失败、0 错误；
- Mockito 仍会提示动态加载 Java Agent 的未来兼容性警告，不影响当前测试结果。

#### 当前限制和下一步

- 默认 `SimpleMeterRegistry` 的数据只存在于当前 JVM，应用重启后清空，也没有 HTTP
  查询端点；当前没有接入 Actuator、Prometheus Registry、Prometheus 或 Grafana；
- 指标当前按 Consumer/Provider 两侧聚合，没有接口、方法、group/version 标签；
- 尚未提供 Provider 在途请求、业务线程池活跃线程、队列长度和拒绝次数 Gauge；
- 旧 Socket 教学入口不经过 Netty Provider Filter 链，因此不会记录 Provider 指标；
- 下一步建议单独实施 R3-B：为现有 `InFlightRequestTracker` 和
  `ProviderBusinessExecutor` 增加只读 Gauge，不在同一步部署监控基础设施。
