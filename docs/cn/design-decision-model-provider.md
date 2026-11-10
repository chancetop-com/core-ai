# 决策模型（Decision Model）接入技术设计 —— System One 契约，首发 Microsoft-Decision-1 + Jev

> 状态：已实现（2026-11-10）。dev E2E 已通过：`azure-decision` provider + `decision-1` 模型在 dev 注册，hub 发现、`/api/gateway/v1/decisions`（noul/choice/score）、默认路由、CLI `decide` 工具均已实测（见 `docs/validation/2026-11-10-dev-decision-model-e2e.md`）。已完成：`ai.core.decision` SPI + `SystemOneDecisionProvider`（3 profile）、`GatewayEndpointType.DECISION` / `decisionProtocol`、`GatewayDecisionProvider` + 适配器工厂、`decide` 工具与 `builtin-decision` 工具组、server session/run 装配、`POST /api/gateway/v1/decisions` 外部接口、`GET /api/hub/decision-models` hub 发现接口，以及 CLI/desktop 引擎的 hub 决策 provider + 模型目录。尚未实现：trace/计价（P1，见 D18）、§8 旁路命令安全检测（P1）、429/529 退避重试（D11 仍为 P0 透传）。
> 范围：core-ai（新 SPI + 工具 + 装配）+ core-ai-server（gateway 适配 + 路由）
> 需求：把"决策模型"作为与 chat LLM / 媒体模型并列的**第三类模型能力**接入；**同时支持 Microsoft-Decision-1（Azure Foundry，fbruatai 已部署 deployment `Decision-1`）与 Jev 1.13（TypeSafe AI）**，即"决策模型"这一族 API。
> 一句话结论：**决策模型不是 LLM，不能塞进 `LLMProvider`；且 Jev 与 Microsoft-Decision-1 同属"System One"契约（同一 `state + questions → answers` wire 协议）→ 用一套 SPI + 一个适配器 + 多个 vendor profile 同时覆盖两者**，照媒体模型（MediaProvider）的成熟骨架落地，不触碰 chat 链路。

---

## 1. 背景

### 1.1 什么是决策模型

2026-09/10，"决策模型"成为新的模型品类（System One 模型）：**不生成自由文本、不产出解释**，输入一个 `state`（文本 / JSON）+ 一组**有类型的提问**，输出**结构化判定**（概率 / 选中项 / 分数），可直接被软件消费。

- 三类问题：
  | 类型 | 语义 | 返回 |
  | --- | --- | --- |
  | `noul` | 是非门控（yes/no） | 0–1 概率（越大越 yes） |
  | `choice` | 从固定选项中选一个 | 选中项 + 每个选项的概率 + confidence |
  | `score` | 有序量表打分 | 分值（各档下标概率加权平均，可落在档位之间）+ 每档概率 + confidence |
- 一次请求可合并多个共享同一 `state` 的问题（并行判定，无需链式调用）。
- 定位：路由、分类、排序/优先级、rubric 评分（AI judging）、门控与过滤、模型/工具/agent 选择、数据标注。

### 1.2 ★ 两个决策模型的 API 是否一致？—— 同族同契约（System One）

**结论：一致到可以共用一套适配器。** Jev 与 Microsoft-Decision-1 都实现"System One"契约（Microsoft 文档原话：the request pattern is *similar to a system-one API*），原生端点路径都以 `/v1/systemone` 结尾（第三方聚合如 OpenRouter 走自己的 `/api/alpha/decisions`，见下表），请求/响应骨架完全相同。

**相同的部分（契约核心，已核实双方文档）：**
- 端点：`POST <host>/…/v1/systemone`
- 请求：`{ "model", "state" (string|object|array), "questions": { "<id>": { "type", "instructions", "criteria" } } }`
- 类型：`noul` / `choice` / `score`；`noul.criteria` = `{true,false}`（可选）、`choice.criteria` = `{option: description}`（必填）、`score.criteria` = 低→高**有序数组**（必填）
- 响应：`{ "model", "answers": { "<id>": { "type", ... } }, "usage": { input_tokens, output_tokens } }`
- `answers` 以请求里的问题 id 为键；`noul` 是 0–1 概率。

**不同的部分（vendor 差异，须由 profile + 规范化层吸收）：**

| 维度 | Microsoft-Decision-1（Azure Foundry，**已实测**） | Jev 1.13（TypeSafe AI，据文档） |
| --- | --- | --- |
| 端点路径 | `<resource>/providers/microsoft/v1/systemone` | `<host>/v1/systemone`（另有简化端点 `POST /api/v1/decisions`）；OpenRouter 两者共用 `POST https://openrouter.ai/api/alpha/decisions` |
| 认证 | `api-key: <key>`（**实测通过**）或 Entra `Bearer` | `Authorization: Bearer <key>` |
| `model` 取值 | **deployment 名**（`Decision-1`）；响应回 `microsoft-decision-1` | **模型 id**（`jev-latest` / `jev-1.13.0`），响应回版本化 id |
| `noul` 应答 | `{ "type":"noul", "noul": 0.029 }` | 同 |
| `choice` 应答 | `{ "type":"choice", "choice", "probabilities", "confidence" }` | 同 |
| `score` 应答 | `{ "type":"score", "score", "legend", "probabilities", "confidence" }` | 同 |
| 选项/档位上限 | choice **2–255**（实测报错文案）；score 档位未实测 | choice ≤ 255；score 2–10 档 |
| 上下文上限 | 未实测 | 64k/请求；state + 最长问题 32k |
| 错误码 | **400 `unsupported_request_argument`**（实测）/ 401 / 403 / 404 / **422**（不支持的 deployment 名或问题定义）/ 429 | 401 / 422 / 429 / **529 Overloaded** |
| 输入模态 | 文本 / **JSON 对象**（均实测通过）/ 数组 | 文本 / JSON / 数组（无图/音/视频） |
| 价格 | $0.042 / M 输入，输出免费 | $0.042 / M 输入，输出免费 |
| 延迟 | 本机公网实测 ~0.9–1.1s/次（含 TLS/APIM，非纯推理）；官方 in-region P50 ~85ms | ~240ms P50（JevBench v1.6.1） |

**实测结论：MS 的应答字段与 Jev 文档字段逐字段一致**（`noul` / `choice` / `score` / `confidence` / `legend` / `probabilities` 全同）→ 规范化层近乎恒等，一个适配器即可同时覆盖两者。**响应中 `model` 是底层模型名（`microsoft-decision-1`），与请求的 deployment 名不同**。

> **对设计的直接含义**：一个 `SystemOneDecisionProvider` + vendor profile（`AZURE_FOUNDRY_DECISION`、`SYSTEMONE`、`OPENROUTER_DECISIONS`）即可覆盖两者；TypeSafe 兼容 host（`api.typesafe.ai`、`jev-api.org`、`jevmodel.net`、`api.b.ai` 等）通常只是 **baseUrl（origin）不同、profile 相同**，加一行即可。**profile 必须携带「完整路径」，不能假定 `/v1/systemone` 后缀**：OpenRouter 把两者都放在 `POST https://openrouter.ai/api/alpha/decisions`（同一个 Decisions API、请求字段与 Jev 相同），固定后缀拼不出这个路径。差异全部收敛到「完整路径 / 认证头 / 应答规范化」，上层工具与调用方只看**统一的** `DecisionAnswer`。

