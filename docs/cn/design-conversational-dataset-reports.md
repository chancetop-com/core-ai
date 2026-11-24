# Core AI Server 对话式 Dataset 报表与个人首页钉选 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft / 待用户验收；P0/P1 实现完成，dev E2E 通过；AI 评审记录保留 |
| 文档版本 | v0.7（领域设计 + P0/P1 实现；dev 2.0.303 已验收） |
| 创建日期 | 2026-11-19 |
| 需求提出者 / Reviewer | 用户；AI 技术评审独立记录，非用户批准 |
| 作者 | core-ai |
| 范围 | 对话创建/修改动态报表；Agent 直接保存；报表管理页；钉选到 `/for-you`；查询、刷新、权限与失效处理 |
| 本次交付 | 设计文档 + 报表后端、管理页、首页钉选 P0/P1 实现；dev 2.0.303 E2E 已通过 |
| 参考方式 | 参考 Agent Gate 的治理结构、服务端强制校验、结构化错误与验收矩阵；不引入发布门禁，也不采用其 proposal/commit 审批模型 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> core-ai-server现在有dataset的能力，agent在定时执行任务之后可以落库一些数据，现在我希望用户可以通过跟agent对话创建报表，然后可以把报表钉到用户自己的首页，方便他们每天查看，你参考agent gate的design文档，做一个设计

### 0.2 需求编号

| ID | 需求 | 性质 |
|---|---|---|
| R1 | 利用已有 Dataset 中由定时 Agent 产出的数据 | 原始需求 |
| R2 | 用户通过与 Agent 对话创建报表，不必手写查询或配置图表 | 原始需求 |
| R3 | 用户可以选择将报表钉到自己的首页 | 原始需求 |
| R4 | 每日查看时展示新的 Dataset 数据，不是创建时的一次性截图 | 对“每天查看”的解释，待确认 |
| R5 | 个人首页与报表按用户隔离；报表不绕过数据源权限 | 必要安全约束 |
| R6 | 支持对话修改、预览确认、取消钉选、排序和查看详情 | 首版完整体验提案 |
| R7 | 保留原始需求、ADR、待 Review 决策、Review 与修改记录 | 沿用 Agent Gate 文档治理约定 |
| R8 | 仅设计；用户确认关键决策后实施 | 工作边界 |
| R9 | Agent 直接创建/修改报表并保存；报表在管理页展示；Pin、删除、排序由用户操作 | 用户澄清 U-004（2026-11-19） |

**2026-11-19 用户澄清（U-004，原文见 §14.1）：**R2 的“创建”指 Agent 直接生成并保存报表，不需要在聊天里再点一次保存；R3 的“钉到首页”以及 R6 的“取消钉选、排序、删除”由用户在报表管理页/报表详情页操作。R6 的“预览确认”解释为用户在管理页查看已保存报表，不是提交前的审批门禁。原 R2/R3/R6 文字保留，不覆盖。

### 0.3 文档维护规则

- 原始需求不覆盖；用户新增要求单独编号。
- API、类名、表结构、预算和 SLO 中的新增部分均为提案，不代表已实现或已经实测。
- 用户 Review 与 AI 技术评审分开记录；AI 评审不构成实施授权。
- 实质修改更新版本、ADR 和修改记录；相关说明集中在本文。
- 早期数据集与报表设想（已删除，见 git 历史）描述早期数据管道与报表方案；本文是**已有 Dataset 能力之上的增量设计**，不改写历史，也不照搬其中过时的 Mongo、自动 extraction、Map DTO 和“所有报表自动上首页”等假设。

## 1. 目标、边界与核心提案

### 1.1 核心提案

建立两个独立对象：

1. **DatasetReport**：用户拥有的、可重复执行的报表定义，包括数据源、时间窗口、查询口径和展示配置；由 Agent 工具创建/修改并直接保存。
2. **HomeReportPin**：用户个人首页引用哪个报表、如何排序与展示；只由用户在管理页操作。不复制数据，不触发 Agent 执行。

完整链路：

```text
定时 Agent ──已有写入能力──> Dataset records
                                │
用户与 Agent 对话                │
  → 识别数据源、字段和统计口径     │
  → 生成受限 ReportSpec           │
  → 服务端验证 + 实际查询预览 <────┘
  → Agent 创建/修改报表并保存（revision）
  → 报表出现在 /reports 管理页
  → 用户查看、Pin/取消 Pin、排序、删除
  → /for-you 使用同一个 ReportRenderer
  → 每次打开 / 刷新执行定义，取得当前数据
```

**LLM 负责理解意图、创建/修改报表定义；服务端负责校验、授权、查询与持久化；报表管理页和 Pin 由人类操作。Agent 工具不提供 Pin、删除或排序；这些动作由用户在管理页发起。日常刷新不调用 LLM，不重新执行采集 Agent。**

### 1.2 目标与不变量

- 同一报表在聊天卡片、详情页和首页采用相同配置协议、查询引擎和渲染组件。
- 保存的是查询定义，不是生成出来的一份 HTML、Markdown 表格或历史结果快照。
- Agent 直接创建/修改本人的报表；只有用户在管理页显式钉选的报表进入首页；取消钉选/删除由用户操作。
- 所有入口执行相同服务端授权与校验；Agent 工具只能创建/修改本人的报表，owner 来自可信上下文；HTTP API 必须校验 owner，客户端提交的 `user_id` 忽略/禁止。
- 查询只读、范围有界、预算有界；错误和超时不伪装成 0 或“暂无数据”。
- 报表创建/修改先校验并预览；修改用 `revision` CAS，失败保留原定义；Pin/layout 用独立 `layout_revision` CAS，两类并发分开。
- 数据缺失、延迟与查询缓存过期是不同状态；卡片清晰展示时间范围、统计口径与数据时间。
- Agent 工具白名单不包含 Pin/删除/排序；这些动作的产品入口在报表管理页。

### 1.3 非目标

- 不重新设计 Dataset 创建、定时调度、Agent 落库或 extraction；接入现有能力即可。
- 首版不做任意 SQL / JavaScript / Python 报表脚本，不嵌入 Agent 生成的活动 HTML。
- 不引入外部 BI 系统、独立查询集群、消息总线或新的报表刷新 Scheduler。
- 首版不做跨 Dataset join、公共分享、团队看板、行级业务 ACL、邮件订阅或 PDF 导出。
- 不把 AI 文字分析当成实时图表刷新的一部分；已有文字报告保持原能力。
- 本文首版首页指 server Web 的 `/for-you`，不顺带重做 desktop 原生首页。
- **不做 proposal / approval receipt / human commit 流程。**Agent 创建/修改报表属于用户授权范围内的工具调用，人类控制点是管理页的 Pin/删除/排序，而不是聊天里的二次确认。
- **首版不做报表版本回滚/历史对比。**只保留 `revision` 与审计摘要；需要回滚时另立需求。

### 1.4 后续条目（不在 v1）

- **多 Dataset / 多源报表**：v1 固定单 Dataset；`spec.source.dataset_id` 为单值，管理页的 `Dataset` 也只展示单值。跨 Dataset join、多源合并和各源独立筛选留作后续需求，不在本次实现。

## 2. 方案依据与取舍

| 依据 | 采用 | 不照搬 / 理由 |
|---|---|---|
| Agent Gate 设计 | 原始需求与稳定编号；服务端确定性校验；revision CAS、结构化错误与验收矩阵；模型不直接操作首页 Pin | 报表不是 Agent 发布，不挂 `QualityRuleEvaluator`，不为私人报表跑 Decision 模型；按用户 U-004 不采用其 proposal/commit/approval receipt 流程 |
| Vega-Lite 声明式可视化 [S1] | 数据查询与展示描述分离、版本化 spec、字段到图形的映射 | 使用现有 Recharts，不新增 Vega runtime；只定义本产品需要的受限协议 |
| PostgreSQL 权限 / RLS [S2] | 应用授权与数据库隔离是不同层；只读角色、RLS 必须验证真实作用域 | 现有自由 SQL executor 有开关且授权粒度为 Dataset，不能当成个人数据隔离的证明；首版不用自由 SQL |
| 当前 Dataset store 抽象 | 利用数据库聚合，不在浏览器拉全部 records 再求和 | 当前接口不足以直接表达半开时间窗、稳定 LAST 和结果预算，必须补齐契约，不能只套一层 controller |

## 3. 现状核对与接入约束

以下为 2026-11-19 工作区实际源码核对，不代表线上开关状态。

| 当前事实 | 源码定位 | 设计约束 |
|---|---|---|
| 登录后的默认首页为 `/for-you` | `core-ai-frontend/src/App.tsx:165` | 钉选接入 ForYou，不接入运维 Dashboard |
| ForYou 有最近会话、文件、文字报告、TODO 和 token usage | `core-ai-frontend/src/pages/for-you/ForYou.tsx:134`；`core-ai-server/src/main/java/ai/core/server/web/foryou/ForYouService.java:45` | 新增独立“我的报表”区域，保留现有模块 |
| `UserReport` 存 title/content/type/tags，无 Dataset 查询定义 | `core-ai-server/src/main/java/ai/core/server/domain/UserReport.java:11`；`core-ai-server/src/main/java/ai/core/server/web/foryou/PgUserReportStore.java:17` | 新增 DatasetReport，不用重载 `content` 或猜测 `type` 来混存动态报表 |
| Dataset 有 GENERAL / SESSION；记录 data 为 JSON String | `core-ai-server/src/main/java/ai/core/server/domain/DatasetType.java:12`；`core-ai-server/src/main/java/ai/core/server/domain/DatasetRecord.java:38` | 首版只支持 GENERAL；不能将 SESSION state 当业务报表数据 |
| Store 可选 mongo / mongo-native / postgresql | `core-ai-server/src/main/java/ai/core/server/DatasetModule.java:46` | 首版动态报表能力只在 PostgreSQL Dataset 后端开启；其他后端显式返回不支持，不隐式全量扫描 |
| PostgreSQL Dataset 元数据为 `app.datasets`，含 schema_version | `core-ai-server/src/main/java/ai/core/server/dataset/store/PgDatasetMetaStore.java:23` | 记录所依赖字段的 fingerprint；不是只凭 Dataset 的更新时间判失效 |
| RecordService 已有 query / aggregate | `core-ai-server/src/main/java/ai/core/server/dataset/DatasetRecordService.java:175`；`:209` | 复用 store 接口与 schema 校验，聚合下推数据库 |
| 聚合已有 SUM/AVG/MIN/MAX/COUNT/LAST，bucket 固定使用 run_started_at；上界为 <=，LAST 未指定 ID tie-break | `core-ai-server/src/main/java/ai/core/server/dataset/store/DatasetAggregationSql.java:23`；`:62`；`:83`；`:104` | 新契约明确 `[from,to)`、时间字段及确定性 LAST，旧客户端语义不静默改变 |
| Agent 访问 Dataset 受绑定白名单和 READ/WRITE/FULL 控制 | `core-ai-server/src/main/java/ai/core/server/dataset/tool/DatasetAccessRegistry.java:24`；`core-ai-server/src/main/java/ai/core/server/dataset/tool/DatasetToolProvider.java:26` | 报表工具必须使用会话白名单；不将全部 Dataset 自动绑定给普通 Agent |
| 顶层会话、run/subagent 的 Dataset 组装已有共享 helper | `core-ai-server/src/main/java/ai/core/server/session/SessionDatasetHelper.java:23`；`:64` | 报表 provider 接入共同组装路径；不能只支持一个聊天入口 |
| Web Dataset API 使用全局 dataset.view/manage，list/get 未按创建者隔离 | `core-ai-server/src/main/java/ai/core/server/web/DatasetWebServiceImpl.java:61`；`:73` | **Dataset.userId 目前是创建者，不等于私有 ACL**；首版复用当前全局 RBAC，报表私人不意味着数据源私人 |
| ForYou controller 使用 dashboard.view，服务按 userId 取个人对象 | `core-ai-server/src/main/java/ai/core/server/web/foryou/ForYouWebServiceImpl.java:39` | 新报表和 Pin 在服务层必须独立校验 owner，不能只靠注解 |
| Rich card 已支持表格、快捷回复、表单，但表单提交是普通用户消息 | `core-ai-frontend/src/pages/chat/components/CustomEventBlocks.tsx:56`；`:154` | 报表创建/修改由 Agent 工具调 ReportService，聊天 JSON 不作为操作凭证；卡片只做展示和导航，不承载 Pin/删除 |
| 已依赖 Recharts | `core-ai-frontend/package.json:35`；`core-ai-frontend/src/pages/for-you/ForYou.tsx:3` | 复用依赖和主题；不引入第二套图表库 |
| SPA route 由 WebModule 读取 SpaRoutes 统一注册 | `core-ai-server/src/main/java/ai/core/server/WebModule.java:50` | 新详情页路由同步加入 SpaRoutes，不按早期设计去改 ServerModule 静态数组 |
| Web 登录返回长期 API key，前端存 localStorage 并以 Bearer 发送 | `core-ai-server/src/main/java/ai/core/server/auth/AuthService.java:107`；`core-ai-frontend/src/api/client.ts:9`；`core-ai-frontend/src/api/auth.ts:44` | 本设计不依赖 human-action 确认；Agent 工具在服务端上下文创建/修改报表，owner 不由模型提交；Pin/删除是普通用户 UI 操作，安全边界是 owner + RBAC |
| `AuthInterceptor` 只按 `cst_` 前缀把 sandbox token 限制在 hub；bearer 认证的内部用户会被写入 session identity；`X-Auth-Request-Email` 被无条件信任 | `core-ai-server/src/main/java/ai/core/server/web/auth/AuthInterceptor.java:88`、`:123`；`core-ai-server/src/main/java/ai/core/server/web/auth/RequestAuthenticator.java:132`；`core-ai-server/src/main/java/ai/core/server/web/CorsInterceptor.java:38`、`:104` | `cst_` 限制使 sandbox agent 不能直接调用非 hub HTTP；Agent 走工具白名单；Pin/删除沿用平台认证，不新增 human-action 承诺；`X-Auth-Request-Email` 为平台级风险另记 |
| 工作区未发现用户时区设置、CSRF 防护或通用报表审计表；现有审计为 `HubCallAuditService` 的 hub 调用审计 | `core-ai-server/src/main/java/ai/core/server/hub/`（按 grep 结果，具体类未逐一展开） | timezone 需新增用户设置或对话确认；Pin 是普通认证 API，CSRF 风险与现有 API 相同；审计表在 §7 定义 |

