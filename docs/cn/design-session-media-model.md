# Core AI 会话媒体模型与生命周期 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | DRAFT v0.1 + 实现中；2026-11-24 用户授权开始实现，采用提案默认值（D1–D10） |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-24 |
| 作者 | core-ai |
| 关联问题 | Q1（会话状态表示）、Q2（媒体生命周期）；接口影响 Q3（请求组装）、Q4（Trace 载荷） |
| 关联证据 | `docs/validation/2026-11-24-uat-runtime-metrics-and-heap-review.md` P1 / P2 / P8 |
| 目标 | 让会话状态的媒体表示与字节生命周期解耦：状态只持有引用，字节由 media store 拥有，请求边界按需水合 |
| 本次交付 | 仅技术设计；不修改业务实现、不建表、不迁移、不部署 |
| 事实与提案 | 附录 B 为设计时源码事实；正文新增的 `ContentType`、`MediaRef`、`SessionMediaStore`、预算默认值均为提案，不代表当前已实现 |

## 结论摘要

1. **根因不是“图片太大”，而是 `Message` 同时承担三个角色**：会话事件/展示、模型上下文、上游 wire payload。三者共用一个 `content` 字节串，导致 retention、duplication、copies 三个乘数叠加。
2. **会话状态的权威副本应是 text + media ref**；原始字节归 `SessionMediaStore` 所有。`Message`、`NodeMessages.history`、`AgentPersistence` 都不应持有超过阈值的内联字节。
3. **`NodeMessages.history` 是展示投影，不是事件日志，不应拥有媒体。** `core-ai-server` 的生产代码没有任何 `getHistory()` 依赖，重建走 `chat_messages` 纯文本；CLI 的所有 history 消费者也只读 `getTextContent()`。history 文本快照可以作为先行修复独立合入。
4. **引入 `Content.IMAGE_REF` + `MediaRef`**：ref 只含 id / kind / locator / mediaType / size / sha256 / displayName，不含字节、不含 secret。`kind` 覆盖 BLOB（Server）、FILE（CLI/Desktop）、SANDBOX、URL。
5. **`SessionMediaStore` 是唯一的字节所有者**：CLI 落到 session 目录，Server 复用 object storage + `SessionAttachmentRef`（或新 collection）；会话关闭 / TTL / 配额驱逐负责清理。
6. **水合边界在 provider wire encoder，不在 `Message` / pruner**：pruner 只产出“本次要水合哪些 ref”的计划；encoder 把计划变成 data URI 或签名 URL；trace 看到的是 ref，不是 base64。
7. **Retention 从“改消息”变成“管 store + 选请求”**：ref 本身很小，可以长期保留；会话内存不再需要“把旧图删掉”的原地降级。B0 的原地 prune 只作为 A1 之前的过渡和 legacy inline data URI 的兜底。
8. **B0（history 文本快照 + 会话级原地 prune + 硬上限）可以先合入**，解决 UAT 的无界驻留；A1（ref + store + 水合）才是密度和 P2 的终态。
9. **兼容性**：`SnapshotVersion` 需要升级；旧 session 文件在加载时清洗/迁移；服务端 ref 不跨 pod 持久化（重建仍走文本），因此不存在混部解析问题；CLI 旧版本读新快照会明确报 `UnsupportedSnapshotVersionException`，不做静默降级。
10. **首版只覆盖 IMAGE**；`MediaRef` 模型保留泛化到 PDF / VIDEO 的位置，但不在本期接线。

## 阅读指南与目录

- 产品 / Reviewer：先读 §1–§2、§5.4–§5.6、§8、§11，确认“存什么、存多久、失败时用户看到什么”。
- 后端 / 执行平台：读 §3–§7、§9–§11，重点关注 store 抽象、水合顺序、413/400 恢复和迁移。
- CLI / Desktop：读 §3.2、§5.2–§5.3、§5.6、§8，重点关注本地文件 store、旧快照兼容和 trace 上传。
- 测试：读 §9、§11，验收矩阵和不变量断言是硬约束。

