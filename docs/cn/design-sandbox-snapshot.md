# Sandbox Snapshot 机制设计

## v1 实施范围（2026-07-06 收敛）

> 实施状态：v1 已实现（2026-07-06，实施计划见 `docs/superpowers/plans/2026-07-06-sandbox-snapshot-v1.md`；kill switch `sys.sandbox.snapshot.enabled` 默认关闭）。

本章是 v1 实施的**唯一优先依据**。下面第 1~20 章是完整版长期设计，继续保留；凡与本章冲突的地方（包括第 19 章旧的 v1 决策），v1 一律以本章为准，被裁剪的机制留给后续版本按需启用。

### 简化依据

复核现有实现后确认两个事实，使 v1 可以大幅裁剪：

1. **sandbox 所有权本来就是 pod 内存级的**。`SandboxManager.activeSandboxes` 是每个 server pod 各自的内存 Map，sandbox 归创建它的 pod 所有，idle 清理（`SandboxCleanupJob` → `cleanupExpired()`）也只清理本 pod 内存中的条目，跨 pod 只有 K8s 孤儿清理兜底。完整版设计中的 lease 机制想解决的"多 pod 争夺 session 执行权"问题，现状本来就不存在解决方案，snapshot 只需不使现状更糟。
2. **生产为 2 副本**，多 pod 竞态真实存在，但唯一需要防的是：pod A 上的旧 sandbox idle 超时后，把过期文件状态 capture 成"最新" snapshot，覆盖用户已在 pod B 上继续工作的成果。这个竞态用 `sandbox_epoch` 一个机制即可消灭，不需要 lease。

### 相对完整版的裁剪

| 完整版机制 | v1 决定 | 理由 |
|------|------|------|
| Lease（`owner_id` / `lease_token` / `lease_expires_at` / 续租） | **整体删除** | 现状即内存所有权，lease 解决的是当前不存在的问题 |
| 多代 generation + fallback 链（保 2 代） | **只保 1 代**：新 snapshot 置 AVAILABLE 后异步删除上一代 | 不兼容类失败多代同样失败；损坏包已被 sha256 + 两阶段可见挡住 |
| Mongo CAS dirty tracking | **pod 内存 boolean**：本 epoch 内执行过任意 sandbox 工具即视为 dirty | sandbox 是 pod 内存所有，内存标记天然正确 |
| restore 失败阻断工具调用 + 用户显式选择 | **原地重试 1 次，仍失败则发 warning 事件 + 空 sandbox 继续** | 语义本就是 best-effort，不为罕见路径新增交互；重试挡住瞬时网络抖动 |
| 前端改造（RESTORING 状态、选择 UI） | **零前端改动** | restore 完成后才发 `READY`；失败 warning 走现有事件渠道 |

### v1 保留的正确性底线

1. **`sandbox_epoch`**：每 session 一个 Mongo 计数文档，`ensureReady()` acquire 新 sandbox 时 `findAndModify` 自增。capture 开始前记录本 pod 持有的 epoch，置 AVAILABLE 时 CAS 校验 `epoch == 记录值`；若用户已在别的 pod 重建 sandbox（epoch 已被自增），过期 capture 的 CAS 失败，blob 作废丢弃。
2. **两阶段可见**：Blob 上传成功 ≠ 可用；Mongo 状态 `UPLOADING → AVAILABLE` CAS 成功后才可被 restore 选中。
3. **白名单 roots 打包 + sha256 校验 + `runtime_major` 兼容检查**（不匹配视为无 eligible snapshot，空 sandbox 继续并记 warning）。版本号内嵌在 runtime 二进制中并由 `/health` 上报、写入 manifest；`image_digest` 在三个 provider 上都没有现成获取通道且默认镜像是可变 tag `latest`，v1 仅记录 image tag 供排查，digest 校验留 v2。
4. **restore 成功（或确认无 eligible snapshot / 降级放弃）之后才发 `READY` 事件**——无感恢复的关键顺序约束。
5. **capture 无论成败，sandbox 都必须照常 release**，不泄漏资源。

### v1 组件与流程

Runtime（sandbox 镜像内，新增 2 个接口）：

- `POST /snapshot`：白名单 roots（`/tmp`、`/skill`、`/workspace`——后者 2026-07-06 起纳入）按排除规则打 tar.gz 流返回，附 manifest（文件数、字节数、sha256、`runtime_version`）。超过单包上限（压缩后 500MB 或 10000 个文件）直接报错拒绝，本次不产生 snapshot。
- `POST /snapshot/restore`：接收 tar.gz 流，校验 sha256；解包前逐条目做路径逃逸检查（拒绝 `..`、越出 roots 的绝对路径、指向 roots 之外的 symlink）后解至 roots。
- `GET /health` 扩展返回 `runtime_version`（编译期注入常量），server restore 前用它做 `runtime_major` 比对。

Server（新增 `SandboxSnapshotService` + 两个钩子）：

- release 钩子：capture 只挂在 `AgentSessionManager.closeSession()`（60 分钟 idle 清理与用户显式 close 共用此路径）调用的 `releaseSandbox(sessionId, capture=true)` 上，且本 epoch 内存 dirty 为 true 才触发：`capture` → Blob 上传 → Mongo CAS 置 AVAILABLE → 异步删除上一代（文档 + blob）→ release。其余 release 路径一律不 capture：AgentRunner / WorkflowRunner / OCG 的 per-run release（session 不会 resume）、`SandboxManager.cleanupExpired()` 兜底、`ensureReady()` 换车（REPLACING，旧 sandbox 已损坏）、`shutdown()`（部署场景，termination grace 内装不下大包上传——**部署仍丢文件是 v1 已知限制，capture-on-shutdown 列为 v2 候选**）。
- `LazySandbox.ensureReady()` 钩子：acquire → epoch 自增 → 查该 session 最新 AVAILABLE 且 `runtime_major` 匹配的 snapshot → 下载 → restore（失败原地重试 1 次）→ 再执行原有 postAcquireHook（pending 文件上传，保证用户离开期间新上传的文件覆盖 snapshot 中的旧同名副本）→ 成功或无 snapshot 才发 `READY`；两次失败 → 空 sandbox 继续，warning 文案放进最终 `READY` 事件的 message 字段（前端对 sandbox 事件是单 segment 替换渲染，单独发 warning 事件会立刻被随后的 READY 覆盖，用户看不见）。
- `ObjectStorageService` 需新增 `uploadObject` / `deleteObject` 两个方法（现接口只有 SAS 凭证生成 + download；Azure 实现沿用现有 SAS + JDK HttpClient 模式，SAS 权限加 write / delete）。

Mongo：

- `sandbox_snapshots`：`snapshot_id, session_id, user_id, epoch, status(UPLOADING/AVAILABLE/DELETED), blob_key, sha256, size, image, runtime_version, roots, created_at, expires_at(14 天)`（`image` 为 tag 仅供排查，兼容判断用 `runtime_version` 的 major）。
- epoch 计数文档：`{_id: session_id, epoch}`——用 session_id 做 `_id`，主键查询免建索引，migration 只需覆盖 `sandbox_snapshots`。
- ⚠️ 部署要求：新 collection 必须新增 index migration（`session_id + status + created_at`），dev 环境 notablescan 下缺索引会直接 500。

Blob：私有 container，key = `{user_id}/{session_id}/{snapshot_id}.tar.gz`；restore 时校验文档 `user_id` 与当前用户一致。

清理：

- session 删除：snapshot 文档标记 DELETED + 删 blob；blob 删除失败由 cleanup job 周期重试。
- `expires_at` 过期的文档与 blob 由 cleanup job 兜底删除，防孤儿。

运维开关：提供一个系统级 kill switch 配置（关闭后 capture/restore 全部跳过，行为退回当前空 sandbox 模式），保证线上出问题可以即时止血。

## 1. 背景

当前 Sandbox 的生命周期以节省资源为优先：会话创建时只挂 `LazySandbox`，第一次执行 shell、python、文件读写类工具时才真正创建 sandbox；会话空闲、关闭或 run 结束后会 release sandbox，避免长时间占用 Pod / warm pool / Docker 容器。

