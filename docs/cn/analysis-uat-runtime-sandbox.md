# UAT core-ai-server 负载不均与沙箱预算问题分析

> 环境：UAT（FBRDevAKSCluster / namespace `uat-ai`）  
> 分析对象：`core-ai-server-6dcfbdcb4-47tjf`、`core-ai-server-6dcfbdcb4-8r5vz`  
> 分析时间：2026-11-03（截图/控制台时间为 UTC+8；`runtime_samples` 时间为 UTC）  
> 状态：主要机制已定位；部分结论仍需额外数据验证  
> 证据标记：**[已确认]** 代码/日志/DB 直接证据；**[推断]** 有间接证据；**[待验证]** 需要 ingress 配置、连接日志或 heap dump 才能确认

---

## 0. 结论摘要

1. **[已确认] 负载集中在 `8r5vz`。** 03:26 UTC（11:26 UTC+8）的采样是 13 个会话 vs 1 个；后续会话回落到 9 个时，`8r5vz` 的 after-GC 仍有 57.78%。**[推断]** 主要机制是“会话归属 + 客户端连接粘性”：会话采用 locality-first 路由，接请求的 pod 认领后一直留在那里；服务端没有按负载重新放置。连接层是否真的粘在同一台 pod，尚缺 ingress 配置/连接日志证据；样本也只有一个用户，不能排除巧合。
2. **[已确认] 沙箱预算超限来自默认值不匹配。** UAT 的 `system_settings` 没有配置 `runtime_*`；默认每用户沙箱内存 `10240Mi`、每个沙箱 `2048Mi`，有效上限 **5 个沙箱**，而每用户会话上限是 20。用户 A 在 1 小时内创建了 8 个会话（6 个图片背景替换、2 个关键词/Google post），前 5 个各占一个 2GiB 沙箱，第 6 个开始被拒：`12288Mi > 10240Mi`。
3. **[已确认] `use_skill` 失败是沙箱不可用的连带结果。** `ServerSkillTool` 在返回 skill 内容前会先把 skill 物化到沙箱；物化触发 `LazySandbox.ensureReady()` → `SandboxManager.enforceQuota()`，配额异常没有被降级处理，整个 skill 被标记为失败。
4. **[待验证] “改了 limit 仍被拒”的原因尚未确定。** UAT `system_settings` 里 runtime 字段仍是 `null`，`updated_at` 是 2026-10-30，日志仍是 `12288Mi > 10240Mi`。`updated_at` 只能证明当天有过一次 settings 写入，不能区分是 Runtime 表单还是 System Configuration 表单；至少存在三种可能：改的是别的环境、保存时 runtime 字段没有带上、或者改的是别的 key。需要在代码/环境/前端三个方向排查，不能直接断言“写入没有到达这个库”。
5. **[推断] 13 个会话不是 heap 饱和的全部原因。** live heap histogram 显示 2.27GB 中 **2.17GB 是 `byte[]`（95.6%）**；同一时段会话上下文估算只有 141MB、inline image 138MB。JDK 25（histogram 显示 `java.base@25.0.5`）使用 compact string（JDK 9+ 已有），大字符串的底层存储计入 `byte[]`，因此重点是大字符串/payload 留存（SSE replay buffer、trace payload、agent history 等），而不是 13 份聊天文本。**精确 holder 仍需 heap dump + dominator 确认。**
6. **建议优先级：** 先确认 UAT 限额的写入路径和值（暂定 `24576Mi / 12`，待确认），再评估 per-user LRU 沙箱回收、SSE/trace payload 字节上限与 base64 剥离；随后做 per-pod 会话计数 + 新会话 least-loaded 放置。只加副本不能迁移已有会话，不能单独解决 skew；副本只有在配合 placement/密度控制时才有意义。

---

## 1. 现象

### 1.1 负载不均

Runtime 面板在 **11:26 UTC+8（03:26 UTC）** 的采样：

| Pod | Sessions | Sandboxes live | Heap | Turns saturated / rejected | Sandboxes rejected |
|---|---:|---:|---:|---:|---:|
| `core-ai-server-6dcfbdcb4-8r5vz` | 13 | 12 | 93.26% of 4096 MiB | 2 / 0 | 13 |
| `core-ai-server-6dcfbdcb4-47tjf` | 1 | 4 | 5.68% of 4096 MiB | 0 / 0 | 0 |

> 表里的 `13` 是 **sandboxes_rejected_total**，不是 live sandbox 数；live 是 12。`Saturated / Rejected` 的 turns 与 sandboxes 分开看，避免把 13 误读成“13 个沙箱”。

`runtime_samples` 的时间线（UTC）：

- 21:39–01:56：两台 pod 基本空闲，各 3 个沙箱，heap 约 5–12%。
- 01:56 起：新会话持续落到 `8r5vz`。
- 02:40：`8r5vz` 5 个会话；03:09：9 个；03:17：11 个；03:22：14 个；03:24–03:26：13 个（这是峰值）。
- 04:15：11 个会话；04:20：9 个；04:23：9 个。会话数会因 idle cleanup 或用户关闭而下降，不是一直稳定在 13。
- `47tjf` 全程只有 1–2 个会话。
- `8r5vz` 的 after-GC heap 从约 6% 涨到 37–51%，峰值多次到 93–99%；`47tjf` 的 after-GC 一直约 4–8%。

这不是“两台 pod 的 CPU 调度不均”，而是**计算侧会话集中在一台 pod**。日志里同一个会话的 SSE 可以落在 `47tjf`，但 `SEND_MESSAGE` 被 publish 到 `8r5vz`；SSE 连接不持有会话堆，真正的 agent/tool 运行时在 owner pod。

### 1.2 沙箱预算与 skill 失败

用户 A（UAT 测试账号；完整邮箱和 sessionId 见 UAT 日志/Kibana，本文用前缀）在 02:23–03:22 创建了 8 个 chat 会话：

