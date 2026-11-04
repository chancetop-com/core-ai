# Dataset on PostgreSQL — PoC 设计

> 状态：已实现（2026-11-02），结果与偏差见 [poc-dataset-postgresql-result.md](poc-dataset-postgresql-result.md)
> 关联分析：core-ai-server 存储层 Mongo + Redis → PostgreSQL 评估（结论：dataset 是迁 PG 的第一批候选）
> 现有设计：[design-dataset-report.md](design-dataset-report.md)

## 1. 背景

core-ng 的实体不允许 `Map<String, Object>`，所以 `DatasetRecord.data` 只能存成 JSON 字符串。这导致 `DatasetRecordService` 有以下问题：

| 问题 | 位置 | 后果 |
|---|---|---|
| 数据过滤在内存里做 | `DatasetRecordService.query` → `matches()` | 先扫最多 `MAX_FILTER_SCAN_RECORDS = 10_000` 条再逐条匹配；超过就返回 `truncated=true`，`total` 也不准 |
| 不能按 data 字段排序或聚合 | 同上 | 报表的 SUM/AVG/按天分组只能拉到内存里算 |
| `Dataset.schema` 只用来展示 | `SchemaField(NUMBER/STRING/BOOLEAN)` | 存储层不建索引，也不按类型比较 |
| 先读、内存合并、再整条写回 | `update`、`patchBySession` | 并发 patch 会丢更新 |
| session state 的 upsert 靠捕获重复键错误重试 | `upsertBySession` 捕获 `MongoWriteException 11000` | 逻辑绕 |

PoC 的目标：**在 core-ng 的约束下，用 PG 的 jsonb 把上面这些问题解决掉，并验证"每个 dataset 一张视图，让 agent 直接写 SQL"是否可行。**

**对照组：** 上面这些问题的根源是 core-ng 的实体映射层（data 被存成了 BSON 字符串），而不是 MongoDB 本身。所以 PoC 同时实现一个**绕过 core-ng 实体映射、直接用 MongoDB 原生 driver 的 store**（data 存为 BSON 子文档），和 PG 版本跑同一套契约测试和基准测试（见第 13 节），用数据回答下面这个问题：

> 过滤、排序、聚合、并发安全这几项，Mongo 改用原生 driver 能否和 PG 打平？PG 真正独有的价值（SQL、带类型的视图、RLS）值不值得为它引入一个新组件？

## 2. 要验证的问题与通过标准

| # | 问题 | 通过标准 |
|---|---|---|
| Q1 | core-ng 9.4.2 的 `db()` 能否正常连 PG：jsonb 读写、`ZonedDateTime` 与 `timestamptz` 互转、view 映射 | 第 6 节的 CRUD 全部跑通；时间往返后 instant 不变 |
| Q2 | Java 侧 `data` 保持 `String`，jsonb 能否通过 `?::jsonb` 写入、`data::text` 读出 | 不依赖 `Map<String, Object>` 实体，也不触发 core-ng 的 ClassValidator |
| Q3 | 现有过滤语义（点路径等值、数字按十进制精确比较、null 表示"缺失或为 null"）能否完全在 SQL 里实现 | 契约测试（第 8 节）对 Mongo 和 PG 两个实现都通过；PG 实现不再有 10k 扫描上限，`truncated` 恒为 false，`total` 精确 |
| Q4 | 扩展的比较操作符（`$gt/$gte/$lt/$lte/$ne/$in/$nin/$exists/$contains`）能否安全地翻译成 SQL | 所有值都走参数绑定，字段名也走参数（`text[]`）；遇到非数字值不报错，而是视为不匹配 |
| Q5 | 原子合并（RFC 7386）和 session state upsert | 50 个线程 × 每线程 100 次、各自 patch 不同字段，结束后所有字段都在（现在的 Mongo 实现会丢） |
| Q6 | 数据库里的聚合：按时间分桶 + 可选分类维度，支持 SUM/AVG/MIN/MAX/COUNT/LAST | 结果与内存里算出的基准结果一致；20 万行、30 天按天聚合，p95 < 300ms |
| Q7 | 根据 schema 为每个 dataset 生成视图，schema 变更时重建视图 | 加字段、删字段、改类型、字段名带特殊字符时都正确，注入用例无效 |
| Q8 | agent 能否安全地对视图执行只读 SQL（可选，第三阶段） | 只能看到被授权的 dataset；越权、写入、`set_config` 绕过、超时等用例全部被拦住 |
| Q9 | 性能 | 见第 9 节的基准表，结果写进 PoC 报告 |
| Q10 | 对照组：Mongo 原生 driver 能否达到 Q3–Q6 的同等能力 | `MongoNativeDatasetRecordStore` 跑同一套契约测试和并发测试；所有语义差异逐条列出，并说明是否有可行的修正办法（第 13.3 节） |
| Q11 | 对照组（可选）：LLM 写 SQL 和写 aggregation pipeline，准确率差多少 | 同一个模型、同一组 20 个分析问题，分别生成"针对视图的 SQL"和"Mongo aggregation pipeline"，统计执行成功率和结果正确率（第 13.5 节） |

## 3. 范围

