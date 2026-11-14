# Core AI Server Agent 发布质量门禁 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Implemented / Dev-verified（用户于 2026-11-14 授权实施） |
| 文档版本 | v0.5（实施验证记录） |
| 创建日期 | 2026-11-12 |
| 需求提出者 / Reviewer | 用户；2026-11-14 授权实施；AI 技术评审见 §14.2 |
| 作者 | core-ai |
| 范围 | Agent 发布质检、可配置规则、管理 UI、发布错误与审计 |
| 本次交付 | 服务端质量门禁、规则/策略治理 API、管理 UI 与 dev 验证；UAT / prod 待按 §10.2 运维预案上线 |
| 设计依据 | 用户原始需求、实际源代码、公开行业资料、AI 技术评审意见；不参考现有 design 文档 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> 我要给core-ai-server的agent发布加一个质量检测机制，它会通过一系列的检测来判断agent是否满足一定的要求，检测可以通过系统的决策模型或者代码来做，规则可能包括：A对配置的检测 1. agent是否配置了已被删除的tool/mcp/skill/subagent/dataset等配置 2. 配置的tool或者subagent或者skill是否超过某个阈值 比如超过50个是不可以的 要ui可配  B提示词的检测 1. 如果提示词中有日期，时间相关的指令，但是没有注入<span v-pre>{{system.time}}</span>时间信息，不行 2. 如果指令中有文件操作、网络抓取或者代码、生成图片、视频等等需要builtin tool去完成的指令，但是没有配置bultin tool的，不行 3. 如果提示词中提到了使用某些tool/api-tool/mcp/skill/subagent/dataset，但是没有对应的配置，不行。 C对描述的检测 1. 必须包含agent的功能简介
> 你要根据我提到的东西 设计一个机制，它可扩展更多的检测，它在用户点击publish时做质检，有问题就报错，报错信息必须包括具体规则的信息和建议，最好还可以通过UI来添加使用决策模型来进行判断的规则。你先出techdesign，我review之后再做后续。design文档你不要参考现有的，你要按业界最佳实践来写，design文档最好有章节保留用户原始需求和review记录以及修改记录等，design文档必须有每个设计和决策的初衷和理由

### 0.2 需求编号

| ID | 要求 | 性质 |
|---|---|---|
| A1 | 配置引用不能指向已删除的 tool / API-tool / MCP / skill / subagent / dataset | 必须阻断 |
| A2 | tool / subagent / skill 等数量阈值由 UI 配置；超过阈值不可发布 | 必须阻断，阈值可配置 |
| B1 | 日期、时间相关指令缺少有效的 <code v-pre>{{system.time}}</code> 注入不可发布 | 必须阻断，语义边界待 Review |
| B2 | 要求执行文件、网络、代码、图片、视频等操作时，必须配置相应 builtin 能力 | 必须阻断 |
| B3 | 指令要求使用的命名资源必须存在对应配置 | 必须阻断 |
| C1 | description 必须包含 Agent 功能简介 | 必须阻断，不能仅检查非空 |
| E1 | 支持代码检查器及系统决策模型，并能继续扩展 | 架构要求 |
| E2 | 用户点击 Publish 时必须执行质检；不能仅靠前端预检 | 发布契约 |
| E3 | 错误包含具体规则信息和修复建议 | UX / API 要求 |
| E4 | UI 可新增基于决策模型的规则 | 本方案纳入首个完整版本，不仅作为远期设想 |
| D1 | 先交付设计，用户 Review 后才能实施 | 工作边界 |
| D2 | 不参考现有 design 文档；保留原始需求、Review、修改历史，每项决策说明初衷和理由 | 文档约束 |

### 0.3 文档维护规则

- 本节原始需求保持原文，后续解释、调整和新增需求单独记录，不覆盖历史。
- 每条 Review 使用稳定 ID，记录意见原文、处理方案、影响章节、状态与确认人；没有用户确认不得写成 Approved。
- AI 技术评审单独记录在第 14.2 节，标注“AI 技术评审、非用户批准”；AI 评审结论不构成实施授权，也不得写成用户已接受。
- 用户 Review 记录在第 14.1 节；没有用户确认不得写成 Approved，也不得把 AI 评审写成用户批准。
- 每次实质修改更新版本、修改记录及受影响的决策；在此文档内维护，不另拆出零散记录。
- 本文“拟新增”的 API、类型、字段及模块是设计，不代表当前实现；数值默认值为提案，不代表已通过测试或已获得批准。

## 1. 目标、边界与核心提案

### 1.1 核心提案

建设一个位于**服务端发布提交之前**的 Quality Gate。它使用统一、不可变的待发布快照，执行版本化规则，收集结构化结果，再由纯代码聚合为“允许发布 / 质量不合格 / 无法完成质检”。

- **确定性事实由代码检查**：引用是否存在、数量是否超限、时间模板是否真正参与渲染、builtin 能力是否可用。
- **自然语言含义由决策模型判断**：是否依赖当前时间、是否要求某项操作、是否要求调用候选资源、description 是否介绍功能。
- **规则管理员可以通过 UI 调参数和新增语义规则**，但不能上传任意脚本或让模型直接执行发布。
- **有问题时保留草稿和原发布版本**，返回每项规则的 ID、版本、要求、位置、检测依据和建议。
- **不确定和系统故障不能伪装成通过**；阻断规则必须取得有效通过结果才能发布。
- **发布元数据也是发布内容的一部分**：name / description 进入发布快照，目录展示已发布快照，避免发布后被普通 update 绕过 C1。
- **Workflow 的 agent snapshot 视为发布入口**：与普通 publish 共享同一门禁，或要求当前 revision 的有效 PASS receipt。

**初衷与理由：**把权限和配置完整性这样可精确判断的问题交给概率模型，会引入不必要的误判；全部用正则，又无法区分“生成代码文本”和“执行代码”等语义。混合检查使判定边界可测试，模型只贡献受限事实，最终动作仍由服务端控制。把发布元数据和 workflow snapshot 纳入，是因为它们同样会改变“外部可见/可执行的内容”，不能只守住 `publishedConfig` 这一个字段。

### 1.2 目标与不变量

1. 所有面向用户的发布入口执行同一门禁，包括普通 API、内部工具、Comparison adoption/revert，以及 Workflow 的 `OWNED_EDITABLE` agent snapshot；不能通过 API / CLI / workflow 绕开 UI。
2. 实际发布内容必须等于被检测内容；发布 candidate 只构建一次，managed prompt 只解析一次，commit 写入同一对象；Agent 自身通过原子 CAS 防止并发修改，外部依赖和策略按固定版本评估并在提交前复核，保证强度见第8.3节。
3. 用户提出的六类检测均有独立规则 ID、可定位结果、修复建议和验收用例；因 MCP tool 列表当前不可持久化，A1 在 v0.3 拆为 reference / MCP server / MCP tool 三条，见 5.1。
4. 规则扩展不要求修改发布主流程；现有检查器的参数调整、语义规则新增不要求发布服务端代码。
5. 发布阻断不修改当前 publishedConfig、发布 receipt 和已发布元数据，不自动修提示词、不偷偷补能力；UI 先保存草稿只更新 editable fields，不改变目录可见的 published name/description。首版为 name/description 增加发布快照（见 5.6），避免发布后修改元数据绕过 C1。
6. 每次发布可追溯规则版本、输入摘要、模型路由版本及结果；仅在启用受控原输入归档且保留期内支持输入重放，不承诺模型逐 bit 再现。
7. 发布审计不依赖可过期报告：每次发布追加保存 receipt 摘要（gate result、candidate/metadata hash、policy/route fingerprint），见 7.1 和 10.3。

### 1.3 非目标

- 不证明 Agent 在真实任务上的完整正确性，不代替端到端测试、红队测试或运行时授权。
- 不在发布检查中执行 Agent，不调用业务 API、启动 MCP 会话、执行 skill 脚本、下载 dataset 或生成图片/视频。
- 不自动删除坏引用、修改 description 或为 Agent 开启所有 builtin tools。
- 不在首版建设任意 Java / JavaScript / Python 插件执行平台，也不直接引入 OPA 服务、工作流引擎或新的消息系统。
- 不为全部已发布 Agent 自动下线；门禁生效后在下一次发布时执行。历史扫描可以沿用引擎，但不是本次首版必要条件。
- 不把用户运行时可以提供的任意信息全部提前穷举验证；运行时权限与资源可用性仍由运行层校验。
- 门禁是**发布门禁**，不是运行时门禁；owner 仍可按现有策略运行自己的草稿（`AgentDependencyAccessPolicy.executableTopLevel` 对 owner 直接返回 editable config），runtime authorization 仍独立生效。首版不把“运行草稿”纳入本门禁。

**初衷与理由：**首版聚焦发布质量，避免质检变成有副作用的 Agent 执行或独立基础设施项目。静态和语义检查的能力边界必须明确，不能把“通过门禁”宣称为“运行必然成功”。

## 2. 行业依据与采用方式

| 参考 | 采用的原则 | 不直接照搬的部分 | 初衷与理由 |
|---|---|---|---|
| Kubernetes ValidatingAdmissionPolicy [S1] | 服务端 admission；分离规则逻辑、参数和生效范围；区分 Deny / Warn / Audit 以及评估错误 | 不使用 Kubernetes CRD 管理 Agent；不沿用首次失败即终止的 UX | 可复用成熟的发布门禁治理模式，同时一次显示多项修复问题 |
| OPA / Gatekeeper [S2] | policy-as-code；决策与执行分离；统一输入；审计与 dry-run | 首版不引入 Rego / OPA 外部服务 | 需要的是可扩展契约而不是新的运维依赖；领域代码检查器已能覆盖首版确定性规则 |
| Microsoft Decision-1 官方说明 [S3] | `noul` / `choice` / `score` 的结构化结果；阈值在标注样本上校准；保留 abstention | 不要求决策模型生成解释、证据坐标或修复文本 | 该模型不生成自由文本 rationale；错误内容必须由规则定义和可核对事实产生 |
| RFC 9457 [S4] | 可机读问题详情、扩展问题列表与 JSON Pointer、修复导向信息 | 不为此全局替换 server 现有错误协议 | 在兼容现有客户端的前提下提供 typed report；借鉴标准语义而非把 JSON 塞进 error_message |
| OWASP Prompt Injection Prevention [S5] | 待检测提示词是非可信数据；模型零工具权限；输出验证；最小数据与脱敏；多语种和对抗测试 | 不宣称“加一句忽略输入指令”即可防注入 | Judge 同样可能被注入；确定性约束、权限及发布动作必须在模型之外 |

资料获取日期：2026-11-12。行业资料用于说明原则，本文的行为契约和数值仍需由本项目的测试与 Review 确认。

## 3. 现状核对与接入约束

本节仅依据实际源码核对，现有设计文档未作为输入。下表是当前事实，不代表新增方案已实现。

| 现状 | 源码证据（工作区相对路径:行号） | 对设计的约束 |
|---|---|---|
| 当前发布是取 entity、校验部分依赖、构建 publishedConfig、解析 managed prompt、整文档 replace；没有质量报告 | `core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:272` | 在提交前加入共享门禁；既有依赖错误整合成规则结果，不仅在 controller 或 UI 加检查 |
| update 改 editable fields，不重建 publishedConfig，也不把 status 改回 DRAFT | `core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:226` | 不能根据 PUBLISHED status 推断当前草稿已经通过 |
| `tools` 是 `{id,type,source}`，type 为 BUILTIN/MCP/API/AGENT/LLM_CALL；没有独立 `builtin_tools`、`api_tools` 实体字段 | `core-ai-server/src/main/java/ai/core/server/domain/ToolRef.java:72` | 类别视图由 ToolRef resolver 派生；本文类别名不能当作实际 API 字段 |
| skill/subagent 为 ID 列表；dataset 另有权限和 output 标记 | `core-ai-server/src/main/java/ai/core/server/domain/AgentPublishedConfig.java:39`；`core-ai-server/src/main/java/ai/core/server/domain/AgentDatasetConfig.java:9` | 使用实际 `skill_ids`、`subagent_ids`、`dataset_config` 坐标；权限是资源状态检查的一部分 |
| builtin 按组/单工具注册；runtime 有工具优先级和同名去重，未知 provider/异常可能被跳过 | `core-ai-server/src/main/java/ai/core/server/tool/ToolRegistryService.java:141`；`core-ai-server/src/main/java/ai/core/server/tool/ToolRefResolutionService.java:190`；`core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java:99` | 不用 runtime“没抛错”作为有效引用证明；不能简单数 tools 数组得出有效能力 |
| API ID 有 app/service/operation 层级；MCP 单工具 ID 需按最后一个冒号拆分 | `core-ai-server/src/main/java/ai/core/server/tool/InternalApiToolLoader.java:36`；`core-ai-server/src/main/java/ai/core/server/domain/ToolRef.java:24` | 复用现有解析器；区分绑定粒度；不能把 ID 随意按冒号拆开 |
| skills/datasets/memory/multimodal/sandbox 会补入部分工具；MCP materialization 可能启动进程 | `core-ai-server/src/main/java/ai/core/server/skill/SkillToolAssembler.java:29`；`core-ai-server/src/main/java/ai/core/server/dataset/tool/DatasetToolProvider.java:30`；`core-ai-server/src/main/java/ai/core/server/tool/McpServerConnectionManager.java:152` | 需要 metadata-only 能力投影，不直接运行完整 AgentBuilder 或 MCP 连接 |
| AGENT run 在执行时提供 `system.time`，author template 经 Mustache；appendix 和 query 保持原文 | `core-ai/src/main/java/ai/core/agent/Node.java:278`；`core-ai/src/main/java/ai/core/agent/PromptRenderer.java:18` | 时间有效注入检查按 runtime profile；不能对所有上下文里的花括号一律判未展开 |
| LLM_CALL 直接将 system prompt 放到 completion，不走 AGENT 的时间渲染路径 | `core-ai-server/src/main/java/ai/core/server/run/LLMCallExecutor.java:63` | LLM_CALL 写入字面 <code v-pre>{{system.time}}</code> 目前也不能满足时间规则；需明确阻断建议，不伪造已注入 |
| 决策模型可注入 DecisionProvider；结果只有概率/标签/分数，不提供文本理由，解析对缺答案较宽松 | `core-ai-server/src/main/java/ai/core/server/GatewayModule.java:137`；`core-ai/src/main/java/ai/core/decision/SystemOneDecisionProvider.java:156` | 复用 Provider，额外严格验证；建议由规则模板产生 |
| decision 通用请求 timeout 固定为 60s，内部调用不自动继承 HTTP Hub audit/quota | `core-ai/src/main/java/ai/core/decision/SystemOneDecisionProvider.java:48`；`core-ai-server/src/main/java/ai/core/server/gateway/GatewayDecisionProvider.java:41` | 发布路径需可取消的专用预算和显式审计/计费，不能假设已有 |
| 普通 Publish 不先 Save，UI 只显示异常字符串；通用 client 丢弃错误结构 | `core-ai-frontend/src/pages/agents/AgentEditor.tsx:664`；`core-ai-frontend/src/api/client.ts:12` | 明确保存再检查，保留 typed report；不能在 error.message 内编码 JSON |
| server API 错误默认只有 errorCode/message；Agent publish 当前权限为 agent.manage | `core-ai-server/src/main/java/ai/core/server/web/CorsInterceptor.java:57`；`core-ai-server/src/main/java/ai/core/server/web/AgentDefinitionWebServiceImpl.java:113` | 新增质量异常的专用结构化映射；不无意改变协作者权限 |
| API user 存在 action RBAC bypass | `core-ai-server/src/main/java/ai/core/server/apiuser/PermissionService.java:56` | 规则管理必须 service-level admin 检查，不仅加权限注解 |
| 目录/详情读取 editable `name` / `description`；`update` 不重置 PUBLISHED | `core-ai-server/src/main/java/ai/core/server/agent/AgentViewHelper.java:34`；`core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:238`、`:267` | C1 不能只在 publish 瞬间检查；必须增加发布元数据快照，或对 PUBLISHED Agent 的元数据修改执行 C1 |
| Workflow 保存版本时固化 owner 的 editable Agent，不经过 Agent publish | `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java:82`；`core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java:247` | workflow snapshot 必须纳入同一门禁，或要求当前 revision 的有效 PASS receipt |
| `ToolRegistryEntry` 不持久化 MCP tool 列表；未连接时 `listMcpServerTools` 返回空 | `core-ai-server/src/main/java/ai/core/server/domain/ToolRegistryEntry.java:14`；`core-ai-server/src/main/java/ai/core/server/tool/McpServerOperationService.java:227` | A1 只能强校验 MCP server；固定 MCP tool 需要可信缓存，否则明确标记未验证，不能伪装 PASS |
| `DecisionRequest` / `DecisionResponse` 不含 provider、upstream model、route fingerprint | `core-ai/src/main/java/ai/core/decision/DecisionRequest.java:11`；`core-ai/src/main/java/ai/core/decision/DecisionResponse.java:10`；`core-ai-server/src/main/java/ai/core/server/gateway/GatewayDecisionProvider.java:45` | 质量门禁需扩展 decision 契约，或提供可复用的 route resolution；不能假设已有 |
| owner 可运行 editable 草稿，不经过 publish | `core-ai-server/src/main/java/ai/core/server/agent/AgentDependencyAccessPolicy.java:135` | 门禁是发布门禁，不是运行时门禁；需在非目标中说明，避免误称覆盖所有执行路径 |
| `PublishLLMCallTool` 把发布异常压成字符串 | `core-ai-server/src/main/java/ai/core/server/llmcall/PublishLLMCallTool.java:78` | 内部 caller 需要文本化全部 issue，或扩展 tool result 的结构化内容；不能只留 “publish failed” |
| 现有 CAS 可使用条件 update 并检查 modifiedCount；Agent 没有 revision，replace 会 upsert；依赖可能跨 Mongo/PG | `core-ai-server/src/main/java/ai/core/server/sandbox/snapshot/SandboxSnapshotService.java:74`；`D:/core-ng-project/core-ng-mongo/src/main/java/core/framework/mongo/impl/MongoCollectionImpl.java:241`、`:279` | 用条件单文档 update，禁止以全局事务或读后 replace 假装并发安全 |

