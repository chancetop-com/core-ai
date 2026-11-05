# Tech Design：会话记录（Session Transcript）——统一 live 与重载的单一真相源

> 状态：草案 v0.8（v0.7 于 2026-11-04 补 completed compression 的水位基点；v0.8 于 2026-11-05 修正 rewind 的作用区间：只丢弃 `to_seq ≤ turn_start.seq < rewind.seq` 的轮，rewind 之后追加的轮保留）
> 作者：Stephen（AI 起草）
> 关联（以下文档暂未纳入本站）：design-core-ai-desktop.md §3.4 / §9.2 第 19 条、design-cli-session-history-context.md、design-session-custom-events.md §3.4、design-session-rich-cards.md
> 版本事实：desktop 0.1.38 / CLI v2.0.63 / server 2.0.152。正文行号均为快照 commit `de1b45cb`（`app.ts` 7,316 行，`wc -l`），以函数名为准。
> 实现状态（2026-11-02）：P0/M1 主体已落地 — `ai.core.session.transcript`（recorder/store/projection/reader）、两宿主注入、`session/records`/`session/recordPayload`/`session/record`、`session/stats`、`session/undo`、桌面 records 渲染与分页、CLI `/resume` 记录回放、`--debug-transcript`。验收：`:core-ai:test`、`:core-ai-cli:test`、`npm --prefix core-ai-desktop test`（144）与 `python core-ai-cli/scripts/appserver-e2e.py`（records/steer/restart/projection/undo/failure/large/gap/dangling）全绿。未完成：矩阵/等价性脚本与性能实测；task_note 轮仅单测覆盖；ACP 与桌面 undo UI 为 P1。Java `AppServerTest` 仍只测 transport，引擎级验收以入库脚本为准。

## 0. 决策摘要

| # | 决策点 | 建议 | 备选与理由 |
|---|--------|------|-----------|
| D1 | 真相源 | **append-only** `~/.core-ai/sessions/{ws}/{sessionId}.events.jsonl`，每行一条带 `seq/turn_id/ts/kind` 的记录；`.data` 不变，继续承担 LLM 上下文 | 扩展 `.data`：轮末整文件重写；SQLite：native-image 多本地库 |
| D2 | 唯一写入者 | **`TranscriptRecorder` 是唯一写入者**：会话与 `EngineSession` 持有 recorder，经 `sessionStart / beginTurn(inputs, source) / steerPending / steerCommit / steerCancel / approval / completeTurn(event) / failTurn(error, usage) / rewind(toSeq) → usage / close` API 写入；它同时实现 `AgentEventListener`（消费 chunk、工具、plan、custom 等非终止事件）；**轮级终止只经 `completeTurn / failTurn` API，不经 `onTurnComplete / onError` 监听器**；seq / turn_id / ts / 缓冲 / 锁由它独占；构造 `(store, metadata, LongSupplier messageCount, sink)`；`TranscriptStore` 是纯文件层 | 会话直写 store：绕过缓冲；以 `onError` 判终止：`SessionStreamingCallback.onError` 也发 `ErrorEvent`，且 CLI 从不传 turnId，无法区分（v0.5 缺陷） |
| D3 | 记录粒度 | 事件级，chunk 折叠；**所有记录以文件 seq 保序**，模型用单一 `items[]` 承载交错 | 服务端"按轮装桶" |
| D4 | live 与重载同构 | 引擎落盘即推 `session/record`；桌面 reducer 只消费记录；**块级替换**（sealed/active） | 轮末整体替换：text → tool → text 中段两份正文 |
| D5 | 统计 | **单一公式**：resume 时 `seed = Σ 未被 rewind 的轮级终止记录 usage`，`Node.currentTokenUsage = seed`；`session/stats` **直接返回 `agent.getCurrentTokenUsage()`**；**运行中 `/undo` 必须 `Node.subtractTokenUsage(被撤销轮的 usage)`**（公式成立的前提，非可选）；`turns = turn_start 数 − rewind 轮数` 从记录数 | "记录累加 + 内存增量"：双算（v0.3 缺陷） |
| D6 | 旧会话 | 读时投影 base 段 `.data.messages[0, base.projection_through)`；**base = 最新一条 `projection_reset` 的 `session_start`，若无则第一条无 `projection_from` 的**；**gap 段在打开时物化进 `.events.jsonl`**（`synthetic:true`，真实文件 seq）；水位 `last` = **最后一条轮级终止记录**（`turn_complete` 或 `turn_id != null` 的 `error`）的 `message_count`；**若其后有 `completed` 的 `compression`，则以该记录的 `after_count` 为基点，只统计 seq 在它之后的轮级终止记录**（压缩会截短 `messages`，压缩前的 `message_count` 不能再用于判定 reset）；指纹只用于 reset 校验，不参与 gap 判定 | 负 seq 投影 gap：排序错且重复写（v0.3）；只认 `turn_complete`：失败轮无水位，重启后再物化一遍（v0.4 缺陷） |
| D7 | 有界性 | **所有字符串载荷字段**都遵循 "> 32KB 外置为 `*_ref` + `*_preview`"（含 `inputs[].text`、`assistant_text.text`、`reasoning`、`arguments`）；单条 ≤ 64KB；`session/records` 按整轮分页 ≤ 2MB / 1000 条，单轮超预算 → 半轮 `partial` | — |
| D8 | 兼容 | `v:1`；未知 `kind` 忽略；尾行截断丢弃；`capabilities.add("transcript")`；`session/history` 与 `renderHistoryMessages` 在 gate 内保留至 CLI 2.0.70 | — |
| D9 | 范围 | P0 = 内核 + app-server RPC + 桌面；M1 含 CLI `--debug-transcript`、`/undo` 接 recorder、store 关闭；P1 = CLI `/resume`、ACP、桌面 undo UI | — |
| D10 | 轮次主键 | **`turn_id` 永远由 recorder mint**（`t{turn_start.seq}`）；客户端 id 逐条进 `inputs[].client_turn_id`；**`session/event` 里 `turn_complete.turn_id` 仍是客户端值**（IM 通道靠它匹配），与记录的 `turn_id` 不是同一个值 | 沿用客户端值：batch 只保留首个 id |
| D11 | 轮内追加输入 | **两阶段 steer，pending 先于 offer**：`sendMessage` 在有运行轮时**先** `seq = recorder.steerPending(...)`（flush 正文 + 在发送位置写 `steer{pending}`），**再** `turnInput.offer(..., steerSeq)`；offer 返回 false → 立刻 `steerCancel(seq)`，消息照旧进 `commandQueue` 成为下一轮 `inputs[]`；`inject` → `steerCommit(seq)`；`end` 重新入队 → `steerCancel(seq)`。**崩溃恢复**：`sessionStart` 扫描尾部，未闭合的 `pending` 补写 `cancelled`。reducer：pending 即渲染，cancelled 移除 | offer 成功后再写 pending：agent 线程可能已 inject/end，`ref_seq` 为空甚至 cancel 先于 pending（v0.5 竞态） |
| D12 | undo 与删除 | `/undo` 追加 `rewind{to_seq, message_count}`；**目标是投影轮时不写 rewind**，靠 `.data` 截短 + 下次打开按 `projection_reset` 重算；删除会话先 `store.close()` 再递归删 `.events.jsonl` 与 `.blobs/` | 截断文件 |

