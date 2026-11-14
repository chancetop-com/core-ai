# Trace / TracePayloadBlob 迁移到 PostgreSQL（G5b 盘点与设计）

状态：盘点完成，待实施
日期：2026-11-13

## 1. 范围

本次只处理 `Trace` 和 `TracePayloadBlob`。`Span` 已完成（V32，dev 已于 2026-11-13 回填 13,008 条）；
`RuntimeSample`、`AnalyticsDailyStats`、`TraceDailyStats` 放在 G5c。

## 2. 数据量（dev 实测）

| 集合 | docs | 大小 |
|---|---:|---:|
| `traces` | 317 | 17.8 MB |
| `trace_payload_blobs` | 30 | 5.0 MB |
| （参考）`runtime_samples` | 86,599 | 111.2 MB |

数据量不大，难点在查询面，不在拷贝。

## 3. 调用点盘点

`MongoCollection<Trace>` 出现在 13 个文件、60+ 处调用，分四类。

### 3.1 写入 / 增量更新

| 位置 | 操作 |
|---|---|
| `trace/service/IngestService.java:166,202,266,277,288,309,331` | insert；按 `trace_id` 做条件 `$set`（agent_name / agent_id / name / model / input / output / duration_ms / completed_at）和 `$inc`（input/output/total/cached tokens、cost_usd） |
| `trace/service/OTLPIngestService.java:144,319,325,344` | insert；同样的条件 `$set` / `$inc` |
| `trace/service/TraceIdentity.java:38` | 按 `trace_id` 回填身份字段 |
| `trace/service/TraceService.java:395` | insert |

所有更新都是**条件字段写入 + 计数自增**，SQL 侧是 `UPDATE ... SET x = CASE/COALESCE ... WHERE trace_id = ?` 和 `x = COALESCE(x,0) + ?`，机械可转。

### 3.2 列表 / 详情读取

| 位置 | 查询 |
|---|---|
| `trace/service/TraceService.java:71,194,202,299,304,343,363,391` | 时间范围 + type/source/agent/model/status/session/user 过滤，按 `created_at` 倒序分页；`get` 先按 `_id` 再按 `trace_id` 兜底；按 user 反查 trace_id（上限 10,000） |
| `trace/maintenance/StaleRunCleanupService.java:89,102,115` | 按 `started_at` + status 找僵死 trace，按 `trace_id` 查单条，更新 status |
| `trace/maintenance/TraceArchiveService.java:103,137,176,186` | 按 `started_at` 分页取归档批次、count、按 `_id` 批量删除 |
| `memory/AgentMemoryService.java:172` | 按 session/user 取 trace |
| `memory/AgentMemoryConsolidationJob.java:132,177` | 按时间窗取 trace |
| `web/foryou/ForYouService.java:209` | 按 user/time 取 trace |

### 3.3 聚合

| 位置 | 分组 | 指标 |
|---|---|---|
| `trace/service/TraceService.java:251` facets | source / agent_id / user_id / model | count |
| `trace/service/TraceService.java:350` sessionSummary | session_id | count / sum(tokens,cost,duration) / first,last |
| `trace/maintenance/TraceDailyMaintenanceService.java:78,155` | 按天 + source/agent/model | sum(tokens,cost) / count |
| `analytics/AdminAnalyticsService.java:215,234,432` | 时间范围 + 维度（source/agent/user/provider/model），含按小时的趋势 | sum(tokens,cost) / count；provider 由 model 映射（Java 侧可做） |
| `apiuser/ApiUserUsageService.java:60` | 按天 | sum(tokens,cost) / count |
| `costalert/CostAlertService.java:117` | 无分组（窗口合计） | sum(cost,tokens) / count |
| `project/ProjectQueryService.java:239,254` | session_id / trace_id | sum(cost) |
| `project/ProjectStatsQueryService.java:138` | 可配置维度 | sum(tokens,cost) / count / first(name) |

聚合形状一致：时间窗口 `match` → 可选 `addFields`（provider 映射）→ `group` → `sum/count` → `sort`。
映射到 SQL 是 `WHERE started_at >= ? AND started_at < ? GROUP BY ...`；`provider` 维度先在 SQL 里按 `model` 分组，再在 Java 里合并成 provider。

### 3.4 TracePayloadBlob

`TracePayloadStore` 把 trace/span payload 里的内联图片 data URL 抽出来，用 `coreai-image://<sha256>` 引用替换；
`trace_id` 维度存取。只有 30 条 / 5 MB，按 `trace_id + hash` 建表即可。

## 4. 表设计（V34）

