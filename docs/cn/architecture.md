# Core-AI 分层架构

> **文档定位**：本页描述 Core-AI 的整体分层结构，并刻意区分两层信息——
> **意图层**（架构设计想让它长什么样，来自 `docs/*/design-*.md`）与
> **事实层**（当前代码实际长什么样，来自源码静态分析）。
> 两层的差距即**架构漂移**，是后续治理的重点。
>
> - 记录日期：2026-08-28
> - 数据来源：`build.gradle.kts`、`core-ai/src/main/java`（439 文件）、`core-ai-server/src/main/java`（699 文件）、`docs/*/design-*.md`
> - 维护约定：本页是**意图 + 事实**的对照，不是纯愿景文档。改架构时同步更新本页；漂移清单（见 §7）应由工具定期检查。

---

## 1. 项目定位

Core-AI 是 Java 21 构建的 AI Agent 框架 + 可自部署的 Agent 平台：

```
core-ai (库)          — 用代码构建 Agent（可嵌入 SDK）
core-ai-server (平台) — 以服务形式运行 Agent（定义 / 调度 / 执行 / 观测）
core-ai-cli (工具)    — 终端里本地或远程与 Agent 交互
core-ai-frontend (Web)— 浏览器管理界面
```

---

## 2. 总体结构与模块依赖

### 2.1 模块清单（Gradle 多模块）

| 模块 | 形态 | 规模 | 职责 |
|------|------|------|------|
| `core-ai-api` | Java 库 | ~466 文件 | **纯契约层**：A2A 协议类型、会话事件类型、JSON Schema、WebService 接口与不可变 DTO；仅依赖 `core-ng-api` |
| `core-ai` | Java 库 | ~439 文件 | **SDK 框架**：agent / llm / tool / rag / memory / flow / skill / mcp / sandbox 等 |
| `core-ai-server` | Java 应用 | ~699 文件 | **运行时平台**：REST + SSE + A2A + 调度 + 沙箱编排 + OTLP 观测，Mongo/Redis 持久化 |
| `core-ai-cli` | 终端应用 | — | picocli + JLine，GraalVM native-image；本地进程内 Agent + 远程连接 |
| `core-ai-frontend` | React SPA | — | React 19 + Vite + Tailwind 管理界面 |
| `core-ai-sandbox-runtime` | Go 服务 | — | 隔离代码执行沙箱，HTTP JSON 协议，零第三方依赖 |
| `core-ai-benchmark` | 评测应用 | — | Harbor 评测框架接入，对 `core-ai-cli` 跑 terminal-bench / swebench 等 |

### 2.2 模块依赖方向（来自 `build.gradle.kts`）

```
core-ai-api  ───────────────────────────── 最底层（纯契约，无环）
   ↑
core-ai  ──依赖──> core-ai-api
   ↑
   ├── core-ai-server  ──依赖──> core-ai + core-ai-api + core-ng-mongo + jedis
   ├── core-ai-cli     ──依赖──> core-ai + core-ai-api + ACP + picocli
   └── core-ai-benchmark ──依赖──> core-ai + core-ai-api
```

✅ **最干净的一层边界**：`core-ai-api` 作为独立契约模块，所有上层单向依赖它。
Server 对 SDK 的耦合集中在契约上——按 grep 出现次数粗略统计，
`ai.core.api.*` 约 720 处、`ai.core.tool.*` 约 170 处、`ai.core.llm.*` 约 80 处、`ai.core.sandbox.*` 约 70 处、`ai.core.agent.*` 约 50 处（按 import 语句计则略低）。

---

## 3. 物理分层（三层）

```
┌── 产品形态层 ─────────────────────────────────────────────┐
│  core-ai-cli (终端)         core-ai-frontend (Web 管理台)     │
├── 运行时平台层 ───────────────────────────────────────────┤
│  core-ai-server (Java 平台：REST/SSE/A2A/调度/观测)            │
│  core-ai-sandbox-runtime (Go 隔离执行沙箱，按需拉起)          │
├── SDK/框架层 ─────────────────────────────────────────────┤
│  core-ai (可嵌入框架)      core-ai-api (公共契约)             │
│  core-ai-benchmark (评测)                                   │
└──────────────────────────────────────────────────────────┘
```