评审重点（v0.4）：D5 公式、D6 gap 物化、D11 写入点、§3.7 投影分类（G4）。D1–D4/D10/D12 三轮评审已认同。

### 0.1 修订记录

**v0.8（2026-11-05，实现修正）** rewind 的区间是 `[to_seq, rewind.seq)`，不是 `≥ to_seq`：一条 rewind 只撤销它之前已经存在的那段历史，rewind 记录之后追加的新轮是新历史。按旧口径，任何一次 rewind 都会把此后所有轮次判为已撤销——桌面只剩 rewind 之前的轮，启动重算水位时还会把整段已存在的对话当 gap 重新物化一遍，造成重复轮次。`TranscriptReader`、`TranscriptStartup`、`TranscriptRecorder` 统一改用 `TranscriptRewind` 的窗口判断；单测 `TranscriptReaderTest.keepsTurnsAppendedAfterARewind` 与 `TranscriptRecorderTest.doesNotReplayTurnsAppendedAfterARewindOnStartup`。

**v0.7（2026-11-04，实现修正）** D6/§3.7：`completed` 的 `compression` 成为水位与 reset 的新基点——`last` 取 `after_count`，只统计其后（`seq > compression.seq`）的轮级终止记录；`size < after_count` 时 reset 从压缩轮起跳。否则压缩后重启会把压缩前的 `message_count` 当成当前水位，误判历史被外部截短并写 `rewind`/`projection_reset`，丢掉压缩前所有轮次。单测 `TranscriptRecorderTest.keepsTurnsWhenACompletedCompressionShortenedTheConversation`。

**v0.6（第五轮评审）** 采纳 S1 `steerPending` 先于 `offer`、offer 失败即 cancel、RPC 线程与 agent 线程的窄竞态进 §6；S2 轮级终止改为 `completeTurn / failTurn` API（`turn_id` 用 recorder 的 `currentTurnId`，usage 用 `SessionTurnUsage`，`onError` 监听器不参与水位）；S3 `task_note` 并入 `turn_start{source:"task_note"}`，天然可分页、可 rewind、可计数；S4 `sessionStart` 补写 dangling pending 的 `cancelled`；S5 `rewind(toSeq)` 返回被撤销轮 usage；S6 `total_turns` 公式写全并与 `stats.turns` 共用；S7 `LargeText` 的 ref 保留原字段名；§7.1/§7.3 补 CLI 无 turnId 失败轮、task_note 轮 `/undo`、dangling pending 用例；§9 Q1 接受、Q2 定为"丢弃 + 提示"。

**v0.5（第四轮评审）** 采纳 A 两阶段 steer（D11，`steerPending / steerCommit / steerCancel`）与 pending 气泡按 `queued` 区分；B `error` 记录带 `message_count` + usage，水位改为"最后一条轮级终止记录"，`QUEUE_FULL` 等 `turn_id = null` 的 error 不参与；C base 取最新 reset，reset 的 `rewind` 与 `session_start` 同锁内先后写；D recorder 经 `ExecutionContext` key 进 `PendingUserInputInjector`；E `Node.subtractTokenUsage` 写进 D5/§5；F 指纹只用于 reset 校验；G 投影 `task_note` 后补 `turn_complete` 且补 `turn_id` 规则；H `Item` 统一 `textRef/textPreview` + "加载全文"动作；`notice.level` 扩为 `system | compression`；`partialTurns[{turnId, side}]` 与 head-partial 排序键；task_note 去重 selector；`AgentInterruptionHandler.isInterruptionMarker` 提为 public；gap 物化预算；§7.3 补失败轮用例。§9 删除 Q3/Q4（已定）。

**v0.4（第三轮评审）** 采纳 G1 gap 物化 + base 取第一条无 `projection_from` 的 `session_start`；G2 `LongSupplier messageCount` 进构造与 API；G3 stats 单一公式、forced `turn_complete` 前先 save；G4 投影 USER/TOOL 分类（中断标记 → system notice、任务通知 → `task_note`、`memory_compress` → compression notice）；H1 `itemKey = seq:ordinal`；H2 `task_status` 未知 → `pendingTaskStatus`，补 task note 去重；H3 投影轮 undo 不写 rewind，`handleUndo` 接 recorder；H4 `TranscriptStore.close()` + 递归删目录；H5 steer 写入点移到 inject；H6 任何 terminal 后丢弃迟到 chunk；H7 `*_ref` 贯穿所有字符串字段；H8 `max_seq / total_turns / partial / source` 定义；事实修正（`:882 discardEmptyTurnBubble`、memory 挂载 `:4135`、steering `:3808`）；`message_count` 明确 end-exclusive；`rewind.turn_id = null`；memory 矩阵改 🟡；fixture 补 task_status 跨轮、投影特殊消息、gap 排序、partial 页。

**v0.3** recorder 唯一写入者与初始化顺序；`items[]` + `taskIndex`；消息计数水位、按页 source；`inputs[]`；sealed/active 块；gate 内保留旧 fallback；`rewind.to_seq`；字段表修正。**v0.2** 轮次主键、水位线、乐观气泡、steer、字段逐字、模型容器、线程模型、切分规则、rewind/删除、`output` 兜底、capabilities 数组、整轮分页、`task_note`、外置、投影 join。

---

## 1. 背景与问题

### 1.1 现象

桌面重开会话后 thinking、Tool use、Skill use、Memory、轮次统计、计划、压缩提示全部消失，只剩纯文本气泡；CLI `/resume` 同样。

### 1.2 根因（按代码核实）

| 路径 | 数据来源 | 客户端入口 | 信息量 |
|------|---------|-----------|--------|
| live | `EventType` 16 项（含 `custom`）+ `on_tool`，即 `AgentEventListener` 17 个方法，经 `EventBridge` 推 `session/event` | `applySessionEvent`（`app.ts:4592`） | 完整 |
| 重载 | `agent.getHistory()`：只记 USER/ASSISTANT 非空文本（`NodeMessages.recordHistory:68`） | `session/history` 只输出 `{role,text}`（`EngineSessionRegistry.history:144`）→ `renderHistoryMessages`（`app.ts:3618`） | 文本 |

