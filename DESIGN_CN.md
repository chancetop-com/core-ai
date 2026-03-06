# Core-AI 框架详细设计文档

> 版本: 1.0 | 更新日期: 2026-03-06 | 分支: vidingcode

## 1. 项目概述

Core-AI 是一个基于 Java 的多智能体 AI 框架，提供了构建、运行和管理 LLM 驱动的智能体的完整生命周期。支持多轮对话、工具调用、RAG、记忆、技能注入、流程编排、反思、MCP 集成和遥测 —— 全部通过模块化、可扩展的架构实现。

**技术栈**: Java 25 + Gradle + core.framework (core-ng) + Jackson + Mustache + GraalVM Native Image

**源码统计**: 5 个模块共 513 个 Java 源文件，核心模块独占 311 个。

---

## 2. 模块架构

```
settings.gradle.kts 包含:
  core-ai           — 核心框架（基础库）
  core-ai-api       — API 契约与领域模型
  core-ai-cli       — 交互式命令行应用
  core-ai-server    — REST API 服务（MongoDB 持久化）
  core-ai-benchmark — BFCL 基准评测
```

### 2.1 依赖关系图

```
                    core-ai-api
                   （API 契约层）
                    ↑         ↑
                    |         |
    core-ai -------+         |
   （核心框架）                |
     ↑    ↑    ↑              |
     |    |    |              |
  cli  server  benchmark ----+
```

- **core-ai-api** 仅依赖 `core.framework:core-ng-api`（零循环依赖）
- **core-ai** 依赖 core-ai-api + core-ng + Jackson + Mustache + Milvus + HNSWLIB + JTokkit + OpenAI + MCP SDK + SQLite + SnakeYAML + OpenTelemetry
- **core-ai-cli** 依赖 core-ai + core-ai-api + JLine 3 + PicoCLI
- **core-ai-server** 依赖 core-ai + core-ai-api + core-ng + core-ng-mongo
- **core-ai-benchmark** 依赖 core-ai + core-ai-api + core-ng + jackson-databind

### 2.2 模块职责

| 模块 | 类型 | 职责 |
|------|------|------|
| **core-ai-api** | 库（发布） | HTTP API 接口（`*WebService`）、MCP 协议 DTO、`@CoreAiMethod`/`@CoreAiParameter` 注解 |
| **core-ai** | 库（发布） | 智能体执行引擎、工具系统、生命周期管线、压缩、RAG、记忆、技能、MCP 客户端/服务端、流程编排、持久化、遥测 |
| **core-ai-cli** | 应用（原生） | 交互式 REPL，支持流式 Markdown 渲染、会话管理、斜杠命令、技能/记忆/MCP 管理 |
| **core-ai-server** | 应用（Web） | 企业级 REST API，MongoDB 持久化，智能体 CRUD、定时执行、SSE 实时会话、文件管理、认证 |
| **core-ai-benchmark** | 应用（测试） | BFCL 基准评测，支持并行批处理、可恢复执行、结果追踪 |

---

## 3. 核心模块架构

### 3.1 包结构

