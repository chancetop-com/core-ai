# Dataset 迁移 PostgreSQL 与报表体系 — 实施计划与设计

> 状态：草案（2026-11-03）
> 前置：[design-dataset-postgresql-poc.md](design-dataset-postgresql-poc.md)、[poc-dataset-postgresql-result.md](poc-dataset-postgresql-result.md)
> 关联：[design-dataset-report.md](design-dataset-report.md)（原 Report 设计，本文第 6 节取代其 ReportConfig 部分）、[design-dataset-session-state.md](design-dataset-session-state.md)
> 读者：实现本计划的工程师 / agent。每个阶段都可以单独交付，章节内给出了验收标准。

## 1. 背景与目标

### 1.1 产品意图

Dataset 是**由 agent 主导的库表**，需要同时满足：

1. **持久化**：agent 产出的结构化数据长期保存；
2. **字段灵活**：agent 可以随时新增、调整字段；
3. **查询灵活**：按任意字段过滤、聚合，不受扫描上限约束；
4. **对话式报表**：用户通过对话让 agent 创建报表；
5. **长期使用**：报表能固定到用户自己的 dashboard，持续刷新数月。

### 1.2 为什么是 PostgreSQL，为什么是现在

PoC 已经验证：PG 能满足 1–3（jsonb + 原子合并 + 数据库内过滤/聚合），并且**多出三项 Mongo 给不了或代价很高的能力**，而它们正好是 4、5 需要的：

- 每个 dataset 是一张真实的、带类型的视图（`ds_xxx`），"agent 主导的库表"有了物理形态；
- 跨 dataset JOIN、窗口函数（环比、移动平均）、百分位数，用 SQL 表达很自然；
- 报表 DSL 表达不了的长尾，可以用"针对视图的 SQL + RLS"兜底（PoC 第三阶段已验证）。

在 ReportSpec 和 dashboard 上线之前迁移，代价最低：数据量还小，报表还没有存量，Mongo 只涉及 `datasets` 与 `dataset_records` 两个集合。

### 1.3 不做的事

- 不在本计划内把全部 63 个 Mongo 集合迁到 PG（见第 8 节的存量策略）；
- 不引入 ORM 或新的 Web 框架，继续用 core-ng；
- v1 报表不做跨用户共享、不做 AI 文字摘要报表（原设计的 `SUMMARY` 类型），留到后续版本。

### 1.4 中间件约束（决定阶段顺序）

初衷是**少维护一个中间件**。如果只引入 PG，而 Mongo 和 Redis 都还在，就会出现一段"三个中间件"的窗口期。本计划的硬约束：

> **阶段 1 上线后，在一个季度内完成阶段 3（Redis 职责并入 PG），回到两个中间件。**
> 阶段 1 与阶段 3 作为同一个项目立项，不允许停在阶段 1。

## 2. 总体路线

| 阶段 | 内容 | 上线后的中间件 | 预估工作量 | 依赖 |
|---|---|---|---|---|
| 0 | 准备：PG 实例、库/角色布局、数据摸底、core-ng 兼容性验证 | Mongo + Redis | 1 周（运维并行） | — |
| 1 | Dataset 存储迁 PG（元数据 + 记录 + 视图） | Mongo + Redis + **PG** | 3–4 周 | 0 |
| 2 | ReportSpec + Dashboard（报表体系） | 同上 | 4–5 周（后端 2–3，前端 2） | 1 |
| 3 | Redis 职责并入 PG（命令队列、租约、计数、RPC、SSE 定向投递） | **Mongo + PG** | 6–8 周（含 2 周灰度） | 0；可与 2 并行 |
| 4 | Mongo 存量按价值迁移 | 逐步收敛为 PG | 持续 | 3 |

阶段 2 与阶段 3 改动的代码几乎不重叠，可以由不同的人并行推进。建议排期：

```
周   1   2   3   4   5   6   7   8   9  10  11  12  13
P0  ███
P1      ████████████
P2                  ████████████████████
P3          ░░░░(SSE 定向投递设计/实现)███████████████████
```

## 3. 阶段 0：准备

### 3.1 PG 实例

集群内使用 **CloudNativePG**（已建好，dev/uat/prod 各一套）：

| 项 | 现状（2026-11-03） |
|---|---|
| 形态 | CloudNativePG，3 实例（`postgresql-1/2/3`），主 `postgresql-1`，service `postgresql-rw/-ro/-r` |
| 版本 | PostgreSQL 16.15（`ghcr.io/cloudnative-pg/postgresql:16`） |
| 规格 | 每实例 1 vCPU / 1Gi，10Gi 存储；`max_connections=100` |
| 数据库 / owner | `core_ai` / `core_ai`（bootstrap owner，同时是应用角色） |
| 备份 | **未配置**（`spec.backup` 为空，没有 WAL 归档）——上线前必须补 Barman ObjectStore + 定时 Backup |
| 监控 | 已挂 `cnpg-default-monitoring` 查询；未开 `enablePodMonitor` |
| 扩展 | `pg_stat_statements`、`pg_trgm` 可用但**未安装**（需要 `shared_preload_libraries` + superuser） |

结论：形态从"Azure Flexible Server"改为 CNPG 后，HA、升级、故障切换由 operator 管理，但**备份/PITR 是必须补的第一项**；`pg_stat_statements` 需要改 Cluster CR（preload）并重启实例，放到 dev 验证后再动 uat/prod。

### 3.2 库、schema 与角色布局

一个数据库 `core_ai`，按职责分 schema，**reader 角色只能看见 `dataset` schema**：

| schema | 内容 | 阶段 |
|---|---|---|
| `dataset` | `dataset_records`、`dataset_sql_grants`、所有 `ds_xxx` 视图 | 1 |
| `app` | `datasets`、`dataset_views`、`reports`、`dashboards` 等业务元数据 | 1、2 |
| `messaging` | 命令队列、pod 注册、租约、计数 | 3 |

| 角色 | 权限 | 用途 |
|---|---|---|
| `core_ai` | 三个 schema 的 owner（CNPG bootstrap owner）；`search_path=app,dataset` | 应用连接 + flyway；因为它是 owner，RLS 对它不生效 |
| `dataset_reader` | `USAGE ON SCHEMA dataset`，只对 `dataset` 下的表和视图有 `SELECT`；独立角色 | 报表 SQL / agent SQL 执行器 |

> **CNPG 的差异：** 原设计的 `core_ai_owner`（仅 flyway）在 CNPG 下不需要——bootstrap owner 就是 `core_ai`。代价是应用角色同时是 owner，RLS 对应用不生效；这符合"应用本来就要看全部数据"的预期，真正的隔离边界是 `dataset_reader`。如果以后要拆出独立的应用角色，再加 `app_all` policy 和显式 DML 授权。

> **PoC 的修正（必须做）：** `DatasetSqlExecutor.initialize` 里执行了 `GRANT SELECT ON ALL TABLES IN SCHEMA public` 和 `ALTER DEFAULT PRIVILEGES ... GRANT SELECT ON TABLES TO reader`。PoC 里只有 dataset 的表所以没问题，但阶段 2、3 会把报表、命令队列、租约都放进同一个库，reader 将能读到所有这些表。生产上必须改为上面的 schema 隔离，角色和授权交给 flyway 管理，应用启动时不再执行任何 GRANT。

`dataset_reader` 的密码不能写进 migration 脚本。做法是：由运维创建角色并设置密码，flyway 只负责授权和 RLS policy。migration 角色没有 CREATEROLE，**不能** `ALTER ROLE dataset_reader SET search_path`——reader 的 `search_path` 由执行器在会话内 `SET LOCAL search_path = dataset` 设置。

### 3.3 数据摸底（决定迁移方式）

在 UAT 与生产的 Mongo 上执行：

```js
db.datasets.countDocuments()
db.dataset_records.countDocuments()
db.dataset_records.aggregate([{$group: {_id: "$dataset_id", n: {$sum: 1}}}, {$sort: {n: -1}}, {$limit: 20}])
db.dataset_records.stats().size
// data 不是合法 JSON 的记录（迁移时会失败）
db.dataset_records.find({data: {$not: /^\s*\{/}}).count()
```

