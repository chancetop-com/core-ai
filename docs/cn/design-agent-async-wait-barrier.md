# Agent Async Wait Barrier — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft；P1–P3 已实现（见 §12），P4/P5 未实现；关键决策仍待用户追认 |
| 文档版本 | v0.3（实现第一阶段并记录实现偏差） |
| 创建日期 | 2026-11-17 |
| 作者 / Reviewer | core-ai / 用户；AI 技术评审单独记录，不代表用户批准 |
| 范围 | `async_task_output(action="wait")`、任务完成信号、Agent 同 turn 等待与恢复 |
| 本次交付 | P1–P3 运行时实现、测试与验证；P4/P5 仅设计（实现对照见 §12） |
| 事实来源 | 当前工作区源码；§3–§6 中标为“拟新增”的部分已在 §12 落地 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文）

> 你看看core-ai的async_task的流程，现在都是调用就返回，返回结果会导致LLM无法真正的停下来等待，我希望一个机制，如果agent调用了wait async的时候，真个agent停下来等待一个async结束的信号再继续，你看看如何设计

### 0.2 需求编号

| ID | 要求 | 属性 |
|---|---|---|
| W1 | 显式调用 async wait 后，当前 Agent 停止推进，不再请求 LLM | 原始需求 |
| W2 | 目标任务的终态信号到达后恢复，而不是让 LLM 反复 poll/wait | 原始需求 |
| W3 | 在原 wait 工具调用、原 turn 上交付结果；不靠新 USER 通知模拟恢复 | 设计推导 |
| W4 | Stop、会话关闭和运行硬期限能够终止等待；取消等待与取消任务分开 | 安全约束 |
| W5 | 不漏唤醒、不串任务，不因完成通知与 wait 竞争重复调用 LLM | 正确性约束 |
| W6 | 父 Agent 等待时，被等待的后台 subagent 和独立任务仍能运行 | 活性约束 |
| W7 | 明确进程内挂起与跨进程 continuation 的边界，不把任务持久化冒充执行恢复 | 范围约束 |
| D1 | 先设计、确认关键决策后实现 | 本次工作边界 |

### 0.3 维护规则

- 保留需求原文；新增需求、ADR、待确认项、Review 与修改记录在本文维护。
- AI Review 只记录为技术意见；没有用户确认不得标记 Approved。
- 源码引用使用工作区相对路径与行号；实施前重新核对。

## 1. 结论与核心提案

**把 wait 定义成 runtime completion barrier，而不是普通返回 `PENDING` 的查询工具。**

```text
当前：wait(T) → 有界等待 → PENDING → 下一次 LLM → 再决定是否等
目标：wait(T) → 挂起当前执行 → terminal(T) → 原 wait 的 TOOL result → 下一次 LLM
```

推荐分阶段：

1. **第一阶段：进程内、同 turn 的事件等待。** Runtime 在工具执行边界识别 typed wait request，订阅目标任务的 completion promise，可取消地 park 当前虚拟线程；不返回中间 tool result。用仍在内存中的 Java 调用栈保存恢复位置，不必先重写整个 ReAct engine。
2. **第二阶段：server 长等待 / 释放执行额度。** 引入 typed suspended outcome、continuation frame 和恢复调度器。若需要跨 Pod / 部署恢复，再增加持久 checkpoint、CAS/fencing 与 completion inbox。

第一阶段的“停下来”是：**当前 Agent 的推理与工具调度不推进，等待不烧 CPU、不调用模型；并非整个进程停止，也不是把子 Agent 一起冻结。** 它仍保留调用栈、会话 turn、内存与逻辑运行额度，不能宣称已经释放全部资源。

### 1.1 不采用的捷径

| 方法 | 为什么不够 |
|---|---|
| Prompt 告诉 LLM “请等一下” | 模型仍被请求；停止没有运行时保证 |
| 只设 `WAITING_FOR_ASYNC_TASK` | 当前循环不以此为栅栏；状态不能代替控制流 |
| 将超时从 30 秒改为 30 分钟 | 仍会超时返回，而且未解决完成竞争、取消和错误后端定位 |
| wait 返回 `PENDING`，结束 turn，再注入完成通知 | 是新一轮推理，不是恢复原工具调用；容易提前发送完成事件 |
| 在普通 lifecycle 的 `beforeModel` 阻塞 | 可能已经有 compression 等模型调用；重试路径也可能绕过该 hook |
| `break` Agent 循环或 `directReturn` | 易触发完成、reflection 或丢失工具配对；没有恢复点 |

## 2. 当前代码的真实流程

### 2.1 为什么 wait 返回后模型继续

`Agent.runTurnsLoop()` 在 `turn()` 返回后追加 assistant/tool messages；只要最后一条是 TOOL 且未取消/超限，就进入下一次模型调用。

- `core-ai/src/main/java/ai/core/agent/Agent.java:155-177`：循环与继续条件。
- `core-ai/src/main/java/ai/core/agent/Agent.java:187-201`：LLM → tool orchestration；整个调用返回后才提交本轮 messages。
- `core-ai/src/main/java/ai/core/tool/ToolOrchestration.java:60-67`、`:202-227`：执行结果压成普通消息，没有 suspended outcome。
- `core-ai/src/main/java/ai/core/tool/ToolExecutor.java:417-443`：pending 可更新 NodeStatus，但 `async_task_output` relay 提前返回；这不是控制栅栏。

**修正用户描述中的细节：当前 wait 并非立即返回。** 默认会等待 30 秒，超过窗口返回 `PENDING`；因而无法持续停住 Agent。

