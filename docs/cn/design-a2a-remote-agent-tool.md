# Core-AI A2A Remote Agent as Tool 设计

> 版本：v1.1
> 日期：2026-05-06
> 状态：Design Draft

## 0. 决策摘要

一句话：**把 server 端 Agent 暴露成本地 Agent 的一个 Tool，本地 Agent 保持主控，远端 Agent 只承担 server-only 能力。**

| 决策点 | 结论 |
|---|---|
| 第一阶段模式 | Remote Agent as Tool，不做 handoff。 |
| 本地/远端责任 | 本地 Agent 负责对话、本地上下文和最终整合；远端 Agent 负责 server-only Tool/MCP/沙箱/内部系统。 |
| Tool 输入 | P0 只暴露 `query`，不让 LLM 直接传 `taskId` / `contextId`。 |
| 调用方式 | P0 使用 `message/stream`，阻塞等待 terminal state，再返回最终文本。 |
| 状态管理 | `contextId` / `taskId` 由 tool 管理，LLM 不可见或仅作为诊断信息出现。 |
| 并发 | 同一 local session + remote agent + remote context 默认串行。 |
| 本地数据外发 | 默认不发送本地文件全文，只发送必要摘要或用户明确指定内容。 |
| `INPUT_REQUIRED` | P0 不做完整续接链路，返回可读提示让本地 Agent 询问用户；P1 再接 `submitInput`。 |
| CLI 配置 | 推荐使用 `a2a.remoteServers.*` 自动发现 server agents；`a2a.remoteAgents.*` 保留为手动 pin 单个 agent 的高级配置。 |

P0 的验收标准只有一个：**本地 CLI Agent 能在同一会话中调用 server Agent 完成 server-only 子任务，并把结果纳入本地最终回答。**

## 1. 背景

Core-AI 是通用 Agent 框架，`core-ai-cli` 和 `core-ai-server` 都只是基于 Core-AI 构建的运行形态。

当前已经具备两类 A2A 能力：

- `core-ai-server` 可以通过 A2A 暴露 server 端 Agent。
- `core-ai-cli --server` 可以把整个 CLI 会话切到远端 A2A Agent。

但用户实际想要的协作模型不是“CLI 全程远程执行”，而是：

```
本地 Agent 仍然掌控对话、读取本地上下文、使用本地工具
  在需要企业 MCP、server-only Tool、server 沙箱或内部系统时
    通过 A2A 调用 server 上的 Agent
  再由本地 Agent 综合远端结果给用户答复
```

因此，这份文档聚焦一个更具体的能力：把远端 A2A Agent 暴露成 Core-AI 本地 Agent 可调用的 Tool，也就是 **Remote Agent as Tool**。

相关基础文档：

- [A2A 框架与 CLI/Server 集成设计](design-cli-server-a2a.md)
- A2A Specification: https://a2a-protocol.org/latest/specification/
- OpenAI Agents SDK Tools: https://openai.github.io/openai-agents-js/guides/tools/
- OpenAI Agents SDK Handoffs: https://openai.github.io/openai-agents-js/guides/handoffs/
- LangChain Multi-agent Patterns: https://docs.langchain.com/oss/javascript/langchain/multi-agent
- AutoGen AgentTool: https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/agents.html
- Semantic Kernel Agent Orchestration: https://learn.microsoft.com/en-us/semantic-kernel/frameworks/agent/agent-orchestration/
- LiteLLM: https://docs.litellm.ai/
- MCP Client Concepts: https://modelcontextprotocol.io/docs/learn/client-concepts

## 2. 外部框架调研结论

### 2.1 Agent as Tool

OpenAI Agents SDK、AutoGen、LangChain 都支持把一个 Agent 包装成 Tool。

典型特征：

| 特征 | 说明 |
|---|---|
| 主 Agent 保持控制权 | 子 Agent 只处理被委托的子任务。 |
| 子 Agent 对主 Agent 表现为函数调用 | 输入通常是 `query` / `input`，输出是文本或结构化结果。 |
| 上下文隔离 | 子 Agent 内部消息、工具调用、推理过程不直接污染主 Agent 历史。 |
| 主 Agent 负责最终整合 | 子 Agent 结果回到主 Agent，由主 Agent 决定下一步。 |

这正好符合 Core-AI 的场景：本地 Agent 继续掌控本地 workspace 和用户交互，server Agent 只承担 server-only 能力。

### 2.2 Handoff

OpenAI、LangChain、Semantic Kernel 都把 Handoff 定义为“把对话控制权转给另一个 Agent”。

它适合：

- 客服分流。
- 专家接管。
- 多阶段直接面向用户的对话。

它不适合作为当前 MVP：

- 本地 Agent 会失去主控权。
- 本地 workspace、文件上下文、用户偏好不一定传给远端。
- 远端 Agent 的回复需要再回到本地 Agent 整合，否则无法形成“协作”。

结论：Handoff 是后续能力，不作为第一阶段主路径。

### 2.3 Graph / Orchestration Runtime

LangGraph、AutoGen SelectorGroupChat、Semantic Kernel Orchestration 都提供 runtime、message routing、selector、group chat 等机制。

