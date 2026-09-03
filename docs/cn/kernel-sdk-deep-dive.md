# core-ai 内核 SDK 详解

> 本文档面向初学者，**逐文件、逐方法**拆解 `core-ai/` 模块的核心 SDK。
> 所有代码片段均来自真实源码（Java 25，core-ng 9.4.2），文件路径相对仓库根。
> 配套阅读：[`learning-path.md`](./learning-path.md)（学习顺序）、[`architecture.md`](./architecture.md)（架构事实层）。

---

## 1. 模块引导：`MultiAgentModule` + `AgentBootstrap`

**文件**：
- `core-ai/src/main/java/ai/core/MultiAgentModule.java`
- `core-ai/src/main/java/ai/core/bootstrap/AgentBootstrap.java`
- `core-ai/src/main/java/ai/core/bootstrap/BootstrapResult.java`

### 1.1 装载入口

宿主应用（server/cli/benchmark）只需一行代码挂载整个内核：

```java
load(new MultiAgentModule());  // core-ng Module
```

`MultiAgentModule.initialize()` 做三件事：

```java
@Override
protected void initialize() {
    var bootstrap = new AgentBootstrap(this::property);
    var result = bootstrap.initialize();   // 读配置、构造各 provider
    bindResult(result);                    // 把 result 里的对象绑进 DI 容器
}
```

### 1.2 `BootstrapResult` 的内容

`BootstrapResult` 是一个 record，包含内核运行需要的所有 provider 实例：

```java
record BootstrapResult(
    LLMProviders llmProviders,           // LLM 注册表
    PersistenceProviders persistenceProviders,
    VectorStores vectorStores,
    LiteLLMProvider liteLLMProvider,     // 具体实现
    LiteLLMProvider openAIProvider,
    LiteLLMProvider azureProvider,
    LiteLLMProvider deepSeekProvider,
    PersistenceProvider temporaryPersistenceProvider,
    PersistenceProvider redisPersistenceProvider,
    PersistenceProvider filePersistenceProvider,
    VectorStore milvusVectorStore,
    VectorStore hnswLibVectorStore,
    TelemetryConfig telemetryConfig,
    LLMTracer llmTracer,
    AgentTracer agentTracer,
    FlowTracer flowTracer,
    GroupTracer groupTracer,
    TracerBundle tracerBundle,
    LangfusePromptConfig langfusePromptConfig,
    LangfusePromptProvider langfusePromptProvider,
    McpClientManager mcpClientManager
)
```

### 1.3 绑定顺序

`bindResult()` 按以下顺序绑进 core-ng 容器：

```java
private void bindResult(BootstrapResult r) {
    bind(r.llmProviders);                // ① 注册表
    bind(r.persistenceProviders);
    bind(r.vectorStores);
    bindLLMProviders(r);                 // ② 具体 LLM 实现（按名字绑定）
    bindPersistenceProviders(r);         // ③ 持久化实现
    bindVectorStores(r);                 // ④ 向量库实现
    bindTelemetry(r);                    // ⑤ 遥测配置 + 各种 Tracer
    bindLangfuse(r);                     // ⑥ Langfuse 提示词管理
    bindMcp(r);                          // ⑦ MCP 客户端管理器（注册 shutdown hook）
}
```

**关键设计**：所有 provider 都是 **SPI 形态**（抽象类或接口），`AgentBootstrap` 读配置文件决定用哪个实现。宿主应用通过 `bind(LiteLLMProvider.class, "openai", r.openAIProvider)` 按名字注入具体 vendor。

---

## 2. Agent 主循环：`Node` → `Agent` → `ModelGateway` → `ToolExecutor`

**文件**：
- `core-ai/src/main/java/ai/core/agent/Node.java`（抽象基类）
- `core-ai/src/main/java/ai/core/agent/Agent.java`（agent loop 本体）
- `core-ai/src/main/java/ai/core/agent/AgentBuilder.java`（流式 builder）
- `core-ai/src/main/java/ai/core/agent/AgentAssembler.java`（终局 assembler）
- `core-ai/src/main/java/ai/core/agent/ModelGateway.java`（LLM 调用 helper）
- `core-ai/src/main/java/ai/core/agent/ExecutionContext.java`（单轮上下文）

### 2.1 类层次

```
Node<T extends Node<T>>           ← 抽象基类：消息、终止条件、持久化、生命周期钩子列表
  └─ Agent extends Node<Agent>    ← agent loop：系统提示、工具集、LLM provider、reflection
       └─ Flow extends Node<Flow> ← DAG 编排：多个 FlowNode 通过 FlowEdge 连接
```

### 2.2 `Node` 关键字段