| 后端 | 当前 wait | 问题 / 源码 |
|---|---|---|
| BackgroundTaskManager | `Future.get(timeout)` | 超时 / starting / finishing 均可能返回 pending；`core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:216-236` |
| ToolCallAsyncTaskManager | 每秒左右执行原工具 poll | 默认 30 秒后返回 pending；poll 错误可能提前结束 wait；`core-ai/src/main/java/ai/core/tool/tools/AsyncTaskOutputTool.java:120-138` |
| AsyncToolTaskExecutor | 每秒检查 future | 默认 30 秒后返回 pending；terminal poll 删除结果；`core-ai/src/main/java/ai/core/tool/tools/AsyncTaskOutputTool.java:141-156`；`core-ai/src/main/java/ai/core/tool/async/AsyncToolTaskExecutor.java:49-75` |

默认 / 最大 wait 分别为 30 秒 / 30 分钟，外层 tool timeout 固定为 31 分钟：`core-ai/src/main/java/ai/core/tool/tools/AsyncTaskOutputTool.java:19-21`、`:78-80`、`:181-184`。

### 2.2 完成通知不是原执行点恢复

```text
worker / server poller 发现终态
  → 保存结果
  → task notification 排入会话队列
  → 当前 turn 返回后，TurnDriver 才执行通知
  → injectUserMessage(XML)
  → continueWithInjectedMessage()
  → 新 notification turn
```

- 本地发布点：`core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:300-311`。
- 会话通知执行：`core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:288-316`。
- Server 观测与通知：`core-ai-server/src/main/java/ai/core/server/asynctask/AsyncToolTaskService.java:74-88`、`:128-138`。

**不能让原 turn 的 wait 等待同一个 TurnDriver 执行 notification：后者排在原 turn 后面，会死锁。** 唤醒必须由 worker / poller 直接完成 promise；UI / LLM 通知是旁路。

### 2.3 普通工具超时不等于异步提升

当前通用 `ToolExecutor` 在工具 timeout 时取消 future、interrupt 工具线程并返回失败，并不会自动注册后台工作：

- `core-ai/src/main/java/ai/core/tool/ToolExecutor.java:328-369`。
- `core-ai/src/main/java/ai/core/tool/ToolCallWait.java:32-55`。

另一套 `SandboxHubService` 的同步窗口过期才会返回 pending、让底层 future 继续；不应混淆两套语义：`core-ai-server/src/main/java/ai/core/server/sandboxhub/SandboxHubService.java:146-185`。

## 3. 第一阶段架构与控制契约

### 3.1 组件边界（拟新增）

```text
Agent → ToolOrchestration → ToolExecutor
                              |
                              +-- 普通调用 → 现有 executeWithTimeout
                              |
                              +-- RuntimeWaitTool.prepare(args) → WaitRequest
                                      |
                                      v
                                TaskAwaitCoordinator
                                  resolve + subscribe
                                  reserve result delivery
                                  park current virtual thread
                                      ^
                                      |
      Background worker / Executor completion / Managed poller
                         → retained terminal snapshot + complete promise
                                      |
                                      v
                          原 functionCall.id 的最终 TOOL result
                              → 后续 batches → 原 Agent loop
```

| 类型 / 组件 | 职责 | 不承担的职责 |
|---|---|---|
| `RuntimeWaitTool` | 根据已验证参数返回可选 typed `WaitRequest`；仅 wait action 进入 runtime control path | 不按工具名字符串猜控制意图，不执行工作 |
| `WaitRequest` | task reference、可选 wait deadline；`poll/cancel` 不产生 wait request | 不携带可持久化的 Java future |
| `TaskAwaitCoordinator` | 定位后端、原子订阅、独立 waiter、取消、通知抑制、等待事件 | 不使用 LLM，不等待 notification turn |
| `WaitCapability` | 宿主显式允许/禁止 live-stack wait，声明可驱动 source；默认禁用 | 不允许仅凭 LLM 参数打开能力 |
| `TaskCompletionSource` | `resolve/subscribe/peek/cancel` 的后端适配；保留 terminal snapshot | 不决定用户提示或 turn 完成 |
| `TaskRef` | owner scope、backend、backend owner、taskId、generation 的稳定标识 | 不把裸 taskId 当全局唯一标识 |
| `WaitExecutionScope` | 当前 agent execution / turn / functionCall.id / waiterId；生命周期限于本次调用 | 不在共享 ExecutionContext 写一个会冻结所有 agent 的布尔量 |

第一阶段**无需给 `ToolCallResult` 新增可序列化的 SUSPENDED 状态**：wait 未返回时，调用栈本身就是 continuation。Typed wait request 先在 runtime 边界消费，最终仍返回普通 terminal tool result。第二阶段释放栈时才必须升级 orchestration / Agent 的返回类型为 typed suspended outcome。

### 3.2 Runtime 接入规则

