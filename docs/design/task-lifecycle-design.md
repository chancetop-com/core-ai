# Core-AI Task 生命周期管理设计文档

> 版本: v1.0
> 日期: 2026-07-07
> 状态: 设计阶段
> 关联调研: [task-lifecycle-research.md](./task-lifecycle-research.md)

## 一、设计目标

在 core-ai 现有 task 机制基础上，增加主 agent 对后台 task 的生命周期管理能力：

1. **Resume（恢复）**: 主 agent 可以向已存在的子 agent 发送新消息，继续之前的会话
2. **Cancel（取消）**: 主 agent 可以取消正在运行的后台 agent，回收资源
3. **Interrupt（中断）**: 主 agent 可以中断当前 turn 但不终止 agent，保留会话继续

## 二、现状分析

### 2.1 已有基础

```
┌─────────────────────────────────────────────────────┐
│                   TaskTool                           │
│  execute(prompt, subagent_type, run_in_background)   │
│    ├─ runInBackground=true → TaskManager.submit()   │
│    └─ runInBackground=false → subAgent.run()         │
└────────────────────────┬────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────┐
│              BackgroundTaskManager                   │
│  submit() | register() | cancelAll() | createChild()│
│  tasks: CopyOnWriteArrayList<Task>                   │
│  executor: VirtualThread per task executor           │
└────────────────────────┬────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────┐
│                    Task                              │
│  taskId | taskName | parentTaskId | future | context │
│  cancel(): future.cancel(true) + cascading children  │
└─────────────────────────────────────────────────────┘
```

### 2.2 已支持的能力

| 能力 | 实现 | 问题 |
|------|------|------|
| Create | TaskTool + BackgroundTaskManager.submit() | ✅ 完善 |
| Cancel (session级) | InProcessAgentSession.cancelTurn() → CancellationToken.cancel() | ✅ 但无 agent 级入口 |
| Interrupt (session级) | InProcessAgentSession.interruptTurn() → CancellationToken.interrupt() | ✅ 但无 agent 级入口 |
| task_id 参数 | TaskTool 接受 task_id，传递到 ExecutionContext | ⚠️ 仅复用 context，不恢复执行 |
| SendMessage | ❌ | 缺失：无法向运行中的 agent 发消息 |

### 2.3 关键缺陷

1. **无 Resume**: `task_id` 参数只在创建时传递到 ExecutionContext，没有"向已存在的 agent 执行会话发送消息并触发新 turn"的能力
2. **Cancel 不可达**: 虽然有 `Task.cancel()` 和 `CancellationToken`，但主 agent（LLM）没有工具可以调用它
3. **无 Agent 寻址**: TaskManager 没有按 taskId 查找/操作单个 agent 的 API
4. **无 SendMessage**: 无法向后台 agent 注入消息触发继续执行
5. **task_id 丢失**: TaskTool 新建 agent 时生成新 taskId，主 agent 拿到的是通知 XML 中的 taskId，但无法用这个 taskId 做任何操作

## 三、核心设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    主 Agent (LLM)                        │
│                                                          │
│  可用操作:                                                │
│  task(create)  task_send_message(send+resume)             │
│  task_cancel   task_interrupt                            │
└──────────┬──────────────────────────────────────────────┘
           │ Tool calls
           ▼
┌─────────────────────────────────────────────────────────┐
│                   TaskLifecycleManager                   │
│                                                          │
│  ┌───────────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │   TaskStore   │  │ TaskRouter   │  │ LifecycleHook │ │
│  │ (persistence) │  │ (lookup+op)  │  │ (events)      │ │
│  └───────────────┘  └──────────────┘  └───────────────┘ │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                 BackgroundTaskManager (enhanced)          │
│                                                          │
│  submit() | get(taskId) | cancel(taskId)                  │
│  interrupt(taskId) | sendMessage(taskId, message)         │
│  listByParent(taskId) | cancelAll()                       │
│  tasks: ConcurrentHashMap<String, Task>                   │
└─────────────────────────────────────────────────────────┘
```

### 3.2 设计原则

- **最小侵入**: 在现有 BackgroundTaskManager/Task/TaskTool 基础上扩展，不推翻重来
- **渐进式**: 先实现核心操作（resume/cancel/interrupt），再逐步完善（状态查询、事件钩子等）
- **向下兼容**: 现有 TaskTool 调用方式完全不变，新增能力通过新工具提供
- **遵循现有模式**: 使用 CancellationToken 树、SessionCommandQueue 通知、VirtualThread 执行器

## 四、详细设计

### 4.1 Task 状态机

扩展 Task 模型，增加显式状态跟踪：

```java
public enum TaskStatus {
    PENDING,       // 已创建，等待执行
    RUNNING,       // 正在执行
    INTERRUPTED,   // 当前 turn 被中断，agent 仍存活（可 resume）
    WAITING_INPUT, // 等待用户输入（工具审批等）
    COMPLETED,     // 正常完成
    FAILED,        // 执行失败
    CANCELLED,     // 被取消
}
```

状态转换：
```
PENDING ──→ RUNNING ──→ COMPLETED
              │  └───→ FAILED
              │  └───→ CANCELLED
              ├──→ INTERRUPTED ──→ RUNNING (resume)
              └──→ WAITING_INPUT ──→ RUNNING (approve)