它适合：

- 多个 Agent 平行或轮流协作。
- 需要显式 DAG / workflow 的复杂任务。
- server 侧稳定自动化流程。

但对当前问题过重。我们第一阶段只需要一个标准 Tool，让本地 Agent 能调用远端 Agent。后续可以在 Core-AI 中把 Remote Agent Tool 作为 orchestration graph 的一个 node。

### 2.4 LiteLLM

LiteLLM 更像 LLM Gateway，而不是 Agent-to-Agent 协议。它的价值在于：

- 统一外部模型接口。
- 中央鉴权、budget、rate limit。
- retry/fallback。
- observability。

对 Core-AI 的启发是：远端 Agent 配置也应该像 gateway 一样具备统一入口、鉴权隔离、超时、fallback 和审计，而不是写死 `core-ai-server`。

### 2.5 MCP

MCP 的 Roots、Sampling、Elicitation 强调一件事：能力可以在 server 侧，但权限、用户输入和安全边界仍由 client 控制。

映射到 A2A Remote Agent Tool：

| MCP 概念 | Core-AI A2A 映射 |
|---|---|
| Roots | 本地 workspace 范围不默认暴露给 server；需要显式传递摘要或文件。 |
| Elicitation | 远端 `INPUT_REQUIRED` 回到本地 Agent/用户。 |
| Sampling | 后续可允许 server 通过 A2A 请求 client 侧模型，但 MVP 不做反向调用。 |

## 3. 设计目标

### 3.1 目标

- 在 `core-ai` 层提供通用 `A2ARemoteAgentToolCall`。
- 任意 Core-AI Agent 都能把 A2A-compatible Agent 加入自己的 tools。
- `core-ai-cli` 能通过配置把 server Agent 注入本地 Agent。
- 本地 Agent 调用远端 Agent 后，远端结果以 `ToolCallResult` 回到本地 Agent。
- 保留远端 `contextId`，支持同一个本地 session 多轮调用同一个 remote Agent。
- 支持通用 A2A endpoint，不绑定 `core-ai-server` 私有 REST API。
- 设计上允许将来适配 LiteLLM Agent、LangGraph Agent、OpenAI Agents SDK Agent 等。

### 3.2 非目标

第一阶段不做：

- server 反向调用 CLI 本地文件、shell 或 MCP。
- 多 Agent graph runtime。
- 远端 Agent 直接接管 CLI 会话。
- 自动上传整个 workspace。
- 远端 nested streaming 原样渲染到本地 UI。
- 完整远端 tool approval / submit-input 闭环。
- CLI 重启后恢复远端 `contextId`。
- 跨协议兼容旧 A2A/ACP 草案。

### 3.3 P0 范围

P0 只交付最小协作闭环：

1. 从 CLI 配置读取 remote server 或手动 remote agent。
2. CLI 启动时通过 core-ai-server `/api/agents` 发现 server 上可用的 Agent。
3. 将发现到的 server Agent 映射为 discoverable A2A tools。
4. 本地 Agent 通过 `activate_tools` 搜索并选择合适的 server Agent。
5. 本地 Agent 通过 tool 调用远端 A2A `message/stream`。
6. tool 收集最终文本输出并返回 `ToolCallResult.completed`。
7. 同一个本地进程内复用远端 `contextId`。
8. 对失败、超时、鉴权错误返回清晰错误。

P0 不承诺：

- 跨 CLI 进程恢复远端上下文。
- 远端任务后台运行后由本地 Agent 轮询。
- 远端 `INPUT_REQUIRED` 原地恢复同一个 task。
- 把远端 stream 过程逐 token 显示在本地 UI。

## 4. 总体决策

选择 **Remote Agent as Tool** 作为主路径。

```
core-ai-cli local session
  └─ Local Core-AI Agent
       ├─ local tools
       ├─ local MCP
       ├─ skills
       └─ server_default_assistant(query)
             └─ A2ARemoteAgentToolCall
                  └─ A2AClient / HttpA2AClient
                       └─ core-ai-server A2A Agent
                            └─ server-only Tool/MCP/sandbox/internal API
```

### 4.1 与现有模式的关系

| 模式 | 当前状态 | 用途 | 是否解决目标问题 |
|---|---|---|---|
| `core-ai-cli --server` | 已支持 | 整个 CLI 会话远程执行 | 否，只是 remote session |
| `SubAgentToolCall` | 已支持 | 本进程内子 Agent | 部分相似，但不能跨进程 |
| `A2ARemoteAgentToolCall` | 新增 | 本地 Agent 调远端 Agent | 是，MVP 主路径 |
| Handoff | 后续 | 对话控制权迁移 | 否，后续增强 |
| Orchestration graph | 后续 | 多 Agent workflow | 否，后续增强 |

### 4.2 责任边界

Remote Agent Tool 的价值在于“能力委托”，不是“换一个 Agent 聊天”。因此边界必须清楚：

