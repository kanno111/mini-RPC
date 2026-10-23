# mini-RPC 分阶段开发记录

## 1. 这份文档解决什么问题

`process.md` 记录最初的实施计划，`README.md` 说明当前代码怎么使用；本文件专门
记录项目实际上是怎样一步步演进到今天的。

以后每次修改代码、配置、依赖或项目结构，都必须在同一次修改中更新本文件。
记录时至少回答下面六个问题：

1. 这次想解决什么问题？
2. 修改了哪些模块和关键类？
3. 修改前后的行为有什么差别？
4. 为什么采用当前设计？
5. 使用什么方式验证过？
6. 还有哪些限制，下一步准备做什么？

这样做的目的不是简单罗列文件，而是让初学者能够沿着历史理解：一个最小 RPC
调用链，如何逐步加入网络框架、协议、注册中心、负载均衡、注解和 Spring 集成。

---

## 2. 最终目标

项目早期阶段仍然保留手动创建 `RpcProvider`、`RpcClientFactory` 等入口，因为
手动组装最容易看清各组件的关系。但这些入口不是最终使用形态。

最终目标是产出一个 Spring Boot Starter，让业务项目只需完成三件事：

1. 引入 starter 依赖；
2. 在配置文件中填写 ZooKeeper、端口、超时等少量配置；
3. Provider 使用 `@RpcService`，Consumer 使用 `@RpcReference`。

理想的 Provider：

```java
@SpringBootApplication
public class ProviderApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}

@RpcService
public class HelloServiceImpl implements HelloService {
    // 只写真正的业务逻辑
}
```

理想的 Consumer：

```java
@Component
public class HelloController {

    @RpcReference
    private HelloService helloService;
}
```

业务代码不应手动创建 Netty、ZooKeeper、序列化器、动态代理，也不应自己负责
启动和关闭这些资源。Spring 容器和 starter 的自动配置会完成这些工作。

---

## 3. 已完成阶段

### 3.1 M0：建立 RPC 的公共数据模型

- 日期：2026-10-20
- 对应提交：`b3ee7c8 mini-RPC initialize`
- 状态：已完成

#### 要解决的问题

网络通信开始之前，先约定客户端要发送什么、服务端要返回什么。否则 Socket
即使成功传递字节，双方也不知道这些字节代表接口、方法、参数还是结果。

#### 主要修改

- 新增 `RpcRequest`：保存接口名、方法名、参数类型、参数值、请求 ID 和版本；
- 新增 `RpcResponse`：保存请求 ID、状态、返回值或异常信息；
- 新增 `RpcProtocol`：集中保存魔数、协议版本、消息类型和固定头长度；
- 新增 `RpcException`：统一表达框架内部异常；
- 新增 `Serializer` 抽象和 `JdkSerializer` 实现；
- 编写最初的 `process.md`，确定从 Socket 到注册中心的学习路线。

#### 得到的能力

框架第一次拥有了明确的“请求信封”和“响应信封”。序列化器可以把 Java 对象
变成字节，也可以把字节还原成 Java 对象。

#### 验证与限制

- 通过 Maven 编译验证基础类型可以正常协作；
- 此时还没有网络客户端、网络服务端和动态代理，不能完成远程调用。

---

### 3.2 M1-A：搭建原生 Socket 通信骨架

- 日期：2026-10-20
- 对应提交：`69e1650 feat: add M1 socket transport foundation`
- 状态：已完成，代码作为教学对照保留

#### 要解决的问题

让客户端能把 `RpcRequest` 发到另一个 Java 进程，并让服务端找到本地服务对象、
反射执行方法、返回 `RpcResponse`。

#### 主要修改

- `SocketRpcClient`：连接指定主机和端口，发送请求并读取响应；
- `SocketRpcServer`：监听端口并接收连接；
- `SocketRpcRequestTask`：处理一条连接中的请求；
- `DefaultServiceProvider`：保存“服务接口 -> 服务实现对象”的映射；
- `RpcRequestHandler`：查找服务并通过反射调用目标方法；
- 抽取 `RpcClient`、`RpcServer`、`ServiceProvider` 接口。

#### 得到的能力

客户端与服务端之间已经可以传递真正的 RPC 请求和响应，但客户端业务代码还
需要手动构造请求。

#### 验证与限制

- 验证 Socket 能完成一问一答；
- 原生阻塞 Socket 的连接和线程管理较简单，不适合作为后续高并发实现。

---

