# 媒体生成成本记录 — 最小可落地版设计(v1)

> 状态:已实施(2026-08-29,实施偏差见 §9)
> 日期:2026-08-29
> 关联:`docs/cn/media-generation-trace-cost.md`(完整版设计稿,本文档是它的裁剪落地版;两者冲突时以本文档为准)
> 目标读者:实施本方案的工程师/模型。所有文件路径、行号以 master 分支当前代码为准,行号仅作定位参考,以代码实际内容为准。

---

## 1. 背景与目标

### 1.1 现状(已核实)

- **LLM 文本调用已有完整成本管道**:`LLMTracer` / `IngestService.applyCost()` 把 token 和 `cost_usd` 写入 `spans`/`traces`,`ModelPricingService` 按「Gateway Model 显式定价 > 上游上报 > catalog 估算」三级定价,有日汇总和成本告警。**本方案不动这条管道,只复用它的定价思路。**
- **图像/视频生成完全没记成本**:
  - `GenerateImageTool.java:121`、`GenerateVideoTool.java:191` 调用后把 `response.usage()` 直接丢弃;
  - `GatewayMediaProvider`(`core-ai-server/src/main/java/ai/core/server/gateway/GatewayMediaProvider.java`)原样透传,不落库;
  - `MediaJob`(`core-ai-server/src/main/java/ai/core/server/domain/MediaJob.java`,集合 `media_jobs`)没有任何 cost/usage 字段;
  - `GatewayModelConfig` 只有 token 单价,没有按张/按秒的媒体单价字段。
- **Drama 预算体系只有估算**:`DramaRenderBudget.settleCompleted()`(`drama/cost/DramaRenderBudget.java:43`)把预估金额当实际金额结算(`settle(seriesId, reserved, reserved)`),真实成本从未回填。
- **上游其实给了计费数据,但全被丢掉**:
  - KIE `GET /api/v1/jobs/recordInfo` 响应里有 `creditsConsumed`(实际扣除积分),`KieMediaProvider.getVideoStatus()` 每次轮询都拿到又扔掉;
  - OpenAI gpt-image 系列响应 `usage` 里有 `input_tokens` / `output_tokens` / `input_tokens_details.{text_tokens, image_tokens}` 明细,目前只取了 `total_tokens`;
  - LiteLLM proxy 会在响应头 `x-litellm-response-cost` 里直接报美元成本;
  - 本地价目表 `core-ai/src/main/resources/model_prices_and_context_window.json`(LiteLLM 格式)**已含媒体单价**:`output_cost_per_image`(生图按张)、`output_cost_per_second`(Veo 按秒,如 `gemini/veo-3.1-generate-001` = 0.4)、`input_cost_per_image_token` / `output_cost_per_image_token`(gpt-image-2 按 token),但 `LLMModelContextRegistry` 只加载了 3 个 token 单价字段。

### 1.2 目标

1. 每一次图像/视频生成,在 `media_jobs` 上留下 `cost_usd`(及来源、用量单位),五条成本来源全覆盖:
   - KIE:上游实报(`creditsConsumed` × 可配汇率);
   - LiteLLM:上游实报(`x-litellm-response-cost` 响应头);
   - gpt-image 系列:token 明细 × catalog token 单价(精确);
   - Google Veo(Vertex Interactions):请求秒数 × catalog 每秒单价;
   - Gemini/其他生图:张数 × catalog 每张单价。
2. Drama 结算改用真实成本(拿不到真实值时回退预估值,行为不变)。
3. Gateway Model 管理页可显式配置媒体单价,覆盖 catalog(与 LLM 定价优先级一致)。

### 1.3 非目标(v1 明确不做)

- 不做 media span / tracing 接入,不改 `Span`、`Trace`、日汇总、成本告警(完整版设计稿的 §媒体 tracing 部分,留待 v2)。
- 不做音频(TTS/ASR)计量(`AudioProvider` SPI 无 usage 概念,单独立项)。
- 不做 KIE 余额(`GET /api/v1/chat/credit`)的周期性采集与告警。
- 不做 `drama_cost_rates` 的自动回归校准(仅保证真实值落库,校准后续人工/脚本做)。
- 不改 `core-ai-assembly-runtime`(纯 ffmpeg 编排,无计费概念)。

---

## 2. 总体数据流