## 4. 产品流程与首页体验

### 4.1 对话创建

示例：用户说“把最近 30 天每天的评论数和好评率做成报表”。

1. Agent 查看当前会话可读的 GENERAL 数据源元数据，确定 Dataset 与字段；只请求有限样本，不下载全量数据。
2. 若名称歧义、时间口径、汇总规则或重跑记录含义不明确，先询问，不靠模型猜测。
3. Agent 可先调用 `preview_dataset_report` 验证配置并查看有限预览；预览不保存。
4. Agent 调用 `create_dataset_report`，提交 title/description/spec；服务端校验、用真实数据预览、确认 owner 来自上下文后保存（revision 1）。创建不包含 Pin。
5. 聊天显示 `report_created` 卡片：标题、数据源、时间范围、口径、打开报表链接；卡片可附有限预览，但以 `/reports/:id` 为准。
6. 用户可继续说“改成七天”“用柱状图”；Agent 调用 `update_dataset_report(report_id, expected_revision, 新配置)`，服务端重新校验/预览并按 revision CAS 更新，revision + 1。
7. 用户到 `/reports` 查看、Pin/取消 Pin、排序、删除。聊天不提供“保存”或“保存并钉选”按钮，Agent 也没有 Pin/删除/排序工具。用户如果说“钉到首页”，Agent 创建报表后引导到 `/reports/:id`，由用户点击 Pin。

**首版确认方式：Agent 直接保存 + 管理页人工操作。**Agent 创建/修改属于用户授权范围内的工具调用，不是人类审批；人类通过管理页的 Pin、删除和排序控制展示与生命周期。若未来需要“防伪造 human action”，另立平台机制，不在首版范围。创建前 Agent 应用一句话说明将保存什么；用户不满意可在管理页删除。

### 4.2 修改已有报表

- 首页 / 详情页“与 Agent 修改”启动带 `report_id` 的报表上下文；服务端加载当前定义与 source，不由客户端提交 owner。
- 普通聊天中修改已有报表时，用户必须明确指定报表，Agent 先 `get_dataset_report` 取得当前定义与 revision；未加载上下文时只创建新报表，不猜要改哪一份。
- Agent 调用 `update_dataset_report(report_id, expected_revision, 新配置)`；服务端校验、预览并按 revision CAS 更新；失败保留原定义并返回 current revision。
- 已钉选报表更新后仍保持位置，Pin 不需要重新创建。
- 原创建 Agent 被删除或聊天结束不影响已保存报表；执行不依赖原 Agent。
- 首版不做版本回滚；审计记录每次修改的 actor、revision 与 spec hash。若用户需要恢复旧版本，后续独立设计版本历史。

### 4.3 报表库与个人首页

- `/reports`：个人动态报表列表，含名称、数据源、展示类型、钉选状态；“创建报表”进入报表对话入口；Agent 创建的报表自动出现在这里。
- `/reports/:id`：完整图表、时间范围切换、口径、数据时间、手动刷新、与 Agent 修改、Pin/取消 Pin、删除。
- `/for-you`：标题之后新增“我的报表”；有 Pin 时放在 token usage 之前，旧模块继续保留。无 Pin 时显示轻量入口，不占大块空白。
- 每张卡片提供：打开详情、刷新、与 Agent 修改、取消钉选；不将“删除报表”放在易误点的取消钉选按钮上。
- 布局首版采用 CSS Grid，宽度 HALF/FULL；默认图表 HALF、表格 FULL，窄屏全宽。用上移/下移操作排序，暂不引入自由拖拽画布。
- 时间范围切换是当前查看参数，不默默改默认口径；修改默认值须通过 Agent 更新并保存。首版同一报表最多一个 Pin。
- 用户登出 / 换账号清理缓存；layout 和 pin 以 server 为准，不只存 localStorage。

### 4.4 数据未准备好

- 0 条匹配记录：显示“此范围暂无数据”；不填 0，不让 Agent 生成示例数据假装真实。
- 查询可成功但数据未更新：保留数值，显示源数据时间与“数据可能延迟”。
- 无数据报表可以保存，但 Agent 必须在聊天中显式提示；无时间 watermark 不判断为 fresh，用户可在管理页删除。
- 权限丢失、源被删、字段不兼容：保留卡片占位及取消钉选入口，停止显示缓存数据，提供有权执行的修复路径。

## 5. 领域模型与组件边界

### 5.1 领域模型

**边界与术语**

- **Report 限界上下文**拥有：报表定义、个人首页 Pin/layout、报表审计。它不拥有 Dataset records，不执行采集 Agent，不保存查询结果快照。
- **Dataset 是外部聚合**：Report 只引用 `dataset_id`，读取其 schema 与 records；Dataset 删除不级联删除报表。
- **UserReport 是另一个既有聚合**：静态文字报告，不与 DatasetReport 共用表，也不通过 `content`/`type` 混存动态报表；首页读模型可并列展示。
- **没有 Proposal / Draft / Published 状态**：Agent 创建即为 ACTIVE；没有提交审批、候选快照或 human commit。
- **读模型不属于实体**：`ReportDataEnvelope`、报表摘要、首页卡片是查询结果，不持久化为报表状态。

**分层**

| 层 | 内容 | 依赖约束 |
|---|---|---|
| domain | `DatasetReport`、`HomeReportLayout`、值对象、`ReportSpecValidator`、`PinLayoutPolicy` | 不依赖 core-ng、Web、DB；只表达业务规则 |
| application | `ReportService`、`ReportQueryService`、`HomeReportService`、`ReportAccessPolicy` | 依赖 domain + 仓储接口；定义事务边界 |
| infrastructure | PG 仓储、Dataset query adapter、审计存储 | 实现仓储接口；处理 SQL/锁 |
| interface | `ReportWebService`、`HomeReportWebService`、`ReportToolProvider` | 只调用 application；不直接写仓储 |

**聚合**

| 聚合根 | 内部实体 / 值对象 | 生命周期 |
|---|---|---|
| `DatasetReport` | `ReportId`、`UserId`、`ReportTitle`、`ReportDescription`、`ReportSpec`、`ReportRevision`、`SpecVersion`、`EngineVersion`、`ReportDependency`、`OriginRef` | 创建 → ACTIVE（revision 递增）→ 删除（终态） |
| `HomeReportLayout` | `UserId`、`LayoutRevision`、`HomeReportPin`（`PinId`、`ReportId`、`Position`、`Width`） | 空 → 有 Pin；Pin 增删、排序、宽度变更 |

`DatasetReport` 与 `HomeReportLayout` 是两个聚合，不互相持有对象引用；只通过 `ReportId` 关联。Pin 状态不放在 `DatasetReport` 里，报表摘要通过读模型 join。

```text
User 1 ── 0..1 HomeReportLayout 1 ── 0..20 HomeReportPin ──> DatasetReport
User 1 ── 0..100 DatasetReport ──> Dataset（外部聚合，只读引用）
```

Key 关系：`HomeReportPin.report_id` 指向 `DatasetReport.id`；`DatasetReport.dataset_id` 指向外部 Dataset。没有 Report → Pin 的级联对象引用，删除由应用服务协调。

**值对象**

- `ReportId` / `UserId` / `DatasetId` / `PinId`：强类型 ID，禁止裸 String 在领域层传递。
- `ReportTitle` / `ReportDescription`：带长度约束的不可变值；title ≤ 120，description ≤ 2000。
- `ReportSpec`：不可变、校验后规范化；包含 `SourceSpec`、`QuerySpec`、`DisplaySpec`、`FreshnessSpec`。
- `QuerySpec`：mode、time_field、time_range、timezone、bucket、category_field、filters、measures、derived_measures、record_fields；字段约束见 §6。
- `TimeRange`：TODAY / YESTERDAY / LAST_N_COMPLETE_DAYS / ROLLING_DAYS / FIXED；解析后得到 `ResolvedRange [from_inclusive, to_exclusive)`。
- `Measure` / `DerivedMeasure`：measure 有唯一 id、label、field、op；derived 首版仅 RATIO。
- `DisplaySpec`：type、x、series、columns、format；format 含 NUMBER / PERCENT / CURRENCY 与 precision。
- `FreshnessSpec`：可选的 `expected_interval_hours`；只用于提示，不证明调度成功。
- `ReportDependency`：dataset_id、dataset schema_version、被引用字段的 name/type/fingerprint。
- `ReportRevision` / `LayoutRevision`：单调递增整数；分别用于报表定义 CAS 与首页布局 CAS。
- `OriginRef`：origin_session_id / origin_agent_id；只作审计与“继续修改”线索，不是运行依赖。

**聚合不变量**

`DatasetReport`：

1. owner 来自可信上下文且创建后不可变；模型不能提交 `user_id`/`owner`。
2. revision 从 1 起，每次成功更新 +1；CAS 冲突不得覆盖。
3. 一份报表只绑定一个 GENERAL Dataset；`spec.source.dataset_id` 必须与 `dataset_id` 一致。
4. spec 必须通过 `ReportSpecValidator`；query/display 必须满足 §6 的兼容矩阵；`spec_json` ≤ 32 KiB UTF-8。
5. 创建/修改前必须成功预览；预览失败不落库、不修改。
6. 依赖快照随 spec 更新；引用字段删除/重命名/类型变化为 `SOURCE_SCHEMA_CHANGED`。
7. 删除是终态；删除为硬删除，`DELETED` 只是审计动作，不保留报表行；删除时同一事务清理本人 Pin 并递增 `layout_revision`。
8. `DatasetReport` 不持有 Pin、首页位置、渲染缓存或查询结果。

`HomeReportLayout`：