```java
public abstract class Node<T extends Node<T>> {
    private String id;
    private String name;
    private NodeStatus nodeStatus;           // INITED / RUNNING / COMPLETED / WAITING_FOR_USER_INPUT
    private Persistence<T> persistence;      // 序列化/反序列化
    private String input;                    // 用户输入
    private String output;                   // agent 输出
    private Node<?> parent;                  // 父节点（用于 group context）
    private Node<?> next;                    // 下一个节点（用于 DAG）
    private Tracer tracer;                   // 遥测
    private ExecutionContext executionContext; // 单轮运行时上下文
    private final List<Termination> terminations;  // 终止条件列表
    private final NodeMessages nodeMessages;  // 消息历史
    List<AbstractLifecycle> agentLifecycles;  // 生命周期钩子链（**关键扩展点**）
}
```

### 2.3 `Agent` 关键字段

```java
public class Agent extends Node<Agent> {
    CancellationToken rootToken;
    String systemPrompt;
    String promptTemplate;
    LLMProvider llmProvider;                 // LLM 抽象
    ToolRegistry toolRegistry;               // 工具注册表
    RagConfig ragConfig;                     // RAG 配置
    Double temperature;
    String model;
    String multiModalModel;
    ReflectionConfig reflectionConfig;       // 自我反思配置
    ToolExecutor toolExecutor;               // 工具执行器
    Compression compression;                 // 上下文压缩
    ReasoningEffort reasoningEffort;
    List<SubAgentToolCall> subAgents;        // 子 agent 列表
    volatile SpanContext lastLLMSpanContext;  // 当前 LLM span（用于工具嵌套 span）
}
```

### 2.4 Agent 主循环调用链

用户输入 → `Agent.execute(query, variables)` → `doExecute()` → `commandOrLoops()` → `chatLoops()` → `chatTurns()` → `runTurnsLoop()`

```java
// Agent.java:76-139
@Override
String execute(String query, Map<String, Object> variables) {
    // 1. 遥测包装
    var activeTracer = (AgentTracer) getTracer();
    if (activeTracer != null) {
        var context = AgentTraceContext.builder()...build();
        return activeTracer.traceAgentExecution(context, () -> {
            var result = doExecute(query, variables, false);
            // ... 设置 context 输出/状态/消息数/取消原因
            return result;
        }, this::isCancelled);
    }
    return doExecute(query, variables, false);
}

String doExecute(String query, Map<String, Object> variables, boolean skipReflection) {
    boolean isFirstExecution = getInput() == null;
    if (isFirstExecution) {
        setInput(query);
        // 处理 CONFIRMATION_PROMPT（用于 human-in-the-loop 权限确认）
        if (getNodeStatus() == NodeStatus.WAITING_FOR_USER_INPUT 
            && Prompts.CONFIRMATION_PROMPT.equalsIgnoreCase(query)) {
            authenticated = Boolean.TRUE;
        }
        updateNodeStatus(NodeStatus.RUNNING);
    }
    commandOrLoops(query, variables, skipReflection);
    if (isFirstExecution && getNodeStatus() == NodeStatus.RUNNING) {
        updateNodeStatus(NodeStatus.COMPLETED);
    }
    return getOutput();
}

private void commandOrLoops(String query, Map<String, Object> variables, boolean skipReflection) {
    if (SlashCommandParser.isSlashCommand(query)) {
        chatCommand(query, variables);           // /slash 命令
    } else if (isAtMention(query)) {
        chatAtMention(query, variables);         // @agent 提及
    } else {
        chatLoops(query, variables, skipReflection);  // 普通对话
    }
}

private void chatLoops(String query, Map<String, Object> variables, boolean skipReflection) {
    var prompt = promptTemplate + query;
    Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);
    
    // RAG：如果配置了 RAG，检索相关文档并拼接到 prompt
    if (ragConfig.useRag()) {
        rag(getInput(), context);
        prompt += RagConfig.AGENT_RAG_CONTEXT_TEMPLATE;
    }

    // Mustache 模板渲染
    prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

    // 主循环：turn-by-turn 与 LLM 交互
    chatTurns(prompt, variables, (m, t) -> ModelGateway.handLLM(this, m, t));

    // Reflection：如果配置了自我反思，跑 reflection loop
    if (reflectionConfig != null && !skipReflection && reflectionConfig.enabled()) {
        ReflectionOrchestrator.reflectionLoop(this, variables);
    }
}
```

### 2.5 Turn 循环：`chatTurns()` → `runTurnsLoop()` → `turn()` → `handleFunc()`

