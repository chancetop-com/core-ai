# Desktop 媒体生成设计：generate_image 与 generate_video

> 状态：**已评审并实现**（2026-10-19；P0 + P1 + §4.4 会话归属；P2 的目录能力标记、任务取消、成本展示未做）。
> 目标版本：core-ai-desktop 下一版（随 engine 2.0.x 发布）；server 侧新增一个只读模型目录接口。
> 相关设计：[媒体引用与 media_id](../design/media-reference.md)、[媒体成本记录](design-media-cost-recording.md)、[Desktop 内置技能](design-desktop-builtin-skills.md)。

## 0.1 评审结论与落地差异

评审确认了四件事（连接派生、managed client、模型目录、产物闭环）和九条当前断点都成立；实现时做了下面几处调整：

- **§2.4 #2 的表述修正**：`GenerateImageTool.buildDescription` / `GenerateVideoTool.buildDescription` 在 core-ai 中已经存在（server 端 `ToolRefResolutionService` 已在用），缺的是 CLI 侧目录以及把描述注入工具 builder；实现只补这两处，没有重写描述生成。
- **会话归属提前到 P0/P1 一起做**（原 §4.4 列为 P2）。服务端 `MediaJobOwner` 本来就带 `sessionId`，控制器补一个 header 即可，成本远低于“把工具描述改成 account/login”；这样 `"last"` 与工具描述承诺一致。
- **`MediaModelHint` 增加 `displayName`**。目录响应要显示可读名称，而 `GatewayRoutingEngine.mediaModelHints()` 已经持有 `GatewayModelConfig.displayName`；让 hint 直接携带它，避免 CLI 再调一次 `availableModels()` 做关联。
- **`mediaModelCatalog` 在 app-server、interactive CLI、ACP 三处都创建**（`CliAppHelper.mediaModelCatalog`）。原 §6.4 只要求 app-server 传入、CLI/ACP 可为 null；但“登录后 CLI 也能直接生成”是 §1.1 的目标，三处共用同一工厂更一致。自定义端点不创建 catalog。
- **`media/get` 增加 `effectiveImageModel` / `effectiveVideoModel` / `imageBase` / `videoBase`**。原响应只有配置值，Settings 无法同时展示“Auto 时的实际模型”和自定义端点地址；新增字段是向后兼容的增量。`imageModel` / `videoModel` 取全局 `~/.core-ai/agent.properties` 的值，`effective*` 按 workspace override > 全局值 > 目录默认 解析，UI 用后者显示“实际生效”的模型。`media/get` / `media/set` 在没有快照时最多等 1.5s（有快照立即返回），否则冷启动后的第一次 Settings 打开会短暂显示空目录；第一次会话仍按 §4.3.2 最多等 3s。
- **Settings 的读写逻辑拆成 `MediaSettingsApi`**。`AppServerEngine` 已接近 450 行文件上限，且拆出后可对 `media/set` 的写入/删除、`UNKNOWN_MODEL` 校验做单测。
- **能力防御返回失败结果而不是抛异常**：`GenerateImageTool` 在非 managed 端点收到引用图时直接返回 `ToolCallResult.failed(...)`，避免 PMD 的 `ExceptionAsFlowControl`，调用方看到的信息不变。
- **目录显示名与顺序**：`HubMediaModelService` 直接映射 `mediaModelHints`，每个列表内按 `modelId` 去重并保留 priority 顺序；不跨列表合并。
- **P2 未做**：目录不返回 `supportsImageReferences` / 时长能力；没有视频任务取消工具；工具结果不带 cost（`media_jobs` 已记录，可后续接 `/generations`）。


## 0. 一句话

`generate_image` / `generate_video` 两个工具本身已经存在，Desktop 缺的不是工具，而是把**引擎 → server 媒体网关**这一段接上：现在引擎的媒体 provider 指向 chat 代理 `/api/cli/v1`，而图片/视频端点只在 `/api/gateway/v1`；同时 Desktop 不知道 server 上有哪些媒体模型，也没有配置入口和产物呈现的闭环。本设计补齐这四件事，不新写一套媒体工具。

## 1. 目标与非目标

### 1.1 目标

- 已登录 core-ai-server 的 Desktop 用户，在任意会话里 agent 能直接调用：
  - `generate_image`：文生图、图生图（`input_images` / `mask` / `media_id` / `"last"`）；
  - `generate_video`：文生视频、图生视频/首尾帧/多参考（`input_references`）；
  - `get_video_status`：轮询并下载完成的视频。
- 生成的图片/视频落到 `{workspace}/.core-ai/media/{images,videos}`，在聊天里可预览，在右侧 Artifacts 面板里可再次打开；重启 Desktop 后仍能在面板里看到。
- `generate_image` 返回 `media_id`；下一轮可以用 `media_id` 或 `"last"` 继续编辑，而不是把 URL 复制回模型上下文。
- 模型选择可控：agent 的工具描述里能看到当前 server 启用的图片/视频模型及其参数提示；Desktop Settings 能设置默认图片/视频模型；未设置时有确定的自动默认。
- 未登录或 server 没有配置媒体模型时，**不要向模型暴露不可用的工具**；报错必须给出可执行的下一步。
- 现有自定义媒体端点（`media.image.api.base` / `media.video.api.base` / Vertex）继续可用，不被这次改动破坏。

### 1.2 非目标

- 不在 Desktop 里另写一套媒体生成逻辑，也不绕过 server 的 gateway（routing、引用编译、计费、`media_jobs` 都在 server）。
- 不新增媒体 provider 协议；Provider/模型注册仍在 server 管理端完成。
- 不做本地 GPU/模型推理；不把图片/视频模型打进安装包。
- 不改变 `MediaProvider` 的既有 SPI（`ImageGenerationRequest` / `VideoGenerationRequest` 形状保持不变）。
- 不做跨用户引用；`media_id` 仍然按 API key 所属用户鉴权。
- 不在本次做 Desktop 的媒体成本页；`media_jobs` 已记录成本，后续可复用 `/generations` 的数据。
- 不对付费生成做自动重试。图片/视频提交失败直接返回，由用户或 agent 决定是否重试；`get_video_status` 的轮询可以重试，因为它不产生新费用。

## 2. 现状与代码事实

### 2.1 Desktop 的引擎链路

- Desktop 不直接跑 agent；`EngineSupervisor.spawnClient()` 为每个 workspace 启动一个 `core-ai-cli app-server --workspace <ws>` 子进程，通过 stdio JSON-RPC 通信（`core-ai-desktop/src/main/engine/EngineSupervisor.ts`）。
- 引擎启动时 `EngineBootstrap` 读取 `~/.core-ai/agent.properties`，再合并 `{workspace}/.core-ai/agent.properties`；随后 `CliAgent.of()` 为每个会话构建工具集（`core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java`、`.../agent/CliAgent.java`）。
- Desktop 的登录态与 CLI 共用 `~/.core-ai/auth.json`；登录/切换环境/登出时 `main.ts` 会关闭所有引擎，下一次会话重新拉起，因此引擎启动时读到的 auth 是最新的。

### 2.2 CLI 当前的媒体 provider

`CliAppHelper` 按下面的优先级构造图片/视频 provider：

```text
media.image.api.base     > media.api.base > litellm.api.base
media.video.protocol=VERTEX_GEMINI_INTERACTIONS（直接走 Vertex，不看 base）
media.video.api.base     > media.api.base > litellm.api.base
```

登录 core-ai-server 时，`CliAppHelper.injectLiteLLMFallback()` 会把：

```properties
litellm.api.base = {server}/api/cli/v1
litellm.api.key  = {apiKey}
```

于是（在没有 `media.video.protocol=VERTEX...` 等显式覆盖时）`imageMediaProvider` / `videoMediaProvider` 最终都会构造出 `LiteLLMMediaProvider({server}/api/cli/v1, key)`。

### 2.3 Server 的媒体端点

- chat 代理：`/api/cli/v1/chat/completions`（`CliProxyChannelListener.PATH`），只注册了 chat。
- 媒体网关：`/api/gateway/v1/images/generations`、`/images/edits`、`/videos`、`/videos/:id`、`/videos/:id/content`（`GatewayModule.registerGatewayProxyRoutes()`，控制器为 `GatewayImageController` / `GatewayVideoController`）。
- 网关内部由 `GatewayMediaProvider` 完成模型路由、引用解析（`media_id` / URL / base64）、上游调用、`media_jobs` 落库与成本结算。

