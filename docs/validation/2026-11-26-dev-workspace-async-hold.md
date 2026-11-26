# Dev — Workspace 异步任务保持（Turn State 扩展）与背景任务缺口（2026-11-26）

## 结论

`2.0.392` 让 Turn State 在「turn 结束但仍有延迟工作」时保持存活：`SessionEventListenerWiring` /
`SessionRebuildManager` 注册的 liveness 探针由 `isTurnRunning()` 扩展为
`isTurnRunning() || hasDeferredWork()`，终态只在没有 pending work 时清除。这样 Workspace 的
`sessionBusy`、租约接管判定、空闲休眠判定会自动把后台任务视为「正在工作」，不会在任务运行期间
停掉 runtime 或让其他会话接管。

单元测试与 `:core-ai-server:check` 通过；但 **dev 上无法做端到端验证**，因为服务端当前没有启用
后台任务（见下节），`run_in_background` 实际不会产生后台任务。因此本改动目前是防御性修复，
待后台任务能力落地后即可生效。

## 环境

- 镜像 `chancetop/core-ai-server:2.0.392`（CI `Build Server` run `45776495724`，commit `9cc53bc69`），
  deployment 2/2 Ready；两个 pod 的 imageID 均指向 OCI index digest
  `sha256:c056347f40a07b0f884506826f45df2df7afa7960dd48f2709f5fa2fa5cd06b4`。
- 无迁移；无新配置。

## 代码变更

- `InProcessAgentSession.hasDeferredWork()` 提升为 public（原本是私有探针）。
- `TurnStateListener`（新）：`RUNNING` → `markRunning`；`IDLE`/`ERROR`/`turn_complete` → 只有
  `pendingWork == false` 时才 `clear`，否则让 registry 的 30s 心跳继续续期。
- `TurnStateRegistry.listener(..., pendingWork)` 重载；原 3 参重载保持旧行为。
- 两处会话装配（新建 / 重建）传入
  `() -> session.isTurnRunning() || session.hasDeferredWork()` 与 `session::hasDeferredWork`。

## 验证

### 单元测试（通过）

`TurnStateRegistryTest`：

1. 有 pending work 时，`turn_complete` 与 `IDLE` 都不清除状态；pending 结束后再触发 terminal 才清除。
2. 旧 3 参 listener 仍在 terminal 立即清除（兼容性）。

`./gradlew :core-ai-server:check` 全绿，镜像 CI 成功。

### 端到端尝试与阻塞（服务端未启用后台任务）

1. **后台子代理**：`task` 调用参数确实带了 `run_in_background=true`，但工具在 **183.5s 后同步返回**
   （`status=success`，subagent 的 `run_bash_command` 耗时 180s），没有产生后台任务。
   原因：`InProcessAgentSession` 只有在 `ExecutionContext.getSubagentOutputSinkFactory() != null`
   时才创建 `BackgroundTaskManager`；服务端没有设置该工厂，也没有生产实现（仓库中只有测试用的
   in-memory 实现），因此 `TaskTool` 走同步分支。
2. **沙箱内后台命令**：在工作区会话里 `run_bash_command` + `run_in_background=true` 返回
   `Async task submitted: task-1`，随后通知轮询得到 `Task not found: task-1`。原因：该结果来自
   沙箱 runtime 的 `/tools/execute`（`SandboxClient.parseResponse` 映射为 `pending`），任务 id 属于
   沙箱进程；服务端的 `ToolCallAsyncTaskManager` 用本地工具的 `poll()` 去查
   `AsyncToolTaskExecutor`，两者的任务注册表不连通，因此永远找不到。
3. 因此 `hasDeferredWork` 在当前 dev 上恒为 false（除了 turn 内的 `turnInput` 队列），异步保持的
   端到端证据无法产生；本次没有伪造通过记录。

## 影响与残留

- 该改动本身对现有行为无可见影响（`hasDeferredWork` 在无后台任务时为 false）；一旦启用后台任务，
  租约/休眠/接管会自动获得保护。
- 需要补齐的两项独立能力（按优先级）：
  1. 服务端 `SubagentOutputSinkFactory` 实现并在会话上下文设置，使 `BackgroundTaskManager` 与
     `run_in_background` 真正生效（输出文件位置、保留期、安全边界需设计）。
  2. 沙箱托管工具的异步任务轮询：`ToolCallAsyncTask` 需要能经由沙箱 runtime 轮询（或
     `SandboxClient` 的 pending/task 映射统一进服务端注册表），否则所有沙箱内后台工具都会
     `Task not found`。
- E2E 脚本：`.core-ai/tasks/workspace-async-lease-e2e.mjs`（当前会停在启动检测；保留作后台任务
  修复后的回归脚本）。失败尝试的临时会话已删除，`idle_sleep_minutes` 已恢复 15。
