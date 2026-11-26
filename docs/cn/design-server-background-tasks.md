# Core AI Server 后台任务（服务端 run_in_background 与沙箱异步轮询）— Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | **DRAFT；待用户 Review；未批准实施** |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-26 |
| 需求提出者 / Reviewer | 用户；尚未批准方案或实施 |
| 作者 | core-ai |
| 产品入口 | Agent 工具 `task` / `run_bash_command` 的 `run_in_background`、`async_task_output` |
| 目标 | 让服务端会话真正支持后台 bash / 子代理，并让沙箱 runtime 的异步任务可被服务端轮询、通知和等待 |
| 本次交付 | 仅技术设计文档；不修改业务实现、不创建迁移、不部署、不执行真实后台任务 |
| 事实与提案 | 附录 B 为设计时源码核对；正文为目标设计。所有新增 API、字段、默认值均为提案，不代表当前已实现 |

## 结论摘要

1. **当前有两个独立缺口，必须一起补。** ① 服务端会话没有 `BackgroundTaskManager`（`InProcessAgentSession`
   只在上下文带 `SubagentOutputSinkFactory` 时创建它，而仓库里没有生产实现），所以 `task` 的
   `run_in_background=true` 会静默走同步分支；② 沙箱 runtime 的 `/execute` 已经能返回
   `pending + taskId`，`SandboxClient.pollTask()` 也已经存在，但服务端把 pending 结果登记到
   `ToolCallAsyncTaskManager` 后仍用**本地工具**轮询，导致 `Task not found: task-1`。
2. **后台子代理依赖 ①。** 补一个服务端 `SubagentOutputSinkFactory` 并在会话上下文里注入，
   `BackgroundTaskManager` 就会创建，`task` 的 `run_in_background=true` 立即可用。**沙箱会话里的
   `run_bash_command` 不会走服务器侧分支**——它被 `AgentSandbox.shouldIntercept` 拦截，必须依赖 ②。
3. **沙箱内后台工具依赖 ②。** pending 结果必须带“来自沙箱”的标记，轮询时路由回对应 sandbox 的
   `GET /tasks/{id}`，而不是本地 `AsyncToolTaskExecutor`。
4. **沙箱任务 id 必须按会话命名空间化。** runtime 的任务 id 是进程内的 `task-1`；不同 sandbox 会重复。
   服务端在登记前改写为 `s-<session hash>-<task-N>`，并把原始 id 持久化在任务记录里，才不会有跨会话
   覆盖和 poll 歧义。
5. **服务端后台任务（子代理、未被拦截的 bash）的输出文件放 `/workspace/.core-ai/tasks/<taskId>.out`。**
   所有 sandbox runtime 都有 `/workspace`（非 workspace 会话是临时的，workspace 会话是持久的）；通知里的
   结果仍是权威，sink 写入失败只降级为“没有输出文件”，不改变任务成败。**沙箱 runtime 的异步任务没有
   sink**；它们的结果在 `/tasks/{id}` 的响应和通知里（见 §4.5）。
6. **任务丢失要显式失败。** sandbox 被替换、workspace 睡眠后重建、runtime 30 分钟保留期过期时，轮询会
   得到 `task not found`；任务必须终止为 `SANDBOX_TASK_LOST` 并只通知一次，不能永远 pending。
7. **`wait` / `cancel` 对沙箱任务首版不支持。** 现有 `TaskAwaitCoordinator` 对 managed task 明确返回
   `WAIT_UNSUPPORTED`；`async_task_output` 的 `poll` 可用，`wait` 继续提示用 `poll`，`cancel` 在 runtime
   补 `/tasks/{id}/cancel` 之前不可用。这是已知边界，不是静默降级。
8. **Workspace 的租约/休眠要看到沙箱任务。** 2.0.392 已把 `turn state` 的 liveness 扩展到
   `hasDeferredWork()`（服务端 `BackgroundTaskManager`）；沙箱异步任务不在其中。首版在
   `WorkspaceService.sessionBusy` 上组合 `AsyncToolTaskService.hasOpenTask(sessionId)`，不改全局 turn
   state 的并发/准入语义。
9. **不新增数据库表、不需要迁移。** `app.async_tool_tasks.data` 是 JSON，新增
   `sandboxTask` / `sandboxTaskId` 字段向后兼容；服务端后台任务本身仍是进程内状态。
10. **首版边界：** 服务端后台任务不跨 Pod 重启；沙箱任务不跨 sandbox 重建；长于 runtime 30 分钟保留期的
    沙箱任务会丢失（用 workspace 的 `start.sh`/长任务方案在 P2 解决）。

## 阅读指南与目录

- 产品 / Reviewer：先读 §1、§3、§10，确认“哪些后台能力要承诺、哪些明确不支持”。
- 后端 / 执行平台：读 §2、§4–§7、§9，重点是 sink 注入、任务 id 命名空间和轮询路由。
- 测试 / 运维：读 §4.8、§7、§8，确认失败矩阵与验收步骤。

