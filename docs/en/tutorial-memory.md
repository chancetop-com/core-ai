# Memory and User Context

<span class="legacy-anchor" id="tutorial-memory-systems"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="overview"></span>
<span class="legacy-anchor" id="memory-system"></span>
<span class="legacy-anchor" id="architecture"></span>
<span class="legacy-anchor" id="core-concepts"></span>
<span class="legacy-anchor" id="memory-attributes"></span>
<span class="legacy-anchor" id="setting-up-memory"></span>
<span class="legacy-anchor" id="memory-extraction"></span>
<span class="legacy-anchor" id="memory-retrieval-principles"></span>
<span class="legacy-anchor" id="langmem-pattern"></span>
<span class="legacy-anchor" id="memory-recall"></span>
<span class="legacy-anchor" id="userid-passing-mechanism"></span>
<span class="legacy-anchor" id="storage-interfaces"></span>
<span class="legacy-anchor" id="custom-storage-implementation"></span>
<span class="legacy-anchor" id="agent-integration"></span>
<span class="legacy-anchor" id="executioncontext"></span>
<span class="legacy-anchor" id="using-unifiedmemory-configuration"></span>
<span class="legacy-anchor" id="complete-example"></span>
<span class="legacy-anchor" id="best-practices"></span>
<span class="legacy-anchor" id="_1-proper-use-of-executioncontext"></span>
<span class="legacy-anchor" id="_2-production-storage-setup"></span>
<span class="legacy-anchor" id="_3-extraction-timing"></span>
<span class="legacy-anchor" id="_4-service-layer-encapsulation"></span>
<span class="legacy-anchor" id="summary"></span>

Memory recalls user-related information across turns or sessions. Conversation messages, CLI session files and MemoryStore are different concepts.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Configure Memory {#配置-memory}

Current entry points are `ai.core.memory.Memory`, `MemoryStore` and `MemoryConfig`. This configuration uses an in-memory store, which does not persist across processes:

```java
var memory = Memory.builder()
    .llmProvider(provider)
    .memoryStore(new InMemoryStore())
    .build();
var memoryConfig = MemoryConfig.builder()
    .autoRecall(true)
    .maxRecallRecords(5)
    .build();
var agent = Agent.builder()
    .name("memory-agent")
    .llmProvider(provider)
    .model(model)
    .unifiedMemory(memory, memoryConfig)
    .build();
```
Retrieval and extraction can require embeddings or model calls. This example is compile-only. `maxRecallRecords` is clamped to 1–20; defaults enable automatic recall and allow five records.

## Identity and execution {#用户隔离与运行}

Run with an `ExecutionContext` containing the correct `userId` and `sessionId`. Store methods are scoped by user; the application must obtain that identity from authentication and avoid reusing another user's context.

## Validation steps {#验证步骤}

1. Save known fixtures and check retrieval for the same user.
2. Check isolation with a second user.
3. Verify retrieval before enabling automatic recall and extraction.
4. Test restart, deletion, access control and sensitive content with a persistent store.

Live extraction, embeddings and persistent backends were not tested. Consult [Memory source](https://github.com/chancetop-com/core-ai/tree/master/core-ai/src/main/java/ai/core/memory).

[Basic Agent](tutorial-basic-agent.md) · [Compression](tutorial-compression.md) · [RAG](tutorial-rag.md)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-memory.md). They may use older APIs; use the source-checked examples above for current development.