- 总量在 500 万行以内：按第 4.8 节的"全量 + 增量 + 对账"迁移，不需要双写；
- 超过 500 万行：再评估是否做双写（概率很低）。

### 3.4 数据访问约定与 core-ng 兼容性验证（第一周完成）

**决定：不使用 core-ng 的 `Repository` / 实体映射。** 这个域的 schema、过滤条件和报表 spec 都是动态 JSON，Repository 的 JSON 列限制正好挡在每一条路上：列表字段只能是标量或枚举、bean 的每个字段和枚举常量都要 `@Property`、不能有 `Map<String, Object>`。而它的收益（实体 CRUD）在这里很小。

**保留 `db()` 做连接池、事务和 action log。** 手写 SQL 走 `Database.select/execute/batchExecute`，行映射用带 `@Column` 的只读 view 类；慢查询、SQL 参数、`INEFFICIENT_QUERY` 告警仍然进 trace。

必须走原生 JDBC 的只有三类：**DDL**（flyway / 运行时视图）、**`LISTEN`**（连接要长期持有）、**`COPY`**，统一由 `PgDdl` 或一条专用连接承载。`pg_notify`、`SKIP LOCKED`、`RETURNING` 都能留在 `Database` 上。

| 验证项 | 期望 | 不通过时的退路 |
|---|---|---|
| `Database.select/execute` 对 schema 限定表名 + `?::jsonb` 参数 | 正常（PoC 已验证） | 无 |
| JDBC URL `currentSchema=app,dataset,messaging` 解析无前缀表名 | 正常 | 全部 SQL 显式加 schema 前缀（建议直接这样做，不依赖 search_path） |
| `DELETE ... RETURNING`（迁移、报表）通过 `Database.select` | 正常（`EXPLAIN DELETE ... RETURNING` 已在 PG 16 验证） | 改用原生 JDBC |
| 连接池 / `Transaction` / action log 在 PG 上 | 正常（PoC 已跑通） | — |
| flyway 连接 Azure PG（密码认证） | 正常 | 用 flyway 官方镜像跑同一组脚本 |

已知限制（来自 PoC，直接沿用绕过方式）：不能绑定数组参数；SQL 里不能出现单引号（`interval '30 seconds'` 用 `make_interval(secs => ?)`，`'.'` 用 `chr(46)`）；jsonb 的 `?` 运算符会和 JDBC 占位符冲突，改用 `jsonb_exists(data, ?)`。

另外 `Database` 会对每个**新的 SQL 文本**首次执行一次 `EXPLAIN`（同一个文本在 pod 内 6 小时内只做一次）。报表编译出的 SQL 形状多，第一次执行会多一次往返；报表有缓存，量级可控，但要把它算进 p99。

### 3.5 验收

- UAT、生产 PG 实例就绪，三个角色和三个 schema 建好；
- 数据摸底结果记入本文附录；
- 3.4 的验证结论记入本文，不通过的项已确定退路。

## 4. 阶段 1：Dataset 存储迁 PG

### 4.1 目标架构

```
DatasetService ──────────────> DatasetMetaStore ─┬─ MongoDatasetMetaStore   (过渡，回滚用)
   (schema 变更 + 视图同步 同一事务)              └─ PgDatasetMetaStore       (目标)
DatasetRecordService ────────> DatasetRecordStore ┬─ MongoDatasetRecordStore (过渡，回滚用)
                                                  └─ PostgresDatasetRecordStore (目标，来自 PoC)
```

- **元数据也迁到 PG。** `datasets` 只有 8 处 Mongo 调用（`DatasetService`），迁移成本很低。好处是 schema 变更、视图重建、受影响报表的标记可以放在**同一个事务**里完成，不会出现"schema 改了、视图没改"的中间状态。
- **两个 PG store 都用"手写 SQL + `@Column` 行类"，不使用 `Repository`**（原因见 3.4）。`schema_json` 以普通 `text` 列存储，进出用 `JsonUtil` 序列化；枚举按 text 存、手动转换。
- `sys.dataset.store=mongo|postgresql` 同时切换元数据和记录，两者不允许分开切。
- 删除 mongo-native 实现：`MongoNativeDatasetRecordStore`、`MongoNativeFilter`、`MongoNativeAggregation` 及其测试。PoC 结论已经出来，没有必要保留。
- `MongoDatasetRecordStore` / `MongoDatasetMetaStore` 保留到切换完成后 30 天，之后与 Mongo 集合一起删除。

### 4.2 表结构（flyway `V1__dataset.sql`）

新建 gradle 子项目 `core-ai-server-db-migration`，应用 `db-migration` 插件，脚本放在 `src/main/resources/db/migration/`。各环境的 `conf/<env>/resources/flyway.properties` 指向对应实例。部署方式是在 helm 的 pre-upgrade hook 里执行一个 k8s Job（`./gradlew flywayMigrate -Penv=<env>`，或者直接用 flyway 官方镜像跑同一组脚本）。**应用启动时不再执行建表 / 建 schema 的 DDL**（每个 dataset 的视图仍由 `DatasetViewManager` 在运行时创建）：删除 `DatasetPgSchemaInitializer`，`DatasetSqlExecutor.initialize` 不再建角色和授权。

```sql
CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS dataset;

-- metadata
CREATE TABLE app.datasets (
    id             text PRIMARY KEY,
    name           text NOT NULL,
    description    text,
    user_id        text NOT NULL,
    type           text,                          -- null = GENERAL
    schema_json    text NOT NULL DEFAULT '[]',    -- List<SchemaField> serialized by JsonUtil
    schema_version integer NOT NULL DEFAULT 1,    -- +1 on every schema change
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE INDEX ix_datasets_user ON app.datasets (user_id, updated_at DESC);

CREATE TABLE app.dataset_views (
    dataset_id  text PRIMARY KEY,
    view_name   text NOT NULL UNIQUE,
    column_map  jsonb NOT NULL,
    schema_hash text NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE TABLE app.dataset_field_indexes (
    dataset_id  text NOT NULL,
    field_name  text NOT NULL,
    index_name  text NOT NULL UNIQUE,
    reason      text NOT NULL,                    -- REPORT | MANUAL
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (dataset_id, field_name)
);

-- records (same shape as the PoC)
CREATE TABLE dataset.dataset_records (
    id             text PRIMARY KEY,
    dataset_id     text NOT NULL,
    agent_id       text NOT NULL,
    run_id         text,
    session_id     text,
    user_id        text,
    data           jsonb NOT NULL DEFAULT '{}'::jsonb,
    run_started_at timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    created_by     text NOT NULL,
    updated_at     timestamptz NOT NULL,
    updated_by     text NOT NULL
);
CREATE INDEX ix_dataset_records_dataset_time ON dataset.dataset_records (dataset_id, run_started_at DESC);
CREATE INDEX ix_dataset_records_dataset_agent_time ON dataset.dataset_records (dataset_id, agent_id, run_started_at DESC);
CREATE INDEX ix_dataset_records_updated ON dataset.dataset_records (updated_at);   -- migration delta + cache
CREATE INDEX ix_dataset_records_run ON dataset.dataset_records (run_id) WHERE run_id IS NOT NULL;
CREATE UNIQUE INDEX ux_dataset_records_session ON dataset.dataset_records (dataset_id, session_id) WHERE session_id IS NOT NULL;
CREATE INDEX ix_dataset_records_data ON dataset.dataset_records USING gin (data jsonb_path_ops);

CREATE TABLE dataset.dataset_sql_grants (
    token      text NOT NULL,
    dataset_id text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (token, dataset_id)
);

-- functions: jsonb_merge_patch (from the PoC, unchanged), dataset_to_date, dataset_to_timestamp (4.4)

-- application role: not the owner, so RLS applies and table privileges must be explicit
-- (app/dataset only; the same grants for messaging are added in V3, when that schema is created)
GRANT USAGE ON SCHEMA app, dataset TO core_ai;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA app, dataset TO core_ai;
GRANT CREATE ON SCHEMA dataset TO core_ai;                                  -- runtime view DDL
ALTER DEFAULT PRIVILEGES FOR ROLE core_ai_owner IN SCHEMA app, dataset
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_ai;               -- tables created later by flyway

-- reader
GRANT USAGE ON SCHEMA dataset TO dataset_reader;
GRANT SELECT ON dataset.dataset_records, dataset.dataset_sql_grants TO dataset_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE core_ai IN SCHEMA dataset GRANT SELECT ON TABLES TO dataset_reader;  -- views created later
ALTER ROLE dataset_reader SET search_path = dataset;

ALTER TABLE dataset.dataset_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_all ON dataset.dataset_records TO core_ai USING (true) WITH CHECK (true);
CREATE POLICY reader_scope ON dataset.dataset_records FOR SELECT TO dataset_reader
    USING (dataset_id IN (SELECT g.dataset_id FROM dataset.dataset_sql_grants g
                          WHERE g.token = current_setting('app.dataset_token', true)));
ALTER TABLE dataset.dataset_sql_grants ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_all ON dataset.dataset_sql_grants TO core_ai USING (true) WITH CHECK (true);
CREATE POLICY reader_scope ON dataset.dataset_sql_grants FOR SELECT TO dataset_reader
    USING (token = current_setting('app.dataset_token', true));
```

