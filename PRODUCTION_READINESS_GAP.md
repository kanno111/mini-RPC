# mini-RPC 与生产级 RPC 框架的差距评估

## 1. 评估基线

本文不是对项目最终形态的假设，而是对一个明确时点的快照评估。

- 评估日期：2026-10-25；
- 开发记录基线：[`DEVELOPMENT_LOG2.md`](DEVELOPMENT_LOG2.md) 3.37 / S5-I-D-B；
- 阶段名称：S5-I-D-B，心跳超时断连与按需重连集成测试；
- 对应 Git 提交：`8cb35ed test: verify Netty heartbeat lifecycle`；
- 直接前置阶段：`1221f7a` Provider 静默连接清理，`5411ad8` 可配置心跳；
- 验证基线：core 52 个测试、Starter 12 个测试全部通过，六个
  Maven 模块构建成功，包含 Testcontainers ZooKeeper + Netty 端到端测试和
  真实 Netty 心跳生命周期测试。

后续如果实现了版本路由、指标、SPI、TLS 等能力，应追加新的评估章节，
不应直接把本文中基于 3.37 阶段的结论当作当前事实。

---

## 2. 评估对象与判定标准

“距离生产级 RPC 框架还有多远”不能只用功能数量回答。本文主要参照两类
主流开源框架：

- Apache Dubbo：代表“Java 开发体验 + RPC 通信 + 服务治理”路线；
- gRPC：代表“IDL + 代码生成 + 标准化协议 + 跨语言”路线。

参考的官方能力说明包括：

