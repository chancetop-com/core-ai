# Dev E2E — Workspace Turn Lease、空闲休眠与卷回收（2026-11-26）

## 结论

P1b 的核心运营项已在 dev 验证通过：

1. **Turn lease**：同一 Workspace 的两个会话不能同时写卷。第二个会话的工具调用等待 30s 后返回
   `workspace is used by another session`（`WORKSPACE_BUSY`）；第一个 turn 结束后，第二个会话的下一次
   工具调用立即接管（29ms）。
2. **空闲休眠**：发布后的第一轮 sweep 将两个已空闲 21 小时的 Workspace runtime 停止，
   `runtime_status=NONE`，Sandbox CR 删除，PVC 保留。
3. **软删除回收**：软删除的 Workspace 在保留期（默认 7 天）后被 sweep 清理：PVC 删除、volume 行标记
   `DELETED`、workspace 行与事件删除。purge 期间恢复会被拒绝（`410 WORKSPACE_DELETED`）。

## 环境

- 镜像 `chancetop/core-ai-server:2.0.389`（CI `Build Server` run `45755331746`，commit `45824544c`），
  deployment 2/2 Ready；两个 pod 的 imageID 均在 OCI index digest
  `sha256:c563e27819d110229163cee1ad75913a17dd7e58f1c3a7e0cbf16df4989d153f` 上。
- 部署后另一会话将 dev 滚动到 `2.0.390`（head `6036270a`，是 `45824544c` 的后代），Workspace 代码不变；
  `workspace-lifecycle` job 在 2.0.390 pod 上继续按分钟运行。
- 未新增迁移：`workspace_leases` / `workspace_job_leases` 是 V52 已有表，本次只接入 store 与调用方。
- 新增配置（`sys.properties`，均可被部署 env 覆盖）：
  `sys.workspace.leaseTtlSeconds=600`、`sys.workspace.leaseWaitSeconds=30`、
  `sys.workspace.minRuntimeSeconds=300`、`sys.workspace.purgeAfterDays=7`。

## 实现摘要

- `WorkspaceLeaseManager`：`workspace_leases` 上的 CAS 租约。turn lease 由会话持有，活跃 turn 期间由
  `WorkspaceLeaseHeartbeat` 续租（TTL 的 1/3，最少 30s）；持有者不再执行 turn（全局 `TurnStateRegistry`）
  或租约过期时可被接管；sleeper 用 120s 的 maintenance lease 串行化。
- `WorkspaceSandbox`：每个工具/文件操作调 `WorkspaceService.beginTurn(...)`，取得租约、校验 runtime 换代
  （休眠后 generation/沙箱名变化）并重新 attach；接管会话时重新 `/bind` hub；`close()` 释放租约。
- `WorkspaceSweeper` + `WorkspaceLifecycleJob`：每分钟一次，`workspace_job_leases` 保证多副本只跑一个。
  先按 `lastUsedAt`/`idleSleepMinutes`（并跳过仍在执行 turn 的会话）停止 runtime，再清理超过
  `purgeAfterDays` 的软删除 Workspace，最后重试卡在 `DELETING` 的卷。
- purge 顺序：先把 volume 标记 `DELETING`（并发 restore 会看到无活动卷而失败），再删 PVC、标记
  `DELETED`、删 binding/lease/events，最后删 workspace 行；PVC 删除失败时保留行供下一轮重试。

## 证据

### 空闲休眠（部署后第一轮 sweep）

```text
04:36:17.650 WorkspaceSweeper - workspace slept after idle, workspace=194705ce…, idleMinutes=1282
04:36:17.739 WorkspaceSweeper - workspace slept after idle, workspace=92dbe342…, idleMinutes=1281
04:36:17.752 WorkspaceLifecycleJob - workspace lifecycle sweep, slept=2, purged=0, volumesDeleted=0
```

- `kubectl get sandbox -n dev-ai | grep ws-` → 空；两个 runtime 的 Sandbox CR 已删除。
- `kubectl get pvc -n dev-ai | grep ws-` → 3 个 `ws-*` PVC 仍 Bound（卷保留），符合设计。

