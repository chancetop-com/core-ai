# dev 阶段 3 第一批：`sys.state.backend=postgres` 验收

- 日期：2026-11-16
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.242`
- 开关：`SYS_STATE_BACKEND=postgres`（`SYS_STORE_MODE=pg` 保持不变）
- Flyway：`V39__messaging_state.sql` 已应用
- 结论：**PASS**，dev 继续运行 postgres 状态后端

## 范围

本批次只把三类分布式状态从 Redis 切到 PostgreSQL：

| 状态 | 接口 | PG 表 |
|---|---|---|
| sandbox 绑定 | `SandboxBindingStore` | `app.sandbox_bindings` |
| 会话活跃度 | `SessionActivityStore` | `messaging.session_activity` |
| A2A 任务快照 | `A2ATaskSnapshotStore` | `messaging.a2a_tasks` |

命令队列、会话归属/turn 租约、RPC、SSE 广播仍走 Redis。默认值仍是 `redis`；`postgres` 只在 `sys.store.mode=pg` 时允许，启动时会校验三张表存在。

## 构建与部署

- `2.0.241` 首次构建失败：`PgStateStoresTest.createTables()` 捕获 `Exception`，被 SpotBugs `REC_CATCH_EXCEPTION` 拦截（`:core-ai-server:spotbugsTest`）。改为捕获 `SQLException` 后重新发版。
- `2.0.242` 构建成功；第一次运行 `:core-ai:test` 时 `SubAgentToolCallTest.timedOutSubAgentResultKeepsSubAgentIdentityStats` 偶发失败，重跑通过。
- Flyway V39 在 rollout 前通过 `kubectl port-forward svc/postgresql-rw 15432:5432` + `flywayMigrate -Penv=dev` 应用。
- 部署后两个 Pod 2/2 Running；日志确认 `SYS_STATE_BACKEND` 覆盖生效、`state-expiry-cleanup` 每 15 分钟调度。

## Schema 核对

```sql
select version, description, success from app.flyway_schema_history order by installed_rank desc limit 2;
-- 39 | messaging state | t

select table_schema, table_name from information_schema.tables
where table_schema = 'messaging' or (table_schema = 'app' and table_name = 'sandbox_bindings');
-- app.sandbox_bindings
-- messaging.a2a_tasks
-- messaging.session_activity
```

## E2E

所有请求使用 dev admin 登录得到的 API key，主入口 `https://core-ai-server.connexup-dev.net`。

| 操作 | 结果 |
|---|---|
| `GET /health-check` | 200 |
| `POST /api/auth/login` | 成功，返回 admin API key |
| `POST /api/sessions` | 创建 `69d4d215-215a-4359-b21c-2c9b9e219be8` |
| `POST /api/sessions/:id/messages`（要求 agent 在 sandbox 执行 `echo e2e-state-backend`） | 回复包含 `e2e-state-backend` |
| PG `app.sandbox_bindings` | 行存在：`claim-b52afd33`，`updated_at=09:16:39Z` |
| `POST /api/sessions/sandbox-terminal/ticket` | 返回 ticket；读 sandbox 绑定来自 PG |
| PG `messaging.session_activity` | 行存在，`expires_at > now()` |
| `POST /api/a2a/agents/assistant:admin@example.com/message/send` | 创建任务 `73068027-55df-4b91-ad85-74a85eaa2e86` |
| PG `messaging.a2a_tasks` | 行存在，`ownerPod=core-ai-server-bc5659b6f-2xlsw` |
| 非 owner Pod（`wjs78`，HTTPS 18443）`GET /api/a2a/tasks/73068027…` | 200，`TASK_STATE_COMPLETED`，输出 `a2a-e2e-ok`；证明快照由另一个 Pod 从 PG 读取 |
| `DELETE /api/sessions/:id`（关闭会话、释放 sandbox） | 成功；`app.sandbox_bindings` 对应行删除，验证绑定的删除路径 |
| 两个 Pod 最近 25 分钟日志 | 无 `ERROR` |

## 回滚

移除部署中的 `SYS_STATE_BACKEND` 环境变量并重新 rollout 即回到 Redis 实现；`messaging` schema 与三张表可保留，不影响旧版本。Flyway V39 不回滚。

## 下一步

按设计 §7.7：pod 注册表 + 会话归属/turn 状态/用户计数租约（`sys.lease.backend`），然后 RPC、SSE，最后命令队列。