| 会话（前缀） | 创建时间（UTC） | 工作负载（来自 `chat_sessions.title`） | 沙箱 |
|---|---|---|---|
| `4c6c3871…` | 02:23:28 | 关键词 / Google post | `core-ai-sandbox-07542fa5` |
| `0b518478…` | 02:58:31 | 关键词 / Google post | `core-ai-sandbox-f5322f2b` |
| `7f214b7d…` | 03:01:42 | 图片背景替换 | `claim-3b9a881c` |
| `86d8de55…` | 03:09:18 | 图片背景替换 | `claim-29e24d73` |
| `0a7fc75d…` | 03:17:24 | 图片背景替换（海报） | `core-ai-sandbox-0a7d1901` |
| `a0ec4b74…` | 03:19:45 | 图片背景替换 | 被拒：`12288Mi > 10240Mi` |
| `99dab41b…` | 03:21:53 | 图片背景替换 | 被拒 |
| `6f844b6c…` | 03:22:17 | 图片背景替换 | 被拒 |

因此“image-heavy”有依据：8 个会话里 6 个是图片背景替换，2 个是关键词；不是 8 个全部 image-edit。`8r5vz` 的 `sandboxes_rejected_total` 为 13，对应最后 3 个会话的 `use_skill`、`read_file`、`run_bash` 等工具重试。

失败调用链：

```text
ServerSkillTool.execute
  -> materializeIfPossible
    -> sandbox.materializeSkill
      -> LazySandbox.ensureReady
        -> SandboxManager.acquire
          -> SandboxManager.enforceQuota
             throw SandboxQuotaExceededException(
               "sandbox memory budget reached for this user (12288Mi > 10240Mi)")
```

`ServerSkillTool.materializeIfPossible` 只捕获 `UnsupportedOperationException`；配额异常会冒泡到 `execute` 的 `catch`，最终返回 `Failed: sandbox memory budget reached ...`，skill 内容没有返回给模型。

---

## 2. 关键证据

### 2.1 沙箱配额算式

UAT `system_settings` 所有 `runtime_*` 为 null，代码默认值：

| 配置 | 默认值 | 来源 |
|---|---:|---|
| `maxSessionsPerUser` | 20 | `RuntimeLimits.DEFAULT_MAX_SESSIONS_PER_USER` |
| `maxSandboxesPerUser` | 10 | `RuntimeLimits.DEFAULT_MAX_SANDBOXES_PER_USER` |
| `maxSandboxMemoryMbPerUser` | 10240 | `RuntimeLimits.DEFAULT_MAX_SANDBOX_MEMORY_MB_PER_USER` |
| 每个沙箱 `memoryLimitMb` | 2048 | `SandboxConstants.DEFAULT_MEMORY_LIMIT_MB` |

因此有效上限：

```text
min(10 个, floor(10240 / 2048)) = 5 个沙箱/用户
```

第 6 个会话申请沙箱时：

```text
已有 5 × 2048 = 10240Mi
+ 新沙箱 2048Mi
= 12288Mi > 10240Mi
```

先撞到的是内存预算，不是 10 个数量上限。

### 2.2 UAT 配置现状与“未生效”排查

`system_settings`（`_id=default`）：

```json
{
  "_id": "default",
  "runtime_max_sandbox_memory_mb_per_user": null,
  "runtime_max_sandboxes_per_user": null,
  "runtime_max_sessions_per_user": null,
  "runtime_max_sandboxes_per_pod": null,
  "updated_at": "2026-10-30T06:58:18.969Z",
  "updated_by": "<redacted>"
}
```

同事最近的报错仍为 `12288Mi > 10240Mi`。但 `updated_at` 只说明 2026-10-30 有过一次 settings 文档写入，不能证明 Runtime 表单保存成功。需要区分：

1. **改的是别的环境**：UAT / DEV / PROD 的 `system_settings` 是不同库；如果 UI 指向了别的环境，UAT 不会变化。
2. **保存时 runtime 字段没有被带上**：`RuntimeSettingsService.update` 的 `apply` 会把 **所有** runtime 字段从 request 赋值（`RuntimeSettingsService.java:62-79`）；如果前端只发送变更字段，未发送的字段会被置为 null，然后 `replace(entity)` 整体覆盖。这是代码层面的真实风险，需要检查前端请求体/网络面板。
3. **改的是别的 key**：例如只改了 `maxSandboxesPerUser`，没有改 `maxSandboxMemoryMbPerUser`，报错仍然是内存限制；或者设成 `20480`（10 个 2GiB 沙箱刚好占满，第 11 个仍会以 `22528 > 20480` 被拒）。

`SystemSettingsService.update`（`SystemSettingsService.java:49-77`）会先加载完整 entity，再 `replace`，本身会保留 runtime 字段；因此“另一个设置页把 runtime 字段覆盖成 null”只在并发写入或前端提交不完整时成立，不能作为默认解释。

**热更新是否成立 [已确认，代码路径]**：

- `SandboxManager.currentLimits()` 每次 acquire 都调用 `limitsSupplier.get()`（`SandboxManager.java:130-137`）。
- `RuntimeGovernanceModule` 把 supplier 接成 `limitsProvider::get`（`RuntimeGovernanceModule.java:37-45`）。
- `RuntimeLimitsProvider.get()` 每次读 `SystemSettingsService.entity()`，后者每次 `systemSettingsCollection.get("default")`（`SystemSettingsProvider.java:26-34`、`SystemSettingsService.java:356-357`）。
- 因此，只要写入的是同一环境、同一 `_id=default` 文档，下一次 acquire 就会用新值；不需要重启。若保存后仍不生效，优先检查环境/文档/前端请求体，再考虑重启对照。

### 2.3 Heap 证据

在 `8r5vz` 上于 **04:15 UTC（11 个会话）** 执行的 live class histogram（`jattach 1 inspectheap`）：

| 类 | 实例数 | 字节数 |
|---|---:|---:|
| `byte[]` | 596,669 | 2,167,031,440 |
| `jdk.internal.vm.FillerElement` | 770 | 27,673,608 |
| `java.lang.String` | 483,313 | 11,599,512 |
| `java.util.HashMap$Node` | 253,825 | 8,122,400 |
| **总计** | **2,553,961** | **2,265,901,840** |