### 2.4 当前断点

| # | 现象 | 根因（代码事实） |
|---|---|---|
| 1 | `generate_image` / `generate_video` 调用返回 404 | provider base 是 `{server}/api/cli/v1`，媒体路由只在 `/api/gateway/v1` |
| 2 | 工具描述里没有模型列表，agent 只能猜 `model` | CLI 侧没有媒体模型目录；`GenerateImageTool.builder().build()` / `GenerateVideoTool.builder(...).build()` 用的是静态 description |
| 3 | 不传 `model` 时 server 拒绝 | `GatewayImageController.imageRequest()` / `GatewayVideoController.videoRequest()` 要求 `model` 非空；CLI 的 `media.image.model` / `media.video.model` 在 Desktop 上通常没有配置 |
| 4 | 图生图/蒙版编辑不生效 | `LiteLLMMediaProvider.imageRequestBody()` 只序列化 prompt/n/size/quality 等，**丢掉 `input_images` 和 `mask`**；`acceptsImageReferences()` 返回 `false` |
| 5 | `media_id` 不能复用 | `LiteLLMMediaProvider` 不实现 `ManagedReferenceProvider`，`GenerateImageTool.resolve()` 对非 managed provider 直接拒绝 symbolic reference；同时 provider 也不解析响应里的 `media_id` |
| 6 | 只有一方 provider 时另一方的工具也会注册 | `EngineBootstrap.mediaProvider = imageProvider != null ? imageProvider : videoProvider`，`CliAgent.mediaTools()` 又把它当作另一方的 fallback；只有视频 provider 时甚至会注册 `generate_image`（对着视频 provider 调 `generateImage`） |
| 7 | Desktop 没有媒体设置入口 | `renderSettingsPanel()` 只有 workspace/engine/approval/max turns/skills/schedules 等；没有 `media.*` 的读写 |
| 8 | 生成结果不进 Artifacts 面板（URL 结果） | `GenerateImageTool` 只对 `b64_json` 调 output sink；上游返回 URL 时直接返回 URL，Desktop 不会落盘、不会发 `artifact` 事件 |
| 9 | 聊天里的本地视频链接点不开 | `rewriteLocalImageSources()` 只处理 `<img>`；`shell:openExternal` 只接受 `http(s)` |

### 2.5 已经具备、不需要重做的部分

- `LocalArtifactSink`（`core-ai-cli`）已实现 `GenerateImageTool.ImageOutputSink` 与 `GetVideoStatusTool.VideoOutputSink`：把字节写到 `{workspace}/.core-ai/media/{images,videos}`，并发出 `artifact` custom event，payload 含 `path/kind/name/contentType/size/tool/createdAt`。
- Desktop 的 `applyArtifactEvent()` 已消费该事件，`renderArtifactPanel()` 已支持图片/视频预览，`ArtifactProtocol` 已支持 HTTP Range（视频 seek 可用）。
- `LocalArtifacts.list()` 已扫描 `.core-ai/media`，所以重启后 Artifacts 面板仍有基线。
- `GetVideoStatusTool` 完成时已经通过 sink 下载视频；`GenerateVideoTool` 已有“同一会话只允许一个视频任务”的上下文状态。
- Desktop 的附件通过 `SendParts` 以 base64 传给引擎；`GenerateImageTool` / `GenerateVideoTool` 对 `AttachedContent` 已有处理路径。

## 3. 方案总览

```text
core-ai-desktop (Electron)
  └─ core-ai-cli app-server (engine, one per workspace)
       ├─ EngineBootstrap
       │    ├─ injectLiteLLMFallback: auth -> litellm.api.base + media.gateway.api.base
       │    ├─ CoreAiGatewayMediaProvider (image/video, ManagedReferenceProvider)
       │    └─ MediaModelCatalog (GET /api/hub/media-models, 60s cache)
       ├─ CliAgent
       │    ├─ generate_image      (description = 静态文档 + 模型列表 + 默认值)
       │    ├─ generate_video      (同上)
       │    └─ get_video_status
       └─ ExecutionContext
            ├─ imageMediaProvider / videoMediaProvider
            ├─ LocalArtifactSink (image + video)
            └─ media.image.model / media.video.model
core-ai-server
  ├─ /api/hub/media-models        (新增，只读模型目录)
  ├─ /api/gateway/v1/images/*     (已有)
  ├─ /api/gateway/v1/videos/*     (已有)
  └─ GatewayMediaProvider         (已有：routing / reference / media_jobs / cost)
```

五条原则：

1. **一条媒体传输通道**：Desktop 引擎直接调用 server 的 `/api/gateway/v1`，与 chat 共用同一个 API key；不为 Desktop 新增代理协议。
2. **一个 managed client**：只有“base 由登录态派生”时才使用 `CoreAiGatewayMediaProvider`（实现 `ManagedReferenceProvider`）；用户显式配置的通用端点仍用 `LiteLLMMediaProvider`，避免把 `media_id` 语义错误地带给普通 LiteLLM/OpenAI 代理。
3. **一个模型目录**：server 暴露只读目录，engine 缓存后同时用于工具描述、默认模型和 Settings UI；不在 Desktop 里硬编码模型名。
4. **一条产物链路**：继续用 `LocalArtifactSink` + Artifact Protocol；只补“URL 结果落盘”和“本地视频链接可播放”两个缺口。
5. **不静默降级**：provider 或模型不可用时，不注册工具；调用失败时返回具体原因和修复动作。

### 3.1 实施分期

| 阶段 | 范围 | 验收标准 |
|---|---|---|
| **P0 打通** | 4.1 连接派生、4.2 managed client、4.3.1 目录 API、4.3.2 catalog、4.3.4 注册矩阵、4.5.1 b64 落盘 | Desktop 登录后能文生图、文生视频、轮询并看到 Artifacts；不配模型也能用 server 默认 |
| **P1 完整体验** | 4.3.3 默认/编辑模型、4.5.1 URL 落盘、4.5.2 视频链接、4.6 Settings、4.7 兼容 | `media_id` 图生图可用；Settings 可选模型；重启后产物仍在；旧 server 有明确降级 |
| **P2 增强** | 4.4 会话级 `"last"`、目录补 `supportsImageReferences` / 时长能力、成本展示、任务取消 | 按评审优先级另行排期 |

P0 也可以用“先写死一个 server 模型 + 只支持文生图/视频”临时验证，但不要把这个临时路径带进发布版本。如果 P2 的会话级 `"last"` 不随本版本发布，P0/P1 必须先把 `GenerateImageToolDescription` / `GenerateVideoTool` 里的 “this conversation/session” 改成 “this account/login”，否则描述与实现不一致。

## 4. 详细设计

### 4.1 连接与配置：把媒体指向 server gateway

#### 4.1.1 派生属性

修改 `CliAppHelper.injectLiteLLMFallback()`：当 `litellm.api.base` 不在配置里、且存在有效登录态时，除了 chat 的 fallback，再派生一组**内部媒体属性**：

```properties
# 已存在（chat）
litellm.api.base = https://core-ai.example.com/api/cli/v1
litellm.api.key  = coreai_xxx

# 新增（media，仅由登录态派生，不写回 agent.properties）
media.gateway.api.base = https://core-ai.example.com/api/gateway/v1
media.gateway.api.key  = coreai_xxx
```

规则：

- 只有 `litellm.api.base` **不是用户显式配置**（即本次 fallback 确实由 auth 注入）时才派生 `media.gateway.api.base`。用户已经配置了自己的 LiteLLM 代理时，媒体继续跟随 `litellm.api.base`，保持现有行为。
- `media.gateway.api.base` 只是内存中的 `PropertiesFileSource` 覆盖，不落盘；用户不需要也不应该手写它。如果它已经被显式配置（测试/特殊环境），不要覆盖。
- 用户显式配置的 `media.image.api.base` / `media.video.api.base` / `media.api.base` 永远优先；显式配置的端点不会被 gateway 替换。
- `media.gateway.api.base` 只用于“没有更具体的媒体配置”的情况；它不会覆盖 `media.video.protocol=VERTEX_GEMINI_INTERACTIONS`。
- 登出/切换环境时 Desktop 会重启引擎，派生值随之失效；不会出现“已经登出但还在用旧 key 调媒体”的情况。
- ACP 还有一份独立的 `AcpAgentRunner.injectLiteLLMFallback()`。必须把它改成调用 `CliAppHelper` 的共享实现（或补上同样的 `media.gateway.*` 派生），否则 ACP 登录后媒体仍然会走 `/api/cli/v1`。

