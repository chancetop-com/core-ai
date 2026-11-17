# 生产阶段 3 第 3 步：RPC transport HTTP 验收

- 日期：2026-11-17
- 环境：`FBRProdAKSCluster / prod-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.250`（原 `2.0.234`）
- 开关：`SYS_RPC_TRANSPORT=http`；`SYS_LEASE_BACKEND` 未设置（继续 Redis lease），`SYS_STATE_BACKEND` 未设置（Redis）
- Flyway：V39、V40、V41 已应用（原 V37）
- 维护窗口：2026-11-17 03:20–03:45 UTC；滚动发布，无需停机
- 结论：**PASS**

## 发布前检查

| 项 | 结果 |
|---|---|
| PG 备份 | `core-ai-pre-rpc-20261117` completed（03:21:26–03:22:51 UTC）；当日 02:00 定时备份也 completed |
| 本地 LLM 依赖 | `core-ai-server/src/main/resources/agent.properties` 指向 `https://litellm.connexup-dev.net`，`llm.model=deepseek/deepseek-v4-flash`；用户 `~/.core-ai/agent.properties` 无 prod endpoint |
| 镜像 | `chancetop/core-ai-server:2.0.250`，CI Build Server 成功 |
| 回滚 | `SYS_RPC_TRANSPORT=redis` + rollout；镜像可回 `2.0.234`；V39–V41 为加表，保留 |

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
| 滚动发布 2.0.234 → 2.0.250 | 2/2 Running；5 分钟健康检查 0 失败 |
| `POST /_internal/rpc`（external/internal host） | 404 |
| 无 token / 错误 `X-Target-Pod` | 401 / 409 |
| 新→新：Pod A 建会话，Pod B 调 `skills` | 成功 |
| 老→新兼容 | 由本次滚动升级覆盖（旧 2.0.234 走 Redis，新 Pod 继续消费）；发布期间无 ERROR、无 fallback 报错 |
| 最近 25 分钟日志 | 无 `ERROR`、无 `rpc http not delivered` |

## 回滚

`SYS_RPC_TRANSPORT=redis` + rollout 即回到 Redis RPC；如需回旧版本，镜像回 `2.0.234`。V39–V41 表保留，无数据迁移、无不可逆操作。

## 备注

`core-ai-internal` Secret 已在 `prod-ai` 创建；当前未写入加密 secret source，重建集群前需要补录。
