# Dataset on PostgreSQL — PoC 结果报告

> 状态：已完成（2026-11-02）
> 设计：[design-dataset-postgresql-poc.md](design-dataset-postgresql-poc.md)
> 代码：`core-ai-server/src/main/java/ai/core/server/dataset/store/`

## 1. 结论

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| Q1 | core-ng 9.4.2 连 PG：jsonb 读写、`ZonedDateTime` ↔ `timestamptz`、view 映射 | **通过（带 3 处适配）** | 契约测试 15 项全绿（含 `timestampsRoundTripAsInstants`）；适配见第 3 节 |
| Q2 | Java 侧 `data` 保持 `String`，`?::jsonb` 写入、`data::text` 读出 | **通过** | 未新增 `Map<String, Object>` 字段，未触发 `ClassValidator` |
| Q3 | 现有过滤语义完全下推到 SQL，无 10k 扫描上限 | **通过** | `DatasetRecordStoreContractTest` 对 PG、mongo-native 各跑一遍；PG `truncated` 恒为 false、`total` 精确 |
| Q4 | `$gt/$gte/$lt/$lte/$ne/$in/$nin/$exists/$contains` 安全翻译 | **通过** | `DatasetFilterSqlTest` 9 项；SQL 文本中不含用户输入，参数全部绑定 |
| Q5 | RFC 7386 原子合并 + session state upsert | **通过** | 50 线程 × 100 次并发 patch，5000 个字段全部保留（PG 93s） |
| Q6 | 数据库内聚合（分桶 + 分类 + SUM/AVG/MIN/MAX/COUNT/LAST） | **通过** | PG 与 mongo-native 结果一致；基准见第 5 节 |
| Q7 | 每个 dataset 一张视图，schema 变更重建 | **通过** | 表名/列名规范化、保留字、中文、注入型字段名、重名、加删字段、改类型全部覆盖 |
| Q8 | agent 只读 SQL（RLS） | **有条件通过** | 越权/写/`set_config`/超时全部拦住，但**设计里的 `SET LOCAL ROLE` 方案可被绕过**，改用独立登录角色（第 3.5 节） |
| Q9 | 性能 | **有条件通过** | 聚合 p95 277/234ms 达标；等值 PG 约 6 倍、范围（视图 + 同表达式索引）约 5 倍；mongo 的 wildcard 索引因语义守卫不可用，见第 5 节 |
| Q10 | 对照组：Mongo 原生 driver 能否达到同等能力 | **通过，但语义/性能有差距** | 同一套契约测试通过；差异见第 6 节 |
| Q11 | （可选）LLM 写 SQL vs 写 aggregation pipeline 的准确率 | **未执行** | 需要真实模型调用与人工标注，超出本次 PoC 的自动化范围 |

**一句话结论：** PG 在"查询能力 / 语义一致性 / 视图与 SQL 可读性"上明显胜出，
mongo-native 只解决了"过滤与聚合下推"，却留下一批需要逐条修补的语义陷阱；
PG 的性能优势真实存在（索引随 schema 字段自动可用），但要落地必须先解决 core-ng 的 DDL/EXPLAIN 限制。
建议按第 7 节的路线推进。

## 2. 交付物清单

| 内容 | 位置 |
|---|---|
| 存储接口 + 三个实现 | `dataset/store/DatasetRecordStore.java`、`MongoDatasetRecordStore`、`MongoNativeDatasetRecordStore`、`PostgresDatasetRecordStore` |
| 过滤翻译 | `DatasetFilterSql`（jsonb 路径）、`DatasetViewFilterSql`（视图列）、`MongoNativeFilter` |
| 聚合 | `DatasetAggregationSql`、`MongoNativeAggregation` |
| 视图 + 字段索引 | `DatasetViewManager`（`sync`/`drop`/`indexField`）、`PgDatasetViewRow` |
| 只读 SQL 执行器 | `DatasetSqlExecutor`（第三阶段） |
| 建表/函数 | `DatasetPgSchemaInitializer`、`PgDdl` |
| null 保留序列化 | `JsonUtil.toJsonWithNulls`（patch/filter 共用） |
| 契约/并发/视图/执行器测试 | `core-ai-server/src/test/java/ai/core/server/dataset/store/` |
| 基准 | `DatasetBenchmarkTest`（`CORE_AI_BENCHMARK=true` 时运行） |
| 本地 PG | `docker-compose.local.yml` 的 `postgres` 服务（16-alpine） |

