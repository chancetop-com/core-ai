# Core AI Skill 发布质量门禁 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft（待用户 Review；AI 技术评审见 §14.2，AI 评审不构成用户批准） |
| 文档版本 | v0.4（第二轮 AI 技术评审修订；评审记录见 §14.2） |
| 创建日期 | 2026-11-16 |
| 需求提出者 / Reviewer | 用户 |
| 作者 | core-ai |
| 参考设计 | `docs/cn/design-agent-publish-quality-gate.md` |
| 范围 | Skill 发布（upload / editor save / repo import / sync / marketplace install）质量门禁、身份与元数据、权限声明、渐进式披露、Eval 数据集 |
| 本次交付 | 设计文档 + 2026-11-16 确定性门禁实现（见 §14.6）+ 2026-11-18 规则管理与自定义 DECISION 规则（见 §14.7）；内置语义规则与 run/report 持久化未实施 |
| 设计依据 | 用户原始需求、实际源代码、Agent Skills 生态的公开资料、AI 技术评审意见 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> 参考agent quality gate设计文档，设计一个skill publish quality gate，1. skill的name必须与skill.md中的文档内容一致 2. skill的desc必须有use when， scope 3. 如果skill文档中或者reference的脚本总有使用core-ai-session的sdk，调用tool, mcp, api-tool, subagent, llm-call, media-genration, decision等功能，需要再tools中声明对应的权限 4. 要有metadata: version, author, tags几个信息 5. 要有清晰workflow 6. 要有决策逻辑decision making的说明 7. 要有交付标准output specification 8. 要有失败处理说明Error Handling 9. 要有验收质量说明Quality assurance 10. 要有Evaluation dataset目录，测试如下维度
>
> | 测试维度 | 测试内容 |
> |---|---|
> | Trigger Accuracy | 应该触发时是否触发 |
> | Scope Accuracy | 是否错误处理不属于自己的任务 |
> | Tool Accuracy | 工具是否正确调用 |
> | Parameter Accuracy | 参数是否正确 |
> | Instruction Following | 是否遵循业务规则 |
> | Output Quality | 输出是否符合预期 |
> | Robustness | 边界条件下是否稳定 |
>
> 所有这些可以走渐进式披露 不一定在一个文件 至少eval的要单独一个文件和目录

原文中的拼写（如 `media-genration`、`需要再tools`）保持原样；实现与规则命名使用正确术语：`media-generation`、`在 tools 中声明`。

### 0.2 需求编号

| ID | 要求 | 性质 |
|---|---|---|
| SQ1 | Skill 的 name 必须与 SKILL.md 中的文档内容一致 | 必须阻断；结构部分确定性检查，语义部分模型判断 |
| SQ2 | description 必须包含 Use when 触发条件与 scope 边界 | 必须阻断；结构部分确定性检查，语义部分模型判断 |
| SQ3 | SKILL.md 或 reference 脚本使用 `core_ai_session` SDK 调用 tool / MCP / api-tool / subagent / llm-call / media-generation / decision 时，必须在 tools 中声明对应权限 | 必须阻断；静态可判定部分确定性检查，动态用法返回不确定 |
| SQ4 | 必须有 metadata：version、author、tags | 必须阻断 |
| SQ5 | 必须有清晰 workflow | 必须阻断；结构确定性 + 语义判断 |
| SQ6 | 必须有 decision making 的说明 | 必须阻断；结构确定性 + 语义判断 |
| SQ7 | 必须有 output specification / 交付标准 | 必须阻断；结构确定性 + 语义判断 |
| SQ8 | 必须有 Error Handling 说明 | 必须阻断；结构确定性 + 语义判断 |
| SQ9 | 必须有 Quality assurance / 验收质量说明 | 必须阻断；结构确定性 + 语义判断 |
| SQ10 | 必须有 Evaluation dataset 目录，覆盖 7 个测试维度 | 必须阻断；目录/文件/维度覆盖确定性检查，用例质量模型判断 |
| E1 | 所有发布路径共享同一门禁，不能只在前端或 CLI 检查 | 架构要求 |
| E2 | 结果包含具体规则、位置、证据与修复建议 | UX / API 要求 |
| E3 | 规则可扩展、参数可配置、策略可版本化与回滚 | 架构要求 |
| E4 | 支持渐进式披露：要求可以分布在 SKILL.md 与 reference 文件中 | 架构要求；链接必须可达且随包发布 |
| N1 | 发布检查不执行 skill、不调用工具、不访问外部网络、不产生业务副作用 | 安全边界 |
| N2 | 无法确定的事实不能伪装成通过；动态/不可分析用法返回不确定 | 正确性要求 |
| D1 | 先交付设计，用户 Review 后才能实施 | 工作边界 |
| D2 | 保留用户原始需求、Review 记录、修改记录；每项决策说明初衷与理由 | 文档约束 |

### 0.3 术语

| 术语 | 本文含义 |
|---|---|
| Publish（发布） | 任何使 skill 在 Hub 中新增或更新可见内容的写入：`POST /api/skills/upload`（含 `core-ai-cli skill push`）、Web 编辑器 `PUT /api/skills/:id`、repo 注册/同步、Marketplace 安装。不是独立的 draft → published 状态机 |
| Skill package | `SKILL.md` + 全部 resources（`references/`、`scripts/`、`assets/`、`eval/` 等），按 `SkillDigest` 计算指纹 |
| Progressive disclosure | SKILL.md 只放入口与路由，详细内容放 reference；Agent 先读 name/description，再按需读 SKILL.md 与 reference |
| Permission declaration | `allowed-tools` 中声明的 required capabilities；它是“需要什么”的声明，不是运行时授权 |
| Eval dataset | skill 包内独立的 `eval/` 目录与 `eval/eval.json`，描述 7 个维度的测试用例与断言 |
| Candidate | 本次将要写入存储的完整 skill package 快照；检查与写入必须是同一个对象 |

### 0.4 文档维护规则

- 本节原始需求保持原文；后续解释、调整和新增需求单独记录，不覆盖历史。
- 每条 Review 使用稳定 ID，记录意见原文、处理方案、影响章节、状态与确认人；没有用户确认不得写成 Approved。
- AI 技术评审单独记录在第 14.2 节，标注“AI 技术评审、非用户批准”；AI 评审结论不构成实施授权。
- 每次实质修改更新版本、修改记录及受影响的决策；不另拆零散记录。
- 本文“拟新增”的 API、类型、字段、规则与默认值是设计提案，不代表当前实现或已通过校准。
- 代码事实以工作区源码为准；设计文档可以引用行号，但实现时以实际代码为准。

## 1. 目标、边界与核心提案

### 1.1 核心提案

在 skill 的**服务端写入路径之前**增加一个 Quality Gate。它使用一次构建、不可变的 `SkillPublishCandidate`，执行版本化规则，收集结构化结果，再由纯代码聚合为“允许写入 / 质量不合格 / 无法完成质检”。只有通过时才把**同一个 candidate** 写入存储。

- **确定性事实由代码检查**：name 与目录/文档身份、metadata 字段、`allowed-tools` 语法、静态 SDK 用法覆盖、目录与文件存在性、JSON schema、7 维覆盖、链接可达性、文件/大小限制。
- **自然语言含义由决策模型判断**：description 的触发与 scope 是否具体、name 与文档主题是否一致、workflow 是否可执行、decision logic 是否说明分支与 fallback、output/error/QA 是否具体、eval 用例是否具体且可观察。
- **渐进式披露是一等公民**：五个内容要求（workflow / decision / output / error / QA）可以位于 SKILL.md 或它链接的 packaged reference 中；链接必须从 SKILL.md 可达、目标必须随包发布、不能只指向外部 URL。
- **Eval 必须独立**：`eval/` 目录 + `eval/eval.json`，不能把用例只写在 SKILL.md 里；发布门禁只验证数据集契约与覆盖，不在发布时执行 eval。
- **权限声明是消费契约**：`allowed-tools` 用类型化语法声明 skill 需要的 tool / MCP / api-tool / subagent / llm-call / media / decision 能力；门禁检查“用法有声明”，不宣称运行时已授权。
- **不确定和系统故障不能伪装成通过**：动态 SDK 用法、模型超时、路由漂移、输入过大都必须返回 `INDETERMINATE` 或 `ERROR`，由执行策略决定阻断或警告。
- **发布内容与检查内容一致**：frontmatter 只解析一次；registry 的 name/description/metadata/allowedTools/version 全部来自 candidate；commit 写入同一份 content/resources/digest。
- **已有 skill 不因门禁上线被追溯下架**：下一次写入时执行门禁；启动 seed / migration 走受信例外清单。

**初衷与理由：**skill 的 name/description 是 Agent 选择是否加载 skill 的唯一常驻信息；workflow、decision、output、error、QA 决定加载后能否可靠执行；权限声明决定脚本在 sandbox 中能否拿到所需能力；eval 决定这些判断是否可回归。把这些都交给正则或全部交给模型都不成立：正则无法判断“workflow 是否可执行”，模型也不应决定 name 是否等于目录名。混合检查让每条规则的判定边界可测试，最终写入动作仍由服务端控制。

### 1.2 目标与不变量

1. 所有面向用户的 skill 写入入口执行同一门禁：CLI push、Web upload、编辑器保存、repo 注册/同步、Marketplace 安装；不能通过 API / CLI / UI 绕开。
2. 实际写入内容必须等于被检测内容：candidate 只构建一次，frontmatter 只解析一次，resources 只读取一次，commit 写入同一对象；存储写入使用 revision CAS。
3. `SKILL.md` 的身份、description、metadata、allowed-tools 是唯一真源；`SkillDefinition` 的这些字段是 projection，不允许请求字段与文档内容不一致时静默覆盖。
4. 五个内容要求可以由 linked reference 满足，但链接必须从 SKILL.md 可达、目标必须存在于本次上传的 resources 中；不能只存在一个未引用的文件。
5. Eval 数据集必须是独立目录与独立文件；7 个维度每个至少 1 个用例；用例必须有可观察的 expected/assertions。
6. 代码检查只使用确定性事实；模型判断只输出受限结构化结果，不生成修复文本、不拥有写入权限。
7. 不确定、模型故障、路由漂移、输入过大不能放行；BLOCK 规则必须取得有效 PASS 才能写入。
8. 检查失败不改变现有 skill、不递增 revision、不写 receipt；新建 skill 不产生半条记录。
9. 已有 skill 保持可读可用；下一次写入才执行门禁；不自动下架、不自动删除。
10. 权限声明不是运行时权限系统；运行时仍由 Agent 配置、sandbox 与授权层决定能力。
11. 检查过程不执行 skill 代码、不启动 MCP 会话、不下载 dataset、不调用业务 API、不访问外部 URL。
12. 每次写入保存可审计 receipt；报告可以过期，publication record 与 receipt 不依赖报告保留期。

### 1.3 非目标

- 不证明 skill 在真实任务上的完整正确性；eval runner 是独立交付物，发布门禁只验证数据集契约。
- 不在发布检查中执行 skill、调用 tool/MCP/API、生成媒体、启动 sandbox 或读取 dataset 内容。
- 不把 `allowed-tools` 变成运行时强制授权；Agent 仍按自身配置决定工具，skill 的声明只用于发现、提示与未来运行时校验。
- 不检查外部 MCP server / API operation / subagent / LLM_CALL 是否对每个读者存在；skill 是跨 namespace 可移植的，外部资源存在性最多作为 WARN。
- 不扫描所有语言、混淆代码或动态生成的调用；静态分析器有明确边界，无法解析时返回 INDETERMINATE。
- 不替代人工评审、Marketplace 策展、红队测试或安全审计。
- 不自动修复文档、自动补权限、自动生成 eval 用例或自动改 name。
- 不引入任意 Java/JavaScript/Python 插件执行平台；规则扩展只允许已注册 checker 与受限 DECISION spec。
- 不承诺跨 Mongo/PG/对象存储的原子事务；只承诺 skill 文档自身的 CAS 与 receipt 一致性。

### 1.4 与 Agent 发布质量门禁的关系

本文复用 `docs/cn/design-agent-publish-quality-gate.md` 的以下模式：

| 复用的模式 | 在 Skill Gate 中的表现 |
|---|---|
| 服务端 admission，而非前端提示 | `SkillPublishService` 在写入前执行；所有入口共享 |
| 一次构建 candidate，检查与写入同一对象 | `SkillPublishCandidate`；frontmatter 只解析一次 |
| CODE / DECISION / HYBRID 与版本化规则 | `SkillQualityRule` / `SkillQualityRuleRevision` / `SkillQualityPolicy` |
| BLOCK / WARN / SHADOW 与 PASS / FAIL / INDETERMINATE / ERROR / SKIPPED | 直接复用同一状态语义与聚合原则 |
| 决策模型严格响应验证、路由指纹、预算与 fail-closed | 复用 `QualityDecisionGateway` 的语义；新增 skill 输入选择器 |
| Typed report + 追加式 publication record | `SkillQualityReport` + `SkillQualityReceipt` + `SkillPublicationRecord` |
| 已有对象不追溯下线，下一次发布检查 | 已有 skill 保持可用；下一次写入检查 |

主要差异：

- Agent 有 draft/published 状态；skill 没有，写入即发布。门禁是“写入门禁”，不是“发布状态机”。
- Agent 检查配置/提示词/描述；skill 还要检查 **package 身份、渐进式披露链接、SDK 权限声明、eval 数据集**。
- Agent 的 `allowed-tools` 不存在；skill 的 `allowed-tools` 是核心契约。
- Agent 的 eval 不在本文范围；skill 的 eval 是用户明确要求，必须独立目录/文件。

## 2. 行业依据与采用方式

| 参考 | 采用的原则 | 不直接照搬的部分 | 初衷与理由 |
|---|---|---|---|
| Agent Skills 渐进式披露 [S1][S2] | 三层加载：name/description → SKILL.md 正文 → 按需 resource；SKILL.md 保持精简；reference 从入口明确链接 | 不要求固定 `scripts/` / `assets/` 目录；只强制 `eval/` 独立 | skill 的 context 成本由加载层级决定；门禁必须能跟随链接检查内容，而不是只看一个文件 |
| Anthropic Skill authoring best practices [S3] | description 同时说明“做什么”和“何时用”；正文保持 <500 行；reference 一层可达 | 不把 500 行作为硬阻断（可配置 WARN） | 500 行是体验建议，不是身份/契约；硬阻断会造成无意义改写 |
| OpenAI “Testing Agent Skills Systematically with Evals” [S4] | prompt → 捕获 trace + artifacts → 确定性 checks → rubric；小规模 10–20 条 prompt；包含 negative trigger | 不在发布门禁中执行 eval；不要求发布时跑模型 | eval 是行为验证，发布门禁是内容契约；把 eval 放进发布会让成本、随机性和副作用不可控 |
| Kubernetes ValidatingAdmissionPolicy / OPA Gatekeeper [S5][S6] | 服务端 admission；规则/参数/绑定分离；Deny / Warn / Audit；dry-run | 不引入 OPA/Rego 服务，不引入 CRD | 复用成熟的发布门禁治理模式，同时保持首版无新运维依赖 |
| RFC 9457 [S7] | 可机读问题详情、多项问题、JSON Pointer、修复导向 | 不全局替换现有 `{errorCode,message}` envelope | 兼容旧客户端；新增 typed `quality_report`，而不是把 JSON 塞进 message |
| OWASP LLM Prompt Injection / Top 10 [S8] | 待检内容是 untrusted；judge 无工具；输出验证；最小数据；对抗样本 | 不宣称“加一句忽略指令”即可防注入 | skill 文档本身是给 Agent 的指令，必须按不可信输入处理 |
| SemVer 2.0.0 [S9] | version 的 MAJOR.MINOR.PATCH 语义与比较 | 允许 2 段版本（如 `2.3`）作为兼容；repo commit 作为特殊版本源 | 版本必须可比较；过度严格会阻断现有 skill |
| CommonMark [S10] | 用 AST 解析 heading / link / code fence | 不手写正则解析 Markdown | 代码围栏、嵌套链接、HTML 注释都会让正则误判；误判会导致假 PASS 或假 FAIL |
| core-ai-session SDK 契约 [S11] | `s.mcp` / `s.api` / `s.llm_call` / `s.agent` / `s.tool` / `s.dataset` / `s.files` 的调用形状 | 不执行脚本、不导入模块、不解析运行时值 | 权限检查必须基于真实 SDK 形状，而不是猜测字符串 |

资料获取日期：2026-11-16。行业资料用于说明原则；本文的行为契约与数值仍需由本项目的测试与 Review 确认。

## 3. 现状核对与接入约束

本节只依据实际源码核对；现有设计文档不作为输入。下表是当前事实，不代表新增方案已实现。

| 现状 | 源码证据（工作区相对路径:行号） | 对设计的约束 |
|---|---|---|
| SKILL.md frontmatter 只解析 name / description / metadata / allowed-tools / license / compatibility，不解析顶层 version | `core-ai/src/main/java/ai/core/skill/SkillLoader.java:203`、`:222`、`:233`、`:236` | 不能假设 version 已存在；必须定义 metadata.version 的规范位置并兼容旧字段 |
| name 的格式检查只 warn，不拒绝；name 与目录名不一致也只 debug | `core-ai/src/main/java/ai/core/skill/SkillLoader.java:250`、`:255`；`core-ai/src/main/java/ai/core/skill/SkillMetadata.java:13` | 门禁必须把 name 格式与目录一致性升级为规则；不能依赖 loader 现状 |
| 上传路径用 `"upload"` 作为 directoryName，服务端无法校验目录名；name/description 直接来自 frontmatter | `core-ai-server/src/main/java/ai/core/server/skill/SkillService.java:80`、`:95` | 上传必须新增 skill directory 信息（如 `skill_dir` 表单字段），否则只能返回未验证 |
| 上传路径没有质量检查；直接 insert/replace | `core-ai-server/src/main/java/ai/core/server/skill/SkillService.java:77`；`SkillUploadController.java:24`、`:70` | 门禁必须接在 service 层，不能只在 controller 或 CLI |
| 编辑器保存路径只改 description/content/allowedTools/resources，不重新解析 content；metadata/version/name 不更新 | `core-ai-server/src/main/java/ai/core/server/skill/SkillService.java:236`；`SkillWebServiceImpl.java:91` | 存在“content 里的 name/description 与 entity 漂移”的绕过；门禁必须从 post-merge candidate 重新解析并投影字段 |
| `SkillDefinition.version` 字段存在，但 skill 写入链路从未赋值 | `core-ai-server/src/main/java/ai/core/server/domain/SkillDefinition.java:54`；`SkillService.java`、`SkillRepoManager.java` 无 `.version =` 赋值 | 前端编辑的顶层 version 实际不持久化；必须修正为 metadata.version 投影 |
| repo 注册与同步路径直接 replace，没有质量检查 | `core-ai-server/src/main/java/ai/core/server/skill/SkillService.java:119`、`:273`；`SkillRepoManager.java:108` | repo 同步也是发布路径，必须纳入；失败不能覆盖旧内容 |
| Marketplace 安装复用 repo 注册 | `core-ai-server/src/main/java/ai/core/server/skill/MarketplaceService.java:60` | 安装/更新必须逐 skill 执行门禁；失败项不写入，成功项可继续 |
| `allowed-tools` 只被解析、存储、展示；运行时装配 skill 工具时不使用它 | `core-ai/src/main/java/ai/core/skill/SkillMetadata.java:96`；`core-ai-server/src/main/java/ai/core/server/skill/SkillToolAssembler.java:29` | 门禁检查“声明完整性”，不能宣称运行时已强制；这是设计必须写明的边界 |
| `BuiltinTools.ALL` 不包含 `transcribe_audio` / `understand_video` / `submit_artifacts` / `decide` 等运行时可用工具；`GROUPED_SETS` 的 key 带 `builtin-` 前缀 | `core-ai/src/main/java/ai/core/tool/BuiltinTools.java:41`、`:139`；`ToolProvider.java` 的 `BUILTIN_*` 常量 | 权限校验不能只查 `BuiltinTools.ALL`；需要与运行时注册表对齐的 `SkillCapabilityCatalog`，否则会把合法工具误判为未知 |
| `PgDocumentStore` 只提供无条件 replace；PG JSONB 字段用 camelCase（如 `qualifiedName`），Mongo 用 snake_case（`qualified_name`） | `core-ai-server/src/main/java/ai/core/server/pg/PgDocumentStore.java`；`PgSkillStore.java:125`；`MongoSkillStore.java:36` | CAS 需要在 PG 增加条件更新；唯一索引必须区分两种存储的字段名；存量重复数据要先审计 |
| `UpdateSkillRequest.content` 可选；旧调用可能只改 description/allowedTools | `core-ai-api/src/main/java/ai/core/api/server/skill/UpdateSkillRequest.java`；`SkillWebServiceImpl.java:91` | update 必须明确“content 必填或字段只读”；否则 candidate 投影无源可依 |
| `SkillLoader.isIgnoredResource` 会忽略以 `.` 开头的路径；`./references/x.md` 这类前导 `./` 会被误丢 | `core-ai/src/main/java/ai/core/skill/SkillLoader.java:33`；`SkillService.java:45` | CandidateBuilder 必须先规范化路径（剥离 `./`、折叠 `//`）再过滤；被丢弃的 required link 必须报错而不是静默断链 |
| Hub summary 只有 id/qualifiedName/namespace/name/description/sourceType/digest/resourceCount/score/updatedAt；没有 version/tags/required capabilities | `core-ai-api/src/main/java/ai/core/api/server/skillhub/SkillHubSummary.java:14`；`SkillHubDetail.java:40` | 为了让作者和消费者使用声明，catalog/detail 需要补充 version/tags/required_capabilities |
| CLI `skill push` 只发送 `skill_file` 与资源文件，不发送目录名 | `core-ai-cli/src/main/java/ai/core/cli/hub/skill/SkillPushCommand.java:41`；`SkillHubClient.java:98` | 目录一致性需要新增字段；旧客户端只能标记未验证 |
| Skill 存储没有 revision / CAS；`replace` 无条件覆盖 | `core-ai-server/src/main/java/ai/core/server/skill/SkillStore.java:36`；`MongoSkillStore.java:100`；`PgSkillStore.java:125` | 检查后写入需要 revision 条件更新；否则并发写入会丢更新 |
| `qualified_name` 查询存在，但没有唯一索引证据 | `MongoSkillStore.java:36`；`SkillStore.java:16` | 需要唯一索引，防止并发新建同名 skill |
| skill digest 已定义 SKILL.md + 排序后的 resources 的稳定哈希 | `core-ai-server/src/main/java/ai/core/server/skill/SkillDigest.java:22`；CLI 镜像实现 `SkillDigest` | 可作为 candidateHash / receipt 的基础，不另造一套哈希 |
| 资源大小限制已存在：单文件 32MB、inline 文档 12MB、inline 单资源 1MB | `core-ai-server/src/main/java/ai/core/server/skill/SkillResourceLimits.java:9` | 门禁复用限制并增加文件数/总量/ eval fixtures 限制 |
| `SkillLoader.isIgnoredResource` 忽略 dotfile、`__pycache__`、`*.pyc` | `core-ai/src/main/java/ai/core/skill/SkillLoader.java:33` | eval/ 与 references/ 会被扫描为 resources；门禁必须验证 linked/eval 文件确实在 resources 中 |
| 前端 skill 编辑器把顶层 `version` 作为可编辑 key | `core-ai-frontend/src/pages/skills/skillFrontmatter.ts:95`、`:103` | 需要迁移到 metadata.version，或兼容旧字段；否则用户编辑不会进入门禁/存储 |
| 前端上传调用 `POST /api/skills/upload`，只发送文件 | `core-ai-frontend/src/api/client.ts:2548` | Web 上传同样要补 skill directory 信息与质量报告展示 |
| core-ai-session SDK 提供 `s.mcp` / `s.api` / `s.llm_call` / `s.agent` / `s.tool` / `s.dataset` / `s.files` 命名空间 | `sdk/core-ai-session/core_ai_session/tree.py:258`、`:305`；`datasets.py:316` | 权限检测可直接按这些调用形状建模；动态 key 无法静态确定 |
| Agent 质量门禁已有 decision gateway、预算、聚合、policy/revision、receipt 模式 | `core-ai-server/src/main/java/ai/core/server/quality/QualityDecisionGateway.java:45`；`QualityRunAggregator.java:22`；`QualityPolicyService.java:139` | 优先抽取共享 kernel；不要复制两套决策/聚合语义 |

