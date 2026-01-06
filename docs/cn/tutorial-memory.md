# 教程：记忆系统

本教程介绍 Core-AI 的记忆系统，用于构建能够记住和学习交互的智能代理。

## 目录

1. [概述](#概述)
2. [短期记忆](#短期记忆)
3. [长期记忆](#长期记忆)
4. [与 Agent 集成](#与-agent-集成)
5. [最佳实践](#最佳实践)

## 概述

Core-AI 提供两层记忆架构：

```
┌─────────────────────────────────────────────────────────────┐
│                      Agent 记忆系统                          │
├─────────────────────────────┬───────────────────────────────┤
│         短期记忆            │          长期记忆              │
│    (会话/对话级别)          │    (持久化/跨会话)             │
├─────────────────────────────┼───────────────────────────────┤
│ • 消息历史                  │ • 用户偏好                     │
│ • 对话摘要                  │ • 事实和知识                   │
│ • 自动总结                  │ • 目标和意图                   │
│ • Token 管理                │ • 历史交互                     │
├─────────────────────────────┼───────────────────────────────┤
│ 生命周期：会话内            │ 生命周期：跨会话                │
│ 存储：内存                  │ 存储：用户自定义（向量数据库等）  │
└─────────────────────────────┴───────────────────────────────┘
```

### 使用场景

| 记忆类型 | 使用场景 |
|---------|---------|
| **短期记忆** | 在会话内维护对话上下文 |
| **长期记忆** | 跨会话记住用户偏好 |
| **两者结合** | 个性化助手，记住过往交互 |

## 短期记忆

短期记忆管理单个会话内的对话上下文。它会自动总结长对话以保持在 token 限制内。

### 基本用法

```java
import ai.core.agent.Agent;

// 短期记忆默认启用
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(llmProvider)
    .enableShortTermMemory(true)  // 默认值：true
    .build();

// Agent 在会话中记住之前的消息
agent.execute("我叫张三");
agent.execute("我叫什么名字？");  // 记住："张三"
```

### 配置选项

```java
import ai.core.memory.ShortTermMemory;

// 创建自定义配置
ShortTermMemory memory = new ShortTermMemory(
    0.8,         // triggerThreshold - 触发压缩的阈值比例（默认 0.8，即 80%）
    5,           // keepRecentTurns - 保留最近的对话轮数（默认 5）
    llmProvider, // LLM 提供者（用于生成摘要）
    "gpt-4"      // 模型名称（用于获取最大 token 限制）
);

Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .shortTermMemory(memory)
    .build();
```

### 压缩机制原理

短期记忆的压缩在每次 LLM 调用前（`beforeModel` 生命周期）同步触发：

```
┌──────────────────────────────────────────────────────────┐
│                      压缩流程                             │
├──────────────────────────────────────────────────────────┤
│  1. 检查当前 Token 数量是否超过阈值                         │
│     （阈值 = 模型最大上下文 × triggerThreshold）            │
│          ↓                                               │
│  2. 如果超过阈值，执行压缩：                                │
│     • 保留系统消息                                        │
│     • 保留最近 N 轮对话（keepRecentTurns）                  │
│     • 如果 N 轮仍超过阈值，只保留当前对话链                  │
│          ↓                                               │
│  3. 调用 LLM 生成摘要                                      │
│          ↓                                               │
│  4. 摘要作为 Tool Call 消息注入：                          │
│     [ASSISTANT] tool_call: memory_compress                │
│     [TOOL] [Previous Conversation Summary]...             │
└──────────────────────────────────────────────────────────┘
```

### 压缩算法详解

**1. 触发条件判断**

```java
boolean shouldCompress = currentTokens >= maxContextTokens * triggerThreshold;
// 例如：模型最大 128K，阈值 0.8，当 tokens >= 102400 时触发
```

**2. 消息分割策略**

```
原始消息列表：
┌─────────────────────────────────────────────────────────┐
│ [SYSTEM] 你是一个助手...                                 │  ← 始终保留
├─────────────────────────────────────────────────────────┤
│ [USER] 第一个问题                                        │
│ [ASSISTANT] 第一个回答                                   │
│ [USER] 第二个问题                                        │  ← 被压缩为摘要
│ [ASSISTANT] 第二个回答                                   │
│ ...更多历史对话...                                       │
├─────────────────────────────────────────────────────────┤
│ [USER] 最近的问题 1                                      │
│ [ASSISTANT] 最近的回答 1                                 │  ← 保留最近 N 轮
│ [USER] 最近的问题 2                                      │
│ [ASSISTANT] tool_call: get_weather                      │  ← 当前对话链
│ [TOOL] 北京 25°C                                        │
└─────────────────────────────────────────────────────────┘
```

**3. 对话链保护**

当最后一条消息不是 USER 时（正在执行 Tool Call），压缩会保护当前对话链不被截断：

```java
// 找到最后一个 USER 消息的位置
boolean isCurrentChainActive = messages.getLast().role != RoleType.USER;
if (isCurrentChainActive) {
    // 保护从最后一个 USER 开始的整个对话链
    minKeepFromIndex = lastUserIndex;
}
```

**4. 摘要生成**

压缩后的摘要通过 LLM 生成，目标 token 数为 `min(4000, max(500, maxContext/10))`：

```
摘要格式：
[Previous Conversation Summary]
用户询问了天气情况，助手通过工具获取了北京的天气信息...
[End of Summary]
```

**5. 最终消息结构**

```
压缩后的消息列表：
┌─────────────────────────────────────────────────────────┐
│ [SYSTEM] 你是一个助手...                                 │
│ [ASSISTANT] tool_call: memory_compress                  │  ← 虚拟 Tool Call
│ [TOOL] [Previous Conversation Summary]...               │  ← 摘要内容
│ [USER] 最近的问题                                        │  ← 保留的消息
│ [ASSISTANT] ...                                         │
└─────────────────────────────────────────────────────────┘
```

### 禁用短期记忆

```java
Agent agent = Agent.builder()
    .name("stateless-agent")
    .llmProvider(llmProvider)
    .enableShortTermMemory(false)  // 禁用短期记忆
    .build();
```

## 长期记忆

长期记忆使用向量嵌入进行语义搜索，实现跨会话的用户信息持久化。

### 架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Agent                                       │
│                                │                                         │
│     ┌──────────────────────────┴──────────────────────────┐             │
│     │                                                      │             │
│     ▼                                                      ▼             │
│  MemoryRecallTool ◄────── LongTermMemory ──────► LongTermMemoryCoordinator
│  (LLM主动调用查询)              │                          │             │
│                                │                          │             │
│                    ┌───────────┴───────────┐              │             │
│                    ▼                       ▼              ▼             │
│              MemoryStore          ChatHistoryStore   MemoryExtractor    │
│              (记忆存储)            (对话历史存储)      (LLM提取记忆)      │
│                    │                       │              │             │
│                    ▼                       ▼              ▼             │
│            用户自定义实现           用户自定义实现    DefaultMemoryExtractor
│           (Milvus/Redis等)        (MySQL/Redis等)                       │
└─────────────────────────────────────────────────────────────────────────┘
```

### 核心概念

**用户控制数据隔离**：框架不强制隔离策略，用户在实现 `MemoryStore` 和 `ChatHistoryStore` 时自己决定如何隔离数据（按租户、用户等）。

**LangMem 模式**：LLM 通过 Tool 主动决定何时查询记忆，而不是每次请求都自动注入，更智能且节省 token。

### 记忆属性

每条记忆记录具有以下关键属性：

| 属性 | 描述 | 范围 |
|------|------|------|
| `importance` | 记忆的重要程度 | 0.0 - 1.0 |
| `decayFactor` | 时间衰减因子（随时间降低） | 0.0 - 1.0 |
| `accessCount` | 记忆被访问的次数 | 0+ |
| `createdAt` | 记忆创建时间 | Instant |
| `lastAccessedAt` | 最后访问时间 | Instant |

**重要性指南：**
- **0.9-1.0**：关键个人信息（姓名、核心偏好、重要目标）
- **0.7-0.8**：有用的上下文（职业、兴趣、正在进行的项目）
- **0.5-0.6**：可有可无的信息（随意提及、次要偏好）
- **低于 0.5**：不值得存储

### 记忆提取机制

长期记忆的核心是从对话中自动提取有价值的信息：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           提取流程                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. 消息进入                                                             │
│     ┌─────────────────┐                                                 │
│     │ onMessage(msg)  │                                                 │
│     └────────┬────────┘                                                 │
│              ↓                                                          │
│  2. 持久化到 ChatHistoryStore                                            │
│     ┌─────────────────────────────────────┐                             │
│     │ chatHistoryStore.save(sessionId, msg)│                            │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  3. 检查是否触发提取（用户消息数 >= maxBufferTurns）                       │
│     ┌─────────────────────────────────────┐                             │
│     │ if (userTurnCount >= 5) trigger()   │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  4. 获取未提取的消息                                                     │
│     ┌─────────────────────────────────────┐                             │
│     │ loadUnextracted(sessionId)          │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  5. LLM 提取记忆（异步或同步）                                            │
│     ┌─────────────────────────────────────┐                             │
│     │ extractor.extract(messages)         │                             │
│     │                                     │                             │
│     │ 输入: "User: 我叫张三，是程序员"      │                             │
│     │       "Assistant: 你好张三！"        │                             │
│     │                                     │                             │
│     │ 输出: [                             │                             │
│     │   {content: "用户名字是张三",        │                             │
│     │    importance: 0.9},               │                             │
│     │   {content: "用户是程序员",          │                             │
│     │    importance: 0.7}                │                             │
│     │ ]                                  │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  6. 生成 Embedding 向量                                                  │
│     ┌─────────────────────────────────────┐                             │
│     │ llmProvider.embeddings(contents)    │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  7. 保存到 MemoryStore                                                   │
│     ┌─────────────────────────────────────┐                             │
│     │ memoryStore.saveAll(records, embeds)│                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  8. 标记已提取位置                                                        │
│     ┌─────────────────────────────────────┐                             │
│     │ markExtracted(sessionId, lastIndex) │                             │
│     └─────────────────────────────────────┘                             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**提取 Prompt 示例**：

```
Extract important facts, preferences, and information about the user from
the following conversation. Return a JSON array of memories.

Conversation:
User: 我叫张三，在北京做程序员
Assistant: 你好张三！程序员工作怎么样？
User: 还不错，我主要写 Java

Output format:
[
  {"content": "用户名字是张三", "importance": 0.9},
  {"content": "用户在北京工作", "importance": 0.7},
  {"content": "用户是程序员", "importance": 0.8},
  {"content": "用户主要使用 Java 编程", "importance": 0.7}
]
```

### 记忆召回机制

当 LLM 需要查询用户记忆时，通过 MemoryRecallTool 触发召回：

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           召回流程                                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. LLM 判断需要查询记忆                                                  │
│     ┌─────────────────────────────────────────────────────────────────┐ │
│     │ 用户: "你还记得我是做什么工作的吗？"                               │ │
│     │ LLM: 我需要查询用户信息... → 调用 search_memory_tool             │ │
│     └────────┬────────────────────────────────────────────────────────┘ │
│              ↓                                                          │
│  2. MemoryRecallTool 接收查询                                            │
│     ┌─────────────────────────────────────┐                             │
│     │ query = "用户的工作"                 │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  3. 生成查询向量                                                         │
│     ┌─────────────────────────────────────┐                             │
│     │ queryEmbedding = embed(query)       │                             │
│     │ [0.12, -0.34, 0.56, ...]           │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  4. 向量相似度搜索                                                        │
│     ┌─────────────────────────────────────┐                             │
│     │ memoryStore.searchByVector(         │                             │
│     │   queryEmbedding,                   │                             │
│     │   topK = 5                          │                             │
│     │ )                                   │                             │
│     │                                     │                             │
│     │ 计算余弦相似度:                       │                             │
│     │ similarity = cosine(query, memory)  │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  5. 返回相关记忆                                                         │
│     ┌─────────────────────────────────────┐                             │
│     │ [User Memory]                       │                             │
│     │ - 用户是程序员 (similarity: 0.89)    │                             │
│     │ - 用户主要使用 Java (similarity: 0.76)│                            │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  6. LLM 基于记忆生成回复                                                  │
│     ┌─────────────────────────────────────────────────────────────────┐ │
│     │ "我记得你是一名程序员，主要使用 Java 进行开发。"                    │ │
│     └─────────────────────────────────────────────────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 设置长期记忆

```java
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.history.InMemoryChatHistoryStore;

// 创建存储（用户可自定义实现）
MemoryStore memoryStore = new InMemoryStore();
ChatHistoryStore chatHistoryStore = new InMemoryChatHistoryStore();

// 构建长期记忆
LongTermMemory longTermMemory = LongTermMemory.builder()
    .llmProvider(llmProvider)
    .memoryStore(memoryStore)
    .chatHistoryStore(chatHistoryStore)
    .config(LongTermMemoryConfig.builder()
        .maxBufferTurns(5)           // 每 5 轮触发一次提取
        .asyncExtraction(true)        // 异步提取
        .extractOnSessionEnd(true)    // 会话结束时提取剩余消息
        .build())
    .build();
```

### 自定义存储实现

用户自己控制数据隔离：

```java
// 为每个用户创建独立的存储实例
public class UserMemoryStoreFactory {
    private final MilvusClient milvusClient;

    public MemoryStore createForUser(String userId) {
        return new MilvusMemoryStore(milvusClient, "memory_" + userId);
    }
}

// 或者在实现内部处理隔离
public class MultiTenantMemoryStore implements MemoryStore {
    private final String tenantId;
    private final MilvusClient client;

    public MultiTenantMemoryStore(String tenantId, MilvusClient client) {
        this.tenantId = tenantId;
        this.client = client;
    }

    @Override
    public List<MemoryRecord> searchByVector(List<Double> embedding, int topK) {
        // 自己决定查哪个 collection、怎么筛选
        return client.search("memory_" + tenantId, embedding, topK);
    }

    // ... 其他方法实现
}
```

### 会话管理

```java
// 开始会话
longTermMemory.startSession("session-123");

// 记录对话消息（自动存储 + 触发提取）
longTermMemory.onMessage(Message.user("我喜欢吃辣"));
longTermMemory.onMessage(Message.assistant("好的，我记住了"));

// 手动召回记忆
List<MemoryRecord> memories = longTermMemory.recall("用户喜欢什么", 5);

// 格式化为上下文
String context = longTermMemory.formatAsContext(memories);
// 输出: [User Memory]
//       - 用户喜欢吃辣的食物

// 结束会话（触发最后一次提取）
longTermMemory.endSession();
```

### 存储接口

**MemoryStore** - 记忆存储接口：

```java
public interface MemoryStore {
    void save(MemoryRecord record, List<Double> embedding);
    void saveAll(List<MemoryRecord> records, List<List<Double>> embeddings);
    List<MemoryRecord> searchByVector(List<Double> queryEmbedding, int topK);
    List<MemoryRecord> searchByVector(List<Double> queryEmbedding, int topK, SearchFilter filter);
    List<MemoryRecord> searchByKeyword(String keyword, int topK);
    void delete(String id);
    int count();
    // ...
}
```

**ChatHistoryStore** - 对话历史存储接口：

```java
public interface ChatHistoryStore {
    void save(String sessionId, Message message);
    List<Message> load(String sessionId);
    List<Message> loadRecent(String sessionId, int limit);
    void markExtracted(String sessionId, int messageIndex);
    List<Message> loadUnextracted(String sessionId);
    // ...
}
```

## 与 Agent 集成

### 使用 unifiedMemory 配置

```java
import ai.core.memory.UnifiedMemoryConfig;

// 推荐方式：使用 Agent builder
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(longTermMemory)  // 自动注册 Tool + Lifecycle
    .build();

// 或者自定义配置
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
        .maxRecallRecords(5)       // 最多返回 5 条记忆
        .autoRecall(true)          // 自动注册 MemoryRecallTool
        .build())
    .build();
```

### LangMem 模式工作流程

```
用户: "你还记得我喜欢吃什么吗？"
     ↓
Agent 判断需要查记忆
     ↓
LLM 调用 search_memory_tool(query="用户喜欢吃什么")
     ↓
MemoryRecallTool.execute() → longTermMemory.recall()
     ↓
返回: [User Memory]
      - 用户喜欢吃辣的食物
     ↓
LLM 生成回复: "我记得你喜欢吃辣的，要不要推荐川菜？"
```

### Tool 描述

`MemoryRecallTool` 的描述告诉 LLM 何时该调用：

> "Search and recall relevant memories about the user. Use this tool when you need to personalize your response based on user preferences, recall something the user mentioned before, or reference past interactions."

## 最佳实践

### 1. 选择正确的记忆类型

```java
// 无状态操作：禁用短期记忆
Agent statelessAgent = Agent.builder()
    .enableShortTermMemory(false)
    .build();

// 单会话：仅短期记忆（默认）
Agent sessionAgent = Agent.builder()
    .enableShortTermMemory(true)
    .build();

// 个性化体验：两种记忆都用
Agent personalizedAgent = Agent.builder()
    .enableShortTermMemory(true)
    .unifiedMemory(longTermMemory)
    .build();
```

### 2. 生产环境存储设置

```java
// 开发环境：内存存储
MemoryStore devStore = new InMemoryStore();
ChatHistoryStore devHistory = new InMemoryChatHistoryStore();

// 生产环境：为每个用户创建独立存储
public LongTermMemory createForUser(String userId) {
    MemoryStore store = new MilvusMemoryStore(userId);
    ChatHistoryStore history = new RedisChatHistoryStore(userId);

    return LongTermMemory.builder()
        .llmProvider(llmProvider)
        .memoryStore(store)
        .chatHistoryStore(history)
        .config(LongTermMemoryConfig.builder()
            .maxBufferTurns(5)
            .asyncExtraction(true)
            .build())
        .build();
}
```

### 3. 提取配置优化

```java
LongTermMemoryConfig config = LongTermMemoryConfig.builder()
    .maxBufferTurns(5)           // 每 5 轮用户消息触发提取
    .asyncExtraction(true)       // 异步提取，不阻塞响应
    .extractOnSessionEnd(true)   // 会话结束时提取剩余消息
    .extractionTimeout(Duration.ofSeconds(30))  // 提取超时
    .build();
```

### 4. 使用 SearchFilter 过滤

```java
// 只查询高重要性记忆
SearchFilter filter = SearchFilter.builder()
    .minImportance(0.7)
    .build();

List<MemoryRecord> memories = store.searchByVector(embedding, 5, filter);

// 按时间过滤
SearchFilter recentFilter = SearchFilter.builder()
    .createdAfter(Instant.now().minus(Duration.ofDays(30)))
    .build();
```

## 总结

本教程涵盖的关键概念：

1. **短期记忆**：基于会话的对话历史，带自动总结
   - 触发条件：token 数量超过阈值（默认 80%）
   - 压缩策略：保留系统消息 + 最近 N 轮 + 当前对话链
   - 摘要注入：通过虚拟 Tool Call 消息

2. **长期记忆**：持久化用户记忆，支持向量语义搜索
   - 提取机制：LLM 从对话中提取重要信息
   - 召回机制：向量相似度搜索

3. **LangMem 模式**：LLM 通过 Tool 主动查询记忆

4. **用户控制隔离**：框架不强制隔离策略，用户自定义存储实现

5. **双存储设计**：`MemoryStore`（记忆）+ `ChatHistoryStore`（对话历史）

下一步：
- 学习[工具调用](tutorial-tool-calling.md)扩展代理能力
- 探索 [RAG 集成](tutorial-rag.md)实现知识增强
- 构建[多代理系统](tutorial-multi-agent.md)处理复杂应用
