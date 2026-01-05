# 教程：记忆系统

本教程介绍 Core-AI 的记忆系统，用于构建能够记住和学习交互的智能代理。

## 目录

1. [概述](#概述)
2. [短期记忆](#短期记忆)
3. [长期记忆](#长期记忆)
4. [统一记忆生命周期](#统一记忆生命周期)
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
│ 存储：内存                  │ 存储：向量数据库 / SQLite       │
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

### 对话链保护

当 Agent 正在执行工具调用时（最后一条消息是 TOOL），压缩会保护当前对话链不被截断：

```
┌──────────────────────────────────────────────────────────┐
│  示例：工具调用过程中触发压缩                               │
├──────────────────────────────────────────────────────────┤
│  [历史消息...]  ← 这部分会被压缩成摘要                      │
│  [USER] 帮我查询天气                                      │
│  [ASSISTANT] tool_call: get_weather                      │
│  [TOOL] 北京今天 25°C，晴  ← 当前对话链保护                 │
└──────────────────────────────────────────────────────────┘
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
┌─────────────────────────────────────────────────────┐
│                    长期记忆                          │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────┐ │
│  │    召回     │    │    提取     │    │  存储   │ │
│  │  (搜索)     │    │  (保存)     │    │ (向量)  │ │
│  └──────┬──────┘    └──────┬──────┘    └────┬────┘ │
│         │                  │                │      │
│         └──────────────────┼────────────────┘      │
│                            │                       │
│                    ┌───────┴───────┐               │
│                    │   命名空间     │               │
│                    │ (用户/组织/...)│               │
│                    └───────────────┘               │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 记忆类型

| 类型 | 描述 | 示例 |
|------|------|------|
| `FACT` | 关于用户的事实信息 | "是软件工程师" |
| `PREFERENCE` | 用户偏好和喜好 | "喜欢简洁的回复" |
| `GOAL` | 用户目标和意图 | "正在学习机器学习" |
| `EPISODE` | 值得注意的过往交互 | "昨天问过 Python 问题" |

### 设置长期记忆

```java
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.DefaultLongTermMemoryStore;

// 创建存储（开发用内存，生产用 SQLite）
var store = DefaultLongTermMemoryStore.inMemory();
// 或者: var store = DefaultLongTermMemoryStore.withSqlite(dataSource);

// 构建长期记忆
LongTermMemory longTermMemory = LongTermMemory.builder()
    .llmProvider(llmProvider)
    .store(store)
    .config(LongTermMemoryConfig.builder()
        .embeddingDimension(1536)      // 取决于嵌入模型
        .asyncExtraction(true)          // 异步记忆提取
        .extractionBatchSize(5)         // 每批提取的消息数
        .build())
    .build();
```

### 命名空间组织

命名空间按作用域组织记忆：

```java
import ai.core.memory.longterm.Namespace;

// 用户作用域（最常用）
Namespace userNs = Namespace.forUser("user-123");

// 组织作用域（组织内共享）
Namespace orgNs = Namespace.of("acme-corp", null);

// 会话作用域（临时）
Namespace sessionNs = Namespace.forSession("session-456");
```

### 手动记忆操作

```java
// 开始会话
longTermMemory.startSessionForUser("user-123", "session-456");

// 召回相关记忆
List<MemoryRecord> memories = longTermMemory.recall(
    "编程偏好",  // 查询
    5            // 返回前 K 条
);

// 按类型过滤召回
List<MemoryRecord> prefs = longTermMemory.recall(
    "偏好设置",
    5,
    MemoryType.PREFERENCE, MemoryType.FACT
);

// 格式化记忆为上下文
String context = longTermMemory.formatAsContext(memories);

// 结束会话
longTermMemory.endSession();
```

## 统一记忆生命周期

`UnifiedMemoryLifecycle` 在 LLM 调用前自动注入长期记忆。

### 工作原理

```
┌───────────────────────────────────────────────────────────┐
│                   Agent 执行流程                           │
├───────────────────────────────────────────────────────────┤
│                                                           │
│   用户查询                                                 │
│       │                                                   │
│       ▼                                                   │
│   ┌─────────────────────────────────────┐                │
│   │     beforeAgentRun()                │                │
│   │     • 初始化会话                     │                │
│   │     • 从 userId 设置命名空间         │                │
│   └─────────────────────────────────────┘                │
│       │                                                   │
│       ▼                                                   │
│   ┌─────────────────────────────────────┐                │
│   │     beforeModel()                   │                │
│   │     • 提取用户查询                   │                │
│   │     • 召回相关记忆                   │                │
│   │     • 注入为 Tool Call 消息          │                │
│   └─────────────────────────────────────┘                │
│       │                                                   │
│       ▼                                                   │
│   [带记忆上下文的 LLM 调用]                                │
│       │                                                   │
│       ▼                                                   │
│   ┌─────────────────────────────────────┐                │
│   │     afterAgentRun()                 │                │
│   │     • 结束会话                       │                │
│   │     • 重置状态                       │                │
│   └─────────────────────────────────────┘                │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

### 与 Agent 集成

```java
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.UnifiedMemoryLifecycle;

// 方式一：使用 Agent builder（推荐）
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
        .maxRecallRecords(5)       // 最大注入记忆条数
        .build())
    .build();

// 方式二：手动设置生命周期
UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(
    longTermMemory,
    5  // maxRecallRecords
);
agent.addLifecycle(lifecycle);
```

### 配置选项

```java
UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
    .maxRecallRecords(5)          // 最大召回记忆数（1-20）
    .memoryBudgetRatio(0.2)       // 记忆的 token 预算（5%-50%）
    .build();
```

### 记忆注入格式

记忆作为 Tool Call 消息注入以保持一致性：

```
发送给 LLM 的消息:
┌────────────────────────────────────────────────────┐
│ [SYSTEM] 你是一个有帮助的助手...                    │
├────────────────────────────────────────────────────┤
│ [ASSISTANT] tool_call: recall_long_term_memory     │
├────────────────────────────────────────────────────┤
│ [TOOL] [User Memory]                               │
│ - 用户喜欢简洁的回复                                │
│ - 用户正在学习 Python                              │
│ - 用户从事数据科学工作                              │
├────────────────────────────────────────────────────┤
│ [USER] 帮我处理 pandas 数据框                       │
└────────────────────────────────────────────────────┘
```

### 执行上下文

要启用记忆查找，需要在 ExecutionContext 中提供 userId：

```java
import ai.core.agent.ExecutionContext;

ExecutionContext context = ExecutionContext.builder()
    .userId("user-123")         // 记忆查找必需
    .sessionId("session-456")   // 可选
    .build();

AgentOutput output = agent.execute("帮我写代码", context);
```

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
    .unifiedMemory(longTermMemory, config)
    .build();
```

### 2. 管理 Token 预算

```java
// 对于上下文窗口较小的模型
UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
    .maxRecallRecords(3)          // 较少记忆
    .memoryBudgetRatio(0.1)       // 10% 的上下文
    .build();

// 对于上下文窗口较大的模型
UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
    .maxRecallRecords(10)
    .memoryBudgetRatio(0.3)
    .build();
```

### 3. 生产环境存储设置

```java
// 开发环境：内存存储
var devStore = DefaultLongTermMemoryStore.inMemory();

// 生产环境：SQLite 持久化
var prodStore = DefaultLongTermMemoryStore.withSqlite(dataSource);

// 大规模：自定义向量存储（Milvus 等）
var scaleStore = new CustomVectorStore(milvusClient);
```

### 4. 优雅处理缺失上下文

```java
// 生命周期优雅处理缺失上下文
// - 没有 userId：跳过记忆注入
// - 没有找到记忆：继续执行，不注入
// - 错误：记录日志，但不使请求失败

AgentOutput output = agent.execute("query");  // 无上下文也能工作
AgentOutput output = agent.execute("query", context);  // 带上下文
```

## 总结

本教程涵盖的关键概念：

1. **短期记忆**：基于会话的对话历史，带自动总结
2. **长期记忆**：带向量搜索的持久化用户记忆
3. **统一记忆生命周期**：自动将记忆注入 LLM 调用
4. **记忆类型**：FACT、PREFERENCE、GOAL、EPISODE
5. **命名空间**：用户、组织、会话作用域

下一步：
- 学习[工具调用](tutorial-tool-calling.md)扩展代理能力
- 探索 [RAG 集成](tutorial-rag.md)实现知识增强
- 构建[多代理系统](tutorial-multi-agent.md)处理复杂应用