#### 4.1.2 provider 选择

`CliAppHelper.imageMediaProvider()` / `videoMediaProvider()` 的选择顺序改成：

```text
图片：
  media.image.api.base         -> LiteLLMMediaProvider（用户自定义，generic）
  media.api.base               -> LiteLLMMediaProvider（用户自定义，generic）
  media.gateway.api.base       -> CoreAiGatewayMediaProvider（managed）
  litellm.api.base             -> LiteLLMMediaProvider（用户自定义 LiteLLM 代理）

视频：
  media.video.protocol=VERTEX_GEMINI_INTERACTIONS -> VertexGeminiOmniMediaProvider
  media.video.api.base         -> LiteLLMMediaProvider（generic）
  media.api.base               -> LiteLLMMediaProvider（generic）
  media.gateway.api.base       -> CoreAiGatewayMediaProvider（managed）
  litellm.api.base             -> LiteLLMMediaProvider（用户自定义 LiteLLM 代理）
```

key 的选择与 base 对齐：`media.image.api.key` > `media.api.key` > `media.gateway.api.key` > `litellm.api.key`（视频同理，视频专属 key 优先）。派生场景下 `media.gateway.api.key` 与 `litellm.api.key` 都是同一个 auth key，不会出现错配。

这样：

- 已登录 Desktop 零配置即可得到 managed provider；
- 用户显式配置的 Azure/OpenAI/Vertex 端点行为不变；
- 通用 LiteLLM 代理不会被误标为 `ManagedReferenceProvider`（它没有 `media_jobs`，无法解析 `gateway-media-v1.*`）。
- 一个小的健壮性处理：如果用户显式配置的 base 以 `/api/gateway/v1` 结尾（复制了 server 的 gateway URL），也按 managed provider 处理；其他显式 base 仍用 generic。

#### 4.1.3 配置属性表

| 属性 | 来源 | 是否写盘 | 说明 |
|---|---|---|---|
| `litellm.api.base` / `.key` | 用户或 auth fallback | 用户配置时写盘；fallback 不写 | chat 端点 |
| `media.gateway.api.base` / `.key` | auth fallback | 否 | 内部派生；指向 `{server}/api/gateway/v1` |
| `media.image.api.base` / `.key` | 用户 | 是 | 自定义图片端点，优先于 gateway |
| `media.video.api.base` / `.key` | 用户 | 是 | 自定义视频端点，优先于 gateway |
| `media.api.base` / `.key` | 用户 | 是 | 图片/视频共享自定义端点 |
| `media.image.model` | 用户 / Desktop Settings | 是 | 默认图片模型 |
| `media.video.model` | 用户 / Desktop Settings | 是 | 默认视频模型 |
| `media.image.edit.model` | 用户（可选） | 是 | 图生图默认模型；不配则取目录里第一个 `image.edits` 模型 |
| `media.video.protocol` | 用户 | 是 | `VERTEX_GEMINI_INTERACTIONS` 时绕过 gateway |

### 4.2 媒体客户端：CoreAiGatewayMediaProvider

#### 4.2.1 类关系

```text
MediaProvider
  └─ LiteLLMMediaProvider                 (generic OpenAI/LiteLLM 端点)
       └─ CoreAiGatewayMediaProvider      (core-ai server gateway)
            implements ManagedReferenceProvider
```

`CoreAiGatewayMediaProvider` 只做三件事，其余复用父类：

1. 标记自己为 `ManagedReferenceProvider`，让 `GenerateImageTool` / `GenerateVideoTool` 把 symbolic reference 原样交给 server；
2. 使用更长的 HTTP 超时（图片同步生成可能阻塞 10 分钟以上）；
3. 附加 `X-Core-AI-Client: cli`（以及可选的会话 header，见 §4.4）。

#### 4.2.2 补齐 `LiteLLMMediaProvider` 协议

当前 `LiteLLMMediaProvider` 是“能提交、不能编辑、不返回 handle”的半成品。设计上把它补成完整协议（generic 端点也能受益）：

**图片请求**（显式构建 body，避免 native Jackson 对 record 序列化的差异）：

```json
{
  "model": "gpt-image-2.5-flare",
  "prompt": "turn the lemon into a lime",
  "n": 1,
  "size": "1024x1536",
  "quality": "high",
  "output_format": "png",
  "output_compression": 90,
  "background": "transparent",
  "input_images": [
    {"media_id": "gateway-media-v1.img.am9iLTE", "name": "source", "role": "subject"}
  ],
  "mask": {"b64Json": "data:image/png;base64,..."},
  "provider_extra": {"input": {"watermark": false}},
  "previous_interaction_id": "interaction-1"
}
```

- `MediaReference` 的序列化必须显式写 `media_id`（snake_case）、`b64Json`、`url`、`name`、`role`（小写枚举名）、`modality`，不要依赖 `JsonUtil.toMap()` 的字段名。
- `mask` 是单个对象，不是数组。
- 无 `input_images` / `mask` 时省略字段，不要发空数组。
- `provider_extra` 继续按现有规则合并。

**图片响应**：

```json
{
  "data": [{"b64_json": "...", "url": "https://...", "revised_prompt": "..."}],
  "usage": {"total_tokens": 350, "image_count": 1},
  "interaction_id": "...",
  "media_id": "gateway-media-v1.img.am9iLTE",
  "notes": ["dropped 2 references: model accepts 4"]
}
```

`LiteLLMMediaProvider.generateImage()` 需要解析 `media_id`、`interaction_id`、`notes`，构造完整的 `ImageGenerationResponse`（当前只解析 `data` 和 `usage`，导致 `media_id` 永远丢失）。

**视频请求**：把当前 `JsonUtil.toMap(request)` 改成显式 body：

```json
{
  "model": "seedance-2.5",
  "prompt": "...",
  "seconds": 5,
  "size": "1280x720",
  "input_references": [
    {"media_id": "gateway-media-v1.img.abc", "role": "first_frame"}
  ],
  "previous_video_id": "gateway-media-v1.vid.xyz",
  "provider_extra": {"input": {"generate_audio": true}}
}
```

**视频状态/下载**：继续使用 `GET /videos/:id` 和 `/videos/:id/content`；解析 `credits_consumed`、`upstream_cost_usd`、`usage`。

**能力声明**：`LiteLLMMediaProvider` 增加一个构造参数 `acceptsImageReferences`（默认 `false`），`CoreAiGatewayMediaProvider` 传 `true`。这样 generic LiteLLM/OpenAI 端点不会被误认为支持引用图；如果用户显式配置的端点确实支持 `input_images`，可以在后续把它接到同一个 flag 上。同时给 `GenerateImageTool` 增加一层防御：如果 `input_images` / `mask` 非空、而 provider 返回 `acceptsImageReferences() == false`，直接失败并说明“该端点不支持引用图，请换支持 image edit 的模型/端点”，不要静默生成一张无关的新图。

#### 4.2.3 超时与错误

| 操作 | 客户端超时 | 说明 |
|---|---|---|
| 图片生成 | 15 min | server 的异步图片 poll 默认 10 min，再加下载余量；`GenerateImageTool` 默认 12 min |
| 视频提交 | 2 min | 只提交异步任务 |
| 视频状态 | 2 min | 轻量 GET |
| 视频下载 | 10 min | MP4 可能较大 |

- 实现上建议拆成三个 HTTP client（generation / control / download），否则一个 15 min 的全局超时会让 `get_video_status` 轮询在异常网络下也等 15 min。
- `GetVideoStatusTool` 的 ToolCall 默认超时是 5 min（`ToolCall.DEFAULT_TIMEOUT_MS`），但完成时还要下载 MP4；建议在 builder 里显式设为 10 min，与下载 client 一致，避免“状态已完成但下载被工具超时打断”。
- `GenerateImageTool.DEFAULT_IMAGE_TIMEOUT_MS` 与 `CoreAiGatewayMediaProvider` 的 HTTP 超时必须同向：工具超时 12 min、HTTP 15 min；如果后续把 server poll 调到 12 min 以上，需要同时调大这两个值（或增加 `media.image.timeout.seconds` 配置）。
- 错误信息统一为 `HTTP <status>: <server message>`；截断 base64，避免把整张图写进模型上下文。
- 401 提示重新登录；404 且 path 以 `/api/gateway/v1` 开头时提示“server 未提供媒体网关，请升级 server 或配置 `media.*` 自定义端点”；400 直接透传 server 的模型/引用错误。
- 部署在反向代理/Ingress 后时，`/api/gateway/v1/images/generations` 的同步等待可能被默认 60s read timeout 截断；需要把该路径的超时调到不小于图片 poll 窗口，或后续把图片也改成异步 job。Desktop 本地直连不受影响，但 UAT/生产环境必须检查。

