# Dev E2E — trace ingest P0 no-loss path (2.0.374)

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-25 |
| 环境 | dev（namespace `dev-ai`，`FBRDevAKSCluster`） |
| 镜像 | `chancetop/core-ai-server:2.0.374` |
| 索引 digest | `sha256:a7d69e187cd58df6628f84443e2f3947d424c9cb469af7fb7025989fb3fd40b3` |
| Store | PostgreSQL trace store（dev `sys.store.mode=pg`） |
| 脚本 | `.core-ai/tasks/dev-trace-ingest-p0-e2e.py` |
| 结果 | PASS |

## 1. 异步接收与缓冲观测

- `/api/admin/runtime/stats` 的 `span_ingest` 返回新增字段：
  `buffer_depth=0`、`buffer_bytes=0`、`buffer_rejected_total=0`、`buffer_failed_total=0`。
- `POST /api/traces/ingest` 两次不同 span + 一次完整 replay，首个请求 HTTP 响应 **0.764s**；
  返回时 trace 尚未必可查，符合“200 = 已进入缓冲区”的 P0 契约。
- 畸形 JSON 仍返回 **400**，没有被缓冲层误报成 503。

## 2. 幂等与 totals 重算

- trace `19fdefab221647a2a3fc4a223af0f59b`：root 11/7 tokens + child 5/3 tokens；
  两次写入后再完整 replay 两个 span。
- 最终 `GET /api/traces/{traceId}/spans` 恰好 **2 行**，trace `inputTokens=16`、
  `outputTokens=10`、`totalTokens=26`，没有因 replay 翻倍。
- 说明 `insertIfAbsent`（PG `ON CONFLICT DO NOTHING` / Mongo duplicate-key）与
  `TraceTotalsReconciler` 的绝对 totals 重算在 dev PG 上生效。

## 3. 发布事故与修复

- 2.0.372 构建通过但未部署；2.0.373 部署后新 pod CrashLoopBackOff：
  `found not used properties, keys=[sys.trace.ingest.shutdown.timeout.seconds]`。
  core-ng 只认模块初始化期间读取过的声明键；该 key 原先在 shutdown lambda 里才读取。
- 2.0.374 在 `TraceModule.initialize()` 先解析 `shutdownTimeout`，启动恢复；2/2 Ready，
  pod digest 与 `docker buildx imagetools inspect` 的索引 digest 一致。
- `PgSpanTokenTotalsRow` 必须在 `PostgresModule` 注册；2.0.373 已补，否则 dev PG 的
  `tokenTotals` 查询会在首次 ingest 时以 `view class is not registered` 失败。

## 4. 验证范围

- `:core-ai-server:test`、`:core-ai-cli:test` 全绿；server/CLI 的 checkstyle、PMD、SpotBugs 全绿。
- 本次 E2E 只覆盖 P0（异步缓冲、503 语义、CLI 重试的单元测试、幂等插入、totals 重算、
  payload 异常传播）；P1 的命名 pool、对象存储引用、backfill、disk spool 尚未实施。
- 已知 CI 易抖：`MediaTaskRunIntegrationTest`（视频 watcher 15s）在 2.0.372 的
  Core AI Check 中超时失败；2.0.374 Build Server 的 `gradle check` 通过。