发布入口覆盖：正常 API、SelfHarness `publish_agent`、`PublishLLMCallTool`、Comparison adoption/revert 均调用共同发布服务；个人助手 fork、管理员 builtin reset、初始化 migration 直接写 publishedConfig。Workflow publish 还可固化 owner 的 editable Agent 为自身 snapshot（不是原 Agent publish）。

源码：`core-ai-server/src/main/java/ai/core/server/selfharness/SelfHarnessDispatcher.java:132`；`core-ai-server/src/main/java/ai/core/server/llmcall/PublishLLMCallTool.java:63`；`core-ai-server/src/main/java/ai/core/server/comparison/ComparisonAdoptionService.java:65`；`core-ai-server/src/main/java/ai/core/server/agent/PersonalAssistantService.java:105`；`core-ai-server/src/main/java/ai/core/server/project/ProjectAnalysisService.java:199`；`core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java:80`。

**接入决策与理由：**普通及交互式替代发布都应共享门禁；个人助手 fork 对过滤后的新配置执行门禁，被过滤的引用记录为 WARN，失败不创建 PUBLISHED 副本；交互式 builtin reset 先检查候选再写入。启动初始化/migration 不同步依赖外部模型，属于明确的受信 seed 路径，用代码检查和 CI fixture 验证，并审计标记 trusted-seed；不向用户开放 bypass；每个 migration 例外必须在 14.5 列出并说明理由。Workflow 不是本次 Agent 发布 API 的入口，但 `OWNED_EDITABLE` snapshot 是等价的可执行配置固化路径；v0.3 采纳 AI 技术评审建议，将其纳入首版（见 8.6），不再作为可选范围。当前 Agent CLI/Hub 无独立 publish 子命令；以后新增必须调用相同 service。

## 4. 架构与扩展契约

### 4.1 组件边界

```text
Admin UI / API / Hub CLI
            |
     Authorize + Save Draft
            |
       Publish Service                 Rule Admin UI
            |                                |
   QualityGateCoordinator <------- Active Policy Revision
            |
   PublishSnapshotBuilder
   + ResourceResolver
   + PromptAnalysisBuilder
   + EffectiveCapabilityResolver
            |
   QualityRuleRegistry
       |              |
 Code Evaluator   Decision Evaluator ------ Existing Decision Gateway
       |              |                    (no tools / no Agent execution)
       +------ RuleResult[]
                    |
              GateAggregator
                    |
       Typed Report + Audit Record
                    |
        Recheck Preconditions
                    |
           Existing Publish Commit
```

| 组件（拟新增） | 责任 | 不应承担的责任 | 初衷与理由 |
|---|---|---|---|
| `QualityGateCoordinator` | 读取已激活规则集，安排检查、预算、结果收集 | 不解析各种资源字段，不直接操作 UI | 让新增规则不改变发布编排 |
| `PublishSnapshotBuilder` | 将待发布内容归一化，固定输入版本和指纹 | 不重新创建一套 Agent 配置模型 | 检测实际发布内容，避免 DTO / runtime 分歧 |
| `ResourceResolver` | 批量解析引用状态、可用性、资源名称与版本 | 不调用业务工具、不下载全部资源内容 | 拿到权威事实，控制 I/O 与信息泄露 |
| `PromptAnalysisBuilder` | 生成文本来源、片段 ID、模板来源和候选引用 | 不让模型生成任意证据坐标 | 使位置与依据可验证 |
| `EffectiveCapabilityResolver` | 从真实装配规则生成有效 builtin / dispatch 能力视图；runtime 与 gate 共用同一实现 | 不根据模型猜测或 UI 勾选推断能力；不复制第二份 if 列表 | “配置了”与“运行时能用”必须一致；避免 gate/runtime 漂移 |
| `PublishedMetadataSnapshot` | 保存已发布 name / description 及 metadataHash | 不让普通 update 直接改变目录可见元数据 | C1 必须覆盖发布后可见内容 |
| `QualityRuleRegistry` | 绑定稳定检查器 key、参数类型及支持的输入版本 | 不加载 UI 上传的 Java class / script | 类型化、安全的代码扩展 |
| `DecisionRuleEvaluator` | 构造受限 state / questions，验证结果，按版本化阈值解释 | 不生成 rationale、不拥有 publish 权限 | 适配现有结构化决策能力 |
| `GateAggregator` | 按固定状态表计算是否允许发布 | 不做第二次模型判断或综合质量打分 | 任一必须规则不能被其他好分数抵消 |
| `QualityReportRepository` | 保存每次检查和对应发布关联 | 不把提示词全文输出到常规日志 | 可审计且不扩大敏感数据暴露 |
| `PublicationRecordRepository` | 追加式保存每次发布的 receipt 摘要、candidate/metadata hash、policy/route fingerprint 与规则结果摘要 | 不依赖可过期报告作为唯一审计证据 | 发布审计必须耐久；只保留最新 receipt 不足以证明历史 |

### 4.2 三种规则执行类型

1. **CODE**：已注册 Java 检查器 + 类型化参数。新增不同算法需要代码发布；调整阈值或选择已注册模板不需要。
2. **DECISION**：管理员通过 UI 配置限定输入、问题、criteria、阈值、错误模板；无需代码发布。
3. **HYBRID**：已注册组合检查器，先用代码建立事实，再用决策模型判语义，最后用代码确认缺陷。首版 B1 / B2 / B3 使用该类型；不提供任意节点工作流编辑器。

统一接口的语义：`evaluate(snapshot, ruleRevision, budget) -> RuleResult`。检查器声明需要的事实，输入和输出使用明确 DTO，不直接获取无限制数据库、网络或发布写权限。

**初衷与理由：**扩展的单位是“新规则 / 新检查器”，不是给发布方法增加一串 if。HYBRID 保留领域逻辑和可测性；任意工作流或脚本让控制面复杂化，并引入服务器远程代码执行风险。

### 4.3 规则生命周期和生效范围

- 逻辑、参数、模型路由、阈值、提示语、输入选择改变都创建不可变 `RuleRevision`。
- 生命周期：`DRAFT -> TESTED -> SHADOW -> ENFORCING -> RETIRED`。`TESTED` 是保存测试结果，不等同于已证明质量；只有授权管理员才能激活。
- `PolicyRevision` 固定一组 `(ruleId, ruleRevision, enforcement)`；单个 active 指针原子切换。每次发布固定这一版本。
- 首版范围为 **server 部署级规则集**，覆盖该部署所有 Agent；不凭空引入组织字段或允许 Agent 作者选择较宽松规则集。
- 数据结构预留明确的 scope key，后续组织级策略只能增加约束；不得通过普通 Agent 配置覆盖平台底线。
- 首版内置 A1 / A2 / B1 / B2 / B3 / C1 激活后为 `BLOCK`；其中 `CFG.MCP_TOOL_AVAILABLE` 因 tool 列表不可持久化首版为 `SHADOW`。自定义规则默认 `SHADOW`，经测试后可改 `BLOCK` 或 `WARN`。
- 平台底线规则不可被普通 Agent 作者禁用；管理员调整、停用、回滚有单独审计和确认操作。

**初衷与理由：**规则本身是发布控制面，修改一条提示词可能阻断所有 Agent。不可变版本、先试运行再阻断、原子切换和有限作用域比“保存后立即全局生效”更可控。首版保持简单全局策略，避免不必要的多层优先级冲突。

### 4.4 判定状态与聚合

规则状态使用 `PASS / FAIL / INDETERMINATE / ERROR / SKIPPED`，与 `BLOCK / WARN / SHADOW` 执行方式分开。

| 规则结果 | BLOCK | WARN | SHADOW | 用户应看到什么 |
|---|---|---|---|---|
| PASS | 可继续 | 可继续 | 可继续 | 检查通过 |
| FAIL | 阻断 | 提示，不阻断 | 仅审计 | 已检测到不满足规则，给出建议 |
| INDETERMINATE | 阻断 | 提示 | 仅审计 | 语义不明确 / 置信不足，不断言违规事实 |
| ERROR | 阻断 | 提示 | 仅审计 | 检查器或模型不可用，可重试 / 联系管理员 |
| SKIPPED | 仅明确不适用时可继续 | 可继续 | 可继续 | 说明不适用条件；依赖失败或预算耗尽不可当作不适用 |

总结果：有 BLOCK FAIL 为 `REJECTED`；没有 FAIL 但有 BLOCK ERROR / INDETERMINATE 为 `UNAVAILABLE`；所有适用 BLOCK 通过为 `PASSED`。有质量 FAIL 与系统 ERROR 并存时报告同时列出，不能只显示一个问题。

`checks_complete` 只表示所有适用的 BLOCK 规则是否完成（PASS / FAIL 都算完成）；WARN 规则出现 ERROR 不改变 `gate_result` 的 PASSED / REJECTED，但必须在报告中单独列出并令 `checks_complete=false`。SHADOW 的执行失败只记录 `shadow_execution_error`，不计入 `checks_complete`。

**初衷与理由：**“模型超时”和“Agent 不合格”不是一回事。阻断未知状态可以防止故障绕过门禁，而明确区分原因能让用户知道应该修 Agent 还是重试。禁止加权总分，因为它会允许存在坏引用的 Agent 用更好的描述抵消缺陷。

## 5. 首版规则的精确定义

| Rule ID | 对应需求 | 执行类型 | 首版 enforcement | 初衷 |
|---|---|---|---|---|
| `CFG.REFERENCE_AVAILABLE` | A1 | CODE | BLOCK | 消除悬空或不可使用的非 MCP 引用 |
| `CFG.MCP_SERVER_AVAILABLE` | A1 | CODE | BLOCK | MCP server 引用必须存在、启用、类型正确、config 可解析 |
| `CFG.MCP_TOOL_AVAILABLE` | A1 | CODE | SHADOW（首版）/ 可转 BLOCK | 固定 MCP tool 存在性需要可信缓存；未缓存不得伪装通过 |
| `CFG.RESOURCE_COUNT_LIMIT` | A2 | CODE | BLOCK | 控制配置复杂度和工具选择开销 |
| `PROMPT.TIME_CONTEXT_REQUIRED` | B1 | HYBRID | BLOCK | 防止没有时间上下文却按时间决策 |
| `PROMPT.BUILTIN_CAPABILITY_REQUIRED` | B2 | HYBRID | BLOCK | 防止承诺执行无法执行的操作 |
| `PROMPT.RESOURCE_BINDING_REQUIRED` | B3 | HYBRID | BLOCK | 防止指令与实际资源绑定不一致 |
| `DESC.FUNCTION_SUMMARY_REQUIRED` | C1 | CODE + DECISION | BLOCK | 确保可发现性与功能定位 |

A1 在 v0.3 拆为 reference / MCP server / MCP tool 三条，原因是 MCP tool 列表当前不持久化（见 5.1）。`CFG.MCP_TOOL_AVAILABLE` 首版为 SHADOW；实现可信 tool 列表缓存并完成测试后，才允许转 BLOCK。

### 5.1 A1 — 引用资源有效性

对所有配置引用按实际资源类型检查：实体存在、未删除、启用状态、对应版本/发布状态可使用、平台允许该 Agent 持有引用。检查适用于 tool / API-tool / MCP server / skill / subagent / dataset；固定 MCP tool 选择单独处理（见下）；未来类型通过 resolver 注册扩展。

- 用 ID 与类型判断，不能只通过名称相同认定存在；已知 builtin tool 用 `ToolRefResolver` / `BuiltinTools` 注册表核对，不查数据库。
- 检查范围为该 Agent 的直接引用；subagent 还必须处于可执行的已发布状态。首版不递归执行所有子 Agent 的语义质检。
- 资源刚被删除或改名，UI picker 即使不再显示它，也必须保留坏引用并在发布报告暴露；禁止保存/归一化时悄悄过滤掉。
- 内部可区分 `MISSING / DISABLED / VERSION_UNAVAILABLE / NOT_USABLE`；对无权读取的资源，外部统一显示“引用不存在或不可用”，避免暴露其他所有者信息。
- 资源解析必须批量，数据库错误映射 ERROR；查不到实体才映射 FAIL。不得把查询故障解释为已删除。
- 每类引用使用各自的访问策略：skill 当前是共享目录（存在性），dataset 有 dataset permission，subagent / LLM_CALL 要求可执行 published 状态；不能用一个泛化的“可读”判断替代。

**MCP 引用边界（v0.3 修订）：**MCP server 引用可以强校验：实体存在、`enabled`、`type=MCP`、config 能被 `McpServerConfig` 解析。运行时连接/注册状态不在本规则内；连接失败属于 runtime，不用发布时网络探测替代。固定 `mcp-tool:{server}:{tool}` 的工具级存在性当前无法在 metadata-only 条件下证明——`ToolRegistryEntry` 不持久化 tool 列表，`McpServerOperationService.listMcpServerTools` 在未连接时返回空（`core-ai-server/src/main/java/ai/core/server/domain/ToolRegistryEntry.java:14`；`core-ai-server/src/main/java/ai/core/server/tool/McpServerOperationService.java:227`），而启动 MCP 会话违反 1.3 非目标。因此首版：

- `CFG.MCP_SERVER_AVAILABLE` 为 BLOCK；
- `CFG.MCP_TOOL_AVAILABLE` 为 SHADOW，只有存在可信 tool 列表快照（持久化 discovery cache，或后续给 `ToolRegistryEntry` 增加 tools 字段）时才判定；未缓存时返回 `INDETERMINATE` 并标记 `MCP_TOOL_LIST_UNAVAILABLE`，不连接 server、不伪装 PASS；
- 若业务要求首版就覆盖“已删除 MCP tool”，必须先实现 tool 列表缓存，作为单独 P1 交付物。