| 边界 | 本地 Agent | 远端 Agent |
|---|---|---|
| 用户对话 | 保持主控，直接面向用户 | 不直接接管用户会话 |
| 本地 workspace | 可读写、可总结、可选择性发送片段 | 默认不可访问 |
| server-only Tool/MCP | 只通过 remote tool 间接使用 | 直接执行 |
| 凭证 | 不把 token 放进 prompt 或 tool result | 使用 server 侧受控凭证 |
| 最终回答 | 综合本地和远端结果后生成 | 只返回子任务结果 |
| 协议状态 | 管理 remote `contextId` / `taskId` | 生成并维护 task/context |

这也意味着 tool description 必须偏窄：它应该引导模型在需要 server-only 能力时使用，而不是把普通问答都委托给 server。

## 5. 核心抽象

### 5.1 A2ARemoteAgentToolCall

建议包路径：

```
core-ai/src/main/java/ai/core/tool/tools/A2ARemoteAgentToolCall.java
```

职责：

- 作为普通 `ToolCall` 暴露给 LLM。
- 解析 `query` 参数。
- 从本地 session 上下文读取远端 `contextId` / `lastTaskId`。
- 构造 A2A `SendMessageRequest`。
- 调用 `A2AClient.stream()` 或 `A2AClient.send()`。
- 收集最终 artifact/message/task 输出。
- 把远端 `contextId` / `taskId` 写回 context store。
- 将结果封装成 `ToolCallResult`。

建议类结构：

```java
public final class A2ARemoteAgentToolCall extends ToolCall {
    private RemoteAgentDescriptor descriptor;
    private RemoteAgentClientFactory clientFactory;
    private RemoteAgentContextStore contextStore;
    private A2AOutputExtractor outputExtractor;
    private RemoteAgentConcurrencyGate concurrencyGate;

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context);

    @Override
    public ToolCallResult poll(String taskId);

    @Override
    public ToolCallResult submitInput(String taskId, String input);

    @Override
    public ToolCallResult cancel(String taskId);
}
```

MVP 可以先只实现 `execute`，但类设计要预留 `poll` / `submitInput` / `cancel`，因为 A2A 本身是 task-based 协议。

### 5.2 RemoteAgentDescriptor

描述一个可调用的远端 Agent Tool。它属于 `core-ai` 框架层，只表达 tool 行为契约，不包含 CLI 配置、连接地址或鉴权信息。

```java
public record RemoteAgentDescriptor(
        String id,
        String toolName,
        String toolDescription,
        Duration timeout,
        ContextPolicy contextPolicy,
        InvocationMode invocationMode,
        int maxInputChars,
        int maxOutputChars
) {
}
```

字段含义：

| 字段 | 含义 |
|---|---|
| `id` | 本地配置 ID，例如 `enterprise`。 |
| `toolName` | 暴露给 LLM 的 tool 名称，例如 `server_default_assistant`。 |
| `toolDescription` | 说明什么时候使用这个远端 Agent。 |
| `timeout` | 单次远端调用最大等待时间。 |
| `contextPolicy` | 是否复用远端 context。 |
| `invocationMode` | `STREAM_BLOCKING` / `SEND_SYNC` / 后续 `ASYNC_TASK`。 |
| `maxInputChars` | 防止本地 Agent 把过大内容一次性发给远端。 |
| `maxOutputChars` | 防止远端输出过大污染主 Agent 上下文。 |

连接细节留在调用方运行时配置中，例如 `core-ai-cli` 的 `A2ARemoteAgentConfig`：

| 字段 | 所属层 | 含义 |
|---|---|---|
| `enabled` | CLI | 是否启用该 remote agent。 |
| `url` | CLI | A2A endpoint 或 core-ai-server 根地址。 |
| `agentId` | CLI | A2A tenant / server agent id。 |
| `apiKeyEnv` / `apiKey` | CLI | 组装 `RemoteAgentClient` 时使用，不进入 framework descriptor。 |

换句话说，`core-ai` 只关心“这个 remote agent tool 如何执行”；CLI 负责“连到哪里、用什么凭证、是否启用”。

### 5.3 RemoteAgentContext

远端 Agent 的多轮上下文状态。

```java
public class RemoteAgentContext {
    public String localSessionId;
    public String remoteAgentId;
    public String contextId;
    public String lastTaskId;
    public TaskState lastState;
    public Instant updatedAt;
    public Map<String, Object> metadata;
}
```

说明：

- `contextId` 由远端 server 生成，client 只当 opaque id 保存。
- 新建任务时 client 不生成 `taskId`。
- 继续上下文时只传 `contextId`，除非是在恢复已有 task 才传 `taskId`。
- `lastTaskId` 用于诊断、取消、poll，不作为新 task 的 client-generated id。

### 5.4 RemoteAgentContextStore

用于保存远端上下文。

```java
public interface RemoteAgentContextStore {
    RemoteAgentContext get(String localSessionId, String remoteAgentId);

    void save(RemoteAgentContext context);

    void delete(String localSessionId, String remoteAgentId);
}
```

推荐实现：

