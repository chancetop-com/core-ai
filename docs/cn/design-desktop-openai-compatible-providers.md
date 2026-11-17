# Core AI Desktop 直连 OpenAI 兼容模型厂商（DeepSeek 优先）— Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Implemented / v0.5（2026-11-17） |
| 文档版本 | v0.5 |
| 创建日期 | 2026-11-17 |
| 需求提出者 / Reviewer | 用户 |
| 作者 | core-ai |
| 范围 | Desktop 本地会话直连 OpenAI 兼容 chat 端点（DeepSeek 预设优先）、独立的 provider 凭据存储、Settings UI、模型列表与会话模型选择 |
| 设计依据 | 用户原始需求、当前工作区源码（含 desktop / CLI / core-ai 框架）、DeepSeek API 已知约束 |
| 明确不在范围 | core-ai-server 代码、server gateway、图片/视频/语音的直连、Anthropic/原生 Gemini 协议 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> 设计让desktop支持直连OPENAI兼容的厂商 主要就是deepseek

> 且添加这个auth不影响切换到其它env 就是尽量不要操作agent.properties 我希望agent.properties以后不处理认证的东西

### 0.2 需求编号

| ID | 要求 | 性质 |
|---|---|---|
| N1 | Desktop 能在界面上添加/编辑/删除一个 OpenAI 兼容的直连模型厂商；DeepSeek 是首要预设 | 必须 |
| N2 | 直连厂商的 API key / base URL 不写入 `agent.properties`；认证数据独立存放 | 必须 |
| N3 | 直连配置与 core-ai-server 的登录/环境切换（dev / uat / prod）互不影响：切换 env 不改直连配置，直连配置不改登录凭据 | 必须 |
| N4 | 未登录 core-ai-server 时，仅靠直连厂商即可完成会话 | 必须 |
| N5 | 已登录时，直连模型与 server 模型能在同一个 Model 下拉里区分并选择；会话级模型选择保持现有语义 | 必须 |
| N6 | 先交付设计，用户 Review 后才实施 | 工作边界 |

### 0.3 文档维护规则

- 0.1 节用户原文保持不变；后续解释、调整和新增需求单独记录，不覆盖历史。
- Review 意见使用稳定 ID，记录处理方案与状态；没有用户确认不得写成「用户批准」。
- 字段名、IPC 名、默认值以实施后的代码为准；本文不代替代码注释和验收记录。
- 本文只覆盖 desktop 本地会话（app-server engine）的 chat 直连；server、CLI 只在共享存储/兼容层被顺带提及。

## 1. 目标、边界与核心提案

### 1.1 核心提案

新增一份**用户级、与登录无关的 provider 凭据库** `~/.core-ai/llm-providers.json`，把「模型厂商凭据」从 `agent.properties` 中彻底拆出来：

- **存储**：直连 profile（type / base URL / API key / 模型清单 / 默认模型 / reasoning 能力）只存在 `llm-providers.json`；`agent.properties` 不再写入任何 `*.api.key`。
- **写入方**：desktop 主进程（Electron main）负责 Settings UI 的增删改，不依赖已连接的 workspace；CLI 交互式配置与 legacy 全局 `model/set` 使用同一个 schema 的 Java store 写入。engine 自己不写凭据。
- **读取方**：app-server / CLI / ACP 在 bootstrap 时读取同一份文件，构造现有的 `LiteLLMProvider`（所有 OpenAI 兼容厂商共用这一实现）。
- **身份模型**：v1 复用现有 `LLMProviderType` 作为 provider 身份（DeepSeek → `deepseek`，任意自定义兼容端点 → `openai`，OpenRouter → `openrouter`）；同一 type 只允许一个 profile。这样会话 sidecar（`models.json`）、子代理 `.provider` 配置以及 `model/list` / `model/set` 的现有语义全部不变。
- **默认连接显式化**：`llm-providers.json` 记录 `default: "server" | "<profile-id>"`。登录/切 env 不会偷偷改写它；新会话按它选择默认 provider，会话内仍可用 Model 下拉覆盖。
- **模型列表合并**：已登录时 `model/list` 同时返回 server 模型与直连模型，每条带 `source` / `providerType`；`model/set` 增加可选 `provider`，消除重名模型的歧义。

**初衷与理由：** 用户要的是「不登服务器也能用自己的 DeepSeek key 干活」，同时「登录/切 env 不要把这条链路带坏」。因此凭据必须与 `auth.json`（server 登录）、`agent.properties`（非认证调优）三者分离；desktop 的 Settings 必须在没有 workspace、没有登录的情况下也能配置直连厂商，否则首次使用会卡在「没有 provider 就发不出消息、发不出消息就建不了会话」的死循环里。

### 1.2 目标与不变量

1. 未登录、无 `agent.properties` 凭据时，配置一个 DeepSeek profile 后可以新建会话并完成真实回合。
2. `llm-providers.json` 是直连凭据的唯一写入目标；provider 凭据不再写入 `agent.properties`（不提供从 `agent.properties` 导入/迁移的入口，用户 2026-11-17 决定）。
3. `auth.json` 只承载 server 登录；登录、切 env、登出都不触碰 `llm-providers.json`。
4. 直连 profile 的 base URL / key 只发给该 profile 的端点，不借用其它环境的 server 凭据，也不把 login key 回落到第三方端点。
5. API key 在任何 RPC / UI / 日志里都不回显完整值；文件系统层面做力所能及的收紧（POSIX 0600）。
6. 已有 `agent.properties` 里的 `<type>.api.key` 继续被读取（兼容），但不再产生新的写入；不提供迁移/导入入口。
7. 新增 provider 不改变 server 端任何 API、权限或计费路径；图片/视频/语音仍走 server 登录（非目标）。
8. 一次用户动作最多导致一次 engine 重载；正在运行的回合不被静默杀掉。

### 1.3 非目标

- 不做多厂商「同一 type 多个命名端点」；那需要把 `LLMProviderType` 的身份抽象成可命名 provider，属于后续 refactor（见 Q1 / ADR-02）。
- 不做 Anthropic Messages、原生 Gemini、AWS Bedrock 等非 OpenAI 协议直连。
- 不做图片生成、视频生成、语音转写的直连；这些继续依赖 core-ai-server 的 gateway。
- 不自动探测模型能力（vision / tool calling / context window）；DeepSeek 预设按已知能力保守声明。
- 不把 API key 上传到任何 server、trace 或 telemetry。
- 不实现 OS keychain / DPAPI 加密（Java 引擎无法解密 Electron `safeStorage`；见 ADR-06、Q4）。
- 不自动删除 `agent.properties` 里的历史凭据；也不提供迁移/导入入口（用户 2026-11-17 决定）。legacy key 只做只读兼容。
- 不改 server gateway 的模型目录、计费与审计。

## 2. 现状核对与接入约束

本节只依据当前源码与本机事实，不把「计划新增」写成已有能力。

| 现状 | 源码证据（工作区相对路径:行号） | 对设计的约束 |
|---|---|---|
| Provider 类型是固定 enum：DEEPSEEK / OPENAI / AZURE / AZURE_INFERENCE / OPENROUTER / LITELLM / GATEWAY | `core-ai/src/main/java/ai/core/llm/LLMProviderType.java:6-13` | v1 的 profile 身份必须复用 type；同一 type 不能并存多个端点 |
| 所有 OpenAI 兼容厂商共用 `LiteLLMProvider`（HTTP/SSE，`{base}/chat/completions`，Bearer auth） | `core-ai/src/main/java/ai/core/llm/providers/LiteLLMProvider.java:154-165`、`:266-284` | 直连不需要新协议实现；只需要新的 provider 来源 |
| Provider 由 `AgentBootstrap` 从 properties 构造；DeepSeek 固定 `https://api.deepseek.com/v1`，OpenAI 必须提供 `openai.api.base` | `core-ai/src/main/java/ai/core/bootstrap/AgentBootstrap.java:219-229`；`core-ai/src/main/java/ai/core/bootstrap/LLMProviderFactory.java:15-53` | 新来源必须在 bootstrap 之后/之中注入并覆盖同 type 的 legacy provider |
| `LLMProviders` 是 `EnumMap<LLMProviderType, LLMProvider>`，默认 provider 靠 `setDefaultProvider(type)` | `core-ai/src/main/java/ai/core/llm/LLMProviders.java:21-47` | 默认连接只能落到一个已注册 type 上；`server` 对应 LITELLM |
| app-server 的配置链：全局 `agent.properties` → workspace `.core-ai/agent.properties` → 登录注入的内存 fallback | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java:148-160`；`core-ai-cli/src/main/java/ai/core/cli/CliAppHelper.java:164-185` | 登录 fallback 只写内存，不落盘；直连 store 可以作为第四条独立来源 |
| 登录凭据在 `~/.core-ai/auth.json`；切 env 只改该文件并关掉全部 engine | `core-ai-cli/src/main/java/ai/core/cli/auth/AuthConfig.java:30`、`:97-114`；`core-ai-desktop/src/main/main.ts:792-800`、`:816-827` | 只要直连 store 是另一个文件，切 env 天然不影响它；engine 重启正好让新配置生效 |
| `model/list` 已登录时只返回 server gateway 模型（`source=server`），未登录才回退本地 registry（`source=local`） | `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java:218-245` | 需要合并两种来源，并解决同名模型的路由歧义 |
| 未登录时 `model/set` 只接受本地 registry 里的模型；登录后任何未知模型都按 LITELLM 路由 | `AppServerEngine.java:329-350` | 直连模型必须进 registry，否则选不中；同时要防止 server 同名模型被本地优先命中 |
| 本地模型条目来自 `ModelRegistry`：`<type>.models` + provider 默认 model | `core-ai-cli/src/main/java/ai/core/cli/config/ModelRegistry.java:21-37` | store 的 `models` / `defaultModel` 必须注入 registry |
| 会话级模型选择存在 sidecar `models.json`（`{model, provider}`），provider 是 type 名字；重建时按 type 匹配 | `core-ai-cli/src/main/java/ai/core/cli/appserver/SessionModelStore.java:23-79`；`EngineSessionModels.java:40-71` | provider 身份保持 type 名，则 sidecar 完全不用迁移 |
| CLI `/model add provider` 和 `saveActiveModel` 直接把 base/key/model 写进 `agent.properties` | `core-ai-cli/src/main/java/ai/core/cli/config/ProviderConfigurator.java:70-80`、`:194-248` | 这是 N2 的主要违例点；CLI 侧要同步改成写 store |
| Desktop Settings 是 userData 下的 `settings.json`，通过 `settings:get/set` 读写；engine RPC 是通用 `engine:call` 白名单 | `core-ai-desktop/src/main/main.ts:756-781`、`:1100-1109`；`core-ai-desktop/src/preload/preload.ts:6` | 新增 IPC 走既有显式白名单；不需要新的渲染层文件访问 |
| Desktop Model 下拉把模型按 `model` 字符串去重，`model/set` 只传 model | `core-ai-desktop/src/renderer/app.ts:1652-1664`、`:2235-2269` | 合并两种来源后必须改为 `provider + model` 身份，否则同名模型会被吞掉 |
| Media 设置已有成熟的 RPC 模式：读文件、校验、`AtomicFiles` 落盘、返回 `restartRequired` | `core-ai-cli/src/main/java/ai/core/cli/appserver/MediaSettingsApi.java:47-96`、`:185-200` | provider 设置可以复用同样的写入/返回语义（但写入方放在 main，见 ADR-04） |
| `reasoning_effort` 由 `thinking/set` 写进 `<prefix>.request.extra_body`；请求时 extra_body 会覆盖模型字段 | `core-ai-cli/src/main/java/ai/core/cli/agent/AgentSessionRunnerHelper.java:24-47`、`:135-162`；`LiteLLMProvider.java:277-284` | DeepSeek 不接受 `max`/`none`（见 §5.6），直连时必须做 level → wire value 映射，否则第一轮就 400 |
| 文件原子写已有 `AtomicFiles`（temp + fsync + rename） | `core-ai/src/main/java/ai/core/utils/AtomicFiles.java:39-65` | store 写入直接复用，避免半写文件 |
| Desktop 启动时会连接一个 workspace 并启动 engine；但空白窗口 / 默认模式可以没有 workspace | `core-ai-desktop/src/renderer/app.ts:8699-8709`、`:2223-2225` | provider 配置不能依赖「当前 workspace 的 engine 已就绪」 |

**接入决策与理由：** 直连配置写入 desktop main（无 workspace 依赖、无登录依赖），engine 只读；CLI 用 Java 实现同一 schema 的写入。这样 UI 在首次运行、未登录、无 workspace 时都能完成配置，而 engine 仍然只有一个「启动时读文件」的简单模型。

## 3. 方案总览

### 3.1 组件边界

```text
┌──────────────────────────── Desktop renderer ────────────────────────────┐
│ Settings → Model providers                                               │
│   connection: (•) Direct · DeepSeek   ( ) core-ai server                 │
│   profiles: DeepSeek  https://api.deepseek.com/v1  key ••••1234          │
│             [Edit] [Test] [Delete]          [+ Add provider]             │
│                                                                          │
│ composer Model 下拉：Server · <model> / Direct · DeepSeek · <model>      │
│   ── IPC providers:*（preload 白名单）────────────┐                      │
└───────────────────────────────────────────────────┼──────────────────────┘
                                                    │
