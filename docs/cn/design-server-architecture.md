# Core AI Server — 架构与设计

## 1. 定位

Core AI Server 是 Core AI 框架的**运行时平台**。它将 core-ai 从一个可嵌入的 SDK 转变为一个托管服务，在其中 Agent 可以被定义、调度、执行和观测。

```
core-ai (库)          — 用代码构建 Agent
core-ai-server (平台)  — 以服务形式运行 Agent
core-ai-cli (工具)     — 本地或远程与 Agent 交互
```

## 2. 设计原则

### 2.1 框架无侵入

Server 不修改 core-ai Agent 框架。所有平台行为通过已有扩展点（`StreamingCallback`、`AbstractLifecycle`、`PersistenceProvider`、`AgentEventListener`）对接：

- Agent 循环的 bug 修复自动惠及所有部署模式
- Server 始终可以运行最新版 core-ai，无需集成补丁

### 2.2 草稿/发布分离

Agent 定义有两个状态：

```
┌─────────────┐    publish()    ┌──────────────────┐
│  草稿 Draft  │ ──────────────> │  已发布 Published │
│  (可编辑)    │                 │  (不可变快照)      │
└─────────────┘                 └──────────────────┘
```

- **草稿**是工作副本，用户自由编辑 system prompt、选择工具、调整参数
- **已发布**是发布时捕获的不可变快照，所有运行、调度、Webhook 都使用此快照
- 保证可复现性 — 运行中的 Agent 永远不会看到编辑中的配置变更

### 2.3 异步优先

Agent 运行本质上是长时间运行的（秒级到分钟级）。Server 永远不会在 HTTP 请求上阻塞 Agent 执行：

- `POST /api/runs/agent/:id/trigger` 立即返回 `202 Accepted` 和 `runId`
- 执行在后台线程池中进行（固定大小：10）
- 客户端通过 `GET /api/runs/:id` 轮询结果，或通过 SSE 监听实时会话

### 2.4 传输无关的会话

交互式会话使用 `AgentSession` 接口。相同的 `InProcessAgentSession` 同时服务于：

- **CLI 本地模式** — 事件直接发送到终端渲染器
- **Server 模式** — 事件通过 `SseEventBridge` 桥接到 SSE

Agent 侧的代码不论客户端类型如何都完全相同。

### 2.5 工具审批双模型

| 模式 | 审批方式 | 使用场景 |
|------|----------|----------|
| 交互式会话 | 用户逐个审批工具调用（或设置自动审批） | 调试、探索 |
| 调度/触发运行 | 所有工具自动审批 | 自动化，Agent 自主运行 |

调度运行中的工具由平台管理员在工具注册表中注册时预先审核。

### 2.6 标准协议观测

追踪使用 **OpenTelemetry** 标准 — 与 Langfuse、Jaeger、Grafana Tempo 等使用相同协议。Server 内置 OTLP 接收器，可以作为自己的观测后端：

```
core-ai Agent 执行
  → OpenTelemetry SDK (core-ai 已内置)
    → OtlpHttpSpanExporter
      → core-ai-server /v1/traces (protobuf)
```

无自定义导出器，无私有协议。如果用户偏好 Langfuse 或 Jaeger，只需将 OTLP endpoint 指向那里 — 零代码改动。

## 3. 架构概览

```
┌──────────────────────────────────────────────────────────────────────┐
│                           HTTP 层                                     │
│                                                                       │
│  AuthInterceptor ─── API Key (Bearer coreai_xxx)                     │
│       │               Azure AD (X-Auth-Request-Email)                │
│       ▼                                                               │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                    WebService 层                                  │ │
│  │  会话 │ Agent │ 运行 │ 调度 │ 工具 │ 认证 │ 文件               │ │
│  └─────────────┬───────────────────────────────────────────────────┘ │
│                │                                                      │
│  ┌─────────────▼───────────────────────────────────────────────────┐ │
│  │                    服务层                                         │ │
│  │                                                                   │ │
│  │  AgentDefinitionService     Agent 定义 (CRUD + 发布)             │ │
│  │  AgentRunner                Agent 执行 (异步线程池)              │ │
│  │  AgentSessionManager        交互式会话 (SSE)                     │ │
│  │  AgentScheduler             Cron 定时触发                        │ │
│  │  ToolRegistryService        内置 + MCP 工具管理                  │ │
│  │  AuthService                注册、登录、API Key                  │ │
│  │  OTLPIngestService          Trace 接收                           │ │
│  │  PromptService              Prompt 模板版本管理                  │ │
│  └─────────────┬───────────────────────────────────────────────────┘ │
│                │                                                      │
│  ┌─────────────▼───────────────────────────────────────────────────┐ │
│  │                    数据层 (MongoDB)                               │ │
│  │                                                                   │ │
│  │  users │ agents │ agent_runs │ agent_schedules │ tool_registries │ │
│  │  files │ traces │ spans │ prompt_templates │ schema_versions     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  SSE 推送: InProcessAgentSession → SseEventBridge → Channel    │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

## 4. 领域模型

### 4.1 实体关系

```
User ──1:N──> AgentDefinition ──1:N──> AgentRun
                    │                      │
                    ├──1:N──> AgentSchedule │
                    │                      │
                    └── N:M ──> ToolRegistry
                                           │