**接入结论：**门禁应位于 `SkillService` 的写入路径之上的新 `SkillPublishService`，而不是 `SkillUploadController` 或 CLI。所有写入入口先构建 candidate，再执行门禁，最后以 revision CAS 写入。当前最大的现有风险不是“规则不够多”，而是 **frontmatter 与 entity 字段可以漂移**、**repo/update 绕过 upload 检查**、**检查后无条件 replace**。设计必须先修这三个结构问题，再谈语义规则。

## 4. 架构与扩展契约

### 4.1 组件边界

```text
CLI push / Web upload / Editor save / Repo import / Repo sync / Marketplace install
                              |
                     SkillPublishService
                              |
                 SkillPublishCandidateBuilder
                 + SkillDocumentParser (CommonMark + SnakeYAML)
                 + ProgressiveDisclosureResolver
                 + SkillPermissionAnalyzer (Python/Shell static lexer)
                 + SkillEvalDatasetValidator
                              |
                    SkillQualityGate
          + SkillQualityPolicy (scope = SKILL)
          + SkillQualityRuleRegistry
          + SkillDecisionEvaluator ---- existing QualityDecisionGateway
                              |
                       GateAggregator
                              |
              Typed Report + SkillQualityReceipt
                              |
                 Revision CAS Write (same candidate)
                              |
              Skills collection + PublicationRecord
```

| 组件（拟新增） | 责任 | 不应承担的责任 | 初衷与理由 |
|---|---|---|---|
| `SkillPublishService` | 统一写入编排：构建 candidate、执行门禁、CAS 写入、写 receipt/publication record | 不解析 Markdown，不直接调用 decision provider | 所有入口共享一条写入契约，避免每个 controller 各写一份检查 |
| `SkillPublishCandidateBuilder` | 从 SKILL.md + resources + 来源信息构建不可变 candidate；解析一次 frontmatter；计算 digest | 不执行脚本、不下载外部资源、不修改原文件 | “检查的内容 = 写入的内容”必须由同一个对象保证 |
| `SkillDocumentParser` | 解析 frontmatter、正文、heading、link、code fence、行号 | 不把 Markdown 当纯文本正则处理 | 代码围栏/嵌套链接/HTML 注释会让字符串匹配误判 |
| `ProgressiveDisclosureResolver` | 从 SKILL.md 解析可达的 reference 图；检查 packaged、深度、环、外链 | 不访问网络、不读取 skill 包以外文件 | 允许内容分布在多个文件，但不能让 Agent 读到不存在的文件 |
| `SkillPermissionAnalyzer` | 静态识别 `core_ai_session` SDK 与 `core-ai-sandbox` / `core-ai-cli` 的 capability 用法 | 不执行代码、不导入模块、不解析动态值 | 权限声明需要事实，而执行不可信脚本违反安全边界 |
| `SkillCapabilityCatalog` | 把声明中的 `tool:<name/group>` 解析为运行时实际 builtin 名称/成员；与 `ToolRegistry`/`BuiltinTools.fromSet` 共享来源 | 不依赖不完整的 `BuiltinTools.ALL`；不把未知名称直接判错 | 权限校验必须与实际装配一致，避免误杀 `transcribe_audio`/`understand_video`/`submit_artifacts`/`decide` |
| `SkillEvalDatasetValidator` | 校验 `eval/eval.json` schema、7 维覆盖、case/assertion、fixtures | 不在发布时运行 eval | 数据集是契约；行为验证是独立 runner 的职责 |
| `SkillQualityRuleRegistry` | 注册 CODE / DECISION / HYBRID evaluator 与参数 schema | 不加载管理员上传的 Java class / script | 可扩展但不能把控制面变成 RCE |
| `SkillDecisionEvaluator` | 选择输入片段、构造受限 questions、按版本化阈值解释结果 | 不生成 rationale、不拥有写入权限 | 复用现有结构化决策能力，保持输出可验证 |
| `SkillQualityGate` | 按策略执行规则、预算、收集结果、聚合 | 不直接写 skills 集合 | 门禁只产生报告；写入由 publish service 控制 |
| `SkillQualityStore` / 共享 Quality Store | 保存 rule/revision/policy/run/receipt/publication record | 不依赖可过期报告作为唯一审计 | 可追溯、可回滚、可耐久审计 |
| `SkillQualityPolicyService` | 版本化规则集、原子激活、回滚 | 不允许普通作者禁用平台规则 | 规则变更影响所有 skill 写入，必须可治理 |

### 4.2 三种规则执行类型

1. **CODE**：已注册 Java checker + 类型化参数。新增算法需要代码发布；调整阈值/选择参数不需要。适用于身份、metadata、权限语法与静态覆盖、eval schema/覆盖、披露链接、包卫生。
2. **DECISION**：管理员通过 UI 配置允许的输入、问题、criteria、阈值、错误模板；无需代码发布。适用于“这段文字是否具体”一类语义判断。
3. **HYBRID**：已注册组合 checker：先用代码建立事实（heading 是否存在、usage 是否可解析、eval 维度是否覆盖），再用决策模型判语义，最后用代码确认缺陷。五个内容要求（SQ5–SQ9）、description 语义、name-content 语义、eval 用例质量使用 HYBRID。

统一接口语义：`evaluate(snapshot, ruleRevision, budget) -> RuleResult`。checker 声明需要的输入，使用明确 DTO；不直接获得无限制数据库、网络或写入权限。

**初衷与理由：**扩展的单位应是“新规则 / 新 checker”，不是给 publish 方法增加一串 if。HYBRID 保留领域逻辑和可测性；任意工作流或脚本会让控制面复杂化，并引入服务器远程代码执行风险。

### 4.3 规则生命周期和生效范围

- 逻辑、参数、模型路由、阈值、提示语、输入选择改变都创建不可变 `SkillQualityRuleRevision`。
- 生命周期：`DRAFT -> TESTED -> SHADOW -> ENFORCING -> RETIRED`。`TESTED` 只是保存测试结果，不等于已证明质量；只有授权管理员才能激活。
- `SkillQualityPolicyRevision` 固定一组 `(ruleId, ruleRevision, enforcement)`；单个 active 指针原子切换；每次写入固定该版本。
- 首版范围为 **server 部署级 skill 规则集**，覆盖该部署所有 skill；不引入 per-namespace 策略。数据结构预留 `scope` key，后续组织级策略只能增加约束。
- 内置规则默认 enforcement 见 4.5；自定义规则默认 `SHADOW`，经测试后可改 `BLOCK` / `WARN`。
- 平台底线规则（name/metadata/eval 结构）不可被普通 skill 作者禁用；管理员调整/停用/回滚有单独审计和确认。

**初衷与理由：**规则本身是写入控制面，改一条阈值可能阻断所有 skill 发布。不可变版本、先 shadow 再阻断、原子切换和有限作用域比“保存后立即全局生效”更可控。

### 4.4 判定状态与聚合

规则状态使用 `PASS / FAIL / INDETERMINATE / ERROR / SKIPPED`，与 `BLOCK / WARN / SHADOW` 执行方式分开。

| 规则结果 | BLOCK | WARN | SHADOW | 用户应看到什么 |
|---|---|---|---|---|
| PASS | 可继续 | 可继续 | 可继续 | 检查通过 |
| FAIL | 阻断 | 提示，不阻断 | 仅审计 | 已检测到不满足规则，给出建议 |
| INDETERMINATE | 阻断 | 提示 | 仅审计 | 无法可靠判断，不断言违规事实 |
| ERROR | 阻断 | 提示 | 仅审计 | checker 或模型不可用，可重试/联系管理员 |
| SKIPPED | 仅明确不适用时可继续 | 可继续 | 可继续 | 说明不适用条件；依赖失败或预算耗尽不能当作不适用 |

总结果：有 BLOCK FAIL 为 `REJECTED`；没有 FAIL 但有 BLOCK ERROR / INDETERMINATE 为 `UNAVAILABLE`；所有适用 BLOCK 通过为 `PASSED`。有质量 FAIL 与系统 ERROR 并存时报告同时列出，不能只显示一个问题。禁止加权总分：一个坏 name 不能被“描述写得好”抵消。

### 4.5 首版规则与默认 enforcement

| Rule ID | 对应需求 | 执行类型 | 首版默认 | 初衷 |
|---|---|---|---|---|
| `SKILL.NAME_IDENTITY` | SQ1 | HYBRID | CODE 部分 BLOCK；语义部分 SHADOW，校准后 BLOCK | 名称/目录/文档主题是同一身份；身份错会导致发现、拉取与更新错位 |
| `SKILL.DESCRIPTION_TRIGGER_SCOPE` | SQ2 | HYBRID | 结构部分 BLOCK；语义部分 SHADOW，校准后 BLOCK | description 是唯一常驻信息，必须同时支持触发与排除 |
| `SKILL.METADATA_REQUIRED` | SQ4 | CODE | BLOCK | version/author/tags 是发现、归属与更新安全的最低元数据 |
| `SKILL.PERMISSION_DECLARATION` | SQ3 | HYBRID | 明确用法未声明 BLOCK；动态用法 INDETERMINATE（首版 SHADOW）；未使用声明 WARN | 声明是消费契约；静态可判定的缺口必须阻断 |
| `SKILL.WORKFLOW` | SQ5 | HYBRID | heading 缺失 BLOCK；语义 SHADOW，校准后 BLOCK | 没有可执行步骤的 skill 只是说明文 |
| `SKILL.DECISION_LOGIC` | SQ6 | HYBRID | 同上 | 分支/fallback 不清会导致同类任务行为漂移 |
| `SKILL.OUTPUT_SPEC` | SQ7 | HYBRID | 同上 | 没有交付标准就无法验收，也无法写 eval |
| `SKILL.ERROR_HANDLING` | SQ8 | HYBRID | 同上 | 失败时静默、乱重试或输出半成品比报错更糟 |
| `SKILL.QUALITY_ASSURANCE` | SQ9 | HYBRID | 同上 | 验收检查是 skill 可回归的前提 |
| `SKILL.EVAL_DATASET` | SQ10 | HYBRID | 目录/文件/schema/7 维覆盖/断言 BLOCK；用例质量 SHADOW | 数据集是行为验证的契约；发布时只验证契约 |
| `SKILL.PROGRESSIVE_DISCLOSURE` | E4 / SQ5–SQ9 | CODE | BLOCK | 链接不可达或未打包会让要求形同虚设 |
| `SKILL.PACKAGE_HYGIENE` | N1 / 安全 | CODE | WARN（路径/符号链接/大小硬限制仍 BLOCK） | 防止包传输与存储被滥用，但不把风格偏好变成阻断 |

**首批不新增用户未要求的规则**：不检查 description 字数风格、不检查固定 heading 数量、不检查作者必须是上传者、不检查 tags 语义质量。这些可作为后续自定义规则。

## 5. 首版规则的精确定义

### 5.1 SQ1 — `SKILL.NAME_IDENTITY`

**类型 / 默认执行：**HYBRID；身份与目录部分 BLOCK；name 与正文主题的语义一致性首版 SHADOW，校准后 BLOCK。

**检查：**

1. `SKILL.md` frontmatter 必须有 `name`，类型为字符串，长度 1–64，匹配 `^[a-z0-9]+(-[a-z0-9]+)*$`。这与现有 `SkillMetadata.isValidName` 一致，但把 loader 的 warning 升级为阻断。
2. **目录一致性**：`directoryName == name`。上传路径必须通过 `skill_dir` 文本字段传入；repo 路径使用包含 SKILL.md 的目录名；编辑器保存使用存储的 `directoryName`（新字段，旧 skill 回填为 `entity.name`）。`skill_dir` 是作者协作契约，不是防伪造的安全边界：恶意客户端可以伪造它，但伪造不会获得额外权限；它用于发现“目录与 frontmatter 不一致”的常见错误。
3. **Registry 投影一致性**：`SkillDefinition.name`、`qualifiedName`（`namespace + "/" + name`）必须从 candidate 的 frontmatter name 派生。
4. **Name 不可变**：`PUT /api/skills/:id` 不允许改名；content 解析出的 name 或 directoryName 与存储不一致时返回 `400 SKILL_NAME_IMMUTABLE`。改名视为“新建 skill + 下线旧 skill”，避免 qualifiedName、Hub 引用、CLI 安装路径和 receipt 全部断裂。
5. **语义一致性**：将 name、directoryName、H1、description、正文前 2000 字符送给决策模型，问“这份文档描述的能力是否与 name 所指的能力一致，而不是复制了另一份 skill 的内容？” 正向 `noul`；`p >= 0.80` 为一致，`p <= 0.20` 为不一致，中间为 INDETERMINATE。
6. 如果没有 H1，不单独阻断（H1 不是用户要求），语义检查使用 description + 首段；如果 H1 与 name 明显冲突，语义检查应命中。
7. 不要求 name 的每个 token 出现在正文中；这会把隐喻式名称误杀。语义判断必须有 evidence（H1/首段）且标记 `SEMANTIC_ASSESSMENT`。

**通过示例：**目录 `markmap-mindmap`，frontmatter `name: markmap-mindmap`，H1 “Markmap Mindmap Skill”，正文描述 markmap 生成。

**失败示例：**

- `SKILL.NAME_IDENTITY@1：SKILL.md name "poster-replica" 与目录名 "poster-tool" 不一致。建议将目录重命名为 poster-replica，或修改 frontmatter name。`
- `SKILL.NAME_IDENTITY@1：文档标题与首段描述的是音频转写，但 skill name 为 poster-replica。请确认是否复制了错误的 SKILL.md。`

**理由：**Hub 引用是 `{namespace}/{name}`，CLI pull 落到 `~/.core-ai/skills/{namespace}/{name}`，更新按 qualifiedName 查找。名称与文档内容不一致会让“发现的是 A、加载的是 B”，也会让后续版本更新落到错误对象。语义检查只判断“是否同一能力”，不评价名称风格。

### 5.2 SQ2 — `SKILL.DESCRIPTION_TRIGGER_SCOPE`

**类型 / 默认执行：**HYBRID；缺失/结构错误 BLOCK；语义具体性首版 SHADOW，校准后 BLOCK。

**检查：**

1. `description` 必须存在、非空、去首尾空白后长度 1–4096；超过 1024 字符给 WARN `DESCRIPTION_TOO_LONG`（不阻断，因为已有 skill 的描述可能较长）。
2. **必须包含触发子句**：大小写不敏感匹配 `\buse\s+when\b`。这是用户明确要求的字面契约；本地化等价表达（如“使用时机”）是否允许作为参数，见 Q2。
3. **必须包含 scope 标记**：匹配 `(?i)\b(scope|in[-\s]?scope|out[-\s]?of[-\s]?scope|not\s+for|do\s+not\s+use|only\s+for)\b`，或中文 `适用范围|不适用|仅用于|不用于`。仅出现 “scope” 但不构成边界时，由语义检查判定为 FAIL/INDETERMINATE。
4. **语义具体性**：两个 `noul` 问题：
   - `trigger_concrete`：Use when 子句是否至少点名一个具体用户请求/输入类型（不是 “use when needed”）？
   - `scope_boundary`：是否说明了不覆盖的任务、输入或限制（不是只重复功能）？
   正向高值 = 满足；阈值 0.80 / 0.20；中间为 INDETERMINATE。
5. description 不应把完整 workflow 塞进来；如果超过 1024 字符且正文已有 workflow，给 WARN 建议把细节移到正文/reference。此项不阻断。
6. description 必须与 `SkillDefinition.description` 投影一致；update 请求的 description 与解析值不一致时返回 `SKILL_METADATA_MISMATCH`（见 8.2）。

**通过示例：**

```yaml
description: Generate interactive Markmap mindmap HTML from Markdown, a file, a URL, or a topic. Use when the user asks to create, visualize, or convert notes into a mindmap. Scope: Markdown/text inputs and public pages; not for slide decks, flowcharts, or binary document formats.
```

**失败示例：**

- `SKILL.DESCRIPTION_TRIGGER_SCOPE@1：description 缺少 Use when 触发条件。建议改为 “Use when the user asks to …”。`
- `SKILL.DESCRIPTION_TRIGGER_SCOPE@1：description 只有功能宣传 “A powerful poster tool”，没有说明何时使用和不适用边界。`
- `SKILL.DESCRIPTION_TRIGGER_SCOPE@1：scope 只写了 “Scope: all tasks”，不是有效边界；请说明不处理的输入/任务类型。`

**理由：**description 是常驻上下文的唯一发现信号；没有 Use when 会漏触发，没有 scope 会误触发。把两者拆成“字面存在”与“语义具体”两层，既能确定性阻断明显缺失，又不在首版用模型误杀简洁但有效的描述。

### 5.3 SQ4 — `SKILL.METADATA_REQUIRED`

**类型 / 默认执行：**CODE；BLOCK。

**规范位置：**frontmatter 的 `metadata` map：

```yaml
metadata:
  version: "1.2.0"
  author: "core-ai-team"
  tags: "poster, image, marketing"
```

- `version`：字符串，匹配 `^\d+(\.\d+){1,2}([-+][0-9A-Za-z.-]+)?$`；允许 `2.3` / `1.2.3` / `1.2.3-beta.1`；长度 ≤ 32。
- `author`：非空字符串，去空白后 1–128 字符；不要求等于上传者（作者可以是团队/组织）；不得包含换行或控制字符。
- `tags`：字符串（逗号分隔）或 YAML 序列；规范化后 1–10 个 tag，每个匹配 `^[a-z0-9]+(-[a-z0-9]+)*$`，长度 1–32，去重后不得为空。建议 ≤ 8 个。现有 `SkillLoader.parseMetadata` 对 YAML 序列使用 `String.valueOf`，会得到 `"[a, b]"`；必须改为对 `tags` 序列 `join(",")`，否则门禁与存储看到的是伪字符串。
- `metadata` map 最多 32 个 key；每个 value 长度 ≤ 1024；`tags` 序列按逗号连接存储为 `String`，符合现有 `Map<String,String>` 约束。

**检查：**

1. `metadata` 必须存在且为 mapping；缺失任一必填 key 分别产生 issue。
2. 顶层 `version` 仅作为旧格式兼容：若 `metadata.version` 缺失而顶层 `version` 存在，接受并给 WARN `LEGACY_VERSION_FIELD`，写入时投影到 `entity.version`；若两者都存在且不同，FAIL `VERSION_CONFLICT`。这要求 `SkillLoader` 额外读取顶层 `version`（只用于兼容，不改写原文）并归一化到 candidate metadata。
3. `entity.version` 必须等于规范化后的 `metadata.version`；`SkillDefinitionView.version` 与 Hub summary 展示该值。
4. **版本递增**：计算 `contentDigest`（SKILL.md 正文 + resources，不含 frontmatter）与 `packageDigest`（完整包）。如果已有 skill 且 `contentDigest` 改变：
   - upload / editor save：`metadata.version` 必须与上一版本不同，且不得低于上一版本；否则 FAIL `VERSION_NOT_BUMPED` / `VERSION_REGRESSION`。
   - repo sync / marketplace install：给 WARN，因为版本源可能是 repo commit；记录实际 commit。
   - 仅 metadata（version/author/tags）变化、正文与 resources 不变：不要求 bump。
