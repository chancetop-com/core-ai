# UAT: Redis cleanup and Jedis Redis decommission

- 日期：2026-11-20
- 环境：`FBRDevAKSCluster / uat-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:2.0.318`（已由前一次发布部署，本次不换镜像）
- 配置：删除 `SYS_STATE_BACKEND` / `SYS_LEASE_BACKEND` / `SYS_RPC_TRANSPORT` /
  `SYS_COMMAND_BACKEND` / `SYS_SSE_DELIVERY` / `SYS_JEDIS_HOST`
- Flyway：V46 已有；V47 `home_todo_digest_runs` 由 `flywayMigrate -Penv=uat` 补记录（表已存在）
- 结论：**PASS**，UAT 的 Jedis Redis 已下线

## 操作

| 操作 | 结果 |
|---|---|
| 应用 UAT manifest（2.0.318 + 删除 legacy env） | 2/2 Running |
| 启动日志 | 无 `FAILED_TO_START`/Jedis 错误 |
| `statefulset/redis` + `service/redis` | 删除 |
| 保留 | `session`（core-ng web session）、`cache`、`feature-store-online-redis` |
| Flyway | 47 |

## 环境提交

- `fbr-env-project` `17ab0c9c`：UAT 删除 legacy env、删除 `15-redis.yml`。

## 备注

UAT 的 2.0.318 已包含 Redis 清理代码，所以本次只做配置清理和 Redis 下线，没有重复跑
跨 Pod E2E；同一镜像的跨 Pod 命令/SSE/A2A 已在 prod 验证（`docs/validation/2026-11-20-prod-redis-cleanup.md`）。
