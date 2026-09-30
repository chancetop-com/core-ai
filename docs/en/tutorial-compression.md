# Context Compression

<span class="legacy-anchor" id="tutorial-compression-context-management"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="overview"></span>
<span class="legacy-anchor" id="key-features"></span>
<span class="legacy-anchor" id="when-compression-triggers"></span>
<span class="legacy-anchor" id="how-compression-works"></span>
<span class="legacy-anchor" id="compression-flow"></span>
<span class="legacy-anchor" id="message-structure-after-compression"></span>
<span class="legacy-anchor" id="configuration"></span>
<span class="legacy-anchor" id="basic-usage"></span>
<span class="legacy-anchor" id="custom-configuration"></span>
<span class="legacy-anchor" id="configuration-parameters"></span>
<span class="legacy-anchor" id="disabling-compression"></span>
<span class="legacy-anchor" id="compression-algorithm"></span>
<span class="legacy-anchor" id="algorithm-design-principles"></span>
<span class="legacy-anchor" id="core-algorithm-flow"></span>
<span class="legacy-anchor" id="step-1-check-trigger-condition"></span>
<span class="legacy-anchor" id="step-2-message-splitting-strategy"></span>
<span class="legacy-anchor" id="step-3-conversation-chain-protection"></span>
<span class="legacy-anchor" id="step-4-summary-generation"></span>
<span class="legacy-anchor" id="step-5-result-assembly"></span>
<span class="legacy-anchor" id="best-practices"></span>
<span class="legacy-anchor" id="_1-choose-appropriate-threshold"></span>
<span class="legacy-anchor" id="_2-adjust-keep-recent-turns-based-on-use-case"></span>
<span class="legacy-anchor" id="_3-monitor-token-usage"></span>
<span class="legacy-anchor" id="_4-combine-with-long-term-memory"></span>
<span class="legacy-anchor" id="_5-handle-edge-cases"></span>
<span class="legacy-anchor" id="implementation-details"></span>
<span class="legacy-anchor" id="core-classes"></span>
<span class="legacy-anchor" id="lifecycle-integration"></span>
<span class="legacy-anchor" id="token-counting"></span>
<span class="legacy-anchor" id="summary"></span>

Compression manages a single Agent's long conversation context separately from cross-session memory. Summarization can add model calls and cost.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Switch and parameters {#开关与参数}

The minimal Mock demo uses `.compression(false)`. Current `CompressionConfig` is a record:

```java
var compression = new CompressionConfig(
    true, 0.75, 4, 2000, null, null);
var agent = Agent.builder()
    .name("context-agent")
    .llmProvider(provider)
    .model(model)
    .compression(compression)
    .build();
```
Arguments are `enabled`, `triggerThreshold`, `keepRecentTurns`, `keepMinTokens`, `contextWindowTokens` and `summaryModel`. Values 0.75, 4 and 2000 illustrate configuration, not recommended defaults. Null retains a default. Configure the provider before passing compression settings.

## Validate compression {#验证压缩}

Fix the input and context limit. Inspect retained recent turns, tool-call chains, summary content and Usage before and after compression. Check lost constraints or tool results, then evaluate task completion and additional model calls.

Live summarization was not executed. Consult [Compression source](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/context/Compression.java) for current defaults and behavior.

[Basic Agent](tutorial-basic-agent.md) · [Memory](tutorial-memory.md) · [Context overview (Chinese)](/cn/manual/#_27-记忆-压缩-rag-与-flow)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-compression.md). They may use older APIs; use the source-checked examples above for current development.