这个机制带来的问题是：sandbox runtime 的可写目录主要是 `/tmp` 和 `/skill`，它们来自 `emptyDir`、`tmpfs` 或容器临时文件系统。sandbox 一旦 release，里面的文件、已安装依赖、materialized skill 资源都会丢失。用户回到同一个 session 后，系统虽然可以无感创建一个新的 sandbox，但新 sandbox 没有继承旧 sandbox 的文件系统内容。

因此本设计不要承诺“完整 sandbox 连续性”。v1 的准确语义是：在同一 session 内，为 sandbox 的白名单可写目录提供 filesystem checkpoint。它可以恢复 `/tmp` 和 `/skill` 的文件树，但不会恢复进程、shell 当前目录、环境变量变更、后台任务、浏览器状态、socket、内存对象或 runtime 内部状态。

## 2. 目标与非目标

### 2.1 目标

- release 前 capture sandbox 白名单可写目录，v1 默认为 `/tmp` 和 `/skill`。
- release 后同一 session 继续执行 sandbox 工具时，自动创建新 sandbox，并在 `READY` 前 restore 最新 eligible AVAILABLE generation。
- snapshot 存储在 Azure Blob 的私有 container 中，Mongo 只保存 metadata 和 blob pointer。
- 对 Docker、Kubernetes Pod、AgentSandbox CRD / SandboxClaim warm pool 共用同一套 runtime API。
- 与用户 artifact 隔离：snapshot 是内部恢复数据，不是用户下载产物，不通过 public URL 暴露。
- 通过 session lease、sandbox epoch、snapshot generation 约束多 pod 并发、owner 转移和重复 capture。
- 支持配额、TTL、安全解包、显式失败策略、审计、清理和后续扩展。

### 2.2 非目标

- 不恢复进程状态、后台 async task、打开的 socket、浏览器会话、内存对象。
- 不恢复 shell 当前工作目录、server 侧工具调用上下文、环境变量运行时修改、`HOME` 指向外部目录的内容。
- 不保证正在运行的 shell/python 命令中途被迁移。
- 不做容器镜像层或 Kubernetes volume snapshot。
- v1 不做增量 snapshot；先做全量 archive。
- v1 不把 snapshot 做成用户可见 artifact。
- v1 不承诺跨 session、跨 user、跨 image digest 的可恢复性。

## 3. 总体方案

采用 runtime 文件系统 archive snapshot：

1. server 在 release sandbox 前调用 runtime `/snapshot`。
2. runtime 将白名单目录打成 `tar.gz` 流，并附带 manifest。
3. server 将压缩包写入 Azure Blob private container。
4. server 在 Mongo `sandbox_snapshots` 中记录 snapshot metadata。
5. 后续 `LazySandbox.ensureReady()` 创建新 sandbox 后，server 查找同一 session 最新 eligible AVAILABLE generation。
6. server 从 Azure Blob 下载 snapshot，调用新 runtime `/snapshot/restore` 恢复。
7. restore 成功或被明确跳过后才发 `READY` sandbox event，并执行原本的 tool call。

```
Idle release
  SandboxService.releaseSandbox(sessionId, IDLE)
    -> acquire/validate session lease
    -> SandboxSnapshotService.capture(...)
       -> SandboxClient.captureSnapshot()
          -> runtime POST /snapshot
       -> Azure Blob upload
       -> Mongo CAS generation AVAILABLE
    -> provider.release(sandbox)

User continues session
  ToolExecutor
    -> LazySandbox.ensureReady()
       -> provider.acquire(new sandbox, epoch++)
       -> dispatch RESTORING
       -> SandboxSnapshotService.restoreEligible(...)
          -> Azure Blob download
          -> runtime POST /snapshot/restore
       -> dispatch READY
    -> execute tool
```

### 3.1 语义边界

用户可感知语义：

- 如果同一 session 因 idle release 释放 sandbox，之后继续使用 sandbox 工具时，系统会尽力恢复上一次成功 checkpoint 的 `/tmp` 和 `/skill` 文件树。
- 如果最新 generation 不可恢复，系统最多 fallback 到上一代 eligible AVAILABLE generation。v1 建议每个 session 保留 2 代 AVAILABLE snapshot。
- 如果没有 eligible snapshot，或者 snapshot 与当前 runtime/image 不兼容，系统使用空 sandbox 继续，但必须记录可见 warning。
- 如果存在 eligible snapshot 但 restore 失败，默认不能静默用空 sandbox 继续。当前工具调用应失败并提示用户可以明确选择“丢弃旧 sandbox 文件，用空 sandbox 继续”。

不承诺的语义：

- 不承诺 `cd` 后的目录仍然有效；每次 shell/python 工具仍按工具自身默认 cwd 执行。
- 不承诺后台进程、async task、浏览器 tab、socket 或 in-memory notebook/kernel 延续。
- 不承诺 `/tmp` 以外的隐式缓存可恢复，除非该路径被列入 snapshot roots。
- 不承诺跨 image digest、跨 runtime major version、跨 user/session 的恢复。

### 3.2 状态机、Lease、Epoch 和 Generation

Snapshot 机制必须先定义状态机，否则 release、restore、dirty tracking、multi-pod ownership 很容易出现竞态。

核心标识：

| 标识 | 含义 |
|------|------|
| `session_id` | chat/agent session 标识，snapshot 的业务归属 |
| `user_id` | 权限边界，restore/delete 必须匹配 |
| `owner_id` | 当前持有 session 执行权的 server pod |
| `lease_token` | owner 获取 session lease 时生成的唯一 token |
| `lease_expires_at` | lease 过期时间，避免 pod 崩溃后永久占有 |
| `sandbox_epoch` | 同一 session 每次 acquire/recreate sandbox 都递增 |
| `snapshot_generation` | 同一 session 每次成功 capture 后递增 |
| `snapshot_id` | 单个 snapshot 文档和 blob 的唯一 id |

状态机：

```text
NO_SANDBOX
  -> ACQUIRING
  -> RESTORING
  -> READY
  -> CAPTURING
  -> RELEASED
  -> NO_SANDBOX

RESTORING
  -> RESTORE_FAILED
  -> RELEASED

CAPTURING
  -> CAPTURE_FAILED
  -> RELEASED
```

状态含义：

| 状态 | 含义 |
|------|------|
| `NO_SANDBOX` | session 只有 `LazySandbox` 或历史记录，没有实际 sandbox |
| `ACQUIRING` | provider 正在创建 Docker container / Pod / SandboxClaim |
| `RESTORING` | 已获得新 sandbox，正在选择并恢复 eligible snapshot |
| `READY` | sandbox 可执行工具，且 restore 已成功或已明确跳过 |
| `CAPTURING` | release 前正在生成和上传 snapshot |
| `RELEASED` | provider 资源已释放 |
| `RESTORE_FAILED` | eligible snapshot restore 失败，本次工具调用按策略中断或等待用户选择 |
| `CAPTURE_FAILED` | capture 失败，但 release 继续，后续只能使用旧 generation 或空 sandbox |

硬性不变量：

- 同一 `session_id + sandbox_epoch` 只能有一个有效 `owner_id + lease_token`。
- 只有当前 owner 且 lease 未过期时，才能执行 capture、restore、mark dirty、clear dirty、release。
- sandbox 进入 `READY` 前，restore 必须已经成功、无 eligible snapshot、明确不兼容跳过，或用户明确选择空 sandbox。
- tool call 只能发往当前 `sandbox_epoch` 且状态为 `READY` 的 sandbox。
- capture 只能在 sandbox quiescent、lease 仍有效、`sandbox_epoch` 未变化时开始。
- Blob 上传成功不等于 snapshot 可用；只有 Mongo CAS 将同一 `session_id + sandbox_epoch + lease_token` 的记录置为 `AVAILABLE` 后，generation 才可用于 restore。
- dirty flag 不能只依赖 `LazySandbox` 内存字段；内存 dirty 只能作为优化，权威状态必须带 `sandbox_epoch` 和 `snapshot_generation` 做 CAS。
- 只有当前 epoch 的 capture 成功变成 AVAILABLE 后，才能 clear dirty。
- release 必须最终发生；capture 失败不能导致 sandbox 长期占用资源。
- 用户删除 session 后，后续任何 restore 都必须被拒绝，并触发 snapshot metadata/blob 删除或清理重试。