1. 维持现有权限、参数验证、beforeTool 和 trace 入口。
2. 在 `ToolExecutor.doExecuteWithCaller()` 验证后识别 `RuntimeWaitTool` 的 wait plan；进入 `TaskAwaitCoordinator`，**不进入普通 `executeWithTimeout()` 的 worker + 100ms steering 检查**。
3. wait 使用调用者的虚拟线程 park；不占工具 batch semaphore，不投递到受限工作池。普通调用不改。
4. wait 返回后沿用成功结果预算 / 保存策略、afterTool 和原 functionCall.id 的消息配对。结果 / usage 从 immutable snapshot 生成本次调用副本，不能多 waiter 修改同一 `ToolCallResult`，也不能重复计入原异步工作的模型用量；wait 错误的输出策略见 §3.3。
5. `AsyncTaskOutputTool.execute()` 被宿主直接调用时也委托同一个 coordinator。已有局部 `taskManager` 的直接调用/测试可构造局部 source；需要显式有效 task scope、能力与取消边界，不要求必须启动会话 TurnDriver。缺能力/有效 scope 时报 `WAIT_UNSUPPORTED`，不得退化为 singleton 全局查找。
6. **在 `ToolOrchestration.partition()` 就根据纯参数判断 `RuntimeWaitTool` 的 wait action并强制单元素 exclusive batch**，不是到了 ToolExecutor 才处理；该分类不得注册 waiter 或产生副作用。wait 禁止进入 `runBatchConcurrently()`，普通 poll/cancel 不受影响。
7. 第一阶段不通过抛普通异常或 break loop 模拟挂起，不触发 reflection、afterAgentRun 或 TurnComplete。
8. `WaitExecutionScope` 由当前 Agent / ToolExecutor 显式传递，其中捕获当前 turn/child cancellation token；**不存入可能被父子共享、原地修改的 ExecutionContext**。子 Agent 的执行得到独立 scope。当前 `SubAgentToolCall.startAndAwait()` 会原地替换 context token/budget（`core-ai/src/main/java/ai/core/tool/tools/SubAgentToolCall.java:118-147`），实施时须隔离子 context或确保捕获执行 token不被改写，不能动态读取已被子 Agent 覆盖的 context token作为父wait令牌。
9. cancellation callback与线程中断共同解除 park；进入 afterTool/trace/持久化收尾前按既有session策略处理中断标志，不能靠吞掉中断继续推理。`WaitCapability` 配置可通过宿主builder/context传入，但它是静态能力，不是共享挂起状态。

### 3.3 默认 wait 的新语义（待确认）

```json
{"action":"wait","task_id":"..."}
```

- 默认等待至 `COMPLETED / FAILED / CANCELLED`，不再有“30 秒后返回 pending”的同步窗口。
- `timeout_ms` 省略或为 0：无独立 wait 期限，仍受宿主运行硬期限与 Stop 约束；负数为参数失败，不沿用旧的至少1000ms clamp。
- `timeout_ms > 0`：明确选择有界等待；到期返回 wait 层的 `FAILED`，带稳定 reason `WAIT_TIMEOUT`、`stats.waitOutcome=timeout`，说明**原任务仍在运行**。它是调用者主动设置的退出条件，不是假称工作失败，不返回含糊的 `PENDING` 让模型继续轮询。
- `WAIT_REQUIRES_INPUT` 用已执行成功的控制查询结果表达，附 `stats.waitOutcome=requires_input`，不把任务mark为FAILED；其他wait错误也有稳定waitOutcome。第一阶段wait错误是短文本，不做成功结果的save_to_file/自动落盘；指标按waitOutcome区分预期超时与底层任务失败。这是当前只对COMPLETED自动保存的显式策略，不声称所有错误也沿用成功落盘路径。
- 运行总预算 / session watchdog 到期：取消本 turn，不额外调用模型；第一阶段等待计入 wall-clock 预算，不计 token / 推理 turn 数，不偷偷暂停硬期限。
- `poll` 仍立即返回当前快照；`cancel` 仍是显式停止底层工作。

这改变了旧 timeout 的返回契约，必须同版本更新 tool schema、描述、tests 和实际受影响的 SDK/调用方；不能只改常量。若必须兼容旧行为，可另保留明确命名的 bounded query action，而不让默认 wait 继续返回中间结果。

## 4. 完成信号、状态机与交付仲裁

### 4.1 任务身份与授权

- LLM 仍只传 task_id；runtime 从当前 owner scope 下的 registry 解析 `TaskRef`，不让模型猜 backend / generation。
- registry key 至少为 `(ownerScope, backend, backendOwner, taskId, generation)`。`backendOwner` 对 sandbox 是实例身份；不能将不同 sandbox 的 `task-1` 混在一起。
- 同一任务多个 waiter 有不同 waiterId；同 ID 的新任务有新 generation，旧通知 / 迟到完成不能唤醒新任务。
- 任务注册必须记录可见 owner scope；当前 singleton executor 缺少此信息，接入前补齐，不能裸 ID 全局回退。父子共享任务可显式授予访问，不能由名字碰巧匹配。
- 不存在、不属于当前授权范围、后端无法驱动、终态已被淘汰时立即明确失败。不要先挂起再发现任务永远无法完成。

### 4.2 不漏唤醒的注册协议

每个 task generation 有保留的权威状态和 completion promise；在任务注册、向调用者暴露 task_id **之前**创建 entry。

```text
任务：REGISTERED → RUNNING → COMPLETED | FAILED | CANCELLED
等待：REGISTERING → WAITING → RESUMING → RESULT_RETURNED
                         ↘ WAIT_CANCELLED | WAIT_TIMEOUT | WAIT_UNAVAILABLE
```

注册 / 完成协议：

1. 原子查找 task entry、授权并固定 generation，pin 结果保留期。
2. 在同一 entry 的锁 / CAS 协议内，先查看终态；若已终态则获取快照，否则注册 waiter。完成与订阅必须共享线性化点，不能使用“检查一次 → 无保护注册”的两步操作。
3. 为该 waiter 建**独立**可取消 future；订阅 task 的共享 completion，但 Stop / timeout 只 settle 自己的 future，不 cancel 共享 promise 或底层执行 future。
4. worker / poller 先保存 immutable terminal snapshot，后完成共享 promise，再走交付仲裁与 UI 通知。若保存失败不得伪造成功唤醒。
5. completion/cancel/deadline 都经过一次性 settlement；signal 回调仅完成 promise / 安排事件，不在锁内执行 LLM、网络发送或长逻辑。
6. finally 撤销 waiter、cancel callback、计时器和 pin。活跃 waiter存在时不可淘汰终态结果；未drain通知仍引用的`(taskId,generation)`抑制状态也必须保留，不能因terminal缓存达到容量上限而失效。结果缓存与通知抑制tombstone的retention分开管理。

旧代码中 running entry 删除到 terminal entry 插入之间的空窗、future 尚未安装就返回 starting，以及 destructive terminal poll，都必须由该协议消除。

