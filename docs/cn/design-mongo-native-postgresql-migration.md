# MongoDB 原生迁移到 PostgreSQL 设计

> 状态：draft / 待评审
> 日期：2026-11-06
> 目标：把 `core-ai-server` 的 MongoDB 访问层一次性重写为 PostgreSQL 原生访问，迁移完成后删除 MongoDB 依赖。
> 关键决策：
> - **不做 FerretDB / Mongo 协议代理**；
> - **不做双写、不做影子读、不做 `pg_to_mongo`**；
> - **允许停机**，公司内部使用，按维护窗口切流；
> - **按组开发、按表装载数据、一次性切流**，不做一张表一张表的生产切换。
>
> 关联文档：
> - `docs/cn/design-dataset-postgresql-migration.md`：Dataset 已完成的 PG 迁移
> - `docs/cn/design-mongo-to-postgresql-migration.md`：FerretDB 方案（已被本文取代，保留作对比）
>
> 实施状态（2026-11-06）：Phase 1 基础设施已落地（`SYS_STORE_MODE`、`PostgresModule`、`PgDocumentStore`、Flyway `V2__system_settings.sql` / `V3__api_keys.sql` / `V4__projects.sql` / `V5__users.sql`）；`SystemSettings`（JSONB）、`ApiKey`（类型化列）、`Project`（JSONB + 行锁 + diff patch）、`User` 全部生产调用方已迁移到 `UserStore`（含 quota 原子更新、静态 access helper、trace/notification/gateway/artifact 等），生产代码中只剩 `MongoUserStore` 自身引用 `MongoCollection<User>`；`SystemPrompt`、`PromptTemplate`、`Trigger`、`ServiceApi` 也已接入 `*Store` + PG JSONB 表（Flyway `V6__config_collections.sql`）；`GatewayProviderConfig` / `GatewayModelConfig` 接入 `GatewayProviderStore` / `GatewayModelStore`（Flyway `V7__gateway_config.sql`），覆盖 gateway、settings、pricing、routing、analytics、trace maintenance；`ToolRegistryEntry` 接入 `ToolRegistryStore`（Flyway `V8__tool_registry.sql`），覆盖 ToolRegistry、MCP operation、WorkflowPort；`SkillDefinition` / `MarketplaceRepo` 接入 `SkillStore` / `MarketplaceRepoStore`（Flyway `V9__skills.sql`），覆盖 SkillService、SkillHub、Catalog、Repo sync、Marketplace、Workflow 快照；`ChannelConfigView` / `OcgConfigView` 接入 `ChannelConfigStore` / `OcgConfigStore`（Flyway `V10__channels.sql`），覆盖 Channel、WeClaw/Telegram/Slack、OpenClaw、通知与会话完成推送；Project 子集合 `ProjectSubject` / `ProjectSubjectAttribution` / `ProjectSubjectEvent` / `ProjectTargetScan` / `ProjectReportDraft` / `ProjectStatsEntity` 接入对应 Store/Repository（Flyway `V11__project_children.sql`），覆盖 Project 分析流水线、报告渲染、统计快照、reset/review 与 self-harness。Mongo/PG 双实现通过全量单测与本地 PG 集成测试。G1 的身份/配置/项目组已全部完成。2026-11-09 dev 已完成 G1 数据复制并切 `2.0.176 + pg`（users 32、api_keys 466、projects 1、skills 69 等，幂等复制，主 dev 回滚为 `2.0.171` + mongo）。2026-11-10 已完成 UAT G1 迁移：Flyway baseline V1 + V2–V11 全成功；Mongo→PG 复制 users 74、api_keys 1165、projects 4、subjects 18、events 4487、attributions 2611、scans 1306、skills 252、tool_registry 30 等，连续两遍幂等且抽样核对通过；主 UAT 已切 `2.0.177 + pg`，canary 已删除。UAT hub 验收 PASS（28 MCP servers/1144 tools，其中 4 个既有 MCP 源 FAILED；skill search 200/上限、PG 252；api-tool 3 apps/338 ops，只读 call 200；agent search 200/上限、Mongo 261）。回滚为 `2.0.171` + 移除 `SYS_STORE_MODE`，UAT Mongo 未删未改。详见 `docs/validation/2026-11-10-uat-g1-migration.md`。

