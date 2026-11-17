# 阶段 3 第 3 步：RPC transport 迁移（Redis Pub/Sub → Pod 间 HTTP）

- 状态：已确认，实施中（2026-11-16）
- 日期：2026-11-16
- 关联：`docs/cn/design-dataset-postgresql-migration.md` §7.5、§7.7
- 前置：`sys.store.mode=pg`（三环境已满足）；`messaging.pods`（V40）用于地址解析。**本步不要求 `sys.lease.backend=postgres`**

## 1. 现状

跨 Pod 的同步 RPC 走 Redis：

1. 调用方 `RpcClient.call()` 从 `SessionOwnershipRegistry` 找到 owner Pod；
2. 把 `SessionCommand` `XADD` 到 `coreai:commands:pod:<owner>`；
3. owner 的 `CommandConsumer` 消费后由 `InProcessCommandHandler` 执行；
4. 执行结果 `PUBLISH` 到 `coreai:rpc:<requestId>`；
5. 调用方 `RpcResponseSubscriber` `PSUBSCRIBE` 收到后完成 future，超时默认 15s（部分调用 30s / 60s，sandbox hub 最长 305s）。

涉及的 RPC 命令（其余命令仍是异步命令，不在此次范围）：

`LOAD_TOOLS`、`LOAD_SKILLS`、`UNLOAD_SKILLS`、`LOAD_SUB_AGENTS`、`GENERATE_AGENT_DRAFT`、`A2A_START_TASK`、`A2A_CANCEL_TASK`、`A2A_RESUME_TASK`、`SANDBOX_CATALOG`、`SANDBOX_TOOL_CALL`、`SANDBOX_TOOL_POLL`。

调用入口：`AgentSessionWebServiceImpl`、`PodLocalExecutor`、`SandboxHubWebServiceImpl`、`A2ARemoteTaskGateway`。

## 2. 决策

**RPC 改为 owner Pod 之间直连 HTTP，不引入 PG 队列/回执表。**

理由：RPC 是同步请求-响应，调用方已经在阻塞等结果；经 PG 落盘 + NOTIFY + 轮询只会增加一次写入和至少一次额外延迟，没有恢复价值（调用方超时后不会重放）。异步命令仍走命令队列，由第 5 步单独迁移。

### 2.1 与 lease 解耦（关键）

`sys.rpc.transport=http` **不要求** `sys.lease.backend=postgres`：

- 会话 owner 仍由现有的 `SessionOwnershipRegistry` 解析（UAT/prod 继续用 Redis lease）；
- 本步只新增 Pod 地址注册：`PodRegistry` 在 `leaseBackend=postgres` **或** `rpcTransport=http` 时启动，把 `POD_IP:8080` 写入 `messaging.pods`；
- 这样 UAT/prod 不需要在本步切换 lease backend，避免“新旧 Pod 一个写 Redis lease、一个写 PG lease”导致的 split-brain；lease 迁移到 PG 作为独立步骤，另做过渡方案。

### 2.2 表与消息语义

**不新增表，不新增 Flyway。** 复用 `V40__messaging_leases.sql` 的：

- `messaging.pods.address`：`POD_IP:<sys.http.listen>`，PodRegistry 每 10s 心跳维护；
- `messaging.session_ownership`：仅在 `leaseBackend=postgres` 时用于 owner；本步 UAT/prod 仍是 Redis owner。

RPC 本身是 HTTP 调用，语义如下：

| 语义 | 行为 |
|---|---|
| 投递 | 调用方从 `SessionOwnershipRegistry` 取 owner Pod，再从 `messaging.pods` 取 `address`，直接 `POST http://<address>/_internal/rpc` |
| 确认 | HTTP 2xx 且 body 为 `{"ok":true,"payload":...}` 即成功；`{"ok":false,"message":...}` 是业务失败，不重试 |
| 重试 | **不自动重试**。仅当请求确定没有到达目标（无 address、地址不是 IP、`ConnectException`/`UnknownHostException`/连接超时、404）时回退 Redis RPC；读超时/连接重置可能已在目标执行，直接报错，避免重复执行 A2A/加载类操作 |
| 过期 | 沿用调用方超时（15s / 30s / 60s / 305s）；超时后迟到响应丢弃 |
| 恢复 | 无持久化。目标 Pod 重启导致调用失败，与当前 Redis RPC 超时语义一致；异步命令不受影响 |

