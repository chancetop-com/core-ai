# Core AI Trace 载荷边界 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | DRAFT v0.1 + 实现中；2026-11-24 用户授权开始实现，采用提案默认值（T1–T8） |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-24 |
| 作者 | core-ai |
| 关联问题 | Q4（Trace 载荷记录什么、何时记录）；接口依赖 Q1（`MediaRef` / `Content.IMAGE_REF`）、Q5（span ingest 字节预算） |
| 关联证据 | `docs/validation/2026-11-24-uat-runtime-metrics-and-heap-review.md` P2 / P3 |
| 目标 | 让 trace 记录“可诊断的引用与元数据”，而不是把原始图片字节复制进 span、protobuf 和入库管线 |
| 本次交付 | 仅技术设计；不修改业务实现、不建表、不迁移、不部署 |
| 事实与提案 | 附录 B 为源码事实；正文新增的 `TracePayloadSink`、`TracePayloadRef`、大小阈值均为提案 |

## 结论摘要

1. **Trace 是旁路观测，不是主数据。** 它必须能在“不改变被观测路径内存曲线”的前提下保留可诊断性；当前实现把完整 `request.messages`（含 data URI）序列化进 span，观测本身成了放大器。
2. **P2 的拷贝链是四段**：`LLMTracer.buildInputJson` 生成巨型 String → span attribute 持有 → `LocalSpanProcessor` 转 protobuf 再复制 → `TracePayloadStore` 在入库阶段正则重建。压缩发生在最后一段，前面的拷贝已经发生。
3. **正确边界：在 span attribute 产生之前就把图片变成引用。** 如果 `Message` 已经是 `IMAGE_REF`（Design A），`LLMTracer` 直接序列化 ref metadata；legacy data URI 通过 `TracePayloadSink` 在序列化时存入 payload store，span 里只留 `coreai-image://<sha256>`。
4. **`TracePayloadSink` 必须是 core 侧 SPI**：core 的 tracer 不能依赖 server 的 Mongo store；Server 实现复用 `TracePayloadStore`，CLI 实现可落本地/随 trace 上传，或返回 null 让 span 记录 placeholder。
5. **必须有硬上限**：单 span attribute（建议 256 KB）和单 trace payload（建议 8 MB）都要有上限；超过时截断并标记 `truncated`，而不是继续分配。
6. **payload store 保持 trace-scoped**：现有 `trace_payload_blobs` 以 `traceId:sha256` 为主键，trace 归档时删除；A1 的 ref 不需要新的全局媒体库，但需要和 `SessionMediaStore` 的 TTL 对齐，避免 trace 还在、图已经没了。
7. **CLI 跨端是独立问题**：CLI 的 ref 指向本地文件，Server 无法解析；要么在 trace 上传时附带受上限约束的 bytes，要么明确显示 placeholder。不能假装 ref 在 Server 可解析。
8. **B0 可以先做**：不做引用模型，只在 `LLMTracer` 序列化时把超限 data URI 替换为短占位并截断总输入；这会丢失 trace 图片细节，但能立刻消灭最大的 span 拷贝。完整方案 B1 依赖 Design A 的 ref。

## 阅读指南与目录

- 产品 / Reviewer：先读 §1、§2、§5.5、§8、§11，确认“trace 还要不要看图、看不了图能否接受”。
- 后端：读 §3–§7、§9–§10，重点关注序列化顺序、sink 注入、payload 生命周期。
- CLI：读 §3.6、§5.6，重点关注本地 ref 与上传策略。
- 测试：读 §7，尤其是“span 无 base64、单 attribute 有上限”的断言。