> ✅ **Microsoft 侧已实测（2026-10-13）**：host、认证、请求/应答字段、错误码全部确认，且应答字段与 Jev **逐字段一致**（证据见 §6.1）。
> ⏳ Jev 侧仍以官方文档为准（字段名与 MS 实测相同）；如需闭环，用一个 Jev key 各跑一次即可。

### 1.3 目标 / 非目标

**目标**
- 决策模型可作为一类 gateway 模型注册、路由、探活、计价、可观测；
- **同一套适配器同时覆盖 Microsoft-Decision-1 与 Jev（及后续 System One 兼容供应商）**；
- agent 可通过工具（P0）发起决策；
- 平台内部可复用（P1，判定/评分/路由）；
- **（P1）旁路命令安全检测**：用决策模型检测 server 上执行的命令是否在做破坏集群 / 主机嗅探扫描等高风险操作（**可开关、旁路不阻塞、先上报后拦截**，见 §8）。

**非目标（本设计不做）**
- 不做决策模型的训练/微调；
- 不把决策模型当作 agent 的 `model`（它不是生成模型）；
- 不逐个对接所有第三方聚合站（只要能配 baseUrl + profile 即可，不为其写专属代码）；
- P0 不做前端管理页专属决策栏（沿用 gateway models 页，endpointType / provider 表单字段留 P1；见 §4 末段 P0 注册方式）。

---

## 2. 现状分析（已核实代码）

### 2.1 core-ai 现有的"模型能力"落点

| 类别 | 抽象（core-ai） | 判别字段（gateway） | gateway 分发 | 装配到 agent 运行 |
| --- | --- | --- | --- | --- |
| chat LLM | `ai.core.llm.LLMProvider`（消息/SSE 抽象） | provider `type`（`LLMProviderType`） | `GatewayLLMProvider` | `llmProviders.getProvider()` |
| 媒体（图/视频） | `ai.core.media.MediaProvider` | provider `mediaProtocol` | `GatewayMediaProvider` + `MediaProviderAdapterFactory` | `ExecutionContext.image/videoMediaProvider` |
| **决策（本设计）** | `ai.core.decision.DecisionProvider`（新增） | provider `decisionProtocol`（新增） | `GatewayDecisionProvider` + `DecisionProviderAdapterFactory`（新增） | `ExecutionContext.decisionProvider`（新增） |

### 2.2 `LLMProvider` 为什么不能承载决策模型

`ai.core.llm.LLMProvider` 的模板方法 `completionStream(request, callback)` 假设 `messages` 列表、`stream=true`、逐 token SSE、`CompletionResponse.choices[].message.content` 为自由文本。决策模型是 `state + questions → answers`，没有 messages、没有流式、没有文本——硬塞会污染整条 chat 抽象（preprocess/postprocess/模态裁剪/trace 全不适用）。

**结论：决策模型必须走独立 SPI，而不是新 `LLMProviderType`。**

### 2.3 媒体模型是本设计的结构先例（照抄骨架）

- SPI `ai.core.media.MediaProvider`；适配器 `ai.core.media.*MediaProvider`；工厂 `ai.core.server.gateway.MediaProviderAdapterFactory`（`mediaProtocol → adapter` switch）；
- gateway 包装 `GatewayMediaProvider`：`routingEngine.route(model, endpoint)` → 按 `provider.id:updatedAt` 缓存 adapter → 改写请求 → 调用 → 记 job/成本；
- 能力/差异按 protocol switch 声明（`MediaProviderCapabilities`）；
- 工具侧 `GenerateImageTool` 用 `context.getImageMediaProvider()`；server 侧在 `SessionContextBuilder`（会话创建+重建唯一入口）与 `AgentRunBuilder`（run 路径）装配。

### 2.4 gateway 注册与路由是通用的（可直接复用）

- `gateway_provider` / `gateway_model` 两个集合与 `GatewayRoutingEngine` 已足够通用：路由按 `GatewayEndpointType` 过滤 `endpoint_types`。
- `GatewayEndpointType` 现为：`CHAT_COMPLETIONS / RESPONSES / IMAGE_GENERATION / IMAGE_EDIT / VIDEO_GENERATION`。
- provider `type` 与 `mediaProtocol` 是**两个独立判别字段**；媒体正是"type + mediaProtocol"组合的既有例证。
- **认证不能复用 `GatewaySupport.applyAuth`**：它是 server 侧 package-private 类，而决策适配器（`SystemOneDecisionProvider`）按先例放在 core-ai，依赖方向 `core-ai-server → core-ai` 决定 core-ai 看不到它；媒体适配器也从不走它（`OpenAIImageMediaProvider` 自建 HTTP client 并硬编码 `Bearer`，`GeminiImageMediaProvider` 用构造参数 `credentialHeader` 决定头名）。
- `GatewaySupport.applyAuth(provider, request, apiKey)` 的规则本身（azure→`api-key`、gemini→`x-goog-api-key`、其余→`Bearer`）只作用于 chat 链路与 provider 探针；决策适配器照 `GeminiImageMediaProvider`，由 server 工厂按 profile 传入认证头名（`AZURE_FOUNDRY_DECISION` → `api-key`，`SYSTEMONE`/`OPENROUTER_DECISIONS` → `Authorization`）。
- `GatewayRoutingEngine.mediaModelHints(endpoint)` 已把"某 endpoint 下启用的模型"喂给工具描述动态注入。

**结论：加 `GatewayEndpointType.DECISION` + `decisionProtocol`（多 profile），其余注册/路由/缓存/探活/计价复用既有骨架，改动面小且不触碰 chat。**

---

## 3. 方案设计

### 3.1 分层与调用链

```
agent 工具 decide(...)            server 内部服务（P1）
        │                                 │
        └────────────► ExecutionContext.decisionProvider ◄────┐
                              │                               │
                  GatewayDecisionProvider (server)            │
                    ├── GatewayRoutingEngine.route(model, DECISION)
                    ├── DecisionProviderAdapterFactory (decisionProtocol → adapter)
                     │        └── SystemOneDecisionProvider (core-ai，照 GeminiImageMediaProvider：baseUrl + credential + credentialHeader)
                     │              ├─ profile AZURE_FOUNDRY_DECISION → /providers/microsoft/v1/systemone (header: api-key)
                     │              ├─ profile SYSTEMONE            → /v1/systemone (header: Authorization: Bearer)
                     │              └─ profile OPENROUTER_DECISIONS → /api/alpha/decisions (header: Authorization: Bearer)
                    ├── 应答规范化 → 统一 DecisionAnswer
                    └── 缓存 / trace / （P1）成本
```

### 3.2 core-ai 领域对象（新增包 `ai.core.decision`）

以 `record` 表达（core-ai 内部使用，不经 core-ng JSON/RPC 边界）：

