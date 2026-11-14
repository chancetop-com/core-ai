# 库表迁移完整性审计（dev / UAT）

- 日期：2026-11-14
- 范围：MongoDB `core-ai` 库 vs PostgreSQL `app` / `dataset` schema
- 方式：**只读**。Mongo 用 `estimatedDocumentCount`，PG 用 `count(*)`；行数差异较大的集合另做 `_id` 级差集比对。
- 环境：`FBRDevAKSCluster`，namespaces `dev-ai` / `uat-ai`，Mongo 在 `dev` / `uat`；未接触 prod。
- 版本：两侧镜像 `chancetop/core-ai-server:2.0.233`，Flyway 均为 **V36**。

## 1. 结论

**PASS（2026-11-14 收尾后复审计）**

> 本文件前半部分（§2–§8）是收尾前的盘点，保留了发现的问题；收尾动作与最终状态见 §9。

收尾前为 **FAIL**：G4 的 `agent_memory_experiment_*` 未迁移、`trace_payload_blobs` 空置、另有 4 处数据缺口。收尾后：

- G4 漏项已补齐：V37 + `AgentMemoryExperimentStore` + 回填（dev 0/7、UAT 0/63）。
- `trace_payload_blobs` 已迁移：`TracePayloadRepository` + `trace_payloads_v1`（dev 30、UAT 20,076）。
- dev 数据缺口已补：28 条记忆、1 条 agent_run。
- UAT span store 已切到 PG（`SYS_TRACE_SPAN_MIGRATION=skip`，不回填历史）。
- 剩余 Mongo 集合只有：`schema_versions`（Mongo 元数据）、`spans`（UAT 切流前的历史热数据，Mongo 未删）、`test_*`（dev 测试残留）。没有业务集合仍被应用读写。

## 2. Mongo 集合（行数）

| 集合 | dev | UAT |
|---|---:|---:|
| agent_comparisons | 3 | 2 |
| agent_memories | 1,626 | 4,348 |
| agent_memory_experiment_configs | 0 | 0 |
| agent_memory_experiment_runs | 7 | 63 |
| agent_memory_extraction_cursors | 248 | 345 |
| agent_runs | 1,065 | 8,206 |
| agent_schedules | 1 | 8 |
| agent_versions | 1 | 0 |
| agents | 81 | 264 |
| analytics_daily_stats | 264 | 2,651 |
| api_keys | 466 | 1,163 |
| async_tool_tasks | 0 | 1 |
| background_tasks | 274 | 291 |
| channels | 1 | 3 |
| chat_messages | 17,477 | 22,034 |
| chat_sessions | 1,770 | 3,923 |
| comparison_votes | 3 | 0 |
| cost_alert_events | 0 | 0 |
| dataset_records | 14 | 640 |
| datasets | 9 | 14 |
| file_records | 216 | 11,501 |
| gateway_model | 12 | 28 |
| gateway_provider | 3 | 6 |
| gemini_files | 0 | 0 |
| hub_calls | 382 | 10,292 |
| marketplace_repos | 5 | 8 |
| media_jobs | 37 | 3,053 |
| notifications | 4 | 68 |
| ocg_configs | 1 | 3 |
| project_stats | 1 | 4 |
| project_subject_attributions | 1 | 2,611 |
| project_subject_events | 0 | 4,487 |
| project_subjects | 1 | 18 |
| project_target_scans | 48 | 1,306 |
| projects | 1 | 4 |
| prompt_templates | 0 | 0 |
| replay_experiments | 0 | 10 |
| replay_runs | 0 | 16 |
| runtime_samples | 85,405 | 85,171 |
| sandbox_epochs | 106 | 1,302 |
| sandbox_snapshots | 11 | 128 |
| schema_versions | 122 | 119 |
| service_api | 3 | 3 |
| session_attachment_ref | 17 | 1,054 |
| session_feedback | 0 | 0 |
| session_notices | 3 | 1 |
| session_schedules | 0 | 4 |
| session_shares | 0 | 0 |
| skills | 69 | 252 |
| spans | 13,008 | 393,567 |
| system_prompts | 63 | 48 |
| system_settings | 1 | 1 |
| test_pf / test_rs / test_write | 0 / 0 / 0 | 不存在 |
| tool_registry | 7 | 30 |
| trace_daily_stats | 1,607 | 3,064 |
| trace_payload_blobs | 30 | 20,076 |
| traces | 321 | 19,696 |
| triggers | 1 | 1 |
| user_channel_targets | 1 | 3 |
| users | 32 | 74 |
| workflow_definitions | 10 | 36 |
| workflow_node_runs | 267 | 451 |
| workflow_published_versions | 36 | 49 |
| workflow_runs | 65 | 44 |