| 实现 | 用途 |
|---|---|
| `InMemoryRemoteAgentContextStore` | P0 默认，进程内保存，最小风险。 |
| `ExecutionContextRemoteAgentContextStore` | P0 可选，写入 `ExecutionContext.customVariables`，同一 Agent 实例内可复用。 |
| `FileRemoteAgentContextStore` | P2，写入 `.core-ai/a2a-contexts/{sessionId}.json`，支持 CLI resume。 |
| `CompositeRemoteAgentContextStore` | P2，先写内存，再写文件。 |

实现注意：

- 当前 `ExecutionContext.sessionId` 在 CLI 本地 session 中不一定设置。实现前需要补齐 session scope。
- 推荐给 `CliAgent.Config` 增加 `sessionId`，构建 `ExecutionContext` 时写入 `.sessionId(sessionId)`。
- P0 只要求同一个 CLI 进程内复用 remote context；跨进程恢复留到 P2。
- 不建议直接把 remote context 混入 `AgentPersistence` 的 messages/status 结构，避免破坏现有 session 文件兼容性。
- 如果无法取得 sessionId，降级为进程内 context，并在 stats/log 中标记 `remote_context_persistent=false`。

## 6. Tool 输入输出契约

### 6.1 Tool 参数

MVP 暴露一个必填参数：

```json
{
  "query": "需要远端 Agent 完成的任务说明"
}
```

后续可扩展：

| 参数 | 阶段 | 用途 |
|---|---|---|
| `query` | MVP | 远端任务输入。 |
| `context_hint` | P1 | 本地 Agent 给远端的额外背景。 |
| `files` | P2 | 显式选择要传给远端的文件或 URI。 |
| `expected_output` | P1 | 期望返回格式。 |
| `remote_context_reset` | P1 | 是否重置远端 context。 |

不建议让 LLM 直接传 `taskId` 或 `contextId`，这些应由 tool 管理。

`query` 也要做输入上限控制：

- 默认 `maxInputChars=30000`。
- 超过上限时返回失败，让本地 Agent 先总结或缩小输入。
- 不自动读取 `@file` 或本地路径内容；文件展开仍由 CLI 现有输入层或后续显式 file part 机制处理。

### 6.2 Tool 描述模板

远端 tool 描述必须明确“何时使用”和“何时不要使用”。

示例：

```text
Use this server-side agent when the task requires enterprise MCP servers,
internal systems, server-hosted credentials, managed sandbox, or centrally
deployed tools. Do not use it for local file reads or local workspace edits
unless the user explicitly asks to consult the server agent.
```

中文配置可以写成：

```text
当任务需要企业 MCP、server-only Tool、内部系统、server 侧沙箱或集中凭证时使用该工具。
不要把本地文件内容默认发给远端；需要远端分析本地文件时，先总结必要上下文或询问用户确认。
```

### 6.3 输出提取

A2A 返回可能是：

- `Message`
- `Task`
- `TaskStatusUpdateEvent`
- `TaskArtifactUpdateEvent`

MVP 输出策略：

1. 优先收集 `TaskArtifactUpdateEvent.artifact.parts[].text`。
2. 若最终 `Task.artifacts` 存在，提取所有 text parts。
3. 若返回 `Message`，提取 message text。
4. 若只有 status message，提取 status message text。
5. 若无文本，返回结构化摘要，包括 `taskId`、`contextId`、state。

输出格式建议：

```text
<remote text result>
```

默认不要把 `taskId` / `contextId` 放入给 LLM 的正文，避免模型后续误用协议状态。它们应进入 `ToolCallResult.stats`：

| stats key | 值 |
|---|---|
| `remote_agent_id` | 配置 ID。 |
| `remote_task_id` | 远端 task id。 |
| `remote_context_id` | 远端 context id。 |
| `remote_state` | 终态。 |
| `remote_duration_ms` | 远端调用耗时。 |
| `remote_output_truncated` | 是否截断。 |

只有 debug 或 CLI 显示层需要时，才把这些信息渲染给用户；不要让模型直接维护它们。

## 7. 调用流程

### 7.1 首次调用

```
Local Agent
  -> model decides to call server_default_assistant(query)
  -> ToolExecutor
  -> A2ARemoteAgentToolCall.execute()
      -> load RemoteAgentContext: none
      -> create Message.user(query)
      -> POST /message/stream
      -> receive Task(contextId, taskId)
      -> receive artifact/status updates
      -> terminal state COMPLETED
      -> save contextId + lastTaskId
      -> return ToolCallResult.completed(remoteOutput)
  -> Local Agent sees tool result
  -> Local Agent synthesizes final answer
```

### 7.2 同一 session 再次调用

```
Local Agent
  -> call server_default_assistant(query)
  -> load RemoteAgentContext(contextId=ctx-1)
  -> SendMessageRequest.message.contextId = ctx-1
  -> remote server creates a new task under same context
  -> save new lastTaskId
  -> return result
```

### 7.3 INPUT_REQUIRED

远端 Agent 可能因为工具审批、缺少信息、二次授权进入 `TASK_STATE_INPUT_REQUIRED`。

MVP 策略：

- 不使用 `ToolCallResult.waitingForInput`。
- 不尝试原地恢复远端 task。
- 返回 `ToolCallResult.completed(...)`，内容明确告诉本地 Agent：远端需要补充输入，应询问用户后再次调用 remote agent。

