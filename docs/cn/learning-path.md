# core-ai 框架学习路径

> 一份按依赖关系编排、由浅入深的 core-ai 框架学习路径。
> 每个阶段给出：**学习目标 / 要读的文件（带路径）/ 核心概念 / 配套教程 / 验收检查**。
> 与 [`architecture.md`](./architecture.md)（事实层：代码长什么样）和 [`overview.md`](./overview.md)（意图层：设计想成为什么）配套阅读；本篇是**顺序层：按什么顺序读才能读懂**。

---

## 0. 如何使用本路径

- 先建立心智模型（Stage 0），再沿 **契约 → 内核 → 宿主 → 客户端 → 前端 → 进阶** 逐层下钻。每一层都建立在前一层的词汇之上，跳层会卡。
- 每个子系统的「读什么」只列**最该先读的 5–10 个文件**；读完即可对照该子系统其余文件，不必通读。
- 路径与 `docs/cn/` 下现有 8 篇 `tutorial-*.md` 一一对应，每阶段附「配套教程」作为动手实验。
- 所有路径相对仓库根。标注 ⚠️ 的是**已知坑/偏差**，见 §4。

---

## 1. 模块全景图

core-ai 实际是 **5 个 Gradle 模块 + 1 个 npm 前端 + 1 个 Go 沙箱 sidecar**，共 7 个组成部分。`settings.gradle.kts` 只注册了前 5 个；前端与沙箱不归 Gradle 管，但在根 `build.gradle.kts` 里被 npm/Exec 任务串进构建链。

### 依赖关系图

```
  非 Gradle  ┌──────────────────────────────────────────────────────┐
  sidecar    │  core-ai-frontend       (npm/Vite, React 19 SPA)     │  调 server /api/* + SSE
             │  core-ai-sandbox-runtime (Go 1.22, 纯标准库 sidecar)  │  被 agent 经 HTTP 调用
             └──────────────────────────────────────────────────────┘

  Gradle（settings.gradle.kts 注册的 5 个）

                core-ai-api        纯契约：DTO + 注解 + WebService 接口
                    ▲
                    │ depends on
                    │
              ┌─────┴──────────────┐
              │  core-ai (kernel)  │  引擎本体：agent loop + 全部子系统
              └──▲───▲───▲─────────┘
                 │   │   │ depends on
          ┌──────┘   │   └──────┐
          │          │          │
   core-ai-server  core-ai-cli   core-ai-benchmark
   运行时宿主+HTTP  REPL/CLI     BFCL 基准 ⚠️无 Main
```

### 七模块速查表

| 模块 | 语言/构建 | 角色 | 入口 | 依赖的 core-ai 模块 |
|---|---|---|---|---|
| `core-ai-api` | Java 25 / Gradle | 纯契约：A2A DTO、`@CoreAiMethod` 注解、`*WebService` 接口 | 无 main（被引用） | 仅 core-ng-api |
| `core-ai` | Java 25 / Gradle | 内核引擎：agent loop、LLM/tool/memory/rag/flow/mcp/skill | `MultiAgentModule`（DI）+ `AgentBootstrap` | `core-ai-api` |
| `core-ai-server` | Java 25 / Gradle | 运行时宿主 + HTTP/SSE/OTLP，30+ 垂直切片 | `Main.java` → `ServerApp` | `core-ai`、`core-ai-api` |
| `core-ai-cli` | Java 25 / Gradle | 交互式 REPL + 内嵌 A2A server + ACP stdio | `Main.java` → `CliApp` | `core-ai`、`core-ai-api` |
| `core-ai-benchmark` | Java 25 / Gradle | BFCL 函数调用基准测试 | ⚠️ 无 `Main.java`，程序式入口 `BFCLEvaluator` | `core-ai`、`core-ai-api` |
| `core-ai-frontend` | TS / npm + Vite | 浏览器管理台 SPA | `src/main.tsx` | 非 Gradle，调 server HTTP |
| `core-ai-sandbox-runtime` | Go 1.22 | 隔离执行 bash/python/文件工具 | `main.go` | 非 Gradle，零三方依赖 |

