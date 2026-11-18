# Skill SDK 能力授权与归属展示 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft；待用户确认关键决策；未实施 |
| 文档版本 | v0.4（会话级能力授权、按能力贡献归属；去掉 runner 与执行绑定；待用户确认） |
| 日期 | 2026-11-17 |
| 需求提出者 / 决策人 | 用户；本文建议不代表用户批准 |
| 范围 | Skill 能力声明、赋予 skill 时的授权、SDK-only 调用面、调用归属、Desktop / Server 事件与历史展示 |
| 事实来源 | 当前工作区源码；代码引用行号基于本次核查 |
| 交付边界 | 只做设计，不修改产品代码、配置、VERSION，不提交、部署或发布 |

## 0. 原始需求与需求编号

### 0.1 用户原始需求（原文）

> 我现在要做一个功能 当skill用sdk的方式使用了api-tool, mcp tool, llm call等工具的时候，我们会在skill加载下方显示出来，相当于用户给了这个skill就是授权了这些tool，但是这些tool不会在LLM的tools列表中，因为是skill的脚本使用的 不需要agent去调用。根据这个需求，我需要你做一个tech design

### 0.2 需求分解

| ID | 要求 | 来源 |
|---|---|---|
| R1 | Skill 脚本通过 SDK 使用 API-tool、MCP tool、LLM_CALL 等能力 | 用户明确要求 |
| R2 | 将这些能力显示在 skill 加载下方 | 用户明确要求 |
| R3 | 用户给 Agent / 会话这个 skill，即一并授权该 skill 的依赖，不再逐工具配置或确认 | 用户明确要求 |
| R4 | 这些 SDK-only 依赖不进入父 LLM 的 tools 列表，由脚本调用，不让 Agent 逐工具执行 | 用户明确要求 |
| R5 | 区分加载时的“已授权能力”与执行时的“实际调用”，不能把声明当作已经执行 | 对 R2 的设计解释，待确认 |
| R6 | 添加 skill 不能绕过资源可授予范围、账号登录、数据权限和配额；V1 按现有角色、API 用户白名单与 enabled 状态约束，不假定已有组织级 ACL | 安全约束，不改变 R3 的一次赋权交互 |
| R7 | 调用按能力贡献归属；多 skill、并行、跨轮和异步不重复、不丢失；不声称脚本进程级归属 | 可观测性与可靠性要求 |
| R8 | 实时展示与历史回放一致；撤销、更新、重建后授权不静默扩大 | 生命周期要求 |
| R9 | Desktop 本地与 Server sandbox 都有真实运行链路，不用 SDK 自报日志伪装平台审计 | 工程完整性要求 |

R5–R9 是设计推导，不冒充用户原始需求。本文件所有新字段、类型、路由与阈值均为提案，除非标注“已有”。

## 1. 推荐结论

### 1.1 一句话架构

**Skill 是带有固定依赖清单的能力包；用户赋予 skill 时一次授权其 SDK 能力，平台在会话级 SDK 调用面执行，UI 在加载节点下展示该 skill 贡献的能力，实际调用按能力贡献者归属。**

三件事明确分离：

1. `Agent ToolRegistry`：供模型发现、选用、派发的工具，保留现有语义。
2. `SkillCapabilityStore`：记录哪个 skill 为会话贡献了哪些具体能力，是授权状态，不是第二套工具定义目录。
3. `SessionSdkBroker`：向脚本暴露会话级 SDK 能力视图（Agent 现有工具 ∪ 已赋予 skill 的贡献），按**能力**归属展示，不按脚本进程归属；不把 grant-only 能力注册到父 `Agent ToolRegistry`。

**授权粒度是会话，不是每次脚本执行。** sandbox 的 `/skill/<name>` 是固定路径且对 Agent 可见，同一 sandbox 里的任何脚本都能读其他 skill 包，所以“按目录/进程判断某个调用属于哪个 skill”既不可靠也不是安全边界。能力在会话内并集生效，UI 用“该能力由哪些 skill 贡献”来归属。

不通过“把工具加载到 Agent 再隐藏 descriptions”实现。模型即便幻觉出这些工具名，也不能从自己的 dispatch map 执行。相同能力若用户另行配置为普通 Agent tool，其原有模型可见性保持不变；**“不进 tools”约束的是 skill 新增的授权来源，不是全局禁止该工具被任何 Agent 使用。**

### 1.2 用户体验

```text
Skill use · sales-audit                           已加载
  SDK 能力 · 该 skill 贡献 · 不提供给模型
    API-tool  crm/leads/list                        已授权
    MCP       analytics/run_query                  已授权
    LLM_CALL  lead-summary                         已授权

SDK 调用 · 3 次 · 按能力贡献归属
  API-tool  crm/leads/list     ← sales-audit        成功 · 210ms
  MCP       analytics/run_query ← sales-audit        成功 · 650ms
  LLM_CALL  lead-summary       ← sales-audit/helper 成功 · 1.2s · 620 tokens
```

- 赋予 / 添加 skill 的 UI 中预览依赖；Server 点击“添加”、Desktop 点击新增的 skill-level“启用 SDK 能力”，即对这个版本的依赖一并授权，不增加每个 API/MCP/LLM_CALL 的审批步骤。
- `use_skill` 完成后，其加载节点下立即出现该 skill 的能力清单；真正执行后再出现在 SDK 调用列表。
- 调用行只声明“该能力由哪些 skill 授权”，不断言是哪个脚本进程发起的——sandbox 里无法可靠证明这一点。
- 参数 / 结果默认收起、脱敏；详情是执行观测，不是喂给 LLM 的 tool response。
- 未执行的能力一直显示“已授权，未调用”，不伪造成功调用。

### 1.3 V1 范围与非目标

V1 必须闭合：MCP、API operation、LLM_CALL；Desktop 本地会话与 Server sandbox；声明、赋权、执行、归属、实时 / 历史、撤销 / 更新。

可按 adapter 扩展：非 runtime-owned builtin / media / decision。Agent delegation 与 dataset **不假装成普通 ToolRef 已被支持**，它们有独立装配、任务、ACL 和资源生命周期，作为后续阶段；V1 遇到 required 的不支持能力必须明确拒绝启用 SDK 功能，而不是忽略声明。

非目标：

- 不给脚本原始 LLM provider endpoint / API key；LLM 仍经已配置的 LLM_CALL 定义。
- 不做脚本 AST 全语言安全证明，不保证任意宿主 Python / shell 不会直连网络或读取用户凭据。
- 不将“安装文件 / 被发现 / 模型加载说明”自动解释为用户授予任意外部权限。
- 不在 V1 建立新的插件平台、Provider 管理平台或新 Agent loop。
- 不追溯补造旧会话中不存在的 SDK 调用记录。

## 2. 当前实现核查

### 2.1 可以复用的能力与真实缺口

