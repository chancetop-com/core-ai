# 06 - LLM 网关

> 🎯 **学习目标**: 掌握 LLM 网关的架构设计、核心机制和扩展开发
> 
> ⏱️ **预计时间**: 2 天
> 
> 📋 **前置要求**: 完成 [05-工作流引擎](./05-工作流引擎.md)

---

## 📚 本章内容

- [6.1 LLM 网关概述](#61-llm-网关概述)
- [6.2 架构设计](#62-架构设计)
- [6.3 核心组件详解](#63-核心组件详解)
- [6.4 路由机制](#64-路由机制)
- [6.5 负载均衡](#65-负载均衡)
- [6.6 故障转移](#66-故障转移)
- [6.7 限流和配额](#67-限流和配额)
- [6.8 实战: 自定义 LLM 提供商](#68-实战-自定义-llm-提供商)
- [6.9 最佳实践](#69-最佳实践)
- [6.10 性能优化](#610-性能优化)
- [6.11 验证学习成果](#611-验证学习成果)

---

## 6.1 LLM 网关概述

### 6.1.1 什么是 LLM 网关

LLM 网关（Large Language Model Gateway）是一个统一的 API 代理层，用于管理和路由对多个 LLM 提供商的请求。

**核心功能**:

| 功能 | 说明 | 示例 |
|------|------|------|
| **统一接口** | 提供标准化的 API 接口 | OpenAI 兼容格式 |
| **多提供商支持** | 支持多个 LLM 提供商 | OpenAI、Anthropic、Google 等 |
| **智能路由** | 根据策略选择最佳提供商 | 基于成本、延迟、可用性 |
| **负载均衡** | 在多个提供商间分配请求 | 轮询、加权、最少连接 |
| **故障转移** | 提供商故障时自动切换 | 自动降级到备用提供商 |
| **限流和配额** | 控制请求频率和用量 | 每分钟 100 次请求 |
| **缓存** | 缓存相似请求的结果 | 语义缓存 |
| **监控和追踪** | 记录请求和性能指标 | 延迟、错误率、成本 |

💡 **核心价值**: 解耦应用与 LLM 提供商，提供灵活性、可靠性和成本优化。

### 6.1.2 为什么需要 LLM 网关

```
┌─────────────────────────────────────────────────────────┐
│                   没有 LLM 网关                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  应用 → 直接调用 OpenAI API                             │
│       → 直接调用 Anthropic API                          │
│       → 直接调用 Google API                             │
│                                                         │
│  问题:                                                  │
│  ❌ 代码中硬编码提供商逻辑                              │
│  ❌ 切换提供商需要修改代码                              │
│  ❌ 没有统一的故障处理                                  │
│  ❌ 难以监控和追踪                                      │
│  ❌ 无法优化成本                                        │
│                                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                   使用 LLM 网关                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  应用 → LLM 网关 → OpenAI                               │
│                → Anthropic                              │
│                → Google                                 │
│                → 其他提供商                             │
│                                                         │
│  优势:                                                  │
│  ✅ 统一接口，应用代码不变                              │
│  ✅ 动态切换提供商，无需修改代码                        │
│  ✅ 统一的故障处理和重试                                │
│  ✅ 完整的监控和追踪                                    │
│  ✅ 智能成本优化                                        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 6.1.3 LLM 网关的职责

`GatewayModule` 提供:

```java
public class GatewayModule extends Module {
    @Override
    protected void initialize() {
        configureGateway();
    }
    
    private void configureGateway() {
        // 1. 绑定核心服务
        var gatewayRoutingEngine = bind(GatewayRoutingEngine.class);
        bind(GatewayModelService.class);
        bind(GatewayProviderService.class);
        bind(GatewayProxyService.class);
        
        // 2. 注册 HTTP 路由
        registerGatewayProxyRoutes();
        
        // 3. 配置 LLM 提供商
        var gatewayLLMProvider = new GatewayLLMProvider(...);
        bind(gatewayLLMProvider);
        
        // 4. 注册为默认提供商
        llmProviders.addProvider(LLMProviderType.GATEWAY, gatewayLLMProvider);
        llmProviders.setDefaultProvider(LLMProviderType.GATEWAY);
    }
}
```

💡 **设计意图**: 
- 统一管理多个 LLM 提供商
- 提供 OpenAI 兼容的 API 接口
- 智能路由和负载均衡
- 完整的监控和追踪

---

## 6.2 架构设计

### 6.2.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        客户端应用                             │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ HTTP/SSE 请求
                       ↓
┌──────────────────────────────────────────────────────────────┐
│                     LLM 网关层                                │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              GatewayProxyController                    │ │
│  │  - /api/gateway/v1/chat/completions                   │ │
│  │  - /api/gateway/v1/responses                          │ │
│  │  - /api/gateway/v1/models                             │ │
│  └────────────────────────────────────────────────────────┘ │
│                         │                                    │
│                         ↓                                    │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              GatewayRoutingEngine                      │ │
│  │  - 路由策略选择                                        │ │
│  │  - 负载均衡                                            │ │
│  │  - 故障转移                                            │ │
│  └────────────────────────────────────────────────────────┘ │
│                         │                                    │
│                         ↓                                    │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              GatewayProviderService                    │ │
│  │  - 提供商管理                                          │ │
│  │  - 健康检查                                            │ │
│  │  - 配额管理                                            │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
└──────────────────────┬───────────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        ↓              ↓              ↓
   ┌─────────┐   ┌─────────┐   ┌─────────┐
   │ OpenAI  │   │Anthropic│   │ Google  │
   │   API   │   │   API   │   │   API   │
   └─────────┘   └─────────┘   └─────────┘
```

### 6.2.2 核心组件

| 组件 | 职责 | 关键方法 |
|------|------|---------|
| **GatewayProxyController** | 处理 HTTP 请求 | `chatCompletions()`, `responses()` |
| **GatewayRoutingEngine** | 路由决策 | `route()`, `selectProvider()` |
| **GatewayProviderService** | 提供商管理 | `getProvider()`, `healthCheck()` |
| **GatewayModelService** | 模型管理 | `getModels()`, `getModelInfo()` |
| **GatewayLLMProvider** | LLM 调用封装 | `complete()`, `completeStream()` |

### 6.2.3 请求处理流程

```
客户端请求
    ↓
┌─────────────────────────┐
│ 1. GatewayProxyController │
│    - 解析请求参数        │
│    - 验证认证            │
│    - 记录请求日志        │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ 2. GatewayRoutingEngine   │
│    - 选择路由策略        │
│    - 选择提供商          │
│    - 应用负载均衡        │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ 3. GatewayProviderService │
│    - 获取提供商配置      │
│    - 检查配额            │
│    - 检查健康状态        │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ 4. 调用 LLM API          │
│    - 发送 HTTP 请求      │
│    - 处理响应            │
│    - 记录性能指标        │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ 5. 返回响应              │
│    - 格式化响应          │
│    - 添加追踪信息        │
│    - 记录审计日志        │
└─────────────────────────┘
```

---

## 6.3 核心组件详解

### 6.3.1 GatewayProxyController

**职责**: 处理 HTTP 请求，提供 OpenAI 兼容的 API 接口。

```java
public class GatewayProxyController {
    @Inject
    private GatewayProxyService proxyService;
    
    /**
     * 聊天完成接口（OpenAI 兼容）
     */
    @Route(method = POST, path = "/api/gateway/v1/chat/completions")
    public void chatCompletions(Request request, Response response) {
        // 1. 解析请求
        var chatRequest = request.body(ChatCompletionRequest.class);
        
        // 2. 验证请求
        validateRequest(chatRequest);
        
        // 3. 调用代理服务
        if (chatRequest.isStream()) {
            // 流式响应
            handleStreamRequest(chatRequest, response);
        } else {
            // 普通响应
            handleNormalRequest(chatRequest, response);
        }
    }
    
    /**
     * 处理普通请求
     */
    private void handleNormalRequest(ChatCompletionRequest request, 
                                     Response response) {
        var result = proxyService.complete(request);
        response.json(result);
    }
    
    /**
     * 处理流式请求
     */
    private void handleStreamRequest(ChatCompletionRequest request, 
                                     Response response) {
        response.sse(sse -> {
            proxyService.completeStream(request, chunk -> {
                sse.send(chunk);
            });
        });
    }
    
    /**
     * 列出可用模型
     */
    @Route(method = GET, path = "/api/gateway/v1/models")
    public void models(Request request, Response response) {
        var models = proxyService.getModels();
        response.json(Map.of("data", models));
    }
    
    /**
     * 生成图像
     */
    @Route(method = POST, path = "/api/gateway/v1/images/generations")
    public void imageGenerations(Request request, Response response) {
        var imageRequest = request.body(ImageGenerationRequest.class);
        var result = proxyService.generateImage(imageRequest);
        response.json(result);
    }
    
    /**
     * 生成视频
     */
    @Route(method = POST, path = "/api/gateway/v1/videos")
    public void videoGenerations(Request request, Response response) {
        var videoRequest = request.body(VideoGenerationRequest.class);
        var result = proxyService.generateVideo(videoRequest);
        response.json(result);
    }
}
```

💡 **设计要点**:
- 提供 OpenAI 兼容的 API 接口
- 支持普通和流式两种响应模式
- 支持多种媒体类型（文本、图像、视频）

### 6.3.2 GatewayRoutingEngine

**职责**: 路由决策，选择最佳的 LLM 提供商。

```java
public class GatewayRoutingEngine {
    @Inject
    private GatewayProviderService providerService;
    
    @Inject
    private RoutingStrategyRegistry strategyRegistry;
    
    /**
     * 路由请求到合适的提供商
     */
    public GatewayProvider route(ChatCompletionRequest request) {
        // 1. 获取可用的提供商
        var availableProviders = providerService.getAvailableProviders();
        
        // 2. 过滤符合条件的提供商
        var filteredProviders = filterProviders(availableProviders, request);
        
        // 3. 应用路由策略
        var strategy = strategyRegistry.getStrategy(request.getRoutingStrategy());
        return strategy.select(filteredProviders, request);
    }
    
    /**
     * 过滤提供商
     */
    private List<GatewayProvider> filterProviders(
            List<GatewayProvider> providers,
            ChatCompletionRequest request) {
        return providers.stream()
            .filter(p -> p.supportsModel(request.getModel()))
            .filter(p -> p.isHealthy())
            .filter(p -> p.hasQuota())
            .toList();
    }
}
```

### 6.3.3 路由策略

```java
public interface RoutingStrategy {
    GatewayProvider select(List<GatewayProvider> providers, 
                          ChatCompletionRequest request);
}

// 轮询策略
public class RoundRobinStrategy implements RoutingStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);
    
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        var index = counter.getAndIncrement() % providers.size();
        return providers.get(index);
    }
}

// 加权轮询策略
public class WeightedRoundRobinStrategy implements RoutingStrategy {
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        // 根据权重选择
        var totalWeight = providers.stream()
            .mapToInt(GatewayProvider::getWeight)
            .sum();
        
        var random = ThreadLocalRandom.current().nextInt(totalWeight);
        var cumulativeWeight = 0;
        
        for (var provider : providers) {
            cumulativeWeight += provider.getWeight();
            if (random < cumulativeWeight) {
                return provider;
            }
        }
        
        return providers.get(0);
    }
}

// 最少连接策略
public class LeastConnectionsStrategy implements RoutingStrategy {
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        return providers.stream()
            .min(Comparator.comparingInt(GatewayProvider::getActiveConnections))
            .orElse(providers.get(0));
    }
}

// 成本优先策略
public class CostOptimizedStrategy implements RoutingStrategy {
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        return providers.stream()
            .min(Comparator.comparingDouble(p -> 
                p.estimateCost(request)))
            .orElse(providers.get(0));
    }
}

// 延迟优先策略
public class LatencyOptimizedStrategy implements RoutingStrategy {
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        return providers.stream()
            .min(Comparator.comparingDouble(GatewayProvider::getAverageLatency))
            .orElse(providers.get(0));
    }
}
```

💡 **策略选择**:
- **轮询**: 简单公平，适合提供商性能相近
- **加权轮询**: 考虑提供商能力差异
- **最少连接**: 负载均衡，避免单个提供商过载
- **成本优先**: 优化成本，适合预算敏感场景
- **延迟优先**: 优化响应速度，适合实时应用

### 6.3.4 GatewayProviderService

**职责**: 管理 LLM 提供商的生命周期和配置。

```java
public class GatewayProviderService {
    @Inject
    private MongoCollection<GatewayProviderConfig> configCollection;
    
    private final Map<String, GatewayProvider> providers = new ConcurrentHashMap<>();
    
    /**
     * 初始化提供商
     */
    public void initialize() {
        var configs = configCollection.find().into(new ArrayList<>());
        
        for (var config : configs) {
            var provider = createProvider(config);
            providers.put(config.getName(), provider);
        }
    }
    
    /**
     * 创建提供商实例
     */
    private GatewayProvider createProvider(GatewayProviderConfig config) {
        return switch (config.getType()) {
            case OPENAI -> new OpenAIGatewayProvider(config);
            case ANTHROPIC -> new AnthropicGatewayProvider(config);
            case GOOGLE -> new GoogleGatewayProvider(config);
            case CUSTOM -> new CustomGatewayProvider(config);
        };
    }
    
    /**
     * 获取可用提供商
     */
    public List<GatewayProvider> getAvailableProviders() {
        return providers.values().stream()
            .filter(GatewayProvider::isAvailable)
            .toList();
    }
    
    /**
     * 健康检查
     */
    public void healthCheck() {
        for (var provider : providers.values()) {
            try {
                provider.ping();
                provider.markHealthy();
            } catch (Exception e) {
                provider.markUnhealthy();
                logger.warn("Provider {} health check failed", 
                    provider.getName(), e);
            }
        }
    }
    
    /**
     * 添加提供商
     */
    public void addProvider(GatewayProviderConfig config) {
        configCollection.insertOne(config);
        var provider = createProvider(config);
        providers.put(config.getName(), provider);
    }
    
    /**
     * 移除提供商
     */
    public void removeProvider(String name) {
        configCollection.deleteOne(Filters.eq("name", name));
        providers.remove(name);
    }
}
```

### 6.3.5 GatewayLLMProvider

**职责**: 封装 LLM 调用，提供统一的接口。

```java
public class GatewayLLMProvider implements LLMProvider {
    @Inject
    private GatewayRoutingEngine routingEngine;
    
    @Inject
    private GatewaySecretProtector secretProtector;
    
    private final LLMProviderConfig config;
    private final LLMProvider fallbackProvider;
    
    @Override
    public CompletionResponse complete(CompletionRequest request) {
        // 1. 转换为网关请求格式
        var gatewayRequest = convertToGatewayRequest(request);
        
        // 2. 路由到合适的提供商
        var provider = routingEngine.route(gatewayRequest);
        
        try {
            // 3. 调用提供商 API
            var response = provider.complete(gatewayRequest);
            
            // 4. 转换回标准格式
            return convertToStandardResponse(response);
            
        } catch (Exception e) {
            // 5. 故障转移
            if (fallbackProvider != null) {
                logger.warn("Gateway provider failed, using fallback", e);
                return fallbackProvider.complete(request);
            }
            throw e;
        }
    }
    
    @Override
    public void completeStream(CompletionRequest request, 
                               Consumer<CompletionChunk> chunkConsumer) {
        var gatewayRequest = convertToGatewayRequest(request);
        var provider = routingEngine.route(gatewayRequest);
        
        try {
            provider.completeStream(gatewayRequest, chunk -> {
                var standardChunk = convertToStandardChunk(chunk);
                chunkConsumer.accept(standardChunk);
            });
        } catch (Exception e) {
            if (fallbackProvider != null) {
                logger.warn("Gateway stream failed, using fallback", e);
                fallbackProvider.completeStream(request, chunkConsumer);
            } else {
                throw e;
            }
        }
    }
    
    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        var gatewayRequest = convertToGatewayEmbedRequest(request);
        var provider = routingEngine.route(gatewayRequest);
        
        var response = provider.embed(gatewayRequest);
        return convertToStandardEmbedResponse(response);
    }
}
```

💡 **设计要点**:
- 统一的 LLM 调用接口
- 自动路由和故障转移
- 支持流式和非流式调用
- 保护 API 密钥安全

---

## 6.4 路由机制

### 6.4.1 路由规则

```java
public class RoutingRule {
    private String name;
    private String condition;      // 匹配条件
    private String provider;       // 目标提供商
    private int priority;          // 优先级
    private boolean enabled;       // 是否启用
    
    /**
     * 评估规则是否匹配
     */
    public boolean matches(ChatCompletionRequest request) {
        if (!enabled) return false;
        
        var context = Map.of(
            "model", request.getModel(),
            "user", request.getUser(),
            "tokens", estimateTokens(request)
        );
        
        return evaluateCondition(condition, context);
    }
}

// 路由规则示例
var rules = List.of(
    // 高优先级用户使用高级模型
    new RoutingRule(
        "premium-users",
        "user.tier == 'premium'",
        "openai-gpt4",
        100,
        true
    ),
    
    // 代码相关请求使用代码模型
    new RoutingRule(
        "code-requests",
        "request.tags contains 'code'",
        "anthropic-claude-code",
        90,
        true
    ),
    
    // 低成本请求使用便宜模型
    new RoutingRule(
        "cost-optimized",
        "tokens < 1000",
        "openai-gpt3.5",
        80,
        true
    )
);
```

### 6.4.2 路由决策流程

```
请求到达
    ↓
┌─────────────────────────┐
│ 1. 解析请求属性          │
│    - model              │
│    - user               │
│    - tokens             │
│    - tags               │
└──────────┬──────────────┘
           ↓
┌─────────────────────────┐
│ 2. 匹配路由规则          │
│    - 按优先级排序        │
│    - 评估条件            │
│    - 选择第一个匹配      │
└──────────┬──────────────┘
           ↓
    ┌──────┴──────┐
    ↓              ↓
  匹配规则      无匹配规则
    ↓              ↓
  使用规则      使用默认策略
  指定的提供商   （如轮询）
    ↓              ↓
    └──────┬──────┘
           ↓
┌─────────────────────────┐
│ 3. 检查提供商状态        │
│    - 是否健康            │
│    - 是否有配额          │
│    - 是否支持模型        │
└──────────┬──────────────┘
           ↓
    ┌──────┴──────┐
    ↓              ↓
  可用          不可用
    ↓              ↓
  返回提供商    尝试下一个
               或降级
```

### 6.4.3 模型映射

```java
public class ModelMappingService {
    // 模型别名映射
    private final Map<String, String> aliases = Map.of(
        "gpt-4", "openai-gpt-4-turbo",
        "claude-3", "anthropic-claude-3-opus",
        "gemini-pro", "google-gemini-pro"
    );
    
    // 提供商模型映射
    private final Map<String, List<String>> providerModels = Map.of(
        "openai", List.of("gpt-4", "gpt-3.5-turbo", "text-embedding-ada-002"),
        "anthropic", List.of("claude-3-opus", "claude-3-sonnet", "claude-3-haiku"),
        "google", List.of("gemini-pro", "gemini-pro-vision")
    );
    
    /**
     * 解析模型名称
     */
    public String resolveModel(String modelName) {
        // 1. 检查别名
        if (aliases.containsKey(modelName)) {
            return aliases.get(modelName);
        }
        
        // 2. 检查提供商前缀
        if (modelName.contains("-")) {
            var parts = modelName.split("-", 2);
            var provider = parts[0];
            var model = parts[1];
            
            if (providerModels.containsKey(provider)) {
                return modelName;
            }
        }
        
        // 3. 返回原始名称
        return modelName;
    }
    
    /**
     * 获取支持该模型的提供商
     */
    public List<String> getProvidersForModel(String modelName) {
        return providerModels.entrySet().stream()
            .filter(e -> e.getValue().contains(modelName))
            .map(Map.Entry::getKey)
            .toList();
    }
}
```

---

## 6.5 负载均衡

### 6.5.1 负载均衡策略

```java
public class LoadBalancer {
    private final List<GatewayProvider> providers;
    private final LoadBalancingStrategy strategy;
    
    /**
     * 选择下一个提供商
     */
    public GatewayProvider next(ChatCompletionRequest request) {
        // 1. 过滤可用提供商
        var available = providers.stream()
            .filter(GatewayProvider::isAvailable)
            .toList();
        
        if (available.isEmpty()) {
            throw new NoAvailableProviderException();
        }
        
        // 2. 应用负载均衡策略
        return strategy.select(available, request);
    }
}

// 加权最少连接策略
public class WeightedLeastConnectionsStrategy implements LoadBalancingStrategy {
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        return providers.stream()
            .min(Comparator.comparingDouble(p -> 
                (double) p.getActiveConnections() / p.getWeight()))
            .orElse(providers.get(0));
    }
}

// 一致性哈希策略
public class ConsistentHashStrategy implements LoadBalancingStrategy {
    private final TreeMap<Integer, GatewayProvider> hashRing = new TreeMap<>();
    
    public ConsistentHashStrategy(List<GatewayProvider> providers, int virtualNodes) {
        for (var provider : providers) {
            for (int i = 0; i < virtualNodes; i++) {
                var hash = hash(provider.getName() + "-" + i);
                hashRing.put(hash, provider);
            }
        }
    }
    
    @Override
    public GatewayProvider select(List<GatewayProvider> providers, 
                                  ChatCompletionRequest request) {
        var key = request.getUser() != null ? 
            request.getUser() : 
            request.getModel();
        
        var hash = hash(key);
        var entry = hashRing.ceilingEntry(hash);
        
        if (entry == null) {
            entry = hashRing.firstEntry();
        }
        
        return entry.getValue();
    }
    
    private int hash(String key) {
        return Math.abs(key.hashCode());
    }
}
```

💡 **策略选择**:
- **轮询**: 简单，但不考虑提供商负载
- **加权轮询**: 考虑提供商能力差异
- **最少连接**: 动态负载均衡
- **一致性哈希**: 相同请求路由到相同提供商，适合有状态场景

### 6.5.2 健康检查

```java
public class HealthChecker {
    @Inject
    private GatewayProviderService providerService;
    
    /**
     * 定期检查提供商健康状态
     */
    @Scheduled(fixedRate = 30000)  // 每 30 秒检查一次
    public void checkHealth() {
        var providers = providerService.getAllProviders();
        
        for (var provider : providers) {
            try {
                // 发送 ping 请求
                var startTime = System.currentTimeMillis();
                provider.ping();
                var latency = System.currentTimeMillis() - startTime;
                
                // 更新健康状态
                provider.markHealthy();
                provider.updateLatency(latency);
                
                logger.debug("Provider {} is healthy, latency: {}ms", 
                    provider.getName(), latency);
                
            } catch (Exception e) {
                // 标记为不健康
                provider.markUnhealthy();
                provider.incrementFailureCount();
                
                logger.warn("Provider {} health check failed: {}", 
                    provider.getName(), e.getMessage());
                
                // 连续失败超过阈值，触发告警
                if (provider.getFailureCount() >= 3) {
                    alertService.sendAlert(
                        "Provider " + provider.getName() + " is unhealthy"
                    );
                }
            }
        }
    }
}
```

---

## 6.6 故障转移

### 6.6.1 故障转移策略

```java
public class FailoverHandler {
    private final List<GatewayProvider> fallbackProviders;
    private final int maxRetries;
    private final Duration retryDelay;
    
    /**
     * 处理请求失败
     */
    public CompletionResponse handleFailure(
            ChatCompletionRequest request,
            GatewayProvider failedProvider,
            Exception error) {
        
        logger.warn("Provider {} failed, attempting failover", 
            failedProvider.getName(), error);
        
        // 1. 标记提供商为不健康
        failedProvider.markUnhealthy();
        
        // 2. 尝试备用提供商
        for (var provider : fallbackProviders) {
            if (provider.isAvailable()) {
                try {
                    var response = provider.complete(request);
                    logger.info("Failover to {} succeeded", 
                        provider.getName());
                    return response;
                } catch (Exception e) {
                    logger.warn("Failover to {} also failed", 
                        provider.getName(), e);
                }
            }
        }
        
        // 3. 所有备用提供商都失败
        throw new AllProvidersFailedException(
            "All providers failed for request", error);
    }
    
    /**
     * 带重试的调用
     */
    public CompletionResponse completeWithRetry(
            ChatCompletionRequest request,
            GatewayProvider provider) {
        
        int attempt = 0;
        
        while (attempt < maxRetries) {
            try {
                return provider.complete(request);
            } catch (RetryableException e) {
                attempt++;
                
                if (attempt >= maxRetries) {
                    throw e;
                }
                
                // 指数退避
                var delay = retryDelay.multipliedBy(
                    (long) Math.pow(2, attempt - 1));
                
                logger.warn("Request failed, retrying in {}ms (attempt {}/{})", 
                    delay.toMillis(), attempt, maxRetries);
                
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RequestInterruptedException("Request interrupted", ie);
                }
            }
        }
        
        throw new MaxRetriesExceededException(
            "Max retries exceeded: " + maxRetries);
    }
}
```

### 6.6.2 降级策略

```java
public class DegradationHandler {
    /**
     * 降级到缓存
     */
    public CompletionResponse degradeToCache(ChatCompletionRequest request) {
        var cacheKey = buildCacheKey(request);
        var cached = cacheService.get(cacheKey);
        
        if (cached != null) {
            logger.info("Degraded to cache for request");
            return cached;
        }
        
        throw new ServiceUnavailableException(
            "No cached response available");
    }
    
    /**
     * 降级到简单模型
     */
    public CompletionResponse degradeToSimpleModel(
            ChatCompletionRequest request) {
        
        var simpleRequest = request.toBuilder()
            .model("gpt-3.5-turbo")  // 使用更简单、更稳定的模型
            .build();
        
        try {
            var provider = providerService.getProvider("openai");
            return provider.complete(simpleRequest);
        } catch (Exception e) {
            throw new DegradationFailedException(
                "Failed to degrade to simple model", e);
        }
    }
    
    /**
     * 返回预设响应
     */
    public CompletionResponse returnFallbackResponse(
            ChatCompletionRequest request) {
        
        return CompletionResponse.builder()
            .content("抱歉，服务暂时不可用，请稍后重试。")
            .model(request.getModel())
            .usage(Usage.builder()
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .build())
            .build();
    }
}
```

💡 **降级策略选择**:
- **缓存降级**: 返回缓存的相似请求结果
- **模型降级**: 切换到更简单、更稳定的模型
- **预设响应**: 返回友好的错误信息

---

## 6.7 限流和配额

### 6.7.1 限流实现

```java
public class RateLimiter {
    // 使用 Redis 实现分布式限流
    @Inject
    private RedisClient redisClient;
    
    /**
     * 检查是否允许请求
     */
    public boolean allowRequest(String userId, String endpoint) {
        var key = "rate_limit:" + userId + ":" + endpoint;
        var limit = getLimit(userId, endpoint);
        
        // 使用 Redis INCR 实现计数器
        var count = redisClient.incr(key);
        
        if (count == 1) {
            // 第一次请求，设置过期时间
            redisClient.expire(key, 60);  // 1 分钟窗口
        }
        
        return count <= limit;
    }
    
    /**
     * 获取限流配置
     */
    private int getLimit(String userId, String endpoint) {
        // 根据用户等级和端点返回不同的限制
        var user = userService.getUser(userId);
        
        return switch (user.getTier()) {
            case FREE -> 10;      // 每分钟 10 次
            case BASIC -> 60;     // 每分钟 60 次
            case PREMIUM -> 300;  // 每分钟 300 次
            case ENTERPRISE -> 1000;  // 每分钟 1000 次
        };
    }
}

// 在 Controller 中使用
public class GatewayProxyController {
    @Inject
    private RateLimiter rateLimiter;
    
    @Route(method = POST, path = "/api/gateway/v1/chat/completions")
    public void chatCompletions(Request request, Response response) {
        var userId = request.getHeader("X-User-Id");
        
        if (!rateLimiter.allowRequest(userId, "chat/completions")) {
            response.status(429);  // Too Many Requests
            response.json(Map.of(
                "error", "Rate limit exceeded",
                "retry_after", 60
            ));
            return;
        }
        
        // 处理请求...
    }
}
```

### 6.7.2 配额管理

```java
public class QuotaManager {
    @Inject
    private MongoCollection<UserQuota> quotaCollection;
    
    /**
     * 检查配额
     */
    public boolean checkQuota(String userId, int estimatedTokens) {
        var quota = quotaCollection.find(
            Filters.eq("userId", userId)
        ).first();
        
        if (quota == null) {
            return true;  // 无限额限制
        }
        
        var currentMonth = getCurrentMonth();
        var usage = quota.getUsage(currentMonth);
        
        return usage.getTokens() + estimatedTokens <= quota.getLimit();
    }
    
    /**
     * 更新配额使用
     */
    public void updateUsage(String userId, int tokens, double cost) {
        var currentMonth = getCurrentMonth();
        
        quotaCollection.updateOne(
            Filters.eq("userId", userId),
            Updates.inc("usage." + currentMonth + ".tokens", tokens),
            Updates.inc("usage." + currentMonth + ".cost", cost)
        );
    }
    
    /**
     * 获取配额信息
     */
    public QuotaInfo getQuotaInfo(String userId) {
        var quota = quotaCollection.find(
            Filters.eq("userId", userId)
        ).first();
        
        if (quota == null) {
            return QuotaInfo.unlimited();
        }
        
        var currentMonth = getCurrentMonth();
        var usage = quota.getUsage(currentMonth);
        
        return QuotaInfo.builder()
            .limit(quota.getLimit())
            .used(usage.getTokens())
            .remaining(quota.getLimit() - usage.getTokens())
            .cost(usage.getCost())
            .build();
    }
}
```

### 6.7.3 成本追踪

```java
public class CostTracker {
    // 模型定价（每 1000 token）
    private static final Map<String, Double> PRICING = Map.of(
        "gpt-4", 0.03,
        "gpt-3.5-turbo", 0.0015,
        "claude-3-opus", 0.015,
        "claude-3-sonnet", 0.003,
        "gemini-pro", 0.0005
    );
    
    /**
     * 计算请求成本
     */
    public double calculateCost(String model, Usage usage) {
        var pricePerKToken = PRICING.getOrDefault(model, 0.0);
        
        var inputCost = (usage.getPromptTokens() / 1000.0) * pricePerKToken;
        var outputCost = (usage.getCompletionTokens() / 1000.0) * pricePerKToken;
        
        return inputCost + outputCost;
    }
    
    /**
     * 记录成本
     */
    public void recordCost(String userId, String model, Usage usage) {
        var cost = calculateCost(model, usage);
        
        // 记录到数据库
        costCollection.insertOne(CostRecord.builder()
            .userId(userId)
            .model(model)
            .usage(usage)
            .cost(cost)
            .timestamp(LocalDateTime.now())
            .build());
        
        // 更新配额
        quotaManager.updateUsage(userId, usage.getTotalTokens(), cost);
    }
}
```

💡 **成本管理**:
- 实时追踪每个请求的成本
- 按用户、模型、时间段统计
- 支持成本预警和限制

---

## 6.8 实战: 自定义 LLM 提供商

### 6.8.1 需求

实现一个自定义 LLM 提供商，支持:
- 自定义 API 端点
- 自定义认证方式
- 自定义请求/响应格式

### 6.8.2 实现

#### 1. 提供商配置

```java
public class CustomProviderConfig extends GatewayProviderConfig {
    private String endpoint;
    private String authType;      // "api_key", "oauth2", "custom"
    private String authHeader;
    private Map<String, String> customHeaders;
    
    // getters and setters
}
```

#### 2. 提供商实现

```java
public class CustomGatewayProvider implements GatewayProvider {
    private final CustomProviderConfig config;
    private final HttpClient httpClient;
    
    public CustomGatewayProvider(CustomProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
    
    @Override
    public String getName() {
        return config.getName();
    }
    
    @Override
    public boolean supportsModel(String model) {
        return config.getSupportedModels().contains(model);
    }
    
    @Override
    public CompletionResponse complete(ChatCompletionRequest request) {
        // 1. 构建请求
        var httpRequest = buildHttpRequest(request);
        
        // 2. 发送请求
        try {
            var httpResponse = httpClient.send(
                httpRequest, 
                HttpResponse.BodyHandlers.ofString()
            );
            
            // 3. 解析响应
            return parseResponse(httpResponse);
            
        } catch (Exception e) {
            throw new ProviderException(
                "Failed to call custom provider", e);
        }
    }
    
    private HttpRequest buildHttpRequest(ChatCompletionRequest request) {
        var body = buildRequestBody(request);
        
        var builder = HttpRequest.newBuilder()
            .uri(URI.create(config.getEndpoint()))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json");
        
        // 添加认证头
        addAuthHeader(builder);
        
        // 添加自定义头
        config.getCustomHeaders().forEach(builder::header);
        
        return builder.build();
    }
    
    private void addAuthHeader(HttpRequest.Builder builder) {
        switch (config.getAuthType()) {
            case "api_key" -> 
                builder.header("Authorization", 
                    "Bearer " + config.getApiKey());
            case "oauth2" -> 
                builder.header("Authorization", 
                    "Bearer " + getAccessToken());
            case "custom" -> 
                builder.header(config.getAuthHeader(), 
                    config.getAuthValue());
        }
    }
    
    private String buildRequestBody(ChatCompletionRequest request) {
        // 转换为自定义格式
        var customRequest = Map.of(
            "model", request.getModel(),
            "messages", request.getMessages(),
            "temperature", request.getTemperature(),
            "max_tokens", request.getMaxTokens()
        );
        
        return JsonUtil.toJson(customRequest);
    }
    
    private CompletionResponse parseResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new ProviderException(
                "Provider returned status: " + response.statusCode());
        }
        
        // 解析自定义响应格式
        var json = JsonUtil.parseJson(response.body());
        
        return CompletionResponse.builder()
            .content(json.getString("result"))
            .model(json.getString("model"))
            .usage(Usage.builder()
                .promptTokens(json.getInt("prompt_tokens"))
                .completionTokens(json.getInt("completion_tokens"))
                .totalTokens(json.getInt("total_tokens"))
                .build())
            .build();
    }
}
```

#### 3. 注册提供商

```java
public class GatewayModule extends Module {
    @Override
    protected void initialize() {
        // 注册自定义提供商工厂
        onStartup(() -> {
            var providerService = bean(GatewayProviderService.class);
            
            providerService.registerFactory("custom", config -> {
                var customConfig = JsonUtil.convert(config, 
                    CustomProviderConfig.class);
                return new CustomGatewayProvider(customConfig);
            });
        });
    }
}
```

#### 4. 配置提供商

```yaml
# application.yml
gateway:
  providers:
    - name: my-custom-provider
      type: custom
      endpoint: https://api.mycompany.com/v1/chat
      authType: api_key
      apiKey: ${CUSTOM_API_KEY}
      supportedModels:
        - custom-model-1
        - custom-model-2
      customHeaders:
        X-Custom-Header: value
```

💡 **学习要点**:
- 实现 `GatewayProvider` 接口
- 处理认证和请求格式
- 注册提供商工厂
- 配置提供商参数

---

## 6.9 最佳实践

### 6.9.1 提供商选择

```java
// ✅ 好的实践: 根据场景选择提供商
public GatewayProvider selectProvider(ChatCompletionRequest request) {
    if (request.isCodeRelated()) {
        // 代码相关使用 Claude
        return providerService.getProvider("anthropic");
    } else if (request.needsVision()) {
        // 视觉任务使用 GPT-4V
        return providerService.getProvider("openai-vision");
    } else if (request.isCostSensitive()) {
        // 成本敏感使用便宜模型
        return providerService.getProvider("openai-gpt3.5");
    } else {
        // 默认使用 GPT-4
        return providerService.getProvider("openai");
    }
}

// ❌ 不好的实践: 硬编码提供商
public CompletionResponse complete(ChatCompletionRequest request) {
    var provider = new OpenAIProvider();  // 硬编码
    return provider.complete(request);
}
```

💡 **原则**:
- 根据任务类型选择提供商
- 考虑成本和性能
- 使用路由策略动态选择

### 6.9.2 错误处理

```java
// ✅ 好的实践: 明确的错误处理
public CompletionResponse complete(ChatCompletionRequest request) {
    try {
        var provider = routingEngine.route(request);
        return provider.complete(request);
    } catch (ProviderException e) {
        logger.error("Provider call failed", e);
        return failoverHandler.handleFailure(request, e);
    } catch (RateLimitException e) {
        logger.warn("Rate limit exceeded", e);
        throw new UserException("请求过于频繁，请稍后重试", e);
    } catch (Exception e) {
        logger.error("Unexpected error", e);
        throw new SystemException("系统错误，请稍后重试", e);
    }
}

// ❌ 不好的实践: 吞掉异常
public CompletionResponse complete(ChatCompletionRequest request) {
    try {
        return provider.complete(request);
    } catch (Exception e) {
        return null;  // 错误被隐藏
    }
}
```

💡 **原则**:
- 区分用户错误和系统错误
- 提供有意义的错误信息
- 记录详细的日志

### 6.9.3 性能优化

```java
// ✅ 好的实践: 使用连接池
public class GatewayProviderService {
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(Executors.newFixedThreadPool(50))  // 连接池
        .build();
}

// ❌ 不好的实践: 每次创建新连接
public CompletionResponse complete(ChatCompletionRequest request) {
    var client = HttpClient.newHttpClient();  // 每次创建
    return client.send(httpRequest, BodyHandlers.ofString());
}
```

💡 **原则**:
- 使用连接池
- 复用 HTTP 客户端
- 异步处理请求

---

## 6.10 性能优化

### 6.10.1 请求批处理

```java
public class BatchProcessor {
    private final BlockingQueue<BatchItem> queue = new LinkedBlockingQueue<>();
    private final int batchSize;
    private final Duration maxWaitTime;
    
    /**
     * 添加请求到批次
     */
    public CompletableFuture<CompletionResponse> addRequest(
            ChatCompletionRequest request) {
        var future = new CompletableFuture<CompletionResponse>();
        queue.offer(new BatchItem(request, future));
        return future;
    }
    
    /**
     * 处理批次
     */
    @Scheduled(fixedDelay = 100)  // 每 100ms 检查一次
    public void processBatch() {
        var items = new ArrayList<BatchItem>();
        queue.drainTo(items, batchSize);
        
        if (items.isEmpty()) return;
        
        // 合并请求
        var mergedRequest = mergeRequests(items);
        
        try {
            // 调用提供商
            var response = provider.complete(mergedRequest);
            
            // 拆分响应
            var responses = splitResponse(response, items.size());
            
            // 完成所有 future
            for (int i = 0; i < items.size(); i++) {
                items.get(i).future().complete(responses.get(i));
            }
        } catch (Exception e) {
            // 失败时拒绝所有 future
            items.forEach(item -> item.future().completeExceptionally(e));
        }
    }
}
```

💡 **优势**:
- 减少 API 调用次数
- 降低延迟
- 优化成本

### 6.10.2 语义缓存

```java
public class SemanticCache {
    @Inject
    private VectorStore vectorStore;
    
    @Inject
    private LLMProvider llmProvider;
    
    /**
     * 检查缓存
     */
    public Optional<CompletionResponse> get(String prompt) {
        // 1. 生成 prompt 的嵌入向量
        var embedding = llmProvider.embed(prompt);
        
        // 2. 搜索相似的缓存项
        var similar = vectorStore.search(embedding, 0.95);  // 相似度阈值
        
        if (similar.isEmpty()) {
            return Optional.empty();
        }
        
        // 3. 返回缓存的响应
        return Optional.of(similar.get(0).getResponse());
    }
    
    /**
     * 添加到缓存
     */
    public void put(String prompt, CompletionResponse response) {
        // 1. 生成嵌入向量
        var embedding = llmProvider.embed(prompt);
        
        // 2. 存储到向量库
        vectorStore.insert(CacheItem.builder()
            .prompt(prompt)
            .embedding(embedding)
            .response(response)
            .timestamp(LocalDateTime.now())
            .build());
    }
}
```

💡 **优势**:
- 减少重复请求
- 降低延迟
- 节省成本

---

## 6.11 验证学习成果

### 6.11.1 自测题

1. **LLM 网关的核心职责是什么？**
   - A. 存储 LLM 模型
   - B. 路由和管理 LLM 请求
   - C. 训练 LLM 模型
   - D. 评估 LLM 性能
   
   **答案**: B

2. **以下哪个不是负载均衡策略？**
   - A. 轮询
   - B. 加权轮询
   - C. 随机选择
   - D. 一致性哈希
   
   **答案**: C

3. **故障转移的目的是什么？**
   - A. 提高性能
   - B. 提高可用性
   - C. 降低成本
   - D. 简化代码
   
   **答案**: B

### 6.11.2 动手实践

1. **分析 GatewayModule**
   - 阅读 `GatewayModule.java`
   - 理解核心组件的依赖关系
   - 画出请求处理流程图

2. **实现自定义提供商**
   - 实现一个简单的 LLM 提供商
   - 支持基本的聊天完成
   - 注册到网关

3. **测试路由策略**
   - 实现多种路由策略
   - 测试不同场景下的路由选择
   - 分析性能指标

### 6.11.3 思考题

1. **如何支持动态添加和移除 LLM 提供商？**

2. **如何实现跨提供商的上下文共享？**

3. **如何优化大规模并发请求的处理？**

---

## 🎉 本章小结

本章你学会了:

✅ LLM 网关的核心概念和架构  
✅ GatewayProxyController 的实现细节  
✅ GatewayRoutingEngine 的路由机制  
✅ 多种负载均衡策略  
✅ 故障转移和降级策略  
✅ 限流和配额管理  
✅ 成本追踪和优化  
✅ 自定义提供商开发  
✅ 性能优化技巧  

---

## 🚀 下一步

准备好进入 **[07-可观测性](./07-可观测性.md)** 了吗？

下一章你将学习:
- OpenTelemetry 集成
- 追踪和日志
- 指标收集
- Langfuse 集成
- 性能监控

---

## 📚 参考资料

- [GatewayModule.java](../../../core-ai-server/src/main/java/ai/core/server/GatewayModule.java) - 网关模块
- [GatewayRoutingEngine.java](../../../core-ai-server/src/main/java/ai/core/server/gateway/GatewayRoutingEngine.java) - 路由引擎
- [GatewayProviderService.java](../../../core-ai-server/src/main/java/ai/core/server/gateway/GatewayProviderService.java) - 提供商服务

---

*最后更新: 2026-08-31*
