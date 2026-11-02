# Tech Design：本地 Session 持久化的崩溃一致性与多写者治理

> 状态：**评审修订稿 v4**（2026-10-30，三轮评审意见已吸收，见 §10、§11、§12）
> 范围：core-ai-cli 与 core-ai-desktop 共用的本地 session 文件（`~/.core-ai/sessions/<workspace>/`），含主快照 `.data`、subagent 线程文件、`titles.json`、`models.json`、`~/.core-ai/app-server/staging/`。**不含** server 端 MongoDB 持久化。
> 代码核实：`FileSessionPersistence`、`Node`、`AgentPersistence`、`TurnPersistence`、`InProcessAgentSession`、`AgentInterruptionHandler`、`CustomEventEmitter`、`EngineSession`、`EngineSessionRegistry`、`SessionTitleStore`、`SessionModelStore`、`PathUtils`、`CliAppHelper`、`CliApp`、`AgentSessionRunner`、`AgentSessionRunnerCommandHandler`、`AcpAgentRunner`、`AcpSlashCommandHandler`、`SubagentThreadRegistry`、`AppServer`、`MediaSettingsApi`、`FileScheduledTaskStore`、`HelperConfigStore`、desktop `ChannelBridge`、`app.ts`
> 关联（以下文档暂未纳入本站）：design-cli-session-history-context.md、design-subagent-thread.md、design-desktop-session-surfaces.md、design-session-custom-events.md
> 复现与证据（均在仓库内）：`core-ai/src/test/java/ai/core/session/SessionSnapshotCrashConsistencyTest.java`、`TornWriteProbe.java` + `TornWriteProbeTest.java`（`CORE_AI_PROBE=true` 门控）、`core-ai-cli/src/test/java/ai/core/cli/utils/PathUtilsTest.java`、`CliAppHelperTest.defaultSessionIdIsUniqueForTwoSessionsCreatedInTheSameSecond`

## 0. 决策摘要

| # | 决策点 | 结论 | 说明 |
|---|--------|------|------|
| 1 | 写入方式 | 所有 session 相关文件改为**同目录临时文件 + fsync + `ATOMIC_MOVE` 替换 + 不支持时降级**，抽成 `ai.core.utils.AtomicFiles` 供全仓库复用 | 仓库里已有三份手写的同样逻辑，六个存储仍在裸 `writeString`。降级到 `REPLACE_EXISTING` 的路径是 best-effort，与 `FileScheduledTaskStore` 一致 |
| 2 | fsync | 临时文件在 rename 前 `force(true)`；系统属性 `core.ai.fsync=false` 关闭，每次调用读取 | 实测 21.7 MB 快照写入 150 ms，fsync 再加几十毫秒。Windows + Defender 实测 p99 超过 500 ms 再议默认值 |
| 3 | 保存失败语义 | 所有保存点统一走 `public static boolean TurnPersistence.save(...)`：失败 warn + `persistence` 自定义事件，返回是否成功，本轮结果不变；“连续失败只发一次”的状态挂在会话上，随会话关闭清理 | 今天正常完成点的 `agent.save` 不在 try 内，保存失败会把成功的一轮报成 `ErrorEvent` |
| 4 | 读路径状态模型 | 单一状态枚举 `SessionLoadState { OK, MISSING, CORRUPT, NEWER_VERSION, IO_ERROR }`；列表用 `SessionTitle(text, state)`，恢复用 `SessionLoad`；`list` 对重命名项也解析，所有入口对同一文件给出相同状态 | v2 的 `FirstUserMessage(text, corrupt)` 表达不了 NEWER_VERSION 和 MISSING，且重命名项会绕过检测 |
| 5 | 加载落点 | `Node` 增加 `public void restore(String id, T snapshot)` 风格的入口（Agent 上为 `restore(String id, AgentPersistenceDomain domain)`），`SessionLoad.Ok` 携带已解析的 domain，只解析一次 | `Node.persistence`、`Node.id` 均 private，`load(id)` 会再读一次文件；二次读之间文件可能已被替换 |
| 6 | blank 文件 | 存在但 0 字节的 `.data` 是 **CORRUPT**，不是 Missing | 0 字节正是 2026-05 磁盘满的线上形态，它会出现在列表里 |
| 7 | id 校验 | RPC 边界严格白名单 `[A-Za-z0-9._~-]`（含 `session/delete` 的数组逐个检查）；provider 层只拒绝路径分隔符、`..`、控制字符，`save/load/delete` 全部经过 | `path(id)` 今天是字符串拼接；subagent 线程 key 的 `sanitize` 不保证落在严格白名单内 |
| 8 | session id | `cli-yyyyMMdd-HHmmss-xxxx`；`create` 用 `putIfAbsent` + 重试，碰撞时先 `close` 刚 build 的会话并释放锁 | 秒级精度同秒即撞；重试不 close 会每次碰撞泄漏一个会话 |
| 9 | workspace 身份与目录 | **按稳定身份归属，不按路径存在性**：项目 `.core-ai/workspace.id`（UUID）为主键，注册表 `workspaces.json` 按 id 索引；目录默认 `basename`，碰撞加 `-<hash8>`，再碰撞顺延 `-2/-3`；`lastPath` 一律存 canonical；**不自动接管**，复制出的项目走新 id 并提示 | v2 的"owner 路径不存在即接管"会在外接盘暂时离线时把 A 的会话给 B；`sessionDirName(basename, identity, collision)` 必须显式带 hash 输入 |
| 10 | 跨进程互斥 | P1：会话级 `<id>.lock`，锁在 `SessionLoads.load` **之前**取；持有者信息在已加锁的 channel 上原地写；`tryAcquire` 返回 `AcquireResult`（Acquired / Busy(holder, local)），不靠 null 兼表两义；锁文件在 `release` 时不删，session delete 时一并删；本地持有者特判；释放点：`session/close`（先 save 再 close）、30 分钟空闲驱逐、delete、closeAll finally | 释放的前提是 `requireSession` 对"文件在、不在 map"的 id 透明 resume，否则驱逐等于让 UI 假死 |
| 11 | engine 单写者 | 每 workspace 一个 `engine.lock`，在读旁车和 housekeeping **之前**获取；只有 engine 写 `titles.json` / `models.json` | 旁车多写者用硬约束解决 |
| 12 | 清理职责 | `Housekeeping`（CLI / engine / ACP 启动各一次）：删 >10 分钟的 `*.tmp`；staging 只在 engine 启动时清，按**全局** sessions 索引判断 `.data`/活锁，阈值与 desktop `sweepStaging` 对齐（7 天）；`retainOnly`（仅 engine 启动）：清旁车孤儿条目，用 `tryLock` 判断锁是否活着 | v2 三处描述不一致；10 分钟 + 当前 workspace 检查会误删别的 workspace 的附件 |
| 13 | schema 版本 | 快照与 subagent 线程文件共用 `version`，缺省 1；`SnapshotVersion.check` 在 `SessionLoads.load`、`AgentPersistence.deserialization`、`SubagentThreadRegistry.load` 三处调用 | 已验证 core-ng 容忍未知字段；`Node.load` 的调用方也不能绕过版本检查 |
| 14 | ACP | P1 的 newSession / loadSession 取 `SessionLock`，进程退出统一 release；SDK 0.11 没有 closeSession handler，`/resume` 切锁列为已知限制 | 当前 ACP 没有 close handler，`sessions` 从不 remove；先释放旧锁再 load 失败会留下无锁的当前会话 |
| 15 | 整快照重写、RPC 线程阻塞 | 本期不改，P2 评估 | 实测每轮重写 21.7 MB：序列化 161 ms + 写入 150 ms；`session/cancel` 在单线程 RPC 上同步保存约 0.3 s；首次 `session/list` 解析 50 份约 3 s |
| 16 | 不做 | 自动修复或删除损坏文件；强制抢锁；按路径自动接管；改 server 端；SQLite | — |

