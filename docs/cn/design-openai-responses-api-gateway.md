# OpenAI Responses API 桥接支持（LLM Gateway 子项目 1）

## 背景

长期目标是用 core-ai-server 自己的 LLM Gateway 生态替换掉外部 litellm proxy 依赖。这是一个跨多个子系统的大项目，已拆分为以下建设顺序：

1. **Responses API 桥接支持（本文档范围）**——在不改动现有 litellm 链路的前提下，新增 OpenAI Responses API 入站方言支持：请求在边缘转换为 Chat Completions 走现有 provider 链路，响应再转换回 Responses API 形状。采用 litellm 同款架构（内部统一 canonical 格式，边缘做方言转换），所有已接入 provider 的模型同时生效。
2. **多 Provider Dispatch 层**（未来独立设计）——`LiteLLMProvider` 改名为 `OpenAICompatibleProvider`，新增原生 `AnthropicProvider`，构建 `RoutingTable` + `GatewayDispatcher` 实现按模型路由、跨 provider fallback、单 provider 多 key 负载均衡，修复 `AZURE_INFERENCE` 未注册的死代码，迁移 `/api/litellm/v1/chat/completions` 到新的 gateway 路由；同时为 OPENAI 类型增加 Responses API 原生透传双模式（litellm 完全体：OpenAI 直连 `/responses` 保真透传，其他 provider 走本子项目的桥接），补齐加密 reasoning items 多轮延续、内置工具、stateful 会话等桥接无法覆盖的保真度。
3. **Auth & Governance**（未来独立设计）——虚拟 Key / 多租户配额管理 + 限流，共享同一套 key→tenant→配额数据模型。
4. **Cost Tracking & Observability**（未来独立设计）——按 key/租户/模型的 token 用量与成本统计，依赖子项目 3 的 key/tenant 模型作为归因维度，优先复用现有 trace/span 基础设施而非另起统计系统。

子项目 2-4 只在此记录建设顺序和大致边界，不在本文档详细设计，后续各自单独走一轮 brainstorming → spec。

## 本文档范围（子项目 1）

**目标**：core-ai-server 新增一条 Responses API 形状的入站路由（`POST /api/gateway/v1/responses`），内部桥接为 Chat Completions 调用现有 `LLMProvider` 链路。客户端可以拿 OpenAI 新版 SDK（默认 Responses API）把 base_url 指向 core-ai-server，访问网关后面的任意模型（deepseek、azure、openrouter 等），不限于 OpenAI。

桥接能力整体落在 core-ai 框架层（`ResponsesBridge` 门面），core-ai-server 只做 HTTP/SSE 接线——框架块可独立交付、脱离 HTTP 端到端测试，也便于未来 CLI/SDK 复用。

**非目标（明确排除）**：
- 不改动 `LiteLLMProvider` 类——桥接只调用其公开的 `completionStream` 入口，源文件零接触。
- 不改动 `/api/litellm/v1/chat/completions` 现有路由。
- 不做 OpenAI 原生 `/responses` 透传——桥接与透传不互斥，透传作为 OPENAI 类型的保真度增强留给子项目 2 的双模式。
- 不做 stateful 会话：`previous_response_id` 无法在无存储的桥接层兑现，直接 400 拒绝；`store` 忽略（OpenAI 侧该字段默认为 true，拒绝会误伤所有未显式设置的请求）。
- 不做 provider 内置工具（`web_search`/`code_interpreter`/`file_search` 等 type 非 `function` 的工具直接 400），只支持客户端自定义 function 工具，多轮对话历史由调用方自己维护并整段传入。
- 不做音频输入（`input_audio` → 400）——内部 `Content` 模型尚未支持 AUDIO（有 //todo 记录），等模型层补齐后再加映射。图片与文件输入**在范围内**（模型已支持，纯映射工作）。
- 不做非流式：`stream` 非 true 直接 400。现有 SSE 路由本来只服务流式客户端，非流式需要另一条普通 JSON 路由，留给后续按需补。
- 不做按模型路由、跨 provider fallback、多 key 负载均衡（`LLMProviderType`/`LLMProviders` 注册表不改，provider 选择与现有路由一致取默认 provider）。
- 不涉及虚拟 Key、限流、成本追踪——鉴权处理沿用现状（提取但不强制），不做任何改进也不做任何退化。

## 现状（决定本设计取舍的关键事实）

