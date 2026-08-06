# Sandbox 上传附件持久化与自动回填设计

- 日期：2026-08-06
- 状态：已确认，待实现
- 优先级：P0
- 范围：`core-ai-server`

## 1. 背景

聊天中的普通附件会先上传到对象存储，再由处理消息的服务端节点下载并放入当前 Sandbox 的 `/tmp/<fileName>`。聊天历史只保存了面向 Agent 的路径提示，例如：

```text
[File uploaded to sandbox: /tmp/社媒数据.xlsx]
```

Sandbox 是临时计算资源。会话空闲后，Sandbox 可能因 TTL、空闲清理、Pod 重启或重新部署而消失；用户下次发送消息时系统会惰性创建一个新的 Sandbox。新的 Sandbox 没有旧 `/tmp` 文件，而聊天历史仍然保留旧路径，因此 Agent 会看到路径但无法读取文件。

对象存储中的源文件此时通常仍然存在。问题不是源对象被删除，而是系统没有持久化“该会话的哪个 Sandbox 路径对应哪个对象存储文件”的关系，也没有在新 Sandbox 创建时重放这层关系。

## 2. 目标

本次 P0 需要满足：

1. Sandbox 附件成功上传到 `/tmp` 后，持久化其对象存储引用和目标路径。
2. 同一会话创建新 Sandbox 时，在首次工具调用前自动恢复仍然有效的原始附件。
3. 只恢复当前用户、当前会话拥有的附件，避免跨用户或跨会话读取。
4. 与 Sandbox 完整快照兼容：完整快照成功恢复时，不用原始附件覆盖快照中的文件。
5. 单个历史附件已不存在时，记录明确告警并继续恢复其他附件，不阻断整个会话。
6. 保持现有聊天消息格式和前端协议兼容。

## 3. 非目标

本次不做以下事项：

- 不延长 Sandbox TTL，也不在附件过期时立即创建新 Sandbox。
- 不把 Sandbox 变成长驻资源。
- 不恢复 Agent 在 Sandbox 中生成、下载或修改的任意工作文件；这些文件仍由完整快照能力负责。
- 不回填本功能上线前、没有持久化引用的历史附件。
- 不调整对象存储的生命周期策略。
- 不修改前端附件下载链接和私有容器访问方式；这是独立问题。

## 4. 方案概览

采用“持久化原始附件引用 + 新 Sandbox 惰性回填”方案：

```text
浏览器上传对象
  -> SEND_MESSAGE 携带对象元数据
  -> 服务端校验并下载对象
  -> 上传到当前 Sandbox /tmp
  -> 成功后写入 session_attachment_ref

后续消息触发新 Sandbox
  -> 创建 Sandbox
  -> 尝试恢复完整快照
  -> 若快照未恢复，则查询当前用户/会话的 Sandbox 附件引用
  -> 每个目标路径只取最新引用
  -> 从对象存储下载并回填到 /tmp
  -> Sandbox READY
```

不采用只延长 TTL 的方案，因为部署、故障和资源回收仍会丢失 `/tmp`；也不把完整快照作为 P0 的唯一依赖，因为快照可能未启用，而且恢复原始附件不需要保存整个文件系统。

## 5. 数据模型

复用现有 `session_attachment_ref` 集合，在 `SessionAttachmentRef` 增加两个可选字段：

```text
kind: SANDBOX | VIDEO
target_path: /tmp/<safe-file-name>
```

Sandbox 附件记录同时保存已有字段：

- `session_id`
- `user_id`
- `container`
- `blob_name`
- `source_etag`
- `source_size_bytes`
- `content_type`
- `file_name`
- `created_at`

兼容规则：

- 新 Sandbox 附件明确写入 `kind=SANDBOX` 和 `target_path`。
- 现有视频引用继续可读；新增视频引用明确写入 `kind=VIDEO`。
- 历史文档没有 `kind` 时，不能被 Sandbox 回填查询命中。
- 为 `{ session_id: 1, user_id: 1, kind: 1, created_at: -1 }` 增加查询索引。

同一目标路径允许保留多条历史引用。恢复时按 `created_at` 从新到旧排序，每个 `target_path` 只取第一条，即“同名文件最后一次上传生效”。这样不需要跨 Pod 的读改写 upsert，也避免并发上传时旧记录覆盖新记录。

## 6. 上传与持久化流程

### 6.1 跨 Pod 命令负载

`AttachmentMessageHelper.collectPendingFiles` 在现有 `fileName/container/blobName` 之外携带 `contentType`。处理命令的 Pod 不信任客户端传入的路径，服务端根据安全文件名计算唯一目标：

```text
/tmp/<basename(fileName)>
```

拒绝空文件名、`.`、`..`、包含 NUL 的名称，以及规范化后无法安全落在 `/tmp` 下的目标。容器必须等于当前配置的 Sandbox 上传容器，`blobName` 必须位于 `uploads/` 前缀下。

### 6.2 成功边界

处理 `SEND_MESSAGE` 时按以下顺序执行：

1. 校验附件来源和文件名。
2. 用对象存储 `headObject` 获取服务端可信的 ETag、大小和 Content-Type。
3. 下载对象并上传到当前 Sandbox 的目标路径。
4. 只有第 3 步成功后才写入 `SessionAttachmentRef`。
5. 写入引用后再继续发送用户消息给 Agent。

`SandboxService.uploadFiles` 返回成功上传的附件结果，而不是静默吞掉失败。引用写入失败时，本次消息失败并返回错误，避免出现“当前 Sandbox 有文件，但系统误以为附件可长期恢复”的不一致状态。已经上传的对象和 Sandbox 文件不做破坏性回滚，用户重试即可生成完整引用。

