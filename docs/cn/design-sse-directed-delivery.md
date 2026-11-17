# SSE 定向投递（`sys.sse.delivery`）— Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft / 待用户确认（v0.2 已处理 AI 评审 P0/P1） |
| 文档版本 | v0.2 |
| 创建日期 | 2026-11-17 |
| 作者 | core-ai |
| 范围 | server 会话 SSE（`/api/sessions/events`、`/api/sessions/messages/stream`）的跨 Pod 投递；A2A 流不在本次范围 |
| 设计依据 | `docs/cn/design-dataset-postgresql-migration.md` §7.6、§7.7；现有 SSE / RPC 实现 |
| 上游阶段 | Stage 3 第 4 步（第 1–3 步已上线 dev/UAT/prod） |

## 0. 原始需求与文档治理

### 0.1 原始需求（原文保留）

> 现在每个事件都会广播到所有 pod（`coreai:sse:*`）。改为：
>
> 1. **owner pod 持有事件源**：`SessionEventHub`（每个会话一个），负责分配递增的 seq，并保留 replay buffer。现在 buffer 放在订阅方的 `SessionChannelService` 里，这正是 d06554b0“owner 是自己就不发 Redis”那次尝试失败的原因：用户重连到另一个 pod 时拿不到之前的事件；
> 2. **订阅方 pod 拉取**：用户的 SSE 连接落在 pod B 上，而会话的 owner 是 pod A 时，B 向 A 发起内部 HTTP 流式请求 `GET /_internal/sessions/{id}/events?after={seq}`，再转发给浏览器；断线后带上最后收到的 seq 重连，A 从 buffer 里补发；
> 3. **owner 变更**：通过 NOTIFY 发一个低频信号（`owner_changed`，只在归属变更时发送），同时每 5 秒检查一次 `session_ownership` 作为兜底；订阅方发现 owner 变了，就转去连接新的 owner；
> 4. **灰度开关**：`sys.sse.delivery=redis|directed`，两种模式并存，按环境逐步切换；
> 5. **待确认**：`/messages/stream` 在服务端主动关闭时前端的行为（会不会自动重连、会不会带上 seq），这决定第 2 步的断线续传能否完全对前端透明。

### 0.2 需求编号

| ID | 要求 | 性质 |
|---|---|---|
| S1 | owner Pod 持有每会话的 `SessionEventHub`，负责 seq 与 replay buffer | 必须 |
| S2 | 非 owner Pod 通过内部 HTTP 流式接口 `after={seq}` 拉取并转发给浏览器 | 必须 |
| S3 | owner 变更可被发现并转向新 owner；不能依赖 Redis 广播 | 必须 |
| S4 | `sys.sse.delivery=redis\|directed` 可切换，默认 redis，可回滚 | 必须 |
| S5 | 公开 SSE API、前端协议和 `/messages/stream` 行为不变 | 必须 |
| S6 | 内部流式接口只接受 Pod IP + 内部 token，外网返回 404 | 必须 |
| S7 | 不新增数据库表；relay 依赖已有的 `messaging.pods` / `session_ownership` | 必须 |
| S8 | 慢订阅方不能阻塞 owner 的事件生产；溢出时关闭并重连，不拖垮回合 | 必须 |
| S9 | 按 dev → UAT → prod 顺序验证，保留可逆配置 | 必须 |

### 0.3 文档治理

- 0.1 节为上游设计原文，不改写；本文的补充决策单独记录。
- Review 使用稳定编号；AI 评审不等于用户批准，未确认前不写 Approved。
- 本文只覆盖会话 SSE 定向投递；命令队列、A2A 流、Redis 实例下线不在本次实现范围。

## 1. 现状与问题

### 1.1 当前链路

```text
agent event (owner pod A)
  -> SseEventBridge
  -> EventPublisher.publish()            // Redis PUBLISH coreai:sse:<sessionId>
  -> 所有 Pod 的 EventSubscriber          // PSUBSCRIBE coreai:sse:*
  -> SessionChannelService.send()
       -> 没有本地 stateMap 的 Pod 直接丢弃
       -> 有本地 SSE channel 的 Pod 写入本地 replay buffer 并发送
```

关键实现：

- `EventPublisher`：`core-ai-server/src/main/java/ai/core/server/messaging/EventPublisher.java:19`
- `EventSubscriber`：`core-ai-server/src/main/java/ai/core/server/messaging/EventSubscriber.java:62`
- `SessionChannelService.connect/send`：`core-ai-server/src/main/java/ai/core/server/web/sse/SessionChannelService.java:136`、`:147`
- 公开 SSE 入口：`AgentMessageStreamChannelListener.java:69`、`AgentSessionChannelListener.java:43`

### 1.2 问题

1. **每条事件广播到所有 Pod**：文本 chunk 是高频事件，Pod 数增加时 Redis 与每个 Pod 的 CPU/网络都线性增长。
2. **replay buffer 在订阅方 Pod**：用户重连到另一个 Pod 时，该 Pod 没有上一个 Pod 的 buffer，当前回合的中间事件丢失；`TURN_COMPLETE.output` 只能兜底最终文本，工具/计划事件无法恢复。
3. **没有 seq**：无法表达“从第 N 条之后继续”，只能整段重放。
4. **`hasSession` 是死代码**：注释说“本地有会话就绕过 Redis”，但没有任何调用点；当前所有事件仍走 Redis。
5. **Redis 仍是 SSE 的硬依赖**：`StartupDependencyCheck` 探测 Redis，`EventSubscriber` 长期 PSUBSCRIBE。

