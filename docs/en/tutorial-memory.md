# Tutorial: Memory Systems

This tutorial covers Core-AI's memory systems for building agents that remember and learn from interactions.

## Table of Contents

1. [Overview](#overview)
2. [Short-term Memory](#short-term-memory)
3. [Long-term Memory](#long-term-memory)
4. [Unified Memory Lifecycle](#unified-memory-lifecycle)
5. [Best Practices](#best-practices)

## Overview

Core-AI provides a two-tier memory architecture:

```
┌─────────────────────────────────────────────────────────────┐
│                      Agent Memory                            │
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
│ Storage: In-memory          │ Storage: Vector DB / SQLite   │
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
    .enableMemory(true)  // Default: true
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
    2000,    // maxSummaryTokens
    0.33,    // triggerRatio (when to start summarizing)
    executor // custom executor for async operations
);

Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .shortTermMemory(memory)
    .build();
```

### How Summarization Works

```
┌──────────────────────────────────────────────────────────┐
│                   Conversation Flow                       │
├──────────────────────────────────────────────────────────┤
│  Turn 1: User asks question                              │
│  Turn 2: Assistant responds                              │
│  Turn 3: User follows up                                 │
│     ...                                                  │
│  Turn N: Token count > threshold                         │
│          ↓                                               │
│  [Async Summarization Triggered]                         │
│          ↓                                               │
│  Old messages → Summary                                  │
│  Recent messages kept                                    │
└──────────────────────────────────────────────────────────┘
```

### Sliding Window Memory

For large conversations, use sliding window to keep recent context:

```java
Agent agent = Agent.builder()
    .name("agent")
    .llmProvider(llmProvider)
    .slidingWindowTurns(10)  // Keep last 10 conversation turns
    .build();
```

### Disabling Short-term Memory

```java
Agent agent = Agent.builder()
    .name("stateless-agent")
    .llmProvider(llmProvider)
    .enableMemory(false)  // Disable memory
    .build();
```

## Long-term Memory

Long-term memory persists user information across sessions using vector embeddings for semantic search.

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  Long-term Memory                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌────────────┐  │
│  │   Recall    │    │  Extraction │    │   Store    │  │
│  │  (Search)   │    │  (Save)     │    │  (Vector)  │  │
│  └──────┬──────┘    └──────┬──────┘    └─────┬──────┘  │
│         │                  │                  │         │
│         └──────────────────┼──────────────────┘         │
│                            │                            │
│                    ┌───────┴───────┐                    │
│                    │   Namespace   │                    │
│                    │ (User/Org/...)│                    │
│                    └───────────────┘                    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### Memory Types

| Type | Description | Example |
|------|-------------|---------|
| `FACT` | Factual information about the user | "Works as software engineer" |
| `PREFERENCE` | User preferences and likes | "Prefers concise responses" |
| `GOAL` | User goals and objectives | "Learning machine learning" |
| `EPISODE` | Notable past interactions | "Asked about Python yesterday" |

### Setting Up Long-term Memory

```java
import ai.core.memory.longterm.LongTermMemory;
import ai.core.memory.longterm.LongTermMemoryConfig;
import ai.core.memory.longterm.DefaultLongTermMemoryStore;

// Create store (in-memory for development, SQLite for production)
var store = DefaultLongTermMemoryStore.inMemory();
// Or: var store = DefaultLongTermMemoryStore.withSqlite(dataSource);

// Build long-term memory
LongTermMemory longTermMemory = LongTermMemory.builder()
    .llmProvider(llmProvider)
    .store(store)
    .config(LongTermMemoryConfig.builder()
        .embeddingDimension(1536)      // Depends on embedding model
        .asyncExtraction(true)          // Async memory extraction
        .extractionBatchSize(5)         // Messages per extraction batch
        .build())
    .build();
```

### Namespace Organization

Namespaces organize memories by scope:

```java
import ai.core.memory.longterm.Namespace;

// User-scoped namespace (most common)
Namespace userNs = Namespace.forUser("user-123");

// Organization-scoped (shared within org)
Namespace orgNs = Namespace.of("acme-corp", null);

// Session-scoped (temporary)
Namespace sessionNs = Namespace.forSession("session-456");
```

### Manual Memory Operations

```java
// Start a session
longTermMemory.startSessionForUser("user-123", "session-456");

// Recall relevant memories
List<MemoryRecord> memories = longTermMemory.recall(
    "programming preferences",  // query
    5                           // top K results
);

// Recall with type filter
List<MemoryRecord> prefs = longTermMemory.recall(
    "preferences",
    5,
    MemoryType.PREFERENCE, MemoryType.FACT
);

// Format memories as context
String context = longTermMemory.formatAsContext(memories);

// End session
longTermMemory.endSession();
```

## Unified Memory Lifecycle

The `UnifiedMemoryLifecycle` automatically injects long-term memories before LLM calls.

### How It Works

```
┌───────────────────────────────────────────────────────────┐
│                  Agent Execution Flow                      │
├───────────────────────────────────────────────────────────┤
│                                                           │
│   User Query                                              │
│       │                                                   │
│       ▼                                                   │
│   ┌─────────────────────────────────────┐                │
│   │     beforeAgentRun()                │                │
│   │     • Initialize session            │                │
│   │     • Set namespace from userId     │                │
│   └─────────────────────────────────────┘                │
│       │                                                   │
│       ▼                                                   │
│   ┌─────────────────────────────────────┐                │
│   │     beforeModel()                   │                │
│   │     • Extract user query            │                │
│   │     • Recall relevant memories      │                │
│   │     • Inject as Tool Call messages  │                │
│   └─────────────────────────────────────┘                │
│       │                                                   │
│       ▼                                                   │
│   [LLM Call with Memory Context]                         │
│       │                                                   │
│       ▼                                                   │
│   ┌─────────────────────────────────────┐                │
│   │     afterAgentRun()                 │                │
│   │     • End session                   │                │
│   │     • Reset state                   │                │
│   └─────────────────────────────────────┘                │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

### Integration with Agent

```java
import ai.core.memory.UnifiedMemoryConfig;
import ai.core.memory.UnifiedMemoryLifecycle;

// Method 1: Using Agent builder (recommended)
Agent agent = Agent.builder()
    .name("personalized-agent")
    .llmProvider(llmProvider)
    .unifiedMemory(longTermMemory, UnifiedMemoryConfig.builder()
        .maxRecallRecords(5)       // Max memories to inject
        .build())
    .build();

// Method 2: Manual lifecycle setup
UnifiedMemoryLifecycle lifecycle = new UnifiedMemoryLifecycle(
    longTermMemory,
    5  // maxRecallRecords
);
agent.addLifecycle(lifecycle);
```

### Configuration Options

```java
UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
    .maxRecallRecords(5)          // Max memories to recall (1-20)
    .memoryBudgetRatio(0.2)       // Token budget for memories (5%-50%)
    .build();
```

### Memory Injection Format

Memories are injected as Tool Call messages for consistency:

```
Messages sent to LLM:
┌────────────────────────────────────────────────────┐
│ [SYSTEM] You are a helpful assistant...            │
├────────────────────────────────────────────────────┤
│ [ASSISTANT] tool_call: recall_long_term_memory     │
├────────────────────────────────────────────────────┤
│ [TOOL] [User Memory]                               │
│ - User prefers concise responses                   │
│ - User is learning Python                          │
│ - User works in data science                       │
├────────────────────────────────────────────────────┤
│ [USER] Help me with pandas dataframes              │
└────────────────────────────────────────────────────┘
```

### Execution Context

To enable memory lookup, provide userId in ExecutionContext:

```java
import ai.core.agent.ExecutionContext;

ExecutionContext context = ExecutionContext.builder()
    .userId("user-123")         // Required for memory lookup
    .sessionId("session-456")   // Optional
    .build();

AgentOutput output = agent.execute("Help me with code", context);
```

## Best Practices

### 1. Choose the Right Memory Type

```java
// Stateless operations: disable memory
Agent statelessAgent = Agent.builder()
    .enableMemory(false)
    .build();

// Single session: short-term only (default)
Agent sessionAgent = Agent.builder()
    .enableMemory(true)
    .build();

// Personalized experience: both memories
Agent personalizedAgent = Agent.builder()
    .enableMemory(true)
    .unifiedMemory(longTermMemory, config)
    .build();
```

### 2. Manage Token Budget

```java
// For models with limited context
UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
    .maxRecallRecords(3)          // Fewer memories
    .memoryBudgetRatio(0.1)       // 10% of context
    .build();

// For models with large context
UnifiedMemoryConfig config = UnifiedMemoryConfig.builder()
    .maxRecallRecords(10)
    .memoryBudgetRatio(0.3)
    .build();
```

### 3. Production Storage Setup

```java
// Development: in-memory
var devStore = DefaultLongTermMemoryStore.inMemory();

// Production: SQLite with persistence
var prodStore = DefaultLongTermMemoryStore.withSqlite(dataSource);

// High-scale: Custom vector store (Milvus, etc.)
var scaleStore = new CustomVectorStore(milvusClient);
```

### 4. Handle Missing Context Gracefully

```java
// Lifecycle handles missing context gracefully
// - No userId: skips memory injection
// - No memories found: continues without injection
// - Errors: logged but don't fail the request

AgentOutput output = agent.execute("query");  // Works without context
AgentOutput output = agent.execute("query", context);  // With context
```

## Summary

Key concepts covered:

1. **Short-term Memory**: Session-based conversation history with auto-summarization
2. **Long-term Memory**: Persistent user memories with vector search
3. **Unified Memory Lifecycle**: Automatic memory injection into LLM calls
4. **Memory Types**: FACT, PREFERENCE, GOAL, EPISODE
5. **Namespaces**: User, Organization, Session scoping

Next steps:
- Learn [Tool Calling](tutorial-tool-calling.md) to extend agent capabilities
- Explore [RAG Integration](tutorial-rag.md) for knowledge enhancement
- Build [Multi-Agent Systems](tutorial-multi-agent.md) for complex applications