- [Dubbo 核心架构与服务治理](https://dubbo.apache.org/en/overview/what/overview/)；
- [Dubbo 服务发现](https://dubbo.apache.org/en/overview/what/core-features/service-discovery/)；
- [Dubbo 负载均衡](https://dubbo.apache.org/en/overview/what/core-features/load-balance/)；
- [Dubbo SPI 扩展机制](https://dubbo.apache.org/en/overview/mannual/java-sdk/reference-manual/architecture/dubbo-spi/)；
- [Dubbo 可观测性](https://dubbo.apache.org/en/overview/what/core-features/observability/)；
- [gRPC 服务定义、调用模型与生命周期](https://grpc.io/docs/what-is-grpc/core-concepts/)；
- [gRPC Health Checking](https://grpc.io/docs/guides/health-checking/)；
- [gRPC OpenTelemetry Metrics](https://grpc.io/docs/guides/opentelemetry-metrics/)；
- [gRPC 认证与 TLS](https://grpc.io/docs/guides/auth/)。

生产级不只表示“正常请求可以成功”，还要同时回答：

1. 网络抖动、节点上下线、慢服务、突发流量时是否保持可用；
2. 升级协议、调整配置、扩展策略时是否可兼容；
3. 出现故障时能否快速观察、定位、止损与恢复；
4. 是否具有加密、身份认证、授权、审计等安全边界；
5. 是否经过并发、长时间、弱网、内存和版本兼容验证；
6. 多人团队是否能通过稳定的扩展点和运维工具持续使用。

---

## 3. 总体结论

### 3.1 当前定位

S5-I-D-B 阶段的 mini-RPC 可以定位为：

> 一个已经跨过 Socket Demo 阶段，具有 Spring Boot 使用体验、完整同步 Unary RPC
> 调用链、基础服务发现以及一批重要运行可靠性机制的 Java 学习型 RPC 框架。

它已经超过常见教学项目的部分是：

- 不只是“动态代理 + Netty”，还包含真实 Spring Bean 发布和注入；
- 不只是“请求能往返”，还处理超时、deadline、过载、优雅关闭和心跳；
- 不只使用模拟测试，还有真实 Netty TCP 和 Testcontainers ZooKeeper 测试；
- 有意识地区分连接重建与业务重试，没有用盲目重试换取表面可用性。

### 3.2 粗略成熟度

以不同目标衡量，结论会完全不同：

| 目标 | 粗略完成度 | 判断 |
| --- | ---: | --- |
| 掌握 RPC 核心原理 | 85% | 主链路与关键稳定性问题已经覆盖 |
| 简历项目完整度 | 75% | 已能讲清调用链、线程模型、超时和生命周期 |
| 受控环境内部使用 | 35% | 还缺可观测性、安全、路由和工程化保障 |
| 通用生产级 RPC 框架 | 15%~20% | 距离治理平台、标准协议和生态还很远 |

百分比只表示工程成熟度的相对位置，不是功能数量或代码量的精确比例。
生产级的后半段往往需要更多压测、故障验证、兼容治理和运维工具，成本并不线性。

---

## 4. 当前已经具备的核心能力

### 4.1 业务使用体验

- Provider 使用 `@RpcService` 暴露 Spring Bean；
- Consumer 使用 `@RpcReference` 注入 JDK 动态代理；
- Spring Boot Starter 自动完成核心组件的创建、启动和销毁；
- 业务代码无需手动创建 Netty、ZooKeeper、序列化器或代理工厂；
- `@RpcReference(timeoutMillis = ...)` 可按引用覆盖全局超时。

这部分已经接近 Java RPC 框架的表层开发体验，是当前差距最小的领域。

### 4.2 数据面主链路

- 自定义 20 字节协议头；
- 请求、响应和心跳消息类型；
- JSON 和 JDK 序列化；
- Netty 长连接、编解码、Channel 复用；
- `requestId` 与 `CompletableFuture` 匹配并发请求；
- Provider 服务表查找和反射调用；
- Netty I/O 线程与 Provider 业务线程隔离。

### 4.3 服务发现和基础负载均衡

- ZooKeeper 临时节点注册；
- Consumer 按服务名发现地址；
- 3 秒本地地址缓存；
- ZooKeeper Watch 主动让旧缓存失效；
- 同一服务的并发刷新合并；
- 轮询与随机负载均衡。

### 4.4 已有可靠性机制

- Consumer 请求等待超时；
- deadline 传递与 Provider 执行前拒绝；
- `RpcCallContext` 执行中协作式 deadline 检查；
- Consumer 写空闲 PING、Provider PONG、Consumer 读空闲断连；
- Provider 清理静默客户端 Channel；
- 旧 Channel 失效后由下一次业务调用按需建立新连接；
- Provider 有界业务队列、过载拒绝与日志诊断；
- Provider 先停止对外发现，再等待在途请求排空；
- Netty 端口绑定成功后才向 ZooKeeper 发布地址。

### 4.5 测试与文档基础

- 核心组件单元测试；
- Netty `EmbeddedChannel` Handler 测试；
- 真实 TCP PING/PONG 保活测试；
- 心跳超时关闭和按需重连测试；
- Testcontainers 真实 ZooKeeper + Netty 端到端测试；
- README、分阶段开发记录和核心中文注释。

---

## 5. 与生产级框架的总体差距矩阵

| 维度 | mini-RPC 3.37 阶段 | 主流生产框架 | 差距 | 优先级 |
| --- | --- | --- | --- | --- |
| Spring 使用体验 | 注解发布、引用和 Starter 自动配置 | 丰富的服务/方法级配置与动态治理 | 中 | P1 |
| 服务模型 | 只按接口全限定名区分 | interface + group + version + metadata | 大 | P0 |
| 调用模型 | 同步 Unary | 同步、异步、Reactive、流式 | 大 | P2 |
| 协议与契约 | 自定义 TCP + Java 接口 | IDL、Schema 兼容、HTTP/2/多协议 | 很大 | P2/P3 |
| 跨语言 | 无 | 多语言 SDK 与代码生成 | 很大 | P3 |
| 服务发现 | 单 ZooKeeper、缓存和一次性 Watch | 多注册中心、元数据、多集群与故障切换 | 大 | P1 |
| 负载均衡 | 等权轮询、随机 | 权重、最少活跃、最短响应、一致性哈希、自适应 | 大 | P1 |
| 路由与流量治理 | 无 | 标签、版本、灰度、地域、动态规则 | 很大 | P1 |
| 超时与 deadline | 全局/单引用超时、deadline 传递与协作检查 | 统一上下文传播、取消和异步传播 | 中 | P1 |
| 连接生命周期 | 心跳、超时断连、按需重连、优雅关闭 | 健康状态、退避、异常实例剔除、多连接策略 | 中到大 | P1 |
| 容错治理 | 超时、过载拒绝 | 限流、熔断、隔离、降级、异常实例摘除 | 大 | P1/P2 |
| 可观测性 | 日志 | Metrics、Tracing、Access Log、告警与仪表盘 | 很大 | P0 |
| 安全 | 明文 TCP，无认证授权 | TLS/mTLS、身份、权限、审计 | 很大 | P0（真实生产） |
| 扩展性 | 有 Java 接口，但实现写死在框架装配中 | SPI、Filter、Router、Protocol 等插件机制 | 大 | P1 |
| 运维与控制面 | 无 | 动态配置、Admin、服务测试、Kubernetes/Mesh | 很大 | P2/P3 |
| 工程验证 | 单元 + 真实 Netty + Testcontainers | CI 矩阵、压测、长稳、弱网、兼容、安全扫描 | 大 | P0/P1 |

P0 表示如果要真实进入生产必须先解决；P1 表示生产化核心能力；P2 表示
高阶治理或扩展；P3 表示只有在跨语言、大规模或平台化目标下才必要。

---

## 6. 关键差距详细分析

### 6.1 服务唯一标识不完整

#### 当前状态

本地服务表和 ZooKeeper 路径主要使用接口全限定名。`RpcRequest.version`
已经存在，但没有参与 Provider 注册、Consumer 发现和服务查找。

#### 生产风险

- 新旧版本 Provider 不能并行发布；
- Consumer 无法明确选择合同版本；
- 同名接口在不同业务分组中可能发生冲突；
- 无法使用版本标识完成渐进升级或回滚。

#### 建议的最小改造

引入不可变 `ServiceKey`：

```text
ServiceKey = interfaceName + group + version
```

让 `@RpcService`、`@RpcReference`、ZooKeeper 路径、服务表和 `RpcRequest`
共享同一套标识规则。

#### 验收标准

- 1.0 和 2.0 Provider 可以同时注册；
- 两个 Consumer 可以同时精确调用不同版本；
- 请求版本不存在时快速失败，不能退化为随机调用其他版本；
- Watch 只失效对应 `ServiceKey` 的缓存。

### 6.2 服务契约只存在于 Java 接口

#### 当前状态

Provider 和 Consumer 必须共享 Java API 模块，参数类型和方法签名依赖
Java 反射。

#### 与 gRPC/Thrift 的差距

gRPC 和 Thrift 通常使用 IDL 描述服务和消息，然后为不同语言生成代码。
它们还需要明确的字段编号、可选性和 Schema 演进规则。mini-RPC 当前没有：

- IDL 语法与编译器；
- 代码生成；
- 字段级兼容规则；
- 跨语言类型映射；
- 客户端与服务端的协议兼容矩阵。

#### 是否应该立即实现

不建议。IDL 和跨语言会把项目从“Java RPC 框架”改造成“协议与工具链
项目”，超出当前简历项目的合理范围。应在文档中明确这是范围边界，
不要假装已经支持跨语言。

### 6.3 只有同步 Unary 调用

#### 当前状态

Netty 内部是异步的，但 `RpcClientProxy` 最终阻塞等待 `CompletableFuture`，
业务 API 仍是一请求一响应的同步调用。

#### 生产级能力

- 同步 Unary；
- `CompletionStage`/Future 异步 Unary；
- Reactive API；
- 客户端流、服务端流和双向流；
- 流级取消、流控与背压。

#### 建议

如果只服务 Java 业务接口，可以先支持返回 `CompletableFuture<T>` 的方法，
但不要立即设计流式 RPC。流式调用会同时改变协议、请求生命周期、
流量控制和 API 模型，不适合作为小步骤。

### 6.4 服务发现仍是单注册中心基础实现

#### 当前状态

Watch 只负责删除旧缓存，下一次调用才重新查询；只支持一个 ZooKeeper
地址和一套节点模型。

#### 生产差距

- 注册中心适配层与 SPI；
- 多注册中心、双注册和迁移；
- 网络断开期间的本地快照与恢复；
- 地址与服务元数据分离；
- 命名空间、环境、机房和权限隔离；
- 大规模地址变更时的异步推送与批处理。

#### 建议

先不增加第二种注册中心。应先把“服务标识”和“地址快照”建模清楚，
再提取稳定的 Registry SPI；否则只会把 ZooKeeper 特定语义复制到其他实现。

### 6.5 负载均衡缺少节点能力感知

#### 当前状态

轮询和随机把每个 Provider 视为完全等价的节点。

#### 生产风险

- 高配机器和低配机器承受相同流量；
- 慢 Provider 仍然持续获得新请求；
- 没有根据在途请求、响应时间或错误率调整流量；
- 有状态或缓存亲和请求无法稳定落到同一节点。

#### 建议顺序

1. 为 `ServiceAddress` 增加权重与可扩展元数据；
2. 实现平滑加权轮询；
3. 在有可靠 Metrics 后再实现最少活跃或最短响应；
4. 最后考虑一致性哈希和自适应策略。

没有指标基础时直接实现“自适应负载均衡”，很容易变成无法验证的过度设计。

### 6.6 路由与灰度治理基本空白

当前 Consumer 获得地址后直接进入负载均衡，缺少“先过滤、再选择”的路由层。
生产中常见的能力包括：

- 版本和分组路由；
- 标签路由；
- 机房、地域和就近访问；
- 按比例灰度；
- 按请求参数路由；
- 动态禁用异常实例。

最小正确起点不是立即建设动态规则中心，而是先让 Consumer 调用链形成：

```text
发现地址快照
        ↓
Router 过滤候选节点
        ↓
LoadBalance 选择一个节点
        ↓
Netty 发送
```

### 6.7 健康检查目前只是“连接存活”

心跳能证明：

- TCP 连接可以往返；
- Netty EventLoop 和心跳 Handler 仍在工作。

心跳不能证明：

- 业务线程池还有容量；
- 数据库、缓存和下游服务正常；
- 某个具体 `@RpcService` 愿意接受请求；
- Provider 没有处于预热、排空或过载状态。

成熟框架会区分连接存活、实例健康和服务就绪状态。gRPC 例如定义了
标准 Health Service，客户端可以根据 `SERVING`/`NOT_SERVING` 状态决定是否发请求。

mini-RPC 后续可以引入简单的 Provider 状态：

```text
STARTING → SERVING → DRAINING → STOPPED
```

但在有指标和路由层之前，不必立即实现完整健康协议。

### 6.8 容错能力仍然不足

#### 已有能力

- 请求超时和 deadline；
- 失效连接清理；
- 有界队列与过载拒绝；
- 优雅关闭与在途请求排空。

#### 缺少能力

- Consumer 熔断；
- Provider 限流；
- 按服务或方法的资源隔离；
- 异常实例剔除；
- 降级和 fallback；
- 排队时间与执行时间的分离指标；
- 动态过载保护。

#### 关于失败重试的明确取舍

当前阶段不应默认在 RPC 框架中实现透明业务重试。框架看不到“查询”、
“下单”、“扣款”之间的幂等性差异，而“Consumer 没收到响应”也不等于
“Provider 没有执行”。

合理的边界是：

- 框架负责连接恢复、失败分类和准确报错；
- 业务根据幂等键、交易语义和补偿方案决定是否重试；
- 未来如果提供框架重试，应默认关闭、由业务显式开启，且必须有尝试次数、
  统一 deadline、退避和重试节流。

这不是“少做一个功能”，而是对分布式不确定性的有意识处理。

### 6.9 可观测性是当前最大的生产缺口之一

#### 当前状态

框架主要依赖 SLF4J 日志。日志可以帮助调试，但无法回答：

- 某服务当前 QPS 是多少；
- P95/P99 响应时间是多少；
- 失败发生在服务发现、连接、排队、执行还是响应返回；
- 当前有多少活跃 Channel 和 Pending Request；
- 哪个 Provider 错误率或响应时间异常；
- 业务线程池是否接近过载。

#### 建议的第一批 Micrometer 指标

| 指标 | 类型 | 关键标签 |
| --- | --- | --- |
| `rpc.client.requests` | Counter | service、method、result |
| `rpc.client.duration` | Timer | service、method、provider |
| `rpc.server.requests` | Counter | service、method、result |
| `rpc.server.duration` | Timer | service、method |
| `rpc.client.active.channels` | Gauge | provider |
| `rpc.client.pending.requests` | Gauge | provider |
| `rpc.server.business.queue.size` | Gauge | server |
| `rpc.server.business.rejected` | Counter | service/reason |
| `rpc.heartbeat.timeouts` | Counter | side/provider |
| `rpc.discovery.refresh` | Counter | service/result |

标签必须控制基数，不能把 requestId、完整参数或异常文本作为标签，
否则会造成指标时序爆炸。

#### 链路追踪差距

还缺少 TraceId/SpanId 的协议传递、Consumer/Provider Span、上下文跨线程传播和
OpenTelemetry 导出。这些能力应建立在 Filter/Interceptor 调用链上，不应散落在
Netty Handler 和业务代码中。

### 6.10 安全边界基本空白

#### 当前状态

- TCP 明文传输；
- Provider 不验证 Consumer 身份；
- Consumer 不验证 Provider 身份；
- 没有服务级授权、请求签名和防重放；
- 没有审计事件；
- JDK 反序列化只适合受信任边界。

#### 生产风险

- 网络中间人可窃听或篡改请求；
- 任意可连接端口的进程都可以尝试调用服务；
- 无法证明请求来自合法 Consumer；
- 无法对敏感服务实施最小权限；
- 如果对不可信输入启用 JDK 反序列化，存在严重攻击面。

#### 生产化最低要求

1. Netty Pipeline 接入 TLS；
2. 至少验证 Provider 证书和主机身份；
3. 内部高安全环境使用 mTLS；
4. 在协议元数据中传递身份或凭证，并通过 Provider Filter 鉴权；
5. 生产默认禁用或严格白名单限制 JDK 反序列化；
6. 对认证失败和越权访问记录审计事件。

对简历项目而言，可以先在文档中清晰声明信任边界；如果要声称“可用于生产”，
TLS 和身份认证则不是可选项。

### 6.11 扩展性还停留在“有接口”阶段

#### 当前状态

`Serializer` 和 `LoadBalance` 已经是接口，Starter 也能在内置实现之间切换，
但外部开发者无法仅通过增加一个 JAR 完成扩展。

#### 缺少的抽象

- 扩展实现的发现与按名称加载；
- 默认扩展和重复名称冲突处理；
- Consumer/Provider Filter 调用链；
- 扩展的顺序和条件激活；
- 扩展实例的作用域与生命周期；
- 扩展装配失败时的明确诊断。

#### 建议

不要一次复制 Dubbo SPI 的全部 IOC、AOP、Wrapper 和条件激活能力。先实现：

```text
@SpiExtension("json")
Serializer
        ↓
ExtensionLoader 按名称加载并缓存无参构造实例
        ↓
Starter 使用配置名称选择
```

第一批只覆盖 Serializer 和 LoadBalance，第二批再增加 Filter。

### 6.12 协议兼容与错误模型不够稳定

当前协议已经有魔数、版本、消息类型、序列化类型和 requestId，但还缺：

- 公开的协议规范和字段兼容规则；
- 不同协议版本的协商或拒绝策略；
- 结构化 RPC 状态码；
- 区分业务异常、过载、超时、无服务、解码错误和认证失败；
- 错误状态与可重试性的稳定映射；
- 消息大小、压缩方式和能力协商。

建议先定义稳定错误枚举，不要使用错误文本做程序判断。

### 6.13 资源隔离与背压只有第一层

全局有界业务线程池能够保护 Netty EventLoop，但仍然有下列问题：

- 一个慢服务可以占满全局线程池，拖累所有其他服务；
- 队列只有容量，没有排队 deadline 与丢弃策略；
- Consumer 没有最大 Pending Request 数量和准入控制；
- 没有按 Provider、服务或方法的隔离舱；
- 没有根据内存、队列和延迟的自适应背压。

建议在有 Metrics 后先加 Consumer Pending Request 上限，再评估是否需要按服务
隔离线程池。不应一开始就为每个方法创建独立线程池。

### 6.14 云原生和运维控制面尚未开始

当前项目没有：

- Kubernetes Service/EndpointSlice 发现；
- Readiness/Liveness 集成；
- 动态配置中心；
- 服务管理控制台；
- 路由规则下发；
- 服务测试、Mock 和接口文档平台；
- Service Mesh/xDS 适配；
- 多租户和命名空间。

这些能力是 Dubbo 与教学型 RPC 项目差距最大的地方，但不是当前简历项目
应该自己重复建设的范围。

### 6.15 工程验证还不足以支撑“生产级”结论

#### 当前优势

项目已经开始验证真实组件协作，而不是只测简单 getter/setter。这是很好的基础。

#### 仍然缺少

- GitHub Actions 或其他 CI；
- JDK 版本和操作系统矩阵；
- JMH 编解码和代理基准；
- 并发压测和容量曲线；
- P50/P95/P99 延迟报告；
- 长时间稳定性测试；
- 弱网、丢包、延迟、网络分区和 ZooKeeper 会话过期测试；
- Netty ByteBuf 泄漏检测；
- 大报文、恶意报文和协议模糊测试；
- 升级、回滚和新旧版本互通测试；
- 依赖漏洞扫描、版本发布和变更日志流程。

生产级不能只由代码结构推断，必须由重复可执行的测试、压测数据和故障演练证明。

---

## 7. 与 Dubbo、gRPC 的直接对照

| 能力 | mini-RPC 3.37 | Dubbo 类框架 | gRPC 类框架 |
| --- | --- | --- | --- |
| 服务定义 | 共享 Java 接口 | Java 接口或 IDL | `.proto` IDL + 代码生成 |
| 业务 API | `@RpcService` / `@RpcReference` | 注解、API、XML、Starter | 生成 Stub/Service Base |
| 调用模型 | 同步 Unary | 同步、异步、Reactive、流式 | Unary、客户端流、服务端流、双向流 |
| 协议 | 自定义 TCP 帧 | Dubbo/Triple/REST/gRPC 等 | HTTP/2 + gRPC 语义 |
| 序列化 | JSON、JDK | 多序列化 + SPI | 默认 Protobuf，可扩展 |
| 服务发现 | ZooKeeper | 多注册中心和元数据模型 | Name Resolver/LB/Service Config 生态 |
| 负载均衡 | 轮询、随机 | 权重、最少活跃、一致性哈希等 | pick-first、round-robin、xDS 等 |
| 服务治理 | 基础上下线 | 路由、灰度、限流、降级、Admin | 依赖 Service Config/xDS/生态组件 |
| deadline | 已传递，执行中协作检查 | 完整调用上下文 | deadline、cancel 与传播 |
| 心跳/健康 | TCP 心跳与静默断连 | 连接、实例和治理状态 | HTTP/2 keepalive + 标准 Health Service |
| 可观测性 | 日志 | Metrics、Tracing、Access Log、Admin | OpenTelemetry 指标和链路生态 |
| 安全 | 无 | TLS/mTLS、认证、授权 | TLS/mTLS、Channel/Call Credentials |
| 扩展 | Java 接口 + 内置分支 | 大量 SPI 扩展点 | Interceptor、Resolver、LB、Credentials |
| 跨语言 | 无 | 多语言 SDK | 核心优势 |
| 规模验证 | 学习项目测试 | 大规模生产实践 | 广泛跨语言基础设施实践 |

最重要的结论是：mini-RPC 与 Dubbo/gRPC 的差距不是“再增加几个 Handler”，
而是从单个框架进化到协议、治理、工具链、运维和生态的系统性差距。

---

## 8. 面向简历项目的推荐演进顺序

下面的路线不追求“功能越多越好”，而追求每一步都有清晰问题、架构价值、
测试证据和面试可解释性。

### R1：服务版本与分组路由

建议分成小步：

1. 引入 `ServiceKey`；
2. `@RpcService` 增加 `version` 和 `group`；
3. Provider 本地服务表改用 `ServiceKey`；
4. ZooKeeper 节点改用 `ServiceKey`；
5. `@RpcReference` 选择版本和分组；
6. 新旧版本共存端到端测试。

简历价值：能讲解服务唯一标识、版本隔离和渐进升级。

### R2：Consumer/Provider Filter 调用链

建立统一扩展位置：

```text
Consumer Proxy
    → ConsumerFilter...
    → Transport
    → ProviderFilter...
    → Service Invocation
```

第一阶段只需要固定顺序的 Spring Bean Filter，不必立即实现完整 SPI。

简历价值：能解释横切能力为什么不应散落在 Netty Handler 中。

### R3：Micrometer 指标

先覆盖调用量、成功/失败、耗时、Channel、Pending Request 和业务线程池。

简历价值：项目从“会跑”进入“可观察、可定位”。

### R4：最小 SPI

先支持 Serializer 和 LoadBalance 按名称加载外部实现，补充：

- 重复名称失败；
- 不存在扩展失败；
- 默认扩展；
- 扩展实例缓存；
- 外部测试 JAR 装配。

简历价值：证明框架有稳定扩展边界，而不是只能修改 `if/else`。

### R5：CI 与性能基线

- GitHub Actions 运行单元测试；
- Docker 可用时运行 Testcontainers；
- JMH 评估 JSON/JDK 编解码；
- 简单并发压测记录吞吐量、P95/P99 和错误率；
- 记录测试硬件、JDK、并发数和报文大小，不单独宣传一个吞吐数字。

简历价值：用数据证明工程质量，避免只描述架构。

### R6：可选的生产安全补强

如果要继续向真实内部生产使用推进，再实现：

- TLS/mTLS；
- 服务身份与权限 Filter；
- 限流与熔断集成；
- Pending Request 准入上限；
- 弱网、长稳和升级兼容测试。

这一阶段已经超出普通简历项目的必要范围。

---

## 9. 不建议当前立即实现的能力

### 9.1 完整自研透明重试

默认重试容易导致非幂等业务重复执行。当前保留按需重建 Channel，
把业务重试交给能够判断幂等性的上层，是更安全的默认。

### 9.2 完整流式 RPC

会同时影响协议、流量控制、取消、API 与线程模型，不是小步骤。

### 9.3 跨语言 IDL 和代码生成

这是一个独立的编译器和工具链项目，不应为了“看起来像 gRPC”而匆忙加入。

### 9.4 自研控制台和 Service Mesh

需要独立控制面、持久化、权限、规则下发和多租户模型，超出当前项目目标。

### 9.5 自研完整熔断限流算法库

更合理的方向是先建立 Filter 扩展点，再集成 Resilience4j/Sentinel 等成熟组件，
而不是在 RPC 主线中重新实现所有容错算法。

---

## 10. 真正进入生产前的最低检查表

以下项目不全部满足时，不应将 mini-RPC 描述为通用生产级框架。

### 协议与兼容

- [ ] 发布协议字段规范；
- [ ] 定义协议版本兼容/拒绝策略；
- [ ] 定义结构化错误码；
- [ ] 验证大报文、恶意报文和半包攻击。

### 服务治理

- [ ] `ServiceKey` 支持 interface/group/version；
- [ ] 节点权重、健康和就绪状态；
- [ ] 至少有基础路由层；
- [ ] 注册中心断开时的可预期行为；
- [ ] 异常 Provider 摘除与恢复策略。

### 稳定性

- [ ] Consumer Pending Request 有上限；
- [ ] 服务级资源隔离或可控的共享策略；
- [ ] 限流、熔断或成熟组件集成；
- [ ] 弱网、网络分区、会话过期和慢 Provider 测试；
- [ ] 长时间稳定性和内存泄漏测试。

### 可观测性

- [ ] 调用量、成功率、延迟和失败分类指标；
- [ ] Channel、Pending Request、线程池和注册中心指标；
- [ ] TraceId/SpanId 跨 RPC 传播；
- [ ] 存在可重复的故障定位流程和告警。

### 安全

- [ ] TLS，真实内部生产优先 mTLS；
- [ ] 服务身份认证和授权；
- [ ] 敏感配置和凭证安全管理；
- [ ] 生产默认禁用或限制 JDK 反序列化；
- [ ] 审计日志和依赖漏洞扫描。

### 工程化

- [ ] CI 自动回归；
- [ ] 发布版本、变更记录和升级说明；
- [ ] 性能基线与回归阈值；
- [ ] 多 JDK/操作系统兼容验证；
- [ ] 故障演练与回滚方案。

---

## 11. 简历和面试中应如何定位

### 推荐描述

> 基于 Spring Boot、Netty 和 ZooKeeper 实现轻量级 Java RPC 框架，支持注解式
> 服务发布与引用、自定义协议、服务发现、负载均衡、超时与 deadline 传递、
> Provider 业务线程隔离、优雅停机、心跳与断线按需重连，并使用真实
> Netty 和 Testcontainers 验证关键生命周期。

### 不应使用的描述

- “生产级 RPC 框架”；
- “性能超过 Dubbo/gRPC”；
- “高可用服务治理平台”；
- “支持跨语言和流式通信”；
- “完全保证请求不重复、不丢失”。

### 面试中最有价值的讲解点

1. Netty EventLoop 为什么不能执行慢业务；
2. requestId 如何在一条 Channel 上匹配并发请求与响应；
3. 为什么端口绑定成功后才能注册 Provider；
4. Watch 为什么只让缓存失效，TTL 为什么仍然需要；
5. deadline 和 Consumer 本地超时的区别；
6. 心跳为什么由写空闲触发，读空闲为什么关闭 Channel；
7. 连接重建与业务重试的区别；
8. 为什么不应默认透明重试非幂等业务；
9. 如何通过真实 Netty 和故障注入测试生命周期；
10. 为什么当前还不能宣称是生产级框架。

---

## 12. 最终结论

在 `DEVELOPMENT_LOG2.md` 3.37 / S5-I-D-B 阶段，mini-RPC 已经完成了 RPC 数据面
最重要的教学链路，并开始解决超时、过载、优雅关闭、心跳和故障恢复等
真实工程问题。它已经是一个完整、可运行、可测试、可解释的简历项目。

但它与 Dubbo/gRPC 之间仍存在系统性差距：

```text
当前优势：RPC 核心调用链 + Spring 体验 + 关键可靠性机制

核心缺口：服务模型 + 路由治理 + 可观测性 + 安全 + 扩展机制

长期差距：IDL/跨语言 + 流式协议 + 控制面 + 云原生生态 + 大规模生产验证
```

对当前目标而言，最优的选择不是把所有主流框架功能都缩小复制一遍，
而是按“版本路由 → Filter 调用链 → Metrics → 最小 SPI → CI/性能基线”的顺序，
把已有框架做得更稳定、更可观察、更容易扩展，并对未实现的生产边界保持诚实。
