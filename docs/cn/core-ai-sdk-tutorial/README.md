# core-ai 内核 SDK 学习教程

> 面向初学者的 **core-ai 内核 SDK** 完整学习教程。
> 基于源码静态分析（Java 25 + core-ng 9.4.2），所有代码片段均来自真实源码。
> 本教程假设你具备 Java 基础，了解依赖注入（DI）概念，但对 core-ai 框架零了解。

---

## 📚 教程定位

本教程专注于 `core-ai/` 模块（内核引擎），**不涉及**：
- `core-ai-server/`（运行时宿主 + HTTP 服务）
- `core-ai-cli/`（交互式 REPL）
- `core-ai-frontend/`（浏览器管理台）
- `core-ai-sandbox-runtime/`（Go 沙箱 sidecar）
- `core-ai-benchmark/`（BFCL 基准测试）

如需了解全局架构，请先阅读：
- [`architecture.md`](../architecture.md) — 代码事实层
- [`learning-path.md`](../learning-path.md) — 学习顺序层
- [`kernel-sdk-deep-dive.md`](../kernel-sdk-deep-dive.md) — 快速概览层

---

## 🗺️ 学习路线图

```
Stage 0: 环境准备（0.5 天）
  ├─ 00-环境准备与构建.md
  └─ 验证：能 ./gradlew build 成功

Stage 1: 架构认知（1 天）
  ├─ 01-内核架构总览.md
  └─ 验证：能画出模块依赖图 + 核心类关系图

Stage 2: 模块引导（1 天）
  ├─ 02-模块引导机制.md
  └─ 验证：能说出 MultiAgentModule 装载了什么、按什么顺序

Stage 3: Agent 核心（3 天，最重）
  ├─ 03-Agent核心循环.md
  ├─ 04-生命周期钩子.md
  └─ 验证：能画出一次 agent turn 的完整调用链

Stage 4: 工具系统（2 天）
  ├─ 05-工具系统.md
  └─ 验证：能写一个自定义 ToolCall 并注册

Stage 5: LLM 系统（1.5 天）
  ├─ 06-LLM提供商.md
  └─ 验证：能理解 completion/embedding/reranking 的 SPI 形态

Stage 6: 记忆与检索（1.5 天）
  ├─ 07-Memory与RAG.md
  └─ 验证：能配置一个简单的 RAG agent

Stage 7: 高级特性（按需选读）
  ├─ 08-Flow编排.md
  ├─ 09-Skill系统.md
  ├─ 10-MCP协议集成.md
  ├─ 11-Session管理.md
  ├─ 12-遥测与可观测.md
  ├─ 13-持久化机制.md
  ├─ 14-提示工程.md
  ├─ 15-终止条件.md
  ├─ 16-沙箱执行.md
  └─ 17-A2A协议.md

Stage 8: 总结与实战（2 天）
  ├─ 18-设计模式总结.md
  ├─ 19-实战示例.md
  └─ 20-调试与优化.md
```

**预计总学习时间**：13-15 天（全职学习）或 4-5 周（兼职学习）

**✅ 教程状态**：全部完成（20 个章节，约 18 万字）

---

## 📖 章节概览

### 基础篇（Stage 0-2）

| 章节 | 主题 | 核心文件 | 学习目标 |
|---|---|---|---|
| **00** | [环境准备与构建](./00-环境准备与构建.md) | `build.gradle.kts`、`settings.gradle.kts` | 能构建项目、运行测试 |
| **01** | [内核架构总览](./01-内核架构总览.md) | 全部 | 能画出模块依赖图 + 核心类关系图 |
| **02** | [模块引导机制](./02-模块引导机制.md) | `MultiAgentModule`、`AgentBootstrap`、`BootstrapResult` | 能说出装载顺序、绑定了什么 |

### 核心篇（Stage 3-6）