Trace (via OTLP) ──1:N──> Span            │
                                           │
PromptTemplate (独立版本管理)               │
```

### 4.2 核心实体

**User** — 带 API Key 认证的用户账户

| 字段 | 说明 |
|------|------|
| id | 标准化邮箱（小写） |
| api_key | `coreai_` + Base64(32 随机字节) |
| role | `admin` 或 `user` |
| status | `active` 或 `pending`（需管理员审批） |

**AgentDefinition** — 声明式 Agent 配置

| 字段 | 说明 |
|------|------|
| system_prompt | Agent 指令 |
| model | LLM 模型标识 |
| tool_ids | ToolRegistry 条目引用 |
| input_template | `{{variable}}` 运行输入模板 |
| type | `AGENT`（多轮 + 工具）或 `LLM_CALL`（单次调用） |
| published_config | 运行/调度使用的不可变快照 |
| webhook_secret | `whk_` + UUID，用于 Webhook 认证 |

**AgentRun** — 单次执行记录

| 字段 | 说明 |
|------|------|
| status | PENDING → RUNNING → COMPLETED / FAILED / TIMEOUT / CANCELLED |
| triggered_by | MANUAL, SCHEDULE, API, WEBHOOK |
| transcript | 完整消息历史（user, assistant, tool_call, tool_result） |
| token_usage | {input: N, output: M} |

**AgentSchedule** — Cron 触发器

| 字段 | 说明 |
|------|------|
| cron_expression | 5 段式：`分 时 日 月 周` |
| concurrency_policy | SKIP（默认）或 PARALLEL |
| next_run_at | 预计算值，用于原子分布式锁 |

**ToolRegistry** — 可用工具

| 类型 | 来源 |
|------|------|
| BUILTIN | 文件操作、Web、代码执行、多模态 |
| MCP | Model Context Protocol 服务器（STDIO/HTTP/SSE 传输） |
| API | 外部 REST API（规划中） |

### 4.3 Trace 实体

**Trace** — 一次完整的 Agent 执行链路

| 字段 | 说明 |
|------|------|
| trace_id | OpenTelemetry trace ID |
| session_id, user_id | 通过 span 属性关联到 Server 实体 |
| total_tokens | 聚合 token 计数 |
| duration_ms | 挂钟时间 |

**Span** — 链路中的单个操作，通过 parent_span_id 形成树结构

| 类型 | 说明 |
|------|------|
| LLM | 单次 LLM API 调用（模型、token、耗时） |
| AGENT | Agent 执行作用域 |
| TOOL | 工具/函数调用 |
| FLOW | Flow 节点执行 |
| GROUP | 多 Agent 组协调 |

**PromptTemplate** — 版本化 Prompt 管理

| 字段 | 说明 |
|------|------|
| template | 含 `{{variable}}` 占位符的文本 |
| version | 每次更新自动递增 |
| published_version | 运行时可用的版本号 |
| status | DRAFT → PUBLISHED → ARCHIVED |

## 5. 执行流程

### 5.1 定时调度运行

```
AgentSchedulerJob (每 1 分钟)
    │
    │  MongoDB 原子更新：认领 next_run_at <= now 的调度
    │  (分布式锁 — 只有一个副本能成功)
    │
    ▼
检查并发策略
    │  SKIP → 如果 Agent 有 RUNNING 状态的运行则放弃
    │  PARALLEL → 无论如何继续
    │
    ▼
AgentRunner.run(definition.publishedConfig, input, SCHEDULE)
    │
    │  1. 创建 AgentRun 记录 (status=RUNNING)
    │  2. 提交到线程池（立即返回）
    │  3. 从已发布配置构建 Agent
    │     - 从 ToolRegistry 解析工具
    │     - 配置 LLM 提供商、模型、system prompt
    │  4. agent.execute(input) — 多轮循环
    │  5. 捕获输出、transcript、token 使用量
    │  6. 更新 AgentRun (COMPLETED / FAILED / TIMEOUT)
    │
    ▼
更新 schedule.next_run_at 为下次触发时间
```

### 5.2 Webhook 触发

```
POST /api/webhooks/:agentId
    │
    │  Authorization: Bearer whk_<secret>
    │
    ▼
验证：Agent 存在、已发布、Webhook 已启用、secret 匹配
    │
    ▼