### 3.3 M1-B：跑通第一次完整 RPC 调用

- 日期：2026-10-20
- 对应提交：`77d9872 feat: complete M1 socket rpc call chain`
- 状态：已完成，代码作为教学对照保留

#### 要解决的问题

业务方不应该手动创建 `RpcRequest`，而应该像调用本地对象一样调用 Java 接口。

#### 主要修改

- 新增 `HelloService` 公共服务契约；
- 新增 `HelloServiceImpl` Provider 实现；
- 新增 Provider、Consumer 示例启动类；
- 新增 `RpcClientProxy`，使用 JDK 动态代理拦截接口方法并构造请求。

#### 得到的能力

Consumer 可以写出 `helloService.sayHello(...)`。表面是普通 Java 方法调用，
背后实际经过动态代理、Socket、服务表和反射调用。

#### 验证与限制

- 分别启动 Provider 与 Consumer，完成端到端远程调用；
- 地址仍然写死，且一次调用的网络模型较基础。

---

### 3.4 工程化：拆分 Maven 多模块

- 日期：2026-10-20
- 对应提交：`31a903e refactor: split project into Maven modules`
- 状态：已完成

#### 要解决的问题

客户端只应该依赖公共接口和 RPC 框架，不应该看到服务端实现类。单模块工程无法
直观表达这条依赖边界。

#### 主要修改

- `mini-rpc-core`：框架实现；
- `mini-rpc-example-api`：Provider 与 Consumer 共享的接口契约；
- `mini-rpc-example-provider`：服务实现与服务端入口；
- `mini-rpc-example-consumer`：客户端业务与客户端入口；
- 根 `pom.xml` 变成聚合父工程并统一依赖版本。

#### 得到的能力

Consumer 只知道 `HelloService`，不知道也不需要知道 `HelloServiceImpl`。这正是
RPC 的接口优先思想：双方共享契约，不共享实现。

#### 验证与限制

- 使用父工程 Maven 构建验证四个模块的依赖方向；
- 仍需双方使用同一份 Java API Jar，尚未采用 gRPC 那样的 IDL 代码生成。

---

### 3.5 M2：将传输层升级为 Netty

- 日期：2026-10-21
- 对应提交：`c19f097 feat: complete M2 Netty RPC transport`
- 状态：已完成

#### 要解决的问题

把阻塞 Socket 的连接、线程和读写管理交给成熟的异步网络框架，并支持同一个
Channel 上同时存在多个尚未返回的请求。

#### 主要修改

- 新增 `NettyRpcServer`、`NettyRpcServerHandler`；
- 新增 `NettyRpcClient`、`NettyRpcClientHandler`；
- 新增 `RpcEncoder`、`RpcDecoder` 并接入 Netty Pipeline；
- 新增 `PendingRequestManager`，用 `requestId` 把异步响应交还给正确请求；
- 引入 `CompletableFuture` 表示“现在还没有结果，未来会完成”的一次调用。

#### 得到的能力

连接建立、网络事件和收发处理由 Netty EventLoop 驱动；多个请求可以共用连接，
响应回来时再根据请求 ID 找到对应的等待者。

#### 验证与限制

- 完成 Netty 版本端到端调用，并通过断点观察 Pipeline；
- 此阶段编解码仍在继续演进，协议校验和多序列化方式尚未完整。

---

### 3.6 M3：自定义二进制协议与 JSON 序列化

- 日期：2026-10-21
- 对应提交：`1e03fc8 feat: complete M3 custom protocol and JSON serialization`
- 状态：已完成

#### 要解决的问题

TCP 只提供连续字节流，没有“一个 Java 对象到这里结束”的概念。框架必须自己
定义帧头和正文长度，才能处理半包、粘包并拒绝非法消息。

#### 主要修改

- 完成 20 字节固定协议头；
- `RpcEncoder` 按字段顺序写入魔数、版本、序列化类型、消息类型、状态、请求 ID、
  正文长度和正文；
- `RpcDecoder` 按相同顺序读取并校验字段，在完整帧到达前等待更多字节；
- 新增 `JsonSerializer`，默认调用链从 JDK 序列化切换到 JSON；
- 协议头中的序列化类型决定正文使用哪一种反序列化方式。

#### 得到的能力

一次 RPC 消息拥有清晰边界，解码器可以从连续 TCP 字节流中切出完整帧。协议
也为心跳、多种序列化器和状态码预留了扩展位置。

#### 验证与限制