### 4.3 `WAITING_FOR_INPUT` 与 poll 错误

- `WAITING_FOR_INPUT` 不是 terminal；任务需要人的输入时不能无限静默等待。runtime 返回明确 wait 结果 `WAIT_REQUIRES_INPUT`，携带 task_id / 输入要求，释放 barrier，让 Agent 询问用户；任务不标成失败。
- polling transport 错误是“暂时无法观测”，不是底层任务 FAILED。后端以独立诊断记录、退避重试；不唤醒 LLM。持续不可恢复或宿主期限到达时以 wait 层错误收尾。
- 工具真正报告任务失败才 settle 为 FAILED。取消成功需保留 CANCELLED terminal snapshot，不能只删记录让 waiter 变成 not-found；取消请求失败则任务继续。

### 4.4 wait 与自动通知：首版采用 generation 级抑制，不做事务票据

**完成事实**与**面向 Agent 的交付**分开：`notify=false` 只关闭自动通知，不能关闭 completion signal；UI 的 TaskStatusEvent 始终独立更新。

首版在单一 TurnDriver、exclusive wait 下精简为：task entry维护独立waiter和 `(notificationRecipient, TaskRef)` 的 `automaticNotificationSuppressed` 标记。建立有效wait时暂停该generation的自动通知；终态交给wait时将抑制保持至结果retention结束。**该标记仅表示“不自动启动推理”，不声称结果已持久消费。** terminal快照始终保留，允许用户显式重新读取。

| 场景 | 行为 |
|---|---|
| 活跃 wait 订阅在完成前建立 | 完成promise直接唤醒；不排该接收者的自动LLM notification |
| notification 已排队但未执行，此时 wait 开始 | 队列drain按完整TaskRef/抑制标记跳过，不只按裸taskId |
| notification 先前已经执行 | 不回滚历史；后续显式wait可读缓存，不新增自动通知 |
| 多个显式waiter | 独立future与结果副本；取消一个不影响其他waiter |
| wait被用户Stop / close / turn硬期限取消 | 对该接收者/TaskRef保留自动通知抑制，不补发、迟到不复活；结果可显式读取 |
| 显式有界wait到期 / requires-input，任务仍非终态 | 若无其他活跃waiter且无turn取消抑制，释放临时抑制，恢复原notify策略 |
| wait已收到终态但后续batch/消息提交失败 | 保留终态缓存与自动抑制；当前turn按错误/取消收尾，不自动补发新的推理turn |

这是对原稿事务票据方案的有意收敛：**首版不跨 `Agent.runTurnsLoop()` 做延迟commit/abort，也不保证结果提交失败后自动交付。**接受进程内失败时由用户显式继续/读取的边界，避免Stop收尾中的`drainTaskNotifications()`之后又“鬼魅唤醒”。抑制与显式读取权分开，不能调用旧markConsumed就删除/撤销结果。

Generation必须进入通知结构/队列filter。`TaskTool`已支持同task_id续跑（`core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:306-319`）；当前`SessionCommandQueue`仅解析`<task-id>`并用`Predicate<String>`过滤（`core-ai/src/main/java/ai/core/session/SessionCommandQueue.java:25-42`、`:93-106`），新generation开始不能清掉旧generation抑制而误注入旧通知。

第一阶段只保证存活进程内无重复自动推理和取消后的迟到抑制，不保证崩溃后exactly-once。**Server跨实例版本再引入reservation/delivery ticket、消息checkpoint与条件更新**；`PersistenceProvider.load/save`没有CAS原语，须新增专用store条件操作，不能用`synchronized(this)`冒充分布式CAS。当前窗口见`core-ai/src/main/java/ai/core/tool/ToolCallAsyncTaskManager.java:174-181`、`:252-260`。

## 5. 三类任务源如何接入

| Source | 完成驱动 | 必要调整 |
|---|---|---|
| BackgroundTaskManager | worker completion / cancel callback | entry 注册即创建 promise；先发布终态再移除 running；保留 immutable snapshot；完成与通知分离；队列/抑制filter带generation，不因同ID续跑清掉旧generation状态 |
| AsyncToolTaskExecutor | worker 返回 / 抛错 / cancel settlement | 注册前初始化 entry；新增 owner scope、completion subscription、retained terminal；poll 不再删除结果；失败/取消也完成 promise |
| ToolCallAsyncTaskManager | 宿主独立 poller / 上游 webhook | 保存终态后发 completion；通知是另一订阅；去掉 wait 自己的 sleep-poll；条件更新避免 terminal / delivery 字段互相覆盖 |

Server 现有默认约 10 秒的 poll job 可继续发现终态：模型等待事件，不参与 poll；以后再根据延迟需求接 webhook。若 managed source 在某 embedder 无后台驱动，必须由宿主安装有界并发的 driver，或拒绝 wait 为 `WAIT_UNSUPPORTED`。**不能只订阅一个没有任何 producer 会完成的 future。**

源码落点：

- `core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:261-311`。
- `core-ai/src/main/java/ai/core/tool/async/AsyncToolTaskExecutor.java:39-86`。
- `core-ai/src/main/java/ai/core/tool/ToolCallAsyncTaskManager.java:146-184`、`:288-312`。
- `core-ai-server/src/main/java/ai/core/server/asynctask/AsyncToolTaskService.java:50-57`、`:74-88`。

### 5.1 Sandbox 和 Hub 不能未经适配直接纳入

Go sandbox taskId 与 Java executor 不是同一任务源。目前有 `SandboxClient.pollTask()`，但未接入这里的 Java Shell/Python poll 链；新 barrier 若仍按裸 taskId 选 Java 后端，会永久等不到正确结果。

