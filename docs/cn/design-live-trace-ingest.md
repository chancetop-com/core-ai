# CLI/Desktop Trace 实时上报 — Tech Design

| 项 | 内容 |
| --- | --- |
| 状态 | REVIEWED（架构评审完成，待确认 §13 关键决策后进入实现） |
| 日期 | 2026-11-27 |
| 影响模块 | core-ai-cli（含 desktop 共享引擎）、core-ai-server、core-ai-frontend |
| DB migration | 无 |
| 架构评审 | agy / `gemini-4-neon`（见 §14） |
| 相关文档 | `docs/cn/design-trace-durable-ingest.md`（trace 落库链路） |

## 1. 用户需求原文

> cli desktop trace 日志要第一时间出现在 server 的 trace 日志中, 不要等 run 结束才出现, 且如果 trace 收集上传失败, 也不能影响 agent 正常流程

编号拆解：

| 编号 | 需求 | 判定标准 |
| --- | --- | --- |
| R1 | 实时出现 | turn 进行中，server trace list 就能查到这条 trace（不是只有本地 `DebugLog`） |
| R2 | 实时更新 | 运行中能看到已完成的 LLM/tool 步骤、模型与 token；结束后终态、输出、总量正确 |
| R3 | 失败隔离 | 采集、序列化、网络重试、服务端拒绝等任何环节失败，都不能让 turn 报错、阻塞或显著变慢 |

## 2. 结论（TL;DR）

1. 新增鉴权端点 `POST /api/traces/ingest/start`：只创建 trace 行（`RUNNING`），**不**创建 span 行。
2. CLI 在 `beforeAgentRun` 立即上报 start；`afterModel` / `afterTool` 上报已完成的 span；`finish` 仍上报今天的全量批次（root + 全部 span + 图片 payloads），同时担任兜底与终态。
3. 服务端修正 trace 级字段的写入规则：**只有批次里带 root span 时才允许写 trace 的 status/input/output/duration/completedAt**。否则增量 span 批次会把运行中的 trace 写成 `COMPLETED`，并用子 span 的输出覆盖 trace 输出。
4. 服务端在 compact/offload 之前按 `span_id` 预查并剔除已落库的 span，避免 Final 全量重放重复压缩与对象存储上传。
5. 鉴权路径补 trace 归属校验：已存在的 traceId 若不属于当前用户，丢弃该批写入。
6. 客户端上传器改为进程级有界队列 + 单消费线程 + 队首连续合并；只有 start 返回 404/405 时才按 `serverUrl` 冷却 15 分钟并降级为今天的“turn 末一次性上传”。
7. 前端列表默认低频轮询以发现新 trace，详情在 RUNNING 时刷新。
8. 不需要 DB migration；旧客户端 + 新服务端、新客户端 + 旧服务端都能安全退化。

## 3. 现状核对

核对原则：以源码为准，不把设计文档自述当事实。

### 3.1 客户端：trace 只在 turn 结束时上传一次