### 1.3 现状中必须保留的语义

- 新的 SSE 连接会重放当前回合 buffer；前端在收到重放的 `RUNNING` 时会清空当前 agent 气泡再重建（`Chat.tsx:1107`），因此整段重放是安全的，不需要前端改协议。
- `SessionChannelService` 的 buffer 语义：
  - `RUNNING` 清空 buffer；
  - `TURN_COMPLETE` / `ERROR` 终止并保留终止事件；
  - 终止后的迟到活动事件丢弃；
  - 连续文本/推理 chunk 合并，按 8 MiB / 50k 事件限制；
  - `TASK_STATUS` 只实时发送，不进入 replay buffer。
- `/messages/stream` 的单请求“先注册 channel 再发命令”顺序不能变（`AgentMessageStreamChannelListener.java:64`），否则会重新引入首事件丢失的竞态。

## 2. 目标与非目标

### 2.1 目标

- 会话事件源和 replay buffer 收敛到 owner Pod；任何 Pod 的 SSE 连接都能从 owner 拿到同一份回放。
- 事件仍按会话有序；订阅方可以用 `after` 续传。
- owner 变化时订阅方能跟随；owner 不可达时不阻塞回合，由前端 watchdog/history 兜底。
- 保留 `sys.sse.delivery=redis|directed`，默认行为与今天完全一致。
- 为最终删除 Redis SSE 广播打基础。

### 2.2 非目标

- 不迁移 A2A `A2AEventRelay`（仍是 Redis pub/sub，单独切片）。
- 不迁移命令队列（下一步）。
- 不要求前端发送 `Last-Event-ID`；本次保持前端零改动。
- 不新增 SSE 事件类型、不改公开 JSON 字段。
- 不在本次删除 Redis 双写/订阅；那是全量 relay 稳定后的清理版本。

## 3. 设计

### 3.1 角色

| 组件 | 位置 | 职责 |
|---|---|---|
| `SessionEventHub` | owner Pod | 每会话一个；分配 seq、维护 replay buffer、向本地/内部订阅者 fan-out |
| `SessionEventHubRegistry` | 每个 Pod | 本 Pod 的 hub 映射；懒创建、关闭时移除、统计 |
| `SessionChannelService` | 每个 Pod | 本地浏览器 channel 注册；选择“本地 hub”或“跨 Pod relay” |
| `EventPublisher` | 每个 Pod | 事件入口；redis 模式走 Redis，directed 模式走本地 hub（过渡期同时写 Redis） |
| `EventSubscriber` | 每个 Pod | redis / directed 过渡期的 Redis 兜底；directed relay 已连接时跳过该会话 |
| `InternalSessionEventChannelListener` | owner Pod | `GET /_internal/sessions/events` 内部 SSE 端点 |
| `SessionRelayClient` | 订阅方 Pod | 连接 owner 的内部 SSE，解析 `id`/`data`，转发到本地 channel |

装配顺序（对应评审 P0-1）：

- `SseTransportModule` **保持在 `ServerApp` 当前位置**（它提供 `ChannelService` / `SseEndpointRegistry`，`GatewayModule` 在其后立即使用，不能后移）。
- `SessionEventHubRegistry`、`EventPublisher`、`SessionChannelService` 在 `MessagingInfrastructureModule` 内创建并绑定；该模块已有 `SessionOwnershipRegistry`、`PodRegistry`（可能为 null）和 `sys.sse.delivery`。
- `SseTransportModule` 只移除 `SessionChannelService` 的绑定，保留 `ChannelService` 与 `SseEndpointRegistry`；`SessionModule` 构造 `SseAuthInterceptor` 时不需要新增 `PodRegistry`/token 依赖，内部路径由 preflight 负责。

### 3.2 `SessionEventHub`

每个会话一个，只在 owner Pod 存在。

```java
public final class SessionEventHub {
    long publish(SseBaseEvent event);                    // 返回分配到的 seq
    Subscription subscribe(long afterSeq, EventSink sink); // 先回放 seq > afterSeq，再接 live
    SessionStatus status();
    void close();

    interface EventSink {
        boolean onEvent(long seq, SseBaseEvent event);   // false = 订阅方已关闭
    }
}
```