> **关键事实**：底层框架是 **core-ng 9.4.2**（自研 Spring-like，Undertow 内核），**不是 Spring**。构建锁定的 Java 版本是 **25**（`buildSrc/.../project.gradle.kts`），而 `overview.md` 仍写 Java 21 —— 这是文档与代码的已知偏差（见 §4）。

---

## 2. 分阶段学习路径

### Stage 0 — 建立心智模型

**目标**：在读任何代码前，先在脑子里装下「core-ai 是什么、分几层、模块怎么咬合」。

**读什么**
- `docs/cn/overview.md` — 产品总览与 5 层意图架构图（Applications / Orchestration / Agents / Capabilities / Providers）。
- `docs/cn/architecture.md` — 事实层：模块清单、依赖方向、物理三层（产品/运行时/SDK）、R1–R10 架构漂移规则、ADR。
- `settings.gradle.kts` + 根 `build.gradle.kts` — 看清楚 5 个 Gradle 模块怎么注册、前端/沙箱怎么被 npm 任务串进来。
- `buildSrc/src/main/kotlin/project.gradle.kts` — 看 Java 25 工具链与 `-Werror`。

**核心概念**
- **意图 vs 事实**：overview 是设计意图，architecture 是代码事实，两者对照着读。
- **三层编排**：单 agent 循环（Agent）/ 多节点 DAG（Flow）/ 跨 agent（A2A）。

**配套教程**：`tutorial-architecture.md`

**验收**：能不看表画出 §1 的依赖关系图，能说出 core-ai 用 core-ng 而非 Spring、用 Mongo+Redis 而非单一数据库。

---

### Stage 1 — 契约层：`core-ai-api`

**目标**：掌握全平台的「词汇表」。这是纯类型模块、无逻辑、最易入口，且内核/服务端/CLI 都依赖它。

**读什么**（`core-ai-api/src/main/java/ai/core/api/`）
- `tool/function/CoreAiMethod.java` + `CoreAiParameter.java` — 把一个 Java 方法标注成 LLM 可调用工具的 SPI。
- `a2a/AgentCard.java`、`Task.java`、`Message.java`、`Part.java`、`A2AMethod.java` — A2A（Agent-to-Agent）JSON-RPC 协议的 discovery/任务/消息/方法常量。
- `apidefinition/ApiDefinition.java` + `jsonschema/JsonSchema.java` — 声明式 API schema 与工具参数 schema。
- `server/` 下任选 2–3 个 `*WebService` 接口（如 `server/session/AgentSession.java`、`server/agent/AgentDefinitionView.java`、`server/run/AgentRunView.java`）— 服务端 HTTP API 的契约面。

**核心概念**
- **API/Impl 契约分离**：接口 + DTO 在 `core-ai-api`，实现在 `core-ai-server` 的 `*WebServiceImpl` 里，用 `api().service(Interface, Impl)` 绑定。
- 整个平台的对象模型词汇（Agent / Task / Run / Session / Tool / Skill / Workflow / Memory）在此定型。

**验收**：能解释 `@CoreAiMethod` 如何把普通方法变成工具，能说出 A2A 的 `AgentCard` 起什么作用。

---

### Stage 2 — 内核引擎：`core-ai`（核心，最重的一环）

**目标**：读懂 agent 主循环与所有支撑子系统。这是平台心脏，建议按 2a→2e 五个子阶段顺序读。

**读什么**（`core-ai/src/main/java/ai/core/`）

#### 2a. 引导与 DI
- `MultiAgentModule.java` — core-ng `Module` 子类，DI 入口；`initialize()` 跑 `AgentBootstrap`，把 LLM/persistence/vector/tracer/MCP 绑进容器。
- `McpServerModule.java` — 独立 Module，在 `/mcp` 暴露 MCP streamable-HTTP 端点。
- `bootstrap/AgentBootstrap.java` — 读配置、构造各 provider、产出 `BootstrapResult`。

> 概念：宿主应用（server/cli）只要装上 `MultiAgentModule`，就能拿到一整套可用 provider。

