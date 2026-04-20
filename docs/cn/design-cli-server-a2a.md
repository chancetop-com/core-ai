# CLI ↔ core-ai-server A2A 交互机制技术设计

> 版本：v1.0
> 作者：Xander
> 日期：2026-04-20
> 状态：Draft

## 1. 背景与目标

当前 `core-ai-cli` 通过进程内 `LocalAgentSession` 直接运行 Agent，所有推理、工具调用都在本地进程。随着业务演进，需要：

- Server 侧托管多个 Agent（coder、planner 等），统一管理模型配额、工具集、会话存储
- CLI 作为轻量远程客户端，用户机器上不再运行大模型推理
- 仍保留"本地文件读写、shell 执行"等必须在用户机器执行的能力
- 与外部 A2A 生态互通（未来可被其他 Agent 调用，或调用其他 Agent）

本设计采用 [A2A (Agent-to-Agent) 协议](https://google-a2a.github.io/A2A/)，通过**双 Agent 模式**解决"远程推理 + 本地工具"的矛盾。

## 2. 设计约束

| 项 | 决策 |
|---|---|
| 协议 | 标准 A2A（JSON-RPC 2.0 + SSE） |
| 用户模型 | 单用户单账号（server.token） |
| Agent 部署 | Server 侧多 Agent；CLI 内嵌一个 LocalAgent |
| Tool 执行位置 | 默认 server 侧；本地文件/shell 走 LocalAgent |
| 网络 | CLI 在用户内网，不要求公网可达 |
| 向后兼容 | 保留 `LocalAgentSession` 纯本地模式 |

## 3. 整体架构

```
┌─────────────────────────────────────┐             ┌────────────────────────────────┐
│  本地机器 (core-ai-cli)              │             │  core-ai-server                │
│                                     │             │                                │
│  TerminalUI                         │             │  A2A Gateway                   │
│  └─ StreamingMarkdownRenderer       │             │   /agents/{id}/.well-known/    │
│                                     │             │   /agents/{id}/a2a   (RPC+SSE) │
│  AgentSession (interface)           │  ① 主链路   │   /agents/{id}/reverse-ws      │
│  ├─ LocalAgentSession (legacy)      │             │                                │
│  └─ RemoteAgentSession ─────────────┼─JSON-RPC ──▶│  MainAgent (coder/planner/…)   │
│       └─ A2AClient                  │  + SSE      │  ├─ Server-side Tools          │
│                                     │             │  │   search/build/test/...    │
│  LocalAgent (in-process A2A server) │             │  │                              │
│   ├─ 127.0.0.1:<random>             │             │  └─ LocalAgentProxy            │
│   ├─ fs.read / fs.write / fs.edit   │             │      (A2A client 反向调 CLI)   │
│   └─ shell.exec (workspace 白名单)   │             │          ▲                     │
│       ▲                             │             │          │                     │
│       │  ② 反向通道 (JSON-RPC/WS)   │             │          │                     │
│       └─────reverse WebSocket ──────┼─────────────┘          │                     │
└─────────────────────────────────────┘                        └──────────────────────┘
```

两条独立链路：

| 链路 | 物理方向 | 协议 | 职责 |
|---|---|---|---|
| ① 主链路 | CLI → Server | JSON-RPC + SSE | 用户消息、token 流、Task 状态 |
| ② 反向通道 | CLI → Server (TCP)，逻辑上 Server → CLI | JSON-RPC over WebSocket | MainAgent 调 LocalAgent 的本地工具 |

## 4. A2A 协议映射

### 4.1 概念映射

| CLI / 现有概念 | A2A / Server 概念 |
|---|---|
| `SessionId`（本地） | `contextId`（server 持久化） |
| 一次用户输入 | 一个 `Task`（新 `taskId`，复用 `contextId`） |
| `AgentEvent`（TOKEN/TOOL/DONE） | `TaskStatusUpdateEvent` / `TaskArtifactUpdateEvent` |
| `/resume <id>` | `tasks/get` 拉历史 + 新 Task 复用 contextId |
| `/agent <id>` | 切换 endpoint 到 `/agents/<id>/a2a` |
| `/tools` | 读取 Agent Card 的 `skills` 字段 |
| Ctrl+C 双击 | `tasks/cancel` RPC |
| SSE 断线重连 | `tasks/resubscribe` RPC |

### 4.2 URL 约定

```
GET  /agents                                    # 列出所有 server Agent
GET  /agents/{agentId}/.well-known/agent.json   # Agent Card
POST /agents/{agentId}/a2a                      # JSON-RPC 入口
GET  /agents/{agentId}/a2a/stream?taskId=...    # SSE (message/stream, tasks/resubscribe)
WS   /agents/{agentId}/reverse-ws               # 反向通道（LocalAgent 注册）
```

### 4.3 A2A RPC 方法支持

| 方法 | 支持 | 用途 |
|---|---|---|
| `message/send` | ✅ | 非流式发送（少用） |
| `message/stream` | ✅ | 主路径，CLI 每次输入走这个 |
| `tasks/get` | ✅ | `/resume` 拉历史 |
| `tasks/cancel` | ✅ | Ctrl+C |
| `tasks/resubscribe` | ✅ | SSE 断线恢复 |
| `tasks/pushNotificationConfig/set` | ❌ | CLI 常驻，不需要 webhook |

## 5. 事件桥接

### 5.1 Server 侧：`AgentEvent` → A2A 事件

新增 `A2AEventBridge implements AgentEventListener`，订阅本地 Agent 事件并转为 A2A 流事件推 SSE：

| 本地 AgentEvent | A2A 事件 | 字段说明 |
|---|---|---|
| `TOKEN_DELTA` | `TaskArtifactUpdateEvent` | `append=true`，text part |
| `TOOL_CALL_START` | `TaskStatusUpdateEvent` | `state=working`，message 描述工具 |
| `TOOL_CALL_RESULT` | `TaskArtifactUpdateEvent` | data part，结构化结果 |
| `MESSAGE_DONE` | `TaskArtifactUpdateEvent` | `lastChunk=true` |
| `TASK_COMPLETED` | `TaskStatusUpdateEvent` | `state=completed`，`final=true` |
| `ERROR` | `TaskStatusUpdateEvent` | `state=failed`，`final=true` |

**不改动现有本地事件系统**，只增加一个 listener 实现。

### 5.2 CLI 侧：SSE → `AgentEvent`

`RemoteAgentSession` 把 SSE 事件反向翻译成 `AgentEvent`，喂给现有的 `CliEventListener`。

**UI 层（TerminalUI、StreamingMarkdownRenderer、TableRenderer）零改动。**

## 6. 反向通道设计（方案 1：双 Agent）

### 6.1 为什么需要

MainAgent 在 server 上，要读写的是用户本地文件。纯正向链路做不到，必须有从 server → CLI 的调用通道。

### 6.2 反向 WebSocket 协议

CLI 启动时主动连 server 的 `/agents/{id}/reverse-ws`，建立长连接后**通过 WS 承载 JSON-RPC**，server 内的 `LocalAgentProxy` 通过这条 WS 发请求、收响应。

**帧格式（WS text frame，JSON）**：

```json
// 1. 注册 (CLI → Server，连接后首帧)
{
  "type": "register",
  "clientVersion": "core-ai-cli/1.0.0",
  "agentCard": {
    "name": "local-agent",
    "capabilities": { "streaming": false },
    "skills": [
      {"id":"fs.read",   "name":"read_file",   "inputModes":["data"], "outputModes":["data"]},
      {"id":"fs.write",  "name":"write_file",  "inputModes":["data"], "outputModes":["data"]},
      {"id":"fs.edit",   "name":"edit_file",   "inputModes":["data"], "outputModes":["data"]},
      {"id":"shell.exec","name":"bash",        "inputModes":["data"], "outputModes":["data"]}
    ]
  },
  "workspace": "/Users/xander/git_repo/core-ai"
}

// 2. 注册确认 (Server → CLI)
{
  "type": "register-ack",
  "sessionId": "sess-abc123",
  "mainAgentId": "coder",
  "serverTime": "2026-04-20T10:00:00Z"
}

// 3. 反向 RPC 请求 (Server → CLI)
{
  "type": "rpc-req",
  "id": "r-123",
  "method": "message/send",
  "params": {
    "message": {
      "role": "user",
      "parts": [{
        "kind": "data",
        "data": { "tool": "fs.write", "path": "src/Foo.java", "content": "..." }
      }]
    }
  }
}

// 4. 反向 RPC 响应 (CLI → Server)
{
  "type": "rpc-res",
  "id": "r-123",
  "result": {
    "kind": "task",
    "id": "local-task-xxx",
    "status": { "state": "completed" },
    "artifacts": [{
      "parts": [{ "kind": "data", "data": { "bytesWritten": 1024 } }]
    }]
  }
}

// 5. 心跳 (双向，30s 间隔)
{ "type": "ping", "ts": 1713600000000 }
{ "type": "pong", "ts": 1713600000000 }

// 6. 错误
{ "type": "rpc-err", "id": "r-123", "error": { "code": -32603, "message": "path not allowed" } }
```

### 6.3 连接生命周期

```
CLI 启动
  └─ 读 agent.properties (server.url, server.token)
  └─ 启动 in-process LocalAgent (127.0.0.1:random)
  └─ WS 连 /agents/main/reverse-ws (Bearer server.token)
      └─ register 帧
      └─ 收到 register-ack
      └─ 进入 REPL，准备接收用户输入

运行期
  └─ 每 30s 心跳
  └─ 收到 rpc-req → 路由到 LocalAgent → 回 rpc-res
  └─ 连接断开
      └─ 指数退避重连 (1s, 2s, 4s, 8s, max 30s)
      └─ server 侧：该 contextId 正在执行的 Task 挂起 60s 等重连
      └─ 60s 未恢复 → Task 进入 failed

CLI 退出
  └─ 主动 close WS (正常关闭码 1000)
  └─ server 清理 LocalAgentProxy 绑定
```

### 6.4 能力协商

MainAgent 的 Agent Card 声明所需的 LocalAgent skill：

```json
"requiredLocalSkills": ["fs.read", "fs.write", "shell.exec"]
```

CLI register 时 server 校验，缺失 skill 直接返回错误并提示升级 CLI。避免 server 演进导致老 client 静默异常。

## 7. 会话与状态管理

### 7.1 Session 存储

| 项 | 实现 |
|---|---|
| Key | `contextId` |
| Value | `{ messages, createdAt, lastActiveAt, metadata }` |
| 首版 | 内存 `ConcurrentHashMap` |
| 正式 | SQLite（CLI 侧本地缓存用于展示）+ server 侧持久化（真相） |

### 7.2 `/resume` 流程

```
CLI: /resume <contextId>
  └─ RemoteAgentSession 调 tasks/get → 拉最后 N 条消息
  └─ CLI 本地回放展示
  └─ 后续 message/stream 都带上这个 contextId
```

### 7.3 中断与取消

- 正在执行的 Task：CLI 调 `tasks/cancel` → server 触发 `AgentSession.cancel()` → 推 `final=true` 的 `canceled` 事件
- SSE 断：CLI 用 `tasks/resubscribe` 重建流，不丢事件（server 按 `eventId` 做断点续传）

## 8. 鉴权与安全

### 8.1 凭证

```
~/.core-ai/agent.properties
─────────────────────────────
server.url=https://ai.example.com
server.token=<long-lived-token>
```

- 所有 HTTP/WS 请求带 `Authorization: Bearer <server.token>`
- Server 颁发 per-session 的 `sessionToken`（短期），绑定 `contextId` + 反向 WS 连接

### 8.2 本地安全（关键）

LocalAgent 面向 server，**必须假设 server 可能被攻破**：

1. **路径白名单**：fs.* 工具只允许 `workspace` 目录及其子目录，拒绝 `..`、符号链接逃逸
2. **Shell 白名单 + 超时**：`shell.exec` 默认 30s 超时；可配置命令前缀白名单（git、npm、gradle 等）
3. **大小限制**：写入文件 ≤ 10 MB；shell 输出 ≤ 1 MB
4. **拒绝敏感路径**：`.ssh`、`.aws`、`~/.core-ai` 本身等硬编码拒绝
5. **仅绑 127.0.0.1**：LocalAgent 不监听公网

### 8.3 审计

所有 LocalAgent 工具调用本地打 audit log（`~/.core-ai/audit.log`），含时间、taskId、tool、参数摘要、结果 size。

## 9. CLI 侧改造

### 9.1 抽象 `AgentSession`

```java
public interface AgentSession {
    CompletableFuture<Void> send(String input);
    void cancel();
    void addEventListener(AgentEventListener listener);
    String sessionId();
    void close();
}
```

两个实现：
- `LocalAgentSession`（现有，纯本地 Agent，不连 server）
- `RemoteAgentSession`（新增，A2A client）

选择逻辑：`agent.properties` 中 `server.url` 有值 → Remote；否则 Local。

### 9.2 `RemoteAgentSession` 内部组件

```
RemoteAgentSession
 ├─ A2AClient            // JSON-RPC + SSE client
 ├─ contextId            // 会话绑定
 ├─ SseEventTranslator   // SSE → AgentEvent
 └─ (持有) LocalAgentHost // 启动本地 A2A server + reverse WS
```

### 9.3 Slash 命令适配

| 命令 | 本地模式 | 远程模式 |
|---|---|---|
| `/model` | 本地切 LLMProvider | `message/send` metadata 带 model 提示（server 决定是否允许） |
| `/agent <id>` | N/A | 切 endpoint |
| `/tools` | 本地列表 | 读 Agent Card `skills` + LocalAgent skills |
| `/resume <id>` | 本地文件恢复 | `tasks/get` + 复用 contextId |
| `/stats` | 本地统计 | 拉 server 端统计 |
| `/copy` `/clear` `/help` | 不变 | 不变 |
| `/compact` | 本地 | server 侧压缩（新增 RPC `sessions/compact`，非 A2A 标准） |

## 10. Server 侧改造

### 10.1 新增模块 / 组件

```
core-ai-server/
 ├─ a2a/
 │   ├─ A2AController          // /agents/{id}/a2a, /.well-known/
 │   ├─ ReverseWsHandler       // /agents/{id}/reverse-ws
 │   ├─ A2AEventBridge         // AgentEvent → SSE
 │   ├─ SessionStore           // contextId → messages
 │   ├─ TaskRegistry           // taskId → running AgentSession
 │   └─ LocalAgentProxy        // 以 A2A client 形式调反向 WS
 └─ agent/
     ├─ AgentRouter            // agentId → MainAgent 实例
     └─ MainAgentFactory
```

### 10.2 `LocalAgentProxy` 实现要点

- 对 MainAgent 呈现为一个标准 A2A `Agent` 接口（像调外部 agent 一样调 sub-agent）
- 内部所有 RPC 请求通过已注册的反向 WS 发出
- 未注册 / 已断开 → 立即返回错误（让 MainAgent 决定 retry 或放弃）
- 支持并发请求（`rpc-req` 的 `id` 区分响应）

## 11. 落地计划

### Phase 1：主链路（2 周）
1. `core-ai-api` 新增 A2A 类型：`AgentCard`、`Task`、`Message`、`Part`、`TaskStatus`、`TaskArtifactUpdateEvent`、`TaskStatusUpdateEvent`
2. Server 实现 `A2AController` + `SessionStore`（内存）
3. Server 实现 `A2AEventBridge`，接现有 Agent 事件推 SSE
4. CLI 抽象 `AgentSession` 接口，实现 `RemoteAgentSession`（仅主链路，不带反向通道）
5. **里程碑**：CLI 能连 server 聊天，但本地文件工具不可用

### Phase 2：反向通道（2 周）
6. CLI 内嵌 `LocalAgent`（复用现有 Tool 框架）
7. 反向 WS 协议（`ReverseWsHandler` + `LocalAgentHost`）
8. `LocalAgentProxy` 接入 MainAgent 工具系统
9. 路径/命令白名单、审计日志
10. **里程碑**：远程 MainAgent 能调本地文件/shell 工具

### Phase 3：可靠性（1 周）
11. `tasks/resubscribe` 断点续传
12. SQLite 持久化（server 侧 session，CLI 侧缓存）
13. Bearer token + per-session token
14. 能力协商、版本检查
15. **里程碑**：生产可用

### Phase 4：生态（可选）
16. 支持外部 A2A agent 作为 sub-agent（server 调第三方）
17. MainAgent Card 对外公开，允许第三方 A2A client 调用 core-ai-server
18. Push notification 支持（为 Web UI 场景铺垫）

## 12. 开放问题

1. **Server 侧 Agent 热插拔**：新增 agent 是否需要重启？首版静态配置，后续通过 Agent 注册中心。
2. **多机器同步**：用户两台机器同一 server.token，`/resume` 能跨机器吗？首版：contextId 可查但 LocalAgent 能力取决于当前连接机器，可能导致历史步骤无法复现。
3. **Tool 版本漂移**：server 升级后要求新 LocalAgent skill，老 CLI 怎么办？靠能力协商 + 明确错误提示。
4. **离线 / 弱网**：主链路 SSE 可重连；反向 WS 断开期间 MainAgent 的本地工具调用全部失败。是否需要"降级模式"（放弃本地操作继续推理）？待定。

## 13. 附录：A2A 消息示例

### 13.1 用户发送消息（正向）

```json
// CLI → Server: POST /agents/coder/a2a
{
  "jsonrpc": "2.0", "id": "1",
  "method": "message/stream",
  "params": {
    "message": {
      "role": "user",
      "messageId": "m-1",
      "contextId": "ctx-abc",
      "parts": [{ "kind": "text", "text": "帮我在 Foo.java 里加个 hello 方法" }]
    }
  }
}

// Server → CLI: SSE stream
event: status
data: {"taskId":"t-1","contextId":"ctx-abc","status":{"state":"working"}}

event: artifact
data: {"taskId":"t-1","artifact":{"artifactId":"a-1","parts":[{"kind":"text","text":"好的，"}],"append":true}}

event: artifact
data: {"taskId":"t-1","artifact":{"artifactId":"a-1","parts":[{"kind":"text","text":"我来修改"}],"append":true,"lastChunk":true}}

event: status
data: {"taskId":"t-1","status":{"state":"completed"},"final":true}
```

### 13.2 本地工具调用（反向）

```json
// Server (LocalAgentProxy) → CLI (via reverse WS)
{
  "type": "rpc-req",
  "id": "r-42",
  "method": "message/send",
  "params": {
    "message": {
      "role": "user",
      "parts": [{
        "kind": "data",
        "data": {
          "skill": "fs.edit",
          "args": {
            "path": "src/Foo.java",
            "oldString": "class Foo {",
            "newString": "class Foo {\n    public void hello() {}\n"
          }
        }
      }]
    }
  }
}

// CLI → Server
{
  "type": "rpc-res",
  "id": "r-42",
  "result": {
    "kind": "task",
    "id": "local-t-7",
    "status": { "state": "completed" },
    "artifacts": [{
      "artifactId": "local-a-1",
      "parts": [{ "kind": "data", "data": { "ok": true, "bytesWritten": 128 } }]
    }]
  }
}
```