┌──────────────────────────── Desktop main ─────────▼──────────────────────┐
│ ProviderStore.ts       读/写/掩码/预设/校验                              │
│ ProviderProbe.ts       GET {base}/models（测试连接、拉模型清单）          │
│                                                                          │
│  写入 ~/.core-ai/llm-providers.json（atomic rename, 0600 best-effort）    │
│  保存后：restart 当前/空闲 engine；运行中的回合结束后再重载               │
└───────────────────────────────────────────────────┬──────────────────────┘
                                                    │ 文件（唯一跨进程契约）
┌──────────────────────────── app-server engine ────▼──────────────────────┐
│ EngineBootstrap: LlmProviderStore.load() → 覆盖/新增 LiteLLMProvider      │
│   → ModelRegistry 注入 profile 的 models/defaultModel                    │
│   → 默认连接按 store.default 解析                                         │
│ model/list 合并 server + direct；model/set 接受 provider                  │
└──────────────────────────────────────────────────────────────────────────┘
```

| 组件（拟新增/改动） | 责任 | 不应承担的责任 | 初衷与理由 |
|---|---|---|---|
| `ProviderStore.ts`（main，新增） | 读写 store、校验、预设、key 掩码 | 不发起 chat 请求、不管理 server 登录、不改 `agent.properties` | 无 workspace/未登录也能配置；单文件、原子写 |
| `ProviderProbe.ts`（main，新增） | `GET {base}/models` 的测试连接与模型发现 | 不保存结果、不把失败当阻塞 | 测试与保存解耦；失败仍允许手动填模型 |
| `LlmProviderStore.java`（CLI，新增） | 同一 schema 的 Java 读取/写入，供 engine 与 CLI 共用 | 不处理 UI、不读 `auth.json` | engine 启动读取；CLI 交互配置与 legacy 全局 set 有落点 |
| `ProviderWiring`（CLI，新增） | 把 store 应用进 `LLMProviders` 与 `ModelRegistry`、解析默认连接 | 不改 provider 实现、不写凭据 | 三个入口（EngineBootstrap / CliApp / ACP）共用一段逻辑 |
| `AppServerEngine.modelList/modelSet`（改） | 合并模型来源、按 provider 路由、默认连接解析 | 不改写 profile 凭据 | 读路径保持简单；legacy 全局 set 才写 store |
| Settings → Model providers（renderer，改） | 展示/编辑/测试/删除、默认连接单选 | 不读文件、不接触 key 明文 | 与现有 Settings 交互一致 |
| composer Model 下拉（renderer，改） | 以 provider+model 为条目身份；空状态引导 | 不决定 provider 可用性 | 同名模型不丢、路由不歧义 |

### 3.2 数据流与加载顺序

1. Desktop 启动 → 用户打开 Settings → Model providers；main 直接读 `~/.core-ai/llm-providers.json`，不需要任何 engine。
2. 保存 profile → main 原子写文件 → 按 §5.7 触发 engine 重载。
3. 任一 workspace 的 engine 启动时：
   - `EngineBootstrap.loadProperties()`（全局 + workspace + 登录内存 fallback，现有逻辑不变）；
   - `AgentBootstrap.initialize()`（现有 legacy provider）；
   - `LlmProviderStore.load()` → 对每个 profile 构造 `LiteLLMProvider` 并 `addProvider(type, provider)`（同 type 覆盖 legacy）；
   - `new ModelRegistry(providers, props, store)` 注入 profile 的模型；
   - `ProviderWiring.applyDefault(providers, signedIn)` 设置默认 type。
4. 会话创建 / resume 仍走现有路径；会话级 pick 由 `models.json` sidecar 恢复。
5. 登录 / 切 env / 登出只改 `auth.json` 并重启 engine；第 3 步重跑时 store 内容不变 → 直连配置原样恢复。

### 3.3 三种「认证」的边界（这是 N2/N3 的核心）

| 概念 | 文件 | 内容 | 谁能写 | 与 env 切换的关系 |
|---|---|---|---|---|
| Server 登录 | `~/.core-ai/auth.json` | server URL、`api_key`、用户、insecure | desktop main（login/switch/logout）、CLI `/login` | 切 env 只改 active 标记 |
| 直连厂商凭据 | `~/.core-ai/llm-providers.json` | base URL、vendor API key、模型清单、默认连接 | desktop main（Settings）、CLI 交互配置 | **完全无关**，切 env 不读写该文件 |
| 非认证调优 | `~/.core-ai/agent.properties` + workspace 覆盖 | 模型名、temperature、timeout、extra_body、max.turn、memory 开关 | CLI/engine（thinking、max.turn） | 与 env 无关；**不再写入任何 api key** |

> 注：`litellm.api.base` / `litellm.api.key` 目前仍可能出现在老用户的 `agent.properties` 里，作为 legacy 兼容读取；新设计不删除、不新增这类写入。见 §10。

## 4. 数据模型与存储

### 4.1 文件位置

`~/.core-ai/llm-providers.json`（Windows：`%USERPROFILE%\.core-ai\llm-providers.json`）。

- 常量放在 `PathUtils`（CLI）与 main 的 `providersFile()`；`user.home` 可被测试用 `-Duser.home` 隔离。
- 不放在 workspace 的 `.core-ai/` 下：这是用户级凭据，不应随仓库/工作区复制或提交。
- 不写进 desktop `settings.json`：CLI 与 engine 都需要读同一份数据。

### 4.2 Schema（v1）

```json
{
  "version": 1,
  "default": "deepseek",
  "serverModel": null,
  "profiles": [
    {
      "id": "deepseek",
      "type": "deepseek",
      "label": "DeepSeek",
      "baseUrl": "https://api.deepseek.com/v1",
      "apiKey": "sk-...",
      "models": ["deepseek-chat", "deepseek-reasoner"],
      "defaultModel": "deepseek-chat",
      "reasoningLevels": ["low", "high", "max"],
      "reasoningEffortMap": { "low": "low", "high": "high", "max": "xhigh" }
    }
  ]
}
```

| 字段 | 必填 | 语义 | 约束 |
|---|---|---|---|
| `version` | 是 | schema 版本 | v1 固定 `1`；未知版本 → engine 只读保守解析，main 提示升级 |
| `default` | 否 | 新会话默认连接 | `"server"` 或 profile `id`；缺失 = 走 legacy 解析（§5.3） |
| `serverModel` | 否 | server 连接下的默认模型 | `default="server"` 时用于 `model/get`；为空则回落到 `litellm.model` / `llm.model` |
| `profiles[].id` | 是 | profile 稳定标识 | v1 必须等于 `type`（见 §4.3） |
| `profiles[].type` | 是 | `LLMProviderType` 名字 | v1 只允许 `deepseek` / `openai` / `openrouter`；拒绝 `litellm`（server 代理槽）、`gateway`、`azure`、`azure-inference`（协议/base 形态不同） |
| `profiles[].label` | 否 | UI 显示名 | 默认用 type 名 |
| `profiles[].baseUrl` | 是 | OpenAI 兼容根路径 | `http(s)://`；缺 `/v1` 由用户负责（与 LiteLLMProvider 一致） |
| `profiles[].apiKey` | 是* | vendor key | 允许空（本地无鉴权端点）；空 key 时请求不带 Authorization |
| `profiles[].models` | 否 | 该端点可用模型 | UI 拉取或手填；engine 会去重后注入 registry |
| `profiles[].defaultModel` | 否 | 该 profile 默认模型 | 为空则用 type 的静态默认（如 DeepSeek `deepseek-chat`） |
| `profiles[].reasoningLevels` | 否 | 该 provider 接受的内部 level | 用于 UI 收窄 Thinking 下拉与 `thinking/set` 校验 |
| `profiles[].reasoningEffortMap` | 否 | 内部 level → wire value | 缺省为恒等映射；DeepSeek 预设 `max → xhigh` |

\* `apiKey` 允许空字符串，但 UI 保存语义是「不传/为空 = 保持原值」，清空必须显式 `clearApiKey: true`（与 server 的 keepExistingSecret 语义一致，避免编辑 label 时把 key 悄悄清掉）。

### 4.3 为什么 v1 的 `id == type`