- `seq` 从 1 开始，单调递增；每个事件（包括合并前的每个 chunk）分配一个 seq。
- buffer 条目保存 `(firstSeq, lastSeq, eventSnapshot)`；连续 chunk 合并时**不修改已入 buffer/已发送的事件对象**，而是用拼接后的内容创建一个新快照替换条目，并更新 `lastSeq`。这样 `Channel.send` 的异步序列化不会与合并发生数据竞争。
- `subscribe(afterSeq)` 的语义：
  - `afterSeq >= lastSeq`：只接 live，不回放；
  - `afterSeq` 恰好等于某个 buffer 条目的 `lastSeq`，且下一条从 `afterSeq + 1` 开始：精确回放后续条目；
  - 其他情况（`afterSeq > 0` 但落在 `[firstSeq, lastSeq]` 的合并区间内，或 buffer 已淘汰该 seq）：先发送一个合成的 `SseStatusChangeEvent(RUNNING)` 重置前端气泡，再从当前回合的 `RUNNING` 边界（或 buffer 起点）回放。**绝不把合并后的整段文本直接追加到客户端已渲染的部分上**，避免 `"hello" + "hello!"` 重复。
  - `afterSeq == 0` 且 buffer 为空或只含终态事件：不补发合成 RUNNING，避免把空闲/已完成会话误标成运行中；只在确实要回放当前回合时才注入 RUNNING。
- `publish` 在 hub 锁内只做：分配 seq、更新 buffer、**原子地把回放切片和订阅者队列注册好**；锁外把事件放入每个订阅者的有界队列，由订阅者自己的虚拟线程调用 `sink.onEvent`。慢客户端不会持有 hub 锁，也不会阻塞回合线程。
- 每个订阅者有独立队列（容量按 buffer 上限的引用数设置，不复制事件字节）和 `lastSentSeq`；队列满或 `sink` 返回 false 时关闭该订阅，由 relay 重连后回放。
- 回放上限 `MAX_REPLAY_EVENTS = 1000`（与 core-ng `SseQueueLimits` 默认队列深度对齐）：条目超过上限时只回放最新 1000 条，并在开头注入 RUNNING；记录一次 WARN。工具事件可能被截断，由 `TURN_COMPLETE.output`/history 兜底，但不允许回放把刚建立的 SSE channel 瞬间打满并 shutdown。
- buffer 上限、RUNNING/terminal/迟到事件语义从 `SessionChannelService` 迁移；文本/推理 chunk 仍合并，但合并只影响 buffer 表示，live 事件仍逐条发送。

### 3.3 本地直连与跨 Pod relay

`SessionChannelService.connect(channel, sessionId)`：

1. 注册本地 channel（保持 `ChannelService` 的“同一会话只保留最新连接”语义）。
2. 把 owner 解析、relay 建立/重连交给该会话的虚拟线程；`onConnect` 本身**不做同步轮询/HTTP/PG 查询**，否则会阻塞 `AgentMessageStreamChannelListener` 后面的 `commandPublisher.publish`。
3. **owner == 本 Pod**：`SessionEventHubRegistry.getOrCreate(sessionId).subscribe(0, ...)`，事件直接写入本地 channel。
4. **owner == 其他 Pod**：启动 `SessionRelayClient`，向 owner 的内部 SSE 端点建立流；事件写入本地 channel。
5. **owner == null**：异步快速轮询（见 §3.6）；不把“无 owner”误判为“会话已关闭”。

`SessionChannelService.close/closeIfCurrent`：

- 关闭本地 channel；停止对应 relay（relay 不跨浏览器连接空转）。
- owner Pod 上的 `close(sessionId)` 同时 `SessionEventHubRegistry.remove(sessionId)`；先让 `session.close()` 的终止事件 flush，再移除 hub（沿用 `AgentSessionManager.cleanupRuntime` 的顺序）。

### 3.4 内部流式协议

**路径**：`GET /_internal/sessions/events?session_id={id}&after={seq}`

- core-ng 的 SSE 配置要求静态路径（`PatchedServerSentEventConfig.java:39` 明确拒绝 `/:`），所以 sessionId 用 query 参数，不用 path 变量。
- 仅在 `sys.sse.delivery=directed` 时注册；redis 模式下该路径不存在，外部/旧 Pod 自然得到 404。
- 响应是标准 SSE：
  - `id: {seq}`；
  - `data: {SseBaseEvent JSON}`；
  - core-ng 每 15 秒发送 keepalive（单行 `:`，见 `PatchedServerSentEventContextImpl.keepAlive()`），relay 客户端必须**按行读取**，任何一行（包括 `:` 注释）都刷新 45 秒活跃计时；不能只按 `\n\n` 分帧后再计时。
- `after` 缺省为 0；owner 先按 §3.2 的安全回放规则回放，再持续发送 live 事件。
- 状态码：
  - `200`：流已建立；
  - `401`：token 缺失/错误；
  - `404`：请求 host 不是 Pod IP（外网/网关），或该 Pod 未启用 relay；
  - `409`：该 Pod 不是当前 owner（owner 刚变化），订阅方重新解析 owner 后重试。
- **pre-flush 校验（P0）**：当前 `PatchedServerSentEventHandler.handleRequest()` 在 `connect()` 之前就设置 `Content-Type` 并 `flush()`，HTTP 200 已经提交；拦截器/监听器随后抛出的异常只会变成 `200 + event:error`，无法返回真实 404/401/409。必须在 SSE handler 增加一个 **preflight 钩子**：
  - `PatchedServerSentEventConfig.preflight(method, path, validator)`；
  - `handleRequest()` 先在 IO 线程处理 CORS/OPTIONS，然后立即 `exchange.dispatch(...)` 到工作/虚拟线程；**在 dispatch 之后**执行 validator（host、token、`isOwner` 可能触发 PG/Redis IO，不能阻塞 Undertow IO 线程）；
  - validator 失败时直接写对应 HTTP 状态和 JSON body 并 `endExchange()`；通过后才设置 `text/event-stream`、`flush()` 并进入现有 `connect()`；
  - `SseAuthInterceptor` 对内部路径只做**精确路径放行**，不再注入 `PodRegistry`/token；host/token/owner 由 preflight 负责。公开路径保持原逻辑。
