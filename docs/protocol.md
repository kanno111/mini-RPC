# mini-RPC 自定义协议

mini-RPC 在 TCP 之上定义了固定 20 字节协议头和变长正文。协议头负责描述如何读取
一条消息，正文由配置的 Serializer 把 Java 对象转换成字节。

## 报文结构

```text
┌────────┬──────┬────────┬────────┬──────┬──────────┬──────────┐
│ 魔数4B │版本1B│序列化1B│消息型1B│状态1B│请求ID 8B │正文长度4B│
└────────┴──────┴────────┴────────┴──────┴──────────┴──────────┘
                                                               │
                                                               ▼
                                                   ┌────────────────────┐
                                                   │  正文 body：N 字节 │
                                                   └────────────────────┘
```

协议头布局：

| 字节偏移 | 长度 | 字段 | 当前含义 |
|---|---:|---|---|
| `0～3` | 4 | magic | 固定 `0xCAFEBABE`，用于识别 mini-RPC 报文 |
| `4` | 1 | version | 当前协议版本 `1` |
| `5` | 1 | serializerType | `1` 为 JDK，`2` 为 JSON |
| `6` | 1 | messageType | `1` 请求、`2` 响应、`3` 心跳 |
| `7` | 1 | status | `0` 成功/无状态，`1` 失败 |
| `8～15` | 8 | requestId | 请求、响应或心跳的匹配编号 |
| `16～19` | 4 | bodyLength | 紧随协议头的正文字节数 |

正文长度字段只占 4 字节，用来记录正文长度；正文内容本身不属于 20 字节协议头。
当前单条消息正文必须在 1 字节到 10 MB 之间。

## 为什么需要固定协议头

TCP 只提供连续字节流，不知道“一个 RPC 请求”从哪里开始、在哪里结束。协议头让
接收方能够：

1. 用魔数和版本判断是否是当前协议；
2. 知道正文使用哪个序列化器；
3. 区分请求、响应和心跳；
4. 使用 requestId 匹配调用；
5. 根据 bodyLength 判断完整正文是否已经到达。

## 编码过程

`RpcEncoder` 是 Netty 出站处理器：

```text
RpcRequest / RpcResponse / RpcHeartbeat
        ↓
识别消息类型、状态和 requestId
        ↓
Serializer.serialize(message)
        ↓
校验 requestId 和正文长度
        ↓
依次写入 20 字节协议头
        ↓
写入正文 byte[]
```

编码器只接受三种消息对象。requestId 必须大于 0；心跳还必须是合法 PING/PONG，
并携带正数 Epoch 毫秒时间戳。

## 解码与半包处理

`RpcDecoder` 继承 `ByteToMessageDecoder`，同时负责消息边界和正文反序列化：

```text
累计的 TCP 字节
        ↓
不足 20 字节：等待更多数据
        ↓
标记当前 readerIndex
        ↓
读取并校验协议头
        ↓
正文不足 bodyLength：恢复 readerIndex，等待更多数据
        ↓
读取完整正文
        ↓
按 serializerType 和 messageType 反序列化
        ↓
校验协议头与正文中的 requestId/状态一致
        ↓
向后续 Handler 输出 Java 对象
```

一条消息可能被 TCP 分成多次到达，这就是“半包”。正文尚未收完整时必须恢复到
本条消息的魔数位置，否则下一次解码会把半段正文误当成下一条协议头。

一次网络读取也可能包含多条完整消息，也就是常说的“粘包”。`ByteToMessageDecoder`
会在仍有可读数据时继续调用解码逻辑，按每条消息自己的 bodyLength 逐条输出。

当前解码器会拒绝：

- 错误魔数或不支持的协议版本；
- 未知序列化类型或消息类型；
- 非法响应状态；
- 非正数 requestId；
- 空正文或超过 10 MB 的正文；
- 协议头和正文元数据不一致的消息。

## 请求正文

一次方法调用会被转换成 `RpcRequest`：

```text
helloService.sayHello("Tom")
        ↓
RpcRequest
├── requestId
├── interfaceName
├── methodName
├── parameterTypes
├── parameters
├── group
├── version
└── deadlineEpochMillis
```

Provider 使用 `interfaceName + group + version` 构造 `ServiceKey`，定位服务对象；
再使用方法名和参数类型定位 Java `Method`。

`deadlineEpochMillis` 是 Consumer 发送前计算的绝对截止时间。Provider 可以在调用
业务方法前拒绝已经失去价值的请求。

## 响应正文

`RpcResponse` 至少携带：

```text
requestId + 结果码 + 返回数据或错误信息
```

响应沿用请求 requestId。Consumer 的 `PendingRequestManager` 使用该编号完成正确的
`CompletableFuture`。协议头 status 和响应正文结果码会互相校验，避免元数据矛盾。

## 心跳消息

心跳与业务请求复用同一协议头，但 `messageType` 固定为心跳。`RpcHeartbeat` 正文
包含：

```text
requestId + heartbeatType(PING/PONG) + timestampEpochMillis
```

PING/PONG 链路：

```text
Consumer Channel                         Provider Channel
      │                                        │
      ├──────── RpcHeartbeat(PING) ───────────>│
      │                             心跳 Handler 直接处理
      │                             不进入业务线程池
      │<──────── RpcHeartbeat(PONG) ───────────┤
 心跳 Handler 消费 PONG
 不进入 PendingRequestManager
```

Provider 收到 PING 后在 Channel 所属 EventLoop 中直接写回 PONG，不查找服务表，
不占用 Provider 业务线程池，也不进入业务在途请求计数。Consumer 在普通响应匹配前
消费 PONG，因此不会为心跳创建普通 RPC Future。

`timestampEpochMillis` 用于日志与延迟诊断，不参与连接存活判断。存活判断使用本地
空闲计时，避免要求两端心跳时钟完全同步。

## 空闲检测

Consumer 每条 Provider Channel 都安装独立的 `IdleStateHandler`：

```text
连续 heartbeat-interval 没有写数据
        ↓
WRITER_IDLE → 发送 PING

连续 heartbeat-timeout 没有读到数据
        ↓
READER_IDLE → 关闭 Channel
        ↓
结束相关等待请求并删除连接缓存
        ↓
下一次 RPC 按需建立新连接
```

普通请求会重置写空闲时间，普通响应和 PONG 都会重置读空闲时间。业务流量活跃时
不会额外发送无意义心跳。

Provider 也为每条客户端 Channel 检测读空闲。普通请求和 PING 都能证明客户端仍然
存活；超过 `provider-client-idle-timeout` 没有入站数据时，只关闭这条静默连接，
不会关闭监听端口或其他客户端。

## 序列化协商的当前边界

协议头明确携带 serializerType，所以请求和响应可以由接收端自动选择解码器。但是
当前类型表只有：

```text
1 → JdkSerializer
2 → JsonSerializer
```

这还不是可动态扩展的协议协商机制。新增第三种序列化方式需要同时修改公共协议常量、
编码器、解码器以及两端部署，后续才会通过最小 SPI 进一步解耦。