5. 版本比较使用数字段比较（`2.10 > 2.9`），不按字符串排序；预发布后缀按 SemVer 规则比较，无法解析时 FAIL `INVALID_VERSION`。

**通过示例：**`metadata.version: "2.3"`、`author: core-ai`、`tags: "poster, image, marketing"`。

**失败示例：**

- `SKILL.METADATA_REQUIRED@1：metadata.version 缺失。建议添加 version: "1.0.0"。`
- `SKILL.METADATA_REQUIRED@1：metadata.tags 为空或包含重复/非法字符；tags 必须是逗号分隔的小写 kebab-case。`
- `SKILL.METADATA_REQUIRED@1：正文已变化，但 version 仍为 2.3。请递增 version（如 2.3.1）后重新发布。`

**理由：**没有 version 就无法判断“同一 skill 的哪个版本被检查/被拉取”；没有 author 就无法归属；没有 tags 就无法发现和治理。版本递增只在语义内容变化时要求，避免仅改标签也被迫发版。

### 5.4 SQ3 — `SKILL.PERMISSION_DECLARATION`

**类型 / 默认执行：**HYBRID（静态分析 + 声明校验，不需要决策模型）；明确用法未声明 BLOCK；动态/不可分析用法 INDETERMINATE（首版 SHADOW）；未使用声明 WARN。

#### 5.4.1 规范字段与语法

用户说的“在 tools 中声明”，在本项目现有格式中对应 frontmatter 的 `allowed-tools`。首版不新增第二个字段；`allowed-tools` 同时承担工具白名单与能力声明。若 Review 决定新增 `tools`，两者必须二选一，不能并存（Q3）。

语法（YAML 序列或空格分隔字符串）：

```yaml
allowed-tools:
  - tool:read_file
  - tool:web_fetch
  - tool:submit_artifacts
  - mcp:github
  - mcp:github/search_issues
  - api-tool:crm/leads/create
  - subagent:research
  - llm-call:summarizer
  - dataset:menu-state
  - media:image
  - media:video
  - decision
```

| 命名空间 | 覆盖范围 | 示例 |
|---|---|---|
| `tool:<name>` | 单个 builtin tool | `tool:read_file`、`tool:web_fetch`、`tool:run_python_script`、`tool:submit_artifacts` |
| `tool:<group>` | builtin group；group 别名归一化到 `ToolProvider.BUILTIN_*` 常量 | `tool:files` → `builtin-files`；`tool:web` → `builtin-web`；`tool:code-execution` → `builtin-code-execution` |
| `mcp:<server>` | 该 MCP server 的任意 tool | `mcp:github` |
| `mcp:<server>/<tool>` | 精确 MCP tool | `mcp:github/search_issues` |
| `api-tool:<app>` | app 下任意 operation | `api-tool:crm` |
| `api-tool:<app>/<service>` | service 下任意 operation | `api-tool:crm/leads` |
| `api-tool:<app>/<service>/<operation>` | 精确 operation | `api-tool:crm/leads/create` |
| `subagent:<name-or-id>` | 一个 subagent | `subagent:research` |
| `llm-call:<name-or-id>` | 一个 LLM_CALL 定义 | `llm-call:summarizer` |
| `dataset:<name-or-id>` | 一个 dataset（扩展项；首版默认 WARN，见 Q3） | `dataset:menu-state` |
| `media:image` / `media:video` / `media:audio` | 媒体生成/理解能力组 | `media:image` 覆盖 `generate_image`；`media:video` 覆盖 `generate_video` + `get_video_status` |
| `decision` | `decide` builtin tool | `decision` |
| `*` 后缀 | 该命名空间全部（显式宽权限） | `mcp:*`、`tool:*` |

**Group 别名归一化表（必须与 `ToolProvider.BUILTIN_*` 常量一致）：**

| 声明别名 | 运行时 group ID |
|---|---|
| `tool:all`（仅建议只读场景，默认 WARN） | `builtin-all` |
| `tool:planning` | `builtin-planning` |
| `tool:files` | `builtin-files` |
| `tool:file-operations` | `builtin-file-operations` |
| `tool:file-read-only` | `builtin-file-read-only` |
| `tool:multimodal` | `builtin-multimodal` |
| `tool:web` | `builtin-web` |
| `tool:bash` | `builtin-bash` |
| `tool:code-execution` | `builtin-code-execution` |
| `tool:github` | `builtin-github` |
| `tool:media-generation` | `builtin-media-generation` |
| `tool:decision` | `builtin-decision` |
| `tool:events` | `builtin-events` |

`tool:<group>` 的成员必须由 **`SkillCapabilityCatalog`** 解析；该 catalog 与运行时 `ToolRegistry` / `BuiltinTools.fromSet` 共享同一注册来源，并加 parity test。不能只查 `BuiltinTools.ALL`：它不包含 `transcribe_audio`、`understand_video`、`submit_artifacts`、`decide` 等运行时工具。catalog 必须由**纯静态 descriptor 表**（kind、名称、group 成员）构建，不依赖运行期 provider 实例：`BuiltinTools.all/fromSet` 需要 `MediaProvider`、`GitHubTokenProvider`、`UnderstandVideoService`，在门禁进程里不可用时会退化为空集，不能作为 catalog 的来源。若 catalog 不可用或与运行时 registry 不一致，未知 builtin 返回 `INDETERMINATE`，不能误报 `UNKNOWN_BUILTIN_TOOL`。

**兼容别名**（仅用于读取旧 skill；规范化后参与覆盖检查，并给 WARN `LEGACY_TOOL_ALIAS`）：

| 旧值 | 规范化 |
|---|---|
| `Bash` | `tool:code-execution` |
| `Read` | `tool:read_file` |
| `Write` | `tool:write_file` |
| `Edit` | `tool:edit_file` |
| `Glob` | `tool:glob_file` |
| `Grep` | `tool:grep_file` |
| `WebFetch` | `tool:web_fetch` |
| `WebSearch` | `tool:web_search` |
| `Task` / `Agent` | 不自动映射；提示作者改用 `subagent:<name>` |

#### 5.4.2 静态检测

分析对象：

- 所有 `.py` / `.sh` / `.bash` / `.ps1` resources；
- SKILL.md 与 reference 中 info string 为 `python` / `py` / `bash` / `sh` / `shell` / `powershell` / `ps1` 的 code fence；
- **所有可执行语言的 code fence 都扫描**；`no-run` / `example` 标记只影响 eval runner 是否执行，不影响权限扫描。示例代码若调用能力也应声明；如果只是说明性伪代码，请使用 `text` info string。
- `text` / `json` / `yaml` / `markdown` 等非执行语言不扫描。

Python 检测形状（支持别名、`async_session()` 与模块级字面量常量）。SDK 的 namespace 同时支持属性访问和下标访问，`-` 与 `_` 可互换（`_normalize_key`），因此分析器必须把**属性链、下标链、混合链与中间节点变量别名**归一到同一个调用路径，不能只识别下标写法：

| 代码形状 | 需要的声明 |
|---|---|
| `s.mcp["server"]["tool"](...)` / `s.mcp.server.tool(...)` | `mcp:server` 或 `mcp:server/tool` |
| `gh = s.mcp.github` 后 `gh.search_issues(...)` | `mcp:github` 或 `mcp:github/search_issues` |
| `s.api["app"]["service"]["operation"](...)` / `s.api.app.service.operation(...)` | `api-tool:app` / `api-tool:app/service` / 精确 operation |
| `s.agent["name"].run(...)` 或 `s.agent["name"](...)` | `subagent:name` |
| `s.llm_call["name"](...)` / `s.llm_call.name(...)` | `llm-call:name` |
| `s.tool("name")(...)` | 先查 `SkillCapabilityCatalog`：已知 builtin/media/decision 按下方映射；未知名称返回 `INDETERMINATE` `TOOL_NAME_NOT_RESOLVABLE`，建议改用 `s.mcp` / `s.api` / `s.agent` 或显式声明 |
| `s.builtin["name"](...)`（SDK 已暴露 `builtin` 命名空间） | `tool:name` |
| `s.dataset["name"].state()/records()/...` | `dataset:name`（扩展项，首版 WARN） |
| `s.files.publish(...)` | `tool:submit_artifacts` |
| `s.files.<other>(...)` | 首版 WARN `UNKNOWN_FILES_OPERATION`，按实际 `submit_artifacts`/builtin 映射补齐 |

媒体与 decision 映射：

| 调用 | 接受声明 |
|---|---|
| `s.tool("generate_image")` | `media:image` 或 `tool:generate_image` |
| `s.tool("generate_video")` / `get_video_status` | `media:video` 或对应 `tool:` |
| `s.tool("transcribe_audio")` / `caption_image` / `understand_video` | `media:audio` / `media:image` / `media:video` 或对应 `tool:` |
| `s.tool("decide")` | `decision`（或 `tool:decide`） |

Shell / CLI 检测形状：

| 命令 | 需要的声明 |
|---|---|
| `core-ai-sandbox call <name> ...` | 按 `<name>` 的 kind 映射（tool/mcp/api/llm_call/agent） |
| `core-ai-cli mcp call <server>/<tool> ...` | `mcp:server` 或精确 tool |
| `core-ai-cli api-tool call <app>/<service>/<operation> ...` | `api-tool:...` |
| `core-ai-cli agent run <agent> ...` | `subagent:<agent>` |
| `core-ai-sandbox catalog` / `tools` / `describe` | 只读发现，不需要调用权限 |

**静态边界：**

- 只做词法/受限语法分析，不导入模块、不执行表达式、不读取环境变量、不解析函数返回值。
- 支持模块级字面量常量、简单字符串拼接、`session()` 别名（`s = session()`、`sess = session()`）、namespace 中间节点别名（`gh = s.mcp.github`）、属性链与下标链的混合写法；key 比较按 SDK 的 `-`/`_` 与大小写归一化规则执行。
- `s.tool("name")` 不是 builtin 专用入口：SDK 按 catalog 的任意 kind 查找。分析器只在 catalog 能把名称解析为 builtin/media/decision 时要求对应 `tool:` / `media:` / `decision` 声明；解析不到时返回 `INDETERMINATE`，不得按 `tool:name` 误杀。
- 动态 key（`s.mcp[server_name]`、`s.tool(tool_name)`）返回 `DYNAMIC_USAGE`：
  - 若存在 `mcp:*` / `tool:*` 等宽声明，PASS + WARN `BROAD_PERMISSION`；
  - 否则 INDETERMINATE，建议改为字面量或显式宽声明；
  - 首版 SHADOW，不阻断；校准后可 BLOCK。
- 如果脚本 import 了本地 helper，helper 也在 resources 中则一起扫描；helper 不在包内则给 WARN `EXTERNAL_HELPER_UNANALYZED`，不假装已覆盖。
- 不在包内的动态代码（网络下载、`eval`、`exec`、`subprocess` 拼接）无法证明安全，给 INDETERMINATE；不执行、不猜测。

#### 5.4.3 校验规则

1. `allowed-tools` 中每个 entry 必须非空、无重复、匹配语法；未知命名空间 FAIL `UNKNOWN_PERMISSION_NAMESPACE`。未知 builtin tool/group：若 `SkillCapabilityCatalog` 可用且通过 parity test，FAIL `UNKNOWN_BUILTIN_TOOL`；若 catalog 不可用/不完整，INDETERMINATE `BUILTIN_REGISTRY_UNAVAILABLE`，不误杀 `transcribe_audio` / `understand_video` / `submit_artifacts` / `decide`。
2. **覆盖**：每个静态检测到的 usage 必须被至少一个声明覆盖；精确声明覆盖自身，前缀声明覆盖子路径，`namespace:*` 覆盖该命名空间全部。明确用法未覆盖 FAIL `PERMISSION_NOT_DECLARED`。
3. **未使用声明**：声明存在但未检测到对应 usage，WARN `UNUSED_PERMISSION`；不阻断，因为动态代码或未来分支可能使用。
4. **外部资源存在性**：MCP server/tool、api-tool operation、subagent、LLM_CALL、dataset 的名称对当前读者不可见时，WARN `EXTERNAL_CAPABILITY_NOT_VISIBLE`，不阻断（skill 可移植，且发布者可能无权枚举全部资源）；不发起网络连接。
5. **dataset 扩展**：用户需求未点名 dataset；`s.dataset` 用法有声明时 PASS，无声明时首版 WARN `DATASET_PERMISSION_UNDECLARED`，不 BLOCK。管理员可在 policy 中升级为 BLOCK（Q3）。
6. **运行时说明**：声明不等于授权；报告和 Hub detail 必须明确 “Required capabilities”，不得写 “已授权” 或 “已启用”。
7. **旧客户端/旧 skill**：没有 `allowed-tools` 且没有检测到 usage → PASS；有 usage 但没有声明 → FAIL（即使旧 skill，只要本次写入）。
8. `allowed-tools` 最多 64 项；`tool:*` / `mcp:*` 等宽声明单独标记 WARN，建议收窄。

**通过示例：**

```python
from core_ai_session import session
s = session()
r = s.mcp["github"]["search_issues"](query="open bugs")
img = s.tool("generate_image")(prompt="a poster")
```

```yaml
allowed-tools:
  - mcp:github
  - media:image
```

**失败示例：**

- `SKILL.PERMISSION_DECLARATION@1：references/fetch.py:12 调用 s.mcp["github"]["search_issues"]，但 allowed-tools 没有 mcp:github 或 mcp:github/search_issues。建议声明所需 server（或精确 tool），并在 Agent 中挂载该 MCP。`
- `SKILL.PERMISSION_DECLARATION@1：references/make.py:8 调用 s.tool("generate_image")，但只声明了 Bash。Bash 不能覆盖图片生成；请声明 media:image。`
- `SKILL.PERMISSION_DECLARATION@1：references/dynamic.py:5 使用动态 server 变量，无法确认覆盖范围；请改为字面量或声明 mcp:*。`

**理由：**skill 脚本在 sandbox 中只能使用 Agent 已挂载的能力；声明缺失会导致运行时 `ToolNotFoundError` 或 403，声明错误会让作者以为“写了就会用”。门禁用静态事实检查声明完整性，同时明确它不是运行时权限系统，避免制造虚假安全感。

### 5.5 SQ5 — `SKILL.WORKFLOW`

**类型 / 默认执行：**HYBRID；无可识别 section BLOCK；语义质量首版 SHADOW，校准后 BLOCK。

**检查：**

1. 在 SKILL.md 或可达的 packaged reference 中，存在 heading（或链接文本）匹配 workflow 同义词：`workflow`、`process`、`pipeline`、`steps`、`how it works`、`工作流`、`流程`、`步骤`。
2. section 非空，至少 2 个可识别步骤（有序列表、编号、或 “Step 1/2”）；只有一个 “follow the workflow” 句子不算。
3. 语义问题：
   - `workflow_ordered`：步骤是否给出顺序（先做什么、再做什么）？
   - `workflow_actionable`：每一步是否包含可执行动作和输入/输出，而不是抽象描述？
   正向阈值 0.80 / 0.20。
4. 如果 workflow 只存在于一个未从 SKILL.md 链接的文件，按 5.11 的 `UNREACHABLE_REFERENCE` 处理，不能算满足。
5. 线性 skill 允许没有分支；但必须明确“按顺序执行，遇到 X 时停止/询问用户”。

**通过示例：**

```markdown
## Workflow
1. Read the user's input (file path, URL, pasted text, or topic).
2. Build the markmap markdown with one H1 and 4–7 H2 branches.
3. Write `<slug>.mindmap.md`; never overwrite an existing file.
4. Run `npx --yes markmap-cli <input> -o <output> --no-open`.
5. Report both paths; if npx is unavailable, keep the .md and explain the fallback.
```

**失败示例：**

- `SKILL.WORKFLOW@1：未找到 Workflow/流程 section。建议增加 “## Workflow”，按 输入 → 处理 → 输出 → 报告 列出步骤，或链接 references/workflow.md。`
- `SKILL.WORKFLOW@1：Workflow 只有 “follow the standard process”，没有可执行步骤。`
- `SKILL.WORKFLOW@1：Workflow 位于 references/flow.md，但 SKILL.md 没有链接它，且文件未随包发布。`

**理由：**Agent 加载 skill 后需要的是可执行程序，不是能力宣言。步骤顺序和输入/输出是后续 eval 的断言基础；没有 workflow，Output/Error/QA 也无法落地。

### 5.6 SQ6 — `SKILL.DECISION_LOGIC`

**类型 / 默认执行：**HYBRID；无 section BLOCK；语义质量首版 SHADOW，校准后 BLOCK。

**检查：**

1. section heading 匹配：`decision making`、`decision logic`、`decisions`、`when to use which`、`routing`、`choose`、`branch`、`决策`、`判断`、`分支`、`如何选择`。
2. 内容必须至少给出一种可观察的决策规则：
   - 条件 → 动作（表格/列表/if 语句式描述）；或
   - 明确“没有分支，默认走主流程；遇到 A/B 时询问用户/停止”；或
   - fallback / tie-breaker（输入缺失、多个模板都匹配、工具不可用）。
3. 语义问题：
   - `decision_conditions`：是否说明在什么条件下选择不同路径或停止？
   - `decision_fallback`：是否说明不确定、冲突或工具失败时的 fallback/询问策略？
4. 不接受只有 “use your judgment” / “choose the best option” 这种无法执行的说明。

**通过示例：**

| Condition | Action | Fallback |
|---|---|---|
| User provides a reference poster + dish photo | Base-plate route | If no dish photo, ask for one |
| User provides copy + brand assets only | Constructive HTML/CSS route | If logo is missing, use text wordmark and ask |
| Two templates match equally | Show contact sheet, ask user | Do not auto-pick |

**失败示例：**

- `SKILL.DECISION_LOGIC@1：未找到 Decision making/决策 section。建议增加条件 → 动作 → fallback 表，或明确 “no branching; ask user when input missing”。`
- `SKILL.DECISION_LOGIC@1：只有 “choose the best template”，没有可观察条件。`

**理由：**skill 的价值在于把专家的判断固化成可重复规则；如果决策只存在于作者脑中，不同模型/不同运行会给出不同行为，eval 也无法断言。

### 5.7 SQ7 — `SKILL.OUTPUT_SPEC`

**类型 / 默认执行：**HYBRID；无 section BLOCK；语义质量首版 SHADOW，校准后 BLOCK。

**检查：**

1. section heading 匹配：`output`、`outputs`、`output specification`、`deliverables`、`delivery`、`result`、`交付`、`输出`、`产物`、`交付标准`。
2. 必须说明：
   - 交付物类型（文本/文件/artifact/图片/视频/表格）；
   - 格式/结构/命名/保存位置（至少一项具体）；
   - 成功标准或验收方式（至少一项可观察）。
3. 语义问题：
   - `output_deliverables`：是否点名具体交付物与格式/路径？
   - `output_acceptance`：是否给出可检查的完成标准？
4. 仅写 “return the result” / “save the file” 不算。

**通过示例：**

```markdown
## Output
- `output/<slug>.mindmap.md` — markmap markdown, one H1, 4–7 H2 branches.
- `output/<slug>.mindmap.html` — self-contained HTML from markmap-cli.
- Chat reply: both paths + how to open; if HTML failed, state the fallback and the manual command.
Acceptance: the .md parses with the markmap frontmatter; the HTML opens standalone; no existing file is overwritten.
```

**失败示例：**

- `SKILL.OUTPUT_SPEC@1：未找到 Output/交付 section。建议列出文件路径、格式、命名和成功标准，或链接 references/output.md。`
- `SKILL.OUTPUT_SPEC@1：Output 只写了 “return the result”，无法验收。`

**理由：**没有交付标准，Agent 无法判断何时完成，eval 也无法写 output_quality 断言；用户也无法判断“做完了没有”。

### 5.8 SQ8 — `SKILL.ERROR_HANDLING`

**类型 / 默认执行：**HYBRID；无 section BLOCK；语义质量首版 SHADOW，校准后 BLOCK。

**检查：**

1. section heading 匹配：`error handling`、`errors`、`failure`、`failure modes`、`troubleshooting`、`recovery`、`失败处理`、`错误处理`、`异常`、`失败模式`。
2. 必须至少列出 2 个现实失败模式，并给出对应动作：
   - 输入缺失/非法；工具/命令不存在；权限/网络失败；输出部分失败；超时；文件已存在。
   - 动作必须是 `retry` / `fallback` / `stop and ask` / `report and preserve partial output` 之一，且说明用户会看到什么。
3. 语义问题：
   - `error_modes`：是否列出至少两个具体失败模式？
   - `error_actions`：每个模式是否有明确动作，而不是 “handle errors gracefully”？
4. 对脚本类 skill，建议同时说明非零退出码/错误消息；不强制代码级检查。

**通过示例：**

| Failure | Detection | Action |
|---|---|---|
| `npx`/Node missing | command not found | Keep the `.md`, report the manual command, do not claim HTML success |
| URL fetch fails | non-2xx/timeout | Ask whether to map from model knowledge; do not invent page content |
| Output file exists | filesystem check | Insert `-2`, `-3` suffix; never silently overwrite |

**失败示例：**

- `SKILL.ERROR_HANDLING@1：未找到 Error Handling/失败处理 section。建议列出至少两个失败模式与动作，或链接 references/errors.md。`
- `SKILL.ERROR_HANDLING@1：只有 “handle errors gracefully”，没有失败模式与用户可见动作。`