**平台对框架的无侵入原则**（设计约定，`docs/cn/design-server-architecture.md`）：
Server 不修改 SDK Agent 框架，所有平台行为通过已有扩展点对接
（`StreamingCallback`、`AbstractLifecycle`、`PersistenceProvider`、`AgentEventListener`）。
✅ 代码事实基本吻合：server 以调用方身份依赖 SDK，未见到对 SDK 内部的 patch。

---

## 4. SDK 内部架构（`core-ai`）

### 4.1 意图层：README 声称的 5 层

```
应用层 Applications
编排层 Orchestration     Flow / Planning
代理层 Agents             Agent / Memory / Reflection
能力层 Capabilities       Tools / RAG / VectorStore / MCP
提供商层 Providers        LLM / Embeddings / Reranker
```

### 4.2 事实层：按依赖高度排序的实际包结构

| 层级 | 包 | 职责 | 依赖特征 |
|------|----|------|----------|
| L0 基础设施 | `persistence` | 可插拔 KV 存储 SPI（Redis/File/Temp） | 干净叶子，零 `ai.core` 依赖 |
| L0 | `schedule` | 纯 Java cron 解析 + 调度任务 | 干净叶子 |
| L0 | `internal` | patch 的 core-ng HTTP client + JSON 工具 | 零依赖，但被 15 个文件跨包引用（泄漏） |
| L0 | `vender` | vendored 二进制管理（ripgrep 等） | 仅依赖 `utils` |
| L1 共享 | `utils` | JsonUtil / ShellUtil / 计数 | ⚠️ 反向依赖 llm/document/tool |
| L1 | `document` | Document/TextChunk/Tokenizer/分割器 | ⚠️ 反向依赖 llm/defaultagents |
| L2 提供商 | `llm` | LLMProvider SPI + LiteLLM 实现 + 流式 | ⚠️ 反向依赖 agent |
| L2 | `media` | 图像/视频生成 provider | 正常 |
| L3 能力 | `tool` | ToolCall/Executor/Orchestration + ~50 内置工具 | ⚠️ god 包，依赖 ~20 个包 |
| L3 | `rag` / `vectorstore` | RAG + HNSWLib/Milvus | ⚠️ rag↔vectorstore 循环 |
| L3 | `mcp` | MCP 客户端/服务端 | ⚠️ mcp↔tool 循环 |
| L3 | `sandbox` | Sandbox/SandboxProvider 抽象 | 依赖 agent/tool 类型 |
| L3 | `skill` | Skills 注册/加载 | ⚠️ skill↔tool 循环 |
| L3 | `prompt` | Mustache 模板 + Langfuse | 正常 |
| L4 代理 | `agent` | **中心运行时**：Agent/Node/ExecutionContext/生命周期 | 最核心之一 |
| L4 | `memory` / `reflection` / `context` / `termination` / `defaultagents` | 记忆 / 反思 / 压缩 / 终止条件 / 预置子代理 | 均与 agent 双向耦合 |
| L5 会话 | `session` / `a2a` | 会话管理 + A2A 集成 | 依赖 agent |
| L6 编排 | `flow` | 图引擎（LLM/Agent/RAG/Tool 节点） | ⚠️ **孤儿**：无任何消费者 |
| L7 装配 | `bootstrap` / `telemetry` / `sse` | 装配 / OpenTelemetry 追踪 / SSE | sse 也基本无人消费 |

### 4.3 中心度与叶子

- **中心 hub**（被依赖最多）：`llm`(13 个外部包)、`agent`(12)、`utils`(12)、`tool`(10)、`document`(10)、`prompt`(8)
  → 代码事实是 **hub 型结构**，而非 README 画的"5 层叠罗汉"。