状态转移建议：

| From | Event | To | 关键条件 |
|------|-------|----|----------|
| `NO_SANDBOX` | first sandbox tool | `ACQUIRING` | owner 持有 lease |
| `ACQUIRING` | provider acquired | `RESTORING` | `sandbox_epoch` 递增 |
| `RESTORING` | no eligible snapshot | `READY` | 记录 skipped reason |
| `RESTORING` | restore success | `READY` | 校验 sha256、manifest、roots、image digest |
| `RESTORING` | restore failed | `RESTORE_FAILED` | 默认中断工具调用 |
| `READY` | idle release with dirty | `CAPTURING` | runtime quiescent |
| `READY` | idle release without dirty | `RELEASED` | 无需 capture |
| `CAPTURING` | CAS AVAILABLE success | `RELEASED` | clear dirty for same epoch |
| `CAPTURING` | capture/upload/CAS failed | `CAPTURE_FAILED` | 记录 failure，不清 dirty generation |
| `CAPTURE_FAILED` | provider release | `RELEASED` | 后续 fallback old generation |

## 4. Snapshot 内容

### 4.1 默认保存目录

| 路径 | v1 策略 | 原因 |
|------|---------|------|
| `/tmp` | 保存 | 用户生成文件、脚本中间产物、`pip install --user` 依赖通常在这里 |
| `/skill` | 保存 | `ServerSkillTool` materialize 的 skill 和资源路径需要继续有效 |
| `/workspace` | 保存（2026-07-06 修订） | runtime 启动时创建该目录，且它是 bash/python 工具的默认 cwd——agent 相对路径产物落在这里，不保存则 resume 丢主要工作目录 |

> 2026-07-06 修订说明：原裁剪理由"Docker `/workspace` 是只读 bind"仅对本地 Docker provider 成立；K8s/AgentSandbox 环境该目录原本不存在，导致裸跑 bash 报 chdir 错误。现 runtime 启动时 `MkdirAll(/workspace)` 并将其纳入快照 roots。本地 Docker 的 bind 目录被打包由 500MB/10000 文件上限兜底。

注意：`/skill` 不是普通缓存目录。它来自 server 侧 skill materialization，restore 时必须确认 skill identity 与内容 hash 是否仍可接受，不能只靠路径存在就认为安全。

### 4.2 文件类型策略

| 类型 | 策略 |
|------|------|
| 普通文件 | 保存 |
| 目录 | 保存 |
| 安全相对 symlink | v1 建议跳过；v2 可保存相对且不逃逸的 symlink |
| 绝对 symlink | 不保存 |
| 指向 root 外的 symlink | 不保存 |
| hardlink | 不保存 |
| socket、FIFO、device file | 不保存 |
| 超大单文件 | 不保存并记录 warning，或直接使 capture 失败，取决于配置 |

### 4.3 默认排除项

```
/tmp/.X11-unix/**
/tmp/.ICE-unix/**
/tmp/core-ai-snapshot-*.tmp
**/*.sock
**/__pycache__/**
```

是否排除 pip cache 需要谨慎：`PIP_USER=1` 且 `HOME=/tmp` 时，用户安装的 Python 依赖可能落在 `/tmp/.local`。因此 v1 不应整体排除 `/tmp/.local`。

### 4.4 Archive 格式

建议使用 `tar.gz`，方便 Go runtime 流式打包/解包，server 无需理解文件树。

Archive 内部结构：

```
manifest.json
roots/tmp/...
roots/skill/...
```

manifest 示例：

```json
{
  "format": "core-ai-sandbox-snapshot/v1",
  "created_at": "2026-06-02T12:00:00Z",
  "session_id": "c1f...",
  "user_id_hash": "sha256:...",
  "snapshot_id": "7e2d1f4c",
  "snapshot_generation": 42,
  "sandbox_epoch": 7,
  "sandbox_id": "claim-abc123",
  "image": "chancetop/core-ai-sandbox-runtime:latest",
  "image_digest": "sha256:...",
  "runtime_version": "1.0.0",
  "runtime_major": 1,
  "roots": [
    {
      "name": "tmp",
      "target": "/tmp",
      "archive_prefix": "roots/tmp",
      "restore_mode": "overlay"
    },
    {
      "name": "skill",
      "target": "/skill",
      "archive_prefix": "roots/skill",
      "restore_mode": "overlay"
    }
  ],
  "skills": [
    {
      "name": "review-report",
      "version": "2026-06-02",
      "materialized_path": "/skill/review-report",
      "content_sha256": "sha256:...",
      "resource_sha256": "sha256:..."
    }
  ],
  "file_count": 148,
  "uncompressed_size": 73400320,
  "compressed_size": 20971520,
  "sha256": "..."
}
```

manifest 必须满足：

- `session_id`、`user_id_hash` 只用于校验和审计；restore 权限仍以 server 当前请求的 `user_id + session_id + lease_token` 为准。
- `image_digest` 是兼容性判断的主键，`image` tag 只用于排查。不要用可变 tag，例如 `latest`，作为恢复资格判断。
- `runtime_major` 不一致时默认不恢复。
- `roots` 必须完整列出 archive root 到 runtime target 的映射；runtime 只能恢复白名单 target。
- `skills` 记录已 materialize skill 的 name/version/hash。restore 时如果 skill hash 不再符合 server 当前 skill registry，v1 可以选择跳过 `/skill` restore 并重新 materialize，不能盲目信任旧目录。
- `sha256` 记录 archive body hash；server 下载后先校验 body hash，runtime 解包时再校验 manifest 和 entry 安全性。

## 5. Azure Blob 存储设计

### 5.1 不复用用户 artifact 存储

当前 `FileService` 会把文件内容 base64 存到 Mongo `file_records`，适合小型用户下载文件，不适合 sandbox snapshot。Snapshot 可能达到几十到几百 MB，也包含内部状态和潜在敏感内容，应单独使用 Azure Blob private container。

### 5.2 配置项

新增配置：

```properties
azure.blob.snapshot.container=core-ai-internal
azure.blob.snapshot.prefix=ai/sandbox-snapshots
azure.blob.snapshot.retention.days=7
azure.blob.snapshot.max.size.mb=256
azure.blob.snapshot.max.file.count=10000
azure.blob.snapshot.enabled=true
azure.blob.snapshot.restore.failure.mode=fail
azure.blob.snapshot.restore.image.policy=strict
azure.blob.snapshot.generations.retained=2
azure.blob.snapshot.operation.timeout.capture.seconds=30
azure.blob.snapshot.operation.timeout.restore.seconds=60
azure.blob.snapshot.encryption.enabled=false
```

复用已有：

```properties
azure.blob.account.name=...
azure.blob.account.key=...
```

`azure.blob.public.base.url` 不应用于 snapshot。

### 5.3 Blob key

推荐 key：

```
{prefix}/{env}/u={userHash}/s={sessionId}/g={generation}/{snapshotId}.tar.gz
```

示例：

```
ai/sandbox-snapshots/prod/u=9c1a2f/s=8f0b.../g=000042/7e2d1f4c.tar.gz
```

如果开启应用层加密：

```
ai/sandbox-snapshots/prod/u=9c1a2f/s=8f0b.../g=000042/7e2d1f4c.tar.gz.enc
```

说明：

- `userHash` 使用 userId 的 sha256 前缀，不使用 email 或其他 PII。
- `sessionId` 可保留完整 UUID，方便排查。
- `generation` 单调递增，便于按时间排序和 fallback。
- `snapshotId` 防止重名。

### 5.4 Blob metadata / tags

metadata：

```
contentType=application/gzip
snapshotFormat=core-ai-sandbox-snapshot/v1
sessionId=<sessionId>
snapshotId=<snapshotId>
generation=<generation>
imageDigest=<sha256>
createdAt=<iso-time>
sha256=<hash>
```

tags：

```
component=sandbox-snapshot
env=prod
status=available
expiresOn=2026-06-09
```

如果 Azure lifecycle policy 支持按 tag 删除，则用 `expiresOn` 或 `component=sandbox-snapshot` 做自动清理。否则由 server cleanup job 删除过期 blob。

### 5.5 删除与生命周期

