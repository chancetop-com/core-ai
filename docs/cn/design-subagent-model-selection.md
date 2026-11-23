# Subagent 模型选择 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Implemented / v0.2；用户已授权实施，逐条设计 Review 仍待确认 |
| 文档版本 | v0.2（实现记录；设计基线为 v0.1） |
| 创建日期 | 2026-11-13 |
| 需求提出者 / Reviewer | 用户；用户 Review 结论待记录 |
| 作者 | core-ai |
| 范围 | `task` 工具启动或继续 subagent 时的模型选择、默认继承、provider 解析、线程持久化与可观测性 |
| 本次交付 | v0.2 已按 v0.1 设计实现；未部署，未创建 commit |
| 设计依据 | 用户原始需求、实际源代码、Claude Code / OpenAI Agents SDK 的公开契约；不参考现有 design 文档方案 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> 我们现在 general subagent 可以指定模型吗
>
> 你设计一下 subagent可以设置模型 默认跟主agent一个模型，如果主agent调用subagent的时候给了则用它设置的模型，先参考design-agent-publish-quality-gate这个文档出techdesign

### 0.2 需求编号

| ID | 要求 | 性质 |
|---|---|---|
| M1 | subagent 类型可以有配置模型：复用 `agent.sub.<name>.model` 与自定义 profile 的 `model:` | 保持并纳入统一优先级 |
| M2 | 未配置模型时，默认使用主 agent 调用 subagent 当时的模型 | 必须 |
| M3 | 主 agent 调用 subagent 时如果给了 `model`，该次调用使用指定模型 | 必须 |
| M4 | 指定模型必须解析到正确的 LLM provider，不能把 A provider 的模型发给 B provider | 必须 |
| M5 | 指定模型不可用或无法解析时必须明确失败，不能静默回落 | 必须 |
| M6 | 不破坏 `task_id` 续跑、后台运行和线程持久化；续跑使用线程自己的模型 | 必须 |
| M7 | 模型选择在工具事件、trace 和子 agent 结果中可观察 | 必须 |
| D1 | 先交付设计，用户 Review 后才能实施 | 工作边界 |
| D2 | 参考指定文档的组织方式，保留原始需求、决策理由、Review 与修改记录 | 文档约束 |

### 0.3 文档维护规则

- 本节原始需求保持原文，后续解释、调整和新增需求单独记录，不覆盖历史。
- 每条 Review 使用稳定 ID，记录意见原文、处理方案、影响章节、状态与确认人；没有用户确认不得写成 Approved。
- 本文“拟新增”“拟修改”的 API、类型、字段和模块是设计，不代表当前实现；默认值和错误文案是提案，需实现与测试确认。
- 每次实质修改更新版本、修改记录及受影响的决策；在此文档内维护，不另拆零散记录。
- 本设计以当前工作区源码为事实来源；源码行号随代码演进可能移动，实施时以实际文件为准。

## 1. 目标、边界与核心提案

### 1.1 核心提案

给 `task` 工具增加一个可选的 `model` 参数，把 subagent 的模型选择统一为一条可解释、可测试的链：

1. **显式调用模型**：`task(model = "…")`，优先级最高。
2. **subagent 自身配置**：自定义 profile 的 `model:`，其次是 `agent.sub.<type>.model`。
3. **默认继承**：以上都没有时，使用主 agent 在本次 dispatch 时的模型。

模型不只是一个字符串，还必须解析到正确的 `LLMProvider`。core-ai 不硬编码模型目录，由调用宿主（CLI / 桌面引擎 / 未来 server）注入 `SubagentModelResolver`；resolver 返回 `(model, provider)`，core 只负责选择、应用、持久化和报错。

subagent 线程创建时记录实际使用的 `model` 和 `provider`。之后用 `task_id` 续跑时，默认使用线程自己的模型；主 agent 再次显式给出不同模型时，切换该线程的模型并持久化，而不是悄悄沿用旧配置。

**初衷与理由：**用户需要的是“默认跟主 agent 一致，但主 agent 可以在单次委派时指定模型”。如果只把 `model` 字符串塞给子 agent，跨 provider 场景会把请求发给错误的 provider；如果不持久化线程模型，线程被驱逐或会话重启后会在用户不知情的情况下换模型。把“选择、解析、持久化、展示”拆开，才能让默认继承、单次覆盖和续跑三种语义同时成立。

### 1.2 目标与不变量

1. 默认继承的是**主 agent dispatch 时**的模型，不是会话创建时的模型；用户中途 `/model` 切换后，后续委派跟随新模型。
2. 优先级固定为：`task.model` > profile `model:` > `agent.sub.<type>.model` > 主 agent 当前模型。空字符串按未提供处理。
3. 显式 `task.model` 必须在创建 agent、注册线程和占用运行 gate 之前完成解析；解析失败直接返回工具失败，不产生线程、不消耗模型调用。
4. 线程记录创建时的 `model` 与 `provider`；续跑和 rehydrate 使用线程模型。显式不同的模型允许切换，并更新线程记录。
5. 既有配置（profile / `agent.sub`）保持兼容：resolver 解析不了配置模型时，沿用原有 parent provider 行为，不把历史配置变成硬失败。
6. 不传 `model` 且没有任何配置时，行为与今天完全一致。
7. core 不引入模型名单、provider 路由表或别名系统；这些属于宿主能力，通过接口注入。
8. 不同模型带来的成本差异沿用现有 `RunBudget` 子预算和按模型计价逻辑，不新增计费通道。
9. 模型选择是委派参数，不是权限授予；工具权限、provider 凭据和预算仍独立校验。

### 1.3 非目标

- 不做 subagent 专用的模型选择 UI。配置仍走 `agent.properties` 和 agent profile 文件。
- 不修改 server 的 AgentDefinition 子 agent 组装路径（`SubAgentAssembler`）；server 的每个子 agent 定义本来就有自己的 `publishedConfig.model`。
- 不做模型别名解析（如 `sonnet` → 完整模型 id）、自动降级、按任务难度自动选模型或自动重试换模型。
- 不做 per-call 的 `temperature` / `reasoningEffort` / `maxTurn` 覆盖；本设计只处理模型与 provider。
- 不保证“模型名在开放网关上一定可用”；resolver 只能证明它属于已配置 provider，最终可用性仍由 provider 或网关裁决。
- 不把模型选择当作成本上限或权限控制；预算、速率和授权继续由现有机制负责。
- 不保证新线程索引能被旧版本 CLI 降级读取；只保证新版本读取旧索引。降级兼容见第 7 节。

## 2. 设计参照与采用方式

| 参照 | 采用的原则 | 不直接照搬的部分 | 初衷与理由 |
|---|---|---|---|
| Claude Code subagents [S1] | subagent 定义支持 `model`，`inherit` 表示跟随主会话；单次调用可传 `model`；优先级为“单次调用 > 定义 > 环境变量 > 主会话模型”；单次参数在 resumed / follow-up 时继续生效 | 不照搬单次参数只接受别名、定义可接受完整 id 的混合规则；不在模型不可用时自动替换成另一个模型 | 这套优先级与用户要求一致；自动替换会让“我要用哪个模型”变成不确定行为 |
| OpenAI Agents SDK [S2] | 每个 Agent 有独立 `model`；未设置时回退到默认模型；运行级别可以覆盖 | 不引入 SDK 的 `Model` 对象和默认模型目录 | “agent 级默认 + 调用级覆盖”是通用模式，但 core 的 provider 解析必须适配现有 CLI / 网关 |
| 项目现有 `agent.sub.*` 与 profile `model:` | 复用既有配置，不新增第二套 per-type 配置 | 不把 `agent.sub` 当作 per-call 参数 | 配置是长期策略，调用参数是一次性意图，两者生命周期不同 |
| 项目现有 `/model` 切换 | 模型必须和 provider 一起切换；provider 解析复用 `ModelRegistry` 的规则 | 不在 subagent 内复用会话级 model picker | 主 agent 的模型选择和 subagent 委派是不同层级 |