### 2.3 接口

- `POST /_internal/rpc`，仅在 `sys.rpc.transport=http` 时注册；`redis` 模式下该路径不存在（404），让其他 Pod 回退 Redis。
- Header：
  - `X-Internal-Token: <token>`（`sys.internal.token`，Secret `core-ai-internal` 的 `rpc-token`；支持 `sys.internal.token.previous` 轮换）
  - `X-Target-Pod: <podId>`，接收方比对自身的 `PodRegistry.podId()`，不一致返回 409（不重试），避免 IP 被回收后误投。
- Body：

```json
{"type":"LOAD_SKILLS","sessionId":"...","userId":"...","payload":"...","requestId":"..."}
```

- 响应：沿用现有 `RpcClient.okResponse/errorResponse` 的 JSON（HTTP 200）；认证失败 401，非 RPC 命令 400，requestId 为空 400；任何未预期异常返回 500，**不能返回 404**（404 会被调用方当成旧 Pod 并回退执行）。
- 只接受上表的 RPC 类型；`SEND_MESSAGE`、`APPROVE_TOOL`、`CANCEL_TURN`、`CLOSE_SESSION`、`TASK_NOTIFICATION` 继续走命令总线。

### 2.4 地址

`PodRegistry` 的 address 改为：

- Deployment 增加 downward API `POD_IP`；
- address = `POD_IP:<sys.http.listen>`（由 `PodRegistry` 接收 port 参数，不硬编码 8080）；
- 没有 `POD_IP` 时保留 `podId:<port>`，但 `HttpRpcTransport` 只对 **IP literal** 发起 HTTP；非 IP 地址直接回退 Redis，避免 K8s DNS（`ndots:5`）的超时。

### 2.5 安全

- `/_internal` 不经过 `AuthInterceptor`（非 `/api/` 前缀），由 endpoint 自己校验共享 token（`MessageDigest.isEqual` 常量时间比较）；token 为空时 `sys.rpc.transport=http` 启动失败。
- 外网 App Gateway 只路由 `/api/...`，不会暴露 `/_internal`；内部 HTTPRoute 仍会路由 `/`，靠 token 保护。上线前在 dev/UAT 实测外网 `/_internal/rpc` 不可达。
- 不要把 `X-Internal-Token` 写进 ActionLog；确认 core-ng 日志不打印该 header。

## 3. 兼容与灰度

开关：`sys.rpc.transport=redis|http`，默认 `redis`。

- `http` 要求 `sys.store.mode=pg`（要有 `messaging.pods`）且 `sys.internal.token` 非空，否则启动失败；**不要求** `sys.lease.backend=postgres`。
- `http` 模式下**保留** Redis RPC 的消费与订阅：
  - 新 Pod 之间走 HTTP；
  - 目标 Pod 没有 `messaging.pods` 行（旧版本 Pod）或地址不可路由/连接失败时，回退到现有 Redis stream + pub/sub；
  - 旧 Pod 发来的 RPC 仍由新 Pod 的 `CommandConsumer` 处理并回 Redis。
- 滚动升级期间：新→新 HTTP；新→旧（无 pod 行）回退 Redis；旧→新仍走 Redis。无需停机。
- 全部 Pod 升级完成后，回退路径自然不再命中；后续版本再删除 Redis RPC 代码与 `sys.rpc.transport` 开关。

## 4. 切换与回滚

- dev：`SYS_RPC_TRANSPORT=http`，先应用 V40（dev 已应用），`SYS_LEASE_BACKEND` 可保持 postgres 或 redis（两者都支持）。
- UAT：当前 2.0.240，没有 V40。本轮应用 **V40**（`messaging.pods`；V39/V41 可不动），Deployment 增加 `POD_IP`、`SYS_INTERNAL_TOKEN`、`SYS_RPC_TRANSPORT=http`；**不改 `SYS_LEASE_BACKEND`**（继续 Redis lease）。
- prod：同 UAT，改 `fbr-prod-env-project`；当前 2.0.234。先备份/校验，确认镜像 tag。
- 回滚：`SYS_RPC_TRANSPORT=redis` + rollout，立即回到现有 Redis RPC；无数据迁移、无不可逆操作。`messaging.pods` 表可保留。

