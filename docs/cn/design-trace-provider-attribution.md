# Gateway trace provider 归因设计

> 状态：已实现（2.0.342 起，随 2.0.343 发布）
> 范围：core-ai-server 的 gateway 路由、LLM span ingest、trace/span 存储、analytics 与计费
> 非范围：历史 trace 的精确还原、prod 部署

## 1. 背景

gateway 允许同一个 model alias（`gateway_model.model_id`）注册在多个 provider 上，用 `priority`
决定哪一行接流量。这个能力用于“优先走某个 provider、其余作为备用”。

但 trace/analytics 原先只记录调用方使用的 model alias，provider 是查询时用
`AnalyticsMappingService` 的 model → provider 映射反推的：

- `app.traces` / `app.spans` 没有 provider 列；
- 同名 alias 有多行时，映射只能取其中一行；
- 改变 priority 或新增一行后，**历史 trace 的 provider 归属会跟着当前配置变化**；
- `ModelPricingService` 也按 alias 取第一行，provider 价格不同时金额可能取错。

2026-11-23 UAT 把 `deepseek-flash` 的 priority 0 行加到 10086 后，当天切换前实际走官方
`deepseek` 的 195 条 trace（$11.53）在实时分析里被显示成 10086，确认了该缺陷。

## 2. 决策

**在请求发生时记录实际 serving provider，而不是事后按 alias 推断。**

- 路由层在 LLM span 上打 `gateway.provider.id`（最终成功/最终尝试的 provider）。
- ingest 把 provider id 写到 span，并回填到 trace（首个 LLM span 的值；多 provider 的 trace
  仍按 trace 级归因，和现有 trace 级 model 口径一致）。
- analytics 优先使用存储的 `provider_id`；没有存储值的历史行才回退到 model 映射。
- pricing 在 provider id 已知时按 `(provider, model)` 取 gateway model 行；未知时保持原 alias 逻辑。

## 3. 数据模型

新增 Flyway `V50__trace_provider.sql`：

```sql
ALTER TABLE app.traces ADD COLUMN IF NOT EXISTS provider_id TEXT;
ALTER TABLE app.spans ADD COLUMN IF NOT EXISTS provider_id TEXT;
CREATE INDEX IF NOT EXISTS traces_provider_started_idx ON app.traces (provider_id, started_at DESC);
CREATE INDEX IF NOT EXISTS spans_provider_started_idx ON app.spans (provider_id, started_at DESC);
```

Mongo 存储使用实体字段 `provider_id`，无需额外迁移。

## 4. 运行时链路

```
GatewayLLMProvider / GatewayAgentTrace
        └─ LLM span attribute: gateway.provider.id = <gateway_provider.id>
IngestService / OTLPIngestService
        ├─ Span.providerId
        └─ TraceIdentity.backfillProvider(traceId, providerId)  (仅当 trace 为空)
PgTraceStore / PgSpanStore / Mongo stores
        └─ provider_id 列/字段
Analytics (PgTraceAnalytics / Mongo analytics)
        └─ COALESCE(provider_id, CASE model ... END)
ModelPricingService
        └─ listByProviderId(providerId) → modelId 匹配 → 该行价格；否则回退 alias
```

## 5. 历史数据

- 新数据：从 2.0.342 起自动记录真实 provider。
- 2026-11-23 UAT 的当天数据：按 6 条 10086 路由的 `createdAt` 做一次性回填，仅更新
  `provider_id IS NULL` 的行；脚本 `.core-ai/tasks/uat-provider-backfill-20261123.sql`。
- 更早的数据：`analytics_daily_stats` 已按当时配置生成，保留原 provider；实时查询仍会按当前
  alias 映射回退，无法精确还原。

## 6. 验证

- 单测：`ModelPricingServiceTest`（provider 价格优先）、`OTLPIngestServiceTest`（span/trace provider）、
  `PgTraceSqlParamsTest`（analytics SQL 参数与 `COALESCE(provider_id`）。
- UAT：`deepseek-flash` 请求命中 10086 后，`app.traces.provider_id` 指向 10086；回填后切换前的
  行指向官方 `deepseek`；analytics provider 维度按存储值聚合。

## 7. 回滚

- 代码回滚：`kubectl --context FBRDevAKSCluster -n uat-ai rollout undo deploy/core-ai-server`。
- 列和索引是 additive 的，回滚旧镜像可继续保留，不影响旧代码。
- 回填脚本是幂等的；如需撤销可把对应 `provider_id` 置回 NULL（仅限本次回填范围）。

## 8. 限制

- 一个 trace 内发生 failover 时，trace 级 provider 取首个 LLM span 的 provider；span 级未来可细化。
- 回填依赖路由创建时间，仅适用于本次 UAT 的明确变更窗口。