同一时段的 `runtime_samples`：

- 04:15：`heap_after_gc_percent = 59.27%`（约 2.43GiB）
- 04:23：9 个会话、6–9 个沙箱，`heap_after_gc_percent = 57.78%`
- `sessions_estimated_bytes_total`：140,908,989（约 141MB）
- `sessions_inline_image_bytes_total`：138,383,022（约 138MB）
- `sessions_context_tokens_total`：890,689

注意：

- `inspectheap` 默认统计存活对象并会触发一次 full GC；在 93–99% heap 的 pod 上执行会有短暂停顿，数字也可能与采样时点有偏差。
- `jdk.internal.vm.FillerElement` 是 JVM/GC 的填充对象，不是业务对象；27MB 只是 JVM 内部碎片/填充，不应作为业务留存分析。
- 2.17GB `byte[]` 不是 13 份会话上下文，也不是 inline image 估算能覆盖的部分；JDK 25 上的 compact string（JDK 9+）把大字符串的底层存储算在 `byte[]`。`String` 对象本身只有 11.6MB，说明重点在**大字符串的底层数组**，而不是字符串对象数量。
- inline image 138MB 只统计 `Content.ImageUrl` 里的 data URL（`AgentFootprintCalculator`），**不覆盖 SSE replay buffer、trace payload、tool 参数/结果**；因此“138MB inline image”和“2GB byte[]”不矛盾。

### 2.4 JFR 分配热点

60 秒 `settings=profile` 采样。以下两张表是**不同视图**，百分比不能跨表相加：

**按类（allocation-by-class）**

| 类 | 分配压力 |
|---|---:|
| `byte[]` | 43.69% |
| `org.bson.internal.ChildCodecRegistry` | 26.28% |
| `java.lang.String` | 15.74% |
| `com.fasterxml.jackson.core.json.JsonWriteContext` | 12.61% |

**按分配点（allocation-by-site）**

| 分配点 | 分配压力 |
|---|---:|
| `java.util.Arrays.copyOfRange(byte[], int, int)` | 26.52% |
| `org.bson.internal.ChildCodecRegistry.get(Class)` | 26.28% |
| `java.lang.StringUTF16.newBytesFor(int)` | 15.88% |
| `EntityDecoder$AgentDefinition$20.decodeAgentDefinition0` | 15.66% |
| `JsonWriteContext.createRootContext` | 12.61% |

这些是**分配压力**，不等于 live set；但说明 MongoDB entity decode（尤其 `AgentDefinition`）和 JSON 写入是高频分配路径，会放大 GC 峰值。`ChildCodecRegistry` 26% 也值得排查：是否每次操作都创建 codec registry，或某个 hot path 在反复 decode 不可变定义。

### 2.5 沙箱生命周期与持久沙箱

- `SandboxService.createDefaultConfig()` 的 `timeoutSeconds = 3900`；空闲会话由 `IdleSessionCleanupJob` 在 **60 分钟**后关闭并释放沙箱。
- 用户关闭浏览器标签页不会释放沙箱；只有会话 idle 回收或显式关闭才释放。
- OCG/OpenClaw instant 沙箱：
  - `OcgSandboxService.startSandbox` 调用 `sandboxService.createSandbox(buildSandboxConfig(), sessionId, "system", true)`；
  - `attachSandbox(..., "system", true)`；
  - `persistentSessionIds` 持有，`SandboxService.shutdown` 也会跳过。
  - 它们占 `system` 用户的预算，不占普通用户 A 的 10240Mi；但滚动回收必须排除它们。

---

## 3. 机制分析与假设

### 3.1 负载不均：会话放置（主要机制，非唯一因素）

**已确认：**

- `CommandPublisher.claimAndProcessLocally`：未认领会话由接请求的 pod 本地 claim；之后 `SessionOwnershipRegistry` 的 Redis key（TTL 60 秒）持续续期，没有迁移路径。
- `RuntimeStatsService` 只是**展示** `maxSessionsPerPod`；创建入口没有拦截，`RuntimeLimits.DEFAULT_MAX_SESSIONS_PER_POD = 500`。
- `RuntimeLimits.admissionEnabled` 默认 false；99% heap 只打 `runtime capacity saturated (warn-only)`，不会拒绝。
- 用户 A 的 8 个会话全部在 `8r5vz` 上创建；同用户的会话创建请求没有分散。

**推断/待验证：**

- 客户端/ingress 的长连接没有轮换，导致同一用户的创建会话请求持续落到同一台 pod。需要 ingress 配置（keepalive、session affinity、upstream 连接池）和访问日志来证实。
- 样本只有一个用户，不能排除“恰好都打到同一台”或“某个客户端/入口固定走一台”的偶然因素。建议用新客户端/强制新连接/不同用户创建会话做对照，观察是否仍然集中。
- 如果 ingress 是短连接/轮询，那么“连接粘性”解释不成立，应转向 session ownership 或其他路由逻辑。

结论应写成：**会话归属机制 + 连接粘性是当前最可能的主要机制，但连接层证据不足；不能把它写成唯一根因。**

### 3.2 沙箱预算：默认值不匹配 + pod 本地记账

1. **[已确认] 有效上限 5 个，但允许 20 个会话。** 20 是每用户会话预算（Redis `coreai:rsess:{userId}`），10240/2048 是沙箱预算；两者没有对齐。用户正常开到第 6 个会话就会遇到工具不可用。
2. **[已确认 + 潜在风险] 沙箱预算按 pod 本地 `activeSandboxes` 计算。** 本次事件中 8 个会话全在 `8r5vz`，所以实际撞限是“集中在单台”导致的；跨 pod 时两台各算 10240Mi、总用量可能翻倍，是**潜在记账风险**，不是本次已发生的事实。文档必须把两者分开。
3. **[已确认] `attach()` 不经过 `enforceQuota`。** `SandboxManager.attach` 只登记，不检查预算；重连（`reattachOrCreateSandbox`）或 OCG 附着可以把用户的实际占用推过 10240Mi，随后任何 `acquire` 都会被拒。
4. **[已确认] 没有滚动回收。** 达到上限时直接拒绝新沙箱，而不是回收该用户最久未活动的空闲沙箱。
5. **[已确认] `use_skill` 把沙箱不可用当成硬失败。** skill 内容本身不依赖沙箱，只有脚本依赖；这里应该降级。