| 信息 | 是否已在 `.data` | 丢失点 |
|------|----------------|--------|
| thinking | 在（`reasoning_content`） | RPC 只输出 text |
| tool 调用 | 调用在 `tool_calls`；结果只在 LLM 上下文，会被压缩；纯工具轮不进 history | 记录规则 + RPC |
| skill | = `use_skill` 调用 | 同上 |
| memory | 不在：`custom` live-only | 内核无容器 |
| 统计 | 不在：`Node.currentTokenUsage` 纯内存 | 重载归零 |
| plan / compression / 审批 / 取消 / 错误 / 时间戳 / 模型 | 全不在 | 从未记录 |
| steering | 在（`recordHistory`） | 今天可见，不单独记录即回归（D11） |
| 一次 batch 多条用户消息 | 在（拼成一条） | `turnId` 只取首条（`executeCommands:122`）（D10） |

### 1.3 原始素材统计（本机 799 个 `.data`，三轮评审复算一致）

| 指标 | 值 |
|------|----|
| 会话文件数 | 799（无 `history` 的旧文件 369） |
| 大小 p50 / p90 / max | 332 KB / 2.0 MB / 21.2 MB；单文件最多 1567 条 messages |
| messages 角色 | tool 69,943 · assistant 58,362 · user 3,930 · system 773 |
| assistant 带 `reasoning_content` / `tool_calls` | 45,160 / 55,470 |
| **无文本的 assistant（永远进不了 history）** | **40,230 / 58,362** |
| history 合计 | 17,672（约 13%） |

### 1.4 渲染层现状（快照 de1b45cb）

- `app.ts` 7,316 行；`SessionView`（约 `:610`）直接持 DOM 句柄与 `toolMap/taskMap/pendingTaskStatus/pendingMemoryRun`，无视图模型层。
- 用户气泡在 `session/send` 前乐观创建（`:3798`）；steering 发送时 `resetStreaming` 封泡（`:3808`；`:3820` 是发送失败的回滚）；`session/send` 只传 `parts`（`:3817`）。
- `addMessageBubble` 已对 USER 文本分类：`systemReminderText`（`:3546`）→ 系统提示行；`parseTaskNotifications`（`:3571`）→ task note。
- memory 芯片挂到 `view.streaming.bubble` 所在轮，即**正在运行**的轮（`memoryWrapper`，`:4135-4136`）。
- `handleTaskStatusEvent`（`:4401`）：未知 taskId 进 `view.pendingTaskStatus`，等任务行出现再应用；`downgradeDuplicateTaskNotes`（`:4427`）去重 task note 与任务行。
- 未闭合工具在 `turn_complete` 由 `resolveStaleToolStatuses` 置 cancelled（`:3900`）；加载期事件进 `view.pendingEvents`；`discardEmptyTurnBubble`（`:882`）清空轮气泡。

### 1.5 先例

服务端 `chat_messages`（按轮装桶，丢交错）；Claude Code `~/.claude/projects/*/*.jsonl`（每行带 uuid/parentUuid/timestamp/version，内容块顺序保留）；Codex rollout + `thread/resume` items。共同点：append-only、事件级、live 与重载共用渲染路径。

---

## 2. 目标 / 非目标

目标：① append-only 记录覆盖 16 项事件 + `on_tool`（瞬时层）+ 已注册 `custom` + 无事件的用户输入；② live 与重载**块级**等价；③ 旧会话无损降级且比今天多显示 thinking/工具，新旧拼接不重复、不遗漏、`/undo` 不丢旧历史、gap 段排在记录之后；④ 验收由机器判定。

非目标：不改服务端；不重做视觉；不新增事件类型；不删 `.data.history`；子代理不单独记录；桌面 undo UI 为 P1。

---

## 3. 设计

### 3.1 总览与初始化顺序

```
宿主 build(sessionId, load):
  1. agent = …; if (load) agent.load(sessionId)
  2. store    = TranscriptStore.open(sessionsDir, sessionId)
  3. recorder = new TranscriptRecorder(store, metadata, () -> agent.getMessages().size(), sink)
     recorder.sessionStart()            ← 内部：读 base session_start / 计算 gap / 必要时物化 gap（§3.7）
  4. inProcess = new InProcessAgentSession(agent, …, recorder)
  5. inProcess.onEvent(new EventBridge(sessionId, sink)); inProcess.onEvent(recorder)
  6. engineSession = new EngineSession(sessionId, agent, inProcess, recorder, …)
  关闭：inProcess.close() → recorder.close()（flush、forced turn_complete、store.close()）→ 之后才允许 delete

TranscriptRecorder（唯一写入者，一把锁）
  ├─ API：sessionStart()（含 dangling pending 补 cancelled）/ beginTurn(inputs, source: "user" | "task_note", model, thinkingLevel)
  │        / steerPending(input) → seq / steerCommit(seq) / steerCancel(seq)
  │        / approval(callId, decision) / completeTurn(TurnCompleteEvent) / failTurn(ErrorEvent, Usage)
  │        / rewind(toSeq) → Usage / close()
  │   轮级终止只走 completeTurn / failTurn；onTurnComplete / onError 监听器不写终止记录
  │   recorder 同时以 TranscriptRecorder.CONTEXT_KEY 放进 agent.getExecutionContext()（同 CustomEventEmitter.CONTEXT_KEY 做法），
  │   供 ai.core.agent 包内的 PendingUserInputInjector / SessionTurnInput 取用
  ├─ AgentEventListener：折叠 chunk、切分 assistant_text、盖章 seq/turn_id/ts、取 messageCount
  └─ 每条记录：store.append → sink.accept(record)
```

### 3.2 记录格式（`v:1`）

公共字段（recorder 盖章）：

| 字段 | 说明 |
|------|------|
| `v` | 1 |
| `seq` | 文件记录 ≥ 1 单调递增（含物化的 gap 合成记录）；**base 投影记录为合成负 seq，不落盘** |
| `ts` | 写入时刻 |
| `turn_id` | `t{turn_start.seq}`；轮外记录（`session_start`、`memory`、`task_status`、晚到 `artifact`、**`rewind`**）为 null |
| `kind` | 见下表 |
| `synthetic` | 可选，`true` = 由 `.data` 物化的 gap 合成记录（§3.7） |

**载荷字段名逐字沿用源事件 `@Property` 名**（混合命名不归一）。注意：这只对载荷成立，`turn_id` 不是同一个值（D10）。**所有字符串字段**遵循 D7：> 32KB 时该字段缺省，改为 `{field}_ref`（blob 路径）+ `{field}_preview`（前 4KB）。

