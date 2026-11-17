# Core AI Desktop 会话完成 Channel 通知 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Implemented / v0.2（用户 2026-11-17 追加勾选时通道校验） |
| 文档版本 | v0.2 |
| 创建日期 | 2026-11-13 |
| 需求提出者 / Reviewer | 用户；用户已决定跳过 Review 并直接实施 |
| 作者 | core-ai |
| 范围 | Desktop chat composer 的会话级「完成通知」勾选开关、Settings 中的 OCG 通知目标、主进程投递与提示流程 |
| 本次交付 | v0.1 已按用户决定直接实施；实现记录见第 13 节 |
| 设计依据 | 用户原始需求、实际源代码、OCG 1.3.6 CLI 实测输出、server 既有 session-completion notification 实现 |

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> 在desktop的chat输入框底部加一个勾选按钮，功能类似server的channel通知，当这个被勾选的时候，会话完成（asstantant做任务结束），发一条消息到他配置的channel上，默认不勾选，勾选的时候如果setting的channel没有配置则提示并帮助用户完成channel配置

### 0.1.1 追加需求（2026-11-17，原文保留）

> desktop的notify功能，我相加个校验，当用户点击勾选notify，如果配置已经好了的情况下，立刻给这个channel发一条诸如好的我会在任务完成后发消息通知您之类的检验，如果通了最好，如果发送失败就应该告诉用户，因为QQ或者微信的机制，需要让他们先发一条消息预热通道，然后用户点击已发送，我们再试一次确认没问题

### 0.2 需求编号

| ID | 要求 | 性质 |
|---|---|---|
| N1 | Desktop chat 输入框底部新增勾选按钮，默认不勾选 | 必须 |
| N2 | 勾选后，会话完成（assistant 结束本轮任务）时向用户配置的 channel 发一条消息 | 必须 |
| N3 | 勾选时若 Settings 中 channel 尚未配置，必须提示并帮助用户完成配置 | 必须，且不能静默失败 |
| N4 | 行为与 server 的 channel 通知类似：按会话开关、在回合终止事件上触发 | 必须 |
| N5 | 通知不得影响正常回合、引擎和 UI；发送失败不能阻塞或回滚会话 | 必须 |
| N6 | 先交付设计，用户 Review 后才实施 | 工作边界 |
| N7 | 勾选时（配置已就绪或刚配置完成）立即向目标 channel 发一条确认消息；发送成功才允许打开该会话开关 | 必须 |
| N8 | 确认消息发送失败时必须告诉用户原因，并给出“先给对方发一条消息预热、点击已发送后重试”的入口；重试成功才确认开启 | 必须 |

### 0.3 文档维护规则

- 0.1 节用户原文保持不变；后续解释、调整和新增需求单独记录，不覆盖历史。
- 每条 Review 使用稳定 ID，记录意见、处理方案、影响章节、状态与确认人；没有用户确认不得写成 Approved。
- 本文中的字段名、IPC 名、默认值在 v0.1 实施后以代码为准；实现记录见第 13 节。
- 每次实质修改更新版本与修改记录；本文不代替代码注释和验收记录。
- 设计只覆盖 desktop 本地会话；server 的 session-completion notification 不在本次改动范围。

## 1. 目标、边界与核心提案

### 1.1 核心提案

在 desktop chat composer 的底部增加一个会话级 `Notify` 勾选框。勾选后，主进程监听该会话的引擎终止事件（`turn_complete` / `error`），把一条简短完成消息发送到用户在 **Settings → IM channels** 中配置的本地 OCG target。

- **投递位置在 main，不在 renderer**：窗口收起、最小化到托盘或切到其它面板时，renderer 可能没有对应会话视图，但主进程始终能收到引擎的 `session/event`。
- **目标来自本地 OCG**：desktop 已有 OCG 安装、登录、channel 管理和 IM bridge；通知复用同一条本机 channel，不新增 server API，也不依赖云端登录。
- **目标身份 = channel + accountId + target**：OCG 同一 channel 可能有多个账号；发送时必须带账号，否则会出现 `UNKNOWN_ACCOUNT`（本机实测）。
- **未配置即不启用**：勾选动作先检查目标；没有目标时打开配置对话框。取消配置则勾选框保持未勾选，绝不出现"看起来已开启但实际不发"的状态。
- **按会话 opt-in**：默认关闭；勾选状态持久化在 desktop 本地，随会话切换恢复；空白窗口的勾选状态应用到首条消息创建的新会话。
- **只由终止事件决定**：不做轮询、不让模型决定是否发送、不把"回合进行中"当作完成。

**初衷与理由：** 这个功能的价值是让用户离开电脑后仍能知道任务结束。它必须是"用户明确打开 + 本机可达 + 真实终止事件"三者同时成立；任何一环缺失都应该表现为可见的未启用或失败提示，而不是静默丢弃。把判定放在 main，是因为 desktop 已经支持关闭窗口到托盘、后台调度和隐藏窗口运行，renderer 不是可靠的生命周期所有者。

### 1.2 目标与不变量

1. 新会话默认不通知；只有用户在该会话勾选后才发送。
2. 一个终止回合最多产生一条通知；`cancelled` 的回合不通知（用户主动停止，不需要再被告知）。
3. 目标只能由用户在 Settings / 配置对话框中选定；模型、提示词或工具输出不能改写目标。
4. 未配置目标、OCG 不可用、target 不存在或发送失败时，不得伪造成已发送；UI 必须能说明原因。
5. 通知失败不影响回合状态、引擎进程、会话持久化和 UI 流式渲染。
6. 不修改 engine / app-server 协议；CLI、ACP、scheduled task 的行为不受影响。
7. 消息体有固定上限；不附带工具参数、工具结果、文件内容、凭据或完整会话历史。
8. 目标选择只使用 OCG 已经见过的 target；不要求用户手工输入 openid 一类平台地址。

### 1.3 非目标

- 不实现 server 端 session-completion notification 的替代品；server 的 `notifyOnComplete` 与 `SessionCompletionNotifier` 保持原样。
- 不实现操作系统原生通知（OS toast）；那是独立能力，不能和 channel 通知混为一条链路。
- 不做离线队列、送达保证、失败重试风暴或跨设备同步；通知是 best-effort。
- 不自动安装 OCG、不自动扫码登录、不自动启动未运行的 channel；配置对话框只提供显式入口和指引。
- 不按 workspace 配置不同目标；首版一个 desktop profile 一个通知目标。
- 不提供消息模板编辑器、多目标广播、抄送、@所有人等能力。
- 不把通知开关写进 engine 的 `.data`；跨 CLI 共享该开关不在首版范围。
- 不处理 IM bridge 产生的重复回复；如果用户给 bridge 会话也勾选通知，可能出现一条 IM 回复加一条完成通知，属于用户显式选择的结果（见 7.6）。

## 2. 现状核对与接入约束

本节只依据实际源码与本机验证，不把"计划新增"写成已有能力。