| 章节 | 主题 | 核心文件 | 学习目标 |
|---|---|---|---|
| **03** | [Agent 核心循环](./03-Agent核心循环.md) | `Node`、`Agent`、`AgentBuilder`、`ModelGateway`、`ExecutionContext` | 能画出一次 turn 的完整调用链 |
| **04** | [生命周期钩子](./04-生命周期钩子.md) | `AbstractLifecycle`、`CompressionLifecycle`、`DoomLoopLifecycle` | 能写自定义 lifecycle |
| **05** | [工具系统](./05-工具系统.md) | `ToolCall`、`ToolExecutor`、`ToolOrchestration`、`ToolRegistry`、`ToolProvider` | 能写自定义工具并注册 |
| **06** | [LLM 提供商](./06-LLM提供商.md) | `LLMProvider`、`LLMProviders`、`LiteLLMProvider` | 能理解 SPI 形态 |
| **07** | [Memory 与 RAG](./07-Memory与RAG.md) | `Memory`、`RagConfig`、`VectorStore` | 能配置 RAG agent |

### 进阶篇（Stage 7）

| 章节 | 主题 | 核心文件 | 学习目标 |
|---|---|---|---|
| **08** | [Flow 编排](./08-Flow编排.md) | `Flow`、`FlowNode`、`FlowEdge` | 能写 DAG 编排 |
| **09** | [Skill 系统](./09-Skill系统.md) | `SkillLoader`、`SkillRegistry`、`SKILL.md` | 能写自定义 skill |
| **10** | [MCP 协议集成](./10-MCP协议集成.md) | `McpClientManager`、`McpServerService` | 能接入外部 MCP server |
| **11** | [Session 管理](./11-Session管理.md) | `InProcessAgentSession`、`TurnDriver`、`PermissionGate` | 能管理会话 |
| **12** | [遥测与可观测](./12-遥测与可观测.md) | `Tracer`、`LLMTracer`、`AgentTracer` | 能接入 OpenTelemetry |
| **13** | [持久化机制](./13-持久化机制.md) | `PersistenceProvider`、`Persistence` | 能配置持久化 |
| **14** | [提示工程](./14-提示工程.md) | `PromptTemplate`、`PromptInject`、`Langfuse` | 能写模板、接入 Langfuse |
| **15** | [终止条件](./15-终止条件.md) | `Termination`、`MaxRound`、`StopMessage` | 能写自定义终止 |
| **16** | [沙箱执行](./16-沙箱执行.md) | `SandboxProvider`、`DockerSandbox` | 能配置沙箱 |
| **17** | [A2A 协议](./17-A2A协议.md) | `A2ARemoteAgentToolCall`、`a2a/` | 能写跨 agent 协作 |

### 总结篇（Stage 8）

| 章节 | 主题 | 学习目标 |
|---|---|---|
| **18** | [设计模式总结](./18-设计模式总结.md) | 能说出 4 个核心设计模式 |
| **19** | [实战示例](./19-实战示例.md) | 能独立写 5 个完整示例 |
| **20** | [调试与优化](./20-调试与优化.md) | 能排查常见问题 |

---

## 🎯 学习方法建议

### 1. **先读后写**
每章先通读概念，再看源码，最后写代码验证。不要跳过"验证"环节。

### 2. **对照源码**
每个文档都会标注文件路径（相对仓库根），读文档时打开对应 `.java` 文件，逐行对照。

### 3. **画图理解**
核心概念（如 agent loop、lifecycle 钩子链、工具派发）都建议画调用关系图，加深理解。

### 4. **写 demo 验证**
每章末尾都有"动手实践"，建议用 IDE 写一个小 demo，跑通后再进入下一章。

### 5. **不要跳章**
章节之间有依赖关系，例如：
- 不懂 `Agent` 就别学 `Lifecycle`
- 不懂 `ToolCall` 就别学 `ToolOrchestration`
- 不懂 `LLMProvider` 就别学 `RAG`