| 章节 | 内容 |
|---|---|
| [1. 背景、问题与第一性原理](#1-背景问题与第一性原理) | 三个角色、四个乘数、UAT 证据 |
| [2. 目标、非目标与不变量](#2-目标非目标与不变量) | 本设计的硬约束 |
| [3. 现状与约束](#3-现状与约束源码事实) | 入口、存储、请求、trace、框架约束 |
| [4. 核心模型](#4-核心模型) | MediaRef、Content.IMAGE_REF、SessionMediaStore、生命周期 |
| [5. 详细流程](#5-详细流程) | 产生、请求、水合、retention、失败矩阵、迁移 |
| [6. 可观测性](#6-可观测性) | 指标、trace、footprint |
| [7. 测试与验收](#7-测试与验收) | 单测、集成、E2E、不变量断言 |
| [8. 备选方案与取舍](#8-备选方案与取舍) | A / B / 分阶段、被否方案 |
| [9. 分阶段实施](#9-分阶段实施) | B0 / A1 / A2 的退出条件 |
| [10. 风险](#10-风险) | 延迟、可用性、兼容、隐私 |
| [11. 关键决策与未决事项](#11-关键决策与未决事项) | 需要用户拍板的问题 |
| [附录 A：用户原始需求与文档治理](#附录-a用户原始需求与文档治理) | 原文摘录与需求追踪 |
| [附录 B：源码事实](#附录-b源码事实) | 设计时核对的文件与行号 |
| [附录 C：Review 与修改历史](#附录-c-review-与修改历史) | 版本变化 |

## 1. 背景、问题与第一性原理

### 1.1 UAT 事实摘要

以下事实来自 `docs/validation/2026-11-24-uat-runtime-metrics-and-heap-review.md`，本设计不重复推导，只引用结论：

- 含 inline 图片的会话占活跃样本约 45.8%；单会话 `AgentFootprint` 中位 5.75 MB、P90 25.78 MB、历史极值 64.72 MB。
- 11-10 至今无 span 积压的样本中，`heap_after_gc (MB) ≈ 284 + 12.9 × img_mb`；单会话驱逐实测净释放约 685 MB（约 15.4×）。
- 全历史仅触发 12 次压缩（8 个会话），真正把堆顶到 50%–99% 的重图会话几乎从未触发压缩；`MessageTokenCounterUtil` 每张图固定计 1000 tokens，压缩感知不到图片字节。
- 11-06 `stktn` 个案：长 turn 运行 28 分钟，`heap_after_gc` 从 183 MB 涨到 2,833 MB，期间 `est_mb` 一直停在 0–5.81 MB，turn 结束才跳到 66.11 MB（img = 64.79 MB）。这说明“驻留”和“每次调用的拷贝”是两条独立的放大链。

### 1.2 第一性原理：`Message` 现在同时扮演三个角色

| 角色 | 生命周期 | 应该持有 | 当前实现 |
|---|---|---|---|
| 会话事件 / 展示 | 会话级，可能跨重启 | 文本 + 资产引用 | `NodeMessages.history` 与 `messages` 共享同一个 `Message` 对象，图片字节被长期 pin 住 |
| 模型上下文 | 请求到会话结束 | 选中的消息 + 引用 | `messages` 持有全部历史图片的 base64 |
| 上游 wire payload | 单次 HTTP | 本次实际要发的字节 | `InlineImagePruner` 复制一份，provider 再序列化，trace 再复制 |

三个角色压在一个 `Message.content` 上，就没有任何一层对“图片该活多久”负责：

- `InlineImagePruner` 只产生新的临时列表赋给 `request.messages`，从不回写 `agent.getMessages()`（`core-ai/src/main/java/ai/core/llm/InlineImagePruner.java:205-224`、`LLMProvider.java:207`）。
- `NodeMessages.history` 是 append-only，把带 `data:image/...` 的 `USER` / `ASSISTANT` `Message` 原样引用（`NodeMessages.java:68-73`）；`CompressionLifecycle.beforeModel()` 只替换 `messages`，不碰 `history`（`CompressionLifecycle.java:56-61`）。
- `MessageTokenCounterUtil` 每张 `IMAGE_URL` 固定计 1000 tokens，与字节大小脱钩（`MessageTokenCounterUtil.java:19,67`）。

### 1.3 四个乘数，必须分开治

| 乘数 | 机制 | 本设计是否解决 |
|---|---|---|
| M1 Retention | `messages` 无界增长；`history` 共享引用在压缩后继续 pin | 是（Q1/Q2） |
| M2 Duplication | CLI `AgentPersistence` 同时序列化 `messages` + `history`，同一图片写两遍；Server 在发消息时就把上传图下载并 base64 | 是（Q1/Q2） |
| M3 Per-request copies | pruner 复制、provider `JsonUtil.toMap` 再序列化、HTTP body、compression 重排 | 部分（请求边界水合；HTTP body 的最后一份拷贝不可避免） |
| M4 Trace copies | `LLMTracer.buildInputJson` → span attribute → `LocalSpanProcessor` → protobuf → `TracePayloadStore` 正则重建 | 接口预留（Q4 定稿后接线；本设计保证 trace 看到 ref） |

**结论：P1 的“驻留”与 P2 的“拷贝”不能用一个“引用化”笼统概括。** 本设计先消灭 M1/M2，并为 M3/M4 提供 ref 边界；M3 的 HTTP body 和 M4 的 span 管线仍需要各自的设计/修复。

### 1.4 需求追踪

| ID | 需求 | 设计落点 |
|---|---|---|
| U1 | 会话不应因图片无界增长，长 turn / idle 会话都不能把堆顶穿 | §2.3 I4、§5.4、§9 |
| U2 | 模型在需要时仍能看到当前图片，旧图降级必须可解释、可重取 | §2.3 I5、§5.2、§5.4 |
| U3 | CLI / Desktop / Server 行为一致，不能只在 Server 生效 | §4.4、§5.1、§5.6 |
| U4 | 旧会话、旧 provider、旧快照不被破坏 | §2.3 I8、§5.6 |
| U5 | trace 可诊断，但不能成为内存放大器 | §2.3 I9、§6、Q4 |
| U6 | 可观测、可回滚、可灰度 | §2.3 I9/I10、§6、§9 |
| G1 | 本期只出设计，不改代码 | 全文；附录 A 记录治理规则 |

## 2. 目标、非目标与不变量

### 2.1 目标

1. 会话状态（`messages` / `history` / CLI 快照）中的媒体只保留引用，不保留原始字节。
2. 每张图片恰好有一个权威 store；store 的写入、读取、驱逐、TTL、删除都有明确所有者。
3. 请求边界按需水合：只把本次真正要发的图片变成 provider 能接受的字节形式；请求结束即释放。
4. 模型语义不变：当前轮/最近一轮的用户附件仍可见；旧图降级为可操作的 placeholder；caption path、text-only model、413/400 恢复都保持现有行为。
5. 可观测：能回答“哪张图、多大、在哪、为什么被驱逐/水合失败”。
6. 可灰度、可回滚：A1 可以按部署/会话关闭，B0 的 retention 仍作为兜底。

### 2.2 非目标

- 不做跨会话的媒体库、CDN、长期资产中心。
- 不在本期把 PDF / VIDEO 接到 ref（模型预留 `MediaRef`，但只接线 IMAGE）。
- 不改变 provider 的模型选择、caption 策略、多模态路由。
- 不把 ref 持久化进 Server 的 `chat_messages`；Server 重建仍然只恢复文本（与今天一致，重建后旧图不可见）。
- 不解决 P3（span ingest 字节预算）、P4–P9（度量/准入/JVM 信号）；只在接口上给 Q3/Q4 留位置。

### 2.3 不变量（硬约束）

以下不变量是后续实现、测试和验收的硬约束；任何实现不得绕过。

1. **单一所有者不变量**：每张图片的原始字节恰好存在于一个权威 `SessionMediaStore` 对象中。`Message.content` 中的 `IMAGE_REF` 只含 locator + 元数据，不含 base64；`history` / `AgentPersistence` 不持有超过 `maxInlineMetadataBytes`（建议 4 KB）的内联媒体描述。
2. **表示与传输分离不变量**：会话状态与持久化快照中不出现 hydrated bytes；hydration 只发生在一次请求的 wire copy 内，请求结束即不可达。
3. **请求纯函数不变量**：pruner 与 hydrator 不得修改 `agent.getMessages()`；请求组装是 `(state, limits, provider) -> wire payload` 的纯函数。Retention 是独立、显式的步骤，不能藏在请求路径里隐式改状态。
4. **有界性不变量**：会话 ref 数、store 字节、单图字节、请求水合字节都有上限；任何产生路径（用户上传、CLI 附件、read_file、sandbox、MCP、生成图）不得绕过 budget。
5. **可重取 / 可降级不变量**：ref 失效时必须降级为带 source 的 placeholder；**绝不**把 `IMAGE_REF`、`coreai-image://` 或任何内部 locator 原样发给上游。
6. **稳定水合不变量**：同一 ref 在多次请求中水合出的 wire 字节必须 byte-identical（保护 prompt cache）；不得在 hydration 时重新编码/压缩，除非 ref 本身记录的就是最终 wire bytes。
7. **安全不变量**：ref 解析必须校验 session / user / allowed root；跨会话、跨用户、路径穿越、任意 URL 读取一律拒绝。ref 不携带 secret、签名或可伪造的绝对路径。
8. **兼容性不变量**：旧快照可读；未知 `ContentType` / ref 在加载时降级为文本，不崩溃；新快照被旧版本读取时明确报 `UnsupportedSnapshotVersionException`，不静默丢数据。
9. **可观测不变量**：每次 put / read / prune / hydrate / evict / miss 都有计数、字节和失败原因；trace 输入中不出现 `data:` base64。
10. **可回滚不变量**：feature flag 关闭时回到 legacy inline 路径；B0 的 retention 仍生效；关闭 A1 不会导致会话无法恢复。

## 3. 现状与约束（源码事实）

### 3.1 图片从哪些路径进入会话

| 入口 | 当前行为 | 关键代码 |
|---|---|---|
| Server 用户上传 | 在**发消息时**从 object storage 下载原图、`ImageDownscaler.shrink`、base64，塞进 `AttachedContent.data`；同时写一条 `SessionAttachmentRef`（kind=IMAGE）用于审计/沙箱 | `ChatMessageAttachments.java:215-232`、`:234-253`；`AgentHelper.java:264-286` |
| CLI / Desktop 附件 | 从磁盘读文件、校验大小、base64；图片直接作为 `AttachedContent` | `core-ai-cli/.../SendParts.java:53-56`、`:96-110`；`AgentHelper.java:188-224` |
| `read_file`（host） | 读图片字节 → `ToolCallResult.withImage(base64, mimeType)` → `AgentHelper.buildImageContent` 变成 `data:image/...` | `ReadFileTool.java:141-155`；`AgentHelper.java:170-186` |
| `read_file`（sandbox） | 通过 sandbox 文件端点下载 → base64 → `withImage`；超过 10 MB 直接拒绝 | `SandboxImageReader.java:36-81` |
| MCP 工具返回图片 | MCP content part 的 base64 → `withImage` | `McpClientService.java:370` |
| `generate_image` / `generate_video` | 已经落文件 / sink，返回 path 或 URL，不内联 base64 | `GeneratedImageFiles.java:41-77` |

结论：**入口不止用户上传**。任何只改 `ChatMessageAttachments` 的方案都会漏掉 CLI 附件、`read_file`、sandbox、MCP 四条路径；这正是路线 A 不能“一次改完”的原因。

### 3.2 当前存储、持久化与恢复

| 事实 | 代码 |
|---|---|
| `NodeMessages` 用两个 `List<Message>` 保存 working messages 和 append-only history | `NodeMessages.java:16-18` |
| `recordHistory` 只按 role + 非空文本过滤，但存入的是**原 `Message` 对象**，因此图片字节被 history 共享引用 | `NodeMessages.java:68-73` |
| CLI `AgentPersistence` 序列化 `messages` **和** `history`；同一 `Message` 对象会在 JSON 中出现两次 | `AgentPersistence.java:35-38`、`:53-75` |
| CLI 快照版本当前为 1；高于 CURRENT 会明确抛错 | `SnapshotVersion.java:11-18` |
| Server `chat_messages.content` 是 `String`，只存文本；图片从不进这张表 | `ChatMessage.java:28`；`ChatMessageService.java:34-36`、`:49-62`、`:243-264` |
| Server 重建从 `chat_messages` 取文本，构造 USER/ASSISTANT 后 `restoreHistory()`（进入 `messages`，不是 `history`） | `SessionRestoreHelper.java:83-99` |
| Server agent 没有默认 persistence provider：bootstrap 不设 default type，`getDefaultPersistenceProvider()` 返回 null，`TurnPersistence.save` 直接 no-op | `AgentBootstrap.java:69-88`；`PersistenceProviders.java:18-20`；`SubAgentAssembler.java:243-244`；`TurnPersistence.java:22` |
| CLI 所有 history 消费者只读 `getTextContent()`（导出、history 分页、transcript 回放、ACP/runner 统计） | `EngineSessionRegistry.java:204-228`；`EngineSession.java:113-125`；`TranscriptHistoryPrinter.java:50-68`；`AcpSlashCommandHandler.java:238-241`；`AgentSessionRunnerCommandHandler.java:137-139` |
| 唯一按对象身份改 history 的地方是 undo：三处直接 `history.remove(message)` | `EngineSession.java:172-175`；`AcpSlashCommandHandler.java:205-208`；`AgentSessionRunnerCommandHandler.java:236-239` |
| CLI memory extraction 把 history 灌进 fork 的 messages，历史图片会被重发 | `AgentFork.java:18-22` |

**关键结论**：Server 不依赖 `NodeMessages.history` 携带 IMAGE_URL；它甚至不读 history 作为恢复源。history 文本快照是安全且独立的先行修复。

### 3.3 当前请求链路

1. `ModelGateway.handLLM` 用 `agent.getMessages()` 直接构造 `CompletionRequest`，`request.messages` 与 `agent.getMessages()` 是**同一个 list 对象**（`ModelGateway.java:20-28`、`CompletionRequest.java:11-31`）。
2. `CompressionLifecycle.beforeModel` 对 `request.messages` 做 `clear + addAll`，因此会原地改 agent 的 list（`CompressionLifecycle.java:44-63`）。
3. `LLMProvider.completionStream` 调 `enforceModalities`；`ModalityEnforcer` 返回新 list，`request.messages = pruneInlineImages(...)` 重新赋值，**不回写 agent**（`LLMProvider.java:198-217`）。
4. `InlineImagePruner.prune` 收集所有 `data:` 图片，按用户附件组 / 工具图片 / 最新保护规则选出保留集，`rewrite` 返回新 list（`InlineImagePruner.java:53-71`、`:205-224`）。
5. 413 时 `healRequest` 以 `maxImages=0` 再 prune 一次并重试（`LLMProvider.java:175-189`）。
6. wire encoder：
   - `LiteLLMProvider.chatCompletionStream` 用 `JsonUtil.toMap(request)` 重新序列化整个请求（`LiteLLMProvider.java:175-197`）。
   - `LiteLLMResponsesRequestMapper` 显式把 `IMAGE_URL` 映射成 `image_url`（`LiteLLMResponsesRequestMapper.java:136-156`）。
   - `GatewayLLMProvider` 先跑基类模板（含 tracing），再委派给上游 LiteLLM provider（`GatewayLLMProvider.java:117-125`、`:179`、`:364`）。
7. `ModalityEnforcer` 把不支持的图片降级为文本 placeholder；`IMAGE_URL` 的 placeholder 会把 URL（包括 `data:`）隐藏（`ModalityEnforcer.java:59-84`）。
8. caption path 对 base64 图片有两条路：有 `captionPathUrl` 时变成文本引用，否则变成 `Content.ImageUrl(dataUri)`（`AgentHelper.java:264-286`）。

### 3.4 当前 trace 链路

1. 每次 LLM 调用开始时，`LLMTracer.createLLMSpan` 把 `buildInputJson(request)` 写进 `langfuse.observation.input`（`LLMTracer.java:124`）。
2. `buildInputJson` 把整份 `request.messages`（含 data URI）序列化为一个巨大 JSON `String`（`LLMTracer.java:241-259`）。
3. `LocalSpanProcessor` 异步转 Protobuf、`TracePayloadStore.Compactor` 在入库阶段用正则把 data URI 替换成 `coreai-image://<sha256>`（`TracePayloadStore.java:197-227`）。
4. 也就是说：**压缩发生在巨大的 String 已经产生、并被 Span 持有之后**；P2 的拷贝没有被避免，只是落库时变小了。

### 3.5 框架与部署约束

- core-ng DTO / entity 不允许 `Object` 字段；`MediaRef` 必须用具体类型。新增 enum 值需要 `@Property(name=...)`（view/DTO）或 `@MongoEnumValue`（entity），两者不能混用。
- `core-ai` 不能依赖 `core-ai-server` 的 object storage；store 抽象必须在 core，实现由各端注入。
- Server 的 `SessionAttachmentRef` 已有 `sessionId` / `userId` / `container` / `blobName` / `contentType` / `sizeBytes` / `kind`，可复用为 BLOB ref 的元数据（`SessionAttachmentRef.java:12-56`）。
- `ObjectStorageService` 已提供上传、下载、签名 URL、`head`、`delete`（`ObjectStorageService.java:15-51`）。
- CLI / Desktop 的 session 文件由 `FilePersistenceProvider` 管理（`FilePersistenceProvider.java:14-51`），媒体目录可放在同一 session 目录下。
- Server 的 session 是 pod-local 的；重建走文本，不跨 pod 传 ref。因此 A1 的 ref 不需要在 Server 侧持久化，也不存在“旧 pod 解析新 ref”的混部问题；**唯一需要迁移的是 CLI session 文件**。

## 4. 核心模型

### 4.1 三层模型

| 层 | 内容 | 生命周期 | 存放 |
|---|---|---|---|
| Conversation State | `messages` / `history`：文本 + `MediaRef` | 会话级 | 进程内；CLI 快照；Server 不持久化 |
| Media Asset | 原始字节 + 元数据（id / sha256 / mediaType / size / owner） | 由 store 管理，可跨 turn | `SessionMediaStore`（CLI 文件 / Server blob） |
| Wire Payload | 本次请求实际发送的 data URI / 签名 URL | 单次 HTTP | provider encoder 的临时副本，请求结束即释放 |

### 4.2 `MediaRef`

提案字段（全部为具体类型，避免 `Object`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | 不透明标识；Server 用 `media_<uuid>`，CLI 可用 `sha256` 或 `<uuid>`；不得是可猜测的路径 |
| `kind` | `MediaRefKind` | `BLOB` / `FILE` / `SANDBOX` / `URL` |
| `container` | String | 仅 BLOB：object storage container |
| `blobName` | String | 仅 BLOB：object key |
| `path` | String | 仅 FILE / SANDBOX：受 root 校验的路径 |
| `url` | String | 仅 URL：https 地址；不保证可长期访问 |
| `mediaType` | String | e.g. `image/png` |
| `format` | String | e.g. `png`；hydration 不重新编码时直接复用 |
| `sizeBytes` | Long | 解码后字节数，用于预算与 prune |
| `sha256` | String | 内容哈希，用于去重与稳定水合；FILE / STORE 必填 |
| `etag` | String | BLOB 的内容标识；当 sha256 不可得时用于去重，不参与 wire 内容 |
| `displayName` | String | 用于 placeholder / trace / UI，如 `photo.png` 或 `Image 1` |
| `source` | String | 可选：产生它的工具参数或附件标签，用于“重新获取”提示 |

约束：

- ref 不携带签名、token、绝对 secret；BLOB 的签名 URL 在 hydration 时临时生成。
- `FILE` / `SANDBOX` 的 `path` 必须由 store 校验在允许的 root 内（workspace、session 目录、sandbox 允许目录），防止快照被篡改后读取任意文件。
- `sizeBytes` 是 BLOB / FILE / STORE ref 的必填项；`sha256` 是 FILE / STORE 的必填项，BLOB 可以用 `etag` 代替（两者都没有时不做去重，但仍可水合）。`URL` ref 可以只有 url + mediaType + size（未知时允许 null，但不得参与预算的精确计算）。

### 4.3 `Content.IMAGE_REF`

在 `Content.ContentType` 新增：

```java
@Property(name = "image_ref")
IMAGE_REF
```

并在 `Content` 增加：

```java
@Property(name = "image_ref")
public MediaRef imageRef;
```

配套工厂：

```java
public static Content of(MediaRef ref) { ... }
```

设计要点：

1. **不复用 `IMAGE_URL` + `coreai-image://` scheme**。复用会让所有 provider 的通用序列化路径（如 `JsonUtil.toMap(request)`）把私有 scheme 当成合法 URL 发出去；新增 enum 值会让所有 `switch (part.type)` 在编译期报错，逼着每个 switch 显式处理。**非 switch 的 if/else 路径（如 `LiteLLMResponsesRequestMapper`、`GatewayModelFailover`、`TranscriptProjection.hasNonText`）不会编译失败，必须靠 grep + `assertNoUnhydratedRefs` 覆盖。**
2. **wire encoder 必须 fail loud**：`LiteLLMResponsesRequestMapper` 遇到 `IMAGE_REF` 直接抛 `IllegalStateException("image ref was not hydrated")`；`LiteLLMProvider.chatCompletionStream` 在 `JsonUtil.toMap` 之前调用 `assertNoUnhydratedRefs(request)`；`GatewayLLMProvider` 复用同一入口。这样“把 ref 发给上游”是测试可捕获的硬错误，而不是 400。
3. **快照可序列化**：`AgentPersistence` 用同一套 `@Property` 序列化，ref 会进入 CLI 快照；`SnapshotVersion` 升到 2。旧版本读到 2 会明确报错，不做静默降级。
4. **PDF / VIDEO 预留**：`MediaRef` 本身与媒体类型无关；本期只新增 `IMAGE_REF`。后续可以加 `FILE_REF` / `VIDEO_REF`，或统一成 `BLOB_REF`，但不在本期承诺。

### 4.4 `SessionMediaStore`

core 侧接口（提案）：

```java
public interface SessionMediaStore {
    MediaRef put(SessionMediaKey key, byte[] bytes, String mediaType, String displayName, String source);

    /** Registers bytes that already live in a durable backend (Server upload blob) without copying them. */
    MediaRef register(SessionMediaKey key, MediaRef existing);

    Optional<MediaRef> find(SessionMediaKey key, String mediaId);

    Optional<byte[]> read(SessionMediaKey key, MediaRef ref);

    /** For providers that need a file path; the store owns the temp file lifetime. */
    Optional<Path> materialize(SessionMediaKey key, MediaRef ref);

    void deleteSession(String sessionId);

    MediaStoreStats stats();
}
```

`SessionMediaKey`：`record SessionMediaKey(String sessionId, String userId)`；Server 侧用于所有权校验，CLI 侧 userId 可以为空。

实现分工：

| 实现 | 位置 | 存储 | 说明 |
|---|---|---|---|
| `FileSessionMediaStore` | CLI / Desktop | `<sessionDir>/media/<sha256>.<ext>` | 原子写；按 sha 去重；root 校验；session close 删除 |
| `BlobSessionMediaStore` | Server | object storage `multimodalContainer` + `SessionAttachmentRef` | 用户上传可直接 `register` 现有 blob（零拷贝）；工具/MCP 字节走 `put` 上传；按 session 前缀 + TTL 清理 |

Server 的选择：**复用 `SessionAttachmentRef` 还是新建 `session_media` collection？**

- 复用优点：所有权、审计、已有 `findBySource` / `insert`；用户上传已经有 row，零迁移。
- 复用缺点：`SessionAttachmentRef` 语义是“消息附件来源”，工具生成图/MCP 图并不是附件；字段（`sourceETag`、`targetPath`）对 store 不全部有意义。
- 建议：**store 内部用 `SessionAttachmentRef` 作为 BLOB 索引，但新增 `kind=MEDIA` 并允许 `sourceETag` 为空**；如果 Review 认为污染附件表，则新建 `session_media` 并保留 `container/blobName/sha256/sizeBytes`。这是 §11 的待决项 D4。

### 4.5 生命周期状态机

`MediaRef`：

```
CREATED ──put/register ok──> AVAILABLE ──evict/TTL──> EVICTED
                                  │
                                  └──store missing/corrupt──> MISSING
```

会话：

```
ACTIVE ──turn end──> ACTIVE ──idle threshold──> IDLE ──close/TTL──> CLOSED
```

| 事件 | 对 ref 的动作 | 对 store 的动作 |
|---|---|---|
| `put` / `register` | 新 ref 进入 `messages` | 写 bytes + metadata；sha 去重 |
| 每次模型调用 | pruner 选本次水合集；不修改 ref | 只读；不驱逐 |
| turn end | 不修改 ref（A1）；B0 对 legacy data URI 做原地 prune | 可选：把本 turn 的 bytes 标记为热 |
| idle 阈值 / session close | 无 | 删除 session 前缀 / 目录；失败重试 + 对账 |
| TTL（如 24h） | ref 保留，读时可能 MISSING | 删除过期对象 |
| 配额压力 | 无 | 按 LRU / 最旧 session 驱逐；失败要可见 |
| 崩溃 | 快照里的 ref 可能 MISSING | 对账任务清理孤儿对象；不自动重建 bytes |

**A1 的关键变化**：ref 很小，所以“会话保留”和“字节保留”解耦。会话可以保留全部 ref（用于展示/追溯），但 store 可以按 TTL 驱逐字节；请求时如果 ref 已 MISSING，就降级为 placeholder。B0 时代“必须把旧图从 messages 删掉”的原地降级，在 A1 后不再是内存必需，只作为 legacy data URI 的兜底。

### 4.6 与 compression / token counter 的关系

- `MessageTokenCounterUtil` 现在把 `IMAGE_URL` 固定计 1000 tokens（`MessageTokenCounterUtil.java:19,67`）。A1 后，`IMAGE_REF` 的计数应区分：
  - 未被本次请求选中的 ref：只计 placeholder 文本的 token（很小）；
  - 被选中的 ref：按 provider 的图片估算（当前 1000/图，未来可按尺寸）。
- 但 compression 在 prune 之前运行，不知道本次会选哪些 ref。建议：compression 用“placeholder 文本 + 请求预算上限（maxInlineImages × 1000）”做保守估算，而不是把所有历史 ref 都按 1000 计。具体公式在 Q3 定稿；本设计只要求**不要因为历史图片数量而虚高触发压缩**。
- compression 丢弃消息时，ref 也随之离开 `messages`；store 的 TTL 独立，不因 compression 立即删除 bytes。

### 4.7 与 history / undo / restore 的关系

- `NodeMessages.recordHistory` 改为存文本快照（`Message.of(role, text)`），不共享原对象；history 不再 pin 图片。
- `NodeMessages.remove` / Agent 的 history 删除改为“从尾部按 role + text 匹配”，覆盖三处 undo 的直接 `history.remove(message)`；如果 Review 要求精确身份，再引入 `historyEntryId` 映射（需评估是否值得）。
- `AgentPersistence` 快照升级到 `SnapshotVersion=2`；旧文件（version 1）加载时：
  - history 中的 `data:` 内容清洗为文本 placeholder；
  - `messages` 中的 `data:` 可以选择懒迁移到 store（首次请求时 put）或保持 legacy inline（由 B0 的硬上限兜底）。建议首版懒迁移，避免加载时大批量 I/O。
- Server 重建仍然只恢复文本；ref 不跨 pod，因此不存在“重建后 ref 失效”的新问题（今天图片本来就丢）。
- `AgentFork` 的 memory extraction 改为只看到文本 history；这是有意行为变化（extraction 不需要图片），需在 Review 记录。

### 4.8 与 trace 的关系

- `LLMTracer` 看到的 `request.messages` 是 ref；`buildInputJson` 序列化出的是 `{type:"image_ref", id, mediaType, sizeBytes, sha256, displayName}`，不再有 base64。
- trace viewer 要显示图片时，走一个受权的 media 解析端点（Server）；CLI 本地文件的 ref 在跨端上传 trace 后无法解析，只能显示 placeholder 或在上传时附带 bytes（Q4 决定）。
- `TracePayloadStore` 的 `coreai-image://` 机制可以保留给 legacy data URI；A1 的 ref 不需要走正则压缩，因为 Span 里本来就没有 data URI。
- 这解决了 P2 的“Span 持有巨型字符串”，但 **HTTP body 的最后一份 data URI 仍然存在**；那是 M3，不是本设计的消灭目标。

## 5. 详细流程

### 5.1 产生：每条入口如何得到 ref

#### 5.1.1 Server 用户上传

目标：**发消息时不再下载、不再 base64**；只登记引用，水合推迟到真正发往上游时。

```
web upload ──> object storage (container/blobName)
                      │
ChatMessageAttachments.contents()
  ├─ validate container/blobName/contentType/size
  ├─ ImageDownscaler.shrink?  ← 仅当尺寸/格式超过 model 可读阈值
  ├─ store.register(key, MediaRef.blob(container, blobName, size, etag, sha?))
  └─ AttachedContent.imageRef(mediaId, displayName)
                      │
AgentHelper.buildImageAttachedContent()
  └─ Content.of(MediaRef)  // IMAGE_REF
```

细节：

- `sha256` 在 register 时可选：如果 object storage 的 `headObject` 有 ETag，可先用 ETag 去重；需要稳定水合时再算 sha（或由上传端提供）。没有 sha 的 BLOB ref 不参与跨会话去重，但仍可水合；FILE / STORE ref 必须带 sha。
- `ImageDownscaler` 仍在产生侧执行一次，确保 store 里存的就是最终 wire bytes；hydration 不再重新编码（不变量 I6）。
- 如果 `store.register` 失败（例如 blob 已被清理），**不要静默回退 inline**；给用户一个明确的“图片不可用，请重新上传”错误。用户上传的图是用户显式资产，静默降级会让模型答非所问。
- `SessionAttachmentRef` 已经记录 `container/blobName/contentType/sizeBytes`（`ChatMessageAttachments.java:234-253`）；如果走复用路线，store 只需补 `sha256` / `kind=MEDIA`。

#### 5.1.2 CLI / Desktop 附件

```
read file ──> validate size/type ──> ImageDownscaler.shrink?
          ──> FileSessionMediaStore.put(bytes)
          ──> MediaRef.file(sessionDir/media/<sha>.<ext>, size, sha)
          ──> Content.IMAGE_REF
```

- 本地文件 store 必须做 root 校验：只允许 workspace、session 目录、系统临时目录下的文件；拒绝 `..`、盘符切换、UNC。
- 文件写完用原子 rename；崩溃时不会留下半张图。
- 同一 session 内相同 sha 只存一份；ref 可以共享同一个 store 对象。

#### 5.1.3 工具读图（host / sandbox）

`ReadFileTool`、`SandboxImageReader` 当前把字节 base64 进 `ToolCallResult`（`ReadFileTool.java:141-155`、`SandboxImageReader.java:69-80`）。目标：

```
tool result bytes ──> store.put(key, bytes, mediaType, displayName, source=tool args)
                  ──> ToolCallResult.withImageRef(MediaRef)
                  ──> AgentHelper.buildImageContent() -> Content.IMAGE_REF
```

- `ToolCallResult` 新增 `imageRef` 字段（与 `imageBase64` 二选一）；`withImage(base64, format)` 保留给 legacy/测试，内部可以先 `put` 再转 ref。
- `SandboxImageReader` 已经下载到本地临时文件；可以直接 `put` 文件内容，避免再 base64。
- 单图上限：沿用 sandbox 的 10 MB 检查；超过则返回文本错误，不进入 store。
- 如果 store.put 失败：工具结果降级为文本（“图片已读取但无法保存到会话媒体库”），不把 base64 塞回消息；否则又回到无界驻留。

#### 5.1.4 MCP 工具图片

`McpClientService` 拿到的是内存 base64（`McpClientService.java:370`）。没有持久位置，必须 `store.put`。这是 A1 覆盖 producer 的关键一条：**MCP 图片是“一次性字节”，不存就永久丢**。

#### 5.1.5 生成图 / 视频

`GeneratedImageFiles` 已经落文件 / sink，并返回 path/URL（`GeneratedImageFiles.java:41-77`）。A1 只需要：

- CLI/Desktop：path 在 workspace 内 → `MediaRef.file`；
- Server：`ServerImageOutputSink` 返回 file/URL → `MediaRef.url` 或 `BLOB`；
- 不需要改生成链路。

### 5.2 请求：prune plan + wire copy

目标：**pruner 不改状态，只产出计划；hydrator 只作用于本次请求的副本。**

#### 5.2.1 流水线

```
agent.getMessages()  (IMAGE_REF + legacy data URI + text)
        │
        ├─ CompressionLifecycle.beforeModel()       // 只改文本/消息数，不碰 store
        │
        ├─ ModalityEnforcer.enforce()               // IMAGE_REF -> InputModality.IMAGE
        │                                           // unsupported -> TEXT placeholder
        │
        ├─ InlineImagePruner.plan(messages, limits) // 只读；输出 PrunePlan
        │
        ├─ LLMTracer.traceLLMCompletion(originalRequest)  // 看到 ref，不看到 base64
        │
        └─ hydrate(originalRequest, plan) -> wireCopy
                    │
                    ├─ kept ref      -> Content.IMAGE_URL(dataUri / signedUrl)
                    ├─ non-kept ref  -> Content.TEXT(placeholder)
                    ├─ legacy data:  -> kept: unchanged / non-kept: placeholder
                    └─ assertNoUnhydratedRefs(wireCopy)
                              │
                              └─ provider encoder (LiteLLM chat / responses / gateway)
```

#### 5.2.2 `PrunePlan`

提案：

```java
public record PrunePlan(
        Set<SlotKey> kept,          // (messageIndex, partIndex)
        List<Placeholder> placeholders,
        int prunedCount,
        long prunedBytes
) {}
```

- `SlotKey` 用消息下标 + part 下标；列表在本次请求内不变。
- `InlineImagePruner` 的现有选择逻辑（最新用户附件组保护、旧组按预算、工具图 newest-first、payload 去重）全部保留，只是输出从“新 list”变成“计划”。
- `rewrite(messages, plan)` 仍保留给 B0 / legacy 原地降级；A1 的请求路径不再调用它。

#### 5.2.3 `hydrate`

```java
CompletionRequest wireCopy(CompletionRequest request, PrunePlan plan, SessionMediaStore store) {
    var copy = request.wireCopy();          // 浅拷贝字段，messages 替换为新 list
    var messages = new ArrayList<Message>(request.messages.size());
    for (int i = 0; i < request.messages.size(); i++) {
        var message = request.messages.get(i);
        var content = new ArrayList<Content>(message.content.size());
        for (int j = 0; j < message.content.size(); j++) {
            var part = message.content.get(j);
            if (part.type == ContentType.IMAGE_REF) {
                if (!plan.kept().contains(new SlotKey(i, j))) {
                    content.add(Content.of(placeholder(part.imageRef, message, i)));
                    continue;
                }
                var dataUri = store.read(key, part.imageRef)   // key = SessionMediaKey(sessionId, userId)
                        .map(bytes -> dataUri(part.imageRef, bytes))
                        .orElseGet(() -> null);
                content.add(dataUri == null
                        ? Content.of(placeholder(part.imageRef, message, i))
                        : Content.of(Content.ImageUrl.of(dataUri, part.imageRef.format)));
            } else if (isLegacyInlineImage(part) && !plan.kept().contains(new SlotKey(i, j))) {
                content.add(Content.of(placeholder(part, message, i)));
            } else {
                content.add(part);
            }
        }
        messages.add(copyWithContent(message, content));
    }
    copy.messages = messages;
    assertNoUnhydratedRefs(copy);
    return copy;
}
```

- hydration 是同步 I/O；16 张图 / 12 MB 的顺序读取要控制在可接受延迟内。若 P95 超过预算（建议 300 ms），在 store 实现内并行读取（小线程池），但不要引入无界并发。
- hydration 失败（ref MISSING / 读失败）→ placeholder + `hydration_miss` 指标；**不抛异常打断整个 turn**，除非用户当前轮的附件全部失败（此时给用户明确错误）。
- `assertNoUnhydratedRefs` 在 `LiteLLMProvider.chatCompletionStream` 和 `LiteLLMResponsesRequestMapper` 各调用一次；Gateway 复用同一入口，不重复实现。

#### 5.2.4 413 / 400 / modality 恢复

| 场景 | 当前行为 | A1 行为 |
|---|---|---|
| 413 Request Entity Too Large | `healRequest` 用 `maxImages=0` 再 prune，重试一次（`LLMProvider.java:175-183`） | 从原始 ref 重新 plan（kept 为空），hydrate 成全 placeholder，重试；**不修改 `agent.getMessages()`** |
| 400 图片被拒 | 标记 model 为 text-only，`enforceModalities` 降级后重试（`LLMProvider.java:184-189`） | 同上；ref 保留在 store，后续 caption path 仍可用 |
| 模型不支持图片 | `ModalityEnforcer` 在 tracer 之前把 `IMAGE_REF` 降级为文本 | 保持；placeholder 用 displayName/source，不用内部 locator |
| caption path | base64 图片在非 vision-native 时转文本引用或 data URI（`AgentHelper.java:264-286`） | ref 转文本引用 + `media_id`；`CaptionImageTool` 通过 store 解析 media_id（新增一条解析路径），不让 base64 回流 |

### 5.3 水合位置：为什么必须在 tracer 之后、provider 之前

这是 A1 与 P2 的关键顺序约束。`completionStream` 在 `enforceModalities` 之后计算 `PrunePlan`，把 `(originalRequest, plan)` 传给 `invokeCompletionStream`；下面的代码只展示 hydrate 的时机：

```java
private CompletionResponse invokeCompletionStream(CompletionRequest request, PrunePlan plan, ...) {
    if (tracer != null && withTracing) {
        // tracer 拿到原始 request（ref），lambda 内部才 hydrate
        return tracer.traceLLMCompletion(name(), request,
                () -> doCompletionStream(hydrate(request, plan), wrappedCallback),
                llmSpanContextSink, wrappedCallback::isCancelled);
    }
    return doCompletionStream(hydrate(request, plan), wrappedCallback);
}
```

- 如果先 hydrate 再进 tracer，Span 仍然持有 base64，A1 对 P2 的红利为零。
- 如果 hydrate 放在每个 provider 的 encoder 内部，逻辑会重复，且 Gateway 的委派路径容易漏；放在 `invokeCompletionStream` 的 lambda 里，所有 provider（LiteLLM chat / responses / Gateway）共享一次。
- `healRequest` / `shouldRetryWithoutImages` 仍在原始 request（ref）上工作；重试时重新 plan + hydrate。

### 5.4 Retention 与预算

#### 5.4.1 A1 之后

ref 很小，**会话内存不需要按图片字节驱逐**。Retention 变成三个独立预算：

| 预算 | 默认值（提案） | 作用点 | 超限行为 |
|---|---|---|---|
| 请求图片数 / 字节 | 16 张 / 12 MB decoded（沿用现有） | `InlineImagePruner.plan` | 按现有选择规则降级为 placeholder |
| 会话 ref 数 | 512（提案） | `messages` 增长 | 触发文本 compression；不删 store bytes |
| store 字节 / session | 256 MB（提案） | `SessionMediaStore` | LRU 驱逐最旧未使用对象；ref 读时 MISSING |
| store TTL | 24h after last activity（提案） | store 清理任务 | 删除 bytes；ref 保留 |
| 单图字节 | 16 MB decoded（提案） | put / register | 拒绝并返回文本错误 |
| 全局 store 配额 | 按部署配置 | store / 对账 | 拒绝新 put 或驱逐最旧 session |

#### 5.4.2 B0 过渡（A1 之前）

B0 仍然需要在 `messages` 里做原地降级，因为此时图片还是 base64：

- 在 `ModelGateway.handLLM` 构造请求前，对 `agent.getMessages()` 跑一次 `InlineImagePruner.prune` 并**原地回写**（`clear + addAll`）。
- turn 结束再跑一次，覆盖“最后一个 tool 结果是大图、会话随后 idle”的场景。
- retention 模式必须有独立硬上限（建议 24 MB decoded），即使是最新用户附件组也不能无限豁免；请求模式保留现有豁免，由 413 重试兜底。
- history 文本快照同时上线，避免压缩/删除后 history 继续 pin 旧图。
- B0 的原地降级在 A1 之后只对 legacy data URI 生效；新产生的 `IMAGE_REF` 不参与。

#### 5.4.3 驱逐顺序

1. 非当前请求、非最近一轮的用户附件组（oldest first）。
2. 旧的工具图片（oldest first）。
3. store 中 LRU 的未引用对象。
4. 超过 TTL 的对象。
5. 全局配额压力下，最久未活动的 session。

**永不驱逐**：当前请求 plan 选中的 ref；当前 turn 最新用户附件组；当前 turn 最新工具图片（除非超过单图硬上限）。

### 5.5 失败矩阵

| 失败 | 检测 | 行为 | 指标 / 日志 |
|---|---|---|---|
| store.put 失败（用户上传） | 异常 / 磁盘满 / blob 403 | 拒绝该消息，提示重新上传；不静默 inline | `media_put_failure{reason}` |
| store.put 失败（工具/MCP） | 同上 | 工具结果降级为文本；不把 base64 塞回消息 | 同上 + tool name |
| store.read MISSING | ref 不存在 / TTL 已删 | 该图 placeholder；其他图继续 | `hydration_miss{kind}` |
| blob 404 / 403 | store 读失败 | placeholder；不把 ref 发给上游 | 同上 |
| sandbox 文件消失 | sandbox 读失败 | placeholder + source 提示重新生成 | `hydration_miss{kind=sandbox}` |
| URL 过期 | HTTP 4xx | 若 provider 支持 URL，回退 base64（从 store 读）；否则 placeholder | `hydration_url_fallback` |
| ref 非法 / 跨用户 | 校验失败 | 拒绝；placeholder；安全日志 | `media_ref_denied` |
| 快照版本过高 | `SnapshotVersion.check` | 明确报 `UnsupportedSnapshotVersionException`；不静默丢数据 | 启动错误 |
| 旧快照含 data URI | 加载时扫描 | 懒迁移到 store（首次请求）或 B0 硬上限兜底 | `legacy_inline_migrated` |
| hydration 延迟超预算 | 计时 | 记录 P95/P99；超过阈值告警；不改语义 | `hydration_latency_ms` |

### 5.6 兼容性与迁移

#### 5.6.1 阶段

| 阶段 | Schema / 快照 | 兼容策略 |
|---|---|---|
| B0 | 无 schema 变化；`SnapshotVersion` 不变 | 纯文本 history + 原地 prune；旧客户端无感 |
| A1 | `Content.IMAGE_REF`；`SnapshotVersion=2`；CLI media 目录 | feature flag 默认关；按部署/用户灰度；旧 CLI 读 v2 明确报错 |
| A2 | store TTL / 对账 / URL 直传 | 向后兼容；flag 控制 |

#### 5.6.2 旧数据

- 旧 CLI 快照（version 1）：
  - `history` 中的 `data:` 在加载时清洗为文本（`[Image omitted: legacy inline payload]` + displayName/source 若可提取）。
  - `messages` 中的 `data:` 保留为 legacy inline；首次模型调用前由 B0 retention 硬上限处理；也可选择懒迁移到 store（推荐：懒迁移，避免一次性大批量 I/O）。
- 旧 Server 会话：`chat_messages` 本来就没有图片；重建后图片不可见，与今天一致，不需要迁移。
- 旧 trace：`TracePayloadStore` 的 `coreai-image://` 继续可读；A1 只改变新 span 的输入格式。

#### 5.6.3 回滚

- flag 关闭：
  - 新消息不再产生 `IMAGE_REF`；
  - 已有 ref 在 `hydrate` 时先读 store 转回 data URI（如果 store 可用），否则 placeholder；
  - 快照序列化仍写 ref（version 2），但旧版本不读；如果要支持旧 CLI，需要把 ref 序列化成 placeholder 文本（降级发布）。
- store 不可用：
  - 读失败 → placeholder；写失败 → 用户上传拒绝、工具图降级文本；
  - **不允许**“store 失败就回退 inline 常驻”，否则回滚会重新引入 P1。

## 6. 可观测性

### 6.1 新增指标

| 指标 | 类型 | 维度 | 用途 |
|---|---|---|---|
| `session_media_refs` | gauge | pod / session | 每个会话的 ref 数；判断上下文/快照膨胀 |
| `session_media_store_bytes` | gauge | pod / session | store 实际占用；配额与 TTL 依据 |
| `session_media_put_total` | counter | kind / result | 产生侧成功率 |
| `session_media_read_total` | counter | kind / result | hydration 命中/失败 |
| `session_media_hydration_bytes` | histogram | provider / kind | 每次请求水合字节；对照请求预算 |
| `session_media_hydration_latency_ms` | histogram | kind | store 读延迟；P95/P99 |
| `session_media_pruned_total` | counter | reason（budget / retention / 413 / 400） | 降级规模 |
| `session_media_evicted_bytes` | counter | reason（ttl / quota / close） | store 清理 |
| `session_media_miss_total` | counter | kind（blob / file / sandbox / url） | 失效原因 |
| `session_media_ref_denied_total` | counter | reason（owner / root / invalid） | 安全 |

命名按现有 `RuntimeSample` 的 snake_case 风格；最终字段名在 Q8 的度量语义里统一。

### 6.2 Trace

- span 输入中的图片：`{type:"image_ref", id, mediaType, format, sizeBytes, sha256, displayName}`；**零 base64**。
- span 属性增加 `coreai.media.hydrated_count` / `coreai.media.hydrated_bytes` / `coreai.media.missing_count`，用于在 trace 里直接看到“这次发了几张图、失败几张”。
- trace viewer 显示图片：Server 侧走受权 media 解析端点；CLI 跨端上传的本地 ref 在 Server 无法解析，显示 placeholder + metadata（除非 Q4 决定上传 bytes）。
- `TracePayloadStore` 的 `coreai-image://` 只服务 legacy data URI；A1 不再产生新的 data URI span。

### 6.3 Footprint

`AgentFootprint` 需要版本升级（`ESTIMATE_VERSION`），因为语义变化：

- `inlineImageBytes`：A1 后应接近 0（只有 legacy data URI）；
- 新增 `mediaRefCount`、`mediaRefBytes`（ref 元数据）、`mediaStoreBytes`（store 实际占用，按 session 汇总）；
- `historyCount` 仍是展示条数；history 文本快照会让文本被 `messages + history` 各计一次（`AgentFootprintCalculator` 用 identity 去重，克隆后不再去重）。这是可接受的少量重复，但需要在注释/文档里说明口径，避免 P8 的 Dashboard 误读。

### 6.4 Dashboard / 告警

- 会话卡片：`refs / store MB / hydration miss / last hydration latency`。
- 集群：`store bytes / session` 分布、`hydration_miss_rate`、`pruned_bytes(total)`、`413 rate`。
- 告警建议：
  - `hydration_miss_rate > 1%`（5 分钟）→ store/TTL 配置或 blob 权限问题；
  - `session_media_store_bytes` 接近配额 → 扩容或缩短 TTL；
  - `hydration_latency_p95 > 300ms` → store 读放大/网络问题；
  - `media_ref_denied_total > 0` → 安全事件。

## 7. 测试与验收

### 7.1 单元测试

| 对象 | 断言 |
|---|---|
| `MediaRef` | 字段校验；BLOB/FILE/SANDBOX/URL 的必填项；禁止 secret/绝对路径穿越 |
| `SessionMediaStore`（File） | put/read/dedup/delete；原子写；root 校验；磁盘满失败 |
| `SessionMediaStore`（Blob） | register 零拷贝；put 上传；ownership 校验；404 处理 |
| `InlineImagePruner.plan` | 现有选择规则不变；legacy data URI 与 IMAGE_REF 混合；最新组保护；硬上限 |
| `hydrate` | kept ref → data URI；non-kept → placeholder；store miss → placeholder；输出无 IMAGE_REF |
| `ModalityEnforcer` | IMAGE_REF → IMAGE；text-only → placeholder；幂等 |
| `NodeMessages` | history 文本快照；undo 的 role/text 匹配；restore 清洗旧文件 |
| `AgentPersistence` | version 2 序列化/反序列化；旧 version 1 兼容；ref 不含 bytes |
| `LLMTracer` | span 输入无 `data:`；含 ref metadata |

### 7.2 集成测试

1. **Server 上传链路**：上传 5 MB PNG → `ChatMessageAttachments.contents` 不产生 base64 → `Content.IMAGE_REF` → mock provider 收到 data URI（仅本次）→ store 有对象 → trace 输入无 base64。
2. **CLI 附件链路**：本地图片 → `FileSessionMediaStore` → 请求体 base64 → 重启 CLI → 快照加载 → 同一 ref 仍可水合（store 文件存在）。
3. **工具读图**：`read_file`/`SandboxImageReader` → `ToolCallResult.imageRef` → 请求水合；store 写失败 → 文本降级。
4. **413 恢复**：mock provider 第一次 413，第二次成功；断言第二次请求无图片、`agent.getMessages()` 仍是 ref、store 未被删除。
5. **400 图片拒绝**：mock provider 标记 text-only；断言 placeholder + ref 保留。
6. **store miss**：删除 store 对象 → 请求仍成功，图片变 placeholder，指标 +1。
7. **旧快照**：version 1 带 data URI 的 history/messages → 加载 → history 清洗、messages 懒迁移/保留；无崩溃。
8. **权限**：跨用户/session 的 ref → 拒绝 + 指标；不泄露 blob 内容。

### 7.3 E2E / 压测

- dev E2E（按项目规则，server 改动后必须主动跑）：一个含 20 张 2 MB 图片的会话，连续 5 turn；断言：
  - `AgentFootprint.inlineImageBytes` ≈ 0；
  - `heap_after_gc` 不再随图片数线性上升；
  - trace span 输入大小 < 100 KB；
  - 请求体仍包含被选中的图片（mock upstream 校验）；
  - 会话驱逐后 store 清理。
- UAT 灰度：单 pod + 白名单用户；对比同会话在 A1 前后的 `heap_after_gc / img_mb` 斜率；UAT/prod 需要用户明确授权。
- 压测：20 并发含图会话、每会话 10 张 2 MB 图；观察 store 读放大、P95 hydration、HTTP body 分配、GC humongous。

### 7.4 不变量断言（必须进 CI）

1. 序列化后的 `messages` / `history` / `AgentPersistence` 中，`data:` 串长度 ≤ `maxInlineMetadataBytes`（允许 legacy 测试除外）。
2. `hydrate` 输出中 `IMAGE_REF` 数量 = 0。
3. `LiteLLMProvider.chatCompletionStream` / `LiteLLMResponsesRequestMapper` 遇到 `IMAGE_REF` 必须抛错（负向测试）。
4. 同一 ref 两次 hydrate 的 data URI byte-identical。
5. 跨 session / 跨 user 的 ref 读取必须失败。
6. trace 输入 JSON 中 `data:image` 出现次数 = 0（A1 路径）。

### 7.5 验收矩阵

| 场景 | 通过标准 |
|---|---|
| 用户上传 1 张图，问“这是什么” | 模型可见；请求结束后会话无 base64；trace 无 base64 |
| 用户上传 5 张图，第二轮追问第 2 张 | 最近用户组仍被保护；模型可见 |
| 20 张工具截图的长 turn | 每步请求 ≤ 16 张/12 MB；会话内存不随步数线性增长 |
| store 删除后追问旧图 | placeholder + 可操作提示；turn 不失败 |
| 413 / 400 | 仅本次请求降级；会话 ref 不丢；重试成功 |
| 旧 CLI 快照 | 可加载；history 无图；messages 不崩 |
| Server 重建 | 文本恢复；不引用已删除的 store 对象 |
| 回滚 flag | 新消息回到 legacy inline；已有 ref 可降级读取 |

## 8. 备选方案与取舍

### 8.1 路线 A：引用态常驻 + 请求边界水合（本设计终态）

优点：

- 会话内存与图片字节解耦；密度最高；
- trace 看到 ref，P2 的 span 拷贝被根治；
- 旧图可保留 ref，展示/追溯不丢；
- 产生侧统一到 store，后续 PDF/VIDEO 可复用。

缺点/成本：

- 改 `Content` 域模型 + 两个 provider encoder + caption path；
- 需要 store 抽象和 CLI/Server 两套实现；
- 迁移/版本/回滚复杂；
- hydration 增加同步 I/O 延迟。

### 8.2 路线 B：保留 Message 字节，靠原地降级 + history 文本化收敛

优点：

- 无协议变更；B0 可以几天内上线；
- 直接消除 UAT 的无界驻留和 history pin；
- 风险低、可回滚。

缺点：

- 每会话仍驻留一个请求预算（12 MB 量级），密度上不去；
- 每次调用仍有 pruner/provider/trace 的多份拷贝；
- 旧图降级是永久丢失（placeholder 只能让模型重新读/用户重传）；
- 无法根治 P2。

### 8.3 分阶段（本设计建议）

**B0 先上，A1 作为终态。** 理由：

- A1 的失效模式是覆盖率（漏一条产生路径 = 仍泄漏），B0 是兜底；
- B0 是 A1 的必要安全网：即使 A1 某个 producer 漏了，retention 仍能限制最坏情况；
- A1 的表示决策需要 Review，B0 不依赖它，可以先解决 UAT 风险。

### 8.4 被否方案

| 方案 | 否决理由 |
|---|---|
| 保留 base64，改 off-heap / 磁盘 LRU | provider 序列化仍要复制；history/快照仍要处理；复杂度高、收益只在驻留 |
| 只支持 provider 原生 URL | 不是所有 provider 都能取私有/签名 URL；URL 过期/鉴权/网络不可控；CLI 本地文件没有公网 URL |
| 只加强图片压缩/降分辨率 | 与 retention/copies 正交；压缩后的图仍会累积 |
| 只调低 `maxInlineImages` / `maxInlineImageBytes` | 治标；最新附件组仍可超预算；旧图仍驻留；trace 仍复制 |
| 只做 trace 正则替换（现状） | 巨型 String 已经产生并持有；P2 的拷贝没有避免 |

## 9. 分阶段实施

### 9.1 B0（先行，不依赖本设计的 Review）

| 任务 | 说明 | 退出条件 |
|---|---|---|
| B0-1 history 文本快照 | `recordHistory` 存 `Message.of(role, text)`；undo 改 role/text 匹配；restore 清洗旧文件 | history 中无 `data:`；CLI 导出/恢复/undo 测试通过 |
| B0-2 会话级 retention prune | `ModelGateway.handLLM` 前 + turn 结束原地回写；legacy data URI 生效 | 单会话 `inlineImageBytes` ≤ 硬上限；idle 会话不再持有大图 |
| B0-3 硬上限 | retention 模式覆盖最新组豁免；建议 24 MB decoded | 最新组超限也会降级；413 路径不受影响 |
| B0-4 指标 | `inlineImageBytes` 高水位、pruned bytes、store 预留指标 | Dashboard 可见 |

B0 的风险：原地降级会改变“旧图仍可见”的现状；但只对超预算/超硬上限的会话生效，且 placeholder 已存在于当前请求行为中，语义一致。

### 9.2 A1（本设计主体）

| 任务 | 说明 | 退出条件 |
|---|---|---|
| A1-1 `MediaRef` + `Content.IMAGE_REF` | 域模型、序列化、assertNoUnhydratedRefs | 单测 + 负向测试通过 |
| A1-2 `SessionMediaStore` | core 接口；File / Blob 实现；TTL/delete | put/read/dedup/权限测试通过 |
| A1-3 producer 迁移 | Server 上传（register）、CLI 附件（put）、read_file/sandbox/MCP（put） | 每条入口都有集成测试；无 base64 进入 messages |
| A1-4 prune plan + hydrate | pruner 输出计划；tracer 后 hydrate；413/400 重试 | 请求体正确；span 无 base64；会话状态不变 |
| A1-5 history/undo/restore | 文本快照、version 2、旧文件清洗/懒迁移 | 旧快照可读；undo 语义不变 |
| A1-6 flag + 灰度 | `session.media.refs.enabled`；按部署/用户 | dev E2E；UAT 单 pod 灰度 |

A1 的退出条件（硬）：

- 不变量 I1–I10 全部有测试；
- dev E2E 通过；
- UAT 灰度下 `heap_after_gc / img_mb` 斜率显著下降；
- 回滚演练通过（flag off + store 不可用）。

### 9.3 A2（后续）

- provider URL 直传（支持签名 URL 的 provider）；
- PDF / VIDEO 接入 `MediaRef`；
- store TTL/对账/跨 pod 清理；
- trace viewer 的 media 解析端点；
- Server 重建时可选恢复 ref（如果产品需要旧图跨重启可见）。

## 10. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| store 读延迟 | 每次请求多 10–300 ms；长 turn 放大 | 只水合选中的图；store 内并行读；P95 告警；必要时本地 LRU 缓存（有界） |
| store 不可用 | 图片全部 placeholder，用户体验退化 | store 健康检查；重试；blob 多副本；失败可见；不静默回退 inline |
| trace 图片不可见 | 调试体验下降 | Server 侧 media 解析端点；CLI 上传 trace 时附带 bytes（Q4 决定） |
| 旧 CLI 读新快照 | 启动报错 | `SnapshotVersion=2` + 明确错误 + Desktop 强制升级；降级发布时把 ref 序列化为 placeholder |
| prompt cache 失效 | 成本上升 | ref 稳定、hydration byte-identical；placeholder 文本确定性；不按计数器生成文本 |
| sandbox 文件生命周期 | ref 在 sandbox 释放后 MISSING | 产生时尽量 `put` 到 session store；或在水合失败时提示重新生成 |
| MCP 一次性图片 | 不存就永久丢 | `McpClientService` 强制 put；失败降级文本 |
| 安全 / 越权 | 读取其他用户图片 | ref 不透明 + session/user 校验 + root 校验；安全指标与审计 |
| store 成本 | blob/磁盘增长 | 每 session 配额 + TTL + LRU；对账清理孤儿 |
| caption path 回归 | text-only 模型看不到图 | `CaptionImageTool` 接受 media_id；集成测试覆盖 |
| Server 重建仍丢图 | 与今天一致，但用户可能期待恢复 | 文档明确；A2 再评估是否把 ref 持久化进 chat_messages |
| flag 复杂度 | 两条路径长期并存 | B0 的 retention 保留为兜底；A2 清理 legacy 路径 |

## 11. 关键决策与未决事项

### 11.1 需要用户拍板的决策

| ID | 决策 | 提案 | 备选 |
|---|---|---|---|
| D1 | 新增 `Content.IMAGE_REF`，还是复用 `IMAGE_URL` + 私有 scheme | 新增 enum，fail loud | 复用 scheme（改动小，但容易漏发上游） |
| D2 | `SessionMediaStore` 放在 core 还是 server | core 接口 + 两端实现 | 仅 server（CLI/Desktop 无法统一） |
| D3 | 水合位置 | `LLMProvider.invokeCompletionStream` 的 lambda，tracer 之后 | 每个 provider encoder 内部（重复、易漏） |
| D4 | Server store 复用 `SessionAttachmentRef` 还是新建 `session_media` | 复用 + `kind=MEDIA`；若 Review 认为污染则新建 | 新建 collection（干净，但多一套索引/迁移） |
| D5 | history 文本快照是否先于 A1 合入 | 是（B0） | 等 A1 一起（UAT 风险继续） |
| D6 | 预算默认值（请求 16/12 MB；store 256 MB/24h；单图 16 MB；硬上限 24 MB） | 按 UAT 现状取值 | 更保守/更宽松，需要容量规划输入 |
| D7 | v1 是否只做 IMAGE | 是 | 同时做 PDF/VIDEO（范围翻倍） |
| D8 | trace 是否需要显示历史图片 | 需要则加 media 解析端点；CLI 跨端另定 | 只显示 metadata/placeholder（实现最简） |
| D9 | Server 重建是否恢复 ref | 不恢复（与今天一致） | 把 ref 存进 chat_messages（新持久化契约） |
| D10 | 旧 data URI 的迁移 | 懒迁移（首次请求）；B0 硬上限兜底 | 加载时全量迁移（启动慢、I/O 大） |

### 11.2 待确认问题

1. Desktop 是否与 CLI 共用同一套 session 目录和 store？如果 Desktop 有自己的媒体目录，清理策略要统一。
2. Server 的 `SessionAttachmentRef` 是否允许 `kind=MEDIA`？现有索引/查询是否按 `kind` 过滤？
3. `CaptionImageTool` 的 `url` 参数是否允许 `media_id`？如果不允许，非 vision-native 模型的 ref 如何走 caption？
4. trace 上传（CLI → Server）是否包含本地图片字节？如果不包含，Server 端 trace 只能显示 metadata。
5. 单图 16 MB 的 decoded 上限是否与上游 provider 的实际限制一致？是否需要按 provider 配置？
6. 会话关闭后 store 是否立即删除，还是保留 24h 供 trace/审计？如果保留，谁负责清理？
7. `MediaRef` 是否需要在 trace / UI 中暴露 `sha256`？哈希本身不敏感，但可能被用于跨会话关联。
8. 旧 CLI 的升级策略：强制升级、还是把 ref 降级序列化为 placeholder？

## 附录 A：用户原始需求与文档治理

### A.1 原始需求摘录（2026-11-24）

> “一张图片在系统里本质是不可变的二进制大对象（Blob），而不是对话状态本身。对话状态（Message）真正需要持久持有的只是轻量引用（reference / URL / artifactId / hash + 元数据）；只有真正构造发往上游 LLM 的 HTTP 请求那一刻……才需要把当前这次请求实际要发送的那几张图临时物化（hydrate）成 base64，发完即丢。”

> “路线 A（最彻底，引用态常驻 + 请求边界水合）……路线 B（渐进式，保留内存 base64 但强制生命周期收敛）……或者分阶段（先 B 止血，再 A 架构收敛）？”

> “NodeMessages.history 目前除了‘保留第一条用户消息当标题’和‘CLI/会话持久化恢复’外，在 Server 端是否还有依赖它携带 IMAGE_URL 的场景？”

### A.2 需求追踪

| ID | 需求 | 落点 |
|---|---|---|
| R1 | 会话状态只持有轻量引用，不持有原始字节 | §2.3 I1、§4.2–§4.4 |
| R2 | 请求边界按需水合，发完即丢 | §2.3 I2、§5.2–§5.3 |
| R3 | 覆盖 CLI / Desktop / Server 的产生路径 | §5.1 |
| R4 | history 不拥有媒体，Server 不依赖 IMAGE_URL | §3.2、§4.7 |
| R5 | 失败可降级、可诊断，不把 ref 发给上游 | §2.3 I5、§5.5 |
| R6 | 可灰度、可回滚 | §2.3 I10、§5.6、§9 |
| R7 | 本期只出设计，不改代码 | 全文 |

### A.3 文档治理

- 本文为 DRAFT；未批准实施；所有新增字段/API/默认值均为提案。
- 评审使用当前会话模型，不使用无法稳定服务的模型；评审记录写入附录 C。
- 只出设计不动代码；实现前等用户确认 §11.1 的关键决策。
- 本文不把未验证的设计主张写成“现有架构事实”；源码事实集中在附录 B。

## 附录 B：源码事实

| 事实 | 位置 |
|---|---|
| `Message.content` 是 `List<Content>`，图片是 `IMAGE_URL` + `ImageUrl.url` 字符串 | `core-ai/src/main/java/ai/core/llm/domain/Message.java:64-78`；`Content.java:45-91` |
| 工具结果图片转成 `data:` URI | `core-ai/src/main/java/ai/core/agent/internal/AgentHelper.java:170-186` |
| 用户附件图片转成 `data:` URI / 文本引用 | `AgentHelper.java:188-224`、`:264-286` |
| Server 上传在发消息时下载 + shrink + base64 | `core-ai-server/src/main/java/ai/core/server/messaging/ChatMessageAttachments.java:215-232` |
| Server 已写 `SessionAttachmentRef`（kind=IMAGE） | `ChatMessageAttachments.java:234-253`；`SessionAttachmentRef.java:12-56` |
| CLI 附件读文件 + base64 | `core-ai-cli/src/main/java/ai/core/cli/appserver/SendParts.java:53-56`、`:96-110` |
| `read_file` 图片 → `withImage(base64)` | `core-ai/src/main/java/ai/core/tool/tools/ReadFileTool.java:141-155` |
| Sandbox 读图 → base64 | `core-ai/src/main/java/ai/core/sandbox/SandboxImageReader.java:69-80` |
| MCP 图片 → base64 | `core-ai/src/main/java/ai/core/mcp/client/McpClientService.java:370` |
| 生成图落文件/sink，不内联 | `core-ai/src/main/java/ai/core/tool/tools/GeneratedImageFiles.java:41-77` |
| `NodeMessages` 两个列表；history 共享原 Message | `core-ai/src/main/java/ai/core/agent/NodeMessages.java:16-18`、`:68-73` |
| CLI 快照同时序列化 messages + history | `core-ai/src/main/java/ai/core/agent/AgentPersistence.java:35-38`、`:53-75` |
| CLI 快照版本 1 | `core-ai/src/main/java/ai/core/session/SnapshotVersion.java:11-18` |
| `chat_messages.content` 是 String | `core-ai-server/src/main/java/ai/core/server/domain/ChatMessage.java:28` |
| ChatMessageService 是展示层，不喂 LLM | `core-ai-server/src/main/java/ai/core/server/session/ChatMessageService.java:34-36`、`:49-62`、`:243-264` |
| Server 重建从 chat_messages 文本恢复 | `core-ai-server/src/main/java/ai/core/server/session/SessionRestoreHelper.java:83-99` |
| Server 无默认 persistence provider | `core-ai/src/main/java/ai/core/bootstrap/AgentBootstrap.java:69-88`；`PersistenceProviders.java:18-20`；`SubAgentAssembler.java:243-244`；`TurnPersistence.java:22` |
| CLI history 消费者只读文本 | `EngineSessionRegistry.java:204-228`；`EngineSession.java:113-125`；`TranscriptHistoryPrinter.java:50-68`；`AcpSlashCommandHandler.java:238-241`；`AgentSessionRunnerCommandHandler.java:137-139` |
| undo 按对象身份删 history | `EngineSession.java:172-175`；`AcpSlashCommandHandler.java:205-208`；`AgentSessionRunnerCommandHandler.java:236-239` |
| CLI memory extraction fork 复制 history | `core-ai-cli/src/main/java/ai/core/cli/utils/AgentFork.java:18-22` |
| Pruner 只返回新 list，不回写 agent | `core-ai/src/main/java/ai/core/llm/InlineImagePruner.java:53-71`、`:205-224` |
| `enforceModalities` 重新赋值 request.messages | `core-ai/src/main/java/ai/core/llm/LLMProvider.java:198-217` |
| 413 重试用 `maxImages=0` | `LLMProvider.java:175-189` |
| compression 原地改 request.messages | `core-ai/src/main/java/ai/core/context/CompressionLifecycle.java:44-63` |
| `ModalityEnforcer` 的 IMAGE_URL 降级 | `core-ai/src/main/java/ai/core/llm/ModalityEnforcer.java:59-84` |
| LiteLLM chat 用 `JsonUtil.toMap(request)` 再序列化 | `core-ai/src/main/java/ai/core/llm/providers/LiteLLMProvider.java:175-197` |
| Responses mapper 显式映射 IMAGE_URL | `core-ai/src/main/java/ai/core/llm/providers/LiteLLMResponsesRequestMapper.java:136-156` |
| Gateway 先跑基类模板再委派 | `core-ai-server/src/main/java/ai/core/server/gateway/GatewayLLMProvider.java:117-125`、`:179`、`:364` |
| tracer 把完整 messages 序列化进 span | `core-ai/src/main/java/ai/core/telemetry/LLMTracer.java:124`、`:241-259` |
| TracePayloadStore 在入库阶段压缩 data URI | `core-ai-server/src/main/java/ai/core/server/trace/service/TracePayloadStore.java:197-227` |
| ObjectStorageService 支持上传/下载/签名/删除 | `core-ai-server/src/main/java/ai/core/server/blob/ObjectStorageService.java:15-51` |
| CLI 文件持久化 | `core-ai/src/main/java/ai/core/persistence/providers/FilePersistenceProvider.java:14-51` |
| 每图固定 1000 tokens | `core-ai/src/main/java/ai/core/utils/MessageTokenCounterUtil.java:19,67` |
| footprint 用 identity 去重 messages/history | `core-ai/src/main/java/ai/core/agent/AgentFootprintCalculator.java:20-38` |
| turn 结束刷新 footprint | `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:278-291`（`:289`） |

## 附录 C：Review 与修改历史

### C.1 版本记录

| 版本 | 日期 | 作者 | 修改 |
|---|---|---|---|
| v0.1 | 2026-11-24 | core-ai | 初稿：问题拆分、Q1/Q2 不变量、MediaRef/SessionMediaStore、B0/A1 分阶段、源码事实 |
| v0.2 | 2026-11-24 | core-ai | 用户授权开始实现，采用提案默认值；记录 A1 实施与偏离（见 C.4） |

### C.2 Review 记录

- 2026-11-24：用户明确授权开始实现，并指定采用 §11.1 的提案默认值：D1（新增 `Content.IMAGE_REF`）、D2（core 接口 + 两端实现）、D3（水合在 `LLMProvider.invokeCompletionStream` 的 lambda，tracer 之后）、D4（复用 `SessionAttachmentRef` + `kind=MEDIA`）、D6（16/12 MB 请求、256 MB/24h store、单图 16 MB、硬上限 24 MB）、D7（v1 仅 IMAGE）、D9（Server 重建不恢复 ref）、D10（懒迁移）。
- 评审模型与结论记录在此处，不写成“用户批准设计”。

### C.3 已知不足

1. `SessionMediaStore` 的 Server 实现细节（复用 `SessionAttachmentRef` vs 新 collection）尚未定稿。
2. `CaptionImageTool` 的 media_id 解析路径只给了方向，未给接口签名。
3. hydration 的并行度/超时/重试策略需要根据 dev 压测数据再定。
4. trace viewer 的 media 解析端点涉及鉴权与跨端上传，需 Q4 一起定。
5. PDF / VIDEO 只预留模型，未做成本评估。

### C.4 A1 实施记录与偏离

实现范围（2026-11-24）：core 的 `MediaRef` / `MediaRefKind` / `SessionMediaKey` / `SessionMediaStore` / `MediaStoreLimits` / `MediaHash` / `FileSessionMediaStore` / `MediaHydrator` / `LegacyImageMigration`，`Content.IMAGE_REF`，`InlineImagePruner` 支持 ref，server 的 `BlobSessionMediaStore` + `SessionMediaController` + `SessionMediaMaintenanceJob`，CLI/Desktop 的 file store 接线，`SnapshotVersion=2`。

与设计提案的偏离（均为最小可行调整）：

| # | 设计提案 | 实施 | 原因 |
|---|---|---|---|
| A-DEV-1 | `InlineImagePruner.plan` + 独立 `PrunePlan` 记录 | 沿用 pruner 的 `rewrite`：非 kept ref 先降级为 placeholder，kept ref 保留；`MediaHydrator` 在 tracer 之后把 kept ref 变成 data URI | 与现有 pruner/413 路径兼容，避免两套选择逻辑；不变量（不改状态、tracer 看 ref、hydrate 输出无 ref）不变 |
| A-DEV-2 | 每个 producer（ReadFileTool / SandboxImageReader / MCP / CLI 附件）直接 `store.put` | 统一在 `AgentHelper` 的消息构建点转换：`AttachedContent.mediaRef` → `register`，其余 base64 → `put` | `AgentHelper` 是唯一的 Content 构建点，一处覆盖所有 producer，避免漏一条路径；工具结果仍短暂携带 base64（有界） |
| A-DEV-3 | Server 上传零拷贝 `register` | `ImageDownscaler` 未改变字节时 register 原 blob；改变时 `put` 压缩后的字节 | 保持 shrink 只发生一次、满足稳定水合 I6；避免 hydration 重新编码 |
| A-DEV-4 | CLI 媒体目录放在 session 持久化目录 | `<workspace>/.core-ai/media/sessions/<sessionId>/` | `CliAgent.Config` 只有 workspace 和 sessionId，没有 sessionsDir；与现有 `.core-ai/media/images` 约定一致 |
| A-DEV-5 | `SessionMediaStore.materialize` | v1 不提供 | 当前 provider 只需 bytes/data URI，没有需要文件路径的 encoder |
| A-DEV-6 | session close 删除 store | Server 的 `deleteSession` 为空操作，TTL 清理任务按 `createdAt` 删除；CLI 的 file store 支持删除 | 服务端没有 session-close 的统一回调；TTL 已能约束增长，A2 再补 close 清理 |
| A-DEV-7 | 新增 DB 表/迁移 | 无迁移：复用 `session_attachment_ref` 的 JSON 文档与 `kind=MEDIA` | 该表在 PG 为 `id + data jsonb`，新增 kind 不需要列变更 |
| A-DEV-8 | trace 通过 media 端点显示原图 | A 提供 `GET /api/sessions/:sessionId/media/:mediaId`；B1 负责 span→session 的关联 | 端点鉴权依赖 session ownership，放 A 更自然 |
| A-DEV-9 | `pruneForRetention` 的硬上限 | 不改 B0 的 retention 实现（另一会话已合入 24 MB 硬上限）；A 的 ref 不参与 retention | 避免重复实现/回退 B0 |