```
                    ┌─ generateImage(同步) ──► 立即结算,写 media_jobs(image 行,state=completed)
调用方(工具/drama) ─┤
                    └─ generateVideo(异步) ──► createVideoJob 记 requested_seconds
                                                    │
                              轮询 getVideoStatus ──┤ 状态转 completed 的那一次(幂等点)
                                                    ▼
                                          MediaCostSettler.settle(job, statusResponse)
                                                    │  定价优先级:
                                                    │  gateway_model 显式媒体价
                                                    │    > 上游实报(KIE credits / LiteLLM header)
                                                    │    > catalog(model_prices json)
                                                    ▼
                                     media_jobs.{cost_usd, cost_source, media_units, ...}
                                                    │
                        drama harvestClip ──► settleCompleted 查 media_jobs 真实值结算
```

关键结算点只有两个:
- **图像**:同步返回,在 `GatewayMediaProvider.generateImage()` 返回前结算并落库;
- **视频**:`MediaJobService.updateVideoStatus()` 中 `completed` 状态**首次**出现时结算(该方法已有 `completed` 一次性判断,天然幂等,见 `MediaJobService.java:63`)。

---

## 3. 改动明细

### 3.1 `core-ai` 模块 — 领域对象扩展

#### 3.1.1 `core-ai/src/main/java/ai/core/media/domain/Usage.java`

现状:`record Usage(Integer totalTokens, Integer imageCount, Integer videoSeconds)`。

改为:

```java
public record Usage(Integer totalTokens, Integer imageCount, Integer videoSeconds,
                    Integer inputTokens, Integer outputTokens,
                    Integer inputTextTokens, Integer inputImageTokens,
                    Double upstreamCostUsd) {
    // 兼容旧的三参构造,避免大面积改调用方
    public Usage(Integer totalTokens, Integer imageCount, Integer videoSeconds) {
        this(totalTokens, imageCount, videoSeconds, null, null, null, null, null);
    }
}
```

说明:
- `inputTokens`/`outputTokens`/`inputTextTokens`/`inputImageTokens`:gpt-image 精确计价所需;
- `upstreamCostUsd`:LiteLLM 响应头等「上游直接报美元」的通道;
- 保留三参构造后,**现有调用方(下面列出的 4 处)编译不破**,只需按需改用全参构造。

现有构造点(需检查,按需升级为全参):
| 文件 | 行 | 处理 |
|---|---|---|
| `core-ai/src/main/java/ai/core/media/OpenAIImageMediaProvider.java` | 114 | 升级,见 3.3.1 |
| `core-ai/src/main/java/ai/core/media/GeminiImageMediaProvider.java` | 126 | 升级,见 3.3.4 |
| `core-ai/src/main/java/ai/core/llm/providers/LiteLLMMediaProvider.java` | 74, 98 | 升级,见 3.3.5 |
| 测试若干 | — | 同步修 |

#### 3.1.2 `core-ai/src/main/java/ai/core/media/domain/VideoStatusResponse.java`

现状:`record VideoStatusResponse(String id, String status, Integer progress, String error, Long completedAt)`。

改为(同样保留旧构造):

```java
public record VideoStatusResponse(String id, String status, Integer progress, String error, Long completedAt,
                                  Double creditsConsumed, Double upstreamCostUsd) {
    public VideoStatusResponse(String id, String status, Integer progress, String error, Long completedAt) {
        this(id, status, progress, error, completedAt, null, null);
    }
}
```

- `creditsConsumed`:KIE 积分(单位是「积分」,不是钱,换算见 3.4.2);
- `upstreamCostUsd`:LiteLLM 等直接报美元的通道。

现有构造点(用旧构造的不用动,列出以便核对):
`KieMediaProvider.java:132`(要升级)、`VertexGeminiOmniMediaProvider.java:85`、`LiteLLMMediaProvider.java:121`(要升级)、`GatewayMediaProvider.java:80/84`、`GatewayProxyService.java:360`、drama 侧 `GatewayRenderBackend` 的 poll(透传即可)。

#### 3.1.3 `core-ai/src/main/java/ai/core/llm/LLMModelContextRegistry.java` — 加载媒体单价

现状:`ModelInfo` 只有 token 三项单价 + peak multiplier(L76-88)。

改动:
1. `ModelInfo` record 增加 4 个字段(加在末尾):
   - `Double outputCostPerImage`(json 字段 `output_cost_per_image`)
   - `Double outputCostPerSecond`(json 字段 `output_cost_per_second`,**只在 `mode` 为 `video_generation` 或 `image_generation` 时读取**——catalog 里 bedrock 一批 chat 模型也有该字段,是按秒的算力计费,不能误用)
   - `Double inputCostPerImageToken`(json 字段 `input_cost_per_image_token`)
   - `Double outputCostPerImageToken`(json 字段 `output_cost_per_image_token`)
2. 新增两个估价方法(参照现有 `estimateCostUsd` 风格,查不到返回 `null`,**绝不抛异常**):