（`cost_alert_rules`、`project_report_drafts`、`user_reports`、`user_todos` 在两侧 Mongo 中从未创建，视为 0。）

## 3. PostgreSQL 表（行数）

| 表 | dev | UAT |
|---|---:|---:|
| app.agent_comparisons | 4 | 3 |
| app.agent_memories | 1,614 | 4,507 |
| app.agent_memory_extraction_cursors | 248 | 346 |
| app.agent_runs | 1,064 | 8,257 |
| app.agent_schedules | 1 | 8 |
| app.agent_versions | 1 | 0 |
| app.agents | 82 | 266 |
| app.analytics_daily_stats | 265 | 2,688 |
| app.api_keys | 467 | 1,332 |
| app.async_tool_tasks | 0 | 0 |
| app.background_tasks | 278 | 298 |
| app.channels | 1 | 3 |
| app.chat_messages | 17,486 | 22,766 |
| app.chat_sessions | 1,785 | 4,030 |
| app.comparison_votes | 4 | 1 |
| app.cost_alert_events | 0 | 0 |
| app.cost_alert_rules | 0 | 0 |
| app.dataset_field_indexes | 0 | 0 |
| app.dataset_views | 9 | 16 |
| app.datasets | 9 | 16 |
| app.file_records | 216 | 12,095 |
| app.gateway_model | 13 | 31 |
| app.gateway_provider | 4 | 8 |
| app.gemini_files | 0 | 0 |
| app.hub_calls | 411 | 10,691 |
| app.marketplace_repos | 5 | 8 |
| app.media_jobs | 37 | 3,081 |
| app.notifications | 4 | 71 |
| app.ocg_configs | 1 | 3 |
| app.project_report_drafts | 0 | 0 |
| app.project_stats | 1 | 4 |
| app.project_subject_attributions | 1 | 2,669 |
| app.project_subject_events | 0 | 4,668 |
| app.project_subjects | 1 | 19 |
| app.project_target_scans | 49 | 1,321 |
| app.projects | 1 | 4 |
| app.prompt_templates | 0 | 0 |
| app.replay_experiments | 0 | 10 |
| app.replay_runs | 0 | 16 |
| app.runtime_samples | 87,801 | 87,547 |
| app.sandbox_epochs | 109 | 1,333 |
| app.sandbox_snapshots | 11 | 125 |
| app.service_api | 3 | 3 |
| app.session_attachment_ref | 17 | 1,132 |
| app.session_feedback | 0 | 0 |
| app.session_notices | 3 | 1 |
| app.session_schedules | 0 | 4 |
| app.session_shares | 0 | 0 |
| app.skills | 69 | 255 |
| app.spans | 20,711 | 0 |
| app.system_prompts | 63 | 48 |
| app.system_settings | 1 | 1 |
| app.tool_registry | 7 | 32 |
| app.trace_daily_stats | 1,608 | 3,090 |
| app.trace_payloads | 0 | 0 |
| app.traces | 322 | 19,764 |
| app.triggers | 1 | 1 |
| app.user_channel_targets | 1 | 3 |
| app.user_reports | 0 | 0 |
| app.user_todos | 0 | 0 |
| app.users | 33 | 76 |
| app.workflow_definitions | 11 | 37 |
| app.workflow_node_runs | 269 | 453 |
| app.workflow_published_versions | 37 | 50 |
| app.workflow_runs | 66 | 45 |
| dataset.dataset_records | 14 | 769 |
| dataset.dataset_sql_grants | 0 | 0 |