```text
Remote agent requires input: <message>. Ask the user and call this remote agent again with the answer.
```

原因：

- 当前 Core-AI 的 `waitingForInput` / `submitInput` 更适合本地 tool/task，远端 task 续接还需要额外 task registry 和 UI 交互。
- P0 目标是协作闭环，不是远端审批闭环。
- 以 completed 返回可让本地 Agent 继续推理并询问用户，而不会把本地 session 卡在半实现状态。

推荐实现顺序：

1. MVP：`INPUT_REQUIRED` 返回明确文本，让本地 Agent 询问用户并重新调用。
2. P1：接入 `ToolCallResult.waitingForInput` 和 `submitInput`。
3. P2：CLI UI 对远端审批请求做原生渲染。

### 7.4 AUTH_REQUIRED

远端返回 `TASK_STATE_AUTH_REQUIRED` 时：

- 不允许 tool 自己请求或保存敏感凭证。
- 返回可操作错误，提示用户配置 `apiKeyEnv` 或在 server 端完成授权。
- 后续可扩展 OAuth device flow，但不进入 MVP。

## 8. 并发模型

AutoGen 的 `AgentTool` 明确提醒：有状态 Agent 不应并发调用，因为内部状态会冲突。A2A remote Agent 同样有 `contextId`，因此默认必须保守。

### 8.1 默认策略

同一个本地 session、同一个 remote agent、同一个 remote context 下：

- 默认串行。
- 不允许两个 tool call 同时复用同一个 `contextId`。
- 如果模型并行发起多个 remote tool call：
  - 对同一 remote agent 排队执行。
  - 对不同 remote agent 可以并行。

### 8.2 可配置策略

| 策略 | 含义 | 适用场景 |
|---|---|---|
| `SERIAL_PER_CONTEXT` | 默认，同 context 串行 | 有状态对话 Agent |
| `SERIAL_PER_AGENT` | 同 remote agent 全局串行 | 远端状态不清晰 |
| `PARALLEL_NEW_CONTEXT` | 并发时强制新 context | 独立分析任务 |
| `DISABLED` | 禁用并发保护 | 只适合无状态 Agent |

MVP 实现 `SERIAL_PER_CONTEXT` 即可。

实现建议：

```java
String key = localSessionId + ":" + remoteAgentId + ":" + contextIdOrNew;
Semaphore semaphore = gates.computeIfAbsent(key, ignored -> new Semaphore(1));
semaphore.acquire();
try {
    return invokeRemoteAgent();
} finally {
    semaphore.release();
}
```

如果首次调用还没有 `contextId`，key 使用 `localSessionId + ":" + remoteAgentId + ":new"`，避免同一 remote agent 在首次建立上下文时并发创建多个 context。后续如配置 `PARALLEL_NEW_CONTEXT`，再放宽这一点。

## 9. 配置设计

### 9.1 CLI agent.properties

推荐配置 remote server，让 CLI 自动发现 server 上的 agents：

```properties
a2a.remoteServers=dev

a2a.remoteServers.dev.enabled=true
a2a.remoteServers.dev.url=https://core-ai-server.connexup-dev.net
a2a.remoteServers.dev.apiKeyEnv=CORE_AI_SERVER_API_KEY
a2a.remoteServers.dev.discovery.enabled=true
a2a.remoteServers.dev.toolPrefix=server
a2a.remoteServers.dev.discoverable=true
a2a.remoteServers.dev.timeout=10m
a2a.remoteServers.dev.contextPolicy=SESSION
a2a.remoteServers.dev.invocationMode=STREAM_BLOCKING
a2a.remoteServers.dev.maxInputChars=30000
a2a.remoteServers.dev.maxOutputChars=20000
```

可选地限制暴露范围：

```properties
a2a.remoteServers.dev.includeAgents=default-assistant,coding-agent
a2a.remoteServers.dev.excludeAgents=test-agent
```

字段：

| 配置 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `enabled` | 否 | `true` | 是否启用该 remote server。 |
| `url` | 是 | 无 | core-ai-server 根地址。 |
| `apiKeyEnv` | 否 | 无 | 从环境变量取 bearer token。 |
| `apiKey` | 否 | 无 | 本地测试可用；生产更推荐 `apiKeyEnv`。 |
| `discovery.enabled` | 否 | `true` | 是否从 `/api/agents` 自动发现 server agents。 |
| `toolPrefix` | 否 | server 配置 id | 发现到的 tool 名称前缀，例如 `server_default_assistant`。 |
| `includeAgents` | 否 | 空 | 只暴露指定 agent id/name。为空表示不过滤。 |
| `excludeAgents` | 否 | 空 | 排除指定 agent id/name。 |
| `discoverable` | 否 | `true` | 发现到的 tools 默认进入 `activate_tools` 目录，而不是直接暴露给 LLM。 |
| `timeout` | 否 | `10m` | 单次调用超时。 |
| `contextPolicy` | 否 | `SESSION` | 是否复用 context。 |
| `invocationMode` | 否 | `STREAM_BLOCKING` | 调用模式。 |
| `maxInputChars` | 否 | `30000` | 输入截断/拒绝上限。 |
| `maxOutputChars` | 否 | `20000` | 输出截断上限。 |