## 1. 问题与目标

### 1.1 事实

对本机 `~/.core-ai/sessions` 的真实文件和代码的审计结论（2026-10-30 复测，单位为十进制 MB，括号内为 MiB）：

| 现象 | 证据 |
|------|------|
| 保存非原子 | `FileSessionPersistence.save` 单句 `Files.writeString`，先截断再按 8192 字节分块写；`TornWriteProbeTest` 12 轮：11 轮半截、1 轮 0 字节，0 轮旧快照幸存；JFR：每次保存 2655 个 `jdk.FileWrite`，0 个 `jdk.FileForce` |
| 一个半截文件拖垮整个 workspace | `EngineSessionRegistry.title()` :398、`sessionTitle()` :385、`CliAppHelper.pickSession()` :375、`AgentSessionRunnerCommandHandler.showSessionPicker` :242 调 `firstUserMessage` 无兜底；`CliApp.resolveSessionId` :160 的 `--continue` 取最新一份后 `AgentSessionRunner` :117 / :147 裸 `agent.load`；`AcpAgentRunner` :221 / :267、`AcpSlashCommandHandler.handleResume` :317 同样 |
| 两个写者互相撕裂 | `Node.saveLock` 只保护同一个 Agent 实例；测试 10/10 轮混合文件 |
| session id 秒级精度 | `defaultSessionId` 用 `yyyyMMdd-HHmmss`；desktop `ChannelBridge.resolveSession` 每个新 IM 会话创建一次，两个会话同秒首次出现即撞；CLI 与 desktop 同前缀跨进程也撞 |
| 同名 workspace 共用目录 | `PathUtils.sessionsDir` 只取 `getFileName()` |
| 客户端 id 可路径穿越 | `FileSessionPersistence.path(id)` 字符串拼接；`resume` / `delete` / `sessionFileExists` 直接拼 RPC 传入的 id |
| 正常完成的保存失败被报成运行失败 | `InProcessAgentSession` :211 的 `agent.save` 在 :144 `catch (Throwable)` 内；`AcpAgentRunner.persistSession` :343 无 catch |
| 孤儿旁车、staging 泄漏 | `create` :116 先写 `titles.json`；`delete` 不删 staging，且只在 `.data` 存在时才进 `toDelete` |
| 写放大 | 802 个文件、794 份主快照，总量 660 MB（630 MiB）；最大 21.7 MB（20.7 MiB）；超过 10 MB 的 5 份（10 MiB 口径 4 份） |

历史：commit `843a2bb0`（2026-05-27）"fix cli crash because the session file broken - 0 byte because the disk full"，只对 blank 返回空。

### 1.2 目标

| # | 目标 | 验收 |
|---|------|------|
| A | kill、磁盘满、rename 被短暂阻塞时，磁盘上要么旧快照要么新快照（`ATOMIC_MOVE` 不可用的降级路径为 best-effort） | `TornWriteProbeTest` torn + empty = 0；`AtomicFilesTest` 写入失败后旧文件不变 |
| B | 单个损坏、新版本或暂时不可读的文件不影响其他 session 的列表、恢复、删除，desktop / CLI / ACP 同样成立 | `listingSurvivesATruncatedSnapshot` 转绿；`--continue` 回退；列表对 IO_ERROR 降级为行标记 |
| C | 两个 session 永不共用文件；客户端 id 不能越出目录 | id 唯一性、目录碰撞、路径穿越测试 |
| D | 保存失败不改变本轮结果，用户能看到并能重试 | `TurnPersistenceTest`；`session/save` RPC |
| E | 第二个进程得到带持有者信息的 `SESSION_BUSY`；空闲会话的锁会释放且 UI 不假死 | 双进程集成；驱逐后 `send` 透明恢复 |
| F | 旁车与 staging 不漂移，含崩溃未走 delete 的残留 | 启动清理测试 |

### 1.3 非目标

不改 `AgentPersistenceDomain` 的内容结构（只加 `version`）；不改 server 端；不改 RPC 线程模型；不引入新存储介质；不提供强制抢锁；不按路径自动接管历史。

## 2. 现状分析（代码事实）

### 2.1 写路径地图

| 触发 | 调用点 | 线程 | 失败处理 |
|------|--------|------|----------|
| 轮次正常结束 | `InProcessAgentSession` :212 / :240 | session 线程 | **无**，落入 `catch (Throwable)` 报 `ErrorEvent` |
| 轮次失败、取消 | `TurnPersistence.save` ← :175 / :179 | session 线程 | best-effort + warn（包级方法） |
| max turns | `InProcessAgentSession` :256 | session 线程 | 无 |
| 用户取消 | `AgentInterruptionHandler` :83 | 调用者线程（CLI 键盘线程 / engine RPC 线程） | 无 |
| compact / undo | `EngineSession` :105 / :135；`AgentSessionRunnerCommandHandler` :192 / :223；`AcpSlashCommandHandler` :202 / :215 | RPC / REPL 线程 | 无 |
| 优雅关闭 | `EngineSessionRegistry.closeAll` :294；`AgentSessionRunner` :187；`AcpAgentRunner.persistSession` :345 | 关闭线程 | closeAll 的 `save()` 抛出时 `close()` 不执行；ACP 无 catch |
| 子代理线程 | `SubagentThreadRegistry` :207 / :212 | 工具执行线程 | 无 |
| 旁车 | `SessionTitleStore.save`、`SessionModelStore.save` | RPC 线程 | warn；构造时读到损坏 JSON 从空 map 开始，下一次 set 覆盖全部旧标题 |

### 2.2 读路径

| 入口 | 路径 | 兜底 |
|------|------|------|
| desktop 列表 | `list` → `summary` → `title`：先查 `titles.title(id)`，命中直接返回；否则 `firstUserMessage` → `JSON.fromJSON`，按 mtime 缓存 | 无；重命名项不解析 |
| schedules 页标题 | `sessionTitle` :385，同上优先级 | 无 |
| desktop 恢复 | `resume` :127 → `load` 判存在 → `build(id, true)` → `agent.load` | 无 |
| desktop 发送 | `app.ts` :3817 `session/send`，**不先 resume**；`call` 失败只 toast 返回 null（:1088） | 会话不在 map 即 `SESSION_NOT_FOUND` |
| CLI `--continue` / `--resume` / `/sessions` | `CliApp` :160、`CliAppHelper.pickSession` :375、`AgentSessionRunnerCommandHandler.showSessionPicker` :242 → `agent.load` | 无 |
| ACP | `AcpAgentRunner` :221 / :267、`AcpSlashCommandHandler.handleResume` :317 | 无 |
| subagent 线程 | `SubagentThreadRegistry.load` :160 直接 `JSON.fromJSON` | 无 |

