# Tech Design：业务系统通过 API 用户接入 core-ai-server

> 状态：待评审（v2，按评审意见重写）
>
> 日期：2026-08-04
>
> 目标版本：P1（业务系统通过临时 API Key 调用 Agent Run API）
>
> v2 变更说明：移除 merchant/partner 业务概念，统一抽象为 core-ai-server 侧的用户类型 `userType=api`（API 用户）；业务系统主体不再建模为独立实体（方案 A），而是 manager 型 API 用户；管理密钥并入 `api_keys` 体系（scope=manage）；配额记账收敛为唯一写点；会话创建两条路径均接入 `source=api` + `apiKeyId`。

## 1. 背景与目标

外部业务系统需要让它的终端用户在自己的产品里使用 core-ai 的 Agent 能力。这些终端用户不是 core-ai 的注册用户，也不应该能登录 core-ai Web UI。因此需要一套「后端对后端」的集成接口，让业务系统后端为其终端用户申请临时访问凭证。

**设计原则：core-ai-server 不引入 merchant/partner 等业务概念**。业务侧的「商户 / 终端用户 / 业务系统」统一抽象为一种用户类型——**API 用户（`userType=api`）**：

- 业务系统主体 = **manager 型 API 用户**（`userType=api`、`ownerId=null`），持有长期管理密钥（`scope=manage`）；
- 终端用户（业务侧语义） = `ownerId` 指向 manager 用户的 API 用户，持有临时调用 key（`scope=call`）；
- 业务侧标识通过 `externalId` 幂等映射（如商户号）。

核心目标：

1. 业务系统后端通过管理接口完成 API 用户与临时 key 的全生命周期管理：
   - 创建 API 用户（**不能在 core-ai-server 登录**）；
   - 为 API 用户申请临时 key；
   - 随时查询 API 用户的 token 消耗；
   - 配置 API 用户的 token 额度；
   - 续期 / 过期一个 key。
2. API 用户客户端（业务系统前端或后端代持）用临时 key 调用 core-ai-server 的 Agent Run API，key 定时过期。
3. 复用既有链路：认证（`RequestAuthenticator`）、trace/token 记账、Analytics 聚合、会话 `apiKeyId` 预留字段、Agent Run API。

非目标：

- API 用户登录 core-ai UI、使用 Azure AD、注册账号。
- 管理 core-ai 资源（Agent 定义、工具、设置、用户）。
- 提供独立的 OpenAI-compatible gateway 代理入口（沿用现有 `/api/agents/:id/call`、`/api/runs/agent/:id/trigger`、`/api/sessions`）。
- 本期做按次计费/余额/发票，只做 token 消耗查询与额度控制。
- 本期做 key 级别的独立配额（配额按 API 用户维度，见第 8 章）。

## 2. 现状与缺口

### 2.1 已有可复用能力

- 认证链：`RequestAuthenticator`（Azure AD 头 → `users.api_key`）已支持 `Bearer coreai_*` / `Bearer cai_*`；`AuthInterceptor` 统一拦截 `/api/*`；SSE 复用同一 authenticator（`SseAuthInterceptor`）。
- 用户模型：`User`（`users` collection，id=email），已有 `role`（user/admin）、`status`（pending/active）、`apiKey` 字段，以及 `AuthService.login/register`、admin 用户管理接口。
- 消耗追踪：`Trace`/`Span` 按 `userId` 记账；`trace_daily_stats`、`analytics_daily_stats` 按日预聚合；`TraceService` 已有按 userId 查询能力。
- Agent 调用：`POST /api/agents/:id/call`（同步）、`POST /api/runs/agent/:id/trigger`（异步）、`POST /api/sessions`（会话 + SSE）。这些接口只要求「已认证用户」，拿到 userId 即可运行任意 Agent。
- `ChatSession.apiKeyId` 字段与 `SessionMeta.apiKeyId` 是**预留字段**（注释写明 "set only when source=api"），目前没有代码填充——正是本设计要接上的点。
- 会话创建的**两条路径**都已带 `source` 参数：`AgentSessionManager.createSession(config, userId, source)` 与 `createSessionFromAgent(definition, overrides, userId, source)`——本期为它们补上 `apiKeyId` 即可。
- Schema 迁移框架：`SchemaMigrationManager` + `SchemaMigrationV*`（当前最高版本 `20260731001`）。

### 2.2 必须补齐的缺口