### 3.3 Heap：大字符串/payload 留存

已确认：

- live `byte[]` 2.17GB，占 95.6%；`String` 对象只有 11.6MB；`char[]` 3.1MB。
- 会话上下文估算 141MB、inline image 138MB，无法解释 2GB。
- 04:23 会话数回落到 9 个后，after-GC 仍有 57.78%，说明不是简单的“每个会话的聊天记录”。

重点排查方向（按可能性）：

1. **SSE replay buffer**
   - `SessionChannelService.MAX_BUFFER_SIZE = 50000`，限制的是**事件数**，不是字节数。
   - `eventBuffer` 保存 `SseBaseEvent`；`SseToolStartEvent.toolArgs`、`SseToolResultEvent.result`、`SseCustomEvent.data` 都可能包含大字符串/base64。
   - `PatchedChannelImpl` 的 8MB 限制只作用于 channel 发送队列，不作用于 replay buffer。
2. **Trace payload**
   - `TracePayloadStore` 专门压缩 trace 里的 `data:image/...;base64`，但单条 data URL 超过 `12MB` 就不再压缩，仍以 String 留在 payload。
   - `hydrate` 会把 blob 的 data URL 加载回内存；如果 trace 页面/维护任务批量加载，会形成大字符串。
3. **Agent history**
   - `InlineImagePruner.prune` 返回 `List.copyOf(result)`，只裁剪“发给模型的 request 副本”，不修改原始 history。
   - 旧图片可能一直留在 session 的 history 里；当前 inline image 估算 138MB，说明这部分不是主因，但会叠加。
4. **MongoDB/AgentDefinition decode**
   - `EntityDecoder$AgentDefinition$20.decodeAgentDefinition0` 占 15.66% 分配压力；`ChildCodecRegistry.get` 占 26.28%。
   - 这说明不可变定义在被反复 decode，或者 codec registry 被高频创建；会推高 GC 峰值，并可能与某些缓存留存相关。

**证据等级：** **不能**从 class histogram 直接断言 2.17GB 就是 SSE/trace/history；它只能说明 95.6% 是 `byte[]`/大字符串。要确认精确 holder，需要 heap dump + dominator。上面的顺序是基于代码路径和指标的优先排查顺序，不是最终定论。

### 3.4 产品/容量层面的问题

- 4GB heap 的 pod 承接 13 个 image-heavy 会话，after-GC 已到 57–60%，峰值 93–99%；安全余量很小。
- `maxSessionsPerPod` 没有执行，placement 没有负载感知；单 pod 密度不可控。
- 负载不均和 heap 饱和是同一个问题的两面：**会话放置没有容量模型**。

---

## 4. 解决方案

### 4.1 立即止血：修正 UAT 限额（暂定值，待确认）

建议暂定：

```text
runtime_max_sandbox_memory_mb_per_user = 24576   # 12 × 2048Mi
runtime_max_sandboxes_per_user = 12
```

注意：

- 这是**暂定值**，需要确认产品目标是 12 个还是 10 个；4.5 节讨论的是长期对齐策略，不是当前 P0 的最终值。
- `20480Mi` 只能容纳 10 个 2GiB 沙箱；第 11 个会以 `22528 > 20480` 被拒。如果同事已经有 10 个沙箱，设 20480 仍会看到内存限制。
- 修改后热生效，代码路径见 2.2；如果保存后仍不生效，先检查是否写入了不同环境/不同文档，再考虑重启对照。
- 验证方式：让用户再建一个会话并触发工具，确认不再出现 `12288Mi > 10240Mi`，同时 `sandboxes_rejected_total` 不再增长。
- 如果暂时不想调大预算，另一个办法是让用户关闭旧会话/等待 60 分钟 idle 回收；但这只是止血。

### 4.2 Per-user LRU 沙箱回收（推荐）

目标：用户达到预算时，回收他**最久未活动**的空闲沙箱，优先保证新请求。

实现要点：

1. `SandboxManager.acquire` 按 `userId` 加锁，串行化“配额检查 + 回收 + 创建 + 登记”；现在 `enforceQuota` 是 check-then-act，同一用户并发创建可能超预算。
2. **拆分时间字段**：`SandboxManager.touch()` 目前会更新 `entry.createdAt = now`（`SandboxManager.java:274`），而 `cleanupExpired` 使用 `entry.createdAt` 计算 `maxAge`（`SandboxManager.java:204/220`）。这不是单纯的命名问题：`createdAt` 现在同时承担“创建时间”和“最后访问/TTL 续期基准”。LRU 改造必须拆成：
   - `createdAt`：固定创建时间，仅用于观测；
   - `lastAccessAt`：每次 `touch()`/`renew()` 更新，用于 LRU；
   - `expiresAt` 或继续用 `lastAccessAt + timeout` 做 TTL。
   如果只把 `createdAt` 改名而不更新 TTL 逻辑，可能导致沙箱被提前回收；这需要单测覆盖。
3. 超预算时按 `lastAccessAt` 升序扫描该用户的条目，跳过：
   - `persistentSessionIds`（OCG/instant 持久沙箱）；
   - 正在跑 turn 的会话（`InProcessAgentSession.isTurnRunning()`）；
   - run 沙箱（runId 不是 chat session）；
   - 最近 N 分钟刚被 touch 的沙箱（避免刚回收又被重新 acquire，形成“回收-重建”循环）。