现有系统里 provider 的身份就是 `LLMProviderType`：`LLMProviders` 的 key、`ModelRegistry.ModelEntry.providerType`、`models.json` 的 `provider`、`agent.sub.<name>.provider` 全部是 type 名。若允许 `id` 自由命名，就必须同时改这些路径（以及 CLI `--provider` 语义），风险和范围都会显著变大。

v1 的取舍：

- DeepSeek 独占 `deepseek`；
- 任意自定义 OpenAI 兼容端点用 `openai`；
- OpenRouter 用 `openrouter`；
- 因此同时最多「DeepSeek + 一个自定义兼容端点 + OpenRouter」（如需更多，走 Q1 的后续 refactor）。

UI 不暴露 `id` 编辑；新增时按 type 生成 `id`，同 type 已存在则进入编辑而不是新建。

### 4.4 校验与错误

| 场景 | 行为 |
|---|---|
| `type` 非法 / 重复 | `providers:save` 返回可读错误，不写文件；v1 允许的 type 见 §4.2 |
| `baseUrl` 不是 http(s) | 拒绝保存；本地 `http://localhost` 允许 |
| `defaultModel` 不在 `models` 中 | 允许（模型目录可能不完整），但 UI 提示 |
| `default` 指向不存在的 profile | 读取时回落 §5.3 的顺序，UI 显示「default 已失效」 |
| 文件损坏（JSON 解析失败） | main：显示错误，禁用保存，提供「Reset store」；engine：warn + 按空 store 处理，不影响已有的 legacy provider |
| 文件版本更高 | main：只读展示，保存按钮禁用；engine：尽力解析已知字段 |
| 并发写（main 与 CLI） | 原子写 + 每次写前重读；用户级配置、低频操作，接受 last-writer-wins；见 ADR-10 |

### 4.5 文件权限

- POSIX：创建/覆盖后 `Files.setPosixFilePermissions(file, 600)`（best-effort，失败只 warn）。
- Windows：文件位于用户 profile 下，继承用户目录 ACL；不额外设置 ACL。**这不是对同用户其它进程的安全边界**，与现有 `auth.json` / `agent.properties` 同级；文档明确说明。
- 不支持把 key 加密（理由见 ADR-06 / Q4）。

## 5. 引擎接入

### 5.1 `LlmProviderStore`（core-ai-cli/config）

```java
public final class LlmProviderStore {
    public static LlmProviderStore load();                 // 全局路径
    public static LlmProviderStore load(Path file);        // 测试 / 自定义 home 场景
    public List<LlmProviderProfile> profiles();
    public String defaultConnection();                     // "server" | "<id>" | null
    public String serverModel();
    public Optional<LlmProviderProfile> profile(LLMProviderType type);
    public void saveProfile(LlmProviderProfile profile);   // CLI 交互配置用
    public void setDefaultConnection(String connection);
    public void setDefaultModel(LLMProviderType type, String model);
}
```

- 读取：缺失文件 → 空 store；解析失败 → warn + 空 store（不 quarantine，避免与 main 的显式 Reset 冲突；main 负责坏文件的重置流程）。
- 写入：`AtomicFiles.writeString`；写前重读、只改目标字段，保留不认识的字段（向前兼容）。
- key 的日志脱敏：任何日志只打印 `type` + `baseUrl` 的 host + `hasApiKey`。

### 5.2 应用 profile（`ProviderWiring`）

```java
final class ProviderWiring {
    static void apply(BootstrapResult result, PropertiesFileSource props, LlmProviderStore store) {
        for (var profile : store.profiles()) {
            var config = LLMProviderFactory.providerConfig(props, profile.type().getName());
            config.setModel(resolveProfileModel(props, profile));   // 见 R-05：不回落到 llm.model
            config.setMultiModalModel(null);          // 直连 provider 不假设多模态能力（v1）
            applyReasoningConfig(config, profile);    // §5.6
            result.llmProviders.addProvider(profile.type(), new LiteLLMProvider(config, profile.baseUrl(), profile.apiKey()));
        }
        // serverModel 必须写进运行中的 litellm config，否则 UI 显示与实际推理模型不一致（R-06）
        if (store.serverModel() != null && result.liteLLMProvider != null) {
            result.liteLLMProvider.config.setModel(store.serverModel());
        }
    }
}
```

要点：

1. `LLMProviderFactory.createProviderConfig` 目前是 private；设计上将其提升为包可见/公共静态方法，避免复制 `<prefix>.*` 的属性解析逻辑（temperature / timeout / extra_body / model extra bodies）。
2. store profile **后注册**，同 type 覆盖 legacy provider → store 优先；legacy `<type>.api.key` 只在没有 profile 时生效。
3. `multiModalModel` 置空：直连 v1 不假设 vision；避免继承 `azure/responses/gpt-5-mini` 这类不存在的 model（见 §11.5 已知限制）。
4. 除从 `<prefix>.*` 继承的非认证调优外，不额外设置 embedding / multimodal 默认值；DeepSeek 无 embeddings，相关功能按现有容错降级（`Memory.generateEmbedding` 已 catch）。
5. **profile 默认模型解析顺序**：`profile.defaultModel` → `props.<type>.model` → `LLMProviders.getProviderDefaultChatModel(type)`。**不能**从 `llm.model` 继承 `deepseek/deepseek-v4-flash` 这类 gateway 格式的名字（`LLMProviderFactory` 的全局默认），否则直连端点会 400/404。
6. **默认 provider 由 `ProviderWiring.resolveDefaultType` 统一设置**，替代现有的 `EngineBootstrap.applyActiveProvider` / `CliApp.applyActiveProvider` / `AcpAgentRunner.restoreActiveProvider`；这三个旧方法必须删除或改为调用同一解析器，否则会在 ProviderWiring 之后覆盖 `store.default`（R-01）。
7. 覆盖同 type 的 provider 后，同步刷新 `BootstrapResult` 的别名（`deepSeekProvider` / `openAIProvider` / `openRouterProvider`），避免 DI 绑定、inline-image 限额或测试持有旧实例。
8. 新建的 `LiteLLMProvider` 要补 `result.llmTracer`（`AgentBootstrap.configureLLMProviders` 原本会给每个初始 provider 注入 tracer；覆盖后不注入会丢 trace）。

调用点（四个入口必须一致）：

| 入口 | 位置 |
|---|---|
| app-server（desktop） | `EngineBootstrap` 构造函数，`bootstrap.initialize()` 之后，替换 `applyActiveProvider` |
| CLI REPL / `--prompt` | `CliApp.initializeSession`，`AgentBootstrap.initialize()` 之后，替换 `applyActiveProvider` |
| ACP | `AcpAgentRunner.run`，`bootstrap.initialize()` 之后，替换 `restoreActiveProvider` |
| CLI 内置工具运行时 | `LocalToolRuntime.load(...)`（`LocalToolRuntime.java:95-111`）同样会构造 provider，必须走同一 `ProviderWiring`，否则 `decide` / 子工具仍看到 legacy 默认 |

`CliSubagentModelResolver.create(props, providers)` 和 `CliApp` 里独立构造的 `ModelRegistry` 也要接收 store（见 §5.4）。

> 顺序细节：`ProviderWiring.apply` 必须发生在 `CliAppHelper.configureInlineImages(...)` **之前**，这样新增的直连 provider 才能继承 CLI 的 4MB inline-image 限额；`resolveDefaultType` 放在 `configureInlineImages` 之后、原来的 `applyActiveProvider` 位置即可。

### 5.3 默认连接解析

`ProviderWiring.resolveDefaultType(store, providers, signedIn)`：

```text
① store.default == "<id>" 且该 profile 已注册        → 该 type
② store.default == "server" 且 litellm 已注册       → LITELLM
③ store.default 缺失（老文件/首次）：
     agent.properties 的 active.provider 且已注册    → 该 type（legacy 兼容）
④ store 有 profile                                → 第一个 profile
⑤ 登录中且 litellm 已注册                          → LITELLM
⑥ 已注册的任意 provider                            → 其 type（保持现有 EnumMap 行为）
```

- `model/get`（无 sessionId）按同一顺序返回默认 model：profile.defaultModel → `props.<type>.model` → `LLMProviders.getProviderDefaultChatModel(type)`；server 连接用 `store.serverModel` → `litellm.model` → `llm.model` → null。
- `model/set`（不带 sessionId，legacy 全局语义）不再写 `agent.properties`：改为 `store.setDefaultConnection(type)` + `store.setDefaultModel(type, model)`；server 则写 `store.default="server"` + `serverModel`，并同步 `liteLLMProvider.config.setModel(model)` 供同一 engine 内的新会话使用。desktop 不使用这条路径（仍是会话级），但 CLI 会用。
- 没有可用 provider（`providers.getProviderTypes().isEmpty()`）时，`session/create` 必须返回业务错误 `NO_PROVIDER_CONFIGURED`，而不是让 `LLMProviders.getProvider()` 抛裸 `IllegalStateException`（见 §7.3 / R-09）。

### 5.4 模型列表合并

`model/list` 的响应：

```json
{
  "source": "mixed",
  "models": [
    { "model": "deepseek-v4-flash", "provider": "gateway", "providerType": "litellm", "source": "server", "displayName": "..." },
    { "model": "deepseek-chat", "provider": "deepseek", "providerType": "deepseek", "source": "direct", "reasoningEfforts": ["low", "high", "max"] }
  ]
}
```

规则：

- 已登录且 hub 返回模型：server 条目 + **仅 store.profiles 产生的** direct 条目；`source="mixed"`。
- **不要把 `ModelRegistry` 里 `LITELLM` 的条目当作 direct**：`LLMProviderFactory` 会给所有 provider 继承 `llm.model = "deepseek/deepseek-v4-flash"`，登录时 litellm 一定有这个默认 model；若混入 direct，会出现幽灵模型且 `source` 误报。`LITELLM` 只属于 server 来源（或未登录时 legacy local 回退）。
- 仅一种来源：保持 `source="server"` 或 `source="local"`（向后兼容 desktop 旧逻辑与 CLI）。未登录且只有 legacy `litellm.api.base` 时，`source="local"` 仍按现有 registry 展示。
- `providerType` 是路由身份：server 条目一律 `litellm`（实际路由就是 litellm 代理），direct 条目是对应 type。
- `provider` 保留展示用名字（server 的 gateway providerName / direct 的 label）。
- 去重键 = `providerType + model`；同名模型在两个来源各保留一条，desktop 显示 `model (DeepSeek)` / `model (Server)`。
- direct 条目的 `reasoningEfforts` 来自 profile.reasoningLevels（无声明则省略，desktop 保持当前「全部 level」行为）。
- `model/set` 新增可选 `provider`（type 名）。有 `provider` 时按它解析；无 `provider` 时保持 legacy（registry 命中 → 该 type；否则登录中 → LITELLM）。desktop 始终带 `provider`。
- **`model/set` 的校验规则**：provider 必须已注册；model 必须非空；**不要求** model 一定出现在 profile.models 中（目录可能过期，用户手填新模型名是合法操作）；若 profile.models 非空且 model 不在其中，返回 `warn` 字段供 UI 提示但**不拒绝**。未知 provider 返回 `MODEL_UNKNOWN`。
- `model/get`（无 sessionId）额外返回 `provider`（type 名）与 `source`（server/direct），供下拉恢复选中项。`serverModel` 为空时按 §5.3 的 server 解析链回落，不隐式等于 `model/list` 的第一项。