### 6.3 幂等与重试

命令重试可能产生内容相同的多条引用。恢复时的“每个路径只取最新引用”保证结果确定；P0 不引入分布式事务或唯一 upsert。后续可以用 command id 增加幂等键，但不影响本次修复的正确性。

## 7. 新 Sandbox 回填流程

### 7.1 触发时机

回填仍由 `LazySandbox.ensureReady()` 的 post-acquire 阶段触发，发生在 Sandbox `READY` 事件之前，因此 Agent 的第一次文件工具调用看到的已经是恢复后的环境。

已有 Sandbox 从 Redis 重新 attach 成功时不回填，避免覆盖仍然存活的文件；只有实际 acquire 新 Sandbox 时执行。

### 7.2 与完整快照的关系

`LazySandbox` 把快照恢复结果传给 post-acquire hook：

- `RESTORED`：跳过附件回填。完整快照包含当时的 `/tmp` 状态，必须保留用户对附件的修改。
- `NONE`：回填持久化的原始附件。
- `DEGRADED`：回填原始附件，同时保留现有“工作文件未能恢复”的 READY 告警。
- 快照能力未启用：等价于 `NONE`。

### 7.3 查询与恢复

Repository 使用 `session_id + user_id + kind=SANDBOX` 查询，按 `created_at` 降序返回。恢复服务还会逐条重新校验：

- 引用属于当前 session 和 user。
- container 等于当前 Sandbox 容器。
- blob name 位于 `uploads/` 前缀。
- target path 是安全的 `/tmp/<basename>`。

每个 target path 只恢复最新引用。单个对象下载或上传失败时记录包含 session、reference id、target path 的告警，继续其他文件；日志不得包含签名 URL、凭据或对象内容。

回填是 best-effort 的原因是：历史对象可能按合法生命周期过期，不能让一个旧附件永久阻断整个会话。READY 日志记录恢复成功数、跳过数和失败数，便于监控。

## 8. 组件改动

主要改动边界：

- `AttachmentMessageHelper`：在命令负载中保留 Content-Type。
- `PendingFile`：携带 Content-Type 和服务端计算的目标路径。
- `SandboxFileService`：集中校验、上传，返回成功结果；提供持久化引用回填能力。
- `InProcessCommandHandler`：获取可信对象元数据，在 Sandbox 上传成功后持久化引用。
- `SessionAttachmentRef` / `SessionAttachmentRefRepository`：增加类型、路径和按 owner/session 查询。
- `SandboxServiceDependencies` / `SandboxService`：注入 Repository，并在新 Sandbox ready hook 中执行恢复。
- `LazySandbox`：把快照恢复结果传给 post-acquire hook。
- Mongo schema migration：创建恢复查询索引。

不修改 `ChatMessage` 文档结构。现有路径提示继续用于 Agent 上下文，持久化引用独立承担恢复职责。

## 9. 错误处理与可观测性

上传阶段：

- 非法容器、对象前缀或文件名：拒绝本次命令，返回可操作错误。
- 对象不存在或下载失败：拒绝本次命令，不写引用。
- Sandbox 上传失败：拒绝本次命令，不写引用。
- 引用持久化失败：拒绝本次命令并记录错误。

恢复阶段：

- Repository 查询失败：记录错误，Sandbox 继续 READY。
- 单文件失败：记录 warning，继续下一文件。
- 全部失败：Sandbox 仍 READY；Agent 后续读文件会得到原有文件不存在错误。

新增结构化日志字段至少包括：`sessionId`、`referenceId`、`targetPath`、`restoreOutcome`、`restoredCount`、`failedCount`。不输出账户密钥、SAS、完整 Authorization header 或文件内容。

## 10. 测试策略

按 TDD 增加以下覆盖：

1. `AttachmentMessageHelperTest`
   - Sandbox 附件命令元数据包含 Content-Type。
   - 多媒体附件行为保持不变。
2. `InProcessCommandHandlerTest`
   - Sandbox 上传成功后写入完整引用。
   - 上传失败时不写引用且命令失败。
   - 非法 container/prefix 被拒绝。
3. `SessionAttachmentRefRepositoryTest`
   - 查询严格限定 session、user、kind，并按时间降序。
4. `SandboxFileServiceTest`
   - 每个目标路径只恢复最新引用。
   - 一个对象失败不影响其他对象。
   - 不安全路径和错误容器被跳过。
5. `LazySandboxTest` / `SandboxServiceTest`
   - `RESTORED` 时不执行原始附件回填。
   - `NONE` 和 `DEGRADED` 时执行回填。
   - 回填完成后才发送 READY。
   - attach 既有 Sandbox 不重复回填。
6. 回归测试
   - 视频引用解析不受新增 `kind` 影响。
   - Workflow staged files 不被保存成聊天附件引用。

## 11. 验收标准

1. 用户上传一个 `.xlsx` 并成功被 Agent 读取。
2. 原 Sandbox 被释放或过期，对象存储源文件仍存在。
3. 用户在同一会话发送新消息，系统惰性创建新 Sandbox。
4. 第一次 Agent 工具执行前，原文件已恢复到相同 `/tmp` 路径并可读取。
5. 若完整快照成功恢复，原始附件回填不会覆盖快照内的同名文件。
6. 不同用户或不同会话的引用不能被恢复。
7. 旧对象不存在时，新 Sandbox 仍能 READY，日志可定位失败引用。
