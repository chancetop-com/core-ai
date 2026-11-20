# prod: 2.0.318 Redis cleanup and Jedis Redis decommission

- 日期：2026-11-20（UTC；本地 2026-11-19 夜）
- 环境：`FBRProdAKSCluster / prod-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.318`（原 `2.0.285`；与 UAT 验证过的镜像一致）
- 配置：删除 `SYS_STATE_BACKEND` / `SYS_LEASE_BACKEND` / `SYS_RPC_TRANSPORT` /
  `SYS_SSE_DELIVERY` / `SYS_COMMAND_BACKEND` / `SYS_JEDIS_HOST`
- Flyway：V45 → V47（V46 `dataset_reports`、V47 `home_todo_digest_runs`）
- 备份：`core-ai-pre-redis-cleanup-20261119` completed（06:27:27–06:28:55 UTC）
- 结论：**PASS**，prod 的 Jedis Redis 已下线；V46/V47 为发布后补应用

## 前置检查

| 项 | 结果 |
|---|---|
| 当前版本 | `2.0.285`，2/2 |
| PG | `postgresql` 3/3 Ready，`core_ai` 1,494 MB |
| Flyway | prod 最新 V45；2.0.318 需要 V46/V47（UAT 已到 V47） |
| 本地 LLM 端点 | `litellm.api.base=https://litellm.connexup-dev.net`，不依赖 prod |
| 镜像 | `2.0.318`，UAT 已运行 97m，CI Build Server 成功 |
| 备份 | 新增 `core-ai-pre-redis-cleanup-20261119`，completed |

## Flyway 补应用

发布 2.0.318 后启动日志出现 `relation "app.home_todo_digest_runs" does not exist`
（`HomeTodoDigestService`）。核对发现 2.0.318 包含 V46（dataset reports）和 V47
（home todo digest），而 prod 只到 V45；UAT 已在 V47。随后通过临时 prod Flyway 配置 +
`kubectl port-forward pod/postgresql-1 15434:5432` 执行：

```powershell
.\gradlew :core-ai-server-db-migration:flywayMigrate -Penv=prod
```

结果：`app.flyway_schema_history` V45 → V47，表已创建；V46/V47 均为 additive
`IF NOT EXISTS`。教训：后续 prod 发布前先对比 UAT 的 Flyway 版本，不要只看“本次是否新增
migration”。

## 变更

- 删除全部 Jedis Redis 后端与开关；state/lease/command/SSE/RPC 全部走 PostgreSQL、
  directed SSE、HTTP RPC。
- A2A 流走 owner pod 的 `/_internal/a2a/events`。
- `prod` 的 `statefulset/redis` 与 `service/redis` 删除；`session`（core-ng web session）、
  `cache`、`feature-store-online-redis` 保留。

## E2E

| 操作 | 结果 |
|---|---|
| 滚动发布 2.0.285 → 2.0.318 | 2/2 Running；启动只探测 Mongo；`PgCommandConsumer started` |
| 新→新：会话 owner A，B 发消息 | B `[PUBLISH] command published via PostgreSQL, targetPod=A`；A 处理；SSE 返回 `PG_COMMAND_OK` |
| directed SSE | 公有 SSE 正常收到 `turn_complete` |
| A2A 跨 Pod：B 发起 `message/stream`，owner A | B 从 `/_internal/a2a/events` 收到 task/artifact/status 事件，最终 `TASK_STATE_COMPLETED`，文本 `A2A_DIRECTED_OK` |
| Redis 下线后复测 | 同样的命令/SSE/A2A E2E 再次通过 |
| PG 数据 | `messaging.pods` 两个 Pod 均为 `command_backend=postgres`；`messaging.commands` 处理后为 0 |
| 日志 | 无 `FAILED_TO_START`/Jedis 错误；仅 `redis-session`（core-ng session）连接 `session` |

## 回滚

- 代码回滚到 `2.0.285` 需要恢复 `redis` StatefulSet/Service、`SYS_JEDIS_HOST` 和
  `SYS_STATE_BACKEND`/`SYS_LEASE_BACKEND`/`SYS_RPC_TRANSPORT`/`SYS_COMMAND_BACKEND`/`SYS_SSE_DELIVERY`。
- 数据库无需回滚；V39–V45 保留。

## 环境提交

- `fbr-prod-env-project` `483d773`：prod 2.0.318、删除 legacy env、删除 `05-redis.yml`。