G2 第一批（2026-11-10）：`ChatSession` 元数据完成 Mongo/PG 双实现，新增 `ChatSessionStore`、`ChatSessionQuery`、`MongoChatSessionStore`、`PgChatSessionStore` 与 Flyway `V12__chat_sessions.sql`；会话生命周期、列表、搜索、Artifact、Comparison、Project 分析/报告、SelfHarness、OTLP 等全部读写改走 store，生产代码中只剩 `MongoChatSessionStore` 自身引用 `MongoCollection<ChatSession>`。启动迁移 `ChatSessionMigrationService` 在 PG 模式下一次性复制 Mongo 会话并写 `g2_migration_markers`，复制为 insert-only，重复执行不会覆盖 PG 已更新的行。dev 已切 `g2-session-pg-20261110-03 + pg`：Mongo 1770 条全部复制，PG 1770 条；新会话只落 PG、Mongo 不再新增，创建/改名/查询/软删 E2E 通过。

G2 第二批（2026-11-10）：`ChatMessage` 完成 Mongo/PG 双实现，新增 `ChatMessageStore`、`ChatMessageQuery`、`MongoChatMessageStore`、`PgChatMessageStore` 与 Flyway `V13__chat_messages.sql`；会话历史、会话读取 tail、历史搜索、Memory evidence/consolidation、Comparison、Project 分析/playbook 全部改走 store。PG 表使用 `session_id + seq` 唯一约束、`content` 独立列（JSON 文档中移除 content 避免重复存储）、`created_at/trace_id/role` 索引和 `pg_trgm` content 索引。启动迁移 `ChatMessageMigrationService` 复制 17477 条消息（1754 个会话）；迁移中处理了真实脏数据：NUL 字符和字面量 `\u0000`，最终在 JSON 树层清洗 NUL，避免破坏合法转义。dev 已切 `g2-chat-message-20261110-04 + pg`：历史消息回读、搜索条件、新消息只写 PG 均验证通过；`ChatMessage` 生产代码中只剩 Mongo store 和迁移服务引用 `MongoCollection<ChatMessage>`。

G2 第三批（2026-11-10）：`FileRecord` 完成 Mongo/PG 双实现，新增 `FileRecordStore`、`FileRecordQuery`、`MongoFileRecordStore`、`PgFileRecordStore`、`FileRecordMigrationService` 与 Flyway `V14__file_records.sql`；FileService、Artifact 列表/共享、For You、SelfHarness、MediaJob artifact、Project 属性/报告/分析和 file-storage-migration 全部改走 store。PG 表只存元数据，不存 `data`；`PgFileRecordStore` 对仍带 inline content 的记录直接拒绝，防止静默丢内容。dev 数据 216 个文件、7 个用户、170 个共享全部复制；上传只写 PG、Mongo 不新增，元数据/下载 URL/共享 token/删除 E2E 通过。

G2 第四批（2026-11-10）：剩余小集合 `SessionFeedback`、`SessionNotice`、`UserChannelTarget`、`SessionAttachmentRef`、`GeminiFile`、`UserReport`、`UserTodo` 完成 Mongo/PG 双实现（Flyway `V15__small_collections.sql`），统一 JSONB 文档表 + 类型化查询；`SmallCollectionStoreModule` 在 channel/session/gateway 之前绑定，`SmallCollectionMigrationService` 一次事务复制并写 `small_collections_v1`。dev 复制 feedback 0、notices 3、targets 1、attachments 17、gemini 0、reports 0、todos 0；For You 报告/待办新建/更新/删除只写 PG，dashboard/list/active count E2E 通过。至此 G2 十个集合全部切到 PG，Mongo 对应集合保留未删。

UAT G2（2026-11-10）：Flyway V12–V15 已应用；`g2-uat-20261110-01` 先以独立 canary 完成全量复制（sessions 3926、messages 22037、files 11503、attachments 1054 等，792MB 消息复制 55s，启动 70s），随后 UAT 主服务缩容到 0、清空 G2 PG 表并重跑全量复制，完成受控切流；PG 与 Mongo 计数一致，公网 UAT API 的 session list/history/For You/artifact 均通过。UAT 主服务 2/2 Ready，canary 已删除。dev 同步到同一最终镜像 `g2-uat-20261110-01`。`ChatMessage`、`FileRecord` 及其余 G2 集合仍待迁移。

