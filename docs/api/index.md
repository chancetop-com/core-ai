# API Reference · 类型与源码

Use the source contract matching your checkout or release. 当前使用文档以 `e1afa9fa` 为源码基线；示例编译核对使用 Java 25，联网模型未运行。

## Agent {#agent}

- [AgentBuilder](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/AgentBuilder.java): providers, models, tools, memory, RAG and compression.
- [Node](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/Node.java): public `run` overloads return String.
- [StreamingCallback](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/llm/streaming/StreamingCallback.java): `onChunk(String)`, `onComplete()` and cancellation hooks.
- [LLMProvider](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/llm/LLMProvider.java): the provider abstraction.

[中文 Agent 教程](/cn/tutorial-basic-agent) · [English Agent tutorial](/en/tutorial-basic-agent)

## Tools {#tools}

[Function annotations](https://github.com/chancetop-com/core-ai/tree/master/core-ai-api/src/main/java/ai/core/api/tool/function) · [Functions converter](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/tool/function/Functions.java) · [Tool implementations](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/tool)

[中文工具教程](/cn/tutorial-tool-calling) · [English tool tutorial](/en/tutorial-tool-calling)

## Flow {#flow}

[Flow](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/flow/Flow.java) · [Nodes and edges](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/flow)

Framework Flow and Server workflows have different definitions. [中文说明](/cn/tutorial-flow) · [English guide](/en/tutorial-flow)

## Server and CLI {#server-cli}

[Public service DTOs](https://github.com/chancetop-com/core-ai/tree/master/core-ai-api/src/main/java/ai/core/api/server) · [Server handlers](https://github.com/chancetop-com/core-ai/tree/master/core-ai-server/src/main/java/ai/core/server) · [CLI Main](https://github.com/chancetop-com/core-ai/blob/master/core-ai-cli/src/main/java/Main.java)

CLI root flags and Hub subcommand flags differ. Use the actual `--help` and [operations manual](/cn/manual/) for request examples and validation scope.

## Compile-only examples {#examples}

[GuideApiExamples.java](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java) collects current guide fragments. It has no main method and does not execute provider requests. [Offline Mock sample](https://github.com/chancetop-com/core-ai/blob/master/docs/cn/manual/examples/java/OfflineAgentDemo.java) has separate runtime evidence.

This reference links to source instead of assuming every deployment includes generated JavaDoc. 自动生成文档不应成为空链接；源码与指南是始终可用的参考入口。