Snapshot 是用户私有数据，删除语义必须明确：

- 用户显式删除 session 时，server 立即把对应 metadata 标为 `DELETING` 或 `DELETED`，并尝试删除 Blob。
- Blob 删除失败时不能丢 metadata，需要保留 tombstone，由 cleanup job 重试。
- 目标删除 SLA：用户删除后 24 小时内完成 Blob hard delete；失败需要报警或进入人工处理队列。
- TTL 过期删除和“每 session 保留 2 代”删除都应通过同一套 cleanup job，避免 Mongo 文档被 TTL 删除后 Blob 变成 orphan。
- Azure lifecycle policy 可以作为兜底，但不能替代业务侧 delete session 语义。

### 5.6 Orphan 与审计

需要两类周期任务：

- `SandboxSnapshotCleanupJob`：删除 expired、DELETED、超过 retained generation 的 snapshot，并重试失败 Blob delete。
- `SandboxSnapshotReconciliationJob`：扫描 Blob prefix 与 Mongo metadata，发现 Mongo missing 的 Blob orphan、Blob missing 的 AVAILABLE metadata、sha256 不匹配或长期 CAPTURING/DELETING 卡住的记录。

审计事件至少包括：

- capture started/completed/failed/skipped。
- restore selected/started/completed/failed/skipped。
- delete requested/completed/failed。
- restore authorization denied。
- fallback 到上一代或空 sandbox。

### 5.7 Blob 访问方式

新增 server-side blob client，不走浏览器 SAS：

```java
public interface SandboxSnapshotBlobStore {
    StoredSnapshot put(String blobName, Path file, SnapshotBlobMetadata metadata);
    Path get(String blobName);
    void delete(String blobName);
}
```

实现可选：

- v1 简单实现：使用 Azure Blob REST API + account key 签名，server 上传/下载。
- 后续实现：接入 Azure SDK。

不向前端返回 snapshot SAS，不提供 public URL。

上传/下载不能走完整 byte array：

- runtime capture body 写入 server 临时文件，边写边计算 sha256 和 compressed size。
- server 上传 Blob 时使用文件流或 bounded stream。
- restore 时从 Blob 下载到临时文件，校验 sha256 后再交给 runtime。
- 临时目录应可配置，例如 `${java.io.tmpdir}/core-ai-sandbox-snapshot`。
- 临时文件要有最大磁盘占用和清理逻辑，避免多个并发 restore 把 server pod 临时盘打满。

## 6. Mongo 元数据模型

新增 domain：

```java
@Collection(name = "sandbox_snapshots")
public class SandboxSnapshot {
    @Id
    public String id;

    @Field(name = "session_id")
    public String sessionId;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "generation")
    public Long generation;

    @Field(name = "sandbox_epoch")
    public Long sandboxEpoch;

    @Field(name = "owner_id")
    public String ownerId;

    @Field(name = "lease_token")
    public String leaseToken;

    @Field(name = "lease_expires_at")
    public ZonedDateTime leaseExpiresAt;

    @Field(name = "status")
    public SandboxSnapshotStatus status;

    @Field(name = "container")
    public String container;

    @Field(name = "blob_name")
    public String blobName;

    @Field(name = "content_type")
    public String contentType;

    @Field(name = "compression")
    public String compression;

    @Field(name = "encrypted")
    public Boolean encrypted;

    @Field(name = "sha256")
    public String sha256;

    @Field(name = "compressed_size")
    public Long compressedSize;

    @Field(name = "uncompressed_size")
    public Long uncompressedSize;

    @Field(name = "file_count")
    public Integer fileCount;

    @Field(name = "roots")
    public List<String> roots;

    @Field(name = "image")
    public String image;

    @Field(name = "image_digest")
    public String imageDigest;

    @Field(name = "runtime_version")
    public String runtimeVersion;

    @Field(name = "runtime_major")
    public Integer runtimeMajor;

    @Field(name = "sandbox_id")
    public String sandboxId;

    @Field(name = "capture_reason")
    public SandboxSnapshotReason captureReason;

    @Field(name = "restore_attempts")
    public Integer restoreAttempts;

    @Field(name = "last_restore_failed_at")
    public ZonedDateTime lastRestoreFailedAt;

    @Field(name = "last_restore_error")
    public String lastRestoreError;

    @Field(name = "skill_manifest_hash")
    public String skillManifestHash;

    @Field(name = "deleted_at")
    public ZonedDateTime deletedAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Field(name = "restored_at")
    public ZonedDateTime restoredAt;

    @Field(name = "error_message")
    public String errorMessage;
}
```

Status：

```java
public enum SandboxSnapshotStatus {
    CAPTURING,
    AVAILABLE,
    FAILED,
    RESTORE_FAILED,
    DELETING,
    DELETED
}
```

Reason：

```java
public enum SandboxSnapshotReason {
    IDLE_RELEASE,
    SERVER_SHUTDOWN,
    CHECKPOINT,
    ERROR_REPLACE
}
```

索引：

```text
sandbox_snapshots(session_id, status, generation desc)
sandbox_snapshots(session_id, generation) unique
sandbox_snapshots(user_id, session_id, status, generation desc)
sandbox_snapshots(user_id, created_at desc)
sandbox_snapshots(expires_at) TTL
sandbox_snapshots(blob_name) unique sparse
sandbox_snapshots(status, updated_at)
```

TTL 只会删除 Mongo 文档，不一定删除 Blob。因此还需要 Blob lifecycle policy 或 `SandboxSnapshotCleanupJob`。

关键写入约束：

- capture 开始时插入 `CAPTURING`，generation 通过 Mongo 原子计数或 session state CAS 分配。
- 上传 Blob 成功后，用 `session_id + generation + sandbox_epoch + owner_id + lease_token + status=CAPTURING` 做 CAS 更新为 `AVAILABLE`。
- CAS 失败时必须删除已上传 Blob 或记录 orphan cleanup 任务，不能把它当可用 snapshot。
- restore 失败不能直接删除 snapshot；先记录 `RESTORE_FAILED` 信息，fallback 到上一代 eligible snapshot。连续失败超过阈值后再从 eligible 集合中排除。
- `DELETING/DELETED` 是用户删除和 TTL cleanup 的 tombstone 状态，防止 Mongo TTL 先删文档导致 Blob orphan。

## 7. Runtime API 设计

### 7.1 Capture

Endpoint：

```
POST /snapshot
```

Request：

```json
{
  "format": "core-ai-sandbox-snapshot/v1",
  "session_id": "c1f...",
  "snapshot_id": "7e2d1f4c",
  "snapshot_generation": 42,
  "sandbox_epoch": 7,
  "image_digest": "sha256:...",
  "roots": ["/tmp", "/skill"],
  "max_bytes": 268435456,
  "max_file_count": 10000,
  "excludes": [
    "/tmp/.X11-unix/**",
    "/tmp/.ICE-unix/**",
    "**/*.sock",
    "**/__pycache__/**"
  ],
  "skill_manifest": [
    {
      "name": "review-report",
      "version": "2026-06-02",
      "content_sha256": "sha256:...",
      "resource_sha256": "sha256:..."
    }
  ],
  "require_quiescent": true
}
```

Response：

```text
200 OK
Content-Type: application/gzip
X-Snapshot-Format: core-ai-sandbox-snapshot/v1
X-Snapshot-File-Count: 148
X-Snapshot-Uncompressed-Size: 73400320
X-Snapshot-Compressed-Size: 20971520
X-Snapshot-Sha256: ...
```

Body 是 `tar.gz`。

失败：

```text
409 active async task exists
413 snapshot exceeds max size
422 unsupported root
500 runtime error
```

### 7.2 Restore

Endpoint：

```
POST /snapshot/restore
```

Request headers：

```text
Content-Type: application/gzip
X-Snapshot-Format: core-ai-sandbox-snapshot/v1
X-Snapshot-Sha256: ...
X-Snapshot-Generation: 42
X-Sandbox-Epoch: 8
```

Body 是 `tar.gz`。

Response：

```json
{
  "status": "completed",
  "snapshot_generation": 42,
  "file_count": 148,
  "restored_roots": ["/tmp", "/skill"],
  "skipped_roots": [],
  "duration_ms": 1234
}
```