| 现状 | 源码证据（工作区相对路径:行号） | 对设计的约束 |
|---|---|---|
| Server 已有会话完成通知：按 session 的 `notifyOnComplete` + user 的 channel/recipient/minMinutes 门控；时长低于 60s 或未达最小分钟数不发送；只处理 source=chat；cancelled 不发送 | `core-ai-server/src/main/java/ai/core/server/session/SessionCompletionNotifier.java:87-98`、`:100-133`、`:227-269` | Desktop 可以复用"终止事件 + 用户目标 + 按会话开关"的语义，但不能复用 server 的 session/user 存储和 HTTP 入口 |
| Server 的 target 是用户级 `notifyChannelId` / `notifyRecipient`；设置接口为 `/api/user/notification-settings`；可投递 channel 只列当前用户可见且启用、且有 outbound adapter 的 channel | `core-ai-server/src/main/java/ai/core/server/session/NotificationSettingsService.java:39-66`、`:68-83`、`:85-95`；`core-ai-api/src/main/java/ai/core/api/server/user/UserNotificationWebService.java:17` | "配置 channel"在 desktop 需要一个等价的本地目标模型；不能假设 server 的 channel 配置对本机直接可用 |
| Server 的目标候选来自 `UserChannelTargetStore`（平台见过的用户地址），不是自由输入 | `NotificationSettingsService.java:56-63`；`core-ai-server/src/main/java/ai/core/server/channel/UserChannelTargetStore.java` | Desktop 也应从 OCG `targets list` 选择目标，避免让用户输入不可知的 openid |
| Web 前端 `NotifyToggle` 是 composer 上的铃铛按钮 + 对话框：先加载 target，未配置时保存 target 同时打开会话开关，已配置时切换当前 chat | `core-ai-frontend/src/pages/chat/components/NotifyToggle.tsx:41-107`、`:150-186`；`core-ai-frontend/src/pages/chat/components/ChatComposer.tsx:74`、`:691` | Desktop 可以借鉴"配置即启用"的流程，但用户要求的是勾选框，交互形态应更直接 |
| Desktop composer 底部是 `composer-controls` 行（Workspace / Model / Thinking / Permissions / Stop / status），由 `renderComposerControls()` 统一重建 | `core-ai-desktop/src/renderer/app.ts:2017-2187`、`:2494-2530` | 通知开关应加在这一行；会话切换时 `refreshModelState()` 会重建它，状态必须来自当前会话而不是模块级常量 |
| Desktop settings 是 userData 下的扁平 JSON，`settings:get` 返回内存对象，`settings:set` 合并 patch 后保存 | `core-ai-desktop/src/main/main.ts:49-68`、`:707-730` | 目标与每会话开关可以纳入 settings；不需要新数据库或迁移 |
| Desktop 已有 OCG 集成：安装、daemon 状态、channel 列表、Start/Stop、扫码登录、Route to desktop、IM bridge | `core-ai-desktop/src/main/main.ts:907-996`、`:1176-1209`；`core-ai-desktop/src/renderer/app.ts:7178` | 通知应复用 OCG，而不是另建 channel 体系；Settings 已有可落地的配置入口 |
| Desktop 的 IM bridge 已通过 OcgPort 发消息（进度提示）；OcgPort 优先走 `/ocg/v1` HTTP，失败才回退 CLI | `core-ai-desktop/src/main/channels/ChannelBridge.ts:358-369`；`core-ai-desktop/src/main/channels/OcgClient.ts:210-217`、`:295-323` | 通知可以复用同一个 OCG 客户端契约；HTTP 是首选，CLI 仅作只读查询 |
| `ocg send` 只有 `--text`，没有 `--text-file`；Windows 下 `.cmd` 经 cmd.exe 转发，正文含 `%` / 换行 / 引号时有转义风险 | `core-ai-desktop/src/main/channels/OcgCli.ts:102-106`；`core-ai-desktop/src/main/skills/RuntimeTools.ts:35-53`、`:172-176`；本机 `ocg send --help`（v1.3.6） | 完成消息正文必须优先走 HTTP JSON；不要把带用户内容的正文拼进 CLI 参数 |
| `OcgHttpPort` 已有 `send()`；`OcgCli` / `OcgHttpPort` 都没有 targets 方法，但 OCG CLI 1.3.6 支持 `ocg targets list --json` | `core-ai-desktop/src/main/channels/OcgClient.ts:210-217`；`core-ai-desktop/src/main/channels/OcgCli.ts`（无 targets）；本机 `ocg targets list --json` 实测返回 `{ok:true,targets:[...]}` | 需要新增一个只读的 targets 查询；target 记录包含 `channel`、`accountId`、`target`、`chatType` |
| 主进程能收到每个 live session 的 `session/event`：EngineSessionRegistry 为每个 session 挂 EventBridge，AppServerEngine 把事件广播给 app-server client | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java:389-400`；`core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java:88`、`:94`、`:106`；`core-ai-cli/src/main/java/ai/core/cli/appserver/EventBridge.java:130-143` | 通知判定放在 main 是可行的；不需要订阅或轮询 |
| 终止事件包含足够信息：`turn_complete` 有 `output` / `turn_id` / `cancelled` / `max_turns_reached` / `duration_ms` / token / `cost_usd`；`error` 有 `message` / `detail` / `turn_id` | `core-ai-api/src/main/java/ai/core/api/server/session/TurnCompleteEvent.java:46-78`；`core-ai-api/src/main/java/ai/core/api/server/session/ErrorEvent.java:29-36` | 无需 engine 改动即可生成完成消息；取消、最大轮次、失败都能区分 |
| Desktop 关闭窗口默认最小化到托盘，进程继续运行；托盘 Quit 才退出 | `core-ai-desktop/src/main/main.ts:656-678`、`:1230-1246` | Renderer 不可靠，main 侧投递才能覆盖"用户离开窗口"的场景 |
| OCG 主动发送需要 `sendSecret` / `callbackSecret`；`ocg send` 支持 `--account`；同一 channel 多账号时不带账号会报 `UNKNOWN_ACCOUNT`（本机实测） | `.core-ai/skills/ocg/SKILL.md:90-97`；`OcgCli.ts:102-106`；本机 `ocg send` 实测 | 目标必须记录 `accountId`；配置对话框必须把 OCG 的发送能力/账号状态暴露出来，不能只保存一个 target 字符串 |
| Desktop preload 是显式白名单，没有 notifications 相关 IPC | `core-ai-desktop/src/preload/preload.ts:4-115` | 需要新增 IPC 和 preload 方法；不能让 renderer 直接访问 fs/OCG |

**接入决策与理由：** 通知链路放在 main，目标配置放在 desktop settings，发送能力复用 OCG。这样既不引入 server 依赖，也不改 engine；同时利用已有的 OCG channel 管理和托盘常驻生命周期。server 的配置模型仅作为语义参考，不把 desktop 的本地会话伪装成 server chat session。

## 3. 方案总览

### 3.1 组件边界

```text
┌──────────────────────────── Desktop renderer ────────────────────────────┐
│ chat composer                                                           │
│  [x] Notify  ── IPC notifications:sessionSet ──┐                         │
│                                                │                         │
│ Settings → IM channels                         │                         │
│  Completion notifications: target / Configure  │                         │
│  ── IPC notifications:targets / test ──────────┤                         │
└────────────────────────────────────────────────┼─────────────────────────┘
                                                 │
┌──────────────────────────── Desktop main ───────▼─────────────────────────┐
│ settings.json                                                             │
│  notifyTarget: {channel, accountId, target, label?}                       │
│  notifySessions: { "<workspace>::<sessionId>": true }                     │
│                                                                           │
│ EngineSupervisor onEvent ── session/event ──► CompletionNotifier          │
│                                                   │                       │
│                                         turn_complete / error            │
│                                         + session enabled + target       │
│                                                   │                       │
│                                         OcgHttpPort.send                  │
│                                                   │                       │
│                                         (HTTP /ocg/v1/send)               │
└───────────────────────────────────────────────────┼───────────────────────┘
                                                    ▼
                                   WeChat / QQ / Telegram / DingTalk / Discord
