# UAT 阶段 3 第 3 步：RPC transport HTTP 验收

- 日期：2026-11-17
- 环境：`FBRDevAKSCluster / uat-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.250`
- 开关：`SYS_RPC_TRANSPORT=http`；`SYS_LEASE_BACKEND` 未设置（继续 Redis lease），`SYS_STATE_BACKEND` 未设置（Redis）
- Flyway：V39（messaging schema/state）、V40（pods/leases）、V41（budget）已应用
- 结论：**PASS**，UAT 保持 HTTP RPC

## 数据

| 表 | 行数 |
|---|---:|
| `messaging.pods` | 2 |
| `messaging.session_activity` | 0 |
| `messaging.a2a_tasks` | 0 |
| `app.sandbox_bindings` | 0 |
| `messaging.session_ownership` | 0（lease 仍为 Redis） |
| `messaging.turn_states` | 0（lease 仍为 Redis） |
| `messaging.user_session_counts` | 0（budget 仍为 Redis） |

## E2E

| 操作 | 结果 |
|---|---|
| 外网/内部 host `POST /_internal/rpc` | 404（host 校验） |
| 无 token / 错误 `X-Target-Pod` | 401 / 409 |
| 新→新：Pod A 建会话，Pod B 调 `skills` | 成功 |
| 旧→新：模拟旧 Pod（2.0.250 + `rpc=redis`，无 pods 行）调用新 owner | 成功，走 Redis |
| 新→旧：会话 owner 为模拟旧 Pod，新 Pod 发起 RPC | 成功；新 Pod 日志 `rpc http not delivered ... reason=no registered address`，回退 Redis |
| 回滚 `SYS_RPC_TRANSPORT=redis` | rollout 期间 180 次健康检查 0 失败；消息与 `skills` 正常 |
| 再切回 `http` | rollout 期间 180 次健康检查 0 失败 |
| 日志 | 无 `ERROR` |

模拟旧 Pod 已删除；`messaging.pods` 最终为两个正式 Pod。

## 回滚

`SYS_RPC_TRANSPORT=redis` + rollout；镜像可回到 `2.0.240`；V39–V41 表保留，无数据迁移。

## 备注

`core-ai-internal` Secret 已在 `uat-ai` 创建；当前未写入加密 secret source，重建集群前需要补录。