## 1. 结论先行

1. **不是一张表一张表切生产**。表之间有外键、查询关联和事务边界，单表切换会出现“一半 Mongo、一半 PG”的不一致；生产只做**一次切流**。
2. **开发和验证按组进行**。66 个集合分成 5 个业务组，每组完成 schema、store、查询改写、测试；组内按依赖顺序装载数据。
3. **数据迁移按表执行**。切流窗口内按依赖顺序逐表 `COPY`/批量写入 PG，失败可重跑；不是把 66 个集合一次性 dump 成 JSON 再猜结构。
4. **预计工作量 8–12 周**（1–2 人全职），其中切流窗口 2–4 小时；UAT `spans` 归档另算 2–4 小时。
5. **用户影响**：开发期间生产不变；切流窗口内服务不可用；内部用户可以接受。UAT 先完整演练一次。

## 2. 目标与非目标

### 2.1 目标

- `core-ai-server` 不再依赖 `Mongo` / `MongoCollection` / BSON 查询。
- 所有业务集合有明确的 PG 表、索引、约束和保留策略。
- 查询改成 SQL/Repository，分页、排序、聚合、并发更新语义可验证。
- 一次性停机迁移，Mongo 只作为切流前快照和回退源。
- Dataset 继续使用现有 PG 实现；UAT `spans` 只归档到对象存储。

### 2.2 非目标

- 不追求把每个字段都拆成第三范式；允许 `jsonb` 承载灵活字段。
- 不保留 Mongo 查询语法兼容层。
- 不要求零停机。
- 不在本次迁移中重写 Redis、对象存储、前端。
- 不要求一次上线就删除所有历史 Mongo 备份。

## 3. 现状与范围

| 项目 | 数量/规模 |
|---|---:|
| `registerMongo()` 注册集合 | 66 |
| UAT 实际集合 | 62（空集合不落盘） |
| prod 实际集合 | 58 |
| `SchemaMigrationV*` 类 | 102 |
| UAT 数据（含 spans） | 120.5 GB / 465,950 docs |
| UAT 数据（不含 spans，需迁 PG） | 约 7.9 GB |
| prod 数据（含 spans） | 1.65 GB / 34,279 docs |
| UAT `spans` | 292,234 docs / 112.6 GB，仅归档 |
| prod `spans` | 15,357 docs / 1.47 GB，迁移 |

代码访问面：

- 业务代码通过 `MongoCollection<T>` 注入访问集合，接口包含 `count / insert / bulkInsert / get / findOne / find / forEach / aggregate / replace / bulkReplace / update / delete / bulkDelete`。
- 运行时没有多文档事务、change stream、`$lookup`、`$text`；`$lookup/$merge/$out` 只在历史 schema migration 中。
- 102 个 schema migration 负责索引、TTL、回填、历史数据修复；原生 PG 后由 Flyway + 一次性数据 Job 取代。

## 4. 迁移策略：按组开发，按表装载，一次切流

### 4.1 为什么不按表切生产

- `ChatSession`、`ChatMessage`、`AgentRun`、`Trace` 等存在跨集合查询和引用；单表切 PG 后，应用要么读两个库，要么维护双写，复杂度和风险都高于一次性切流。
- Mongo 的 `_id` 排序、嵌套字段、数组、TTL 语义和 PG 不同；单表切换会让分页/排序在边界处出现不一致。
- 内部用户可接受停机，没必要用“在线逐表迁移”换取复杂度。

### 4.2 采用的方式

| 维度 | 做法 |
|---|---|
| 开发 | 按 5 个业务组，逐组完成 schema、store、查询、测试 |
| 代码合并 | 每组 PG 实现先合并，但生产仍走 Mongo；通过 `SYS_STORE_MODE=mongo` 保持原行为 |
| 数据装载 | 切流窗口内按依赖顺序逐表迁移，支持断点重跑 |
| 生产切换 | 所有组完成后，一次维护窗口切到 `SYS_STORE_MODE=pg` |
| 回退 | 切流前 Mongo 无损；切流后只能从 dump 恢复，接受切流后写入丢失 |