- **干净叶子**：`persistence`、`schedule`、`internal`、`vender`
- **孤儿**：`flow`（编排层在 SDK 内无消费者，server 用的是自建 `server.workflow.engine`）、`sse`

### 4.4 已知包循环（双向 import 边）

```
agent ↔ tool        agent ↔ memory      agent ↔ context    agent ↔ reflection
agent ↔ termination agent ↔ session     agent ↔ defaultagents
rag ↔ vectorstore   llm ↔ telemetry     llm ↔ document
mcp ↔ tool          skill ↔ tool        tool ↔ defaultagents
document ↔ defaultagents
```

---

## 5. Server 端架构（`core-ai-server`）

### 5.1 纵向 4 层（每个功能模块内部，自上而下）

```
ai.core.api.server.*      ← 契约层（位于 core-ai-api 模块：WebService 接口 + 不可变 DTO）
   ↓ 实现
*WebServiceImpl / *Controller   ← Web 层（HTTP/RPC 门面；Controller 走 http().route）
   ↓
*Service                      ← 服务层（业务逻辑，Module.initialize() 中 bind()）
   ↓
domain 的 @Collection 实体     ← 持久化层（Mongo，56 collection + 4 view）
```

命名约定：实体 = `domain` 下的名词类（`@Collection`/`@Field`/`@Id`）；
业务逻辑 = `*Service`；HTTP = `*WebServiceImpl`（RPC 门面）或 `*Controller`（原始路由，如 `OTLPController`、`IngestController`、`GatewayProxyController`）。

### 5.2 横向：30+ 功能模块

`ServerApp.initialize()` 按**有先后顺序**装配（顺序是 load-bearing 的）：

```
load(SystemModule) → registerMongo() → load(MultiAgentModule) [SDK]
  → load(ServiceApiModule / McpServerModule / McpModule)   [MCP/API 工具]
  → loadPlatformInfrastructure()  (14 模块：auth/messaging/rbac/sse/blob/file...)
  → loadDomainModules()           (22 模块：session/task/trace/dataset/memory/sandbox/...)
  → load(WebModule)               (SPA 静态服务)
```

> ⚠️ 顺序约束示例：`ApiUserModule` 必须早于 `TraceModule`（trace 注入配额服务）；
> `ProjectModule` 必须最后加载（其流水线注入 `AgentRunner` + `ToolRegistryService`，
> 而 core-ng 的 `bind()` 立即解析依赖，被依赖者必须先绑定）。

### 5.3 Mongo 集合（`ServerApp.registerMongo()` 注册的核心实体）

| 分组 | 集合（节选） |
|------|-------------|
| 账户 | `User`, `ApiKey`（ApiUser 用量经 view `ApiUserDailyUsageRow` 暴露） |
| Agent | `AgentDefinition`, `AgentRun`, `AgentSchedule`, `AgentMemory`(+extraction/experiment) |
| 会话 | `ChatSession`, `ChatMessage`, `SessionFeedback`, `SessionSchedule` |
| 工具/技能 | `ToolRegistryEntry`, `SkillDefinition`, `MarketplaceRepo` |
| 追踪 | `Trace`, `Span`, `TraceDailyStats`, `PromptTemplate` |
| 数据 | `Dataset`, `DatasetRecord`, `Project`(+subject/report/stats) |
| 工作流 | `WorkflowDefinition`, `WorkflowPublishedVersion`, `WorkflowRun`, `WorkflowNodeRun` |
| 其他 | `FileRecord`, `SandboxSnapshotDoc`, `BackgroundTask`, `Notification`, `CostAlertRule/Event`, `ReplayExperiment/Run`, `MediaJob`, `SystemSettings`, `SystemPrompt` |