| 层 | 现状 | P1 改动 |
| --- | --- | --- |
| 用户模型 | `User` 没有「不可登录的 API 用户」概念 | 新增 `userType`（internal/api）+ `ownerId` + `externalId`；API 用户无密码、非 email id、不进入 Azure AD 自动建号 |
| 凭证 | 每用户仅一个永续 `apiKey`（`users.api_key`），无 TTL、无多 key | 新增 `api_keys` collection：一个用户可有多把临时 key，各有 `expiresAt`/状态/scope；管理密钥同为 `api_keys` 记录（scope=manage） |
| 业务系统主体 | 无「业务系统」概念 | 不新增实体：业务系统 = manager 型 API 用户，管理密钥 = 其名下 `scope=manage` 的 key |
| 认证 | `RequestAuthenticator` 只认 `coreai_*`/`cai_*` → `users.api_key` | 增加 `ctk_*`（临时调用 key）/ `cmk_*`（管理密钥）→ 统一查 `api_keys` → 解析为 API 用户 userId |
| 上下文 | `AuthContext` 只有 `auth.userId` | 增加 `auth.keyId`（临时 key id，供会话 `apiKeyId` 使用）；不需要 partnerId |
| 管理 API | 无 | 新增 API 用户管理 WebService（增查、权限配置、key 签发/续期/过期、quota 配置、用量查询）与 admin 侧管理 |
| 资源权限 | 无（任何已认证用户可调任意 Agent） | 新增 API 用户级资源权限（resourceType/resourceId 白名单），Agent Run 入口检查 |
| 配额 | 无 | API 用户 quota 配置 + 窗口内消耗计数 + Agent Run 入口检查 |

## 3. 总体架构

```text
                    +---------------------------+
                    |  core-ai admin UI / API   |--- 1. 创建 manager 型 API 用户（admin，一次性返回 cmk_ 管理密钥）
                    +---------------------------+
                              |
                              v
+-------------------+    2. 提供 cmk_ 管理密钥    +-------------------------------+
| 业务系统后端        | <------------------------> |  core-ai-server               |
| (Partner Backend) |   3. POST /api/api-users                  创建终端用户对应的 API 用户 |
|                   |   4. PUT /api/api-users/:userId/config    配置权限与额度      |
|                   |   5. POST /api/api-users/:userId/keys     签发临时 key  |
|                   |   6. GET /api/api-users/:userId/usage     查消耗        |
|                   |   7. POST /api/api-users/keys/:keyId/renew|expire 续期/过期 |
+-------------------+                              +-------------------------------+
        | 8. 返回临时 key（ctk_xxx，TTL）
        v
+-------------------+
| API 用户客户端     |  9. Authorization: Bearer ctk_xxx
+-------------------+        |
                             v
                  +-------------------------------+
                  |  AuthInterceptor                |
                  |  RequestAuthenticator           |
                  |   ctk_ -> api_keys -> userId    |
                  +-------------------------------+
                             | userId = API 用户
                             v
                  +-------------------------------+
                  |  Agent Run API                 |
                  |  POST /api/agents/:id/call     |
                  |  POST /api/runs/agent/:id/trigger
                  |  POST /api/sessions            |
                  +-------------------------------+
                             | 10. trace 按 userId 记账
                             v
                  +-------------------------------+
                  |  traces / trace_daily_stats / analytics_daily_stats
                  +-------------------------------+
```

流程说明：

1. core-ai admin 创建 manager 型 API 用户（代表业务系统主体），拿到一次性 `cmk_` 管理密钥，交给业务系统后端。
2. 终端用户在业务系统触发需 Agent 的功能 → 业务系统后端用 `cmk_` 调管理接口：先 `POST /api/api-users`（幂等，按业务侧 externalId）创建/复用 API 用户，再 `PUT /api/api-users/:userId/config` 配置权限（可调用哪些 Agent）与 token 额度。
3. 业务系统后端 `POST /api/api-users/:userId/keys` 申请临时 key（指定 TTL），拿到 `ctk_xxx`。
4. 业务系统把临时 key 下发给终端用户客户端（或后端代持），客户端带 `Authorization: Bearer ctk_xxx` 调用 Agent Run API。
5. core-ai 认证链把 `ctk_` 解析为 API 用户 userId；配额检查在 Agent Run 入口执行；trace 按该 userId 记账，复用全部 Analytics 管道。
6. 临时 key 到期自动失效；业务系统后端可续期或立即过期；可随时查询消耗、调整额度。

## 4. 数据模型

### 4.1 `User`（扩展既有实体，`users` collection）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String (@Id) | 普通用户 = email；API 用户 = `api:{uuid}`（幂等靠唯一索引而非确定性 id，见第 9 章） |
| `userType` | String (新) | `internal`（默认，既有用户）\| `api`（API 用户，含 manager 型业务系统主体） |
| `ownerId` | String (新) | API 用户归属的 manager 用户 id；internal 用户与 manager 型 API 用户为 null |
| `externalId` | String (新) | 业务系统侧的终端用户标识（业务侧幂等键，如商户号） |
| `permissions` | List\<ResourcePermission\> (新) | 可用 Agent 列表（P1 仅支持 `resource_type=agent` 的具体 id，不支持通配），见 4.2 |
| `passwordHash` | String | API 用户恒为 null → 无法通过密码登录 |
| `quotaTokens` | Long (新) | **每日** token 上限；null/0 = 不限 |
| `quotaWindowStart` | ZonedDateTime (新) | 当前日窗口起点（UTC 0 点，懒重置） |
| `quotaConsumedTokens` | Long (新) | 当日已消耗计数（$inc） |
| `status` | String | API 用户创建即 `active`，否则临时 key 校验不通过 |

