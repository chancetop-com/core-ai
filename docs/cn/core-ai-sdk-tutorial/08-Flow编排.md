# 08 - Flow 编排

> **学习目标**：深入理解 Flow DAG 编排系统，掌握 FlowNode、FlowEdge、节点类型、条件分支、持久化等核心概念，以及如何用 Flow 编排多步骤工作流。
>
> **预计时间**：1.5 天
>
> **前置要求**：完成 [07-Memory与RAG](./07-Memory与RAG.md)（Agent 核心循环）

---

## 📋 本章内容

- [8.1 Flow 系统总览](#81-flow-系统总览)
- [8.2 Flow 核心模型](#82-flow-核心模型)
- [8.3 FlowNode 节点类型](#83-flownode-节点类型)
- [8.4 FlowEdge 边类型](#84-flowedge-边类型)
- [8.5 执行流程](#85-执行流程)
- [8.6 持久化与恢复](#86-持久化与恢复)
- [8.7 遥测与追踪](#87-遥测与追踪)
- [8.8 实战示例](#88-实战示例)
- [8.9 验证学习成果](#89-验证学习成果)

---

## 8.1 Flow 系统总览

### 8.1.1 Flow 的角色

Flow 是 core-ai 的 **DAG 编排引擎**，用于把多个 Agent 节点编排成一个工作流：

- **单 Agent**：一个 agent 独立运行，适合简单任务
- **Flow**：多个 agent 通过边连接成 DAG，适合复杂任务

💡 **设计意图**：Flow 对应 agent 三层编排中的**第二层**（多节点 DAG）。第一层是单 agent 循环，第三层是跨 agent（A2A）。

### 8.1.2 核心类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                         Flow                                 │
│                                                             │
│  - id, name, description                                    │
│  - nodes: List<FlowNode<?>>                     ← 节点列表 │
│  - edges: List<FlowEdge<?>>                     ← 边列表   │
│  - persistence: Persistence<Flow>               ← 持久化   │
│  - currentNodeId, currentInput, currentVariables            │
│  - tracer: FlowTracer                           ← 遥测     │
│  - executionContext: ExecutionContext                       │
│                                                             │
│  + run(nodeId, input, variables): String        ← 入口     │
│  + getNodeById(nodeId): FlowNode<?>             ← 按 ID 获取│
└──────────────────────┬──────────────────────────────────────┘
                       │ 包含
                       ▼
        ┌──────────────────────────────────┐
        │          FlowNode<T>             │  ← 节点抽象基类
        ├──────────────────────────────────┤
        │  - id, name, type                │
        │  - input, output, variables      │
        │  - status (NodeStatus)           │
        │  - parent: Flow                  │
        │  - persistence                   │
        │  - listeners                     │
        ├──────────────────────────────────┤
        │  + execute(input, variables)     │  ← 抽象方法
        │  + init()                        │  ← 初始化
        │  + next(input, vars): String     │  ← 下一节点
        └──────────────┬───────────────────┘
                       │ 子类
        ┌──────────────┼──────────────┬───────────────┐
        ▼              ▼              ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│AgentFlowNode │ │ LLMFlowNode  │ │ RagFlowNode  │ │ CustomNode   │
│              │ │              │ │              │ │ (用户自定义) │
│ 运行 Agent   │ │ 调 LLM       │ │ RAG 检索     │ │              │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```

### 8.1.3 Flow vs Agent

| 特性 | Agent | Flow |
|---|---|---|
| **结构** | 单循环（turn-by-turn） | DAG（多节点） |
| **节点类型** | Agent | AgentFlowNode / LLMFlowNode / RagFlowNode |
| **执行方式** | `execute(query)` | `run(nodeId, input)` |
| **持久化** | 消息历史 | 节点状态 + 边连接 |
| **适用场景** | 简单对话 | 复杂工作流 |

---

## 8.2 Flow 核心模型

### 8.2.1 文件位置

```
core-ai/src/main/java/ai/core/flow/Flow.java
```

### 8.2.2 关键字段

```java
public class Flow {
    String id;                                    // Flow 唯一 ID
    String name;                                  // Flow 名称
    String description;                           // Flow 描述
    List<FlowNode<?>> nodes = List.of();          // 节点列表
    List<FlowEdge<?>> edges = List.of();          // 边列表
    Persistence<Flow> persistence;                // 持久化
    String currentNodeId;                         // 当前执行节点 ID
    String currentInput;                          // 当前输入
    Map<String, Object> currentVariables = Map.of();  // 当前变量
    FlowNodeChangedEventListener flowNodeChangedEventListener;
    FlowNodeOutputUpdatedEventListener flowNodeOutputUpdatedEventListener;
    private LLMProviders llmProviders;
    private VectorStores vectorStores;
    private FlowTracer tracer;
    private ExecutionContext executionContext;
}
```

💡 **设计意图**：
- **nodes + edges**：DAG 的完整定义
- **currentNodeId / currentInput / currentVariables**：运行时状态，支持暂停/恢复
- **listeners**：节点状态变化时通知监听者（如 UI 更新）

### 8.2.3 Builder 模式

```java
var flow = Flow.builder()
    .id("my-flow")
    .name("My Workflow")
    .description("A multi-step workflow")
    .nodes(List.of(node1, node2, node3))
    .edges(List.of(edge1, edge2))
    .llmProviders(llmProviders)
    .vectorStores(vectorStores)
    .tracer(tracer)
    .build();
```

---

## 8.3 FlowNode 节点类型

### 8.3.1 文件位置

```
core-ai/src/main/java/ai/core/flow/FlowNode.java
core-ai/src/main/java/ai/core/flow/nodes/
```

### 8.3.2 抽象基类

```java
public abstract class FlowNode<T extends FlowNode<T>> {
    String id;
    String name;
    String type;
    String input;
    String output;
    Map<String, Object> variables = new HashMap<>();
    NodeStatus status = NodeStatus.INITED;
    Flow parent;
    Persistence<FlowNode<T>> persistence;
    List<FlowNodeChangedEventListener> listeners = new ArrayList<>();
    
    // 核心方法
    public abstract String execute(String input, Map<String, Object> variables);
    
    public void init() {
        // 初始化逻辑（子类可重写）
    }
    
    public String next(String input, Map<String, Object> variables) {
        // 决定下一个节点（默认：按边连接）
        return null;
    }
}
```

💡 **设计意图**：
- **`execute()`**：节点的核心逻辑，子类必须实现
- **`init()`**：初始化逻辑，节点首次执行前调用
- **`next()`**：决定下一个节点，支持条件分支

### 8.3.3 内置节点类型

**AgentFlowNode**（运行 Agent）：

```java
public class AgentFlowNode extends FlowNode<AgentFlowNode> {
    private Agent agent;
    
    public AgentFlowNode(Agent agent) {
        this.agent = agent;
        this.type = "agent";
    }
    
    @Override
    public String execute(String input, Map<String, Object> variables) {
        // 把输入传给 agent，执行一轮
        return agent.execute(input, variables);
    }
}
```

**LLMFlowNode**（直接调 LLM）：

```java
public class LLMFlowNode extends FlowNode<LLMFlowNode> {
    private LLMProvider llmProvider;
    private String promptTemplate;
    
    @Override
    public String execute(String input, Map<String, Object> variables) {
        // 渲染模板
        var prompt = MustachePromptTemplate.render(promptTemplate, variables);
        
        // 调 LLM
        var response = llmProvider.completion(
            CompletionRequest.of(List.of(Message.of(RoleType.USER, prompt)))
        );
        
        return response.choices.get(0).message.content;
    }
}
```

**RagFlowNode**（RAG 检索）：

```java
public class RagFlowNode extends FlowNode<RagFlowNode> {
    private VectorStore vectorStore;
    private LLMProvider llmProvider;
    private int topK;
    
    @Override
    public String execute(String input, Map<String, Object> variables) {
        // 生成 query 的向量
        var embedding = llmProvider.embedding(new EmbeddingRequest(List.of(input)));
        
        // 在向量库中检索
        var request = SimilaritySearchRequest.builder()
            .queryVector(embedding.embeddings.get(0).vector)
            .topK(topK)
            .build();
        
        var results = vectorStore.similaritySearch(request);
        
        // 把检索结果拼接到 variables
        variables.put("rag_context", formatResults(results));
        
        return variables.get("rag_context").toString();
    }
}
```

💡 **设计意图**：
- **AgentFlowNode**：最常用，把 Agent 包装成节点
- **LLMFlowNode**：直接调 LLM，不需要完整 Agent
- **RagFlowNode**：只做 RAG 检索，不调 LLM

---

## 8.4 FlowEdge 边类型

### 8.4.1 文件位置

```
core-ai/src/main/java/ai/core/flow/edges/
```

### 8.4.2 边抽象

```java
public abstract class FlowEdge<T extends FlowEdge<T>> {
    String fromNodeId;      // 源节点 ID
    String toNodeId;        // 目标节点 ID
    String condition;       // 条件表达式（可选）
    
    // 判断是否满足条件
    public abstract boolean matches(String output, Map<String, Object> variables);
}
```

### 8.4.3 内置边类型

**DirectEdge**（直连边）：

```java
public class DirectEdge extends FlowEdge<DirectEdge> {
    @Override
    public boolean matches(String output, Map<String, Object> variables) {
        return true;  // 总是满足
    }
}
```

**ConditionalEdge**（条件边）：

```java
public class ConditionalEdge extends FlowEdge<ConditionalEdge> {
    private final Predicate<String> condition;
    
    public ConditionalEdge(String fromNodeId, String toNodeId, Predicate<String> condition) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.condition = condition;
    }
    
    @Override
    public boolean matches(String output, Map<String, Object> variables) {
        return condition.test(output);
    }
}
```

💡 **设计意图**：
- **DirectEdge**：无条件连接，执行完源节点后直接跳到目标节点
- **ConditionalEdge**：条件连接，只有满足条件才跳转

---

## 8.5 执行流程

### 8.5.1 Flow 执行入口

```java
public String run(String nodeId, String input, Map<String, Object> variables) {
    try {
        var activeTracer = getActiveTracer();
        if (activeTracer != null) {
            // 有遥测：包装执行
            var context = FlowTraceContext.builder()
                .flowId(this.id)
                .flowName(this.name)
                .nodeId(nodeId)
                .sessionId(execContext.getSessionId())
                .userId(execContext.getUserId())
                .build();
            
            return activeTracer.traceFlowExecution(context, () -> execute(nodeId, input, variables));
        }
        return execute(nodeId, input, variables);
    } catch (Exception e) {
        var currentNode = getNodeById(currentNodeId);
        return Strings.format("Exception at {}: {}", currentNode.getName(), e.getMessage());
    }
}
```

### 8.5.2 执行流程详解

```
Flow.run(nodeId, input, variables)
  │
  ├─ 1. 设置当前节点
  │      └─ currentNodeId = nodeId
  │
  ├─ 2. 获取节点
  │      └─ node = getNodeById(nodeId)
  │
  ├─ 3. 初始化节点
  │      └─ node.init()
  │
  ├─ 4. 执行节点
  │      └─ output = node.execute(input, variables)
  │
  ├─ 5. 更新状态
  │      ├─ node.status = COMPLETED
  │      ├─ node.output = output
  │      └─ 通知 listeners
  │
  ├─ 6. 决定下一个节点
  │      ├─ 查找从当前节点出发的边
  │      ├─ 检查每条边的条件
  │      └─ 找到第一条满足条件的边
  │
  ├─ 7. 如果有下一个节点
  │      └─ 递归执行：run(nextNodeId, output, variables)
  │
  └─ 8. 返回最终输出
```

💡 **设计意图**：
- **递归执行**：Flow 通过递归调用 `run()` 遍历 DAG
- **条件分支**：通过 `FlowEdge.matches()` 实现条件跳转
- **状态持久化**：每个节点执行后保存状态，支持暂停/恢复

### 8.5.3 条件分支示例

```java
// 定义节点
var classifyNode = new LLMFlowNode(llmProvider);
classifyNode.promptTemplate("分类以下问题：{{input}}\n返回 'technical' 或 'general'");

var techNode = new AgentFlowNode(techAgent);
var generalNode = new AgentFlowNode(generalAgent);

// 定义边（条件分支）
var techEdge = new ConditionalEdge(
    "classify", "tech", 
    output -> output.contains("technical")
);
var generalEdge = new ConditionalEdge(
    "classify", "general",
    output -> !output.contains("technical")
);

// 构建 Flow
var flow = Flow.builder()
    .nodes(List.of(classifyNode, techNode, generalNode))
    .edges(List.of(techEdge, generalEdge))
    .build();

// 执行
var result = flow.run("classify", "Java 怎么用？", null);
// 输出：techAgent 的回答（因为 classify 返回 "technical"）
```

---

## 8.6 持久化与恢复

### 8.6.1 Flow 持久化

Flow 支持持久化，可以暂停和恢复执行：

```java
// 持久化 Flow 状态
flow.persistence.save(flow);

// 恢复 Flow 状态
var restoredFlow = flow.persistence.load(flowId);

// 从上次中断的节点继续执行
var result = restoredFlow.run(restoredFlow.currentNodeId, 
                               restoredFlow.currentInput, 
                               restoredFlow.currentVariables);
```

💡 **设计意图**：
- **断点续传**：长时间运行的工作流可以持久化，避免丢失进度
- **故障恢复**：节点执行失败后，可以从上次成功的节点继续

### 8.6.2 节点状态持久化

每个节点执行后都会保存状态：

```java
public abstract class FlowNode<T extends FlowNode<T>> {
    // ...
    
    public void saveState() {
        if (persistence != null) {
            persistence.save(this);
        }
    }
}
```

---

## 8.7 遥测与追踪

### 8.7.1 FlowTracer

```java
public interface FlowTracer {
    <T> T traceFlowExecution(FlowTraceContext context, Supplier<T> execution);
}
```

💡 **设计意图**：FlowTracer 追踪整个 Flow 的执行过程，包括每个节点的输入/输出、耗时、状态变化。

### 8.7.2 FlowTraceContext

```java
public class FlowTraceContext {
    String flowId;
    String flowName;
    String nodeId;
    String nodeName;
    String sessionId;
    String userId;
}
```

---

## 8.8 实战示例

### 8.8.1 简单工作流：分类 → 处理

```java
// 1. 定义节点
var classifyNode = new LLMFlowNode(llmProvider);
classifyNode.id = "classify";
classifyNode.name = "分类节点";
classifyNode.promptTemplate = "判断问题类型：{{input}}\n返回 'question' 或 'statement'";

var questionNode = new AgentFlowNode(qaAgent);
questionNode.id = "question";
questionNode.name = "问题处理";

var statementNode = new AgentFlowNode(summaryAgent);
statementNode.id = "statement";
statementNode.name = "陈述处理";

// 2. 定义边
var questionEdge = new ConditionalEdge("classify", "question", 
    output -> output.contains("question"));
var statementEdge = new ConditionalEdge("classify", "statement",
    output -> output.contains("statement"));

// 3. 构建 Flow
var flow = Flow.builder()
    .id("classify-flow")
    .name("分类工作流")
    .nodes(List.of(classifyNode, questionNode, statementNode))
    .edges(List.of(questionEdge, statementEdge))
    .llmProviders(llmProviders)
    .build();

// 4. 执行
var result = flow.run("classify", "Java 是什么？", null);
System.out.println(result);
// 输出：qaAgent 的回答
```

### 8.8.2 复杂工作流：RAG → 分类 → 处理

```java
// 1. 定义节点
var ragNode = new RagFlowNode();
ragNode.id = "rag";
ragNode.name = "RAG 检索";

var classifyNode = new LLMFlowNode(llmProvider);
classifyNode.id = "classify";
classifyNode.name = "分类";
classifyNode.promptTemplate = "基于上下文判断问题类型：{{input}}\n上下文：{{rag_context}}";

var techNode = new AgentFlowNode(techAgent);
techNode.id = "tech";
techNode.name = "技术处理";

var generalNode = new AgentFlowNode(generalAgent);
generalNode.id = "general";
generalNode.name = "通用处理";

// 2. 定义边
var ragToClassify = new DirectEdge("rag", "classify");
var classifyToTech = new ConditionalEdge("classify", "tech", 
    output -> output.contains("technical"));
var classifyToGeneral = new ConditionalEdge("classify", "general",
    output -> !output.contains("technical"));

// 3. 构建 Flow
var flow = Flow.builder()
    .id("rag-classify-flow")
    .name("RAG 分类工作流")
    .nodes(List.of(ragNode, classifyNode, techNode, generalNode))
    .edges(List.of(ragToClassify, classifyToTech, classifyToGeneral))
    .vectorStores(vectorStores)
    .build();

// 4. 执行
var result = flow.run("rag", "core-ai 是什么？", null);
System.out.println(result);
// 输出：techAgent 的回答（基于 RAG 检索结果）
```

---

## 8.9 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 说出 Flow 的三个核心组成部分（nodes/edges/persistence）
- [ ] 说出三种内置节点类型（AgentFlowNode/LLMFlowNode/RagFlowNode）
- [ ] 说出两种边类型（DirectEdge/ConditionalEdge）
- [ ] 画出 Flow 的执行流程（递归 + 条件分支）
- [ ] 能写一个简单的 Flow 工作流

### 🔧 动手实践

1. **读源码**：

```
core-ai/src/main/java/ai/core/flow/Flow.java
core-ai/src/main/java/ai/core/flow/FlowNode.java
core-ai/src/main/java/ai/core/flow/nodes/AgentFlowNode.java
core-ai/src/main/java/ai/core/flow/edges/ConditionalEdge.java
```

2. **写一个简单 Flow**：

定义 3 个节点 + 2 条边，实现条件分支。

3. **测试持久化**：

执行到一半暂停，持久化 Flow，然后恢复执行。

### 📝 自测题

1. Flow 的三层编排中，Flow 属于哪一层？
   - A. 第一层（单 agent 循环）
   - B. 第二层（多节点 DAG）
   - C. 第三层（跨 agent）
   
   **答案**：B（第二层，多节点 DAG）

2. 条件分支通过什么实现？
   - A. FlowNode.next()
   - B. FlowEdge.matches()
   - C. Flow.run()
   
   **答案**：B（FlowEdge.matches()）

3. 哪个节点类型用于 RAG 检索？
   - A. AgentFlowNode
   - B. LLMFlowNode
   - C. RagFlowNode
   
   **答案**：C（RagFlowNode）

---

## 🎉 本章小结

本章你学会了：

- ✅ Flow 的 DAG 编排模型（nodes + edges）
- ✅ 三种内置节点类型（Agent/LLM/RAG）
- ✅ 两种边类型（Direct/Conditional）
- ✅ Flow 的执行流程（递归 + 条件分支）
- ✅ 持久化与恢复机制
- ✅ FlowTracer 遥测追踪
- ✅ 实战示例（简单/复杂工作流）

---

## 🚀 下一章

准备好进入 **[09-Skill系统](./09-Skill系统.md)** 了吗？

下一章你会学到：
- Skill 的定义（SKILL.md frontmatter）
- SkillLoader 加载机制
- SkillRegistry 注册表
- Skill 如何转成工具

---

*最后更新：2026-08-31*
