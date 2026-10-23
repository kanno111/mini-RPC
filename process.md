# mini-RPC 手写 RPC 框架 — 实施规划

> 本文保存项目最初的分阶段实施路线。当前真实代码结构、类职责、运行方式和
> 完整调用链请参见 [README.md](README.md)。
> 已经实际完成的阶段、每次修改的原因、验证结果和后续限制，请参见
> [DEVELOPMENT_LOG.md](DEVELOPMENT_LOG.md)。

> 目标：从零手写一个**可运行、可理解、标准版**的 RPC 框架（含注册中心）。
> 定位：学习型项目，重点搞懂 RPC 的**核心链路**与**分层思想**，而非对标 Dubbo 的完整生产能力。
>
> 技术基线：Java 21 · Maven 多模块 · 框架与示例应用分离

---

## 一、参考的优秀开源项目及借鉴点

| 项目 | 借鉴点 | 落到本项目的具体做法 |
|------|--------|----------------------|
| **Apache Dubbo** | 十层分层架构（proxy / registry / cluster / protocol / exchange / transport / serialize…）、SPI 扩展、RpcRequest/RpcResponse 抽象 | 采用"接口 + 默认实现"的可插拔设计（`Serializer`、`RpcClient`、`ServiceRegistry`、`LoadBalance` 全部抽象成接口） |
| **gRPC** | 接口优先（Interface First）、请求/响应消息的规范结构、HTTP/2 分帧思想 | 用 Java 接口而非 IDL 表达契约；自定义二进制协议帧（魔数 + 长度字段 + 消息类型） |
| **guide-rpc-framework**（JavaGuide/Snailclimb） | 教育型、循序渐进：Socket → Netty → 注册中心 → 序列化扩展 | **直接采用它的分阶段演进路线**（本项目 M1→M4 与其对齐） |
| **SOFARPC / mini-rpc** | bolt 协议帧设计、客户端连接管理 | 协议头字段设计、Client 的连接复用与请求-响应匹配 |

---

## 二、RPC 核心调用链路（先建立全局认知）

一次 `userService.getUser(1)` 调用，背后发生的事：

```
① 客户端调用接口方法
        │  JDK 动态代理拦截（proxy.RpcClientProxy）
        ▼
② 构造 RpcRequest { 接口名, 方法名, 参数类型[], 参数值[], 请求ID, 版本 }
        │  序列化（serialize.Serializer）
        ▼
③ 编码成协议帧 → 网络发送（transport.client）
        ▼
④ 服务端解码 → 反序列化得到 RpcRequest（transport.server）
        │
⑤ 本地服务注册表查找实现类（provider.ServiceProvider）
        │  反射调用方法
        ▼
⑥ 构造 RpcResponse { 请求ID, 返回值 / 异常 }
        │  序列化 → 编码 → 回传
        ▼
⑦ 客户端反序列化 → 按请求ID匹配挂起的请求 → 代理返回结果
```

**贯穿全程的三个核心设计**：

1. **请求ID（requestId）**：`AtomicLong` 自增，用于在异步/多请求场景下把响应与请求一一对应。
2. **接口抽象**：通信层、序列化层、注册中心层都定义接口，实现可替换。
3. **分层解耦**：上层（proxy/registry）不直接依赖底层（Socket/Netty），依赖倒置。

---

## 三、Maven 多模块结构

```
mini-RPC
├── pom.xml                              # 父工程：聚合模块、统一版本
├── mini-rpc-core/                       # 通用 RPC 框架
│   └── src/main/java/com/itheima/
│       ├── common/                      # 请求、响应、协议常量、统一异常
│       ├── serialize/                   # 序列化接口与实现
│       ├── proxy/                       # 客户端动态代理
│       ├── provider/                    # 服务注册表与反射调用
│       └── transport/                   # Socket/Netty 客户端与服务端
│
├── mini-rpc-example-api/                # 客户端、服务端共享的服务契约
│   └── src/main/java/com/itheima/example/api/
│       └── HelloService.java
│
├── mini-rpc-example-provider/           # 服务提供者应用
│   └── src/main/java/com/itheima/example/provider/
│       ├── HelloServiceImpl.java
│       └── ProviderMain.java
│
└── mini-rpc-example-consumer/           # 服务消费者应用
    └── src/main/java/com/itheima/example/consumer/
        └── ConsumerMain.java
```

> 模块依赖：`provider -> core + api`，`consumer -> core + api`。
> consumer **不依赖 provider**，因此客户端只能看到公共接口，无法访问服务端实现类。

---

## 四、分阶段实施路线

### M0 — 项目骨架（0.5 天）

**目标**：建立包结构、核心数据模型，可编译通过。

**产出**：
- `RpcRequest` / `RpcResponse`（含 `code`、`message`、`data` 字段，`RpcResponse` 携带异常信息）
- `RpcException`、`RpcProtocol` 常量类
- `Serializer` 接口 + `JdkSerializer` 实现
- `pom.xml` 引入基础依赖（Lombok、Jackson，日志用 SLF4J + Logback）

**验证**：`mvn compile` 通过。

---

### M1 — 最小可用 Socket 直连版（1 天）⭐ 核心里程碑

**目标**：跑通 RPC 全链路（BIO + JDK 序列化 + 硬编码地址），理解原理。

**关键点**：
- `SocketRpcServer`：`ServerSocket` 阻塞 accept，每个连接交给一个线程处理，读取→反序列化→反射调用→写回。
- `SocketRpcClient`：每次调用建连或简单连接复用，发送请求、阻塞读取响应。
- `RpcClientProxy`：`Proxy.newProxyInstance` 拦截方法调用，组装 `RpcRequest` 并交给 client。
- `DefaultServiceProvider`：本地 `Map<serviceName, Object>` 注册服务实现。