| 章节 | 内容 |
|---|---|
| [1. 需求与范围](#_1-需求与范围) | 原始需求、目标/非目标、需求追踪、首版边界 |
| [2. 现状与问题](#_2-现状与问题) | 工具层、会话层、沙箱 runtime、服务端异步任务、Workspace、实测证据 |
| [3. 核心模型与不变量](#_3-核心模型与不变量) | 任务身份、来源、状态、单写者与通知语义 |
| [4. 设计](#_4-设计) | sink、上下文注入、id 命名空间、轮询中继、wait/cancel、Workspace 活动、配置 |
| [5. 数据模型与兼容](#_5-数据模型与兼容) | AsyncTaskData 新字段、无迁移、回滚 |
| [6. 安全、隐私与网络](#_6-安全、隐私与网络) | 输出文件边界、sandbox 鉴权、secret |
| [7. 可观测性、失败处理与运维](#_7-可观测性、失败处理与运维) | 指标、失败矩阵、runbook、kill switch |
| [8. 验收与实施计划](#_8-验收与实施计划) | 验收矩阵、测试策略、P0–P3、上线/回滚 |
| [9. 风险登记](#_9-风险登记) | 概率/影响/缓解 |
| [10. ADR 与待 Review 决策](#_10-adr-与待-review-决策) | 关键取舍、需要用户拍板的问题 |
| [附录 A](#附录-a-用户原始需求与文档治理) | 用户原始需求、文档治理 |
| [附录 B](#附录-b-源码核对) | 设计时源码事实与行号 |
| [附录 C](#附录-c-review-与修改历史) | Review 记录、修改历史 |
| [附录 D](#附录-d-术语) | 术语 |

## 1. 需求与范围

### 1.1 原始需求

用户在 2026-11-26 的会话中明确要求：

> “下一步做什么”

> “后台任务：先出设计（docs/cn 设计文档，评审后再实现）”

在此之前，用户已确认 Workspace P1b 的核心工作，并把“后台任务”列为下一步；背景是 Workspace 设计
§4.5/§6.4 要求 `run_in_background` 延长 Turn Lease，而 dev 实测发现服务端实际不支持后台执行（见
§2.6）。本设计只解决“服务端后台任务真正可用”，不包含 Workspace 的 bootstrap/start、`/env` API 或前端。

### 1.2 目标与非目标

**目标**

1. 服务端会话中，`task` 工具的 `run_in_background=true` 立即返回 task id，子代理继续在后台运行，
   完成后以任务通知回到会话。
2. 服务端会话中，`run_bash_command` 的 `run_in_background=true` 由 sandbox runtime 异步执行，服务端能
   `poll` 到 pending/completed/failed，并在终态通知会话。
3. 后台任务运行期间，Workspace 的 Turn Lease、空闲休眠、租约接管都把该会话视为 busy。
4. 任务失败、sandbox 丢失、服务重启时行为明确：要么可恢复，要么显式失败并通知一次。
5. 不改变现有前台工具、同步 sandbox 执行、快照/沙箱生命周期的语义。

**非目标（首版）**

- 服务端后台任务跨 Pod 重启持久化（`BackgroundTaskManager` 仍是进程内）。
- 沙箱任务在 sandbox 重建后的续跑/续查。
- 沙箱任务的 `wait` 与 `cancel`（见 §4.6）。
- 后台任务的用户可见 UI 页面（现有聊天任务状态事件继续使用）。
- 长于 runtime 30 分钟保留期的任务；workspace bootstrap/start 的 2 小时任务属于后续设计。
- 多租户共享后台执行池、优先级队列、跨用户配额（只做会话级上限）。

### 1.3 需求追踪

| ID | 需求 | 设计落点 |
|---|---|---|
| RQ1 | `task` 的 `run_in_background=true` 真正后台执行 | §4.2、§4.3 |
| RQ2 | `run_bash_command` 的 `run_in_background=true` 经 sandbox runtime 执行并可轮询 | §4.4、§4.5 |
| RQ3 | 任务完成后回到原会话（通知一次、可重试、不重复） | §3.3、§4.5 |
| RQ4 | 后台任务期间 Workspace 不被休眠/接管 | §4.7 |
| RQ5 | sandbox 被替换/任务过期时显式失败 | §4.5、§7.2 |
| RQ6 | 不新增迁移、可回滚 | §5、§8.4 |
| RQ7 | 前端/Agent 不需要新增工具；沿用 `async_task_output` | §4.6 |
| RQ8 | 输出可被 Agent 读取（文件路径稳定） | §4.2 |

### 1.4 首版边界

| 首版纳入 | 首版不纳入 |
|---|---|
| 服务端 `task` 后台子代理 | 后台子代理跨 Pod 重启恢复 |
| sandbox `run_bash_command` / `run_python_script` 的 `run_in_background` | 其他沙箱工具的异步化（按同一机制扩展即可） |
| `async_task_output` 的 `poll` | 沙箱任务的 `wait` / `cancel` |
| Workspace 租约看到沙箱任务 | Workspace 输出文件自动清理 |
| 任务丢失显式失败 | sandbox 重建后的任务迁移 |
| 会话级任务并发上限 | 跨用户后台任务配额/计费 |
| **只覆盖 chat session**（`SessionContextBuilder`） | eval / workflow / AgentRun / API run / OCG 等路径的后台任务（保持现状） |

## 2. 现状与问题

### 2.1 工具层：后台分支存在，但服务端没有 manager

- `TaskTool` 的后台分支只在 `context.getTaskManager() != null` 时走
  `launchBackground`（`core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:196-197`、`:233-248`）。
  否则同一调用会走同步 `runSync`。
- `ShellCommandTool` 同理：`runInBackground && taskManager != null` 才 `taskManager.submit(...)`
  （`core-ai/src/main/java/ai/core/tool/tools/ShellCommandTool.java:355-371`），否则同步执行。
- **但 sandbox 会话根本走不到这个分支**：`run_bash_command` / `run_python_script` / 文件工具在
  `SandboxConstants.INTERCEPTED_TOOLS` 里（`core-ai/src/main/java/ai/core/sandbox/SandboxConstants.java:18-26`），
  `ToolExecutor` 直接把调用交给 `sandbox.execute`，由 runtime 决定同步还是 pending。因此聊天会话的
  bash 后台路径必须靠 §4.5 的沙箱中继，不能靠 P0 的 server manager。
- `InProcessAgentSession` 只有在 `ExecutionContext.getSubagentOutputSinkFactory() != null` 时创建
  `BackgroundTaskManager`（`core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:126-127`）。
- 仓库中 `SubagentOutputSinkFactory` 只有测试实现（`TaskToolTest`、`AsyncTaskFlowTest` 的 in-memory
  factory），没有生产实现，服务端上下文也没有设置它（`core-ai-server` 中无
  `subagentOutputSinkFactory(` 调用）。

**结果：** `run_in_background=true` 在服务端静默退化为同步执行。

### 2.2 会话层：context 是唯一注入点

- `SessionContextBuilder.build` 是服务端聊天会话 `ExecutionContext` 的唯一构造点
  （`core-ai-server/src/main/java/ai/core/server/session/SessionContextBuilder.java:70-81`），已有
  `withAsyncTaskManager`（`:54-57`），但没有 sink factory 的入口。
- 三个构造点：`AgentSessionManager.java:164`、`:247`、`SessionRebuildManager.java:283`。
- `BackgroundTaskManager` 需要 `SessionCommandQueue`、sink factory、sessionId、事件 dispatcher 和
  `TaskRunCoordinator`（`core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:106-113`）；
  `InProcessAgentSession` 已具备除 sink factory 之外的全部依赖。

### 2.3 沙箱 runtime：异步任务已实现，但没有服务端消费者

- `/execute` 解析 `run_in_background` / `async`，提交到 `TaskRegistry` 并返回
  `{status:"pending", taskId}`（`core-ai-sandbox-runtime/main.go:392-421`、`:1281-1287`）。
- `GET /tasks/{id}` 返回 `status/result/duration`（`main.go:432-457`）。
- `TaskRegistry`：进程内 `task-<n>` id、最多 5 个并发、终态保留 30 分钟、结果截断 30KB
  （`main.go:75-147`、`:1271-1276`）。
- **没有** `/tasks/{id}/cancel` 端点；任务不会跨 runtime 进程存活。
- 服务端 `SandboxClient.pollTask(taskId)` 已存在（`GET /tasks/{id}`）
  （`core-ai-server/src/main/java/ai/core/server/sandbox/SandboxClient.java:166-178`），但**没有任何调用方**。
- `SandboxClient.parseResponse` 把 pending 映射为 `ToolCallResult.pending(response.taskId, "Async task
  submitted: ...")`（`:262-265`）。

### 2.4 服务端异步任务：登记了，但轮询用错了工具

- `ToolExecutor.handleAsyncResult` 对 pending 结果登记 `ToolCallAsyncTask`，并按 `context.getSessionId()`
  绑定 owner（`core-ai/src/main/java/ai/core/tool/ToolExecutor.java:420-448`）。
- `ToolCallAsyncTaskManager.pollTask` 用**存储的工具名**解析本地工具并调用 `tool.poll(taskId)`
  （`core-ai/src/main/java/ai/core/tool/ToolCallAsyncTaskManager.java:146-186`）。
- 沙箱 pending 结果的 `task-1` 属于 runtime；本地 `ShellCommandTool.poll` 查的是
  `AsyncToolTaskExecutor`（`core-ai/src/main/java/ai/core/tool/tools/ShellCommandTool.java:400-403`），
  两个注册表不连通 → `Task not found: task-1`。
- `ToolCallAsyncTaskManager` 的持久化是 `app.async_tool_tasks(id, data JSONB, updated_at)`
  （`core-ai-server/src/main/java/ai/core/server/asynctask/PgAsyncTaskPersistence.java:22-26`），新增字段不需要
  迁移。
- `AsyncTaskData` 目前只有 `taskId/toolName/sessionId/originalCall/status/...`
  （`ToolCallAsyncTaskManager.java:319-346`），没有“这个任务来自哪个 sandbox”。

### 2.5 Workspace：Turn State 已扩展，但只覆盖 `hasDeferredWork`

- 2.0.392 把 `SessionEventListenerWiring` / `SessionRebuildManager` 的 liveness 探针改为
  `isTurnRunning() || hasDeferredWork()`，`TurnStateListener` 在终态只在没有 deferred work 时清除
  （`core-ai-server/src/main/java/ai/core/server/messaging/TurnStateListener.java`、
  `TurnStateRegistry.java`）。
- `hasDeferredWork()` = `turnInput.hasPending() || taskManager.hasRunningTasks()`
  （`core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:492-495`）。
- **沙箱异步任务不在其中**：它登记在 `ToolCallAsyncTaskManager`，不经过 `BackgroundTaskManager`。
  因此“后台 bash 在 sandbox 里跑”时，`sessionBusy` 仍可能为 false，Workspace 会被休眠或允许接管。
- `WorkspaceService.sessionBusy` 由 `RuntimeGovernanceModule` 从 `TurnStateRegistry.liveness` 赋值；
  租约接管、维护租约和空闲休眠都读它。

### 2.6 实测证据（dev，2.0.392）

1. `task` 参数确实带 `run_in_background=true`，但工具在 **183.5s 后同步返回**（子代理的
   `run_bash_command` 前台跑了 180s），没有产生后台任务。
2. 工作区会话内 `run_bash_command` + `run_in_background=true` 返回 `Async task submitted: task-1`，随后
   通知轮询得到 `Task not found: task-1`。
3. 结论：这不是模型/提示词问题，而是服务端缺少 manager 和 sandbox 轮询路由。完整记录：
   `docs/validation/2026-11-26-dev-workspace-async-hold.md`。

## 3. 核心模型与不变量

### 3.1 任务来源与身份

| 来源 | 谁执行 | 状态存放 | 服务端轮询路径 | 首版 |
|---|---|---|---|---|
| `SERVER` | 服务端线程（`BackgroundTaskManager`） | 进程内 map | `BackgroundTaskManager.pollTask` | ✅ |
| `SANDBOX` | sandbox runtime 的 `TaskRegistry` | runtime 进程内 | `Sandbox.pollTask` → `GET /tasks/{id}` | ✅ |
| `MANAGED` | 外部 API（渲染/视频等，`ToolCallAsyncTaskManager`） | `app.async_tool_tasks` | 本地工具的 `poll()` | 保持现状 |

- 对 Agent 可见的 task id 是**会话内全局唯一**的字符串；服务端内部再映射到后端 id。
- `SERVER` / `MANAGED` 的 task id 由工具生成（子代理 UUID、渲染任务 id）；`SANDBOX` 的 runtime id
  （`task-1`）只在 runtime 进程内有意义，服务端在登记前生成新的 Agent 可见 id（见 §4.4）。
- 任务归属：`sessionId` 是唯一归属键；一个任务只能被一个 session 消费；跨 session 的 poll/wait 一律拒绝。

### 3.2 不变量

1. **不静默降级**：如果服务端没有后台执行能力（flag 关闭、无 sandbox），`run_in_background=true` 必须在
   工具结果里明确说明“已同步执行 / 后台不可用”，不能把同步执行伪装成后台任务。
2. **单归属**：一个 Agent 可见 task id 同时只属于一个 session、一个后端；不同 sandbox 的 `task-1` 不得
   互相覆盖。
3. **通知一次**：任务终态最多产生一条“任务完成”通知；通知通过命令队列重试直到会话接受，被 `poll`
   消费后抑制；不得重复注入。
4. **输出尽力而为**：输出文件写入/上传失败只记录日志并降级为“无输出文件”，不改变任务的
   completed/failed 状态。
5. **Workspace 单写者不变**：后台任务不绕过 Turn Lease；后台任务运行期间 Workspace 仍只允许一个
   turn 持有写权限；后台任务结束前不得休眠/接管。
6. **丢失显式化**：后端不存在（sandbox 被替换/任务过期/进程重启）时，任务必须在有限次轮询内终止为
   `SANDBOX_TASK_LOST` / 失败并通知一次，不能永远 pending。
7. **无迁移兼容**：旧 `AsyncTaskData` 记录没有新字段时按 `sandboxTask=false` 处理；旧任务继续可 poll。
8. **只由能触达后端的 Pod 轮询**：服务端后台任务只由持有会话的 Pod 轮询；沙箱任务只由持有该
   sandbox 的 Pod 轮询；跨 Pod 的会话由 owner 检查跳过（沿用 `AsyncToolTaskService` 的现有机制）。

### 3.3 生命周期

```text
submit ──► pending ──► completed / failed / timeout / lost
                 │
                 ├─ notification pending ──► delivered（或重试到放弃）
                 └─ poll 消费终态（可选）──► 抑制通知
```

- `SERVER`：提交后立即返回 task id，任务在线程池执行；终态写 `terminalTasks`、派发 `TaskStatusEvent`、
  入队任务通知（`BackgroundTaskManager.java:354-379`）。
- `SANDBOX`：runtime 返回 `pending`；服务端登记任务并每轮询一次 runtime，终态时走
  `ToolCallAsyncTaskManager` 的通知路径（与渲染任务一致）。
- `MANAGED`：不在本设计范围内，仅保证不被沙箱路径误伤（见 §4.5 的“不得用本地工具轮询沙箱任务”）。

## 4. 设计

### 4.1 总体架构

```text
Agent tool call
   │
   ├─ task(run_in_background) ──► BackgroundTaskManager.submit
   │                                   ├─ output sink ──► sandbox /workspace/.core-ai/tasks/<id>.out
   │                                   └─ terminal ──► TaskStatusEvent + task notification (command queue)
   │
   └─ run_bash_command(run_in_background)
           └─ Sandbox.execute ──► runtime /execute ──► pending + taskId
                                      │
                                      ▼
                          ToolExecutor.handleAsyncResult
                                      │ namespace id + persist
                                      ▼
                          ToolCallAsyncTaskManager (app.async_tool_tasks)
                                      │ owner-pod poll
                                      ▼
                          Sandbox.pollTask(rawId) ──► runtime GET /tasks/{id}
                                      │ terminal
                                      ▼
                          task notification (同一队列)
```

### 4.2 服务端输出 Sink（让 BackgroundTaskManager 能创建）

**目标：** 为 `BackgroundTaskManager` 里的任务（后台子代理、未被沙箱拦截的 bash）提供可用的
`SubagentOutputSinkFactory`，输出写到 sandbox 内，Agent 可通过 `read_file` / `tail` 读取。
注意：sandbox 会话的 `run_bash_command` 被拦截，执行发生在 runtime，不经过这个 sink（§4.5）。

**实现提案**

- 新类 `SandboxSubagentOutputSinkFactory implements SubagentOutputSinkFactory`，每个会话一个实例：
  - 构造参数：`SandboxService sandboxService`、`String sessionId`、`long maxBytes`。
  - `create(taskId)` 返回 `SandboxSubagentOutputSink`，其 reference 固定为
    `/workspace/.core-ai/tasks/<taskId>.out`。
- `SandboxSubagentOutputSink`：
  - 在内存中缓冲输出（上限 `maxBytes`，超出部分丢弃并追加
    `\n[output truncated at N bytes]`）；
  - `close()` 时通过 `sandboxService.getSandbox(sessionId)` 取当前 sandbox handle，调用
    `uploadFile(path, bytes)` 一次性写入；`getSandbox` 为空或上传失败只记录 WARN，不抛出；
  - `write` / `close` 都不得向调用方抛异常（保证不变量 4）。
- 路径选择：统一 `/workspace/.core-ai/tasks/`。所有 sandbox runtime 都以 `/workspace` 为工作目录
  （`core-ai-sandbox-runtime/main.go:151-153`）；非 workspace 会话的 `/workspace` 是临时的，workspace
  会话的是持久卷。这样不需要区分会话类型，且 Agent 读文件路径一致。
- 生命周期：`BackgroundTaskManager.submit` 在提交时创建 sink 并读取 reference（`BackgroundTaskManager.java:155-156`）；
  输出在任务结束时写入，`close()` 由 `runAgentWithSink` 的 finally 调用（`:50-73`）。

**需要的 core-ai 行为修正**

`BackgroundTaskManager.runAgentWithSink` 现在把 `sink.write(result)` 放在 try 内、写失败会被 catch 成
任务失败（`:50-73`）。首版改为 `writeQuietly(sink, ...)`（内部 try/catch + WARN），确保“输出文件失败
不改变任务状态”。

**没有 sandbox 的会话**

`SandboxService.getSandbox(sessionId)` 为空（sandbox 关闭/未启用）时，不创建 sink factory →
`BackgroundTaskManager` 不创建 → 工具走同步。此时 `TaskTool` / `ShellCommandTool` 在
`run_in_background=true` 且没有 task manager 时应在结果里追加一行
`background execution is unavailable for this session; the call ran synchronously`，满足不变量 1。

### 4.3 上下文注入

- `SessionContextBuilder` 新增：
  ```java
  public SessionContextBuilder withSubagentOutputSinkFactory(SubagentOutputSinkFactory factory)
  ```
  在 `build(sessionId, userId)` 时调用 `ExecutionContext.builder().subagentOutputSinkFactory(factory)`
  （`SessionContextBuilder.java:70-81`）。
- 三个构造点创建 `new SandboxSubagentOutputSinkFactory(sandboxService, sessionId, maxBytes)`：
  `AgentSessionManager.java:164`、`:247`、`SessionRebuildManager.java:283`。三处都已持有
  `SandboxService`；factory 内部延迟取 sandbox，不要求构造时 sandbox 已存在。
- `InProcessAgentSession` 的现有逻辑（`:126-127`）会因 factory 非空自动创建
  `BackgroundTaskManager`，其余依赖（`SessionCommandQueue`、`TaskRunCoordinator`、dispatcher）已经就绪。
- `BackgroundTaskManager` 使用 `AsyncToolTaskExecutor` 的全局线程池
  （`BackgroundTaskManager.java:106-113`）；首版不新增线程池，但要在文档和指标里暴露任务排队/饱和。

### 4.4 沙箱 pending 结果的接管与 task id 命名空间

**问题：** runtime 的 task id 是 `task-<n>`，每个 runtime 进程从 1 开始；服务端
`app.async_tool_tasks` 的 key 是全局的 `TASK_PREFIX + taskId`（
`ToolCallAsyncTaskManager.java:305-308`）。两个 sandbox 同时产生 `task-1` 会互相覆盖；同一个 session
在 sandbox 重建后也会复用 `task-1`。

**设计：**

1. `ToolExecutor.handleAsyncResult` 在登记前识别“来自沙箱的 pending”：
   - `result.getStats().get("sandboxStatus") == "pending"` 且 `result.getTaskId()` 非空
     （`SandboxClient.parseResponse` 已写入，`SandboxClient.java:262-265`）；
   - 通过 `context.getSandbox()` 取到当前 sandbox。
2. 服务端生成新的 Agent 可见 id：`st-<uuid>`（或 `s<session8><sandbox6>-<task>`；推荐 UUID，
   完全避免解析和碰撞）。把原始 runtime id 存到 stats/任务记录：
   - `result.withTaskId(agentTaskId).withResult("Async task submitted: " + agentTaskId)`
     （`ToolCallResult.withTaskId` / `withResult`，`ToolCallResult.java:160`、`:227`）；
   - `result.withStats("sandboxTaskId", rawTaskId)`、`withStats("sandboxTask", "true")`；
   - **顺序**：先改写 id 与 result 文本，再 `bindOwner` 与 `storeTask`，否则 owner 绑定/持久化的是旧 id。
3. `ToolCallAsyncTaskManager` 在 `storeTask` 时把 `sandboxTask` / `sandboxTaskId` 写入
   `AsyncTaskData`（新增字段，见 §5）。Agent/通知/`async_task_output` 全程使用新 id。
4. 轮询时 `ToolCallAsyncTaskManager.pollTask` 先看 `data.sandboxTask`：
   - 是 → 调用宿主的 `SandboxTaskPoller`（§4.5），**绝不**解析本地工具；
   - 否 → 现状（本地工具 poll）。
5. `isFreshTask` / “tool not available” / “AsyncTaskOutputTool 不支持 polling” 这些分支对
   `sandboxTask` 必须跳过（否则会把任务丢掉）。

### 4.5 沙箱轮询中继

**Sandbox 抽象扩展**

在 `Sandbox` 接口新增：

```java
default ToolCallResult pollTask(String taskId) {
    throw new UnsupportedOperationException("poll is not supported");
}
```

实现：

| 实现 | 行为 |
|---|---|
| `AgentSandbox` | `runtimeClient.pollTask(taskId)`（`SandboxClient.java:166-178`） |
| `DockerSandbox` / `KubernetesSandbox` | 同样持有 `runtimeClient`，直接 `runtimeClient.pollTask(taskId)` |
| `LazySandbox` | 委托到已 materialize 的 sandbox（必要时先 materialize） |
| `WorkspaceSandbox` | `runtime().pollTask(taskId)`，走**无租约**的运行时常驻路径；不触发 Turn Lease |

**宿主轮询委托**

- `ToolCallAsyncTaskManager` 增加可选的 `SandboxTaskPoller` 钩子：
  ```java
  @FunctionalInterface
  public interface SandboxTaskPoller {
      ToolCallResult poll(String sessionId, String sandboxTaskId, String sandboxId);
  }
  ```
  由服务端 `AsyncToolTaskService` 的 `ServerAsyncTaskManager` 设置。
- 服务端实现：
  ```java
  (sessionId, rawTaskId, sandboxId) -> {
      var sandbox = sandboxService.getSandbox(sessionId);
      if (sandbox == null) return null;                    // 可重试：会话不在本 Pod/尚未重建
      return sandbox.pollTask(rawTaskId);
  }
  ```
  - `null` 表示“暂时不可达”，保持 pending，由丢失阈值兜底；
  - 非 null 的 failed + `task not found` 视为后端确定丢失。
- 轮询频率沿用 `AsyncToolTaskService` 的 `async-tool-task-poll`（默认 10s，
  `AsyncToolTaskModule.java:22-29`）；单个任务连续 3 次不可达或收到 `task not found` 后终止为
  `SANDBOX_TASK_LOST`（`failed`），只通知一次。
- 跨 Pod：`pollOpenTasks` 已按 `ownedElsewhere(sessionId)` 跳过非本 Pod 会话
  （`AsyncToolTaskService.java:74-89`）；本 Pod 的 `sandboxService.getSandbox(sessionId)` 可用。
- 任务记录里的 `sandboxId`（可选）用于日志/诊断；轮询实际只依赖 `sessionId`（同一会话的当前
  sandbox）。sandbox 重建后旧任务必然丢失，按上一条终止。

**终态与通知**

- runtime 返回 `completed` / `failed` / `timeout` → 映射为 `ToolCallResult` 终态；
  `timeout` 用 `hardFailed`（这是执行器层失败，不是工具的业务失败）。
- 终态走 `ToolCallAsyncTaskManager.announce` → 命令队列任务通知（现有逻辑，
  `ToolCallAsyncTaskManager.java:288-311`）；`async_task_output poll` 读到终态时
  `markNotificationDelivered`（`AsyncTaskOutputTool.java:108-112`）抑制通知。
- 任务通知使用新的 Agent 可见 id；模型可继续 `async_task_output`。

### 4.6 wait / cancel 的首版行为

- **wait**：`TaskAwaitCoordinator.resolve` 对 managed task 返回
  `WAIT_UNSUPPORTED ... use action='poll'`（`TaskAwaitCoordinator.java:169-174`）。沙箱任务属于
  managed，首版保持该行为：
  - `async_task_output action='wait'` → 明确提示用 `poll`；
  - Agent 提示词/工具描述已提示“wait 不可用时用 poll”，不需要新工具。
- **cancel**：runtime 没有 `/tasks/{id}/cancel`（§2.3）。首版 `async_task_output action='cancel'` 对
  `sandboxTask` 返回 `SANDBOX_TASK_CANCEL_UNSUPPORTED`；实现上必须在
  `ToolCallAsyncTaskManager.cancelTask` 里先判断 `sandboxTask`，不能落到“用本地工具 cancel”分支；
  P2 再补 runtime 端点与中继。
- **P2 方向**：为 managed task 增加“poll-based completion producer”（宿主按现有轮询频率构造
  `TaskCompletionRegistry.Entry`），让 `wait` 与后台任务一致；或由 `AsyncToolTaskService` 在终态时
  完成该 entry。此设计不在首版范围。

### 4.7 Workspace 活动信号

**问题：** `hasDeferredWork()` 只覆盖 `BackgroundTaskManager` 与 turn 输入；沙箱后台任务不在 Turn
State 里，Workspace 可能在任务运行时休眠或允许另一个会话接管。

**设计（首版）：**

- `AsyncToolTaskService` 新增：
  ```java
  public boolean hasOpenSandboxTask(String sessionId)
  ```
  实现为 `manager().listTasks()` 中 `open && sandboxTask && sessionId.equals(sessionId)`；只在
  Workspace 的租约/休眠判定和心跳中调用（频率：每 turn 一次、每 1 分钟每个 READY workspace 一次、
  心跳每 ~200s 一次），可加 5s 内存缓存。`TaskSnapshot` 增加 `sandboxTask` 字段（从
  `AsyncTaskData` 读取），否则无法过滤。
- `RuntimeGovernanceModule` 组合（保持全局 turn state 不变）：
  ```java
  bean(WorkspaceService.class).sessionBusy = sessionId ->
      turnStates.liveness(sessionId) != TurnStateRegistry.TurnLiveness.NOT_RUNNING
          || asyncToolTaskService.hasOpenSandboxTask(sessionId);
  ```
- 后台子代理已经由 `hasDeferredWork()`（`BackgroundTaskManager`）覆盖；这里只补沙箱任务。渲染/视频等
  managed task 是否也阻止休眠见 §10 D3（推荐首版不计入）。
- 非 Workspace 的 `SandboxCleanupJob` 也用 `sessionBusy` 判定“会话是否在忙”
  （`SandboxService.sessionBusy`，由 `RuntimeGovernanceModule` 设置）；SERVER 任务会让 Turn State
  保持 RUNNING，因此普通 sandbox 不会被 TTL 清理误杀。
- 这样租约接管（`WorkspaceLeaseManager.stealable`）、维护租约、空闲休眠、`WorkspaceSandbox` 心跳
  都自动把“有沙箱任务”的会话视为 busy。
- **为什么不动全局 Turn State**：全局 liveness 同时驱动每用户并发配额和准入；把渲染/视频等
  managed task 计入“in-flight turns”会改变准入语义。Workspace 只关心“这个会话是否还在使用它的
  卷”，用 workspace 专属 predicate 更窄、可回滚。渲染任务是否也阻止休眠见 §10 D3。

### 4.8 配置与限额

| 配置键 | 默认 | 含义 |
|---|---|---|
| `sys.tasks.background.enabled` | `false`（dev `true`） | 总开关；关闭时不创建 sink factory/manager，工具按同步执行并明确提示 |
| `sys.tasks.background.maxPerSession` | `5` | 服务端后台任务并发上限（与 runtime 的 5 对齐） |
| `sys.tasks.background.outputMaxBytes` | `1048576` | 输出文件上限；超出截断并标注 |
| `sys.tasks.sandbox.lostPolls` | `3` | 连续不可达/not found 次数达到后终止为 `SANDBOX_TASK_LOST` |
| `sys.tasks.sandbox.pollIntervalSeconds` | 沿用 `sys.asyncTask.pollSeconds`（10） | 沙箱任务轮询间隔 |

**现有限额（不在本设计修改）：**

- runtime：每个 sandbox 最多 5 个并发异步任务、终态保留 30 分钟、结果截断 30KB
  （`main.go:100-147`、`:1271-1276`）。
- 服务端 `BackgroundTaskManager` 没有独立上限；`AsyncToolTaskExecutor` 全局线程池饱和会延迟任务。
  首版增加会话级上限与指标，不新建执行池。
- Workspace 输出文件：`/workspace/.core-ai/tasks` 不自动清理（P2 加保留期；见 §10 D4）。

## 5. 数据模型与兼容

### 5.1 AsyncTaskData 新增字段

`app.async_tool_tasks.data` 是 JSON（`PgAsyncTaskPersistence.java:22-26`），新增字段不需要迁移：

| 字段 | 类型 | 含义 |
|---|---|---|
| `sandboxTask` | `Boolean` | 该任务由 sandbox runtime 执行，轮询必须走 `Sandbox.pollTask` |
| `sandboxTaskId` | `String` | runtime 原始 task id（如 `task-1`），只用于中继 |
| `sandboxId` | `String`（可选） | 诊断用：任务提交时的 sandbox/CR 名称 |

- `AsyncTaskData.from(task)` 从 `ToolCallAsyncTask` 的初始结果 stats 读取
  `sandboxTask` / `sandboxTaskId`；旧记录缺失时视为 `false` / `null`。
- `restoredResult()` 对 `sandboxTask` 的终态返回同样的 completed/failed 文本；不需要新状态枚举。
- `ToolCallAsyncTaskManager` 的 `TASK_PREFIX` 仍是 `task:`（或现有前缀），Agent 可见 id 放在
  `data.taskId`。

### 5.2 任务记录的清理

- 现有 `AsyncToolTaskService.TERMINAL_RETENTION = 24h`（`AsyncToolTaskService.java:32`）会清理终态任务；
  丢失任务在 `SANDBOX_TASK_LOST` 终态后同样进入 24h 清理。
- 仍有打开但永远不会终态的任务（例如 runtime 一直不可达且未达到 lost 阈值）会占用记录；lost 阈值是
  兜底，必须保证最终终态。

### 5.3 兼容与回滚

- 旧服务端读新记录：忽略新字段，按“本地工具轮询”处理（旧任务本来就只可能是本地/渲染任务）。
- 新服务端读旧记录：`sandboxTask=false`，行为不变。
- 回滚：关闭 `sys.tasks.background.enabled` 并重启；已登记的沙箱任务记录若仍存在，轮询会因 flag
  关闭而不再执行（P0 的 poll 路径受 flag 保护），24h 后自动清理；不产生数据损坏。

## 6. 安全、隐私与网络

- **输出文件边界**：文件只写在用户自己的 sandbox 内；workspace 会话落在用户卷上，与用户自己写的文件
  同一信任边界。路径由服务端生成（`<uuid>`），不接受用户输入，避免路径穿越。
- **敏感信息**：后台命令/子代理的输出可能包含 token 或环境变量；这些内容本来也会出现在工具结果和聊天
  记录中。文档与提示词继续要求“不要把 secret 写进文件”；输出文件与聊天记录同等级别，不做额外加密。
- **跨用户隔离**：`SandboxService.getSandbox(sessionId)` 只返回本会话的 sandbox；任务轮询带
  `sessionId`，不接受其他会话的 task id（`ToolExecutor` 的 owner 绑定与
  `TaskAwaitCoordinator` 的 ownerScope 已有机制）。
- **网络**：轮询使用现有 sandbox client 与 runtime 的内部地址；不新增对外端口或公开 API。
- **DoS**：会话级并发上限 5（与 runtime 一致）、输出上限 1 MiB、轮询 10s 一次；超限返回明确错误。

## 7. 可观测性、失败处理与运维

### 7.1 指标 / 日志

| 指标/日志 | 含义 |
|---|---|
| `tasks.background.started` / `settled` / `failed` | `BackgroundTaskManager` 的任务计数 |
| `tasks.background.rejected` | 超过会话上限被拒绝 |
| `tasks.sandbox.polls` / `poll_miss` | 轮询次数与 sandbox 不可达次数 |
| `tasks.sandbox.lost` | `SANDBOX_TASK_LOST` 终止数 |
| `tasks.notification.retry` / `abandoned` | 现有通知重试/放弃 |
| `tasks.output.write_failed` | sink 写入失败 |
| `tasks.output.bytes` | 输出大小分布（p50/p95/max） |

日志统一带 `sessionId`、`taskId`（Agent 可见）、`sandboxTaskId`、`tool`、`source`。

### 7.2 失败矩阵

| 失败 | 表现 | 首版行为 |
|---|---|---|
| 服务端 Pod 重启（SERVER 任务在跑） | 内存 task 丢失 | 任务丢失；`async_task_output poll` 返回 not found；无通知。P2 再讨论持久化 |
| sandbox 被替换/workspace 睡眠（SANDBOX 任务） | runtime `task not found` | 连续 3 次/明确 not found → `SANDBOX_TASK_LOST`，通知一次 |
| runtime 30 分钟保留期过期 | 轮询 not found | 同上 |
| sandbox 不可达（网络/重建中） | poll 返回 null | 保持 pending，达到 lost 阈值后失败 |
| 输出文件写入失败 | sandbox 不可达/权限 | WARN，任务结果仍以通知为准 |
| 通知投递失败 | 命令队列拒绝 | 现有重试/退避；超过次数标记 abandoned |
| 会话在任务运行中被关闭 | session 消失 | 后台任务随会话生命周期结束（best-effort）；不保证通知；文档明确 |
| 达到并发上限 | 第 6 个任务 | 立即失败，提示 `background task limit reached (5)` |
| flag 关闭 | 工具调用 | 同步执行 + 结果明确提示（不变量 1） |

### 7.3 运维 / runbook

1. **确认后台能力**：`/api/capabilities` 是否暴露 `background_tasks`（提案）；或查看
   `sys.tasks.background.enabled`。
2. **任务卡住**：查 `app.async_tool_tasks`（`sandboxTask=true` 的行）与对应 sandbox runtime 的
   `/tasks/{id}`；查 `tasks.sandbox.poll_miss` 日志。
3. **输出缺失**：查 `tasks.output.write_failed`；确认 sandbox 在任务结束时仍存在。
4. **紧急回滚**：把 `sys.tasks.background.enabled=false` 写入部署 env（或 ConfigMap）并滚动；
   在跑的任务随 Pod 重启终止。没有数据库回滚动作。
5. **清理**：终态任务 24h 自动清理；workspace 输出文件人工清理（P2 自动）。

## 8. 验收与实施计划

### 8.1 验收矩阵

| ID | 场景 | 通过标准 |
|---|---|---|
| A1 | `task` 子代理 `run_in_background=true` | 工具在 2s 内返回 task id；父 turn 结束；子代理继续；通知回到会话；结果为子代理输出 |
| A2 | 工作区 `run_bash_command` 后台（`sleep 30; echo OK`） | 工具 <2s 返回 pending；`async_task_output poll` 先 pending 后 completed；通知一次；结果文本为 `OK`（沙箱任务没有 sink 输出文件） |
| A3 | 两个 sandbox 同时产生 `task-1` | 两个任务 id 不同；结果/通知不串会话；store 无覆盖 |
| A4 | 后台任务运行期间 Workspace 空闲 | `runtime_status` 保持 READY；无 sleep/接管；任务结束后按 `idleSleepMinutes` 休眠 |
| A5 | 任务运行中删除/重建 sandbox | 轮询得到 `SANDBOX_TASK_LOST`；通知一次；不永久 pending |
| A6 | flag 关闭 | 工具结果明确“后台不可用、已同步执行”；不出现假 task id |
| A7 | 服务端 Pod 重启 | SERVER 任务丢失（`poll` not found）；不产生重复通知；文档记录 |
| A8 | `poll` 消费终态 | 后续不再收到重复任务通知 |
| A9 | 输出上传失败 | 任务仍 completed；日志有 `tasks.output.write_failed` |
| A10 | 第 6 个并发任务 | 立即失败并给出上限错误；不影响已有任务 |

### 8.2 测试策略

- **core-ai 单测**：
  - `BackgroundTaskManager` sink 写入失败不改变终态（writeQuietly）。
  - `ToolCallAsyncTaskManager` 对 `sandboxTask=true` 的分支：不解析本地工具、调用 poller、poller 返回
    null/not found 的行为。
  - task id 命名空间生成/唯一性测试。
- **server 单测**：
  - `SandboxTaskPoller` 在 sandbox 存在/不存在时的路由；
  - `SessionContextBuilder` 注入 sink factory；
  - `WorkspaceService.sessionBusy` 组合 predicate（异步任务存在 → busy）。
- **dev E2E**：
  - 复用 `.core-ai/tasks/workspace-async-lease-e2e.mjs`（改为真实后台子代理）；
  - 新增 `.core-ai/tasks/sandbox-background-task-e2e.mjs`：pending → poll → 通知 → 输出文件；
  - 两个会话同时 `task-1` 的隔离测试；
  - 任务运行期间 workspace 保持 READY 的断言（现有脚本）。
- **回滚演练**：dev 关闭 flag → 工具同步执行提示 → 重新开启。

### 8.3 实施分阶段

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| **P0** | 服务端 sink factory + 上下文注入 + writeQuietly + 工具提示 | 后台子代理在 dev 可用；单测通过；flag 默认关闭 |
| **P1** | `Sandbox.pollTask` + task id 命名空间 + `AsyncTaskData` 字段 + 服务端 poller + lost 策略 | 沙箱后台 bash 的 pending→completed→通知全链路通过 |
| **P2** | `WorkspaceService.sessionBusy` 组合异步任务 + 现有 Workspace E2E 回归 | 任务期间不休眠/不接管；任务结束后能休眠 |
| **P3（可选）** | `wait` 的 poll-based producer、runtime `/tasks/{id}/cancel`、输出文件保留期、服务端任务持久化 | 按用户优先级 |

**首版建议**：P0+P1+P2 一起发一个 dev 版本；P3 不阻塞。

### 8.4 上线与回滚

- 新配置全部走部署 env / ConfigMap；首版 dev 开启，UAT/prod 默认关闭，等 dev 观察后再放量。
- 不需要迁移；回滚只关 flag + 重启，没有 schema/数据回滚。
- 发布前跑 `:core-ai-server:check` 与前端 build（本次无前端改动）。
- dev E2E 必须使用 CI 构建镜像（沿用项目规则）。

## 9. 风险登记

| 风险 | 概率/影响 | 缓解 |
|---|---|---|
| 共享 `AsyncToolTaskExecutor` 线程池饱和，后台任务排队、前台渲染变慢 | 中/中 | 会话级上限 5、指标 `tasks.background.rejected`、P3 独立池 |
| task id 命名空间实现错误导致跨会话结果覆盖 | 低/高 | 服务端生成 `st-<uuid>`、单测覆盖两 sandbox 同名任务、代码评审逐分支检查 |
| 通知重复注入或丢失 | 中/中 | 沿用现有“通知一次 + 重试/放弃”机制；A8 验收；日志带 generation |
| 输出文件占满 Workspace 卷 | 中/中 | 单文件 1 MiB、任务 id 唯一、P3 保留期清理、监控输出字节 |
| runtime 30 分钟保留期不够长构建 | 中/中 | 文档明确；P3 提高/续期；workspace 长任务用 start.sh |
| 服务端 Pod 重启丢失后台子代理 | 高/中 | 首版明示不支持；P3 评估持久化；不承诺跨重启 |
| flag 打开后全平台后台任务量上升（token/并发） | 中/中 | dev 先开、会话上限、指标观察、UAT/prod 默认关 |
| 跨 Pod 时轮询看不到 sandbox | 中/低 | 仅 owner Pod 轮询（现有 ownedElsewhere）；不可达计入 lost |
| `wait` 不可用导致 Agent 反复 poll 消耗 token | 中/低 | 工具描述与错误信息明确；P3 poll-based wait |
| 输出文件包含 secret | 低/中 | 与聊天/工具结果同级信任边界；提示词禁止写 secret；不新增公开端点 |

## 10. ADR 与待 Review 决策

### 10.1 ADR 摘要

| ID | 决策 | 理由 | 被否方案 |
|---|---|---|---|
| ADR-1 | 同时做“服务端 sink/manager”与“沙箱轮询中继” | 两个缺口分别对应子代理与服务端 bash；只修一个仍会有一半工具静默降级 | 只修 sink：沙箱 bash 仍 `Task not found` |
| ADR-2 | 输出文件写 sandbox `/workspace/.core-ai/tasks` | Agent 可直接 `read_file`/`tail`；workspace 卷持久，非 workspace 临时目录也可用 | 服务端本地临时目录（Agent 读不到）；对象存储（需要下载/鉴权） |
| ADR-3 | 服务端生成 Agent 可见 task id（`st-<uuid>`），runtime 原始 id 内部保存 | 彻底避免 `task-1` 跨 sandbox/重启碰撞；不依赖解析 | 直接用 runtime id（冲突）；拼 sandbox id（长且需解析） |
| ADR-4 | Workspace 活动用组合 predicate，不改全局 Turn State | 避免改变每用户并发/准入语义；范围窄、可回滚 | 把 managed task 计入全局 liveness（影响准入和统计） |
| ADR-5 | 首版只支持 `poll`，`wait`/`cancel` 延后 | `TaskAwaitCoordinator` 对 managed task 明确不支持；runtime 无 cancel；不与首版交付耦合 | 首版同时做 wait/cancel（扩大改动面） |
| ADR-6 | 服务端后台任务不持久化（首版） | `BackgroundTaskManager` 是进程内；持久化需要新的恢复语义 | 持久化到 DB + 重建（独立项目） |
| ADR-7 | 复用 `app.async_tool_tasks` JSON，不建新表 | 新字段向后兼容、无迁移、清理逻辑已有 | 新建表/新迁移（成本高、收益低） |
| ADR-8 | 沙箱任务丢失判定：明确 `task not found` 或连续 3 次不可达 | runtime 是权威；避免永久 pending | 无限重试（占记录）；一次失败即丢（误判网络抖动） |

### 10.2 待 Review 决策（需要用户拍板）

| ID | 决策 | 推荐 | 影响 |
|---|---|---|---|
| D1 | 输出目录：sandbox `/workspace/.core-ai/tasks` vs 服务端临时目录/对象存储 | sandbox 路径 | 决定 Agent 是否可直接读、以及卷占用 |
| D2 | `wait` / `cancel` 是否必须首版 | 不必须（poll 先行） | 影响长任务体验；会增加 P1 改动面 |
| D3 | 渲染/视频等 managed task 是否也阻止 Workspace 休眠 | 首版不计入（只算 sandbox task） | 更省算力；但渲染结束后首次写卷需唤醒 |
| D4 | Workspace 输出文件保留期 | 7 天（P2 实现），首版不清理 | 卷空间与可追溯性 |
| D5 | runtime 30 分钟保留期是否提高 | 暂不改；长任务用 start.sh/P3 | 长构建可能丢失结果 |
| D6 | flag 默认值与放量顺序 | dev on，UAT/prod off，观察后再放 | 变更风险与灰度 |
| D7 | 服务端后台任务是否跨 Pod 持久化 | 首版不做（明示） | Agent 在 Pod 重启后可能等不到通知 |

## 附录 A：用户原始需求与文档治理

### A.1 原始需求（原文）

> 下一步做什么

> 后台任务：先出设计（docs/cn 设计文档，评审后再实现）

### A.2 文档治理

- 本文只做设计，不改业务实现、不建迁移、不部署、不执行真实后台任务。
- 实现前必须由用户确认 §10.2 的关键决策；评审记录只代表 AI/评审意见，不代表用户批准。
- 修改本文时必须更新附录 C 的版本与修改记录；源码事实变化时先更新附录 B，再更新正文结论。
- 与 Workspace 相关的设计引用 `docs/cn/design-persistent-workspace.md`（Turn Lease、sleep、失败不降级）。

## 附录 B：源码核对

| 事实 | 位置 |
|---|---|
| `TaskTool` 后台分支要求 `context.getTaskManager()` | `core-ai/src/main/java/ai/core/tool/tools/TaskTool.java:196-197`、`:233-248` |
| `ShellCommandTool` 后台分支要求 task manager，否则同步 | `core-ai/src/main/java/ai/core/tool/tools/ShellCommandTool.java:355-371` |
| sandbox 会话拦截 shell/文件工具，绕过服务端后台分支 | `core-ai/src/main/java/ai/core/sandbox/SandboxConstants.java:18-26`、`AgentSandbox.java:59-76` |
| `BackgroundTaskManager` 需要 sinkFactory | `core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:99-113` |
| 无 sinkFactory 时不创建 manager | `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:126-127` |
| sink 的 reference/write/close 契约 | `core-ai/src/main/java/ai/core/tool/subagent/SubagentOutputSink.java:7-14` |
| `BackgroundTaskManager.submit` 创建 sink 并读取 reference | `BackgroundTaskManager.java:151-185` |
| 终态通知与 TaskStatusEvent | `BackgroundTaskManager.java:354-379` |
| `runAgentWithSink` 写 sink 可能把成功任务判失败 | `BackgroundTaskManager.java:50-73` |
| `SessionContextBuilder` 是唯一 context 构造点 | `core-ai-server/src/main/java/ai/core/server/session/SessionContextBuilder.java:70-81` |
| `withAsyncTaskManager` 现有注入方式 | `SessionContextBuilder.java:54-57` |
| 三个 context 构造点 | `AgentSessionManager.java:164`、`:247`、`SessionRebuildManager.java:283` |
| `Sandbox` 接口没有 poll | `core-ai/src/main/java/ai/core/sandbox/Sandbox.java:22-26` |
| `SandboxClient.execute` → runtime `/execute` | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxClient.java:123-141` |
| `SandboxClient.pollTask` 已存在但无调用方 | `SandboxClient.java:166-178` |
| pending 结果映射 | `SandboxClient.java:249-273` |
| `AgentSandbox` 持有 `runtimeClient` | `core-ai-server/src/main/java/ai/core/server/sandbox/agentsandbox/AgentSandbox.java:30`、`:65-76` |
| `ToolExecutor` 登记 pending 任务 | `core-ai/src/main/java/ai/core/tool/ToolExecutor.java:420-448` |
| 本地工具轮询 | `ToolCallAsyncTaskManager.java:146-186` |
| `AsyncTaskData` 现有字段 | `ToolCallAsyncTaskManager.java:319-346` |
| `ToolCallResult.withTaskId` | `core-ai/src/main/java/ai/core/tool/ToolCallResult.java:160` |
| `async_task_output` 的 poll/wait/cancel | `core-ai/src/main/java/ai/core/tool/tools/AsyncTaskOutputTool.java:56-129` |
| managed task 的 wait 明确不支持 | `core-ai/src/main/java/ai/core/tool/wait/TaskAwaitCoordinator.java:149-175` |
| `AsyncToolTaskService` 轮询与 owner 跳过 | `core-ai-server/src/main/java/ai/core/server/asynctask/AsyncToolTaskService.java:74-89` |
| `ServerAsyncTaskManager` 解析工具 | `AsyncToolTaskService.java:156-169` |
| `app.async_tool_tasks` JSON 存储 | `core-ai-server/src/main/java/ai/core/server/asynctask/PgAsyncTaskPersistence.java:22-26` |
| runtime `/execute` 异步分支 | `core-ai-sandbox-runtime/main.go:392-421` |
| runtime `/tasks/{id}` | `core-ai-sandbox-runtime/main.go:432-457` |
| runtime 任务上限/保留/截断 | `core-ai-sandbox-runtime/main.go:75-147`、`:1271-1276` |
| `isAsync` 解析 `run_in_background` / `async` | `core-ai-sandbox-runtime/main.go:1281-1287` |
| Turn State liveness 已含 `hasDeferredWork` | `core-ai-server/src/main/java/ai/core/server/session/SessionEventListenerWiring.java:21-23`、`TurnStateListener.java` |
| `hasDeferredWork` 只含 turn 输入与 BackgroundTaskManager | `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:492-495` |
| Workspace 的 `sessionBusy` 接线 | `core-ai-server/src/main/java/ai/core/server/RuntimeGovernanceModule.java`（`bean(WorkspaceService.class).sessionBusy`） |
| Workspace 租约/休眠读 `sessionBusy` | `core-ai-server/src/main/java/ai/core/server/workspace/WorkspaceLeaseManager.java`、`WorkspaceSweeper.java` |
| 2.0.392 dev 实测（同步 183.5s、Task not found） | `docs/validation/2026-11-26-dev-workspace-async-hold.md` |

## 附录 C：Review 与修改历史

### C.1 Review 记录

**独立评审（待补）**：2026-11-26 调用 `agy -p "/usage"` 时，Gemini 池 5 小时额度 0%（12:05Z 重置）、
Claude&GPT 池 5 小时额度 0%（13:29Z 重置），按项目约定不强行调用、不冒充评审通过。额度恢复后需用
`gemini-4-neon`（或按规则换池并声明）对本稿做只读评审，并把结论写回本节。

**自审记录（非独立评审，2026-11-26）**：本轮已修正/补充以下问题，供独立评审复核：

1. 明确“先改写 task id、再 bindOwner/storeTask”的顺序，避免 owner 绑定旧 id。
2. 沙箱轮询实现补充 `DockerSandbox` / `KubernetesSandbox`（两者同样持有 `runtimeClient`）。
3. `cancel` 必须在 `ToolCallAsyncTaskManager.cancelTask` 先判断 `sandboxTask`，不得落到本地工具分支。
4. 目录锚点统一为 VitePress 的 `_` 形式。
5. Workspace 活动首版只计 `sandboxTask`，避免渲染任务让卷长期不休眠（D3）。
6. 补充说明非 Workspace sandbox 的 TTL 清理同样读 `sessionBusy`，SERVER 任务不会误杀。

### C.2 修改历史

| 版本 | 日期 | 作者 | 修改 |
|---|---|---|---|
| v0.1 | 2026-11-26 | core-ai | 初稿：现状核对、两层方案（服务端 sink + 沙箱轮询中继）、task id 命名空间、失败矩阵与验收 |
| v0.1.1 | 2026-11-26 | core-ai | 自审修订：id 改写顺序、Docker/K8s 轮询、cancel 分支、锚点、Workspace 活动范围、TTL 清理说明 |

## 附录 D：术语

| 术语 | 含义 |
|---|---|
| SERVER task | 由服务端 `BackgroundTaskManager` 执行的后台任务（子代理、未被 sandbox 拦截的 bash） |
| SANDBOX task | 由 sandbox runtime `TaskRegistry` 执行的异步任务（`run_bash_command` / `run_python_script` 等） |
| MANAGED task | 由外部 API 执行、服务端 `ToolCallAsyncTaskManager` 仅做轮询/通知的任务（渲染、视频等） |
| sink | `SubagentOutputSink`，后台任务的输出文件写入器 |
| poll relay | 服务端把沙箱 pending 任务的轮询转发到 `Sandbox.pollTask` 的路径 |
| lost | 沙箱/任务已不存在，服务端把任务标记为失败并通知一次 |
| Turn Lease | Workspace 的单写者租约，见 `docs/cn/design-persistent-workspace.md` |