```

| 组件（拟新增/改动） | 责任 | 不应承担的责任 | 初衷与理由 |
|---|---|---|---|
| `CompletionNotifier`（main） | 监听终止事件、读取会话开关与目标、格式化消息、调用 OCG 发送、记录失败 | 不解析 OCG channel 账号、不管理登录、不重试、不修改会话 | 让投递成为一个独立的、可单测的边界 |
| `NotificationStore` / settings 字段 | 保存目标与按会话开关 | 不保存消息正文、不保存 OCG 凭据 | 本地设置足够，避免新存储与迁移 |
| `OcgTargets` 查询（main） | 通过 `ocg targets list --json` 读取最近联系人 | 不凭空构造地址、不遍历用户隐私 | 与 server "平台见过的地址"原则一致 |
| `notify-status` 对话框（renderer） | 选择目标、保存、测试、引导安装/登录 | 不直接发消息、不读文件、不写 OCG 配置 | 把配置和投递分开，权限最小化 |
| Composer `Notify` 勾选框（renderer） | 显示当前会话开关、触发配置、持久化 | 不决定是否发送、不监听引擎事件 | UI 只表达用户意图，投递事实由 main 判定 |
| Settings → IM channels 通知行 | 显示/修改目标、测试发送、打开配置 | 不自动登录、不自动安装 | 用户可以在离开 composer 后仍找到配置入口 |

### 3.2 一次通知的完整时序

1. 用户勾选 `Notify`；renderer 检查 `getSettings().notifyTarget`。
2. 没有目标 → 打开配置对话框；用户选择 OCG target（或先去 Settings 配置）→ 保存 target → 勾选并写入会话开关。
3. 已有目标 → 直接写入当前会话开关。
4. 用户发送消息，引擎执行回合；main 收到 `status_change(running)`（仅用于排障，不用于判定）和最终 `turn_complete` / `error`。
5. `CompletionNotifier` 检查：会话是否开启、目标是否存在、事件是否 cancelled、是否重复。
6. 通过 → 格式化消息 → `OcgHttpPort.send`；失败只记录日志/推送失败事件到 UI，不回滚任何状态。
7. 用户切回窗口 → 可看到该会话仍保持勾选；下一轮继续同样的判定。

## 4. 交付语义

### 4.1 什么算「会话完成」

首版把一次用户消息驱动的完整回合（从 `status_change(running)` 到 `turn_complete` 或 `error`）视为"assistant 做任务结束"。

| 事件 | 是否通知 | 消息状态 | 理由 |
|---|---|---|---|
| `turn_complete`，`cancelled=false` | 是 | `completed`；`max_turns_reached=true` 时写 `stopped at max turns`；`budget_exhausted=true` 时补充 `budget exhausted` | 这是正常完成；达到上限也是任务终止，用户需要知道 |
| `turn_complete`，`cancelled=true` | 否 | — | 用户主动停止，通常人还在现场；server 同样跳过 |
| `error` | 是 | `failed — <message>` | 失败同样需要用户知道；server 也监听 error |
| `status_change` / 其它事件 | 否 | — | 不是终止事件，不能提前/重复发送 |
| 引擎重启导致回合无终止事件 | 否 | — | 没有可信的完成事实；不能猜测成功或失败 |

**理由：** 用引擎的终止事件作为唯一触发源，避免轮询和"回合看起来不动了"之类的启发式判断。`cancelled` 不通知是沿用 server 语义，也避免用户手动停止后仍收到"已完成"。

### 4.2 触发时机与时长门槛

首版在终止事件到达时立即发送，不设最小回合时长。消息正文包含 `duration_ms`，让用户知道任务跑了多久。

server 使用默认 5 分钟、最低 60 秒的门槛，是因为 web chat 的普通短回合很多，且 server 还要区分 chat / channel / schedule 来源。desktop 的开关是**按会话显式勾选**的，用户勾选本身就表达了"这个会话完成时请告诉我"；再叠加一个隐形的时长门槛，会让用户以为功能失效。是否增加可选的"仅通知超过 N 分钟的回合"留作 Review 问题 Q1。

### 4.3 按会话状态

- 默认：没有记录即关闭（N1）。
- 持久化键：`<workspace>::<sessionId>`。session id 只允许 `[A-Za-z0-9._~-]`（`core-ai-cli/src/main/java/ai/core/cli/appserver/SessionIds.java:12`），不会与 `::` 冲突；workspace 作为整体前缀，不做字符串拆分。
- 空白窗口（尚未创建 session）：勾选状态暂存为 `notifyPending`；首条消息调用 `session/create` 成功后，把该状态写入新 session 的键。用户切走再回来，空白窗口的 pending 状态不跨窗口保留（避免误把上一个草稿的意图带给新会话）。
- 会话切换：`refreshModelState()` 已会在打开会话后重建 composer controls；通知控件从当前 `workspace::sessionId` 重新读取状态。
- 会话删除：本地键可能残留；读取时只按当前 session id 查，不会被别的会话命中。可在后续加惰性清理，但不作为首版必需项。
- 同一 session 被 CLI 打开：CLI 不读取该开关，也不会发送通知；这是首版明确的边界（Q4）。

### 4.4 通知目标

```ts
interface NotifyTarget {
  channel: string;      // OCG channel id, e.g. openclaw-weixin
  accountId?: string;   // OCG account id; required when the channel has multiple accounts
  target: string;       // OCG target, e.g. o9cq...@im.wechat
  label?: string;       // display only, e.g. "微信 · Stephen"
  chatType?: string;    // direct | group | channel
}
```

- 目标来源：`ocg targets list --json` 返回的最近联系人；不提供自由文本输入。微信单聊的 target 是 openid，只有对方发过消息才知道，手工输入不可行。
- 目标唯一性：`channel + accountId + target`。同一个 openid 在不同 account 下不是同一个目标；发送时必须带 `accountId`。
- 单选：首版一个 desktop profile 只保存一个目标。多目标广播需要独立设计（去重、权限、失败聚合），不在本次范围。
- `chatType=group`：允许选择，但在对话框和 Settings 行明确标注 `shared — the message is visible to the group`；默认排序把 direct 放在前面。
- 目标失效：channel 被删除、账号退出、daemon 未运行、OCG 没有 `sendSecret` 时，发送会失败；保留已保存目标，提示用户重新配置，不静默改目标。

### 4.5 勾选时的配置引导

```text
点击 Notify
  │
  ├─ 已有 notifyTarget ──► 勾选当前会话，写入 settings
  │
  └─ 没有 notifyTarget
        │
        └─ 打开 "Session notifications" 对话框
              ├─ OCG CLI 未安装 ──► [Install OCG] + [Open Settings → IM channels]
              ├─ OCG 已安装但 targets 为空 ──► 说明"先给 bot 发一条消息"
              │                                  + [Refresh] + [Open IM channels]
              ├─ targets 有记录 ──► 选择 target + [Save]（可选 [Send test]）
              └─ 用户取消 ──► 勾选框保持未勾选
