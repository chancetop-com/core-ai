# Architecture and Runtime Boundaries

<span class="legacy-anchor" id="core-ai-architecture-deep-dive"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="overall-architecture"></span>
<span class="legacy-anchor" id="layered-architecture-design"></span>
<span class="legacy-anchor" id="core-design-principles"></span>
<span class="legacy-anchor" id="agent-execution-engine"></span>
<span class="legacy-anchor" id="execution-flow-details"></span>
<span class="legacy-anchor" id="state-transition-mechanism"></span>
<span class="legacy-anchor" id="core-code-analysis"></span>
<span class="legacy-anchor" id="lifecycle-system"></span>
<span class="legacy-anchor" id="hook-point-design"></span>
<span class="legacy-anchor" id="abstractlifecycle-class"></span>
<span class="legacy-anchor" id="built-in-lifecycle-implementations"></span>
<span class="legacy-anchor" id="custom-lifecycle-example"></span>
<span class="legacy-anchor" id="tool-execution-mechanism"></span>
<span class="legacy-anchor" id="json-schema-auto-generation"></span>
<span class="legacy-anchor" id="type-mapping-table"></span>
<span class="legacy-anchor" id="schema-generation-core-logic"></span>
<span class="legacy-anchor" id="tool-execution-flow"></span>
<span class="legacy-anchor" id="tool-executor-core-code"></span>
<span class="legacy-anchor" id="message-processing-flow"></span>
<span class="legacy-anchor" id="message-types-and-structure"></span>
<span class="legacy-anchor" id="message-building-flow"></span>
<span class="legacy-anchor" id="flow-execution-engine"></span>
<span class="legacy-anchor" id="flow-graph-structure"></span>
<span class="legacy-anchor" id="flow-execution-process"></span>
<span class="legacy-anchor" id="edge-processing-logic"></span>
<span class="legacy-anchor" id="conditional-routing"></span>
<span class="legacy-anchor" id="tracing-and-telemetry"></span>
<span class="legacy-anchor" id="opentelemetry-integration"></span>
<span class="legacy-anchor" id="agenttracer-core-methods"></span>
<span class="legacy-anchor" id="trace-attribute-specification"></span>
<span class="legacy-anchor" id="summary"></span>

Run the [offline Agent](framework.md#离线-agent) first. This page describes current entry points and module boundaries, without treating historical proposals as implemented features.

## Three parts and public API {#三部分与公共-api}

<div class="architecture-map">
<div><strong>Entry points</strong><a href="/core-ai/en/server">Server / Web</a><a href="/core-ai/en/cli">CLI</a><span>Java applications</span></div>
<div><strong>Agent execution</strong><span>core-ai framework</span><span>Provider · Tools · Context</span></div>
<div><strong>Shared types</strong><span>core-ai-api</span><a href="/core-ai/en/api">Interfaces and DTOs</a></div>
</div>

Server owns shared resources, identity and session services. CLI provides local interaction and Hub commands. The framework provides agents, providers, tools and context. Public API also includes service DTOs; current source builds require Java 25 rather than an independent Java 17 compatibility promise.

## Agent entry point {#agent-运行入口}

Applications call `Node.run(...)` with a query and optional context. The builder configures models, tools and lifecycle options. Execution can complete, fail or wait for input/asynchronous work. Check `NodeStatus`; internal `execute` methods are not the public application entry point.

## Tools and identity {#工具与资源身份}

Local function tools, stdio MCP and Server Hub are distinct entry points. Prompts and tool names do not establish Server authorization. Python SDK uses CLI locally and a session Hub in sandbox environments, with different identity boundaries.

## Context and orchestration {#上下文与编排}

Memory, Compression and RAG cover recall, context length and document retrieval respectively. Java Flow and Server workflows are not interchangeable; persistence and recovery require explicit implementations and lifecycles.

## Source navigation {#源码定位}

| Topic | Entry point |
| --- | --- |
| Public run methods | [Node.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/Node.java) |
| Agent configuration | [AgentBuilder.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/AgentBuilder.java) |
| CLI commands | [Main.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai-cli/src/main/java/Main.java) |
| Server services | [ServerApp.java](https://github.com/chancetop-com/core-ai/blob/master/core-ai-server/src/main/java/ai/core/server/ServerApp.java) |

[API Reference](/en/api) · [Basic Agent](tutorial-basic-agent.md) · [Flow](tutorial-flow.md) · [Historical Server design](design-server-architecture.md)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-architecture.md). They may use older APIs; use the source-checked examples above for current development.