**在范围内：**
- `dataset_records` 的存储换成 PG，用开关切换，默认仍是 Mongo
- 生成每个 dataset 的视图
- 新增聚合接口（只在 service 层，不改前端）
- 可选的只读 SQL 执行器（第三阶段）
- 契约测试、并发测试、基准测试，以及 PoC 报告
- 对照组：基于 Mongo 原生 driver 的 store（第 13 节），以及可选的 LLM 查询准确率实验

**不在范围内：**
- `datasets` 元数据表：继续留在 Mongo，PG 侧只读取它的 schema
- Mongo 存量数据迁移：基准测试用合成数据
- Redis 相关的任何改动
- 前端、报表 UI
- 生产环境配置与部署
- 修改 agent 工具（`QueryDatasetRecordsTool` 等）的入参和出参结构。唯一例外：Q4 的操作符可以写进工具描述，入参格式不变，仍是 JSON filter

## 4. 环境与依赖

### 4.1 本地 PG

在 `docker-compose.local.yml` 中增加服务（PG ≥ 15，因为第 7.3 节要用 `security_invoker` 视图）：

```yaml
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: core_ai
      POSTGRES_USER: core_ai
      POSTGRES_PASSWORD: core_ai
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
```

同时在 `volumes:` 里加上 `postgres-data:`。

### 4.2 依赖

- `buildSrc/src/main/kotlin/Versions.kt` 新增 `const val POSTGRESQL_JDBC_VERSION = "42.7.4"`
- `build.gradle.kts` 的 `project(":core-ai-server")` 中新增 `runtimeOnly("org.postgresql:postgresql:${Versions.POSTGRESQL_JDBC_VERSION}")`
- core-ng 9.4.2 本身已经支持 PG（jar 里有 `jdbc:postgresql:` 方言识别和 `PostgreSQLQueryAnalyzer`），不需要额外的 core-ng 模块

### 4.3 配置（`sys.properties`）

```properties
# mongo | mongo-native | postgresql, default mongo
sys.dataset.store=mongo
# mongo-native only: collection used by the native-driver store, separate from the core-ng mapped dataset_records
sys.dataset.mongoNativeCollection=dataset_records_v2
sys.db.url=jdbc:postgresql://localhost:5432/core_ai
sys.db.user=core_ai
sys.db.password=core_ai
```

只有当 `sys.dataset.store=postgresql` 时才调用 `db()`，保证默认配置下服务不依赖 PG。

## 5. 表结构