| 事实 | 证据 |
| --- | --- |
| 每个 turn 在 `beforeAgentRun` 里生成 `traceId` / `rootSpanId` / `startedAtMs` / input，仅存在本地 `ThreadLocal` | `core-ai-cli/src/main/java/ai/core/cli/trace/TraceCollectorLifecycle.java:73-83` |
| `afterModel` 构造 LLM span 后只 `state.spans.add(span)`，不发网络请求 | 同上 `:95-123` |
| `afterTool` 构造 TOOL span 后同样只累积 | 同上 `:135-158` |
| root span（`name="cli turn"`、`type="AGENT"`、`parentSpanId=null`）只在 `finish()` 里构造 | 同上 `:170-186` |
| 整个 turn 唯一一次上传发生在 `finish()` 末尾 `uploader.upload(request)`，由 `afterAgentRun` / `afterAgentFailed` 触发 | 同上 `:161-197` |
| 所有 hook 都包在 `safe()` 中，任何 `Throwable` 只记日志、不向 agent 抛出 | 同上 `:37-42` |
| 上传是异步的：`upload()` 把 `send()` 提交到单线程 daemon executor 后立即返回 | `core-ai-cli/src/main/java/ai/core/cli/trace/HttpTraceUploader.java:42-46,180-183` |
| 上传失败吞掉：3 次尝试，200 之外的可重试状态才重试，其余只写 `DebugLog` | 同上 `:206-235` |
| 退出时 `flush()` 等待队列清空（默认 15s） | 同上 `:141-157` |
| 只有登录后（serverUrl + apiKey 均非空）才挂载 trace 生命周期；未登录零开销 | `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java:104-111` |
| desktop 通过 `EngineSessionRegistry` 构造同一个 `CliAgent` 并传入 `clientType`，因此 CLI 与 desktop 共享这条链路 | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java:330,379-389` |
| 子 agent 在 worker 线程上跑，ThreadLocal 让它拥有独立的 turn，不会污染父线程 | `TraceCollectorLifecycle.java:210-223` |
| `beforeAgentRun` 存的 `state.input` 未截断，截断只发生在 `finish()` 构造 root 时 | 同上 `:80` 与 `:181` |
| 终态只有 `OK` / `ERROR` 两种，没有 `CANCELLED`；即使 `ExecutionContext.isCancelled()` 为真 | 同上 `:161-168`；`core-ai/src/main/java/ai/core/agent/ExecutionContext.java:191-193` |
| 并发工具批是“整批跑完再逐条 `afterTool`”，单个快工具不会先于同批慢工具上报 | `core-ai/src/main/java/ai/core/tool/ToolOrchestration.java:115-128` |

结论：今天的系统里，server 侧要等 turn 结束、且这次上传成功之后，才第一次知道这个 trace 存在。这就是 R1/R2 不满足的直接原因。

### 3.2 服务端：已有异步、幂等、best-effort 的落库链路，但语义只适配“整批一次上传”

| 事实 | 证据 |
| --- | --- |
| 鉴权入口 `POST /api/traces/ingest`，`AuthInterceptor` 解析 userId，controller 以 `traceSource(clientType)` 作为 source 入队 | `core-ai-server/src/main/java/ai/core/server/TraceModule.java:131-135`；`trace/web/ingest/IngestController.java:55-74` |
| RBAC 是 fail-closed：非公开 `/api/*` 路由若没有 `@PermissionsRequired` 或 `@PermissionsBypass` 直接 403；现有 ingest 已标注 `@PermissionsBypass` | `web/auth/PermissionInterceptor.java:54-66`；`IngestController.java:55` |
| ingest 的 JSON 用裸 `new ObjectMapper()`（Jackson 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`），客户端多送一个未知字段就会 400 | `IngestController.java:21,62-69` |
| span 的 `bodyStore.offload` 发生在 `insertIfAbsent` **之前**：重复上传的 span 仍会被 gzip + 对象存储 PUT，然后才被唯一索引丢弃 | `IngestService.java:243-254`；`TraceBodyStore.java:118-143` |
| trace 级 body 的对象 key 每次写入生成新的随机 UUID（注释明确为 merged session 设计），重复写 trace input 会产生孤儿对象 | `TraceBodyStore.java:229-236` |
| `findByTraceId` 走完整 `SELECT`，会把 `input/output/metadata` 全部取回 | `PgTraceStore.java:26-36,115-118` |
| HTTP 线程只入队，真正落库在单消费线程 `trace-ingest-buffer` 上；队列/字节超限返回 503 `trace ingest busy` | `trace/service/MemoryTraceSpanBuffer.java:68-98,100-136` |
| 一条 record 重试 3 次后计数丢弃，不会反馈给调用方（除入队失败） | 同上 |
| ingest 先按 trace 选 representative：有 root 用 root，否则用该 trace 的第一个 span | `trace/service/IngestService.java:99-114` |
| trace 已存在时**无条件** `updateTrace(representative)` | 同上 `:129-133` |
| `updateTrace` 会写 status / errorMessage / input / output / durationMs / completedAt | 同上 `:184-209` |
| trace 不存在时按 representative 建行：type/status/input/output/duration/completedAt 全部取自 representative | 同上 `:135-181` |
| span 写入用唯一索引 + `ON CONFLICT DO NOTHING` 幂等；重复 span 直接 return，**不更新已有行**，也不重复累计 token | `trace/PgSpanStore.java:130`；`core-ai-server-db-migration/.../V32__spans.sql:33`；`IngestService.java:248-265` |
| 每批结束后用绝对值 max 重算 trace 总量，重放不会放大 | `IngestService.java:125-127`；`TraceTotalsReconciler` |
| 图片 payload 按 sha256 去重，`ON CONFLICT DO NOTHING` | `trace/service/PgTracePayloadRepository.java:43-49` |
| 30 分钟无活动的 `RUNNING` trace 会被 maintenance 标记为 ERROR（`run interrupted`），判据是 `updatedAt` | `trace/maintenance/StaleRunCleanupService.java:34,44,54-55,103-112` |
| OTLP 链路已有“先建 trace、后补身份”的先例 `traceStarted(...)` | `trace/service/OTLPIngestService.java:104-111` |
| trace 列表按 `created_at DESC` 排序，时间过滤用 `started_at` | `trace/PgTraceStore.java:226-232`；`trace/PgTraceFilter.java:119-125` |

#### 关键限制 1：子 span 批次会污染 trace 级字段

`ensureTrace` 对已存在的 trace 无条件调用 `updateTrace(representative)`。一旦客户端把“单个已完成的 span”作为一批上传，而这一批没有 root span：

- `status` 被写成 `COMPLETED`（`mapTraceStatus("OK")` → `COMPLETED`）；
- `input` / `output` 被写成这个子 span 的输入输出；
- `durationMs` / `completedAt` 被写成子 span 的耗时与完成时间。

结果是：turn 还在跑，trace 列表已经显示 COMPLETED；turn 结束后虽然 root 批次会纠正 status，但运行期间的展示是错误的，且 detail 页的 trace 输出会短暂变成 tool 输出。**这是本期必须修的服务端语义，不修则实时上报不可用。**

#### 关键限制 2：先发“占位 root span”的方案不可行

`saveSpan` 依赖 `insertIfAbsent`，重复 `span_id` 直接 return，不会更新已有行（`IngestService.java:248-254`、`PgSpanStore.java:130`）。因此：

- 若在 turn 开始时先上传一个 `RUNNING` 的 root span 占位，turn 结束时真正的 root span 会因 `span_id` 相同被丢弃；
- trace 行虽然会被 `updateTrace` 改成 COMPLETED，但 root span 行永远停留在 RUNNING / 无输出。

要让占位方案成立，必须改成 upsert 或允许更新 span 行，会破坏“重复上传不重复计数”的幂等前提。**不采用。**

#### 关键限制 3：trace 的 type/name 只在首次建行时可信

`ensureTrace` 建行时 `trace.type = mapTraceType(representative.type)`（`IngestService.java:147-150`），而 `updateTrace` 不更新 `trace.type`。若首个批次是子 span（`type=LLM`），trace 会被错误归类为 `llm_call`，事后无法纠正。**因此 start 必须显式建行，且首次建行必须由 root/start 决定 type。**

#### 关键限制 4：Final 全量重发不是免费的

`saveSpan` 先 `bodyStore.offload(span)` 再 `insertIfAbsent`（`IngestService.java:243-254`）。超过 16 KiB 的 span body 会被 gzip 并同步 PUT 到对象存储；即使这一 span 在实时阶段已经落库，Final 重发时仍会完整走一遍压缩 + 上传，最后才被唯一索引丢弃。`MemoryTraceSpanBuffer` 只有一条消费线程且与 OTLP/服务端 span 共享（`MemoryTraceSpanBuffer.java:54-70,100-116`），所以这条放大路径会直接影响全服 trace 写入延迟。**服务端必须在压缩/上传之前按 `span_id` 过滤掉已存在的 span。**

#### 关键限制 5：鉴权 ingest 没有 trace 归属校验

`ensureTrace` / `saveSpan` / `storePayload` 都只信任请求里的 `traceId`，不校验 `existing.userId == authUserId`（`IngestService.java:129-133,211-269`）。今天 trace 在 turn 末才创建、存活时间短，暴露面有限；实时化后 trace 会在 RUNNING 状态停留整个 turn，任何拿到 traceId 的已登录用户都可以向别人的 trace 追加 span/payload 或覆盖终态。**本期在鉴权路径上补上归属校验。**

### 3.3 前端：RUNNING 状态已有渲染，但没有自动刷新

| 事实 | 证据 |
| --- | --- |
| 列表加载只由筛选/分页/手动刷新按钮触发，没有定时器 | `core-ai-frontend/src/pages/traces/TraceList.tsx:119-151` |
| 状态筛选已包含 `RUNNING`，行上已有运行态渲染 | 同上 `:56` 及行组件 |
| 详情数据由 `TraceDetailPanel` / `TraceDetail` 两个容器在挂载时加载一次；`TraceInspector` 是纯展示组件（props 传入 trace/spans） | `TraceDetailPanel.tsx:17-37`；`TraceDetail.tsx:19-37`；`TraceInspector.tsx:64-72` |
| `TraceInspector` 的 span 详情缓存一旦写入就永不失效（`if (spanDetails[selectedSpanKey]) return;`），运行期拉到的“无图片”版本会一直用到关页 | `TraceInspector.tsx:94-131` |
| 未手动选 span 时默认选中项由 `findDefaultSpan` 计算（优先 ERROR，其次 root）；root 在 turn 末才落库，可能导致默认选中跳变 | `TraceInspector.tsx:98-106`；`traceViewModel.ts:206-211` |

所以即使服务端已经有 RUNNING 记录，用户也要手动点刷新才能看到变化。

### 3.4 可复用的既有能力

- **异步 + 重试 + 静默失败**：`HttpTraceUploader` 已经是 daemon 线程、3 次退避重试、所有异常吞掉，满足 R3 的骨架。
- **幂等**：`span_id` 唯一索引、payload sha256 去重、totals 绝对值重算，使“增量上传 + 结束全量重发”在数据上安全。
- **独立连接池**：trace 读写走 `TraceDbModule` 的命名数据源（`sys.trace.db.*`），实时上报增加的小请求不会挤占主库连接池。
- **鉴权**：`/api/traces/ingest` 已在 `AuthInterceptor` 覆盖范围内，新增同级端点不需要改鉴权框架。

## 4. 目标与非目标

### 4.1 目标

| 编号 | 目标 |
| --- | --- |
| G1 | turn 开始后 ≤2s，trace list 出现该 trace，状态 RUNNING |
| G2 | 每完成一个 LLM/tool 步骤，≤2s 内该 span 在详情页可见，token/模型同步刷新 |
| G3 | turn 结束后 5s 内，trace 的终态、输出、duration、token 总量与“一次性上传”完全一致 |
| G4 | 任何上报失败都不改变 turn 的结果与耗时（本地开销 <100ms） |
| G5 | 旧服务端 / 旧客户端 / 断网 / 进程崩溃等场景都有确定行为，不产生永久 RUNNING 的“僵尸 trace” |

### 4.2 非目标

- 不做“进行中的 span”可视化（例如正在流式输出的 LLM 调用的半成品 span）。本期只展示**已完成**的步骤。
- 不做 token 级流式增量，不改变 span 结构、字段与截断规则。
- 不把 CLI 切到 OTLP；不引入本地缓冲文件或断点续传。
- 不改变 trace 命名策略（root 仍叫 `cli turn`），不新增 trace 列表的“实时”视觉标识。
- 不同步修改 desktop 的渲染进程/主进程代码（desktop 复用引擎，引擎升级即生效；仅引擎关闭路径的 `flush` 属于 cli 模块改动）。
- 不做服务端限流/配额策略（沿用现有 buffer 背压）。

## 5. 方案选型

| 方案 | 做法 | 优点 | 致命问题 | 结论 |
| --- | --- | --- | --- | --- |
| A. 新增 start 端点 + 增量 span | 新端点只建 trace 行；span 走现有 ingest | 语义清晰；旧服务端可用 404 明确探测；不需要改 span 幂等语义 | 需要新增端点与客户端降级逻辑 | **采用** |
| B. 先上传占位 root span | turn 开始发 RUNNING root，结束再更新 | 复用现有 ingest | `insertIfAbsent` 丢弃最终 root，root 行永久 RUNNING（§3.2 限制 2） | 否决 |
| C. 复用 `/api/traces/ingest` 加 `start:true` | 同一端点、空 spans + 标记 | 不新增端点 | 旧服务端是否忽略未知字段取决于反序列化配置；即使忽略也无法告知客户端“实时不可用”，会持续发送增量 span 并触发服务端状态污染 | 否决 |
| D. 客户端本地缓冲 + 服务端拉取 | 新通道拉取 | 不依赖 push | 新认证通道、运维面大，偏离现有 push 模型 | 否决 |
| E. 维持现状 | turn 末上传 | 零改动 | 不满足 R1/R2 | 否决 |

**方案 A 的关键配套**：服务端必须同时修正“子 span 批次不写 trace 级字段”（§6.3），否则实时上报会把 trace 显示搞坏。这个修正对旧客户端是 no-op（旧客户端每批都带 root）。

## 6. 详细设计

### 6.1 协议

#### 6.1.1 新增 trace start 端点

```
POST /api/traces/ingest/start
Authorization: Bearer <apiKey>
Content-Type: application/json
```

请求体（所有字段均为具体类型，不含 `Object`）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `traceId` | String | 是 | 客户端生成的 32 位 hex；trim 后长度 16–128，字符集 `[A-Za-z0-9_-]`（16 位下限用于降低误撞/枚举空间） |
| `name` | String | 否 | 缺省 `cli turn`；trim 后截断 512 字符 |
| `input` | String | 否 | 用户 query，客户端按现有 `truncate` 规则截断；服务端最多保留 4 MiB，超出部分截断而不是返回 400 |
| `startedAtEpochMs` | Long | 否 | turn 开始时间；缺省取服务端 now；越界（早于 now-30d 或晚于 now+5m）clamp 到 now 并记 warn |
| `serviceName` / `serviceVersion` / `environment` | String | 否 | 与 `IngestRequest` 同义，缺省 `unknown` |
| `clientType` | String | 否 | 复用 `traceSource(...)` 映射：`desktop` → `desktop`，其它/缺省 → `cli` |

DTO 必须标注 `@JsonIgnoreProperties(ignoreUnknown = true)`：controller 使用裸 `ObjectMapper`（`IngestController.java:21`），Jackson 默认拒绝未知字段。

响应：

| 状态码 | body | 含义 |
| --- | --- | --- |
| 200 | `ok` | 已创建，或该 traceId 已存在（幂等；仅当已有行 name 为空时回填 name，其余字段不动） |
| 400 | `invalid` | traceId 非法 / body 无法解析 |
| 401 | — | 未认证（由 `AuthInterceptor` 处理） |
| 503 | `trace ingest busy` | buffer 容量/字节超限（与现有 ingest 一致） |

约束：

- 端点不创建 span 行，不累计 token，`status=RUNNING`、`type=agent`、`durationMs=0`、`completedAt=null`、`output=null`。
- 幂等语义 = `insertIfAbsent`：重复 start 不覆盖已有行的 status/input，尤其不能把已 COMPLETED 的 trace 改回 RUNNING。
- `userId` 只取自鉴权上下文（`authUserId`），忽略任何客户端自带身份，与现有 `ingestAuthed` 一致。
- `metadata` 写 `service/version/environment`，便于列表与后续排查。

#### 6.1.2 增量 span 批次

直接复用现有 `POST /api/traces/ingest`：

- 每个请求只带一个 trace 的 1–N 个**已完成** span，`payloads` 为空数组或不带；
- 不做 in-flight span，不新增字段；
- 服务端行为变化仅限 §6.3 的“无 root 批次不写 trace 级字段”。

#### 6.1.3 最终批次

`finish()` 维持现有行为：root + 全部 span + `CliTracePayloads` 收集的图片，一次上传。它承担三件事：

1. 兜底：实时批次丢失/端点不可用时，数据仍完整到达；
2. 终态：root 批次把 trace 写成 OK/ERROR（`CANCELLED` 目前 CLI 不会产生，见 D16），并写入 output、duration、completedAt；
3. 图片：实时批次不传 payload，图片只在最终批次上传（见 §6.2.4）。

### 6.2 客户端设计

#### 6.2.1 生命周期改动

| Hook | 现状 | 改动 |
| --- | --- | --- |
| `beforeAgentRun` | 只建本地 `TurnState` | 建完 state 后立即 `uploader.start(...)`，input 先走 `truncate`（现状只在 `finish` 截断） |
| `afterModel` | 构造 span → `state.spans.add` | 追加 `uploader.span(span)` |
| `afterTool` | 构造 span → `state.spans.add` | 追加 `uploader.span(span)` |
| `finish` | 全量上传 | 不变（仍全量上传） |

实现约束：

- `uploader.span(...)` 必须在 span 对象**完全构造完成后**入队，入队后不再修改该对象；
- 所有新增调用仍包在 `safe()` 内，异常只记 `DebugLog`；
- `TraceUploader` 保持函数式接口（仅一个抽象方法 `upload`），新增 `default void start(...) {}` / `default void span(...) {}`。这样现有测试里的 lambda 与“不上传”实现都不需要改。

#### 6.2.2 上传器结构

`HttpTraceUploader` 从“无界 executor + 每任务一次请求”改为“进程级有界队列 + 单消费线程”。**队列必须是进程级的**：desktop/ACP 会让同一个进程创建多个 uploader（每个 session 一个），现有 `EXECUTOR` 已经是 static（`HttpTraceUploader.java:39-46`），如果队列按实例建，既会重现源码注释里明确禁止的线程泄漏，也会让静态 `flush()` 失效。

```
enqueue(Outbound{target, kind, ...})
        │
        ▼
LinkedBlockingDeque<Outbound>（512 条 / 8 MiB，自己记账）
        │  take；Final 走 offerFirst
        ▼
cli-trace-upload (static daemon)
        │
        ├─ Start  → target.startEndpoint
        ├─ Span*  → target.endpoint（合并 1–32 条，合计 ≤1 MiB）
        └─ Final  → target.endpoint（全量 + payloads）
```

`Outbound` 携带不可变 `Target{endpoint, startEndpoint, apiKey, httpClient, mediaRoot}`，因为不同 session 可能连不同的 serverUrl：

| 规则 | 设计 |
| --- | --- |
| 入队不阻塞 agent 线程 | 只在锁内做 `offer`，绝不 `put`、绝不做序列化 |
| Span 队列满 | 直接丢弃并累计 `droppedSpans`，最终批次仍会补齐 |
| Final 入队 | 先 `removeIf` 掉同 `traceId` 未发送的 Start/Span（Final 是全量超集），再 `offerFirst`；容量不足时淘汰最旧的 Span |
| 合并窗口 | 只从**队首**连续取同类记录：`peek` 判断 target/traceId/kind 都一致才 `poll`，最多 32 条 / 1 MiB；遇到第一个不匹配的记录立即停止，**不做 `drainTo`**（避免打乱 Start/Span/Final 的顺序） |
| 顺序 | 同一 trace 的 start 先于其 span 入队；Final 允许插队（它自带 root，服务端可从 root 建行），但绝不允许 Span 越过 Start 或 Final |
| 序列化位置 | 在消费线程做 `JsonUtil.toJson(toMap(...))`，agent 线程只做对象引用入队 |
| 超时/重试 | Start/Span：3s 超时、最多 1 次重试（丢了由 Final 兜底）；Final：10s、3 次、408/429/5xx 才重试 |
| flush | 入队 barrier 并等待；`CliApp` 仍是 15s，desktop 引擎关闭路径新增 3s 超时的 `flush`（见 §12 P1） |

#### 6.2.3 降级状态机

```
UNKNOWN ──start 200──▶ ENABLED
   │                      │
   │                      └─ span/final 正常发送
   ├─start 404/405──────▶ DISABLED(serverUrl, until = now + 15min)
   └─其它失败 ───────────▶ 保持 ENABLED
```

- 只有 **404 / 405** 才判定“服务端不支持实时”：404 是明确的“没有这个路由”，405 是“方法不允许”。**400 不降级**——它更可能是单次请求格式/校验问题，把它当成永久熔断会把偶发脏输入放大成整个进程失能。
- `IngestStartRequest` 必须加 `@JsonIgnoreProperties(ignoreUnknown = true)`：controller 用的是裸 `ObjectMapper`（见 §3.2），未来客户端加可选字段不能把旧服务端打成 400。
- 降级标记按规范化后的 `serverUrl` 分桶，并带 15 分钟 TTL：desktop 切环境、运维临时关开关（D12）后都能自动恢复，不需要重启进程。
- 5xx / 408 / 429 / 网络异常**不**关闭实时：可能是瞬时故障，span 丢失由最终批次补齐。
- 旧客户端不会调用 start，因此不受任何影响。

#### 6.2.4 图片与体积

- 实时请求**不**调用 `CliTracePayloads.collect`，不带 `payloads`：避免 agent 线程/消费线程反复扫描 8 MiB 媒体目录，也避免图片字节重复上传。
- 图片只在 Final 批次上传；服务端按 sha256 去重，且 payload 存储与 span 去重相互独立，所以即使 span 已被实时批次写入，Final 里的 payload 仍会落库。
- 单条 span 的 `input/output` 已由 `truncate` 控制，合并后的请求体上限 1 MiB，服务端 9 MiB record 上限不会被触碰。

#### 6.2.5 客户端开销

- agent 线程新增成本 = 每 step 一次 `offer` + 一次 `new CliTraceStartRecord`，无序列化、无锁等待；
- 消费线程串行发送，网络慢只会让队列变长，不会阻塞 turn；
- 队列元素引用的是已在 `state.spans` 中保留的对象，不额外复制大字符串。

### 6.3 服务端设计

#### 6.3.1 新增 DTO 与路由

| 项 | 设计 |
| --- | --- |
| DTO | `trace/web/ingest/IngestStartRequest`：只有 String/Long 字段，无 `Object`、无枚举，`@JsonIgnoreProperties(ignoreUnknown = true)` |
| Controller | `IngestController.ingestStartAuthed(Request request)`：与 `ingestAuthed` 同样的 `AuthContext.userId(webContext)` 空值检查、`MAPPER.readValue` 与 `@PermissionsBypass` 注解；解析后 `traceBuffer.offerStart(...)` |
| 路由 | `TraceModule` 中与 `/api/traces/ingest` 同级注册 `POST /api/traces/ingest/start`，不做 whitelist，走 `AuthInterceptor` |
| Buffer | **不重构队列结构**。`MemoryTraceSpanBuffer` 已有 `offer(Runnable task, long bytes, String kind)`（`MemoryTraceSpanBuffer.java:76-98`），新增 `offerStart(...)` 三行，内部复用 `offer(() -> ingestService.startTrace(...), bytes, "start")` |
| Service | `IngestService.startTrace(IngestStartRequest, String authUserId, String source)` |

`@PermissionsBypass` 是必须的：`PermissionInterceptor` 对没有 `@PermissionsRequired` / `@PermissionsBypass` 的非公开 `/api/*` 路由直接抛 `ForbiddenException`（`PermissionInterceptor.java:54-66`）。漏标会让 start 100% 403。

buffer 注意：

- start 的 `bytes` 用 `body.length`，与 ingest 共用 `max.record.bytes`（9 MiB）与容量账本；
- `offer` 失败仍返回 503 `trace ingest busy`，客户端按可重试处理；
- 关闭时 drain 逻辑不变。

#### 6.3.2 startTrace 的建行规则

```
trace.id        = UUID
trace.traceId   = request.traceId
trace.name      = request.name (trim, 512 上限) 或 "cli turn"
trace.type      = "agent"                       // 不接受客户端指定
trace.status    = RUNNING
trace.source    = source                         // cli / desktop
trace.userId    = authUserId
trace.startedAt = clamp(request.startedAtEpochMs) 或 now
trace.input     = bodyStore.offload(...)         // 大 body 走对象/文件存储
trace.metadata  = {service, version, environment}
trace.durationMs = 0
trace.completedAt = null
trace.output/errorMessage = null
counter/costUsd  = 0
trace.createdAt = trace.updatedAt = now
insertIfAbsent(trace)
  ├─ true  → 结束
  └─ false → 竞态：另一个请求（可能是 span 先到）已经建行
       ├─ owner 不一致 → warn + 丢弃（不覆盖）
       ├─ name 为空 → backfillTraceName(request.name)   // 只补名字
       └─ 不改 status / input / completedAt / output
```

多 Pod 下 `start` 与第一个 `span` 可能并发（HTTP 200 只代表“入队成功”，不代表“已落库”，见 `MemoryTraceSpanBuffer.java:100-116`）。span 先落库时 trace 会以降级形态建行；start 后到只做名字回填，状态与输入输出仍由最终 root 批次负责。

不做的三件事：

1. 不写 `sessionId`（CLI 现有 span 也不带 `session.id`，本期不引入不一致；见 D11）。
2. 不推断 model/provider（等第一个 LLM span 到达后由 `saveSpan` 的 backfill 写入）。
3. 不写任何 span 行。

#### 6.3.3 ingest 的 root / 非 root 语义（核心修正）

完整流程：

```
ingest(request, authUserId, source):
  1. 归属校验（仅 authUserId != null）：
        对 request.spans 里每个 traceId 取 existing；
        若 existing.userId != null && !equals(authUserId) → 记入 rejected，
        该 trace 的 payload 与 span 全部丢弃 + 安全 warn（仍返回 200）
  2. 按 span_id 预查已存在集合（一次 IN 查询，仅 rejected 之外的 trace）
        → 从 request.spans 剔除已存在的 span（跳过 compact / offload / insert）
  3. payload 存储（仅 rejected 之外的 trace）
  4. compactor 只处理剩余 span
  5. ensureTrace（复用第 1 步读到的 existing；只对 rejected 之外的 trace）
  6. saveSpan（只对 rejected 之外且未被预查过滤的 span）
  7. recomputeTotals（只对实际写入或已存在的 trace）
```

第 2 步是必须的：没有它，Final 全量重放会让每个已落库的大 span 重新 gzip + PUT 到对象存储（§3.2 限制 4）。预查只是性能优化，真正的幂等闸门仍是 `insertIfAbsent`，所以并发下不会写错。

`ensureTrace` 改为：

```java
boolean hasRoot = representative.parentSpanId == null;
boolean authenticated = source != null;   // /api/traces/ingest 走鉴权；/api/ingest/spans 为 null

if (existing != null) {
    if (!authenticated) {
        updateTrace(traceId, representative);        // 匿名路径：完全保持现状
    } else if (hasRoot) {
        updateTrace(traceId, representative);        // 只有 root 能写 trace 级字段
    }
    // 鉴权 + 非 root：不写任何 trace 级字段。
    // model / provider / agent / name 由 saveSpan 的 backfill 完成。
    return;
}
```

建行分支：

| 场景 | type | status | name | input/output | duration/completedAt |
| --- | --- | --- | --- | --- | --- |
| 有 root（旧客户端、最终批次） | `mapTraceType(root.type)` | `mapTraceStatus(root)` | `friendlyTraceName(root.name, agentName)` | 取自 root | 取自 root |
| 无 root + 鉴权端点（start 丢失的降级） | 固定 `agent` | 固定 `RUNNING` | `null`（等 start 或最终 root 回填） | 全部 `null` | `0` / `null` |
| 无 root + 匿名端点（保持现状） | `mapTraceType(child.type)` | `mapTraceStatus(child)` | `friendlyTraceName(child.name, agentName)` | 取自 child | 取自 child |

`insertIfAbsent` 竞态分支（`IngestService.java:179-181`）同样要加条件：

```java
if (!traceStore.insertIfAbsent(trace)) {
    if (!authenticated) updateTrace(traceId, representative);          // 匿名：现状
    else if (hasRoot) updateTrace(traceId, representative);            // 鉴权：只有 root
    // 鉴权 + 非 root：什么都不做
}
```

否则“子 span 与 start 并发、insertIfAbsent 失败”的分支仍会把 RUNNING 写成 COMPLETED，等于核心修正漏了一半。

`updateTrace` 的三处配套修改：

1. **错误信息可清除**：`status != ERROR && representative.parentSpanId == null` 时设置 `update.clearErrorMessage = true`（`TraceUpdate.java:23` 已支持，OTLP 路径已这么用：`OTLPIngestService.java:236-241`）。否则被 stale cleanup 标过 ERROR 的长 turn 结束后会永久残留 `run interrupted`。
2. **root 必须能补 name**：现在只在 `agentName != null` 时才 `backfillTraceName`（`IngestService.java:201-208`），而 CLI 的 root span 不带 `gen_ai.agent.name`。改为 root representative 无条件调用 `backfillTraceName`（name 为空/占位时才会真正写）。
3. **input 不重复上传**：`updateTrace` 每次都对 `rootSpan.input` 做 `bodyStore.offload`，而 trace 级 key 每次生成新 UUID（`TraceBodyStore.java:229-236`），会留下孤儿对象。规则改为：`existing.input != null` 且 incoming input 与已有内容一致（或已有是 ref 且 sha256 相同）时跳过 offload；至少要做到“start 已经写过、Final 内容相同则不重写”。

为什么用 `source != null` 作为“鉴权端点”的判据：

- `/api/traces/ingest` 只在登录后由 CLI/desktop 使用，且这类客户端最终一定会上报 root；子批次只可能来自本期新增的增量上报。
- `/api/ingest/spans` 是匿名兼容路径，现有第三方 producer 可能只发 LLM span、从不发 root；对它们必须保持“用第一个 span 定型”的旧语义，否则 trace 会永远 RUNNING 并在 30 分钟后被判 ERROR。这是本设计里最重要的兼容性边界。

不可省略的推导：**没有这条修正，“实时上报”必然把运行中的 trace 显示成 COMPLETED，并让 trace 输出滚动成最后一个 tool 的结果。** 这不是优化，是前置条件。

#### 6.3.4 乱序、重放与最终一致性

| 场景 | 行为 |
| --- | --- |
| start 先到，span 后到 | trace 已存在，子批次不写 trace 级字段；span 正常插入并累计 token |
| 多 Pod：span 先落库、start 后到 | trace 以 RUNNING/type=agent/name=null 建行；start 冲突分支只补 name；最终 root 补 input/output/status |
| start 丢失（请求彻底失败） | 建 RUNNING trace（type=agent、name=null），最终 root 批次补全 name/input/output/status |
| 最终批次后又收到重放的子批次 | trace 已 COMPLETED；子批次不改状态；重复 span 被预查过滤 + `insertIfAbsent` 丢弃 |
| 最终批次重放 | root 已存在 → `updateTrace` 重写同样的终态，幂等；`clearErrorMessage` 保证脏错误不残留 |
| 某个实时 span 请求彻底丢失 | span 缺失直到 Final 批次补齐；若 Final 里也没有（进程崩溃），则永久缺失，但 trace 仍会被 stale cleanup 收尾 |
| token 统计 | 只有真正插入的 span 才累计；每批结束用 `TraceTotalsReconciler` 以绝对值重算，重放不会放大 |
| 跨用户 traceId | 归属校验失败 → payload/span 全部丢弃 + 安全 warn；不返回 403（已经 200 响应），但不会写入任何数据 |

#### 6.3.5 与 30 分钟 stale cleanup 的关系

- `StaleRunCleanupService` 只处理 `started_at` 与 `updated_at` 都早于 `now-30m` 的 RUNNING trace。
- 每个成功插入的 span 都会刷新 `updatedAt`（`incrementTraceTotals`），因此正常长 turn 不会被误杀。
- 唯一例外：单个步骤持续 >30 分钟且期间没有任何 span 完成（例如一个超长 tool 调用）。此时 trace 会被短暂标成 ERROR；turn 结束的 root 批次会把它改回 COMPLETED，并因 §6.3.3 的 `clearErrorMessage` 清掉 `run interrupted` 文本，属于“可自愈的展示抖动”。是否加心跳见 D10。
- 另一个已有事实：并发工具批要等整批完成才逐条 `afterTool`（`ToolOrchestration.java:115-128`），因此同批里 1s 的工具不会先于 60s 的工具可见。这是**上报粒度**限制，不是缺陷；在 G2 的验收里按“步骤完成”而不是“单个工具完成”计数。

#### 6.3.6 前端设计

轮询必须放在**容器**里，不能放进 `TraceInspector`：它是 props 驱动的纯展示组件，同时被列表侧栏（`TraceDetailPanel`）和独立详情页（`TraceDetail`）复用，改它会破坏只读/嵌入模式。

| 位置 | 改动 |
| --- | --- |
| `TraceDetailPanel` / `TraceDetail` | 打开的 trace 为 `RUNNING` 时，每 2s 静默刷新 trace + spans；变为终态后停止；页面隐藏时暂停；请求失败静默保留旧数据 |
| `TraceList` | 默认开启“Live”轮询（**不要求当前页已有 RUNNING 行**）：页面可见且筛选范围 ≤24h 时每 5s 静默拉取当前 query；出现 RUNNING 行后提升到 3s；无 RUNNING 且超过 30 分钟自动降回 5s 或停止（见 D9）。提供显式开关，默认开 |
| 静默语义 | 轮询不改变 `requestKey`、不触发骨架屏、不重置分页/滚动/选中行 |
| 缓存失效 | trace 从 RUNNING 变为终态时清空 `TraceInspector` 的 `spanDetails` 缓存（或至少失效带 `image_ref` 的项），让 Final 补传的图片能重新拉取 |
| 默认选中 | 未手动选 span 时，把第一次算出的 `effectiveSelectedSpanId` 固定下来（sticky），避免 root 落库后 `findDefaultSpan` 把选中项跳走 |
| 手动刷新 | 保留现有按钮，二者互不干扰（手动刷新仍走 `refreshKey`） |

为什么列表默认也要轮询：只在“已有 RUNNING 行”时轮询，会让停留在默认列表页的用户永远看不到新 turn 的第一条记录，必须先手动刷新一次——直接违背 R1。代价是每打开一个列表页约 0.2 QPS 的读请求（5s 一次），可以通过开关关闭。

不在本期：WebSocket/SSE 推送、列表行的“LIVE”动画、增量 diff 渲染。

### 6.4 端到端时序

```
CLI turn start
  ├─ beforeAgentRun ──▶ POST /api/traces/ingest/start ──▶ buffer ──▶ trace(RUNNING) 可见
  ├─ LLM call #1
  ├─ afterModel ──────▶ POST /api/traces/ingest [llm span] ──▶ span 可见，token/model 更新
  ├─ tool call #1
  ├─ afterTool ───────▶ POST /api/traces/ingest [tool span] ──▶ span 可见
  ├─ ... (重复)
  └─ afterAgentRun
       └─ finish ─────▶ POST /api/traces/ingest [root + all spans + payloads]
                          ├─ root span 落库（子 span 因 span_id 去重被跳过）
                          ├─ updateTrace：COMPLETED / output / duration / errorMessage
                          └─ totals 重算 = 与一次性上传一致
```

崩溃路径：

```
CLI 进程被杀
  └─ trace 停在 RUNNING（updatedAt 不再刷新）
       └─ ≥30min 后 StaleRunCleanupService → ERROR "run interrupted: no activity for 30m"
```

### 6.5 降级与失败矩阵

| 场景 | 客户端行为 | 服务端 / 最终结果 |
| --- | --- | --- |
| 服务端支持 start | 实时发送 | trace 立即可见，逐步更新 |
| 服务端无 start（旧版本，404/405） | 按 serverUrl 关闭实时 15 分钟，只发 Final | 与今天一致：turn 末出现；15 分钟后自动重探 |
| start 遇 400 | **不降级**（单次请求问题），记日志 | start 丢失 → 首个 span 以 RUNNING 建行（降级路径） |
| start 遇 503 / 5xx / 超时 | 1 次重试后丢弃，实时保持开启 | 首个 span 批次会以 RUNNING 建行 |
| 中途断网 | 增量发送失败即丢弃；Final 继续重试 | 网络恢复后 Final 补齐全部 span；若进程退出则 `flush` 尽力而为 |
| 服务端持续 5xx | 同上 | trace 可能缺失或停留在 RUNNING，由 stale cleanup 收尾 |
| 客户端时钟超前/滞后 | 原样发送 | start 建行时 clamp 到服务端时间；最终 root 的 `updateTrace` 用客户端 completedAt，可能与前值略有出入（可接受） |
| CLI 崩溃 | — | 30 分钟后标记 ERROR |
| 旧 CLI + 新服务端 | 不调 start，只发 root 批次 | 行为完全不变（root 代表，走原语义） |
| 未登录 | 不创建 uploader | 零请求、零开销 |
| 旧服务端 + 增量 span（不应发生） | 因 404 已禁用实时 | 无 |
| 队列堆积（服务端慢） | 512 上限，Span 丢弃；Final 插队并淘汰同 trace 未发的增量 | trace 仍能看到 start 与部分步骤，终态完整 |
| 跨用户 traceId（鉴权路径） | 无法感知（HTTP 已 200） | 服务端丢弃写入 + 安全 warn，不污染他人的 trace |

## 7. 数据与兼容性

### 7.1 存储

- **无 schema 变更**：`app.traces.status` 已支持 `RUNNING`，`app.spans.span_id` 唯一索引已存在，payload 表已有 `(trace_id, blob_hash)` 唯一约束。
- 新增的 trace 行只是“提前创建”，字段集合与今天 `ensureTrace` 创建的完全一致（终态相关字段为空）。
- 唯一的空值差异：start 丢失、由子 span 抢先建行时 `name` 为 `null`（等 start/root 回填），其余降级字段（status/type）仍保证有值，列表不会出现空状态。
- `created_at` 由服务端生成，列表排序（`ORDER BY created_at DESC`）不会因客户端时钟偏移而错乱。
- `started_at` 取客户端时间并做 clamp；列表的“最近 15 分钟/1 小时”筛选依赖它，因此时钟偏差 >5 分钟时需要 clamp 兜底。

### 7.2 兼容矩阵

| 客户端 | 服务端 | 行为 |
| --- | --- | --- |
| 旧 CLI | 新 server | 完全不变（不调 start；root 批次走原语义） |
| 新 CLI | 新 server | 实时可见 |
| 新 CLI | 旧 server | start 404 → 按 serverUrl 关闭实时 15 分钟 → turn 末上传，与今天一致 |
| 旧 CLI | 旧 server | 不变 |
| desktop（新引擎） | 新 server | 与 CLI 相同（`clientType=desktop`，source 正确） |
| desktop（旧引擎） | 新 server | 不变 |
| 匿名 SDK | 新 server | `/api/ingest/spans` 语义完全不变（包括“无 root 也能定型”） |

### 7.3 数据保留与清理

实时上报只提前写入，不改变任何保留/归档策略；被丢弃的 span 不会产生孤儿 payload（payload 按 traceId 存储，读取时按 span 引用解析，未被引用的 payload 由现有清理策略回收）。

## 8. 可观测性

| 侧 | 指标/日志 | 用途 |
| --- | --- | --- |
| CLI | `DebugLog`: `trace live disabled (server=…, status=404, until=…)` | 确认降级原因与恢复时间 |
| CLI | `DebugLog`: `trace span dropped: queue full (n=…)` | 判断网络/服务端是否长期不可用 |
| CLI | 退出时汇总 `sent/start/spans/dropped` 计数（仅 DebugLog） | 现场排查 |
| Server | start 成功 / 幂等命中 / owner 不匹配各一条日志 | 判断客户端是否在实时上报、是否有越权写入 |
| Server | 预查过滤的 span 数（debug） | 验证 Final 重放没有产生重复 offload |
| Server | 现有 buffer 计数器（入队/丢弃/重试） | 容量与背压 |
| Server | `trace ingest busy` 503 计数（现有日志） | 是否需要扩容 buffer |

不新增 metrics 端点，不引入新依赖。

## 9. 性能与容量

| 维度 | 估算 | 说明 |
| --- | --- | --- |
| 每 turn 请求数 | `1 start + ceil(steps / 合并数) + 1 final` | 10 步、每次 1 条 ≈ 12 个请求；同一同步窗口内的并行 tool 由“队首连续合并”压成一个请求 |
| 单请求大小 | 1–50 KB（无图片） | 图片只在 Final 上传，最大 8 MiB |
| 服务端额外 DB 成本 | 每请求 1 次 `tokenTotals` 聚合 + 1 次 span_id 预查 + 正常 span 插入 | 预查是 `IN` 列表；聚合按 traceId 走索引；若 dev 实测偏高，再考虑“无 root 批次跳过 reconciler” |
| 重复 span 成本 | Final 重放的已存在 span 被预查过滤，跳过 compact/offload/insert | 这是本期必须做的性能前置 |
| CLI agent 线程开销 | 每 step 一次 `offer`，无序列化/无网络 | 目标 <1ms/step |
| 内存 | 队列上限 512 条引用 + 最终批次已有对象 | 无大字符串复制 |
| 服务端 buffer | start 与 span 共用 10000 容量 / 64 MiB handoff | start 很小，不改变量级 |
| trace 列表轮询 | 默认 5s、有 RUNNING 提升到 3s、隐藏页暂停、可关闭 | 单用户稳态 ≤0.2 QPS |

## 10. 风险与缓解

| 编号 | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| R1 | 子 span 批次覆盖 trace 状态/输出 | 运行中显示 COMPLETED、输出错乱 | §6.3.3 修正（含 insertIfAbsent 竞态分支）+ 单测覆盖 |
| R2 | 新客户端打到旧服务端 | trace 永不实时 / 长期 RUNNING | start 404/405 探测 + 按 serverUrl 的 15 分钟冷却 + Final 兜底 |
| R3 | 重复上传导致 token 翻倍 | 报表失真 | span_id 唯一 + 仅插入时累计 + 绝对值重算 |
| R4 | 队列无界增长 | CLI 内存/退出变慢 | 有界队列 + 丢弃 Span + Final 插队 |
| R5 | 超长单步被 30 分钟清理误判 | 短暂显示 ERROR | 终态批次自愈 + `clearErrorMessage`；心跳见 D10 |
| R6 | 客户端时钟偏移 | 列表时间筛选查不到 | start 时 clamp + warn |
| R7 | 鉴权过期 | 实时与 Final 都 401 | 与今天一致，不影响 turn；重登录后下个 turn 恢复 |
| R8 | trace 列表被大量 RUNNING 行占据 | 观感差 | 30 分钟清理兜底；列表轮询有上限与开关 |
| R9 | 服务端 buffer 被小请求打满 | 影响其它 trace 写入 | 合并 + 预查 + 现有容量；必要时用 D12 开关关闭实时 |
| R10 | 跨用户 trace 注入 | 数据污染/越权 | 鉴权路径 owner 校验 + warn |
| R11 | Final 重放触发重复对象存储上传 | 共享的 trace 写入线程被拖慢 | §6.3.3 第 2 步的 span_id 预查 |
| R12 | 把 400 误判成“服务端不支持” | 整个进程永久失能 | 只认 404/405 + 15 分钟 TTL 重探 |
| R13 | 桌面端退出未 flush | 队中 Final 丢失 | P1 在引擎关闭路径补 3s flush |

## 11. 测试计划

### 11.1 单元测试

客户端（core-ai-cli）：

| 用例 | 断言 |
| --- | --- |
| `beforeAgentRun` | 先入队 start，再入队任何 span；start 的 traceId/startedAt 与最终 root 一致；input 已截断 |
| `afterModel` / `afterTool` | 每个完成步骤各入队 1 条 span，且对象在入队后不再变化 |
| `finish` | 仍入队一次全量批次（root + 全部 span） |
| 未登录 | 不创建 uploader，不产生任何入队 |
| 上传器队列满 | Span 被丢弃并计数，Final 不被丢弃 |
| Final 入队 | 同 trace 未发送的 Start/Span 被移除，Final 排在队首 |
| start 返回 404 | 该 serverUrl 进入冷却，后续 Span 丢弃，Final 仍发送 |
| start 返回 400 | **不**进入冷却，仅记日志 |
| start 返回 503 | 不进入冷却，1 次重试后继续实时 |
| 合并窗口 | 队首连续 3 条同 trace 的 span 合并为 1 个请求，且 payloads 为空；遇到不同 trace/target/kind 立即停止 |
| 同进程两个 uploader | 共用一个队列与一个消费线程；各自的 endpoint/apiKey 不串 |
| `flush` | 等待队列清空后返回；超时返回 false |
| `TraceUploader` 默认方法 | 现有 lambda 实现仍编译、行为不变 |

服务端（core-ai-server）：

| 用例 | 断言 |
| --- | --- |
| start 建行 | status=RUNNING、type=agent、无 span 行、input 落库 |
| start 幂等 | 第二次 start 不改 status/input/updatedAt；name 为空时补 name |
| start 参数校验 | 非法 traceId → 400；超长 name 截断；时间越界 clamp；未知字段不报错 |
| 子 span 批次（鉴权） | trace 已存在时 status/input/output/duration/completedAt 不被修改 |
| 子 span 批次（匿名） | 保持旧行为（用第一个 span 定型） |
| 子 span 先于 start | 建 RUNNING trace，type=agent，name=null，input/output 为空；start 后到只补 name |
| insertIfAbsent 竞态 | 子 span 与 start 并发时，失败分支不把 RUNNING 写成 COMPLETED |
| 最终 root 批次 | status/error/output/duration/completedAt 正确；root 无 agentName 也能补 name |
| 错误信息清除 | 先被标记 ERROR（残留 error_message）的 trace，root OK 批次后 error_message 为空 |
| root 批次重放 | 幂等，token 不翻倍 |
| 最终批次后再来子批次 | 状态保持 COMPLETED |
| span 预查过滤 | 已存在的 span 不触发 compact/offload/insert；新增 span 正常写入 |
| owner 不匹配 | 鉴权路径下拒绝写入 payload/span，日志含安全 warn；原 trace 不变 |
| buffer | start 与 span 共用容量；超限返回 503 |

回归测试：现有 `IngestServiceTest` / `IngestControllerTest` / `MemoryTraceSpanBufferTest` 全部通过，特别是“旧客户端单批全量上传”和“匿名无 root 批次”的用例必须零修改通过。

### 11.2 dev 环境 E2E（实现完成后主动执行）

1. 构建并登录 dev server，跑一个至少 3 次 tool 调用的 prompt。
2. 在 turn 进行中轮询 `GET /api/traces?status=RUNNING&range=15m`：
   - start 后 ≤2s 出现该 trace；
   - 每完成一步，spans 数量 +1，token 单调增加。
3. turn 结束 5s 后核对：status=COMPLETED、span 数 = 1 root + N steps、token 总量 = 各 span 之和、无重复 span。
4. 故障注入：
   - 把 CLI 指向不存在的端口 → turn 正常完成，DebugLog 有上传错误，无卡顿；
   - 用不带 start 端点的 server 构建 → CLI 按 serverUrl 降级，trace 在 turn 末出现；
   - 中途 kill CLI → 手动把该 trace 的 `updated_at` 改到 31 分钟前，等一个清理周期，确认变为 ERROR。
5. 旧版 CLI 对 dev server 跑同一 prompt，确认 trace 结果与改动前一致。
6. 安全检查：用账号 B 的 token 向账号 A 的 traceId 发送一个 span/payload，确认服务端丢弃并打 warn，A 的 trace 不变。
7. 性能检查：在 turn 结束后看 server 日志的“预查过滤 span 数”，确认 Final 重放没有重复 offload；对比改动前后 trace ingest 的单条处理耗时。

### 11.3 验收对照

| 需求 | 验收方式 |
| --- | --- |
| R1 | E2E 步骤 2 的第一条 |
| R2 | E2E 步骤 2 的第二条 + 步骤 3 + 步骤 7 |
| R3 | E2E 步骤 4 的前两条 + 单测“队列满/404/503” |

## 12. 实施拆分

### P0（本期必须）

| 顺序 | 内容 | 文件 |
| --- | --- | --- |
| 1 | 新增 start DTO + controller + 路由（含 `@PermissionsBypass`、`@JsonIgnoreProperties`） | `trace/web/ingest/IngestStartRequest.java`(new)、`IngestController.java`、`TraceModule.java` |
| 2 | buffer 新增 `offerStart`（复用现有 `offer`） | `trace/service/MemoryTraceSpanBuffer.java` |
| 3 | startTrace + root/非 root 语义 + 竞态分支 + owner 校验 + clearErrorMessage + name 回填 | `trace/service/IngestService.java` |
| 4 | span_id 预查（接口默认实现返回空，PG 覆盖） | `trace/SpanStore.java`（default 方法）、`trace/PgSpanStore.java` |
| 5 | 服务端单测与回归 | `IngestServiceTest`、`IngestControllerTest`、`MemoryTraceSpanBufferTest` |
| 6 | 客户端 start 记录与默认方法 | `trace/CliTraceStart.java`(new)、`trace/TraceUploader.java` |
| 7 | 上传器：进程级队列/单线程/队首合并/Final 插队/冷却 | `trace/HttpTraceUploader.java` |
| 8 | 生命周期逐 step 上报 + input 截断 | `trace/TraceCollectorLifecycle.java` |
| 9 | 客户端单测 | `TraceCollectorLifecycleTest`、`HttpTraceUploaderTest` |
| 10 | dev E2E | — |

### P1（本期建议）

| 内容 | 文件 | 状态 |
| --- | --- | --- |
| ~~列表默认 Live 轮询 + 开关~~ | `core-ai-frontend/src/pages/traces/TraceList.tsx` | 用户决定不做（D9），手动 refresh |
| ~~详情容器轮询（`TraceDetailPanel` / `TraceDetail`）~~ | `core-ai-frontend/src/pages/traces/TraceDetailPanel.tsx`、`TraceDetail.tsx` | 同上 |
| ~~span 详情缓存失效 + 默认选中 sticky~~ | `core-ai-frontend/src/pages/traces/TraceInspector.tsx` | 同上（无轮询后无失效需求） |
| desktop 引擎关闭时 `flush(3s)` | `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerShutdown.java` | 已完成 |
| trace 元数据轻量查询（避免每批拉全量 input/output） | `TraceStore` + PG/Mongo 实现 | 推迟（P2） |

### P2（后续）

- 长 turn 心跳（D10 选心跳时）。
- `session.id` 上报（D11）。
- `CANCELLED` 终态（D16）。
- in-flight span 可视化（需要 span upsert 语义）。
- 服务端 live ingest 容量指标。

### 发布顺序

1. server 先上线（新端点 + 语义修正），对旧客户端零影响；
2. CLI 发版；desktop 随后 bump 内嵌引擎版本；
3. 前端可独立上线（即使没有实时数据也不报错）。

## 13. 决策台账

| 编号 | 决策 | 推荐 | 状态 |
| --- | --- | --- | --- |
| D1 | 实时范围只含“trace 行 + 已完成步骤”，不含进行中的 span | A | 已采纳（P0 实现） |
| D2 | 用独立 `POST /api/traces/ingest/start` 建立 trace 行，而不是占位 root span 或复用 ingest 加字段 | A | 已采纳（P0 实现） |
| D3 | 服务端只允许“带 root 的批次”或“匿名路径”写 trace 级字段 | 是 | 已采纳（P0 实现） |
| D4 | turn 结束仍全量重发一次作为兜底 | 是 | 已采纳（P0 实现） |
| D5 | 实时批次不携带图片 payload | 是 | 已采纳（P0 实现） |
| D6 | 只有 start 返回 404/405 才关闭实时，按 serverUrl 计 15 分钟冷却后自动重探；400 不降级 | 是 | 已采纳（P0 实现） |
| D7 | 客户端队列上限 512，Span 可丢、Final 不丢并插队 | 是 | 已采纳（P0 实现） |
| D8 | 合并策略：队首连续合并，单批 ≤32 条 / 1 MiB，不额外等待 | 是 | 已采纳（P0 实现） |
| D9 | ~~前端列表默认 5s 轮询（有 RUNNING 时 3s）+ 显式开关；详情 2s~~ | 不做自动轮询 | 用户 2026-11-27 决定：手动 refresh 即可，前端本期不改 |
| D10 | 超长单步导致的 30 分钟误判：接受自愈，还是本期就加心跳 | 接受自愈（心跳 P2） | 已采纳 |
| D11 | 本期不新增 `session.id` 上报 | 是 | 已采纳 |
| D12 | 加 `sys.trace.live.enabled` 运维开关（关闭时 start 返回 404，客户端自动降级） | 建议加 | 本期不做（客户端降级链已就绪，后续加开关无需改客户端） |
| D13 | 版本策略：server 先发，CLI 次之，desktop 跟随引擎 pin | 是 | 已执行（server 2.0.408 / CLI 2.0.95 同批提交，desktop 另发） |
| D14 | 鉴权路径对已存在的 traceId 做 owner 校验，不匹配即拒绝（安全修正，不建议留做可选项） | 是 | 已采纳（P0 实现） |
| D15 | 服务端按 span_id 预查，已存在 span 跳过 compact/offload/insert | 是 | 已采纳（P0 实现） |
| D16 | 本期是否把 `ctx.isCancelled()` 映射为 CANCELLED 终态 | 本期不做（P2），文档不承诺 | 已采纳 |
| D17 | desktop 引擎关闭时 `flush(3s)` 保住队中 Final | 是 | 已采纳（`AppServerShutdown`） |

## 14. Review 记录

### 14.1 评审信息

| 项 | 内容 |
| --- | --- |
| 时间 | 2026-11-27 |
| 模型 | 本机 agy CLI，`gemini-4-neon`（调用前已按约定用 `agy -p "/usage"` 查额度） |
| 输入 | 本文件的初稿 + `core-ai-cli`/`core-ai-server`/`core-ai-frontend` 源码 |
| 方式 | 只读评审，评审意见由当前 agent 核对源码后合入本文档 |
| 结论 | 初稿不可直接实现；修订后可进入实现评审 |

### 14.2 问题与处理

| # | 级别 | 评审意见 | 源码核实 | 处理 |
| --- | --- | --- | --- | --- |
| 1 | S1 | Final 全量重发会让已写入的 span 再走一遍 compact/offload/insert，单消费线程下拖慢全部 trace 写入 | `IngestService` 先 `offload` 再 `insertIfAbsent`；`MemoryTraceSpanBuffer` 单线程消费 | 采纳：§6.3.3 第 2 步 span_id 预查；P0 任务 4 |
| 2 | S1 | 新端点缺 RBAC 注解会 403；缺 traceId 归属校验会被跨用户注入；把 400 当作“不支持”会让进程永久降级 | `PermissionInterceptor` 对未声明权限的 `/api/*` 直接 403；`IngestController` 的鉴权路径未校验 trace 归属 | 采纳：`@PermissionsBypass`、owner 校验（D14）、只认 404/405（D6） |
| 3 | S1 | 上传器用进程静态线程 + 实例 endpoint 会串 server；`drainTo` 会打乱跨 trace 顺序 | `HttpTraceUploader` 现有 static executor，endpoint 为实例字段 | 采纳：进程级队列 + 每次请求携带 Target + 队首连续合并 |
| 4 | S1 | `insertIfAbsent` 失败分支会把 RUNNING 覆盖成 COMPLETED；多 Pod 下 start/子批次可能乱序；stale 的 `error_message` 不会被清除 | `IngestService` 竞态分支直接 `updateTrace(traceId, rootSpan)`；`updateTrace` 不清 errorMessage | 采纳：竞态分支按 root/非 root 分流；只回填空 name；Final 成功后 `clearErrorMessage` |
| 5 | S1 | 前端轮询落点错误（`TraceInspector` 是纯展示）；默认列表不会发现新 RUNNING trace；span 详情缓存不失效 | `TraceDetailPanel`/`TraceDetail` 负责取数；`TraceInspector` 缓存 `spanDetails` 且无失效逻辑；`TraceList` 仅在已有 RUNNING 时轮询 | 采纳：轮询放到容器 + 缓存失效 + 默认 Live 轮询（D9） |
| 6 | S2 | 增量请求若沿用 3×10s 重试会饿死 Final；desktop 退出没有 `flush` | `HttpTraceUploader` MAX_ATTEMPTS=3/10s；`appserver` 下无 `flush()` 调用 | 采纳：增量短超时少重试 + Final 插队淘汰同 trace 增量（D7）；desktop flush 列为 P1（D17） |
| 7 | S2 | `400` 熔断 + 单一 `static DISABLED` 会让所有 server 一起失能 | — | 采纳：D6 |
| 8 | S2 | 未映射取消状态、并行工具批次上报粒度、`beforeAgentRun` 的 input 未截断就上报 | 生命周期只产出 OK/ERROR；工具按批次回调；input 在 `finish()` 才截断 | 采纳：input 先截断；批次粒度写入限制；CANCELLED 列为 D16 |
| 9 | S3 | 不必重构 buffer；`findByTraceId` 全列查询在热路径偏重 | buffer 已有通用 `offer`；`TraceStore.findByTraceId` 返回完整行 | 采纳：新增 `offerStart` 复用 `offer`；轻量查询列 P1 |

### 14.3 未采纳 / 降级

| 评审建议 | 处理 | 理由 |
| --- | --- | --- |
| start 冲突时同时回填 input | 本期只回填 name | 避免与最终 root 的 input 语义分叉；input 由 Final 保证 |
| 增量请求完全不重试 | 保留 1 次、3s 超时 | 弱网下的可见性收益大于成本 |
| span 详情也统一交给容器轮询 | 仅失效缓存 + 容器轮询 | 避免组件持有数据获取职责 |

## 15. 修改记录

| 日期 | 变更 |
| --- | --- |
| 2026-11-27 | 初稿：现状核对、方案选型、协议/客户端/服务端/前端设计、测试与实施拆分 |
| 2026-11-27 | 按架构评审修订：span 预查、owner 校验、404/405 + TTL 降级、进程级上传队列、竞态与错误信息清除、前端落点与缓存、实施拆分与决策台账补齐 |
| 2026-11-27 | P0 实现完成（server 2.0.408 / CLI 2.0.95）：start 端点与 root/非 root 语义、owner 校验、span 预查、进程级队列上传器、逐 step 上报、引擎关闭 `flush(3s)`；决策台账定版（D9 用户决定不做前端自动轮询，D12 运维开关本期不做） |



