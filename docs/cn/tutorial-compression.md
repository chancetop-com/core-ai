# 教程：压缩机制（上下文管理）

本教程介绍 Core-AI 的压缩机制，用于管理会话内的对话上下文。

## 目录

1. [概述](#概述)
2. [压缩原理](#压缩原理)
3. [配置](#配置)
4. [压缩算法](#压缩算法)
5. [最佳实践](#最佳实践)

## 概述

压缩是 Core-AI 用于在 token 限制内管理长对话的解决方案。当对话变得过长时，压缩会自动总结较旧的消息，同时保留最近的上下文和重要信息。

### 核心特性

- **自动触发**：当 token 数量超过阈值时自动压缩
- **上下文保留**：保持系统消息、最近对话轮次和当前对话链
- **LLM 生成摘要**：使用 LLM 创建智能摘要
- **透明集成**：通过 Agent 生命周期无缝工作

### 压缩触发时机

```
Token 使用时间线：
|========================================|
0%                                     100% (最大上下文)
                      ^
                      |
              触发点 (默认 80%)
```

## 压缩原理

### 压缩流程

```
+------------------------------------------------------------------+
|                          压缩流程                                  |
+------------------------------------------------------------------+
|                                                                  |
|  1. Agent 接收用户消息                                            |
|          |                                                       |
|          v                                                       |
|  2. beforeModel 生命周期钩子触发                                   |
|          |                                                       |
|          v                                                       |
|  3. CompressionLifecycle 检查 token 数量                          |
|     currentTokens >= maxContext * triggerThreshold?              |
|          |                                                       |
|     否 --+-- 是                                                   |
|     |         |                                                  |
|     v         v                                                  |
|  正常       4. 拆分消息：                                          |
|  继续          - 系统消息（始终保留）                               |
|                - 要压缩的消息（较旧）                               |
|                - 要保留的消息（最近 N 轮 + 当前链）                  |
|                    |                                             |
|                    v                                             |
|                5. 通过 LLM 生成摘要                                |
|                    |                                             |
|                    v                                             |
|                6. 重建消息列表：                                    |
|                   [系统消息] + [摘要作为ToolCall] + [最近消息]       |
|                    |                                             |
|                    v                                             |
|                7. 使用压缩后的消息继续                              |
|                                                                  |
+------------------------------------------------------------------+
```

### 压缩后的消息结构

压缩前：
```
[SYSTEM]     你是一个有帮助的助手...
[USER]       第一个问题
[ASSISTANT]  第一个回答
[USER]       第二个问题
[ASSISTANT]  第二个回答
... (更多消息)
[USER]       最近的问题
[ASSISTANT]  最近的回答（带工具调用）
[TOOL]       工具结果
```

压缩后：
```
[SYSTEM]     你是一个有帮助的助手...
[ASSISTANT]  tool_call: memory_compress {}
[TOOL]       [Previous Conversation Summary]
             - 用户询问了 X，助手解释了...
             - 做出的关键决定：...
             [End Summary]
[USER]       最近的问题
[ASSISTANT]  最近的回答（带工具调用）
[TOOL]       工具结果
```

## 配置

### 基本用法

```java
import ai.core.agent.Agent;

// 压缩默认启用
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(llmProvider)
    .build();
```

### 自定义配置

```java
import ai.core.compression.Compression;

// 使用自定义设置创建压缩
Compression compression = new Compression(
    0.8,         // triggerThreshold: 在最大上下文的 80% 时压缩（默认）
    5,           // keepRecentTurns: 保留最近 5 轮对话（默认）
    llmProvider, // 用于生成摘要的 LLM 提供者
    "gpt-4"      // 用于 token 计数的模型名称
);

Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .compression(compression)
    .build();
```

### 配置参数

| 参数 | 默认值 | 描述 |
|------|--------|------|
| `triggerThreshold` | 0.8 | 触发压缩的最大上下文比例 (0.0-1.0) |
| `keepRecentTurns` | 5 | 要保留的最近对话轮数 |
| `llmProvider` | 必需 | 用于生成摘要的 LLM 提供者 |
| `model` | 必需 | 用于确定最大上下文 token 数的模型名称 |

### 禁用压缩

```java
Agent agent = Agent.builder()
    .name("stateless-agent")
    .llmProvider(llmProvider)
    .enableCompression(false)  // 完全禁用压缩
    .build();
```

## 压缩算法

压缩算法是 Core-AI 的核心机制之一，它确保长对话能够在 LLM 的上下文窗口限制内有效运行。

### 算法设计原则

1. **保护关键信息**：系统消息和当前对话链始终被保护
2. **智能拆分**：根据对话轮次而非单纯 token 数量进行拆分
3. **LLM 生成摘要**：使用 LLM 生成高质量的对话摘要
4. **无缝集成**：压缩结果以伪工具调用形式注入，对 LLM 透明

### 核心算法流程

```
┌─────────────────────────────────────────────────────────────────┐
│                      压缩算法核心流程                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  输入: messages (当前消息列表)                                   │
│                                                                 │
│  Step 1: 触发检查                                               │
│    currentTokens >= maxContext * triggerThreshold ?             │
│    └─ 否: 返回原消息列表                                        │
│                                                                 │
│  Step 2: 消息分类                                               │
│    ├─ systemMsg: 系统消息 (始终保留)                            │
│    ├─ conversationMsgs: 对话消息                                │
│    └─ currentChain: 当前未完成的对话链 (受保护)                  │
│                                                                 │
│  Step 3: 计算保留边界                                           │
│    keepFromIndex = min(                                         │
│        findKeepFromIndexByTurns(),  // 最近 N 轮                │
│        minKeepFromIndex             // 当前链起点                │
│    )                                                            │
│                                                                 │
│  Step 4: 拆分消息                                               │
│    toCompress = conversationMsgs[0:keepFromIndex]               │
│    toKeep = conversationMsgs[keepFromIndex:]                    │
│                                                                 │
│  Step 5: 生成摘要                                               │
│    summary = llmProvider.complete(summarizePrompt + toCompress) │
│                                                                 │
│  Step 6: 组装结果                                               │
│    result = [systemMsg]                                         │
│           + [ASSISTANT: tool_call(memory_compress)]             │
│           + [TOOL: summary]                                     │
│           + toKeep                                              │
│                                                                 │
│  输出: result (压缩后的消息列表)                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 步骤 1：检查触发条件

```java
boolean shouldCompress = currentTokens >= maxContextTokens * triggerThreshold;
// 示例：最大 128K tokens，阈值 0.8
// 当 tokens >= 102,400 时触发
```

### 步骤 2：消息拆分策略

算法将消息分为三类：

```
原始消息：
+-----------------------------------------------------------+
| [SYSTEM] 你是一个有帮助的助手...              | 始终保留   |
|-----------------------------------------------------------|
| [USER] 问题 1                                 |           |
| [ASSISTANT] 回答 1                            | 要压缩    |
| [USER] 问题 2                                 |           |
| [ASSISTANT] 回答 2                            |           |
| ... 更多旧消息 ...                            |           |
|-----------------------------------------------------------|
| [USER] 最近问题 1                             |           |
| [ASSISTANT] 最近回答 1                        | 保留      |
| [USER] 最近问题 2                             | (最近     |
| [ASSISTANT] tool_call: get_weather           | N 轮      |
| [TOOL] 北京 25℃                              | + 链)     |
+-----------------------------------------------------------+
```

### 步骤 3：对话链保护

当最后一条消息不是来自 USER 时（例如，在工具执行期间），当前对话链受到保护：

```java
// 找到最后一条 USER 消息
boolean isCurrentChainActive = messages.getLast().role != RoleType.USER;
if (isCurrentChainActive) {
    // 保护从最后一条 USER 消息开始的整个链
    minKeepFromIndex = lastUserIndex;
}
```

这确保工具调用序列不会被打断：
```
[USER]      天气怎么样？          <- 链从这里开始
[ASSISTANT] tool_call: get_weather <- 受保护
[TOOL]      结果: 25℃             <- 受保护（当前）
```

### 步骤 4：摘要生成

通过 LLM 生成摘要，目标 token 数：

```java
int targetTokens = Math.min(4000, Math.max(500, maxContextTokens / 10));
int targetWords = (int) (targetTokens * 0.75);
```

**摘要提示模板：**
```
Summarize the following conversation into a concise summary.
Requirements:
1. Preserve key facts, decisions, and context
2. Keep important user preferences and goals mentioned
3. Remove redundant back-and-forth and filler content
4. Use bullet points for clarity
5. Keep within {targetWords} words

Conversation to summarize:
{formatted_messages}

Output summary directly:
```

### 步骤 5：结果组装

最终消息列表结构：

```java
List<Message> result = new ArrayList<>();
result.add(systemMessage);           // 系统提示
result.add(toolCallMessage);         // [ASSISTANT] tool_call: memory_compress
result.add(toolResultMessage);       // [TOOL] 摘要内容
result.addAll(messagesToKeep);       // 最近消息
```

## 最佳实践

### 1. 选择合适的阈值

```java
// 对于大上下文模型（128K+）
// 较高阈值允许压缩前保留更多历史
Compression compression = new Compression(0.85, 5, llmProvider, model);

// 对于较小上下文模型（8K-32K）
// 较低阈值为响应留出更多空间
Compression compression = new Compression(0.7, 3, llmProvider, model);
```

### 2. 根据用例调整保留轮数

```java
// 客户支持：保留更多上下文
Compression compression = new Compression(0.8, 8, llmProvider, model);

// 快速问答：需要较少轮次
Compression compression = new Compression(0.8, 3, llmProvider, model);
```

### 3. 监控 Token 使用

```java
Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .compression(compression)
    .build();

// 执行后检查 token 使用情况
agent.run("问题", context);
TokenUsage usage = agent.getCurrentTokenUsage();
System.out.println("总 tokens: " + usage.getTotalTokens());
```

### 4. 与长期记忆结合

```java
// 压缩用于会话上下文
// 长期记忆用于跨会话持久化
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .enableCompression(true)    // 会话内
    .unifiedMemory(memory)      // 跨会话
    .build();
```

### 5. 处理边缘情况

```java
// 非常短的对话：压缩不会触发
// 非常长的单条消息：可能立即超过阈值

// 在应用中考虑消息长度限制
if (userMessage.length() > MAX_MESSAGE_LENGTH) {
    userMessage = truncate(userMessage, MAX_MESSAGE_LENGTH);
}
```

## 实现细节

### 核心类

| 类 | 位置 | 描述 |
|----|------|------|
| `Compression` | `ai.core.compression` | 主要压缩逻辑 |
| `CompressionLifecycle` | `ai.core.compression` | Agent 生命周期集成 |
| `MessageTokenCounter` | `ai.core.compression` | Token 计数工具 |

### 生命周期集成

压缩通过 Agent 生命周期系统集成：

```java
// 在 AgentBuilder.copyValue() 中
if (this.compressionEnabled) {
    agent.compression = this.compression != null
        ? this.compression
        : new Compression(this.llmProvider, this.model);
    agent.agentLifecycles.add(new CompressionLifecycle(agent.compression));
}
```

### Token 计数

Token 计数使用模型特定的分词器：

```java
// MessageTokenCounter 计算消息列表的 tokens
int currentTokens = MessageTokenCounter.count(messages);

// LLMModelContextRegistry 提供每个模型的最大输入 tokens
int maxTokens = LLMModelContextRegistry.getInstance().getMaxInputTokens(model);
```

## 总结

本教程涵盖的关键概念：

1. **自动上下文管理**：当 token 使用超过阈值时自动触发压缩
2. **智能拆分**：保留系统消息、最近轮次和当前对话链
3. **LLM 驱动的摘要**：使用 LLM 创建有意义的压缩内容摘要
4. **透明集成**：通过 Agent 生命周期工作，无需手动干预
5. **可配置行为**：可调节阈值、保留轮数，可禁用

下一步：
- 学习[长期记忆](tutorial-memory.md)实现跨会话持久化
- 探索[工具调用](tutorial-tool-calling.md)扩展代理能力
- 构建复杂工作流[流程编排](tutorial-flow.md)
