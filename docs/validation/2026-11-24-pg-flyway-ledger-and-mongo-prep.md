# PG 迁移账本对齐与 Mongo 下线准备（dev / UAT / prod 执行）

> 日期：2026-11-24
> 范围：`app.flyway_schema_history` 与 V45–V53 实际 schema 的盘点、备份、dev/UAT/prod 迁移与 prod 发布；Mongo 下线准备（不删数据）。
> 约束：不改业务代码；prod 零写操作；不删除任何 Mongo 数据/PVC/集合。

## 1. 结论

1. **V45 checksum 不匹配已经不存在**。当前文件 checksum `-589265998`，与 dev/UAT/prod 三环境 history 行完全一致；改造前的旧文件 checksum 是 `577327395`。原因是 V45 在 2026-11-19 被改成幂等 DDL（`d39374516`），而三个环境的 history 行都在该提交之后写入。`flyway validate` 现在只报 V49/50/52/53 pending，没有 checksum 错误，因此**没有执行 `flyway repair`**（执行也只会是 no-op）。
2. **dev 已由正常 `flyway migrate` 推进到 V53**（V49/50/52/53 全部 Success，对象原本已被手工建好，幂等 DDL 只产生 "already exists, skipping" 警告）。
3. **UAT 已推进到 V53**；V52（workspace 7 张表 + `chat_sessions` 两列）与 V53（agent reference health 两表 + `notifications.dedupe_key` + 5 个 GIN/btree 索引）是本次真实新建的对象。UAT 2.0.363 两 pod 保持 Ready、0 重启；迁移前日志里的 `column "workspace_id" does not exist` / `relation "app.agent_health_state" does not exist` 在迁移后不再出现。
4. **prod 已按 runbook 执行（2026-11-24）**：CNPG 备份 `core-ai-pre-v49-v53-20261124` completed（backupId `20261124T130800`）；V49/V50/V52/V53 applied（12.95 s），history 52 行 / head V53，`validate` 53/53；镜像 **2.0.363**，Deployment revision 98，2/2 Ready。详见 `docs/validation/2026-11-24-prod-v49-v53-migration-runbook.md`。
5. **prod Mongo span 已全部回填**：`app.g2_migration_markers` 的 `spans_v1` 写于 2026-11-14 11:23:54 UTC；Mongo 的 27,452 条 span 全部早于该时间。PG 当前 `app.spans` 34,575 行，其中 23,714 行早于切流点；差的 3,738 行是 2026-10-15…10-24 的旧 span，已在 30 天保留期后被归档到 Blob 并从 PG 删除（10-25 起逐日计数与 Mongo 完全一致，11-14 边界差 1 条是 `created_at` vs `started_at` 的口径差）。

## 2. 盘点（改动前）

| 环境 | history 行数 | head | V45 checksum | V49 | V50 | V52 | V53 |
|---|---|---|---|---|---|---|---|
| dev | 47（baseline V2） | V48 | -589265998 | 对象已在 | 对象已在 | 对象已在 | 对象已在 |
| UAT | 48（baseline V1） | V48 | -589265998 | 对象已在 | 对象已在 | **缺** | **缺** |
| prod | 48（baseline V1） | V48 | -589265998 | **缺** | **缺** | **缺** | **缺** |

盘点方法：直接查询 `app.flyway_schema_history`，并用 `information_schema.columns` / `pg_tables` / `pg_indexes` 对照 V45–V53 的 27 张表、7 个列、55 个索引。prod 的 34,575 条 span / 1,502 MB、223 条 chat_sessions、56 个 agents 等规模数据用于锁与耗时评估。

## 3. 备份

改动前已导出三环境 `app.flyway_schema_history`（plain SQL，含数据）：