| 当前事实 | 代码依据 | 对设计的影响 |
|---|---|---|
| `allowed-tools` 已解析，但不负责运行时授权 | `core-ai/src/main/java/ai/core/skill/SkillLoader.java:236`；`core-ai-server/src/main/java/ai/core/server/skill/quality/SkillPermissionDeclaration.java:9` | 必须有显式版本化 opt-in，不能直接把全部旧声明变成授权 |
| 发布路径统一从 candidate 投影 allowedTools / metadata / version | `core-ai-server/src/main/java/ai/core/server/skill/quality/SkillPublishCandidateBuilder.java:98`；`core-ai-server/src/main/java/ai/core/server/skill/SkillPublishService.java:77` | 复用发布 candidate 与 gate，不再从 UI 字符串另建事实源 |
| 静态 analyzer 能检测部分 Python SDK / shell 使用，但未持久化完整调用清单 | `core-ai-server/src/main/java/ai/core/server/skill/quality/SkillPermissionAnalyzer.java:24`、`:150` | 检测用于校验和作者提示，不能用它自动猜出 runtime grants |
| Server loadSkills 只保存 skill IDs 并安装两个 skill 工具 | `core-ai-server/src/main/java/ai/core/server/session/SessionSkillManager.java:140`、`:152`；`core-ai-server/src/main/java/ai/core/server/skill/SkillToolAssembler.java:29` | 需要公共赋权服务覆盖 session、run 和 subagent 的技能装配路径 |
| 本地 SkillTool 只有不带 context 的 execute；Server 版有 context | `core-ai/src/main/java/ai/core/tool/tools/SkillTool.java:71`；`core-ai-server/src/main/java/ai/core/server/skill/ServerSkillTool.java:64` | 本地必须增加 context-aware skill activation，不只改 Server |
| ToolRegistry 只有 DIRECT 进入 definitions，但其余仍在 model dispatchMap | `core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java:83`；`core-ai/src/main/java/ai/core/agent/Agent.java:187` | 仅“不在初始列表”不等于工具面分离；独立 SDK view 可以保持 model dispatch 不变 |
| DEFERRED 在 sandbox catalog 可调；HIDDEN 不可调 | `core-ai-server/src/main/java/ai/core/server/sandboxhub/SandboxHubCatalog.java:91`；`core-ai-server/src/test/java/ai/core/server/sandboxhub/SandboxHubCatalogTest.java:302` | 不用 HIDDEN 实现脚本能力；也不复用 DEFERRED 的模型发现语义 |
| activate_tools 能把 discoverable 工具提升为 DIRECT | `core-ai/src/main/java/ai/core/tool/tools/ToolActivationTool.java:117` | Grant-only 不进入模型 discovery / activation 源，防止绕回 tools 列表 |
| ToolRef resolver 支持会话级 MCP、API、LLM_CALL；AGENT 有另外装配路径 | `core-ai-server/src/main/java/ai/core/server/tool/ToolRefResolutionService.java:133`、`:190` | 复用解析器 / 工具 adapter；不改共享 ToolCall exposure、不启动另一套 registry 服务 |
| sandbox SDK 使用 session token；用户权限校验不是其当前入口契约 | `core-ai-server/src/main/java/ai/core/server/web/auth/AuthInterceptor.java:135`；`core-ai-server/src/main/java/ai/core/server/sandboxhub/SandboxHubWebServiceImpl.java:26` | 新依赖要在赋权处校验，不把旧 @PermissionsBypass 当作任意资源授权 |
| Hub 调用跳过 lifecycle，但使用 session executor 的身份 / tracing / usage | `core-ai/src/main/java/ai/core/agent/Agent.java:204`；`core-ai-server/src/main/java/ai/core/server/sandboxhub/SandboxHubService.java:207` | 补独立 SDK call observer，不直接套 model lifecycle 造成重复、审批死锁或 conversation 写入 |
| Hub 异步任务目前保存在 service 内存，poll 未按 skill 归属建模 | `core-ai-server/src/main/java/ai/core/server/sandboxhub/SandboxHubService.java:134`、`:178` | 增加 owner-scoped CallLedger，不仅加 UI 字段 |
| Server 使用 ChatMessageService 的展示层聚合，不实例化 Desktop TranscriptRecorder；Web reload 将 tool records 重建成扁平列表 | `core-ai-server/src/main/java/ai/core/server/session/ChatMessageService.java:33`、`:244`；`core-ai-server/src/main/java/ai/core/server/web/SessionHistoryHelper.java:18`；`core-ai-frontend/src/pages/chat/utils.ts:63` | Server 的 SDK history 需要独立持久化和明确 merge；不能假定已有 transcript 或嵌套回放 |
| currentToolCallId 是 ThreadLocal，customVariables 是共享 HashMap，executor 有异步切线程 | `core-ai/src/main/java/ai/core/agent/ExecutionContext.java:48`、`:68`、`:85`；`core-ai/src/main/java/ai/core/tool/ToolExecutor.java:263`、`:343` | SDK 调用不依赖它推断 skill；并发调用需要独立的不可变调用上下文 |
| sandbox 的 skill 目录固定且对 Agent 可见，同一沙箱脚本可读其他 skill 包 | `core-ai-server/src/main/java/ai/core/server/skill/ServerSkillTool.java:98`；`core-ai-sandbox-runtime/main.go:27` | 目录/进程不能作为归属依据或授权边界；授权按会话、展示按能力贡献 |
| SandboxHubCatalog 从 Agent dispatch map 派生 | `core-ai-server/src/main/java/ai/core/server/sandboxhub/SandboxHubCatalog.java:67` | 需要在同一 catalog 合并 skill 能力，但不注册进 ToolRegistry |
| SDK 默认本地走 user-level CLI；CORE_AI_HUB 存在时禁止回退 CLI | `sdk/core-ai-session/core_ai_session/session.py:474`；`sdk/core-ai-session/core_ai_session/cli.py:358`、`:879` | Desktop 需要 session-bound broker；不能只给本地 CLI 加一个日志 header |
| runtime 已监听 loopback Hub、覆写 session token；执行环境注入 CORE_AI_HUB | `core-ai-sandbox-runtime/hubproxy.go:25`、`:69`；`core-ai-sandbox-runtime/main.go:1214` | 沿用现有 session 级 hub；不需要为每次执行注入 skill 凭证 |
| 桌面已有 session event 与持久 transcript 双通道 | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java:396`；`core-ai-cli/src/main/java/ai/core/cli/appserver/EventBridge.java:130` | 复用事件 / record 投递，不在 SDK print 中夹带 UI 协议 |
| ToolStartEvent 与 Desktop ToolItem 没有 skill / capability 归属字段 | `core-ai-api/src/main/java/ai/core/api/server/session/ToolStartEvent.java:19`；`core-ai-desktop/src/renderer/transcript/reducer.ts:70` | SDK 调用用独立 typed 事件与展示索引；不把能力归属塞进 model tool 事件，也不按“最近加载”猜 |
| Web 现有嵌套按 task_id，不是 skill / capability | `core-ai-frontend/src/pages/chat/Chat.tsx:891`；`core-ai-frontend/src/pages/chat/components/ToolsBlock.tsx:145` | task_id 不可挪用为 skill 归属；SDK 调用按 capability + contributors 独立归组 |

### 2.2 不能作为实现捷径的做法

1. 在 `ServerSkillTool.execute()` 注册一批 DIRECT / DEFERRED 工具：授权时机太晚，模型自己触发加载不等于用户赋权，且共享 ToolCall 的可见性可能串会话。
2. `excludeTools()`：同时删除 dispatch，不是 SDK-only 可见性。
3. `context.currentSkill = lastLoadedSkill`：无法处理两个技能、并行脚本或跨轮，且 mutable context 不可当 provenance。
4. 在 SDK 内主动 `send_custom_event`：客户端可伪造 / 丢失，不是执行平台产生的审计。
5. 为脚本补 model tool lifecycle：可能触发逐工具确认、重复事件、模型对话记录或等待用户而脚本阻塞；本功能需要独立的 SDK 调用路径。
6. 自动将 `mcp:*` / `api-tool:*` 每次解析为最新全集：共享 skill 更新或目录新增即静默扩权。

## 3. ADR 摘要与目标架构

### 3.1 ADR 摘要（均为提议）

| ADR | 推荐决策 | 理由与代价 |
|---|---|---|
| ADR-01 | 用户赋予 skill 即授权固定 manifest；安装 / discovery / use_skill 本身不产生新授权 | 满足一次赋权，避免模型或共享技能内容替用户扩权；需界定现有 Desktop 启用操作 |
| ADR-02 | 复用 allowed-tools 作为声明，metadata 显式 opt-in SDK capability contract v1 | 避免再造一套自由文本依赖；旧 informational contract 不静默改变 |
| ADR-03 | 独立 SkillCapabilityStore + 会话级 SDK capability view，复用既有 resolver / executor | Parent LLM definitions、dispatch、search 均不受 grant-only 影响；增加独立授权状态而不是新工具平台 |
| ADR-04 | 不按静态扫描结果自动授予；required 精确依赖，组 / wildcard 展开成固定集合 | 动态调用只可从授权集合选择；作者需补声明，组展开需预览和有界 |
| ADR-05 | 授权到会话：有效能力 = 已赋予 skill 贡献的并集；不绑定每次脚本执行 | 固定 `/skill` 目录与同一沙箱信任域使按目录/进程归属不可靠；归属改为能力级贡献 |
| ADR-06 | 不新增 runner、不按命令路径归属：沿用现有 `run_bash_command` / `run_python_script`；SDK session() 走会话级 broker | 不改技能写法、不加 LLM 工具、不需要包内执行清单；代价是同一会话内任一脚本可使用任一已授权能力 |
| ADR-07 | UI 专用 SDK 事件 + 持久调用账本，不追加父 LLM conversation | 实时 / 回放同源、异步可终结；事件 writer 要支持明确 originTurnId |
| ADR-08 | Desktop 本地 broker 是 V1 完成条件，不只实现 Server sandbox | 当前本地 user-level CLI 不满足会话授权 / 审计；工程量主要在这条新链路 |
| ADR-09 | manifest / 资源 / 依赖目标版本固定；撤销 gate 立即阻止后续 admission | 不静默更新授权；在途外部副作用不能承诺回滚 |
| ADR-10 | V1 先闭合 MCP / API / LLM_CALL，其他能力按 adapter 显式扩展 | Agent / dataset 装配不同，避免等工具的“等”变成无限范围 |

### 3.2 分层

```text
User assigns skill / agent publication
  -> SkillManifestResolver (pure plan; no executing provider)
  -> SkillGrantPolicy (who may grant; owner + role/whitelist/enabled rules)
  -> SkillCapabilityStore (assignment → capability contributions)