```
ai.core/
├── agent/                 — Agent, Node, Group, ExecutionContext, Builder
│   ├── formatter/         — 输出格式化（Code, JSON）
│   ├── lifecycle/         — AbstractLifecycle 钩子系统
│   ├── listener/          — 消息更新、状态变更事件
│   ├── slashcommand/      — 斜杠命令解析
│   └── streaming/         — StreamingCallback 接口
├── bootstrap/             — AgentBootstrap, BootstrapResult 初始化
├── context/               — 压缩、工具调用裁剪及其生命周期
├── defaultagents/         — 内置智能体（审核、RAG 查询改写、文本分割）
├── document/              — TextSplitter, Tokenizer, Document, TextChunk
├── flow/                  — 流程引擎、FlowNode、FlowEdge、15+ 节点类型
├── image/                 — ImageProvider 抽象（LiteLLM 图像生成）
├── internal/json/         — CoreAiAnnotationIntrospector（自定义 Jackson）
├── llm/                   — LLMProvider 抽象
│   ├── domain/            — Message, Content, Tool, CompletionRequest/Response, Usage
│   └── providers/         — OpenAIProvider, LiteLLMProvider
├── mcp/                   — MCP 客户端/服务端
│   ├── client/            — McpClientManager, McpClientService, 连接监控
│   └── server/            — McpServerService, API 加载器, 动态 API 调用
├── memory/                — Memory, MemoryStore, MemoryRecord, MemoryLifecycle
├── persistence/           — Persistence<T>, PersistenceProvider（File, Redis, Temporary）
├── prompt/                — PromptTemplate（Mustache）, Langfuse 集成
├── rag/                   — RagConfig, SimilaritySearchRequest, 过滤表达式
├── reflection/            — ReflectionEvaluator, ReflectionConfig, ReflectionHistory
├── session/               — SessionManager, SessionPersistence, ToolPermissionStore
├── skill/                 — SkillConfig, SkillLoader, SkillLifecycle, SkillPromptFormatter
├── sse/                   — Server-Sent Events 支持
├── task/                  — Task, TaskMessage, TaskArtifact, TaskPersistence
├── telemetry/             — AgentTracer, LLMTracer, FlowTracer, GroupTracer
├── termination/           — Termination 接口（MaxRound, MaxToken, StopMessage, UserCancelled）
├── tool/                  — ToolCall, Function, ToolExecutor, ToolCallAsyncTaskManager
│   └── tools/             — 15+ 内置工具（ReadFile, WriteFile, Shell, WebFetch 等）
├── utils/                 — JsonUtil, JsonSchemaUtil, MessageTokenCounterUtil, ShellUtil
├── vectorstore/           — VectorStore 接口（MilvusVectorStore, HnswLibVectorStore）
└── vender/                — 供应商集成异常
```

### 3.2 核心执行模型

#### Node（抽象基类）

`Node<T>` 是所有可执行单元（Agent、Group、Flow）的基类。核心状态：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id`, `name`, `description` | String | 身份标识 |
| `messages` | List\<Message\> | 对话历史 |
| `nodeStatus` | NodeStatus | INITED → RUNNING → COMPLETED/FAILED/WAITING_* |
| `currentTokenUsage` | Usage | 累计 Token 用量 |
| `terminations` | List\<Termination\> | 终止条件 |
| `agentLifecycles` | List\<AbstractLifecycle\> | 已注册的生命周期钩子 |
| `persistenceProvider` | PersistenceProvider | 存储后端 |
| `executionContext` | ExecutionContext | 每次请求的运行时上下文 |
| `streaming` / `streamingCallback` | boolean / StreamingCallback | 实时流式输出 |

**NodeStatus 状态机**：
```
INITED → RUNNING → COMPLETED
                  → FAILED
                  → WAITING_FOR_USER_INPUT
                  → WAITING_FOR_ASYNC_TASK