4. 对可回收条目：
   - 先 `captureBeforeRelease(sessionId, sandboxId)` 尝试快照；
   - **快照失败策略需要明确**：如果 snapshot 已启用且 capture 失败，建议跳过该候选、尝试下一个，避免静默丢用户文件；如果 snapshot 未启用，记录 WARN 后释放，并在产品上提示“被回收会话的工作区可能丢失”。不能只写“先 capture”而不定义失败语义。
   - 调用 `LazySandbox.close()` 释放（`sessionSandboxes` 保留 LazySandbox，下一次工具调用会重新 acquire）；
   - 扣减内存/数量，继续直到能容纳新沙箱。
5. **后台进程/服务风险**：snapshot 只保存文件状态，不保存进程/内存态。被回收的沙箱里如果有 dev server、后台任务，回收后会丢失；建议只对“纯文件型、idle 超过 X 分钟”的 chat 会话回收，或提供 per-agent opt-out。
6. 如果所有沙箱都在使用中/持久，明确拒绝，并提示“所有沙箱都在使用中，请关闭空闲会话”；不要静默失败。
7. **锁与账本要一起设计**：用户级锁是 pod 本地的；4.5 节建议改成 Redis 跨 pod 记账。两者不能各做各的——如果先上 per-pod LRU、后改 Redis 账本，锁和账本会不一致，可能出现跨 pod 超卖/重复回收。建议先在 per-pod 范围内完成 LRU + 本地配额，再一次性迁移到 Redis 账本 + 分布式预留。
8. 记录指标：`sandbox_evictions_total`、被回收的 sessionId、释放 MB、剩余预算、快照失败数；日志 INFO。

语义：新请求成功；被回收的旧会话下次使用工具时冷启动/恢复快照，但不会阻塞当前用户。建议先实现这一项，因为它直接解决“第 6 个沙箱被拒”的体验，而不需要无限上调预算。

### 4.3 负载均衡：新会话放置

**短期（推荐）**

1. Redis 维护每台 pod 的活跃会话数：`coreai:rpod:{pod}`，创建 +1、关闭 -1，pod 心跳续 TTL。
2. 新建会话时，接收请求的 pod 比较自己与所有 pod 的计数：
   - 自己低于软上限（如 8）且不是最忙 → 本地创建；
   - 否则选负载最低的 pod，通过 `RpcClient.callToPod` 转发“创建会话”请求，由目标 pod 创建并返回 sessionId。
3. **原子性**：多个 pod 可能同时看到同一个最低值并同时转发；需要 Redis Lua/`SETNX` 做“检查 + 预留”，或让目标 pod 再校验软上限并返回重定向/重试。不能只做“先查计数再转发”。
4. **计数漂移**：pod 崩溃时靠 TTL 清理会短暂漂移；需要心跳 + 定期对账（例如从 ownership registry / session set 反算），并把漂移作为告警指标。
5. 转发失败回退本地创建，保证可用性；把 `maxSessionsPerPod` 变成真正的新建拦截；所有 pod 都满时返回 429，并给出明确提示。
6. SSE/命令路由保持现状：客户端 SSE 可以在任意 pod，`CommandPublisher` 会把命令路由到 owner pod。

**验收注意：** 软上限示例是 8；两台 pod 的总软容量是 16，所以“创建 20 个会话、偏差 ≤2”与软上限冲突。验收应写成：

- 2 pods × 软上限 8：创建 **16** 个会话，max-min ≤2；
- 或 3 pods / 总容量 ≥20：创建 20 个会话，max-min ≤2；
- 达到软上限后新会话应按策略转发或 429，而不是无限本地回退。

**长期**

设计文档中的 runtime worker tier：HTTP 层无状态，会话执行放到独立 worker 池，通过共享命令队列派发；放置按会话数、在途 turn、heap 水位加权。只有这样才能彻底摆脱“客户端长连接决定会话归属”。

**不要做的事**

- 不要只加副本：已有会话不会迁移；新副本只有在配合 placement/密度控制时才有意义。否则同一个重度用户的长连接仍可能把所有新会话钉在一台 pod 上。
- 不要试图迁移活跃会话：SSE、sandbox、session state 都在 owner pod，迁移成本高且容易丢事件；让 idle cleanup/滚动重启自然再分布。

### 4.4 Heap 优化

1. **SSE replay buffer 加字节上限**（例如每 session 8MB），超过丢弃最旧事件；对 `toolArgs` / `result` / `customEvent.data` 里的 base64 做剥离或替换成引用。
2. **历史图片只存引用**：`InlineImagePruner` 裁剪后把 history 里的 data URL 替换成对象存储 URL/占位符；不要只裁剪 request 副本。
3. **Trace payload**：所有 LLM/tool payload 的 data URL 都走压缩；降低 `TracePayloadStore.MAX_DATA_URL_LENGTH`；dashboard hydrate 不要一次性加载大图片。
4. **缓存 AgentDefinition/API schema**：避免每 turn/每工具调用重新 decode Mongo entity；检查 `ChildCodecRegistry` 是否每个操作都新建。
5. **降低单 pod 密度**：13 个 image-heavy 会话对 4GB heap 太密；按 placement 限制到 8–10 个，或把副本加到 3–4 个。**注意**：加副本只有在配合 placement 时才能解决 skew；单独加副本只是密度缓解，不是 13 vs 1 的修复。把 `-Xmx` 从 4GB 提到 5GB 只能拖延，不是根治；容器 limit 6GB，非堆还有约 2GB，风险不小。
6. **Heap dump 确认**：`/tmp` 还有 7.4GB 空间；可以 dump heap + MAT dominator 分析，确认 2.17GB 的 holder 后再做针对性修复。
7. **量化验收**：同一 workload（13 个 image-heavy 会话）下，after-GC 目标建议 **< 45%（约 1.8GB）**，且不再出现 95%+ 的峰值；目标值需结合实际 session runtime 基线校准。

### 4.5 配额记账修正

