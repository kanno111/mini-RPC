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

### 3.13 S2-A：由 Spring 管理客户端工厂生命周期

- 日期：2026-10-23
- 对应提交：`feat: complete Spring Boot RPC integration`
- 状态：已实现并验证

#### 要解决的问题

手动版本需要业务代码使用 `try (RpcClientFactory factory = ...)` 创建和关闭工厂。
Spring 应用中不应该由每个业务类重复管理这个基础设施对象，而应该让整个应用
共享一个客户端工厂，并在 Spring 容器关闭时统一释放资源。

#### 主要修改

- `MiniRpcAutoConfiguration` 新增 `RpcClientFactory` Bean；
- 工厂使用 `MiniRpcProperties.registryAddress` 创建，不再固定使用默认地址；
- 使用 `@Lazy` 延迟实例化，单纯启动不需要 RPC 客户端的应用时不会连接
  ZooKeeper，也不会创建 Netty 客户端线程；
- 使用 `destroyMethod = "close"`，让 Spring 关闭时自动释放 Netty 和 ZooKeeper；
- 使用 `@ConditionalOnMissingBean`，业务方提供自定义工厂时自动配置主动让位；
- 新增两个测试，分别验证延迟创建和自定义 Bean 覆盖规则；
- README 同步更新 S2 状态、工厂生命周期和已生效配置。

#### 得到的能力

Spring 容器现在已经知道客户端工厂应该怎样创建和销毁。未来 Bean 后置处理器
发现 `@RpcReference` 时，只需向 Spring 取得这一个工厂，不必自己组装底层组件。

#### 设计原因

- 客户端工厂内部持有 Netty 线程和 ZooKeeper 连接，适合作为应用级单例共享；
- 延迟创建避免“应用虽然引入 Starter、但没有调用 RPC，也必须连接 ZooKeeper”；
- 条件装配为高级用户保留替换默认工厂的能力；
- 本步只解决生命周期，下一步再处理字段注入，便于单独理解 Spring Bean。

#### 验证与限制

- 执行 `mvn test -pl mini-rpc-spring-boot-starter -am`，core 与 Starter 构建成功，
  Starter 累计 4 个测试全部通过；
- 测试过程没有启动 ZooKeeper，通过检查 Bean 定义和单例池验证延迟创建；
- 自定义工厂测试注册一个不会实例化的延迟 Bean 定义，验证默认工厂会主动让位；
- 尚未把 `request-timeout` 传入 `NettyRpcClient`；
- 尚未实现 Bean 后置处理器，业务字段不会因为本步修改而自动获得代理；
- 下一步 S2-B 将实现 `@RpcReference` 的 Spring Bean 后置处理器。

---

### 3.14 S2-B：Spring 自动处理 `@RpcReference`

- 日期：2026-10-23
- 对应提交：`feat: complete Spring Boot RPC integration`
- 状态：已实现并完成端到端验证

#### 要解决的问题

S2-A 只让 Spring 管理客户端工厂，业务 Bean 的远程服务字段仍然没有自动赋值。
最终使用者不应该手动取得工厂并调用 `injectReferences()`，Spring 应该在对象
创建过程中自动发现注解。

#### 主要修改

- 新增 `RpcReferenceBeanPostProcessor`，接入 Spring Bean 的属性填充阶段；
- 处理器先扫描当前类和父类，只有发现 `@RpcReference` 才取得客户端工厂；
- 发现注解后复用 core 的 `RpcClientFactory.injectReferences()`，不重复实现字段
  校验、JDK 动态代理创建和反射赋值；
- 处理器通过 `ObjectProvider<RpcClientFactory>` 延迟取得工厂，普通 Bean 不会
  导致 ZooKeeper 连接；
- 在自动配置中使用静态 `@Bean` 方法尽早注册处理器；
- 新增测试，验证处理器已注册，并且只对含注解的 Bean 索取客户端工厂；
- README 同步更新 S2 状态、Starter 职责、使用方式和当前限制。

#### 得到的能力

对于真正由 Spring 创建的业务对象，`@RpcReference` 字段现在可以在初始化之前
自动获得 RPC 动态代理。业务代码不再需要调用 `createProxy()` 或
`injectReferences()`。

#### 设计原因

- 使用属性填充阶段而不是初始化完成以后再注入，使 `@PostConstruct` 执行时
  RPC 字段已经可用；
- 使用 `ObjectProvider` 保留客户端工厂的延迟创建语义；
- Spring 层只负责决定“什么时候注入”，字段规则和具体注入仍由 core 负责；
- 本阶段暂不迁移示例，等 Provider Spring 发布完成后再做完整端到端迁移。

#### 验证与限制

- 执行 `mvn test -pl mini-rpc-spring-boot-starter -am`，core 与 Starter 构建成功，
  Starter 累计 5 个测试全部通过；
- 单元测试不连接 ZooKeeper，使用会抛出标记异常的 ObjectProvider 验证按需取值；
- 后续 S4 已在 Spring Boot Consumer 中完成真实 ZooKeeper、Netty 端到端联调；
- 同一业务 Bean 中存在错误注解字段时，core 注入器会阻止半完成注入；
- 后续 S3 已让 Spring 识别 `@RpcService` Bean，替代示例中的无参构造扫描方式。

---

### 3.15 S3：把 `@RpcService` 发布链路接入 Spring

- 日期：2026-10-23
- 对应提交：`feat: complete Spring Boot RPC integration`
- 状态：已实现并验证

#### 要解决的问题

旧 `RpcServiceScanner` 会自己反射调用公开无参构造方法，因此服务实现不能正常
使用构造器注入。目标是让 Starter 只负责识别 RPC 注解，而把对象创建、依赖
解析和生命周期交给 Spring。

#### 主要修改

- `MiniRpcProperties` 新增 `provider-enabled`、`server-host` 配置，已有
  `server-port` 一并接入 Provider 创建；
- 新增 `RpcServiceBeanDefinitionRegistryPostProcessor`，扫描 Spring Boot 启动类
  所在基础包，只查找 `@RpcService`，并把结果注册成 BeanDefinition；
- 扫描器不调用服务类构造方法，Spring 在正常 Bean 创建阶段完成构造器选择和
  依赖注入；
- 新增 `SpringRpcProviderLifecycle`，从 Spring 容器取得服务 Bean，注册到
  `RpcProvider`，并在后台线程启动阻塞式 Netty 服务；
- Spring 容器关闭时，生命周期组件关闭 Provider，并等待后台线程退出；
- `MiniRpcAutoConfiguration` 按 `mini-rpc.provider-enabled=true` 条件注册扫描器、
  `RpcProvider` 和生命周期组件；
- 新增扫描测试及只有有参构造方法的测试服务，验证依赖确实由 Spring 注入；
- 更新核心类中文注释，区分 BeanDefinition 注册阶段与 Bean 创建阶段。

#### 行为变化

Provider 业务实现不再要求公开无参构造方法。只要类位于 Spring Boot 基础扫描包
内并标记 `@RpcService`，它就会成为真正的 Spring Bean，可以使用构造器注入、
`@Autowired`、AOP 等 Spring 能力。

#### 设计原因