错误示例：`CFG.REFERENCE_AVAILABLE@1：skill_ids[2] 引用的 skill 不存在或不可用。建议移除此引用，或重新选择有效且有权使用的 skill。`

**理由：**A1 的核心是持久化引用完整性，而不是网络健康检查。运行时可能有不同权限或临时网络故障，不能用发布时调用所有工具验证；最小可执行元数据足以发现确定性坏配置。

### 5.2 A2 — 数量阈值

UI 可配置 `tool_refs_total / builtin_refs / api_refs / mcp_servers / skills / subagents / datasets` 等计数视图，全部从实际配置派生，不新增重复 Agent 字段。首版建议 `tool_refs_total`（全部直接 ToolRef 绑定）、skills、subagents 各默认 `maxCount=50`，其余细分类别默认不新增用户未要求的限制；管理员可启用。50 为需求中的示例值，待 Review 确认。

- `count > maxCount` 才失败；50 允许，51 失败。零允许，可用于禁止此类配置；不支持负数。
- 计数采用该类别**直接配置的不同规范化引用数量**：ToolRef 使用 type + canonical id + source，其他使用资源 ID；重复引用另作为配置合法性问题报告，不能把不同 MCP server 的同名 tool 合并。
- UI 明确显示计数单位：MCP server 数量不等于 server tools 数量，skill 数量不等于 skill 内步骤数，subagent 数量不递归。
- 一个 Agent 配置超过阈值的不同类别返回不同 issue；显示实际数、上限及需要减少的最少数量。
- “总有效工具数量”属于另一个可扩展规则，不能暗中把 A2 的直接绑定数改变为运行时 tool schema 数；如启用需单独 ID 与计数口径。

错误示例：`CFG.RESOURCE_COUNT_LIMIT@1：skills 为 57 个，上限 50 个，至少减少 7 个。建议保留必需 skill，删除重复职责，或由规则管理员调整阈值。`

**理由：**阈值是管理策略而不是模型判断；用 UI 参数可快速调整。清楚的计数口径防止用户看到 10 个 MCP servers 却被告知超过 50 个“工具”。

### 5.3 B1 — 时间指令与有效注入

HYBRID：代码判断有效注入；只有未注入时才调用模型判断是否存在适用时间指令。

参数 `temporalScope`：

- `CURRENT_TIME_DEPENDENT`（v0.3 默认）：仅当指令的行为依赖“当前时钟”时触发，例如“今天 / 本周 / 最近 N 天 / 当前年份 / 是否过期 / 从现在起算”。日期格式、解析用户提供的日期、固定历史日期转换、时区换算不触发。
- `ANY_TEMPORAL_INSTRUCTION`（可选严格模式）：按原需求较严格字面含义，覆盖日期格式、指定日期的操作指令等。普通引文或背景中的日期不视作指令。

v0.3 按 AI 技术评审建议默认 `CURRENT_TIME_DEPENDENT`。原因：把“输出格式为 YYYY-MM-DD”也要求注入 `system.time` 会产生大量无意义误阻断，反而训练作者机械地加一行时间。若用户坚持原始字面要求，可在规则参数中切换到 `ANY_TEMPORAL_INSTRUCTION`，规则 ID 和接口不变。两种模式均包含日期和时区相对判断，不能仅凭出现数字日期触发。

示例：“输出日期格式 YYYY-MM-DD”不触发；“汇总本周销售”触发；“把 2026-01-01 转成周几”不触发；“判断优惠券是否过期”触发；LLM_CALL 的“从用户输入中提取日期”不触发。

有效注入必须由真实模板解析/渲染路径确认：参与最终 system prompt 的 <code v-pre>{{system.time}}</code> 变量来源被实际替换，且不只是聊天记录、未使用配置、转义文本或模板名。不是简单 <code v-pre>prompt.contains("{{system.time}}")</code>。平台统一追加时间信息只有确实进入该 Agent runtime 的 system prompt 并有 provenance 才可满足；不能假设所有 Agent 自动有时间。

按 definition type 区分：AGENT 可通过真实 Mustache 路径验证；LLM_CALL 当前不渲染 system.time，故包含该字面变量也不能视为有效注入。首版不为质检顺便改变 LLM_CALL runtime：遇到适用时间指令阻断并说明该类型不支持时间模板，建议改为有渲染支持的 AGENT 或移除该指令；若要支持 LLM_CALL 时间模板须另行 Review，增加真正拥有渲染契约的 runtime 改动和回归测试。AGENT 当前 SYSTEM message 未必每轮刷新；本规则证明上下文含有效时间，不证明它精确到本轮，时间新鲜度是后续独立规则。

错误示例：<code v-pre>PROMPT.TIME_CONTEXT_REQUIRED@1：system_prompt 包含适用的时间指令，但未发现有效 system.time 注入。对 AGENT，建议在有效提示词模板加入“当前时间：{{system.time}}”，或删除不需要的时间指令；对 LLM_CALL，提示当前执行路径不支持该模板，不能只补字面字符串。</code>

**理由：**关键词与模板字符串存在均不足以证明时间上下文可用。参数显式保留用户严格要求与低误报模式之间的差别，避免设计悄悄改需求。

### 5.4 B2 — 操作意图与 builtin 能力

先按服务端装配逻辑建立能力集合，再让模型对原提示词判定“是否要求 Agent 实际执行该操作”。每类独立问题，不使用单选 choice 把多种同时需要的能力互相排斥。

| 能力 | 操作意图示例 | 不应仅据此触发的内容 | 检查要求 |
|---|---|---|---|
| `FILE_READ / FILE_WRITE` | 读取本地文件、保存 CSV 到工作区 | 解释如何读文件、返回 CSV 文本 | 对应读取/写入 builtin 可用；不能用有 read tool 满足 write |
| `WEB_SEARCH / WEB_FETCH` | 搜索互联网、抓取 URL 内容 | 总结用户贴入的网页文本 | 按指令区分 search / fetch，不以任意网络能力替代 |
| `CODE_EXECUTE` | 运行 Python、安装依赖、执行脚本 | 编写代码示例、解释代码 | runtime 支持的代码执行 builtin |
| `IMAGE_GENERATE / IMAGE_EDIT` | 生成实际图片、对参考图改图 | 写图片提示词、建议构图 | 实际对应 builtin 和 provider 支持 |
| `VIDEO_GENERATE` | 生成或编辑实际视频 | 写分镜或视频 prompt | 视频 builtin；异步结果的配套能力按真实注册关系检查 |

能力投影将稳定能力 ID 映射到实际注册工具：FILE_READ 对应 `read_file`；FILE_WRITE 根据操作分别需要 `write_file` 或 `edit_file`（edit 还需实际 `read_file` 前置）；WEB_SEARCH/FETCH 分别对应 `web_search` / `web_fetch`；CODE_EXECUTE 对应支持该语言/执行面的 `run_bash_command` 或 `run_python_script`；IMAGE_GENERATE/EDIT 对应 `generate_image` 和相应 provider 能力；VIDEO_GENERATE 对应 `generate_video`，长任务必须具备 `get_video_status` 配套。工具存在不代表宿主执行环境就绪，shell/Python 还须满足 server sandbox/执行面前提；media 检查注册/路由元数据，不产生真实媒体请求。

依据：`core-ai/src/main/java/ai/core/tool/BuiltinTools.java:80`、`:99`、`:104`、`:124`；`core-ai/src/main/java/ai/core/tool/tools/ShellCommandTool.java:37`；`core-ai/src/main/java/ai/core/tool/tools/PythonScriptTool.java:25`。表中能力 ID 为拟新增稳定抽象，不是假定已有配置字段。组引用会展开成同一组能力；通过共享 metadata resolver 而非复制运行装配 if 列表维护。

**单一事实源（v0.3 修订）：**能力投影必须与 runtime 使用同一个 `EffectiveCapabilityResolver`；单 builtin tool 不能用 `ToolRegistryEntry` 直接查表，因为 `builtin:{group}:{tool}` 由 `ToolRefResolver.dynamicToolSet` 解析（`core-ai-server/src/main/java/ai/core/server/tool/ToolRefResolutionService.java:294`）。首版至少加入 parity test：同一组配置下，gate 投影的工具名集合必须等于实际 `ToolRegistry` 的工具名集合；否则 B2 不得标记为已实现。

- 首版遵循用户要求，`providerPolicy=BUILTIN_REQUIRED`。同名 API-tool、MCP 或 skill 不自动替代 builtin；不能因 description 宣称“支持联网”就通过。
- 若要接受受控 API / MCP / subagent 等价实现，必须另行 Review，新增经管理员维护的能力提供者注册表和 `ANY_APPROVED_PROVIDER` 策略；不以模型评分认定等价。
- 对“先调用子 Agent 完成文件操作”而不是父 Agent 自己操作：父检查已绑定可用 subagent，并检查该 subagent 的 published effective capability 是否包含所需操作；不递归检查 subagent 的 prompt 语义，也不错误强制父打开文件工具。结果定位到父提示词字段并引用 subagent ID；不对任意自然语言转交视为已实现。
- “执行任务但没有任何实现路径”和“讲述实现方式”必须区分；默认/可选任务分支只要承诺执行都适用，纯禁止指令不适用。
- runtime 自动加入的工具仅在 server 发布执行面实际保证可用时计入；不能借 CLI 客户端可能注入的工具满足 server Agent 门禁。

错误示例：`PROMPT.BUILTIN_CAPABILITY_REQUIRED@1：提示词要求抓取 URL，但 Agent 缺少 WEB_FETCH 对应 builtin。建议启用指定 builtin，或改为仅分析用户提供的网页内容。`

**理由：**验证的是操作能力，而不是“有没有至少一个 builtin tool”。能力注册表和意图判断分离既支持后续新增能力，也避免工具改名后质检独立映射失效。

### 5.5 B3 — 指令所需资源与配置一致

采用两层检测，避免要求决策模型输出它不支持的任意资源名列表。

**引用语法（首版）**

| 形式 | 示例 | 匹配方式 |
|---|---|---|
| 显式 ID | `api-operation:orders_search`、`mcp-tool:github:search`、`builtin:files:read_file` | 按类型 + 规范 ID 精确匹配 |
| 配置名 / alias | `orders_search`、`github` | 仅在当前可读目录中精确匹配；同名多类型不算覆盖 |
| 普通文本 | “调用订单查询接口” | 交给语义层；不生成具体资源 ID |

1. **候选引用层**：代码解析明确 ID、受支持的命名形式、代码标识符、已配置资源的 runtime 名/alias、当前可读目录中与文本精确匹配的名称。保留片段和类型；名称冲突不得随便选第一个。候选层只扫描当前用户可读、且与文本精确匹配的名称；目录规模设上限（建议 2000 个资源），超出时只保留显式 ID 和已配置资源，其余交给语义层并标记 `CATALOG_TRUNCATED`。
2. **语义完整性层**：将完整主提示词、已配置资源的名称/类型/alias、候选引用及允许的固定调度方式送给决策模型，判断是否存在“要求调用但配置不能覆盖”的资源引用。覆盖明确语法以外的普通自然语言和未知名称。结果仅为结构化 verdict；未知名称问题至少定位到原提示词字段，不能编造具体资源 ID。

候选层对每个候选判“实际要求使用 / 仅提及、示例或禁止 / 不明确”，然后代码比对类型和绑定。明确要求 `tool:orders_search`，但绑定同名 skill 不满足；MCP tool 还要映射到已配置 server 及选择范围；API-tool 和普通 tool 分开，不能靠名称串相同通过。

- 命名资源的静态使用必须有对应绑定。动态 hub 发现、按名称 `use_skill`、运行时临时添加 MCP 等**不自动豁免本规则**；如果用户希望认可动态发现，需要单独范围化策略 Review。
- 明确的示例“不要使用 orders_search”不要求该资源；引用说明文档的名字不等于要求使用。
- 多个未配置资源返回多个 issue；对于未被候选解析器定位的遗漏，返回字段级语义问题与静态修复模板。
- 不把整个无权读取的全站资源目录送给模型；未知资源通过不覆盖 verdict 检出，不通过越权查询补全。
- 泛化自然语言检测仍存在漏报、误报；必须在标注样本验证，不能宣称覆盖所有可能资源称呼。支持显式命名引用以提高确定性，但不强制用户先重写全部提示词才能首版上线。

错误示例：`PROMPT.RESOURCE_BINDING_REQUIRED@1：system_prompt 第 12 行要求调用 API-tool orders_search，但 tools 中没有相应 type=API 的绑定。建议选择并绑定有效 API-tool，或删除这项调用指令。`

未知引用示例：`PROMPT.RESOURCE_BINDING_REQUIRED@1：语义检查认为 system_prompt 要求的部分资源未被配置覆盖；当前无法可靠定位资源名。建议核对工具、技能、子 Agent 和数据集的调用指令，使用明确名称并配置对应资源。`

**理由：**决策模型不是文本提取器；用它生成任意列表或解释会与真实 API 不符。双层方案让已知引用精确可修复，未知提及仍受检查，并诚实标记定位和语义检测的限制。

### 5.6 C1 — 功能简介

先检查 description 去空白后的非空性，再使用决策问题：“仅从该 description，读者能否明确知道 Agent 的至少一项实际功能或任务？”

**发布元数据快照（v0.3 修订）：**C1 不能只在 publish 瞬间检查 editable description。当前 `update` 直接修改 `entity.description`，目录读取 `entity.description`（`core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:238`；`core-ai-server/src/main/java/ai/core/server/agent/AgentViewHelper.java:34`），发布后改成空文本即可绕过 C1。首版在 Agent 文档增加 `publishedName` / `publishedDescription`（或等价的 `PublishedMetadata` 子文档）：

- `publish` 把候选元数据写入快照；
- 目录和详情对 PUBLISHED Agent 优先展示快照；
- `update` 只改 editable 字段，不改变已发布目录元数据；
- `metadataHash` 纳入 quality receipt 与“绿灯”比较；
- 如果暂不实现元数据快照，则 `update` 对 PUBLISHED Agent 的 name / description 修改必须执行 C1，失败返回 `AGENT_QUALITY_METADATA_REJECTED`，不能只靠 UI 提示。

**C1 判定：**

- 空白、仅名字、版本号、标签、宣传语“最好用的智能助手”失败。
- “抓取餐厅菜单并输出结构化菜品数据”通过；不强制固定格式、语言、长篇说明或最低 100 字。
- description 与任务严重冲突可扩展为独立一致性规则，不在 C1 悄悄增加要求。

错误示例：`DESC.FUNCTION_SUMMARY_REQUIRED@1：description 没有明确介绍 Agent 的功能。建议补充“做什么 + 服务什么对象/产出什么”，例如“分析销售数据并生成周报”。`

**理由：**非空或最小字数检查会允许无意义文本通过，且误拒简短有效介绍。该规则只约束用户要求的“功能简介”，不混入风格偏好。

## 6. 检测输入、决策模型和可信证据

### 6.1 统一快照

`PublishQualitySnapshot`（拟新增）包含：

| 输入 | 内容与边界 | 初衷与理由 |
|---|---|---|
| Agent 元数据 | ID、type、候选 name / description、已发布 name / description、允许的 owner/context 标识、metadataHash | description 不在 executable config 中，不能漏检；目录可见元数据必须有发布快照 |
| 待发布配置 | 从当前保存草稿创建的同一 executable candidate；保留原始引用供检查 | 不检测旧 publishedConfig；归一化不得消除问题 |
| Prompt 视图 | inline / managed prompt 解析内容、input template、被实际展开的变量；来源 ID / hash | 检查最终实际指令，不仅看 inline 字段 |
| Template provenance | 来源字段、template key、system.time 是否参与真实渲染 | 发布时不必冻结某个时间值，但要证明运行时注入机制 |
| 引用与能力 | 批量解析的引用状态/版本/可读名称，server runtime 的有效能力 | 给模型权威事实而非让它猜配置含义 |
| 版本指纹 | definitionRevision、executionCandidateHash、metadataHash、prompt fingerprint、dependency fingerprint、policyRevision、evaluatorVersion、model route fingerprint | 防止错发布、过期报告和模型路由漂移；绿灯按 hash 而不是单一 revision |

