1. 目标

让图片、视频生成和普通 LLM 调用一样：

1. 在单个 Trace 的 spans 中可看到：
    - 调用类型：image_generation / video_generation
    - 请求模型与实际路由模型
    - token usage（上游有返回时）
    - 图片数量 / 视频秒数
    - 单次 cost、计价来源和价格快照
2. 对应的 Trace 自动累积 media generation 的 token 和 cost。
3. Admin Dashboard 的：
    - 全局 cost / token / call 统计
    - 按模型的排名
    - 选中模型后的趋势图
      都自然包含 image/video generation 模型。
4. 保持现有 text/multimodal LLM 统计、日聚合、realtime/history 两种模式兼容。

────────────────────────────────────────────────────────────────

2. 总体方案

核心原则：将 media generation 建模为 Trace Span

不要只在 media_jobs 记录成本，也不要在 dashboard 单独建另一套 media analytics。

图片、视频生成将生成一个标准 Span，写入现有 spans collection；然后把 usage/cost 原子累加进其父 Trace。

  ```text
  Agent Run / Session Trace
   ├─ LLM Span: plan / tool selection
   ├─ Tool Span: generate_image
   │   └─ Media Span: image_generation
   ├─ Tool Span: generate_video
   │   └─ Media Span: video_generation
   └─ LLM Span: final response
  ```

这里实际不强制要求 parent 为 Tool span（当前 tool 没有稳定的 telemetry context 可直接取）；第一期可直接把 media span 作为当前 trace 的子 span，或使用 request span context。重点是：

- span 与正确 trace / user / session / agent run 关联；
- Span.model 记录 gateway model ID；
- Span.costUsd 可参与 Trace 汇总；
- Trace.model 保持原有主 LLM 模型语义，不被 media model 覆盖。

Dashboard 现有按 trace.model 聚合，因此仅写 Span 还不足以自动出现在现有模型排行。需要扩展 analytics 的聚合来源，让模型维度同时聚合：

- LLM trace 总计；
- media span 明细。

下方说明具体处理。

────────────────────────────────────────────────────────────────

3. 数据模型

3.1 扩展 Span

保留现有字段：

  ```text
  trace_id
  span_id
  parent_span_id
  name
  type
  model
  input_tokens
  output_tokens
  cached_tokens
  cost_usd
  cost_source
  pricing_model_id
  input_price_per_1m_tokens
  output_price_per_1m_tokens
  attributes
  ```

新增 media 专用字段：

  ```java
  @Field(name = "operation")
  public String operation; // "image_generation" | "video_generation"

  @Field(name = "provider_id")
  public String providerId;

  @Field(name = "resolved_model")
  public String resolvedModel; // 上游实际模型

  @Field(name = "media_image_count")
  public Long mediaImageCount;

  @Field(name = "media_video_seconds")
  public Long mediaVideoSeconds;

  @Field(name = "media_request")
  public String mediaRequest; // 脱敏后的请求摘要 JSON
  ```

attributes 也同步存一份 OpenTelemetry 风格属性，便于查询、调试与向后兼容：

  ```text
  gen_ai.operation.name = "image_generation" / "video_generation"
  gen_ai.request.model = <gateway model id>
  gen_ai.response.model = <upstream model>
  gen_ai.system = <provider id>
  gen_ai.usage.input_tokens
  gen_ai.usage.output_tokens
  gen_ai.usage.total_tokens
  gen_ai.usage.image_count
  gen_ai.usage.video_seconds
  gen_ai.usage.cost_usd
  core_ai.media.request_id / core_ai.media.job_id
  ```

> mediaRequest 不保存图片 base64、API key 或完整 prompt input image 内容。
> 建议保存 model、size、quality、n、seconds、是否有 input image / mask 等计费相关参数。prompt 可以沿用 Trace 的数据脱敏策略，第一期不额外记录。

3.2 扩展 MediaJob

视频为异步任务，需要 job 上保存提交时的计费上下文和最终 usage/cost：

  ```java
  @Field(name = "trace_id")
  public String traceId;

  @Field(name = "span_id")
  public String spanId;

  @Field(name = "provider_id")
  public String providerId; // 已有

  @Field(name = "requested_model")
  public String requestedModel; // 已有

  @Field(name = "resolved_model")
  public String resolvedModel; // 已有

  @Field(name = "input_tokens")
  public Long inputTokens;

  @Field(name = "output_tokens")
  public Long outputTokens;

  @Field(name = "total_tokens")
  public Long totalTokens;

  @Field(name = "video_seconds")
  public Long videoSeconds;

  @Field(name = "cost_usd")
  public Double costUsd;

  @Field(name = "cost_source")
  public String costSource;

  @Field(name = "cost_recorded_at")
  public ZonedDateTime costRecordedAt;
  ```

幂等性字段：

  ```java
  @Field(name = "cost_applied")
  public Boolean costApplied;
  ```

保证视频轮询多次、多个客户端并发查询状态时，只能把 token/cost 写到 Trace 一次。

────────────────────────────────────────────────────────────────

4. 定价配置设计

当前 GatewayModelConfig 只有 token 单价：

  ```text
  input_price_per_1m_tokens
  output_price_per_1m_tokens
  ```

这不足以表示图/视频的上游收费规则。扩展为 media pricing 配置：

  ```java
  @Field(name = "image_price_per_unit_usd")
  public Double imagePricePerUnitUsd;

  @Field(name = "video_price_per_second_usd")
  public Double videoPricePerSecondUsd;
  ```

这里的 unit 定义为最终成功返回的一张图片。

第一期保持简单、明确：

┌────────────────────────────┬──────────────────────────┬───────────────────────────────────────┐
│ endpoint type              │ 计价字段                 │ cost                                  │
├────────────────────────────┼──────────────────────────┼───────────────────────────────────────┤
│ chat / embedding / caption │ input/output token price │ token-based cost                      │
│ image generation / edit    │ imagePricePerUnitUsd     │ imageCount × imagePricePerUnitUsd     │
│ video generation           │ videoPricePerSecondUsd   │ videoSeconds × videoPricePerSecondUsd │
└────────────────────────────┴──────────────────────────┴───────────────────────────────────────┘