- BeanDefinition 可以理解为“对象创建说明书”，先登记说明书，再由 Spring 按
  统一规则创建对象，避免 mini-RPC 重写一套依赖注入容器；
- 使用 `SmartLifecycle` 把 RPC 服务端资源纳入 Spring 启停过程；
- `RpcProvider.start()` 会阻塞等待 Netty 端口关闭，因此放到独立平台线程中，
  不能堵住 Spring 主启动线程；
- 通过 `provider-enabled` 区分 Provider 与纯 Consumer，避免 Consumer 意外启动
  Netty 服务端。

#### 验证与限制

- `RpcServiceBeanDefinitionRegistryPostProcessorTest` 验证一个没有无参构造方法的
  `@RpcService` 能取得 Spring 提供的构造器参数；
- Starter 当前累计 6 个自动化测试；
- 旧的非 Spring `RpcServiceScanner` 仍然保留，并继续存在无参构造限制；
- Provider 目前仍先注册 ZooKeeper 地址、再启动 Netty，存在极短的可见性窗口。

---

### 3.16 S4：示例迁移到 Spring Boot 并完成真实端到端联调

- 日期：2026-10-23
- 对应提交：`feat: complete Spring Boot RPC integration`
- 状态：已完成并验证

#### 要解决的问题

Starter 的各个扩展点单独存在还不代表真正开箱即用。本阶段把 Provider 和
Consumer 示例都改为业务代码不手动创建 RPC 基础设施，并使用真实 ZooKeeper、
Netty 完成一次完整调用。

#### 主要修改

- 父 POM 的依赖管理加入 `mini-rpc-spring-boot-starter`；
- Starter 改为依赖 `spring-boot-starter`，统一提供 `SpringApplication`、日志和
  YAML 配置支持；
- Provider、Consumer 示例从直接依赖 core 改为依赖 Starter；
- Provider 新增 `application.yml`，开启 Provider 并配置注册中心、主机和端口；
- `ProviderMain` 简化为 `@SpringBootApplication + SpringApplication.run()`；
- 新增普通 Spring Bean `ProviderIdentity`，`HelloServiceImpl` 改为只含有参构造
  方法，通过实际响应中的进程 ID 证明构造器注入生效；
- Consumer 新增 `application.yml`，`ConsumerMain` 改为 Spring Boot 应用，只在
  字段声明 `@RpcReference`，不再创建工厂或调用 `injectReferences()`；
- README 同步更新模块关系、Spring 启动链、运行参数、断点顺序和当前限制。

#### 行为变化

最终示例的业务入口已经接近目标用法：Provider 只写 `@RpcService`，Consumer
只写 `@RpcReference`。Netty、ZooKeeper、代理工厂、服务发布和资源关闭均由
Starter 与 Spring 容器管理。

#### 验证方式与结果

- 执行 `mvn test`，全部模块编译成功，Starter 6 个测试全部通过；
- 使用 Docker 容器 `mini-rpc-zookeeper` 提供 ZooKeeper 3.9.6；
- Provider 启动后，ZooKeeper 目录出现临时节点 `127.0.0.1:8080`；
- Consumer 连续完成 4 次 `sayHello` 调用，响应均携带 Provider 进程 ID；
- Consumer 退出时自动关闭客户端工厂、Netty 连接和 ZooKeeper 会话；
- Provider 关闭后，服务目录子节点从 `[127.0.0.1:8080]` 变成 `[]`，证明临时
  地址随会话结束清理。

#### 当前限制和下一步

- `request-timeout` 已读取但还没有传给底层 `NettyRpcClient`；
- 真实端到端验证目前依赖本机 Docker，尚未封装成 Testcontainers 自动化测试；
- 下一步优先把超时配置真正接入客户端，再实现服务发现缓存和节点监听。

---

### 3.17 S4-B：拆分 Consumer 启动与演示调用职责

- 日期：2026-10-23
- 对应提交：`feat: complete Spring Boot RPC integration`
- 状态：已实现并验证

#### 要解决的问题

`ConsumerMain` 同时负责启动 Spring Boot、持有远程引用、执行演示循环和主动关闭
容器，容易让初学者误以为这些都是使用 RPC 必须编写的代码。目标是让示例更接近
Dubbo 项目的常见结构：启动类只启动应用，远程引用位于真正使用它的业务 Bean。

#### 主要修改

- `ConsumerMain` 去掉 `CommandLineRunner`、`@RpcReference`、调用循环和
  `try-with-resources`，只保留 `SpringApplication.run()`；
- 新增 `HelloConsumerRunner` Spring Bean，专门保存 `HelloService` 远程引用并
  执行四次演示调用；
- 为两个类补充中文注释，明确 Runner 在真实项目中通常由 Controller 或 Service
  替代，四次循环只用于观察多 Provider 轮询；
- README 同步更新 Consumer 模块职责、运行说明和断点位置。

#### 行为变化与设计原因

RPC 请求内容和次数没有变化，但 Spring 容器不再由启动类主动关闭。现在启动类
保持稳定，RPC 调用属于普通 Spring Bean，结构更接近长期运行的实际应用，也更
准确地展示 `@RpcReference` 可以写在 Controller、Service 或 Runner 中。

#### 验证与限制

- 执行根工程 `mvn test`，全部模块构建成功，Starter 6 个测试全部通过；
- 本阶段只整理示例职责，没有修改协议、代理、网络或注册中心行为；
- 下一步仍是把 `request-timeout` 配置接入底层客户端。

---

### 3.18 S5-A：接通 Consumer 全局请求超时配置

- 日期：2026-10-23
- 对应提交：尚未提交
- 状态：已实现并验证

#### 要解决的问题

`application.yml` 中的 `mini-rpc.request-timeout` 虽然已经能够绑定到
`MiniRpcProperties`，但客户端等待响应时仍然使用 `NettyRpcClient` 内写死的
5 秒常量。修改配置不会改变运行行为，容易给使用者造成误解。

#### 主要修改

- `NettyRpcClient` 使用 `Duration` 定义默认 5 秒超时，并新增可传入超时的构造
  方法；旧构造方法继续使用默认值，保证早期教学代码兼容；
- 把配置转换成毫秒保存，拒绝空值、零值、负值以及小于 1 毫秒的配置；
- `CompletableFuture.get()` 改为使用实例自己的超时时间，超时异常同时报告实际
  毫秒数；
- `RpcClientFactory` 新增“ZooKeeper 地址 + 请求超时”构造方法，并将超时继续
  传递给 `NettyRpcClient`；
- `MiniRpcAutoConfiguration` 创建客户端工厂时，同时传入注册中心地址和
  `MiniRpcProperties.requestTimeout`；
- 新增测试，验证 750 毫秒能够保存到底层客户端，并拒绝零毫秒；
- README 同步更新已生效配置、当前限制和下一步计划。

#### 行为变化

Consumer 现在可以通过下面的配置控制请求发出后等待响应的最长时间：

```yaml
mini-rpc:
  request-timeout: 750ms
```

这个值只控制 RPC 响应等待，不控制 TCP 建立连接的超时。未填写配置时仍使用
默认 5 秒，因此现有应用行为保持不变。

#### 设计原因