```

```java
public class Task {
    public final String taskId;
    public final String taskName;
    public final String parentTaskId;
    private final Future<?> future;
    private final ExecutionContext subContext;
    private final Agent subAgent;                    // NEW: 持有 agent 引用
    private volatile TaskStatus status;              // NEW: 显式状态
    private volatile CancellationToken taskToken;    // NEW: task 级别的 token

    // 现有
    public void cancel() { ... }

    // NEW
    public void interrupt() {
        if (taskToken != null) {
            taskToken.interrupt();  // CancelReason.NEW_MESSAGE_INTERRUPT
        }
        status = TaskStatus.INTERRUPTED;
    }

    public void sendMessage(String message) {
        // 向 agent 注入消息并触发继续执行
        subAgent.injectUserMessage(message);
        // 重新提交执行
        var newFuture = executor.submit(() -> {
            subAgent.continueWithInjectedMessage();
            ...
        });
        this.future = newFuture;
        status = TaskStatus.RUNNING;
    }

    public boolean isTerminal() {
        return status == TaskStatus.COMPLETED
            || status == TaskStatus.FAILED
            || status == TaskStatus.CANCELLED;
    }
}
```

### 4.2 BackgroundTaskManager 增强

```java
public class BackgroundTaskManager {

    // 改用 ConcurrentHashMap 支持高效查找
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    // --- 现有方法保持不变 ---
    public TaskHandle submit(String taskId, Supplier<String> agentRunner,
                             CancellationToken token) { ... }
    public BackgroundTaskManager createChild() { ... }
    public void cancelAll() { ... }

    // --- NEW: 生命周期操作 ---

    /** 按 taskId 查找 task */
    public Optional<Task> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 取消指定 task */
    public TaskOperationResult cancelTask(String taskId) {
        var task = tasks.get(taskId);
        if (task == null) return TaskOperationResult.notFound(taskId);
        if (task.isTerminal()) return TaskOperationResult.alreadyTerminal(taskId, task.getStatus());
        task.cancel();
        return TaskOperationResult.success(taskId, "cancelled");
    }

    /** 中断指定 task（保留 agent 会话） */
    public TaskOperationResult interruptTask(String taskId) {
        var task = tasks.get(taskId);
        if (task == null) return TaskOperationResult.notFound(taskId);
        if (task.getStatus() != TaskStatus.RUNNING) {
            return TaskOperationResult.invalidState(taskId, task.getStatus(), TaskStatus.RUNNING);
        }
        task.interrupt();
        return TaskOperationResult.success(taskId, "interrupted");
    }

    /** 向指定 task 发送消息（用于 resume） */
    public TaskOperationResult sendMessage(String taskId, String message) {
        var task = tasks.get(taskId);
        if (task == null) return TaskOperationResult.notFound(taskId);
        if (task.isTerminal()) return TaskOperationResult.alreadyTerminal(taskId, task.getStatus());
        task.sendMessage(message);
        return TaskOperationResult.success(taskId, "message_sent");
    }

    /** 列出指定父 task 的所有子 task */
    public List<Task> listByParent(String parentTaskId) {
        return tasks.values().stream()
            .filter(t -> parentTaskId.equals(t.parentTaskId))
            .toList();
    }

    /** 获取所有活跃 task */
    public List<Task> getActiveTasks() {
        return tasks.values().stream()
            .filter(t -> !t.isTerminal())
            .toList();
    }
}
```

### 4.3 新工具设计

#### 4.3.1 TaskSendMessageTool — 向 task 发消息（含 resume）

```
工具名: task_send_message
参数:
  - task_id (String, required): 目标 task 的 ID
  - message (String, required): 要发送的消息内容
  - resume (Boolean, optional, default=true): 发送后是否触发 agent 继续执行

