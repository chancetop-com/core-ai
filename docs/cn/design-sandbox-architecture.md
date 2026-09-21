# Sandbox 沙箱子系统 — 架构与设计

## 1. 定位

Sandbox 是 Core AI Server 的**隔离执行层**。Agent 在运行过程中会调用「跑代码」「读写文件」类工具（shell、python、文件读写/检索），这些工具如果直接在 server 进程里执行会带来安全与稳定性风险。Sandbox 把这类工具**拦截并转发到一个隔离的运行时 Pod** 中执行，server 进程只负责编排。

```
不隔离的工具(LLM 调用、检索、内存)  → 在 server 进程内执行
隔离的工具(shell/python/文件)        → 拦截 → 转发到 Sandbox Runtime Pod
```

## 2. 设计原则

### 2.1 端口-适配器（Ports & Adapters）

抽象层（`core-ai/sandbox`）只定义两个端口接口，不依赖任何具体运行时：

- `Sandbox` — 一个沙箱实例的能力（执行工具、查状态、投递 skill、取回文件）
- `SandboxProvider` — 怎么创建/销毁沙箱（`acquire` / `release` / `getStatus`）

具体运行时（K8s Agent Sandbox CRD、裸 Pod、本地 Docker）作为适配器实现这两个接口。调用方对运行时无感知，便于在不同环境间切换。

### 2.2 懒加载（Lazy provisioning）

会话创建时**不真正开沙箱**，只放一个占位代理 `LazySandbox`。真正的 Pod 在**第一次拦截到工具调用时**才创建。纯聊天、不跑代码的会话永远不消耗 Pod 资源。

### 2.3 自愈（Self-healing）

工具执行遇到连接错误时，沙箱状态翻为 `ERROR`；下一次工具调用会自动重建一个新沙箱，对调用方透明。

### 2.4 资源默认收敛 + 安全默认关闭

- 网络默认关闭（`networkEnabled=false`）
- 危险环境变量黑名单（`PATH`、`LD_PRELOAD`、`LD_LIBRARY_PATH` 等）
- 内存/CPU/超时都有 min-max 钳制
- 注入 K8s label 前做 `sanitizeLabel`

## 3. 架构分层

```
┌─────────────────────────────────────────────────────────────┐
│  抽象层 (core-ai/sandbox) —— 不依赖任何具体运行时              │
│  Sandbox(接口) │ SandboxProvider(接口) │ SandboxConfig        │
│  SandboxConstants │ SandboxStatus │ SandboxFile               │
└─────────────────────────────────────────────────────────────┘
                          ▲ 实现
┌─────────────────────────────────────────────────────────────┐
│  编排层 (core-ai-server/sandbox)                              │
│  SandboxService ── 会话级门面 (sessionId → Sandbox)           │
│      └─ SandboxManager ── 生命周期 / 计数 / 到期跟踪          │
│           └─ LazySandbox ── 懒加载代理(核心)                  │
│      └─ SandboxCleanupJob ── 每 5min 兜底清理                 │
│      └─ SandboxClient ── 与 runtime 的 HTTP 通信             │
└─────────────────────────────────────────────────────────────┘
                          ▲ 三种 Provider 实现
┌──────────────────┬──────────────────┬───────────────────────┐
│ AgentSandbox     │ Kubernetes       │ Docker                │
│ (UAT / 生产)     │ (裸 Pod)         │ (本地开发)            │
│ CRD + warm pool  │                  │                       │
└──────────────────┴──────────────────┴───────────────────────┘
                          │ HTTP :8080  /execute  /health
                          ▼
                  Sandbox Runtime Pod (隔离执行环境)
```

## 4. 核心抽象

### 4.1 `Sandbox` 接口

| 方法 | 说明 |
|------|------|
| `shouldIntercept(toolName)` | 该工具是否应被沙箱接管 |
| `execute(toolName, args, ctx)` | 在沙箱里执行工具，返回 `ToolCallResult` |
| `materializeSkill(name, version, tar)` | 把 skill 投递进沙箱 |
| `downloadFile(path)` | 从沙箱取回产物文件 |
| `bind(SandboxBinding)` / `unbind()` | 把会话的 Sandbox Hub 身份（server 地址 + 会话令牌）交给 runtime，启用其回环代理；会话释放沙箱时清除。仅内存态，不落盘 |
| `getStatus / getId / hostname / ip / image / close` | 状态与元信息 |