说明：

- 表的 owner 是 `core_ai_owner`，所以 `core_ai` 也受 RLS 约束，需要一条 `app_all` policy 放行（也可以给 `core_ai` 加 `BYPASSRLS`，二选一）；
- `ALTER DEFAULT PRIVILEGES FOR ROLE core_ai` 要求执行它的角色（flyway 的 `core_ai_owner`）**是 core_ai 的成员**，否则会 permission denied；这一点在阶段 0 的角色创建脚本里落实（`GRANT core_ai TO core_ai_owner WITH ADMIN OPTION`，或让两者由同一个运维角色管理）；
- 视图的 owner 是 `core_ai`（运行时由 `DatasetViewManager` 创建），并且必须带 `WITH (security_invoker = true)`，PoC 已经这样做了。否则 reader 通过视图读数据时，RLS 会按视图 owner 的身份判断，实际上等于失效；
- 原设计（design-dataset-report.md）里有一条 `(dataset_id, run_id)` 唯一约束，但它实际没有落地：`SchemaMigrationVDatasetIndexes` 没有建这个索引，而且 `insert_dataset_record` 工具允许一个 run 写入多条记录。所以这里**不建**唯一约束，只为按 run 反查建一个普通的部分索引。

### 4.3 写入时按 schema 校验类型

PoC 的结论之一是：类型不匹配的数据会让视图列变成 NULL，从而让报表悄悄出错。所以要在入口挡住。

`DatasetRecordWriteRules` 新增 `normalize(Dataset, Map<String, Object>)`，insert、update、upsertBySession、patchBySession 四条写路径都要调用：

| 字段类型 | 接受 | 自动转换 | 拒绝 |
|---|---|---|---|
| NUMBER | 数字 | 数字字符串（`"42"`、`"3.5"`）转为数字 | 其它 |
| STRING | 字符串 | 数字、布尔值转为字符串 | 对象、数组 |
| BOOLEAN | 布尔值 | `"true"` / `"false"` | 其它 |
| DATE（新增） | `yyyy-MM-dd` 字符串 | 带时间的 ISO 字符串截取日期部分 | 其它 |
| DATETIME（新增） | 带时区偏移的 ISO-8601 | 没有偏移的值按 dataset 的时区补上 | 其它 |

- `null` 仍然表示删除字段（patch 语义），不参与校验；
- 不在 schema 里的字段沿用现有规则：有 schema 时丢弃，无 schema 时全部接受；
- 被拒绝时返回 `IllegalArgumentException`，消息里列出字段名、期望类型和实际值的类型。工具层会把它转成 `ToolCallResult.failed`，agent 能看到原因并自行修正。

### 4.4 新增 DATE / DATETIME 字段类型

报表经常需要按业务日期分析（如订单日期），而不是只能按 `run_started_at`。

- `SchemaFieldType` 新增 `DATE`、`DATETIME`。Mongo 实体仍需要 `@MongoEnumValue`，对外 view 用 `@Property`；PG 侧按 `text` 存储、读出来手动转枚举（不使用 `@DBEnumValue`）；
- 视图列的表达式要求**可以建索引**，因此必须是 IMMUTABLE 函数，并且遇到非法值时返回 NULL 而不是报错：

```sql
CREATE FUNCTION dataset.dataset_to_date(v jsonb) RETURNS date LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  IF jsonb_typeof(v) <> 'string' OR NOT (v #>> '{}') ~ '^\d{4}-\d{2}-\d{2}' THEN RETURN NULL; END IF;
  RETURN make_date(substr(v #>> '{}', 1, 4)::int, substr(v #>> '{}', 6, 2)::int, substr(v #>> '{}', 9, 2)::int);
EXCEPTION WHEN others THEN RETURN NULL;
END $$;

CREATE FUNCTION dataset.dataset_to_timestamp(v jsonb) RETURNS timestamptz LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
  IF jsonb_typeof(v) <> 'string' OR NOT (v #>> '{}') ~ '(Z|[+-]\d{2}:?\d{2})$' THEN RETURN NULL; END IF;
  RETURN (v #>> '{}')::timestamptz;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;
```

`dataset_to_timestamp` 声明为 IMMUTABLE 严格来说不成立，因为 `::timestamptz` 依赖会话时区。但入参必须带显式偏移，偏移存在时结果与会话时区无关，所以这样声明是安全的。正则检查就是这个前提的保证，不能删。

`DatasetViewManager.expression()` 新增两个分支，分别调用这两个函数；`DatasetViewFilterSql` 支持对这两种类型做比较和 `$in`，参数用 `?::date` / `?::timestamptz` 绑定。

### 4.5 Schema 变更与视图生命周期

**所有 schema 变更都走 `DatasetService.update`，在一个 PG 事务里完成：**

```
BEGIN
  SELECT ... FROM app.datasets WHERE id = ? FOR UPDATE      -- serialize concurrent schema edits
  UPDATE app.datasets SET schema_json = ?, schema_version = schema_version + 1, updated_at = ?
  DROP VIEW / CREATE VIEW dataset.ds_xxx ... WITH (security_invoker = true)   -- via the same JDBC connection
  UPSERT app.dataset_views
  UPDATE app.reports SET status = ... WHERE id IN (SELECT report_id FROM app.report_datasets WHERE dataset_id = ?)  -- stage 2
COMMIT
```

- PG 的 DDL 是事务性的，视图重建失败时整个事务回滚，schema 不会和视图不一致；
- 由于 core-ng 会给 DDL 套 EXPLAIN（PoC 3.3），这段事务要在 `PgDdl` 提供的原生 JDBC 连接上执行，DML 也在同一个连接上，不能混用 core-ng 的 `Transaction`。建议把它封装成 `PgDdl.inTransaction(connection -> ...)`；
- 重建视图前先 `SET LOCAL lock_timeout = '3s'`。`DROP VIEW` 需要 AccessExclusive 锁，如果有长查询正在读这个视图，宁可失败重试，也不要一直排队，否则会把后面所有读这个视图的请求都堵住。

**变更类型和处理方式：**

| 变更 | 记录数据 | 视图 | 已有报表（阶段 2） |
|---|---|---|---|
| 新增字段 | 不动 | 加列 | 不受影响 |
| 删除字段 | 不动（数据保留在 jsonb 里，只是视图不再暴露） | 删列；同时删除该字段的表达式索引 | 引用了该字段的报表标为 `BROKEN` |
| 修改类型 | 不动；返回一份报告：有多少条记录的值和新类型不匹配 | 改列的表达式；重建该字段的表达式索引 | 重新校验，不合法的标为 `BROKEN` |
| **重命名字段**（新增操作） | `UPDATE dataset.dataset_records SET data = (data - ?) \|\| jsonb_build_object(?, data -> ?) WHERE dataset_id = ? AND jsonb_exists(data, ?)` | 改列名 | 自动把 spec 里的旧字段名改成新名字，状态不变 |