- `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxClient.java:166-177`。
- `core-ai/src/main/java/ai/core/tool/tools/ShellCommandTool.java:390-397`。
- `core-ai/src/main/java/ai/core/tool/tools/PythonScriptTool.java:242-249`。

P4必须补 `SandboxTaskCompletionSource`与Sandbox poll/cancel接口，通过原sandbox身份访问`/tasks/{id}`，上报终态并保持sandbox活性；P1–P3无此adapter时立即拒绝，不硬挂起。Hub `InFlight`是另一类句柄，需要独立adapter，不能假设已注册managed task。

TaskRef注册与旧`handleAsyncResult()`分开：后者只处理PENDING/WAITING_FOR_INPUT，会跳过`ASYNC_LAUNCHED`；本地Background已经自管注册，这本身不是bug，但新统一路由必须涵盖三种真实工作句柄，不能把ASYNC_LAUNCHED当作不存在。**不据此断言sandbox当前返回ASYNC_LAUNCHED**：`SandboxClient.parseResponse()`把`pending`映成PENDING（`core-ai-server/src/main/java/ai/core/server/sandbox/SandboxClient.java:249-265`），实际缺口是注册了错误的poll后端/缺sandbox身份。

Wrapper返回`PENDING(innerTaskId)`只表示依赖转交，不是完成。首版不支持Hub wrapper、不做通用递归图；P4接Hub时做授权、单层明确handoff，若要支持多跳再设计cycle/hop上限。**wait/control tool不参与timeout-to-async promotion，不创建wait-of-wait wrapper。**

## 6. 执行、取消与资源边界

### 6.1 严格等待与用户 steering（待确认）

第一阶段建议采用：**等待期间新用户输入排队，不抢占 barrier；Stop 可立即取消。**

- 完成信号到达后，遵循已有 pending input 注入顺序，在下一次 LLM 前处理等待期间的用户输入。
- 保留输入的 turnId / 变量 / 附件与 transcript 记录；Stop 时沿用输入重排队，不能静默丢弃。
- 不复用 `ToolCallWait` 的“新消息即取消工具”策略；其 100ms slice 会使严格 wait 被普通消息解除：`core-ai/src/main/java/ai/core/tool/ToolCallWait.java:38-42`。
- 若产品希望用户一句“别等了”也能立即解除，则选可抢占模式：消息到达先取消 wait，再调用 LLM。它是明确的另一种策略，不同时宣称“只能完成信号恢复”。

| 事件 | barrier | 底层工作 | 是否调用 LLM |
|---|---|---|---|
| 目标任务完成 / 失败 / 成功取消 | 返回终态结果 | 已终态 | 原循环随后继续 |
| 其他任务完成 | 不解除 | 对方按正常通知策略 | 当前 Agent 不调用 |
| 新用户普通消息 | 保持，输入待注入 | 不变 | 完成前不调用 |
| Stop / ESC | 取消当前 turn 和 waiter | 遵守既有 token ownership；wait 本身不主动 cancelTask | 不为被取消 turn 恢复 |
| 显式正数 timeout_ms 到期 | 返回 WAIT_TIMEOUT | 仍运行 | 调用者指定有界退出后可继续 |
| run budget / session watchdog 到期 | 取消 turn | 遵守原资源取消策略 | 不额外调用 |
| close / 进程退出 | 清理 waiter | 由宿主关闭策略决定 | 不恢复 |

`CancellationToken.onCancel()` 适合取消本 waiter；不能把 task 的共享 promise 直接交给当前 `orCancel()`，因为它会 cancel 传入 future：`core-ai/src/main/java/ai/core/agent/CancellationToken.java:244-260`。

### 6.2 多工具与父子 Agent

- wait exclusive：此前batch已结束，等待期间后续batch不启动。completion后若没有pending user input则执行剩余batch；**若等待期间已收到新输入，orchestration跳过尚未启动的后续call，为每个原toolCallId补明确cancelled/skipped结果，提交完整工具配对后在下一次LLM前注入输入**。不先启动带副作用的过时工具再让ToolCallWait取消它。
- 工具调用存在依赖于后续尚未执行 call 的情况应明确拒绝 / 视为非法依赖；不要改成启动后续工作来“解死锁”。第一阶段仅等待此前已注册任务。
- 其他已经启动的独立后台工作继续；等待父 Agent 不冻结子 Agent 的模型。wait scope 归属具体 Agent execution，不由共享 context 的全局 flag 决定。
- 单一 task 有多个显式 waiter可以共享完成事实，但各自取消独立、各自配对原 toolCallId。
- 仅当前 Agent 不再发模型请求；整个进程中的其他 Agent、独立任务与被等待 subagent 的 token 仍会增长，这是必要的活性而非违反 barrier。

### 6.3 生命周期、UI 与运行额度

第一阶段：

- 维持原 turn 的 RUNNING、turnId、usage baseline、取消 token、watchdog、ownership heartbeat 和 sandbox 保留，不调用 finishTurn。
- 新增非终态 `AgentWaitEvent`：`waiting/resuming/cleared`，携带 executionId / toolCallId / TaskRef / deadline / generation；UI 可显示“等待任务 …”，仍保留 Stop。
- TaskStatusEvent 继续展示任务自身完成；不把任务完成误当整个会话完成。
- 仅最终正常完成发 TurnComplete；wait 不触发渠道完成通知、反思总结或 transcript 完结。旧客户端不识别 wait event 时仍显示 RUNNING。
- 第一阶段不新增 SessionStatus 枚举值、不提前释放会话 turn admission；等待计入 wall-clock，活跃 wait 数 / 等待时长须有观测和宿主限制。

会话生命周期参考：`core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:149-210`、`:246-285`。wait 必须保持在 `agent.run()` 内部而非返回后再继续。