### 4.3 模型目录：让 agent 知道有什么模型

#### 4.3.1 server 端点

新增只读内部 API：

```http
GET /api/hub/media-models
Authorization: Bearer <apiKey>
```

响应：

```json
{
  "imageModels": [
    {
      "modelId": "gpt-image-2.5-flare",
      "displayName": "GPT Image 2.5",
      "providerName": "azure-uat",
      "upstreamModel": "gpt-image-2.5"
    }
  ],
  "imageEditModels": [
    {
      "modelId": "gpt-image-2.5-flare",
      "displayName": "GPT Image 2.5",
      "providerName": "azure-uat",
      "upstreamModel": "gpt-image-2.5"
    }
  ],
  "videoModels": [
    {
      "modelId": "seedance-2.5",
      "displayName": "Seedance 2.5",
      "providerName": "volcengine",
      "upstreamModel": "doubao-seedance-2-5-260628"
    }
  ]
}
```

实现：

- `core-ai-api` 新增 `HubMediaModelWebService`、`ListHubMediaModelsResponse`、`HubMediaModelView`。响应使用**三个有序列表**而不是一个带 `endpointTypes` 的扁平列表：每个列表的顺序就是对应端点的 `mediaModelHints` 顺序，这样“第一个模型”与 gateway 的默认选择一致，`image.edits` 的默认也不会被生成列表的顺序污染。同一个模型可以同时出现在 `imageModels` 和 `imageEditModels` 中。
- **不复用** `HubModelWebService`：chat 模型目录与媒体目录的权限、字段和用途不同，单独一个接口更清晰。`HubMediaModelWebServiceImpl` 使用 `@PermissionsBypass`（认证即可读），与 `GatewayImageController` / `GatewayVideoController` 的媒体网关保持一致；否则会出现“能调用媒体端点、却看不到模型列表”的错位。如果产品要求媒体模型也受 `CHAT_USE` 约束，那么网关 controller、目录接口和路由必须一起改，不能只改目录。
- 新增 `HubMediaModelService`：分别调用 `GatewayRoutingEngine.mediaModelHints(IMAGE_GENERATION / IMAGE_EDIT / VIDEO_GENERATION)`，每个列表内按 `modelId` 去重（同一 modelId 的多条 failover route 取优先级最高的一条），`displayName` 从 `GatewayRoutingEngine.availableModels()` 按 `modelId` 关联。不要跨列表合并。
- 只返回 enabled 且 provider enabled 的模型（`registeredModels` 已过滤）。
- 每个列表沿用 `registeredModels` 的 priority → modelId → provider → id 顺序，保证 Desktop 看到的默认模型稳定。
- 用户级模型访问策略：当前与 `HubModelService.models()` 一样，目录是全局 enabled 模型；如果后续引入 per-user model access，过滤函数必须与 `GatewayRoutingEngine.route()` 共用，保证“列出的就是能用的”，避免 Desktop 选到一个调用时才 403 的模型。
- 兼容说明：路由引擎把 `endpointTypes` 为空的模型视为“所有端点可用”（`supportsEndpoint` 的既有语义），所以历史上没有显式配置 `endpointTypes` 的媒体模型仍会出现在目录里；管理端应给媒体模型显式配置 `endpointTypes`，避免 chat 模型被误列为媒体模型。
- `upstreamModel` 出现在响应里是为了让 CLI 复用 `MediaModelParameterHints` 的模型参数提示；这是登录用户可见的内部 API，不包含密钥。若后续认为上游模型名不应外露，可改为由 server 直接返回 `parameterHint` 字段，CLI 不再需要 `upstreamModel`。

#### 4.3.2 CLI 侧 `MediaModelCatalog`

新增 `core-ai-cli/src/main/java/ai/core/cli/media/`：

```text
MediaModelCatalog
  - 构造：AuthConfig（serverUrl / apiKey / insecure）
  - fetch(): HubMediaModelClient -> ListHubMediaModelsResponse
  - 缓存：成功 60s；失败 30s 内不重试；保留 last-known-good
  - 线程安全：synchronized 刷新，读走 volatile snapshot
  - 方法：
      imageModels()         -> List<MediaModelHint>   // 对应响应 imageModels
      imageEditModels()     -> List<MediaModelHint>   // 对应响应 imageEditModels
      videoModels()         -> List<MediaModelHint>   // 对应响应 videoModels
      defaultImageModel()   -> String | null          // 目录顺序第一个
      defaultImageEditModel() -> String | null
      defaultVideoModel()   -> String | null
```

- `HubMediaModelClient` 复用 `RemoteApiClient`，带 `X-Core-AI-Client: cli`，超时 8s，`insecure` 跟随 auth。
- `EngineBootstrap` 启动时后台预热一次（不阻塞引擎启动）；`media/get` 优先返回已有快照，不因为 Settings 打开而卡住 dispatch 线程。第一个会话如果还没有快照，允许在 3s 内同步取一次（与现有 `model/list` 的行为一致），超时则按“目录未知”处理。
- catalog 只在**至少一个 modality 实际使用 managed gateway** 时创建；如果用户对图片/视频都配置了自定义端点，不创建 catalog，`CliAgent` 走静态 description。图片用 gateway、视频用 Vertex 这种混合配置，catalog 仍会创建，但只服务图片。
- `media/get` 分别返回 `imageSource` / `videoSource`（`server` / `custom` / `none`），Settings 按 modality 展示；不要用一个全局“已连接”标志掩盖混合配置。
- catalog 只用于 **managed gateway**；自定义端点仍由用户在 `media.*` 里指定模型。
- 失败语义：`fetch` 失败返回 `null` + `error`，不抛出到会话创建流程；`CliAgent` 用 last-known-good，若从未成功过则按“未知目录”处理（见 §4.3.4）。

#### 4.3.3 工具描述与默认模型

`CliAgent.mediaTools()` 改为：

```java
if (imageProvider != null && imageAvailable) {
    var description = GenerateImageTool.buildDescription(catalog.imageModels(), catalog.imageEditModels());
    if (effectiveImageModel != null) description += "\n\nDefault image model for this session: " + effectiveImageModel;
    tools.add(GenerateImageTool.builder().description(description).build());
}
if (videoProvider != null && videoAvailable) {
    var description = GenerateVideoTool.buildDescription(catalog.videoModels());
    if (effectiveVideoModel != null) description += "\n\nDefault video model for this session: " + effectiveVideoModel;
    tools.add(GenerateVideoTool.builder(videoProvider).description(description).build());
    tools.add(GetVideoStatusTool.builder().build());
}
```

默认模型优先级：

```text
图片：
  media.image.model（用户/Settings）
  > catalog.defaultImageModel()   // 目录顺序第一个（priority 排序）
  > 无（不注册 generate_image）

图生图：
  media.image.edit.model（用户显式配置）
  > 如果 media.image.model 本身在 image.edits 目录里，则复用它
  > catalog.defaultImageEditModel()
  > 默认图片模型

视频：
  media.video.model
  > catalog.defaultVideoModel()
  > 无（不注册 generate_video / get_video_status）
```

- `media.image.model` / `media.video.model` 最终写入 `ExecutionContext` 的 `media.image.model` / `media.video.model`，`model_scope=session` 仍按现有逻辑覆盖会话默认值。
- `model=""`（用于 `model_scope=session` 清除会话默认）在请求前归一为“未指定”，再走默认选择；不要把空字符串当作模型 ID 发给 gateway。
- `CliAgent` 写入两个 context 变量：`media.image.edit.models`（image.edits 目录里的 modelId 集合，`List<String>`）和 `media.image.edit.model`（回退默认：用户配置的 `media.image.edit.model` > 配置的 `media.image.model`（若在集合中）> `catalog.defaultImageEditModel()`）。
- `GenerateImageTool` 的规则：先解析 `input_images` / `mask`；如果有引用且 `args.model` 为空，先看当前 `media.image.model` 是否在 `media.image.edit.models` 集合中（这样 `model_scope=session` 切换后仍然正确），在则用它，否则用 `media.image.edit.model`。实现时把 model 选择移到 input images 解析之后，避免默认文生图模型被用于图生图而触发 gateway 的 endpoint 不匹配。
- 工具描述里追加 `Default image/video model ...` 的意图：agent 在省略 `model` 时知道会花哪个模型的费用，也能在默认模型不支持当前操作（如文生图模型收到引用图）时主动换模型。
- 视频工具描述补一句：带 `input_references` 时不要依赖 text-to-video 默认，选择提示里写明 image-to-video / reference-to-video 的模型（目录暂不返回引用能力标记，见 §10 开放问题 3）。
- 模型参数提示继续由 `MediaModelParameterHints` 生成；server 目录返回 `upstreamModel` 后，CLI 与 server 端 agent 使用同一套提示文本。

