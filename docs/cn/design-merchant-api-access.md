# Tech Design：业务系统临时 Key 接入 core-ai-server

> 状态：待评审
>
> 日期：2026-07-31
>
> 目标版本：P1（业务系统通过临时 API Key 调用 Agent Run API）

## 1. 背景与目标

外部业务系统（Partner）需要让它的 merchant 用户在自己的产品里使用 core-ai 的 Agent 能力。merchant 不是 core-ai 的注册用户，也不应该能登录 core-ai Web UI。因此需要一套「后端对后端」的集成接口，让业务系统后端为 merchant 申请临时访问凭证。

核心目标：

1. 业务系统后端通过管理接口完成 merchant 用户与临时 key 的全生命周期管理：
   - 创建 merchant 对应的用户（**不能在 core-ai-server 登录**）；
   - 为 merchant 用户申请临时 key；
   - 随时查询 merchant 用户的 token 消耗；
   - 配置 merchant 用户的 token 额度；
   - 续期 / 过期一个 key。
2. merchant 客户端（业务系统前端或后端代持）用临时 key 调用 core-ai-server 的 Agent Run API，key 定时过期。
3. 复用既有链路：认证（`RequestAuthenticator`）、trace/token 记账、Analytics 聚合、会话 `apiKeyId` 预留字段、Agent Run API。

非目标：

- merchant 登录 core-ai UI、使用 Azure AD、注册账号。
- 管理 core-ai 资源（Agent 定义、工具、设置、用户）。
- 提供 merchant 独立的 OpenAI-compatible gateway 代理入口（沿用现有 `/api/agents/:id/call`、`/api/runs/agent/:id/trigger`、`/api/sessions`）。
- 本期做按次计费/余额/发票，只做 token 消耗查询与额度控制。
- 本期做 key 级别的独立配额（配额按 merchant 用户维度，见第 8 章）。

## 2. 现状与缺口

### 2.1 已有可复用能力

- 认证链：`RequestAuthenticator`（Azure AD 头 → `users.api_key`）已支持 `Bearer coreai_*` / `Bearer cai_*`；`AuthInterceptor` 统一拦截 `/api/*`；SSE 复用同一 authenticator（`SseAuthInterceptor`）。
- 用户模型：`User`（`users` collection，id=email），已有 `role`（user/admin）、`status`（pending/active）、`apiKey` 字段，以及 `AuthService.login/register`、admin 用户管理接口。
- 消耗追踪：`Trace`/`Span` 按 `userId` 记账；`trace_daily_stats`、`analytics_daily_stats` 按日预聚合；`TraceService` 已有按 userId 查询能力。
- Agent 调用：`POST /api/agents/:id/call`（同步）、`POST /api/runs/agent/:id/trigger`（异步）、`POST /api/sessions`（会话 + SSE）。这些接口只要求「已认证用户」，拿到 userId 即可运行任意 Agent。
- `ChatSession.apiKeyId` 字段与 `SessionMeta.apiKeyId` 是**预留字段**（注释写明 "set only when source=api"），目前没有代码填充——正是本设计要接上的点。
- Schema 迁移框架：`SchemaMigrationManager` + `SchemaMigrationV*`。

### 2.2 必须补齐的缺口

| 层 | 现状 | P1 改动 |
| --- | --- | --- |
| 用户模型 | `User` 没有「不可登录的 merchant 用户」概念 | 新增 `userType`（internal/merchant）+ `partnerId` + `externalMerchantId`；merchant 用户无密码、非 email id、不进入 Azure AD 自动建号 |
| 凭证 | 每用户仅一个永续 `apiKey`（`users.api_key`），无 TTL、无多 key | 新增 `api_keys` collection：一个 merchant 可有多把临时 key，各有 `expiresAt`/状态 |
| 合作方 | 无「业务系统」主体与其管理密钥 | 新增 `partners` collection + 长期 `cpp_` 管理密钥 |
| 认证 | `RequestAuthenticator` 只认 `coreai_*`/`cai_*` → `users.api_key` | 增加 `ctk_*` → `api_keys` 查询 → 解析为 merchant userId；`cpp_*` → `partners` 查询 → partnerId |
| 上下文 | `AuthContext` 只有 `auth.userId` | 增加 `auth.partnerId` 与 `auth.keyId`（临时 key id，供会话 `apiKeyId` 使用） |
| 管理 API | 无 | 新增 partner 管理 WebService（merchant 增查、key 签发/续期/过期、quota 配置、用量查询）与 admin 侧 partner 管理 |
| 配额 | 无 | merchant 用户 quota 配置 + 窗口内消耗计数 + Agent Run 入口检查 |