## 3. core-ng 9.4.2 的限制与绕过方式

这些是实现过程中实际撞到的问题，设计文档里没有覆盖。

### 3.1 不能绑定数组参数

`DatabaseOperation.setParam` 只支持 String/Enum/日期/Boolean/Integer/Long/Double/BigDecimal/UUID。
设计里的 `?::text[]`、`?::jsonb[]`、`unnest(?::text[])` 都不可用。改为：

- 字段路径：`data #> string_to_array(?, chr(46))`，路径作为一个 String 绑定；
- `$in`：`data #> ... IN (SELECT jsonb_array_elements(?::jsonb))`，列表作为一个 JSON 文本绑定。

`chr(46)` 而不是 `'.'`，原因见 3.2。

### 3.2 `validateSQL` 禁止 SQL 里出现单引号

`QueryInspector.validateSQL` 要求 SQL 文本不含 `'`（也只允许 `CREATE` 开头绕过）。
因此所有字面量都必须变成参数：`jsonb_typeof(...) = ?::text`、`date_trunc(?, ...)`、
`chr(46)` 代替 `'.'`。这也顺带保证了注入面为零。

### 3.3 `EXPLAIN` 会作用在所有语句上，包括 DDL

9.4.2 的 `QueryInspector.explain` 没有 DDL 白名单（源码 `d:\core-ng-project` 的版本比 jar 新，
jar 里没有 `CREATE`/`DROP` 的 early return）。`CREATE TABLE` / `CREATE VIEW` 会被拼成
`EXPLAIN CREATE ...` 直接语法错误。结论：

- **建表、建视图、`ANALYZE` 必须走原生 JDBC**，新增 `PgDdl`（打开连接、`autoCommit=false`、
  执行、提交/回滚）；
- DML（INSERT/UPDATE/DELETE/SELECT）继续走 `Database`，`EXPLAIN` 对它们是合法的。

`DatasetPgSchemaInitializer` 与 `DatasetViewManager` 因此都依赖 `PgDdl`，而不是 `Database.execute`。

### 3.4 视图/聚合的"动态列数"

core-ng view 的字段数是编译期固定的，聚合结果列数随 metric 数量变化。做法是
`jsonb_build_array(...)::text AS payload`，映射到只有 `@Column(name = "payload") String payload`
的 `PgJsonRow`，再在 Java 里解析。视图侧则是"固定的系统列 + 按 schema 生成的列"，
每列显式 `AS "列名"`。

### 3.5 RLS 方案：设计里的 `SET LOCAL ROLE` 不安全（重要）

设计第 7.3 节用 `INSERT grants(txid, dataset_id)` + `SET LOCAL ROLE dataset_reader`。
`role` 是 USERSET 参数，agent 只要在 SQL 里写
`SELECT set_config('role', 'none', true)` 就会**恢复会话用户（应用账号）**，RLS 直接失效。
把 grants 表本身也加上 RLS 也挡不住这一步。

改成：

- `dataset_reader` 是**独立 LOGIN 角色**（不是 `SET ROLE`），连接本身就只拥有 SELECT 权限；
- 授权用**随机 token**：应用连接写入 `dataset_sql_grants(token, dataset_id, created_at)`，
  reader 连接 `SET LOCAL app.dataset_token = '<uuid>'`；
- `dataset_records` 与 `dataset_sql_grants` 两张表都对 reader 开 RLS，
  只放行 `token = current_setting('app.dataset_token', true)` 的行；reader 看不到别人的 token；
- `RESET ROLE` / `set_config('role', ...)` 因为会话用户本来就是 reader，已无提权空间；
- 执行完一律回滚，超时 5s、最多 1000 行。

