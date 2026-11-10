# dev G2 第四批：小集合迁移验收

- 日期：2026-11-10
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:g2-small-collections-20261110-02`
- 存储：`SYS_STORE_MODE=pg`，Flyway `V15__small_collections.sql` 已应用
- 结论：**PASS**

## 范围

本批次迁移剩余 7 个小集合：

- `SessionFeedback`
- `SessionNotice`
- `UserChannelTarget`
- `SessionAttachmentRef`
- `GeminiFile`
- `UserReport`
- `UserTodo`

每类都有 Mongo/PG 双实现：

- `SessionFeedbackStore`
- `SessionNoticeStore`（abstract + Mongo/PG）
- `UserChannelTargetStore`（abstract + Mongo/PG）
- `SessionAttachmentRefRepository`（interface + Mongo/PG）
- `GeminiFileRepository`（interface + Mongo/PG）
- `UserReportStore`
- `UserTodoStore`

`SmallCollectionStoreModule` 在 channel/session/gateway 模块之前绑定；`SmallCollectionMigrationService` 一次事务复制全部 7 个集合，marker `small_collections_v1`。

## Schema

`V15__small_collections.sql` 为每个集合建 `id + data jsonb + updated_at` 表，并加 JSONB 表达式索引：

- session_feedback：user、session
- session_notices：user/channel/recipient/answered、createdAt
- session_attachment_ref：session/user/kind/createdAt
- gemini_files：source 五元组、expiresAt
- user_reports：user+updatedAt、user+createdAt
- user_todos：user+createdAt、user+completed

## 数据迁移

| 集合 | Mongo | PG |
|---|---:|---:|
| session_feedback | 0 | 0 |
| session_notices | 3 | 3 |
| user_channel_targets | 1 | 1 |
| session_attachment_ref | 17 | 17 |
| gemini_files | 0 | 0 |
| user_reports | 0 | 0 |
| user_todos | 0 | 0 |
| marker | - | `small_collections_v1` |

## E2E

| 操作 | 结果 |
|---|---|
| `GET /api/for-you` | reports/todos/active/files 计数正常 |
| `POST /api/for-you/reports` | PG 有行，Mongo 0 行 |
| `POST /api/for-you/todos` | PG 有行，Mongo 0 行 |
| `GET /api/for-you/reports` / `todos` | 各返回 1 条 |
| `PUT /api/for-you/todos/:id` completed=true | PG 更新，active count 从 1 → 0 |
| `DELETE /api/for-you/reports/:id` / `todos/:id` | PG 行删除 |

`SessionNotice` / `UserChannelTarget` / `SessionAttachmentRef` / `GeminiFile` 的内部查询由 `PgSmallCollectionStoresTest` 覆盖。

## 自动化测试

- `PgSmallCollectionStoresTest`（本地 PostgreSQL 16）：4 个用例通过
  - feedback insert/duplicate
  - notice record/unanswered/markAnswered
  - channel target record/load
  - attachment find/prefix/videos/sandbox/attachFile
  - gemini find/replace
  - report/todo list/count/active/replace
- 相关 Channel/Session/Attachment 测试通过
- 全量 `:core-ai-server:check` 仍被并行任务的 lint 错误阻塞（Gateway/decision 相关）；本批次文件未出现在 checkstyle/PMD 报错中

## 事故与修复

`...-01` 首次部署后 `GET /api/for-you` 报：

```
ERROR: syntax error at or near "WHERE"
```

根因：`PgUserTodoStore.list` 构造 SQL 时漏 append `SELECT`，只剩 ` WHERE ...`。修复后 `...-02` 部署，dashboard/list/active count E2E 通过。

## 回滚

- 回滚本批次：部署 `g2-file-record-20261110-01`，保留 `SYS_STORE_MODE=pg`。该镜像继续读写 Mongo 小集合；ChatSession/ChatMessage/FileRecord 仍走 PG。
- Mongo 7 个集合未删除、未修改。
- PG 表可保留；回滚期间新增的 PG 小集合数据不会回到 Mongo。

## 状态

G2 十个集合（ChatSession、ChatMessage、FileRecord、SessionFeedback、SessionNotice、UserChannelTarget、SessionAttachmentRef、GeminiFile、UserReport、UserTodo）已全部切到 PG。Mongo 集合全部保留，用于回滚与后续核对。