- **与 adoption D11 对齐：不要把 Redis 计数器作为目标态。** adoption 文档 D11 明确选择按 `core-ai/user-id` 标签 list claim/CR + 短 TTL 缓存，因为 Redis 计数依赖 acquire/release 严格配对，server 崩溃、release 失败、K8s 侧过期删除都会漂移。本文早期草案里的“Redis 跨 pod 记账”降级为历史方案，不作为当前实施项。
- 当前阶段保留 per-pod `activeSandboxes` 配额，配合限额与 LRU；adoption P1/P3 完成后再迁移到标签计数（P4）。
- `attach()` 也要计入预算，否则重连/OCG 可以绕过；这是一项独立的小修正，可以在 per-pod 阶段做。
- OCG persistent 沙箱用独立账本（如 `system` 或 `persistent` pool），滚动回收排除。
- 对齐默认值：要么 `maxSandboxMemoryMbPerUser = maxSandboxesPerUser × 2048`（10 个 = 20GiB），要么把 `maxSandboxesPerUser` 降到 5，并把 `maxSessionsPerUser` 与沙箱预算对齐；不要让“20 个会话”和“5 个沙箱”同时存在而没有任何提示。
- 4.5 是长期目标；P0 的 `24576 / 12` 只是暂定止血值，两者不矛盾，但需要在变更记录里写清楚“当前值”和“目标值”。

### 4.6 风险与回滚

| 变更 | 风险 | 回滚/缓解 |
|---|---|---|
| UAT 限额调大 | 单用户可占更多沙箱，节点压力上升 | 改回 10240/10；观察 `sandboxes_rejected_total`、节点容量 |
| LRU 回收 | 快照失败/后台进程丢失；回收-重建循环 | 快照失败跳过候选；minIdleTime + cooldown；per-agent opt-out；灰度 |
| SSE 字节上限 | 断线重放可能缺事件，前端出现 gap | 前端重连后拉历史；记录 `sse_buffer_dropped_total`；feature flag 回滚 |
| placement 转发 | RPC 失败/计数漂移导致创建失败或集中 | 本地回退；原子预留；心跳对账；软上限可配置 |
| Heap 修复 | 误删图片/影响 UI 展示 | 保留引用/URL；灰度；对比 after-GC 和用户可见性 |

---

## 5. 优先级与验收

> 本节 P0/P1/P2 是 **quota/用户体验 track**；adoption 的 P0a/P0b/P1–P4 是另一套编号，边界见第 7 节。

| 优先级 | 动作 | 影响 | 风险 | 验收 |
|---|---|---|---|---|
| P0 | UAT 暂定设 `24576 / 12`（待确认） | 立即解除 5 沙箱上限 | 低（热更新） | 用户第 6 个会话可触发工具；`sandboxes_rejected_total` 不再增长；确认写入的是 UAT `_id=default` |
| P1 | Per-user LRU 沙箱回收 | 新请求优先，旧会话按需冷启动 | 中（字段拆分、快照失败、persistent/busy 排除、锁与账本） | 单测：LRU、busy/persistent 跳过、并发不超预算、快照失败策略；UAT 6 个沙箱场景成功 |
| P1 | SSE replay buffer 字节上限 + base64 剥离 | 直接降低大字符串留存 | 中（可能影响 replay 完整性） | 13 个 image-heavy 会话下 after-GC < 45%；前端重连 gap 有兜底 |
| P2 | per-pod 会话计数 + least-loaded 放置 | 解决 13 vs 1 的 skew | 中（原子预留、RPC、计数漂移） | 2 pods × 软上限 8：16 个会话 max-min ≤2；3 pods：20 个会话 max-min ≤2 |
| P2 | AgentDefinition/Mongo decode 缓存 | 降低 GC 峰值 | 低 | JFR 中 `EntityDecoder$AgentDefinition` 分配占比明显下降 |
| P3 | Trace payload 压缩扩展、heap dump 定位 | 降低 2GB 级留存 | 低/中 | trace 页面不 OOM；dominator 报告无异常大 byte[] |

建议验证命令/指标：

- `SandboxManagerQuotaTest` 增加：并发 acquire、LRU 选择、persistent 跳过、busy 跳过、快照失败、`createdAt`/`lastAccessAt`/TTL 行为。
- `RuntimeStatsService` / `runtime_samples`：`sandboxes_rejected_total`、`sessions_active`、`heap_after_gc_percent`。
- `sandbox_evictions_total`、`sse_buffer_bytes`、`sse_buffer_dropped_total`、`trace_payload_bytes` 等新指标。
- JFR/histogram 复测：13 个 image-heavy 会话时 `[B]` 不应再占 95%。
- placement 压测：2 或 3 pods，按 4.3 的容量写验收，而不是 20 个会话配 2×8 软上限。

---

## 6. 待确认

1. **limit 改到了哪个环境/哪个文档？** UAT `system_settings` 的 runtime 字段仍是 null；需要检查前端请求体和环境，不能只凭 `updated_at` 下结论。
2. **限额目标值：** `24576 / 12`（暂定）还是 `20480 / 10`？如果是 10，用户第 11 个沙箱仍会被拒；4.5 的长期对齐策略另议。
3. **是否允许低峰重启/滚动 `8r5vz`？** 这能把已有会话打散，但会打断正在跑的 turn；需要确认窗口。
4. **是否现在实现 LRU 回收和 placement？** 建议先做 LRU + SSE 字节上限；placement 单独一批，锁/账本一起设计。
5. **是否授权 dump heap？** 用于确认 2.17GB 的精确 holder；否则只能按代码路径排查。
6. **ingress 配置/连接日志能否提供？** 这是确认“连接粘性”假设的关键证据；没有它，负载不均只能写成“推断”，不能写成唯一根因。

---

## 7. 与 sandbox-native-adoption 的实施分层

> 编号说明：本文第 5 节的 P0/P1/P2 是 **quota/用户体验 track**；`design-agent-sandbox-native-adoption.md` 的 P0a/P0b/P1–P4 是 **controller/API/安全 track**。两者不是同一套编号，不能直接混排。

### 7.1 判断规则

