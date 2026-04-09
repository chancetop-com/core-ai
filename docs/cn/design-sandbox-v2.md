# Agent Sandbox Architecture Design V2

> Reference: [Anthropic - Building effective agents: Managed agents](https://www.anthropic.com/engineering/managed-agents)

---

## 1. Design Philosophy

Anthropic 在 Managed Agents 架构中提出了一个核心原则：**virtualize the components of an agent**，借鉴操作系统设计，将 Agent 拆解为三个独立的虚拟化组件：

| Component | Definition | Analogy |
|-----------|-----------|---------|
| **Session** | Append-only event log of everything that happened | Process state |
| **Harness** | The loop that calls Claude and routes tool calls | CPU scheduler |
| **Sandbox** | Execution environment for code and file operations | Virtual memory / filesystem |

核心设计原则：
- **Brain 与 Hands 解耦**：Harness（脑）和 Sandbox（手）完全独立，通过标准接口通信
- **Session 持久化**：事件日志独立于 Harness 和 Sandbox 之外，崩溃可恢复
- **Sandbox 无状态**：容器是"牲畜"（cattle），挂了就丢，换新的继续
- **凭据隔离**：Credentials 永远不进入 Sandbox

---

## 2. core-ai Architecture Mapping

### 2.1 Mapping

```
Anthropic Model              core-ai Mapping
─────────────────────────────────────────────────────────
Session (event log)     →    InProcessAgentSession + PersistenceProvider + AgentEventListener
Harness (agent loop)    →    Agent.chatTurns() + ToolExecutor + AbstractLifecycle
Sandbox (execution)     →    ShellCommandTool / PythonScriptTool (currently on host)
```

### 2.2 Current State

**Session Layer**：
- `InProcessAgentSession`：单线程执行器保证顺序性，通过 `dispatch()` 广播 12 种事件
- `PersistenceProvider`：只保存 `messages + nodeStatus`（通过 `AgentPersistence` 序列化）
- 事件 fire-and-forget，不持久化

**Harness Layer**：
- `Agent.chatTurns()` → `turn()` → `handleFunc()` → `ToolExecutor.execute()`
- `AbstractLifecycle` 提供 beforeTool/afterTool hook
- `ServerPermissionLifecycle` 实现工具审批（`PermissionGate` + `CompletableFuture` 阻塞，300s 超时）
- 多个 tool calls 在 `handleFunc()` 中**顺序执行**（`.stream().map()` 同步）

**Sandbox Layer**：缺失
- `ShellCommandTool` / `PythonScriptTool` 直接 `ProcessBuilder` 在宿主机执行
- 无隔离、无资源限制、继承宿主机所有环境变量

### 2.3 Gap Analysis

| Anthropic 特性 | core-ai 现状 | Gap |
|----------------|-------------|-----|
| Brain-Hands decouple | 工具在宿主机本地执行 | 需要 Sandbox 拦截层 |
| Session = durable event log | PersistenceProvider 只存 messages；events fire-and-forget | 需要 SessionEventLog |
| Container is cattle | 无容器概念 | 需要 AKS Pod 管理 |
| Credential isolation | 工具继承宿主机环境 | 需要隔离设计 |
| Multiple hands per brain | 1 Agent : N tools (本地) | 需要预留 1:N sandbox |
| Lazy provisioning | N/A | 需要 LazySandbox |

---

## 3. core-ai Architecture Gaps & Improvements

### 3.1 Gap 1: Session 不是真正的 Event Log

**问题**：`PersistenceProvider` 只保存 `messages + nodeStatus`。事件（`TextChunkEvent`, `ToolStartEvent`, `ToolResultEvent` 等）是 fire-and-forget 的，dispatch 到 listener 后丢弃。Server 崩溃后事件丢失，无法回放、审计、或做灵活的 context 切片。

**为什么这对 Sandbox 重要**：
- Sandbox Pod 可能随时故障，需要从 event log 恢复上下文
- Sandbox 工具执行结果需要持久化记录，用于审计和 debug
- Anthropic 的 `getEvents()` 支持按位置切片读取，可实现灵活的 context engineering

**改进方案**：引入 `SessionEventLog`

```java
public interface SessionEventLog {
    void append(String sessionId, AgentEvent event);
    List<AgentEvent> getEvents(String sessionId, int fromIndex, int toIndex);
    int getEventCount(String sessionId);
    List<AgentEvent> getEventsSince(String sessionId, int fromIndex);
}
```

**设计考量**：

**为什么不直接增强 PersistenceProvider？**
- `PersistenceProvider` 的语义是保存 Agent 运行时状态（messages snapshot），是"最新状态"
- Event Log 的语义是"所有发生过的事情"，是 append-only 的历史记录
- 两者用途不同：PersistenceProvider 用于 Agent 恢复执行，Event Log 用于审计/回放/debug
- 职责分离更清晰

**性能影响**：
- 每次 dispatch 多一次 MongoDB 写入，但 event 都很小（通常 < 1KB）
- 可以用 MongoDB 的 capped collection 或 TTL index 控制存储量
- 高频事件（`TextChunkEvent`）可以选择性不写 event log，或者 batch 写入

**实现位置**：
- 接口定义在 `core-ai-api`（让 `InProcessAgentSession` 可以引用）
- MongoDB 实现在 `core-ai-server`（`MongoSessionEventLog`）
- CLI 环境不需要 event log（本地执行没有恢复需求）

**Open Questions**：
- Event log 保留多久？建议按 session TTL + 7 天
- 要不要支持 event log 的 compact/compression？初版不需要
- TextChunkEvent 频率很高（每个 token 一个），是否跳过？建议 Phase 1 跳过 streaming events，只记录结构化事件

### 3.2 Gap 2: Sandbox 生命周期没有事件通知

**问题**：现有 12 种 `AgentEvent` 都是 Agent/LLM/Tool 层面的。前端/客户端无法知道 sandbox 正在创建、已就绪、或故障替换。

**为什么重要**：
- Sandbox Pod 创建可能需要 5-30 秒，用户需要知道"正在准备环境"
- 故障替换时需要告知用户"执行环境重建中"
- 管理员需要监控 sandbox 状态

**改进方案**：新增 `SandboxEvent`

```java
public class SandboxEvent extends AgentEvent {
    public String sessionId;
    public String sandboxId;
    public SandboxEventType type;     // CREATING, READY, ERROR, REPLACING, TERMINATED
    public String message;
    public Long durationMs;           // CREATING → READY 耗时
}
```

**在哪里触发**：
- `LazySandbox.ensureReady()` → CREATING / READY / ERROR
- `LazySandbox.close()` → TERMINATED
- 自动替换时 → REPLACING

**如何传递到事件系统**：LazySandbox 需要一个 event dispatcher。通过构造函数注入 `Consumer<SandboxEvent>`，由 `AgentSessionManager` 或 `AgentRunner` 在创建 LazySandbox 时提供。

**设计考量**：

**为什么不用 Lifecycle hook？**
- Lifecycle 的 hook 点是 beforeTool/afterTool，粒度是工具调用级别
- Sandbox 的 CREATING/READY/ERROR 是基础设施级别的事件，不属于任何一次工具调用
- 用独立的事件类型更清晰

### 3.3 Gap 3: Async 工具调用与 Sandbox 的交互

**问题**：`ShellCommandTool` 支持 `async=true` 模式：提交任务到 `AsyncToolTaskExecutor`（虚拟线程池），立即返回 `PENDING`，Agent 后续通过 `poll(taskId)` 查询结果。

如果 sandbox 拦截了 async 工具调用：
1. 任务在哪里执行？sandbox Pod 内还是 server 端？
2. taskId 谁生成？server 端还是 sandbox runtime？
3. poll 请求怎么路由到 sandbox？
4. 如果 sandbox Pod 在 async 执行期间故障，task 怎么处理？

**设计决策**：

**方案 A（推荐）：Sandbox Runtime 内部管理 async**
- Sandbox `execute()` 接收 async 参数 → Runtime 内部提交异步任务 → 返回 taskId
- poll 请求通过 `Sandbox.execute("poll_async_task", taskId)` 转发到 Runtime
- Runtime 维护自己的 task registry

**理由**：
- 保持 sandbox 的封装性——所有执行在 sandbox 内完成
- 避免 server 端和 sandbox 端的 task 状态不一致
- Pod 故障时 task 自然失败，Agent 收到 error 后可重试

**方案 B（备选）：Server 端管理 async，sandbox 只执行同步**
- Sandbox `execute()` 始终同步执行
- async 逻辑在 server 端：server 将 sandbox.execute() 包装在 AsyncToolTaskExecutor 中
- poll 直接走 server 端的 task registry

**理由**：
- 实现更简单，sandbox runtime 不需要 task 管理
- 但同步执行意味着 sandbox HTTP 连接需要保持长时间（最多 600s）

**MVP 决策**：Phase 1 使用方案 B（同步模式）。sandbox 内不支持 async。如果 LLM 请求 `async=true`，sandbox 仍然同步执行，只是 timeout 设长一些。Phase 2 再实现方案 A。

**原因**：
- MVP 目标是验证拦截机制和 Pod 管理，async 增加复杂度但不影响核心价值
- 大多数 Agent 工具调用是短命令（< 30s），async 场景相对少见
- 如果需要 async，server 端包装方式零改动 sandbox runtime

### 3.4 Gap 4: 工具审批（Permission）与 Sandbox 的交互

**问题**：`ServerPermissionLifecycle.beforeTool()` 在 `ToolExecutor.execute()` 中被调用，检查工具是否需要用户审批。审批通过后才进入 `doExecute()`，sandbox 拦截在 `doExecute()` 内部。

执行顺序：
```
ToolExecutor.execute()
  → lifecycles.beforeTool()     // ← 审批在这里（可能阻塞 300s）
  → doExecute()
      → sandbox.shouldIntercept() // ← 拦截在这里
      → sandbox.execute()         // ← 远程执行
  → lifecycles.afterTool()      // ← 结果事件在这里
```

**这意味着**：
1. ✅ 审批在 sandbox 拦截之前——sandbox 工具也会经过审批流程
2. ✅ `ToolStartEvent` 在 sandbox 执行之前发出——前端能看到工具开始
3. ✅ `ToolResultEvent` 在 sandbox 执行之后发出——前端能看到结果
4. ✅ 如果用户 deny，`ToolCallDeniedException` 抛出，sandbox 不会执行

**这是一个非常好的架构巧合**：现有的 lifecycle 机制天然兼容 sandbox 拦截，不需要任何修改。

**但有一个注意点**：`ToolStartEvent` 中的 `diff` 字段（文件操作预览）在 sandbox 模式下可能不准确，因为文件不在宿主机上。Phase 2 文件工具拦截时需要处理这个问题。

### 3.5 Gap 5: ExecutionContext 的 Sandbox 语义

**当前状态**：`ExecutionContext` 有 `customVariables: Map<String, Object>` 可以存任意数据。

**为什么需要专门的 sandbox 字段而不是用 customVariables？**

| 维度 | customVariables | 专用字段 |
|------|----------------|---------|
| 类型安全 | `(Sandbox) context.getCustomVariable("sandbox")` 需要强转 | `context.getSandbox()` 编译时检查 |
| 可发现性 | 其他开发者不知道 map 里有什么 key | IDE 自动补全，一目了然 |
| 序列化 | Sandbox 对象不能序列化到 JSON | 专用字段可以在序列化时跳过 |
| 依赖 | core-ai 模块不需要知道 Sandbox 接口 | core-ai 模块需要引入 Sandbox 接口 |

**决策**：用专用字段。虽然这意味着 core-ai 模块需要定义 `Sandbox` 接口，但 sandbox 是一等公民概念，不应该藏在 map 里。

**Sandbox.execute() 为什么需要 ExecutionContext 参数？**

不是为了执行逻辑（sandbox 内部不需要 LLMProvider、StreamingCallback 等），而是为了：
1. **Tracing**：传递 sessionId/userId 用于日志关联
2. **SandboxEvent dispatch**：sandbox 可以通过 context 中的 callback 发送事件
3. **未来扩展**：context 中可能有 sandbox 需要的信息（如 workspace path override）

### 3.6 Gap 6: 多 Tool Calls 顺序执行的影响

**现状**：`Agent.handleFunc()` 中的 `.stream().map()` 是同步顺序执行。如果 LLM 一次返回 3 个工具调用：
```
Tool 1: run_bash_command (sandbox) → 5s
Tool 2: read_file (host)          → 0.1s
Tool 3: run_python_script (sandbox) → 10s
```
总耗时 = 15.1s，而非并行的 10s。

**这对 Sandbox 的影响**：
- 每次 sandbox tool call 都是一次 HTTP 请求到 Pod，有额外网络延迟
- 顺序执行放大了延迟影响
- 但好处是：不需要处理并发 sandbox 请求的复杂性

**要不要改成并行？**

不改。原因：
1. 工具之间可能有依赖（Tool 2 读取 Tool 1 写入的文件）
2. LLM 是按顺序生成 tool calls 的，暗示了执行顺序
3. 并行执行引入竞态条件，debugging 更难
4. Anthropic 的设计也是 `execute(name, input) → string`，是同步的

### 3.7 Gap 7: Session Rebuild 时的 Sandbox 恢复

**现状**：`AgentSessionManager.getSession()` 发现 session 不在内存中时，调用 `rebuildSession()`：
- 从 `SessionState.AgentConfigSnapshot` 或 `SessionConfig` 重建 Agent
- 工具从 `ToolRegistryService` 重新解析
- 创建新的 `InMemoryToolPermissionStore`（历史 permission decisions 丢失）
- **不恢复 sandbox**——没有 sandboxConfig 信息

**改进**：`SessionState` 需要包含 `SandboxConfig`：

```java
public class SessionState {
    // ... existing fields ...
    public SandboxConfig sandboxConfig;    // new
}
```

Rebuild 时：
```java
private InProcessAgentSession rebuildFromSnapshot(String sessionId, AgentConfigSnapshot snapshot, String userId) {
    // ... existing tool resolution ...
    var contextBuilder = ExecutionContext.builder().sessionId(sessionId).userId(userId);

    if (snapshot.sandboxConfig != null && Boolean.TRUE.equals(snapshot.sandboxConfig.enabled)) {
        contextBuilder.sandbox(new LazySandbox(snapshot.sandboxConfig, sandboxManager));
    }
    // ... build agent ...
}
```

**注意**：rebuild 后的 sandbox 是全新的（新 Pod）。旧 Pod 可能已经被 cleanup。这符合 "cattle not pets" 原则。但意味着：
- 旧 sandbox 中的 `/tmp` 临时文件丢失
- 旧 sandbox 中正在执行的 async task 丢失
- 如果 workspace 是 PVC 挂载的 git repo，代码文件不受影响

---

## 4. Sandbox Interface Design (core-ai module)

### 4.1 Interface

```java
package ai.core.sandbox;

public interface Sandbox extends AutoCloseable {
    /**
     * Check if this sandbox should intercept execution of the named tool.
     */
    boolean shouldIntercept(String toolName);

    /**
     * Execute a tool inside the sandbox.
     * Context provides sessionId/userId for tracing, not for execution logic.
     */
    ToolCallResult execute(String toolName, String arguments, ExecutionContext context);

    SandboxStatus getStatus();

    String getId();
}
```

### 4.2 Why `shouldIntercept()` on the interface?

**替代方案 1**：在 `ToolExecutor` 中硬编码拦截列表
- 问题：ToolExecutor 在 core-ai 模块，不应该知道哪些工具需要沙箱化
- 不同 sandbox 实现可能拦截不同的工具集（代码沙箱 vs 浏览器沙箱）

**替代方案 2**：在 `ToolCall` 上标记 `sandboxable` 属性
- 问题：需要修改所有 ToolCall 实现
- 运行时决策（哪些工具走沙箱）变成了编译时决策

**选择 `shouldIntercept()` 的原因**：
- 决策权在 Sandbox 实现——不同 sandbox 拦截不同工具
- ToolExecutor 只问 "should I?" 然后 delegate，不做任何判断
- 未来 `SandboxRouter` 可以组合多个 sandbox，每个拦截自己关心的工具

### 4.3 Why `extends AutoCloseable`?

Sandbox 持有远程资源（AKS Pod），需要在 session/run 结束时释放。`AutoCloseable` 是 Java 标准的资源管理接口，支持 try-with-resources。

### 4.4 SandboxStatus

```java
public enum SandboxStatus {
    PENDING,       // LazySandbox initial state, no Pod yet
    CREATING,      // Pod provisioning in progress
    READY,         // Pod ready, accepting execute() calls
    EXECUTING,     // Currently executing a tool call
    ERROR,         // Pod failed, needs replacement
    TERMINATED     // Pod deleted, sandbox unusable
}
```

**为什么需要 EXECUTING 状态？**
- 防止并发 execute() 调用导致 Pod 上的竞态（虽然当前 handleFunc 是顺序的，但 SubAgent 可能并发）
- 监控用途：知道 sandbox 当前是否在忙

**为什么需要 ERROR 状态而不是直接 TERMINATED？**
- ERROR 表示"可以自动恢复"（LazySandbox 检测到 ERROR 后 acquire 新 Pod）
- TERMINATED 表示"不可恢复"（sandbox 已被显式关闭）
- 区分这两者让 LazySandbox 知道是否应该尝试重建

### 4.5 ToolExecutor Interception

在 `ToolExecutor.doExecute()` 中，找到 tool 之后、auth check 之前插入：

```java
var sandbox = context.getSandbox();
if (sandbox != null && sandbox.shouldIntercept(tool.getName())) {
    var result = sandbox.execute(tool.getName(), functionCall.function.arguments, context);
    result.withStats("executionMode", "sandbox");
    result.withStats("sandboxId", sandbox.getId());
    return result;
}
```

**为什么在 auth check 之前？**

不对——应该在 auth check **之后**。sandbox 工具也需要经过权限检查。但实际上不需要特殊处理，因为 auth check 发生在 `ToolExecutor.execute()` 的 `beforeTool` lifecycle 中（`ServerPermissionLifecycle`），而 sandbox 拦截在 `doExecute()` 中，lifecycle 已经执行完了。

执行顺序确认：
```
execute()
  → beforeTool lifecycles (包括 permission check)  ← 审批在此
  → doExecute()
      → find tool
      → sandbox intercept                           ← 拦截在此
      → [如果不拦截] auth check, param validation, executeWithTimeout
  → afterTool lifecycles
```

**等等，这里有个问题**：`doExecute()` 中的 auth check（line 69）和 lifecycle 中的 permission check 不是同一个东西：
- lifecycle `beforeTool`：`ServerPermissionLifecycle` 检查是否需要用户审批，可能阻塞等待
- `doExecute` auth check：检查 `tool.isNeedAuth() && !authenticated`，这是 "tool 声明需要认证但 agent 没有认证" 的检查

Sandbox 拦截应该放在 **tool lookup 之后、auth check 之前**（因为 sandbox 自己管理认证）。但实际上 sandbox 拦截后直接返回，不会走到 auth check，所以位置不重要——只要在 `find tool` 之后就行。

**为什么还需要 find tool？**
- 因为需要 `tool.getName()` 来给 `shouldIntercept()` 判断
- 如果 tool 在 toolCalls 中找不到，应该返回 "tool not found"，而不是问 sandbox

**Edge Case**：如果 sandbox 配置了要拦截某个 tool，但这个 tool 没有注册在 Agent 的 toolCalls 中——应该怎么办？
- 当前设计：tool 找不到 → 返回 "tool not found"，sandbox 不会被问到
- 这是正确的行为：sandbox 是执行环境的替代，不是工具的替代。工具定义（name, parameters, description）仍然由 Agent 注册的 ToolCall 提供

### 4.6 Intercepted Tool Set

```java
public final class SandboxConstants {
    // MVP: only code execution tools
    public static final Set<String> CODE_EXECUTION_TOOLS = Set.of(
        "run_bash_command",
        "run_python_script"
    );

    // Phase 2: add file operation tools
    public static final Set<String> FILE_OPERATION_TOOLS = Set.of(
        "read_file", "write_file", "edit_file", "glob_file", "grep_file"
    );
}
```

**为什么 MVP 只拦截代码执行？**
- 代码执行（shell/python）是安全风险最高的——可以执行任意命令
- 文件工具只操作文件系统，风险较低
- 文件工具拦截需要在 sandbox runtime 中实现完整的文件操作 API，工作量大
- 文件工具有 path rewriting 问题（宿主机路径 vs sandbox 内路径）

**Phase 2 拦截文件工具时的路径问题**：
- Agent 看到的路径：`/workspace/src/main.py`
- 宿主机路径：`/mnt/storage/repos/{workspaceId}/src/main.py`
- Sandbox 内路径：`/workspace/src/main.py`（PVC subPath 挂载）

当文件工具被拦截时，arguments 中的 `file_path` 需要映射。两种方案：
- **方案 A**：让 Agent 的 system prompt 告知所有文件路径以 `/workspace/` 开头
- **方案 B**：sandbox runtime 做路径前缀校验和映射

推荐方案 A——更简单，且 LLM 很擅长遵循路径约定。

---

## 5. Sandbox Lifecycle Design

### 5.1 Cattle, Not Pets

Anthropic 的核心原则：*"Containers become cattle rather than pets—if one fails, the harness catches the error and can reinitialize a new container."*

```
                    ┌─────────────┐
                    │ PENDING     │  (LazySandbox created, no Pod yet)
                    └──────┬──────┘
                           │ first shouldIntercept() == true → execute()
                    ┌──────▼──────┐
                    │ CREATING    │  (AKS Pod provisioning)
                    └──────┬──────┘
                           │ Pod ready + health check pass
                    ┌──────▼──────┐
              ┌────►│ READY       │◄────── auto-replace on error
              │     └──────┬──────┘
              │            │ execute()
              │     ┌──────▼──────┐
              │     │ EXECUTING   │
              │     └──────┬──────┘
              │            │ complete
              └────────────┘
                           │ close() / timeout / unrecoverable error
                    ┌──────▼──────┐
                    │ TERMINATED  │
                    └─────────────┘
```

### 5.2 LazySandbox

```java
public class LazySandbox implements Sandbox {
    private final SandboxConfig config;
    private final SandboxManager manager;
    private final Consumer<SandboxEvent> eventDispatcher;  // for lifecycle events
    private volatile Sandbox delegate;
    private volatile SandboxStatus status = SandboxStatus.PENDING;
}
```

**为什么用 Lazy 模式？**
- 很多 Agent run 可能不调用任何 sandboxed tool（只用 web search、MCP 等）
- Pod 创建需要 5-30 秒，不应该阻塞 Agent 启动
- Anthropic 原文：*"sessions that didn't need a container right away didn't wait for one"*

**Thread Safety 设计**：
```java
private void ensureReady() {
    if (delegate != null && delegate.getStatus() != SandboxStatus.ERROR) {
        return; // fast path, no lock
    }
    synchronized (this) {
        if (delegate != null && delegate.getStatus() != SandboxStatus.ERROR) {
            return; // double-check
        }
        // Replace failed sandbox
        if (delegate != null && delegate.getStatus() == SandboxStatus.ERROR) {
            dispatchEvent(SandboxEventType.REPLACING);
            manager.release(delegate);
        }
        // Acquire new sandbox
        dispatchEvent(SandboxEventType.CREATING);
        status = SandboxStatus.CREATING;
        try {
            delegate = manager.acquire(config);
            status = SandboxStatus.READY;
            dispatchEvent(SandboxEventType.READY, acquireDurationMs);
        } catch (Exception e) {
            status = SandboxStatus.ERROR;
            dispatchEvent(SandboxEventType.ERROR, e.getMessage());
            throw e;  // let ToolExecutor catch and return as failed ToolCallResult
        }
    }
}
```

**为什么用 synchronized 而不是 ReentrantLock？**
- `ensureReady()` 只在 tool execution 时调用，而 `handleFunc()` 是顺序的
- 实际上几乎不会有锁竞争
- synchronized 更简单、JVM 做了偏向锁优化

**自动恢复的边界**：
- 什么算 ERROR？HTTP 超时、Connection refused、Pod status != Running
- 什么不算 ERROR？工具执行返回非零 exit code（这是正常的工具结果）
- 恢复几次？无限次（每次 ensureReady 检测到 ERROR 就替换）
- 会不会无限循环？不会——如果 acquire() 本身也失败（K8s API 挂了），异常会抛出给 Agent，LLM 看到 error 后会停止调用该工具

### 5.3 Sandbox 与 Session/Run 生命周期绑定

| 场景 | Sandbox 创建 | Sandbox 销毁 |
|------|-------------|-------------|
| AgentRunner (one-shot run) | LazySandbox in ExecutionContext | run 完成后 finally 中 close() |
| AgentSession (interactive) | LazySandbox in ExecutionContext | session close 时 close() |
| Session rebuild | 新建 LazySandbox（旧 Pod 已回收） | 同上 |
| Server shutdown | N/A | SandboxManager.destroyAll() |
| Pod 超时 | N/A | SandboxCleanupJob 定时检查并回收 |

**为什么不共享 Sandbox 跨多个 Run？**
- 安全隔离：不同 run 可能是不同用户/不同 Agent
- 简化状态管理：不需要清理上一次 run 的残留文件
- Anthropic 模型也是每个 session 独立的 sandbox

---

## 6. AKS Sandbox Implementation (core-ai-server module)

### 6.1 Module Structure

```
core-ai-server/src/main/java/ai/core/server/sandbox/
├── SandboxProvider.java              // provisioning interface
├── SandboxManager.java               // lifecycle + active tracking
├── SandboxCleanupJob.java            // scheduled cleanup
├── LazySandbox.java                  // lazy provisioning
├── SandboxConstants.java             // intercepted tool set
├── aks/
│   ├── AksSandboxProvider.java       // K8s Pod lifecycle
│   ├── AksSandbox.java              // execute via HTTP to Pod
│   ├── AksClient.java               // lightweight K8s REST client
│   └── PodSpecBuilder.java          // Pod manifest generation
├── workspace/
│   ├── WorkspaceService.java         // Git clone/sync
│   └── WorkspaceInfo.java
└── web/
    └── SandboxController.java        // REST API
```

### 6.2 AksClient — K8s API 交互

**为什么不用 fabric8 kubernetes-client？**
- fabric8 引入 ~30+ 个 jar 依赖，增加 classpath 复杂性和冲突风险
- 我们只需要 4 个 API：createPod, getPod, deletePod, listPods
- 项目已有 HTTP client 能力（core.framework），直接用

**为什么不用 kubectl exec？**
- `kubectl exec` 需要在 server 上安装 kubectl + kubeconfig
- 每次 exec 都 fork 新进程，性能差
- 不如直接 HTTP 调用 Pod 内 Runtime

**AksClient 设计**：
```java
public class AksClient {
    private final String apiServer;
    private final String token;

    public PodInfo createPod(String namespace, String podJson) {
        // POST /api/v1/namespaces/{ns}/pods
        // Authorization: Bearer {token}
        // Content-Type: application/json
    }

    public void waitForReady(String namespace, String name, Duration timeout) {
        // Poll GET /api/v1/namespaces/{ns}/pods/{name}
        // Check: status.phase == "Running" && conditions[type=Ready].status == "True"
        // Poll interval: 2 seconds
        // Timeout: configurable, default 60s
    }

    public String getPodIp(String namespace, String name) {
        // GET pod → status.podIP
    }

    public void deletePod(String namespace, String name) {
        // DELETE /api/v1/namespaces/{ns}/pods/{name}
        // gracePeriodSeconds: 5
    }
}
```

**waitForReady 的 timeout 策略**：
- 默认 60 秒
- 如果超时，创建失败，LazySandbox 标记 ERROR
- 下次 tool call 时会尝试创建新的 Pod
- **不做 warm pool 的原因（MVP）**：warm pool 增加复杂度（pool 大小、replenish 策略、闲置资源浪费），MVP 先验证基础流程

### 6.3 AksSandbox — Remote Execution

```java
public class AksSandbox implements Sandbox {
    private final String podName;
    private final String podIp;
    private volatile SandboxStatus status = SandboxStatus.READY;
    private final Instant createdAt = Instant.now();
    private final int timeoutSeconds;

    @Override
    public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
        status = SandboxStatus.EXECUTING;
        try {
            var request = new ExecuteRequest(toolName, arguments);
            var response = httpPost(
                "http://" + podIp + ":8080/execute",
                JSON.toJSON(request),
                30_000L  // per-tool timeout, not sandbox TTL
            );
            status = SandboxStatus.READY;
            return parseResponse(response);
        } catch (Exception e) {
            status = SandboxStatus.ERROR;
            return ToolCallResult.failed("Sandbox execution failed: " + e.getMessage());
        }
    }
}
```

**HTTP timeout 的选择**：
- per-tool timeout（30s for shell, 60s for python），不是 sandbox TTL
- sandbox TTL 由 SandboxCleanupJob 管理
- 如果 HTTP 超时但 Pod 还在运行，标记 ERROR 触发替换
- **问题**：正在执行的长命令（如编译）可能被误杀。解决：让 timeout 跟随工具参数中的 `async` 标记

**为什么用 HTTP 而不是 gRPC？**
- 项目没有 gRPC 依赖，引入增加复杂度
- HTTP + JSON 更简单，调试方便（curl 直接测试）
- 性能差异在 sandbox 场景下忽略不计（每次调用通常 > 1s）

### 6.4 PodSpecBuilder

**Pod Security Context 的每个选项的原因**：

```yaml
securityContext:
  runAsNonRoot: true              # 防止容器内进程以 root 运行
  runAsUser: 1001                 # 固定 UID，不用 root
  fsGroup: 1001                   # 确保挂载卷的文件归属正确
  seccompProfile:
    type: RuntimeDefault          # 启用默认 seccomp profile，限制系统调用

containers:
  - securityContext:
      allowPrivilegeEscalation: false   # 禁止 setuid/setgid 提权
      capabilities:
        drop: ["ALL"]                   # 丢弃所有 Linux capabilities
      readOnlyRootFilesystem: true      # 根文件系统只读
```

**为什么 readOnlyRootFilesystem: true？**
- 防止 Agent 修改系统文件（/etc/passwd 等）
- 防止安装恶意软件
- /tmp 通过 emptyDir 单独挂载为可写

**emptyDir 的 sizeLimit**：
```yaml
volumes:
  - name: tmp
    emptyDir:
      sizeLimit: "100Mi"    # 防止 /tmp 被填满导致 node 磁盘压力
```

**为什么不用 PersistentVolume 作为 /tmp？**
- /tmp 是临时的，Pod 销毁后不需要保留
- emptyDir 使用 node 的本地存储，更快
- PV 需要 provision/cleanup lifecycle，增加复杂度

### 6.5 SandboxManager

```java
public class SandboxManager {
    private final SandboxProvider provider;
    private final ConcurrentMap<String, SandboxEntry> activeSandboxes = new ConcurrentHashMap<>();

    public Sandbox acquire(SandboxConfig config) { ... }
    public void release(Sandbox sandbox) { ... }
    public void cleanupExpired() { ... }
    public void destroyAll() { ... }
}
```

**为什么 SandboxManager 而不是直接用 SandboxProvider？**
- Manager 负责全局视图：追踪所有活跃 sandbox，定时清理过期的
- Provider 只负责单个 sandbox 的创建和销毁
- Manager 是 server 级 singleton，Provider 可以有多种实现

**cleanupExpired 的策略**：
- 每 5 分钟执行一次
- 检查每个 sandbox 的 `createdAt + timeoutSeconds`
- 超时的直接 `provider.destroy(sandbox)` 然后从 map 移除
- **问题**：如果 sandbox 正在执行 tool call 时超时怎么办？
  - 不主动杀——cleanup 只处理 createdAt 超过 TTL 的
  - 正在执行的 tool call 有自己的 HTTP timeout
  - 如果 tool call 完成后 sandbox 已超时，LazySandbox 下次 ensureReady() 会发现 TERMINATED，acquire 新的

---

## 7. Sandbox Runtime (In-Pod Service)

### 7.1 Technology Choice

**Go vs Python vs Java**

| 维度 | Go | Python | Java |
|------|-----|--------|------|
| 镜像大小 | ~10MB (scratch) | ~150MB (slim) | ~200MB (JRE) |
| 启动速度 | < 50ms | ~500ms | ~2s |
| 进程管理 | `os/exec` 成熟 | `subprocess` 成熟 | `ProcessBuilder` 成熟 |
| HTTP server | stdlib `net/http` | FastAPI/Flask | 需要框架 |
| 错误隔离 | 天然进程隔离 | GIL 限制并发 | 线程模型更复杂 |

**选择 Go**：
- 镜像最小，Pod 拉取最快
- 启动最快，减少 cold start 延迟
- 单二进制部署，无运行时依赖
- `os/exec` 的 timeout/kill 管理清晰

**为什么不用 Java（和 core-ai 保持一致）？**
- Runtime 的代码量很小（< 200 行），用什么语言无所谓
- Java 镜像大（~200MB）且启动慢（~2s），在追求快速 Pod ready 的场景下是劣势
- Runtime 不需要和 core-ai 共享代码

### 7.2 API Contract

```
GET  /health
Response: 200 {"status": "ok"}

POST /execute
Request:  {"tool": "run_bash_command", "arguments": "{\"command\":\"ls\",\"workspace_dir\":\"/workspace\"}"}
Response: {"status": "completed|failed|timeout", "result": "...", "duration_ms": 123}
```

**为什么 arguments 是 string 而不是 parsed JSON？**
- 保持和 `ToolCall.execute(String arguments)` 一致
- Runtime 不需要理解所有工具参数的 schema
- 只需要解析它关心的字段（command, workspace_dir 等）

**Response status 的设计**：
- `completed`：命令正常退出（exit code 0）
- `failed`：命令非零退出或内部错误
- `timeout`：命令超时被杀

**为什么不区分 "命令返回非零" 和 "runtime 错误"？**
- 对 Agent/LLM 来说没有区别——都是 "这个操作没成功"
- 错误原因在 result 文本中（"exit code 1: ..." vs "internal error: ..."）
- 保持简单

### 7.3 Security Constraints

```go
func sanitizePath(requested, defaultPath string) string {
    abs := filepath.Clean(requested)
    if strings.HasPrefix(abs, "/workspace") || strings.HasPrefix(abs, "/tmp") {
        return abs
    }
    return defaultPath
}

func minimalEnv() []string {
    return []string{
        "PATH=/usr/local/bin:/usr/bin:/bin",
        "HOME=/tmp",
        "LANG=en_US.UTF-8",
        "PYTHONIOENCODING=utf-8",
    }
}
```

**为什么限制路径只能 /workspace 和 /tmp？**
- /workspace：工作代码目录（PVC 挂载）
- /tmp：临时文件（emptyDir 挂载，可写）
- 阻止 Agent 读取 /etc、/proc 等系统目录
- 即使 readOnlyRootFilesystem 阻止了写入，读取也不应该允许

**为什么清空环境变量？**
- 防止任何 K8s/cloud 注入的 secrets 泄露（如 ServiceAccount token）
- 只保留必要的 PATH、HOME、LANG
- Agent 执行的命令不需要云凭据

**filepath.Clean 的重要性**：
- 防止 `../../etc/passwd` 这种 path traversal
- Go 的 `filepath.Clean` 规范化路径后，`strings.HasPrefix` 才安全

### 7.4 Output Truncation

```go
const maxOutputSize = 30 * 1024  // 30KB, matches ShellCommandTool behavior

func truncateOutput(output string) string {
    if len(output) > maxOutputSize {
        return output[:maxOutputSize] + "\n... [output truncated at 30KB]"
    }
    return output
}
```

**为什么 30KB？**
- 和 `ShellCommandTool` 的现有行为一致
- LLM 的 context window 有限，过大的 output 浪费 tokens
- 如果 Agent 需要完整输出，可以将其写入文件然后 read_file

### 7.5 Dockerfile

```dockerfile
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY . .
RUN CGO_ENABLED=0 go build -o /sandbox-runtime .

FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash coreutils findutils grep git curl jq && \
    rm -rf /var/lib/apt/lists/*
RUN groupadd -g 1001 sandbox && useradd -u 1001 -g sandbox -m sandbox
COPY --from=builder /sandbox-runtime /usr/local/bin/sandbox-runtime
USER 1001
WORKDIR /workspace
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/sandbox-runtime"]
```

**为什么 base image 是 python:3.12-slim 而不是 scratch/alpine？**
- 需要 Python runtime（用于 `run_python_script`）
- 需要 bash、git、curl 等常用命令行工具
- scratch 什么都没有，alpine 需要额外装 python

**为什么不用更大的 image（如 ubuntu:latest）？**
- 安全原则：最小化攻击面
- 镜像越大，拉取越慢，cold start 越久
- 只装 Agent 常用的工具，不装多余的

---

## 8. Integration Points

### 8.1 AgentRunner Integration

```java
// buildAgent()
var sandboxConfig = config != null ? config.sandboxConfig : definition.sandboxConfig;
if (sandboxConfig != null && Boolean.TRUE.equals(sandboxConfig.enabled)) {
    var sandbox = new LazySandbox(sandboxConfig, sandboxManager, event -> {
        // dispatch sandbox events to run transcript
        LOGGER.info("sandbox event: {} for run {}", event.type, runId);
    });
    contextBuilder.sandbox(sandbox);
}
```

**为什么在 buildAgent 里而不是 executeAgent 里？**
- sandbox 需要注入到 ExecutionContext，而 context 在 buildAgent 时构建
- executeAgent 拿到的是已构建好的 Agent，不方便再修改 context

**finally 释放**：
```java
finally {
    var sandbox = agent.getExecutionContext().getSandbox();
    if (sandbox != null) {
        try { sandbox.close(); }
        catch (Exception e) { LOGGER.warn("sandbox cleanup failed", e); }
    }
}
```

**为什么 catch Exception 而不是 let it propagate？**
- sandbox cleanup 失败不应该覆盖原始的执行结果/异常
- SandboxCleanupJob 会兜底清理泄露的 Pod

### 8.2 AgentSessionManager Integration

**关键设计**：session 的 sandbox 需要在 `sessionSandboxes` map 中追踪，因为：
1. `closeSession()` 需要释放 sandbox
2. session rebuild 时需要知道是否需要新建 sandbox
3. 管理员需要能查看 session 关联的 sandbox 状态

```java
private final ConcurrentMap<String, Sandbox> sessionSandboxes = new ConcurrentHashMap<>();
```

### 8.3 ServerModule Registration

```java
// Conditional: only if sandbox is enabled
if ("true".equals(System.getProperty("sys.sandbox.enabled"))) {
    bind(AksClient.class);
    bind(AksSandboxProvider.class);
    bind(SandboxManager.class);
    bind(WorkspaceService.class);
    schedule().fixedRate("sandbox-cleanup", bind(SandboxCleanupJob.class), Duration.ofMinutes(5));
    http().route(HTTPMethod.GET, "/api/sandbox/active", bind(SandboxController.class));
    http().route(HTTPMethod.POST, "/api/sandbox/:id/terminate", bind(SandboxController.class));
}
```

**为什么是 conditional？**
- 不是所有部署都有 AKS 集群
- 开发/测试环境可能不需要 sandbox
- 不注册就不需要 AKS 配置，backward compatible

---

## 9. Data Model

### 9.1 SandboxConfig

```java
public class SandboxConfig {
    public Boolean enabled;               // default false
    public String image;                  // default "core-ai-sandbox:latest"
    public Integer memoryLimitMb;         // default 512, max 2048
    public Integer cpuLimitMillicores;    // default 500, max 2000
    public Integer timeoutSeconds;        // sandbox TTL, default 3600, max 7200
    public Boolean networkEnabled;        // default false
    public String gitRepoUrl;            // optional, git repo to clone
    public String gitBranch;             // default "main"
}
```

**为什么不把 Intercepted tool set 放在 SandboxConfig 里？**
- 拦截哪些工具是 Sandbox 实现决定的，不是用户配置
- 用户不应该能选择 "只拦截 python 不拦截 shell"——这会导致不一致的安全策略
- 未来如果真需要，可以加 `interceptOverride` 字段

**为什么有 max 限制？**
- 防止用户配置 100GB 内存把 node 打爆
- 在 `AksSandboxProvider.acquire()` 中校验，超出限制返回 error

---

## 10. Credential Isolation

### 10.1 Threat Model

攻击场景：Agent（被 prompt injection 影响）尝试窃取凭据
- 读取环境变量中的 API key → 环境变量已清空
- 访问 K8s ServiceAccount token → 路径不存在或只读 rootfs
- 访问 cloud metadata endpoint → NetworkPolicy 阻断
- 通过 git remote URL 提取 token → WorkspaceService 在克隆时已 strip

### 10.2 Bundled Auth for Git (Phase 2)

Anthropic 的做法：*"use each repository's access token to clone the repo during sandbox initialization and wire it into the local git remote."*

core-ai 实现：
```
1. WorkspaceService 在 server 端用 Deploy Key 克隆
2. 克隆后修改 .git/config，将 remote URL 改为 /workspace（本地路径）
3. PVC 挂载到 Pod，Agent 可以 git log/diff/status
4. Phase 2: git push 通过 server 端 proxy
```

**为什么不直接把 Deploy Key 放进 Pod？**
- Agent 可以 `cat /root/.ssh/id_rsa` 窃取 key
- 即使限制了路径，Agent 可以 `env | grep SSH` 或 `find / -name id_rsa`
- Anthropic 的原则：*"the structural fix was to make sure the tokens are never reachable from the sandbox"*

### 10.3 MCP/API Tools — 天然隔离

- MCP 工具在 server 进程中执行（McpClientManager），不经过 sandbox
- ServiceApi 工具同理
- sandbox 只拦截 code execution tools，其他工具走 host 执行

这意味着：Agent 调用 MCP tool 时，ToolExecutor 先问 `sandbox.shouldIntercept("mcp_tool_name")`，得到 false，然后正常在 host 执行。无需特殊处理。

---

## 11. Error Handling and Resilience

### 11.1 Error Recovery Matrix

| 故障 | 检测方式 | 恢复策略 | 用户感知 |
|------|---------|---------|---------|
| Pod 创建超时 | waitForReady timeout | acquire() 抛异常 → LLM 看到 error | "Sandbox 创建超时" |
| Pod OOMKilled | execute() HTTP error 或 getPod status | AksSandbox.status = ERROR → 下次自动替换 | "命令执行失败，环境重建中" |
| Runtime HTTP 超时 | HttpClient timeout | AksSandbox.status = ERROR → 自动替换 | "命令超时" |
| Pod 被 K8s evict | execute() Connection refused | AksSandbox.status = ERROR → 自动替换 | 透明替换 |
| K8s API 不可用 | AksClient HTTP error | acquire() 失败 → LLM 看到 error | "无法创建执行环境" |
| Server crash | Session lost | Session rebuild → new LazySandbox | Client 重连后透明恢复 |

### 11.2 Error 返回格式

Sandbox error 通过 `ToolCallResult.failed()` 返回给 LLM：

```
Error: Sandbox execution failed: Connection refused (sandbox-a1b2c3d4 is being replaced, retry expected)
```

**为什么在错误消息中加 "retry expected"？**
- 引导 LLM 重试而不是放弃
- LLM 看到 "retry expected" 通常会再调一次相同的工具
- 重试时 LazySandbox 已经准备好新 Pod

### 11.3 什么不恢复

| 丢失的状态 | 影响 | 是否可接受 |
|-----------|------|----------|
| /tmp 中的临时文件 | Agent 写的中间文件丢失 | 可接受——LLM 会重新生成 |
| 正在执行的命令 | 命令结果丢失 | 可接受——LLM 看到 error 会重试 |
| pip install 的包 | 需要重新安装 | 可接受——但频繁替换会慢，Phase 2 可用预装镜像缓解 |

---

## 12. Security Design

### 12.1 Defense in Depth

```
Layer 1: Pod Security Context (non-root, drop caps, readonly rootfs)
Layer 2: Sandbox Runtime path sanitization (/workspace, /tmp only)
Layer 3: Minimal environment variables (no secrets)
Layer 4: NetworkPolicy (block internal network + metadata)
Layer 5: Resource limits (memory, CPU, disk, PIDs)
Layer 6: TTL timeout (SandboxCleanupJob)
Layer 7 (Phase 2): Kata/gVisor kernel isolation
```

### 12.2 NetworkPolicy

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: sandbox-deny-all
  namespace: core-ai-sandbox
spec:
  podSelector:
    matchLabels:
      component: sandbox
  policyTypes: ["Egress"]
  egress: []  # Deny all by default
---
# Applied only when networkEnabled=true
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: sandbox-allow-external
spec:
  podSelector:
    matchLabels:
      component: sandbox
      network-enabled: "true"
  policyTypes: ["Egress"]
  egress:
  - to:
    - ipBlock:
        cidr: 0.0.0.0/0
        except:
        - 10.0.0.0/8
        - 172.16.0.0/12
        - 192.168.0.0/16
        - 169.254.169.254/32
```

**为什么有两个 NetworkPolicy？**
- 第一个是 default deny——所有 sandbox Pod 默认无网络
- 第二个是 opt-in——只有 `network-enabled: "true"` label 的 Pod 才有外部网络
- PodSpecBuilder 根据 `config.networkEnabled` 决定是否加这个 label

**为什么阻断 10.0.0.0/8 等内网？**
- 防止 Agent 扫描内网服务（数据库、admin panel 等）
- 防止 Agent 访问其他 Pod（K8s 集群内 Pod 通信通常在 10.x）

**为什么阻断 169.254.169.254？**
- 云平台 metadata endpoint，可以获取 instance credentials
- Azure: Instance Metadata Service (IMDS)
- 如果被访问，Agent 可能获取 Azure 凭据

### 12.3 Resource Limits

| Resource | Default | Max | Why |
|----------|---------|-----|-----|
| Memory | 512Mi | 2Gi | OOMKill 保护 node；大部分工具不需要 > 512Mi |
| CPU | 500m | 2000m | 公平共享 node CPU；throttle 不会杀 Pod |
| /tmp disk | 100Mi | 500Mi | 防止填满 node 磁盘；100Mi 足够临时文件 |
| PIDs | 256 | 256 | 防止 fork bomb |
| Sandbox TTL | 3600s | 7200s | 防止僵尸 Pod；1 小时足够大多数 session |
| Command timeout | 30s | 600s (async) | 防止 hang |
| Output | 30KB | 30KB | 和现有 ShellCommandTool 一致 |

---

## 13. Configuration

```properties
# sys.properties
sys.sandbox.enabled=true
sys.sandbox.aks.api-server=https://aks-cluster.hcp.eastus.azmk8s.io
sys.sandbox.aks.token=eyJ...
sys.sandbox.aks.namespace=core-ai-sandbox
sys.sandbox.aks.default-image=core-ai-sandbox:latest
sys.sandbox.aks.pod-ready-timeout-seconds=60
sys.sandbox.aks.storage.pvc-name=sandbox-workspaces
sys.sandbox.aks.storage.base-path=/mnt/storage
sys.sandbox.cleanup-interval-minutes=5
```

**为什么 token 在 properties 里而不是环境变量？**
- core.framework 的配置管理统一用 properties
- 生产部署时可以通过 K8s Secret mount 覆盖
- 也可以用环境变量 override（core.framework 支持 `SYS_SANDBOX_AKS_TOKEN`）

---

## 14. REST API

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| GET | `/api/sandbox/active` | List active sandboxes | Admin |
| GET | `/api/sandbox/:id/status` | Get sandbox status | Admin |
| POST | `/api/sandbox/:id/terminate` | Force-terminate | Admin |
| POST | `/api/workspaces` | Create workspace | User |
| PUT | `/api/workspaces/:id/sync` | Sync workspace | User |
| DELETE | `/api/workspaces/:id` | Delete workspace | User |

**为什么 sandbox 管理是 Admin only？**
- 普通用户不应该能看到其他用户的 sandbox
- 普通用户不应该能终止其他用户的 sandbox
- sandbox 生命周期由系统自动管理

---

## 15. Observability

### 15.1 Existing Event Integration

Sandbox 执行结果通过现有 `ToolResultEvent` 传递，在 stats 中标记来源：
```java
result.withStats("executionMode", "sandbox");
result.withStats("sandboxId", sandbox.getId());
result.withStats("podName", podName);
```

### 15.2 New SandboxEvent

通过 `AgentEventListener.onSandbox(SandboxEvent)` 传递到 SSE 客户端。

### 15.3 Logging

关键日志点：
- `SandboxManager.acquire()` — sandbox 创建，包含 podName, duration
- `AksSandbox.execute()` — 每次工具调用，包含 toolName, duration, podIp
- `SandboxManager.release()` — sandbox 释放
- `SandboxCleanupJob.cleanupExpired()` — 过期 sandbox 清理

### 15.4 Metrics (Phase 2)

- `sandbox_acquire_duration_seconds` — histogram
- `sandbox_execute_duration_seconds` — histogram, label: tool_name
- `sandbox_active_count` — gauge
- `sandbox_error_total` — counter, label: error_type
- `sandbox_replaced_total` — counter

---

## 16. Evolution Roadmap

### Phase 1: MVP

| # | Task | Effort | Module |
|---|------|--------|--------|
| 1 | `Sandbox` interface + `SandboxStatus` + `SandboxConstants` | 1d | core-ai |
| 2 | `ExecutionContext.sandbox` field | 0.5d | core-ai |
| 3 | `ToolExecutor` interception (3 lines) | 0.5d | core-ai |
| 4 | `SandboxEvent` event type | 1d | core-ai-api + core-ai |
| 5 | `SandboxConfig` data model | 0.5d | core-ai-server |
| 6 | `AgentDefinition` + `AgentPublishedConfig` field | 0.5d | core-ai-server |
| 7 | `AksClient` (K8s REST API) | 2d | core-ai-server |
| 8 | `PodSpecBuilder` | 1d | core-ai-server |
| 9 | `AksSandboxProvider` + `AksSandbox` | 2d | core-ai-server |
| 10 | `LazySandbox` + `SandboxManager` + `SandboxCleanupJob` | 2d | core-ai-server |
| 11 | `AgentRunner` integration | 1d | core-ai-server |
| 12 | `AgentSessionManager` integration + `SessionState` | 1d | core-ai-server |
| 13 | `ServerModule` registration | 0.5d | core-ai-server |
| 14 | Sandbox REST API | 1d | core-ai-server |
| 15 | Sandbox Runtime (Go HTTP server) | 2d | sandbox-runtime/ |
| 16 | Sandbox Runtime Docker image | 1d | sandbox-runtime/ |
| 17 | K8s NetworkPolicy manifests | 0.5d | k8s/ |
| 18 | Integration tests | 2d | core-ai-server |

### Phase 2: Workspace + File Tools + Performance

- `SessionEventLog` interface + `MongoSessionEventLog`
- `WorkspaceService` — Git clone/sync with deploy keys
- File tool interception — read/write/edit/glob/grep
- Warm Pool — pre-warmed Pod pool
- Kata/gVisor RuntimeClass
- Async tool support in sandbox runtime
- Observability metrics

### Phase 3: Full Features

- Read-Write workspace (Git Worktree per session)
- Git push via server proxy (Bundled Auth)
- Playwright browser sandbox
- DLP scanning
- Audit logging to trace system
- Multi-sandbox per agent (`SandboxRouter`)

---

## 17. Design Decision Log

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Sandbox interface location | core-ai module | Type safety in ToolExecutor; sandbox is first-class concept |
| Sandbox backend (MVP) | AKS Pod only | Production target; skip Docker intermediate |
| K8s client | Lightweight HTTP (AksClient) | Avoid fabric8's 30+ jar deps |
| Runtime language | Go | Smallest image, fastest startup |
| Intercepted tools (MVP) | Shell + Python only | Highest risk; file tools add path complexity |
| Creation timing | Lazy (LazySandbox) | Avoid 5-30s startup for non-sandbox agents |
| Error recovery | Auto-replace via LazySandbox | Cattle not pets; transparent to LLM |
| Async in sandbox (MVP) | Sync only (server-side async wrapper) | Simplicity; async is rare |
| Credential model | Never in sandbox | Anthropic vault pattern; structural isolation |
| Session event log | Phase 2 (not MVP) | Nice-to-have for recovery; MVP can live without |
| Multi-sandbox | Phase 3 (interface ready) | `shouldIntercept()` supports future SandboxRouter |
| Permission + sandbox | No change needed | Lifecycle hooks fire before sandbox intercept |

---

## Appendix A: Comparison with Anthropic Managed Agents

| Anthropic Design | core-ai Implementation |
|-----------------|----------------------|
| `execute(name, input) → string` | `Sandbox.execute(toolName, arguments, context) → ToolCallResult` |
| `provision({resources})` | `SandboxProvider.acquire(SandboxConfig)` |
| Session = append-only log | Phase 2: `SessionEventLog`; MVP: `PersistenceProvider` |
| `wake(sessionId)` | `AgentSessionManager.getSession(id, state)` rebuild |
| `getSession(id)` → event log | Phase 2: `SessionEventLog.getEvents()`; MVP: `PersistenceProvider.load()` |
| `emitEvent(id, event)` | `InProcessAgentSession.dispatch(AgentEvent)` |
| Bundled Auth (Git) | Phase 2: `WorkspaceService` clones with deploy key |
| Vault Pattern (OAuth) | MCP/API tools run server-side, never in sandbox |
| Containers are cattle | `LazySandbox` auto-replaces on ERROR |
| Lazy provisioning | `LazySandbox` creates Pod on first tool call |
| Brain-Hands decoupling | `ToolExecutor` interception → remote `Sandbox.execute()` |
| Multiple hands per brain | Phase 3: `SandboxRouter` (interface ready) |

## Appendix B: Open Questions

1. **Sandbox Runtime 预装软件列表**：MVP 预装 bash/python/git/curl/jq。是否需要 Node.js？还是让用户通过自定义 image 解决？
2. **Sandbox 日志收集**：Pod 内 stdout/stderr 是否需要收集到 server 端？还是只依赖 execute() 的返回值？
3. **Sandbox image 版本管理**：如何升级 sandbox runtime 而不影响正在运行的 sandbox？Rolling update 还是新旧并存？
4. **PVC 存储策略**：Azure Files Premium vs Azure Disk vs NFS？需要评估 IOPS 和并发读写性能。
5. **warm pool size 的自动调整**：Phase 2 是否需要基于负载自动 scale warm pool？还是固定大小够用？