资料获取日期：2026-11-13。行业资料只用于说明契约模式；具体错误文案、校验范围和兼容策略以本项目源码与测试为准。

## 3. 现状核对与接入约束

本节只依据实际源码核对；下表是当前事实，不代表新增方案已实现。

| 现状 | 源码证据（工作区相对路径:行号） | 对设计的约束 |
|---|---|---|
| `task` 工具参数只有 `task_id` / `description` / `prompt` / `subagent_type` / `run_in_background` / `notify`，没有 `model` | `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:389-409` | 需要新增可选参数；无参数时必须完全保持现有行为 |
| 当前模型解析：先 `SubAgentConfig.model`，再 `context.getModel()` | `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:337-346` | 默认继承已经存在，设计要保留并补上显式调用层 |
| 自定义 profile 的 `model` 优先于上面解析出的模型 | `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:297-324`（`:303`） | 选择链必须显式表达 profile 的位置；显式调用要能覆盖 profile |
| `agent.sub.<name>.model` / `.provider` / `.max-turn` 从 properties 自动发现 | `core-ai-cli/src/main/java/ai/core/cli/CliAppHelper.java:309-334` | 复用现有配置，不新增同样含义的字段 |
| 每次 `Agent.run` 都会把自己的 provider / model 写进 `ExecutionContext` | `core-ai/src/main/java/ai/core/agent/Agent.java:428-441` | “主 agent 当前模型”应读取 dispatch 时的 context，而不是启动时快照 |
| 内置 subagent 工厂已经按 `(toolRegistry, llmProvider, model, context, maxTurn)` 构建 | `core-ai/src/main/java/ai/core/defaultagents/DefaultGeneralAgent.java:52`；`DefaultExploreAgent.java:46`；`DefaultCodeSimplifierAgent.java:38` | 显式模型只需替换传入的 model/provider，不需要新增 Agent 类型 |
| 线程已记录 model，但 index 没有 provider | `core-ai/src/main/java/ai/core/agent/subagent/SubagentThread.java:26`、`:36`；`SubagentThreadIndex.java:55-56` | 需要补 provider 持久化，否则跨 provider 续跑会漂移 |
| 线程 rehydrate 只按当前配置重建，不使用 `thread.model()` | `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:168-174`；`SubagentThreadRegistry.java:95-110` | 必须改为按线程模型重建；这是本设计明确的兼容性变更点 |
| 工具启动事件只按 `SubAgentConfig` / 父模型显示 model，忽略显式参数和线程模型 | `core-ai/src/main/java/ai/core/session/ServerPermissionLifecycle.java:146-163` | 显式参数与线程模型必须进入事件，否则 UI 显示与实际调用不一致 |
| 同步结果 footer 不含 model；异步通知含 `<task-model>` | `core-ai/src/main/java/ai/core/tool/tools/TaskToolPrompt.java:127-137`、`:139-147` | footer 应补充模型，便于主 agent 决定是否续跑 |
| 主 agent 的 `/model` 切换会同时改 agent provider 和 model，下一次 run 写入 context | `core-ai-cli/src/main/java/ai/core/cli/agent/ModelPicker.java:73-90`；`core-ai/src/main/java/ai/core/agent/Agent.java:434-435` | “默认跟主 agent 一致”在切换后仍然成立，不需要额外同步逻辑 |
| app-server 的 `model/set` 用 `ModelRegistry` 解析 provider；登录状态下未知模型回退 `LITELLM` | `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java:329-350`；`ModelRegistry.java:21-52` | CLI resolver 应复用同一规则，避免“模型能选、subagent 不能用” |
| core 没有模型目录或 model→provider 映射；`ExecutionContext` 只有当前 provider | `core-ai/src/main/java/ai/core/agent/ExecutionContext.java:58-63`；`LLMProvider.java` 没有模型枚举 | resolver 必须由宿主注入；core 不能硬编码模型名 |
| server 的 subagent 走 AgentDefinition + `SubAgentAssembler`，各自有 published model | `core-ai-server/src/main/java/ai/core/server/agent/SubAgentAssembler.java:125-143`、`:185-198` | server 的 agent 定义路径不在本次范围；`task` 内置工具路径只在配置了 builtin 组时生效 |
| `task` 在内置工具组 ALL / PLANNING 中；general agent 自身工具集不含 task | `core-ai/src/main/java/ai/core/tool/BuiltinTools.java:41-72`、`:74-78`；`DefaultGeneralAgent.java:22-24` | 默认无递归；自定义 profile 显式加入 task 时，嵌套调用按各自 context 解析 |
| `RunBudget` 按实际 model 估算成本；未知价格有 `costEstimateMissing` 信号 | `core-ai/src/main/java/ai/core/agent/budget/RunBudget.java:204-215`、`:282-296` | 不同模型的成本差异由现有预算承接，不需要新逻辑 |
| 现有测试覆盖续跑、持久化、后台运行和线程列表 | `core-ai/src/test/java/ai/core/tool/tools/TaskToolTest.java:89-239`；`core-ai/src/test/java/ai/core/agent/subagent/SubagentThreadRegistryTest.java:44-152` | 新能力必须加测，且不能破坏这些用例 |

**接入判断：**本设计的主战场是 `task` 工具（CLI / desktop app-server / ACP，以及配置了 builtin 组的 server agent）。server 的 `SubAgentAssembler` 是另一条“AgentDefinition 作为子工具”的路径，它已经在每个定义上配置模型，不因本设计改变。若未来要求 server 的 `task` 在运行前校验模型可用性，再单独接入 server 的模型目录。

## 4. 架构与契约

### 4.1 组件边界

```text
Main Agent (model=Mp, provider=Pp)
        |
        |  task(subagent_type=..., model=Mx?)
        v
   TaskTool.dispatch
        |
        +-- SubagentModelSelector (deterministic precedence)
        |        |
        |        +-- SubagentModelResolver (host injected, optional)
        |                +-- CLI: ModelRegistry + LiteLLM fallback
        |                +-- absent: parent provider, no catalog validation
        |
        +-- createAgent(type, model, provider)
        +-- SubagentThread(model, provider)
        |
        v
   TaskToolPrompt footer / ToolStartEvent / trace / usage
```

