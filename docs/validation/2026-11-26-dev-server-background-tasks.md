# Dev E2E — 服务端后台任务与沙箱异步轮询（2026-11-26）

## 结论

`2.0.399` 打通了两条后台任务路径：

1. **服务端后台子代理**：`task` 的 `run_in_background=true` 立即返回 task id，父 turn 结束，子代理继续；
   完成后任务通知回到会话；Workspace 在整个后台任务期间保持 READY，任务结束后按 idle 休眠。
2. **沙箱异步 bash**：`run_bash_command` 的 `run_in_background=true` 由 sandbox runtime 执行，工具在
   19ms 内返回 pending（服务端生成 `st-<uuid>` id）；服务端按 runtime `/tasks/{id}` 轮询，终态通知
   会话；事后再次 `async_task_output poll` 仍能拿到终态结果。
3. **输出文件**：服务端后台任务的输出写到 `/workspace/.core-ai/tasks/<taskId>.out`；workspace 睡眠
   后重新唤醒，文件仍在且可读（本次 276 字节）。

## 环境

- 镜像 `chancetop/core-ai-server:2.0.399`（CI `Build Server` run `45804342936`，commit `a1b9bb94e`），
  deployment 2/2 Ready；两个 pod 的 imageID 均为 OCI index digest
  `sha256:7e774455eb1f35fc72f5e0a22904915a9d8a0b5f456d53455348be2026cf782c`。
- dev 部署通过 `kubectl set env deployment/core-ai-server SYS_TASKS_BACKGROUND_ENABLED=true` 打开开关
  （尚未写入 `fbr-env-project` dev ConfigMap；UAT/prod 未开启）。
- 新增配置：`sys.tasks.background.enabled`（默认 false）、`sys.tasks.background.maxPerSession=5`、
  `sys.tasks.background.outputMaxBytes=1048576`；无数据库迁移。

## P0 — 服务端后台子代理（复用 `.core-ai/tasks/workspace-async-lease-e2e.mjs`）

```text
launched sa-9f93d503 ... <output-ref>/workspace/.core-ai/tasks/sa-9f93d503.out</output-ref>
first turn complete at 2026-11-26T11:12:44.804Z
workspace 110s after the turn READY 5
notification turn complete; BG_DONE visible: true
workspace asleep after background work NONE 5
WORKSPACE_ASYNC_LEASE_E2E_PASS
```

- 工具立即返回 `async_launched`（不再是之前的 183.5s 同步返回）；父 turn 结束后子代理继续运行。
- 后台任务运行 110s 后 Workspace 仍为 `READY`（generation 5），没有被休眠或接管。
- 任务完成后通知轮到达（BG_DONE），随后 Workspace 正常休眠到 `NONE`。

### 输出文件持久性

另开会话在 Workspace 唤醒后执行：

```bash
ls -l /workspace/.core-ai/tasks && cat /workspace/.core-ai/tasks/sa-9f93d503.out
```

得到 `-rw-r--r-- ... 276 Nov 26 11:15 sa-9f93d503.out`，内容包含 `BG_DONE` 与子代理 footer。说明
sink 写入成功且卷在睡眠/唤醒后保留。

## P1 — 沙箱异步 bash（`.core-ai/tasks/sandbox-background-task-e2e.mjs`）

```text
launch tool ms 19 {"status":"pending","result":"Async task submitted: st-19c1f6be-c6fc-434b-b58e-3f483de74159","duration_ms":19}
task id st-19c1f6be-c6fc-434b-b58e-3f483de74159
first turn complete
notification turn complete; SANDBOX_BG_DONE visible: true
poll result {"tool_name":"async_task_output","status":"success","result":"SANDBOX_BG_DONE\n","duration_ms":3}
SANDBOX_BACKGROUND_E2E_PASS
```

- `app.async_tool_tasks` 记录：`status=COMPLETED`、`sandboxTask=true`、`sandboxTaskId=task-3`、
  `lastMessage="SANDBOX_BG_DONE\n"`；说明 pending 结果被服务端接管、runtime id 只用于中继。
- 通知轮在没有 agent 主动 poll 的情况下到达，说明服务端轮询（`AsyncToolTaskService`）在工作。
- 终态后再次 poll 直接返回存储的终态（3ms），没有重复通知。
- 早前一个脚本版本在任务运行中删除会话，得到 `SANDBOX_TASK_LOST: sandbox task is no longer reachable`
  （显式失败、通知一次），符合失败不静默的预期；该会话已清理。

## 自动化与其他

- 新增 `ToolCallAsyncTaskManagerSandboxTest` 4 个用例：poller 路由、连续不可达→lost、not found→立即
  lost、cancel/input 显式不支持；通过。
- `./gradlew :core-ai:test` 与 `:core-ai-server:check` 全绿（含 trace workstream 当天提交的测试修复）。
- 一次共享工作树事故：`AgentSessionManager` 的未提交改动被其他会话覆盖，提交前已重新应用；不影响
  最终代码。

## 残留与已知边界

- `async_task_output action='wait'` 对沙箱任务仍返回 `WAIT_UNSUPPORTED`（沿用 managed task 语义）；
  `cancel` 返回 `SANDBOX_TASK_CANCEL_UNSUPPORTED`（runtime 无 `/tasks/{id}/cancel`）。
- 服务端后台任务（子代理/非拦截 bash）仍是进程内状态，Pod 重启丢失；沙箱任务不跨 sandbox 重建。
- runtime 异步任务上限 5、终态保留 30 分钟、结果 30KB 截断，未修改。
- `/workspace/.core-ai/tasks` 输出文件未自动清理；按设计 P3 再做保留期。
- dev 的开关只在 Deployment env；`fbr-env-project` 的 dev ConfigMap 仍需补上，避免下一次 apply
  静默关闭。UAT/prod 未开启。
- 后台任务设计文档 `docs/cn/design-server-background-tasks.md` 的独立 Review 仍待 agy 额度恢复后补。