1. 每个用户 0..1 个 layout；revision 从 0 起，每次变更 +1。
2. Pin 数量 ≤ 20；`UNIQUE(user_id, report_id)`，同一报表最多一个 Pin。
3. position 连续 0..n-1；width 只能 HALF/FULL；reorder 必须提交当前全部 Pin。
4. 只能 Pin 本人可读的报表定义（report.view）；非本人/不存在统一返回 404。
5. 任何 pin/unpin/reorder/width 变更都在 layout 行锁内完成并做 `expected_layout_revision` CAS；拿到 layout 锁后再校验 report，避免与删除报表竞争。
6. 删除报表由 `ReportService` 协调：锁 report → 锁 layout → 删除 Pin → layout revision +1。
7. layout 不复制 report spec/title；读模型按 report_id join 当前定义。

**执行规则：**不变量由聚合方法在变更前检查；应用服务只负责编排、授权、事务与仓储调用；仓储不替领域做规则判断。Pin 上限/唯一性同时由 `PinLayoutPolicy` 与 DB 约束（`UNIQUE(user_id, report_id)`）双保险。

**领域策略**

| 策略 | 职责 |
|---|---|
| `ReportSpecValidator` | 解析/校验/规范化 spec；字段与 display 兼容、预算、时区、依赖 fingerprint；输出可定位的错误 |
| `PinLayoutPolicy` | Pin 上限、唯一性、位置连续性、宽度、reorder 完整性；只做规则判断，不访问 DB |
| `ReportAccessPolicy` | 从 principal/会话上下文决定 owner、report.view/manage、dataset.view、session binding；不信任模型输入 |

**应用服务与事务**

| 服务 | 职责 | 事务 / 锁 |
|---|---|---|
| `ReportService` | create / update / delete / get / list；校验、预览、CAS、审计；删除时协调 Pin 清理 | create 在用户级锁内检查 100 上限并插入；update 锁 report 行并 CAS；delete 锁 report 再锁 layout |
| `ReportQueryService` | 解析时间窗、执行只读查询、整形、缓存；不修改状态 | REPEATABLE READ 只读事务；不持有写锁 |
| `HomeReportService` | pin / unpin / reorder / width；owner 与 report 可读性检查 | 锁 layout 行并 CAS；拿到 layout 锁后再校验 report，避免与删除竞争；不锁 report |

**仓储**

- `DatasetReportRepository`：`findOwned(reportId, userId)`、`listOwned(userId, offset, limit)`、`insert(report)`、`update(report, expectedRevision)`、`delete(reportId, userId, expectedRevision)`。
- `HomeReportLayoutRepository`：`getOrCreateForUpdate(userId)`、`save(layout, expectedLayoutRevision)`；行锁在仓储实现内，不在 domain。
- `ReportAuditRepository`：`append(entry)`；append-only，不提供 update/delete（清理任务除外）。
- 读模型仓储：`ReportSummaryQuery`（列表/详情，join pin 状态）、`ReportDataQuery`（envelope）；读写分离，读模型不参与聚合写事务。

**生命周期**

```text
DatasetReport:
  (none) --create--> ACTIVE(revision=1)
  ACTIVE(n) --update(CAS)--> ACTIVE(n+1)
  ACTIVE(n) --delete--> DELETED

HomeReportLayout:
  EMPTY(revision=0) --pin--> PINNED(revision+1, n pins)
  PINNED --unpin/reorder/width--> PINNED(revision+1)
  PINNED --delete report--> PINNED(revision+1, pin removed)
```

没有 DRAFT / PROPOSED / PUBLISHED / COMMITTED；没有“先提案后提交”的状态机。

**并发与锁顺序**

- 报表更新：只锁 report 行，按 `expected_revision` CAS。
- Pin/unpin/reorder/width：只锁 layout 行，按 `expected_layout_revision` CAS。
- 删除报表：先锁 report 行，再锁 layout 行；任何路径都不允许先锁 layout 再锁 report，避免死锁。
- Report 更新不触碰 layout；Pin 变更不触碰 report；删除是唯一跨聚合写操作。

**领域事件与审计**

- v1 不引入消息总线、Outbox 或领域事件订阅；审计表是 append-only 的事实记录：`CREATED / UPDATED / DELETED / PINNED / UNPINNED / REORDERED / WIDTH_CHANGED`。
- 若未来需要事件驱动，可从审计表派生；当前不增加投递语义和补偿复杂度。

**包结构（拟）**

```text
ai.core.server.report
  domain/
    DatasetReport, ReportId, ReportTitle, ReportDescription,
    ReportSpec, QuerySpec, TimeRange, Measure, DerivedMeasure,
    DisplaySpec, FreshnessSpec, ReportDependency, ReportRevision,
    HomeReportLayout, HomeReportPin, PinId, Position, Width, LayoutRevision,
    ReportSpecValidator, PinLayoutPolicy
  application/
    ReportService, ReportQueryService, HomeReportService, ReportAccessPolicy
  infrastructure/
    PgDatasetReportRepository, PgHomeReportLayoutRepository,
    PgReportAuditRepository, DatasetReportQueryAdapter
  interface/
    ReportWebServiceImpl, HomeReportWebServiceImpl, ReportToolProvider

ai.core.api.server.report
  ReportWebService, HomeReportWebService, request/view/error DTO
```

**与现有 Dataset 的边界**

- Report 不复制 Dataset records；查询通过 `DatasetReportQueryStore` 读取，强制带 dataset_id。
- Report 不改变 Dataset schema、不触发采集、不拥有 Dataset ACL；首版沿用全局 `dataset.view`。
- Dataset 删除不级联删除报表；报表在下一次查询时返回 `SOURCE_ACCESS_DENIED`/`SOURCE_SCHEMA_CHANGED`，卡片保留并允许取消 Pin。

### 5.2 运行时组件边界

```text
Interactive session
  ReportToolProvider ──> ReportService ──> ReportSpecValidator
                             │                    │
                             └─> ReportQueryService <┘
                                      │
                              DatasetReportQueryStore
                                      │
                            PostgreSQL Dataset records

Human UI (/reports, /for-you)
  ReportWebService ──> ReportService (get/list/delete)
  HomeReportService ──> Pin / Layout / Revision
  ReportRenderer <── ReportDataEnvelope <── ReportQueryService
```

拟新增职责：

| 组件 | 职责 / 禁止事项 |
|---|---|
| ReportAccessPolicy | 解析可信 human/session principal；report owner、RBAC、会话 source 白名单与当前权限检查 |
| ReportSpecValidator | schema、字段类型、query/display 匹配、参数和预算校验；输出 JSON Pointer 错误 |
| ReportService | 创建/修改/删除/查询报表定义；owner 来自上下文；revision CAS、审计；不操作 Pin |
| ReportQueryService | 解析当前时间窗、执行只读查询、结果整形、状态与受限缓存；不调用 LLM |
| DatasetReportQueryStore | Store 的报表查询扩展；明确 SQL 的参数化、超时、limit 与半开区间 |
| HomeReportService | 当前用户 layout/pin 的原子修改和有界批量数据读取；只能由用户 UI 调用 |
| ReportToolProvider | list/get/preview/create/update 工具；不提供 pin、delete、reorder |
| ReportRenderer | Recharts / 表格 / 指标渲染；不执行脚本、不使用任意 HTML spec |

**与已有 Dataset API 的关系：**记录查询、绑定与权限仍由现有能力拥有。需要在 dataset 层增加精确的 report query contract，不在 report 层手写独立、不受契约约束的任意 SQL；通用授权与参数校验共用 policy。报表定义、revision、审计归 report 模块；Pin/layout 归 home 模块。Agent 只能通过 ReportService 创建/修改本人的报表，不能调用 HomeReportService。

## 6. ReportSpec 与精确查询语义

### 6.1 首版协议

`spec_version=1`，一份报表绑定一个 GENERAL Dataset，一个查询结果对应一个展示组件。用户一次要求多个独立图表，Agent 可分次创建多份报表，而不是首版就建设任意 dashboard DSL。

| 部分 | 字段 | 契约 |
|---|---|---|
| source | dataset_id | 必须是真实、当前可读的数据源 ID；名称仅展示 |
| query | mode | AGGREGATE / RECORDS；不能同时提交两者 |
| query | time_field | `meta.run_started_at` / `meta.created_at`，或 `data.<field>` 指向 schema 中类型为 DATE / DATETIME 的业务时间字段；不接受 STRING 日期 |
| query | time_range | TODAY / YESTERDAY / LAST_N_COMPLETE_DAYS / ROLLING_DAYS / FIXED；明确自然日或滚动窗口 |
| query | timezone | 必填 IANA zone；优先用户设置（拟新增），无设置时 Agent 必须询问并由用户确认；不得静默默认 UTC；预览/详情必须显示 |
| query | bucket | NONE / HOUR / DAY / WEEK / MONTH；WEEK 为 ISO 周一开始；DATE 时间字段不支持 HOUR |
| query | category_field | 可选 schema 字段；仅一维分类；可带 `data.` 前缀，服务端归一化为顶层字段名 |
| query | filters | 类型化条件：`field` + `op`（EQ/NE/GT/GTE/LT/LTE/IN，默认 EQ）+ `value`；值按字段类型校验，禁止自由 SQL |
| query | measures | 最多 8 个；唯一 id、label、field、op；op 为 SUM/AVG/MIN/MAX/COUNT/LAST |
| query | derived_measures | 首版仅 RATIO：两个 measure id 相除；不接受表达式字符串，分母 0/null 返回 null |
| query | record_fields | RECORDS 模式白名单字段，最多 20 个；按 time_field DESC、created_at DESC、id DESC 稳定排序与有界分页 |
| display | type | METRIC / LINE / BAR / TABLE |
| display | x / series / columns | 引用查询结果列，不引用不存在的 schema 字段或任意脚本 |
| display | format | NUMBER / PERCENT / CURRENCY；precision 为 0–6；货币必须明确币种 |
| freshness | expected_interval_hours | 可选用户预期采集频率；不由报表服务推断 scheduler 已成功 |

字段路径使用 `meta.*` 与 `data.*` 分离；schema 字段名原样作为参数，不把点路径与标识符字符串拼接进 SQL。首版只允许已声明的顶层 schema 字段，嵌套 JSON 和业务日期 String 解析仍不支持；DATE / DATETIME 业务时间字段是类型化扩展。报表查询编译到 `DatasetViewManager` 生成的 typed view，字段名、类型和索引来自同一份 schema。

**display × query 兼容矩阵（拟）：**METRIC 仅允许 `bucket=NONE`、`category=NONE`、单行；LINE/BAR 必须有 bucket 或 category 之一，不允许同时使用（首版不做 category→series pivot），series 引用 measure/derived；TABLE 允许 AGGREGATE/RECORDS，RECORDS 仅 TABLE。缺失 bucket 由服务端返回 `bucket_axis` 或连续 null 行；首版 series 上限 8。

**时间边界：**写入元数据时间不等于业务发生日期。需要按营业日统计时，数据集 schema 必须把该字段声明为 DATE / DATETIME；引擎不会把 STRING 日期静默当成业务时间，也不会用 `meta.created_at` 替代。DATE 时间字段的范围按报表时区的本地日期比较，桶值转换为该时区午夜的 timestamptz。

### 6.2 配置示例：评论数与加权好评率

以下示例省略可选字段；API 的 `spec_json` 为该对象序列化后的 String。

```json
{
  "spec_version": 1,
  "source": {"dataset_id": "dataset-uuid"},
  "query": {
    "mode": "AGGREGATE",
    "time_field": "meta.run_started_at",
    "time_range": {"kind": "LAST_N_COMPLETE_DAYS", "days": 30},
    "timezone": "Asia/Shanghai",
    "bucket": "DAY",
    "measures": [
      {"id": "positive", "label": "好评数", "field": "data.positive_count", "op": "SUM"},
      {"id": "total", "label": "评论数", "field": "data.total", "op": "SUM"}
    ],
    "derived_measures": [
      {"id": "positive_rate", "label": "好评率", "op": "RATIO", "numerator": "positive", "denominator": "total"}
    ]
  },
  "display": {
    "type": "LINE",
    "x": "bucket",
    "series": [
      {"field": "total", "axis": "LEFT", "format": "NUMBER"},
      {"field": "positive_rate", "axis": "RIGHT", "format": "PERCENT"}
    ],
    "precision": 2
  },
  "freshness": {"expected_interval_hours": 24}
}
```