4.1 图片 cost 计算优先级

  ```text
  1. 上游显式 cost（如果 provider / LiteLLM response 返回 cost）
  2. Gateway Model imagePricePerUnitUsd × 实际 imageCount
  3. Gateway Model token price × 上游返回 token usage
  4. unavailable（cost_usd = null）
  ```

4.2 视频 cost 计算优先级

  ```text
  1. 上游显式 cost
  2. Gateway Model videoPricePerSecondUsd × 最终实际 videoSeconds
  3. Gateway Model token price × 上游返回 token usage
  4. unavailable（cost_usd = null）
  ```

4.3 关键取值规则

图片数量

  ```text
  优先 response.usage.imageCount
  否则 response.data.size()
  否则 request.n
  ```

不会因为 request n 是 4 就直接计为 4；如果上游实际只返回 2 张，以最终响应为准。

视频秒数

  ```text
  优先完成态上游 usage.videoSeconds
  否则提交响应 usage.videoSeconds
  否则 request.seconds
  ```

由于部分 provider 只在提交阶段提供 usage，提交后先记录“候选计费 usage”；任务完成时若能取得更精确 usage 再覆盖。

若无法获得 actual duration、又使用 request seconds 回退，span 属性应明确：

  ```text
  core_ai.media.usage_source = "request_estimate"
  ```

这样 dashboard 能展示 cost，但审计时可判断其为估算。

4.4 价格快照

每一次 media span / video job 都保存：

  ```text
  cost_source
  pricing_model_id
  image_price_per_unit_usd
  video_price_per_second_usd
  input_price_per_1m_tokens
  output_price_per_1m_tokens
  ```

后续调整 Gateway Model 价格不会修改历史 usage/cost。

────────────────────────────────────────────────────────────────

5. 调用链与写入时机

5.1 引入 MediaTraceService

Server 中新增单一服务，集中处理：

  ```text
  MediaTraceService
   ├─ startImageGeneration(...)
   ├─ completeImageGeneration(...)
   ├─ startVideoGeneration(...)
   ├─ completeVideoGeneration(...)
   ├─ failMediaGeneration(...)
   └─ applyUsageAndCostOnce(...)
  ```

职责：

- 创建/更新 media Span；
- 根据 MediaUsage 和 model config 计算 cost；
- 持久化价格快照；
- 原子更新父 Trace 的 tokens、cost；
- 视频场景使用 costApplied 实现幂等；
- 避免让 Tool、Gateway、Job service 各自复制计算逻辑。

价格计算从现有 ModelPricingService 中拆出或扩展为：

  ```java
  MediaPricingService.resolveImage(...)
  MediaPricingService.resolveVideo(...)
  ```

而不是将 image count/video seconds 硬塞进现有 ModelPricingService.resolve(model, input, output, cached)。

────────────────────────────────────────────────────────────────

5.2 GenerateImageTool 调用流程

  ```text
  GenerateImageTool.execute
    → 从 ExecutionContext 取得 MediaTraceContext
    → MediaTraceService.startImageGeneration()
    → provider.generateImage(request)
    → MediaTraceService.completeImageGeneration(response)
    → 返回图片给 agent
  ```

需要在 ExecutionContext 增加一个服务端注入的不可序列化上下文对象，例如：

  ```java
  MediaTraceContext(
    traceId,
    userId,
    sessionId,
    agentRunId,
    agentId,
    agentName
  )
  ```

注意它只传 trace identity，不能把 Mongo service 放入 core 模块的 ExecutionContext，避免 core-ai 反向依赖 core-ai-server。

推荐方式：

- core-ai 定义一个轻量 MediaUsageListener interface；
- ExecutionContext 可选持有该 listener；
- Server 用 MediaTraceService 实现 listener 并在 AgentSessionManager / AgentRunBuilder 注入；
- GenerateImageTool / GenerateVideoTool 仅调用 listener，不依赖 server package。

接口示例：

  ```java
  public interface MediaUsageListener {
      MediaOperation beginImageGeneration(ImageGenerationRequest request);

      void completeImageGeneration(
          MediaOperation operation,
          ImageGenerationResponse response
      );

      MediaOperation beginVideoGeneration(VideoGenerationRequest request);

      void completeVideoSubmission(
          MediaOperation operation,
          VideoGenerationResponse response
      );

      void fail(MediaOperation operation, Throwable error);
  }
  ```

MediaOperation 是 core 模块的无服务依赖 request context/value object。

────────────────────────────────────────────────────────────────

5.3 GenerateVideoTool / 视频异步流程

视频难点是“提交成功”不等于“最终生成成功”，所以分两个阶段：

A. 提交时

  ```text
  GenerateVideoTool
    → listener.beginVideoGeneration
    → provider.generateVideo
    → listener.completeVideoSubmission
    → 返回 pending task
  ```

此时创建 Span，状态为 RUNNING / PENDING，并将其 spanId 关联到 MediaJob。

GatewayMediaProvider.generateVideo(...) 创建 MediaJob 时，需要接收 trace span identity。建议在 VideoGenerationRequest 不加 server-only 字段，而是：

- listener 在 completeVideoSubmission 后，以 gateway video handle 查找 MediaJob；
- 把 traceId / spanId 写入 job；
- 或由 ContextualMediaProvider 包装并将 operation id 传给 server gateway 内部方法。

更可靠的实现是后者：扩展 server 内部的 ContextualMediaProvider 和 GatewayMediaProvider.generateVideo 参数，传递一个内部 MediaTraceReference，从创建 job 时就原子写入关联关系。该 reference 不走 API、也不进入上游 request。

B. 完成或失败时

所有以下入口都调用同一结算方法：

- getVideoStatus
- downloadVideo 前的状态确认
- 后台 reconciliation job（建议增加）

当 provider 返回 terminal state：

  ```text
  completed
  failed
  cancelled
  ```

MediaJobService.updateVideoStatus()：

1. 更新 job 状态；
2. 对完成任务读取/确定 final usage；
3. 调用 MediaTraceService.completeVideoGeneration(job, usage)；
4. 使用条件更新：

  ```text
  cost_applied != true
  ```