- 使用 Maven 构建及 Provider/Consumer 联调验证 JSON 请求与响应；
- 序列化器选择仍是显式判断，尚未使用 SPI 扩展机制。

---

### 3.7 M4-A：接入 ZooKeeper 注册中心

- 日期：2026-10-22
- 对应提交：`8d3197f feat: add ZooKeeper service registry and discovery`
- 状态：已完成基础版本

#### 要解决的问题

Consumer 不应把 Provider 的 `127.0.0.1:8080` 写死。Provider 启动时应报告自己
提供什么服务，Consumer 调用时应根据服务接口查询可用地址。

#### 主要修改

- 新增 `ServiceRegistry`、`ServiceDiscovery` 抽象；
- 新增 `ServiceAddress` 表示主机和端口；
- 使用 Curator 创建和复用 ZooKeeper 客户端；
- `ZooKeeperServiceRegistry` 把 Provider 地址注册为临时节点；
- `ZooKeeperServiceDiscovery` 读取服务节点下的 Provider 地址；
- 使用 Docker 启动 ZooKeeper 并通过命令行查看节点。

#### 得到的能力

Provider 会在 ZooKeeper 中“报到”。Provider 会话结束后临时节点自动消失，
Consumer 可以根据接口全限定名发现服务地址。

#### 验证与限制

- Docker ZooKeeper 启动成功，并验证节点注册、查询与进程退出后的清理；
- Consumer 每次调用仍直接访问 ZooKeeper，没有本地缓存和节点监听。

---

### 3.8 M4-B：负载均衡和客户端高层工厂

- 日期：2026-10-22
- 对应提交：`991c048 feat: add service discovery load balancing client`
- 状态：已完成基础版本

#### 要解决的问题

同一个服务可能有多个 Provider。Consumer 需要先发现全部地址，再挑选一个地址，
同时不应由业务代码手动组装发现、负载均衡、Netty 和动态代理。

#### 主要修改

- 新增 `LoadBalance` 抽象和 `RoundRobinLoadBalance` 轮询实现；
- 新增 `ServiceAddressResolver` 与默认实现，组合服务发现和负载均衡；
- 改造 `NettyRpcClient`，按目标地址建立并复用 Channel；
- 新增 `RpcClientFactory`，集中创建客户端调用链并统一关闭资源；
- 示例支持同时启动 8080、8081 两个 Provider 观察轮询。

#### 得到的能力

Consumer 不再指定固定 Provider 地址。第一次请求选择第一个地址，下一次选择下
一个地址，到末尾后重新从头开始。

#### 验证与限制

- 双 Provider 连续调用验证轮询结果；
- 轮询计数只在当前 Consumer 进程内维护，没有权重、最少连接等策略。

---

### 3.9 M4-C：服务端注解扫描与高层入口

- 日期：2026-10-22
- 对应提交：`d547c17 feat: add provider auto registration and documentation`
- 状态：已完成基础版本

#### 要解决的问题

Provider 不应该为每个实现类重复编写“创建对象、放入服务表、注册 ZooKeeper、
启动 Netty”的样板代码。

#### 主要修改

- 新增 `@RpcService` 标记远程服务实现类；
- 新增 `RpcServiceScanner` 扫描指定包；
- 新增 `RpcProvider`，统一服务实例注册、ZooKeeper 注册、Netty 启停和资源关闭；
- Provider 示例只需指定扫描包和端口；
- 新增完整 `README.md`，梳理项目结构、类职责、调用链和调试断点。

#### 得到的能力

实现类加上 `@RpcService` 后，扫描器会找到它、创建实例、放进本地服务表并把
服务地址注册到 ZooKeeper，最终由 Netty 对外提供调用。

#### 验证与限制

- Maven 测试通过；使用两个 Provider 和一个 Consumer 完成联调；
- 扫描器通过无参构造方法创建对象，无法使用 Spring 的依赖注入和生命周期。

---

### 3.10 M4-D：`@RpcReference` 基础字段注入（当前工作区）

- 日期：2026-10-22
- 对应提交：`feat: add Spring Boot starter foundation`
- 状态：已实现、已联调并纳入阶段性提交

#### 要解决的问题

Consumer 已经有 `RpcClientFactory.createProxy(...)`，但每增加一个远程接口都要
手写一次创建代理的代码。目标是先做一个不依赖 Spring 的注解注入版本，帮助
理解 Spring 后续会替我们自动完成什么。

#### 主要修改