比例值存储与输出为 0–1，前端百分号格式化，不重复乘 100。不同单位使用左右轴并显式标注；不能把评论数和好评率放在无说明的同一数值轴上。

### 6.3 时间、聚合与缺失值

- 服务端一次取得 `evaluated_at`，按报表时区计算范围，再转换为 UTC instant。所有报表查询使用 `[from_inclusive,to_exclusive)`。
- LAST_N_COMPLETE_DAYS(7)：当地今日 00:00 往前 7 个自然日，到今日 00:00；TODAY：当地今日 00:00 到 evaluated_at；ROLLING_DAYS(7)：evaluated_at 往前 168 小时；FIXED 持久化明确 instant，只用于历史窗口，不作为日常默认。
- DST 自然日可以是 23/25 小时，不用固定 24 小时替代本地日历；bucket 按同一 timezone，响应包含 UTC bucket instant 与显示标签。
- LAST：按选定 time_field DESC、created_at DESC、record.id DESC 取该组确定代表行，即使值 null 也不改取前一行；结果确定，但不宣称 ID 顺序反映业务先后。
- SUM/AVG/MIN/MAX 只接受 NUMBER schema 字段；实际非数字值不强转、不算 0，计入 `invalid_value_count`。COUNT 明确为匹配记录行数，不是 SUM(业务数量)。
- RATIO 在**分组聚合后**计算；加权好评率为 SUM(positive)/SUM(total)，不自动用 AVG(positive_rate)。分母 0/null 显示“—”，可见原因码。
- 无行与有行但全 null 不同；响应包含 matched_record_count、invalid_value_count。服务端返回 `bucket_axis` 或连续 bucket（缺失值为 null），保证折线断开/柱状留空；不能默认补零，也不能让前端自行猜 DST/月初边界。
- 任意 numeric 返回十进制字符串，前端绘图时转换为有限 Number；超出安全表示范围显示提示，不能默默截断精度。非有限值禁止输出。
- source_time=max(选定 time_field)，source_updated_at=max(updated_at)，均按源过滤条件与解析后的时间窗，在同一 REPEATABLE READ 只读事务快照内计算；query evaluated_at 不是源数据时间。
- `expected_interval_hours` 只产生延迟提示，不能证明一个自然日所有门店 / 所有记录均完整。数据覆盖与调度成功证明不在首版契约内。

### 6.4 重跑与查询规模

- 默认每条 record 参与计算；报表不自行按 run_id、日期或名称去重。
- 用户指出 Dataset 存的是每日快照 / 累计数，Agent 应提出 LAST；真正增量记录用 SUM。重跑产生重复行时，需数据管道提供稳定业务主键或明确去重规则，不能凭图表层猜测。
- 时间 bucket 聚合在数据库完成；表格分页在数据库完成，不将 query 的前 1000 条当全集求和。
- 查询结果先按声明口径计算，再检查上限。聚合超限返回 RESULT_LIMIT_EXCEEDED，提示缩短范围 / 降低粒度；不把截断的曲线当完整结果。
- TABLE 支持 record page_size/offset 查看明细，首页固定第一页；响应显示 total 与 has_more。分类柱状图首版超限报错，不偷做“Top N”改变含义。
- Agent 的 `preview_dataset_report` / `create_dataset_report` / `update_dataset_report` 共用同一 query contract；预览失败不保存、不修改。

## 7. 持久化模型

首版新增 PostgreSQL 表，不增加新的 Mongo 集合，也不迁移已有 UserReport。SQL migration 在 `core-ai-server-db-migration` 中使用下一个未占用的 Flyway 版本，不能假定固定 V 编号。

**存储边界：**报表 domain 全部落 PostgreSQL：`app.dataset_reports`、`app.home_report_layouts`、`app.home_report_pins`、`app.dataset_report_audits`；不新增 Mongo collection，不做 Mongo 双写/迁移。`DatasetReport` 等领域类不加 Mongo 注解；PG store 使用 `Database`/SQL/row mapping。报表能力要求 `sys.dataset.store=postgresql` 且 PG 连接可用；非 PG 后端直接 `REPORT_CAPABILITY_UNAVAILABLE`，不存在 Mongo fallback。已有 `UserReport` 保持现有 `sys.store.mode` 选择（mongo 或 pg），不迁移、不重载，也不作为动态报表存储。

### 7.1 DatasetReport

拟新增 `app.dataset_reports`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id / user_id | TEXT | UUID / 报表 owner，服务端生成与绑定 |
| title / description | TEXT | 标题与用途；长度分别 120 / 2000 |
| revision | BIGINT | 从 1 起，所有定义修改 CAS |
| spec_version / engine_version | INTEGER | 协议与执行语义版本；不支持的版本停止执行 |
| spec_json | JSONB | 规范化 ReportSpec，不包含 source 数据行 |
| dataset_id | TEXT | 单源引用，用于查依赖；必须与 spec 一致 |
| dependency_json | JSONB | 所引用 schema 字段名、类型及 fingerprint |
| origin_session_id / origin_agent_id | TEXT nullable | 审计与“继续修改”线索，不是运行依赖 |
| created_at / updated_at | TIMESTAMPTZ | 定义时间，不是数据时间 |

索引 `(user_id,updated_at DESC,id)`、`(dataset_id)`。首版私有，不设置公开 share token。源删除时不级联删报表；因此不设对 Dataset 的 ON DELETE CASCADE。报表删除必须由 ReportService 在同一 layout 锁协议下显式清理 Pin 并递增 layout revision；不依赖 Pin FK cascade。

### 7.2 ReportAudit

拟新增 `app.dataset_report_audits`：id、user_id、report_id、action（CREATE / UPDATE / DELETE / PIN / UNPIN / REORDER / WIDTH）、actor_user_id、origin_session_id、origin_agent_id、revision、spec_hash、details_json、created_at。

- CREATE/UPDATE/DELETE 由 ReportService 在同一事务写入；PIN/UNPIN/REORDER/WIDTH 由 HomeReportService 在同一事务写入。
- `spec_hash` 是规范化 ReportSpec 的 SHA-256；只在定义变更时写，不保存原始数据行、样本值或提示词。
- 保留 90 天，由有界后台维护清理；清理不影响报表或 Pin。
- 审计是“谁在哪个会话/Agent 下改了什么”的摘要，不是完整版本历史；首版不做回滚。

### 7.3 HomeReportPin 与 layout

拟新增 `app.home_report_layouts`：user_id PK、revision、updated_at。

拟新增 `app.home_report_pins`：id、user_id、report_id、position、width(HALF/FULL)、created_at、updated_at。

- `UNIQUE(user_id,report_id)` 防重复 Pin；索引 `(user_id,position,id)`。
- 每次 pin/unpin/reorder/width 变更，在单事务中锁定该用户 layout 行、检查 expected_layout_revision 并递增 revision；首个用户通过 insert-on-conflict 初始化 revision=0。
- reorder 请求必须包含当前全部 pin IDs，不能含其他用户对象；按数组顺序重建 position。冲突返回当前 layout，让客户端刷新重试，不静默覆盖另一设备操作。
- 删除报表在同一 layout 锁协议下完成 Pin 清理和 revision 更新；即使 DB 存在 FK，也不能只依赖它删除而不更新 layout revision。

### 7.4 Java / API 约束

WebService DTO 放在 `core-ai-api`，实现放在 server。ReportSpec 可用固定 typed beans 做内部校验，但异构 JSON 的网络 / SQL 边界使用 `String specJson`、`String rowsJson`；**不用 `Map<String,Object>` 或 `Object` 的 bean 字段**。String 内容必须解析校验，不能因是 String 就绕过 size/schema 限制。内部 Map 的临时解析不等于合法 DTO。

新增 PG store / row 映射遵循 core-ng 约束；模块按 policy/store → query/validator → report/home → controller/provider 的依赖顺序绑定。枚举的实体与 view 注解不混用。

## 8. 授权、并发与失效

### 8.1 权限矩阵

拟新增 `report.view` / `report.manage`，manage implies view；默认 user role 按产品确认后授予，迁移不能覆盖自定义 role。首页 Pin 使用 dashboard.view + report.view，修改 Pin 只影响本人；创建/修改/删除报表需要 report.manage。查看报表数据还需要当前 dataset.view。

| 场景 | 检查 |
|---|---|
| Agent 创建/修改报表 | 可信交互用户；report.manage；dataset.view；会话绑定包含 source 且为 READ/WRITE/FULL；源存在且 GENERAL；owner 来自工具上下文，模型不能提交 user_id/session_id/owner |
| 专用“创建报表”入口 | 用户须有 chat.use/report.manage/dataset.view；展示可见数据源供选择，服务端为选定数据源建立只读绑定；这是显式产品流程，不扩权既有 Agent |
| HTTP 读取 / 数据查询 | 当前用户 owner、report.view、dataset.view；输入中的 user_id 忽略/禁止 |
| HTTP 删除 | 当前用户 owner、report.manage；若已 Pin，同一事务清理 Pin 并递增 layout revision |
| Pin / unpin / reorder / width | 当前用户 owner；dashboard.view + report.view；只影响本人 layout；普通用户 UI 操作，不声称防伪造 |
| Saved report 在首页执行 | report.owner=当前用户；report.view、dataset.view；不依赖原 Agent 是否仍绑定 source |
| 无人值守定时 run | 只继续现有 Dataset 写入；report provider 仅交互式可信用户上下文注册；不在定时 run 中创建/修改报表 |
| 未登录公共分享页 / 别人的 chat | 不提供动态 report 数据与持久化动作，旧卡片最多显示无数据的占位信息 |

首版**不新增 Dataset 私有 ACL**：当前 dataset.view 是全局可读能力，不能仅用 Dataset.userId=创建者改变现有共享行为。Report.userId 与 Pin.userId 则严格为 owner。若今后 Dataset 引入 ACL，ReportAccessPolicy 必须委托同一 source policy；不能让历史报表绕过新规则。

### 8.2 创建、修改与 Pin 的事务

1. `create_dataset_report` 不接受 owner、user_id、confirmed 等客户端授权字段；服务端从工具上下文取 userId/sessionId。先校验 spec 与 source 权限，再用真实数据预览；预览失败返回结构化错误，不插入报表。
2. 创建在用户级锁（advisory lock 或 report quota 行）内检查 100 上限，再在单事务中插入 DatasetReport（revision=1）、dependency、origin_session/origin_agent 和 CREATE 审计；**不创建 Pin**。
3. `update_dataset_report` 加载本人报表，按 `expected_revision` CAS；重新校验/预览后更新 spec、revision + 1、dependency、updated_at 和 UPDATE 审计；失败保留原定义并返回 current revision。
4. `DELETE /api/reports/:id` 在事务中锁报表与用户 layout 行，检查 `expected_revision`（已 Pin 时还检查 `expected_layout_revision`）；删除报表、本人 Pin，递增 layout revision，写审计。不能只删 Pin 不更新 layout revision。
5. Pin 是独立用户动作：`POST /api/for-you/report-pins` 锁 layout、检查 `expected_layout_revision`、插入唯一 Pin、递增 revision、写审计；已存在则返回当前 Pin，不重复创建。
6. Reorder 请求必须包含当前全部 Pin IDs；按数组顺序重建 position；冲突返回当前 layout，不静默覆盖另一设备。
7. Agent 工具不提供 Pin/删除/排序能力，也不能调用 `HomeReportService`；即使模型在聊天里声称“已经钉好了”，服务端也不会产生 Pin 记录。

**本设计不提供“人类授权不可伪造”的保证。**Agent 创建/修改报表的权限来自当前用户与会话绑定；人类通过管理页的 Pin、删除和排序控制展示与生命周期。若未来需要防伪造的 human action，应在平台层另立机制，而不是在报表功能里补一个可被 Bearer API key 复用的按钮 API。

