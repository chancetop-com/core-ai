# Trigger System — Tech Design (v2)

## 1. 概述

在 core-ai-server 中增加 **Trigger（触发器）** 系统，作为一等公民的顶层功能模块。

触发器是"什么条件下做什么事"的抽象，通过类型区分触发方式，通过 Action 定义触发后的行为。目前支持 **Webhook** 和 **Schedule** 两种类型，未来可扩展。

页面导航结构变化：

```
（之前）                        （之后）
Tools                          Trigger
├── MCP                        ├── Webhook
├── API Tools                  └── Schedule (原 Scheduler)
Skills
Agent
Scheduler (独立)
```

## 2. 清理：删除旧的 Agent 级别 Webhook

旧机制存在于 `AgentDefinition.webhookSecret` 字段，通过 `enableWebhook/disableWebhook` API 控制，无实际作用。删除内容：

| 文件 | 操作 |
|---|---|
| `AgentDefinition.java` | 删除 `webhookSecret` 字段 |
| `AgentDefinitionView.java` | 删除 `webhookSecret` 字段 |
| `AgentDefinitionService.java` | 删除 `enableWebhook()`, `disableWebhook()` 方法 |
| `AgentDefinitionWebServiceImpl.java` | 删除对应接口实现 |
| `AgentDefinitionWebService.java` (core-ai-api) | 删除 `enableWebhook/disableWebhook` 接口定义 |
| `WebhookController.java` | 删除整个类 |
| `WebhookTriggerResponse.java` | 删除整个类 |
| `ServerModule.java` | 删除 `POST /api/webhooks/:agentId` 路由 |
| `AuthInterceptor.java` | 移除 `/api/webhooks/` 豁免路径 |
| `client.ts` (前端) | 删除 AgentDefinition 中的 `webhook_secret` 字段 |
| 前端 Agent 编辑页 | 删除 webhook 相关 UI |

## 3. 核心概念

### Trigger（触发器）

| 字段 | 说明 |
|---|---|
| id | 主键 (UUID) |
| userId | 创建者 |
| name | 名称 |
| description | 描述 |
| type | 类型枚举: `WEBHOOK`, `SCHEDULE` |
| enabled | 启用/禁用 |
| config | `Map<String, String>` — 类型特定配置（见下文） |
| actionType | Action 类型: `RUN_AGENT` |
| actionConfig | Action 参数: `{ "agent_id": "...", "input_template": "..." }` |
| createdAt / updatedAt | 审计时间戳 |

### 按类型的 config 设计

每种 Trigger type 有不同的配置字段，通过 `config` map 承载：

**WEBHOOK:**
```
{
  "secret": "whk_xxx",              // 自动生成，用于 Bearer Token 验证
  "allowed_methods": "POST",        // 可选，逗号分隔
  "verifier_type": "bearer",        // "bearer"(默认) | "slack"(P1)
  "slack_signing_secret": ""        // 仅 verifier_type=slack 时需要, 用户手动填入
}
```

> `sys.publicUrl` 配置项用于计算 `webhook_url`（如 `https://core-ai.example.com/api/webhook-triggers/{id}`），不在 trigger 实体中存储。

**SCHEDULE（从现有 AgentSchedule 迁移）：**
```
{
  "cron_expression": "0 0 * * *",
  "timezone": "Asia/Shanghai",
  "concurrency_policy": "SKIP_IF_RUNNING"
}
```

> `concurrency_policy` 枚举值：`SKIP_IF_RUNNING`（运行中则跳过）、`QUEUE`（排队）、`PARALLEL`（并发执行）

### Action（动作）

| Action 类型 | 说明 |
|---|---|
| **RUN_AGENT** | 运行一个已发布的 Agent（通过现有 AgentRunner） |
| (未来) FORWARD_AGENT | 转发给一个活跃的 Agent session |
| (未来) SEND_MESSAGE | 向聊天 session 发消息 |

**MVP 只做 `RUN_AGENT`**。

## 4. 数据模型

