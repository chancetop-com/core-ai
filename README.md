<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./assets/core-ai-logo-v5-symbol-c-wordmark-dark.svg">
    <img src="./assets/core-ai-logo-v5-symbol-c-wordmark.svg" alt="core-ai" width="640">
  </picture>
</p>

<div align="center">

[![Java Version](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Documentation](https://img.shields.io/badge/Documentation-Available-brightgreen.svg)](https://chancetop-com.github.io/core-ai/)
[![GitHub Stars](https://img.shields.io/github/stars/chancetop-com/core-ai?style=social)](https://github.com/chancetop-com/core-ai)

[English](README.md) | [中文](README.zh-CN.md)

</div>

---

## 🌟 Core-AI: AI Agent in Your Terminal & Server

Core-AI gives you an AI-powered coding agent that runs in your terminal and a self-hosted agent server with a web UI. The CLI works standalone with any LLM provider's API key, or connects to a core-ai-server for team features. Chat with LLMs, execute tools, orchestrate sub-agents, and manage knowledge — all from the command line or your browser.

### 🚀 Quick Start

#### CLI Tool

Download and run in seconds:

```bash
# macOS
curl -L -o core-ai-cli https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-darwin
chmod +x core-ai-cli && sudo mv core-ai-cli /usr/local/bin/

# Linux
curl -L -o core-ai-cli https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-linux
chmod +x core-ai-cli && sudo mv core-ai-cli /usr/local/bin/

# Windows (PowerShell)
mkdir "$env:USERPROFILE\bin" -Force
Invoke-WebRequest -Uri "https://github.com/chancetop-com/core-ai/releases/latest/download/core-ai-cli-windows.exe" -OutFile "$env:USERPROFILE\bin\core-ai-cli.exe"
```

```bash
core-ai-cli
```

The CLI works in two modes:

- **Standalone** — Configure a provider API key directly in `~/.core-ai/agent.properties` and start chatting. Supports OpenAI, DeepSeek, OpenRouter, Azure, LiteLLM, and any OpenAI-compatible API.
- **With a server** — Login to a core-ai-server to auto-configure the LLM proxy, share agents, and access team features.

If no configuration is found on first run, the CLI will guide you through server login. You can add or switch providers anytime via the `/model` slash command.

**Common commands:**

```bash
core-ai-cli                                          # Interactive chat
core-ai-cli --prompt "Explain quantum computing"       # One-shot query
core-ai-cli --model "openai/gpt-4o"                    # Use a specific model
core-ai-cli --workspace /path/to/project                # Set workspace
core-ai-cli --continue                                  # Resume last session
core-ai-cli --server https://your-server.com --api-key your-token  # Remote server
core-ai-cli --serve                                     # Start as A2A web server
core-ai-cli --upgrade                                   # Self-update
```

#### Server (Docker)

Run core-ai-server locally with a single command:

**Prerequisites:** [Docker](https://docs.docker.com/get-docker/) and Docker Compose

```bash
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai
docker compose -f docker-compose.local.yml up -d
```

Open [https://localhost:8443](https://localhost:8443). Default admin: `admin@example.com` / `admin`

<details>
<summary>Minimal Docker Compose example</summary>

```yaml
name: core-ai

services:
  mongo:
    image: mongo:7
    command: ["mongod", "--replSet", "rs0", "--bind_ip_all"]
    ports:
      - "27017:27017"
    volumes:
      - mongo-data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand({ ping: 1 }).ok"]
      interval: 10s
      timeout: 5s
      retries: 10

  mongo-init:
    image: mongo:7
    depends_on:
      mongo:
        condition: service_healthy
    restart: "no"
    entrypoint:
      - bash
      - -c
      - |
        mongosh --host mongo:27017 --quiet --eval '
          try {
            rs.status();
          } catch (e) {
            rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "mongo:27017" }] });
          }
        '

  core-ai-server:
    image: chancetop/core-ai-server:latest
    depends_on:
      mongo-init:
        condition: service_completed_successfully
    ports:
      - "8080:8080"
    environment:
      SYS_HTTP_LISTEN: "8080"
      SYS_MONGO_URI: "mongodb://mongo:27017/core-ai?replicaSet=rs0"
      SYS_ADMIN_EMAIL: "admin@example.com"
      SYS_ADMIN_PASSWORD: "admin"
      SYS_ADMIN_NAME: "Admin"
      LLM_MODEL: "gpt-4o"

volumes:
  mongo-data:
```

</details>

> 💡 The full `docker-compose.local.yml` includes Redis, sandbox, and SSL. See [core-ai-server/README.md](core-ai-server/README.md) for details.

### ✨ Features

- **💬 Terminal Agent** — Interactive AI chat with file ops, web search, code execution, and sub-agent orchestration. Works standalone with any LLM provider's API key
- **🌐 Agent Server** — Self-hosted web UI for agent management, session history, and multi-user team access
- **🧩 Tools & MCP** — Built-in tools (read, write, search, fetch, grep, glob) plus MCP protocol for custom tool servers
- **👥 Multi-Agent** — Delegate tasks to sub-agents and remote agents via A2A protocol
- **🧠 Memory & Knowledge** — Persistent session memory, markdown knowledge base, and automatic extraction
- **🔌 Multi-Provider** — Connect to OpenAI, DeepSeek, OpenRouter, Azure, LiteLLM, or any OpenAI-compatible API
- **📋 Todo & Planning** — Built-in task tracking with plan/review workflow
- **🎯 Skills System** — Modular, reusable domain knowledge packages with progressive disclosure

### 💡 Use Cases

- **💻 Coding Assistant** — Understands your codebase, executes multi-step tasks, manages todos
- **🤖 Customer Support** — Context-aware agents with tool integration and knowledge base
- **📊 Data Analysis** — Query data, generate reports, and visualize results
- **🔄 Workflow Automation** — Automate complex business processes with agent pipelines
- **📚 Knowledge Management** — Build and query enterprise knowledge bases
- **👥 Team Collaboration** — Multiple users share agents, sessions, and tools via the server

### 🔧 For Developers

Core-AI is also a Java SDK for building custom AI agent applications.

#### Java SDK

**Maven:**
```xml
<dependency>
    <groupId>com.chancetop</groupId>
    <artifactId>core-ai</artifactId>
    <version>1.0.24</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'com.chancetop:core-ai:1.0.24'
```

Add repositories to your build configuration:
```groovy
repositories {
    mavenCentral()
    maven {
        url = uri("https://neowu.github.io/maven-repo/")
        content { includeGroupByRegex("core\\.framework.*") }
    }
    maven {
        url = uri("https://chancetop-com.github.io/maven-repo/")
        content { includeGroupByRegex("com\\.chancetop.*") }
    }
}
```

**Basic usage:**
```java
AzureOpenAIConfig config = AzureOpenAIConfig.builder()
    .endpoint("https://your-resource.openai.azure.com")
    .apiKey("your-api-key")
    .deploymentName("gpt-4")
    .build();

LLMProvider llmProvider = new AzureOpenAILLMProvider(config);

Agent agent = Agent.builder()
    .name("assistant")
    .description("A helpful AI assistant")
    .llmProvider(llmProvider)
    .systemPrompt("You are a helpful and professional AI assistant.")
    .build();

AgentOutput output = agent.execute("How can I help you today?");
System.out.println(output.getOutput());
```

#### Architecture

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

#### Build from Source

**Requirements:** Java 21+, Gradle 8.0+

```bash
git clone https://github.com/chancetop-com/core-ai.git
cd core-ai

# Build Java project
./gradlew build

# Build CLI native binary (requires GraalVM JDK 21+)
./gradlew :core-ai-cli:nativeCompile

# Run example service
./gradlew :example-service:run
```

### 📖 Documentation

- [Overview](./docs/en/overview.md) — Core concepts and architecture
- [Quick Start Guide](./docs/en/quickstart.md) — Get up and running in 10 minutes
- [Building AI Agents](./docs/en/tutorial-basic-agent.md) — Create agents with memory and reflection
- [Memory Systems](./docs/en/tutorial-memory.md) — Memory with vector semantic search
- [Tool Calling](./docs/en/tutorial-tool-calling.md) — Extend agents with custom tools
- [RAG Integration](./docs/en/tutorial-rag.md) — Retrieval-augmented generation
- [Skills System](./docs/en/tutorial-skills.md) — Modular domain knowledge packages
- [Flow Orchestration](./docs/en/tutorial-flow.md) — Build complex workflows

---

<div align="center">

### 🌐 Links

[GitHub](https://github.com/chancetop-com/core-ai) • [Documentation](./docs/) • [Issues](https://github.com/chancetop-com/core-ai/issues) • [Discussions](https://github.com/chancetop-com/core-ai/discussions)

</div>