实体分布在 `ai.core.server.domain`（主体）+ 8 个特性局部包（`trace/domain`、`trigger/domain`、`replay/domain`、`sandbox/snapshot`、`memory`、`apimcp/serviceapi/domain`、`costalert`、`channel`）。

### 5.4 跨模块耦合分析

| 发现 | 说明 |
|------|------|
| `domain` 是最中心实体层 | 几乎所有模块依赖它：workflow(29) project(22) gateway(13) session(10)… |
| `web` 是 **god package** | 19 个无关功能的 `*WebServiceImpl` 堆在 `ai.core.server.web`；对 rbac 20 条 import、对 session 7 条；`ChatSessionWebServiceImpl` 同时注入 rbac/memory.experiment/sandbox.snapshot/session/domain |
| `tool ⇄ run ⇄ agent` 紧密簇 | `ToolRegistryService` → import run/agent/llmcall；`AgentRunBuilder`/`SubAgentAssembler`/`LLMCallBuilderTools` 反向 import tool |
| `session` 是 god 模块 | import 16 个其他模块包 |
| 共享基础设施 | `sse`(SseEndpointRegistry，6+ 模块用)、`messaging`(Redis 发布订阅)、`rbac`、`blob`、`file`、`schedule`、`util` 无专属 feature 模块，被多模块消费 |

已确认的包级双向环：`tool⇄run`、`tool⇄agent`、`tool⇄llmcall`、`session⇄web`、`session⇄agent`、`session⇄messaging`、`web⇄apiuser`、`web⇄rbac`、`apiuser⇄rbac`、`a2a⇄messaging`、`trace⇄analytics`（弱）。

---

## 6. 基础设施边界

### 6.1 各组件接入 server 的协议

```
core-ai-frontend (React)   ──REST /api/*──＋──SSE──→  server
                             (POST /api/sessions/messages/stream, PUT /api/sessions/events)
core-ai-cli --server        ──A2A 协议 (HTTP+JSON+SSE, /api/a2a)──→  server
core-ai-cli 斜杠命令         ──REST /api/* + SSE──→  server
core-ai-server              ──HTTP JSON (SandboxClient)──→  sandbox-runtime (Go)
DockerSandboxProvider       ──按需拉起容器──→  chancetop/core-ai-sandbox-runtime:latest
```

### 6.2 沙箱 runtime（Go）

- **角色**：隔离执行 Agent 的"碰文件系统 + 跑代码"类工具；单二进制、零第三方依赖。
- **协议**：纯 HTTP JSON（server 单向调用，沙箱不回拨）。端点：
  `/health`、`/execute`、`/tasks/{id}`、`/files/content`、`/files/upload`、`/skills/{name}`、`/mcp`(+`/mcp/start` `/mcp/stop`)、`/snapshot`、`/snapshot/restore`、`/ocg/callback/*`。
- **执行工具**：`run_bash_command`、`run_python_script`、`read_file`、`write_file`、`edit_file`、`glob_file`、`grep_file`。
- **隔离措施**：非 root、环境变量最小化 + 黑名单、路径越界防护、输出截断 30KB、超时钳制。
- **额外能力**：托管子 MCP 服务器（stdio JSON-RPC → Streamable-HTTP 桥接）、文件系统快照（tar.gz + manifest，防 tar 炸弹）。
- **镜像**：`chancetop/core-ai-sandbox-runtime:latest`（python:3.12-slim + Node 22 + uv + gh + Chromium）。
- **设计对照**：✅ 与 `docs/cn/design-sandbox-architecture.md` 的 Ports & Adapters 高度吻合——
  抽象接口 `Sandbox`/`SandboxProvider`（SDK 内）+ 编排层（server 内 `SandboxService`/`SandboxManager`/`LazySandbox` 懒加载与自愈）+ 三种 provider（AgentSandbox/K8s/Docker）。

### 6.3 前端与 CLI

