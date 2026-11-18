# 命令队列 Redis Streams → PostgreSQL — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft / 实施中 |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-18 |
| 作者 | core-ai |
| 范围 | 异步 `SessionCommand` 的跨 Pod 投递；同步 RPC 已由 HTTP transport 承载，不在本次范围 |
| 上游 | `docs/cn/design-dataset-postgresql-migration.md` §7.3、§7.7 第 5 步 |

## 1. 现状与问题

当前异步命令链路：

```text
CommandPublisher
  -> Redis Stream coreai:commands:pod:<owner> 或 coreai:commands:unowned
  -> CommandConsumer（一个 Pod 一个 pod-stream consumer + unowned consumer group）
  -> InProcessCommandHandler.handle()
  -> xdel / xack
```

- `CommandPublisher.java:42` 写入 Redis Stream；`CommandConsumer.java:118` 读取 unowned group。
- 命令没有数据库记录：Pod 崩溃时只能依赖 Redis Stream 未删除条目重读；无法查询积压、重试次数或失败原因。
- `CommandPublisher` 通过 `SessionOwnershipRegistry` 找 owner；找不到 owner 时走 unowned stream。
- `claimLocally=true` 时，未认领会话在收到请求的 Pod 本地 claim 并直接处理，绕过队列。

## 2. 目标与非目标

### 2.1 目标

- 新增 `sys.command.backend=redis|postgres`，默认 `redis`，行为不变。
- `postgres` 模式：异步命令写入 PostgreSQL，owner Pod 轮询/领取/确认；可查询、可重试、崩溃后可恢复。
- 保留混合版本兼容：新 Pod 同时消费 Redis（旧 Pod 发布）和 PG（新 Pod 发布）；旧 Pod 继续只消费 Redis。
- 不改变 `SessionCommand` 语义、`InProcessCommandHandler` 和公开 API。
- 不改变同步 RPC；`RpcClient` 仍走 HTTP/Redis 回退。

### 2.2 非目标

- 不迁移 A2A `A2AEventRelay`（单独切片）。
- 不删除 Redis 命令队列/`JedisConfig`；清理在全部 Pod 切到 PG 之后。
- 不引入新的消息中间件。

## 3. 数据模型（Flyway V45）

```sql
ALTER TABLE messaging.pods ADD COLUMN command_backend TEXT NOT NULL DEFAULT 'redis';

CREATE TABLE messaging.commands (
    id           BIGSERIAL PRIMARY KEY,
    type         TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    user_id      TEXT,
    payload      TEXT NOT NULL,
    request_id   TEXT,
    target_pod   TEXT,                         -- NULL = unowned
    status       TEXT NOT NULL DEFAULT 'pending', -- pending | processing | failed
    claimed_by   TEXT,
    claimed_at   TIMESTAMPTZ,
    attempts     INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error   TEXT
);

CREATE INDEX commands_pending_idx
    ON messaging.commands (status, available_at, target_pod, id);
```

- `command_backend` 是 Pod 能力标记：新 Pod 注册 `postgres`，旧 Pod 代码不写该列，默认 `redis`。
- 成功处理后删除行；失败保留 `failed` + `last_error`，由清理任务按 24 小时删除。

## 4. 路由与兼容

### 4.1 发布

`CommandPublisher.publish`（`postgres` 模式）：

1. 保持 `claimLocally`：无 owner 时先尝试本地 claim；成功则直接 `InProcessCommandHandler.handle`，不入队。
2. 有 owner：
   - `owner.commandBackend == postgres` → `PgCommandQueue.publish(command, owner)`；
   - 否则（旧 Pod / 未知）→ 继续 `XADD` 到 Redis pod stream。
3. 无 owner：
   - PG 模式 → `PgCommandQueue.publish(command, null)`；
   - 旧 Pod 仍走 Redis unowned stream。
4. PG 写入失败 → 回退 Redis 当前路径（记录 WARN）。

### 4.2 消费

新 Pod 在 `postgres` 模式同时启动：

- 原 `CommandConsumer`（Redis pod stream + unowned stream），用于旧发布方/旧 owner；
- 新 `PgCommandConsumer`，轮询 `target_pod = self OR target_pod IS NULL` 的 pending 行。

旧 Pod 只启动原 `CommandConsumer`，因此：

- 新发布方给旧 owner 的命令走 Redis，旧 Pod 能消费；
- 旧发布方给新 owner 的命令走 Redis，新 Pod 的 Redis consumer 能消费；
- 新→新走 PG；
- 无 owner 的命令两边各自可消费，不会互相阻塞。

## 5. 领取、确认、重试、恢复

