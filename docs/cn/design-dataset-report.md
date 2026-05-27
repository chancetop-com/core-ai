# Dataset & Report — 结构化数据管道与报表系统

## 1. 背景与问题

### 1.1 场景

Agent 通过 scheduler 定时执行，产出有时效性的数据（例如：「今日 review 好评数 = 42」），而非仅一次性文件（PDF / CSV）。
这些数据必须在 run 完成时立即落库，因为此数据后天已经无法重新获取。

当前 `AgentRun.output` 是纯文本 String，无法做结构化查询、过滤、聚合和时间序列分析。

### 1.2 目标

构建一条**闭环数据管道**：

```
用户定义 Dataset (Schema)
  → Agent 绑定 Dataset
    → Agent run 完成后自动提取结构化数据写入
      → Report 消费 Dataset 数据
        → Dashboard 展示
```

- **通用**：任何 agent 都可以产出任意 schema 的结构化数据
- **自动**：框架自动完成 extraction，不需要 agent 手动调用 tool
- **可组合**：一个 Dataset 可被多个 agent 写入、可被多个 Report 消费
- **自服务**：用户可手动或通过 agent 辅助创建 Report

---

## 2. 领域模型

### 2.1 实体关系

```
User ──1:N──> Dataset ──1:N──> DatasetRecord
  │                ▲
  │                │ outputDatasets
  │                │
  ├──1:N──> AgentDefinition ──1:N──> AgentRun
  │                                    │
  │                    extraction 时关联到 DatasetRecord.runId
  │
  └──1:N──> Report ──N:1──> Dataset
```

### 2.2 Dataset

MongoDB 集合 `datasets`。

用户定义的一张"数据表"，包含名称和 schema 字段列表。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | UUID |
| name | String | 展示名，如「Review Stats」 |
| description | String | 用途说明 |
| user_id | String | 创建者 |
| schema | List\<SchemaField\> | 字段定义 |
| created_at | ZonedDateTime | |
| updated_at | ZonedDateTime | |

**SchemaField：**

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 字段名，如 `positive_count` |
| type | Enum | `NUMBER` / `STRING` / `BOOLEAN` |
| label | String | 展示名，如「好评数」 |

schema 示例：

```json
[
  {"name": "positive_count", "type": "NUMBER", "label": "好评数"},
  {"name": "negative_count", "type": "NUMBER", "label": "差评数"},
  {"name": "total",          "type": "NUMBER", "label": "总数"},
  {"name": "positive_rate",  "type": "NUMBER", "label": "好评率"},
  {"name": "summary",        "type": "STRING", "label": "摘要"}
]
```

### 2.3 DatasetRecord

MongoDB 集合 `dataset_records`。

每次 agent run 产出的**一行结构化数据**。天然按 `run_started_at` 排序即为时间序列。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | UUID |
| dataset_id | String | 所属 Dataset |
| agent_id | String | 产出 agent |
| run_id | String | 关联 AgentRun |
| data | Map\<String, Object\> | 匹配 schema 的结构化数据 |
| run_started_at | ZonedDateTime | agent run 启动时间 |
| created_at | ZonedDateTime | 记录写入时间 |

**索引：**

| 索引 | 字段 | 类型 | 用途 |
|------|------|------|------|
| `dataset_time` | `(dataset_id, run_started_at)` | 复合索引 | 时间范围查询 |
| `dataset_agent` | `(dataset_id, agent_id)` | 复合索引 | 按 agent 过滤 |
| `dataset_run_unique` | `(dataset_id, run_id)` | 唯一索引 | 防止同一 run 重复写入同一 dataset |

### 2.4 Report

MongoDB 集合 `reports`。

报表定义，描述「从哪个 Dataset 取什么数据、以什么形式展示」。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | UUID |
| name | String | 报表名 |
| user_id | String | 创建者 |
| dataset_id | String | 数据源 |
| type | ReportType | 可视化类型 |
| config | ReportConfig | 可视化配置 |
| sort_order | Integer | Dashboard 排序 |
| created_at | ZonedDateTime | |
| updated_at | ZonedDateTime | |