```java
@Collection(name = "triggers")
public class Trigger {
    @Id
    public String id;

    @NotNull @Field(name = "user_id")
    public String userId;

    @NotNull @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @NotNull @Field(name = "type")
    public TriggerType type;

    @NotNull @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "config")
    public Map<String, String> config;        // 类型特定配置

    @NotNull @Field(name = "action_type")
    public String actionType;

    @Field(name = "action_config")
    public Map<String, String> actionConfig;  // 动作参数

    @NotNull @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}

public enum TriggerType {
    @MongoEnumValue("WEBHOOK") WEBHOOK,
    @MongoEnumValue("SCHEDULE") SCHEDULE
}
```

## 5. Schedule 迁移

现有的 `AgentSchedule` 是独立实体，有独立的 CRUD 和调度器。迁移策略：

| 阶段 | 动作 |
|---|---|
| **Phase 1 (MVP)** | **Trigger 系统新建，AgentSchedule 保留不变**。Schedule 类型的 Trigger 先做 UI 层面的管理页面，暂不接入实际的定时调度逻辑。用户仍然通过旧 Scheduler 页面管理定时任务。 |
| **Phase 2** | 将 `AgentSchedulerJob` / `AgentScheduleService` 底层的定时调度能力统一到 Trigger 系统，废弃 `AgentSchedule` collection。 |

**MVP 的 Schedule Trigger 在 UI 上只是给一个"定时"类型的占位入口，实际调度能力由旧系统继续承担。**

### 旧 Scheduler 页面保留什么

- `AgentSchedule` 实体保留
- `AgentScheduleService` / `AgentSchedulerJob` 保留
- 页面**挪到** Triggers 导航下，改名为"Schedule"子页

这样用户感知上是 Triggers → Schedule，数据层面新旧共存。

## 6. API 设计

### CRUD API: `/api/triggers`

| Method | Path | 说明 | Auth |
|---|---|---|---|
| `GET` | `/api/triggers` | 列表（按 type 筛选） | 需要 |
| `POST` | `/api/triggers` | 创建 | 需要 |
| `GET` | `/api/triggers/:id` | 详情 | 需要 |
| `PUT` | `/api/triggers/:id` | 更新 | 需要 |
| `DELETE` | `/api/triggers/:id` | 删除 | 需要 |
| `POST` | `/api/triggers/:id/enable` | 启用 | 需要 |
| `POST` | `/api/triggers/:id/disable` | 禁用 | 需要 |
| `POST` | `/api/triggers/:id/rotate-secret` | 轮换 webhook secret | 需要 |

### Public Endpoint: `POST /api/webhook-triggers/:id`

接收外部平台（Slack / Discord / Telegram）的 Webhook 请求。

- **无 Auth**（在 `AuthInterceptor` 中豁免）
- 方法：支持 POST（也可通过 config 配置允许 GET）
- 验证：读取 trigger.config 中的 secret，检查 `Authorization: Bearer <secret>`
- 执行：`AgentRunner.run(agent, resolvedInput, TriggerType.WEBHOOK)`
- 响应：`{ "run_id": "...", "status": "RUNNING" }`

### Slack URL Verification Challenge 处理

Slack 配置 Event Subscription 时，会先发送一个 `url_verification` 请求来验证 endpoint 所有权，格式如下：

```json
// Slack → POST /api/webhook-triggers/{id}
{
  "token": "Jhj5dZrVaK7ZwHHjRyZWjbDl",
  "challenge": "3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P",
  "type": "url_verification"
}
```

Slack 期望响应 200 OK，body 原样返回 `challenge` 字段的值：

```json
// 我们 → 200
{ "challenge": "3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P" }
```

**TriggerController 必须优先处理这个请求**，在进入签名验证和 Action 执行之前就判断并返回。处理逻辑：

```java
public Response execute(Request request) {
    var bodyStr = bodyAsString(request);
    var payload = parseJson(bodyStr);

    // 1. Slack URL verification challenge
    if ("url_verification".equals(payload.get("type"))) {
        var challenge = (String) payload.get("challenge");
        return Response.text(JSON.toJSON(Map.of("challenge", challenge)));
    }

    // 2. 加载 trigger、验证签名、执行 Action（略）
}
```

> 注意：返回必须是 `text/plain` 或 `application/json`，Slack 两种都接受。我们统一返回 JSON。

