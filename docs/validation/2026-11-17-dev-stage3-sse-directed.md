# dev 阶段 3 第 4 步：SSE 定向投递验收

- 日期：2026-11-17
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 阶段镜像：`chancetop/core-ai-server:2.0.263`（部署后另一会话将 dev 升到 `2.0.265`，仍为 directed 且包含本提交）
- 开关：`SYS_SSE_DELIVERY=directed`；`SYS_RPC_TRANSPORT=http`、`SYS_STATE_BACKEND=postgres`、`SYS_LEASE_BACKEND=postgres` 保持
- Flyway：无新增；relay 复用 `messaging.pods` / `session_ownership`
- 结论：**PASS**，dev 保持 directed

## 实现

- `SessionEventHub`：owner Pod 每会话持有 seq、replay buffer；`SessionEventHubRegistry` 管理生命周期。
- 非 owner Pod 通过 `GET /_internal/sessions/events?session_id=&after=` 跟随 owner；Redis 广播保留为滚动升级/relay 失败兜底。
- `EventPublisher` 在 directed 模式写本地 hub + Redis（过渡期双写）；无本地 hub 的低频事件 POST 给 owner。
- 内部 GET 使用 pre-200 preflight（host + token + owner），外网返回 404 而不是 200 SSE。
- `SYS_SSE_DELIVERY` 必须在 `sys.properties` 声明，否则 core-ng 不暴露该属性；这是 2.0.261 部署后发现并修复的启动/路由缺失问题。

## E2E

所有公有请求使用 dev admin API key；Pod A/B 通过 `kubectl port-forward` 固定。

| 操作 | 结果 |
|---|---|
| 2.0.263 以 `redis` 启动 | 2/2，SSE 正常；确认属性声明后 directed 路由注册 |
| 2.0.263 切 `directed` 滚动 | 2/2，无启动错误 |
| 内部 GET：Pod IP host + 正确 token | 200，`text/event-stream`，`retry: 5000` |
| 外网 `https://core-ai-server.connexup-dev.net/_internal/sessions/events` | 404 `NOT_FOUND` |
| 内部 GET：错误 token | 401 |
| 内部 GET：非 owner Pod | 409 |
| 跨 Pod：会话 owner A，SSE 连接 B | B 收到 text/turn_complete；A 日志 `internal session event stream connected`，确认走 relay 而非 Redis |
| 重连：B 的 `/api/sessions/events` | 回放 owner 的终止状态（IDLE） |
| owner 删除：删除 Pod A，等待 lease 失效后在 B 发新消息 | B `claimed unowned session locally` + `session rebuilt successfully`，`OWNER_SWITCH_OK` 完成 |
| 回滚：`SYS_SSE_DELIVERY=redis` | 2/2，公有 SSE 返回 `REDIS_OK` |
| 恢复 directed | 2/2，最终 `sse=directed` |
| 日志 | 无 `FAILED_TO_START`/本功能 `ERROR`；仅既有 MCP 授权告警 |

## 镜像与提交

- 镜像：`chancetop/core-ai-server:2.0.263`
- 主仓：`b77e76bd`（directed SSE）、`b2bbacd8`/`8f1876c6`（测试修复）、`5ae71eb0`（属性声明）、`c06249ce`（属性顺序）
- env：`27a3584`（dev 2.0.263 + directed）
- 构建：Build Server 2.0.263 成功（首次 `CancelTurnPersistenceTest` 偶发失败，单测本地通过，rerun 成功）

## 回滚

将 `SYS_SSE_DELIVERY` 改为 `redis`（或删除）并 rollout；无 Flyway、无数据迁移。relay 失败时新 Pod 也会自动回退 Redis。