## 3. 总体架构

```text
                    +---------------------------+
                    |  core-ai admin UI / API   |--- 1. 创建 partner（admin，一次性返回 cpp_ 管理密钥）
                    +---------------------------+
                              |
                              v
+-------------------+    2. 提供 cpp_ 管理密钥    +-------------------------------+
| 业务系统后端        | <------------------------> |  core-ai-server               |
| (Partner Backend) |   3. POST /api/partner/merchants           创建 merchant 用户 |
|                   |   4. POST /api/partner/merchants/:userId/keys  签发临时 key  |
|                   |   5. PUT /api/partner/merchants/:userId/quota  配置额度      |
|                   |   6. GET /api/partner/merchants/:userId/usage   查消耗      |
|                   |   7. POST /api/partner/keys/:keyId/renew|expire 续期/过期    |
+-------------------+                              +-------------------------------+
        | 8. 返回临时 key（ctk_xxx，TTL）
        v
+-------------------+
| merchant 客户端     |  9. Authorization: Bearer ctk_xxx
+-------------------+        |
                             v
                  +-------------------------------+
                  |  AuthInterceptor                |
                  |  RequestAuthenticator           |
                  |   ctk_ -> api_keys -> userId    |
                  +-------------------------------+
                             | userId = merchant 用户
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

1. core-ai admin 创建 partner，拿到一次性 `cpp_` 管理密钥，交给业务系统后端。
2. merchant 在业务系统点击需 Agent 的功能 → 业务系统后端用 `cpp_` 调管理接口：先 `POST /api/partner/merchants`（幂等，按业务侧 merchantId）创建/复用商户用户。
3. 业务系统后端 `POST /api/partner/merchants/:userId/keys` 申请临时 key（指定 TTL），拿到 `ctk_xxx`。
4. 业务系统把临时 key 下发给 merchant 客户端（或后端代持），客户端带 `Authorization: Bearer ctk_xxx` 调用 Agent Run API。
5. core-ai 认证链把 `ctk_` 解析为 merchant userId；配额检查在 Agent Run 入口执行；trace 按该 userId 记账，复用全部 Analytics 管道。
6. 临时 key 到期自动失效；业务系统后端可续期或立即过期；可随时查询消耗、调整额度。

## 4. 数据模型

### 4.1 `User`（扩展既有实体，`users` collection）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String (@Id) | 普通用户 = email；merchant 用户 = `merchant:{partnerId}:{externalMerchantId}`（确定性 id，幂等且不与 email 冲突） |
| `userType` | String (新) | `internal`（默认，既有用户）\| `merchant` |
| `partnerId` | String (新) | merchant 用户归属的 partner id |
| `externalMerchantId` | String (新) | 业务系统侧的 merchant id（partner 幂等键） |
| `passwordHash` | String | merchant 用户恒为 null → 无法通过密码登录 |
| `quotaTokens` | Long (新) | 配额窗口 token 上限；null/0 = 不限 |
| `quotaWindow` | String (新) | `TOTAL` \| `DAY` \| `MONTH` |
| `quotaWindowStart` | ZonedDateTime (新) | 当前窗口起点（懒重置） |
| `quotaConsumedTokens` | Long (新) | 窗口内已消耗计数（$inc） |
| `status` | String | merchant 用户创建即 `active`，否则临时 key 校验不通过 |

登录限制（三重保证）：

1. `passwordHash = null`，`AuthService.login` 密码校验必然失败；
2. `AuthService.login` 显式拒绝 `userType=merchant`；
3. Azure AD 自动建号路径按 `X-Auth-Request-Email` 头创建用户，merchant id 不含 `@`，不会触发。

实现注意（core-ng 约束）：`userType` 带默认值 `"internal"`，实体字段必须加 `@NotNull` 注解；quota 相关字段为可空 Long（`Long` 而非 `long`），无需默认值。

### 4.2 `Partner`（新实体，`partners` collection）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String (@Id) | `p_` + uuid |
| `name` | String | 业务系统名称 |
| `apiKeyHash` | String | SHA-256(管理密钥)，**仅创建时明文返回一次** |
| `apiKeyPrefix` | String | `cpp_` + 前 8 位，便于展示 |
| `status` | String | `active` \| `disabled` |
| `contact` | String | 联系方式（可选） |
| `createdAt` | ZonedDateTime | |
| `lastUsedAt` | ZonedDateTime | |
| `createdBy` | String | core-ai admin userId |

管理密钥格式：`cpp_` + base64url(32 随机字节)。库中只存 SHA-256 哈希（比既有 `users.api_key` 明文存储更安全，新特性采用哈希）。

### 4.3 `ApiKey`（新实体，`api_keys` collection，临时 key）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | String (@Id) | `k_` + uuid（管理接口用 keyId 引用） |
| `keyHash` | String | SHA-256(临时 key) |
| `keyPrefix` | String | `ctk_` + 前 8 位 |
| `userId` | String | 归属 merchant userId |
| `partnerId` | String | 签发方 partner |
| `allowedAgentIds` | List\<String\> | 可选：允许调用的 Agent id 白名单；空 = 全部 |
| `status` | String | `active` \| `revoked` \| `expired` |
| `expiresAt` | ZonedDateTime | 到期时间（认证时校验） |
| `createdAt` / `lastUsedAt` / `revokedAt` | ZonedDateTime | 审计 |

临时 key 格式：`ctk_` + base64url(32 随机字节)。认证时：`ctk_` 前缀 → SHA-256 → 查 `api_keys.key_hash`。

状态机：`active` →（到期校验时懒更新 `expired`）或 →（手动 `revoked`）。`expired`/`revoked` 均不可续期；续期仅作用于 `active` 且 `expiresAt > now` 的 key。

## 5. API 设计

### 5.1 Admin 侧（core-ai 管理端，需 `admin` 角色）— `AdminPartnerWebService`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/partners` | 创建 partner，返回 `{partner_id, api_key}`（明文一次性） |
| GET | `/api/admin/partners` | 列表 |
| POST | `/api/admin/partners/:id/rotate-key` | 轮换管理密钥，返回新 `api_key`（旧密钥立即失效） |
| POST | `/api/admin/partners/:id/update-status` | 启用 / 禁用 |