| kind | 来源 | 载荷 | 备注 |
|------|------|------|------|
| `session_start` | `recorder.sessionStart` | `workspace, engine_version, client, model, projection_through, projection_fingerprint, projection_from?, projection_reset?` | 每次打开一条；**base = 第一条无 `projection_from` 的**；后续仅记录 gap；reducer 忽略 |
| `turn_start` | `beginTurn`（`executeUserInput` / `executeTaskNotifications` 第一次 dispatch 前） | `source: "user" \| "task_note", inputs[{text, attachments[{filename, mediaType, size, blob_ref}], client_turn_id}], model, thinking_level, message_count_before` | batch N 条 → N 个 input；**任务通知轮 = `source:"task_note"`、`inputs:[{text: XML}]`**，之后照常 `turn_complete`，天然有 `t{seq}`、可分页 / rewind / 计数；`message_count_before` 由 supplier 取 |
| `steer` | `steerPending`（**`turnInput.offer` 之前**）/ `steerCommit`（`PendingUserInputInjector.inject`）/ `steerCancel`（offer 失败、`SessionTurnInput.end` 重新入队、`sessionStart` 发现 dangling） | `state: "pending" \| "committed" \| "cancelled", text, attachments[], client_turn_id, ref_seq?` | pending 一条完整记录（写前 flush 正文）；committed/cancelled 只带 `ref_seq`；`steerSeq` 与 `turnId` 穿过 `PendingUserInputQueue.PendingUserInput` |
| `assistant_text` | `text_chunk` 折叠 | `text` | |
| `reasoning` | `reasoning_complete` | `reasoning, durationMs` | |
| `tool_start` | 同名事件 | `callId, toolName, arguments, diff, taskId, run_in_background, model, toolType` | |
| `tool_result` | 同名事件 + 同 callId 的输出尾巴 | `callId, toolName, status, result, durationMs, taskId, toolType` | |
| `tool_approval` | 请求事件 / `approval` | `callId, toolName, suggestedPattern, decision` | 两条 |
| `batch_tool_start` | 同名事件 | `group, tools[], task_id` | |
| `plan_update` | 同名事件 | `todos[]` | |
| `compression` | 同名事件 | `before_count, after_count, completed, context_tokens, max_context_tokens, trigger_threshold` | |
| `sandbox` | 同名事件 | 原载荷 | |
| `task_status` | 同名事件 | `taskId, toolName, status` | |
| `custom` | 同名事件 | `name, data, card, callId` | |
| `turn_complete` | 同名事件 | `output, cancelled, max_turns_reached, duration_ms, input_tokens, output_tokens, cached_tokens, cost_usd, budget_*, message_count` | `message_count` = supplier 在 **save 之后** 取的 `messages.size()`（end-exclusive 计数） |
| `error` | `failTurn`（轮级终止）/ `onError` 监听器（非终止） | `terminal: boolean, message, detail, message_count?, input_tokens?, output_tokens?, cached_tokens?, cost_usd?` | **`terminal:true` 只由 `failTurn` 写**（`InProcessAgentSession` 的 `catch → persistOnFailure → dispatchTerminal` 路径直接调用）：`turn_id` = recorder 的 `currentTurnId`，`message_count` 由 supplier 在 save 后取，usage 由 `SessionTurnUsage` 计算；`onError` 监听到的（含 `SessionStreamingCallback.onError`、`QUEUE_FULL`）写 `terminal:false`，不参与水位。**不看事件里的客户端 `turn_id`** |
| `notice` | 仅投影/物化产生 | `level: "system" \| "compression", text, before_count?, after_count?` | `system` = 中断标记等系统提示；`compression` = 投影中的 `memory_compress` 调用与结果（G4）；`turn_id` 随所在轮，轮外为 null |
| `rewind` | `rewind(toSeq)` | `to_seq, message_count` | `turn_id = null`；reducer 丢弃 `to_seq ≤ turn_start.seq < rewind.seq` 的 turn（rewind 记录之后追加的轮保留） |

不落盘：`text_chunk`、`reasoning_chunk`、`environment_output_chunk`、`on_tool`、`status_change`。

### 3.3 内核：`TranscriptRecorder` / `TranscriptStore`

位置 `ai.core.session.transcript`：`TranscriptRecorder`、`TranscriptStore`（append / 扫描索引 / blob / `close()`）、`TranscriptRecord`、`TranscriptMetadata`（宿主注入 `engine_version / client / model / thinking_level` 供给函数；app-server 用 `thinking/get` 的值，CLI 用自身配置）、`TranscriptProjection`、`TranscriptKinds`。每类 ≤ 450 行。

**挂载**：`InProcessAgentSession` 可选参数 `TranscriptRecorder`（null = 现状）。顺序见 §3.1；`resume` 对 live 会话 `computeIfAbsent`。

**调用点**：`executeUserInput` → `beginTurn(inputs = 每条 QueuedMessage 的 {text, attachments, turnId}, source:"user")`；`executeTaskNotifications` → `beginTurn(inputs = [{text: xml}], source:"task_note")`；`sendMessage` 在运行轮存在时 **先** `seq = steerPending(...)` **再** `turnInput.offer(..., seq)`，offer 返回 false → `steerCancel(seq)` 并照旧入 `commandQueue`；`PendingUserInputInjector.inject`（经 `ExecutionContext` 取 recorder）→ `steerCommit(seq)`；`SessionTurnInput.end` 重新入队 → `steerCancel(seq)`；`approveToolCall` → `approval`；正常结束 `dispatchTerminal(turnComplete)` 之前 → `completeTurn(turnComplete)`；失败路径 `catch → persistOnFailure → failTurn(errorEvent, SessionTurnUsage.of(agent, before)) → dispatchTerminal(errorEvent)`；`EngineSession.undo` 与 `AgentSessionRunnerCommandHandler.handleUndo`（`:206`，需接入 recorder）→ `usage = rewind(toSeq)` → `Node.subtractTokenUsage(usage)`；会话关闭 → `close()`。`sessionStart` 扫描尾部：最后一个 `steer{pending}` 若无 committed/cancelled → 补写 `cancelled`（崩溃恢复，S4）。

**turn_id**：`beginTurn`/`taskNote` mint `t{seq}` 记 `currentTurnId`；terminal 后置 null。

**线程与顺序**：`dispatch` 不单线程；**记录顺序 = recorder 锁顺序**。API 与事件同一把锁。**任何 terminal（`turn_complete`/`error`/forced）之后到达的 chunk 一律丢弃并计数**，不进入下一轮缓冲（H6）。`close()`：flush → 若有 open turn，**先 `agent.save()`** 再写 forced `turn_complete{cancelled:true, message_count}`（否则下次打开 `size < last` 误触 reset）→ `store.close()`。