PoC 阶段由 `DatasetPgSchemaInitializer` 在 `onStartup` 时执行幂等 DDL（`CREATE … IF NOT EXISTS` / `CREATE OR REPLACE`）。转为生产方案时再改成 flyway：`buildSrc` 里已经有 `db-migration.gradle.kts`，PoC 不需要接入。

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS dataset_records (
    id             text        PRIMARY KEY,          -- 保持现有 UUID 字符串
    dataset_id     text        NOT NULL,
    agent_id       text        NOT NULL,
    run_id         text,
    session_id     text,
    user_id        text,
    data           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    run_started_at timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    created_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL,
    updated_by     text        NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_time
    ON dataset_records (dataset_id, run_started_at DESC);
CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_agent_time
    ON dataset_records (dataset_id, agent_id, run_started_at DESC);
CREATE INDEX IF NOT EXISTS ix_dataset_records_user
    ON dataset_records (user_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_dataset_records_session
    ON dataset_records (dataset_id, session_id) WHERE session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_dataset_records_data
    ON dataset_records USING gin (data jsonb_path_ops);

CREATE TABLE IF NOT EXISTS dataset_views (
    dataset_id  text        PRIMARY KEY,
    view_name   text        NOT NULL UNIQUE,
    column_map  jsonb       NOT NULL,   -- {"<schema field name>": "<view column name>"}
    schema_hash text        NOT NULL,
    updated_at  timestamptz NOT NULL
);
```

### 5.1 RFC 7386 合并函数

```sql
CREATE OR REPLACE FUNCTION jsonb_merge_patch(target jsonb, patch jsonb)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    result jsonb;
    k text;
    v jsonb;
BEGIN
    IF patch IS NULL OR jsonb_typeof(patch) <> 'object' THEN
        RETURN patch;
    END IF;
    IF target IS NULL OR jsonb_typeof(target) <> 'object' THEN
        result := '{}'::jsonb;
    ELSE
        result := target;
    END IF;
    FOR k, v IN SELECT key, value FROM jsonb_each(patch) LOOP
        IF jsonb_typeof(v) = 'null' THEN
            result := result - k;
        ELSIF jsonb_typeof(v) = 'object' THEN
            result := jsonb_set(result, ARRAY[k], jsonb_merge_patch(result -> k, v));
        ELSE
            result := jsonb_set(result, ARRAY[k], v);
        END IF;
    END LOOP;
    RETURN result;
END $$;
```

**与现有 Java `merge()` 的差异（需要在 PoC 报告中写明）：** 当 patch 的值是对象、而原值不是对象时，Java 会把 patch 子对象原样写入，**其中的 null 字段也会保留**；SQL 版本按 RFC 7386 处理，会把这些 null 字段删掉。`patchBySession` 的 javadoc 声明的是 RFC 7386 语义，所以以 SQL 版本为准，并在契约测试里单独覆盖这个用例。

## 6. 代码结构

```
ai.core.server.dataset
├── DatasetRecordService            // 保留公共 API 和静态工具（parseFilter/parseData/MAX_*/merge），内部委托给 store
├── DatasetService                  // delete() 改为调用 store.deleteByDataset；create/update 后调用 viewManager.sync
└── store
    ├── DatasetRecordStore          // 接口
    ├── MongoDatasetRecordStore     // 把现有 Mongo 逻辑原样搬过来
    ├── MongoNativeDatasetRecordStore   // 对照组：原生 driver，data 存为 BSON 子文档（第 13 节）
    ├── MongoNativeFilter           // 对照组：JSON filter → Bson
    ├── MongoNativeAggregation      // 对照组：聚合请求 → aggregation pipeline
    ├── PostgresDatasetRecordStore
    ├── DatasetFilterSql            // JSON filter → SQL 片段 + 参数
    ├── DatasetAggregationSql       // 聚合请求 → SQL + 参数
    ├── DatasetViewManager          // 生成、重建、删除视图（第二阶段）
    ├── DatasetSqlExecutor          // 只读 SQL 执行器（第三阶段，可选）
    ├── DatasetPgSchemaInitializer
    └── PgDatasetRecordRow          // core-ng view，用于 SELECT 结果映射
```

### 6.1 接口

```java
public interface DatasetRecordStore {
    void insert(DatasetRecord record);
    DatasetRecordService.QueryResult query(DatasetRecordService.QueryRequest request);
    boolean update(String datasetId, String id, Map<String, Object> patch, String updatedBy);
    boolean delete(String datasetId, String id);
    long deleteByDataset(String datasetId);
    Optional<DatasetRecord> findBySession(String datasetId, String sessionId);
    void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId);
    void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId);
    AggregateResult aggregate(AggregateRequest request);   // Mongo 实现可以抛 UnsupportedOperationException
}
```

调用方完全不改：`HubDatasetService`、`AgentRunBuilder`、`SelfHarnessDispatcher`、`DatasetWebServiceImpl`、各个 dataset 工具都继续调用 `DatasetRecordService`。

### 6.2 DatasetModule 装配

```java
var store = property("sys.dataset.store").orElse("mongo");
if ("postgresql".equals(store)) {
    db().url(requiredProperty("sys.db.url"));
    db().user(requiredProperty("sys.db.user"));
    db().password(requiredProperty("sys.db.password"));
    db().view(PgDatasetRecordRow.class);
    // + 聚合结果 view、count 等
    bind(DatasetRecordStore.class, bind(PostgresDatasetRecordStore.class));
    onStartup(bean(DatasetPgSchemaInitializer.class)::initialize);
} else if ("mongo-native".equals(store)) {
    var client = MongoClients.create(requiredProperty("sys.mongo.uri"));
    onShutdown(client::close);
    bind(DatasetRecordStore.class, bind(new MongoNativeDatasetRecordStore(client,
            property("sys.dataset.mongoNativeCollection").orElse("dataset_records_v2"))));
} else {
    bind(DatasetRecordStore.class, bind(MongoDatasetRecordStore.class));
}
bind(DatasetService.class);
bind(DatasetRecordService.class);
```

注意：core-ng 按精确类型解析依赖，所以接口必须显式 `bind(DatasetRecordStore.class, …)`（参考 `MessagingInfrastructureModule` 对 `ScheduledTaskStore` 的写法）；而且 store 必须在 `DatasetService` / `DatasetRecordService` 之前绑定。

### 6.3 读写 SQL（PostgresDatasetRecordStore）

- **读取：** 统一投影为 `SELECT id, dataset_id, agent_id, run_id, session_id, user_id, data::text AS data, run_started_at, created_at, created_by, updated_at, updated_by`，映射到 `PgDatasetRecordRow`（字段都加 `@Column`），再转换成 `DatasetRecord`。
- **插入：** 用 `database.execute("INSERT INTO dataset_records (...) VALUES (?, ?, …, ?::jsonb, …)", …)`，不使用 `Repository`，因为 jsonb 需要显式 cast，upsert 也需要 `ON CONFLICT`。
- **update（按 id）：** `UPDATE dataset_records SET data = jsonb_merge_patch(data, ?::jsonb), updated_at = ?, updated_by = ? WHERE id = ? AND dataset_id = ?`。返回影响行数，0 行即返回 false，保持"必须同时匹配 id 和 dataset_id"的安全约束。
- **upsertBySession：**
  ```sql
  INSERT INTO dataset_records (id, dataset_id, agent_id, session_id, user_id, data, run_started_at,
                               created_at, created_by, updated_at, updated_by)
  VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
  ON CONFLICT (dataset_id, session_id) WHERE session_id IS NOT NULL
  DO UPDATE SET data = EXCLUDED.data, run_started_at = EXCLUDED.run_started_at,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
  ```
- **patchBySession：** 和上面相同，只是把 `DO UPDATE SET data = jsonb_merge_patch(dataset_records.data, EXCLUDED.data)`。
- **大小限制：** `MAX_STATE_BYTES` 在 Java 侧检查。patch 之后的最终大小用 `RETURNING octet_length(data::text)` 检查，超限就抛异常、事务回滚。

### 6.4 过滤 → SQL（DatasetFilterSql）

**输入**仍然是 `Map<String, Object>`。键是点路径，翻译时转成参数 `?::text[]`（例如 `"a.b"` → `{a,b}`），字段名永远不拼进 SQL。

| filter 写法 | SQL |
|---|---|
| `"k": <标量>`（字符串、数字、布尔） | `data @> ?::jsonb`，参数是按路径构造的嵌套对象，比如 `{"a":{"b":3}}`，可以走 GIN 索引。jsonb 的数字比较本来就是 numeric 比较，`3` 和 `3.0` 相等，和现在 BigDecimal 的语义一致 |
| `"k": <对象或数组>`（不是操作符对象） | `data #> ?::text[] = ?::jsonb`，深度相等。不能用 `@>`，因为数组的包含关系是子集，不是相等 |
| `"k": null` | `(data #> ?::text[] IS NULL OR data #> ?::text[] = 'null'::jsonb)` |
| `"k": {"$gt": 2}` 等 | 见下 |