- `DecisionQuestion`：`name`、`type`（`NOUL|CHOICE|SCORE`）、`instructions`、`criteria`（`Map<String,String>` 或 `List<String>`；**必须保序**，score 的数组顺序=档位下标）。
- `DecisionRequest`：`model`、`state`（**String**：原始文本或 JSON 文本）、`questions`。
- `DecisionAnswer`（**统一规范化结果**，吸收 vendor 差异）：`name`、`type`、`probability`（noul 的 0–1，规范化自 `noul`/`probability`）、`choice`、`score`、`probabilities`（`Map<String,Double>`）、`confidence`（可空）、`legend`（`Map<String,String>`，可空）、`raw`（可选，原样保留 vendor 应答便于排查）。
- `DecisionResponse`：`answers`（`Map<String,DecisionAnswer>`，保序）、`model`、`usage`（input/output tokens）、`costUsd`/`costSource`（P1）。
- `DecisionProvider`（interface）：`DecisionResponse decide(DecisionRequest request)`；`String name()`。
- `SystemOneDecisionProvider`（实现）：一个实现 + 多个 profile，构造参数即 profile 差异（`baseUrl`、`credential`、`credentialHeader`、完整请求路径），照 `GeminiImageMediaProvider(baseUrl, credential, credentialHeader)`——**不依赖 server 侧的 `GatewaySupport.applyAuth`**（core-ai 看不到它），负责请求映射与**应答规范化**（`noul`→probability、缺 `confidence`/`legend` 时置空）。

> **`Object` / 泛型陷阱（仓库既有约束）**：`state` 用 `String`（JSON 文本），`criteria` 用 `Map<String,String>` / `List<String>`，需要动态结构时用 `JsonUtil`。`score` 的 `criteria` 与 `legend` 必须保序（`List` / `LinkedHashMap`，不要 `Map.of`）。
> **规范化**：实测 MS 与 Jev 的应答字段一致（`noul` / `choice`+`probabilities`+`confidence` / `score`+`legend`+`probabilities`+`confidence`）→ 规范化近乎恒等，仅做防御性默认（缺字段置空）；保留 `DecisionAnswer` 这一层，是为了未来接入字段不同的 vendor 时上层零改动。

### 3.3 gateway 侧改动

**a) endpoint 类型**
- `GatewayEndpointType` 新增 `DECISION("/systemone", GatewayModelService.ENDPOINT_DECISION)`。注：`path` 字段当前只被 `GatewayProxyService`（拼 Azure upstream URL / span 命名）使用，P0 决策不经代理、该值无副作用；P2 做外部代理时 Azure 的路径拼接逻辑需单独处理。
- `GatewayModelService`：`ENDPOINT_DECISION = "decision"`；`endpointTypeAliases` 加 `decision` / `decision.systemone`。

**b) provider 判别字段与 profile**
- `GatewayProviderConfig`（`gateway_provider`）新增 `@Field("decision_protocol") decisionProtocol`（P1 可再加 `default_decision_model`）。
- `GatewayProviderRequest` / `GatewayProviderView` 加 `decisionProtocol`；`GatewayProviderService.apply/validate/toView` 加一行；新增 `normalizeDecisionProtocol`，P0 白名单：
  - `AZURE_FOUNDRY_DECISION` —— Azure Foundry（MS），完整路径 `/providers/microsoft/v1/systemone`，认证头 `api-key`；
  - `SYSTEMONE` —— TypeSafe 兼容（Jev 及 `api.typesafe.ai`/`jev-api.org`/`jevmodel.net`/`api.b.ai` 等），完整路径 `/v1/systemone`，认证头 `Authorization: Bearer`；
  - `OPENROUTER_DECISIONS` —— OpenRouter（Jev 与 MS 同一端点），完整路径 `/api/alpha/decisions`，认证头 `Authorization: Bearer`（最省事：一条 provider + 一个 key 同时覆盖两个模型）。
- **profile 兜底（照 `MediaProviderAdapterFactory.protocol()` 先例）**：`decisionProtocol` 为空时按 `type` 推导——`azure` → `AZURE_FOUNDRY_DECISION`，`openai`/`openai-compatible` → `SYSTEMONE`，`openrouter` → `OPENROUTER_DECISIONS`。该 factory 只在路由 `DECISION` 模型时被调用，所以按 type 兜底安全；有了它 **P0 无需新增前端字段**（否则 P0 只能靠裸 API 注册 provider，见 §6.2）。
- provider `type`：MS 用 `azure`；Jev 用 `openai`；OpenRouter 用 `openrouter`。`type` 在决策链路只用于 profile 兜底与探针选择（认证头由 profile 决定，不再经 `applyAuth`）。
- `GatewayProviderService.test()`：该 provider 下存在属于 `decision` endpoint 的 `gateway_model` 行（或 `decisionProtocol` 非空）时走**决策探针**（POST 一个最小 `noul` 问题），而不是 azure 的 `/openai/deployments`。

**c) 适配器工厂与 gateway 包装**
- 新增 `ai.core.server.gateway.DecisionProviderAdapterFactory`（仿 `MediaProviderAdapterFactory`）：`decisionProtocol → adapter`，P0 三个 profile 汇入同一个 `SystemOneDecisionProvider`（构造参数不同：完整路径 + credentialHeader）。
- 新增 `ai.core.server.gateway.GatewayDecisionProvider`（仿 `GatewayMediaProvider`）：先 `hasEnabledProviders()` 守卫（照 `GatewayMediaProvider.route()` 的友好报错）→ `route(model, DECISION)` → 按 `provider.id:updatedAt` 缓存 adapter → 用 `route.upstreamModel` 覆盖请求 `model` → 调 adapter → 规范化 →（P1）trace/cost。
- `GatewayModule` 绑定；`GatewayRoutingEngine.defaultModel` switch 补 `case DECISION -> provider.defaultDecisionModel`（P1；P0 无该字段时给空值，注意这是 switch 表达式、必须穷尽）；新增 `decisionModelHints(endpoint)`（P1）。

**d) 模型注册（沿用现有模型页）**
一条 `gateway_model` 行 = 一个决策模型，`endpoint_types=["decision"]`：
- MS：`upstream_model = Decision-1`（deployment 名）；
- Jev：`upstream_model = jev-latest` 或 `jev-1.13.0`（模型 id）；
- 多供应商 = 多条 provider + 多行模型，路由按 modelId / priority（既有逻辑）。
- ⚠️ **必须手工建，不能用模型页的 "Import models"**：导入走 `GatewayModelDiscoveryService` + `GatewayModelCatalog.enrich`，`Decision-1` deployment 会被 catalog 判成 chat 模型（endpoint 类型来自 catalog/发现），导入即错误注册成 chat；discovery 对决策模型不适用（P1 可考虑在 catalog 为已知决策模型名打标）。

### 3.4 消费面（谁调用、怎么用）

