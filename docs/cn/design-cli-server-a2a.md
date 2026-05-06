# Core-AI A2A 框架与 CLI/Server 集成设计

> 版本：v2.0
> 日期：2026-05-06
> 状态：Design Draft

## 1. 背景

Core-AI 是通用 Agent 框架，`core-ai-cli` 和 `core-ai-server` 都是基于 Core-AI 构建出来的产品形态。

参考协议：

- A2A official specification: https://a2a-protocol.org/dev/specification/
- A2A and MCP: https://a2a-protocol.org/dev/topics/a2a-and-mcp/

当前需要解决的问题不是单纯的 CLI 远程调用 server，而是让任意 Core-AI Agent 都具备标准化的跨进程、跨框架协作能力：

- 有些 Tool 或 MCP 只能在 server 端执行，例如企业内部系统、集中托管的 MCP 连接、受控凭证、集群资源。
- CLI 是本地终端进程，无法直接访问这些 server-only Tool/MCP。
- `core-ai-server` 需要把 server 端 Agent 作为可发现、可调用、可流式订阅的远端 Agent 暴露出来。
- `core-ai-cli` 需要能像调用本地 `AgentSession` 一样调用远端 Agent。
- 未来还需要适配 LiteLLM、LangGraph、OpenAI Agents SDK 等非 Core-AI Agent 运行时。

因此，A2A 在 Core-AI 中被定位为**框架级 Agent 互操作层**，而不是 `core-ai-cli` 和 `core-ai-server` 之间的私有协议。

## 2. A2A 在 Core-AI 中是什么

A2A（Agent-to-Agent）在 Core-AI 中承担四件事：

| 能力 | Core-AI 中的含义 |
|---|---|
| Agent 发现 | 通过 Agent Card 描述一个 Agent 的身份、能力、输入输出模式、鉴权方式和协议入口。 |
| 消息发送 | 把用户或其他 Agent 的一次输入封装为 `Message`，交给远端 Agent 执行。 |
| 任务管理 | 每次远端执行形成一个 `Task`，有独立 `taskId`，并通过 `contextId` 关联多轮上下文。 |
| 事件流 | 通过 SSE 推送状态变化、文本增量、产物增量、input-required、完成、失败和取消。 |

Core-AI 不要求调用方理解被调用 Agent 的内部实现。调用方只依赖 A2A 的外部契约：

- `AgentCard`
- `Message`
- `Part`
- `Task`
- `TaskStatus`
- `TaskArtifactUpdateEvent`
- `TaskStatusUpdateEvent`

被调用方可以是 Core-AI Agent，也可以是其他框架 Agent，只要能适配成这个契约即可。

## 3. 设计原则

| 原则 | 决策 |
|---|---|
| 框架优先 | A2A 抽象放在 `core-ai` / `core-ai-api`，CLI 和 server 只是接入方。 |
| 新协议优先 | 旧 A2A/ACP 草案没有外部用户，不保留兼容层，直接按新模型改。 |
| 传输层可替换 | DTO 和运行时不绑定 Undertow 或 core-ng，HTTP handler 只是薄适配层。 |
| 事件系统复用 | 继续复用 `AgentEvent` / `AgentEventListener`，通过 adapter 转成 A2A stream event。 |
| Tool/MCP 位置显式 | Tool 是否只能 server 端执行，由 Agent 能力、Tool registry 和路由策略决定。 |
| 第三方可适配 | LiteLLM 等运行时通过 adapter 实现 Core-AI A2A provider/client 契约。 |

## 4. 模块边界

```
core-ai-api
  └─ ai.core.api.a2a
       AgentCard / Message / Part / Task / TaskStatus / StreamResponse ...

core-ai
  └─ ai.core.a2a
       A2AAgentProvider      // provider 侧契约
       A2AClient             // client 侧契约
       A2ARunManager         // Core-AI Agent -> A2A Task runtime
       A2AEventAdapter       // AgentEvent -> A2A stream event
       A2ATaskState          // task 状态、输出、等待输入、listener 生命周期

core-ai-cli
  └─ ai.core.cli.a2a
       Undertow A2A endpoint
       CLI 本地 serve 模式
       后续 RemoteAgentSession 通过 A2AClient 调 server Agent

core-ai-server
  └─ 后续新增 A2A web 层
       core-ng handler/web service
       复用 core-ai-api DTO 和 core-ai runtime
       暴露企业 server 端 Agent
```

