# 教程：记忆系统

本教程介绍 Core-AI 的记忆系统，用于构建能够记住和学习交互的智能代理。

## 目录

1. [概述](#概述)
2. [压缩（会话内）](#压缩会话内)
3. [长期记忆（跨会话）](#长期记忆跨会话)
4. [与 Agent 集成](#与-agent-集成)
5. [最佳实践](#最佳实践)

## 概述

Core-AI 提供两层记忆架构：

```
┌─────────────────────────────────────────────────────────────┐
│                      Agent 记忆系统                          │
├─────────────────────────────┬───────────────────────────────┤
│           压缩              │          长期记忆              │
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
| **压缩** | 在会话内维护对话上下文 |
| **长期记忆** | 跨会话记住用户偏好 |
| **两者结合** | 个性化助手，记住过往交互 |

## 压缩（会话内）

压缩管理单个会话内的对话上下文。它会自动总结长对话以保持在 token 限制内。

### 基本用法

```java
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;

String userId = "user-123";

// 压缩默认启用
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(llmProvider)
    .enableCompression(true)  // 默认值：true
    .build();

// 创建执行上下文（包含 userId）
ExecutionContext context = ExecutionContext.builder()
    .userId(userId)
    .build();

// Agent 在会话中记住之前的消息
agent.run("我叫张三", context);
agent.run("我叫什么名字？", context);  // 记住："张三"
```

### 配置选项

```java
import ai.core.memory.Compression;

// 创建自定义配置
Compression compression = new Compression(
    0.8,         // triggerThreshold - 触发压缩的阈值比例（默认 0.8，即 80%）
    5,           // keepRecentTurns - 保留最近的对话轮数（默认 5）
    llmProvider, // LLM 提供者（用于生成摘要）
    "gpt-4"      // 模型名称（用于获取最大 token 限制）
);

Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .compression(compression)
    .build();
```

### 压缩机制原理

压缩在每次 LLM 调用前（`beforeModel` 生命周期）同步触发：

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

### 禁用压缩

```java
Agent agent = Agent.builder()
    .name("stateless-agent")
    .llmProvider(llmProvider)
    .enableCompression(false)  // 禁用压缩
    .build();
```

## 长期记忆（跨会话）

长期记忆使用向量嵌入进行语义搜索，实现跨会话的用户信息持久化。

### 架构

```
┌────────────────────────────────────────────────────────────────┐
│                           Memory System                        │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌─────────────┐          ┌─────────────┐      ┌────────────┐ │
│  │  Extraction │─────────▶│ MemoryStore │◀─────│   Memory   │ │
│  │   (提取)     │   写入    │   (存储)    │  读取  │   (检索)   │ │
│  └─────────────┘          └─────────────┘      └────────────┘ │
│        │                                             │         │
│        ▼                                             ▼         │
│  ChatHistoryProvider                           LLMProvider    │
│  (用户实现)                                     (向量化)       │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### 核心概念

**职责分离**：
- `Extraction`：独立运行，从聊天历史提取记忆并存储
- `Memory`：仅用于检索，被 Agent 使用

**用户级隔离**：所有操作都通过 `ExecutionContext.userId` 隔离数据。

**LangMem 模式**：LLM 通过 Tool 主动决定何时查询记忆，而不是每次请求都自动注入。

### 记忆属性

每条记忆记录具有以下关键属性：

| 属性 | 描述 | 范围 |
|------|------|------|
| `importance` | 记忆的重要程度 | 0.0 - 1.0 |
| `decayFactor` | 时间衰减因子（随时间降低） | 0.0 - 1.0 |
| `accessCount` | 记忆被访问的次数 | 0+ |
| `createdAt` | 记忆创建时间 | Instant |
| `lastAccessedAt` | 最后访问时间 | Instant |

### 设置长期记忆

```java
import ai.core.memory.Memory;
import ai.core.memory.MemoryStore;
import ai.core.memory.Extraction;
import ai.core.memory.InMemoryStore;
import ai.core.memory.history.InMemoryChatHistoryProvider;

String userId = "user-123";

// 1. 创建共享存储
MemoryStore memoryStore = new InMemoryStore();
// 注意：如需使用 addRecord 等方法，请声明为 InMemoryChatHistoryProvider 类型
InMemoryChatHistoryProvider historyProvider = new InMemoryChatHistoryProvider();

// 2. 创建 Extraction（独立运行）
Extraction extraction = new Extraction(memoryStore, historyProvider, llmProvider);
// 或使用 Builder
Extraction extraction = Extraction.builder()
    .memoryStore(memoryStore)
    .historyProvider(historyProvider)
    .llmProvider(llmProvider)
    .model("gpt-4")  // 可选
    .build();

// 3. 创建 Memory（仅检索）
Memory memory = Memory.builder()
    .llmProvider(llmProvider)
    .memoryStore(memoryStore)
    .defaultTopK(5)  // 可选
    .build();

// 4. 创建 Agent
Agent agent = Agent.builder()
    .name("personalized-assistant")
    .llmProvider(llmProvider)
    .unifiedMemory(memory)  // 自动注册 MemoryRecallTool
    .build();
```

### 记忆提取

用户在适当时机（如会话结束）触发提取：

```java
// 使用 InMemoryChatHistoryProvider 记录对话历史
InMemoryChatHistoryProvider historyProvider = new InMemoryChatHistoryProvider();
historyProvider.addRecord(userId, ChatRecord.user("我喜欢深色模式", Instant.now()));
historyProvider.addRecord(userId, ChatRecord.assistant("好的！", Instant.now()));

// 会话结束后触发提取（同步执行）
extraction.run(userId);
```

### 记忆召回

当 LLM 需要查询用户记忆时，通过 MemoryRecallTool 触发：

```
用户: "你还记得我喜欢什么吗？"
     ↓
Agent 判断需要查记忆
     ↓
LLM 调用 search_memory_tool(query="用户喜欢什么")
     ↓
MemoryRecallTool.execute(args, context)
     ↓
userId = context.getUserId()  // 从 ExecutionContext 获取
memory.retrieve(userId, query)
     ↓
返回: [User Memory]
      - 用户喜欢深色模式
     ↓
LLM 生成回复: "我记得你喜欢深色模式。"
```

### userId 传递机制

`userId` 通过 `ExecutionContext` 在整个执行链中传递：

```
agent.run(query, context)
       ↓
Agent 存储 executionContext
       ↓
ToolExecutor.execute(functionCall, context)
       ↓
MemoryRecallTool.execute(args, context)
       ↓
String userId = context.getUserId()
       ↓
memory.retrieve(userId, query)
```

### 存储接口

**MemoryStore** - 记忆存储接口：

```java
public interface MemoryStore {
    // 保存记忆
    void save(String userId, MemoryRecord record);
    void save(String userId, MemoryRecord record, List<Double> embedding);
    void saveAll(String userId, List<MemoryRecord> records, List<List<Double>> embeddings);

    // 查询记忆
    Optional<MemoryRecord> findById(String userId, String id);
    List<MemoryRecord> findAll(String userId);
    List<MemoryRecord> searchByVector(String userId, List<Double> queryEmbedding, int topK);
    List<MemoryRecord> searchByKeyword(String userId, String keyword, int topK);

    // 删除记忆
    void delete(String userId, String id);
    void deleteAll(String userId);

    // 衰减管理
    void recordAccess(String userId, List<String> ids);
    void updateDecayFactor(String userId, String id, double decayFactor);
    List<MemoryRecord> findDecayed(String userId, double threshold);
    int deleteDecayed(String userId, double threshold);

    int count(String userId);
}
```

**ChatHistoryProvider** - 对话历史提供者接口（函数式接口）：

```java
@FunctionalInterface
public interface ChatHistoryProvider {
    List<ChatRecord> load(String userId);
}
```

框架内置了 `InMemoryChatHistoryProvider` 实现，提供 `addRecord` 等方法：

```java
public class InMemoryChatHistoryProvider implements ChatHistoryProvider {
    List<ChatRecord> load(String userId);
    void addRecord(String userId, ChatRecord record);
    void addRecords(String userId, List<ChatRecord> records);
    void clear(String userId);
}
```

### 自定义存储实现

```java
// 实现 MemoryStore 接口
public class MilvusMemoryStore implements MemoryStore {
    private final MilvusClient client;

    @Override
    public List<MemoryRecord> searchByVector(String userId, List<Double> embedding, int topK) {
        // 按 userId 隔离查询
        return client.search("memory_collection",
            "user_id == '" + userId + "'", embedding, topK);
    }
    // ... 其他方法
}

// 实现 ChatHistoryProvider 接口（函数式接口，仅需实现 load 方法）
public class DatabaseHistoryProvider implements ChatHistoryProvider {
    private final MessageRepository repository;

    @Override
    public List<ChatRecord> load(String userId) {
        return repository.findByUserId(userId);
    }
}

// 或使用 Lambda 表达式
ChatHistoryProvider historyProvider = userId -> repository.findByUserId(userId);
```

## 与 Agent 集成

### ExecutionContext

`ExecutionContext` 是 Agent 执行时的上下文，包含：

```java
public final class ExecutionContext {
    private final String sessionId;      // 会话ID
    private final String userId;         // 用户ID（用于记忆隔离）
    private final Map<String, Object> customVariables;  // 自定义变量
}
```

### 使用 unifiedMemory 配置

```java
import ai.core.memory.MemoryConfig;
import ai.core.agent.ExecutionContext;

// 推荐方式：使用 Agent builder
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(memory)  // 自动注册 MemoryRecallTool
    .build();

// 运行时传入 ExecutionContext
ExecutionContext context = ExecutionContext.builder()
    .userId("user-123")
    .sessionId("session-456")
    .build();

agent.run("你好", context);
```

```java
// 或者自定义配置
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(memory, MemoryConfig.builder()
        .maxRecallRecords(10)      // 最多返回 10 条记忆
        .autoRecall(true)          // 自动注册 MemoryRecallTool
        .build())
    .build();
```

### 完整示例

```java
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.memory.Memory;
import ai.core.memory.Extraction;
import ai.core.memory.InMemoryStore;
import ai.core.memory.history.ChatRecord;
import ai.core.memory.history.InMemoryChatHistoryProvider;

public class MemoryExample {
    public static void main(String[] args) {
        String userId = "user-123";

        // 共享存储
        var memoryStore = new InMemoryStore();
        var historyProvider = new InMemoryChatHistoryProvider();

        // 1. Extraction - 独立运行
        Extraction extraction = new Extraction(memoryStore, historyProvider, llmProvider);

        // 2. Memory - Agent 使用
        Memory memory = Memory.builder()
            .llmProvider(llmProvider)
            .memoryStore(memoryStore)
            .build();

        // 3. Agent
        Agent agent = Agent.builder()
            .name("assistant")
            .systemPrompt("你是个性化助手，使用记忆工具回忆用户偏好")
            .llmProvider(llmProvider)
            .unifiedMemory(memory)
            .build();

        // 4. 模拟对话并记录到 historyProvider
        historyProvider.addRecord(userId, ChatRecord.user("我喜欢 Vim 编辑器", Instant.now()));
        historyProvider.addRecord(userId, ChatRecord.assistant("好的，已记录！", Instant.now()));

        // 5. 会话结束后提取记忆
        extraction.run(userId);

        // 6. 下次会话时 Agent 可以检索记忆
        ExecutionContext context = ExecutionContext.builder()
            .userId(userId)
            .build();
        agent.run("你还记得我喜欢什么编辑器吗？", context);
    }
}
```

## 最佳实践

### 1. 选择正确的记忆类型

```java
// 无状态操作：禁用压缩
Agent statelessAgent = Agent.builder()
    .name("stateless")
    .llmProvider(llmProvider)
    .enableCompression(false)
    .build();

// 单会话：仅压缩（默认）
Agent sessionAgent = Agent.builder()
    .name("session")
    .llmProvider(llmProvider)
    .enableCompression(true)
    .build();

// 个性化体验：两种记忆都用
Agent personalizedAgent = Agent.builder()
    .name("personalized")
    .llmProvider(llmProvider)
    .enableCompression(true)   // 会话内
    .unifiedMemory(memory)     // 跨会话
    .build();
```

### 2. 正确使用 ExecutionContext

```java
// 创建带 userId 的上下文
ExecutionContext context = ExecutionContext.builder()
    .userId("user-123")
    .sessionId("session-456")  // 可选
    .customVariable("key", value)  // 可选：自定义变量
    .build();

// 每次调用 agent.run 时传入 context
String response = agent.run("查询内容", context);
```

### 3. 生产环境存储设置

```java
// 开发环境：内存存储
MemoryStore devStore = new InMemoryStore();
ChatHistoryProvider devHistory = new InMemoryChatHistoryProvider();

// 生产环境：持久化存储
MemoryStore prodStore = new MilvusMemoryStore(milvusClient);
ChatHistoryProvider prodHistory = new DatabaseHistoryProvider(repository);
// 或使用 Lambda
ChatHistoryProvider prodHistory = userId -> repository.findByUserId(userId);
```

### 4. 提取时机

```java
// 方式1：会话结束时提取
public void onSessionEnd(String userId) {
    extraction.run(userId);
    historyProvider.clear(userId);  // 可选：清理历史
}

// 方式2：定时任务批量提取
@Scheduled(cron = "0 0 * * * *")  // 每小时
public void batchExtraction() {
    for (String userId : activeUsers) {
        extraction.run(userId);
    }
}
```

### 5. 服务层封装

```java
public class ChatService {
    private final Agent agent;
    private final Extraction extraction;
    private final InMemoryChatHistoryProvider historyProvider;

    public String chat(String userId, String message) {
        // 记录用户消息
        historyProvider.addRecord(userId, ChatRecord.user(message, Instant.now()));

        // 创建上下文并执行
        ExecutionContext context = ExecutionContext.builder()
            .userId(userId)
            .build();
        String response = agent.run(message, context);

        // 记录助手回复
        historyProvider.addRecord(userId, ChatRecord.assistant(response, Instant.now()));

        return response;
    }

    public void endSession(String userId) {
        extraction.run(userId);
        historyProvider.clear(userId);
    }
}
```

## 总结

本教程涵盖的关键概念：

1. **压缩**：基于会话的对话历史，带自动总结
   - 触发条件：token 数量超过阈值（默认 80%）
   - 压缩策略：保留系统消息 + 最近 N 轮 + 当前对话链

2. **长期记忆**：持久化用户记忆，支持向量语义搜索
   - `Extraction`：独立提取器，从聊天历史提取记忆
   - `Memory`：检索器，被 Agent 使用

3. **ExecutionContext**：执行上下文，传递 userId 等信息
   - 通过 `context.getUserId()` 实现用户级数据隔离

4. **LangMem 模式**：LLM 通过 Tool 主动查询记忆

下一步：
- 学习[工具调用](tutorial-tool-calling.md)扩展代理能力
- 探索 [RAG 集成](tutorial-rag.md)实现知识增强
- 构建[多代理系统](tutorial-multi-agent.md)处理复杂应用