**操作符对象的判定：** 值是 Map，并且**所有**键都以 `$` 开头，才视为操作符对象；否则按深度相等处理，保持向后兼容。

| 操作符 | SQL（`p` 表示 `?::text[]`） |
|---|---|
| `$eq` / `$ne` | `data #> p = ?::jsonb` / `data #> p IS DISTINCT FROM ?::jsonb` |
| `$gt/$gte/$lt/$lte`（数字） | `(CASE WHEN jsonb_typeof(data #> p) = 'number' THEN (data #>> p)::numeric END) > ?` |
| `$gt/…`（字符串，按 ISO 时间或字典序比较） | `(CASE WHEN jsonb_typeof(data #> p) = 'string' THEN data #>> p END) > ?` |
| `$in` / `$nin` | `data #> p = ANY(?::jsonb[])`，`$nin` 用 `NOT (... )` 并包含 null 的情况 |
| `$exists` | `data #> p IS NOT NULL` / `IS NULL` |
| `$contains`（字符串，不区分大小写） | `data #>> p ILIKE ?`，参数是 `'%' + escapeLike(v) + '%'` |

- **类型判定：** 比较值是 `Number` 就走 numeric 分支，`String` 就走字符串分支。**必须用 `CASE WHEN jsonb_typeof(...)` 包一层**，否则遇到一条字段值不是数字的记录，cast 会报错，整个查询失败。
- **未知操作符：** 抛出 `IllegalArgumentException`，错误信息里列出支持的操作符。错误文案和现有 `parseFilter` 的风格保持一致，因为工具和 hub 共用这段文案。

### 6.5 query 的行为

- `WHERE dataset_id = ? [AND run_started_at >= ?] [AND run_started_at <= ?] [AND agent_id = ?] [AND <filter>]`，`ORDER BY run_started_at DESC, id`，`LIMIT ? OFFSET ?`
- `total` 用同样的 WHERE 条件执行 `SELECT count(*)`；`truncated` 恒为 false
- `fields` 裁剪继续由 Java 的 `trimFields` 负责（它在分页之后执行，开销可以忽略），保证输出完全一致
- 可选：在 `QueryRequest` 末尾新增 `sortField` / `sortDesc`（字段在 schema 里是 NUMBER 时按 numeric 排序，否则按 text 排序）。不暴露给工具

### 6.6 聚合（DatasetAggregationSql）

```java
public record AggregateRequest(String datasetId, ZonedDateTime from, ZonedDateTime to, String agentId,
                               Map<String, Object> filter, String bucket /* hour|day|week|month|null */,
                               String timezone /* IANA, default UTC */, String categoryField,
                               List<Metric> metrics) { }
public record Metric(String field, String fn /* SUM|AVG|MIN|MAX|COUNT|LAST */) { }
public record AggregateResult(List<String> columns, List<List<Object>> rows) { }
```

SQL 骨架（分桶粒度、字段名、时区全部用参数绑定，GROUP BY 用列序号）：

```sql
SELECT date_trunc(?, run_started_at, ?)                       AS bucket,
       data #>> ?::text[]                                     AS category,
       sum(CASE WHEN jsonb_typeof(data #> ?::text[]) = 'number'
                THEN (data #>> ?::text[])::numeric END)       AS m0,
       (array_agg(data #> ?::text[] ORDER BY run_started_at DESC))[1]::text AS m1   -- LAST
FROM dataset_records
WHERE dataset_id = ? AND ...
GROUP BY 1, 2
ORDER BY 1, 2
```

- 结果映射：在外层用 `SELECT jsonb_build_array(bucket, category, m0, m1)::text AS row FROM (...) t`，把每一行变成一个 JSON 文本，映射到只有一个 `@Column String row` 字段的 view 上，在 Java 里再解析。这是在 core-ng 的固定 view 约束下，处理"列数不固定"的通用做法，第 7.3 节同样使用。
- 校验：SUM/AVG/MIN/MAX 只允许用在 schema 中类型为 NUMBER 的字段上；`bucket` 只允许白名单里的值；`categoryField` 必须在 schema 中。
- `bucket` 为 null 时不分桶，GROUP BY 只按 category（或者不分组）。

## 7. 动态表结构：每个 dataset 一张视图

### 7.1 第二阶段：DatasetViewManager