- core 使用 JDK 自带的 `Duration`，不依赖 Spring，同时可以表达毫秒和秒；
- 保留旧构造方法，避免为了增加 Starter 配置破坏纯 Java 教学入口；
- 当前先实现全局超时，后续再让 `@RpcReference` 支持单个服务覆盖全局值，保持
  每一步修改范围便于审核。

#### 验证与限制

- 执行根工程 `mvn test`，全部模块构建成功，Starter 8 个测试全部通过；
- 测试构造 Netty 客户端但不连接 Provider，验证配置保存和边界校验；
- 当前还没有 `@RpcReference(timeout=...)` 单引用覆盖；
- 下一步进入 S5-B：服务发现本地缓存和 ZooKeeper 节点变化监听。

---

### 3.19 S5-B-A：服务发现增加短期本地地址缓存

- 日期：2026-10-23
- 对应提交：尚未提交
- 状态：已实现并验证

#### 要解决的问题

此前每次 RPC 调用都会执行一次 ZooKeeper `getChildren()`。同一个 Consumer 连续
调用同一服务时，Provider 地址通常没有变化，重复访问注册中心会增加延迟和
ZooKeeper 压力。但在还没有节点监听时，也不能永久缓存，否则 Provider 下线后
Consumer 会一直使用旧地址。

#### 主要修改

- `ZooKeeperServiceDiscovery` 新增按服务名称隔离的并发地址缓存；
- 第一次发现服务时查询 ZooKeeper，并把不可变地址快照缓存 3 秒；
- 3 秒内重复发现同一服务直接返回缓存，过期后自动重新查询；
- 使用 `System.nanoTime()` 判断经过时间，避免用户调整系统时间破坏缓存有效期；
- 使用 `ConcurrentHashMap.compute()` 按服务名称原子刷新，同一服务并发缓存失效时
  只由一个线程访问 ZooKeeper，不同服务仍可并行刷新；
- 关闭服务发现对象时同时清空本地缓存；
- core 新增仅测试范围的 JUnit 与 Mockito 依赖，并新增第一组 core 单元测试；
- README 同步更新阶段状态、服务发现行为、当前限制和后续计划。

#### 行为变化

假设 Consumer 在 3 秒内连续调用四次 `HelloService`：

```text
第 1 次：查询 ZooKeeper，并写入本地缓存
第 2 次：读取本地缓存
第 3 次：读取本地缓存
第 4 次：读取本地缓存
```

缓存过期后的下一次调用会重新查询 ZooKeeper。不存在服务时得到的空列表也会
短暂缓存，避免大量无效请求持续访问注册中心。

#### 设计原因

- 永久缓存会产生无法恢复的陈旧地址，因此在 Watch 完成前先使用短 TTL；
- 缓存保存不可变 `List`，防止调用方修改列表导致后续负载均衡读到脏数据；
- 本阶段不同时引入 Curator Watch，便于分别理解“缓存”和“监听”的职责。

#### 验证与限制

- 单元测试连续发现同一服务两次，并验证 Curator `forPath()` 只执行一次；
- 执行根工程 `mvn test`，core 1 个、Starter 8 个测试全部通过；
- Provider 地址变化不会立即推送给 Consumer，最迟要等 3 秒缓存过期；
- 下一步 S5-B-B 将加入 ZooKeeper 节点监听，在 Provider 上下线时主动更新或
  失效缓存。

---

### 3.20 S5-B-B：ZooKeeper Watch 主动失效地址缓存

- 日期：2026-10-23
- 对应提交：尚未提交
- 状态：已实现并验证

#### 要解决的问题

短期缓存减少了 ZooKeeper 查询，但仅依赖 3 秒 TTL 时，Provider 上线或下线后
Consumer 最多要等待 3 秒才能看到变化。目标是在节点变化发生时立刻使旧缓存
失效，同时继续保留 TTL 作为异常情况下的兜底。

#### 主要修改

- `ZooKeeperServiceDiscovery` 每次真正查询服务目录时，通过 Curator
  `usingWatcher()` 注册一次性 Watch；
- Provider 地址子节点新增或删除时，监听回调删除对应服务的本地缓存；
- 下一次 `discover()` 因缓存缺失重新查询 ZooKeeper，并自动注册下一次 Watch；
- 服务目录尚不存在时，改为监听目录本身的创建事件；第一个 Provider 注册后，
  原先缓存的空地址列表会立即失效；
- 不额外引入 `curator-recipes`，复用已有 `curator-framework` 的 Watch API；
- 扩展 core 单元测试，模拟 `NodeChildrenChanged` 事件，验证事件后再次发现服务
  会重新访问 ZooKeeper 并取得新地址；
- README 同步更新阶段状态、服务发现行为、当前限制和下一步计划。

#### 行为变化

Provider 地址变化后的流程变为：

```text
Provider 上线或下线
        ↓
ZooKeeper 产生节点事件
        ↓
Consumer Watch 收到通知
        ↓
删除该服务的本地地址缓存
        ↓
下一次 RPC 查询最新地址并注册新 Watch
```

#### 设计原因

- ZooKeeper Watch 是一次性的，因此不能收到事件后继续假设监听仍然有效；
- 事件回调只做轻量的缓存删除，不在 ZooKeeper 回调线程中执行网络查询；
- 下一次业务调用负责重新查询和注册 Watch，使监听生命周期容易理解；
- TTL 与 Watch 同时保留：Watch 提供及时性，TTL 处理监听丢失或长时间无事件的
  兜底刷新。

#### 验证与限制

- core 累计 2 个测试：一个验证缓存复用，一个验证 Watch 事件后重新查询；
- 执行根工程 `mvn test`，core 2 个、Starter 8 个测试全部通过；
- 当前监听采用“事件后失效、下次调用再刷新”，不是后台主动刷新地址；
- 下一步优先调整 Provider 发布顺序：Netty 监听成功后再注册 ZooKeeper 地址。

---

### 3.21 S5-C：端口绑定成功后再发布 Provider 地址

- 日期：2026-10-23
- 对应提交：尚未提交
- 状态：已实现并验证

#### 要解决的问题

此前 `RpcProvider.registerService(...)` 会立即把 Provider 地址写入 ZooKeeper，
而 Netty 端口要到之后调用 `start()` 才开始绑定。两者之间存在一个短暂窗口：
Consumer 已经能够发现地址，但连接该地址时端口可能还没有准备好。如果端口被
占用导致绑定彻底失败，注册中心还会短暂出现一个实际不可用的地址。

#### 主要修改

- `RpcProvider.registerService(...)` 现在只登记本地“接口 -> 实现对象”映射，并
  保存稍后需要发布的服务名称；
- `RpcServer` 新增带 `Runnable` 的启动方法，用统一回调表示“网络端口已经绑定
  成功”，原来的无参数 `start()` 作为默认方法保留；
- `NettyRpcServer` 在 `bind(port).sync()` 成功后执行回调，再等待 Channel 关闭；
- 教学用 `SocketRpcServer` 在 `new ServerSocket(port)` 成功后执行同样的回调；
- `RpcProvider.start()` 把 `publishServices()` 作为回调传给网络服务端，回调中
  才逐个向注册中心发布地址；
- `ServiceRegistry` 继承 `AutoCloseable` 并声明 `close()`，让 `RpcProvider` 依赖
  注册中心抽象，而不是写死 ZooKeeper 实现；