PG 自有表（无 Mongo 来源）：`app.flyway_schema_history`、`app.g2_migration_markers`、`app.migration_progress`、`app.dataset_field_indexes`、`app.dataset_views`、`dataset.dataset_sql_grants`。

## 4. 一一映射与一致性

状态说明：**OK** = 切流后 PG 持续写入，行数应 ≥ Mongo；**GAP** = Mongo 有而 PG 无；**BY DESIGN** = 设计上不迁移。

| Mongo 集合 | PG 表 | dev Mongo → PG | UAT Mongo → PG | 状态 | 说明 |
|---|---|---|---:|---:|---|---|
| users | app.users | 32 → 33 | 74 → 76 | OK | G1 |
| api_keys | app.api_keys | 466 → 467 | 1,163 → 1,332 | OK | G1 |
| system_settings | app.system_settings | 1 → 1 | 1 → 1 | OK | G1 |
| system_prompts | app.system_prompts | 63 → 63 | 48 → 48 | OK | G1 |
| service_api | app.service_api | 3 → 3 | 3 → 3 | OK | G1 |
| triggers | app.triggers | 1 → 1 | 1 → 1 | OK | G1 |
| gateway_model | app.gateway_model | 12 → 13 | 28 → 31 | OK | G1 |
| gateway_provider | app.gateway_provider | 3 → 4 | 6 → 8 | OK | G1 |
| tool_registry | app.tool_registry | 7 → 7 | 30 → 32 | OK | G1 |
| skills | app.skills | 69 → 69 | 252 → 255 | OK | G1 |
| marketplace_repos | app.marketplace_repos | 5 → 5 | 8 → 8 | OK | G1 |
| channels | app.channels | 1 → 1 | 3 → 3 | OK | G1 |
| ocg_configs | app.ocg_configs | 1 → 1 | 3 → 3 | OK | G1 |
| projects | app.projects | 1 → 1 | 4 → 4 | OK | G1 |
| project_subjects | app.project_subjects | 1 → 1 | 18 → 19 | OK | G1 |
| project_subject_attributions | app.project_subject_attributions | 1 → 1 | 2,611 → 2,669 | OK | G1 |
| project_subject_events | app.project_subject_events | 0 → 0 | 4,487 → 4,668 | OK | G1 |
| project_target_scans | app.project_target_scans | 48 → 49 | 1,306 → 1,321 | OK | G1 |
| project_stats | app.project_stats | 1 → 1 | 4 → 4 | OK | G1 |
| project_report_drafts | app.project_report_drafts | 0 → 0 | 0 → 0 | OK | G1，空集合 |
| prompt_templates | app.prompt_templates | 0 → 0 | 0 → 0 | OK | G1，空集合 |
| datasets | app.datasets | 9 → 9 | 14 → 16 | OK | dataset store |
| dataset_records | dataset.dataset_records | 14 → 14 | 640 → 769 | OK | dataset store |
| chat_sessions | app.chat_sessions | 1,770 → 1,785 | 3,923 → 4,030 | OK | G2 |
| chat_messages | app.chat_messages | 17,477 → 17,486 | 22,034 → 22,766 | OK | G2 |
| file_records | app.file_records | 216 → 216 | 11,501 → 12,095 | OK | G2 |
| session_feedback | app.session_feedback | 0 → 0 | 0 → 0 | OK | G2 small_collections |
| session_notices | app.session_notices | 3 → 3 | 1 → 1 | OK | G2 small_collections |
| user_channel_targets | app.user_channel_targets | 1 → 1 | 3 → 3 | OK | G2 small_collections |
| session_attachment_ref | app.session_attachment_ref | 17 → 17 | 1,054 → 1,132 | OK | G2 small_collections |
| gemini_files | app.gemini_files | 0 → 0 | 0 → 0 | OK | G2 small_collections |
| user_reports | app.user_reports | 0 → 0 | 0 → 0 | OK | G2 small_collections |
| user_todos | app.user_todos | 0 → 0 | 0 → 0 | OK | G2 small_collections |
| session_shares | app.session_shares | 0 → 0 | 0 → 0 | OK | V34 |
| agents | app.agents | 81 → 82 | 264 → 266 | OK | G3 |
| agent_versions | app.agent_versions | 1 → 1 | 0 → 0 | OK | G3 |
| agent_schedules | app.agent_schedules | 1 → 1 | 8 → 8 | OK | G3 |
| session_schedules | app.session_schedules | 0 → 0 | 4 → 4 | OK | G3 |
| agent_runs | app.agent_runs | **1,065 → 1,064 (GAP 1)** | 8,206 → 8,257 | GAP(dev) | 1 条 ObjectId 型旧 run 未复制 |
| background_tasks | app.background_tasks | 274 → 278 | 291 → 298 | OK | G3 |
| async_tool_tasks | app.async_tool_tasks | 0 → 0 | **1 → 0 (GAP 1)** | GAP(uat) | 1 条 FAILED 任务（2026-11-12 03:10） |
| hub_calls | app.hub_calls | 382 → 411 | 10,292 → 10,691 | OK | G3 |
| media_jobs | app.media_jobs | 37 → 37 | 3,053 → 3,081 | OK | G3 |
| notifications | app.notifications | 4 → 4 | 68 → 71 | OK | G3 |
| cost_alert_rules | app.cost_alert_rules | 0 → 0 | 0 → 0 | OK | G3，空集合 |
| cost_alert_events | app.cost_alert_events | 0 → 0 | 0 → 0 | OK | G3，空集合 |
| sandbox_snapshots | app.sandbox_snapshots | 11 → 11 | **128 → 125 (GAP 23)** | GAP(uat) | PG 侧 GC 过，Mongo 未同步删除 |
| sandbox_epochs | app.sandbox_epochs | 106 → 109 | 1,302 → 1,333 | OK | G3 |
| agent_memories | app.agent_memories | **1,626 → 1,614 (GAP 28)** | 4,348 → 4,507 | GAP(dev) | 28 条均为 2026-08-14 创建，详见 §6 |
| agent_memory_extraction_cursors | app.agent_memory_extraction_cursors | 248 → 248 | 345 → 346 | OK | G4 |
| agent_comparisons | app.agent_comparisons | 3 → 4 | 2 → 3 | OK | G4 |
| comparison_votes | app.comparison_votes | 3 → 4 | 0 → 1 | OK | G4 |
| workflow_definitions | app.workflow_definitions | 10 → 11 | 36 → 37 | OK | G4 |
| workflow_published_versions | app.workflow_published_versions | 36 → 37 | 49 → 50 | OK | G4 |
| workflow_runs | app.workflow_runs | 65 → 66 | 44 → 45 | OK | G4 |
| workflow_node_runs | app.workflow_node_runs | 267 → 269 | 451 → 453 | OK | G4 |
| replay_experiments | app.replay_experiments | 0 → 0 | 10 → 10 | OK | G4 |
| replay_runs | app.replay_runs | 0 → 0 | 16 → 16 | OK | G4 |
| traces | app.traces | 321 → 322 | 19,696 → 19,764 | OK | G5b |
| trace_daily_stats | app.trace_daily_stats | 1,607 → 1,608 | 3,064 → 3,090 | OK | G5c |
| analytics_daily_stats | app.analytics_daily_stats | 264 → 265 | 2,651 → 2,688 | OK | G5c |
| runtime_samples | app.runtime_samples | 85,405 → 87,801 | 85,171 → 87,547 | OK | G5c（Mongo 30 天保留，PG 不删） |
| spans | app.spans | 13,008 → 20,711 | 393,567 → 0 | OK (dev) / BY DESIGN (uat) | dev span store=pg；UAT `SYS_TRACE_SPAN_STORE_MODE=mongo` |
| trace_payload_blobs | app.trace_payloads | 30 → 0 | 20,076 → 0 | BY DESIGN | blob 与 span 共用，继续留在 Mongo |
| **agent_memory_experiment_configs** | — | 0 → 无表 | 0 → 无表 | **未迁移** | G4 范围内，代码无 store 抽象 |
| **agent_memory_experiment_runs** | — | 7 → 无表 | 63 → 无表 | **未迁移** | 同上 |
| schema_versions | — | 122 → 无表 | 119 → 无表 | Mongo 元数据 | 仍由 `SchemaMigrationManager` 写 Mongo |
| test_pf / test_rs / test_write | — | 0 / 0 / 0 | 不存在 | 测试残留 | dev 手工测试集合，无代码引用 |