**ReportType：**

| 值 | 说明 | 前端渲染 |
|------|------|----------|
| METRIC | 单值卡片 | MetricCard 组件 |
| TABLE | 数据表格 | Table 组件 |
| LINE_CHART | 折线图（时间序列） | Recharts AreaChart |
| BAR_CHART | 柱状图 | Recharts BarChart |
| SUMMARY | AI 生成文字摘要 | Markdown 渲染 |

**ReportConfig：**

| 字段 | 类型 | 说明 |
|------|------|------|
| value_fields | List\<String\> | 数值字段，如 `["positive_count", "total"]` |
| time_field | String | 时间字段，默认 `run_started_at` |
| category_field | String | 分类维度（TABLE / BAR_CHART） |
| aggregation | AggregationType | `LAST` / `SUM` / `AVG` / `COUNT` |
| default_time_range | String | 默认时间范围 `"7d"` / `"30d"` |

### 2.5 AgentDefinition 扩展

在 `AgentDefinition` 和 `AgentPublishedConfig` 各新增一个字段：

| 字段 | 类型 | MongoDB 字段名 | 说明 |
|------|------|---------------|------|
| output_datasets | List\<OutputDatasetBinding\> | `output_datasets` | agent 产出到的 dataset 列表 |

**OutputDatasetBinding：**

| 字段 | 类型 | 说明 |
|------|------|------|
| dataset_id | String | Dataset ID |

---

## 3. 核心流程

### 3.1 Agent run → 自动 extraction → 落库

extraction 在 `AgentRunner.run()` 的 async 回调中执行，`execute()` 返回后、`scheduleSandboxRelease()` 之前。这个注入点天然覆盖 `executeAgent` 和 `executeLLMCall` 两条路径。

```
┌──────────────────────────────────────────────────────────────────────┐
│  AgentRunner.run()  async callback                                   │
│                                                                      │
│  1. execute(runEntity, definition, sandbox, variables)               │
│     ├── executeAgent()  →  agent.run(input) → updateRunStatus(...)  │
│     └── executeLLMCall() → llmCallExecutor.execute() → COMPLETED    │
│                                                                      │
│  2. if (runEntity.status == COMPLETED                                │
│         && definition.outputDatasets != null):                       │
│       for each binding:                                              │
│         dataset = datasetService.get(binding.datasetId)              │
│         extracted = extractStructured(output, dataset.schema)        │
│         insert DatasetRecord(datasetId, extracted, runId, ...)       │
│                                                                      │
│  3. scheduleSandboxRelease(runId)  (finally)                         │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 Structured Extraction 策略

`LLMCallExecutor` 新增 `extractStructured()` 方法，分两种策略：

#### 策略 A：直接 parse（零成本）

触发条件：`definition.responseSchema != null`（即 agent 本身配置了 `response_schema`，output 已是结构化 JSON）。

流程：
```
agent.output (JSON String)
  → JSON.parse → Map<String, Object>
  → 按 dataset.schema 字段名提取匹配的字段（partial match）
  → schema 中有但 output 中不存在的字段设为 null
  → 提取成功则直接使用，无需 LLM
```

**Partial match 行为：**
- output 和 dataset schema 的字段名交集即为提取结果
- schema 中有但 output 中不存在的字段 → 值为 `null`
- output 中有但 schema 中没有的字段 → 忽略
- 只有当 output 不是合法 JSON 时，才 fallback 到策略 B

#### 策略 B：LLM extraction（默认）

触发条件：`definition.responseSchema == null`（agent output 是自由文本）。

流程：
```
agent.output (free text)
  → 构造 extraction prompt:
      "Extract the following fields from the text below.
       Schema: {positive_count: number, total: number, ...}
       Text: {agent.output}"
  → LLM call with responseFormat = dataset schema → JSON Schema
  → 返回 structured JSON → Map<String, Object>
  → insert DatasetRecord
```

**Extraction prompt 模板：**

```
Extract the following structured data from the agent's output below.

Schema fields:
- positive_count (number): 好评数
- total (number): 总评论数
- summary (string): 摘要