**P0-a：agent 工具 `decide`（端到端可用、最易验证）**
- 新增 `ai.core.tool.tools.DecideTool`，独立 builtin group `builtin-decision`（连字符风格，与 `builtin-media-generation` 一致：`ToolProvider` 加常量 + `BuiltinTools.GROUPED_SETS` 注册），按需可加入 `BUILTIN_ALL`。
- 入参：`state`（string）+ `questions`（array：`{ name, type, instructions, criteria }`）；返回统一的 `answers`（概率/选中项/分数/confidence）JSON。
- provider 取用：新增 `ExecutionContext.decisionProvider`，工具从 context 取（与 `getImageMediaProvider()` 同构）；CLI 无 gateway → provider 为 null → 工具返回 "no decision provider configured"。
- 描述（P1）：动态注入可用决策模型清单（`GatewayRoutingEngine.decisionModelHints`），仿 `generate_image`；注入点 = server 侧 `ToolRefResolutionService` 的 enhancer（`mediaModelHints` 先例见 `ToolRefResolutionService` 内 `GatewayEndpointType.IMAGE_GENERATION` 等调用）。
- 语义立场：**工具只回概率与选中项，绝不自动执行副作用**——高风险动作由 agent/human 决策（与 Jev/TypeSafe 官方"execution stays in your application"一致）。

**P0-b：server 内部 `DecisionService`** —— 一级封装（路由+缓存+规范化+trace），供平台内部判定；P0 提供接口，**首个真实消费者 = §8 旁路命令安全检测器**。

**P1：真实内部消费者（示例）**
- **旁路命令安全检测**：见 §8（server 上执行的命令是否在做破坏集群 / 嗅探扫描等高风险操作）。
- **AI judging**：agent 对比测试每轮评审、`memory experiment` 的 LLM judge → 换决策模型（快/便宜数量级，正是靶心场景）。
- **模型/工具路由**：多候选模型间按"质量/成本/延迟"打分。
- **门控**：危险工具/写操作前的 `noul` 门控。

**P2：外部接入 —— `POST /api/gateway/v1/decisions`（把 core-ai 当决策模型代理）**

目标：**系统外的 app**（业务系统、外部终端、脚本）用 **API 用户密钥**（`ctk_`，见 `design-api-user-access`）或已登录会话调用 core-ai 的决策网关，无需自己接 Azure / TypeSafe。复用 §3.3 的 `GatewayDecisionProvider`（路由 / 适配器 / 规范化 / trace 全共享），**不新增模型接入面**。

- **控制器 / 路由**：新增 `ai.core.server.gateway.GatewayDecisionController`（`@PermissionsBypass`，与 `GatewayImageController` / `GatewayProxyController` 同款——认证由 `AuthInterceptor` 负责、RBAC 拦截器跳过），在 `GatewayModule.registerGatewayProxyRoutes` 里 `http().route(HTTPMethod.POST, "/api/gateway/v1/decisions", controller::decide)`。
- **请求**（System One 原形，§1.2）：
  ```json
  {"model":"decision-1",
   "state":"...",
   "questions":{"team":{"type":"choice","instructions":"...","criteria":{"billing":"..."}}}}
  ```
  `model` 省略 → gateway 的 `DECISION` 默认模型（P1，见 §3.6）；`state` 接受 string / JSON object / array（内部按 `String` + `JsonUtil` 承载，原样透传给适配器）。
- **响应**（**规范化**成统一 `DecisionAnswer` 形状——与两家 vendor 字段一致，客户端近似 drop-in）：
  ```json
  {"model":"microsoft-decision-1",
   "answers":{"team":{"type":"choice","choice":"engineering","probabilities":{...},"confidence":0.9977}},
   "usage":{"input_tokens":125,"output_tokens":3}}
  ```
  core-ai 扩展字段（`provider_id` / `cost_usd` / `decision_id` 等，P1）放**顶层**，不污染 `answers`。
- **鉴权 / 归属**：`AuthContext.userId(webContext)`（同 `GatewayProxyController`）；外部 app 用 API 用户密钥（`ctk_`），走既有网关端点的鉴权链。
- **trace 合并**：复用 `GatewaySupport.sessionId(request, userId, body)` 与 `CLIENT_SESSION_HEADERS` 表——外部终端带会话头即合并成一条 trace。⚠️ 决策 body 没有 `messages`/`input`，`derivedSessionId` 的"开场指纹"路径**不适用**，不带会话头就不合并；可在表里加一个中性头（如 `x-core-ai-session`）供决策/外部 app 显式声明会话。
- **配额 / 计价**：P1 决策 trace 记 `user.id` → 现有 API 用户 token 配额（span ingest 处条件 `$inc`）自动生效，模型行 `inputPricePer1MTokens` 计价（§3.5）。**⚠️ 未接 trace/计价前，外部调用无法计量——这是 P2 上线的前置条件**（见 D18）。
- **错误**：上游 4xx / 429（含 MS 422、Jev 529）**透传**为 gateway 错误（P0 无退避，见 D11）；参数错误（缺 `model`/`state`/`questions`、非法 type/shape）→ 400 `BadRequestException`，尽量保留 upstream `error.code`（如 `unsupported_request_argument`）。
- **模型发现**：`GET /api/gateway/v1/models`（既有）**已经**会列出 decision 模型——`GatewayRoutingEngine.models()` 走 `registeredModels(snapshot, null)`（不按 endpoint 过滤），`GatewayProxyService.models()` 把每行映射成 `{id, object, owned_by}`。**缺口 = 列表无法区分哪个模型是 decision 能力**（响应不带 endpoint 类型）→ P2 给每行补 `endpoint_types`（OpenAI `/models` 形状加字段、向后兼容），或加 `GET /api/gateway/v1/decisions/models` 只列 `decision` 模型；外部 app 据此选 `model`（见 D19）。
- **非目标**：不做 OpenAI `/chat/completions` 形状的封装（决策不是 chat），不做流式（决策无流）。

> P2 改动清单（估算）：`gateway/GatewayDecisionController`（~80 行）+ `GatewayModule` 路由 1 行 + `GatewayProxyService.models()` 每行加 `endpoint_types`（~2 行）+ `GatewaySupport.CLIENT_SESSION_HEADERS` 加一行 + 鉴权/计费沿用既有 + 外部契约测试。

### 3.5 计价与可观测

- 定价复用模型行字段：`inputPricePer1MTokens`（0.042）、`outputPricePer1MTokens`（0 = 免费）；`pricing_source=manual`。两者价格一致（$0.042/M，输出免费）。
- trace：P1 新增 `SpanType.DECISION`（+ `SpanTypeView` mirror，注意 `@MongoEnumValue`/`@Property` 双枚举约定），或独立 `DecisionTrace`（仿 `MediaGenerationTrace`）；attributes：`gen_ai.request.model`、providerId、输入 tokens、cost、`user.id`/`session.id`。P0 至少记录 usage（含 `elapsed`，若 vendor 返回）。
- 归属（P1）：媒体靠 `ContextualMediaProvider(owner)` 把 `MediaJobOwner(userId, sessionId, runId)` 带进 `GatewayMediaProvider` 才能按用户/会话归属 job（见 `SessionContextBuilder` / `AgentRunBuilder` 的 `instanceof GatewayMediaProvider` 分支）；决策若要按 `user.id`/`session.id` 落 trace，同样需要一个 per-context owner 包装（或在 `DecideTool` 显式把 userId/sessionId 传给 provider）。P0 只 log usage 则可不做。

### 3.6 默认模型与解析链（P1）

仿媒体：`decide` 工具 `model` 参数（单次）→ 会话变量（session 覆盖）→ System Configuration "Default Decision Model" → gateway 路由默认。