### 5.5 会话 / 子代理 / compression

- `models.json` sidecar 不变：`{model, provider}`，`provider` 仍是 type 名。profile 删除后 `applyStored` 的现有 warn + 回落逻辑继续生效。
- 子代理：`agent.sub.<name>.provider` 仍是 type 名；未显式配置时继承父 agent（现有语义），直连 provider 天然可用。
- compression 的 `updateModel(provider, model)` 不需要改：它拿的是 provider 实例。
- CLI `--model <name>` 仍只切模型不切 provider；若该模型属于 direct profile 且当前 provider 不匹配，行为保持现状（文档提示：跨 provider 选模型用 `/model` 或 desktop 下拉）。

### 5.6 DeepSeek 的 reasoning_effort 兼容（必须）

已知约束：DeepSeek 接受 `low/medium/high/xhigh`，拒绝 `max` 和 `none`（`.core-ai/knowledge/reference/deepseek-api.md:13-16`）；而 core-ai 内部 level 是 `none/low/high/max`（`core-ai/src/main/java/ai/core/llm/domain/ReasoningEffort.java:12-20`）。当前 thinking 只是把字符串写进 `extra_body`，没有厂商映射，所以直接连 DeepSeek 且当前 level 为 `max` 时第一轮就会 400。

设计：

1. profile 的 `reasoningLevels` 是 UI/校验层的允许集合；DeepSeek 预设 `["low","high","max"]`。
2. profile 的 `reasoningEffortMap` 把内部 level 映射成 wire value；DeepSeek 预设 `max → xhigh`，`low/high` 恒等。无此字段的 profile 使用恒等映射。
3. `thinking/get` 与 `thinking/set` 都接受可选 `sessionId` / `provider`：优先 sessionId 对应会话的 provider，其次显式 provider，最后按 `default` 连接解析；**不再依赖 `agent.properties` 的 `active.provider`**。desktop 的调用传当前 sessionId；空白窗口没有 sessionId 时传 `pendingModel.provider`，避免把直连模型的 effort 写进 server 前缀。
4. `thinking/set` 写入的键是当前 provider 前缀的 `<type>.request.extra_body`（不再是全局 `llm.*`），写入前用 profile 的映射转换；level 不在允许集合则返回 `INVALID_PARAMS` 并列出允许值。desktop 的 Thinking 下拉已经按 `model/list.reasoningEfforts` 收窄，正常操作不会撞到这条校验。
5. `thinking/get` 读取同一前缀并反向映射 wire value → 内部 level；对直连 profile，前缀不存在即 `off`（不继承全局 `llm.request.extra_body`，否则显示的 level 与实际请求不一致）；对 legacy/非 profile provider，前缀不存在时回落到 `llm.request.extra_body`。
6. 构建直连 provider config 时，**先从继承的 base `llm.request.extra_body` / `llm.request.extra_body.<model>` 中剥掉 `reasoning_effort`**（不把全局 level 透给厂商），再应用 `<type>.request.extra_body` / 模型级配置并按 profile 映射 wire value。这样：
   - 用户选 `off` 时前缀键不存在 → 请求里没有 `reasoning_effort`，不会因为 base 里残留 `max` 又发出去；
   - 用户选 `low/high/max` 时前缀键显式覆盖，`max` 在 DeepSeek 上映射为 `xhigh`；
   - base 里的其它 extra_body 键（`top_p` 等）仍然继承。
7. 无 `reasoningLevels` 声明的自定义 profile 不做校验/映射（厂商未知，保持现状）。

> 实现提示：`LLMProviderConfig.modelExtraBodies` 是 private，`resolveExtraBody(model)` 只返回 Map；需要增加一个「拷贝 base 时剥离 reasoning_effort」的构造路径（在 `LLMProviderFactory` 拷贝 base config 时过滤，而不是在请求期改 body）。注意 `LLMProviderConfig` 的拷贝构造共享 `requestExtraBody` 对象引用，过滤时必须构造新的 Map，不能原地改（否则会污染同源 provider 的 base 配置）。

**理由：** 这是 DeepSeek 作为首选厂商的「首次体验」问题，不能留给用户自己踩 400 后再猜。映射表是 provider 元数据，放在 store 比放在 `agent.properties` 更合适。

### 5.7 生效与重载

- store 在 engine **启动时读一次**；保存后必须重载 engine 才生效（与 `thinking/set`、`maxturn/set` 的 `restartRequired` 语义一致）。
- **重载编排放在 main / `EngineSupervisor`，不要依赖 renderer 的 `mountedView` 状态**。现有 `pendingEngineRestart` 只在被查看的会话结束回合时触发（`app.ts:5594-5599`），同 workspace 的后台会话、其它 workspace 的回合、channel bridge 或 schedule 触发的回合都可能被 `shutdownAll()` 误杀（R-04）。
- 建议实现：
  1. `EngineSupervisor` 维护每个 workspace 的「活跃回合数」与 `pendingReload` 标记（它已经通过 `onEvent` 收到 `status_change` / `turn_complete` / `error`）。计数按 `sessionId` 的 running 状态幂等更新：`status_change(running)` 只置位、`turn_complete`/`error` 只清零，避免同一回合双事件把计数减错。
  2. 保存 provider 后，main 对空闲 engine 立即 dispose；对忙碌 engine 只置 `pendingReload`，在最后一个回合终止事件后 dispose 并按需重新启动。
  3. 每次由 main 主动 dispose 后向 renderer 推一条 `engine:reloaded {workspace}`（main → renderer push）；renderer 收到后对当前打开的会话调用 `session/resume`（已保存过的会话）并 `refreshModelState()`。从未产生保存点的新会话无法 resume：清掉 live session 指针、保留当前 transcript 作为草稿并提示重新发送（避免下一次发送直接 `SESSION_NOT_FOUND`）。
  4. `auth:switch` 的 `shutdownAll` 仍可保留（用户显式切换环境时，强制重载是预期行为）；provider 保存不再复用这条粗暴路径。
- CLI / ACP 下次启动自然读取新文件。
- 不做文件监听热重载：跨进程、跨 workspace 的 watcher 复杂度和收益不成正比。

## 6. Desktop 主进程接口（IPC / preload）

### 6.1 IPC 列表

| IPC | 入参 | 返回 | 用途 |
|---|---|---|---|
| `providers:list` | — | `{ signedIn, defaultConnection, serverModel, profiles, storePath, error? }` | Settings 初始化；`profiles` 不含完整 key |
| `providers:save` | `{ profile, apiKey?, clearApiKey?, makeDefault? }` | `{ ok, profiles, defaultConnection, restartRequired }` | 新增/编辑 profile；原子写；校验同 §4.4 |
| `providers:delete` | `{ id }` | `{ ok, profiles, defaultConnection, restartRequired }` | 删除 profile；`default` 指向它时回落到 server/第一个 profile |
| `providers:setDefault` | `{ connection }` | `{ ok, defaultConnection, restartRequired }` | `"server"` 或 profile id；server 未登录时拒绝 |
| `providers:test` | `{ type, baseUrl, apiKey?, id? }` | `{ ok, status, latencyMs, models?, error? }` | GET `{base}/models`；`apiKey` 不传时使用已保存值 |
| `providers:discover` | 同上 | `{ ok, models, error? }` | 拉模型清单；失败不阻塞手动输入 |

preload 增加对应方法（`providerList` / `providerSave` / `providerDelete` / `providerSetDefault` / `providerTest` / `providerDiscover`）。renderer 不获得 `fs` / `path` / 原始 HTTP 能力。

main → renderer 的推送事件：`engine:reloaded { workspace }`，用于 provider 保存后告诉 renderer 重载已完成，触发 `session/resume` 与 `refreshModelState()`（见 §5.7）。preload 增加 `onEngineReloaded(cb)` 订阅。

### 6.2 `providers:list` 的 profile 形状

```json
{
  "id": "deepseek",
  "type": "deepseek",
  "label": "DeepSeek",
  "baseUrl": "https://api.deepseek.com/v1",
  "models": ["deepseek-chat", "deepseek-reasoner"],
  "defaultModel": "deepseek-chat",
  "reasoningLevels": ["low", "high", "max"],
  "hasApiKey": true,
  "apiKeyHint": "••••1a2b"
}
```

- `apiKeyHint` 只保留末 4 位；少于 8 位时显示 `••••`，不泄露长度以外的信息。
- 不返回 `legacy` 字段，也不提供从 `agent.properties` 导入的入口（用户 2026-11-17 决定）；legacy key 由 engine 静默兼容读取。

### 6.3 保存语义

- `apiKey` 缺省 / `null` / 空串 → 保持原值（编辑非 key 字段时不会丢 key）。
- **`profile` 对象里不接受 `apiKey` 字段**：key 只从独立的 `apiKey` 入参进入，避免整对象被日志或回显带出。
- `clearApiKey: true` → 删除 key（允许保存无鉴权端点）。
- `profile.id` 缺省时按 `type` 生成（v1 两者相同）；同 `type` 已存在时返回错误 `PROVIDER_EXISTS`（UI 引导进入编辑）。
- 保存成功后 main 重写文件，并返回 `restartRequired: true`；重载由 main 按 §5.7 的编排执行，renderer 不直接调用 `shutdownAll`。
- `makeDefault: true` → 同时把 `default` 设为该 profile。**只在当前没有有效默认连接时**（未登录且 `default` 为空/失效）自动采用；已登录且默认是 server 时，新增 profile 不改变默认连接，UI 只显示 `Set as default` 让用户显式切换（避免添加直连厂商时被静默切走 server）。

### 6.4 测试连接

- 请求：`GET {baseUrl}/models`，`Authorization: Bearer <key>`（key 为空则不带），超时 8s。
- `baseUrl` 末尾的 `/` 先去掉；若用户填的已经是 `.../chat/completions`，直接报 `baseUrl should be the API root, not the completions path`，不猜。
- 成功：`ok=true`，解析 `data[].id` 作为 `models`；不修改任何文件。
- 失败：返回 `status`（HTTP 或 0）+ 截断到 300 字符的错误摘要；UI 提示「保存仍可用，请确认 base URL/key」。
- 不发送 chat completion（避免计费）；部分兼容端点不支持 `/models` 时属于已知限制，用户可跳过测试。

### 6.5 文件写入细节