**candidate 只构建一次（v0.3 修订）：**`buildCandidate` 负责 clean IDs、解析 managed prompt、生成 `AgentPublishedConfig` 和 `executionCandidateHash` / `metadataHash`；质量检查读取该对象，commit 必须写入同一对象。禁止在 commit 时重新调用 `fromEditableDefinition` 或重新解析 `systemPromptId`，否则“检测内容 = 发布内容”不成立。

模型 state 默认只包含当前规则需要的字段。description 规则不发送 tools、prompt；提示词语义规则发送必要配置名称与能力，不发送密钥、API headers、MCP credentials、数据集行、文件内容、用户历史或全部配置 variables 的秘密值。

静态展开只复用纯解析能力，不执行 user-provided expressions、skill script、网络加载或读取业务数据。无法静态解析的必需来源明确返回 `INDETERMINATE`，禁止用省略后的提示词当完整内容通过。

首版检查主体是待发布 Agent 的实际主指令和显式输入模板；自动工具 schema、skill 全文、dataset 内容、subagent 全部内部提示词不进行无限递归扫描。受管 prompt 内容解析进发布快照后独立于后续 prompt 更新；runtime 动态模板来源则记录并按第 8 节处理其变化。

**理由：**检查“最终用户任务里的任意注入内容”在发布时不可能；明确静态边界、保留来源和不执行副作用可避免伪完整性。首版不假装检查了所有子资源的内部行为。

### 6.2 决策模型映射

- 不使用目标 Agent 的 chat model；从 decision endpoint 目录选择固定 gateway model alias，并记录 resolved provider / upstream model / route fingerprint。
- **路由固定与漂移（v0.3 修订）**：RuleRevision 保存 `modelAlias` + `expectedRouteFingerprint`；每次 run 记录实际 provider / upstream / routeFingerprint。路由不匹配时记录 `MODEL_ROUTE_DRIFT`，BLOCK 规则按 ERROR 处理，不静默换模型。若 provider 支持 temperature / seed，应固定并纳入指纹；不支持时记录 provider 默认值，并把它作为路由指纹的一部分。
- **契约依赖**：当前 `DecisionRequest` / `DecisionResponse` 不返回 provider、upstream model 或 route fingerprint（`core-ai/src/main/java/ai/core/decision/DecisionRequest.java:11`；`DecisionResponse.java:10`；`GatewayDecisionProvider.java:45`）。P1 必须先扩展 decision 契约（例如 `DecisionCallResult` 或 `resolveRoute + decide`），不能假设已有。
- 首版自定义规则向导默认 `noul`；统一为 **高值表示满足规则**。正向谓词例如“description 明确介绍 Agent 功能”。B1 / B2 / B3 组合检查器的内部意图问题可以高值表示存在意图，但必须声明 polarity，不与合规阈值混用。
- `noul` 提议初始区间：`p >= 0.80` 为肯定，`p <= 0.20` 为否定，其间为 `INDETERMINATE`。这是未校准起点；不能称为普适最佳阈值或 80% 正确率。两个门限都可配置并要求 `0 <= lower < upper <= 1`。
- `choice` 使用管理员定义的有限标签及其映射；建议有明确 `cannot_tell` 标签。winning-label probability 低于阈值或前两项差距不足时为不确定，不用 `confidence` 替代标签概率。
- `score` 明确量程 `0..levels-1` 及 pass/fail 区间；分数不是“通过概率”。首版高级表单可配置，但不默认用于底线门禁。
- `confidence` 是上游独立字段，保留显示/审计，但没有经本项目验证的语义，不默认作为单独放行依据；`noul` 不强求该字段。

**理由：**清晰的 polarity 和不同类型阈值防止“判违规概率很高反而通过”的集成错误。保留不确定区间可以暴露模糊指令，不靠多数票掩盖模型不稳定。

### 6.3 严格响应验证

质量 adapter 必须在通用 DecisionProvider 外增加与请求对应的验证，不能直接信任宽松 parser：

1. 每个 expected question 都有一个 answer，question name 唯一且 type 与请求一致；未知 answer 不参与放行并记录协议异常。
2. 必需数值有限，不为 null、NaN、Infinity，概率位于 [0,1]。
3. choice 标签必须属于请求 allowlist，winner probability 存在；分布标签和总和满足 provider 已验证契约，误差容忍建议 `1e-3`，需实测确认。
4. score 位于量程内；是否需要完整分布由该规则契约声明，不能读取当前 score 分支未填的 `probability`。
5. response/schema 不匹配、缺答案或返回非法类型为 ERROR，不得默认为 false、PASS 或“模型没有发现问题”。
6. 报告保留 question/rule 映射和已验证答案；错误消息不回显完整上游 body 或凭据。
7. 路由元数据必须与实际调用一致；缺失 provider / upstream / route fingerprint 时，BLOCK 规则记 `ERROR`，不能按默认模型放行。

**理由：**当前 Provider 容错解析适用于一般工具展示，不等于可靠门禁。发布路径必须有比通用调用更严格的契约，而无需破坏其他 decide 用户的兼容行为。

### 6.4 证据与建议从哪里来

- 每项 issue 的标题、规则要求、建议来自版本化规则定义和受限模板；模板只允许已声明变量，不支持任意表达式或 HTML。
- 配置问题 evidence 来自代码，例如资源 ID、数组位置、实际 count、expected max。
- Prompt 全文生成稳定片段 ID 与来源坐标；B1/B2 可在完整语义判定后用受预算的片段复核定位。同上下文多片段无法归因时只返回字段级位置，不生成虚构行号。
- 若 choice 返回片段 ID 选项，ID 必须来自候选 allowlist；“选中片段”仍是语义候选依据，不包装为形式证明。
- 输出标记 `evidence_kind=CODE_FACT / SEMANTIC_ASSESSMENT`；UI 明示后者由决策模型判定。
- 没有动态证据时，规则 ID、完整要求、被检字段、已知配置、模板化建议仍必须齐全；不以第二个生成式 LLM 的解释作为门禁真值。

**理由：**满足“具体规则信息与建议”并不意味着模型必须写理由。可信位置来自原输入，规则说明来自管理员；能够被用户核对比流畅但虚构的 rationale 更重要。

### 6.5 输入规模、分批与失败边界

- 模型输入上限取 provider context budget 与服务端配置上限的较小值；计量包括 state、questions、criteria 和必要输出，不能把字符数等同 token。
- 首版建议单调用最多 20 questions、最多 3 个批次并行；一个 run 最多 120 questions（包含片段归因），最多 8 次 provider calls。候选资源多时分批，输出不超过 100 个可见 issue，余项只统计并提供受权分页。
- 首版建议累计模型输入预算 48k estimated tokens、单 state 文本不超过 64k 字符；这些是运行保护默认值提案，具体模型 contextWindow 必须进一步收紧。
- 完整主提示词超出单调用上下文时：可分段执行适合局部判断的 CODE/语义规则，但依赖全局上下文的 B1/B2/B3 不使用独立分段的全 PASS 当全文通过。超预算返回明确 INPUT_TOO_LARGE / INDETERMINATE，建议精简提示词或管理员采用支持的更大上下文决策模型。
- 调度顺序先保障全部 BLOCK，再执行 WARN，SHADOW 只用独立容量或已完成必需检查后的剩余预算；不把 SHADOW 与 BLOCK 放同一 provider batch，不让前者抢占后者 deadline/questions/calls。SHADOW 因预算未执行仅记录 shadow execution error，不影响最终 gate。
- 策略激活先计算必需规则最大 questions/calls、输入投影及 deadline 计划，明显超强制预算的 policy 拒绝激活；无法静态估计的候选规模在实际 run 中受控并返回输入问题。SHADOW 不计入 BLOCK 的可执行容量证明。
- 所有独立必需规则尽量收集结果，不因首个配置失败就隐藏其他问题。依赖解析失败的语义检查报告 dependency error；必需检查预算达到上限报告未完成项，不宣称已做完全部检测。

**理由：**规则可以无限增加，但发布延时和费用不能无限增长。不能静默截断全文，因为漏掉最后一条指令可能恰好绕过门禁。

## 7. 规则数据模型与管理 UI

### 7.1 数据模型（拟新增）

| 实体 | 核心字段 | 初衷与理由 |
|---|---|---|
| `AgentQualityRule` | id、key、name、category、owner/admin metadata、latestRevision、lifecycle | 稳定身份用于追踪与 UI 列表 |
| `AgentQualityRuleRevision` | ruleId、revision、description、rationale、kind、evaluatorKey/version、typed parameters、inputSelector、decisionSpec、messageTemplate、remediationTemplate、createdBy/At | 每次规则变更有可追溯初衷，不能修改旧报告中的规则含义 |
| `AgentQualityPolicyRevision` | id、revision、scope、ruleRevisionBindings、activatedBy/At、changeReason | 固定一次发布使用的规则集合 |
| `AgentQualityPolicyHead` | scope、activeRevision、generation | 单文档原子激活，而不是逐条启用导致半套规则 |
| `AgentQualityRun` | runId、agentId、actor、mode、executionCandidateHash、metadataHash、policyRevision、runStatus、gateResult、checksComplete、ruleResults、routeFingerprint、timings、model usage、createdAt/finishedAt/expiresAt | 检测状态与 Agent DRAFT/PUBLISHED 状态分离；绿灯按 hash 比较 |
| 发布 receipt（Agent 内） | qualityRunId、policyRevision、definitionRevision、executionCandidateHash、metadataHash、gateResult、ruleResultDigest、routeFingerprint、publishedAt | 同发布快照原子写入，证明实际发布对应哪个检查 |
| `AgentPublicationRecord`（追加式） | agentId、revision、receipt 摘要、candidate / metadata hash、policyRevision、routeFingerprint、gateResult、ruleResultDigest、publishedBy / At | 报告过期后仍能证明发布历史；只保留最新 receipt 不够 |
| `AgentPublishedMetadata`（Agent 内或独立子文档） | name、description、metadataHash | 目录展示已发布元数据，防止 update 绕过 C1 |
| `RuleTestCase / TestRun` | 输入 fixture、expected verdict、标签来源、规则版本、实测结果及样本集版本 | 自定义模型规则必须可以回归，不靠管理员试一次就生效 |

首版规则与报告使用 Mongo repository，因为当前 Agent 发布链路仍以 Mongo 为真源；不借此迁移 Agent 存储。repository 接口可以预留替换，但不得宣称已有全域 PostgreSQL 事务。

新增集合至少需要：quality run 的 `agent_id + created_at`、idempotency key 唯一索引、rule revision 的 `rule_id + revision`、publication record 的 `agent_id + published_at`；报告 TTL 与 publication record 保留期分离，避免审计依赖已过期报告。

Java 实体和 API DTO 使用具体字段类型，集合值类型明确，持久化数值/布尔用包装类型。动态 criteria / parameters 如需要 JSON text，存为 `String` 并在服务端按 kind 反序列化为确定类型，禁止实体或 WebService DTO 使用 `Object`、`Map<String,Object>` 或 BSON Document；entity / view 枚举分开。新增 collection 必须显式注册，绑定顺序满足注入依赖。

**理由：**版本化数据是可审计门禁的基础；符合 core-ng 类型约束才能启动。动态规则不等于可以在全局模型中使用无法验证的任意 Object。

### 7.2 数量规则 UI

`Settings -> Agent Quality -> Rules`：列表显示 rule key、说明、类别、类型、当前版本、生效方式、测试状态和更新人。

数量规则的编辑表单逐类别显示：启用、当前计数口径、允许最大值、边界示例“50 允许 / 51 阻断”，校验非负整数，保存为新 revision。对 platform safety upper bound 的提升给管理员明确风险说明。

**理由：**调阈值是常见运营动作；必须不用改代码即可完成，而且用户应能直接理解边界和计数单位。

### 7.3 新增 decision 规则向导

1. **基本信息**：稳定 key、显示名、类别、规则要求、设计初衷、失败提示与修复建议均必填。
2. **检查输入**：选择 description / 主提示词 / 已配置资源摘要 / 有效能力等 allowlist；展示数据预览与脱敏说明。首版没有任意数据库 SQL、URL、JSONPath code 或其他 Agent 的输入选择。
3. **决策问题**：默认正向 noul，填写“满足是什么 / 不满足是什么”；高级表单提供有限 choice / 有序 score。给出纯文本规则，不允许生成式解释要求。
4. **模型与区间**：decision catalog picker、pass/fail/不确定映射；区间校验、预计输入规模、执行预算。
5. **试测**：至少提供通过和失败样例；推荐增加模糊、否定、引用、混合中英文样例。显示每样例 verdict、分数/概率、耗时和费用信息，不伪造 chain-of-thought。
6. **保存与激活**：先保存 DRAFT，测试成功后进入 SHADOW；管理员看 dry-run 与影响摘要，填 changeReason 后激活 BLOCK / WARN。激活是明确确认动作，不等同于点击保存。

示例自定义规则：`PROMPT.OUTPUT_REQUIREMENT_PRESENT`，“提示词是否明确说明需要交付的结果？”失败说明“缺少可辨识的输出要求”，建议“添加输出内容、格式或交付方式”；输入只选主提示词，使用 noul，无需新 server 代码。

**理由：**UI 能添加新语义规则是完整功能，而非只改一个模型 prompt 字符串。输入限制、规则 rationale、边界样本和独立激活降低误配置把全部发布锁死的风险。

### 7.4 规则测试与维护

- 试测只调用同一 evaluator，不发布 Agent，不执行工具。读取真实 Agent 作为 fixture 需操作者有读取权限；不得通过规则管理权限获得所有 private prompt。
- TESTED 必须满足每项 fixture 实际 verdict 与 expected verdict 一致、必需答案完整且无 ERROR；与 expected 不一致的 INDETERMINATE 也算失败。BLOCK 激活必须满足正反样例全部匹配、未完成检查为零、策略预算可执行，并附代表性 holdout 报告、版本、样本分母和管理员批准。最小两个例子只是配置完整性要求，不是准确率证明。
- 必须分别度量合规样本实际阻断率（包含 FAIL / INDETERMINATE / ERROR）与确定判定覆盖率；建议首版目标为误阻断率≤5%、确定判定覆盖率≥95%，与第11节违规precision/漏检指标一起 Review；没有代表性样本时只允许 SHADOW，不自动转 BLOCK。模型版本/criteria/输入或阈值变化重新运行 fixture suite。
- 删除采用 RETIRED，历史 revision 不物理删；回滚通过激活旧 policy revision 完成，并重新校验旧模型仍可用。
- 管理页面有 History / Test Results / Impact tabs；统计 shadow 失败、不确定、检查故障与 latency。权限允许的 Agent 内容才可展开。
- 内置 CODE 逻辑不可在 UI 修改；只能调支持的参数和消息，添加 CODE 算法需代码审查和测试。未来需要表达式规则时可独立引入受限 CEL checker，不在首版以 eval 实现。
- 回归测试必须覆盖：B2 投影 vs runtime registry（parity）、C1 发布后元数据修改、MCP tool 缓存命中/未命中、WARN 发布成功后的报告读取、model route drift、workflow snapshot gate。

**理由：**治理和规则执行同样重要。保留历史、回滚和 shadow 可以缩短错误策略恢复时间，而禁止任意脚本避免把高权限设置变成 RCE 入口。

## 8. 发布协议、并发和一致性

### 8.1 首版选择：同步、有界、服务端强制

点击 Publish 执行：

