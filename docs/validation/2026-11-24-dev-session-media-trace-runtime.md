# Dev E2E — session media refs, trace payload boundary, runtime pressure (2.0.369)

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-24 |
| 环境 | dev（namespace `dev-ai`，`FBRDevAKSCluster`） |
| 镜像 | `chancetop/core-ai-server:2.0.369` |
| 索引 digest | `sha256:cd8b84f042038b5af84c92592b21b233ea48e9f79b9d07c5bb9af71cf4bf636c` |
| 数据库 | `app.runtime_samples` 手工应用 V54（7 列，均 `IF NOT EXISTS`） |
| 脚本 | `.core-ai/tasks/dev-media-trace-runtime-e2e.py` |
| 结果 | PASS（A/B/C） |

## 1. Design A — 会话媒体引用

- 新会话上传 1,441,103-byte PNG（800×600 随机噪声）。`ImageDownscaler` 将其缩为 321,591-byte JPEG 写入 media store。
- LLM span 输入出现结构化 ref（无 base64）：
  `{'id': 'media_dcdcbdd5-…', 'kind': 'blob', 'container': 'static', 'blob_name': 'ai/session-media/<session>/097a24bd….jpg', 'media_type': 'image/jpeg', 'size_bytes': 321591, 'sha256': '097a24bd…'}`
- `GET /api/sessions/{sessionId}/media/{mediaId}` → `200 image/jpeg`，321,591 bytes（与 store 字节一致）。
- 运行时样本：`sessions_inline_image_bytes_total` 增量 **0**；`sessions_media_ref_bytes_total` 增量 **79**（ref 元数据）；
  下一个样本 `sessions_estimated_bytes_peak_max=19,256`。
- 结论：会话状态只持有 ref，请求边界水合；旧内联驻留路径没有承接这张图。

## 2. Design B1 — Trace 载荷边界

- 同一 LLM span：`input_bytes=92,945`（< 256 KB），无 `data:image/...;base64`，trace 详情 **1,335 bytes**（< 2 MB hydrate 预算）。
- 通过 `POST /api/traces/ingest` 上传一个 70-byte PNG 的 `payloads`，再 `GET /api/traces/{traceId}/payloads/{sha256}`
  → `200 image/png`，70 bytes，PNG 头正确。验证了 CLI U1 的 server 侧落库与按需取图。
- 提示：core-ng 要求 path variable 只含字母；最初写成 `:sha256` 导致 2.0.368 启动失败，2.0.369 改为 `:hash`。

## 3. Design C1/C2 — 压力信号与度量语义

- `/api/admin/runtime/stats`：`memory.old_gen_percent=3.67`、`memory.gc_kind=young`、
  `sessions.estimated_bytes_peak_total` / `peak_max` / `media_ref_bytes_total` 均存在。
- `/api/admin/runtime/series/1/5`：13 个按时间排序的桶，每个桶含
  `old_gen_peak_percent`、`heap_after_gc_peak_percent`、`container_peak_percent`、
  `sessions_sum_peak`、`in_flight_turns_sum_peak`。
- `/api/admin/runtime/pods`：2.0.369 pod 的 `old_gen_percent=3.67 / gc_kind=young`，
  `sessions_estimated_bytes_peak_max=19,256`、`sessions_media_ref_bytes_total=79`；
  仍在 10 分钟回看窗口内的 2.0.364 pod 对新字段返回 null（预期）。

## 4. 发布事故与修复

- 2.0.368 的 `POST/GET` 路由 `/api/traces/:traceId/payloads/:sha256` 被 core-ng 拒绝：
  `path variable must be letter, variable=sha256`，新 pod CrashLoopBackOff；旧 pod 保持服务。
- 修复：`:hash`（2.0.369）。CI `Build Server` 第一次在 `MediaTaskRunIntegrationTest` 超时失败，
  重跑通过（与 2.0.364 时的已知易抖测试一致）。