```

#### Agent（继承 Node）

LLM 交互的主要执行单元。新增字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `systemPrompt` | String | 系统提示词（Mustache 模板） |
| `promptTemplate` | String | 用户查询包装模板 |
| `llmProvider` | LLMProvider | 模型抽象 |
| `toolCalls` | List\<ToolCall\> | 可用工具列表 |
| `temperature`, `model` | float, String | LLM 参数 |
| `ragConfig` | RagConfig | RAG 配置 |
| `compression` | Compression | 历史压缩 |
| `reflectionConfig` | ReflectionConfig | 自我改进循环 |
| `useGroupContext` | boolean | 继承父节点消息 |
| `subAgents` | List | 子智能体工具调用 |

#### 执行流程

```
Agent.execute(query, variables)
  │
  ├─ 1. 生命周期: beforeAgentRun()
  │
  ├─ 2. 上下文组装 (AgentBuilder.buildUserQueryToMessage)
  │     ├─ buildSystemMessage()      — Mustache 渲染 systemPrompt + 变量
  │     ├─ addTaskHistories()        — 注入 Task 多轮历史
  │     ├─ useGroupContext?          — 拼接父节点消息
  │     ├─ buildUserMessage()        — USER 消息（文本 + 图片 + PDF）
  │     └─ cleanDanglingToolMessages()
  │
  ├─ 3. chatTurns() — 多轮对话循环
  │     │
  │     ├─ 生命周期: beforeModel()
  │     │   ├─ ToolCallPruningLifecycle — 裁剪旧工具调用段
  │     │   ├─ SkillLifecycle          — 注入技能描述到系统提示词
  │     │   └─ CompressionLifecycle    — Token >= 80% 上限时压缩
  │     │
  │     ├─ handLLM() → LLMProvider.completion(request)
  │     │
  │     ├─ 生命周期: afterModel()
  │     │
  │     ├─ 如果响应中包含 tool_calls:
  │     │   ├─ 生命周期: beforeTool() — 权限检查
  │     │   ├─ ToolExecutor.execute()
  │     │   ├─ 生命周期: afterTool()  — 压缩大型结果
  │     │   └─ 继续循环
  │     │
  │     ├─ 如果 reflectionConfig.enabled:
  │     │   └─ reflectionLoop() — 评估 → 改进 → 重新评估
  │     │
  │     └─ 检查终止条件 → 中断或继续
  │
  ├─ 4. 生命周期: afterAgentRun()
  │
  └─ 返回 output
