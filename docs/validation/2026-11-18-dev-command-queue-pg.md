# dev 阶段 5：命令队列 PostgreSQL 验收

- 日期：2026-11-18
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.285`
- 开关：`SYS_COMMAND_BACKEND=postgres`；state/lease 已为 postgres，SSE directed
- Flyway：新增 `V45__messaging_commands.sql`；dev 在部署前手动执行 `flywayMigrate -Penv=dev`
- 结论：**PASS**，dev 保持 postgres 命令队列

## E2E

| 操作 | 结果 |
|---|---|
| 新→新：会话 owner A，B 发消息 | `[PUBLISH] command published via PostgreSQL`，`PG_COMMAND_OK` 完成；`messaging.commands` 处理后为 0 |
| 旧→新：2.0.263 旧 Pod 向新 owner 发消息 | 旧 Pod `publishing command via Redis`；新 owner 的 Redis consumer 处理，`OLD_TO_NEW_OK` |
| 新→旧：新 Pod 向 2.0.263 旧 owner 发消息 | 旧 Pod `command_backend=redis`，新 publisher 走 Redis；旧 Pod 处理，`NEW_TO_OLD_OK` |
| 旧 Pod 的 directed SSE relay | 新 Pod 对旧 owner 内部端点 404，自动回退 Redis，SSE 正常 |
| 回滚：`SYS_COMMAND_BACKEND=redis` | 2/2，公有 SSE 返回 `COMMAND_REDIS_OK`，命令走 Redis |
| 恢复 postgres | 2/2，最终 `cmd=postgres` |
| 启动日志 | 无 `FAILED_TO_START`/本功能 ERROR；仅既有 MCP 授权告警 |

## 实现与修复

- V45：`messaging.pods.command_backend` + `messaging.commands`。
- 新 Pod 同时消费 Redis pod/unowned stream 和 PG queue；旧 Pod 只消费 Redis。
- `PodRegistry` 注册 `command_backend`，`CommandPublisher` 按 owner 能力选择 PG/Redis。
- 修复：`schedule().fixedRate` 不接受 lambda，改为 `PgCommandCleanupJob`；`PgCommandRow`/`PgPodCommandBackendRow` 必须 `db().view()` 注册；V45 需先于应用启动执行。

## 镜像与提交

- 镜像：`2.0.285`
- 主仓：`39ef88370`（命令队列）、`fc7b5f7af`（cleanup job）、`9d5f963dc`（view 注册）
- env：`b98cad69`（dev 2.0.285 + command postgres）

## 回滚

`SYS_COMMAND_BACKEND=redis`（或删除）+ rollout；V45 表保留，未处理行不影响 Redis 模式。
