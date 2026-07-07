# Task 生命周期管理调研报告

> 调研日期：2026-07-07
> 调研对象：Codex (OpenAI)、Open Claude Code、OpenCode
> 调研范围：task/agent 的 resume、cancel、interrupt、状态管理等生命周期能力

## 一、调研概要

对三个主流 AI Coding 工具的 Task 生命周期管理机制进行了深入调研，重点关注：创建（create）、恢复（resume）、取消（cancel）、中断（interrupt）、后台执行、持久化等能力。

| 维度 | Codex | Open Claude Code | OpenCode |
|------|-------|------------------|----------|
| 语言 | Rust (tokio) | TypeScript (Node.js) | TypeScript (Effect-TS, Bun) |
| 执行单元 | Thread (tokio task) | Task + Agent runner | Session + Fiber |
| 取消原语 | CancellationToken 树 | AbortController 树 | Fiber.interrupt + AbortController |
| 中断语义 | Interrupt (reason + marker) | interrupt vs cancel (reason 区分) | Exit interrupt + Cancel scope |
| 恢复机制 | Rollout history 回放 | Transcript JSONL 重放 | SQLite message history 重放 |
| 持久化 | JSONL rollout + SQLite | 磁盘文件 + JSONL | SQLite (WAL) + git snapshot |

---

## 二、Codex (OpenAI)

### 2.1 状态机

**AgentStatus 枚举** (`protocol/src/protocol.rs`):
```
PendingInit → Running → Interrupted ⇄ Running
                       → Completed(Option<String>)
                       → Errored(String)
                       → Shutdown
```

状态完全从事件流派生（`agent_status_from_event`），不额外存储：
- `TurnStarted` → Running
- `TurnComplete` → Completed
- `TurnAborted(Interrupted)` → Interrupted（可恢复）
- `TurnAborted(其他)` → Errored

**TurnAbortReason**: `Interrupted`(用户中断) | `Replaced`(新turn替换) | `BudgetLimited`(Token耗尽) | `ReviewEnded`(审查终止)

### 2.2 取消机制

多层协作式取消，核心流程：

```
Op::Interrupt
  → Session::interrupt_task() → Session::abort_all_tasks(reason)
    → handle_task_abort():
      1. cancellation_token.cancel()       // 信号源头
      2. cancel_git_enrichment_task()      // 取消后台Git任务
      3. 等待 100ms 宽限期                 // 让任务观察取消信号
      4. handle.abort()                    // 强制 AbortOnDropHandle
      5. task.abort()                      // 自定义清理
      6. 注入 <turn_aborted> XML 标记      // 持久化到 rollout
      7. emit TurnAborted 事件
```

**关键设计**:
- `OrCancelExt` trait: 通过 `tokio::select!` 竞速 future 和 cancellation token
- `AbortOnDropHandle`: RAII — Drop 时自动 abort tokio task
- `<turn_aborted>` 标记**先 flush 后 emit**：保证崩溃恢复后仍能看到
- 100ms **宽限期**：让 interrupted task 观察取消信号后再强制 kill，防止 pending approval 在 TurnAborted 前暴露为 rejection

### 2.3 中断机制

**Op::Interrupt** — 中止当前 task 但不终止后台终端进程。

每个关键 await 点使用 `.or_cancel(&token)` 检查：
- 模型采样请求 / 工具执行 / 审批等待 / 权限请求 / 用户输入等待

**中断上下文标记** (注入到对话历史):
```xml
<turn_aborted>
  The user interrupted the previous turn on purpose.
  Any running unified exec processes may still be running in the background.
  If any tools/commands were aborted, they may have partially executed.
</turn_aborted>
```

### 2.4 恢复机制

```
rollout_path → InitialHistory::Resumed(ResumedHistory {
    conversation_id,  // 保持原 thread_id
    history,          // 完整历史 items
    rollout_path,
})
  → Codex::spawn() → 重播 rollout history → 恢复对话上下文
    → BFS 恢复 agent 子树 (从 AgentGraphStore)
```

**Fork 机制**:
```rust
pub enum ForkSnapshot {
    TruncateBeforeNthUserMessage(usize),  // 截断到指定用户消息前
    Interrupted,  // 模拟中断（附加 <turn_aborted> 标记）
}
```

### 2.5 关键特点

1. **事件驱动状态**: Agent 状态从事件流派生，不需要额外存储
2. **协作式取消优先**: 先让任务检测信号，AbortOnDropHandle 作强制兜底
3. **持久化优先**: 关键标记先 flush 后 emit
4. **RAII 兜底**: AbortOnDropHandle 保证异常路径也能清理

---

## 三、Open Claude Code

### 3.1 状态机

```typescript
type TaskStatus = 'pending' | 'running' | 'completed' | 'failed' | 'killed'
```

**两阶段驱逐**: 终端+retain=false → `evictAfter = now + 30s`；killed 仅保留 3s。

### 3.2 取消/中断机制