| 章节 | 内容 |
|---|---|
| [1. 背景与第一性原理](#_1-背景与第一性原理) | trace 的三个角色、四段拷贝 |
| [2. 目标、非目标与不变量](#_2-目标、非目标与不变量) | 旁路观测的硬约束 |
| [3. 现状与源码事实](#_3-现状与源码事实) | tracer、队列、入库、存储、查看、CLI |
| [4. 核心模型](#_4-核心模型) | `TracePayloadRef`、`TracePayloadSink`、大小预算 |
| [5. 详细设计](#_5-详细设计) | 序列化、sink、入库、存储、查看、跨端、失败 |
| [6. 可观测性](#_6-可观测性) | trace 自身的指标 |
| [7. 测试与验收](#_7-测试与验收) | 单测、集成、回归 |
| [8. 备选方案与取舍](#_8-备选方案与取舍) | 截断 / 引用 / 现状 |
| [9. 分阶段实施](#_9-分阶段实施) | B0 / B1 / B2 |
| [10. 风险](#_10-风险) | 可观测性退化、跨端、存储 |
| [11. 关键决策与未决事项](#_11-关键决策与未决事项) | 需要拍板的问题 |
| [附录 A：需求与治理](#附录-a-需求与治理) | 原文与约束 |
| [附录 B：源码事实](#附录-b-源码事实) | 文件与行号 |
| [附录 C：Review 与修改历史](#附录-c-review-与修改历史) | 版本变化 |

## 1. 背景与第一性原理

### 1.1 Trace 的三个角色

| 角色 | 需要什么 | 不需要什么 |
|---|---|---|
| 调试 | 这次请求发了哪些消息、哪些图、多大、来自哪里 | 原始图片字节的长期副本 |
| 计费 / 统计 | token、模型、时长、状态、工具调用 | 图片内容 |
| 回放 | 请求结构、工具参数、图片引用（能按需取到原图） | 把原图内联在 span 属性里 |

当前实现把“回放需要图”误解成“span 必须携带图”，导致观测路径复制主路径的最大对象。

### 1.2 四段拷贝

| 段 | 代码 | 发生什么 |
|---|---|---|
| 1. span 输入 | `LLMTracer.createLLMSpan` L124 + `buildInputJson` L241-259 | 把完整 `request.messages` 序列化成 JSON String |
| 2. span 持有 | OTel `Span.setAttribute` | 字符串成为 span attribute，随 span 存活到流式响应结束 |
| 3. ingest | `LocalSpanProcessor.onEnd` L181-191 + `convertToExportRequest` L222-236 | `ReadableSpan` → `ExportTraceServiceRequest`，attributes 再复制进 protobuf |
| 4. 入库 | `OTLPIngestService.ingest` L52-59、`saveSpan` L204-207；`IngestService.ingest` L80-87 | 在 DB 写入前用 `TracePayloadStore.Compactor` 正则替换 data URI |

`TracePayloadStore.Compactor.compact` 确实把 data URI 去重成 `coreai-image://sha256`，但它在第 4 段才发生；第 1–3 段的 10+ MB String 和 protobuf 已经分配并持有。P2 的“13×–30× 放大”主要来自这里。

### 1.3 为什么不能只优化正则

`Compactor` 需要完整 `payload` 字符串才能 `matcher.find()`，`StringBuilder` 重新拼装还会再分配一份接近原大小的缓冲。把正则写得更快，不会消除第 1 段的序列化和第 2/3 段的复制；必须把“图片 → 引用”前移到序列化之前。

## 2. 目标、非目标与不变量

### 2.1 目标

1. span attribute 中的图片以引用 + 元数据表示，不出现大段 base64。
2. 单 span / 单 trace 有明确的大小上限；超限可见、可降级。
3. payload store 的写入、去重、保留、删除、回放有明确所有者。
4. 旧 trace（已有 `coreai-image://` 或内联 data URI）仍然可读。
5. CLI / Server 两端对“ref 能否解析”有明确语义，不静默丢图。

### 2.2 非目标

- 不做通用 tracing 平台替换（OTel/Langfuse 语义不变）。
- 不改 trace 的采样策略、索引结构、计费口径。
- 不解决 P3 的 span ingest 字节预算（Q5）；本设计只保证单条 span 不再巨型。
- 不承诺 trace 永久保留图片；保留窗口与 trace 归档一致。

### 2.3 不变量

1. **旁路不放大**：观测路径对单次请求的额外驻留不超过 `MAX_SPAN_ATTRIBUTE_BYTES`（建议 256 KB）；不得把完整 data URI 保留到 span 生命周期结束。
2. **先引用后序列化**：图片在进入 span attribute 之前必须已变成 ref 或短占位；不允许“先生成巨型 JSON，再正则替换”。
3. **硬上限**：单 span 输入、单 trace payload、单 payload 对象都有上限；超限截断并标记，不得无限分配。
4. **可回放**：被引用的 payload 在 trace 保留窗口内可按需取回；归档时先 hydrate 再写归档，归档后删除 store。
5. **失败降级**：sink 不可用 / 引用缺失时，span 记录 placeholder + 元数据；trace 查看不失败。
6. **作用域隔离**：payload 以 trace 为作用域，不跨 trace 复用；删除 trace 时一并删除 payload。
7. **跨端显式**：CLI 本地 ref 在 Server 不可解析时，必须标记 `unresolvable`，不能返回空图或错误。
8. **不改主路径语义**：sink 失败不得让 LLM 调用失败；sink 延迟必须计入观测预算，不能阻塞流式响应。

## 3. 现状与源码事实

### 3.1 Span 创建

- `LLMTracer.createLLMSpan` 设置 `langfuse.observation.input = buildInputJson(request)`（`core-ai/src/main/java/ai/core/telemetry/LLMTracer.java:114-126`）。
- `buildInputJson` 把 `request.messages` 和 `request.tools` 放进 `LinkedHashMap` 后 `JsonUtil.toJson`（`LLMTracer.java:241-259`）。
- 没有大小检查；失败时回退到 `serializeMessagesToJson`，同样没有上限。

### 3.2 队列与转换

- `LocalSpanProcessor` 的 ingest 队列按**条数** 10,000 控制，trace 创建队列 2,000（`LocalSpanProcessor.java:44-50`）。
- `onEnd` 在 worker 线程里 `convertToExportRequest(span)`，把 attributes 逐个转成 protobuf `KeyValue`（`LocalSpanProcessor.java:181-191`、`:222-251`）。
- 队列满时丢 span 并计数（`LocalSpanProcessor.java:69-90`）；这是 P3 的容量问题，不是本设计的重点，但说明 span 大小会直接影响队列的内存。

### 3.3 入库与压缩

- OTLP 路径：`OTLPIngestService.ingest` 创建 `Compactor`，`saveSpan` 里 `span.input/output = compactor.compact(...)`（`OTLPIngestService.java:52-59`、`:204-207`、`:239`、`:285`、`:314-315`）。
- CLI HTTP ingest 路径：`IngestService.ingest` 对每个 `spanReq` 的 `input/output` 做 `compactor.compact`（`IngestService.java:80-87`）。
- `TracePayloadStore.Compactor` 用正则匹配 `data:image/...;base64,...`，按 `traceId:sha256` 去重，替换为 `coreai-image://sha256`；超过 `MAX_DATA_URL_LENGTH`（12 MB）的 data URL 跳过（`TracePayloadStore.java:30-36`、`:197-227`）。

### 3.4 存储与生命周期

- `TracePayloadBlob` 是 Mongo collection `trace_payload_blobs`，主键 `traceId:sha256(dataUrl)`，保存原 data URL 字符串（`TracePayloadBlob.java:15-33`）。
- `TracePayloadRepository` 按 trace 查询/删除（`TracePayloadRepository.java:13-24`）。
- trace 归档时先 `payloadStore.hydrate(trace, true)` / `hydrate(page, true)`，把引用还原成原图再写归档；归档后 `deleteForTraces` 删除 payload（`TraceArchiveService.java:142-155`、`:161-190`）。
- 查看详情时 `TraceService.getWithPayload` / `span()` 调 `payloadStore.hydrate(..., false)`，把图内联回 JSON（`TraceService.java:241-258`）。

### 3.5 当前缺陷

| 缺陷 | 影响 |
|---|---|
| 序列化在压缩之前 | 10+ MB String 先产生，P2 拷贝无法避免 |
| 单 attribute 无上限 | 一次请求可能把 12 MB 图片写进 span |
| 大图跳过去重 | `>12 MB` 的 data URL 直接内联落库 |
| 查看详情 hydrate 全图 | 详情响应本身可能变成 12 MB，前端/网络再次放大 |
| CLI 上传原样 JSON | CLI 的 data URI 先进入 HTTP body，再由 Server 压缩；上传侧也有一份大拷贝 |

## 4. 核心模型

### 4.1 `TracePayloadRef`

提案字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `sha256` | String | 内容哈希；与现有 `coreai-image://<sha256>` 兼容 |
| `mediaType` | String | e.g. `image/png` |
| `sizeBytes` | Long | 解码后字节数 |
| `traceId` | String | 作用域；服务端解析时校验 |
| `source` | String | `message` / `tool` / `attachment`，用于 UI |
| `displayName` | String | 可选，便于 trace 里识别 |
| `resolvable` | Boolean | CLI 本地 ref 上传到 Server 时为 false |

序列化到 span 时只写 `{type:"image_ref", sha256, mediaType, sizeBytes, source, displayName, resolvable}`，不含字节。

### 4.2 `TracePayloadSink`（core SPI）

```java
public interface TracePayloadSink {
    /** @return a stable ref, or null when the payload cannot be stored (caller writes a placeholder). */
    String store(String traceId, String dataUrl, String source);

    boolean available();
}
```

- core 的 `LLMTracer` 通过 `TracerRegistry` / `TelemetryConfig` 注入一个可选 sink；未配置时走 placeholder/截断。
- Server 实现：包装 `TracePayloadStore`，暴露 `store(traceId, dataUrl, source)`，内部复用 `sha256`、`TracePayloadBlob`、`TracePayloadRepository`。
- CLI 实现：
  - 方案 A（推荐首版）：本地记录 ref + bytes 到 trace 上传包；Server 在 ingest 时写入 payload store。上传包有大小上限，超过则只传 metadata。
  - 方案 B：CLI 本地 trace viewer 自己解析；Server 只显示 `unresolvable`。
- sink 必须是非阻塞/有界：`store` 失败只返回 null，不抛异常；内部有超时和并发上限。

### 4.3 大小预算

| 预算 | 默认值（提案） | 作用 |
|---|---|---|
| `MAX_SPAN_ATTRIBUTE_BYTES` | 256 KB | 单 span 的 `langfuse.observation.input` / `output` 上限；超过截断 |
| `MAX_TRACE_PAYLOAD_BYTES` | 8 MB | 单 trace 的 input+output 总量上限；超过降级 |
| `MAX_INLINE_TRACE_IMAGE_BYTES` | 32 KB | 小于此值的图可以内联，避免为小图引入 ref 开销 |
| `MAX_PAYLOAD_STORE_BYTES` | 12 MB / 对象 | 单 payload 对象上限；超过不存，写 placeholder |
| `TRACE_PAYLOAD_TTL` | 跟随 trace retention | store 生命周期 |

## 5. 详细设计

### 5.1 序列化顺序（关键）

`LLMTracer.buildInputJson` 按以下顺序处理每条 message 的每个 part：

```
if part.type == IMAGE_REF:
    emit {type:"image_ref", ...}                     // A1 路径，零字节
else if part.type == IMAGE_URL and url startsWith("data:"):
    if decodedSize <= MAX_INLINE_TRACE_IMAGE_BYTES:
        emit as-is                                  // 小图内联，保持兼容
    else:
        ref = sink.store(traceId, url, source)      // 在序列化时压缩
        emit ref != null ? {type:"image_ref", ...} : {type:"text", text:"[image omitted: N bytes]"}
else:
    emit as-is
```

然后：

1. 序列化整个 input。
2. 如果 `length > MAX_SPAN_ATTRIBUTE_BYTES`，对消息文本/工具结果做**结构化截断**（保留头尾 + `…[truncated N bytes]`），而不是直接 `substring` 破坏 JSON。
3. 标记 `coreai.trace.input_truncated=true`、`coreai.trace.input_bytes`。
4. 任何异常回退到“只序列化角色 + 文本摘要”，绝不回退到完整 base64。

### 5.2 sink 的实现与注入

- `TelemetryConfig` 增加可选 `TracePayloadSink`；`LLMTracer` 构造时注入。
- Server `TraceModule` 绑定 `TracePayloadStore` 的 sink adapter；必须避免循环依赖（sink 只依赖 repository，不依赖 ingest service）。
- CLI 在 `CliAgent` 初始化时注入 `CliTracePayloadSink`；它把 ref + bytes 写到 trace 上传包，或在未登录时返回 null。
- 测试用 `RecordingTracePayloadSink` 断言 store 调用次数和 ref 稳定性。

### 5.3 ingest 侧

- 新 span（A1）已经不含大 data URI；`Compactor` 仍保留给旧 client / 旧 span，作为兼容路径。
- `Compactor` 增加 `MAX_DATA_URL_LENGTH` 的可配置值和“超限写 placeholder”的行为，避免大图直接内联落库。
- `LocalSpanProcessor` 的队列容量问题由 Q5 处理；本设计只保证单条 span 不超过 `MAX_SPAN_ATTRIBUTE_BYTES`，从而让 Q5 的字节预算可估算。

### 5.4 存储与生命周期

- 继续使用 `trace_payload_blobs`（trace-scoped，主键 `traceId:sha256`），避免新建全局媒体库。
- `store` 前检查 `MAX_PAYLOAD_STORE_BYTES`；超限返回 null，span 记录 placeholder + 原始大小。
- 归档时 `hydrate(..., true)` 必须成功；失败则归档失败并告警，不能静默写空图。
- `deleteForTraces` 与 trace 删除保持同一事务边界（或至少同一批次），避免孤儿 payload。
- TTL：trace 保留窗口即 payload 保留窗口；不额外延长。
- 去重：同一 trace 内相同 sha 只存一份；跨 trace 不共享，避免删除耦合。

### 5.5 查看与回放

当前 `TraceService.getWithPayload` 把图片 hydrate 回 JSON，详情响应可能很大。建议：

1. 保留现有 `getWithPayload` 作为兼容接口，但加 `MAX_HYDRATE_BYTES`（例如 2 MB）：超过则保留 `coreai-image://`，前端按需请求。
2. 新增受权端点 `GET /api/traces/{traceId}/payloads/{sha256}`，返回原始 bytes + content-type；前端把 ref 渲染成图片 URL。
3. 列表/时间线只用 `spanStore.summariesByTrace`（已存在），不 hydrate。
4. 归档回放使用归档文件里的原图，不依赖已删除的 store。

### 5.6 CLI 跨端

- CLI 的 `HttpTraceUploader` 目前把 span `input/output` 原样 JSON 上传（`HttpTraceUploader.java:71-91`、`:155-163`）。
- A1 后 CLI span 里是 ref；Server 无法解析本地文件。三种策略：
  - **U1（推荐首版）**：CLI 在上传包里附带 `payloads: [{sha, mediaType, dataUrl}]`，总大小 ≤ `MAX_TRACE_PAYLOAD_BYTES`；Server ingest 先写 payload store，再写 span。
  - **U2**：CLI 只传 metadata，Server trace 显示 `unresolvable`；CLI 本地 trace 可看。
  - **U3**：CLI 把图上传到 object storage，ref 用 URL；成本高，首版不做。
- 选择 U1 时，上传包也要有大小上限；超过则降级为 U2，并在 span 标记 `unresolvable`。

### 5.7 失败矩阵

| 失败 | 行为 | 指标 |
|---|---|---|
| sink 不可用 | span 写 `[image omitted: N bytes]` + size | `trace_payload_sink_unavailable` |
| sink store 失败 | 同上；不阻塞 LLM 调用 | `trace_payload_store_failure` |
| payload > MAX | 不存，写 placeholder | `trace_payload_oversize` |
| 查看时 ref 缺失 | 保留 `coreai-image://` + `resolvable=false`；不抛错 | `trace_payload_miss` |
| 归档 hydrate 失败 | 归档失败 + 告警；不写空图 | `trace_archive_hydrate_failure` |
| CLI 上传超限 | 降级 U2，标记 unresolvable | `trace_payload_upload_oversize` |
| 输入截断 | 保留头尾 + 标记 | `trace_input_truncated` |

## 6. 可观测性

| 指标 | 类型 | 用途 |
|---|---|---|
| `trace_input_bytes` | histogram | 观察 span 输入大小分布；验证上限 |
| `trace_input_truncated_total` | counter | 截断频率 |
| `trace_payload_store_bytes` | counter | 写入 payload 的字节 |
| `trace_payload_store_failure_total` | counter | sink/存储失败 |
| `trace_payload_oversize_total` | counter | 超大对象 |
| `trace_payload_refs` | gauge | 活跃引用数 |
| `trace_payload_miss_total` | counter | 查看/归档缺失 |
| `trace_payload_upload_oversize_total` | counter | CLI 上传降级 |
| `trace_archive_hydrate_failure_total` | counter | 归档失败 |

Span 自身增加 `coreai.trace.payload_mode`（`ref` / `inline` / `truncated` / `omitted`）和 `coreai.trace.input_bytes`，便于在 trace 列表里直接过滤。

## 7. 测试与验收

### 7.1 单元测试

1. `buildInputJson` 遇到 1 MB data URI → sink 被调用一次，输出中无 base64，长度 < 256 KB。
2. sink 返回 null → 输出 placeholder + size，长度 < 256 KB。
3. 小图（< 32 KB）保持内联，不调用 sink。
4. 超长文本 → 结构化截断，JSON 仍可解析。
5. `Compactor` 对旧 data URI 仍工作；超 12 MB 不再内联。
6. `TracePayloadStore` 去重、删除、hydrate strict/non-strict。

### 7.2 集成测试

1. OTLP 路径：mock span processor + payload sink → 入库 span 无 base64。
2. CLI ingest 路径：上传带 data URI 的 span → 入库前压缩；上传带 refs 的 span → 解析 refs。
3. trace 详情：`getWithPayload` 不超过 `MAX_HYDRATE_BYTES`；media endpoint 返回正确 content-type。
4. 归档：hydrate strict 成功 → 归档文件含原图 → store 删除；hydrate 失败 → 归档失败。
5. CLI 上传：U1 附带 payloads → Server 可查看；超限 → U2 + unresolvable。

### 7.3 验收矩阵

| 场景 | 通过标准 |
|---|---|
| 12 MB 图片的 LLM 调用 | span input < 256 KB；HB 无 12 MB 级 allocation（相对现状） |
| 旧 trace 查看 | `coreai-image://` 仍能 hydrate |
| 新 trace 查看 | 图片按需加载；详情响应 < 2 MB |
| trace 归档 | 归档含原图；store 删除 |
| CLI trace | 图片可见或明确 unresolvable |
| sink 故障 | LLM 调用不受影响；span 有 placeholder |

### 7.4 回归

- `TracePayloadStoreTest`、`GatewayImageControllerTest`、`AnthropicMessagesProtocolTest` 等现有测试保持通过。
- 新增“span 输入中 `data:image` 出现次数 = 0（>32 KB）”的断言，防止回归。

## 8. 备选方案与取舍

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 现状（入库时压缩） | 零改动 | 巨型 String 已产生；P2 不解决 | 否 |
| B0 截断（不引用） | 最快、最小改动 | trace 看不到图；诊断退化 | 过渡 |
| B1 引用化（本设计） | 根治 span 拷贝；图可按需回放 | 需要 sink + ref 模型 + 跨端策略 | 推荐 |
| 只依赖 A1（Message ref） | 无 trace 改动 | legacy data URI、CLI、旧 client 仍会泄漏 | 不完整 |
| 全局媒体库共享 | 去重率高 | 删除耦合、权限复杂 | 否 |

## 9. 分阶段实施

### B0（先行，可与 A1 无关）

1. `LLMTracer` 增加 `MAX_SPAN_ATTRIBUTE_BYTES` 和结构化截断。
2. 超过 `MAX_INLINE_TRACE_IMAGE_BYTES` 的 data URI 直接替换为 `[image omitted: N bytes]`（不存）。
3. 指标 + 回归测试；flag `trace.payload.size_guard.enabled`。
4. 退出条件：任何请求的 span input ≤ 256 KB；LLM 调用不受影响。

### B1（依赖 Design A 的 `IMAGE_REF`）

1. `TracePayloadSink` SPI + Server adapter + CLI adapter。
2. `buildInputJson` 按 §5.1 顺序序列化；ref 路径零 base64。
3. `TracePayloadStore` 超限行为、查看上限、media endpoint。
4. 退出条件：新 span 无 base64；ref 可在保留窗口内回放；归档 strict hydrate 通过。

### B2（后续）

1. CLI 上传包携带 payloads（U1）或明确 U2。
2. 详情页按需加载图片；列表不 hydrate。
3. payload TTL 与 trace retention 对齐；孤儿对账。

## 10. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| trace 图片细节丢失 | 调试体验下降 | B0 只截断超限；B1 保留 ref + 按需查看 |
| sink 延迟 | 阻塞 LLM 调用 | sink 异步/有界；失败返回 null；不进入主路径 |
| trace 存储增长 | Mongo 成本 | 单 trace/单对象上限；归档删除；TTL |
| CLI 跨端不可解析 | 用户看到破图 | U1 上传或 U2 明确标记 |
| 旧 client 仍发大图 | 入库仍有大对象 | Compactor 超限 placeholder；Q5 字节预算 |
| 详情 hydrate 过大 | 前端/网络放大 | `MAX_HYDRATE_BYTES` + media endpoint |

## 11. 关键决策与未决事项

| ID | 决策 | 提案 | 备选 |
|---|---|---|---|
| T1 | trace 是否必须显示原图 | 是，按需解析 | 只显示 metadata（B0 即可） |
| T2 | 单 span attribute 上限 | 256 KB | 128 KB / 1 MB |
| T3 | 单 trace payload 上限 | 8 MB | 4 MB / 16 MB |
| T4 | 小图内联阈值 | 32 KB | 0（全部 ref） / 64 KB |
| T5 | CLI 跨端策略 | U1 上传带 payloads | U2 只标记 unresolvable |
| T6 | payload store 是否跨 trace 去重 | 否（trace-scoped） | 是（全局库，删除复杂） |
| T7 | 详情接口是否继续 hydrate | 保留但限 2 MB；新增 media endpoint | 只返回 ref |
| T8 | B0 是否允许丢失 trace 图片 | 是，过渡期 | 不做 B0，等 B1 |

## 附录 A：需求与治理

- 用户要求：先拆问题，再对需要 tech design 的问题出设计；本文件只出设计，不改代码。
- 关联 Q4；接口依赖 Q1/Q5。
- 评审使用当前会话模型；不写成“用户批准”。

## 附录 B：源码事实

| 事实 | 位置 |
|---|---|
| tracer 序列化完整 messages 进 span | `core-ai/src/main/java/ai/core/telemetry/LLMTracer.java:114-126`、`:241-259` |
| ingest 队列按条数 10,000 | `core-ai-server/src/main/java/ai/core/server/trace/spi/LocalSpanProcessor.java:44-50`、`:177-191` |
| span → protobuf 属性复制 | `LocalSpanProcessor.java:222-251` |
| OTLP ingest 在入库前压缩 | `core-ai-server/src/main/java/ai/core/server/trace/service/OTLPIngestService.java:52-59`、`:204-207`、`:314-315` |
| CLI ingest 在入库前压缩 | `core-ai-server/src/main/java/ai/core/server/trace/service/IngestService.java:80-87` |
| Compactor 正则/上限/去重 | `core-ai-server/src/main/java/ai/core/server/trace/service/TracePayloadStore.java:30-36`、`:197-227` |
| payload 存储模型 | `core-ai-server/src/main/java/ai/core/server/trace/domain/TracePayloadBlob.java:15-33` |
| 归档 strict hydrate + 删除 | `core-ai-server/src/main/java/ai/core/server/trace/maintenance/TraceArchiveService.java:142-155`、`:161-190` |
| 详情 hydrate | `core-ai-server/src/main/java/ai/core/server/trace/service/TraceService.java:241-258` |
| CLI 上传原样 JSON | `core-ai-cli/src/main/java/ai/core/cli/trace/HttpTraceUploader.java:71-91`、`:150-170` |

## 附录 C：Review 与修改历史

| 版本 | 日期 | 作者 | 修改 |
|---|---|---|---|
| v0.1 | 2026-11-24 | core-ai | 初稿：四段拷贝、sink、大小预算、B0/B1/B2 |
| v0.2 | 2026-11-24 | core-ai | 用户授权按提案默认值实施 B1；记录实施与偏离（见 C.2） |

### C.2 实施记录与偏离

- 2026-11-24：用户授权按提案默认值实施，T1（按需显示原图）、T2（256 KB/span）、T3（8 MB/trace）、T4（32 KB 内联）、T5（CLI 跨端 U1）、T6（不跨 trace 去重）、T7（详情保留 hydrate 但限 2 MB + media endpoint）、T8（允许 B0 过渡期丢图）。
- Trace B0（LLMTracer 256 KB 上限 + 超限占位）由简单修复轨道先行合入；B1 在其之上接 ref。

| # | 设计提案 | 实施 | 原因 |
|---|---|---|---|
| B-DEV-1 | `TracePayloadSink` core SPI | 未新增；A 的 ref 本来就不含字节，legacy data URI 由 Trace B0 的 256 KB 上限/占位处理 | 当前没有消费者；避免与正在改 `LLMTracer` 的 B0 会话重复实现。等出现“需要在上传时转存 legacy data URI”的真实调用再加 SPI |
| B-DEV-2 | `Compactor` 超 12 MB 跳过 | 超限或超出 8 MB/trace 预算时替换为 `[image omitted from trace: N bytes]`；store 失败同样降级 | 旧行为会把超限图片原样内联落库，违背“span 不持有大 base64” |
| B-DEV-3 | 单 trace 8 MB 上限 | 在 `Compactor` 内按 trace 累计（一个 ingest 批次内）；跨批次不累计 | Compactor 的生命周期是一次 ingest；跨批次精确累计需要额外计数表，收益低，先按批次约束 |
| B-DEV-4 | 详情 hydrate 上限 2 MB | `TracePayloadStore.hydrate(..., false)` 按替换后字符预算截断，超过的部分保留 `coreai-image://` | 不需要额外查询 blob 大小即可限制响应体 |
| B-DEV-5 | media endpoint `GET /api/traces/{traceId}/payloads/{sha256}` | 已实现，`TRACE_VIEW` + `TraceAccessControl`；返回原始 bytes + content-type | 与现有 trace 权限模型一致 |
| B-DEV-6 | CLI U1 上传 payloads | `HttpTraceUploader` 扫描 span input 中 `kind=file/sandbox` 的 ref，只读取 session media root 下的文件，总量 8 MB；随请求 `payloads` 上传 | 安全边界：伪造快照不能借 trace 上传读任意文件；blob/url ref 由服务端自行解析 |
| B-DEV-7 | 前端按需加载图片 | 本阶段只提供端点与结构化 ref；前端仍显示 ref 元数据，图片渲染在 B2 接 | 用户要求前端只做必要展示，避免顺手改无关页面 |