核心边界：

- `core-ai-api` 只放协议 DTO，不依赖运行时。
- `core-ai` 放框架级 A2A runtime 和抽象，不依赖 CLI/server。
- `core-ai-cli` 和 `core-ai-server` 只负责 HTTP、安全、配置、进程生命周期。
- 第三方框架 adapter 不应反向污染 Core-AI Agent 核心模型。

## 5. 协议模型

### 5.1 Agent Card

Agent Card 是远端 Agent 的自描述文件。Core-AI 使用它做发现、能力协商和 UI 展示。

推荐入口：

```
GET /.well-known/agent-card.json
```

Core-AI 使用的关键字段：

| 字段 | 用途 |
|---|---|
| `name` / `description` / `version` | Agent 身份和版本。 |
| `supportedInterfaces` | 声明 HTTP+JSON、协议版本和 endpoint。 |
| `capabilities.streaming` | 是否支持 `/message:stream`。 |
| `capabilities.pushNotifications` | 是否支持 webhook 异步通知，首版不支持。 |
| `capabilities.extendedAgentCard` | 是否支持认证后的扩展 Agent Card。 |
| `skills` | Agent 可对外声明的能力，例如代码修改、文件操作、企业知识检索。 |
| `defaultInputModes` / `defaultOutputModes` | 默认输入输出 MIME 类型。 |
| `securitySchemes` / `securityRequirements` | server 端鉴权声明。 |

示例：

```json
{
  "name": "core-ai-coder",
  "description": "Core-AI coding agent with server-side tools and MCP access",
  "version": "1.0.0",
  "supportedInterfaces": [
    {
      "url": "https://core-ai.example.com/a2a/agents/coder",
      "protocolBinding": "HTTP_JSON",
      "protocolVersion": "1.0"
    }
  ],
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "extendedAgentCard": true
  },
  "skills": [
    {
      "id": "code-review",
      "name": "code-review",
      "description": "Review code changes and propose fixes",
      "inputModes": ["text/plain", "application/json"],
      "outputModes": ["text/plain", "application/json"]
    }
  ],
  "defaultInputModes": ["text/plain"],
  "defaultOutputModes": ["text/plain"]
}
```

### 5.2 Message 和 Part

`Message` 表示一次通信 turn：

```json
{
  "role": "ROLE_USER",
  "messageId": "m-1",
  "contextId": "ctx-1",
  "parts": [
    {
      "text": "帮我检查这个 PR 的风险",
      "mediaType": "text/plain"
    }
  ]
}
```

`Part` 使用 oneof 风格表达内容。一个 part 一般只设置以下字段之一：

| 字段 | 用途 |
|---|---|
| `text` | 文本内容。 |
| `raw` | base64 或其他原始字节文本表示。 |
| `url` | 文件或资源 URI。 |
| `data` | JSON 结构化数据。 |

辅助字段：

| 字段 | 用途 |
|---|---|
| `filename` | 文件名。 |
| `mediaType` | MIME 类型。 |
| `metadata` | 扩展元数据。 |

设计决策：

- 不保留旧草案中的 `type`。
- 当前 DTO 也不依赖 discriminator 字段；通过 `text/raw/url/data` 哪个字段存在判断具体内容类型。
- 如果未来需要适配仍发送 `kind` 的旧客户端，应放到独立兼容 adapter，不进入核心 DTO。

### 5.3 Task 和 Context

| 概念 | 含义 |
|---|---|
| `taskId` | 一次远端执行的唯一 ID。 |
| `contextId` | 多轮对话上下文 ID，可被多个 task 复用。 |
| `Task.status` | 当前执行状态。 |
| `Task.artifacts` | Agent 已产生的输出产物。 |
| `Task.history` | 可选的消息历史。 |

状态机：

```
SUBMITTED
  └─ WORKING
       ├─ INPUT_REQUIRED ── 用户/调用方补输入 ──> WORKING
       ├─ AUTH_REQUIRED  ── 补充认证 ──────────> WORKING
       ├─ COMPLETED
       ├─ FAILED
       ├─ CANCELED
       └─ REJECTED
```

