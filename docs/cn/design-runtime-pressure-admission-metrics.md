# Core AI 运行时压力信号、准入与度量语义 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | DRAFT v0.1 + 实现中；2026-11-24 用户授权开始实现，采用提案默认值（D-C1…D-C8） |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-24 |
| 作者 | core-ai |
| 关联问题 | Q6（内存压力信号）、Q7（准入）、Q8b（度量语义 / footprint） |
| 关联证据 | `docs/validation/2026-11-24-uat-runtime-metrics-and-heap-review.md` P4 / P5 / P6 / P7 / P8 / P9 |
| 目标 | 用真实物理信号判断“能不能接活”，用明确语义回答“一个 Pod 能扛多少会话” |
| 本次交付 | 仅技术设计；不修改业务实现、不建表、不迁移、不部署 |
| 事实与提案 | 附录 B 为源码事实；正文新增的 `PressureSignal`、`oldGenPercent`、`_sum` 字段、阈值均为提案 |

## 结论摘要

1. **“内存压力”不是一个数，而是三个不同的物理量**：瞬时 `heapUsed`（年轻代分配压力）、after-GC / Old Gen（留存压力）、container RSS（JVM 之外也计入的容器压力）。把它们混成一个 `heapUsedPercent` 会导致误熔断（P9）和错误归因（P7）。
2. **准入只看留存压力，不看瞬时 Young Gen**。建议主信号为 `oldGenPercent`（`G1 Old Gen`）+ 最近一次“有实际回收”的 GC after 值；瞬时 `heapUsed` 只作为灾难性快速跳闸（>95% 连续 2 次），不参与常规阈值。
3. **G1 的 `latestGcInfo()` 不能直接当 after-GC**：`G1 Concurrent GC` 的 before == after，`G1 Young` 之后仍包含未回收的 Old Gen / Humongous。必须按 GC bean 区分，并忽略“没有回收动作”的事件。
4. **准入路由不能靠精确路径枚举**。`AdmissionInterceptor` 只匹配 `POST /api/sessions/messages/stream`，漏掉 `POST /api/sessions/{id}/messages` 等写入口；需要一个“工作路由注册表 + 覆盖测试”，而不是再加一条 if。
5. **多副本口径必须显式**：`(all replicas)` 不能同时表示 max 和 sum。Gauge 分两类——比率用 `max pod`，可加量用 `fleet sum`（按分钟对齐后跨 pod 求和，再取桶内峰值）；前端标题/tooltip 必须与后端字段同名同义。
6. **Counter delta 必须跨桶**：当前只算桶内 `last - first`，桶与桶之间的增量丢失，且 `Math.max(0, ...)` 会把乱序/重启静默归零。需要 per-pod baseline + 重启归零处理 + `ORDER BY`。
7. **SQL 顺序不是可选项**：`PgRuntimeSampleStore.range()` 没有 `ORDER BY`，`LinkedHashMap` 建桶、`firstPerPod/lastPerPod` 都依赖输入顺序；7 天查询触及 `LIMIT 50000` 时截断也是无序的。
8. **Footprint 是“逻辑内容代理”，不是 JVM 实际内存**。当前只算顶层 `messages + history`，不含 subagent、turn buffer、SSE replay；只在 turn 结束刷新，长 turn 期间完全盲区。需要定义 scope、刷新节奏和高水位。
9. **C0 可以先做**：`ORDER BY`、跨桶 delta、前端补字段、路由覆盖、SSE buffer 落库，都不依赖信号模型；C1 再做 old-gen 信号 + 滞回；C2 做 footprint v2 + fleet 语义。
10. **没有“正确阈值”，只有可校准的信号**：先用 `admissionEnabled=false` 记录 saturated，和真实 OOM/GC/驱逐对比，再开启拒绝；阈值必须可配置、可回滚。

## 阅读指南与目录

- 产品 / Reviewer：先读 §1、§2、§5.5、§8、§11，确认“面板上的每个数字到底是什么意思”。
- 后端 / SRE：读 §3–§7、§9–§10，重点关注信号、路由覆盖、聚合和灰度。
- 前端：读 §5.5、§7.3，字段名与 tooltip 必须和后端语义一致。
- 测试：读 §7，尤其是路由覆盖、跨桶 delta、不变量断言。