```java
protected void chatTurns(String query, Map<String, Object> variables, 
                         BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    buildUserQueryToMessage(query, variables);  // 把用户 query 加入消息历史
    runTurnsLoop(constructionAssistantMsg);      // 进入 turn 循环
}

private String runTurnsLoop(BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    var currentIteCount = 0;
    var agentOut = new StringBuilder();
    do {
        if (isCancelled()) break;
        
        // 物化工具：把 ToolProvider 转换成 Tool 定义列表 + dispatch map
        var mat = toolRegistry.materialize(getExecutionContext());
        
        // 执行一次 turn：调 LLM → 拿 response → 如果是 TOOL_CALLS 则执行工具
        var turnMsgList = turn(getMessages(), mat, constructionAssistantMsg);
        turnMsgList.forEach(this::addMessage);
        
        // 提取 ASSISTANT 文本输出
        var turnText = turnMsgList.stream()
            .filter(m -> RoleType.ASSISTANT.equals(m.role))
            .map(Message::getTextContent)
            .collect(Collectors.joining(""));
        if (!turnText.isEmpty()) {
            if (!agentOut.isEmpty()) agentOut.append("\n\n");
            agentOut.append(turnText);
        }
        currentIteCount++;
    } while (!isCancelled()
            && AgentHelper.lastIsToolMsg(getMessages())  // 最后一条是工具调用
            && currentIteCount < maxTurnNumber);         // 未超最大 turn 数

    setOutput(agentOut.toString());
    if (currentIteCount >= maxTurnNumber) {
        throw new MaxTurnsExceededException(maxTurnNumber);
    }
    return agentOut.toString();
}

public List<Message> turn(List<Message> messages, ToolMaterialization toolMaterialization, 
                          BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    var resultMsg = new ArrayList<Message>();
    
    // 1. 调 LLM（通过 ModelGateway.handLLM）
    var choice = constructionAssistantMsg.apply(messages, toolMaterialization.definitions());
    resultMsg.add(choice.message.toMessage());
    
    // 2. 如果 LLM 返回 TOOL_CALLS，执行工具
    if (choice.finishReason == FinishReason.TOOL_CALLS) {
        var funcMsg = handleFunc(choice.message.toMessage(), toolMaterialization.getDispatchMap());
        resultMsg.addAll(funcMsg);
    }
    return resultMsg;
}

public List<Message> handleFunc(Message funcMsg, Map<String, ToolCall> dispatchMap) {
    if (isCancelled()) return List.of();
    
    // 3. 工具编排：并发执行，按 concurrencyGroup 分批
    var orchestration = new ToolOrchestration(dispatchMap, agentLifecycles, 
                                               getToolExecutor(), getExecutionContext());
    return orchestration.execute(funcMsg.toolCalls);
}
```

### 2.6 `ModelGateway.handLLM()`：调 LLM 的内部 helper

```java
// ModelGateway.java（推断）
public static Choice handLLM(Agent agent, List<Message> messages, List<Tool> tools) {
    // 1. 触发 beforeModel lifecycle 钩子
    for (var lifecycle : agent.agentLifecycles) {
        lifecycle.beforeModel(request, agent.getExecutionContext());
    }
    
    // 2. 调 LLM provider
    var response = agent.llmProvider.completion(request);
    
    // 3. 触发 afterModel lifecycle 钩子
    for (var lifecycle : agent.agentLifecycles) {
        lifecycle.afterModel(request, response, agent.getExecutionContext());
    }
    
    // 4. 触发 onModelResponse lifecycle 钩子（用于 inline retry）
    for (var lifecycle : agent.agentLifecycles) {
        var injectMessages = lifecycle.onModelResponse(request, response, agent.getExecutionContext());
        if (injectMessages != null && !injectMessages.isEmpty()) {
            // 注入消息并重试 LLM 调用
            return retryLLM(...);
        }
    }
    
    // 5. 返回 choice（message + finishReason）
    return new Choice(response.choices.get(0), response.finishReason);
}
```

### 2.7 `ExecutionContext`：单轮运行时上下文

```java
// ExecutionContext.java（推断）
public class ExecutionContext {
    String sessionId;
    String userId;
    String taskId;
    Sandbox sandbox;                    // 代码执行沙箱
    ToolRegistry toolRegistry;          // 当前 turn 的工具集
    CancellationToken cancellationToken; // 取消令牌
    AgentProfileRegistry agentProfileRegistry; // @agent 提及解析
    // ... 其他运行时状态
}
```

---

## 3. 生命周期钩子链：`AbstractLifecycle`

**文件**：`core-ai/src/main/java/ai/core/agent/lifecycle/AbstractLifecycle.java`

### 3.1 所有钩子方法