```

#### ExecutionContext（执行上下文）

每次请求的运行时上下文，传递给工具和生命周期：

```java
ExecutionContext {
    sessionId          // 会话标识
    userId             // 用户标识
    customVariables    // Map<String, Object> 动态数据
    asyncTaskManager   // 后台任务管理
    persistenceProvider // 存储后端
    attachedContent    // 图片/PDF 附件
    llmProvider        // 覆盖 LLM 提供者
    model              // 覆盖模型
}
```

当未提供显式上下文时（如 CLI 中 `agent.run(message)`），`Node.getExecutionContext()` 会自动从 Node 自身字段（persistenceProvider 等）构建。

---

### 3.3 消息与对话模型

```
Message
├── role: RoleType (USER | ASSISTANT | SYSTEM | TOOL)
├── content: List<Content>
│   └── Content
│       ├── type: TEXT | IMAGE_URL | FILE
│       ├── text / imageUrl / fileContent
│       └── format, detail level
├── reasoningContent: String（扩展思考）
├── toolCallId: String（TOOL 角色关联 ID）
└── toolCalls: List<FunctionCall>（ASSISTANT 角色的工具调用）
```

**每个 Agent 的消息序列**：
```
SYSTEM → USER → ASSISTANT → [TOOL → ASSISTANT]* → ...
```

---

### 3.4 生命周期系统

`AbstractLifecycle` 中的 8 个钩子点：

| 钩子 | 时机 | 用途 |
|------|------|------|
| `beforeAgentBuild` | 初始化前 | 修改 Builder 配置 |
| `afterAgentBuild` | 初始化后 | 缓存计算数据 |
| `beforeAgentRun` | 执行前 | 加载上下文（记忆召回） |
| `afterAgentRun` | 执行后 | 持久化结果（记忆存储） |
| `afterAgentFailed` | 异常时 | 错误日志/恢复 |
| `beforeModel` | LLM 调用前 | 上下文操作（压缩、裁剪、注入技能） |
| `afterModel` | LLM 响应后 | 响应处理 |
| `beforeTool` / `afterTool` | 工具执行前后 | 权限门控、结果压缩 |

**注册顺序**（在 `AgentBuilder.copyValue` 中）：

| 顺序 | 生命周期 | 主要钩子 |
|------|----------|----------|
| 1 | ToolCallPruningLifecycle | beforeModel — 移除旧工具调用段 |
| 2 | SkillLifecycle | beforeModel — 注入技能描述 |
| 3 | MemoryLifecycle | beforeAgentRun/afterAgentRun — 召回/存储记忆 |
| 4 | CompressionLifecycle | beforeModel + afterTool — 压缩历史 |

用户自定义生命周期插入在 Skill 和 Compression 之间。

---

### 3.5 上下文管理

#### 压缩（`Compression.java`）

通过摘要旧消息防止上下文溢出：

- **触发条件**: `当前Token数 >= 最大上下文Token数 * 0.8`（可配置）
- **保留内容**: 系统消息 + 最近 5 轮 / 15k Token
- **压缩策略**: LLM 对旧消息生成摘要
- **注入方式**: 伪造 `memory_compress` tool_call + result 消息对
- **大型工具结果**: >64k Token → 截断首尾各 500 Token，完整内容写入临时文件

#### 工具调用裁剪（`ToolCallPruning.java`）

移除冗余的工具调用历史：

- **段定义**: `ASSISTANT(tool_calls) + TOOL(results)...`
- **已消化**: 段后面跟有非空 ASSISTANT 回复
- **裁剪**: 移除旧的已消化段，保留最近 N=2 段
- **排除**: `memory_compress` 和用户指定的工具名
- **后置校验**: 确保 tool_call_id 引用完整性

---

### 3.6 工具系统

#### ToolCall（抽象基类）

```java
ToolCall {
    namespace, name, description   // 身份标识
    parameters: List<ToolCallParameter>  // JSON Schema 参数
    needAuth: boolean              // 需要审批
    directReturn: boolean          // 跳过 LLM 后处理
    llmVisible: boolean            // 对 LLM 可见
    discoverable: boolean          // 用户可发现
    timeoutMs: long                // 执行超时

    execute(arguments, context) → ToolCallResult
    poll(taskId)                   // 异步轮询
    submitInput(taskId, input)     // 异步用户输入
    cancel(taskId)                 // 取消异步任务
}
```

#### Function（反射式工具）

`Function extends ToolCall` 包装带有 `@CoreAiMethod` 注解的 Java 方法：

```java
@CoreAiMethod(name = "read_file", description = "读取文件内容")
public String readFile(
    @CoreAiParameter(name = "path", description = "文件路径") String path,
    ExecutionContext context  // 自动注入，不对 LLM 暴露
) { ... }
```

- `Function.executeSupport` 自动检测 `ExecutionContext` 类型参数并注入
- 支持 `responseConverter` 自定义结果格式化
- 动态参数支持运行时参数修改

#### 内置工具

| 工具 | 说明 |
|------|------|
| ReadFileTool | 读取文件内容 |
| WriteFileTool | 写入文件内容 |
| EditFileTool | 差异编辑文件 |
| GlobFileTool | 按模式查找文件 |
| GrepFileTool | 搜索文件内容 |
| ShellCommandTool | 执行 Shell 命令 |
| PythonScriptTool | 运行 Python 脚本 |
| WebFetchTool | HTTP 请求 |
| WebSearchTool | 网页搜索 |
| RagTool | 向量相似度搜索 |
| MemoryTool / MemoryRecallTool | 存储/检索记忆 |
| AskUserTool | 交互式用户查询 |
| WriteTodosTool | 任务列表管理（支持持久化） |
| AddMcpServerTool | 注册 MCP 服务器 |
| ManageSkillTool | 技能管理 |
| ToolActivationTool | 启用/禁用工具 |
| SubAgentToolCall | 委派给子智能体 |
| AsyncTaskOutputTool | 轮询异步任务结果 |
| CaptionImageTool | 图像理解 |
| SummarizePdfTool | PDF 处理 |

#### ToolCallResult（工具调用结果）

```java
ToolCallResult {
    status: COMPLETED | PENDING | WAITING_FOR_INPUT | FAILED
    result: String              // 结果文本
    taskId: String              // 异步任务 ID
    directReturn: boolean       // 绕过 LLM
    stats: Map                  // 执行统计
    image: (base64, format)     // 图像结果
}
```

#### 工具权限系统

- `ToolCall.needAuth` 标记需要审批的工具
- `ToolPermissionStore` 将已审批工具名持久化到文件
- `ServerPermissionLifecycle.beforeTool()` 通过 5 分钟审批超时门控执行
- 支持 `APPROVE_ALWAYS` 永久授权

---

### 3.7 LLM 提供者抽象

```java
LLMProvider（抽象类） {
    completion(CompletionRequest) → CompletionResponse        // 同步调用
    completionStream(CompletionRequest, StreamingCallback)    // 流式调用
    embeddings(EmbeddingRequest) → EmbeddingResponse          // 文本嵌入
    reranking(RerankingRequest) → RerankingResponse           // 语义重排
    captionImage(CaptionImageRequest) → CaptionImageResponse  // 图像理解
    completionFormat() → 结构化 JSON 响应
}
```

**实现类**: OpenAIProvider, LiteLLMProvider

**支持的提供者类型**（`LLMProviderType`）: DEEPSEEK, OPENAI, AZURE, AZURE_INFERENCE, OPENROUTER, LITELLM

**模型上下文注册表**（`LLMModelContextRegistry`）: 模型名 → 上下文窗口大小映射（默认 128k 输入, 4k 输出）。支持前缀匹配和基础模型名提取。

**Token 计数**: `Tokenizer` 使用 JTokkit（CL100K_BASE 编码）。`MessageTokenCounterUtil` 计算消息列表的 Token 数。

---

### 3.8 记忆系统

```
Memory
├── retrieve(userId, query, topK) — 基于嵌入的搜索
├── formatAsContext()              — 格式化为提示词文本
└── MemoryStore（接口）
    ├── save(userId, record)
    └── searchByVector(userId, embedding, topK)