**产出**：`HelloService` 接口 + 实现 + ProviderMain + ConsumerMain，consumer 通过代理远程调用成功打印结果。

**验证**：本地起 provider，consumer 调用 `hello.sayHello("world")` 返回正确。

---

### M2 — 引入 Netty（1~1.5 天）

**目标**：用 Netty 替换 Socket，掌握 Netty 的线程模型与 Pipeline。

**关键点**：
- `NettyRpcServer`：`ServerBootstrap` + boss/worker `NioEventLoopGroup`，`childHandler` 装配编解码器 + 业务 Handler。
- `NettyRpcClient`：`Bootstrap`，连接复用（`Channel` 缓存），用 `ChannelFuture` 异步发送。
- **请求-响应匹配**：客户端维护 `Map<requestId, CompletableFuture<RpcResponse>>`，服务端返回后由入站 Handler 根据 requestId 完成对应 future。

**产出**：`transport/codec` 编解码器（先用简单的 `LengthFieldBasedFrameDecoder` + JDK 序列化）。

**验证**：M1 的示例代码在 Netty 版上无改动跑通。

---

### M3 — 自定义协议帧 + 多序列化（1 天）

**目标**：设计二进制协议，序列化可插拔。

**协议帧格式**（参考 Dubbo/bolt 精简版）：

```
┌──────────────┬──────┬──────┬──────┬──────────┬──────────┬──────────────┐
│ 魔数(4B)     │ 版本 │ 序列化 │ 消息 │ 状态(1B)  │ 请求ID(8B)│ 数据长度(4B) │
│ 0xCAFEBABE   │ (1B) │ 类型(1B)│ 类型(1B)│         │          │              │
└──────────────┴──────┴──────┴──────┴──────────┴──────────┴──────────────┘
                                                        ┌─────────────────┐
                                                        │ body 数据(NB)    │
                                                        └─────────────────┘
```

**关键点**：
- `RpcEncoder` / `RpcDecoder`（`MessageToByteEncoder` / `ByteToMessageDecoder`）实现自定义帧编解码。
- `Serializer` 增加 `JsonSerializer`，协议头的"序列化类型"字段决定用哪个实现反序列化。
- 消息类型区分：请求 / 响应 / 心跳。

**验证**：切换序列化类型（JDK ↔ JSON），示例仍跑通。

---

### M4 — 注册中心（ZooKeeper）+ 负载均衡（1~1.5 天）⭐ 标准版完成

**目标**：服务地址不再硬编码，实现注册、发现、负载均衡。

**关键点**：
- `pom.xml` 引入 `curator-framework`、`curator-recipes`。
- **服务注册**（服务端）：启动时把 `服务名 -> 地址列表` 写入 ZK 临时节点（如 `/mini-rpc/com.itheima.api.HelloService/127.0.0.1:8080`），断开自动清除。
- **服务发现**（客户端）：拉取服务名下的地址列表，`TreeCache` 监听节点变化动态更新本地缓存。
- **负载均衡**：`RandomLoadBalance` / `RoundRobinLoadBalance`，从地址列表选一个建连。
- 注解 `@RpcService` 扫描并自动注册服务；`@RpcReference` 注入代理。

**产出**：完整标准版 —— 多实例 provider 注册，consumer 自动发现并负载均衡调用。

**验证**：起两个 provider（不同端口）注册同一服务，consumer 循环调用，观察请求被轮询分发；停掉一个 provider，consumer 自动剔除。

---

### M5 — 可选进阶（视精力）

- SPI 扩展机制（`META-INF/services` 自动加载实现）
- 更多序列化：Hessian / Kryo / Protobuf
- 容错：调用超时、失败重试
- 更丰富负载均衡：加权、一致性哈希
- 心跳保活、优雅停机

---

## 五、技术选型汇总

| 维度 | 选型 | 演进路径 |
|------|------|----------|
| 语言 / 构建 | Java 21 · Maven | 已定 |
| 动态代理 | JDK `Proxy` | 固定 |
| 序列化 | JDK → Jackson(JSON) | 接口抽象，可插拔 |
| 通信 | JDK Socket → Netty | M1 → M2 |
| 注册中心 | ZooKeeper + Curator | M4 |
| 负载均衡 | 随机 → 轮询 | M4 |
| 日志 | SLF4J + Logback | M0 |
| 协议 | 自定义二进制帧 | M3 |

---

## 六、里程碑一览

| 里程碑 | 名称 | 交付标准 |
|--------|------|----------|
| M0 | 骨架 | 可编译，核心模型就位 |
| M1 | Socket 直连版 | 全链路跑通（⭐） |
| M2 | Netty 版 | 通信层替换，异步匹配 |
| M3 | 自定义协议 | 协议帧 + 多序列化 |
| M4 | 注册中心 | 标准版完成（⭐） |
| M5 | 进阶扩展 | 可选 |

---

## 七、风险与建议

1. **M1 别贪多**：先把 BIO + JDK 序列化 + 硬编码地址跑通，这是理解 RPC 的分水岭，后续所有优化都是对这三块的替换。
2. **请求ID 匹配是 Netty 化的难点**：异步模型下务必用 `CompletableFuture` 挂起，避免同步阻塞或响应错配。
3. **序列化注意跨版本兼容**：JDK 序列化要求两边类版本一致，JSON 更适合调试观察报文。
4. **ZooKeeper 需本地环境**：提前装好或用 Docker（`zookeeper:3.8`）起一个单节点，避免卡在环境上。
5. **模块边界保持清晰**：业务接口与 DTO 放在 `example-api`；服务实现只放在 provider；consumer 不得依赖 provider；通用框架不得依赖任何示例业务模块。
