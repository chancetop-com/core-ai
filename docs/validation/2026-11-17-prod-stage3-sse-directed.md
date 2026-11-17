# prod 阶段 3 第 4 步：SSE 定向投递验收

- 日期：2026-11-17
- 环境：`FBRProdAKSCluster / prod-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.263`（原 `2.0.250`）
- 开关：`SYS_SSE_DELIVERY=directed`；`SYS_RPC_TRANSPORT=http` 保持，lease/state 仍为 Redis
- Flyway：无新增
- 发布前检查：本机 `litellm.api.base=https://litellm.connexup-dev.net`，不依赖 prod
- 结论：**PASS**，prod 保持 directed

## E2E

prod admin API key；Pod A/B 通过 `kubectl port-forward` 固定。

| 操作 | 结果 |
|---|---|
| 滚动发布 2.0.250 → 2.0.263 + directed | 2/2 Running |
| 内部 GET：Pod IP host + 正确 token | 200 `text/event-stream` |
| 外网 `https://core-ai-server.connexup.net/_internal/sessions/events` | 404 |
| 错误 token / 非 owner Pod | 401 / 409 |
| 跨 Pod：owner A，SSE 连接 B | B 收到 `PROD_DIRECTED_OK` 的 text/turn_complete；A 日志 `internal session event stream connected` |
| 健康检查 | 2/2，无 `FAILED_TO_START`/本功能 `ERROR` |

## 镜像与提交

- 镜像：`chancetop/core-ai-server:2.0.263`
- env：`bdac816`（prod 2.0.263 + directed）
- 无数据库备份需求（无 Flyway/无数据迁移）

## 回滚

`SYS_SSE_DELIVERY=redis`（或删除）+ rollout；无 Flyway、无不可逆操作。prod 未执行回滚，dev/UAT 已实测同一路径。