```java
/**
 * 生图估价。优先 token 明细(gpt-image 系):
 *   textInput×input_cost_per_token + imageInput×input_cost_per_image_token + output×output_cost_per_image_token
 * token 明细不可用或单价缺失时回退按张:imageCount × output_cost_per_image。
 * 两者都算不出返回 null。
 */
public Double estimateImageCostUsd(String modelName, Integer inputTextTokens, Integer inputImageTokens,
                                   Integer outputTokens, Integer imageCount)

/** 生视频估价:seconds × output_cost_per_second;模型或单价缺失返回 null。 */
public Double estimateVideoCostUsd(String modelName, Integer seconds)
```

3. **模型名匹配规则**:catalog 的 key 带 provider 前缀(`gemini/veo-3.1-generate-001`、`azure/gpt-image-2`)。查找顺序:精确匹配 → 常见前缀轮询(`gemini/`、`azure/`、`vertex_ai/`、`openai/` + 裸名)。若现有 `getModelInfo` 已有类似逻辑则复用;没有就在这两个新方法内做前缀轮询,不改动 `getModelInfo` 本身的行为。

### 3.2 `core-ai-server` — 数据模型与定价/结算服务

#### 3.2.1 `MediaJob` 扩展(`core-ai-server/src/main/java/ai/core/server/domain/MediaJob.java`)

新增字段(Mongo 无 schema,老数据自然为 null,无需数据迁移):

```java
@Field(name = "media_type")          public String mediaType;        // "image" | "video"
@Field(name = "requested_seconds")   public Integer requestedSeconds; // 视频:提交时请求的秒数
@Field(name = "media_units")         public Double mediaUnits;        // 计价用量:秒数 / 张数 / 积分数
@Field(name = "media_unit_type")     public String mediaUnitType;     // "second" | "image" | "credit" | "token"
@Field(name = "credits_consumed")    public Double creditsConsumed;   // KIE 原始积分(留痕,即使没配汇率也先存)
@Field(name = "cost_usd")            public Double costUsd;
@Field(name = "cost_source")         public String costSource;        // "gateway_model" | "upstream" | "model_catalog" | "unavailable"
@Field(name = "pricing_model_id")    public String pricingModelId;    // 命中的定价条目(gateway model_id 或 catalog key)
```

存量视频 job 的 `media_type` 为 null,读取时按 "video" 理解即可,不回填。

#### 3.2.2 `GatewayModelConfig` 扩展(`core-ai-server/src/main/java/ai/core/server/domain/GatewayModelConfig.java`)

在现有 token 价字段(L74-92)后新增:

```java
@Field(name = "image_price_per_image")    public Double imagePricePerImage;   // USD/张
@Field(name = "video_price_per_second")   public Double videoPricePerSecond;  // USD/秒
```

同步改动(照抄现有 token 价字段的走线方式):
- API view/request 对象:搜 `inputPricePer1MTokens` 在 `core-ai-api/src/main/java/ai/core/api/server/` 下的出现位置(model 的 view/upsert request),同样加这两个字段;
- `GatewayModelService` 的 upsert/映射处同步透传;
- 前端 `core-ai-frontend` 的 gateway model 编辑表单可后补,v1 允许仅 API 可配,**不阻塞本方案**。

#### 3.2.3 `GatewayProviderConfig` 扩展(KIE 积分汇率)

`core-ai-server/src/main/java/ai/core/server/domain/GatewayProviderConfig.java` 新增:

```java
// 每积分兑美元(如 KIE)。null = 未配置,只记 credits_consumed 不折算金额
@Field(name = "credit_usd_rate")
public Double creditUsdRate;
```

同步:provider 的 API view/upsert(参照 `timeout_seconds` 等现有可选字段的走线);前端表单可后补。
汇率值不要写死在代码里(KIE 积分单价随充值档位浮动),来源标注在管理端由人工填。

#### 3.2.4 新类 `MediaPricingService`(`core-ai-server/src/main/java/ai/core/server/trace/service/MediaPricingService.java`)

参照同目录 `ModelPricingService` 的结构与命名,定价优先级一致:

