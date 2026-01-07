# Core-AI Architecture Deep Dive

This document provides an in-depth analysis of Core-AI framework's core architecture and execution mechanisms, helping developers understand the framework's design principles.

## Table of Contents

1. [Overall Architecture](#overall-architecture)
2. [Agent Execution Engine](#agent-execution-engine)
3. [Lifecycle System](#lifecycle-system)
4. [Tool Execution Mechanism](#tool-execution-mechanism)
5. [Message Processing Flow](#message-processing-flow)
6. [AgentGroup Coordination](#agentgroup-coordination)
7. [Flow Execution Engine](#flow-execution-engine)
8. [Tracing and Telemetry](#tracing-and-telemetry)

## Overall Architecture

### Layered Architecture Design

Core-AI adopts a clear layered architecture with well-defined responsibilities for each layer:

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                         │
│          User code, business logic, service interfaces       │
├─────────────────────────────────────────────────────────────┤
│                    Orchestration Layer                       │
│              Flow │ AgentGroup │ Planning                    │
│           Node traversal, multi-Agent coordination           │
├─────────────────────────────────────────────────────────────┤
│                     Agent Layer                              │
│          Agent │ Lifecycle │ Memory │ Reflection             │
│        Conversation management, context memory               │
├─────────────────────────────────────────────────────────────┤
│                    Capability Layer                          │
│         Tools │ RAG │ Compression │ MCP                      │
│       Tool calling, knowledge retrieval, compression         │
├─────────────────────────────────────────────────────────────┤
│                   Provider Layer                             │
│       LLMProvider │ Embedding │ Reranker │ VectorStore       │
│            Model calls, vectorization, reranking             │
└─────────────────────────────────────────────────────────────┘
```

### Core Design Principles

1. **Builder Pattern**: All core components use Builder pattern for fluent configuration
2. **Lifecycle Hooks**: Extensible execution pipeline through Lifecycle mechanism
3. **State Machine**: Clear state transitions for managing execution process
4. **Dependency Injection**: Support for DI containers for testing and extensibility

## Agent Execution Engine

### Execution Flow Details

Agent execution consists of **outer wrapper** and **core execution** two layers:

```
┌─────────────────────────────────────────────────────────────────┐
│                      Agent.run(query, context)                   │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │               Node.aroundExecute()                       │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │  beforeAgentRun() - Can modify query             │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  │                         │                               │    │
│  │                         ▼                               │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │           Agent.execute(query)                   │    │    │
│  │  │  ┌───────────────────────────────────────────┐  │    │    │
│  │  │  │         doExecute(query, variables)        │  │    │    │
│  │  │  │                    │                       │  │    │    │
│  │  │  │                    ▼                       │  │    │    │
│  │  │  │         commandOrLoops(query)              │  │    │    │
│  │  │  │         ├─ SlashCommand → chatCommand()    │  │    │    │
│  │  │  │         └─ Normal → chatLoops()            │  │    │    │
│  │  │  │                    │                       │  │    │    │
│  │  │  │                    ▼                       │  │    │    │
│  │  │  │         chatTurns() - Core dialogue loop   │  │    │    │
│  │  │  │         ┌─────────────────────────┐        │  │    │    │
│  │  │  │         │  while (hasToolCall &&  │        │  │    │    │
│  │  │  │         │        turn < maxTurn)  │        │  │    │    │
│  │  │  │         │    ├─ beforeModel()     │        │  │    │    │
│  │  │  │         │    ├─ LLM call          │        │  │    │    │
│  │  │  │         │    ├─ afterModel()      │        │  │    │    │
│  │  │  │         │    ├─ Tool execution    │        │  │    │    │
│  │  │  │         │    └─ Accumulate output │        │  │    │    │
│  │  │  │         └─────────────────────────┘        │  │    │    │
│  │  │  └───────────────────────────────────────────┘  │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  │                         │                               │    │
│  │                         ▼                               │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │  afterAgentRun() - Can modify result             │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### State Transition Mechanism

Agent uses a state machine to manage execution states:

```
                    ┌─────────┐
                    │  INITED │ (Initial state)
                    └────┬────┘
                         │ run() called
                         ▼
                    ┌─────────┐
            ┌───────│ RUNNING │───────┐
            │       └────┬────┘       │
            │            │            │
     Tool needs     Normal       Exception
       auth        completion    occurred
            │            │            │
            ▼            ▼            ▼
┌───────────────────┐ ┌─────────┐ ┌────────┐
│WAITING_FOR_USER   │ │COMPLETED│ │ FAILED │
│     _INPUT        │ └─────────┘ └────────┘
└─────────┬─────────┘
          │ User confirms "yes"
          │
          ▼
     ┌─────────┐
     │ RUNNING │ (Continue execution)
     └─────────┘
```

### Core Code Analysis

**Multi-turn Dialogue Loop** (`chatTurns` method):

```java
protected void chatTurns(String query, Map<String, Object> variables,
        BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    // 1. Build user message
    buildUserQueryToMessage(query, variables);

    var currentIteCount = 0;
    var agentOut = new StringBuilder();

    // 2. Dialogue loop
    do {
        // Execute single turn
        var turnMsgList = turn(getMessages(), toReqTools(toolCalls), constructionAssistantMsg);

        // Add messages to history
        turnMsgList.forEach(this::addMessage);

        // Accumulate assistant output
        agentOut.append(turnMsgList.stream()
            .filter(m -> RoleType.ASSISTANT.equals(m.role))
            .map(m -> m.content)
            .collect(Collectors.joining("")));

        currentIteCount++;
    } while (lastIsToolMsg() && currentIteCount < maxTurnNumber);
    // Loop condition: last message is tool message AND not exceeded max turns

    setOutput(agentOut.toString());
}
```

**Key Design Points**:
- Loop termination: Last message is not tool message, OR reached max turns
- Tool calls executed via `parallelStream` for efficiency
- Output accumulation: Only collects ASSISTANT role message content

## Lifecycle System

### Hook Point Design

Core-AI provides 8 lifecycle hook points covering the complete Agent execution lifecycle:

```
┌─────────────────────────────────────────────────────────────┐
│                     Lifecycle Hooks                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Build Phase:                                               │
│    ├─ beforeAgentBuild(AgentBuilder)                        │
│    └─ afterAgentBuild(Agent)                                │
│                                                             │
│  Run Phase:                                                 │
│    ├─ beforeAgentRun(query, context)     ← Can modify query │
│    ├─ afterAgentRun(query, result, ctx)  ← Can modify result│
│    └─ afterAgentFailed(query, ctx, ex)   ← Exception handler│
│                                                             │
│  LLM Call Phase:                                            │
│    ├─ beforeModel(request, context)      ← Modify request   │
│    └─ afterModel(request, response, ctx) ← Process response │
│                                                             │
│  Tool Execution Phase:                                      │
│    ├─ beforeTool(functionCall, context)                     │
│    └─ afterTool(functionCall, context, result)              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### AbstractLifecycle Class

```java
public abstract class AbstractLifecycle {
    // Build phase hooks
    public void beforeAgentBuild(AgentBuilder agentBuilder) { }
    public void afterAgentBuild(Agent agent) { }

    // Run phase hooks - Use AtomicReference for modifiable parameters
    public void beforeAgentRun(AtomicReference<String> query,
                               ExecutionContext executionContext) { }
    public void afterAgentRun(String query, AtomicReference<String> result,
                              ExecutionContext executionContext) { }
    public void afterAgentFailed(String query, ExecutionContext executionContext,
                                 Exception exception) { }

    // LLM call phase hooks
    public void beforeModel(CompletionRequest completionRequest,
                           ExecutionContext executionContext) { }
    public void afterModel(CompletionRequest completionRequest,
                          CompletionResponse completionResponse,
                          ExecutionContext executionContext) { }

    // Tool execution phase hooks
    public void beforeTool(FunctionCall functionCall,
                          ExecutionContext executionContext) { }
    public void afterTool(FunctionCall functionCall,
                         ExecutionContext executionContext,
                         ToolCallResult toolResult) { }
}
```

### Built-in Lifecycle Implementations

| Lifecycle Class | Purpose | Trigger Point |
|----------------|---------|---------------|
| `CompressionLifecycle` | Context compression | `beforeModel` - Check and compress messages |
| `MemoryLifecycle` | Memory recall tool registration | Build time - Register `MemoryRecallTool` |
| `RAGLifecycle` | RAG retrieval enhancement | `beforeModel` - Inject retrieval results |

### Custom Lifecycle Example

```java
public class AuditLifecycle extends AbstractLifecycle {
    private final AuditLogger auditLogger;

    @Override
    public void beforeAgentRun(AtomicReference<String> query,
                               ExecutionContext context) {
        // Log input audit
        auditLogger.logInput(context.getUserId(), query.get());
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result,
                              ExecutionContext context) {
        // Log output audit
        auditLogger.logOutput(context.getUserId(), result.get());

        // Sensitive word filtering (modify result)
        String filtered = sensitiveWordFilter(result.get());
        result.set(filtered);
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        // Log LLM call
        auditLogger.logLLMRequest(request.getModel(), request.getMessages().size());
    }
}
```

## Tool Execution Mechanism

### JSON Schema Auto-Generation

Tool parameters are defined via `ToolCallParameter`, and the framework auto-generates OpenAI-compliant JSON Schema:

```
┌─────────────────────────────────────────────────────────────┐
│              Tool Definition → JSON Schema Generation        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ToolCall                                                   │
│    ├─ name: "get_weather"                                   │
│    ├─ description: "Get weather information"                │
│    └─ parameters: List<ToolCallParameter>                   │
│         ├─ city (String, required)                          │
│         └─ unit (String, optional, enum: [celsius, ...])    │
│                        │                                    │
│                        ▼ toTool()                           │
│                                                             │
│  Tool                                                       │
│    ├─ type: "function"                                      │
│    └─ function:                                             │
│         ├─ name: "get_weather"                              │
│         ├─ description: "Get weather information"           │
│         └─ parameters: JsonSchema                           │
│              {                                              │
│                "type": "object",                            │
│                "properties": {                              │
│                  "city": {                                  │
│                    "type": "string",                        │
│                    "description": "City name"               │
│                  },                                         │
│                  "unit": {                                  │
│                    "type": "string",                        │
│                    "enum": ["celsius", "fahrenheit"]        │
│                  }                                          │
│                },                                           │
│                "required": ["city"]                         │
│              }                                              │
└─────────────────────────────────────────────────────────────┘
```

### Type Mapping Table

| Java Type | JSON Schema Type |
|-----------|------------------|
| `String` | `string` |
| `Integer`, `Long` | `integer` |
| `Double`, `Float` | `number` |
| `Boolean` | `boolean` |
| `List<T>` | `array` with items |
| Custom Object | `object` with properties |
| `Enum` | `string` with enum values |

### Schema Generation Core Logic

```java
public class JsonSchemaUtil {
    public static JsonSchema toJsonSchema(List<ToolCallParameter> parameters) {
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;

        // Extract required fields
        schema.required = parameters.stream()
            .filter(v -> v.isRequired() != null && v.isRequired())
            .map(ToolCallParameter::getName)
            .toList();

        // Generate property definitions
        schema.properties = parameters.stream()
            .filter(v -> v.getName() != null)
            .collect(Collectors.toMap(
                ToolCallParameter::getName,
                JsonSchemaUtil::toSchemaProperty
            ));

        return schema;
    }

    private static JsonSchema toSchemaProperty(ToolCallParameter p) {
        var property = new JsonSchema();
        property.description = p.getDescription();
        property.type = buildJsonSchemaType(p.getClassType());
        property.enums = p.getEnums();

        // Recursively handle nested objects
        if (property.type == PropertyType.OBJECT && isCustomObjectType(p.getClassType())) {
            var nestedSchema = toJsonSchema(p.getClassType());
            property.properties = nestedSchema.properties;
            property.required = nestedSchema.required;
        }

        return property;
    }
}
```

### Tool Execution Flow

```
┌─────────────────────────────────────────────────────────────┐
│                    Tool Execution Flow                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  LLM returns tool_calls                                     │
│         │                                                   │
│         ▼                                                   │
│  Agent.handleFunc(funcMsg)                                  │
│         │                                                   │
│         ▼ parallelStream                                    │
│  ┌─────────────────────────────────────────┐               │
│  │  ToolExecutor.execute(functionCall)     │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  beforeTool() lifecycle hook            │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  Tool lookup: Match ToolCall by name    │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  Auth check: needAuth && !authenticated?│               │
│  │    └─ Yes: return WAITING_FOR_USER_INPUT│               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  tool.execute(arguments, context)       │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  afterTool() lifecycle hook             │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  Return ToolCallResult                  │               │
│  └─────────────────────────────────────────┘               │
│         │                                                   │
│         ▼                                                   │
│  Build TOOL message, continue dialogue loop                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Tool Executor Core Code

```java
public class ToolExecutor {
    public ToolCallResult execute(FunctionCall functionCall, ExecutionContext context) {
        // Pre-hook
        lifecycles.forEach(lc -> lc.beforeTool(functionCall, context));

        // Find tool
        var tool = findToolByName(functionCall.function.name);
        if (tool == null) {
            return ToolCallResult.failed("tool not found: " + functionCall.function.name);
        }

        try {
            // Auth check
            if (Boolean.TRUE.equals(tool.isNeedAuth()) && !authenticated) {
                statusUpdater.accept(NodeStatus.WAITING_FOR_USER_INPUT);
                return ToolCallResult.failed("This tool requires user authentication...");
            }

            // Execute tool (with tracing support)
            ToolCallResult result;
            if (tracer != null) {
                result = tracer.traceToolCall(
                    functionCall.function.name,
                    functionCall.function.arguments,
                    () -> tool.execute(functionCall.function.arguments, context)
                );
            } else {
                result = tool.execute(functionCall.function.arguments, context);
            }

            // Post-hook
            lifecycles.forEach(lc -> lc.afterTool(functionCall, context, result));

            return result;
        } catch (Exception e) {
            return ToolCallResult.failed("tool call failed: " + e.getMessage(), e);
        }
    }
}
```

## Message Processing Flow

### Message Types and Structure

```
┌─────────────────────────────────────────────────────────────┐
│                      Message Types                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Message {                                                  │
│    role: RoleType          // SYSTEM|USER|ASSISTANT|TOOL    │
│    content: String         // Message content               │
│    name: String            // Tool name (TOOL message)      │
│    toolCallId: String      // Tool call ID (TOOL message)   │
│    toolCalls: List<FC>     // Tool calls (ASSISTANT msg)    │
│  }                                                          │
│                                                             │
│  Message Flow Example:                                      │
│                                                             │
│  [SYSTEM]     You are an assistant...                       │
│       │                                                     │
│       ▼                                                     │
│  [USER]       What's the weather in Beijing?                │
│       │                                                     │
│       ▼ LLM inference                                       │
│  [ASSISTANT]  toolCalls: [{get_weather, {city: "Beijing"}}] │
│       │                                                     │
│       ▼ Tool execution                                      │
│  [TOOL]       name: get_weather                             │
│               content: 25C, sunny                           │
│       │                                                     │
│       ▼ LLM inference                                       │
│  [ASSISTANT]  The weather in Beijing is sunny, 25C.         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Message Building Flow

```java
// User message building
protected void buildUserQueryToMessage(String query, Map<String, Object> variables) {
    // 1. Template rendering (Mustache)
    String renderedQuery = renderTemplate(query, variables);

    // 2. Check if promptTemplate prefix needed
    if (promptTemplate != null) {
        renderedQuery = renderTemplate(promptTemplate, variables) + renderedQuery;
    }

    // 3. Create USER message
    addMessage(Message.of(RoleType.USER, renderedQuery));
}

// Tool call result message building
public List<Message> handleFunc(Message funcMsg) {
    return funcMsg.toolCalls.parallelStream().map(tool -> {
        var result = getToolExecutor().execute(tool, getExecutionContext());

        // TOOL message must include toolCallId
        return Message.of(
            RoleType.TOOL,
            result.toResultForLLM(),
            tool.function.name,    // name
            tool.id,               // toolCallId
            null, null
        );
    }).flatMap(List::stream).toList();
}
```

## AgentGroup Coordination

### Multi-Agent Coordination Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentGroup Architecture                   │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    AgentGroup                        │    │
│  │  ┌───────────┐  ┌───────────┐  ┌───────────┐        │    │
│  │  │  Agent A  │  │  Agent B  │  │  Agent C  │        │    │
│  │  │ (Analyst) │  │ (Planner) │  │ (Executor)│        │    │
│  │  └───────────┘  └───────────┘  └───────────┘        │    │
│  │         │              │              │              │    │
│  │         └──────────────┼──────────────┘              │    │
│  │                        │                             │    │
│  │                        ▼                             │    │
│  │  ┌─────────────────────────────────────────────┐    │    │
│  │  │               Handoff Strategy               │    │    │
│  │  │  ┌─────────┐ ┌─────────┐ ┌───────────────┐ │    │    │
│  │  │  │ Direct  │ │  Auto   │ │HybridAutoDirect│ │    │    │
│  │  │  │ Handoff │ │ Handoff │ │   Handoff     │ │    │    │
│  │  │  └─────────┘ └─────────┘ └───────────────┘ │    │    │
│  │  └─────────────────────────────────────────────┘    │    │
│  │                        │                             │    │
│  │                        ▼                             │    │
│  │  ┌─────────────────────────────────────────────┐    │    │
│  │  │               Planning                       │    │    │
│  │  │  • nextAgentName: Next Agent to execute     │    │    │
│  │  │  • nextQuery: Query for next Agent          │    │    │
│  │  │  • nextAction: Next action                  │    │    │
│  │  └─────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### AgentGroup Execution Flow

```
AgentGroup.run(query)
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│  1. setupAgentGroupSystemVariables()                         │
│     Set system variables: agents info, maxRound, termWord   │
│                                                             │
│  2. startRunning()                                          │
│     Status → RUNNING, round = 1                             │
│                                                             │
│  3. while (notTerminated()) {                               │
│     │                                                       │
│     │  // Token overflow handling                           │
│     │  if (currentTokenUsageOutOfMax()) {                   │
│     │      currentQuery = handleToShortQuery()              │
│     │  }                                                    │
│     │                                                       │
│     │  // Execute Handoff strategy                          │
│     │  handoff.handoff(this, planning, variables)           │
│     │       │                                               │
│     │       ├─ DirectHandoff: Sequential next Agent         │
│     │       ├─ AutoHandoff: Moderator Agent decides         │
│     │       └─ HybridHandoff: Mixed strategy                │
│     │                                                       │
│     │  // Check if finished                                 │
│     │  if (finished()) return output                        │
│     │                                                       │
│     │  // Execute selected Agent                            │
│     │  currentAgent = getAgentByName(planning.nextAgentName)│
│     │  output = currentAgent.run(planning.nextQuery)        │
│     │                                                       │
│     │  // Check waiting status                              │
│     │  if (waitingForUserInput()) return output             │
│     │                                                       │
│     │  // Round complete, reset state                       │
│     │  roundCompleted()                                     │
│     │  round++                                              │
│     }                                                       │
│                                                             │
│  4. return "Run out of round..."                            │
└─────────────────────────────────────────────────────────────┘
```

### Handoff Strategy Details

#### DirectHandoff (Sequential Switching)

```java
public class DirectHandoff implements Handoff {
    @Override
    public void handoff(AgentGroup agentGroup, Planning planning,
                       Map<String, Object> variables) {
        var currentAgent = agentGroup.getCurrentAgent();

        // Get next Agent (circular order)
        String nextAgentName = getNextAgentNameOf(
            agentGroup.getAgents(),
            currentAgent == null ? null : currentAgent.getName()
        );

        // Set planning result
        var result = new DefaultPlanningResult();
        result.name = nextAgentName;
        result.query = agentGroup.getOutput() == null
            ? agentGroup.getInput()
            : agentGroup.getOutput();
        result.planning = "direct handoff to " + nextAgentName;

        planning.directPlanning(result);
    }

    // Circular get next Agent
    private String getNextAgentNameOf(List<Node<?>> agents, String currentName) {
        if (currentName == null) return agents.getFirst().getName();

        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).getName().equals(currentName)) {
                int nextIndex = (i + 1) % agents.size();
                return agents.get(nextIndex).getName();
            }
        }
        return "";
    }
}
```

#### AutoHandoff (Automatic Decision)

```java
public record AutoHandoff(Agent moderator) implements Handoff {
    @Override
    public void handoff(AgentGroup agentGroup, Planning planning,
                       Map<String, Object> variables) {
        // Set moderator's parent node
        if (moderator.getParentNode() == null) {
            moderator.setParentNode(agentGroup);
        }

        agentGroup.setCurrentAgent(moderator);

        // Let moderator Agent make planning decision
        // moderator's systemPrompt contains info about all available Agents
        // It outputs JSON-formatted planning result
        String text = planning.agentPlanning(moderator,
            agentGroup.getCurrentQuery(), variables);

        agentGroup.setRawOutput(text);
    }
}
```

### Planning Interface Design

```java
public interface Planning {
    /**
     * Agent planning mode: Let moderator Agent think about next action
     * @return moderator's raw output (JSON format)
     */
    String agentPlanning(Agent agent, String query, Map<String, Object> variables);

    /**
     * Directly set planning result (for DirectHandoff)
     */
    <T> void directPlanning(T instance);

    /**
     * Get planning result
     */
    String nextAgentName();  // Next Agent name to execute
    String nextQuery();      // Query to pass to next Agent
    String nextAction();     // Next action (may be termination word)
}
```

## Flow Execution Engine

### Flow Graph Structure

```
┌─────────────────────────────────────────────────────────────┐
│                     Flow Graph Structure                     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Nodes:                                                     │
│    ├─ FlowNodeType.EXECUTE      - Execute node              │
│    ├─ FlowNodeType.AGENT        - Agent node                │
│    ├─ FlowNodeType.AGENT_GROUP  - AgentGroup node           │
│    ├─ FlowNodeType.TOOL         - Tool node                 │
│    ├─ FlowNodeType.OPERATOR_FILTER - Filter node            │
│    └─ FlowNodeType.LLM          - Direct LLM call node      │
│                                                             │
│  Edges:                                                     │
│    ├─ FlowEdgeType.CONNECTION - Data flow connection        │
│    │     sourceNode ──data──▶ targetNode                    │
│    │                                                        │
│    └─ FlowEdgeType.SETTING    - Setting/config connection   │
│          settingNode ──config──▶ targetNode                 │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Flow Execution Process

```
Flow.run(nodeId, input, variables)
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│  1. validate()                                              │
│     Validate Flow configuration integrity                   │
│                                                             │
│  2. currentNode = getNodeById(nodeId)                       │
│     Get current node                                        │
│                                                             │
│  3. Trigger node change event                               │
│     flowNodeChangedEventListener.eventHandler(currentNode)  │
│                                                             │
│  4. Initialize node settings                                │
│     settings = getNodeSettings(currentNode)  // SETTING edge│
│     if (!settings.isEmpty()) {                              │
│         initSettings(settings)                              │
│         currentNode.initialize(settings, edges)             │
│     }                                                       │
│                                                             │
│  5. Execute node                                            │
│     if (isExecutableType(currentNode.type)) {               │
│         result = currentNode.execute(input, variables)      │
│                                                             │
│         // Check waiting status                             │
│         if (agent.status == WAITING_FOR_USER_INPUT) {       │
│             status = FlowStatus.WAITING_FOR_USER_INPUT      │
│             return result.text()                            │
│         }                                                   │
│     }                                                       │
│                                                             │
│  6. Trigger output update event                             │
│     flowNodeOutputUpdatedEventListener.eventHandler(...)    │
│                                                             │
│  7. Get next nodes                                          │
│     nextNodes = getNextNodes(currentNode)  // CONNECTION    │
│     if (nextNodes.isEmpty()) {                              │
│         status = FlowStatus.SUCCESS                         │
│         return result.text()                                │
│     }                                                       │
│                                                             │
│  8. Select next node (when multiple branches)               │
│     if (nextNodes.size() > 1) {                             │
│         nextNode = selectNextNodeByEdgeValue(result, nodes) │
│     } else {                                                │
│         nextNode = nextNodes.values().iterator().next()     │
│     }                                                       │
│                                                             │
│  9. Recursive execution                                     │
│     return execute(nextNode.id, result.text(), variables)   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Edge Processing Logic

```java
// Get next nodes (CONNECTION type edges)
public Map<FlowEdge<?>, FlowNode<?>> getNextNodes(FlowNode<?> node) {
    return edges.stream()
        .filter(edge -> edge.type == FlowEdgeType.CONNECTION)
        .filter(edge -> edge.getSourceNodeId().equals(node.id))
        .collect(Collectors.toMap(
            edge -> edge,
            edge -> getNodeById(edge.getTargetNodeId())
        ));
}

// Get node settings (SETTING type edges)
public List<FlowNode<?>> getNodeSettings(FlowNode<?> node) {
    return edges.stream()
        .filter(edge -> edge.type == FlowEdgeType.SETTING)
        .filter(edge -> edge.getSourceNodeId().equals(node.id))
        .map(edge -> getNodeById(edge.getTargetNodeId()))
        .toList();
}
```

### Conditional Routing

When a node has multiple downstream nodes, edge values are used for routing selection:

```java
// Route selection in FlowNode
public FlowNode<?> selectNextNodeByEdgeValue(FlowNodeResult result,
                                              Map<FlowEdge<?>, FlowNode<?>> nextNodes) {
    for (var entry : nextNodes.entrySet()) {
        FlowEdge<?> edge = entry.getKey();

        // Edge value matches result
        if (edge.getValue() != null &&
            edge.getValue().toString().equals(result.text())) {
            return entry.getValue();
        }
    }

    // Default to first node
    return nextNodes.values().iterator().next();
}
```

## Tracing and Telemetry

### OpenTelemetry Integration

Core-AI uses OpenTelemetry for distributed tracing, compatible with Langfuse and other platforms:

```
┌─────────────────────────────────────────────────────────────┐
│                   Trace Hierarchy                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Flow Execution Span (if any)                               │
│    └─ Group Execution Span (if any)                         │
│         └─ Agent Execution Span                             │
│              ├─ LLM Completion Span                         │
│              │    ├─ model: "gpt-4"                         │
│              │    ├─ input_tokens: 1500                     │
│              │    └─ output_tokens: 500                     │
│              └─ Tool Call Span                              │
│                   ├─ tool_name: "get_weather"               │
│                   ├─ input: "{city: 'Beijing'}"             │
│                   └─ output: "25C, sunny"                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### AgentTracer Core Methods

```java
public class AgentTracer {
    private final Tracer tracer;
    private final boolean enabled;

    /**
     * Trace Agent execution
     */
    public <T> T traceAgentExecution(AgentTraceContext context, Supplier<T> operation) {
        if (!enabled) return operation.get();

        var span = tracer.spanBuilder(INSTRUMENTATION_NAME)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("langfuse.observation.type", "agent")
            .setAttribute("gen_ai.operation.name", "agent")
            .setAttribute("gen_ai.agent.name", context.getName())
            .setAttribute("agent.has_tools", context.hasTools())
            .setAttribute("agent.has_rag", context.hasRag())
            .startSpan();

        try (var scope = span.makeCurrent()) {
            T result = operation.get();

            // Record execution result
            span.setAttribute("output.value", context.getOutput());
            span.setAttribute("agent.status", context.getStatus());

            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Trace tool call
     */
    public <T> T traceToolCall(String toolName, String arguments, Supplier<T> operation) {
        if (!enabled) return operation.get();

        var span = tracer.spanBuilder(toolName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("langfuse.observation.type", "tool")
            .setAttribute("tool.name", toolName)
            .setAttribute("input.value", arguments)
            .startSpan();

        try (var scope = span.makeCurrent()) {
            T result = operation.get();
            span.setAttribute("output.value", result.toString());
            return result;
        } finally {
            span.end();
        }
    }
}
```

### Trace Attribute Specification

| Category | Attribute | Description |
|----------|-----------|-------------|
| **Agent** | `gen_ai.agent.name` | Agent name |
| | `gen_ai.agent.id` | Agent ID |
| | `agent.has_tools` | Tools enabled |
| | `agent.has_rag` | RAG enabled |
| | `agent.status` | Execution status |
| | `agent.message_count` | Message count |
| **LLM** | `gen_ai.request.model` | Request model |
| | `gen_ai.response.model` | Response model |
| | `gen_ai.usage.input_tokens` | Input token count |
| | `gen_ai.usage.output_tokens` | Output token count |
| | `gen_ai.response.finish_reasons` | Finish reason |
| **Tool** | `tool.name` | Tool name |
| | `input.value` | Input parameters |
| | `output.value` | Output result |
| **Session** | `session.id` | Session ID |
| | `user.id` | User ID |

## Summary

Core-AI framework achieves powerful and flexible AI Agent capabilities through the following core designs:

1. **Layered Architecture**: Clear responsibility separation for easy understanding and extension
2. **Lifecycle Hooks**: 8 hook points covering complete execution process, supporting AOP-style extension
3. **State Machine**: Clear state transitions for handling async and waiting scenarios
4. **Tool System**: Auto JSON Schema generation, supports auth and async execution
5. **Multi-Agent Coordination**: Handoff + Planning dual mechanism for flexible coordination
6. **Flow Orchestration**: Directed graph execution engine with conditional routing and event listeners
7. **Observability**: Native OpenTelemetry support, compatible with mainstream observability platforms

Understanding these core mechanisms will help you better use Core-AI to build complex AI applications.
