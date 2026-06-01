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
        ├─ ensureReady()                                   // 懒建 / 自愈
        └─ AgentSandbox.execute
              └─ SandboxClient HTTP POST podIP:8080/execute
                    └─ {status: completed | failed | timeout | pending}
```

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

**目标方案（按活动续期）**：

- 给 `AgentSandboxProvider` 增加 `renewClaim`，在 `touchActivity` 时 PATCH `SandboxClaim.spec.lifecycle.shutdownTime = now + timeout`，让续期真正作用到 K8s。
- 机制 ③ 的清理基准从 `creationTimestamp` 改为 claim 上的 `shutdownTime`，与 K8s 原生 lifecycle 对齐。
- 效果：活跃会话沙箱不再被中途删除；只有真正空闲超时的沙箱才被回收。

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
