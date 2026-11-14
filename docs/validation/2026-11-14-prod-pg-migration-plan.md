# Prod PostgreSQL 迁移计划（只读调研 + 执行方案）

- 日期：2026-11-14
- 状态：计划，**未对 prod 做任何变更**（本文所有数据来自 `kubectl --context FBRProdAKSCluster -n prod-ai get/exec` 只读查询）
- 参考：dev/UAT 已完成 G1–G5 + dataset，见 `docs/validation/2026-11-14-migration-completeness-audit.md`（PASS）
- 变更入口：`fbr-prod-env-project`（`azure/prod/kube/resource/app/ai/20-core-ai-server.yaml`），**不直接 kubectl edit**

## 1. 结论摘要

| 项 | 结论 |
|---|---|
| 数据总量 | Mongo `core-ai` 库 2.6 GB data / 2.4 GB storage，58 个集合、约 7.2 万文档 |
| 最大表 | `spans` 27,310 条但 **2.38 GB**（平均 87 KB/条，payload 很重）；其余集合合计 < 400 MB |
| PG 容量 | CNPG 3 实例，50 GiB/实例，当前仅用 8 MB；4Gi 内存 / 2 CPU，**容量充足** |
| Flyway 现状 | prod 的 `public.flyway_schema_history` 只有 V1（dataset baseline），**`app` schema 没有历史表**，需要 baseline |
| dataset | 已在 PG（`SYS_DATASET_STORE=postgresql`），`app.datasets` 1 / `dataset.dataset_records` 17，与 Mongo 一致，**迁移范围外、不要动** |
| 建议窗口 | 本地时间（UTC+8）**01:00–02:00**：prod 流量最低（01:00 约 9 traces/h，03:00 起自动化任务爬升） |
| 切流方式 | 分 2 个窗口：S1=G1–G4（G5 显式钉 Mongo），S2=G5（trace+span+payload+runtime 必须同窗口）。每阶段独立回滚 |
| 停机 | 每个窗口 `replicas=0` 后再起新版本，全程 10–40 分钟（S2 主要花在 span 回填） |
| 建议 | 先在 **canary 数据库**（`core_ai_canary`）用 prod Mongo 真实数据彩排一遍，量出 span 回填耗时后再定正式窗口 |

## 2. 只读盘点

### 2.1 应用与集群

