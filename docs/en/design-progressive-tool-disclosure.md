# Progressive Tool Disclosure

When an Agent has many tools, sending all tool schemas on every LLM call is wasteful.
This document describes a lightweight mechanism that hides tools initially and lets the LLM activate them on demand.

## Problem

Every tool's full JSON Schema is included in each LLM request:

```
50 tools x ~300 tokens/tool = ~15,000 tokens per turn
```

This causes context bloat, higher cost, slower responses, and makes it harder for the LLM to pick the right tool.

## Design

### Two types of tools

Tools are split by a single boolean flag `discoverable` on `ToolCall`:

- **Core tools** (`discoverable = false`, default) — always visible to LLM, sent on every turn
- **Discoverable tools** (`discoverable = true`) — hidden initially, activated on demand via `activate_tools`

### Adaptive mode selection

The system automatically picks a strategy based on how many discoverable tools exist:

```
discoverable tools <= 30            discoverable tools > 30

  Catalog Mode                        Search Mode

  description lists all tools         description says "search by keyword"
  LLM picks names directly            LLM searches first, then activates
  Cost: 1 extra turn                  Cost: 2 extra turns
  ~20 tokens/tool in catalog          0 tokens until search
```

### Catalog mode (few tools)

The `activate_tools` tool's description contains a compact catalog:

```
Activate additional tools to make them available for use.
Call this with the names of tools you need.

Tool Catalog:
- postgres_query: Execute SQL queries against PostgreSQL database
- mysql_connect: Connect to MySQL database and run queries
- redis_client: Redis key-value store operations
```

~20 tokens per tool (name + truncated description) vs ~300 tokens per tool for full schemas.

The catalog is **dynamic** — already-activated tools are removed from the catalog each turn, avoiding duplication.

### Search mode (many tools)

The `activate_tools` description only tells the LLM to search:

```
Search and activate additional tools. A large number of tools are available but not shown.

Usage:
1. Search: activate_tools(query="database connection") — find tools by keyword
2. Activate: activate_tools(tool_names=["tool_a", "tool_b"]) — make tools available
```

Search matches keywords against tool names and descriptions (simple string contains, no external dependencies).

## Runtime Flow

### Catalog mode

```
Turn 1: LLM sees [read_file, write_file, activate_tools]
        activate_tools description lists: postgres_query, mysql_connect, redis_client
        LLM calls: activate_tools(tool_names=["postgres_query"])
        -> postgres_query.llmVisible = true

Turn 2: LLM sees [read_file, write_file, activate_tools, postgres_query]
        activate_tools catalog now only lists: mysql_connect, redis_client
        LLM calls: postgres_query(query="SELECT * FROM users")
```

### Search mode

```
Turn 1: LLM calls: activate_tools(query="order management")
        -> returns: "Found 5 tools: order_service_list, order_service_create, ..."

Turn 2: LLM calls: activate_tools(tool_names=["order_service_list"])
        -> order_service_list.llmVisible = true

Turn 3: LLM calls: order_service_list(...)
```

## How it works (key insight)

No changes to the Agent core loop. `chatTurns()` already re-evaluates tool visibility on every iteration:

```java
// Agent.java — existing code, unchanged
do {
    var turnMsgList = turn(getMessages(),
        AgentHelper.toReqTools(toolCalls),   // re-filters by isLlmVisible() each turn
        constructionAssistantMsg);
    ...
} while (AgentHelper.lastIsToolMsg(getMessages()) && ...);
```

When `activate_tools` sets `tool.setLlmVisible(true)`, the next loop iteration picks it up automatically.

## Implementation

### Changed files

| File | Change |
|------|--------|
| `ToolCall.java` | Added `Boolean discoverable` field, getter, setter, builder support |
| `ToolCall.java` | `toTool()` uses `getDescription()` instead of `description` field directly (enables dynamic description) |
| `ToolActivationTool.java` | New file — the `activate_tools` meta-tool |
| `AgentBuilder.java` | In `copyValue()`: detects discoverable tools, hides them, injects `ToolActivationTool` |