## 5. 实现清单

| 文件 | 改动 |
|---|---|
| `RpcClient` | 增加 `RpcTransport` 抽象；`redis` 用现有逻辑，`http` 先 HTTP、确定未送达时回退 Redis；公开 API 不变 |
| `RedisRpcTransport` | 从 `RpcClient` 抽出的现有 Redis stream/pub-sub 逻辑 |
| `HttpRpcTransport` | 解析 address、校验 IP literal、POST、token/targetPod header、per-request timeout、错误分类 |
| `InternalRpcController` | 校验 token/type/requestId/targetPod；调用 `InProcessCommandHandler.handleRpc()`；异常兜底 500 |
| `InProcessCommandHandler` | 增加 `handleRpc(SessionCommand): String`；用显式 response sink 传递，不改命令总线路径 |
| `PodRegistry` | 增加 `address(podId)` 查询（`messaging.pods`，30s 心跳内）；构造参数增加 port |
| `MessagingInfrastructureModule` | 在需要时创建/启动 `PodRegistry`；构造 `RpcClient` 的 transport；绑定顺序确保 PodRegistry 先于 RpcClient |
| `MessagingRuntimeModule` | `http` 模式注册 `/_internal/rpc`；保留 Redis RPC 订阅/消费 |
| `sys.properties` | `sys.rpc.transport=redis`、`sys.internal.token=`、`sys.internal.token.previous=` |
| env 仓 | Deployment 增加 `POD_IP` downward API、`SYS_INTERNAL_TOKEN` secret ref、`SYS_RPC_TRANSPORT` |

`InProcessCommandHandler` 的同步返回不使用 ThreadLocal：`handleRpc` 走显式 `Consumer<String>`/`String` 返回路径，保证响应只在调用线程内返回，不会误 `PUBLISH` 到 Redis。命令总线路径保持原样。

## 6. 客户端与超时

- 用 `java.net.http.HttpClient`：`connectTimeout=2s`，每个请求 `.timeout(callerTimeout)`；不设 client-level call timeout，避免 305s 的 sandbox RPC 被 87s 默认值截断。
- 错误分类：
  - 回退 Redis：无 address、地址非 IP、`ConnectException`、`UnknownHostException`、连接超时、HTTP 404；
  - 不回退、直接报错：HTTP 401/403/409/5xx、`HttpTimeoutException`（读超时）、连接重置（可能已执行）。
- 记录 `transport=http|redis|redis_fallback`、`fallbackReason`、`targetPod`、latency；为后续删除 Redis RPC 提供依据。

## 7. E2E 矩阵

dev（实现后的镜像）：

1. `/_internal/rpc`：无 token → 401；错误 token → 401；非 RPC 类型 → 400；requestId 为空 → 400；外网域名访问 → 404/不可达。
2. 真实 RPC：用 pod A 的 port-forward 创建会话（owner=A），再用 pod B 的 port-forward 调用 `POST /api/sessions/:id/skills`（`LOAD_SKILLS`），断言成功且 pod A 日志出现 internal rpc；确认没有 `RPC command published to stream`。
3. 跨 Pod A2A：pod A 创建 task，pod B `cancel`/`resume`，走 `callToPod` HTTP。
4. 旧 Pod 回退：保留一个 `messaging.pods` 行但把 address 改成非 IP（模拟旧 Pod），调用 RPC，断言回退 Redis 且成功。
5. 超时/重试：目标 Pod 的 HTTP 端点人为延迟超过调用超时（测试用环境变量或临时 stub），断言报错且目标只执行一次。
6. 长 RPC：`SANDBOX_TOOL_CALL` 超过 90s，断言不被 HTTP client 默认超时截断。
7. 重启恢复：删除 owner Pod 后，另一 Pod 重建会话并成功处理一次 RPC；会话发消息/审批/取消仍正常。
8. 认证：从 internal-gateway 或 sandbox Pod 无 token 调用 `/_internal/rpc` → 401。