- 额外提供低频的 `POST /_internal/sessions/events`：它是**普通 HTTP 路由**（`http().route(POST, ...)`），不是 SSE，不能复用 SSE preflight；用 `InternalRpcController` 风格的 host + token 校验：
  - body 为 `{ "session_id": "...", "event": <SseBaseEvent JSON> }`；
  - 仅用于 directed 模式下“事件生产者不在 owner Pod”的兜底（命令错误、取消确认等），不对每个 chunk 调用；
  - owner 收到后 `hub.publish(event)`，失败时发布方退回 Redis。
- 不返回用户数据以外的内容；不记录逐条事件日志。

**认证与隐藏**：

- 复用 `sys.internal.token` / `sys.internal.token.previous`，常量时间比较（与 `InternalRpcController.java:92` 一致）。
- 内部 GET 的 host/token/owner 校验全部由 §3.4 的 preflight 完成；`SseAuthInterceptor` 只对 `/_internal/sessions/events` 做精确路径放行，不再重复校验。POST 是普通 HTTP 路由，使用与 `InternalRpcController.java:39/92` 相同的 host + token 校验。外部 host 一律 404，不暴露路由存在。
- 其他 SSE 端点仍走原有用户认证/RBAC，不受影响。

### 3.5 事件发布路径

`EventPublisher` 增加模式：

- **redis**（默认）：与今天完全一致，`PUBLISH coreai:sse:<sessionId>`。
- **directed**：
  1. 取本 Pod 的 hub；没有则用 `SessionOwnershipRegistry.isOwner(sessionId)` 判断，是 owner 就懒创建。
  2. 有 hub：`hub.publish(event)`，同时仍 `PUBLISH` 到 Redis（过渡期双写，见 §4）。
  3. 没有 hub 且不是 owner：通过 `POST /_internal/sessions/events` 把事件转给 owner；POST 失败（owner 不可达/旧版本）再退回 Redis。该路径只用于低频边角事件（命令错误、取消确认），不对 chunk 调用，避免每事件一次 HTTP。

`EventSubscriber` 在 directed 模式下**继续运行**，作为混合版本/relay 失败时的兜底：

- `EventSubscriber` 在反序列化**之前**先按 sessionId 判断该会话是否有健康 relay；有则直接跳过（避免双写期仍为每个 chunk 做 `JSON.fromJSON`），没有才继续走原有解析与投递。为此 `EventSubscriber` 需要先从 channel 名拿到 sessionId，再做过滤；`SessionChannelService.hasHealthyRelay(sessionId)` 是低成本的本地 map 查询。
- `SessionChannelService.onBroadcast(sessionId, event)`：
  - 该会话有健康 relay → 忽略（避免与 relay 重复）；
  - relay 正在建立（pending）→ 暂存到本地 pending buffer，不直接下发；
  - relay 建立失败 → flush pending buffer 到本地 channel，并继续走 broadcast 投递（含 buffer）；
  - relay 建立成功 → 丢弃 pending buffer，由 hub 的 `after=0` 回放接管，避免重复。
- relay 成功建立后清理该会话的 fallback buffer，避免之后 relay 回放与 Redis 兜底重复。

这样在滚动切换 `redis → directed` 的窗口内：

- redis Pod 仍能从 Redis 收到 directed Pod 的事件；
- directed Pod 对 redis owner（没有内部端点，404）自动退回 Redis；
- 不会因为“一半 Pod 新、一半 Pod 旧”而丢事件。

### 3.6 owner 解析与变更

- 连接时解析一次 owner。
- **新会话的 owner 可能尚未产生**：`AgentMessageStreamChannelListener` 先 `connect` 再 `commandPublisher.publish`，而 `CommandPublisher` 的 `claimLocally` 要等 publish 时才 claim。`connect` 只注册 channel 并启动异步 resolver；resolver 发现 owner 为 null 时用 100ms 间隔快速轮询最多 2 秒（覆盖本地 claim 的窗口），owner 变为本 Pod 后直接订阅本地 hub；仍为 null 才进入常规 5 秒 watchdog。resolver 不阻塞 `onConnect`/首条命令，owner hub 的 buffer 保证轮询期间产生的事件不会丢。
- 每个活跃 relay 由 `SessionChannelService` 的 5 秒 watchdog 检查 `SessionOwnershipRegistry.getOwner(sessionId)`：
  - owner 变化 → 关闭当前内部流，按新 owner 重连，`after=0`（新 owner 的 seq 是新 epoch，不能沿用旧 seq）。
  - owner 仍相同 → 不动；relay 自身断线时从 `lastSeq + 1` 续传。
  - owner 为 null → 最多等 30 秒（与 `PgOwnershipStore.LIVENESS_SECONDS` 一致）；仍为 null 则关闭本地 channel，让前端 watchdog/history 接管。