```java
public abstract class AbstractLifecycle {
    // Agent 构建阶段
    public void beforeAgentBuild(AgentBuilder agentBuilder) { }
    public void afterAgentBuild(Agent agent) { }
    
    // LLM 调用前后
    public void beforeModel(CompletionRequest completionRequest, ExecutionContext executionContext) { }
    public void afterModel(CompletionRequest completionRequest, CompletionResponse completionResponse, ExecutionContext executionContext) { }
    
    // Agent 运行前后
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) { }
    public void beforeAgentRun(Node<?> node, AtomicReference<String> query, ExecutionContext executionContext) {
        beforeAgentRun(query, executionContext);
    }
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) { }
    public void afterAgentFailed(String query, ExecutionContext executionContext, Exception exception) { }
    
    // 工具调用前后
    public void beforeTool(FunctionCall functionCall, ExecutionContext executionContext) {
        // todo human in loop  ← 注释提示这里可以加 human-in-the-loop 审批
    }
    public void afterTool(FunctionCall functionCall, ExecutionContext executionContext, ToolCallResult toolResult) { }
    
    // 工具批次前后（并发组）
    public void beforeBatch(String group, List<FunctionCall> tools, ExecutionContext context) { }
    public void afterBatch(String group, List<FunctionCall> tools, ExecutionContext context) { }
    
    // LLM 响应后（用于 inline retry）
    public List<Message> onModelResponse(CompletionRequest completionRequest, CompletionResponse completionResponse, ExecutionContext executionContext) {
        return null;  // 返回 null 或空列表表示接受响应；返回消息列表表示注入并重试
    }
}
```

### 3.2 谁调用谁

| 钩子 | 调用者 | 时机 |
|---|---|---|
| `beforeAgentBuild` | `AgentAssembler.assemble()` | Agent 组装前 |
| `afterAgentBuild` | `AgentAssembler.assemble()` | Agent 组装后 |
| `beforeModel` | `ModelGateway.handLLM()` | 调 LLM 前 |
| `afterModel` | `ModelGateway.handLLM()` | 调 LLM 后 |
| `onModelResponse` | `ModelGateway.handLLM()` | LLM 响应后（用于 inline retry）|
| `beforeAgentRun` | `Agent.execute()` | Agent 开始执行前 |
| `afterAgentRun` | `Agent.execute()` | Agent 执行完成后 |
| `afterAgentFailed` | `Agent.execute()` | Agent 执行失败后 |
| `beforeTool` | `ToolExecutor.execute()` | 单工具执行前 |
| `afterTool` | `ToolExecutor.execute()` | 单工具执行后 |
| `beforeBatch` | `ToolOrchestration.executeBatch()` | 并发批次执行前 |
| `afterBatch` | `ToolOrchestration.executeBatch()` | 并发批次执行后 |

### 3.3 具体 Lifecycle 实现

| 实现类 | 文件 | 作用 |
|---|---|---|
| `CompressionLifecycle` | `ai/core/context/CompressionLifecycle.java` | 上下文压缩：当消息历史过长时，用 LLM 摘要压缩旧消息 |
| `ToolCallPruning` | `ai/core/context/ToolCallPruning.java` | 工具调用裁剪：移除过旧的工具调用结果 |
| `DoomLoopLifecycle` | `ai/core/agent/doomloop/DoomLoopLifecycle.java` | 死循环检测：检测重复的工具调用模式并注入提示 |
| `ResponseValidationLifecycle` | `ai/core/agent/lifecycle/ResponseValidationLifecycle.java` | 响应校验：校验 LLM 输出格式 |
| `ReflectionLifecycle` | `ai/core/reflection/ReflectionLifecycle.java` | 自我反思：在 turn 结束后触发反思循环 |

**关键设计**：所有横切逻辑（压缩、死循环检测、计划更新、reflection）都通过 lifecycle 钩子织入，而不是硬编码在 agent loop 里。这是内核最重要的扩展点——你可以写自己的 lifecycle 实现，注册到 `AgentBuilder`，就能在任意时机插入逻辑。

---

## 4. 工具系统：`ToolCall` → `ToolExecutor` → `ToolOrchestration` → `ToolRegistry`

**文件**：
- `core-ai/src/main/java/ai/core/tool/ToolCall.java`（工具抽象定义）
- `core-ai/src/main/java/ai/core/tool/ToolExecutor.java`（单工具执行）
- `core-ai/src/main/java/ai/core/tool/ToolOrchestration.java`（并发编排）
- `core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java`（中央注册表）
- `core-ai/src/main/java/ai/core/tool/registry/ToolProvider.java`（工具来源 SPI）

### 4.1 `ToolCall`：工具抽象