重命名作为独立操作提供：hub API 和 agent 工具各新增一个 `rename_field(dataset_id, from, to)`。不要从"删一个字段、加一个字段"去推断是重命名，那样推断不可靠。数据量大的 dataset 分批更新：每批 5000 条，按 id 分页，每批一个事务；在全部完成之前，视图和报表都不切换。

**删除 dataset（同一个事务）：** 先把引用它的报表标为 `BROKEN`，再删 `app.report_datasets` / `app.report_fields` / `app.dataset_field_indexes` / `app.dataset_views` 中该 dataset 的行和 `dataset.dataset_records` 的记录，最后 `DROP VIEW`。顺序反过来会在中途失败时留下悬空引用。

**启动时对账：** `DatasetViewReconciler` 在应用启动后，以及每天一次，对比 `app.datasets` 的 schema 和 `app.dataset_views.schema_hash`，补建缺失或过期的视图。多个 pod 同时执行时，用 **session 级** `pg_try_advisory_lock(hashtext('dataset_view_reconcile'))` 加锁，对账结束后显式 unlock；不要用 `_xact_` 版本——它在自动提交下随语句结束就释放，起不到互斥作用。

### 4.6 字段索引策略

- GIN（`jsonb_path_ops`）服务于走 jsonb 路径的标量等值过滤（`data @> ?`），也就是 schema 以外的字段和嵌套路径；
- schema 字段走视图，GIN 用不上，需要按 `DatasetViewManager.expression()` 建表达式索引：`(dataset_id, <表达式>)`；
- **不要对所有字段都建索引**，写入放大和存储成本都会很高。只在两种情况下建：
  1. 某个固定报表在这个字段上过滤或分组，并且这个 dataset 的记录数超过 5 万条（报表保存时检查）；
  2. 管理员手动指定。
- 每个 dataset 最多 5 个表达式索引，登记在 `app.dataset_field_indexes`，删字段时一并删除；
- 必须用 `CREATE INDEX CONCURRENTLY` 建索引，否则会阻塞写入。它不能在事务里执行，所以 `PgDdl` 要新增一个 autocommit 模式的方法，由后台任务异步执行。建索引失败时会留下 INVALID 索引，要先 DROP 再重试。

### 4.7 查询路径（沿用 PoC，补齐生产化细节）

- 路由规则不变：过滤字段全部是 schema 里的简单字段，就走视图；否则走 jsonb 路径。视图表达不了的条件（`null`、`$ne: null`、`$exists`、对象或数组值）整体回退到 jsonb 路径；
- 先修掉 PoC 遗留的语义差异：视图路径上的 `$contains` 要拒绝非字符串参数（和 jsonb 路径一致）；对数字字段用 `$contains` 时，jsonb 路径会把数字转成文本去匹配，视图路径则不匹配。统一为"`$contains` 只匹配字符串值"，并修改 jsonb 路径的实现（`jsonb_typeof = 'string'` 保护），再补上契约用例；
- 元数据缓存：每次查询都要读 dataset schema 和 `dataset_views`，改为进程内缓存，key 是 `dataset_id`，value 是 `(schema_version, column_map)`，TTL 30 秒。本 pod 修改 schema 后立即失效；其它 pod 最多读到 30 秒前的旧元数据。如果视图已经被重建而缓存里还是旧列名，查询会报"列不存在"，此时清掉缓存并重试一次；
- 两套契约测试（无 schema / 带 schema）继续作为回归网，新增的 DATE、DATETIME 类型要在带 schema 的那一套里补用例。

### 4.8 数据迁移

**工具：** `DatasetMigrationJob`，放在 core-ai-server 内，通过内部管理 API（`POST /_sys/dataset-migration`，需要管理员权限）触发，同时注入 Mongo 和 PG 两套 store。

| 步骤 | 操作 | 幂等方式 |
|---|---|---|
| 1. 元数据全量 | 遍历 `datasets`，写入 `app.datasets`，并为每个 dataset 建视图 | `ON CONFLICT (id) DO UPDATE ... WHERE app.datasets.updated_at < EXCLUDED.updated_at` |
| 2. 记录全量 | 按 `_id` 分页（每批 1000 条），把 `data` 字符串解析成 jsonb，用 `batchExecute` 写入 | 同上，按 `updated_at` 判断新旧 |
| 3. 增量 | 取 `updated_at >= 上次检查点 - 5 分钟` 的记录，重复执行直到增量为 0 | 同上 |
| 4. 删除对账 | 按 dataset 比较记录数；数量不一致的 dataset 做 id 差集，删除 PG 里多出的 id | 可以重复执行 |
| 5. 校验 | 按 dataset 比较记录数；抽样 1% 的记录，把 data 解析成 Map 后比较是否相等；挑 10 个代表性查询，在两边执行并比较结果 | — |

- `data` 不是合法 JSON 的记录：跳过并写入迁移报告（包括 id、dataset、原始值的前 200 个字符），不阻塞整体迁移。阶段 0 的摸底已经统计过数量；
- 迁移前执行 4.3 节的类型校验，**只统计、不拦截**：哪些 dataset 有多少条记录的值和 schema 类型不匹配。结果给 dataset 的 owner 参考，迁移本身照常进行；
- 迁移报告保存为 JSON，供人工检查。

### 4.9 切换与回滚

```
1. 发布 vN：带 PG 配置，sys.dataset.store=mongo，包含迁移工具   → 验证连通性，StartupDependencyCheck 增加 PG 探测
2. 执行步骤 1–3（全量 + 增量）
3. （UAT 必做，生产可选）影子读：sys.dataset.shadowRead=true
     查询以 Mongo 结果为准返回，同时异步在 PG 上执行同一查询，比较 total 和 id 列表，差异写日志
     观察 3–7 天，差异必须为 0，或者能逐条解释
4. 切换：sys.dataset.store=postgresql，滚动重启
     滚动期间旧 pod 仍然写 Mongo，新 pod 写 PG
5. 滚动完成后：执行增量和删除对账（步骤 3、4），把旧 pod 在窗口期写入 Mongo 的数据补到 PG
6. Mongo 的 datasets、dataset_records 保留 30 天，不删除
7. 30 天后：删除 Mongo 实现、Mongo 集合和影子读代码
```

- **回滚**（30 天内）：迁移工具支持 `direction=pg_to_mongo`，用同样的方式增量回写；然后切回 `sys.dataset.store=mongo`，滚动重启，再执行一次增量；
- 影子读用一个 `ShadowDatasetRecordStore` 装饰器实现，生产上可以只对 10% 的查询开启。

### 4.10 运维与监控

- `StartupDependencyCheck` 增加 `verify("postgres", ...)`，执行 `SELECT 1`；
- 连接池：用 core-ng 的 `db().poolSize(min, max)`，每个 pod 先设为 `5, 20`；reader 连接改为独立的小连接池（2–5 个），不再每次执行都新建连接；
- 慢查询：core-ng 的 action log 会自动记录慢 SQL；PG 侧用 `pg_stat_statements`，每周看一次 Top 20；
- 告警：连接数超过 80%、存储超过 70%、复制延迟、长事务（超过 60 秒）；
- 清理：`dataset_sql_grants` 的过期 token 由定时任务删除，不再在每次执行时顺带删除。

### 4.11 阶段 1 交付物与验收