1. UI 若 dirty，先按版本条件保存草稿；保存失败则不继续。按钮和编辑区在保存/发布期间互斥，避免并发本地状态覆盖。
2. 服务端先鉴权，读取同一保存草稿，**只构建一次** candidate：clean IDs、解析 managed prompt、生成 `AgentPublishedConfig`、`executionCandidateHash`、`metadataHash`；记录 STARTED run。
3. 执行所有适用规则，保存终态报告；先收集完所有独立必需规则的结果，任一必须规则失败或无法完成则停止发布，不更改 publishedConfig、published metadata 或 receipt。
4. 若通过，重检当前权限、草稿 revision、`executionCandidateHash`、`metadataHash`、关键依赖摘要、active policy generation、model route fingerprint；变化则返回 STALE，不使用旧结果发布。
5. 对 Agent 做带 revision 条件的单文档 update，写入**同一 candidate**、发布元数据快照、quality receipt、追加式 publication record，并增加 definitionRevision。
6. 更新 run 的 publication outcome，通知目录变化。成功响应保留 AgentDefinitionView，并通过 `X-Quality-Run-Id` / `X-Quality-Gate-Result` 头返回 run 摘要；WARN 详情由 UI 调 latest 报告接口读取。目录通知只能在提交成功后发出。

如果只有 WARN 规则失败，publish 仍成功，但响应头必须带 run id 和 warn count；UI 不得把它当作静默成功。

首版采用同步返回，预计常见检查可在一个交互等待内完成；建议 gate 总 deadline 45s，单模型调用不超过 20s，最多 3 个并行批次。必须在实际 transport 设置 deadline/cancellation，不能只让上层 Future 超时而后台仍无限消费。当前 DecisionProvider 固定 60s，实施需增加可选调用 options / deadline 的通用契约，而不是把 Gateway timeout 配置当成已生效。

**理由：**需求是点击 Publish 得到质检错误，不是创建长期审核工单。首版有界同步最简单，不新增队列/轮询 worker/恢复租约系统；若实测 p95 无法满足交互时延，再扩展成持久 async publish operation，仍复用同一 snapshot/result/commit 契约。

### 8.2 草稿并发保障

新增单调 `definitionRevision`，所有修改该 Agent 文档的业务路径必须使用条件 update；旧记录初始化 revision，迁移必须 bump migration version。只修 publish，而继续让 update 使用旧 entity replace，会让 publish 快照被随后过时 update 覆盖，不能作为完整方案。`definitionRevision` 只负责 CAS；质量“绿灯”和 receipt 的有效性按 `executionCandidateHash` + `metadataHash` 比较，避免 description-only 修改把执行配置的发布结果误判为过期，也避免只改元数据就重新获得绿灯。

- 编辑读取 revision；UI Save 通过 `If-Match` 传 expected revision；冲突返回 409，不盲目覆盖别人改动。
- PUBLISHED Agent 的 name / description 修改只改 editable 字段和草稿 metadataHash；receipt 与 published metadata 不变，目录仍读 published metadata，直到下一次 publish。
- UI Publish 对保存成功的 revision 传 `If-Match`。旧 API caller 可不传，服务端用读取时 revision 构建 candidate；检查后仍必须 CAS。
- commit filter 至少为 `_id + definitionRevision`，update 增加 revision 并写 publication receipt 与 publication record；不使用 upsert 或整文档 replace。
- 同时 publish 和 update：只有符合 revision 的一个写入成功；另一个返回 STALE/CONFLICT。并发 delete 后 commit 为零更新，不得把 Agent 重新创建。
- core-ng update 返回 modifiedCount；CAS 同时增加 revision，保证匹配成功确实发生修改；`modified==1` 才算成功，不把零修改当成功。
- 对比采纳/回滚应先建 candidate 并检测，再条件保存与发布；如必须沿用先改草稿路径，明确回报“草稿已更新、发布未变”，不谎报原状完全未动。

**理由：**模型调用拉长 read-write 时间窗口，读后 replace 既会丢用户编辑，也可能重新创建已删记录。单文档 CAS 是当前存储可落实的边界，不假设存在跨库事务。

### 8.3 依赖与策略变化的保证强度

依赖摘要包含被检查的 ID、状态、关键 revision/content hash、选择范围、能力装配版本、`executionCandidateHash`、`metadataHash` 和 `routeFingerprint`。门禁结束后再次批量读取比对；变化返回 STALE，提示重新检测。规则激活、模型路由或发布元数据改变也如此。

必须区分两种保证：

- **强保证**：被提交的 Agent candidate 与被检 candidate 相同，published metadata 与 `metadataHash` 相同；Agent 自身写入通过原子 CAS 保障。
- **提交前复核语义**：外部依赖、规则 active head、模型路由在最终复核时一致。它们可能分属 Mongo、PG、进程注册表，与 Agent commit 之间仍存在竞态窗口；本稿没有实测其上限，不宣称“极小”或跨资源原子事务。拟要求最终复核结束到 CAS 发起超过 2s 则放弃本次提交重新检查，但该 deadline 只能缩短陈旧窗口，不能阻止窗口内的外部变化。

首版接受“最终复核时有效”的发布 admission 语义；报告记录核对时间与摘要。资源删除/撤权在发布后的任何时间都可能发生，运行时仍校验，不因 quality receipt 绕过授权。以后若业务要求绝对禁止发布中资源删除，需要资源不可变版本 pin、引用删除限制或共享事务边界，是单独项目而不是伪装一把 Agent 锁。

策略激活同样是以检查所固定 revision 审计，最终复核尽力保证新策略不被忽略；竞态窗口内可能按刚切换前的合法版本提交，不能标记成用了最新版本。紧急禁用执行的需求应放在 runtime authorization/kill switch，不仅依赖发布质检。

**理由：**真实系统是混合存储。准确写出一致性强度，比承诺无法实现的“所有资源全原子”更符合工程最佳实践。

### 8.4 重试、幂等与崩溃

- 支持 `Idempotency-Key`，作用域为 actor + agent + publish operation；同 key 必须对应同 candidate revision/hash，不同输入返回 409。`quality-checks` 预检也支持可选 key，避免重复计费。legacy 无 key 仍强制门禁，但不能保证客户端重试不产生额外评估费用。
- run 在模型调用前创建，key 有唯一索引。同 key 的并发请求不同时执行：STARTED 返回进行中冲突和 run link；终态可取回原报告，不重新抽样模型直到“碰巧通过”。
- 提交前将通过报告及拟发布 receipt 以 `COMMITTING` 状态持久化。`STARTED -> COMMITTING` 必须按 run 状态、operation generation 与尚未超时的条件 CAS；它与清理任务的 `STARTED -> ABORTED` 竞争，仅一个能成功。已 ABORTED / CANCELLED / 超时的请求不能进入提交阶段。进入 COMMITTING 后清理器只 reconcile，不把它作为 STARTED 超时终止；提交者仅在本次 CAS 获胜后发起一次 Agent commit。
- Agent CAS 同时写 runId、operation key hash、`executionCandidateHash`、`metadataHash`；只有成功赢得 COMMITTING 的请求持有提交资格。后台修复只核对 receipt 完成审计，不抢占资格、不自动重放 publish；晚到的模型响应不能复活已终止 run。
- CAS 成功但 HTTP 断开：重试通过 Agent receipt / run / publication record 获知已提交，不再次提交；返回原 operation 的结果或报告，不把后续别人的最新版当成本次发布结果。
- 若 COMMITTING 与当前 receipt 无法证明是否提交，先按 operation key hash + `executionCandidateHash` 查 append-only publication record；仍无法证明才标记 `PUBLICATION_OUTCOME_UNKNOWN`，请求操作者刷新检查，不猜测或重发。首版不宣称跨集合 exactly-once；若必须永久消除该状态，后续增加原子 publication history / outbox 设计。
- STARTED 超过 deadline + grace 且无 receipt 标记 ABORTED；下次使用新 key 可重新质检。原 key 不因重试自动更改判定。

**理由：**幂等防止同一 operation 重复评估；不能防止作者换新 key 对同样内容反复提交。首版明确保留这个概率抽样风险，用 actor/candidate 频率限制和审计观测兜底，不声称已解决；如果 BLOCK 规则实测容易通过反复抽样绕过，必须先 Review 增加按候选+规则+模型指纹的受控重检窗口或人工复核策略再启用。Agent + report 多集合没有事务，必须允许明确未知状态，而不是在崩溃恢复中无条件再发布。

### 8.5 预检查与缓存

提供可选“Check Quality”，用同一引擎和规则在已保存草稿上预检查，返回报告但不发布。首版不将预检查的 PASS 作为长期授权令牌，也不跨发布尝试缓存模型 PASS；点击 Publish 必须重新执行门禁，避免时间变化、资源变化或规则变更漏检。

同一个 idempotency key 的结果重放属于同 operation 去重，不是跨请求绕过检查。可以缓存非敏感注册表元数据，但最终权威引用读取与 commit CAS 不得由 stale cache 取代。

**理由：**预检服务于编辑反馈，强制门禁服务于安全发布；首版先不做复杂 cache invalidation，宁可多一次受预算评估，也不允许旧“绿灯”发布新内容。

### 8.6 Workflow agent snapshot 的发布准入（v0.3 新增）

Workflow 保存版本时会为每个 agent 节点固化 snapshot；其中 `OWNED_EDITABLE` 路径直接使用 owner 的 editable config，不经过 Agent publish（`core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java:82`；`core-ai-server/src/main/java/ai/core/server/workflow/WorkflowPublishService.java:247`）。这是一条等价的可执行配置固化路径，v0.3 纳入门禁：

1. `OWNED_EDITABLE` candidate 必须先构建与普通 publish 相同的 snapshot，执行同一套 BLOCK 规则；失败则不保存该版本，返回 typed report。
2. 或者要求该 Agent 当前 `definitionRevision` 存在有效 PASS receipt，且 `executionCandidateHash` + `metadataHash` 与 candidate 一致；否则拒绝 snapshot。
3. `PUBLISHED` 路径继续使用已发布的 `publishedConfig`；如果该 Agent 的 receipt 与当前 published config 不一致，记录 warning，不静默使用。
4. 首版不在 workflow 内做异步质检；同步失败即返回，workflow 版本不写入。
5. 历史 workflow 版本不自动重新检查；下一次保存版本时执行。

**理由：**如果不覆盖这条路径，用户可以不发布 Agent 而直接在 workflow 中执行它，普通 publish 门禁形同虚设。把 workflow snapshot 纳入后，不变量 #1 才成立。

## 9. API、错误协议与发布 UI

### 9.1 API 契约（均为拟新增或变更）

| API | 行为 | 初衷与理由 |
|---|---|---|
| `POST /api/agents/:id/publish` | 保留 URL 和成功 AgentDefinitionView；可接受 If-Match / Idempotency-Key；始终执行门禁；成功响应头带 `X-Quality-Run-Id` / `X-Quality-Gate-Result` / `X-Quality-Warn-Count` | 所有已有调用者获得强制保护，不以是否传 reportId 判定是否检查；WARN 也有交付路径 |
| `POST /api/agents/:id/quality-checks` | 同步检测已保存草稿，返回 typed report；FAIL 为正常检测结果，HTTP 200，无发布；可选 Idempotency-Key | 编辑预检与执行副作用区分 |
| `GET /api/agents/:id/quality-checks/:runId` | 读取有权限的报告、原 operation 状态；支持历史有限分页 | 重试恢复与调试不依赖长错误字符串 |
| `GET /api/agents/:id/quality-checks/latest` | 读取当前 revision 的最近一次报告、run 状态和 WARN 摘要 | 成功发布后的 WARN 详情、恢复和调试不依赖记忆 runId |
| `GET /api/admin/agent-quality/rule-templates` | 返回 CODE/HYBRID 模板、参数 schema、支持的输入、内置规则说明 | UI 从后端能力目录渲染，避免前后端各一份规则逻辑 |
| `/api/admin/agent-quality/rules` | CRUD 逻辑身份、创建不可变 revision、retire，不覆盖历史 | 可扩展规则管理 |
| `POST /api/admin/agent-quality/rules/:id/test` | 对指定 revision 和有权 fixture 试测 | 同执行器回归 |
| `POST /api/admin/agent-quality/policies/activate` | expected generation + policy revision + changeReason，原子激活 | 防止多人管理覆盖和半套生效 |

成功发布不改变 `AgentDefinitionView` 的 body；新增信息放响应头 + latest report API，避免破坏旧客户端。WARN 规则失败时 publish 仍 200，但响应头必须让前端知道需要展示 warn。

quality-check 权限至少为对该 Agent 具有当前发布管理权限；报告读取遵循同一资源访问策略。publish 不新增未经 Review 的 owner-only 或 agent.publish 权限；保留普通协作者的现有能力。

### 9.2 发布错误分类

| HTTP | errorCode | 语义 / 修复 |
|---|---|---|
| 422 | `AGENT_QUALITY_REJECTED` | 至少一条 BLOCK FAIL；修 Agent 配置/提示词/描述 |
| 422 | `AGENT_QUALITY_INPUT_UNRESOLVED` / `AGENT_QUALITY_INPUT_TOO_LARGE` | 必需语义检查无法明确判定或完整输入超预算；不宣称确定违规，建议澄清/精简输入，retryable=false |
| 503 | `AGENT_QUALITY_UNAVAILABLE` | 必需检查因 provider/transport/内部错误无法完成；临时故障可重试，配置故障联系管理员；retryable按reason填写 |
| 409 | `AGENT_QUALITY_STALE` | candidate / dependency / active policy 变化；刷新后重新检测 |
| 409 | `AGENT_PUBLISH_IN_PROGRESS` / `IDEMPOTENCY_CONFLICT` | 原请求在处理或 key 对应不同输入；查 run，不重复提交 |
| 403 | 现有权限代码 | 无权发布或管理策略；质检不是权限判断的替代 |
| 429 | `AGENT_QUALITY_CAPACITY_EXCEEDED` | 超调用者配额/并发限制；给 Retry-After，不修改草稿 |

如果 v0.3 的发布元数据快照方案暂不实现，则 `update` 路径增加 `AGENT_QUALITY_METADATA_REJECTED`（422），对 PUBLISHED Agent 的 name / description 修改执行 C1；不能只靠 UI 提示。

报告级 `gate_result=UNAVAILABLE` 表示没有完整放行结论；HTTP进一步区分稳定输入问题与暂时服务故障。每个问题必须有 `issue_reason`、`retryable`、`recommended_action`（EDIT_INPUT / RETRY / CONTACT_ADMIN / REFRESH）。多原因并存全部列出，不能自动重试非retryable结果。已有明确FAIL时仍422 REJECTED，同时列出其他未完成项。

现有错误协议保持 `{errorCode,message}` 并增加可选 `quality_report`；针对质量异常在 error mapper 中使用 typed DTO 序列化，其他错误不改变。前端新增保留 status/errorCode/payload 的 `ApiError`，不能仍只抛 `Error(message)`。内部 service caller 收到 typed quality exception/report；SelfHarness / LLM-call tool / Comparison 首版至少文本化全部 issue，不抹成“publish failed”，结构化保留待 R-AI-12 决定。

**理由：**RFC 9457 的问题列表、定位和机器可读语义适用，但现有所有客户端不应为了一个功能被迫换 envelope。语义协议必须明确，单纯增加异常字段会被当前 CorsInterceptor 丢弃。

### 9.3 报告示例

以下为保持现有 envelope 的示例；`field_path` 使用实际配置字段，而能力类别名称不当作实体字段。