## 5. 分组与集合清单

### G1：身份、配置、项目基础

| 集合 | 目标模型 | 说明 |
|---|---|---|
| `User` | 类型化表 | 账号、状态、类型、时间戳 |
| `ApiKey` | 类型化表 | `user_id`、hash、scope、过期时间 |
| `SystemSettings` | 类型化 + `jsonb` | 单例配置，`data jsonb` |
| `SystemPrompt` | 类型化 + `jsonb` | 名称、内容、版本 |
| `ServiceApi` | 类型化 + `jsonb` | API 定义 |
| `Trigger` | 类型化 + `jsonb` | 触发器配置 |
| `GatewayModelConfig` | 类型化 + `jsonb` | provider/model 配置 |
| `GatewayProviderConfig` | 类型化 + `jsonb` | provider 配置 |
| `ToolRegistryEntry` | 类型化 + `jsonb` | 工具元数据 |
| `SkillDefinition` | 类型化 + `jsonb` | 技能元数据/文件清单 |
| `MarketplaceRepo` | 类型化 + `jsonb` | 仓库配置 |
| `ChannelConfigView` | 类型化表 | `channels` 集合的配置视图类 |
| `OcgConfigView` | 类型化表 | `ocg_configs` 集合的配置视图类 |
| `Project` | 类型化表 | 项目主数据 |
| `ProjectSubject` | 类型化表 | 项目主体 |
| `ProjectSubjectAttribution` | 类型化表 | 归因 |
| `ProjectSubjectEvent` | 分区表 | 事件日志，按时间分区 |
| `ProjectTargetScan` | 类型化 + `jsonb` | 扫描结果 |
| `ProjectReportDraft` | 类型化 + `jsonb` | 报告草稿 |
| `ProjectStatsEntity` | 类型化 + `jsonb` | 项目统计 |
| `PromptTemplate` | 类型化 + `jsonb` | 模板 |
| `Dataset` / `DatasetRecord` | 已迁移，删除 Mongo 版本 | 继续用现有 PG store |
| `SchemaVersion` | 删除 | 由 Flyway `flyway_schema_history` 取代 |

### G2：会话、消息、文件

| 集合 | 目标模型 | 说明 |
|---|---|---|
| `ChatSession` | 类型化 + `jsonb` | 会话主数据 + 状态 |
| `ChatMessage` | 分区表 | 按会话/时间查询，内容 `jsonb` |
| `SessionFeedback` | 类型化表 | 会话反馈 |
| `SessionNotice` | 类型化表 | 会话通知 |
| `UserChannelTarget` | 类型化表 | 用户渠道目标 |
| `FileRecord` | 类型化 + `jsonb`/`bytea` | 文件元数据；内容按现有对象存储策略 |
| `GeminiFile` | 类型化表 | Gemini 文件引用 |
| `SessionAttachmentRef` | 类型化表 | 会话附件引用 |
| `UserReport` | 类型化 + `jsonb` | 用户报告 |
| `UserTodo` | 类型化 + `jsonb` | 用户待办 |

### G3：Agent、运行、任务

| 集合 | 目标模型 | 说明 |
|---|---|---|
| `AgentDefinition` | 类型化 + `jsonb` | agent 定义、工具、配置 |
| `AgentVersion` | 类型化 + `jsonb` | 发布版本快照 |
| `AgentSchedule` | 类型化表 | agent 定时任务 |
| `SessionSchedule` | 类型化表 | 会话定时任务 |
| `AgentRun` | 分区表 | 运行记录，按时间分区 |
| `BackgroundTask` | 类型化 + `jsonb` | 后台任务 |
| `AsyncToolTaskRecord` | 类型化 + `jsonb` | 异步工具任务 |
| `HubCall` | 分区表 | Hub 调用审计，按时间分区 |
| `MediaJob` | 类型化 + `jsonb` | 媒体任务 |
| `Notification` | 类型化 + `jsonb` | 通知 |
| `CostAlertRule` | 类型化 + `jsonb` | 成本告警规则 |
| `CostAlertEvent` | 分区表 | 成本告警事件 |
| `SandboxSnapshotDoc` | `jsonb` 表 | 沙箱快照 |
| `SandboxEpochDoc` | 类型化 + `jsonb` | 沙箱 epoch |