此逻辑同样兼容 Discord 和 Telegram 的类似验证流程（Telegram 在设置 Webhook 时也会发送 `url_verification` 或要求在 GET 请求时返回特定内容）。

### Webhook 验证器体系（含 Slack 签名支持）

MVP 先用 Bearer Token 验证，但设计上预留可扩展的验证器接口：

```java
public interface WebhookVerifier {
    /**
     * @return true 表示验证通过
     */
    boolean verify(Request request, Trigger trigger);
}
```

| 验证器 | 适用平台 | MVP/P1 |
|---|---|---|
| `BearerTokenVerifier` | 通用（Discord, Telegram） | **MVP** |
| `SlackSignatureVerifier` | Slack（HMAC-SHA256） | **P1** |

**SlackSignatureVerifier 设计（P1 实现）：**

Slack 的签名验证机制：
1. 请求头包含 `X-Slack-Request-Timestamp` 和 `X-Slack-Signature`
2. 签名算法：`HMAC-SHA256(secret, "v0:" + timestamp + ":" + requestBody)`
3. 比对生成的签名和 `X-Slack-Signature` 头的值

实现时需要：
- trigger.config 中额外存储 Slack Signing Secret（用户在创建时填入）
- 验证 timestamp 是否在 ±5 分钟内（防重放攻击）
- config 中加 `verifier_type` 字段：`"bearer"`（默认）或 `"slack"`

```java
public class SlackSignatureVerifier implements WebhookVerifier {
    private static final int ALLOWED_TIMESTAMP_DRIFT_SECONDS = 300; // 5 分钟

    @Override
    public boolean verify(Request request, Trigger trigger) {
        var timestamp = request.header("X-Slack-Request-Timestamp")
                .orElseThrow(() -> new ForbiddenException("missing timestamp"));
        var signature = request.header("X-Slack-Signature")
                .orElseThrow(() -> new ForbiddenException("missing signature"));

        // 1. 防重放：timestamp 必须在当前时间的 ±5 分钟内
        var now = Instant.now().getEpochSecond();
        if (Math.abs(now - Long.parseLong(timestamp)) > ALLOWED_TIMESTAMP_DRIFT_SECONDS) {
            return false;
        }

        // 2. 生成签名
        var sigBase = "v0:" + timestamp + ":" + request.body().orElse("");
        var signingSecret = trigger.config.get("slack_signing_secret");
        var expected = "v0=" + hmacSha256Hex(signingSecret, sigBase);

        // 3. 安全比对（防时序攻击）
        return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(String secret, String data) {
        // 标准 HMAC-SHA256 实现
    }
}
```

> **MVP 阶段**：Slack 场景下直接用 Bearer Token 验证，URL 的 UUID 随机性本身提供基础安全（不可猜测）。用户在 Slack 配置 Event Subscription 时，URL 本身已包含 trigger ID，泄露风险低。**P1 再补 SlackSignatureVerifier**。

### 服务器公网可达性要求

Trigger 系统的 Webhook endpoint 需要被外部平台（Slack / Discord / Telegram）直接访问，因此服务器必须有公网可达地址：

| 部署环境 | 方案 |
|---|---|
| K8s 生产 | Ingress + 域名 + TLS（用户已确认会配置） |
| 本地开发 | ngrok / bore / Cloudflare Tunnel 等工具暴露临时公网 URL |

**建议**：在 `sys.publicUrl` 配置中填写完整的外部访问地址（如 `https://core-ai.example.com`），TriggerController 用此配置计算 `webhook_url` 展示给用户。

### 响应示例

```json
POST /api/triggers
{
  "name": "Slack Notifier",
  "description": "处理 Slack Event Subscriptions",
  "type": "WEBHOOK",
  "action_type": "RUN_AGENT",
  "action_config": {
    "agent_id": "abc-123",
    "input_template": "Slack 事件: {{payload}}"
  }
}
→
{
  "id": "trigger-xyz",
  "name": "Slack Notifier",
  "type": "WEBHOOK",
  "enabled": true,
  "config": {
    "secret": "whk_a1b2c3d4",
    "allowed_methods": "POST"
  },
  "webhook_url": "https://your-server/api/webhook-triggers/trigger-xyz",
  "action_type": "RUN_AGENT",
  "action_config": {
    "agent_id": "abc-123",
    "input_template": "Slack 事件: {{payload}}"
  },
  "created_at": "...",
  "updated_at": "..."
}
```