**切分白名单**（只对 live 会封泡的事件切）：`tool_start`、`tool_result`、`batch_tool_start`、`reasoning_complete`、`plan_update`、`tool_approval`、`custom` 中 `quick_replies` 与带 `card` 的、`steer`、`turn_complete`、`error`。不切：`task_status`、`custom:memory`、`custom:artifact`、`compression`。

**折叠**：输出尾巴按 callId 累加 ≤ 32KB，`tool_result` 时 `result` 为空或不含则并入。

**文件 IO**：`O_APPEND` 单次 `write`，不 fsync；resume 扫描只解析行首 `seq/kind/turn_id/synthetic` 与 `turn_complete.message_count`、`rewind.to_seq`：建索引、算 `seed`（§3.6）。**`TranscriptStore.close()` 关 `FileChannel`**（Windows 上打开中的文件不能删）。

**删除**：`EngineSessionRegistry.delete` → `live.close()`（含 store）→ `FileSessionPersistence.delete`：删 `.data`、子代理文件、`.events.jsonl`，**递归**删 `.blobs/`。

### 3.4 app-server 协议

| 方法 / 通知 | 参数 | 返回 / 语义 |
|------------|------|------------|
| `session/records` | `{sessionId, beforeSeq?, turns? (默认 30), maxRecords? (≤1000)}` | `{records[] (seq 升序), total_turns, max_seq, next_before_seq?, partial, oversized, source}`。**`max_seq` = 会话文件当前最大 seq**（不是本页），只用于首屏缓冲去重；**`total_turns`（与 `session/stats.turns` 共用同一函数）= 文件内 `turn_start` 数（含 `source:"task_note"` 与 synthetic）+ base 投影的 `turn_start` 数（含 task_note）− 被 rewind 的轮数；head-partial 轮按 1 计**；`partialTurns: [{turnId, side: "head" \| "tail"}]`（替代布尔 `partial`）= 页内未完整加载的轮；**head-partial 轮没有 `turn_start.seq`，排序键取该轮本页首条记录的 seq**，`turnId` 取记录自带的 `turn_id`；**`source` 按本页**：全为 base 投影或 `synthetic` → `projection`，全为真实记录 → `transcript`，混合 → `mixed`；`oversized:true` = 文件 > 50MB，只服务最近 1 万条。页边界对齐 `turn_start`，超预算减轮数，单轮仍超 → 半轮 `partial` |
| `session/record` 通知 | `{sessionId, record}` | 落盘后同步推送 |
| `session/recordPayload` | `{sessionId, seq, field, index?}` | `{text?, path, size}`；路径必须在**该 sessionId** 的 `.blobs/` 内 |
| `session/stats` | 不变 | **= `agent.getCurrentTokenUsage()`**（seed + 运行增量，§3.6）；`turns` 同 `total_turns` |
| `session/send` | `parts, turnId?` | 桌面每条传 `turnId: "d-" + uuid` → `inputs[].client_turn_id` |
| `session/history` | 不变 | deprecated，CLI 2.0.70 删除 |
| `initialize` | — | `capabilities.add("transcript")` |

加载期：首屏 `session/records` 返回前的 `session/record` 按 seq 缓冲，返回后丢弃 `seq ≤ max_seq`，其余补放。"加载更早"合并后**从全量重建模型**。

### 3.5 桌面渲染层

`src/renderer/transcript/`：`records.ts`、`reducer.ts`、`classify.ts`（迁出 `toolUseKind / skillNameFrom / subagentLabelFrom / parseTaskNotifications / systemReminderText`）、`selectors.ts`。

```ts
interface TranscriptModel {
  turns: Turn[];                              // 按 turn_start.seq
  taskIndex: Map<taskId, turnId>;
  pendingTaskStatus: Map<taskId, status>;     // 任务行尚未出现的状态（镜像 live 的 view.pendingTaskStatus）
  orphans: Item[];
}
interface Turn { turnId; sortSeq /* turn_start.seq，head-partial 取首条记录 seq */; source: 'transcript' | 'projection'; items: Item[]; plan; summary; open; partial: 'none' | 'head' | 'tail' }
type Item = { key: string /* `${seq}:${ordinal}` */; seq; … } & LargeText
// 所有带大文本的字段统一三件套：显示文本用原字段名，ref / preview 保留原字段名后缀，与记录字段和 recordPayload.field 一一对应：
//   input.text / textRef / textPreview；reasoning.reasoning / reasoningRef / reasoningPreview；
//   tool.arguments / argumentsRef；tool.result / resultRef / resultPreview；custom.data / dataRef；custom.card / cardRef
// *Ref 存在时 UI 显示 preview + "加载全文"（调 session/recordPayload{seq, field}），reducer 不做字段改名
```

reducer 规则：

- `session_start` 忽略。`turn_start` → 新 turn（`source` 透传），`inputs[]` 每项一个 `input` item（`key = seq:i`）；`source:"task_note"` 的 turn 其 input 用 `parseTaskNotifications` 渲染为 task note，不作用户气泡；`steer{pending}` → 当前 open turn 的 `input{steering:true, seq}`，`steer{committed, ref_seq}` 只改状态，`steer{cancelled, ref_seq}` 移除该 item；**selector 规则**：`taskIndex` 命中同 taskId 的任务行时隐藏该 task_note turn 的 input（镜像 `downgradeDuplicateTaskNotes`）。`error{terminal:false}` → `notice{level:error}`，不关轮；`error{terminal:true}` 关轮并追加 notice。
- 乐观气泡：以 `client_turn_id` 为 key 创建 pending；被 `turn_start.inputs[]` 或 `steer{pending}` 认领即转正；`session/send` 返回 `queued:true` 的消息**保留到下一轮 `turn_start` 认领**，不在当前轮结束时标失败；只有 `session/send` 失败或 `engine/ready`（引擎重启）才标"未送达"。
- `tool_start` → `tool` item；`run_in_background`/`taskId` 非空 → `taskIndex[taskId] = turnId`，并消费 `pendingTaskStatus[taskId]`；`tool_result` 按 callId 合并。
- `task_status`：经 `taskIndex` 回写起始轮；**找不到 → `pendingTaskStatus`**，不挂当前轮（H2）。
- `turn_id = null` 的 `memory`/晚到 `artifact` → seq 之前最近的 turn（含 open）。
- `turn_complete`/`error` 关闭 turn：running `tool` 置 cancelled；无 `text` item 且 `output` 非空 → 合成 `text`；`error` 追加 `notice`。`notice{level:system}` 原样为 item。
- `rewind{to_seq}` 删除 `to_seq ≤ turn_start.seq < rewind.seq` 的 turn 及其 `taskIndex`；rewind 记录之后追加的轮属于新历史，保留。
- 渲染时相邻 `text` 合并为一个气泡。

