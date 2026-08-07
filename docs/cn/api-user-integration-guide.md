# 业务系统对接指南：core-ai Agent API

> 版本：v1（对齐 design-api-user-access.md v2.1）
>
> 日期：2026-08-04
>
> 适用对象：业务系统后端开发者、前端开发者

本指南面向需要将 core-ai Agent 能力集成到自有产品的业务系统，说明账号开通、权限配置、临时凭证申请、Agent 调用全流程。

---

## 1. 对接总览

### 1.1 角色与凭证体系

| 角色 | 说明 | 凭证 |
| --- | --- | --- |
| core-ai 管理员 | 创建业务系统主体（manager 用户）并签发 `cmk_` 管理密钥，**线下交付**给业务系统 | 既有账号（admin） |
| 业务系统后端 | 管理本系统的终端用户、签发临时凭证（**无自助创建/获取管理密钥的接口**） | `cmk_` 管理密钥（长期，可轮换，由管理员交付） |
| 终端用户客户端 | 实际调用 Agent（业务系统前端或后端代持） | `ctk_` 临时 key（短时效，可续期） |

### 1.2 核心概念

- **API 用户**：core-ai-server 侧的一种用户类型（`userType=api`），代表业务系统的一个终端用户。业务侧的商户/用户标识通过 `externalId` 映射，创建后返回 core-ai 的 `user_id`。
- **权限**：API 用户可调用的 Agent 列表，由 `permissions` 白名单控制（`resource_type=agent` + 具体 `resource_id=agentId`，P1 不支持通配）。权限挂在用户上，不随 key 变化。
- **配额**：API 用户的**每日 token 上限**（按 UTC 日窗口），超出后返回 429。
- **临时 key**：`ctk_` 开头的调用凭证，默认有效期 1 小时（可配），到期自动失效。**续期 / 过期 / 重新签发均由业务系统后端操作**（管理面，`cmk_`），终端用户客户端不能自助续期。
- **管理密钥**：`cmk_` 开头的管理凭证，**由 core-ai 管理员在 core-ai-server 侧创建后线下交付**给业务系统（仅创建/轮换时返回明文一次）；业务系统侧没有自助创建管理密钥的接口，密钥轮换需联系管理员。

### 1.3 接口分层

| 层 | 调用方 | 认证 | Base 前缀 |
| --- | --- | --- | --- |
| 管理面 API | 业务系统后端 | `Authorization: Bearer cmk_xxx` | `/api/api-users` |
| 调用面 API | 终端用户客户端 | `Authorization: Bearer ctk_xxx` | `/api/agents` `/api/runs` `/api/sessions` |
| Admin API | core-ai 管理员 | 既有 admin 认证 | `/api/admin/api-users` |

> 环境地址（Base URL）由商务对接时提供（dev/uat/prod）。

---

## 2. 对接流程

```
┌─────────────┐      ┌──────────────────┐      ┌─────────────────┐
│ core-ai 管理员│      │  业务系统后端       │      │ 终端用户客户端     │
└──────┬──────┘      └────────┬─────────┘      └────────┬────────┘
       │                      │                         │
       │ 1. 管理员创建业务系统    │                         │
       │    POST /api/admin/api-users (admin 角色)        │
       │    ← cmk_ 管理密钥（仅此一次）                     │
       │                      │                         │
       │ 2. 线下交付 cmk_ 管理密钥                          │
       ├─────────────────────►│                         │
       │                      │ 3. 创建终端用户            │
       │                      │ POST /api/api-users {external_id, name}
       │                      │ ← user_id                │
       │                      │                         │
       │                      │ 4. 配置权限与每日额度（合并） │
       │                      │ PUT /api/api-users/:userId/config
       │                      │    {permissions, token_quota}
       │                      │                         │
       │                      │ 5. 签发临时 key           │
       │                      │ POST /api/api-users/:userId/keys
       │                      │ ← ctk_（仅此一次）         │
       │                      └────────────┬────────────┘
       │                                   │ 7. 下发 ctk_ 给客户端
       │                                   │
       │                                   │ 8. 调用 Agent
       │                                   │ POST /api/agents/:id/call
       │                                   │ Authorization: Bearer ctk_xxx
       │                                   │ ← output / run_id
       │                                   │
       │ 9.（可选，业务系统后端操作）           │
       │    续期 key：POST /api/api-users/keys/:keyId/renew
       │    立即过期：POST /api/api-users/keys/:keyId/expire
       │    查消耗：  GET  /api/api-users/:userId/usage
```