- 新增 `RpcProviderStartupOrderTest`，使用内存测试替身验证正常顺序和端口绑定
  失败时的行为；
- README 同步更新完成状态、完整启动链、调试断点和当前限制。

#### 行为变化

Provider 启动顺序现在是：

```text
Spring 创建 @RpcService Bean
        ↓
写入 Provider 本地服务表
        ↓
Netty 向操作系统绑定监听端口
        ↓ 只有绑定成功才执行回调
向 ZooKeeper 发布所有服务地址
        ↓
Consumer 可以发现并连接 Provider
```

如果 Netty 端口绑定失败，启动成功回调不会执行，因此不会向 ZooKeeper 发布地址。
如果地址发布过程中失败，异常会继续向上抛出，Netty 端口和 ZooKeeper 会话都会
在 `finally` 清理，已经创建的临时节点也会随会话关闭而消失。

#### 设计原因

- 回调表达的是通用的“网络已准备好”事件，Netty 和 Socket 不需要依赖 ZooKeeper；
- 先监听、后发布可以保证“注册中心可见”意味着 Provider 至少已经能够接受连接；
- 保留无参数 `RpcServer.start()`，旧教学代码仍然可以按原来的方式启动；
- 使用 `ServiceRegistry` 抽象便于测试，也为以后替换其他注册中心保留边界。

#### 验证与限制

- 执行 `mvn -q -pl mini-rpc-core test`，core 4 个测试全部通过；
- 执行根工程 `mvn -q test`，core 4 个、Starter 8 个测试全部通过；
- 顺序测试验证事件严格为“本地登记 -> 端口绑定 -> 注册中心发布”；
- 失败测试验证端口绑定失败时注册地址发布次数为 0，并正常执行资源关闭；
- 当前测试使用内存替身验证顺序，真实 ZooKeeper + Netty 的可重复容器集成测试
  仍是下一步。

### 3.22 S5-D：Testcontainers 真实端到端集成测试

- 日期：2026-10-24
- 对应提交：`feat: add integration tests and reference timeouts`
- 状态：已实现并验证

#### 要解决的问题

此前单元测试分别验证了服务发现缓存、Watch 和 Provider 发布顺序，但注册中心与
网络层都使用了模拟对象或内存替身。真实 ZooKeeper + Netty 链路只经过人工联调，
无法随着 `mvn test` 重复执行，也无法自动发现后续修改造成的跨组件回归。

#### 主要修改

- 父 POM 引入 Testcontainers 2.0.5 BOM，统一容器测试组件版本；BOM 放在
  Spring Boot BOM 前，避免 Boot 管理的旧 Testcontainers 版本覆盖 2.x 组件；
- `mini-rpc-core` 增加仅测试范围的 `testcontainers` 与
  `testcontainers-junit-jupiter` 依赖，生产运行包和业务项目不受影响；
- 新增 `RpcEndToEndIntegrationTest`，使用固定的
  `zookeeper:3.9.6-jre-17` 镜像和宿主机随机映射端口；
- 测试在随机空闲端口启动真实 `RpcProvider`，使用独立 Curator 会话检查临时
  节点，再组装真实服务发现、轮询选址、Netty 客户端和动态代理完成 RPC 调用；
- Provider 关闭后同时等待 Netty 线程退出、ZooKeeper 临时节点删除和 Consumer
  地址列表变空；
- 测试将缓存 TTL 设为 30 秒，但只等待 Watch 10 秒。地址在 TTL 到期前变空，
  可以区分“Watch 主动失效”和“缓存自然过期”；
- 使用 `disabledWithoutDocker = true`：Docker 不可用时只跳过容器用例，不阻塞
  与外部基础设施无关的普通单元测试；
- README 同步更新阶段状态、测试运行方式、环境要求与下一步计划。

#### 行为变化

生产 RPC 行为没有变化。本阶段改变的是验证能力：Docker 可用时，根工程
`mvn test` 会自动执行下面的真实链路：

```text
Testcontainers 启动 ZooKeeper
        ↓
RpcProvider 绑定随机 Netty 端口并发布临时节点
        ↓
Consumer 发现地址并写入 30 秒缓存
        ↓
动态代理发起真实 RPC 并取得响应
        ↓
关闭 Provider，临时节点随会话消失
        ↓
Watch 删除旧缓存，下一次发现得到空列表
```

#### 设计原因

- 测试放在 core，是因为被验证的协议、Netty、注册中心、代理和生命周期都属于
  core；无需启动两个 Spring Boot 示例进程，也不让 core 反向依赖 example；
- 测试使用专用最小服务接口，避免示例业务改文案时无关地破坏框架集成测试；
- ZooKeeper 容器端口随机映射，Provider 端口也动态选择，降低和开发者本机服务
  冲突的概率；
- 轮询等待异步状态并设置 10 秒上限，不使用不可解释的固定长时间休眠；
- 没有同时引入重试、心跳或 SPI，保持本阶段只补足测试基础设施。

#### 验证方式与结果

- 执行 `mvn -pl mini-rpc-core -Dtest=RpcEndToEndIntegrationTest test`；
- Testcontainers 成功启动 ZooKeeper 3.9.6，端到端用例 1 个通过，0 失败、
  0 错误、0 跳过；
- 用例实际验证了 RPC 响应、临时节点创建与删除、Provider 线程退出以及 Watch
  在 30 秒 TTL 到期前让 Consumer 缓存失效；
- Docker 未运行时预先验证了同一用例会被标记为跳过，core 其余 4 个测试通过；
- 当前限制是执行容器用例需要可用的 Docker 引擎；下一步建议实现
  `@RpcReference` 单引用超时覆盖全局配置。

### 3.23 S5-E：@RpcReference 单引用超时覆盖全局配置

- 日期：2026-10-24
- 对应提交：`feat: add integration tests and reference timeouts`
- 状态：已实现并验证

#### 要解决的问题

S5-A 已经让 `mini-rpc.request-timeout` 成为真正生效的全局配置，但所有远程引用
只能使用同一个等待时间。快速查询和慢速报表等服务的合理超时往往不同，如果为
每个超时创建一套客户端，又会重复创建 Netty 线程、Channel 和 ZooKeeper 连接。

#### 主要修改

- `@RpcReference` 新增 `timeoutMillis`，默认值 `-1` 表示继承全局配置，显式值
  必须大于 0；
- `RpcClient` 新增带 `Duration` 的发送重载，默认实现明确报告不支持，避免早期
  Socket 教学客户端悄悄忽略覆盖值；
- `NettyRpcClient` 把原有全局超时保留为默认值，同时允许每次 `sendRequest()`
  传入本次有效超时；等待和超时异常都使用本次值；
- `RpcClientProxy` 保存可选的单引用超时：没有覆盖时调用原发送入口，有覆盖时
  调用带超时的入口；
- `RpcClientFactory` 新增带 `Duration` 的 `createProxy()`，只创建轻量代理，继续
  复用同一个 Netty 客户端、Channel 缓存和服务发现连接；
- `RpcReferenceInjector` 读取字段注解，先统一校验所有字段，再按默认或覆盖值
  创建代理；非法超时会在 Spring Bean 注入阶段得到包含字段名的明确错误；