- 不做 LISTEN/NOTIFY：core-ng `Database` 没有 LISTEN 抽象，额外维护裸 JDBC 连接的收益不足以抵消运维复杂度；5 秒轮询已满足 30–60 秒接管目标。
- owner 变化后新 hub 的 buffer 可能不完整：前端收到重放的 `RUNNING` 会清空气泡重建；最终 `TURN_COMPLETE.output` 和 history 同步兜底。这是设计接受的“可能丢中间事件、不丢最终结果”。

### 3.7 状态与统计（对应评审 P0-4）

- `AgentSessionWebServiceImpl.status()` 仍由 `TurnStatusResolver`（跨 Pod 的 turn registry）主导；`SessionChannelService.status(sessionId)` 在 directed 模式下返回本地 relay 收到的 `lastStatus`（最近一条 STATUS_CHANGE/TURN_COMPLETE/ERROR），没有本地 relay 时返回 `IDLE`。
- 这不比 broadcast 更差：redis 模式下，未承载该 SSE channel 的 Pod 本来也只有 `IDLE`；ERROR 的权威来源是 SSE 事件与 history。本切片不新增跨 Pod `status` RPC，避免为 watchdog 引入额外每几秒一次的 Pod 间调用。
- `RuntimeStatsService` 在 directed 模式下汇总本 Pod `SessionEventHubRegistry` 的 buffer bytes/events/dropped；非 owner Pod 的 hub 统计为 0，这是预期。全局统计应在后续 observability slice 中按 Pod 聚合，不在本次范围。
- 若后续发现 ERROR 状态丢失影响 watchdog，再单独设计 `GET /_internal/sessions/status` 或把终端状态写入 `TurnStateRegistry`；不在本次实现。

### 3.8 公开 SSE 语义

- `/api/sessions/events` 与 `/api/sessions/messages/stream` 的 URL、方法、认证、JSON 字段不变。
- 前端仍不发送 `Last-Event-ID`；每次新连接从 `after=0` 开始，由 owner 回放当前回合 buffer。这与今天“新 Pod 本地 buffer 为空”相比是增强，不是协议变化。
- `AgentMessageStreamChannelListener` 仍先 `connect` 再 `commandPublisher.publish`；relay 的建立是异步的，但 `connect` 会先把本地 channel 注册好，owner 的第一条事件不会因为 relay 尚未连上而丢失（relay 连接后从 0 回放）。
- `/messages/stream` 在服务端不主动关闭的行为保持不变；relay 的生命周期跟随浏览器连接，不额外引入关闭时机。

### 3.9 生命周期与清理

- hub 懒创建：owner 首次发布事件或首次有 SSE 连接时创建。
- hub 移除：`SessionChannelService.close(sessionId)`（owner 清理会话时）调用 `hubRegistry.remove`；buffer 随之释放。
- relay 移除：本地 channel 关闭即停止；不保留空闲 relay。
- Pod 停机：`SessionChannelService` 关闭所有 relay 和 watchdog；owner 上的 hub 随进程退出。
- 会话被 idle cleanup 关闭时，订阅方 relay 会收到 404/连接关闭；按 §3.6 重试，owner 仍为 null 后关闭本地 channel。

### 3.10 可观测性

- 日志（INFO，低频）：
  - relay 建立/关闭、owner 变化、从 relay 退回 Redis、owner 30 秒不可达；
  - 不打印逐条事件或文本内容。
- 指标（沿用 `RuntimeStatsService` 风格）：
  - 本 Pod 活跃 relay 数；
  - relay 连接失败/重连次数；
  - hub buffer bytes/events、dropped total（owner 视角）；
  - Redis fallback 投递次数。

## 4. 开关、部署与回滚

### 4.1 开关

| 属性 | 值 | 默认 | 说明 |
|---|---|---|---|
| `sys.sse.delivery` | `redis` / `directed` | `broadcast` | `relay` 启用 hub + 内部流；过渡期仍双写 Redis |
| `sys.internal.token` | 字符串 | 无 | relay 必填；缺失则启动失败 |
| `sys.internal.token.previous` | 字符串 | 空 | token 轮换期接收端兼容 |

`relay` 的前置条件：

- `PodRegistry` 必须存在（即 `sys.rpc.transport=http` 或 `sys.lease.backend=postgres`）；否则没有 Pod 地址，启动失败并给出明确错误。
- `sys.internal.token` 非空。
- 不要求 `sys.lease.backend=postgres`；UAT/prod 当前是 `rpc=http` + `lease=redis`，可用。

### 4.2 部署顺序

1. 发布包含 relay 代码、但 `sys.sse.delivery` 未设置的版本（默认 broadcast）：行为与今天完全一致。
2. 观察一轮发布，确认无启动/binding 回归。
3. 将 `SYS_SSE_DELIVERY=directed` 写入环境配置并滚动更新。
   - 滚动期间新旧 Pod 混跑：directed Pod 双写 Redis，redis Pod 继续消费；directed Pod 对 redis owner 自动退回 Redis。