### 2.3 会话生命周期与可见性

- engine：`sessions` map 只在 `delete` :224 与 `closeAll` :292 移除；无 `session/close`，无空闲驱逐。
- ACP：builder 只注册 `newSession / loadSession / prompt / cancel`（`AcpAgentRunner` :168-194），无 close；`sessions` 只 `put` :232 / :278。
- desktop：`requestSingleInstanceLock` 单实例；`ChannelBridge.resolveSession` :343 对 `session/resume` 任何异常都新建会话。
- `Node`：`persistence`、`id` 均 private（:42、:36），`setId` 包级（:424），`load(id)` :204 自己再读一次文件。
- `TurnPersistence.save` 包级（:14），只有 `Agent, sessionId, logger, reason`，无事件出口。`CustomEventEmitter.CONTEXT_KEY` 为 public，可从 `agent.getExecutionContext()` 取 emitter。

### 2.4 仓库内已有的正确样本

`FileScheduledTaskStore.persist` :405（`.tmp` + `ATOMIC_MOVE` + 降级 + 共享冲突重试 3 次）、`MediaSettingsApi` :213、`HelperConfigStore` :144（无 `ATOMIC_MOVE`）。仍裸写的：`FileSessionPersistence`、`FilePersistenceProvider`、`FileRuleBasedPermissionStore`、`FileTodoStore`、`SessionTitleStore`、`SessionModelStore`。

### 2.5 外部基线

Claude Code（本机 `~/.claude/projects/*.jsonl`）append-only JSONL，1.8 MB 文件 821 行；write-file-atomic / electron-store 临时文件 + fsync + rename；SQLite 文档把"截断后分块写"列为典型损坏来源。

## 3. 总体设计

```
启动（每 workspace 一次）
  WorkspaceDirs.ensure(workspace)        读/建 .core-ai/workspace.id → 查 workspaces.json → 得到 sessions 目录
  EngineLock.acquire(dir/engine.lock)    仅 engine；失败 → ENGINE_ALREADY_RUNNING
  Housekeeping.run(dir)                  删 >10 min 的 *.tmp；staging 仅 engine 启动按全局索引清（7 天）
  retainOnly(...)                        仅 engine：清无 .data 且无活锁的 titles/models 条目
  SessionTitleStore / SessionModelStore  在 EngineLock 之后构造；损坏文件改名 .bad 再重建

写
  任何保存点 ──► TurnPersistence.save(agent, id, logger, reason): boolean
                  └─► Agent.save ─► FileSessionPersistence.save ─ SessionFileNames.check ─► AtomicFiles.writeString
                  失败：warn + CustomEvent("persistence", failed)；本轮结果不变

读
  SessionLoads.load(persistence, id) ─► SessionLoad { Ok(domain) | Missing | Corrupt | NewerVersion | IoError }
    list / sessionTitle / pickSession: SessionTitle(text, state)，重命名项也解析，按 mtime 缓存
    resume:    Ok → agent.restore(id, domain)；其余 → 业务错误
    --continue: 跳过非 Ok 取下一份
    requireSession: 不在 map 但文件在 → 透明 resume（P1）

互斥（P1）
  SessionLock.tryAcquire(dir/<id>.lock) ── 在 SessionLoads.load 之前
  释放：session/close(save→close)、空闲驱逐、delete、closeAll finally、进程退出；锁文件保留
```

## 4. 详细设计

### 4.1 `AtomicFiles`（新，`ai.core.utils`）

```java
public final class AtomicFiles {
    public static void writeString(Path target, String content);
    public static void write(Path target, byte[] content);
}
```

1. `createDirectories(parent)`；
2. 临时文件 `parent/.<target 文件名>.<pid>.<random>.tmp`；
3. `FileChannel.force(true)`，属性 `core.ai.fsync` 每次调用读取；
4. `Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)`；`AtomicMoveNotSupportedException` 降级 `REPLACE_EXISTING`（best-effort）；其他 `IOException` 重试 3 次、间隔 50 ms；
5. 任一步失败：删 tmp，抛 `UncheckedIOException`，目标文件保持原样。

故障注入：包级可见的 `writer` 钩子（默认 `Files.write`），测试替换为"写一半抛异常"；钩子用 `ThreadLocal` 或每次调用的包级重载，避免并行测试互相干扰。

**禁止用于锁文件**：rename 会换 inode，锁会挂在旧文件上（见 4.9）。

接入顺序（各自独立 commit）：① `FileSessionPersistence` + `SessionTitleStore` + `SessionModelStore`；② `FilePersistenceProvider` + `FileRuleBasedPermissionStore` + `FileTodoStore`；③ 删除三份手写版本改用。

### 4.2 id 校验：两层

| 层 | 类 | 规则 | 调用点 |
|----|----|------|--------|
| RPC 边界 | `SessionIds.check(id)` | `^[A-Za-z0-9._~-]{1,200}$`，否则 `invalidParams` | `EngineSessionRegistry` 所有读 `sessionId` 的方法；`delete` 的 `sessionIds` 数组逐个检查 |
| 文件名安全 | `SessionFileNames.check(key)` | 拒绝 `/`、`\`、`..` 片段、控制字符、空串、以 `.` 开头 | `FileSessionPersistence.path()`，`save/load/delete/deleteSubagentFiles/exists` 全部经过 |

`sessionFileExists` :405 与 `EngineScheduler` :157 改调 `FileSessionPersistence.exists(id)`。

### 4.3 读路径：状态模型与统一加载入口

```java
public enum SessionLoadState { OK, MISSING, CORRUPT, NEWER_VERSION, IO_ERROR }

public record SessionTitle(String text, SessionLoadState state) {}     // text 可为 null（无用户消息 / 文件不存在）

public sealed interface SessionLoad {
    record Ok(AgentPersistence.AgentPersistenceDomain domain) implements SessionLoad {}
    record Missing() implements SessionLoad {}                         // 仅文件不存在
    record Corrupt(String reason) implements SessionLoad {}            // 含 0 字节文件
    record NewerVersion(int version) implements SessionLoad {}
    record IoError(IOException cause) implements SessionLoad {}
}

