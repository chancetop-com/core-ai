# Dev E2E — trace Layer 2 body offload (2.0.398)

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-26 |
| 环境 | dev（namespace `dev-ai`，`FBRDevAKSCluster`） |
| 镜像 | `chancetop/core-ai-server:2.0.398` |
| 镜像 digest | `sha256:35e6f91dd7d0bc0e4526fdd72208ff184038d7747e3b2440a942dae637dd810c` |
| 构建 | CI `Build Server` run `45803999090`，commit `0817c9fd2`（workflow_dispatch） |
| 对象存储 | dev Azure Blob（`fbrdevbostorage`，私有 `artifacts` 容器，前缀 `dev-ai/`） |
| 脚本 | `.core-ai/tasks/dev-trace-layer2-e2e.py` |
| 结果 | PASS |

## 1. 变更

- `sys.trace.body.inline.max.bytes`（默认 16 KiB）以内的 span/trace `input`/`output` 保持内联；
  超过阈值的 body 先 gzip，再写入对象存储：
  `{prefix}/trace-bodies/{YYYY-MM}/{traceId}/{spanId}.{in|out}.json.gz`。
- PG 的 `app.spans` / `app.traces` 只保留轻量引用 JSON：
  `{"$coreai_blob_ref":"coreai-blob://...","encoding":"gzip","rawBytes":...,"compressedBytes":...,"sha256":"...","preview":"..."}`。
- `preview` 对 input 使用 `TracePreviewExtractor.extract()`（保留最后一条 user 消息），
  对 output 取前 512 字符；列表/会话摘要直接读取该 preview，不再显示引用 JSON。
- 详情读取：`<= 2 MiB` 的 body 透明解压回原文；更大的 body 保留引用，由
  `GET /api/traces/{traceId}/spans/{spanId}/body?field=input|output` 按 `TRACE_VIEW` + trace 归属鉴权按需返回全文。
- 归档：删除 spans/traces 前先收集 `$coreai_blob_ref`，PG 行删除提交后再删除已关闭月份的 body 对象；
  当月对象保留给 Lifecycle 兜底。
- 无对象存储或 Mongo trace store 时自动保持内联；不需要数据库迁移。

## 2. 验证结果

| 用例 | body 大小 | 结果 |
|---|---|---|
| small | 666 B | PG 内联原文，详情返回原文，未出现引用 |
| medium | 65,691 B | PG 只存 293 B 引用；详情透明解压为原文；列表 preview 为 `medium layer2 question` |
| huge | 3,145,881 B（gzip 后 3,194 B） | PG 只存 294 B 引用；详情保留引用；body 端点返回完整 3,145,881 B，字节完全一致 |

SQL 实测（`app.spans`）：

- small：`octet_length(input)=666`，内容为原始 JSON；
- medium：`octet_length(input)=293`，以 `{"$coreai_blob_ref":"coreai-blob://dev-ai/trace-bodies/2026-11/...` 开头；
- huge：`octet_length(input)=294`，引用同一格式。

`app.traces` 的 input 同样为 319–320 B 的引用（trace 级 body 使用 `trace-<uuid>` 独立对象，
避免同一 trace 的多个 root span 并发覆盖同一个对象键）。

body 端点：

- `GET /api/traces/{traceId}/spans/{spanId}/body?field=input` → 200，`application/json`，
  3,145,881 bytes 与上传原文逐字节一致；
- `?field=nope` → 400。

## 3. 边界与后续

- 归档时 body 对象的引用感知删除只有单元测试覆盖；本次未构造 30 天归档窗口。
- 前端 Span 详情对 `> 2 MiB` 的 body 目前会显示引用 JSON；UI 尚未接入 body 端点（后续前端改动）。
- 本次只部署 dev；UAT / prod 未变更，升级需显式授权。
- 历史 `app.spans` 的内联 body 不做回填；只有新写入的数据享受 Layer 2 卸载，
  UAT 已有的 25 GB `spans` 需要另行设计回填/缩容（与 `trace_payloads` 的 `VACUUM FULL` 分开处理）。

## 4. 结论

Layer 2 写入、读取、预览与按需 body 端点已在 dev 验证通过：3 MiB 的 span body 在 PG 中从
3,145,881 B 降到 294 B，且详情/端点仍能拿到原文。