- 注入器内部依赖收窄成最小 `ProxyCreator`，使单元测试无需连接 ZooKeeper，也
  不需要为了测试取消 `RpcClientFactory` 的 final 限制；
- 新增注入、动态代理和 Netty 实际等待测试，并让 Testcontainers 端到端用例
  使用显式 2 秒覆盖值完成真实 RPC；
- README 同步增加使用示例、阶段状态和当前限制。

#### 行为变化

全局配置仍然提供默认值：

```yaml
mini-rpc:
  request-timeout: 5s
```

Consumer 可以只为特定引用覆盖：

```java
@RpcReference(timeoutMillis = 750)
private HelloService helloService;
```

调用链变为：

```text
Spring 发现 @RpcReference 字段
        ↓
RpcReferenceInjector 读取 timeoutMillis
        ↓
-1：创建使用全局默认值的代理
正数：创建携带单引用超时的代理
        ↓
RpcClientProxy 每次调用传递所选超时
        ↓
同一个 NettyRpcClient 使用本次有效超时等待响应
```

#### 设计原因

- 超时属于 Consumer 等待策略，不写入 `RpcRequest`，避免把纯客户端配置发送给
  Provider 或改变网络协议；
- 超时按调用传给共享客户端，而不是按引用创建客户端，避免资源数量随字段数量
  增长；
- 注解属性使用 `timeoutMillis` 明确单位，避免裸数字的含义不清楚；
- `-1` 只表示“继承全局”，0 和其他负数不具备业务含义，因此尽早拒绝；
- 保留原 `sendRequest(request)` 和构造方法，早期教学代码及现有调用保持兼容。

#### 验证方式与结果

- `RpcReferenceInjectorTimeoutTest` 验证默认继承、750ms 覆盖和非法 0ms；
- `RpcClientProxyTimeoutTest` 验证代理分别选择默认发送入口和带超时入口；
- `NettyRpcClientRequestTimeoutTest` 使用只接受连接但不响应的本地 TCP 服务端，
  验证单次 100ms 覆盖不会继续等待全局 5 秒，并报告实际超时值；
- `RpcEndToEndIntegrationTest` 使用单引用 2 秒超时完成真实 ZooKeeper + Netty RPC；
- 执行 core 测试（Docker 未启动），10 个普通测试通过，容器测试按设计跳过；
- 启动 Docker 后执行根工程 `mvn test`，core 11 个、Starter 8 个测试全部通过，
  共 19 个测试，0 失败、0 错误、0 跳过；
- 当前限制是注解只覆盖请求等待时间，不覆盖 TCP 连接超时；下一步建议让序列化器
  和负载均衡策略可以通过 Starter 配置切换。

### 3.24 S5-F：序列化器和负载均衡策略 Starter 配置化

- 日期：2026-10-24
- 对应提交：`feat: configure RPC strategies`
- 状态：已实现并验证

#### 要解决的问题

Starter 虽然已经自动组装完整调用链，但 `RpcClientFactory` 固定创建
`JsonSerializer + RoundRobinLoadBalance`，`RpcProvider` 也固定创建
`JsonSerializer`。框架已经有对应接口，却还不能通过 Spring Boot 配置选择已有
实现；业务方若要改变策略，只能重新手工组装高层入口。

#### 主要修改

- `MiniRpcProperties` 新增 `serializer` 和 `load-balance`：序列化可选 `json/jdk`，
  负载均衡可选 `round-robin/random`，默认值保持 JSON 与轮询不变；
- core 新增无共享状态的 `RandomLoadBalance`，使用 `ThreadLocalRandom` 从候选
  Provider 中等概率选址；
- `RpcClientFactory` 新增接收 `Serializer`、`LoadBalance` 的构造方法，原构造方法
  继续委托默认 JSON 与轮询，保留非 Spring 教学入口的兼容性；
- `RpcProvider` 新增接收 `Serializer` 的构造方法，Starter 选中的序列化方式因此
  同时接入 Consumer 请求编码和 Provider 响应编码；
- 自动配置新增 `Serializer`、`LoadBalance` Bean，并使用
  `@ConditionalOnMissingBean` 允许业务应用提供兼容的自定义 Bean；
- Provider、Consumer 示例配置和 README 同步补充新选项、默认值与安全限制；
- 新增随机策略测试，以及默认选择、配置切换和自定义 Bean 让位测试。

#### 行为变化

默认配置不变，下面两个属性即使省略也仍使用 JSON 与轮询：

```yaml
mini-rpc:
  serializer: json
  load-balance: round-robin
```

Consumer 可以改为 JDK 请求编码和随机选址：

```yaml
mini-rpc:
  serializer: jdk
  load-balance: random
```

调用链中的固定实现变为配置选择：

```text
MiniRpcProperties
        ↓
Serializer Bean + LoadBalance Bean
        ↓
RpcClientFactory / RpcProvider
        ↓
Netty 编码请求或响应 / Consumer 选择 Provider
```

#### 设计原因

- 配置枚举只暴露框架已经实现并能解码的选项，拼写错误会在 Spring 配置绑定阶段
  直接失败，避免运行到第一次 RPC 调用才发现配置无效；
- 选择发生在 Starter，core 继续只依赖抽象接口，保持 core 不依赖 Spring；
- Consumer 和 Provider 共用同一个 `Serializer` Bean，但它们只用它编码本端发出
  的正文；接收方仍根据协议头的序列化编号选择内置解码器；
- 保留原构造方法和默认值，已有纯 Java 示例与未填写新配置的应用行为不变；
- Spring Bean 覆盖为以后扩展保留入口，但当前解码器只支持 JDK、JSON 类型，尚未
  建立可以动态注册任意序列化编号的完整 SPI。

#### 验证方式与结果

- `RandomLoadBalanceTest` 验证单候选地址选择和空列表校验，测试不依赖随机概率；
- `MiniRpcAutoConfigurationTest` 验证枚举属性绑定、YAML 中的 `round-robin` 写法、
  默认 JSON + 轮询、切换为 JDK + 随机，以及业务自定义 Bean 覆盖内置 Bean；
- 执行 `mvn -pl mini-rpc-core,mini-rpc-spring-boot-starter -am test`，core 13 个
  测试中 12 个通过、容器测试因 Docker 未运行跳过，Starter 12 个测试全部通过，
  0 失败、0 错误；
- 执行根工程 `mvn test`，六个 Maven 模块全部构建成功，两个示例应用也完成编译；
- 当前限制是只有两种内置序列化和两种负载均衡，且没有权重、服务版本、SPI；
  下一步建议实现 Provider 优雅停机与在途请求排空。

### 3.25 S5-G-A：Provider 主动摘流与 Netty 排空顺序

- 日期：2026-10-24
- 对应提交：`feat: improve RPC shutdown and deadlines`
- 状态：已实现并验证

#### 要解决的问题

此前 `RpcProvider.close()` 先停止 Netty，最后通过关闭 ZooKeeper 会话让临时节点
消失。正常停机时地址在网络服务停止后仍可能短暂可见，Consumer 有机会选到已经
停止接收请求的 Provider；同时代码没有记录哪些服务真正发布成功，无法精确主动
注销“发布到一半”的部分结果。