### 6. **反复阅读**
核心章节（03-Agent、04-Lifecycle、05-工具）建议读 2-3 遍，第一遍理解概念，第二遍看源码，第三遍写代码。

---

## 📋 前置知识

### 必须掌握
- **Java 基础**：泛型、Lambda、Stream API、并发（CompletableFuture）
- **依赖注入（DI）**：理解接口/实现分离、容器、绑定
- **设计模式**：Builder、Strategy、Observer、Template Method

### 建议了解
- **core-ng 框架**：core-ai 基于 core-ng（非 Spring），了解 Module/Bean 概念即可
- **OpenTelemetry**：用于遥测，了解 Span/Tracer 概念即可
- **LLM API**：了解 OpenAI API 格式（completion/tool calling）
- **MCP 协议**：Model Context Protocol，用于工具集成

### 可选了解
- **LiteLLM**：用于多 vendor 支持
- **Milvus / HNSWlib**：向量库实现
- **Mustache**：提示词模板引擎
- **Langfuse**：提示词管理、LLM 可观测

---

## 🛠️ 配套资源

### 文档
- [`architecture.md`](../architecture.md) — 架构事实层（代码长什么样）
- [`learning-path.md`](../learning-path.md) — 学习顺序层（按什么顺序读）
- [`kernel-sdk-deep-dive.md`](../kernel-sdk-deep-dive.md) — 快速概览层（600 行精简版）
- [`overview.md`](../overview.md) — 产品意图层（设计想成为什么）

### 源码
- 仓库根：`/Users/murphy/Code/Chancetop/Project/core-ai/`
- 内核模块：`/Users/murphy/Code/Chancetop/Project/core-ai/core-ai/`
- 所有路径相对仓库根

### 构建
```bash
# 构建整个项目
./gradlew build

# 只构建内核模块
./gradlew :core-ai:build

# 运行测试
./gradlew :core-ai:test

# 生成 Javadoc
./gradlew :core-ai:javadoc
```

---

## ⚠️ 已知偏差与坑

1. **Java 版本**：`overview.md` 写 Java 21，实际构建锁的是 **Java 25**（`buildSrc/.../project.gradle.kts`）。以代码为准。
2. **底层框架**：core-ai 基于 **core-ng 9.4.2**（自研 Spring-like），**不是 Spring**。
3. **文档滞后**：部分设计文档（如 `architecture.md`）未挂进 VitePress 侧边栏，需直链访问。
4. **benchmark 无 Main**：`core-ai-benchmark` 模块没有可运行的 `Main.java`，只有程序式入口。
5. **沙箱是 Go**：`core-ai-sandbox-runtime` 是 Go 写的 sidecar，不在 Gradle 里。

---

## 📝 约定与符号

本教程使用以下约定：

- **文件路径**：相对仓库根，如 `core-ai/src/main/java/ai/core/agent/Agent.java`
- **类名**：用反引号包裹，如 `Agent`
- **方法名**：用反引号包裹 + 括号，如 `execute()`
- **代码片段**：真实源码，带注释说明
- **调用链**：用 `→` 表示调用关系，如 `Agent.execute() → doExecute() → chatLoops()`
- **设计意图**：用 💡 标记
- **常见问题**：用 ⚠️ 标记
- **动手实践**：用 🔧 标记

---

## 🚀 开始学习

准备好了？从 **[00-环境准备与构建](./00-环境准备与构建.md)** 开始吧！

如果你已经熟悉 core-ai 基础，可以直接跳到：
- **[03-Agent 核心循环](./03-Agent核心循环.md)** — 内核心脏
- **[04-生命周期钩子](./04-生命周期钩子.md)** — 最重要的扩展点
- **[05-工具系统](./05-工具系统.md)** — 工具定义与派发
- **[19-实战示例](./19-实战示例.md)** — 5 个完整 demo

---

*本教程基于源码静态分析整理，文件路径与类职责均经核实；运行时行为需以实际启动验证为准。*

*最后更新：2026-08-31*