### G4：记忆、对比、工作流、回放

| 集合 | 目标模型 | 说明 |
|---|---|---|
| `AgentMemory` | 类型化 + `jsonb` | 分层记忆；如含向量，评估 pgvector |
| `AgentMemoryExtractionCursor` | 类型化表 | 抽取游标 |
| `AgentMemoryExperimentConfig` | 类型化 + `jsonb` | 实验配置 |
| `AgentMemoryExperimentRun` | 类型化 + `jsonb` | 实验运行 |
| `AgentComparison` | 类型化 + `jsonb` | 对比实验 |
| `ComparisonVote` | 类型化表 | 投票 |
| `WorkflowDefinition` | 类型化 + `jsonb` | 工作流定义 |
| `WorkflowPublishedVersion` | 类型化 + `jsonb` | 发布版本 |
| `WorkflowRun` | 分区表 | 运行实例 |
| `WorkflowNodeRun` | 分区表 | 节点运行 |
| `ReplayExperiment` | 类型化 + `jsonb` | 回放实验 |
| `ReplayRun` | 类型化 + `jsonb` | 回放运行 |

### G5：Trace、统计、分析

| 集合 | 目标模型 | 说明 |
|---|---|---|
| `Trace` | 分区表 | 按时间分区，核心字段类型化，attributes `jsonb` |
| `Span` | 分区表 | prod 迁移；UAT 只归档，不建表数据 |
| `TracePayloadBlob` | 分区表 / `bytea` | 大 payload；按 trace_id 索引 |
| `RuntimeSample` | 分区表 | 运行时采样，数值列类型化 |
| `AnalyticsDailyStats` | 类型化 + `jsonb` | 日统计，维度字段类型化 |
| `TraceDailyStats` | 类型化 + `jsonb` | Trace 日统计 |

## 6. PostgreSQL 数据模型约定

- **主键**：保留原字符串 ID（ObjectId hex 或业务 ID），统一 `text primary key`；不重新生成 UUID，避免跨表引用断裂。
- **时间**：`timestamptz`；Mongo `Date` 统一 UTC。
- **灵活字段**：`jsonb`；只对过滤、排序、关联字段建类型化列/表达式索引。
- **枚举**：`text` + `check` 约束或 lookup 表；不依赖 PG enum 类型，方便回滚/扩展。
- **数组/嵌套文档**：`jsonb`；需要查询的路径建 GIN 或表达式索引。
- **二进制**：`bytea`；大文件继续走对象存储，PG 只存元数据/引用。
- **索引**：以现有 Mongo `getIndexes()` 为基线，逐集合映射 B-tree / unique / partial / GIN；不照搬 TTL 索引，改用分区或保留 Job。
- **分区**：`ChatMessage`、`AgentRun`、`HubCall`、`Trace`、`Span`、`TracePayloadBlob`、`RuntimeSample`、`ProjectSubjectEvent` 按时间分区；保留策略用 `DROP PARTITION`，不依赖 TTL。
- **Schema**：业务表放 `app` schema；Dataset 继续用 `app`/`dataset` 现有结构；表名统一 snake_case 复数。

## 7. 代码改造

### 7.1 访问层

- 删除业务代码中的 `MongoCollection<T>` 注入，改为每个模块的 PG store：
  - `UserPgStore`、`ChatSessionPgStore`、`AgentRunPgStore`、`TracePgStore` 等；
  - 简单集合可以共用一个 `PgCrudStore<T>` + 明确的表/列映射；
  - 复杂查询（分页、聚合、join）写模块内 SQL，不提供 Mongo filter 兼容层。
- 保留 service 层接口不变，减少上层改动；store 实现从 Mongo 换成 PG。
- 所有 SQL 走 core-ng `Database` / 参数绑定，不拼字符串。
- 分页统一 keyset：`(created_at, id)` 或 `(id)`，替代 Mongo `_id > lastId`。
- 原子更新用 `UPDATE ... WHERE ... RETURNING` / `SELECT FOR UPDATE`；需要跨表一致性时用 PG 事务。

### 7.2 删除 Mongo 专属代码

切流完成后删除：