### ToolCall.java

Added field alongside existing `llmVisible`:

```java
Boolean discoverable;

public boolean isDiscoverable() {
    return discoverable != null && discoverable;
}
```

Builder:
```java
.discoverable(true)  // mark a tool as discoverable
```

### ToolActivationTool.java

Core class, ~170 lines. Key design points:

**Dynamic description** — overrides `getDescription()` to regenerate catalog each turn, excluding already-activated tools:

```java
@Override
public String getDescription() {
    if (searchMode) {
        return buildSearchModeDescription();
    }
    var inactiveTools = allToolCalls.stream()
            .filter(t -> t.isDiscoverable() && !t.isLlmVisible())
            .toList();
    return buildCatalogDescription(inactiveTools);
}
```

**Dual operation** — `query` parameter triggers search, `tool_names` triggers activation:

```java
@Override
public ToolCallResult execute(String arguments) {
    if (query != null)     return executeSearch(query);
    if (toolNames != null) return executeActivate(toolNames);
}
```

**Search** — simple keyword matching against name and description, no external dependencies:

```java
private boolean matchesAny(ToolCall tool, String[] keywords) {
    var name = tool.getName().toLowerCase();
    var desc = tool.getDescription().toLowerCase();
    for (var kw : keywords) {
        if (name.contains(kw) || desc.contains(kw)) return true;
    }
    return false;
}
```

### AgentBuilder.java

Added after subAgents wiring in `copyValue()`:

```java
var discoverableTools = agent.toolCalls.stream()
        .filter(ToolCall::isDiscoverable)
        .toList();
if (!discoverableTools.isEmpty()) {
    for (var tool : discoverableTools) {
        tool.setLlmVisible(false);
    }
    agent.toolCalls.add(ToolActivationTool.builder()
            .allToolCalls(agent.toolCalls).build());
}
```

## Usage

### Marking tools as discoverable

```java
// core tool — always visible (default)
var readFile = ReadFileTool.builder().build();

// discoverable tool — hidden initially, activated on demand
var postgresQuery = PostgresQueryTool.builder()
        .discoverable(true)
        .build();

var agent = Agent.builder()
        .toolCalls(List.of(readFile, postgresQuery))
        .build();

// AgentBuilder automatically:
// 1. Hides postgresQuery (llmVisible=false)
// 2. Creates and injects activate_tools meta-tool
// 3. Selects catalog or search mode based on discoverable tool count
```

### With MCP tools

```java
var agent = Agent.builder()
        .toolCalls(List.of(coreTools))
        .mcpServers(List.of("company-api-server"))
        .build();

// MCP tools can be marked discoverable via McpToolCall configuration
```

## Design Decisions

### Why permanent activation (not per-turn)?

Tools are usually needed for multiple turns. Per-turn activation would force the LLM to call `activate_tools` before every use, doubling the turn count and negating the token savings.

### Why no deactivation?

In practice, an agent session focuses on one task domain. Activated tools rarely exceed 5-10 in a session. If all tools end up activated, they probably should have been core tools.

### Why keyword search instead of semantic search?

Semantic search requires embedding models — an external dependency that adds latency and complexity for marginal benefit. Tool names and descriptions are already structured text; simple keyword matching is sufficient and has zero dependencies.

### Threshold: 30 tools

The `CATALOG_MODE_THRESHOLD = 30` balances catalog readability (~600 tokens) against search overhead (2 turns instead of 1). This can be tuned.

## Integration with existing features

### ToolCallPruning

Works naturally. `activate_tools` call/result pairs get pruned like any other tool call once digested. Activated tools remain visible because `llmVisible` was mutated in-memory.

### Compression

No interaction. Compression operates on message content; tool visibility is managed separately.

### SubAgents

SubAgents added via `subAgents()` can also be marked `discoverable`. The wiring in `copyValue()` runs after subAgents are added to `toolCalls`, so they are included in the scan.