#### 主要修改

- `RpcProvider` 新增 `publishedServiceNames`，只在注册中心 `register()` 成功返回后
  记录服务，区分“本地准备提供”和“已经对外发布”两个状态；
- `publishServices()` 与 `close()` 使用同一个对象锁协调，关闭已经开始后拒绝继续
  发布，减少启动与关闭并发时产生残留地址的风险；
- 关闭顺序调整为“主动注销已发布地址 → 停止 Netty → 关闭注册中心会话”；
- 任一步清理失败时仍继续执行后续清理，最后抛出第一个异常，后续异常保存为
  suppressed exception，避免一个注销异常阻止端口和线程释放；
- `NettyRpcServer.stop()` 的注释明确现有 `shutdownGracefully()` 会等待已经进入
  EventLoop 的任务完成；
- 启动顺序测试增加注销事件，并新增“注销失败仍继续停止 Netty 和关闭注册中心”
  用例；README 同步增加完整关闭调用链和当前边界。

#### 行为变化

正常关闭流程变为：

```text
RpcProvider.close()
        ↓
ServiceRegistry.unregister(...)
地址节点主动删除，Consumer Watch 失效缓存
        ↓
RpcServer.stop()
关闭监听端口并等待 Netty worker 优雅终止
        ↓
ServiceRegistry.close()
关闭会话并兜底清理临时节点
```

端口绑定失败时 `publishedServiceNames` 为空，不会伪造注销动作；如果多个服务中只有
前几个发布成功，关闭阶段只注销这些已经成功发布的服务。

#### 设计原因

- 先从注册中心摘流，可以先阻止新的 Consumer 发现当前节点，再关闭网络服务；
- 单独记录已发布集合，不能只使用本地 `serviceNames`，因为本地登记不代表远程
  地址节点已经创建成功；
- 主动注销用于缩短正常停机的地址可见窗口，关闭 ZooKeeper 会话仍作为进程异常、
  主动注销失败等情况的最终兜底；
- 本步复用 Netty 自身的优雅线程组关闭，不同时引入请求计数器和新配置，保持修改
  容易审核。

#### 验证方式与结果

- `RpcProviderStartupOrderTest` 验证完整顺序为“本地登记 → 端口绑定 → 发布地址 →
  主动注销 → 停止服务端 → 关闭注册中心”；
- 新增注销失败场景，验证异常发生后仍会执行 Netty 停止和注册中心关闭；
- 执行 `mvn -q -pl mini-rpc-core -Dtest=RpcProviderStartupOrderTest test`，3 个测试
  全部通过；
- 执行根工程 `mvn test`，core 14 个测试中 13 个通过、容器测试因 Docker 未运行
  跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功；
- 当前依赖 Netty `shutdownGracefully()` 排空 EventLoop 任务，还没有显式在途请求
  计数和可配置最大排空时间；下一步建议完成这两个能力。

### 3.26 S5-G-B：在途请求计数与可配置排空超时

- 日期：2026-10-24
- 对应提交：`feat: improve RPC shutdown and deadlines`
- 状态：已实现并验证

#### 要解决的问题

S5-G-A 已经先从 ZooKeeper 摘流，再调用 Netty 的优雅关闭，但框架无法回答“当前
还有多少 RPC 没结束”，也不能限制主动等待的时间。若只根据业务方法是否返回来
判断完成，响应可能还在 Netty 写队列中，Consumer 仍然收不到结果。

#### 主要修改

- 新增 `InFlightRequestTracker`，以同步状态保护“是否接受新请求”和“当前在途数”，
  避免关闭线程判断为 0 后又有请求进入的竞态；
- `NettyRpcServerHandler` 接受请求并取得执行许可时计数 `+1`，响应
  `ChannelFuture` 完成时计数 `-1`，覆盖业务执行和响应写入两个阶段；
- 开始排空后，旧连接继续发来的新请求不会进入业务方法，而是收到“Provider 正在
  关闭”的失败响应；
- `NettyRpcServer.stop()` 先关闭监听 Channel，再等待在途数归零；超时后记录剩余
  数量，对 worker 发起立即关闭且不再同步等待，避免关闭线程再次无限等待；
- 新增 `mini-rpc.shutdown-timeout`，默认 10 秒，`MiniRpcProperties →
  MiniRpcAutoConfiguration → RpcProvider → NettyRpcServer` 全链路生效；
- 原有 `NettyRpcServer`、`RpcProvider` 构造方法继续委托默认 10 秒，保留纯 Java
  教学入口兼容性；Provider 示例和 README 同步补充配置与行为；
- 新增排空器单元测试，并在 Starter 属性绑定测试中验证 `12s` 配置。

#### 行为变化

Provider 可以配置：

```yaml
mini-rpc:
  shutdown-timeout: 10s
```

请求与关闭线程的协作关系变为：

```text
Netty Handler 接受请求
        ↓
tryStartRequest()：在途数 +1
        ↓
执行服务方法并写出 RpcResponse
        ↓
ChannelFuture 完成：在途数 -1

Spring 请求关闭
        ↓
beginDraining()：禁止新业务请求
        ↓
等待计数归零，最长 shutdown-timeout
        ↓
正常排空，或超时后继续关闭 Netty
```

#### 设计原因

- 在 `tryStartRequest()` 内同时检查接收状态和增加计数，确保关闭与请求进入之间
  没有检查后再修改的竞态窗口；
- 在响应写入 Future 完成后才归还计数，比业务方法返回时归还更符合“Consumer 有
  机会拿到完整结果”的定义；
- 超时后使用零 quiet period、零 timeout 发起 Netty worker 立即关闭，并且不再
  同步等待 graceful Future，避免 Spring 关闭线程再次无限等待；
- 配置使用 Spring Boot `Duration`，可以明确写 `500ms`、`10s`，避免裸数字单位
  不清楚；小于 1 毫秒的值会在创建服务端时被拒绝；
- 任意 Java 业务代码若永久阻塞并忽略线程中断，框架不能安全强杀线程，这是当前
  实现以及 Java 线程模型本身需要明确说明的边界。

#### 验证方式与结果

- `InFlightRequestTrackerTest` 验证关闭前请求可以进入、开始排空后新请求被拒绝、
  完成响应后等待成功、超时返回 false，以及小于 1 毫秒配置被拒绝；
- `NettyRpcServerHandlerDrainTest` 使用 Netty `EmbeddedChannel` 验证响应写完后计数
  归零，以及排空开始后的请求不会执行 `RpcRequestHandler`；
- `MiniRpcAutoConfigurationTest` 验证 `mini-rpc.shutdown-timeout=12s` 正确绑定为
  `Duration.ofSeconds(12)`；
- 执行 `mvn -pl mini-rpc-core,mini-rpc-spring-boot-starter -am test`，core 17 个
  测试中 16 个通过、容器测试因 Docker 未运行跳过，Starter 12 个测试全部通过；
- 增加 Handler 排空测试后执行根工程 `mvn test`，core 19 个测试中 18 个通过、
  容器测试按设计跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功；
- 下一步建议实现 Consumer deadline 向 Provider 传递，并让 Provider 在执行前判断
  请求是否已经超时。