---

## 4. 改动清单（P0，量级估算）

| # | 模块 | 改动 | 量级 |
| --- | --- | --- | --- |
| 1 | core-ai | 新增 `ai/core/decision/{DecisionProvider,DecisionRequest,DecisionResponse,DecisionQuestion,DecisionAnswer,SystemOneDecisionProvider}.java`（含 3 profile + 规范化；认证走构造参数 `credentialHeader`，**不依赖 server 侧 `applyAuth`**） | ~300 行 |
| 2 | core-ai | 新增 `ai/core/tool/tools/DecideTool.java` | ~150 行 |
| 3 | core-ai | `ai/core/agent/ExecutionContext.java` 加 `decisionProvider` | ~10 行 |
| 4 | core-ai | `ai/core/tool/BuiltinTools.java` 加决策 group | ~10 行 |
| 5 | core-ai-api | `GatewayProviderRequest` / `GatewayProviderView` 加 `decisionProtocol` | ~4 行 |
| 6 | server | `gateway/GatewayEndpointType.java` 加 `DECISION` | ~2 行 |
| 7 | server | `gateway/GatewayModelService.java` 加 `ENDPOINT_DECISION` + alias | ~4 行 |
| 8 | server | `server/domain/GatewayProviderConfig.java` 加 `decisionProtocol`（+`defaultDecisionModel`） | ~6 行 |
| 9 | server | `gateway/GatewayProviderService.java` normalizeDecisionProtocol（含按 type 兜底）/apply/validate/toView/test 决策探针 | ~50 行 |
| 10 | server | 新增 `gateway/DecisionProviderAdapterFactory.java` | ~40 行 |
| 11 | server | 新增 `gateway/GatewayDecisionProvider.java`（含 `hasEnabledProviders()` 守卫） | ~120 行 |
| 12 | server | `gateway/GatewayModule.java` 绑定 | ~6 行 |
| 13 | server | `gateway/GatewayRoutingEngine.java` `defaultModel` switch + `decisionModelHints` | ~15 行 |
| 14 | server | `session/SessionContextBuilder.java` + `run/AgentRunBuilder.java` 装配 `decisionProvider` | ~15 行 |
| 15 | 测试 | adapter 单测（本地 `com.sun.net.httpserver.HttpServer` stub，照 `OpenAIImageMediaProviderTest`；含三 profile 的请求断言 + 应答规范化）+ `decisionProtocol` 按 type 兜底 + 路由 endpoint 过滤 + `DecideTool` + `ExecutionContext` 装配 | ~250 行 |

集合：复用 `gateway_provider` / `gateway_model`，**无需新增 Mongo 集合与迁移**（新增 `decision_protocol` 字段对存量文档为 null，天然兼容）。**注册方式**：前端 gateway 表单已提供 `decisionProtocol` / `defaultDecisionModel` 字段与模型表单的 `decision` endpointType；也可继续用裸 API 注册（`POST /api/gateway/providers` / `POST /api/gateway/models`），`decisionProtocol` 可省略（按 `type` 兜底）。

---

## 5. 关键决策点（请 review）

| # | 决策点 | 建议 | 理由 |
| --- | --- | --- | --- |
| D1 | 归类：新 SPI vs 塞进 `LLMProvider` | **新 SPI**（`DecisionProvider`，放在 core-ai 供 `DecideTool` 复用） | 接口形状与 chat 完全不同；core-ng/依赖方向决定 SPI 必须在 core-ai |
| D2 | 覆盖范围：MS-only vs **System One 通用** | **通用**（一个适配器 + profile：`AZURE_FOUNDRY_DECISION` / `SYSTEMONE` / `OPENROUTER_DECISIONS`） | 两者同契约；用户要求两个都支持 |
| D3 | 判别字段：`decisionProtocol` vs 新 provider `type` | **新 `decisionProtocol`**（多 profile），**为空时按 `type` 兜底** | 与 mediaProtocol 一致（含 `protocol()` 兜底先例）；同 Azure 资源可同载 chat+decision；兜底让 P0 免于新增前端字段 |
| D4 | 应答差异：直连 vendor 字段 vs **规范化** | **规范化成统一 `DecisionAnswer`** | MS 未文档化 `confidence`/`legend`，上层不能感知差异 |
| D5 | 工具形态：单个通用 `decide(state, questions[])` vs 拆 `classify`/`judge` | **单个通用** | 贴合契约"一次多问题"能力 |
| D6 | P0 是否暴露给 agent | **P0 同做工具** | 无工具则无法端到端验证 |
| D7 | endpoint id 命名 | **`decision`** 或 **`decision.systemone`** | 与 image/video 风格一致；倾向 `decision` |
| D8 | 第三方聚合站（jev-api.org / jevmodel.net / api.b.ai） | **只当"不同 baseUrl 的 SYSTEMONE"**，不写专属代码 | 契约相同；避免维护第三方清单 |
| D9 | trace | P1 新增 `SpanType.DECISION` | 语义清晰；P0 先 log usage |
| D10 | 默认模型 / 会话级覆盖 | P1 | P0 用显式 `model` 参数 |
| D11 | 限流退避重试（429 / 529） | **P0 不做，透传错误** | gateway 侧现无 retry/backoff（`ai.core.server.gateway` 无命中），是全新工作，不为 P0 引入 |
| D12 | 无可用 provider 时 | `GatewayDecisionProvider` 先 `hasEnabledProviders()` 守卫并给友好 `BadRequestException` | 照 `GatewayMediaProvider.route()` 既有行为 |
| D13 | 旁路检测的干预挂钩点 | **server 侧 Lifecycle 观测（`beforeTool`）**，不做逐命令 veto | `beforeTool` 返回 void、无法否决；会话级干预（P2）足够且不改 core-ai |
| D14 | 检测阶段与开关 | **模式化配置**：`report`（P1 只上报）→ `block`（P2 取消 turn + 隔离会话），默认关闭 | 用户要求先上报、后期再拦截，且必须可开关 |
| D15 | 检测失败 / 不确定时 | **fail-open（放行）+ 立即返回（不阻塞）** | 旁路定位——检测绝不能拖慢或中断正常执行 |
| D16 | 告警展示位置 | **并入既有 `Cost Alerts` 页**并**仅改显示名**为 `Alerts`，页内分 `Cost alerts`（原样）/ `Security alerts`（新）两区，不新开独立页；权限沿用 `costalert.manage` | 复用既有告警台与导航，避免 Settings 子页膨胀；**保留路由 `/settings/cost-alerts`**（不动 App.tsx / SpaRoutes，零深链风险） |
| D17 | 外部接入（`/api/gateway/v1/decisions`）的响应形状 | **规范化 System One 形状**（`model`/`answers`/`usage`，与两家 vendor 一致、近似 drop-in）+ core-ai 扩展字段放顶层 | 免客户端适配；与 §3.2 的 `DecisionAnswer` 同构 |
| D18 | 外部接入的上线前置 | **P2，且依赖 P1 的 trace / 计价先落地** | 外部调用必须可计量（配额 + 账单）；否则先只对内部开放 |
| D19 | 外部模型发现 | 给 `GET /api/gateway/v1/models` 每行**加 `endpoint_types`**（向后兼容），不新开端点 | decision 模型本就在该列表（不按 endpoint 过滤），只缺能力标记 |

