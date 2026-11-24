# Core AI Agent 引用完整性与质量状态 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | DRAFT；待用户 Review；未批准实施 |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-24 |
| 需求提出者 / Reviewer | 用户；尚未批准方案或实施 |
| 作者 | core-ai |
| 产品入口 | Agents 列表、Agent 编辑器、Notifications |
| 目标 | 当 Skill 被删除、Service API resync 移除 API tool，或 Agent 最近一次质量检查未通过时，在 Agent 列表持续展示需要处理的信号，并把引用失效事件通知给 Agent 所有者；用户移除失效引用（发布版本还需要重新发布）后信号消失 |
| 本次交付 | 仅技术设计文档；不修改业务实现、不创建迁移、不部署 |
| 事实与提案 | 附录 B 为设计时源码事实；正文为目标设计。所有新增 API、表、字段、默认值均为提案，不代表当前已实现 |

## 结论摘要

1. **这是“软引用完整性”问题，不是给 Agent 加一个状态字段的问题。** Skill 是独立表；API tool 是 Service API payload 里的 operation，并不是数据库行；Agent 的引用是嵌套在文档里的 JSON 数组。数据库没有外键可用，删除资源也不会自动触碰 Agent。
2. **Badge 是当前事实的派生结果，不是持久化布尔值。** 列表读取时用“当前 Agent 定义/已发布快照 + 当前 Skill 表 + 当前 API catalog”计算；移除引用后立即消失。资源恢复后也会自动消失——这与“直到用户删除引用”的字面要求略有偏差，但能避免“资源已恢复、感叹号还在”的假警报。
3. **通知是边沿事件，Badge 是状态。** 删除/resync 时检测并写入 `agent_reference_issues` 台账；通知按 Agent 聚合并按 issue episode 去重；Badge 的判定不读取台账，因此台账延迟、补偿任务失败或重复执行都不会改变列表显示。
4. **必须同时检查 Draft 和 PublishedConfig。** `publishedConfig` 是其他用户实际执行的契约，`tools/skillIds` 是作者下一次发布的契约。只在 draft 修好、没有重新发布，已发布版本仍然是坏的；反过来，只在 published 里坏、draft 已删掉，修复动作就是重新发布。
5. **API resync 的检测不能依赖一次 reload 的 diff。** 每个 Pod 的上一份快照不同，Pod 重启后也会丢；正确做法是 reload 成功后，用“当前 catalog vs 当前 Agent 引用”做一次带范围的当前事实对账。reload 只是触发器，不是事实来源。
6. **catalog / Skill 表加载失败不得产生“引用不存在”的结论。** 失败时标记 `UNVERIFIED`，不显示感叹号；否则一次数据库抖动或 payload 解析故障会造成全量误报，并可能触发通知风暴。
7. **质量感叹号 = 最近一次完成的、作者侧质量运行的结论。** 首版取 `PRECHECK / PUBLISH / FORK`，排除 `WORKFLOW`（工作流消费者触发的快照校验）与未完成/中止的运行。编辑内容不会清除失败，只有后续一次 `PASSED` 才清除；若检查后内容已变更，Badge 保留但在 tooltip/编辑器标注“上次检查失败，内容已变更，需要重检”。
8. **首版范围 = Skill + Service API tool（app/service/operation）。** MCP server/tool、subagent、LLM_CALL、dataset、managed prompt 在质量门禁里已有检查，但运行时健康语义不同（MCP tool 列表未持久化、subagent 需要发布版本等），首版不混入。
9. **不自动修改 Agent、不阻止删除、不自动下架。** 删除是合法运维动作，API resync 更是系统驱动、无法阻止；自动从 Agent 里删除引用会静默改变用户配置和已发布行为，并绕过 `definitionRevision` 的并发语义。平台只做检测、提示，让 owner 明确修复。
10. **首版存储落在 PostgreSQL。** 三个环境当前都是 `SYS_STORE_MODE=pg`；新增 issue 台账与通知去重键不增加 Mongo 写入。即使台账不可用，派生 Badge 仍然可以工作，只是没有通知与审计。

## 阅读指南与目录

- 产品 / Reviewer：先读 §1、§2、§3.3–§3.5、§6、§11，确认“什么算需要处理”、Badge 何时出现/消失、Draft/Published 的修复路径，以及需要拍板的决策。
- 后端：读 §3–§5、§7、§8，重点关注软引用语义、反向查找、去重、失败偏置、模块绑定顺序和源码改动范围。
- 前端 / 测试：读 §5.1、§6、§10，核对 API 字段、列表/编辑器/通知交互与验收矩阵。
- 原始需求、源码核对、Review 与修改历史保留在本文件附录，不另拆零散记录。