4. 全量 relay 后观察至少一周（UAT）/ 一周（prod，按上游阶段策略），再进入“清理版本”去掉 Redis 双写与 `EventSubscriber`。

### 4.3 回滚

- 将 `SYS_SSE_DELIVERY` 改回 `broadcast`（或删除）并滚动更新；代码无需回退，无 Flyway。
- 回滚期间 directed Pod 仍双写 Redis，redis Pod 能收到；redis owner 没有内部端点，directed Pod 自动退回 Redis。因此回滚窗口不丢事件。
- 清理版本发布后，回滚路径变为“先恢复 `sys.sse.redis-fallback=true` 的过渡版本，再切 broadcast”；因此清理版本必须单独发布、单独观察，不与本切片合并。

### 4.4 数据库

- 不新增表；不新增 Flyway。
- hub/seq 是 owner Pod 内存状态；owner 变化时 seq epoch 重置是设计行为，不做持久化（见 D5）。

## 5. 失败处理

| 场景 | 行为 | 兜底 |
|---|---|---|
| 内部端点 404（owner 是旧 Pod / 未启用 relay） | 标记该会话 relay 不可用，走 Redis fallback | 旧 Pod 仍广播；前端无感 |
| 内部端点 401（token 轮换/配置错误） | 记录 WARN，退回 Redis；周期性重试 relay | 修复 token 后自动恢复 |
| 内部端点 409（owner 刚变化） | 重新解析 owner，`after=0` 重连 | 5 秒 watchdog 也会纠正 |
| 外网请求内部端点 | preflight 在 200 之前返回 404 | 不会出现 200 + `event:error` 暴露路由 |
| 非 owner Pod 产生错误/取消事件 | `EventPublisher` POST 给 owner；失败退回 Redis | relay 健康时也能收到；低频路径 |
| 新会话 owner 尚未产生 | `connect` 100ms 快速轮询最多 2 秒 | 本地 claim 后立即订阅 hub，不白等 5 秒 |
| owner Pod 被杀 | relay 连接断开；watchdog 发现 owner 为 null 或变化 | 30 秒内等待新 owner；超时关闭本地 channel，前端 `recoverTurn`/history 同步 |
| relay 读超时（45 秒无任何行） | 关闭流，按安全回放规则重连（owner 未变）或 `after=0`（owner 变） | 重连回放；keepalive 单行 `:` 也刷新计时 |
| 慢订阅方/队列满 | 订阅者线程消费队列；`Channel.send` 返回 false 或关闭 | 移除订阅；relay 重连后从 buffer 回放；不阻塞 owner 回合 |
| hub buffer 溢出 | 沿用现有策略丢弃最旧事件并 WARN | `TURN_COMPLETE.output`/history 兜底 |
| relay 建立前事件已产生 | `connect` 先注册本地 channel；relay 回放 | 不丢首事件；pending buffer 防重复 |
| 合并 chunk 与 `after` 重叠 | 回退为“合成 RUNNING + 当前回合安全回放” | 前端清空气泡重建，不重复拼接文本 |
| owner 变更导致 seq 重置 | 订阅方检测 owner 变化后 `after=0` | 新 hub 回放 + 前端 RUNNING 清空气泡 |
| Redis 不可用 | broadcast 模式与今天一致会受影响；directed 模式下本地 hub 仍可服务 owner 本地连接 | relay 不依赖 Redis；双写失败只记录，不影响 hub |

## 6. 安全

- 内部端点只接受 Pod IP host；外部 host 在 **preflight**（200 之前）返回 404，不暴露路由。
- `X-Internal-Token` 常量时间比较；接受 `previous` token 以支持轮换。
- 内部端点不经过用户认证/RBAC，但只有持有 token 且能直连 Pod IP 的调用方能建立流。
- 公开 SSE 端点的认证/RBAC 不变；`SseAuthInterceptor` 只对精确的内部路径做 bypass，其余路径继续走原有逻辑。
- 内部 GET 走 preflight；POST 走普通 HTTP host + token 校验。POST 只接受低频事件，不能作为 chunk 旁路。
- 日志不记录 token、不记录事件内容。

## 7. 测试与验收

### 7.1 单元测试

- `SessionEventHub`：
  - seq 单调递增；`subscribe(after)` 在安全边界精确回放；
  - 连续 chunk 合并是 copy-on-merge，已发送对象不被修改；`after` 落入合并区间时回退为 RUNNING + 安全回放，不重复拼接；
  - `RUNNING` 清空 buffer；`TURN_COMPLETE`/`ERROR` 终止；终止后迟到事件丢弃；`TASK_STATUS` 分配 seq、live 发送但不入 buffer；
  - 订阅者队列关闭/`sink=false` 时移除；慢订阅者不持有 hub 锁；
  - buffer 字节/事件上限与 dropped 统计。
- `SessionRelayClient` SSE 解析：
  - 多行 `data:`、`id:`、单行 `:` keepalive、分片到达；按行刷新 45 秒计时；
  - 断线重连安全边界；owner 变化 `after=0`；
  - 45 秒无任何行超时。
