# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**core-ai** is a Java framework for building AI agents and multi-agent applications. It provides abstractions for LLM providers, agents, tools, RAG (Retrieval-Augmented Generation), vector stores, and agent flows.

### Key Modules

- **core-ai**: The main framework library with agent abstractions and AI capabilities
- **core-ai-api**: API definitionsand interfaces (Java 17 compatible)
- **example-service**: Reference implementation showing framework usage
- **example-service-interface**: Service interface definitions

## Build Commands

The project uses Gradle with Kotlin DSL. Java 21 toolchain is required.

### Basic Commands
```bash
# Build entire project
./gradlew build

# Build specific module
./gradlew :core-ai:build
./gradlew :example-service:build

# Clean and rebuild
./gradlew clean build

# Run tests
./gradlew test

# Run tests for specific module
./gradlew :core-ai:test

# Run the example service
./gradlew :example-service:run

# Create distribution
./gradlew :example-service:installDist

# Create Docker-ready build
./gradlew :example-service:docker

# Publish to Maven repository (core-ai modules only)
./gradlew publish
```

### Environment-Specific Builds
```bash
# Build with specific environment configuration
./gradlew -Penv=dev build
./gradlew -Penv=prod build
```

### Local Development
To use local projects instead of published versions, set environment variable:
```bash
export CORE_AI_USE_LOCAL_PROJECTS=true
```

## Architecture

### Core Abstractions

**Agent (`ai.core.agent.Agent`)**
- Main abstraction for AI agents with LLM interactions
- Supports system prompts, prompt templates (Mustache), and tool calls
- Features: RAG integration, reflection, memory (short-term and long-term), streaming
- Agent execution returns output after processing queries through LLM
- Can enter WAITING_FOR_USER_INPUT status when tools require authentication

**Flow (`ai.core.flow.Flow`)**
- Orchestrates multiple agents/nodes in a directed graph
- Nodes: Agent, LLM, RAG, Tool, Operator (filter/conditional routing)
- Edges: CONNECTION (flow between nodes) and SETTING (configuration)
- Executes by traversing nodes from start to finish

**AgentGroup (`ai.core.agent.AgentGroup`)**
- Manages multiple agents working together
- Supports handoff strategies: DirectHandoff, AutoHandoff, HybridAutoDirectHandoff
- Planning strategies for coordinating agent actions
- Termination conditions for group completion

**LLM Providers (`ai.core.llm.LLMProvider`)**
- Abstract interface for LLM services
- Implementations in `ai.core.llm.providers`: Azure OpenAI, Azure Inference, etc.
- Supports completion, streaming, embeddings, reranking, image captioning

**Tools (`ai.core.tool.ToolCall`)**
- Function calling interface for agents
- JSON schema generation for tool definitions
- MCP (Model Context Protocol) integration support (`ai.core.mcp`)
- Built-in tools in `ai.core.tool.tools`

**Vector Stores (`ai.core.vectorstore`)**
- Implementations: Milvus, HNSWLib
- Used for RAG similarity search and document retrieval

**Memory (`ai.core.memory`)**
- NaiveMemory: Simple key-value memory for context retention
- Integrated into agents for long-term context

### Key Patterns

**Builder Pattern**: Most core classes use builders (Agent.builder(), Flow.builder())

**Node Hierarchy**: Both Agent and Flow extend from Node concept with status tracking (PENDING, RUNNING, COMPLETED, WAITING_FOR_USER_INPUT, FAILED)

**Persistence**: Framework provides persistence abstraction for saving/loading agent and flow state

**Event Listeners**: Flow supports listeners for node changes and output updates

**RAG Integration**: Agents can enable RAG with query rewriting, embeddings, similarity search, and reranking

**Telemetry/Tracing**: OpenTelemetry integration for distributed tracing via OTLP (compatible with Langfuse, Jaeger, etc.)

## Project Structure

```
core-ai/src/main/java/ai/core/
├── agent/           # Agent abstractions, formatters, handoffs, planning
├── flow/            # Flow orchestration, nodes, edges
├── llm/             # LLM provider interfaces and implementations
├── tool/            # Tool calling, function definitions, MCP integration
├── vectorstore/     # Vector store implementations (Milvus, HNSWLib)
├── memory/          # Memory implementations
├── rag/             # RAG configuration and utilities
├── mcp/             # Model Context Protocol (client/server)
├── task/            # Task management abstractions
├── document/        # Document processing and text splitting
├── prompt/          # Prompt templating (Mustache engine)
├── sse/             # Server-Sent Events for streaming
├── telemetry/       # OpenTelemetry tracing (AgentTracer, TelemetryConfig)
├── a2a/             # Agent-to-Agent communication
├── defaultagents/   # Built-in utility agents
└── utils/           # JSON schema, reflection utilities
```

## Dependencies

Key external dependencies (see `buildSrc/src/main/kotlin/Versions.kt`):
- core-ng framework (9.1.8): Base application framework
- Azure OpenAI SDK (1.0.0-beta.16)
- Azure AI Inference (1.0.0-beta.5)
- Milvus Java SDK (2.5.2)
- Mustache.java (0.9.10): Template engine
- JTokkit (1.1.0): Token counting
- Jackson (2.17.2): JSON processing
- HNSWLib (1.2.0): Vector similarity search
- OpenTelemetry (1.44.1): Distributed tracing (API, SDK, OTLP exporter)

## Testing