Core-AI 内部映射：

| Core-AI 事件 | A2A 状态或事件 |
|---|---|
| session 创建 | `TASK_STATE_SUBMITTED` / `TASK_STATE_WORKING` |
| 文本 token/chunk | `TaskArtifactUpdateEvent` |
| 工具开始/进度 | `TaskStatusUpdateEvent(state=WORKING)` |
| 工具审批请求 | `TaskStatusUpdateEvent(state=INPUT_REQUIRED)` |
| turn 完成 | `TaskStatusUpdateEvent(state=COMPLETED, final=true)` |
| 用户取消 | `TaskStatusUpdateEvent(state=CANCELED, final=true)` |
| 异常 | `TaskStatusUpdateEvent(state=FAILED, final=true)` |

## 6. HTTP Binding

Core-AI 首选 HTTP+JSON binding。当前 CLI serve 模式已经使用以下 endpoint，server 侧后续保持同构：

| Endpoint | 方法 | 用途 |
|---|---|---|
| `/.well-known/agent-card.json` | `GET` | 获取 Agent Card。 |
| `/message:send` | `POST` | 非流式发送消息；返回 `SendMessageResponse`。 |
| `/message:stream` | `POST` | 流式发送消息；返回 SSE `StreamResponse`。 |
| `/tasks/{taskId}` | `GET` | 查询 task 当前状态。 |
| `/tasks/{taskId}:cancel` | `POST` | 取消 task。 |

请求头：

| Header | 用途 |
|---|---|
| `Content-Type: application/a2a+json` | A2A JSON 请求体。 |
| `Accept: text/event-stream` | 请求 SSE 流式响应。 |
| `A2A-Version` | 客户端声明协议版本。 |
| `A2A-Extensions` | 后续扩展协商。 |
| `Authorization` | server 端鉴权。 |

流式响应使用 SSE，每个 `data:` 是一个 `StreamResponse` oneof：

```json
{"task":{"id":"t-1","contextId":"ctx-1","status":{"state":"TASK_STATE_WORKING"}}}
```

```json
{"artifactUpdate":{"taskId":"t-1","contextId":"ctx-1","append":false,"lastChunk":false,"artifact":{"artifactId":"a-1","parts":[{"text":"正在检查","mediaType":"text/plain"}]}}}
```

```json
{"statusUpdate":{"taskId":"t-1","contextId":"ctx-1","status":{"state":"TASK_STATE_COMPLETED"}}}
```

## 7. 端到端调用流程

### 7.1 CLI 调 server Agent

```
core-ai-cli
  └─ 读取 server.url / token / agentId
  └─ GET /.well-known/agent-card.json
  └─ 根据 Agent Card 选择 HTTP_JSON + streaming
  └─ 用户输入
      └─ POST /message:stream
          └─ server 创建/复用 AgentSession(contextId)
          └─ server 返回 Task + SSE updates
          └─ CLI 把 A2A stream event 转成本地 AgentEvent
          └─ 现有 TerminalUI 渲染
```

CLI 侧的目标不是重写 UI，而是新增一个远端 session 实现：

```java
AgentSession
  ├─ InProcessAgentSession   // 本地 Agent
  └─ RemoteAgentSession      // A2A client，后续新增
```

这样 `CliEventListener`、`TerminalUI`、slash command 的大部分逻辑可以继续复用。

### 7.2 server 暴露企业 Agent

```
core-ai-server
  └─ AgentDefinition / AgentRouter
      └─ 构建 Core-AI Agent
          └─ A2ARunManager
              └─ AgentSession
                  └─ AgentEvent
                      └─ A2AEventAdapter
                          └─ StreamResponse SSE
```

server 侧新增 web 层只做：

- 鉴权和租户解析。
- agentId 到 Agent 定义的路由。
- HTTP request/response 与 `A2AAgentProvider` 的桥接。
- task/context 的持久化和恢复策略。

核心执行仍在 `core-ai`。

## 8. server-only Tool/MCP 路由

### 8.1 问题

CLI 本地进程不能访问某些 Tool/MCP：