#### 4.3.4 工具注册矩阵

| 场景 | `generate_image` | `generate_video` / `get_video_status` |
|---|---|---|
| 已登录 + gateway 目录有图片模型 | ✅ | 目录有视频模型时 ✅ |
| 已登录 + 目录为空/无该 modality 模型 | 仅当 `media.image.model` 有值 | 仅当 `media.video.model` 有值 |
| 已登录 + 目录接口 404（旧 server） | 仅当 `media.image.model` 有值 | 仅当 `media.video.model` 有值 |
| 自定义 `media.image.api.base` | ✅（静态描述） | 仅当配置了视频 provider |
| 自定义 `media.video.api.base` / Vertex | 仅当配置了图片 provider | ✅ |
| 未登录、无 `media.*` | ❌ | ❌ |

- 显式配置的模型优先于目录：只要 `media.image.model` / `media.video.model` 有值，就不因为“目录为空/不包含它”而隐藏工具；目录可能过期或不完整，真正的模型有效性由 server 在调用时判断。目录只用于补充提示和自动默认。
- 删除 `CliAgent.mediaTools()` 里“图片 provider 兜底成视频 provider”的逻辑；共享 `media.api.base` 已经由 `CliAppHelper` 分别解析成 image/video provider，不需要在工具注册层再做 fallback。`mediaProvider` 参数可以保留以兼容现有 `Config`，但不再参与跨 modality 兜底，后续可清理。
- `EngineBootstrap.mediaProvider` 不再用“image 优先”推断；它只表示显式的共享端点（`media.api.base` 存在时），否则为 `null`。这样不会出现“只有图片端点却注册视频工具”的假能力。
- `media/models` RPC 会返回当前目录和默认值，Desktop Settings 据此显示“可用/不可用”的原因。

### 4.4 会话归属：让 `"last"` 真的是“本次会话”

工具描述里 `["last"]` 写的是“the most recent image of this session”，但 `GatewayImageController.currentOwner()` 目前只传 `userId`，`MediaJobService.findLatestCompleted()` 在没有 `sessionId` 时会退化成“该用户最近一张”。Desktop 的会话是隔离的，这个差异会让“last”可能引用到另一个会话的图片。

设计上建议**同期做**（成本低，收益明确）：

1. `CoreAiGatewayMediaProvider` 增加可选的 `sessionId`，在每个请求上带 `x-session-id: <sessionId>`（`GatewaySupport.CLIENT_SESSION_HEADERS` 已认识这个 header）。
2. `GatewayImageController` / `GatewayVideoController` 的 `currentOwner(Request)` 改为：
   ```java
   new MediaJobOwner(AuthContext.userId(webContext), GatewaySupport.clientSessionId(request), null)
   ```
   `GatewaySupport.clientSessionId` 是包内可见方法，控制器同包。
3. app-server 的 `EngineSessionRegistry.build()` 为每个会话构造 provider（或从一个 `MediaProviderFactory` 取），把 `sessionId` 传进去；interactive CLI / ACP 可以继续用单例 provider（不带 session header），行为退化为“该用户最近一张”，与今天一致。实现上建议 clone 一个轻量 wrapper 复用父类已建好的 HTTP client/连接池，不要每个会话都重建一份。
4. 这样 `media_jobs.session_id` 也会被填上，媒体 trace 与成本记录能落到正确会话；后续 Desktop 做“本次会话的生成历史”时不需要再补数据。

如果本期不做会话级 `"last"`，必须把工具描述从 “of this session” 改成 “of this account/login”，不要保留一个与实现不一致的承诺。

### 4.5 产物：落盘、聊天预览、Artifacts 面板

#### 4.5.1 图片

- `b64_json`：维持现状，`GenerateImageTool.saveImage()` 解码后走 `ImageOutputSink`；Desktop 的 sink 写 `.core-ai/media/images` 并发 `artifact`。
- `url`：新增“有 sink 时下载并落盘”的路径：
  - 仅当 `context` 里有 `ImageOutputSink` 时启用（CLI 无 sink 时保持返回 URL，不改变现有行为）；
  - 下载上限 64 MB（generated image 可能比参考图大，不能复用 `ReferenceImageLoader` 的 10 MB 上限）；
  - 跟随重定向、校验 `Content-Type` 以 `image/` 开头；文件扩展名按 Content-Type 推断（png/jpeg/webp），不要用可能为空的 `output_format`；失败时回退为原 URL，并在结果里加一条 notes，不失败整次生成；
  - 多图结果（`n > 1`）逐张落盘，结果文本返回本地路径列表；`media_id` 仍然只对应第一张（gateway 的既有语义，需在结果里说明）。
- 聊天里的 Markdown 图片链接：`rewriteLocalImageSources()` 已把 workspace 内的绝对路径改写成 `coreai-artifact://`，无需改动。
- Artifacts 面板：sink 写入 `.core-ai/media/images`，`LocalArtifacts.list()` 重启后可恢复；无需 manifest。

#### 4.5.2 视频

- `GetVideoStatusTool` 完成时继续走 `VideoOutputSink` 下载 MP4；Desktop 的 sink 写 `.core-ai/media/videos` 并发 `artifact`。
- Artifacts 面板已支持 `<video>` 播放；`ArtifactProtocol` 已实现 Range，可直接 seek。
- 聊天里的本地视频链接：新增 `rewriteLocalVideoLinks(root)`，在 `renderMarkdownInto()` 里与图片重写一起调用：
  - 找到 `<a href>` 指向 workspace 内、扩展名为 `.mp4/.webm/.mov` 的链接；
  - 替换为 `<video controls preload="metadata" src="coreai-artifact://...">`，并保留一个“在 Artifacts 中打开”的按钮；
  - 非视频链接保持现状（`shell:openExternal` 只处理 http(s)）。
- 如果视频仍在 processing，`get_video_status` 返回 PENDING；Desktop 的工具卡片显示 pending 状态，不生成产物。完成事件到达后 sink 才发 `artifact`。
- 工具卡片缩略图：`artifactByCallId` + `appendToolThumbnail()` 已支持；视频显示 ▶ 图标，点击打开 Artifacts 面板。

#### 4.5.3 失败与降级

| 情况 | 行为 |
|---|---|
| URL 图片下载失败 | 保留原 URL，结果加 note，面板可能无本地副本 |
| 视频下载失败 | 工具返回失败，保留 `video_id`；再次调用 `get_video_status` 会重新下载 |
| sink 写入失败 | 工具失败（不能假装产物已保存）；错误带上目标路径 |
| 产物文件被用户删除 | Artifacts 基线扫描会自然消失；聊天里的历史链接可能 404 |
| 视频 URL 过期 | 立即下载策略（`GetVideoStatusTool` 完成即下载）避免依赖上游 24h URL |

### 4.6 Desktop 设置面板

#### 4.6.1 位置与内容

在 `renderSettingsPanel()` 中新增 “Media generation” 区块（建议放在 “Built-in skills” 之前），内容：

```text
Media generation
  Server: https://core-ai.example.com  (core-ai gateway)
  Default image model  [ Auto (first enabled: gpt-image-2.5-flare) ▼ ]
  Default video model  [ Auto (first enabled: seedance-2.5)        ▼ ]
  hint: Shared with the CLI; stored in ~/.core-ai/agent.properties. The engine reloads to apply.
```

状态分支：