- `LiteLLMProxyChannelListener`（`core-ai-server/.../web/sse/LiteLLMProxyChannelListener.java`）是现有入站 SSE 路由的参照实现：解析 body 为 `CompletionRequest` → `llmProviders.getProvider().completionStream(request, callback, null, false)`（默认 provider、不开 tracing）→ callback 的 `onRawData` 把上游 SSE 载荷原样转发 → 结束补发 `[DONE]`。鉴权信息（`AuthContext.userId()`）只用于日志，不做强制校验。
- `LLMProvider.completionStream` 内部已经完整具备"整体请求重试（`MAX_RETRIES=3`、3 秒退避）、失败但已有部分内容时打 `[interrupted]` 标记返回、异步/缓冲回调包装"（实现在 `LiteLLMProvider.executeSSERequest`）。桥接方案通过这个公开入口全部免费复用——之前直连方案里"在新客户端复制一份重试逻辑"的取舍不复存在。
- `StreamingCallback.onRawData(String sseData)` 逐条拿到上游 SSE 的 data 载荷（即每个 `chat.completion.chunk` 的 JSON 字符串），是流式事件合成器的天然输入。
- `RawSseChannel.sendRawData`（实现在 `PatchedChannelImpl.java:99`）只会把内容包成 `data: <data>\n\n`。Responses API 的事件流要求 `event: <name>` 命名行（如 `event: response.output_text.delta`），因此需要在 `RawSseChannel`/`PatchedChannelImpl` 新增一个 `sendRawEvent(String event, String data)`。
- `LLMProvider` 抽象类的方法签名围绕 Chat Completions 语义设计，与 Responses API 的请求/响应结构（`input`/`instructions`，输出 `items` 而非 `choices`）对不上——这正是选择"边缘转换、内部保持单一 canonical 格式"而不是"抽象层长出第二套协议"的原因。
- **内部 canonical 模型已支持多模态**：`Message.content` 是 `List<Content>`，`Content` 支持 `TEXT`/`IMAGE_URL`/`FILE` 三种 part，其中 `FileContent` 的 `file_id`/`filename`+`file_data` 形状与 Chat Completions 的 file part 完全一致；现有 agent 附件/图片链路已在现网验证过 parts 的上游序列化。两个已知缺口：`AUDIO`/`VIDEO` 尚未支持（`Content.java` 中有 //todo 记录）；`ImageUrl` 只有 `url`/`format` 字段，没有 `detail`。

## 架构

### `core-ai` 框架层新增

框架层以一个门面类承载全部桥接能力：**`ResponsesRequest` 进 → Responses 事件流 + 最终 `ResponsesResponse` 出**。校验、转换、调 provider、事件合成、终止事件的编排都在框架内完成，server 不参与任何桥接逻辑。

**`ai.core.llm.domain.responses` 包（新增，纯 DTO）**
贴 OpenAI Responses API 真实 JSON 形状的领域模型：`ResponsesRequest`、`ResponsesResponse`、输入/输出 item（含 `input_text`/`input_image`/`input_file` 等 content part 类型）、流式事件等支撑类型。与现有 `CompletionRequest`/`CompletionResponse` 平级、独立，不复用也不强行统一。类型较多，拆成子包内多个聚焦小文件（遵守单文件 ≤500 行约束）。

**`ai.core.llm.responses.ResponsesBridge`（新文件，门面/编排器）**
框架对外的唯一入口：`ResponsesResponse stream(ResponsesRequest request, ResponsesEventListener listener)`，`ResponsesEventListener` 是 `onEvent(String type, String dataJson)` 形状的函数式接口。构造时注入一个 `LLMProvider` 实例（不感知实例来自哪里）。内部编排：策略字段校验（不通过即抛校验异常，此时尚未发出任何事件）→ `ResponsesRequestMapper` 转换 → `provider.completionStream(...)`（与现有入站路由一致不开 tracing；内部回调的 `onRawData` 喂 `ResponsesStreamSynthesizer`，合成事件实时转给 listener）→ 返回后产出 `response.completed` 并返回最终响应对象。

**`ai.core.llm.responses.ResponsesRequestMapper`（新文件，纯函数，bridge 内部件）**
`ResponsesRequest` → `CompletionRequest` 的完整映射规则：
- `instructions` → SYSTEM message；`input` 为纯字符串时 → 单条 USER 文本 message。
- message item 角色映射：`user`/`assistant`/`system` 原样对应，`developer` → SYSTEM。
- **多模态 content part 映射（落在现有 `Content` 模型上，不发明模型没有的能力）**：`input_text` → `TEXT`；`input_image` → `IMAGE_URL`（Responses 侧 `image_url` 是字符串，URL 与 data URI 均直填 `ImageUrl.url`，`detail` 透传到 `ImageUrl` 新增的可选 `detail` 属性）；`input_file` → `FILE`（`file_id`/`filename`+`file_data` 直接对位，`file_url` 沿用现有 `FileContent.ofUrl` 放 `file_id` 字段的约定，与现网 litellm 链路行为一致）；assistant 历史消息的 `output_text` part → `TEXT`。
- 工具历史映射：`function_call` item → ASSISTANT message 的 `toolCalls`（`call_id`/`name`/`arguments` 对位 `FunctionCall`）；`function_call_output` item → TOOL message（`call_id` → `toolCallId`）。
- 工具定义：Responses 扁平格式 → Chat Completions 嵌套 function 格式；`max_output_tokens` 等采样参数对位。
- 策略字段校验（见"错误处理"）也在此层实现，由 bridge 在入口处调用。