- 企业内网 API。
- 集中注册的 MCP server。
- server 侧托管的密钥和 OAuth token。
- 沙箱、构建集群、知识库索引等集中资源。

这些能力应由 server Agent 执行，而不是要求 CLI 本地安装和授权。

### 8.2 设计

Tool/MCP 注册时声明执行位置：

| 执行位置 | 含义 |
|---|---|
| `LOCAL` | 只能本地执行，例如用户 workspace 文件编辑。 |
| `SERVER` | 只能 server 执行，例如企业 MCP、集中凭证。 |
| `ANY` | 本地/server 都可执行，由策略选择。 |

路由策略：

| 场景 | 行为 |
|---|---|
| CLI 用户选择 server Agent | 用户消息直接通过 A2A 发到 server，server 使用 server-side Tool/MCP。 |
| 本地 Agent 发现需要 server-only tool | 把能力封装成远端 Agent/tool，通过 A2A 委托给 server。 |
| server Agent 需要本地 workspace 操作 | 首版不做反向通道；后续可通过 LocalAgent Provider 或受控 tool approval 扩展。 |

首版优先级：

1. 支持 CLI 作为 A2A client 调 server Agent。
2. 支持 server Agent 使用 server-only Tool/MCP。
3. 暂不实现 server 反向调用 CLI 本地文件/shell。

反向本地工具调用是更高风险能力，需要单独设计安全边界、路径白名单、审批、审计和断线恢复。

## 9. input-required 和审批

当远端 Agent 需要调用方补充输入或审批时，task 进入 `TASK_STATE_INPUT_REQUIRED`。

工具审批示例：

```json
{
  "statusUpdate": {
    "taskId": "t-1",
    "contextId": "ctx-1",
    "status": {
      "state": "TASK_STATE_INPUT_REQUIRED",
      "message": {
        "role": "ROLE_AGENT",
        "parts": [
          {
            "data": {
              "type": "tool_approval",
              "callId": "call-1",
              "tool": "shell.exec",
              "command": "./gradlew check"
            },
            "mediaType": "application/json"
          }
        ]
      }
    }
  }
}
```

调用方恢复 task：

```json
{
  "message": {
    "role": "ROLE_USER",
    "messageId": "m-approve-1",
    "taskId": "t-1",
    "parts": [
      {
        "data": {
          "decision": "approve",
          "call_id": "call-1"
        },
        "mediaType": "application/json"
      }
    ]
  }
}
```

Core-AI runtime 映射：

```
ToolApprovalRequestEvent
  -> A2AEventAdapter
  -> TaskStatusUpdateEvent(INPUT_REQUIRED)
  -> client approve/deny
  -> A2ARunManager.resumeTask()
  -> AgentSession.approveToolCall()
```

## 10. 第三方 Agent 适配

A2A adapter 的目标是让外部 Agent 运行时接入 Core-AI，而不是把外部框架模型泄漏进核心。

建议抽象：

```java
public interface RemoteAgentClient extends AutoCloseable {
    AgentCard getAgentCard();

    A2AInvocationResult send(SendMessageRequest request);

    Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request);

    Task getTask(GetTaskRequest request);

    Task cancelTask(CancelTaskRequest request);
}
```

```java
public interface RemoteAgentProvider {
    AgentCard getAgentCard();

    A2AInvocationResult send(SendMessageRequest request);

    Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request);

    Task getTask(GetTaskRequest request);

    Task cancelTask(CancelTaskRequest request);
}
```

现有 `A2AClient` / `A2AAgentProvider` 已经接近这个形态。后续可以选择：

- 直接沿用 `A2AClient` / `A2AAgentProvider` 命名。
- 或重命名为更通用的 `RemoteAgentClient` / `RemoteAgentProvider`，A2A 只是首个协议实现。

LiteLLM adapter 形态：

```
LiteLLM Agent
  └─ LiteLLMAgentProvider implements A2AAgentProvider
       ├─ AgentCard: 从模型、工具、metadata 生成
       ├─ send: 调 LiteLLM 非流式接口
       ├─ stream: 调 LiteLLM streaming 并转 A2AStreamEvent
       ├─ getTask: 从 adapter task registry 查询
       └─ cancelTask: 尽力取消或标记 canceled
```