> `webhook_url` 是计算字段（由 `sys.publicUrl` 配置），不在 MongoDB 中存储。前端在详情中展示并支持一键复制。

## 7. 后端架构

### 包结构

```
core-ai-server/src/main/java/ai/core/server/
├── trigger/
│   ├── TriggerController.java         ← Public webhook endpoint
│   ├── TriggerService.java            ← CRUD + 执行逻辑
│   ├── TriggerScheduleJob.java        ← 定时调度遍历 SCHEDULE 类型的 trigger（Phase 2）
│   ├── domain/
│   │   ├── Trigger.java
│   │   └── TriggerType.java
│   └── action/
│       ├── TriggerAction.java         ← 接口
│       └── RunAgentAction.java        ← RUN_AGENT 实现
```

### 接口定义（core-ai-api）

```java
// TriggerWebService.java (core-ai-api)
public interface TriggerWebService {
    @PUT
    @Path("/api/triggers")
    TriggerView create(CreateTriggerRequest request);

    @GET
    @Path("/api/triggers")
    ListTriggersResponse list(@QueryParam("type") String type);

    @GET
    @Path("/api/triggers/:id")
    TriggerView get(@PathParam("id") String id);

    @PUT
    @Path("/api/triggers/:id")
    TriggerView update(@PathParam("id") String id, UpdateTriggerRequest request);

    @DELETE
    @Path("/api/triggers/:id")
    void delete(@PathParam("id") String id);

    @PUT
    @Path("/api/triggers/:id/enable")
    TriggerView enable(@PathParam("id") String id);

    @PUT
    @Path("/api/triggers/:id/disable")
    TriggerView disable(@PathParam("id") String id);

    @PUT
    @Path("/api/triggers/:id/rotate-secret")
    TriggerView rotateSecret(@PathParam("id") String id);
}
```

### TriggerAction 接口

```java
public interface TriggerAction {
    String type();   // "RUN_AGENT"
    void execute(Trigger trigger, Map<String, String> resolvedConfig, String payload);
}
```

### ServerModule 注册

```java
// Trigger system
var triggerService = bind(TriggerService.class);
triggerService.publicUrl = property("sys.publicUrl").orElse("http://localhost:8080");
api().service(TriggerWebService.class, bind(TriggerWebServiceImpl.class));
http().route(HTTPMethod.POST, "/api/webhook-triggers/:id", bind(TriggerController.class));

// Agent schedule (移入 Trigger 导航，但逻辑不变)
api().service(AgentScheduleWebService.class, bind(AgentScheduleWebServiceImpl.class));
schedule().fixedRate("agent-scheduler", bind(AgentSchedulerJob.class), Duration.ofMinutes(1));

// 清理旧 webhook 路由
// 删除: http().route(HTTPMethod.POST, "/api/webhooks/:agentId", ...)
```

## 8. 前端设计

### 导航结构变化

**Layout.tsx navItems:**

```typescript
const navItems: NavItem[] = [
  { to: '/chat', icon: MessageCircle, label: 'Chat', show: caps.chat },
  { to: '/', icon: Activity, label: 'Traces', show: caps.traces },
  { to: '/agents', icon: Bot, label: 'Agents', show: true },
  { to: '/system-prompts', icon: FileText, label: 'System Prompts', show: caps.systemPrompts },
  { to: '/triggers', icon: Webhook, label: 'Triggers', show: true, children: [
    { to: '/triggers/webhook', icon: Webhook, label: 'Webhook', show: true },
    { to: '/triggers/schedule', icon: Calendar, label: 'Schedule', show: true },
  ]},
  // /scheduler 从顶层移除
  { to: '/tools', icon: Wrench, label: 'Tools', show: true, children: [
    { to: '/mcp', icon: Network, label: 'MCP', show: true },
    { to: '/api-tools', icon: Key, label: 'API Tools', show: true },
  ]},
  { to: '/skills', icon: Sparkles, label: 'Skills', show: true },
];
```

### 页面路由