- `MongoConfig` / `registerMongo()` / `MongoCollection` 注入；
- `SchemaMigrationManager` 和 102 个 `SchemaMigrationV*`；
- `MongoNativeDatasetRecordStore`、`MongoDatasetMetaStore`、`MongoDatasetRecordStore`；
- `StartupDependencyCheck` 的 Mongo 探测，改为 PG 探测；
- `pom.xml` 的 Mongo driver 依赖（确认无其他用途后）。

### 7.3 查询盘点

每组开始前做一次查询盘点，逐条记录：

| 现状 | 目标 |
|---|---|
| `Filters.eq/gt/in/exists/regex` | 明确的 SQL `WHERE` 方法 |
| `Sorts.desc("created_at")` | `ORDER BY created_at DESC, id DESC` |
| `Query.limit/skip` | keyset 分页 + `LIMIT` |
| `Count` | `COUNT(*)` 或近似统计 |
| `Aggregates.match/group/sort/project` | SQL `GROUP BY` / 窗口函数 |
| `replace/update` | `INSERT ... ON CONFLICT` / `UPDATE` |
| `bulkInsert/bulkDelete` | `COPY` / `UNNEST` / 批量 `IN` |

没有盘点到、但运行时才发现的查询，按 bug 处理，不允许临时加回 Mongo 兼容层。

## 8. 数据迁移 Job

一次性、离线、可重跑：

1. 从 Mongo 按 `_id` 分页读取集合，每批 1000–5000 条。
2. 转换成 PG row（类型、时间、JSON、枚举、null）。
3. 批量写入 PG：大表用 `COPY`，小表用 batch `INSERT ... ON CONFLICT (id) DO UPDATE`。
4. 每张表记录 checkpoint（last_id、count、失败数），失败可从断点重跑。
5. 迁移后执行 `ANALYZE`，再创建非唯一索引；唯一索引在数据校验后创建。
6. 逐表校验：count、首/中/尾抽样、`id` 集合差、关键字段 hash。
7. UAT `spans` 不进 Job；按归档规范单独处理。

**装载顺序**（按依赖）：

1. G1 基础：User、ApiKey、Project*、AgentDefinition/Version/Schedule、Skill、Marketplace、SystemSettings/SystemPrompt、Gateway*、ToolRegistry、ServiceApi、Trigger、PromptTemplate。
2. G2 会话：ChatSession、ChatMessage、SessionFeedback/Notice、UserChannelTarget、FileRecord、GeminiFile、SessionAttachmentRef、UserReport/UserTodo。
3. G3 运行：AgentRun、BackgroundTask、AsyncToolTask、HubCall、MediaJob、Notification、CostAlert*、SessionSchedule、Sandbox*。
4. G4 记忆/工作流/回放。
5. G5 Trace/统计（prod 含 Span；UAT 不含 Span）。

## 9. 切流流程

### 9.1 切流前

- 冻结 Mongo schema migration；确认所有历史 migration 已 applied。
- UAT 完整演练：停服 → 备份 → 迁移 → 校验 → 切 PG → smoke → 回退。
- 生产备份：Mongo dump 上传对象存储；PG 先做一次 base backup。
- 公告维护窗口，内部用户避开工作时间。

### 9.2 切流窗口

1. 停写：`kubectl -n prod-ai scale deploy core-ai-server --replicas=0`，确认无 in-flight 写。
2. 最终 Mongo dump；记录 `mongodump` 时间点和 checksum。
3. 执行数据迁移 Job；按表 checkpoint；失败可重跑。
4. 数据校验：count、抽样、关键索引、`ANALYZE`。
5. 部署 `SYS_STORE_MODE=pg` 的镜像；先起 1 个 pod 跑 smoke：
   登录、会话、发消息、agent run、trace、dataset、analytics、文件、定时任务。
6. 扩到全副本；观察错误率、p95、PG 慢查询、连接数。
7. 保留 Mongo 只读 30 天；确认无回退需求后下线。

### 9.3 回退

- **切流前**：Mongo 未变，切回 `SYS_STORE_MODE=mongo` 即可，无损。
- **切流后**：没有双写；回退需要停服、用 T0 dump 恢复 Mongo、切回旧镜像，切流后写入丢失。内部使用可接受，但要在 60 分钟内决策。