- 新增字段注解 `@RpcReference`；
- 新增 `RpcReferenceInjector`，查找当前类及父类中的引用字段；
- 校验字段不能是 `static`、`final`，字段类型必须是接口，且不能覆盖已有值；
- 通过 `RpcClientFactory` 创建 JDK 动态代理并反射写入字段；
- Consumer 示例由手动 `createProxy()` 改成 `injectReferences(application)`；
- 同步更新 README 的使用方式、类职责、断点顺序和当前限制。

#### 得到的能力

Consumer 业务类只需在接口字段上写 `@RpcReference`。不过当前业务对象仍由
`new ConsumerMain()` 创建，并且仍需手动调用 `injectReferences()`。

#### 验证与限制

- `mvn test` 已通过；
- Docker ZooKeeper、8081 Provider 和 Consumer 联调通过，连续四次远程调用成功；
- 这只是为了讲清注入原理的过渡实现。接入 Spring 后，应由 Spring 的后置处理器
  自动检查所有 Bean，业务代码不再调用 `injectReferences()`。

---

### 3.11 文档治理：明确 Spring 最终形态和持续记录规则

- 日期：2026-10-22
- 对应提交：`feat: add Spring Boot starter foundation`
- 状态：已完成

#### 要解决的问题

已有 README 能说明当前结构，但缺少按实际提交整理的演进历史，也没有一条能让
后续开发持续执行的规则来保证文档与代码同步。

#### 主要修改

- 新增本文件，回溯记录 M0 至 M4 每一阶段；
- README 明确最终目标是 Spring Boot Starter，而不是继续扩展手写启动入口；
- 新增仓库级 `AGENTS.md`，要求以后每次修改同步更新本文件；
- `process.md` 增加文档导航，区分原始计划、当前说明和实际开发记录。

#### 验证与限制

- 对照 Git 提交历史核对阶段日期、提交号和主要文件；
- 使用 `git diff --check` 检查 Markdown 和代码补丁格式；
- 本阶段只建立约束，Spring 集成代码尚未开始。

---

### 3.12 S1：建立 Spring Boot Starter 和自动配置骨架

- 日期：2026-10-23
- 对应提交：`feat: add Spring Boot starter foundation`
- 状态：已完成并通过审核

#### 要解决的问题

此前 `mini-rpc-core` 是纯 Java 框架，Spring Boot 不知道项目里有哪些 RPC 配置，
也不知道应该从哪个类开始自动装配。本阶段先建立 Spring 能识别的最小入口，
不急着启动网络或注入代理。

#### 主要修改

- 新增 `mini-rpc-spring-boot-starter` 模块，并让它单向依赖 `mini-rpc-core`；
- 父 POM 新增 Spring Boot 3.5.7 依赖清单，统一 Spring 相关依赖版本；
- 在 Spring Boot 依赖清单之前导入 Netty 4.2.19 BOM，防止 Boot 默认管理的
  Netty 4.1 子模块与现有 Netty 4.2 API 混用；
- 同步统一 Jackson、SLF4J 和 Logback 各子模块版本，避免只升级其中一个 Jar
  后产生编译错误或运行期警告；
- 新增 `MiniRpcProperties`，支持读取 `enabled`、`registry-address`、
  `request-timeout` 和 `server-port`；
- 新增 `MiniRpcAutoConfiguration`，在 `mini-rpc.enabled` 未配置或为 `true` 时
  把 `MiniRpcProperties` 注册到 Spring 容器；
- 新增 `AutoConfiguration.imports`，让 Spring Boot 无需业务方手动 `@Import`
  就能发现 mini-RPC 自动配置；
- 新增 `MiniRpcAutoConfigurationTest`，使用小型 Spring 上下文验证自动发现、
  属性绑定和显式关闭功能；
- README 同步增加 Starter 模块、阶段状态和当前能力说明。

#### 得到的能力

未来业务项目引入 Starter 后，Spring Boot 已经能够找到 mini-RPC 的自动配置
入口，并能把 `application.yml` 中的 `mini-rpc.*` 内容转换成 Java 配置对象。

这里要特别区分“配置已读取”和“功能已启动”：当前只是把配置对象放进 Spring
容器，还没有创建 `RpcClientFactory`，也没有连接 ZooKeeper 或启动 Netty。

#### 设计原因

- Spring 依赖放在单独 Starter 模块，避免 `mini-rpc-core` 被迫依赖 Spring；
- core 仍可用于理解手动组装和非 Spring 场景；
- 依赖清单会影响传递依赖版本，因此显式保持整组 Netty 子模块版本一致，而不是
  只给 `netty-all` 写一个版本号；