`DatasetSqlExecutorTest` 覆盖了：只能看被授权的 dataset、直接查基表只返回授权行、
`INSERT`/`COPY`/`pg_sleep(6)`/多语句/`set_config('role','core_ai')` 全部被拒绝，
以及 `current_user = dataset_reader_test`。

### 3.6 视图同时作为内部查询入口（修正了"表达式索引无效"的结论）

最初的基准把索引建在 `data #> '{positive_count}'` 上，查询却用
`data #> string_to_array(?, chr(46))`，视图里又是 `data -> 'positive_count'` ——
三种表达式互不相同，索引当然用不上。这不是 PG 的限制，是基准自己写错了。

修正后的做法：

- 当过滤字段全部是 schema 里的简单字段时，`PostgresDatasetRecordStore.query` 改查视图
  （`WHERE "positive_count" > ?`），类型化比较由 `DatasetViewFilterSql` 生成；
- 索引用 `DatasetViewManager.expression()` 生成，和视图列完全相同
  （`CREATE INDEX ... ON dataset_records (dataset_id, (<同一个 CASE 表达式>))`）；
- 三条视图无法安全表达的语义**整体回退到 jsonb**：
  1. `null` / `$ne: null` —— 视图列把"字段缺失""显式 null""类型不匹配"都变成 NULL，
     只有 jsonb 路径能区分；
  2. `$exists` —— 同上；
  3. 嵌套路径、对象/数组值。
- 视图内的 `$nin` 显式写成 `(col IS NULL OR NOT (col IN (...)))`，否则缺字段的记录会被漏掉；
- 视图里的 STRING 列加了类型保护
  （`CASE WHEN jsonb_typeof(data -> k) = 'string' THEN data ->> k END`），
  所以 STRING 字段里存了数字/对象时，视图里显示为 NULL —— 这是有意的取舍；
- 基准里用 EXPLAIN 确认命中（`Bitmap Index Scan on ix_ds_benchhot_positive_count`）。

代价：每次带过滤的查询多两次元数据读（dataset schema + `dataset_views`），
生产上可以考虑缓存。

**防回归：** 契约测试现在跑两遍 —— 无 schema（jsonb 路径）和带 schema（视图路径），
同一批用例约束两条路径，另有 `$nin` 缺字段、`$ne: null` 类型不匹配、STRING 类型保护三条专门用例。

### 3.7 其它

- **线上 bug（与选型无关，已修）：** `JsonUtil` 的 ObjectMapper 配了 `NON_NULL`，`Map` 里的 null
  会被丢掉。`UpdateSessionStateTool` 和 `HubDatasetService.updateState` 在调用 `patchBySession`
  之前先用 `JsonUtil.toJson(data)` 序列化，于是 `{"x": null}` 到达 store 之前 null 就已经没了 ——
  生产环境里通过 `update_session_state` 和 hub `state.patch` **都删不掉字段**。
  修法：`JsonUtil` 新增 `toJsonWithNulls`（`Include.ALWAYS`），两个入口改用它；
  store 内部的过滤/更新同样使用它。回归测试覆盖到工具和 hub 两个入口。
  这个 bug 与存储无关，建议和 `merge` 语义修正一起单独提交。
- `DatasetRecordService.merge` 原来在"patch 是对象、原值不是对象"时直接写入子对象，
  会保留子对象里的 null；按 RFC 7386 修正为递归合并（与 SQL 版一致），
  现有 Mongo 实现也随之修正（其 javadoc 本来就声明 RFC 7386）。
  这一处会改变默认 Mongo 路径的线上行为，同样应作为独立修复提交。
- Mongo-native 需要显式建索引：本地 `mongo:7` 开了 `notablescan`，没有索引连 upsert 都报
  "No indexed plans available"。`MongoNativeDatasetRecordStore.ensureIndexes()` 在模块启动时调用。

## 4. 测试结果