- 读取 → 修改 → 写同目录临时文件 → `fsync` → `rename` 覆盖。Node 侧用 `openSync` + `writeFileSync` + `fsyncSync` + `closeSync` + `renameSync`（与 Java `AtomicFiles` 的 temp + force + atomic move 等价）；Windows 上 rename 覆盖同一卷可行。
- 目录不存在时 `mkdirSync(recursive)`。
- POSIX `chmodSync(file, 0o600)` best-effort；失败只记日志。
- 坏文件：`providers:list` 返回 `error` + `profiles: []`；保存被拒绝，UI 提供 `Reset store`（把坏文件改名为 `llm-providers.json.bad-<ts>`，然后写空 store）。这样不会把用户手写的半截配置静默覆盖掉。
- **跨语言一致性**：在 `core-ai-desktop/test/fixtures/llm-providers.json` 与 `core-ai-cli/src/test/resources/llm-providers.json` 放同一份 fixture；TS 与 Java 的 roundtrip 测试都断言读出的 profile/default 相同，防止两侧字段名漂移。

## 7. Desktop UI 与交互

### 7.1 Settings 新分区位置

`Settings` 面板内，放在 **Desktop** 之后、**Media generation** 之前：

```text
Model providers
  Connection
    ( ) core-ai server — signed in as stephen (disabled when signed out)
    (•) Direct — DeepSeek

  Providers
    DeepSeek        https://api.deepseek.com/v1      key ••••1a2b     [Edit] [Test]
    + Add provider

  Uses a vendor API key stored on this machine (~/.core-ai/llm-providers.json).
  It is independent of core-ai-server sign-in and environment switching.
```

- 顶部 `Connection` 是「新会话默认连接」，不是 per-session 开关；会话内仍可用 composer 的 Model 下拉覆盖。
- **该分区不依赖 `state.workspace`**：`renderProvidersSection()` 与 Media generation 不同，空白窗口/未连接时也要渲染；数据全部来自 main IPC，不调用 engine。
- `core-ai server` 行始终显示；未登录时禁用并显示 `Sign in from the profile menu`；不自动弹登录框。
- 没有 profile 时显示空状态和 `Add provider`；如果同时未登录，`server` 行仍以禁用态展示，避免用户以为选项消失。空状态不预选任何连接。

### 7.2 Add / Edit provider 对话框

字段：

| 字段 | 交互 |
|---|---|
| Provider | 预设下拉：`DeepSeek` / `OpenAI-compatible` / `OpenRouter`；编辑时只读 |
| Label | 可选，默认预设名 |
| Base URL | DeepSeek/OpenRouter 预填并可改（兼容代理）；自定义必填 |
| API key | 密码输入框；已有 key 时 placeholder 为 `••••1a2b (saved)`；留空 = 保持 |
| Models | 一行一个或逗号分隔；`Fetch models` 调 `providers:discover`，失败保留手填 |
| Default model | 下拉（来自 models + 手填候选）+ 允许直接输入 |
| Reasoning levels | 高级项；预设已填时折叠展示，说明 `levels this endpoint accepts` |

按钮：`Save`、`Test connection`、`Cancel`；编辑态额外 `Delete`（二次确认）。

行为：

- `Test connection` 使用当前表单值，不需要先保存；测试失败不阻止保存。
- `Save` 成功后关闭对话框、刷新 provider 列表、按 §5.7 触发重载；toast：`Provider saved — engine reloads`。
- 删除默认 profile：`default` 回落到 server（已登录）或下一个 profile；toast 说明新默认值。
- 未保存的修改在关闭时丢弃（与现有 Settings 表单一致）。

### 7.3 Composer Model 下拉

- 选项按来源分组：`Server · <gateway provider>` / `Direct · <label>`；同名模型显示 `model (DeepSeek)` / `model (Server)`。
- 选项身份 = `providerType + model`（renderer 内部用索引/复合 key），选中后 `model/set { model, provider }`。
- **空白窗口的 pending pick 必须带 provider**：`state.pendingModel` 从 `string` 改为 `{ model: string; provider?: string } | null`；`startNewSession` 发送 `model/set { sessionId, model, provider }`（`app.ts:2519-2524`）。否则空白窗口选 direct 模型后首条消息会走无 provider 的 legacy 路径（R-03）。
- 当前值来自 `model/get`：优先 `provider` + `model` 匹配；找不到时按 model 字符串回退（兼容旧 engine）。
- **Thinking 条目的查找也要用 `provider + model`**：`app.ts:2276` 现在只按 `entry.model === state.currentModel` 找 `reasoningEfforts`，server 与 DeepSeek 同名时会命中错误条目；改为复合键，并在模型切换后重建 Thinking 控件（当前 `modelSelect.onchange` 只更新 `currentModel`，不重绘，下拉会停留在旧模型的 level 集合）。
- 空白窗口改 Thinking 时 `thinking/set` 传 `pendingModel.provider`（§5.6），没有 pending pick 则不传，由引擎按默认连接解析。
- Thinking 下拉按当前条目 `reasoningEfforts` 收窄；无声明则保持现有 `none/low/high/max/off`。
- 空状态：没有可用模型时下拉禁用并显示 `No provider configured`；点击/旁边的提示打开 `Settings → Model providers`。发送拦截要分两种情况：**已连接 workspace 且 `model/list` 为空** → 发送前提示并给 Settings 入口；**没有 workspace（默认模式）** → 允许发送首条消息去创建 workspace，由 engine 的 `NO_PROVIDER_CONFIGURED` 返回可读错误并提示配置，不能把首次建会话也堵死（R-09）。

### 7.4 与现有 Settings 的一致性

- 复用 `.settings-section`、`.btn small`、`.hint`、`.notice` 等样式；亮/暗主题走 CSS 变量。
- 不新增模态框架；沿用 `openLoginDialog` 的 overlay/dialog 结构。
- 文案英文（与 desktop UI 一致），不添加 emoji。

## 8. 与 server 登录 / 环境切换 / workspace 的关系（N3）

### 8.1 动作矩阵

| 用户动作 | `auth.json` | `llm-providers.json` | `agent.properties` | engine |
|---|---|---|---|---|
| Sign in / 新增 server | 写 | 不读写 | 不写 | 关闭重连（现有） |
| 切换 env（dev/uat/prod） | 改 active | 不读写 | 不写 | 关闭重连（现有） |
| Sign out | 删除该条 | 不读写 | 不写 | 关闭重连（现有） |
| 添加/编辑/删除直连 provider | 不读写 | 写 | 不写 | 空闲则重载；忙则延迟 |
| 切换默认连接（server/direct） | 不读写 | 写 | 不写 | 同上 |
| composer 选模型 | 不读写 | 不写 | 不写 | 会话内立即生效（runtime） |
| Thinking / Max turns | 不读写 | 不写 | 写（非认证） | 重载（现有） |

### 8.2 关键结论

1. **切换 env 不会丢直连配置**：两个文件、两套语义；`auth:switch` 只做 `writeAuthEntries` + `shutdownAll`（`core-ai-desktop/src/main/main.ts:792-800`）。
2. **直连配置不会钉住 server env**：新设计从不写 `litellm.api.base` / `litellm.api.key`；登录 fallback 仍然只是内存注入（`CliAppHelper.injectLiteLLMFallback`）。
3. **默认连接不被登录隐式改写**：登录后 `default` 仍是用户选的值；只有用户点 `Connection` 才变。若 `default="server"` 但当前未登录，解析回落到直连 profile（§5.3）。
4. **workspace 不承载凭据**：store 是用户级的；workspace `.core-ai/agent.properties` 不会覆盖 profile 的 base/key，但其中的非认证调优（`<type>.temperature`、`<type>.request.extra_body`、`<type>.model` 等）仍会被 `ProviderWiring` 继承并作用于直连 provider。Settings 的 provider 页不展示这些 workspace 值，只提示「tuning still comes from agent.properties」。
5. **多 workspace**：每个 engine 启动时读同一份 store；保存后由 main 统一重载空闲 engine，后台调度下次唤醒时自然拿到新配置。
6. **登出后仍可工作**：只要直连 profile 存在，`model/list` 的 local 部分仍返回 direct 模型，`model/set` 按 `provider=deepseek` 路由。

### 8.3 与现有 legacy 行为的冲突点

- 老用户可能同时在 `agent.properties` 有 `deepseek.api.key`、在 `auth.json` 有登录。旧行为默认 provider 取决于 `active.provider` / EnumMap 顺序；新行为由 `store.default` 或 §5.3 顺序决定。legacy key 仍被 engine 读取；Settings 不提供导入/迁移入口，用户如果新增同 type 的 profile，store 会覆盖 legacy（但不会删除原文件内容）。
- 老用户可能在 `agent.properties` 写死了 `litellm.api.base`（指向某个 env 的 proxy）。这会让登录 fallback 失效（现有行为，不是本设计引入）。Settings 只做只读检测与提示：`Legacy litellm.api.base found; it pins chat to that endpoint.` 提供 `Open agent.properties`（用系统默认编辑器）而不是由 app 改文件。

## 9. 安全与隐私

| 面 | 设计 |
|---|---|
| 静态存储 | `llm-providers.json` 与 `auth.json` 同级；POSIX 0600 best-effort；Windows 依赖用户目录 ACL；明确不是同用户进程间的安全边界 |
| key 回显 | `providers:list` 只返回 `hasApiKey` + 末 4 位；保存请求里的 key 不落日志；`providers:test` 的请求头不打印 |
| 日志 | 只记录 `type`、host、`hasApiKey`、HTTP status；不记录完整 URL query、不记录 Authorization |
| 端点隔离 | 每个 profile 的 key 只发往自己的 `baseUrl`；禁止「直连端点缺 key 时回落到 `litellm.api.key`」的逻辑（现有 media 的 `mediaKey` 已采用同样的配对原则，见 `CliAppHelper.java:259-273`） |
| 传输 | 复用 `LiteLLMProvider` 的现有 HTTP 行为；不新增信任开关；本地 http 仅用于 `localhost` 类端点 |
| 外部外发 | 直连请求从本机直接发往厂商，不经过 core-ai-server；server 看不到这些请求，也不会产生 server trace/计费 |
| 附件/历史 | 直连时消息历史与附件与普通 CLI 相同；不把 workspace 路径、文件内容额外发给厂商（由 agent 正常行为决定，不做新的外发） |
| 迁移 | 不做导入/迁移入口（用户 2026-11-17 决定）；legacy key 由 engine 静默兼容读取，profiles 里不复制 `agent.properties` 的内容 |

**明确说明：** 该功能让用户把第三方的 API key 存在本机，这是用户显式配置的行为；与 `auth.json` 一样是明文文件。设计不宣称它是加密保险箱。

## 10. 迁移、兼容与回滚

### 10.1 读取兼容（不破坏现有用户）