| 章节 | 内容 |
|---|---|
| [1. 背景、需求、目标与非目标](#1-背景需求目标与非目标) | 从第一性原理拆解“Agent 需要关注”到底是什么 |
| [2. 对用户方案的判断与总体方案](#2-对用户方案的判断与总体方案) | 哪些判断成立、哪些需要修正；端到端流程 |
| [3. 核心模型与不变量](#3-核心模型与不变量) | 引用、可见性、质量状态、派生与事件的边界 |
| [4. 检测、通知与对账](#4-检测通知与对账) | 触发源、反向查找、去重、多 Pod、回填、失败语义 |
| [5. 数据模型与 API](#5-数据模型与-api) | View、表、索引、Store 接口、兼容与示例 |
| [6. 前端与交互](#6-前端与交互) | 列表 Badge、编辑器修复、质量提示、通知中心 |
| [7. 服务架构与关键流程](#7-服务架构与关键流程) | 组件依赖、列表快照、删除/resync/保存流程、模块绑定 |
| [8. 可观测性、性能与容量](#8-可观测性性能与容量) | 指标、日志、延迟预算、批量与失败矩阵 |
| [9. 安全、权限与隐私](#9-安全权限与隐私) | 谁能看到什么、通知收件人、数据最小化 |
| [10. 验收、实施计划与风险](#10-验收实施计划与风险) | 验收矩阵、分阶段、测试策略、风险 |
| [11. 关键决策与未决事项](#11-关键决策与未决事项) | ADR 摘要、决策台账、需要用户拍板的问题 |
| [附录 A：用户原始需求与文档治理](#附录-a用户原始需求与文档治理) | 原文与维护规则 |
| [附录 B：设计时源码核对](#附录-b设计时源码核对) | 文件、行号、事实与约束 |
| [附录 C：Review 与修改历史](#附录-c-review-与修改历史) | Review 记录与版本变化 |

## 1. 背景、需求、目标与非目标

### 1.1 第一性原理：把“Agent 需要关注”拆成四个信号

用户要的不是“Agent 列表多一个图标”，而是：**当 Agent 的配置已经不能按作者预期执行时，用户应该在打开列表的第一眼就知道，并且知道该修什么。** 要设计正确，必须先回答“哪些情况算不能按预期执行”，以及每种情况的事实来源、变化频率和可验证性。

把“Agent 可用性”拆成四个信号：

| 信号 | 具体内容 | 事实来源 | 变化频率 | 是否适合常驻 Badge |
|---|---|---|---|---|
| R1 引用完整性 | `skill_ids` 指向的 Skill 是否存在；`tools` 里的 API operation/app/service 是否仍在当前 catalog | Skill 表、Service API 解析后的 catalog、Agent 文档 | 低（删除/resync 时变化） | **是**：确定性、环境级、可批量计算 |
| Q1 质量判定 | 最近一次质量运行（precheck/publish/fork）的 `gate_result` 是否通过 | `agent_quality_runs` | 低（用户触发检查时变化） | **是**：已持久化、可审计、有明确结论 |
| R2 运行时健康 | MCP server 是否连得上、API 是否 401、模型是否可用、沙箱配额是否足够 | 运行时连接、外部服务 | 高、瞬时 | 否：每次调用本来就会给出具体错误；常驻 Badge 会变成噪声 |
| A1 访问健康 | 当前调用者是否有权限、配额是否足够 | 调用者身份、RBAC、配额 | 按调用者变化 | 否：上下文相关，且执行时会 fail-closed |

**只有 R1 与 Q1 值得做成列表 Badge。** R2/A1 是运行时/调用者上下文问题，做成“Agent 健康状态”会把短期故障误写成长期属性；而 R1/Q1 都是“关于这份配置已经发生过的事实”，适合作为持续提醒。

进一步拆解 R1：

- 一个引用由四部分组成：**持有者**（Agent）、**引用种类**（Skill / API tool）、**目标身份**（Skill id；`app:service:operation`）、**所在配置**（draft 或 published snapshot）。
- 引用有效，当且仅当目标在**当前环境**中存在，并且在请求的粒度上可用。
- 引用不是 Agent 的属性，而是 Agent 配置与目标 catalog 之间的**关系**。目标可以独立于 Agent 被删除或 resync 替换，Agent 文档完全不变。
- 因此它天然是“软引用”：没有数据库外键，也不能靠删除级联保证。

为什么不能像关系库那样用外键 / RESTRICT / CASCADE？

1. **跨存储、跨形态。** Skill 在 `app.skills`；API tool 不是行，而是 `service_api.payload` 解析出来的 operation；Agent 引用是 JSON 数组。没有一张表可以同时约束三者。
2. **删除可能是系统驱动的。** Skill 删除是用户动作，可以阻止；但 Service API resync 是上游定义变化后由 `ToolRegistrySyncJob` 自动 reload 的，平台无法“拒绝上游删除一个 operation”。
3. **自动级联会静默改用户配置。** 从 Agent 里删掉引用会改变执行行为、bump `definitionRevision`、让并发编辑器收到冲突，而且对已发布配置还需要一次隐式重新发布；这是不可接受的副作用。
4. **软删除也不够。** 保留 Skill tombstone 可以让引用“仍能解析”，但 API operation 来自 payload，无法为每个 operation 建 tombstone；已发布 Agent 仍然无法执行那个 operation。

所以正确模型只能是：**允许目标消失，检测关系失效，把事实呈现给能修复的人，由用户显式删除引用或重新发布。** 这与数据库的“引用完整性”目标一致，只是把执行点从写入时外键改成了读取时对账。

### 1.2 需求追踪

| ID | 用户目标 / 补充要求 | 设计落点 |
|---|---|---|
| U1 | API tool 被 resync 并删除后，检测所有引用它的 Agent | §4.1、§4.2：reload 成功后做“当前 catalog vs 当前 Agent 引用”的带范围对账；不依赖单 Pod diff |
| U2 | Skill 被删除后，检测所有引用它的 Agent | §4.1、§4.2：删除成功后按 skill id 反向查询 draft + published |
| U3 | 提示用户这个 Agent 的 Skill / tool 引用不存在了 | §4.3、§6.4：持久化 in-app notification，按 Agent 聚合、按 episode 去重；SSE 只是在线提示 |
| U4 | Agent 列表加 `!`，直到用户删除失效引用 | §3.3、§5.1、§6.1：Badge 派生；draft 移除即消失，published 需要重新发布；资源恢复则自动消失（见 §2.1） |
| U5 | Agent 触发了 quality 检测但没通过，也显示 `!` | §3.4、§5.1、§6.3：取最近一次完成的 PRECHECK/PUBLISH/FORK 运行；失败保留到后续 PASS |
| U6 | 参考 `design-persistent-workspace.md` 的文档结构 | 全文；结论摘要、需求追踪、不变量、ADR、附录 A/B/C |
| U7 | 从第一性原理整理需求和设计取舍 | §1.1、§2.1、§3.5、§11 |
| C1 | 不能因为 catalog/DB 抖动产生假 `!` 和通知风暴 | §3.2、§4.5、§8.4：`UNVERIFIED` 不触发 Badge；首部署静默回填 |
| C2 | 多 Pod 下不能重复通知 | §4.3：active issue 唯一键 + notification `dedupe_key` |
| C3 | 列表不能因为健康检查明显变慢 | §8.2：仅 full view 计算；Skill/quality 批量查询；API 纯内存 |
| C4 | 不破坏现有 API/客户端 | §5.1、§5.4：新增可选嵌套字段；旧客户端忽略 |
| D1 | 只做 tech design，不实现 | 附录 C 记录未授权实施 |

### 1.3 目标与不变量

以下不变量是后续实现和验收的硬约束：

1. **Badge 真实性不变量**：Badge 显示时，必须存在至少一个**已确认**的坏引用，或一次**已完成**的质量失败；`UNVERIFIED/UNKNOWN` 不得触发感叹号。
2. **派生不变量**：Badge 只由当前 Agent 配置、当前 catalog、当前 quality run 派生；不从 `agent_reference_issues` 或通知状态读取。台账故障不能让 Badge 出现或消失。
3. **通知一次不变量**：同一 `(agent, ref, episode)` 最多一条通知；episode 由 active issue 的生命周期定义，issue resolve 后再次失效才算新 episode。
4. **Draft/Published 完整性不变量**：只要 published config 里仍有坏引用，Agent 就必须被标记，即使 draft 已经删除该引用。
5. **修复由用户完成不变量**：系统不自动删除引用、不自动发布、不自动下架；用户显式保存/发布后才清除。
6. **失败偏置不变量**：无法验证时宁可显示 `UNKNOWN`/不显示 Badge，也不产生假 `ATTENTION`；同样，不能把“加载失败”当成“为空 catalog”。
7. **无静默写入不变量**：健康检查、通知、对账任务都不写 `app.agents`；不 bump `definitionRevision`，不与编辑器 CAS 冲突。
8. **幂等不变量**：删除钩子、reload 钩子、对账任务可以被重复执行，结果一致；重复执行最多产生一次通知。
9. **受众不变量**：draft 引用问题与质量失败只对有编辑能力的用户展示；published 坏引用对所有能看到该 Agent 的用户展示（因为它影响实际执行）。
10. **质量证据不变量**：Badge 只基于 `COMPLETED` 的运行；`STARTED/ABORTED/FAILED` 不构成“没通过”的结论。

### 1.4 首版边界与非目标

| 首版纳入 | 首版不纳入 |
|---|---|
| Skill 删除后的 draft + published 引用检测 | 自动删除 Agent 里的失效引用 |
| API app/service/operation 在 resync/reload 后的检测 | 阻止 Skill 删除 / 阻止 Service API resync |
| Agent 列表 `!` + tooltip + 计数 | 自动下架、自动取消发布、自动通知所有历史使用者 |
| Agent 编辑器的失效引用横幅与逐个移除 | 运行时 MCP 连接探测、API 连通性探测 |
| 质量最近一次 PRECHECK/PUBLISH/FORK 失败 Badge | WORKFLOW 触发的快照校验 Badge（见 §3.4） |
| In-app notification + 按 Agent 聚合 + 去重 | 邮件 / Slack / 企业微信等外部通道 |
| 首部署静默回填 + 周期对账安全网 | 历史趋势看板、按资源维度的分析报表 |
| PG 台账与通知去重键 | Mongo 双实现（当前环境已 `SYS_STORE_MODE=pg`） |
| 删除 Skill 前的影响预览（P2 可选） | MCP server/tool、subagent、LLM_CALL、dataset、managed prompt 的运行时健康 |
| Agent 列表 “Needs attention” 过滤（P2 可选） | 多用户协作的通知偏好与订阅管理 |

**理由：**首版必须把“确定性的配置坏掉”与“瞬时的运行时坏掉”分开。把 MCP 连接、模型可用性、权限都塞进 Badge，会让用户很快学会忽略它；而 Skill/API 引用和质量门禁是已经发生、可审计、可操作的事实。MCP/subagent 等可以在同一套骨架上扩展，但不应在首版混入不同的可用性语义。

## 2. 对用户方案的判断与总体方案

### 2.1 用户方案的逐条判断

用户提出的方案是：“API tool 被 resync 并删除后 / Skill 被删除时，检测所有引用它们的 Agent，提示用户这个 Agent 的 Skill 或 tool 引用不存在了，并在 Agent 列表加 `!`，直到用户删除失效引用；另外 Agent 触发了 quality 检测但没通过也显示感叹号。”逐条分析：

| 用户判断 | 结论 | 分析 | 设计落点 |
|---|---|---|---|
| “删除后检测所有引用 Agent” | **方向正确，但不能只靠事件** | Skill 删除可以在删除成功后反向查询；API resync 是后台 reload，单 Pod 的 diff 不可靠（Pod 重启/多 Pod 上一份快照不同）。正确做法是“事件触发 + 当前事实对账 + 周期安全网”。 | §4.1、§4.2 |
| “提示用户” | **正确，但要用持久化通知** | SSE 只覆盖当前在线且连在同一 Pod 的用户；`NotificationEventPublisher` 明确是 in-process。必须落 `notifications` 表，SSE 只是即时提示。 | §4.3、§6.4 |
| “列表加 `!`” | **正确，但 Badge 应是派生值** | 持久化一个 `has_problem` 布尔值会和真实状态漂移：资源恢复、Agent 被其他编辑器修改、后台任务失败都会留下错误标记；而且写 Agent 文档会 bump `definitionRevision`，破坏并发编辑。 | §3.5、§5.1 |
| “直到用户删除引用” | **大部分正确，但资源恢复应自动清除** | Skill 用 UUID，删除后不会以同一 id 回来；API operation 可能在一次错误 resync 后恢复。若 Badge 是派生值，恢复后自动消失，避免假警报。若坚持“只有删除才消失”，需要把 Badge 变成台账状态，代价是漂移与补偿。 | §11.3 Q1 |
| “quality 没通过也显示 `!`” | **正确，但要定义“哪一次检测”** | 质量运行绑定到一次检查时的内容；编辑后旧结果不一定适用。建议取最近一次完成的作者侧运行，失败保留到后续 PASS；编辑不清除，tooltip 标注“内容已变更需重检”。 | §3.4、§11 Q3 |
| “参考 persistent-workspace 文档” | **采纳** | 采用同样的 DRAFT 元数据、结论摘要、需求追踪、不变量、ADR、源码核对、Review 历史结构。 | 全文 |
| “从第一性原理整理” | **采纳** | 先把“可用性”拆成 R1/Q1/R2/A1，再决定哪些值得 Badge；把“引用”定义为跨存储关系，解释为什么不能 FK/级联。 | §1.1 |

**最需要修正的一个隐含假设：**“检测所有引用 Agent”等于“每次删除都扫全表”。这在小规模可用，但会把删除请求变成 O(N)。首版应采用：

- Skill 删除：按 skill id 反向查询（PG JSONB 包含 + GIN 索引），只返回引用者；
- API resync：只加载“带 API 类型引用”的 Agent，在内存中对照新 catalog；不做“每个被删 operation 查一次”；
- 全量扫描只作为周期对账安全网，有界批量、可中断、可重复。

**关于“直到删除”的取舍：** 本文推荐 **派生 Badge**，因此：

- 正常路径：用户从 draft 删除引用并保存 → Badge 立即消失；published 里还有 → 需要重新发布后才消失。符合用户直觉。
- 边界路径：API operation 被移除后又被恢复 → Badge 自动消失。这不是“用户删除了引用”，但引用已经不再失效；继续显示会误导。
- 通知不会被“恢复”撤回：历史通知保留，但不会因为恢复而变成新的未读；下一轮对账把 issue 标记为 `RESTORED`。

### 2.2 端到端用户流程

**A. Skill 被删除**

1. 用户/管理员在 Skills 页面删除一个 Skill。
2. `SkillService.delete` 删除记录与 blob；随后同步（或短异步）调用 `AgentHealthService.onSkillDeleted(skillId, skillName, actorUserId)`。
3. 健康服务按 skill id 反向查询所有 draft/published 引用该 id 的 Agent；对每个 Agent upsert 一条 active issue（`refType=SKILL`, `refId=skillId`, `refName=删除前的名字`, `sources=[DRAFT|PUBLISHED]`）。
4. 对新建/未通知的 active issue，按 Agent 聚合创建一条 in-app notification 给 Agent owner；`dedupeKey` 由 issue id 集合决定；SSE 在线推送只是附加。
5. 用户回到 Agents 列表，看到该 Agent 名称旁的 `!`；tooltip 显示“Skill ‘x’ 已不存在（Draft + Published）”。
6. 用户打开 Agent 编辑器：顶部横幅列出失效 Skill；Skill chip 标红；点击移除后保存。`requireAccessibleSkills` 会阻止仍带失效 Skill 的保存，因此移除是保存的前提。
7. 保存成功后 `onAgentSaved` 重新计算，draft issue resolve；如果 published 也引用它，issue 仍以 `PUBLISHED` 存在，Badge 继续显示，编辑器提示“重新发布以替换已发布版本”。
8. 用户重新发布；`publishedConfig` 不再包含该 Skill；Badge 消失，issue resolve。

**B. Service API resync 删除了 API tool**

1. 管理员更新/删除 `service_api` 记录，或 `updateAllFromSysApi` 从上游拉取新 payload；`configSnapshot` 变化。
2. `ToolRegistrySyncJob` 在下一个 30s tick 检测到变化，`reloadApiTools()` 成功加载新 catalog。
3. reload 成功后触发 `AgentHealthService.onApiCatalogReloaded()`：加载所有带 API 类型引用的 Agent，逐个 ref 对照新 catalog；新建/更新 `refType=API` 的 active issue。
4. 通知与 Badge 流程同 A；tooltip 显示 “API tool ‘app > service > operation’ 在 resync 后不可用（Published）”。
5. 用户在编辑器移除失效 API tool chip 并保存；draft issue resolve；published 仍需重新发布。
6. 如果上游后来恢复了该 operation，下一轮对账（或一次成功 reload）会把 issue 标记 `RESTORED`，Badge 自动消失。

**C. 质量检查未通过**

1. 用户在编辑器点击 Check Quality，或点击 Publish 触发 gate。
2. `PublishQualityGateService` 创建并完成一条 `AgentQualityRun`；结果 `REJECTED`（有 BLOCK 失败）或 `UNAVAILABLE`（必需检查无法完成）。
3. 用户回到列表；`AgentHealthService` 批量读取每个 Agent 最近一次完成的 PRECHECK/PUBLISH/FORK 运行；若 `gate_result != PASSED`，`health.quality.state = FAILED|UNAVAILABLE`，Badge 显示 `!`。
4. 用户打开编辑器，看到质量横幅：run id、gate result、block/incomplete 计数、检查时间；可打开完整报告或重新检查。
5. 用户修改内容后，Badge **不因编辑而消失**；编辑器标注“上次检查失败，内容已变更，需要重检”。下一次 Check Quality/Publish 通过后，新的 PASSED run 成为最近一次，Badge 消失。

**D. 列表读取**

1. 前端调用 `GET /api/agents?my=true&limit=20`。
2. 服务端分页查询 Agent；只对 full view（非 summary/picker）计算健康。
3. 一次批量查 Skill 名称/存在性；一次取 API catalog 快照（内存）；一次批量取本页 Agent 的最新完成质量运行；一次读当前 policy revision。
4. 组装 `AgentDefinitionView.health`；任何组件失败 → 该维度 `UNVERIFIED/UNKNOWN`，不抛错、不影响列表。
5. 前端在名称旁渲染 `!`；tooltip 汇总原因；点击行进入编辑器。

### 2.3 总体架构

```text
Agents list / Agent editor / Notifications
                 |
        AgentDefinitionService
          |                |
   AgentHealthService  ----+--> AgentQualityStore / QualityPolicyService
          |                |
          |                +--> SkillStore (batch names/existence)
          |                +--> ApiToolCatalogService (in-memory snapshot)
          |                +--> AgentReferenceIssueStore (PG ledger)
          |                +--> NotificationService --> NotificationStore / SSE
          |
   AgentDefinitionStore (reverse queries, reference projection)

Event hooks / jobs:
  SkillService.delete ──────────────► AgentHealthService.onSkillDeleted
  ToolRegistrySyncJob reload ──────► AgentHealthService.onApiCatalogReloaded
  AgentDefinitionService save/pub/del ► onAgentSaved / onAgentDeleted
  AgentHealthReconcileJob ─────────► full sweep + backfill + resolve
```

**与现有架构的关系：**

- `AgentDefinitionService` 仍然是 Agent 视图的唯一组装者；健康信息只是新增的嵌套 view，不改变 CRUD/发布/质量门禁路径。
- `AgentHealthService` 不注入 `AgentDefinitionService`，避免循环依赖；它直接使用 `AgentDefinitionStore`、`SkillStore`、`ApiToolCatalogService`、质量 store 和通知服务。
- `SkillService` 与 `AgentDefinitionService` 通过 setter 接收健康服务/监听器，由后加载的 `AgentHealthModule` 注入；不改变 `ServerApp` 的模块顺序。
- `ToolRegistrySyncJob` 的 reload 结果需要变成可判断的成功/失败；健康对账只在成功时触发。
- `NotificationEventPublisher` 的跨 Pod 局限保持不变；持久化通知是事实来源。

### 2.4 状态机与状态定义

健康状态用两个正交维度描述，避免“引用坏了”和“质量没过”互相覆盖：

**Agent 健康状态 `health.status`：**

```text
OK ──broken ref / quality failed──▶ ATTENTION
 │                                      │
 └──unverifiable──▶ UNKNOWN ◀───────────┘（无法验证，不是失败）
```

| 状态 | 含义 | 是否显示 `!` |
|---|---|---|
| `OK` | 已验证：无坏引用，且（对编辑者）最近质量运行未失败 | 否 |
| `ATTENTION` | 至少一个 confirmed broken ref，或质量最近运行 `REJECTED/UNAVAILABLE` | 是 |
| `UNKNOWN` | 没有 confirmed 坏引用，但至少一个维度无法验证（catalog/Skill store/quality store 不可用） | 否；编辑器可显示“无法验证” |

**引用 issue 生命周期：**

```text
(不存在) ──detect──▶ OPEN(notifiedAt=null) ──notify──▶ OPEN(notifiedAt!=null)
                                                        │
                          ref removed / restored / agent deleted
                                                        ▼
                                                    RESOLVED
                          （再次失效）──▶ 新 issue id / 新 episode
```

| 字段 | 含义 |
|---|---|
| `OPEN` | 当前仍确认失效；Badge 的派生依据不依赖它，但通知/审计依赖它 |
| `notifiedAt` | 该 episode 是否已经成功创建通知；为空时下一轮重试 |
| `RESOLVED` | 引用已从 Agent 删除、资源恢复、或 Agent 被删除；`resolvedAt` + `resolution` |
| `episode` | 一个 active issue 的生命周期；resolve 后再次失效会创建新 issue id，因此可以再次通知 |

**质量状态 `health.quality.state`：**

| 状态 | 含义 | 是否显示 `!` |
|---|---|---|
| `NONE` | 没有完成的 PRECHECK/PUBLISH/FORK 运行 | 否 |
| `PASSED` | 最近一次完成的运行 `gate_result=PASSED`（含 exempted） | 否 |
| `FAILED` | 最近一次完成的运行 `gate_result=REJECTED` | 是 |
| `UNAVAILABLE` | 最近一次完成的运行 `gate_result=UNAVAILABLE`（必需检查未完成） | 是 |
| `UNKNOWN` | 质量存储不可用，无法判断 | 否 |

`quality.policyChanged = run.policyRevision != currentPolicyRevision` 是附加标记，不改变 Badge；编辑器据此提示“策略已变化，需要重检”。

## 3. 核心模型与不变量

### 3.1 术语与实体关系

| 对象 | 定义 | 可变性 / 关系 |
|---|---|---|
| `AgentReference` | Agent 配置里的一条引用：`skillIds[]`、`tools[]`（API 类型）或 `publishedConfig` 中的对应字段 | 随 Agent 保存/发布变化；没有独立 id |
| `ReferenceTarget` | 被引用对象：Skill 记录；Service API catalog 中的 app/service/operation | 独立生命周期；可被删除、禁用、resync 替换 |
| `ReferenceIssue` | 一次“引用失效”的 episode：agentId、refType、refId、sources、reason、firstDetectedAt、lastSeenAt、notifiedAt、resolvedAt | 台账对象；用于通知去重与审计，不驱动 Badge |
| `HealthSnapshot` | 某个 Agent 在某个读取时刻的派生健康视图：referenceIssues + qualityStatus | 不落库；每次列表/详情读取时计算 |
| `QualityStatus` | 最近一次完成的作者侧质量运行的摘要 | 由 `agent_quality_runs` 派生；不新增 Agent 字段 |
| `AttentionNotification` | 通知中心的一条 `AGENT_ATTENTION` 通知，按 Agent 聚合 | 持久化；`dedupeKey` 保证 episode 内最多一条 |

关系：

- `Agent 1—N AgentReference`（draft 和 published 各一组）。
- `ReferenceTarget 1—N AgentReference`（一个 Skill/API tool 可被多个 Agent 引用）。
- `Agent 1—N ReferenceIssue`（历史 episode；同一时刻最多一个 active issue per `(agentId, refType, refId)`）。
- `ReferenceIssue N—1 AttentionNotification`（一个通知覆盖同一次检测批次里同一 Agent 的多个新 issue）。
- `Agent 1—N QualityRun`（历史运行；Badge 只看最近一次完成的作者侧运行）。

**为什么 issue 用 `(agentId, refType, refId)` 而不是 `(agentId, toolIndex)`：** 引用的身份是目标身份，不是数组下标。用户在中间插入/删除一个 tool 不应该产生新的 episode；同一个 Skill 同时出现在 draft 和 published 也应该合并为一条 issue，用 `sources` 区分。

### 3.2 引用有效性的定义

#### 3.2.1 Skill

- 判定：`skillIds[]` 中的每个 id 是否存在于 `app.skills`。
- 批量：一次 `findNames(ids)` 返回存在的 id → name；不在返回集合中的 id 即缺失。
- 权限：当前 `SkillService.resolveAccessibleSkills` 的注释明确“Skill resolution itself only requires that every referenced Skill exists”（`SkillService.java:401-412`），因此首版不把“调用者无权使用”纳入引用健康；权限在 Agent/session 授权层处理。
- 缺失 → issue `SKILL_NOT_FOUND`，`refName` 使用删除前的名字（删除钩子传入）或 id。
- Skill 内容更新、repo sync 不改变 id，不算失效。
- **不要复用 `AgentListHelper.resolveSkillNames` 的返回值判断缺失**：它 catch 异常后返回空 map（`AgentListHelper.java:91-102`），会把“查询失败”误判为“全部缺失”；健康检查必须直接调用 `SkillStore.findNames` 并区分成功/失败。

#### 3.2.2 Service API tool

API tool 的 ref id 有三种粒度（`InternalApiToolLoader.java:36-42`、`AgentEditor.tsx:512-534`）：

| ref id 形态 | 有效性 | 说明 |
|---|---|---|
| `api-operation:{app}:{service}:{operation}` | 当前 catalog 中存在同名 app/service/operation | 最严格；operation 被移除、service 被禁用、app 被删除都失效 |
| `api-service:{app}:{service}` | 当前 catalog 中该 service 至少有一个 operation | service 不存在或全部 operation 被移除/禁用 → 失效 |
| `api-app:{app}` | 当前 catalog 中该 app 至少有一个 operation | app 不存在或全部 service/operation 不可用 → 失效 |
| `builtin-service-api` | 不评估 | 它是“全部 Service API”的组引用，不是具体 tool；首版不把它当成可失效引用 |
| 其他 `api-*` 形态 | 格式非法 → `MALFORMED_REFERENCE` | 防止拼写错误静默变成“没有工具” |

Catalog 必须来自**成功加载**的 Service API 快照：

- `ApiDefinitionService.loadAll` 会跳过 `enabled != true` 的 app，跳过 disabled service，跳过 disabled operation（`ApiDefinitionService.java:35-48`、`:63-66`、`:92-96`）。
- `InternalApiToolLoader.loadByToolId` 对缺失 operation 返回空列表，而不是抛错（`InternalApiToolLoader.java:139-160`）；`ToolRefResolver.resolveApiRef` 对空结果直接跳过，只留下一个 warn 日志（`ToolRefResolver.java:297-312`）。这就是“运行时静默少一个 tool”的根因，也是 Badge 的价值。
- `ApiToolCatalogService` 当前在加载失败时返回空 snapshot（`ApiToolCatalogService.java:127-151`）。**这是危险的**：空 snapshot 会让所有 API ref 看起来缺失。首版必须保留“最后一次成功快照 + 加载状态”，加载失败时把 API 维度标为 `UNVERIFIED`，绝不能把失败当成空 catalog。
- 一个 app 的 payload 解析失败会被 `loadAll` 捕获并跳过该 app（`ApiDefinitionService.java:41-45`）。这必须与“record 被删除/禁用”区分：
  - `service_api` 记录不存在、`enabled=false`、service/operation 被 disabled → 该 app 不在成功加载的 catalog 中，相关 ref 是 `CONFIRMED_MISSING`；
  - 记录存在但 payload 解析失败 → 相关 ref 是 `UNVERIFIED`，不能判 broken，也不能通知。
  实现上让 `ApiDefinitionService` 暴露 `LoadResult { apis, failedApps }`（或让 catalog snapshot 记录 `failedApps`），健康检查只对 `failedApps` 之外的缺失判 broken。

#### 3.2.3 引用种类与 ToolRef 类型

- `ToolRef.type` 可能是 `API`、`MCP`、`BUILTIN`、`AGENT`、`LLM_CALL`；也可能是 null（旧数据），需要从 id 前缀推断（`ToolRef.inferTypeFromId`）。
- 首版只评估 `type == API` 或 id 以 `api-app:/api-service:/api-operation:` 开头的 ref；其他类型跳过，不标记、不通知。
- 如果 ref.type 明确是 `API` 但 id 形态不合法 → broken（`MALFORMED_REFERENCE`）。
- 如果 ref.type 与 id 形态冲突（例如 type=BUILTIN 但 id=`api-operation:...`），首版按 id 前缀识别 API 并检查；类型冲突本身由质量门禁的 `CFG.REFERENCE_AVAILABLE` 负责。

#### 3.2.4 与质量门禁 ReferenceAvailableRule 的关系

质量门禁已经有一套完整的引用解析（`QualityReferenceResolver.java:58-68`）和规则（`ReferenceAvailableRule.java:17-43`），覆盖 SKILL、API、MCP、AGENT、LLM_CALL、DATASET、SYSTEM_PROMPT。首版健康检查**不复用整个 resolver**，原因：

1. 它按 ref 逐条查 DB（`resolveSkills` 用 `findOrNull`，`resolveApi` 走 `apiToolNames` → `loadByToolId`），列表场景会产生 N×M 查询；
2. 它还包含 MCP/dataset/subagent 等首版不评估的类型；
3. 它是“发布候选”的判定，包含网络/权限等更重的语义。

首版抽出一个只做 Skill + API 的 metadata probe，但**必须复用同一组 reason code 和用户文案**，并用一致性测试锁定：对同一组 Agent 配置，健康检查的 SKILL/API 结果应与 `QualityReferenceResolver` 过滤后的结果一致（MCP 等除外）。这样既避免列表性能问题，也避免两套判定慢慢漂移。

### 3.3 Draft 与 Published 双视图

Agent 文档里有两份配置：

| 视图 | 字段 | 谁依赖它 | 修复方式 |
|---|---|---|---|
| Draft | `tools`、`skillIds` | 作者下一次保存/发布；owned editable 的运行 | 编辑器中移除引用并保存 |
| Published | `publishedConfig.tools`、`publishedConfig.skillIds` | 其他用户、A2A、workflow、session 等所有非 owner 执行 | 编辑 draft（如果 draft 也有）并**重新发布**；只改 draft 不够 |

关键事实：

- `AgentPublishedConfig` 是发布时冻结的副本（`AgentPublishedConfig.java:39-50`）；`AgentDependencyAccessPolicy.hasValidatedPublishedSkills` 只证明“发布时做过 skill 校验”（`skillValidationVersion`），**不证明 Skill 现在仍然存在**（`AgentDependencyAccessPolicy.java:34-38`）。所以已发布 Agent 的 Skill 被删除后，现有校验不会阻止运行，也不会自动报警。
- `AgentDefinitionService.update` 对 draft 的 `skillIds` 调用 `requireAccessibleSkills`（`AgentDefinitionService.java:294`、`:376-380`），缺失 Skill 会直接拒绝保存；这意味着用户**必须**先移除失效 Skill 才能保存任何修改。这是现有行为，设计不改变它，但编辑器要在保存前把问题说清楚。
- API tool 在 update 路径没有等价校验；用户可以在 draft 里保留一个失效 API ref 并保存。Badge 负责持续提示。
- `AgentDefinitionService.publish` 会用 draft 构建候选并写入 `publishedConfig`（`AgentPublishService.java:28-59`）；因此“移除 draft 引用 + 重新发布”是修复 published issue 的唯一正常路径。
- 若 draft 已经不含该引用、只有 `publishedConfig` 含：编辑器的动作不是“移除”，而是“发布当前 draft 以替换已发布版本”。UI 必须区分这两种情况。

**Badge 规则：**

- Draft issue 只对“有编辑能力”的受众展示（owner、admin、持有 `agent.manage` 的用户），因为它不影响其他用户的已发布执行。
- Published issue 对所有能看到该 Agent 的受众展示，因为它影响实际执行。
- 同一 `(refType, refId)` 同时存在于 draft 和 published → 一条 issue，`sources=[DRAFT,PUBLISHED]`；任一来源仍存在就保持 active。

### 3.4 质量状态的定义

#### 3.4.1 取哪一次运行

质量运行由 `PublishQualityGateService` 创建并持久化（`PublishQualityGateService.java:60-63`、`:116-142`、`:204-220`），字段包括 `mode`、`runStatus`、`gateResult`、`definitionRevision`、`policyRevision`、`createdAt/finishedAt`（`AgentQualityRun.java:28-90`）。

**不能用 `AgentDefinition.qualityReceipt` 代替：** receipt 只在成功发布时写入（`AgentPublishService.java:47-76`），失败检查不会更新 receipt；它的 `gateResult` 实际上永远是 `PASSED`（或豁免），无法表达“最近一次检查失败”。receipt 证明的是“已发布版本由哪次 run 放行”，不是“最近一次检查结果”。

首版 Badge 取：

- `runStatus == COMPLETED`；
- `mode ∈ {PRECHECK, PUBLISH, FORK}`；
- 按 `finishedAt`（缺失时用 `createdAt`）倒序的第一条；
- 该条的 `gateResult` 决定状态。

**为什么排除 WORKFLOW：** `WorkflowAgentSnapshotService` 会在工作流创建/更新时对“owned editable”的 Agent 运行一次 `QualityGateMode.WORKFLOW`（`WorkflowAgentSnapshotService.java:85-100`）。这是工作流消费者对 Agent 的校验，不是 Agent 作者主动触发的检查；把它算进 Agent 的 Badge 会让作者看到一个自己没触发、也不知道如何复现的失败。若后续产品希望“任何质量运行失败都提醒”，可以把它作为独立配置项，而不是首版默认。

**为什么包含 FORK：** `PersonalAssistantService.applyForkGate` 在 fork 时运行 `QualityGateMode.FORK`，失败会让副本保持 DRAFT（`PersonalAssistantService.java:125-155`）。这是用户自己的副本，失败对用户有意义。

**不纳入离线评估：** `design-agent-evaluation.md` 的 eval trial 有自己的 run/grader/报告，不是 `AgentQualityRun`；首版不把 eval 的 pass/fail 混进 `health.quality`。未来若需要，应新增独立字段或把 verdict 映射成质量运行，而不是复用当前 `gate_result` 语义。

#### 3.4.2 结果映射

- `PASSED` → `PASSED`；即使 `checksComplete=false`（WARN incomplete）也不显示 `!`，WARN 在报告里展示。
- `REJECTED` → `FAILED`；`block_fail_count` 取 run 上新增的计数字段；旧 run 缺失时列表显示通用失败文案（不伪造 0），编辑器完整报告仍可精确计算。
- `UNAVAILABLE` → `UNAVAILABLE`；表示必需检查未完成（如决策模型不可用），用户应重试，而不是改内容；`incomplete_count` 同样优先取 run 字段，旧 run 缺失则省略。
- `exempted=true` → `PASSED`（内置/白名单豁免）；编辑器标注 exempt。
- 没有运行 → `NONE`，不显示 `!`。未检查不是失败。
- `gateResult` 缺失（旧/异常记录）→ `UNKNOWN`，不显示 `!`。
- 质量存储读取失败 → `UNKNOWN`，不显示 `!`。

#### 3.4.3 编辑、策略变化与“粘性”

推荐语义：**最近一次完成的作者侧运行失败后，Badge 保留到下一次 PASS，不因编辑而清除。**

理由：

1. 质量失败是**关于一次已发生检查的历史事实**；编辑不会产生新的检查证据，直接清除等于用一个未知状态替换已知失败。
2. 现有编辑器在保存时会清空本地 `qualityReport`（`AgentEditor.tsx:700`），但那是“报告不再对应当前内容”的 UI 状态，不等于“质量已经通过”。列表 Badge 应更保守。
3. 用户修改内容通常是为了修复失败；保留 Badge 能提醒“改完还要再检查/发布”。若编辑后自动消失，用户可能误以为已修复。

代价与缓解：

- 旧失败可能针对旧内容；`health.quality` 同时返回 `definitionRevision`、`currentRevision`、`contentChanged`、`policyChanged`。`contentChanged` 的计算必须区分“发布写入”和“检查后编辑”：

  ```text
  contentChanged =
      !(run.definitionRevision == currentRevision
        || (run.definitionRevision + 1 == currentRevision
            && entity.qualityReceipt != null
            && run.id.equals(entity.qualityReceipt.runId)))
  ```

  发布成功会把 `definitionRevision` +1，同时 receipt 指向该 run，因此不算“检查后内容变了”；预检/失败发布没有写入，revision 相等；检查后再编辑则 revision +1 且 receipt 不匹配，`contentChanged=true`。tooltip/编辑器根据 `contentChanged`/`policyChanged` 写“内容已变更，需要重检”，**不要直接把 revision 数字差当成内容变化**（发布成功会 +1），也不要让用户以为当前内容一定失败。
- 若策略已经放宽、旧失败不再适用，用户仍需要一次重检来清除；这是“没有新证据就不撤销警告”的安全偏置，可接受。
- 若用户明确希望“编辑即清除”，实现上只需比较 revision；但这会把“未检查”伪装成“没问题”，本文不推荐。

#### 3.4.4 策略变化

`QualityPolicyService.active()` 每次读取 head/policy/rules（`QualityPolicyService.java:140-157`）；首版为了列表性能只读 head 的 `activeRevision`（新增轻量方法），与 `run.policyRevision` 比较：

- 相同 → 结果视为当前策略下有效；
- 不同 → `policyChanged=true`，Badge 状态不变（仍按运行结果），编辑器提示“策略已变化，需要重检”。

不因为策略变化直接把 `FAILED` 改成 `PASSED` 或 `NONE`；也不因为策略变化自动重跑（质量检查可能调用决策模型，有成本和副作用）。

### 3.5 派生状态与事件状态的边界

这是本文最核心的设计取舍：

| 信息 | 存储形态 | 读取方式 | 为什么 |
|---|---|---|---|
| 引用是否失效 | **不存储**（Agent 文档 + catalog 是事实） | 每次读取时派生 | 资源可恢复；Agent 可被并发编辑；存储布尔值必然漂移 |
| Agent 曾发生过的失效 episode | `agent_reference_issues` | 通知/审计/对账读取；`refName` 展示补全 | 需要“已经通知过”与 episode 边界；Badge 的显示/隐藏不能依赖它 |
| 通知 | `notifications` + `dedupe_key` | 通知中心读取 | 用户可能离线；SSE 不是可靠通道 |
| 质量结果 | `agent_quality_runs`（已有） | 批量取最新完成运行 | 门禁已经持久化；不重复存 Agent 字段 |
| 质量策略 | `agent_quality_policy_*`（已有） | 读 active revision | 用于 `policyChanged` 标记 |

**明确拒绝的方案：在 `AgentDefinition` 上新增 `health_status` / `has_broken_reference` 字段。**

- 写 Agent 文档必须 bump `definitionRevision`（`PgAgentDefinitionStore.java:203-214`），后台健康检查会和编辑器 CAS 冲突；
- 资源恢复、catalog 修复、Skill 重新上传都不会自动更新该字段，必然漂移；
- 需要全量回填、双写、补偿任务；
- 该字段是环境级事实，不是 Agent 的作者属性；同一 Agent 对不同受众（是否能编辑）显示不同，也无法用一个字段表达。

**明确拒绝的方案：用通知是否已读/存在来判断 Badge。**

- 用户标记已读不代表问题消失；
- 通知可能因为多 Pod/SSE 丢失；
- 历史通知无法表达“当前已恢复”；
- 会把“提醒”与“事实”耦合。

**结论：**Badge 的**判定**（是否 `ATTENTION`）永远是派生视图；台账只服务于通知、审计、以及 `refName` 的 best-effort 展示补全。台账有值时显示删除前的 Skill 名字；台账不可用/无值时回退到 ref id，**绝不因为台账状态改变 Badge 的显示或隐藏**。这样即使台账任务失败、重复、延迟，列表仍然说真话。

## 4. 检测、通知与对账

### 4.1 触发源

| 触发源 | 时机 | 执行方式 | 检测范围 | 失败处理 |
|---|---|---|---|---|
| Skill 删除 | `SkillService.delete` 成功后 | 同步调用，严格时间预算；必要时短异步 | 按 `skillId` 反向查询 draft + published 引用者 | 不影响删除结果；记录错误，下一轮对账补偿 |
| Service API reload | `ToolRegistrySyncJob` 检测到 `service_api` 变化并 reload 成功 | 后台任务内异步/同步均可 | 所有带 API 类型引用的 Agent，对照新 catalog | 不影响 reload；记录错误，下一轮对账补偿 |
| Agent 保存 | `update` 成功后 | 同步或提交后短异步 | 该 Agent 的当前 draft + published | 不影响保存；Badge 读取时仍会派生 |
| Agent 发布 | `publish` 成功后 | 同上 | 该 Agent 的当前 draft + published | 不影响发布 |
| Agent 删除 | `delete` 成功后 | 同上 | 该 Agent 的 active issues | 标记 `AGENT_DELETED` |
| 周期对账 | `AgentHealthReconcileJob` | 后台、可分页、可重复 | 全量（有界批量 + 游标） | 下一 tick 继续；不阻塞业务 |
| 质量运行 | 不触发通知/台账 | 无 | Badge 读取时批量取最近运行 | 读取失败 → `UNKNOWN` |
| 质量策略变化 | 不触发 | 无 | `policyChanged` 读取时计算 | 无 |

#### 4.1.1 Skill 删除

`SkillService.delete` 当前先 `skillStore.get(id)` 拿到实体（为了删 blob），再 `skillStore.delete(id)`（`SkillService.java:230-236`）。首版：

1. 删除成功后，把 `entity.name`、`id`、操作者 `userId` 交给 `AgentHealthService.onSkillDeleted`。
2. 健康服务反向查询引用者（§4.2.1），对每个 Agent upsert issue。
3. 删除 API 的响应不等待通知插入；但检测本身建议同步完成（反向查询是索引查询，通常很小）。如果受影响 Agent 超过阈值（如 200），改为后台任务，避免删除请求超时。
4. 任何检测/通知异常只记录日志，不把删除变成失败——资源已经删了，失败会让客户端重试并产生不一致。

为什么不把钩子放在 `SkillService` 里直接注入 `AgentHealthService`：

- `SkillService` 在 `SkillModule`（`ServerApp` 第 240 行）绑定，早于 `AgentHealthModule`（应在 Notification/ApiToolHub 之后）；
- `AgentHealthService` 需要 `SkillStore` 而不是 `SkillService`，所以不会形成循环；
- 用 setter/监听器由后加载的模块注入，最稳。

#### 4.1.2 Service API reload

`ToolRegistrySyncJob` 每 30 秒执行：

1. `toolRegistryService.syncDatabaseTools()`（MCP 同步）；
2. `serviceApiService.configSnapshot()` 变化时，`mcpServerService.reload()` + `toolRegistryService.reloadApiTools()`（`ToolRegistrySyncJob.java:29-49`）。

当前 `reloadApiTools()` 在内部 catch 异常并只打日志（`ToolRefResolutionService.java:251-262`），调用方无法知道成功/失败；并且 `lastApiSnapshot` 无论成功失败都会被更新（`ToolRegistrySyncJob.java:38-45`），导致一次失败后不会重试直到下一次配置变化。首版必须：

- 让 reload 返回 `boolean`（或抛类型化异常），失败时不更新 `lastApiSnapshot`；
- 成功后再触发健康对账；
- 健康对账不依赖“这次删了哪些 id”的 diff，而是加载当前带 API 引用的 Agent，对照**当前成功快照**；
- 加载失败时保留上一份成功快照用于诊断，但健康对账不跑（标记 `UNVERIFIED`），避免全量误报。

`ToolRegistrySyncJob` 由 `schedule().fixedRate` 注册（`ToolRegistryModule.java:28`）。core-ng 的 `Scheduler` 没有分布式锁，每个 Pod 都会执行（`d:\core-ng-project\core-ng\src\main\java\core\framework\internal\scheduler\Scheduler.java:117-126`）。因此对账/通知必须幂等（§4.3）。

#### 4.1.3 Agent 保存 / 发布 / 删除

- 保存：先完成 `replaceIfRevision`，再调用 `onAgentSaved(entity, actorUserId)`。健康服务用新实体计算当前坏引用，upsert 新 issue、resolve 不再存在的 issue。这样用户移除引用后，台账也能立即进入 resolved，下一次再失效可以重新通知。
- 发布：`publishedConfig` 更新后调用同一个 `onAgentSaved`，因为 published 引用可能新增/消失。
- 删除：`onAgentDeleted(agentId)` 把所有 active issue 标为 `AGENT_DELETED`；通知历史保留。
- 这些钩子必须在写事务/写请求成功之后执行；失败只记录，不影响保存/发布/删除的返回值。

### 4.2 反向查找与扫描策略

#### 4.2.1 Skill 反向查询

PG 使用 JSONB 包含查询：

```sql
SELECT id, user_id, data::text AS data
FROM app.agents
WHERE data -> 'skillIds' @> ?::jsonb
   OR data -> 'publishedConfig' -> 'skillIds' @> ?::jsonb
```

参数为 `["<skillId>"]`。需要的索引：

```sql
CREATE INDEX IF NOT EXISTS agents_skill_ids_gin_idx
    ON app.agents USING GIN ((data -> 'skillIds'));
CREATE INDEX IF NOT EXISTS agents_published_skill_ids_gin_idx
    ON app.agents USING GIN ((data -> 'publishedConfig' -> 'skillIds'));
```

**JSON key 命名必须区分存储：**

- PG 文档表用 `JsonUtil` 序列化 Java 对象，只有 `@Property` 会改名，`@Field`（core-ng Mongo 注解）不参与；因此 `app.agents.data` 的 key 是 `skillIds`、`publishedConfig`、`definitionRevision`（`V38__agent_quality_gate.sql:67-71` 的 `jsonb_set(data, '{publishedName}', ...)` 也是 camelCase）。
- Mongo 文档用 `@Field(name=...)`，所以同一个实体在 Mongo 里的字段是 `skill_ids`、`published_config`、`definition_revision`。
- 因此 PG 查询/索引写 camelCase；Mongo 查询写 `{ $or: [{ skill_ids: id }, { "published_config.skill_ids": id }] }`，索引为 `{ skill_ids: 1 }`、`{ "published_config.skill_ids": 1 }`。首版 PG 为主；Mongo 只作为兼容降级（见 §5.4）。

#### 4.2.2 API 引用对账

API operation 删除可能一次影响整个 app（payload 替换），逐个 removed id 反向查会产生大量查询；反过来，带 API 引用的 Agent 通常远少于全量 Agent。首版：

```sql
SELECT id, user_id, data::text AS data
FROM app.agents
WHERE data -> 'tools' @> '[{"type":"API"}]'::jsonb
   OR data -> 'publishedConfig' -> 'tools' @> '[{"type":"API"}]'::jsonb
```

带 API 引用的 Agent 加载后，在内存中逐 ref 对照 `ApiToolCatalogService` 的成功快照（app/service/operation 集合），得到当前 broken 集合。需要的索引：

```sql
CREATE INDEX IF NOT EXISTS agents_tools_gin_idx
    ON app.agents USING GIN ((data -> 'tools'));
CREATE INDEX IF NOT EXISTS agents_published_tools_gin_idx
    ON app.agents USING GIN ((data -> 'publishedConfig' -> 'tools'));
```

**为什么不按 removed id 查：**

- 一次 payload 更新可能删掉整个 app 的几十/几百个 operation；
- 多 Pod 的“上一份快照”不同，removed 集合不可靠；
- 当前事实对账不依赖事件顺序，重启后也能得到正确结果。

**Legacy type 提示：** 如果存量 `ToolRef.type` 为 null、但 id 以 `api-` 开头，单纯 `@> '[{"type":"API"}]'` 会漏掉。实现时要么先用迁移/回填补齐 type，要么在反向查询里用 `jsonb_array_elements` 检查 id 前缀；不要在健康检查里假设所有 API ref 都带 type。

#### 4.2.3 周期全量对账

`AgentHealthReconcileJob` 用于补偿漏掉的勾子、处理“资源恢复”、以及首次部署回填。它不应该每 tick 全表加载大文档：

- 新增 `AgentDefinitionStore.referenceProjection(afterId, limit)`，只 select `id, user_id, definitionRevision, tools, skillIds, publishedConfig.tools, publishedConfig.skillIds`，不 select `system_prompt`；
- 按 `id > cursor ORDER BY id LIMIT ?` 分页，默认 500/页；每 tick 在时间预算内连续处理多页（如最多 5s/20 页），而不是只处理一页，缩短首次回填时间；
- 每页一次 Skill 批量存在性查询；API 对照内存快照；质量状态只在列表读取时算，对账不需要；
- 游标与回填标记放在 `app.agent_health_state` 单行（§5.2）；
- 同一时刻只允许一个 Pod 执行（PG advisory lock 或 `PodRegistry` 选主）；即使多 Pod 跑，唯一键也保证幂等，锁只是省资源。
- 全量 sweep 之后（或同一 tick 内）对 **所有 active issue** 做一次校验：Agent 不存在 → `AGENT_DELETED`；Agent 已不再引用该 ref → `REMOVED`；目标已恢复 → `RESTORED`。这样即使 Agent 已经没有引用、或删除/保存钩子丢失，台账也不会留下永久 active 记录。
- 全量 sweep 的 Agent 投影可以只包含“有引用”的 Agent（省资源）；active issue 校验单独走台账表，两者互补。

### 4.3 通知去重、聚合与多 Pod

#### 4.3.1 Issue 台账

`agent_reference_issues` 的 active 唯一键（逻辑字段名；PG JSONB 表达式见 §5.2）：

```text
(agentId, refType, refId) WHERE resolvedAt IS NULL
```

upsert 语义：

- 不存在 active → 插入新 issue，`firstDetectedAt=now`，`notifiedAt=null`；
- 已存在 active → 更新 `sources`、`lastSeenAt`、`refName`（如果有更准确的名字）；
- 本轮没有检测到、但之前 active → 如果 Agent 仍引用该目标且目标已恢复 → `RESOLVED/RESTORED`；如果 Agent 不再引用 → `RESOLVED/REMOVED`；Agent 删除 → `RESOLVED/AGENT_DELETED`。

#### 4.3.2 通知聚合一：一个 Agent 一条

同一次检测/对账中，一个 Agent 可能有多个新失效引用（例如一个 app 被删，Agent 里 8 个 operation）。逐个发 8 条通知是噪声。首版按 Agent 聚合：

1. upsert 完本轮 issue 后，取该 Agent 所有 `notifiedAt == null` 的 active issue；
2. 一条通知覆盖这些 issue，message 列出前 5 条，剩余写 “and N more”；
3. `dedupeKey = "agent-health:" + agentId + ":" + recipientUserId + ":" + sha256(sorted(issueIds))`；
4. `createIfAbsent` 返回“已插入”或“dedupeKey 已存在”（说明另一个 Pod 已经创建）都视为成功，把这些 issue 的 `notifiedAt = now()`；只有真正的写入失败才保持 null 等下一轮。

如果进程在“插入 issue”和“创建通知”之间崩溃：issue 仍是 `notifiedAt=null`，下一轮会把它们重新聚合，dedupeKey 不变；如果两个 Pod 同时处理，dedupeKey 相同，`notifications.dedupe_key` 唯一索引只允许一条。

#### 4.3.3 通知类型与收件人

- `NotificationCategory.AGENT`，新增 `NotificationType.AGENT_ATTENTION`。
- 收件人 = `AgentDefinition.userId`（owner）。首版不发给所有 AGENT_MANAGE 用户，避免跨用户打扰；owner 是最明确的修复责任人。
- 通知 `agentId` 设置为该 Agent，前端通知中心可点击跳转 `/agents/{id}`。
- 如果 actor 就是 owner，首版仍然通知（用户在删除 Skill 后可能忘记哪些 Agent 用了它）；后续可加“删除前影响预览”并在自操作时降级为静默（§11 Q5）。
- SSE 在线推送沿用 `NotificationEventPublisher`，但它是 in-process（`NotificationEventPublisher.java:12-18`）；跨 Pod 的在线提醒不在首版范围，持久化通知保证用户下次打开通知中心能看到。

#### 4.3.4 多 Pod 与重复执行

- Skill 删除请求通常只落在一个 Pod，但重试/负载均衡可能重复；active 唯一键去重。
- API reload 每个 Pod 都会执行；每个 Pod 都可能创建同一 issue；唯一键 + 通知 dedupeKey 去重。
- 对账任务可能重叠；同上。
- 通知创建失败时保留 `notifiedAt=null`，下一轮重试；不要因为一次失败把 issue 标成已通知。

#### 4.3.5 通知内容示例

```text
Title:   Agent "Order Analyst" needs attention
Message: 2 references are broken: Skill "pdf-report" was deleted; API tool
         "order-service > refund > create" is no longer available after a
         Service API resync. Open the agent to remove them or republish.
agentId: 5f0c...
type:    AGENT_ATTENTION
```

### 4.4 首次部署回填

如果部署后第一次对账直接按“新 issue → 通知”处理，历史坏引用会给所有 owner 发一波通知，造成“上线即风暴”。首版：

1. `app.agent_health_state` 有一条 singleton 记录（`id="reconciler"`），包含 `backfillDone`、`cursor`、`lastRunAt`。
2. 第一次完整 sweep 期间（`backfillDone=false`）：
   - 正常 upsert issue；
   - 把所有 active issue 的 `notifiedAt` 预设为 `now()`，**不创建通知**；
   - 记录日志/指标：`backfill issues=N, agents=M`。
3. 游标回到起点、完整 sweep 结束后写 `backfillDone=true`。
4. 之后的新 issue 才创建通知。
5. 事件钩子在回填期间也遵循同一抑制策略，避免“半回填 + 半通知”的混乱。
6. 因此发布顺序建议：P2/P3 先上线 Badge 与台账，但 `backfillDone=false` 时**所有通知路径都只写 issue、不发通知**；P4 完成首次全量 sweep 后把 `backfillDone` 置 true，通知才真正打开。`AgentHealthService` 对该标记做短 TTL 缓存（如 60s），让各 Pod 在 P4 完成后很快打开通知，而不是各自重启才生效。

这样上线后用户打开列表能看到 Badge，但不会被历史问题轰炸；新发生的删除/resync 才通知。

### 4.5 失败语义

| 失败 | 健康计算 | 通知 | 日志/指标 |
|---|---|---|---|
| API catalog 加载失败 | API 维度 `UNVERIFIED`，不显示 `!`；保留上一份成功快照用于诊断 | 不创建新 issue；不 resolve 现有 issue | `WARN api_catalog_unavailable`；metric `agent_health_probe_failure{component=api}` |
| Skill store 查询失败 | Skill 维度 `UNVERIFIED` | 不创建/不 resolve | `WARN skill_store_unavailable`；metric |
| 质量 store 查询失败 | `health.quality.state=UNKNOWN` | 无 | `WARN quality_store_unavailable`；metric |
| 质量 policy head 读取失败 | `policyChanged=null`；`quality.state` 仍按 run | 无 | `WARN quality_policy_unavailable`；metric |
| 通知插入失败 | 不影响 | issue `notifiedAt` 保持 null，下一轮重试 | `ERROR notification_create_failed`；metric |
| 台账 upsert 失败 | 不影响 Badge | 记录错误，下一轮补偿 | `ERROR issue_upsert_failed` |
| 对账投影解析失败 | 跳过该 Agent，下一条继续 | 无 | `WARN agent_reference_projection_failed,id=...` |
| 健康计算整体异常 | 该 Agent `UNKNOWN`，不显示 `!` | 无 | `WARN agent_health_failed,id=...`；metric |
| 删除钩子异常 | 删除成功 | 下一轮对账补偿 | `ERROR skill_delete_hook_failed` |

**核心原则：**健康检查是诊断，不是业务主路径。它不能因为自身失败让列表 500、让删除失败、让发布失败；也不能在不确定时冒充确定。

## 5. 数据模型与 API

### 5.1 API View

在 `AgentDefinitionView` 增加可选嵌套字段：

```java
@Property(name = "health")
public AgentHealthView health;
```

新增 view（`core-ai-api/src/main/java/ai/core/api/server/agent/`）：

```java
public class AgentHealthView {
    @Property(name = "status")            public String status;          // OK | ATTENTION | UNKNOWN
    @Property(name = "needs_attention")   public Boolean needsAttention;
    @Property(name = "reference_issues")  public List<AgentReferenceIssueView> referenceIssues;
    @Property(name = "quality")           public AgentQualityStatusView quality;
}

public class AgentReferenceIssueView {
    @Property(name = "ref_type")          public String refType;         // SKILL | API
    @Property(name = "ref_id")            public String refId;
    @Property(name = "ref_name")          public String refName;
    @Property(name = "state")             public String state;           // BROKEN | UNVERIFIED
    @Property(name = "reason")            public String reason;          // SKILL_NOT_FOUND | API_TOOL_NOT_FOUND | ...
    @Property(name = "sources")           public List<String> sources;   // DRAFT | PUBLISHED
    @Property(name = "in_draft")          public Boolean inDraft;
    @Property(name = "in_published")      public Boolean inPublished;
    @Property(name = "message")           public String message;
}

public class AgentQualityStatusView {
    @Property(name = "state")               public String state;               // NONE | PASSED | FAILED | UNAVAILABLE | UNKNOWN
    @Property(name = "run_id")              public String runId;
    @Property(name = "mode")                public String mode;                // PRECHECK | PUBLISH | FORK
    @Property(name = "gate_result")         public String gateResult;          // PASSED | REJECTED | UNAVAILABLE
    @Property(name = "block_fail_count")    public Integer blockFailCount;
    @Property(name = "incomplete_count")    public Integer incompleteCount;
    @Property(name = "warn_count")          public Integer warnCount;
    @Property(name = "exempted")            public Boolean exempted;
    @Property(name = "checked_at")          public ZonedDateTime checkedAt;
    @Property(name = "definition_revision") public Integer definitionRevision;
    @Property(name = "current_revision")    public Integer currentRevision;
    @Property(name = "policy_revision")     public Integer policyRevision;
    @Property(name = "policy_changed")      public Boolean policyChanged;
    @Property(name = "content_changed")     public Boolean contentChanged;
    @Property(name = "message")             public String message;
}
```

规则：

- `health` 只出现在 full `AgentDefinitionView`（`get`、`list` 非 summary/picker、`create/update/publish` 返回值）；`summary`、`picker`、`favorites` 不计算，保持轻量。
- 现有 `AgentDefinitionService.get(String id)` / `getByName(name, userId)` 中前者没有 caller 参数（`AgentDefinitionService.java:255-265`），需要增加 `get(id, viewerUserId)` 重载，由 `AgentDefinitionWebServiceImpl.get` 传入 `AuthContext.userId`；`getByName` 已有 `userId`，可直接用于 health；`create/update/publish` 已有 `userId`，直接复用。
- `viewerCanManage` 每个 list 请求只计算一次（owner/admin/`agent.manage`），不要对每个 Agent 调权限服务。
- `referenceIssues` 只包含**当前受众**可见的问题：published 问题所有人可见；draft 问题只对 owner/admin/`agent.manage` 可见。
- `refName` 是展示字段：Skill 缺失时优先取 issue 台账里删除前的名字（best-effort），台账不可用/无值则回退为 ref id；API 从 ref id 解析。`refName` 不参与 `status` 判定。
- `quality` 只对有编辑能力的受众计算；其他受众为 null，避免把别人的草稿失败当成已发布 Agent 的问题。
- `status=ATTENTION` 当且仅当至少一个 `state=BROKEN` 的 issue 或 `quality.state ∈ {FAILED, UNAVAILABLE}`。
- `status=UNKNOWN` 当且仅当没有确认 broken，但至少一个**确实需要验证**的维度 `UNVERIFIED/UNKNOWN`。例如 Agent 没有任何 Skill，Skill store 故障不应把它标成 UNKNOWN；没有任何 API ref，catalog 故障同理。
- 所有 list 字段使用空列表而不是 null；旧客户端忽略未知属性。
- 不把 `quality.issues` 放进列表；完整报告仍走 `GET /api/agents/:id/quality-checks/latest`。

### 5.2 PostgreSQL 表与索引

新增迁移（实现为 `V53`；`V52` 已被并行的 persistent workspace 改动占用，使用更高版本避免 out-of-order）：

```sql
-- 1) 引用问题台账（通知去重 + 审计；不驱动 Badge）
CREATE TABLE IF NOT EXISTS app.agent_reference_issues
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 同一 Agent 的同一目标，同一时刻最多一个 active issue
CREATE UNIQUE INDEX IF NOT EXISTS agent_reference_issues_active_idx
    ON app.agent_reference_issues ((data ->> 'agentId'), (data ->> 'refType'), (data ->> 'refId'))
    WHERE data ->> 'resolvedAt' IS NULL;

CREATE INDEX IF NOT EXISTS agent_reference_issues_agent_idx
    ON app.agent_reference_issues ((data ->> 'agentId'), (data ->> 'resolvedAt'));

CREATE INDEX IF NOT EXISTS agent_reference_issues_ref_idx
    ON app.agent_reference_issues ((data ->> 'refType'), (data ->> 'refId'), (data ->> 'resolvedAt'));

-- 2) 对账/回填状态（单行 singleton）
CREATE TABLE IF NOT EXISTS app.agent_health_state
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3) 通知去重键
ALTER TABLE app.notifications ADD COLUMN IF NOT EXISTS dedupe_key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS notifications_dedupe_key_idx
    ON app.notifications (dedupe_key)
    WHERE dedupe_key IS NOT NULL;

-- 4) Agent 反向查询索引（PG JSON key 是 camelCase，见 §4.2.1）
CREATE INDEX IF NOT EXISTS agents_skill_ids_gin_idx
    ON app.agents USING GIN ((data -> 'skillIds'));
CREATE INDEX IF NOT EXISTS agents_published_skill_ids_gin_idx
    ON app.agents USING GIN ((data -> 'publishedConfig' -> 'skillIds'));
CREATE INDEX IF NOT EXISTS agents_tools_gin_idx
    ON app.agents USING GIN ((data -> 'tools'));
CREATE INDEX IF NOT EXISTS agents_published_tools_gin_idx
    ON app.agents USING GIN ((data -> 'publishedConfig' -> 'tools'));

-- 5) 质量批量查询：按 agent + finishedAt 取最近完成运行
CREATE INDEX IF NOT EXISTS agent_quality_runs_agent_finished_idx
    ON app.agent_quality_runs ((data ->> 'agentId'), (data ->> 'finishedAt') DESC);
```

说明：

- `app.agents` 是 JSONB 文档表（`V16__agents.sql:1-24`）；现有索引都在列上，没有 `tools/skillIds` 的 GIN 索引，需要补齐。
- `app.notifications` 现有列见 `PgNotificationStore.java:20-23`；`dedupe_key` 需要同时加入 `data` JSON 与列，便于唯一索引。
- `CREATE INDEX` 会在表上取锁；Agent 表通常较小，可在低峰部署。若数据量大，需评估 `CREATE INDEX CONCURRENTLY` + 非事务迁移方案（Flyway 默认事务内不能 `CONCURRENTLY`）。
- `app.agent_quality_runs` 已有 `(agentId, createdAt DESC)` 索引（`V38__agent_quality_gate.sql:46-50`）；新增 `finishedAt` 索引用于更准确的“最近完成”排序。

**台账字段（`data`）：**

| 字段 | 类型 | 说明 |
|---|---|---|
| `agentId` | String | Agent id |
| `agentOwnerId` | String | 冗余 owner，通知与审计用 |
| `refType` | String | `SKILL` / `API` |
| `refId` | String | Skill id 或 API ref id |
| `refName` | String | 可读名称；删除时尽量传入删除前的名字 |
| `reason` | String | `SKILL_NOT_FOUND` / `API_TOOL_NOT_FOUND` / `MALFORMED_REFERENCE`（台账只记录 confirmed broken，不记录 UNVERIFIED） |
| `sources` | List<String> | `DRAFT` / `PUBLISHED` |
| `firstDetectedAt` | ZonedDateTime | 本 episode 首次检测时间 |
| `lastSeenAt` | ZonedDateTime | 最近一次仍检测到的时间 |
| `notifiedAt` | ZonedDateTime | 成功创建通知的时间；null 表示待通知 |
| `resolvedAt` | ZonedDateTime | resolve 时间；null 表示 active |
| `resolution` | String | `REMOVED` / `RESTORED` / `AGENT_DELETED` |

**状态字段（`agent_health_state.data`）：** `backfillDone`、`cursor`、`lastRunAt`、`lastFullSweepAt`。

### 5.3 Store 接口改动

**`AgentDefinitionStore`（PG 完整实现；Mongo 按保留的 `@Field` 名提供等价查询，或显式降级）：**

```java
List<AgentDefinition> findReferencingAgents(String skillId);       // draft + published
List<AgentDefinition> findAgentsWithApiRefs();                     // type=API 的 tools
List<AgentReferenceProjection> referenceProjections(String afterId, int limit);
```

- `findReferencingAgents` 返回完整实体（钩子需要 owner/name/refs）；数量超过阈值时改为投影。
- `findAgentsWithApiRefs` 也返回完整实体；如果量很大，返回投影并批量取 owner。
- `referenceProjections` 只取健康计算需要的 JSON 片段，避免加载 system prompt。
- PG 查询用 camelCase key；Mongo 查询用 `@Field` 名（snake_case）。首版必须交付 PG；Mongo 可返回空/降级并记录警告，不阻塞 PG 首发。
- 为避免新增接口方法迫使未使用的 Mongo 实现同步改造，可在 `AgentDefinitionStore` 给新方法提供 default 空实现，由 `PgAgentDefinitionStore` override；Mongo 模式退化为“无反向查询/无对账”，派生 Badge 仍按当前实体计算。

**`AgentReferenceIssueStore`（PG）：**

```java
AgentReferenceIssue upsertActive(AgentReferenceIssue issue);       // unique active key
List<AgentReferenceIssue> listUnnotified(String agentId);
List<AgentReferenceIssue> listActiveByAgentIds(Collection<String> agentIds); // refName 展示
List<AgentReferenceIssue> listActive(int limit, String afterId);              // 对账巡检
void markNotified(Collection<String> issueIds, ZonedDateTime at);
void resolve(Collection<String> issueIds, String resolution, ZonedDateTime at);
void resolveAllForAgent(String agentId, String resolution, ZonedDateTime at);
List<AgentReferenceIssue> listActiveByRef(String refType, String refId);
AgentHealthState state();                                          // backfill cursor
void saveState(AgentHealthState state);
```

**`AgentQualityStore`：**

```java
Optional<AgentQualityRun> findLatestCompletedRun(String agentId, Set<QualityGateMode> modes);
Map<String, AgentQualityRun> findLatestCompletedRuns(Collection<String> agentIds, Set<QualityGateMode> modes);
```

- PG 用 `DISTINCT ON ((data ->> 'agentId')) ... ORDER BY (data ->> 'agentId'), (data ->> 'finishedAt') DESC, id DESC`（id 作为同毫秒 tie-breaker）。
- 过滤 `runStatus='COMPLETED'` 与 mode 白名单；`STARTED/ABORTED/FAILED` 不参与。
- 列表只需要 `gateResult`、计数、时间、revision；不要加载完整 `issues/ruleResults`。建议在 `finishRun` 时把 `blockFailCount`、`incompleteCount`、`warnCount` 写入 `AgentQualityRun`（nullable）；批量查询用 `data - 'issues' - 'ruleResults'` 投影，旧 run 的计数缺失按“未知/省略”处理，不用 0 冒充。

**`QualityPolicyService`：**

```java
int activeRevision();   // 只读 head.activeRevision，避免列表读取时加载整条 policy + rules
```

**`NotificationService` / `NotificationStore`：**

```java
boolean createIfAbsent(Notification notification);   // insert ... ON CONFLICT (dedupe_key) DO NOTHING
```

- `Notification` 增加 `dedupeKey` 字段（`@Field(name="dedupe_key")`），仅在内部使用；`NotificationView` 不暴露。
- PG `INSERT ... ON CONFLICT DO NOTHING`，Mongo 用唯一 partial index + 重复键处理（首版 PG 为主）。

**`SkillStore`：** 复用 `findNames(Collection<String>)`（`PgSkillStore.java:79-92`），不需要新方法；返回的 map 即“存在的 id → name”。

### 5.4 兼容与迁移

- 新字段/新表全部是**增量**；`AgentDefinitionView.health` 可空，旧客户端忽略。
- `NotificationType.AGENT_ATTENTION` 是新增枚举值；旧前端会显示原始字符串，不影响功能。新前端补 label/color。
- 现有 `AgentQualityReceipt` / `quality` 字段不变；`health.quality` 是最近运行，二者语义不同，不要合并。
- **存储模式：**当前三环境 `SYS_STORE_MODE=pg`（`docs/cn/design-mongo-decommission.md:27`），且 `MessagingInfrastructureModule` 要求 pg（`MessagingInfrastructureModule.java:36-39`）。首版台账/状态表只做 PG；若 `sys.store.mode != pg`：
  - 派生 Badge 仍可工作（Skill/API/Quality 读取不依赖台账）；
  - 通知与去重禁用，记录启动警告；
  - `AgentHealthModule` 不绑定 `PgAgentReferenceIssueStore`，`AgentHealthService` 以“无台账”模式运行（只派生，不通知、不对账）；
  - 不新增 Mongo collection，避免在 decommission 期间制造新写入。
- 迁移必须 bump 版本；已有迁移不会重跑（项目约定）。
- 不修改 `AgentDefinition` 实体，不做 Agent 文档回填；只回填台账（首次 sweep 静默）。

### 5.5 API 示例

**列表响应（节选）：**

```json
{
  "agents": [
    {
      "id": "agent-1",
      "name": "Order Analyst",
      "status": "PUBLISHED",
      "tools": [
        {"id": "api-operation:order-service:refund:create", "type": "API", "source": "order-service"}
      ],
      "skill_ids": ["skill-deleted"],
      "health": {
        "status": "ATTENTION",
        "needs_attention": true,
        "reference_issues": [
          {
            "ref_type": "SKILL",
            "ref_id": "skill-deleted",
            "ref_name": "pdf-report",
            "state": "BROKEN",
            "reason": "SKILL_NOT_FOUND",
            "sources": ["DRAFT", "PUBLISHED"],
            "in_draft": true,
            "in_published": true,
            "message": "Skill \"pdf-report\" no longer exists."
          },
          {
            "ref_type": "API",
            "ref_id": "api-operation:order-service:refund:create",
            "ref_name": "order-service > refund > create",
            "state": "BROKEN",
            "reason": "API_TOOL_NOT_FOUND",
            "sources": ["PUBLISHED"],
            "in_draft": false,
            "in_published": true,
            "message": "API tool was removed after a Service API resync."
          }
        ],
        "quality": {
          "state": "FAILED",
          "run_id": "run-9",
          "mode": "PRECHECK",
          "gate_result": "REJECTED",
          "block_fail_count": 2,
          "incomplete_count": 0,
          "warn_count": 1,
          "exempted": false,
          "checked_at": "2026-11-24T09:30:00Z",
          "definition_revision": 12,
          "current_revision": 12,
          "policy_revision": 3,
          "policy_changed": false,
          "content_changed": false,
          "message": "Last quality check failed: 2 blocking issues."
        }
      }
    }
  ],
  "total": 1
}
```

**不可验证示例：**

```json
{
  "status": "UNKNOWN",
  "needs_attention": false,
  "reference_issues": [
    {
      "ref_type": "API",
      "ref_id": "api-operation:order-service:refund:create",
      "state": "UNVERIFIED",
      "reason": "API_TOOL_UNVERIFIED",
      "sources": ["DRAFT"],
      "in_draft": true,
      "in_published": false,
      "message": "API tool reference could not be verified because the catalog is unavailable."
    }
  ],
  "quality": {"state": "UNKNOWN", "message": "Quality status could not be read."}
}
```

## 6. 前端与交互

### 6.1 Agent 列表

当前列表行渲染在 `AgentList.tsx:396-447`：Bot icon、名称、StatusBadge、类型、Memory 按钮、更新时间。新增：

- 在名称旁（`AgentList.tsx:419` 之后）渲染 `AlertTriangle`（lucide）图标，条件 `a.health?.needs_attention`。
- 图标颜色：amber/red（与现有 destructive 色一致），不使用纯色块；保持 18px 行高。
- `title`/tooltip 内容由 `health.reference_issues` 与 `health.quality` 生成：
  - 前 3 条：`Skill "x" no longer exists (draft, published).`
  - 质量：`Last quality check failed: 2 blocking issues.`
  - 超出：`and N more.`
- 如果 `reference_issues.length + (quality failed ? 1 : 0) > 1`，在图标旁显示一个小计数（例如 `2`），避免只看到图标不知道有几件事。
- 图标本身不可单独点击（行点击进入编辑器）；`aria-label="Agent needs attention: 2 issues"`。
- `health.status=UNKNOWN` 不显示感叹号；可在编辑器里显示“无法验证”的说明，避免列表出现无法行动的图标。
- 无编辑权限的 viewer 只看到 published issue；有编辑权限的 viewer 即使看 other tab，也可以看到该 Agent 的 draft issue/质量状态。Tooltip 末尾按权限加 `Only the owner can fix this.`。
- 不改变默认排序；后续可加 “Needs attention” 过滤（P2）。自动把坏 Agent 排到前面会让用户以为 Agent 顺序变了，首版不做。
- 列表 health 在进入/刷新页面时重新拉取，不做逐条 SSE 推送；删除/保存后通过通知或下次刷新体现，避免列表行在用户操作时跳动。

### 6.2 Agent 编辑器

当前编辑器已能渲染选中的 tools/skills，并已有逐个移除按钮（`AgentEditor.tsx:1617-1650`、`:2043-2065`）。新增：

1. **顶部健康横幅**（标题下方）：
   - `UNKNOWN`：中性提示 `Some references could not be verified; retry later.`，不标红、不显示 `!`；
   - `ATTENTION`：amber/red 背景，列出最多 5 条 issue；
   - 每条显示 `refName`、`sources`、原因；对 PUBLISHED-only 的条目显示 `Publish the current draft to fix the published version.`；
   - 对 DRAFT/PUBLISHED 都有的条目显示 `Remove it from the draft, then republish.`；
   - 右侧提供 `Review references` 按钮，滚动到 Tools/Skills 区。
2. **Skill chip**：`skill` 找不到时（`allSkills` 里没有该 id）改为红色边框/红色圆点，文字用 `health` 里的 `refName` 或 id；移除按钮已有。
3. **API tool chip**：`health.reference_issues` 中 `refType=API` 且 `inDraft=true` 的 id，对应 chip 标红；移除按钮已有。
4. **保存前提示**：如果仍有 DRAFT broken ref，保存按钮旁显示 `Fix broken references before saving.`；Skill 的保存会被服务端 `requireAccessibleSkills` 拒绝，提前提示比等 403 好。
5. **发布前提示**：如果 `health.reference_issues` 中存在 PUBLISHED issue 或 DRAFT issue，Publish 按钮弹确认/警示；实际阻断仍由质量门禁的 `CFG.REFERENCE_AVAILABLE`（以及发布路径的其它校验）决定，前端不假装是权威。
6. **修复后的刷新**：`update`/`publish` 响应已经带 `health`，用返回值更新本地 `agent.health`；列表在下次进入时重新拉取。不要在前端自己推断“移除后一定好了”（published 可能还在）。

### 6.3 质量失败展示

- 编辑器已有 Check Quality / Publish 按钮与 `QualityReportPanel`（`AgentEditor.tsx:677-691`、`:1043-1083`）。新增一个持久横幅（与健康横幅分开或合并为同一区域）：
  - `FAILED`：`Last quality check failed (run <id>, <time>): 2 blocking issues.` + `View report` + `Recheck`。
  - `UNAVAILABLE`：`Last quality check could not complete: required checks are unavailable.` + `Recheck`。
  - `PASSED`：不显示横幅；如果 `policyChanged=true` 或 `contentChanged=true`，显示轻量提示 `Policy/content changed since the last check; recheck before publishing.`。
  - `NONE`：不显示；未检查不是失败。
- 质量横幅不应在保存后自动消失（§3.4.3）；保存只清空本地报告详情，不改变“最近一次运行”的事实。
- `View report` 调 `GET /api/agents/:id/quality-checks/latest`；`Recheck` 调现有 `POST /api/agents/:id/quality-checks`，成功后用返回的 report 更新横幅，并 refetch `GET /api/agents/:id` 或用 report 推导 `health.quality`（两者必须一致）。
- 列表 Badge 与编辑器横幅使用同一 `health.quality` 数据，避免列表说失败、编辑器说没问题。

### 6.4 通知中心

- `Notifications.tsx:156-201` 已渲染 type/category/title/message/agent_id。新增：
  - `AGENT_ATTENTION` 的颜色（amber）与 label（`AGENT ATTENTION` 或 `Needs attention`）。
  - 当 `n.type === 'AGENT_ATTENTION' && n.agent_id` 时，整条通知可点击：`navigate('/agents/' + n.agent_id)`，并调用 `markRead`。
  - 如果没有 `agent_id`（理论上首版不会），保持不可点击。
- 通知消息包含 Agent 名称与 ref 名称；不需要前端再查 Agent。
- SSE 在线推送沿用现有 `notification` channel；离线用户下次打开通知中心/未读计数即可看到。
- 不在通知里放“Fix”按钮直接修改 Agent，避免从通知跳转后误操作；只跳转编辑器。

### 6.5 可访问性与文案

- 不用颜色作为唯一信号：`!` 图标 + `title` + `aria-label`；色盲用户仍能通过形状和文本理解。
- 图标 `role="img"`，`aria-label` 包含 issue 数量与最高优先级原因。
- 文案使用产品现有英文 UI；服务端 `message` 首版英文，后续可接 i18n。中文设计文档不直接作为 UI 文案。
- Tooltip 限长：最多 3 条 + `and N more`，避免遮挡列表；完整列表在编辑器横幅。
- 图标不要放在可点击按钮内部，避免嵌套交互冲突。

## 7. 服务架构与关键流程

### 7.1 组件与依赖

| 组件 | 职责 | 依赖 | 不依赖 |
|---|---|---|---|
| `AgentHealthService` | 派生 health、批量 probe、issue upsert、通知聚合、对账入口 | `AgentDefinitionStore`、`SkillStore`、`ApiToolCatalogService`、`AgentQualityStore`、`QualityPolicyService`、`PermissionService`、`UserStore`、`AgentReferenceIssueStore`、`NotificationService` | `AgentDefinitionService`（避免循环） |
| `AgentReferenceIssueStore` / `Pg...` | issue 台账与对账状态 | `Database`（PG） | Agent/Quality/Notification 服务 |
| `AgentHealthReconcileJob` | 周期全量对账、回填、恢复/清理 | `AgentHealthService` | 业务请求路径 |
| `AgentHealthModule` | 绑定上述组件、setter 注入钩子、注册 job | 后加载模块 | 不改变现有模块顺序 |
| `AgentDefinitionService` | 在 full view 上挂 `health`；保存/发布/删除后触发钩子 | 可空 `AgentHealthService` | 不把 health 写回实体 |
| `SkillService` | 删除后回调健康服务 | 可空 `AgentHealthListener` | 不直接依赖 `AgentHealthService` 实现 |
| `ToolRegistrySyncJob` / `ToolRegistryService` | reload 成功回调 | 可空 `Runnable` 监听器 | 不依赖健康服务 |

**为什么用 setter/监听器而不是构造注入：**

- `ServerApp` 现有顺序是 `AgentDefinitionModule`（第 248 行）→ `ToolRegistryModule`（250）→ `McpHubModule` → `WebFoundationModule` → `ApiToolHubModule`（257）→ … → `NotificationModule`（277）。
- `AgentHealthService` 需要 `ApiToolCatalogService`（257 才绑定）和 `NotificationService`（277 才绑定），不能在 248 绑定。
- 因此 `AgentHealthModule` 应在 `NotificationModule` 之后加载；由它调用：
  - `agentDefinitionService.setHealthService(bean(AgentHealthService.class))`
  - `skillService.setHealthListener(agentHealthService::onSkillDeleted)`
  - `toolRegistryService.setApiCatalogReloadListener(agentHealthService::onApiCatalogReloaded)`
- 这些 setter 在启动完成后、请求到来前生效；未注入时（部分单元测试/裁剪部署）相关功能退化为“无 health/无通知”，不影响原有路径。

### 7.2 列表健康快照流程

```text
list(userId, request)
  ├─ 现有查询/分页/权限
  ├─ summary/picker? ──是──▶ 直接返回（不计算 health）
  ├─ 否
  │   ├─ viewerCanManage = admin || agent.manage（每请求一次；owner 在 per-agent 判断中追加）
  │   ├─ canSeeQuality = viewerCanManage || page 中存在 viewer 自己拥有的 Agent
  │   ├─ batchSkillIds = Σ(draft.skillIds + published.skillIds)
  │   ├─ skillNames = skillStore.findNames(batchSkillIds)  ──失败──▶ skills UNVERIFIED
  │   ├─ issueNames = 存在缺失 Skill 时才查
  │   │     ? issueStore.listActiveByAgentIds(pageAgentIds)  // 只用于 refName，失败回退 id
  │   │     : empty
  │   ├─ catalog = apiToolCatalog.successSnapshot()        ──失败──▶ API UNVERIFIED
  │   ├─ qualityRuns = canSeeQuality
  │   │     ? qualityStore.findLatestCompletedRuns(pageAgentIds, {PRECHECK,PUBLISH,FORK})  ──失败──▶ quality UNKNOWN
  │   │     : empty
  │   ├─ policyRevision = canSeeQuality ? qualityPolicy.activeRevision() : null
  │   └─ for each agent: build AgentHealthView（§3.2–3.4；quality 仅 owner/admin/manage）
  └─ map view.health
```

**查询数量（每页）：**

- 1 次 Agent 分页（现有）；
- 1 次 Skill `findNames`（批量）；
- 1 次 issue 台账 `listActiveByAgentIds`（仅存在缺失 Skill 时；只补 `refName`，失败回退 id，不影响状态）；
- 0 次 API DB（内存快照；首次访问可能触发一次 reload）；
- 1 次 Quality batch（仅 canSeeQuality）；
- 1 次 policy head（仅 canSeeQuality）；
- 0 次 Agent 健康写入。

目标：20 条 Agent 的列表新增 p95 < 20ms（不含原有查询）。如果 Skill/Quality 批量查询超时，按 §4.5 降级，不阻塞列表。

### 7.3 Skill 删除流程

```text
SkillWebServiceImpl.delete(id)
  └─ SkillService.delete(id)
       ├─ load entity (name)
       ├─ skillStore.delete(id)
       ├─ blobStore.deleteReplaced(...)
       ├─ invalidateCatalog()
       └─ healthListener.onSkillDeleted(id, entity.name, actorUserId)
            ├─ agentStore.findReferencingAgents(id)   // draft + published
            ├─ for each agent: upsert issue(s)
            ├─ notifyUnnotified(agent)
            └─ failures: log only; reconcile job compensates
```

要点：

- 删除是同步成功返回；健康检测失败不回滚删除。
- 如果受影响 Agent 数超过阈值，钩子改为 `executor.submit`，删除请求只记录任务；此时列表 Badge 仍是派生值，用户马上能看到，通知稍后到。
- `actorUserId` 目前 `SkillService.delete` 没有参数；需要从 `SkillWebServiceImpl` 透传（`AuthContext.userId`）或仅用于日志，首版可不用于收件人过滤。

### 7.4 Service API reload 流程

```text
ToolRegistrySyncJob.execute
  ├─ syncDatabaseTools()
  └─ reloadApiToolsIfChanged()
       ├─ snapshot = serviceApiService.configSnapshot()
       ├─ if unchanged: return
       ├─ mcpServerService.reload()
       ├─ ok = toolRegistryService.reloadApiTools()   // 改为返回成功/失败
       ├─ if !ok: log; keep lastApiSnapshot; return   // 下个 tick 重试
       ├─ lastApiSnapshot = snapshot
       └─ apiCatalogReloadListener.run()
            └─ AgentHealthService.onApiCatalogReloaded()
                 ├─ agents = agentStore.findAgentsWithApiRefs()
                 ├─ for each ref: check against successful catalog
                 ├─ upsert/resolve issues; notify
                 └─ failures: log only; reconcile compensates
```

- `ApiToolCatalogService` 需要暴露 `successSnapshot()`（含 `loadedAt`/`healthy`），而不是把失败伪装成空 snapshot；snapshot 内增加 `byRefId`/app/service → operation 计数索引，让 app/service/operation 三种 ref 都能 O(1) 判定。
- `ToolRegistryService.reloadApiTools()` 目前只返回 void 并 catch；需要改为 `boolean reloadApiTools()` 或抛异常，供 job 判断。
- `mcpServerService.reload()` 失败时 job 已 catch 并跳过；不触发健康对账。
- reload 成功后先 invalidate `ApiToolCatalogService`，再调用健康 listener，保证对账读到新快照而不是旧缓存。
- 多 Pod 同时 reload/对账：幂等；通知去重。

### 7.5 Agent 保存 / 发布 / 删除流程

```text
update(id, request, userId)
  ├─ 现有 CAS / 校验
  ├─ replaceIfRevision(...)
  ├─ notifyCatalogChanged()
  ├─ health = healthService.snapshot(entity, userId)      // 同步，供本次响应
  ├─ healthService.onAgentSaved(entity, userId)           // 可异步；台账/通知失败不影响返回
  └─ toView(entity, userId, health)

publish(id, userId)
  ├─ 现有质量门禁 + 发布写入
  ├─ health = healthService.snapshot(result.entity(), userId)
  ├─ healthService.onAgentSaved(result.entity(), userId)  // 可异步
  └─ toView(..., health)

delete(id, userId)
  ├─ agentDefinitionStore.delete(id)
  ├─ healthService.onAgentDeleted(id)                     // 可异步
  └─ notifyCatalogChanged()
```

`onAgentSaved` 与 `snapshot` 都会重新计算当前坏引用；重复计算是刻意取舍：保存响应必须拿到最新派生 health，台账/通知则可以晚一点、失败也不影响保存。若性能实测显示重复计算不可接受，可让 `onAgentSaved` 返回 snapshot 并复用。

`onAgentSaved` 的逻辑：

1. 用新实体计算当前 draft + published 的 broken refs（Skill 批量 + API 内存）。
2. 对当前 broken 的 `(refType, refId)` upsert active issue。
3. 对该 Agent 所有 active issue 中不在当前 broken 集合的：
   - 如果 ref 已不在任一配置中 → `RESOLVED/REMOVED`；
   - 如果 ref 仍在配置中但目标已恢复 → `RESOLVED/RESTORED`。
4. 对本轮 `notifiedAt=null` 的 issue 聚合成一条通知。
5. 失败只记录；Badge 读取仍正确。

### 7.6 模块与源码改动范围

| 模块/文件 | 改动 | 说明 |
|---|---|---|
| `core-ai-api/.../agent/AgentHealthView.java` | 新增 | health 顶层 |
| `core-ai-api/.../agent/AgentReferenceIssueView.java` | 新增 | 单个 issue |
| `core-ai-api/.../agent/AgentQualityStatusView.java` | 新增 | 质量摘要 |
| `core-ai-api/.../agent/AgentDefinitionView.java` | 加 `health` | 可选字段，向后兼容 |
| `ai/core/server/agenthealth/AgentHealthService.java` | 新增 | 派生 + 钩子 + 通知聚合 |
| `ai/core/server/agenthealth/AgentReferenceIssue.java` | 新增 | 台账 domain（PG JSONB） |
| `ai/core/server/agenthealth/AgentReferenceIssueStore.java` | 新增 | 接口 |
| `ai/core/server/agenthealth/PgAgentReferenceIssueStore.java` | 新增 | PG 实现 |
| `ai/core/server/agenthealth/AgentHealthReconcileJob.java` | 新增 | 周期对账 |
| `ai/core/server/agenthealth/AgentHealthModule.java` | 新增 | 绑定/setter/job |
| `ai/core/server/agent/AgentDefinitionService.java` | list/get/update/publish/delete 集成 health | 不写实体 |
| `ai/core/server/agent/AgentDefinitionStore.java` + PG/Mongo | 新增反向查询/投影 | 见 §5.3 |
| `ai/core/server/agent/AgentDefinitionService.java`（view 挂载） | 在 service 层设置 `view.health` | helper 保持纯转换，避免 favorites 意外计算 |
| `ai/core/server/skill/SkillService.java` | 删除后回调 | setter 注入 listener |
| `ai/core/server/web/SkillWebServiceImpl.java` | 透传 actorUserId（可选） | 现有 delete 无 actor |
| `ai/core/server/tool/ToolRegistryService.java` | reload 返回成功/失败；reload listener | 现有 invalidator 模式 |
| `ai/core/server/schedule/ToolRegistrySyncJob.java` | 仅在成功时更新 snapshot/触发对账 | 修掉“失败也更新” |
| `ai/core/server/apitoolhub/ApiToolCatalogService.java` | 暴露成功快照/加载状态；增加 byRefId/app/service 索引 | 失败不返回空 |
| `ai/core/server/quality/AgentQualityStore.java` + PG/Mongo | batch latest completed runs（summary 投影） | 见 §5.3 |
| `ai/core/server/quality/domain/AgentQualityRun.java` | 新增 `blockFailCount/incompleteCount/warnCount`（nullable） | 避免列表加载完整 issues |
| `ai/core/server/quality/PublishQualityGateService.java` | `finishRun` 写入计数 | 与 run 结果同一次写 |
| `ai/core/server/quality/QualityPolicyService.java` | `activeRevision()` | 轻量读取 |
| `ai/core/server/notification/NotificationService.java` | `createIfAbsent` | dedupeKey |
| `ai/core/server/notification/NotificationStore.java` + PG/Mongo | `insertIfAbsent` | 唯一键 |
| `ai/core/server/domain/Notification.java` | `dedupeKey` | `dedupe_key` |
| `ai/core/server/domain/NotificationType.java` | `AGENT_ATTENTION` | 新类型 |
| `core-ai-server-db-migration/.../V53__agent_reference_health.sql` | 新增 | §5.2 |
| `core-ai-frontend/src/api/client.ts` | 类型 | AgentHealth 等 |
| `core-ai-frontend/src/pages/agents/AgentList.tsx` | Badge/tooltip | §6.1 |
| `core-ai-frontend/src/pages/agents/AgentEditor.tsx` | 横幅/chip/质量 | §6.2–6.3 |
| `core-ai-frontend/src/pages/notifications/Notifications.tsx` | 类型/跳转 | §6.4 |
| 测试 | 见 §10.4 | 单元/集成/E2E/前端 |

**不做的事：**

- 不修改 `AgentDefinition` 实体，不新增 `health` 字段；
- 不修改 `AgentPublishedConfig` / `AgentQualityReceipt`；
- 不把通知写进 `chat_sessions` / traces；
- 不改 Agent 列表默认排序；
- 不做跨 Pod SSE。

## 8. 可观测性、性能与容量

### 8.1 指标与日志

指标（低基数；不带 agentId/skillId）：

- `agent_health_snapshot_total{result=ok|attention|unknown}`
- `agent_health_probe_failure_total{component=skill|api|quality|issue_store}`
- `agent_health_issue_detected_total{ref_type=skill|api}`
- `agent_health_issue_resolved_total{ref_type=skill|api,resolution=removed|restored|agent_deleted}`
- `agent_health_notification_total{ref_type=skill|api}`
- `agent_health_notification_deduped_total`
- `agent_health_reconcile_duration_ms`、`agent_health_reconcile_agents`
- `agent_health_backfill_issues`

日志：

- `INFO` issue detected/resolved：agentId、refType、refId、sources、reason；
- `INFO` notification created：agentId、issueCount、dedupeKey 前缀；
- `WARN` probe unavailable / store failure，携带动作名与耗时；
- `ERROR` notification/issue upsert failure，带异常摘要；不要每个 Agent 打一行导致日志风暴，按批次聚合。

### 8.2 性能预算

| 路径 | 预算 | 保障 |
|---|---|---|
| Agent 列表 full view（20 条） | 新增 p95 < 20ms | 批量 Skill/Quality；API 内存；无每 Agent DB 查询 |
| Agent 详情/保存/发布 | 新增 p95 < 10ms | 单 Agent 批量；同样复用 catalog |
| Skill 删除钩子（≤200 引用者） | p95 < 500ms | 索引反向查询；issue 批量 upsert；通知异步可接受 |
| API reload 对账（≤2000 带 API 引用 Agent） | p95 < 2s | 投影 + 内存对照；分批；超时下一 tick |
| 周期全量对账 | 单 tick < 10s（core-ng 警告线） | 500/页 + 时间预算内多页；游标；只处理有引用的 Agent |
| 通知创建 | 单 Agent < 20ms | 按 Agent 聚合；dedupe 唯一键 |

**不做的优化（首版）：** 不缓存 health 结果；不建反向引用索引表；不把 health 写入 Agent。先证明列表批量查询足够快；如果不够，再加基于 `definitionRevision + catalogGeneration + latestRunId` 的短 TTL 缓存。

### 8.3 容量与批量

- 假设 10k Agent、每个 5 条引用：一次全量投影约 10k 小行，分 20 页；每页一次 Skill 查询。可接受。
- 一个 Skill 被 5k Agent 引用：删除钩子会产生 5k issue + 最多 5k 通知（按 Agent 聚合后仍可能 5k 条）。首版设阈值（如 500）：
  - ≤500：逐 Agent 通知；
  - \>500：只写 issue + 记录 `agent_health_fanout_capped`，通知降级为“管理员摘要”或不发；Badge 仍对每个 Agent 生效。
  - 阈值可配置；不要因为一次批量删除打爆通知表。
- 通知消息长度限制在 4KB 内；超出截断并写 `and N more`。

### 8.4 失败矩阵（运维视角）

| 场景 | 用户看到 | 系统行为 | 恢复 |
|---|---|---|---|
| Skill 删除钩子失败 | Badge 仍显示（派生） | issue 可能缺失；日志 error | 周期对账补 issue/通知 |
| API reload 失败 | 不显示新的 `!`（API 维度 UNVERIFIED） | 保留上一份成功快照；不更新 snapshot 版本 | 下一 tick 重试；修复后对账 |
| API catalog 成功但为空 | 所有 API ref 显示 `!` | 视为确认缺失；通知 | 修复 `service_api` 配置后自动消失 |
| 通知表不可用 | Badge 正常；通知延迟 | issue `notifiedAt=null` | 下一轮对账重试 |
| 台账表不可用 | Badge 正常；无通知 | 记录错误；对账跳过通知部分 | 恢复后回填/重试 |
| 质量 store 不可用 | 无质量 `!`；`UNKNOWN` | 列表降级 | 恢复后自动恢复 |
| 多 Pod 同时对账 | 无重复 Badge | 唯一键/dedupe 去重 | 无需处理 |
| Agent 在检测中被删除 | 无 Badge | issue resolve/忽略 | 无 |
| Agent 在检测中被编辑 | 下一次读取纠正 | 本次快照可能短暂不一致 | 无 |

## 9. 安全、权限与隐私

### 9.1 可见性

- `AgentDefinitionService.list` 已按 `AGENT_VIEW` 权限和可见性 scope 过滤（`AgentDefinitionWebServiceImpl.java:57-61`）；健康信息只跟随已可见的 Agent，不扩大 Agent 可见范围。
- Draft issue / quality 状态只对以下受众返回：
  - `entity.userId == viewerUserId`（owner）；
  - `user.role == "admin"`；
  - `PermissionService.has(viewerUserId, "agent.manage")`（能编辑共享 Agent 的用户）。
- Published issue 对所有能读取该 Agent 的受众返回；它影响实际执行，隐瞒反而有害。
- 列表接口由 `@PermissionsRequired(AGENT_VIEW)` 保护；健康字段不新增路由。

### 9.2 通知收件人

- 只发给 `AgentDefinition.userId`（owner）。不发给所有 `AGENT_MANAGE` 用户，避免“一个 Skill 删除，全公司收到通知”。
- 通知内容包含 Agent 名称、Skill/API 名称、原因；不包含其他用户信息、session、trace。
- 如果 owner 已删除/停用，通知无法送达；Badge 仍由 admin/其他可见者看到。后续可加“owner 缺失时发给 admin”规则（§11 Q9）。
- 通知不包含 API 请求参数、Skill 内容、prompt；只包含引用身份。

### 9.3 数据最小化

- issue 台账只存 ref 身份、来源、时间、resolution；不存 Agent 配置快照、prompt、Skill 内容。
- `refName` 是展示名称；Skill 删除后名字来自删除前的实体，不额外保留内容。
- API ref 的 `refName` 由 id 解析（`app > service > operation`），不读 Service API payload。
- 日志不打印完整 Agent 文档或 Skill 内容。

## 10. 验收、实施计划与风险

### 10.1 验收矩阵

| ID | 场景 | 预期 |
|---|---|---|
| A1 | Skill 被删除，Agent draft 引用 | 列表 `!`；tooltip 显示 Skill；owner 收到通知；issue active |
| A2 | Skill 被删除，仅 published 引用 | 列表 `!`（所有可见者）；编辑器提示“重新发布”；draft 无该引用 |
| A3 | Skill 被删除，draft + published 都引用 | 一条 issue，sources 两项；移除 draft + 保存后仍显示（published），重新发布后消失 |
| A4 | 用户从 draft 移除失效 Skill 并保存 | 保存成功；Badge 消失（若 published 也无）；issue `REMOVED` |
| A5 | 用户从 draft 移除但未重新发布（published 仍有） | Badge 保留；编辑器提示 Publish；重新发布后消失 |
| A6 | API operation 被 resync 删除，draft 引用 | 列表 `!`；通知；issue active |
| A7 | API operation 恢复 | 下一轮对账/成功 reload 后 Badge 自动消失；issue `RESTORED` |
| A8 | API catalog 加载失败 | 不显示 `!`；`health.status=UNKNOWN`；不发通知 |
| A9 | Skill store 查询失败 | 不显示 Skill `!`；`UNKNOWN`；不发通知 |
| A10 | 质量 PRECHECK 失败 | 列表 `!`；quality `FAILED`；工具提示 run/计数 |
| A11 | 质量 CHECK 通过 | Badge 消失；quality `PASSED` |
| A12 | 质量失败后编辑内容 | Badge 保留；`contentChanged=true`，编辑器提示重检 |
| A13 | 质量策略变化 | `policyChanged=true`；Badge 状态不变；编辑器提示重检 |
| A14 | 质量运行 WORKFLOW 失败 | 不影响 Badge（首版排除） |
| A15 | 质量运行 STARTED/ABORTED | 不影响 Badge |
| A16 | 多 Pod 同时检测同一 Skill | 只有一条 active issue、一条通知 |
| A17 | 首次部署回填 | 生成 issue，但不发历史通知；Badge 可见 |
| A18 | 通知插入失败 | issue `notifiedAt=null`；下一轮重试；不重复 |
| A19 | Agent 删除 | issue `AGENT_DELETED`；通知历史保留 |
| A20 | summary/picker 列表 | 不返回 health；性能不变 |
| A21 | 共享 Agent，viewer 无编辑权限 | 只显示 published issue；不显示 draft/quality |
| A22 | 列表 20 条，其中 10 条有 5 条 issue | 响应正确；p95 新增 <20ms |
| A23 | 台账表不可用 | 列表 Badge 正常；无通知；错误日志 |
| A24 | API reload 失败后下一次 tick | 重试 reload；成功后对账 |

### 10.2 实施分阶段

| 阶段 | 内容 | 交付/验收 |
|---|---|---|
| P0 | 本文档 Review、拍板 §11 决策 | 用户确认 |
| P1 | 只读 health：API view、`AgentHealthService` 派生、list/get 集成、Skill/API 判定、质量状态 | A1–A5、A10–A15、A20–A22 的只读部分；前端 Badge + 编辑器横幅 |
| P2 | 检测/通知：issue 台账、Skill 删除钩子、通知聚合/dedupe、Agent 保存/发布/删除钩子 | A1–A5、A16–A19、A23 |
| P3 | API resync：reload 成功判定、`onApiCatalogReloaded`、success snapshot、API 对账 | A6–A8、A24 |
| P4 | 对账/回填/运维：`AgentHealthReconcileJob`、静默回填、指标、阈值/容量 | A7、A17、A23 |
| P5 | 体验增强：删除前影响预览、Needs attention 过滤、MCP/subagent 扩展 | 单独设计/评审 |

**依赖顺序：** P1 不依赖台账，可以先上线让用户看到 Badge；P2/P3 才需要通知与台账。P2/P3 上线时 `backfillDone=false`，只写 issue/不发通知；P4 完成首次全量 sweep 后再打开通知，避免历史问题通知风暴。这样即使通知链路有问题，核心价值（列表说真话）已经交付。

### 10.3 风险登记

| ID | 风险 | 等级 | 缓解 |
|---|---|---|---|
| R1 | catalog 加载失败被当成空，产生全量假 `!`/通知风暴 | 高 | 成功快照 + `UNVERIFIED` + 首部署静默回填 + 失败不发通知（§3.2.2、§4.4） |
| R2 | 多 Pod 重复通知 | 中 | active 唯一键 + 通知 dedupeKey（§4.3） |
| R3 | 列表性能退化 | 中 | 仅 full view；批量 Skill/Quality；API 内存；p95 预算（§8.2） |
| R4 | 旧失败在编辑后长期保留，用户以为当前内容失败 | 中 | tooltip/编辑器明确 revision 与“需重检”；后续可加“编辑清除”开关（§11 Q3） |
| R5 | published-only 问题用户不知道怎么修 | 中 | 编辑器文案明确“重新发布”；P5 可加一键发布引导 |
| R6 | 删除钩子 fanout 过大 | 中 | 阈值 + 管理员摘要/降级；Badge 仍生效（§8.3） |
| R7 | 台账与派生状态漂移 | 低 | Badge 不读台账；台账只做通知/审计（§3.5） |
| R8 | Mongo 模式无台账 | 低 | Badge 可用；通知禁用；当前环境已 PG（§5.4） |
| R9 | 质量运行模式新增后未纳入白名单 | 低 | 显式白名单 + 测试；新模式需评审 |
| R10 | 通知内容泄露跨用户信息 | 低 | 只发 owner；只含引用身份（§9.2） |

### 10.4 测试策略

**单元测试：**

- `AgentHealthServiceTest`：Skill draft/published/两者；API operation/app/service 缺失；type 冲突；catalog/ Skill/quality store 失败 → UNVERIFIED/UNKNOWN；质量状态矩阵（NONE/PASSED/FAILED/UNAVAILABLE/UNKNOWN/exempted/policyChanged/contentChanged，含“发布成功后 contentChanged=false”）；受众过滤（owner/admin/manage/其他）。
- `AgentReferenceIssueStoreTest` / `PgAgentReferenceIssueStoreTest`：active 唯一键、upsert、resolve、episode 重开、notifiedAt 重试。
- `QualityRunQueryTest`：最近完成运行（排除 STARTED/ABORTED/WORKFLOW）、`finishedAt` 排序。
- `SkillServiceTest`：删除触发 listener；listener 异常不影响删除。
- `ToolRegistrySyncJobTest`：reload 成功才更新 snapshot/触发 listener；失败重试。
- `ApiToolCatalogServiceTest`：成功/失败快照、空 catalog 与失败区分。
- 一致性测试：同配置下，health 的 SKILL/API 结果 vs `QualityReferenceResolver` 过滤结果一致（MCP 除外）。

**集成测试（PG 容器/H2 视现有测试设施）：**

- 删除 Skill → 反向查询 → issue/通知；重复执行只一条通知。
- 修改 Service API payload 删除 operation → reload → 对账 → issue；恢复 operation → resolve。
- Agent 更新移除引用 → issue resolve；添加失效 API ref → issue。
- 首次回填不通知；回填后新 issue 通知。

**前端测试：**

- `AgentList`：有 health/无 health/UNKNOWN/多 issue；`!` 显示与 tooltip；共享 Agent 受众。
- `AgentEditor`：横幅、红色 chip、移除后本地状态、published-only 文案、质量横幅/NONE/PASSED/FAILED/UNKNOWN。
- `Notifications`：`AGENT_ATTENTION` label/color/click-through。

**E2E（dev）：**

- 按项目约定，server 改动完成后必须跑 dev 环境 E2E（`feedback/dev-e2e-deploy-workflow.md`）；设计文档不执行。
- 场景：创建 Skill + 引用它的 Agent → 删除 Skill → 列表 `!` + 通知 → 移除引用 + 保存 → `!` 消失；发布版本再验证一次。
- API resync：修改 `service_api` 删除 operation → 等待 30s job → 列表 `!`；恢复后消失。
- 质量：Check Quality 失败 → 列表 `!`；修复 + 重新检查通过 → 消失。

**性能测试：**

- 20/50 条列表，10% Agent 有 5 条 issue；对比无 health 的 p95。
- 200/2000 个 Agent 带 API 引用的 reload 对账耗时。

## 11. 关键决策与未决事项

### 11.1 ADR 摘要

| ID | 决策 | 选择 | 放弃的方案 | 理由 |
|---|---|---|---|---|
| ADR-1 | Badge 形态 | 读取时派生 | Agent 上存 `health_status` / 台账驱动 | 防漂移、避免 CAS 冲突、资源恢复自动清除 |
| ADR-2 | 引用失效处理 | 检测 + 提示 + 用户修复 | FK/RESTRICT、CASCADE 删除引用、软删除 tombstone | 跨存储、resync 不可阻止、不能静默改用户配置 |
| ADR-3 | 首版范围 | Skill + API tool | MCP/subagent/dataset/prompt 全量 | 可用性语义不同；质量门禁已覆盖发布时检查 |
| ADR-4 | Draft/Published | 双视图都检查 | 只看 draft 或只看 published | published 是执行契约；draft 是下一次发布契约 |
| ADR-5 | API 检测方式 | 当前 catalog vs 当前引用对账 | 单 Pod reload diff、逐 removed id 查询 | 多 Pod/重启不可靠；app 级删除会产生大量查询 |
| ADR-6 | 通知去重 | issue 台账 + notification dedupeKey | 只靠 SSE、只靠内存、只靠通知是否已读 | 多 Pod、离线、重试 |
| ADR-7 | 质量 Badge | 最近完成的 PRECHECK/PUBLISH/FORK，失败粘到 PASS | 实时重跑、按当前 revision 自动清除、WORKFLOW 也纳入 | 无新证据不撤销警告；避免消费者触发噪声 |
| ADR-8 | 失败偏置 | UNVERIFIED/UNKNOWN 不显示 `!`、不发通知 | 加载失败当空 catalog | 防全量假警报 |
| ADR-9 | 存储 | PG 台账；Badge 不依赖台账 | Mongo 双实现、Agent 字段 | 当前环境 PG；decommission 中不新增 Mongo 写入 |
| ADR-10 | 自动修复 | 不做 | 自动删除引用、自动下架、自动重新发布 | 静默改行为、绕过并发/审计 |
| ADR-11 | 通知聚合 | 按 Agent + 检测批次聚合 | 每个 ref 一条、每条删除事件群发 | 降噪；仍能定位到 Agent |

### 11.2 决策状态台账

| ID | 状态 | 说明 |
|---|---|---|
| ADR-1 | 提议，待用户确认 | 若用户坚持“存状态、只有删除才清除”，需要增加补偿/回填设计 |
| ADR-2 | 提议，待用户确认 | 若用户希望阻止删除，需要单独设计引用确认流程 |
| ADR-3 | 提议，待用户确认 | MCP/subagent 可作为 P5 |
| ADR-4 | 提议 | 本文强依赖此决策 |
| ADR-5 | 提议 | 需要修 `reloadApiTools` 返回值与 catalog 失败状态 |
| ADR-6 | 提议 | 需要新增 notification `dedupe_key` 迁移 |
| ADR-7 | 提议，待用户确认 | 见 Q3 |
| ADR-8 | 提议 | 无争议的安全偏置 |
| ADR-9 | 提议 | 当前环境事实支持 |
| ADR-10 | 提议 | 无争议 |
| ADR-11 | 提议 | 阈值见 §8.3 |

### 11.3 待 Review 决策

| ID | 问题 | 本文建议 | 影响 |
|---|---|---|---|
| Q1 | 资源恢复后，Badge 是否自动清除？用户字面要求“直到删除引用” | 自动清除（派生值） | 若坚持粘性，需要把 Badge 改成台账状态 + 补偿任务 |
| Q2 | 通知收件人是否只发 owner？ | 只发 owner | 共享 Agent 的协作者可能先发现；可后续加“最近编辑者” |
| Q3 | 质量失败是否因编辑而清除？ | 不清除，保留到后续 PASS；标注需重检 | 若用户希望编辑即清除，改为比较 revision |
| Q4 | WORKFLOW 失败是否纳入质量 Badge？ | 首版不纳入 | 纳入会增加作者不可控的噪声 |
| Q5 | 删除 Skill 前是否做影响预览？actor 自操作是否免通知？ | P5 做预览；首版仍通知 | 体验优化，不影响核心正确性 |
| Q6 | 是否首版就覆盖 MCP server / subagent？ | 否 | 需要额外的可用性语义/持久化 tool 列表 |
| Q7 | 大于阈值的 fanout 如何处理？ | 写 issue + 管理员摘要；不发全量 | 需要确定阈值与摘要通道 |
| Q8 | 是否增加 “Needs attention” 列表过滤/排序？ | P5，先不加自动排序 | 影响列表导航与分页实现 |
| Q9 | owner 已删除/停用时通知发给谁？ | 首版只发 owner；后续 owner 缺失时发给 admin | 影响通知送达率与管理员负担 |

## 附录 A：用户原始需求与文档治理

### A.1 用户原始需求（2026-11-24，原文保留）

> 在apitool被resync并有apitool被删除后/skill被删除的时候 去检测所有对他们有引用的agent，然后提示用户这个agent的skill或者tool引用不存在了，并且在agent的列表上加个!展示这个信息，知道用户把agent的这个失败引用删除。另外如果agent触发了quality检测，但是没通过，也显示感叹号标识。还是参考design-persistent-workspace文档来做设计文档。也是从第一性原理出发整理需求和设计取舍

### A.2 本次采用的结构约束

- 采用 `design-persistent-workspace.md` 的文档骨架：DRAFT 元数据、结论摘要、阅读指南、需求追踪、不变量、边界、ADR、源码核对、Review 与修改历史。
- 附录 B 只记录**设计时已核对**的源码事实；未验证的假设明确标注“待实现时确认”。
- 本文是设计文档，不代表任何实现已批准或已完成；不修改业务代码、不创建迁移、不部署。
- 评审记录只陈述 AI 自检意见，不写成“用户批准”。

### A.3 维护规则

- 新增/修改检测规则、状态语义、API 字段时，同步更新 §3、§5、§10、§11。
- 任何“派生 vs 持久化”的变更必须先改 ADR-1，再改实现。
- 实现阶段若发现源码事实与本附录不符，先更新附录 B，再更新设计结论。

## 附录 B：设计时源码核对

### B.1 Agent 与引用模型

| 事实 | 位置 |
|---|---|
| Agent 文档包含 `tools`、`skillIds`、`publishedConfig`、`definitionRevision`、`qualityReceipt` | `core-ai-server/src/main/java/ai/core/server/domain/AgentDefinition.java:46`、`:61-62`、`:88-92`、`:126-127` |
| 发布配置是冻结副本，含 `tools`、`skillIds`、`skillValidationVersion` | `core-ai-server/src/main/java/ai/core/server/domain/AgentPublishedConfig.java:39-50` |
| 每次条件写入 bump `definitionRevision` | `core-ai-server/src/main/java/ai/core/server/agent/PgAgentDefinitionStore.java:203-214` |
| 列表 full view 与 summary/picker 分支 | `core-ai-server/src/main/java/ai/core/server/agent/AgentDefinitionService.java:124-181`、`AgentListHelper.java:36-61` |
| 详情/保存/发布/质量检查入口 | `AgentDefinitionService.java:255-259`、`:280-335`、`:337-355`、`:357-361` |
| Draft Skill 校验在保存/发布/质量检查前执行 | `AgentDefinitionService.java:294`、`:347-359`、`:376-380` |
| View 组装（list 去掉 prompt/schema） | `AgentViewHelper.java:76-104`、`:131-151` |
| 质量 receipt 字段 | `core-ai-server/src/main/java/ai/core/server/domain/AgentQualityReceipt.java` |

### B.2 Skill 删除与解析

| 事实 | 位置 |
|---|---|
| 删除先取实体（name），再删 store/blob，再 invalidate catalog | `core-ai-server/src/main/java/ai/core/server/skill/SkillService.java:230-236` |
| Skill 解析只要求存在，不做调用者权限校验 | `SkillService.java:401-412` |
| 批量名称查询可作为存在性集合 | `SkillService.java:385-389`；`PgSkillStore.java:79-92` |
| Repo sync 只更新内容，不删除记录 | `SkillService.java:261-375`；`SkillRepoSyncJob.java:65-82` |
| Web delete 入口 | `core-ai-server/src/main/java/ai/core/server/web/SkillWebServiceImpl.java:129-130` |

### B.3 Service API 同步与工具解析

| 事实 | 位置 |
|---|---|
| `service_api` 删除/更新/上游同步 | `core-ai-server/src/main/java/ai/core/server/apimcp/serviceapi/service/ServiceApiService.java:61-63`、`:65-95`、`:146-158` |
| `configSnapshot` 用 count + max updatedAt 检测变化 | `ServiceApiService.java:183-194` |
| `loadAll` 跳过 disabled app/service/operation，失败跳过 app | `core-ai-server/src/main/java/ai/core/server/apimcp/serviceapi/service/ApiDefinitionService.java:35-48`、`:63-66`、`:92-96` |
| API ref id 形态与 `loadByToolId` | `core-ai-server/src/main/java/ai/core/server/tool/InternalApiToolLoader.java:36-42`、`:139-160` |
| 运行时缺失 API ref 静默跳过 | `core-ai-server/src/main/java/ai/core/server/tool/ToolRefResolver.java:297-312`；`ToolRefResolutionService.java:374-385` |
| 测试明确 stale exact ref 被跳过 | `core-ai-server/src/test/java/ai/core/server/tool/ToolRefResolutionServiceTest.java:505-514` |
| Sync job 每 30s 检查并 reload | `core-ai-server/src/main/java/ai/core/server/schedule/ToolRegistrySyncJob.java:29-49`；`ToolRegistryModule.java:28` |
| reload 内部 catch，调用方无法判断成功 | `core-ai-server/src/main/java/ai/core/server/tool/ToolRefResolutionService.java:251-262` |
| Catalog 失败时返回空 snapshot | `core-ai-server/src/main/java/ai/core/server/apitoolhub/ApiToolCatalogService.java:127-151` |
| Catalog refId 构造 | `ApiToolCatalogService.java:158` |
| 前端 API ref 形态与显示名 | `core-ai-frontend/src/pages/agents/AgentEditor.tsx:512-534`、`:603-614` |

### B.4 质量门禁与运行记录

| 事实 | 位置 |
|---|---|
| 质量运行字段（mode/runStatus/gateResult/revision/hash/created/finished） | `core-ai-server/src/main/java/ai/core/server/quality/domain/AgentQualityRun.java:37-90` |
| Gate result 只有 PASSED/REJECTED/UNAVAILABLE | `quality/domain/QualityGateResult.java` |
| Gate mode 含 PRECHECK/PUBLISH/WORKFLOW/FORK | `quality/domain/QualityGateMode.java` |
| Precheck 创建并完成 run，不强制抛错 | `core-ai-server/src/main/java/ai/core/server/quality/PublishQualityGateService.java:60-63`、`:68-89` |
| Run 持久化与完成 | `PublishQualityGateService.java:116-142`、`:204-220` |
| 发布成功写 receipt（runId/gateResult/hash） | `core-ai-server/src/main/java/ai/core/server/agent/AgentPublishService.java:28-59`、`:61-76` |
| 质量运行查询（latest/list） | `core-ai-server/src/main/java/ai/core/server/quality/PgAgentQualityStore.java:177-186` |
| 策略读取每次 load head+policy+rules | `core-ai-server/src/main/java/ai/core/server/quality/QualityPolicyService.java:140-157` |
| 质量引用解析（SKILL/API/MCP/...） | `core-ai-server/src/main/java/ai/core/server/quality/QualityReferenceResolver.java:58-68`、`:113-124`、`:226-236` |
| 引用可用性规则与文案 | `core-ai-server/src/main/java/ai/core/server/quality/ReferenceAvailableRule.java:17-43`、`:52-68` |
| WORKFLOW 触发 gate 的位置 | `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowAgentSnapshotService.java:85-100` |
| FORK 触发 gate 的位置 | `core-ai-server/src/main/java/ai/core/server/agent/PersonalAssistantService.java:125-155` |
| 编辑器 Check Quality / 面板 / 保存清空报告 | `core-ai-frontend/src/pages/agents/AgentEditor.tsx:677-691`、`:1043-1083`、`:700` |

### B.5 通知与存储

| 事实 | 位置 |
|---|---|
| Notification 实体字段 | `core-ai-server/src/main/java/ai/core/server/domain/Notification.java:18-49` |
| NotificationService 创建/列表/已读 | `core-ai-server/src/main/java/ai/core/server/notification/NotificationService.java:26-70` |
| SSE publisher 是 in-process、每 user 一个 channel | `core-ai-server/src/main/java/ai/core/server/notification/NotificationEventPublisher.java:12-18`、`:36-57` |
| PG notifications 列与 insert | `core-ai-server/src/main/java/ai/core/server/notification/PgNotificationStore.java:20-23`、`:44-47` |
| NotificationType 现有值 | `core-ai-server/src/main/java/ai/core/server/domain/NotificationType.java` |
| 通知中心渲染/agent_id 展示 | `core-ai-frontend/src/pages/notifications/Notifications.tsx:156-201` |
| 三环境 `SYS_STORE_MODE=pg` | `docs/cn/design-mongo-decommission.md:27` |
| messaging 强制 pg | `core-ai-server/src/main/java/ai/core/server/MessagingInfrastructureModule.java:36-39` |
| Agent 表 DDL/索引 | `core-ai-server-db-migration/src/main/resources/db/migration/V16__agents.sql:1-24` |
| Skill 表 DDL/索引 | `V9__skills.sql:1-12` |
| 质量表 DDL/索引 | `V38__agent_quality_gate.sql:39-63` |
| 最新迁移版本 V50 | `core-ai-server-db-migration/src/main/resources/db/migration/` |
| core-ng scheduler 每 Pod 执行，无分布式锁 | `d:\core-ng-project\core-ng\src\main\java\core\framework\internal\scheduler\Scheduler.java:117-126` |
| ServerApp 模块顺序 | `core-ai-server/src/main/java/ai/core/server/ServerApp.java:128-307` |

### B.6 前端落点

| 事实 | 位置 |
|---|---|
| AgentDefinition 前端类型 | `core-ai-frontend/src/api/client.ts:627-664` |
| Agent 列表行渲染 | `core-ai-frontend/src/pages/agents/AgentList.tsx:396-447` |
| 选中 tools chips 与移除 | `AgentEditor.tsx:1617-1650` |
| 选中 skills chips 与移除 | `AgentEditor.tsx:2043-2065` |
| 质量按钮/面板/receipt 展示 | `AgentEditor.tsx:677-691`、`:1043-1083`、`:2157-2158` |
| 通知中心渲染 | `Notifications.tsx:156-201` |

### B.7 设计时未验证 / 未执行

- 未实现任何代码，未创建迁移，未启动 dev/UAT/prod。
- 未验证 `ApiToolCatalogService` 在真实 load 失败时的现有行为对健康检查的影响；结论基于源码的 catch/return empty 逻辑，实施前需加测试锁定。
- 未验证 `app.agents` 表在真实环境的数据量/索引创建锁时长；实施前需在 dev 观察。
- 未验证 `Notification` PG 表新增 `dedupe_key` 的唯一索引对现有数据/写入的影响；设计上为 nullable + partial unique index，理论安全，仍需迁移测试。
- 未验证列表 20 条 + health 的实际 p95；预算为设计目标，实施后需压测。
- 未验证 `QualityPolicyService.activeRevision()` 的最优实现；可能直接读 `AgentQualityPolicyHead`。

## 附录 C：Review 与修改历史

### C.1 用户 Review

- 2026-11-24：用户提出需求；尚未对本文方案、ADR 或待决事项作出批准。
- 本文状态保持 DRAFT，直到用户明确选择 §11.3 的 Q1/Q3/Q4 等关键决策。

### C.2 AI 自检记录（非用户批准）

| 日期 | 角色 | 结论 | 处理 |
|---|---|---|---|
| 2026-11-24 | core-ai（AI 自检） | 需求覆盖：Skill 删除、API resync、通知、列表 Badge、质量 Badge 均有落点 | 已完成 |
| 2026-11-24 | core-ai（AI 自检） | 关键源码事实已核对：Agent 双配置、Skill 删除、API 静默跳过、质量运行持久化、通知 in-process、模块顺序 | 见附录 B |
| 2026-11-24 | core-ai（AI 自检） | 主要风险：假警报、通知风暴、多 Pod 重复、列表性能、published-only 修复路径 | 见 §8.4、§10.3 |
| 2026-11-24 | core-ai（AI 自检） | 需要用户拍板：资源恢复是否清 Badge、质量失败是否因编辑清除、WORKFLOW 是否纳入 | 见 §11.3 |

### C.3 修改记录

| 版本 | 日期 | 修改 | 原因 |
|---|---|---|---|
| v0.1 | 2026-11-24 | 初稿：第一性原理、需求/不变量、派生 Badge、双视图、检测/通知/台账、API/前端/实施计划 | 用户需求 |

### C.4 设计时未执行的事项

- 未修改 `AgentDefinition` / `AgentPublishedConfig` / 质量门禁 / 通知实现。
- 设计阶段未创建迁移；实现阶段已新增 `V53__agent_reference_health.sql`。
- 设计阶段未运行 `./gradlew`、前端测试或 dev E2E；实现阶段的验证记录见 §10.4 与 `docs/validation/`。
- 未部署、未发版。
