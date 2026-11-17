# UAT 阶段 3 第 4 步：SSE 定向投递验收

- 日期：2026-11-17
- 环境：`FBRDevAKSCluster / uat-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.263`（原 `2.0.258`）
- 开关：`SYS_SSE_DELIVERY=directed`；`SYS_RPC_TRANSPORT=http` 保持，`SYS_LEASE_BACKEND`/`SYS_STATE_BACKEND` 仍为 Redis
- Flyway：无新增
- 结论：**PASS**，UAT 保持 directed

## E2E

UAT admin API key；Pod A/B 通过 `kubectl port-forward` 固定。

| 操作 | 结果 |
|---|---|
| 部署 2.0.263 + directed | 2/2，滚动完成，无启动错误 |
| 内部 GET：Pod IP host + 正确 token | 200 `text/event-stream` |
| 外网 `https://core-ai-server.connexup-uat.net/_internal/sessions/events` | 404 |
| 错误 token / 非 owner Pod | 401 / 409 |
| 跨 Pod：owner A，SSE 连接 B | B 收到 `UAT_DIRECTED_OK` 的 text/turn_complete；A 日志 `internal session event stream connected` |
| 回滚：`SYS_SSE_DELIVERY=redis` | 2/2，公有 SSE 返回 `UAT_REDIS_OK` |
| 恢复 directed | 2/2，最终 `sse=directed` |
| 日志 | 无 `FAILED_TO_START`/本功能 `ERROR` |

## 镜像与提交

- 镜像：`chancetop/core-ai-server:2.0.263`
- env：`eebbb92`（UAT 2.0.263 + directed）
- 说明：UAT manifest 中既有的 `SYS_TRACE_SPAN_MIGRATION=skip` 是另一会话的未提交改动；部署时保留，提交时未纳入本次 commit。

## 回滚

`SYS_SSE_DELIVERY=redis` + rollout；无 Flyway、无数据迁移。已在 UAT 实测回滚并恢复 directed。