- `EventPublisher`：
  - broadcast 只 Redis；
  - relay 有 hub 时 hub + Redis（过渡期），无 hub 时 POST owner，POST 失败再 Redis；
  - 不因 Redis 异常影响 hub 发布。
- preflight / `SseAuthInterceptor`：
  - 外网 host → 404；Pod host + 错误 token → 401；Pod host + 正确 token 且 owner → 200；
  - 非 owner → 409；
  - preflight 在 `exchange.dispatch` 之后执行，不阻塞 Undertow IO 线程；
  - POST 走普通 HTTP host/token 校验，不进入 SSE handler；
  - 公开 `/api/sessions/events` 仍要求用户认证。
- `SessionChannelService`：
  - owner 本地订阅 hub；非 owner 启动 relay；
  - owner 为 null 时 100ms 快速轮询，claim 本 Pod 后立即本地订阅；
  - relay 失败退回 Redis；relay 成功后忽略重复 Redis 事件；
  - 旧 channel 的异步 onClose 不得误杀新 relay（generation/channel 绑定）；
  - `close` 停止 relay、移除 hub。

### 7.2 dev E2E

前置：`SYS_RPC_TRANSPORT=http`（已有 PodRegistry）、`SYS_SSE_DELIVERY=directed`、2 个以上 Pod。

1. **跨 Pod 基本流**：SSE 连接固定在 Pod B，会话 owner 在 Pod A；发一条消息，B 收到 text/tool/TURN_COMPLETE，顺序与 A 本地一致。
2. **重连回放**：回合进行中断开 B 的连接，改连 Pod C；C 从 owner 回放当前回合，前端气泡不重复（RUNNING 重置）。
3. **owner 变更**：删除 owner A，等待会话在 B 重建；SSE 在 30–60 秒内恢复，TURN_COMPLETE/最终文本不丢。
4. **慢订阅方**：暂停读取 relay 响应，owner 回合继续完成；relay 被关闭/重连后可回放，不影响 turn。
5. **混合版本/回退**：保留一个 redis Pod，directed Pod 对 redis owner 自动走 Redis；把配置切回 broadcast，SSE 仍可用。
6. **内部端点**：外网 `/_internal/sessions/events` 返回 404；错误 token 401；非 owner 409；合法请求 200。
7. **日志**：无逐条事件日志、无 token、无未处理异常；relay 建立/切换/回退有 INFO。

### 7.3 UAT / prod

- 按 §4.2 顺序：broadcast 发布 → 观察 → relay 滚动 → 观察。
- 验证项与 dev 相同；prod 操作前确认无维护窗口、可回滚。
- 记录每个环境的镜像 tag、Pod 数、配置、E2E 结果、回滚命令。

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 事件生产者不在 owner Pod | directed 模式无 hub，事件只进 Redis；relay 订阅方会忽略 Redis，可能丢事件 | 过渡期 `onBroadcast` 仅在 relay 健康时忽略；relay 失败自动回退；低频事件 POST owner；E2E 覆盖命令错误、A2A 相关路径 |
| owner 变化丢失中间 chunk | 工具/计划事件不完整 | `TURN_COMPLETE.output`/history 兜底；前端 RUNNING 重置；接受“最终结果不丢” |
| 5 秒轮询增加 DB 读 | owner 变更检测延迟/DB 压力 | 仅活跃 relay 轮询；新会话 100ms 快速轮询最多 2 秒；`getOwner` 走 `messaging.pods` 索引；必要时批量查询 |
| relay 慢客户端拖住 owner | 回合延迟 | hub 锁内不调用 `Channel.send`；每订阅者独立队列/线程；队列满关闭订阅 |
| 模块顺序/binding 变化导致启动失败 | 发布失败 | `SseTransportModule` 保持原位；`SessionChannelService`/hub 在 `MessagingInfrastructureModule` 绑定；`PodRegistry` 在 broadcast 下可空；启动测试覆盖 relay 前置校验 |
| 内部端点被网关暴露 | 安全风险 | preflight 在 200 之前校验 host/token/owner；外网 404；E2E 显式验证 |
| preflight 扩展影响公开 SSE | 全站 SSE 异常 | 只在配置了 preflight 的内部路径执行；公开路径零改动；补 handler 测试 |
| 双写期间 Redis 仍是瓶颈 | 迁移收益延迟 | 明确这是过渡态；全量 relay 后单独清理版本去掉双写/订阅 |
| 合并 chunk + `after` 续传重复 | UI 重复文本 | copy-on-merge；`after` 落在合并区间时合成 RUNNING + 安全回放 |
| seq 重置导致客户端重复/丢失 | UI 重复文本 | 前端 RUNNING 重置；owner 变化 `after=0`；不沿用旧 seq |

## 9. 实施拆分

1. **Hub 与模式骨架**（默认 broadcast，行为不变）
   - `SseDeliveryMode`、`SessionEventHub`、`SessionEventHubRegistry`；
   - 从 `SessionChannelService` 抽离 buffer 语义，补单元测试；
   - `EventPublisher` 支持 broadcast/relay（relay 暂不启用）；
   - 装配调整：`MessagingInfrastructureModule` 读取 `sys.sse.delivery`、创建并绑定 `SessionEventHubRegistry`、构造 `EventPublisher` 和 `SessionChannelService`；`SseTransportModule` **保持原位**，只保留 `ChannelService` / `SseEndpointRegistry`；`SessionChannelService` 在 broadcast 下允许 `PodRegistry` 为 null。
