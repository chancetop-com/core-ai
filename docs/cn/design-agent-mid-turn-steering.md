# Tech Design：Agent 回合内追加消息（Mid-turn Steering）

> 状态：**已实现（2026-10-23）**
> 日期：2026-10-23
> 目的：用户消息在 agent 正在执行工具链时也能被接收，并在**下一次 model call 之前**注入当前 turn，而不是等整个 turn 结束
> 关联：Agent 运行时治理（design-agent-runtime-governance，待补充）、会话完成通知（design-session-completion-notify，待补充）

## 0. 决策摘要

| 决策点 | 结论 |
| --- | --- |
| 注入点 | 每次 model call 之前（`Agent.runTurnsLoop` 循环顶部） |
| 是否抢占 | **不抢占**。不打断正在进行的 model stream，也不打断正在执行的 tool；等当前 model call / tool batch 自然结束后再注入 |
| 停止语义 | 仍然由用户按 Stop 触发 `cancelTurn()`；本特性不引入新的中断路径 |
| turn 边界 | 注入后仍在**同一个 turn** 内：`pending input` 会让循环继续，即使模型刚返回的是纯文本 |
| 注入位置约束 | 只能在上一轮 `turn()`（assistant 消息 + 全部 tool result）完整结束后注入，绝不能在 `tool_use` 与 `tool_result` 之间插入 |
| 消息顺序 | 严格 FIFO；多条 pending 消息各自成为一条 user message，不合并 |
| 队列上限 | 复用 `SessionRuntimeLimits.maxQueuedMessages`；per-turn 队列满时回退到 session 队列，两者都满才报 `QUEUE_FULL` |
| 取消/失败 | 未注入的消息回退到 `SessionCommandQueue`，作为下一 turn 继续执行，不静默丢弃 |
| 传输 | 运行中追加消息走普通 `sendMessage`（不新开 SSE）；已有 SSE stream 继续接收当前 turn 的事件 |
| 非目标 | 不修改 in-flight HTTP 请求；不做 stream preemption；不做 per-message 取消；不改 task notification 的既有路径 |

## 1. 背景

当前 `InProcessAgentSession.sendMessage()` 无条件把消息放进 `SessionCommandQueue`，由 `TurnDriver` 在当前 turn 结束后再 `drainSameMode()`。因此用户在长工具链执行期间发的消息，只能等整个 turn 结束才被模型看到。

Codex / Claude Code 的做法证明：消息不需要写进正在进行的请求，只要写进**会话的 pending 状态**，并在 agent loop 的下一个安全点重建请求即可。本设计选择最保守、可控的注入点：**下一次 model call 之前**。

## 2. 现状（代码事实）

| 关注点 | 现状 | 位置 |
| --- | --- | --- |
| 会话消息入口 | `sendMessage` 只入 `SessionCommandQueue` | `core-ai/.../session/InProcessAgentSession.java:109` |
| turn 调度 | `TurnDriver` 在队列非空时取一个 `CommandBatch` 同步执行 | `core-ai/.../session/TurnDriver.java:35` |
| agent loop | `do { turn() } while (lastIsToolMsg && turns < max)` | `core-ai/.../agent/Agent.java:146` |
| 一次 turn | 一次 model call + 该次 tool calls 的全部结果 | `Agent.turn()` / `handleFunc()` |
| 现有 per-turn 注入 | `injectUserMessage()` + `continueWithInjectedMessage()`，仅用于 task notification 的 turn 后重入 | `Agent.java:346`、`InProcessAgentSession.java:212` |
| 用户消息持久化 | `handleSendMessage` 在 `session.sendMessage` 之前写入 chat message | `core-ai-server/.../InProcessCommandHandler.java:144` |
| 队列上限 | `SessionRuntimeLimits.maxQueuedMessages`，0 = 不限 | `SessionRuntimeLimits.java:36` |

## 3. 机制

### 3.1 数据结构

新增 `ai.core.agent.PendingUserInputQueue`：

```java
public record PendingUserInput(
        String message,
        Map<String, Object> variables,
        List<AttachedContent> attachedContents) {}

public final class PendingUserInputQueue {
    boolean offer(PendingUserInput input);       // false = closed / full
    List<PendingUserInput> drain();              // 取出当前全部，保序
    List<PendingUserInput> closeAndDrain();      // turn 结束时关闭并取走剩余
    boolean isEmpty();
    int size();
}
```

- 内部 `ArrayDeque` + `ReentrantLock`，保证 FIFO 和 `offer`/`close` 的原子性；
- `closeAndDrain()` 之后 `offer` 返回 false，调用方回退到 `SessionCommandQueue`，避免“turn 刚结束、消息落进已废弃队列”的丢消息窗口。

`ExecutionContext` 增加一个 transient 字段：

```java
private PendingUserInputQueue pendingUserInput;
```

它只在 turn 执行期间非空；turn 结束后由 `SessionTurnInput` 清空。

`SessionTurnInput`（`ai.core.session`）持有这个窗口的生命周期：`begin()` 建队列并挂到 context，`offer()` 给 `sendMessage` 用，`end()` 关闭窗口并把剩余消息回退到 `SessionCommandQueue`。这样 `InProcessAgentSession` 不必自己管理 `volatile` 字段和竞态。

### 3.2 turn 生命周期

`InProcessAgentSession.executeCommands()`：

```text
turn start
  input = turnInput.begin()          // new PendingUserInputQueue + context 挂载
  dispatch(RUNNING)
  agent.run(...)
turn end (finally)
  turnInput.end(input)               // 关闭窗口；剩余消息 -> commandQueue
```

`sendMessage()` 的路由：

```text
if turnInput.offer(...)                   -> accepted for current turn
else if commandQueue.enqueueUserInput(...) -> accepted for next turn
else -> QUEUE_FULL
```