```sql
CREATE TABLE app.traces (
    id text PRIMARY KEY,
    trace_id text NOT NULL,
    name text,
    model text,
    type text,
    source text,
    agent_name text,
    agent_id text,
    session_id text,
    user_id text,
    status text,
    error_message text,
    input text,
    output text,
    metadata jsonb,
    input_tokens bigint,
    output_tokens bigint,
    total_tokens bigint,
    cached_tokens bigint,
    cost_usd double precision,
    duration_ms bigint,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz,
    updated_at timestamptz
);
CREATE INDEX traces_trace_id_idx ON app.traces (trace_id);   -- 非唯一：历史数据可能有重复
CREATE INDEX traces_created_idx ON app.traces (created_at DESC, id DESC);
CREATE INDEX traces_started_idx ON app.traces (started_at, id);
CREATE INDEX traces_session_idx ON app.traces (session_id, created_at DESC);
CREATE INDEX traces_user_idx ON app.traces (user_id, created_at DESC);
CREATE INDEX traces_status_started_idx ON app.traces (status, started_at);

CREATE TABLE app.trace_payloads (
    trace_id text NOT NULL,
    blob_hash text NOT NULL,
    content text NOT NULL,
    PRIMARY KEY (trace_id, blob_hash)
);
```

`input` / `output` 用 `text`（与 spans 一致），列表页不查这两列。

## 5. 接口设计

新增 `TraceStore`，方法只接受**类型化查询对象**，不暴露 Bson：

```java
public interface TraceStore {
    void insert(Trace trace);
    Trace get(String id, String traceId);          // _id 优先，trace_id 兜底
    List<Trace> list(TraceQuery query);            // 过滤 + 排序 + 分页
    long count(TraceQuery query);
    List<TraceFacetRow> facets(TraceQuery query, TraceFacet facet, int limit);
    List<String> traceIds(TraceQuery query, int limit);
    void update(TraceUpdate update);               // 条件 set + 增量
    long deleteByStartedBefore(ZonedDateTime cutoff, int limit);
}
```

`TraceQuery` 承载：ids / prefix / type / source / agent / model / status / session / user(s) / 时间范围 /
排序 / offset / limit。`TraceUpdate` 承载：按 trace_id 的条件字段写入与计数增量。

Mongo 实现把 `TraceQuery` 翻成 Bson（现有逻辑原样搬过去）；PG 实现拼参数化 SQL。

## 6. 切流方式

**不能只切一半**：写入切到 PG 而聚合还在读 Mongo，报表会静默变空；反之则新 trace 查不到。

建议顺序：

1. 抽 `TraceStore` + `MongoTraceStore`，把所有调用点从 `MongoCollection<Trace>` 改为 store（行为不变，可单独发布验证）。
2. 加 `PgTraceStore` + `TraceMigrationService`（复用 spans 的后台分片 + `app.migration_progress` 游标）。
3. dev 上 `SYS_TRACE_STORE=pg` 整体切换，跑回填，验证行数/marker/内存。
4. UAT 全量演练，再排 prod。

回滚开关：`sys.trace.store=mongo|postgresql`（默认 mongo），与 `sys.dataset.store` 同风格。

## 7. 工作量

| 阶段 | 内容 | 预估 |
|---|---|---|
| 1 | `TraceStore` + `MongoTraceStore` + 13 个文件改造 | 1–1.5 天 |
| 2 | V34 + `PgTraceStore` + 回填服务 | 1–1.5 天 |
| 3 | dev/UAT 验证、性能对比、切流 | 0.5 天 |

## 8. 风险

- `AdminAnalyticsService` 的 provider 维度依赖 `model -> provider` 映射，SQL 分组后要在 Java 合并，注意去重口径。
- `TraceArchiveService` 按 `_id` 批量删除；PG 侧要按 `id` 删除并同时删 `spans`（现有逻辑已按 trace 删 span，需核对）。
- `get(traceId)` 的 `_id` 优先 / `trace_id` 兜底语义要保留，否则 OTLP 与 chat 两条写入路径的 id 不一致时会查不到。
- 历史 `traces` 可能有重复 `trace_id`，唯一索引会建失败；先用非唯一索引。

## 9. 实施记录（2026-11-13）

- Flyway 版本：**V35**（V34 已被并行会话的 `app.session_shares` 占用），dev 与 UAT 均已执行。
- 唯一索引保留：`traces_trace_id_uidx`；回填用 `INSERT ... ON CONFLICT DO NOTHING`，历史重复的 `trace_id` 只会保留先写入的一条，与 Mongo 侧“一个 trace_id 一份文档”的语义一致。
- 开关（与 span store 同风格）：
  - `sys.trace.store.mode=auto|mongo|pg`（默认 auto，跟随 `sys.store.mode`；`pg` 要求全局也是 `pg`）
  - `sys.trace.migration=copy|skip`（默认 copy；skip 时不回填，历史留在 Mongo）
- P90 口径：Mongo 侧是“最近秩”取第 `ceil(0.9n)` 个值，SQL 侧用 `percentile_disc(0.9)` 对齐（不是插值的 `percentile_cont`）。realtime 路径的 `p90_total_tokens` 仍沿用历史语义 = `max`。
- `trace_payload_blobs` 暂不迁移：blob 由 span 与 trace 共用，UAT 的 span store 仍是 Mongo（`SYS_TRACE_SPAN_STORE_MODE=mongo`），因此 `app.trace_payloads` 建表但空置，`TracePayloadStore` 继续读写 Mongo。
- 验证结果：
  - dev（镜像 2.0.229）：回填 321 条，`traces_v1` marker 写入、进度行删除；列表 / facets / 详情 / session summary / analytics realtime 全部经 API 验证；手工 ingest 一条验证写入路径后删除。
  - UAT（镜像 2.0.229）：回填 19,707 条，marker 写入；列表与 analytics 各维度 API 验证通过；PG 内存 ~1.0 GiB / 2 GiB。