| 组件（拟新增或修改） | 责任 | 不应承担的责任 | 初衷与理由 |
|---|---|---|---|
| `SubagentModelResolver`（新，core） | 把 model id 解析成已配置的 `(model, provider)`；提供可用模型列表供错误提示 | 不创建 Agent、不读 AgentProfile、不决定优先级 | 让 core 只依赖“模型可解析”契约，不绑定 CLI / server 的具体目录 |
| `SubagentModelSelector`（新，core，package-private） | 按固定优先级选出 model、provider 和来源；集中生成模型错误文案 | 不直接读 properties，不做网络请求 | 选择逻辑可单测，`TaskTool` 只负责编排 |
| `SubagentAgentFactory`（新，core，package-private） | 用选好的 model/provider 构建内置或 profile subagent | 不决定模型优先级 | 把 agent 装配从工具生命周期中拆开，控制单文件复杂度 |
| `SubagentModelRef`（新，core） | 线程持久化的 `(model, provider)` 值对象 | 不持有 `LLMProvider` 实例 | provider 对象不可序列化，线程只存稳定名称 |
| `SubagentThread` / `SubagentThreadIndex` | 记录并恢复线程实际模型和 provider | 不判断模型是否可用 | 线程是对话，模型必须随会话一起稳定 |
| `CliSubagentModelResolver`（新，CLI） | 复用 `ModelRegistry` 和 `model/set` 的 provider 解析规则 | 不做 UI、不写配置 | 保证“模型选择器能选”与“subagent 能用”一致 |
| `TaskToolPrompt` / `ServerPermissionLifecycle` | 展示实际 model，而不是仅展示配置值 | 不参与 agent 构建 | UI 与 trace 必须与真实调用一致 |
| `ExecutionContext` | 携带 resolver，供嵌套 subagent 复用 | 不保存 per-thread 状态 | 嵌套调用天然继承同一个宿主能力 |

### 4.2 模型选择优先级

| 顺序 | 来源 | 何时使用 | provider 解析 | 解析失败 |
|---|---|---|---|---|
| 1 | `CALL`：`task.model` | 主 agent 显式给出且非空白 | 必须经 resolver；无 resolver 时用 parent provider | 返回工具失败，不创建线程 |
| 2 | `THREAD`：`thread.model` | 续跑已有线程，且本次未给 `model` | 优先线程记录的 provider；否则 resolver | 新格式线程失败；legacy 线程按第 4.4 节降级 |
| 3 | `PROFILE`：自定义 profile 的 `model:` | 新线程，或 legacy 线程未记录模型 | `SubAgentConfig.llmProvider` 优先；其次 resolver；最后 parent provider | 不硬失败，记录 warning（保持既有配置兼容） |
| 4 | `CONFIG`：`agent.sub.<type>.model` | 新线程，或 legacy 线程未记录模型 | `SubAgentConfig.llmProvider` 优先；其次 resolver；最后 parent provider | 不硬失败，记录 warning |
| 5 | `INHERIT`：主 agent 当前模型 | 以上都没有 | parent provider | 无校验；provider 默认模型兜底 |

选择逻辑的伪代码：

```java
ModelSelection select(String type, String requestedModel, SubagentThread thread,
                      AgentProfile profile, SubAgentConfig config, ExecutionContext context) {
    if (notBlank(requestedModel)) {
        return resolveStrictly(requestedModel.trim(), context);        // CALL
    }
    if (thread != null && notBlank(thread.model())) {
        return resolveThreadModel(thread, context);                    // THREAD
    }
    if (profile != null && notBlank(profile.model())) {
        return resolveWithFallback(profile.model().trim(), config, context, "PROFILE");
    }
    if (config != null && notBlank(config.model())) {
        return resolveConfigured(config, context);                     // CONFIG
    }
    return inherit(context);                                           // INHERIT
}
```

**为什么 profile 在 `agent.sub` 前面：**这是当前代码的实际行为（`buildAgentFromProfile` 使用 `profile.model != null ? profile.model : model`）。本设计不改变已有优先级，只在其前面加一层显式调用。

**实现约束：**profile 只查一次，并把同一个 `AgentProfile` 对象传给选择和 agent 构建，避免两次读取之间 profile 文件变化，造成“选择的模型”和“构建的 agent”不一致。

**为什么父模型在最后：**父模型是“没有更具体意图时的默认值”，不是强制值；否则用户配置的 per-type 模型永远不会生效。

**空值处理：**`null`、空字符串和纯空白都视为“未提供”。模型 id 按精确字符串比较，不做大小写折叠，不做首尾以外的规范化。

### 4.3 provider 解析规则

模型和 provider 必须一起解析。`task.model` 是模型 id，不是 provider 名；调用方不传 provider，避免把“选模型”变成“选基础设施”。

| 来源 | 规则 |
|---|---|
| `CALL` | 有 resolver：`resolve(model)` 必须返回已配置 provider；返回空或抛异常即失败。无 resolver：使用 parent provider，model 原样传入（仅用于 standalone / 测试；真实 CLI 会注入 resolver）。 |
| `THREAD` | 线程存了 provider（新格式）：按名称找到已配置 provider，找到即用；找不到或名称为空时用 resolver 按模型重新解析，仍失败则失败，不静默换成 parent provider。线程没有 provider（legacy）：先尝试 resolver，仍失败时按旧行为使用 parent provider，并在运行后补写 provider。 |
| `PROFILE` | `SubAgentConfig.llmProvider` 是 per-type 配置，优先使用；未配置时尝试 resolver；解析不到时沿用 parent provider，并记录 warning。profile 没有 provider 字段，这是唯一可行且兼容旧行为的策略。 |
| `CONFIG` | `SubAgentConfig.llmProvider` 是显式配置，优先级最高；未配置时尝试 resolver；仍失败则沿用 parent provider（与今天一致）。 |
| `INHERIT` | 使用 parent provider；模型取 `context.getModel()`，为 null 时由 provider 的默认模型兜底。 |

`SubagentModelResolver` 的拟新增契约：

```java
public interface SubagentModelResolver {
    ResolvedModel resolve(String model, String preferredProvider);

    default ResolvedModel resolve(String model) {
        return resolve(model, null);
    }

    List<String> availableModels();

    record ResolvedModel(String model, LLMProvider provider, String providerName) {
    }
}
```

`resolve` 失败时抛 `SubagentModelUnavailableException`，带 `UNKNOWN_MODEL` / `PROVIDER_UNAVAILABLE` reason；`availableModels()` 只用于错误提示，不参与选择逻辑，也不保证穷尽（网关可能接受目录外模型）。`preferredProvider` 只在线程恢复时作为“这个模型之前由该 provider 服务”的提示；实现必须确认该 provider 能服务该模型（或本身是网关），否则回退到按模型解析。

**CLI resolver 的解析规则（与 `model/set` 对齐）：**

```java
ResolvedModel resolve(String model, String preferredProvider) {
    if (preferredProvider configured) return provider(preferredProvider);
    var type = modelRegistry.getProviderType(model);
    if (type == null && litellmConfigured) type = LLMProviderType.LITELLM;
    if (type == null) throw new SubagentModelUnavailableException(UNKNOWN_MODEL, model);
    var provider = llmProviders.getProvider(type);
    if (provider == null) throw new SubagentModelUnavailableException(PROVIDER_UNAVAILABLE, model);
    return new ResolvedModel(model, provider, type.getName());
}
```

- `litellmConfigured` 与 `model/set` 的登录回退一致：本地 registry 找不到、但已配置 LiteLLM 网关时，接受该模型 id，由网关做最终校验。这样桌面端登录用户使用服务端模型目录时不会出现“模型选择器能选、subagent 不能用”。
- `ModelRegistry.getProviderType` 对同名模型返回第一个匹配项；这与 `/model` 现有歧义处理一致。设计不引入第二套歧义规则。
- server 使用 `GatewayLLMProvider` 时，provider 通常只有一个，模型 id 由网关 routing engine 解析；server 没有注入 resolver 时，`CALL` 直接使用当前默认 provider（通常是网关），未知模型在首次调用时由 provider 或网关报错。若 server 配置了非网关的默认 provider，行为随该 provider，不假设一定是网关。

### 4.4 续跑与线程语义