| 章节 | 内容 |
|---|---|
| [1. 背景与第一性原理](#_1-背景与第一性原理) | 三个物理量、三类失真 |
| [2. 目标、非目标与不变量](#_2-目标、非目标与不变量) | 本设计的硬约束 |
| [3. 现状与源码事实](#_3-现状与源码事实) | 采集、准入、聚合、footprint、前端 |
| [4. 核心模型](#_4-核心模型) | PressureSignal、准入状态机、度量语义、footprint |
| [5. 详细设计](#_5-详细设计) | 信号、路由、聚合、footprint、Dashboard、失败 |
| [6. 可观测性](#_6-可观测性) | 信号自身与告警 |
| [7. 测试与验收](#_7-测试与验收) | 单测、集成、路由覆盖、面板快照 |
| [8. 备选方案与取舍](#_8-备选方案与取舍) | 瞬时 / after-GC / Old Gen / RSS |
| [9. 分阶段实施](#_9-分阶段实施) | C0 / C1 / C2 |
| [10. 风险](#_10-风险) | 误熔断、时钟、口径 |
| [11. 关键决策与未决事项](#_11-关键决策与未决事项) | 需要拍板的问题 |
| [附录 A：需求与治理](#附录-a-需求与治理) | 原文与约束 |
| [附录 B：源码事实](#附录-b-源码事实) | 文件与行号 |
| [附录 C：Review 与修改历史](#附录-c-review-与修改历史) | 版本变化 |

## 1. 背景与第一性原理

### 1.1 三个物理量

| 物理量 | 代表什么 | 采样成本 | 适用 | 不适用 |
|---|---|---|---|---|
| `heapUsedPercent` | 当前堆占用，包含 Young Gen 垃圾 | 极低 | 灾难性快速跳闸、分配速率观察 | 常规准入（GC 前误报） |
| `heapAfterGcPercent` / `oldGenPercent` | GC 后仍存活的留存对象 | 低（MXBean） | 准入、容量规划、泄漏判断 | 瞬时尖峰 |
| `containerMemoryPercent` / `rss` | 容器整体内存，含堆外、元空间、线程栈 | 低（cgroup/proc） | 容器 OOM 风险、JVM 外泄漏 | 单会话归因 |

P9 的根因是**把第一个当成了第三个**：85% 的瞬时 `heapUsed` 可能只是 Young Gen 还没回收，下一秒就掉到 40%；用它拒绝请求会抖动误伤。

### 1.2 三类失真

1. **信号失真（P7/P9）**：GC 事件混用、瞬时值当留存值、缺少 Old Gen。
2. **聚合失真（P4/P5）**：无 `ORDER BY`、`max` 标注成 `all replicas`、跨桶 delta 丢失。
3. **归因失真（P6/P8）**：采集了 `heap_after_gc` / `container` / `est` / `img`，前端不展示；footprint 只在 turn 结束刷新，长 turn 看不到。

### 1.3 要回答的问题

- **准入**：这个 Pod 现在还能接一个 turn 吗？依据必须是“接了之后会不会 OOM”，而不是“这一刻堆用了多少”。
- **容量规划**：一个 Pod 能扛多少会话？依据必须是“会话留存字节 / 分布 / 高水位”，而不是“当前活跃会话数”。
- **故障归因**：是哪一个会话 / 哪一类图片 / 哪一次 GC 把堆顶上去的？依据必须是可关联的 footprint + GC + trace。

## 2. 目标、非目标与不变量

### 2.1 目标

1. 准入信号与真实 OOM 风险相关，且不因 Young GC 抖动。
2. 所有创建工作（新会话、聊天 turn、run、channel、gateway）都有准入覆盖，或有明确 allowlist。
3. 每个面板数字有唯一语义（max pod / fleet sum / bucket peak / per-minute sum），前后端一致。
4. Counter delta 跨桶正确，pod 重启可识别。
5. Footprint 覆盖会话的真实内容（含 subagent / buffer / SSE），并有高水位。
6. 先校准、再拦截；可配置、可回滚。

### 2.2 非目标

- 不做 HPA / 自动扩缩容。
- 不改 JVM 参数、GC 选型、容器 limit。
- 不引入 Prometheus / 新监控栈；继续用 `runtime_samples` + action log。
- 不做 per-user 堆内存精确核算（只做会话级逻辑估算）。
- 不解决 P1/P2 的图片驻留（Design A/B）；本设计假设它们会降低 footprint。

### 2.3 不变量

1. **信号新鲜度**：准入使用的样本不得超过 `SIGNAL_MAX_AGE`（建议 15s）；过期时 fail-open + 告警，不能用陈旧值拒绝全部流量。
2. **滞回**：进入/退出饱和使用不同阈值 + 连续样本数 + 冷却时间；不允许在阈值附近来回跳。
3. **比率不求和**：`heapUsedPercent` / `containerPercent` 等比率只能取 max/avg，不能跨 pod 相加。
4. **可加量先对齐再求和**：sessions / in-flight turns / runs queued 等先按分钟对齐（每 pod 取该分钟最后一个样本），再跨 pod 求和。
5. **Counter 有 baseline**：每个桶的 delta 以“桶开始前该 pod 的最后一个样本”为 baseline；`curr < prev` 视为重启，计数为 `curr`，不得静默 `max(0)`。
6. **顺序确定**：范围查询必须有 `ORDER BY collected_at, pod`；桶用有序结构；截断可见。
7. **路由全覆盖**：每个创建工作路由要么被 guard，要么在 allowlist 中；有自动化测试枚举。
8. **Footprint 可加**：`estimatedBytes = Σ components + overhead`；版本化；高水位单调不减（会话生命周期内）。
9. **面板同义**：前端字段名 / 标题 / tooltip 与后端语义一一对应，不允许“后端 max、前端写 all replicas”。
10. **可回滚**：`admissionEnabled=false` 仍记录 saturated；信号/阈值可配置；新字段向后兼容。

## 3. 现状与源码事实

### 3.1 采集

| 事实 | 代码 |
|---|---|
| `JvmMemoryMetrics.snapshot()` 采集 heapUsed/heapMax、before/after GC、container、RSS | `JvmMemoryMetrics.java:34-50` |
| `latestGcInfo()` 遍历所有 GC bean，取 `getLastGcInfo()` 最晚的一个 | `JvmMemoryMetrics.java:67-76` |
| `heapUsage()` 把所有 heap pool 的 used 相加；没有 Old Gen 专项 | `JvmMemoryMetrics.java:52-65` |
| `RuntimeStatsJob` 每分钟写 action log + `RuntimeSample` | `RuntimeStatsJob.java:32-100` |
| `sseStats()` 已计算 `bufferedBytes/bufferedEvents/droppedTotal`，但只写 action log，不写 `RuntimeSample` | `RuntimeStatsService.java:84-90`；`RuntimeStatsJob.java:68-70`；`RuntimeSample.java:18-170` 无 SSE 字段 |
| `SessionFootprintTracker.refresh()` 只在 turn 结束调用 | `SessionFootprintTracker.java:24-31`；`InProcessAgentSession.java:278-291`（`:289`） |
| `AgentFootprintCalculator` 只算顶层 `messages + history`，identity 去重 | `AgentFootprintCalculator.java:20-38` |

### 3.2 准入

| 事实 | 代码 |
|---|---|
| `RuntimeCapacityGuard.saturatedScope()` 用 `heapUsedPercent >= heapWatermarkPercent` 和 `inFlightTurns >= max` | `RuntimeCapacityGuard.java:43-48` |
| `heapUsedPercent()` 每 5s 采样一次瞬时 `heapUsed` | `RuntimeCapacityGuard.java:64-70`、`:121-126` |
| 默认 watermark 85%，可配 50–99 | `RuntimeLimits.java:13`、`:32` |
| `admissionEnabled=false` 时只记录 saturated，不拒绝 | `AdmissionInterceptor.java:44-53` |
| 拦截器只匹配精确路径：`POST /api/sessions`、`POST /api/sessions/messages/stream`、`POST /api/runs`、`/api/channels/`、`/api/gateway/v1/`、`PUT /api/sessions/events` | `AdmissionInterceptor.java:20-31` |
| 未覆盖 `POST /api/sessions/{id}/messages` 等写入口（P9） | 同上；需路由审计确认全集 |
| 429 语义由 `CapacityExceededException` 携带 scope + retryAfter | `AdmissionInterceptor.java:52-53`；`RuntimeCapacityGuard.java:117-119` |

### 3.3 聚合与存储

| 事实 | 代码 |
|---|---|
| `PgRuntimeSampleStore.range()` 无 `ORDER BY`，`LIMIT ?` | `PgRuntimeSampleStore.java:170-174` |
| `RuntimeSampleService.series()` 用 `LinkedHashMap` 建桶，依赖输入顺序 | `RuntimeSampleService.java:54-69` |
| `Bucket` 的 `firstPerPod` / `lastPerPod` 依赖输入顺序；delta 只算桶内 | `RuntimeSampleService.java:120-185` |
| 并发 gauge 取 `max`（单 pod 峰值），前端写 `(all replicas)` | `RuntimeSampleService.java:147-151`；`RuntimeWatermarks.tsx:132-143` |
| 7 天查询 `MAX_SAMPLES = 50000`，截断不可见 | `RuntimeSampleService.java:28`、`:72-74` |
| `pods()` 按 `collectedAt` 取每 pod 最新，顺序无关；但 `series()` 不是 | `RuntimeSampleService.java:35-52` |

### 3.4 前端

| 事实 | 代码 |
|---|---|
| Heap 图只画 `heap_peak_percent` / `heap_avg_percent` | `RuntimeWatermarks.tsx:119-130` |
| Concurrency 图写 `(all replicas)`，但数据是 max | `RuntimeWatermarks.tsx:132-143` |
| Refusals 图用 bucket delta | `RuntimeWatermarks.tsx:146-159` |
| PodCard 只显示 Heap/Sessions/Turns/Runs/Sandboxes/Span/Saturated，不显示 after-GC、container、est/img | `RuntimeWatermarks.tsx:164-186` |
| 后端 `RuntimePodView` 已返回 `heapAfterGcPercent`、`containerMemoryPercent`、`sessionsEstimatedBytesTotal`、`sessionsInlineImageBytesTotal` | `RuntimePodView.java:34-65` |
| `RuntimeSeriesView.Point` 已返回 `span_ingest_depth_peak`，前端未画 | `RuntimeSeriesView.java:47-48` |

### 3.5 约束

- `RuntimeSample` 是 core-ng Mongo entity：不能有 `Object`/primitive；新增字段要有 `@Field`，集合已注册。
- `PgRuntimeSampleStore` 是手写 SQL；新增列要同步 INSERT/SELECT/params/row mapping。
- `RuntimeSeriesView` / `RuntimePodView` 是 WebService DTO：enum 需要 `@Property`，字段名即前端契约。
- 多副本时钟可能轻微漂移；按分钟对齐要允许 ±5s 容差，或以 `collected_at` 为准。

## 4. 核心模型

### 4.1 `PressureSignal`

提案结构：

```java
public record PressureSignal(
        Double heapUsedPercent,          // 瞬时，仅用于灾难跳闸
        Double heapAfterGcPercent,       // 最近一次有回收的 GC 之后
        Double oldGenPercent,            // G1 Old Gen used / heapMax
        Double containerMemoryPercent,   // cgroup current / max
        Double rssPercent,               // VmRSS / cgroup max
        Long   gcId,
        Long   gcAgeMs,
        String gcKind,                   // young / mixed / old / concurrent / unknown
        Long   reclaimedBytes,           // before - after
        Boolean signalStale,
        Long   sampledAtMillis
) {}
```

- `heapUsedPercent`、`oldGenPercent`、`containerMemoryPercent` 是三个不同的物理量，不合并成一个。
- `heapAfterGcPercent` 只在 `reclaimedBytes > 0` 且 `gcKind != concurrent` 时更新；`G1 Young` 的值仍可能包含 Old Gen，因此只作为辅助，不作为主准入信号。
- `oldGenPercent` 是主信号；`containerMemoryPercent` 是容器 OOM 的兜底信号。
- `signalStale` 由采样时间与当前时间计算；超过 `SIGNAL_MAX_AGE` 置 true。

### 4.2 准入状态机

```
NORMAL ──(enter condition for N samples)──> SATURATED
   ▲                                           │
   └──(exit condition for M samples + cooldown)┘
```

- `enter condition`：`oldGenPercent >= heapWatermarkPercent` 连续 `ENTER_SAMPLES`（建议 2）× 5s；或 `containerMemoryPercent >= containerWatermarkPercent`（建议 90%）连续 2 次；或 `heapUsedPercent >= 95` 连续 2 次（灾难跳闸）。
- `exit condition`：`oldGenPercent <= heapWatermarkPercent - HYSTERESIS`（建议 10 个百分点）连续 `EXIT_SAMPLES`（建议 3）；且 `heapUsedPercent < 90`；冷却 `COOLDOWN_MS`（建议 60s）。
- 状态是 per-pod 的；不跨 pod 共享。用户/会话配额继续用 Redis/DB 的共享计数。
- `admissionEnabled=false`：状态机照常运行，只记录 `saturated`，不拒绝；这是校准模式。
- `signalStale=true`：不进入 SATURATED（fail-open），记录 `signal_stale`；如果 `containerMemoryPercent >= 95` 仍可拒绝（容器 OOM 是硬风险），由 D-C7 决定。

### 4.3 度量语义

每个字段必须标注四要素：**对象**（pod / fleet）、**类型**（gauge / counter / ratio）、**时间**（instant / bucket peak / per-minute）、**聚合**（max / sum / avg）。建议命名：

| 后缀 | 语义 | 示例 |
|---|---|---|
| `_peak` | 桶内所有样本的最大值 | `heap_peak_percent` = 所有 pod 所有样本的最大值（max pod peak） |
| `_sum_peak` | 先按分钟对齐、跨 pod 求和，再取桶内最大 | `sessions_sum_peak` = fleet 会话峰值 |
| `_avg` | 桶内样本平均；必须注明是“按 pod 平均”还是“按样本平均” | `heap_avg_percent` 建议定义为“每 pod 分钟均值再跨 pod 平均” |
| `_delta` | 桶内增量，跨桶有 baseline | `turns_rejected_delta` |
| `_max` | 单点最大值（非桶） | `sessions_estimated_bytes_max` |

比率（percent）**永远不能**有 `_sum`；可加量（sessions/turns/runs/sandboxes/bytes）才有 `_sum`。

### 4.4 Footprint 模型

`AgentFootprint` v2 提案：

| 组件 | 说明 | 来源 |
|---|---|---|
| `messagesBytes` | working messages 的文本 + 工具参数 + ref 元数据 | `AgentFootprintCalculator` |
| `historyBytes` | 展示 history 的文本（B0 后为克隆） | 同上 |
| `subagentBytes` | 子 agent 的 messages/history | `SubagentThreadRegistry` |
| `turnBufferBytes` | `ChatMessageService.bufferBySession` 的 thinking/tools/events | Server |
| `sseReplayBytes` | `SessionChannelService` 的 replay buffer | Server |
| `mediaRefBytes` | `MediaRef` 元数据 + store 指针 | Design A |
| `storeBytes` | `SessionMediaStore` 实际占用（A1） | Design A |

- `estimatedBytes = Σ components + per-message/session overhead`。
- `ESTIMATE_VERSION` 升到 2；不同版本的样本不能混算。
- 高水位：`peakEstimatedBytes`、`peakInlineImageBytes`、`peakContextTokens`、`peakAt`；会话生命周期内单调不减。
- 刷新：turn 结束（现有）+ 长 turn 内每 `FOOTPRINT_REFRESH_MS`（建议 30s）或每次 tool result 后（二选一，D-C5）；刷新用 dirty flag 防止 O(n) 过频。

## 5. 详细设计

### 5.1 信号采集

#### 5.1.1 GC 事件

`JvmMemoryMetrics` 改造：

1. 保留 `GC_BEANS`，但按 bean 记录 `lastGcInfo` 与 bean 名称（`G1 Young Generation` / `G1 Old Generation` / `G1 Concurrent GC`）。
2. `gcKind`：
   - `G1 Young Generation` → `young`
   - `G1 Old Generation` → `old`（Mixed/Old 都可能在这里；按 `reclaimedBytes` 和 pool 变化辅助判断）
   - `G1 Concurrent GC` → `concurrent`（无回收动作，忽略）
3. `heapAfterGc`：只在 `reclaimedBytes > max(1% heap, 1MB)` 且 `gcKind != concurrent` 时更新；否则沿用上一次有效值。
4. `oldGenUsed`：`MemoryPoolMXBean.getName().equals("G1 Old Gen")` 的 `getCollectionUsage().getUsed()`（最近一次 GC 后）或 `getUsage().getUsed()`；`oldGenMax` 用 `heapMax`（G1 Old Gen 的 max 可能是 -1）。
5. `containerMemoryPercent` / `rssPercent` 保持现有 cgroup / proc 读取；加 `null` 安全。
6. `signalStale`：`now - sampledAt > SIGNAL_MAX_AGE`。

#### 5.1.2 采样与窗口

- `RuntimeCapacityGuard` 内部维护一个环形窗口（例如 6 个 5s 样本 = 30s）：
  - `oldGenPercent` 的滑动平均/最小值用于进入/退出判断；
  - `heapUsedPercent` 的连续计数用于灾难跳闸；
  - 窗口外样本丢弃，避免长期陈旧。
- `RuntimeStatsJob` 每分钟落库时，把 `oldGenPercent`、`gcKind`、`reclaimedBytes`、`containerPercent`、`rssPercent` 写入 `RuntimeSample`（新增字段）。
- `RuntimeCapacityGuard` 的采样与 `JvmMemoryMetrics.snapshot()` 共用同一实现，避免两套口径。

### 5.2 准入策略

#### 5.2.1 路由注册表

把 `AdmissionInterceptor.guarded()` 的精确匹配改成 `WorkRoute` 列表：

```java
record WorkRoute(HTTPMethod method, String pattern, String scope) {}
```

- 支持 `{id}` 占位符 / 前缀匹配；匹配顺序从具体到通用。
- 必须覆盖（至少）：
  - `POST /api/sessions`
  - `POST /api/sessions/messages/stream`
  - `POST /api/sessions/{sessionId}/messages`
  - `PUT /api/sessions/events`
  - `POST /api/runs`
  - `POST /api/channels/**`
  - `POST /api/gateway/v1/**`
  - A2A / hub / sandbox-hub 中会启动 turn 的写入口（需路由审计确认）
- 只读 / 基础设施路径（GET、health、auth、artifacts、admin metrics）显式 allowlist，不 guard。
- `AdmissionRouteTest`：扫描已知 work controller 的 `@Path`/WebService 方法，断言每个 route 要么在 `guarded`，要么在 `allowlist`；新增路由未分类时测试失败。

#### 5.2.2 拒绝语义

- HTTP 429 + `Retry-After`（30s）+ body `{scope, retryAfterSeconds, message}`。
- scope：`heap_retained` / `heap_catastrophic` / `pod_turns` / `user_turns` / `container`。
- 不排队：准入只做快速拒绝；排队由 per-session command queue / run queue 负责。
- 用户级公平：只在 chat 入口检查 `maxConcurrentTurnsPerUser`（现有行为），不扩大到 run/gateway。
- 校准：`admissionEnabled=false` 时记录 `wouldReject`，并按 scope 分桶；每周对比真实 OOM/驱逐。

#### 5.2.3 多副本

- 堆是 pod-local：准入只看本 pod 信号，不查其他 pod。
- 用户/会话/沙箱配额是 fleet 级：继续用现有 Redis/DB 计数。
- 不要用“所有 pod 的 heap max”做准入；那会把其他 pod 的压力错误地转嫁到本 pod。

### 5.3 聚合与存储

#### 5.3.1 查询顺序

`PgRuntimeSampleStore.range()` 改为：

```sql
SELECT ... FROM app.runtime_samples
WHERE collected_at >= ? AND collected_at <= ?
ORDER BY collected_at ASC, pod ASC
LIMIT ?
```

- 需要 `(collected_at)` 或 `(collected_at, pod)` 索引；确认现有索引。
- `LIMIT` 改为 `limit + 1` 或额外 `count`，让 `RuntimeSampleService` 能标记 `truncated=true`。
- `pods()` 继续按 `collectedAt` 取每 pod 最新，但加 `sampleAgeSeconds` / `stale`。

#### 5.3.2 桶与 gauge

- 用 `TreeMap<Long, Bucket>` 或先按时间排序；`Bucket` 内部 per-pod 用 `first/last` 时按 `collectedAt` 比较，不依赖输入顺序。
- Gauge：
  - `heap_peak_percent` = 桶内所有样本最大值（max pod peak）；前端标 “max pod”。
  - `heap_avg_percent` = 先按 pod 求该桶内均值，再跨 pod 平均；标 “fleet avg”。
  - 新增 `heap_after_gc_peak_percent`（max pod）、`old_gen_peak_percent`（max pod）、`container_peak_percent`（max pod）。
  - 新增 `sessions_sum_peak`、`in_flight_turns_sum_peak`、`runs_queued_sum_peak`、`sandboxes_sum_peak`：先按分钟对齐（每 pod 取该分钟最后一个样本），跨 pod 求和，再取桶内最大。保留现有 `_peak` 作为 max pod。
- 比率字段不允许 `_sum`；前端图例必须写清 “max pod” / “fleet sum”。

#### 5.3.3 Counter delta

- 新增 `RuntimeSampleStore.baseline(from)`：返回每个 pod 在 `from` 之前最后一个样本；或在 `range` 时多取一个桶的 lookback。
- 每个桶的 delta：
  ```
  for each pod:
      prev = last sample before bucket start (or last sample of previous bucket)
      curr = last sample in bucket
      if prev == null: delta += curr (first observation)
      else if curr >= prev: delta += curr - prev
      else: delta += curr; resetDetected++
  ```
- 不再用 `Math.max(0, last-first)`；乱序/重启不会被静默吞掉。
- `firstPerPod` 只用于“该 pod 是否在桶内报告”，不再用于 delta。
- `truncated=true` 时，delta 可能不完整；前端显示警告，不把不完整当完整。

#### 5.3.4 时间对齐

- 以 `collected_at` 的分钟边界对齐；允许 ±5s 漂移。
- 如果某 pod 在某个分钟没有样本，沿用上一个分钟的值（carry forward），并在 `pods_reporting` 里体现缺失；不要把它当成 0。
- `pods_reporting` 是“桶内有样本的 pod 数”，不是“部署总 pod 数”；面板需要显示两者（`expected_pods` 可从部署配置/最近 10 分钟见过的最多 pod 数推断，D-C10）。

### 5.4 Footprint 刷新与高水位

#### 5.4.1 采集范围

`AgentFootprintCalculator` v2 的输入不再只是 `messages + history`，而是 `FootprintScope`：

```java
record FootprintScope(
        List<Message> messages,
        List<Message> history,
        List<Agent> subagents,
        TurnBufferSizes turnBuffers,
        SseReplaySizes sseReplay
) {}
```

- `messages` / `history`：沿用现有字符计数；history 在 B0 后是文本克隆，计入 `historyBytes`（会与 messages 的文本重复，但这是实际内存，接受）。
- `subagents`：从 `ExecutionContext.getSubagentThreads()` 拿活跃 agent 的 `footprint()`；只算 live 子 agent，不算已持久化的。
- `turnBuffers`：`ChatMessageService` 暴露 `bufferBytes(sessionId)`（thinking/tools/events 的字符数）；不要复制内容。
- `sseReplay`：`SessionChannelService` 暴露 `bufferedBytes(sessionId)`（已有全局 `bufferedBytes()`，P8 建议按 session 暴露）。
- `mediaRefBytes` / `storeBytes`：A1 后加入；B0 阶段为 0。

`estimatedBytes` = 各组件之和 + `MESSAGE_OVERHEAD_BYTES × messages` + `SESSION_OVERHEAD_BYTES`；`ESTIMATE_VERSION=2`。

#### 5.4.2 刷新节奏

- turn 结束：现有 `finishTurn()` 已调用 `footprint.refresh()`（`InProcessAgentSession.java:289`），保留。
- 长 turn 内：每 `FOOTPRINT_REFRESH_MS`（建议 30s）刷新一次，或在每次 tool result 后刷新（D-C5）。
- 刷新是 `O(messages)`；用 `dirty` 标志避免无变化时重复扫描；多次请求共享一次结果。
- 高水位：`SessionFootprintTracker` 在每次 `refresh()` 后更新 `peakEstimatedBytes` / `peakInlineImageBytes` / `peakContextTokens` / `peakAt`，只增不减。
- `SessionSnapshot` 增加 peak 字段；`RuntimeStatsService.SessionAggregate` 汇总：
  - `sessions_estimated_bytes_peak_total`（各会话 peak 之和）
  - `sessions_estimated_bytes_peak_max`（单会话最大 peak）
  - `sessions_inline_image_bytes_peak_total`
- `RuntimeSample` 新增字段：
  - `old_gen_percent`、`gc_kind`、`gc_reclaimed_bytes`
  - `sse_buffer_bytes`、`sse_buffer_events`、`sse_dropped_total`
  - `sessions_estimated_bytes_peak_total`、`sessions_estimated_bytes_peak_max`、`sessions_inline_image_bytes_peak_total`
- `RuntimeStatsJob.applySessions` / `applyMemory` 同步写入；`PgRuntimeSampleStore` 的 INSERT/SELECT/params/row mapping 同步扩展。

#### 5.4.3 与 A1 的关系

- A1 后 `inlineImageBytes` 接近 0，`mediaRefBytes`/`storeBytes` 成为新的归因字段；`ESTIMATE_VERSION` 再次升级。
- A1 的 `SessionMediaStore` 统计（§Design A）应直接提供给 `RuntimeStatsService`，避免两套字节计数。

### 5.5 Dashboard 与字段语义

#### 5.5.1 图表

| 图 | 字段 | 语义 | 说明 |
|---|---|---|---|
| Retained heap | `heap_after_gc_peak_percent`、`old_gen_peak_percent`、`container_peak_percent` | max pod | 真实留存压力；watermark 线 |
| Allocation pressure | `heap_peak_percent`、`heap_avg_percent` | max pod / fleet avg | 年轻人压力；不用于准入 |
| Session footprint | `sessions_estimated_bytes_peak_total`、`sessions_inline_image_bytes_peak_total`、`sessions_estimated_bytes_peak_max` | fleet sum / max session | 容量规划主线 |
| Work | `sessions_sum_peak`、`in_flight_turns_sum_peak`、`runs_queued_sum_peak`、`sandboxes_sum_peak` + 现有 `_peak` | fleet sum / max pod | 双线并排，避免 max 当 sum |
| Refusals | `turns_saturated_delta`、`turns_rejected_delta`、`runs_rejected_delta`、`sandboxes_rejected_delta`、`span_ingest_dropped_delta` | 跨桶 delta | 桶间增量不再丢 |
| Span ingest | `span_ingest_depth_peak`、`span_ingest_dropped_delta` | max pod | P6 已采集未展示 |

#### 5.5.2 PodCard

增加：

- `After GC %`（`heap_after_gc_percent`）
- `Old Gen %`（`old_gen_percent`，A1 后）
- `Container %`（`container_memory_percent`）
- `Session est / inline img MB`（`sessions_estimated_bytes_total` / `sessions_inline_image_bytes_total`）
- `Signal age`（采样年龄；stale 标红）
- `SSE buffer`（`sse_buffer_bytes`）

保留现有 Heap/Sessions/Turns/Runs/Sandboxes/Span/Saturated；删除或改名容易被误读的 “Heap” 为 “Heap used (instant)”。

#### 5.5.3 标题与 tooltip

- `(all replicas)` 禁止裸用；必须写 `fleet sum` 或 `max pod`。
- tooltip 示例：
  - “max pod：桶内所有副本中最高的单点值，用于判断最差 pod 是否接近上限。”
  - “fleet sum：按分钟对齐后跨副本求和，再取桶内峰值，用于判断集群总量。”
  - “bucket peak：桶内最大值，不是平均值。”
- 前端字段名与后端 `@Property` 一一对应；加一个 `RuntimeWatermarks.semantics.test.tsx` 快照测试，断言图例文本。

### 5.6 失败矩阵

| 失败 | 行为 | 指标 |
|---|---|---|
| 样本查询无 `ORDER BY` / 乱序 | 桶顺序错、delta 归零 | `runtime_samples_out_of_order`（测试/防御日志） |
| `LIMIT` 截断 | `truncated=true`；前端警告 | `runtime_series_truncated` |
| Pod 重启（counter 归零） | delta 计为 `curr`；`resetDetected++` | `runtime_counter_reset_total` |
| 某 pod 缺样本 | carry forward；`pods_reporting` 减少 | `runtime_pod_missing_total` |
| 信号过期 | fail-open + 告警；容器 >95% 可拒绝（D-C7） | `runtime_signal_stale` |
| 阈值抖动 | 滞回 + 冷却；状态切换计数 | `runtime_admission_transitions` |
| 路由漏配 | 测试失败；生产记录 `unguarded_work_route` | `runtime_unguarded_route_total` |
| footprint 刷新过慢 | 采样降频；记录耗时 | `runtime_footprint_refresh_ms` |
| 新字段未落库 | 版本指纹不匹配；面板标 `N/A` | `runtime_sample_version` |

## 6. 可观测性

| 指标 | 类型 | 用途 |
|---|---|---|
| `runtime_signal_old_gen_percent` | gauge | 主准入信号 |
| `runtime_signal_after_gc_percent` | gauge | 留存压力辅助 |
| `runtime_signal_container_percent` | gauge | 容器 OOM 风险 |
| `runtime_signal_stale_total` | counter | 采样/线程问题 |
| `runtime_admission_state` | gauge | 0=NORMAL, 1=SATURATED |
| `runtime_admission_transitions_total` | counter | 抖动检测 |
| `runtime_admission_would_reject_total` | counter | 校准模式 |
| `runtime_admission_rejected_total` | counter | 实际拒绝 |
| `runtime_unguarded_route_total` | counter | 路由覆盖漏洞 |
| `runtime_series_truncated_total` | counter | 查询截断 |
| `runtime_counter_reset_total` | counter | pod 重启 |
| `runtime_footprint_refresh_ms` | histogram | 刷新成本 |
| `runtime_footprint_version_mismatch_total` | counter | 口径混算 |

告警建议：

- `runtime_signal_stale_total > 0`（5 分钟）→ 采集线程/调度异常。
- `runtime_admission_transitions_total` 突增 → 阈值/滞回不合适。
- `runtime_unguarded_route_total > 0` → 新路由未分类。
- `runtime_series_truncated_total > 0` → 查询窗口/limit 不足。
- `runtime_footprint_version_mismatch_total > 0` → 滚动升级/口径混算。

## 7. 测试与验收

### 7.1 单元测试

| 对象 | 断言 |
|---|---|
| `JvmMemoryMetrics` | `G1 Concurrent GC` 不更新 after-GC；无回收的 Young GC 不更新；Old Gen 取值正确；stale 计算正确 |
| `RuntimeCapacityGuard` | 进入需 N 次；退出需 M 次 + 冷却；stale fail-open；灾难跳闸独立 |
| `AdmissionInterceptor` | 路由模式匹配；allowlist；429 body/Retry-After；`admissionEnabled=false` 只记录 |
| `RuntimeSampleService` | 乱序输入桶顺序正确；跨桶 delta；pod 重启；缺样本 carry forward；truncated 标记 |
| `RuntimeSampleService.delta` | `prev=null`、`curr<prev`、`curr=prev` 三种分支 |
| `AgentFootprintCalculator` | 组件求和 = total；版本化；高水位单调 |
| `SessionFootprintTracker` | turn 结束刷新；长 turn 定时刷新；peak 只增不减 |

### 7.2 集成测试

1. **乱序样本**：插入 3 个 pod 的乱序样本 → `series()` 的 bucket 顺序、first/last、delta 正确。
2. **跨桶 delta**：桶 A 结束到桶 B 开始之间递增 → 增量计入 B，不丢失。
3. **Pod 重启**：counter 从 100 归零 → delta 计为当前值，`resetDetected`。
4. **50k 截断**：构造超过 limit 的样本 → `truncated=true`，前端显示警告。
5. **路由覆盖**：枚举所有 work controller，断言 guarded/allowlist；新增一个未分类路由 → 测试失败。
6. **准入状态机**：mock 信号序列（80→86→86→76→76→76）→ 进入/退出次数符合预期。
7. **footprint v2**：带 subagent + turn buffer + SSE 的会话 → `estimatedBytes` 等于组件和；peak 正确。
8. **Dashboard 语义**：快照测试图例/tooltip 文本与后端字段语义一致。

### 7.3 E2E / 校准

- dev 环境：用 20 个含图会话 + 2 个长 turn 压测；观察 `oldGenPercent`、`afterGC`、`container` 与真实 GC/OOM 的相关性。
- UAT 灰度：`admissionEnabled=false` 运行一周，记录 `wouldReject` 与真实驱逐/OOM；再开 `true`，观察误拒率。
- 验收矩阵：

| 场景 | 通过标准 |
|---|---|
| Young GC 前瞬时 90% | 不触发拒绝（old gen 低） |
| Old Gen 持续 88% | 连续 2 次后进入 SATURATED；降到 75% 以下 3 次后恢复 |
| Pod 重启 | delta 不丢、不误报 |
| 7 天查询 | 顺序正确；截断可见 |
| 新 work 路由 | 测试强制分类 |
| 长 turn | footprint 30s 内可见；peak 不丢 |
| 仪表盘 | 每个图例标注 max pod / fleet sum |

## 8. 备选方案与取舍

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 继续用瞬时 `heapUsed` | 零改动、最快 | Young GC 误报；P9 抖动 | 否 |
| 只用 `heapAfterGc` | 比瞬时稳 | G1 Concurrent/Young 污染；旧值可能过期 | 辅助 |
| 用 `oldGenPercent` 主信号 + after-GC 辅助 | 与留存压力最相关 | 需要 GC 事件过滤；G1 pool 名依赖 | 推荐 |
| 用 container RSS 主信号 | 覆盖堆外/容器 OOM | 包含代码缓存/线程栈，噪声大；对会话归因弱 | 兜底 |
| fleet 级 heap 准入 | 全局视角 | 跨 pod 查询延迟/耦合；把别人的压力转嫁 | 否 |
| 准入排队 | 平滑 | 长队列掩盖问题、超时 | 否；per-session queue 已够 |
| 只修 SQL/前端，不改信号 | 低风险 | P9 抖动仍在 | C0 过渡 |

## 9. 分阶段实施

### C0（简单修复，先发）

| 任务 | 说明 | 退出条件 |
|---|---|---|
| C0-1 `ORDER BY` + 索引 | `range()` 加 `ORDER BY collected_at, pod`；确认索引 | 乱序测试通过 |
| C0-2 跨桶 delta | baseline + reset 处理；去掉 `Math.max(0, ...)` | 桶间增量不丢 |
| C0-3 SSE 落库 | `RuntimeSample` 加 `sse_buffer_bytes/events/dropped` | 面板可见 |
| C0-4 PodCard 补字段 | after-GC、container、est/img、span depth | 前端显示 |
| C0-5 路由覆盖 | 补 `POST /api/sessions/{id}/messages`；路由测试 | 测试通过 |
| C0-6 截断可见 | `truncated` 字段 + 前端警告 | 7 天查询可见 |
| C0-7 `pods()` tie-break | 时间相同按 pod 排序；stale 标记 | 稳定 |

C0 不依赖信号模型，可以和 Design A 的 B0 并行。

### C1（信号 + 准入）

| 任务 | 说明 | 退出条件 |
|---|---|---|
| C1-1 GC 事件过滤 | 按 bean 区分；忽略 concurrent；reclaimed 阈值 | 单测覆盖 |
| C1-2 Old Gen 采集 | `G1 Old Gen` collection usage；写入 RuntimeSample | 面板可见 |
| C1-3 状态机 | enter/exit 连续样本 + 滞回 + 冷却；stale fail-open | 序列测试通过 |
| C1-4 路由注册表 | `WorkRoute` + allowlist + 自动覆盖测试 | 无未分类路由 |
| C1-5 429 语义 | scope + Retry-After + body | 集成测试 |
| C1-6 校准 | `admissionEnabled=false` 记录 wouldReject | UAT 一周数据 |

C1 的退出条件：误拒率可接受；Young GC 不再触发拒绝；路由无遗漏。

### C2（语义 + footprint）

| 任务 | 说明 | 退出条件 |
|---|---|---|
| C2-1 `_sum_peak` | 按分钟对齐后求和；保留 `_peak` 为 max pod | 面板双线 |
| C2-2 footprint v2 | 组件、版本、subagent/turn buffer/SSE | 组件和 = total |
| C2-3 高水位 | SessionSnapshot/RuntimeSample/峰值聚合 | 长 turn 可见 |
| C2-4 Dashboard 重做 | Retained/Allocation/Footprint/Work/Refusals | 图例语义测试 |
| C2-5 校准报告 | “一个 Pod 能扛多少会话” | 用 peak + 分布回答 |

## 10. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| Old Gen 指标在非 G1 / 不同 JDK 不可用 | 信号缺失 | 回退到 after-GC / container；标 `signalSource` |
| GC 事件过滤复杂 | 误判留存压力 | 单测覆盖 G1 Young/Mixed/Concurrent；保留原始 gcKind |
| 阈值不合适 | 误拒或漏拒 | 先校准；可配置；滞回；一键关闭 |
| 多副本时钟漂移 | 对齐错误 | 以 `collected_at` 为准；±5s 容差；carry forward |
| footprint 刷新成本 | 长 turn 卡顿 | dirty flag；30s 采样；异步/低优先级 |
| 新字段 schema 迁移 | 旧样本 null | 字段可空；版本指纹；面板 N/A |
| 前端语义再次漂移 | 误读 | 字段名 + tooltip + 快照测试 |
| 路由枚举不全 | 绕过准入 | 自动测试 + 生产 `unguarded_work_route` |
| 把 max 当 sum | 容量误判 | `_peak` / `_sum_peak` 命名分离；图例强制 |

## 11. 关键决策与未决事项

| ID | 决策 | 提案 | 备选 |
|---|---|---|---|
| D-C1 | 主准入信号 | `oldGenPercent` + after-GC 辅助 | after-GC 主 / container 主 |
| D-C2 | 阈值/滞回 | 85% 进入（2×5s）、75% 退出（3×5s）、60s 冷却 | 90/80、1/2 样本 |
| D-C3 | Gauge 语义 | `_peak` = max pod；`_sum_peak` = fleet sum | 只保留一种 |
| D-C4 | Footprint scope | messages/history/subagent/turnBuffer/SSE/refs/store | 只算 messages+history |
| D-C5 | 长 turn 刷新 | 30s 定时 + turn 结束 | 每次 tool result |
| D-C6 | 50k 截断 | `limit+1` + `truncated`；不自动降采样 | 服务端降采样 |
| D-C7 | 信号过期 | fail-open + 告警；container >95% 仍拒绝 | fail-closed |
| D-C8 | 拒绝状态码 | 429 + Retry-After | 503 |
| D-C9 | 高水位存储 | `RuntimeSample` 字段 | 单独 collection |
| D-C10 | `expected_pods` | 最近 10 分钟见过的最大 pod 数 | 部署配置注入 |

### 待确认问题

1. UAT/prod 的 JVM 是否统一为 G1？如果不是，Old Gen pool 名/行为要分别处理。
2. 是否允许在 `RuntimeSample` 增加 10+ 字段（表宽/写入成本）？还是拆 `runtime_memory_samples`？
3. `RuntimeStatsJob` 每分钟一次是否足够做准入校准？是否需要 10s 采样表？
4. 前端是否接受“max pod”和“fleet sum”双线？还是只保留一种并改标题？
5. footprint 是否要把 `ChatMessageService` / SSE buffer 的字节算进“会话”？如果算，`AgentFootprint` 的语义就从“Agent 内容”变成“会话总占用”，需要改名/版本。
6. 路由审计的“全集”从哪里来：core-ng 的 WebService 接口？还是手工清单？需要一次盘点。
7. 429 是否要区分 `heap` / `pod_turns` / `user_turns` 给前端不同文案？
8. 校准模式下 `wouldReject` 是否需要持久化到 `RuntimeSample`，还是只写 action log？

## 附录 A：需求与治理

- 用户要求：先拆问题，再对需要 tech design 的问题出设计；本文件只出设计，不改代码。
- 关联 Q6/Q7/Q8b；C0 的简单修复可以独立先做。
- 评审使用当前会话模型；不写成“用户批准”。

## 附录 B：源码事实

| 事实 | 位置 |
|---|---|
| JVM 快照字段 | `core-ai-server/src/main/java/ai/core/server/runtime/JvmMemoryMetrics.java:34-50`、`:127-140` |
| 最新 GC 混用所有 bean | `JvmMemoryMetrics.java:67-76` |
| heap pool 求和、无 Old Gen | `JvmMemoryMetrics.java:52-65` |
| 准入用瞬时 heap | `RuntimeCapacityGuard.java:43-48`、`:64-70`、`:121-126` |
| 默认 watermark 85% | `RuntimeLimits.java:13`、`:32` |
| 准入路由精确匹配 | `AdmissionInterceptor.java:20-31` |
| 429 语义 | `AdmissionInterceptor.java:52-53`；`RuntimeCapacityGuard.java:117-119` |
| RuntimeSample 字段 | `core-ai-server/src/main/java/ai/core/server/runtime/RuntimeSample.java:18-170` |
| SSE 已算未落库 | `RuntimeStatsService.java:84-90`；`RuntimeStatsJob.java:68-70` |
| Job 每分钟写样本 | `RuntimeStatsJob.java:32-100` |
| SQL 无 ORDER BY | `PgRuntimeSampleStore.java:170-174` |
| series 用 LinkedHashMap / 桶内 delta | `RuntimeSampleService.java:54-69`、`:120-185` |
| max 冒充 all replicas | `RuntimeSampleService.java:147-151`；`RuntimeWatermarks.tsx:132-143` |
| 50k 限制 | `RuntimeSampleService.java:28`、`:72-74` |
| PodView 已有字段 | `core-ai-api/src/main/java/ai/core/api/server/runtime/RuntimePodView.java:34-65` |
| SeriesView 已有 span depth | `RuntimeSeriesView.java:47-48` |
| footprint 只算 messages+history | `core-ai/src/main/java/ai/core/agent/AgentFootprintCalculator.java:20-38` |
| tracker 只 turn 结束刷新 | `core-ai/src/main/java/ai/core/session/SessionFootprintTracker.java:24-31`；`InProcessAgentSession.java:289` |
| 前端只画 heap/并发/拒绝 | `core-ai-frontend/src/pages/dashboard/components/RuntimeWatermarks.tsx:119-186` |

## 附录 C：Review 与修改历史

| 版本 | 日期 | 作者 | 修改 |
|---|---|---|---|
| v0.1 | 2026-11-24 | core-ai | 初稿：三个物理量、准入状态机、度量语义、footprint v2、C0/C1/C2 |
| v0.2 | 2026-11-24 | core-ai | 用户授权按提案默认值实施 C1/C2；记录实施与偏离（见 C.2） |

### C.2 实施记录与偏离

- 2026-11-24：用户授权按提案默认值实施。C0-1（ORDER BY）与 C0-2（跨桶 delta）由简单修复轨道先行合入，本次未重写；C0-3（SSE 落库）、C0-6（truncated）也已在 master，本次未回退。
- 采用 D-C1（`oldGenPercent` 主信号 + after-GC 辅助）、D-C2（85% 进入 2×5s、75% 退出 3×5s、60s 冷却）、D-C3（`_peak`=max pod、`_sum_peak`=fleet sum）、D-C7（stale fail-open + container >95 拒绝）、D-C8（沿用 429 + Retry-After）。

| # | 设计提案 | 实施 | 原因 |
|---|---|---|---|
| C-DEV-1 | `containerWatermarkPercent` 可配置 | `RuntimeLimits.containerWatermarkPercent = 90`，未加 `SystemSettings` 列 | 避免为一个保守常量扩充 settings 表；后续需要在线调整时再加列 |
| C-DEV-2 | footprint scope 含 subagent / turnBuffer / SSE | v2 覆盖 messages/history + media refs + 高水位；subagent/turnBuffer/SSE 暂记 0，未接入 | 这些字节由 server 侧 `ChatMessageService` / `SessionChannelService` 持有，core 的 `Agent` 不可达；需要新增跨服务 accessor，作为 C2 后续 |
| C-DEV-3 | 长 turn 每 30s 定时刷新 | `SessionRuntimeStats.snapshots()` 读取时调用 `refreshIfDue(30s)`（stats job 每分钟触发） | 不引入额外线程；长 turn 最迟约 1 分钟内可见（设计目标 30s，采样精度受 job 周期限制） |
| C-DEV-4 | stale 15s fail-open | 超过 15s 不进入 SATURATED，保留上一次稳定状态；容器 ≥95 仍返回 `container` | D-C7 的直接实现 |
| C-DEV-5 | `_sum_peak` 按分钟对齐并 carry forward | 在 series bucket 内按分钟累计，桶内未见样本的 pod 沿用上一分钟；桶外不回溯 | 跨桶 baseline 需要额外查询；桶内已消除“同一分钟多报”导致的重复计数 |
| C-DEV-6 | `expected_pods`（D-C10） | 未实现，仅返回 `pods_reporting` | 属于 C0/后续项，本次不扩展到部署配置 |
| C-DEV-7 | 路由注册表自动枚举 work controller | `WORK_ROUTES` 手工注册 + `AdmissionInterceptorTest` 覆盖已知 chat 入口 | core-ng 没有可枚举的 WebService 路由元数据；测试保证新增入口至少被显式分类 |
| C-DEV-8 | GC 事件过滤 `reclaimedBytes` 阈值 | 报告 `gcKind` 与 `reclaimedBytes`；准入用 `oldGenPercent` 为主，after-GC 仅在 `reclaimed>0 && kind!=concurrent` 时启用 | 阈值可按实际 GC 数据再校准，避免写死 1%/1MB |

- Dev E2E（2026-11-24，2.0.369）：`old_gen_percent=3.67`、`gc_kind=young`；series 13 个有序桶均含
  `old_gen_peak_percent` / `sessions_sum_peak` / `in_flight_turns_sum_peak`；pod 视图
  `sessions_estimated_bytes_peak_max=19,256`、`sessions_media_ref_bytes_total=79`。
  记录：`docs/validation/2026-11-24-dev-session-media-trace-runtime.md`。