| 交付物 | 验收标准 |
|---|---|
| `core-ai-server-db-migration` 子项目 + `V1__dataset.sql` | UAT、生产都能 `flywayMigrate`；应用启动时不执行任何 DDL |
| `PgDatasetMetaStore`，`DatasetService` 改为基于 store 接口 | schema 变更 + 视图重建在同一事务；在重建视图时注入失败，事务能完整回滚 |
| 写入类型校验 + DATE/DATETIME | 四条写路径都有用例；两套契约测试覆盖新类型 |
| 重命名字段 | hub API + agent 工具；大 dataset 分批执行的用例 |
| 视图对账、字段索引任务 | 删除视图后重启，视图能自动补建；`CONCURRENTLY` 建索引失败后能清理并重试 |
| 迁移工具 + 影子读 | UAT 影子读 7 天没有无法解释的差异；迁移报告归档 |
| 删除 mongo-native、`DatasetPgSchemaInitializer`、执行器里的授权代码 | 代码中没有残留引用 |
| 监控与告警 | 已配置，并在 UAT 触发过一次 |

## 5. 阶段 1 与 core-ai 其它模块的边界

- dataset 的 agent 工具（`dataset/tool/*`）、hub 的 dataset API（`HubDatasetService`）对外的输入输出**不变**，`DatasetOpPayloads` 的形状也不变。区别只是 `truncated` 恒为 false，`total` 变为精确值；
- `AgentDefinition.outputDatasets` 引用的 dataset id 不变，不需要迁移；
- session state 类型的 dataset 走同一张表（`session_id` 列 + 部分唯一索引），PoC 已经验证。

## 6. 阶段 2：ReportSpec 与 Dashboard

### 6.1 概念

| 概念 | 说明 |
|---|---|
| **ReportSpec** | 一份报表的结构化定义：数据源、过滤、时间、维度、指标、展示方式。**长期保存的一律存 spec，不存 SQL** |
| **Report** | 保存下来的报表：spec + 名称 + owner + 状态 |
| **Dashboard** | 用户的看板，由多个固定上去的 Report 组成，带布局信息 |
| **SQL Report**（高级） | spec 表达不了的长尾，保存一段针对视图的只读 SQL，用 reader 角色加 RLS 执行。v1 默认关闭，由开关控制 |

为什么存 spec 而不存 SQL：spec 可以在保存时按 schema 校验；schema 变更时能知道影响了哪些报表；字段重命名可以自动改写；dashboard 上的时间范围是参数而不是拼进 SQL 的文本；前端能确定性地选择图表；LLM 只需要按 JSON schema 填空，容易校验，出错也能自动修正。

### 6.2 ReportSpec v1

```json
{
  "version": 1,
  "source": {"dataset_id": "ds-123"},
  "filters": {"region": {"$in": ["us", "ca"]}, "flagged": false},
  "time": {
    "field": "run_started_at",
    "range": {"last": "30d"},
    "bucket": "day",
    "timezone": "Asia/Shanghai"
  },
  "dimensions": [{"field": "region", "top": 10}],
  "metrics": [
    {"id": "rate", "label": "好评率", "expr": "SUM(positive_count) / SUM(total)", "format": "percent"},
    {"id": "reviews", "label": "评论数", "expr": "SUM(total)", "format": "integer"}
  ],
  "compare": {"type": "previous_period"},
  "sort": [{"by": "reviews", "desc": true}],
  "limit": 200,
  "visual": {"type": "LINE_CHART", "x": "time", "series": "region", "y": ["rate"]}
}
```

| 字段 | 规则 |
|---|---|
| `source.dataset_id` | 必填。v1 只支持单个 dataset（跨 dataset 关联见 6.9） |
| `filters` | 沿用现有的过滤 DSL，**只能引用 schema 字段**，保证走视图路径 |
| `time.field` | `run_started_at`，或者 DATE / DATETIME 类型的 schema 字段 |
| `time.range` | `{"last": "7d"\|"30d"\|"90d"\|"12w"\|"12m"}`、`{"from": ..., "to": ...}`，或者 `{"this": "week"\|"month"\|"quarter"\|"year"}`。dashboard 上可以覆盖 |
| `time.bucket` | `hour` / `day` / `week` / `month` / `null`（不按时间分桶） |
| `dimensions` | 0–2 个 schema 字段（STRING、BOOLEAN、DATE），`top` 表示按第一个指标取前 N 个 |
| `metrics` | 1–6 个，`expr` 使用 6.3 节的表达式语法 |
| `compare` | `null`，或者 `previous_period`（与上一个等长时间段对比） |
| `visual.type` | `METRIC` / `LINE_CHART` / `BAR_CHART` / `PIE_CHART` / `TABLE` |

### 6.3 指标表达式

用自己写的小型解析器解析，**不接受原始 SQL**：

```
expr     := term (('+' | '-') term)*
term     := factor (('*' | '/') factor)*
factor   := number | agg | '(' expr ')'
agg      := FUNC '(' field ')' | 'COUNT' '(' '*' ')'
FUNC     := SUM | AVG | MIN | MAX | COUNT | COUNT_DISTINCT | LAST | FIRST | P50 | P90 | P99
```

- `field` 必须是 schema 字段：SUM、AVG、P* 只接受 NUMBER；COUNT_DISTINCT、LAST、FIRST 接受任意类型；
- 编译成 SQL 时：数字常量一律绑定为参数（受 `validateSQL` 限制）；除法自动包一层 `NULLIF(分母, 0)`；P50 编译为 `percentile_cont(0.5) WITHIN GROUP (ORDER BY col)`，其中 0.5 也作为参数绑定；LAST 编译为 `(array_agg(col ORDER BY run_started_at DESC))[1]`；
- 不支持跨行计算（例如累计值），v2 再考虑窗口类指标。

### 6.4 编译与执行

```
ReportSpec ──校验(schema)──> ReportQueryPlan ──编译──> SQL（针对 dataset.ds_xxx，参数全部绑定）
                                                      └─ 执行（core_ai 角色；视图天然只含该 dataset 的数据）
```

编译后的 SQL 形状（以 6.2 的 spec 为例）：

```sql
SELECT date_trunc(?, run_started_at AT TIME ZONE ?) AS t,
       "region" AS d1,
       SUM("positive_count") / NULLIF(SUM("total"), 0) AS m_rate,
       SUM("total") AS m_reviews
FROM ds_xxx
WHERE run_started_at >= ? AND run_started_at < ?
  AND "region" IN (?, ?) AND "flagged" = ?
GROUP BY 1, 2
ORDER BY 1, 4 DESC
LIMIT ?
```

- `top` 维度：先执行一次"按维度汇总、取前 N 个"的子查询，再把这 N 个值作为 `IN (?, ...)` 条件放进主查询（不能绑定数组，所以展开成 N 个占位符，N ≤ 50）；
- `time.field` 是 DATE 类型时用两参数 `date_trunc(?, col::timestamp)`；只有 `timestamptz` 字段（`run_started_at` 或 DATETIME 列）才能用三参数、带时区的版本。编译器按字段类型选模板，不能只判断"是不是时间字段"；
- `compare.previous_period`：在等长的上一段时间上再执行一次同样的查询，在 Java 里按 (t 的偏移量, 维度) 合并。这比写窗口函数简单，结果也更好验证；
- 结果统一转换为 `ReportData { columns: [...], rows: [[...]], compare_rows: [...], generated_at }`，用 PoC 的 `PgJsonRow`（`jsonb_build_array(...)::text`）解决列数不固定的问题；
- 每次执行都设置 `statement_timeout = 10s`，结果最多 5000 行。

### 6.5 校验与 schema 变更的影响

- **保存时校验**：字段是否存在、类型是否匹配；指标表达式能否解析；维度个数和指标个数是否超限；图表配置是否与维度、指标对得上（例如 PIE_CHART 必须正好 1 个维度、1 个指标）；
- **依赖登记**：`app.report_datasets(report_id, dataset_id)`、`app.report_fields(report_id, dataset_id, field_name)`。不能绑定数组，所以用关联表来做依赖查询；
- **schema 变更**（4.5 节的同一个事务里）：
  - 删除字段：引用了它的报表变为 `BROKEN`，`broken_reason = "field xxx removed"`；
  - 修改类型：重新校验；
  - 重命名：改写 spec，并更新 `report_fields`；