> turn 正在运行时队列满，只报错，不再 dispatch IDLE（否则会把正在跑的会话错误地标成空闲）。

### 3.3 agent loop 注入

`Agent.runTurnsLoop()`：

```java
do {
    if (isCancelled()) break;
    PendingUserInputInjector.inject(this);   // 关键：每次 model call 之前
    var mat = toolRegistry.materialize(...);
    var turnMsgList = turn(...);             // model call + tool batch
    ...
    currentIteCount++;
} while (!isCancelled()
        && (AgentHelper.lastIsToolMsg(getMessages()) || PendingUserInputInjector.hasPending(this))
        && currentIteCount < maxTurnNumber);
```

`PendingUserInputInjector.inject()`：

1. `queue.drain()`；
2. 若有 pending，先 `MessageNormalizer.normalize(this)`，保证不存在没有 result 的 tool call；
3. 逐条应用变量、临时设置 `attachedContents`、`AgentHelper.buildUserMessage(...)`、`addMessage(...)`；
4. 恢复 `attachedContents`。

为什么这样是安全的：

- `turn()` 是同步的：它先拿到 assistant 消息，再执行该消息的全部 tool calls 并把 tool result 全部 append；`runTurnsLoop` 回到顶部时，最后一条一定是 tool result 或纯文本 assistant；
- 因此注入永远不会落在 `tool_use` 和 `tool_result` 之间；
- 如果 `turn()` 因取消/异常提前退出，`isCancelled()` 会在下一次循环顶部 break，pending 留给 `finally` 回退到 session 队列。

### 3.4 为什么需要 `|| hasPendingUserInput()`

模型可能在一次 model call 里直接返回最终文本（没有 tool call）。如果只看 `lastIsToolMsg`，turn 会结束，pending 只能等下一 turn。加上 `hasPendingUserInput()` 后：

```text
model 返回 "done"
→ 循环条件发现 pending
→ 再跑一次 model call，把用户的新消息交给模型
→ 仍是同一个 turn，TurnCompleteEvent 只发一次
```

这也是“消息在文本生成期间到达”时的行为：它不会打断当前 stream，但会在当前 model call 返回后继续同一 turn。

### 3.5 取消、失败、队列溢出

| 场景 | 行为 |
| --- | --- |
| 用户按 Stop | `cancelTurn()` 取消 turn token；未注入的 pending 在 finally 回退到 `SessionCommandQueue`，作为下一 turn 执行 |
| model/tool 异常 | 未注入的 pending 同样回退到 session 队列；用户消息不丢 |
| per-turn 队列满 | 回退到 `SessionCommandQueue`；若 session 队列也满，turn 仍在跑时只记 WARN（不发 terminal `ErrorEvent`，否则会清掉 SSE replay buffer），turn 已结束时仍按原逻辑报 `QUEUE_FULL` + IDLE |
| turn 正常结束 | pending 已全部注入；`closeAndDrain()` 为空 |
| 强制 close | 未注入消息已由 `ChatMessageService.writeUserMessage` 持久化到 chat history；agent 内存态可能来不及注入（见 §6 已知边界） |

## 4. UI / 传输

- 聊天页在 `status === 'running'` 时保持输入框和 Send 可用，同时保留 Stop 按钮；
- 运行中发送**不新开 SSE stream**，调用普通 `sessionApi.sendMessage`；当前 turn 的 SSE stream 继续接收事件；
- 用户消息乐观回显；服务端 `writeUserMessage` 已落库，刷新历史后仍在；
- 文件/设置/语音等在运行中仍可保持禁用（v1 只开放文本 steering）。

## 5. 测试

| 测试 | 断言 |
| --- | --- |
| `messageSentDuringToolExecutionIsInjectedAfterTheToolResult` | 阻塞 tool 期间发两条消息 → tool 结束后、下一次 model request 按顺序包含两条消息；只产生 1 个 turn |
| `messageSentDuringFinalTextContinuesTheSameTurn` | model 生成文本期间发消息 → 当前 call 返回后再发一次 model call；仍是 1 个 turn |
| `cancelledTurnRequeuesPendingInputForTheNextTurn` | Stop 后 pending 成为下一 turn，不丢失 |
| `PendingUserInputQueueTest` | FIFO、上限、close 后 offer 拒绝 |
| `SessionCommandQueueTest.keepsFifoOrderWithinTheSamePriority` | 同优先级 FIFO（新增 sequence tiebreaker） |
| `ChatComposer.test.tsx > keeps the composer usable while a turn is running` | 运行中 textarea 可用、Enter 仍触发 onSend |

## 6. 已知边界 / 非目标

- **不抢占**：长文本生成期间的消息要等当前 model call 返回；长 tool 要等 tool batch 结束。用户仍可用 Stop 中断。
- **不做 per-message 取消**：v1 只能 Stop 整个 turn；pending 消息随 turn 结束回退。
- **强制 close 的极端情况**：如果 agent 线程在注入前被强制终止，消息已在 chat history，但可能未进入 agent 内存消息；后续如需严格恢复，可在 `SessionRebuildManager` 里对齐 chat history 与 agent history。
- **transcript 交错**：`ChatMessageService` 按 turn 聚合 assistant 文本，运行中插入的 user message 在历史里会排在最终 assistant 消息之前；不做逐段 flush。
- **CLI 交互输入**：backend/app-server/server chat 已支持运行中追加消息；CLI REPL 的输入仍由 `readyForInput` 串行化（一次只读一条、等 turn 结束），本次未改 CLI 的终端交互。
- **不改变 task notification**：`executeTaskNotifications()` 仍走 `injectUserMessage + continueWithInjectedMessage`，不在本特性范围内。