| 测试类 | 用例数 | 说明 |
|---|---|---|
| `DatasetRecordStoreContractTest`（PG，jsonb 路径） | 16 | 点路径/数字/字符串/null/对象/数组/范围/分页/裁剪/update/delete/session/patch/大小限制/时间往返 |
| `DatasetRecordStoreContractTest`（mongo-native） | 16 | 同一套契约，全部通过 |
| `PostgresDatasetRecordStoreTest` | +6（共 22） | 聚合（分桶/分类/无桶/校验）、超限回滚、视图路由与 jsonb 回退 |
| `PostgresViewDatasetRecordStoreTest` | 19 | **同一套契约 + schema**（视图路径），另加 `$nin` 缺字段/`$ne: null` 类型不匹配/STRING 类型保护 |
| `MongoNativeDatasetRecordStoreTest` | +3（共 19） | 聚合与 LAST |
| `DatasetFilterSqlTest` | 9 | SQL 不含用户输入、参数顺序、操作符、LIKE 转义 |
| `DatasetViewManagerTest` | 5 | 列名规范化/重名/保留字/中文/注入名、改类型、删字段、drop |
| `DatasetSqlExecutorTest` | 6 | 授权隔离、基表 RLS、当前角色、写/超时/多语句/提权拦截 |
| `SessionStateToolTest` / `HubDatasetServiceTest` | 各 +1 | 入口层 null 保留（删字段）回归 |
| `MongoDatasetRecordStoreTest` | 24 | 现有 Mongo 行为回归（mock） |
| `PostgresConcurrencyTest` / `MongoNativeConcurrencyTest` | 各 1 | 50 线程 × 100 次 patch |

没有 PG/Mongo 环境时，集成用例由 `@EnabledIfEnvironmentVariable` 自动跳过，
默认 `./gradlew test` 不受影响。

**并发测试的耗时：** PG 跑完 5000 次 patch 约 93 秒（5000 次都打在同一个 session 行上，
每次重写整个 jsonb 文档并更新 GIN 索引）。真实 session state 基本不会有 50 个并发写者，
所以不是阻断问题；但如果 state 接近 256KB 上限且写入频繁，这个开销会变得明显。

**构建状态：** `:core-ai-server:test`（全量，默认配置）、`checkstyleMain/Test`、`pmdMain/Test`、
`spotbugsMain` 全部通过。`spotbugsTest` 在本机因 `com.google.auth.oauth2.GoogleCredentials`
不在 test runtime 的 aux classpath 而 exit 3 —— 这是既有构建配置问题（`google-auth` 是
`compileOnly`），把新增测试临时移除后同样复现，与本 PoC 无关。

## 5. 基准测试（本机，1M 行，200 次迭代）

配置：100 个 dataset；热点 `bench-hot` 20 万行；其余 99 个各 8 千行，共 99.2 万行；
时间均匀分布在 90 天内。schema：`positive_count/negative_count/total: NUMBER`、
`status/region: STRING`、`flagged: BOOLEAN`。每条场景预热 10 次后跑 200 次。
跑之前先 `DROP INDEX`（避免上一轮遗留的索引污染"无索引"场景），存储测量前 `VACUUM FULL`。