- **触发时机：** `DatasetService.create` / `update`（schema 有变化时）调用 `sync(dataset)`；`delete` 时调用 `drop(datasetId)`。启动时对 `dataset_views` 中缺失或 hash 不一致的 dataset 补做一次 sync。PoC 里可以只做一个手动触发的方法。
- **视图名：** `ds_` + dataset id 去掉 `-` 后转小写，长度小于 63。
- **列名生成：**
  1. 转小写，不在 `[a-z0-9_]` 范围内的字符替换成 `_`
  2. 以数字开头，或者与系统列（`id, agent_id, run_id, session_id, user_id, run_started_at, created_at, updated_at`）、SQL 保留字冲突时，加前缀 `f_`
  3. 截断到 60 个字符，重名的追加 `_2`、`_3`……
  4. 生成 DDL 时，标识符一律加双引号
  5. 映射关系写进 `dataset_views.column_map`
- **字段的 JSON key：** 作为 SQL 字面量出现时，单引号转义成 `''`；也可以用 `format('%L', key)` 在数据库侧生成。
- **类型映射：**

  | SchemaFieldType | 列表达式 |
  |---|---|
  | NUMBER | `CASE WHEN jsonb_typeof(data -> 'k') = 'number' THEN (data ->> 'k')::numeric END` |
  | BOOLEAN | `CASE WHEN jsonb_typeof(data -> 'k') = 'boolean' THEN (data ->> 'k')::boolean END` |
  | STRING | `CASE WHEN jsonb_typeof(data -> 'k') = 'string' THEN data ->> 'k' END`（非字符串值显示为 NULL） |

- **过滤走视图：** schema 里声明过的简单字段，过滤改为查对应的视图列（`WHERE "positive_count" > ?`），
  这样按视图同表达式建的索引才能命中。`null` / `$ne: null` / `$exists` 因为无法区分
  "缺失、显式 null、类型不匹配"，整体回退到 jsonb 路径；`$nin` 在视图路径显式写成
  `(col IS NULL OR NOT (col IN (...)))`。

- **重建：** 在一个事务里执行 `DROP VIEW IF EXISTS … ; CREATE VIEW … WITH (security_invoker = true) AS SELECT …`，然后 upsert `dataset_views`。PG 的 DDL 是事务性的，失败会整体回滚。不用 `CREATE OR REPLACE VIEW`，因为它不支持删列和改类型。
- **热点字段索引（可选）：** 给指定字段建表达式索引 `CREATE INDEX … ON dataset_records ((<和视图列完全相同的表达式>))`。
  实现时增加了 `DatasetViewManager.indexField(dataset, field)`：索引表达式与视图列共用同一个生成器，
  并把 schema 字段的过滤也改为查视图（`WHERE "positive_count" > ?`），
  这样索引才能真正命中。基准与 EXPLAIN 证据见结果报告第 5 节。

### 7.2 必须覆盖的视图用例

- 新增字段、删除字段、NUMBER 改成 STRING
- 字段名为 `Order Count`、`1st`、`id`、`select`、中文 `好评数`、以及恶意名 `a'b"c; DROP TABLE dataset_records; --`
- 两个字段规范化后重名，比如 `a-b` 和 `a_b`
- 删除 dataset 后视图和 `dataset_views` 记录都被清除

### 7.3 第三阶段（可选）：只读 SQL 执行器（验证 RLS）

> **实现时的修正（2026-11-02）：** 下面基于 `txid` + `SET LOCAL ROLE dataset_reader` 的方案**不安全**：
> `role` 是 USERSET 参数，agent 只要在 SQL 里执行 `SELECT set_config('role', 'none', true)` 就会恢复
> 会话用户（应用账号），RLS 立即失效。实际实现改为：`dataset_reader` 是独立 LOGIN 角色（连接即受限），
> 授权用随机 token（`dataset_sql_grants(token, dataset_id, created_at)`），
> policies 用 `current_setting('app.dataset_token', true)` 过滤，并对授权表本身也开 RLS。
> 详见结果报告第 3.5 节与 `DatasetSqlExecutor`。

目标：agent 可以写 `SELECT … FROM ds_xxx …`，但只能看到当前 session 绑定的 dataset。

```sql
CREATE ROLE dataset_reader NOLOGIN;
GRANT dataset_reader TO core_ai;                           -- 应用账号可以 SET ROLE 成它
CREATE TABLE IF NOT EXISTS dataset_sql_grants (
    txid       bigint NOT NULL,
    dataset_id text   NOT NULL,
    PRIMARY KEY (txid, dataset_id)
);
ALTER TABLE dataset_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY dataset_reader_scope ON dataset_records FOR SELECT TO dataset_reader
    USING (dataset_id IN (SELECT g.dataset_id FROM dataset_sql_grants g WHERE g.txid = txid_current()));
CREATE POLICY app_full_access ON dataset_records TO core_ai USING (true) WITH CHECK (true);
GRANT SELECT ON dataset_records TO dataset_reader;          -- 视图是 security_invoker，需要基表权限，RLS 负责过滤
GRANT SELECT ON dataset_sql_grants TO dataset_reader;       -- 只给读权限，不给写
-- 每个 ds_* 视图：GRANT SELECT ON ds_xxx TO dataset_reader
```