| 环境 | 文件 | 大小 | SHA-256 |
|---|---|---|---|
| dev | `C:\Users\stephen\.core-ai\backups\flyway\20261124\dev-flyway_schema_history-20261124.sql` | 6,787 B | `37C2AEBB0550E62309E6E113EDAAB854F657B915273EA1C0C6A80F380EC0735F` |
| UAT | `C:\Users\stephen\.core-ai\backups\flyway\20261124\uat-flyway_schema_history-20261124.sql` | 6,883 B | `5DCE7AD05A0EEA6B347951901BC206CE667F20633092E60CA1270FB41C0C5561` |
| prod | `C:\Users\stephen\.core-ai\backups\flyway\20261124\prod-flyway_schema_history-20261124.sql` | 6,882 B | `527DAE411F18297630B04D19E69C67A3F02E3A727C09371C009822E37EA396DA` |

prod 的 Flyway 账本备份是在变更前用只读查询导出的。Flyway CLI 11.13.2 与按环境生成的连接配置放在 `D:\core-ai\tmp\flyway-cli` 与 `C:\Users\stephen\.core-ai\ops\flyway\{dev,uat,prod}.conf`（含凭据，未进仓库）。

prod 变更前另创建了 CNPG 物理备份 `core-ai-pre-v49-v53-20261124`：`phase=completed`、`backupId=20261124T130800`、目标 `azure://fbrdevbostorage.blob.core.windows.net/pg-backup/prod`。

## 4. dev

```powershell
gradlew :core-ai-server-db-migration:flywayInfo -Penv=dev   # 首次盘点，Schema version: 48
# 因共享工作区的 Gradle daemon 锁竞争，后续改用独立 Flyway CLI 11.13.2
flyway -configFiles=<dev.conf> validate
flyway -configFiles=<dev.conf> migrate
flyway -configFiles=<dev.conf> validate
```

实际输出摘要：

- `Current version of schema "app": 48`
- V49：`column "ttft_ms" of relation "spans" already exists, skipping`
- V50：两个 `provider_id` 列、两个索引 `already exists, skipping`
- V52：7 张 workspace 表、`chat_sessions` 两列、12 个索引全部 `already exists, skipping`
- V53：2 张表、`notifications.dedupe_key`、9 个索引全部 `already exists, skipping`
- `Successfully applied 4 migrations to schema "app", now at version v53 (execution time 00:12.694s)`
- 之后 `validate`：`Successfully validated 53 migrations`

history 变化：47 行 → 51 行；V49/50/52/53 的 `installed_on` 为 2026-11-24 20:39（本地），`success=t`。所有对象在迁移前已存在，迁移只补账本，不重复破坏数据。

## 5. UAT

```powershell
flyway -configFiles=<uat.conf> validate   # 仅 49/50/52/53 pending，无 checksum 错误
flyway -configFiles=<uat.conf> migrate
flyway -configFiles=<uat.conf> validate
```

实际结果：

- V49/V50 的列与索引已存在（此前手工执行），输出 `already exists, skipping`
- **V52 真实创建**：`app.workspaces`、`app.workspace_volumes`、`app.workspace_bindings`、`app.workspace_leases`、`app.workspace_async_tasks`、`app.workspace_job_leases`、`app.workspace_events`；`app.chat_sessions.workspace_id/workspace_generation`；12 个索引（含 3 个 partial unique index）
- **V53 真实创建**：`app.agent_reference_issues`、`app.agent_health_state`、`app.notifications.dedupe_key`、`agent_reference_issues_*` 3 个索引、`notifications_dedupe_key_idx`、4 个 `agents` GIN 索引、`agent_quality_runs_agent_finished_idx`（共 9 个索引）
- `Successfully applied 4 migrations to schema "app", now at version v53 (execution time 00:14.011s)`
- 之后 `validate`：`Successfully validated 53 migrations`

UAT 应用验证（2.0.363，image `chancetop/core-ai-server:2.0.363`）：

- `rollout status`：successfully rolled out；两 pod Ready，restartCount=0（迁移未触发重启）
- 迁移前日志（12:38 UTC）确有 `column "workspace_id" does not exist` 与 `relation "app.agent_health_state" does not exist`；迁移后 5 分钟窗口内不再出现这两类错误
- 复跑 inventory：27/27 表 present、7/7 列 present、55/55 索引 present

