# Function Tools and MCP

<span class="legacy-anchor" id="tutorial-tool-calling"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="tool-calling-overview"></span>
<span class="legacy-anchor" id="what-is-tool-calling"></span>
<span class="legacy-anchor" id="tool-execution-mechanism"></span>
<span class="legacy-anchor" id="core-ai-tool-architecture"></span>
<span class="legacy-anchor" id="creating-custom-tools"></span>
<span class="legacy-anchor" id="_1-basic-tool-implementation"></span>
<span class="legacy-anchor" id="_2-complex-tool-implementation"></span>
<span class="legacy-anchor" id="_3-async-tools"></span>
<span class="legacy-anchor" id="json-schema-definition"></span>
<span class="legacy-anchor" id="json-schema-auto-generation-principles"></span>
<span class="legacy-anchor" id="_1-auto-schema-generation"></span>
<span class="legacy-anchor" id="_2-complex-schema-definition"></span>
<span class="legacy-anchor" id="mcp-protocol-integration"></span>
<span class="legacy-anchor" id="_1-mcp-server"></span>
<span class="legacy-anchor" id="_2-mcp-client"></span>
<span class="legacy-anchor" id="_3-mcp-tool-adapter"></span>
<span class="legacy-anchor" id="built-in-tools"></span>
<span class="legacy-anchor" id="_1-using-built-in-tools"></span>
<span class="legacy-anchor" id="_2-extending-built-in-tools"></span>
<span class="legacy-anchor" id="tool-composition-and-orchestration"></span>
<span class="legacy-anchor" id="_1-tool-chains"></span>
<span class="legacy-anchor" id="_2-conditional-tool-execution"></span>
<span class="legacy-anchor" id="_3-parallel-tool-execution"></span>
<span class="legacy-anchor" id="real-world-examples"></span>
<span class="legacy-anchor" id="example-1-data-analysis-assistant"></span>
<span class="legacy-anchor" id="example-2-devops-automation-assistant"></span>
<span class="legacy-anchor" id="example-3-api-integration-assistant"></span>
<span class="legacy-anchor" id="error-handling-and-retries"></span>
<span class="legacy-anchor" id="_1-tool-error-handling"></span>
<span class="legacy-anchor" id="_2-tool-fallback-strategy"></span>
<span class="legacy-anchor" id="tool-monitoring"></span>
<span class="legacy-anchor" id="_1-performance-monitoring"></span>
<span class="legacy-anchor" id="best-practices"></span>
<span class="legacy-anchor" id="summary"></span>

Tools expose explicit application capabilities to an Agent. Start with a side-effect-free Echo function, inspect its schema, then add model-driven selection. Tool choice depends on the input, prompt and provider.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Define a Java tool {#定义-java-函数工具}

Annotations are in `ai.core.api.tool.function`; conversion is `ai.core.tool.function.Functions`. Supply method and parameter `name` and `description`. Parameters default to `required=true`.

```java
public static class EchoTools {
    @CoreAiMethod(name = "echo", description = "Return the input text")
    public String echo(
        @CoreAiParameter(name = "text", description = "Input text")
        String text) {
        return text;
    }
}
```
## Attach it to an Agent {#绑定到-agent}

```java
var agent = Agent.builder()
    .name("echo-agent")
    .llmProvider(provider)
    .model(model)
    .toolCalls(Functions.from(new EchoTools()))
    .build();
```
Calling `new EchoTools().echo("hello")` should return `hello`; this checks the function, not model selection or approval.

## Schema and authorization {#schema-与权限}

`Functions.from(...)` reads annotated public methods and creates schemas. Define types, null handling, defaults and failures. The default `needAuth=false` does not establish authorization to an external resource. Writes need identity checks, approvals, idempotency and failure handling.

## Two MCP entry points {#mcp-的两个入口}

Framework MCP clients require an initialized manager and actual builder contracts such as `mcpServers(...)`. CLI workspace `.core-ai/MCP.json` loads local stdio servers. `core-ai-cli mcp ...` discovers and calls Server Hub resources instead.

Follow [the Hub flow](cli.md#连接-server): search, describe, then call the returned path with an arguments file. See [local MCP and hooks (Chinese)](/cn/manual/#_19-本地-mcp-skills-与-hooks) for paths and prerequisites.

## Debugging order {#调试顺序}

1. Check the ordinary Java function.
2. Inspect the generated schema.
3. Check selection and result handling with a test provider.
4. Validate identity, failures and sanitized logs when integrating an external system.

[Basic Agent](tutorial-basic-agent.md) · [Skills](tutorial-skills.md) · [Tool source](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/tool)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-tool-calling.md). They may use older APIs; use the source-checked examples above for current development.
