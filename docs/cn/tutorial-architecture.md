# Core-AI 架构与原理深度解析

本文档深入解析 Core-AI 框架的核心架构和运行机制，帮助开发者深入理解框架的设计原理。

## 目录

1. [整体架构](#整体架构)
2. [Agent 执行引擎](#agent-执行引擎)
3. [生命周期系统](#生命周期系统)
4. [工具执行机制](#工具执行机制)
5. [消息处理流程](#消息处理流程)
6. [Flow 执行引擎](#flow-执行引擎)
7. [追踪与遥测](#追踪与遥测)

## 整体架构

### 分层架构设计

Core-AI 采用清晰的分层架构，每层职责明确：

```
┌─────────────────────────────────────────────────────────────┐
│                    应用层 (Application Layer)                │
│          用户代码、业务逻辑、服务接口                          │
├─────────────────────────────────────────────────────────────┤
│                    编排层 (Orchestration Layer)              │
│                    Flow │ Planning                          │
│                 节点遍历、任务规划                            │
├─────────────────────────────────────────────────────────────┤
│                     代理层 (Agent Layer)                     │
│          Agent │ Lifecycle │ Memory │ Reflection            │
│        对话管理、上下文记忆、自我反思                          │
├─────────────────────────────────────────────────────────────┤
│                    能力层 (Capability Layer)                 │
│         Tools │ RAG │ Compression │ MCP                     │
│       工具调用、知识检索、上下文压缩                           │
├─────────────────────────────────────────────────────────────┤
│                   提供商层 (Provider Layer)                  │
│       LLMProvider │ Embedding │ Reranker │ VectorStore      │
│            模型调用、向量化、重排序                            │
└─────────────────────────────────────────────────────────────┘
```

### 核心设计原则

1. **Builder 模式**：所有核心组件使用 Builder 模式构建，提供流畅的配置体验
2. **生命周期钩子**：通过 Lifecycle 机制实现可扩展的执行管道
3. **状态机管理**：使用明确的状态转换管理执行过程
4. **依赖注入**：支持 DI 容器，便于测试和扩展

## Agent 执行引擎

### 执行流程详解

Agent 的执行分为**外层包装**和**核心执行**两个层次：

```
┌─────────────────────────────────────────────────────────────────┐
│                      Agent.run(query, context)                   │
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │               Node.aroundExecute()                       │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │  beforeAgentRun() - 可修改查询                    │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  │                         │                               │    │
│  │                         ▼                               │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │           Agent.execute(query)                   │    │    │
│  │  │  ┌───────────────────────────────────────────┐  │    │    │
│  │  │  │         doExecute(query, variables)        │  │    │    │
│  │  │  │                    │                       │  │    │    │
│  │  │  │                    ▼                       │  │    │    │
│  │  │  │         commandOrLoops(query)              │  │    │    │
│  │  │  │         ├─ SlashCommand → chatCommand()    │  │    │    │
│  │  │  │         └─ Normal → chatLoops()            │  │    │    │
│  │  │  │                    │                       │  │    │    │
│  │  │  │                    ▼                       │  │    │    │
│  │  │  │         chatTurns() - 核心对话循环          │  │    │    │
│  │  │  │         ┌─────────────────────────┐        │  │    │    │
│  │  │  │         │  while (hasToolCall &&  │        │  │    │    │
│  │  │  │         │        turn < maxTurn)  │        │  │    │    │
│  │  │  │         │    ├─ beforeModel()     │        │  │    │    │
│  │  │  │         │    ├─ LLM 调用          │        │  │    │    │
│  │  │  │         │    ├─ afterModel()      │        │  │    │    │
│  │  │  │         │    ├─ 工具执行          │        │  │    │    │
│  │  │  │         │    └─ 累积输出          │        │  │    │    │
│  │  │  │         └─────────────────────────┘        │  │    │    │
│  │  │  └───────────────────────────────────────────┘  │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  │                         │                               │    │
│  │                         ▼                               │    │
│  │  ┌─────────────────────────────────────────────────┐    │    │
│  │  │  afterAgentRun() - 可修改结果                    │    │    │
│  │  └─────────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

### 状态转换机制

Agent 使用状态机管理执行状态：

```
                    ┌─────────┐
                    │  INITED │ (初始状态)
                    └────┬────┘
                         │ run() 调用
                         ▼
                    ┌─────────┐
            ┌───────│ RUNNING │───────┐
            │       └────┬────┘       │
            │            │            │
     工具需认证      正常完成     发生异常
            │            │            │
            ▼            ▼            ▼
┌───────────────────┐ ┌─────────┐ ┌────────┐
│WAITING_FOR_USER   │ │COMPLETED│ │ FAILED │
│     _INPUT        │ └─────────┘ └────────┘
└─────────┬─────────┘
          │ 用户确认 "yes"
          │
          ▼
     ┌─────────┐
     │ RUNNING │ (继续执行)
     └─────────┘
```

### 核心代码解析

**多轮对话循环** (`chatTurns` 方法)：

```java
protected void chatTurns(String query, Map<String, Object> variables,
        BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    // 1. 构建用户消息
    buildUserQueryToMessage(query, variables);

    var currentIteCount = 0;
    var agentOut = new StringBuilder();

    // 2. 对话循环
    do {
        // 执行单轮对话
        var turnMsgList = turn(getMessages(), toReqTools(toolCalls), constructionAssistantMsg);

        // 添加消息到历史
        turnMsgList.forEach(this::addMessage);

        // 累积助手输出
        agentOut.append(turnMsgList.stream()
            .filter(m -> RoleType.ASSISTANT.equals(m.role))
            .map(m -> m.content)
            .collect(Collectors.joining("")));

        currentIteCount++;
    } while (lastIsToolMsg() && currentIteCount < maxTurnNumber);
    // 循环条件：最后一条是工具消息且未超过最大轮次

    setOutput(agentOut.toString());
}
```

**关键设计点**：
- 对话循环的终止条件：最后一条消息不是工具消息，或达到最大轮次
- 工具调用通过并行流 (`parallelStream`) 执行，提高效率
- 输出累积：只收集 ASSISTANT 角色的消息内容

## 生命周期系统

### 钩子点设计

Core-AI 提供 8 个生命周期钩子点，覆盖 Agent 执行的完整生命周期：

```
┌─────────────────────────────────────────────────────────────┐
│                     生命周期钩子                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  构建阶段:                                                   │
│    ├─ beforeAgentBuild(AgentBuilder)                        │
│    └─ afterAgentBuild(Agent)                                │
│                                                             │
│  运行阶段:                                                   │
│    ├─ beforeAgentRun(query, context)     ← 可修改查询       │
│    ├─ afterAgentRun(query, result, ctx)  ← 可修改结果       │
│    └─ afterAgentFailed(query, ctx, ex)   ← 异常处理         │
│                                                             │
│  LLM调用阶段:                                                │
│    ├─ beforeModel(request, context)      ← 修改请求参数     │
│    └─ afterModel(request, response, ctx) ← 处理响应         │
│                                                             │
│  工具执行阶段:                                               │
│    ├─ beforeTool(functionCall, context)                     │
│    └─ afterTool(functionCall, context, result)              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### AbstractLifecycle 抽象类

```java
public abstract class AbstractLifecycle {
    // 构建阶段钩子
    public void beforeAgentBuild(AgentBuilder agentBuilder) { }
    public void afterAgentBuild(Agent agent) { }

    // 运行阶段钩子 - 通过 AtomicReference 实现可修改参数
    public void beforeAgentRun(AtomicReference<String> query,
                               ExecutionContext executionContext) { }
    public void afterAgentRun(String query, AtomicReference<String> result,
                              ExecutionContext executionContext) { }
    public void afterAgentFailed(String query, ExecutionContext executionContext,
                                 Exception exception) { }

    // LLM调用阶段钩子
    public void beforeModel(CompletionRequest completionRequest,
                           ExecutionContext executionContext) { }
    public void afterModel(CompletionRequest completionRequest,
                          CompletionResponse completionResponse,
                          ExecutionContext executionContext) { }

    // 工具执行阶段钩子
    public void beforeTool(FunctionCall functionCall,
                          ExecutionContext executionContext) { }
    public void afterTool(FunctionCall functionCall,
                         ExecutionContext executionContext,
                         ToolCallResult toolResult) { }
}
```

### 内置生命周期实现

| 生命周期类 | 作用 | 触发时机 |
|-----------|------|----------|
| `CompressionLifecycle` | 上下文压缩 | `beforeModel` - 检查并压缩消息 |
| `MemoryLifecycle` | 记忆召回工具注册 | 构建时注册 `MemoryRecallTool` |
| `RAGLifecycle` | RAG 检索增强 | `beforeModel` - 注入检索结果 |

### 自定义生命周期示例

```java
public class AuditLifecycle extends AbstractLifecycle {
    private final AuditLogger auditLogger;

    @Override
    public void beforeAgentRun(AtomicReference<String> query,
                               ExecutionContext context) {
        // 记录输入审计日志
        auditLogger.logInput(context.getUserId(), query.get());
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result,
                              ExecutionContext context) {
        // 记录输出审计日志
        auditLogger.logOutput(context.getUserId(), result.get());

        // 敏感词过滤（修改结果）
        String filtered = sensitiveWordFilter(result.get());
        result.set(filtered);
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        // 记录 LLM 调用
        auditLogger.logLLMRequest(request.getModel(), request.getMessages().size());
    }
}
```

## 工具执行机制

### JSON Schema 自动生成

工具参数通过 `ToolCallParameter` 定义，框架自动生成符合 OpenAI 规范的 JSON Schema：

```
┌─────────────────────────────────────────────────────────────┐
│              工具定义 → JSON Schema 生成流程                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ToolCall                                                   │
│    ├─ name: "get_weather"                                   │
│    ├─ description: "获取天气信息"                            │
│    └─ parameters: List<ToolCallParameter>                   │
│         ├─ city (String, required)                          │
│         └─ unit (String, optional, enum: [celsius, ...])    │
│                        │                                    │
│                        ▼ toTool()                           │
│                                                             │
│  Tool                                                       │
│    ├─ type: "function"                                      │
│    └─ function:                                             │
│         ├─ name: "get_weather"                              │
│         ├─ description: "获取天气信息"                       │
│         └─ parameters: JsonSchema                           │
│              {                                              │
│                "type": "object",                            │
│                "properties": {                              │
│                  "city": {                                  │
│                    "type": "string",                        │
│                    "description": "城市名称"                 │
│                  },                                         │
│                  "unit": {                                  │
│                    "type": "string",                        │
│                    "enum": ["celsius", "fahrenheit"]        │
│                  }                                          │
│                },                                           │
│                "required": ["city"]                         │
│              }                                              │
└─────────────────────────────────────────────────────────────┘
```

### Schema 生成核心逻辑

```java
public class JsonSchemaUtil {
    public static JsonSchema toJsonSchema(List<ToolCallParameter> parameters) {
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;

        // 提取必需字段
        schema.required = parameters.stream()
            .filter(v -> v.isRequired() != null && v.isRequired())
            .map(ToolCallParameter::getName)
            .toList();

        // 生成属性定义
        schema.properties = parameters.stream()
            .filter(v -> v.getName() != null)
            .collect(Collectors.toMap(
                ToolCallParameter::getName,
                JsonSchemaUtil::toSchemaProperty
            ));

        return schema;
    }

    private static JsonSchema toSchemaProperty(ToolCallParameter p) {
        var property = new JsonSchema();
        property.description = p.getDescription();
        property.type = buildJsonSchemaType(p.getClassType());
        property.enums = p.getEnums();

        // 递归处理嵌套对象
        if (property.type == PropertyType.OBJECT && isCustomObjectType(p.getClassType())) {
            var nestedSchema = toJsonSchema(p.getClassType());
            property.properties = nestedSchema.properties;
            property.required = nestedSchema.required;
        }

        return property;
    }
}
```

### 工具执行流程

```
┌─────────────────────────────────────────────────────────────┐
│                    工具执行流程                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  LLM 返回 tool_calls                                        │
│         │                                                   │
│         ▼                                                   │
│  Agent.handleFunc(funcMsg)                                  │
│         │                                                   │
│         ▼ parallelStream                                    │
│  ┌─────────────────────────────────────────┐               │
│  │  ToolExecutor.execute(functionCall)     │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  beforeTool() 生命周期钩子               │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  工具查找：按名称匹配 ToolCall            │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  认证检查：needAuth && !authenticated?  │               │
│  │    └─ Yes: 返回 WAITING_FOR_USER_INPUT  │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  tool.execute(arguments, context)       │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  afterTool() 生命周期钩子                │               │
│  │         │                               │               │
│  │         ▼                               │               │
│  │  返回 ToolCallResult                    │               │
│  └─────────────────────────────────────────┘               │
│         │                                                   │
│         ▼                                                   │
│  构建 TOOL 消息，继续对话循环                                │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 工具执行核心代码

```java
public class ToolExecutor {
    public ToolCallResult execute(FunctionCall functionCall, ExecutionContext context) {
        // 前置钩子
        lifecycles.forEach(lc -> lc.beforeTool(functionCall, context));

        // 查找工具
        var tool = findToolByName(functionCall.function.name);
        if (tool == null) {
            return ToolCallResult.failed("tool not found: " + functionCall.function.name);
        }

        try {
            // 认证检查
            if (Boolean.TRUE.equals(tool.isNeedAuth()) && !authenticated) {
                statusUpdater.accept(NodeStatus.WAITING_FOR_USER_INPUT);
                return ToolCallResult.failed("This tool requires user authentication...");
            }

            // 执行工具（支持追踪）
            ToolCallResult result;
            if (tracer != null) {
                result = tracer.traceToolCall(
                    functionCall.function.name,
                    functionCall.function.arguments,
                    () -> tool.execute(functionCall.function.arguments, context)
                );
            } else {
                result = tool.execute(functionCall.function.arguments, context);
            }

            // 后置钩子
            lifecycles.forEach(lc -> lc.afterTool(functionCall, context, result));

            return result;
        } catch (Exception e) {
            return ToolCallResult.failed("tool call failed: " + e.getMessage(), e);
        }
    }
}
```

## 消息处理流程

### 消息类型与结构

```
┌─────────────────────────────────────────────────────────────┐
│                      消息类型                                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Message {                                                  │
│    role: RoleType          // SYSTEM|USER|ASSISTANT|TOOL    │
│    content: String         // 消息内容                       │
│    name: String            // 工具名称（TOOL消息）           │
│    toolCallId: String      // 工具调用ID（TOOL消息）         │
│    toolCalls: List<FC>     // 工具调用列表（ASSISTANT消息）  │
│  }                                                          │
│                                                             │
│  消息流转示例:                                               │
│                                                             │
│  [SYSTEM]     你是一个助手...                                │
│       │                                                     │
│       ▼                                                     │
│  [USER]       北京天气怎么样？                               │
│       │                                                     │
│       ▼ LLM 推理                                            │
│  [ASSISTANT]  toolCalls: [{get_weather, {city: "北京"}}]    │
│       │                                                     │
│       ▼ 工具执行                                            │
│  [TOOL]       name: get_weather                             │
│               content: 25°C, 晴                             │
│       │                                                     │
│       ▼ LLM 推理                                            │
│  [ASSISTANT]  北京今天天气晴朗，气温25°C。                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 消息构建流程

```java
// 用户消息构建
protected void buildUserQueryToMessage(String query, Map<String, Object> variables) {
    // 1. 模板渲染（Mustache）
    String renderedQuery = renderTemplate(query, variables);

    // 2. 检查是否需要添加 promptTemplate 前缀
    if (promptTemplate != null) {
        renderedQuery = renderTemplate(promptTemplate, variables) + renderedQuery;
    }

    // 3. 创建 USER 消息
    addMessage(Message.of(RoleType.USER, renderedQuery));
}

// 工具调用结果消息构建
public List<Message> handleFunc(Message funcMsg) {
    return funcMsg.toolCalls.parallelStream().map(tool -> {
        var result = getToolExecutor().execute(tool, getExecutionContext());

        // TOOL 消息必须包含 toolCallId
        return Message.of(
            RoleType.TOOL,
            result.toResultForLLM(),
            tool.function.name,    // name
            tool.id,               // toolCallId
            null, null
        );
    }).flatMap(List::stream).toList();
}
```

## Flow 执行引擎

### Flow 图结构

```
┌─────────────────────────────────────────────────────────────┐
│                     Flow 图结构                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Nodes (节点):                                              │
│    ├─ FlowNodeType.EXECUTE    - 执行节点                    │
│    ├─ FlowNodeType.AGENT      - Agent 节点                  │
│    ├─ FlowNodeType.TOOL       - 工具节点                    │
│    ├─ FlowNodeType.OPERATOR_FILTER - 过滤器节点             │
│    └─ FlowNodeType.LLM        - 直接 LLM 调用节点           │
│                                                             │
│  Edges (边):                                                │
│    ├─ FlowEdgeType.CONNECTION - 数据流连接                  │
│    │     sourceNode ──数据──▶ targetNode                    │
│    │                                                        │
│    └─ FlowEdgeType.SETTING    - 设置/配置连接               │
│          settingNode ──配置──▶ targetNode                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Flow 执行流程

```
Flow.run(nodeId, input, variables)
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│  1. validate()                                              │
│     验证 Flow 配置完整性                                     │
│                                                             │
│  2. currentNode = getNodeById(nodeId)                       │
│     获取当前节点                                             │
│                                                             │
│  3. 触发节点变更事件                                         │
│     flowNodeChangedEventListener.eventHandler(currentNode)  │
│                                                             │
│  4. 初始化节点设置                                           │
│     settings = getNodeSettings(currentNode)  // SETTING 边   │
│     if (!settings.isEmpty()) {                              │
│         initSettings(settings)                              │
│         currentNode.initialize(settings, edges)             │
│     }                                                       │
│                                                             │
│  5. 执行节点                                                 │
│     if (isExecutableType(currentNode.type)) {               │
│         result = currentNode.execute(input, variables)      │
│                                                             │
│         // 检查等待状态                                      │
│         if (agent.status == WAITING_FOR_USER_INPUT) {       │
│             status = FlowStatus.WAITING_FOR_USER_INPUT      │
│             return result.text()                            │
│         }                                                   │
│     }                                                       │
│                                                             │
│  6. 触发输出更新事件                                         │
│     flowNodeOutputUpdatedEventListener.eventHandler(...)    │
│                                                             │
│  7. 获取下一个节点                                           │
│     nextNodes = getNextNodes(currentNode)  // CONNECTION 边  │
│     if (nextNodes.isEmpty()) {                              │
│         status = FlowStatus.SUCCESS                         │
│         return result.text()                                │
│     }                                                       │
│                                                             │
│  8. 选择下一个节点（多分支时）                               │
│     if (nextNodes.size() > 1) {                             │
│         nextNode = selectNextNodeByEdgeValue(result, nodes) │
│     } else {                                                │
│         nextNode = nextNodes.values().iterator().next()     │
│     }                                                       │
│                                                             │
│  9. 递归执行                                                 │
│     return execute(nextNode.id, result.text(), variables)   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 边的处理逻辑

```java
// 获取下一个节点（CONNECTION 类型边）
public Map<FlowEdge<?>, FlowNode<?>> getNextNodes(FlowNode<?> node) {
    return edges.stream()
        .filter(edge -> edge.type == FlowEdgeType.CONNECTION)
        .filter(edge -> edge.getSourceNodeId().equals(node.id))
        .collect(Collectors.toMap(
            edge -> edge,
            edge -> getNodeById(edge.getTargetNodeId())
        ));
}

// 获取节点设置（SETTING 类型边）
public List<FlowNode<?>> getNodeSettings(FlowNode<?> node) {
    return edges.stream()
        .filter(edge -> edge.type == FlowEdgeType.SETTING)
        .filter(edge -> edge.getSourceNodeId().equals(node.id))
        .map(edge -> getNodeById(edge.getTargetNodeId()))
        .toList();
}
```

### 条件路由

当一个节点有多个下游节点时，使用边的值进行路由选择：

```java
// FlowNode 中的路由选择
public FlowNode<?> selectNextNodeByEdgeValue(FlowNodeResult result,
                                              Map<FlowEdge<?>, FlowNode<?>> nextNodes) {
    for (var entry : nextNodes.entrySet()) {
        FlowEdge<?> edge = entry.getKey();

        // 边的值匹配结果
        if (edge.getValue() != null &&
            edge.getValue().toString().equals(result.text())) {
            return entry.getValue();
        }
    }

    // 默认返回第一个节点
    return nextNodes.values().iterator().next();
}
```

## 追踪与遥测

### OpenTelemetry 集成

Core-AI 使用 OpenTelemetry 实现分布式追踪，兼容 Langfuse 等平台：

```
┌─────────────────────────────────────────────────────────────┐
│                   追踪层次结构                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Flow Execution Span (如有)                                 │
│    └─ Group Execution Span (如有)                           │
│         └─ Agent Execution Span                             │
│              ├─ LLM Completion Span                         │
│              │    ├─ model: "gpt-4"                         │
│              │    ├─ input_tokens: 1500                     │
│              │    └─ output_tokens: 500                     │
│              └─ Tool Call Span                              │
│                   ├─ tool_name: "get_weather"               │
│                   ├─ input: "{city: 'Beijing'}"             │
│                   └─ output: "25°C, sunny"                  │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### AgentTracer 核心方法

```java
public class AgentTracer {
    private final Tracer tracer;
    private final boolean enabled;

    /**
     * 追踪 Agent 执行
     */
    public <T> T traceAgentExecution(AgentTraceContext context, Supplier<T> operation) {
        if (!enabled) return operation.get();

        var span = tracer.spanBuilder(INSTRUMENTATION_NAME)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("langfuse.observation.type", "agent")
            .setAttribute("gen_ai.operation.name", "agent")
            .setAttribute("gen_ai.agent.name", context.getName())
            .setAttribute("agent.has_tools", context.hasTools())
            .setAttribute("agent.has_rag", context.hasRag())
            .startSpan();

        try (var scope = span.makeCurrent()) {
            T result = operation.get();

            // 记录执行结果
            span.setAttribute("output.value", context.getOutput());
            span.setAttribute("agent.status", context.getStatus());

            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * 追踪工具调用
     */
    public <T> T traceToolCall(String toolName, String arguments, Supplier<T> operation) {
        if (!enabled) return operation.get();

        var span = tracer.spanBuilder(toolName)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("langfuse.observation.type", "tool")
            .setAttribute("tool.name", toolName)
            .setAttribute("input.value", arguments)
            .startSpan();

        try (var scope = span.makeCurrent()) {
            T result = operation.get();
            span.setAttribute("output.value", result.toString());
            return result;
        } finally {
            span.end();
        }
    }
}
```

### 追踪属性规范

| 属性类别 | 属性名 | 描述 |
|---------|-------|------|
| **Agent** | `gen_ai.agent.name` | Agent 名称 |
| | `gen_ai.agent.id` | Agent ID |
| | `agent.has_tools` | 是否启用工具 |
| | `agent.has_rag` | 是否启用 RAG |
| | `agent.status` | 执行状态 |
| | `agent.message_count` | 消息数量 |
| **LLM** | `gen_ai.request.model` | 请求模型 |
| | `gen_ai.response.model` | 响应模型 |
| | `gen_ai.usage.input_tokens` | 输入 token 数 |
| | `gen_ai.usage.output_tokens` | 输出 token 数 |
| | `gen_ai.response.finish_reasons` | 完成原因 |
| **Tool** | `tool.name` | 工具名称 |
| | `input.value` | 输入参数 |
| | `output.value` | 输出结果 |
| **Session** | `session.id` | 会话 ID |
| | `user.id` | 用户 ID |

## 总结

Core-AI 框架通过以下核心设计实现了强大而灵活的 AI Agent 能力：

1. **分层架构**：清晰的职责划分，便于理解和扩展
2. **生命周期钩子**：8 个钩子点覆盖完整执行过程，支持 AOP 式的扩展
3. **状态机管理**：明确的状态转换，便于处理异步和等待场景
4. **工具系统**：自动 JSON Schema 生成，支持认证和异步执行
5. **Flow 编排**：有向图执行引擎，支持条件路由和事件监听
6. **可观测性**：原生 OpenTelemetry 支持，兼容主流观测平台

理解这些核心机制，将帮助您更好地使用 Core-AI 构建复杂的 AI 应用。