1. 没有 `llm-providers.json` → 完全走现有逻辑（`LLMProviderFactory` + `agent.properties`），行为不变。
2. 有 store 且某 type 有 profile → store 覆盖该 type 的 legacy provider；其它 type 的 legacy provider 保留。
3. `active.provider` 仍被读取，但只在 `store.default` 缺失时作为默认连接的 fallback（§5.3）。
4. `models.json`（会话级 pick）不需要迁移：provider 仍是 type 名。
5. CLI 交互配置（`/model add provider`、`/model add model`、`/model remove model`）从读写 `agent.properties` 改为写 store；旧的 `<type>.api.key` / `<type>.models` 继续可读。`ProviderConfigurator` 的 `configure`、`saveActiveModel`、`addModelToProvider`、`removeModelFromProvider` 四处都要迁移，不能只改前两处（R-08）。
6. `ProviderConfigurator.hasFixedApiBase` 目前把 DEEPSEEK / OPENROUTER 的 base 写死；既然 §7.2 允许兼容代理，CLI 侧也应对这两个 type 允许自定义 base（默认值仍预填官方地址）。
7. workspace 的 `.core-ai/agent.properties` 仍可覆盖 `<type>.model` / `<type>.request.extra_body` / temperature；Settings 只提示「tuning still comes from agent.properties」，不展示、不修改这些值，避免用户排查不到模型/参数变化。

### 10.2 不做迁移入口（用户 2026-11-17 决定）

- Settings → Model providers **不提供**「从 agent.properties 导入/迁移」按钮，也不显示 legacy key 提示；`providers:list` 不返回 legacy 信息。
- engine 仍按 §10.1 读取 `<type>.api.key` / `litellm.api.*`；用户新增同 type 的 profile 后，store 覆盖 legacy 行为，但**不修改、不删除** `agent.properties`。
- 愿意清理老 key 的用户自行编辑文件；app 不提供一键删除（避免误删导致回退旧版不可用）。
- 这样做的理由：用户明确不需要迁移；少一条写 `agent.properties` 的代码路径，也少一个「导入时 key 被复制/泄露」的面。

### 10.3 降级（用户回退旧版 desktop/CLI）

- 因为新设计不删除、不改写 `agent.properties` 里的旧凭据，旧版仍能按原方式启动并使用 legacy provider。
- 如果用户只在 store 里新增了 profile、没有配置 legacy key，旧版看不到该直连能力；这是预期行为，文档在 release note 说明。
- `llm-providers.json` 对旧版是不可见文件，不影响启动。

### 10.4 发布与版本

- 本改动同时涉及 `core-ai-cli`（provider store、model/list、model/set、thinking）与 `core-ai-desktop`（main IPC、UI）。
- desktop 的 `coreAi.pinnedEngine` 必须升到包含新 CLI 的版本；`providers:*` 是 main IPC，旧 engine 也能打开 Settings，但旧 engine 不读 store，直连模型不会出现在 `model/list`。发布时把两者放同一个 release note，并确认 `syncBundledCli()`（`main.ts:557-581`）同步的是新 CLI 包。
- CLI 版本单独发布时：旧 desktop + 新 CLI 也不会坏（旧 desktop 不写 store；新 CLI 能读 legacy）。
- 文档：更新 `docs/cn/manual/index.md` 的「路线 B：不使用 Server 模型代理」，把 `agent.properties` 教程改为「Settings → Model providers（或 CLI 交互配置）」，并说明 `agent.properties` 只保留非认证调优。

### 10.5 回滚

- 删除 `llm-providers.json` + 重启 engine → 回到纯 legacy 行为。
- 不需要数据迁移脚本；文件是全新的，旧文件不受影响。

## 11. 验证与验收

### 11.1 单元测试

**Java（core-ai-cli）**

- `LlmProviderStore`：缺失文件、空文件、正常 roundtrip、坏 JSON、未知字段保留、`version` 更高、同 type 重复、`default` 指向不存在 id。
- key 语义：不传/空 = keep、`clearApiKey` = remove、替换新值；掩码输出只有末 4 位。
- `ProviderWiring`：store 覆盖 legacy；legacy-only 不变；默认连接解析 ①–⑥ 全分支；`server` 未登录回落；`serverModel` 写进 litellm config；profile 默认模型不继承 `llm.model`。
- `ModelRegistry`：profile.models + defaultModel 注入；同名模型不同 type 都保留；`getProviderType(model, provider)`；`LITELLM` 不被标记为 direct。
- `model/list` 合并：未登录 local、登录 mixed、server providerType=litellm、direct reasoningEfforts、无 profile 时不出幽灵 direct。
- `model/set`：带 provider 的直连路由；未知 provider → `MODEL_UNKNOWN`；model 不在 profile.models → 成功但带 warn；server 模型在未登录时不可用。
- 零 provider：`session/create` 返回 `NO_PROVIDER_CONFIGURED`，不是 `IllegalStateException`。
- thinking：level → wire 映射（`max → xhigh`）、允许集合校验、未知值回落 off、继承的 base / type / model 级 extra_body 重写、`thinking/get|set` 的 sessionId 解析。
- 共享 fixture：`core-ai-cli/src/test/resources/llm-providers.json` 与 desktop fixture 同内容，Java/TS roundtrip 结果一致。
- 回归：没有 store 时全部现有测试通过（provider 构造、subagent、session model）。

**Desktop（Node test）**

- `ProviderStore`：schema 校验、往返、坏文件、原子写（含 fsync 路径）、key keep/clear、mask、preset 默认值、`default` 回落。
- `ProviderProbe`：`/models` 正常/4xx/超时/非 JSON/空 key/误填 completions 路径；错误截断。
- 共享 fixture：与 Java 侧同一份 JSON，roundtrip 字段一致。
- settings 兼容：旧 `settings.json` 没有 provider 字段时不受影响（store 是独立文件）。
- renderer 纯函数：pendingModel `{model, provider}` 编码/恢复、复合键去重、无 provider 的发送拦截。

### 11.2 集成测试（stub OpenAI server）

用本地 stub（`GET /models` + `POST /chat/completions` SSE）拉起 app-server：

1. 未登录 + 空 `agent.properties` + store 有 DeepSeek profile → `model/list` 返回 direct；`model/set {provider:"deepseek"}` → `session/send` 成功；请求体带 `Authorization: Bearer stub`。
2. 登录一个 stub server（`auth.json`）→ `model/list` 变为 mixed；server 模型可选；切回 direct 模型仍可用。
3. 切换 env（改 `auth.json` active）→ engine 重启后 store 不变、direct 模型仍在、会话 pick 仍生效。
4. 登出 → 直连仍可用；server 模型消失。
5. 保存 provider 后 `agent.properties` 的哈希不变（除 thinking/maxturn 的显式操作外）。
6. 删除 profile → 会话 sidecar 指向已删除 type 时 warn 并回落默认，不崩溃。
7. 同名模型（server 与 direct 都有 `deepseek-chat`）→ 两个条目、各自路由正确；空白窗口选 direct 后首条消息带 provider。
8. 零 provider（无登录、无 store、无 legacy key）→ `session/create` 返回 `NO_PROVIDER_CONFIGURED`，UI 有可读提示；配置 provider 后同一流程成功。
9. server hub 不可达/超时 → `model/list` 回落到 direct（`source="local"` 或 mixed 中 server 为空）且直连回合可用。
10. 升级场景：`agent.properties` 有 `llm.request.extra_body={"reasoning_effort":"max"}` + store 有 DeepSeek profile、未设置 DeepSeek 前缀 → 请求体**不带** `reasoning_effort`（base 值被剥离，不 400）；随后 `thinking/set max`（带 sessionId）→ 请求体是 `xhigh`；`thinking/set off` → 又回到不带该字段。
11. profile.defaultModel 为空时，请求模型是 DeepSeek 的静态默认（`deepseek-chat`），不是 `deepseek/deepseek-v4-flash`。

### 11.3 手工 / 真实厂商验收

- 真实 DeepSeek key：保存、Test connection、新建会话、发一条普通消息；Thinking 选 low/high，确认不再出现 `Invalid value: 'max'`；选 off 后请求不带 `reasoning_effort`。
- 在 DeepSeek 会话中切到 server 模型，再切回来；会话 pick 保持。
- 切换 dev/uat 环境后，DeepSeek profile 仍在且可直接使用。
- 断网/错误 key：请求失败信息可读；不影响其它 provider 和 UI。
- 删除 DeepSeek profile 后，Settings 显示 server（若已登录）或空状态。

### 11.4 需求验收矩阵

| 需求 | 验收点 |
|---|---|
| N1 | Settings 可新增/编辑/删除 DeepSeek 与自定义兼容端点；无 workspace 也可操作 |
| N2 | 保存 provider 后 `agent.properties` 哈希不变；文件中无 `*.api.key` 新增 |
| N3 | 登录/切 env/登出前后 `llm-providers.json` 字节不变；切 env 后直连模型仍可用 |
| N4 | 未登录、无 legacy key 时，仅靠 store 可完成真实回合 |
| N5 | 登录后 Model 下拉同时出现 server/direct，可区分选择；同名模型不丢 |
| N6 | 本文先 Review；Review 前不改业务代码 |

### 11.5 已知限制（v1 明确接受）

- 同一 type 只能一个自定义端点（`openai` 槽位）；多个自定义兼容厂商需要后续 provider 身份 refactor。
- DeepSeek 直连是纯文本 chat；图片理解/生成、视频、语音仍依赖 server 登录或其它 provider。模型是否支持 tool calling / vision 由厂商决定，默认 `deepseek-chat`（支持工具）；切到 `deepseek-reasoner` 等模型前需自行确认能力。
- embeddings 相关能力（memory 语义检索等）在 DeepSeek-only 配置下会失败/降级；现有 `Memory.generateEmbedding` 已 catch 并 warn。
- 不做 OS keychain 加密；文件是明文（与 `auth.json` 一致）。
- CLI 与 desktop 同时编辑 store 时 last-writer-wins；不做跨进程锁。
- 不做 `llm-providers.json` 热重载；必须重载 engine。
- DeepSeek 的 reasoning 映射基于当前已知 API 约束，厂商变更时需要更新预设（代码常量，不是用户配置）。

## 12. 关键决策记录（ADR 摘要）