| 场景 | 现有 Mongo（core-ng） | mongo-native p50/p95/p99 | PG p50/p95/p99 | 结论 |
|---|---|---|---|---|
| 等值 `status=done`，limit 100 | 10k 扫描上限，`truncated=true` | 808 / 1204 / 1498 ms | **139 / 219 / 362 ms** | PG 快约 6 倍；mongo 的成本主要在精确 `countDocuments` |
| 同上，mongo 加 `data.$**` wildcard 索引 | — | 828 / 989 / 1080 ms | — | 索引被选中，但 ~50% 选择性下仍要回表，计数成本不变（见下方 explain） |
| 同上，去掉守卫的裸查询（`mongo-raw`） | — | 348 / 438 / 593 ms | — | 更快，但语义错误（标量会匹配数组元素） |
| 范围 `positive_count > 50`，视图路径、无索引 | 不支持（内存过滤） | 209 / 261 / 281 ms（mongo 有字段索引） | 148 / 205 / 229 ms | 基本持平 |
| 同上，用视图同表达式建索引 | — | — | **31 / 39 / 43 ms** | **索引命中**，p50 约 3–4 倍提升 |
| 30 天按天 `SUM(total)`、`AVG(positive_count)` | 不支持（应用层算） | 235 / 319 / 380 ms | **216 / 277 / 437 ms** | p95 277ms，达标；p99 偏高 |
| 按 region + 按天 `SUM(total)` | 不支持 | 240 / 302 / 326 ms | **183 / 234 / 254 ms** | p95 < 300ms，达标 |
| 视图 `SELECT region, sum(total) FROM ds_x GROUP BY 1` | 不支持 | 需手写等价 pipeline | **283 / 352 / 427 ms** | 可用；与本机负载相关，波动较大 |
| 单条 insert | — | 9 / 16 / 20 ms | 5 / 9 / 12 ms | 接近 |
| 单条 patch | — | 7 / 12 / 17 ms | 5 / 8 / 10 ms | 接近 |
| 50 线程并发 patch 丢字段数 | 未跑（读-改-写实现，按代码分析会丢更新） | 0（CAS 只在路径冲突时触发） | 0 | PG/mongo-native 通过 |

**Mongo wildcard 索引的 explain**（20k 文档、50% 选择性，`mongosh` 副本；完整基准是 200k）：

```
--- guarded   ({'data.status':'done'} + {'data.status':{$not:{$type:'array'}}})
winningPlan = FETCH(filter: $not $type) <- IXSCAN(index: data.$**)
keysExamined=10000 docsExamined=10000 returned=10000
--- no-guard  ({'data.status':'done'})
winningPlan = FETCH <- IXSCAN(index: data.$**)
keysExamined=10000 docsExamined=10000 returned=10000
```

结论修正：wildcard 索引**并没有被守卫挡住**，两条查询都走了 IXSCAN；
没有改善的原因是选择性太低（~50%），索引扫描仍要回表一半文档，精确 `count` 把这个成本放大了。
去掉守卫的裸查询更快，但语义不对；不能作为优化手段。

**PG 索引命中的证据**（EXPLAIN 的 count 查询，`positive_count > 50`）：

```
Aggregate  (cost=72636.97..72636.98 rows=1) (actual time=28.544 ms)
  ->  Bitmap Heap Scan on dataset_records
        Recheck Cond: ((dataset_id = 'bench-hot') AND (CASE WHEN jsonb_typeof((data -> 'positive_count')) = 'number'
                      THEN ((data ->> 'positive_count'))::numeric ELSE NULL::numeric END > '50'::numeric))
        ->  Bitmap Index Scan on ix_ds_benchhot_positive_count
              Index Cond: ((dataset_id = 'bench-hot') AND (CASE WHEN jsonb_typeof(...) = 'number' THEN (...)::numeric ... > '50'))
```

即：过滤走视图列、索引由同一个 `DatasetViewManager.expression()` 生成，planner 能把视图展开成索引用到的表达式。

灌数据（100 万行）耗时：PG + Mongo 合计约 95 秒。

存储占用（99.2 万行）：

- PG：`pg_total_relation_size('dataset_records')` ≈ **755 MB**（fresh seed + 热点字段表达式索引；
  含主键、4 个 B-tree、1 个 jsonb GIN）。
- Mongo-native：逻辑大小 `size` ≈ 319 MB，WiredTiger 压缩后 `storageSize` ≈ 59 MB，
  索引 `totalIndexSize` ≈ 171 MB（含 wildcard 与 `(dataset_id, data.positive_count)`），
  合计约 230 MB。

PG 约为 Mongo 的 3.3–3.6 倍：jsonb 以二进制存储且未压缩，GIN 索引对每个 key 建条目；
Mongo 的 WiredTiger 默认压缩，但索引占了大头。dataset 线上数据量不大时可以忽略，
数据量大再评估压缩/分区。