#### 2b. Agent 主循环
- `agent/Node.java` — Agent/Flow 节点的抽象基类（消息、终止条件、持久化、生命周期）。
- `agent/Agent.java`（`extends Node`）— agent loop 本体：系统提示、工具集、LLM provider、reflection。
- `agent/AgentBuilder.java` + `agent/AgentAssembler.java` — 流式 builder 组装 + 终局 assembler 统一施加默认值/终止/记忆/reflection。
- `agent/ExecutionContext.java` — 单轮运行时上下文（session/user/task、sandbox、工具、取消）。
- `agent/ModelGateway.java` — 调 LLM provider 的内部 helper，带生命周期钩子。
- `tool/ToolCall.java`、`ToolExecutor.java`、`ToolOrchestration.java` — 工具定义 / 单工具执行（带 tracing）/ 并发批量执行（带并发组屏障）。
- `termination/` — `Termination` 接口与 `MaxRound`、`StopMessage` 等终止策略。

#### 2c. 生命周期钩子链（关键扩展缝）
- `agent/lifecycle/AbstractLifecycle.java` — `beforeModel/afterModel`、`beforeTool/afterTool`、`beforeAgentRun/afterAgentRun`、`before/afterAgentBuild` 钩子。
- `context/`（Compression、ToolCallPruning）+ `agent/doomloop/` — 都是作为 lifecycle 接进来的横切逻辑。

> 概念：agent loop 不是一坨，而是钩子链。压缩、死循环检测、计划更新、reflection 全部以 lifecycle 形式织入，由 `ModelGateway`/`ToolExecutor` 调用。**这是内核最重要的扩展点。**

**配套教程**：`tutorial-compression.md`

#### 2d. Provider SPI（统一形态）
- `tool/registry/ToolRegistry.java` — 工具中央注册表，派发唯一事实源。
- `tool/registry/ToolProvider.java` — 工具来源 SPI：`id()`/`priority()` + `RefreshPolicy`（`EVERY_TURN`/`ONCE`/`MANUAL`）。
- `tool/registry/ToolRegistryFactory.java` — 从 builtin 组装 registry。
- `llm/LLMProvider.java` + `llm/LLMProviders.java` — LLM 抽象 + 按 `LLMProviderType` 注册的 registry。
- `persistence/PersistenceProvider.java`、`vectorstore/VectorStore.java`（+ hnswlib/milvus）、`skill/SkillRegistry.java`、`telemetry/TracerRegistry.java` — 同形 SPI。

> 概念：工具/LLM/持久化/向量库/skill/tracer **全是同一套 registry+provider+priority 模式**。高优先级 provider 覆盖同名工具；刷新策略决定每轮/一次性/手动重载。看懂一个就懂全部。

#### 2e. 支撑子系统（按需深读）
| 子系统 | 包 | 必读入口 | 配套教程 |
|---|---|---|---|
| LLM 提供商 | `llm/` | `LLMProvider.java`、`llm/providers/` | — |
| 工具 | `tool/` | `ToolCall`、`ToolOrchestration`、`tool/tools/` | `tutorial-tool-calling.md` |
| 记忆 | `memory/` | `Memory.java`、`MemoryStore.java`、`DecayCalculator.java` | `tutorial-memory.md` |
| RAG | `rag/` | `RagConfig.java`、`SimilaritySearchRequest.java` | `tutorial-rag.md` |
| 向量库 | `vectorstore/` | `VectorStore.java`（hnswlib/milvus 两实现） | — |
| Flow DAG | `flow/` | `Flow.java`、`FlowNode.java`、`flow/nodes/`、`flow/edges/` | `tutorial-flow.md` |
| Skill | `skill/` | `SkillLoader.java`（SKILL.md frontmatter）、`SkillRegistry.java` | `tutorial-skills.md` |
| MCP | `mcp/` | `mcp/client/`、`mcp/server/` | — |
| 沙箱 | `sandbox/` | `Sandbox.java`、`SandboxProvider.java` | — |
| Session | `session/` | `InProcessAgentSession.java`、`TurnDriver.java`、`permission/PermissionGate.java` | `tutorial-basic-agent.md` |
| 遥测 | `telemetry/` | `Tracer.java`、各 `*Tracer` | — |
| 反思 | `reflection/` | `ReflectionConfig.java`、`ReflectionEvaluator.java` | — |
| A2A 运行时 | `a2a/` | clients、transport、`A2ARemoteAgentToolCall` | — |
| 提示工程 | `prompt/` | `PromptTemplate.java`、`prompt/engines/`（Mustache） | — |

