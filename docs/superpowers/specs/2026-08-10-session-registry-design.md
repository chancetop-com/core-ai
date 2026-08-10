# 统一 Session Registry 与跨 Pod 首消息链路修复设计

- 日期：2026-08-10
- 状态：方向已确认，待书面审阅
- 优先级：P0
- 范围：`core-ai-server`

## 1. 背景

UAT 的 Agent Builder Auto 创建链路会先调用 `POST /api/sessions`，再调用
`POST /api/sessions/messages/stream` 发送首条消息。创建接口当前会：

1. 生成 `sessionId`；
2. 在接收请求的 Pod 中构建 `InProcessAgentSession`；
3. 将 session 和身份元数据写入当前 JVM 的 Map；
4. 在 Redis 中 claim session ownership；
5. 返回 `201 Created` 和 `sessionId`。

创建接口不会无条件写入 Mongo `chat_sessions`。该集合目前通常由首条用户消息，或
额外加载 tools、skills、sub-agents 的副作用创建。

2026-08-07 的提交 `f0659edd` 在两个 SSE listener 中增加了
`ChatMessageService.getSessionMeta(sessionId)` 前置检查，而该方法只查询 Mongo。
因此，没有提前产生 `chat_sessions` stub 的新 session 会出现循环依赖：

```text
创建接口返回 sessionId
  -> Mongo 尚无 chat_sessions 记录
  -> 消息流在发布 SEND_MESSAGE 前返回 NOT_FOUND
  -> 首条消息未执行
  -> chat_sessions 永远不会被首条消息创建
```

UAT 已用同一 `sessionId` 验证：Pod A 成功创建本地运行 session，约 280ms 后 Pod B
在 `AgentMessageStreamChannelListener` 的 Mongo 检查处返回 `NOT_FOUND`。后续请求即使
落回 Pod A 仍然失败，因为该检查不读取 Pod A 的本地 Map。

## 2. 目标

本次修复需要满足：

1. `POST /api/sessions` 只有在 session 已对所有 Pod 共享可见时才返回成功。
2. Agent Builder、普通 Agent、带或不带 tools/skills/sub-agents 的 Agent 遵守相同创建契约。
3. 创建请求和消息流请求落到不同 Pod 时，首条消息仍能成功执行并流式返回。
4. session 的存在性、用户归属和删除状态由一个共享组件统一判断。
5. SSE 在 join channel 前验证当前用户拥有该 session，避免跨用户订阅事件。
6. idle cleanup 只释放 Pod 运行资源，不删除可重建的持久 session。
7. 不修改前端 API 协议，不新建 Mongo collection，兼容现有 `chat_sessions` 数据。

## 3. 非目标

本次不做以下事项：

- 不把 `InProcessAgentSession` 序列化到 Mongo 或 Redis。
- 不让多个 Pod 同时执行同一个 session；Redis ownership 机制继续负责运行归属。
- 不重写 Session rebuild、SSE buffering 或 Redis Pub/Sub 协议。
- 不新增完整分布式事务框架。
- 不新增 session idempotency key；响应丢失导致的空 session 清理后续单独评估。
- 不修改 Chat 前端的创建、发送和重试流程。
- 不在本次变更中部署或重启 Dev、UAT、Prod；部署需要独立授权。

## 4. 方案选择

采用“新增 `SessionRegistry`，复用现有 `chat_sessions` 集合”的方案。

没有采用 Controller 直接插入 Mongo，因为它只修复 Web 入口；CLI、API、A2A、scheduled
或未来直接调用 `AgentSessionManager` 的入口仍可能绕过相同不变量。

没有新建 session collection 或完整状态机，因为现有 `chat_sessions` 已包含本次需要的
稳定身份、归属、来源、删除和重建元数据。新增集合会制造第二份持久事实源和迁移问题。

统一的是 session 的逻辑身份与访问契约，不是跨 Pod 共享 JVM 运行对象：

| 组件 | 职责 |
| --- | --- |
| `SessionRegistry` / Mongo | session 存在性、用户归属、Agent、来源、删除状态和持久加载信息 |
| `AgentSessionManager` | 当前 Pod 的 `InProcessAgentSession` 和运行活动时间 |
| `SessionOwnershipRegistry` / Redis | 当前执行 Pod 的临时 ownership |
| `ChatMessageService` | 消息、标题、消息数量和 Agent 输出持久化 |
| `SessionChannelService` | SSE channel、事件 buffer 和发送 |

