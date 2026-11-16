# dev 阶段 3 第二批：`sys.lease.backend=postgres` 验收

- 日期：2026-11-16
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.244`
- 开关：`SYS_LEASE_BACKEND=postgres`（`SYS_STORE_MODE=pg`、`SYS_STATE_BACKEND=postgres` 保持）
- Flyway：`V40__messaging_leases.sql`、`V41__messaging_session_budget.sql` 已应用
- 结论：**PASS**，dev 继续运行 PostgreSQL 租约

## 范围

| 状态 | 实现 | PG 表 |
|---|---|---|
| Pod 注册 / 心跳 | `PodRegistry` | `messaging.pods` |
| Session / AgentRun 租约 | `OwnershipStore` | `messaging.session_ownership` (`resource_id`) |
| Turn 状态 | `TurnStateStore` | `messaging.turn_states` |
| 用户会话预算 | `SessionBudgetStore` | `messaging.user_session_counts` |

租约有效性跟随 owner pod 的心跳（10 秒续期，30 秒失效），不再为每条 lease 续期。`LeaseCleanupJob` 每分钟删除死 Pod 的 lease 和计数，并删除 5 分钟无心跳的 Pod 行。Redis 实现保留，默认仍是 `redis`。

## 构建与部署

- `2.0.243`：首版租约实现，构建成功并部署验证了 ownership、turn state、死 Pod 清理。
- 发现会话预算问题：按 `(user_id, pod_id)` 计数时，创建会话和关闭会话落在不同 Pod 会泄漏计数（实测 `admin@example.com` 留下 1 行）。
- `2.0.244`：`V41` 将预算表改为每用户单行 + 24 小时过期，任意 Pod 都可以释放；`2.0.243` 已部署但预算表当时只有一个测试计数，直接替换无生产影响。
- `V41` 在 rollout 前应用；`2.0.244` 构建成功（`Build Server` 4m30s）。

## Schema

```sql
select version, description, success from app.flyway_schema_history order by installed_rank desc limit 2;
-- 41 | messaging session budget | t
-- 40 | messaging leases         | t

select table_name from information_schema.tables where table_schema = 'messaging';
-- a2a_tasks, pods, session_activity, session_ownership, turn_states, user_session_counts
```

## E2E

所有请求使用 dev admin 登录得到的 API key，主入口 `https://core-ai-server.connexup-dev.net`。

| 操作 | 结果 |
|---|---|
| 部署后 `messaging.pods` | 2 行，heartbeat 间隔约 10 秒，age < 10s |
| 新建会话 `c3dcaaae…` 并发送消息 | `messaging.session_ownership` 行存在，owner=`…nwsq2` |
| 发送长消息（600 词）并轮询 | `messaging.turn_states` 行出现，`user_id=admin@example.com`、owner=`…nwsq2` |
| 等待 turn 结束 | 状态 `idle`，`turn_states` 行被清除 |
| 关闭会话 | `session_ownership` 行删除，`turn_states` 无残留 |
| A2A 任务 `43f8c3b5…` | `messaging.a2a_tasks` 行与对应 `session_ownership` context 租约同时存在，owner=`…p64h2` |
| 缩容 2→3→2 | 3 个 Pod 时 `pods`=3；回到 2 个后约 70 秒内死 Pod 行被 `LeaseCleanupJob` 删除，剩余 2 行 heartbeat 正常 |
| `2.0.244` 新建会话 `daee64f1…` + 消息 | 回复 `lease-244-ok`；ownership 行存在；关闭后 ownership=0、`user_session_counts`=0 |
| 最近 15 分钟日志 | 无 `ERROR` |

## 已知限制

- 租约表 `session_ownership` 同时承载 session 和 AgentRun 的 lease key，列名为 `resource_id`。
- `turn_states` 用 `user_id` 代替设计中的 `turn_id`，用于跨 Pod 统计每用户进行中的 turn。
- Pod 行在优雅关闭时会主动删除；进程被强杀时依赖心跳失效 + 每分钟清理（实测缩容场景约 70 秒）。
- 会话预算的 acquire 是“upsert + sum + 超限回滚”，两个 Pod 同时创建时可能短暂超过上限 1；这是软预算，后续有需要再改为原子计数。

## 回滚

移除 `SYS_LEASE_BACKEND` 环境变量并重新 rollout 即回到 Redis 实现；V40/V41 表可保留。`SYS_STATE_BACKEND` 不受影响。

## 下一步

按设计 §7.7：RPC 改为 pod 间 HTTP（`sys.rpc.transport=redis|http`），然后 SSE 定向投递，最后命令队列（`sys.command.backend=redis|postgres`）。