| ID | 决策 | 初衷/理由 | 主要备选及为何未选 | 代价 |
|---|---|---|---|---|
| ADR-01 | 直连凭据独立存 `~/.core-ai/llm-providers.json`，不写 `agent.properties` | 用户明确要求；workspace 的 `agent.properties` 可能被提交/共享，key 不应进那里 | 继续存 `agent.properties`：违背 N2，且 env/workspace 覆盖会互相污染 | 多一个文件与两套写入实现 |
| ADR-02 | v1 复用 `LLMProviderType` 作为 provider 身份；同 type 一个 profile | 不改 `LLMProviders` / sidecar / 子代理 / CLI 语义，改动面最小 | 命名 provider registry：要重构 identity、sidecar、CLI provider 参数，风险和范围过大 | 自定义兼容端点只能有一个；多端点等 v2 |
| ADR-03 | `default`（默认连接）显式存储，登录不隐式切换 | 用户要的是「直连不被 env 切换影响」；隐式切换会让新会话忽然换 provider | 登录后默认走 server：会静默改变用户配置 | 用户需要手动切默认连接 |
| ADR-04 | UI 的增删改由 desktop main 执行；engine 启动读取，凭据只由 main/CLI 写 | Settings 必须在无 workspace/未登录时可用；engine RPC 依赖 workspace | 走 `providers/*` engine RPC：空白窗口/未连接时不可用 | 文件格式需要 TS/Java 两侧保持一致（需共享 fixture 测试） |
| ADR-05 | Model 下拉合并 server+direct，条目身份 `providerType+model`；`model/set` 带 provider | 同名模型必须能区分路由；现有按 model 去重会吞条目 | 只列一个来源：已登录用户看不到直连模型，违背 N5 | renderer 与协议字段要改，旧 engine 需回退逻辑 |
| ADR-06 | key 明文落盘 + POSIX 0600；不做 keychain | Java engine 无法解密 Electron `safeStorage`；`auth.json` 已是同级先例 | DPAPI/keychain：engine 读不到；自建加密密钥：与密文同库存放，收益有限 | 同用户进程可读；文档明确声明 |
| ADR-07 | 不迁移、不删除 legacy key，也不提供导入入口 | 用户明确不需要迁移；不改 `agent.properties` 正是其要求；旧版降级仍可用 | 自动导入/一键清理：多写路径、误删风险、无用户价值 | 用户需自行决定是否清理老 key |
| ADR-08 | 直连 v1 只覆盖 chat，媒体/语音仍走 server | 避免把 media gateway、计费、job 记录一起搬进 desktop | 全模态直连：范围与风险成倍 | 直连用户暂时不能用图片/视频生成 |
| ADR-09 | DeepSeek 声明 reasoning 允许集合与 wire 映射（`max → xhigh`） | 否则首选厂商第一轮就 400；映射属于 provider 元数据 | 只把 `max` 从下拉移除：用户失去能力；交给用户猜：体验差 | 多一个 profile 字段与 thinking 改造 |
| ADR-10 | 两侧写入都做原子替换与写前重读，接受 last-writer-wins | 用户级低频配置；跨语言文件锁复杂度高 | 分布式锁/单写进程：收益低 | 极端并发下可能丢一次编辑 |

## 13. 决策记录（原待 Review 项）

2026-11-17 用户决定：**Q2 不需要（不做迁移入口），其余按本稿建议执行**。下表保留问题、建议与最终决定。

| ID | 事项 | 本稿建议 | 最终决定（2026-11-17） | 影响 |
|---|---|---|---|---|
| Q1 | 是否在本期支持「多个自定义 OpenAI 兼容端点」 | 否；v1 一个 `openai` 槽位，后续做命名 provider refactor | 按建议：v1 不支持 | 范围/改动面 vs 灵活性 |
| Q2 | 是否提供从 `agent.properties` 导入 legacy key | 显式 Import + 二次确认删除 | **不做**：无导入/迁移入口，也不删除 legacy key | 用户便利 vs 可回退性 |
| Q3 | 登录后是否应自动把默认连接切到 server | 否；默认连接只由用户切换 | 按建议：不自动切换 | 可预测性 vs 直觉 |
| Q4 | 是否接受明文 key 文件（0600） | 是；与 `auth.json` 一致，keychain 不在本期 | 按建议：接受 | 安全预期 |
| Q5 | v1 预设是否包含 OpenRouter（以及 Azure OpenAI） | 包含 OpenRouter；Azure 暂不做 | 按建议 | UI 复杂度 vs 覆盖 |
| Q6 | provider 保存后是否重启所有 workspace 的空闲 engine | 是；忙碌的等回合结束再重载 | 按建议 | 后台调度及时性 |
| Q7 | CLI `/model add provider` 是否与本改动同版本迁移到 store | 是；否则 CLI 仍会写 `agent.properties` | 按建议：同版本迁移 | 发布耦合度 |
| Q8 | 已登录时是否必须在 Model 下拉显示 direct 模型 | 是；按来源分组并标注 | 按建议 | N5 的解释 |
| Q9 | Test connection 是否只做 `GET /models` | 是；不发 chat 避免计费 | 按建议 | 兼容少数端点 |
| Q10 | 是否在 composer 空状态直接提供 `Add provider` 入口 | 是；点击跳到 Settings 对应分区 | 按建议 | 首次使用体验 |
| Q11 | DeepSeek reasoning 预设是否只允许 `low/high` | 允许 `low/high/max`（max 映射 xhigh） | 按建议 | 能力 vs 厂商变更风险 |

## 14. Review 记录与修改记录

### 14.1 用户 Review 记录

| Review ID | 日期 | Reviewer | 意见原文/范围 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| U-01 | 2026-11-17 | 用户 | 「Q2不需要 其它都按你说的」 | Q2 移除导入/迁移入口；Q1、Q3–Q11 按建议执行 | §10.2 / §13 / ADR-07 / §6 | CONFIRMED（关键决策，非逐条批准） |

状态使用 `OPEN / ACCEPTED / REJECTED_WITH_REASON / IMPLEMENTED_IN_DOC / CONFIRMED`；ACCEPTED 表示接受修改意见，不等于用户已经批准全部设计。

### 14.2 AI 技术评审记录（非用户批准）

2026-11-17 由 `gemini-4-hydrogen` 对照当前源码进行独立评审与 v0.2 复核。结论：三个主轴（三文件分离、复用 `LLMProviderType`/`LiteLLMProvider`、DeepSeek reasoning 兼容）方向正确；首轮发现 4 个 P0 与若干 P1/P2，复核又发现 4 个 P1，均已处理（见 14.2.1）。这不是用户批准。

| Review ID | 日期 | Reviewer | 范围 | 主要意见 | 处理 | 状态 |
|---|---|---|---|---|---|---|
| AI-01 | 2026-11-17 | gemini-4-hydrogen | 全文 + 相关源码 | 4 P0：`applyActiveProvider` 覆盖默认、`model/list` 混入 LITELLM 幽灵模型、空白窗口 pendingModel 缺 provider、`shutdownAll` 误杀后台回合；另有 6 个 P1（默认模型继承 gateway 名、serverModel 未注入、thinking 未带 sessionId、CLI 迁移不全、零 provider 崩溃、model/set 校验不清） | 逐条修订，见 14.2.1 | ACCEPTED（文档已修订） |
| AI-02 | 2026-11-17 | gemini-4-hydrogen | v0.2 复核 | R-01–R-13 全部实质处理；新增 4 个 P1：重载后 renderer 无 `engine:reloaded` 通知/回合计数口径、Thinking 条目仍按 model 匹配且空白窗口无 provider、`makeDefault` 会隐式覆盖 server、新 provider 缺 `llmTracer` 注入 | 逐条修订，见 14.2.1 R-14–R-17 | ACCEPTED（v0.3） |

#### 14.2.1 评审意见处理

| 意见 | 处理位置 | 结论 |
|---|---|---|
| R-01 `applyActiveProvider` 覆盖 `store.default` | §5.2 要点 6、调用点表；§5.3 | 删除三个旧入口的 apply/restore，统一用 `ProviderWiring.resolveDefaultType` |
| R-02 `LITELLM` 默认模型混入 direct | §5.4 规则第 2 条 | direct 只来自 store.profiles；LITELLM 只作 server/local 来源 |
| R-03 空白窗口 pendingModel 无 provider | §7.3 第 3 条；§11.1/§11.2 | pendingModel 改为 `{model, provider}`；首条消息带 provider |
| R-04 `shutdownAll` 误杀后台回合 | §5.7 | 重载编排下沉到 main/`EngineSupervisor`，按 workspace 跟踪活跃回合 |
| R-05 默认模型继承 `llm.model` | §5.2 要点 5；§11.2 第 11 项 | `profile.defaultModel → props.<type>.model → 静态默认` |
| R-06 `serverModel` 未注入 litellm | §5.2 代码与要点；§5.3 | bootstrap/global set 都同步 `liteLLMProvider.config.setModel` |
| R-07 thinking 未带 sessionId / wire 值耦合 | §5.6 第 3-7 条 | get/set 支持 sessionId；按 provider 前缀写入并按映射转换 |
| R-08 CLI ProviderConfigurator 迁移不全 | §10.1 第 5-6 条 | 四个方法全部迁移；允许 DeepSeek/OpenRouter 自定义 base |
| R-09 零 provider 发送崩溃 | §5.3 末条；§7.3 末条；§11.1/§11.2 | 前端拦截 + engine 返回 `NO_PROVIDER_CONFIGURED` |
| R-10 model/set 校验规则不清 | §5.4 校验规则；§6.4；§11 | provider 必须已注册；model 不在目录只 warn；补失败用例 |
| R-11 LocalToolRuntime / workspace 调优遗漏 | §5.2 调用点表；§8.2 第 4 条 | 补入口；明确非认证调优仍受 workspace 覆盖 |
| R-12 Node 原子写无 fsync / 模型级 extra_body | §6.5；§5.6 第 6 条 | fsync + rename；模型级 reasoning 也映射；共享 fixture |
| R-13 UI 空状态/引用细节 | §7.1；§5.4；§10.4 | server 行始终显示但禁用；serverModel 回落写清；补 `syncBundledCli` |
| R-14 重载后 renderer 无通知 / 回合计数失准 | §5.7 | main 推 `engine:reloaded`；按 sessionId 幂等维护 running 计数 |
| R-15 Thinking 条目按 model 匹配 / 空白窗口无 provider | §7.3；§5.6 | 复合键查找；模型切换后重建控件；`thinking/set` 支持 provider |
| R-16 `makeDefault` 隐式覆盖 server | §6.3 | 只在无有效默认连接时自动采用；否则显示显式 `Set as default` |
| R-17 新 provider 缺 `llmTracer` | §5.2 要点 8 | 覆盖后注入 `result.llmTracer` |