**AbortController 树**:
```
QueryEngine (root) → StreamingToolExecutor → Tool AbortController → 子Agent AbortController
```
- 单向传播（根→叶），WeakRef 防泄漏，reason 透传
- 唯一反向路径：权限弹窗拒绝上行到 root

**语义化 reason** — 四种取消语义：

| reason | 触发场景 | Shell 行为 | Agent 行为 |
|--------|---------|-----------|-----------|
| `undefined` | ESC/Ctrl+C | **杀进程** | 全部停止 |
| `'interrupt'` | 用户输入新消息 | **保留进程**，转后台 | 只停 cancel 行为工具 |
| `'sibling_error'` | 并行 Bash 出错 | 杀进程 | 合成错误 |
| `'streaming_fallback'` | 流退化 | 不涉及 | 合成回退错误 |

**interruptBehavior 接口** (每个工具声明):
```typescript
interruptBehavior(): 'cancel' | 'block'
// 'cancel' — 中断时停止; 'block' — 继续运行，新消息等待
```

**InProcessTeammate 双层 AbortController**:
- `abortController`: kill 整个 teammate
- `currentWorkAbortController`: 只 abort 当前 turn（用于 interrupt）

### 3.3 恢复机制

```
resumeAgentBackground({ agentId, prompt, ... })
  1. 读 transcript JSONL + agent metadata
  2. 过滤无效消息 (空assistant, 孤儿thinking, 未完成tool_use)
  3. 重建 contentReplacementState
  4. 恢复 worktree 路径（不存在则回退父级cwd）
  5. 重建 Agent 定义 + Fork system prompt
  6. re-register 任务 (复用 agentId, 合并 UI 状态)
  7. promptMessages = [...resumedMessages, newPrompt]
  8. void runAsyncAgentLifecycle(...)
```

### 3.4 关键特点

1. **语义化取消**: reason 区分 cancel/interrupt/sibling_error/streaming_fallback
2. **双层 AbortController**: kill agent vs abort turn 分离
3. **interruptBehavior**: 每个工具声明中断行为
4. **前/后台切换**: Promise.race + resolver Map，不 abort agent
5. **resume 合并逻辑**: 重注册时保留 UI 状态，跳过重复事件

---

## 四、OpenCode

### 4.1 状态机

- **Session 内存状态**: `idle | busy | retry`
- **BackgroundJob**: `running | completed | error | cancelled`
- **Runner 4 态**: `Idle → Running → Idle | Idle → Shell → Idle | Idle → Running → Shell → ShellThenRun`

### 4.2 取消/中断机制

**7 层取消链路**:
```
用户操作 → SDK session.abort() → SessionRunState.cancel() + BFS cancelBackgroundJobs
  → Runner.cancel() (Fiber.interrupt) → Processor onInterrupt → AbortController → Process
```

**Runner.cancel() 原子操作** (SynchronizedRef.modify):
| 状态 | Cancel 动作 |
|------|-----------|
| `Idle` | 直接返回 |
| `Running` | `Fiber.interrupt` + `Deferred.fail(done, Cancelled)` |
| `Shell` | `stopShell()` + 中断 shell fiber |

**级联取消 BFS**: Session → Runner → BackgroundJobs → 子 Session Jobs → Scope.close → 子 Fiber.interrupt

**进程级优雅降级**: `SIGTERM → 5s 等待 → SIGKILL`

**中断 vs 错误区分**:
```typescript
Exit.isFailure(exit) && Cause.hasInterruptsOnly(exit.cause)
  ? Cancelled    // 纯中断
  : 正常传播      // 其他错误
```

**双层中断体系**: Effect Fiber 中断 (协作式) + AbortController (HTTP/进程级)

### 4.3 恢复机制

OpenCode 的 "task" 本质是 **Session**。恢复从 SQLite 重建：

```
1. 分页逆序加载 Message + Part 历史 (50/batch)
2. 过滤 compaction 的旧消息
3. 检查 lastUser/lastAssistant 状态
4. 检测 pending subtask/compaction
5. 加载 agent 配置、权限、模型
6. 构建 system prompt
7. 开始 loop 执行
```

**task_id 恢复**: 传入之前的 task_id(sessionID) → sessions.get() → 存在则复用，不存在则新建

**Session Fork**: `session.fork()` 复制消息到新 session，重新映射 ID

### 4.4 关键特点

1. **一切皆 Session**: task 和 agent 本质是 Session，通过 parentID 建立关系
2. **Effect-TS 基石**: Fiber/Scope/Deferred/SynchronizedRef 原子状态转换
3. **双层取消**: Effect Fiber 中断 + AbortController 互补
4. **原子状态机**: SynchronizedRef.modify 保证 CAS 原子转换
5. **级联 BFS**: Session→Runner→Jobs→Scope→Fiber

---

## 五、核心能力对比矩阵

### 5.1 生命周期操作

