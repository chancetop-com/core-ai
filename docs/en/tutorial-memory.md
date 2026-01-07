# Tutorial: Memory Systems

This tutorial covers Core-AI's memory systems for building agents that remember and learn from interactions.

## Table of Contents

1. [Overview](#overview)
2. [Memory System](#memory-system)
3. [Agent Integration](#agent-integration)
4. [Best Practices](#best-practices)

## Overview

Core-AI's memory system persists user information, supporting cross-session storage of user preferences, facts, and interaction history.

```
+-------------------------------------------------------------+
|                    Memory System Architecture                |
+-------------------------------------------------------------+
|                                                             |
|  Core Capabilities:                                         |
|  - User preference persistence                              |
|  - Facts and knowledge storage                              |
|  - Goals and intents recording                              |
|  - Interaction history tracking                             |
|                                                             |
|  Features:                                                  |
|  - Vector semantic search                                   |
|  - User-level data isolation                                |
|  - LangMem pattern (on-demand retrieval)                    |
|                                                             |
|  Lifecycle: Across sessions                                 |
|  Storage: User-defined (Vector DB, etc.)                    |
|                                                             |
+-------------------------------------------------------------+
```

## Memory System

The memory system persists user information across sessions using vector embeddings for semantic search.

### Architecture

```
+----------------------------------------------------------------+
|                         Memory System                           |
+----------------------------------------------------------------+
|                                                                |
|  +-----------+          +-------------+      +------------+    |
|  | Extraction|--------->| MemoryStore |<-----| Memory     |    |
|  | (extract) |   write  |  (storage)  | read | (retrieve) |    |
|  +-----------+          +-------------+      +------------+    |
|       |                                            |           |
|       v                                            v           |
|  ChatHistoryProvider                          LLMProvider      |
|  (user implements)                            (vectorize)      |
|                                                                |
+----------------------------------------------------------------+
```

### Core Concepts

**Separation of Concerns**:
- `Extraction`: Runs independently, extracts memories from chat history and stores them
- `Memory`: Used only for retrieval, consumed by Agent

**User-Level Isolation**: All operations are isolated by `ExecutionContext.userId`.

**LangMem Pattern**: LLM proactively decides when to query memories via Tool, rather than automatically injecting on every request.

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

### Setting Up Memory

```java
import ai.core.memory.Memory;
import ai.core.memory.MemoryStore;
import ai.core.memory.Extraction;
import ai.core.memory.InMemoryStore;
import ai.core.memory.history.InMemoryChatHistoryProvider;

String userId = "user-123";

// 1. Create shared storage
MemoryStore memoryStore = new InMemoryStore();
// Note: Declare as InMemoryChatHistoryProvider to use addRecord methods
InMemoryChatHistoryProvider historyProvider = new InMemoryChatHistoryProvider();

// 2. Create Extraction (runs independently)
Extraction extraction = new Extraction(memoryStore, historyProvider, llmProvider);
// Or use Builder
Extraction extraction = Extraction.builder()
    .memoryStore(memoryStore)
    .historyProvider(historyProvider)
    .llmProvider(llmProvider)
    .model("gpt-4")  // Optional
    .build();

// 3. Create Memory (retrieval only)
Memory memory = Memory.builder()
    .llmProvider(llmProvider)
    .memoryStore(memoryStore)
    .defaultTopK(5)  // Optional
    .build();

// 4. Create Agent
Agent agent = Agent.builder()
    .name("personalized-assistant")
    .llmProvider(llmProvider)
    .unifiedMemory(memory)  // Auto-registers MemoryRecallTool
    .build();
```

### Memory Extraction

Users trigger extraction at appropriate times (e.g., at end of conversation):

```java
// Use InMemoryChatHistoryProvider to record chat history
InMemoryChatHistoryProvider historyProvider = new InMemoryChatHistoryProvider();
historyProvider.addRecord(userId, ChatRecord.user("I like dark mode", Instant.now()));
historyProvider.addRecord(userId, ChatRecord.assistant("Got it!", Instant.now()));

// Trigger extraction after session ends (synchronous execution)
extraction.run(userId);
```

**Extraction Flow:**

```
+-----------------------------------------------------------------------+
|                         Extraction Flow                                |
+-----------------------------------------------------------------------+
|                                                                       |
|  1. User triggers extraction                                          |
|     extraction.run(userId)                                            |
|          |                                                            |
|          v                                                            |
|  2. Load messages from historyProvider                                |
|     historyProvider.loadForExtraction(userId)                         |
|          |                                                            |
|          v                                                            |
|  3. Filter unextracted messages (tracked internally)                  |
|     messages.subList(lastExtracted + 1, size)                         |
|          |                                                            |
|          v                                                            |
|  4. LLM extracts memories                                             |
|     Input: "User: I'm John, a developer"                              |
|            "Assistant: Hi John!"                                      |
|                                                                       |
|     Output: [                                                         |
|       {content: "User name is John", importance: 0.9},                |
|       {content: "User is a developer", importance: 0.7}               |
|     ]                                                                 |
|          |                                                            |
|          v                                                            |
|  5. Generate embedding vectors                                        |
|     llmProvider.embeddings(contents)                                  |
|          |                                                            |
|          v                                                            |
|  6. Save to MemoryStore                                               |
|     memoryStore.saveAll(userId, records, embeddings)                  |
|          |                                                            |
|          v                                                            |
|  7. Update extraction state (tracked internally)                      |
|     extractedIndexMap.put(userId, lastIndex)                          |
|                                                                       |
+-----------------------------------------------------------------------+
```

### Memory Retrieval Principles

The memory retrieval mechanism is based on **vector similarity search**, which converts natural language queries into vector representations and finds the most relevant memories:

```
┌─────────────────────────────────────────────────────────────┐
│                  Vector Similarity Search Flow               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  User Query: "What does the user like?"                     │
│         │                                                   │
│         ▼                                                   │
│  1. Vectorize query                                         │
│     queryEmbedding = llmProvider.embeddings(query)          │
│         │                                                   │
│         ▼                                                   │
│  2. Similarity search in MemoryStore                        │
│     candidates = store.searchByVector(                      │
│         userId,                                             │
│         queryEmbedding,                                     │
│         topK                                                │
│     )                                                       │
│         │                                                   │
│         ▼                                                   │
│  3. Apply scoring factors                                   │
│     score = similarity                                      │
│           × importance                                      │
│           × decayFactor                                     │
│           × recencyBoost                                    │
│         │                                                   │
│         ▼                                                   │
│  4. Return top-K results                                    │
│     [MemoryRecord, MemoryRecord, ...]                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Core Retrieval Code**:
```java
public List<MemoryRecord> retrieve(String userId, String query, int topK) {
    // 1. Generate query embedding
    List<Double> queryEmbedding = llmProvider.embeddings(List.of(query)).getFirst();

    // 2. Vector similarity search
    List<MemoryRecord> candidates = memoryStore.searchByVector(userId, queryEmbedding, topK * 2);

    // 3. Re-rank by composite score
    return candidates.stream()
        .sorted((a, b) -> Double.compare(
            computeScore(b, queryEmbedding),
            computeScore(a, queryEmbedding)
        ))
        .limit(topK)
        .toList();
}
```

### LangMem Pattern

Core-AI adopts the **LangMem pattern** for memory integration, where the LLM proactively decides when to query memories via a tool call, rather than automatically injecting memories on every request:

```
┌─────────────────────────────────────────────────────────────┐
│                    LangMem Pattern Flow                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  User: "Do you remember what I like?"                       │
│         │                                                   │
│         ▼                                                   │
│  Agent receives query                                       │
│         │                                                   │
│         ▼                                                   │
│  LLM analyzes: "Need to recall user preferences"            │
│         │                                                   │
│         ▼                                                   │
│  LLM outputs: tool_call: search_memory_tool                 │
│               arguments: {"query": "user preferences"}      │
│         │                                                   │
│         ▼                                                   │
│  MemoryRecallTool executes                                  │
│  memory.retrieve(userId, "user preferences")                │
│         │                                                   │
│         ▼                                                   │
│  Returns: ["User likes dark mode", "User prefers Vim"]      │
│         │                                                   │
│         ▼                                                   │
│  LLM generates response with memory context                 │
│  "I remember you like dark mode and prefer using Vim!"      │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**Benefits of LangMem Pattern**:
- **On-demand retrieval**: Only queries memory when contextually relevant
- **Reduced noise**: Avoids injecting irrelevant memories
- **LLM control**: LLM decides what to remember based on conversation context
- **Efficiency**: Saves tokens by not auto-injecting on every request

### Memory Recall

When LLM needs to query user memories, it triggers recall via MemoryRecallTool:

```
User: "Do you remember what I like?"
     |
     v
Agent decides to query memory
     |
     v
LLM calls search_memory_tool(query="what does user like")
     |
     v
MemoryRecallTool.execute(args, context)
     |
     v
userId = context.getUserId()  // Get from ExecutionContext
memory.retrieve(userId, query)
     |
     v
Returns: [User Memory]
         - User likes dark mode
     |
     v
LLM generates response: "I remember you like dark mode."
```

### userId Passing Mechanism

`userId` is passed through `ExecutionContext` throughout the execution chain:

```
agent.run(query, context)
       |
       v
Agent stores executionContext
       |
       v
ToolExecutor.execute(functionCall, context)
       |
       v
MemoryRecallTool.execute(args, context)
       |
       v
String userId = context.getUserId()
       |
       v
memory.retrieve(userId, query)
```

### Storage Interfaces

**MemoryStore** - Memory storage interface:

```java
public interface MemoryStore {
    // Save memories
    void save(String userId, MemoryRecord record);
    void save(String userId, MemoryRecord record, List<Double> embedding);
    void saveAll(String userId, List<MemoryRecord> records, List<List<Double>> embeddings);

    // Query memories
    Optional<MemoryRecord> findById(String userId, String id);
    List<MemoryRecord> findAll(String userId);
    List<MemoryRecord> searchByVector(String userId, List<Double> queryEmbedding, int topK);
    List<MemoryRecord> searchByKeyword(String userId, String keyword, int topK);

    // Delete memories
    void delete(String userId, String id);
    void deleteAll(String userId);

    // Decay management
    void recordAccess(String userId, List<String> ids);
    void updateDecayFactor(String userId, String id, double decayFactor);
    List<MemoryRecord> findDecayed(String userId, double threshold);
    int deleteDecayed(String userId, double threshold);

    int count(String userId);
}
```

**ChatHistoryProvider** - Chat history provider interface (functional interface):

```java
@FunctionalInterface
public interface ChatHistoryProvider {
    List<ChatRecord> loadForExtraction(String userId);
}
```

The framework provides built-in `InMemoryChatHistoryProvider` with `addRecord` methods:

```java
public class InMemoryChatHistoryProvider implements ChatHistoryProvider {
    List<ChatRecord> loadForExtraction(String userId);
    void addRecord(String userId, ChatRecord record);
    void addRecords(String userId, List<ChatRecord> records);
    void clear(String userId);
}
```

### Custom Storage Implementation

```java
// Implement MemoryStore interface
public class MilvusMemoryStore implements MemoryStore {
    private final MilvusClient client;

    @Override
    public List<MemoryRecord> searchByVector(String userId, List<Double> embedding, int topK) {
        // Query with userId isolation
        return client.search("memory_collection",
            "user_id == '" + userId + "'", embedding, topK);
    }
    // ... other methods
}

// Implement ChatHistoryProvider interface (functional interface, only needs loadForExtraction method)
public class DatabaseHistoryProvider implements ChatHistoryProvider {
    private final MessageRepository repository;

    @Override
    public List<ChatRecord> loadForExtraction(String userId) {
        return repository.findByUserId(userId);
    }
}

// Or use Lambda expression
ChatHistoryProvider historyProvider = userId -> repository.findByUserId(userId);
```

## Agent Integration

### ExecutionContext

`ExecutionContext` is the context during Agent execution, containing:

```java
public final class ExecutionContext {
    private final String sessionId;      // Session ID
    private final String userId;         // User ID (for memory isolation)
    private final Map<String, Object> customVariables;  // Custom variables
}
```

### Using unifiedMemory Configuration

```java
import ai.core.memory.MemoryConfig;
import ai.core.agent.ExecutionContext;

// Recommended: Using Agent builder
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(memory)  // Auto-registers MemoryRecallTool
    .build();

// Pass ExecutionContext at runtime
ExecutionContext context = ExecutionContext.builder()
    .userId("user-123")
    .sessionId("session-456")
    .build();

agent.run("Hello", context);
```

```java
// Or with custom configuration
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(memory, MemoryConfig.builder()
        .maxRecallRecords(10)      // Return max 10 memories
        .autoRecall(true)          // Auto-register MemoryRecallTool
        .build())
    .build();
```

### Complete Example

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

        // Shared storage
        var memoryStore = new InMemoryStore();
        var historyProvider = new InMemoryChatHistoryProvider();

        // 1. Extraction - runs independently
        Extraction extraction = new Extraction(memoryStore, historyProvider, llmProvider);

        // 2. Memory - used by Agent
        Memory memory = Memory.builder()
            .llmProvider(llmProvider)
            .memoryStore(memoryStore)
            .build();

        // 3. Agent
        Agent agent = Agent.builder()
            .name("assistant")
            .systemPrompt("You are a personalized assistant, use memory tools to recall user preferences")
            .llmProvider(llmProvider)
            .unifiedMemory(memory)
            .build();

        // 4. Simulate conversation and record to historyProvider
        historyProvider.addRecord(userId, ChatRecord.user("I like Vim editor", Instant.now()));
        historyProvider.addRecord(userId, ChatRecord.assistant("Got it, recorded!", Instant.now()));

        // 5. Extract memories after session ends
        extraction.run(userId);

        // 6. In next session, Agent can retrieve memories
        ExecutionContext context = ExecutionContext.builder()
            .userId(userId)
            .build();
        agent.run("Do you remember what editor I like?", context);
    }
}
```

## Best Practices

### 1. Proper Use of ExecutionContext

```java
// Create context with userId
ExecutionContext context = ExecutionContext.builder()
    .userId("user-123")
    .sessionId("session-456")  // Optional
    .customVariable("key", value)  // Optional: custom variables
    .build();

// Pass context on each agent.run call
String response = agent.run("query content", context);
```

### 2. Production Storage Setup

```java
// Development: in-memory stores
MemoryStore devStore = new InMemoryStore();
ChatHistoryProvider devHistory = new InMemoryChatHistoryProvider();

// Production: persistent storage
MemoryStore prodStore = new MilvusMemoryStore(milvusClient);
ChatHistoryProvider prodHistory = new DatabaseHistoryProvider(repository);
// Or use Lambda
ChatHistoryProvider prodHistory = userId -> repository.findByUserId(userId);
```

### 3. Extraction Timing

```java
// Option 1: Extract at session end
public void onSessionEnd(String userId) {
    extraction.run(userId);
    historyProvider.clear(userId);  // Optional: clear history
}

// Option 2: Scheduled batch extraction
@Scheduled(cron = "0 0 * * * *")  // Every hour
public void batchExtraction() {
    for (String userId : activeUsers) {
        extraction.run(userId);
    }
}
```

### 4. Service Layer Encapsulation

```java
public class ChatService {
    private final Agent agent;
    private final Extraction extraction;
    private final InMemoryChatHistoryProvider historyProvider;

    public String chat(String userId, String message) {
        // Record user message
        historyProvider.addRecord(userId, ChatRecord.user(message, Instant.now()));

        // Create context and execute
        ExecutionContext context = ExecutionContext.builder()
            .userId(userId)
            .build();
        String response = agent.run(message, context);

        // Record assistant response
        historyProvider.addRecord(userId, ChatRecord.assistant(response, Instant.now()));

        return response;
    }

    public void endSession(String userId) {
        extraction.run(userId);
        historyProvider.clear(userId);
    }
}
```

## Summary

Key concepts covered in this tutorial:

1. **Memory System**: Persistent user memories with vector semantic search
   - `Extraction`: Independent extractor, extracts memories from chat history
   - `Memory`: Retriever, used by Agent

2. **ExecutionContext**: Execution context, passes userId and other info
   - User-level data isolation via `context.getUserId()`

3. **LangMem Pattern**: LLM proactively queries memories via Tool

4. **Dual Storage Design**: `MemoryStore` (memories) + `ChatHistoryProvider` (read from user's messages)

Next steps:
- Learn [Tool Calling](tutorial-tool-calling.md) to extend agent capabilities
- Explore [RAG Integration](tutorial-rag.md) for knowledge enhancement
- Build complex workflows with [Flow Orchestration](tutorial-flow.md)