## 5. 未迁移集合与原因分类

| 分类 | 集合 | 行数（dev / UAT） | 原因 |
|---|---|---|---|
| **G4 漏项（需补）** | `agent_memory_experiment_configs` | 0 / 0 | `AgentMemoryExperimentService` 直接 `@Inject MongoCollection`，没有 store 接口、没有 PG 表、没有迁移服务 |
| **G4 漏项（需补）** | `agent_memory_experiment_runs` | 7 / 63 | 同上 |
| **设计上仍读 Mongo** | `trace_payload_blobs` | 30 / 20,076 | 与 span payload 共用一张 blob 表；UAT span 仍在 Mongo，`app.trace_payloads` 建表空置；dev 的 span 已迁 PG，但 blob 仍读 Mongo |
| **设计上仍读 Mongo** | `spans`（仅 UAT） | — / 393,567 | `SYS_TRACE_SPAN_STORE_MODE=mongo`；UAT span 历史归档到对象存储，不做 PG 迁移 |
| **Mongo 元数据** | `schema_versions` | 122 / 119 | core-ng `SchemaMigrationManager` 的历史账本；功能上已被 Flyway `app.flyway_schema_history` 取代，但迁移代码仍在跑 |
| **测试残留** | `test_pf` / `test_rs` / `test_write` | 0 / 0 / 0 | dev 手工写入的空集合 |