**核心概念**
- **三层编排**：单 agent（`Agent`+`ModelGateway`+`ToolOrchestration`）/ 多节点 DAG（`Flow`+`FlowNode`+`FlowEdge`）/ 跨 agent（`a2a/` 把远端 agent 暴露成 `A2ARemoteAgentToolCall`）。
- **Builder + Assembler 分离**：generic builder 负责拼装，assembler 负责统一施加横切默认值。

**验收**：能画出一次 agent turn 的完整调用链（`TurnDriver` → `InProcessAgentSession` → `Agent` → `ModelGateway` → lifecycle 钩子 → `ToolExecutor`/`ToolOrchestration` → 终止判断），能说出为什么压缩是 lifecycle 而不是硬编码在 loop 里。

---

### Stage 3 — 运行时宿主：`core-ai-server`

**目标**：看懂内核如何被托管成一个真服务（HTTP/SSE/OTLP + 调度 + 持久化 + 工作流）。

**读什么**（`core-ai-server/src/main/java/`）
- `Main.java` → `ai/core/server/ServerApp.java` — core-ng `App` 子类，三阶段初始化：① `loadPlatformInfrastructure`（settings、对象存储、SSE、gateway、trace、auth、rbac、messaging、sandbox）→ ② `loadDomainModules`（skill、agent、session、tool、channel、workflow、project）→ ③ `WebModule`（传输层最后挂）。
- **垂直切片范式**：每个子系统 = 一个包 + 一个顶层 `*Module.java`。切片内分层为 `domain/*`（Mongo 实体）→ service → `web/*WebServiceImpl`（RPC）或 gateway controller（HTTP）。
- HTTP/RPC 层：`WebModule.java`、`web/ChatSessionWebServiceImpl.java`（样板 RPC 实现）、`web/auth/`（`RequestAuthenticator`+`AuthInterceptor`+`PermissionInterceptor`）、`web/sse/`（`SseEventBridge`+`AgentSessionChannelListener`）、`gateway/GatewayProxyController.java`（OpenAI 兼容代理）。
- Session：`session/AgentSessionManager.java`、`session/SessionRegistry.java`。
- Gateway：`GatewayModule.java`、`gateway/GatewayRoutingEngine.java`、`gateway/GatewayProxyService.java`。
- Workflow：`workflow/WorkflowRunner.java`（Mongo 租约生命周期）、`workflow/WorkflowAdvancer.java`（纯 DAG 驱动循环）、`workflow/engine/WorkflowGraph.java`。
- 调度：`schedule/AgentScheduler.java`（cron 扫描 + Mongo CAS 抢占 `next_run_at`）、`schedule/SessionScheduler.java`、`schedule/MongoScheduledTaskStore.java`。
- Trace：`trace/web/otlp/OTLPController.java`、`trace/service/IngestService.java`。
- Replay：`replay/service/ReplayService.java`+`ReplayExecutor.java`。
- 沙箱提供方：`sandbox/SandboxService.java`、`sandbox/docker/DockerSandbox.java`、`sandbox/kubernetes/KubernetesSandbox.java`。
- 消息：`messaging/JedisConfig.java`（Redis pub/sub 命令/事件 + 会话归属）。
- 持久化：`ServerApp.registerMongo()`（所有 Mongo collection/view 注册）、`domain/migration/SchemaMigrationManager.java`。

**核心概念**
- **模块化 DI（core-ng，非 Spring）**：每子系统一个 `class ... extends Module`，`ServerApp` 用 `load(new XxxModule())` 装载，靠 `bind()`/`bean()`/`api().service()` 接线。
- **双 HTTP 面**：RPC JSON 服务走 `api().service()`；原生路由走 `http().route()`（gateway 代理、OTLP、SPA 静态）；流式走 `SseEndpointRegistry.register()`。
- **Mongo 为主存储 + Redis 为消息**：实体全注册进 `registerMongo()`；Redis 负责 pub/sub 与会话归属。
- **Mongo CAS 租约做分布式协调**：`AgentScheduler`/`WorkflowRunner` 用条件更新抢活 + 心跳，多副本调度无需独立协调器。