| 项 | 现状 |
|---|---|
| 镜像 | `chancetop/core-ai-server:latest`（可变 tag，**迁移必须换成固定版本**） |
| 副本 | 2，`maxSurge=1 / maxUnavailable=0`，`minReadySeconds=10` |
| 资源 | requests 200m/512Mi，limits 2000m/**2Gi**；`JAVA_OPTS=-Xmx3072m`（heap 上限高于容器 limit，见风险 R6） |
| 存储开关 | 只有 `SYS_DATASET_STORE=postgresql`；**没有** `SYS_STORE_MODE`，也没有任何 `SYS_TRACE_*` → 其余全部走 Mongo |
| PG | CloudNativePG `postgresql` 3/3 healthy，50 GiB × 3（csi-ssd-wait），2 CPU / 4 GiB，库 `core_ai` 8 MB |
| PG 备份 | `ScheduledBackup postgresql-daily`（每日 02:00），最近一次 5 小时前，barman → Azure `pg-backup/prod` |
| Mongo | 3 副本（`mongodb-0/1/2`，namespace `prod`），每副本 50 GiB PVC，**没有备份任务** |
| Redis | `cache-0`，单实例 |

### 2.2 Mongo 集合（行数 / 大小）

| 集合 | docs | data | 说明 |
|---|---:|---:|---|
| spans | 27,310 | 2,379 MB | **迁移重点**，payload 重（avg 87 KB） |
| runtime_samples | 32,245 | 47 MB | G5c |
| agent_runs | 1,665 | 89 MB | G3 |
| chat_messages | 1,112 | 25 MB | G2 |
| traces | 1,069 | 13 MB | G5b |
| file_records | 672 | <1 MB（storage 33 MB） | G2，元数据 |
| project_subject_events | 2,454 | 1 MB | G1 |
| hub_calls | 2,270 | 2 MB | G3 |
| api_keys | 412 | <1 MB | G1 |
| project_target_scans | 428 | <1 MB | G1 |
| project_subject_attributions | 385 | <1 MB | G1 |
| analytics_daily_stats | 240 | <1 MB | G5c |
| agent_memories | 207 | <1 MB | G4 |
| background_tasks | 186 | <1 MB | G3 |
| skills | 122 | 32 MB | G1（含文件清单） |
| schema_versions | 113 | <1 MB | Mongo 元数据，不迁 |
| trace_payload_blobs | 60 | 10 MB | G5b，**60 条但平均 170 KB** |
| agents | 54 | 1 MB | G3 |
| media_jobs | 20 | <1 MB | G3 |
| dataset_records / datasets | 17 / 1 | <1 MB | 已迁 PG，冻结 |
| users | 21 | <1 MB | G1 |
| agent_memory_extraction_cursors | 16 | <1 MB | G4 |
| gateway_model / provider | 11 / 3 | <1 MB | G1 |
| tool_registry / marketplace_repos | 11 / 5 | <1 MB | G1 |
| agent_schedules | 4 | <1 MB | G3 |
| project_subjects / projects | 3 / 1 | <1 MB | G1 |
| async_tool_tasks / service_api / system_settings / workflow_definitions | 1 / 1 / 1 / 1 | <1 MB | G3/G1/G4 |
| session_attachment_ref | 7 | <1 MB | G2 |
| 其余（对比、回放、告警、通知、工作流运行等） | 0 | — | 空集合 |

Mongo 中没有的集合（prod 从未产生数据）：`channels`、`cost_alert_rules`、`sandbox_epochs`、`project_report_drafts`、`user_reports`、`user_todos`、`session_shares` —— 对应 PG 表建成空表即可。

### 2.3 PG 现状

- `app` schema：只有 `datasets`、`dataset_views`、`dataset_field_indexes`
- `dataset` schema：`dataset_records`、`dataset_sql_grants`、`ds_7cc16fbccd114cca9d5350b914bcf806`（单数据集物理表，17 行）
- `public.flyway_schema_history`：**只有一条 `1 | dataset`**（2026-11-06 手工 baseline 的痕迹），`app` schema 下没有历史表
- `core_ai` 角色：`search_path=app, dataset`（与 UAT 一致）

> 结论：prod 的 Flyway 账本与 dev/UAT 不一致（历史表在 `public` 而非 `app`），**这是本次迁移最容易踩的坑**。

## 3. 需要特殊处理的集合

| 集合 | 处理 | 原因 |
|---|---|---|
| `spans` | prod **全量回填**（`SYS_TRACE_SPAN_MIGRATION=copy`，默认值），不进归档 | 与 UAT 不同：prod 设计上迁移全部 span。27k 条 / 2.4 GB，按 50 条/页 + 8 MB 页上限，预计 5–15 分钟 |
| `trace_payload_blobs` | 回填 60 条 / 10 MB，`trace_payloads_v1` | 迁移后 span/trace 详情从 PG 还原 `coreai-image://` |
| `analytics_daily_stats` / `trace_daily_stats` | 回填（240 / 221） | 历史分析口径依赖；G5b/G5c 已验证 |
| `dataset_*` | **不动** | 已在 PG；Mongo 侧冻结。迁移期间禁止 `SYS_DATASET_STORE` 变更 |
| `schema_versions` | 不迁 | Mongo 元数据，Flyway 接管后保留只读 |
| `sandbox_epochs` / `channels` 等 | 建空表 | prod 无数据，PG 表由 Flyway 创建 |
| `file_records` | 迁移元数据 | 内容已在对象存储；PG 只存元数据（与 dev/UAT 一致） |

## 4. 前置条件

### 4.1 PG 与存储

- [x] CNPG 3 实例 healthy、50 GiB/实例（预计新增 < 3 GB，无需扩容）
- [x] PG 每日备份在跑；**迁移窗口前手动触发一次 `Backup` CR**，并确认 `completed`
- [ ] 迁移窗口把 PG 内存 limit 复核一次（4 GiB 足够；span 回填单批 ≤ 8 MB）
- [ ] 迁移窗口把 server pod 内存 limit 从 2 Gi 提到 **4 Gi**（与 `-Xmx3072m` 对齐，见 R6），窗口结束后可回调

### 4.2 备份

- [ ] **Mongo 目前没有备份**：窗口前做一次全库 `mongodump`（或存储快照），落到对象存储/独立 PVC，保留到 prod 观察期结束（≥ 2 周）
- [ ] 记录 Mongo 各集合行数（本文 §2.2）作为迁移前基线

### 4.3 Flyway baseline

prod 的历史表在 `public` 且只有 V1，直接 `flywayMigrate` 会尝试重放 V1 并与现有表冲突。方案：

1. 在 `core-ai-server-db-migration/conf/prod/resources/flyway.properties` 配置（端口转发到 `postgresql-rw:5432`，用户 `core_ai`；密码从 `postgresql-app` secret 取，文件不提交进 git）
2. 先执行 `./gradlew :core-ai-server-db-migration:flywayMigrate -Penv=prod -Dflyway.baselineVersion=1`
   - 脚本会打开 `baselineOnMigrate`，在 **`app.flyway_schema_history`** 写入 V1 baseline，然后按顺序应用 V2–V37
   - 由于 `core_ai` 的 `search_path=app, dataset`，历史表会落在 `app`（与 dev/UAT 一致）
3. 校验：`app.flyway_schema_history` 最新版本 = 37，`app`/`dataset` 表齐全；`public.flyway_schema_history` 保持原样（历史遗留，不再使用，记录在案）
4. 若 baseline 失败：删除 `app.flyway_schema_history` 后重试；V1–V37 全部 `IF NOT EXISTS`，不会破坏 dataset 数据

### 4.4 密钥与网络

- `SYS_DB_PASSWORD` 已通过 `postgresql-app` secret 注入，无需改动
- Flyway 从本地/跳板机走 `kubectl port-forward`，不要暴露 PG 到公网
- 监控：开迁移时观察 `core-ai-server` 日志（`*MigrationService` / `PagedBackfill`）、PG `pg_stat_activity`、pod 内存

## 5. 迁移步骤

prod 的 G1–G4 共用一个开关（`SYS_STORE_MODE`），G5 有独立开关，因此分两个窗口：

### 阶段 S1：G1–G4（身份/配置/项目、会话/消息/文件、Agent/运行/任务、记忆/对比/工作流/回放）

**前置**：Flyway 完成（§4.3）；G5 显式钉回 Mongo，避免顺带触发 span/trace 回填。

**env 变更（env 仓库）**：

```yaml
- name: SYS_STORE_MODE
  value: pg
- name: SYS_TRACE_STORE_MODE        # 新增：G5 trace 暂不切
  value: mongo
- name: SYS_TRACE_SPAN_STORE_MODE   # 新增：span 暂不切
  value: mongo
- name: SYS_RUNTIME_STORE_MODE      # 新增：runtime sample 暂不切
  value: mongo
```

**步骤**：

1. `replicas: 0`，等 pod 全部退出（写入停止）
2. 提交 env 变更 → 流水线 apply → `replicas: 2`
3. 起 2 个新 pod：G1–G4 的迁移服务在后台虚拟线程里分片复制（marker + advisory lock，单实例执行）
4. 盯日志直到各 marker 写入：`users_v1`、`api_keys_v1`、`agents_v1`、`agent_versions_v1`、`agent_schedules_v1`、`session_schedules_v1`、`chat_sessions_v1`、`chat_messages_v1`、`file_records_v1`、`small_collections_v1`、`agent_runs_v1`、`background_tasks_v1`、`async_tool_tasks_v1`、`hub_calls_v1`、`media_jobs_v1`、`notifications_v1`、`cost_alert_*_v1`、`sandbox_*_v1`、`agent_memories_v1`、`agent_memory_cursors_v1`、`agent_comparisons_v1`、`comparison_votes_v1`、`workflow_*_v1`、`replay_*_v1`、`agent_memory_experiment_*_v1`
5. 行数校验（§7），全绿后进入观察期
6. 观察 60 分钟：登录、会话列表/历史、文件上传下载、Agent 运行、记忆、工作流

**预计**：数据 < 300 MB，迁移 + 启动 5–10 分钟；窗口 01:00–02:00。

### 阶段 S2：G5（trace / span / payload / runtime / 日统计）

**env 变更**：删除 S1 加的三个 override（回到 auto → 跟随 `SYS_STORE_MODE=pg`）；`SYS_TRACE_SPAN_MIGRATION` 不设置（默认 copy，**prod 要全量迁移 span**）。

**步骤**：

1. `replicas: 0`
2. env 变更 apply → `replicas: 2`
3. 迁移服务启动：`trace_migration`（1,069 traces）、`trace_payload_migration`（60 blobs）、`runtime_sample_migration`（32k）、`trace_daily_stats`/`analytics_daily_stats`，以及 `span_migration`（27,310 spans / 2.4 GB，页 50 / 8 MB）
4. 盯 marker：`traces_v1`、`trace_payloads_v1`、`runtime_samples_v1`、`trace_daily_stats_v1`、`analytics_daily_stats_v1`、`spans_v1`
5. 行数校验 + 抽样：随机 3 条 trace 详情（含 span、`coreai-image://` 还原）、runtime 面板、analytics realtime/history
6. 观察 120 分钟：trace 列表/详情、span 树、图片渲染、runtime 面板、admin analytics

**预计**：span 回填为主，5–15 分钟；窗口 01:00–02:00。

### 阶段 P0：彩排（建议，不占用 prod 窗口）

在 prod CNPG 上建一次性库 `core_ai_canary`，用临时 Deployment（1 副本、不接 Service、`SYS_DB_URL=.../core_ai_canary`、`SYS_STORE_MODE=pg`）连 prod Mongo 跑一遍全量迁移，量出真实耗时并验证 Flyway baseline；结束后删除 canary 库与 Deployment。

- canary 只读 Mongo，不写 Mongo；写入全部落在 canary 库
- canary 的定时任务（archive/stale cleanup）读的是 canary 库，不会删 prod Mongo 数据
- 不接流量，不影响用户

## 6. 切流策略

| 项 | 做法 |
|---|---|
| 版本 | env 仓库把 `image: chancetop/core-ai-server:latest` 改为固定 tag（本次迁移版本 `2.0.234` 或其后包含全部迁移代码的版本） |
| 开关 | 全部通过 env 仓库 `20-core-ai-server.yaml` 的 `env` 段，**禁止 kubectl edit** |
| 停机 | 每个窗口先 `replicas: 0`，确认无 pod 后再起，避免新旧版本同时写 Mongo/PG 造成数据分叉 |
| 发布 | 提交 env 仓库 → CI 流水线 apply → 恢复 `replicas: 2` → 等 2/2 Ready |
| 流量 | 无 HPA，2 副本滚动；停服期间由 Azure App Gateway 返回 5xx，需提前通知用户/挂维护提示 |
| 观察 | S1 60 分钟、S2 120 分钟；期间保留 Mongo 只读，不做任何清理 |

## 7. 验收标准

### 7.1 每阶段 PASS 条件

| 检查 | 方法 | 通过标准 |
|---|---|---|
| Flyway | `select max(version) from app.flyway_schema_history` | 37，且无 failed |
| marker | `select id from app.g2_migration_markers` | 本阶段 marker 全部存在，`app.migration_progress` 为空 |
| 行数 | Mongo 基线 vs PG `count(*)` | PG ≥ Mongo（切流后 PG 继续增长） |
| 抽样 | 每阶段随机 3 条业务记录 | 字段、时间、状态一致 |
| API | 登录、列表、详情 | 200 且数据正确 |
| 无回归 | pod 日志 | 无 `UncheckedSQLException` / `PSQLException` / 迁移 ERROR |

### 7.2 最终一致性审计

复用 `docs/validation/2026-11-14-migration-completeness-audit.md` 的方法：全集合行数对比 + 对差异集合做 `_id` 级差集，目标 PASS（剩余 Mongo 集合仅 `schema_versions`、`test_*`）。

## 8. 回滚方案

| 阶段 | 回滚动作 | 数据影响 |
|---|---|---|
| S1 (G1–G4) | env 移除 `SYS_STORE_MODE=pg`（恢复默认 mongo）+ 三个 G5 override 一并移除 → apply → 2 副本 | Mongo 未删未改；切流期间新写入在 PG，回滚后这部分在 Mongo 不可见（窗口内流量极低，可在回滚前用报告记录 id） |
| S2 (G5) | env 加回 `SYS_TRACE_STORE_MODE=mongo`、`SYS_TRACE_SPAN_STORE_MODE=mongo`、`SYS_RUNTIME_STORE_MODE=mongo` → apply | Mongo trace/span 未删；PG 侧新数据留存 |
| Flyway | 不回滚 schema；新增表为空不影响 Mongo 模式 | — |
| 灾难 | Mongo mongodump 恢复（§4.2）；PG 用 barman 最近备份 | 需要停服 |

**硬要求**：观察期内 Mongo 集合一律只读，不得清理、不得加 TTL。

## 9. 风险清单

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | `spans` 2.4 GB、单条 payload 可达数 MB | 回填慢 / PG 内存抖动 | 页 50 + 8 MB 上限；窗口前把 PG 内存复核；canary 彩排量耗时；必要时分两次启动续跑（游标+marker） |
| R2 | 切流窗口内写入丢失 | 窗口内新数据只在旧存储 | `replicas: 0` 后再切，彻底消除分叉；窗口选 01:00（约 9 traces/h） |
| R3 | Flyway 与现有 dataset schema 冲突 | baseline 失败、启动失败 | 先 `baselineVersion=1`；V1–V37 全部 `IF NOT EXISTS`；失败可删 `app.flyway_schema_history` 重试 |
| R4 | PG 容量 | 回填中断 | 50 GiB 已用 8 MB，新增 < 3 GB；barman 备份在 |
| R5 | 迁移是“启动时一次性复制” | 中途失败会重试整个阶段 | marker 未写就重启会重跑；已完成的 marker 不会重复 |
| R6 | pod memory limit 2 Gi vs `-Xmx3072m` | span 回填期间 OOMKill | **窗口前把 limit 提到 4 Gi**（env 仓库），观察期后再评估是否回调 |
| R7 | prod 使用 `:latest` | 无法确定实际版本、回滚无锚点 | 迁移前固定 tag；回滚即改回上一固定 tag |
| R8 | Mongo 无备份 | 回滚/事故无兜底 | 窗口前 `mongodump` 并保留 ≥ 2 周 |
| R9 | `public.flyway_schema_history` 遗留 | 误导后续运维 | 文档记录；待稳定后清理或标注废弃 |
| R10 | 观察期发现功能异常但已写入 PG | 需回滚且有数据差 | 保留窗口内请求日志与 trace；回滚前导出 PG 侧新增行 id |

## 10. 时间估算

| 工作 | 预估 |
|---|---|
| 前置：Flyway baseline、备份、canary 库与临时 Deployment | 0.5 天 |
| 彩排（P0）：全量跑一遍 + 记录耗时 | 0.5 天（可并行） |
| S1 窗口：停服 + 迁移 + 校验 + 观察 | 10 分钟操作 + 60 分钟观察 |
| S2 窗口：停服 + span 回填 + 校验 + 观察 | 20–40 分钟操作 + 120 分钟观察 |
| 最终一致性审计 | 2 小时 |
| **合计** | **约 1.5–2 人日 + 一个晚间窗口（建议两晚：S1 一晚，S2 次晚）** |

建议顺序：`P0 彩排 → S1（周一 01:00）→ 观察一周 → S2（下周一 01:00）→ 最终审计`。若 S1 观察一周无异常，可把 S2 提前。

## 11. 附：prod 需要新增的文件与环境变量

**代码仓库**：`core-ai-server-db-migration/conf/prod/resources/flyway.properties`（本地生成、不入库；内容为 port-forward 的 `jdbc:postgresql://localhost:15434/core_ai` + `core_ai` 密码）

**env 仓库** `azure/prod/kube/resource/app/ai/20-core-ai-server.yaml`：

S1：
```yaml
- name: SYS_STORE_MODE
  value: pg
- name: SYS_TRACE_STORE_MODE
  value: mongo
- name: SYS_TRACE_SPAN_STORE_MODE
  value: mongo
- name: SYS_RUNTIME_STORE_MODE
  value: mongo
# 窗口期临时提高内存
- name: JAVA_OPTS
  value: "-Xmx3072m -Xms512m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
# resources.limits.memory: 4Gi
```

S2：删除三个 `*_STORE_MODE=mongo` 覆盖，保留 `SYS_STORE_MODE=pg`；`SYS_TRACE_SPAN_MIGRATION` 不设置（默认 copy）。

回滚：删除 `SYS_STORE_MODE`（S1）/ 加回三个 `mongo` 覆盖（S2），并把 image 改回上一固定 tag。
