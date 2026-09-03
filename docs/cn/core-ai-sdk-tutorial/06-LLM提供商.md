# 06 - LLM 提供商

> **学习目标**：深入理解 LLM 提供商的抽象机制、SPI 形态、多 vendor 支持，以及如何接入不同的 LLM 服务。
>
> **预计时间**：1.5 天
>
> **前置要求**：完成 [05-工具系统](./05-工具系统.md)

---

## 📋 本章内容

- [6.1 LLM 系统总览](#61-llm-系统总览)
- [6.2 LLMProvider 抽象基类](#62-llmprovider-抽象基类)
- [6.3 LLMProviders 注册表](#63-llmproviders-注册表)
- [6.4 LiteLLMProvider 实现](#64-litellmprovider-实现)
- [6.5 领域模型](#65-领域模型)
- [6.6 流式调用](#66-流式调用)
- [6.7 多模态支持](#67-多模态支持)
- [6.8 验证学习成果](#68-验证学习成果)

---

## 6.1 LLM 系统总览

### 6.1.1 LLM 系统的角色

LLM 系统是 agent 的"大脑"，负责：
- **文本生成**：根据上下文生成回答
- **工具调用**：识别需要调用的工具和参数
- **向量嵌入**：把文本转成向量（用于 RAG）
- **重排序**：对检索结果重排序（提高 RAG 质量）
- **图像理解**：理解图像内容（多模态）

💡 **设计意图**：LLM 系统是 agent 的核心能力，通过抽象屏蔽不同 vendor 的差异，让 agent 可以无缝切换 LLM。

### 6.1.2 核心类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                   LLMProvider (abstract)                     │
│                                                             │
│  - config (LLMProviderConfig)                               │
│  - tracer (LLMTracer)                                       │
│  - modalityRegistry (ModelModalityRegistry)                 │
│                                                             │
│  + completion(request): CompletionResponse       ← 文本生成 │
│  + completionFormat(request, clazz): T           ← 结构化输出│
│  + embedding(request): EmbeddingResponse         ← 向量嵌入 │
│  + reranking(request): RerankingResponse         ← 重排序   │
│  + captionImage(request): CaptionImageResponse   ← 图像描述 │
│  + completionStream(request, callback)           ← 流式调用 │
└──────────────────────┬──────────────────────────────────────┘
                       │ implements
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                   LiteLLMProvider                            │
│                                                             │
│  - baseUrl, apiKey                                          │
│  - httpClient                                               │
│                                                             │
│  + completion(request): CompletionResponse                  │
│    // 调 LiteLLM API 或 OpenAI/Azure/DeepSeek API          │
│                                                             │
│  + completionFormat(request, clazz): T                      │
│    // 设置 response_format = JSON schema                    │
│    // 解析 JSON 到目标类型                                   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                   LLMProviders                               │
│                                                             │
│  - providers: Map<id, LLMProvider>              ← 注册表    │
│                                                             │
│  + register(id, provider)                                   │
│  + get(id): LLMProvider                                     │
│  + getDefault(): LLMProvider                                │
└─────────────────────────────────────────────────────────────┘
```

### 6.1.3 LLM 调用流程

```
Agent.turn()
  │
  ├─ 1. ModelGateway.handLLM()
  │      │
  │      ├─ lifecycle.beforeModel(request, context)
  │      │
  │      ├─ llmProvider.completion(request)  ← 调 LLM
  │      │      │
  │      │      ├─ 构建 HTTP 请求（OpenAI 兼容格式）
  │      │      ├─ 发送请求到 LLM API
  │      │      ├─ 解析响应
  │      │      └─ 返回 CompletionResponse
  │      │
  │      ├─ lifecycle.afterModel(request, response, context)
  │      │
  │      └─ lifecycle.onModelResponse(request, response, context)
  │             │
  │             └─ 返回 null（接受）或 消息列表（重试）
  │
  └─ 2. 返回 Choice（message + finishReason）
```

---

## 6.2 LLMProvider 抽象基类

### 6.2.1 文件位置

```
core-ai/src/main/java/ai/core/llm/LLMProvider.java
```

### 6.2.2 类定义

```java
public abstract class LLMProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LLMProvider.class);
    
    protected LLMTracer tracer;
    protected ModelModalityRegistry modalityRegistry = SeedModelModalityRegistry.INSTANCE;
    public LLMProviderConfig config;

    public LLMProvider(LLMProviderConfig config) {
        this.config = config;
    }

    // ========== 核心方法 ==========
    
    /**
     * 文本生成（核心方法）
     * 根据消息历史生成回答
     */
    public abstract CompletionResponse completion(CompletionRequest request);
    
    /**
     * 结构化输出（核心方法）
     * 生成符合指定类型的 JSON 对象
     */
    public abstract <T> T completionFormat(CompletionRequest request, Class<T> clazz);
    
    /**
     * 向量嵌入（核心方法）
     * 把文本转成向量（用于 RAG）
     */
    public abstract EmbeddingResponse embedding(EmbeddingRequest request);
    
    /**
     * 重排序（核心方法）
     * 对检索结果重排序（提高 RAG 质量）
     */
    public abstract RerankingResponse reranking(RerankingRequest request);
    
    /**
     * 图像描述（核心方法）
     * 理解图像内容（多模态）
     */
    public abstract CaptionImageResponse captionImage(CaptionImageRequest request);
    
    // ========== 流式调用 ==========
    
    /**
     * 流式 completion（同步）
     */
    public void completionStream(CompletionRequest request, StreamingCallback callback) {
        throw new UnsupportedOperationException("Streaming not supported");
    }
    
    /**
     * 流式 completion（异步）
     */
    public void completionStreamAsync(CompletionRequest request, AsyncStreamingCallback callback) {
        throw new UnsupportedOperationException("Async streaming not supported");
    }
    
    // ========== 辅助方法 ==========
    
    /**
     * 带附件的结构化输出
     */
    public final <T> T completionFormatAttachedContent(String systemPrompt, String query, 
                                                        AttachedContent attachedContent, 
                                                        String model, Class<T> clazz) {
        var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            List.of(Message.of(RoleType.SYSTEM, systemPrompt),
                    AgentHelper.buildUserMessage(query, attachedContent)),
            null, null, model, null, Boolean.FALSE, ResponseFormat.of(clazz), null
        ));
        return completionFormat(request, clazz);
    }
    
    /**
     * 简单的结构化输出
     */
    public final <T> T completionFormat(String systemPrompt, String userPrompt, 
                                         String model, Class<T> clazz) {
        return completionFormat(systemPrompt, userPrompt, model, clazz, null);
    }
    
    public final <T> T completionFormat(String systemPrompt, String userPrompt, 
                                         String model, Class<T> clazz, Integer timeoutSeconds) {
        return completionFormat(systemPrompt, userPrompt, model, clazz, ResponseFormat.of(clazz), timeoutSeconds);
    }
    
    public final <T> T completionFormat(String systemPrompt, String userPrompt, 
                                         String model, Class<T> clazz, ResponseFormat responseFormat, 
                                         Integer timeoutSeconds) {
        var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            List.of(Message.of(RoleType.SYSTEM, systemPrompt),
                    Message.of(RoleType.USER, userPrompt)),
            null, null, model, null, Boolean.FALSE, responseFormat, null
        ));
        request.setTimeoutSeconds(timeoutSeconds);
        return completionFormat(request, clazz);
    }
    
    // ========== Getter/Setter ==========
    
    public void setTracer(LLMTracer tracer) {
        this.tracer = tracer;
    }
    
    public LLMTracer getTracer() {
        return tracer;
    }
    
    public void setModalityRegistry(ModelModalityRegistry modalityRegistry) {
        this.modalityRegistry = modalityRegistry;
    }
    
    public ModelModalityRegistry getModalityRegistry() {
        return modalityRegistry;
    }
}
```

### 6.2.3 核心方法详解

| 方法 | 作用 | 使用场景 |
|---|---|---|
| `completion(request)` | 文本生成 | Agent 主循环、对话 |
| `completionFormat(request, clazz)` | 结构化输出 | JSON schema、工具定义解析 |
| `embedding(request)` | 向量嵌入 | RAG 检索、Memory 存储 |
| `reranking(request)` | 重排序 | RAG 结果优化 |
| `captionImage(request)` | 图像描述 | 多模态理解 |
| `completionStream(request, callback)` | 流式调用 | 实时输出、聊天界面 |

💡 **设计意图**：
- **completion**：最基础的方法，生成文本回答
- **completionFormat**：生成结构化数据（JSON），自动解析到目标类型
- **embedding**：把文本转成向量，用于相似度搜索
- **reranking**：对检索结果重排序，提高 RAG 质量
- **captionImage**：理解图像内容，用于多模态场景

### 6.2.4 结构化输出原理

`completionFormat` 的工作流程：

```
completionFormat(request, clazz)
  │
  ├─ 1. 设置 response_format = JSON schema
  │      └─ 根据 clazz 生成 JSON schema
  │
  ├─ 2. 调 completion()
  │      └─ LLM 返回 JSON 字符串
  │
  └─ 3. 解析 JSON 到目标类型
         └─ JsonUtil.fromJson(response, clazz)
```

**示例**：

```java
// 定义目标类型
public class PersonInfo {
    public String name;
    public int age;
    public String city;
}

// 调结构化输出
var person = llmProvider.completionFormat(
    "你是一个信息提取助手",
    "张三，28岁，住在北京",
    "gpt-4",
    PersonInfo.class
);

// person.name = "张三"
// person.age = 28
// person.city = "北京"
```

💡 **设计意图**：结构化输出让 LLM 返回的数据可以直接用 Java 对象处理，无需手动解析 JSON。

---

## 6.3 LLMProviders 注册表

### 6.3.1 文件位置

```
core-ai/src/main/java/ai/core/llm/LLMProviders.java
```

### 6.3.2 类定义

```java
public class LLMProviders {
    private final Map<String, LLMProvider> providers = new ConcurrentHashMap<>();

    public void register(String id, LLMProvider provider) {
        providers.put(id, provider);
    }

    public LLMProvider get(String id) {
        return providers.get(id);
    }

    public LLMProvider getDefault() {
        return providers.values().iterator().next();  // 取第一个
    }

    public boolean has(String id) {
        return providers.containsKey(id);
    }

    public Set<String> ids() {
        return providers.keySet();
    }
}
```

### 6.3.3 使用方式

```java
// 注册多个 provider
var providers = new LLMProviders();
providers.register("openai", openAIProvider);
providers.register("azure", azureProvider);
providers.register("deepseek", deepSeekProvider);

// 获取默认 provider
var defaultProvider = providers.getDefault();

// 获取指定 provider
var openaiProvider = providers.get("openai");

// Agent 使用指定 provider
var agent = Agent.builder()
    .llmProvider(providers.get("deepseek"))  // 用 DeepSeek
    .build();
```

💡 **设计意图**：
- **多 provider 共存**：可以同时注册多个 LLM provider
- **按需选择**：Agent 可以按名字选择 provider
- **默认 provider**：不指定时用第一个注册的

---

## 6.4 LiteLLMProvider 实现

### 6.4.1 文件位置

```
core-ai/src/main/java/ai/core/llm/providers/LiteLLMProvider.java
```

### 6.4.2 设计思路

LiteLLM 是一个开源项目，提供统一的 API 接口访问多种 LLM vendor。`LiteLLMProvider` 通过 LiteLLM 代理访问 OpenAI、Azure、DeepSeek 等多种 LLM。

💡 **设计意图**：
- **统一接口**：不需要为每个 vendor 写实现
- **易于扩展**：新增 vendor 只需在 LiteLLM 配置
- **兼容 OpenAI**：所有请求都转换成 OpenAI 兼容格式

### 6.4.3 核心实现（简化版）

```java
public class LiteLLMProvider extends LLMProvider {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public LiteLLMProvider(LLMProviderConfig config, String baseUrl, String apiKey) {
        super(config);
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        // 1. 转换成 OpenAI 兼容的请求格式
        var openAIRequest = toOpenAIRequest(request);
        
        // 2. 构建 HTTP 请求
        var httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(openAIRequest)))
            .build();
        
        // 3. 发送请求
        try {
            var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("LLM API error: " + httpResponse.body());
            }
            
            // 4. 解析响应
            return parseResponse(httpResponse.body());
        } catch (Exception e) {
            throw new RuntimeException("Failed to call LLM API", e);
        }
    }

    @Override
    public <T> T completionFormat(CompletionRequest request, Class<T> clazz) {
        // 1. 设置 response_format 为 JSON schema
        request.setResponseFormat(ResponseFormat.of(clazz));
        
        // 2. 调 completion
        var response = completion(request);
        
        // 3. 解析 JSON 到目标类型
        var content = response.choices.get(0).message.content;
        return JsonUtil.fromJson(content, clazz);
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest request) {
        // 1. 构建请求
        var embeddingRequest = Map.of(
            "input", request.texts,
            "model", config.getModel()
        );
        
        // 2. 发送请求
        var httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/embeddings"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(embeddingRequest)))
            .build();
        
        try {
            var httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("Embedding API error: " + httpResponse.body());
            }
            
            // 3. 解析响应
            return parseEmbeddingResponse(httpResponse.body());
        } catch (Exception e) {
            throw new RuntimeException("Failed to call embedding API", e);
        }
    }

    @Override
    public RerankingResponse reranking(RerankingRequest request) {
        // 类似实现，调 reranking API
        // ...
        throw new UnsupportedOperationException("Reranking not implemented");
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        // 类似实现，调 vision API
        // ...
        throw new UnsupportedOperationException("Caption image not implemented");
    }

    private Map<String, Object> toOpenAIRequest(CompletionRequest request) {
        var result = new LinkedHashMap<String, Object>();
        result.put("model", request.model);
        result.put("messages", request.messages.stream()
            .map(m -> Map.of(
                "role", m.role.name().toLowerCase(),
                "content", m.content
            ))
            .toList());
        
        if (request.temperature != null) {
            result.put("temperature", request.temperature);
        }
        
        if (request.tools != null && !request.tools.isEmpty()) {
            result.put("tools", request.tools.stream()
                .map(t -> Map.of(
                    "type", "function",
                    "function", Map.of(
                        "name", t.function.name,
                        "description", t.function.description,
                        "parameters", t.function.parameters
                    )
                ))
                .toList());
        }
        
        if (request.responseFormat != null) {
            result.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", request.responseFormat.schema
            ));
        }
        
        return result;
    }

    private CompletionResponse parseResponse(String json) {
        // 解析 OpenAI 格式的响应
        var map = JsonUtil.toMap(json);
        var choices = (List<Map<String, Object>>) map.get("choices");
        
        var response = new CompletionResponse();
        response.choices = choices.stream()
            .map(c -> {
                var message = (Map<String, Object>) c.get("message");
                var msg = new Message();
                msg.role = RoleType.valueOf(((String) message.get("role")).toUpperCase());
                msg.content = (String) message.get("content");
                
                if (message.containsKey("tool_calls")) {
                    msg.toolCalls = parseToolCalls((List<Map<String, Object>>) message.get("tool_calls"));
                }
                
                var choice = new Choice();
                choice.message = msg;
                choice.finishReason = FinishReason.valueOf(((String) c.get("finish_reason")).toUpperCase());
                return choice;
            })
            .toList();
        
        return response;
    }

    private List<FunctionCall> parseToolCalls(List<Map<String, Object>> toolCalls) {
        return toolCalls.stream()
            .map(tc -> {
                var function = (Map<String, Object>) tc.get("function");
                var fc = new FunctionCall();
                fc.name = (String) function.get("name");
                fc.arguments = (String) function.get("arguments");
                return fc;
            })
            .toList();
    }

    private EmbeddingResponse parseEmbeddingResponse(String json) {
        var map = JsonUtil.toMap(json);
        var data = (List<Map<String, Object>>) map.get("data");
        
        var response = new EmbeddingResponse();
        response.embeddings = data.stream()
            .map(d -> {
                var emb = new Embedding();
                emb.vector = ((List<Number>) d.get("embedding")).stream()
                    .map(Number::doubleValue)
                    .toList();
                return emb;
            })
            .toList();
        
        return response;
    }
}
```

💡 **设计意图**：
- **OpenAI 兼容**：所有请求都转换成 OpenAI 格式，LiteLLM 会转发到实际 vendor
- **统一解析**：解析 OpenAI 格式的响应
- **错误处理**：HTTP 状态码非 200 时抛异常

### 6.4.4 配置示例

```yaml
# application.yml
llm:
  base-url: https://api.litellm.ai/v1
  api-key: ${LITELLM_API_KEY}
  default-model: gpt-4
  
  # 可选：多个 vendor
  openai:
    api-key: ${OPENAI_API_KEY}
  azure:
    api-key: ${AZURE_API_KEY}
    endpoint: https://xxx.openai.azure.com
  deepseek:
    api-key: ${DEEPSEEK_API_KEY}