### 8.3 依赖变化与错误保留

- 每次 preview / create / update / data 都校验所引用字段与类型；无关新增字段和 label 修改不让报表失效。
- 所引用字段删除、重命名、类型变化返回 SOURCE_SCHEMA_CHANGED；不自动替换新字段，不静默展示旧缓存。
- preview 与 data 查询在 REPEATABLE READ 只读事务中读取 schema 与 records，返回前复核 dependency fingerprint；schema_version 在事务内变化则返回 SOURCE_SCHEMA_CHANGED，要求重新预览。不得声称能锁住 Mongo / 外部任意变更。
- create/update 在写事务内复核 dependency fingerprint 与 source 可读性；预览成功后 schema 变化则返回 SOURCE_SCHEMA_CHANGED，不落库/不改定义。
- report 修改 CAS 冲突时保留原定义和旧 Pin，返回 current_revision；不自动以最后写入覆盖。
- 对不存在 / 非本人 report 返回同一种 404，避免 ID 枚举；有权对象的 source 权限丢失只返回通用 SOURCE_ACCESS_DENIED，不泄露无权 source 的名字/schema。
- report 创建/修改/删除和 Pin 变更追加短审计摘要；不是每次刷新记录原始数据。
- Agent create/update 失败不留下半保存报表；Pin/删除失败按事务回滚，不改报表定义。

## 9. API、Agent 工具与事件协议

### 9.1 拟新增 HTTP API

以下沿用现有接口 + DTO 模式；接口中 path parameter 为 :id，列表均有 offset/limit 且限制用户范围。首版 HTTP 不暴露报表 create/update；创建/修改只由 Agent 工具调用 `ReportService`，避免绕过工具白名单与预览。未来手动编辑器复用同一 `ReportService`，不新建弱校验入口。

| 方法 / 路径 | 输入 / 输出 |
|---|---|
| GET `/api/reports` | 当前用户动态报表摘要，分页；不混入旧文字 UserReport |
| GET `/api/reports/:id` | 定义、revision、source、pin 状态；owner 与权限校验 |
| GET `/api/reports/:id/data` | range kind/days 或 from/to、page_size/offset；时区使用报表定义；ReportDataEnvelope |
| DELETE `/api/reports/:id` | expected_revision、expected_layout_revision（仅已 Pin 时必填）；原子删除定义与本人 Pin，需确认 |
| GET `/api/for-you/report-pins` | layout_revision + 当前用户 Pin 与轻量 report 摘要，不执行数据查询 |
| POST `/api/for-you/report-pins/data` | 至多 8 个本人 pin_id（首屏；其余懒加载），返回逐项 ReportDataEnvelope / error；服务器校验实际 Pin |
| POST `/api/for-you/report-pins` | report_id、width、expected_layout_revision；已 Pin 则返回当前 Pin，避免重复 |
| DELETE `/api/for-you/report-pins/:id` | expected_layout_revision；只取消钉选 |
| PUT `/api/for-you/report-pins/layout` | 完整 pin IDs 顺序、width、expected_layout_revision；CAS |

GET data 可以带 `refresh=true` 做重新查询，但不会触发 Agent run。Pin 相关接口是普通用户 UI 操作，不提供“人类授权不可伪造”的保证；Agent 工具也不暴露这些动作。

### 9.2 ReportDataEnvelope

固定 DTO 外层，动态 columns/rows 使用 JSON String；前端统一解码并校验版本。

```json
{
  "report_id": "report-uuid",
  "report_revision": 1,
  "spec_version": 1,
  "status": "READY",
  "range": {
    "from_inclusive": "2026-10-19T16:00:00Z",
    "to_exclusive": "2026-11-18T16:00:00Z",
    "timezone": "Asia/Shanghai"
  },
  "columns_json": "[{\"name\":\"bucket\",\"type\":\"TIME\"},{\"name\":\"total\",\"type\":\"DECIMAL\"},{\"name\":\"positive_rate\",\"type\":\"DECIMAL\"}]",
  "rows_json": "[[\"2026-11-17T16:00:00Z\",\"100\",\"0.42\"]]",
  "matched_record_count": 30,
  "invalid_value_count": 0,
  "source_time": "2026-11-18T01:00:00Z",
  "source_updated_at": "2026-11-18T01:10:00Z",
  "evaluated_at": "2026-11-19T01:00:00Z",
  "freshness_status": "UNKNOWN",
  "has_more": false
}
```

若采用 `bucket_axis`，响应增加同名列（UTC instants + 显示标签）；否则服务端保证 rows 覆盖完整 bucket 序列，缺失 measure 为 null。两种方案只能选一种，`spec_version=1` 固定。

columns schema 和 rows 的长度 / 标量类型需一致。数据状态 READY / NO_DATA；freshness 独立为 WITHIN_EXPECTATION / DELAYED / UNKNOWN。失败通过结构化 error 返回，不用 READY+0 掩盖。整页 batch 单项失败时总体 200 并返回该 pin 的 error，整请求未授权则 401/403。

### 9.3 Agent tools

ReportToolProvider 拟提供：

- `list_report_sources`：当前会话可读的 GENERAL 源及 schema、有限更新信息；数据源名称重名时必须选 ID。
- `list_dataset_reports` / `get_dataset_report`：当前用户可访问的报表摘要 / 定义，均有数量限制。
- `preview_dataset_report`：title、description、spec_json；验证并返回有限预览，不保存。
- `create_dataset_report`：title、description、spec_json；验证、预览并保存，返回 report_id / revision；不创建 Pin。
- `update_dataset_report`：report_id、expected_revision、新配置；验证、预览并按 revision CAS 更新。

样本读取复用 `query_dataset_records` 的白名单；不增加绕过 DatasetAccessRegistry 的全局 query tool。**首版无 `pin_report`、`delete_report`、`reorder_reports` LLM tool**；Pin/删除/排序只能由用户在管理页操作。无需增加模型路由，使用用户当前对话模型即可。create/update 只在交互式可信用户上下文注册，不在无人值守定时 run 或 subagent 中注册。

### 9.4 Rich event 与历史聊天

拟新增平台事件 `report_created` / `report_updated`：schema_version=1、report_id、revision、title；附加 generic rich card 作为不支持专用组件的降级展示（口径文字、有限表格与内部详情链接）。

- ChatReportCard 通过认证 API 读取 `/api/reports/:id` 或 `/api/reports/:id/data`，并调用 ReportRenderer；引用 report_id/revision，不永久保留一次查询结果。
- 历史聊天恢复时，report 已删除 / 无权限 / 定义已更新分别呈现对应状态；不在组件 render 时自动修改报表。
- 图表数据不通过 custom event 携带全量；旧事件被转发 / 公开分享不自动读取实时数据。卡片不承载 Pin/删除/排序按钮，这些动作只在 `/reports` 管理页。
- 前端创建/更新成功后刷新 `/reports`；首页重新加载服务端 layout，不依赖跨页内存状态。

### 9.5 错误协议

沿用既有 error_code/error_message；附 typed `issues[]`，包括 code、path(JSON Pointer)、expected、suggestion。实现前验证 core-ng 异常 / WebService 的响应映射，必要时用专用 validation result DTO，不能把 JSON 串塞进 error_message 冒充协议。

| code | HTTP | 行为 |
|---|---|---|
| REPORT_INVALID_SPEC / UNSUPPORTED_SPEC_VERSION | 400 | 定位字段，保留未保存的输入供修改 |
| INVALID_TIME_RANGE | 400 | 显示允许的范围/时区，不自动改口径 |
| SOURCE_ACCESS_DENIED | 403 | 不返回数据，清理用户侧旧结果 |
| REPORT_NOT_FOUND | 404 | 不区分不存在和别人的对象 |
| REPORT_REVISION_CONFLICT / LAYOUT_REVISION_CONFLICT / SOURCE_SCHEMA_CHANGED | 409 | 刷新当前定义 / 重新预览，禁止静默覆盖 |
| REPORT_LIMIT_REACHED / PIN_LIMIT_REACHED | 409 | 提示清理旧报表/Pin；不自动删旧项 |
| QUERY_BUDGET_EXCEEDED / RESULT_LIMIT_EXCEEDED | 422 | 显示缩短时间窗 / 减少维度的建议，不视作无数据 |
| RATE_LIMITED | 429 | 带 Retry-After；创建/修改/刷新共享 report scope |
| REPORT_CAPABILITY_UNAVAILABLE | 503 | 未启用 / 非 PG 后端；不自动降级 |
| REPORT_QUERY_UNAVAILABLE | 503 | 数据库 / 服务暂不可用，可重试；不改原定义 |

## 10. 安全、刷新、预算与可观测性

### 10.1 只读执行与安全边界

- ReportSpec 严格解析：未知关键字段、非法 enum、循环 measure 依赖、超长字段名与非标量过滤值拒绝；标题、标签和数据值视为非可信文本。
- 所有 SQL 来自固定 query builder；Dataset ID、字段路径、时区、条件值使用参数，只有 allowlist 中的函数 / bucket 作为代码常量。禁止 spec 指定 URL、SQL、script、CSS、原始 HTML 或外部 resource。
- `DatasetReportQueryStore` 使用主连接的只读事务（`SET TRANSACTION READ ONLY` + REPEATABLE READ），并对生成 SQL 强制带 source ID；不是先拿到所有记录再在 Java 过滤。独立只读 DB 角色作为后续加固，不在首版声称已实现。
- 不使用既有 `DatasetSqlExecutor` 的自由 SQL 入口；其 RLS 授权 token 也不作为报表 owner 判断。若实施增加 RLS，必须验证 reader 非 superuser / BYPASSRLS、view 没有以 owner 绕过、connection context 不串租户；RLS 不是代替服务授权。
- Rich card 和图表标签不插入 HTML；渲染 text，详情链接使用固定内部路由。不存在匿名获取 report 数据的端点。
- Agent 可以创建/修改本人的报表，但工具白名单不提供 Pin/删除/排序，也不暴露 `HomeReportService`；即使模型声称“已钉好”，服务端不会产生 Pin。本设计不声称“人类授权不可伪造”；Pin/删除是普通认证的 UI 操作。
- 平台的 `X-Auth-Request-Email` 信任、CORS 允许该 header、bearer API key 长期有效，是独立于报表功能的平台安全项；本功能不新增对这些凭证的依赖，但部署前应确认 header 不可由客户端伪造。

### 10.2 刷新与缓存

**首版不做后台预计算任务。**读取时执行受限查询，活跃首页周期刷新；采集仍由已有 Scheduler 控制，二者解耦。

- 进入首页先加载轻量 Pin layout，独立加载报表数据；慢报表不阻塞 token usage、TODO、文件和会话。
- 初次读取及点击刷新查询当前数据；页面可见且活跃时每 5 分钟重新请求，hidden/unmount 停止，回到前台后按缓存年龄重查。每卡片 loading/error 隔离。
- 单实例首版 bounded LRU 缓存，TTL=60 秒，最多 128 项 / 总计 32 MiB；缓存只是性能优化，多副本不依赖它保证正确性。
- key 包含 user_id、report_id/revision、engine_version、完整规范化查询参数、**解析后的时间窗**、dependency fingerprint 与有效授权 scope fingerprint；命中前重新查 owner/权限/schema，权限丢失不返回旧缓存。
- ROLLING/TODAY 的时间窗可能每次不同，允许低命中，不为命中率暗改统计边界。固定自然日窗可以较好复用。
- `refresh=true` 绕过结果缓存但同样受并发与 rate limit 约束；不能靠无限点击压垮数据库。
- 新数据 / record 修改或删除最多在 60 秒结果 TTL 后可见；手动刷新直接重查。源权限 / 所依赖 schema 的变更按每请求检查，不等待 TTL。
- 查询失败不默认以旧结果覆盖错误。首版不返回 server stale fallback；客户端保留旧图只能在“查询暂不可用”时标注旧 evaluated_at，权限/源删除/schema 不兼容必须清空旧图。