```

对话框保存 target 后，回到 composer 自动勾选当前会话；这是"配置即启用"，与 server `NotifyToggle` 的默认行为一致。若用户只打开 Settings 配置 target、没有从对话框保存，则回到 composer 后仍需用户再次勾选，避免"配置了 target 就自动给所有会话发消息"。

### 4.6 消息格式

首版固定模板，不提供 UI 编辑：

```text
⏱ Session finished — <title>
Duration: 3m 12s · completed
tokens 12K in / 3K out · $0.12
<assistant 最终输出前 200 字符，单行>
```

失败：

```text
⚠ Session failed — <title>
Duration: 1m 04s · failed — <error message 前 200 字符>
```

规则：

- `<title>` 优先读 `titles.json` 的会话重命名，其次读 `.data` 的首条 user 文本（复用 `main.ts:287-289` 的 `readSessionTitle`）；都没有时用 `session <前 8 位>`，不暴露完整内部 ID。
- `duration_ms` 格式化为 `123ms` / `3s` / `3m 12s` / `1h 05m`；缺失则省略该行。
- token / cost 只在事件提供时显示；`cancelled` 不发送，因此不会出现 `cancelled` 状态。
- assistant 输出取 `turn_complete.output`；没有 output 时用事件之外的最后一条 assistant 历史文本（可选，失败则省略）；截断到 200 字符，换行压成空格。
- 不包含工具参数、工具结果、附件内容、文件路径、完整 prompt、workspace 绝对路径或凭据。
- 正文长度硬上限 1,000 字符；模板本身短，截断只影响 output/error 摘要。

**语言：**首版英文，与 server 的通知模板和 desktop UI 保持一致；assistant 输出内容保持原文（可能是中文）。是否本地化留作 Q4。

### 4.7 发送失败、重试与去重

- 发送是 best-effort：失败只记日志，并向 renderer 推一条 `notifications:delivery` 事件用于 toast；不改变会话状态、不重试、不排队。
- 不自动重试：避免同一回合短时间重复发消息；OCG/daemon 恢复后，下一回合自然恢复。
- 不自动启动 OCG daemon 或 channel：避免通知功能在后台悄悄拉起进程；配置/发送失败时提示用户去 Settings 处理。
- 去重：main 维护一个内存集合，键为 `workspace::sessionId::turnId::kind`（`kind` = `turn_complete` / `error`）；`turnId` 缺失时不参与去重。集合设上限（例如 200 条），超出按插入顺序淘汰。引擎正常只发一个终止事件，去重是防御性的。
- 进程退出/崩溃：没有送达的消息不会补发；首版不做 durable outbox。

### 4.8 完成 / 取消 / 失败的状态表

| 组合 | 消息 |
|---|---|
| enabled + target + turn_complete(not cancelled) | 发送 `completed` |
| enabled + target + turn_complete(cancelled) | 不发送 |
| enabled + target + error | 发送 `failed` |
| enabled + 无 target | 不发送；记日志，打开窗口时 toast 一次"Notification target is not configured" |
| disabled + target + turn_complete | 不发送 |
| 无 target + disabled | 不发送 |

### 4.9 勾选时校验与通道预热（v0.2）

勾选 `Notify` 不再只是写一个本地布尔值：在打开开关之前必须证明目标 channel 现在能收到消息。

```text
点击 Notify
  │
  ├─ 解析目标（已有 notifyTarget，或配置对话框刚保存，或从最近联系人自动选定）
  │
  ├─ 立即发送确认消息：「好的，我会在任务完成后发消息通知您。(core-ai)」
  │     ├─ 成功 ──► 写入会话开关，勾选框保持勾选，toast 提示 Notifications on
  │     └─ 失败 ──► 弹出发送失败对话框（见下）
  │
  └─ 失败对话框
        ├─ 显示目标 label 与原始错误
        ├─ 微信/QQ：提示先给对方发一条消息预热通道
        ├─ 其它 channel：提示检查 channel 运行状态与目标是否仍存在
        ├─ [I have sent it — retry] ──► 再次发送确认消息
        │     ├─ 成功 ──► 关闭对话框并打开会话开关
        │     └─ 失败 ──► 更新错误，可继续重试
        ├─ [Open Settings → IM channels] ──► 关闭并跳到配置
        └─ [Cancel] ──► 勾选框保持未勾选
```

规则：

- **成功才开启**：确认消息没有送达时，会话开关不写入，避免出现“看起来已开启、实际发不出去”的状态。
- **每次勾选都校验**：这是用户显式打开开关的动作；即使目标未变，也重新证明一次通道可用。
- **QQ / 微信预热**：这两类 channel 的机器人通常只能在用户先发过消息后主动发送（微信实测 `ret:-2 prepare failed`）。重试按钮发送的仍是同一条确认消息，成功后用户会收到它，同时开关被打开。
- **错误必须可见**：失败对话框同时显示原始错误和可执行指引；不做静默降级，也不自动替用户发送预热消息。
- **不改变完成通知语义**：确认消息只发生在勾选时；回合完成通知仍只由 `turn_complete` / `error` 触发（见 4.1）。
- **确认消息与测试消息分离**：`notifications:test` 仍用于 Settings 中的人工测试；`notifications:verify` 专用于勾选校验，正文固定、不携带会话内容。

## 5. 数据与接口

### 5.1 DesktopSettings 新增字段

```ts
interface DesktopSettings {
  // ...existing fields

  /** User-picked local OCG target for session-completion notifications. */
  notifyTarget?: NotifyTarget | null;