Parent Agent
  -> Agent ToolRegistry -> LLM definitions / model dispatch (unchanged)
  -> use_skill -> SkillActivation (UI load anchor + capability snapshot)
  -> run_bash_command / run_python_script (既有脚本工具)
       -> core_ai_session.session()
            -> SessionSdkBroker
                 -> session capability view = agent tools ∪ skill contributions
                 -> verify capability + live grant + policy
                 -> existing resolver / ToolExecutor script path
                 -> SDK response only to script
                 -> CallLedger + Session events -> UI / history
```

`SDK capability view` 是会话级视图：只输出 Agent 现有工具与该会话已赋予 skill 的贡献，不输出别的会话或其他用户的能力。没有 skill 贡献时，脚本行为与现状一致（只能看到 Agent 工具）。

**归属依据是能力，不是路径或进程**：sandbox 内 `/skill/<name>` 固定且可读，任何脚本都能执行任意 skill 包里的文件；把“命令里出现哪个目录”当授权依据只是不可靠的启发式。因此能力按会话授权，展示按“能力由哪些 skill 贡献”归属。

相同物理 tool 可以服务 model 与 skill 两种授权来源；执行对象可以复用，授权记录、调用来源和视图必须分开。任何场景都不修改共享实例的 `exposure / discoverable / llmVisible`。

## 4. Skill 能力声明与解析

### 4.1 单一声明源与显式 opt-in

复用 `SKILL.md` 的 typed `allowed-tools`。新增字符串 metadata 标记；版本只接受已支持值：

```yaml
---
name: sales-audit
description: Audit leads with the bundled script
allowed-tools:
  - api-tool:crm/leads/list
  - mcp:analytics/run_query
  - llm-call:lead-summary
metadata:
  version: "1.0.0"
  coreai-sdk-capabilities-version: "1"
