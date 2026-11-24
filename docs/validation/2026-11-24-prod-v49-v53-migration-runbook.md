# prod V49–V53 迁移 Runbook（2026-11-24 已执行）

> 状态：**已执行**（2026-11-24 21:08–21:14 CST）。备份 `core-ai-pre-v49-v53-20261124` completed
> （backupId `20261124T130800`）；Flyway V49–V53 applied（12.95 s）；镜像 `2.0.363`，Deployment
> revision 98，2/2 Ready。
> 适用对象：`FBRProdAKSCluster` / namespace `prod-ai` / CNPG `postgresql`（primary `postgresql-1`）。
> 关联记录：`docs/validation/2026-11-24-pg-flyway-ledger-and-mongo-prep.md`。

## 0. 执行结果

| 项目 | 结果 |
|---|---|
| CNPG 备份 | `core-ai-pre-v49-v53-20261124`，`phase=completed`，`backupId=20261124T130800`，`azure://fbrdevbostorage.blob.core.windows.net/pg-backup/prod` |
| Flyway | V49/V50/V52/V53 applied；execution `12.952 s`；history 52 行，head V53；`validate` 53/53 |
| V50 索引锁 | V49 1.16 s、V50 1.92 s、V52 5.95 s、V53 3.93 s；写入排队远低于预估 5–30 s |
| 发布 | env commits `dc478ae`、`4dbc18f`、`59fc175`；image `2.0.363`；Deployment revision 98 |
| 镜像 digest | OCI index `sha256:f6ad1906…`；pod imageID 相同；amd64 manifest `sha256:97dbc951…` |
| 验证 | 2/2 Ready、0 restart；`/health-check` 200（公网 + 集群内）；`/api/capabilities` 200；受保护 API 401 |
| Mongo | pod 环境 `SYS_TRACE_SPAN_MIGRATION=skip`；`currentOp` 中 core-ai 连接数 0 |
| 回滚 | `kubectl --context FBRProdAKSCluster -n prod-ai rollout undo deploy/core-ai-server --to-revision=97`（2.0.336） |

## 1. 变更前事实（2026-11-24 核验；下方为执行前快照）

| 项目 | 值 |
|---|---|
| 线上镜像 | `chancetop/core-ai-server:2.0.336` |
| env 仓库镜像 | `2.0.318`（**已漂移，不可直接 apply**） |
| Flyway 版本 | `app.flyway_schema_history` head **V48**（48 行，baseline V1） |
| Flyway 校验 | `validate` 只报 49/50/52/53 pending，**无 checksum 错误**；V45 checksum `-589265998` 与当前文件一致 |
| PG 数据库 | 1.7 GB；`app.spans` 34,575 行 / 1,502 MB；`app.traces` 1,821 行 / 19 MB |
| 其他相关表 | `chat_sessions` 223 行 / 432 kB；`notifications` 1 行 / 64 kB；`agents` 56 行 / 888 kB；`agent_quality_runs` 4 行 / 72 kB |
| 磁盘 | 50 GiB，已用 2.3 GiB（5%） |
| CNPG 备份 | `postgresql-daily` 每天 02:00；最近成功 `2026-11-24T02:01:44Z`；Azure Blob `pg-backup/prod` |

prod 实际 schema 与迁移文件的差异：

- **V49 缺失**：`app.spans.ttft_ms`
- **V50 缺失**：`app.traces.provider_id`、`app.spans.provider_id`、`traces_provider_started_idx`、`spans_provider_started_idx`
- **V52 缺失**：`app.workspaces` 等 7 张表、`chat_sessions.workspace_id/workspace_generation` 及 12 个索引
- **V53 缺失**：`app.agent_reference_issues`、`app.agent_health_state`、`notifications.dedupe_key`、4 个 agents GIN 索引、3 个 `agent_reference_issues_*` 索引、`notifications_dedupe_key_idx`、`agent_quality_runs_agent_finished_idx`
- V45–V48 对象均已存在，且账本一致。

## 2. 影响与锁分析

Flyway 对每个版本单独开事务；所有 DDL 都是 additive（`IF NOT EXISTS`）。

| 版本 | 主要 DDL | 锁 | 预计耗时 / 影响 |
|---|---|---|---|
| V49 | `spans` 加可空列 `ttft_ms` | ACCESS EXCLUSIVE（spans） | 元数据操作，<100 ms；瞬间阻塞 spans 读写 |
| V50 | `traces`/`spans` 加 `provider_id`；建 2 个 btree 索引 | 加列：ACCESS EXCLUSIVE；建索引：SHARE | `traces` 19 MB/1,821 行 <1 s；`spans` 1,502 MB/34,575 行，预计 **5–30 s**，期间 spans 的 INSERT/UPDATE/DELETE 阻塞，SELECT 不受影响；WAL 峰值有限，磁盘余量 47 GiB |
| V52 | 7 张空表 + 12 个索引；`chat_sessions` 加 2 列 | 建空表不碰业务；`chat_sessions` ACCESS EXCLUSIVE | 秒级；`chat_sessions` 仅 432 kB，加列 <100 ms |
| V53 | 2 张空表 + 9 个索引；`notifications` 加列 + 唯一索引；`agents` GIN 索引 | `notifications`/`agents` ACCESS EXCLUSIVE（短） | 秒级；两张表分别 64 kB / 888 kB |