登录限制（三重保证）：

1. `passwordHash = null`，`AuthService.login` 密码校验必然失败；
2. `AuthService.login` 显式拒绝 `userType=api`；
3. Azure AD 自动建号路径按 `X-Auth-Request-Email` 头创建用户，API 用户 id（`api:...`）不含 `@`，不会触发。

实现注意（core-ng 约束）：`userType` 带默认值 `"internal"`，实体字段必须加 `@NotNull` 注解；quota 相关字段为可空 Long（`Long` 而非 `long`），无需默认值。**core-ng 的 `EntityEncoder` 会把 null 字段显式写入文档**（`writer.writeNull()`），因此存量 internal 用户的 `ownerId`/`externalId` 会以 null 值存在——幂等唯一索引**不能使用 sparse**（sparse 只跳过缺失字段、不跳过 null 值），必须用 partial index（见第 9 章）。

### 4.2 `ResourcePermission`（内嵌对象，非独立 collection）

权限模型面向未来 core-ai-server 权限管理体系统一抽象：**资源类型 + 资源 id** 的二元组。**P1 范围：仅支持 `agent` 资源类型、具体 id 列表（不含通配）**，未来在此模型上扩展其他资源类型与通配语义。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `resourceType` | String | 资源类型：P1 仅 `agent`；未来扩展 `tool` / `dataset` / `skill` 等 |
| `resourceId` | String | 资源 id（agentId），P1 为具体 id 列表，不支持 `*` |

语义：

- 白名单：`User.permissions` 为空 = 无任何资源权限（**不是**全部放行）；
- P1：`permissions = [{resourceType: "agent", resourceId: "<agentId>"}, ...]` = 可调用这些指定 Agent；
- 未来权限体系（RBAC/角色、internal 用户、通配、工具/数据集授权）在此模型上扩展：新增 resourceType 即可，检查点统一走 `PermissionService.check(userId, resourceType, resourceId)`。

core-ng 约束：`ResourcePermission` 为 `User` 的内嵌对象（类似 `AgentDatasetConfig`），不注册为独立 collection；字段均为 String，无 `Object` 类型问题。

### 4.3 `ApiKey`（新实体，`api_keys` collection）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String (@Id) | `k_` + uuid（管理接口用 keyId 引用） |
| `keyHash` | String | SHA-256(key)，明文仅创建/签发时返回一次 |
| `keyPrefix` | String | `ctk_` / `cmk_` + 前 8 位，便于展示 |
| `userId` | String | 归属 API 用户 userId（manager 或终端 API 用户） |
| `scope` | String (新) | `call`（临时调用 key）\| `manage`（管理密钥，仅 admin 创建 manager 用户/轮换时签发） |
| `metadata` | Map\<String, String\> (新) | 业务侧上下文（如商户名、订单号），透传至 `Trace.metadata` |
| `status` | String | `active` \| `revoked` \| `expired` |
| `expiresAt` | ZonedDateTime | 到期时间（认证时校验）；`manage` 密钥为 null（长期，靠轮换） |
| `createdAt` / `lastUsedAt` / `revokedAt` | ZonedDateTime | 审计 |

key 格式：

- 临时调用 key：`ctk_` + base64url(32 随机字节)，TTL 默认 1 小时、上限 7 天（可配）。
- 管理密钥：`cmk_` + base64url(32 随机字节)，仅 admin 创建/轮换时返回明文一次。

认证时：`ctk_`/`cmk_` 前缀 → SHA-256 → 查 `api_keys.key_hash`。

状态机：`active` →（到期校验时懒更新 `expired`）或 →（手动 `revoked`）。`expired`/`revoked` 均不可续期；续期仅作用于 `active`、`scope=call` 且 `expiresAt > now` 的 key。

### 4.4 不新增「业务系统」实体（方案 A）

- 业务系统主体 = **manager 型 API 用户**（`userType=api`、`ownerId=null`），业务系统名称 = `User.name`；
- 管理密钥 = 该用户名下 `scope=manage` 的 ApiKey（无 TTL，靠 rotate-key 轮换）；
- 终端用户（原 merchant 概念）= `ownerId` 指向 manager 用户的 API 用户；
- 权限（可调用哪些 Agent）= `User.permissions`（用户级，**不挂在 key 上**——key 只负责认证与 TTL，权限随用户走，换 key 不丢权限、禁用用户即收回权限）；
- 禁用业务系统 = manager 用户 `status=disabled`，其名下所有 API 用户的临时 key 认证同步失效（见 6.1 的 owner 状态校验）；
- 密钥轮换 = 撤销旧 `cmk_` + 签发新 `cmk_`，完全复用 ApiKey 生命周期；
- 审计：管理动作与调用都落在 userId 维度，trace 全链路可归因到具体业务系统。