---

## 6. 验证方案

### 6.1 实现前：live probe（**必做，阻塞 DTO 定稿**）
各跑一次 `noul` / `choice` / `score`，记录并落 `.core-ai/tasks/`：

1. **Microsoft-Decision-1**：确认资源 host（`*.openai.azure.com` vs `*.services.ai.azure.com`）+ `api-key` 鉴权 + **`choice`/`score`/`noul` 应答完整 JSON**（含是否返回 `confidence`/`legend`）。
2. **Jev**（`api.typesafe.ai` 或任一 SYSTEMONE host）：确认 `Bearer` + `/v1/systemone` + 应答（`noul`/`choice`/`score` 字段与 `result` 包裹与否）。
3. 用同一 `SystemOneDecisionProvider` 对两者各跑一遍，断言**规范化输出一致**（同问题 → 同结构 `DecisionAnswer`）。

> **MS 实测结果（2026-10-13，脚本 `.core-ai/tasks/foundry-decision-probe*.ps1`）**
> - **端点/认证**：`POST https://fbruatai.services.ai.azure.com/providers/microsoft/v1/systemone`，头 `api-key` → **200**。
> - **请求**：`{"model":"Decision-1","state":<string|object>,"questions":{"<id>":{"type","instructions","criteria"}}}`；`state` 传字符串与 JSON 对象**均通过**。
> - **应答（三类问题一次请求，原样）**：
>   ```json
>   {"model":"microsoft-decision-1",
>    "answers":{
>      "is_urgent":{"type":"noul","noul":0.0293},
>      "severity":{"type":"score","score":2.996,"legend":{"0":"Cosmetic","1":"Minor","2":"Major","3":"Critical"},
>                  "probabilities":{"0":0.0002,"1":0.0003,"2":0.0028,"3":0.9967},"confidence":0.9956},
>      "team":{"type":"choice","choice":"engineering",
>              "probabilities":{"billing":0.0012,"engineering":0.9985,"support":0.0004},"confidence":0.9977}},
>    "usage":{"input_tokens":125,"output_tokens":3}}
>   ```
>   → 字段与 Jev 文档**逐字段一致**；`model` 回底层名 `microsoft-decision-1`。
> - **错误**：非法 type / 缺 criteria → **400**，`{"error":{"code":"unsupported_request_argument","message":"...","details":"..."}}`；choice 文案确认 **`requires 2-255 options`**（选项范围）；另文档列 **422**（不支持的 deployment 名 / 问题定义），本次未实测。
> - **延迟**：本机公网 5 连测 890–1064ms（p50 ≈ 995ms），含 TLS/APIM，非纯推理；in-cluster 预期更低。
> - ⏳ Jev 侧未实测（无 key），字段以文档为准（与 MS 实测相同）；如拿到 key 可照 §6.1 同法闭环。

### 6.2 dev 端到端（coreai-dev profile）
1. 注册两个 provider + 两个模型（前端表单或裸 API：`POST /api/gateway/providers` / `POST /api/gateway/models`）：
   - MS：`type=azure` + `decisionProtocol=AZURE_FOUNDRY_DECISION`（可省略，按 type 兜底）+ `baseUrl` + `apiKey`；模型 `modelId=decision-1`、`upstreamModel=Decision-1`、`endpointTypes=["decision"]`。
   - Jev：`type=openai` + `decisionProtocol=SYSTEMONE`（可省略）+ `baseUrl=https://api.typesafe.ai` + `apiKey`；模型 `modelId=jev`、`upstreamModel=jev-latest`、`endpointTypes=["decision"]`。
   - （可选）OpenRouter：`type=openrouter` + `decisionProtocol=OPENROUTER_DECISIONS` + `baseUrl=https://openrouter.ai`，一条 provider 同时挂两个模型。
2. 两个 provider 的 `test` 通过。
3. chat 里让 agent 用 `decide` 分别打两个模型：一次 `choice` 分类 + 一次 `noul` 门控 + 一次 `score`；断言工具块、返回结构一致、落库 trace。
4. 负例：未注册模型报错、disabled 模型被拦、上游 4xx/429 透传（P0 无退避重试，见 D11）。
5. 单测：三 profile 的请求映射 + 应答规范化（含缺 `confidence`/`legend` 的 MS 形态）、`decisionProtocol` 按 type 兜底、路由 endpoint 过滤、`DecideTool`、`ExecutionContext` 装配（本地 `com.sun.net.httpserver.HttpServer` stub）。
6. **（P2）外部 app 接入**：用 API 用户密钥（`ctk_`）直接 `POST /api/gateway/v1/decisions` → 断言规范化响应、`user.id` 归属与 token 配额计数、无会话头时不合并 trace / 带会话头时合并；负例：无效 key 401、无权限、未注册模型 400。

---

## 7. 风险与限制

| 风险 | 说明 / 缓解 |
| --- | --- |
| 应答字段（MS） | ✅ 已实测：与 Jev 逐字段一致（`noul` / `choice` / `score` / `confidence` / `legend` / `probabilities`）→ DTO 可定稿 |
| 资源 host / 鉴权 | ✅ MS 已实测：`fbruatai.services.ai.azure.com` + `api-key`；Jev 预期 `Bearer` |
| 不可解释 / wording 敏感 / fairness | 双方官方"known limitations"；工具只回概率，高风险决策人工复核 |
| 限流 | MS 429；Jev 429 + **529 Overloaded**；gateway 侧现无 backoff 逻辑（P0 透传错误，见 D11，退避重试留 P1） |
| 第三方聚合路径 | OpenRouter 两者共用一个**非** `/v1/systemone` 的路径 → profile 必须携带完整路径（见 §1.2、§3.3b） |
| 首选项偏好 | Jev 明知会偏向第一个 choice → 上层可随机化/复算校验 |
| 误当 LLM 用 | 明确：无 streaming / 无自由文本，只能结构化判定 |
| prompt injection | 决策内容本身可能被对抗性操纵（Jev 明说不把 state 当敌意）；只回概率不自动行动 |
| 旁路命令护栏（§8） | 误报 / 漏报 / 成本 / 命令含敏感内容——默认关闭、旁路 fail-open、先 `report` 后 `block`；见 §8.10 |

---

## 8. 旁路命令安全检测器（可选，决策模型驱动）

> 状态：P1 设计（尚未实现）。本节是决策模型的**第一个真实内部消费者**——复用 §3.4 P0-b 的 `DecisionService`（路由 + 规范化 + trace）。

### 8.1 定位与目标

检测 server 上执行的 bash / python / powershell 命令是否属于**高风险操作**：破坏集群（删除/驱逐/篡改工作负载、`kubectl delete`、`terraform destroy` 等）、**主机/网络嗅探扫描**（`nmap`、`masscan`、内网/网段探测）、凭据窃取、数据外泄、持久化后门等。

三条硬性语义（即"旁路"的定义）：