```json
{
  "errorCode": "AGENT_QUALITY_REJECTED",
  "message": "Agent 未发布：2 项质量规则未通过。未创建新的发布版本。",
  "quality_report": {
    "run_id": "quality-run-123",
    "agent_id": "agent-123",
    "definition_revision": 12,
    "execution_candidate_hash": "sha256:...",
    "metadata_hash": "sha256:...",
    "policy_revision": 3,
    "route_fingerprint": "decision-1/azure/upstream-model/v1",
    "gate_result": "REJECTED",
    "checks_complete": true,
    "issues": [
      {
        "rule_id": "PROMPT.TIME_CONTEXT_REQUIRED",
        "rule_revision": 1,
        "rule_name": "时间指令必须有有效时间上下文",
        "rule_requirement": "适用的时间指令必须配合有效 system.time 注入",
        "status": "FAIL",
        "enforcement": "BLOCK",
        "field_path": "/system_prompt",
        "evidence_kind": "SEMANTIC_ASSESSMENT",
        "evidence_text": "汇总本周销售结果",
        "location_precision": "FIELD",
        "message": "提示词含适用时间指令，但未发现有效 system.time 注入。",
        "suggestion": "在参与运行时渲染的 system prompt 中加入：当前时间：{{system.time}}。",
        "decision_type": "noul",
        "question_id": "time_instruction_present",
        "predicate": "提示词是否含适用的时间指令",
        "polarity": "AFFIRMATIVE_MEANS_REQUIRED_INTENT",
        "decision_metric": "affirmative_probability",
        "affirmative_probability": 0.94,
        "affirmative_threshold": 0.80,
        "decision_mapping": "时间指令为肯定且有效时间注入为false时FAIL",
        "issue_reason": "MISSING_TIME_CONTEXT",
        "retryable": false,
        "recommended_action": "EDIT_INPUT"
      },
      {
        "rule_id": "CFG.RESOURCE_COUNT_LIMIT",
        "rule_revision": 2,
        "rule_name": "配置数量上限",
        "rule_requirement": "skill 直接绑定数量不得超过配置上限",
        "status": "FAIL",
        "enforcement": "BLOCK",
        "field_path": "/skill_ids",
        "evidence_kind": "CODE_FACT",
        "actual_count": 57,
        "max_count": 50,
        "message": "配置了 57 个 skill，上限 50 个。",
        "suggestion": "至少减少 7 个绑定；保留必需 skill，或联系规则管理员调整上限。",
        "issue_reason": "COUNT_LIMIT_EXCEEDED",
        "retryable": false,
        "recommended_action": "EDIT_INPUT"
      }
    ]
  }
}
```

成功发布且存在 WARN 时，响应 body 仍是 `AgentDefinitionView`；`X-Quality-Gate-Result: PASSED`、`X-Quality-Warn-Count: N` 和 `X-Quality-Run-Id` 供 UI 拉取报告。`metadata_hash` 覆盖 name / description 的发布快照，`execution_candidate_hash` 覆盖可执行配置。

概率字段在该示例表示内部“存在时间指令”的肯定概率，不表示失败概率或模型正确率。所有 decision issue 必须携带 question_id、predicate、polarity、decision_metric、threshold/mapping，不能只给裸概率；UI 用明确文案。无 evidence_text 时允许不提供，但不得杜撰。每条 issue 必须有 rule ID/version/name/requirement、status/enforcement、message、suggestion、目标字段和 evidence kind；坐标精度、数值等按实际结果可选。

### 9.4 发布 UI 行为

- 操作过程显示“保存草稿 -> 检查配置/提示词/描述 -> 发布”，按钮 loading，避免重复点击和 Save/Publish 交叉写入。
- 发布成功但存在 WARN：显示“已发布，N 条警告”，可打开 latest report；不得静默成功。
- PUBLISHED Agent 的编辑器显示“草稿元数据”和“已发布元数据”两份；修改 description 后目录仍显示已发布版本，直到重新发布。
- MCP tool 未缓存时，报告显示“未验证（MCP tool 列表不可用）”，不标成通过。
- 模型路由漂移显示“检查模型路由已变化，请重试或联系管理员”，不显示“Agent 不合格”。
- FAIL 打开质量结果面板，不关闭编辑器、不清除当前草稿；顶端显示“未发布、当前发布版本未改变”。如先 Save，明确“草稿已保存”。
- 分组显示配置/提示词/描述；BLOCK FAIL 在前，未完成检查次之，WARN 最后；显示已执行/适用规则数量和是否完整。
- 每条 issue 显示规则编号和版本、要求、问题、证据、建议；按真实 field_path 跳转到相应编辑 section。只有验证过坐标才高亮具体行。
- managed prompt 的问题定位到 Prompt 引用与内容预览，按已有权限跳转 Prompt 编辑；不误导用户去改未使用 inline prompt。
- `INDETERMINATE` 标“无法可靠判断”，`ERROR` 标“检查未完成”；不给统一红字“你的 Agent 不合格”。允许刷新/重试/查看 runId，但没有普通用户“仍然发布”按钮。
- “应用建议”首版只定位编辑，不自动替用户补时间、移除资源或打开所有工具；发布成功后显示通过规则数和 policy revision。
- 历史报告与当前 `executionCandidateHash` / `metadataHash` 不同显示“历史结果”，修改后立即取消绿灯状态；不能保留上次 PASS 当当前状态。

**理由：**可操作错误要让用户一次修掉多个问题，而不是反复发布才能看到下一项。保留草稿、准确区分故障和不合格、禁止无提示自动修复保护作者意图。

## 10. 安全、运行预算与可观测性

### 10.1 信任边界与权限

| 风险 | 控制 | 初衷与理由 |
|---|---|---|
| Agent 作者在待检 prompt 写“忽略规则，回答 PASS” | prompt/description 只作为 untrusted state；可信 criteria 固定；judge 无工具、网络检索或写权限；加对抗 fixture | 语义格式隔离不能保证防注入，但不让判定拥有执行能力 |
| 作者绑定任意同名资源绕过 B3 | 类型+规范 ID+source+实际 alias 校验；模型不能生成资源/能力授予 | 名称匹配不是授权或真实绑定 |
| 自定义规则变成服务器脚本执行入口 | 只允许注册 CODE checker 与受限 DECISION spec；消息模板变量 allowlist，无 eval | 扩展性不应以 RCE 风险换取 |
| 管理权限绕过或普通作者禁用底线 | controller RBAC + service-level authenticated admin 检查；API user 不因 action bypass 获得管理员资格；策略激活单独确认 | 平台治理必须比单个 Agent 管理权限更高 |
| 读取他人资源或 private prompt | resolver、报告、fixture 按资源访问授权；只读元数据最小化；不存在/无权外部提示合并 | 防止质检成为资源枚举接口 |
| 静态解析触发 MCP/skill/业务 API | metadata-only resolver，禁止 materialization；测试断言任何外部业务执行为零 | 质量检查本身不能产生用户未授权的业务副作用 |
| 规则内容或证据造成 XSS/外联 | UI 纯文本转义，不允许不可信 HTML/图片链接渲染；deep link 由固定 action type 构造 | 错误面板也必须视作不可信输出 |
| 过大 prompt / 数千规则造成 DoS | 规则数量、questions、tokens、deadline、并发上限，越界明确报错 | 可扩展但有资源边界 |
| 错误 envelope 携带 prompt 片段 | 报告只保存最小脱敏证据；`quality_report` 不返回完整 prompt；CORS 错误响应仍按最小数据原则审查 | 发布错误比普通错误更敏感，不能扩大暴露 |
| MCP tool 未缓存被当成通过/失败 | 未缓存返回 `INDETERMINATE` + `MCP_TOOL_LIST_UNAVAILABLE`；不连接 server | 不能用一个空列表证明工具存在或不存在 |
| API user 通过 RBAC bypass 管理规则 | controller + service 双重检查 `userType != API_USER` 且 human admin；规则激活单独确认 | 平台治理权限不能被 API user 的通配权限继承 |

规则管理员可以管理规则，不自动获得其他用户 private Agent 的读取权限。规则 state 的脱敏与 diff 预览禁止回显密码。正式发布、规则激活/停用、试测付费模型有明确动作和预算提示；质检过程不偷偷触发外部业务发布。

### 10.2 预算与恢复策略

- 首版建议每个 Agent 同时最多一个 publish evaluation、每调用者最多两个、每 server 实例最多六个模型评估批次；用独立 semaphore/容量指标保护内部调用，不依赖仅覆盖 Gateway HTTP 的 admission interceptor。
- 单次 run deadline、调用次数、input budget 和 output size 是强制边界，规则管理员不能把它设为无限。限值变更有审计。
- provider 网络/5xx/429 可以在剩余 deadline 内最多重试一次，并尊重 Retry-After；没有剩余预算直接 ERROR。不为 FAIL、低概率或不确定结果自动重试，不自动换模型投票。
- 默认固定 decision route，无静默 chat fallback；route 不可用时 BLOCK 不放行。管理员可回滚错误 policy revision，必须留下原因与影响；恢复管理 API 不受该 Agent 门禁阻断。
- 模型使用量显式记录 caller / agent / run / rule group / provider / upstream model / input-output tokens。复用现有 quota/pricing服务前先确认其 decision 类型支持；未接入不能声称已有扣费或成本告警。
- 初版定价或 usage 缺失时按保守输入估算预算并标 `cost_unknown`，不得记成 $0；用 calls/tokens/deadline硬限制兜底。是否按 API user 配额或平台治理预算归属见 Review 清单。

**Decision route 故障的运维预案（v0.3 修订）：**BLOCK 规则在 provider 故障时阻断发布是预期行为。运维应能：查看受影响 run、回滚到上一个可用 policy revision、在明确审计和双人确认下临时 suspend decision rules（仅管理员，不是普通用户 bypass），并在恢复后重新启用。任何临时 suspend 都必须记录 actor、原因、范围和到期时间。`quality-checks` 预检也受同样的并发和预算限制。

**理由：**新增规则意味着新增内部模型流量，不能绕过原来仅在外层 API 的限流。只重试技术故障，不重试质量 verdict，避免模型随机性成为发布绕过渠道。

### 10.3 审计、指标与保存策略

每次 run 记录：Agent/actor、候选与规则指纹、规则状态、检查器版本、问题规格 hash、requested/actual model、route fingerprint、概率/分数、error category、耗时、usage、是否已提交和 receipt。内部 Provider 调用显式接入 Hub audit 或专用 quality audit；不假设已经经过 GatewayDecisionController。每次成功发布追加 `AgentPublicationRecord`，保存 gate result、candidate/metadata hash、policy/route fingerprint、ruleResultDigest；报告 TTL 可以短于 publication record 的审计要求，但报告过期后仍能用摘要证明当次发布。

指标：`quality_run_total`（mode/result）、`quality_precheck_total`、`quality_warn_total`、`quality_rule_result_total`（rule/status）、p50/p95/p99 gate/rule latency、model error rate、INDETERMINATE rate、`quality_route_drift_total`、`mcp_tool_unverified_total`、预算/容量拒绝、CAS conflict、shadow 与 enforcing 的阻断比例、cost_unknown。Agent ID/user ID不放高基数 metrics label，只放受控日志字段。

建议告警起点（需 shadow 数据调整）：5 分钟模型 ERROR 超 5%、p95 超 30s、INDETERMINATE 突升、规则激活后拒绝率异常跳变。告警不自动放宽 BLOCK；管理员查看后回滚明确 policy revision。

默认持久报告仅保存脱敏证据和指纹，建议 90 天；publication receipt 与追加式 publication record 按 Agent 发布审计保存。若需要精确输入重放，可受控加密保存 snapshot JSON，建议 30 天，独立访问权限、密钥与清理任务。不能把敏感全文复制进普通 action/trace log。没有保存全文时报告明确“可追溯版本和摘要，不支持恢复未归档原输入”。

**理由：**概率系统需要观察误判和漂移，审计也必须能区分“检查过”“通过了”“实际上发布了”。最小数据默认值避免为了质量检查扩大秘密暴露与长期保存成本。

## 11. 实施计划、验证与上线

**本节为 Review 后的计划；用户已于 2026-11-14 授权按本计划实施（见 §14.1）。**

### 11.1 分阶段计划

| 阶段 | 工作 | 必须完成的验证 | 初衷与理由 |
|---|---|---|---|
| P0 设计确认 | 关闭第 13 节范围/语义问题，固定首版规则契约和判定区间 | 用户 Review 记录；需求 A1–E4 无遗漏 | 避免实现阶段才发现规则含义不同 |
| P1 内核与并发 | DTO/repository/registry、metadata-only snapshot、published metadata snapshot、definitionRevision/CAS、`executionCandidateHash`/`metadataHash`、decision 契约扩展（route/deadline）、typed report/error、publication record，接入共享发布 service | 单元/存储并发/序列化启动测试；原成功响应兼容；所有写 Agent 路径审计；candidate 只构建一次 | 后续模型接入不能建立在不可靠发布写入上 |
| P2 六类规则 | A1（含 MCP server/tool 拆分）、A2、实际时间 provenance、共享 `EffectiveCapabilityResolver`、B1/B2/B3/C1 decision adapter，严格响应与预算；workflow snapshot gate | 下节矩阵、metamorphic/adversarial cases、B2 parity test、MCP 缓存命中/未命中、workflow snapshot | 先保证用户的具体规则，而不是只有空框架 |
| P3 UI 与治理 | 保存-检查-发布、WARN 交付、issue 定位、published vs draft metadata、数量配置、decision rule 新建/试测/shadow/激活/回滚 | UI E2E、权限/越权测试、模型不可用、route drift、WARN 读取和 stale flow | 扩展和错误 UX 是核心交付，不推到远期 |
| P4 dev / shadow | CI 构建并发布镜像，dev E2E；A1/A2/C1 先 BLOCK，B1/B2/B3 先 shadow；历史 Agent dry-run 扫描；调阈值 | 实测 latency、budget、FPR/FNR、不确定率；没有静默未检查项；受影响 Agent 清单 | 模型校准必须基于项目数据，不靠示例阈值 |
| P5 enforcing | 内置规则转 BLOCK，自定义规则按授权策略生效；监控告警和 rollback drill | 拒绝不改旧发布快照/元数据、API/workflow 无法绕过、WARN 可见、回滚可用、catalog 通知仅成功后发生 | 开启门禁后具备可恢复的操作手段 |

现有已发布 Agent 不自动重新执行或下线；重新发布必须检查。可信 seed 不能通过请求参数绕过，新 UI/API 不提供 `skip_quality_check`。

### 11.2 规则验收矩阵

| 需求 | 必测样例 | 预期 |
|---|---|---|
| A1 | 已删除 skill、未知 builtin、缺失 API operation、不可用 LLM-call/subagent、缺失 dataset | 阻断，逐项报告类型/位置/建议；DB timeout为 ERROR，不称“已删除” |
| A1 MCP | 删除 MCP server、禁用 server、非法 config、固定 MCP tool 缓存命中/未命中 | server 级阻断；tool 级缓存命中时按结果；未命中返回 INDETERMINATE（首版 SHADOW 仅审计），不连接 server |
| A2 | 每类别 49/50/51；重复 ID；同名不同 source；整组 builtin；MCP server/单 tool不同口径；UI 将上限改 10 | 50允许/51失败；参数变更按新 revision生效；规范 ID不误去重 |
| B1 | “按本周汇总”、明确日期操作、历史背景引用、禁用时间行为、固定日期转换；有效/无效/未使用/转义变量；managed prompt含时间 | 默认窄模式按 CURRENT_TIME_DEPENDENT 判定；严格模式差异明确；只认实际模板注入 |
| B1 类型差异 | AGENT渲染 system.time；LLM_CALL 只存字面变量；后续 user query中的花括号 | LLM_CALL不假通过；不把用户输入/appendix花括号当发布模板错误 |
| B2 | 读文件但仅配置 write、写文件但仅 read、网页search/fetch分开、只写代码文本/实际运行、实际媒体/仅写prompt | 只阻断真实执行缺能力；每缺能力单独 issue |
| B2 装配 | builtin组/单工具、自动补工具、冲突优先级、server sandbox关闭、CLI注入不适用于 server、skill/dataset/memory/multimodal/sandbox 自动补工具 | gate 投影 == runtime registry；parity test 通过；不可通过无效provider或错执行面绕过 |
| B3 | 明确命名tool/API/MCP/skill/subagent/dataset无绑定、同名错类型、source冲突、未知自然语言名字、仅禁止/引用/示例、动态发现 | 按首版静态绑定策略；候选能精确定位，未知引用字段级提示不虚构ID |
| C1 | 空白、仅名字/标签、宣传语、短功能介绍、中文/英文/混合语种 | 无功能失败，简洁有效功能介绍通过 |
| C1 元数据 | 发布后 update 改空 description、只改名字、改已发布 description | 目录仍显示 published metadata；下次 publish 才检查 C1；不得出现“已发布但简介为空” |
| 扩展 | UI新增“输出要求”规则、改criteria/threshold、保存草稿未激活、shadow转BLOCK、retire与回滚 | 不改代码可新增DECISION；未激活不影响发布；历史含义不变 |
| WARN 交付 | WARN 规则失败、SHADOW 结果、发布成功 | publish 200 + headers + latest report；UI 显示警告；SHADOW 不对作者展示 |
| Workflow snapshot | OWNED_EDITABLE snapshot、PUBLISHED snapshot、receipt 过期 | editable snapshot 走门禁或要求 PASS receipt；失败不写版本 |
| 路由漂移 | alias 改 provider、provider 故障、route fingerprint 不匹配 | ERROR/STALE，不静默换模型 |
| fork | 模板有坏引用、过滤后配置 | fork 对过滤后配置检查；丢弃引用记 WARN；失败不创建 PUBLISHED |
| 结果契约 | 缺答案、unknown type/label、NaN、概率越界、score越界、provider timeout/429、预算耗尽 | ERROR/INDETERMINATE，不放行；typed错误包含规则与恢复建议 |

