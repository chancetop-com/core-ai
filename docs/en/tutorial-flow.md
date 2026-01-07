# Tutorial: Flow Orchestration

This tutorial covers how to use Core-AI's Flow system to build complex workflows and execution flows.

## Table of Contents

1. [Flow Overview](#flow-overview)
2. [Flow Execution Engine](#flow-execution-engine)
3. [Node Types](#node-types)
4. [Edges and Connections](#edges-and-connections)
5. [Conditional Routing](#conditional-routing)
6. [State Management](#state-management)
7. [Real-World Examples](#real-world-examples)

## Flow Overview

### What is Flow?

Flow is Core-AI's workflow orchestration system that allows you to:
- Build complex multi-step processes
- Orchestrate multiple agents, tools, and LLM calls
- Implement conditional logic and branching
- Manage data flow between nodes

### Flow Architecture

```
┌─────────────────────────────────────┐
│              Flow                   │
│                                     │
│  ┌──────┐     ┌──────┐            │
│  │Start │────▶│Agent │            │
│  └──────┘     └──┬───┘            │
│                  │                 │
│            ┌─────▼─────┐          │
│            │ Condition │          │
│            └─┬───────┬─┘          │
│              │       │            │
│        ┌─────▼─┐ ┌──▼───┐       │
│        │Tool A │ │Tool B│       │
│        └───┬───┘ └──┬───┘       │
│            │        │            │
│            └────┬───┘            │
│                 │                │
│            ┌────▼────┐           │
│            │  End    │           │
│            └─────────┘           │
└─────────────────────────────────────┘
```

## Flow Execution Engine

### Directed Graph Execution Model

Flow is essentially a directed graph execution engine that executes workflows by recursively traversing nodes:

```
┌─────────────────────────────────────────────────────────────────┐
│                   Flow Execution Engine Core Flow                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Flow.run(nodeId, input, variables)                             │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  1. validate() - Validate Flow configuration             │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  2. Get current node                                     │   │
│  │     currentNode = getNodeById(nodeId)                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  3. Initialize node settings (SETTING edges)             │   │
│  │     settings = getNodeSettings(currentNode)              │   │
│  │     currentNode.initialize(settings, edges)              │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  4. Execute current node                                 │   │
│  │     if (isExecutableType(currentNode.type)) {            │   │
│  │         result = currentNode.execute(input, variables)   │   │
│  │     }                                                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  5. Get next nodes (CONNECTION edges)                    │   │
│  │     nextNodes = getNextNodes(currentNode)                │   │
│  │     if (nextNodes.isEmpty()) → FlowStatus.SUCCESS        │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  6. Conditional routing (when multiple branches)         │   │
│  │     if (nextNodes.size() > 1) {                          │   │
│  │         nextNode = selectNextNodeByEdgeValue(result)     │   │
│  │     }                                                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  7. Recursive execution of next node                     │   │
│  │     return execute(nextNode.id, result.text(), vars)     │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Edge Types and Roles

Flow edges are divided into two types:

| Edge Type | Purpose | Description |
|-----------|---------|-------------|
| `CONNECTION` | Data flow connection | Defines execution order between nodes, data flows from source to target |
| `SETTING` | Configuration injection | Injects settings from config node to target node, not part of execution flow |

```java
// CONNECTION edge: defines execution flow
public Map<FlowEdge<?>, FlowNode<?>> getNextNodes(FlowNode<?> node) {
    return edges.stream()
        .filter(edge -> edge.type == FlowEdgeType.CONNECTION)
        .filter(edge -> edge.getSourceNodeId().equals(node.id))
        .collect(Collectors.toMap(edge -> edge, edge -> getNodeById(edge.getTargetNodeId())));
}

// SETTING edge: get configuration
public List<FlowNode<?>> getNodeSettings(FlowNode<?> node) {
    return edges.stream()
        .filter(edge -> edge.type == FlowEdgeType.SETTING)
        .filter(edge -> edge.getSourceNodeId().equals(node.id))
        .map(edge -> getNodeById(edge.getTargetNodeId()))
        .toList();
}
```

### Conditional Routing Mechanism

When a node has multiple downstream nodes, Flow uses edge values for routing selection:

```java
// Select next node based on edge value
public FlowNode<?> selectNextNodeByEdgeValue(FlowNodeResult result,
                                              Map<FlowEdge<?>, FlowNode<?>> nextNodes) {
    for (var entry : nextNodes.entrySet()) {
        FlowEdge<?> edge = entry.getKey();
        // Select this branch when edge value matches execution result
        if (edge.getValue() != null &&
            edge.getValue().toString().equals(result.text())) {
            return entry.getValue();
        }
    }
    // Default to first node
    return nextNodes.values().iterator().next();
}
```

## Creating Basic Flows

### 1. Simple Linear Flow

```java
import ai.core.flow.Flow;
import ai.core.flow.Node;
import ai.core.flow.Edge;
import ai.core.flow.FlowOutput;

public class BasicFlowExample {

    public Flow createSimpleFlow(LLMProvider llmProvider) {
        // Create nodes
        Node startNode = Node.createStart();

        Node agent1 = Node.createAgent(
            Agent.builder()
                .name("analyzer")
                .description("Analyze input")
                .llmProvider(llmProvider)
                .build()
        );

        Node agent2 = Node.createAgent(
            Agent.builder()
                .name("processor")
                .description("Process data")
                .llmProvider(llmProvider)
                .build()
        );

        Node endNode = Node.createEnd();

        // Create edges (connections)
        List<Edge> edges = List.of(
            Edge.connection(startNode, agent1),
            Edge.connection(agent1, agent2),
            Edge.connection(agent2, endNode)
        );

        // Build flow
        return Flow.builder()
            .name("simple-flow")
            .description("Simple linear processing flow")
            .nodes(List.of(startNode, agent1, agent2, endNode))
            .edges(edges)
            .build();
    }

    public void executeFlow() {
        Flow flow = createSimpleFlow(llmProvider);

        // Execute flow
        FlowOutput output = flow.execute("Process this input data");

        // View results
        System.out.println("Flow status: " + output.getStatus());
        System.out.println("Final output: " + output.getFinalOutput());

        // View each node output
        for (NodeOutput nodeOutput : output.getNodeOutputs()) {
            System.out.println(nodeOutput.getNodeName() + ": " +
                             nodeOutput.getOutput());
        }
    }
}
```

### 2. Complex Flow Building

```java
public class ComplexFlowExample {

    public Flow createComplexFlow() {
        // Use fluent API to build complex flow
        return Flow.builder()
            .name("complex-workflow")
            .description("Complex business flow")

            // Add nodes
            .addStartNode("input")

            .addAgentNode("validator",
                Agent.builder()
                    .name("validator")
                    .systemPrompt("Validate input data integrity and format")
                    .build())

            .addToolNode("data-fetch",
                new DatabaseQueryTool())

            .addLLMNode("analyzer",
                llmProvider,
                "Analyze data and generate insights")

            .addRAGNode("knowledge-enhance",
                ragConfig)

            .addOperatorNode("filter",
                OperatorType.FILTER,
                data -> ((Map) data).get("score") > 0.8)

            .addEndNode("output")

            // Add connections
            .connect("input", "validator")
            .connect("validator", "data-fetch")
            .connect("data-fetch", "analyzer")
            .connect("analyzer", "knowledge-enhance")
            .connect("knowledge-enhance", "filter")
            .connect("filter", "output")

            // Configure execution parameters
            .maxExecutionTime(Duration.ofMinutes(5))
            .parallelExecution(true)
            .errorHandling(ErrorHandlingStrategy.CONTINUE_ON_ERROR)

            .build();
    }
}
```

## Node Types

### 1. Agent Node

```java
public Node createAgentNode() {
    Agent agent = Agent.builder()
        .name("decision-maker")
        .description("Make decisions")
        .llmProvider(llmProvider)
        .systemPrompt("""
            Analyze input and make decision.
            Output format:
            {
                "decision": "approve/reject/review",
                "reason": "decision reason"
            }
            """)
        .build();

    return Node.builder()
        .id("decision-node")
        .type(NodeType.AGENT)
        .agent(agent)
        .timeout(Duration.ofSeconds(30))
        .retryPolicy(RetryPolicy.exponentialBackoff(3))
        .build();
}
```

### 2. Tool Node

```java
public Node createToolNode() {
    ToolCall tool = new DataProcessingTool();

    return Node.builder()
        .id("process-data")
        .type(NodeType.TOOL)
        .tool(tool)
        .inputTransformer(input -> {
            // Transform input format
            return Map.of(
                "data", input,
                "options", Map.of("format", "json")
            );
        })
        .outputTransformer(output -> {
            // Transform output format
            return ((Map) output).get("result");
        })
        .build();
}
```

### 3. LLM Node

```java
public Node createLLMNode() {
    return Node.builder()
        .id("llm-processor")
        .type(NodeType.LLM)
        .llmProvider(llmProvider)
        .prompt("""
            Task: {{task}}
            Data: {{data}}

            Please analyze data and provide insights.
            """)
        .promptData(Map.of(
            "task", "Data Analysis"
        ))
        .temperature(0.7)
        .maxTokens(1000)
        .build();
}
```

### 4. RAG Node

```java
public Node createRAGNode() {
    return Node.builder()
        .id("knowledge-retrieval")
        .type(NodeType.RAG)
        .ragConfig(RAGConfig.builder()
            .vectorStore(vectorStore)
            .topK(5)
            .similarityThreshold(0.75)
            .queryRewriter(new SmartQueryRewriter())
            .build())
        .contextInjectionTemplate("""
            Answer based on the following knowledge:
            {{#documents}}
            - {{content}}
            {{/documents}}

            Question: {{query}}
            """)
        .build();
}
```

### 5. Operator Node

```java
// Filter node
public Node createFilterNode() {
    return Node.builder()
        .id("quality-filter")
        .type(NodeType.OPERATOR)
        .operatorType(OperatorType.FILTER)
        .predicate(data -> {
            Map<String, Object> map = (Map<String, Object>) data;
            double score = (double) map.get("qualityScore");
            return score >= 0.8;
        })
        .build();
}

// Transform node
public Node createTransformNode() {
    return Node.builder()
        .id("data-transformer")
        .type(NodeType.OPERATOR)
        .operatorType(OperatorType.TRANSFORM)
        .transformer(data -> {
            // Data transformation logic
            Map<String, Object> input = (Map<String, Object>) data;
            return Map.of(
                "processedData", processData(input),
                "metadata", extractMetadata(input),
                "timestamp", Instant.now()
            );
        })
        .build();
}
```

## Edges and Connections

### 1. Basic Connections

```java
public List<Edge> createBasicEdges() {
    Node nodeA = createNode("A");
    Node nodeB = createNode("B");
    Node nodeC = createNode("C");

    return List.of(
        // Simple connection
        Edge.connection(nodeA, nodeB),

        // Conditional connection
        Edge.conditional(nodeB, nodeC,
            output -> output.toString().contains("success")),

        // Connection with data transformation
        Edge.withTransform(nodeA, nodeC,
            data -> transformData(data))
    );
}
```

### 2. Conditional Edges

```java
public Flow createConditionalFlow() {
    Node decisionNode = Node.createAgent(/* ... */);
    Node approveNode = Node.createAgent(/* ... */);
    Node rejectNode = Node.createAgent(/* ... */);
    Node reviewNode = Node.createAgent(/* ... */);

    List<Edge> edges = List.of(
        // Conditional routing based on decision result
        Edge.conditional(decisionNode, approveNode,
            output -> {
                Map result = parseOutput(output);
                return "approve".equals(result.get("decision"));
            }),

        Edge.conditional(decisionNode, rejectNode,
            output -> {
                Map result = parseOutput(output);
                return "reject".equals(result.get("decision"));
            }),

        Edge.conditional(decisionNode, reviewNode,
            output -> {
                Map result = parseOutput(output);
                return "review".equals(result.get("decision"));
            })
    );

    return Flow.builder()
        .nodes(List.of(decisionNode, approveNode, rejectNode, reviewNode))
        .edges(edges)
        .build();
}
```

### 3. Setting Edges

```java
public List<Edge> createSettingEdges() {
    Node configNode = createConfigNode();
    Node processorNode = createProcessorNode();

    return List.of(
        // Setting edge: pass configuration, not data flow
        Edge.setting(configNode, processorNode, "config"),

        // Multiple settings
        Edge.settings(configNode, processorNode, Map.of(
            "threshold", 0.8,
            "maxRetries", 3,
            "timeout", 30
        ))
    );
}
```

## Conditional Routing

### 1. Simple Conditional Routing

```java
public Flow createRoutingFlow() {
    return Flow.builder()
        .name("routing-flow")

        // Router node
        .addRouterNode("router",
            input -> {
                Map data = (Map) input;
                String type = (String) data.get("type");

                return switch (type) {
                    case "A" -> "handler-a";
                    case "B" -> "handler-b";
                    case "C" -> "handler-c";
                    default -> "default-handler";
                };
            })

        // Handler nodes
        .addAgentNode("handler-a", createHandlerA())
        .addAgentNode("handler-b", createHandlerB())
        .addAgentNode("handler-c", createHandlerC())
        .addAgentNode("default-handler", createDefaultHandler())

        // Connections
        .connectRouter("router", List.of(
            "handler-a",
            "handler-b",
            "handler-c",
            "default-handler"
        ))

        .build();
}
```

### 2. Dynamic Routing

```java
public Flow createDynamicFlow() {
    return Flow.builder()
        .name("dynamic-flow")

        // LLM decides routing
        .addLLMRouterNode("llm-router",
            llmProvider,
            """
            Analyze input and decide the best processing path:
            Input: {{input}}

            Available paths:
            - fast-track: Simple quick processing
            - detailed-analysis: Detailed analysis
            - expert-review: Expert review

            Return the selected path name.
            """)

        // Dynamically create processing paths
        .addDynamicPaths(pathName -> {
            return switch (pathName) {
                case "fast-track" -> createFastTrackPath();
                case "detailed-analysis" -> createDetailedPath();
                case "expert-review" -> createExpertPath();
                default -> createDefaultPath();
            };
        })

        .build();
}
```

## State Management

### 1. Flow State

```java
public void manageFlowState() {
    Flow flow = createFlow();

    // Add state listener
    flow.addStateListener(new FlowStateListener() {
        @Override
        public void onStateChange(FlowState oldState, FlowState newState) {
            System.out.println("State change: " + oldState + " -> " + newState);

            // Take action based on state
            if (newState == FlowState.FAILED) {
                notifyAdministrator();
                saveFailureContext();
            }
        }

        @Override
        public void onNodeComplete(String nodeId, NodeOutput output) {
            System.out.println("Node completed: " + nodeId);
            updateProgress(nodeId);
        }
    });

    // Execute flow
    FlowOutput output = flow.execute(input);

    // Check final status
    switch (output.getStatus()) {
        case COMPLETED:
            System.out.println("Flow completed successfully");
            break;

        case FAILED:
            System.out.println("Flow failed: " + output.getError());
            handleFailure(output);
            break;

        case WAITING_FOR_USER_INPUT:
            System.out.println("Waiting for user input");
            String userInput = collectUserInput();
            output = flow.continueWithInput(userInput);
            break;
    }
}
```

### 2. Context Management

```java
public Flow createContextAwareFlow() {
    // Create shared context
    FlowContext context = new FlowContext();

    return Flow.builder()
        .name("context-flow")
        .context(context)

        // Nodes can read/write context
        .addNode(Node.builder()
            .id("collector")
            .type(NodeType.AGENT)
            .contextWriter((ctx, output) -> {
                ctx.set("collected_data", output);
                ctx.increment("processing_count");
            })
            .build())

        .addNode(Node.builder()
            .id("processor")
            .type(NodeType.AGENT)
            .contextReader(ctx -> {
                Object data = ctx.get("collected_data");
                int count = ctx.getInt("processing_count");
                return Map.of("data", data, "count", count);
            })
            .build())

        .build();
}
```

### 3. Checkpointing and Recovery

```java
public Flow createCheckpointedFlow() {
    return Flow.builder()
        .name("checkpointed-flow")

        // Enable checkpointing
        .enableCheckpointing(true)
        .checkpointStore(new FileCheckpointStore("/var/checkpoints"))
        .checkpointInterval(Duration.ofMinutes(1))

        // Add checkpoint nodes
        .addCheckpointNode("checkpoint-1")

        // Long-running node
        .addNode(createLongRunningNode())

        .addCheckpointNode("checkpoint-2")

        // Error recovery strategy
        .recoveryStrategy(new CheckpointRecoveryStrategy())

        .build();
}

public void executeWithRecovery() {
    Flow flow = createCheckpointedFlow();

    try {
        FlowOutput output = flow.execute(input);
    } catch (FlowExecutionException e) {
        // Resume from last checkpoint
        String checkpointId = e.getLastCheckpoint();
        FlowOutput output = flow.resumeFromCheckpoint(checkpointId);
    }
}
```

## Parallel and Async Execution

### 1. Parallel Branches

```java
public Flow createParallelFlow() {
    return Flow.builder()
        .name("parallel-flow")

        // Fork node
        .addForkNode("fork",
            input -> List.of(
                Map.of("branch", "A", "data", input),
                Map.of("branch", "B", "data", input),
                Map.of("branch", "C", "data", input)
            ))

        // Parallel branches
        .addParallelBranch("branch-a",
            List.of(createNodeA1(), createNodeA2()))

        .addParallelBranch("branch-b",
            List.of(createNodeB1(), createNodeB2()))

        .addParallelBranch("branch-c",
            List.of(createNodeC1(), createNodeC2()))

        // Join node
        .addJoinNode("join",
            results -> mergeResults(results))

        // Connections
        .connect("fork", List.of("branch-a", "branch-b", "branch-c"))
        .connect(List.of("branch-a", "branch-b", "branch-c"), "join")

        // Parallel execution configuration
        .parallelExecutor(Executors.newFixedThreadPool(10))
        .maxParallelism(3)

        .build();
}
```

## Real-World Examples

### Example 1: Loan Approval Flow

```java
public class LoanApprovalFlow {

    public Flow createLoanApprovalFlow() {
        return Flow.builder()
            .name("loan-approval")
            .description("Loan approval process")

            // 1. Application validation
            .addAgentNode("application-validator",
                Agent.builder()
                    .name("validator")
                    .systemPrompt("Validate loan application completeness")
                    .build())

            // 2. Credit scoring
            .addToolNode("credit-check", new CreditScoreTool())

            // 3. Risk assessment
            .addAgentNode("risk-assessment",
                Agent.builder()
                    .name("risk-analyzer")
                    .systemPrompt("""
                        Assess risk based on:
                        - Credit score
                        - Income status
                        - Loan amount
                        - Repayment term

                        Output risk level: low/medium/high
                        """)
                    .build())

            // 4. Decision routing
            .addRouterNode("decision-router",
                output -> {
                    Map assessment = (Map) output;
                    String risk = (String) assessment.get("riskLevel");
                    return switch (risk) {
                        case "low" -> "auto-approve";
                        case "medium" -> "manual-review";
                        case "high" -> "auto-reject";
                        default -> "manual-review";
                    };
                })

            // 5. Auto approve
            .addAgentNode("auto-approve", createApprovalAgent())

            // 6. Manual review
            .addNode(Node.builder()
                .id("manual-review")
                .type(NodeType.HUMAN_TASK)
                .humanTaskConfig(HumanTaskConfig.builder()
                    .assignee("loan-officer")
                    .timeout(Duration.ofHours(24))
                    .escalation("senior-officer")
                    .build())
                .build())

            // 7. Auto reject
            .addAgentNode("auto-reject", createRejectionAgent())

            // 8. Notification
            .addToolNode("notification", new NotificationTool())

            // Connect flow
            .connect("START", "application-validator")
            .connect("application-validator", "credit-check")
            .connect("credit-check", "risk-assessment")
            .connect("risk-assessment", "decision-router")
            .connectRouter("decision-router", Map.of(
                "auto-approve", "auto-approve",
                "manual-review", "manual-review",
                "auto-reject", "auto-reject"
            ))
            .connect(List.of("auto-approve", "manual-review", "auto-reject"),
                    "notification")
            .connect("notification", "END")

            .build();
    }
}
```

### Example 2: Content Generation Pipeline

```java
public class ContentGenerationPipeline {

    public Flow createContentPipeline() {
        return Flow.builder()
            .name("content-generation")

            // 1. Topic research (parallel)
            .addParallelBranch("research",
                List.of(
                    Node.createTool(new WebSearchTool()),
                    Node.createTool(new DatabaseQueryTool()),
                    Node.createRAG(ragConfig)
                ))

            // 2. Content planning
            .addAgentNode("content-planner",
                Agent.builder()
                    .name("planner")
                    .systemPrompt("""
                        Create content outline based on research:
                        1. Main points
                        2. Supporting arguments
                        3. Structure arrangement
                        """)
                    .build())

            // 3. Content generation (parallel sections)
            .addForkNode("content-fork",
                outline -> splitIntoSections(outline))

            .addParallelNodes(List.of(
                createSectionWriter("introduction"),
                createSectionWriter("body"),
                createSectionWriter("conclusion")
            ))

            // 4. Content merge
            .addJoinNode("content-merge",
                sections -> mergeSections(sections))

            // 5. Quality check
            .addAgentNode("quality-checker",
                Agent.builder()
                    .name("editor")
                    .systemPrompt("""
                        Check content quality:
                        - Grammar and spelling
                        - Logical coherence
                        - Factual accuracy
                        - Style consistency
                        """)
                    .build())

            // 6. Final formatting
            .addToolNode("formatter", new ContentFormatterTool())

            .build();
    }
}
```

## Best Practices

1. **Flow Design**
   - Keep flows simple and readable
   - Use parallel execution appropriately
   - Implement proper error handling
   - Add checkpoint support

2. **Performance Optimization**
   - Use batch processing to reduce overhead
   - Implement caching strategies
   - Optimize data transfer between nodes
   - Configure parallelism appropriately

3. **Reliability**
   - Implement retry mechanisms
   - Add timeout controls
   - Use dead letter queues
   - Save execution state

4. **Maintainability**
   - Modular flow design
   - Use meaningful node names
   - Add detailed logging
   - Implement version control

## Summary

Through this tutorial, you learned:

1. Flow's basic concepts and architecture
2. Various node types and their usage
3. Edge and connection configuration
4. Conditional routing and branching
5. State management and checkpointing
6. Real-world application examples

Core-AI's Flow system provides powerful and flexible workflow orchestration capabilities for building complex AI applications and business processes.

Next steps:
- Learn [Tool Calling](tutorial-tool-calling.md) to extend capabilities
- Explore [Memory Systems](tutorial-memory.md) for context management
- Read the [Architecture Deep Dive](tutorial-architecture.md) for core mechanisms