## 5. API 设计

### 5.1 Admin 侧（core-ai 管理端，需 `admin` 角色）— `AdminApiUserWebService`

管理密钥**只能由 core-ai 管理员创建并线下交付**，业务系统侧无任何自助创建/自助获取管理密钥的接口；轮换同样走 admin 接口（旧密钥立即失效）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/api-users` | 创建 manager 型 API 用户（业务系统主体），返回 `{user_id, api_key}`（`cmk_` 明文一次性，admin 线下交付给业务系统） |
| GET | `/api/admin/api-users` | 列表 |
| POST | `/api/admin/api-users/:id/rotate-key` | 轮换管理密钥，返回新 `api_key`（旧密钥立即失效，admin 线下交付新密钥） |
| POST | `/api/admin/api-users/:id/update-status` | 启用 / 禁用 |

### 5.2 管理侧（业务系统后端调用，`Authorization: Bearer cmk_*`）— `ApiUserWebService`

| 方法 | 路径 | 请求 → 响应 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/api-users` | `CreateApiUserRequest{external_id, name}` → `ApiUserView{user_id, external_id, name, status, permissions, quota}` | 幂等创建/复用（按 ownerId + externalId） |
| GET | `/api/api-users/:userId` | → `ApiUserView` | 查详情（含权限、额度、当前窗口消耗） |
| PUT | `/api/api-users/:userId/config` | `UpdateApiUserConfigRequest{permissions?, input_token_quota?, output_token_quota?}` → `ApiUserView` | **配置权限与每日额度**（合并接口，字段可选，语义见下） |
| GET | `/api/api-users/:userId/usage` | `UsageQueryRequest{from, to}` → `UsageView{total_tokens, input_tokens, output_tokens, cached_tokens, cost_usd, call_count, by_day[]}` | 按时间范围查 token 消耗（限制 from/to 跨度 ≤ 90 天） |
| POST | `/api/api-users/:userId/keys` | `CreateKeyRequest{ttl_seconds, metadata?}` → `CreateKeyResponse{key_id, key, expires_at}` | 签发临时 key（明文一次性返回） |
| GET | `/api/api-users/:userId/keys` | → `ListKeysView{keys[]}` | 列出该用户的 key（不含明文） |
| POST | `/api/api-users/keys/:keyId/renew` | `RenewKeyRequest{ttl_seconds}` → `RenewKeyResponse{key_id, expires_at}` | 续期（仅 active、call scope 且未到期） |
| POST | `/api/api-users/keys/:keyId/expire` | → void | 立即过期（revoked） |

`PUT /api/api-users/:userId/config` 语义：

- 请求体字段均可选，**传了才更新**，未传字段保持不变（部分更新）；
- `permissions`：可用 Agent 列表，**全量覆盖**（空数组 = 清空全部权限）；P1 仅支持 `resource_type=agent` 的具体 id；
- `input_token_quota` / `output_token_quota`：**每日** input/output token 上限（按 UTC 日窗口），`null`/`0` = 不限（单位 token，1M = 1_000_000）；
- 权限与额度查询统一看 `GET /api/api-users/:userId` 详情，不再提供独立查询接口。

示例：

```json
{
  "permissions": [ { "resource_type": "agent", "resource_id": "order-assistant" } ],
  "input_token_quota": 1000000,
  "output_token_quota": 500000
}
```

约束：

- 所有管理接口做**归属校验**：路径里的 `userId` / `keyId` 必须属于当前认证的 manager 用户（`ownerId == 当前 userId`），否则 403。
- `ttl_seconds` 默认 3600（1 小时），上限取 `sys.api-user.key.max.ttl`（默认 7 天，可配）。

### 5.3 Agent 调用侧（API 用户临时 key）— 复用现有 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agents/:id/call` | 同步调用（LLM_CALL 直连 / AGENT 轮询） |
| POST | `/api/runs/agent/:id/trigger` | 异步触发 |
| GET | `/api/runs/:id` | 查询 run 结果 |
| POST | `/api/sessions` + `/api/sessions/:id/messages/stream` | 多轮会话 + SSE 流式 |

- 认证：`Authorization: Bearer ctk_xxx` → API 用户 userId，随后链路与普通用户一致。
- 会话场景：**两条创建路径**（带 agentId 的 `createSessionFromAgent(...)` 与纯 config 的 `createSession(...)`）都在创建时设置 `SessionMeta.apiKeyId = AuthContext.keyId()`、`source = "api"`，接上预留的 `ChatSession.apiKeyId` 字段。`AgentSessionManager` 的两个方法需新增 `apiKeyId` 参数（Web 层从 `AuthContext.keyId()` 传入，非 Web 调用方传 null）。
- **权限检查**：Agent Run 入口（`AgentRunWebServiceImpl.call/trigger`、`llmCall`、`AgentSessionWebServiceImpl` 创建会话前）调用 `PermissionService.check(userId, "agent", agentId)`——校验 `User.permissions` 是否含 `(agent, agentId)` 或 `(agent, *)`，无权限抛 403 `FORBIDDEN`。权限随用户走，key 不参与权限判定。
- 临时 key 若配置了 `metadata`，调用时写入 `Trace.metadata`（与 `keyId` 一并解析自 AuthContext）。