  /** Per-session opt-in flags, key = "<workspace>::<sessionId>". */
  notifySessions?: Record<string, boolean>;
}
```

`settings.json` 示例：

```json
{
  "notifyTarget": {
    "channel": "openclaw-weixin",
    "accountId": "6387e6aae05a-im-bot",
    "target": "o9cq809xMvk5ISWQRsqSPSHR5Wxo@im.wechat",
    "label": "微信 · 最近联系人",
    "chatType": "direct"
  },
  "notifySessions": {
    "D:\\core-ai::cli-20261113-101500": true
  }
}
```

- 不把 target 或 flag 写进 engine session `.data`；desktop 自有语义不应改变 engine 持久化格式。
- `normalizeSettings` 增加形状校验：target 必须是非空 `channel` + 非空 `target`；`accountId` / `label` / `chatType` 可选且为字符串；`notifySessions` 只保留布尔值条目，key 长度上限防止异常膨胀。
- `settings:set` 对 `notifyTarget` 做一次 normalization；非法值返回错误而不是写入半个目标。

**理由：**desktop settings 已经是用户级本地配置（approval policy、workspaces、channelBindings 都在这里），把通知目标放同一层可以复用现有读写、展示和备份路径。按会话开关是 desktop 行为，不应污染 engine 协议。

### 5.2 新增 IPC / preload

| IPC | 入参 | 返回 | 用途 |
|---|---|---|---|
| `notifications:targets` | — | `{ ok, targets, error?, warning? }` | 读取 `ocg targets list --json`；失败时给配置对话框展示原因 |
| `notifications:sessionGet` | `workspace, sessionId` | `boolean` | composer 初始化/切换会话时读取开关 |
| `notifications:sessionSet` | `workspace, sessionId, enabled` | `boolean` | 写入/删除 `notifySessions` 键；主进程立即持久化 |
| `notifications:test` | — | `OcgResult` | 用已保存 target 发一条测试消息，验证 daemon / secret / 账号 |
| `notifications:verify` | — | `OcgResult` | 勾选时发送固定的确认消息（`NOTIFY_HANDSHAKE_TEXT`），成功才允许打开会话开关；失败原因返回 renderer 供预热对话框展示 |
| `notifications:delivery`（main → renderer push） | `{ ok, targetLabel?, error? }` | — | 发送失败时提供 toast；成功时默认不打扰用户 |

Preload 只暴露以上方法，不暴露 `fs`、`path` 或原始 OCG 客户端。`notifications:targets` 是只读查询，不启动 daemon、不修改 OCG 配置。

**理由：**现有 preload 是显式白名单，新增能力必须走 IPC；把 OCG 细节留在 main 可以避免 renderer 直接拼接 shell 命令。

### 5.3 OCG 侧新增/调整

- `OcgCli.ts` 新增 `ocgTargets()`：执行 `targets list --json`，解析 `{ ok, targets }`；target 字段做最小校验（channel/target 非空）。
- `OcgHttpPort.send` 调整：`accountId` 为空时不发送该字段（`POST /ocg/v1/send` 的 body 省略空账号），避免服务端把空字符串当成一个不存在的账号。
- 完成通知正文走 `OcgHttpPort.send`（HTTP JSON）；**不走** `ocg send --text`，避免 Windows cmd.exe 对 `%`、换行、引号的转义风险。CLI 只用于只读的 targets 查询。
- 若 OCG daemon 未运行或 apiVersion < 1：`notifications:test` 返回可读错误（“OCG daemon is not reachable / OCG is too old”），配置对话框给出 `Open Settings → IM channels` 指引；不自动启动 daemon。

**理由：**OCG 的 HTTP 接口已经是 desktop IM bridge 的既有发送路径；复用它可以避免再维护一套 shell 转义。targets 是只读命令，CLI 比假设一个未文档化的 HTTP 端点更稳妥。

### 5.4 Engine / app-server

零改动。`session/event` 已经包含 `sessionId` 和完整事件；`turn_complete` / `error` 的字段足够生成消息（见第 2 节）。`EngineSupervisor.onEvent` 已经在 main 侧集中收到所有 workspace 的通知，只需在 `channelBridge?.onEngineEvent(...)` 旁边增加 `completionNotifier?.onEngineEvent(...)`。

### 5.5 存储与清理

- `settings.json` 是唯一持久化位置；没有新集合、没有 migration。
- `notifySessions` 只在勾选时写入、取消勾选时删除；session 删除不会级联清理，读取时只查当前 key。可在 `notifications:sessionGet` 时顺手删除明显不可能命中的过期 key，或在后续批次加一次启动清理；不作为首版验收条件。
- 目标里不保存 OCG token、cookie 或 channel 凭据；`accountId` / `target` 是路由标识，不是认证秘密。日志中 target 只显示前后各 4 位。

## 6. UI 与交互

### 6.1 Composer 勾选框

位置：`composer-controls` 行最左侧（Workspace 之前），与现有控件同高、同字号；面板模式（右侧 dock 打开）下保留文字 `Notify`，不降级成纯图标，确保用户知道它是什么。

```text
┌──────────────────────────────────────────────────────────────┐
│  [textarea]                                                  │
│  [+] [                                              ] [mic]  │
│  [x] Notify   Workspace [..]  Model [..]  Thinking [..] ...  │
└──────────────────────────────────────────────────────────────┘
```

状态：

| 状态 | 显示 | 点击行为 |
|---|---|---|
| 未勾选，有目标 | 空框 + `Notify`；tooltip 显示当前 target label | 勾选时先发送确认消息；成功才写当前会话 |
| 未勾选，无目标 | 空框 + `Notify`；tooltip “Set up a channel first” | 打开配置对话框；取消则保持未勾选 |
| 已勾选 | 勾选框 + `Notify`；tooltip 显示 target label + “on for this session” | 取消勾选，删除当前会话键 |
| 空白窗口 | 空框（或 pending 状态） | 写入 pending；首条消息创建会话后落到该会话 |
| 回合运行中 | 不 disabled | 允许切换；只影响本次终止事件是否发送（在事件到达时读取开关） |
| 勾选校验中 | 勾选框 disabled，toast 显示正在校验的目标 | 等待发送结果 |
| 校验失败 | 勾选框恢复未勾选，弹出预热/失败对话框 | 按 4.9 重试、去 Settings 或取消 |

- 不把开关做成"全局默认开启"；新会话永远从未勾选开始（N1）。
- 勾选/取消不触发引擎重启、不改变 model/thinking/permissions。
- 控件状态来自 `notifications:sessionGet` 或 bootstrap 时读到的 `settings.notifySessions`；不依赖 DOM 存活。
- 选中/悬停样式复用现有 `.composer-*` 变量，亮/暗主题都走 CSS 变量。

**理由：**用户明确说了"输入框底部"和"勾选按钮"，因此不做 server 的铃铛 + 弹窗作为主入口；弹窗只用于配置，不作为日常开关。保留文字而不是纯图标，是因为这个开关关乎"会不会发消息到外部"，需要可读。

### 6.2 配置对话框

标题：`Session notifications`

内容：

1. 说明文案：`Get a message on your IM channel when a turn in this chat finishes.`
2. 目标选择（`<select>`）：
   - 每项：`微信 · <label>` / `QQ · <label>` / `Telegram · <label>`；
   - `target` 只显示脱敏值（前 4 + … + 后 4）；
   - group 项追加 `(shared)` 与“群内可见”提示。
3. 操作：`Save`、`Send test`、`Cancel`。
4. 未就绪分支：
   - OCG 未安装：`Install OCG`（复用 `channelsInstallTool`，带确认）+ `Open Settings → IM channels`；
   - OCG 已安装但无 targets：`Send the bot a message once, then Refresh.` + `Refresh` + `Open Settings → IM channels`；
   - daemon 未运行：显示错误原文 + `Open Settings → IM channels`。
5. 保存成功后关闭对话框并返回 `true`；调用方（composer）据此自动勾选当前会话。`Send test` 只发送，不改变开关。

不做自由文本 target 输入；不显示完整 openid；不做“测试成功才能保存”的硬门禁，但测试失败要显示原因。

### 6.3 Settings → IM channels 中的入口

在现有 `IM channels` 区域、`Desktop bridge` 行之后增加：

```text
Completion notifications     <target label or "Not configured">
                             [Configure…] [Send test]
                             One message when a chat turn finishes. Uses this machine's OCG daemon.