失败：

```text
400 invalid archive
413 archive exceeds max size
422 archive entry escapes allowed root
500 runtime error
```

### 7.3 Runtime 安全规则

Capture：

- 只允许白名单 root：`/tmp`、`/skill`。
- 跳过 socket、FIFO、device file。
- 统计 uncompressed size 和 file count，超过限制立即失败。
- archive entry 必须使用相对路径。
- manifest 必须写入 archive 第一层。

Restore：

- `/snapshot/restore` 只允许在新 sandbox 进入 `READY` 前调用。已经对外 READY 且可能执行过工具的 sandbox，不允许 restore。
- 只允许恢复到 manifest 声明的白名单 root。
- 禁止 `..`、绝对路径、路径 clean 后逃逸。
- 禁止创建 device、FIFO、socket。
- 禁止 hardlink。
- v1 跳过 symlink 或仅允许不逃逸的相对 symlink。
- restore 目标必须是新 sandbox；正常情况下 `/tmp` 和 `/skill` 应接近空目录。
- restore 使用 staging 目录，例如 `/tmp/core-ai-snapshot-restore-<id>`。先完整解包和校验，再按 root merge 到目标目录。
- 文件冲突策略：v1 overlay overwrite snapshot 内同路径文件；runtime 自己创建且不在 snapshot 内的文件不删除。
- 权限策略：普通文件收敛到 `0644`，可执行文件可恢复为 `0755`，目录为 `0755`；不恢复 owner/group。
- restore 部分失败时必须清理 staging，并返回失败；sandbox 不进入 `READY`。
- 解包时持续统计 uncompressed size 和 file count，超过配置立即失败。

## 8. Server 组件设计

### 8.1 `SandboxSnapshotService`

职责：

- 判断是否需要 capture。
- 调用 sandbox runtime capture。
- 上传 Azure Blob。
- 写 Mongo metadata。
- 查找最新 eligible AVAILABLE generation。
- 下载 Azure Blob 并 restore 到新 sandbox。
- 记录失败和降级信息。

接口草案：

```java
public class SandboxSnapshotService {
    public Optional<SandboxSnapshot> captureBeforeRelease(
        String sessionId,
        String userId,
        Sandbox sandbox,
        SnapshotLeaseContext leaseContext,
        SandboxSnapshotReason reason
    );

    public SnapshotRestoreDecision restoreEligible(
        String sessionId,
        String userId,
        Sandbox sandbox,
        SnapshotLeaseContext leaseContext,
        SandboxConfig config
    );

    public void deleteSessionSnapshots(String sessionId, String userId);
}
```

`SnapshotLeaseContext` 至少包含：

```java
public record SnapshotLeaseContext(
    String ownerId,
    String leaseToken,
    Instant leaseExpiresAt,
    long sandboxEpoch,
    long currentSnapshotGeneration
) { }
```

`SnapshotRestoreDecision` 用于区分：

- restored generation。
- no eligible snapshot。
- skipped because incompatible。
- failed and blocked current tool call。
- user explicitly continued with empty sandbox。

### 8.2 `SandboxClient`

新增方法：

```java
public SnapshotCaptureResult captureSnapshot(SnapshotCaptureRequest request);

public SnapshotRestoreResult restoreSnapshot(Path archive, String expectedSha256);
```

`SnapshotCaptureResult` 建议包含临时文件路径和 headers：

```java
public record SnapshotCaptureResult(
    Path archivePath,
    String sha256,
    long compressedSize,
    long uncompressedSize,
    int fileCount,
    String runtimeVersion,
    int runtimeMajor,
    String imageDigest
) { }
```

Capture 不建议把完整 archive 放入 byte array，应使用临时文件或 streaming，避免 server 内存峰值。

### 8.3 `Sandbox` 接口

可以选择扩展现有接口：

```java
default SnapshotCaptureResult captureSnapshot(SnapshotCaptureRequest request) {
    throw new UnsupportedOperationException("captureSnapshot not supported");
}

default SnapshotRestoreResult restoreSnapshot(Path archive, String expectedSha256) {
    throw new UnsupportedOperationException("restoreSnapshot not supported");
}
```

DockerSandbox、KubernetesSandbox、AgentSandbox 都委托给 `SandboxClient`。

### 8.4 Dirty tracking

为避免每次 idle release 都上传相同内容，需要 dirty tracking。但 dirty 不能只放在 `LazySandbox` 内存字段中，否则 server pod 切换、session rebuild、capture CAS 失败都会产生错误判断。

Dirty 规则：

| 操作 | dirty |
|------|-------|
| `run_bash_command` | true |
| `run_python_script` | true |
| `write_file` | true |
| `edit_file` | true |
| `use_skill` materialize 成功 | true |
| `read_file` | false |
| `glob_file` | false |
| `grep_file` | false |
| `submit_artifacts` | false |

实现建议：

- `LazySandbox` 维护内存 dirty 字段，作为本 pod 内快速判断。
- session state 或 Mongo 中维护权威 dirty state，字段至少包括 `session_id`、`sandbox_epoch`、`dirty_generation`、`last_mutation_at`。
- `execute()` 在写类工具完成后，用当前 `sandbox_epoch + lease_token` CAS 标 dirty。
- `materializeSkill()` 成功后，用当前 `sandbox_epoch + lease_token` CAS 标 dirty，并更新 `skill_manifest_hash`。
- capture 成功并且 Mongo CAS 将 snapshot 标为 `AVAILABLE` 后，才能用同一 `sandbox_epoch + lease_token + generation` 清 dirty。
- capture 失败、upload 成功但 CAS 失败、owner 变更、epoch 变化时，都不能清 dirty。

为了保守，`run_bash_command` 和 `run_python_script` 无论 exit status 是否非零，只要执行过就标 dirty，因为命令可能已经写文件。

### 8.5 Release reason

新增：

```java
public enum SandboxReleaseReason {
    IDLE,
    SESSION_CLOSE,
    USER_DELETE_SESSION,
    AGENT_RUN_COMPLETE,
    SERVER_SHUTDOWN,
    ERROR_REPLACE
}
```

Capture 策略：

| Release reason | capture? | 说明 |
|----------------|----------|------|
| `IDLE` | 是 | v1 核心场景，用户稍后继续 session |
| `SESSION_CLOSE` | v1 否 | 如果只是前端切走不应 close；真正 close 的语义需要先厘清 |
| `USER_DELETE_SESSION` | 否，删除已有 snapshot | 用户删除 session 时不应保留内部状态 |
| `AGENT_RUN_COMPLETE` | 默认否 | run 是一次性任务，artifact 另有机制 |
| `SERVER_SHUTDOWN` | v1 否 | shutdown 路径时间不可控，后续可做 best-effort |
| `ERROR_REPLACE` | v1 否 | 错误场景优先释放资源，使用上次 AVAILABLE generation |

v1 推荐只接 `IDLE` release capture。这样可以先把一致性、安全和恢复语义做正确，再扩展到 checkpoint、server shutdown 或 error replace。

## 9. 生命周期集成

### 9.1 Session 创建

Session 创建时不 restore。仍然只创建 `LazySandbox`，保持当前懒加载成本模型。

### 9.2 首次工具调用

如果 session 从未创建过 sandbox，流程不变：创建新 sandbox，没有 snapshot 则直接执行。

### 9.3 Idle release

`IdleSessionCleanupJob` 是 v1 capture 的主要入口。它不应该把 idle 等同于用户删除 session，而应触发 `releaseSandbox(sessionId, IDLE)`：

```
if current pod owns session lease:
    if sandbox exists and sandbox.actualized:
        if dirty for current sandbox_epoch:
            capture snapshot with lease/epoch CAS
        release sandbox
else:
    skip; current owner handles idle release
```

注意：如果当前 session 只是 idle，但 InProcessAgentSession 仍有可恢复的 message history，snapshot 只负责 runtime 文件系统，不负责 chat history。

capture 失败时：

- 记录 `CAPTURE_FAILED` event、metrics 和 Mongo failure metadata。
- sandbox 仍然 release，避免资源泄漏。
- 不清 dirty generation；后续 restore 只能使用上一个 AVAILABLE generation 或空 sandbox。

### 9.4 用户继续 session

