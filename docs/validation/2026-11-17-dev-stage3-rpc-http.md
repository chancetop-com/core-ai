# dev 阶段 3 第 3 步：RPC transport HTTP 验收

- 日期：2026-11-17
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.250`
- 开关：`SYS_RPC_TRANSPORT=http`（`SYS_LEASE_BACKEND=postgres`、`SYS_STATE_BACKEND=postgres` 保持）
- Flyway：无新增；复用 `V40__messaging_leases.sql` 的 `messaging.pods`（dev 已应用 V39–V41）
- 结论：**PASS**，dev 保持 HTTP RPC

## 范围

- `RpcClient` 增加 `RedisRpcTransport` / `HttpRpcTransport`，默认仍为 Redis
- `POST /_internal/rpc`：共享 token、`X-Target-Pod`、请求 host 必须是 Pod IP、只接受 RPC 命令
- `PodRegistry` 在 `lease=postgres` 或 `rpc=http` 时启动；HTTP RPC 不要求 `SYS_LEASE_BACKEND=postgres`（本次 dev 两者都开）
- 异步命令（SEND_MESSAGE 等）继续走 Redis 命令总线

## 构建过程与修复

| 镜像 | 结果 |
|---|---|
| 2.0.247 | 被无关的 `pmdMain` 拦截：`SkillQualityGate` 的 `FinalFieldCouldBeStatic`（来自另一会话提交 `4e15f02b`）。未改该文件，构建失败 |
| 2.0.248 | 移除 `SkillQualityGate` 的重复 `rules` 字段（`fb6a5e68`），构建成功；首次部署发现 `/_internal/rpc` 通过内部 HTTPRoute 在公网 host 上返回 401 |
| 2.0.249 | 增加请求 host 校验：只接受 Pod IP，其他 host 返回 404；构建成功 |
| 2.0.250 | 修复 Redis 模式启动失败：`sys.internal.token.previous` 在 `rpc=redis` 时未被读取，core-ng 拒绝启动；改为无条件读取。构建成功并部署 |

## E2E

所有请求使用 dev admin 登录得到的 API key。

| 操作 | 结果 |
|---|---|
| 外网 `POST https://core-ai-server.connexup-dev.net/_internal/rpc` | 404（2.0.249 前为 401，已修复） |
| 内部请求无 token | 401 |
| 内部请求错误 token | 401 |
| 正确 token + 错误 `X-Target-Pod` | 409 |
| 正确 token + 非 RPC 类型 `SEND_MESSAGE` | 400 |
| 正确 token + `LOAD_SKILLS` | 200，返回业务 JSON |
| 新→新 RPC：在 Pod A 建会话，Pod B 调 `POST /api/sessions/:id/skills` | 成功；Redis `MONITOR` 中没有该 RPC 的 `XADD`/`LOAD_SKILLS`，确认走 HTTP |
| 旧→新兼容：模拟旧 Pod（2.0.250 + `SYS_RPC_TRANSPORT=redis`）调用新 owner 的 RPC | 成功，走 Redis stream |
| 新→旧回退：会话 owner 为模拟旧 Pod，新 Pod 发起 RPC | 成功；新 Pod 日志 `rpc http not delivered ... reason=rpc endpoint missing`，旧 Pod 日志 `processing command: type=LOAD_SKILLS`，确认 404 回退 Redis |
| 回滚 `SYS_RPC_TRANSPORT=redis` | rollout 期间健康检查 590 次，0 失败；消息与 `loadSkills` 正常 |
| 再切回 `http` | rollout 期间健康检查 590 次，0 失败 |
| owner Pod 删除 | 另一 Pod 接管（owner 变更），会话重建后 `loadSkills` 成功 |
| 最近 20 分钟日志 | 无 `ERROR` |

模拟旧 Pod 是临时 Pod `core-ai-server-old-rpc`（2.0.250 + `rpc=redis`），测试后已删除；`messaging.pods` 最终只有两个正式 Pod。

## 回滚

`SYS_RPC_TRANSPORT=redis` + rollout，立即恢复 Redis RPC；无数据迁移、无不可逆操作。`messaging.pods` 与 V39–V41 保留。

## 下一步

UAT：应用 V39 + V40（UAT 当前 2.0.240，无 messaging schema），不切 `SYS_LEASE_BACKEND`，部署 2.0.250 + `SYS_RPC_TRANSPORT=http` + `POD_IP` + `core-ai-internal` secret，按同一矩阵复验。