MemoryRecord { userId, content, embedding, metadata, timestamp }
```

**MemoryLifecycle**:
- `beforeAgentRun` — 召回相关记忆，注入上下文
- `afterAgentRun` — 从对话中存储新记忆
- 当 `autoRecall` 启用时自动注册 `MemoryRecallTool`

**存储**: 基于 SQLite 的持久化存储（通过 sqlite-jdbc）。

---

### 3.9 技能系统

技能是从文件系统加载的 Markdown/YAML 文件，注入到系统提示词中：

```
SkillConfig → SkillLoader → SkillMetadata → SkillPromptFormatter → 系统提示词
```

- **SkillLifecycle.afterAgentBuild()**: 加载并缓存技能
- **SkillLifecycle.beforeModel()**: 将格式化的技能追加到系统消息
- **SkillSource**: `(name, path, priority)` 用于排序
- **ManageSkillTool**: 运行时技能启用/禁用

---

### 3.10 RAG 系统

```
查询 → [查询改写] → 嵌入 → VectorStore.search(topK) → [重排] → 注入上下文
```

**RagConfig**: `useRag`, `topK=5`, `threshold`, `vectorStore`, `llmProvider`, `enableQueryRewriting=true`

**VectorStore 接口**: `add(documents)`, `search(request)`, `delete(ids)`
- **MilvusVectorStore** — 分布式向量数据库
- **HnswLibVectorStore** — 内存 HNSW 索引

**过滤系统**: 元数据过滤的表达式树，配合 `MilvusExpressionConverter`。

**上下文注入**: 通过 `__rag_default_context_placeholder__` 模板追加到提示词。

---

### 3.11 反思系统

用于提升输出质量的自我改进循环：

```
Agent.reflectionLoop()
├── evaluate(output) → EvaluationResult { score, pass, weaknesses, suggestions }
├── 如果 pass 或 score >= 阈值 → 完成
├── 否则: 根据反馈构建改进提示词
├── 重新执行智能体 → 新输出
└── 重复直到 maxRound（默认 3）
```

**ReflectionConfig**: `enabled`, `maxRound=3`, `minRound=1`, `evaluationCriteria`, `prompt`

**ReflectionListener**: `onReflectionStart`, `onBeforeRound`, `onAfterRound`, `onError` 回调

---

### 3.12 流程编排

基于 DAG 的工作流引擎，支持类型化节点和条件路由：

```java
Flow {
    nodes: Map<String, FlowNode>
    edges: List<FlowEdge>
    run(nodeId, input, variables) → output
}
```

**节点类型**（15+）：

| 类别 | 节点 |
|------|------|
| 智能体 | AgentFlowNode, AgentToolFlowNode |
| LLM | LLMFlowNode, OpenAIFlowNode, LitellmFlowNode, DeepSeekFlowNode |
| 工具 | ToolFlowNode, FunctionToolFlowNode |
| 向量 | MilvusFlowNode, HNSWLibFlowNode, RagFlowNode |
| 控制 | OperatorIfFlowNode, OperatorSwitchFlowNode, OperatorFilterFlowNode |
| 系统 | ThrowErrorFlowNode, WebhookTriggerFlowNode, EmptyFlowNode |

**FlowEdge**: `ConnectionEdge`（数据流）和 `SettingEdge`（带值的条件路由）。

---

### 3.13 MCP 集成

#### 客户端

```
McpClientManager → McpClientService[] → (stdio | HTTP | SSE 传输)
    ├── addServer() / removeServer()
    ├── getTools() → 适配为 ToolCall 列表
    ├── callTool() → ToolCallResult
    └── McpConnectionMonitor — 健康检查 + 自动重连