### 5.2 Partner 管理侧（业务系统后端调用，`Authorization: Bearer cpp_*`）— `MerchantWebService`

| 方法 | 路径 | 请求 → 响应 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/partner/merchants` | `CreateMerchantRequest{external_merchant_id, name}` → `MerchantView{merchant_id, external_merchant_id, name, status, quota}` | 幂等创建/复用商户用户（按 partnerId + externalMerchantId） |
| GET | `/api/partner/merchants/:userId` | → `MerchantView` | 查 merchant 详情（含额度、当前窗口消耗） |
| PUT | `/api/partner/merchants/:userId/quota` | `UpdateQuotaRequest{token_quota, quota_window}` → `MerchantView` | 配置 token 额度（TOTAL/DAY/MONTH） |
| GET | `/api/partner/merchants/:userId/usage` | `UsageQueryRequest{from, to}` → `UsageView{total_tokens, input_tokens, output_tokens, cached_tokens, cost_usd, call_count, by_day[]}` | 按时间范围查 token 消耗 |
| POST | `/api/partner/merchants/:userId/keys` | `CreateKeyRequest{ttl_seconds, allowed_agent_ids?}` → `CreateKeyResponse{key_id, key, expires_at, allowed_agent_ids}` | 签发临时 key（明文一次性返回） |
| GET | `/api/partner/merchants/:userId/keys` | → `ListKeysView{keys[]}` | 列出该 merchant 的 key（不含明文） |
| POST | `/api/partner/keys/:keyId/renew` | `RenewKeyRequest{ttl_seconds}` → `RenewKeyResponse{key_id, expires_at}` | 续期（仅 active 且未到期） |
| POST | `/api/partner/keys/:keyId/expire` | → void | 立即过期（revoked） |

约束：

- 所有 partner 管理接口做**归属校验**：路径里的 `userId` / `keyId` 必须属于当前认证的 partner，否则 403。
- `ttl_seconds` 默认 3600（1 小时），上限取 `sys.partner.key.max.ttl`（默认 7 天，可配）。

### 5.3 Agent 调用侧（merchant 临时 key）— 复用现有 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agents/:id/call` | 同步调用（LLM_CALL 直连 / AGENT 轮询） |
| POST | `/api/runs/agent/:id/trigger` | 异步触发 |
| GET | `/api/runs/:id` | 查询 run 结果 |
| POST | `/api/sessions` + `/api/sessions/:id/messages/stream` | 多轮会话 + SSE 流式 |