**理由：**skill 运行在真实环境，工具缺失、网络失败、文件冲突是常态；没有失败处理，Agent 容易静默输出半成品或反复重试。

### 5.9 SQ9 — `SKILL.QUALITY_ASSURANCE`

**类型 / 默认执行：**HYBRID；无 section BLOCK；语义质量首版 SHADOW，校准后 BLOCK。

**检查：**

1. section heading 匹配：`quality assurance`、`quality`、`acceptance`、`verification`、`checks`、`validation`、`qc`、`质量保证`、`验收`、`质量`、`检查`。
2. 必须给出至少 2 个可执行验收检查，例如：
   - 命令 + 期望输出/退出码；
   - 文件存在/格式校验；
   - 手动检查项（打开 HTML、检查链接、检查图片尺寸）；
   - eval 运行命令与通过阈值。
3. 语义问题：
   - `qa_checks`：检查是否具体、可执行？
   - `qa_runnable`：是否说明如何运行/观察结果（命令、脚本、eval）？
4. 必须引用 `eval/eval.json` 或说明 eval 的运行方式。只在正文引用文件路径但没有可执行检查时，记 `CODE WARN` `QA_EVAL_REFERENCE_ONLY`：WARN 不进入聚合的 INDETERMINATE，规则升级为 BLOCK 时也只按 WARN 放行，避免同一条件在不同 enforcement 下语义漂移。

**通过示例：**

```markdown
## Quality assurance
- `npx --yes markmap-cli input.md -o out.html --no-open` exits 0 and creates a non-empty HTML.
- `python eval/run.py --skill . --dataset eval/eval.json` reports trigger/scope/tool/parameter/instruction/output/robustness pass rates; publish gate requires all 7 dimensions present.
- Manual: open the HTML in a browser, confirm one H1 and 4–7 branches.
```

**失败示例：**

- `SKILL.QUALITY_ASSURANCE@1：未找到 Quality assurance/验收 section。建议列出可执行检查与期望结果，并引用 eval/eval.json。`
- `SKILL.QUALITY_ASSURANCE@1：只有 “the output looks good”，没有可运行检查。`

**理由：**验收质量说明把“我认为完成了”变成“如何证明完成了”。它与 eval 数据集是上下游关系：QA 说明运行方式，eval 提供用例与断言。

### 5.10 SQ10 — `SKILL.EVAL_DATASET`

**类型 / 默认执行：**HYBRID；目录/文件/schema/7 维覆盖/断言结构 BLOCK；用例质量首版 SHADOW。

#### 5.10.1 目录与文件

必须存在：

```text
<skill>/
  SKILL.md
  eval/
    eval.json              # 必需，数据集 manifest
    fixtures/              # 可选，输入文件/图片/音频/期望输出
    README.md              # 可选，运行/解释说明
```

- 只接受 `eval/eval.json`；`eval.md`、`SKILL.md` 内联用例、`evals/` 等其他目录不满足 SQ10。
- `eval/` 与 `eval/eval.json` 必须作为 resources 随包发布；未打包 = FAIL `EVAL_DATASET_NOT_PACKAGED`。
- 目录名固定 `eval`，避免每个 skill 自创路径导致 runner 无法发现。

#### 5.10.2 Manifest schema（首版）

```json
{
  "schema_version": 1,
  "skill": "markmap-mindmap",
  "dataset_version": "1.0.0",
  "cases": [
    {
      "id": "trigger-001",
      "dimension": "trigger_accuracy",
      "input": {
        "user_request": "Turn these meeting notes into an interactive mindmap.",
        "fixtures": []
      },
      "expected": {
        "triggered": true
      },
      "assertions": [
        {"kind": "skill_invoked", "value": true}
      ],
      "tags": ["positive", "implicit"],
      "notes": "Implicit trigger; user never names the skill."
    }
  ]
}
```

字段约束：

- `schema_version`：整数 1。
- `skill`：必须等于 frontmatter name。
- `dataset_version`：SemVer-like 字符串；用例变化时建议递增（WARN，不阻断）。
- `cases`：1–200 个；`id` 唯一，匹配 `^[a-z0-9][a-z0-9-]{1,63}$`。
- `dimension`：只能是 7 个 canonical 值之一（见 5.10.3）。
- `input`：对象，至少包含 `user_request`（非空字符串）；可选 `fixtures`（相对 `eval/fixtures/` 的路径数组）、`context`、`workspace`。
- `expected`：对象，按维度必须包含指定 key（见 5.10.3）。
- `assertions`：1–20 个结构化断言；至少 1 个。首版支持的 kind：
  `skill_invoked`、`tool_called`、`tool_not_called`、`tool_args`、`output_contains`、`output_not_contains`、`file_exists`、`file_not_exists`、`exit_code`、`rubric`、`no_crash`。
- `tags`：可选字符串数组；`notes`：可选字符串。
- 禁止占位符：`user_request` / `expected` / `assertions` 的**整个值**等于 `TODO`、`TBD`、`...`、`<...>`、`example`、`lorem ipsum`（大小写不敏感），或 `user_request` 匹配 `^<[^>]+>$` / `^\.{3,}$` 时 FAIL `EVAL_CASE_PLACEHOLDER`。不要把 `example` / `...` 作为任意子串拦截：`"give an example"`、`"<repo>"` 这类合法测试输入应通过。占位符的模糊变体交给 `eval_cases_concrete` 语义检查（首版 SHADOW）。
- fixtures 必须存在、位于 `eval/fixtures/` 下、无路径穿越/符号链接；单文件 ≤ 10MB，总 fixtures ≤ 20MB。

#### 5.10.3 7 个测试维度

| 维度（canonical） | 用户要求 | 必须测什么 | `expected` 必需 key | 推荐最少用例 |
|---|---|---|---|---|
| `trigger_accuracy` | 应该触发时是否触发 | 显式点名、隐式描述、带噪声上下文；至少 1 个 negative control（不应触发） | `triggered: boolean` | 3（2 正 + 1 负） |
| `scope_accuracy` | 是否错误处理不属于自己的任务 | 边界内任务应处理；边界外任务应拒绝/转交；不伪造能力 | `in_scope: boolean`、`expected_behavior: handle\|refuse\|handoff` | 2 |
| `tool_accuracy` | 工具是否正确调用 | 该调用时调用正确能力；不该调用时不调用；不拿别名替代 | `tools: string[]` 或 `no_tools: boolean` | 2 |
| `parameter_accuracy` | 参数是否正确 | 关键参数值/类型/目标对象正确；缺参时不猜 | `tool: string`、`arguments: object`、`match: exact\|subset\|schema` | 2 |
| `instruction_following` | 是否遵循业务规则 | 业务约束、顺序、禁止项、确认点 | `rules: string[]` 或 `checks: string[]` | 2 |
| `output_quality` | 输出是否符合预期 | 交付物类型/格式/结构/命名/验收标准 | `outputs: object[]` 或 `rubric: string[]` | 2 |
| `robustness` | 边界条件下是否稳定 | 输入缺失/非法、工具失败、超时、文件冲突、部分失败 | `failure_mode: string`、`expected_behavior: ask_user\|retry\|fallback\|fail_gracefully\|no_partial_output` | 2 |

**覆盖规则：**

- 7 个维度每个至少 1 个 case；缺失任一维度 BLOCK `EVAL_DIMENSION_MISSING`。
- 推荐 10–20 个 case；低于 10 给 WARN `EVAL_DATASET_SMALL`；`trigger_accuracy` / `scope_accuracy` / `robustness` 少于 2 个给 WARN（这三类最容易“只测了 happy path”）。
- `trigger_accuracy` 必须至少 1 个 `triggered=false` 的 negative control；没有则 WARN `EVAL_NO_NEGATIVE_TRIGGER`（建议 BLOCK，见 Q5）。
- 每个 `expected` 不能为空对象；每个 case 至少有 1 个 assertion。
- `expected` 与 `assertions` 必须能被 eval runner 解释；无法识别的 assertion kind FAIL `EVAL_ASSERTION_UNKNOWN`。

#### 5.10.4 用例质量（首版 SHADOW）

- `eval_cases_concrete`：case 的 `user_request` 是否像一个真实用户会说的话，而不是抽象描述？
- `eval_expected_observable`：`expected` / `assertions` 是否能在 trace 或 artifacts 上观察？
- `eval_dimension_fit`：用例是否真的在测它声明的维度（例如工具正确性 case 不应只断言输出文本）？
- 这些由决策模型判断，首版 SHADOW；校准后可 BLOCK。

#### 5.10.5 发布时不执行 eval

发布门禁只验证数据集契约、覆盖与打包；**不运行 skill、不调用模型、不执行 eval runner**。原因：

- 运行 eval 需要 Agent、模型、sandbox、工具与 fixture，可能产生费用和副作用；
- trigger/output 质量有随机性，不能把一次抽样当作发布事实；
- 发布门禁要的是“可回归的契约存在”，行为验证由 `skill eval` / CI 独立执行。

**通过示例：**7 个维度各 1 个 case，trigger 有 1 个 negative，所有 case 有 `expected` + assertion，`eval/eval.json` 已打包，fixtures 存在。

**失败示例：**

- `SKILL.EVAL_DATASET@1：缺少 eval/eval.json。请在 skill 根目录创建 eval/ 目录，并至少覆盖 trigger_accuracy、scope_accuracy、tool_accuracy、parameter_accuracy、instruction_following、output_quality、robustness。`
- `SKILL.EVAL_DATASET@1：eval.json 缺少维度 scope_accuracy；当前只有 6/7。`
- `SKILL.EVAL_DATASET@1：case trigger-002 的 user_request 是 "TODO"，不是可执行测试。`
- `SKILL.EVAL_DATASET@1：eval/ 未包含在本次上传的 resources 中；请确认没有在打包/上传时被忽略。`

**理由：**用户要求“有 Evaluation dataset 目录，测试 7 个维度”。没有独立目录/文件就无法作为可运行契约；只验证“存在”而不验证维度/断言，会得到一堆不能跑的样例。发布时只做静态契约检查，避免把随机性和费用带进发布路径。

### 5.11 E4 — `SKILL.PROGRESSIVE_DISCLOSURE`

**类型 / 默认执行：**CODE；BLOCK（路径/链接），WARN（主文件过大）。

**解析算法：**

1. 用 CommonMark 解析 SKILL.md，收集 **inline link 与 reference-style link**（`[text](path)` 与 `[text][ref]` + `[ref]: path`）的相对路径；忽略纯 anchor（`#...`）与外部 URL。
2. 规范化路径：剥离前导 `./`、折叠 `//`、统一 `/`；拒绝绝对路径、盘符、`..` 逃逸、符号链接逃逸；目标必须位于 skill root 内。规范化必须在 `SkillLoader.isIgnoredResource` 之前执行，避免 `./references/x.md` 被误丢。
3. 从 SKILL.md 出发，深度优先遍历 `.md` 链接，最大深度 2、最多 20 个文件、总文本 ≤ 512KB；检测环（同一 canonical path 只访问一次）。
4. 每个目标必须存在于本次 candidate 的 resources 中；否则 FAIL `BROKEN_LINK` / `REFERENCE_NOT_PACKAGED`。
5. 对每个要求（workflow/decision/output/error/QA），在 SKILL.md + 可达 reference 的 headings 中匹配 5.5–5.9 的同义词。匹配使用**版本化 heading 规范化**后再比较，不做任意子串匹配；减少 `result` / `errors` / `quality` 误命中：
   - 小写化、折叠空白、去除行尾标点；
   - 剥离前导编号与前缀（`1.`、`2)`、`Step 1:`、`§3`、`4.2`）与 Markdown 强调标记；
   - 括号备注只在同义词参数显式开启时剥离（如 `Quality Assurance (QA)`）；默认剥离会导致 `Output (JSON)` 与 `Output (CSV)` 误判为同一要求；
   - `&` / `/` 不自动拆分，按同义词表登记的完整写法匹配（如 `error handling & recovery`）；表未登记时给 `HEADING_NOT_MATCHED`，由作者补 heading 或由管理员扩表。
   规范化函数与同义词表都是 RuleRevision 的一部分，改动生成新 revision。
6. **摘要 + 链接的穿透规则**（修复“SKILL.md 只写摘要”场景）。穿透必须是确定性条件，不能依赖“详见/完整流程”这类自然语言猜测：
   - 触发条件：命中 section 去链接文本后的有效正文少于 300 字符，且该 section 内有且仅有一个指向 packaged `.md` 的链接；
   - 触发后按目标文件提取：
     - 目标文件有同义 heading → 提取该 heading 的 section；
     - 目标文件没有同义 heading 且正文 ≤8k 字符 → 取目标文件正文；
     - 目标文件没有同义 heading 且正文 >8k 字符 → `SECTION_TOO_LARGE`，issue 定位到目标文件路径与实际字符数，建议添加对应子标题或拆分文件；不得只报通用的“输入过大”；
   - 主 section 的摘要保留为 context，不因摘要只有 5 行而 FAIL；
   - 不满足触发条件（正文足够长或存在多个链接）时，只检查主 section，不穿透；避免把无关 reference 全文当成要求正文。
   - 如果 SKILL.md 没有命中 heading，但存在链接文本等于要求名（如 `[Workflow](references/workflow.md)`），同样按目标文件提取。
   - 合并结果记录 `primary_source` 与 `summary_source`；issue 定位到目标文件。
7. 外部 URL 可以作为补充资料，但不能满足 E4 的要求；如果要求只指向外链，FAIL `REMOTE_SECTION_NOT_ALLOWED`（发布时不抓取 URL，避免 SSRF）。
8. 存在但未被 SKILL.md 可达链接引用的文件，不参与要求匹配；单独给 WARN `UNREFERENCED_REFERENCE`（不阻断，因为可能是图片/模板）。
9. SKILL.md 正文超过 500 行或 32KB 给 WARN `MAIN_FILE_TOO_LARGE`，建议拆分到 references；不阻断。

**Section 同义词：**

| 要求 | 英文 heading | 中文 heading |
|---|---|---|
| Workflow | workflow, process, pipeline, steps, how it works | 工作流, 流程, 步骤 |
| Decision logic | decision making, decision logic, routing, choose, branch | 决策, 判断, 分支, 如何选择 |
| Output spec | output, outputs, output specification, deliverables, delivery, result | 输出, 交付, 交付标准, 产物 |
| Error handling | error handling, errors, failure modes, troubleshooting, recovery | 错误处理, 失败处理, 异常, 失败模式 |
| Quality assurance | quality assurance, quality, acceptance, verification, checks, validation, qc | 质量保证, 验收, 质量, 检查 |

**通过示例：**SKILL.md 的 workflow section 只有 5 行摘要，链接 `[Full workflow](references/workflow.md)`；`references/workflow.md` 在 resources 中，内容有 7 个步骤。

**失败示例：**

- `SKILL.PROGRESSIVE_DISCLOSURE@1：SKILL.md 链接 references/workflow.md，但本次上传的 resources 中没有该文件。请把文件加入 skill 目录后重新发布。`
- `SKILL.PROGRESSIVE_DISCLOSURE@1：Output 要求只链接到 https://example.com/output，发布时无法保证 Agent 可访问；请改为 packaged reference。`
- `SKILL.PROGRESSIVE_DISCLOSURE@1：references/ 中的 error-handling.md 没有被 SKILL.md 链接，不能作为 Error Handling 要求。`

**理由：**Agent Skills 的核心是渐进式披露：入口只放路由，细节按需加载。允许内容分布在 reference 是用户明确要求；但“分布”不能变成“丢失”——链接必须可达、目标必须随包发布、发布时不依赖网络。

### 5.12 包卫生 — `SKILL.PACKAGE_HYGIENE`

**类型 / 默认执行：**CODE；路径/符号链接/大小硬限制 BLOCK，其余 WARN。

- 拒绝符号链接、`..`、绝对路径、重复 resource path；所有 resource 必须位于 skill root 内。
- 忽略/拒绝 dotfile、`__pycache__`、`*.pyc`、`.git`；`node_modules` 给 WARN（不应作为 skill 资源）。
- 文件数 ≤ 500；单文件 ≤ 32MB（复用 `SkillResourceLimits`）；总包 ≤ 64MB；eval fixtures ≤ 20MB。
- 不允许资源路径以 `SKILL.md` 以外的可执行文件覆盖入口；`SKILL.md` 只能有一个（大小写敏感）。
- 不执行秘密扫描；不在发布日志/报告中输出文件内容。若未来加入 secret scanner，作为独立 WARN 规则，避免把二进制/fixture 误报当阻断。
- 二进制资源允许（图片/音频/视频），但报告只记录路径/大小/摘要，不送模型。

**理由：**skill 包会被解压到 sandbox，路径逃逸/符号链接/超大文件是供应链与存储风险。门禁应保证包是“可安全传输的资源集合”，而不是运行时的安全沙箱。

## 6. 快照、静态分析与决策模型

### 6.1 `SkillPublishCandidate`

```text
SkillPublishCandidate
- source: UPLOAD | EDITOR | REPO | MARKETPLACE | TRUSTED_SEED
- sourceRef: repo URL/branch/commit/path（可空）
- namespace, directoryName
- existingSkillId, expectedRevision（更新时）
- content: SKILL.md 原文
- resources: Map<path, bytes>（排序、不可变）
- parsed: name, description, metadata, allowedTools, version
- document: headings, sections, links, code blocks, line index
- disclosure: reachable files, resolved sections, broken links
- permissionUsage: DetectedUsage[]
- permissions: NormalizedPermissions
- evalDataset: manifest, dimensionCounts, caseCount, datasetDigest, validation
- packageDigest = SkillDigest.of(content, resources)
- contentDigest = hash(body + resources)  // 用于 version bump
- metadataHash = hash(name, description, metadata, normalized permissions)
- revision = existing revision + 1（新建为 1）
```

Candidate 是唯一输入：所有规则读取它，commit 写入它；禁止 commit 时重新读取文件或重新解析 prompt/frontmatter。

### 6.2 Document parser

- frontmatter 用 SnakeYAML SafeConstructor（现有 `SkillLoader` 已使用），限制 code point 与 map 大小。
- Markdown 用 CommonMark AST（建议新增 `org.commonmark:commonmark` 依赖）；如果 Review 拒绝新增依赖，退化为经过测试的受限解析器，必须覆盖 fenced code、inline link、heading、HTML comment 的测试向量，不能只用 `indexOf`。
- 解析结果保留：
  - heading 层级、文本、起始行；
  - section 起止；
  - inline link 的 text/target/行号；
  - fenced code 的 info string、内容、行号；`no-run`/`example` 标记；
  - frontmatter 原始文本与每个 key 的行号（用于 issue 定位）。
- 解析失败（无 frontmatter、YAML 非法、非 UTF-8）直接 FAIL `SKILL_DOCUMENT_INVALID`，不进入语义检查。

### 6.3 Permission analyzer

- Python：词法扫描 + 受限表达式解析；只识别 SDK 调用链、赋值别名、模块级字面量常量；不导入、不执行。
- Shell：识别 `core-ai-sandbox` / `core-ai-cli` 的 `call` 子命令与参数；变量参数返回 dynamic。
- Markdown：按 5.4.2 扫描 code fence；`text`/`json`/`yaml` 等非执行语言跳过。
- `SkillCapabilityCatalog`：从纯静态 descriptor 表（与运行时 `ToolRegistry` 共享同一常量/注册来源）构建 builtin 名称与 group 成员；不实例化需要 provider 的 `BuiltinTools.all/fromSet`。加 parity test，确保 `tool:<group>` 的覆盖判断与真实装配一致。catalog 不完整时未知 builtin 返回 INDETERMINATE，不误判。
- 输出 `DetectedUsage(kind, name, path, line, dynamic, confidence, snippet)`；`snippet` 只保留 1 行、≤200 字符，报告中脱敏。
- 动态用法：不猜测；`DYNAMIC_USAGE` + 建议。若脚本包含 `eval` / `exec` / 动态 import / 网络下载代码，标记 `UNANALYZABLE`，对应规则 INDETERMINATE。
- 分析器版本写入 rule revision / run，便于回溯。

### 6.4 Decision model mapping

| 规则 | state 输入（allowlist） | questions（`noul`，正向=满足） | 通过条件 |
|---|---|---|---|
| `SKILL.NAME_IDENTITY` | name、directoryName、H1、description、正文前 2000 字符 | `name_content_consistent` | `p >= 0.80`；`<=0.20` FAIL；中间 INDETERMINATE |
| `SKILL.DESCRIPTION_TRIGGER_SCOPE` | 仅 description | `trigger_concrete`、`scope_boundary` | 两者都 `>=0.80`；任一 `<=0.20` FAIL；否则 INDETERMINATE |
| `SKILL.WORKFLOW` | 命中的 workflow section（≤8k 字符） | `workflow_ordered`、`workflow_actionable` | 同上 |
| `SKILL.DECISION_LOGIC` | 命中的 decision section（≤8k） | `decision_conditions`、`decision_fallback` | 同上 |
| `SKILL.OUTPUT_SPEC` | 命中的 output section（≤8k） | `output_deliverables`、`output_acceptance` | 同上 |
| `SKILL.ERROR_HANDLING` | 命中的 error section（≤8k） | `error_modes`、`error_actions` | 同上 |
| `SKILL.QUALITY_ASSURANCE` | 命中的 QA section（≤8k） | `qa_checks`、`qa_runnable` | 同上 |
| `SKILL.EVAL_DATASET` | manifest 摘要 + 最多 10 个 case 的 input/expected（≤8k） | `eval_cases_concrete`、`eval_expected_observable`、`eval_dimension_fit` | 三者都 `>=0.80`；任一 `<=0.20` FAIL；否则 INDETERMINATE |