### 5.1 领取

`PgCommandQueue.claim(podId, limit)` 在事务中：

```sql
SELECT id, type, session_id, user_id, payload, request_id, attempts
FROM messaging.commands
WHERE status = 'pending'
  AND available_at <= now()
  AND (target_pod = ? OR target_pod IS NULL)
ORDER BY id
LIMIT ?
FOR UPDATE SKIP LOCKED;
```

逐行 `UPDATE ... SET status='processing', claimed_by=?, claimed_at=now(), attempts=attempts+1`，提交后返回。一个 Pod 一个 consumer，按 `id` 顺序处理，保持单 Pod 内命令顺序。

### 5.2 成功

`DELETE FROM messaging.commands WHERE id=? AND claimed_by=?`。

### 5.3 失败/重试

- `attempts < 3`：`status='pending'`、`available_at=now()+backoff`（1s、5s）、清空 claim、记录 `last_error`；
- `attempts >= 3`：`status='failed'`，保留错误，后续由清理任务删除；
- 不自动重试已经产生副作用的命令；重试只覆盖消费者异常/崩溃窗口。

### 5.4 崩溃恢复

- 消费者停止时 `releaseClaimedBy(podId)` 把本 Pod 的 `processing` 行恢复为 `pending`。
- 清理任务每 30 秒把 `processing` 且 `claimed_by` 对应 Pod 心跳已失效（`messaging.pods` 超过 30 秒无心跳）的行恢复为 `pending`；不会因命令执行时间长而误抢。
- `attempts` 在领取时递增；达到 3 次仍无法完成则标记 `failed`。

### 5.5 Unowned

- 领取到 `target_pod IS NULL` 的命令时，先 `SessionOwnershipRegistry.claim(sessionId)`；claim 失败则 `retryLater`，不执行。
- claim 成功后按普通命令处理；这与当前 unowned consumer 的语义一致。

## 6. 配置与部署

| 属性 | 值 | 默认 |
|---|---|---|
| `sys.command.backend` | `redis` / `postgres` | `redis` |

`postgres` 前置条件：

- `sys.store.mode=pg`；
- 存在 `PodRegistry`（`rpc=http` 或 `lease=postgres`）；
- Flyway V45 已应用。

部署顺序：

1. 发布含 PG 队列代码、但 `SYS_COMMAND_BACKEND` 未设置的版本（默认 Redis，行为不变）。
2. 滚动到全量新版本后，设置 `SYS_COMMAND_BACKEND=postgres` 滚动。
3. 观察至少一周；之后再发清理版本去掉 Redis consumer/publisher。

回滚：`SYS_COMMAND_BACKEND=redis` + rollout；PG 表保留，未处理行不影响 Redis 模式。

## 7. 观测与测试

- 日志：发布目标（postgres/redis）、领取/完成/失败、重试次数、unowned claim 失败；不打印 payload。
- 指标/查询：`messaging.commands` 按 status/target 统计积压；consumer 每轮领取数。
- 单测：队列 SQL 映射/状态转换（本地 PG）、`CommandBackend.of`、发布路由（mock registry）。
- dev E2E：新→新、旧→新、新→旧、unowned、Pod 重启、失败重试、回滚 redis、SSE/RPC 同时正常。
- UAT：同矩阵，固定镜像 tag，保留回滚。

## 8. 风险

| 风险 | 对策 |
|---|---|
| 混合版本路由错误导致命令丢失/重复 | `command_backend` 能力标记 + 双消费；E2E 覆盖旧 Pod 两个方向 |
| 长命令被误认为崩溃并重抢 | 只按 Pod 心跳失效恢复，不按 `claimed_at` 超时 |
| 队列积压/表膨胀 | 成功即删；failed 24h 清理；索引 `(status, available_at, target_pod, id)` |
| PG 不可用 | 发布回退 Redis；消费重连；不阻塞 HTTP 请求 |
| 顺序变化 | 单 Pod consumer 按 id 顺序；跨 Pod 本就无全局顺序 |
| 旧 Pod 不认 `command_backend` 列 | 列默认 `redis`；旧 Pod 的 upsert 不写该列 |

## 9. 实施拆分

1. V45 + `CommandBackend` + `PgCommandQueue` + 行类型。
2. `PodRegistry` 能力列/查询；`CommandPublisher` 路由与回退。
3. `PgCommandConsumer` + stale/cleanup job；`MessagingRuntimeModule` 双消费。
4. 单测 + dev E2E；通过后 UAT。
5. 清理版本（后续）：去掉 Redis 命令 publisher/consumer，再进入 Redis 下线。