### 3.27 S5-H-A：Consumer deadline 传递与 Provider 执行前检查

- 日期：2026-10-24
- 对应提交：`feat: improve RPC shutdown and deadlines`
- 状态：已实现并验证

#### 要解决的问题

此前 Consumer 超时只会结束本地 `CompletableFuture` 等待。请求可能因为网络延迟
或 Provider 排队，在 Consumer 已经放弃后才开始执行；Provider 不知道客户端期限，
仍可能执行数据库查询、写入等已经没有返回价值的业务操作。

#### 主要修改

- `RpcRequest` 新增 `deadlineEpochMillis`，0 表示早期调用方没有携带 deadline；
- `NettyRpcClient` 在服务发现和建连完成、真正发送请求之前，根据本次有效超时
  计算绝对 deadline，因此全局 `request-timeout` 和 `@RpcReference.timeoutMillis`
  都会自动生效；
- 请求已有 deadline 时采用更早值，本地配置只能收紧而不能延长上游期限，为以后
  重试和跨服务继续传播保留正确语义；deadline 加法溢出时饱和到 `Long.MAX_VALUE`；
- `RpcRequestHandler` 在服务查找和反射调用前比较 Provider 当前时间；已经过期就
  返回失败 `RpcResponse`，不会访问本地服务表或执行业务方法；
- 新增 deadline 计算、Provider 过期拒绝/未过期执行，以及 JSON、JDK 序列化往返
  测试；README 同步更新请求结构、完整调用链、限制和下一步。

#### 行为变化

一次使用 750ms 超时的调用现在会形成：

```text
Consumer 准备发送请求
        ↓
deadlineEpochMillis = 当前 Epoch 毫秒 + 750
        ↓
RpcRequest 通过网络到达 Provider
        ↓
Provider 当前时间 >= deadline？
        ├── 是：返回失败，不执行服务方法
        └── 否：继续服务查找和 Method.invoke(...)
```

deadline 不改变 Consumer 原来的本地等待保护：Consumer 仍然使用同一个有效超时
等待响应；新增字段只是把这个期限告诉 Provider，减少已经失去价值的服务端执行。

#### 设计原因

- deadline 在 Netty 真正发送前计算，与现有“请求发出后等待响应”的超时语义一致，
  不把首次 ZooKeeper 查询和 TCP 建连时间计算进去；
- 使用绝对时间后，Provider 无需知道 Consumer 何时发出，只需比较当前时间；代价
  是双方系统时钟必须基本同步，生产环境通常需要可靠的时间同步服务；
- Provider 在取得服务对象之前检查，确保过期请求不会触发业务副作用；
- 0 作为“没有 deadline”保留早期手工构造请求和 Socket 教学代码的兼容行为；
- 本步只处理“开始执行前已经过期”，不尝试强制终止任意正在运行的 Java 方法，
  将协作式取消留给下一阶段。

#### 验证方式与结果

- `NettyRpcClientDeadlineTest` 验证默认设置、保留更早期限、收紧更晚期限和加法溢出；
- `RpcRequestHandlerDeadlineTest` 验证过期请求不访问 `ServiceProvider`，未过期请求
  仍能正常反射执行；
- `RpcRequestDeadlineSerializationTest` 验证 JSON、JDK 两种正文格式都能完整保留
  `deadlineEpochMillis`；
- 执行 deadline 相关定向测试，8 个测试全部通过；
- 执行根工程 `mvn test`，core 27 个测试中 26 个通过、容器测试因 Docker 未运行
  跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功；
- 当前限制是绝对 deadline 依赖两端时钟同步，并且无法取消已经开始执行的服务方法；
  下一步建议设计 Provider 执行中的协作式取消。

### 3.28 S5-H-B：Provider 执行中 deadline 协作式检查

- 日期：2026-10-24
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

S5-H-A 只能在服务方法开始前拒绝过期请求。一旦 `Method.invoke(...)` 已经进入耗时
循环或分批任务，Consumer 即使不再等待，Provider 仍会继续执行。本步先提供一个
安全、可审核的协作式机制，让业务代码能够在自己选择的数据一致性边界检查 deadline。

#### 主要修改

- 新增 `RpcCallContext`，使用 `ThreadLocal` 保存当前 Provider 调用的 requestId 和
  `deadlineEpochMillis`；
- `RpcRequestHandler` 只在反射调用服务方法期间绑定上下文，并通过
  `try-with-resources` 在成功、业务异常和反射异常路径统一清理；
- `RpcCallContext.checkDeadline()` 在当前时间到达 deadline 时抛出 `RpcException`，
  该异常沿现有反射异常处理链转换为失败 `RpcResponse`；
- deadline 为 0 的兼容请求不会被执行中检查拒绝；嵌套绑定关闭后会恢复外层上下文；
- 新增上下文边界、无 deadline、嵌套恢复以及请求执行期间可见性的单元测试；
- README 同步补充使用方法、完整调用链、当前限制和下一步。

#### 行为变化与调用链

```text
RpcRequestHandler 确认请求尚未过期
        ↓
绑定 RpcCallContext(requestId, deadline)
        ↓
Method.invoke(...) 执行业务方法
        ↓
耗时业务在安全点调用 checkDeadline()
        ├── 未到期或没有 deadline：继续执行
        └── 已到期：抛出 RpcException，形成失败响应
        ↓
finally 语义关闭 Scope，清理 ThreadLocal
```

普通短时服务不需要调用这个 API。只有确实可能长时间运行、并且能够明确选择安全
停止点的业务，才需要在循环或每批任务之间主动检查。

#### 设计原因

- Java 无法安全地从外部强制终止任意用户代码；`Thread.stop()` 可能在持锁或数据
  更新一半时破坏业务状态，因此本步采用由业务选择安全点的协作式取消；
- 上下文只包围 `Method.invoke(...)`，避免服务查找和反射准备阶段误暴露为业务执行；
- 关闭作用域时恢复旧值而不是简单删除，使同一线程出现嵌套调用时仍能回到外层上下文；
- 在没有上下文时读取 requestId/deadline 会明确抛错，避免把 0 误认为真实请求信息。

#### 验证方式、当前限制与下一步

- `RpcCallContextTest` 验证 deadline 边界、0 deadline、作用域清理和嵌套恢复；
- `RpcRequestHandlerDeadlineTest` 验证业务方法能读取本次 requestId/deadline，返回后
  当前线程不残留上下文；
- 已执行上述定向测试，6 个测试全部通过；
- 执行根工程 `mvn test`，core 31 个测试中 30 个通过、容器测试因 Docker 未运行
  跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功；
- 当前仍是协作式机制：不调用 `checkDeadline()` 的阻塞业务不会自动结束；
- `ThreadLocal` 不会自动传播到业务自行创建的异步线程；
- 下一步建议把业务方法执行从 Netty I/O EventLoop 移到独立业务线程池，再单独设计
  deadline 到达时的任务中断和“响应只完成一次”保护。

### 3.29 S5-H-C-A：Provider Netty I/O 与业务线程隔离

- 日期：2026-10-24
- 对应提交：尚未提交
- 状态：已实现并验证

#### 目标