| 原则 | 含义 |
| --- | --- |
| **不阻塞** | 检测跑在工具执行关键路径之外（异步 + 专用有界执行器），绝不延迟命令执行或工具返回 |
| **不致命（fail-open）** | 检测失败（超时 / 上游错误 / 解析失败）只 `LOGGER.warn`，绝不让工具调用或 turn 失败；不确定时放行 |
| **可开关** | 默认关闭；开关 / 模式 / 阈值全部走 System Configuration，现读即生效 |

阶段：**P1 只上报**（落 finding + 告警，不干预）；**P2 拦截会话**（高置信命中时取消当前 turn 并隔离会话）。两阶段用同一个"模式"配置切换，P1 只认 `report`。

为什么用决策模型：命令风险是"一次性、有界、要结构化判定"的场景——正是决策模型的靶心；比 LLM 快且便宜，输出天然是"类别 + 概率 + confidence"，便于阈值化与分级。复用本设计的 `DecisionProvider` / `SystemOneDecisionProvider` / `DecisionService`，不引入新的模型接入面。

### 8.2 挂钩点与旁路语义（代码落点）

- **挂钩 = server 侧 `AbstractLifecycle`**（先例 `ai.core.server.sandbox.SandboxLifecycle`）：新增 `ai.core.server.security.CommandGuardLifecycle`，在 `beforeTool(FunctionCall, ExecutionContext)` 观测。`ToolExecutor.execute`（core-ai）在 **sandbox 与本地两条执行路径之前**都会调用 lifecycles，因此同时覆盖沙箱内的命令与（sandbox 关闭时）宿主上直接执行的命令（`ShellCommandTool.doExecute` 用 `ProcessBuilder` 直接跑）。
- **只对命令类工具触发**：`run_bash_command`（`ShellCommandTool.TOOL_NAME`）、`PythonScriptTool`（以及宿主机适用时的 `PowershellCommandTool`）；从 `functionCall.function.arguments`（JSON）取 `command` / `code`。
- **旁路执行**：`beforeTool` 内只做「是否需要检测 + 提交异步任务」后**立即返回**；检测体（决策模型调用 + 落库 + 告警）交给专用**有界**执行器（仿 `AsyncToolTaskExecutor`），信号量限并发，队列满直接丢弃并记 metric。
- **身份快照**：`ExecutionContext` 提供 userId / sessionId / taskId；agent 名在 `beforeAgentRun(Node, ...)` 里捕获（`node` 即 `Agent`）。所有身份在**提交时快照**——异步线程不依赖 ThreadLocal。
- 会话内即时提示走 `CustomEventEmitter`（context 变量 `CustomEventEmitter.CONTEXT_KEY`，由 `InProcessAgentSession` 注入），但它**只在 turn 内有效**（`SessionCustomEventEmitter` 有 turn 窗口与 256KB/turn 预算），因此**不能作为唯一上报面**。
- ⚠️ `beforeTool` 返回 `void`，**无法否决单个工具调用**——这正是本设计把"干预"定义为**会话级**（P2 取消 turn + 隔离会话）而非逐命令拦截的原因；逐命令 veto 需要改 core-ai `ToolExecutor` 增加钩子，本设计不做。

### 8.3 检测契约（决策问题设计）

单次请求，`state` = 命令文本（可附工具名 / cwd；state 预算有限，别塞整段会话历史）。一次多问题（System One 契约）：

| 问题 | 类型 | 作用 |
| --- | --- | --- |
| `category` | `choice` | 含显式 `none`：`{none, cluster_destructive, host_recon_scan, credential_access, data_exfiltration, persistence_backdoor}`；返回选中项 + 各类概率 + confidence |
| `is_dangerous` | `noul` | 0–1 整体风险概率，便于单调阈值 / 兜底 |
| `severity`（可选） | `score` | 0..N 档，供分级告警与排序 |

判定 = `category != none` **且** `P(category) ≥ 阈值` **且** `confidence ≥ 阈值`。指令里必须带**良性负例**（`ls` / `git status` / 编译 / 测试 / 读取 → `none`），并声明"只判定命令文本本身，不臆测意图"，以压低误报。结果复用统一的 `DecisionAnswer`。

### 8.4 可开关与配置（System Configuration → 新增 Security 子栏，P1）

沿用 `SystemSettings` + `SystemSettingsService`（现读即生效、多副本无重启）：

| 字段 | 类型 | 缺省 / 语义 |
| --- | --- | --- |
| `command_guard_enabled` | Boolean | null / 缺省 = **关** |
| `command_guard_mode` | String | `report` \| `block`；默认 `report`（P1 只认 report） |
| `command_guard_model` | String | 决策模型 id；空 → gateway 的 `DECISION` 默认路由 |
| `command_guard_min_category_probability` | Double | 默认如 0.7 |
| `command_guard_min_confidence` | Double | 默认如 0.6 |
| `command_guard_max_inspections_per_minute` | Integer | 成本 / 限流护栏 |

- 校验：`command_guard_model` 必须是 enabled 且 `endpoint_types` 含 `decision` 的 gateway 模型（仿 `SystemSettingsService.validateModelExists` 再加 endpoint 过滤）。
- 同步加到 core-ai-api 的 `SystemSettingsRequest` / `SystemSettingsView`（`@Property`）与 `toView`。
- 字段语义沿用仓库既有的"null = 跟随默认 / 不覆盖"约定（见 `skill_repo_sync_*` 先例），带默认值的字段注意 core-ng 的 `@NotNull` 约束。

### 8.5 上报与展示面（P1）

- **落库**：新集合 `command_guard_findings`（必须在 `ServerApp.registerMongo` 注册，且**新增查询字段必须配套索引迁移**——notablescan 环境否则 291）。字段：`id / user_id / session_id / run_id? / agent_name / tool / command(截断 ≤4KB) / category / category_probability / confidence / severity? / model / action(reported|blocked) / created_at`；索引 `(created_at desc)`、`(user_id, created_at desc)`、`(severity, created_at desc)`。
- **会话内即时提示（best-effort）**：turn 内用 `CustomEventEmitter` 发一个 `command_guard` 事件 + 通用卡片；turn 已结束时 emit 会被拒（正常，忽略即可）。
- **管理面展示 = 既有 `Cost Alerts` 页**（**仅改显示名**为 `Alerts`，复合告警台），页内两个顶层分区：
  - **Cost alerts**：现有内容原样保留（`Rules` / `Alert History` 两个子 tab + 规则编辑器，`CostAlertAdminWebService` 不动）；
  - **Security alerts**（新）：`command_guard_findings` 的**只读列表**——Time / User / Session / Tool / Category / Probability / Confidence / Severity / Action / Command（截断预览），带**时间窗 + category + severity 过滤**；行内可**标记误报**（负反馈，供调阈值 / 加白名单）。
  - 页面级仍 admin 可见，**沿用现有 `costalert.manage` 权限门**（避免 RBAC 迁移 churn；若要独立权限见 D16）。