**sealed vs active**：`TurnDom` 按 `(turnId, key)` 存块；流式期间唯一 active 块（text 或 reasoning）+ running 工具卡；记录到达即替换对应 active 节点并清瞬时缓冲；`turn_complete` 只管 summary/结束态/stale。`reasoning_complete` 留在瞬时层封块。乐观气泡认领规则见上文 reducer 规则。

`applySessionEvent` 只保留：三类 chunk、`reasoning_complete`、`tool_approval_request`、`status_change`、`on_tool`。`renderHistoryMessages` 留在 gate 内。估算：新增约 950 行，`app.ts` 删约 1,000 / 改约 450。

### 3.6 统计与 Memory

- **公式**：resume 扫描 `seed = Σ(未被 rewind 的轮级终止记录——turn_complete 与 error{terminal:true}——的 input/output/cached tokens、cost)`，`Node.seedTokenUsage(seed)`；运行中 `Node` 照常累加；**运行中 `/undo`：`usage = recorder.rewind(toSeq)`（store 从该轮终止记录取）→ `Node.subtractTokenUsage(usage)`**；`session/stats` 与 CLI `/stats` = `getCurrentTokenUsage()`，`turns` 复用 `total_turns` 函数。不存在第二个累加源。
- 每轮小结：`turn_complete` 记录 → `summary`。
- Memory：`custom:memory` 归 seq 之前最近的 turn（含 open）。**只在引擎会话存活期间**能进记录（`publishMemoryActivity` 只对 live session 分发），会话关闭后的报告丢失（矩阵 🟡）。

### 3.7 旧会话投影、水位线与 gap 物化（D6）

1. **base**：第一次接触会话时 `sessionStart` 写 base `session_start{projection_through = size, projection_fingerprint = hash(messages[through-1])}`。**base 的取法：若存在 `projection_reset:true` 的 `session_start`，取最新一条；否则取第一条无 `projection_from` 的。** base 段 `messages[0, base.projection_through)` 读时投影，seq 为负数，不落盘。
2. **`last`**：最后一条未被 rewind 的**轮级终止记录**（`completeTurn` 写的 `turn_complete`，或 `failTurn` 写的 `error{terminal:true}`，含 synthetic）的 `message_count`；若没有任何轮级终止记录，`last = base.projection_through`。`error{terminal:false}` 不参与；判定不看事件的客户端 `turn_id`。**压缩基点**：若存在 `completed:true` 的 `compression`，取最新一条，令 `last = max(上面的结果, compression.after_count)`，并且只扫描 seq 在该 compression 之后的轮级终止记录——压缩前的记录描述的是压缩前那段上下文，拿它们的 `message_count` 与压缩后的 `size` 比较会把正常压缩误判为历史被截短。
3. **gap 物化**（每次 `sessionStart`，与 reset 判定同一把锁）：`size = messages.size()`。**gap 只判 `size > last`，不比指纹**（`messages[last-1]` 的指纹没有存储位置，F）。若 `size > last` → 先写 `session_start{projection_from = last, projection_through = size}`，再把 `messages[last, size)` 投影为合成记录**用真实文件 seq 追加**（`synthetic:true`）；合成末轮 `turn_complete.message_count = size`，`last` 前推，下次不重复。**reset**：若 `size < last`，或 `size == base.projection_through` 但 `messages[base.through-1]` 指纹不匹配 → 同锁内先写 `rewind{to_seq = 第一条 message_count > size 的轮级终止所在轮的 turn_start.seq}`（有压缩基点时只在这些基点之后的记录里找），再写 `session_start{projection_reset:true, projection_through = size, projection_fingerprint}`（它成为新 base），不再物化；接受该段可能失真。**压缩后的 reset 特例**：若 `size < compression.after_count`，说明 `.data` 比压缩后的上下文还短，reset 直接从该 compression 所在轮的 `turn_start.seq` 起跳。**预算**：单次物化 ≤ 2,000 条 messages 同步完成，超出部分记 `projection_from`/`through` 分批，首次 resume 只物化最近 2,000 条，其余在首轮结束后后台补（M1 先量实际耗时，§3.8）。
4. **投影算法**（base 与 gap 共用 `TranscriptProjection`），**复用 live 的 USER/TOOL 分类**（G4）：
   - USER 全为 `<system-reminder>` 包裹（`AgentInterruptionHandler.isInterruptionMarker`，需提为 public）→ `notice{level:system}`，不开轮，`turn_id` 随前一轮（无则 null）；
   - USER 为 `<task-notification>` → 先关掉上一轮（补 `turn_complete`），写 `turn_start{source:"task_note", inputs:[{text}]}`，随即补 `turn_complete`（无 usage）；与普通轮同一套 id / 分页 / rewind / 计数规则（base 段 `p{hash}`，gap 段 `t{seq}`）；
   - 其余 USER → `turn_start{inputs:[{text}]}`（文本 `getJoinedTextContent()`，跳过 `IMAGE_URL/FILE/VIDEO`，附件只保留文本标签、不渲染 chip；`turn_id` base 段为 `p{hash}`，gap 段为 `t{seq}`）；
   - ASSISTANT：`reasoning_content` → `reasoning`；文本 → `assistant_text`（连续合并）；`tool_calls[]` → `tool_start`，**但 `memory_compress`（`Compression.COMPRESSION_TOOL_NAME`）的调用及其 TOOL 结果 → `compression` notice**，不生成 tool 记录；
   - TOOL → `tool_result`（按 `tool_call_id` 配对，status `"unknown"`）；
   - 每个 `turn_start` 前与段末补 `turn_complete{message_count = 该轮覆盖的消息数，end-exclusive}`，不留 open 幽灵轮；
   - 同样应用 32KB 外置 / preview。
5. `source` 按页（§3.4）；新会话仅有 `session_start` 时不显示"从旧会话重建"（gate `projection_through > 0`）。
6. **undo 落在投影轮**（resumed 旧会话首次 `/undo`）：不写 `rewind`；`.data` 截短后下次打开走规则 3 的 reset 路径。

### 3.8 有界性与性能预算

| 项 | 预算 | 实测（M3） | 手段 |
|----|------|-----------|------|
| 单条记录 | ≤ 64KB | — | 所有字符串字段 > 32KB 外置 |
| 单次 `session/records` | ≤ 2MB / 1000 条 | — | 缩轮；半轮 `partial` |
| 1 万条 resume | < 300ms | — | 只解析行首 |
| 首屏 | 30 轮 < 500ms | — | `(turnId, key)` 增量 DOM |
| 文件增长 | p90 < 1MB | — | 无 chunk、外置 |
| 上限 | 50MB → `oversized` | — | 最近 1 万条；P2 归档 |
| gap 物化 | 单次同步 ≤ 2,000 条 messages、< 500ms | — | 超出分批，首轮结束后后台补（§3.7-3） |

