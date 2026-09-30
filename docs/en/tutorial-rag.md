# Retrieval-Augmented Generation

RAG adds retrieved document chunks to model input. It requires an index, embeddings, a VectorStore and a provider; a builder setting alone does not create infrastructure.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Configure RagConfig {#接入-ragconfig}

Use `.ragConfig(...)`, rather than the old `.enableRAG(...)` example. The caller supplies a prepared `vectorStore`:

```java
var rag = RagConfig.builder()
    .useRag(true)
    .topK(5)
    .threshold(0.0)
    .vectorStore(vectorStore)
    .llmProvider(provider)
    .enableQueryRewriting(false)
    .build();
var agent = Agent.builder()
    .name("rag-agent")
    .llmProvider(provider)
    .model(model)
    .ragConfig(rag)
    .build();
```
Query rewriting is disabled here to isolate retrieval during setup; this is not a universal recommendation. Rewriting, embedding and reranking requests depend on provider and configuration. No vector store or live model was contacted.

## Check in order {#按顺序检查}

1. Verify document provenance, parsing and chunks.
2. Match embedding dimensions to the index schema.
3. Test VectorStore retrieval directly and inspect chunks and scores.
4. Inspect the context injected into the Agent and its answer.
5. Compare topK, thresholds, rewriting and reranking on a fixed test set.

Milvus and HNSWLib backends require their actual runtime dependencies. Source: [RagConfig](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/rag/RagConfig.java), [RagPipeline](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/agent/RagPipeline.java).

## Troubleshooting {#常见问题}

For zero results, check the index, identity, dimensions and filters. For incorrect answers, inspect chunks, injected context and prompts. Retrieval does not itself authorize document access; enforce that at the resource boundary.

[Function Tools](tutorial-tool-calling.md) · [Memory](tutorial-memory.md) · [API Reference](/en/api)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-rag.md). They may use older APIs; use the source-checked examples above for current development.
