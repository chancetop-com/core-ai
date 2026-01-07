# Core-AI Overview

## What is Core-AI?

Core-AI is a powerful Java framework designed specifically for building intelligent agents (AI Agents) and multi-agent applications. It provides a comprehensive set of abstractions and tools that enable developers to easily integrate various Large Language Model (LLM) providers and build sophisticated AI-driven applications.

## Core Features

### 1. Unified LLM Abstraction
- **Multi-Provider Support**: Seamless integration with Azure OpenAI, Azure AI Inference, and other LLM providers
- **Standardized Interface**: Unified API interface for easy switching between different model providers
- **Streaming Response**: Support for real-time streaming output to enhance user experience

### 2. Intelligent Agents
- **Autonomous Decision Making**: Agents can autonomously select tools and execution strategies based on tasks
- **System Prompts**: Support for custom system prompts and Mustache template engine
- **Memory Management**: Built-in short-term and long-term memory systems
- **Reflection Capability**: Agents can reflect on execution results and self-improve

### 3. Tool Calling
- **Function Calling**: Support for JSON Schema-defined function calls
- **MCP Protocol**: Full support for Model Context Protocol for standardized tool integration
- **Built-in Toolset**: Out-of-the-box implementation of commonly used tools

### 4. RAG (Retrieval-Augmented Generation)
- **Vector Storage**: Integration with vector databases like Milvus and HNSWLib
- **Intelligent Retrieval**: Support for query rewriting, similarity search, and reranking
- **Document Processing**: Built-in document splitting and processing tools

### 5. Flow Orchestration
- **Visual Workflows**: Orchestrate complex execution flows through directed graphs
- **Node Types**: Support for Agent, LLM, RAG, Tool, Operator, and other node types
- **Conditional Routing**: Support for condition-based dynamic routing
- **State Management**: Complete flow state tracking and persistence

### 6. Observability
- **Distributed Tracing**: Built-in OpenTelemetry support
- **Performance Monitoring**: Detailed execution time and token usage statistics
- **Integration Support**: Compatible with observability platforms like Langfuse and Jaeger

## Architecture Design

### Layered Architecture

```
┌─────────────────────────────────────┐
│        Applications Layer           │
├─────────────────────────────────────┤
│      Orchestration Layer            │
│         Flow / Planning             │
├─────────────────────────────────────┤
│         Agents Layer                │
│   Agent / Memory / Reflection       │
├─────────────────────────────────────┤
│       Capabilities Layer            │
│  Tools / RAG / VectorStore / MCP    │
├─────────────────────────────────────┤
│        Providers Layer              │
│     LLM / Embeddings / Reranker     │
└─────────────────────────────────────┘
```

### Modular Design

Core-AI adopts a highly modular design with main modules including:

- **core-ai-api**: API definitions and interfaces (Java 17 compatible)
- **core-ai**: Main framework library containing all core functionality
- **example-service**: Reference implementation demonstrating framework usage
- **example-service-interface**: Service interface definitions

## Design Principles

### 1. Builder Pattern
All core classes provide fluent builder APIs to simplify complex object creation:

```java
Agent agent = Agent.builder()
    .name("assistant")
    .llmProvider(provider)
    .systemPrompt("You are a helpful assistant")
    .enableRAG(ragConfig)
    .build();
```

### 2. Async-First
The framework design fully considers asynchronous execution, supporting streaming responses and concurrent processing.

### 3. Extensibility
Through interfaces and abstract classes, easily extend new LLM providers, tools, and vector stores.

### 4. Production-Ready
- Complete error handling and retry mechanisms
- State persistence and recovery
- Distributed tracing and monitoring

## Use Cases

Core-AI is suitable for building various AI-driven applications:

1. **Intelligent Customer Service**: Build context-aware customer service agents that can call tools
2. **Code Assistants**: Create development assistants that understand code and execute tasks
3. **Data Analytics Platforms**: Build analytics agents for data querying and report generation
4. **Workflow Automation**: Implement complex business process automation through flow orchestration
5. **Knowledge Management Systems**: Build enterprise knowledge bases and Q&A systems using RAG

## Technology Stack

- **Java 21**: Leveraging the latest Java features
- **Gradle**: Build system using Kotlin DSL
- **core-ng framework**: Base application framework
- **Jackson**: JSON processing
- **Mustache**: Template engine
- **OpenTelemetry**: Distributed tracing

## Open Source Ecosystem

Core-AI actively embraces the open source ecosystem with excellent integration with the following projects:

- **Milvus**: Open source vector database
- **OpenTelemetry**: Cloud-native observability framework
- **Model Context Protocol**: Standardized model context protocol
- **Langfuse**: Observability platform for LLM applications

## Next Steps

- Check out the [Quick Start Guide](quickstart.md) to get up and running quickly
- Read the Tutorials to learn about features in depth:
  - [Building AI Agents](tutorial-basic-agent.md) - Create intelligent agents
  - [Memory Systems](tutorial-memory.md) - Long-term memory with vector search
  - [Compression](tutorial-compression.md) - Session-based context management
  - [Tool Calling](tutorial-tool-calling.md) - Extend agents with tools
  - [RAG Integration](tutorial-rag.md) - Knowledge retrieval
  - [Flow Orchestration](tutorial-flow.md) - Visual workflows
- Explore the [API Documentation](api-reference.md) for detailed interfaces
- Reference [Example Projects](examples.md) for real-world applications