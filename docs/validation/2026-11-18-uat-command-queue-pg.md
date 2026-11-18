# UAT 阶段 5：命令队列 PostgreSQL 验收

- 日期：2026-11-18
- 环境：`FBRDevAKSCluster / uat-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.285`（原 `2.0.274`）
- 开关：`SYS_COMMAND_BACKEND=postgres`；state/lease 已为 postgres，SSE directed
- Flyway：`V45` 已应用；`V42–V45` 通过 `psql` 手工执行（见下）
- 结论：**PASS**，UAT 保持 postgres 命令队列

## Flyway 说明

UAT 的 `app.conversation_requests` 表由 `postgres` 用户创建，`core_ai` 不是 owner；`V42__conversation_requests.sql` 在 `CREATE INDEX` 处报 `must be owner of table`，因此 `flywayMigrate -Penv=uat` 无法从 V41 前进。

本轮只手工执行了 `V43__plugins.sql`、`V44__skill_quality_rules.sql`、`V45__messaging_commands.sql`（V45 已改为 `IF NOT EXISTS`，可重复执行）；`flyway_schema_history` 仍停在 V41。这是既有 schema ownership 问题，不是本次代码问题；后续修 V42 权限后 Flyway 可继续，V45 幂等不会重复破坏。

## E2E

| 操作 | 结果 |
|---|---|
| 部署 2.0.285 + command postgres | 2/2，无启动错误 |
| 跨 Pod：owner A，B 发消息 | `[PUBLISH] command published via PostgreSQL`，`UAT_COMMAND_PG_OK` 完成；`messaging.commands` 处理后为 0 |
| PG 能力列 | 两个 Pod 均为 `command_backend=postgres` |
| 回滚：`SYS_COMMAND_BACKEND=redis` | 2/2，公有 SSE 返回 `UAT_COMMAND_REDIS_OK` |
| 恢复 postgres | 2/2，最终 `state=postgres lease=postgres cmd=postgres sse=directed` |

## 镜像与提交

- 镜像：`2.0.285`
- env：`44bcbb0f`（UAT 2.0.285 + command postgres；未包含另一会话的 trace skip 工作区改动）

## 回滚

`SYS_COMMAND_BACKEND=redis`（或删除）+ rollout；V43–V45 表保留，未处理行不影响 Redis 模式。
