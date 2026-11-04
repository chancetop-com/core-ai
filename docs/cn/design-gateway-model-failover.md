# Gateway 模型故障切换（Model Failover）设计

> 状态：已实现（2026-11-04）
> 范围：`core-ai-server` 的 gateway 模型路由与 agent 运行时（`GatewayLLMProvider`）、gateway 模型管理 API、settings 前端
> 非范围：`/api/gateway/v1/*` 外部代理链路、媒体模型（image/video）、跨副本共享的健康状态

## 1. 背景

gateway 的默认模型是 DeepSeek。所有不指定模型的 agent（`AgentRunBuilder.resolveModel` 依次取 agent 配置 → 系统设置 → `GatewayRoutingEngine.defaultChatModelId()`）最终都会打到 DeepSeek。DeepSeek 服务商一旦出现故障（连接失败、超时、5xx、限流），这些 agent 会整体不可用，即便 gateway 里还注册着 Azure/Gemini 等可用模型。

目标：

1. 给模型配置**有序的备选模型列表**（fallback chain），主模型在“还没产出任何内容”前失败时，自动按顺序切到下一个模型。
2. 默认模型（不指定模型的 agent）只要在它上面配置了备选链，就能获得故障切换能力。
3. 切换必须是安全的：已经流式输出给调用方的请求不能重试，否则会出现重复内容。
4. 故障期间不要把每次请求都重新耗在主模型的超时上。

## 2. 配置模型

在 `gateway_model` 上新增字段：

```java
@Field(name = "fallback_model_ids")
public List<String> fallbackModelIds;
```

- 顺序即优先级：`[gpt-6.1-sol, gemini-3.8-pro]` 表示先试 GPT，再试 Gemini。
- 空 / 未配置 = 不启用故障切换（默认行为不变）。
- 只支持一级链：A 的列表就是完整顺序，不会递归展开 B 自己的列表，避免环和多级意外跳转。
- 管理 API（`GatewayModelRequest` / `GatewayModelView`）和 settings 的模型编辑面板都暴露该字段。
- 保存时校验：
  - 去空白、去重；
  - 不允许引用自己；
  - 每个 fallback 必须是一个已存在的 gateway 模型（防止拼写错误静默失效）；
  - 允许引用当前 disabled 的模型（运行时跳过，等它恢复后自动重新可用）。

管理员在“默认模型”那一行（`isDefault=true`，或优先级最高的 chat 模型）配置 fallback 链，就得到“默认模型挂了自动切换”的效果。

## 3. 运行时行为

入口是 `GatewayLLMProvider.doCompletionStream`（server 内所有 agent、subagent、CLI 会话、LLM call、记忆整理等最终都走它）。

```
resolveEndpoint(request) → 主路由 resolveRoute()
        │
        ├─ 主模型有 fallbackModelIds → 解析成 [主模型, fallback1, fallback2, ...]
        │     - fallback 必须 enabled、provider enabled、endpoint 兼容
        │     - 请求带图片时跳过 supportsVision=false 的模型
        │     - 请求带工具时跳过 supportsTools=false 的模型
        │     - 带 FILE/PDF 内容时 endpoint 已强制 responses，不兼容的 fallback 被跳过
        │
        └─ 顺序上把最近失败的模型排到最后（60s 冷却）
        │
        ▼
依次尝试；每次尝试前把 request 恢复到调用方原始状态（model/endpoint/temperature/
reasoning_effort/response_format/messages）→ 应用该模型自己的配置 → 调用上游
        │
        ├─ 成功 → 清除该模型健康标记，返回
        └─ 失败 → 判断是否可切换：
              - 本次尝试已经输出过 chunk/reasoning/tool/raw data/complete → 不切换，原样抛出
              - 调用已取消 → 不切换
              - 错误是请求级（400/404/413/422 等）→ 不切换
              - 否则记录失败，WARN 日志，继续下一个
```

### 3.1 哪些错误会触发切换

| 错误 | 切换 | 说明 |
|---|---|---|
| 连接失败 / DNS / TLS / 读写超时 / 流中断（`IOException`、`SocketTimeoutException`、`HTTPClientException` 无 status） | 是 | 典型的服务商不可用 |
| `statusCode=5xx` | 是 | 上游故障 |
| `statusCode=429` / `408` / `425` | 是 | 限流 / 超时 |
| `statusCode=401` / `403` | 是 | 该 provider 凭证/配置坏了，别的模型还能服务 |
| `statusCode=400` / `404` / `413` / `422` | 否 | 请求或模型名本身的问题，换模型只会浪费一次调用 |
| 流内 error event（`LLMStreamException`） | 视文案 | 命中 overloaded / timeout / rate limit / 服务不可用等瞬时模式才切换 |
| 取消（`CancellationException` / callback cancelled） | 否 | 用户意图 |
| `INVALID_URL` | 否 | 配置错误 |

### 3.2 输出安全