```

#### 服务端

```
McpServerService → 通过 MCP 协议暴露 Agent 工具
ApiMcpToolLoader → 从 OpenAPI 规范生成 MCP 工具
DynamicApiCaller → 以工具调用方式执行 API 端点
```

---

### 3.14 会话与持久化

#### 持久化接口

```java
Persistence<T> {
    String serialization(T t);        // 序列化
    void deserialization(T t, String c); // 反序列化
}

PersistenceProvider {
    save(String id, String context);   // 保存
    Optional<String> load(String id);  // 加载
    delete(List<String> ids);          // 删除
    clear();                           // 清空
}
```

**实现类**: FilePersistenceProvider（文件系统）, RedisPersistenceProvider（Redis）, TemporaryPersistenceProvider（内存）

#### 会话管理

```
SessionManager → SessionPersistence → SessionInfo (id, name, timestamp)
AgentPersistence → 序列化 messages + nodeStatus
```

---

### 3.15 遥测

```
Tracer（抽象）
├── AgentTracer — 智能体执行追踪
├── LLMTracer   — LLM API 调用追踪
├── FlowTracer  — 流程执行追踪
└── GroupTracer  — 组执行追踪
```

通过 OpenTelemetry OTLP 导出。Langfuse 集成支持提示词版本管理。

---

### 3.16 提示词系统

**Mustache 模板**: 系统提示词和查询模板支持 `{{variable}}` 变量插值。

**系统变量**（自动注入）：
- 构建时: `AGENT_TOOLS`
- 运行时: `NODE_CURRENT_INPUT`, `SYSTEM_CURRENT_TIME`, `NODE_CURRENT_ROUND`, `AGENT_WRITE_TODOS_SYSTEM_PROMPT`

**Langfuse 集成**: 通过 `LangfusePromptProvider` 支持提示词版本管理，含标签和版本支持。

---

## 4. CLI 模块设计

### 4.1 架构

```
Main.java (PicoCLI)
  └── CliApp.start()
        ├── InteractiveConfigSetup — 首次配置向导
        ├── CliAgent.of() — 构建 Agent（含工具、技能、MCP）
        └── AgentSessionRunner.run() — REPL 主循环
              ├── TerminalUI (JLine 3) — 输入/输出
              ├── StreamingMarkdownRenderer — 实时渲染
              ├── SlashCommandRegistry — 16 个命令
              └── CliEventListener — 事件处理