subagent 线程不是无状态函数调用；它保存了之前的消息、工具输出和上下文。模型切换必须显式、可观察，不能因为会话重启而漂移。

1. **新线程**：按 4.2 选出模型，创建 agent，注册线程时写入 `SubagentModelRef(model, provider)`。
2. **续跑且未给 `model`**：使用线程记录的模型和 provider。线程没有记录（legacy）时，按 profile / config / inherit 重新选择，并在本次运行后写回线程。
3. **续跑且给了相同 `model`**：视为 no-op，仍使用线程 provider。
4. **续跑且给了不同 `model`**：解析成功后，对 live agent 调用 `setLlmProvider` + `setModel`；对 dormant agent，在 rehydrate 工厂里直接用新选择重建。更新线程记录，footer 显示新模型。
5. **线程正在运行**：沿用现有 gate，先返回 “still running”，不做模型切换，避免并发修改同一个 agent。
6. **父 agent 中途换模型**：已存在的线程不自动跟随，仍使用线程记录的模型；需要跟随父模型时，显式传当前模型或开新线程。这是“线程是持续对话”的直接结果。
7. **rehydrate**：必须使用线程记录的模型和 provider 重建 agent，而不是当前 profile / `agent.sub` / 父模型。线程模型不可用时，返回 `SUBAGENT_THREAD_MODEL_UNAVAILABLE`，不静默换模型。
8. **legacy 线程**：旧索引没有 provider，但 `model` 字段已经存在（`TaskTool` 今天就会写入）。重建时先尝试 resolver；解析不到时按旧行为使用 parent provider，并在本次运行后补写 provider。旧索引没有 `model` 的线程按 inherit 处理。
9. **profile 被删除 / 类型不可用**：保留现有 `SubagentThreadUnavailableException` 语义，优先报告“type 不再可用”，而不是误报模型问题。
10. **compression**：当前内置 subagent 与 profile 构建路径都没有启用 compression；若未来给 subagent 配置 compression，切换模型时必须同步 `Compression.updateModel(provider, model)`，可复用 `EngineSessionModels.applyTo` 的做法，否则压缩摘要仍会发往旧模型。

**为什么允许续跑时切换模型：**需求明确“主 agent 调用时给了就用它设置的模型”，Claude Code 的单次模型参数在 resumed / follow-up 时也继续生效。拒绝切换会让主 agent 无法纠正之前的模型选择。切换风险是 provider 特有的消息格式差异；本项目已有 `MessageNormalizer`，且切换是显式行为，失败会作为工具错误返回，线程保持不变。

### 4.5 Tool 契约

`task` 工具新增参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `model` | String | 否 | 本次 subagent 运行的模型 id。省略则继承主 agent 当前模型；不可用时工具调用失败，不回退。续跑时传不同值会切换该线程的模型。 |

参数描述（拟）：

```text
Optional model id for this subagent run. Omit it to inherit your current model.
Pass an exact model id only when the user asked for a specific model; an unavailable
model fails the call instead of falling back. When continuing a subagent with task_id,
a different model switches that subagent to it for this and later runs.
```

工具描述在现有 Usage notes 后追加一条（拟）：

```text
7. `model` is optional. Omit it to run the subagent on your current model. Use an exact
   model id available in this session; an unavailable model fails the call. Omit it when
   you are unsure, or when the user did not ask for a specific model.
```

`toTool(context)` 生成描述时追加当前模型提示（拟）：

```text
Your current model: <effective-model>. Omit `model` to inherit it.
```

当 `context.getModel()` 为空时，使用 provider 配置模型（与 `ModelPicker.getCurrentModelName` 的取值方式一致）；两者都为空时不追加该行。

**不提供 provider 参数：**provider 是运行环境细节，不应让主模型选择；由 resolver 根据模型 id 解析。这样也避免模型在参数里编造不存在的 provider 名。

**不做别名：**`sonnet` / `opus` 这类别名不在 v1 支持范围；resolver 只接受配置中的精确 id。若未来需要别名，放在 resolver 内部做确定性映射，而不是在 `TaskTool` 里加分支。

**配置示例（已有能力，本设计复用）：**

```properties
# ~/.core-ai/agent.properties 或 <workspace>/.core-ai/agent.properties
agent.sub.general-purpose-agent.model=gpt-5-mini
agent.sub.general-purpose-agent.provider=litellm
```

```markdown
---
name: fast-general
description: "A cheap general-purpose subagent for search and summarization"
model: gpt-5-mini
tools:
  - builtin-files
  - builtin-web
---
You are a fast general-purpose subagent.
```

内置名 `general-purpose-agent` 不能被同名文件覆盖（内置 profile 优先级 0 且先注册）；要使用 profile 的 `model:`，请起一个新名字并通过 `subagent_type` 调用。

### 4.6 失败与错误

`ToolCallResult` 目前只有文本结果，不引入新的 API 错误类型；用稳定的 reason 前缀让日志、测试和 UI 可机器识别。

| reason | 触发场景 | 用户可见文案（拟） | 状态副作用 |
|---|---|---|---|
| `SUBAGENT_MODEL_UNKNOWN` | 显式 `model` 不在可用目录，且无网关回退 | `model 'x' is not available in this session. Available models: ... Omit model to inherit your current model.` | 不创建线程，不消耗 LLM 调用 |
| `SUBAGENT_MODEL_PROVIDER_UNAVAILABLE` | 模型属于某 provider，但该 provider 未配置 | `model 'x' is configured for provider 'y', but that provider is not available.` | 同上 |
| `SUBAGENT_THREAD_MODEL_UNAVAILABLE` | 续跑时线程模型或 provider 已不可用 | `subagent sa-xxxx was created with model 'x', which is no longer available. Start a new subagent or restore the provider configuration.` | 线程保持原状态，run count 不增加 |
| `SUBAGENT_MODEL_INVALID` | 参数类型/解析异常（理论上 JSON schema 已拦截） | `model must be a non-empty string when provided.` | 同上 |
| `SUBAGENT_MODEL_LOCKED`（可选，v1 不启用） | 管理员锁定 subagent 模型，禁止调用级覆盖 | `model override is disabled for subagent type 'x'.` | 同上 |

- 错误只返回模型 id、provider 名和可用模型列表，不返回 API key、base URL、headers 或完整 provider 配置。
- 可用模型列表最多展示 10 个，避免错误消息过长；文案明确“列表不保证穷尽”，并建议省略 `model`。
- 显式模型解析失败发生在 `tryAcquire` 之前，不会出现“gate 已占用但没有 run”的泄漏路径。
- 续跑时如果线程正在运行，先返回现有 “still running” 错误；模型校验不应绕过 gate。

### 4.7 可观测性

| 展示面 | 内容 | 变更 |
|---|---|---|
| 工具描述 | 当前主 agent 模型 + `model` 参数说明 | 新增，帮助主模型决定是否省略 |
| `ToolStartEvent.model` | 本次实际使用的模型（显式 > 线程 > 配置 > 父模型） | 修改 `ServerPermissionLifecycle`，不再只读 `SubAgentConfig` |
| 同步结果 footer | `[subagent task_id=... type=... model=... runs=...]` | 新增 `model`，仅在线程有模型时输出 |
| Active subagents 列表 | 每行显示 `model=...` | 新增，便于主模型决定续跑哪个线程 |
| 异步启动通知 | `<task-model>` 使用线程实际模型 | 现有字段，改为线程真实值 |
| Trace / LLM span | 请求实际发送的 model | 现有链路自然记录；需确认子 agent 的 model 覆盖后 span 正确 |
| 日志 | `subagent model selected: taskId, type, model, provider, source` | 新增 debug/info 日志，source 只用于诊断 |
| 用量与成本 | 按实际 model 估算；未知价格标记 `costEstimateMissing` | 复用现有 `RunBudget` |