被拦截的工具集合定义在 `SandboxConstants.INTERCEPTED_TOOLS`：shell、python、read/edit/write/glob/grep file。边界即「碰文件系统 + 跑代码」的工具。

### 4.2 `SandboxConfig` 与默认值

| 配置项 | 默认值 | 上限 |
|--------|--------|------|
| `memoryLimitMb` | 512 | 2048 |
| `cpuLimitMillicores` | 500 | 2000 |
| `timeoutSeconds` | 1800 | 7200 |
| `networkEnabled` | false | — |
| `maxAsyncTasks` | 5 | — |

`validate()` 会把 `timeoutSeconds` 钳制到 `[300, 7200]`，并校验环境变量黑名单。

## 5. 关键设计模式

### 5.1 懒加载代理 `LazySandbox`

```
createSession → new LazySandbox(占位, getId()="pending")   // 0 成本
   首次 shell/python 工具
     → LazySandbox.execute → ensureReady()
       → SandboxManager.acquire → Provider.acquire        // 此刻才起 Pod
```

`ensureReady()` 用双重检查锁（double-checked locking）保证并发下只创建一次，并在创建/就绪/替换/终止各阶段通过 `eventDispatcher` 推送 `SandboxEvent` 给前端。

### 5.2 自愈

```
execute 抛 ConnectException/SocketTimeoutException
   → AgentSandbox.status = ERROR
   → 下次工具调用 ensureReady() 检测到非 READY
     → release 旧沙箱 + acquire 新沙箱（dispatch REPLACING → READY）
```

> ⚠️ **状态易失性**：runtime Pod 的 `/tmp` 使用 `emptyDir{medium: Memory}`，沙箱一旦被替换/重建，**之前写入的所有文件与工作状态全部丢失**。自愈解决了「可用性」，但不保留「连续性」。

### 5.3 Warm Pool vs Direct 双模式（AgentSandboxProvider）

```
acquire:
  if useWarmPool() && !hasCustomConfig(config):
      创建 SandboxClaim CRD → 调度器从预热池秒级分配 → waitForReady(60s)
  else:
      创建 Sandbox CR → 从零拉起 → waitForReady(120s)
```

- **降级触发**：一旦指定了**自定义镜像或自定义 env**（`hasCustomConfig`），就绕过预热池走 direct 模式（预热池镜像固定）。

### 5.4 异步任务

runtime 对长任务返回 `pending + taskId`，server 端用 `SandboxClient.pollTask(taskId)` 轮询，避免 HTTP 长连接占用，`MAX_ASYNC_TASKS` 限制并发。

## 6. 执行链路（一次 shell 工具调用）

```
ToolExecutor.doExecute
  ├─ sandbox = context.getSandbox()
  ├─ useSandbox = sandbox.shouldIntercept("shell")        // true
  └─ sandbox.execute(...)  →  LazySandbox.execute
        ├─ ensureReady()                                   // 懒建 / 自愈；就绪后钩子 onSandboxReady 里 bind 会话身份（替换后 rebind）
        └─ AgentSandbox.execute
              └─ SandboxClient HTTP POST podIP:8080/execute
                    └─ {status: completed | failed | timeout | pending}
```

### 6.1 反向链路：沙箱内的脚本调会话能力（Sandbox Hub）

沙箱里的脚本（skill 脚本、临时 python/bash）通过 **回环 hub** 使用本会话 agent 已配置的能力（MCP / API 工具 / LLM_CALL / sub-agent / 非沙箱 builtin），脚本本身不持有任何凭据：