```java
public class MediaPricingService {
    @Inject MongoCollection<GatewayModelConfig> gatewayModelCollection;

    public record MediaPrice(Double costUsd, String source, String pricingModelId, Double units, String unitType) {
        static MediaPrice unavailable() { return new MediaPrice(null, "unavailable", null, null, null); }
    }
    // units/unitType 是实施时新增字段(设计初稿只有前三个):为 media_jobs.media_units/media_unit_type 提供来源
    //   gateway_model/catalog 视频:units=seconds、"second"
    //   gateway_model/catalog 图像按张:units=imageCount、"image";catalog token 路径:units=outputTokens、"token"
    //   upstream credits:units=creditsConsumed、"credit";upstream 美元实报(响应头):units=null(未知)

    /**
     * 生图定价。model 传网关对外模型名(requestedModel),catalogModel 传上游真实模型名(resolvedModel),
     * 两者都尝试查 catalog(先 resolved 后 requested)。
     * 优先级:gateway_model.image_price_per_image × imageCount
     *   > usage.upstreamCostUsd()(source="upstream")
     *   > LLMModelContextRegistry.estimateImageCostUsd(...)(source="model_catalog")
     */
    public MediaPrice resolveImage(String requestedModel, String resolvedModel, Usage usage, int imageCount)

    /**
     * 生视频定价。
     * 优先级:gateway_model.video_price_per_second × seconds
     *   > upstreamCostUsd(LiteLLM header 等,source="upstream")
     *   > creditsConsumed × provider.creditUsdRate(source="upstream")
     *   > estimateVideoCostUsd(resolvedModel 或 requestedModel, seconds)(source="model_catalog")
     * seconds 取 MediaJob.requestedSeconds;为 null 时 catalog 途径不可用(返回 unavailable,除非上游实报)。
     */
    public MediaPrice resolveVideo(String requestedModel, String resolvedModel, Integer seconds,
                                   Double creditsConsumed, Double creditUsdRate, Double upstreamCostUsd)
}
```

gateway_model 查找按 `model_id = requestedModel`(与 `ModelPricingService.gatewayModel()` 一致)。

注册:在 `GatewayModule.configureGateway()` 里 `bind(MediaPricingService.class)` + `bind(MediaCostSettler.class)`,**必须在 `bind(MediaJobService.class)` 之前**——core-ng `bind(X)` 即时解析 `@Inject`,而 GatewayModule 先于 TraceModule(ModelPricingService 所在)加载,照抄 TraceModule 会导致启动失败(实施偏差 B1)。

#### 3.2.5 `MediaJobService` — 落库与视频结算

`core-ai-server/src/main/java/ai/core/server/gateway/MediaJobService.java` 改动:

1. **`createVideoJob` 增加秒数与类型**:签名加一个 `Integer requestedSeconds` 参数(或新增重载,避免破坏现有 3 个调用点:`GatewayMediaProvider.generateVideo` L69、`GatewayProxyService.gatewayVideoResponse` L369、以及测试)。写入 `job.mediaType = "video"; job.requestedSeconds = requestedSeconds;`。
   - `GatewayMediaProvider.generateVideo`:秒数取 `request.seconds()`;
   - `GatewayProxyService.gatewayVideoResponse`(raw 代理路径):秒数从请求体 `seconds` 字段取(`prepare()` 阶段的 requestBody 里有,需要把它带到 `gatewayVideoResponse`,最简单的办法是从 `parseBody(upstream)` 拿不到时再从 call 里补——实际实现:在 `proxy()` 里 VIDEO_GENERATION 分支解析原始请求体的 `"seconds"` 传下去)。
2. **`updateVideoStatus` 内做视频成本结算**(唯一幂等点):

```java
public void updateVideoStatus(MediaJob job, VideoStatusResponse status) {
    ...
    var completed = job.completedAt == null && "completed".equals(state);
    ...
    if (status.creditsConsumed() != null) {
        updates = Updates.combine(updates, Updates.set("credits_consumed", status.creditsConsumed()));
    }
    if (completed) {
        updates = Updates.combine(updates, Updates.set("completed_at", now));
        var price = costSettler.settleVideo(job, status);   // 见下
        if (price.costUsd() != null) {
            updates = Updates.combine(updates,
                Updates.set("cost_usd", price.costUsd()),
                Updates.set("cost_source", price.source()),
                Updates.set("pricing_model_id", price.pricingModelId()),
                Updates.set("media_units", units),           // 秒数或积分数
                Updates.set("media_unit_type", unitType));
        }
    }
    ...
}
```

其中 `costSettler` 是注入的 `MediaCostSettler`(下节)。**结算失败(任何异常)只 WARN 日志,绝不影响状态更新主流程。**

3. 新增 `createImageJob(...)`:图像是同步的,直接创建一条 `state="completed"`、`mediaType="image"`、带 cost 字段的完整记录(一次 insert,无 update)。

#### 3.2.6 新类 `MediaCostSettler`(`core-ai-server/src/main/java/ai/core/server/gateway/MediaCostSettler.java`)

职责:从 job + status/usage 收集定价输入,调 `MediaPricingService`,返回 `MediaPrice`。需要拿 provider 的 `creditUsdRate`——通过 `GatewayRoutingEngine.jobProvider(job.providerId)` 取 `GatewayProviderConfig`(`GatewayMediaProvider.java:82` 已有同样用法)。