处理输入模板：
    │  {{payload}} → 完整请求体
    │  {{field}}   → 从 JSON 请求体中提取字段
    │
    ▼
AgentRunner.run(publishedConfig, processedInput, WEBHOOK)
    │
    ▼
返回 { runId, status: "RUNNING" }
```

### 5.3 交互式会话 (SSE)

```
客户端                           服务端                          Agent 线程
  │                               │                                  │
  │ POST /api/sessions            │                                  │
  │ ───────────────────────────>  │                                  │
  │ { sessionId }                 │  创建 InProcessAgentSession      │
  │ <───────────────────────────  │                                  │
  │                               │                                  │
  │ PUT /api/sessions/events      │                                  │
  │   ?sessionId=xxx              │                                  │
  │ ─── SSE 连接 ──────────────>  │  注册 SseEventBridge             │
  │                               │                                  │
  │ POST /messages { "hello" }    │                                  │
  │ ───────────────────────────>  │  session.sendMessage()           │
  │                               │ ─────────────────────────────>   │
  │                               │                                  │ agent.run()
  │ SSE: text_chunk               │ <── 事件分发 ─────────────────   │ LLM 流式输出
  │ <───────────────────────────  │                                  │
  │ SSE: tool_approval_request    │ <── PermissionGate.wait() ────   │ 需要审批
  │ <───────────────────────────  │                                  │ [阻塞]
  │                               │                                  │
  │ POST /approve { APPROVE }     │                                  │
  │ ───────────────────────────>  │  PermissionGate.respond()        │
  │                               │ ─────────────────────────────>   │ 解除阻塞
  │ SSE: tool_result              │ <── 事件分发 ─────────────────   │ 工具已执行
  │ SSE: turn_complete            │ <── 事件分发 ─────────────────   │ 完成
  │ <───────────────────────────  │                                  │
```

### 5.4 OTLP Trace 接收

```
core-ai Agent 执行
    │
    │  AgentTracer / LLMTracer / FlowTracer
    │  (OpenTelemetry span，遵循 GenAI 语义约定)
    │
    ▼
OtlpHttpSpanExporter (批量, protobuf)
    │
    │  POST /v1/traces (或 /api/public/otel/v1/traces)
    │
    ▼
OTLPController
    │  ExportTraceServiceRequest.parseFrom(body)
    │
    ▼
OTLPIngestService
    │  遍历 ResourceSpans → ScopeSpans → Span：
    │    - 提取 traceId, spanId, parentSpanId (hex)
    │    - 提取属性 (gen_ai.*, langfuse.*, session.*, user.*)
    │    - 从 langfuse.observation.type 解析 span 类型
    │    - 根 span (无 parent) → upsert Trace 记录
    │    - 所有 span → insert Span 记录
    │
    ▼
MongoDB: traces + spans 集合
```

## 6. 认证模型

```
                    ┌──────────────────┐
                    │  AuthInterceptor │
                    └────────┬─────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
    Azure AD (企业)                  API Key (编程)
    X-Auth-Request-Email            Authorization: Bearer coreai_xxx
    首次登录自动创建用户              在 MongoDB 中验证 key
                                    检查 user.status == active
              │                              │
              └──────────────┬───────────────┘
                             ▼
                    userId → 请求上下文