**时效提示规则：**对于 TODAY/ROLLING，`evaluated_at-source_time` 与用户 expected interval 比较；对于过去的完整 / 固定窗口，不把今日距离旧数据时间的差自动判为采集延迟，默认 UNKNOWN，并显示该窗口的 source_time。SOURCE freshness 不是“今天调度已成功”的证明。

### 10.3 初始运行预算（均需 dev 压测校准）

| 项目 | 建议上限 | 达到上限时 |
|---|---|---|
| 每用户报表 / Pin | 100 / 20（首屏最多 8） | 创建拒绝，提示清理；不自动删旧项 |
| spec_json | 32 KiB UTF-8 | 400，不靠字符串字符数代替字节数 |
| 查询窗口 | 最多 366 天；固定窗也受限 | 提示缩短；不允许无界全历史 |
| filters / measures / series / columns | 20 / 8（含派生）/ 8 / 20 | validation error |
| 聚合行 / 分类数 / TABLE page | 1000 / 50 / 100（最大 200） | 聚合超限报错；明细返回 has_more |
| 单个数据响应 | 256 KiB UTF-8 | 明确 RESULT_LIMIT_EXCEEDED；不静默截单元格 |
| 单 SQL statement timeout / 单 report 总预算 | 3 秒 / 5 秒 | 中止并取消数据库执行，不继续后台占连接 |
| 首页 batch | 首屏最多 8 张；最多 2 并行；8 秒总 wall budget | 未启动项 / 超时项独立错误；其余滚动懒加载 |
| 用户 / 实例在途查询 | 2 / 16 | 429 / busy，带 retry-after；全局 executor 有界 |
| 用户刷新 / 创建修改速率 | 每分钟 20 / 10 | 429；共享 report scope，不能换 API 绕过 |
| 审计保留 / 清理期限 | 90 天 / 有界后台维护 | 只清审计摘要，不影响报表 / Pin |

batch 预算与 per-user 并发同时生效：首屏最多 8 张、最多 2 并行，与每用户 2 个在途查询一致；其余 Pins 滚动懒加载。首版慢卡片可出现独立 QUERY_BUDGET_EXCEEDED，页面保留独立重试；不承诺最大负载下一次全部成功。

建议 SLO：轻量 layout p95 < 300ms；常见 7/30 天、10 万行内聚合 p95 < 1s；6 张正常卡片首次数据 p95 < 3s。只作为待压测目标，不以文档代替验证。

### 10.4 指标与审计

- ActionLogContext：user_id、report_id、revision、dataset_id、origin_session_id、query_mode、bucket、resolved range；不记 raw rows、过滤敏感值、完整提示词。
- 指标：query latency/timeout/rows/response bytes、cache hit/miss、validation failure by code、report create/update、pin 数量、CAS conflict。
- 单 report 与 batch 子任务使用 correlation ID；用户错误提示给 request_id 供排查。
- 数据库部署失败 / query error 与 source schema 错误分开统计；首页告警不触发 LLM 解释或重跑采集。
- 审计记录创建、修改、删除、pin/unpin/layout 变更和 actor，保留 90 天；不保存 preview 原始数据。

## 11. 实施计划、验证与上线

### 11.1 分阶段计划

| 阶段 | 实施内容 | 对应验证 / 完成条件 |
|---|---|---|
| P0 契约与权限 | 核实 PG Dataset backend、report.manage/dataset.view、会话绑定；typed spec / validator、report query store 扩展 | schema / 时间 / ratio / 半开区间测试通过；非 PG 明确 503；没有这些证据不进入 P1 |
| P1 报表后端闭环 | PG migration（reports/layouts/pins/audits）；ReportService create/update/delete；HomeReportService pin/layout；query、CAS、结构化错误与能力开关 | Agent 创建/修改、用户 Pin/删除/重排、CAS、越权、删除清理测试通过 |
| P2 对话与管理页 | ReportToolProvider（list/get/preview/create/update）；ChatReportCard、ReportRenderer、ReportList/Detail、ForYou Pins | 从自然语言创建 → `/reports` 出现 → 用户 Pin → 首页读取 → 次日新数据 → unpin/delete 全链路 dev E2E；旧聊天和旧首页模块不回归 |
| P3 生产加固 | 缓存、budget/rate limit、首屏 8/懒加载、schema/error UX、审计指标、可访问性 | 大数据、慢查询、部分失败、多副本和换账号测试；满足压测目标或调整已明示预算 |

P0–P3 是一个完整首版的依赖阶段，不能把只完成 CRUD 的 P1 称作用户需求已交付。用户确认设计后再实施；实现完成主动运行 dev E2E，使用 CI 构建镜像；UAT/prod 需单独授权。

### 11.2 需求与验收矩阵

| ID | 验收场景 | 预期 |
|---|---|---|
| R1 | 定时 Agent 往 GENERAL Dataset 新写入 / 更新记录 | 报表读取真实数据；不改落库入口 |
| R2 | 对话“最近七天每日评论总数”，有明确 NUMBER 字段 | Agent 创建报表；`/reports` 出现 revision 1；真实折线 / 表格 |
| R2 | source 名称重名 / 无 schema / SESSION / 字段无权限 | 询问或明确拒绝，不猜源、不扩权限；不保存 |
| R2 | “好评率”多个日期总评论数不同 | ratio of sums，不能平均日比例 |
| R2 | 无 report.manage / 无 dataset.view / 预览失败 / 无效 spec | 不保存报表；结构化错误；不留下半成品 |
| R3 | Agent 创建但不 Pin | 首页不显示；报表在 `/reports` |
| R3 | 用户在详情页 Pin / 再次 Pin / 取消 Pin | 首页仅显示已 Pin；重复无第二张卡；取消后报表仍在 |
| R3 | 用户删除已 Pin 报表 | 报表与 Pin 同一事务删除；layout revision 递增 |
| R4 | 翌日打开 LAST_N_COMPLETE_DAYS / 手动 refresh | 自然日窗口推进；新记录可见，无 LLM 调用和 Agent run |
| R4 | 插入超 1000 条 records 后 SUM | 全部符合条件的行参与数据库聚合，不只前一页 |
| R4 | null、空窗、分母 0、非法数值、缺 bucket | 区分 0 与未知，折线断开/柱状留空且有 warnings |
| R4 | 边界 record 正好在 to；DST 日、周一、同时间 LAST | 半开上界、正确本地日、稳定 tie-break |
| R5 | 另一 user/report/pin ID，公共分享 | 不泄露定义或数据 |
| R5 | Agent 工具 surface / HTTP Pin 权限 | 工具列表无 pin/delete/reorder；HTTP Pin 只能操作本人 layout，不声称防伪造 |
| R5 | 失去 dataset.view / report.view 后缓存命中 | 仍拒绝并清空旧数据，不等 TTL |
| R6 | Agent update CAS 冲突 / 双设备改 report | 409 不覆盖；旧定义保留 |
| R6 | 双设备改 layout、响应丢失重试 | 409 不覆盖；不出现孤儿 Pin |
| R6 | 删除 Dataset / 重命名字段 / 无关字段新增 | 前两者明确失效且可取消 Pin；无关新增仍可运行 |
| R6 | 6 张正常 + 1 张超时；非 PG；服务 capability 关闭 | 独立失败、旧首页可用、不回退无界 Mongo 扫描 |
| R6 | 前端 320px/1440px、深色、键盘与屏幕阅读器 | 不横向溢出；图表有表格降级，按钮可用键盘操作 |
| R6 | logout → 另一用户登录、刷新历史聊天、旧卡片 | 缓存无串用户；旧卡片不会自动修改报表 |

**AI 评审建议补充验收（待用户确认后并入正式矩阵，不改变既有需求编号）：**

- Agent 创建/修改负例：无 `report.manage`、无 `dataset.view`、SESSION/无 schema、字段无权限、预览失败、无效 spec、超 100 报表；不能留下半成品。
- 管理页：未 Pin / 已 Pin / 重复 Pin / 取消 Pin / 删除已 Pin 报表 / layout 409；Agent 工具无 pin/delete/reorder。
- 时间：用户无时区设置、DST 春/秋、ISO 周一/月初/闰日、`FIXED` 窗口、`LAST` 同时间多行。
- 图表整形：category 单独作为 x 轴；LINE/BAR 同时提交 bucket+category 被拒绝；缺失 bucket、null vs 0、>50 分类、TABLE page>100、METRIC 多行拒绝。
- 容量：首屏 8 张 / 2 并行、20 Pins 懒加载、每用户 2 个在途查询、rate limit、缓存权限撤销、断网/超时。
- 分享：Agent 文字不复述结果数值 / 分享脱敏；卡片不自动读取实时数据。

### 11.3 非功能与上线方式

- 使用 `sys.report.enabled`（拟新增，默认 false）；与 `sys.dataset.store=postgresql`、必要迁移和身份能力一起检查，暴露 `datasetReports` capability。条件不足隐藏创建 / Pin 控件，已有 Pin 显示服务不可用，不删除对象。
- 新增 `/reports`、`/reports/:id` 前端路由与 SpaRoutes；静态刷新和直接链接 E2E，不能只测 SPA 内导航。
- 先迁移 → 后端关闭开关部署 → 前端 capability-gated 部署 → dev 开启验收。UAT/prod 在用户授权后逐级推进。
- 本次不要求新增 Agent 发布质量规则；report provider 是运行时用户能力，发布时的 dataset 引用仍受既有 gate 检查。
- 回滚关闭开关即可停止新查询与创建；保留报告 / Pin / 审计表和原 UserReport，不做破坏性 down migration。已保存高版本 spec 在旧引擎上显式 unsupported，不以旧语义运行。
- dev 验证报告需包含实际命令、镜像版本、数据库迁移版本与请求证据；它们是实施后的验收材料，不在当前设计里宣称通过。

### 11.4 拟新增 / 修改范围

| 模块 | 预计文件 / 类型 |
|---|---|
| core-ai-api | ReportWebService、HomeReportWebService；report request/view/error DTO |
| core-ai-server/report | ReportAccessPolicy、ReportSpecValidator、ReportService、ReportQueryService、HomeReportService、PG stores、ReportToolProvider |
| core-ai-server/dataset/store | 报表 query contract / PG query builder：半开区间、选定 metadata time、LAST tie-break、结果/时间/字节预算；保留旧 query API 语义 |
| core-ai-server 组装 | ReportModule、RBAC catalog/roles、capabilities、可信 session 工具组装；SpaRoutes；不把报告逻辑塞入 AgentRunner |
| core-ai-server-db-migration | 新表（reports/layouts/pins/audits）、索引、CAS 字段，使用实际下一个 Flyway version |
| core-ai-frontend | api/client、reportTypes、ReportRenderer、ChatReportCard、ReportList/Detail、ForYou 的 PinnedReportsSection、Layout 入口与 capability 路由 |
| tests | Validator/query/store/report/access 单测与 PG integration；前端组件测试；真实 dev E2E 与预算压测 |

## 12. 关键决策记录（ADR 摘要）

除 ADR-05 已由 U-004 决定外，其余均为**推荐、待用户确认**，不是已批准决策。