- `BROKEN` 的报表在 dashboard 上显示原因和"让 agent 修复"按钮。点击后打开一个对话，预填报表 id 和失败原因，agent 用 `update_report` 修复。

### 6.6 数据模型（`V2__report.sql`）

```sql
CREATE TABLE app.reports (
    id            text PRIMARY KEY,
    user_id       text NOT NULL,
    name          text NOT NULL,
    description   text,
    kind          text NOT NULL,              -- SPEC | SQL
    spec_json     text,                       -- kind = SPEC
    sql_text      text,                       -- kind = SQL
    status        text NOT NULL,              -- VALID | BROKEN
    broken_reason text,
    source_session_id text,                   -- conversation that created it
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);
CREATE INDEX ix_reports_user ON app.reports (user_id, updated_at DESC);

CREATE TABLE app.report_datasets (report_id text NOT NULL, dataset_id text NOT NULL, PRIMARY KEY (report_id, dataset_id));
CREATE INDEX ix_report_datasets_dataset ON app.report_datasets (dataset_id);
CREATE TABLE app.report_fields (report_id text NOT NULL, dataset_id text NOT NULL, field_name text NOT NULL,
                                PRIMARY KEY (report_id, dataset_id, field_name));
CREATE INDEX ix_report_fields_dataset ON app.report_fields (dataset_id, field_name);

CREATE TABLE app.dashboards (
    id         text PRIMARY KEY,
    user_id    text NOT NULL,
    name       text NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX ux_dashboards_default ON app.dashboards (user_id) WHERE is_default;

CREATE TABLE app.dashboard_items (
    id             text PRIMARY KEY,
    dashboard_id   text NOT NULL REFERENCES app.dashboards (id) ON DELETE CASCADE,
    report_id      text NOT NULL REFERENCES app.reports (id) ON DELETE CASCADE,
    position       integer NOT NULL,
    layout_json    text NOT NULL,             -- {"w": 6, "h": 4}
    overrides_json text,                      -- {"time": {"range": {"last": "7d"}}}
    created_at     timestamptz NOT NULL
);
CREATE INDEX ix_dashboard_items_dashboard ON app.dashboard_items (dashboard_id, position);
```

这几张表同样不使用 `Repository`：读写走手写 SQL，行映射用 `@Column` view 类。`spec_json` / `layout_json` / `overrides_json` 都是普通 `text` 列，进出用 `JsonUtil` 序列化。这样 `ReportSpec` 可以按业务需要自由定义 `Map`、嵌套 bean 和枚举，不受 core-ng JSON 校验（每个字段必须 `@Property`）的限制；`filters` 在 JSON 里保持 6.2 示例中的对象形状，编译阶段再解析成过滤 DSL。

### 6.7 Agent 工具（对话式创建报表）

| 工具 | 作用 |
|---|---|
| `describe_dataset(dataset_id)` | 已有能力的增强：返回 schema、记录数、每个字段的非空率和 top 值（采样），帮助 agent 选字段 |
| `preview_report(spec)` | 校验并执行 spec，返回前 50 行数据和一个 `report_preview` 事件。**聊天界面直接渲染出图表** |
| `save_report(spec, name, description)` | 保存报表，返回 report_id |
| `pin_report(report_id, dashboard_id?, layout?)` | 固定到 dashboard（不指定时用默认 dashboard，没有就自动创建） |
| `list_reports()` / `get_report(id)` | 查看已有报表 |
| `update_report(id, spec)` | 修改报表，包括修复 `BROKEN` 的报表 |
| `query_dataset_sql(sql, dataset_ids)` | 仅在 `sys.dataset.sqlExecutor.enabled=true` 时提供：探索性的只读 SQL |

对话流程约定（写进工具描述和系统提示）：

```
用户描述需求 → describe_dataset → 生成 spec → preview_report（失败就根据错误修正，最多重试 3 次）
→ 让用户确认图表 → save_report → 询问是否固定 → pin_report
```

`preview_report` 返回的校验错误必须结构化（`{path: "metrics[0].expr", error: "field total is STRING, SUM requires NUMBER"}`），让 agent 能自己改正。

### 6.8 API、缓存与前端

**API**（`ReportWebService`、`DashboardWebService`）：

- `GET/POST/PUT/DELETE /api/reports[/{id}]`
- `POST /api/reports/preview`（请求体是 spec）
- `POST /api/reports/{id}/data`（请求体是覆盖参数：时间范围）
- `GET/POST/PUT/DELETE /api/dashboards[/{id}]`；`PUT /api/dashboards/{id}/items`（整体保存布局）

**缓存**：用 core-ng 的 `cache()` 本地缓存，key 是 `report_id + 覆盖参数的 hash + schema_version`；TTL 按分桶粒度设置，`hour` 为 1 分钟，其它为 5 分钟。用户点刷新时带 `refresh=true` 跳过缓存。不做"写入即失效"，否则会在 dataset 上形成一个写入热点。

**前端**：

- 新页面"我的看板"（路由 `/boards`，避开现有 `pages/dashboard`，那个是运行时监控页面）：网格布局，每个格子渲染一份报表；
- `ReportChart` 组件根据 `visual.type` 渲染（Recharts），**和聊天里的 `report_preview` 用同一个组件**；
- 每个格子提供：时间范围切换、刷新、编辑（打开对话）、取消固定；`BROKEN` 状态显示原因和修复入口。

**权限**：v1 只有 owner 能看自己的报表和 dashboard。执行时按 owner 身份重新校验 dataset 访问权（复用 dataset 现有的权限判断）；如果已经没有权限，报表显示"无权访问数据源"，不报错。

### 6.9 v2 预留：跨 dataset 关联与 SQL 报表

- spec 的 `source` 扩展为 `sources: [{alias, dataset_id}]` 加上 `joins: [{left, right, on: [[field, field]], type}]`，编译成视图之间的 JOIN；`report_datasets` 已经支持一个报表依赖多个 dataset；
- SQL 报表：执行时先给报表引用的所有 dataset 授权，再用 reader 角色执行。时间范围通过 GUC（会话参数）`app.report_from` / `app.report_to` 注入，SQL 里调用 `dataset.report_from()` / `dataset.report_to()` 这两个函数读取，不做字符串替换。保存时执行一次 `EXPLAIN` 确认 SQL 能跑通。SQL 报表做不了依赖分析，schema 变更后只能在执行时才发现问题，所以要标注为"高级报表"；
- **建议的验证方式**：v1 上线 4–6 周后，统计 agent 生成 spec 失败的情况，以及用户提出但做不出来的报表需求。如果跨 dataset 关联占比明显，就做 v2 的 JOIN；如果大部分是零散的长尾需求，就开放 SQL 报表。

### 6.10 阶段 2 交付物与验收

| 交付物 | 验收标准 |
|---|---|
| `V2__report.sql`；Report、Dashboard 的 store 和服务 | CRUD 与依赖登记的单元测试 |
| ReportSpec 校验器 + 指标表达式解析器 + SQL 编译器 | 针对编译器的快照测试，覆盖 6 种指标函数、2 个维度、top、compare、5 种图表类型；集成测试在真实 PG 上执行 |
| schema 变更联动 | 删字段、改类型、重命名三种情况的报表状态测试 |
| 7 个 agent 工具 | 在测试 dataset 上跑通完整的对话流程（至少 10 个典型需求，人工验收） |
| API + 缓存 | — |
| 前端：我的看板 + `ReportChart` + 聊天内预览 | 固定、布局、时间覆盖、刷新、`BROKEN` 态都可以操作 |

## 7. 阶段 3：Redis 职责并入 PG

### 7.1 现状清单与替代方案