### 14.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 | 用户确认 |
|---|---|---|---|---|---|
| v0.1 | 2026-11-17 | core-ai | 初稿：需求、源码事实、独立 store、provider 注入、模型合并、IPC/UI、env 隔离、安全、迁移、验证、ADR、待 Review 决策 | 先设计后实现 | 待 Review |
| v0.2 | 2026-11-17 | core-ai | 按 AI 评审修订：去除 applyActiveProvider 覆盖、LITELLM 幽灵模型、pendingModel provider、重载编排、默认模型回落、serverModel 注入、thinking sessionId/映射、CLI 迁移、零 provider、校验规则、fsync/fixture 等 | AI 评审（gemini-4-hydrogen）发现 4 P0 + 6 P1 | 待 Review |
| v0.3 | 2026-11-17 | core-ai | 复核修订：`engine:reloaded` 通知与回合计数幂等、Thinking 复合键/空白窗口 provider、`makeDefault` 不覆盖 server、新 provider 注入 `llmTracer`；允许 type 限定为 deepseek/openai/openrouter | AI 复核（gemini-4-hydrogen）新增 4 P1 | 待 Review |
| v0.4 | 2026-11-17 | core-ai | 用户决策落地：Q2 不做迁移入口（移除 Import/legacy 提示与 `providers:list.legacy`）；Q1、Q3–Q11 按建议确认；状态改为「关键决策已确认，待实施」 | 用户 2026-11-17 决定 | 用户确认 |
| v0.5 | 2026-11-17 | core-ai | 实施记录：Java store/wiring/engine、desktop main store/probe/IPC/reload、Settings UI、模型下拉与 thinking；补充验证结果与实现偏差 | 用户 2026-11-17 指示实施 | 用户确认 |

### 14.4 设计自查记录

- N1–N6 逐项有落点（§7 / §4 / §8 / §5 / §3 / §0）。
- 第 2 节所有「现状」均来自当前工作区源码，行号在编写时核对；未把计划能力写成已有能力。
- 未涉及 server 端代码；图片/视频/语音明确列为非目标。
- 已覆盖最容易踩的三个坑：同名模型路由、`agent.properties` 残留 key、DeepSeek `reasoning_effort` 400。
- v0.2 已逐条处理 AI 评审的 4 个 P0 与 6 个 P1；v0.3 处理复核新增的 4 个 P1；v0.4 按用户决定移除 Q2 的迁移入口，其余决策确认。剩余 P2 作为实现注意事项记录在 §5.6 / §6.5 / §10.4。

## 15. 源码索引

### 15.1 现有实现

- Provider 类型与工厂：`core-ai/src/main/java/ai/core/llm/LLMProviderType.java:6-13`；`core-ai/src/main/java/ai/core/bootstrap/LLMProviderFactory.java:20-52`；`core-ai/src/main/java/ai/core/bootstrap/AgentBootstrap.java:219-229`。
- Provider 注册与默认：`core-ai/src/main/java/ai/core/llm/LLMProviders.java:21-72`。
- HTTP / SSE 实现：`core-ai/src/main/java/ai/core/llm/providers/LiteLLMProvider.java:154-165`、`:266-284`。
- CLI 启动与配置链：`core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java:103-160`、`:178-185`；`core-ai-cli/src/main/java/ai/core/cli/CliApp.java:153-160`；`core-ai-cli/src/main/java/ai/core/cli/acp/AcpAgentRunner.java:116-146`。
- 登录与运行时凭据：`core-ai-cli/src/main/java/ai/core/cli/auth/AuthConfig.java:30-63`、`:97-114`；`core-ai-cli/src/main/java/ai/core/cli/auth/RuntimeAuthConfig.java:25-52`；`core-ai-cli/src/main/java/ai/core/cli/CliAppHelper.java:164-185`。
- 模型列表 / 选择 / 侧车：`core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java:180-207`、`:218-364`；`core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionModels.java:40-71`；`core-ai-cli/src/main/java/ai/core/cli/appserver/SessionModelStore.java:23-79`；`core-ai-cli/src/main/java/ai/core/cli/config/ModelRegistry.java:21-52`。
- 交互式 provider 配置（现状写 agent.properties）：`core-ai-cli/src/main/java/ai/core/cli/config/ProviderConfigurator.java:70-80`、`:194-248`。
- Thinking / extra_body：`core-ai-cli/src/main/java/ai/core/cli/agent/AgentSessionRunnerHelper.java:24-47`、`:135-162`；`core-ai/src/main/java/ai/core/llm/domain/ReasoningEffort.java:12-40`。
- Desktop 主进程：`core-ai-desktop/src/main/main.ts:756-781`（settings）、`:790-827`（auth）、`:1100-1109`（engine:call）；`core-ai-desktop/src/main/engine/EngineSupervisor.ts:25-92`；`core-ai-desktop/src/preload/preload.ts:4-18`。
- Desktop 渲染层：`core-ai-desktop/src/renderer/app.ts:1652-1664`（model state）、`:2235-2271`（model select）、`:7005-7131`（Settings）、`:1454-1471`（env switch）、`:8699-8709`（bootstrap）。
- 文件工具：`core-ai/src/main/java/ai/core/utils/AtomicFiles.java:39-65`；`core-ai-cli/src/main/java/ai/core/cli/utils/PathUtils.java:19-24`。
- Settings RPC 参考：`core-ai-cli/src/main/java/ai/core/cli/appserver/MediaSettingsApi.java:47-96`、`:185-200`。

### 15.2 本机/外部事实

- DeepSeek API：`response_format` 仅 `json_object`；`reasoning_effort` 接受 `low/medium/high/xhigh`，拒绝 `max`/`none`（`.core-ai/knowledge/reference/deepseek-api.md:5-16`）。
- OpenAI 兼容 `GET /models` 用于发现模型与连通性测试；DeepSeek 端点 `https://api.deepseek.com/v1`。
- Desktop 现有 Settings / auth / engine 重载流程见 §2 与 §8。

## 16. 实施记录（2026-11-17）

### 16.1 落地位置

**Java / CLI**

- `core-ai-cli/src/main/java/ai/core/cli/config/LlmProviderProfile.java`（新增）：profile POJO + 预设（DeepSeek / OpenAI-compatible / OpenRouter）。
- `core-ai-cli/src/main/java/ai/core/cli/config/LlmProviderStore.java`（新增）：`~/.core-ai/llm-providers.json` 的读/写、校验、POSIX 0600、原子写；`default` 字段用 `@Property(name="default")`。
- `core-ai-cli/src/main/java/ai/core/cli/config/ProviderWiring.java`（新增）：profile → `LiteLLMProvider`、默认连接解析、`serverModel`/tracer/别名注入。
- `core-ai-cli/src/main/java/ai/core/cli/config/ModelRegistry.java`：接收 store，注入 profile 模型；新增 `getProviderType(model, provider)`。
- `core-ai-cli/src/main/java/ai/core/cli/config/ProviderConfigurator.java`：`/model` 交互式配置改为写 store；四个方法全部迁移。
- `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java`：加载/应用 store，替换 `applyActiveProvider`。
- `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java`：handlers 委托给新的 `ModelSettingsApi`。
- `core-ai-cli/src/main/java/ai/core/cli/appserver/ModelSettingsApi.java`（新增）：model list/get/set、thinking get/set、server+direct 合并、provider 路由、serverModel。
- `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java`：零 provider 时返回 `NO_PROVIDER_CONFIGURED`。
- `core-ai-cli/src/main/java/ai/core/cli/CliApp.java` / `AcpAgentRunner.java` / `LocalToolRuntime.java`：统一走 `ProviderWiring`。
- `core-ai-cli/src/main/java/ai/core/cli/agent/AgentSessionRunnerHelper.java`：provider-scoped thinking 读写。
- `core-ai/src/main/java/ai/core/llm/LLMProviderConfig.java`：`removeReasoningEffort` / `mapReasoningEffort`（先剥离继承值，再按 profile 映射）。
- `core-ai/src/main/java/ai/core/bootstrap/LLMProviderFactory.java`：暴露 `baseConfig` / `providerConfig` / `applyProviderProperties`。

**Desktop**

- `core-ai-desktop/src/main/providers/ProviderStore.ts`（新增）：store 读写、预设、掩码、keep/clear key、原子写 + fsync。
- `core-ai-desktop/src/main/providers/ProviderProbe.ts`（新增）：`GET {base}/models` 测试/发现。
- `core-ai-desktop/src/main/main.ts`：`providers:*` IPC、`providerListView`、按 workspace 跟踪运行回合的延迟重载、`engine:reloaded` 推送。
- `core-ai-desktop/src/preload/preload.ts`：provider 方法与 `onEngineReloaded`。
- `core-ai-desktop/src/renderer/app.ts`：Settings → Model providers、provider 对话框、模型下拉复合身份、pendingModel `{model, provider}`、thinking sessionId/provider、`engine:reloaded` 处理、无 provider 发送提示。
- `core-ai-desktop/src/renderer/styles.css`：provider 列表样式。

### 16.2 与设计稿的差异 / 落地选择

| 设计点 | 落地选择 | 原因 |
|---|---|---|
| 设计稿 §6.5 提到 Reset store | 实现 `providers:reset` IPC + Settings 按钮 | 坏文件可恢复，不静默覆盖 |
| 设计稿 §5.2 提到 tracing | `ProviderWiring` 给新 provider 注入 `result.llmTracer` | 覆盖 provider 后不丢 trace |
| 设计稿 §5.4 `provider` 展示名 | server 条目保留 gateway providerName；direct 用 label | 与 UI 文案一致 |
| 设计稿 §5.7 延迟重载 | main 维护 `providerRunningSessions`；空闲 engine 立即 dispose，忙的等最后回合结束 | 不依赖 renderer mountedView |
| 设计稿 §7.1 空状态 | 未登录时 server 行禁用；无 profile 显示 Add provider | 与实现布局一致 |
| 设计稿 Q2 | 未实现 Import；legacy key 仅 engine 读取 | 用户 2026-11-17 决定 |

### 16.3 验证

- `.\gradlew.bat :core-ai-cli:test :core-ai:test`：通过（含新增 `LlmProviderStoreTest`、`ProviderWiringTest`、`LLMProviderConfigReasoningTest`）。
- `.\gradlew.bat :core-ai-cli:checkstyleMain :core-ai:checkstyleMain`：通过。
- `.\gradlew.bat :core-ai-cli:spotbugsMain`：通过（`:core-ai:spotbugsMain` 仍有仓库既有的 DecisionOptions/DecisionRoute 告警，与本次无关）。
- `npm --prefix core-ai-desktop run check`：通过。
- `npm --prefix core-ai-desktop test`：207/207 通过（新增 `providerStore.test.mjs`、`providerProbe.test.mjs`）。
- `.core-ai/tasks/provider-store-smoke.py`：真实 app-server 回合通过——store 直连 DeepSeek stub、`model/list` local、`model/set` provider 路由、thinking `max → xhigh`、`off` 清除、`agent.properties` 不含 key。
- 未执行桌面 UI E2E（未重启用户正在使用的 desktop）；Node 测试 + TypeScript 检查 + 构建通过。

### 16.4 发布注意

- desktop 的 `coreAi.pinnedEngine` 需要 bump 到包含本 CLI 的版本；`syncBundledCli()` 同步新 CLI 包。
- 发布说明同时提 CLI 与 desktop；旧 desktop + 新 CLI 可工作（旧 desktop 不写 store），新 desktop + 旧 engine 不会显示 direct 模型。