`OutputTrackingCallback` 包住本次尝试的 callback，记录是否已经向下游递送过任何内容：

- agent 链路：只看 `onChunk` / `onReasoningChunk` / `onTool` / `onToolComplete` / `onReasoningComplete` / `onComplete`；SSE 原始帧（`onRawData`）不算，因为 agent 不消费它。
- passthrough 链路（CLI 代理，`request.isPassthrough()`）：任何原始 SSE 帧都会直接透传给客户端，因此 `onRawData` 也算已输出。

一旦已经输出，再切换会在客户端产生重复的半截回答，所以保持原错误抛出。

### 3.3 60s 冷却

`GatewayModelFailover` 在进程内维护 `modelId → unhealthyUntil`：

- 可切换的失败发生后，该模型 60 秒内视为不健康；
- 构造尝试列表时把不健康模型稳定地排到最后；
- 之后 60 秒内的请求直接走 fallback，不再为主模型付一次超时；
- 任一尝试成功即清除该模型的标记；
- 冷却结束后自动重新探测主模型，不需要人工操作。

冷却只在单进程内生效（server 多副本各自独立）。这是有意的取舍：不需要额外存储，也不会因一次瞬时错误在集群范围内永久摘除模型。

### 3.4 请求状态恢复

一次尝试会就地改写 `CompletionRequest`（gateway alias → upstream model、endpoint 切换、reasoning effort 映射、JSON mode 降级时往 system message 追加 schema 指令、强制 temperature）。切换前用 `RequestSnapshot` 恢复：

- 标量字段直接恢复；
- messages 做浅深拷贝（Message / Content 复制一层，字符串与嵌套引用共享），保证每次尝试都从调用方原始消息开始；
- 尝试结束后无论成功失败都恢复 `request.model` / `request.endpoint`，调用方看到的仍是原始 alias。

### 3.5 计费与 trace 口径

- 失败尝试的 usage 不会返回；成功尝试的 usage 正常进入 `Agent.addLlmUsage`，但模型名仍是请求的 gateway alias（现有 gateway 计费本来按 alias 定价）。
- 因此当 fallback 模型与 alias 的价格不同时，记账按 alias 计价，可能与真实上游成本有偏差；如需精确计费，后续应把“实际服务模型”带回 response/trace（当前 `CompletionResponse` 没有 model 字段，属于跨模块改动）。
- 切换会打 WARN：`gateway model failed before producing output, switching to fallback, model=..., fallback=..., attempt=i/n, endpoint=..., error=...`。
- 每次尝试仍由上游自己的 `LiteLLMProvider` 做 3 次即时重试；failover 发生在这些重试全部失败之后。

## 4. 关键代码

| 文件 | 职责 |
|---|---|
| `GatewayModelConfig` | `fallbackModelIds` 持久化字段 |
| `GatewayModelRequest` / `GatewayModelView` | 管理 API 字段 |
| `GatewayModelFallbackSupport` | 归一化、存在性/自引用校验、在 `apply` 中落库 |
| `GatewayRoutingEngine.fallbackModels` | 按配置顺序解析 fallback 模型（含重复过滤） |
| `GatewayModelFailover` | 错误分类、输出追踪、请求快照、60s 冷却 |
| `GatewayLLMProvider` | 尝试链构造、健康排序、逐次执行与恢复 |
| `core-ai-frontend/.../GatewayProviders.tsx` | 模型编辑面板的 Failover Models 输入框 |

## 5. 测试

- `GatewayModelFailoverTest`：错误分类、输出追踪（agent vs passthrough）、请求快照恢复。
- `GatewayLLMProviderTest`：主模型 503 切到 fallback、按顺序切换、已有输出不切换、400 不切换且不冷却、disabled fallback 跳过、vision 不兼容跳过、冷却后第二次请求直接走 fallback。
- `GatewayRoutingEngineTest`：fallback 顺序、去重、未配置返回空。
- `GatewayModelServiceTest`：保存顺序、自引用拒绝、未知模型拒绝。
- `core-ai-server:checkstyleMain/Test`、前端 `tsc -b`。

## 6. 限制与后续

1. `/api/gateway/v1/chat/completions`、`/responses` 外部代理还没有复用这条链（它们自己处理 SSE 透传和 trace，需要单独的重放安全设计）。
2. 媒体模型（image/video）的故障切换未实现；fallback 字段目前只被 chat/agent 链路消费。
3. 冷却状态是进程内的，多副本不共享；如需集群级熔断，可把 `GatewayModelFailover` 的健康表迁到 Redis/Mongo。
4. 没有主动健康检查/预热；只有真实请求失败才触发切换。
5. 一次故障切换仍可能先消耗主模型的完整上游超时（默认至少 600s 的 reasoning timeout）；冷却只能避免后续请求重复付这个成本。若需要更快切换，可后续给 fallback 链加“首包超时”或异步探测。
