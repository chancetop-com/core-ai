# Core-AI Framework

<div align="center">

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](./docs/)
[![GitHub Stars](https://img.shields.io/github/stars/chancetop-com/core-ai?style=social)](https://github.com/chancetop-com/core-ai)

[English](README.md) | [中文](README.zh-CN.md)

</div>

---

## 🌟 Core-AI: Build Intelligent AI Agent Applications

Core-AI is a powerful Java framework designed for building AI agents and multi-agent applications. It provides comprehensive abstractions for LLM providers, agents, tools, RAG (Retrieval-Augmented Generation), vector stores, and agent flow orchestration.

### ✨ Key Features

- **🤖 Intelligent Agents** - Build autonomous agents with memory, reflection, and tool-calling capabilities
- **👥 Multi-Agent Systems** - Orchestrate multiple specialized agents working together
- **🔧 Tool Integration** - Extensive tool system with JSON Schema and MCP protocol support
- **📚 RAG Support** - Built-in RAG with vector stores (Milvus, HNSWLib) integration
- **🔄 Flow Orchestration** - Visual workflow design with conditional routing and parallel execution
- **🔍 Observability** - OpenTelemetry tracing compatible with Langfuse, Jaeger, etc.
- **☁️ LLM Providers** - Support for Azure OpenAI, Azure AI Inference, and more

### 🚀 Quick Start

#### Installation

**Maven:**
```xml
<dependency>
    <groupId>com.chancetop</groupId>
    <artifactId>core-ai</artifactId>
    <version>1.1.84</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'com.chancetop:core-ai:1.1.84'
```

#### Maven Repositories

Add the following repositories to your build configuration:

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://neowu.github.io/maven-repo/")
        content {
            includeGroupByRegex("core\\.framework.*")
        }
    }
    maven {
        url = uri("https://chancetop-com.github.io/maven-repo/")
        content {
            includeGroupByRegex("com\\.chancetop.*")
        }
    }
}
```

#### Basic Example

```java
// Initialize LLM Provider
AzureOpenAIConfig config = AzureOpenAIConfig.builder()
    .endpoint("https://your-resource.openai.azure.com")
    .apiKey("your-api-key")
    .deploymentName("gpt-4")
    .build();

LLMProvider llmProvider = new AzureOpenAILLMProvider(config);

// Create an AI Agent
Agent agent = Agent.builder()
    .name("assistant")
    .description("A helpful AI assistant")
    .llmProvider(llmProvider)
    .systemPrompt("You are a helpful and professional AI assistant.")
    .build();

// Execute Query
AgentOutput output = agent.execute("How can I help you today?");
System.out.println(output.getOutput());
```

### 📖 Documentation

**Getting Started**
- [Overview](./docs/en/overview.md) - Core concepts and architecture
- [Quick Start Guide](./docs/en/quickstart.md) - Get up and running in 10 minutes

**Tutorials**
- [Building AI Agents](./docs/en/tutorial-basic-agent.md) - Create intelligent agents with memory and reflection
- [Memory Systems](./docs/en/tutorial-memory.md) - Long-term memory with vector semantic search
- [Compression](./docs/en/tutorial-compression.md) - Session-based context management
- [Multi-Agent Systems](./docs/en/tutorial-multi-agent.md) - Orchestrate multiple agents with handoff strategies
- [RAG Integration](./docs/en/tutorial-rag.md) - Implement retrieval-augmented generation
- [Tool Calling](./docs/en/tutorial-tool-calling.md) - Extend agents with custom tools
- [Flow Orchestration](./docs/en/tutorial-flow.md) - Build complex workflows

### 🏗️ Architecture

```
┌─────────────────────────────────────┐
│        Applications Layer           │
├─────────────────────────────────────┤
│      Orchestration Layer            │
│    Flow / AgentGroup / Planning     │
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

### 💡 Use Cases

- **🤖 Customer Support** - Build context-aware support agents with tool integration
- **💻 Code Assistants** - Create development assistants that understand and execute tasks
- **📊 Data Analytics** - Build agents for data querying and report generation
- **🔄 Workflow Automation** - Automate complex business processes
- **📚 Knowledge Management** - Enterprise knowledge bases with Q&A systems
- **👥 Collaborative AI** - Multiple specialized agents working together

### 🛠️ Development

**Requirements:**
- Java 21+
- Gradle 8.0+
- An LLM API key (Azure OpenAI or compatible provider)

**Build from Source:**
```bash
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai
./gradlew build
```

**Run Examples:**
```bash
./gradlew :example-service:run
```

---

<div align="center">

### 🌐 Links

[GitHub](https://github.com/chancetop-com/core-ai) • [Documentation](./docs/) • [Issues](https://github.com/chancetop-com/core-ai/issues) • [Discussions](https://github.com/chancetop-com/core-ai/discussions)

</div>