# 教程：流程编排（Flow Orchestration）

本教程将介绍如何使用 Core-AI 的 Flow 系统构建复杂的工作流和执行流程。

## 目录

1. [流程编排概述](#流程编排概述)
2. [节点类型](#节点类型)
3. [边和连接](#边和连接)
4. [条件路由](#条件路由)
5. [状态管理](#状态管理)
6. [实战案例](#实战案例)

## 流程编排概述

### 什么是 Flow？

Flow 是 Core-AI 中的工作流编排系统，允许您：
- 构建复杂的多步骤流程
- 编排多个代理、工具和 LLM 调用
- 实现条件逻辑和分支
- 管理数据在节点间的流动

### Flow 架构

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

## 创建基本流程

### 1. 简单线性流程

```java
import ai.core.flow.Flow;
import ai.core.flow.Node;
import ai.core.flow.Edge;
import ai.core.flow.FlowOutput;

public class BasicFlowExample {

    public Flow createSimpleFlow(LLMProvider llmProvider) {
        // 创建节点
        Node startNode = Node.createStart();

        Node agent1 = Node.createAgent(
            Agent.builder()
                .name("analyzer")
                .description("分析输入")
                .llmProvider(llmProvider)
                .build()
        );

        Node agent2 = Node.createAgent(
            Agent.builder()
                .name("processor")
                .description("处理数据")
                .llmProvider(llmProvider)
                .build()
        );

        Node endNode = Node.createEnd();

        // 创建边（连接）
        List<Edge> edges = List.of(
            Edge.connection(startNode, agent1),
            Edge.connection(agent1, agent2),
            Edge.connection(agent2, endNode)
        );

        // 构建流程
        return Flow.builder()
            .name("simple-flow")
            .description("简单的线性处理流程")
            .nodes(List.of(startNode, agent1, agent2, endNode))
            .edges(edges)
            .build();
    }

    public void executeFlow() {
        Flow flow = createSimpleFlow(llmProvider);

        // 执行流程
        FlowOutput output = flow.execute("处理这个输入数据");

        // 查看结果
        System.out.println("流程状态: " + output.getStatus());
        System.out.println("最终输出: " + output.getFinalOutput());

        // 查看各节点输出
        for (NodeOutput nodeOutput : output.getNodeOutputs()) {
            System.out.println(nodeOutput.getNodeName() + ": " +
                             nodeOutput.getOutput());
        }
    }
}
```

### 2. 复杂流程构建

```java
public class ComplexFlowExample {

    public Flow createComplexFlow() {
        // 使用流畅的 API 构建复杂流程
        return Flow.builder()
            .name("complex-workflow")
            .description("复杂的业务流程")

            // 添加节点
            .addStartNode("input")

            .addAgentNode("validator",
                Agent.builder()
                    .name("validator")
                    .systemPrompt("验证输入数据的完整性和格式")
                    .build())

            .addToolNode("data-fetch",
                new DatabaseQueryTool())

            .addLLMNode("analyzer",
                llmProvider,
                "分析数据并生成洞察")

            .addRAGNode("knowledge-enhance",
                ragConfig)

            .addOperatorNode("filter",
                OperatorType.FILTER,
                data -> ((Map) data).get("score") > 0.8)

            .addEndNode("output")

            // 添加连接
            .connect("input", "validator")
            .connect("validator", "data-fetch")
            .connect("data-fetch", "analyzer")
            .connect("analyzer", "knowledge-enhance")
            .connect("knowledge-enhance", "filter")
            .connect("filter", "output")

            // 配置执行参数
            .maxExecutionTime(Duration.ofMinutes(5))
            .parallelExecution(true)
            .errorHandling(ErrorHandlingStrategy.CONTINUE_ON_ERROR)

            .build();
    }
}
```

## 节点类型

### 1. Agent 节点

```java
public class AgentNodeExample {

    public Node createAgentNode() {
        Agent agent = Agent.builder()
            .name("decision-maker")
            .description("做出决策")
            .llmProvider(llmProvider)
            .systemPrompt("""
                分析输入并做出决策。
                输出格式：
                {
                    "decision": "approve/reject/review",
                    "reason": "决策理由"
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
}
```

### 2. Tool 节点

```java
public class ToolNodeExample {

    public Node createToolNode() {
        ToolCall tool = new DataProcessingTool();

        return Node.builder()
            .id("process-data")
            .type(NodeType.TOOL)
            .tool(tool)
            .inputTransformer(input -> {
                // 转换输入格式
                return Map.of(
                    "data", input,
                    "options", Map.of("format", "json")
                );
            })
            .outputTransformer(output -> {
                // 转换输出格式
                return ((Map) output).get("result");
            })
            .build();
    }
}
```

### 3. LLM 节点

```java
public class LLMNodeExample {

    public Node createLLMNode() {
        return Node.builder()
            .id("llm-processor")
            .type(NodeType.LLM)
            .llmProvider(llmProvider)
            .prompt("""
                任务：{{task}}
                数据：{{data}}

                请分析数据并提供见解。
                """)
            .promptData(Map.of(
                "task", "数据分析"
            ))
            .temperature(0.7)
            .maxTokens(1000)
            .build();
    }
}
```

### 4. RAG 节点

```java
public class RAGNodeExample {

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
                基于以下知识回答：
                {{#documents}}
                - {{content}}
                {{/documents}}

                问题：{{query}}
                """)
            .build();
    }
}
```

### 5. Operator 节点

```java
public class OperatorNodeExample {

    // 过滤节点
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

    // 转换节点
    public Node createTransformNode() {
        return Node.builder()
            .id("data-transformer")
            .type(NodeType.OPERATOR)
            .operatorType(OperatorType.TRANSFORM)
            .transformer(data -> {
                // 数据转换逻辑
                Map<String, Object> input = (Map<String, Object>) data;
                return Map.of(
                    "processedData", processData(input),
                    "metadata", extractMetadata(input),
                    "timestamp", Instant.now()
                );
            })
            .build();
    }

    // 聚合节点
    public Node createAggregateNode() {
        return Node.builder()
            .id("result-aggregator")
            .type(NodeType.OPERATOR)
            .operatorType(OperatorType.AGGREGATE)
            .aggregator(new ResultAggregator())
            .aggregationWindow(Duration.ofSeconds(10))
            .build();
    }
}
```

## 边和连接

### 1. 基本连接

```java
public class EdgeExample {

    public List<Edge> createBasicEdges() {
        Node nodeA = createNode("A");
        Node nodeB = createNode("B");
        Node nodeC = createNode("C");

        return List.of(
            // 简单连接
            Edge.connection(nodeA, nodeB),

            // 带条件的连接
            Edge.conditional(nodeB, nodeC,
                output -> output.toString().contains("success")),

            // 带数据转换的连接
            Edge.withTransform(nodeA, nodeC,
                data -> transformData(data))
        );
    }
}
```

### 2. 条件边

```java
public class ConditionalEdgeExample {

    public Flow createConditionalFlow() {
        Node decisionNode = Node.createAgent(/* ... */);
        Node approveNode = Node.createAgent(/* ... */);
        Node rejectNode = Node.createAgent(/* ... */);
        Node reviewNode = Node.createAgent(/* ... */);

        List<Edge> edges = List.of(
            // 基于决策结果的条件路由
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
}
```

### 3. 设置边

```java
public class SettingEdgeExample {

    public List<Edge> createSettingEdges() {
        Node configNode = createConfigNode();
        Node processorNode = createProcessorNode();

        return List.of(
            // 设置边：传递配置而不是数据流
            Edge.setting(configNode, processorNode, "config"),

            // 多个设置
            Edge.settings(configNode, processorNode, Map.of(
                "threshold", 0.8,
                "maxRetries", 3,
                "timeout", 30
            ))
        );
    }
}
```

## 条件路由

### 1. 简单条件路由

```java
public class SimpleRoutingExample {

    public Flow createRoutingFlow() {
        return Flow.builder()
            .name("routing-flow")

            // 路由节点
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

            // 处理节点
            .addAgentNode("handler-a", createHandlerA())
            .addAgentNode("handler-b", createHandlerB())
            .addAgentNode("handler-c", createHandlerC())
            .addAgentNode("default-handler", createDefaultHandler())

            // 连接
            .connectRouter("router", List.of(
                "handler-a",
                "handler-b",
                "handler-c",
                "default-handler"
            ))

            .build();
    }
}
```

### 2. 复杂条件路由

```java
public class ComplexRoutingExample {

    public Flow createComplexRoutingFlow() {
        // 创建智能路由器
        Node intelligentRouter = Node.builder()
            .id("intelligent-router")
            .type(NodeType.ROUTER)
            .routingStrategy(new IntelligentRoutingStrategy())
            .build();

        return Flow.builder()
            .name("complex-routing")
            .addNode(intelligentRouter)
            .addMultipleRoutes(intelligentRouter, createRoutes())
            .build();
    }

    private class IntelligentRoutingStrategy implements RoutingStrategy {
        @Override
        public String determineRoute(Object input, FlowContext context) {
            // 基于多个因素决定路由
            Map data = (Map) input;

            // 检查优先级
            int priority = (int) data.getOrDefault("priority", 0);
            if (priority > 8) {
                return "urgent-handler";
            }

            // 检查数据大小
            int dataSize = calculateDataSize(data);
            if (dataSize > 1000000) {
                return "batch-processor";
            }

            // 基于历史性能选择
            String bestPerformer = context.getBestPerformingNode();
            if (bestPerformer != null) {
                return bestPerformer;
            }

            return "default-processor";
        }
    }
}
```

### 3. 动态路由

```java
public class DynamicRoutingExample {

    public Flow createDynamicFlow() {
        return Flow.builder()
            .name("dynamic-flow")

            // LLM 决定路由
            .addLLMRouterNode("llm-router",
                llmProvider,
                """
                分析输入并决定最佳处理路径：
                输入：{{input}}

                可用路径：
                - fast-track: 简单快速处理
                - detailed-analysis: 详细分析
                - expert-review: 专家审核

                返回选择的路径名称。
                """)

            // 动态创建处理路径
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
}
```

## 状态管理

### 1. 流程状态

```java
public class FlowStateExample {

    public void manageFlowState() {
        Flow flow = createFlow();

        // 添加状态监听器
        flow.addStateListener(new FlowStateListener() {
            @Override
            public void onStateChange(FlowState oldState, FlowState newState) {
                System.out.println("状态变化: " + oldState + " -> " + newState);

                // 基于状态执行操作
                if (newState == FlowState.FAILED) {
                    notifyAdministrator();
                    saveFailureContext();
                }
            }

            @Override
            public void onNodeComplete(String nodeId, NodeOutput output) {
                System.out.println("节点完成: " + nodeId);
                updateProgress(nodeId);
            }
        });

        // 执行流程
        FlowOutput output = flow.execute(input);

        // 检查最终状态
        switch (output.getStatus()) {
            case COMPLETED:
                System.out.println("流程成功完成");
                break;

            case FAILED:
                System.out.println("流程失败: " + output.getError());
                handleFailure(output);
                break;

            case WAITING_FOR_USER_INPUT:
                System.out.println("等待用户输入");
                String userInput = collectUserInput();
                output = flow.continueWithInput(userInput);
                break;
        }
    }
}
```

### 2. 上下文管理

```java
public class FlowContextExample {

    public Flow createContextAwareFlow() {
        // 创建共享上下文
        FlowContext context = new FlowContext();

        return Flow.builder()
            .name("context-flow")
            .context(context)

            // 节点可以读写上下文
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
}
```

### 3. 检查点和恢复

```java
public class CheckpointingExample {

    public Flow createCheckpointedFlow() {
        return Flow.builder()
            .name("checkpointed-flow")

            // 启用检查点
            .enableCheckpointing(true)
            .checkpointStore(new FileCheckpointStore("/var/checkpoints"))
            .checkpointInterval(Duration.ofMinutes(1))

            // 添加检查点节点
            .addCheckpointNode("checkpoint-1")

            // 长时间运行的节点
            .addNode(createLongRunningNode())

            .addCheckpointNode("checkpoint-2")

            // 错误恢复策略
            .recoveryStrategy(new CheckpointRecoveryStrategy())

            .build();
    }

    public void executeWithRecovery() {
        Flow flow = createCheckpointedFlow();

        try {
            FlowOutput output = flow.execute(input);
        } catch (FlowExecutionException e) {
            // 从最近的检查点恢复
            String checkpointId = e.getLastCheckpoint();
            FlowOutput output = flow.resumeFromCheckpoint(checkpointId);
        }
    }
}
```

## 并行和异步执行

### 1. 并行分支

```java
public class ParallelFlowExample {

    public Flow createParallelFlow() {
        return Flow.builder()
            .name("parallel-flow")

            // 分叉节点
            .addForkNode("fork",
                input -> List.of(
                    Map.of("branch", "A", "data", input),
                    Map.of("branch", "B", "data", input),
                    Map.of("branch", "C", "data", input)
                ))

            // 并行分支
            .addParallelBranch("branch-a",
                List.of(
                    createNodeA1(),
                    createNodeA2()
                ))

            .addParallelBranch("branch-b",
                List.of(
                    createNodeB1(),
                    createNodeB2()
                ))

            .addParallelBranch("branch-c",
                List.of(
                    createNodeC1(),
                    createNodeC2()
                ))

            // 合并节点
            .addJoinNode("join",
                results -> mergeResults(results))

            // 连接
            .connect("fork", List.of("branch-a", "branch-b", "branch-c"))
            .connect(List.of("branch-a", "branch-b", "branch-c"), "join")

            // 并行执行配置
            .parallelExecutor(Executors.newFixedThreadPool(10))
            .maxParallelism(3)

            .build();
    }
}
```

### 2. 异步节点

```java
public class AsyncFlowExample {

    public Flow createAsyncFlow() {
        return Flow.builder()
            .name("async-flow")

            // 异步节点
            .addAsyncNode("async-processor",
                CompletableFuture.supplyAsync(() -> {
                    // 异步处理
                    return processDataAsync();
                }))

            // 非阻塞节点
            .addNonBlockingNode("fire-and-forget",
                input -> {
                    // 触发异步操作但不等待
                    triggerAsyncOperation(input);
                    return Map.of("status", "triggered");
                })

            // Future 节点
            .addFutureNode("future-result",
                input -> CompletableFuture
                    .supplyAsync(() -> heavyComputation(input))
                    .orTimeout(60, TimeUnit.SECONDS))

            .build();
    }
}
```

## 实战案例

### 案例1：贷款审批流程

```java
public class LoanApprovalFlow {

    public Flow createLoanApprovalFlow() {
        return Flow.builder()
            .name("loan-approval")
            .description("贷款审批流程")

            // 1. 申请验证
            .addAgentNode("application-validator",
                Agent.builder()
                    .name("validator")
                    .systemPrompt("验证贷款申请的完整性")
                    .build())

            // 2. 信用评分
            .addToolNode("credit-check",
                new CreditScoreTool())

            // 3. 风险评估
            .addAgentNode("risk-assessment",
                Agent.builder()
                    .name("risk-analyzer")
                    .systemPrompt("""
                        基于以下信息评估风险：
                        - 信用分数
                        - 收入状况
                        - 贷款金额
                        - 还款期限

                        输出风险等级：低/中/高
                        """)
                    .build())

            // 4. 决策路由
            .addRouterNode("decision-router",
                output -> {
                    Map assessment = (Map) output;
                    String risk = (String) assessment.get("riskLevel");

                    return switch (risk) {
                        case "低" -> "auto-approve";
                        case "中" -> "manual-review";
                        case "高" -> "auto-reject";
                        default -> "manual-review";
                    };
                })

            // 5. 自动批准
            .addNode(Node.builder()
                .id("auto-approve")
                .type(NodeType.AGENT)
                .agent(createApprovalAgent())
                .build())

            // 6. 人工审核
            .addNode(Node.builder()
                .id("manual-review")
                .type(NodeType.HUMAN_TASK)
                .humanTaskConfig(HumanTaskConfig.builder()
                    .assignee("loan-officer")
                    .timeout(Duration.ofHours(24))
                    .escalation("senior-officer")
                    .build())
                .build())

            // 7. 自动拒绝
            .addNode(Node.builder()
                .id("auto-reject")
                .type(NodeType.AGENT)
                .agent(createRejectionAgent())
                .build())

            // 8. 通知
            .addToolNode("notification",
                new NotificationTool())

            // 连接流程
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

    private class CreditScoreTool extends ToolCall {
        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String ssn = (String) arguments.get("ssn");
            // 调用信用评分 API
            int score = getCreditScore(ssn);
            return ToolCallResult.success(Map.of(
                "score", score,
                "rating", getRating(score)
            ));
        }
    }
}
```

### 案例2：内容生成管道

```java
public class ContentGenerationPipeline {

    public Flow createContentPipeline() {
        return Flow.builder()
            .name("content-generation")

            // 1. 主题研究
            .addParallelBranch("research",
                List.of(
                    // 网络搜索
                    Node.createTool(new WebSearchTool()),
                    // 数据库查询
                    Node.createTool(new DatabaseQueryTool()),
                    // 知识库检索
                    Node.createRAG(ragConfig)
                ))

            // 2. 内容规划
            .addAgentNode("content-planner",
                Agent.builder()
                    .name("planner")
                    .systemPrompt("""
                        基于研究结果创建内容大纲：
                        1. 主要观点
                        2. 支撑论据
                        3. 结构安排
                        """)
                    .build())

            // 3. 内容生成（并行）
            .addForkNode("content-fork",
                outline -> splitIntoSections(outline))

            .addParallelNodes(List.of(
                createSectionWriter("introduction"),
                createSectionWriter("body"),
                createSectionWriter("conclusion")
            ))

            // 4. 内容合并
            .addJoinNode("content-merge",
                sections -> mergeSections(sections))

            // 5. 质量检查
            .addAgentNode("quality-checker",
                Agent.builder()
                    .name("editor")
                    .systemPrompt("""
                        检查内容质量：
                        - 语法和拼写
                        - 逻辑连贯性
                        - 事实准确性
                        - 风格一致性
                        """)
                    .build())

            // 6. 条件优化
            .addConditionalNode("needs-revision",
                output -> {
                    Map quality = (Map) output;
                    double score = (double) quality.get("score");
                    return score < 0.8;
                })

            // 7. 修订循环
            .addLoopNode("revision-loop",
                LoopConfig.builder()
                    .maxIterations(3)
                    .loopCondition(output -> needsRevision(output))
                    .loopBody(List.of(
                        createRevisionAgent(),
                        createQualityChecker()
                    ))
                    .build())

            // 8. 最终格式化
            .addToolNode("formatter",
                new ContentFormatterTool())

            // 9. 发布
            .addToolNode("publisher",
                new PublishingTool())

            .build();
    }

    private Node createSectionWriter(String section) {
        return Node.createAgent(
            Agent.builder()
                .name(section + "-writer")
                .systemPrompt("为" + section + "部分生成内容")
                .build()
        );
    }
}
```

### 案例3：数据 ETL 流程

```java
public class DataETLFlow {

    public Flow createETLFlow() {
        return Flow.builder()
            .name("etl-pipeline")

            // Extract（提取）
            .addParallelBranch("extract",
                List.of(
                    // 多源数据提取
                    createExtractor("mysql", "SELECT * FROM orders"),
                    createExtractor("mongodb", "db.customers.find()"),
                    createExtractor("api", "https://api.example.com/data"),
                    createExtractor("csv", "/data/sales.csv")
                ))

            // Transform（转换）
            .addNode(Node.builder()
                .id("transformer")
                .type(NodeType.OPERATOR)
                .operatorType(OperatorType.TRANSFORM)
                .transformer(new DataTransformer())
                .parallelProcessing(true)
                .batchSize(1000)
                .build())

            // 数据验证
            .addNode(Node.builder()
                .id("validator")
                .type(NodeType.OPERATOR)
                .operatorType(OperatorType.VALIDATE)
                .validator(new DataValidator())
                .validationRules(List.of(
                    new NotNullRule("customer_id"),
                    new RangeRule("amount", 0, 1000000),
                    new FormatRule("email", "^[A-Za-z0-9+_.-]+@(.+)$")
                ))
                .build())

            // 错误处理分支
            .addErrorHandler("validation-error",
                ErrorHandlerConfig.builder()
                    .errorTypes(List.of(ValidationException.class))
                    .strategy(ErrorStrategy.DEAD_LETTER_QUEUE)
                    .deadLetterQueue("failed-records")
                    .build())

            // Load（加载）
            .addNode(Node.builder()
                .id("loader")
                .type(NodeType.TOOL)
                .tool(new DataWarehouseLoader())
                .retryPolicy(RetryPolicy.builder()
                    .maxAttempts(3)
                    .backoffMultiplier(2)
                    .build())
                .build())

            // 监控和报告
            .addNode(Node.builder()
                .id("monitor")
                .type(NodeType.MONITOR)
                .metrics(List.of(
                    "records_processed",
                    "errors_count",
                    "processing_time"
                ))
                .alerting(AlertConfig.builder()
                    .condition("errors_count > 100")
                    .destination("ops-team@example.com")
                    .build())
                .build())

            // 流程配置
            .schedule(CronSchedule.daily("02:00"))
            .timeout(Duration.ofHours(2))
            .checkpoint(CheckpointConfig.builder()
                .enabled(true)
                .interval(Duration.ofMinutes(10))
                .storage(new S3CheckpointStorage("s3://bucket/checkpoints"))
                .build())

            .build();
    }

    private class DataTransformer implements Transformer<List<Map>, List<Map>> {
        @Override
        public List<Map> transform(List<Map> input) {
            return input.stream()
                .map(this::transformRecord)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        }

        private Map transformRecord(Map record) {
            // 数据清洗
            cleanData(record);

            // 字段映射
            Map transformed = mapFields(record);

            // 数据增强
            enrichData(transformed);

            // 数据标准化
            normalizeData(transformed);

            return transformed;
        }
    }
}
```

## 高级特性

### 1. 子流程

```java
public class SubFlowExample {

    public Flow createMainFlow() {
        // 创建子流程
        Flow validationSubFlow = createValidationFlow();
        Flow processingSubFlow = createProcessingFlow();

        return Flow.builder()
            .name("main-flow")

            // 嵌入子流程
            .addSubFlow("validation", validationSubFlow)
            .addSubFlow("processing", processingSubFlow)

            // 条件调用子流程
            .addConditionalSubFlow("optional-subflow",
                createOptionalFlow(),
                input -> shouldRunOptional(input))

            // 动态子流程
            .addDynamicSubFlow("dynamic",
                input -> selectSubFlow(input))

            .build();
    }
}
```

### 2. 事件驱动

```java
public class EventDrivenFlowExample {

    public Flow createEventDrivenFlow() {
        return Flow.builder()
            .name("event-driven")

            // 事件监听器
            .addEventListener("data-arrived",
                event -> processNewData(event))

            .addEventListener("error-occurred",
                event -> handleError(event))

            // 事件触发节点
            .addEventEmitterNode("emitter",
                output -> {
                    if (isImportant(output)) {
                        return Event.of("important-data", output);
                    }
                    return null;
                })

            // 事件等待节点
            .addEventWaitNode("wait-for-approval",
                EventWaitConfig.builder()
                    .eventType("approval")
                    .timeout(Duration.ofHours(1))
                    .onTimeout(() -> escalate())
                    .build())

            .build();
    }
}
```

## 监控和可观测性

### 1. 流程监控

```java
public class FlowMonitoringExample {

    @Inject
    private MeterRegistry metrics;

    @Inject
    private AgentTracer tracer;

    public Flow createMonitoredFlow() {
        return Flow.builder()
            .name("monitored-flow")

            // 添加追踪
            .tracer(tracer)

            // 添加指标收集
            .metricsCollector(new FlowMetricsCollector(metrics))

            // 添加日志
            .logger(LoggerFactory.getLogger("flow"))

            // 监听器
            .addExecutionListener(new FlowExecutionListener() {
                @Override
                public void beforeNode(String nodeId) {
                    metrics.counter("flow.node.started", "node", nodeId).increment();
                }

                @Override
                public void afterNode(String nodeId, NodeOutput output) {
                    metrics.timer("flow.node.duration", "node", nodeId)
                        .record(output.getDuration());

                    if (output.isError()) {
                        metrics.counter("flow.node.errors", "node", nodeId).increment();
                    }
                }
            })

            .build();
    }
}
```

## 最佳实践

1. **流程设计**
   - 保持流程简单和可读
   - 合理使用并行执行
   - 实现适当的错误处理
   - 添加检查点支持

2. **性能优化**
   - 使用批处理减少开销
   - 实施缓存策略
   - 优化节点间数据传输
   - 合理配置并行度

3. **可靠性**
   - 实现重试机制
   - 添加超时控制
   - 使用死信队列
   - 保存执行状态

4. **可维护性**
   - 模块化流程设计
   - 使用有意义的节点名称
   - 添加详细日志
   - 实施版本控制

5. **测试**
   - 单独测试每个节点
   - 测试完整流程
   - 模拟失败场景
   - 性能测试

## 总结

通过本教程，您学习了：

1. ✅ Flow 的基本概念和架构
2. ✅ 各种节点类型的使用
3. ✅ 边和连接的配置
4. ✅ 条件路由和分支
5. ✅ 状态管理和检查点
6. ✅ 实际应用案例

Core-AI 的 Flow 系统提供了强大而灵活的工作流编排能力，使您能够构建复杂的 AI 应用和业务流程。

## 相关资源

- [API 参考文档](api-reference.md)
- [性能优化指南](performance-guide.md)
- [部署指南](deployment-guide.md)
- [故障排除](troubleshooting.md)