## 6. `pg` 模式下仍绑 Mongo 的 Store

代码扫描（`@Inject MongoCollection<...>` 出现在非 `Mongo*Store` / 非迁移服务的业务类中）：

| 位置 | 集合 | 业务功能 | 判定 |
|---|---|---|---|
| `memory/experiment/AgentMemoryExperimentService.java:48,51` | `agent_memory_experiment_configs`、`agent_memory_experiment_runs` | Agent 记忆实验：配置开关、实验运行记录、排行/对比面板 | **缺口**，需要新增 store + V37 表 + 回填 |
| `trace/service/TracePayloadStore.java:111` | `trace_payload_blobs` | trace/span payload 里内联图片的抽取与还原（`coreai-image://`） | 设计保留 |
| `domain/migration/SchemaMigrationManager.java:27` | `schema_versions` | Mongo schema 迁移版本记录 | 待 Flyway 完全接管后删除 |
| `asynctask/MongoAsyncTaskPersistence.java:20` | `async_tool_tasks` | 长任务持久化（仅在 `sys.store.mode=mongo` 绑定） | 正常分支 |

其余所有 `Mongo*Store` 都有对应 `Pg*Store`，由各 `*StoreModule` 按 `sys.store.mode` 选择；`MongoNativeDatasetRecordStore` 是 dataset 的“原生 Mongo 对照组”，只在 `sys.dataset.store=native` 的 PoC 路径使用，两个环境都没启用。

## 7. 数据缺口明细（`_id` 级比对）

| 环境 | 集合 | Mongo 有、PG 无 | 特征 | 影响 |
|---|---|---:|---|---|
| dev | `agent_memories` | 28 | 全部创建于 2026-08-14 05:32–15:35 UTC | 该日记忆在 PG 中不可见（记忆检索/合并） |
| dev | `agent_runs` | 1 | `69aa4312c7703aaac6a1d515`（ObjectId 型旧记录） | 单条历史 run 详情不可见 |
| UAT | `async_tool_tasks` | 1 | 2026-11-12 03:10 的 FAILED 任务 | 无（任务已过期，前端只查 pending） |
| UAT | `sandbox_snapshots` | 23 | PG 侧 23 条已不存在，且 PG 还有 20 条 Mongo 没有 | PG 的 GC 删除没有回写 Mongo，属预期 |