1. **是否直接让 `SandboxManager.acquire` 不再返回 `12288 > 10240`？** 是 → 现在做。
2. **是否依赖 v1beta1 / v1.0.9 / P1？** 是 → 等 adoption。
3. **adoption 是否会替换这个机制？** 是 → 现在只做最小兼容层，不建第二套。
4. **安全项（P0a/P0b/P2）不直接解决 quota，但也不能无限期推迟。** 它们应独立排期，不要塞进 quota 修复，也不要因为 quota 修复而忽略。

### 7.2 现在做（直接解决“无法创建 sandbox”）

| 工作 | 为什么现在做 | 依赖/边界 |
|---|---|---|
| 修正 UAT 写入路径，设 `24576 / 12`（Q0，暂定） | 直接解除 5 个沙箱上限；用户第 6 个会话即可恢复 | 无 adoption 依赖；先确认写的是 UAT `_id=default`，前端 request 没有漏 runtime 字段 |
| 验证 `RuntimeSettingsService` / 前端提交字段 | 防止“改了但没生效”；`apply` 会把未提交字段置 null | 无；如果确认是前端 bug，先修前端或直接写 DB |
| `use_skill` 降级 | 即使沙箱配额仍紧，skill 内容也能返回；脚本不可用要明确提示 | 无；`materializeIfPossible` 捕获配额/超时异常，不捕获 `UnsupportedOperationException` 之外的异常 |
| per-user LRU 沙箱回收 | 直接解决“第 N 个沙箱被拒”；用户 A 即使超过 12 个，也能回收最久未活动的空闲沙箱 | 可在 v0.3.10 上做，不依赖 P1；接口要预留，P3 的 Suspend 会替换“释放+重建”语义 |
| 对齐 `maxSessionsPerUser` 与沙箱预算 | 避免“允许 20 个会话、实际只能 5 个沙箱”的隐藏陷阱；即使不做 LRU，也应早失败并给明确提示 | 无；产品上决定 5/10/12 的目标 |
| `attach()` 计入配额 | 防止重连/OCG 绕过预算，导致“看起来没满但 acquire 被拒” | 无；小改动；OCG persistent 单独账本 |
| （可选并行）placement / 软上限 | 不直接解决 quota，但降低 13 vs 1 的热点与单 pod 密度 | 无 adoption 依赖；不要把它当成 quota 修复的替代 |
| （可选并行）SSE/trace payload 字节上限 | 不直接解决 quota，但降低 heap 2GB 级留存和 OOM 风险 | 无 adoption 依赖；可 feature flag，注意 replay gap |

**Q0 的验收**：用户 A 的第 6 个会话能触发工具；`sandboxes_rejected_total` 不再增长；`use_skill` 至少返回 skill 内容；UAT 写入的是 `_id=default` 的 `runtime_max_sandbox_memory_mb_per_user=24576`、`runtime_max_sandboxes_per_user=12`。

### 7.3 不作为当前 quota 修复：留待 adoption 或独立排期

| adoption 工作 | 为什么不现在做 | 依赖 |
|---|---|---|
| **P1：controller v0.3.10 → v0.5.2 → v1.0.9，代码切 v1beta1** | 不解决 quota；升级需要排空沙箱约 75 分钟，且所有 manifest / 4 处 API 常量都要改；先做会和 quota 止血互相干扰 | 无（可与 P0a 并行），但应作为独立窗口 |
| **P1/P3：`operatingMode: Suspended` + `volumeClaimTemplates` + PVC** | Suspend 是原生空闲机制，不是“提高每个用户沙箱预算”；它依赖 v1beta1/P1，且会改变 `/tmp` 语义、TTL、attach/status、archive、配额统计；现在做会造第二套 suspend | P1；需要先压测 Azure Disk/PVC |
| **P4：全局配额按 K8s 标签计数** | adoption D11 明确选择 `core-ai/user-id` 标签 + list + 短 TTL 缓存，而不是 Redis 计数器；它依赖 P1/P3 的资源模型，现在建 Redis 账本会白做 | P1/P3；当前只保留 per-pod + LRU |
| **P1/P4：pod spec 单一来源，删除 `SandboxCRSpecBuilder` / direct CR** | 不影响 quota；依赖 v1beta1 的 template/claim 能力；先删除会打断自定义 image/env 的现有路径 | P1/P4 |
| **P1：`failedPodPolicy`、`service`、`shutdownPolicy: Delete`、`nodeName/serviceFQDN`** | 都是 v1beta1 字段；不解决 quota；现在改 API 版本会引入升级风险 | P1 |
| **P1/P4：claim `env`、多 template、HPA/KEDA、warm pool 弹性、固定镜像 tag** | claim `env` 会强制冷启动，不能恢复 warm pool；多 template 是资源规格治理，不是当前 quota 问题 | P1/P4 |
| **P2：runtime `/bind` token、pre-bind 端点锁死、securityContext、Kata 评估** | 不解决 quota；是安全项。P2 不依赖 P1，但应独立排期，不要混进 quota 修复 | P0b（网络边界）；可与 P1 并行 |
| **P0a/P0b：AKS network policy engine + template/direct 策略** | 不解决 quota；P0a 是平台 node pool reimage，P0b 是安全策略；P0b 不依赖 P1，可独立做 | P0a 平台窗口；P0b 需 dev 回归 |

> 这里说“留待 adoption 或独立排期”是指它们**不是解决用户创建沙箱的前置条件**，不是建议无限期推迟。P0a/P2 是安全关键路径，应按 adoption 文档独立排期；如果只做 quota 修复而长期不做 P0a/P2，会保留“沙箱可访问内网 + runtime 无鉴权”的风险。

**P3 与 quota 的关系**：Suspend 后沙箱不再以 RUNNING pod 形式占用内存，长期看可以减少“活跃沙箱”数量，间接缓解 per-user 预算；但 adoption 文档 D11 要求 RUNNING 只算内存、SUSPENDED 另设 PVC 配额，这需要 P3 一起改。它是 **长期替代 LRU 的原生机制**，不是当前的止血手段。