- 默认 decision model alias：`decision-1`（经 gateway 解析为 provider/upstream）；RuleRevision 可 pin `expectedRouteFingerprint`。
- 高值统一表示“满足规则”；B2/B3 这类“存在意图”的极性在规则内声明，不与合规阈值混用。
- 严格响应验证复用 `QualityDecisionGateway`：每个 question 必须有 answer、类型匹配、概率有限且在 [0,1]、choice 标签在 allowlist、route 完整；任何协议错误为 ERROR，不默认 PASS。
- `confidence` 只记录，不作为放行依据。
- 不使用目标 Agent 的 chat model；不生成自由文本理由；evidence 来自原文档片段。

**调用分组与预算口径：**

当前共享 kernel 的实际上限是 `QualityBudget` 的 8 次 provider calls、120 questions、单次 20 questions、48,000 **输入字符**（`MAX_INPUT_CHARS`），`QualityPolicyService` 的规则数与问题数校验直接引用同一常量；它们不是 4 calls / 8 questions，也不是 token 数。设计中的所有预算必须按**字符**表述，不能写 “48k tokens”。

8 条语义规则若逐条调用需要 16 questions / 8 calls，会吃满现有 call 上限，且 `PublishQualityGateService` 当前按 enforcement 排序**逐条**执行规则，无法把两条不同 enforcement 的规则合并成一次 provider call。因此 Skill gate 需要一个 run 级 `SkillDecisionBatchCoordinator`：它在规则循环前按下列 4 组一次性执行并缓存每个 question 的答案，规则 evaluator 只消费缓存结果。同组内只要存在 BLOCK 规则，该组在 BLOCK 阶段执行；SHADOW 规则不得先占用该组预算。

| Call group | 包含规则 | questions | state 上限（字符） |
|---|---|---|---|
| `identity_description` | NAME (1) + DESCRIPTION (2) | 3 | name/H1/description/正文前 2000 字符，合计 ≤8k |
| `process` | WORKFLOW (2) + DECISION (2) | 4 | 两个 section 各 ≤8k，合计 ≤16k |
| `delivery` | OUTPUT (2) + ERROR (2) | 4 | 两个 section 各 ≤8k，合计 ≤16k |
| `quality` | QA (2) + EVAL (3) | 5 | QA section + eval 摘要各 ≤8k，合计 ≤16k |
| **合计** | 8 条规则 | **16** | ≤56k 字符（预算上限 64k，余量给 JSON 包装） |

- SKILL budget profile：`maxCalls=4`、`maxQuestions=16`、`maxInputChars=64_000`、`maxStateCharsPerCall=20_000`、deadline 30s。这些数值放进 `SubjectBudgetProfile`，由 `QualityPolicyService.validateBudget(subjectType)` 读取，不能把 Agent 的常量复制一份写死。
- 每个 question 仍按 0.80 / 0.20 阈值独立判定；一个 call 内任一 question 为协议错误时，该组的规则为 ERROR，不能把同组其他答案当 PASS。
- 规则可以单独 SHADOW/WARN；分组只影响 transport，不改变每条规则的 enforcement。
- 首版 batch coordinator 串行执行 4 组，不做并行 provider call。`QualityBudget` 的计数是普通 `int`，没有并发预留语义；要并行必须先改成线程安全的预留/扣减模型，不能只把循环包进线程池。串行 4 次调用在 30s deadline 内可接受；若实测 p95 不足，再单独设计并发预算。
- 如果某组 state 超过 20k 字符或任一 section 超过 8k：返回 `INPUT_TOO_LARGE` / `SECTION_TOO_LARGE` INDETERMINATE，建议拆分，不静默截断。

**输入规模：**

- 单 section 送入 ≤8k 字符；超长返回 `SECTION_TOO_LARGE` INDETERMINATE，issue 必须包含文件路径与实际字符数。
- 单次 run 最多 16 个 decision questions、4 次 provider calls、64k 输入字符；超出返回 `INPUT_TOO_LARGE` / `BUDGET_EXHAUSTED`。
- 发布 gate deadline 30s；单调用 20s；首版串行 4 组。技能包通常小于 Agent 配置，30s 足够；若实测 p95 不足，先优化输入选择，不无限延长 HTTP 超时。

### 6.5 证据与建议

- 代码 issue 的 evidence 来自 candidate：路径、行号、实际值、期望值、维度计数、JSON pointer。
- 语义 issue 的 evidence 是命中 section 的原文片段（≤200 字符）与 `SEMANTIC_ASSESSMENT`；不能生成不存在的行号。
- 建议来自规则模板（版本化），只允许已声明变量；不渲染不可信 HTML。
- 每条 issue 必含：rule ID/revision/name/requirement、status/enforcement、field_path、message、suggestion、issue_reason、retryable、recommended_action。
- `field_path` 示例：`/name`、`/description`、`/metadata/version`、`/metadata/tags`、`/allowed-tools/2`、`/SKILL.md#workflow`、`/references/workflow.md#step-2`、`/eval/eval.json#/cases/3`。
- 没有动态证据时，规则说明、被检字段、已知值、模板建议仍必须齐全；不用第二个生成式模型写“理由”。

### 6.6 Candidate 与 receipt

- `packageDigest` 使用现有 `SkillDigest.of(content, resources)`；CLI 与 server 已有同算法与测试向量，不另造哈希。
- `metadataHash` 覆盖 name、description、metadata、normalized permissions；用于判断“仅元数据变化”与 issue 定位。
- `SkillQualityReceipt` 写入 SkillDefinition：
  - `runId`、`policyRevision`、`revision`、`packageDigest`、`metadataHash`、`gateResult`、`warnCount`、`ruleResultDigest`、`routeFingerprint`、`checkedAt`。
- 读取时 `receipt.packageDigest == entity.digest` 且 `gateResult == PASSED`（含 WARN）时，可证明这个 digest 通过了门禁；REJECTED 不应写入。receipt 用于证明“这个 digest 被检查过”，不替代运行时授权。
- 报告可保留 90 天；receipt 与 publication record 按审计保留期保存。

## 7. 数据模型、策略与管理 UI

### 7.1 数据模型（拟新增或扩展）

| 实体 | 核心字段 | 初衷与理由 |
|---|---|---|
| `SkillQualityRule` | id、key、subjectType=SKILL、name、category、latestRevision、lifecycle、builtIn | 稳定身份用于追踪与 UI 列表；subjectType 防止与 Agent 规则混用 |
| `SkillQualityRuleRevision` | ruleId、revision、description、rationale、kind、evaluatorKey/version、typed parameters、inputSelector、decisionSpec、messageTemplate、remediationTemplate、createdBy/At | 每次规则变更有可追溯初衷；旧报告含义不被新 revision 改写 |
| `SkillQualityPolicyRevision` | id、revision、scope=`skill:server`、ruleRevisionBindings、activatedBy/At、changeReason | 固定一次写入使用的规则集合 |
| `SkillQualityPolicyHead` | scope、activeRevision、generation | 单文档原子激活，避免半套规则 |
| `SkillQualityRun` | runId、subjectType=SKILL、skillId/qualifiedName、actor、mode、candidateHash、metadataHash、policyRevision、runStatus、gateResult、checksComplete、issues、ruleResults、routeFingerprint、modelUsage、timings、createdAt/finishedAt/expiresAt | 检测状态与 skill 内容分离；绿灯按 digest 比较 |
| `SkillQualityReceipt`（Skill 内） | runId、policyRevision、revision、packageDigest、metadataHash、gateResult、warnCount、ruleResultDigest、routeFingerprint、checkedAt | 与写入同一条件更新，证明实际存储内容对应哪次检查 |
| `SkillPublicationRecord`（追加式） | skillId、qualifiedName、revision、runId、packageDigest、contentDigest、policyRevision、routeFingerprint、gateResult、ruleResultDigest、warnCount、operationKeyHash、publishedBy/At、sourceType | 报告过期后仍能证明发布历史 |
| `SkillEvaluationSummary`（run 内嵌或独立） | datasetVersion、datasetDigest、caseCount、dimensionCounts、missingDimensions、qualityVerdict | 发布报告能展示 eval 覆盖；不复制用例内容 |

**共享 kernel vs 独立 store：**

- 推荐：把现有 Agent 质量门禁的 **状态机、decision gateway、聚合、policy/revision** 抽象为 subject-neutral kernel；在 rule/policy/run 上增加 `subject_type`，agent 行回填 `AGENT`，skill 行写 `SKILL`。`QualityPolicyService.active(subjectType)` 使用不同 head scope（`agent:server` / `skill:server`）。
- 兼容：现有集合名（如 `agent_quality_runs`）可以保留，新增 `subject_type` 字段；或新增 `quality_runs` 并迁移。无论选哪种，必须保证 Agent 门禁回归测试通过。
- 备选：新建 `skill_quality_*` 集合并只复用无状态组件（`QualityDecisionGateway` / `QualityBudget` / `QualityRunAggregator` 语义）。实现更快，但两套 policy 生命周期容易漂移；若选此方案，必须在文档中记录差异并共享报告契约（见 ADR-SQ-08、Q9）。
- **实施风险（AI 评审建议，待 Q9 确认）：** 现有 `PublishQualityGateService` 直接依赖 `AgentDefinition`、`AgentDefinitionStore`、`AgentQualityStore`、`AgentQualityRun.agentId` 与 `PublishCandidateBuilder`，不是纯 subject-neutral。P1 若同时重写线上 Agent 门禁的存储与服务，回归范围会覆盖整个已上线的发布路径。折中路线：P1 只抽出无状态契约（参数化 budget profile、decision gateway、聚合语义、report mapper），持久化用独立 `skill_quality_*` 集合；等 skill gate 稳定后再评估合并集合。这是路线建议，不是已批准决策。

**索引与存储：**

- `skill_quality_runs`：`skill_id + created_at`；`operation_key_hash` 唯一（有 key 时）；`skill_id + candidate_hash` 便于重试查询。
- `skill_publication_records`：`skill_id + published_at`；`operation_key_hash` 可查。
- `skill_quality_policy_heads`：`scope` 唯一；`skill_quality_policy_revisions`：`revision` 唯一。
- `skills.qualified_name` 唯一索引（Mongo 字段 `qualified_name`）；PG 的 JSONB 字段是 `qualifiedName`，必须建在表达式 `data->>'qualifiedName'` 上，不能照抄 Mongo 字段名。建索引前先审计存量重复：有重复则中止迁移并输出清单，不静默删除。
- `skills.revision` 单调递增；写入使用条件更新（见 8.3）。迁移必须先把存量 skill 回填 `revision=1`；滚动发布期间旧副本可能写入 `revision=null`，CAS 把 null 视为 revision 0 并在首次写入时置 1，避免存量 skill 首次更新永远匹配 0 行。
- `PgDocumentStore` 目前只有无条件 `replace`；需要新增条件更新（`UPDATE ... WHERE id = ? AND data->>'revision' = ?` 或等价方法），并检查受影响行数。不能把 `replace` 后重新读一遍当作 CAS。
- Mongo/PG 双实现与现有 `sys.store.mode` 一致；PG 使用下一可用 Flyway 版本，Mongo 在 `ServerApp.registerMongo()` 注册新集合。迁移版本必须 bump，已有迁移不会重跑。

**core-ng 约束：**

- 实体/视图不使用 `Object`、`Map<String,Object>`、BSON Document；动态 criteria/parameters 存 JSON 字符串，服务端按 kind 反序列化为确定类型。
- 集合值类型明确；持久化数值/布尔用包装类型；默认值字段加 `@NotNull`。
- entity enum 用 `@MongoEnumValue`，view enum 用 `@Property`，同一 enum 不能两用；需要时建镜像 enum。
- `MongoCollection<?>` 字段加 `@Inject`；绑定顺序满足依赖。
- 新集合必须显式注册；不能假设自动发现。

### 7.2 管理 UI

`Settings -> Quality -> Skill Rules`（与 Agent Rules 同一入口，tab 或 subject filter）：

- 列表：rule key、说明、类别、类型（CODE/DECISION/HYBRID）、最新 revision、enforcement、测试状态、更新人、更新时间。
- 参数编辑：description 最大长度、tags 数量范围、eval 最少 case/推荐 case、section 同义词、动态权限策略（SHADOW/BLOCK）、是否允许 `namespace:*`、version bump 策略、主文件大小 WARN 阈值。
- 规则试测：选择/上传一个有权限的 skill fixture（zip 或目录），用同一 evaluator 生成报告；不写入、不发布、不执行脚本。读取真实 skill 作为 fixture 需要操作者有读取权限。
- 激活：先 DRAFT，测试通过后 SHADOW；管理员查看 dry-run 与影响摘要，填 `changeReason` 后激活 BLOCK/WARN。激活是明确确认动作，不等同于保存。
- 历史/回滚：保留不可变 revision；policy 回滚到旧 revision；记录 actor、原因、影响。
- 权限：controller RBAC + service-level human admin 检查；API user 不得因 action RBAC bypass 获得管理员资格；规则激活/停用单独审计。

### 7.3 Skill 编辑器与 Hub UI

- 编辑器增加 “Check quality” 按钮：把当前 SKILL.md + resources + directoryName 作为 precheck candidate 发送；不写入。Save/Push 仍会重新执行门禁，不复用 precheck PASS。
- 质量面板按 Identity / Metadata / Description / Content / Permissions / Eval / Links 分组；BLOCK FAIL 在前，未完成次之，WARN 最后；每条显示规则编号/版本、要求、证据、建议。
- 点击 issue 定位到 SKILL.md 对应 heading/行、metadata 字段、allowed-tools 条目、eval case 或 reference 文件；只有解析器给出精确行号时才高亮行，否则定位到 section。
- PUBLISHED/PUSHED 成功后显示 “已检查规则数 / policy revision / warn count”；失败显示 “未写入，现有 skill 未改变”。
- 不提供 “仍然发布” 按钮；不自动补 Use when/scope、不自动加权限、不自动生成 eval。
- 元数据编辑：v1 建议提供 version/author/tags 结构化字段，并保留未知 metadata key 的原文；如果实现成本高，至少校验并在保存时提示，不接受“编辑器显示 version 但实际没写入”的现状。
- Hub detail：显示 version、tags、required capabilities（从 allowed-tools 规范化）、source；quality 状态默认只对 owner/admin 可见，避免把内部质检信息暴露给所有消费者。
- Hub summary：增加 `version`、`tags`、`required_capabilities`，支持 `skill search` 的结果展示；search 可按 tag 过滤作为可选增强。

### 7.4 规则测试与维护

- 试测只调用同一 evaluator，不发布 skill、不执行脚本、不调用工具。
- `TESTED` 至少满足：每个 fixture 的实际 verdict 与 expected 一致、必需答案完整、无 ERROR；与 expected 不一致的 INDETERMINATE 也视为失败。
- BLOCK 激活要求：正反样例匹配、未完成检查为零、策略预算可执行、附代表性 holdout 报告；最小两个样例只是配置完整性，不是准确率证明。
- 度量：合规样本误阻断率（FAIL/INDETERMINATE/ERROR 都算阻断）与确定判定覆盖率；建议首版目标误阻断 ≤5%、确定覆盖率 ≥95%，需用真实 skill 数据校准。
- 删除规则使用 RETIRED，不物理删；回滚通过激活旧 policy revision；模型/阈值/输入变化重新跑 fixture。
- 回归测试必须覆盖：name/目录/内容漂移、update 绕过、repo/marketplace 路径、权限动态用法、eval 维度缺失、渐进式披露断链、模型超时/路由漂移、CAS 冲突、旧 skill 读取。

## 8. 发布协议、并发和一致性

### 8.1 写入路径与行为

| 入口 | 行为 | 失败时 |
|---|---|---|
| `POST /api/skills/upload`（CLI push / Web upload） | 构建 candidate → 门禁 → 按 qualifiedName 查现有 → insert/update(revision CAS) → receipt/publication record | 422/409/503；新 skill 不创建；已有 skill 不改 |
| `PUT /api/skills/:id`（编辑器保存） | 合并 resources + 新 content → 构建 candidate → 门禁 → 校验请求字段与解析值一致 → CAS update | 422/400；现有内容与 revision 不变 |
| `POST /api/skills/register-repo` / `POST /api/skills/:id/sync` | clone/读取一次 → 每个 skill 构建 candidate → 逐个门禁 → 逐条写入 | 每个 skill 独立报告；失败项不写/保留旧内容；成功项可写 |
| `POST /api/skills/marketplace/:id/install` | 复用 repo 注册；逐 skill 门禁 | 同上；返回 installed/skipped 列表 |
| `SkillRepoSyncJob` | 定时同步；逐 skill 门禁 | 失败保留旧内容；记录报告与告警；不自动回滚其它 skill |
| trusted seed / migration | 不调用模型门禁；代码检查 + CI fixture + `trusted-seed` 审计 | 启动失败或记录迁移错误；不向用户开放 bypass |

**关键行为：**

- 所有入口返回同一种 typed report；HTTP 状态与 errorCode 按 §9.2。
- 失败不修改 `content/resources/digest/revision/updatedAt`，不写 receipt。
- 成功响应保留 `SkillDefinitionView`；通过 header（`X-Skill-Quality-Run-Id` / `X-Skill-Quality-Gate-Result` / `X-Skill-Quality-Warn-Count`）暴露摘要，旧客户端不解析也兼容。
- 批量 repo/marketplace 不是“全有或全无”；每个 skill 独立原子性，响应必须列出哪些写入、哪些跳过、为什么。

### 8.2 Candidate 一次构建与字段投影

这是本设计最重要的结构修复：

1. `SkillPublishService` 读取/合并后，只调用一次 `SkillPublishCandidateBuilder.build(...)`。
2. Builder：
   - 解析 frontmatter 一次；
   - 校验 `name`/`description`/`metadata`/`allowed-tools`；
   - 读取 resources 一次（repo 从 clone 目录；upload 从 multipart；update 从合并结果）；
   - 计算 `packageDigest` / `contentDigest` / `metadataHash`；
   - 生成 skills 实体投影：`name`、`qualifiedName`、`description`、`metadata`、`allowedTools`、`version`。
3. **资源物化顺序（门禁前不写对象存储）：** 现有 `SkillResourceWriter.merge/toResources/enforceBudget` 会在合并阶段直接调用 `blobStore.put`，并把 `resource.content` 置为 `null`。门禁必须在此之前拿到可读字节，且不能为一次最终被拒绝的发布留下副作用。因此：
   - candidate 的 resources 是纯内存的 `Map<path, byte[]>`；
   - `keep=true` 的已有资源按 `storagePath` 只读 hydrate 字节，不修改存储行；
   - 门禁阶段禁止调用会写 blob 的 writer 方法；只有门禁 `PASSED` 后才执行 blob 写入、inline/budget 物化与 CAS；
   - 资源路径过滤（`isIgnoredResource`）只影响 candidate 与链接校验，不能用于跳过权限扫描。
4. `PUT /api/skills/:id` 的 `content` 必须是本次更新的完整 SKILL.md（编辑器已有全文；这是首版 API 细化）。如果 `content` 缺失，只允许不改变 name/description/metadata/allowed-tools 的 resource 操作；该操作同样要用“旧 content + post-merge resources”构建完整 candidate 并跑完所有门禁。此时 `description` / `allowedTools` 请求字段若存在且与现有解析值不同，返回 `400 SKILL_METADATA_MISMATCH`。不支持“只改 description 但 content 不变”的静默改写。
5. `PUT /api/skills/:id` 的 `description` / `allowedTools` 请求字段成为兼容镜像：
   - 如果存在且与解析值不同，返回 `400 SKILL_METADATA_MISMATCH`；
   - 如果一致，忽略请求字段，使用解析值；
   - 不静默选择其中一个，避免 UI 与文档漂移。
6. commit 写入同一 candidate；禁止在 commit 时重新读取 SKILL.md、重新解析 resources 或重新计算 name。
7. `directoryName` 写入 SkillDefinition（新字段）；旧 skill 回填 `entity.name`。update 不允许改变 `name` / `directoryName`：content 解析出的 name 与存储不一致返回 `SKILL_NAME_IMMUTABLE`（改名 = 新建 + 下线旧 skill）。
8. 资源路径先规范化（剥离前导 `./`、折叠 `//`、统一 `/`）再调用 `SkillLoader.isIgnoredResource`；被忽略的路径若被 SKILL.md/reference 引用，必须报 `REFERENCE_NOT_PACKAGED`，不能静默断链。

**理由：**现有 `update` 只改 `entity.content`，不解析 name/description/metadata；如果门禁检查的是请求字段，而写入的是另一份 content，就会出现“检查通过但发布别的内容”。candidate-once 与投影校验同时解决这个绕过和字段漂移。

### 8.3 Revision 与 CAS

- `SkillDefinition` 增加 `revision`（Integer，新建 1，更新 +1）。迁移先把存量文档回填 `revision=1`；滚动发布期间旧副本可能写入 null，CAS 将 null 视为 0，并在首次成功写入时置 1。
- `SkillStore` 增加 `boolean replaceIfRevision(SkillDefinition entity, int expectedRevision)`：
  - Mongo：`updateOne(filter = _id + revision, set = 新文档)`，检查 modifiedCount；不能 replace 后假装 CAS。
  - PG：新增条件更新方法；JSONB 字段是 `revision`，SQL 形如 `UPDATE ... WHERE id = ? AND COALESCE(data->>'revision','0')::int = ?`；检查受影响行数。不能复用无条件 `replace`。