Rules:
- Extract only values explicitly stated in the text. Do not infer or fabricate data.
- If a field is not present in the text, set it to null.
- For numeric fields, coerce string representations to numbers (e.g., "42" → 42).
- For boolean fields, accept true/false or yes/no.
- Return a valid JSON object matching the schema. Do not include extra fields.

Agent output:
---
{agent.output}
---

Return JSON:
```

LLM 参数：`temperature=0`（确定性输出），`responseFormat = schema JSON`（structured output / JSON mode）。

**注意：** extraction LLM call 不计入 agent 的 maxTurns，它是一次独立的、框架层面的调用。

### 3.3 AgentRunner 改动点

在 `AgentRunner.java` 中：

1. 注入 `DatasetService` 和 `DatasetRecordService`（新增）
2. 在 `run()` 方法的 async 回调中，`execute()` 返回后（仅当 run status == COMPLETED），调用 `extractDatasetRecords()`

```java
// AgentRunner.run()  async callback
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
    try {
        execute(runEntity, definition, sandbox, resolvedVariables);
        if (runEntity.status == RunStatus.COMPLETED
                && definition.outputDatasets != null && !definition.outputDatasets.isEmpty()) {
            extractDatasetRecords(runEntity, definition);
        }
    } finally {
        scheduleSandboxRelease(runId);
    }
}, executorService);
```

```java
// AgentRunner.java 新增字段和方法
@Inject DatasetService datasetService;
@Inject DatasetRecordService datasetRecordService;

private void extractDatasetRecords(AgentRun runEntity, AgentDefinition definition) {
    for (var binding : definition.outputDatasets) {
        try {
            var dataset = datasetService.get(binding.datasetId);
            if (dataset == null) {
                LOGGER.warn("dataset not found, datasetId={}, runId={}", binding.datasetId, runEntity.id);
                continue;
            }
            var extracted = llmCallExecutor.extractStructured(runEntity.output, dataset, definition);
            datasetRecordService.insert(dataset.id, definition.id, runEntity.id,
                runEntity.startedAt, extracted);
            LOGGER.info("dataset record inserted, datasetId={}, runId={}", dataset.id, runEntity.id);
        } catch (Exception e) {
            LOGGER.error("failed to extract dataset record, datasetId={}, runId={}", binding.datasetId, runEntity.id, e);
            // extraction failure 不影响 agent run 状态，run 本身已经是 COMPLETED
        }
    }
}
```

**设计决策：extraction 失败不影响 run 状态。** 因为：
- Run 本身已经成功完成，output 已持久化
- Extraction 是增值行为，失败不应推翻 run 的结果
- 错误通过日志暴露，后续可以补录

---

## 4. API 设计

### 4.1 Dataset API

路由前缀 `/api/datasets`。

**权限模型：** 所有 Dataset 操作限定在当前用户范围。创建时 `user_id` 取自当前登录用户；查询/更新/删除时校验 `user_id` 匹配。Agent 只能绑定当前用户拥有的 Dataset。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/datasets` | 创建 Dataset |
| GET | `/api/datasets` | 列出当前用户的 Dataset |
| GET | `/api/datasets/:id` | 获取详情（校验 user_id） |
| PUT | `/api/datasets/:id` | 更新（name, description, schema；校验 user_id） |
| DELETE | `/api/datasets/:id` | 删除（级联删除 records；校验 user_id） |

**创建请求：**

```json
{
  "name": "Review Stats",
  "description": "Daily review sentiment statistics",
  "schema": [
    {"name": "positive_count", "type": "NUMBER", "label": "好评数"},
    {"name": "total", "type": "NUMBER", "label": "总数"}
  ]
}
```

### 4.2 DatasetRecord API

路由前缀 `/api/datasets/:id/records`。