---
```

`allowed-tools` 是该 capability contract 的声明上界，不是可执行记录。没有 opt-in 的旧 skill 保留 informational / quality-gate 语义，不产生新 SDK grants。

**不新增包内执行清单，也不按命令路径判定归属。** 现有 `SKILL.md` 已经是执行说明，模型本来就用 `run_bash_command` / `run_python_script` 按说明跑脚本；再加 `skill.runtime.json` 只会制造第二份入口事实源和升级负担。路径归属同样不可靠：

- sandbox 的 `/skill/<name>` 是固定路径，`read_skill_resource` 和终端都能看到，同一沙箱的脚本可以读取任何已物化 skill 包；因此“命令里出现了哪个目录”不能证明调用来源，也不构成权限边界。
- 平台不为每次脚本执行创建 skill 绑定，也不按“最近加载的 skill”猜归属。
- `use_skill` 只做两件事：返回正文/资源与 `base_dir`，并附上该 skill 贡献的 SDK 能力摘要（不含 schema、不调用 `activate_tools`）。
- 脚本直接用现有方式运行；SDK 授权按会话能力集合生效（见 §6.2）。

这没有新增工具、没有新文件格式、没有存量技能改写；代价是同一会话内任一脚本可以使用任一已授权能力，且 UI 只能按能力贡献归属，不能声称“这次调用来自某个脚本进程”。

### 4.2 规范化 manifest（派生数据，不是第二事实源）

拟新增共享 core 类型与 parser：`SkillCapabilityManifest`。Server 发布、Desktop 本地读取、下载与预览使用同一语义。将现有 quality parser 的通用 grammar 下移 / 提取到 core；Server gate 保留检测与风险规则，不能让 core 依赖 server。

Manifest 至少含：`contractVersion, skillIdentity, qualifiedName, contentDigest, entries[], detectedUsages[], validationErrors[]`。

每个 entry 是 `declaredKind + selector + resolutionStatus`；解析为运行能力时生成 `ResolvedSkillCapability`：`capabilityId, kind, canonicalRef, displayPath, targetConfigHash, schemaHash, riskClass, executionAdapter`。canonicalRef 是平台内部稳定引用；显示名称改名不应错误绑定别的资源。

- 精确 API operation / MCP tool / LLM_CALL 为默认；不因只检测到一次使用就缩小或扩大作者声明。
- API app / MCP server / builtin group 的组声明可以在预览时展开，**将展开后的具体集合固化**。新加入组内的工具不自动进入旧 grant。
- 全 namespace wildcard（如 mcp:*）V1 不自动授权：需要作者收窄为明确组 / 引用；group 展开上限建议 200 项，超过返回可操作错误，不静默截断。
- 静态 analyzer 发现未声明使用 / 动态无法确认的依赖用于作者校验。动态选择只要在已授权的固定集合内，运行时可以调用；scanner 未检测到不等于禁止，也不是安全保证。
- 重名、未知资源、规范化脚本名冲突、多个候选、missing required adapter 均返回明确错误，不能 first-wins。
- 内置文件 / shell / Python 是 runtime-native，不作为 SDK Hub grants；平台文件发布等 capability 若有 adapter 则单独支持。`subagent / dataset` V1 为不支持 required adapter，不沿用现有 gate 忽略 dataset 的行为。

### 4.3 预览 API 与摘要

拟新增 `SkillAssignmentPlan` 的 preview / commit 两阶段内部协议。preview 不启动 MCP、sandbox 或脚本，只解析 metadata 与目录状态；若 MCP schema 尚不可用，显示 `pending_resolution`，不能假称已验证可执行。

Plan 包括 skill digest、资源的稳定 IDs / 配置 hash、具体依赖、可授予结果、warning、planDigest 与过期时间。Server“添加”或Desktop新增“启用SDK能力”点击提交这个planDigest，就是一次skill-level授权动作，不增加逐工具确认。

- Server：扩展 loadSkills / Agent 配置保存入口承载 planDigest；增加 owner-checked preview 能力，比较会话的原限制继续保留。
- Desktop：新增 app-server `skill/assignmentPlan` / `skill/assign`；由 Main / Renderer 的可信 UI 交互发起，不能让 LLM 调用这两个控制面方法。
- 摘要内不返回凭据或 Provider endpoint。每项显示资源名、类别和是否有写入 / 高成本能力。
- 计划过期或内容 / 依赖范围变化，提交返回 `SKILL_PLAN_CHANGED`，UI 刷新依赖摘要；不在同一个旧点击里接受新依赖集合。

## 5. 赋予 skill 即授权：边界与生命周期

### 5.1 四个动作不是同一件事

| 动作 | 是否产生 grant | 语义 |
|---|---|---|
| 下载、安装、被扫描发现 | 否 | 仅保存文件 / 发现描述 |
| 用户在 Agent 配置选 skill 并保存 / 发布 | 是 | 对当时的 manifest 授权，写入 Agent skill assignment snapshot |
| 用户在会话添加 skill；Desktop 显式启用 skill | 是 | 原有 skill-level 操作提交固定 grant；Desktop 需显示依赖摘要 |
| 模型 use_skill / 读资源 | 否 | 从已赋予集合激活一个加载实例，不获得任何额外权限 |

Desktop 当前 user/workspace skill 自动发现不等于存在历史 grant。迁移后内容型旧 skill 可以加载；opt-in skill 没有 assignment 时 `use_skill` 返回“需要启用此 skill 的 SDK 能力”，通过 UI / CLI 的 skill-level 确认完成。Headless 无授权快照时 fail closed，不在后台自动接受。

**Desktop 新增入口，不假称已有通用启用开关**：现有本地skill自动扫描只有发现/安装信息。本设计在Skills面板增加“为当前会话启用SDK能力”和依赖预览；V1默认session scope。可选“记住此workspace赋权”作为assignment scope扩展，user全局自动赋权不作为V1默认。

自然语言请求未赋权opt-in skill：use_skill返回失败SKILL_ASSIGNMENT_REQUIRED与typed assignmentRequired摘要，不注入SKILL.md正文、不创建activation。Renderer根据该结构显示“启用此skill”按钮，点击通过可信skill/assignmentPlan→skill/assign控制面完成一次skill-level授权；按钮不执行脚本、不自动继续旧toolcall，用户再发消息/点击“继续”开始下一轮，随后use_skill才能加载正文和创建activation。不复用普通tool审批事件，不允许模型/API替用户点击。CLI提供一次skill-level确认；headless返回可操作错误。已有assignment加载不再确认，不弹逐tool窗口。

### 5.2 授权策略：不强迫用户另配工具，也不越权

`SkillGrantPolicy` 分开两个问题：

1. **谁有权赋予这个 skill？** Server session owner / Agent 配置的授权编辑者；Desktop 可信用户交互与指定 workspace scope。
2. **这个主体能否把这些资源授给执行？** 不是仅校验 skill 存在，而是采用下面明确的 V1 规则。

**现状限制**：Server 内部用户的 MCP/API 目前只有 action-level 角色权限 + enabled 检查；API 用户另有 PermissionService 的 mcp-server / api-app 白名单。不存在完整 per-tool 用户 ACL / 组织 deny 表。现有 Agent 保存也不能作为 MCP/API 授权已校验的证明。依据：`core-ai-server/src/main/java/ai/core/server/mcphub/McpHubAccessPolicy.java:24`、`core-ai-server/src/main/java/ai/core/server/apitoolhub/ApiToolHubAccessPolicy.java:22`。

V1 不新建一整套组织 ACL，规则如下：

| 主体 / 能力 | Assignment 校验 |
|---|---|
| 内部用户授予 MCP | 当前主体具备 mcp.call；具体 server enabled、目标 tool 存在；只固化声明展开集合 |
| 内部用户授予 API | 当前主体具备 apitool.call；目标 app / operation enabled且存在；保留后端 caller data-scoping |
| API 用户授予 MCP/API | 同时满足现有 action 权限与 PermissionService server/app 白名单 |
| LLM_CALL | 复用 AgentDependencyAccessPolicy 的 published/owned executable 校验及对应运行权限 |
| Agent 发布者赋予 skill | 保存 / 发布时按上述规则生成 provenance snapshot；不是沿用未经校验的旧 ToolRef mounts |
| Published Agent 的聊天调用者 | 使用发布者固定、可委托的 assignment，校验调用者有权运行该 Agent；不要求聊天者再拥有 publisher 的 mcp.call / apitool.call；不能扩大依赖 |

动态会话添加 skill / Desktop remote assignment按当前赋予者校验；缺少角色权限返回 blocked，用户无需给 Agent 逐工具配置，但平台不能凭 chat.use 为其扩大账号权限。旧 published skillIds只有存在性校验，不自动升级为新的 capability provenance，需合法发布者重新保存 / 发布。

资源被 disabled、现有账号授权被撤销或版本变化，数据面检查失效。若产品要求内部用户 per-resource / 组织级 deny，需要另行将 ACL 表与策略列为前置范围，不能在本文假称已经有该能力。

本地 remote capability 同样由 Server grant 控制面执行上述规则；新增普通用户认证的 skill-grant 控制面，以及只接受有限数据面凭证的 `/api/skill-execution/*` 数据面，不让 session SDK退回通用用户 Hub，不把 Provider secret 发到 Desktop 脚本。

Skill-level grant 省去重复的 per-tool permissive prompt，不等于批准额外业务必要的发送 / 支付确认或二次登录。**V1 不新增后台异步审批队列**：已知必需交互的 capability 在 preview 标记不支持；执行中出现硬性审批 / 登录挑战，返回明确 ACTION_REQUIRED，当前调用失败且无外部执行，提示完成业务授权后重新启动该 skill。不得自动 approve，也不占住脚本执行等待下一条用户消息。独立异步审批队列留后续设计。

### 5.3 授权数据与事务

拟新增类型：

| 类型 | 核心字段 | 作用域 |
|---|---|---|
| SkillAssignment | assignmentId, skillId / local source identity, qualifiedName, contentDigest, manifestDigest, scope, grantedBy, grantedAt, dependencies[], serverIdentity?, remoteAssignmentId?, accountIdentity?, epoch, state | Agent published snapshot 或 Desktop enable 配置；workspace记住授权时保留远端归属 |
| SkillSessionGrant | grantId, assignmentId, sessionId / runId, ownerId, skillDigest, capabilities[], remoteGrantId?, epoch, state | 当前 session / run |
| SkillCapabilityContribution | capabilityId, skillId, assignmentId, kind, displayPath, targetConfigHash | 每个 assignment 的贡献项；会话并集与 UI 归属都由它派生 |
| SkillActivation | activationId, loadCallId, originTurnId?, capabilityIds[]（展示快照）, state | 每次 use_skill 的 UI 加载节点；不构成执行绑定 |

状态分开：assignment `active / stale / revoked / blocked`；grant `active / revoked / suspended`；activation `loaded / unavailable`。不能以 `use_skill` 成功等价于 SDK 可执行。

- Server 用现有 state/store 抽象，PG / Mongo 双实现必须同时支持；DTO/entity 使用具体 bean、枚举镜像和包装类型，禁止 Map<String,Object>；若 schema 迁移有版本管理，修改时同步 bump。
- Desktop assignment 放已有管理配置的独立 typed section / file，session grants 放 FileSessionPersistence 对应 session state；transcript 是展示投影，不作为授权源。
- 写 assignment / grant snapshot 和 loadedSkillIds 使用同一事务或 CAS 提交语义。多 skill 的一次 commit 默认整体成功 / 整体失败；单项失败不留下半授权 session。
- 本地 / Server 两个 authority 无分布式事务：远端 grant 先 prepare 为不可执行 lease，本地 CAS commit 后 activate，失败 / 超时自动回收；远端不因 prepare 可被调用。需要 reconcile 中间状态，但绝不以可执行半提交状态“成功”。
- runtime tool handle 是可重建缓存；授权事实是稳定 refs、版本、epoch，不持久化 Java ToolCall。

### 5.4 更新、重建和撤销

**V1 选择“内容变更即 stale”，不建设历史 Skill / LLM_CALL 双版本存储。** 当前 Skill 发布覆盖内容并删除被替换的旧 blob（`core-ai-server/src/main/java/ai/core/server/skill/SkillPublishService.java:50`），AgentPublishedConfig也没有可寻址 published revision 表（`core-ai-server/src/main/java/ai/core/server/domain/AgentPublishedConfig.java:11`）。不能假设旧版本能长期热执行。

- Assignment固定包digest；LLM_CALL固定所选执行配置的canonical hash（包括prompt/model/schema/影响权限的配置），MCP/API固定stableRef+schemaHash+连接配置identity。不把新 hash 字段说成已有 revision。
- 每次 admission 比较当前目标 identity 与授权快照；变化即 stale，禁新调用，提示用户更新/重新赋予 skill。already-admitted调用使用当次捕获配置完成，不能在执行中重读最新prompt替换。
- use_skill 首次加载时原子物化只读包到 content-addressed 目录，以保护校验/执行的TOCTOU；物化包可维持本次加载，不等于仍允许该旧assignment新建调用。结束后按现有生命周期清理，不承诺全局版本仓库。
- 恢复只从持久grant snapshot核对当前skill/目标hash与授权来源；auth_required为suspended、变更为stale、缺失为blocked，不能从最新内容重算grants或以全用户CLI补跑。
- schemaHash只能检测schema/配置变化，不能证明第三方MCP/API行为版本永远相同；provider远端行为升级不在平台hash可保证范围。
- 撤销时先持久增加 epoch，broker admission 在短 gate 内读取 epoch + state 并登记调用，之后才外发。普通更新等安全点；显式 revoke 立即禁止新 admission / poll / publish / 新子任务，并 best-effort cancel 已启动调用。
- 一个调用已越过 admission 的外部副作用不承诺回滚；资源 provider 的撤销 token、取消和 deadline 尽量缩短窗口。跨 pod 的检查需要 owner 路由 + durable epoch，不只依赖 GUI 状态或长缓存。
- 两个 skill 共享依赖时各自授权独立；卸载 A 只撤销 A 的 grant，不能卸掉 B 或用户独立配置的 model tool。共享连接依资源 handle 引用计数管理，不以 capability 名删除整个 provider。
- 撤销是控制面事实，撤销事件即使落盘 UI 失败也不能恢复执行权限。历史仍保留当时名称、摘要与状态，不靠当前 skill 文件才能读。

## 6. 会话级能力视图与 SDK 调用

### 6.1 脚本仍用现有 bash/python 运行

skill 脚本本来就是这样跑起来的：模型 `use_skill` 读到说明，再调用 `run_bash_command` / `run_python_script`（sandbox 内由 runtime 拦截）。V1 不改变这条路径：

- `use_skill` 只补充 `activation_id`、该 skill 贡献的能力摘要和原有 hub 提示；不注入依赖 schema，不调用 `activate_tools`。
- 不创建执行绑定，不匹配命令中的目录，不注入每次执行的 skill 凭证。
- SDK `session()` 接口不变；只要 `CORE_AI_HUB` 指向会话 broker，就走 HTTP transport（sandbox 本来如此，Desktop 见 §6.3）。

这样没有新工具、没有包内清单、没有存量脚本改写；代价是平台不声称“这次调用来自哪个脚本进程”。

### 6.2 能力按会话授权、按能力归属

**有效能力集合 = Agent 当前脚本可见工具 ∪ 已赋予 skill 的贡献**，按 capabilityId 去重：

| 来源 | 是否可被模型调用 | 是否可被脚本调用 | UI 归属 |
|---|---|---|---|
| Agent 工具（现有） | 取决于原有 exposure | 是（现状） | 无 skill chip |
| 仅 skill 贡献 | 否 | 是 | 展示 contributors |
| 两者都有 | 取决于原有 exposure | 是 | 展示 contributors，并标注“同时也是 Agent 工具” |

- 调用授权只检查：capabilityId 在有效集合内、对应 assignment 未 stale/revoked、当前 epoch 有效、资源 enabled / 角色与白名单仍通过。脚本自报的 skill 名、路径、header 一律不作为授权或归属依据。
- contributors 由 assignment 的贡献项计算：一个能力被多个 skill 声明时列出全部；卸载一个 skill 只移除它的贡献，能力仍被其他 skill 或 Agent 工具保留时继续可用。
- 授权范围是**会话**：同一 sandbox 内任一脚本都能调用任一已授权能力。`/skill` 目录固定且可读，同一沙箱就是一个信任域，能力集合按会话计算。
- grant-only 能力不进入 `ToolRegistry` 的 definitions、dispatchMap 或 `activate_tools` 的发现源；模型即使在文本里编造工具名也无法通过自己的工具面执行。

### 6.3 Desktop 本地 SessionSdkBroker

新增 Engine 持有的 broker，不使用 Electron renderer 或 SDK 子进程作权威裁决：

```text
run_bash_command / run_python_script -> Engine 本地进程（既有工具）
  -> session 级 CORE_AI_HUB
    -> SDK HTTP Session
      -> Local SessionSdkBroker
        ├─ local MCP adapter -> configured local MCP manager
        └─ remote API/MCP/LLM_CALL -> bounded remote grant lease
               -> Server /api/skill-execution/* -> existing tool adapters
```

- EngineSessionRegistry 创建每会话 broker state 并装配 event sink；Engine 关闭 / session 释放时关闭 listener。存在 skill 贡献的会话，Engine 在启动 bash/python 子进程时注入 `CORE_AI_HUB`（同一会话所有子进程同值，不按 skill 区分）；没有贡献则保持现状。用户自己的终端不继承该变量，仍走用户级 CLI。
- broker 按有效能力集合解析调用，不解析脚本自报的 skill；catalog 同时包含 Agent 现有工具与 skill 贡献，供脚本发现。
- 本地 MCP 与 remote MCP 的来源稳定 ID 必须明确，重名不静默选本地或远端。只解析已授予 refs，不从全账号 catalog 挑工具。
- Server 在 grant 控制面按登录主体做身份 / 可授予校验，签发 finite remote grant；call/poll 只接受这条有限数据面凭证，不接受脚本的普通 user key 或自报 session。
- Engine 在内存持有 remote 凭证，通过 HTTPS 调用，不输出脚本环境；本地 broker 按同一 capabilityId 映射。普通 CLI Hub 保持现有行为。
- 只要注入了会话 hub，SDK 一定走 HTTP transport。broker 不可达、grant 失效或版本不兼容明确失败，**绝不回落 CliSession 以全用户身份执行**；AsyncSession、刷新 catalog、namespaces、Task 和 errors 保持同契约。
- CORE_AI_HUB 不存在时，用户独立启动的普通 SDK 仍走现有 CliSession，不自动关联最近 Desktop 会话，也不宣称有 skill 级审计。
- 远端不可用但本地 MCP 可用的 skill，只有全部 required 依赖满足才标 SDK-ready；V1 无 optional 声明，不用半成功状态假称完成赋权。

**重启与remote lease恢复**：仅持久化remoteGrantId、Server identity、owner/account identity和manifestDigest，不持久化短期数据面凭证。恢复时以当前同一Server的登录身份向控制面renew；Server再次校验owner、贡献快照、角色/资源授权，签发新短lease但不增加capability集合，不要求重复用户skill确认。未登录/不同账号/过期grant返回suspended(auth_required)或blocked；用户重新登录后renew，不回落CLI。不恢复旧进程为“仍运行”，CallLedger按reconciler标unknown/interrupted。

**Desktop V1 provider边界**：支持localMCP与Server application-hosted/remoteMCP；SANDBOX_HOSTED远端MCP在Desktop无Server sandbox承载，preview明确不支持并阻断该skill assignment。Server sandbox场景仍支持它。将来若Desktop要创建专用远端sandbox是独立范围，不在本方案里隐式创建。

**跨端MCP寻址**：声明mcp:serverName/toolName仅为显示选择器，alias精确大小写/分隔规则经共享parser确定，resolver保留原始ID；它不是内部ref直接按冒号split。Server输出结构化{serverId, toolName}并映射既有ToolRef格式；local输出{workspaceSourceId, configuredServerId, toolName}。plan记录executionLocation(local/server)；跨端同名要有显式workspace alias映射，否则报AMBIGUOUS_CAPABILITY。禁止first-wins、下划线归一或fallback自动改目标。API相同地解析三段选择器后保留结构化稳定app/service/operation，不拿显示串充授权ID。

### 6.4 Server sandbox路径

扩展现有 hubproxy，不新增通用 user hub 访问：

1. Session/Run skill assignment 完成，broker 保存贡献快照；sandbox 准备可以延迟到首次脚本执行。
2. use_skill 先比较当前 skill contentDigest 与 assignment；赋权后、首次加载前若已更新/旧 blob 不可用，直接 stale 并 fail closed。通过后才原子物化已核对包到 content-addressed 路径，读取正文并创建 activation；不依赖延迟准备还能取回旧 blob。
3. 模型按技能说明调用 bash/python；runtime 照旧为该进程注入会话级 `CORE_AI_HUB`。平台不识别目录、不创建 execution、不注入 skill 凭证。
4. runtime 按现有 session token 代理到 owner pod；`PodLocalExecutor / SandboxHubCommands` 仍按 sessionId 路由，不需要透传 skill/execution identity——归属由调用目标能力反推。
5. catalog/describe/call 从合并视图解析：Agent 现有脚本可见工具（只读 dispatch map）+ skill 贡献条目。skill-only 条目 callable=true，但不存在于 `ToolRegistry`，模型看不到也搜不到。
6. resolver 使用 session-scoped MCP 管理器，SANDBOX_HOSTED provider 必须准备完成再调，不回落 global manager 冒用别的会话。

namespace 路径建议 `/skill/<stableIdentity>/<digest>`，避免当前 `/skill/{name}` 跨 namespace 同名覆盖和更新覆盖；这是不可变性与正确性措施，不是保密措施——同一 sandbox 的脚本仍可读取任意已物化包。

共享 MCP 连接的所有权为 (session/runId, serverStableId, configHash, executionLocation)。同配置可复用一个 session-local handle，由 model mount 与各 skill 贡献分别持有引用；caller 身份每 call 注入，不能绑定成第一个 skill 的身份。卸载只减少该贡献的引用，最后一个引用释放才停进程。相同 serverID 不同 configHash 拒绝混用，V1 不在同一 session 悄悄重启替换 model 的 MCP。SANDBOX_HOSTED 进程能力/文件环境属于整个 sandbox，broker 只限制调用面。

### 6.5 统一 SDK 调用执行与异步

拟增加 SDK 调用包装层，复用已有 ToolExecutor 的 parse/validation、caller 注入、trace、timeout、LLM usage 和 AsyncTaskManager；不复用 model beforeTool/afterTool。额外实现：

- admission policy：capabilityId 在会话有效集合、assignment 未 stale/revoked、epoch 有效、targetConfigHash 未变、deadline/quota、角色/白名单/enabled 与交互/登录挑战；V1 交互挑战返回 ACTION_REQUIRED。
- per-call immutable context：不能跨并行调用改共享 ExecutionContext 的 caller、budget 或 `currentToolCallId`。SDK 调用不携带也不推导 skill 执行身份。
- SDK call ledger 登记 requestId/callId；短 gate 内校验并登记，释放后执行外部调用，避免持锁等网络。
- 在途总数、per-call 并发、结果大小、deadline 按 session 预算有界；LLM_CALL tokens 进入 session ledger 一次，不在 SDK 事件和脚本结果两次计费。
- V1 需要硬性用户交互的调用返回 ACTION_REQUIRED 并失败，不使用当前 model pendingUserInput 队列，不后台阻塞等用户；UI 给出完成授权/重启 skill 指引。

**显式 ScriptCallContext 契约**：拟增 `ExecutionContext.forScriptCall(sessionServices, admittedCall)`，创建新 context，快照 caller、model/目标配置、附加输入与只读 customVariables；不共享原 HashMap。只通过明确 sessionServices 引用共享线程安全预算账本、AsyncTaskManager、audit/eventSink。取消 token 独立，链接 session 释放 / 能力撤销 / 调用 deadline，而非模型下一轮可替换的 turn token。跨 supplyAsync 通过显式参数 / OTel context 传播。

LLM usage 归一兼容 ToolCallResult.llmUsage 与已有 LLM_CALL stats 的 llm_call_input_tokens/output_tokens 形状，统一转 ledger usage，以 callId+usageVersion 去重。接口消费 side-effect 只有一个，不让 core consumer 与 broker 第二次写同一份 session cost。

**异步状态机**：`accepted -> running | pending -> completed | failed | cancelled | interrupted/unknown`。HTTP wait 超时只返回 pending，不能写 completed 事件；工具底层 async 与 Hub future 双层 task 映射到同一个 callId。waiting_input 仅作为未来 contract 扩展保留，不在 V1 承诺支持。

CallLedger 必须包含 `callId, requestId, ownerScope, capabilityId, contributorSkillIds, grantEpoch, originTurnId, taskId, acceptedAt, terminalAt, status, duration, resultRef, usage`。task poll 先验证 owner / capability 仍在有效集合 / task lease，不能仅用全局 Map taskId 命中即返回结果。AgentRun 没有 InProcessSession 也必须有 run-scoped event sink / ledger；不能因为没有会话对象丢事件。

事件终态只写一次，poll 不新增重复调用行；取消/重启无法确定外部结果时标 interrupted/unknown，不自动重放有副作用调用。requestId 支持去重但不承诺跨外部 API exactly-once；unknown 状态先核查，不盲目重试。

拟新增 CallReconciler：owner/runtime 绑定带持久 ownerEpoch 与 heartbeat/deadline；恢复时扫描 accepted/running/pending。底层 provider 有持久 task 且可安全 owner-scoped poll 则继续查询，否则 owner lease 过期 + fencing 后 CAS 为 interrupted/unknown 并写终态待投递，不因为另一 pod 抢到 session 就重执行 call。周期与 timeout 按现有 lease/config 定义，不做无限 running。

**session / stateless run / subagent 统一口径**：会话内的 subagent 共享该会话的有效能力集合（同一 sandbox、同一信任域）；独立 AgentRunBuilder 从目标 Agent 自身已校验的 publishedSkillAssignments 派生 runId-scoped 能力集合，**不继承父会话或父 Agent 的贡献**。目标配置缺新 assignment provenance 则返回 SDK_UNAUTHORIZED_LEGACY，内容型 skill 仍可读；覆盖这些装配入口是 V1 必选测试。V1 不支持通过 SDK 新委托 agent 能力，也不把子 agent 专属 skill 的依赖自动并入父会话；需要时另行设计。

## 7. 事件、持久化与 UI

### 7.1 专用执行事件，不混入父模型对话

建议新增typed事件（而非SDK自发CUSTOM），统一进入平台AgentEventListener/event sink与历史投影：

| 事件 | 作用 |
|---|---|
| skill_activation | 加载锚点及该 skill 的贡献快照；绑定 use_skill callId |
| sdk_call_start/update/result | 实际调用及 pending/终态；带能力贡献者，V1 交互挑战以 ACTION_REQUIRED 失败记录 |
| skill_capability_changed | revoked/stale/blocked 或贡献变化；实时投影，不作为授权事实源 |

SDK-only 字段独立于 ToolStartEvent，避免旧 CLI / lifecycle 误认为 model 发起工具。示例 wire envelope（新增提案；所有新 Java 事件字段用 @Property 显式 snake_case，Engine 与 Server 一致；旧事件保留旧命名，adapter 有契约测试）：

```json
{
  "type": "sdk_call_start",
  "event_id": "evt_03",
  "session_id": "s_01",
  "origin_turn_id": "t12",
  "call_id": "hc_01",
  "capability_id": "cap_01",
  "contributor_skill_ids": ["skill_sales_audit"],
  "tool_kind": "api",
  "tool_path": "crm/leads/list",
  "origin": "skill_sdk",
  "status": "running",
  "arguments_preview": "{...redacted...}"
}
```

所有字段由 broker 生成。**没有 script call / execution / parent call 字段**：平台不声称知道哪个脚本进程发起调用；`contributor_skill_ids` 来自能力授权关系。模型的 task_id 仍只代表任务，不挪用。`call_id` 是真实调用标识，poll / provider task id 不能替换它。

### 7.2 持久化与晚到事件

- Desktop：TranscriptKinds/EventWriter/Recorder 增加 typed records；记录包含明确 originTurnId，writer 不可用“当前 turn”盖掉已结束 turn 的 SDK 调用。SDK-only records 从不进入 agent.getMessages()。
- Server：CallLedger 与 skill activation 存储为独立会话/run 历史。当前 ChatMessage 无 turnId，新增 nullable turnId 到 ChatMessage、Message/HistoryMessage 及 store 投影；SessionEventListenerWiring 在 turn-start 捕获稳定 turnId（不得等 turn complete 才生成）。SkillActivation 保存 parentCallId=use_skill 的 callId，仅用于把“能力清单”挂到加载节点；**SDK 调用不挂 parent call**，历史按 capabilityId + contributors 归组，找不到 contributors 时显示独立“Skill SDK”条目。不得插入假的 assistant/tool messages。
- SDK 历史使用独立 eventSeq/cursor，不能与 ChatMessage.seq 等数值比较；shared originTurnId 仅用于时间线排序。Desktop 使用现有 transcript seq；Server history response 增 sdk_activities + sdk_cursor，live/history snapshot 用 cursor 去重。
- 持久化 SDK 事件提供 schemaVersion、stable eventId、occurredAt、originTurnId、eventSeq。PG 的 ledger 更新 + outbox 同行事务；Mongo 将 pending event 与 delivery version 内嵌 call 文档，用单文档 CAS 更新状态/终态/version；worker 按 delivery version 幂等发送并 CAS 确认。Desktop 先追加 call log/typed record 再发通知，崩溃后按 eventId reconcile；投递至少一次，UI 去重。
- 大参数/结果复用 blob/resultRef 机制，UI 只拉授权可见字段；token/secret/敏感个人数据按现有审计政策脱敏。失败异常也不能泄露 remote credential。
- 当前 turn complete 不封死旧 turn 的 SDK 事件；异步事件可以晚到并更新对应能力条目。分页没带父节点时显示独立“Skill SDK · 能力名”fallback；跨轮显示引用节点，不复制或重复计数完整调用。
- SDK_call_result 先于 start、重复投递、断线重放时按 callId upsert；replay 先构建 activation/call 索引再渲染，不依赖到达顺序。
- Undo/rewind 既不撤销已发生外部副作用，也不能抹掉执行审计；关联模型 turn 可以在可见对话中折叠/标撤回，SDK 历史保留原归属。

### 7.3 Desktop渲染

改造 `transcript/reducer.ts` 为 normalized activation/call 索引，ToolItem 只引用这些节点，不把所有后续消息永久搬进一个可变 DOM 子树。`app.ts` 的 live 与 transcript 路径共用 projection renderer。

加载节点下展示**该 skill 贡献的 SDK 能力**：精确资源、授权状态、script-only 标签；可折叠，默认显示前几项 + 总数。调用列表独立展示：

1. **SDK 调用**：每条带能力贡献者 chip（一个或多个），按 acceptedAt/seq 排序；成功/失败/耗时/usage 独立。
2. 不用“脚本执行”容器，也不把调用嵌套在某个 `use_skill` 下；单个贡献者时 chip 链接到该 skill，多个时列出全部。

通用 Tool use 计数分 model 顶层工具与 SDK calls，标题可以显示“工具 3 · SDK 调用 5”，不能两次计算同一 call。

只有正文已成功加载后出现能力失效时，显示“说明已加载；SDK 不可用：原因”，不把 dependencies 标绿。未赋权 opt-in skill 是加载失败，按 §5.1 显示授权按钮而不是“已加载”；stale 发生在首次加载前也必须先拒绝正文与 activation。点击能力只看受控详情，不把工具添加到 Agent。

### 7.4 Web/CLI降级

- Server SseEventBridge 与 Frontend API types 增加新 typed 事件；ToolsBlock 保留现有 task children，SDK calls 按 capability + contributors 归组，不让同 taskId 误收编。
- Web 历史读取 merge 新增 SDK projection，reload 与 live 一致。没有新 renderer 的客户端不会收到假的 model tool records；可通过新增只读历史摘要接口查询。
- CLI 非 GUI 只输出技能加载的能力摘要和 SDK 调用列表（能力 + contributors + 状态），不要求同等富 UI；headless 可写结构化执行日志但不伪造 model conversation。
- 旧 SDK 支持标准 catalog/call/result 仍可走 HTTP transport；新增字段为加法，旧 SDK 不能理解 task lease 或 contract 时明确报版本错误，不偷偷转 user-level CLI。所有升级通过 contract fixtures 验证。

## 8. 实施切分、兼容与回滚

### 8.1 改动地图

| 层 | 已有落点 | 拟改内容 |
|---|---|---|
| core skill | SkillMetadata / SkillLoader / SkillTool | 共享manifest grammar、context-awareactivation、use_skill能力摘要；不新增工具/清单/绑定 |
| core execution | ToolExecutor / ExecutionContext / AgentEventListener | SDK调用包装、immutablecontext、admission与quota，typedSDK事件；不扩大modeldispatch |
| Server skill control | SkillPublishService / SessionSkillManager / SkillToolAssembler | opt-in校验、plan/commit、grant policy与贡献快照，覆盖session/run装配 |
| Server providers | ToolRefResolutionService / existing MCP/API/LLM_CALL adapters | grant-only catalog条目、固定目标配置hash、无副作用preview与执行时materialization |
| Server data/auth | domain/store / AuthInterceptor / SandboxHubService / PodLocalExecutor | 持久贡献/CallLedger、有限remotegrant入口、session级call与scope-checkedpoll、outbox |
| sandbox runtime | hubproxy.go / bind.go | 保持session级hub与token；不新增per-execution凭证；合并catalog由server完成 |
| SDK | session.py（含 AsyncSession）/ cli.py / tree.py / Task | 会话HTTPtransport、新增字段兼容、tasklease与无回退、错误映射 |
| Desktop Engine | EngineSessionRegistry / FileSessionPersistence / EventBridge | localSessionSdkBroker、assignment控制面、session级`CORE_AI_HUB`注入、typedrecord投递 |
| Desktop UI | transcript/reducer.ts / app.ts / styles.css | 能力预览、contributorchips、live/replay同projection、错误与分页fallback |
| Web UI | skill详情/配置 / Chat.tsx / ToolsBlock / historyAPI | 一次赋权贡献展示、SDK事件消费、历史merge、不复用taskId父关系 |

新增类型名与路由在实现前可以按代码规范调整，但边界必须保持：**控制面授权、会话级SDK调用面、模型工具面、展示历史四者不能混成一个ToolCall列表；归属是能力级，不是进程级。**

### 8.2 分期与退出条件

| 阶段 | 实施 | 验证 / 退出条件 |
|---|---|---|
| P0 契约与控制面 | manifest opt-in、共享parser、plan/commit、grantpolicy、持久snapshot、资源固定refs | 用户确认§10；旧skill无新增grant；preview不执行代码；CAS/权限/组展开/版本变化测试通过 |
| P1 Server闭环 | 贡献快照、合并catalog、grant-only调用、CallLedger与typedevents | 一个含API/MCP/LLM_CALL的skill无需另配工具即可跑；三项均不进父LLMdefinitions/dispatch/discovery；跨pod/poll/撤销验证 |
| P2 Desktop闭环 | 本地broker、有限remotegrant、会话hub注入、assignmentUI | SDK不走userCliSession；本地与remoteMCP正确区分；API/LLM_CALL成功并被当前会话审计；失联绝不回退 |
| P3 UI与历史质量 | Desktop/Web能力与调用展示、live/replay同源、outbox/reconcile、异步终态、错误脱敏 | 刷新/重开/分页/lateevent/重复/重启/undo结果一致；devE2E及实际Desktop流程通过 |
| P4 扩展，可选 | builtin/media/decision，再单独设计agent/datasetadapter | 每种adapter具备独立grant、quota、task、ACL与验收；不因genericnamespace已有就自动放行 |

P1可以用于Server内部验证，但**整个用户功能不能以Server成功、Desktop退化为普通CLI作为完成**。最小发布必须包括P0–P3中V1三类能力的端到端链路。无需先全量引入P4，也不要求迁移所有历史skill。

### 8.3 发布与兼容

- 新能力默认featuregate关闭；Server、Engine、runtime、SDK协商contract后才能开启新assignment。准备和版本协商必须先于授权成功展示。
- 数据库先加nullabletypedfields / tables，再发布能读写新contract的Server和Engine，最后启用UI；存在旧版本消费者时不发它不能安全处理的数据。
- 旧Agenttools/旧sandboxhub/独立CLI命令语义不变；旧allowed-tools不静默授权。需要赋权的skill按批迁移，保留作者原文与质量gate结果。
- 对旧SkillPublish质量设计“allowed-tools不是runtimegrant”的描述，实施时明确记新ADR与opt-in差异；不是改写成历史版本已经支持。
- 回滚关闭新assignment与calladmission，保留贡献/ledger/history。旧Engine只读可显示“需要新版运行”，不能把新skill重新作为全用户CLI脚本执行。
- 版本变更、Server发布与devE2E按现有CI构建镜像流程；UAT/prod必须另获用户明确授权。本次设计不触发这些操作。

### 8.4 开工前的最小协议冻结清单

用户确认§10后、P0/P2开始前冻结以下具体DTO/路由；命名可调整但必须有contract fixture，不在实现中随端各自发明：

| 协议面 | 最小请求/响应与约束 |
|---|---|
| assignment plan/commit | skillIdentity、scope、content/manifestDigest；响应planDigest、expiresAt、typeddependencies/status/error；commit同digest+owner/CAS，不允许body自报owner |
| remote控制面 | prepare/activate/renew/revoke；remoteAssignmentId与每session remoteGrantId区分；renew只用Server当前登录主体，返回contractVersion、expiresAt、有限集合/状态，不扩大旧grant |
| remote数据面 | catalog、tools/:capabilityId/describe、tools/:capabilityId/call、tasks/:taskId/poll；已验证session确定ownerScope；call含requestId、argumentsJson、timeout，返回callId/status/taskLease/resultRef/usage/error；JSON参数用String，不用DTO Map<String,Object> |
| assignment_required | use_skill失败result的typedUI字段assignmentRequired{skillIdentity, requestedScope, errorCode, previewAvailable}；不包含秘密、不自动执行，只有可信UI按钮调用控制面 |
| Server history | ChatMessage/HistoryMessage新增nullableturnId；sdk_activities为typed列表（activation/call/seq/ref），sdk_cursor独立分页；调用按capabilityId+contributors归组；对应PG/Mongo migration版本与read/write兼容 |
| MCP alias | workspace显式alias→{executionLocation, serverIdentity?, serverId, workspaceSourceId?}；preview先确定唯一结构化capabilityId，不能从显示name猜或冒号split |
| 版本协商 | Engine/Server控制面与SDK /me均返回skill_sdk_contract_version=1；unknownrequiredcontract拒绝prepare，不能在执行失败后降级userHub |
| capability贡献 | 每项capabilityId{kind,ref,targetConfigHash,displayPath}与assignment/skill关联；会话视图=Agent工具+贡献并集；响应含contributors，不含执行/进程绑定 |

所有外部DTO采用具体typedbean，nullableenum使用entity/view镜像；arguments/data异构部分用JSONString；历史大结果使用ref+受限拉取。具体leaseTTL、并发额度和轮询退避从现有sessionbudget/config统一来源定义，不能各SDK硬编码出不同上限。

## 9. 验证计划与验收标准

### 9.1 不变量（必须在测试中直接断言）

1. Skill assignment前后父LLMproviderrequest中的tools集合不因grant-only能力增加；父dispatchMap与activate_tools的可发现集合也不增加。
2. 会话SDKcatalog恰好为“Agent脚本可见工具∪该会话已赋予skill贡献”；不包含其他会话/其他用户的能力，也不因某次脚本执行改变。
3. `use_skill`不能产生新grant；没有用户assignment/固定digest，不可调用grant-only能力。
4. 用户独立赋予的modeltool不因skill贡献/unload改变exposure；共享ToolCall没有跨sessionmutation。
5. 每次SDK调用的capabilityId都能在有效集合中解析；contributors快照来自能力授权关系；不声称脚本进程归属。
6. 每call最终usage只计一次；SDKobserver/脚本结果/asyncpoll不可重复计费、重复终态或自动重放写操作。
7. revoke/版本stale/会话释放后禁止新admission；poll/result读取遵循owner、capability有效性与tasklease；审计不因UIrewind消失。

### 9.2 单元 / 契约 / 故障测试

| 类别 | 关键用例 | 已有测试基础 / 新增测试 |
|---|---|---|
| manifest | 精确/组/动态/无opt-in/未知contract/入口逃逸/digest一致；scanner不是grant源 | SkillLoaderTest、SkillPermissionAnalyzerTest、SkillPublishServiceTest；新增coremanifesttests |
| 授权 | 非owner、仅skill存在、不可授予依赖、policydeny、CAS变化、prepare不执行、整体失败回收 | SessionSkillManagerTest；新增SkillAssignment/GrantPolicy/remotelease测试 |
| 模型工具面 | definitions/dispatch/discovery均无grant-only；幻觉函数名拒绝；独立modeltool仍可见 | ToolRegistryTest、ToolActivationTool相关测试、Agentproviderrequest捕获测试 |
| catalog/SDK | 三种能力均可调用；共享能力跨skill保留；SDK刷新拿固定版本；AsyncSession/Task/旧SDK兼容；不回退CLI | SandboxHubCatalogTest/ServiceTest；PythonSDKtests与contract-fixtures |
| runtime | session token与hub保持现状；伪造身份仍不能越权；同名包不覆盖；不新增执行凭证 | Go hub_test.go / hub_contract_test.go；新增catalog合并测试 |
| Desktop | Engine重启、跨workspace/session借用、local/remote同名、有限remotegrant、真实session/event与record | EventBridgeTest、AppServerEngineTest；新增LocalSessionSdkBrokerTest |
| 异步/配额 | wait超时pending、async终态晚到、taskId盗用、重启unknown、去重、额度超限、两层task映射 | SandboxHubServiceTest、AsyncTaskManager/usage测试；新增CallLedger状态机测试 |
| 事件历史 | result-before-start、重复投递、分页缺失、跨轮lateevent、卸载后回放、undo、脱敏/大结果 | TranscriptRecorderTest/ReaderTest；ServerSDKhistorytests |
| UI | 两次同skill加载、两个skill共享能力、未调用能力、live/replay同树、contributors展示 | ToolsBlock.test.tsx；Desktop增加reducer/projectiontests与GUI验收 |

Desktop当前缺少已覆盖此交互的自动化UI测试，不以“改app.ts后typecheck通过”代替可视化与流程验证。测试命令在实现时从当前build scripts核对，避免设计文件把未经运行命令写成验证成功。

### 9.3 V1 端到端场景

创建一个测试skill，精确声明一个只读API operation、一个只读MCP tool、一个LLM_CALL，入口脚本顺序调用并输出小报告：

1. 用户只添加skill，不另行给Agent加载三个工具；UI先展示三项贡献和同一个grant的版本。
2. 父LLM只通过use_skill获取说明，再用既有bash/python工具启动脚本；捕获providerrequest、materialization和activation，证明三项grant-only不进入模型。
3. 脚本成功调用三项；UI展示三行真实调用、贡献者chip及参数/结果引用，调用结果仅返回脚本。
4. 刷新/关闭重开会话，能力摘要和三行调用与live一致，不补造或丢失。
5. 加载第二个skill，两技能共享一个资源；任一脚本可调用共享能力，UI列出两个贡献者；卸载其中一个后共享能力仍可用。
6. 让一个调用超过HTTPwaitbudget，UI先pending后终态；结束turn之后还能更新原条目，不错误挂到新turn。
7. mid-run撤销、Provider失联、Engine/server重启、skill包变更、目标配置变更分别验证failclosed/unknown，不自动重试写调用、不以用户CLI身份fallback。
8. Server dev与Desktop本地都运行以上链路；验收记录明确用到的contract/镜像/Engine版本。没有用户批准前不开始实现或部署。

## 10. 待用户确认的关键决策

| 决策 | 推荐 | 需要确认的取舍 |
|---|---|---|
| D1 展示语义 | 加载下显示该 skill 贡献的能力；调用列表按能力贡献者归属，不断言脚本进程 | 是否接受“能力归属”而不是“嵌套到某次加载/某个脚本”？ |
| D2 赋权动作 | Server原有添加skill即授权；Desktop新增一次skill级启用控制面；安装/discovery/modeluse_skill不授予 | Desktop V1默认session scope；可选记住workspace；未赋权时显示可信UI启用按钮，不逐tool确认 |
| D3 授权范围 | 会话级并集：已赋予 skill 的能力对同一会话内任意脚本可用 | 确认会话是授权单位；归属按能力贡献展示，不按脚本进程 |
| D4 声明兼容 | allowed-tools + SDKcontractv1opt-in；精确/有限组，固化展开 | 不直接把所有旧allowed-tools或namespacewildcard变成runtime授权 |
| D5 覆盖范围 | V1闭合Desktop+Server的API/MCP/LLM_CALL | builtin/media按adapter后续；agent/dataset独立设计，不提前声称通用支持 |
| D6 模型边界 | grant-only不进父registry/dispatch/search；独立modeltool配置不受影响 | 这是更明确的工具面分离，避免仅隐藏初始definitions；需独立SDKview与grantstate |
| D7 版本与撤销 | 固定授权时的包/配置hash；更新即stale并需skill级重新赋予，revoke立即禁新调用 | V1不建历史版本库；当次已物化包保护进程，不据此恢复旧grant的新执行 |

R1–R4为用户原始目标，不作为“可选实现”改变。D1–D7是实现这些目标的建议，待用户确认；AIreview不能替代用户批准。

## 11. Review 记录

| 日期 | 模型 | 性质 | 结论 / 状态 |
|---|---|---|---|
| 2026-11-17 | gemini-4-hydrogen | 首轮AI技术评审；不代表用户批准 | 有条件通过：认可独立SDK授权/调用面；指出真实ACL口径、旧版保留、Server历史锚点、并发context、Desktop续租五项阻断前提 |
| 2026-11-17 | core-ai | 依据首轮意见修订 | 明确角色/白名单/发布者授权；V1更新即stale；增turnId+父callId和双seq域；具体ScriptCallContext；续租与SANDBOX_HOSTED边界；补run/subagent、Mongo单文档outbox/reconciler、ACTION_REQUIREDfailfast与迁移成本 |
| 2026-11-17 | gemini-4-hydrogen | 修订稿AI复核；不代表用户批准 | 架构级P0阻断已解除，可作为待用户决策的基线；建议清理组织/审批旧句、未赋权加载状态，并补协议冻结前置 |
| 2026-11-17 | 用户 | 设计反馈 | 质疑新增 `run_skill_script` 的必要性：脚本本来就用现有 bash/python 运行，不应为归属再造一个模型工具 |
| 2026-11-17 | 用户 | 设计反馈 | `/skill` 目录固定且对 Agent 可见，按路径判断归属不可靠；目录/进程不能当授权边界 |
| 2026-11-17 | core-ai | v0.4 修订 | 授权改为会话级能力并集；删除执行绑定/脚本归属；UI 按能力贡献者展示；同步修改ADR、事件、历史、测试与D3 |

## 12. 修改记录

| 版本 | 日期 | 修改 |
|---|---|---|
| v0.1 | 2026-11-17 | 保留原始需求，完成源码核查、ADR、manifest/一次赋权、独立SDKview、Desktop/Server链路、事件历史、生命周期与验证计划 |
| v0.2 | 2026-11-17 | 按首轮AI评审纠正授权与版本存储假设，补齐Desktop新启用入口/续租、明确provider边界、具体并发context、Server历史锚点、outbox/reconciler与run/subagent口径；V1交互挑战failfast |
| v0.3 | 2026-11-17 | 删除新增runner与包内执行清单；沿用既有bash/python工具，按命令中的skillbase_dir确定性归属并注入每次执行绑定；更新ADR-06、事件字段、实施地图与D3 |
| v0.4 | 2026-11-17 | 按固定`/skill`目录不可作边界的事实重构：会话级能力并集、能力级贡献者展示、无执行绑定/无进程归属；更新ADR-05/06、§4/§6/§7、协议与验收 |