| ID | 决策 | 初衷与理由 | 备选 / 代价 |
|---|---|---|---|
| ADR-01 | 声明式 ReportSpec，不存活动 HTML / 任意脚本 | 查询可重复、安全可校验、跨聊天/首页复用 | 任意 HTML 更自由但权限、刷新和长期维护困难 |
| ADR-02 | 单 GENERAL Dataset、受限聚合与明细；不支持自由 SQL | 已有聚合可复用，权限和成本可穷举测试 | SQL 更强但任意表/函数、依赖追踪和 scope 更复杂；join 后置 |
| ADR-03 | DatasetReport 独立于 UserReport | 动态定义和静态文字生命周期不同，不破坏已有首页报告 | 统一实体会引入 type 推断、content 混义与大迁移 |
| ADR-04 | 报表定义与 HomeReportPin 分离 | 保存不等于上首页，取消 Pin 不删报表 | 首版一报表一 Pin；不支持多视角重复卡 |
| ADR-05 | Agent 直接创建/修改报表；人管理 Pin/删除/排序 | U-004：对话创建后报表直接进管理页，人类操作控制展示；实现简单，符合用户心智 | proposal/approval receipt 更严格但体验复杂、需可信 human-action 机制；已由用户否决 |
| ADR-06 | 读取时查询，不用新 scheduler / LLM 刷新 | 数据采集与展示解耦，低运维与模型成本 | 首次加载有 DB 成本；复杂预计算后置 |
| ADR-07 | PG-only 能力开启，新元数据存 PG | 当前报表需要精确数据库聚合与事务，不再扩展 Mongo 路线 | 其他 backend 显式不可用；不实现双存储报表系统 |
| ADR-08 | 首版复用 dataset.view 全局源可读，report/pin 私有 | 与当前真实权限一致，不把创建者字段误当 ACL | 若用户希望 Dataset 本身私有，须先独立设计共享/ACL 迁移 |
| ADR-09 | 元数据时间、半开窗、ratio of sums、稳定 LAST | 口径可解释、边界和重跑可测试 | 类型化业务日期与去重规则需另加能力，不能假装支持 |
| ADR-10 | report revision CAS + 独立 layout CAS | 创建/修改与 Pin 解耦；避免覆盖和多端冲突；删除时同一事务清理 Pin | 不再有 report+pin 原子提交；增加两类 revision 与事务实现 |
| ADR-11 | 现有 Recharts + 受限展示协议、CSS Grid | 保持一致主题，控制依赖和维护成本 | 任意 BI 画布、拖拽与多图复合页后置 |
| ADR-12 | 无原始数据永久 preview；有界 TTL 缓存 | 减少敏感数据持久化和失权后泄露 | 历史聊天不保证永久保留当时图表数值；独立快照导出后置 |
| ADR-13 | 首版不做报表版本回滚 | revision + 审计足以定位“谁改了什么”；避免版本历史表和 UI | 需要恢复旧版本时另立需求 |

## 13. 待 Review 决策

| ID | 问题 | 推荐 | 影响 |
|---|---|---|---|
| D1 | “首页”是否确定为 server Web `/for-you`？ | 是，不同时重做 desktop 首页 | 产品入口 / 实施范围 |
| D2 | 首版声明式聚合、一个 source / 一个图表，是否需要自由 SQL 或跨源 join？ | 受限 spec；自由 SQL/join 后置 | 查询安全、复杂度、交付周期 |
| D3 | 聊天里是否还需要“保存 / 保存并钉选”按钮？ | 已由用户 U-004 决定：不需要；Agent 直接创建/修改，人在管理页 Pin/删除/排序 | 移除 proposal/commit；权限边界改为工具白名单 |
| D4 | Dataset 可读是否维持现有全局 dataset.view？ | 首版维持；报表/Pin 私有 | 若要求数据源私有，要先补 ACL 治理 |
| D5 | 首版是否接受 PG-only、metadata time；需要 business_date 吗？ | PG-only；按明确采集日期。业务日期需求单独确认 | backend 与时间类型扩展 |
| D6 | “我的报表”放在 ForYou 顶部、旧 token usage 后移？ | 有 Pin 时放在顶部，空时轻量入口 | 首页信息优先级 |
| D7 | 首版 100 报表 / 20 Pin、5 分钟活跃刷新、60 秒缓存可接受吗？ | 作为起始预算，dev 压测后调整 | 容量 / 成本 / 时效 |
| D8 | 动态文字摘要、共享、导出和采集失败告警是否纳入首版？ | 不纳入，不影响已有静态文字报告 | 范围控制 |
| D9 | 是否新增用户时区设置？ | 推荐新增；无设置时 Agent 必须询问，不静默默认 UTC | 时间口径正确性 |
| D10 | 是否需要报表版本回滚？ | 首版不做；revision + 审计，后置 | 实现复杂度 |

**AI 评审建议（2026-11-19，非用户决定）：**

- D1：同意先做 `/for-you`，但必须确认 desktop 是否才是用户“每天查看”的主入口；若 desktop 是主入口，本设计的 R3/R4 只能算部分满足。
- D2：同意受限 spec；自由 SQL/跨源 join 后置，不与首版混做。
- D3：已由 U-004 确认 Agent 直接创建/修改、人在管理页操作；评审不再要求 human-action 机制。
- D4：同意首版维持全局 `dataset.view`，但 UI 应明示“有 dataset.view 的用户可读该数据源”，避免把“报表私有”误解为“数据私有”。
- D5：同意 PG-only 与 metadata time；`business_date` 应作为独立能力（类型化业务时间字段 + 去重规则），不要在首版用字符串猜测。
- D6：同意有 Pin 时置顶、空态轻量入口；注意与现有文字报告的命名区分（建议“动态报表” vs “报告”）。
- D7：预算已按首屏 8 张 / 2 并行修正；仍需 dev 压测校准，不要把当前数字直接当验收线。
- D8：同意不纳入首版；已有静态文字报告保持不变。
- D9：建议新增；否则“今日/自然日”在无时区用户上仍然错。
- D10：同意首版不做；需要恢复旧版本时另立需求。

**实施前的工程阻断：**确认 `sys.dataset.store=postgresql`、迁移版本、report.manage/dataset.view、会话绑定与 ReportToolProvider 注册条件；确认 `/reports` 页面与 Pin API 的 owner 校验。人工确认不再是阻断项（U-004 已取消）。仍建议在部署层确认 `X-Auth-Request-Email` 不可伪造，但这是平台安全项，不是本功能的前置。

AI 评审补充：v0.4 按 U-004 移除了 proposal/commit，P0-1/P0-2 不再适用；P1-1/P1-2/P1-3/P1-4/P1-5/P1-6/P1-9 已在 v0.4 调整；P1-8 与 P1-10 仍需实现前确认。

## 14. Review 记录与修改记录

### 14.1 用户 Review 记录

尚无用户对整份设计的批准；D3 已由 U-004 明确。要求继续出设计文档不等于授权实施。

| ID | 日期 | 意见原文 | 处理 / 影响章节 | 状态 |
|---|---|---|---|---|
| U-001 | 2026-11-19 | 怎么还没出tech design | 停止无界探索，直接交付本文，保留源码核对证据 | 已处理；非方案批准 |
| U-002 | 2026-11-19 | 继续出设计文档 | 仅设计，不改代码；汇总待确认决策 | 已处理；非实施授权 |
| U-003 | 2026-11-19 | 那以后不用这个做review了 | 指 `gemini-4-hydrogen` 持续 429；停用该模型评审，改用当前会话模型；不构成设计批准 | 已处理；非方案批准 |
| U-004 | 2026-11-19 | 不需要保证吧 agent建完了报表 不是应该在报表管理页面展示吗 然后我们人就可以去操作它了啊 | 取消 proposal/human commit/approval receipt；Agent 直接创建/修改报表并保存，报表进入 `/reports` 管理页；Pin、删除、排序由用户操作；影响 §1/§4/§5/§7/§8/§9/§11/§12/§13 | 已处理；属于产品模型澄清，非整份设计批准 |
| U-005 | 2026-11-19 | 报表的domain设计 | 新增 §5.1 领域模型：两个聚合、值对象、不变量、策略、应用服务/仓储、事务/锁顺序、分层与包结构 | 已处理；非方案批准 |
| U-006 | 2026-11-19 | 是存PG的吧 没有再去存到mongo吧 | 确认报表 domain 全部 PG；§7 增加存储边界：不新增 Mongo collection、不做双写/迁移；非 PG 后端 capability 关闭 | 已处理；非方案批准 |

### 14.2 AI 技术评审记录（非用户批准）

2026-11-19 首次调用指定模型 `gemini-4-hydrogen`：模型服务返回 HTTP 429 / RESOURCE_EXHAUSTED，工具在 26 秒内重试 4 次仍失败，未获得评审结论。

同日再次调用仍为 429；用户决定以后不再使用该模型做评审。本次评审改由当前会话模型完成（AI-REVIEW-002），以下为 AI 评审意见，不是用户批准，也不冒充指定模型结论。

| ID | 类型 | 结果 | 处理 / 状态 |
|---|---|---|---|
| AI-REVIEW-001 | 指定模型技术评审 | 未完成；服务限流，不是设计通过或不通过 | 如实记录阻塞，先交付用户 Review；未切换其他模型、未声称 AI 评审通过 |
| AI-REVIEW-002 | 当前会话模型技术评审 | 方向成立；原始 2 个 P0、11 个 P1、6 个 P2；v0.4 按 U-004 简化后 P0 不再适用 | 剩余 P1/P2 在实现前确认；非实施授权 |

**总体结论：**设计对现状的核对、权限边界、查询口径和事务思路总体扎实，ADR 取舍合理。v0.4 按 U-004 移除了 proposal/commit 后，原 P0-1（人工确认边界）与 P0-2（proposal 预览数据接口）不再适用；剩余主要契约是图表整形、时区来源、PG 查询快照、错误映射与分享脱敏。

下表为 v0.3 原始评审记录，保留用于追溯；v0.4 的适用性见文末“v0.4 处理状态”。