| 能力 | Codex | Open Claude Code | OpenCode | core-ai (现状) |
|------|-------|------------------|----------|---------------|
| **Create/Launch** | spawn_thread | registerAsyncAgent | background.start() | TaskTool + BackgroundTaskManager |
| **Cancel/Kill** | abort_all_tasks | killAsyncAgent | Runner.cancel() + BFS | Task.cancel() + CancellationToken |
| **Interrupt** | Op::Interrupt + reason | abort('interrupt') + reason | Fiber.interrupt | interruptTurn() + NEW_MESSAGE_INTERRUPT |
| **Resume** | resume_thread_from_rollout | resumeAgentBackground | Session prompt + task_id | ❌ (仅 task_id 复用 context) |
| **Fork** | fork_thread (ForkSnapshot) | ❌ | session.fork() | ❌ |
| **Stop/Terminate** | Shutdown Op | stopTask() | BackgroundJob.cancel | ❌ (无统一入口) |

### 5.2 取消/中断语义

| 特性 | Codex | Open Claude Code | OpenCode | core-ai (现状) |
|------|-------|------------------|----------|---------------|
| 取消原因枚举 | TurnAbortReason (4种) | reason string (4种) | Exit type | CancelReason (6种) ✅ |
| 宽限期 | 100ms | ❌ | ❌ | ❌ |
| 中断 vs 取消区分 | ✅ | ✅ | ✅ | ✅ NEW_MESSAGE_INTERRUPT |
| 部分结果保留 | ❌ | ✅ extractPartialResult | ❌ | ✅ buildPartialOrCancelledResult |
| 上下文标记 | <turn_aborted> XML | task_notification XML | metadata.interrupted | ❌ |
| 工具级中断行为声明 | ❌ | ✅ interruptBehavior | ❌ | ❌ |
| 双层控制器 (kill vs turn) | ❌ | ✅ | ❌ | ❌ (Token 有父子树但无此语义) |

### 5.3 恢复机制

| 特性 | Codex | Open Claude Code | OpenCode | core-ai (现状) |
|------|-------|------------------|----------|---------------|
| 对话历史恢复 | ✅ rollout 回放 | ✅ transcript 重放 | ✅ SQLite 分页加载 | ❌ |
| Agent 子树恢复 | ✅ BFS (AgentGraphStore) | ❌ | ❌ | ❌ |
| fork/分叉 | ✅ ForkSnapshot | ❌ | ✅ session.fork() | ❌ |
| worktree 恢复 | ❌ | ✅ | ❌ | ❌ |
| 消息过滤重建 | ❌ | ✅ 3种过滤 | ✅ compaction 过滤 | ❌ |

---

## 六、可借鉴的设计模式

### 6.1 取消/中断设计

| 模式 | 来源 | 核心思想 |
|------|------|---------|
| **语义化 reason** | 三家通用 | 用枚举区分取消原因，各层据此采取不同行为 |
| **宽限期** | Codex | 先通知取消信号，等100ms再强制kill，让任务做清理 |
| **上下文标记注入** | Codex | 取消后向对话历史注入标记，告知模型中断原因 |
| **双层控制器** | Open Claude Code | kill整个agent vs 只abort当前turn — 两个独立controller |
| **interruptBehavior接口** | Open Claude Code | 每个工具声明中断时cancel(丢弃)还是block(继续) |
| **协作式取消优先** | Codex | 先让任务自己检测信号退出，RAII做强制兜底 |

### 6.2 恢复设计

| 模式 | 来源 | 核心思想 |
|------|------|---------|
| **回放式恢复** | Codex, OpenCode | 从持久化消息历史重建上下文 |
| **Agent子树恢复** | Codex | BFS递归恢复整棵agent树 |
| **Fork快照** | Codex | 从指定snapshot point分叉，注入中断标记 |
| **消息过滤重建** | Open Claude Code | 过滤无效消息后重建完整上下文 |
| **resume合并逻辑** | Open Claude Code | 重新注册时保留UI状态，跳过重复事件 |

### 6.3 状态管理设计

| 模式 | 来源 | 核心思想 |
|------|------|---------|
| **事件驱动状态** | Codex | 状态完全从事件流派生，不需要单独的存储字段 |
| **原子状态机** | OpenCode | CAS原子转换，防止并发状态错乱 |
| **两阶段驱逐** | Open Claude Code | 终端状态保留N秒展示后再GC |

---

## 七、对 core-ai 的建议

### 已有优势
- **CancellationToken 树**: 已支持树形传播、分阶段清理、语义化 CancelReason (6种)
- **BackgroundTaskManager**: 已有后台 agent 执行和通知机制
- **VirtualThread 执行器**: 已使用现代线程模型
- **PersistenceProvider**: 已有抽象持久化层

### 需要补充的能力
1. **Resume**: 核心缺失。需要从持久化历史重建 agent 执行上下文，继续执行
2. **Agent 级别的 Cancel/Interrupt API**: 目前 cancel 在 session/turn 层面，需要 agent 级别的操作入口
3. **取消上下文标记**: 取消后向对话历史注入通知，告知模型中断原因
4. **Agent 生命周期事件**: resume/cancel/interrupt 的事件回调机制
5. **统一的 Task 管理入口**: TaskManager 从 list+submit 升级为完整的 CRUD + 生命周期管理