兼容的手动 pin 配置仍然可用，适合只暴露单个指定 agent：

```properties
a2a.remoteAgents=enterprise

a2a.remoteAgents.enterprise.enabled=true
a2a.remoteAgents.enterprise.url=https://core-ai-server.connexup-dev.net
a2a.remoteAgents.enterprise.agentId=default-assistant
a2a.remoteAgents.enterprise.apiKeyEnv=CORE_AI_SERVER_API_KEY
a2a.remoteAgents.enterprise.name=server_default_assistant
a2a.remoteAgents.enterprise.description=Use this server-side agent for enterprise MCP, server-only tools, managed sandbox, and internal systems.
a2a.remoteAgents.enterprise.timeout=10m
a2a.remoteAgents.enterprise.contextPolicy=SESSION
a2a.remoteAgents.enterprise.invocationMode=STREAM_BLOCKING
a2a.remoteAgents.enterprise.maxInputChars=30000
a2a.remoteAgents.enterprise.maxOutputChars=20000
```

字段：

| 配置 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `enabled` | 否 | `true` | 是否启用该 remote agent。 |
| `url` | 是 | 无 | A2A endpoint 或 core-ai-server 根地址。 |
| `agentId` | 否 | server 默认 | A2A tenant / agent id。 |
| `apiKeyEnv` | 否 | 无 | 从环境变量取 bearer token。 |
| `apiKey` | 否 | 无 | 本地测试可用；生产更推荐 `apiKeyEnv`。 |
| `name` | 否 | Agent Card name 规范化 | Tool 名称。 |
| `description` | 否 | Agent Card description | Tool 描述。 |
| `discoverable` | 否 | `false` | 手动配置的 remote agent 是否也隐藏到 `activate_tools` 目录。 |
| `timeout` | 否 | `10m` | 单次调用超时。 |
| `contextPolicy` | 否 | `SESSION` | 是否复用 context。 |
| `invocationMode` | 否 | `STREAM_BLOCKING` | 调用模式。 |
| `maxInputChars` | 否 | `30000` | 输入截断/拒绝上限。 |
| `maxOutputChars` | 否 | `20000` | 输出截断上限。 |

### 9.2 Context Policy

| 值 | 含义 |
|---|---|
| `NONE` | 每次调用都新建远端 context。 |
| `SESSION` | 同一个本地 session 复用同一个远端 context。 |
| `TASK` | 只在同一个本地 task 下复用，暂不实现。 |

MVP 使用 `SESSION`。

### 9.3 与 `~/.core-ai/remote.json` 的关系

现有 `~/.core-ai/remote.json` 用于 `/remote` 或 `core-ai-cli --server`，含义是“切换整个会话到远端”。

`a2a.remoteServers.*` 和 `a2a.remoteAgents.*` 用于“本地 Agent 注入远端 Agent Tool”。

推荐优先使用 `a2a.remoteServers.*`，因为它能让本地 CLI 自动看到 server 上新增或更新的 agents。`a2a.remoteAgents.*` 更适合稳定 pin 某个特定 Agent，或者连接非 core-ai-server 的通用 A2A endpoint。

两者不要混用为同一配置文件，避免语义混乱。后续可以提供命令：

```text
/remote-agent add
/remote-agent list
/remote-agent test
```

它可以把当前 remote server 导入 `agent.properties` 的 remote agent 配置。

## 10. CLI 接入点

### 10.1 配置解析

建议新增：

```
core-ai-cli/src/main/java/ai/core/cli/remote/A2ARemoteAgentConfig.java
core-ai-cli/src/main/java/ai/core/cli/remote/A2ARemoteServerConfig.java
core-ai-cli/src/main/java/ai/core/cli/remote/A2ARemoteAgentConfigLoader.java
core-ai-cli/src/main/java/ai/core/cli/remote/A2ARemoteAgentDiscovery.java
```

解析 `PropertiesFileSource`：

```java
List<A2ARemoteAgentConfig> configs = A2ARemoteAgentConfigLoader.load(props);
List<A2ARemoteServerConfig> servers = A2ARemoteAgentConfigLoader.loadServers(props);
```

### 10.2 注入 CliAgent

`CliAgent.Config` 增加：

```java
String sessionId,
List<A2ARemoteAgentConfig> remoteAgents,
List<A2ARemoteServerConfig> remoteServers
```

`CliAgent.buildTools()`：

```java
tools.addAll(A2ARemoteAgentTools.from(
    config.remoteAgents,
    config.remoteServers,
    reservedToolNames(tools)));
```

发现到的 server agents 默认 `discoverable=true`，因此会被 `AgentBuilder.configureToolDiscovery()` 隐藏到 `activate_tools` 目录中。本地 Agent 先搜索/激活合适的远端 Agent，再真正调用对应的 A2A tool，避免 server agent 很多时污染工具列表。

`ExecutionContext` 构建时补齐：