**Server AgentRunner的固定池必须单独处理。** 当前10个platform thread默认run timeout 600秒；10个父run等待同池的子run会占满池：`core-ai-server/src/main/java/ai/core/server/run/AgentRunner.java:51-64`。第一阶段`AgentRunBuilder`显式`WaitCapability.DISABLED`或不注入coordinator，调用wait立即WAIT_UNSUPPORTED；不是仅文档说不启用，也不是改虚拟线程就能消除逻辑额度死锁。CLI/desktop session builder显式启用、子execution继承宿主能力并得到独立scope。

Server chat在P4前也默认禁用。接入前需独立completion producer、正确owner/source、store条件更新，且明确wait受`turnTimeoutSeconds`硬上限封顶。CLI/desktop默认无turn墙钟期限，但设置了宿主期限则等待也计入，不保证超出期限的任务仍自动续跑。Server长等待超过硬上限的需求应调预算或转P5 stackless，不默默暂停watchdog，也不宣称跨Pod恢复。

## 7. 第二阶段：真正释放栈与 durable continuation

第一阶段满足“不继续请求 LLM，收到任务信号再继续”，但**不保证应用关闭 / Pod 重启后恢复原等待点**。任务表和聊天消息不是 continuation。

若需要 server 长等待 / 释放执行额度，建议新增：

```text
ToolExecutionOutcome = Completed(result) | Suspended(waitRequest)
TurnExecutionOutcome = Completed(output) | Suspended(continuation)

RUNNING → CHECKPOINTED_WAITING → RESUME_READY → RUNNING → COMPLETED
                                   ↘ CANCELLED / TIMED_OUT
```

Continuation 至少保留：

- agent definition / model / tools version、owner scope、execution/turnId、waiterId、generation；
- 已提交 messages + 当前尚未提交的 assistant choice、完整 tool_calls；
- batch cursor、已完成工具结果、待执行 call、原 toolCallId；
- loop iteration、输出累计、usage baseline、预算与绝对硬期限、附件 / sandbox 引用；
- side-effect tool 的完成与交付凭据、取消 tombstone、owner fencing token。

恢复不是 `injectUserMessage()`：调度器在有界并发额度下加载 continuation，先关联原 wait result，再执行剩余 batch / 原循环；已经完成的副作用工具不重放。

持久版本需：

1. checkpoint 与 wait subscription 的一致性；completion 先于 park 时也能立刻进入 RESUME_READY。
2. completion outbox/inbox 的 at-least-once + 幂等 CAS settlement；不能宣称传输 exactly-once。
3. 先持久存结果并置 resume-ready，再 ACK；owner lease/fencing 防双重恢复。
4. wake 定向到 chat turn / AgentRun，不通过 run:<id> 去错误地找 chat session。
5. 崩溃扫描补偿丢 callback、park/wake race；取消/旧 generation 不复活。
6. WAITING 逻辑 run 仍存在，但释放 active execution slots；恢复时重走 admission，等待任务占用独立并发/保留资源。
7. sandbox / 上游任务无法恢复时明确 WAIT_UNAVAILABLE 或 terminal failure，不重做付费/发消息工作来猜结果。

内部可参考已实现的 Workflow typed suspension、PAUSED 和恢复扫描：