结论：

- **不需要停机、不需要重启应用**；V49–V53 都是向后兼容的新增对象，2.0.336 不会读取它们。
- 唯一需要关注的是 **V50 在 `app.spans` 上建索引时写入短暂排队**。建议选低流量窗口（例如 02:00–04:00 备份之后），并观察 span ingest 延迟。
- 如果希望完全避免写入排队，可先把 V50 的两个 `CREATE INDEX` 手工改成 `CREATE INDEX CONCURRENTLY` 并单独执行，再用 `flyway repair`/手工 history 标记；**本次不推荐**，因为会偏离官方迁移路径，且当前表规模很小。

## 3. 执行前检查

```powershell
# 1) prod 只读核对，确认仍是 V48 / 无 checksum 错误
kubectl --context FBRProdAKSCluster -n prod-ai port-forward svc/postgresql-rw 15434:5432
flyway -configFiles=<prod.conf> info
flyway -configFiles=<prod.conf> validate      # 预期只报 49/50/52/53 pending
```

```sql
-- 2) 确认没有并发 DDL / 长事务
SELECT pid, state, wait_event_type, now() - xact_start AS xact_age, left(query, 120)
FROM pg_stat_activity
WHERE datname = 'core_ai' AND xact_start IS NOT NULL
ORDER BY xact_start;
```

```powershell
# 3) 变更前备份（二选一，推荐 a）
# a. CNPG 物理备份（沿用已有 pre-change 命名习惯）
@'
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: core-ai-pre-v49-v53-<YYYYMMDD>
  namespace: prod-ai
spec:
  cluster:
    name: postgresql
'@ | kubectl --context FBRProdAKSCluster apply -f -
kubectl --context FBRProdAKSCluster -n prod-ai get backup core-ai-pre-v49-v53-<YYYYMMDD> -w

# b. 逻辑备份（1.7 GB；在 pod 内生成再取回，避免 PowerShell 文本重定向破坏 -Fc 二进制）
kubectl --context FBRProdAKSCluster -n prod-ai exec postgresql-1 -- \
  pg_dump -U postgres -d core_ai -Fc --no-owner --no-privileges -f /tmp/core_ai-pre-v49-v53.dump
kubectl --context FBRProdAKSCluster -n prod-ai cp postgresql-1:/tmp/core_ai-pre-v49-v53.dump .
kubectl --context FBRProdAKSCluster -n prod-ai exec postgresql-1 -- rm /tmp/core_ai-pre-v49-v53.dump
```

```powershell
# b. 逻辑备份（1.7 GB；在 pod 内生成再取回，避免 PowerShell 文本重定向破坏 -Fc 二进制）
kubectl --context FBRProdAKSCluster -n prod-ai exec postgresql-1 -- pg_dump -U postgres -d core_ai -Fc --no-owner --no-privileges -f /tmp/core_ai-pre-v49-v53.dump
kubectl --context FBRProdAKSCluster -n prod-ai cp postgresql-1:/tmp/core_ai-pre-v49-v53.dump .
kubectl --context FBRProdAKSCluster -n prod-ai exec postgresql-1 -- rm /tmp/core_ai-pre-v49-v53.dump
```

```powershell
# 4) 备份 Flyway 账本（改动前；plain SQL 可用文本重定向）
kubectl --context FBRProdAKSCluster -n prod-ai exec postgresql-1 -- pg_dump -U postgres -d core_ai -t app.flyway_schema_history --no-owner --no-privileges | Out-File -Encoding utf8 prod-flyway_schema_history-pre-v49-v53.sql
```

## 4. 执行

```powershell
# 使用 Flyway CLI 11.13.2（与仓库 flyway-gradle-plugin 同版本）
flyway -configFiles=<prod.conf> migrate
```

预期输出：

- `Successfully validated 53 migrations`
- `Migrating schema "app" to version "49" … "50" … "52" … "53"`
- `Successfully applied 4 migrations to schema "app", now at version v53`

> prod 当前 V45 checksum 与文件一致，**不需要 `flyway repair`**。如果未来出现 checksum 错误，先 `flyway repair` 再 migrate；repair 只会更新 checksum/清理 failed 行，不改 schema。

## 5. 执行后验证