```

---

## 6.5 领域模型

### 6.5.1 Message 消息

```java
public class Message {
    public RoleType role;           // SYSTEM/USER/ASSISTANT/TOOL
    public String content;          // 文本内容
    public List<FunctionCall> toolCalls;  // 工具调用列表（ASSISTANT）
    public String toolCallId;       // 工具调用 ID（TOOL）
    public String name;             // 工具名（TOOL）
    
    public static Message of(RoleType role, String content) {
        var msg = new Message();
        msg.role = role;
        msg.content = content;
        return msg;
    }
}
```

💡 **设计意图**：
- **SYSTEM**：系统消息，定义 agent 角色
- **USER**：用户输入
- **ASSISTANT**：agent 回答（可能包含工具调用）
- **TOOL**：工具执行结果

### 6.5.2 Tool 工具定义

```java
public class Tool {
    public ToolType type;           // FUNCTION
    public Function function;       // 函数定义
}

public class Function {
    public String name;             // 函数名
    public String description;      // 函数描述
    public JsonSchema parameters;   // 参数 JSON schema
}
```

💡 **设计意图**：Tool 定义会被传给 LLM，让 LLM 知道有哪些工具可用。

### 6.5.3 FunctionCall 工具调用

```java
public class FunctionCall {
    public String name;             // 工具名
    public String arguments;        // 参数（JSON 字符串）
}
```

💡 **设计意图**：LLM 返回 FunctionCall，告诉 agent 要调用哪个工具、传什么参数。

### 6.5.4 CompletionRequest 补全请求

```java
public class CompletionRequest {
    public List<Message> messages;  // 消息历史
    public List<Tool> tools;        // 工具定义
    public String model;            // 模型名称
    public Double temperature;      // 温度参数
    public ResponseFormat responseFormat;  // 响应格式（JSON schema）
    public Integer timeoutSeconds;  // 超时时间
    