```java
ExecutionContext.builder()
    .sessionId(config.sessionId)
    .customVariables(Map.of("workspace", config.workspace.toAbsolutePath().toString()))
    .persistenceProvider(config.persistenceProvider)
    .subagentOutputSinkFactory(...)
    .build();
```

注意：当前 `CliAgent.of()` 已经设置 `customVariables(workspace)`，但没有设置 `sessionId`。为了远端 context 持久化，sessionId 应进入 `ExecutionContext`。

这个改动要先做，因为 remote context 的 key 必须稳定：

```text
remote-context-key = localSessionId + ":" + remoteAgentId
```

如果没有 `sessionId`，同一个 CLI 会话内的 remote context 只能放在 tool 实例内存里；这会让测试和后续持久化都变得不清晰。

### 10.3 System Prompt

不建议在 system prompt 中详细描述远端 Agent 细节。让工具的 `description` 承担选择依据即可。

只需要在 CLI prompt 中追加一条简短原则：

```text
Some tools may delegate work to remote server-side agents. Use them only when their tool description matches the task, and do not send local file contents unless needed for the task.
```

## 11. Server 侧要求

`core-ai-server` 已具备 A2A endpoint、Agent Card、stream、get/cancel、跨 Pod task routing。Remote Agent Tool 对 server 侧新增要求较少。

需要确认：

| 要求 | 当前状态 | 说明 |
|---|---|---|
| Agent Card 可发现 | 已有 | `A2ARemoteConnector` 已支持 server 根地址和 `/api/a2a` 候选。 |
| bearer token 鉴权 | 已有 | CLI 已能传 `Authorization: Bearer`。 |
| `message/stream` | 已有 | Tool 默认使用 stream 收集最终输出。 |
| `contextId` 复用 | 需验证 | 本地 tool 会传回远端 contextId。 |
| cross-Pod get/cancel | 已有 | 之前已通过 registry/relay/RPC 修复。 |
| task history/output 足够 | 需测试 | 输出提取依赖 artifact/status。 |

## 12. P0 验收标准

P0 完成时必须满足：

| 验收项 | 判断方式 |
|---|---|
| 未配置 remote agent 时行为不变 | 本地 CLI 原有单元测试/手工启动通过。 |
| 配置 remote agent 后出现新 tool | 本地 Agent tool list 包含配置的 tool name。 |
| 本地 Agent 可调用 server Agent | fake A2A client 和 dev server 均能返回结果。 |
| 同一会话复用 `contextId` | 第二次调用 request 带上第一次返回的 `contextId`。 |
| LLM 不直接管理协议 ID | tool schema 中没有 `taskId` / `contextId` 参数。 |
| 同 remote context 串行 | 并发测试中同 key 不并行进入 fake client。 |
| token 不泄漏 | tool result、stats、日志不包含 bearer token。 |
| 输出受控 | 超过 `maxOutputChars` 时截断并标记。 |

## 13. 协议兼容策略

当前 Core-AI A2A DTO 使用 oneof 字段表达：

- `Part.text`
- `Part.raw`
- `Part.url`
- `Part.data`

核心 DTO 不保留旧草案里的 `type`，也不依赖旧的 `kind` discriminator。版本差异应通过协议 adapter 处理。

策略：

| 场景 | 策略 |
|---|---|
| Core-AI client 调 Core-AI server | 使用当前 DTO，最小转换。 |
| 第三方 A2A v1 client/server | 按 Agent Card / `A2A-Version` 选择 serialization profile。 |
| 老草案客户端 | 如确实需要，放在 legacy adapter，不污染 core DTO。 |

MVP 只保证 Core-AI client/server 和当前目标 A2A profile。

## 14. 安全边界

### 14.1 Token

- 优先使用 `apiKeyEnv`。
- `apiKey` 只允许本地测试，不推荐提交到配置。
- token 不进入 prompt、tool result、stats、日志。

### 14.2 本地数据外发

默认不把本地文件、workspace、shell output 全量发给远端。

本地 Agent 如需远端分析本地文件，应采用优先级：

1. 发送必要摘要。
2. 发送用户明确指定的片段。
3. 后续通过显式 file upload/URI 机制发送文件。

### 14.3 审批

远端 Agent 的 server-only tool approval 由 server 执行本地审批策略。CLI 不自动批准 server 侧危险动作，除非 server 明确受信。

远端 `INPUT_REQUIRED` 要回到本地用户确认。

### 14.4 审计

每次远端调用至少记录：

- local session id
- remote agent id
- remote task id
- remote context id
- state
- duration
- error category

不要记录 bearer token 和敏感输入全文。

## 15. 错误处理

| 错误 | Tool 行为 |
|---|---|
| Agent Card 获取失败 | `ToolCallResult.failed("remote agent unavailable...")` |
| 401/403 | 提示检查 `apiKeyEnv` 或 server 权限。 |
| 404 agent/task not found | 清理本地 remote context，建议重试新 context。 |
| stream 中断 | 如果已有 `taskId`，尝试 `getTask`；否则失败。 |
| timeout | 返回失败；如果已有远端 `taskId`，stats 带上 taskId，后续可 cancel。 |
| `INPUT_REQUIRED` | P0 返回 completed 提示本地 Agent 询问用户；P1 再做原 task 续接。 |
| `AUTH_REQUIRED` | 返回 failed，提示配置或更新 server 侧授权。 |
| `FAILED` / `REJECTED` | 返回失败，保留远端错误消息。 |
| `CANCELED` | 返回失败或 canceled 文本，避免本地 Agent 当成功结果使用。 |
| 输出过大 | 截断并标注 `[truncated]`。 |