```sql
-- 账本
SELECT version, description, checksum, installed_on, success
FROM app.flyway_schema_history
WHERE version IN ('49','50','52','53') ORDER BY installed_rank;

-- 对象
SELECT column_name, data_type FROM information_schema.columns
WHERE table_schema='app' AND table_name='spans' AND column_name IN ('ttft_ms','provider_id');
SELECT column_name, data_type FROM information_schema.columns
WHERE table_schema='app' AND table_name='traces' AND column_name='provider_id';

SELECT indexname FROM pg_indexes
WHERE schemaname='app' AND indexname IN
('traces_provider_started_idx','spans_provider_started_idx',
 'chat_sessions_workspace_idx','notifications_dedupe_key_idx',
 'agent_quality_runs_agent_finished_idx');

SELECT count(*) FROM pg_tables
WHERE schemaname='app' AND tablename LIKE 'workspace%';   -- 7
SELECT count(*) FROM pg_tables
WHERE schemaname='app' AND tablename IN ('agent_reference_issues','agent_health_state'); -- 2
```

```powershell
flyway -configFiles=<prod.conf> validate   # 必须 Successfully validated 53 migrations
kubectl --context FBRProdAKSCluster -n prod-ai rollout status deploy/core-ai-server
kubectl --context FBRProdAKSCluster -n prod-ai logs deploy/core-ai-server --since=15m | Select-String "ERROR"
```

## 6. 回滚

- **首选**：不回滚 schema。V49–V53 全部是新增列/表/索引，2.0.336 不读取；回滚应用镜像即可。
- **账本回滚**（只有在决定撤销 DDL 时）：
  ```sql
  DELETE FROM app.flyway_schema_history WHERE version IN ('49','50','52','53');
  ```
- **DDL 回滚**（可选，按逆序）：
  ```sql
  DROP INDEX IF EXISTS app.agent_quality_runs_agent_finished_idx;
  DROP INDEX IF EXISTS app.agents_published_tools_gin_idx;
  DROP INDEX IF EXISTS app.agents_tools_gin_idx;
  DROP INDEX IF EXISTS app.agents_published_skill_ids_gin_idx;
  DROP INDEX IF EXISTS app.agents_skill_ids_gin_idx;
  DROP INDEX IF EXISTS app.notifications_dedupe_key_idx;
  ALTER TABLE app.notifications DROP COLUMN IF EXISTS dedupe_key;
  DROP TABLE IF EXISTS app.agent_health_state;
  DROP TABLE IF EXISTS app.agent_reference_issues;
  DROP INDEX IF EXISTS app.chat_sessions_workspace_idx;
  ALTER TABLE app.chat_sessions DROP COLUMN IF EXISTS workspace_generation;
  ALTER TABLE app.chat_sessions DROP COLUMN IF EXISTS workspace_id;
  DROP TABLE IF EXISTS app.workspace_events, app.workspace_job_leases, app.workspace_async_tasks,
    app.workspace_leases, app.workspace_bindings, app.workspace_volumes, app.workspaces;
  DROP INDEX IF EXISTS app.spans_provider_started_idx;
  DROP INDEX IF EXISTS app.traces_provider_started_idx;
  ALTER TABLE app.spans DROP COLUMN IF EXISTS provider_id;
  ALTER TABLE app.spans DROP COLUMN IF EXISTS ttft_ms;
  ALTER TABLE app.traces DROP COLUMN IF EXISTS provider_id;
  ```
- **数据级回滚**：恢复 `core-ai-pre-v49-v53-*` 备份（CNPG restore 或 `pg_restore`）。这是唯一能保证时间点一致的路径。
- 注意：不要用 2.0.318/2.0.336 覆盖当前 2.0.363；env 仓库已在 `59fc175` 同步到 2.0.363。

## 7. 窗口与责任

| 项目 | 建议 |
|---|---|
| 时间窗口 | 低流量时段；02:00 日备完成后 |
| 预计总耗时 | 备份 2–10 min + 迁移 10–60 s + 验证 15 min |
| 停机 | 不需要 |
| 应用重启 | 不需要（若同时把 `SYS_TRACE_SPAN_MIGRATION` 改为 `skip`，会触发一次滚动重启，见下） |
| 监控 | span ingest 延迟、`pg_stat_activity` 的锁等待、CNPG 磁盘/WAL |
| 授权 | 需用户明确授权后才能对 prod 执行任何写操作 |

## 8. 与 Mongo 下线准备的关系

- prod 的 `spans_v1` marker 已于 2026-11-14 11:23:54 UTC 写入，27,452 条 legacy span 已全部回填 PG；`SYS_TRACE_SPAN_MIGRATION` 已从 `copy` 改为 `skip`。
- 该 env 变更已随 2.0.363 发布：`fbr-prod-env-project` main `246c7ce..59fc175`（`dc478ae` PVC RBAC、`4dbc18f` skip、`59fc175` image），Deployment revision 98。
- 改 env 触发了滚动重启，但本次它与镜像升级合并执行，没有额外的重启窗口。
- Mongo 是共享集群（prod 命名空间 `mongodb-0/1/2`，59 个 `core-ai` collection，同时承载 20+ 其他服务）；停 pod/PVC 不属于 core-ai 单方面动作，必须等跨团队迁移完成。