| Redis 用途 | 位置 | Key / 通道 | 语义 | PG 替代 |
|---|---|---|---|---|
| 每个 pod 的命令队列 | `CommandPublisher`、`CommandConsumer` | `coreai:commands:pod:*`（Stream，XREAD，处理完成后在 finally 里 XDEL） | 处理完才删；处理中崩溃不会提前丢消息 | `messaging.session_commands`，用 `SKIP LOCKED` 消费（7.3） |
| 无主命令队列 | 同上 | `coreai:commands:unowned`（消费组 + XDEL） | 同上 | 同一张表，`target_pod IS NULL` |
| RPC 响应 | `RpcClient`、`RpcResponseSubscriber`、`InProcessCommandHandler` | `coreai:rpc:*`（pub/sub） | 请求-响应 | **pod 之间直接走 HTTP**（7.5） |
| SSE 事件广播 | `EventPublisher`、`EventSubscriber`、`SseEventBridge` | `coreai:sse:*`（psubscribe） | 广播到所有 pod | **定向投递**（7.6），不经过数据库 |
| 会话归属租约 | `SessionOwnershipRegistry` | TTL 60s | SET NX EX，Lua 比较后删除 | `messaging.session_ownership`，有效性跟随 pod 心跳（7.4） |
| 运行中的 turn | `TurnStateRegistry` | `coreai:rturn:*`，TTL 90s，每 30s 续期 | 租约 | `messaging.turn_states`，同样跟随 pod 心跳 |
| 用户会话预算 | `UserSessionBudget` | `coreai:rsess:*`（INCR/DECR） | 计数器 | `messaging.user_session_counts(user_id, pod_id, count)`，取总数用 SUM |
| 会话活跃度 | `SessionActivityRegistry` | 带 TTL 的 key | 过期标记 | 表 + `expires_at` + 定时清理 |
| sandbox 绑定 | `SandboxRedisStore` | 无 TTL | 持久化的映射 | 普通表 `app.sandbox_bindings` |
| A2A 任务快照 | `A2ATaskRegistry` | `coreai:a2a:*`（SETEX） | 带过期时间的缓存 | 表 + `expires_at` |
| A2A 事件 | `A2AEventRelay` | pub/sub | 推送到订阅方 | 和 SSE 走同一套定向投递 |

core-ng 的 `cache()` 如果配置成了 Redis，也要改为本地缓存（需要逐个检查哪些缓存要求跨 pod 一致）。

### 7.2 pod 注册表：所有租约的基础

```sql
CREATE TABLE messaging.pods (
    pod_id       text PRIMARY KEY,          -- POD_NAME
    address      text NOT NULL,             -- POD_IP:port, for internal HTTP
    started_at   timestamptz NOT NULL,
    heartbeat_at timestamptz NOT NULL
);
```

- 每个 pod 每 10 秒更新一次自己的 `heartbeat_at`；超过 30 秒没有心跳就视为死亡；
- **租约的有效性不再按每条记录续期，而是看持有者的心跳**：`session_ownership`、`turn_states` 只存 `owner_pod`，有效的条件是 `owner_pod` 的心跳仍然新鲜。这样续期的写入量从"每个会话每 30 秒一次"降为"每个 pod 每 10 秒一次"；
- 清理任务（每分钟执行，用 advisory lock 保证只有一个 pod 在跑）：删除已死亡 pod 的租约、计数行和注册记录；把发给已死亡 pod 的未消费命令改为 `target_pod = NULL`，交给其它 pod 处理；
- pod 正常关闭时主动删除自己的行，并把自己队列里的命令转给其它 pod（取代现在 shutdown drain 时把命令重新发到 unowned stream 的做法）。

### 7.3 命令队列

```sql
CREATE TABLE messaging.session_commands (
    id         bigserial PRIMARY KEY,
    target_pod text,                       -- null = unowned
    session_id text NOT NULL,
    type       text NOT NULL,
    payload    text NOT NULL,
    visible_at timestamptz,                -- lease; null = deliverable
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_session_commands_target ON messaging.session_commands (target_pod, id);
```

- **发布**：`INSERT` 之后执行 `SELECT pg_notify(?, ?)`（通道名是 `cmd_` + pod 的 hash，没有归属的命令用 `cmd_unowned`；payload 为空，只用来唤醒消费方）；
- **消费**（保持现在"处理失败/进程崩溃后仍会重新投递"的语义）：

  ```sql
  UPDATE messaging.session_commands
     SET visible_at = now() + make_interval(secs => ?)      -- lease, e.g. 60s
   WHERE id IN (SELECT id FROM messaging.session_commands
                WHERE target_pod = ? AND (visible_at IS NULL OR visible_at < now())
                ORDER BY id LIMIT 32 FOR UPDATE SKIP LOCKED)
  RETURNING id, session_id, type, payload
  ```

  处理完成后按 id `DELETE`。不要改成"先删再处理"：现在的实现是 `handle()` 之后在 `finally` 里 `XDEL`，删除发生在处理之后；先删会在进程崩溃时直接丢掉命令。租约过期即重新可见，失败的原因至少还能重试。

  没有归属的命令用 `WHERE target_pod IS NULL`，多个 pod 并发消费由 `SKIP LOCKED` 保证互不重复；
- **唤醒**：每个 pod 用一条专用的原生 JDBC 连接执行 `LISTEN`（core-ng 的连接池不支持 `getNotifications`），收到通知后立即拉取；另外每秒兜底轮询一次，因为 NOTIFY 丢失或连接重连时不能漏掉命令；
- **这些语句都走 `Database.select/execute`**（`RETURNING` 映射到只读 view 类），不需要原生 JDBC；只有 `LISTEN` 用专用连接；
- **负载**：命令只在用户发消息、审批工具、取消等操作时产生，量级是每秒几十条。NOTIFY 的全局提交锁在这个量级下不是问题，**但不要把 SSE 事件放到 NOTIFY 上**；
- `CommandType` 和 payload 的格式不变，只替换传输层。`CommandPublisher.resolveTargetStream` 改为查询 `session_ownership` 来决定 `target_pod`。

### 7.4 租约与计数

```sql
CREATE TABLE messaging.session_ownership (session_id text PRIMARY KEY, owner_pod text NOT NULL, acquired_at timestamptz NOT NULL);
CREATE TABLE messaging.turn_states (session_id text PRIMARY KEY, owner_pod text NOT NULL, turn_id text NOT NULL, started_at timestamptz NOT NULL);
CREATE TABLE messaging.user_session_counts (user_id text NOT NULL, pod_id text NOT NULL, count integer NOT NULL, PRIMARY KEY (user_id, pod_id));
```

- 获取归属（原子操作，只有原持有者已死亡或者就是自己时才能抢到）：

  ```sql
  INSERT INTO messaging.session_ownership (session_id, owner_pod, acquired_at) VALUES (?, ?, now())
  ON CONFLICT (session_id) DO UPDATE SET owner_pod = EXCLUDED.owner_pod, acquired_at = EXCLUDED.acquired_at
  WHERE messaging.session_ownership.owner_pod = EXCLUDED.owner_pod
     OR NOT EXISTS (SELECT 1 FROM messaging.pods p WHERE p.pod_id = messaging.session_ownership.owner_pod
                    AND p.heartbeat_at > now() - interval '30 seconds')
  RETURNING owner_pod
  ```

  注意：SQL 里不能出现单引号，所以 `interval '30 seconds'` 要改为 `make_interval(secs => ?)`，以参数绑定。本节的所有 SQL 都要这样处理；
- 释放：`DELETE ... WHERE session_id = ? AND owner_pod = ?`，对应现在 Lua 脚本的"比较后删除"；
- 用户会话计数：每个 pod 只更新自己那一行（`INSERT ... ON CONFLICT DO UPDATE SET count = count + ?`），取总数用 `SUM(count)`。pod 死亡时清理任务删除它的行，这一点**比 Redis 版更好**：Redis 版在 pod 崩溃时计数会泄漏。

### 7.5 RPC 改为 pod 之间直连 HTTP