```java
public abstract class ToolCall {
    public static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L;  // 5 分钟超时
    public static final String SAVE_TO_FILE_PARAM = "save_to_file";
    static final int MAX_SAVE_TO_FILE_SIZE = 10 * 1024 * 1024;  // 10 MB

    String namespace;              // 命名空间（用于分组）
    String name;                   // 工具名（唯一标识）
    String description;            // 工具描述（给 LLM 看）
    List<ToolCallParameter> parameters;  // 参数列表
    Boolean needAuth;              // 是否需要认证
    Boolean directReturn;          // 是否直接返回（不继续对话）
    Boolean llmVisible;            // 是否对 LLM 可见
    Boolean discoverable;          // 是否可发现
    String concurrencyGroup;       // 并发组名（同组工具可并发执行）
    String sourceType;             // 来源类型（builtin/mcp/api-tools 等）
    protected Long timeoutMs;      // 超时时间
    ToolExposure exposure;         // 暴露级别（DIRECT/INTERNAL）

    // 核心方法：执行工具
    public abstract ToolCallResult execute(String arguments);
    
    // 可选方法：轮询、提交输入、取消（用于异步工具）
    public ToolCallResult poll(String taskId) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support polling");
    }
    public ToolCallResult submitInput(String taskId, String input) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support user input");
    }
    public ToolCallResult cancel(String taskId) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support cancellation");
    }
}
```

### 4.2 `ToolExecutor`：单工具执行

```java
public class ToolExecutor {
    private final List<AbstractLifecycle> lifecycles;  // 生命周期钩子链
    private final AgentTracer tracer;
    private final Consumer<NodeStatus> statusUpdater;
    private final BiConsumer<String, Usage> llmUsageConsumer;  // 工具内部 LLM 调用的 token 使用量
    private final Supplier<SpanContext> llmSpanContextSupplier;  // 父 LLM span 上下文
    private boolean authenticated = false;

    public ToolCallResult execute(ToolCall tool, String arguments, ExecutionContext context) {
        // 1. 触发 beforeTool lifecycle
        for (var lifecycle : lifecycles) {
            lifecycle.beforeTool(functionCall, context);
        }
        
        // 2. 执行工具（带超时、取消、tracing）
        var result = doExecute(tool, arguments, context);
        
        // 3. 触发 afterTool lifecycle
        for (var lifecycle : lifecycles) {
            lifecycle.afterTool(functionCall, context, result);
        }
        
        return result;
    }
}
```

### 4.3 `ToolOrchestration`：并发编排

```java
public class ToolOrchestration {
    private static final int DEFAULT_MAX_CONCURRENCY = 10;

    private final int maxConcurrency;
    private final List<AbstractLifecycle> lifecycles;
    private final ToolExecutor toolExecutor;
    private final ExecutionContext context;
    private final Map<String, String> groupIndex;  // toolName → concurrencyGroup
    private final Map<String, ToolCall> toolIndex;  // toolName → ToolCall

    public List<Message> execute(List<FunctionCall> toolCalls) {
        // 1. 按 concurrencyGroup 分批
        var batches = partition(toolCalls);
        List<Message> allMessages = new ArrayList<>();
        
        // 2. 逐批执行（同批内并发，批间串行）
        for (var batch : batches) {
            context.throwIfCancelled();
            allMessages.addAll(executeBatch(batch));
        }
        return allMessages;
    }

    private List<List<FunctionCall>> partition(List<FunctionCall> toolCalls) {
        // 同 concurrencyGroup 的工具放同一批；null group 的工具独占一批（作为屏障）
        // ...
    }

    private List<Message> executeBatch(List<FunctionCall> batch) {
        // 1. 触发 beforeBatch lifecycle
        for (var lifecycle : lifecycles) {
            lifecycle.beforeBatch(group, batch, context);
        }
        
        // 2. 并发执行（用 CompletableFuture + Semaphore 控制最大并发数）
        var futures = batch.stream()
            .map(fc -> CompletableFuture.supplyAsync(() -> toolExecutor.execute(tool, arguments, context), executor))
            .toList();
        var results = futures.stream().map(CompletableFuture::join).toList();
        
        // 3. 触发 afterBatch lifecycle
        for (var lifecycle : lifecycles) {
            lifecycle.afterBatch(group, batch, context);
        }
        
        // 4. 转换成 Message 列表
        return results.stream().map(r -> Message.toolMessage(r)).toList();
    }
}
```

**关键设计**：
- **并发组（concurrencyGroup）**：同组工具可并发执行，不同组工具串行。这允许你控制工具间的依赖关系。
- **屏障（null group）**：没有 concurrencyGroup 的工具独占一批，后续批次等它完成。这用于"破坏性"工具（如写文件）。
- **最大并发数**：默认 10，通过 Semaphore 控制。

### 4.4 `ToolRegistry`：中央注册表