**验收**：能说出一个新子系统要加进来需要哪几样（一个 `domain` 实体、一个 `*Module`、一个 `*WebServiceImpl`、在 `ServerApp` 里 `load` + `registerMongo`），能解释 SSE 流怎么从 `AgentSessionChannelListener` 一路推到前端。

---

### Stage 4 — 客户端与工具链

#### 4a. `core-ai-cli`（REPL）
- `core-ai-cli/src/main/java/Main.java` → `ai/core/cli/CliApp.java` — picocli 入口，按 flag 路由到 `start()`/`startServe()`/`startAcpAgent()`/`startRemote()`，共享 `bootstrapCore()` → `AgentBootstrap`。
- `cli/agent/AgentSessionRunner.java` — 生产者-消费者 REPL：`LinkedBlockingQueue` + 守护 sender 线程，`POISON_PILL` 退出。
- `cli/agent/CommandDispatcher.java` + `cli/command/SlashCommandRegistry.java` — slash 命令路由与注册表。
- `cli/agent/CliAgent.java` — agent 工厂，组装工具/profile/提示。
- `cli/a2a/`（`--serve` 内嵌 A2A server）、`cli/acp/`（ACP stdio）、`cli/graalvm/NativeReflectionFeature.java`（原生镜像反射注册）。

> 概念：picocli + JLine 3 + GraalVM native-image；CLI、server、远端三种模式共用同一套 `AgentBootstrap`，差异只在传输层。

#### 4b. `core-ai-sandbox-runtime`（Go sidecar，⚠️ 非 Gradle）
- `core-ai-sandbox-runtime/main.go` — HTTP server、`toolMap` 工具分发表（bash/python/文件）、`TaskRegistry`（并发上限 + `GET /tasks/{id}` 轮询）。
- `core-ai-sandbox-runtime/snapshot.go` — tar.gz 捕获/恢复 + 白名单根 + sha256。
- `core-ai-sandbox-runtime/mcp.go` — MCP 子进程管理（start/stop/RPC）。
- `core-ai-sandbox-runtime/Dockerfile` — 非根 UID 1001 隔离。

> 概念：Go 1.22 **纯标准库**，零三方依赖；通过 `filepath.EvalSymlinks` + `isPathWithin` 做 workspace 路径囚禁，拒 `..` 与符号链接逃逸。它是 agent 经 HTTP 调用的隔离执行后端，不是 Gradle peer。

#### 4c. `core-ai-benchmark`（BFCL 基准）
- `core-ai-benchmark/src/main/java/ai/core/benchmark/evaluator/BFCLEvaluator.java` — 编排 load → batch process → write，**程序式入口**（⚠️ 模块内无 `Main.java`，无可运行 CLI 入口，见 §4）。
- `benchmark/processor/BatchProcessor.java` — 通用并发批处理 + 进度。
- `benchmark/inference/BFCLInferenceHandle.java` + `BFCLInferenceFCHandle.java` — 策略模式：把 BFCL 函数定义归一化成 Tool schema，对 `LLMProvider` 跑推理并记延迟。
- `benchmark/loader/BFCLDatasetLoader.java` — JSON-line 数据集读取 + 可续跑（跳过已完成的 `*_result.json`）。

**验收**：能说出 CLI 三种启动模式的差别、沙箱为何用 Go 而不和 Java 同构、benchmark 当前为何不能 `java -jar` 直接跑。

---

### Stage 5 — 前端：`core-ai-frontend`

**目标**：理解浏览器管理台如何与 server 通信、如何按特性门控路由。