- 内部接口 `POST /_internal/commands/{type}`，调用方从 `messaging.pods` 查到目标 pod 的地址，直接发起同步调用，超时时间沿用现在 RPC 的设置；
- **安全**：所有 `/_internal/*` 接口都要校验共享密钥请求头（`X-Internal-Token`，来自 k8s Secret），并且确认 Envoy 的 HTTPRoute **没有**把 `/_internal` 暴露到外部；
- 目标 pod 不可达（已经死亡）时：先清掉它持有的归属，再按无主命令的流程处理，或者向调用方返回明确的错误，具体按命令类型决定（沿用现在 RPC 超时时的行为）。

### 7.6 SSE 定向投递（前置条件，建议单独出一份详细设计）

现在每个事件都会广播到所有 pod（`coreai:sse:*`）。改为：

1. **owner pod 持有事件源**：`SessionEventHub`（每个会话一个），负责分配递增的 seq，并保留 replay buffer。现在 buffer 放在订阅方的 `SessionChannelService` 里，这正是 d06554b0"owner 是自己就不发 Redis"那次尝试失败的原因：用户重连到另一个 pod 时拿不到之前的事件；
2. **订阅方 pod 拉取**：用户的 SSE 连接落在 pod B 上，而会话的 owner 是 pod A 时，B 向 A 发起内部 HTTP 流式请求 `GET /_internal/sessions/{id}/events?after={seq}`，再转发给浏览器；断线后带上最后收到的 seq 重连，A 从 buffer 里补发；
3. **owner 变更**：通过 NOTIFY 发一个低频信号（`owner_changed`，只在归属变更时发送），同时每 5 秒检查一次 `session_ownership` 作为兜底；订阅方发现 owner 变了，就转去连接新的 owner；
4. **灰度开关**：`sys.sse.delivery=broadcast|relay`，两种模式并存，按环境逐步切换；
5. **待确认**：`/messages/stream` 在服务端主动关闭时前端的行为（会不会自动重连、会不会带上 seq），这决定第 2 步的断线续传能否完全对前端透明。

### 7.7 切换方式

每个组件一个开关，按风险从低到高依次切换，每一步在 UAT 至少观察 1 周：

| 顺序 | 组件 | 开关 |
|---|---|---|
| 1 | sandbox 绑定、A2A 快照、会话活跃度 | `sys.state.backend=redis\|postgres` |
| 2 | pod 注册表 + 租约 + 计数 | `sys.lease.backend` |
| 3 | RPC 改为 HTTP | `sys.rpc.transport=redis\|http` |
| 4 | SSE 定向投递 | `sys.sse.delivery=broadcast\|relay` |
| 5 | 命令队列 | `sys.command.backend=redis\|postgres` |
| 6 | 去掉 Redis：删除 `JedisConfig`，`StartupDependencyCheck` 不再探测 Redis，下线 Redis 实例 | — |

命令队列切换时，旧 pod 往 Redis 发、新 pod 往 PG 发，命令会在两边各自积压一部分。解决办法：过渡版本中新 pod **同时消费** Redis 和 PG 两边的队列，等全部 pod 都切换完成后，再发一个版本去掉 Redis 消费。

### 7.8 阶段 3 验收

- 第 7.7 节的开关全部切到 PG 后，UAT 稳定运行 2 周，生产稳定运行 2 周；
- 压测：对 100 个并发会话做发消息、审批、取消、关闭这几类操作，命令端到端延迟的 p99 不比 Redis 版差 50ms 以上；
- 故障演练：杀掉会话的 owner pod，会话在 30–60 秒内被其它 pod 接管，SSE 能续上，没有重复执行的命令；
- Redis 实例下线。

## 8. 阶段 4：Mongo 存量策略

- **新功能默认用 PG。** 新建 Mongo 集合需要在设计文档里说明理由；
- **按价值迁移，不按数量。** 优先迁移和报表、分析相关的集合，因为它们能直接从 SQL 获益：`AnalyticsDailyStats`、`TraceDailyStats`、`RuntimeSample` 的统计，agent run 的历史；
- 普通 CRUD 实体（配置、定义类）在 Mongo 里没有问题，不为了统一而迁；
- 每迁一个集合都沿用阶段 1 的方法：store 接口 + 迁移工具 + 影子读 + 开关 + 保留 30 天；
- 什么时候彻底去掉 Mongo：剩余集合的维护成本超过迁移成本时再立项，不设截止日期。

## 9. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| core-ng `Database` 在 PG 上的未知限制（方言、RETURNING、池行为） | 阶段 1、2 返工 | 阶段 0 第一周完成 3.4 的验证，并提前确定退路 |
| "三个中间件"的窗口期拖长 | 违背初衷 | 阶段 1 与阶段 3 同一项目立项；阶段 3 在阶段 1 开始后第 3 周启动 |
| 迁移丢数据或数据不一致 | 用户数据受损 | 幂等的 upsert、增量同步、删除对账、影子读、Mongo 保留 30 天、反向回写 |
| 重建视图时被长查询阻塞 | 查询排队 | `lock_timeout = 3s` + 重试；报表查询设 `statement_timeout` |
| 建表达式索引阻塞写入 | 写入卡住 | 只用 `CONCURRENTLY`，异步执行，失败后清理 INVALID 索引 |
| 存储约为 Mongo 的 3.5 倍 | 成本上升 | 阶段 0 摸底；如果 GIN 利用率低（`pg_stat_user_indexes`），考虑删除 |
| session state 的热点行写放大 | 写入延迟 | 监控单行更新频率；state 接近 256KB 时考虑拆分 |
| reader 角色越权 | 数据泄露 | schema 隔离；视图带 `security_invoker`；`DatasetSqlExecutorTest` 的越权用例纳入 CI |
| LISTEN 连接断开 | 命令延迟 | 每秒兜底轮询；连接断开后自动重建 |
| 内部 HTTP 接口暴露 | 安全风险 | 共享密钥校验 + 在 Envoy 路由层确认不对外暴露 |

## 10. 待决事项

| # | 事项 | 建议 | 决策人 |
|---|---|---|---|
| D1 | PG 实例形态与规格 | Azure Flexible Server PG 16 | 运维 + 你 |
| D2 | `datasets` 元数据是否随阶段 1 一起迁 | 是（为了事务一致性） | 你 |
| D3 | 写入类型校验是拒绝还是宽松转换 | 按 4.3 的规则：能转换的转换，其余拒绝 | 你 |
| D4 | 是否需要 DATE/DATETIME 字段类型 | 是 | 你 |
| D5 | SQL 报表与 agent 自由 SQL 何时开放 | v1 关闭，按 6.9 节的数据决定 | 你 |
| D6 | 阶段 3 由谁负责、与阶段 2 是否并行 | 并行 | 你 |
| D7 | 生产环境是否开启影子读 | UAT 必须开启；生产按 10% 采样开启 | 你 |

## 11. 实现约束（摘自 AGENTS.md 和 PoC）

- 注释只用英文，只写类级 javadoc；新文件要纳入 git，作者与 git author 一致；
- core-ng：不使用 `Repository` / 实体映射；行类只用 `@Column`，动态数据一律 `String` + `JsonUtil`；
- PG 上的 SQL：不能有单引号（常量一律绑定为参数，`'.'` 写成 `chr(46)`，interval 用 `make_interval(secs => ?)`）；不能绑定数组；DDL 只能通过 flyway 或 `PgDdl` 执行；jsonb 的 `?` 运算符改用 `jsonb_exists`；
- 用于 JSON 合并补丁和过滤的序列化一律用 `JsonUtil.toJsonWithNulls`（见 502777eb）；
- 枚举：Mongo 实体用 `@MongoEnumValue`，对外 view 用 `@Property`；PG 侧按 text 存储、手动转换；
- checkstyle 规定单个文件不超过 450 行，SQL 编译器、迁移工具这类大类要提前拆分。

## 附录 A：数据摸底结果

（阶段 0 填写：各环境的 dataset 数、记录数、最大的 20 个 dataset、data 非法的记录数、存量类型不匹配统计。）

## 附录 B：core-ng 兼容性验证结果

（阶段 0 填写：3.4 节每一项的结论和退路。）