    public static CompletionRequest of(CompletionRequestOptions options) {
        var request = new CompletionRequest();
        request.messages = options.messages;
        request.tools = options.tools;
        request.model = options.model;
        request.temperature = options.temperature;
        request.responseFormat = options.responseFormat;
        return request;
    }
}
```

### 6.5.5 CompletionResponse 补全响应

```java
public class CompletionResponse {
    public List<Choice> choices;    // 选择列表（通常只有一个）
    public Usage usage;             // token 使用量
}

public class Choice {
    public Message message;         // 消息
    public FinishReason finishReason;  // 结束原因
}

public enum FinishReason {
    STOP,           // 正常结束
    TOOL_CALLS,     // 需要调用工具
    LENGTH,         // 达到最大长度
    CONTENT_FILTER  // 内容被过滤
}

public class Usage {
    public int promptTokens;        // 输入 token 数
    public int completionTokens;    // 输出 token 数
    public int totalTokens;         // 总 token 数
}
```

💡 **设计意图**：
- **FinishReason**：告诉 agent 为什么停止生成
  - `STOP`：正常回答完成
  - `TOOL_CALLS`：需要调用工具（agent 会继续执行工具）
  - `LENGTH`：达到最大长度（可能需要截断）
- **Usage**：记录 token 使用量，用于计费和监控

---

## 6.6 流式调用

### 6.6.1 StreamingCallback 同步回调

```java
public interface StreamingCallback {
    void onToken(String token);           // 收到 token
    void onComplete(CompletionResponse response);  // 完成
    void onError(Exception e);            // 错误
}
```

### 6.6.2 使用示例

```java
llmProvider.completionStream(request, new StreamingCallback() {
    @Override
    public void onToken(String token) {
        System.out.print(token);  // 实时打印
    }
    
    @Override
    public void onComplete(CompletionResponse response) {
        System.out.println("\n[完成]");
    }
    
    @Override
    public void onError(Exception e) {
        System.err.println("[错误] " + e.getMessage());
    }
});
```

💡 **设计意图**：流式调用让 agent 可以实时输出，提升用户体验（聊天界面常见）。

---

## 6.7 多模态支持

### 6.7.1 ModelModalityRegistry 模型能力注册表

```java
public interface ModelModalityRegistry {
    boolean supportsVision(String model);      // 是否支持图像理解
    boolean supportsFunctionCalling(String model);  // 是否支持工具调用
    boolean supportsStreaming(String model);   // 是否支持流式
}
```

💡 **设计意图**：不同模型支持不同能力，`ModelModalityRegistry` 统一管理，避免调用不支持的功能。

### 6.7.2 图像理解

```java
// 构建带图像的消息
var message = Message.of(RoleType.USER, "这张图片里有什么？");
message.images = List.of(
    new Image("https://example.com/image.jpg")
);