确保只结算一次。

失败 / cancelled 默认：

  ```text
  costUsd = 0
  ```

但保留 provider 上报 cost 的能力：若上游明确返回已扣费 cost，则仍记录该 cost，并将 cost_source = upstream。

C. 长时间无人轮询的任务

仅依赖用户/agent poll，会导致完成视频迟迟不进入 Trace cost。因此应增加一个轻量定时 reconciliation：

  ```text
  每 1~5 分钟
    查询 state ∈ submitted/queued/processing 的 media_jobs
    调用上游 getVideoStatus
    更新状态及最终 usage/cost
  ```

该任务应有单批上限与异常隔离，避免 provider 故障影响 server 主流程。

────────────────────────────────────────────────────────────────

6. Trace 与 Span 统计规则

6.1 Trace 的字段

Trace 继续使用现有：

  ```text
  input_tokens
  output_tokens
  total_tokens
  cached_tokens
  cost_usd
  ```

Media span 完成时：

  ```text
  input_tokens += media usage.totalTokens（如上游提供）
  output_tokens += 0
  total_tokens += media usage.totalTokens（如上游提供）
  cost_usd += media cost
  ```

图片数量、视频秒数不塞进 Trace.totalTokens，避免伪造成 token；它们保存在 Span，并由 analytics 新字段单独聚合。

6.2 Trace 详情页面

现有 Trace spans 返回中，media span 可显示：

  ```text
  Operation: Image generation
  Model: gpt-image-2
  Provider: openai
  Images: 2
  Tokens: 1,235
  Cost: $0.0800
  Cost source: gateway_model
  Price snapshot: $0.04 / image
  ```

视频类似：

  ```text
  Operation: Video generation
  Model: sora-2
  Provider: openai
  Video duration: 10 s
  Cost: $1.20
  Price snapshot: $0.12 / second
  ```

> 这一部分假设现有 Trace Detail UI 已通用渲# Tech Design: Media Generation Cost Tracing & Admin Analytics

Goals

1. 在 Trace detail 中展示 generate_image 和 generate_video 的：
    - 使用的模型与 provider；
    - token usage（若上游返回）；
    - image count / video seconds；
    - cost；
    - cost 的来源与定价快照。
2. 将媒体生成成本累加到所属 Trace 的 total_tokens、cost_usd。
3. 在 Admin Dashboard 中，使媒体模型自然出现在：
    - By Model 统计；
    - By Provider 统计；
    - 总体调用数、token、cost；
    - 趋势图；
    - 已选择模型的趋势图。
4. 保持现有 text / multimodal LLM tracing 行为兼容。
5. 不把“提交视频任务”误记为“视频生成成功且已收费”。

────────────────────────────────────────────────────────────────

Scope and Principles

计费原则

┌──────────────────────┬──────────────────────────────────────────────────────────────────────┐
│ 场景                 │ 计费时机                                                             │
├──────────────────────┼──────────────────────────────────────────────────────────────────────┤
│ 图片生成             │ 上游 generateImage 成功返回时                                        │
│ 视频生成             │ 上游 video job 返回 usage 时立即记录；否则在任务首次完成轮询时记录   │
│ 图片/视频失败        │ 默认不记录 cost，除非上游明确返回 usage/cost                         │
│ 视频轮询多次         │ 只能记一次 cost，必须幂等                                            │
│ 外部系统提供真实费用 │ 优先采用上游 cost                                                    │
│ 未提供真实费用       │ 使用 Gateway Model 上配置的 media pricing                            │
│ 没有可用价格         │ 记录 usage，但 cost_usd = null / cost source=unavailable，不误记为 0 │
└──────────────────────┴──────────────────────────────────────────────────────────────────────┘

Token 的语义

为保持已有 dashboard DTO 和聚合逻辑不需要大规模改变：

  ```text
  input_tokens  = media provider 返回的 total_tokens（如果有）
  output_tokens = 0
  total_tokens  = input_tokens
  cached_tokens = 0
  ```

这并不表示图片或视频“只有输入 token”，而是目前 media response 的 usage 模型只提供 totalTokens。原始计费单位将独立保留：

  ```text
  media_image_count
  media_video_seconds
  ```

因此 dashboard 可以继续显示 token/cost/calls；Trace detail 可以额外显示图片数、视频秒数。

────────────────────────────────────────────────────────────────

1. 数据模型

1.1 扩展 GatewayModelConfig：增加媒体价格规则

现有：

  ```java
  inputPricePer1MTokens
  outputPricePer1MTokens
  ```

只适用于 text / multimodal token pricing，不足以对图片和视频计价。

新增字段建议：

  ```java
  @Field(name = "image_price_per_unit")
  public Double imagePricePerUnit;

  @Field(name = "video_price_per_second")
  public Double videoPricePerSecond;
  ```

语义：

┌────────────────────────┬──────────────┬────────────────────────┐
│ 字段                   │         单位 │ 用途                   │
├────────────────────────┼──────────────┼────────────────────────┤
│ image_price_per_unit   │  USD / image │ 按成功返回图片数量计费 │
│ video_price_per_second │ USD / second │ 按视频时长计费         │
└────────────────────────┴──────────────┴────────────────────────┘

定价规则

Image generation

  ```text
  cost = image_count × image_price_per_unit
  ```

image_count 优先级：

1. 上游 usage.image_count
2. 成功响应 data.size()
3. 请求 n
4. 无法确定则不估价

Video generation

  ```text
  cost = billable_video_seconds × video_price_per_second
  ```

billable_video_seconds 优先级：

1. 上游 usage.video_seconds
2. 请求中的 seconds
3. 无法确定则不估价

> 若某个 provider/model 的视频价格依赖 resolution、duration tier、fps、quality 等，初始版本通过 provider_extra 或不同的 Gateway Model 配置进行区分。例如将 veo-3-720p 和 veo-3-1080p 作为不同的 logical model 配置。
> 不建议第一版引入复杂且不可维护的 JSON pricing rule engine。

API/UI 影响

Gateway Model 的请求 / response / 编辑表单同步增加：

  ```text
  imagePricePerUnit
  videoPricePerSecond
  ```