**权限模型：** 查询时校验 dataset 的 `user_id` 与当前用户匹配。`query_dataset_records` tool 同样受此约束。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/datasets/:id/records?from=&to=&fields=&limit=&offset=` | 时间序列查询 |

**查询参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| from | ISO 8601 | 起始时间 |
| to | ISO 8601 | 结束时间 |
| fields | String | 逗号分隔的字段名，如 `positive_count,total` |
| limit | Integer | 最大返回条数，默认 100，最大 1000 |
| offset | Integer | 分页偏移量，默认 0 |
| agent_id | String | 按产出 agent 过滤 |

**响应：**

```json
{
  "records": [
    {
      "id": "uuid",
      "run_id": "uuid",
      "agent_id": "agent-123",
      "run_started_at": "2026-05-27T08:00:00Z",
      "data": {"positive_count": 42, "total": 100, "positive_rate": 0.42}
    }
  ],
  "total": 365
}
```

MongoDB 查询实现：

```java
var filter = Filters.and(
    Filters.eq("dataset_id", datasetId),
    Filters.gte("run_started_at", from),
    Filters.lte("run_started_at", to)
);
// fields projection: 只返回 data 中的指定字段
var projection = new BsonDocument();
if (fields != null) {
    for (var field : fields) {
        projection.append("data." + field, new BsonInt32(1));
    }
}
```

### 4.3 Report API

路由前缀 `/api/reports`。

**权限模型：** 所有 Report 操作限定在当前用户范围。`/data` 端点校验 report 和关联 dataset 的 `user_id` 均匹配。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/reports` | 创建 Report |
| GET | `/api/reports` | 列出当前用户的 Report |
| GET | `/api/reports/:id` | 获取详情 |
| PUT | `/api/reports/:id` | 更新 |
| DELETE | `/api/reports/:id` | 删除 |
| GET | `/api/reports/:id/data?from=&to=` | 执行报表查询，返回渲染数据 |

**Report Data API 响应（按 type 不同）：**

METRIC 类型：
```json
{
  "type": "METRIC",
  "value": 42,
  "label": "好评数",
  "trend": "+5%",
  "updated_at": "2026-05-27T08:00:00Z"
}
```

> **注意：** `trend` 字段需要对比上一个等长周期（例如当前 7 天 vs 前 7 天），计算逻辑较复杂，Phase 1 先不实现（trend 返回 null），推迟到 Phase 4。

LINE_CHART 类型：
```json
{
  "type": "LINE_CHART",
  "series": [
    {"label": "5/20", "positive_count": 38, "total": 100},
    {"label": "5/21", "positive_count": 42, "total": 102}
  ]
}
```

Report 执行逻辑在 `ReportService` 中，负责：
1. 读取 `report.config` 确定查询参数
2. 调用 `DatasetRecordService.query()` 获取原始数据
3. 按 `ReportType` 做聚合/格式化
4. 返回前端可直接渲染的结构

---

## 5. Agent Tool 扩展

### 5.1 query_dataset_records Tool

允许 agent（通常为报表 agent）查询 dataset 的时间序列数据。

| 属性 | 值 |
|------|------|
| 名称 | `query_dataset_records` |
| 类型 | BUILTIN |
| 可见性 | llm_visible=true, discoverable=true |

**参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| dataset_id | String | 是 | |
| fields | String | 否 | 逗号分隔字段名 |
| from | String | 否 | ISO 8601 |
| to | String | 否 | ISO 8601 |
| limit | Integer | 否 | 默认 50 |

**返回：** `{records: [...], total: N}`

### 5.2 create_report Tool（可选 V2）

让 agent 直接创建 Report 定义。

| 属性 | 值 |
|------|------|
| 名称 | `create_report` |
| 类型 | BUILTIN |

**参数：** `dataset_id`, `name`, `type`, `config` (JSON)

用于实现「用户说一句话，agent 帮忙建报表」的场景。

---

## 6. 数据模型总结

### 6.1 新增 MongoDB 集合

| 集合 | 说明 | 预估规模 |
|------|------|----------|
| `datasets` | Dataset 定义 | 数十条 |
| `dataset_records` | 时间序列数据行 | 每 agent 每天一条，规模化后数千~数十万 |
| `reports` | Report 定义 | 数十条 |

### 6.2 新增 Java 类清单

**core-ai-server 模块（`ai.core.server.*`）：**

