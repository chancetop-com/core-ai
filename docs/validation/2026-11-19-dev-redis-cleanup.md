# Dev: Redis messaging cleanup and Redis decommission

- 日期：2026-11-19
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.304`（commit `c5201ec7f`）
- 配置：不再设置 `SYS_STATE_BACKEND` / `SYS_LEASE_BACKEND` / `SYS_RPC_TRANSPORT` /
  `SYS_SSE_DELIVERY` / `SYS_COMMAND_BACKEND` / `SYS_JEDIS_HOST`
- 结论：**PASS**，dev 的 Jedis Redis 已下线，core-ai-server 不再依赖它

## 变更

- 删除全部 Redis 实现：ownership/lease、turn state、session budget/activity、sandbox binding、
  A2A snapshots、command stream、SSE pub/sub、Redis RPC fallback。
- `StartupDependencyCheck` 只探测 Mongo；`JedisConfig`/`JedisPool` 与 `sys.jedis.*` 删除。
- A2A 流改为 owner pod 的 `GET /_internal/a2a/events` SSE，由调用方 pod 订阅。
- 部署不再需要 `sys.store.mode=pg` 以外的后端开关（`sys.store.mode=pg` 仍为必需）。
- core-ng 的 web session 仍使用独立的 `session` Redis（`SYS_SESSION_HOST=session`），
  不在本次 Jedis 迁移范围内；`cache` Redis 也不属于 core-ai-server。

## E2E

| 操作 | 结果 |
|---|---|
| 2/2 启动 | `startup dependency verified, name=mongo`；无 Redis/Jedis 初始化；`PgCommandConsumer started` |
| 新→新：会话 owner A，B 发消息 | B `[PUBLISH] command published via PostgreSQL, targetPod=A`；A 的 PG consumer 处理；SSE 返回 `PG_COMMAND_OK` |
| `messaging.commands` | 处理后 0 |
| `messaging.pods` | 两个 Pod 均为 `command_backend=postgres` |
| A2A 跨 Pod：会话 owner A，B 发起 `message/stream` | B 通过 HTTP RPC 把 `A2A_START_TASK` 发给 A；A 创建 task；B 从 `/_internal/a2a/events` 收到 task/artifact/status 事件，最终 `TASK_STATE_COMPLETED`，文本 `A2A_DIRECTED_OK` |
| owner 重启：删除 Pod A，50s 后 B 发消息 | B claim + rebuild，SSE 返回 `REBUILD_OK`；无 Redis 参与 |
| Redis 下线 | `statefulset/redis` 与 `service/redis` 删除；core-ai-server 2/2 继续工作 |
| 启动/运行日志 | 无 `FAILED_TO_START`/Jedis 错误；仅既有 MCP 授权告警 |

## 滚动升级注意

清理版本与旧 Pod 混跑时，如果 A2A task 的 owner 仍是旧 Pod（没有
`/_internal/a2a/events`），新的 ingress Pod 无法订阅该 task 的流；命令、SSE、RPC、
ownership 的双向兼容不受影响。因此 UAT/prod 在低 A2A 流量窗口发布，或等待滚动完成后
再验证 A2A 跨 Pod 流。

## 回滚

- 代码回滚到 `2.0.303` 需要恢复 `redis` StatefulSet/Service 和 `SYS_JEDIS_HOST`。
- dev 的 `redis` 已删除；回滚按 `05-redis.yml` 的上一版重新 apply。
- 未执行数据库回滚；V39–V45 保留。

## 环境提交

- `fbr-env-project` `5c0234b6`：dev 2.0.304、删除 legacy env、删除 `05-redis.yml`。
