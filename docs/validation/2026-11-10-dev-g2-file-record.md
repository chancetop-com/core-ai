# dev G2 第三批：FileRecord 迁移验收

- 日期：2026-11-10
- 环境：`FBRDevAKSCluster / dev-ai / core-ai-server`
- 镜像：`chancetop/core-ai-server:g2-file-record-20261110-01`
- 存储：`SYS_STORE_MODE=pg`，Flyway `V14__file_records.sql` 已应用
- 结论：**PASS**

## 范围

本批次迁移 `FileRecord` 元数据。文件内容继续在对象存储，不进入 PostgreSQL。

新增/调整：

- `FileRecordStore`、`FileRecordQuery`
- `MongoFileRecordStore`、`PgFileRecordStore`
- `FileRecordMigrationService` + `app.g2_migration_markers`
- Flyway `V14__file_records.sql`
- `PgFileRecordStoreTest`

查询面已全部改走 store：FileService、Artifact 我的/共享/共享用户、For You、SelfHarness、MediaJob artifact、Project attribution/report/analysis/playbook、FileStorageMigrationJob。生产代码中只剩 `MongoFileRecordStore` 和迁移服务引用 `MongoCollection<FileRecord>`。

## Schema

`app.file_records` 全部为类型化列，无 `data`：

- `id`、`user_id`、`file_name`、`content_type`、`size`
- `content_hash`、`storage_path`、`thumb_storage_path`
- `share_token`、`shared_at`、`created_at`
- 索引：user+created、user+hash、share_token 唯一（partial）、shared_at（partial）、created_at

**内容边界**：`data` 不落 PG。`PgFileRecordStore.insert` 遇到 `data != null && storagePath == null` 直接抛错；`FileRecordMigrationService` 在复制时做同样的显式检查，绝不允许静默丢内容。

## 数据迁移

dev 结果：

| 数据 | 数量 |
|---|---:|
| Mongo `file_records` | 216 |
| PG `app.file_records` | 216 |
| 用户数 | 7 |
| 共享文件 | 170 |
| marker | `file_records_v1` |

UAT 盘点：11444 个文件，全部有 `storage_path`，无 inline data；复制前仍需按同一检查执行。

## E2E

| 操作 | 结果 |
|---|---|
| `GET /api/artifacts/my?limit=3` | total=73，元数据正常 |
| `GET /api/artifacts/shared?limit=3` | total=170 |
| `GET /api/artifacts/shared/users` | 7 个用户 |
| `GET /api/files/:id` | 元数据正常 |
| `GET /api/files/:id/download-url` | 返回 Azure Blob SAS URL |
| 上传 `g2-file-e2e.txt` | PG 有行，Mongo 0 行 |
| `POST /api/files/:id/share` + `GET /api/public/artifacts/:token` | 共享 token 与公开读取正常 |
| `DELETE /api/files/:id` | PG 行删除，存储清理 |

上传只写 PG、Mongo 不新增，确认运行时已切到 PG store。

## 自动化测试

- 相关文件/Project/MediaJob 测试通过
- `PgFileRecordStoreTest`（本地 PostgreSQL 16）：4 个用例通过，覆盖 insert/duplicate、元数据更新、share token 原子抢占、shared users、过滤、inline 内容拒绝
- 全量 `:core-ai-server:check` 当前被另一并行任务的 lint 错误阻塞（Gateway/decision 相关，不属于本批次）；本批次文件未出现在 checkstyle/PMD 报错中

## 回滚

- 回滚本批次：部署 `g2-chat-message-20261110-04`，保留 `SYS_STORE_MODE=pg`。该镜像没有 FileRecord PG store，继续读写 Mongo；ChatSession/ChatMessage 仍走 PG。
- Mongo `file_records` 未删除、未修改。
- PG 表 `app.file_records` 可保留。
- 回滚期间新增的 PG 文件记录不会回到 Mongo；如需完整回滚，先导出这期间的上传记录。

## 遗留

- 小集合：`SessionFeedback`、`SessionNotice`、`UserChannelTarget`、`SessionAttachmentRef`、`GeminiFile`、`UserReport`、`UserTodo`。
- UAT 文件元数据 11444 条可快速复制；但需要先确认 0 条 inline。