## 6. 认证与授权改造

### 6.1 `RequestAuthenticator` 扩展

```java
private String authenticateFromApiKey(Request request) {
    var auth = request.header("Authorization");
    if (auth.isEmpty()) return null;
    var value = auth.get();

    if (value.startsWith("Bearer ctk_") || value.startsWith("Bearer cmk_")) {   // 新增：API 用户 key（临时调用 / 管理）
        return authenticateFromApiKeyRecord(value.substring(7));
    }
    if (!value.startsWith("Bearer coreai_") && !value.startsWith("Bearer cai_")) return null;
    // ... 既有 users.api_key 逻辑不变
}

private String authenticateFromApiKeyRecord(String key) {
    var hash = sha256(key);
    var apiKey = apiKeyCollection.findOne(Filters.eq("key_hash", hash));
    if (apiKey.isEmpty()) throw new UnauthorizedException("invalid api key");

    var k = apiKey.get();
    if (!"active".equals(k.status)) throw new UnauthorizedException("api key is not active");
    if (k.expiresAt != null && k.expiresAt.isBefore(ZonedDateTime.now())) {
        updateKeyStatus(k, "expired");                     // 懒更新
        throw new UnauthorizedException("api key expired");
    }
    var user = userCollection.get(k.userId);
    if (user.isEmpty() || !"active".equals(user.get().status)) {
        throw new UnauthorizedException("api user disabled");
    }
    if (user.get().ownerId != null) {                      // 归属的 manager 被禁用则同步失效
        var owner = userCollection.get(user.get().ownerId);
        if (owner.isEmpty() || !"active".equals(owner.get().status)) {
            throw new UnauthorizedException("api user disabled");
        }
    }
    updateLastUsed(k);                                     // 节流更新（见 11.8 性能注记）
    return k.userId;                                       // 下游全部按 userId 记账
}
```

要点：

- `ctk_`（scope=call）与 `cmk_`（scope=manage）统一走 `api_keys` 查询，**都解析为 userId**——管理面与调用面不再有第二套身份，trace/审计全部归因到用户。
- 认证时校验「归属用户 active + **owner 用户 active**」：manager 被禁用后，其名下所有临时 key 立即失效（修复原设计 partner 禁用不生效的缺口）。
- key 的 `scope` 由 `AuthInterceptor` 按路径约束（见 6.2），`RequestAuthenticator` 本身不区分。

### 6.2 `AuthContext` / `AuthInterceptor`

- `AuthContext` 增加常量与访问器：`KEY_ID_KEY = "auth.keyId"`、`keyId(WebContext)`。**不需要 partnerId**——manager 与终端用户都是 userId。
- `AuthInterceptor.intercept`：
  - 路径以 `/api/api-users/` 开头 → 走 `authenticateFromApiKey` 且要求 key `scope=manage`（否则 403），把 `userId` + `keyId` 放入 context；
  - 路径以 `/api/admin/api-users` 开头 → 既有 `authenticate(request)` + `requireAdmin`（`role=admin`），把 `userId` 放入 context；
  - 其他 `/api/*` → 既有 `authenticate(request)`；当 `ctk_` 命中时，把临时 key 的 `keyId` 也放入 context（供会话 `apiKeyId` 使用）。
- 管理接口的 controller 通过 `AuthContext.userId(webContext)` 取当前 manager 用户，并做资源归属校验（`ownerId == 当前 userId`）。
- `SseAuthInterceptor` 无需改动：SSE 只用临时 key（`ctk_`），走 `authenticateFromApiKey`。

### 6.3 登录限制

- `AuthService.login`：查库后若 `userType=api` → 直接抛 `UnauthorizedException`（"api user cannot login"）。
- `UserService` 自助接口（`/api/user/api-key`、`/api/user/change-password`）：对 `userType=api` 返回 403，防止 API 用户自建永续 key 绕过 TTL。
- Azure AD 自动建号：`ensureUser` 仅处理 email 形式的 userId，API 用户 id（`api:...`）不会进入该路径。
- `AuthService.listUsers` 用 `Filters.exists("email")`，API 用户无 email 不会出现在既有 admin 用户列表——admin 面通过 `/api/admin/api-users` 单独管理。

## 7. 用量查询

`GET /api/api-users/:userId/usage?from=..&to=..`：