```java
public class ToolRegistry {
    private final Map<String, ToolProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ToolCall>> providerCache = new ConcurrentHashMap<>();

    public void registerProvider(ToolProvider provider) {
        var previous = providers.put(provider.id(), provider);
        providerCache.remove(provider.id());  // 清缓存
        // ... 日志
    }

    public ToolMaterialization materialize(ExecutionContext context) {
        // 1. 收集所有工具（按 priority 排序）
        var collected = collectTools();
        
        // 2. 转换成 Tool 定义列表（给 LLM 看）+ dispatch map（用于派发）
        var definitions = new ArrayList<Tool>();
        var dispatchMap = new LinkedHashMap<String, ToolCall>();
        
        for (var entry : collected.tools.entrySet()) {
            var tool = entry.getValue();
            if (tool.getExposure() == ToolExposure.DIRECT) {
                definitions.add(tool.toTool(context));  // 转换成 LLM 可见的 Tool 定义
            }
            dispatchMap.put(entry.getKey(), tool);  // 加入 dispatch map
        }
        
        return new ToolMaterialization(definitions, dispatchMap, collected.toolProviderIndex);
    }

    private CollectResult collectTools() {
        // 1. 按 priority 排序 provider
        var sorted = providers.values().stream()
            .sorted(Comparator.comparingInt(ToolProvider::priority))
            .toList();
        
        // 2. 依次调用 provide()，高优先级覆盖低优先级同名工具
        var tools = new LinkedHashMap<String, ToolCall>();
        for (var provider : sorted) {
            var provided = provider.provide();
            for (var entry : provided.entrySet()) {
                tools.put(entry.getKey(), entry.getValue());  // 高优先级覆盖
            }
        }
        
        return new CollectResult(tools, toolProviderIndex);
    }
}
```

### 4.5 `ToolProvider`：工具来源 SPI

```java
public interface ToolProvider {
    // 内置 provider 常量
    String BUILTIN_ALL = "builtin-all";
    String BUILTIN_PLANNING = "builtin-planning";
    String BUILTIN_FILES = "builtin-files";
    String BUILTIN_FILE_OPERATIONS = "builtin-file-operations";
    String BUILTIN_FILE_READ_ONLY = "builtin-file-read-only";
    String BUILTIN_MULTIMODAL = "builtin-multimodal";
    String BUILTIN_WEB = "builtin-web";
    String BUILTIN_BASH = "builtin-bash";
    String BUILTIN_CODE_EXECUTION = "builtin-code-execution";
    String BUILTIN_GITHUB = "builtin-github";
    String BUILTIN_MEDIA_GENERATION = "builtin-media-generation";

    String BUILTIN = "builtin";
    String USER = "user-provided";
    String API_TOOLS = "api-tools";
    String DYNAMIC = "dynamic";
    String DATASET = "dataset";
    String SANDBOX = "sandbox";

    // 核心方法
    String id();                           // provider 唯一标识
    Map<String, ToolCall> provide();       // 提供工具列表
    
    // 可选方法
    default int priority() { return 100; }  // 优先级（越小越高）
    default RefreshPolicy refreshPolicy() { return RefreshPolicy.EVERY_TURN; }

    // 刷新策略
    enum RefreshPolicy {
        EVERY_TURN,  // 每次 materialize() 都调用 provide()
        ONCE,        // 只调用一次，永久缓存
        MANUAL       // 手动调用 invalidateCache() 清缓存
    }
}
```

**关键设计**：
- **优先级覆盖**：高优先级 provider 的工具覆盖低优先级同名工具。
- **刷新策略**：`EVERY_TURN` 用于动态工具（如 MCP 工具可能变化），`ONCE` 用于静态工具（如内置工具），`MANUAL` 用于需要手动刷新的工具。

---

## 5. Provider SPI 模式：以 `LLMProvider` 为例

**文件**：
- `core-ai/src/main/java/ai/core/llm/LLMProvider.java`（抽象基类）
- `core-ai/src/main/java/ai/core/llm/LLMProviders.java`（注册表）
- `core-ai/src/main/java/ai/core/llm/providers/LiteLLMProvider.java`（LiteLLM 实现）

### 5.1 `LLMProvider` 抽象基类