不新增独立指标服务；如果后续需要统计“显式覆盖比例”，可在现有 action/trace 日志中按 `source=CALL` 聚合，不把 model 作为高基数 metric label。

## 5. 数据模型与持久化

### 5.1 线程字段

| 字段 | 类型 | 语义 | 兼容性 |
|---|---|---|---|
| `SubagentThread.model` | String | 创建或最近一次切换后的模型；null 表示未解析，交给 provider 默认 | 已存在；从 `final` 改为可更新 |
| `SubagentThread.provider` | String | 稳定 provider 名称（如 `litellm`、`azure`）；null 表示 legacy 或未知 | 新增 |
| `ThreadRef.model` | String | 索引中的模型 | 已存在 |
| `ThreadRef.provider` | String | 索引中的 provider | 新增，可选 |

拟新增值对象：

```java
public record SubagentModelRef(String model, String provider) {
}
```

`SubagentThread` 增加 `updateModel(String model, String provider)`，在切换成功后和 legacy 线程首次运行后调用。`SubagentThreadRegistry` 增加 `registerWithModel(..., SubagentModelRef, Agent)`，保留旧的 `register(..., String model, Agent)` 重载（provider 为 null），减少测试与外部调用面的改动。

### 5.2 索引格式

```json
{
  "threads": [
    {
      "task_id": "sa-1a2b3c4d",
      "kind": "TASK",
      "type": "general-purpose-agent",
      "description": "search for the retry config",
      "model": "gpt-5-mini",
      "provider": "litellm",
      "run_count": 2,
      "created_at_ms": 1763000000000,
      "last_run_at_ms": 1763000100000,
      "context_tokens": 12034,
      "last_summary": "..."
    }
  ]
}
```

- 新版本读取旧索引：缺失的 `provider` 为 null，按 4.4 的 legacy 规则处理。
- 旧版本读取新索引：新增字段可能触发严格 JSON 反序列化失败；本设计只承诺前向兼容（新读旧），不承诺降级兼容。若要支持降级，需要给索引加版本号或独立 key，属于后续工作。
- 线程消息数据（`SubagentPersistenceKeys.thread`）不重复存模型；索引是模型元数据的单一来源，消息体只存对话。
- 持久化失败仍沿用现有策略：线程退化为 memory-only，不阻塞运行。

### 5.3 rehydrate 流程

1. `SubagentThreadRegistry` 从索引加载 `model` / `provider`。
2. `TaskTool` 在续跑前完成选择（显式参数或线程记录）。
3. 工厂 `createAgent(type, subContext, selection)` 用选择的 model/provider 构建 agent。
4. `rehydrate` 恢复消息时删除旧的 system message，保留其余对话（现有行为不变）。
5. 若线程模型不可解析，抛 `SubagentThreadUnavailableException`，由 `TaskTool` 映射为 `SUBAGENT_THREAD_MODEL_UNAVAILABLE`。
6. 运行结束后把实际生效的 model/provider 写回线程并持久化；即使本次是 inherit 且 `model` 为 null，也用 provider 的默认模型补写，避免下次续跑再跟着父 agent 漂移。

## 6. 运行面接入

| 运行面 | resolver 构建 | 说明 |
|---|---|---|
| 交互式 CLI | `CliApp` 用 `props` + `result.llmProviders` 构建 `CliSubagentModelResolver`，通过 `CliAgent.Config` 传入 | `/model` 切换后，下一次 run 的 `ExecutionContext.model` 自动更新，子 agent 默认跟随 |
| desktop app-server | `EngineBootstrap` 构建并暴露 resolver；`EngineSessionRegistry` 在创建每个 session 的 `CliAgent.Config` 时传入 | 与 `model/set` 使用同一套 `ModelRegistry` + LiteLLM 回退规则 |
| ACP | `AcpAgentRunner.run()` 用已有 `props` + `result.llmProviders` 构建 resolver | 无 UI，但 `task` 与线程续跑使用相同契约 |
| server（`task` 内置工具路径） | 不注入 resolver；使用 server 默认 provider（通常为 `GatewayLLMProvider`） | 模型 id 由网关 routing 解析；未知模型在首次 LLM 调用时报错。server 的 AgentDefinition 子 agent 不走本路径 |
| standalone / 单测 | 不注入 resolver | 显式模型使用 parent provider；便于 core 单测，不依赖 CLI 目录 |

`CliSubagentModelResolver` 构造建议：

```java
public final class CliSubagentModelResolver implements SubagentModelResolver {
    public static CliSubagentModelResolver create(PropertiesFileSource props, LLMProviders providers) {
        return new CliSubagentModelResolver(new ModelRegistry(providers, props), providers);
    }
}
```

`CliAgent.Config` 增加一个 `subagentModelResolver` 字段；`configureExecutionContext` 把它写入 `ExecutionContext`；`TaskTool.buildSubContext` 再复制到子 context，保证嵌套 subagent 也能解析模型。`EngineBootstrap` 已经有 `modelRegistry`，应直接复用它构造 resolver；`CliApp` / ACP 可用上面的工厂创建，避免同时存在两份 provider 解析规则。

**共享结构的边界：**`SubAgentToolCall`（`AGENT_TOOL`）也使用 `SubagentThread`，但它的 agent 由 AgentDefinition 或工厂提供，没有 per-call 模型参数，目前以 `model=null` 注册（`SubAgentToolCall.java:165`）。本设计保留 `register(..., model, agent)` 旧重载，`provider` 为 null，不改变 `AGENT_TOOL` 的构建与 rehydrate 行为；`TaskTool` 只对 `SubagentThreadKind.TASK` 做模型选择、切换和展示。

**server 的边界：**`SubAgentAssembler` 在构建每个子 agent 时已经读取 `publishedConfig.model`（`SubAgentAssembler.java:192`、`:220-228`），这是“AgentDefinition 作为工具”的路径，不受本设计影响。只有当 server agent 配置了包含 `task` 的 builtin 工具组时，才会进入本设计的 `TaskTool` 路径；此时 provider 是 server 的默认 provider（通常是网关），模型 id 由 provider / 网关负责路由。

## 7. 兼容性、并发与成本

### 7.1 兼容性

| 场景 | 当前行为 | 本设计行为 | 说明 |
|---|---|---|---|
| 不传 `model`，无 profile / `agent.sub` 配置 | 使用 parent model | 不变 | 默认继承本来就走 `context.getModel()` |
| 不传 `model`，有 `agent.sub.<type>.model` | 使用配置模型 | 不变 | 仍按类型配置解析 |
| 不传 `model`，自定义 profile 有 `model:` | profile 模型优先 | 不变 | 选择链保留 profile 优先级 |
| 线程被驱逐后继续 | 用当前配置重建，忽略 `thread.model` | 用线程记录的模型重建 | **有意的行为变更**；否则重启会静默换模型 |
| 旧索引（无 provider） | 用当前配置重建 | 先按模型解析，失败时按旧行为 parent provider，运行后补写 provider | 尽力前向兼容 |
| 旧版本 CLI 读取新索引 | 不适用 | 可能因未知字段失败 | 只承诺新读旧；降级兼容不在本次范围 |
| 配置模型不在 resolver 目录 | 使用 parent provider | 保持 parent provider，记录 warning | 不把历史配置变成硬失败 |
| 显式模型不可用 | 当前没有该参数 | 工具失败，不回落 | 这是新增能力，不存在历史兼容问题 |