- **已登录 + 目录非空**：显示 server URL、默认模型下拉、模型数量。
- **已登录 + 目录为空**：`No image/video models are enabled on this server. Ask an administrator to register a gateway model with image.generations / image.edits / video.generations.` 下拉禁用。
- **未登录**：`Sign in from Hub to generate images and videos with the server gateway.` + 一个跳转 Hub 的按钮。
- **自定义端点**（`media.image.api.base` / `media.video.api.base` / Vertex）：按 modality 显示 `Custom endpoint (media.*)`；该行模型输入改为文本框（或只读显示当前值），不显示 server 目录。图片和视频可以一个是 server、一个是 custom。
- **workspace override**：如果 `{workspace}/.core-ai/agent.properties` 覆盖了 `media.image.model` / `media.video.model`，显示 `Overridden by workspace .core-ai/agent.properties`，下拉仍可改全局值但提示不会生效。

#### 4.6.2 读写路径

Desktop renderer 通过现有 `window.coreai.call(workspace, method, params)` 调用 engine：

```json
// media/get
{
  "signedIn": true,
  "gateway": "https://core-ai.example.com/api/gateway/v1",
  "imageSource": "server",
  "videoSource": "server",
  "imageModel": "gpt-image-2.5-flare",
  "videoModel": "seedance-2.5",
  "effectiveImageModel": "gpt-image-2.5-flare",
  "effectiveVideoModel": "seedance-2.5",
  "imageBase": "https://core-ai.example.com/api/gateway/v1",
  "videoBase": "https://core-ai.example.com/api/gateway/v1",
  "catalogUnavailable": false,
  "imageModelFromWorkspace": false,
  "videoModelFromWorkspace": false,
  "imageModels": [
    {"modelId": "gpt-image-2.5-flare", "displayName": "GPT Image 2.5", "providerName": "azure-uat"}
  ],
  "imageEditModels": [
    {"modelId": "gpt-image-2.5-flare", "displayName": "GPT Image 2.5", "providerName": "azure-uat"}
  ],
  "videoModels": [
    {"modelId": "seedance-2.5", "displayName": "Seedance 2.5", "providerName": "volcengine"}
  ],
  "error": null
}
```

```json
// media/set
{"imageModel": "gpt-image-2.5-flare", "videoModel": "seedance-2.5"}
// response
{"imageModel": "gpt-image-2.5-flare", "videoModel": "seedance-2.5", "restartRequired": true}
```

- `media/get` 内部使用 `MediaModelCatalog`；目录请求失败时返回 `error`，但不要阻塞 Settings 其他区块。额外返回 `imageModelFromWorkspace` / `videoModelFromWorkspace` 两个布尔值（读 `{workspace}/.core-ai/agent.properties` 是否存在对应键），用于显示 override 提示。
- `media/set` 写 `~/.core-ai/agent.properties` 的 `media.image.model` / `media.video.model`；字段省略表示不改，空字符串/null 表示删除该键（回到 auto）。沿用 `AppServerEngine.persistActiveModel()` 的 `Properties.store` 方式，不新增配置文件。
- managed gateway 下，`media/set` 对非空的 `imageModel` / `videoModel` 做一次 catalog 校验（模型不在对应列表里就返回 `UNKNOWN_MODEL`）；custom endpoint 不校验，模型名由用户端点决定。
- 返回 `restartRequired: true`；renderer 复用 `restartWorkspaceEngine()`。如果当前有 turn 在跑，复用 `state.pendingEngineRestart`（与 max turns 相同的“turn 结束后重载”语义）。
- 不需要新增 preload API；`engine:call` 已经覆盖。

#### 4.6.3 为什么不做 Desktop 私有配置

- Desktop 与 CLI 共用同一个引擎和 `agent.properties`；模型、max turns、权限已经是这个模式。
- 如果把媒体设置放到 Desktop 的 `settings.json`，CLI 看不到，用户会得到两套默认模型；也会让“Desktop 生成”和“CLI 生成”行为不一致。
- `media.gateway.api.base` 是 auth 派生的运行时值，不应写盘；用户显式配置仍走 `media.*`。

### 4.7 兼容、降级与安全

- **旧 server（无 `/api/hub/media-models`）**：`MediaModelCatalog` 收到 404 时标记 `catalogUnavailable`；如果用户配置了 `media.image.model` / `media.video.model`，仍注册工具（静态描述），否则不注册。Settings 显示 `Server does not expose a media model catalog; upgrade the server or set media models manually.`
- **旧 server（无 `/api/gateway/v1` 媒体路由）**：目录 404 + 调用 404；错误信息必须指向“升级 server 或配置 `media.*` 自定义端点”。不要显示“no media provider configured”这种误导性信息。
- **自定义端点**：继续走 `LiteLLMMediaProvider`；若端点不支持 `input_images`，`GenerateImageTool` 在发送前拒绝（`acceptsImageReferences() == false`），避免静默生成无关图片。
- **鉴权**：`CoreAiGatewayMediaProvider` 使用 `Authorization: Bearer <apiKey>`；不记录 key；TLS 行为与现有 `LiteLLMMediaProvider` 一致（`PatchedHTTPClientBuilder.trustAll()`，支持本地自签名 server）。
- **API key 过期**：401 直接返回“请重新登录”；Desktop 的 Hub 登录/登出会重启引擎，不会复用旧 provider。interactive CLI 如果在运行中完成登录/切换 server，`LiteLLMProvider` 会通过 `RuntimeAuthConfig` 热更新，但媒体 provider 的 key 是构造时固定的；重启 CLI 即可，不会影响 Desktop。
- **并发**：`generate_video` 仍然“一会话一任务”；`generate_image` 保持 `MEDIA_GENERATION` 并发组；Desktop 不需要额外加锁。
- **审批**：媒体工具不是只读工具；`ask` policy 下仍走 Desktop 审批条。审批文案建议显示模型和 prompt 摘要（现有 tool arguments 已可展开）。

## 5. 关键流程

### 5.1 首次文生图（已登录，零配置）

```text
Desktop 启动
  -> app-server engine
  -> injectLiteLLMFallback(auth) 写入 litellm.api.base + media.gateway.api.base
  -> EngineBootstrap: CoreAiGatewayMediaProvider(image/video)
  -> MediaModelCatalog 拉取 /api/hub/media-models
  -> 第一个会话: CliAgent 注册 generate_image/generate_video/get_video_status
用户: "画一只在雨里的猫"
  -> agent 调 generate_image {prompt}
  -> CoreAiGatewayMediaProvider POST /api/gateway/v1/images/generations
  -> GatewayMediaProvider 路由到 gpt-image-2.5，返回 b64/url + media_id
  -> GenerateImageTool.saveImage -> LocalArtifactSink
       -> {workspace}/.core-ai/media/images/image-<uuid>.png
       -> artifact event
  -> 工具结果: ![Generated image](绝对路径) + media_id
  -> Desktop 聊天渲染图片；Artifacts 面板出现新图
```

### 5.2 图生图 / media_id 继续编辑

```text
用户: "把这张图的背景换成海边" + 引用上一张图
  -> agent 从上一轮结果拿到 media_id（或用户在图库里选择）
  -> generate_image {
       prompt: "same cat, background becomes a seaside at dusk",
       input_images: [{"media_id": "gateway-media-v1.img.xxx", "name": "cat"}]
     }
  -> CoreAiGatewayMediaProvider 原样发送 media_id（ManagedReferenceProvider）
  -> GatewayMediaReferenceResolver 解析为上游可用表示
  -> 返回新图 + 新 media_id
```

如果 agent 使用 `"last"`：

- 有 §4.4 的 session header：解析“本会话最近一张”；
- 未实现 §4.4：解析“当前用户最近一张”，工具描述必须相应改写。

### 5.3 视频提交、轮询、落盘

```text
用户: "让这张图动起来"
  -> agent 调 generate_video {prompt, input_references: [{"media_id": ...}]}
  -> POST /api/gateway/v1/videos -> {id: "gateway-media-v1.vid.xxx", status: "queued"}
  -> ToolCallResult.pending(video_id)
  -> agent 调 get_video_status {video_id} / async_task_output
  -> GET /api/gateway/v1/videos/:id
       queued/processing -> PENDING
       completed -> GET /api/gateway/v1/videos/:id/content
  -> GetVideoStatusTool.saveVideo -> LocalArtifactSink
       -> {workspace}/.core-ai/media/videos/video-<id>.mp4
       -> artifact event
  -> 工具结果: [Download/play video](绝对路径)
  -> Desktop 聊天内联 <video controls>；Artifacts 面板可播放/seek
```

### 5.4 未登录 / 没有媒体模型