行为:
  1. 从 BackgroundTaskManager 查找 task
  2. 验证 task 存在且未终止
  3. 注入 user message 到 agent
  4. 如果 resume=true，触发 agent continueWithInjectedMessage()
  5. 返回确认 XML

返回（成功）:
  <task-message-sent>
    <task-id>sa-abc12345</task-id>
    <status>message_delivered</status>
    <agent-status>running</agent-status>
  </task-message-sent>

返回（task 不存在）:
  <task-error>
    <task-id>sa-abc12345</task-id>
    <error>not_found</error>
  </task-error>
```

#### 4.3.2 TaskCancelTool — 取消 task

```
工具名: task_cancel
参数:
  - task_id (String, required): 要取消的 task ID

行为:
  1. 查找 task
  2. 取消 CancellationToken（先 NOTIFY→INTERRUPT 阶段）
  3. 如果 agent 在运行中，中断其执行线程
  4. 级联取消所有子 task
  5. 向主 agent 注入取消通知 XML

返回:
  <task-cancelled>
    <task-id>sa-abc12345</task-id>
    <was-running>true</was-running>
    <cancelled-children>2</cancelled-children>
  </task-cancelled>
```

#### 4.3.3 TaskInterruptTool — 中断当前 turn

```
工具名: task_interrupt
参数:
  - task_id (String, required): 要中断的 task ID

行为:
  1. 查找 task
  2. 中断 CancellationToken（CancelReason.NEW_MESSAGE_INTERRUPT，跳过 CLOSE/ABORT）
  3. 不杀 agent，不杀后台进程
  4. 向对话历史注入中断标记

返回:
  <task-interrupted>
    <task-id>sa-abc12345</task-id>
    <can-resume>true</can-resume>
  </task-interrupted>
```

### 4.4 现有 TaskTool 增强

在现有 TaskTool 基础上做最小改动：

**参数增强**: task_id 参数的行为从"复用 ExecutionContext"改为"resume 已有 agent"：

```java
@Override
public ToolCallResult execute(String arguments, ExecutionContext context) {
    // ...existing parsing...
    var taskId = getStringValue(argsMap, "task_id");

    // NEW: 如果提供了 task_id 且 taskManager 中存在该 task
    if (taskId != null && taskManager != null) {
        var existingTask = taskManager.getTask(taskId);
        if (existingTask.isPresent() && !existingTask.get().isTerminal()) {
            // resume 模式：向已有 agent 发送消息
            var result = taskManager.sendMessage(taskId, prompt);
            if (result.isSuccess()) {
                return ToolCallResult.asyncLaunched(taskId,
                    buildResumeNotificationXml(taskId, description));
            }
        }
    }

    // 原有逻辑：创建新 agent
    var subContext = buildSubContext(subagentType, context, taskId, description);
    var subAgent = createAgent(subagentType, subContext);
    // ...existing code...
}
```

**关键设计**: task_id 首次使用时创建新 agent，后续使用同一 task_id 时为 resume 语义。这与 Open Claude Code 和 OpenCode 的行为一致。

### 4.5 中断上下文标记

取消/中断后，向主 agent 的对话历史注入 XML 标记，参考 Codex 的 `<turn_aborted>` 和 core-ai 现有的 `<task-notification>`：

```xml
<!-- 中断标记（注入到被中断 agent 的输出中） -->
<agent-interrupted>
  <task-id>sa-abc12345</task-id>
  <reason>user_cancelled</reason>
  <partial-output>Agent was working on file X. 3 tool calls completed.</partial-output>
</agent-interrupted>
```

**注入时机**: 在 `BackgroundTaskManager.submit()` 的任务完成通知中，如果状态是 cancelled/interrupted，附加该标记。

### 4.6 Resume 实现细节

Resume 是本次设计中最重要的新能力。核心流程：

```
主 Agent 调用 task_send_message(task_id, message)
  → TaskLifecycleManager.sendMessage(taskId, message)
    → BackgroundTaskManager.sendMessage(taskId, message)
      → Task.sendMessage(message):
        1. 验证 task 状态可 resume (RUNNING/INTERRUPTED/WAITING_INPUT)
        2. agent.injectUserMessage(message) — 注入到消息列表
        3. 提交新 future 到 executor:
           agent.continueWithInjectedMessage()
             内部调用 Agent.runTurnsLoop() 从断点继续
        4. 更新 task status → RUNNING
        5. 注册完成回调（通知主 agent）