| 路径 | 组件 | 说明 |
|---|---|---|
| `/triggers` | → 重定向到 `/triggers/webhook` | Trigger 首页直接跳转 Webhook 子页 |
| `/triggers/webhook` | `TriggersWebhook.tsx` | Webhook 类型 Trigger 的 CRUD 列表 |
| `/triggers/schedule` | `Scheduler.tsx` | 现有 Scheduler 页面，移入此处 |

### TriggersWebhook 页面

复用现有 MCP 页面的 CRUD 模式：
- 卡片列表：展示名称、描述、启用状态、webhook URL（可复制）、关联 Agent
- 操作按钮：编辑、启/禁用、删除、轮换 secret
- 创建/编辑 Modal：名称、描述、选择 Agent、输入模板
- 创建成功后高亮展示 webhook URL + secret（带复制按钮，一次性的）

### 页面文件结构

```
core-ai-frontend/src/pages/
├── triggers/
│   ├── TriggersWebhook.tsx     ← Webhook 列表 + CRUD
│   └── (TriggersSchedule.tsx)  ← 复用现有 scheduler 即可
```

### 前端 API 扩展

```typescript
export interface TriggerView {
  id: string;
  name: string;
  description: string;
  type: 'WEBHOOK' | 'SCHEDULE';
  enabled: boolean;
  config: Record<string, string>;
  webhook_url?: string;         // 仅 WEBHOOK 类型
  action_type: string;          // "RUN_AGENT"
  action_config: Record<string, string>;
  created_at: string;
  updated_at: string;
}

export const api.triggers = {
  list: (type?: string) => request<ListTriggersResponse>(`/api/triggers?...`),
  get: (id: string) => request<TriggerView>(`/api/triggers/${id}`),
  create: (data: CreateTriggerRequest) => request<TriggerView>('/api/triggers', { method: 'POST', body: JSON.stringify(data) }),
  update: (id: string, data: UpdateTriggerRequest) => request<TriggerView>(`/api/triggers/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  delete: (id: string) => request<void>(`/api/triggers/${id}`, { method: 'DELETE' }),
  enable: (id: string) => request<TriggerView>(`/api/triggers/${id}/enable`, { method: 'POST' }),
  disable: (id: string) => request<TriggerView>(`/api/triggers/${id}/disable`, { method: 'POST' }),
  rotateSecret: (id: string) => request<TriggerView>(`/api/triggers/${id}/rotate-secret`, { method: 'POST' }),
};
```

## 9. 安全考虑

1. **Webhook secret** 自动生成 `whk_` + UUID，创建/轮换时在响应中返回一次
2. **Public endpoint 限速**：按 IP + trigger ID 做简单速率限制
3. **请求体大小限制**：最大 1MB
4. **Auth 豁免**：`/api/webhook-triggers/` 路径在 AuthInterceptor 中豁免
5. **secret 存储**：以明文存储在 trigger.config 中（与服务端内部 secret 同级，属于基础设施配置）

## 10. 实现计划

| 步骤 | 内容 | 涉及模块 |
|---|---|---|
| 1 | 删除旧 webhook 代码 | agent 包、webhook 包、auth |
| 2 | 定义 Trigger Java 数据模型 + 枚举 | core-ai-server |
| 3 | 定义 Trigger API 接口 + Request/Response 类 | core-ai-api |
| 4 | 实现 TriggerService（CRUD） | core-ai-server |
| 5 | 实现 TriggerWebServiceImpl | core-ai-server |
| 6 | 实现 TriggerController（public webhook endpoint） | core-ai-server |
| 7 | 实现 TriggerAction + RunAgentAction | core-ai-server |
| 8 | 注册到 ServerModule + AuthInterceptor | core-ai-server |
| 9 | 前端：扩展 client.ts | core-ai-frontend |
| 10 | 前端：修改 Layout 导航（Triggers 顶层 + Schedule 移入） | core-ai-frontend |
| 11 | 前端：修改 App.tsx 路由 | core-ai-frontend |
| 12 | 前端：实现 TriggersWebhook 页面 | core-ai-frontend |
| 13 | 前端：Scheduler 页面保持不动，仅改变挂载位置 | core-ai-frontend |