当用户发新消息，session rebuild 可能先恢复 agent history。真正需要 sandbox 时：

```
LazySandbox.ensureReady()
  acquire sandbox
  increment sandbox_epoch
  select latest eligible AVAILABLE generation
  restore selected snapshot
  dispatch READY
```

restore 必须在 `READY` event 之前完成，避免前端显示 ready 但文件尚未恢复。

restore selection 不能写成“latest snapshot”，必须按以下条件筛选：

```text
user_id = current user
session_id = current session
status = AVAILABLE
not deleted
expires_at > now
format = core-ai-sandbox-snapshot/v1
image_digest = current sandbox image digest
runtime_major = current runtime major
roots compatible with configured roots
skill manifest eligible or skill root can be skipped/re-materialized
```

排序：

```text
generation desc, created_at desc
```

fallback：

```text
try generation N
  -> restore failed
     -> record restore failure for N
     -> try generation N-1 if eligible
        -> restore failed or none
           -> block tool call by default
```

只有“没有 eligible snapshot”或“明确不兼容跳过”的情况，才可以用空 sandbox 继续并记录 warning。存在 eligible snapshot 但 restore 失败时，默认必须中断当前工具调用。

### 9.5 Session 删除

用户显式删除 session：

1. release sandbox，不 capture。
2. Mongo snapshot metadata 标记 `DELETING` / `DELETED`，保留 tombstone。
3. 删除对应 Azure Blob。
4. 后续同一 `user_id + session_id` 的 restore 请求必须拒绝。

如果 Blob 删除失败，保留 metadata 并由 cleanup job 重试。

## 10. 前端与事件

成功恢复路径下，v1 可以不改前端，仅在后端 restore 完成后发 `READY`。但 restore 失败默认阻断工具调用，需要通过现有 chat/tool error 或新增事件让用户知道原因。为了可观察性，建议扩展 `SandboxEventType`：

```java
SNAPSHOTTING,
SNAPSHOT_FAILED,
RESTORING,
RESTORE_FAILED
```

事件语义：

| 事件 | 时机 |
|------|------|
| `SNAPSHOTTING` | idle release 前开始 capture |
| `SNAPSHOT_FAILED` | capture 失败但仍 release |
| `RESTORING` | provider acquire 新 sandbox 后、`READY` event 前 |
| `RESTORE_FAILED` | eligible snapshot restore 失败，当前工具调用默认中断 |
| `READY` | sandbox 已可用，且 restore 已完成或已明确跳过 |

RESTORE_FAILED 是否中断用户请求建议做成配置：

```properties
azure.blob.snapshot.restore.failure.mode=fail
```

可选值：

- `fail`：本次工具调用失败，要求用户确认是否用空 sandbox 继续。
- `warn`：继续执行工具，但给用户/session 写 warning。仅用于 dev 或显式降级环境，不作为 prod 默认值。

推荐 v1 使用 `fail`。原因是 restore 失败代表系统本来应该恢复的文件树没有恢复，继续执行 shell/python 可能让用户误以为依赖、临时文件或 skill 资源仍然存在。

可以自动 warning 并继续的情况只有：

- 没有任何 eligible snapshot。
- snapshot 因 image digest / runtime major / root capability 不兼容被明确跳过。
- 用户已经在本 session 内明确选择丢弃旧 sandbox 文件，用空 sandbox 继续。

## 11. 安全与隔离

### 11.1 Blob 隔离

- snapshot container 必须 private。
- 不生成前端 SAS。
- 不使用 `azure.blob.public.base.url`。
- Blob key 不包含 email、agent name、文件名等可读 PII。
- Blob metadata 不放 env var、prompt、工具参数。

### 11.2 Restore 授权

restore 前必须在 server 侧完成授权：

- 当前请求 user 必须拥有 `session_id`。
- snapshot metadata 的 `user_id + session_id` 必须匹配当前请求。
- 当前 server pod 必须持有有效 `owner_id + lease_token`。
- 当前 `sandbox_epoch` 必须是新 acquire 的 epoch，且 sandbox 尚未 `READY`。
- snapshot 不能处于 `DELETING`、`DELETED`、expired 或 restore ban 状态。

runtime 不应直接信任 archive 内的 `session_id` 或 `user_id_hash`。这些字段只用于 defense-in-depth 校验，业务授权以 server 的 Mongo/session ownership 判断为准。

### 11.3 内容敏感性

Snapshot 可能包含：

- 用户生成的报告、代码、数据文件。
- 脚本写入的临时 token。
- Python/npm 安装目录。
- materialized skill 内容和资源。

因此 snapshot 应被视为用户私有数据，权限边界至少等同 chat session。capture、restore、delete、fallback 都应写 audit log，并保留可按 user/session/snapshotId 查询的操作记录。

### 11.4 加密

Azure Storage 默认有服务端加密。是否需要应用层加密取决于合规要求。

如果做应用层加密：

- server 生成随机 data key。
- data key 用 KMS 或 server master key 包装。
- Blob 存 `.enc`。
- Mongo 存 `encrypted=true`、`key_id`、`nonce`、`wrapped_key`。
- sha256 建议同时记录 plaintext hash 和 ciphertext hash，restore 时校验 ciphertext，再解密校验 plaintext。

v1 可以先不做应用层加密，但要满足 private container、无前端 SAS、审计、删除 SLA、Blob key 不含 PII。若后续合规要求更高，再启用应用层 envelope encryption。

### 11.5 Restore 防逃逸

restore 是最敏感路径，必须在 runtime 内严格验证：

- entry path clean 后不能逃逸 target root。
- 禁止 hardlink。
- 禁止 absolute path。
- 禁止 `../`。
- 禁止 device、FIFO、socket。
- symlink 默认不恢复。
- 文件权限收敛到 `0644`，可执行扩展恢复为 `0755`。

## 12. 配额与性能

### 12.1 默认配额

| 项 | 默认值 |
|----|--------|
| compressed size | 256 MB |
| uncompressed size | 1 GB |
| file count | 10000 |
| capture timeout | 30 s |
| restore timeout | 60 s |
| snapshots per session | 2 |
| retention | 7 days |
| server temp disk per operation | 512 MB |
| concurrent capture/restore per pod | configurable, default 2 |

### 12.2 超限策略

如果 snapshot 超限：

1. capture 返回失败。
2. server 记录 `FAILED` metadata 和 error message。
3. sandbox 仍然 release，避免资源泄漏。
4. 下次恢复时使用更早的 eligible AVAILABLE snapshot；如果没有，则用空 sandbox 并给 warning。
5. 给 session 增加 warning，让用户知道临时文件没有被完整保留。

如果 restore 下载或解压超限：

1. 标记该 generation 的 restore failure。
2. fallback 到上一代 eligible AVAILABLE snapshot。
3. 如果没有可恢复 generation，按 `restore.failure.mode` 处理；prod 默认 fail。

### 12.3 临时文件与背压

Snapshot 流程会在三个位置产生磁盘压力：

- runtime capture staging / tar 流。
- server capture 临时 archive。
- server restore 下载临时 archive，以及 runtime restore staging。

v1 要求：

- server 不使用 byte array 保存 archive。
- 每个 pod 维护 capture/restore semaphore，避免并发打满临时盘。
- capture 和 restore 前检查可用磁盘空间，不满足时返回 quota failure。
- 临时文件名称必须带 snapshot id，且被 exclude pattern 排除，避免 capture 自己的临时文件。
- 进程重启后 cleanup job 清理过期临时文件。
- metrics 记录 temp disk usage、quota rejection、operation duration、compressed/uncompressed bytes。

### 12.4 大文件优化

v1 全量 archive 可以先接受。后续优化：

- 按文件 mtime/size 做增量 manifest。
- 对大文件单独 Blob 分片，manifest 记录引用。
- 周期 checkpoint 时只上传 dirty 文件。
- 多 snapshot generation 共用 content-addressed blob。

## 13. 一致性与并发

### 13.1 Capture quiescent

release 前 capture 通常发生在无活跃 tool call 时。runtime 仍应提供基本保护：

- runtime 维护 active execution 计数。
- `/snapshot` 如果 `require_quiescent=true` 且有 active execution，返回 409。
- server 可重试短时间，仍不成功则跳过 capture。