### 3.9 安全与隐私

暴露面同 `.data`；`recordPayload` 与 `transcript:openBlob` IPC（`main.ts` + `preload.ts`）校验 sessionId ↔ blob 目录；附件副本随会话删除；导出脱敏 P2。

---

## 4. 覆盖矩阵（当前 → 目标；✅/❌/🟡/⬜ 瞬时层）

| 事件 / 输入 | 当前 | 目标 |
|------|------|------|
| 用户输入（batch 每条） | — / ✅ 拼成一条 / ✅ / ✅ | `turn_start.inputs[]` ✅ / ✅ / ✅ / ✅ |
| steering | — / ✅ / ✅ / ✅ | `steer`（inject 处）✅ / ✅ / ✅ / ✅ |
| 任务通知 | — / ✅ / ✅ / ✅ | `turn_start{source:"task_note"}` ✅ / ✅ / ✅ / ✅ |
| 中断标记（USER system-reminder） | — / ✅ / ✅（系统提示行）/ ✅ | `notice{system}`（投影）✅ / ✅ / ✅ |
| text_chunk | ✅ / 🟡 / ✅ / ✅ | ✅ / ✅ / ✅ / ✅ 块级 sealed |
| reasoning_chunk / complete | ✅ / 🟡 / ❌ / ✅ | ✅ / ✅ / ✅ / ✅；complete 另留瞬时封块 |
| tool_start / tool_result | ✅ / 🟡 / ❌ / ✅ | ✅ / ✅ / ✅ / ✅（未闭合 → cancelled） |
| tool_approval_request + 决策 | ✅ / ❌ / ❌ / ✅ | ✅ / ✅ 两条 / ✅ / ✅ |
| on_tool · status_change | ✅ / ❌ / ❌ / 🟡 | ⬜ |
| batch_tool_start · plan_update · error | ✅ / ❌ / ❌ / ✅ | ✅ / ✅ / ✅ / ✅ |
| environment_output_chunk | ✅ / ❌ / ❌ / ✅ | ✅ / ✅ 并入 tool_result / ✅ / ✅ |
| compression（事件 + 投影 `memory_compress`） | ✅ / ❌ / ❌ / ✅ | ✅ / ✅ / ✅ / ✅ notice |
| sandbox | ✅ / ❌ / ❌ / ❌ | ✅ / ✅ / ✅ / 🟡 |
| task_status | ✅ / ❌ / ❌ / ✅ | ✅ / ✅ / ✅ / ✅（taskIndex / pendingTaskStatus） |
| turn_complete | ✅ / ❌ / ❌ / ✅ | ✅ / ✅（含 message_count）/ ✅ / ✅ |
| custom:quick_replies · artifact · 富卡片 | ✅ / ❌ / ❌ / ✅ | ✅ / ✅ / ✅ / ✅ |
| custom:memory | ✅ / ❌ / ❌ / ✅ | ✅ / 🟡 仅引擎会话存活期 / ✅ / ✅ |
| undo（CLI） | — / ✅ 直接删 / ✅ / — | `rewind` ✅ / ✅ / ✅ / P1 |

---

## 5. 实现映射

| 模块 | 新增 / 改动 | 说明 |
|------|------------|------|
| core-ai | `ai.core.session.transcript.*` | D2/D3/D6/D10；recorder 构造带 `LongSupplier messageCount` |
| core-ai | `InProcessAgentSession` | 可选 recorder；`beginTurn / taskNote / approval` 调用点；`close()` 先 save 再 forced complete |
| core-ai | `PendingUserInputInjector` / `SessionTurnInput` / `PendingUserInputQueue` | `turnId` 与 `steerSeq` 穿透 `PendingUserInput`；`offer` 处 `steerPending`、`inject` 处 `steerCommit`、`end` 重新入队处 `steerCancel`；injector 经 `ExecutionContext.get(TranscriptRecorder.CONTEXT_KEY)` 取 recorder |
| core-ai | `ExecutionContext` | 新 key `TranscriptRecorder.CONTEXT_KEY`（同 `CustomEventEmitter.CONTEXT_KEY` 模式） |
| core-ai | `AgentInterruptionHandler` | `isInterruptionMarker` 提为 public 供 `TranscriptProjection` 复用 |
| core-ai | `Node` | `seedTokenUsage(Usage)`、**`subtractTokenUsage(Usage)`**（D5 前提） |
| core-ai | `SessionTurnUsage` | 新增 `of(agent, before) → Usage`，失败路径在 `failTurn` 调用点直接拿到该轮 usage |
| core-ai | `TranscriptStore` | `turnUsage(toSeq)`：从该轮终止记录读 usage，供 `recorder.rewind(toSeq)` 返回 |
| core-ai | `InProcessAgentSession` 终止路径 | 正常：`completeTurn` → `dispatchTerminal`；失败：`persistOnFailure` → `failTurn` → `dispatchTerminal`；`sendMessage` 的 steer 顺序改为 pending → offer |
| core-ai | `FileSessionPersistence.delete` | 删 `.events.jsonl`、递归删 `.blobs/` |
| core-ai-cli | `EngineSessionRegistry` | `build()` 顺序；`records / recordPayload`；`delete` 先 close；`history` deprecated |
| core-ai-cli | `EngineSession` | 持 recorder；`undo` → `rewind`（投影轮不写）；`stats` 改公式 |
| core-ai-cli | `AppServerEngine` | 注册方法；`capabilities.add("transcript")`；`thinking/get` 值作 metadata |
| core-ai-cli | `AgentSessionRunner` / `AgentSessionRunnerCommandHandler.handleUndo` | 注入 recorder；`/undo` 接 `rewind`；`--debug-transcript` |
| core-ai-cli | `AcpAgentRunner` | P1 |
| core-ai-cli | `NativeReflectionFeature` | 新 DTO |
| core-ai-desktop | `src/renderer/transcript/*` | §3.5 |
| core-ai-desktop | `app.ts` | `client_turn_id`；瞬时/记录层；sealed/active；`openSession`；gate 内保留 `renderHistoryMessages` |
| core-ai-desktop | `main.ts` + `preload.ts` | `transcript:openBlob` |
| core-ai-desktop | `RpcClient.ts` | 透传 `session/record` |
| 文档 | `design-core-ai-desktop.md` §3.4 / §9.2-19 | 回指本文 |

里程碑：