## 16. 测试计划

### 16.1 core-ai 单元测试

新增：

```
core-ai/src/test/java/ai/core/a2a/A2ARemoteAgentToolCallTest.java
```

覆盖：

- `query` 缺失返回 failed。
- 首次调用不带 contextId。
- 完成后保存 contextId/taskId。
- 第二次调用带上 contextId。
- stream artifact 拼接。
- message-only response 提取。
- task-only response 提取。
- `INPUT_REQUIRED` 在 P0 转成 completed 提示。
- `FAILED` / `REJECTED` / `AUTH_REQUIRED` 映射。
- 输出截断。
- 同 context 并发串行。

### 16.2 CLI 配置测试

覆盖：

- `a2a.remoteAgents` 多个 id 解析。
- `a2a.remoteServers` 多个 id 解析。
- `apiKeyEnv` 读取。
- tool name 规范化。
- discovery 从 `/api/agents` 过滤 `AGENT` 类型并映射成 discoverable tools。
- 自动发现 tool 和内置 tool 同名时自动加 agent id 后缀。
- description fallback 到 server agent description。
- 未配置时不改变现有 CLI 行为。

### 16.3 集成测试

本地 fake A2A server：

- Agent Card。
- `message/stream` 返回 task、artifact、completed。
- `message/stream` 返回 input-required。
- `tasks/{id}` 和 cancel。

dev 环境手工验证：

1. 本地 CLI 正常启动。
2. 配置 `a2a.remoteServers.dev`。
3. 提问一个需要 server-only MCP 的任务。
4. 确认本地 Agent 先通过 `activate_tools` 选择合适的 server agent。
5. 确认本地 Agent 调用发现到的 server agent tool，例如 `server_default_assistant`。
6. 确认 server pod 日志有 A2A task。
7. 确认本地 Agent 最终综合远端结果，而不是直接切到远端会话。

## 17. 分阶段落地

### P0：MVP

- `CliAgent.Config` / `ExecutionContext` 补 `sessionId`。
- 新增 `A2ARemoteAgentToolCall`。
- 新增 remote agent descriptor/config loader。
- 新增 `a2a.remoteServers.*` discovery，从 `/api/agents` 自动发现 server agents。
- 将发现到的 server agents 注入为 discoverable remote tools。
- 使用 `HttpA2AClient.stream()`，同步等待 terminal state。
- 保存 `contextId` / `lastTaskId` 到进程内 context store。
- 输出 text artifact/message。
- `INPUT_REQUIRED` 返回可读提示，不做原 task 续接。
- 添加单元测试和 fake client 测试。

### P1：交互恢复

- 支持 `ToolCallResult.waitingForInput`。
- 支持 `submitInput(remoteTaskId, input)`。
- CLI 对远端 `INPUT_REQUIRED` 做用户友好渲染。
- 支持 cancel remote task。

### P2：远端上下文持久化和文件传递

- `FileRemoteAgentContextStore` / `CompositeRemoteAgentContextStore`。
- CLI resume 后恢复 remote context。
- 显式文件上传或 URI part。
- 支持输出 structured data。

### P3：框架级编排

- Remote Agent Tool 作为 Flow/Graph node。
- Handoff API。
- Agent Card registry。
- 多 remote agent routing/fallback。
- LiteLLM / LangGraph / OpenAI Agents SDK adapter。

## 18. 推荐实现顺序

1. 给 `CliAgent.Config` / `ExecutionContext` 补 `sessionId`，保证 context key 稳定。
2. 在 `core-ai` 实现 `A2ARemoteAgentToolCall`，先只依赖 `A2AClient`，不要依赖 CLI/server。
3. 实现 `A2AOutputExtractor` 和 fake client 单测。
4. 实现进程内 `RemoteAgentContextStore`。
5. 在 `core-ai-cli` 解析 `a2a.remoteAgents.*` 并注入 tools。
6. 用 dev server 做真实 A2A 调用验证。
7. 再考虑 `INPUT_REQUIRED` 的完整 submit-input 链路和文件型 context store。

## 19. 核心结论

Remote Agent as Tool 是当前最合适的落地方式：

- 它符合 OpenAI、LangChain、AutoGen 主流 agent-as-tool 设计。
- 它保持本地 Agent 主控权，满足 CLI 本地上下文和 server-only 能力协作。
- 它复用现有 A2A client/server 实现，不需要引入复杂 orchestration runtime。
- 它能自然扩展到第三方 A2A-compatible Agent。

第一阶段不要做 handoff，不要做 server 反向调用本地工具，不要做复杂 group chat。先把“本地 Agent 可以稳定、安全、可测试地调用 server Agent”打通。
