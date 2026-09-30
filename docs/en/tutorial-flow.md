# Framework Flow and Server Workflows

<span class="legacy-anchor" id="tutorial-flow-orchestration"></span>
<span class="legacy-anchor" id="table-of-contents"></span>
<span class="legacy-anchor" id="flow-overview"></span>
<span class="legacy-anchor" id="what-is-flow"></span>
<span class="legacy-anchor" id="flow-architecture"></span>
<span class="legacy-anchor" id="flow-execution-engine"></span>
<span class="legacy-anchor" id="directed-graph-execution-model"></span>
<span class="legacy-anchor" id="edge-types-and-roles"></span>
<span class="legacy-anchor" id="conditional-routing-mechanism"></span>
<span class="legacy-anchor" id="creating-basic-flows"></span>
<span class="legacy-anchor" id="_1-simple-linear-flow"></span>
<span class="legacy-anchor" id="_2-complex-flow-building"></span>
<span class="legacy-anchor" id="node-types"></span>
<span class="legacy-anchor" id="_1-agent-node"></span>
<span class="legacy-anchor" id="_2-tool-node"></span>
<span class="legacy-anchor" id="_3-llm-node"></span>
<span class="legacy-anchor" id="_4-rag-node"></span>
<span class="legacy-anchor" id="_5-operator-node"></span>
<span class="legacy-anchor" id="edges-and-connections"></span>
<span class="legacy-anchor" id="_1-basic-connections"></span>
<span class="legacy-anchor" id="_2-conditional-edges"></span>
<span class="legacy-anchor" id="_3-setting-edges"></span>
<span class="legacy-anchor" id="conditional-routing"></span>
<span class="legacy-anchor" id="_1-simple-conditional-routing"></span>
<span class="legacy-anchor" id="_2-dynamic-routing"></span>
<span class="legacy-anchor" id="state-management"></span>
<span class="legacy-anchor" id="_1-flow-state"></span>
<span class="legacy-anchor" id="_2-context-management"></span>
<span class="legacy-anchor" id="_3-checkpointing-and-recovery"></span>
<span class="legacy-anchor" id="parallel-and-async-execution"></span>
<span class="legacy-anchor" id="_1-parallel-branches"></span>
<span class="legacy-anchor" id="real-world-examples"></span>
<span class="legacy-anchor" id="example-1-loan-approval-flow"></span>
<span class="legacy-anchor" id="example-2-content-generation-pipeline"></span>
<span class="legacy-anchor" id="best-practices"></span>
<span class="legacy-anchor" id="summary"></span>

Distinguish Java `ai.core.flow.Flow` from the Server Web workflow editor. Definitions, node models, publishing and execution lifecycles differ. UI-exported JSON is not a Java Flow argument.

::: info Validation
These fragments come from the [complete compile-only sample](https://github.com/chancetop-com/core-ai/blob/master/docs/examples/GuideApiExamples.java). Types were checked against current source; live models and external stores were not executed. The caller supplies parameters such as `provider` and `model`. Runtime evidence is in the [offline Agent](framework.md#离线-agent).
:::
## Nodes, edges and construction {#节点、边与构造}

Java Flow uses `FlowNode`, `FlowEdge` and `ConnectionEdge`. Its builder accepts `.nodes(...)` and `.edges(...)`, with providers, vector stores, tracer and listeners as needed. Each implementation defines its `check` and `init` contracts.

A node class name does not establish completed behavior. For example, current `EmptyFlowNode.execute(...)` returns null; inspect the selected node and its constraints first.

## Validate and run {#检查与运行}

Assuming the caller already constructed a Flow and supplies an existing start ID, input and context:

```java
flow.validate();
String result = flow.run(startNodeId, input, context);
```
The entry point is `run(startNodeId, input, context)` or the variables overload, returning String. It does not use the old `FlowOutput` / `flow.execute(input)` example. Validation does not establish external connectivity.

## Minimal verification path {#最小验证顺序}

1. Check node IDs, connection edges and settings edges.
2. Verify each node's input and result.
3. Execute a minimal path and inspect node/output listeners.
4. Add branches, persistence and user-input handling individually.

Flow and Server workflows were not executed during this update. Test recovery, concurrency and error propagation against selected implementations rather than assuming design proposals are shipped behavior.

## Server workflow path {#server-的操作路径}

Start with START → one node → END in the Web editor and check draft/published lifecycles. See [workflow steps (Chinese)](/cn/manual/#_5-工具-技能-数据集与工作流).

[Flow source](https://github.com/chancetop-com/core-ai/blob/master/core-ai/src/main/java/ai/core/flow/Flow.java) · [Architecture](tutorial-architecture.md) · [Server](server.md)

## Historical long-form material

Earlier scenario analysis and extended examples are retained in the [pinned version](https://github.com/chancetop-com/core-ai/blob/cf6479d7eca83c78cd4f80765601db3f1917241b/docs/en/tutorial-flow.md). They may use older APIs; use the source-checked examples above for current development.
