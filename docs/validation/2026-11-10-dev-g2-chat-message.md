# dev G2 第二批：ChatMessage 迁移验收

- 日期：2026-11-10
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:g2-chat-message-20261110-04`
- 存储：`SYS_STORE_MODE=pg`，Flyway `V13__chat_messages.sql` 已应用
- 结论：**PASS**

## 范围

本批次迁移 `ChatMessage`。`ChatSession` 已在第一批迁移；`FileRecord` 及其余 G2 集合仍在 Mongo。

新增/调整：

- `ChatMessageStore`、`ChatMessageQuery`
- `MongoChatMessageStore`、`PgChatMessageStore`
- `ChatMessageMigrationService` + `app.g2_migration_markers`
- Flyway `V13__chat_messages.sql`
- `PgChatMessageStoreTest`

查询面已全部改走 store：会话历史、会话读取 tail、历史搜索、Memory evidence/consolidation、Comparison、Project 分析/playbook。生产代码中只剩 `MongoChatMessageStore` 和迁移服务直接引用 `MongoCollection<ChatMessage>`。

## Schema

`app.chat_messages`：

- `id` 主键、`session_id`、`seq`
- `role`、`trace_id`、`content`、`created_at`
- `data jsonb` 保存 thinking/tools/events/sandbox/compression
- 唯一约束 `(session_id, seq)`
- 索引：`(session_id, seq)`、`(session_id, created_at)`、`(session_id, role)`、`trace_id`、`created_at`
- `pg_trgm` GIN 索引：`content`

**content 不重复存储**：写入时 `JsonUtil.toJson(message)` 后由 SQL `?::jsonb - ?` 移除 `content` 键，content 单独进列；读取时从列回填实体。实测 `data ? 'content' = false`。

## 数据迁移

`ChatMessageMigrationService` 启动时执行：

1. advisory lock 防止多副本并发；
2. 按 `_id` 分页读 Mongo（200/批）；
3. `insertIfAbsent` 写入 PG；
4. 成功后写 `chat_messages_v1` marker。

dev 结果：

| 数据 | 数量 |
|---|---:|
| Mongo `chat_messages` | 17477 |
| PG `app.chat_messages`（首次复制） | 17477 |
| Mongo 会话数 | 1754 |
| PG 会话数 | 1754 |
| PG（含 E2E 新建 2 条） | 17479 |
| marker | `chat_sessions_v1`、`chat_messages_v1` |

## 脏数据处理

首次迁移失败两次，均为真实历史数据：

1. `...-01`：`jsonb` 拒绝 `\u0000`（tool result 里有 NUL），启动事务回滚。
2. `...-02`：第一版清洗直接对序列化 JSON 做 `replace("\\u0000","")`，把合法的字面量 `\\u0000` 破坏成非法转义 `\s`，启动事务再次回滚。

最终方案：解析 JSON 树，对 TextNode 里的实际 NUL 字符清洗，再序列化。JSON 中合法的字面量 `\u0000` 原样保留。`PgChatMessageStoreTest` 覆盖两种输入。`...-03` 首迁成功，`...-04` 为无 NUL 消息跳过 JSON 重解析的最终优化版。

## E2E

| 操作 | 结果 |
|---|---|
| `GET /api/sessions/2aa1a6fc-…/history` | 2 条历史消息，content/thinking 完整回读（来自 PG） |
| 创建新会话并发送 `Reply with exactly: PG message test` | user seq=1、agent seq=2 |
| PG 新会话消息 | 2 条 |
| Mongo 新会话消息 | 0 条 |
| 删除测试会话 | session `deleted=true` |

新消息只写 PG、Mongo 不新增，确认运行时已切到 PG store。

## 自动化测试

- `./gradlew :core-ai-server:check`：通过
- `PgChatMessageStoreTest`（本地 PostgreSQL 16）：4 个用例通过，覆盖：
  - content 列与 JSON payload 分离/回读
  - `(session_id, seq)` 重复插入
  - latestSeq、按 seq 历史、按 role count
  - 搜索词、`anyScope`（trace OR session window）
  - NUL 清洗 + 字面量 `\u0000` 保留

## 回滚

- 回滚本批次：部署 `g2-session-pg-20261110-03`，保留 `SYS_STORE_MODE=pg`。该镜像没有 ChatMessage PG store，会继续读写 Mongo；ChatSession 仍走 PG。
- Mongo `chat_messages` 未删除、未修改。
- PG 表 `app.chat_messages` 可保留。
- 回滚期间新增的 PG 消息不会回到 Mongo；如需完整回滚，应先导出这期间的消息再切回。

## 遗留

- `FileRecord`、`SessionFeedback`、`SessionNotice`、`UserChannelTarget`、`SessionAttachmentRef`、`GeminiFile`、`UserReport`、`UserTodo` 仍待迁移。
- 搜索使用 `content ILIKE` + `pg_trgm`；CJK 双字词短于 trigram，仍可正确过滤，只是索引收益有限。
- UAT 迁移需评估 792MB 消息数据的复制耗时；必要时把迁移从启动 hook 移到独立 Job。