```java
public abstract class LLMProvider {
    protected LLMTracer tracer;
    protected ModelModalityRegistry modalityRegistry = SeedModelModalityRegistry.INSTANCE;
    public LLMProviderConfig config;

    public LLMProvider(LLMProviderConfig config) {
        this.config = config;
    }

    // 核心方法：completion（文本生成）
    public abstract CompletionResponse completion(CompletionRequest request);
    
    // 核心方法：completionFormat（结构化输出）
    public abstract <T> T completionFormat(CompletionRequest request, Class<T> clazz);
    
    // 核心方法：embedding（向量嵌入）
    public abstract EmbeddingResponse embedding(EmbeddingRequest request);
    
    // 核心方法：reranking（重排序，用于 RAG）
    public abstract RerankingResponse reranking(RerankingRequest request);
    
    // 核心方法：captionImage（图像描述，用于多模态）
    public abstract CaptionImageResponse captionImage(CaptionImageRequest request);
    
    // 流式 completion
    public void completionStream(CompletionRequest request, StreamingCallback callback) {
        throw new UnsupportedOperationException("Streaming not supported");
    }
    
    // 异步流式 completion
    public void completionStreamAsync(CompletionRequest request, AsyncStreamingCallback callback) {
        throw new UnsupportedOperationException("Async streaming not supported");
    }
    
    // 辅助方法：completionFormatAttachedContent（带附件的结构化输出）
    public final <T> T completionFormatAttachedContent(String systemPrompt, String query, 
                                                        AttachedContent attachedContent, 
                                                        String model, Class<T> clazz) {
        var request = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
            List.of(Message.of(RoleType.SYSTEM, systemPrompt),
                    AgentHelper.buildUserMessage(query, attachedContent)),
            null, null, model, null, Boolean.FALSE, ResponseFormat.of(clazz), null
        ));
        return completionFormat(request, clazz);
    }
}
```

### 5.2 `LLMProviders` 注册表

```java
public class LLMProviders {
    private final Map<String, LLMProvider> providers = new ConcurrentHashMap<>();

    public void register(String id, LLMProvider provider) {
        providers.put(id, provider);
    }

    public LLMProvider get(String id) {
        return providers.get(id);
    }

    public LLMProvider getDefault() {
        return providers.values().iterator().next();  // 取第一个
    }
}
```

### 5.3 `LiteLLMProvider`：LiteLLM 实现

```java
// LiteLLMProvider.java（推断）
public class LiteLLMProvider extends LLMProvider {
    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public LiteLLMProvider(LLMProviderConfig config, String baseUrl, String apiKey) {
        super(config);
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        // 1. 转换成 OpenAI 兼容的请求格式
        var openAIRequest = toOpenAIRequest(request);
        
        // 2. 调 LiteLLM API（或直接调 OpenAI/Azure/DeepSeek API）
        var response = httpClient.send(...);
        
        // 3. 解析响应
        return parseResponse(response);
    }

    @Override
    public <T> T completionFormat(CompletionRequest request, Class<T> clazz) {
        // 1. 设置 response_format 为 JSON schema
        request.setResponseFormat(ResponseFormat.of(clazz));
        
        // 2. 调 completion
        var response = completion(request);
        
        // 3. 解析 JSON 到目标类型
        return JsonUtil.fromJson(response.choices.get(0).message.content, clazz);
    }

    // ... 其他方法
}
```

**关键设计**：
- **统一抽象**：`LLMProvider` 定义了 completion / embedding / reranking / captionImage 等核心方法，屏蔽底层 vendor 差异。
- **Modality Registry**：`ModelModalityRegistry` 管理不同模型的能力（如哪些模型支持 vision、哪些支持 function calling）。
- **LiteLLM 兼容**：`LiteLLMProvider` 通过 LiteLLM 代理访问多种 vendor（OpenAI/Azure/DeepSeek 等），无需为每个 vendor 写实现。

---

## 6. 其他子系统简述

### 6.1 Memory / RAG / VectorStore

**文件**：
- `core-ai/src/main/java/ai/core/memory/Memory.java`
- `core-ai/src/main/java/ai/core/rag/RagConfig.java`
- `core-ai/src/main/java/ai/core/vectorstore/VectorStore.java`

**核心概念**：
- **Memory**：持久化对话历史或提取的关键信息，供后续 turn 检索。
- **RAG**：检索增强生成，通过向量库检索相关文档，拼接到 prompt。
- **VectorStore**：向量存储抽象，实现有 Milvus 和 HNSWlib。

**SPI 形态**：
```java
public interface MemoryProvider {
    Memory getMemory(String sessionId);
    void saveMemory(String sessionId, Memory memory);
}

public interface VectorStore {
    void upsert(String collection, List<Embedding> embeddings);
    List<Embedding> search(String collection, float[] query, int topK);
}
```

### 6.2 Flow DAG 编排

**文件**：
- `core-ai/src/main/java/ai/core/flow/Flow.java`
- `core-ai/src/main/java/ai/core/flow/FlowNode.java`
- `core-ai/src/main/java/ai/core/flow/edges/`

**核心概念**：
- **Flow**：多个 FlowNode 通过 FlowEdge 连接成的 DAG。
- **FlowNode**：抽象节点，子类实现 `execute()` 方法。
- **FlowEdge**：边，定义节点间的控制流（如条件分支、并行）。