| 阶段 | 内容 | 出口 |
|------|------|------|
| M0 | `--record`；矩阵脚本；DOM diff；`appserver-e2e.py` 入库 `core-ai-cli/scripts/` | 素材入库 |
| M1 | recorder API + store（含 close）+ projection（含 G4 分类、gap 物化）+ 单测；两宿主注入；`/undo` 接 recorder；`--debug-transcript` | `core-ai-cli/scripts/appserver-e2e.py`（§7.3）全绿；CLI 与 app-server kind 序列一致 |
| M2 | reducer + sealed/active + 等价性测试 | §7.1；无双气泡；投影提示正确 |
| M3 | stats 公式；blob 外置；§3.8 实测；CLI `/resume`（P1） | §7 全绿 |

---

## 6. 风险

| 风险 | 缓解 |
|------|------|
| 块级替换闪烁 | 同形 DOM 按 `(turnId, key)` patch；录屏核验 |
| 切分边界 / steer 位置 | API 与事件同锁；两阶段 steer（pending 先于 offer 定位置，inject/end 处 commit/cancel）；真实录像 fixture |
| **steer 与工具记录的窄竞态**：`steerPending` 在 RPC 线程、`tool_start` 在 agent 线程，发送瞬间工具正好启动时锁顺序可能把工具排在 steer 前，而 live 是 steer 在前 | 接受为已知差异：§7.1 该 fixture 允许"相邻且仅差一个 tool_start 的位置互换"进入 `stripVolatile` 白名单；若实测频繁再考虑让 live 以记录位置为准重排 |
| 多线程 dispatch | 顺序 = 锁顺序；terminal 后丢弃迟到 chunk |
| gap 物化误判 | 指纹 + end-exclusive 计数 + reset 路径；§7.3 gap 用例断言"排在最后一条记录之后、不重复、再次打开不再写" |
| Windows 删除失败 | `store.close()` 先于 delete；递归删目录 |
| 多宿主同开 | 与 `.data` 同级；`O_APPEND`；互斥不在本期 |
| `app.ts` 回归 | chunk / `reasoning_complete` / 审批代码不动；`fake-engine.mjs` 回放 |

---

## 7. 验证

### 7.1 等价性（`transcript.equivalence.test.mjs`）

真实录像 fixture；`liveModel`（全部帧）与 `replayModel`（仅记录 + 分页 + 缓冲 + 全量重建）`deepEqual`（剔除计时与 `open`）。fixture 至少 16 个：纯文本；**text → tool → text**；多工具交错 thinking；审批拒绝；memory 晚到；取消；**steering 发送后仍有流式文本与工具**（pending 位置 = live 封泡位置，committed 不移动）；**steering 未注入被 end 重新入队**（cancelled + 下一轮 inputs）；`queued:true` 的消息跨轮认领；batch 两条；`/undo`；**task_status 跨轮完成**；**投影含中断标记 + task notification + memory_compress**；**gap 段排序**；**partial 页（缺头 / 缺尾）**；**失败轮（desktop 有 client turnId）**；**失败轮（CLI 无 client turnId）**；**发送 steering 时工具正好启动**（窄竞态白名单）；**task_note 轮的 `/undo`**；**崩溃后重开带 dangling `steer{pending}` 的会话**（显示为已取消，不出现幽灵用户消息）。

### 7.2 覆盖矩阵（`scripts/transcript/matrix.mjs`）

读 `EventType` / `SessionEventDispatcher` / `TranscriptKinds` / `reducer.ts`；非白名单事件"落盘"或"回放"为 ❌ 即失败。

### 7.3 引擎级 E2E（`core-ai-cli/scripts/appserver-e2e.py`）

入库脚本用离线 LLM stub 启动真实 app-server，覆盖 13 个阶段：初始化/能力、一轮工具调用 + 审批 + 运行中 steering（records kind/seq、`turn_id`、`inputs[].client_turn_id`、steer pending/committed、`message_count == .data` 消息数、`recordPayload`、`stats`）、重启后 records/stats/turn_id 一致、删除 `.events.jsonl` 后的 `.data` 投影（`source:"projection"`、`p...` turn_id、tool/assistant/turn_complete）、投影轮 `/undo` 后 reset、失败轮 `error{terminal:true}` 与重启不重复物化、40KB 大字段外置与 `recordPayload`、旧客户端 gap 物化（synthetic/seq/一次写入/不重复）、dangling `steer{pending}` 杀进程恢复（cancelled + open turn cancelled + `.data` 不损坏）。Java `AppServerTest` 继续只测 JSON-RPC transport。

脚本尚未覆盖（后续补）：`task_note` 轮（当前由 projection/recorder 单测覆盖）、batch 两条输入与 `queued` 跨轮、审批拒绝/取消/多工具交错/partial 页的引擎级组合、压缩后重启的水位（当前由 `TranscriptRecorderTest` 覆盖）。

### 7.4 DOM 级 E2E

`fake-engine.mjs` 回放同一 fixture：live vs `session/records`，a11y 树一致（耗时文本除外），用户气泡数与 text 块数相等。

### 7.5 交叉客户端（M1）· 7.6 人工 UAT · 7.7 性能数字

同 v0.3：CLI `--debug-transcript` 与桌面 kind 序列一致；另一位同事不读本文对照 §4 勾选；1 万条记录实测填 §3.8。

---

## 8. 迁移与兼容

旧桌面 + 新引擎：`session/history` 仍可用。新桌面 + 旧引擎：无 `"transcript"` → gate 内旧路径。旧会话：读时投影 + gap 物化，无需用户操作。服务端不受影响。

---

## 9. 待评审问题（v0.6）

已定（不再讨论）：D5 `subtractTokenUsage` 必做，usage 由 `rewind(toSeq)` 返回；D11 两阶段 steer 且 pending 先于 offer；轮级终止只经 `completeTurn / failTurn`；task_note 为 `turn_start` 的 `source` 变体；**Q1 接受**：gap 物化写入文件（排序与幂等的唯一干净解）；**Q2 定为**：reset 时失真段丢弃，不物化、不截断文件，桌面在该会话头部显示"历史已被外部修改，部分记录已丢弃"提示。

1. **D11** `steer{cancelled}` 时 live 端是否同步移除已显示的用户气泡；建议移除（与重载一致），否则把 cancelled 纳入 `stripVolatile`。
2. §6 steer 与工具记录的窄竞态按白名单接受，还是让 live 以记录位置为准重排（M2 实测后定）。
3. `partialTurns` 半轮例外 vs 单轮上限 4MB。
4. 首屏 30 轮 vs 按字节预算。
5. 32KB 统一阈值。
6. gap 物化同步 2,000 条上限与后台补齐（M1 量化后定）。
7. 子代理不单独记录。
8. 多进程写互斥。
9. ACP P1。
10. 桌面 undo UI P1。