## 5. 持久化语义

继续复用 `ChatSession` / `chat_sessions`，不增加必需字段或 schema migration。

新 session 在创建阶段写入：

```text
_id
user_id
agent_id
source
schedule_id
api_key_id
message_count = 0
created_at
deleted_at = null / absent
```

统一语义：

- 记录存在且 `deleted_at` 为空：逻辑 session 存在且可被重建。
- 记录不存在：创建尚未完成，或 session 从未合法存在。
- `deleted_at` 非空：用户已逻辑删除，不能连接、发送或重建。
- Redis ownership 不存在：当前没有 owner，需要 claim 或 rebuild；不代表逻辑 session 不存在。
- 本地 `sessions` Map 不存在：当前 Pod 没有运行对象；不代表逻辑 session 不存在。

本次不新增 `CREATING/ACTIVE/CLOSED` 字段。创建接口不会在 Registry 写入成功前暴露 ID，
因此“未删除的 Registry 记录”即可表示逻辑上可用。运行态关闭与用户删除必须继续分离。

## 6. SessionRegistry 设计

新增 `ai.core.server.session.SessionRegistry`，集中持有
`MongoCollection<ChatSession>` 并提供以下职责：

```java
ChatSession create(SessionRegistration registration);
ChatSession get(String sessionId);
ChatSession requireAccessible(String sessionId, String callerUserId);
String requireUserId(String sessionId);
String requireAgentId(String sessionId);
void recordUserMessage(String sessionId, String title, ZonedDateTime now);
void recordAgentMessage(String sessionId, ZonedDateTime now);
void addLoadedTools(String sessionId, List<ToolRef> toolRefs);
void addLoadedSkillIds(String sessionId, List<String> skillIds);
void addLoadedSubAgentIds(String sessionId, List<String> agentIds);
boolean softDelete(String sessionId, String callerUserId);
```

`SessionRegistration` 是 Registry 自己拥有的不可变输入，包含 `sessionId/userId/agentId/source/
scheduleId/apiKeyId/createdAt`。它不能复用 `ChatMessageService.SessionMeta`，否则 session 身份仍然
反向依赖消息服务。

要求：

- `create` 使用 Mongo `_id` 唯一约束；只把 duplicate key 视为可能的并发重试。
- duplicate key 后必须重新读取并比较 `user_id/agent_id/source`；身份不同则失败。
- Mongo unavailable、timeout、write concern failure 等错误不得被当作 duplicate 或成功吞掉。
- `requireAccessible` 在记录缺失或已删除时返回 404；非 owner 返回统一不可用错误，不能泄露配置。
- `recordUserMessage` 和 loaded dependency 更新只允许更新现有记录；更新数量为 0 是 invariant violation。
- 所有更新使用原子 `$set`、`$inc`、`$addToSet`，不执行跨 Pod read-modify-write。

`ChatMessageService` 不再拥有 session 身份的内存事实源。`metaBySession` 可以在本次改动中
保留为本地性能缓存，但不能再参与 session 是否存在或 caller 是否有权访问的判断；
身份和授权必须始终以 `SessionRegistry` 为准。

## 7. 创建事务边界

### 7.1 AgentSessionManager

两个创建路径必须遵守同一不变量：

- `createSession(...)`
- `createSessionFromAgent(...)`

推荐顺序：

```text
校验用户、Agent 权限和 quota
  -> 分配 sessionId
  -> 构建 Agent、Sandbox、InProcessAgentSession
  -> 注册当前 Pod 的本地运行对象
  -> claim Redis ownership（必须检查返回值）
  -> SessionRegistry.create 写入共享记录
  -> 加载定义内 tools / skills / sub-agents
  -> 返回 SessionCreationResult
```

Agent 定义内依赖继续由不可变 Agent snapshot/config 负责重建；请求动态加载并需要写入
`chat_sessions.loaded_*` 的 tools、skills、sub-agents 只能更新已存在的 Registry 记录。
任何依赖加载都不能再通过“创建 stub 的副作用”决定 session 是否存在。

### 7.2 Web 创建编排