var request = CompletionRequest.of(...)
    .messages(List.of(message));

var response = llmProvider.completion(request);
// response.choices.get(0).message.content = "图片里有一只猫"
```

💡 **设计意图**：多模态支持让 agent 可以理解图像内容，适用于图像分析、OCR 等场景。

---

## 6.8 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 说出 `LLMProvider` 的 5 个核心方法
- [ ] 说出 `LiteLLMProvider` 的工作原理
- [ ] 说出 `CompletionRequest` 和 `CompletionResponse` 的结构
- [ ] 说出 `FinishReason` 的 4 种类型
- [ ] 能配置多个 LLM provider

### 🔧 动手实践

1. **读源码**：

打开以下文件，逐行读：

```
core-ai/src/main/java/ai/core/llm/LLMProvider.java
core-ai/src/main/java/ai/core/llm/LLMProviders.java
core-ai/src/main/java/ai/core/llm/providers/LiteLLMProvider.java
```

2. **配置多个 provider**：

写一个 `application.yml`，配置 OpenAI、Azure、DeepSeek 三个 provider。

3. **调结构化输出**：

用 `completionFormat` 生成一个 JSON 对象：

```java
var person = llmProvider.completionFormat(
    "提取信息",
    "张三，28岁，北京",
    "gpt-4",
    PersonInfo.class
);
```

### 📝 自测题

1. `LLMProvider` 的核心方法是什么？
   - A. `completion()`
   - B. `embedding()`
   - C. `reranking()`
   
   **答案**：A（`completion()` 是最基础的方法）

2. `FinishReason.TOOL_CALLS` 表示什么？
   - A. 正常结束
   - B. 需要调用工具
   - C. 达到最大长度
   
   **答案**：B（需要调用工具）

3. `LiteLLMProvider` 的优势是什么？
   - A. 只支持 OpenAI
   - B. 统一接口访问多 vendor
   - C. 不需要 API key
   
   **答案**：B（统一接口访问多 vendor）

---

## 🎉 本章小结

本章你学会了：

- ✅ LLM 系统的整体架构（LLMProvider/LLMProviders/LiteLLMProvider）
- ✅ `LLMProvider` 的 5 个核心方法（completion/embedding/reranking/captionImage/stream）
- ✅ `LiteLLMProvider` 的工作原理（OpenAI 兼容格式）
- ✅ 领域模型（Message/Tool/FunctionCall/CompletionRequest/CompletionResponse）
- ✅ `FinishReason` 的 4 种类型（STOP/TOOL_CALLS/LENGTH/CONTENT_FILTER）
- ✅ 流式调用和多模态支持
- ✅ 多 vendor 配置

---

## 🚀 下一章

准备好进入 **[07-Memory与RAG](./07-Memory与RAG.md)** 了吗？

下一章你会学到：
- Memory 系统（对话历史、记忆存储、检索）
- RAG 系统（检索增强生成）
- VectorStore（向量库抽象）
- 如何配置一个 RAG agent

这是核心篇的最后一章，完成后你就掌握了内核的核心 SDK。

---

*最后更新：2026-08-31*
