# Basic Agent

<span class="legacy-anchor" id="tutorial-building-ai-agents"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="agent-basics"></span>
<span class="legacy-anchor" id="what-is-an-agent"></span>
<span class="legacy-anchor" id="core-architecture-design"></span>
<span class="legacy-anchor" id="state-transition-mechanism"></span>
<span class="legacy-anchor" id="agent-core-components"></span>
<span class="legacy-anchor" id="agent-execution-flow"></span>
<span class="legacy-anchor" id="creating-agents"></span>
<span class="legacy-anchor" id="_1-simple-agent"></span>
<span class="legacy-anchor" id="_2-configured-agent"></span>
<span class="legacy-anchor" id="_3-streaming-agent"></span>
<span class="legacy-anchor" id="system-prompts-and-templates"></span>
<span class="legacy-anchor" id="_1-static-system-prompt"></span>
<span class="legacy-anchor" id="_2-dynamic-templates-mustache"></span>
<span class="legacy-anchor" id="_3-prompt-template-for-user-query"></span>
<span class="legacy-anchor" id="_4-langfuse-prompt-integration"></span>
<span class="legacy-anchor" id="reflection-mechanism"></span>
<span class="legacy-anchor" id="_1-basic-reflection"></span>
<span class="legacy-anchor" id="_2-custom-reflection-configuration"></span>
<span class="legacy-anchor" id="_3-reflection-with-listener"></span>
<span class="legacy-anchor" id="_4-simple-reflection-with-evaluation-criteria"></span>
<span class="legacy-anchor" id="state-management"></span>
<span class="legacy-anchor" id="_1-agent-status"></span>
<span class="legacy-anchor" id="_2-persistence-and-recovery"></span>
<span class="legacy-anchor" id="_3-reset-agent-state"></span>
<span class="legacy-anchor" id="_4-token-usage-tracking"></span>
<span class="legacy-anchor" id="best-practices"></span>
<span class="legacy-anchor" id="_1-single-responsibility-principle"></span>
<span class="legacy-anchor" id="_2-error-handling"></span>
<span class="legacy-anchor" id="_3-proper-context-usage"></span>
<span class="legacy-anchor" id="_4-observability-with-tracing"></span>
<span class="legacy-anchor" id="_5-memory-integration"></span>
<span class="legacy-anchor" id="summary"></span>

Read [framework setup](framework.md) first and confirm Java 25 and dependencies. Start with a Mock provider for a reproducible input, result and status.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Create an Agent {#创建基本代理}

`Agent.builder()` returns `AgentBuilder`; `run` returns `String`. The caller chooses a model supported by the provider and creates user/session context:

```java
var agent = Agent.builder()
    .name("guide-agent")
    .llmProvider(provider)
    .model(model)
    .systemPrompt("Answer clearly.")
    .compression(false)
    .maxTurn(3)
    .build();
var context = ExecutionContext.builder()
    .userId("demo-user")
    .sessionId("demo-session")
    .build();
String result = agent.run("Hello", context);
```
Check `agent.getNodeStatus()`. A completed run should be `COMPLETED`; input or asynchronous tasks can pause execution. A returned string alone does not prove completion.

## Prompts and configuration {#系统提示和模板}

`.systemPrompt(...)`, `.model(...)`, `.temperature(...)` and `.maxTurn(...)` configure the prompt, request and Agent turn limit. A turn limit is not a process timeout. Provide application variables through `ExecutionContext` or `run(query, variables)` and manage identity separately from prompts.

## Streaming {#流式响应}

Import `ai.core.llm.streaming.StreamingCallback`. Use `onChunk(String)` and no-argument `onComplete()`:

```java
var agent = Agent.builder()
    .name("streaming-agent")
    .llmProvider(provider)
    .model(model)
    .streaming(true)
    .streamingCallback(new StreamingCallback() {
        @Override public void onChunk(String chunk) {
            System.out.print(chunk);
        }
        @Override public void onComplete() {
            System.out.println();
        }
        @Override public void onError(Throwable error) {
            System.err.println(error.getMessage());
        }
    }).build();
```
Then call `agent.run(query, context)`. Keep callbacks responsive and handle sensitive output deliberately. This fragment was compile-checked; live streaming was not executed.

## Reflection {#反思机制}

Reflection uses `reflectionConfig(...)`, evaluation settings and provider capabilities and may add model calls. Begin with a minimal Agent, then consult [AgentBuilder source](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/AgentBuilder.java) for the options you need.

## Execution status {#状态管理}

The application owns `userId`, `sessionId`, persistence and recovery. States include `INITED`, `RUNNING`, `WAITING_FOR_USER_INPUT`, `WAITING_FOR_ASYNC_TASK`, `COMPLETED` and `FAILED`. Check waiting work before retrying a task.

## Next {#下一步}

[Function Tools](tutorial-tool-calling.md) · [Memory](tutorial-memory.md) · [Compression](tutorial-compression.md) · [API Reference](/en/api)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-basic-agent.md). They may use older APIs; use the source-checked examples above for current development.
