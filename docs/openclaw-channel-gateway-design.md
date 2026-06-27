# OpenClaw Channel Gateway 集成技术设计文档

## 1. 背景

[OpenClaw Channel Gateway](https://github.com/demobin8/openclaw-channel-gateway)（以下简称 OCG）是一个轻量级 IM 渠道网关，它复用 OpenClaw 的渠道生态（Telegram、Discord、微信、钉钉、QQ 等），将消息通过 HTTP 转发到任何兼容 OpenAI 格式的 Agent API。

OCG 是一个 npm 包（`openclaw-channel-gateway`），依赖 Node.js >= 22.12，以独立进程运行（`ocg start`），读取 JSON 配置文件。

**调度模式**：OCG 支持两种模式：

- **同步模式**（默认）：收到消息 → POST 到 Agent API → 流式接收 SSE 响应 → 逐块投递到 IM。适合短平快对话。
- **异步模式**（`async: true`）：收到消息 → POST 到 Agent API（带 `X-OCG-Callback` header）→ 立即返回 → OCG 启动 callback HTTP server 等待 → Agent 完成后 POST 回调 OCG 投递结果。适合长时间运行的 Agent 任务。

本设计基于 **OCG 异步模式**（v1.0.5+），解决同步模式下 Agent 长时间运行导致 HTTP 超时的问题。

## 2. 目标

将 OCG 集成到 core-ai-server 中，让用户可以在 core-ai 平台上管理 OCG 配置，并以 sandbox 方式运行 OCG 实例，从而打通更多 IM 渠道。

职责边界：core-ai 负责 OCG 配置管理、sandbox 生命周期、Agent OpenAI-compatible endpoint、异步回调 worker 和 HMAC 签名；OCG 负责 IM 渠道接入、IM 会话标识、callback server、callback token 生命周期和最终消息投递。

## 3. 整体架构（异步回调模式）

```
Telegram / Discord / 微信 / 钉钉 / QQ
        │ 用户消息
        ▼
┌──────────────────────────────────────┐
│  OCG Sandbox (持久运行)               │
│  Node.js 22.12+                      │
│  ocg start (async mode)              │
│  callback server :3457               │
└──────────────┬───────────────────────┘
                │ ① POST /api/channels/{id}/v1/chat/completions
               │    Body: OpenAI format
               │    Header: X-OCG-Callback: http://{sandbox-ip}:3457/ocg/callback/{token}
               │    立即返回 202
               ▼
┌──────────────────────────────────────┐
│  core-ai-server                      │
│  ┌────────────────────────────────┐  │
│  │ ChannelSyncController           │  │
│  │  (检测 X-OCG-Callback header)    │  │
│  │  1. 解析 OpenAI body            │  │
│  │  2. 读取 X-OCG-Callback header  │  │
│  │  3. 创建 Agent session          │  │
│  │  4. 派发消息                    │  │
│  │  5. [async] 返回 202            │  │
│  │  6. [sync]  轮询返回            │  │
│  └──────────────┬─────────────────┘  │
│                 │ 后台轮询 agent 回复  │
│                 ▼                    │
│  ┌────────────────────────────────┐  │
│  │ OcgCallbackPool                │  │
│  │  轮询 ChatMessageService        │  │
│  │  → 获取回复                     │  │
│  │  → ② POST {reply} 到 callback  │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
               │ ② POST /ocg/callback/{token}
               │    Body: {"reply": "...", "isError": false}
               │    Header: X-OCG-Signature (可选 HMAC)
               ▼
┌──────────────────────────────────────┐
│  OCG Sandbox                         │
│  callback server 验证 token + 签名    │
│  → 投递回复到对应 IM 渠道             │
└──────────────────────────────────────┘
               │
               ▼
Telegram / Discord / 微信 / 钉钉 / QQ
```

## 4. OCG 异步回调协议

> 协议版本：OCG v1.0.5+，详见 [CHANGELOG](https://github.com/demobin8/openclaw-channel-gateway/blob/master/CHANGELOG.md)

### 4.1 Forward 请求（OCG → core-ai）

OCG 发送标准 OpenAI chat completion 请求，回调地址放在 HTTP Header 中。请求体保持 OpenAI-compatible，使用标准 `user` 字段传递 IM 会话标识，便于 core-ai 按会话复用 Agent session：

```http
POST /v1/chat/completions
Content-Type: application/json
X-OCG-Callback: http://{sandbox-ip}:3457/ocg/callback/a1b2c3d4e5f6...
Authorization: Bearer sk-xxx

{
  "model": "gpt-4o",
  "messages": [{ "role": "user", "content": "你好" }],
  "stream": false,
  "user": "telegram:default:123456789"
}
```

| Header | 说明 |
|---|---|
| `X-OCG-Callback` | Agent 完成后必须 POST 的回调地址，路径中包含 64 位十六进制随机 token |
| `Authorization` | `Bearer <sys.ocg.api-key>`，仅 OCG async 分支强制校验 |

`user` 建议由 OCG 使用稳定 session key 生成，例如 `{channelId}:{accountId}:{conversationId}` 或 `{channelId}:{accountId}:{userId}`。OCG v1.0.8 当前尚未发送该字段，集成时需要同步修改 OCG 源码，在 async forward body 中增加 `user: sessionKey`。

core-ai 收到后立即返回 HTTP 202：

```json
{"status": "accepted"}
```

### 4.2 Callback 请求（core-ai → OCG）

Agent 处理完毕后，向 `X-OCG-Callback` 中的 URL 发送回复：

```http
POST /ocg/callback/a1b2c3d4e5f6...
Content-Type: application/json
X-OCG-Signature: sha256=<hex-digest>

{
  "reply": "回复内容",
  "isError": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `reply` | string / object | 是 | 回复文本或结构化内容 |
| `isError` | boolean | 否 | 是否作为错误消息投递（默认 false） |

### 4.3 Token 生命周期

- 每条转发消息生成唯一 64 位十六进制 token（`crypto.randomBytes(32)`）
- Token 默认有效期 **30 分钟**（可通过 `callbackTokenTTL` 配置）
- Token **一次性消费**——成功回调后立即销毁

### 4.4 HMAC 签名（可选）

若 OCG 配置了 `callbackSecret`，core-ai 需对回调请求签名：

```
signature = HMAC-SHA256(request-body-bytes, callbackSecret)
header    = "sha256=" + hex(signature)
```

`callbackSecret` 只以 core-ai 的 `OcgConfigView.callbackSecret` 为准。启动 sandbox 生成最终 `ocg.json` 时，core-ai 将该值写入 `callbackSecret`；若为空则移除最终配置中的 `callbackSecret`，不启用 HMAC。禁止同时在 `configJson` 与顶层字段维护不同 secret，避免 core-ai 签名与 OCG 校验不一致。

签名必须基于最终 HTTP 请求体的 UTF-8 bytes。实现时应先序列化 callback body 得到 `bodyJson`，用同一份 `bodyJson` 计算 HMAC 并作为 HTTP body 发送，避免 HTTP client 二次序列化导致签名不匹配。

### 4.5 callbackHost 处理

OCG v1.0.8 中 `callbackHost` 默认值为 `0.0.0.0`（监听所有网口）。`buildCallbackUrl()` 逻辑：

```typescript
const reachableHost = host === "0.0.0.0" ? "127.0.0.1" : host;
```

Sandbox 部署时，core-ai 在启动时获取 sandbox IP，将 `callbackHost` 设置为 **sandbox 实际 IP**（非 `0.0.0.0`），这样 `X-OCG-Callback` header 中的 URL 使用 sandbox IP，core-ai 可直接访问。

## 5. 实体关系

```
Channel (type="openclaw")  ←1:1→  OCG Config  ←→  Sandbox (持久运行)
```

- **Channel**：提供 webhook URL 入口用作 OCG 的 `agentUrl`
- **OCG Config**：通过 `channelId` 单向关联 Channel。用户上传原始 JSON 配置；`agentUrl`、`apiKey`、`async`、`callbackHost`、`callbackSecret` 等运行时字段由 core-ai 启动 sandbox 时注入/覆盖
- **Sandbox**：持久运行，timeout 设为 86400s（24h），由 `OcgHealthCheckJob` 定时 renew

> 关系说明：一个 OCG 实例对应一个 Channel 入口和一个 Sandbox。OCG 内部可配置多个 IM 渠道，所有渠道的消息都转发到同一个 core-ai Channel 入口。如果需要不同 Agent 处理不同渠道，需创建多个 Channel + 多个 OCG Config。多 IM 渠道共用同一入口时，OCG 必须通过 OpenAI 标准 `user` 字段传递稳定 session key，保证 core-ai session 能按 IM 会话隔离和复用。

## 6. 用户操作流程

```
1. 创建 Channel (type="openclaw") → 系统生成 webhookUrl
2. 创建 OCG Config → 选择关联的 Channel → 上传原始 JSON 配置（agentUrl 只读预览，由系统注入）
3. 点击"启动 Sandbox" → 系统创建 sandbox → 获取 sandbox IP
   → 生成最终 ocg.json（强制 async: true, callbackHost: <sandboxIP>）
   → 执行 OCG_CONFIG_PATH=/workspace/ocg.json ocg start
4. OCG 启动后，IM 消息通过 forward → callback 协议与 core-ai 交互
5. Health check job 定时巡检 sandbox 状态并 renew
6. 点击"停止 Sandbox" → 系统关闭 sandbox 容器
```

## 7. 数据模型

### 7.1 OCG Config 实体

MongoDB 集合：`ocg_configs`

```java
@Collection(name = "ocg_configs")
public class OcgConfigView {
    @Id
    public String id;

    @NotNull
    @Field(name = "channel_id")
    public String channelId;

    @NotNull
    @Field(name = "config_json")
    public String configJson;

    @Field(name = "callback_secret")
    public String callbackSecret;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled = true;

    @Field(name = "sandbox_id")
    public String sandboxId;

    @Field(name = "sandbox_ip")
    public String sandboxIp;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
```

字段说明：

| Java 字段 | MongoDB 字段 | 类型 | 说明 |
|---|---|---|---|
| `id` | `_id` | String | 唯一标识，用户自定义（如 `"ocg-prod"`） |
| `channelId` | `channel_id` | String | 关联的 Channel ID |
| `configJson` | `config_json` | String | 原始 JSON 配置（用户上传） |
| `callbackSecret` | `callback_secret` | String | HMAC 签名密钥（可选，与 OCG 共享） |
| `enabled` | `enabled` | Boolean | 是否启用，默认 true。**有默认值，必须加 `@NotNull`** |
| `sandboxId` | `sandbox_id` | String | 运行时 sandbox ID（K8s pod name）。启动时写入，停止时清空。落库即代表 sandbox 在运行 |
| `sandboxIp` | `sandbox_ip` | String | Sandbox 运行时 IP（用于 callback 可达性检查） |
| `createdAt` | `created_at` | ZonedDateTime | 创建时间 |
| `updatedAt` | `updated_at` | ZonedDateTime | 更新时间 |

**Sandbox 状态不存库**，由 health check job 在运行时计算（stopped / running / error）。

**configJson 字段说明**：参考 `ToolRegistry.rawConfig` 的模式，原始 JSON 以字符串形式存储，方便用户查看和编辑。用户配置主要包含 OCG 渠道配置和可选的模型、端口等参数：

```json
{
  "model": "gpt-4o",
  "callbackPort": 3457,
  "callbackTokenTTL": 1800,
  "channels": { "telegram": { ... }, "discord": { ... } }
}
```

`agentUrl`、`apiKey`、`async`、`callbackHost`、`callbackSecret` 由系统在启动时自动注入/覆盖，不要求用户在 `configJson` 中配置。`callbackSecret` 只以顶层 `OcgConfigView.callbackSecret` 为准；如用户在 `configJson` 中填写，生成最终 `ocg.json` 时也会被顶层字段覆盖或移除。

### 7.2 Channel 实体

现有 `channels` 集合，`ChannelConfigView` 无需修改字段。新增类型 `"openclaw"` 后，`config` 字段中不需要额外存储关联信息（由 OCG Config 单向引用 Channel）。

### 7.3 OCG Config Store

参考 `ChannelConfigStore` 模式：内存 `ConcurrentHashMap` + MongoDB 持久化，启动时全量加载。

```java
public class OcgConfigStore {
    private final ConcurrentHashMap<String, OcgConfigView> cache = new ConcurrentHashMap<>();

    @Inject
    MongoCollection<OcgConfigView> collection;

    void loadAllFromDb() {
        for (var config : collection.find()) {
            cache.put(config.id, config);
        }
    }

    void store(OcgConfigView config) {
        cache.put(config.id, config);
        collection.replaceOne(Filters.eq("_id", config.id), config, new ReplaceOptions().upsert(true));
    }

    OcgConfigView load(String id) {
        return cache.get(id);
    }

    OcgConfigView loadByChannelId(String channelId) {
        return cache.values().stream()
            .filter(c -> channelId.equals(c.channelId))
            .findFirst().orElse(null);
    }

    List<OcgConfigView> allWithSandbox() {
        return cache.values().stream()
            .filter(c -> c.sandboxId != null)
            .toList();
    }

    void clearSandbox(String id) {
        var config = cache.get(id);
        if (config != null) {
            config.sandboxId = null;
            config.sandboxIp = null;
            collection.updateOne(Filters.eq("_id", id), Updates.combine(
                Updates.unset("sandbox_id"), Updates.unset("sandbox_ip")));
        }
    }

    void remove(String id) {
        cache.remove(id);
        collection.deleteOne(Filters.eq("_id", id));
    }
}
```

关键方法：
- `loadByChannelId(channelId)` — `ChannelSyncController`（dispatch）和 `OcgCallbackPool` 通过 channelId 反查 OCG Config 获取 `callbackSecret`
- `allWithSandbox()` — 重启恢复时遍历所有运行中的 sandbox
- `clearSandbox(id)` — 清空 sandbox 关联但不删除配置本身

## 8. API 设计

### 8.1 OCG Config 管理 API

**Base path**: `/api/admin/ocg-configs`

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/` | 列表所有 OCG 配置（含运行时 sandbox 状态） |
| `POST` | `/` | 创建 OCG 配置 |
| `GET` | `/:id` | 获取详情 |
| `PUT` | `/:id` | 更新配置 |
| `DELETE` | `/:id` | 删除配置（需先停止 sandbox） |
| `POST` | `/:id/start` | 启动 Sandbox |
| `POST` | `/:id/stop` | 停止 Sandbox |
| `GET` | `/:id/status` | 查询 Sandbox 运行状态 |

### 8.2 OCG Dispatch（消息入口）

复用已有的 sync 端点 `POST /api/channels/:channelId/v1/chat/completions`，通过 `X-OCG-Callback` header 的有无区分模式：

| | sync 模式 | async 模式（OCG） |
|---|---|---|
| `X-OCG-Callback` header | 无 | 有 |
| 响应方式 | 轮询 agent 回复 → inline 返回 | 立即返回 202 → 由 `OcgCallbackPool` 后台回调 |
| Controller 逻辑 | `ChannelSyncController`（已有） | 同入口，内部判断 header 分流 |

`ChannelSyncController` 改造：在 `execute()` 开头检测 header，有 `X-OCG-Callback` 时走异步分支：

```java
var callbackUrl = request.header("X-OCG-Callback").orElse(null);
if (callbackUrl != null) {
    return handleAsync(request, callbackUrl);   // 派发消息 → 提交 OcgCallbackPool → 返回 202
}
// 原有 sync 逻辑不变
```

异步分支流程：
1. 验证 `Authorization: Bearer` header：提取 token，与系统配置的 API key（`sys.ocg.api-key`）比对。不匹配返回 401。OCG 侧最终 `ocg.json.apiKey` 由系统注入与此一致的 key。鉴权仅对带 `X-OCG-Callback` 的 OCG async 分支强制执行，原 sync 分支保持现有兼容行为
2. 解析 OpenAI body（复用已有解析逻辑）
3. 读取 `X-OCG-Callback` header 获取回调 URL
4. 查找 Channel，要求 Channel 存在且 `channelType = "openclaw"`，再解析关联 Agent
5. 根据 OpenAI 标准 `user` 字段创建/复用 Agent session，派发消息。OCG 必须发送稳定 `user` 值，否则 core-ai 会按新会话处理，无法获得多轮上下文
6. 提交 `OcgCallbackPool` 后台轮询任务
7. 返回 HTTP 202 `{"status":"accepted"}`

### 8.3 Channel Types API

`GET /api/admin/channel-types` 返回列表中新增：

```java
Map.of("type", "openclaw", "label", "OpenClaw")
```

### 8.4 创建 OCG Config 请求体

```json
{
  "id": "ocg-prod",
  "channelId": "ocg-gateway",
  "configJson": "{ \"model\": \"gpt-4o\", \"channels\": { \"telegram\": { ... } } }",
  "callbackSecret": "可选"
}
```

**创建时校验**：

1. `configJson` 必须能解析为合法 JSON object
2. `channelId` 对应的 Channel 必须存在且类型为 `"openclaw"`
3. `callbackSecret` 只接受顶层字段；如 `configJson` 中包含 `callbackSecret`，启动时会被顶层字段覆盖或移除
4. 不要求 `configJson` 包含 `"async": true`，最终 `ocg.json` 由 core-ai 强制覆盖 `async = true`

### 8.5 OCG Config 列表响应体

```json
{
  "configs": [
    {
      "id": "ocg-prod",
      "channelId": "ocg-gateway",
      "channelName": "ocg-gateway",
      "configJson": "...",
      "enabled": true,
      "sandboxId": "sbx-abc123",
      "sandboxIp": "10.244.1.5",
      "sandboxStatus": "running",
      "createdAt": "2026-06-25T10:00:00Z",
      "updatedAt": "2026-06-25T10:30:00Z"
    }
  ]
}
```

其中 `sandboxStatus` 为运行时计算字段，取值：`stopped` / `running` / `error`。

## 9. OCG Sandbox 服务设计

> **生命周期原则**：OCG sandbox 是持久的、以数据库为准。`sandboxId` / `sandboxIp` 落库意味着 sandbox 在运行；用户 Stop 才停止，用户 Start 才创建。服务重启时通过 `SandboxProvider.attach()` 重连已有 pod，不重建。

### 9.1 `OcgSandboxService`

核心方法：

```java
// 启动 sandbox：生成 ocg.json → 创建 sandbox → 上传配置 → 执行 ocg start → 记录 sandboxId + ip
void startSandbox(String ocgConfigId);

// 停止 sandbox：通过 sandboxId 找到并关闭 sandbox → 清空 sandboxId + sandboxIp
void stopSandbox(String ocgConfigId);

// 查询状态：检查 sandbox 存活 + OCG 进程状态（stopped / running / error）
OcgSandboxStatus getStatus(String ocgConfigId);

// 重启恢复：遍历所有 sandboxId != null 的配置，尝试 attach 已有 pod
void recoverOnStartup();
```

### 9.2 Sandbox 启动逻辑

1. 从 `OcgConfigStore` 加载 OCG Config
2. 从 `ChannelConfigStore` 加载关联 Channel，获取 `webhookUrl`
3. 构建 sandbox 配置：
   - `networkEnabled = true`（OCG 需要访问 IM 平台 API）
   - `timeoutSeconds = 86400`（24 小时，由 health check 定期 `renew`）
   - `memoryLimitMb = 1024`（Node.js + OCG + IM SDK 需充足内存）
4. 创建 Sandbox（复用现有 `SandboxService`），获取 sandbox IP
5. 合并生成完整的 `ocg.json`，先保留用户 `configJson` 中的渠道配置和可选参数，再由系统自动注入/覆盖以下字段：
   - `agentUrl` = Channel 的 webhookUrl（dispatch 端点）
   - `apiKey` = 系统 API key（`sys.ocg.api-key`）
   - `async` = true（强制异步模式）
   - `callbackHost` = sandbox IP（避免 `0.0.0.0 → 127.0.0.1` 重写）
   - `callbackPort` = 用户 configJson 中的值或默认 3457
   - `callbackSecret` = OCG Config 顶层 `callbackSecret`；为空则从最终配置中移除
   - `channels` = 用户 configJson 中的 channels 部分
6. 将 `ocg.json` 上传到 sandbox 固定路径，例如 `/workspace/ocg.json`
7. 启动 OCG：
   ```bash
   OCG_CONFIG_PATH=/workspace/ocg.json ocg start
   ```
   OCG v1.0.4+ 默认读取 `~/.openclaw-channel-gateway/ocg.json`，如果上传到工作目录，必须通过 `OCG_CONFIG_PATH` 指定配置文件。OCG 当前通过 `applyConfigEnvOverrides()` 将 `ocg.json.apiKey` 注入 `OCG_API_KEY`，实际 forward header 从环境变量读取，因此必须通过标准 `ocg start` 入口启动或显式设置 `OCG_API_KEY`。
   > 注意：sandbox 镜像需预装 Node.js 22.12+ 和 `openclaw-channel-gateway`，避免启动时 `npm install -g` 耗时过长。
8. 检查 OCG 启动状态（例如执行 `OCG_CONFIG_PATH=/workspace/ocg.json ocg status --json`）
9. 将 `sandboxId` 和 `sandboxIp` 写入 OCG Config

### 9.3 Sandbox 停止逻辑

1. 从 `OcgConfigStore` 加载配置，获取 `sandboxId`
2. 调用 `SandboxService` 关闭对应 sandbox
3. 清空 OCG Config 的 `sandboxId` 和 `sandboxIp` 字段

### 9.4 服务重启恢复

OCG sandbox 是**持久化**的——`sandboxId` 和 `sandboxIp` 存储在 MongoDB 中，代表真实运行的 K8s pod。重启恢复策略：

1. 遍历所有 `sandboxId != null` 的 OCG Config
2. 对每个记录，调用 `SandboxManager.attach(...)` 尝试重连已有 pod（内部委托 `SandboxProvider.attach`）：
   - **pod 存活** → 重新注册到 `SandboxManager`，恢复 health check 续期，sandbox 状态恢复为 `running`
   - **pod 已死/不存在** → 清空 `sandboxId` 和 `sandboxIp`，sandbox 状态变为 `stopped`
3. 恢复完成后，`OcgHealthCheckJob` 照常运行，对所有已恢复的 sandbox 进行定期续期和状态检查

重启期间，OCG sandbox pod 自身不受影响（K8s pod 独立于 core-ai-server 进程），IM 消息仍可正常转发到 dispatch 端点。由于 `OcgCallbackPool` 是内存组件且重启即丢失，重启窗口内（约 30s）正在进行的异步对话的回调会丢失，但 OCG token 有 30 分钟有效期，下一轮对话不受影响。

### 9.5 SandboxProvider 接口扩展

为支持重启后重连已有 pod，在 `SandboxProvider` 接口中新增 `attach` 方法：

```java
// ai.core.sandbox.SandboxProvider
public interface SandboxProvider {
    Sandbox acquire(SandboxConfig config, String sessionId, String userId);

    /** 尝试重连一个已存在的 sandbox（如 K8s pod），用于服务重启恢复。
     *  返回 Optional.empty() 表示不支持或 sandbox 已不存在。 */
    default Optional<Sandbox> attach(String sandboxId, SandboxConfig config, String sessionId, String userId) {
        return Optional.empty();
    }

    void release(Sandbox sandbox);
    default void renew(Sandbox sandbox, int timeoutSeconds);
    SandboxStatus getStatus(Sandbox sandbox);
}
```

`KubernetesSandboxProvider` 实现：

```java
@Override
public Optional<Sandbox> attach(String sandboxId, SandboxConfig config, String sessionId, String userId) {
    var pod = client.getPod(sandboxId);   // K8s API: GET /api/v1/namespaces/{ns}/pods/{name}
    if (pod == null || !"Running".equals(pod.getStatus().getPhase())) {
        return Optional.empty();
    }
    var ip = pod.getStatus().getPodIP();
    // 用已有 pod 信息构造 Sandbox，不创建新 pod
    return Optional.of(new KubernetesSandbox(sandboxId, ip, config));
}
```

OCG 重启恢复调用链：

```java
// OcgSandboxService
void recoverOnStartup() {
    for (var config : ocgConfigStore.allWithSandbox()) {
        var sandbox = sandboxManager.attach(
            config.sandboxId, buildSandboxConfig(config), "ocg-" + config.id, "system"
        );
        if (sandbox.isPresent()) {
            LOGGER.info("ocg sandbox recovered: id={}, ip={}", config.sandboxId, config.sandboxIp);
        } else {
            ocgConfigStore.clearSandbox(config.id);   // pod 已死，清库
            LOGGER.warn("ocg sandbox lost, cleared: id={}, pod={}", config.id, config.sandboxId);
        }
    }
}
```

> 注意：`SandboxManager` 需要新增 `attach(...)` 方法，内部调用 `SandboxProvider.attach(...)` 并在成功后注册到 `activeSandboxes`。不要暴露 `provider` 字段给 OCG 服务直接访问。

### 9.6 Sandbox 镜像要求

现有 `chancetop/core-ai-sandbox-runtime` 镜像需增加：

- Node.js >= 22.12
- `openclaw-channel-gateway` 全局安装（`npm install -g openclaw-channel-gateway`）

## 10. OcgCallbackPool

后台轮询 agent 回复并通过 HTTP 回调给 OCG 的组件。

```java
public class OcgCallbackPool {
    // 有界线程池：核心 8 线程，最大 32，队列 128。避免 OCG 消息洪峰时线程无限增长。
    // CallerRunsPolicy：队列满时由调用线程（ChannelSyncController 的 HTTP 线程）直接执行，
    // 形成自然背压，阻止 OCG 继续转发直到消费能力恢复。
    private final ExecutorService executor = new ThreadPoolExecutor(
        8, 32, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(128),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    private static final long POLL_TIMEOUT_MS = 30 * 60 * 1000;  // 对齐 OCG token TTL
    private static final long POLL_INTERVAL_MS = 500;

    @Inject
    ChatMessageService chatMessageService;
    @Inject
    OcgConfigStore ocgConfigStore;

    public void submit(String sessionId, String callbackUrl, String channelId) {
        executor.submit(() -> {
            var reply = pollForResponse(sessionId);
            var config = ocgConfigStore.loadByChannelId(channelId);
            var callbackSecret = config == null ? null : config.callbackSecret;
            if (reply != null) {
                postCallback(callbackUrl, reply, false, callbackSecret);
            } else {
                postCallback(callbackUrl, "Agent did not respond within timeout", true, callbackSecret);
            }
        });
    }

    private void postCallback(String url, String reply, boolean isError, String secret) {
        var body = Map.of("reply", reply, "isError", isError);
        var bodyJson = JsonUtil.toJson(body);
        var headers = new HashMap<String, String>();
        headers.put("Content-Type", "application/json");
        if (secret != null && !secret.isBlank()) {
            headers.put("X-OCG-Signature", "sha256=" + hmacSha256(bodyJson.getBytes(StandardCharsets.UTF_8), secret));
        }
        // 带退避的重试：3 次，间隔 2s / 4s / 8s
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                httpPost(url, bodyJson, headers);
                LOGGER.info("callback success, url={}, attempt={}", url, attempt);
                return;
            } catch (Exception e) {
                if (attempt == 3) {
                    LOGGER.error("callback failed after 3 attempts, url={}, replyLen={}", url, reply.length(), e);
                    // TODO: 写入死信表，供管理员手动补发
                } else {
                    try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }

    private String pollForResponse(String sessionId) {
        // 与 ChannelSyncController.pollForResponse() 相同的轮询逻辑
    }
}
```

> **Callback 重试策略**：3 次退避（2s / 4s / 8s）。全部失败后写日志，后续可扩展为写入死信表（`ocg_callback_dead_letters`）供管理员手动补发。

## 11. ChannelSyncController 改造

在已有 `ChannelSyncController` 中增加异步分支，不再需要独立的 `OcgAsyncController`。

```java
@Override
public Response execute(Request request) {
    var body = request.body();
    if (body.isEmpty()) throw new BadRequestException("body is required");
    var bodyStr = new String(body.get(), StandardCharsets.UTF_8);

    Map<String, Object> payload;
    try {
        payload = (Map<String, Object>) JsonUtil.fromJson(Map.class, bodyStr);
    } catch (Exception e) {
        throw new BadRequestException("invalid JSON body: " + e.getMessage());
    }

    var callbackUrl = request.header("X-OCG-Callback").orElse(null);
    if (callbackUrl != null) {
        verifyOcgAuthorization(request);
    }

    var userText = extractUserText(payload);
    if (userText == null) throw new BadRequestException("no user message found in request");

    var channelId = request.pathParam("channelId");
    var channel = channelConfigStore.load(channelId);
    if (channel == null) throw new NotFoundException("channel not configured: " + channelId);
    if (callbackUrl != null && !"openclaw".equals(channel.channelType)) {
        throw new BadRequestException("channel " + channelId + " is not openclaw");
    }
    if (channel.agentId == null || channel.agentId.isBlank())
        throw new BadRequestException("channel " + channelId + " has no agent configured");

    var agent = agentDefinitionService.getEntity(channel.agentId);
    if (agent == null) throw new NotFoundException("agent not found: " + channel.agentId);

    var userId = channel.userId != null ? channel.userId : channelId + ":" + UUID.randomUUID().toString().substring(0, 8);
    var userField = (String) payload.get("user");

    String sessionId;
    if (userField != null && sessionCache.containsKey(userField)) {
        sessionId = sessionCache.get(userField);
        chatMessageService.writeUserMessage(sessionId, userText);
        var command = SessionCommand.sendMessage(sessionId, userId, userText, null);
        commandPublisher.publish(command);
    } else {
        var config = new SessionConfig();
        var result = sessionManager.createSessionFromAgent(agent, config, userId, "channel-" + channelId);
        sessionId = result.sessionId();
        if (userField != null) {
            sessionCache.put(userField, sessionId);
        }
        chatMessageService.writeUserMessage(sessionId, userText);
        var command = SessionCommand.sendMessage(sessionId, userId, userText, null);
        commandPublisher.publish(command);
    }

    // —— 异步分支（OCG）——
    if (callbackUrl != null) {
        ocgCallbackPool.submit(sessionId, callbackUrl, channelId);
        return Response.text(JSON.toJSON(Map.of("status", "accepted"))).status(202);
    }

    // —— 同步分支（已有逻辑）——
    var response = pollForResponse(sessionId);
    // ... 返回 OpenAI 格式 ...
}
```

> 不需要注册新路由，`OcgCallbackPool` 通过 `@Inject` 注入到 `ChannelSyncController` 即可。OCG async 分支的鉴权只在 `X-OCG-Callback` 存在时执行，避免破坏原同步 endpoint 的兼容性。OCG async forward 当前是 fire-and-forget，OCG 不会读取 core-ai 的非 2xx 错误体；鉴权或校验失败主要体现在 OCG 日志，用户侧可能没有即时错误提示。
>
> **Session 生命周期**：异步模式下 `createSessionFromAgent` 创建的 ChatSession 不会被显式关闭。`IdleSessionCleanupJob`（已有，每 5 分钟）会自动清理超时 session。若按 OCG `user` 复用会话，`sessionCache` 需要在 session 清理或回调失败后同步移除失效映射，避免复用已关闭 session。

## 12. Health Check Job

### 12.1 `OcgHealthCheckJob`

实现 `core.framework.schedule.Job` 接口，定时（建议每 30 秒）执行：

1. 加载所有 `sandboxId != null` 的 OCG Config
2. 对每个运行中的 OCG：
   - 检查 sandbox 容器是否存活（通过 `SandboxService`）
   - 检查 OCG 进程是否正常运行（在 sandbox 内执行 `OCG_CONFIG_PATH=/workspace/ocg.json ocg status --json`）
   - 调用 `sandboxManager.renew(sandboxId)` 续期 sandbox（避免 24h 超时）
3. 状态异常时记录日志，sandbox 状态变为 `error`
4. Sandbox 正常运行则标记为 `running`

注册方式（在 `ServerModule.java` 中）：

```java
schedule().fixedRate("ocg-health-check", bind(OcgHealthCheckJob.class), Duration.ofSeconds(30));
```

## 13. Channel 集成

### 13.1 Channel 类型注册

在 `ChannelAdminController.types()` 中新增：

```java
Map.of("type", "openclaw", "label", "OpenClaw")
```

### 13.2 为什么不使用 InboundAdapter / OutboundAdapter

OCG 异步模式的消息入口是 `ChannelSyncController`（通过 `X-OCG-Callback` header 区分），回复通过 HTTP 回调（`OcgCallbackPool`），不走 `ChannelController`（webhook）→ `ChannelInboundAdapter` → `ChannelEventBridge` → `ChannelOutboundAdapter` 路径。因此不需要注册 OCG 的 adapter pair。

如果未来 OCG 增加了需要走异步 webhook 路径的场景（如 OAuth 回调），可以再添加 adapter。

## 14. 前端设计

### 14.1 导航

在 `Layout.tsx` 的 Triggers 组下新增子菜单项：

```tsx
{ to: '/triggers/channels', icon: Radio, label: 'Channels', show: true },
{ to: '/triggers/openclaw', icon: Zap, label: 'OpenClaw', show: true },
{ to: '/triggers/schedule', icon: Calendar, label: 'Schedule', show: true },
```

路由在 `App.tsx` 中添加 `"/triggers/openclaw"` 的 SPA fallback。

### 14.2 OpenClaw 页面

页面结构：

- **顶部**：标题 + "New OCG Config" 按钮
- **列表表格**：
  - 名称（id）
  - 关联 Channel（channelId）
  - Sandbox IP（运行时可见）
  - Sandbox 状态指示灯（绿色 running / 灰色 stopped / 红色 error）
  - 操作按钮：Start / Stop / Edit / Delete
- **新建/编辑弹窗（右侧抽屉）**：
  - OCG Config ID（新建时可编辑，编辑时只读）
  - 关联 Channel（下拉选择已有的 Channel，建议过滤 type="openclaw"）
  - Callback Secret（可选，HMAC 签名密钥）
  - agentUrl（根据选中 Channel 的 webhookUrl 只读预览，不保存到用户 configJson，由启动时注入）
  - JSON 配置（大文本框，用户直接粘贴完整 JSON）

### 14.3 Channel 创建时的联动

已有 Channels 创建页面中，当用户选择 Channel Type 为 "OpenClaw" 时，无需额外字段。Channel 创建完拿到 webhookUrl 后，再去 OpenClaw 页面创建 OCG Config。系统只在启动 sandbox 生成最终 `ocg.json` 时注入 `agentUrl`，不修改用户原始 `configJson`。

## 15. 文件清单

### 后端（新建）

| 文件 | 说明 |
|---|---|
| `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgConfigView.java` | MongoDB 实体 |
| `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgConfigStore.java` | 存储层（CRUD + 缓存） |
| `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgConfigController.java` | REST API 控制器（CRUD + start/stop/status） |
| `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgSandboxService.java` | Sandbox 生命周期管理 |
| `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgCallbackPool.java` | 后台轮询 + HTTP 回调 |
| `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgHealthCheckJob.java` | 定时健康检查 + sandbox renew |

### 后端（修改）

| 文件 | 修改内容 |
|---|---|
| `core-ai-server/src/main/java/ai/core/server/channel/ChannelSyncController.java` | 新增异步分支：检测 `X-OCG-Callback` header → 提交 `OcgCallbackPool` → 返回 202 |
| `core-ai-server/src/main/java/ai/core/server/channel/ChannelAdminController.java` | `types()` 方法新增 `"openclaw"` |
| `core-ai-server/src/main/java/ai/core/server/ServerModule.java` | 绑定 OCG 服务、注册 health check job、调用 `recoverOnStartup()` |
| `core-ai-server/src/main/java/ai/core/sandbox/SandboxProvider.java` | 新增 `attach()` 默认方法 |
| `core-ai-server/src/main/java/ai/core/sandbox/SandboxManager.java` | 新增 `attach(...)` 方法，内部委托 provider 并注册恢复的 sandbox |

### 前端（新建）

| 文件 | 说明 |
|---|---|
| `core-ai-frontend/src/pages/openclaw/OpenClaw.tsx` | OpenClaw 管理页面 |

### 前端（修改）

| 文件 | 修改内容 |
|---|---|
| `core-ai-frontend/src/components/Layout.tsx` | 侧边栏新增 OpenClaw 子菜单 |
| `core-ai-frontend/src/App.tsx` | 新增路由 `/triggers/openclaw` |
| `core-ai-frontend/src/api/client.ts` | 新增 `api.ocg.*` 方法 |

## 16. ServerModule 集成顺序

在 `ServerModule.java` 中的绑定顺序：

```java
protected void initialize() {
    // ... 现有初始化 ...
    
    // 1. SandboxModule 已加载（提供 SandboxService）
    
    // 2. 绑定 OCG 存储层
    var ocgConfigStore = bind(OcgConfigStore.class);
    onStartup(ocgConfigStore::loadAllFromDb);
    
    // 3. 绑定 OCG 服务
    var ocgSandboxService = bind(OcgSandboxService.class);
    bind(OcgCallbackPool.class);
    
    // 4. 注册 OCG 路由（在 bindChannels() 中）
    // ...
    
    // 5. 注册 health check job
    schedule().fixedRate("ocg-health-check", bind(OcgHealthCheckJob.class), Duration.ofSeconds(30));
    
    // 6. 重启恢复：重连数据库中记录的运行中 sandbox
    onStartup(ocgSandboxService::recoverOnStartup);
}
```

## 17. 后续优化考虑

1. **OCG 源码适配**：async forward body 增加 OpenAI 标准 `user: sessionKey` 字段，确保 core-ai 可按 IM 会话复用 Agent session
2. **OCG JSON 配置表单化**：当前为原始 JSON 文本框，后续可以提供结构化表单（Telegram Bot Token、Discord Token 等分项填写）
3. **OCG 同步模式支持**：如需短平快的同步对话，可支持 OCG 同步模式 → `ChannelSyncController`（已有）
4. **OCG 多实例**：当前 Channel 与 OCG Config 为 1:1，后续可考虑一个 OCG 对接多个 Channel 入口（不同 Agent）
5. **OCG 日志查看**：sandbox 内的 OCG 日志可回传到 core-ai-server 展示
6. **OCG 插件管理**：前端界面支持安装/卸载 OCG 渠道插件
7. **Sandbox 自动重启**：health check 检测到异常时可自动尝试重启
8. **Sandbox 网络策略**：K8s NetworkPolicy 限制 OCG sandbox 只能访问 IM 平台 API 域名 + core-ai-server webhook URL