### 13.2 Async task

如果 runtime 有 pending async task：

- v1 不保存 async task。
- capture 默认返回 409。
- server 对 idle release 可以选择延迟 release，或 release 并记录 snapshot skipped。

推荐 v1：pending async task 时不 capture，并延迟一次 idle release；超过最大 idle 容忍时间后强制 release。

### 13.3 多 server pod

`SessionOwnershipRegistry` 已用于 session ownership。Snapshot capture/restore 应只由 session owner 执行，但仅“检查 owner”还不够，还需要 lease token、epoch 和 Mongo CAS。

Mongo metadata 写入需要防重复：

- capture 创建 `CAPTURING` 记录。
- 上传成功后 CAS 更新为 `AVAILABLE`。
- 同一 session 同一 generation 只允许一个 AVAILABLE。
- 如果 owner 变化、lease 过期或 sandbox epoch 改变，CAS 必须失败。

推荐流程：

```text
captureBeforeRelease(sessionId, epoch E, lease L)
  assert owner(sessionId) == this pod
  assert leaseToken == L and not expired
  assert sandboxEpoch == E
  insert CAPTURING generation G
  runtime /snapshot
  upload blob
  update where sessionId, generation G, epoch E, lease L, status CAPTURING
      set status AVAILABLE
  if update count == 0:
      delete blob or mark orphan cleanup
      do not clear dirty
```

restore 流程：

```text
restoreEligible(sessionId, epoch E, lease L)
  assert owner(sessionId) == this pod
  assert leaseToken == L and not expired
  assert sandboxEpoch == E
  query eligible AVAILABLE generations desc
  for each candidate:
      mark restore attempt
      download + sha256 check
      runtime /snapshot/restore
      if success: return restored
      record restore failure and try previous
  return failed/no eligible decision
```

owner 变化时：

- 新 owner 可以 acquire 新 sandbox，并递增 `sandbox_epoch`。
- 老 owner 的 capture/restore CAS 因 lease 或 epoch 不匹配失败。
- 老 owner 不得把旧 sandbox 发 `READY`，也不能 clear dirty。

## 14. 失败处理

| 场景 | 处理 |
|------|------|
| runtime capture 失败 | 记录 FAILED，release 继续 |
| Azure upload 失败 | 删除临时 archive，记录 FAILED，release 继续 |
| Mongo CAS 写入失败 | 尝试删除已上传 Blob 或登记 orphan cleanup，release 继续，不清 dirty |
| release 前 sandbox 已不可连 | 跳过 capture，使用旧 snapshot |
| 没有 eligible snapshot | 空 sandbox 继续，记录 warning |
| image digest/runtime major mismatch | 跳过该 snapshot，继续查找上一代 eligible generation |
| restore 下载失败 | 记录 restore failure，尝试上一代 eligible generation |
| restore 校验失败 | 记录 restore failure，尝试上一代 eligible generation |
| 所有 eligible generation restore 失败 | 发 RESTORE_FAILED，prod 默认中断当前工具调用 |
| 用户选择空 sandbox 继续 | 记录 explicit discard decision，本次后可空 sandbox 继续 |
| user delete session 与 restore 并发 | delete 优先，restore 被拒绝 |

推荐 restore fallback：

```
latest eligible AVAILABLE generation
  -> restore failed
     -> mark failed_for_restore
     -> try previous eligible AVAILABLE generation
        -> none
           -> RESTORE_FAILED
              -> user explicitly chooses empty sandbox
                 -> empty sandbox + warning
```

## 15. 兼容性策略

Snapshot metadata 记录 `image`、`image_digest`、`runtime_version` 和 `runtime_major`。

默认策略：

- 同 image digest：允许 restore。
- 不同 image digest：默认跳过。
- runtime major version 不同：跳过。
- runtime minor version 不同：best-effort restore。

配置：

```properties
azure.blob.snapshot.restore.image.policy=strict
```

可选值：

- `strict`：image digest 不同不恢复。
- `warn`：image digest 不同仍恢复，但记录 warning。仅用于 dev 或临时排障。
- `ignore`：不检查 image digest。prod 不推荐。

推荐 v1 使用 `strict`。

`/skill` 兼容性策略：

- skill manifest hash 完全匹配：允许恢复 `/skill`。
- skill 版本存在但 hash 不匹配：跳过 `/skill` restore，并由 server 按当前 registry 重新 materialize。
- skill 已不存在或用户无权访问：跳过对应 skill root，记录 warning。
- `/tmp` 可在 image/runtime 兼容时单独恢复，但 `/skill` 不应在 hash 不匹配时盲目恢复。

### 15.1 生产运维要求

Kill switch：

```properties
azure.blob.snapshot.enabled=false
azure.blob.snapshot.capture.enabled=false
azure.blob.snapshot.restore.enabled=false
azure.blob.snapshot.provider.allowlist=docker,kubernetes,agentsandbox
```

Metrics：

- `sandbox_snapshot_capture_total{result,reason,provider}`。
- `sandbox_snapshot_restore_total{result,provider}`。
- `sandbox_snapshot_bytes{type=compressed|uncompressed}`。
- `sandbox_snapshot_duration_seconds{operation=capture|restore}`。
- `sandbox_snapshot_fallback_total{reason}`。
- `sandbox_snapshot_no_eligible_total{reason}`。
- `sandbox_snapshot_lease_conflict_total`。
- `sandbox_snapshot_orphan_blob_total`。
- `sandbox_snapshot_temp_disk_reject_total`。
- `sandbox_snapshot_delete_retry_total`。

Canary restore：

- 周期性抽样最近 AVAILABLE snapshot。
- 下载并校验 sha256。
- 在临时 sandbox 或 runtime temp dir 执行 restore dry-run。
- 不进入真实用户 session，不发送 READY。
- 失败率超过阈值时报警，并可自动关闭 restore。

Runbook 应覆盖：

- restore failure 暴增。
- Blob upload 失败。
- Mongo CAS conflict 暴增。
- orphan blob 增长。
- temp disk quota rejection。
- delete SLA 逾期。
- image digest rollout 导致大量 no eligible snapshot。

## 16. 需要修改的代码位置

### 16.1 Runtime

文件：

```
core-ai-sandbox-runtime/main.go
```

新增：

- `SnapshotRequest`
- `SnapshotRestoreResponse`
- `handleSnapshotCapture`
- `handleSnapshotRestore`
- `archiveRoots`
- `restoreArchive`
- path/root validation helpers
- active execution tracking

### 16.2 Core abstraction

文件：

```
core-ai/src/main/java/ai/core/sandbox/Sandbox.java
core-ai/src/main/java/ai/core/sandbox/SandboxConstants.java
```

新增：

- snapshot capability default methods。
- snapshot 配额默认值。

### 16.3 Server sandbox

文件：

```
core-ai-server/src/main/java/ai/core/server/sandbox/SandboxClient.java
core-ai-server/src/main/java/ai/core/server/sandbox/LazySandbox.java
core-ai-server/src/main/java/ai/core/server/sandbox/SandboxService.java
core-ai-server/src/main/java/ai/core/server/sandbox/SandboxManager.java
```

新增：

- capture/restore HTTP client。
- dirty tracking。
- release reason。
- `LazySandbox.ensureReady()` restore hook。
- capture before release hook。

### 16.4 Provider

文件：

```
core-ai-server/src/main/java/ai/core/server/sandbox/docker/DockerSandbox.java
core-ai-server/src/main/java/ai/core/server/sandbox/kubernetes/KubernetesSandbox.java
core-ai-server/src/main/java/ai/core/server/sandbox/agentsandbox/AgentSandbox.java
```

新增：

- snapshot default methods 委托 `SandboxClient`。

### 16.5 Snapshot service / Blob store

新增包：

```
core-ai-server/src/main/java/ai/core/server/sandbox/snapshot/
```

建议文件：

```
SandboxSnapshotService.java
SandboxSnapshotBlobStore.java
AzureSandboxSnapshotBlobStore.java
SandboxSnapshotConfig.java
SandboxSnapshotCleanupJob.java
SandboxSnapshotReconciliationJob.java
SandboxSnapshotAuditLogger.java
SandboxSnapshotLeaseContext.java
SnapshotCaptureRequest.java
SnapshotCaptureResult.java
SnapshotRestoreResult.java
SnapshotRestoreDecision.java
```