**读什么**（`core-ai-frontend/`）
- `vite.config.ts` — dev 代理：`/api` 与 `/.well-known` → `http://localhost:8080`，SSE 透传（`text/event-stream` 头重设、不缓冲），加 `X-Forwarded-Proto: https`。
- `src/main.tsx` — React 根挂载。
- `src/App.tsx` — 全路由（lazy + Suspense），`AuthContext`/`CapabilitiesContext` 引导，`RequirePermission` 门控。
- `src/api/client.ts` — 中央 REST 客户端（~2700 行），定义所有 `api.*` 域方法与 DTO；`BASE = ''`（同源相对请求），Bearer apiKey 从 `localStorage` 取，401 跳 `/login`。
- `src/api/capabilities.ts` — `/api/capabilities` 特性旗标 + React context 门控路由。
- `src/components/Layout.tsx` — 应用外壳：可折叠侧边栏、主题切换、通知/设置/登出。
- `src/pages/chat/Chat.tsx`（主聊天：SSE 流、工具审批、artifact）与 `src/pages/workflows/WorkflowEditor.tsx`（可视化工作流编辑器，最大特性面）。

**核心概念**
- **非 Gradle 的 npm/Vite 项目**（React 19 + Vite 8 + Tailwind 4 + React Router 7），靠根 `build.gradle.kts` 的 `npmInstallServer`/`buildFrontendServer`/`copyFrontendServer` 任务把 `build/dist` 拷进 server 安装目录。
- **dev 走代理、prod 走同源**：生产下 SPA 由 server 当静态文件托管（`WebModule.java` + `StaticFileController`）。
- **无独立状态管理库**：用 React Context + 组件局部状态；工作流画布用 `@xyflow/react`（React Flow）。

**验收**：能说清 dev 与 prod 下前端分别怎么连到 server，能解释 `capabilities.ts` 如何决定一个路由对某用户是否可见。

---

### Stage 6 — 进阶专精轨道

读完前 5 个阶段后，按兴趣选轨深钻。每轨给出内核代码 + 服务端代码 + 设计文档三件套。

| 轨道 | 内核（`core-ai`） | 服务端（`core-ai-server`） | 设计文档（`docs/cn/`） |
|---|---|---|---|
| **A. Workflow 引擎** | `flow/` | `workflow/`（Runner/Advancer/Graph） | `design-workflow-engine.md`、`design-workflow-node.md`、`design-workflow-server.md` |
| **B. 记忆 & RAG** | `memory/`、`rag/`、`vectorstore/` | `memory/` 域 | `design-memory-system.md`、`graph-memory-skill.md` |
| **C. A2A & MCP** | `a2a/`、`mcp/` | `a2a/`、`apimcp/` | `design-a2a-remote-agent-tool.md`、`design-cli-server-a2a.md`、`../design/dynamic-mcp-server.md` |
| **D. 可观测性** | `telemetry/` | `trace/`（OTLP ingest） | `media-generation-trace-cost.md` |
| **E. 沙箱 & 隔离** | `sandbox/` | `sandbox/`（Docker/K8s） | `design-sandbox-architecture.md`、`design-sandbox-snapshot.md` |
| **F. Skills** | `skill/` | `skill/` 域 | `skill-enhancement-plan.md`、`experiment-skill-placement-cn.md`、`self-impronvment-skill.md` |

---

## 3. 学习路线速查表