> **关于数字的说明：** 本机同时有另一个构建任务在跑，同一场景在不同轮次间有 ±40% 的波动
> （例如等值过滤 PG 在 55–139ms 之间）。结论看数量级与 EXPLAIN，不要依赖单次毫秒值。
> 所有数字都包含精确 `count(*)`；如果允许"估算 total"，两边都能再快一个量级。

## 6. 语义差异清单

### 6.1 PG vs 现有 Mongo

| 语义点 | 现有 Mongo（core-ng 映射） | PG | 结论 |
|---|---|---|---|
| `data` 存储 | JSON 字符串 | jsonb | PG 可直接过滤/聚合 |
| 过滤上限 | 1 万条扫描，`truncated=true` | 无上限，`total` 精确 | PG 胜 |
| 数字比较 | BigDecimal | jsonb numeric | 一致 |
| 字符串 vs 数字 | 类型不同不相等 | jsonb 路径一致；视图路径的 STRING 列加了 `jsonb_typeof` 保护，非字符串值显示为 NULL | 一致 |
| null 过滤 | 缺失或显式 null | 同上 | 一致 |
| 对象相等 | key 顺序无关 | jsonb 相等 key 顺序无关 | 一致 |
| 数组相等 | 完全相等 | `#>` 深比较，完全相等 | 一致 |
| patch 合并 | 读-改-写，并发丢更新 | `jsonb_merge_patch` 原子 | PG 胜 |
| 会话 upsert | 捕获取重复键后重试 | `ON CONFLICT` | PG 更简单 |
| 字段名限制 | 无 | 无 | 一致 |

### 6.2 mongo-native vs 现有 Mongo / PG

| 语义点 | mongo-native 的行为 | 处理 |
|---|---|---|
| 标量 vs 数组 | Mongo `eq` 会匹配数组元素 | 加 `$not: {$type: "array"}` |
| 对象相等 | BSON 文档比较区分字段顺序 | `$setEquals($objectToArray(...))`，仅一层；更深层仍按顺序 |
| 数组相等 | 可能匹配"外层数组元素" | `$expr: {$eq: [...]}` |
| `null` | 等价 | 无需修正 |
| `3` vs `3.0` | Mongo 数值跨类型相等 | 一致 |
| `"3"` vs `3` | 类型不同 | 一致 |
| 数字比较 | 类型括号，不会匹配字符串 | 一致 |
| 字段名 | 不能含 `.` 或以 `$` 开头 | 写入前校验（PG 无此限制） |
| 聚合 | `$dateTrunc` 需要 5.0+ | 本地 mongo:7 可用；需确认 UAT/生产版本 |
| 大小限制 | 原子 patch 后无法回滚超限 | 小 patch 走原子路径，>8KB 走 CAS 预检 |

## 7. 选型建议

**结论：真正决定选哪条路的是 Q11（是否让 agent 自己写 SQL 分析数据），而不是这一轮的基准数字。**

- 如果近期产品形态是"工具化查询 + 固定报表"：**选 mongo-native**。它不引入新组件，
  改动能收敛在 `dataset/store/`，同一套契约测试已证明能达到 Q3–Q6 的能力。
- 如果"动态表结构"的本意是让 agent / 用户像查表一样直接分析 dataset：
  **PG 的视图 + RLS 是 Mongo 给不了的能力**（Q7/Q8），值得把 Q11 补做后再定方案。

补充说明：

1. **性能对比的前提已修正**：最初"PG 快 9 倍"来自不公平的对比 —— PG 有 jsonb GIN，
   mongo-native 在 `data.status` 上没有索引。补测 wildcard 索引 `data.$**` 后，
   等值过滤仍没有改善：explain 显示索引确实被选中，但 ~50% 的选择性下仍要回表一半文档，
   精确 `count` 把成本放大了；去掉语义守卫的裸查询才快（2.3 倍），但那样会引入
   "标量匹配数组元素"的错误语义。
   也就是说，**Mongo 的索引只在"高选择性字段"上才有明显收益**，而 PG 的 GIN/表达式索引
   对这类"中等选择性 + 精确计数"的组合更稳。