```text
未登录:
  -> 不派生 media.gateway.api.base
  -> 没有 provider -> 不注册媒体工具
  -> Settings: "Sign in from Hub..."

已登录但没有媒体模型（且没有显式配置 media.*.model）:
  -> catalog 返回空
  -> 不注册对应工具
  -> Settings: "No image/video models are enabled on this server..."
  -> agent 完全看不到这两个工具，不会浪费一次失败调用
```

## 6. 代码改动清单

### 6.1 core-ai-api

| 文件 | 改动 |
|---|---|
| `server/hubmodels/HubMediaModelWebService.java` | 新增 `GET /api/hub/media-models` 接口 |
| `server/hubmodels/ListHubMediaModelsResponse.java` | `imageModels / imageEditModels / videoModels` 三个 `List<HubMediaModelView>` |
| `server/hubmodels/HubMediaModelView.java` | `modelId / displayName / providerName / upstreamModel` |

### 6.2 core-ai-server

| 文件 | 改动 |
|---|---|
| `gateway/HubMediaModelService.java` | 分别映射 `GatewayRoutingEngine.mediaModelHints` 三个端点；每个列表内按 modelId 去重；补 displayName；保持 priority 顺序 |
| `gateway/HubMediaModelWebServiceImpl.java` | API 实现，`@PermissionsBypass`（认证即可读，与媒体网关一致） |
| `GatewayModule.java` | 注册 `HubMediaModelWebService` |
| `gateway/GatewayImageController.java` | （P2）`currentOwner(Request)` 带上 `clientSessionId` |
| `gateway/GatewayVideoController.java` | （P2）同上 |
| 测试 | `HubMediaModelServiceTest`、`GatewayImageControllerTest` / `GatewayVideoControllerTest` 增加 owner/session 用例 |

### 6.3 core-ai

| 文件 | 改动 |
|---|---|
| `llm/providers/LiteLLMMediaProvider.java` | 显式图片/视频 body；发送 `input_images` / `mask`；解析 `media_id` / `interaction_id` / `notes`；可配置 `acceptsImageReferences` flag（managed 子类传 true）；可配置超时 |
| `media/CoreAiGatewayMediaProvider.java` | 新增；继承 `LiteLLMMediaProvider`，实现 `ManagedReferenceProvider`；gateway 超时；client/session header |
| `tool/tools/GenerateImageTool.java` | 引用能力防御；编辑默认模型；URL 结果落 sink；多图落盘 |
| `tool/tools/GetVideoStatusTool.java` | builder 显式设置 10 min 超时（覆盖下载） |
| `tool/tools/GenerateVideoTool.java` | 失败提示补充 `media_id` / `sandbox_path`，与工具描述一致 |
| 测试 | `CoreAiGatewayMediaProviderTest`、`LiteLLMMediaProviderTest` 新增、`GenerateImageToolTest` 扩展、`GenerateVideoToolTest` 扩展 |

### 6.4 core-ai-cli

| 文件 | 改动 |
|---|---|
| `CliAppHelper.java` | `injectLiteLLMFallback` 派生 `media.gateway.api.base/key`；provider 选择顺序；新增 `mediaGatewayBase()` / `mediaGatewayKey()` |
| `appserver/EngineBootstrap.java` | 构造 `MediaModelCatalog`；暴露 managed gateway 配置（base/key/session factory） |
| `agent/CliAgent.java` | `Config` 增加 catalog；`mediaTools()` 按目录构建描述、注册矩阵；解析默认/编辑模型并写入 context；移除 image→video 兜底 |
| `media/MediaModelCatalog.java` | 新增；60s 缓存、last-known-good、默认模型选择 |
| `media/HubMediaModelClient.java` | 新增；`/api/hub/media-models` 客户端 |
| `appserver/EngineSessionRegistry.java` | 传入 catalog；（P2）按会话构造 managed provider（session header） |
| `appserver/AppServerEngine.java` | 新增 `media/get` / `media/set` RPC |
| `BootstrapCore.java` | 增加 `MediaModelCatalog` 字段（可为 null）；`mediaProvider` 仅在显式 `media.api.base` 时非 null |
| `CliApp.java` / `acp/AcpAgentRunner.java` | 传入 catalog（可为 null）；ACP 的 `injectLiteLLMFallback` 改为复用 `CliAppHelper`（补 `media.gateway.*` 派生）；两边都不要再把 image provider 推断成 shared media provider |
| 测试 | `MediaModelCatalogTest`、`CliAgentMediaToolsTest`、`AppServerMediaRpcTest`、`EngineBootstrapTest`（如已有） |

### 6.5 core-ai-desktop

| 文件 | 改动 |
|---|---|
| `src/renderer/app.ts` | 新增 Media generation 设置区块；`media/get` / `media/set`；`rewriteLocalVideoLinks()`；本地视频链接点击行为 |
| `src/renderer/styles.css` | 媒体设置与内联视频的少量样式 |
| `package.json` | `coreAi.pinnedEngine` 升到包含本次改动的 CLI 版本（发布时） |
| 文档 | `docs/cn/desktop.md` / `docs/en/desktop.md` 增加 Media generation 一节 |
| 测试 | 真实 Desktop + engine + server 的 E2E（见 §8.3） |

### 6.6 文档

- `docs/skills/core-ai-cli-manual/references/agent-properties.md`：补充 `media.gateway.api.base` 为“登录态派生、通常不需要手写”，以及 `media.image.edit.model`。
- `docs/cn/desktop.md` / `docs/en/desktop.md`：说明“登录后 agent 可以生成图片/视频，产物在 Artifacts 面板，模型在 Settings 选择”。
- 本文档。

## 7. 失败模式与用户可见信息

| 失败 | 工具结果 / Desktop 显示 | 用户动作 |
|---|---|---|
| 未登录 | 工具不存在；Settings 显示 `Sign in from Hub to use media generation` | 登录 |
| 已登录但目录 404（旧 server） | 工具仅在显式配置了 `media.image.model` / `media.video.model` 时出现；Settings 显示升级提示 | 升级 server 或配置模型 |
| 目录为空 | 未显式配置模型时对应工具不注册；Settings 显示没有启用的模型 | 管理员注册 gateway model |
| 401 | `Authentication failed. Sign in again from Hub.` | 重新登录 |
| 404 `/api/gateway/v1/...` | `Media gateway is not available on this server. Upgrade the server or configure media.*.` | 升级/配置 |
| 429 / 503 | 原样透传 `platform is at capacity, retry later` | 稍后重试 |
| 400 `image model is required` | `No image model selected. Choose one in Settings or pass model=...` | 设置默认模型 |
| 400 `gateway model does not support endpoint` | 原样透传，并提示“图生图请选择 [image-to-image] 模型” | 换模型 |
| 400 `media reference ...` | 原样透传 | 换 `media_id` / 文件 |
| 图片超时 | `Image generation timed out after 12 minutes; the task may still be running upstream.` | 重试/检查 server poll 设置 |
| 视频 failed | `Video generation failed: <error>` | 改 prompt/模型 |
| 视频下载失败 | 返回失败并保留 `video_id` | 再调 `get_video_status` |
| 磁盘写入失败 | 工具失败，带目标路径 | 清理磁盘/换 workspace |
| 审批被拒绝 | 工具不执行，Desktop 显示 denied | 重新发起并批准 |

原则：错误里必须出现**下一步**（登录、升级、选模型、换模型、重试），不要只返回 HTTP 状态码。

## 8. 测试计划

### 8.1 单元测试

**core-ai**

- `CoreAiGatewayMediaProviderTest`（本地 `com.sun.net.httpserver.HttpServer`）：
  - 图片请求 body 含 `model/prompt/input_images/media_id/mask/provider_extra`，`Authorization: Bearer`；
  - 响应解析 `data[].b64_json/url`、`media_id`、`interaction_id`、`notes`、`usage`；
  - 视频 `POST /videos`、`GET /videos/:id`、`GET /videos/:id/content`；
  - 错误状态带 server message，base64 被截断。
- `LiteLLMMediaProviderTest`（新增）：generic 模式仍不标记 managed；显式 body；`acceptsImageReferences` flag。
- `GenerateImageToolTest`：
  - managed provider 收到 `media_id`，不触发下载；
  - 非 managed provider + 引用图 → 明确失败（不再静默丢弃）；
  - `input_images` + 未指定 model → 使用 `media.image.edit.model`；
  - URL 结果 + sink → 落盘并返回本地路径；
  - 多图结果逐张落盘。