public final class SessionLoads {
    public static SessionLoad load(SessionPersistence persistence, String id);   // 读 + 解析 + 版本检查，一次
    public static SessionTitle title(SessionPersistence persistence, String id); // 基于 load，取第一条用户消息
}
```

**`FileSessionPersistence.load` 变更**：存在但 blank 的文件返回 `Optional.of("")`，`SessionLoads` 把 blank 归为 `Corrupt("empty file")`；`Missing` 只在 `!exists` 时产生。

**`Agent.restore(String id, AgentPersistenceDomain domain)`**（新，public）：等价于 `AgentPersistence.deserialization` 的 domain 部分 + `setId(id)`，不再读文件。`Node.load(id)` 保留给未接入 `SessionLoads` 的调用方。

**`SessionManager.firstUserMessage` 替换为 `SessionManager.title(id): SessionTitle`**，调用方全部更新：`EngineSessionRegistry.title/sessionTitle`、`CliAppHelper.pickSession`、`AgentSessionRunnerCommandHandler.showSessionPicker`、`SessionSnapshotCrashConsistencyTest`。

**列表优先级**：先解析文件得到 `state`，再决定显示文字：有重命名用重命名，否则用第一条用户消息，否则用 id。即**重命名项也解析**，`list`、`sessionTitle`、`pickSession` 对同一文件状态一致。`CachedTitle` 改为 `(mtime, text, state)`。

**各入口行为**：

| 入口 | CORRUPT | NEWER_VERSION | IO_ERROR |
|------|---------|---------------|----------|
| `session/list` | 文字退回重命名或 id；`summary.state = "corrupt"` | `summary.state = "newer_version"` | `summary.state = "io_error"`，warn；**列表整体仍成功** |
| `session/resume` | `business("SESSION_CORRUPT")` | `business("SESSION_NEWER_VERSION", data.version)` | INTERNAL_ERROR |
| `sessionTitle` | 重命名或 id | 同左 | 同左 |
| CLI `--continue` | 跳过取下一份；全部不可用则新建并提示 | 同左，提示升级 | 跳过并打印原因 |
| CLI `--resume` / `/sessions` | `(unreadable)`，仅 corrupt 项 | `(needs newer core-ai)` | `(unavailable)` |
| ACP `loadSession` / `/resume` | ACP 业务错误 `session_corrupt` | `session_newer_version` | 透传 |
| `SubagentThreadRegistry.load` :160 | 线程标记不可续，warn | 同左 | 透传 |

`Missing`（文件不存在）不进入 `list`；`resume` → `SESSION_NOT_FOUND`；`sessionTitle` → `SessionTitle(null, MISSING)`，`SchedulesApi` 退回显示 `sessionId`；CLI/ACP 同 resume。

**desktop UI**：

| `summary.state` | 行内表现 | 允许的操作 |
|------|----------|------------|
| `corrupt` | 灰字 + "会话文件损坏" | 删除 |
| `newer_version` | 灰字 + "需要更新 core-ai" | 删除、升级引导 |
| `io_error` | 灰字 + "暂时无法读取" | 重试（刷新列表）、删除 |
| `SESSION_BUSY`（P1） | toast："正由 `<process>` (pid `<pid>`) 使用，自 `<since>`" | 重试；不新建 |

`ChannelBridge.resolveSession` :343 按错误码分支：`SESSION_NOT_FOUND` / `SESSION_CORRUPT` 才 `session/create`；`SESSION_BUSY` 有限次退避重试，超时告知；`SESSION_NEWER_VERSION` 提示升级并**停止重试/不新建**（当前进程读不了更高版本，重试无意义）；其他错误失败本条消息，不新建。

### 4.4 保存失败语义

```java
// ai.core.session.TurnPersistence
public static boolean save(Agent agent, String sessionId, Logger logger, String reason);
```

- 无 provider 或无用户消息 → `true`（无事可做）。
- 成功 → `true`。
- 抛出 → warn；从 `agent.getExecutionContext().getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY)` 取 emitter，存在则发 `CustomEvent.of(sessionId, "persistence", {"status":"failed","reason":...,"file":...}, null, null)`；返回 `false`。
- 同一会话连续失败只发一次事件，成功后重置并发 `{"status":"ok"}`；“已发过失败”状态挂在 `InProcessAgentSession`（随 `close` 清理），不放在静态 map 里长期累积。

| 调用点 | 变更 |
|--------|------|
| `InProcessAgentSession` :212 / :240 / :256 | 改调 |
| `AgentInterruptionHandler` :83 | 改调（无 emitter 时只 warn） |
| `EngineSession.compact/undo` :105 / :135 | 改调；RPC 返回 `persisted: boolean` |
| `AgentSessionRunnerCommandHandler` :192 / :223 | 改调；REPL 打印结果 |
| `AcpSlashCommandHandler` :202 / :215、`AcpAgentRunner.persistSession` :343 | 改调；ACP warning 通知 |
| `EngineSessionRegistry.closeAll` :293 | `try { save } catch { warn } finally { close }` |
| `SubagentThreadRegistry` :207 / :212 | 包 try/catch，warn |

新增 RPC `session/save`：desktop 的"未保存"toast 提供"重试"动作；CLI 提供 `/save`。

### 4.5 session id

`defaultSessionId(prefix)` = `prefix + yyyyMMdd-HHmmss + "-" + 4 位 base36`。`AcpAgentRunner` :201 改调。`create`：

```java
for (int attempt = 0; attempt < 3; attempt++) {
    var id = CliAppHelper.defaultSessionId("cli-");
    if (!(SessionLock.tryAcquire(dir, id) instanceof AcquireResult.Acquired acquired)) continue; // P1
    var session = build(id, false, acquired.lock());
    if (sessions.putIfAbsent(id, session) == null) return id;
    session.close();                                        // 释放 lock、关闭 TurnDriver
}
throw RpcException.business("SESSION_ID_EXHAUSTED", ...);
```

唯一性测试断言格式 `^cli-\d{8}-\d{6}-[a-z0-9]{4}$`，不依赖随机不碰撞。

### 4.6 workspace 身份、注册表与目录（P1）

**身份**：项目目录下 `.core-ai/workspace.id`，内容为 UUID，首次运行生成；`.core-ai/.gitignore` 追加 `workspace.id`（避免随仓库复制）。目录只读或不可写时退回"路径身份"：id = `path:<canonical>`，不持久化。

**纯函数**：`PathUtils.sessionDirName(String basename, String identity, int collision)` → `basename`、`basename-<sha256(identity)[0,8)>`、`basename-<hash8>-2`…；`identity` 优先用 `workspace.id`，只读项目回退用 canonical path。`PathUtilsTest` 只测它（同一 identity 稳定、不同 identity 不同、collision 顺延）。

**注册表** `~/.core-ai/sessions/workspaces.json`（`AtomicFiles` 写，`workspaces.lock` 互斥）：

```json
{ "version": 1,
  "workspaces": { "<workspace id>": { "dir": "app", "lastPath": "D:\\clients\\acme\\app", "claimedAt": "..." } } }