### 16.6 Domain / migration

新增：

```
core-ai-server/src/main/java/ai/core/server/domain/SandboxSnapshot.java
core-ai-server/src/main/java/ai/core/server/domain/SandboxSnapshotState.java
core-ai-server/src/main/java/ai/core/server/domain/SandboxSnapshotStatus.java
core-ai-server/src/main/java/ai/core/server/domain/SandboxSnapshotReason.java
core-ai-server/src/main/java/ai/core/server/domain/migration/SchemaMigrationVSandboxSnapshotIndexes.java
```

## 17. 测试计划

### 17.1 Runtime tests

- capture `/tmp` 单文件并 restore 到空目录。
- capture `/skill` 多级目录。
- manifest 必须包含 image digest、runtime major、generation、roots、sha256。
- 排除 socket/FIFO/device。
- 拒绝 `..` escape archive。
- 拒绝 absolute path archive。
- 拒绝 hardlink archive。
- 超过 max bytes 返回 413。
- active execution 时 `require_quiescent=true` 返回 409。
- restore 到 staging 成功后再 merge；故意制造中途失败时目标目录不应半恢复。
- 文件权限按策略收敛，不恢复 owner/group。

### 17.2 Server unit tests

- dirty=false release 不 capture。
- dirty=true idle release capture 并上传 Blob，Mongo CAS 成功后才 clear dirty。
- capture 失败仍 release。
- upload 成功但 Mongo CAS 失败时登记/delete orphan，不 clear dirty。
- restore latest eligible AVAILABLE generation。
- latest eligible restore 失败后 fallback previous eligible generation。
- 所有 eligible generation restore 失败时，`restore.failure.mode=fail` 会阻断当前工具调用。
- no eligible snapshot 时空 sandbox 继续并记录 warning。
- user delete session 删除 snapshot metadata 和 blob。
- user delete session 与 restore 并发时 restore 被拒绝。
- image digest mismatch strict 跳过 restore。
- runtime major mismatch 跳过 restore。
- skill manifest hash mismatch 时跳过 `/skill` 或触发 re-materialize。
- owner 变化、lease token 不匹配、sandbox epoch 不匹配时 capture/restore CAS 失败。
- session rebuild 后 dirty state 从权威状态恢复，而不是依赖 `LazySandbox` 内存字段。

### 17.3 Integration tests

使用 Docker provider：

1. 创建 session。
2. 执行 `write_file` 写 `/tmp/a.txt`。
3. idle release 触发 snapshot。
4. 删除 old sandbox。
5. 同 session 继续执行 `read_file /tmp/a.txt`。
6. 验证内容存在，且 sandbox id 已变化。

再验证 `/skill`：

1. use_skill materialize。
2. release + restore。
3. 新 sandbox 中 `/skill/<name>/SKILL.md` 存在。
4. 修改 skill hash 后再次 restore，验证旧 `/skill` 不被盲目恢复。

多 pod / ownership：

1. pod A acquire sandbox epoch 7。
2. pod B 获得新 owner lease 并 acquire epoch 8。
3. pod A 的 capture CAS 必须失败，且不能 clear dirty。
4. pod B restore/capture 使用 epoch 8 成功。

Blob/Mongo 异常：

1. upload 成功后模拟 Mongo 写失败。
2. cleanup/reconciliation 发现 Blob orphan。
3. sha256 损坏时 restore 失败并 fallback。

### 17.4 Manual verification

- dev 环境检查 Azure Blob prefix 下有 snapshot。
- Mongo `sandbox_snapshots` 有 AVAILABLE 记录。
- 用户继续 session 后前端没有 broken file path。
- restore 失败时默认阻断工具调用，并展示清楚的恢复失败信息。
- 用户选择空 sandbox 继续后，系统使用空 sandbox 并记录 explicit discard。
- 删除 session 后 Blob 在 cleanup SLA 内删除。
- canary restore job 能周期性抽样下载、校验、restore 到临时 sandbox。

## 18. 上线步骤

### Phase 1: Metadata、Lease 与 Blob 基础

- 增加 Mongo `sandbox_snapshots`、snapshot state、generation 分配和 indexes。
- 定义 `SnapshotLeaseContext`，接入 `SessionOwnershipRegistry` 的 owner/lease 校验。
- 增加 Azure Blob private upload/download。
- 增加 cleanup/reconciliation/audit 基础骨架。
- 不接入实际 release/restore。

### Phase 2: Runtime API

- 增加 `/snapshot` 和 `/snapshot/restore`。
- 本地用 Docker runtime 验证 archive/restore。
- 补 path validation、staging restore、sha256、quiescent、quota tests。
- 不接入 server release。

### Phase 3: Idle release capture

- 给 `SandboxService.releaseSandbox` 增加 reason。
- 只接 `IDLE` reason。
- idle release 前 capture，并用 lease/epoch/generation CAS 更新 AVAILABLE。
- capture 失败不阻塞 release，但记录 continuity loss。

### Phase 4: Lazy restore

- `LazySandbox.ensureReady()` acquire 后 restore latest eligible AVAILABLE generation。
- restore 完成后再 dispatch READY。
- eligible snapshot restore 全部失败时默认阻断当前工具调用。
- 同 session 继续文件工具 E2E 验证。

### Phase 5: 观测与前端事件

- 增加 `SNAPSHOTTING`、`RESTORING`、失败事件。
- 前端展示恢复失败、fallback、空 sandbox 继续选择。
- 增加 metrics：
  - capture count / failure count
  - restore count / failure count
  - snapshot bytes
  - capture/restore duration
  - fallback count
  - no eligible snapshot count
  - lease conflict count
  - orphan blob count
  - temp disk quota rejection count

### Phase 6: 清理与 lifecycle

- Azure lifecycle policy 或 cleanup job。
- user delete session 删除 snapshot。
- 保留 generation 数配置。
- canary restore job。
- runbook 和 kill switch。

## 19. 推荐 v1 决策（已过时，被顶部"v1 实施范围"取代）

> ⚠️ 本章是 2026-06 的初版建议，2026-07-06 复核后已被文档顶部"v1 实施范围"一章取代。主要差异：只保留 1 代 snapshot（原 2 代）、内存 dirty（原 CAS dirty）、restore 失败重试 1 次后降级继续（原 fail 阻断）、单包上限 500MB / 保留 14 天（原 256MB / 7 天）、无 lease。以下内容仅作历史记录保留。

- Snapshot roots：`/tmp`、`/skill`。
- Archive：`tar.gz`。
- 存储：Azure Blob private container。
- Metadata：Mongo `sandbox_snapshots`。
- 最大 compressed size：256 MB。
- 最大 file count：10000。
- Retention：7 天。
- 每 session 保留最新 2 个 AVAILABLE snapshot。
- Image policy：strict，按 `image_digest` 判断。
- Runtime compatibility：`runtime_major` 必须一致。
- Restore failure mode：fail。
- Restore selection：latest eligible AVAILABLE generation，最多 fallback 到上一代。
- Capture 时机：只做 idle release 前。
- Dirty tracking：内存 dirty 只是优化，权威 dirty state 必须带 epoch/generation 并通过 CAS 更新。
- Security：private Blob container、无前端 SAS、restore 授权、audit log、delete SLA。
- Operations：cleanup、orphan reconciliation、canary restore、metrics、kill switch。
- 暂不做周期 checkpoint。

## 20. 后续扩展

- 周期 checkpoint：对长时间活跃 session，每 N 分钟或 dirty 后 debounce capture。
- 增量 snapshot：按 manifest diff 上传。
- Content-addressed blob：多个 snapshot 复用相同文件块。
- Named checkpoint / rollback：允许用户显式保存和回滚。
- Derived reusable snapshot：把常用依赖安装后的状态作为可复用基础层。
- Writable workspace：把 `/workspace` 改成可持久化 volume 后纳入 roots。
- UI 文件浏览器：基于 snapshot 或 sandbox runtime 展示当前文件树。
- 用户级 quota：限制单用户总 snapshot 存储。
- 应用层 envelope encryption：在合规要求提升时启用。