**执行流程**（`DatasetSqlExecutor.execute(sql, allowedDatasetIds)`，整个过程在同一个事务里）：

1. `INSERT INTO dataset_sql_grants SELECT txid_current(), unnest(?::text[])`（此时还是应用账号）
2. `SET LOCAL ROLE dataset_reader; SET LOCAL statement_timeout = '5s'`。这里不能用 `SET TRANSACTION READ ONLY`，因为它必须是事务里的第一条语句，而第 1 步已经执行过 INSERT。只读由两点保证：`dataset_reader` 只有 SELECT 权限，第 4 步又一定回滚。注意：本地 docker 里的 `core_ai` 是超级用户，但 `SET ROLE` 之后当前角色已经是非超级用户，RLS 照样生效。测试里要断言 `current_user = 'dataset_reader'`
3. `SELECT jsonb_agg(to_jsonb(t))::text FROM (<agent sql>) t LIMIT 1000`：外层包装可以拒绝多语句，并限制返回行数
4. 无论成功与否都回滚：grants 随事务一起消失，也保证没有任何写入

**为什么不用 `set_config('app.dataset_ids', …)` 加 RLS：** 任何角色都能调用 `set_config` 改写自定义 GUC，agent 可以在自己的 SQL 里自行"授权"。授权表的方案下 reader 没有写权限，也就无法伪造授权。

**必须覆盖的越权用例：**
- 查询未授权 dataset 的视图，以及直接查基表 `dataset_records`：都只返回空，或只返回已授权的行
- `SELECT set_config(...)`、`INSERT/UPDATE/DELETE`、`; DROP …`、`COPY`、`pg_sleep(10)`、`pg_read_file`、通过 `dblink` 外连：全部被拒绝或超时
- 查询 `dataset_sql_grants` 的其他 txid：只能看到授权表本身，不会泄露数据（可选：对授权表也加 RLS，限定 `txid = txid_current()`）

## 8. 测试

**位置：** `core-ai-server/src/test/java/ai/core/server/dataset/store/`

1. **契约测试：** 抽象类 `DatasetRecordStoreContractTest`，由三个类继承：`MongoDatasetRecordStoreTest`（mock collection，复用现有 `DatasetRecordServiceTest` 的用例）、`PostgresDatasetRecordStoreTest`（真实 PG）、`MongoNativeDatasetRecordStoreTest`（真实 Mongo，用 `@EnabledIfEnvironmentVariable(named = "CORE_AI_MONGO_TEST_URI", …)` 控制，每次测试用随机的 collection 名并在结束后删除）。至少覆盖：
   - 点路径等值、`3` 与 `3.0` 相等、`"3"` 与 `3` 不相等、`null` 匹配缺失字段、对象深度相等、数组相等（不是包含）
   - 时间范围、agentId、分页与 total、fields 裁剪
   - update 和 delete 时 dataset_id 不匹配必须返回 false
   - session state 的 upsert、patch（含第 5.1 节的差异用例）、MAX_STATE_BYTES
2. **PG 测试开关：** 用 `@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")`，没有 PG 环境时自动跳过，不影响 `./gradlew test`。仓库当前没有用 Testcontainers，PoC 不引入。
3. **并发测试（Q5）：** 50 个线程 × 每线程 100 次，分别 patch 同一个 session state 的 `f_{thread}_{i}` 字段，结束后断言字段数为 5000。PG 和 mongo-native 两个实现都必须通过。同一份测试对现有 Mongo 实现也跑一次并记录丢失数量，作为对照写进报告（不需要断言）。
4. **过滤翻译单测：** `DatasetFilterSql` 生成的 SQL 中不允许出现任何来自用户输入的字面量，断言参数列表符合预期。
5. **视图和执行器测试：** 覆盖第 7.2 节和第 7.3 节的用例清单。

## 9. 基准测试

写一个 `DatasetPgBenchmark`（`main` 方法或带 `@Tag("benchmark")` 的测试，默认不运行）。

**数据：** 100 个 dataset、共 100 万条记录。其中热点 dataset 有 20 万条，时间均匀分布在 90 天内。schema 为 `positive_count:NUMBER, negative_count:NUMBER, total:NUMBER, status:STRING, region:STRING, flagged:BOOLEAN`。

| 场景 | Mongo 现状 | Mongo 原生 driver | PG | 备注 |
|---|---|---|---|---|
| 热点 dataset、等值过滤 `status=done`、limit 100 | | | | 现状有 10k 扫描上限，记录 truncated |
| 范围过滤 `positive_count > 50`，无索引 / 有索引 | 不支持 | | | Mongo 原生用 `(dataset_id, data.positive_count)` 复合索引；PG 用部分表达式索引 |
| 30 天按天聚合，计算 SUM(total)、AVG(positive_count) | 不支持（需要应用层自己算） | | | 目标 p95 < 300ms |
| 按 region 分类 + 按天聚合 | 不支持 | | | |
| 视图查询 `SELECT region, sum(total) FROM ds_x GROUP BY 1` | 不支持 | 用等价 pipeline 测 | | |
| 50 线程并发 patch，统计丢失字段数 | | 0 | 0 | 原生 driver 另外记录 CAS 重试次数（第 13.2 节） |
| 单条 insert、patch 的 p50/p95 | | | | |
| 100 万条数据的存储大小（数据 + 索引） | | | | |