**rehydrate 变更的理由：**线程是一个持续对话，不是每次按最新配置重新执行的函数。如果线程创建时用模型 A，云端配置后来改成 B，重启后线程突然用 B，会改变成本、能力和输出风格，而且调用方看不到。把线程模型固定下来，才能让“续跑”名副其实。需要换模型的用户可以在续跑时显式传 `model`，或开新线程。

### 7.2 并发

- 每个线程仍然只有一个运行 gate（`SubagentThread.tryStart`）。模型切换发生在 gate 获取成功之后、agent 运行之前；并发续跑会在 gate 处被拒绝。
- `SubagentThread.model` / `provider` 只在该线程持有 gate 时更新；读取方（列表、footer）可能看到更新前或更新后的值，但不会看到半个字符串。
- 线程索引写入沿用 `SubagentThreadRegistry.persist` 的 `synchronized (indexLock)`，不新增并发写路径。
- resolver 必须是线程安全的；CLI 实现只读 `ModelRegistry` / `LLMProviders`，满足要求。
- 注意 `ModelRegistry` 当前类注释明确“not thread-safe”，内部是 `ArrayList`；而 subagent 解析可能发生在 session 线程或后台线程，与 `/model` 的增删模型并发。实现必须先把 registry 读路径变成线程安全的（例如 `CopyOnWriteArrayList`、同步快照，或由 resolver 持有不可变快照并在模型变更时刷新），不能让 resolver 直接并发读现有可变 `ArrayList`。
- `TaskTool` 本身是工具实例，可能被多个 session 共享；本设计不引入 per-tool 可变状态，模型选择全部使用调用参数和 context。

### 7.3 成本与预算

- 子 agent 的 `ExecutionContext` 已经通过 `runBudget.childScope()` 继承预算（`TaskTool.buildSubContext`），模型切换不改变这个边界。
- `RunBudget` 按实际模型估算成本（`RunBudget.java:204-215`、`:286-296`）；不同模型价格不同，但都在同一个子预算内结算。
- 模型不在价格表时，`costEstimateMissing` 会被置位；UI / 日志应能区分“费用未知”和“费用为 0”。
- 显式模型是主模型的决定，不自动重试到更便宜或更贵的模型；失败就是失败。
- 若未来需要硬性限制可选模型集合，建议在 resolver 的 `availableModels()` 或配置层实现，不要把价格表硬编码进 `TaskTool`。

### 7.4 嵌套与边界

- 默认 general agent 的工具集不包含 `task`（`DefaultGeneralAgent.java:22-24`），所以默认没有递归 subagent。
- 自定义 profile 可以显式把 `task` 加入工具集；嵌套调用时，每个子 agent 的 `ExecutionContext` 由 `Agent.getExecutionContext()` 写入自己的 model/provider，因此内层默认继承的是**直接父 agent**的模型，而不是最外层主 agent。
- 子 agent 的工具权限、沙箱和输出目录沿用现有 subContext 复制逻辑，本设计不改变。
- 模型切换不会改变 subagent 的 tool set、system prompt、max turn 或 thread 标识。

## 8. 安全与治理

| 风险 | 控制 | 初衷与理由 |
|---|---|---|
| 主模型编造不存在的模型名 | resolver 校验；无网关回退时直接失败并列出可用模型 | 早期失败比让子 agent 跑一半再报错更可控 |
| 主模型被网页或工具输出注入，选择昂贵模型 | 只允许 resolver 目录内 / 网关接受的模型；沿用 RunBudget；记录 `source=CALL` | 不新增“模型选择”权限，但保留成本边界和审计线索 |
| 跨 provider 发送错误模型 | 模型与 provider 一起解析；不允许只改 model 不改 provider | 这是本设计最核心的正确性约束 |
| 线程重启后静默换模型 | 线程持久化 model/provider；不可用时明确失败 | 用户看到的模型必须和实际调用一致 |
| provider 配置泄露 | 错误只展示 provider 名称，不展示 base URL、key、headers | 错误消息会进入 transcript，不能扩大敏感信息暴露 |
| 模型名进入日志/UI 造成 XSS | 模型 id 按纯文本处理，不拼 HTML | 现有 UI 已按文本渲染工具参数；新增字段不改变信任级别 |
| 管理员希望锁定模型 | v1 不提供 lock；显式调用优先（见 Q3） | 用户原始需求就是“调用时给了就用它”，锁是额外的治理能力 |
| 未知模型的成本不可见 | 复用 `costEstimateMissing`；日志记录 model 和 usage | 不把未知价格伪装成 0 |

模型选择不改变工具授权：subagent 仍然只能使用 profile / 工具集允许的工具；模型不能因为“更贵”而获得额外能力。

## 9. 测试与验收

### 9.1 测试矩阵

| 编号 | 场景 | 预期 |
|---|---|---|
| T1 | 不传 `model`，无 profile / `agent.sub` | 子 agent 请求的 model 等于父 agent 当前 model |
| T2 | `agent.sub.explore.model=m1` | 子 agent 使用 m1；footer 显示 m1 |
| T3 | profile `model: m2` + `agent.sub` m1 | 使用 m2（profile 优先） |
| T4 | 显式 `model=m3` + profile m2 | 使用 m3（显式优先） |
| T5 | 显式模型映射到另一个 provider | 子 agent 的 `LLMProvider` 是目标 provider；请求 model 正确 |
| T6 | 显式未知模型，无网关回退 | 工具失败，reason=`SUBAGENT_MODEL_UNKNOWN`；不注册线程；不调用 LLM |
| T7 | 显式模型属于未配置 provider | 工具失败，reason=`SUBAGENT_MODEL_PROVIDER_UNAVAILABLE` |
| T8 | 续跑不传 `model` | 使用线程 model；父 agent 中途 `/model` 切换不影响 |
| T9 | 续跑传相同 `model` | no-op，不重复写线程 |
| T10 | 续跑传不同 `model` | 切换成功；线程 model/provider 更新；footer 显示新模型 |
| T11 | 线程驱逐后继续 | rehydrate 使用线程 model，而不是当前配置 |
| T12 | 线程 model 已不可用 | 继续失败，reason=`SUBAGENT_THREAD_MODEL_UNAVAILABLE`；线程状态不变 |
| T13 | 旧索引无 provider | 可解析时使用解析出的 provider；不可解析时沿用 parent provider，运行后补写 |
| T14 | 旧索引无 model | 按 inherit 处理 |
| T15 | 后台运行 + 显式 model | 异步通知 `<task-model>` 正确；完成后的 footer 正确 |
| T16 | 显式模型为空白字符串 | 视为未提供，走默认继承 |
| T17 | 嵌套 subagent（profile 显式包含 task） | 内层默认继承直接父 agent 的模型 |
| T18 | `ToolStartEvent.model` | 显式 > 线程 > 配置 > 父模型，与实际调用一致 |
| T19 | 现有 `TaskToolTest` / `SubagentThreadRegistryTest` | 全部通过，无行为回归 |
| T20 | 预算：高成本模型 + 子预算 | 预算照常扣减；未知价格置 `costEstimateMissing` |

### 9.2 单元 / 集成测试

