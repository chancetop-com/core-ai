# Tutorial: Compression (Context Management)

This tutorial covers Core-AI's compression mechanism for managing conversation context within sessions.

## Table of Contents

1. [Overview](#overview)
2. [How Compression Works](#how-compression-works)
3. [Configuration](#configuration)
4. [Compression Algorithm](#compression-algorithm)
5. [Best Practices](#best-practices)

## Overview

Compression is Core-AI's solution for managing long conversations within token limits. When conversations become too long, compression automatically summarizes older messages while preserving recent context and important information.

### Key Features

- **Automatic Triggering**: Compresses when token count exceeds threshold
- **Context Preservation**: Keeps system messages, recent turns, and current conversation chain
- **LLM-Generated Summaries**: Uses LLM to create intelligent summaries
- **Transparent Integration**: Works seamlessly through Agent lifecycle

### When Compression Triggers

```
Token Usage Timeline:
|========================================|
0%                                     100% (max context)
                      ^
                      |
              Trigger Point (default 80%)
```

## How Compression Works

### Compression Flow

```
+------------------------------------------------------------------+
|                       Compression Flow                            |
+------------------------------------------------------------------+
|                                                                  |
|  1. Agent receives user message                                  |
|          |                                                       |
|          v                                                       |
|  2. beforeModel lifecycle hook triggered                         |
|          |                                                       |
|          v                                                       |
|  3. CompressionLifecycle checks token count                      |
|     currentTokens >= maxContext * triggerThreshold?              |
|          |                                                       |
|     No --+-- Yes                                                 |
|     |         |                                                  |
|     v         v                                                  |
|  Continue   4. Split messages:                                   |
|  normally      - System message (always keep)                    |
|                - Messages to compress (older)                    |
|                - Messages to keep (recent N turns + current)     |
|                    |                                             |
|                    v                                             |
|                5. Generate summary via LLM                       |
|                    |                                             |
|                    v                                             |
|                6. Rebuild message list:                          |
|                   [System] + [Summary as ToolCall] + [Recent]    |
|                    |                                             |
|                    v                                             |
|                7. Continue with compressed messages              |
|                                                                  |
+------------------------------------------------------------------+
```

### Message Structure After Compression

Before compression:
```
[SYSTEM]     You are a helpful assistant...
[USER]       First question
[ASSISTANT]  First answer
[USER]       Second question
[ASSISTANT]  Second answer
... (many more messages)
[USER]       Recent question
[ASSISTANT]  Recent answer (with tool call)
[TOOL]       Tool result
```

After compression:
```
[SYSTEM]     You are a helpful assistant...
[ASSISTANT]  tool_call: memory_compress {}
[TOOL]       [Previous Conversation Summary]
             - User asked about X, assistant explained...
             - Key decisions made: ...
             [End Summary]
[USER]       Recent question
[ASSISTANT]  Recent answer (with tool call)
[TOOL]       Tool result
```

## Configuration

### Basic Usage

```java
import ai.core.agent.Agent;

// Compression is enabled by default
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(llmProvider)
    .build();
```

### Custom Configuration

```java
import ai.core.compression.Compression;

// Create compression with custom settings
Compression compression = new Compression(
    0.8,         // triggerThreshold: compress at 80% of max context (default)
    5,           // keepRecentTurns: keep last 5 conversation turns (default)
    llmProvider, // LLM provider for generating summaries
    "gpt-4"      // model name for token counting
);

Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .compression(compression)
    .build();
```

### Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `triggerThreshold` | 0.8 | Ratio of max context to trigger compression (0.0-1.0) |
| `keepRecentTurns` | 5 | Number of recent conversation turns to preserve |
| `llmProvider` | required | LLM provider for generating summaries |
| `model` | required | Model name for determining max context tokens |

### Disabling Compression

```java
Agent agent = Agent.builder()
    .name("stateless-agent")
    .llmProvider(llmProvider)
    .enableCompression(false)  // Disable compression entirely
    .build();
```

## Compression Algorithm

### Step 1: Check Trigger Condition

```java
boolean shouldCompress = currentTokens >= maxContextTokens * triggerThreshold;
// Example: max 128K tokens, threshold 0.8
// Triggers when tokens >= 102,400
```

### Step 2: Message Splitting Strategy

The algorithm splits messages into three categories:

```
Original Messages:
+-----------------------------------------------------------+
| [SYSTEM] You are a helpful assistant...       | Always    |
|-----------------------------------------------------------|
| [USER] Question 1                             |           |
| [ASSISTANT] Answer 1                          | To be     |
| [USER] Question 2                             | Compressed|
| [ASSISTANT] Answer 2                          |           |
| ... more old messages ...                     |           |
|-----------------------------------------------------------|
| [USER] Recent question 1                      |           |
| [ASSISTANT] Recent answer 1                   | Keep      |
| [USER] Recent question 2                      | (recent   |
| [ASSISTANT] tool_call: get_weather            | N turns   |
| [TOOL] Beijing 25C                            | + chain)  |
+-----------------------------------------------------------+
```

### Step 3: Conversation Chain Protection

When the last message is not from USER (e.g., during tool execution), the current conversation chain is protected:

```java
// Find the last USER message
boolean isCurrentChainActive = messages.getLast().role != RoleType.USER;
if (isCurrentChainActive) {
    // Protect entire chain starting from last USER message
    minKeepFromIndex = lastUserIndex;
}
```

This ensures tool call sequences are not broken:
```
[USER]      What's the weather?      <- Chain starts here
[ASSISTANT] tool_call: get_weather   <- Protected
[TOOL]      Result: 25C              <- Protected (current)
```

### Step 4: Summary Generation

Summary is generated via LLM with target token count:

```java
int targetTokens = Math.min(4000, Math.max(500, maxContextTokens / 10));
int targetWords = (int) (targetTokens * 0.75);
```

**Summary Prompt Template:**
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

### Step 5: Result Assembly

The final message list structure:

```java
List<Message> result = new ArrayList<>();
result.add(systemMessage);           // System prompt
result.add(toolCallMessage);         // [ASSISTANT] tool_call: memory_compress
result.add(toolResultMessage);       // [TOOL] Summary content
result.addAll(messagesToKeep);       // Recent messages
```

## Best Practices

### 1. Choose Appropriate Threshold

```java
// For models with large context (128K+)
// Higher threshold allows more history before compression
Compression compression = new Compression(0.85, 5, llmProvider, model);

// For models with smaller context (8K-32K)
// Lower threshold leaves more room for responses
Compression compression = new Compression(0.7, 3, llmProvider, model);
```

### 2. Adjust Keep Recent Turns Based on Use Case

```java
// Customer support: keep more context
Compression compression = new Compression(0.8, 8, llmProvider, model);

// Quick Q&A: fewer turns needed
Compression compression = new Compression(0.8, 3, llmProvider, model);
```

### 3. Monitor Token Usage

```java
Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .compression(compression)
    .build();

// After execution, check token usage
agent.run("question", context);
TokenUsage usage = agent.getCurrentTokenUsage();
System.out.println("Total tokens: " + usage.getTotalTokens());
```

### 4. Combine with Long-term Memory

```java
// Compression for session context
// Long-term memory for cross-session persistence
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .enableCompression(true)    // Within session
    .unifiedMemory(memory)      // Across sessions
    .build();
```

### 5. Handle Edge Cases

```java
// Very short conversations: compression won't trigger
// Very long single messages: may exceed threshold immediately

// Consider message length limits in your application
if (userMessage.length() > MAX_MESSAGE_LENGTH) {
    userMessage = truncate(userMessage, MAX_MESSAGE_LENGTH);
}
```

## Implementation Details

### Core Classes

| Class | Location | Description |
|-------|----------|-------------|
| `Compression` | `ai.core.compression` | Main compression logic |
| `CompressionLifecycle` | `ai.core.compression` | Agent lifecycle integration |
| `MessageTokenCounter` | `ai.core.compression` | Token counting utility |

### Lifecycle Integration

Compression integrates through the Agent lifecycle system:

```java
// In AgentBuilder.copyValue()
if (this.compressionEnabled) {
    agent.compression = this.compression != null
        ? this.compression
        : new Compression(this.llmProvider, this.model);
    agent.agentLifecycles.add(new CompressionLifecycle(agent.compression));
}
```

### Token Counting

Token counting uses model-specific tokenizers:

```java
// MessageTokenCounter counts tokens for message list
int currentTokens = MessageTokenCounter.count(messages);

// LLMModelContextRegistry provides max input tokens per model
int maxTokens = LLMModelContextRegistry.getInstance().getMaxInputTokens(model);
```

## Summary

Key concepts covered in this tutorial:

1. **Automatic Context Management**: Compression triggers automatically when token usage exceeds threshold
2. **Intelligent Splitting**: Preserves system message, recent turns, and current conversation chain
3. **LLM-Powered Summarization**: Uses LLM to create meaningful summaries of compressed content
4. **Transparent Integration**: Works through Agent lifecycle without manual intervention
5. **Configurable Behavior**: Adjustable threshold, recent turns, and can be disabled

Next steps:
- Learn about [Long-term Memory](tutorial-memory.md) for cross-session persistence
- Explore [Tool Calling](tutorial-tool-calling.md) to extend agent capabilities
- Build [Multi-Agent Systems](tutorial-multi-agent.md) for complex applications