```
脚本 (python: core_ai_session SDK / bash: core-ai-sandbox CLI)
  │  HTTP，无 Authorization
  ▼
runtime 127.0.0.1:8081/hub/*            ← 只监听回环；从内存 binding 取令牌，覆盖脚本传来的 Authorization
  │  Bearer cst_…（会话令牌）
  ▼
server /api/sandbox-hub/*（仅此路径接受 cst_）
  ├─ 校验令牌 + 会话/沙箱绑定（本 pod 无该会话时回退 Redis 里存下的绑定，避免已释放/已替换的沙箱复活）
  ├─ 目录类请求（me/catalog/tools/describe）由任意 pod 直接用持有会话 pod 发布的目录快照回答
  └─ 执行类请求经 messaging RPC 转发到持有会话的 pod（SANDBOX_TOOL_CALL / SANDBOX_TOOL_POLL）
        └─ 用会话自己的 ExecutionContext 执行工具，审计写 hub_calls（source=sandbox），与 agent 调用同配额/同 trace
```

- 环境变量只注入 `CORE_AI_HUB`（以及信息性的 `CORE_AI_SESSION_ID` / `CORE_AI_AGENT_NAME`）；不注入任何指向原始 LLM 的变量。
- 长任务（async 工具、慢 sub-agent）返回 `pending + task_id`，调用方轮询 `/tasks/:id`。
- 同一个 python 脚本在本机也能跑：`CORE_AI_HUB` 未设置时 SDK 改走 `core-ai-cli … --json`（本机用户身份），离线单测用 `FakeSession`；三者在同一份 `sdk/core-ai-session/contract-fixtures/*.json` 上断言（§ 详见 design-sandbox-hub.md §5.8/§5.10）。
- 完整设计（契约、SDK、迁移路径、风险）见 `docs/cn/design-sandbox-hub.md`。

## 7. 生命周期管理

| 动作 | 触发点 | 调用链 |
|------|--------|--------|
| **创建** | 首次拦截工具 | `LazySandbox.ensureReady → Manager.acquire → Provider.acquire` |
| **续期** | 每条用户消息 `touchActivity` | `Service.renewSandbox → Manager.renew` |
| **释放** | session 关闭 | `Service.releaseSandbox → LazySandbox.close → Manager.release → Provider.release` |
| **兜底清理** | 每 5min（`SandboxCleanupJob`） | `Manager.cleanupExpired`（内存）+ `Provider.cleanupExpiredSandboxes`（扫 K8s 清孤儿） |

`SandboxManager` 用 `Map<sandboxId, SandboxEntry>` 跟踪所有活跃沙箱，`SandboxService` 用 `Map<sessionId, Sandbox>` 维护会话到沙箱的映射。

## 8. 已知问题与改进方向

### 8.1 生命周期时间基准不统一（高优先级）🔴

当前存在**三套独立的删除机制，全部以 30min（`DEFAULT_TIMEOUT_SECONDS=1800`）为基准**：

1. K8s 原生 lifecycle：`SandboxClaim.spec.lifecycle.shutdownTime = 创建时刻 + timeout`，`shutdownPolicy=Delete`
2. `SandboxManager.cleanupExpired`：按内存 `createdAt + timeout`
3. `AgentSandboxProvider.cleanupExpiredClaims`：按 K8s `creationTimestamp + maxLifetime`

而 session 空闲清理阈值是 **60min**（`IdleSessionCleanupJob`）。两个基准对不上。

更关键的是：**`SandboxManager.renew()` 只更新内存里的 `createdAt`，从不 PATCH K8s**。因此机制 ① 和 ③ 完全无视续期 —— 即使会话一直活跃，沙箱也会在创建满 30min 后被硬删，触发 `released sandbox not tracked` WARN，并依赖自愈重建（伴随 `/tmp` 状态丢失）。

**解决方案（按活动续期，已实现）**：

- `SandboxProvider.renew(sandbox, config)` 默认空实现；`AgentSandboxProvider` 按**沙箱自身的 provisioning mode** PATCH（JSON merge-patch）`SandboxClaim.spec.lifecycle.shutdownTime`（claim）或 `Sandbox.spec.shutdownTime`（direct CR）`= now + lifetimeSeconds(config)`。寿命解析只有一处（`AgentSandboxProvider.lifetimeSeconds`，未配置时 3600s），获取与续期写同一个值 —— 续期永远不会写出比获取时更短的截止时间。
- `SandboxManager.renew` 在更新内存 `createdAt` 的同时调用 `provider.renew`（传获取时的 config），让续期真正作用到 K8s。续期为 best-effort：失败仅告警，下条消息重试。
- 机制 ③ 的清理判定从 `creationTimestamp + maxLifetime` 改为优先按 claim/CR 上的 `shutdownTime`（无则回退创建时间），与续期后的截止时间及 K8s 原生 lifecycle 对齐。
- 效果：活跃会话沙箱不再被中途删除；只有真正空闲超时的沙箱才被回收。