- 认证：`Authorization: Bearer ctk_xxx` → merchant userId，随后链路与普通用户一致。
- 会话场景：`AgentSessionManager.createSessionFromAgent(...)` 创建会话时设置 `SessionMeta.apiKeyId = AuthContext.keyId()`、`source = "api"`，接上预留的 `ChatSession.apiKeyId` 字段。
- 临时 key 若配置了 `allowedAgentIds`，在 `AgentRunWebServiceImpl.call/trigger` 与 `AgentSessionWebServiceImpl` 创建会话前校验 `agentId ∈ allowedAgentIds`。

## 6. 认证与授权改造

### 6.1 `RequestAuthenticator` 扩展

```java
private String authenticateFromApiKey(Request request) {
    var auth = request.header("Authorization");
    if (auth.isEmpty()) return null;
    var value = auth.get();

    if (value.startsWith("Bearer ctk_")) {                 // 新增：merchant 临时 key
        return authenticateFromTempKey(value.substring(7));
    }
    if (value.startsWith("Bearer cpp_")) {                 // 新增：partner 管理密钥（仅 /api/partner/* 走）
        return null;   // 由 AuthInterceptor 的 partner 分支处理
    }
    if (!value.startsWith("Bearer coreai_") && !value.startsWith("Bearer cai_")) return null;
    // ... 既有 users.api_key 逻辑不变
}

private String authenticateFromTempKey(String key) {
    var hash = sha256(key);
    var apiKey = apiKeyCollection.findOne(Filters.eq("key_hash", hash));
    if (apiKey.isEmpty()) throw new UnauthorizedException("invalid api key");

    var k = apiKey.get();
    if (!"active".equals(k.status)) throw new UnauthorizedException("api key is not active");
    if (k.expiresAt.isBefore(ZonedDateTime.now())) {
        updateKeyStatus(k, "expired");                     // 懒更新
        throw new UnauthorizedException("api key expired");
    }
    var user = userCollection.get(k.userId);
    if (user.isEmpty() || !"active".equals(user.get().status)) {
        throw new UnauthorizedException("merchant account disabled");
    }
    updateLastUsed(k);                                     // 审计 lastUsedAt
    return k.userId;                                       // 下游全部按 merchant userId 记账
}
```

partner 分支（`/api/partner/*` 专用）：

```java
public String authenticatePartner(Request request) {
    var auth = request.header("Authorization");
    if (auth.isEmpty()) return null;
    var value = auth.get();
    if (!value.startsWith("Bearer cpp_")) return null;

    var hash = sha256(value.substring(7));
    var partner = partnerCollection.findOne(Filters.eq("api_key_hash", hash));
    if (partner.isEmpty()) throw new UnauthorizedException("invalid partner key");
    if (!"active".equals(partner.get().status)) throw new UnauthorizedException("partner disabled");
    updateLastUsed(partner.get());
    return partner.get().id;
}
```

