# UAT G2 迁移验收

- 日期：2026-11-10
- 环境：`FBRDevAKSCluster / uat-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:g2-uat-20261110-01`
- 存储：`SYS_STORE_MODE=pg`，Flyway V12–V15
- 结论：**PASS**

## 范围

G2 十个集合：

- ChatSession
- ChatMessage
- FileRecord
- SessionFeedback
- SessionNotice
- UserChannelTarget
- SessionAttachmentRef
- GeminiFile
- UserReport
- UserTodo

## Flyway

UAT PG 从 V11 升到 V15：

- V12 chat_sessions
- V13 chat_messages（含 pg_trgm、content 列、session+seq 唯一）
- V14 file_records
- V15 small_collections

## Canary 演练

创建 `core-ai-server-g2-canary`（1 replica，独立 label，不接 service），设置 `SYS_STARTUP_TIMEOUT_SECONDS=900`，执行启动迁移：

| 集合 | 数量 | 耗时 |
|---|---:|---:|
| chat_sessions | 3926 | 约 8s |
| chat_messages | 22037 | 约 55s（792MB） |
| file_records | 11503 | 约 4s |
| session_attachment_ref | 1054 | 同 small 批 |
| session_notice | 1 | 同 small 批 |
| user_channel_target | 3 | 同 small 批 |
| 其他小集合 | 0 | - |

startup completed 总耗时约 70s，低于默认 180s watchdog；900s 只作为 canary 保险。

Canary API 验证（port-forward 8443）：

- session list total=328
- session history 4 条
- For You files=152
- artifacts my=152、shared=9209

canary 不接 service，无流量。

## 受控切流

为避免 canary 复制后旧主服务继续写 Mongo 造成缺口：

1. UAT 主服务缩容到 0；
2. canary 也缩容到 0；
3. 清空 G2 PG 表并删除 G2 marker（Mongo 未动）；
4. 主服务切 `g2-uat-20261110-01`，扩容到 2；
5. 启动迁移重新全量复制当前 Mongo 快照。

最终 PG / Mongo 计数：

| 数据 | PG | Mongo |
|---|---:|---:|
| chat_sessions | 3926 | 3926 |
| chat_messages | 22037 | 22037 |
| file_records | 11503 | 11503 |
| session_attachment_ref | 1054 | 1054 |
| session_notice | 1 | 1 |
| user_channel_target | 3 | 3 |

marker：

- chat_sessions_v1
- chat_messages_v1
- file_records_v1
- small_collections_v1

## 公网 UAT E2E

| 操作 | 结果 |
|---|---|
| `GET /api/chat/sessions?limit=2` | total=328，数据正常 |
| `GET /api/sessions/:id/history` | 4 条消息 |
| `GET /api/for-you` | files=152 |
| `GET /api/artifacts/my?limit=1` | total=152 |
| `GET /api/artifacts/shared?limit=1` | total=9216 |

UAT 主服务 2/2 Ready，canary deployment 已删除。

## 检查

- `./gradlew :core-ai-server:check` 全绿（含 decision 集成与 G2 store）
- dev 已同步最终镜像 `g2-uat-20261110-01`

## 回滚

- 回滚 UAT：部署 `2.0.180`，保留 `SYS_STORE_MODE=pg`。该镜像只使用 G1 PG store，G2 集合回到 Mongo；Mongo 未删未改。
- 回滚期间新增的 PG 数据不会回到 Mongo；如需完整回滚，先导出该时间段的 G2 数据。
- 新增 `SYS_STARTUP_TIMEOUT_SECONDS` 仅用于 canary；主服务使用默认 180s。

## 后续

- G2 已完成 dev + UAT。
- 下一组：G3（Agent、运行、任务）。