```

**关键问题**: `Agent.continueWithInjectedMessage()` 需要是幂等的、可重入的。当前 `Agent.run()` 要求 agent 处于 INITED 状态，resume 场景下 agent 可能处于 COMPLETED/RUNNING 等状态。

**解决方案**: 在 Agent 中增加 `continueAfterInterrupt()` 方法：

```java
public String continueAfterInterrupt() {
    // 不重置状态，从当前 messages 末尾继续执行 turn loop
    if (nodeStatus == NodeStatus.FAILED || nodeStatus == NodeStatus.COMPLETED) {
        // 终端状态需要重建
        throw new IllegalStateException("Cannot continue from terminal state");
    }
    setNodeStatus(NodeStatus.RUNNING);
    return runTurnsLoop(); // 继续 turn 循环
}
```

### 4.7 持久化

Resume 依赖对话历史的持久化。当前 Agent 支持通过 `PersistenceProvider` save/load：

```java
// Agent.java (已有)
public void save(String sessionId) {
    if (persistenceProvider != null) {
        persistenceProvider.save(sessionId, this);
    }
}
```

**增强**: Task 级别的持久化：

```java
// 在 Task.sendMessage() 之前确保 agent 状态已保存
public void prepareForResume() {
    if (subAgent.hasPersistenceProvider()) {
        subAgent.save("task:" + taskId);
    }
}
```

Resume 时从持久化恢复的流程：
```
TaskLifecycleManager.resumeTask(taskId, message)
  → 从 PersistenceProvider 加载 agent: persistenceProvider.load("task:" + taskId)
  → 重建 ExecutionContext
  → agent.injectUserMessage(message)
  → agent.continueAfterInterrupt()
```

## 五、API 汇总

### 5.1 新工具（LLM 可调用）

| 工具名 | 参数 | 用途 |
|--------|------|------|
| `task` (增强) | +resume 语义 | 创建新 task 或 resume 已有 task |
| `task_send_message` | task_id, message, resume? | 向运行中 agent 发消息/恢复执行 |
| `task_cancel` | task_id | 取消指定 task |
| `task_interrupt` | task_id | 中断当前 turn，保留 agent |

### 5.2 编程 API（Java）

```java
// TaskLifecycleManager — 新增主入口
public interface TaskLifecycleManager {
    // 创建
    TaskHandle createTask(String taskId, String description, Agent agent,
                          ExecutionContext context, CancellationToken token);

    // 生命周期操作
    TaskOperationResult resumeTask(String taskId, String message);
    TaskOperationResult cancelTask(String taskId);
    TaskOperationResult interruptTask(String taskId);

    // 查询
    Optional<TaskStatus> getTaskStatus(String taskId);
    List<TaskInfo> listActiveTasks();
    List<TaskInfo> listChildTasks(String parentTaskId);
}

// TaskOperationResult
public record TaskOperationResult(
    String taskId,
    boolean success,
    String action,
    String errorCode,
    String errorMessage,
    TaskStatus previousStatus,
    TaskStatus newStatus
) {
    public static TaskOperationResult success(String taskId, String action) { ... }
    public static TaskOperationResult notFound(String taskId) { ... }
    public static TaskOperationResult alreadyTerminal(String taskId, TaskStatus status) { ... }
    public static TaskOperationResult invalidState(String taskId, TaskStatus current, TaskStatus required) { ... }
}
```

## 六、生命周期事件

### 6.1 Task 状态变更事件

```java
public sealed interface TaskLifecycleEvent {
    record TaskCreated(String taskId, String parentTaskId, String agentType) implements TaskLifecycleEvent {}
    record TaskStarted(String taskId) implements TaskLifecycleEvent {}
    record TaskInterrupted(String taskId, CancelReason reason) implements TaskLifecycleEvent {}
    record TaskResumed(String taskId, String message) implements TaskLifecycleEvent {}
    record TaskCompleted(String taskId, String output) implements TaskLifecycleEvent {}
    record TaskFailed(String taskId, String error) implements TaskLifecycleEvent {}
    record TaskCancelled(String taskId, CancelReason reason, String partialOutput) implements TaskLifecycleEvent {}
}
```

### 6.2 事件通知机制

复用现有的 `SessionCommandQueue` 通知机制：

```
BackgroundTaskManager
  → 状态变更时构建 task-notification XML
    → commandQueue.enqueueTaskNotification(xml)
      → TurnDriver 唤醒主 agent
        → 主 agent 在下一个 turn 收到通知
```

**事件类型扩展**（当前只有 task completion 通知）:
```xml
<!-- Resume 确认 -->
<task-notification>
  <task-id>sa-abc12345</task-id>
  <status>resumed</status>
  <message>task_send_message delivered, agent continuing execution</message>
</task-notification>