| ID | 级别（v0.3 原始评审） | 章节 | 问题 | 建议 |
|---|---|---|---|---|
| P0-1 | P0 | §8.2、§13 阻断 | 人工确认边界未定义：前端登录返回长期 API key，存 localStorage 并以 Bearer 发送；`AuthInterceptor` 对 bearer 认证的内部用户会写入 session identity；没有 CSRF 层；`X-Auth-Request-Email` 被无条件信任且 CORS 允许任意 origin 带该 header。仅凭“按钮调用 API + 当前用户认证”不能证明是人类点击，脚本、持有 key 的 agent、直接访问服务端者都能构造同样请求。 | 设计层二选一：(A) commit 只接受交互式登录会话，`AuthContext` 增加 authMethod，禁止 bearer 派生 session，补 SameSite/CSRF，拒绝 header 认证；(B) 一次性 approval receipt，绑定 user + proposal revision + hash，UI-only 获取、单次使用、短 TTL。把“前端隐藏按钮”从阻断表述中删除。 |
| P0-2 | P0 | §4.1、§9.1、§9.4 | proposal 预览数据接口缺失：聊天卡片要渲染真实预览，但 API 表只有 GET proposal（“预览元数据”）和 commit，没有 GET proposal data；若 GET proposal 内嵌 ReportDataEnvelope，未定义大小、缓存、权限和过期语义，卡片无法可靠取数。 | 增加 `GET /api/report-proposals/:id/data`，或明确 GET proposal 返回 preview envelope；owner + proposal revision + hash 校验，支持 range override；定义过期/superseded/无数据/超预算返回。 |
| P1-1 | P1 | §6.1、§6.4、§9.2 | 图表整形协议不完整：`category_field` 与 LINE/BAR 的长表→宽表 pivot、缺失 bucket 的轴补齐、METRIC 行数约束、RECORDS 允许的 display type 均未定义；“折线断开”在 category 轴上无法保证。 | 增加 display × query 兼容矩阵；服务端返回 `bucket_axis` 或保证连续 bucket 且缺失值为 null；明确前端 pivot 规则与 series 上限；METRIC 要求单行无 bucket/category。 |
| P1-2 | P1 | §6.1、§6.3 | timezone 来源不存在：设计说 Agent 从“可信用户设置”取，但工作区未发现用户时区设置；默认 UTC 使“今日/自然日”对中国用户错 8 小时，直接违背 R4。 | 新增用户时区设置，或使用浏览器时区并让用户确认；部署默认 Asia/Shanghai；预览显示解析后的 [from,to) 与 timezone；补“无设置”验收。 |
| P1-3 | P1 | §6.3 | `LAST` 以 record.id（UUID）DESC 作 tie-break，结果确定但不代表“最新”；“取最新行”文案误导。`FIXED` 持久化 instant 后不会随时间推进，与“每天查看新数据”冲突。 | `LAST` 改为 time_field DESC, created_at DESC, id DESC，文案改为“确定代表行”；FIXED 在 UI 标注“固定历史窗口”，不作为日常默认；补半开边界/DST/周一/闰日测试。 |
| P1-4 | P1 | §10.3 | 首页 batch 预算与并发矛盾：20 Pins、最多 4 并行、8s 总预算，但每用户在途查询上限 2；20×3s/2=30s，慢卡片会大面积超时，“部分失败”在全部慢查询时不成立；6 卡 p95<2s 与 3s statement timeout 也不一致。 | 把 batch fan-out 与 per-user 并发分开定义；首屏只取前 6–8 张，其余懒加载；或把 Pin 默认上限降到 10；用 dev 压测校准后再定数字。 |
| P1-5 | P1 | §6.3、§9.2 | “schema 与 records 同一 PG snapshot”未指定隔离级别；现有 `PostgresDatasetRecordStore.aggregate` 先读 metadata 再 select records，没有事务；PG 默认 READ COMMITTED 下两条语句不是同一快照。 | 明确 REPEATABLE READ 只读事务或单条 SQL join metadata；返回前复核 schema_version；补并发改 schema/写记录的集成测试。 |
| P1-6 | P1 | §10.1、§11.4 | “独立只读 DB 权限”未落地：意味着第二个 datasource/连接池/secret/角色授权；core-ng 当前 `DatasetModule` 只绑定一个 Database；迁移模块是否允许 CREATE ROLE/GRANT 未核实。 | 首版可只用主连接 + `SET TRANSACTION READ ONLY` + SQL builder allowlist，明确这是“查询只读”而非“DB 权限隔离”；若坚持独立角色，补 config/secret/migration/连接池设计和验收。 |
| P1-7 | P1 | §7.2、§8.2 | `candidate_hash`/canonicalization 未定义：key 顺序、数字格式、null/缺省、Unicode 未规定，可能导致同义 spec 不同 hash 或 hash 校验失效。 | 采用 RFC 8785 JCS，或明确“字段排序 + 固定 decimal 字符串 + 拒绝 NaN/Infinity”；SHA-256；把 canonicalizer 写进 spec_version=1 并加测试向量。 |
| P1-8 | P1 | §9.5 | error 协议与 core-ng 映射未验证：要返回 `issues[]` 需要专用 DTO 或 TypedWebError；设计把验证留到“实现前”。错误表还缺 RATE_LIMITED、REPORT_LIMIT_REACHED、PIN_LIMIT_REACHED、INVALID_TIME_RANGE、SESSION_NOT_OWNED。 | preview 用 200 + validation DTO（issues[] + candidate 保留），commit 用 TypedWebError；补 429 + Retry-After 和限额错误；加错误码单测。 |
| P1-9 | P1 | §10.4、§11.4、§7 | 审计表未定义：§7 没有审计表；现有 `HubCallAuditService` 是 hub 调用审计；§11.4 却把“审计表”列入迁移范围。 | 明确复用现有审计还是新增 `app.dataset_report_audits`（字段、90 天保留、清理任务）；若复用，给出表名和写入点。 |
| P1-10 | P1 | §9.4、§10.1 | 分享/历史聊天的数据泄露：预览数值由卡片 API 拉取，但 Agent 文字回复可能复述数值；聊天可被分享/公开，转发后数值仍在 transcript。设计只保证“不自动读实时数据”。 | 明确“Agent 回复不得复述查询结果数值”（工具输出/提示约束），或分享时对含 proposal 的会话脱敏/禁用；把残留风险写进非目标或安全章节。 |
| P1-11 | P1 | §8.2、§9.5 | 限额失败时 commit 全回滚：Pin 达 20 上限或 layout revision 冲突时整个 commit 回滚，用户连报表也保存不了；没有 PIN_LIMIT_REACHED 和“仅保存不钉选”回退。 | commit 返回具体限额错误，UI 提供“保存但不钉选”重试；或把 Pin 作为独立后续动作；补验收。 |
| P2-1 | P2 | §3 | §3 结论基本准确，但有 1–3 行漂移（`DatasetWebServiceImpl` list/get 实际 64/75，`DatasetAggregationSql` 的 `<=` 实际 105）；缺前端 Bearer、AuthInterceptor 建 session、无时区/CSRF/通用审计三条关键事实。 | 已在 §3 补充三条事实；后续引用行号以源码为准，不把行号当契约。 |
| P2-2 | P2 | §9.2 | `columns_json`/`rows_json` 的 JSON String 符合 core-ng ClassValidator，但前后端二次解析、payload 膨胀、类型错误只能运行时发现。 | 评估固定 `ReportRowView { List<String> values }` 是否可行；不可行则保留 String，但加版本化 decoder、列行一致性测试、256 KiB 边界测试。 |
| P2-3 | P2 | §7.3 | FK cascade 若被其他路径触发，Pin 被删而 layout revision 不变；用户删除/源删除未定义清理策略。 | 删除路径统一走 service；或去掉 cascade 改为显式清理；补 user/source 删除策略。 |
| P2-4 | P2 | §4.3、§10.2 | 未明确禁止把 report data 写入 localStorage/IndexedDB；现有前端已把 apiKey 存 localStorage，容易顺手缓存数据。 | 明确数据只存内存/请求级，登出/切换用户清空；加跨用户验收。 |
| P2-5 | P2 | §4.3、§9.1 | 现有 ForYou 已有 recent reports（UserReport），新增“我的报表”容易混淆。 | 命名区分“动态报表/Dataset 报表”与“报告”，空态说明区别。 |
| P2-6 | P2 | §9.5 | 422 是否被 core-ng 支持未核实；429 需要 Retry-After（现有 CapacityExceededException 支持）。 | 明确 422 的映射方式；429 返回 Retry-After；补错误映射测试。 |

**v0.4 处理状态（U-004 后）：**P0-1 / P0-2 / P1-7 / P1-11 因移除 proposal/commit 不再适用；P1-1 / P1-2 / P1-3 / P1-4 / P1-5 / P1-6 / P1-9 已在 v0.4 设计调整（display 矩阵与 `bucket_axis`、timezone 询问、LAST tie-break、首屏 8 张 / 2 并行、REPEATABLE READ、主连接只读事务、审计表）；P1-8（core-ng error 映射）与 P1-10（分享 transcript 数值）仍需实现前确认；P2 保留。

**§3 事实核对：**除 P2-1 的行号漂移外，§3 的核心结论均可复现：`/for-you` 默认首页、UserReport 字段、Dataset GENERAL/SESSION、`sys.dataset.store` 三后端、`app.datasets.schema_version`、`DatasetRecordService.query/aggregate`、聚合 bucket 固定 `run_started_at` 且上界 `<=`、LAST 无 ID tie-break、Dataset 白名单、SessionDatasetHelper、Dataset API 全局 `dataset.view`、ForYou owner 校验、Rich card 表单提交为普通消息、Recharts 依赖、SpaRoutes 注册，均与源码一致。风险集中在 §8/§9/§10 的契约细节，不在 §3 的现状描述。

### 14.3 文档格式与示例校验（不是技术评审）

- 章节 0–15 顺序完整；保留原始需求、需求编号、ADR、待 Review 决策与修改记录。
- 两个 JSON 示例均通过解析校验；内部 `columns_json` / `rows_json` 另作解析和列行长度检查。
- 已与实际源码核对首页、现有文字报告、Dataset store/aggregate/权限及框架 DTO 约束，证据见 §3。
- 本次设计文档与实现代码在同一变更中提交；格式校验不代表运行验证；v0.6 的 AI 评审由当前会话模型完成，仍不代表用户批准或实施授权。

### 14.4 修改记录

| 版本 | 日期 | 修改 |
|---|---|---|
| v0.1 | 2026-11-19 | 基于实际 Dataset / ForYou / chat 接口提出完整增量方案；定义查询口径、proposal commit、个人 Pin、预算与验收；未实施 |
| v0.2 | 2026-11-19 | 完成文档结构与示例校验；记录指定模型 429 导致评审未完成，交付用户 Review，不冒充模型评审通过 |
| v0.3 | 2026-11-19 | 加入当前会话模型 AI 技术评审（2 P0 / 11 P1 / 6 P2）；补充 §3 认证事实、§11.2 验收建议与 §13 决策建议；记录指定模型再次 429 及用户停用该模型的决定；未改实现代码 |
| v0.4 | 2026-11-19 | 按用户 U-004 取消 proposal/human commit/approval receipt；Agent 直接创建/修改报表并保存，报表进入 `/reports`，Pin/删除/排序由用户操作；移除 proposals 表与相关 API/tool；增加审计表；按 AI 评审调整 display 矩阵、timezone、LAST tie-break、首屏 batch、REPEATABLE READ/只读事务；未改实现代码 |
| v0.5 | 2026-11-19 | 新增 §5.1 领域模型：DatasetReport/HomeReportLayout 聚合、值对象、不变量、领域策略、应用服务与仓储、生命周期、并发/锁顺序、分层与包结构；补充 §7 存储边界（全部 PG、无 Mongo 双写）；未改实现代码 |
| v0.6 | 2026-11-19 | 落地 P0/P1：V46 迁移、PG 仓储、ReportSpecValidator、ReportQueryService、ReportService/HomeReportService、报表/首页 API、ReportToolProvider、前端报表列表/详情/渲染器/ForYou 钉选/聊天 report_created 卡片；未部署 |\n| v0.7 | 2026-11-19 | dev 部署 2.0.303 并开启 SYS_REPORT_ENABLED；E2E 覆盖 Agent 创建报表/report_created 事件、报表 data、个人 Pin、batch data、删除已 Pin 报表与 layout revision 递增；UAT/prod 未动 |
| v0.8 | 2026-11-24 | 支持 DATE/DATETIME 业务时间字段作为 `time_field`；筛选器改为类型化 `op`（EQ/NE/GT/GTE/LT/LTE/IN）并按字段类型校验；category_field/filters/record_fields 接受可选 `data.` 前缀并归一化；报表查询改为编译到 `DatasetViewManager` typed view；数据集编辑器和工具说明暴露 DATE/DATETIME；未改 spec_version |

### 14.5 Dev E2E 记录（2026-11-19）

- 镜像 `chancetop/core-ai-server:2.0.303`（CI Build Server 成功），namespace `dev-ai`，`SYS_REPORT_ENABLED=true`；`/api/capabilities` 返回 `dataset_reports=true`。
- V46 已在 dev PostgreSQL 手工执行（Flyway 因既有 V45 checksum mismatch 拒绝迁移，未修改历史 migration）；表 `app.dataset_reports`、`app.home_report_layouts`、`app.home_report_pins`、`app.dataset_report_audits` 存在。
- 用绑定 Dataset 的聊天会话触发 Agent：`list_report_sources`、`preview_dataset_report`、`create_dataset_report` 均成功，返回 `report_created` 事件；报表数据返回两个 bucket，ratio 0.4/0.8，边界 `to` 的记录被正确排除。
- API E2E：`GET /api/reports`、`GET /api/reports/:id`、`GET /api/reports/:id/data`、`POST /api/for-you/report-pins`、`GET /api/for-you/report-pins`、`POST /api/for-you/report-pins/data`、`DELETE /api/reports/:id` 通过；删除已 Pin 报表后 Pin 清空且 `layout_revision` 递增。
- 测试对象已清理；UAT/prod 未部署。

## 15. 参考资料

- [S1] [Vega-Lite — View Specification](https://vega.github.io/vega-lite/docs/spec.html)：参考声明式定义和数据/展示映射，不引入 Vega runtime。
- [S2] [PostgreSQL — Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)：RLS、表 owner/BYPASSRLS 和权限变化的安全边界。
- [P1] `docs/cn/design-agent-publish-quality-gate.md:14`、`:38`、`:51`：需求治理、服务端强制校验与 Review 结构；不复制其模型提案/提交门禁。
- [P2] 早期数据集与报表设计（已删除，见 git 历史）：仅用于明确本设计的增量范围，不作为现状证明。
- [P3] 实际实现文件与行号集中在 §3；框架约束来自项目 AGENTS.md，并与现有 PG/DTO 代码核对。

外部资料读取日期：2026-11-19。本文不把参考系统的实现能力、行为或性能当成本项目已实现的能力。