> **关键点：`cmk_` 管理密钥由 core-ai 管理员创建（admin 接口），线下交付给业务系统。** 业务系统侧没有任何"自助创建/申请管理密钥"的接口——第 3 章所有管理面接口都是**使用**已交付的 `cmk_` 来管理本系统的 API 用户。

> **调用方边界**：续期 / 过期 / 查消耗属于**管理面**（`Bearer cmk_`），只能由**业务系统后端**调用；终端用户客户端（`ctk_`）只能调 Agent，不能自助续期——key 到期后由业务系统后端续期或重新签发。

---

## 3. 管理面 API（业务系统后端）

> 认证：所有请求头携带 `Authorization: Bearer cmk_xxx`（管理密钥）。
> 内容类型：`application/json`。
>
> **`cmk_` 管理密钥由 core-ai 管理员创建并线下交付**，业务系统侧无自助申请接口。以下接口均为业务系统**使用**该密钥管理本系统的 API 用户（创建、授权、配额、签发临时 key、查用量）。

### 3.1 创建 API 用户（幂等）

```
POST /api/api-users
```

请求：

```json
{
  "external_id": "merchant-10086",
  "name": "张三的店铺"
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `external_id` | string | 是 | 业务侧终端用户标识（商户号等），同一业务系统内唯一，幂等键 |
| `name` | string | 是 | 用户显示名 |

响应（200）：

```json
{
  "user_id": "api:0f8a...",
  "external_id": "merchant-10086",
  "name": "张三的店铺",
  "status": "active",
  "permissions": [],
  "quota": { "input_token_quota": null, "output_token_quota": null, "consumed_input_tokens": 0, "consumed_output_tokens": 0 }
}
```

- 幂等：同一 `external_id` 重复创建返回既有用户（不报错）。
- 创建后默认**无任何权限**，需执行 3.2 配置。

### 3.2 配置权限与每日额度（合并接口）

> 权限（可用 Agent 列表）与额度（每日 input/output token 上限）通过**一个接口**配置，字段可选、传了才更新。额度单位：token（1M = 1_000_000）。

```
PUT /api/api-users/:userId/config
```

请求（示例：同时配置权限 + 每日额度）：

```json
{
  "permissions": [
    { "resource_type": "agent", "resource_id": "order-assistant" },
    { "resource_type": "agent", "resource_id": "refund-agent" }
  ],
  "input_token_quota": 1000000,
  "output_token_quota": 500000
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `permissions` | array | 否 | 可用 Agent 列表，**全量覆盖**（传空数组 = 清空全部权限）；P1 仅支持 `resource_type=agent` 的具体 id |
| `input_token_quota` | long | 否 | **每日** input token 上限（按 UTC 日窗口）；`null`/`0` 表示不限 |
| `output_token_quota` | long | 否 | **每日** output token 上限（按 UTC 日窗口）；`null`/`0` 表示不限 |

部分更新示例（只改额度，不动权限）：

```json
{ "input_token_quota": 500000, "output_token_quota": 250000 }
```

响应（200）：`ApiUserView`（含 `permissions`、`quota` 与 `quota.consumed_input_tokens` / `quota.consumed_output_tokens` 当日消耗）。

> 注意：每日 0 点（UTC）自动重置当日消耗；权限与额度的当前值通过 `GET /api/api-users/:userId` 查询（3.3）。

### 3.3 查询 API 用户详情

```
GET /api/api-users/:userId
```

响应（200）：`ApiUserView`（同 3.1，含权限、每日额度、当日已消耗）。

### 3.4 签发临时 key

```
POST /api/api-users/:userId/keys
```

请求：

```json
{
  "ttl_seconds": 3600,
  "metadata": { "merchant_name": "张三的店铺", "order_id": "SO-20260804-001" }
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `ttl_seconds` | int | 否 | 有效期秒数，默认 3600（1 小时），上限 604800（7 天） |
| `metadata` | object | 否 | 业务上下文（键值均为字符串），会透传到调用时的 trace，便于对账检索 |

响应（200）：

```json
{
  "key_id": "k_9f2c...",
  "key": "ctk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "expires_at": "2026-08-04T11:00:00Z"
}
```

> **`key` 明文仅本次返回**，之后无法再次获取，请立即安全保存（建议业务后端加密存储，不下发日志）。

### 3.5 列出 key

```
GET /api/api-users/:userId/keys
```

响应（200）：

```json
{
  "keys": [
    { "key_id": "k_9f2c...", "key_prefix": "ctk_xxxxxxxx", "scope": "call",
      "status": "active", "expires_at": "2026-08-04T11:00:00Z", "created_at": "..." }
  ]
}
```

（不含明文 key。）

### 3.6 续期 key

> **调用方：业务系统后端**（`Bearer cmk_`）。终端用户客户端不能自助续期——key 到期后由业务系统后端续期或重新签发。

```
POST /api/api-users/keys/:keyId/renew
```

请求：

```json
{ "ttl_seconds": 3600 }
```

响应（200）：

```json
{ "key_id": "k_9f2c...", "expires_at": "2026-08-04T12:00:00Z" }
```

约束：仅 `active`、`scope=call` 且未到期的 key 可续期；`revoked`/`expired` 不可续期（需重新签发）。

典型续期场景：

1. 业务系统后端**主动续期**：定期检查 `expires_at`，剩余有效期低于阈值（如 30 分钟）时提前续期，保证长会话/长任务不中断；
2. 客户端收到 401（key 过期）后上报业务系统后端 → 后端续期或重新签发后下发新 key。

### 3.7 立即过期 key

> **调用方：业务系统后端**（`Bearer cmk_`）。用于主动收回凭证（如用户注销、疑似泄露）。

```
POST /api/api-users/keys/:keyId/expire
```

请求体为空。响应（200）：空。该 key 即刻失效。

### 3.8 查询用量

```
GET /api/api-users/:userId/usage?from=2026-08-01T00:00:00Z&to=2026-08-04T23:59:59Z
```

响应（200）：

```json
{
  "total_tokens": 125000,
  "input_tokens": 80000,
  "output_tokens": 40000,
  "cached_tokens": 5000,
  "cost_usd": 0.3125,
  "call_count": 86,
  "by_day": [
    { "date": "2026-08-04", "total_tokens": 32000, "input_tokens": 21000, "output_tokens": 10000, "cached_tokens": 1000, "cost_usd": 0.08, "call_count": 22 }
  ]
}
```

约束：`from`/`to` 跨度 ≤ 90 天，均必填。

### 3.9 归属校验

管理面所有接口要求操作的用户/key 属于当前 `cmk_` 对应的业务系统，否则返回 403。

---

## 4. 调用面 API（终端用户客户端）

> 认证：`Authorization: Bearer ctk_xxx`（临时 key）。
> 仅能调用已被授权（permissions 白名单）的 Agent，否则返回 403。

### 4.1 同步调用

```
POST /api/agents/:agentId/call
```

请求：

```json
{
  "input": "帮我查一下订单 SO-20260804-001 的状态",
  "attachments": []
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `input` | string | 是 | 用户输入 |
| `attachments` | array | 否 | 附件（图片等），结构见 LLM 附件 |

响应（200）：

```json
{
  "output": "订单 SO-20260804-001 当前状态：已发货，预计 8 月 6 日送达。",
  "token_usage": { "input": 320, "output": 45, "total": 365 },
  "run_id": "run-..."
}
```

### 4.2 异步触发

```
POST /api/runs/agent/:agentId/trigger
```

请求体同 4.1（`input`、`attachments`）。

响应（202）：

```json
{
  "run_id": "run-...",
  "status": "PENDING"
}
```

### 4.3 查询 run 结果

```
GET /api/runs/:runId
```

响应（200）：

```json
{
  "id": "run-...",
  "agent_id": "order-assistant",
  "status": "COMPLETED",
  "input": "...",
  "output": "订单 SO-20260804-001 当前状态：已发货。",
  "error": null,
  "token_usage": { "input": 320, "output": 45, "total": 365 },
  "trace_id": "...",
  "started_at": "...",
  "completed_at": "...",
  "transcript": [
    { "ts": "...", "role": "user", "content": "帮我查订单" },
    { "ts": "...", "role": "assistant", "content": "订单状态：已发货" }
  ],
  "artifacts": []
}
```

`status` 取值：`PENDING` / `RUNNING` / `COMPLETED` / `FAILED` / `TIMEOUT` / `CANCELLED` / `SKIPPED`。建议轮询间隔 ≥ 2s。

### 4.4 多轮会话（SSE 流式）

**创建会话：**

```
POST /api/sessions
```

请求：

```json
{
  "agent_id": "order-assistant",
  "config": { "model": null, "temperature": null }
}
```

响应（200）：

```json
{ "session_id": "session-..." }
```

**发送消息（SSE 流式）：**

```
POST /api/sessions/:sessionId/messages/stream
```

请求：

```json
{ "message": "帮我查一下订单 SO-20260804-001" }
```

响应：`text/event-stream`，事件结构见 core-ai SSE 协议（消息增量、工具调用、完成事件）。

> 多轮会话的每轮消息都会计入该用户的 token 消耗与配额。

---

## 5. 错误码

| HTTP | 错误码 | 场景 | 处理建议 |
| --- | --- | --- | --- |
| 401 | `UNAUTHORIZED` | 未认证 / key 无效 / key 已过期 | 检查 `Authorization` 头；key 过期则重新签发 |
| 403 | `FORBIDDEN` | key 无权限调用该 Agent / 资源不属于当前业务系统 | 检查权限白名单与归属 |
| 404 | `NOT_FOUND` | 用户 / key / Agent / run 不存在 | 检查 ID 是否正确 |
| 409 | `CONFLICT` | 资源冲突（一般幂等场景不会出现） | 重查既有资源 |
| 429 | `QUOTA_EXCEEDED` | 配额已用尽 | 提示用户稍后再试 / 调高配额 |
| 429 | `RATE_LIMITED` | 触发限流 | 退避重试 |
| 400 | `BAD_REQUEST` | 参数缺失或非法 | 检查请求体字段 |

错误响应体：

```json
{ "error": { "code": "QUOTA_EXCEEDED", "message": "quota exceeded" } }
```

---

## 6. 安全与运维注意事项

1. **`cmk_` 管理密钥**：由 core-ai 管理员创建并线下交付（仅创建/轮换时返回明文一次），业务系统无自助创建/获取入口；长期有效，务必妥善保管（建议独立密钥管理服务）；泄露或需轮换时联系管理员（旧 key 立即失效）。
2. **`ctk_` 临时 key**：明文仅签发时返回一次，建议业务后端加密存储；到期自动失效，客户端不应持久化。
3. **幂等创建**：`POST /api/api-users` 用 `external_id` 幂等，重复调用安全。
4. **权限最小化**：按需配置 Agent 白名单，不建议默认 `*`。
5. **配额**：配置每日 token 上限，避免消耗失控；每日 0 点（UTC）自动重置。
6. **用量对账**：`usage` 接口口径与 core-ai admin 侧一致（均来自 trace）；`metadata` 中建议携带业务单号便于检索。
7. **禁用业务系统**：联系管理员禁用后，名下所有临时 key 立即失效。

---

## 7. 对接 Checklist

- [ ] 管理员创建业务系统，拿到 `cmk_` 管理密钥
- [ ] 后端实现：创建 API 用户（幂等）→ 配置权限 → 配置额度 → 签发 key
- [ ] 后端实现：key 续期/过期、用量查询
- [ ] 客户端实现：`Bearer ctk_` 调用 Agent（同步 / 异步 / 会话）
- [ ] 错误处理：401/403/429 的提示与重试
- [ ] 联调环境验证：创建 → 授权 → 调用 → 查用量全链路