语义模型测试不能只断言一个固定概率：mock adapter 验证状态机；真实模型用标注 verdict和错误类别评估，概率区间允许波动。模型 fixture按语言、否定/引文、意图类别分层，保留独立 holdout，测试阈值不使用同一测试集反复优化。

### 11.3 非功能与系统验收

- **发布副作用**：BLOCK FAIL/ERROR/INDETERMINATE不改变 publishedConfig、published metadata、publishedAt、receipt；预检不改变草稿或发布内容；无业务 tool/MCP/skill调用。
- **元数据**：发布后修改 description/name 不影响目录已发布快照；下一次 publish 重新检查 C1。
- **Workflow**：OWNED_EDITABLE snapshot 不能绕过门禁；失败不写版本；PUBLISHED snapshot 使用已发布配置。
- **WARN**：WARN 失败不阻断，但必须可被作者读取；publish 成功响应头包含 run id。
- **路由**：provider/upstream 改变时 receipt 与报告记录 drift；BLOCK 规则不静默换模型。
- **审计耐久**：publication record 不依赖 90 天报告；报告过期后仍能证明 gate result 摘要。
- **并发**：Save/Publish、Publish/Publish、Delete/Publish、managed prompt更新、policy切换、subagent发布/删除、provider route变化；无 lost update、无 delete 后重建。
- **原入口**：API、self harness、LLM-call publish、comparison adoption/revert、fork、builtin reset、Workflow snapshot 按第3节和第8.6节契约验证。
- **错误兼容**：旧成功API不变，旧客户端至少看到可用 message；新UI读取完整问题数组而非解析message；内部工具调用至少文本化全部 issue，结构化保留待 R-AI-12 决定。
- **权限**：普通协作者仍能发布；非admin/API user不能激活或停用规则；无权 fixture/报告不能读取；无资源枚举泄漏。
- **预算与恢复**：调用 cancellation真实生效，无僵尸请求；重复 key不重复评估，崩溃/响应丢失/COMMITTING恢复不自动重发publish；容量拒绝有 Retry-After。
- **校准**：报告每规则 FPR/FNR、precision/recall、INDETERMINATE/ERROR比例与样本分母；别将“不确定”算通过或正确拒绝。建议阻断语义规则 holdout违规 precision目标≥95%、漏检率≤5%作为起点，但需业务Review、样本规模及置信区间；不得称“零误判”。
- **性能**：建议真实dev负载p95≤30s且硬deadline45s；实际达不到时先减少不必要重复输入/归因，再Review异步路径，不能无限延长HTTP超时。

**理由：**质量门禁不仅要“能检出几个坏例子”，还要避免误阻断、绕过、丢编辑和发生成本失控。每阶段包含对应验证，实施后主动执行dev E2E，但本次仍止于设计Review。

## 12. 关键决策记录（ADR 摘要）

| ID | 决策 | 初衷/理由 | 主要备选及为何未选 | 代价 |
|---|---|---|---|---|
| ADR-01 | 共享发布service内强制gate，预检可选 | API和内部工具不能绕过UI | 仅前端校验：绕过容易且内容过期 | 发布路径增耗时 |
| ADR-02 | CODE/DECISION/HYBRID统一契约 | 用精确事实+语义判断各取所长 | 全正则漏意图；全模型误判确定性事实 | 需要能力投影与adapter |
| ADR-03 | 版本化规则+policy原子激活 | 控制策略变化可追踪可回滚 | mutable规则直接生效：历史含义丢失 | 版本与清理治理 |
| ADR-04 | 模型没有发布权限，不要求其生成理由 | 当前decision API不输出解释且judge可被注入 | chat-judge生成“通过+理由”：输出风险/成本/幻觉 | 静态建议与字段级证据不总能精确到句 |
| ADR-05 | BLOCK未知/故障不放行 | 错误不能成为门禁绕过 | fail-open：不满足用户“有问题不可发布” | 模型故障会暂时影响发布 |
| ADR-06 | 同步有界首版 | 满足点击Publish即时反馈，少运维构件 | durable async queue：首版复杂度高；无限同步：不可控 | 大输入/大量规则需明确拒绝或未来异步 |
| ADR-07 | Agent自身CAS，外部依赖最终复核 | 当前Mongo/PG无共同事务，精确声明保证 | read后replace会覆盖；全局分布式锁成本高且跨写方难保证 | 外部变化仍有极小竞态 |
| ADR-08 | 首版不复用跨publish PASS cache | 降低stale判定风险 | 长期“质检通过token”：invalidation复杂 | 预检后发布重复少量模型成本 |
| ADR-09 | UI不能写任意代码，只注册checker/限定decision spec | 可扩展但避免控制面RCE | Python/JS在线规则：安全与隔离超首版范围 | 新CODE算法仍要代码发布 |
| ADR-10 | 默认 `CURRENT_TIME_DEPENDENT`、builtin-only、静态命名绑定 | 减少无意义误阻断；严格模式仍可配置 | 默认严格字面：日期格式指令也被误杀；自动承认MCP等价/动态发现：可能漏缺配置 | 部分合法runtime动态模式会被拒，需Review |
| ADR-11 | trusted seed不在启动中依赖模型 | 不因外部模型故障导致server无法启动或无法恢复规则 | 启动同步judge：可用性/循环依赖风险 | 明确例外，CI验证且不开放用户bypass |
| ADR-12 | typed report扩展现有error envelope | 保留旧成功API及旧message读取 | 全局迁移RFC9457：无必要破坏兼容 | client/error mapper需同步改动 |
| ADR-13 | 发布 name/description 快照 | C1 不能在发布后被 update 绕过 | update 时执行 C1：目录仍可能短暂不一致；不做：需求失效 | catalog/view 需读快照 |
| ADR-14 | MCP server / MCP tool 分级 | metadata-only 且不启动 MCP 会话 | 连接 server 验证：违反非目标且有副作用；不校验：漏检 | 需要 tool 列表缓存才能 BLOCK 单 tool |
| ADR-15 | workflow OWNED_EDITABLE snapshot 纳入门禁 | 等价可执行配置路径不能绕过 | 延后：门禁形同虚设；只做普通 publish：留下后门 | workflow 保存版本增加检查耗时 |
| ADR-16 | decision 契约返回 route fingerprint | 发布审计必须证明谁判定 | 只存 alias：路由漂移不可见；二次 resolve：可能与实际调用不一致 | 改 core-ai 共享契约 |
| ADR-17 | 追加式 publication record | 报告 TTL 短于审计要求 | 只存最新 receipt：历史发布不可证明 | 多一个集合与写入 |

## 13. 待 Review 决策

以下问题已有建议，用户可以直接接受或按编号修改，不需要重新描述整个方案。

| ID | 待确认事项 | 本稿默认/建议 | 影响 |
|---|---|---|---|
| Q1 | 时间规则是否连固定日期/格式指令也必须system.time | v0.3 采纳 AI 建议：默认 `CURRENT_TIME_DEPENDENT`（不要求格式/固定日期）；严格字面模式作为可选 `ANY_TEMPORAL_INSTRUCTION` | B1误报率与需求边界 |
| Q2 | builtin必须直接提供，还是允许经验证API/MCP/子Agent等价实现 | 首版BUILTIN_REQUIRED；子Agent按其 published effective capability 检查；不凭description认定等价 | B2可执行路径与兼容性 |
| Q3 | 命名资源能否仅靠动态hub发现而不绑定 | 默认不豁免，命名静态调用须绑定 | B3与动态agent工作流 |
| Q4 | 数量是直接引用数还是展开后有效工具数，50是否默认 | 首版tool_refs_total、skills、subagents各50；有效工具数另规则 | A2计数与阈值 |
| Q5 | 模型不确定时是否阻断 | 阻断但不标确定违规；允许同 candidate、新 run 重试（同 idempotency key 仍返回原报告）；无普通force publish | 可用性与模型误判处理 |
| Q6 | LLM_CALL时间模板支持 | 默认不改runtime，适用规则时阻断并解释；如要求支持需扩展render契约 | 特殊definition type实施范围 |
| Q7 | Workflow发布的内嵌Agent snapshot是否首版纳入 | v0.3 采纳 AI 建议：纳入；OWNED_EDITABLE snapshot 走同一门禁或要求 PASS receipt（见8.6） | 防止等价执行配置绕过 |
| Q8 | trusted-seed/init以及builtin恢复路径的例外 | 启动seed代码/CI检查；交互reset仍gate；不开放user bypass；migration 例外逐条登记 | 启动可用性与既有Agent迁移 |
| Q9 | 模型、初始阈值、同步预算与费用归属 | 明确decision alias；0.2/0.8仅校准起点；45s deadline；首版建议平台治理预算 + per-actor 限流 | 成本/限流/体验 |
| Q10 | 文档定义的外部依赖一致性强度可否接受 | Agent自身强CAS，外部依赖最终复核，不承诺跨库原子；publication record 提供耐久审计 | 如要求更强需版本pin/删除限制 |
| Q11 | MCP tool 存在性首版是否要求 BLOCK | 默认 `CFG.MCP_TOOL_AVAILABLE` SHADOW；实现 tool 列表缓存并测试后再转 BLOCK；未缓存时 INDETERMINATE | A1 覆盖范围与误阻断 |
| Q12 | 发布元数据快照 vs update 时执行 C1 | 首版采用 `publishedName` / `publishedDescription` 快照；若暂不做快照，则 update 必须执行 C1 | C1 绕过与目录行为 |

## 14. Review 记录与修改记录

### 14.1 用户 Review 记录

**2026-11-14 用户授权实施：**用户明确要求按本设计 v0.3 实施 Agent 发布质量门禁，并授权在 §14.1
记录「用户授权实施（2026-11-14）」、把状态从 Draft 改为 Approved / Implementing。设计内仍标 OPEN
的决策按文档默认或最保守方案落地，选择记录如下（R-IMPL 系列）。该授权针对本特性实施，不等同于对
未来规则阈值、模型或运营策略的永久批准。

| Review ID | 日期 | Reviewer | 意见原文/范围 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| R-U-01 | 2026-11-14 | 用户 | 按 v0.3 实施；OPEN 决策按默认/最保守方案落地并记录 | 进入实施；状态改为 Approved / Implementing | 全文、§14.1 | ACCEPTED / IMPLEMENTED |
| R-IMPL-01 | 2026-11-14 | core-ai（实施记录） | B1/B2/B3 首版是否直接 BLOCK | 按 §11.1 P4 分阶段：种子策略中 B1/B2/B3 为 SHADOW，A1/A2/C1 为 BLOCK；管理员可在测试后激活 BLOCK | §4.3、§11.1、§13 | IMPLEMENTED |
| R-IMPL-02 | 2026-11-14 | core-ai（实施记录） | 质量数据存 Mongo 还是 PG | 实现 `AgentQualityStore` 双实现：`sys.store.mode=pg` 用 PostgreSQL（Flyway V38，jsonb 文档表），否则 Mongo；与 Agent 存储模式一致，避免切换存储模式后门禁消失 | §7.1 | IMPLEMENTED |
| R-IMPL-03 | 2026-11-14 | core-ai（实施记录） | 报告 TTL 与清理 | 首版记录 `expires_at`（90 天）但不启用自动删除任务；publication record 追加保存，审计不依赖报告 | §10.3 | IMPLEMENTED（清理任务待后续） |
| R-IMPL-04 | 2026-11-14 | core-ai（实施记录） | 幂等键与并发 STARTED | 同 key 同候选：终态复用报告，STARTED 返回 409 + runId；STARTED 超过 90s 标记 ABORTED 并要求新 key；不自动重发 publish | §8.4 | IMPLEMENTED |
| R-IMPL-05 | 2026-11-14 | core-ai（实施记录） | fork 门禁失败后的行为 | 个人助手 fork 先对过滤后配置执行门禁；失败时创建 DRAFT（owner 仍可运行草稿），被丢弃引用仅记录日志；不创建 PUBLISHED | §3、§11.2 | IMPLEMENTED（WARN issue 展示待补） |
| R-IMPL-06 | 2026-11-14 | core-ai（实施记录） | workflow PUBLISHED snapshot 的 receipt 不一致 | 记录 warning 日志并继续使用已发布配置；OWNED_EDITABLE 走完整门禁，失败不写版本 | §8.6 | IMPLEMENTED |
| R-IMPL-07 | 2026-11-14 | core-ai（实施记录） | 模型路由漂移复核 | RuleRevision 可 pin `expectedRouteFingerprint`；未 pin 时记录实际 route；提交前用 route resolve 复核，不二次调用模型 | §6.2、§8.3 | IMPLEMENTED |
| R-IMPL-08 | 2026-11-14 | core-ai（实施记录） | MCP 单 tool 存在性 | 首版未实现持久化 tool 列表缓存；`CFG.MCP_TOOL_AVAILABLE` 返回 INDETERMINATE（issue_reason=MCP_TOOL_LIST_UNAVAILABLE），种子为 SHADOW | §5.1、§13 Q11 | IMPLEMENTED（缓存待后续） |
| R-IMPL-09 | 2026-11-14 | core-ai（实施记录） | B3 候选数量 | 每次最多 12 个候选 + 1 个全局未覆盖问题；仅识别显式 ID 与已配置资源精确名称；未知名称只给字段级问题 | §5.5、§6.5 | IMPLEMENTED |
| R-IMPL-10 | 2026-11-14 | core-ai（实施记录） | 异步/清理 reconciler | 首版同步、有界；STARTED 过期在下次请求时 CAS 为 ABORTED；未实现后台 reconciler/异步发布队列 | §8.1、§8.4 | NOT IMPLEMENTED（后续） |
| R-IMPL-11 | 2026-11-14 | core-ai（实施记录） | 自定义规则 BLOCK 激活门槛 | 首版要求最新 revision 至少有一次匹配 expected 的试测才能激活非 SHADOW；完整正反样例集、FPR/FNR holdout 仍按 P4 校准，未宣称准确率 | §7.3、§7.4 | IMPLEMENTED（完整 TestCase/Run 待补） |
| R-IMPL-12 | 2026-11-14 | core-ai（实施记录） | 规则 revision 与测试结果存储 | RuleRevision 不可变；试测结果记录在逻辑规则的 `lastTestedRevision/lastTestedAt/lastTestSummary`，未拆独立 RuleTestCase/TestRun 集合 | §7.1、§7.4 | IMPLEMENTED（结构简化） |
| R-IMPL-13 | 2026-11-14 | core-ai（实施记录） | UI 交付 | Settings → Agent Quality 提供规则列表、参数/阈值编辑、试测、shadow/BLOCK/WARN 激活、policy 回滚；Agent 编辑器提供 Save→Check→Publish、WARN 交付、issue 定位与 published/draft 元数据 | §9.4、§11.1 P3 | IMPLEMENTED |