当这些价格手工修改时：

  ```text
  pricing_source = manual
  pricing_updated_at = now
  ```

模型发现若获得这些价格也可带入；但各 provider 的 model discovery 格式不统一，第一版不依赖 discovery 自动发现 media pricing。

────────────────────────────────────────────────────────────────

1.2 扩展 Span：保存媒体计费和使用快照

继续沿用现有 span 字段：

  ```text
  model
  input_tokens
  output_tokens
  cached_tokens
  cost_usd
  cost_source
  pricing_model_id
  input_price_per_1m_tokens
  output_price_per_1m_tokens
  ```

新增字段：

  ```java
  @Field(name = "operation_type")
  public String operationType; // llm, image_generation, video_generation

  @Field(name = "media_image_count")
  public Long mediaImageCount;

  @Field(name = "media_video_seconds")
  public Long mediaVideoSeconds;

  @Field(name = "image_price_per_unit")
  public Double imagePricePerUnit;

  @Field(name = "video_price_per_second")
  public Double videoPricePerSecond;
  ```

示例 image span：

  ```json
  {
    "name": "generate_image",
    "type": "TOOL",
    "operation_type": "image_generation",
    "model": "gpt-image-2",
    "input_tokens": 1250,
    "output_tokens": 0,
    "total_tokens": 1250,
    "media_image_count": 2,
    "cost_usd": 0.08,
    "cost_source": "gateway_model",
    "pricing_model_id": "gpt-image-2",
    "image_price_per_unit": 0.04
  }
  ```

示例 video span：

  ```json
  {
    "name": "generate_video",
    "type": "TOOL",
    "operation_type": "video_generation",
    "model": "veo-3-720p",
    "input_tokens": 0,
    "output_tokens": 0,
    "total_tokens": 0,
    "media_video_seconds": 8,
    "cost_usd": 0.4,
    "cost_source": "gateway_model",
    "pricing_model_id": "veo-3-720p",
    "video_price_per_second": 0.05
  }
  ```

attributes 中还会保留基础审计信息，例如：

  ```text
  gen_ai.operation.name = image_generation / video_generation
  gen_ai.request.model
  gen_ai.system = gateway provider id
  media.image_count
  media.video_seconds
  media.job_id
  media.upstream_video_id
  ```

────────────────────────────────────────────────────────────────

1.3 扩展 MediaJob

视频任务是异步的，必须在数据库中保存“是否已记账”，防止多次 poll 导致重复累计。

新增：

  ```java
  @Field(name = "trace_id")
  public String traceId;

  @Field(name = "parent_span_id")
  public String parentSpanId;

  @Field(name = "media_span_id")
  public String mediaSpanId;

  @Field(name = "input_tokens")
  public Long inputTokens;

  @Field(name = "video_seconds")
  public Long videoSeconds;

  @Field(name = "cost_usd")
  public Double costUsd;

  @Field(name = "cost_source")
  public String costSource;

  @Field(name = "cost_recorded_at")
  public ZonedDateTime costRecordedAt;
  ```

其中：

- mediaSpanId 是生成视频提交时创建的 span；
- costRecordedAt != null 是成本幂等写入标记；
- 若 submit response 已带 usage，则创建 job 后立刻结算；
- 若 submit 没 usage，则第一次检测到 completed 时结算；
- 已结算的视频任务不会因重复 polling 再次更新 trace total。

────────────────────────────────────────────────────────────────

2. Trace / Span 写入设计

2.1 新增统一服务：MediaTraceService

位置建议：

  ```text
  core-ai-server/.../trace/service/MediaTraceService.java
  ```

职责：

1. 读取 execution / run 上下文并定位 Trace。
2. 创建或更新 media span。
3. 调用独立的媒体定价解析器。
4. 原子累计所属 Trace 的：
    - input_tokens
    - total_tokens
    - cost_usd
5. 为视频任务提供幂等结算方法。

建议接口：

  ```java
  MediaSpanRecord recordImageGeneration(
      MediaTraceContext context,
      GatewayRoute route,
      ImageGenerationRequest request,
      ImageGenerationResponse response
  );

  MediaSpanRecord recordVideoSubmission(
      MediaTraceContext context,
      GatewayRoute route,
      VideoGenerationRequest request,
      VideoGenerationResponse response,
      MediaJob job
  );

  void finalizeVideoGeneration(
      MediaJob job,
      VideoStatusResponse status
  );
  ```

MediaTraceContext 包含：

  ```text
  traceId
  parentSpanId
  userId
  sessionId
  agentRunId
  agentId
  agentName
  ```

2.2 Trace 关联

Agent run

AgentRunBuilder 已经在 ContextualMediaProvider 中传入：

  ```text
  userId
  sessionId
  agentRunId
  ```

增强为同时传入本次 agent run 的 traceId。若 run 的 trace 尚未创建，可：

- 先创建一个 trace root；
- 或将关联信息暂存于 job，等 Trace 出现后补链。

建议采用 lazy association：

1. ContextualMediaProvider 传递 agentRunId 等上下文；
2. MediaTraceService 从 agent_runs.trace_id 获取 trace；
3. trace 不存在时先跳过创建，保留 job context；
4. 在后续视频 completed / trace 可用时再关联；
5. 图片 generation 是同步调用，正常情况下 agent trace 已存在。

这避免工具层直接依赖 OpenTelemetry SDK 的 active span 状态。

Session（非 Agent Run）

AgentSessionManager 已提供：

  ```text
  userId
  sessionId
  ```

可通过 session 的当前 trace / 或新建 trace 将媒体 span 关联进去。需要复用当前 session trace 的解析方式，避免媒体调用变成孤立数据。

────────────────────────────────────────────────────────────────

3. 媒体定价解析

3.1 新增 MediaPricingService

独立于现有 ModelPricingService：

  ```text
  ModelPricingService  → LLM input/output token pricing
  MediaPricingService  → image / video usage-unit pricing
  ```

建议返回：

  ```java
  record MediaPrice(
      Double costUsd,
      String source,
      String pricingModelId,
      Double imagePricePerUnit,
      Double videoPricePerSecond
  ) {}
  ```