## 10. G5c 实施记录（2026-11-14）

范围：`runtime_samples`、`trace_daily_stats`、`analytics_daily_stats`。

- Flyway **V36**（dev/UAT 均已执行）：
  - `app.runtime_samples`（51 列，5 个 JSONB 桶分布列）+ `runtime_samples_collected_idx`
  - `app.trace_daily_stats` + `(user_id, date)`、`(date)` 两个索引
  - `app.analytics_daily_stats` + `(date)`、`(user_id, date)` 两个索引
- 新增 store（Mongo / PG 双实现）：
  - `RuntimeSampleStore`：`insert` / `bulkInsert` / `range`
  - `TraceDailyStatsStore`：`upsert` / `countByDate` / `dailyUsage`
  - `AnalyticsStatsStore`：`upsert` / `stats` / `trend` / `dimensionTrend`
- 开关：`sys.runtime.store.mode`（auto/mongo/pg）+ `sys.runtime.migration`（copy/skip）；两张日汇总表跟随 `sys.trace.store.mode`，避免 trace 与 rollup 落在不同存储上。
- 回填抽了共享基类 `PagedBackfill`：游标 + advisory lock + marker + “整页失败则逐行重试并跳过坏行”。三个新回填都复用它。
- 踩坑记录：
  1. `Database.beginTransaction()` 必须 close（try-with-resources）才会清理 ThreadLocal，否则同一线程后续查询报 `db access is not allowed after transaction ended`。
  2. `dailyRollup` 曾多绑一个参数（`GROUP BY` 用序号后不再需要第二个占位符），只在每日任务真正执行时暴露；已补 `PgTraceSqlParamsTest` / `PgRuntimeSampleSqlParamsTest`，对每条生成的 SQL 断言占位符数 == 绑定参数数。
  3. core-ng 的 `@Column(json=true)` 只接受 bean/list，`Map<String, Integer>` 不行，桶分布列的 Map 按 JSON 文本存 + Jackson 转换。
- 验证结果：
  - dev（镜像 2.0.233）：`runtime_samples` 86,712、`trace_daily_stats` 1,607、`analytics_daily_stats` 264，三个 marker 均写入；`/api/admin/runtime/{pods,stats,series}`、`/api/for-you/token-usage`、analytics history 全维度通过；手工触发 `TRACE_DAILY_MAINTENANCE:2026-11-12` 成功（traceDailyStats=1、analyticsDailyStats=1）。
  - UAT（镜像 2.0.233）：`runtime_samples` 86,456、`trace_daily_stats` 3,064、`analytics_daily_stats` 2,651，marker 全部写入；同上 API 通过；手工触发每日任务成功（traceDailyStats=39、analyticsDailyStats=56）；PG 内存 ~0.9 GiB / 2 GiB。


## 11. UAT span 切 PG 与 payload 迁移（2026-11-14）

### 11.1 UAT span store 切到 PostgreSQL

- UAT 部署先加 `SYS_TRACE_SPAN_MIGRATION=skip`（镜像 `2.0.233`），再从 configmap `core-ai-server-config` 删除 `SYS_TRACE_SPAN_STORE_MODE=mongo`，rollout 后 `sys.trace.span.store.mode=auto` 跟随 `SYS_STORE_MODE=pg`。
- 启动日志确认 `SpanMigrationService - span migration skipped by configuration`，**没有**回填 39 万 Mongo span，也没有写 `spans_v1` marker。
- 验证：手工 ingest 一条 span 后 `app.spans` 从 0 → 1，Mongo `spans` 停在 394,122 不再增长；旧 trace 的 span 查询返回空（预期降级），新 trace 正常。
- 归档状态：UAT `TRACE_ARCHIVE` 每日成功执行（保留 30 天，归档到 Azure Blob），切流时 Mongo 内 394k span 中仅 9,346 条刚超过 30 天窗口，其余为 30 天内热数据，不在归档范围；切流后这批热数据在应用侧不可见，但 Mongo 数据未删、回滚即可恢复。
- 回滚：configmap 加回 `SYS_TRACE_SPAN_STORE_MODE=mongo` 并 rollout。

### 11.2 trace_payload_blobs 迁移到 app.trace_payloads

- 新增 `TracePayloadRepository`（Mongo / PG 双实现），`TracePayloadStore` 的 compaction、hydrate、delete 全部走仓库；`app.trace_payloads` 从空置变为正式存储。
- 回填 `trace_payloads_v1`：dev 30 条、UAT 20,076 条（约 11 GB，单条最大 12 MB，页大小按 10 条控制内存）。
- 验证：旧 trace 详情与单个 span 详情均能把 `coreai-image://` 还原为原始 data URL（dev 与新 ingest 的 UAT trace 都验证）；新 ingest 的图片直接写入 PG。
- UAT 磁盘：回填后 20Gi 卷用到 14G（剩余 5.9G），建议后续扩到 30Gi。