- `TaskToolTest`：用可控的 `SubagentModelResolver` 和多个 `MockLLMProvider` 验证选择链、provider 切换、失败路径和线程持久化。
- `SubagentThreadRegistryTest`：验证 `provider` 的持久化、旧索引兼容、`updateModel` 后重启仍生效。
- `ServerPermissionLifecycleTest`：验证工具启动事件的 model 优先级；尤其是续跑时线程模型优先于当前配置。
- `TaskToolPromptTest`（若新增）：验证 footer / active list 包含 model 且不破坏现有 `(type)` 格式。
- 不需要真实模型：所有选择逻辑用 mock provider；跨 provider 用两个 `RecordingMockLLMProvider` 分别记录请求。

### 9.3 手工 / E2E

实现完成后（不在本设计阶段执行）：

1. 用 `agent.properties` 配置至少两个 provider（或一个 LiteLLM 网关 + 一个本地 provider）。
2. 启动交互式 CLI 或 desktop app-server，触发一次 `task(subagent_type=general-purpose-agent, model=<目标模型>)`。
3. 在 trace / 日志中确认实际请求 model 与 provider 正确；确认工具启动事件显示目标模型。
4. 续跑同一 `task_id`，确认默认使用线程模型；显式传另一个模型，确认切换并持久化。
5. 重启 CLI / 引擎，再次续跑，确认仍使用线程模型。
6. 删除或改名模型配置，确认续跑返回 `SUBAGENT_THREAD_MODEL_UNAVAILABLE`，而不是静默换模型。
7. 跑现有 CLI / desktop 回归，确认不传 `model` 的会话行为不变。

### 9.4 验收标准

- M1–M7 每条都有对应测试或手工验证记录。
- 显式未知模型不会产生线程、不会调用 LLM、不会占用运行 gate。
- 不传 `model` 的现有会话行为与基线一致。
- 线程重启/驱逐后的模型与创建时或最近一次显式切换一致。
- 工具事件、footer、异步通知和 trace 中的模型一致。
- 错误信息包含模型、可用模型和修复建议，不泄露 provider 凭据。

## 10. 实施计划

**本节保留实施计划，并记录 v0.2 完成情况。** v0.2 已完成 P1–P3 的代码与单元测试；P0 的逐条设计 Review 仍待用户确认；部署、dev E2E 和 commit 未执行。不在本次设计任务中部署。

| 阶段 | 工作 | 必须完成的验证 |
|---|---|---|
| P0 设计确认 | 关闭第 12 节 Q1–Q11，固定优先级、错误文案和续跑语义 | 用户 Review 记录；M1–M7 无遗漏 |
| P1 core 选择链 | `SubagentModelResolver` / `SubagentModelRef` / 异常；`TaskTool` 新增参数、选择和 provider 解析；`ExecutionContext` 携带 resolver；错误路径 | 单测 T1–T7、T16、T19；无 gate 泄漏 |
| P2 线程与可观测性 | `SubagentThread` model/provider 可变与持久化；`SubagentThreadIndex` 新字段；rehydrate 使用线程模型；footer / active list / `ToolStartEvent` | 单测 T8–T15、T17–T18；旧索引兼容 |
| P3 宿主接入 | CLI `CliSubagentModelResolver`；`CliAgent.Config`；interactive / app-server / ACP 三处装配；server 行为确认 | 手工 E2E；desktop 重启引擎后复测 |
| P4 文档与收尾 | 更新工具说明、发布说明；记录任何兼容性变更；按项目习惯提交代码 | 代码审查；全量相关测试；dev E2E 记录 |

### 10.1 预计改动文件

| 模块 | 文件 | 改动 |
|---|---|---|
| core | `core-ai/src/main/java/ai/core/agent/subagent/SubagentModelResolver.java` | 新增接口与嵌套 `ResolvedModel` |
| core | `core-ai/src/main/java/ai/core/agent/subagent/SubagentModelUnavailableException.java` | 新增带 reason 的解析异常 |
| core | `core-ai/src/main/java/ai/core/agent/subagent/SubagentModelRef.java` | 新增持久化值对象 |
| core | `core-ai/src/main/java/ai/core/tool/tools/SubagentModelSelector.java` | 新增模型选择器与 `ModelSelection` |
| core | `core-ai/src/main/java/ai/core/tool/tools/SubagentAgentFactory.java` | 新增 subagent 构建工厂 |
| core | `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java` | 参数、编排、provider 应用、续跑切换、错误映射 |
| core | `core-ai/src/main/java/ai/core/tool/tools/TaskToolPrompt.java` | 参数说明、当前模型提示、footer / active list |
| core | `core-ai/src/main/java/ai/core/agent/subagent/SubagentThread.java` | `provider`、可变 model、`updateModel` |
| core | `core-ai/src/main/java/ai/core/agent/subagent/SubagentThreadIndex.java` | `provider` 可选字段 |
| core | `core-ai/src/main/java/ai/core/agent/subagent/SubagentThreadRegistry.java` | `registerWithModel`、rehydrate 使用线程模型 |
| core | `core-ai/src/main/java/ai/core/agent/ExecutionContext.java` | resolver 存取与子 context 复制 |
| core | `core-ai/src/main/java/ai/core/session/ServerPermissionLifecycle.java` | 工具事件 model 解析 |
| CLI | `core-ai-cli/src/main/java/ai/core/cli/agent/CliSubagentModelResolver.java` | 新增 resolver |
| CLI | `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java` | Config 字段、写入 ExecutionContext |
| CLI | `core-ai-cli/src/main/java/ai/core/cli/CliApp.java` | 构造 resolver |
| CLI | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java` | 构造并暴露 resolver |
| CLI | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java` | 传入 resolver |
| CLI | `core-ai-cli/src/main/java/ai/core/cli/acp/AcpAgentRunner.java` | 构造 resolver |
| test | `TaskToolTest`、`SubagentThreadRegistryTest`、`ServerPermissionLifecycleTest` | 新增用例，更新 register 调用 |

## 11. 关键决策记录（ADR 摘要）

| ID | 决策 | 初衷 / 理由 | 主要备选及为何未选 | 代价 |
|---|---|---|---|---|
| ADR-01 | 在 `task` 工具增加可选 `model` | 主 agent 是唯一知道单次委派意图的一方 | 只靠配置：无法为单次任务选模型；只靠会话 `/model`：影响整个会话 | 工具 schema 与描述增长 |
| ADR-02 | 优先级为显式 > profile > `agent.sub` > 父模型 | 与现有 profile 优先行为一致，并满足用户的显式覆盖要求 | 显式 > `agent.sub` > profile：改变现有语义；配置 > 显式：违背需求 | 规则需要文档化 |
| ADR-03 | 模型解析交给宿主注入的 resolver | core 没有模型目录；CLI 与 server 的解析面不同 | core 内置模型表：很快过时；只改 model 不改 provider：跨 provider 必错 | 多一个接口和三处装配 |
| ADR-04 | 显式模型不可用时失败，不自动降级 | 用户明确选了模型，静默替换会隐藏错误 | 自动换模型：不可预测；静默父模型：违背意图 | 用户需要重试或省略参数 |
| ADR-05 | 线程持久化 model + provider | 线程是持续对话，重启后必须保持同一模型 | 只存 model：provider 可能漂移；不存：重启后回到配置 | 索引多一个字段；旧版本降级不保证 |
| ADR-06 | 续跑显式不同模型时允许切换 | 需求要求“调用时给了就用它”，并允许纠正之前的模型 | 拒绝切换：限制主 agent；静默忽略：违背意图 | 跨 provider 切换有格式风险，已用 MessageNormalizer 兜底 |
| ADR-07 | v1 只接受精确模型 id，不做别名 | 别名需要额外的映射和冲突规则，且 core 没有目录 | 支持 `sonnet` 等别名：需要新的解析层和歧义策略 | 用户必须使用配置中的 id |
| ADR-08 | 不新增 subagent UI / per-type 配置 | 现有 profile 和 `agent.sub` 已能表达长期策略 | 新增 UI：超出本次需求；新增配置：与现有字段重复 | 用户需要编辑配置文件 |
| ADR-09 | server `SubAgentAssembler` 路径不改 | server 的子 agent 本来就是带 model 的 AgentDefinition | 把两条 subagent 路径合并：范围过大且风险高 | server 的 `task` 仍由网关解析模型 |
| ADR-10 | 显式 / 线程模型失败不回退；配置 / profile 模型失败回退 parent provider | 新意图要明确失败，旧配置要保持兼容 | 全部失败：会让历史配置无法运行；全部回退：显式意图被吞掉 | 两类来源的错误策略不同，需要文档说明 |