<!-- Cancel 确认 -->
<task-notification>
  <task-id>sa-abc12345</task-id>
  <status>cancelled</status>
  <reason>user_cancelled</reason>
  <partial-result>Agent produced 3 tool results before cancellation</partial-result>
</task-notification>

<!-- Interrupt 确认 -->
<task-notification>
  <task-id>sa-abc12345</task-id>
  <status>interrupted</status>
  <can-resume>true</can-resume>
</task-notification>
```

## 七、实现计划

### 阶段 1: 基础能力（Task 状态 + Cancel）

**改动范围**: `Task.java`, `BackgroundTaskManager.java`, `TaskTool.java`（微调）

1. Task 增加 `TaskStatus` 状态机
2. BackgroundTaskManager 增加 `getTask()`, `cancelTask()`, `listByParent()` 方法
3. 新增 `task_cancel` 工具
4. Task 中持有 Agent 引用，支持 cancel 时级联取消子 agent

**产出**: 主 agent 可以取消指定后台 agent

### 阶段 2: Resume 能力

**改动范围**: `Agent.java`, `BackgroundTaskManager.java`, 新增 `TaskSendMessageTool.java`

1. Agent 增加 `continueAfterInterrupt()` 方法
2. BackgroundTaskManager 增加 `sendMessage()` 方法
3. 新增 `task_send_message` 工具
4. TaskTool 增强 task_id 的 resume 语义

**产出**: 主 agent 可以向已存在的 agent 发消息并触发继续执行

### 阶段 3: Interrupt 能力 + 上下文标记

**改动范围**: `CancellationToken.java`（已有基础）, 新增 `TaskInterruptTool.java`

1. 新增 `task_interrupt` 工具
2. 中断后注入上下文标记到消息历史
3. 完善事件通知机制

**产出**: 主 agent 可以中断 agent 当前 turn 但不终止它

### 阶段 4: 持久化 + 事件钩子

**改动范围**: `PersistenceProvider.java`, `TaskLifecycleEvent.java`

1. Task 状态持久化支持
2. Resume 时的上下文重建（从 PersistenceProvider 加载）
3. TaskLifecycleEvent 体系
4. Lifecycle Hook 集成

**产出**: 跨进程/跨重启的 task 恢复能力

## 八、文件变更清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `core-ai/src/main/java/ai/core/agent/Task.java` | 修改 | 增加 TaskStatus 状态机、Agent 引用、interrupt()/sendMessage() |
| `core-ai/src/main/java/ai/core/agent/TaskStatus.java` | 新增 | Task 状态枚举 |
| `core-ai/src/main/java/ai/core/agent/TaskOperationResult.java` | 新增 | Task 操作结果 record |
| `core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java` | 修改 | 增加 getTask/cancelTask/interruptTask/sendMessage/listByParent |
| `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java` | 修改 | task_id resume 语义增强 |
| `core-ai/src/main/java/ai/core/tool/tools/TaskSendMessageTool.java` | 新增 | 发送消息/恢复执行的工具 |
| `core-ai/src/main/java/ai/core/tool/tools/TaskCancelTool.java` | 新增 | 取消 task 的工具 |
| `core-ai/src/main/java/ai/core/tool/tools/TaskInterruptTool.java` | 新增 | 中断 task 当前 turn 的工具 |
| `core-ai/src/main/java/ai/core/agent/Agent.java` | 修改 | 增加 continueAfterInterrupt() 方法 |
| `core-ai/src/main/java/ai/core/agent/lifecycle/TaskLifecycleEvent.java` | 新增 | 生命周期事件 sealed 接口 |
| `core-ai/src/main/java/ai/core/session/TaskLifecycleManager.java` | 新增 | 统一 task 生命周期管理入口 |
| `core-ai/src/main/java/ai/core/tool/BuiltinTools.java` | 修改 | 注册新工具 |

## 九、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| Agent.continueAfterInterrupt() 的线程安全 | Agent 状态不一致 | 复用 CancellationToken 的 turn-level 隔离；每次 resume 创建新的 turn token |
| Resume 时的上下文膨胀 | Token 超出限制 | 复用现有 Compression 机制，resume 前检查并压缩 |
| task_id 冲突 | 发消息到错误的 agent | task_id 包含 parent 前缀 + 随机后缀，足够唯一 |
| 持久化 Agent 的版本兼容性 | 反序列化失败 | PersistenceProvider 已有版本管理；resume 失败时返回明确错误 |
| 主 agent 过度使用 resume | 无限增长的消息历史 | TaskTool 的 subagent_type 描述中强调 resume 的使用场景限制 |
