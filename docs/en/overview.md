# Core AI Overview

<span class="legacy-anchor" id="what-is-core-ai"></span>
<span class="legacy-anchor" id="core-features"></span>
<span class="legacy-anchor" id="_1-unified-llm-abstraction"></span>
<span class="legacy-anchor" id="_2-intelligent-agents"></span>
<span class="legacy-anchor" id="_3-tool-calling"></span>
<span class="legacy-anchor" id="_4-rag-retrieval-augmented-generation"></span>
<span class="legacy-anchor" id="_5-flow-orchestration"></span>
<span class="legacy-anchor" id="_6-observability"></span>
<span class="legacy-anchor" id="architecture-design"></span>
<span class="legacy-anchor" id="layered-architecture"></span>
<span class="legacy-anchor" id="modular-design"></span>
<span class="legacy-anchor" id="design-principles"></span>
<span class="legacy-anchor" id="_1-builder-pattern"></span>
<span class="legacy-anchor" id="_2-async-first"></span>
<span class="legacy-anchor" id="_3-extensibility"></span>
<span class="legacy-anchor" id="_4-production-ready"></span>
<span class="legacy-anchor" id="use-cases"></span>
<span class="legacy-anchor" id="technology-stack"></span>
<span class="legacy-anchor" id="open-source-ecosystem"></span>
<span class="legacy-anchor" id="next-steps"></span>

Core AI combines a team service, a terminal client and a Java agent framework. Server and CLI share `core-ai` and `core-ai-api` within one repository, but their entry points and runtime requirements differ.

## Choose an entry point {#选择入口}

| Part | Purpose | Start here |
| --- | --- | --- |
| Server | Manage agents, tools, skills and datasets; expose Web, REST and SSE | [Server](server.md) |
| CLI | Local agents, editor ACP and Server Hub operations | [CLI](cli.md) |
| Framework | Build agents, tools, context and Flow in Java applications | [Java framework](framework.md) |

CLI can connect directly to a model provider or sign in to Server for its model proxy. Hub commands discover and call authorized Server resources. Framework Flow and the Server workflow editor are separate layers; their configurations are not interchangeable.

## Environment and versions {#环境与版本}

Source baseline: `e1afa9fa`, CLI 2.0.21, framework 1.4.0-SNAPSHOT, public API 1.3.0-SNAPSHOT. Source builds require **Java 25** and **Gradle Wrapper 9.3.0**. Native CLI releases include a runtime. Server also needs MongoDB and Redis; other infrastructure depends on enabled features.

A SNAPSHOT constant does not establish Maven publication. Use same-checkout project dependencies or artifacts confirmed by your team. Check the source and `--help` for your actual release.

## Capabilities and prerequisites {#能力与前提}

- Agents: prompts, providers, tools and execution status.
- Tools: annotated functions, MCP and approval handling; side effects depend on each implementation.
- Context: compression and memory are independently configured.
- Retrieval: RAG needs documents, embeddings, a VectorStore and its runtime dependencies.
- Orchestration and tracing: framework Flow, Server workflows and Traces have distinct contracts.

## Reading path {#推荐阅读路径}

[Quick Start](quickstart.md) → [Offline Agent](framework.md#离线-agent) → [Basic Agent](tutorial-basic-agent.md) → [Function Tools](tutorial-tool-calling.md). Continue with [Memory](tutorial-memory.md), [RAG](tutorial-rag.md), [Skills](tutorial-skills.md) or [Flow](tutorial-flow.md) as needed.

The [tutorial index](tutorials.md) states prerequisites and validation limits. [API Reference](/en/api) points to current types and source. Design records explain background rather than define the current API.

## Validation scope {#验证范围}

Source CLI help/version, Mock Agent and FakeSession were tested on macOS. Server startup, live model calls, Windows execution, native installation and upgrades were not verified. Expected results are separate from execution records in the [full manual (Chinese)](/cn/manual/).