```java
public class MediaCostSettler {
    @Inject MediaPricingService pricingService;
    @Inject GatewayRoutingEngine routingEngine;   // 或注入取 provider 的等价途径

    public MediaPricingService.MediaPrice settleVideo(MediaJob job, VideoStatusResponse status) { ... }
    public MediaPricingService.MediaPrice settleImage(String requestedModel, String resolvedModel, Usage usage, int imageCount) { ... }
}
```

#### 3.2.7 `GatewayMediaProvider.generateImage` — 图像结算点

`GatewayMediaProvider.java:46-53` 改为:

```java
public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
    ...
    var response = upstream.generateImage(rewritten);
    try {
        var price = costSettler.settleImage(request.model(), resolved.upstreamModel(),
                response.usage(), response.data() == null ? 0 : response.data().size());
        mediaJobService.createImageJob(owner /* 见下 */, resolved, request.model(), price, response.usage());
    } catch (Exception e) {
        LOGGER.warn("image cost recording failed, model={}", request.model(), e);
    }
    return response;
}
```

注意 owner:`generateImage` 现在没有 owner 参数。参照 `generateVideo` 的做法加一个包内可见的 `generateImage(request, owner)` 重载,`ContextualMediaProvider.generateImage`(`ContextualMediaProvider.java:23`)改为调带 owner 的版本;接口方法默认 `MediaJobOwner.UNKNOWN`。

raw 代理路径(`GatewayProxyService.proxyImageGenerations`/`proxyImageEdits`)v1 不落 media_jobs(它有 span 记录 usage,金额留给 v2 tracing 方案),在文档里注明即可。

### 3.3 各 Provider 接入

#### 3.3.1 KIE(`core-ai/src/main/java/ai/core/media/KieMediaProvider.java`)— 最高优先级