`AgentSessionWebServiceImpl.create` 在返回前还会加载请求额外指定的 tools、skills 和
sub-agents，并保存现有 HTTP `SessionState`。整个过程都属于“创建尚未对调用方成功”的范围。

创建编排需要捕获从分配 ID 到构造响应之间的异常。若任一步骤失败：

1. 从本地 `sessions` Map 移除并关闭运行对象；
2. 释放 Sandbox；
3. 释放 Redis ownership；
4. 若 Registry 已写入，将该记录标记 `deleted_at`；
5. 返回非 2xx，不返回可重试使用的 `sessionId`。

建议提供 `AgentSessionManager.abortCreation(sessionId)`，只用于尚未成功暴露给调用方的新 ID。
补偿必须 best-effort 记录失败，但原始创建异常仍是返回给调用方的主错误。

### 7.3 成功契约

`201 Created` 明确保证：

- Mongo Registry 可查询到 session；
- `user_id/agent_id/source` 已完整；
- session 未删除；
- Redis 中已有 owner；
- 当前 owner Pod 已有可执行的本地 session。

浏览器的 `CoreAIServerSessionId` Cookie 继续只表示 HTTP session，不承担 Agent session
存在性或授权职责。

## 8. 首条消息与依赖更新

`ChatMessageService.writeUserMessage` 继续先持久化 `ChatMessage`，随后通过 Registry 更新
session metadata，但不再 insert `ChatSession`：

```text
title = 首条用户消息的截断标题（仅原 title 为空时）
last_message_at = now
message_count += 1
```

若 Registry update 返回 0，说明创建契约被破坏。命令应失败并通过现有 SSE error event 返回，
不能重新偷偷创建一个缺少可信 owner metadata 的 session。

Agent 完成一轮后继续更新 `last_message_at` 和消息数量。loaded tools、skills、sub-agents
统一由 Registry 更新既有记录。

## 9. SSE 与授权

两个入口在建立 channel 前执行相同校验：

- `AgentMessageStreamChannelListener`
- `AgentSessionChannelListener`

流程：

```text
解析并校验 sessionId
  -> 从 AuthContext 获取 caller userId
  -> SessionRegistry.requireAccessible(sessionId, userId)
  -> connect channel
  -> join session channel
  -> 发布 SEND_MESSAGE（仅消息流接口）
```

`AgentSessionChannelListener` 需要注入 `WebContext`。校验必须发生在 `connect/join` 前，
否则已登录用户只要猜到 session ID 就可能订阅其他用户的 Redis SSE 事件。

owner Pod 的 `InProcessCommandHandler` / `AgentSessionManager` 仍然重复执行 caller 校验，形成
入口和执行端的 defense in depth。

## 10. 跨 Pod 数据流

```text
Pod A: POST /api/sessions
  -> 构建本地 session
  -> Redis owner = Pod A
  -> Mongo SessionRegistry create
  -> 返回 sessionId

Pod B: POST /api/sessions/messages/stream
  -> Mongo requireAccessible
  -> 在 Pod B 建立 SSE channel
  -> Redis 发布 SEND_MESSAGE

Pod A
  -> 消费命令并再次校验 caller
  -> 执行 Agent
  -> Redis 发布 SSE events

Pod B
  -> 接收 events
  -> 返回浏览器
```

消息流不要求落到 owner Pod；Mongo 解决共享身份，Redis 解决命令和事件路由，本地 Map 只保存
不可共享的运行对象。

## 11. Rebuild、Idle Close 与用户删除

`SessionRebuildManager` 从 Registry 读取 owner、Agent、source 和 loaded dependencies；已删除
记录不得 rebuild。Redis owner 缺失或 TTL 过期时，符合现有规则的 Pod可以重建并 claim。

`IdleSessionCleanupJob` 只执行 runtime close：

- 移除本地运行对象；
- 捕获需要的 Sandbox snapshot；
- 释放 Sandbox；
- 释放 Redis ownership；
- 保留 Mongo Registry 和消息历史。

用户从 Chat 历史删除 session 才设置 `deleted_at`。不能把 idle runtime close 映射成逻辑删除，
否则历史会话无法重建。

## 12. 空 Session

创建时持久化会产生短暂或长期 `message_count=0` 的合法记录，例如创建成功后用户立即关闭页面。

本次保留这些记录：