- **前端**：REST(`fetch` /api/*) + SSE（XHR + `Accept: text/event-stream`）；无 WebSocket。Vite dev proxy 显式透传 SSE 到 :8080。
- **CLI 双模式并存**（注意区分）：
  1. **本地模式**：进程内 Agent（`CliApp` → `AgentSessionRunner` → `InProcessAgentSession`），不经 server；
  2. **远程模式**：`--server` 走 **A2A 协议**（`A2ARemoteConnector` → `HttpA2AClient`，发现 `/.well-known/agent-card.json`）；斜杠命令走 REST+SSE（`RemoteApiClient` + `HttpAgentSession`）。
  ⚠️ 两套远程交互协议并存（A2A 与 REST 会话），是后续演进需要关注的复杂性来源。

### 6.4 部署形态

- `docker-compose.local.yml`：mongo（replSet）+ redis + core-ai-server 三服务。
- **沙箱不在 compose 中**——由 server 的 `DockerSandboxProvider` 运行期按会话拉起容器。
- K8s 形态：多副本共享 Mongo，调度器用 Mongo 原子 `findOneAndUpdate` 做分布式锁（无外部协调器）。

---

## 7. 边界契约与漂移清单（可检查规则）

> 本节把"分层应该是什么样"写成**可检查的规则**（import 方向断言）。
> 每条给出规则、当前状态、以及 grep 验证命令。
> 目标：配合代码图谱工具（如 CodeGraph）做**架构漂移自动检测**——
> agent 每次变更前自查这些规则，违规即拦截。

### 7.1 SDK 层规则

| # | 规则 | 当前状态 | 验证方式 |
|---|------|---------|---------|
| R1 | `ai.core.llm`（提供商层）不得依赖 `ai.core.agent.*` / `ai.core.tool.*` | ❌ 违反：`LLMProvider` import `agent.AttachedContent`、`agent.internal.AgentHelper`；LiteLLM import `agent.CancelReason` | `grep -rl "ai.core.agent" core-ai/src/main/java/ai/core/llm` |
| R2 | `ai.core.tool` 不得直接依赖 `ai.core.agent.ExecutionContext` 等 agent 运行时类型（应抽象共享接口到 api/契约层） | ❌ 违反：`ToolCall`/`ToolExecutor`/`ToolOrchestration`/`ToolRegistry` 均依赖 | `grep -rl "ai.core.agent" core-ai/src/main/java/ai/core/tool` |
| R3 | `ai.core.document`、`ai.core.utils` 不得依赖 `defaultagents`/`llm` 上层 | ❌ 违反：`LLMTextSplitter` → `defaultagents`+`llm`；`MessageTokenCounterUtil` → `llm` | `grep -rl "ai.core.defaultagents\|ai.core.llm" core-ai/src/main/java/ai/core/document core-ai/src/main/java/ai/core/utils` |
| R4 | `ai.core.rag` ↔ `ai.core.vectorstore` 不得双向循环（统一由 rag 依赖 vectorstore） | ❌ 违反：`VectorStore`/`HnswConfig` import `rag.*` | `grep -rl "ai.core.rag" core-ai/src/main/java/ai/core/vectorstore` |
| R5 | `ai.core.flow`（编排层）必须有明确消费者，否则标记 deprecated | ⚠️ 孤儿：SDK 内无消费者，server 自建 `server.workflow.engine` | `grep -rl "ai.core.flow" --include="*.java" . \| grep -v "core-ai/src/main/java/ai/core/flow"` |
| R6 | 顶层包循环需逐步消除（§4.4 清单），新代码不得新增双向依赖 | ❌ 存在 14 个循环 | 周期性依赖扫描 |

### 7.2 Server 层规则

| # | 规则 | 当前状态 | 验证方式 |
|---|------|---------|---------|
| R7 | `ai.core.server.domain`（共享实体层）不得向上依赖 service 模块（blob/gateway/settings/project/task…） | ❌ 违反：`domain/GeminiFileService` → blob；`domain/BackgroundTask` → task；`domain/migration/*` → project | `grep -rlnF -e ai.core.server.blob -e ai.core.server.gateway -e ai.core.server.settings -e ai.core.server.project -e ai.core.server.task core-ai-server/src/main/java/ai/core/server/domain/` |
| R8 | `ai.core.server.web` 不应作为共享 god package——每个 WebServiceImpl 归属其功能模块 | ❌ 违反：19 个 WebServiceImpl 集中在 web 包 | `find core-ai-server/src/main/java/ai/core/server/web -name '*WebServiceImpl.java' \| wc -l` |
| R9 | `ai.core.server.tool` / `run` / `agent` 三角簇需明确单向依赖方向 | ❌ 违反：双向环 | `grep -rl "ai.core.server.run" core-ai-server/src/main/java/ai/core/server/tool` |
| R10 | Mongo 实体文件位置统一（收敛到 `domain` 或模块内 `domain` 子包，避免 8+ 处分散） | ⚠️ 分散 | `find core-ai-server/src/main/java -name "*.java" \| xargs grep -l "@Collection"` |

### 7.3 架构漂移 TOP 结论

1. **SDK"5 层"是纸面架构**——代码实际是 hub 型 + 14 个包循环；README 分层图若作为契约早已被违反。
2. **server 的 `web` god package**——设计文档画的"模块自持端点"未落实。
3. **`domain` 实体层向上依赖**——设计是纯数据层，实际混入依赖上层服务的类。
4. **`flow` 编排层孤儿**——SDK 与 server 各有一套工作流引擎。
5. **SDK 与 server 循环结构同构**（`agent↔tool` 两层都出现）——是系统性耦合，非偶然。

---

## 8. 关键架构决策（ADR 摘要）

| 决策 | 选择 | 理由 |
|------|------|------|
| D1 契约独立成模块 | `core-ai-api` 单独发布，仅依赖 core-ng-api | 前后端共享稳定契约，server/CLI 单向依赖 |
| D2 平台不侵入框架 | server 通过扩展点对接 SDK | 框架 bug 修复自动惠及所有部署形态 |
| D3 Agent 草稿/发布分离 | draft 可编辑，published 为不可变快照 | 运行可复现性 |
| D4 异步优先 | 运行在后台线程池，HTTP 立即返回 202 | Agent 执行是秒~分钟级 |
| D5 传输无关会话 | `AgentSession` 接口同时服务 CLI 本地 / SSE / A2A | 交互层不绑定传输 |
| D6 沙箱端口-适配器 | `Sandbox`/`SandboxProvider` 抽象 + 3 种 provider | 环境可切换（Docker/K8s/CRD） |
| D7 工具审批双模型 | 交互式逐个审批；调度/触发全自动（工具注册时预审） | 自治 Agent 不能等人审批 |
| D8 观测走 OTLP 标准 | SDK 内置 OpenTelemetry，server 自带 OTLP 接收器 | 可替换 Langfuse/Jaeger 零代码改动 |
| D9 调度去中心化 | Mongo 原子 CAS 代替 ZooKeeper/Redis 锁 | 无外部协调器，多副本安全 |

---

## 9. 演进建议（按优先级）

1. **修复 SDK 方向性违规（R1/R3）**：把 `agent` 的 `ExecutionContext` 等运行时类型中供 provider/capability 使用的部分抽象到 `core-ai-api`，斩断 `llm→agent`、`document→defaultagents`、`utils→llm`。
2. **`tool` 减负**：`tool` 依赖 ~20 个包，是最大的重构热点；优先把 `tool→agent` 依赖通过接口反转。
3. **收敛 `web` god package（R8）**：按模块拆分 WebServiceImpl 归属。
4. **清理 `flow` 孤儿（R5）**：决定 SDK `flow` 与 server `workflow.engine` 是合并还是弃用其一。
5. **把 §7 规则接入工具**：接入代码图谱（CodeGraph）后，将 R1-R10 写成 import 断言，纳入 CI / agent 变更前自查。