**`ai.core.llm.responses.ResponsesResponseMapper`（新文件，纯函数，bridge 内部件）**
`CompletionResponse` → `ResponsesResponse`：`choices` → output items（文本 → message item，toolCalls → function_call items），usage → `input_tokens`/`output_tokens`。供 `response.completed` 终止事件内嵌完整响应对象使用。

**`ai.core.llm.responses.ResponsesStreamSynthesizer`（新文件，每请求一个实例的状态机，bridge 内部件）**
本方案最大的一块新代码。输入：逐条 `chat.completion.chunk` JSON（来自 `onRawData`）；输出：`(eventName, dataJson)` 序列。职责：生成 `resp_`/`msg_`/`fc_` 前缀的 id 与单调递增的 `sequence_number`；开场合成 `response.created`/`response.in_progress`；文本流合成 `response.output_item.added` → `response.content_part.added` → `response.output_text.delta`… → `response.output_text.done` → `response.content_part.done` → `response.output_item.done`；工具调用流合成 `response.function_call_arguments.delta`/`.done` 一组；收尾用 `ResponsesResponseMapper` 把 `completionStream` 的返回值（权威最终内容与 usage，含可能的 `[interrupted]` 标记）转成 `response.completed` 事件。

**`ai.core.sse.RawSseChannel` / `ai.core.sse.internal.PatchedChannelImpl`（修改）**
新增 `sendRawEvent(String event, String data)`，写出 `event: <name>\ndata: <data>\n\n`。现有 `sendRawData` 行为不变。

**`ai.core.llm.domain.Content.ImageUrl`（修改，纯加性）**
新增可选 `detail` 属性（`low`/`high`/`auto`）——`input_image.detail` 直接影响视觉 token 计费，语法网关不能静默丢弃。字段可空、不设置时不序列化，对现有链路零影响。`Content` 中 AUDIO/VIDEO 的 //todo 记录保留不动。

**`ai.core.llm.domain.CompletionRequest`（修改，纯加性）**
现有字段里没有输出上限和 top_p 的表达位（只有 `temperature`），而 `max_output_tokens` 是成本控制的关键旋钮，静默丢弃违反校验原则。新增可空 `top_p`、`max_completion_tokens` 两个 `@Property` 字段——JSON 序列化为 NON_NULL，不设置时对现有上游请求体零影响。

**零改动清单**：`LLMProvider`、`LiteLLMProvider`、`LLMProviderType`、`LLMProviders`、`AgentBootstrap` 全部不动——桥接不需要任何新配置项，用的就是已配置的默认 provider。

### `core-ai-server` 平台层新增

**`ai.core.server.web.sse.ResponsesChannelListener`（新文件）**
薄接线层，不含桥接逻辑：解析 body 为 `ResponsesRequest` → 用 `llmProviders.getProvider()`（默认 provider，与现有路由一致）构造 `ResponsesBridge` → `bridge.stream(request, (type, data) -> rawChannel.sendRawEvent(type, data))` → 返回后关闭 channel。校验异常转 `BadRequestException`（400）。鉴权处理与现状保持一致（提取但不强制）。

**新路由**：`POST /api/gateway/v1/responses`，在 `ServerModule` 用 `sseConfig.listen` 注册（参照 `ServerModule.java:240` 现有写法）。使用 `gateway` 前缀为子项目 2 的路由迁移预留空间；全新路由，无存量调用方。

## 数据流