优先级

Image

  ```text
  1. 上游明确返回 cost_usd（未来 provider adapter 支持时）
  2. Gateway Model: image_price_per_unit × resolved image count
  3. unavailable
  ```

Video

  ```text
  1. 上游明确返回 cost_usd
  2. Gateway Model: video_price_per_second × billable seconds
  3. unavailable
  ```

> inputPricePer1MTokens 不会用于 image/video generation 的成本估算。
> 若 provider 返回 totalTokens，它只计入 usage / analytics token 指标，不被错误套用为 LLM 的 input/output token price。

3.2 价格快照

写入 span/job 时固化：

  ```text
  pricing_model_id
  image_price_per_unit
  video_price_per_second
  cost_source
  cost_usd
  ```

后续管理员修改 Gateway Model 价格时，历史 span 和已结算 job 的成本不变化。

────────────────────────────────────────────────────────────────

4. Provider 与 Tool 调用改造

4.1 MediaProvider response

保留现有：

  ```java
  Usage(Integer totalTokens, Integer imageCount, Integer videoSeconds)
  ```

建议扩展为：

  ```java
  Usage(
      Integer totalTokens,
      Integer imageCount,
      Integer videoSeconds,
      Double costUsd
  )
  ```

这样 provider adapter 可传递上游真实 cost。

兼容性：

- 所有 adapter 更新构造函数；
- 没有成本的 provider 返回 null；
- API 兼容，因为这是内部 Java record，不是外部稳定 API。

4.2 GatewayMediaProvider

GatewayMediaProvider 是最合适的 server-side interception point，因为此处同时掌握：

- Gateway route；
- logical requested model；
- resolved upstream model；
- 上游 response；
- MediaJob 创建 / 完成状态。

设计：

Image

  ```text
  GatewayMediaProvider.generateImage
    1. route
    2. 调上游
    3. 根据 response + request 创建 media span
    4. 计算并累计 cost
    5. 返回上游 response
  ```

Video

  ```text
  GatewayMediaProvider.generateVideo
    1. route
    2. 调上游 submit
    3. 创建 MediaJob
    4. 创建 pending media span
    5. 若 response 中有可计费 usage：立即结算
    6. 返回 Gateway video handle
  ```

  ```text
  GatewayMediaProvider.getVideoStatus
    1. 调上游查询状态
    2. 更新 MediaJob 状态
    3. 第一次 completed：
         - 获取可用 usage（submit response stored usage 或 status response usage）
         - 计算 cost
         - 更新 media span
         - 原子累计 trace
    4. 返回状态
  ```

4.3 Tool result stats

工具结果也附带可展示的 usage，便于 agent event / debug UI 消费：

GenerateImageTool

  ```text
  media_model
  media_image_count
  media_total_tokens
  media_cost_usd
  ```

GenerateVideoTool

提交时：

  ```text
  video_id
  media_model
  ```

完成时可由状态接口返回：

  ```text
  media_video_seconds
  media_total_tokens
  media_cost_usd
  ```

这不是计费权威数据；权威数据以 server 的 Span / MediaJob 为准。

────────────────────────────────────────────────────────────────

5. Trace Detail UI

现有 trace detail 读取 Span，因此扩展 span view/API 即可。

展示策略：

- operation_type = image_generation：
    - 标识：Image Generation；
    - Model# Tech Design：Media Generation Trace Cost & Admin Analytics

1. 目标

让以下工具产生的调用在 Trace 中可见，并能纳入 Admin Dashboard 的模型统计及趋势：

- caption_image：继续作为 LLM / multimodal token 调用统计。
- generate_image：记录图片生成 usage、数量和 cost。
- generate_video：记录视频生成 usage、时长和 cost。
- Admin Dashboard：
    - 模型维度包含图片、视频模型；
    - 总览、模型列表、趋势图均包含 media 调用；
    - 可以区分 text / image / video 类型，避免用户把“张数/秒数”误解成 LLM token。

本设计只对新产生的调用生效。历史 media 任务没有足够的 usage / pricing snapshot，因此不做自动回填。

────────────────────────────────────────────────────────────────

2. 现状与问题

2.1 Trace 数据的聚合单位是 Trace，而非 Span

目前 Trace 在 ingest 时将每个 Span 的以下字段汇总：

  ```text
  input_tokens
  output_tokens
  total_tokens
  cached_tokens
  cost_usd
  ```

Admin Analytics 的 realtime 数据源为 traces；history 数据源为按 Trace 聚合的 analytics_daily_stats。

因此，若想让 dashboard 统计 media 模型，至少需要保证：

1. 图片/视频调用会产生 Span；
2. Span 归属到当前 Trace；
3. Span 的 cost 能增加到 Trace cost_usd；
4. Trace 能按模型/类型拆分，否则一个 Trace 中混合多个模型时，按 trace.model 分组会错误归属。

2.2 不能只把图片/视频费用加到 Trace

如果仅执行：

  ```text
  Trace.cost_usd += mediaCost
  Trace.total_tokens += mediaTokens
  ```

那么：

- Trace detail 能显示总 cost；
- global dashboard 总 cost 也能正确；

但 Admin Dashboard 的“按模型”会把整条 Trace 的总费用归属到 Trace 的主要 LLM model，而不是实际的 image/video model。一个 agent run 同时调用 GPT 和 image/video model 时，模型排行会失真。

所以 media analytics 的统计粒度必须从 Trace 提升到 Span。

────────────────────────────────────────────────────────────────

3. 总体方案

新增统一的 Media Generation telemetry pipeline：

  ```text
  GenerateImageTool / GenerateVideoTool
    → ContextualMediaProvider / GatewayMediaProvider
    → resolved gateway model + upstream response usage
    → MediaGenerationTracer
    → 记录 Media Generation Span
    → 更新所属 Trace totals
    → Analytics 从 Span 聚合模型维度和趋势
  ```

3.1 设计原则

1. Span 是成本明细的权威记录
    - 每次 image/video 生成各生成一个 span。
    - span 保存 usage、billing units、price snapshot 和 cost_usd。