```

每个 sessions 目录内另有 `owner.json` `{ "id": ..., "lastPath": ... }`（`AtomicFiles` 写），注册表损坏时据此重建。

**`WorkspaceDirs.ensure(workspace)`**（每 canonical 路径进程内 memo 一次，只在 CLI / engine / ACP 启动调用）：

1. 读或生成 `workspace.id`。
2. 注册表有该 id → 用其 `dir`，更新 `lastPath`。项目改名或移动时 id 跟着走，历史自然找回。
3. 无该 id，`basename` 目录不存在 → 建目录、写 `owner.json`、注册。
4. 无该 id，`basename` 目录存在但无 `owner.json`（legacy）→ **领养**：写 `owner.json`、注册。这是今天所有未碰撞 workspace 的升级路径，目录名不变。
5. 无该 id，`basename` 已有 `owner.json` 属于别的 id → 碰撞：取 `sessionDirName(basename, n)` 第一个空闲名，建目录、注册。**不自动接管**，无论 owner 路径是否存在。
6. 注册表写失败（锁冲突重试 3 次后仍失败、I/O）→ 仍使用已写 `owner.json` 的目录并 warn；`owner.json` 在下次启动重建注册表。不要为了“重试”换目录，否则本次会话会写进另一个目录、下次启动又回到原目录。

**复制检测**：`lastPath` 一律存 canonical path。若注册表里同一 id 的 `lastPath` 与当前 canonical 路径不同且 `lastPath` 仍存在 → 判定为复制：当前路径生成新 id、走第 3/5 步，并提示"检测到项目副本，会话历史未复制"。`lastPath` 不存在 → 判定为移动，走第 2 步。当前路径 canonical 与 `lastPath` 相同（symlink/junction/映射盘）→ 同一 workspace，只更新 `lastPath`，**绝不**改写共享的 `workspace.id`。

**取舍与 rollout**：旧版本 CLI 仍按 basename 写；未碰撞 workspace 新旧版本同目录，可混跑可回退。碰撞 workspace 的旧版本继续写 `basename`（即别人的目录），这是今天已有的 bug。

### 4.7 会话锁（P1）

`SessionLock.tryAcquire(Path dir, String id): AcquireResult`，其中 `AcquireResult` 为 `Acquired(SessionLock)` / `Busy(Holder holder, boolean local)`；`null` 不再兼表“busy / 本地已持有”两义：

- 打开 `<id>.lock`（`CREATE, READ, WRITE`），`channel.tryLock()`；同进程重复获取抛 `OverlappingFileLockException`，捕获后返回 `Busy(holder, local=true)`。
- 成功后在**同一 channel** 上 `truncate(0)` + 写 `{"pid":..,"process":"desktop-engine|cli|acp","since":..}`，不经 `AtomicFiles`。
- 远程 busy 时读取文件内容作为 `Holder` 放进 `SESSION_BUSY` 的 data；读不到则只给 pid 未知。
- `release()`：`lock.release()` + `channel.close()`；**不删除 `.lock` 文件**（删除会让另一进程在旧 inode 上加锁，再与新文件形成两把锁）。每 id 一个几十字节的文件，随 `delete` 一起删除（见下）。

**获取时机**：`create`（见 4.5）、`resume` / `resumeQuietly`、`AgentSessionRunner` 启动、`AcpAgentRunner.newSession/loadSession`。**顺序**：先 `tryAcquire`，再 `SessionLoads.load`，再 `build`；任何一步失败释放锁。

**释放点**：

| 场景 | 行为 |
|------|------|
| `session/close`（新 RPC） | `TurnPersistence.save` → `close`（若正在运行则强制中止当前轮并发出 cancelled 终止事件）→ `sessions.remove` → `release`；desktop 关闭标签或切换 workspace 时调用 |
| 空闲驱逐 | `!running()`、无 pending work、最近活动 > 30 分钟 → 同 `session/close`；由 heartbeat 线程每 5 分钟扫描 |
| `delete` | 若 id 在本 `sessions` map：`close` + `remove`（释放本地锁）；然后 `tryAcquire`，拿不到 → `SESSION_BUSY`，不删；拿到 → 删 `.data`、线程文件、旁车、staging，`release`，删 `.lock` |
| `closeAll` | `try { save } catch { warn } finally { close; release }` |
| 进程退出 | OS 回收 |

**`requireSession` 透明恢复**（驱逐的前提）：`send / history / stats / compact / undo / export / cancel / approve` 走 `requireSession`；id 不在 map 但 `exists(id)` → 执行 `resume` 逻辑（含取锁、`SessionLoads`），失败按 4.3 的 resume 错误返回；`delete` 不走透明恢复。desktop 渲染层不需要改 `sendCurrent`。

**scheduler**：`resumeQuietly` 取不到锁 → 跳过本次触发并记录，不抢占。

**ACP**：`acp-agent-support 0.11.0` 的 `AcpAgent.sync(...)` builder 只有 `initialize / newSession / loadSession / prompt / cancel`，**没有 closeSession handler**；P1 在 `AcpAgentRunner` 进程退出时统一 `persist` + `release`。`AcpSlashCommandHandler.handleResume` :317 仍不切换锁（现有 `/resume` 只换 agent 内容、不更新 `AcpSession.sessionId`），这是已知限制；ACP SDK 提供 close 或 session switch 后再补。newSession / loadSession 仍取 `SessionLock`，失败返回 ACP 错误。

**限制**：网络盘上 `tryLock` 不可靠，锁是 best-effort；正确性由 4.1 保证。旧版本 CLI 不识别 `.lock`，混跑期不受约束。

### 4.8 engine 单写者与旁车

- `EngineLock`：engine 在 `WorkspaceDirs.ensure` 之后、构造 `SessionTitleStore` / `SessionModelStore` 与 `Housekeeping` 之前 `tryLock` `engine.lock`；失败 → `EngineAlreadyRunningException`（含持有者），`app-server` 进程退出码 2 并把信息写到 stderr，desktop supervisor 报 engine 启动失败。此时尚未进入 JSON-RPC `initialize`，所以错误不是 RPC 业务码。持有者信息写法同 4.7。CLI / ACP 不取此锁。
- `SessionTitleStore` / `SessionModelStore`：`AtomicFiles` 写；构造时解析失败 → 原文件改名 `titles.json.bad-<ts>`，warn，从空 map 开始，不静默覆盖。
- `retainOnly`：仅 engine 启动时执行一次；删除既无 `.data`、又无**活锁**的条目。liveness 检查用不创建文件的 open + `tryLock`（或接受为每个 title 创建几十字节 `.lock` 残留），避免为孤儿条目制造新锁文件。

### 4.9 `Housekeeping`

CLI / engine / ACP 启动各执行一次，best-effort、每项 catch：
1. 删除 `.*.tmp` 中 mtime 早于 10 分钟的文件；
2. **staging 只在 engine 启动时清**（CLI/ACP 不清）：扫描**全局** `~/.core-ai/sessions/*/` 的 `<id>.data` / `<id>.lock`，删除 `<id>` 在任一 workspace 都无 `.data`、无活锁，且目录 mtime 早于 **7 天**的 staging 目录；阈值与 desktop `AttachmentStager.sweepStaging`（`main.ts:1056`）一致，避免误删别的 workspace 或跨 workspace 的草稿附件；
3. 不碰 `titles.json` / `models.json`（归 `retainOnly`），不碰 `.lock`。

注：desktop 已有 `sweepStaging(7 天)`；保留它作为桌面启动时的兜底，engine 的清理与它同阈值，二者都只删“全局无 `.data`/活锁”的目录。

### 4.10 schema 版本

`AgentPersistenceDomain` 加 `version`（写入 1）；`SnapshotVersion.check(domain)`：`null` 视为 1，`> CURRENT` 抛 `UnsupportedSnapshotVersionException(version)`。调用点：`SessionLoads.load`、`AgentPersistence.deserialization`（`Node.load` 的调用方不能绕过）、`SubagentThreadRegistry.load` :160。

## 5. 边界与风险

| 风险 | 应对 |
|------|------|
| fsync 延迟 | 属性可关；Windows + Defender 实测 p99；P2 格式改造是根本解 |
| rename 被 AV 阻塞 | 重试 3 次；仍失败走 4.4，旧文件完好，UI 可重试 |
| `ATOMIC_MOVE` 不支持的文件系统 | 降级为 best-effort，与 `FileScheduledTaskStore` 一致，目标 A 已注明 |
| 透明 resume 让 `send` 在驱逐后多 2 s 以上（21 MB 解析 2.4 s） | 可接受；UI 已有发送中状态 |
| `workspace.id` 被用户提交进 git 导致 clone 共享 id | `.core-ai/.gitignore` 追加；复制检测兜底 |
| 项目目录只读 | 退回路径身份，不持久化，行为等同今天 |
| 注册表损坏 | 由各目录 `owner.json` 重建 |
| stale `.lock` 永久保留 | 文件极小；`delete` 时一并删除；`retainOnly` 用 `tryLock` 判活，不受 stale 文件影响 |
| 非 session 存储改原子写改变失败模式 | 独立 commit（P0.5） |

## 6. 分期

| 期 | 内容 | 转绿的测试 |
|----|------|------------|
| P0 | 4.1 接入① + 4.2 + 4.3（状态模型、`SessionLoads`、`Agent.restore`、blank=Corrupt、列表 IO_ERROR 降级）+ 4.4（public `TurnPersistence.save`、`session/save`）+ 4.5 + 4.10（`version` 字段，`SessionLoads` 的 NEWER_VERSION 需要）+ `ChannelBridge` 分支 | `listingSurvivesATruncatedSnapshot`、`concurrentWriters...`、`defaultSessionIdIsUnique...`、`TornWriteProbeTest`、路径穿越、`--continue` 回退、`TurnPersistenceTest` |
| P0.5 | 4.1 接入②③（独立 commit） | `AtomicFilesTest` |
| P1 | 4.6 身份与注册表 + 4.7 锁（含 `session/close`、透明 resume、驱逐、ACP close）+ 4.8 + 4.9 | `sessionDirName` 测试、`WorkspaceDirsTest`、`SessionLockTest`、双进程集成、驱逐后 `send` 恢复、孤儿清理 |
| P2 | 决策 15 | — |

P0 的磁盘格式改动只有可忽略的 `version` 字段（旧版本容忍未知字段，新版本缺省按 1 处理）；P1 之后旧版本在未碰撞 workspace 仍可读写同一目录。

## 7. 文件影响

| 文件 | 变更 | 期 |
|------|------|----|
| `core-ai/.../utils/AtomicFiles.java` | 新增 | P0 |
| `core-ai/.../session/SessionFileNames.java`、`SessionLoadState.java`、`SessionTitle.java`、`SessionLoad.java`、`SessionLoads.java` | 新增 | P0 |
| `core-ai/.../session/FileSessionPersistence.java` | 原子写、`path()` 校验、`exists(id)`、blank 返回 `""` | P0 |
| `core-ai/.../session/SessionManager.java` | `firstUserMessage` → `title(): SessionTitle` | P0 |
| `core-ai/.../session/TurnPersistence.java` | public、返回 boolean、发事件 | P0 |
| `core-ai/.../session/InProcessAgentSession.java` | 保存点改经 `TurnPersistence` | P0 |
| `core-ai/.../agent/Node.java`、`Agent.java`、`AgentPersistence.java` | `Agent.restore(id, domain)`；`version` 字段与 `SnapshotVersion` | P0 |
| `core-ai/.../agent/AgentInterruptionHandler.java` | 改经 `TurnPersistence` | P0 |
| `core-ai/.../agent/subagent/SubagentThreadRegistry.java` | 写入 try/catch；`load` 版本检查 | P0 / P1 |
| `core-ai-cli/.../CliAppHelper.java`、`CliApp.java` | id 后缀；`pickSession` 用 `SessionTitle`；`--continue` 回退 | P0 |
| `core-ai-cli/.../agent/AgentSessionRunner.java`、`AgentSessionRunnerCommandHandler.java` | `load` 改 `SessionLoads`；`showSessionPicker` 用 `SessionTitle`；compact/undo 改经 `TurnPersistence`；新增 `/save` 命令 | P0 |
| `core-ai-cli/.../acp/AcpAgentRunner.java`、`AcpSlashCommandHandler.java` | `load` 改 `SessionLoads`；保存改经 `TurnPersistence`；复用 `defaultSessionId`；close handler、`/resume` 锁切换（P1） | P0 / P1 |
| `core-ai-cli/.../appserver/SessionIds.java` | 新增 | P0 |
| `core-ai-cli/.../appserver/EngineSessionRegistry.java` | id 校验、`summary.state`、`SESSION_CORRUPT`、`putIfAbsent` + close、`closeAll` finally、`session/save`；锁、`session/close`、透明 resume、驱逐、`delete` 流程（P1） | P0 / P1 |
| `core-ai-cli/.../appserver/EngineSession.java` | compact/undo 改经 `TurnPersistence`，返回 `persisted` | P0 |
| `core-ai-cli/.../appserver/AppServerEngine.java`、`AppServer.java` | 注册 `session/save`（P0）、`session/close`（P1）；heartbeat 驱逐扫描（P1） | P0 / P1 |
| `core-ai-cli/.../appserver/EngineScheduler.java` | 改调 `exists(id)`；锁失败跳过（P1） | P0 / P1 |
| `core-ai-cli/.../appserver/SessionTitleStore.java`、`SessionModelStore.java` | 原子写、`.bad` 改名；`retainOnly`（P1） | P0 / P1 |
| `core-ai-cli/.../appserver/EngineBootstrap.java` | `WorkspaceDirs.ensure`、`EngineLock`、`Housekeeping` 顺序 | P1 |
| `core-ai-cli/.../utils/PathUtils.java`、`WorkspaceDirs.java`（新） | `sessionDirName` 纯函数；身份、注册表、领养、复制检测 | P1 |
| `core-ai/.../session/SessionLock.java`、`EngineLock.java`、`Housekeeping.java`、`SnapshotVersion.java`、`UnsupportedSnapshotVersionException.java` | 新增 | P1 |
| `core-ai/.../persistence/providers/FilePersistenceProvider.java`、`session/FileRuleBasedPermissionStore.java`、`core-ai-cli/.../task/FileTodoStore.java` | 原子写 | P0.5 |
| `core-ai-cli/.../appserver/MediaSettingsApi.java`、`schedule/FileScheduledTaskStore.java`、`helper/HelperConfigStore.java` | 改用 `AtomicFiles` | P0.5 |
| `core-ai-desktop/src/renderer/app.ts` | `state` 行渲染；`persistence` 事件与重试；`SESSION_BUSY` toast；关闭标签调 `session/close`（P1） | P0 / P1 |
| `core-ai-desktop/src/main/channels/ChannelBridge.ts` :343 | 按错误码分支（BUSY 退避、NEWER_VERSION 不新建/不重试） | P0 |
| `core-ai-desktop/src/main/attachments/AttachmentStager.ts`、`main.ts` :1056 | 与 engine Housekeeping 对齐 staging 清理阈值（7 天）；保留 desktop 兜底 | P1 |
| `core-ai/src/test/.../SessionSnapshotCrashConsistencyTest.java` | 断言改为 `title(id).text()` / `.state()` | P0 |
| `docs/cn/cli.md` | `core.ai.fsync`、`/save` | P0 |

## 8. 测试计划

已存在、当前为红（对应期完成后必须转绿）：
- `SessionSnapshotCrashConsistencyTest.listingSurvivesATruncatedSnapshot`（P0；断言改为 `manager.title("good").text() == "hello"`、`manager.title("torn").state() == CORRUPT`）
- `SessionSnapshotCrashConsistencyTest.concurrentWritersNeverLeaveAMixedSnapshot`（P0）
- `CliAppHelperTest.defaultSessionIdIsUniqueForTwoSessionsCreatedInTheSameSecond`（P0，改为断言格式）
- `PathUtilsTest.sessionsDirIsDistinctForDifferentWorkspacesWithTheSameName`（P1，改测 `sessionDirName("app", id1, 0)` / `sessionDirName("app", id2, 0)` 稳定且不同、collision 顺延）
- `TornWriteProbeTest`（P0，`CORE_AI_PROBE=true` 门控；判定已改为内容比对）

新增：
- `AtomicFilesTest`：`writer` 钩子注入失败 → 目标不变、无残留 tmp；降级路径；`core.ai.fsync=false` 不 `force`。
- `SessionFileNamesTest` / `SessionIdsTest`：穿越与控制字符被拒；subagent key 在宽松层通过、严格层被拒；`delete` 数组逐项。
- `FileSessionPersistenceTest`：非法 id 的 `load/delete` 抛出；0 字节文件 `load` 返回 `Optional.of("")`。
- `SessionLoadsTest`：半截 → `Corrupt`；0 字节 → `Corrupt`；`version: 99` → `NewerVersion`；不存在 → `Missing`；`title` 对以上状态返回对应 `SessionTitle.state`（含 MISSING）；`Ok` 后 `Agent.restore` 不再读文件；FAILED 状态快照恢复后能继续一轮。
- `TurnPersistenceTest`：保存抛异常 → 返回 false、`persistence` 事件一次、`TurnComplete` 照发；连续失败只一次事件；成功后 `ok` 事件。
- `CliAppTest`：`--continue` 最新一份损坏时取下一份；全部损坏新建。
- `EngineSessionRegistryTest`：损坏 / 新版本 / IO 错误三种 `summary.state` 且被缓存；重命名的损坏项也带 `state`；`resume` 三种错误码；`create` 碰撞时 close 并重试；`closeAll` 中 `save` 抛出仍 `close`；`session/save`。
- `WorkspaceDirsTest`（P1）：六个分支；复制检测（canonical 相同视为同一 workspace，不改写共享 `workspace.id`）；注册表写失败仍用已写 `owner.json` 的目录；注册表损坏由 `owner.json` 重建；并发认领。
- `SessionLockTest` / `EngineLockTest`（P1）：第二次 `tryAcquire` 返回 `Busy(holder, local=false)`；同进程重复获取返回 `Busy(local=true)`；release 后文件仍在且可重新获取；`delete` 对他人持锁返回 `SESSION_BUSY`、对本地持锁先 close 再删。
- `HousekeepingTest`（P1）：staging 清理按全局 `.data`/活锁判断、7 天阈值，不误删其他 workspace 的目录；`*.tmp` 10 分钟阈值。
- `EngineSessionRegistryEvictionTest`（P1）：驱逐后 `send` 透明恢复；`delete` 不透明恢复。
- 手工：Windows + Defender 下 `force(true)` 尾延迟；JFR 确认每次保存 1 个 `jdk.FileForce`。

回归：`:core-ai:test`（1187）与 `:core-ai-cli:test`（445）全绿。注意 buildSrc `failFast = true`。

## 9. 评审点（v3）

| # | 问题 | 结论 / 待拍板 |
|---|------|------|
| Q1 | fsync 默认开 | 开；Windows + Defender p99 > 500 ms 再议 |
| Q2 | workspace 归属 | `workspace.id` 身份模型，不按路径接管；`lastPath` 存 canonical，symlink/junction 视为同一 workspace；复制检测提示。只读项目目录退回路径身份可接受（移动会丢历史，需提示） |
| Q3 | 空闲驱逐 30 分钟 | 保留，前提是 `requireSession` 透明 resume 落地（同一 P1 交付） |
| Q6 | 保存失败的 UI | toast + 行标记 + `session/save` 重试；连续失败只提示一次 |
| Q7 | `session/save` 是否也给 CLI | 给，`/save` 命令 |
| Q8 | staging 清理阈值与归属 | 仅 engine 启动、全局索引、7 天；与 desktop `AttachmentStager` 对齐 |
| Q9 | `SessionLock.tryAcquire` 返回类型 | `AcquireResult`（Acquired / Busy(holder, local)），不用 null 兼表两义 |

## 10. 评审修正记录（首轮，2026-10-30）

| 评审意见 | 处理 |
|----------|------|
| 数字偏差 | 单位差异（MB vs MiB）加期间新增会话；§1.1 双单位 |
| `TornWriteProbe` 不在仓库 | 搬进 `core-ai/src/test`，走真实 `FileSessionPersistence.save`，env 门控 |
| ChannelBridge 并非每条消息建 session | 改为"每个新 IM 会话建一次" |
| 1. `corrupt` 无法从 null 推导 | 引入显式结果类型（v3 进一步改为 `SessionTitle(text, state)`） |
| 2. CLI / ACP `load` 无覆盖 | `SessionLoads` 统一入口；`--continue` 回退 |
| 3. id 校验只在 save | 两层校验，`path()` 统一经过 |
| 4. 正常完成点保存失败被报成运行失败 | 所有保存点 best-effort + 事件 |
| 5. 迁移污染测试 | 纯函数 + 启动时 ensure |
| 6. 锁无释放点 | `session/close`、驱逐、delete、closeAll finally |
| 7. ChannelBridge 把 BUSY 当失效 | 按错误码分支 |
| 8. housekeeping 竞态 | 启动一次、10 分钟阈值、pid 临时文件 |
| 9. 旁车多写者 | `engine.lock` |
| 10. always-hash 回归 | basename 默认 + 碰撞加 hash + 注册表 |
| 次要项 | 均已处理 |

## 11. 评审修正记录（第二轮，2026-10-30）

| 评审意见 | 处理 |
|----------|------|
| 1. `FirstUserMessage(text, corrupt)` 表达不了 NEWER_VERSION；重命名项绕过检测 | 决策 4：`SessionLoadState` 枚举 + `SessionTitle(text, state)`；列表对重命名项也解析；`list` / `sessionTitle` / `pickSession` 状态一致 |
| 2. `SessionLoads.apply` 无落点 | 决策 5：`Agent.restore(id, domain)` public；`SessionLoad.Ok` 携带已解析 domain，只解析一次 |
| 3. 空闲驱逐让 desktop 下一条消息失败 | §4.7 `requireSession` 透明 resume，`delete` 除外；renderer 不改 |
| 4. 按路径接管会串项目；回退 basename 重引碰撞 | 决策 9：`workspace.id` 身份模型，不自动接管，复制检测；注册表写失败仍用已写 `owner.json` 的目录，下次重建 |
| 5. `TurnPersistence` 包级且无事件出口；漏 `AgentSessionRunnerCommandHandler` | §4.4 public `boolean save(...)`，经 `CustomEventEmitter.CONTEXT_KEY` 发事件；§4.4 / §7 补调用方 |
| 6. 锁的机械细节 | §4.7：持有者信息原地写、`OverlappingFileLockException` 本地特判、create 重试先 close、锁在 load 之前、锁文件不删、`session/close` 先 save |
| 7. ACP 无 close handler；`/resume` 不切锁 | 决策 14：新增 close handler；`/resume` 先取新锁再释放旧锁 |
| 8. blank 被判 Missing；列表 IO_ERROR 整体失败 | 决策 6：blank = CORRUPT，`load` 返回 `Optional.of("")`；列表 IO_ERROR 降级为 `state = io_error` |
| 9. `firstUserMessage` 改类型漏调用方 | §4.3 / §7 / §8 补 `showSessionPicker` 与测试断言 |
| 10. housekeeping / retainOnly / staging 不一致 | 决策 12：职责划分写死；`retainOnly` 用 `tryLock` 判活；staging 残留进 `Housekeeping` |
| 次要：`previousOwners` | 身份模型取代，删除 |
| 次要：hash 碰撞顺延 | `sessionDirName(basename, n)` 顺延 `-2/-3` |
| 次要：`EngineLock` 顺序 | §4.8 明确在旁车读与 housekeeping 之前 |
| 次要：探针按大小判定 | 已改为内容比对（`TornWriteProbe`） |
| 次要：降级路径 best-effort | 目标 A 与决策 1 注明 |
| 次要：旁车损坏静默覆盖 | 改名 `.bad-<ts>` 再重建 |
| 次要：`delete` 数组逐项校验 | §4.2 注明 |
| 次要：`sessionTitle` 优先级一致 | 与 `list` 同一路径 |
| Q2 / Q3 / Q6 回答 | 分别采纳身份模型、透明 resume 前置、`session/save` 重试入口 |

## 12. 评审修正记录（第三轮，2026-10-30）

| 评审意见 | 处理 |
|----------|------|
| 1. staging 清理按当前 workspace、10 分钟，会误删别的 workspace/草稿附件 | 决策 12 / §4.9：仅 engine 启动清、全局 sessions 索引判 `.data`/活锁、7 天，与 desktop `AttachmentStager.sweepStaging` 对齐；§7 补 `AttachmentStager.ts`/`main.ts` |
| 2. `tryAcquire` 返回 null 无法承载 busy/holder/local | 决策 10 / §4.7：改为 `AcquireResult { Acquired, Busy(holder, local) }`；§4.5 create 循环同步更新 |
| 3. `sessionDirName(basename, collision)` 算不出 hash | 决策 9 / §4.6：签名改为 `sessionDirName(basename, identity, collision)`；identity 优先 `workspace.id`，只读回退 canonical path |
| 4. `SessionTitle` 缺 MISSING | 决策 4 / §4.3：`SessionLoadState` 加 `MISSING`；`sessionTitle` 对已删除会话返回 `(null, MISSING)`，`SchedulesApi` 退回 sessionId |
| 5. 复制检测可能改写共享 `workspace.id` | §4.6：`lastPath` 存 canonical；canonical 相同视为同一 workspace，只更新 `lastPath`，不改写 id |
| 6. ACP `/resume` 先释放旧锁再 load，目标失败留空窗 | 决策 14 / §4.7：acquire target → load target → restore 成功后才 release current；短暂同时持两把锁 |
| 7. `AgentPersistence.deserialization` 绕过版本检查 | 决策 13 / §4.10：`SnapshotVersion.check` 加到 `AgentPersistence.deserialization`，`Node.load` 调用方也不能绕过 |
| 8. `ChannelBridge` 对 NEWER_VERSION 永久重试 | §4.3：NEWER_VERSION 提示升级、停止重试/不新建；BUSY 有限退避；其他错误失败本条消息 |
| 9. `TurnPersistence` 失败状态是静态 map | §4.4：状态挂 `InProcessAgentSession`，随 close 清理 |
| 次要：`owner.json` 原子写 | §4.6 注明由 `AtomicFiles` 写 |
| 次要：`retainOnly` 判活会创建 `.lock` | §4.8 注明 liveness 检查用不创建文件的 open，或接受几十字节残留 |
| 次要：`/save` 未列调用方 | §7 `AgentSessionRunnerCommandHandler` 补 `/save` |
| 次要：`session/close` 运行中语义 | §4.7 注明：save → close（强制中止当前轮并发出 cancelled 终止事件） |
| 次要：`AtomicFiles.writer` 静态钩子并行 | §4.1 注明测试用 `ThreadLocal` 或包级重载 |
| 次要：`Housekeeping` 无测试 | §8 增加 `HousekeepingTest` |

## 13. 实现记录（2026-10-30）

- P0 + P0.5 已实现：`AtomicFiles`、原子 session 写、两层 id 校验、`SessionLoads`/`SessionTitle`、`Agent.restore`、`TurnPersistence`、`session/save`、`/save`、id 后缀、`ChannelBridge` 错误码分支、非 session 存储原子写。
- P1 已实现：`WorkspaceDirs`（`workspace.id` + registry + `owner.json` + legacy 领养 + 复制检测）、`SessionLock`/`EngineLock`、engine/CLI/ACP 的会话锁、`session/close`、`requireSession` 透明 resume、30 分钟空闲驱逐、`Housekeeping`（tmp + 全局 staging）、`retainOnly`。
- 与文档的差异：engine lock 在 `EngineBootstrap` 构造期 fail-fast（`EngineAlreadyRunningException`，app-server 退出码 2），而不是进入 `initialize` 后返回业务码；ACP SDK 0.11 无 closeSession handler，锁在 ACP 进程退出时释放；ACP `/resume` 仍不切锁（现有 slash resume 不更新 `AcpSession.sessionId`）；desktop 在会话菜单提供 Close（调 `session/close`），标签/工作区切换仍主要依赖 30 分钟驱逐。
- 验证：`:core-ai:test`、`:core-ai-cli:test` 全绿；`SessionLockTest`、`WorkspaceDirsTest`、`HousekeepingTest` 新增；desktop `npm run check`/`build`/`test` 通过。