```

### 4.2 斜杠命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/model` | 切换 LLM 模型 |
| `/stats` | Token 使用统计 |
| `/tools` | 列出可用工具 |
| `/copy` | 复制上次响应 |
| `/compact` | 压缩历史 |
| `/export` | 导出对话为 Markdown |
| `/memory` | 记忆管理 |
| `/init` | 初始化项目配置 |
| `/skill` | 管理技能 |
| `/mcp` | 管理 MCP 服务器 |
| `/undo` | 撤销上次操作 |
| `/resume` | 恢复上次会话 |
| `/debug` | 切换调试模式 |
| `/clear` | 清空对话 |
| `/exit` | 退出 CLI |

### 4.3 核心特性

- **会话持久化**: 通过 `--resume` 参数恢复会话
- **流式 Markdown**: 实时渲染，支持语法高亮
- **工具审批**: 交互式批准/拒绝工作流
- **Tab 补全**: 文件、命令、模型自动补全
- **GraalVM 原生**: 通过 `native-app` 插件编译为原生二进制

---

## 5. Server 模块设计

### 5.1 架构

```
ServerApp → SystemModule + MultiAgentModule + ServerModule
  ├── AgentDefinitionService — 智能体配置 CRUD
  ├── AgentRunner — 并发执行（10 线程，600 秒超时）
  ├── AgentScheduler — 基于 Cron 的定时调度（1 分钟间隔）
  ├── AgentSessionManager — 实时 WebSocket 会话
  ├── FileService — 基于文件系统的文件存储
  └── UserService — 认证 + API Key 管理
```

### 5.2 API 端点

| 类别 | 端点 |
|------|------|
| 智能体 | CRUD、发布、列表 |
| 运行 | 触发、列表、详情、取消 |
| 会话 | 创建、发送消息、审批工具、历史记录、SSE 流 |
| 定时任务 | Cron 任务 CRUD |
| 工具 | 列表、分类 |
| 文件 | 上传、下载 |
| 用户 | 个人信息、API Key |

### 5.3 数据库（MongoDB）

集合: `agents`, `runs`, `schedules`, `users`, `tool_registries`, `files`, `schema_versions`

通过 `SchemaMigrationManager` 进行 Schema 迁移。

---

## 6. Benchmark 模块设计

### 6.1 BFCL 评测流水线

```
BFCLEvaluator
  ├── BFCLDatasetLoader — 加载/解析 BFCL JSON，支持断点续传
  ├── BatchProcessor — 并行执行（CPU 核心数线程，batch=50）
  └── InferenceHandle
        ├── BFCLInferenceFCHandle — 单次函数调用
        └── BFCLInferencePlanHandle — 多轮对话 + WriteTodosTool（最多 4 轮）
```

### 6.2 数据流

```
数据集 (JSON) → BFCLItem[] → InferenceHandle.handle() → BFCLItemEvalResult → 结果文件
                                                                                 ↑
                                                                      （恢复时跳过已完成项）
```

---

## 7. 核心设计模式

| 模式 | 使用位置 |
|------|----------|
| **Builder（构建者）** | AgentBuilder, NodeBuilder, ExecutionContext.Builder, 所有 Config 类 |
| **Lifecycle/Hook（生命周期/钩子）** | AbstractLifecycle 的 8 个钩子点，有序注册 |
| **Strategy（策略）** | Termination, PersistenceProvider, VectorStore, LLMProvider, Formatter |
| **Template Method（模板方法）** | Node.execute() → Agent.chatTurns() → handLLM() |
| **Observer（观察者）** | MessageUpdatedEventListener, StatusChangedEventListener, StreamingCallback |
| **Registry（注册表）** | LLMModelContextRegistry, TracerRegistry, SlashCommandRegistry, ModelRegistry |
| **Adapter（适配器）** | McpClientToolCallMessageHandler（MCP → 内部 ToolCall） |
| **Decorator（装饰器）** | 生命周期管线包装智能体执行 |
| **Factory（工厂）** | ToolCallResult.completed/failed/pending, Content.of/ofFileUrl |

---

## 8. 配置参考

### 8.1 Agent Builder 选项

