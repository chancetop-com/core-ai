# UAT 阶段 3 第 1–2 步：state + lease 切 PostgreSQL 验收

- 日期：2026-11-18
- 环境：`FBRDevAKSCluster / uat-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.263`（后续升到 `2.0.285`，state/lease 保持 postgres）
- 开关：`SYS_STATE_BACKEND=postgres`、`SYS_LEASE_BACKEND=postgres`；RPC http、SSE directed 保持
- Flyway：UAT 已应用 V39–V41（本轮无新增）
- 结论：**PASS**，UAT 保持 state/lease postgres

## E2E

| 操作 | 结果 |
|---|---|
| 部署 state/lease postgres | 2/2，无启动错误 |
| 多 Pod 会话：owner A，B 发消息 | `UAT_PG_OK` 完成；SSE/RPC 正常 |
| PG 表 | `messaging.pods=2`；`session_ownership` 指向 owner；`turn_states` 结束后 0；`user_session_counts` 正常 |
| Pod 重启恢复：删除 owner，等待 lease 失效后在 B 发消息 | B claim + rebuild，`UAT_PG_REBUILD_OK`；owner 行切到 B |
| 回滚：移除两个 env | 2/2，公有 SSE 返回 `UAT_REDIS_STATE_OK` |
| 恢复 postgres | 2/2，最终 `state=postgres lease=postgres` |

## 镜像与提交

- 阶段镜像：`2.0.263`（后续 `2.0.285` 包含命令队列）
- env：`5d72d64d`（UAT state/lease postgres；未包含另一会话的 `SYS_TRACE_SPAN_MIGRATION=skip` 工作区改动）

## 回滚

移除 `SYS_STATE_BACKEND` / `SYS_LEASE_BACKEND` + rollout；无 Flyway 回滚、无数据迁移。
