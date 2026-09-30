# API 参考：类型与源码

以对应 checkout 或发布版本的源码契约为准。当前使用文档以 `e1afa9fa` 为源码基线；示例编译核对使用 Java 25，联网模型未运行。

## Agent {#agent}

- [AgentBuilder](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/AgentBuilder.java)：provider、模型、工具、记忆、RAG 与压缩配置。
- [Node](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/Node.java)：公共 `run` 重载返回 String。
- [StreamingCallback](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/llm/streaming/StreamingCallback.java)：`onChunk(String)`、`onComplete()` 与取消回调。
- [LLMProvider](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/llm/LLMProvider.java)：provider 抽象。

[中文 Agent 教程](/cn/tutorial-basic-agent)

## 工具 {#tools}

[函数注解](https://github.com/chancetop-com/core-ai/tree/master/core-ai-api/src/main/java/ai/core/api/tool/function) · [Functions 转换器](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/tool/function/Functions.java) · [工具实现](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/tool)

[中文工具教程](/cn/tutorial-tool-calling)

## Flow {#flow}

[Flow](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/flow/Flow.java) · [节点与边](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/flow)

框架 Flow 与 Server 工作流采用不同定义。 [中文说明](/cn/tutorial-flow)

## Server 与 CLI {#server-cli}

[服务 DTO](https://github.com/chancetop-com/core-ai/tree/master/core-ai-api/src/main/java/ai/core/api/server) · [Server 处理器](https://github.com/chancetop-com/core-ai/tree/master/core-ai-server/src/main/java/ai/core/server) · [CLI Main](https://github.com/chancetop-com/core-ai/blob/master/core-ai-cli/src/main/java/Main.java)

CLI 根参数与 Hub 子命令参数不同。请求示例和验证范围见实际 `--help` 与[使用手册](/cn/manual/)。

## 编译核对示例 {#examples}

[GuideApiExamples.java](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java) 汇集当前指南片段，没有 main 方法，编译过程不执行 provider 请求。[离线 Mock 示例](https://github.com/chancetop-com/core-ai/blob/master/docs/cn/manual/examples/java/OfflineAgentDemo.java) 提供独立运行证据。

本页直接链接源码，不假设每次部署都含 JavaDoc。 自动生成文档不应成为空链接；源码与指南是始终可用的参考入口。