| 阶段 | 模块 | 必读文件（相对根） | 配套教程 | 验收要点 |
|---|---|---|---|---|
| 0 心智模型 | 全局 | `docs/cn/overview.md`、`docs/cn/architecture.md`、`settings.gradle.kts` | `tutorial-architecture.md` | 画出依赖图 |
| 1 契约 | `core-ai-api` | `api/tool/function/CoreAi*.java`、`api/a2a/*`、`api/server/*WebService` | — | 解释 `@CoreAiMethod` |
| 2a 引导 | `core-ai` | `MultiAgentModule`、`bootstrap/AgentBootstrap` | — | 说清 DI 装载 |
| 2b loop | `core-ai` | `agent/Node`、`Agent`、`AgentBuilder`、`AgentAssembler`、`ModelGateway`、`tool/Tool*` | `tutorial-basic-agent.md`、`tutorial-tool-calling.md` | 画出一次 turn |
| 2c 生命周期 | `core-ai` | `agent/lifecycle/AbstractLifecycle`、`context/`、`agent/doomloop/` | `tutorial-compression.md` | 说清为何压缩是 lifecycle |
| 2d SPI | `core-ai` | `tool/registry/*`、`llm/LLMProvider*` | — | 说出统一 registry 模式 |
| 2e 子系统 | `core-ai` | `memory/`、`rag/`、`flow/`、`skill/`、`mcp/`、`session/` | `tutorial-memory/rag/flow/skills.md` | 三层编排 |
| 3 宿主 | `core-ai-server` | `Main`、`ServerApp`、`web/ChatSessionWebServiceImpl`、`gateway/*`、`workflow/*`、`schedule/AgentScheduler` | — | 加新子系统要几步 |
| 4a CLI | `core-ai-cli` | `Main`、`CliApp`、`agent/AgentSessionRunner`、`command/*` | — | 三种模式差别 |
| 4b 沙箱 | `core-ai-sandbox-runtime` | `main.go`、`snapshot.go`、`mcp.go` | — | 为何用 Go |
| 4c 基准 | `core-ai-benchmark` | `evaluator/BFCLEvaluator`、`inference/*`、`loader/*` | — | 为何无 Main |
| 5 前端 | `core-ai-frontend` | `vite.config.ts`、`src/App.tsx`、`src/api/client.ts`、`pages/chat/Chat.tsx` | — | dev/prod 连接差异 |

---

## 4. 已知偏差与坑（学之前先知道）

1. **Java 版本偏差**：`overview.md` 写 Java 21，实际构建工具链锁定 **Java 25**（`buildSrc/src/main/kotlin/project.gradle.kts` 的 `JavaLanguageVersion.of(25)`，且 `-Xlint:all -Werror`）。读到 21 不要困惑，以代码为准。
2. **`core-ai-benchmark` 无可运行入口**：`app` 约定插件设 `mainClass = "Main"`，但该模块下**没有 `Main.java`**，只暴露 `BFCLEvaluator.eval(...)` 程序式入口。直接 `java -jar` 跑不起来，需自写 bootstrap 或经测试调用。
3. **`core-ai-sandbox-runtime` 不在 Gradle 里**：它是独立 Go 模块，**不在 `settings.gradle.kts`**，靠自身 `go.mod`（Go 1.22，零依赖）与 `Dockerfile` 构建。别在 Gradle 输出里找它。
4. **`core-ai-frontend` 也不在 `settings.gradle.kts`**：它是独立 npm 项目，靠根 `build.gradle.kts` 的 npm/Exec 任务串进构建。
5. **`architecture.md` 尚未挂进 VitePress 侧边栏**：`docs/cn/architecture.md` 目前是 untracked 且未进 `docs/.vitepress/sidebars/cn.ts`，在站点导航里不可达（但仍可在 `/core-ai/cn/architecture.html` 直链访问）。本篇 `learning-path.md` 同理，需手动挂入侧边栏才能在导航里出现。

---

## 5. 构建与运行验证

读完每个阶段后，用以下命令验证理解（均在仓库根执行）：

```bash
# 全量构建（5 个 Gradle 模块 + 前端 npm + 拷贝）
./gradlew build

# 只跑 server（含前端构建与拷贝）
./gradlew :core-ai-server:installDist
# 产物在 build/install/core-ai-server/，启动后默认 8080 端口

# 跑 CLI REPL
./gradlew :core-ai-cli:installDist
# 产物中运行 core-ai-cli，进入交互式 REPL

# 沙箱 sidecar（Go）
cd core-ai-sandbox-runtime && go build -o sandbox . && ./sandbox   # 默认 :8080

# 文档站点本地预览（VitePress）
cd docs && npm install && npm run dev   # 预览本篇与全部 cn/ 文档
```

> 验收 Stage 3 的小技巧：在 `ServerApp` 的 `loadPlatformInfrastructure` / `loadDomainModules` 两处下断点，启动一次，看模块装载顺序与 Mongo 注册顺序，胜过读十遍静态代码。

---

*本路径基于源码静态分析整理，文件路径与构建配置均经核实；运行时行为需以实际启动验证为准。*