每个场景预热后跑 200 次，记录 p50/p95/p99。关键 SQL 附上 `EXPLAIN (ANALYZE, BUFFERS)`。

## 10. 交付物

1. 带开关的代码（默认 `mongo`，现有行为零变化），`./gradlew :core-ai-server:build` 通过（含 checkstyle、spotbugs）
2. 第 8 节的测试；PG 测试在本地 `docker compose -f docker-compose.local.yml up postgres` 后可以运行
3. PoC 报告 `docs/cn/poc-dataset-postgresql-result.md`，内容包括：
   - Q1–Q9 逐条的结论（通过 / 不通过 / 有条件通过，附证据）
   - 第 9 节的基准表
   - 遇到的 core-ng 限制及绕过方式，例如 jsonb 参数绑定、`ZonedDateTime` 映射、core-ng 对 SQL 或返回行数的告警
   - 与现有 Mongo 实现的语义差异清单（PG、mongo-native 各一份）
   - **三方对比与选型建议：** 按"能力 / 语义一致性 / 性能 / 实现复杂度 / 运维成本 / 独有能力"列一张表，最后给出明确结论：
     - 选 mongo-native：只解决查询问题，不引入 PG
     - 选 PG：理由需要落到 Q7、Q8、Q11 的结果上
     - 或者"现阶段 mongo-native，等存储统一时再迁 PG"
   - 转为生产方案时的工作清单（只写选中的方案）：PG 需要 flyway、Mongo 存量数据迁移、StartupDependencyCheck 加入 PG 探测、监控；mongo-native 需要把 `dataset_records` 的旧数据从字符串转成子文档

## 11. 实现约束（摘自 AGENTS.md 和项目约定）

- 注释只写英文，并且只写类级 javadoc；新文件的 `@author stephen`；新建的 `.java` 文件要 `git add`
- 一律 import 类，不在代码里写全限定名（现有的 `DatasetService` 里有 `ai.core.server.domain.SchemaField` 全限定写法，改到时顺手修正）
- checkstyle 限制单个文件 450 行。`PostgresDatasetRecordStore` 如果超出，就把 SQL 构建拆到 `DatasetFilterSql` / `DatasetAggregationSql` 里
- core-ng 的 view 类不能有 `Object` 或 `Map<String, Object>` 字段，所有动态内容都用 `String`（JSON 文本）传递
- 不修改工具和 hub 的输出结构（`DatasetOpPayloads`），保证 agent 和脚本无感知
- PoC 期间不改动 `MongoDatasetRecordStore` 的行为，它就是把现有逻辑原样搬出来

## 12. 建议的实现顺序

1. 第一阶段：依赖、配置、schema 初始化、`DatasetRecordStore` 抽取（Mongo 实现，现有测试全部通过）、PG 的 CRUD、session state、过滤翻译、契约测试和并发测试 → 回答 Q1–Q5
2. 第一阶段同步进行（可以并行）：`MongoNativeDatasetRecordStore`，跑同一套契约测试和并发测试 → 回答 Q10
3. 第二阶段：PG 和 mongo-native 的聚合、PG 视图管理、三方基准测试 → 回答 Q6、Q7、Q9
4. 第三阶段（可选）：只读 SQL 执行器和越权测试 → 回答 Q8；LLM 查询准确率实验 → 回答 Q11
5. 编写 PoC 报告（含三方对比和选型建议）

## 13. 对照组：Mongo 原生 driver

### 13.1 存储

- **单独的 collection：** 使用 `dataset_records_v2`，不能和现有的 `dataset_records` 共用。原因是 core-ng 映射的 `DatasetRecord.data` 是 `String`，一旦读到子文档就会映射失败。
- **自建 client：** core-ng 的 `Mongo` 接口不暴露原生 `MongoDatabase`（`MongoImpl` 里是 private 字段），所以用 `MongoClients.create(sys.mongo.uri)` 单独建一个 client。driver（`mongodb-driver-sync` 5.5.1）已经在 classpath 上，不需要新增依赖。代价是多一个连接池，报告里要写明。
- **文档结构：** 字段和 PG 表一一对应。`data` 用 `Document.parse(json)` 写入成子文档，读出时 `document.get("data", Document.class).toJson()` 再交给 `DatasetRecord.data`。另外增加一个 `version: Long` 字段，供第 13.2 节的 CAS 使用。
- **索引：**
  - `(dataset_id, run_started_at desc)`
  - `(dataset_id, agent_id, run_started_at desc)`
  - `user_id`
  - `(dataset_id, session_id)`，唯一索引，带 partialFilterExpression `session_id` 存在
  - 基准测试的热点字段：`(dataset_id, data.positive_count)`
  - Mongo 没有 GIN 那样的通用 JSON 索引；wildcard 索引 `data.$**` 可以作为一个可选场景测一次

### 13.2 写入