- `GenerateVideoToolTest`：请求 body 的 `input_references` 序列化；pending 状态不变。

**core-ai-cli**

- `MediaModelCatalogTest`：解析、端点分类、默认模型（目录顺序第一个）、60s 缓存、失败 last-known-good、未登录。
- `CliAgentMediaToolsTest`：注册矩阵（图片 only / 视频 only / 两者 / 目录空 / 目录 404；含显式 model 与未配置两种）；描述包含模型列表和默认值；无 image→video 兜底。
- `AppServerMediaRpcTest`：`media/get` / `media/set` 的 JSON 形状、空值删除、`restartRequired`、workspace override 提示。

**core-ai-server**

- `HubMediaModelServiceTest`：三个列表分别映射、列表内去重、displayName、priority 顺序、disabled 过滤、空目录。
- `GatewayImageControllerTest` / `GatewayVideoControllerTest`：session header → `MediaJobOwner.sessionId`；无 header 时保持 userId-only。

### 8.2 集成测试

- 用本地 stub gateway（HttpServer）跑 `EngineBootstrap -> CliAgent -> GenerateImageTool`：
  - 模拟 `/api/hub/media-models` 返回一个图片模型；
  - 模拟 `/api/gateway/v1/images/generations` 返回 b64 + media_id；
  - 断言工具注册、描述、结果、LocalArtifactSink 落盘与 artifact event。
- server 侧用现有 `GatewayImageControllerTest` / `GatewayVideoControllerTest` 的 mock provider 覆盖路由；不新增真实上游调用。

### 8.3 Desktop UI E2E（必须）

按项目规则，Desktop 改动必须做真实 UI 级验证：

1. 本地启动 core-ai-server（IDE Debug，7443/7070），在 gateway 管理端启用至少一个图片模型（`image.generations` + `image.edits`）和一个视频模型（`video.generations`）。
2. `npm --prefix core-ai-desktop run build`，启动 Desktop，登录同一个 server。
3. Settings → Media generation：确认 server URL、默认模型下拉、模型数量；切换默认图片模型，确认引擎重载且设置保留。
4. 新建会话，让 agent 生成一张图片：
   - 聊天里出现图片；
   - Artifacts 面板出现同一文件；
   - 工具结果里有 `media_id`；
   - 文件存在于 `{workspace}/.core-ai/media/images/`。
5. 用 `media_id` 让 agent 编辑上一张图：确认 server 端 `media_jobs` 有两条记录，第二张是新图而不是重新生成。
6. 生成视频：
   - 提交后工具卡片显示 pending；
   - `get_video_status` 完成后 Artifacts 面板出现 MP4；
   - 聊天里的视频可播放/seek；
   - 文件存在于 `{workspace}/.core-ai/media/videos/`。
7. 重启 Desktop：Artifacts 面板仍能看到两张图和视频（baseline 扫描）。
8. 登出：新建会话确认媒体工具消失；Settings 显示登录提示。
9. 负向：在 server 禁用全部媒体模型，重启引擎，确认工具不注册且 Settings 给出管理员提示。

### 8.4 静态检查

```text
./gradlew :core-ai:test --tests "ai.core.media.*" --tests "ai.core.tool.tools.GenerateImageToolTest"
./gradlew :core-ai-cli:test --tests "ai.core.cli.media.*" --tests "ai.core.cli.appserver.*"
./gradlew :core-ai-server:test --tests "ai.core.server.gateway.*"
npm --prefix core-ai-desktop run check
npm --prefix core-ai-desktop test
```

按模块质量门禁补充 `checkstyleMain` / `pmdMain` / `spotbugsMain`（core-ai 改动涉及媒体与工具代码）。

## 9. 发布与回滚

### 9.1 发布顺序

1. **server**：先发布 `/api/hub/media-models` 与 session header 支持；纯增量，不影响现有客户端。
2. **core-ai / core-ai-cli**：发布 provider、catalog、工具注册与 RPC；CLI 用户在登录后即可使用（不需要 Desktop）。
3. **core-ai-desktop**：升级 `coreAi.pinnedEngine`，发布带 Settings 与视频渲染的版本。
4. 文档与 release note 同步。

### 9.2 兼容矩阵

| server | engine | 行为 |
|---|---|---|
| 新（有目录 + gateway） | 新 | 完整体验 |
| 新（有目录） | 旧 | 旧引擎仍指向 `/api/cli/v1`，媒体调用 404；升级 engine |
| 旧（无目录，有 gateway） | 新 | 目录 404；有显式 `media.*.model` 时可用，否则工具隐藏 |
| 旧（无 gateway） | 新 | 目录 404 + 调用 404；Settings 提示升级或配置自定义端点 |

### 9.3 回滚

- 服务端新增接口可以保留，不影响回滚。
- Desktop 回滚到旧版本即可恢复原行为；旧版本的媒体工具仍会 404（这是现状，不是新回归）。
- 用户侧应急：在 `~/.core-ai/agent.properties` 显式配置 `media.image.api.base` / `media.video.api.base` 指向自己的端点。`media.gateway.api.base` 是运行时派生值、不在文件里，无法“删除配置”；显式 `media.*` 会按 §4.1.2 的优先级覆盖它。

### 9.4 可观测性

- 引擎启动日志（不含 key）：
  ```text
  media gateway configured: base=https://core-ai.example.com/api/gateway/v1, imageModels=2, videoModels=1
  media tools: generate_image=on, generate_video=on, get_video_status=on
  media model catalog unavailable: HTTP 404 (fallback: configured defaults only)
  ```
- 工具失败沿用 `GenerateImageTool` / `GenerateVideoTool` 的失败结果；Desktop 的 engine log 面板可看到。
- server 侧沿用 `media_jobs` 与 trace；本次不新增指标。

## 10. 决策记录与开放问题

| # | 决策 | 理由 |
|---|---|---|
| 1 | 媒体走 `/api/gateway/v1`，不新增 `/api/cli/v1` 媒体别名 | gateway 是既有的 OpenAI 形媒体面，controller 已具备 owner/reference/cost 逻辑；避免两套入口 |
| 2 | 用 `media.gateway.api.base` 派生而不是直接改 `litellm.api.base` | 用户自己的 LiteLLM 代理仍然可以同时服务 chat 和 media；派生值不写盘、不污染配置 |
| 3 | managed client 单独一个类，而不是让 `LiteLLMMediaProvider` 永远 managed | 通用 LiteLLM/OpenAI 端点没有 `media_jobs`，不能解析 `gateway-media-v1.*` |
| 4 | 媒体设置写 `~/.core-ai/agent.properties` | 与 CLI 共享同一套引擎与配置；避免 Desktop 私有配置造成行为分叉 |
| 5 | 目录返回 `upstreamModel` | CLI 复用 `MediaModelParameterHints`，与 server 端 agent 的描述完全一致；不含密钥 |
| 6 | URL 图片只在有 output sink 时落盘 | CLI 无 sink 的现有行为不变；Desktop/Server 需要持久化产物 |
| 7 | 默认模型 = 配置值 > 目录顺序第一个（priority 排序） | 与 `GatewayRoutingEngine` 对注册模型的实际顺序一致；用户可在 Settings 固定自己的默认，避免引入第二套 “isDefault” 语义 |
| 8 | 目录为空/未登录时不注册工具 | 避免模型看到不可用能力后编造调用；Settings 负责解释原因 |

开放问题（评审时确认）：

1. **会话级 `"last"`** 是否本期做？建议做（§4.4）。如果不做，必须同步改工具描述。
2. **`upstreamModel` 外露** 是否可接受？如果不可接受，改成 server 返回 `parameterHint`，CLI 不再依赖上游模型名。
3. **视频默认模型与引用**：如果用户默认的是 text-to-video 模型，但消息里带了参考图，agent 需要主动换 image-to-video 模型。工具描述已列出模型能力提示，但目录目前不返回 `maxImageReferences` / `video-in/video-out` 标记；是否在目录里补 `supportsImageReferences`，让 tool 能自动选择？可作为 P2。
4. **媒体任务取消**：目前 `generate_video` 没有取消工具；Desktop 用户可能希望“停止等待”。这是现有工具能力缺口，不在本次范围。
5. **成本展示**：工具结果目前不带 cost；server 的 `media_jobs` 已记录。Desktop 是否需要在 Artifacts 卡片上显示本次花费，需要产品确认。