### 6.2 `AuthContext` / `AuthInterceptor`

- `AuthContext` 增加常量与访问器：`PARTNER_ID_KEY = "auth.partnerId"`、`KEY_ID_KEY = "auth.keyId"`，以及 `partnerId(WebContext)` / `keyId(WebContext)`。
- `AuthInterceptor.intercept`：
  - 路径以 `/api/partner/` 开头 → `requestAuthenticator.authenticatePartner(request)` → 把 `partnerId` 放入 context；**不设置 userId**；
  - 其他 `/api/*` → 既有 `authenticate(request)`；当 `ctk_` 命中时，把临时 key 的 `keyId` 也放入 context（供会话 `apiKeyId` 使用）。
- partner 管理接口的 controller 通过 `AuthContext.partnerId(webContext)` 取当前 partner，并做资源归属校验。
- `SseAuthInterceptor` 无需改动：SSE 只用临时 key（`ctk_`），走 `authenticateFromApiKey`。

### 6.3 登录限制

- `AuthService.login`：查库后若 `userType=merchant` → 直接抛 `UnauthorizedException`（"merchant account cannot login"）。
- `UserService` 自助接口（`/api/user/api-key`、`/api/user/change-password`）：对 `userType=merchant` 返回 403，防止 merchant 用户自建永续 key 绕过 TTL。
- Azure AD 自动建号：`ensureUser` 仅处理 email 形式的 userId，merchant id（`merchant:...`）不会进入该路径。

## 7. 用量查询

`GET /api/partner/merchants/:userId/usage?from=..&to=..`：

- 实时口径：聚合 `traces`（`user_id = :userId`，`created_at ∈ [from, to]`），`$sum` input/output/cached/total tokens 与 `cost_usd`，`$count` call_count，并按天 `$group` 输出 `by_day`。
- 复用 `TraceService`/`TraceController` 的聚合逻辑（新建 `MerchantUsageService` 调用同一底层聚合，避免重复实现）。
- 该接口返回给业务系统后端的即为「merchant 用户的 token 消耗」，与 admin 侧 `GET /api/admin/analytics/by-user` 口径一致（都来自 traces）。
- `MerchantView` 里的 `quota_consumed` 则来自 `User.quotaConsumedTokens` 计数器（实时，用于配额判断），两者口径说明见第 8 章。

## 8. 配额控制

### 8.1 配额模型

- 配额挂在 merchant 用户上：`quotaTokens`（窗口内 token 上限）、`quotaWindow`（TOTAL / DAY / MONTH）。
- 配额窗口起点 `quotaWindowStart`：TOTAL 为创建时间（永不重置）；DAY 为当日 0 点；MONTH 为当月 1 日 0 点。

### 8.2 入口检查（`MerchantQuotaService.checkQuota(userId)`）

在 Agent Run 入口（`AgentRunWebServiceImpl.call/trigger`、`AgentSessionWebServiceImpl` 创建会话前）调用：

1. 读 merchant 用户；`quotaTokens` 为 null/0 → 放行；
2. 懒重置：若当前时间已越过 `quotaWindowStart + window` → 重置 `quotaConsumedTokens=0` 并更新 `quotaWindowStart`；
3. `quotaConsumedTokens >= quotaTokens` → 抛 `QuotaExceededException`（HTTP 429，错误码 `QUOTA_EXCEEDED`）。

### 8.3 消耗记账

- run / 会话完成时，从 `AgentRun.tokenUsage`（run 完成）或 LLM call 的 response usage（会话每轮）`$inc` 到 `User.quotaConsumedTokens`。
- 写点：`AgentRunBuilder.updateRunStatus`（run 完成）与会话每轮 LLM 完成后的 trace 写入点。
- 并发下可能出现短暂超卖（多个并发 run 同时通过入口检查）——P1 接受，文档注明；如后续需要硬限制，可在 LLM call 层逐次 `$inc` 校验。