## 6. prod（已执行）

授权后按 runbook 执行（2026-11-24 21:08–21:14 CST）：

```powershell
# 备份：Backup CR core-ai-pre-v49-v53-20261124 → completed / backupId 20261124T130800
# 迁移（Flyway CLI 11.13.2）
flyway -configFiles=<prod.conf> migrate   # V49/V50/V52/V53, execution 12.952 s
flyway -configFiles=<prod.conf> validate  # Successfully validated 53 migrations
```

结果：

- history：48 行 / V48 → **52 行 / head V53**；`validate` 53/53。
- 对象：7 张 workspace 表、6 个新增列、`traces/spans` 两个 provider 索引、`chat_sessions_workspace_idx`、`notifications_dedupe_key_idx`、`agent_quality_runs_agent_finished_idx` 均存在。
- V50 在 `app.spans`（34,575 行 / 1,502 MB）建索引耗时 1.92 s，`pg_stat_activity` 无异常长事务，应用日志无迁移错误。
- 发布：image `2.0.363`，Deployment revision 98；pod imageID `sha256:f6ad1906…` 与 OCI index digest 一致。
- 验证：2/2 Ready、0 restart；公网与集群内 `/health-check` 200；`/api/capabilities` 200；`/api/hub/models`、`/api/agents?view=picker` 返回 401（认证层正常）。
- `SYS_TRACE_SPAN_MIGRATION=skip` 已生效；2.0.363 的 `SpanStoreModule` 不再绑定 `SpanMigrationService`，启动只探测 PostgreSQL（`startup dependency verified, name=postgres`），Mongo `currentOp` 中 core-ai 连接数 0。

prod 差异与锁/耗时、备份、回滚、窗口见独立 runbook，现状态已更新为“已执行”。

## 7. Mongo 下线准备（未删除任何数据）

### 7.1 数据核对

| 项目 | 结果 |
|---|---|
| prod Mongo | namespace `prod`，StatefulSet `mongodb` 3/3，PVC `data-mongodb-0/1/2` 各 50 GiB |
| `core-ai` database | 59 个 collection；`spans` 27,452 条 / 2.73 GB dataSize；`traces` 1,076 条 |
| 回填标记 | `app.g2_migration_markers`: `traces_v1` 2026-11-14 11:11:43Z、`spans_v1` 2026-11-14 11:23:54Z；共 36 个 marker |
| PG 侧 | `app.spans` 34,575 行；`app.traces` 1,821 行 |
| 逐日核对 | 2026-10-25…11-13 Mongo 与 PG 完全一致；10-15…10-24 共 3,739 条已过 30 天保留期，被 `TraceArchiveService` 归档到 Blob 后从 PG 删除。直接证据：2026-11-24T01:09Z 的归档任务上传 `12 traces / 56 spans`、cutoff=2026-10-25，正好等于 Mongo 10-24 桶的 56 条；06-09 的 13 条为跨期 trace，仍在 PG |
| 连接 | Mongo 是共享集群，`currentOp` 中可见 product/payment/loyalty/marketing 等 20+ 服务；core-ai 没有独立可停的条件 |

结论：**prod 的 legacy span/trace 已全部回填，`SYS_TRACE_SPAN_MIGRATION` 已随 2.0.363 发布切换为 `skip`**。

### 7.2 env 变更（已发布）

- 仓库：`D:\fbr-prod-env-project`，`main` 已推送到 `origin/main`（`246c7ce..59fc175`）。
- 发布时先把本地 `main` rebase 到 `origin/main`（`246c7ce`），保留 PVC RBAC 提交为 `dc478ae`；
  再 cherry-pick 原分支 `ops/prod-trace-span-skip` 的 `c8763c3` 为 `4dbc18f`；镜像提交 `59fc175`。
- 改动：`azure/prod/kube/resource/app/ai/20-core-ai-server.yaml`
  - `SYS_TRACE_SPAN_MIGRATION: copy` → `skip`
  - `image: 2.0.336` → `2.0.363`