| 包路径 | 类 | 说明 |
|--------|------|------|
| `domain` | `Dataset.java` | @Collection("datasets")，内嵌 `SchemaField` |
| `domain` | `DatasetRecord.java` | @Collection("dataset_records") |
| `domain` | `SchemaField.java` | Dataset schema 字段定义（可内嵌于 Dataset） |
| `domain` | `Report.java` | @Collection("reports") |
| `domain` | `ReportType.java` | Enum：METRIC / TABLE / LINE_CHART / BAR_CHART / SUMMARY |
| `domain` | `ReportConfig.java` | 内嵌类 |
| `domain` | `AggregationType.java` | Enum：LAST / SUM / AVG / COUNT |
| `domain` | `OutputDatasetBinding.java` | 内嵌于 AgentDefinition / AgentPublishedConfig |
| `service` | `DatasetService.java` | Dataset CRUD |
| `service` | `DatasetRecordService.java` | insert + query（含 user_id 校验） |
| `service` | `ReportService.java` | Report CRUD + execute（含 user_id 校验） |
| `run` | `LLMCallExecutor.java` | 新增 extractStructured() |
| `run` | `AgentRunner.java` | 新增 extractDatasetRecords() |
| `tool` | `QueryDatasetRecordsTool.java` | BUILTIN tool：query_dataset_records |
| `tool` | `CreateReportTool.java` | BUILTIN tool：create_report |
| `web` | `DatasetWebServiceImpl.java` | REST API 实现 |
| `web` | `ReportWebServiceImpl.java` | REST API 实现 |

**core-ai-api 模块（`ai.core.api.server.*`）：**

| 包路径 | 类 | 说明 |
|--------|------|------|
| `api.server.dataset` | `DatasetWebService.java` | WebService 接口定义（`@POST @Path(...)`） |
| `api.server.dataset` | `ReportWebService.java` | WebService 接口定义 |
| `api.server.dataset` | `CreateDatasetRequest/Response` 等 | 请求/响应 DTO |

> **注意：** 遵循现有项目规范：接口 + DTO 在 `core-ai-api` 模块，实现在 `core-ai-server` 模块。参考 `AgentRunWebService`（api） + `AgentRunWebServiceImpl`（server）模式。

### 6.3 修改现有文件

| 文件 | 改动 |
|------|------|
| `AgentDefinition.java` | +outputDatasets 字段 |
| `AgentPublishedConfig.java` | +outputDatasets 字段 |
| `AgentRunner.java` | 注入 DatasetService + DatasetRecordService，在 run() async 回调中调用 extractDatasetRecords() |
| `LLMCallExecutor.java` | +extractStructured() |
| `AgentDefinitionService.java` | handle outputDatasets in create/update/publish |
| `ServerModule.java` | bind DatasetService / DatasetRecordService / ReportService；api().service(...) 注册 DatasetWebService / ReportWebService；registerStaticFiles() 中新增 SPA 路由 `/datasets`、`/datasets/new`、`/datasets/:id`、`/reports`、`/reports/new`、`/reports/:id` |

---

## 7. 前端页面规划

### 7.1 Dataset 管理页

- 路径：`/datasets`
- 列表页：展示当前用户所有 Dataset，支持创建/编辑/删除
- 编辑页：定义 name、description、schema 字段（动态添加/删除行，每行选 name + type + label）

### 7.2 Agent 编辑页改动

- 在 AgentEditor 现有表单中新增「Output Dataset」区域
- 下拉选择框，列出当前用户的所有 Dataset
- 支持多选（一个 agent 可产出到多个 Dataset）
- 保存时随 `output_datasets` 一起提交

### 7.3 Report 管理页

- 路径：`/reports`
- 创建 Report：选择 Dataset → 选择 type → 配置 valueFields / timeField → 预览
- 列表页：展示已有 Report，支持编辑/删除
- Agent 辅助创建：聊天中输入「帮我建一个 report 展示最近 7 天好评率」

### 7.4 Dashboard 改动

- 在 Dashboard 页面底部新增「Reports」区域
- 循环渲染用户的所有 Report，按 `sort_order` 排序
- METRIC → MetricCard，LINE_CHART/BAR_CHART → Recharts，TABLE → 表格
- 每个 Report 卡片右上角有「编辑」「删除」操作按钮