- **新 API**：`SecurityAlertAdminWebService`（`ai.core.api.server.security`）——`GET /api/admin/security-alerts`（time-range + category + severity + limit 过滤，仿 `CostAlertAdminWebService.events` 的 query bean）+ `POST /api/admin/security-alerts/:id/false-positive`；实现在 `ai.core.server.security.SecurityAlertAdminWebServiceImpl`。
- ⚠️ **只改显示名，路由与文件路径不动**（避免书签 / 深链失效）：`Settings.tsx` 导航 label「Cost Alerts」→「Alerts」、`CostAlerts.tsx` 页内 `<h1>` 同步；**路由 `/settings/cost-alerts` 与 `SpaRoutes.PATHS` 保持不变**，前端组件 / 目录也不改名——因此 `App.tsx` 路由与 `SpaRoutesTest` 均无需改动。
- **不改变工具结果**：agent **不知道**这次检测（与"平台内部行为不进 LLM 工具描述"一致），避免影响其行为或诱导其绕过。

### 8.6 拦截会话（P2）

仅当 `command_guard_mode=block` 且高置信命中：

- **取消当前 turn**：`ExecutionContext.getCancellationToken().cancel(reason)`；需要一个语义化 reason（如新增 `CancelReason.SECURITY_GUARD`；⚠️ `CancelReason` 加值前先核查所有 switch 是否穷尽——`CancelReason` 在 core-ai）。
- **会话隔离**：把 sessionId 标记为 quarantine（如独立集合，或 `chat_sessions` 加字段），后续消息被拦并提示"需管理员复核"；解封走 admin 操作。
- **延迟语义**：block 模式必须**等判定返回**再决定放行与否 → 这是**唯一**引入额外延迟的路径；report 模式零阻塞。因此 block 是显式、管理员主动选择的强约束档。
- 本层是**会话级**（对应"拦截会话"的诉求），不是逐命令 veto（见 §8.2 注）。

### 8.7 误报与成本控制

- **只读快速放行**：复用现成的 `BashReadOnlyChecker.isReadOnly(command)` 跳过明显只读命令，减少无谓调用。
- **阈值**：概率 + confidence 双阈值，默认保守；`none` 选项 + 负例指令。
- **去重 / 限流**：同 session 相同命令 N 秒内只测一次；`command_guard_max_inspections_per_minute`；队列满丢弃。
- **fail-open**：超时 / 上游错误 / 解析失败一律放行，绝不误拦。
- **观测**：每次检测记 model / 延迟 / usage；命中率与误报标记率作为调参依据。

### 8.8 改动清单（P1）

| # | 模块 | 改动 |
| --- | --- | --- |
| 1 | server | 新增 `security/CommandGuardLifecycle`（挂到与 `SandboxLifecycle` 相同的装配点：`AgentSessionManager`×2 / `SessionRebuildManager` / `AgentRunBuilder`；子 agent 经 `extraLifecycles` 一并覆盖，见 `SessionSubAgentManager` / `SubAgentAssembler`） |
| 2 | server | 新增 `security/CommandGuardService`（判定 + 分级 + 阈值门控） |
| 3 | server | 新增 `security/CommandGuardFinding`（entity）+ repository |
| 4 | server | 新增有界执行器 + 去重 / 限流 |
| 5 | server | `ServerApp.registerMongo` 注册集合 + SchemaMigration 建索引 |
| 6 | server | `SystemSettings` / `SystemSettingsService` 加 6 字段 + getter + 校验 |
| 7 | core-ai-api | `SystemSettingsRequest` / `SystemSettingsView` 加字段 |
| 8 | server + 前端 | 告警台（§8.5）：`Cost Alerts` 页**仅改显示名**为 `Alerts`（导航 label + 页内标题；路由 `/settings/cost-alerts` 与目录保持不变）+ 新增 `Security alerts` 分区；新 `SecurityAlertAdminWebService`（`GET /api/admin/security-alerts` + `POST .../:id/false-positive`） |
| 9 | 测试 | lifecycle 异步不阻塞 / fail-open、阈值门控、只读跳过、去重限流、集合注册与索引 |

> P2 额外：`CancelReason.SECURITY_GUARD`、会话 quarantine 与解封、block 模式端到端。

> ⚠️ 与 §4 不同：本节**需要新增 Mongo 集合与索引迁移**（§4 的决策接入不需要）。

### 8.9 验证

1. 单测：`beforeTool` 只提交、不阻塞（用一个**故意慢**的 stub provider，断言工具调用耗时不受影响）；检测异常不冒泡；只读命令跳过；概率 / confidence 阈值门控；集合注册与索引。
2. dev E2E（`report` 模式）：打开开关 → agent 分别跑一条良性命令（`ls` / `git status`，不报）与一条明显危险命令（如 `kubectl delete ns <x>` 或 `nmap`）→ 断言 `command_guard_findings` 落库、会话内 best-effort 卡片出现、**`Settings › Alerts › Security alerts` 分区能看到该 finding（含过滤与标记误报）**、**工具结果与 turn 不受影响**；关闭开关 → 不产生 finding。
   - 改名回归：导航与页标题显示 `Alerts`、旧路由 `/settings/cost-alerts` 仍可访问、`Cost alerts` 原功能（Rules / Alert History）不回归。
3. P2 E2E（`block` 模式）：高置信命令 → turn 被取消 + 会话进入 quarantine + 解封流程。

### 8.10 风险与限制

| 风险 | 缓解 |
| --- | --- |
| 误报（正常运维命令被判危险） | 阈值保守 + 只读跳过 + `none` 负例 + 先 `report` 观察 + admin 标记误报 |
| 漏报 | 明确这是**补充信号**、不是唯一防线（旁路 + fail-open + 可选采样）；强隔离仍靠沙箱边界 |
| 成本 / 延迟 | 旁路 + 限流 + 只读跳过 + 决策模型本身低价（$0.042/M） |
| 命令含敏感信息 | 入库截断 / 脱敏，访问限 admin |
| P2 误拦 | 仅 block 模式 + 高置信 + 人工解封 |
| prompt injection（命令内容被对抗性构造） | 只做判定不改行为；命中以先上报为主 |

---

## 附：外部来源
- Microsoft Command Line《Introducing Microsoft-Decision-1》(2026-10-09)：https://commandline.microsoft.com/microsoft-decision-1-model-foundry/
- Microsoft Learn《Deploy and use Microsoft-Decision-1 in Microsoft Foundry》：https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-microsoft-decision
- TypeSafe AI《Introducing System One Models & Jev》(2026-09-16)：https://typesafe.ai/blog/introducing-system-one-models-and-jev
- Jev Model API 文档（`/v1/systemone`，请求/应答字段与错误码）：https://jevmodel.net/docs
- Jev 模型 id / 别名 / 限制：https://jev-api.org/jev-models
- Jev 1.13 jaggedness（限制清单）：https://docs.typesafe.ai/model-jaggedness/jev-1.13
- JevBench v1.6（Jev vs Microsoft-Decision-1 排名）：https://modelsystem.one/news/jevbench-v1-6-benchmark-heaven/
- TypeSafe AI《System One》概念页：https://docs.typesafe.ai/concepts/system-one
- Jev vs Microsoft-Decision-1 规格对照（含 OpenRouter Decisions API 说明）：https://systemonemodels.org/compare/jev-vs-microsoft-decision-1/
- Microsoft-Decision-1 规格/限制（System One Models）：https://systemonemodels.org/models/microsoft-decision-1/