**⚠️ 2026-09-21 修正（UAT 沙箱暴涨根因，本次彻底修复）**：provisioning mode 是**每次 acquire** 决定的（claim 只能带 template/warm pool/lifecycle，所以配了自定义 image/env 的 agent 走 direct CR），但 `getStatus` / `release` / `renew` / `attach` 原先都按 **provider 级** `useWarmPool()` 分支，导致 direct CR 被当成 claim 操作：

| 方法 | 旧行为（direct CR + 已配 template） | 后果 |
|------|--------------------------------------|------|
| `getStatus` | 查 `sandboxclaims/<CR名>` → 404 → `TERMINATED`（CR 实际 `Ready=True`） | 每次空闲 >30s 后的工具调用都判定要替换 → 新建 pod（UAT 实测 11 个 CR 与工具调用时间戳 1:1 对齐，单会话峰值 ≈6Gi/1.2vCPU） |
| `release` | `deleteClaim(CR名)` → 404 被静默容忍 | 旧 CR 删不掉 → 孤儿存活到自身 1h TTL |
| `renew` | PATCH 打到 claim 上 → 404 return | CR 寿命永不续期（长会话 1h 必失沙箱） |
| `attach` | 先 `getClaim(id)` → 404 → empty | 重启/重建后 direct 沙箱挂不回来（终端地址解析同理失效） |

修复：把 mode 放到**实例**上 —— 新增 `AgentSandboxKind`（`CLAIM`/`DIRECT`），`AgentSandbox.Config` 携带、由 acquire/attach 写入，所有 per-sandbox 操作按 `sandbox.kind()` 分派；`attach` 先按 id 形状（`claim-` 前缀）判定主模式，未命中再回退另一种模式（兼容 provider 配置变更后遗留的绑定）。配套：`SandboxService` 覆盖会话沙箱条目时释放旧实例（另一处孤儿来源）、claim 路径缺 extensions client 时不再谎报终态。

- 注意：`renew` 由 `touchActivity` 在每条用户消息时同步触发一次 K8s PATCH，后续可考虑节流或异步化以降低消息处理延迟。

### 8.2 缺少失效感知（watch/reconcile）

session 无法主动感知沙箱被外部删除，只能靠「下次执行失败 → 自愈」被动发现。可考虑：用 `Provider.getStatus`（已具备查 K8s 真实状态的能力）做主动探活，在 `ensureReady` 快路径中提前发现 `TERMINATED`，避免一次必然失败的工具调用。

### 8.3 双重 release 噪声

cleanup 与 session close 都会调用 `release`，必然产生 `not tracked` WARN。建议把该分支降级为 debug，或让 `release` 幂等。

## 9. 文件清单

| 文件 | 职责 | 行数 |
|------|------|------|
| `core-ai/sandbox/Sandbox.java` | 沙箱能力接口 | 42 |
| `core-ai/sandbox/SandboxProvider.java` | provider 接口 | 13 |
| `core-ai/sandbox/SandboxConfig.java` | 配置 + 校验 | 65 |
| `core-ai-server/sandbox/SandboxService.java` | 会话级门面 | 180 |
| `core-ai-server/sandbox/SandboxManager.java` | 生命周期/跟踪 | 140 |
| `core-ai-server/sandbox/LazySandbox.java` | 懒加载代理 | 153 |
| `core-ai-server/sandbox/SandboxClient.java` | runtime HTTP 客户端 | 215 |
| `core-ai-server/sandbox/agentsandbox/AgentSandboxProvider.java` | CRD/warm-pool provider | 386 |
| `core-ai-server/sandbox/agentsandbox/SandboxCRSpecBuilder.java` | Sandbox CR 构造 | 141 |