2. Trace 是展示和总览聚合
    - 将 media span cost / token-like usage 累加到 Trace。
    - Trace detail 可展示所有 span，因此可看见媒体调用的模型、用量和成本。

3. 模型分析从 Span 统计
    - Dashboard 的 model/provider 维度改用 spans，而不是 traces。
    - 一个 Trace 多模型调用时，各费用准确归属。
    - source/agent/user 维度仍可保留 Trace 聚合，或也逐步迁移到 Span 聚合。

4. 定价必须 snapshot
    - 调用完成时把最终使用的单价和来源写到 span。
    - 后续修改 Gateway Model 的价格，不改变历史调用成本。

5. token 与 media units 分开
    - 图片张数、视频秒数不是 token。
    - 即使上游返回 total_tokens，也单独存储 media units，避免 dashboard / trace UI 把生成 1 张图显示为 “1 token”。

────────────────────────────────────────────────────────────────

4. 数据模型

4.1 扩展 GatewayModelConfig

现有模型配置只有：

  ```text
  inputPricePer1MTokens
  outputPricePer1MTokens
  ```

增加 media pricing 字段：

  ```java
  @Field(name = "image_price_per_unit_usd")
  public Double imagePricePerUnitUsd;

  @Field(name = "video_price_per_second_usd")
  public Double videoPricePerSecondUsd;
  ```

语义：

┌────────────────────────┬───────────────────────────────┬────────────────────────────────────────────┐
│ 字段                   │ 使用场景                      │ 计费公式                                   │
├────────────────────────┼───────────────────────────────┼────────────────────────────────────────────┤
│ imagePricePerUnitUsd   │ IMAGE_GENERATION / IMAGE_EDIT │ image_count × image_price_per_unit_usd     │
│ videoPricePerSecondUsd │ VIDEO_GENERATION              │ video_seconds × video_price_per_second_usd │
│ inputPricePer1MTokens  │ LLM / multimodal caption      │ input token formula                        │
│ outputPricePer1MTokens │ LLM / multimodal caption      │ output token formula                       │
└────────────────────────┴───────────────────────────────┴────────────────────────────────────────────┘

同时 Gateway Model API 的 Request / View / discovery metadata 同步增加这些字段。

定价不足时的处理

- Image：没有 imagePricePerUnitUsd → cost_usd = null，cost_source = unavailable
- Video：没有 videoPricePerSecondUsd → cost_usd = null，cost_source = unavailable
- 不允许使用 LLM 的 input/output token 单价去猜 image/video 价格。

────────────────────────────────────────────────────────────────

4.2 扩展 Span

现有 Span 已支持：

  ```text
  model
  input_tokens
  output_tokens
  cached_tokens
  cost_usd
  cost_source
  pricing_model_id
  input/output_price_per_1m_tokens
  attributes
  ```

新增：

  ```java
  @Field(name = "operation")
  public String operation;
  // chat | image_generation | image_edit | video_generation

  @Field(name = "media_type")
  public String mediaType;
  // image | video

  @Field(name = "media_units")
  public Long mediaUnits;
  // 图片：image count；视频：seconds

  @Field(name = "media_unit_type")
  public String mediaUnitType;
  // image | second

  @Field(name = "media_price_per_unit_usd")
  public Double mediaPricePerUnitUsd;
  ```

示例：生成两张图片

  ```json
  {
    "type": "TOOL",
    "operation": "image_generation",
    "media_type": "image",
    "media_unit_type": "image",
    "media_units": 2,
    "model": "gpt-image-2",
    "cost_usd": 0.08,
    "cost_source": "gateway_model",
    "pricing_model_id": "gpt-image-2",
    "media_price_per_unit_usd": 0.04,
    "attributes": {
      "gen_ai.operation.name": "image_generation",
      "gen_ai.request.model": "gpt-image-2",
      "gen_ai.response.image_count": "2"
    }
  }
  ```

示例：10 秒视频

  ```json
  {
    "type": "TOOL",
    "operation": "video_generation",
    "media_type": "video",
    "media_unit_type": "second",
    "media_units": 10,
    "model": "sora-2",
    "cost_usd": 1.20,
    "cost_source": "gateway_model",
    "pricing_model_id": "sora-2",
    "media_price_per_unit_usd": 0.12,
    "attributes": {
      "gen_ai.operation.name": "video_generation",
      "gen_ai.request.model": "sora-2",
      "gen_ai.response.video_seconds": "10"
    }
  }
  ```

input_tokens / output_tokens：

- 若 upstream response 返回 usage.total_tokens，写入 input_tokens（或新增 total_tokens 后直接记录 total）。
- 由于 media provider 当前没有 prompt/output token 的一致语义，建议：
    - input_tokens = 0
    - output_tokens = 0
    - 在 attributes 写 gen_ai.usage.total_tokens；
    - media_units 是图片/视频 usage 的主要指标。

这样 token dashboard 不会虚构 input/output token 分布。

────────────────────────────────────────────────────────────────

4.3 扩展 Trace

Trace 维持现有 token/cost 总字段，不需要把图片张数和视频秒数塞进 total_tokens。

新增专门聚合字段：

  ```java
  @Field(name = "image_count")
  public Long imageCount;

  @Field(name = "video_seconds")
  public Long videoSeconds;
  ```

Trace detail header 可显示：

  ```text
  Tokens: 18,241
  Images generated: 4
  Video generated: 20s
  Total cost: $x.xx
  ```

Trace 的 cost_usd 累加 LLM + image + video 的 cost。

────────────────────────────────────────────────────────────────

4.4 新增 span 级 analytics daily stats

现有 AnalyticsDailyStats 是 Trace 聚合，无法正确实现多模型归属。建议新增 collection：

  ```text
  analytics_span_daily_stats
  ```

维度：

  ```text
  date
  operation
  media_type
  user_id
  agent_id / agent_name
  source
  model
  provider_id / provider_name
  ```

指标：

  ```text
  input_tokens
  output_tokens
  total_tokens
  cached_tokens
  image_count
  video_seconds
  cost_usd
  call_count
  ```

这样 Dashboard 可以支持：