### 7.5 路由规划

```
/datasets            → DatasetList.tsx
/datasets/new        → DatasetEditor.tsx  (create mode)
/datasets/:id        → DatasetEditor.tsx  (edit mode)
/reports             → ReportList.tsx
/reports/new         → ReportEditor.tsx   (create mode)
/reports/:id         → ReportEditor.tsx   (edit mode)
/dashboard            → Dashboard.tsx      (已有，新增 Reports 区域)
/agents/:id           → AgentEditor.tsx    (已有，新增 Output Dataset 选项)
```

> **服务端变更：** 以上路由需同步添加到 `ServerModule.registerStaticFiles()` 的 SPA 路由数组中，否则刷新页面会 404。

---

## 8. 设计决策记录

### 8.1 为什么 DatasetRecord 是独立集合而非嵌入 Dataset？

- Agent 每天跑一次，一年 365 条记录。如果嵌入，Dataset 文档会持续膨胀
- MongoDB 单文档 16MB 上限，嵌入不适用于时间序列数据
- 独立集合可以建 `(datasetId, runStartedAt)` 复合索引，查询高效

### 8.2 为什么 extraction 失败不标记 run 为失败？

- Run 本身已成功完成，output 已持久化，用户仍可查看
- Extraction 是增值行为，失败不应该推翻 run 的成功状态
- 如果 extraction 失败导致数据缺失，用户可以通过「re-extract」手动补录

### 8.3 为什么不用 agent 手动调用 tool 来写数据？

用户原始设计中明确提到「框架自动完成」。手动调用 tool 的方式：
- 依赖 agent 记得调用，容易遗漏
- 依赖 prompt engineering 质量，不可靠
- 增加 agent 的 maxTurns 消耗

框架自动 extraction 更可靠，且对 agent 透明。

### 8.4 为什么 extraction 使用独立的 LLM call 而非在 agent 循环内完成？

- Agent 的 maxTurns 应专注于完成任务，不应消耗在格式化输出上
- 独立 LLM call 可以设置 `temperature=0` 保证确定性
- 独立 LLM call 可以使用更便宜/更快的模型（如 gpt-4o-mini）
- 失败隔离：extraction 失败不影响 agent 本身的执行结果

### 8.5 为什么 DatasetRecord 使用 `(dataset_id, run_id)` 唯一索引？

- 同一 run 不可能对同一 dataset 产出两条记录，建唯一索引防止数据重复
- 若未来需要 re-extract 功能，可以直接 upsert 而非追加，避免同一 run 产生多条记录

### 8.6 为什么 METRIC trend 延迟到 Phase 4？

- Trend 计算需要对比两个等长时间窗口（如当前 7 天 vs 前 7 天），涉及二次查询和聚合
- Phase 1-2 先保证核心数据管道的正确性，trend 作为增强功能后续迭代
- API 响应中 `trend` 字段保留，Phase 1-2 返回 `null`

---

## 9. 实现阶段

### Phase 1：核心数据管道（后端）

1. Dataset 领域模型 + CRUD API
2. DatasetRecord 领域模型 + insert/query
3. AgentRunner extraction 集成
4. LLMCallExecutor.extractStructured()
5. AgentDefinition + outputDatasets 字段

### Phase 2：Report + 展示（后端 + 前端）

1. Report 领域模型 + CRUD API
2. Report 数据执行 API（/reports/:id/data）
3. Dashboard Reports 区域渲染
4. Dataset 管理页面
5. Agent Editor output dataset 选择

### Phase 3：Agent 工具 + 自服务（增强）

1. query_dataset_records Tool
2. create_report Tool
3. Report 管理页面（CRUD）
4. Agent 辅助建 Report 的对话体验

### Phase 4：生产增强

1. Extraction 使用专用小模型（降低成本，模型可配置）
2. Re-extract 手动补录功能
3. Dataset schema 变更时的数据迁移策略
4. METRIC trend 计算（当前周期 vs 前一周期对比）
5. Report 分享/导出