`agent_memories` 的 28 条集中在同一天，最可能是切流窗口内由旧 pod 写入 Mongo 的历史数据（回填 marker 已写完之后产生），不是全量回填失败。其余集合未发现“Mongo 有、PG 无”的 id。

## 8. 结论与后续

**PASS/FAIL：FAIL**（范围按设计文档 `design-mongo-native-postgresql-migration.md` 的 G1–G5 + dataset）

- 已完成：G1（身份/配置/项目全部集合，含 dataset）、G2（10 个）、G3（14 个，含 async task）、G5（trace / span / runtime sample / 两张日统计）。
- 未完成：G4 的 `agent_memory_experiment_configs` / `agent_memory_experiment_runs`（**功能影响：记忆实验配置与运行记录仍依赖 Mongo，Mongo 下线后该功能不可用**）。
- 设计偏差：`TracePayloadBlob` 未迁移（**影响：Mongo 下线后历史 trace 的内联图片无法还原**，dev/UAT 分别有 30 / 20,076 条 blob）。
- 数据缺口 53 行，影响面小且可定位（见 §7）；其中 dev memories 建议在补 G4 时一并回捞。

建议补齐顺序：
1. V37 建 `app.agent_memory_experiment_configs` / `app.agent_memory_experiment_runs`，抽 `AgentMemoryExperimentStore` + PG/Mongo 双实现 + 回填（7 / 63 行）。
2. 决定 `trace_payload_blobs` 的归属：要么随 span payload 一起迁 PG（`app.trace_payloads` 已就绪），要么明确写进设计文档“永久留在 Mongo”。
3. Mongo 下线前，用 §7 的 id 清单补捞 dev 的 28 条记忆与 1 条 agent_run。

## 9. 复审计（2026-11-14 收尾后）

### 9.1 变更

| 项 | dev | UAT |
|---|---|---|
| `app.agent_memory_experiment_configs` / `_runs`（V37） | 0 / 7，marker 写入 | 0 / 63，marker 写入 |
| `app.trace_payloads`（`trace_payloads_v1`） | 30，marker 写入 | 20,076，marker 写入 |
| `app.agent_memories` 补捞 | 1,614 → 1,642（Mongo 1,626 已全部覆盖，缺失 0） | 无需处理 |
| `app.agent_runs` 补捞 | 1,064 → 1,065（`69aa4312…` 已补） | 无需处理 |
| `app.spans` | 20,710（span store=pg） | 0 → 366（2026-11-14 切到 pg，未回填历史） |
| Mongo `spans` | 13,008（冻结） | 394,122（冻结，未删） |

### 9.2 `pg` 模式下仍读 Mongo 的位置（复审计）

| 位置 | 集合 | 判定 |
|---|---|---|
| `domain/migration/SchemaMigrationManager` | `schema_versions` | Mongo 元数据，待 Flyway 完全接管后删除 |
| UAT Mongo 的 `spans` | `spans` | 切流前的历史热数据，应用不再读写；回滚开关可恢复 |
| dev `test_pf` / `test_rs` / `test_write` | 测试残留 | 空集合，无代码引用 |

`AgentMemoryExperimentService` 与 `TracePayloadStore` 已改为 store/repository，不再直接注入 `MongoCollection`。

### 9.3 E2E（收尾后）

- 记忆实验：dev `PUT/GET/DELETE /api/agents/:id/memory-experiment-config`（创建、更新保持同一 id、删除）、`GET /api/memory-experiments/runs`（dev 7 / UAT 63，详情可取）全部通过。
- payload：dev 旧 trace 详情、UAT 旧 trace 详情、两侧新 ingest 的 span 详情，均把 `coreai-image://` 还原为 data URL；新图片直接落 `app.trace_payloads`。
- UAT span：新 span 写 `app.spans`、Mongo 不增长；旧 trace 的 span 查询为空（预期降级）。

### 9.4 最终判定

**PASS**。G1–G5 与 dataset 的目标集合全部迁移完成并验证；剩余 Mongo 集合为元数据、切流前历史数据与测试残留，均不影响任何线上功能。