### 7.4 与本文 4.5 的冲突修正

本文早期草案建议“per-user 沙箱内存改成 Redis 跨 pod 记账”；adoption D11 明确 **不采用 Redis 计数器**，改用 K8s 标签计数。按 adoption 文档为准：

- **现在**：per-pod `activeSandboxes` + 限额 + LRU；不要新建 Redis 全局计数器。
- **adoption 后**：P4 按 `core-ai/user-id` 标签 list claim/CR + 短 TTL 缓存；内存规格按 template → memory 映射，不能继续用 `config.memoryLimitMb`。
- 这样避免先写一套 Redis 账本、P1/P4 再删掉。

### 7.5 现在不要做的“伪解”

- **Redis 全局配额计数器**：D11 已否决；会漂移，还要对账。
- **第二套 suspend/PVC 机制**：P3 会做原生 `operatingMode` + PVC；现在自研一套会与 P1/P3 冲突。
- **为了 quota 提前升 controller / 切 v1beta1**：升级窗口和 75 分钟排空不是 quota 修复的一部分；先做会把两个风险绑在一起。
- **用 claim `env` 注入 token 或改变 warm pool 行为**：claim `env` 强制冷启动（D6/D8），不能恢复 warm pool；token 主路径是 `/bind`。
- **部署 sandbox-router**：D9 明确不部署；router 会剥掉 `Authorization`，与 P2 token 鉴权冲突。
- **把“加副本”当成负载均衡修复**：已有会话不会迁移；副本只有在配合 placement/密度控制时才有意义。

---

## 附录 A：相关代码位置

| 主题 | 文件 |
|---|---|
| 沙箱配额检查 | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxManager.java:96-128` |
| `attach` 绕过配额 | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxManager.java:140-159` |
| `touch` / TTL 时间字段 | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxManager.java:204,220,264-285` |
| skill 物化失败 | `core-ai-server/src/main/java/ai/core/server/skill/ServerSkillTool.java:92-105` |
| LazySandbox 懒加载 | `core-ai-server/src/main/java/ai/core/server/sandbox/LazySandbox.java:226-272` |
| 默认限额 | `core-ai-server/src/main/java/ai/core/server/runtime/RuntimeLimits.java:13-26` |
| 默认沙箱内存 | `core-ai/src/main/java/ai/core/sandbox/SandboxConstants.java:39` |
| 会话本地认领 | `core-ai-server/src/main/java/ai/core/server/messaging/CommandPublisher.java:53-90` |
| 会话 ownership TTL | `core-ai-server/src/main/java/ai/core/server/messaging/SessionOwnershipRegistry.java:21` |
| 空闲会话回收 | `core-ai-server/src/main/java/ai/core/server/session/AgentSessionManager.java:386-410` |
| SSE replay buffer | `core-ai-server/src/main/java/ai/core/server/web/sse/SessionChannelService.java:28,108-122` |
| SSE channel 队列上限 | `core-ai/src/main/java/ai/core/sse/internal/PatchedChannelImpl.java:103-130` |
| trace data URL 压缩 | `core-ai-server/src/main/java/ai/core/server/trace/service/TracePayloadStore.java:38-40,221-248` |
| inline image 裁剪 | `core-ai/src/main/java/ai/core/llm/InlineImagePruner.java:64,223` |
| OCG persistent 沙箱 | `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgSandboxService.java:64-65,128` |
| 限额热加载 | `core-ai-server/src/main/java/ai/core/server/RuntimeGovernanceModule.java:37-45` |
| 限额写入 | `core-ai-server/src/main/java/ai/core/server/runtime/RuntimeSettingsService.java:62-79` |
| settings 读取/写入 | `core-ai-server/src/main/java/ai/core/server/settings/SystemSettingsService.java:49-77,356-357` |
| 运行时统计 | `core-ai-server/src/main/java/ai/core/server/runtime/RuntimeStatsService.java:106-148` |

## 附录 B：诊断记录与注意事项

- live class histogram：`jattach 1 inspectheap`；该命令会统计存活对象并触发一次 full GC，在 93–99% heap 的 pod 上执行有短暂停顿，数据也受采样时点影响。
- JFR：`JFR.start settings=profile` 采样 60 秒后 `JFR.dump`，再用 `jfr view allocation-by-class` / `allocation-by-site` 查看；两套视图的百分比不能相加。
- `jdk.internal.vm.FillerElement` 是 JVM/GC 填充对象，不是业务对象；不要把它算进业务留存。
- `mongosh` 只读查询 `system_settings` 与 `runtime_samples`；`kubectl logs` 用于关联 sessionId、sandboxId 与 `SandboxQuotaExceededException`。
- 本文已对邮箱和完整 sessionId 做脱敏；完整 ID 保留在 UAT 日志/Kibana。

## 附录 C：文档修订记录

- 2026-11-03 v2：按 review 修正以下问题：limit 未生效改为多假设并区分证据等级；补充 Runtime 热更新代码路径；负载不均改为“主要机制 + 连接粘性待验证”；明确 `13` 是 sandboxes_rejected_total；补充 04:15/04:20/04:23 的会话数变化；补充会话标题以证明 6 个 image-edit；说明 inline image 138MB 只覆盖 history；拆分 JFR class/site 两张表；明确 `createdAt` 需要拆成 `createdAt` + `lastAccessAt` 并保持 TTL 行为；补充 LRU 快照失败、回收-重建循环、后台进程、锁/账本一致性；补充 placement 原子预留与计数对账；修正 P2 验收容量；拆分“加副本”与“placement”的前提；补充风险/回滚、量化 after-GC、时区说明和脱敏。
- 2026-11-03 v3：结合 `design-agent-sandbox-native-adoption.md` 增加第 7 节，区分“现在解决 quota 的 Q0/Q1”和“留待 adoption 或独立排期的 P0a/P0b/P1–P4”；按 adoption D11 修正 4.5 的 Redis 跨 pod 记账方案，改为当前 per-pod + LRU、后续 K8s 标签计数。