## 12. 待 Review 决策

以下问题已有建议，用户可以直接接受或按编号修改，不需要重新描述整个方案。

| ID | 待确认事项 | 本稿默认 / 建议 | 影响 |
|---|---|---|---|
| Q1 | 续跑时显式传不同模型：切换还是拒绝？ | 切换，并更新线程记录；与 Claude Code 的 resumed 行为一致 | 续跑语义、成本、provider 兼容性 |
| Q2 | 是否支持模型别名（如 `sonnet` / `fast`）？ | v1 不支持，只接受精确 id；未来在 resolver 内做确定性映射 | 参数可用性、解析复杂度 |
| Q3 | 是否需要管理员锁定 subagent 模型（禁止调用级覆盖）？ | v1 不加；如果成本/合规需要，后续加 `agent.sub.<name>.model-override=false` | 治理能力、实现范围 |
| Q4 | 是否在调用前用 hub 模型目录严格校验，而不是只依赖 LiteLLM 回退？ | v1 复用 `model/set` 规则（本地目录 + LiteLLM 回退）；hub 目录校验作为后续增强 | 错误时机、离线可用性 |
| Q5 | server 的 `task` 内置工具路径是否纳入本次验收？ | 纳入“不回归”范围；不要求 server 提前校验模型 | 测试范围、server 支持度 |
| Q6 | 线程模型不可用时：失败还是回退父模型？ | 新格式线程失败；legacy 线程尽量解析，失败时按旧行为 parent provider | 续跑可用性、可预测性 |
| Q7 | 切换模型时是否重置 profile 的 `reasoningEffort` / `temperature`？ | v1 不重置，沿用 profile 配置；由 provider 决定是否拒绝 | 部分模型组合可能失败，需要另行设计 |
| Q8 | 工具描述是否显示主 agent 当前模型？ | 显示，帮助主模型判断“省略即继承” | 工具描述 token 成本 |
| Q9 | 是否需要在 desktop / CLI 设置页暴露 subagent 模型配置？ | v1 不做，继续用 `agent.properties` / profile；若需要，单独设计 Settings 区块 | 产品可用性、范围 |
| Q10 | 主 agent 可否在用户未明确要求时自行选择更便宜/更强的模型？ | 协议允许，但工具描述提示“仅在用户要求时传”；不做代码级意图判断 | 成本、可预测性、提示词行为 |
| Q11 | 续跑未传 `model` 时，用线程模型还是父 agent 当前模型？ | 用线程模型（线程是持续对话，M6）；需要跟随父模型时显式传当前模型 | 续跑语义、成本、可预测性 |

## 13. Review 记录与修改记录

### 13.1 用户 Review 记录

用户已授权按 v0.1 设计实施；这不是对 Q1–Q11 的逐条确认，设计细节仍待 Review。

| Review ID | 日期 | Reviewer | 意见原文 / 范围 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| R-U-01 | 2026-11-13 | 用户 | 先按你的设计实现吧 | 按 v0.1 设计实施，记录实现差异；未部署、未提交 | 全篇 | CONFIRMED_FOR_IMPLEMENTATION |
| — | — | — | 逐条设计 Review 仍待补充 | — | — | Awaiting Review |

状态使用 `OPEN / ACCEPTED / REJECTED_WITH_REASON / IMPLEMENTED_IN_DOC / CONFIRMED`；ACCEPTED 表示接受修改意见，不等于用户批准全部设计。最后必须记录是否允许进入实施阶段。

### 13.2 设计自查记录

本节只记录文档自查，不作为用户批准。

- 对照 M1–M7 逐项覆盖：配置模型、默认继承、显式覆盖、provider 解析、失败策略、续跑/持久化、可观测性。
- 已核对第 3 节源码引用的文件存在性与关键行为；行号以当前工作区为准。
- 与现有代码行为逐条对照：无 `model` 参数时路径不变；profile 优先于 `agent.sub`；`/model` 切换在下一轮生效；线程 rehydrate 现在使用线程记录的 model/provider。
- v0.2 已实现并运行相关测试：`TaskToolTest`（含显式模型、provider 切换、续跑切换、rehydrate、父模型变化）、`SubagentThreadRegistryTest`（model/provider 持久化）、`ServerPermissionLifecycleTest`（工具事件模型）、`CliSubagentModelResolverTest`（CLI provider 解析）；`core-ai` 与 `core-ai-cli` 全量测试通过；`core-ai` / `core-ai-cli` 的 checkstyle 与 PMD 通过。
- 为满足单文件复杂度约束，实现把模型选择拆到 `SubagentModelSelector`，把 agent 构建拆到 `SubagentAgentFactory`；与 v0.1 设计的分层一致，只是组件名更具体。
- `core-ai-server:compileTestJava` 因工作区中已有的 `CostAlertServiceTest` / `traceCollection` 未完成改动而失败，与本设计无关；server 主代码已编译通过。
- 未部署，未创建 commit；本次实现只覆盖 `task` 工具与 CLI/ACP 装配，server 的 AgentDefinition 子 agent 路径未改。

### 13.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 | 用户确认 |
|---|---|---|---|---|---|
| v0.1 | 2026-11-13 | core-ai | 初稿：原始需求、源码现状、选择链、provider 解析、线程持久化、错误与可观测性、测试矩阵、ADR 与待 Review 问题 | 给用户完整 Review 对象；先设计后实施 | 待 Review |
| v0.2 | 2026-11-13 | core-ai | 实施：`task.model` 参数、选择链与 resolver、线程 model/provider 持久化、rehydrate、footer/active list/ToolStartEvent、CLI/ACP 装配与测试；补充实现差异与测试记录 | 用户授权按设计实施；保留设计到实现的追踪 | 已授权实施；逐条 Review 待确认 |

## 14. 参考资料

- [S1] [Claude Code — Create custom subagents](https://code.claude.com/docs/en/sub-agents)：subagent 定义的 `model` 字段、`inherit` 语义、单次调用 `model` 参数、优先级与 resumed 行为。资料获取日期：2026-11-13。
- [S2] [OpenAI Agents SDK — Agents](https://openai.github.io/openai-agents-python/agents/)；[Models](https://openai.github.io/openai-agents-python/models/)：每个 Agent 可设置 `model`，未设置时使用默认模型；运行级别可覆盖默认模型。资料获取日期：2026-11-13。

源码核对定位见第 3 节。本文没有复用项目已有 design 文档的方案结构；仅参考了用户指定文档的治理与章节组织方式。