1. 客户端 POST `/api/gateway/v1/responses`，body 为 OpenAI Responses API 形状（`stream=true`）。
2. `ResponsesChannelListener` 解析为 `ResponsesRequest`，调用 `ResponsesBridge.stream(...)`，事件回调直连 `rawChannel.sendRawEvent`。
3. bridge 入口校验策略字段，`ResponsesRequestMapper` 转换为 `CompletionRequest`，调用 `provider.completionStream(...)`——重试、退避、`[interrupted]`、流缓冲全部由现有链路承担，桥接层零新增重试代码。
4. 上游每个 `chat.completion.chunk` 经 `onRawData` 进入 `ResponsesStreamSynthesizer`，合成的 Responses API 事件序列实时回调给 listener 经 `sendRawEvent` 转发。
5. `completionStream` 返回后，bridge 产出 `response.completed`（内嵌 `ResponsesResponseMapper` 转换的完整响应）并返回最终对象，listener 关闭 channel。

## 错误处理

- 上游请求级失败的重试语义完全继承 `completionStream`（重试 3 次、3 秒退避；部分内容场景打 `[interrupted]` 后正常返回，桥接层照常走到 `response.completed`）。
- `completionStream` 抛出异常（重试耗尽且无内容）时，bridge 合成 `response.failed` 终止事件并返回 `status=failed` 的最终对象，listener 正常关闭 channel，保证客户端 SDK 能正常终止而不是挂死。
- 策略字段校验在 bridge 入口、发出任何事件之前完成，原则：**无法兑现且忽略会静默改变语义的 → 400；纯 advisory 的 → 忽略 + 日志**。
  - 400 拒绝：`previous_response_id`、`item_reference` item（都依赖服务端存储）；`stream` 非 true；工具列表含 type 非 `function` 的项；content 含 `input_audio`（canonical 模型未支持 AUDIO）。
  - 忽略 + 日志：`store`、`include`；`reasoning` item——Chat Completions 无法回传 reasoning 历史，丢弃后上游自行重新推理，这是桥接的固有语义；agentic 客户端会整段回传上一轮输出，若拒绝会直接打断工具调用循环。
  - 校验异常由 listener 转成 `BadRequestException`（400）。
- 无跨 provider fallback——这是子项目 2（Dispatch 层）的范围。

## 测试策略

- `ResponsesRequestMapper` 单元测试：instructions/input/多轮历史/工具定义/参数的映射正确性；多模态 part 映射（`input_image` 的 URL 与 data URI 两种形态及 `detail` 透传、`input_file` 的 `file_id`/`file_data`/`file_url` 三种形态）；策略字段各分支（`previous_response_id` 400、内置工具 400、非流式 400、`input_audio` 400、`store`/`include`/`reasoning` item 忽略）。
- `ResponsesResponseMapper` 单元测试：纯文本、工具调用、usage 三类映射。
- `ResponsesStreamSynthesizer` 单元测试：给定 chunk 序列断言完整事件序列（纯文本流、工具调用流、文本+工具混合、`[interrupted]` 场景）；`sequence_number` 单调递增；item id 在同一响应内稳定。这是测试投入的重心。
- `ResponsesBridge` 端到端单元测试：用桩 `LLMProvider`（脚本化地经 `onRawData` 回放 chunk 序列并返回最终 `CompletionResponse`）驱动完整桥接流程，断言事件序列与最终响应；覆盖校验拒绝（不发任何事件）与上游异常转 `response.failed` 两个分支。框架块凭这一层即可脱离 HTTP 完整验收。
- `ResponsesChannelListener` 集成测试：参照现有覆盖 `/api/litellm/v1/chat/completions` 的 SSE 集成测试模式，新增一份打 `/api/gateway/v1/responses`，断言 `event:` 命名行框架正确。
- 所有测试代码注释使用英文。

## 未来子项目的边界（供后续设计参考，不在本次实现范围）

- **子项目 2**：`LiteLLMProvider` → `OpenAICompatibleProvider` 改名；新增 `AnthropicProvider`；`LLMProviders` 注册表从 `EnumMap<Type, LLMProvider>` 扩展为 `EnumMap<Type, List<LLMProvider>>` 支持多 key 轮询；`RoutingTable` + `GatewayDispatcher` 实现按模型前缀路由、跨候选 fallback（429/5xx/超时触发，4xx 不触发，已开始流式输出后不触发）、启动时校验路由表引用的 provider 已注册（顺带修复 `AZURE_INFERENCE` 死代码）；OPENAI 类型的 Responses API 原生透传双模式（补齐加密 reasoning items 延续、内置工具、stateful）；`/api/litellm/v1/chat/completions` 迁移到 `/api/gateway/v1/chat/completions`，旧路径保留一版过渡别名，`core-ai-cli` fallback 地址同步更新。
- **子项目 3**：虚拟 Key / 多租户配额管理 + 限流，共享 key→tenant→配额数据模型。
- **子项目 4**：按 key/租户/模型的成本追踪，依赖子项目 3 的 key/tenant 模型，优先复用现有 trace/span 基础设施。