- 实时口径：聚合 `traces`（`user_id = :userId`，`created_at ∈ [from, to]`），`$sum` input/output/cached/total tokens 与 `cost_usd`，`$count` call_count，并按天 `$group` 输出 `by_day`。
- 复用 `TraceService`/`TraceController` 的聚合逻辑（新建 `ApiUserUsageService` 调用同一底层聚合，避免重复实现）。
- 该接口返回给业务系统后端的即为「API 用户的 token 消耗」，与 admin 侧 `GET /api/admin/analytics/by-user` 口径一致（都来自 traces）。
- `ApiUserView` 里的 `quota_consumed` 则来自 `User.quotaConsumedTokens` 计数器（实时，用于配额判断），两者口径说明见第 8 章。
- `from/to` 跨度上限 90 天，防大范围慢聚合；`by_day` 按 UTC 天分组（与 `trace_daily_stats` 一致）。

## 8. 配额控制

### 8.1 配额模型（P1：每日 token 上限）

- 配额挂在 API 用户上：`quotaTokens`（**每日** token 上限，null/0 = 不限）、`quotaWindowStart`（当前日窗口起点）、`quotaConsumedTokens`（当日已消耗）。
- 日窗口按 **UTC 0 点** 划分（与 `trace_daily_stats` 口径一致）。
- 不做 TOTAL / MONTH 窗口——P2 如需按生命周期/月度统计，复用同一字段扩展窗口类型即可（`quotaWindow` 字段暂不引入）。

### 8.2 入口检查（`ApiUserQuotaService.checkQuota(userId)`）

在 Agent Run 入口（`AgentRunWebServiceImpl.call/trigger`、`llmCall`、`AgentSessionWebServiceImpl` 创建会话前）调用：

1. 读 API 用户；`quotaTokens` 为 null/0 → 放行；
2. 懒重置：当前时间已越过 `quotaWindowStart + 1 天`（或 UTC 日期已变）→ 重置 `quotaConsumedTokens=0` 并更新 `quotaWindowStart`；
3. `quotaConsumedTokens >= quotaTokens` → 抛 `QuotaExceededException`（HTTP 429，错误码 `QUOTA_EXCEEDED`）。

会话场景：HTTP 层只在创建会话时检查一次；**每轮 turn 起点（会话 worker 内、Agent 执行前）复查**，避免长会话创建后持续消耗越过配额。

### 8.3 消耗记账（唯一写点）

- **唯一记账点：trace 写入点**（LLM 调用完成、trace 落库时，按 `traceId` 幂等 `$inc` 到 `User.quotaConsumedTokens`）。**run 完成路径（`AgentRunBuilder.updateRunStatus`）不再单独记账**——AGENT 型 run 的最后一次 LLM call 已包含在 trace 写点中，避免同一笔消耗被 `$inc` 两次（v2 修正原设计双写点重复计数风险）。
- 会话每轮 LLM 完成后的 trace 写入点同样 `$inc`（同一写点逻辑，天然幂等）。
- 并发下可能出现短暂超卖（多个并发 run 同时通过入口检查）——P1 接受，文档注明；如后续需要硬限制，可在 LLM call 层逐次 `$inc` 校验。

### 8.4 对账

- `quotaConsumedTokens` 是增量计数，可能与 traces 真实消耗漂移（例如 run 失败回滚、跨窗口边界）。
- 新增每日对账 job：从 `traces` 按 `user_id` 重算当日/当月消耗并校正计数器（复用 `TraceDailyMaintenanceJob` 的聚合能力）。

## 9. 迁移与索引

新增 `SchemaMigrationVApiKeys`（version `20260804001`，注册到 `SchemaMigrationManager.operationalMigrations()`；当前最高版本为 `20260731001`）：

```java
// api_keys：key 哈希唯一 + 按用户/scope/过期时间查询
mongo.createIndex("api_keys", Indexes.ascending("key_hash"), new IndexOptions().unique(true));
mongo.createIndex("api_keys", Indexes.ascending("user_id", "scope"));
mongo.createIndex("api_keys", Indexes.ascending("expires_at"));

// users：API 用户幂等键（owner_id + external_id 唯一，partial）
var partial = new IndexOptions().unique(true)
        .partialFilterExpression(Filters.and(Filters.type("owner_id", "string"), Filters.type("external_id", "string")));
mongo.createIndex("users", Indexes.ascending("owner_id", "external_id"), partial);

// traces：用量查询（user_id + created_at），若 SchemaMigrationVTraceListFilterIndexes 已覆盖则跳过
mongo.createIndex("traces", Indexes.ascending("user_id", "created_at"));
```

注意：

