# 11 - Session 管理

> **学习目标**：深入理解 Session 会话管理机制，掌握 InProcessAgentSession、TurnDriver、PermissionGate 等核心组件，以及如何管理多轮对话、权限控制和事件流。
>
> **预计时间**：1.5 天
>
> **前置要求**：完成 [03-Agent核心循环](./03-Agent核心循环.md)

---

## 📋 本章内容

- [11.1 Session 系统总览](#111-session-系统总览)
- [11.2 InProcessAgentSession](#112-inprocessagentsession)
- [11.3 TurnDriver 调度器](#113-turndriver-调度器)
- [11.4 PermissionGate 权限门控](#114-permissiongate-权限门控)
- [11.5 事件系统与监听器](#115-事件系统与监听器)
- [11.6 会话持久化与恢复](#116-会话持久化与恢复)
- [11.7 实战示例](#117-实战示例)
- [11.8 验证学习成果](#118-验证学习成果)

---

## 11.1 Session 系统总览

### 11.1.1 Session 的角色

Session 是 core-ai 的**会话管理层**，负责：

- **会话生命周期**：创建、运行、暂停、恢复、销毁
- **消息路由**：接收用户消息，传递给 Agent
- **权限控制**：工具调用前的审批流程
- **事件流**：Agent 执行过程中的事件推送（文本、工具调用、状态变化等）
- **多轮对话**：管理对话历史和上下文

💡 **设计意图**：Session 是 Agent 与外部世界（HTTP 服务、CLI、WebSocket）之间的桥梁，封装了会话状态管理和事件分发逻辑。

### 11.1.2 核心类关系图

```
┌─────────────────────────────────────────────────────────────┐
│                  InProcessAgentSession                      │
│                                                             │
│  - sessionId: String                                        │
│  - agent: Agent                                             │
│  - permissionGate: PermissionGate               ← 权限门控 │
│  - commandQueue: SessionCommandQueue            ← 命令队列 │
│  - turnDriver: TurnDriver                       ← Turn调度 │
│  - listeners: List<AgentEventListener>          ← 事件监听 │
│                                                             │
│  + sendMessage(message)                         ← 发送消息 │
│  + addEventListener(listener)                   ← 添加监听 │
│  + cancel()                                     ← 取消执行 │
│  + approveTool(toolCallId, decision)            ← 审批工具 │
└──────────────────────┬──────────────────────────────────────┘
                       │ 使用
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ TurnDriver   │ │PermissionGate│ │SessionCommand│
│              │ │              │ │Queue         │
│ 调度 Turn    │ │ 权限审批     │ │ 命令队列     │
│ 执行顺序     │ │ 流程控制     │ │ 异步处理     │
└──────────────┘ └──────────────┘ └──────────────┘
```

### 11.1.3 Session vs Agent

| 特性 | Agent | Session |
|---|---|---|
| **职责** | 执行逻辑（LLM调用、工具执行） | 会话管理（消息路由、权限、事件） |
| **生命周期** | 无状态（可复用） | 有状态（绑定会话） |
| **多轮对话** | 不管理（由 Session 管理） | 管理对话历史 |
| **权限控制** | 不关心（由 Session 处理） | 工具调用审批 |
| **事件推送** | 不推送（由 Session 分发） | 事件流管理 |

💡 **设计意图**：Agent 专注于"做什么"（执行逻辑），Session 专注于"怎么管理"（会话控制）。职责分离，易于测试和扩展。

---

## 11.2 InProcessAgentSession

### 11.2.1 文件位置

```
core-ai/src/main/java/ai/core/session/InProcessAgentSession.java
```

### 11.2.2 核心职责

InProcessAgentSession 是**进程内会话**实现，适用于单进程场景（如 CLI、测试）：

- **封装 Agent**：包装 Agent，提供会话管理能力
- **管理权限**：通过 PermissionGate 控制工具调用
- **调度 Turn**：通过 TurnDriver 管理多轮对话
- **分发事件**：通过 Listener 模式推送事件

### 11.2.3 构造方法

```java
public class InProcessAgentSession implements AgentSession {
    private final String sessionId;
    private final Agent agent;
    private final PermissionGate permissionGate;
    private final SessionCommandQueue commandQueue;
    private final TurnDriver turnDriver;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

    public InProcessAgentSession(
        String sessionId, 
        Agent agent, 
        boolean autoApproveAll,           // 是否自动审批所有工具
        ToolPermissionStore permissionStore  // 工具权限存储
    ) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.permissionGate = new PermissionGate();
        this.commandQueue = new SessionCommandQueue();
        this.turnDriver = new TurnDriver(commandQueue, this::executeCommands);
        
        // 设置流式回调（用于推送文本块事件）
        agent.setStreamingCallback(new SessionStreamingCallback(sessionId, this::dispatch, context));
        
        // 添加权限生命周期（拦截工具调用，请求审批）
        var permissionLifecycle = new ServerPermissionLifecycle(
            sessionId, this::dispatch, permissionGate, autoApproveAll, permissionStore, toolTypeResolver()
        );
        agent.addLifecycle(permissionLifecycle);
        
        // 添加计划更新生命周期（推送计划变化事件）
        agent.addLifecycle(new PlanUpdateLifecycle(this::dispatch));
        
        agent.setAuthenticated(true);
        setupCompressionListener();
    }
}
```

💡 **设计意图**：
- **autoApproveAll**：开发/测试时可以自动审批所有工具，跳过人工确认
- **permissionStore**：持久化用户的审批决策，避免重复审批相同工具
- **ServerPermissionLifecycle**：通过 Lifecycle 钩子拦截工具调用，实现权限控制

### 11.2.4 核心方法

```java
public class InProcessAgentSession {
    /**
     * 发送用户消息
     */
    @Override
    public void sendMessage(String message) {
        sendMessage(message, null);
    }
    
    public void sendMessage(String message, Map<String, Object> context) {
        // 1. 创建消息命令
        var command = new UserMessageCommand(message, context);
        
        // 2. 加入命令队列
        commandQueue.enqueue(command);
        
        // 3. TurnDriver 会异步处理命令
        turnDriver.triggerExecution();
    }
    
    /**
     * 添加事件监听器
     */
    public void addEventListener(AgentEventListener listener) {
        listeners.add(listener);
    }
    
    /**
     * 取消当前执行
     */
    public void cancel() {
        agent.cancel();
        dispatch(new StatusChangeEvent(sessionId, SessionStatus.CANCELLED));
    }
    
    /**
     * 审批工具调用
     */
    public void approveTool(String toolCallId, ApprovalDecision decision) {
        permissionGate.approve(toolCallId, decision);
    }
    
    /**
     * 分发事件给所有监听器
     */
    private void dispatch(AgentEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logger.error("Failed to dispatch event to listener", e);
            }
        }
    }
    
    /**
     * 执行命令（由 TurnDriver 调用）
     */
    private void executeCommands(List<SessionCommand> commands) {
        for (var command : commands) {
            if (command instanceof UserMessageCommand msgCmd) {
                executeUserMessage(msgCmd);
            }
        }
    }
    
    private void executeUserMessage(UserMessageCommand command) {
        try {
            dispatch(new StatusChangeEvent(sessionId, SessionStatus.RUNNING));
            
            // 调用 Agent 执行
            var result = agent.execute(command.message(), command.context());
            
            // 推送文本完成事件
            dispatch(new TextChunkEvent(sessionId, result));
            dispatch(new TurnCompleteEvent(sessionId, result));
            dispatch(new StatusChangeEvent(sessionId, SessionStatus.IDLE));
            
        } catch (MaxTurnsExceededException e) {
            dispatch(new ErrorEvent(sessionId, "Max turns exceeded"));
            dispatch(new StatusChangeEvent(sessionId, SessionStatus.ERROR));
        } catch (Exception e) {
            dispatch(new ErrorEvent(sessionId, e.getMessage()));
            dispatch(new StatusChangeEvent(sessionId, SessionStatus.ERROR));
        }
    }
}
```

💡 **设计意图**：
- **命令队列**：用户消息先进入队列，TurnDriver 异步处理，避免阻塞
- **事件分发**：所有事件通过 `dispatch()` 推送给监听器，支持多个监听器（如 UI、日志）
- **状态管理**：Session 有明确的状态（IDLE/RUNNING/CANCELLED/ERROR），通过事件推送

### 11.2.5 会话状态

```java
public enum SessionStatus {
    IDLE,           // 空闲，等待用户输入
    RUNNING,        // 正在执行
    WAITING_INPUT,  // 等待用户输入（如权限审批）
    CANCELLED,      // 已取消
    ERROR           // 执行出错
}
```

💡 **设计意图**：
- **IDLE**：可以接收新消息
- **RUNNING**：正在执行 Agent，不能接收新消息（需要等待或取消）
- **WAITING_INPUT**：等待权限审批，用户需要 approve/reject
- **CANCELLED**：用户主动取消
- **ERROR**：执行出错，需要处理

---

## 11.3 TurnDriver 调度器

### 11.3.1 文件位置

```
core-ai/src/main/java/ai/core/session/TurnDriver.java
```

### 11.3.2 核心职责

TurnDriver 负责**调度 Turn 执行**，管理命令队列和执行顺序：

- **命令队列管理**：维护待执行的命令列表
- **执行调度**：决定何时执行命令（串行/并行）
- **异步处理**：使用虚拟线程异步执行，不阻塞主线程
- **状态跟踪**：跟踪当前执行状态

💡 **设计意图**：TurnDriver 是 Session 的"调度器"，确保命令按正确顺序执行，支持异步和并发控制。

### 11.3.3 核心实现

```java
public class TurnDriver {
    private final SessionCommandQueue commandQueue;
    private final Consumer<List<SessionCommand>> executor;
    private final AtomicBoolean executing = new AtomicBoolean(false);
    
    public TurnDriver(SessionCommandQueue commandQueue, Consumer<List<SessionCommand>> executor) {
        this.commandQueue = commandQueue;
        this.executor = executor;
    }
    
    /**
     * 触发执行
     * 如果有命令在队列中且当前未执行，则启动执行
     */
    public void triggerExecution() {
        if (executing.compareAndSet(false, true)) {
            // 使用虚拟线程异步执行
            Thread.startVirtualThread(this::executeLoop);
        }
    }
    
    /**
     * 执行循环
     * 持续处理命令队列，直到队列为空
     */
    private void executeLoop() {
        try {
            while (true) {
                // 1. 从队列中取出所有待执行命令
                var commands = commandQueue.drainAll();
                
                if (commands.isEmpty()) {
                    // 队列为空，退出循环
                    break;
                }
                
                // 2. 执行命令
                executor.accept(commands);
            }
        } finally {
            // 3. 标记执行完成
            executing.set(false);
            
            // 4. 检查是否有新命令加入（在执行期间）
            if (!commandQueue.isEmpty()) {
                triggerExecution();
            }
        }
    }
}
```

💡 **设计意图**：
- **虚拟线程**：使用 Java 21 的虚拟线程，轻量级异步，不阻塞平台线程
- **drainAll**：一次性取出所有待执行命令，批量处理，提高效率
- **compareAndSet**：原子操作，确保同一时间只有一个执行循环
- **重新触发**：执行期间如果有新命令加入，执行完成后会再次触发

### 11.3.4 执行流程

```
用户调用 sendMessage("你好")
  │
  ├─ 1. 创建 UserMessageCommand
  │
  ├─ 2. 加入 commandQueue
  │
  └─ 3. 调用 turnDriver.triggerExecution()
         │
         ├─ 检查 executing 标志（false → true）
         │
         ├─ 启动虚拟线程
         │      │
         │      └─ executeLoop()
         │             │
         │             ├─ drainAll() → 取出所有命令
         │             │
         │             ├─ executor.accept(commands)
         │             │      │
         │             │      └─ Session.executeCommands()
         │             │             │
         │             │             └─ agent.execute() → 执行 Agent
         │             │
         │             └─ 队列为空，退出循环
         │
         └─ executing.set(false)
```

---

## 11.4 PermissionGate 权限门控

### 11.4.1 文件位置

```
core-ai/src/main/java/ai/core/session/PermissionGate.java
```

### 11.4.2 核心职责

PermissionGate 负责**工具调用的权限审批**：

- **拦截工具调用**：在工具执行前检查是否需要审批
- **等待用户决策**：如果需要审批，暂停执行，等待用户 approve/reject
- **记录决策**：把用户的决策存入 permissionStore，避免重复审批

💡 **设计意图**：PermissionGate 实现**human-in-the-loop**模式，让用户可以控制 Agent 的行为，特别是危险操作（如删除文件、发送邮件）。

### 11.4.3 核心实现

```java
public class PermissionGate {
    private final Map<String, CompletableFuture<ApprovalDecision>> pendingApprovals = new ConcurrentHashMap<>();
    
    /**
     * 请求审批
     * 如果工具需要审批，返回 Future，等待用户决策
     * 如果不需要审批（或已自动审批），返回已完成的 Future
     */
    public CompletableFuture<ApprovalDecision> requestApproval(String toolCallId, ToolCall toolCall) {
        // 1. 检查是否已自动审批（autoApproveAll 或 permissionStore 中有记录）
        if (isAutoApproved(toolCall)) {
            return CompletableFuture.completedFuture(ApprovalDecision.APPROVE);
        }
        
        // 2. 创建 Future，等待用户决策
        var future = new CompletableFuture<ApprovalDecision>();
        pendingApprovals.put(toolCallId, future);
        
        // 3. 推送审批请求事件
        dispatch(new ToolApprovalRequestEvent(sessionId, toolCallId, toolCall));
        
        return future;
    }
    
    /**
     * 用户审批
     */
    public void approve(String toolCallId, ApprovalDecision decision) {
        var future = pendingApprovals.remove(toolCallId);
        if (future != null) {
            future.complete(decision);
        }
    }
    
    /**
     * 检查是否自动审批
     */
    private boolean isAutoApproved(ToolCall toolCall) {
        // 1. 检查 autoApproveAll 标志
        if (autoApproveAll) {
            return true;
        }
        
        // 2. 检查 permissionStore（用户之前的决策）
        if (permissionStore != null) {
            var storedDecision = permissionStore.getDecision(toolCall.getName());
            if (storedDecision == ApprovalDecision.APPROVE_ALWAYS) {
                return true;
            }
        }
        
        // 3. 检查工具是否需要审批
        return !toolCall.isNeedAuth();
    }
}
```

💡 **设计意图**：
- **CompletableFuture**：异步等待用户决策，不阻塞执行线程
- **pendingApprovals**：维护待审批的工具调用，key 是 toolCallId
- **自动审批策略**：
  - `autoApproveAll = true`：所有工具自动审批（开发/测试）
  - `permissionStore`：用户之前选择"总是批准"的工具
  - `toolCall.isNeedAuth() = false`：工具标记为不需要审批

### 11.4.4 审批决策

```java
public enum ApprovalDecision {
    APPROVE,            // 批准本次
    REJECT,             // 拒绝本次
    APPROVE_ALWAYS,     // 总是批准此工具
    REJECT_ALWAYS       // 总是拒绝此工具
}
```

💡 **设计意图**：
- **APPROVE/REJECT**：一次性决策，下次还需要审批
- **APPROVE_ALWAYS/REJECT_ALWAYS**：持久化决策，存入 permissionStore，下次自动审批/拒绝

### 11.4.5 审批流程

```
Agent 调用工具
  │
  ├─ 1. ServerPermissionLifecycle.beforeTool()
  │      │
  │      └─ permissionGate.requestApproval(toolCallId, toolCall)
  │             │
  │             ├─ 检查 autoApproveAll → true → 返回 APPROVE
  │             ├─ 检查 permissionStore → APPROVE_ALWAYS → 返回 APPROVE
  │             └─ 需要审批 → 创建 Future，推送事件
  │
  ├─ 2. 推送 ToolApprovalRequestEvent
  │      │
  │      └─ UI 显示审批对话框
  │
  ├─ 3. 用户点击"批准"
  │      │
  │      └─ session.approveTool(toolCallId, APPROVE)
  │             │
  │             └─ permissionGate.approve(toolCallId, APPROVE)
  │                    │
  │                    └─ future.complete(APPROVE)
  │
  └─ 4. Lifecycle 收到审批结果，继续执行工具
```

---

## 11.5 事件系统与监听器

### 11.5.1 AgentEvent 事件基类

```java
public abstract class AgentEvent {
    protected final String sessionId;
    protected final Instant timestamp;
    
    protected AgentEvent(String sessionId) {
        this.sessionId = sessionId;
        this.timestamp = Instant.now();
    }
}
```

### 11.5.2 内置事件类型

```java
// 文本块事件（Agent 输出文本）
public class TextChunkEvent extends AgentEvent {
    private final String text;
}

// 工具开始事件
public class ToolStartEvent extends AgentEvent {
    private final String toolCallId;
    private final String toolName;
    private final String arguments;
}

// 工具结果事件
public class ToolResultEvent extends AgentEvent {
    private final String toolCallId;
    private final String result;
    private final boolean isError;
}

// 状态变化事件
public class StatusChangeEvent extends AgentEvent {
    private final SessionStatus status;
}

// Turn 完成事件
public class TurnCompleteEvent extends AgentEvent {
    private final String output;
}

// 错误事件
public class ErrorEvent extends AgentEvent {
    private final String message;
}

// 工具审批请求事件
public class ToolApprovalRequestEvent extends AgentEvent {
    private final String toolCallId;
    private final ToolCall toolCall;
}

// 推理块事件（LLM 思考过程）
public class ReasoningChunkEvent extends AgentEvent {
    private final String reasoning;
}

// 压缩事件（上下文压缩）
public class CompressionEvent extends AgentEvent {
    private final int originalMessageCount;
    private final int compressedMessageCount;
}

// 计划更新事件
public class PlanUpdateEvent extends AgentEvent {
    private final String plan;
}
```

💡 **设计意图**：
- **细粒度事件**：每种事件对应不同的 UI 更新（文本显示、工具状态、审批对话框等）
- **时间戳**：所有事件都有时间戳，便于排序和调试
- **sessionId**：事件绑定到特定 Session，支持多会话

### 11.5.3 AgentEventListener 监听器

```java
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
```

### 11.5.4 监听器示例

```java
// UI 监听器（推送事件到前端）
public class UIEventListener implements AgentEventListener {
    private final WebSocketSession webSocket;
    
    @Override
    public void onEvent(AgentEvent event) {
        if (event instanceof TextChunkEvent textEvent) {
            webSocket.send(JsonUtil.toJson(Map.of(
                "type", "text_chunk",
                "text", textEvent.getText()
            )));
        } else if (event instanceof ToolStartEvent toolEvent) {
            webSocket.send(JsonUtil.toJson(Map.of(
                "type", "tool_start",
                "toolCallId", toolEvent.getToolCallId(),
                "toolName", toolEvent.getToolName()
            )));
        } else if (event instanceof ToolApprovalRequestEvent approvalEvent) {
            webSocket.send(JsonUtil.toJson(Map.of(
                "type", "approval_request",
                "toolCallId", approvalEvent.getToolCallId(),
                "toolName", approvalEvent.getToolCall().getName()
            )));
        }
        // ... 处理其他事件
    }
}

// 日志监听器（记录事件到日志）
public class LoggingEventListener implements AgentEventListener {
    private static final Logger logger = LoggerFactory.getLogger(LoggingEventListener.class);
    
    @Override
    public void onEvent(AgentEvent event) {
        logger.info("Event: {} - {}", event.getClass().getSimpleName(), event.getSessionId());
    }
}

// 使用
session.addEventListener(new UIEventListener(webSocket));
session.addEventListener(new LoggingEventListener());
```

💡 **设计意图**：
- **多监听器**：可以同时添加多个监听器（UI、日志、分析等）
- **解耦**：Session 不关心谁在监听，只负责分发事件
- **异步分发**：事件分发是异步的，不阻塞 Agent 执行

---

## 11.6 会话持久化与恢复

### 11.6.1 SessionPersistence

```java
public class SessionPersistence {
    private final PersistenceProvider persistenceProvider;
    
    /**
     * 保存会话状态
     */
    public void save(InProcessAgentSession session) {
        var state = new SessionState(
            session.id(),
            session.agent().getMessages(),  // 对话历史
            session.agent().getSystemVariables(),  // 系统变量
            Instant.now()
        );
        
        persistenceProvider.save(session.id(), JsonUtil.toJson(state));
    }
    
    /**
     * 加载会话状态
     */
    public Optional<SessionState> load(String sessionId) {
        return persistenceProvider.load(sessionId)
            .map(json -> JsonUtil.fromJson(json, SessionState.class));
    }
    
    /**
     * 恢复会话
     */
    public void restore(InProcessAgentSession session) {
        load(session.id()).ifPresent(state -> {
            session.agent().restoreHistory(state.messages());
            session.agent().setSystemVariables(state.systemVariables());
        });
    }
}
```

💡 **设计意图**：
- **持久化对话历史**：保存 Agent 的消息列表，支持会话恢复
- **持久化系统变量**：保存系统变量（如用户信息、上下文），支持跨会话复用
- **时间戳**：记录保存时间，便于管理和清理

### 11.6.2 恢复流程

```
应用启动
  │
  ├─ 1. 从数据库加载 SessionState
  │
  ├─ 2. 创建 Agent
  │
  ├─ 3. 创建 InProcessAgentSession
  │
  └─ 4. 调用 sessionPersistence.restore(session)
         │
         ├─ 恢复对话历史
         │      └─ agent.restoreHistory(state.messages())
         │
         └─ 恢复系统变量
                └─ agent.setSystemVariables(state.systemVariables())
```

---

## 11.7 实战示例

### 11.7.1 创建 Session

```java
// 1. 创建 Agent
var agent = Agent.builder()
    .name("assistant")
    .systemPrompt("你是一个助手")
    .llmProvider(llmProvider)
    .toolRegistry(toolRegistry)
    .build();

// 2. 创建 Session
var session = new InProcessAgentSession(
    "session-123",
    agent,
    false,  // 不自动审批
    new InMemoryToolPermissionStore()
);

// 3. 添加监听器
session.addEventListener(new UIEventListener(webSocket));
session.addEventListener(new LoggingEventListener());

// 4. 发送消息
session.sendMessage("你好，请帮我写一个 Java 程序");

// 5. 等待执行完成（异步）
// UI 会收到 TextChunkEvent、ToolStartEvent 等事件
```

### 11.7.2 处理权限审批

```java
// Agent 调用危险工具（如删除文件）
// UI 收到 ToolApprovalRequestEvent

// 用户点击"批准"
session.approveTool(toolCallId, ApprovalDecision.APPROVE);

// 或者用户点击"总是批准"
session.approveTool(toolCallId, ApprovalDecision.APPROVE_ALWAYS);
// 下次调用相同工具时会自动批准
```

### 11.7.3 取消执行

```java
// 用户点击"取消"按钮
session.cancel();

// Agent 会收到取消信号，停止执行
// UI 会收到 StatusChangeEvent(CANCELLED)
```

### 11.7.4 恢复会话

```java
// 应用重启后
var sessionState = sessionPersistence.load("session-123").orElseThrow();

var agent = Agent.builder()
    .name("assistant")
    .systemPrompt("你是一个助手")
    .build();

var session = new InProcessAgentSession("session-123", agent, false, permissionStore);

// 恢复对话历史
sessionPersistence.restore(session);

// 继续对话
session.sendMessage("继续刚才的任务");
```

---

## 11.8 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 说出 Session 的五个核心职责
- [ ] 说出 InProcessAgentSession 的三个核心组件（PermissionGate/TurnDriver/EventListeners）
- [ ] 说出 TurnDriver 的调度机制（命令队列 + 虚拟线程 + 异步执行）
- [ ] 说出 PermissionGate 的四种审批决策（APPROVE/REJECT/APPROVE_ALWAYS/REJECT_ALWAYS）
- [ ] 说出至少 5 种事件类型（TextChunk/ToolStart/ToolResult/StatusChange/TurnComplete）
- [ ] 能创建 Session 并处理权限审批

### 🔧 动手实践

1. **创建 Session**：

创建 InProcessAgentSession，添加监听器，发送消息。

2. **处理权限审批**：

配置一个需要审批的工具，模拟用户审批流程。

3. **恢复会话**：

保存会话状态，重新加载，继续对话。

### 📝 自测题

1. Session 和 Agent 的职责分离是什么？
   - A. Session 管理会话，Agent 执行逻辑
   - B. Session 执行逻辑，Agent 管理会话
   - C. 没有分离，职责相同
   
   **答案**：A（Session 管理会话，Agent 执行逻辑）

2. TurnDriver 使用什么技术实现异步执行？
   - A. 线程池
   - B. 虚拟线程（Java 21）
   - C. 回调函数
   
   **答案**：B（虚拟线程）

3. 哪种审批决策会被持久化？
   - A. APPROVE
   - B. REJECT
   - C. APPROVE_ALWAYS
   
   **答案**：C（APPROVE_ALWAYS 会存入 permissionStore）

---

## 🎉 本章小结

本章你学会了：

- ✅ Session 系统的整体架构（InProcessAgentSession + TurnDriver + PermissionGate）
- ✅ InProcessAgentSession 的核心职责（会话管理、权限控制、事件分发）
- ✅ TurnDriver 的调度机制（命令队列 + 虚拟线程 + 异步执行）
- ✅ PermissionGate 的权限审批流程（拦截 → 等待 → 决策）
- ✅ 事件系统与监听器（AgentEvent + AgentEventListener）
- ✅ 会话持久化与恢复（SessionPersistence）
- ✅ 实战示例（创建 Session、处理审批、恢复会话）

---

## 🚀 下一章

准备好进入 **[12-遥测与可观测](./12-遥测与可观测.md)** 了吗？

下一章你会学到：
- OpenTelemetry 集成
- Tracer 基类
- LLMTracer / AgentTracer / FlowTracer
- 全链路追踪
- 与 Langfuse 集成

---

*最后更新：2026-08-31*