### Turn lease（两个会话共享同一 Workspace）

会话 A 执行 `run_bash_command: sleep 60; echo A_DONE`，随后会话 B 请求 `echo B_FIRST`：

```text
05:11:53 A tool_start
05:12:27 B tool_result (32.9s) status=failure
         result="SANDBOX_UNAVAILABLE: the sandbox could not run this tool
                 (workspace is used by another session). Tell the user; do not retry …
                 duration_ms=30234"
05:12:55 A turn_complete
05:12:59 B tool_result status=success result="B_AFTER_LEASE\n" duration_ms=29
05:13:00 B turn_complete
05:13:01 workspace runtime_status=READY runtime_generation=3（turn 期间未休眠、未换代）
```

- `app.workspace_leases` 在 turn 期间存在一行 `lease_kind=turn`（workspace `92dbe342…`，session 为当时
  持租会话），会话关闭后为空；`app.workspace_job_leases` 每轮结束后为空。
- 另一会话未拿到租约时，工具层把 `WORKSPACE_BUSY` 映射为 `SANDBOX_UNAVAILABLE` 返回给 Agent，符合
  “明确拒绝、不静默等待”的语义。

### 软删除回收

1. `POST /api/workspaces {name: e2e-purge-…, size_gb: 1}` → `05ce57eb-4edf-4a98-9f6f-b42b34d1c706`，
   PVC `ws-05ce57eb…-g0`（Pending，WaitForFirstConsumer）。
2. `DELETE /api/workspaces/05ce57eb…` → 软删除，runtime NONE，卷保留。
3. 手工把 `deleted_at` 回退 8 天；下一轮 sweep（05:16:21 UTC）后：
   - `select count(*) from app.workspaces where id=…` → **0**
   - `workspace_volumes.status` → **DELETED**（`deleted_at/updated_at=05:16:21`）
   - `workspace_events` → **0**
   - `kubectl get pvc | grep 05ce57eb` → **空**
4. 恢复保护：volume 处于 `DELETING`/`DELETED` 时 `POST /api/workspaces/:id/undelete` 返回
   `WORKSPACE_DELETED`（410）；单元测试覆盖了 purge 与 restore 的竞争回滚。

## 单元测试

`WorkspaceLifecycleTest`（5 个用例）：

- 超过保留期 → 删 PVC、volume 标记 DELETED、workspace/events 删除；
- 保留期内不处理；
- PVC 删除失败时保留行与 `DELETING`，下一轮成功；
- volume 已被 purge 时 restore 返回 `WORKSPACE_DELETED`；
- 在 sweep 读取候选与执行之间被 restore → 撤销 `DELETING` 标记、保留 workspace。

`WorkspaceServiceTest` 的 disabled 路径不变。`./gradlew :core-ai-server:check` 全绿。

## 残留与已知边界

- **异步后台任务不在租约跟踪内**：`workspace_async_tasks` 尚未接入。一个 turn 结束后仍运行的
  `run_in_background` 任务不阻止租约被接管，也不阻止 15 分钟后的空闲休眠；后续需要把 async task 终态
  纳入 lease 续期（设计 §3.4/§6.4）。
- **等待成本**：第二个会话的工具线程最多阻塞 `leaseWaitSeconds`（默认 30s）才失败；这是设计选择的
  明确拒绝，不是队列。
- 仍未实现：bootstrap/start 脚本执行、`/env` `/scripts` `/tasks` API、前端 Workspace UI、配额/驱逐与
  usage/审计页面。
- UAT/prod 未开启 `SYS_WORKSPACE_ENABLED`；本次无新迁移，UAT/prod 的 V52 已随 V49–V54 批次应用。
- E2E 脚本：`.core-ai/tasks/workspace-lease-lifecycle-e2e.mjs`（首次运行因脚本自身 SSE 收尾缺陷挂起，
  已修正；与产品无关）。临时会话与 purge 测试 Workspace 已清理。