```

- `Configure…` 打开 6.2 的同一个对话框；Settings 侧保存后只更新行文本，不自动给任何会话打开开关。
- 目标未配置时显示 `Not configured — select a channel target`，与 composer 的提示一致。
- 现有 OCG 安装 / 登录 / Route to desktop 流程不变；通知不需要 mailbox 模式，使用 OCG 的主动发送能力即可（`sendSecret` 必须已配置）。
- 不把该行放进 server 的 Notifications 页面；这是本机 desktop 配置。

### 6.4 其它交互细节

- 会话切换：composer 控件重建时读取当前会话 flag；空白窗口显示未勾选（除非本次草稿的 pending 状态尚未被消费）。
- 删除会话：当前视图回到空白 draft，pending 状态清零；已删除会话的旧键不再被查询。
- 面板模式：`Notify` 文字保留；若空间不足，允许换行到第二行（`.composer-controls` 已是 flex-wrap）。
- 错误提示：发送失败 toast 只显示一次（同一 `turnId` 不重复），文案包含 `Notification failed: <reason>`，不显示完整 target。
- 配置变更：在 Settings 中改 target 不影响已勾选会话的 flag；下一次终止事件使用新 target。

## 7. 异常、边界与安全

| 场景 | 行为 | 理由 |
|---|---|---|
| 勾选时无 target | 打开配置对话框；取消则保持未勾选 | N3；不能显示"已开启"却没有目标 |
| 勾选时确认消息发送失败 | 不写入会话开关；弹出失败对话框，显示目标与原始错误；提供 `I have sent it — retry` | N7/N8；没有送达证据就不能承诺完成任务后会通知 |
| 微信/QQ 未预热 | 对话框提示先给对方发一条消息，用户点击重试后再校验一次 | 这两个平台的机器人通常不能在用户未互动时主动发起会话 |
| 重试仍失败 | 保留对话框与最新错误，可继续重试或去 Settings | 不伪造成功；也不把用户困在一个只能取消的弹窗里 |
| OCG 未安装 | 对话框显示安装入口和 Settings 指引；不自动安装 | 安装属于显式动作，不能在用户点击通知开关时静默执行 |
| OCG daemon 未运行 | 保存 target 可以成功；发送/测试返回 `OCG daemon is not reachable` | 允许用户先配置，稍后启动；不能把"daemon 没起"误报为"消息已发" |
| channel 有多账号 | target 记录带 `accountId`，发送时显式传入 | 本机实测不带账号会 `UNKNOWN_ACCOUNT` |
| target 被删除/账号退出 | 发送失败 → 日志 + toast；保留 target 供用户排查 | 不静默清空用户配置 |
| `sendSecret` / `callbackSecret` 未配置 | 发送失败并提示 OCG 侧配置问题 | 这是 OCG 的能力前提，不在 desktop 伪造 |
| 回合被用户取消 | 不发送 | 沿用 server 语义；用户在现场 |
| 回合因 max turns / budget 结束 | 发送，状态写 `stopped at max turns` / `budget exhausted` | 仍是任务终止，用户需要知道 |
| 引擎重启，回合中断 | 不发送；下一回合恢复 | 没有可信终止事件，不能猜测 |
| 窗口关闭到托盘 | main 继续接收事件并发送 | 这正是该功能的主要使用场景 |
| 应用退出/崩溃 | 未发送的消息丢失；不补发 | 首版不做 outbox；不能让"补发"变成重复消息源 |
| 通知正文含 `%` / 换行 / 引号 | 走 OCG HTTP JSON，不经过 shell | 避免 Windows cmd 转义破坏消息 |
| assistant 输出含敏感内容 | 只截取 200 字符；只发往用户自己选的 target | 与用户在该会话中能看到的内容同域；不扩大暴露 |
| 目标为群聊 | 允许，但标 `shared` 且提示群内可见 | 用户显式选择；不做静默外发 |
| IM bridge 会话也勾选 | 可能收到 bridge 回复 + 完成通知 | 用户显式打开；文档说明，不特殊屏蔽 |
| 同一回合重复终止事件 | 内存去重（session + turnId + kind） | 防御性，不改变正常一次发送 |
| 多个 desktop profile | 各自 settings 独立，互不影响 | settings 在 userData 下 |
| 设置被本机其它进程修改 | 以主进程当前 settings 为准；不做文件锁外的保护 | 本机单用户应用；不把本地文件当安全边界 |

**安全边界：** 目标只能由本机用户通过 UI 选择；模型和工具输出不能改 target；消息正文不包含凭据、工具原始结果、文件内容或完整历史；日志不打印完整 target 和正文。该功能不增加任何 server 端权限或数据外发面。

## 8. 验证与验收

### 8.1 单元测试

- `formatCompletionNotice`：completed / max turns / budget / failed / 超长 output / 换行 / 缺失 duration / 缺失 token / 无 title。
- `CompletionNotifier.onEngineEvent`：disabled、无 target、cancelled、error、正常、重复 turnId、非终止事件、不同 workspace 的同名 session id。
- settings normalization：非法 target、空 channel、空 target、非布尔 flag、超长 key。
- `ocgTargets` 解析：正常数组、`ok:false`、非 JSON、空 targets。
- 目标身份：同 target 不同 accountId 不相等；group 与 direct 的 label 区分。
- `notifyNeedsWarmup` / `notifyVerifyFailureHint`：微信、QQ 返回预热指引；其它 channel 返回 Settings 指引。
- `NOTIFY_HANDSHAKE_TEXT`：勾选校验正文固定，包含“任务完成后”语义。

### 8.2 Renderer / UI 测试

- 新会话默认未勾选；勾选后切换会话再回来仍勾选；另一会话保持未勾选。
- 无 target 时点击 → 对话框出现；取消 → 仍未勾选；保存 target → 自动勾选。
- Settings 行配置后，composer 的 `Notify` 使用新 target 文案；`Send test` 失败显示错误。
- 空白窗口勾选 → 发送首条消息创建会话 → 该会话为已勾选；新建另一个空白窗口不继承。
- 面板模式 / 窄宽度下控件不溢出；亮/暗主题样式一致。
- 勾选且目标已配置 → 立即发送确认消息；成功后才勾选。
- 确认消息失败 → 弹出预热对话框；取消后仍未勾选；点击重试成功后才勾选。
- 配置对话框中刚保存 target → 返回 composer 后同样先校验，再打开开关。

### 8.3 Main + engine 集成测试

用假 OCG HTTP server（或注入 `OcgPort` 的测试实现）+ stub LLM：

1. 给会话 A 打开开关，运行一个正常回合 → 恰好 1 条消息，正文含 title/duration/status/output。
2. 会话 A 取消回合 → 0 条消息。
3. 会话 A 触发 `error` → 1 条 `failed` 消息。
4. 会话 B 不打开 → 0 条消息。
5. 无 target / target 删除 / daemon 503 → 回合照常结束，日志有失败，renderer 收到一次 `notifications:delivery`，没有异常抛出。
6. 窗口 hide（托盘）状态运行 → 仍收到通知；关闭应用后不补发。
7. 同一 `turnId` 的重复终止事件 → 仍只发 1 条。

### 8.4 手工 / 真实 OCG 验收

- 本机 OCG 已登录微信，选择一个 direct target，发送测试消息成功。
- 勾选一个真实会话，运行一个短任务 → 收到完成消息；再运行一个长任务 → 收到完成消息（首版无时长门槛）。
- 取消任务 → 不收到消息。
- 在 Settings 改 target → 下一条消息发到新 target。
- 删除会话后旧键不导致异常；重新创建同名标题会话不会误用旧开关（session id 不同）。

### 8.5 需求验收矩阵

| 需求 | 验收点 |
|---|---|
| N1 | 新会话 / 空白窗口默认未勾选；无全局默认开启 |
| N2 | 勾选后正常回合完成收到一条消息；消息内容含标题与时长 |
| N3 | 无 target 时勾选弹出配置；取消不开启；保存后自动开启；OCG 未安装/无 targets 有指引 |
| N4 | 按会话开关；cancelled 不通知；error 通知；目标来自用户配置 |
| N5 | 发送失败不阻塞回合；引擎无改动；窗口隐藏仍可发送 |
| N6 | 本文件先 Review；Review 前不改业务代码 |
| N7 | 勾选时发送固定确认消息；成功才写入会话开关；每次勾选都校验 |
| N8 | 失败对话框展示原始错误与预热指引；重试成功后开关才打开 |

## 9. 关键决策记录（ADR 摘要）

| ID | 决策 | 初衷/理由 | 主要备选及为何未选 | 代价 |
|---|---|---|---|---|
| ADR-01 | 通知投递在 main 进程 | 托盘/隐藏窗口下 renderer 可能没有会话视图；main 始终收到 engine 事件 | renderer 监听：窗口关闭、切换面板、未打开会话时会漏发 | 需要新增 main 模块与 IPC |
| ADR-02 | 使用本地 OCG target，不走 server channel | desktop 会话是本地 engine session，server notifier 看不到；OCG 已在 desktop 集成 | 新增 server 发送 API：跨服务依赖、权限与数据外发面更大 | 未安装 OCG 的用户需要先配置 |
| ADR-03 | 按会话 opt-in，默认关闭，存 desktop settings | 用户明确要求默认不勾选；不应改变 engine `.data` 协议 | 存 engine session：需要协议/持久化改动，CLI 也会受影响 | CLI 打开同一会话看不到开关 |
| ADR-04 | target 从 `ocg targets list` 选择，不允许自由输入 | openid 只有入站消息才知道；server 也采用"平台见过的地址" | 手工输入：不可用且易错；全量扫描通讯录：隐私与权限问题 | 用户必须先给 bot 发过消息 |
| ADR-05 | 完成消息走 OCG HTTP，不走 `ocg send --text` | Windows cmd 对 `%`/换行/引号有转义风险；HTTP JSON 已是 bridge 的发送路径 | CLI 发送：正文可能被破坏；临时文件：`send` 不支持 `--text-file` | 依赖 daemon 的 `/ocg/v1` |
| ADR-06 | 首版每个终止回合都通知，不设时长门槛 | 用户原话是"会话完成就发"；勾选本身已是显式 opt-in | 沿用 server 5 分钟：短任务看起来像坏了 | 高频短会话可能消息较多，用户可取消勾选 |
| ADR-07 | error 也通知，cancelled 不通知 | 失败需要知道；取消是用户主动行为 | 只通知成功：失败任务静默；连取消也通知：噪音 | 失败消息可能包含错误摘要 |
| ADR-08 | 一个 profile 一个 target | 与 server 的 user-level 目标一致，配置简单 | 每会话目标：UI/存储/切换复杂度高，首版不需要 | 多 channel 用户需要改目标 |
| ADR-09 | 不做自动重试/离线队列 | 避免重复消息；通知不是关键事务 | 重试队列：需要去重、顺序、进程恢复，首版过重 | daemon 短暂故障会漏一条 |
| ADR-10 | 消息固定模板、英文、200 字符摘要 | 与 server 模板一致；可预测、可测试 | 模板编辑器/本地化：首版范围膨胀 | 用户不能自定义措辞 |
| ADR-11 | 勾选时先发固定确认消息，成功才开启开关 | 微信/QQ 的主动消息依赖用户先互动；静默开启会让用户误以为完成通知一定可达 | 只在回合结束时报告失败：用户离开电脑后才发现通道不可用 | 每次勾选多一条消息；通道故障时不能直接开启 |

## 10. 待 Review 决策

| ID | 待确认事项 | 本稿默认/建议 | 影响 |
|---|---|---|---|
| Q1 | 是否要"仅通知超过 N 分钟的回合" | 首版 0 门槛（每次终止都发）；后续可加 `notifyMinMinutes`，默认关闭 | 消息频率与 server 语义对齐程度 |
| Q2 | 失败回合是否通知 | 是；`cancelled` 不通知 | 失败可见性与噪音 |
| Q3 | 是否允许选择群聊 target | 允许，但标 `(shared)` 并提示群内可见；direct 优先 | 隐私与群消息可见性 |
| Q4 | 消息语言 | 英文模板，assistant 输出保持原文 | 与 desktop UI / server 的一致性 |
| Q5 | 是否把开关写进 engine session，供 CLI 共享 | 首版否；只存 desktop settings | 跨客户端一致性 |
| Q6 | 是否包含 assistant 输出摘要 | 包含前 200 字符；无输出则省略 | 信息量与敏感内容面 |
| Q7 | OCG 未安装/daemon 未运行时是否自动拉起 | 否；只给安装/设置入口 | 避免后台静默拉起进程 |
| Q8 | 目标是否允许"每个 workspace 不同" | 首版否；一个 profile 一个 target | 多 workspace 用户的灵活性 |
| Q9 | IM bridge 会话重复通知是否要特殊屏蔽 | 首版不特殊处理；用户显式勾选才发生 | 边界行为与实现复杂度 |
| Q10 | 是否需要"Clear target"按钮 | 首版提供 Configure 覆盖；不单独做 Clear（避免已勾选会话突然变无目标） | 配置管理完整性 |

## 11. Review 记录与修改记录

### 11.1 用户 Review 记录

用户于 2026-11-13 明确决定不进行 Review，直接按 v0.1 实施；这不是对设计内容的逐条批准，实施记录见第 13 节。

| Review ID | 日期 | Reviewer | 意见原文/范围 | 处理方案 | 影响章节 | 状态 |
|---|---|---|---|---|---|---|
| U-01 | 2026-11-13 | 用户 | 不 review 了，直接按当前设计文档开始实现 | 按 v0.1 实施；不明确处选保守方案并记录 | 第 13 节 | CONFIRMED（跳过 Review，非逐条批准） |

状态使用 `OPEN / ACCEPTED / REJECTED_WITH_REASON / IMPLEMENTED_IN_DOC / CONFIRMED`；ACCEPTED 表示接受修改意见，不等于用户已经批准全部设计。

### 11.2 AI 技术评审记录（非用户批准）

本稿尚未进行独立的 AI 技术评审；编写时已按实际源码核对第 2 节事实。后续评审意见追加到本表，状态只表示文档处理。

### 11.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 | 用户确认 |
|---|---|---|---|---|---|
| v0.1 | 2026-11-13 | core-ai | 初稿：原始需求、源码事实、main 投递架构、settings/IPC、OCG target、UI、异常、验证与 ADR | 先给用户 Review，不实施 | 待 Review |
| v0.1-impl | 2026-11-13 | core-ai | 追加第 13 节实施记录、落地选择与已知限制；状态改为 Implemented | 用户决定跳过 Review 直接实施 | 用户决定 |
| v0.2 | 2026-11-17 | core-ai | 追加 N7/N8、4.9 勾选时校验与预热流程、`notifications:verify` IPC、异常与测试、ADR-11、实施记录 13.5 | 用户要求勾选时立即校验通道，失败时引导预热后重试 | 用户提出需求 |
| v0.2-e2e | 2026-11-17 | core-ai | 追加 13.5.1 真实通道 E2E 记录 | 用户要求 E2E | 用户要求 |

### 11.4 设计自查记录

- 对照 N1–N6 逐项覆盖：默认不勾选、完成触发、未配置引导、server 类似语义、失败不影响回合、先设计后实施。
- 核对实际源码：desktop composer、settings、OCG、engine 事件链、server notifier 与 web UI 的引用行号均来自当前工作区。
- 实施后补充：用户决定跳过 Review，实现提交为 `4c45045f`；代码与测试以该提交为准，本文记录设计与落地选择。

## 12. 参考与源码索引

### 12.1 本机事实

- OCG v1.3.6：`ocg targets list --json` 返回 `{ok:true, targets:[{channel, accountId, target, chatType, senderId, senderName, firstSeenAt, lastSeenAt, count}]}`。
- OCG v1.3.6：`ocg send --help` 只提供 `--text`，不提供 `--text-file`；`--account` 可用。
- 本机实测：同一 channel 多账号时，不带 `--account` 的 `ocg send` 返回 `UNKNOWN_ACCOUNT`。

### 12.2 源码定位

- Server 通知：`core-ai-server/src/main/java/ai/core/server/session/SessionCompletionNotifier.java:39-269`；`NotificationSettingsService.java:29-137`；`core-ai-server/src/main/java/ai/core/server/domain/ChatSession.java:68`；`core-ai-api/src/main/java/ai/core/api/server/user/UserNotificationWebService.java:17`。
- Web UI：`core-ai-frontend/src/pages/chat/components/NotifyToggle.tsx:41-236`；`ChatComposer.tsx:74`、`:691`；`core-ai-frontend/src/pages/chat/Chat.tsx:169-297`、`:2191`。
- Desktop 主进程：`core-ai-desktop/src/main/main.ts:49-68`（settings）、`:707-730`（settings IPC）、`:1176-1179`（engine event fan-out）、`:1185-1209`（channel bridge）、`:656-678`（tray）、`:287-289`（session title）。
- Desktop renderer：`core-ai-desktop/src/renderer/app.ts:2017-2187`（composer controls）、`:2494-2530`（composer DOM）、`:7178`（IM channels settings）、`:8052-8055`（bootstrap settings）。
- OCG：`core-ai-desktop/src/main/channels/OcgClient.ts:210-217`；`OcgCli.ts:73-106`；`ChannelBridge.ts:358-369`；`RuntimeTools.ts:35-53`、`:172-176`。
- Engine 事件：`core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java:389-400`；`AppServerEngine.java:88-106`；`EventBridge.java:130-143`；`core-ai-api/src/main/java/ai/core/api/server/session/TurnCompleteEvent.java:46-78`；`ErrorEvent.java:29-36`。
- OCG skill：`.core-ai/skills/ocg/SKILL.md:90-97`（proactive send / targets）。

## 13. 实施记录（2026-11-13）

用户决定跳过 Review，直接按 v0.1 实现。实现提交：`4c45045f desktop: send a channel notification when a session turn finishes`。

### 13.1 对文档不明确处的落地选择

| 文档位置 | 不明确/可选点 | 本次采用的保守方案 | 理由 |
|---|---|---|---|
| Q1 | 是否设置最短回合时长 | 不设门槛，每个终止回合都通知 | 用户原话是“会话完成就发”；勾选已是显式 opt-in |
| Q2 | 失败是否通知 | `error` 通知；`cancelled` 不通知 | 失败需要知道，取消是用户主动行为 |
| Q3 | 群聊 target | 允许，但标注 `(shared)` 且提示群内可见；默认 direct 优先 | 不隐藏能力，同时明确外发范围 |
| Q4 | 消息语言 | 英文模板；assistant 输出保持原文 | 与 server 模板和 desktop UI 一致 |
| Q5 | 开关是否写入 engine session | 否，只存 desktop `settings.json` | 不改变 engine 协议；CLI 共享不在首版 |
| Q6 | 是否包含输出摘要 | 包含前 200 字符；无输出则省略 | 让用户知道结果，同时限制敏感内容面 |
| Q7 | OCG 未安装/daemon 未运行时是否自动拉起 | 否；只提供安装/Settings 入口 | 避免后台静默安装或拉起进程 |
| Q8 | 是否按 workspace 区分 target | 否；一个 desktop profile 一个 target | 与 server 的 user-level 目标一致 |
| Q9 | IM bridge 会话重复通知 | 不特殊处理 | 只有用户显式勾选才会发生 |
| Q10 | 是否提供 Clear target | 否；Configure 覆盖即可 | 避免已勾选会话突然变成无目标 |
| 5.3 | 消息正文的发送通道 | 只用 OCG `/ocg/v1/send` HTTP；targets 用 CLI 只读查询 | 避免 Windows cmd 对正文的转义风险 |
| — | 消息正文是否使用 emoji | 不使用；用 `Session finished` / `Session failed` 文本标记 | 遵循项目“无明确要求不加 emoji”的约束 |

### 13.2 实现落点

- main：`src/main/notifications/CompletionNotifier.ts`（新增）；`main.ts`（settings、IPC、engine fan-out、OCG 发送）。
- OCG：`OcgCli.ts` 新增 `normalizeOcgTargets` / `ocgTargets`；`OcgClient.ts` 的 `send` 在账号为空时省略 `accountId`。
- renderer：`src/renderer/notifications.ts`（新增，纯标签/掩码/规范化）；`app.ts`（composer 勾选框、配置对话框、Settings 行、交付失败 toast）；`styles.css`（勾选框与对话框样式）。
- 测试：`test/completionNotifier.test.mjs`（新增）；`test/ocgClient.test.mjs`（空账号 body 断言）。

### 13.3 验证

- `npm --prefix core-ai-desktop run check`：通过。
- `npm --prefix core-ai-desktop test`：186/186 通过。
- `npm --prefix core-ai-desktop run build`：通过；构建产物在 `core-ai-desktop/dist`。

### 13.4 已知限制

- 通知只在 OCG daemon 可达、目标 channel 有主动发送能力（`sendSecret`/`callbackSecret`）时成功；失败只记录/toast，不重试。
- 目标是一个 desktop profile 一个；不做多目标广播或按 workspace 区分。
- 不做离线队列、进程重启补发或跨设备同步。
- CLI 打开同一会话时不会读取 desktop 的按会话开关。
- 已删除会话的开关键不会立即清理；读取只按当前 session id 命中，残留键不影响新会话。
- 未做真实桌面 E2E（按用户要求不安装/重启正在运行的 desktop）；本次验证为单测 + TypeScript 检查 + 构建。（v0.2 已补做隔离实例的真实通道 E2E，见 13.5。）

### 13.5 勾选校验实施记录（2026-11-17）

- 需求：用户追加“勾选时立即给 channel 发一条确认消息；失败要告诉用户，微信/QQ 先发消息预热，点击已发送后重试确认”。
- main：`CompletionNotifier.ts` 新增 `NOTIFY_HANDSHAKE_TEXT`；`main.ts` 新增 `notifications:verify` IPC，复用 `sendOcgNotification`（HTTP `/ocg/v1/send`）。
- preload：新增 `notificationsVerify()`。
- renderer：`notifications.ts` 新增 `notifyNeedsWarmup` / `notifyVerifyFailureHint`；`app.ts` 的勾选流程改为“解析目标 → 发送确认消息 → 成功才 `setSessionNotify(true)`”；失败时打开预热对话框，重试成功后才开启。
- 失败对话框：显示目标 label、原始错误、微信/QQ 预热指引；按钮为 `I have sent it — retry`、`Open Settings → IM channels`、`Cancel`。
- 验证：`npm --prefix core-ai-desktop run check` 通过；`npm --prefix core-ai-desktop test` 194/194 通过（新增 `notifyNeedsWarmup` / `notifyVerifyFailureHint` / `NOTIFY_HANDSHAKE_TEXT` 断言）；`npm --prefix core-ai-desktop run build` 通过。
- 已知限制：`@tencent-weixin/openclaw-weixin@2.4.4` 忽略 `ret:-2`，会把未送达误报为成功；升级到 2.4.6+ 后 OCG 才会返回 `PLATFORM_SEND_FAILED`，desktop 才能展示预热失败。

#### 13.5.1 真实通道 E2E（2026-11-17）

环境：用独立 `--user-data-dir` 启动源码 dev 实例（不影响正在运行的安装版），workspace 为临时目录，OCG 使用本机已登录的 `openclaw-weixin`；通过 CDP 驱动 renderer，真实目标为本机微信 `o9cq…@im.wechat`。

1. **失败路径**：将 `notifyTarget` 换成不存在的 target 后勾选 Notify。OCG 返回 `sendMessage ret=-3 errmsg=invalid arguments`；renderer 弹出对话框，文本包含目标 label、原始错误、微信/QQ 预热指引、`I have sent it — retry`、`Open Settings → IM channels`、`Cancel`；勾选框未保持开启。
2. **重试成功**：在对话框打开期间把 target 恢复为真实微信地址，点击 `I have sent it — retry`。OCG 日志 `ok:true`，对话框关闭，勾选框保持勾选，toast 为 `Notifications on — 微信 · … (channel verified)`；真实微信收到确认消息（textLength=27）。
3. **空白窗口转正式会话**：真实 target 下勾选 Notify（确认消息 `ok:true`，textLength=27），随后发送 `hi`；首条消息创建 `cli-20261117-141321-4hq5`，`settings.json` 写入 `notifySessions["<workspace>::cli-20261117-141321-4hq5"]=true`。
4. **回合完成通知**：该回合 3 秒完成，OCG 日志出现第三条发送 `ok:true`（textLength=123），即 `Session finished — hi` 完成通知；微信实际收到。
5. 清理：关闭 dev 实例，安装版保持运行；临时 profile 位于 `tmp/e2e-notify`（已被 .gitignore 覆盖）。

