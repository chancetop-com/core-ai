# prod 阶段 3 第 1–2 步 + 阶段 5：state/lease/command 切 PostgreSQL

- 日期：2026-11-19
- 环境：`FBRProdAKSCluster / prod-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.285`（原 `2.0.263`；index digest `sha256:264a28757c9c7fdb0a12e5e9bb20b38c6decd6cb8e32a11d6cd21a1f0bd7265c`）
- 开关：`SYS_STATE_BACKEND=postgres`、`SYS_LEASE_BACKEND=postgres`、`SYS_COMMAND_BACKEND=postgres`；SSE directed 保持
- Flyway：V41 → V45（V42–V45 本次应用）
- 结论：**PASS**，prod 保持 state/lease/command postgres

## 前置检查

| 项 | 结果 |
|---|---|
| 当前版本 | `2.0.263`，state/lease/command 为空（Redis），SSE directed |
| PG 容量 | `core_ai` 1,487 MB；数据盘 49G 中已用 2.1G（5%） |
| Flyway | 实际为 V41（V39–V41 已由 RPC 阶段应用），非 V37；本次需 V42–V45 |
| 所有权预检 | `app.conversation_requests` 等新表不存在，由 `core_ai` 创建，无 V42 所有权问题 |
| 备份 | `core-ai-pre-pg-cutover-20261119` completed（00:40:23–00:41:53 UTC） |
| 本地 LLM | `litellm.api.base=https://litellm.connexup-dev.net`，不依赖 prod |
| 镜像 | `2.0.285`，Build Server 成功 |

## Flyway

通过临时 prod flyway 配置 + `kubectl port-forward pod/postgresql-1 15434:5432` 执行：

```powershell
.\gradlew :core-ai-server-db-migration:flywayMigrate -Penv=prod
```

结果：`app.flyway_schema_history` 从 V41 前进到 V45；四张新表 owner 均为 `core_ai`。

## E2E

| 操作 | 结果 |
|---|---|
| 滚动发布 2.0.263 → 2.0.285 + 三个 postgres env | 2/2 Running |
| 新→新：owner A，B 发消息 | `[PUBLISH] command published via PostgreSQL`，`PROD_PG_OK` |
| PG 数据 | `messaging.commands` 处理后 0；`session_ownership` owner A；turn_states 0；budget 1 |
| owner 重启：删除 Pod A，等待 lease 失效后在 B 发消息 | B claim + rebuild，`PROD_PG_REBUILD_OK`；owner 行切到 B |
| 新→旧：临时 2.0.263 Pod 作为 owner，新 Pod 发消息 | 新 publisher 走 Redis，旧 Pod 处理 `PROD_NEW_TO_OLD_OK` |
| 旧→新：旧 Pod 向新 owner 发消息 | 旧 Pod 发布 Redis，新 owner 消费 `PROD_OLD_TO_NEW_OK` |
| PG 写入失败回退 | 临时重命名 `messaging.commands` 后发消息：`failed to publish command to PostgreSQL, falling back to Redis`，`PROD_PG_FALLBACK_OK`；表已恢复 |
| 回滚：移除三个 env | 2/2，`PROD_ROLLBACK_REDIS_OK` |
| 恢复三个 env | 2/2，最终 `state=postgres lease=postgres cmd=postgres sse=directed` |
| 日志 | 无 `FAILED_TO_START`/本功能 ERROR；仅既有 MCP 授权告警 |

临时旧版本 Pod `core-ai-server-old-command` 测试后已删除；`messaging.pods` 最终只有两个正式 Pod。

## 回滚

移除 `SYS_STATE_BACKEND`、`SYS_LEASE_BACKEND`、`SYS_COMMAND_BACKEND` + rollout；V42–V45 表保留，Redis 实现自动接管。prod 已实测回滚并恢复。

## env

- `04d6f7d` — prod 2.0.285 + state/lease/command postgres（含既有 trace skip 工作区改动之外的本次变更）