## 10. 排期与分工

| 阶段 | 工作 | 预估 |
|---|---|---|
| 0 | 查询盘点、表结构设计、索引映射 | 1–2 周 |
| 1 | Flyway baseline、PG row/store 基础设施、迁移 Job 框架 | 1 周 |
| 2 | G1 身份/配置/项目 | 1–2 周 |
| 3 | G2 会话/消息/文件 | 1–2 周 |
| 4 | G3 Agent/运行/任务 | 1–2 周 |
| 5 | G4 记忆/工作流/回放 | 1 周 |
| 6 | G5 Trace/统计（UAT spans 归档） | 1–2 周 |
| 7 | 数据迁移 Job 完善、逐表校验 | 1 周 |
| 8 | UAT 全量演练、性能基线、回退演练 | 1 周 |
| 9 | 生产切流 + 观察 | 1 天（窗口 2–4 小时） |
| 10 | 稳定运行、删除 Mongo 代码/依赖 | 2 周 |

1 人全职约 10–13 周；2 人可按 G1/G2 与 G3/G4 并行，约 7–9 周。实际取决于查询复杂度和回归范围。

## 11. 风险

| 风险 | 影响 | 对策 |
|---|---|---|
| 66 个集合的隐藏查询未盘点 | 运行时 SQL 缺失/结果不一致 | 每组先做查询清单；全量 API 回归；UAT 真实流量 |
| BSON 类型/空值/嵌套字段差异 | 数据语义变化 | 逐字段映射；抽样 hash；类型测试 |
| 并发更新语义 | 丢更新/重复 | `SELECT FOR UPDATE`/`RETURNING`/唯一约束；并发测试 |
| 大表迁移超时 | 窗口延长 | `COPY`、分批、并行表；UAT 实测 |
| 索引/TTL 映射错误 | 查询慢/数据不清理 | 以 `getIndexes()` 为基线；分区/保留 Job；EXPLAIN 验证 |
| 聚合 SQL 改写错误 | 报表数字不对 | 与 Mongo 结果对拍；固定数据集回归 |
| 一次性切流失败 | 长时间停机 | UAT 演练；回退脚本；Mongo dump |
| 范围膨胀 | 工期失控 | 按组冻结范围；G5 可独立排期；不在迁移中做新功能 |
| UAT spans 归档不可恢复 | 历史 trace 丢失 | 独立归档校验；临时 Mongo 抽样恢复 |
| 内部用户不习惯停机 | 体验 | 提前公告；非工作时间；维护页 |

## 12. 验收标准

- [ ] 业务代码中不再有 `MongoCollection` / `Mongo` / BSON 查询依赖；
- [ ] 66 个集合都有 PG 表/视图，或明确标记为已删除/Dataset 已有；
- [ ] 每张表有主键、必要索引、保留策略；
- [ ] 数据迁移后 count/抽样/关键字段 hash 全部通过；
- [ ] 所有 API smoke 通过，关键 p95 不明显劣化；
- [ ] 并发更新、分页、排序、聚合有回归测试；
- [ ] UAT `spans` 归档通过 manifest/checksum/抽样恢复；
- [ ] 回退演练通过（切流前）；
- [ ] 生产切流后连续 7 天无 P0/P1；
- [ ] Mongo 代码、schema migration、driver 依赖删除；Mongo 下线。

## 13. 待确认

1. **表模型粒度**：是否接受“类型化列 + `jsonb`”混合模型，而不是全部第三范式？
   - 建议接受：66 个集合全拆关系表会显著拉长工期，且很多字段只用于展示。
2. **大字段**：`TracePayloadBlob`、`FileRecord` 的内容是否全部进 PG，还是对象存储 + PG 引用？
   - 建议：文件内容继续对象存储；`TracePayloadBlob` 小数据进 `bytea`，超大 payload 评估对象存储。
3. **UAT `spans`**：已确认只归档；prod 的 `spans` 是否完整迁移？
   - 当前设计：prod 迁移，UAT 归档。
4. **切流时间**：是否可以接受周末/夜间 2–4 小时停机？
5. **人力资源**：1 人还是 2 人并行？这直接决定 10–13 周还是 7–9 周。