UAT：同矩阵（至少 1、2、4、6、7），观察 1 周再切 prod。

prod：先在 UAT 通过，按 playbook 改 `fbr-prod-env-project`；执行前确认本地 LLM 配置不指向 prod。

## 8. 风险

| 风险 | 对策 |
|---|---|
| `/_internal` 被外部访问 | token + 外网 ingress 只挂 `/api`；上线前实测 |
| 滚动升级期间新→旧调用失败 | 404/无 address/非 IP/connect refused 时回退 Redis |
| 超时后重试导致重复执行 | 只在确定未送达时回退；超时直接报错 |
| 目标 Pod 地址过期/被回收 | 只用 30s 心跳内的 address；`X-Target-Pod` 校验；失效回退 |
| `POD_IP` 未配置导致全部回退 | 启动日志告警；dev/UAT E2E 覆盖；文档写明 |
| HTTP client 默认超时截断长 RPC | 用 `java.net.http.HttpClient` + per-request timeout，connect timeout 2s |
| token 轮换 401 | 支持 `sys.internal.token.previous`，滚动重启期间两者都接受 |

## 9. 待确认

1. endpoint 采用单入口 `POST /_internal/rpc`（推荐）还是设计原文的 `/_internal/commands/{type}`？
2. 灰度期保留 Redis 回退（推荐，滚动升级无停机）还是严格只走 HTTP？
3. **本步不切换 UAT/prod 的 `SYS_LEASE_BACKEND`，只新增 `messaging.pods` + HTTP RPC**（推荐，避免 lease split-brain）；是否同意把 lease 迁移留到独立步骤？
4. 内部 token 使用新 Secret `core-ai-internal/rpc-token`（推荐），还是复用现有 `terminal-gateway-secret` 新 key？
5. 是否需要 RPC 审计表？本期建议不加（同步调用，无恢复价值，增加写入）。

## 10. 评审记录

- 2026-11-16：用 `gemini-4-hydrogen` 评审。主要结论：方向可行；**阻断项**是 UAT/prod 同时切 lease 与 RPC 会在滚动升级期间产生双执行。已据此把 RPC 与 lease 解耦（§2.1、§4），并补充非 IP 地址回退、endpoint 仅在 http 模式注册、HTTP client 超时、显式 response sink、404/requestId 校验、targetPod header、token 轮换和更严格的 E2E。

## 11. 实施记录（dev）

- 实现提交：`eaf36378`（HTTP RPC）、`c5c7d8c5`（请求 host 必须是 Pod IP）、`ddbbd909`（Redis 模式读取 token 轮换属性）；另 `fb6a5e68` 修复了阻塞构建的无关 PMD 问题。
- 镜像：`2.0.250`；dev `SYS_RPC_TRANSPORT=http`，`SYS_LEASE_BACKEND=postgres`，无新增 Flyway。
- dev E2E：外网 `/_internal/rpc` 404、token/目标/类型校验、新→新 HTTP（Redis MONITOR 无该 RPC 的 XADD）、旧→新 Redis 兼容、新→旧 404 回退 Redis、回滚 redis 与再切回 http 均 0 健康检查失败、删除 owner Pod 后会话重建且 RPC 成功。
- 详见 `docs/validation/2026-11-17-dev-stage3-rpc-http.md`。
- UAT（2.0.250）：应用 V39–V41（原 V38），保持 `SYS_LEASE_BACKEND` / `SYS_STATE_BACKEND` 为 Redis，开启 `SYS_RPC_TRANSPORT=http`；新→新、旧→新、新→旧回退、token、外网 404、回滚 redis 与切回 http 均通过，详见 `docs/validation/2026-11-17-uat-stage3-rpc-http.md`。
- prod（2.0.250，原 2.0.234）：先做 CNPG 备份 `core-ai-pre-rpc-20261117`，应用 V39–V41；本地 agent LLM 指向 `litellm.connexup-dev.net`，不依赖 prod；滚动发布 5 分钟健康检查 0 失败，新→新 RPC 成功，外网 `/_internal/rpc` 404，详见 `docs/validation/2026-11-17-prod-stage3-rpc-http.md`。