- 新建：`qualified_name` 唯一索引；Mongo 用 `qualified_name`，PG 用表达式 `data->>'qualifiedName'`。建索引前先审计重复数据；如存在重复，迁移中止并输出清单，不静默删除。insert 冲突返回 409 `SKILL_ALREADY_EXISTS`。
- 更新：客户端可传 `If-Match: <revision>`；不传时服务端使用读取时 revision，但 commit 仍必须 CAS。
- CAS 失败返回 `409 SKILL_QUALITY_STALE`（或 `SKILL_REVISION_CONFLICT`），提示刷新后重新检查；不重放旧报告。
- 并发 upload 同一 qualifiedName：一个成功，另一个 409；不能两个都覆盖。
- 并发 update/publish/delete：delete 后 CAS 零更新，不能重建；update 与 publish 只有一个 revision 匹配。
- `revision` 只负责 CAS；“绿灯”有效性按 `packageDigest` + `metadataHash` 比较，避免 description-only 修改把执行内容误判为过期，也避免只改元数据就重新获得绿灯。
- **Blob 与 CAS 的失败边界：** 不承诺跨文档库与对象存储的原子事务。门禁通过、blob 已写、CAS 返回 409 时，允许留下一个不可见的 content-addressed orphan（路径含 content hash，不会被任何 SkillDefinition 引用）；由异步 GC 按“无引用 + 超过 24h”清理并记录指标。不能为了消除 orphan 而把 blob 写入提前到门禁之前，也不能把 CAS 失败伪装成成功。判定发布是否成功只看 skill 文档 CAS 的结果。

**理由：**门禁拉长了 read-write 窗口；当前 `replace` 无条件覆盖会丢用户编辑，并可能把检查过的内容替换成另一份。单文档 CAS 是当前 Mongo/PG 存储可落实的边界；不假设跨库事务。

### 8.4 幂等、重试与崩溃

- `POST upload` / `PUT update` / `POST quality-checks` 支持可选 `Idempotency-Key`，作用域为 actor + skill qualifiedName/id + operation。
- 同 key + 同 `packageDigest`：返回已有终态报告；同 key + 不同 digest：409 `IDEMPOTENCY_CONFLICT`。
- run 在模型调用前创建（STARTED）；同 key 并发：STARTED 未超时返回 409 + runId；超时（建议 90s grace）CAS 为 ABORTED，要求新 key。
- 写入成功后，receipt + publication record 是“已提交”的证据；HTTP 断开重试时按 digest/revision 查 receipt，返回 `alreadyPublished=true`，不重复写、不重复计费。
- 写入前崩溃：STARTED 过期后 ABORTED；不自动重发 publish；新 key 重新检查。
- 不承诺跨集合 exactly-once；如果 receipt 与 publication record 无法证明写入结果，标记 `PUBLICATION_OUTCOME_UNKNOWN`，要求人工/客户端刷新，不猜测。
- 发布失败（FAIL/INDETERMINATE/ERROR）不自动重试模型；provider 429/5xx 可在 deadline 内重试一次；路由漂移不换模型。

### 8.5 预检查与缓存

- `POST /api/skills/quality-checks` 接受完整 candidate（multipart 或 JSON + content/resources），返回报告但不写入；供编辑器 “Check quality”、CLI `skill check` 使用。
- 预检查 PASS 不是发布令牌；`skill push` / save 必须重新执行门禁，避免资源、策略、模型路由变化漏检。
- 可以缓存非敏感的 registry/内置工具清单，但最终权威读取与 commit CAS 不得由 stale cache 取代。
- 同一 idempotency key 的重放属于同 operation 去重，不是跨请求绕过检查。

### 8.6 已有 Skill、Trusted Seed 与例外

- 门禁上线前已存在的 skill：保持可读、可拉取、可运行；不自动下架、不删除、不改 digest。下一次 upload/update/repo sync 时执行门禁。
- 旧 skill 没有 `quality` receipt：Hub 可以显示 “not yet checked”（仅 owner/admin），不阻塞读取。
- 启动 seed / migration 如果直接写 skill：必须在 §14.5 登记；走代码检查 + CI fixture，不调用模型，不向用户开放 bypass；写 `trustedSeed=true` 审计标记。
- repo sync / marketplace 内容不是 trusted seed；必须逐 skill 检查。策展/白名单不能成为用户绕过门禁的入口。
- 管理员紧急处理：只能回滚 policy 或临时 suspend DECISION 规则（有 actor/原因/范围/到期时间）；不能替作者跳过确定性规则。
- **管理员豁免白名单（2026-11-19 实现）：** 为“已知违规但不能改（外部 skill）或不愿改”的 subject 提供受控豁免。白名单随 active skill policy revision 固化，保存时由服务端生成 `change_reason`，只有 human platform admin 可修改；入口是 Settings → Quality → **Exemptions** 列表页，同一页面统一展示 agent 与 skill 两个 policy 的条目，新增时先选类型（Agent/Skill）再从可搜索的 subject 选择器中选择。Agent 条目按 agent id 落库，skill 条目按 `namespace/name` 落库（精确匹配，不支持通配符）；subject 已删除时条目仍会显示且可移除。命中后该 subject 跳过全部规则评估，报告与 receipt 记录 `exempted/exemption_reason`，不再阻塞 upload/editor/repo sync/marketplace install。它不是作者可用的 bypass：普通作者没有写白名单的权限。

### 8.7 版本与内容变化

- upload/editor：`contentDigest` 改变时 version 必须递增；只改 metadata 不强制。
- repo sync：commit 是内容版本源；`metadata.version` 未变给 WARN，不阻断，除非策略显式配置为 BLOCK。
- marketplace install：同 repo；对已安装 skill 的更新给 WARN，记录 repo commit。
- version 回退（新版本低于旧版本）在 upload/editor 为 FAIL；repo/marketplace 为 WARN + 审计。
- 旧顶层 `version`：接受为 alias，WARN；写入时投影到 `entity.version`，但不改写 SKILL.md 原文（避免未经用户同意的内容变更）。

### 8.8 保证强度

- **强保证：**被写入的 skill package 与被检查的 candidate 相同（同一对象、同一 digest）；存储写入通过 revision CAS；receipt 与 packageDigest 一致。
- **提交前复核：**内置工具注册表、外部能力可见性、active policy 在写入前再次读取；变化返回 STALE。外部 MCP/API/agent 的可见性可能在检查后变化，本设计不承诺跨资源原子。
- **弱保证：**`allowed-tools` 是声明，不是运行时授权；外部资源存在性最多 WARN；skill 的真实行为由 eval/运行层验证。
- 资源删除/撤权在写入后仍可能发生；运行层仍校验，不因 receipt 绕过授权。

## 9. API、错误协议、CLI 与 UI

### 9.1 API 契约（拟新增或变更）

| API | 行为 | 初衷与理由 |
|---|---|---|
| `POST /api/skills/upload` | 保留 URL；multipart 增加 `skill_dir` 文本字段；始终执行门禁；成功返回 SkillDefinitionView + quality headers | 所有已有调用者获得强制保护；目录一致性可验证 |
| `PUT /api/skills/:id` | 保留 URL；merge 后执行门禁；校验 description/allowedTools 与解析值一致；支持 If-Match | 封住编辑器保存绕过；不允许字段漂移 |
| `POST /api/skills/quality-checks` | 接受完整 candidate（multipart/JSON），同步返回 typed report，不写入 | 编辑器/CLI 预检查，不产生发布副作用 |
| `GET /api/skills/:id/quality-checks/:runId` | 读取有权限的报告与 run 状态 | 重试/调试不依赖长错误字符串 |
| `GET /api/skills/:id/quality-checks/latest` | 读取最近报告、WARN 摘要 | 发布成功后的 WARN 交付 |
| `GET /api/admin/skill-quality/rule-templates` | 返回 CODE/HYBRID 模板、参数 schema、支持的输入 | UI 从后端能力目录渲染，避免前后端两套规则 |
| `/api/admin/skill-quality/rules` | CRUD 逻辑身份、创建不可变 revision、retire | 可扩展规则治理 |
| `POST /api/admin/skill-quality/rules/:id/test` | 对指定 revision 和有权 fixture 试测 | 同一执行器回归 |
| `POST /api/admin/skill-quality/policies/activate` | expected generation + policy revision + changeReason，原子激活 | 防止多人管理覆盖和半套生效 |

- 成功写入不改变 `SkillDefinitionView` body 的既有字段；新增 quality 信息优先放响应头 / latest report，避免破坏旧客户端。
- 质量报告读取至少要求对该 skill 有 `skill.view`；管理规则要求 human admin + `skill.manage`，API user 不得继承管理员。
- **`skill_dir` 必填**：新上传/新 CLI 必须发送目录名；缺失返回 `400 SKILL_DIRECTORY_REQUIRED` 并提示升级 CLI。不做“缺失就 WARN 放行”，否则任何客户端省略字段即可绕过目录一致性；过渡期如需兼容旧 CLI，只能由 server 配置显式开启并标记 `INDETERMINATE`，且必须有到期时间（见 Q1/Q10）。
- **批量 repo/marketplace 响应**：`RegisterRepoSkillsResponse` 增加 `installed`、`skipped`、`quality_reports`；`MarketplaceRepoDetailResponse` 增加最近一次 `install_status` / `skipped`。至少一个 skill 成功 → 200 + 逐项结果；全部失败 → 422 + reports，不创建 `skillCount=0` 的 MarketplaceRepo 记录（已有 repo 保留）。
- **`createMarketplaceRepo` 的载体不匹配**：该 RPC 当前返回单数 `MarketplaceRepoView`（`SkillWebServiceImpl.createMarketplaceRepo`），无法承载逐 skill 结果。首版让它返回 `MarketplaceRepoView` + 质量摘要字段（`installedCount` / `skippedCount` / `qualityReports`），或新增 `CreateMarketplaceRepoResponse`；不能声称已有 response 能原样返回批量结果。
- **RPC 响应头**：`SkillWebServiceImpl` 实现的是 core-ng RPC 接口，返回 POJO 而非 `Response`；quality headers 必须通过注入的 `WebContext.response().header(...)` 写入，并在契约测试中覆盖，不能假设框架会自动加上。

### 9.2 错误分类

| HTTP | errorCode | 语义 / 修复 |
|---|---|---|
| 422 | `SKILL_QUALITY_REJECTED` | 至少一条 BLOCK FAIL；修 SKILL.md / metadata / permissions / eval |
| 422 | `SKILL_QUALITY_INPUT_UNRESOLVED` / `SKILL_QUALITY_INPUT_TOO_LARGE` | 必需语义检查无法判定或输入超预算；不宣称确定违规，建议澄清/精简；retryable=false |
| 400 | `SKILL_METADATA_MISMATCH` | update 请求字段与 SKILL.md 解析值不一致；刷新编辑器后重试 |
| 400 | `SKILL_NAME_IMMUTABLE` | update 试图通过 content 改变 name/directoryName；改名应新建 skill 并下线旧 skill |
| 400 | `SKILL_DIRECTORY_REQUIRED` | upload 未发送 `skill_dir`；升级 CLI 或补字段后重试 |
| 400 | `SKILL_DOCUMENT_INVALID` | SKILL.md 无法解析/无 frontmatter/YAML 非法；修正文件 |
| 409 | `SKILL_QUALITY_STALE` | revision / 依赖 / active policy 变化；刷新后重新检查 |
| 409 | `SKILL_ALREADY_EXISTS` / `SKILL_PUBLISH_IN_PROGRESS` / `IDEMPOTENCY_CONFLICT` | 同名并发新建 / 原请求处理中 / key 对应不同输入；查 run，不重复提交 |
| 503 | `SKILL_QUALITY_UNAVAILABLE` | 必需检查因 provider/transport/内部错误无法完成；临时故障可重试，配置故障联系管理员 |
| 429 | `SKILL_QUALITY_CAPACITY_EXCEEDED` | 超调用者配额/并发；给 Retry-After，不修改 skill |
| 403 | 现有权限代码 | 无权发布/管理；质检不是权限判断的替代 |

- 每个问题必须有 `issue_reason`、`retryable`、`recommended_action`（`EDIT_INPUT / RETRY / CONTACT_ADMIN / REFRESH`）。
- 多原因并存全部列出；已有 FAIL 时仍 422 REJECTED，同时列出未完成项。
- 现有错误协议保持 `{errorCode,message}`，增加可选 `quality_report`；其他错误不改变 envelope。
- 前端新增保留 status/errorCode/payload 的 `ApiError`，不能只抛 `Error(message)`；CLI `--json` 输出完整 `error.quality_report`。

### 9.3 报告示例

```json
{
  "errorCode": "SKILL_QUALITY_REJECTED",
  "message": "Skill 未写入：3 项质量规则未通过。现有 skill 未改变。",
  "quality_report": {
    "run_id": "skill-quality-run-123",
    "subject_type": "SKILL",
    "skill_id": null,
    "qualified_name": "stephen/poster-replica",
    "candidate_hash": "sha256:...",
    "metadata_hash": "sha256:...",
    "policy_revision": 3,
    "gate_result": "REJECTED",
    "checks_complete": true,
    "issues": [
      {
        "rule_id": "SKILL.NAME_IDENTITY",
        "rule_revision": 1,
        "rule_name": "名称与文档身份一致",
        "rule_requirement": "SKILL.md name、目录名与文档主题必须一致",
        "status": "FAIL",
        "enforcement": "BLOCK",
        "field_path": "/name",
        "evidence_kind": "CODE_FACT",
        "message": "SKILL.md name 为 poster-replica，但目录名为 poster-tool。",
        "suggestion": "将目录重命名为 poster-replica，或修改 frontmatter name 后重新发布。",
        "issue_reason": "DIRECTORY_NAME_MISMATCH",
        "retryable": false,
        "recommended_action": "EDIT_INPUT"
      },
      {
        "rule_id": "SKILL.DESCRIPTION_TRIGGER_SCOPE",
        "rule_revision": 1,
        "status": "FAIL",
        "enforcement": "BLOCK",
        "field_path": "/description",
        "evidence_kind": "CODE_FACT",
        "message": "description 缺少 Use when 触发条件。",
        "suggestion": "改为：Use when the user asks to … Scope: …。",
        "issue_reason": "MISSING_USE_WHEN",
        "retryable": false,
        "recommended_action": "EDIT_INPUT"
      },
      {
        "rule_id": "SKILL.PERMISSION_DECLARATION",
        "rule_revision": 1,
        "status": "FAIL",
        "enforcement": "BLOCK",
        "field_path": "/references/fetch.py#L12",
        "evidence_kind": "CODE_FACT",
        "evidence_text": "s.mcp[\"github\"][\"search_issues\"](...)",
        "message": "脚本使用 MCP github/search_issues，但 allowed-tools 未声明。",
        "suggestion": "在 allowed-tools 中加入 mcp:github 或 mcp:github/search_issues，并在 Agent 中挂载该 server。",
        "issue_reason": "PERMISSION_NOT_DECLARED",
        "retryable": false,
        "recommended_action": "EDIT_INPUT"
      }
    ]
  }
}
```

- `evidence_text` 只放最小片段；不回显完整脚本/密钥。
- decision issue 额外携带 `question_id`、`predicate`、`polarity`、`decision_metric`、`affirmative_probability`、`threshold`、`decision_mapping`；不能只给裸概率。
- 成功且存在 WARN：写入 200，响应头 `X-Skill-Quality-Gate-Result: PASSED`、`X-Skill-Quality-Warn-Count: N`、`X-Skill-Quality-Run-Id`；UI 不得静默成功。

### 9.4 CLI 行为

- `core-ai-cli skill push <dir>`：
  - 发送 `skill_file`、resources、`skill_dir`；服务端门禁；
  - 失败时人类模式分组打印 issue（规则、位置、建议），`--json` 输出完整 `quality_report`；退出码保持 1（tool/business error），不新增退出码；
  - 不提供 `--skip-quality`。
- `core-ai-cli skill check <dir>`：调用 `POST /api/skills/quality-checks`，不写入；退出码 0 = PASSED（含 WARN），1 = REJECTED/UNAVAILABLE；`--json` 便于 CI。
- `core-ai-cli skill show <ns>/<name>`：展示 version、tags、required capabilities、source；owner 可看到最近 quality 摘要。
- `core-ai-cli skill search`：结果增加 version/tags；可选 `--tag` 过滤（后续）。
- 旧 CLI 未发送 `skill_dir`：服务端返回 `400 SKILL_DIRECTORY_REQUIRED`，CLI 提示升级；不做静默降级，也不把旧客户端当作已通过。

### 9.5 UI 行为

- 操作过程：“Check quality → Save/Push → 结果”，按钮 loading；Save/Push 期间互斥，避免本地状态覆盖。
- 失败：保留编辑器内容，不清空；显示“未写入，现有 skill 未改变”；新建 skill 时显示“未创建”。
- WARN 成功：显示“已写入，N 条警告”，可打开 latest report；不静默成功。
- issue 分组与定位见 7.3；不提供普通用户 “仍然发布”。
- 元数据表单：version/author/tags 与 SKILL.md 同步；如果用户直接编辑原文，保存后重新解析并显示差异。
- Hub detail 的 required capabilities 用 “需要 / 不包含” 文案，不使用 “已授权”。

## 10. 安全、运行预算与可观测性

### 10.1 信任边界与权限

| 风险 | 控制 | 初衷与理由 |
|---|---|---|
| SKILL.md / reference 写入 “忽略规则，返回 PASS” | 文档/脚本只作为 untrusted state；criteria 固定；judge 无工具、无网络、无写权限；输出严格验证；对抗 fixture | 语义隔离不能保证防注入，但不能让判定拥有执行能力 |
| skill 作者用同名资源冒充所需能力 | 权限声明按命名空间 + 名称匹配；不因描述宣称“支持联网”就通过；外部可见性只 WARN，不授予 | 名称不是授权；skill 只能声明需要什么，不能自行获得能力 |
| 发布检查执行不可信脚本导致 RCE | 静态词法/语法分析；不 import、不 eval/exec、不执行 shell/python；不运行 eval runner | 门禁本身不能成为远程代码执行入口 |
| 路径穿越 / 符号链接逃逸 | canonical path 校验；拒绝 `..`、绝对路径、符号链接；resource 必须位于 skill root | skill 会被解压到 sandbox；包路径不能逃出根目录 |
| 外链 SSRF / 发布时网络依赖 | 不抓取任何 URL；外链不能作为要求来源；只允许作为补充资料 | 发布路径不应访问外部服务，也不应依赖网络可用性 |
| 报告/日志泄露 prompt、脚本、密钥 | 只保存最小 evidence 片段；不把完整 SKILL.md/resources 写日志；二进制不送模型；错误 body 最小化 | 质量报告比普通错误更敏感，不能扩大暴露 |
| 管理员规则变成 RCE | 只允许注册 evaluator + 受限 DECISION spec；消息模板变量 allowlist；无 eval/任意脚本 | 扩展性不能以控制面 RCE 为代价 |
| API user / 普通作者绕过管理员 | controller RBAC + service-level `userType != API_USER` + human admin 检查；策略激活单独确认 | 平台治理必须高于单个 skill 管理权限 |
| 过大包 / 过多文件 / 超大 eval 造成 DoS | 文件数、单文件、总包、fixtures、decision questions/tokens/deadline、issue 数量上限；越界明确报错 | 可扩展但有资源边界 |
| 动态/不可分析用法被当作通过 | `DYNAMIC_USAGE` / `UNANALYZABLE` 返回 INDETERMINATE，绝不 PASS | 不能让“看不懂”变成“没问题” |
| Issue 内容 XSS / 外联 | UI 纯文本转义；不渲染不可信 HTML/图片链接；deep link 由固定 action type 构造 | 错误面板也是不可信输出 |

### 10.2 预算与恢复

- 每个 skill 同时最多 1 个 publish evaluation；每调用者最多 2 个；每 server 实例最多 6 个模型评估批次；用独立 semaphore/容量指标，不依赖仅覆盖 Gateway HTTP 的 admission interceptor。
- 单次 run：deadline 30s；单 provider call 20s；最多 4 次 provider calls；最多 16 个 questions；最多 64k 输入字符；最多 100 个可见 issue。Skill gate 使用独立的 `SubjectBudgetProfile`，不复用 Agent 的预算常量（Agent 目前是 8 calls / 120 questions / 48k 字符，且不会因为本设计而放宽）。首版 batch coordinator 串行调用，不做并行批次：`QualityBudget` 的计数器不是线程安全的，也没有预留语义。
- 包限制：最多 500 个 resource；单文件 32MB；总包 64MB；eval fixtures 20MB；SKILL.md 32KB WARN。
- provider 网络/5xx/429 可在剩余 deadline 内重试一次，尊重 Retry-After；没有预算直接 ERROR；不重试 FAIL/INDETERMINATE，不自动换模型投票。
- 默认固定 decision route；route 不可用或 fingerprint 不匹配时 BLOCK 规则 ERROR，不静默 fallback。
- 管理员可以回滚错误 policy revision 或临时 suspend DECISION 规则（有 actor/原因/范围/到期时间）；确定性规则不能 suspend；恢复管理 API 不受单个 skill 门禁影响。
- 记录 model usage：caller、skill、run、rule group、provider、upstream model、input/output tokens；复用 quota/pricing 前先确认支持 decision 类型；缺失时标 `cost_unknown`，不记 $0。

### 10.3 审计、指标与保留