此前 `NettyRpcServerHandler.channelRead0()` 会直接调用
`RpcRequestHandler.handle()`，所以 `Method.invoke(...)` 实际运行在 Netty worker
EventLoop 上。一个慢服务方法会阻塞该 EventLoop 管理的其他 Channel，网络读取、
响应写回和连接事件都可能被延迟。本步先把业务执行移到 Provider 独立线程池。

#### 主要修改

- 新增 `ProviderBusinessExecutor`，使用固定数量业务线程和 `ArrayBlockingQueue`
  有界队列；默认线程数为可用处理器数量与 2 的较大值，队列容量为 1,024；
- 业务线程统一命名为 `mini-rpc-provider-business-*`，便于日志和线程转储定位；
- `NettyRpcServerHandler` 取得在途许可后只向业务执行器提交任务，随后立即结束本次
  EventLoop 调用；服务查找、deadline 检查和反射调用都在业务线程执行；
- 业务线程得到 `RpcResponse` 后调用 `writeAndFlush()`，Netty 再把真正的编码和
  Socket 写入调度到 Channel 所属 EventLoop；
- 使用有界队列和 `AbortPolicy`。线程与队列都满、或停机期间执行器已经关闭时，
  Handler 返回明确失败响应并正确归还在途计数；
- `NettyRpcServer.stop()` 在请求排空成功后正常关闭业务线程池，排空超时时使用
  `shutdownNow()` 向剩余任务发出中断，然后继续关闭 Netty；
- 新增任务分发、独立线程、队列饱和拒绝以及拒绝后计数归还测试；README 同步更新
  线程模型、完整调用链、限制和下一步。

#### 行为变化与线程调用链

修改前：

```text
Netty worker EventLoop
        ↓
解码 RpcRequest
        ↓
RpcRequestHandler.handle()
        ↓
Method.invoke(...) 可能长时间阻塞 EventLoop
```

修改后：

```text
Netty worker EventLoop
        ↓
解码 RpcRequest 并取得在途许可
        ↓
提交 ProviderBusinessExecutor
        ↓
立即返回处理其他 Channel

Provider 业务线程
        ↓
RpcRequestHandler.handle()
        ↓
绑定 RpcCallContext 并执行 Method.invoke(...)
        ↓
生成 RpcResponse，调用 writeAndFlush()
        ↓
Netty EventLoop 编码并写入 Socket
```

#### 设计原因

- Netty EventLoop 的价值是用少量线程管理大量连接，Handler 必须快速返回；耗时
  用户代码放在 EventLoop 中会扩大单个慢请求的影响范围；
- 不使用 `Executors.newFixedThreadPool()`，因为其默认无界队列会在持续过载时不断
  占用内存；有界队列让 Provider 的最大积压量明确可控；
- 不使用 `CallerRunsPolicy`，因为任务提交者正是 Netty EventLoop，调用者执行会在
  过载时让线程隔离失效；也不静默丢弃，否则 Consumer 只能等待超时；
- 业务线程池由 `NettyRpcServer` 持有，确保它与监听端口、workerGroup 使用同一套
  Provider 生命周期，而不是为每条 Channel 创建线程池；
- `RpcCallContext` 仍由 `RpcRequestHandler` 在反射调用周围绑定，因此迁移后会自然
  绑定到真正执行业务的线程，并继续通过作用域关闭避免线程池复用导致上下文串线。

#### 验证方式、当前限制与下一步

- `NettyRpcServerHandlerDrainTest` 使用可控执行器验证：`channelRead0()` 只提交任务，
  任务真正运行前不会调用 `RpcRequestHandler`，响应写入后在途计数归零；
- 同一测试验证执行器拒绝请求时不会执行业务，并返回失败响应、归还在途计数；
- `ProviderBusinessExecutorTest` 验证任务运行在独立命名线程，以及“1 个工作线程 +
  容量 1 的队列”全部占满后第三个任务会收到 `RejectedExecutionException`；
- 已执行线程隔离、deadline 和调用上下文相关定向测试，12 个测试全部通过；
- 执行根工程 `mvn test`，core 35 个测试中 34 个通过、容器测试因 Docker 未运行
  跳过，Starter 12 个测试全部通过，六个 Maven 模块构建成功；
- 当前线程数和队列容量还是框架内部默认值，尚未接入 `MiniRpcProperties`；
- 当前拒绝响应仍使用通用 `RpcException`，协议还没有“Provider 过载”专用状态码；
- deadline 到达后还不会自动取消已经提交的 `Future`；
- 下一步建议实现 S5-H-C-B：通过 Starter 配置业务线程数和队列容量，并在拒绝日志
  中报告活跃线程数和队列积压量。

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

- 状态：已完成基础版本，详细记录见 3.13 和 3.14；
- 已实现 Spring `BeanPostProcessor`；
- Spring 创建每一个业务 Bean 时，处理器查找 `@RpcReference` 字段；
- 已复用 `RpcClientFactory` 创建代理并注入；
- Spring 容器关闭时会自动关闭 Netty 和 ZooKeeper。

完成后，Consumer 不再创建工厂，也不再调用 `injectReferences()`。

### S3：让 Provider 服务使用真正的 Spring Bean

- 状态：已完成基础版本，详细记录见 3.15；
- 已从 Spring 容器获取 `@RpcService` 对象；
- 示例不再通过反射调用无参构造方法创建实现类；
- 服务实现可以正常使用构造器注入、`@Autowired`、数据库组件等 Spring 能力；
- 容器启动时自动注册服务并启动 Netty，关闭时自动注销和释放资源。

### S4：把示例改造成 Spring Boot 应用

- 状态：已完成，详细记录见 3.16；
- Provider 与 Consumer 示例已引入 Starter；
- 已增加 `application.yml`；
- 已删除示例中手工组装框架组件的代码；
- 已使用真实 ZooKeeper 与 Netty 完成端到端联调；
- 真实端到端联调已在 S5-D 封装为可重复运行的 Testcontainers 测试。

### S5：补齐适合 starter 的可配置和扩展能力

- 状态：进行中，S5-A 全局请求超时、S5-B 缓存与 Watch、S5-C Provider 安全
  发布顺序、S5-D 容器端到端测试、S5-E 单引用超时、S5-F 策略配置、S5-G
  优雅停机、S5-H-A deadline 执行前检查、S5-H-B 执行中协作式检查和 S5-H-C-A
  I/O/业务线程隔离已完成，详细记录见 3.18～3.29；
- 服务发现已有短期本地缓存和 ZooKeeper Watch；
- Provider 已改为 Netty 端口绑定成功后再发布 ZooKeeper 地址；
- 已使用 Testcontainers 自动验证真实 ZooKeeper + Netty 完整链路；
- 全局和单引用请求超时已接入；序列化器和负载均衡策略已支持 Starter 配置；
- 条件装配和清晰的启动失败提示；
- 业务线程池参数配置、自动中断、重试、心跳与更完整的自动化测试；
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

---

## 6. 开发记录续写位置

从 S5-H-C-B（3.30）开始，新的阶段记录续写到根目录
[`DEVELOPMENT_LOG2.md`](DEVELOPMENT_LOG2.md)。本文件保留 3.1～3.29 的历史记录，
不再继续增长；README 仍会随架构、配置和使用方式变化同步更新。
