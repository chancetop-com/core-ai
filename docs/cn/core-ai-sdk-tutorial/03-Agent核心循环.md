# 03 - Agent 核心循环

> **学习目标**：深入理解 Agent 主循环的完整调用链，从用户输入到最终输出的每一步。
>
> **预计时间**：3 天（内核最核心的一章，建议反复阅读）
>
> **前置要求**：完成 [02-模块引导机制](./02-模块引导机制.md)

---

## 📋 本章内容

- [3.1 Agent 类结构](#31-agent-类结构)
- [3.2 完整调用链](#32-完整调用链)
- [3.3 execute() 入口](#33-execute-入口)
- [3.4 doExecute() 状态管理](#34-doexecute-状态管理)
- [3.5 commandOrLoops() 路由](#35-commandorloops-路由)
- [3.6 chatLoops() 主循环](#36-chatloops-主循环)
- [3.7 chatTurns() 准备](#37-chattturns-准备)
- [3.8 runTurnsLoop() 循环](#38-runturnsloop-循环)
- [3.9 turn() 单轮](#39-turn-单轮)
- [3.10 handleFunc() 工具执行](#310-handlefunc-工具执行)
- [3.11 关键辅助方法](#311-关键辅助方法)
- [3.12 验证学习成果](#312-验证学习成果)

---

## 3.1 Agent 类结构

### 3.1.1 类定义

```java
public class Agent extends Node<Agent> {
    // 静态工厂方法
    public static AgentBuilder builder() {
        return new AgentBuilder();
    }
    
    // 核心字段
    CancellationToken rootToken;
    String systemPrompt;           // 系统提示词
    String promptTemplate;         // 提示词模板（Mustache）
    LLMProvider llmProvider;       // LLM 提供商
    ToolRegistry toolRegistry;     // 工具注册表
    RagConfig ragConfig;           // RAG 配置
    Double temperature;            // 温度参数
    String model;                  // 模型名称
    String multiModalModel;        // 多模态模型（用于图像理解）
    boolean preferCaptionPath;     // 是否优先用 caption 路径
    ReflectionConfig reflectionConfig;  // 反思配置
    ReflectionListener reflectionListener;
    Boolean useGroupContext;       // 是否使用组上下文
    Integer maxTurnNumber;         // 最大 turn 数
    Boolean authenticated = Boolean.FALSE;  // 是否已认证
    ToolExecutor toolExecutor;     // 工具执行器
    Compression compression;       // 压缩配置
    ReasoningEffort reasoningEffort;  // 推理力度
    List<SubAgentToolCall> subAgents = new ArrayList<>();  // 子 agent 列表
    volatile SpanContext lastLLMSpanContext;  // 最后一次 LLM 调用的 span 上下文
}
```

💡 **设计意图**：Agent 继承自 `Node<Agent>`，这是一个泛型基类，支持节点嵌套（如 Flow 中的 Agent 节点）。`volatile SpanContext` 用于追踪，确保并发安全。

### 3.1.2 继承关系

```
Node<T extends Node<T>>           ← 抽象基类
  ├─ Agent extends Node<Agent>    ← Agent 节点
  └─ Flow extends Node<Flow>      ← Flow 节点（DAG 编排）
```

💡 **设计意图**：`Node` 是通用的节点抽象，`Agent` 和 `Flow` 都是节点，可以互相嵌套（如 Flow 中包含多个 Agent）。

---

## 3.2 完整调用链

### 3.2.1 调用链图

```
用户输入 query
  │
  ▼
Agent.execute(query, variables)
  │
  ├─ 1. 遥测包装（如果有 AgentTracer）
  │      └─ traceAgentExecution(...)
  │
  ├─ 2. doExecute(query, variables, false)
  │      │
  │      ├─ 2.1 首次执行：设置 input，更新状态为 RUNNING
  │      │
  │      ├─ 2.2 commandOrLoops(query, variables, skipReflection)
  │      │      │
  │      │      ├─ 如果是 /slash 命令：chatCommand()
  │      │      ├─ 如果是 @agent 提及：chatAtMention()
  │      │      └─ 否则：chatLoops()  ← 主路径
  │      │             │
  │      │             ├─ 3.1 拼接 promptTemplate + query
  │      │             ├─ 3.2 如果启用 RAG：rag() 检索
  │      │             ├─ 3.3 Mustache 模板渲染
  │      │             ├─ 3.4 chatTurns(prompt, ...)
  │      │             │      │
  │      │             │      ├─ 4.1 buildUserQueryToMessage()  ← 构建消息
  │      │             │      └─ 4.2 runTurnsLoop()              ← 主循环
  │      │             │             │
  │      │             │             └─ do-while 循环
  │      │             │                    │
  │      │             │                    ├─ 5.1 toolRegistry.materialize()  ← 物化工具
  │      │             │                    ├─ 5.2 turn(messages, mat, ...)    ← 单轮
  │      │             │                    │      │
  │      │             │                    │      ├─ 6.1 constructionAssistantMsg()  ← 调 LLM
  │      │             │                    │      │      └─ ModelGateway.handLLM()
  │      │             │                    │      │
  │      │             │                    │      └─ 6.2 如果 finishReason == TOOL_CALLS
  │      │             │                    │             └─ handleFunc()  ← 执行工具
  │      │             │                    │                    └─ ToolOrchestration.execute()
  │      │             │                    │
  │      │             │                    └─ 循环条件：最后一条是工具消息 && 未超 maxTurnNumber
  │      │             │
  │      │             └─ 3.5 如果启用反思：ReflectionOrchestrator.reflectionLoop()
  │      │
  │      └─ 2.3 首次执行且状态为 RUNNING：更新状态为 COMPLETED
  │
  └─ 返回 output
```

### 3.2.2 关键方法表

| 方法 | 作用 | 调用者 |
|---|---|---|
| `execute(query, variables)` | 入口，遥测包装 | 外部调用 |
| `doExecute(query, variables, skipReflection)` | 状态管理 | `execute()` |
| `commandOrLoops(query, variables, skipReflection)` | 路由（命令/循环） | `doExecute()` |
| `chatLoops(query, variables, skipReflection)` | 主循环（RAG + 模板 + turn） | `commandOrLoops()` |
| `chatTurns(query, variables, constructionAssistantMsg)` | 准备消息 + 启动循环 | `chatLoops()` |
| `buildUserQueryToMessage(query, variables)` | 构建系统消息 + 用户消息 | `chatTurns()` |
| `runTurnsLoop(constructionAssistantMsg)` | 主循环（多轮） | `chatTurns()` |
| `turn(messages, toolMaterialization, constructionAssistantMsg)` | 单轮（调 LLM + 执行工具） | `runTurnsLoop()` |
| `handleFunc(funcMsg, dispatchMap)` | 执行工具 | `turn()` |

---

## 3.3 execute() 入口

### 3.3.1 源码

```java
@Override
String execute(String query, Map<String, Object> variables) {
    var activeTracer = (AgentTracer) getTracer();
    if (activeTracer != null) {
        // 有遥测：包装执行
        var execContext = getExecutionContext();
        var context = AgentTraceContext.builder()
                .name(getName())
                .id(getId())
                .input(query)
                .withTools(toolRegistry != null && !toolRegistry.getToolCalls().isEmpty())
                .withRag(ragConfig != null && ragConfig.useRag())
                .sessionId(execContext.getSessionId())
                .userId(execContext.getUserId())
                .build();

        return activeTracer.traceAgentExecution(context, () -> {
            var result = doExecute(query, variables, false);
            context.setOutput(getOutput());
            context.setStatus(getNodeStatus().name());
            context.setMessageCount(getMessages().size());
            var token = getExecutionContext().getCancellationToken();
            context.setCancelReason(token != null && token.getReason() != null
                    ? token.getReason().name().toLowerCase(Locale.ENGLISH) : null);
            return result;
        }, this::isCancelled);
    }
    // 无遥测：直接执行
    return doExecute(query, variables, false);
}
```

### 3.3.2 逻辑解析

1. **检查遥测**：如果有 `AgentTracer`，用 `traceAgentExecution()` 包装执行
2. **构建追踪上下文**：记录 agent 名称、ID、输入、是否使用工具、是否使用 RAG、session ID、用户 ID
3. **执行并记录**：执行 `doExecute()`，完成后记录输出、状态、消息数、取消原因
4. **无遥测**：直接调用 `doExecute()`

💡 **设计意图**：遥测是**横切关注点**，通过 tracer 包装执行，不侵入业务逻辑。如果没配置遥测，`getTracer()` 返回 `null`，直接执行。

---

## 3.4 doExecute() 状态管理

### 3.4.1 源码

```java
String doExecute(String query, Map<String, Object> variables, boolean skipReflection) {
    boolean isFirstExecution = getInput() == null;
    if (isFirstExecution) {
        setInput(query);
        // 处理权限确认
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
```

### 3.4.2 逻辑解析

1. **首次执行判断**：`getInput() == null` 表示首次执行
2. **首次执行初始化**：
   - 设置 `input` 为 query
   - 如果状态是 `WAITING_FOR_USER_INPUT` 且 query 是确认提示，设置 `authenticated = true`
   - 更新状态为 `RUNNING`
3. **执行主循环**：调用 `commandOrLoops()`
4. **完成后更新状态**：如果首次执行且状态仍为 `RUNNING`，更新为 `COMPLETED`
5. **返回输出**：`getOutput()`

💡 **设计意图**：状态管理确保 agent 从 `INITED` → `RUNNING` → `COMPLETED` 的正确转换。`WAITING_FOR_USER_INPUT` 用于 human-in-the-loop 权限确认。

### 3.4.3 状态流转图

```
INITED
  │
  │ execute()
  ▼
RUNNING
  │
  ├─ 正常完成
  │    ▼
  │  COMPLETED
  │
  ├─ 等待用户输入
  │    ▼
  │  WAITING_FOR_USER_INPUT
  │    │
  │    │ 用户输入确认
  │    ▼
  │  RUNNING（继续）
  │
  └─ 取消
       ▼
     CANCELLED
```

---

## 3.5 commandOrLoops() 路由

### 3.5.1 源码

```java
private void commandOrLoops(String query, Map<String, Object> variables, boolean skipReflection) {
    if (SlashCommandParser.isSlashCommand(query)) {
        chatCommand(query, variables);           // /slash 命令
    } else if (isAtMention(query)) {
        chatAtMention(query, variables);         // @agent 提及
    } else {
        chatLoops(query, variables, skipReflection);  // 普通对话
    }
}

private boolean isAtMention(String query) {
    return AtMentionParser.isAtMention(query) && getExecutionContext().getAgentProfileRegistry() != null;
}
```

### 3.5.2 逻辑解析

1. **Slash 命令**：如果 query 以 `/` 开头（如 `/help`、`/clear`），走 `chatCommand()`
2. **At 提及**：如果 query 包含 `@agent`（如 `@assistant 你好`），走 `chatAtMention()`
3. **普通对话**：否则走 `chatLoops()`（主路径）

💡 **设计意图**：支持三种输入模式，满足不同场景：
- **Slash 命令**：快速操作（如 `/clear` 清空历史）
- **At 提及**：多 agent 场景，指定调用某个 agent
- **普通对话**：默认模式

---

## 3.6 chatLoops() 主循环

### 3.6.1 源码

```java
private void chatLoops(String query, Map<String, Object> variables, boolean skipReflection) {
    // 1. 拼接 promptTemplate + query
    var prompt = promptTemplate + query;
    Map<String, Object> context = variables == null ? Maps.newConcurrentHashMap() : new HashMap<>(variables);
    
    // 2. 如果启用 RAG，检索相关文档
    if (ragConfig.useRag()) {
        rag(getInput(), context);
        prompt += RagConfig.AGENT_RAG_CONTEXT_TEMPLATE;
    }

    // 3. Mustache 模板渲染
    prompt = new MustachePromptTemplate().execute(prompt, context, Hash.md5Hex(promptTemplate));

    // 4. 执行 turns
    chatTurns(prompt, variables, (m, t) -> ModelGateway.handLLM(this, m, t));

    // 5. 如果启用反思，执行反思循环
    if (reflectionConfig != null && !skipReflection && reflectionConfig.enabled()) {
        ReflectionOrchestrator.reflectionLoop(this, variables);
    }
}
```

### 3.6.2 逻辑解析

1. **拼接 prompt**：`promptTemplate + query`
   - `promptTemplate` 是预设的提示词模板（如 "你是一个助手，请回答用户的问题："）
   - `query` 是用户输入
   
2. **RAG 检索**：如果 `ragConfig.useRag()` 为 true
   - 调用 `rag()` 方法检索相关文档
   - 把检索结果拼接到 prompt（通过 `RagConfig.AGENT_RAG_CONTEXT_TEMPLATE`）
   
3. **模板渲染**：用 Mustache 引擎渲染 prompt
   - 支持变量替换（如 `{{user_name}}`）
   - 用 `Hash.md5Hex(promptTemplate)` 作为缓存 key
   
4. **执行 turns**：调用 `chatTurns()` 开始主循环

5. **反思循环**：如果启用反思
   - 调用 `ReflectionOrchestrator.reflectionLoop()` 让 agent 自我反思
   - 可以修正之前的错误

💡 **设计意图**：
- **RAG**：检索增强生成，让 agent 能访问外部知识
- **模板**：支持动态提示词（如根据用户角色调整语气）
- **反思**：自我纠错机制，提高输出质量

### 3.6.3 RAG 检索流程

```java
private void rag(String query, Map<String, Object> variables) {
    RagPipeline.execute(ragConfig, query, variables, this::addTokenCost);
}
```

`RagPipeline.execute()` 做以下事情：
1. 把 query 转成向量（embedding）
2. 在向量库中检索相似文档（top-K）
3. 把检索结果存入 `variables`（key 为 `rag_context`）
4. 后续模板渲染时，`{{rag_context}}` 会被替换为检索结果

---

## 3.7 chatTurns() 准备

### 3.7.1 源码

```java
protected void chatTurns(String query, Map<String, Object> variables, 
                         BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    buildUserQueryToMessage(query, variables);
    runTurnsLoop(constructionAssistantMsg);
}
```

### 3.7.2 逻辑解析

1. **构建消息**：`buildUserQueryToMessage()` 把 query 加入消息历史
2. **启动循环**：`runTurnsLoop()` 开始多轮循环

💡 **设计意图**：`constructionAssistantMsg` 是一个函数，接收 `(messages, tools)` 参数，返回 `Choice`（LLM 响应）。这样可以灵活切换不同的 LLM 调用方式（如普通调用 vs mock 调用）。

### 3.7.3 buildUserQueryToMessage() 详解

```java
private void buildUserQueryToMessage(String query, Map<String, Object> variables) {
    normalizeMessages();  // 规范化消息（如移除过旧的消息）

    // 1. 如果没有消息，添加系统消息
    if (getMessages().isEmpty()) {
        addMessage(buildSystemMessage(variables));
    } else if (getMessages().getFirst().role != RoleType.SYSTEM) {
        // 如果第一条不是系统消息（如从 DB 恢复的历史），在前面插入
        addMessageToFront(buildSystemMessage(variables));
    }

    // 2. 如果使用组上下文，添加父节点的消息
    if (isUseGroupContext() && getParentNode() != null) {
        addMessages(getParentNode().getMessages());
    }

    // 3. 构建用户消息
    var reqMsg = AgentHelper.buildUserMessage(query, getExecutionContext());
    removeLastAssistantToolCallMessageIfNotToolResult(reqMsg);  // 移除未完成的工具调用
    addMessage(reqMsg);
}

private Message buildSystemMessage(Map<String, Object> variables) {
    var prompt = systemPrompt;
    if (getParentNode() != null && isUseGroupContext()) {
        this.putSystemVariable(getParentNode().getSystemVariables());
    }
    Map<String, Object> var = Maps.newConcurrentHashMap();
    if (variables != null) var.putAll(variables);
    var.putAll(getSystemVariables());
    prompt = new MustachePromptTemplate().execute(prompt, var, Hash.md5Hex(promptTemplate));
    return Message.of(RoleType.SYSTEM, prompt);
}
```

💡 **设计意图**：
- **系统消息**：必须放在最前面，定义 agent 的角色和行为
- **组上下文**：多 agent 协作时，子 agent 可以访问父 agent 的消息
- **消息规范化**：确保消息历史符合 LLM API 的要求（如必须以系统消息开头）

---

## 3.8 runTurnsLoop() 循环

### 3.8.1 源码

```java
private String runTurnsLoop(BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    var currentIteCount = 0;
    var agentOut = new StringBuilder();
    do {
        if (isCancelled()) break;
        
        // 1. 物化工具
        var mat = toolRegistry.materialize(getExecutionContext());
        
        // 2. 执行单轮
        var turnMsgList = turn(getMessages(), mat, constructionAssistantMsg);
        logger.debug("Agent[{}] turn {}: received {} messages", getName(), currentIteCount + 1, turnMsgList.size());
        
        // 3. 添加消息到历史
        turnMsgList.forEach(this::addMessage);
        
        // 4. 提取 ASSISTANT 文本输出
        var turnText = turnMsgList.stream()
            .filter(m -> RoleType.ASSISTANT.equals(m.role))
            .map(Message::getTextContent)
            .collect(Collectors.joining(""));
        
        // 5. 拼接输出（多个 turn 之间用换行分隔）
        if (!turnText.isEmpty()) {
            if (!agentOut.isEmpty()) {
                agentOut.append("\n\n");
            }
            agentOut.append(turnText);
        }
        
        currentIteCount++;
    } while (!isCancelled()
            && AgentHelper.lastIsToolMsg(getMessages())  // 最后一条是工具消息
            && currentIteCount < maxTurnNumber);         // 未超最大 turn 数

    setOutput(agentOut.toString());
    if (currentIteCount >= maxTurnNumber) {
        logger.warn("agent run out of turns: maxTurnNumber - {}", maxTurnNumber);
        throw new MaxTurnsExceededException(maxTurnNumber);
    }
    return agentOut.toString();
}
```

### 3.8.2 逻辑解析

1. **循环条件**：
   - 未取消
   - 最后一条消息是工具调用（表示需要继续执行）
   - 未超最大 turn 数（防止死循环）

2. **每次迭代**：
   - 物化工具：把 `ToolProvider` 转换成 `Tool` 定义列表
   - 执行单轮：调 LLM → 拿响应 → 如果是工具调用则执行工具
   - 添加消息：把新消息加入历史
   - 提取文本：提取 ASSISTANT 角色的文本内容
   - 拼接输出：多个 turn 的文本用 `\n\n` 分隔

3. **循环结束**：
   - 设置输出
   - 如果超最大 turn 数，抛异常
   - 返回输出

💡 **设计意图**：
- **多轮循环**：支持 agent 调用多个工具，每个工具调用后继续与 LLM 交互
- **最大 turn 数**：防止死循环（如 agent 不断调用同一个工具）
- **输出拼接**：多个 turn 的文本输出会被拼接成最终输出

### 3.8.3 循环流程图

```
do-while 循环
  │
  ├─ 1. 检查取消
  │
  ├─ 2. 物化工具
  │      └─ toolRegistry.materialize()
  │
  ├─ 3. 执行单轮
  │      └─ turn(messages, mat, constructionAssistantMsg)
  │             │
  │             ├─ 调 LLM
  │             │      └─ ModelGateway.handLLM()
  │             │
  │             └─ 如果是工具调用
  │                    └─ handleFunc()
  │                           └─ ToolOrchestration.execute()
  │
  ├─ 4. 添加消息
  │
  ├─ 5. 提取文本
  │
  ├─ 6. 拼接输出
  │
  └─ 7. 检查循环条件
         ├─ 未取消
         ├─ 最后一条是工具消息
         └─ 未超 maxTurnNumber
```

---

## 3.9 turn() 单轮

### 3.9.1 源码

```java
public List<Message> turn(List<Message> messages, ToolMaterialization toolMaterialization, 
                          BiFunction<List<Message>, List<Tool>, Choice> constructionAssistantMsg) {
    var resultMsg = new ArrayList<Message>();
    
    // 1. 调 LLM
    var choice = constructionAssistantMsg.apply(messages, toolMaterialization.definitions());
    resultMsg.add(choice.message.toMessage());
    
    // 2. 如果是工具调用，执行工具
    if (choice.finishReason == FinishReason.TOOL_CALLS) {
        var funcMsg = handleFunc(choice.message.toMessage(), toolMaterialization.getDispatchMap());
        resultMsg.addAll(funcMsg);
    }
    
    return resultMsg;
}
```

### 3.9.2 逻辑解析

1. **调 LLM**：
   - `constructionAssistantMsg` 是一个函数，接收 `(messages, tools)` 参数
   - 返回 `Choice`（包含 message 和 finishReason）
   - 默认实现是 `ModelGateway.handLLM(this, messages, tools)`

2. **添加 LLM 响应**：把 LLM 的响应消息加入结果

3. **工具调用**：如果 `finishReason == TOOL_CALLS`
   - 调用 `handleFunc()` 执行工具
   - 把工具执行结果加入结果

💡 **设计意图**：
- **Choice**：封装 LLM 响应，包含 message（消息）和 finishReason（结束原因）
- **finishReason**：
  - `STOP`：正常结束（LLM 给出了文本回答）
  - `TOOL_CALLS`：需要调用工具
  - `LENGTH`：达到最大长度

---

## 3.10 handleFunc() 工具执行

### 3.10.1 源码

```java
public List<Message> handleFunc(Message funcMsg, Map<String, ToolCall> dispatchMap) {
    if (isCancelled()) return List.of();
    var orchestration = new ToolOrchestration(dispatchMap, agentLifecycles, getToolExecutor(), getExecutionContext());
    return orchestration.execute(funcMsg.toolCalls);
}
```

### 3.10.2 逻辑解析

1. **检查取消**：如果已取消，返回空列表
2. **创建编排器**：`ToolOrchestration` 负责并发执行工具
3. **执行工具**：调用 `orchestration.execute()` 执行所有工具调用
4. **返回结果**：返回工具执行结果的消息列表

💡 **设计意图**：`ToolOrchestration` 是工具执行的核心，负责：
- 按 `concurrencyGroup` 分批
- 同批内并发执行
- 批间串行执行
- 调用 `ToolExecutor` 执行单个工具
- 调用 lifecycle 钩子

---

## 3.11 关键辅助方法

### 3.11.1 getToolExecutor()

```java
private ToolExecutor getToolExecutor() {
    if (toolExecutor == null) {
        toolExecutor = new ToolExecutor(agentLifecycles, getTracer(), this::updateNodeStatus,
                this::addLlmUsage, () -> this.lastLLMSpanContext);
    }
    toolExecutor.setAuthenticated(authenticated);
    return toolExecutor;
}
```

💡 **设计意图**：懒加载 `ToolExecutor`，避免不必要的初始化。`setAuthenticated()` 传递认证状态，用于 human-in-the-loop 权限检查。

### 3.11.2 getExecutionContext()

```java
@Override
public ExecutionContext getExecutionContext() {
    var context = super.getExecutionContext();
    if (context.getCancellationToken() == null) {
        context.setCancellationToken(AgentInterruptionHandler.getCancellationToken(this));
    }
    context.setLlmProvider(llmProvider);
    context.setModel(model);
    context.setMultiModalModel(multiModalModel);
    context.setVisionNative(resolveContextVisionNative());
    context.setStreamingCallback(getStreamingCallback());
    context.setLifecycles(agentLifecycles);
    context.setToolRegistry(toolRegistry);
    return context;
}
```

💡 **设计意图**：`ExecutionContext` 是单轮运行时上下文，包含所有需要的信息（LLM provider、模型、工具注册表、生命周期钩子等）。每次调用都会更新，确保信息最新。

### 3.11.3 cancel()

```java
public void cancel() {
    AgentInterruptionHandler.cancel(this);
}

public void cancel(CancelReason reason) {
    AgentInterruptionHandler.cancel(this, reason);
}
```

💡 **设计意图**：支持取消 agent 执行。`CancelReason` 记录取消原因（如用户手动取消、超时等）。

### 3.11.4 restoreHistory()

```java
public void restoreHistory(List<Message> messages) {
    if (messages == null || messages.isEmpty()) return;

    if (AgentInterruptionHandler.isInterruptionMarker(messages.getLast())) {
        logger.debug("detected persisted interruption marker in history");
    }

    addMessages(messages);
}
```

💡 **设计意图**：从持久化存储恢复历史消息（如从数据库加载会话历史）。支持检测中断标记（表示上次执行被中断）。

---

## 3.12 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 画出完整的调用链图（从 `execute()` 到 `ToolOrchestration.execute()`）
- [ ] 说出 10 个关键方法的作用
- [ ] 说出状态流转（INITED → RUNNING → COMPLETED）
- [ ] 说出循环条件（未取消 + 最后一条是工具消息 + 未超 maxTurnNumber）
- [ ] 说出 RAG、模板、反思的作用

### 🔧 动手实践

1. **读源码**：

打开 `Agent.java`，逐行读，对照本文档理解每个方法。

2. **画调用链图**：

用纸笔画出完整的调用链，标注每个方法的作用。

3. **写 demo**：

用 `AgentBuilder` 写一个最简单的 agent，调一次 `execute()`，看输出：

```java
var agent = Agent.builder()
    .name("test-agent")
    .systemPrompt("你是一个助手")
    .llmProvider(llmProvider)
    .build();

var output = agent.execute("你好", null);
System.out.println(output);
```

### 📝 自测题

1. `execute()` 方法的作用是什么？
   - A. 直接执行主循环
   - B. 遥测包装 + 调用 `doExecute()`
   - C. 只管理状态
   
     **答案**：B（遥测包装 + 调用 `doExecute()`）

2. `doExecute()` 中，首次执行时会做什么？
   - A. 设置 input，更新状态为 RUNNING
   - B. 直接执行主循环
   - C. 检查取消
   
   **答案**：A（设置 input，更新状态为 RUNNING）

3. `chatLoops()` 中，RAG 的作用是什么？
   - A. 检索相关文档，拼接到 prompt
   - B. 压缩消息历史
   - C. 执行工具
   
   **答案**：A（检索相关文档，拼接到 prompt）

4. `runTurnsLoop()` 的循环条件是什么？
   - A. 未取消 + 最后一条是工具消息 + 未超 maxTurnNumber
   - B. 未取消 + 有工具
   - C. 未超 maxTurnNumber
   
   **答案**：A（未取消 + 最后一条是工具消息 + 未超 maxTurnNumber）

5. `turn()` 方法的作用是什么？
   - A. 调 LLM + 执行工具
   - B. 只调 LLM
   - C. 只执行工具
   
   **答案**：A（调 LLM + 如果是工具调用则执行工具）

---

## 🎉 本章小结

本章你学会了：

- ✅ Agent 类的结构和字段
- ✅ 完整的调用链（从 `execute()` 到 `ToolOrchestration.execute()`）
- ✅ 10 个关键方法的作用
- ✅ 状态流转（INITED → RUNNING → COMPLETED）
- ✅ 主循环的逻辑（RAG + 模板 + turn 循环 + 反思）
- ✅ 单轮的逻辑（调 LLM + 执行工具）
- ✅ 工具执行的逻辑（`ToolOrchestration` 并发编排）

---

## 🚀 下一章

准备好进入 **[04-生命周期钩子](./04-生命周期钩子.md)** 了吗？

下一章你会学到：
- `AbstractLifecycle` 的所有钩子方法
- 谁调用谁（调用关系）
- 具体的 lifecycle 实现（Compression、DoomLoop、Reflection）
- 如何写自定义 lifecycle

这是内核**最重要的扩展点**，80% 的定制需求都可以通过 lifecycle 实现。

---

*最后更新：2026-08-31*