- **必须用 partial index，不能用 sparse**：core-ng `EntityEncoder` 会把 null 字段显式写入文档，sparse 唯一索引会收录 `owner_id=null` 的存量 internal 用户，导致唯一冲突；`Filters.type(..., "string")` 只索引 ownerId/externalId 均为字符串的 API 用户。
- 幂等创建流程：按 `(owner_id, external_id)` 查询 → 不存在则 insert → 捕获 `DuplicateKeyException` 后重查返回既有记录（并发安全）。
- `api_keys.expires_at` 索引可后续配 TTL（如 90 天后物理删除），或由维护 job 清理 `revoked/expired` 超过保留期的 key。
- 已有 `traces` 索引请确认 `user_id` 与 `created_at` 的复合索引是否存在（`SchemaMigrationVTraceListFilterIndexes` 已有部分），缺少则在本迁移中补充。

## 10. 改造清单（按文件）

### core-ai-api（新增 DTO + 接口）

```text
ai/core/api/server/apiuser/
  AdminApiUserWebService.java     // admin：创建 manager 用户/列表/轮换密钥/启停
  ApiUserWebService.java          // 管理侧：API 用户增查、quota、usage、key 签发/续期/过期
  request/CreateApiUserRequest.java
  request/UpdateApiUserConfigRequest.java
  request/UsageQueryRequest.java
  request/CreateKeyRequest.java
  request/RenewKeyRequest.java
  response/ApiUserView.java
  response/ApiUserQuotaView.java
  response/UsageView.java
  response/DailyUsageView.java
  response/CreateKeyResponse.java
  response/RenewKeyResponse.java
  response/ListKeysView.java
  response/AdminApiUserView.java
  response/CreateApiUserResponse.java
```

### core-ai-server

```text
domain/User.java                              // + userType/ownerId/externalId/permissions/quota 字段
domain/ResourcePermission.java                // 新内嵌对象（resourceType/resourceId）
domain/ApiKey.java                            // 新实体（含 scope/metadata）
domain/migration/SchemaMigrationVApiKeys.java // 新迁移（v20260804001）
web/auth/AuthContext.java                     // + keyId
web/auth/RequestAuthenticator.java            // + ctk_/cmk_ 分支、authenticateFromApiKeyRecord()
web/auth/AuthInterceptor.java                 // /api/api-users/* 与 /api/admin/api-users 分支
apiuser/ApiUserService.java                   // 创建/查询 API 用户、权限与 quota 配置、幂等逻辑
apiuser/ApiUserKeyService.java                // key 签发/续期/过期/列表、sha256
apiuser/ApiUserQuotaService.java              // checkQuota、recordUsage、窗口懒重置
apiuser/ApiUserUsageService.java              // traces 聚合用量
apiuser/PermissionService.java                // check(userId, resourceType, resourceId)；未来权限体系入口
apiuser/AdminApiUserWebServiceImpl.java
apiuser/ApiUserWebServiceImpl.java
ApiUserModule.java                            // bind + api().service(...)，注册进 ServerApp
ServerApp.java                                // registerMongo 增加 ApiKey.class；加载 ApiUserModule
session/AgentSessionManager.java              // createSession/createSessionFromAgent 增加 apiKeyId 参数
session/ChatMessageService.java               // SessionMeta.of 增加 apiKeyId（或调用方透传）
```

### 配置

`sys.properties`：`sys.api-user.key.default.ttl=3600`、`sys.api-user.key.max.ttl=604800`。

## 11. 安全性考量

1. **密钥存储**：`cmk_` / `ctk_` 均只存 SHA-256 哈希，明文仅创建/轮换/签发时返回一次；所有列表接口不返回明文。
2. **租户隔离**：管理接口强制校验资源归属（`ownerId == 当前 manager userId`），一个业务系统无法操作另一个业务系统的 API 用户/key；manager 被禁用时其名下所有临时 key 认证同步失败。
3. **不可登录**：API 用户无密码 + `AuthService.login` 显式拒绝 + 非真实邮箱 id（不触发 Azure AD 自动建号）。
4. **无管理权限**：API 用户 `role=user`，所有 admin 接口的 `requireAdmin` 检查天然拒绝；`/api/user/api-key` 自助接口对 API 用户返回 403，防其自建永续 key 绕过 TTL。
5. **临时 key 校验**：认证时校验 `status=active` + `expiresAt` + 归属用户与 owner 用户 `status=active`；过期懒更新为 `expired`；`revoked`/`expired` 不可续期。
6. **资源权限**：API 用户默认无任何资源权限（白名单空 = 拒绝），Agent Run 入口统一走 `PermissionService.check`；权限挂在用户上，不随 key 变化，禁用用户即收回全部权限。
7. **配额保护**：入口检查阻止超额调用；唯一写点 + 每日对账校正计数器。
8. **审计**：`ApiKey.lastUsedAt`、`revokedAt` 全量记录；run/trace 全链路按 userId 可追溯。
9. **性能注记**：`updateLastUsed(k)` 采用节流更新（距上次更新 > 5 分钟才写库，或仅 `$set` 单字段），避免每次认证一次 Mongo 写放大；`updateLastLogin` 的整文档 replace 仅存在于既有 Azure AD 路径，不做扩展。

## 12. 与既有概念的关系

