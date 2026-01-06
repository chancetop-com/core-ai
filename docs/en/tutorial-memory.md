# Tutorial: Memory Systems

This tutorial covers Core-AI's memory systems for building agents that remember and learn from interactions.

## Table of Contents

1. [Overview](#overview)
2. [Short-term Memory](#short-term-memory)
3. [Long-term Memory](#long-term-memory)
4. [Agent Integration](#agent-integration)
5. [Best Practices](#best-practices)

## Overview

Core-AI provides a two-tier memory architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                      Agent Memory System                     │
├─────────────────────────────┬───────────────────────────────┤
│      Short-term Memory      │       Long-term Memory        │
│  (Session/Conversation)     │    (Persistent/Cross-session) │
├─────────────────────────────┼───────────────────────────────┤
│ • Message history           │ • User preferences            │
│ • Conversation summary      │ • Facts and knowledge         │
│ • Auto-summarization        │ • Goals and intents           │
│ • Token management          │ • Past interactions           │
├─────────────────────────────┼───────────────────────────────┤
│ Lifecycle: Within session   │ Lifecycle: Across sessions    │
│ Storage: In-memory          │ Storage: User-defined (Vector DB, etc.) │
└─────────────────────────────┴───────────────────────────────┘
```

### When to Use Each Type

| Memory Type | Use Case |
|-------------|----------|
| **Short-term** | Maintaining conversation context within a session |
| **Long-term** | Remembering user preferences across sessions |
| **Both** | Personalized assistants that remember past interactions |

## Short-term Memory

Short-term memory manages conversation context within a single session. It automatically summarizes long conversations to stay within token limits.

### Basic Usage

```java
import ai.core.agent.Agent;

// Short-term memory is enabled by default
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(llmProvider)
    .enableShortTermMemory(true)  // Default: true
    .build();

// Agent remembers previous messages in the session
agent.execute("My name is John");
agent.execute("What's my name?");  // Remembers: "John"
```

### Configuration Options

```java
import ai.core.memory.ShortTermMemory;

// Create with custom configuration
ShortTermMemory memory = new ShortTermMemory(
    0.8,         // triggerThreshold - ratio to trigger compression (default 0.8, i.e., 80%)
    5,           // keepRecentTurns - number of recent turns to keep (default 5)
    llmProvider, // LLM provider (for generating summaries)
    "gpt-4"      // model name (for getting max token limit)
);

Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .shortTermMemory(memory)
    .build();
```

### How Compression Works

Short-term memory compression triggers synchronously before each LLM call (in the `beforeModel` lifecycle):

```
┌──────────────────────────────────────────────────────────┐
│                   Compression Flow                        │
├──────────────────────────────────────────────────────────┤
│  1. Check if current token count exceeds threshold        │
│     (threshold = model max context × triggerThreshold)    │
│          ↓                                               │
│  2. If exceeded, perform compression:                     │
│     • Preserve system message                            │
│     • Keep recent N turns (keepRecentTurns)              │
│     • If N turns still exceed, keep only current chain   │
│          ↓                                               │
│  3. Call LLM to generate summary                         │
│          ↓                                               │
│  4. Summary injected as Tool Call message:               │
│     [ASSISTANT] tool_call: memory_compress               │
│     [TOOL] [Previous Conversation Summary]...            │
└──────────────────────────────────────────────────────────┘
```

### Compression Algorithm Details

**1. Trigger Condition**

```java
boolean shouldCompress = currentTokens >= maxContextTokens * triggerThreshold;
// Example: max 128K, threshold 0.8, triggers when tokens >= 102400
```

**2. Message Splitting Strategy**

```
Original message list:
┌─────────────────────────────────────────────────────────┐
│ [SYSTEM] You are a helpful assistant...                 │  ← Always kept
├─────────────────────────────────────────────────────────┤
│ [USER] First question                                   │
│ [ASSISTANT] First answer                                │
│ [USER] Second question                                  │  ← Compressed to summary
│ [ASSISTANT] Second answer                               │
│ ...more history...                                      │
├─────────────────────────────────────────────────────────┤
│ [USER] Recent question 1                                │
│ [ASSISTANT] Recent answer 1                             │  ← Keep recent N turns
│ [USER] Recent question 2                                │
│ [ASSISTANT] tool_call: get_weather                      │  ← Current chain
│ [TOOL] Beijing 25°C                                     │
└─────────────────────────────────────────────────────────┘
```

**3. Conversation Chain Protection**

When the last message is not USER (executing Tool Call), compression protects the current conversation chain:

```java
// Find the position of last USER message
boolean isCurrentChainActive = messages.getLast().role != RoleType.USER;
if (isCurrentChainActive) {
    // Protect entire chain starting from last USER
    minKeepFromIndex = lastUserIndex;
}
```

**4. Summary Generation**

Summary is generated via LLM, target tokens: `min(4000, max(500, maxContext/10))`:

```
Summary format:
[Previous Conversation Summary]
User asked about weather, assistant retrieved Beijing weather info...
[End of Summary]
```

**5. Final Message Structure**

```
Compressed message list:
┌─────────────────────────────────────────────────────────┐
│ [SYSTEM] You are a helpful assistant...                 │
│ [ASSISTANT] tool_call: memory_compress                  │  ← Virtual Tool Call
│ [TOOL] [Previous Conversation Summary]...               │  ← Summary content
│ [USER] Recent question                                  │  ← Kept messages
│ [ASSISTANT] ...                                         │
└─────────────────────────────────────────────────────────┘
```

### Disabling Short-term Memory

```java
Agent agent = Agent.builder()
    .name("stateless-agent")
    .llmProvider(llmProvider)
    .enableShortTermMemory(false)  // Disable short-term memory
    .build();
```

## Long-term Memory

Long-term memory persists user information across sessions using vector embeddings for semantic search.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Agent                                       │
│                                │                                         │
│     ┌──────────────────────────┴──────────────────────────┐             │
│     │                                                      │             │
│     ▼                                                      ▼             │
│  MemoryRecallTool ◄────── LongTermMemory ──────► LongTermMemoryCoordinator
│  (LLM proactively calls)       │                          │             │
│                                │                          │             │
│                    ┌───────────┴───────────┐              │             │
│                    ▼                       ▼              ▼             │
│              MemoryStore          ChatHistoryStore   MemoryExtractor    │
│              (Memory storage)    (Conversation store)  (LLM extraction) │
│                    │                       │              │             │
│                    ▼                       ▼              ▼             │
│            User implementation      User implementation  DefaultMemoryExtractor
│           (Milvus/Redis/etc.)      (MySQL/Redis/etc.)                   │
└─────────────────────────────────────────────────────────────────────────┘
```

### Core Concepts

**User-Controlled Data Isolation**: The framework does not enforce isolation strategies. Users decide how to isolate data (by tenant, user, etc.) when implementing `MemoryStore` and `ChatHistoryStore`.

**LangMem Pattern**: LLM proactively decides when to query memories via Tool, rather than automatically injecting on every request. This is smarter and saves tokens.

### Memory Attributes

Each memory record has the following key attributes:

| Attribute | Description | Range |
|-----------|-------------|-------|
| `importance` | How important the memory is | 0.0 - 1.0 |
| `decayFactor` | Time-based decay (decreases over time) | 0.0 - 1.0 |
| `accessCount` | How often the memory has been accessed | 0+ |
| `createdAt` | When the memory was created | Instant |
| `lastAccessedAt` | When the memory was last accessed | Instant |

**Importance Guidelines:**
- **0.9-1.0**: Critical personal info (name, core preferences, important goals)
- **0.7-0.8**: Useful context (occupation, interests, ongoing projects)
- **0.5-0.6**: Nice to know (casual mentions, minor preferences)
- **Below 0.5**: Not worth storing

### Memory Extraction Mechanism

The core of long-term memory is automatically extracting valuable information from conversations:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Extraction Flow                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. Message arrives                                                     │
│     ┌─────────────────┐                                                 │
│     │ onMessage(msg)  │                                                 │
│     └────────┬────────┘                                                 │
│              ↓                                                          │
│  2. Persist to ChatHistoryStore                                         │
│     ┌─────────────────────────────────────┐                             │
│     │ chatHistoryStore.save(sessionId, msg)│                            │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  3. Check if extraction should trigger (user turns >= maxBufferTurns)   │
│     ┌─────────────────────────────────────┐                             │
│     │ if (userTurnCount >= 5) trigger()   │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  4. Load unextracted messages                                           │
│     ┌─────────────────────────────────────┐                             │
│     │ loadUnextracted(sessionId)          │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  5. LLM extracts memories (async or sync)                               │
│     ┌─────────────────────────────────────┐                             │
│     │ extractor.extract(messages)         │                             │
│     │                                     │                             │
│     │ Input: "User: I'm John, a developer"│                             │
│     │        "Assistant: Hi John!"        │                             │
│     │                                     │                             │
│     │ Output: [                           │                             │
│     │   {content: "User name is John",    │                             │
│     │    importance: 0.9},               │                             │
│     │   {content: "User is a developer",  │                             │
│     │    importance: 0.7}                │                             │
│     │ ]                                  │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  6. Generate embedding vectors                                          │
│     ┌─────────────────────────────────────┐                             │
│     │ llmProvider.embeddings(contents)    │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  7. Save to MemoryStore                                                 │
│     ┌─────────────────────────────────────┐                             │
│     │ memoryStore.saveAll(records, embeds)│                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  8. Mark extracted position                                             │
│     ┌─────────────────────────────────────┐                             │
│     │ markExtracted(sessionId, lastIndex) │                             │
│     └─────────────────────────────────────┘                             │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Extraction Prompt Example**:

```
Extract important facts, preferences, and information about the user from
the following conversation. Return a JSON array of memories.

Conversation:
User: I'm John, working as a developer in Beijing
Assistant: Hi John! How's work going?
User: Pretty good, I mainly write Java

Output format:
[
  {"content": "User name is John", "importance": 0.9},
  {"content": "User works in Beijing", "importance": 0.7},
  {"content": "User is a developer", "importance": 0.8},
  {"content": "User mainly uses Java", "importance": 0.7}
]
```

### Memory Recall Mechanism

When LLM needs to query user memories, it triggers recall via MemoryRecallTool:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Recall Flow                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  1. LLM decides to query memories                                       │
│     ┌─────────────────────────────────────────────────────────────────┐ │
│     │ User: "Do you remember what I do for work?"                     │ │
│     │ LLM: Need to look up user info... → call search_memory_tool     │ │
│     └────────┬────────────────────────────────────────────────────────┘ │
│              ↓                                                          │
│  2. MemoryRecallTool receives query                                     │
│     ┌─────────────────────────────────────┐                             │
│     │ query = "user's job"                │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  3. Generate query vector                                               │
│     ┌─────────────────────────────────────┐                             │
│     │ queryEmbedding = embed(query)       │                             │
│     │ [0.12, -0.34, 0.56, ...]           │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  4. Vector similarity search                                            │
│     ┌─────────────────────────────────────┐                             │
│     │ memoryStore.searchByVector(         │                             │
│     │   queryEmbedding,                   │                             │
│     │   topK = 5                          │                             │
│     │ )                                   │                             │
│     │                                     │                             │
│     │ Calculate cosine similarity:        │                             │
│     │ similarity = cosine(query, memory)  │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  5. Return relevant memories                                            │
│     ┌─────────────────────────────────────┐                             │
│     │ [User Memory]                       │                             │
│     │ - User is a developer (sim: 0.89)   │                             │
│     │ - User mainly uses Java (sim: 0.76) │                             │
│     └────────┬────────────────────────────┘                             │
│              ↓                                                          │
│  6. LLM generates response based on memories                            │
│     ┌─────────────────────────────────────────────────────────────────┐ │
│     │ "I remember you're a developer who mainly works with Java."     │ │
│     └─────────────────────────────────────────────────────────────────┘ │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Setting Up Long-term Memory

```java
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.InMemoryStore;
import ai.core.memory.history.InMemoryChatHistoryStore;

// Create stores (users can provide custom implementations)
MemoryStore memoryStore = new InMemoryStore();
ChatHistoryStore chatHistoryStore = new InMemoryChatHistoryStore();

// Build long-term memory
LongTermMemory longTermMemory = LongTermMemory.builder()
    .llmProvider(llmProvider)
    .memoryStore(memoryStore)
    .chatHistoryStore(chatHistoryStore)
    .config(LongTermMemoryConfig.builder()
        .maxBufferTurns(5)           // Trigger extraction every 5 turns
        .asyncExtraction(true)        // Async extraction
        .extractOnSessionEnd(true)    // Extract remaining messages on session end
        .build())
    .build();
```

### Custom Storage Implementation

Users control data isolation themselves:

```java
// Create separate store instance per user
public class UserMemoryStoreFactory {
    private final MilvusClient milvusClient;

    public MemoryStore createForUser(String userId) {
        return new MilvusMemoryStore(milvusClient, "memory_" + userId);
    }
}

// Or handle isolation within the implementation
public class MultiTenantMemoryStore implements MemoryStore {
    private final String tenantId;
    private final MilvusClient client;

    public MultiTenantMemoryStore(String tenantId, MilvusClient client) {
        this.tenantId = tenantId;
        this.client = client;
    }

    @Override
    public List<MemoryRecord> searchByVector(List<Double> embedding, int topK) {
        // Decide which collection to query and how to filter
        return client.search("memory_" + tenantId, embedding, topK);
    }

    // ... other method implementations
}
```

### Session Management

```java
// Start session
longTermMemory.startSession("session-123");

// Record conversation messages (auto-saved + triggers extraction)
longTermMemory.onMessage(Message.user("I like spicy food"));
longTermMemory.onMessage(Message.assistant("Got it, I'll remember that"));

// Manually recall memories
List<MemoryRecord> memories = longTermMemory.recall("What does the user like?", 5);

// Format as context
String context = longTermMemory.formatAsContext(memories);
// Output: [User Memory]
//         - User likes spicy food

// End session (triggers final extraction)
longTermMemory.endSession();
```

### Storage Interfaces

**MemoryStore** - Memory storage interface:

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

**ChatHistoryStore** - Conversation history storage interface:

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

## Agent Integration

### Using unifiedMemory Configuration

```java
import ai.core.memory.UnifiedMemoryConfig;

// Recommended: Using Agent builder
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(longTermMemory)  // Auto-registers Tool + Lifecycle
    .build();

// Or with custom configuration
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
        .maxRecallRecords(5)       // Return max 5 memories
        .autoRecall(true)          // Auto-register MemoryRecallTool
        .build())
    .build();
```

### LangMem Pattern Workflow

```
User: "Do you remember what food I like?"
     ↓
Agent decides to query memory
     ↓
LLM calls search_memory_tool(query="what food does user like")
     ↓
MemoryRecallTool.execute() → longTermMemory.recall()
     ↓
Returns: [User Memory]
         - User likes spicy food
     ↓
LLM generates response: "I remember you like spicy food. Would you like me to recommend some Sichuan dishes?"
```

### Tool Description

The `MemoryRecallTool` description tells LLM when to invoke it:

> "Search and recall relevant memories about the user. Use this tool when you need to personalize your response based on user preferences, recall something the user mentioned before, or reference past interactions."

## Best Practices

### 1. Choose the Right Memory Type

```java
// Stateless operations: disable short-term memory
Agent statelessAgent = Agent.builder()
    .enableShortTermMemory(false)
    .build();

// Single session: short-term only (default)
Agent sessionAgent = Agent.builder()
    .enableShortTermMemory(true)
    .build();

// Personalized experience: use both memories
Agent personalizedAgent = Agent.builder()
    .enableShortTermMemory(true)
    .unifiedMemory(longTermMemory)
    .build();
```

### 2. Production Storage Setup

```java
// Development: in-memory stores
MemoryStore devStore = new InMemoryStore();
ChatHistoryStore devHistory = new InMemoryChatHistoryStore();

// Production: create separate store per user
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

### 3. Extraction Configuration Optimization

```java
LongTermMemoryConfig config = LongTermMemoryConfig.builder()
    .maxBufferTurns(5)           // Trigger extraction every 5 user messages
    .asyncExtraction(true)       // Async extraction, don't block response
    .extractOnSessionEnd(true)   // Extract remaining messages on session end
    .extractionTimeout(Duration.ofSeconds(30))  // Extraction timeout
    .build();
```

### 4. Using SearchFilter

```java
// Query only high-importance memories
SearchFilter filter = SearchFilter.builder()
    .minImportance(0.7)
    .build();

List<MemoryRecord> memories = store.searchByVector(embedding, 5, filter);

// Filter by time
SearchFilter recentFilter = SearchFilter.builder()
    .createdAfter(Instant.now().minus(Duration.ofDays(30)))
    .build();
```

## Summary

Key concepts covered in this tutorial:

1. **Short-term Memory**: Session-based conversation history with auto-summarization
   - Trigger condition: token count exceeds threshold (default 80%)
   - Compression strategy: keep system message + recent N turns + current chain
   - Summary injection: via virtual Tool Call message

2. **Long-term Memory**: Persistent user memories with vector semantic search
   - Extraction mechanism: LLM extracts important information from conversations
   - Recall mechanism: vector similarity search

3. **LangMem Pattern**: LLM proactively queries memories via Tool

4. **User-Controlled Isolation**: Framework doesn't enforce isolation; users implement custom storage

5. **Dual Storage Design**: `MemoryStore` (memories) + `ChatHistoryStore` (conversation history)

Next steps:
- Learn [Tool Calling](tutorial-tool-calling.md) to extend agent capabilities
- Explore [RAG Integration](tutorial-rag.md) for knowledge enhancement
- Build [Multi-Agent Systems](tutorial-multi-agent.md) for complex applications