Tests use JUnit Platform with these settings:
- Fail fast on first failure
- Full exception stack traces
- Standard streams shown in output
- JVM arg: `-XX:+EnableDynamicAgentLoading`

## Application Structure (example-service)

The example service extends `core.framework.module.App`:
- Main class: `ai.core.example.ExampleApp`
- Modules loaded: SystemModule, MultiAgentModule, McpServerModule, ExampleModule
- Configuration in `conf/` directory (environment-specific resources)

Start scripts set JVM properties:
- `-Dcore.webPath=APP_HOME/web`
- `-Dcore.appName=${applicationName}`

## Maven Repositories

Custom repositories required:
```groovy
maven { url = "https://neowu.github.io/maven-repo/" }  // core.framework.*
maven { url = "https://chancetop-com.github.io/maven-repo/" }  // com.chancetop.*
maven { url = "https://maven.codelibs.org/" }  // elasticsearch modules
```

## Module Publishing

Only `core-ai`, `core-ai-api`, and modules ending with `-library` or `-api` are published to Maven repository.

Current versions:
- core-ai: 1.1.84
- core-ai-api: 1.1.14

## Interface Modules

Modules ending with `-interface` or `-interface-v2` are compiled with Java 17 for broader compatibility, while other modules use Java 21.

## OpenTelemetry Tracing

The framework includes built-in support for distributed tracing via OpenTelemetry OTLP export, compatible with Langfuse and other observability platforms.

### Automatic Configuration (Recommended)

Tracing is automatically initialized when you set the `trace.otlp.endpoint` property in your application configuration. The `MultiAgentModule` loads the `TelemetryModule` which handles the setup:

**Properties** (in `conf/sys.properties`):
```properties
# Required: OTLP endpoint (e.g., Langfuse, Jaeger, custom collector)
trace.otlp.endpoint=https://cloud.langfuse.com

# Optional: Service identification (defaults shown)
trace.service.name=core-ai
trace.service.version=1.0.0
trace.environment=production
```

When these properties are set:
1. `TelemetryModule` automatically creates `TelemetryConfig` and `AgentTracer`
2. Both are bound for dependency injection throughout your application
3. Tracing is enabled for all agents and flows that have the tracer injected

**Usage with Dependency Injection**:
```java
public class MyService {
    @Inject
    AgentTracer tracer;  // Auto-injected when trace.otlp.endpoint is set

    @Inject
    LLMProvider llmProvider;

    public void createAgent() {
        Agent agent = Agent.builder()
            .name("my-agent")
            .llmProvider(llmProvider)
            .tracer(tracer)  // Use injected tracer
            .build();
    }
}
```

### Manual Configuration (Advanced)

For custom telemetry setup outside the standard module initialization:

```java
// 1. Create telemetry configuration
TelemetryConfig telemetryConfig = TelemetryConfig.builder()
    .serviceName("my-agent-service")
    .serviceVersion("1.0.0")
    .environment("production")
    .otlpEndpoint("https://cloud.langfuse.com")
    .enabled(true)
    .build();

// 2. Create tracer
AgentTracer tracer = new AgentTracer(
    telemetryConfig.getOpenTelemetry(),
    telemetryConfig.isEnabled()
);

// 3. Add tracer to agents
Agent agent = Agent.builder()
    .name("my-agent")
    .llmProvider(llmProvider)
    .tracer(tracer)
    .build();

// 4. Add tracer to flows
Flow flow = Flow.builder()
    .name("my-flow")
    .nodes(nodes)
    .edges(edges)
    .tracer(tracer)
    .build();
```

### What Gets Traced

- **Agent Executions**: Full agent lifecycle with input/output, status, message counts
- **LLM Completions**: Model calls with token usage, finish reason, request parameters
- **Tool Calls**: Function executions with arguments and results
- **Flow Executions**: Multi-node workflow execution paths

### Trace Attributes

Follows OpenTelemetry semantic conventions with custom agent-specific attributes:
- `gen_ai.*`: LLM request/response details (model, tokens, temperature)
- `agent.*`: Agent metadata (name, ID, type, status, tools, RAG)
- `flow.*`: Flow orchestration details (flow ID, node ID)
- `tool.*`: Tool call information (name, arguments)

### Langfuse Integration

For Langfuse, configure the OTLP endpoint:
- **US Cloud**: `https://us.cloud.langfuse.com`
- **EU Cloud**: `https://cloud.langfuse.com`
- **Self-hosted**: Your deployment endpoint

Authentication via Langfuse public/secret keys passed as OTLP headers.

### Implementation Details

The telemetry integration is implemented across several components:

- **`TelemetryModule.java`** (core-ai:162): Auto-initializes tracing when `trace.otlp.endpoint` is set
- **`MultiAgentModule.java`** (core-ai:162): Loads TelemetryModule via `configTelemetry()` method
- **`TelemetryConfig.java`** (core-ai/src/main/java/ai/core/telemetry): OpenTelemetry configuration with OTLP exporter
- **`AgentTracer.java`** (core-ai/src/main/java/ai/core/telemetry): Tracing utilities for agents, flows, LLM calls, and tools
- **`Node.java`** (core-ai/src/main/java/ai/core/agent): Base class supporting tracer injection (lines 59, 321-327)
- **`Agent.java`** (core-ai/src/main/java/ai/core/agent): Traces agent execution lifecycle
- **`Flow.java`** (core-ai/src/main/java/ai/core/flow): Traces flow execution paths

See `example-service/src/main/java/ai/core/example/TelemetryExampleModule.java` for a manual configuration example.