```java
Agent.builder()
    // 身份标识
    .name("agent-name")
    .description("智能体描述")

    // LLM 配置
    .llmProvider(provider)
    .model("gpt-4")
    .temperature(0.7f)
    .reasoningEffort(ReasoningEffort.HIGH)

    // 提示词
    .systemPrompt("你是 {{role}}")
    .promptTemplate("回答: {{query}}")

    // 工具
    .toolCalls(List.of(tool1, tool2))
    .mcpServers(List.of(mcpConfig))

    // 上下文
    .maxTurn(20)
    .useGroupContext(true)
    .compression(Compression.builder()...)
    .toolCallPruning(ToolCallPruning.builder()...)

    // 功能特性
    .ragConfig(RagConfig.builder()...)
    .skills(SkillConfig.of("/path/to/skills"))
    .unifiedMemory(memory)
    .reflectionConfig(ReflectionConfig.defaultReflectionConfig())
    .subAgents(List.of(subAgent))

    // 生命周期
    .addAgentLifecycle(customLifecycle)

    // 会话
    .persistence(new AgentPersistence())
    .persistenceProvider(fileProvider)
    .streaming(true)
    .streamingCallback(callback)

    .build();
```

### 8.2 系统变量

| 变量 | 注入时机 | 内容 |
|------|----------|------|
| `AGENT_TOOLS` | 构建时 | 格式化的工具列表 |
| `NODE_CURRENT_INPUT` | 运行时 | 当前用户查询 |
| `SYSTEM_CURRENT_TIME` | 运行时 | ISO 时间戳 |
| `NODE_CURRENT_ROUND` | 运行时 | 当前轮次编号 |
| `NODE_MAX_ROUND` | 构建时 | 配置的最大轮次 |
| `AGENT_WRITE_TODOS_SYSTEM_PROMPT` | 运行时 | 活跃的待办事项上下文 |

---


## 9. 附录：关键文件索引

### 核心智能体
- `core-ai/src/main/java/ai/core/agent/Agent.java`
- `core-ai/src/main/java/ai/core/agent/Node.java`
- `core-ai/src/main/java/ai/core/agent/AgentBuilder.java`
- `core-ai/src/main/java/ai/core/agent/ExecutionContext.java`

### 上下文管理
- `core-ai/src/main/java/ai/core/context/Compression.java`
- `core-ai/src/main/java/ai/core/context/ToolCallPruning.java`
- `core-ai/src/main/java/ai/core/context/CompressionLifecycle.java`
- `core-ai/src/main/java/ai/core/context/ToolCallPruningLifecycle.java`

### 工具
- `core-ai/src/main/java/ai/core/tool/ToolCall.java`
- `core-ai/src/main/java/ai/core/tool/function/Function.java`
- `core-ai/src/main/java/ai/core/tool/ToolExecutor.java`
- `core-ai/src/main/java/ai/core/tool/tools/WriteTodosTool.java`

### LLM
- `core-ai/src/main/java/ai/core/llm/LLMProvider.java`
- `core-ai/src/main/java/ai/core/llm/domain/Message.java`
- `core-ai/src/main/java/ai/core/llm/domain/CompletionRequest.java`

### 生命周期
- `core-ai/src/main/java/ai/core/agent/lifecycle/AbstractLifecycle.java`
- `core-ai/src/main/java/ai/core/skill/SkillLifecycle.java`
- `core-ai/src/main/java/ai/core/memory/MemoryLifecycle.java`

### 持久化
- `core-ai/src/main/java/ai/core/persistence/PersistenceProvider.java`
- `core-ai/src/main/java/ai/core/agent/AgentPersistence.java`
- `core-ai/src/main/java/ai/core/session/SessionManager.java`

### 流程
- `core-ai/src/main/java/ai/core/flow/Flow.java`
- `core-ai/src/main/java/ai/core/flow/FlowNode.java`

### MCP
- `core-ai/src/main/java/ai/core/mcp/client/McpClientManager.java`
- `core-ai/src/main/java/ai/core/mcp/server/McpServerService.java`