状态使用 `OPEN / ACCEPTED / REJECTED_WITH_REASON / IMPLEMENTED_IN_DOC / CONFIRMED`；ACCEPTED表示接受修改意见，不等于用户已经批准全部设计。最后必须记录是否允许进入实施阶段。

### 14.2 AI 技术评审记录（非用户批准）

评审日期：2026-11-12。评审范围：v0.2 全文、实际源代码、发布入口与一致性边界。评审结论：**Approve with changes**；方向正确，但 6 个问题需在 v0.3 定案，不按 v0.2 直接实施。以下状态只表示文档处理，不是用户批准。

| Review ID | 日期 | Reviewer | 意见摘要 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| R-AI-01 | 2026-11-12 | core-ai（AI 技术评审） | C1 只在 publish 检查，发布后 update 可直接改空 description，目录仍展示 | 增加 `publishedName` / `publishedDescription` 快照；`metadataHash` 纳入绿灯；不实现快照则 update 执行 C1 | 1.2、5.6、7.1、8.2、9.2、11.2、ADR-13、Q12 | IMPLEMENTED_IN_DOC |
| R-AI-02 | 2026-11-12 | core-ai（AI 技术评审） | A1 的固定 MCP tool 存在性需要连接 server 才能验证，与 metadata-only 非目标冲突 | 拆为 `CFG.MCP_SERVER_AVAILABLE` / `CFG.MCP_TOOL_AVAILABLE`；tool 级首版 SHADOW，未缓存 INDETERMINATE | 5、5.1、10.1、11.2、ADR-14、Q11 | IMPLEMENTED_IN_DOC |
| R-AI-03 | 2026-11-12 | core-ai（AI 技术评审） | Workflow 的 `OWNED_EDITABLE` snapshot 绕过 publish | 纳入 8.6：走同一门禁或要求 PASS receipt；失败不写版本 | 1.2、3、8.6、11.2、ADR-15、Q7 | IMPLEMENTED_IN_DOC |
| R-AI-04 | 2026-11-12 | core-ai（AI 技术评审） | WARN / SHADOW 在成功 publish 时没有交付路径 | 增加响应头 + `GET latest` 报告；UI 显示 WARN | 9.1、9.3、9.4、11.3 | IMPLEMENTED_IN_DOC |
| R-AI-05 | 2026-11-12 | core-ai（AI 技术评审） | `DecisionProvider` 不返回 provider / upstream / route fingerprint，无法审计模型路由 | 扩展 decision 契约；RuleRevision pin `expectedRouteFingerprint`；drift 记 ERROR | 6.2、6.3、P1、ADR-16 | IMPLEMENTED_IN_DOC |
| R-AI-06 | 2026-11-12 | core-ai（AI 技术评审） | B2 能力投影没有与 runtime 共用事实源，存在漂移风险 | 抽出共享 `EffectiveCapabilityResolver`；加 parity test | 4.1、5.4、11.2、P2 | IMPLEMENTED_IN_DOC |
| R-AI-07 | 2026-11-12 | core-ai（AI 技术评审） | receipt 只保留最新一份，报告 90 天过期，发布审计不可耐久 | 增加 append-only `AgentPublicationRecord` + `ruleResultDigest` | 1.2、7.1、8.4、10.3、ADR-17 | IMPLEMENTED_IN_DOC |
| R-AI-08 | 2026-11-12 | core-ai（AI 技术评审） | fork 先过滤坏引用再写 PUBLISHED，与“gate 继承配置”矛盾 | 明确对过滤后配置执行门禁，丢弃引用记 WARN；失败不创建 PUBLISHED | 3、11.2 | IMPLEMENTED_IN_DOC |
| R-AI-09 | 2026-11-12 | core-ai（AI 技术评审） | B3 缺少引用语法；subagent 责任检查深度不清 | 增加引用语法表；subagent 只查 published effective capability，不递归 prompt | 5.4、5.5 | IMPLEMENTED_IN_DOC |
| R-AI-10 | 2026-11-12 | core-ai（AI 技术评审） | 规则管理员身份未定义；API user 会继承 RBAC bypass | 明确 human admin predicate + controller / service 双重检查 | 9.1、10.1 | IMPLEMENTED_IN_DOC |
| R-AI-11 | 2026-11-12 | core-ai（AI 技术评审） | fail-closed 在 decision provider 故障时缺少运维预案 | 增加 runbook：回滚 policy、受审计临时 suspend、恢复 | 10.2 | IMPLEMENTED_IN_DOC |
| R-AI-12 | 2026-11-12 | core-ai（AI 技术评审） | 内部 caller 丢结构化报告；`PublishLLMCallTool` 只有字符串 | 首版文本化全部 issue；是否扩展 `ToolCallResult` 结构化字段待实现决定 | 3、9.2 | OPEN |
| R-AI-13 | 2026-11-12 | core-ai（AI 技术评审） | owner 可运行 editable 草稿，门禁不是运行时门禁 | 在非目标明确 scope；不改变现有草稿执行策略 | 1.3 | IMPLEMENTED_IN_DOC |
| R-AI-14 | 2026-11-12 | core-ai（AI 技术评审） | publish 现在多次构建 / 解析，检测内容可能不等于发布内容 | 明确 `buildCandidate -> evaluate -> commit(candidate)`；managed prompt 只解析一次 | 1.2、6.1、8.1 | IMPLEMENTED_IN_DOC |
| R-AI-15 | 2026-11-12 | core-ai（AI 技术评审） | 错误 envelope 手工拼接，`quality_report` 需要可靠序列化；CORS 下敏感片段需最小化 | 明确 typed serializer + 最小脱敏；不把完整 prompt 放进 error body | 9.2、10.1 | IMPLEMENTED_IN_DOC |
| R-AI-16 | 2026-11-12 | core-ai（AI 技术评审） | 历史 Agent 可能在 A1 生效后无法发布 | P4 增加 dry-run 扫描和受影响清单，再切 ENFORCING | 11.1、11.3 | IMPLEMENTED_IN_DOC |

**AI 技术评审未决项：**R-AI-12（内部 tool result 结构化字段）仍是 OPEN；Q1 / Q2 / Q5 / Q7 / Q11 / Q12 虽然已在文档中按 AI 建议落稿，但仍需用户确认。AI 评审不授权实施。

### 14.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 | 用户确认 |
|---|---|---|---|---|---|
| v0.1 | 2026-11-12 | core-ai | 独立初稿：原需求、源码事实、六类规则、扩展契约、UI、发布一致性、错误、安全、验证和ADR | 给用户完整Review对象；不参考已有design文档 | 待Review |
| v0.2 | 2026-11-12 | core-ai | 自查修订：提交状态fencing、BLOCK/SHADOW预算隔离、外部一致性和重检风险边界、激活质量指标、概率谓词、输入错误分类、重放承诺 | 消除设计矛盾与无法兑现的保证；未改变用户原始需求 | 待Review |
| v0.3 | 2026-11-12 | core-ai | AI 技术评审修订：发布元数据快照、MCP server/tool 拆分、workflow snapshot 准入、WARN 交付、decision route 指纹、B2 单一投影、publication record、fork/B3/admin/runbook/内部 caller 边界；更新验收与 ADR | 处理 AI 技术评审 P0/P1；不改变用户原始需求 | 待Review |
| v0.4（实施记录） | 2026-11-14 | core-ai | 记录用户实施授权与 R-IMPL-01..13 的落地选择（存储、SHADOW 种子、幂等/并发、fork、workflow、MCP tool 未缓存、试测与 UI） | 用户授权实施；OPEN 决策按默认/最保守方案落地并留痕 | 用户授权实施（2026-11-14） |
| v0.5（实施验证记录） | 2026-11-14 | core-ai | 记录 2.0.235–2.0.238 的实现与 dev E2E 结果、V38 迁移、剩余限制；交互式 builtin reset 改为全部候选检查通过后再写入 | 实施完成并完成 dev 验证；不改变用户原始需求 | 用户授权实施（2026-11-14） |

### 14.4 设计自查记录

本节只记录文档自查，不作为用户批准。

- 对照原始需求逐项覆盖 A1/A2/B1/B2/B3/C1/E1–E4；所有首版规则包含定义、理由、错误建议及验收样例。
- v0.3 根据 AI 技术评审修订：C1 元数据快照、MCP tool 分级、workflow snapshot、WARN 交付、decision route 指纹、B2 parity、publication record、fork/B3/admin/runbook/内部 caller 边界。
- 检查代码引用文件存在、JSON示例可解析、Markdown章节和代码围栏完整；新增源码引用已按工作区实际文件核对；未进行业务实现测试、模型校准或dev部署，相关指标仍为提案。
- 本次只修改此设计文档，未触碰业务代码，未创建commit。

### 14.5 Trusted seed / migration 例外清单（v0.3 新增）

| 路径 | 是否走模型门禁 | 控制 |
|---|---|---|
| `ProjectBuiltinAgents`、`SchemaMigrationVDefaultAgent`、`SchemaMigrationVIssueReporterAgent`、`SchemaMigrationVProjectAutoSubjects` | 否（启动 / migration） | 代码审查 + CI fixture + `trusted-seed` 审计标记；不向用户开放 bypass |
| `SchemaMigrationVProjectAttributionRepair`、`SchemaMigrationVProjectAttributionScope` | 否（仅 builtin project agents） | 同上；只改 builtin ID，不批量改用户 Agent |
| `SchemaMigrationVPublishedAgentSnapshotSecurity` | 否（安全迁移，回填所有 published config） | 同上；必须记录迁移原因、影响范围和回滚说明 |
| `PersonalAssistantService.buildFork` | 是（对过滤后的新配置） | 失败不创建 PUBLISHED；被丢弃引用记 WARN |
| 管理员 builtin reset | 是（交互式） | 先检查候选再写入；不因管理员身份跳过规则 |
| `WorkflowAgentSnapshotService` 的 `OWNED_EDITABLE` | 是 | 见 8.6；失败不写 workflow 版本 |
| `ComparisonAdoptionService` adoption / revert | 是 | 先建 candidate 再条件保存与发布 |
| `SelfHarnessDispatcher.publish_agent`、`PublishLLMCallTool` | 是 | 调用共享 publish service，不另走旁路 |
| `AgentPortService.importAgent` | 否（只创建草稿） | 不写 PUBLISHED；后续 publish 必须走门禁 |

任何新增直接写 `published_config` 或 `status=PUBLISHED` 的路径，必须先在此表登记，否则视为绕过门禁。

### 14.6 实施验证记录（dev，2026-11-14）

**代码与镜像：**主要实现 commit `63747b05`、`a242ee4f`、`e4f9e0a8`、`4e864c83`、`402fc1dd`、`60f9dc6e`，镜像 `2.0.235` → `2.0.239`。本次仅操作 dev（`FBRDevAKSCluster` / namespace `dev-ai`），未触碰 UAT / prod。

**静态与自动化检查：**`./gradlew :core-ai-server:check` 通过（compile、unit test、checkstyle、PMD、SpotBugs、JaCoCo）；`npm --prefix core-ai-frontend run build` 通过。

**迁移：**Flyway V38 已在 dev 应用（schema version 38），创建 `app.agent_quality_*` 与 `app.agent_publication_records`；启动种子为 8 条规则、policy revision 1（A1/A2/C1 为 BLOCK；B1/B2/B3 与 MCP tool 为 SHADOW），决策模型 `decision-1` 经 `azure-decision` 解析成功。

| 场景 | dev 实测结果 |
|---|---|
| 预检 | `POST /api/agents/:id/quality-checks` 返回 `PASSED`，含 runId、`executionCandidateHash`、`metadataHash`、`policyRevision`、route fingerprint |
| 正常发布 | `200`，响应头 `X-Quality-Run-Id/Gate-Result/Warn-Count`；receipt 记录 run、hashes、policy；`publishedName/Description` 快照写入 |
| 发布后改草稿 | 将 editable `description` 置空后，catalog 仍返回已发布 description，C1 不被 update 绕过 |
| C1 | 空 description 发布被 `422 AGENT_QUALITY_REJECTED` 拒绝，issue 含 `field_path=/description`、suggestion |
| A1 | 未知 builtin 引用发布被 `422` 拒绝，issue 含 `resource_type=BUILTIN`、`issue_reason=BUILTIN_NOT_REGISTERED`、可重试标记 |
| A1 MCP server | 不存在的 MCP server 发布被 `422` 拒绝，`CFG.MCP_SERVER_AVAILABLE` / `MCP_SERVER_NOT_FOUND` |
| A2 | 规则 revision 参数改为 `subagents=5` 后，对 11 个 subagent 的候选试测为 `FAIL`（actual=11, max=5），随后恢复 `50`；规则变更走 revision，不修改历史 revision |
| B1 | 时间指令 + 无 `{{system.time}}` 的试测为 `FAIL`，决策概率约 0.99，返回 route fingerprint |
| B2 / B3 | “使用 read_file 但无对应 builtin”“引用 @subagent:research 但无绑定”试测均为 `FAIL` |
| WARN | 将 B1 激活为 `WARN` 后发布返回 `200`、`warnCount=1`；report 中该 issue `enforcement=WARN`、状态可读 |
| 策略回滚 | 以历史 `policy_revision` 重新激活成功，后续发布回到原始绑定 |
| 自定义 DECISION 规则 | 创建 `201`；未试测激活被 `400` 拒绝；试测 `FAIL`（概率约 0.014）；激活后发布 `200` + WARN，report 含自定义规则 key；回滚成功；retire 后 lifecycle=`RETIRED` |
| 部署健康 | rollout 2/2 Running，无 CrashLoop；decision 路由未漂移 |

**剩余限制 / 未完成验证：**

- A2 的完整“发布被门禁阻断”链路未在 dev 用真实 Agent 跑通：旧发布前置检查会先拒绝合成的不存在 subagent ID（`400 agent has an unavailable sub-agent dependency`）。规则本身的计数与超限结果已通过规则试测和单元测试验证；要跑完整链路需创建 51 个真实 subagent。
- 管理 UI 与 Agent 编辑器的视觉 E2E 未执行：验证时宿主桌面处于锁屏状态，无法驾驶内置浏览器。前端构建通过、API 契约与组件 wiring 已审查；issue 的“定位”动作已按 field_path 滚动到 description / prompt / tools / skills / subagents / datasets 对应区块。P3 视觉验收仍待人工确认。
- `CFG.MCP_TOOL_AVAILABLE` 仍为 SHADOW / INDETERMINATE（R-IMPL-08），报告 TTL 清理与后台 reconciler 未实现（R-IMPL-03 / R-IMPL-10）。
- 启动初始化与 migration 保持 trusted seed，不调用模型门禁；交互式 builtin reset 已改为“全部候选先检查、全部通过后统一写入”，避免部分重置。

## 15. 参考资料

- [S1] [Kubernetes — Validating Admission Policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)：规则/参数/绑定分离、验证方式、failure policy、可操作消息。
- [S2] [OPA Gatekeeper — Introduction](https://open-policy-agent.github.io/gatekeeper/website/docs/)；[OPA — Policy Primer via Examples](https://www.openpolicyagent.org/docs/kubernetes/primer.md)：policy决策与admission/audit执行分离。
- [S3] [Microsoft Learn — Deploy and use Microsoft-Decision-1](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-microsoft-decision)：typed questions、无自由文本rationale、阈值与标注验证；[Model Catalog](https://ai.azure.com/catalog/models/Microsoft-Decision-1)。
- [S4] [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)：可机读问题、扩展字段、多项验证错误、JSON Pointer、安全信息最小化。
- [S5] [OWASP — LLM Prompt Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)：输入/规则隔离、零权限judge、输出校验、最小权限与对抗测试边界。

源码核对定位见第3节。本文没有复用项目已有设计文档结构或方案；未来如需要协调其他设计，应在Review后显式记录依赖与差异。