**SPI 形态**：
```java
public abstract class FlowNode {
    public abstract String execute(ExecutionContext context);
}

public class Flow extends Node<Flow> {
    List<FlowNode> nodes;
    List<FlowEdge> edges;
    
    public void addNode(FlowNode node) { nodes.add(node); }
    public void addEdge(FlowEdge edge) { edges.add(edge); }
}
```

### 6.3 Skill 系统

**文件**：
- `core-ai/src/main/java/ai/core/skill/SkillLoader.java`
- `core-ai/src/main/java/ai/core/skill/SkillRegistry.java`

**核心概念**：
- **Skill**：封装一组相关工具 + 提示词模板，可复用的能力单元。
- **SKILL.md**：技能定义文件，frontmatter 定义元数据，正文定义提示词。

**SPI 形态**：
```java
public interface SkillProvider {
    List<Skill> provide();
}

public class SkillRegistry {
    void register(Skill skill);
    Skill get(String name);
}
```

### 6.4 MCP client & server

**文件**：
- `core-ai/src/main/java/ai/core/mcp/client/McpClientManager.java`
- `core-ai/src/main/java/ai/core/mcp/server/McpServerService.java`

**核心概念**：
- **MCP client**：消费外部 MCP server 提供的工具，转换成 `ToolCall`。
- **MCP server**：把内核的工具/agent 暴露成 MCP 协议，供外部 agent 调用。

### 6.5 Session & turn

**文件**：
- `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java`
- `core-ai/src/main/java/ai/core/session/TurnDriver.java`
- `core-ai/src/main/java/ai/core/session/permission/PermissionGate.java`

**核心概念**：
- **Session**：一次对话会话，管理消息历史、用户信息、权限。
- **TurnDriver**：事件驱动的 turn 调度器（daemon virtual thread）。
- **PermissionGate**：权限门控，用于 human-in-the-loop 审批。

### 6.6 Telemetry

**文件**：
- `core-ai/src/main/java/ai/core/telemetry/Tracer.java`
- `core-ai/src/main/java/ai/core/telemetry/LLMTracer.java`
- `core-ai/src/main/java/ai/core/telemetry/AgentTracer.java`

**核心概念**：
- **Tracer**：基于 OpenTelemetry 的遥测抽象。
- **LLMTracer**：跟踪 LLM 调用（输入/输出、token 使用、延迟）。
- **AgentTracer**：跟踪 agent 执行（turn 数、工具调用、总延迟）。

---

## 7. 总结：内核 SDK 的核心设计模式

### 7.1 Provider SPI 模式

所有子系统（LLM / Persistence / VectorStore / Skill / Tracer）都遵循同一套形态：

```
Registry（注册表）
  └─ Provider（抽象接口/基类）
       └─ ConcreteProvider（具体实现）
```

- **Registry**：集中管理 provider，按 id 注册/获取。
- **Provider**：定义 SPI 接口（如 `LLMProvider.completion()`）。
- **ConcreteProvider**：具体实现（如 `LiteLLMProvider`）。

### 7.2 Lifecycle 钩子链

所有横切逻辑（压缩、死循环检测、reflection）都通过 `AbstractLifecycle` 钩子织入：

```
Agent/ToolExecutor/ModelGateway
  └─ 调用 lifecycle.beforeXxx() / afterXxx()
       └─ 具体 Lifecycle 实现（Compression / DoomLoop / Reflection）
```

### 7.3 Builder + Assembler 分离

- **Builder**：流式 API，收集用户配置（如 `AgentBuilder.systemPrompt(...).tool(...)`）。
- **Assembler**：终局组装，统一施加默认值、终止条件、memory、reflection。

### 7.4 三层编排

1. **单 agent 循环**：`Agent` + `ModelGateway` + `ToolOrchestration`（并发工具批次）。
2. **多节点 DAG**：`Flow` + `FlowNode` + `FlowEdge`。
3. **跨 agent**：`A2A` 把远端 agent 暴露成 `A2ARemoteAgentToolCall`。

---

## 8. 学习建议

1. **先读 `AbstractLifecycle`**：理解所有钩子方法，这是内核最重要的扩展点。
2. **再读 `Agent.execute()` → `runTurnsLoop()` → `turn()` → `handleFunc()`**：理解主循环。
3. **然后读 `ToolCall` → `ToolExecutor` → `ToolOrchestration`**：理解工具系统。
4. **最后读 `LLMProvider`**：理解 LLM 抽象。
5. **对照源码**：每读一个类，打开对应 `.java` 文件，看真实代码。
6. **写小 demo**：用 `AgentBuilder` 写一个最简单的 agent，调一次 `execute()`，看输出。

---

*本文档基于源码静态分析整理，文件路径与类职责均经核实；运行时行为需以实际启动验证为准。*