- 已 `kubectl apply`：Deployment revision 98，2/2 Ready；pod 环境确认 `SYS_TRACE_SPAN_MIGRATION=skip`。
- 工作区里其他会话的两个 `02-encrypt-one.sh` 未提交改动未被触碰、未丢失。
- 原分支 `ops/prod-trace-span-skip`（`c8763c3`）保留作为历史参考，不再使用。

### 7.3 后续 P2–P4 清单

**P2 运行时解耦（代码 + env）**

1. `SYS_TRACE_SPAN_MIGRATION=skip`（已随 2.0.363 生效）。
2. 移除启动时的 Mongo ping（`StartupDependencyCheck`）与 `schema_versions` 读取（`SchemaMigrationManager`）；pg 模式不再注册 Mongo 连接/集合。
3. 从 prod ConfigMap 移除 `SYS_MONGO_URI`（`azure/prod/kube/resource/app/ai/config/20-core-ai-server-config.yml`）。
4. 用 NetworkPolicy 临时阻断 core-ai → `mongodb.prod:27017`，观察 7 天 0 连接与业务无异常。
5. 移除 `MigrationService` 绑定（span/trace/G1–G5 共 36 个 marker 已全部完成）。

**P3 代码与依赖清除**

1. 删除 Mongo 实现、`@MongoEnumValue` 实体、113 个相关测试中的 Mongo mock、Maven/Gradle Mongo driver 依赖。
2. 删除 `MongoConfig`、`MongoModule`、collections 注册、`SchemaMigrationV*`（先确认三环境 `schema_versions` 不再被读取）。
3. 删除 mongoku / mongodb_exporter / snapscheduler / Mongo RBAC 等仅存于 env 仓库的运维对象（core-ai 名下）。

**P4 数据与基础设施**

1. `core-ai` database 逻辑 dump + restore 演练（三环境）；保留 manifest/checksum。
2. 等待所有共享 Mongo 的服务迁走（当前 20+ 连接方），再停 `mongodb` StatefulSet；停之前保留 PVC。
3. PVC 进入归档生命周期后再删除（至少 30 天观察、7 天无恢复请求），跨团队变更窗口批准。
4. 删除 `core-ai` database；保留备份直至 P6 观察期结束。

### 7.4 仍阻塞的事项

- **Mongo 是共享集群**：prod `mongodb-0/1/2` 同时服务 20+ 个业务服务；core-ai 不能单独停 pod/PVC。
- **D2 未决策**：保留自建 Mongo 支持 vs 完全删除，决定 P3 是 1–2 周还是 26–37 人日。
- **P2 剩余代码项**：2.0.363 已不再绑定 `SpanMigrationService` 且启动只探测 PostgreSQL，但 `SYS_MONGO_URI` 仍在 ConfigMap、`registerMongo()`/`SchemaMigrationManager` 的清理仍需单独 PR。
- **Mongo 数据归档**：P4 的 dump/restore 演练与跨团队停集群窗口尚未开始；本次未删除任何数据/PVC。
- **后续 prod 变更仍需单独授权**：V49–V53 迁移与 2.0.363 发布已完成，但 P2–P4 不在本次授权范围。

## 8. 关键命令与 SQL 索引

- Flyway：`flyway -configFiles=<env.conf> info|validate|migrate`
- 账本：`SELECT * FROM app.flyway_schema_history ORDER BY installed_rank;`
- 对象核对：`information_schema.columns`、`pg_tables`、`pg_indexes`（27 表 / 7 列 / 55 索引的清单见 `D:\core-ai\tmp\flyway-cli\inventory.sql`）
- 备份：`pg_dump -U postgres -d core_ai -t app.flyway_schema_history --no-owner --no-privileges`
- Mongo 只读：`kubectl -n prod exec mongodb-0 -- mongosh --quiet --eval '<countDocuments/getCollectionNames>'`
- prod marker：`SELECT id, completed_at FROM app.g2_migration_markers WHERE id IN ('spans_v1','traces_v1');`