- 模型：gpt-5.6-terra、gpt-image-2、sora-2 分开；
- 类型：chat / image generation / video generation；
- 图片模型按 images count；
- 视频模型按 generated seconds；
- cost / calls 趋势；
- provider 统计也正确。

现有 analytics_daily_stats 保持不变，避免扩大现有 Trace 查询与存储结构的改动范围；新的 Admin Dashboard API 使用 span analytics collection。

────────────────────────────────────────────────────────────────

5. 调用链与 Trace 关联

5.1 现有问题

GenerateImageTool 和 GenerateVideoTool 从 ExecutionContext 取得 MediaProvider。
当前 ContextualMediaProvider 只携带：

  ```java
  MediaJobOwner(userId, sessionId, agentRunId)
  ```

但没有当前 OTel trace context / trace ID，因此 media provider 无法自动知道该把 span 关联到哪个 Trace。

5.2 方案：在工具层创建 telemetry span

保持 GatewayMediaProvider 专注路由和上游通信，在工具层处理 tracing：

  ```text
  GenerateImageTool.execute(...)
    → MediaGenerationTracer.traceImageGeneration(...)
    → provider.generateImage(...)
    → span completed / failed
  ```

`GenerateVideo# Tech Design：Media Generation Cost Tracing & Analytics

1. Goal

让 generate_image 和 generate_video 与 LLM / multimodal completion 一样：

1. 在 Trace 详情中有独立 Span，显示：
    - 请求模型、实际路由模型、provider；
    - media 类型（image / video）；
    - usage（token、图片数量、视频秒数）；
    - cost_usd、cost source、价格快照；
    - 请求参数摘要，例如 image size、quality、数量、video seconds。
2. Trace 总 token / total cost 自动包含 media generation。
3. Admin Dashboard 的：
    - 全局指标；
    - cost/token/calls 趋势；
    - By Model 排名与模型趋势；

   自动包含 image/video 模型，无需另建一套 dashboard。

────────────────────────────────────────────────────────────────

2. Current State and Gap

Existing capabilities

- LLM completion 已经通过 OpenTelemetry → OTLP → Span / Trace 记录 token 和 cost。
- Trace 中的 tokens/cost 会聚合到 AnalyticsDailyStats，因此 Dashboard 的 global、trend、model ranking 已有通用数据管道。
- MediaProvider response 已定义 usage：

  ```java
  public record Usage(
      Integer totalTokens,
      Integer imageCount,
      Integer videoSeconds
  ) {}
  ```

- Gateway 能解析 image/video 请求的逻辑模型，并路由为 provider + upstream model。
- 视频任务已有 media_jobs，并与 user/session/agent-run 关联。

Current gaps

- image/video 的 Usage 未进入 tracing。
- 没有 media operation Span。
- MediaJob 没有 usage、cost、price snapshot。
- Gateway Model 只有 input/output token price，无法覆盖按张、按秒的典型 media pricing。
- Dashboard 只按 trace 聚合；由于 media 不写入 trace，因此自然缺失。

────────────────────────────────────────────────────────────────

3. Design Principles

3.1 Reuse the existing Trace / Span / Analytics pipeline

不新建 media_analytics 或第二套 Dashboard API。

每次 media generation 都写入现有 spans collection，且同步原子增量更新所属 Trace：

  ```text
  media operation span
    → trace totals
    → TraceDailyMaintenanceService
    → analytics_daily_stats
    → existing admin dashboard
  ```

好处：

- 前端的 global/trend/by-model 已能自动使用；
- 历史/实时模式都一致；
- 日聚合不需要专门为 image/video 复制一套逻辑；
- Trace detail 天然呈现所有操作成本。

3.2 Cost 必须按生成时快照保存

和 LLM span 定价一致，span 记录：

  ```text
  cost_usd
  cost_source
  pricing_model_id
  pricing fields / pricing unit
  ```

后续修改 Gateway Model 价格，不应改变历史请求的成本。

3.3 使用显式 media pricing，不伪装成 token pricing

图片/视频计费通常不是单纯 input/output token：

- 图片：按 model、size、quality、数量或 edit 类型；
- 视频：按 model、resolution、生成秒数、quality 等。

因此应在 GatewayModelConfig 中增加 media 专用价格规则，而不是把每张图价格塞到 “per 1M tokens” 字段中。

────────────────────────────────────────────────────────────────

4. Proposed Data Model

4.1 GatewayModelConfig: Add mediaPricing

新增一个 concrete DTO / entity field，避免 Map<String, Object>（core-ng 不允许）。

  ```java
  public class MediaPricing {
      public Double imagePriceUsd;
      public Double videoPriceUsdPerSecond;
  }
  ```

建议字段：

  ```java
  @Field(name = "media_pricing")
  public MediaPricing mediaPricing;
  ```

初版含义：

┌────────────────────────┬──────────────────────────────┬──────────────────────────────────┐
│ Field                  │ Applies to                   │ Meaning                          │
├────────────────────────┼──────────────────────────────┼──────────────────────────────────┤
│ imagePriceUsd          │ IMAGE_GENERATION, IMAGE_EDIT │ 单张生成图片的价格（USD/image）  │
│ videoPriceUsdPerSecond │ VIDEO_GENERATION             │ 每生成秒视频的价格（USD/second） │
└────────────────────────┴──────────────────────────────┴──────────────────────────────────┘

计算：

  ```text
  image cost = imagePriceUsd × actual image count
  video cost = videoPriceUsdPerSecond × actual generated seconds
  ```

为什么初版不按 size / quality 建 pricing matrix

这会迅速变成可配置的多维规则系统，例如：

  ```text
  model × operation × size × quality × format × duration × edit mode
  ```

如果第一版就做通用矩阵，需要处理：

- rule priority / wildcard；
- 多个命中规则；
- schema 演进；
- UI 管理体验；
- provider 实际账单项与本地请求参数不一致；
- 上游返回 usage 后的 reconcile。

建议第一期先以模型级固定单价闭环 trace / dashboard 统计。后续若实际模型需要不同 size / quality 价格，可将 mediaPricing 扩展为 List<MediaPricingRule>，保留已有 snapshot 兼容性。

────────────────────────────────────────────────────────────────

4.2 Span: Add media dimensions

现有 input_tokens / output_tokens / total_tokens 继续保留。新增：

  ```java
  @Field(name = "media_type")
  public String mediaType;  // image | video

  @Field(name = "media_operation")
  public String mediaOperation; // image_generation | image_edit | video_generation

  @Field(name = "media_count")
  public Long mediaCount; // actual generated image count

  @Field(name = "media_seconds")
  public Long mediaSeconds; // billed/generated video duration

  @Field(name = "media_unit_price_usd")
  public Double mediaUnitPriceUsd;

  @Field(name = "media_pricing_unit")
  public String mediaPricingUnit; // image | second
  ```

使用 string 而不是 entity enum，避免 Mongo enum 注解与 API enum 双类型维护；这些字段目前无需作为强约束 domain enum 暴露。

已有字段继续用于价格审计：

  ```text
  cost_usd
  cost_source
  pricing_model_id
  ```

Token semantics

- 若上游返回 usage.totalTokens：
  ```text
  input_tokens = totalTokens
  output_tokens = 0
  total_tokens = totalTokens
  ```
  因为当前 media usage 不区分 prompt / completion token。

- 若无 token usage：
  ```text
  input_tokens = 0
  output_tokens = 0
  total_tokens = 0
  ```
  但仍可有正确的 image/video cost。

这样既不伪造 token，也保证 Dashboard cost 和 calls 正确。

────────────────────────────────────────────────────────────────

4.3 MediaJob: Persist submission pricing and final usage

给 video job 新增：

  ```java
  @Field(name = "usage_total_tokens")
  public Long usageTotalTokens;

  @Field(name = "usage_video_seconds")
  public Long usageVideoSeconds;

  @Field(name = "cost_usd")
  public Double costUsd;

  @Field(name = "cost_source")
  public String costSource;

  @Field(name = "pricing_model_id")
  public String pricingModelId;

  @Field(name = "media_unit_price_usd")
  public Double mediaUnitPriceUsd;

  @Field(name = "media_pricing_unit")
  public String mediaPricingUnit;

  @Field(name = "trace_id")
  public String traceId;

  @Field(name = "span_id")
  public String spanId;
  ```

视频是异步的，因此 submission 时和 completion 时的可用数据不同：

- 提交：模型、请求 seconds、路由信息已知；
- 完成：实际 duration/usage 可能才从上游 status 或 response 得到；
- 成本应在最终完成时落账，避免 failed/cancelled 任务被记为完整成功成本。

────────────────────────────────────────────────────────────────

5. Media Telemetry Flow

5.1 Introduce MediaTraceService

新增 server-side MediaTraceService，负责创建与更新 media spans，并复用 Trace 总额更新规则。

核心职责：

  ```java
  recordImageGeneration(...)
  recordVideoSubmission(...)
  completeVideoGeneration(...)
  failVideoGeneration(...)
  ```

该 service 不依赖 OpenTelemetry exporter；它直接写 server Span / Trace collections。

原因：

- MediaProvider 是同步/异步混合接口；
- video completion 发生在后续 polling 请求中，无法自然维持同一个 OTel span；
- server 已掌握 Gateway route、authenticated owner、job ID，更适合做可追溯账务记录。

Trace correlation

优先关联现有 agent trace：

  ```text
  AgentRun.traceId
  ```

回退：

- session 查询最近活跃 trace：不采用，可能把成本关联到错误会话；
- 无 active trace 时：创建独立 media Trace。

因此设计为：

1. 如果 agentRunId 存在且 AgentRun.traceId 已存在：
    - 使用对应 trace。
2. 如果没有可用 trace：
    - 创建 root trace：
      ```text
      name = "Image generation" / "Video generation"
      source = "media"
      type = API
      model = requested model
      user/session/run 信息从 `MediaJobOwner` 复制
      ```
    - 生成一个 media span 作为 root span。

对于 agent run 中 trace 尚未写入的竞态：AgentRun.traceId 不存在时创建独立 trace，而不猜测关联其他 trace。这是审计安全优先的取舍。

────────────────────────────────────────────────────────────────

5.2 Image generation lifecycle

  ```text
  GenerateImageTool
    → ContextualMediaProvider.generateImage
    → GatewayMediaProvider route + upstream call
    → receives ImageGenerationResponse
    → MediaTraceService.recordImageGeneration(...)
  ```

记录完成态 Span：

  ```text
  span.name             = "image_generation" 或 "image_edit"
  span.type             = TOOL
  span.model            = requested Gateway Model ID
  span.media_type       = "image"
  span.media_count      = response.usage.imageCount, fallback response.data.size, fallback request.n, fallback 1
  span.input_tokens     = response.usage.totalTokens, fallback 0
  span.total_tokens     = same
  span.cost_usd         = calculated / upstream reported cost
  span.status           = OK
  span.attributes       = safe request/result metadata
  ```

请求 metadata 应限制为结构化、安全摘要：

  ```text
  model, resolved_model, provider_id, n, size, quality,
  output_format, background, has_input_images, has_mask
  ```

不存储 prompt，因为 agent tool input 已由现有 trace/agent execution 链路处理；避免无谓复制潜在敏感数据。

失败时也记录 error Span，但默认：

  ```text
  cost_usd = null / 0
  cost_source = "unavailable"
  ```

除非上游明确回传发生费用（初版接口尚无此字段）。

────────────────────────────────────────────────────────────────

5.3 Video generation lifecycle

  ```text
  GenerateVideoTool
    → ContextualMediaProvider.generateVideo
    → GatewayMediaProvider routes + submits upstream
    → MediaJobService.createVideoJob
    → MediaTraceService.recordVideoSubmission
    → returns pending tool call
  ```

提交阶段创建 Span，状态标记为 pending/processing：

  ```text
  span.name = "video_generation"
  span.status = OK (transport submission succeeded)
  span.attributes.media_state = "submitted"
  span.cost_usd = null
  ```

MediaJob 存下 traceId + spanId，便于以后准确更新该 Span。

在 polling 中：

  ```text
  GatewayMediaProvider.getVideoStatus