- **update / patchBySession：** 优先把 RFC 7386 patch 翻译成 `$set` / `$unset` 点路径（例如 `{"a":{"b":1,"c":null}}` → `$set data.a.b`、`$unset data.a.c`），一次 `updateOne` 完成，不需要先读。
  - **回退：** 遇到路径冲突（原值不是对象，Mongo 报错码 28 `PathNotViable`），或者 patch 里有空对象、需要整体替换时，改用 CAS：读出文档 → 用现有的 Java `merge()` 合并 → `replaceOne({_id, version: v}, … version: v + 1)`，失败最多重试 10 次。
  - 报告里记录两条路径各自的触发次数，以及 CAS 重试次数的分布。
- **upsertBySession：** `updateOne(filter, {$set: {data, run_started_at, updated_*}, $setOnInsert: {_id, agent_id, created_*, version: 0}}, upsert(true))`。Mongo 4.2 以后，服务端会对"按唯一索引等值匹配的 upsert"自动重试；PoC 中仍然捕获 11000 错误并重试一次，同时记录发生的次数。
- **字段名校验：** data 里任意层级的 key 以 `$` 开头或者包含 `.`，都要拒绝。PG 方案不受这个限制，作为语义差异记录下来。

### 13.3 过滤：已知的语义陷阱（契约测试必须覆盖）

| 现有语义（Java `valueEquals`） | Mongo 直接用 `Filters.eq("data." + path, v)` 的行为 | 修正办法 |
|---|---|---|
| 标量期望值不匹配数组（`"a"` ≠ `["a","b"]`） | 字段是数组且**包含**该元素时就会匹配 | 再加一个条件 `{path: {$not: {$type: "array"}}}` |
| 对象相等与 key 的顺序无关 | 子文档相等是**按字段顺序**比较的，`{a:1,b:2}` ≠ `{b:2,a:1}` | 对对象期望值，改用 `$expr` 配合 `$setEquals(objectToArray…)` 递归比较，或者展开成逐个叶子字段的等值条件再加字段数检查；这个场景走不了索引 |
| 数组期望值要求完全相等 | 还会匹配"外层数组中有一个元素等于该数组"的情况 | 加 `$size`，或者改用 `$expr: {$eq: [...]}` |
| `null` 匹配"缺失或为 null" | `{path: null}` 的行为相同 | 不需要修正 |
| `3` 等于 `3.0` | int/long/double 跨类型按数值比较 | 不需要修正 |
| `"3"` 不等于 `3` | 不同类型不相等 | 不需要修正 |

**操作符：** `$gt/$gte/$lt/$lte/$ne/$in/$nin/$exists` 都直接对应 Mongo 原生操作符。Mongo 的比较本身就区分类型，数字条件不会匹配字符串，所以不需要 PG 里那层 `CASE WHEN`。`$contains` 用 `Filters.regex(path, Pattern.quote(v), "i")`，走不了索引。

**路径：** 每一段都要校验，不能以 `$` 开头。这是 Mongo 方案的"注入面"，对应 PG 方案里字段名走参数绑定的做法。

### 13.4 聚合

pipeline：`$match` →（LAST 需要的话先 `$sort run_started_at`）→ `$group`：

- 分桶键：`_id: {bucket: {$dateTrunc: {date: "$run_started_at", unit, timezone}}, category: "$data.<field>"}`
- 指标：`$sum` / `$avg` / `$min` / `$max` 直接作用于 `$data.<field>`，`$sum` 和 `$avg` 会自动忽略非数字值；COUNT 用 `$sum: 1`；LAST 用 `$last`（前面已经排序），或者用 `$top`（需要 5.2+）

**版本要求：** `$dateTrunc` 需要 MongoDB 5.0+。本地 compose 用的是 `mongo:7`；**需要确认 UAT 和生产的 Mongo 版本**，结果写进报告。

### 13.5 （可选）LLM 查询准确率实验（Q11）

这一节衡量的是 PG 的核心独有价值："让 agent 直接写查询来分析数据"。

- **数据：** 用第 9 节基准测试的热点 dataset，按 schema 提供两种描述：一个 `ds_x` 视图的列定义，和 Mongo 文档结构（`data.*`）。
- **题目：** 20 个自然语言分析问题，难度分层：
  - 单条件过滤计数
  - 时间范围加按天聚合
  - 分组后取 Top N
  - 环比、占比
  - 多条件组合，加上 null 值处理
  - 窗口类问题，例如"每个 region 最近一次的 positive_rate"
- **执行：** 同一个模型（用当前默认的 agent 模型），对每道题分别生成 SQL 和 aggregation pipeline JSON。每道题各跑 3 次，执行后与人工写好的标准答案比对结果。
- **记录：** 执行成功率、结果正确率、平均 token 数、典型错误类型。
- **注意公平：** 两种形式的 prompt 结构和示例数量要保持一致。

### 13.6 对照组的只读查询隔离（只做设计评估，不实现）

如果走 mongo-native，"agent 自由查询"的等价做法是让 agent 提交 aggregation pipeline，由应用层：

- 在最前面强制插入 `$match: {dataset_id: {$in: allowed}}`
- 禁止 `$lookup`、`$unionWith`、`$graphLookup`、`$out`、`$merge`、`$function`、`$accumulator`、`$where` 等阶段和操作符
- 限制 `maxTimeMS`

报告中把这个方案和第 7.3 节的 PG RLS 方案对比：隔离是在应用层白名单里做，还是由数据库保证；被绕过的风险面有多大；未来 Mongo 新增操作符时白名单要不要跟着维护。
