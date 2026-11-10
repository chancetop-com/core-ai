# dev G2 第一批：ChatSession 元数据迁移验收

- 日期：2026-11-10
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:g2-session-pg-20261110-03`
- 存储：`SYS_STORE_MODE=pg`，Flyway `V12__chat_sessions.sql` 已应用
- 结论：**PASS**

## 范围

本批次只迁移 `ChatSession` 元数据；`ChatMessage`、`FileRecord` 及其余 G2 集合仍留在 Mongo。

新增/调整：

- `ChatSessionStore`、`ChatSessionQuery`
- `MongoChatSessionStore`、`PgChatSessionStore`
- `SessionStoreModule`（在 `ProjectAttributionModule` 之前绑定 store，避免 eager injection 失败）
- `ChatSessionMigrationService` + `app.g2_migration_markers`
- Flyway `V12__chat_sessions.sql`
- `PgChatSessionStoreTest`

所有 `ChatSession` 读写已从 `MongoCollection<ChatSession>` 移到 store：会话生命周期、列表、搜索、Artifact、Comparison、Project 分析/报告、SelfHarness、OTLP。生产代码中只剩 `MongoChatSessionStore` 自身引用 `MongoCollection<ChatSession>`。

## Schema

`app.chat_sessions` 使用“类型化列 + `data jsonb`”：

- 类型化列：`user_id`、`agent_id`、`source`、`comparison_id`、`comparison_pair_id`、`comparison_variant`、`title`、`message_count`、`created_at`、`last_message_at`、`notify_on_complete`、`deleted_at`
- `data`：完整 `ChatSession` 文档
- 索引：user+last_message、user+created、agent+last_message、comparison pair、comparison
- 排序：PostgreSQL 显式使用 `ASC NULLS FIRST` / `DESC NULLS LAST`，与 Mongo 对 null/missing 的排序语义一致

## 数据迁移

`ChatSessionMigrationService` 在 pg 模式启动时执行：

1. `pg_try_advisory_xact_lock` 防止多副本并发复制；
2. 按 `_id` 分页读取 Mongo `chat_sessions`；
3. `insertIfAbsent` 写入 PG（insert-only，重复执行不会覆盖 PG 运行时已更新的行）；
4. 成功后写入 `app.g2_migration_markers(id='chat_sessions_v1')`。

dev 结果：

| 数据 | 数量 |
|---|---:|
| Mongo `chat_sessions` | 1770 |
| PG `app.chat_sessions`（首次复制） | 1770 |
| PG `app.chat_sessions`（含 E2E 新建后） | 1772 |
| PG 已软删 | 291（含 2 条 E2E） |
| Mongo 已软删 | 289 |
| `g2_migration_markers` | `chat_sessions_v1` |

抽样：

- `2aa1a6fc-0e21-4aca-8742-3a28b4fb45a0`：PG title `?`、message_count 2，与 Mongo 一致。

## E2E

所有请求使用 dev API key，显式访问 `https://core-ai-server.connexup-dev.net`。

| 操作 | 结果 |
|---|---|
| `GET /api/chat/sessions?limit=1` | `total=209`，返回 session 字段完整 |
| `POST /api/sessions` | 创建 `c9eef287-…`；PG 有行、Mongo 0 行 |
| `DELETE /api/chat/sessions/c9eef287-…` | `deleted=true`；PG `deleted_at` 非空 |
| `POST /api/sessions` + `PUT /api/chat/sessions/:id` + `GET` | 标题更新为 `G2 session metadata E2E`，PG 回读一致 |
| `DELETE /api/chat/sessions/c76d4bc8-…` | `deleted=true`；PG 已软删 |

新会话只写 PG、不写 Mongo，确认运行时已切到 PG store。

## 自动化测试

- `./gradlew :core-ai-server:check`：通过（无 `CORE_AI_PG_TEST_URL` 时 PG 集成测试自动跳过）
- `PgChatSessionStoreTest`（本地 PostgreSQL 16）：4 个用例通过，覆盖 insert/duplicate、消息计数、title 只写一次、loaded tools/skills/sub-agents、artifact append、comparison、软删、过滤/排序/artifact 文件过滤、null 排序、upsert
- `SessionModuleTest`、`SessionRegistryTest`、SessionSearch/Project/OTLP 相关测试：通过
- 镜像构建：`:core-ai-server:docker` + `docker build`

## 事故与修复

首次部署 `g2-session-pg-20261110-01` 启动失败：

```
can not resolve dependency, type=ChatSessionStore,
field=ProjectArtifactBinder.chatSessionStore
```

原因：`ProjectAttributionModule` 在 `SessionModule` 之前绑定 `ProjectArtifactBinder`，而 store 当时在 `SessionModule` 内绑定。修复：新增 `SessionStoreModule`，在 `ProjectAttributionModule` 之前绑定 `ChatSessionStore`；`SessionModule` 只使用 bean。`g2-session-pg-20261110-02` 部署成功。

## 回滚

- 回滚本批次：部署 `2.0.179`，保留 `SYS_STORE_MODE=pg`。`2.0.179` 没有 `ChatSession` PG store，会继续读 Mongo；G1 的 PG store 不受影响。Mongo `chat_sessions` 未删除、未修改。
- 如需回滚完整 G1：再按 `docs/validation/2026-11-10-uat-g1-migration.md` 的回滚步骤移除 `SYS_STORE_MODE`。
- PG 表 `app.chat_sessions` 可保留，不影响旧版本。

## 下一步

1. `ChatMessage`：设计 `app.chat_messages`（`session_id + seq` 唯一、大字段 `data jsonb`、搜索索引），迁移读写并做数据复制。
2. `FileRecord`：元数据进 PG，内容继续对象存储；处理 legacy inline content。
3. 其余小集合：`SessionFeedback`、`SessionNotice`、`UserChannelTarget`、`SessionAttachmentRef`、`GeminiFile`、`UserReport`、`UserTodo`。
4. 每批沿用本轮：双实现 + Flyway + 启动复制 + dev E2E。