- 每次 run 记录：skill/actor、candidate/metadata hash、规则状态、checker 版本、问题的规格 hash、requested/actual model、route fingerprint、概率/分数、error category、耗时、usage、是否已写入、receipt。
- 每次成功写入追加 `SkillPublicationRecord`：runId、revision、digest、policy revision、route fingerprint、gateResult、ruleResultDigest、warnCount、sourceType、publishedBy/At。
- 指标：`skill_quality_run_total`（mode/result）、`skill_quality_rule_result_total`（rule/status）、`skill_quality_rejected_total`、`skill_permission_undeclared_total`、`skill_eval_dimension_missing_total`、`skill_quality_route_drift_total`、p50/p95/p99 gate/rule latency、model error rate、INDETERMINATE rate、CAS conflict、capacity reject、precheck usage。
- 告警起点（需 shadow 数据调整）：5 分钟模型 ERROR >5%、p95 >20s、INDETERMINATE 突升、规则激活后拒绝率异常跳变。告警不自动放宽 BLOCK。
- 报告默认保存脱敏证据与指纹，建议 90 天；receipt 与 publication record 按 skill 发布审计保留。若需要精确输入重放，可受控加密保存 candidate JSON（建议 30 天，独立权限/密钥/清理）；不能把完整脚本写普通 action/trace log。
- 报告过期后仍能用 receipt/publication record 证明“某个 digest 在某个 policy 下通过/被拒绝”；不承诺未归档输入可重放。

## 11. 实施计划、验证与上线

**本节为 Review 后的计划；用户尚未授权实施。**

### 11.1 分阶段计划

| 阶段 | 工作 | 必须完成的验证 | 初衷与理由 |
|---|---|---|---|
| P0 设计确认 | 关闭 §13 的开放决策，固定首版规则契约与 enforcement | 用户 Review 记录；SQ1–SQ10 无遗漏 | 避免实现阶段才发现“scope 应包含什么”“eval 是否执行”理解不同 |
| P1 内核与写入 | shared quality kernel subject type / per-subject budget（SKILL 16 questions / 4 calls / 64k 字符，串行 batch coordinator）、candidate builder、CommonMark/SnakeYAML parser、disclosure resolver、permission analyzer、`SkillCapabilityCatalog` parity、eval validator、revision/CAS + 存量回填、typed report、receipt/publication record；接入 upload/update/repo/marketplace | 单元/存储并发/序列化启动测试；旧成功响应兼容；所有写入路径审计；candidate 只构建一次；catalog parity；门禁前零 blob 写入 | 后续语义规则不能建立在不可靠写入、预算不足或错误 builtin 映射上 |
| P2 确定性规则 | NAME/METADATA/PERMISSION 静态/PROGRESSIVE_DISCLOSURE/EVAL 结构/包卫生；section heading 存在性 | 验收矩阵 SQ1–SQ10、旧 skill 兼容、动态用法、断链、eval 缺维、并发 | 先把用户明确的十项要求做成可阻断的底线 |
| P3 语义规则 | description 具体性、name-content、workflow/decision/output/error/QA 语义、eval 用例质量；decision gateway/route/budget | mock adapter 状态机；真实模型 shadow 数据；误阻断/不确定率 | 模型判断需要校准，不能在首日 BLOCK 全量 |
| P4 UI / CLI / Hub | 编辑器 Check/issue 定位、metadata 表单、管理规则 UI、`skill check`、Hub version/tags/required capabilities | UI E2E、权限/越权、WARN 交付、旧 CLI 兼容、断链定位 | 扩展与错误 UX 是核心交付，不是附属 |
| P5 dev shadow / 上线 | CI 镜像、dev E2E；确定性规则 BLOCK、语义规则 SHADOW；现有 skill dry-run 扫描；调阈值；UAT/prod 分阶段 | 实测 p95、预算、误阻断、不确定率；受影响 skill 清单；回滚 drill | 用项目数据校准，不靠示例阈值；上线可恢复 |

### 11.2 验收矩阵

| 需求 | 必测样例 | 预期 |
|---|---|---|
| SQ1 | 目录/name 不一致；非法 name；name 与正文主题不一致；update content 改 name | 422；issue 定位 `/name` 或语义证据；现有 skill 不变 |
| SQ1 API | upload 缺 `skill_dir`；update 只传 description 不传 content；update content 改 name；旧 CLI | 400 `SKILL_DIRECTORY_REQUIRED` / `SKILL_METADATA_MISMATCH` / `SKILL_NAME_IMMUTABLE`；不写入 |
| CAS | 存量 revision=null；并发 update；PG JSONB `qualifiedName`；重复 qualifiedName 迁移 | null 首次写入成功并置 1；并发一个成功一个 409；唯一索引按存储字段建；重复数据迁移中止/清单 |
| SQ2 | 缺 Use when；缺 scope；scope 为 "all tasks"；有效 description；超长 description | 结构缺失 FAIL；语义模糊 INDETERMINATE/FAIL（SHADOW 期只记录）；有效 PASS |
| SQ3 | `s.mcp` 未声明；声明 `mcp:server`；`s.tool("generate_image")` + `media:image`；`s.tool("decide")` + `decision`；`s.api`/`s.agent`/`s.llm_call`；动态 key；未使用声明；旧 `Bash` | 未声明 FAIL；声明覆盖 PASS；动态 INDETERMINATE；未使用 WARN；旧别名 WARN |
| SQ4 | 缺 version/author/tags；非法 semver；tags 重复/大写；content 变化但 version 不变；仅改 tags；旧顶层 version | 缺项分别 FAIL；非法 FAIL；version 未递增 FAIL；仅元数据 PASS；旧字段 WARN |
| SQ5 | 无 Workflow；Workflow 在 reference 且已链接；reference 未打包；只有一句 "follow process" | 缺失 FAIL；链接且打包 PASS；未打包 FAIL；空泛 INDETERMINATE |
| SQ6 | 无条件/动作；只有 "use judgment"；条件→动作→fallback 表；显式 no branching + ask user | 空泛 FAIL/INDETERMINATE；具体 PASS |
| SQ7 | 只写 "return result"；具体路径/格式/验收；只在未链接文件 | 空泛 FAIL；具体 PASS；未链接 FAIL |
| SQ8 | 只有 "handle errors"；两个失败模式 + 动作；工具失败/文件冲突 | 空泛 FAIL；具体 PASS |
| SQ9 | 只有 "looks good"；命令 + 期望结果；引用 eval 但无检查 | 空泛 FAIL；具体 PASS；仅引用 WARN/INDETERMINATE |
| SQ10 | 无 eval/；无 eval.json；缺 1 个维度；schema 非法；占位符；fixtures 缺失；eval 未打包；7 维齐全 | 结构/覆盖 FAIL；占位符 FAIL；齐全 PASS；小数据集 WARN |
| E1 | upload / update / repo register / sync / marketplace install 分别提交坏 skill | 全部被同一规则阻断；失败不写/保留旧内容 |
| E2 | 每条 issue 的字段完整性 | rule ID/version/name/requirement/status/enforcement/field_path/message/suggestion/reason/retryable/action 齐全 |
| E3 | 新 DECISION 规则、改阈值、shadow→BLOCK、retire/回滚 | 不改代码新增 DECISION；未激活不影响写入；历史含义不变 |
| E4 | 要求位于 linked reference；断链；外链；环；深度 3；未引用文件 | 可达且打包 PASS；断链/外链/超深 FAIL；未引用 WARN |
| N1 | 发布检查期间的进程/网络/工具调用审计 | 零 skill 执行、零 MCP 会话、零业务 API、零媒体生成 |
| N2 | 动态 SDK key、模型超时、路由漂移、malformed response、预算耗尽 | INDETERMINATE/ERROR；不放行；报告说明原因与动作 |
| 兼容 | 旧 skill 无 receipt；旧 CLI 无 skill_dir；旧 `allowed-tools`；顶层 version | 可读可用；写入时提示/WARN 或明确未验证；不静默假通过 |
| 并发 | 同时 upload/update/delete；CAS 冲突；同 key 重试 | 一个成功，另一个 409/STALE；不重建已删对象；不重复计费 |
| 预算分组 | 4 组 state 各接近 8k/16k、总字符 >48k（<64k），同组混排 BLOCK/SHADOW | 4 次串行调用、16 questions，不误触预算；SHADOW 不抢 BLOCK 预算；provider 调用数可断言 |
| 资源时序 | resource-only PUT（省略 eval/eval.json + 1.5MB 脚本）；大文本资源 >1MB inline 阈值 | 门禁读到完整字节并 422；对象存储无新增 blob、revision 不变；仅 PASSED 后写 blob |
| SDK 形状 | `s.mcp.github.search_issues()`、`gh = s.mcp.github; gh.search_issues()`、`s.mcp["github"]["search_issues"]`、`s.tool("mcp_name")`、动态 key | 前三种一致命中 `mcp:github`；未知 `s.tool` 名称 INDETERMINATE；动态 key 不静默 PASS |
| 标题/穿透 | `## 1. Workflow`、`## Error Handling & Recovery`、`## Quality Assurance (QA)`、摘要 + 单个 12k reference 链接 | 规范化后命中；唯一链接且正文 <300 字符才穿透；超 8k 报 `SECTION_TOO_LARGE` 且带目标路径/字符数 |
| Marketplace RPC | `createMarketplaceRepo` 2 skill（1 好 1 坏）与全坏 | 部分成功 200 + installed/skipped/reports；全坏 422 且不建 0-skill repo；quality headers 存在 |

### 11.3 非功能与系统验收

- **写入副作用**：任何 FAIL/INDETERMINATE/ERROR 不改变 content/resources/digest/revision/receipt；precheck 不改变 skill；repo 失败保留旧内容。
- **所有入口**：API、CLI、Web、repo、marketplace 均走 `SkillPublishService`；新增直接写 skills 的路径必须在 §14.5 登记。
- **candidate**：frontmatter 只解析一次；commit 不重新解析；name/description/metadata/allowedTools/version 与 candidate 一致。
- **并发**：Save/Push、Push/Push、Delete/Push、repo sync/publish、policy 切换；无 lost update、无 delete 后重建。
- **WARN**：WARN 失败不阻断，但必须可被作者读取；成功响应头带 run id/warn count。
- **路由**：provider/upstream 改变时报告记录 drift；BLOCK 规则不静默换模型。
- **审计**：publication record 不依赖 90 天报告；报告过期后仍能证明 gate result 摘要。
- **性能**：建议 dev 真实负载 p95 ≤20s、硬 deadline 30s；达不到时先优化输入选择/并行，不无限延长 HTTP 超时。
- **权限**：普通协作者仍能发布自己 namespace 的 skill；非 admin/API user 不能改规则；无权 fixture/报告不能读取。
- **UI**：issue 可定位到 section/metadata/permission/eval；不提供强制发布；不静默 WARN。

### 11.4 Eval runner（独立交付）

发布门禁不执行 eval；以下为后续 `skill eval` / CI 的设计边界：

- 输入：`eval/eval.json` + fixtures + skill package。
- 执行：将 skill 挂载到一个测试 Agent，使用固定模型/路由与 sandbox；记录 trace、artifacts、tool calls、parameters、exit codes。
- 评分：
  - Trigger Accuracy：显式/隐式/上下文/negative control；检查 skill 是否被调用；每 case 可重复 N 次，报告稳定性（建议 3 次，成本可配）。
  - Scope Accuracy：边界内继续、边界外拒绝/转交；确定性 expected + rubric 兜底。
  - Tool Accuracy：trace 中实际 tool/capability 与 expected 比较；支持 `tool_not_called`。
  - Parameter Accuracy：tool args 精确/子集/schema 匹配；缺参/多余参数单独报告。
  - Instruction Following：业务规则断言 + trace 顺序/确认点；rubric 只用于开放式规则。
  - Output Quality：文件存在/格式/结构/命名 + rubric（decision model 结构化输出）。
  - Robustness：fixture 注入缺失输入、工具错误、超时、文件冲突；断言 no_crash / graceful failure / no partial output。
- 输出：每维度 pass rate、失败 case、trace 路径、artifact 路径、token/时间；不生成加权总分作为发布依据。
- 首版 eval runner 建议 advisory/CI，不作为 publish BLOCK；在校准并积累数据后，再由 policy 决定是否把某维度作为发布规则。
- 评测模型/路由固定并记录 fingerprint；模型变化需重新基线；不能拿同一测试集反复调阈值而不留 holdout。

### 11.5 现有 Skill 的迁移

1. 先干跑：扫描现有 skill（upload + repo + marketplace），按新规则生成报告，不写入；统计缺失项（metadata、eval、scope、workflow 等）。
2. 提供模板：一个通过门禁的最小 skill 模板（SKILL.md + eval/eval.json + references），以及每个失败规则的修复示例。
3. 分批修复：优先 team-owned skills；用户私有 skill 由 owner 在下次更新时修复。
4. 不自动改写用户文件；可以提供 “生成缺失 section 草稿” 的工具，但必须由用户确认。
5. 门禁上线后，旧 skill 仍可读/可拉取；下一次 upload/update/sync 才阻断。

## 12. 关键决策记录（ADR 摘要）

| ID | 决策 | 初衷/理由 | 主要备选及为何未选 | 代价 |
|---|---|---|---|---|
| ADR-SQ-01 | 服务端 `SkillPublishService` 统一门禁 | 所有入口不能绕过；检查与写入同一层 | 仅 CLI/前端检查：可绕过且内容过期 | 写入路径增加耗时 |
| ADR-SQ-02 | candidate 一次构建；身份/元数据从 SKILL.md 投影 | 防止 update 字段漂移与“检查 A 写入 B” | 请求字段优先：会继续漂移；检查后再解析：无法保证一致 | 需要修正现有 update/upload 契约 |
| ADR-SQ-03 | CODE + DECISION + HYBRID | 精确事实用代码，语义用模型 | 全正则漏语义；全模型误判身份/结构 | 需要 parser/analyzer 与语义校准 |
| ADR-SQ-04 | 渐进式披露链接必须可达且随包发布 | 允许内容分散，但不能丢失 | 只检查 SKILL.md：无法满足用户“可分布在 reference”；允许外链：发布不可验证 | 需要链接图与打包校验 |
| ADR-SQ-05 | Eval 独立 `eval/` + `eval/eval.json`；发布只验契约 | 用户明确要求独立目录/文件；避免发布时随机性/费用 | 内联用例：无法被 runner 发现；发布时跑 eval：成本/随机/副作用 | 需要 eval runner 独立交付 |
| ADR-SQ-06 | 复用 `allowed-tools` 作为类型化权限声明 | 现有格式已有该字段；用户说“在 tools 中声明” | 新增 `permissions`：两套字段易冲突；运行时强制：超出 skill 声明范围 | 需要规范语法与 analyzer |
| ADR-SQ-07 | 静态分析有限；动态用法 INDETERMINATE | 不执行不可信代码；不伪装完整 | 执行脚本分析：RCE/副作用；正则全匹配：误判 | 部分动态 skill 无法自动通过 |
| ADR-SQ-08 | 共享 quality kernel（subject type） | 一套状态机/decision/聚合/审计，避免漂移 | 完全独立 skill gate：实现快但两套治理 | 需要重构 Agent gate 存储/服务并回归 |
| ADR-SQ-09 | repo / marketplace 也纳入门禁 | 等价写入路径不能绕过 | 只守 upload：repo 是后门 | repo 同步可能因坏 skill 报错 |
| ADR-SQ-10 | 确定性规则先 BLOCK，语义规则先 SHADOW | 避免模型误阻断全量写入；用真实数据校准 | 全 BLOCK：上线风险高；全 SHADOW：用户要求未生效 | 需要 shadow 观察期 |
| ADR-SQ-11 | 已有 skill 不追溯下架 | 避免门禁上线造成大面积不可用 | 自动扫描并下架：破坏现有使用 | 旧 skill 暂时没有 receipt |
| ADR-SQ-12 | revision CAS + qualified_name 唯一索引 | 防止并发丢更新/重复创建 | 无条件 replace：丢编辑；全局锁：成本高且跨写方难保证 | 需要改 store 与迁移 |
| ADR-SQ-13 | metadata.version 为规范位置；顶层 version 兼容 | 用户要求 metadata；现有编辑器顶层 version 实际未持久化 | 顶层 version 为规范：不符合用户要求；两者并存：歧义 | 前端/解析器需要迁移 |
| ADR-SQ-14 | 本设计不实现运行时 `allowed-tools` 强制 | 声明与授权分离；避免虚假安全感 | 运行时按 skill 限制工具：需要 Agent/sandbox 授权改造 | 声明错误的 skill 仍可能在运行时失败 |

## 13. 待 Review 决策

以下问题已有建议，用户可以直接接受或按编号修改，不需要重新描述整个方案。

| ID | 待确认事项 | 本稿默认/建议 | 影响 |
|---|---|---|---|
| Q1 | “name 与 SKILL.md 文档内容一致”是否包含：目录名、frontmatter name、正文主题三层？ | 三层都检查；`skill_dir` 必填，缺失直接 400 `SKILL_DIRECTORY_REQUIRED`（不做静默降级）；name 不允许通过 update 改名；正文主题语义 SHADOW→BLOCK | 名称契约的严格度与旧 CLI 兼容 |
| Q2 | description 是否只接受英文 `use when`，还是允许“使用时机/何时使用”等本地化等价表达？ | 首版要求字面 `use when`（大小写不敏感）；本地化作为 policy 参数，默认关闭 | 中文 skill 的编写成本与触发一致性 |
| Q3 | 权限声明复用 `allowed-tools`，还是新增 `tools` 字段？`dataset` 是否默认阻断？ | 复用 `allowed-tools`；dataset 默认 WARN，不因用户未点名而阻断 | 兼容现有 skill 与用户“在 tools 中声明”的措辞 |
| Q4 | 外部 MCP/api-tool/subagent/llm-call/dataset 名称是否必须存在？ | 只对 builtin 名称 BLOCK；外部名称不可见时 WARN，不阻断（skill 可移植） | 防止合法 skill 因发布者权限不足被误杀 |
| Q5 | Eval 每个维度最少 1 个 case，还是要求 2 个/negative trigger 必须？ | 1 个/维度 BLOCK；推荐 10–20 个、trigger/scope/robustness ≥2、trigger 必须有 negative 作为 WARN；后续可升级 BLOCK | 数据集门槛与作者负担 |
| Q6 | 语义规则首版 SHADOW 还是直接 BLOCK？ | 结构/身份/权限/eval 契约 BLOCK；语义质量 SHADOW，校准后 BLOCK | 上线风险与用户要求生效时间 |
| Q7 | 内容变化是否必须 bump version？repo sync 是否例外？ | upload/editor 内容变化必须 bump；仅 metadata 不强制；repo/marketplace 为 WARN | 版本纪律与 repo 自动化兼容 |
| Q8 | repo / marketplace install 是否全部纳入门禁？ | 全部纳入，逐 skill 报告；失败保留旧内容/跳过新 skill | 策展仓库的可用性与安全性 |
| Q9 | 共享 quality kernel 重构，还是独立 skill store？ | 推荐共享 subject-typed kernel；若风险高，先并行 store 但共享契约。AI 复评补充：鉴于现有 `PublishQualityGateService` 强耦合 Agent 实体与 `agent_quality_*`，P1 可先共享无状态组件 + 独立 `skill_quality_*` 持久化，降低线上回归面 | 实施成本与长期维护 |
| Q10 | 已有 skill 是否追溯扫描/下架？ | 不追溯下架；dry-run 报告 + 下次写入阻断；owner/admin 可见未检查状态 | 现有用户可用性 |
| Q11 | author 是否必须等于上传者/namespace owner？ | 不强制；author 是归属声明，可为团队/组织；只校验非空 | 团队协作与账号归属 |
| Q12 | description 超长是否阻断？ | >1024 WARN；>4096 FAIL；不按风格阻断 | context 成本与写作自由 |
| Q13 | 编辑器保存是否强制提交完整 SKILL.md？ | 是；content 缺失只允许不改 metadata/identity 的 resource 操作；旧“只改 description”调用返回 `SKILL_METADATA_MISMATCH`，UI 同版升级 | API 兼容与字段漂移治理 |

## 14. Review 记录与修改记录

### 14.1 用户 Review 记录

**尚未收到用户 Review。** 本文为 Draft，未授权实施。用户确认后在此记录 Review ID、意见、处理方案、影响章节与状态；没有确认不得写成 Approved。

| Review ID | 日期 | Reviewer | 意见原文/范围 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
|  |  |  |  |  |  |  |

### 14.2 AI 技术评审记录（非用户批准）

评审日期：2026-11-16。评审模型：`gemini-4-hydrogen`。评审范围：v0.2 全文、用户原始需求、相关源码事实。总体结论：**Approve with changes**；方向正确，但有预算、权限映射、渐进式披露、CAS 存量兼容与 update 语义问题需先消歧。以下为 AI 评审意见与文档处理，**不是用户批准，不构成实施授权**。

