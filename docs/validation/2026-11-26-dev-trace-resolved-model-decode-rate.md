# dev E2E: trace resolved model + answer-only decode speed (2.0.388)

恢复 2026-11-25 两个会话的未提交实现后的验证记录。代码是从 `cli-20261125-135331-m7ig` 与
`cli-20261125-141155-tvc2` 的会话日志（131 条 `edit_file` + 3 条 `write_file`）重放恢复的，
重放后按当前 master 修掉 2 处冲突（`PgSpanStoreTest` 方法长度、`GatewayProxyServiceTest`
import），并通过 checkstyle/PMD/SpotBugs 与相关单测。

## 发布信息

- commit `86e5671b9`（"Record the served model and answer-only decode speed on traces; release 2.0.388"）
- CI `Build Server` run `45754471824` success；镜像 `chancetop/core-ai-server:2.0.388`，
  index digest `sha256:6af308a076d161a0029955a5f6c2847f37afb642621a7a9482d6304f0cf15036`
- dev deployment `2/2 Ready`，两个 pod 的 `imageID` digest 与 CI 镜像一致
- 迁移重排：原 `V56__span_reasoning_and_first_content.sql` 与 master 已有的
  `V56__trace_payload_backfill_poison.sql` 冲突，改名为 **V58**；`V57__trace_resolved_model.sql`
  保持。两者已手工应用到 dev `postgresql-2`（`IF NOT EXISTS`，dev 的 Flyway 历史仍只到 V1）。

## E2E 1：归因与解码速率

脚本 `.core-ai/tasks/trace-model-decode-e2e.py`，trace `9dc669cba1944f33bb93959855617b7f`：

- 两条 LLM span：`model=deepseek-flash`、`resolvedModel=gpt-6-luna`；
  样本一 output 400 / reasoning 380 / firstContent 500ms / duration 2500ms（10 tok/s），
  样本二 output 200 / reasoning 50 / firstContent 300ms / duration 3300ms（50 tok/s）。
- `GET /api/traces/{id}` 与 `/spans` 返回 `resolvedModel=gpt-6-luna`（trace 与两条 span）。
- `GET /api/admin/analytics/llm-metrics?minutes=60`：`deepseek-flash` 行
  `p50OutputTokensPerSecond=10.0`、`p95=50.0`、`avg=34.0`，与构造样本的逐 token 加权完全一致
  （旧口径会把 reasoning 计入、窗口从首 token 起算，两个样本都会虚高）。
- PG 行核对：`reasoning_tokens` 380/50、`first_content_ms` 500/300、`ttft_ms` 200/300 均落库；
  `app.traces.resolved_model=gpt-6-luna`，trace output tokens 600。

## E2E 2：定价按实际服务模型

脚本 `.core-ai/tasks/trace-pricing-e2e.py`，trace `82105d017b2c4e588e683e623c8edeea`：
`model=deepseek-flash`、`resolvedModel=decision-1`、`inputTokens=100000`、
`attributes["gateway.provider.id"]=c135ac82-…`。

- 结果 `cost_usd=0.0042`、`cost_source=gateway_model`、`pricing_model_id=decision-1`
  （decision-1 输入价 0.042/1M；按别名 deepseek-flash 计价只有 0.00006），确认 fallback 流量
  不再按别名价格计费。

## 备注

- `SpanView/TraceView` 只暴露 `resolvedModel`；`reasoningTokens`/`firstContentMs` 是内部指标，
  仅用于 LLM Ops 聚合，不在 span 详情 API 中返回（E2E 通过 PG 行核对）。
- `MongoSpanStore` 同步实现了同样的聚合口径（Mongo 下线前仍可用）。