2. **内部端点与 relay 客户端**
   - 给 `PatchedServerSentEventHandler` 增加 preflight 钩子（在 200/flush 之前校验 host/token/owner）；
   - 注册 `GET /_internal/sessions/events` 与低频 `POST /_internal/sessions/events`（仅 relay）；
   - `SseAuthInterceptor` 内部路径 bypass；
   - `SessionRelayClient` + 按行 SSE 解析 + 5 秒 watchdog + 100ms 快速 owner 轮询 + Redis fallback；
   - `SessionChannelService` 本地/relay/pending 分支；旧 channel onClose 用 channel/generation 绑定；
   - 单元测试与启动校验。
3. **dev E2E**：按 §7.2 验证，记录 `docs/validation/`。
4. **UAT**：broadcast 发布 → relay 滚动 → 观察；记录。
5. **prod**：同 UAT；记录与回滚步骤。
6. **清理版本（后续，不在本切片）**：全量 relay 稳定后，去掉 Redis 双写、`EventSubscriber` 的 SSE 职责，随后进入命令队列迁移。

提交拆分：每个可独立回滚的逻辑单元一个 commit；版本号改动并入对应功能 commit，不单独发 VERSION bump。

## 10. 待确认决策

| # | 决策 | 推荐 | 理由 |
|---|---|---|---|
| D1 | owner 变更检测只做 5 秒轮询，不做 LISTEN/NOTIFY | 推荐 | core-ng `Database` 无 LISTEN 抽象；裸 JDBC 连接的运维成本高；5 秒满足 30–60 秒接管目标 |
| D2 | relay 过渡期是否保留 Redis 双写 + 订阅兜底 | 推荐保留 | 滚动切换/混合版本不丢事件；代价是迁移期 Redis 负载不降，清理版本再移除 |
| D3 | 前端是否发送 `Last-Event-ID`/seq | 推荐不改 | 现有 RUNNING 重置使整段回放安全；避免前端与 server 版本耦合；内部 relay 自己用 `after` |
| D4 | A2A `A2AEventRelay` 是否本次一起迁 | 推荐不迁 | 生命周期/终态语义不同，单独切片；但它是 Redis 下线的剩余项，需在命令队列之后排期 |
| D5 | seq 是否持久化到 PG | 推荐不持久化 | owner 变化时 seq epoch 重置；持久化每个 chunk 的 seq 会引入写放大；接受中间事件丢失、最终结果兜底 |
| D6 | 内部端点用 query 参数而不是 path 变量 | 推荐 query | core-ng SSE 明确拒绝 `/:` 路径（`PatchedServerSentEventConfig.java:39`） |
| D7 | 为返回真实 404/401/409，是否扩展 `PatchedServerSentEventHandler` 增加 preflight | 推荐扩展 | 现有 handler 在拦截器前已提交 200；否则外网会拿到 200 SSE 流，违背 S6 |
| D8 | directed 模式下是否新增跨 Pod status RPC | 推荐不新增 | 现有 broadcast 的 status 本来就是 Pod 本地 + turn registry；ERROR 由 SSE/history 兜底；避免 watchdog 额外 Pod 间调用 |

## 11. Review 记录

| 日期 | Reviewer | 结论 | 处理 |
|---|---|---|---|
| 2026-11-17 | core-ai（AI 初稿） | Draft，待用户确认 D1–D6 | — |
| 2026-11-17 | gemini-4-hydrogen（AI Review） | No-Go v0.1：P0-1 模块顺序会破坏 GatewayModule；P0-2 handler 在拦截器前已提交 200；P0-3 chunk 合并与 `after` 续传会重复文本；P0-4 status/stats 读旧 buffer。另有 P1-5 锁内 send/队列、P1-6 新会话 owner 等待、P1-7 非 owner 错误事件、P1-8 单行 keepalive。 | v0.2 已逐项修订：保持 `SseTransportModule` 原位并在 `MessagingInfrastructureModule` 绑定；新增 preflight；copy-on-merge + 安全回放；status/stats 按 Pod 说明；订阅者队列/锁外 send；100ms 快速 owner 轮询；POST owner 兜底；按行 keepalive。 |
| 2026-11-17 | gemini-4-hydrogen（AI Re-review v0.2） | 条件式 Go：原 4 个 P0 已解决；新 P0 是 preflight 在 IO 线程执行 `isOwner` 会阻塞；另有 POST 不能复用 SSE preflight、100ms owner 轮询必须异步、回放/队列边界、`SseAuthInterceptor` 描述矛盾。 | 二次修订：preflight 改为 dispatch 后在 worker 执行；POST 改普通 HTTP 路由；owner resolver 异步；`MAX_REPLAY_EVENTS=1000`；统一 auth 职责。 |
| 2026-11-17 | core-ai | v0.2 Draft，待用户确认 D1–D8 | 未实施 |