```

**公开路由**（无需认证）：`/api/auth/register`、`/api/auth/login`、`/api/webhooks/*`

**Webhook 认证**：独立于用户认证，使用 Agent 专属的 `whk_` secret。

**用户生命周期**：注册 (pending) → 管理员审批 (active) → 生成 API Key

## 7. 分布式调度

调度器在每个 Server 副本中运行。防止重复触发的机制：

```
AgentScheduler.evaluate()
    │
    ▼
MongoDB 原子更新：
    filter:  { _id: scheduleId, next_run_at: { $lte: now } }
    update:  { $set: { next_run_at: <下次 cron 时间> } }
    │
    │  只有一个副本能成功（比较并交换）
    │  失败者得到 updateCount == 0，静默跳过
    │
    ▼
成功者继续触发 AgentRunner
```

此模式无需外部协调（无 ZooKeeper、无 Redis 锁）。MongoDB 的原子 `findOneAndUpdate` 提供分布式互斥。

## 8. 工具解析

```
AgentDefinition.toolIds = ["builtin-file-ops", "jira-mcp", "slack-mcp"]
                                    │
                                    ▼
                          ToolRegistryService.resolveTools()
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
               BUILTIN            MCP             API
           BuiltinTools.      McpToolCalls.    (规划中)
           FILE_OPERATIONS    of(manager, id)
                    │               │
                    └───────┬───────┘
                            ▼
                    List<ToolCall> → Agent.builder().toolCalls()
```

MCP 服务器由管理员注册，启动时加载，通过 `McpClientManager` 在所有 Agent 运行间共享。

## 9. SSE 事件类型

| 事件 | 方向 | 载荷 |
|------|------|------|
| `text_chunk` | Server → 客户端 | `{ chunk }` — 流式 LLM 输出 |
| `reasoning_chunk` | Server → 客户端 | `{ chunk }` — 思考/推理内容 |
| `reasoning_complete` | Server → 客户端 | `{ reasoning }` — 完整推理文本 |
| `tool_start` | Server → 客户端 | `{ callId, toolName, arguments }` |
| `tool_result` | Server → 客户端 | `{ callId, toolName, status, result }` |
| `tool_approval_request` | Server → 客户端 | `{ callId, toolName, arguments }` |
| `turn_complete` | Server → 客户端 | `{ output, inputTokens, outputTokens }` |
| `error` | Server → 客户端 | `{ message, detail }` |
| `status_change` | Server → 客户端 | `{ status }` — IDLE / RUNNING / ERROR |

## 10. 配置参考

### sys.properties

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `sys.http.port` | 8080 | HTTP 服务端口 |
| `sys.mongo.uri` | (必填) | MongoDB 连接字符串 |
| `sys.admin.email` | admin@example.com | 初始管理员邮箱 |
| `sys.admin.password` | admin | 初始管理员密码 |
| `sys.admin.name` | Admin | 初始管理员显示名 |
| `sys.file.storagePath` | ./data/files | 文件上传存储目录 |

### agent.properties

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `litellm.api.base` | — | LiteLLM 代理 URL |
| `litellm.api.key` | — | LiteLLM API 密钥 |
| `openai.api.base` | — | OpenAI API 基础 URL |
| `openai.api.key` | — | OpenAI API 密钥 |
| `trace.otlp.endpoint` | — | OTLP 导出端点（设为自身即可使用内置追踪） |
| `trace.service.name` | core-ai | 追踪中的服务名称 |
| `trace.service.version` | 1.0.0 | 追踪中的服务版本 |
| `trace.environment` | production | 部署环境 |
| `trace.otlp.public.key` | — | Langfuse 公钥（用于 Basic Auth） |
| `trace.otlp.secret.key` | — | Langfuse 密钥（用于 Basic Auth） |

### 运行时限制

| 参数 | 值 | 位置 |
|------|-----|------|
| 最大并发运行 | 10 | AgentRunner 线程池 |
| 默认运行超时 | 600 秒 (10 分钟) | AgentRunner |
| Transcript 截断 | 每个工具结果 10,240 字符 | AgentRunner |
| 调度器轮询间隔 | 1 分钟 | AgentSchedulerJob |
| OTLP 批量大小 | 512 spans | TelemetryConfig |
| OTLP 批量延迟 | 1 秒 | TelemetryConfig |

## 11. 数据库集合

| 集合 | 说明 | 关键索引 |
|------|------|----------|
| `users` | 用户账户 | `api_key` (唯一, 稀疏) |
| `agents` | Agent 定义 | `user_id` |
| `agent_runs` | 执行记录 | `agent_id`, `status`, `started_at` |
| `agent_schedules` | Cron 调度 | `agent_id`, `next_run_at` |
| `tool_registries` | 工具/MCP 注册 | — |
| `files` | 文件元数据 | `user_id` |
| `schema_versions` | 迁移追踪 | — |
| `traces` | Trace 记录 | `trace_id` (唯一), `session_id`, `created_at` |
| `spans` | Span 记录 | `trace_id`, `span_id` (唯一), `parent_span_id` |
| `prompt_templates` | Prompt 版本 | `name`, `status`, `created_at` |

## 12. 部署

### 单实例

```bash
# 配置
cat > sys.properties << 'EOF'
sys.mongo.uri=mongodb://localhost:27017/core-ai
EOF

cat > agent.properties << 'EOF'
openai.api.base=https://api.openai.com/v1
openai.api.key=sk-xxx
trace.otlp.endpoint=http://localhost:8080
EOF

# 运行
./gradlew :core-ai-server:run
```

### Kubernetes (多副本)

- 副本共享 MongoDB — 调度使用原子锁，无重复触发
- 会话存储在内存中 — 客户端必须重连到同一副本（sticky session），或交互式会话使用单副本
- 无状态 API 请求（运行、Agent、工具）可在任意副本间工作

### 将 core-ai 连接到 Server 追踪

```properties
# 在 core-ai 的 agent.properties 中
trace.otlp.endpoint=http://core-ai-server:8080

# 使用 Langfuse 路径（默认）：
# → 发送到 http://core-ai-server:8080/api/public/otel/v1/traces

# 使用 useLangfusePath=false：
# → 发送到 http://core-ai-server:8080/v1/traces
```

core-ai 无需任何代码改动。