2. **表达式索引的问题已解决**：之前基准里索引无效，是因为索引、查询、视图三处用了不同表达式。
   现在 schema 字段的过滤统一走视图列（`WHERE "positive_count" > ?`），
   索引也用同一个表达式生成器创建，范围查询从 148ms 降到 31ms（p50），EXPLAIN 见第 5 节。
   代价是：只有"全部过滤字段都在 schema 里"时才走视图，嵌套路径仍回退到 jsonb。
3. **PG 剩下的硬伤只有 DDL/EXPLAIN**：DDL 不能走 core-ng（已用 `PgDdl` 绕过），
   生产上意味着 schema 管理要独立于框架，最好直接接 flyway。
4. **运维成本仍然是主要决策因素**：多一个数据库意味着备份、监控、连接池、故障域，
   而这一轮的收益集中在"能不能直接查表"这一件事上。

**如果最终要迁 PG，额外收益（Q8）是实打实的**：RLS 把"越权"从应用层白名单变成数据库保证，
且不需要维护一份"禁止的操作符"清单（Mongo 的干净做法只能靠应用层白名单，
见设计 13.6）。

## 8. 转生产的工作清单

### 若选 mongo-native

1. `dataset_records_v2` 的历史数据迁移：把 core-ng 的 `data` 字符串转成子文档，
   逐批 `updateOne`，迁移期间双写。
2. 补齐 `MongoNativeDatasetRecordStore` 的索引（`ensureIndexes` 已实现，需在生产集群确认
   `notablescan` 之外的索引构建窗口）。
3. 等值过滤的精确 `countDocuments` 仍是全表扫描：`data.$**` wildcard 索引会被选中，
   但在中等选择性下仍要回表大量文档，别指望它解决计数成本；要么接受扫描，
   要么为高选择性字段单独建索引。
4. 把 `MongoNativeFilter` 的对象相等/数组相等用例补进现有回归测试。
5. 先修 3.6 节的 null 丢失 bug 和 `merge` 语义（与存储选型无关，应单独发布）。

### 若选 PG

1. 用 `db-migration.gradle.kts` 接管 `DatasetPgSchemaInitializer` 的 DDL，去掉运行时建表。
2. 启动时对 `dataset_views` 缺失或 hash 不一致的 dataset 补建视图（当前只在 create/update 时同步，
   PoC 未做启动补偿）。
3. `StartupDependencyCheck` 增加 PG 探测；连接池、慢查询、`pg_stat_statements` 纳入监控。
4. schema 字段过滤走视图（已实现），并为热点字段用 `DatasetViewManager.indexField` 建
   与视图同表达式的索引；上线前用 EXPLAIN 确认命中。
5. RLS 方案按 3.5 节落地：独立登录角色 + token 授权表 + 两张表的 RLS。
6. Mongo 存量数据迁移（同 mongo-native 第 1 条）。
7. Q11 的 LLM 准确率实验补做后再决定"agent 自由写 SQL"是否上线。
8. 去 UAT 看 `dataset_records` 的实际规模：PG 存储约为 Mongo 的 3.3–3.6 倍，
   数据量小可以忽略，数据量大再考虑压缩/归档。

## 9. 运行方式

```powershell
# 1. 本地依赖
docker compose -f docker-compose.local.yml up -d postgres mongo mongo-init

# 2. 契约/并发/视图/执行器测试（没有环境变量时自动跳过 PG/Mongo 用例）
$env:CORE_AI_PG_TEST_URL="jdbc:postgresql://localhost:5432/core_ai"
$env:CORE_AI_MONGO_TEST_URI="mongodb://localhost:27017/core-ai?replicaSet=rs0&directConnection=true"
.\gradlew.bat :core-ai-server:test --tests "ai.core.server.dataset.store.*"

# 3. 基准（约 10 分钟，含 100 万行灌数据）
$env:CORE_AI_BENCHMARK="true"
.\gradlew.bat :core-ai-server:test --tests "ai.core.server.dataset.store.DatasetBenchmarkTest"
```