- 用户可以重新打开并发送首条消息；
- idle cleanup 会释放其运行资源；
- 不为本次 P0 引入 idempotency key 或空 session 定时删除。

如果上线后空记录明显污染列表，再单独设计列表隐藏或定时软删除。该行为不应和本次跨 Pod
正确性修复耦合。

## 13. 错误处理与可观测性

新增结构化日志至少包含：

- `sessionId`
- `userId`（遵循现有日志脱敏策略）
- `agentId`
- `source`
- `ownerPod`
- `registryOutcome`
- `creationPhase`
- `cleanupOutcome`

关键日志：

```text
session registry created
session creation completed
session creation aborted
session registry invariant violation
session access rejected
```

不得输出 Authorization header、API key、Cookie、Redis credentials 或 Mongo connection string。

需要监控：

- session create success/failure 数；
- Registry create failure 数；
- SSE missing/deleted/foreign session 拒绝数；
- `AgentMessageStreamChannelListener` 的 NOT_FOUND 比率；
- 创建补偿失败数。

## 14. 测试策略

按 TDD 增加：

1. `SessionRegistryTest`
   - create 写入所有身份字段和 `message_count=0`；
   - duplicate same identity 幂等，different identity 失败；
   - Mongo 非 duplicate 错误向上传播；
   - missing、deleted、foreign owner 校验；
   - user/agent message 原子更新；
   - loaded dependency 更新不能创建缺失 session。
2. `AgentSessionManagerCallerTest` 或新的 creation test
   - 普通 session 与 Agent session 都在返回前创建 Registry；
   - Registry 失败时释放本地 session、Sandbox 和 ownership；
   - 没有 skills/sub-agents 的 Agent 也产生 Registry 记录。
3. `AgentSessionWebServiceImplTest`
   - extra dependency 加载失败时执行创建补偿且不返回 ID；
   - 成功响应前 Registry 已可查询。
4. `AgentMessageStreamChannelListenerTest`
   - 当前用户的持久 session 可以连接并发布命令；
   - missing/deleted/foreign session 在 connect 前失败。
5. `AgentSessionChannelListenerTest`
   - reconnect 使用同一授权边界。
6. `ChatMessageServiceTest`
   - 首条消息只更新 Registry，不再 insert session；
   - `message_count` 从 0 更新到 1，title 只初始化一次。
7. `SessionRebuildManagerTest`
   - Registry session 在 owner 过期后可以重建；
   - deleted session 不能重建；
   - idle runtime close 不删除 Registry。

重点回归：

- Agent Builder Auto；
- 默认 Assistant；
- 只有 builtin tools 的自定义 Agent；
- 带 skills/sub-agents 的 Agent；
- 已有历史 session reconnect；
- API key、scheduled 和 A2A source metadata；
- 跨用户访问拒绝。

## 15. 验收标准

1. `POST /api/sessions` 返回 201 时，Mongo 已能查询到同一 `sessionId` 的完整身份记录。
2. Agent Builder Auto 的首条消息能够收到 Agent 回复。
3. 创建请求和 stream 请求落到不同 UAT Pod 时仍成功。
4. stream 请求落回 owner Pod 时行为一致。
5. 不带 skills/sub-agents 的 Agent 不再出现特殊失败。
6. 首条消息后 `title/last_message_at/message_count` 正确更新。
7. foreign user 在 channel join 前被拒绝。
8. idle cleanup 后历史 session 可以 rebuild；用户删除后不能 rebuild。
9. Registry 写入失败时创建 API 返回非 2xx，且无残留 runtime ownership。
10. UAT 日志不再出现新创建 session 在 `AgentMessageStreamChannelListener.java:52` 的 NOT_FOUND。

## 16. 验证与发布边界

本地验证至少包括：

```text
相关 targeted tests
./gradlew --rerun-tasks :core-ai-server:check
git diff --check
```

仓库根 `./gradlew check` 若仍命中已知、与本次无关的 `core-ai:spotbugsTest` findings，必须按实际
finding 报告，不能把它误归因到本次 server 变更。

用户另行授权部署后，按 Dev -> UAT 两副本顺序验证；不能只看 Pod Ready，必须核对镜像身份、
`/api/capabilities`、Mongo Registry、Redis ownership、两个 Pod 的 session 日志和真实 Agent
Builder 首条回复。Prod 不在本次实现授权范围内。