### 8.4 对账

- `quotaConsumedTokens` 是增量计数，可能与 traces 真实消耗漂移（例如 run 失败回滚、跨窗口边界）。
- 新增每日对账 job：从 `traces` 按 `user_id` 重算当日/当月消耗并校正计数器（复用 `TraceDailyMaintenanceJob` 的聚合能力）。

## 9. 迁移与索引

新增 `SchemaMigrationVMerchantApiKeys`（version `20260731002`，注册到 `SchemaMigrationManager.operationalMigrations()`）：

```java
// api_keys：key 哈希唯一 + 按用户/partner/过期时间查询
mongo.createIndex("api_keys", Indexes.ascending("key_hash"), new IndexOptions().unique(true));
mongo.createIndex("api_keys", Indexes.ascending("user_id"));
mongo.createIndex("api_keys", Indexes.ascending("partner_id", "status"));
mongo.createIndex("api_keys", Indexes.ascending("expires_at"));

// partners：管理密钥唯一
mongo.createIndex("partners", Indexes.ascending("api_key_hash"), new IndexOptions().unique(true));

// users：merchant 幂等键（partner_id + external_merchant_id 唯一，sparse）
var sparse = new IndexOptions().unique(true).sparse(true);
mongo.createIndex("users", Indexes.ascending("partner_id", "external_merchant_id"), sparse);

// traces：用量查询（user_id + created_at）
mongo.createIndex("traces", Indexes.ascending("user_id", "created_at"));
```

注意：

- `users.partner_id + external_merchant_id` 唯一索引必须 `sparse`，避免存量普通用户（两字段为 null）冲突。
- `api_keys.expires_at` 索引可后续配 TTL（如 90 天后物理删除），或由维护 job 清理 `revoked/expired` 超过保留期的 key。
- 已有 `traces` 索引请确认 `user_id` 与 `created_at` 的复合索引是否存在（`SchemaMigrationVTraceListFilterIndexes` 已有部分），缺少则在本迁移中补充。

## 10. 改造清单（按文件）

### core-ai-api（新增 DTO + 接口）

```text
ai/core/api/server/partner/
  AdminPartnerWebService.java     // admin：创建/列表/轮换/启停 partner
  MerchantWebService.java         // partner 管理：merchant 增查、quota、usage、key 签发/续期/过期
  request/CreateMerchantRequest.java
  request/UpdateQuotaRequest.java
  request/UsageQueryRequest.java
  request/CreateKeyRequest.java
  request/RenewKeyRequest.java
  response/MerchantView.java
  response/MerchantQuotaView.java
  response/UsageView.java
  response/DailyUsageView.java
  response/CreateKeyResponse.java
  response/RenewKeyResponse.java
  response/ListKeysView.java
  response/AdminPartnerView.java
  response/CreatePartnerResponse.java
```

### core-ai-server

```text
domain/User.java                              // + userType/partnerId/externalMerchantId/quota 字段
domain/Partner.java                           // 新实体
domain/ApiKey.java                            // 新实体
domain/migration/SchemaMigrationVMerchantApiKeys.java   // 新迁移
web/auth/AuthContext.java                     // + partnerId/keyId
web/auth/RequestAuthenticator.java            // + ctk_/cpp_ 分支、authenticatePartner()
web/auth/AuthInterceptor.java                 // /api/partner/* 分支
partner/PartnerService.java                   // admin 侧 partner CRUD、密钥生成/轮换
partner/MerchantService.java                  // 创建/查询 merchant 用户、quota 配置
partner/MerchantKeyService.java               // key 签发/续期/过期/列表、sha256
partner/MerchantQuotaService.java             // checkQuota、recordUsage、窗口懒重置
partner/MerchantUsageService.java             // traces 聚合用量
partner/AdminPartnerWebServiceImpl.java
partner/MerchantWebServiceImpl.java
PartnerModule.java                            // bind + api().service(...)，注册进 ServerApp
ServerApp.java                                // registerMongo 增加 Partner.class、ApiKey.class；加载 PartnerModule
```