`getVideoStatus()`(L129-133)升级:从 `recordInfo` 的 `data` 里多取 `creditsConsumed`(数值型,官方文档 [Get Task Details](https://docs.kie.ai/market/common/get-task-detail) 确认字段名):

```java
public VideoStatusResponse getVideoStatus(String videoId) {
    var task = taskData(execute(HTTPMethod.GET, recordInfoUrl(videoId), null, "video status"));
    var state = stringValue(task, "state");
    return new VideoStatusResponse(videoId, normalizeStatus(state), intValue(task, "progress"),
            stringValue(task, "failMsg"), null,
            doubleValue(task, "creditsConsumed"), null);
}

private Double doubleValue(Map<String, Object> map, String name) {
    var value = map.get(name);
    return value instanceof Number number ? number.doubleValue() : null;
}
```

注意:KIE 任务未完成时 `creditsConsumed` 可能缺失/为 0,原样透传 null/0,由结算层在 completed 时取最后一次的值。

#### 3.3.2 Google 视频 Veo(`VertexGeminiOmniMediaProvider.java`)

响应不含金额,**不改该 provider**。计价依赖 `MediaJob.requestedSeconds`(3.2.5 已记)× catalog `output_cost_per_second`。
- catalog key 是 `gemini/veo-3.1-generate-001` 这类;`resolvedModel` 是 Vertex 的模型名(如 `veo-3.1-generate-001`),3.1.3 的前缀轮询要能把 `veo-3.1-generate-001` 命中 `gemini/veo-3.1-generate-001`。
- `request.seconds()` 为 null 时(用户没传时长):Veo 默认 8 秒——**不要在代码里猜**,此时 catalog 途径返回 unavailable,靠管理员在 gateway_model 配 `video_price_per_second` 也无法算(缺秒数)。做法:`GatewayMediaProvider.generateVideo` 里 seconds 为 null 时记 null,结算 unavailable;同时在本文档遗留问题里注明「建议 drama/工具侧总是显式传 seconds」(drama 的 `submitClip` 已传,见 `DramaProductionService.java:249`)。

#### 3.3.3 OpenAI gpt-image(`OpenAIImageMediaProvider.java`)

`parseResponse()`(L108-116)升级 usage 解析:

```java
var usageMap = (Map<String, Object>) response.get("usage");
Usage usage = null;
if (usageMap != null) {
    var details = usageMap.get("input_tokens_details") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.<String, Object>of();
    usage = new Usage(
        intValue(usageMap, "total_tokens"), intValue(usageMap, "image_count"), null,
        intValue(usageMap, "input_tokens"), intValue(usageMap, "output_tokens"),
        intValue(details, "text_tokens"), intValue(details, "image_tokens"),
        null);
}
```

计价在 `MediaPricingService.resolveImage` 里走 `estimateImageCostUsd(model, inputTextTokens, inputImageTokens, outputTokens, imageCount)`:
- token 明细齐全 → 按 token 精确算(gpt-image-2 catalog 条目:`input_cost_per_token`=5e-6、`input_cost_per_image_token`=8e-6、`output_cost_per_image_token`=3e-5,见 json `azure/gpt-image-2`);
- 明细缺失 → 回退 `imageCount × output_cost_per_image`(gpt-image-1 有按 质量×尺寸 的 `azure/{quality}/{size}/gpt-image-1` flat 条目,v1 不做质量/尺寸匹配,匹配不上就 unavailable,可由 gateway_model 显式价兜底)。

#### 3.3.4 Gemini 生图(`GeminiImageMediaProvider.java` / `VertexGeminiImageMediaProvider.java`)

- `GeminiImageMediaProvider.usage()`(L122-126)已取 `usageMetadata.totalTokenCount`,补取 `promptTokenCount`/`candidatesTokenCount` 填入 `inputTokens`/`outputTokens`(usageMetadata 标准字段);
- `VertexGeminiImageMediaProvider.generateImage` 实际**委托** `GeminiImageMediaProvider`(构造 `new GeminiImageMediaProvider(baseUrl, token, "Authorization")`),usage 由委托者解析,无需单独改动(实施偏差 B2,设计初稿「usage 恒 null」不成立);
- 计价走「张数 × `output_cost_per_image`」(imageCount = 返回 images 条数,不依赖上游 usage)。

#### 3.3.5 LiteLLM(`core-ai/src/main/java/ai/core/llm/providers/LiteLLMMediaProvider.java`)

三处读响应头 `x-litellm-response-cost`(`core.framework.http.HTTPResponse.headers`,注意大小写不敏感处理——先按小写取,取不到再遍历 equalsIgnoreCase):
- `generateImage`(L57 后):塞进 `Usage.upstreamCostUsd`;
- `generateVideo`(L91 后):塞进 `Usage.upstreamCostUsd`(挂在 `VideoGenerationResponse.usage`;但视频结算点在 status,所以**同时**在 `getVideoStatus`(L115 后)也读一次响应头塞进 `VideoStatusResponse.upstreamCostUsd`——LiteLLM 对 GET /videos/{id} 不一定返回该头,读不到就是 null,无妨);
- 头的值是十进制字符串,`Double.parseDouble`,解析失败置 null 并 debug 日志。

### 3.4 Drama 真实成本结算

#### 3.4.1 `DramaRenderBudget.settleCompleted`(`drama/cost/DramaRenderBudget.java:40-46`)

```java
public void settleCompleted(DramaRender render, Map<String, Object> payload) {
    var reserved = reservedCents(payload);
    var actual = actualCents(render).orElse(reserved);   // 拿不到真实值 → 维持现状(预估即实际)
    if (reserved <= 0 && actual <= 0) return;
    costService.settle(render.seriesId, reserved, actual);
    render.costCents = actual;
    renderCollection.replace(render);
}

private Optional<Long> actualCents(DramaRender render) {
    // render.mediaJobId 是 gateway 的视频句柄(GatewayVideoHandle.encode(job.id))
    // 解码后查 media_jobs.cost_usd,× 汇率转分
    ...
}
```

实现要点:
- `render.mediaJobId` 在 `DramaProductionService.java:251` 写入,值是 `GatewayRenderBackend.submitClip` 返回的 gateway videoId(即 `GatewayVideoHandle.encode(job.id)`),用 `GatewayVideoHandle.decode()` 还原 job id 查 `media_jobs`;decode 失败(非 gateway 句柄)返回 empty。`GatewayVideoHandle` 原为包私有,已改为 public(实施偏差 B3);
- 依赖注入 `MongoCollection<MediaJob>`(或复用 `MediaJobService`,注意模块依赖方向,`drama` 依赖 `gateway` 包在 server 内是允许的,`GatewayRenderBackend` 已 import gateway 类);
- **USD → 分换算**:drama 预算单位是「分」但币种语义未固定。新增 sys.properties 配置 `drama.cost.centsPerUsd`,默认 `100`(按美元分记);若团队按人民币分记预算,运维改成 ~`720`。读取走线:`DramaModule` 用 `property("drama.cost.centsPerUsd")` 读取后经构造参数传入 `DramaRenderBudget`(该模块原无 property 读取先例,core-ng `Module.property` 可读 sys.properties);
- 时序问题:`harvestClip`(`DramaProductionService.java:263-285`)在 `downloadClip` 之后调 `settleCompleted`,而下载前必然轮询到过 completed(`GatewayMediaProvider.downloadVideo` 不查状态但 drama 的 poll 循环查),`media_jobs.cost_usd` 此时通常已写入;若仍为 null(如 KIE credits 晚到),回退预估值——**不做延迟补偿**,v1 接受。

#### 3.4.2 KIE 积分汇率

`GatewayProviderConfig.creditUsdRate`(3.2.3)由管理员在 provider 设置页填写(v1 API 可配即可)。未配置时:`media_jobs.credits_consumed` 照记,`cost_usd` 走 catalog 途径(KIE 的模型名如 `bytedance/seedance-1-pro` 在 catalog 里多半查不到 → unavailable),不报错。

---

## 4. 边界与约束(实施时必须遵守)

1. **成本记录永远不能让业务失败**:所有结算/落库包 try-catch,失败 WARN,不抛出。
2. **幂等**:视频只在 `completed` 首次转换时写 cost(`MediaJobService.updateVideoStatus` 的 `completed` 布尔已保证);图像一次 insert 无重入。
3. **不改变任何现有 record 的调用方语义**:`Usage`、`VideoStatusResponse` 用「保留旧构造 + 追加字段」的方式扩展,所有旧构造点零改动可编译。
4. **不动 LLM 文本成本管道**(`LLMTracer`、`IngestService`、`ModelPricingService` 一行都不改;`MediaPricingService` 是新文件)。
5. Mongo 字段全部可空,不写 SchemaMigration 数据回填;若要给 `media_jobs` 补查询索引(如按 `media_type`),新增 `SchemaMigrationVMediaJobCost`(参照 `SchemaMigrationVMediaJobIndexes.java` 的写法,version 取当天日期序号),并在 `SchemaMigrationManager` 注册处登记——v1 无新查询模式,**可不加索引**。
6. catalog json(`model_prices_and_context_window.json`)是 LiteLLM 上游同步来的,**不要手工编辑**;缺价的模型用 gateway_model 显式价兜底。

## 5. 测试计划

| 测试 | 覆盖 |
|---|---|
| `core-ai/src/test/java/ai/core/media/KieMediaProviderTest.java`(已有,扩展) | recordInfo 带 `creditsConsumed` → `VideoStatusResponse.creditsConsumed`;缺失时为 null |
| `OpenAIImageMediaProviderTest`(新建) | usage 明细解析:`input_tokens_details.{text_tokens,image_tokens}`;usage 缺失时全 null |
| `GeminiImageMediaProviderTest`(新建) | `usageMetadata.promptTokenCount/candidatesTokenCount` → input/output tokens(Vertex 路径经委托覆盖) |
| `LLMModelContextRegistryTest`(扩展) | `estimateImageCostUsd`:gpt-image-2 token 路径、按张回退(`aiml/dall-e-3`)、未知模型 null;`estimateVideoCostUsd`:裸名 `veo-3.1-generate-001` 前缀匹配命中 `gemini/veo-3.1-generate-001`;bedrock commitment chat 模型的 `output_cost_per_second` **不被**误用(mode 守卫) |
| `MediaPricingServiceTest`(新建,参照 `ModelPricingService` 相关测试) | 三级优先级:gateway 显式价 > upstream(header / credits×rate)> catalog > unavailable;秒数缺失时 catalog 途径 unavailable |
| `MediaJobServiceTest`(新建) | completed 首次转换写 cost;二次 update 不重写;结算抛异常不影响状态更新;credits_consumed 未完成也留痕;createImageJob 落库字段 |
| `DramaRenderBudgetTest`(新建,原设计写「扩展」但该测试此前不存在,`drama/cost/DramaCostServiceTest.java` 同目录) | settleCompleted:media_jobs 有 cost_usd → 按真实值结算(reserved 释放、spent 记实际);查不到/非 gateway 句柄 → 回退 reserved;`render.costCents` 写实际值;`centsPerUsd` 换算 |
| `MediaProviderAdapterFactoryTest`(已有,回归) | 不受影响 |

运行:`./gradlew :core-ai:test :core-ai-server:test`(Windows 下 `gradlew.bat`)。

## 6. 实施顺序(每步可独立提交、编译通过)

1. **[core-ai]** `Usage`、`VideoStatusResponse` 扩展(含旧构造保留)+ `LLMModelContextRegistry` 媒体单价加载与两个估价方法 + 测试。
2. **[core-ai]** KIE `creditsConsumed` 解析、OpenAI usage 明细解析、Gemini/Vertex usageMetadata 解析、LiteLLM 响应头解析 + 测试。
3. **[core-ai-server]** `MediaJob`/`GatewayModelConfig`/`GatewayProviderConfig` 字段 + API view 走线;`MediaPricingService` + `MediaCostSettler` + 注册 + 测试。
4. **[core-ai-server]** `MediaJobService`(createVideoJob 带秒数、updateVideoStatus 结算、createImageJob)+ `GatewayMediaProvider`(generateImage 落库、generateVideo 传秒数)+ `GatewayProxyService.gatewayVideoResponse` 传秒数 + 测试。
5. **[core-ai-server]** Drama:`sys.properties` 加 `drama.cost.centsPerUsd`、`DramaRenderBudget.settleCompleted` 真实值结算 + 测试。
6. (可选,不阻塞)前端:gateway model / provider 表单加媒体单价与积分汇率输入框。

## 7. 验收标准

1. 通过 gateway 用 KIE 生成一条视频,完成后 `media_jobs` 该记录有 `credits_consumed`;配置了 `credit_usd_rate` 时 `cost_usd = credits × rate`、`cost_source="upstream"`。
2. 用 Veo(Vertex Interactions)生成 8 秒视频,`media_jobs.cost_usd ≈ 8 × catalog 每秒价`、`cost_source="model_catalog"`、`media_units=8`、`media_unit_type="second"`。
3. 用 gpt-image 生成一张图,`media_jobs` 出现 `media_type="image"` 的完整记录,`cost_usd` 按 token 明细计算。
4. gateway_model 配了 `video_price_per_second` 的模型,`cost_source="gateway_model"` 且金额按显式价。
5. drama 渲染一个 shot 完成后,`drama_renders.cost_cents` 与 `drama_series.spent_cents` 反映真实成本(能查到时),预算闸门行为与之前一致。
6. 任何一家上游响应异常/字段缺失,生成流程本身不受影响(仅缺 cost 字段 + WARN 日志)。
7. 全量测试通过。

## 8. 遗留问题(记录,不在 v1 处理)

- raw 代理路径(`/v1/images/generations`、`/v1/videos` 直连)图像不落 media_jobs,金额进 span 的方案随 v2 tracing 一起做(完整版设计稿 §媒体 tracing)。
- `Usage.videoSeconds` 与 `MediaJob.requestedSeconds` 语义重叠,v2 统一。
- KIE 余额监控(`GET /api/v1/chat/credit` 定时采集 + 低余额告警,接口见 [Get Remaining Credits](https://docs.kie.ai/common-api/get-account-credits))。
- `drama_cost_rates` 用 `media_jobs` 真实值自动校准(设计稿 §10.3 R4)。
- 音频(Azure Speech TTS 按字符 / ASR 按秒)计量。
- gpt-image-1 按 质量×尺寸 flat 价的精确匹配(需要把 quality/size 带进定价输入)。

---

## 9. 实施偏差与澄清(2026-08-29 实施时记录)

- **B1 MediaPricingService 绑定位置**:设计初稿说「照 ModelPricingService 在 TraceModule 注册」,实际绑在 `GatewayModule`,且必须在 `bind(MediaJobService.class)` 之前。原因:core-ng `bind(X)` 即时解析 `@Inject`,`MediaJobService → MediaCostSettler → MediaPricingService` 的注入链若等 TraceModule(加载顺序晚于 GatewayModule)会启动失败。
- **B2 VertexGeminiImageMediaProvider 未改**:初稿称其「usage 恒 null」,实际它把 `generateImage` 委托给 `GeminiImageMediaProvider`(L25),usageMetadata 解析改在委托者即覆盖两条路径。
- **B3 `GatewayVideoHandle` 由包私有改为 public**:drama 包(`DramaRenderBudget`)需要 `decode()` 还原 job id,跨包可见性必须放开。
- **B4 `MediaPrice` 增加 `units`/`unitType` 字段**:§3.2.5 伪代码要写 `media_units`/`media_unit_type` 但初稿 `MediaPrice` 没有对应字段,实施时补上并由各定价源填充(token 路径 = outputTokens/"token",按张 = imageCount/"image",按秒 = seconds/"second",credits = creditsConsumed/"credit",美元实报头 = null)。
- **B5 `DramaRenderBudgetTest` 为新建而非扩展**:该测试文件实施前不存在。
- **B6 测试用例参考数据已核对 catalog**:`gemini/veo-3.1-generate-001`(0.4/s)、`azure/gpt-image-2` 与裸 `gpt-image-2`(token 价 5e-6/8e-6/3e-5,无 per-image 价)、`aiml/dall-e-3`(0.052/张)、`bedrock/*/1-month-commitment/cohere.command-light-text-v14`(chat 模式带 output_cost_per_second,须被 mode 守卫排除)均存在于 `model_prices_and_context_window.json`;裸名 `gpt-image-2` 存在,故解析该名时 `pricing_model_id` 记 `gpt-image-2` 而非 `azure/gpt-image-2`(精确匹配优先)。