| Review ID | 日期 | Reviewer | 意见摘要 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| R-AI-SQ-01 | 2026-11-16 | core-ai（AI 技术评审） | 8 条语义规则按逐条调用需 16 questions / 8 calls，超过当时文档假设的 8 questions / 4 calls 上限，策略无法激活或运行必爆预算 | 改为 4 个 call group（identity_description / process / delivery / quality），合计 16 questions；定义 SKILL 独立 budget profile。v0.4 复核后更正：现有上限实为 8 calls / 120 questions；profile 改按字符表述（16 questions / 4 calls / 64k 字符） | §6.4、§10.2、§11.1 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-02 | 2026-11-16 | core-ai（AI 技术评审） | 旧 CLI 无 `skill_dir` 时“WARN/INDETERMINATE”与 BLOCK 状态机矛盾：要么阻断旧客户端，要么任何请求省略字段即可绕过 | 改为 `skill_dir` 必填，缺失 400 `SKILL_DIRECTORY_REQUIRED`；不做静默降级；明确 skill_dir 是协作契约、不是防伪造边界；name 不允许 update 改名 | §5.1、§8.2、§9.1、§13 Q1 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-03 | 2026-11-16 | core-ai（AI 技术评审） | 权限检测与 `BuiltinTools`/SDK 实际实现不匹配：group key 带 `builtin-` 前缀、`ALL` 缺 transcribe/understand/submit/decide、漏 `s.builtin`/`s.agent(...)`/`async_session`/`s.files`；`example` 跳过可绕过；dataset 不应默认 BLOCK | 增加 group 别名归一化表与 `SkillCapabilityCatalog`（与运行时 registry parity）；catalog 不可用时 INDETERMINATE；补全 SDK 形状；所有可执行 fence 都扫描；dataset 默认 WARN | §5.4、§6.3、§13 Q3 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-04 | 2026-11-16 | core-ai（AI 技术评审） | 渐进式披露“SKILL.md 摘要 + 链接”场景未定义穿透：只解析 inline link、只取命中 heading 的正文，摘要会被误判为完整 workflow；同义词 `result`/`errors`/`quality` 过宽 | 增加 reference-style link；定义“摘要 + 链接”合并/穿透规则；heading 改为规范化完整相等；保留 `UNREFERENCED_REFERENCE` WARN | §5.5–§5.11 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-05 | 2026-11-16 | core-ai（AI 技术评审） | CAS 存量兼容问题：PG `PgDocumentStore` 无条件 replace；存量 revision 为 null；PG JSONB 是 `qualifiedName`，不能照抄 Mongo `qualified_name`；存量重名会导致唯一索引失败 | 迁移回填 `revision=1`，滚动期 null 视为 0；新增 PG 条件更新；Mongo/PG 分别建索引；建索引前审计重复并中止/输出清单 | §7.1、§8.3 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-06 | 2026-11-16 | core-ai（AI 技术评审） | `UpdateSkillRequest.content` 可选，只改 description 会与解析值冲突；改名口径前后矛盾；`./references/x.md` 会被 `isIgnoredResource` 误丢 | content 必须为完整 SKILL.md（或只做 resource 操作）；name 不可变；路径先规范化再过滤；被丢弃的 required link 报错 | §3、§5.1、§8.2、§13 Q13 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-07 | 2026-11-16 | core-ai（AI 技术评审） | repo/marketplace 批量门禁缺少响应字段与部分失败状态；全部失败仍可能创建 0 skill 的 repo | 增加 installed/skipped/quality_reports；至少一个成功 200，全部失败 422 且不创建 0-skill repo | §8.1、§9.1 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-08 | 2026-11-16 | core-ai（AI 技术评审） | eval 占位符按任意子串拦截 `example`/`...` 会误杀合法用例 | 改为整个值等于占位符/`^<[^>]+>$`/`^\.{3,}$`；模糊变体交给语义检查 | §5.10.2 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-09 | 2026-11-16 | core-ai（AI 技术评审） | 文档版本/评审记录未同步；§6.6 残留草稿批注 | 版本更新为 v0.3；回填本节；清理 §6.6 | §0 头部、§6.6、§14.2、§14.3 | IMPLEMENTED_IN_DOC |

**AI 评审未决项：** Q1–Q13 仍需用户确认；R-AI-SQ-01 的 SKILL budget profile 需要实现时改共享 kernel 的 `validateBudget`；R-AI-SQ-03 的 `SkillCapabilityCatalog` parity test 是 P1 必测项。AI 评审不授权实施。

#### 第二轮复评（v0.3 → v0.4）

评审日期：2026-11-16。评审模型：`gemini-4-hydrogen`。评审范围：v0.3 全文与工作区实际源码（`QualityBudget`、`QualityPolicyService`、`PublishQualityGateService`、`SkillWebServiceImpl`、`SkillResourceWriter`、`tree.py`、`BuiltinTools`）。总体结论：**有条件通过**；R-AI-SQ-02/05/08/09 复核关闭，R-AI-SQ-01/03/04/06/07 存在残余缺陷。以下为 AI 技术评审意见与文档处理，**不是用户批准，不构成实施授权**。

| Review ID | 日期 | Reviewer | 意见摘要 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| R-AI-SQ-10 | 2026-11-16 | core-ai（AI 技术评审） | 预算基线写错且单位混用：源码实际是 8 calls / 120 questions / 20 per call / 48k **字符**，文中写成 4 calls / 8 questions 与 48k tokens；4 组合规输入可超 50k 字符；`PublishQualityGateService` 逐条执行规则，无法合并不同 enforcement；`QualityBudget` 计数非线程安全 | 删除 token 表述，改为字符与 `SubjectBudgetProfile`（SKILL：4 calls / 16 questions / 64k 字符 / 单组 20k）；新增 `SkillDecisionBatchCoordinator` 预执行并缓存 4 组答案；首版串行，不做并行批次 | §6.4、§10.2、§11.1 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-11 | 2026-11-16 | core-ai（AI 技术评审） | 资源合并时序未定义：`SkillResourceWriter.merge/toResources/enforceBudget` 在构建期就写 `blobStore.put` 并清空 `content`；先 merge 再门禁会在 422 前产生副作用，且大文本资源对门禁不可读；`createMarketplaceRepo` 返回单数 view，承载不了逐 skill 结果；RPC POJO 需显式写响应头 | §8.2 明确门禁前纯内存 `Map<path, bytes>`、keep 资源按 storagePath 只读 hydrate、仅 PASSED 后写 blob；§8.3 增加 CAS 失败允许不可见 content-addressed orphan + 24h GC 契约；§9.1 增加 `createMarketplaceRepo` 载体与 `WebContext.response()` header 契约 | §8.2、§8.3、§9.1、§11.2 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-12 | 2026-11-16 | core-ai（AI 技术评审） | SDK 静态检测漏属性链/中间别名/`-`/`_` 归一化，会假 PASS；`s.tool(name)` 按整个 catalog 任意 kind 查找，固定要求 `tool:name` 会误杀 MCP/API/agent；`BuiltinTools.all/fromSet` 依赖运行期 provider，不能作为 catalog 来源 | §5.4.2 增加属性链、混合链、中间节点别名与归一化；`s.tool` 未解析时 INDETERMINATE；`SkillCapabilityCatalog` 改为纯静态 descriptor 表 + parity test（§5.4.1、§6.3） | §5.4.1、§5.4.2、§6.3、§11.2 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-13 | 2026-11-16 | core-ai（AI 技术评审） | 渐进式披露的 heading 匹配与穿透条件不可测：未定义前导编号/括号处理；依赖“详见/完整流程”自然语言猜测；超 8k 只报泛化超限；SQ9 的 WARN/INDETERMINATE 归属不清 | §5.11 增加版本化 heading 规范化（去编号、折叠空白；括号与 `&`/`/` 只按同义词表精确匹配）；穿透改为“正文 <300 字符且唯一 packaged `.md` 链接”的确定性条件；超限 issue 带目标路径与字符数；§5.9 明确 `CODE WARN QA_EVAL_REFERENCE_ONLY` 不聚合为 INDETERMINATE | §5.9、§5.11、§11.2 | IMPLEMENTED_IN_DOC |
| R-AI-SQ-14 | 2026-11-16 | core-ai（AI 技术评审） | 共享 kernel 的 P1 重构范围过大：`PublishQualityGateService` 强耦合 Agent 实体与 `agent_quality_*`；§14.5 的 trusted seed 清单未落到具体迁移类，启动失败策略未定 | §7.1 与 Q9 记录“先共享无状态组件 + 独立 `skill_quality_*`”的实施风险建议；trusted seed 仍待实施前核对迁移类并补清单（未在本次宣称已核实） | §7.1、§13 Q9、§14.5 | PARTIALLY_ADDRESSED |

**第二轮未决项：** Q1–Q13 仍待用户确认；R-AI-SQ-14 的 trusted seed 具体迁移类与 fail-fast/跳过策略需在 P1 前核对源码后回填；R-AI-SQ-10 的 batch coordinator 是本稿新增设计提案，尚未实现或校准。AI 评审不授权实施。

### 14.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 | 用户确认 |
|---|---|---|---|---|---|
| v0.1 | 2026-11-16 | core-ai | 初稿：原始需求、源码事实、10 项规则、渐进式披露、权限声明、eval 7 维、数据模型、API、安全、实施与 ADR | 给用户完整 Review 对象；参考 Agent quality gate 设计 | 待 Review |
| v0.2（评审稿） | 2026-11-16 | core-ai | 送 `gemini-4-hydrogen` 评审的完整稿 | AI 技术评审输入 | 待 Review |
| v0.3 | 2026-11-16 | core-ai | 按 AI 评审修订：SKILL 独立 decision budget 与 4 个 call group、skill_dir 必填、SkillCapabilityCatalog 与 SDK 形状补全、渐进式披露摘要+链接穿透、PG/Mongo CAS 与 revision 回填、update content/name/path 语义、repo/marketplace 响应、eval 占位符精确匹配、文档状态同步 | 处理 R-AI-SQ-01..09；不改变用户原始需求 | 待 Review |
| v0.4 | 2026-11-16 | core-ai | 第二轮评审修订：更正预算基线为 8 calls/120 questions/48k 字符并区分字符与 token、新增 SKILL budget profile 与 batch coordinator（首版串行）、门禁前纯内存资源物化与 blob 写入时序、CAS 失败 orphan/GC 契约、SDK 属性链/别名与 s.tool 语义、静态 descriptor catalog、heading 规范化与确定性穿透、SQ9 WARN 归属、createMarketplaceRepo 载体与 RPC header 契约、Q9 实施风险建议 | 处理 R-AI-SQ-10..14；不改变用户原始需求；不构成用户批准 | 待 Review |
| v0.5（实施记录） | 2026-11-19 | core-ai | 新增管理员豁免白名单：随 skill policy revision 固化的 `exemptions` 列表、Skill Quality 页面编辑、命中后 gate 短路、report/receipt 记录 `exempted` + `exemption_reason` | 用户要求豁免已知违规但不能修改的外部 skill | 用户请求（2026-11-19） |
| v0.6（实施记录） | 2026-11-19 | core-ai | 白名单从文本框改为独立列表页：Agent/Skill 条目统一展示、类型感知的 subject 选择器、`/api/admin/quality-exemptions` 列表/搜索/新增/移除 API；存储仍为 policy revision 内的 `exemptions`，每次变更激活新 revision | 用户反馈文本框式白名单不可用（“太反人类”） | 用户请求（2026-11-19） |

### 14.4 设计自查记录

本节只记录文档自查，不作为用户批准。

- 对照用户原始需求逐项覆盖 SQ1–SQ10；每条包含类型、默认 enforcement、检查、示例、建议与理由。
- 覆盖渐进式披露（E4）、所有写入路径（E1）、typed report（E2）、规则扩展（E3）、无副作用（N1）、不确定不放行（N2）。
- v0.3 根据 `gemini-4-hydrogen` AI 技术评审修订：SKILL 独立 decision budget 与 call group、skill_dir 必填、SkillCapabilityCatalog、渐进式披露穿透、CAS 存量兼容、update content/name/path 语义、repo/marketplace 响应、eval 占位符精确匹配。
- v0.4 根据第二轮 `gemini-4-hydrogen` AI 技术评审修订：更正预算基线（8 calls / 120 questions / 48k 字符，非 4/8 与 token）、新增 SKILL budget profile 与串行 batch coordinator、门禁前纯内存资源物化、CAS 失败 orphan/GC 边界、SDK 属性链与 `s.tool` 语义、静态 descriptor catalog、heading 规范化与确定性穿透、SQ9 WARN 归属、`createMarketplaceRepo` 载体与 RPC header 契约。
- 复核代码引用文件存在、行号与当前工作区一致；JSON 示例可解析；Markdown 章节/代码围栏完整。
- 未进行业务实现、模型校准、dev 部署；性能/阈值/eval runner 仍为提案。
- 本次只修改设计文档，未触碰业务代码，未创建 commit。
- 明确未写成“用户批准”；AI 评审单独记录。

### 14.5 Trusted seed / 例外清单

| 路径 | 是否走模型门禁 | 控制 |
|---|---|---|
| 启动 seed / migration 直接写 skills | 否 | 代码审查 + CI fixture + `trusted-seed` 审计；不向用户开放 bypass |
| 已有 skill（门禁上线前） | 否（下次写入才检查） | 保持可读/可用；dry-run 报告；不自动下架 |
| `SkillRepoSyncJob` | 是 | 逐 skill 门禁；失败保留旧内容；记录报告/告警 |
| Marketplace install | 是 | 逐 skill 门禁；失败跳过/保留旧内容；返回 installed/skipped |
| 管理员 emergency suspend | 仅 DECISION 规则 | actor/原因/范围/到期时间；确定性规则不可 suspend |
| 任何新增直接写 skills / 设 revision 的路径 | 必须先登记 | 未登记视为绕过门禁 |

**待补（P1 前置）：** 本表的 “启动 seed / migration 直接写 skills” 还没有落到具体类名与调用点，也未确定代码检查失败时是 fail-fast 还是跳过单条。实现前必须核对实际启动/迁移代码并回填清单与策略；在此之前不能声称 trusted seed 路径已被审计覆盖。

### 14.6 实施验证记录

实施日期：2026-11-16。本轮实现的是确定性门禁与写入骨架；语义决策规则尚未实现。以下为工作区实际验证结果，**不是用户批准，也不是 dev/prod 部署记录**。

**已实现（代码）**

- `SkillPublishCandidate` / `SkillPublishCandidateBuilder` / `SkillDocumentParser`：frontmatter、heading、link、fence 解析；资源路径规范化后才过滤 ignored；candidate 只构建一次并投影实体。
- 确定性规则：`SKILL.NAME_IDENTITY`、`SKILL.DESCRIPTION_TRIGGER_SCOPE`（Use when + scope 结构）、`SKILL.METADATA_REQUIRED`、`SKILL.PACKAGE_HYGIENE`、`SKILL.PROGRESSIVE_DISCLOSURE`（含编号 heading 规范化、摘要+唯一链接穿透、断链/外链检查）、`SKILL.EVAL_DATASET`（7 维、断言、占位符、fixtures）、`SKILL.PERMISSION_DECLARATION`（typed allowed-tools + Python/Shell 静态扫描 + 动态 INDETERMINATE）。
- 写入路径：`SkillPublishService` 统一处理 upload / editor update / repo register / repo sync / marketplace install；门禁通过后才写对象存储并做 revision CAS；失败不写 skill、不写 receipt。
- `SkillDefinition` 增加 `directoryName`、`revision`、`qualityReceipt`；Mongo/PG 均增加 `insertIfAbsent` 与 `replaceIfRevision`；PG 用 JSONB `revision` 条件更新。
- upload 增加 `skill_dir` 必填（缺失 400 `SKILL_DIRECTORY_REQUIRED`）；CLI `skill push` 与 Web 文件夹上传同步发送；编辑保存通过 `SKILL_METADATA_MISMATCH` / `SKILL_NAME_IMMUTABLE` 防止字段漂移。
- 错误协议：`SKILL_QUALITY_REJECTED` / `SKILL_QUALITY_UNAVAILABLE` / `SKILL_QUALITY_STALE` 携带 `quality_report`；成功响应通过 `X-Skill-Quality-*` header 暴露 run id / gate result / warn count。

**尚未实现（仍为设计提案）**

- 语义决策规则（description 具体性、name-content 主题、workflow/decision/output/error/QA 语义、eval 用例质量）尚未接入 `QualityDecisionGateway`；当前只做结构与覆盖检查，没有 shadow 语义报告。
- 规则/策略管理 UI、`skill check` 预检查 API、报告持久化与查询 API、publication record 追加集合。
- repo/marketplace 的逐 skill 部分失败响应与 `createMarketplaceRepo` 批量载体；当前单 skill 失败会中止该次 repo 注册。
- `qualified_name` 唯一索引与存量 `revision` 迁移回填；当前依赖 CAS + 应用层重复检查，legacy null 按 0 处理。
- blob orphan GC、`SubjectBudgetProfile` 的共享 kernel 改造（本轮无模型调用，故未触发预算）。

**验证**

- `:core-ai-server:test --tests "ai.core.server.skill.*"`：通过（skill service、repo sync、quality gate、parser、permission analyzer、publish service）。
- `:core-ai-cli:compileJava`：通过；`core-ai-frontend npm run build`（tsc + vite）：通过。
- `:core-ai-server:check`：通过（checkstyle、PMD、SpotBugs、test）。
- dev E2E（2026-11-16，`2.0.246`，镜像 digest `sha256:f39cb352…c8dbed`）：通过。已验证 valid upload、missing eval、undeclared SDK usage、broken reference、missing `skill_dir`、directory mismatch、valid update（PG `revision=2` + receipt）、failed update 不改变 revision 与 receipt、成功响应 header。记录见 `docs/validation/2026-11-16-dev-skill-quality-gate.md`。
- E2E 发现并修复：`PgSkillStore.replaceIfRevision` 的 SQL 直接写了 JSON key 的单引号，core-ng 拒绝单引号导致编辑上传 500；已改为绑定参数 `data ->> ?`，在 `2.0.246` 复测通过。
- 仍未做真实模型校准、并发压力、旧 skill dry-run、repo/marketplace 端到端。

### 14.7 规则管理与自定义 DECISION 实现（2026-11-18）

本轮交付把 §4.2/§4.3/§7.1 的规则管理落到 skill gate，并保持既有确定性规则的行为不变。

**已实现**

- `SkillQualityRule` / `SkillQualityRuleRevision` / `SkillQualityPolicyRevision` / `SkillQualityPolicyHead` + `SkillQualityStore`（Mongo 与 PostgreSQL 两种模式，迁移 `V44__skill_quality_rules.sql`）。
- 启动时幂等 seed 10 条内置规则与初始 skill policy；内置规则仍由代码实现，只有 enforcement 可通过策略调整。
- `SkillQualityRuleRegistry`：内置规则的 evaluator key 即 rule key；自定义规则统一使用 `SKILL.DECISION.CUSTOM`，输入选择器限定为 `DESCRIPTION` / `SKILL_CONTENT` / `METADATA` / `ALLOWED_TOOLS` / `RESOURCE_MANIFEST` / `EVAL_DATASET`。
- `SkillDecisionQualityRule` 复用 `QualityDecisionGateway` 的严格响应、路由指纹、阈值与预算；模型不可用、预算耗尽、协议错误一律 ERROR，按 BLOCK 聚合为 `UNAVAILABLE`，不伪装通过。
- `SkillQualityPolicyService`：规则生命周期、不可变 revision、单文档原子激活、`BLOCK/WARN/SHADOW` 绑定、变更原因与测试后阻断校验。
- 管理 API：`/api/admin/skill-quality/rule-templates`、`rules`（GET/POST）、`rules/:id`（PUT）、`rules/:id/test`、`rules/:id/retire`、`policies`、`policies/activate`；沿用 human admin 双重校验。
- 规则试测：从既有 skill 重建 `SkillPublishCandidate`（可覆盖 SKILL.md），只读、不写 blob/skill。
- Skill Quality 管理页与 Agent Quality 共用规则编辑器：新增/编辑 decision rule、试测、应用策略、retire；内置规则只读定义，仅可调整 enforcement。

**仍未实现（保持设计提案）**

- 内置 HYBRID 语义规则（SQ5–SQ9、description/name 语义、eval 用例质量）及其 batch coordinator。
- skill quality run/report 持久化与查询、publication record；当前报告仍只随写入响应返回。
- 每个内置规则可编辑 typed parameters（本轮内置规则参数固定）。
- per-namespace 策略、shadow 数据校准、真实误阻断率评估。

## 15. 参考资料

- [S1] [Anthropic — Agent Skills overview](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)：三层渐进式披露、frontmatter name/description、按需加载 resource。
- [S2] [Anthropic — Skill authoring best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices)：SKILL.md 保持精简、reference 一层可达、避免深层嵌套。
- [S3] [Claude — Create custom skills](https://claude.com/docs/skills/how-to)：name 必须匹配目录、description 说明何时使用、references/assets/scripts 组织。
- [S4] [OpenAI — Testing Agent Skills Systematically with Evals](https://developers.openai.com/blog/eval-skills)：prompt → trace/artifacts → 确定性 checks → rubric；小规模 prompt set；negative trigger。
- [S5] [Kubernetes — Validating Admission Policy](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/)：服务端 admission、规则/参数分离、failure policy。
- [S6] [OPA Gatekeeper — Introduction](https://open-policy-agent.github.io/gatekeeper/website/docs/)：policy-as-code、决策与执行分离、audit/dry-run。
- [S7] [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)：多项问题、JSON Pointer、安全最小化。
- [S8] [OWASP — LLM Prompt Injection Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)：不可信输入、judge 零权限、输出验证、对抗测试。
- [S9] [Semantic Versioning 2.0.0](https://semver.org/)：版本语义与比较。
- [S10] [CommonMark Spec](https://spec.commonmark.org/)：heading/link/code fence AST。
- [S11] [core-ai-session SDK 源码](https://github.com/chancetop-com/core-ai/blob/master/sdk/core-ai-session/core_ai_session/tree.py)：`s.mcp` / `s.api` / `s.llm_call` / `s.agent` / `s.tool` / `s.dataset` 调用形状。
- [S12] [Agent 发布质量门禁设计](./design-agent-publish-quality-gate.md)：状态机、版本化规则、decision adapter、typed report 与审计模式。
- [S13] [Microsoft Learn — Deploy and use Microsoft-Decision-1](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-microsoft-decision)：typed questions、无自由文本 rationale、阈值与标注验证。
- [S14] [OpenAI — Build skills](https://developers.openai.com/codex/build-skills)：name/description、workflow、reference/scripts 组织与边界。