### 配置

`sys.properties`：`sys.partner.key.default.ttl=3600`、`sys.partner.key.max.ttl=604800`。

## 11. 安全性考量

1. **密钥存储**：`cpp_` / `ctk_` 均只存 SHA-256 哈希，明文仅创建/签发时返回一次；所有列表接口不返回明文。
2. **租户隔离**：partner 管理接口强制校验资源归属（`partnerId` 匹配），一个 partner 无法操作另一个 partner 的 merchant/key。
3. **不可登录**：merchant 用户无密码 + `AuthService.login` 显式拒绝 + 非真实邮箱 id（不触发 Azure AD 自动建号）。
4. **无管理权限**：merchant 用户 `role=user`，所有 admin 接口的 `requireAdmin` 检查天然拒绝；`/api/user/api-key` 自助接口对 merchant 用户返回 403，防其自建永续 key 绕过 TTL。
5. **临时 key 校验**：认证时校验 `status=active` + `expiresAt` + 归属用户 `status=active`；过期懒更新为 `expired`；`revoked`/`expired` 不可续期。
6. **配额保护**：入口检查阻止超额调用，防止 token 消耗失控；每日对账校正计数器。
7. **审计**：`ApiKey.lastUsedAt`、`revokedAt`、`Partner.lastUsedAt` 全量记录；run/trace 全链路可追溯。

## 12. 与既有概念的关系

| 概念 | 本设计 | 既有 | 关系 |
| --- | --- | --- | --- |
| 认证 | `ctk_` 临时 key | `coreai_`/`cai_` 用户 apiKey | 并存；`RequestAuthenticator` 按前缀分流 |
| 合作方 | `Partner`（`partners`） | `Channel` / `OcgConfig`（渠道） | 独立新概念；Channel 面向 IM 平台 webhook 接入，Partner 面向业务系统后端 API 接入，二者不混用 |
| 会话归属 | `source=api` + `apiKeyId` | `ChatSession.source` / `apiKeyId` 预留字段 | 本期接上预留字段 |
| 消耗查询 | partner 侧 `usage` | admin `analytics/by-user` | 同一 traces 数据源，不同授权视角 |
| 配额 | merchant 用户级 | 无 | 新增能力 |

## 13. 非目标与后续演进

- P2：按 key 的独立配额、按 Agent 的配额。
- P2：余额 / 充值 / 按次计费 / 发票。
- P2：merchant 用量查询的 `analytics_daily_stats` 预聚合版（本期实时聚合 traces 已够用）。
- P2：partner 侧的 webhook 通知（如额度阈值告警、key 到期告警）。
- P2：`api_keys` TTL 清理 job 与归档。
- P2：merchant 会话的 `source=api` 在 analytics 中单独维度统计。
- 不建议：把 partner 管理密钥做成永不过期且不轮换——admin 侧已提供 `rotate-key`。

## 14. 评审待确认

1. partner 管理 API 路径前缀 `/api/partner/*` 是否合适（替代方案 `/api/merchant/*`、`/api/biz/*`）。
2. `ttl_seconds` 默认 1 小时、上限 7 天是否合理。
3. merchant 用户 id 采用确定性拼接 `merchant:{partnerId}:{externalMerchantId}` 是否可接受（利于幂等，但 id 可被人为构造——不影响安全，因鉴权始终以 key 为准）。
4. 配额窗口默认 `DAY` 是否合适，还是默认 `TOTAL`（生命周期总额）。
5. 是否需要本期就支持 `allowedAgentIds` 白名单（涉及 `AgentRunWebServiceImpl` 与 `AgentSessionWebServiceImpl` 的额外校验）。
6. 是否需要在签发临时 key 时同时支持 `metadata`（业务系统自带上下文，如 merchant 名称、订单号）用于 trace 检索。