| 概念 | 本设计 | 既有 | 关系 |
| --- | --- | --- | --- |
| 认证 | `ctk_` 临时 key / `cmk_` 管理密钥 | `coreai_`/`cai_` 用户 apiKey | 并存；`RequestAuthenticator` 按前缀分流，统一解析为 userId |
| 业务系统 | manager 型 API 用户（无独立实体） | `Channel` / `OcgConfig`（渠道） | Channel 面向 IM 平台 webhook 接入，API 用户面向后端 API 接入，二者不混用 |
| 用户类型 | `userType=internal/api` | `User.role`（user/admin） | role 管权限，userType 管身份形态（可登录 vs 仅 API），正交 |
| 会话归属 | `source=api` + `apiKeyId` | `ChatSession.source` / `apiKeyId` 预留字段 | 本期接上预留字段，两条创建路径都接入 |
| 资源权限 | `User.permissions`（ResourcePermission 白名单） | `DatasetPermission`（agent 配置内）、`ToolPermissionStore`（工具级） | 本期新增统一资源权限模型；既有 dataset/tool 权限是 agent 内部机制，暂不打通，P2 统一 |
| 消耗查询 | 管理侧 `usage` | admin `analytics/by-user` | 同一 traces 数据源，不同授权视角 |
| 配额 | API 用户级 | 无 | 新增能力 |

## 13. 非目标与后续演进

- P2：按 key 的独立配额、按 Agent 的配额。
- P2：余额 / 充值 / 按次计费 / 发票。
- P2：API 用户用量查询的 `analytics_daily_stats` 预聚合版（本期实时聚合 traces 已够用）。
- P2：管理侧 webhook 通知（如额度阈值告警、key 到期告警）。
- P2：`api_keys` TTL 清理 job 与归档。
- P2：API 用户会话的 `source=api` 在 analytics 中单独维度统计。
- P2：**权限管理体系统一化**——`ResourcePermission` 模型扩展到 internal 用户（现有「任何已认证用户可调任意 Agent」行为收敛为显式授权）、工具/数据集/技能资源类型、基于角色的聚合（Role → permissions）与授权审计。
- 不建议：把管理密钥做成永不过期且不轮换——admin 侧已提供 `rotate-key`。

## 14. 评审待确认（v2）

按评审意见已决议：

1. ✅ **概念抽象**：移除 merchant/partner，统一为 `userType=api`（方案 A：业务系统 = manager 型 API 用户，不新增实体）。
2. ✅ **路径前缀**：管理面 `/api/api-users/*`，admin 面 `/api/admin/api-users/*`。
3. ✅ **TTL 默认 1h、上限 7d**（`sys.api-user.key.*` 可配）。
4. ✅ **P1 配额收敛为每日 token 上限**：固定按 UTC 日窗口，不做 TOTAL/MONTH；未来扩展时复用同一字段加窗口类型即可。
5. ✅ **资源权限提入 P1 并升级为用户级模型**：外部系统用户默认可调全部 Agent 风险偏大；`ResourcePermission`（resourceType/resourceId 白名单）挂在 `User.permissions`，key 不再携带白名单（v2.1 修正：原 `allowedAgentIds` 挂在 key 上，权限应随用户走，换 key 不丢权限、禁用用户即收回权限）。P1 仅支持 `agent` 资源类型的具体 id 列表（不含通配）。
6. ✅ **key metadata 提入 P1**（`Trace.metadata` 已有，透传成本低、业务检索价值高）。
7. ✅ **配额唯一写点**（trace 写入点按 traceId 幂等；run 完成路径不记账）。
8. ✅ **权限与额度配置合并为 `PUT /api/api-users/:userId/config`**（字段可选部分更新；permissions 全量覆盖、input/output token_quota 为每日上限，单位 token）。
9. ✅ **会话两条创建路径均接 `source=api` + `apiKeyId`**。
10. ✅ **认证级联校验 owner 状态**（manager 禁用后名下临时 key 立即失效）。
11. ✅ **幂等唯一索引用 partial 而非 sparse**（core-ng 显式写 null 字段）。

剩余待确认：

1. 会话场景「每轮 turn 起点复查配额」落在哪个执行点（建议：`SessionCommand` 处理 / Agent 执行前），需要与会话 worker 现状对齐。
2. 是否需要在 admin 侧提供「查看某业务系统全部 API 用户用量汇总」的只读接口（P1 可选，P2 兜底）。
3. 管理密钥是否需要独立的被动过期策略（当前建议无 TTL、靠 admin 轮换）。
4. **权限检查范围**：`PermissionService.check` 是否只约束 API 用户（internal 用户维持现状），还是 P1 就对 internal 用户也生效（影响既有行为，建议 P1 仅 API 用户，P2 统一）。
5. **`config` 接口的 permissions 语义**：当前设计为全量覆盖（空数组 = 清空权限）；如需增量（add/remove）接口再行扩展。