- `core-ai-server/src/main/java/ai/core/server/workflow/NodeOutcome.java:64-73`。
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowRunner.java:213-246`。
- `core-ai-server/src/main/java/ai/core/server/workflow/WorkflowRunnerJob.java:50-87`。

复用其状态机 / CAS / 补偿原则，不把整个 Agent 强制转换成 workflow graph。此阶段需独立补全持久化 schema 与事务 ADR 后实施；本文不将其标为第一阶段完成条件。

## 8. 实施批次与验收

### 8.1 实施顺序

| 批次 | 内容 | 验证 / 退出条件 |
|---|---|---|
| P0 | 确认默认 wait、timeout、steering、进程内边界 | 用户确认 §9 待决策；未确认不改实现 |
| P1 | TaskRef / retained completion / generation通知抑制；Background + Executor source | register/complete/cancel races、多waiter、身份/终态保留、同ID续跑旧通知过滤 |
| P2 | RuntimeWaitTool / Coordinator、显式WaitCapability、ToolExecutor control path、同turn行为 | 模型调用计数、强制exclusive、工具配对、恢复时输入跳过后续batch、Stop、预算测试 |
| P3 | CLI / desktop 非终态等待事件与集成验证 | 真实 app-server 事件链；没有提前 TurnComplete / 完成通知，输入与 Stop 正常 |
| P4 | Managed driver、专用store条件更新/交付协议、server chat与sandbox/Hub adapter逐源接入 | owner/source/DB CAS、poll/cancel路由、watchdog预算、通知竞争；无能力入口仍拒绝 |
| P5 | 需求明确需要后，再做 stackless / durable continuation | crash windows、fencing、admission、无副作用重放、跨 Pod dev E2E |

P1–P3 是推荐的最小交付；不能在 UI 宣称已经支持 P4/P5 的场景。若本次用户要求 server 也必须一次覆盖，需要把 P4 纳入首个实施版本并逐一验证，而不复用不完整的旧 polling 路由。

### 8.2 核心验收：证明没有调用模型，而非“看起来在等”

1. 可控 completion source + 请求计数 stub：默认 wait 持续超过旧 30 秒窗口；当前 Agent 的 LLM request count 保持不变，目标完成后才增加。
2. 确认不只是主 loop：当前 Agent 不触发 compression / reflection / lifecycle 的额外模型请求；被等待 subagent 可正常请求自己的模型。
3. 在 register 前 / 中 / 后完成，全部不漏唤醒；回调重复、其他 task signal、旧 generation signal 不错误恢复。
4. task success/failed/cancelled、WAITING_FOR_INPUT、传输错误与 wait timeout 分别符合约定；默认等待不返回 PENDING。
5. Stop / close / hard deadline 解除 waiter，不污染共享 promise，不晚到复活；取消其中一个 waiter不取消另一个。
6. 同 response 内 `work → wait → follow-up tool` 顺序正确；配对原 toolCallId，iteration / token 不因等待虚增。
7. wait 与已经排队的自动通知竞争，只有一条自动推理交付路径；notify=false 仍可唤醒。
8. wait得到结果后，后续batch失败 / Stop、消息未提交：终态缓存仍可显式读取，不补发自动推理；Stop/close/硬期限取消后迟到完成也被抑制。
9. taskId 跨 session/sandbox 碰撞、ID 复用、terminal TTL、缺 driver / unsupported source、wrapper handoff/cycle 均有明确信号。
10. UI 显示等待，原 turn 不结束，TaskStatus 与 TurnComplete 不混淆；完成后正常保存、恢复输入并最终完成。

基于现有 `AsyncTaskOutputToolTest`、`BackgroundTaskManagerTest`、`ToolCallAsyncTaskManagerTest`、`AsyncTaskFlowTest`、`ToolCallWaitTest`、`MidTurnSteeringTest` 扩展。时间相关 race 优先用 latch/barrier/可注入时钟，避免大量 sleep。

拟执行检查：core `:core-ai:ciCheck`、CLI `:core-ai-cli:releaseCheck`、desktop `npm test` 与 TypeScript check；若修改 server，按既有工作流执行 dev 环境 E2E。**本次只出设计，尚未运行这些实现验收，不把计划写成已验证。**

## 9. ADR 摘要与待用户确认

### 9.1 ADR 摘要

| ADR | 建议 | 理由 / 代价 |
|---|---|---|
| A1 | wait 是 runtime control path，不是字符串状态 | 默认无中间返回，runtime保证同执行点恢复 |
| A2 | 先做 live-stack completion barrier | 改动小、同 turn天然保序；不释放所有逻辑资源，不跨重启 |
| A3 | 结果信号与自动通知分离 | 无 TurnDriver死锁、notify=false 可等待、减少重复推理 |
| A4 | 独立 waiter + TaskRef generation | 取消隔离、避免跨任务串结果与迟到复活 |
| A5 | 默认等 terminal，显式有界超时是 wait 错误 | 满足真正等待；旧 timeout 返回语义有兼容代价 |
| A6 | 默认新消息排队，Stop立即生效 | 满足严格 barrier；用户普通消息的响应延迟需要 UI 明示 |
| A7 | server run长等待先不上 live-stack | 防固定池/额度死锁；后续采用 stackless continuation |

### 9.2 待确认决策

| ID | 待确认问题 | 推荐 |
|---|---|---|
| D-WAIT | 默认 wait 不再30秒返回，而是等到终态 / Stop / 硬期限；是否接受？ | 是 |
| D-TIMEOUT | 显式 timeout_ms 到期返回 WAIT_TIMEOUT，任务继续；是否接受旧 PENDING 语义变更？ | 是；同步更新所有实际调用方 |
| D-INPUT | 等待期间新消息排队，还是立即抢占并交给 LLM？ | 排队；Stop专门解除 |
| D-SCOPE | 首版先 CLI/desktop进程内，还是必须同时覆盖server chat / AgentRunner / sandbox？ | 先P1–P3；P4逐源接入，AgentRunner留第二阶段 |
| D-DURABLE | 是否要求关闭应用、部署或Pod重启后自动继续？ | 不作为首版目标；需要则补持久化详细设计 |

> 用户在本轮要求直接实现，因此 P1–P3 按上述“推荐”列落地；这属于实现选择，不表示用户已逐项批准，仍需在验收时确认。

## 10. AI Review 记录

| ID | Reviewer / 日期 | 意见 | 处理 / 状态 |
|---|---|---|---|
| R1 | `gemini-4-hydrogen` / 2026-11-17 | v0.1有条件通过；5项P0与重要边界需修订 | 以下为AI评审处理，不代表用户批准 |
| R1-P0-01 | 同上 | partition必须提前识别wait、强制exclusive | §3.2规则6明确纯分类、不得进入并发batch；已纳入设计 |
| R1-P0-02 | 同上 | SubAgentToolCall共享context/token可能污染父wait | §3.2规则8明确scope/token显式捕获、子context隔离；已纳入设计 |
| R1-P0-03 | 同上 | 同task_id续跑，旧通知会串新generation | §4.4明确队列/filter携generation与旧抑制保留；已纳入设计 |
| R1-P0-04 | 同上 | 票据abort归还与Stop drain冲突、可能鬼魅唤醒 | §4.4裁掉首版commit/abort，Stop/close/硬期限对目标自动抑制，缓存显式读；已修订 |
| R1-P0-05 | 同上 | 后端身份与ASYNC_LAUNCHED注册缺口、不得全局fallback | 首版身份/快速失败守卫采纳；复核源码纠正sandbox映为PENDING而非ASYNC_LAUNCHED的推断，见§5.1 |
| R1-P1-01 | 同上 | server watchdog封顶合法长等待 | §6.3明确硬上限、P4前决定预算；长等待转P5；不默认暂停期限 |
| R1-P1-02 | 同上 | timeout解析与FAILED落盘/指标副作用 | §3.3补负数校验、waitOutcome、错误短文本策略；已纳入设计 |
| R1-P1-03 | 同上 | pending输入会导致wait后普通工具启动即取消 | §6.2直接跳过未启动call并补配对结果，再注入用户输入；已纳入设计 |
| R1-P1-04 | 同上 | AgentRunner禁用必须有代码守卫 | WaitCapability默认禁用，宿主显式注入，未接入入口WAIT_UNSUPPORTED；已纳入设计 |
| R1-P1-05 | 同上 | PersistenceProvider没有数据库CAS原语 | §4.4与P4列专用store条件更新、poll/cancel路由前置依赖；延期P4 |
| R1-P2-01 | 同上 | 首版票据事务/多跳wrapper过度设计 | 首版采用generation通知抑制，Hub多跳延期；已收敛 |
| R1-P2-02 | 同上 | 中断收尾、直接工具单测有效scope | §3.2规则5/9明确局部source直接调用与中断收尾；已纳入设计 |
| R1-P2-03 | 同上 | 若干引用起止未覆盖方法末尾 | 原引用多数为关键语句区间并非完整方法，保留有效关键区间；补同ID续跑、队列、subcontext与sandbox parse引用 |
| R2 | `gemini-4-hydrogen` / 2026-11-17 | v0.2复核通过，首版P1–P3无残留P0/P1阻断项 | AI技术意见，不是用户批准；实现仍待§9.2确认 |
| R2-N1 | 同上 | 默认禁用能力，旧单测需显式开启；terminal淘汰不应释放未drain旧通知抑制 | builder/直接调用测试遵守显式能力；§4.2补通知tombstone独立retention；已纳入实施要求 |

## 11. 修改记录

| 版本 | 日期 | 修改 |
|---|---|---|
| v0.1 | 2026-11-17 | 核对三类异步后端、Agent loop、通知链与普通超时行为；提出同turn completion barrier，明确交付/取消/资源/持久化边界与验收 |
| v0.2 | 2026-11-17 | 根据gemini-4-hydrogen评审补exclusive分类、scope/token隔离、同IDgeneration、显式能力守卫与steering恢复规则；首版裁剪事务票据/Hub多跳，明确Stop迟到抑制和server预算边界；纠正sandbox状态映射推断 |
| v0.3 | 2026-11-17 | 实现 P1–P3：wait 控制路径、完成注册表、generation 通知抑制、exclusive/skip、AgentWaitEvent；记录实现偏差与验证结果（§12） |

## 12. 第一阶段实现记录（P1–P3）

### 12.1 已落地

| 设计项 | 实现落点 |
|---|---|
| typed wait 控制请求 | `ai.core.tool.wait.WaitRequest`、`RuntimeWaitTool`、`WaitRequests`；`AsyncTaskOutputTool` 仅对 `action=wait` 生成请求 |
| 完成信号与保留终态 | `TaskCompletionRegistry`（每源一个实例，register→complete→retained snapshot，独立 waiter future，同 ID 新 generation 使旧 waiter 以 replaced 结束） |
| 后台任务源 | `BackgroundTaskManager.submit` 在 executor submit 前注册 generation；终态先存快照再 complete；`pollTask` 不再破坏性删除 |
| executor 任务源 | `AsyncToolTaskExecutor` 注册/完成 promise；终态保留可重复 poll；`bindOwner` 绑定发起会话 |
| runtime 接入 | `ToolExecutor` 在参数验证后识别 wait 并进入 `TaskAwaitCoordinator`，绕过 `executeWithTimeout` 与 100ms steering 取消 |
| 独占 batch | `ToolOrchestration.partition` 依据纯参数把 wait 强制为单元素 exclusive batch；等待结束后若有 pending 用户输入，未启动的后续 call 补 skipped TOOL 结果 |
| 默认语义 | 无 `timeout_ms` 时等到终态/Stop/预算；正数到期返回 `WAIT_TIMEOUT`（FAILED，任务继续）；负数/非数字返回 `WAIT_INVALID_TIMEOUT` |
| 通知抑制 | registry 内 generation 级 tombstone；`BackgroundTaskManager.publishTerminal` 跳过自动通知但始终发 TaskStatus；`SessionCommandQueue` 按 `<task-generation>` 过滤已排队通知 |
| 能力守卫 | `ExecutionContext.waitCapability` 默认 `DISABLED`；CLI/desktop（CliAgent）显式 `ENABLED`；`AgentRunBuilder` 显式 `DISABLED` |
| 等待事件 | `AgentWaitEvent`（waiting/resuming/cleared）经 `SessionEventDispatcher`、CLI `EventBridge` 转发；旧客户端忽略后仍显示 RUNNING |
| 自等待保护 | 执行上下文 `taskId` 与目标相同时直接返回 `WAIT_UNAVAILABLE`，避免后台 subagent 等自己 |

### 12.2 未落地（仍按 P4/P5）

- `ToolCallAsyncTaskManager` / managed task：CLI 无该 store，server 默认禁用；命中时明确 `WAIT_UNSUPPORTED`，不挂起。
- Sandbox / Hub source adapter、跨 Pod 恢复、持久 continuation、消息 checkpoint/fencing：未实现。
- Desktop 前端未新增等待中的可视状态；事件已到客户端，UI 可后续接入。
- 跨 Agent 的 wait 图成环只做了“自等待”这一种直接环的拒绝；多跳环仍依赖 P2 的显式依赖规则。

### 12.3 验证

- `:core-ai:ciCheck` 通过（checkstyle/PMD/SpotBugs 与全量 core 测试）。
- 新增 `TaskAwaitCoordinatorTest`（park、Stop、超时恢复通知、同 ID generation、executor 终态保留、exclusive+steering）与 `AsyncTaskFlowTest.waitParksTheTurnWithoutCallingTheModelUntilTheTaskCompletes`（等待期间主 Agent 模型调用数保持 2，任务完成后才到 3，且不产生 notification turn）。
- `:core-ai-server:compileJava`、`:core-ai-cli:compileJava`、`:core-ai-cli:checkstyleMain/Test` 通过。
- `:core-ai-cli:releaseCheck` 仍被仓库既有问题阻断（`AppServerEngine`、`ModelSettingsApi`、`LlmProviderStore` 的 PMD 命中与 `LlmProviderStoreTest` 的 SpotBugs 命中，均非本次改动文件）；本次未修改这些存量问题。

