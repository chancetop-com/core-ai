# Dev E2E — trace payload Layer 1 object refs (2.0.375)

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-25 |
| 环境 | dev（namespace `dev-ai`，`FBRDevAKSCluster`） |
| 镜像 | `chancetop/core-ai-server:2.0.375` |
| 索引 digest | `sha256:818fb74d156b4ef6a47f13f74754d11044d53695b4d39fa60e42b4b9f3f95f40` |
| 数据库 | `app.trace_payloads` 手工应用 V55（nullable content + object_hash/storage_key/content_type/size_bytes + 两个索引） |
| 对象存储 | dev Azure Blob（`fbrdevbostorage`，私有 `artifacts` 容器） |
| 脚本 | `.core-ai/tasks/dev-trace-layer1-e2e.py` |
| 结果 | PASS |

## 1. 对象引用写入

- 合成的 413-byte PNG（`image/png`）通过 `POST /api/traces/ingest` 的 `payloads` 上传；
  trace `bf101385073a43a38d46fce9b17e2242`。
- `GET /api/traces/{traceId}/payloads/{ref_hash}` 返回 **200 image/png**，字节与原始 413 bytes 完全一致。
- `ref_hash = sha256(dataUrl)` 与 `object_hash = sha256(rawBytes)` 不同，符合设计：
  - `ref_hash`：`ed653beb8aed5352f2a09fd10cd629d1cae4bf5257e9f3a4a4f120df971e381e`
  - `object_hash`：`a18c757f003a7ea856f882a66919574b4363169f34abc83160b21726a043a6e6`
- PG 行验证：
  - `content IS NULL = true`
  - `storage_key = dev-ai/trace-blobs/2026-11/a1/a18c…`
  - `content_type = image/png`，`size_bytes = 413`
- 结论：图片原始二进制写入对象存储，PG 只保留轻量引用；读路径仍按原有 `(trace_id, blob_hash)` 鉴权，
  客户端无需知道全局 `object_hash`。

## 2. 已验证路径

- `TracePayloadStore` 的 Compactor / CLI `payloads` / server payload controller 都走对象模式。
- `TracePayloadStore.load()` 与 `payload()` 能按 `storage_key` 下载并恢复；`content` 回退路径保留给旧行。
- `TraceArchiveService` 删除 payload 行时通过 `DELETE ... RETURNING storage_key` 收集候选，
  `deleteObjects` 在 `hasStorageKey` 为零引用时调用 `deleteObject`（本次未构造 30 天归档窗口，GC 只有单测覆盖）。
- `PgTraceStorageKeyRow` 已在 `PostgresModule` 注册；`PgSpanTokenTotalsRow`、V55 均已在 dev 验证。

## 3. 运维边界

- V55 必须先于 2.0.375 部署到目标环境；否则 payload 读写会因缺少新列失败。
- UAT / prod 仍是旧镜像与 V54；本次只部署 dev，未改 UAT/prod。
- 对象存储 Lifecycle（68d）和引用感知 GC 的完整归档 E2E 尚未在 dev 构造；当前只有单元测试/代码路径。
- Layer 2（>16 KiB span body gzip 外置）和 P1 命名 trace pool 尚未实施。