- 先验证自动发现和配置绑定，下一步再接客户端，出现问题时更容易定位；
- 当前把自动配置与 starter 放在一个模块中，减少学习阶段的模块数量。成熟项目
  可以再拆成 `autoconfigure` 与纯依赖聚合的 `starter` 两个模块。

#### 验证与限制

- 执行根工程 `mvn test`，父工程和五个子模块全部构建成功；
- Starter 的 2 个自动配置测试全部通过：一个验证自动发现和属性绑定，另一个
  验证 `mini-rpc.enabled=false` 可以关闭自动配置；
- 编译期成功生成 `META-INF/spring-configuration-metadata.json`，IDE 可据此
  提示四个 `mini-rpc.*` 配置项；
- 通过依赖树核对 Netty 子模块均为 4.2.19，Jackson、SLF4J、Logback 子模块
  版本也保持一致；
- 当前没有对配置值增加范围校验，例如端口是否为 1～65535；
- 当前自动配置不会产生网络连接，不会改变现有 Provider/Consumer 的运行方式；
- 下一步 S2 将注册由 Spring 管理的 `RpcClientFactory`，再通过 Bean 后置处理器
  自动处理 `@RpcReference`。

---

## 4. 接下来如何接入 Spring

下一阶段不再继续发明一个自制的“客户端应用容器”。Spring 本身已经负责对象
创建、依赖注入、配置读取和生命周期管理，mini-RPC 应该接入这些扩展点。

建议按下面的小步骤实施，每一步完成后都先让你审核：

### S1：建立 Spring Boot Starter 骨架

- 状态：已完成，详细记录见 3.12；
- 已新增 `mini-rpc-spring-boot-starter` 模块；
- 已引入 Spring Boot 自动配置所需依赖；
- 已新增 `MiniRpcProperties`，读取 `mini-rpc.*` 配置；
- 已新增自动配置类，但暂时不扫描服务、不启动网络；
- 已编写最小上下文测试，验证自动配置和属性绑定。

这一步只搭骨架，不一次塞入完整功能。

### S2：让 Consumer 引用由 Spring 自动注入

- 实现 Spring `BeanPostProcessor`；
- Spring 创建每一个业务 Bean 后，处理器查找 `@RpcReference` 字段；
- 复用 `RpcClientFactory` 创建代理并注入；
- Spring 容器关闭时自动关闭 Netty 和 ZooKeeper。

完成后，Consumer 不再创建工厂，也不再调用 `injectReferences()`。

### S3：让 Provider 服务使用真正的 Spring Bean

- 从 Spring 容器获取 `@RpcService` 对象；
- 不再通过反射调用无参构造方法创建实现类；
- 服务实现可以正常使用构造器注入、`@Autowired`、数据库组件等 Spring 能力；
- 容器启动完成后自动注册服务并启动 Netty，关闭时自动注销和释放资源。

### S4：把示例改造成 Spring Boot 应用

- Provider 与 Consumer 示例引入 starter；
- 增加 `application.yml`；
- 删除示例中手工组装框架组件的代码；
- 增加 Spring Boot 集成测试，验证注解、自动配置和完整 RPC 调用。

### S5：补齐适合 starter 的可配置和扩展能力

- 服务发现本地缓存与 ZooKeeper Watch；
- 超时、序列化器、负载均衡策略配置；
- 条件装配和清晰的启动失败提示；
- 重试、心跳、优雅停机与更完整的自动化测试；
- 视学习进度再引入 SPI，不在 Spring 基础链路之前堆叠过多功能。

---

## 5. 后续记录模板

每次修改时复制下面的模板，在“已完成阶段”末尾新增一节。不要只写“优化代码”
之类无法复盘的描述。

```markdown
### 阶段编号：简短名称

- 日期：YYYY-MM-DD
- 对应提交：尚未提交 / 提交号与标题
- 状态：进行中 / 已完成 / 已验证

#### 要解决的问题

说明修改前的痛点和本次边界。

#### 主要修改

- 按模块或职责列出关键修改；
- 说明新增、调整或删除的主要类和配置。

#### 得到的能力

说明修改前后，框架使用方式或运行行为发生了什么变化。

#### 验证与限制

- 写明执行过的命令、测试或人工联调；
- 写明尚未解决的问题和推荐的下一步。
```
