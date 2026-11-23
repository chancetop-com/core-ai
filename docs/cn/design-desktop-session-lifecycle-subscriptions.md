# Desktop 会话生命周期订阅与渠道主动通知 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Draft / 待用户确认关键决策；仅设计，未实施 |
| 文档版本 | v0.3.1（持续Task Run；独立AI复核完成；Q5 SQLite 已确认，其余待用户确认） |
| 创建日期 | 2026-11-20 |
| 作者 | core-ai |
| 需求提出者 / 决策人 | 用户；AI 评审不代表用户批准 |
| AI 技术评审模型 | 当前会话模型 `gpt-6.1-sol`；评审记录见 §14 |
| 范围 | Desktop 托管的本地 app-server 会话；生命周期事实、关注订阅、原渠道通知、持久化与恢复 |
| 交付 | 本文；不修改运行时代码，不部署、不发送真实 IM 消息 |
| 事实基线 | v0.3 核对工作区当前源码，HEAD `d0a188531`；核对范围见 §2，未改变并发会话的无关改动 |

## 阅读导航

- **先决策**：§1 是方案评估，不以“用户提出订阅”直接推导“必须造事件总线”。
- **理解语义**：§3 区分 LLM step、driver execution、持续 Task Run、required 工作与结果处理。
- **看实现边界**：§4–§8 覆盖事件源、工具、订阅、投递及恢复。
- **决定首期范围**：§10、§13；验收与 AI 评审在 §11、§14。

## 0. 原始需求与文档治理

### 0.1 用户原始需求（原文保留）

> 我的痛点：我通过微信、qq等渠道让core-ai-desktop查看某个任务的进度，agent确实会响应，但是它无法真正的去跟踪会话的状态然后继续主动的通知我，必须我隔一段时间就去主动问，很麻烦，参考之前我们对比的deepseek的super agent架构，我记得它有一个事件的订阅和注册机制，我想我们是不是可以先实现一个功能，让会话注册自己的生命周期事件（至少结束事件要有），然后跟我对话的channel的那个agent收到我的指令后就去订阅那个会话的结束事件，然后发消息通知我。我需要你基于这个需求，仔细分析和设计一个techdesign，但你再进入techdesign之前要先考虑决策这个做法是否合理，是否是最佳实践，有没有别的方案。

### 0.2 需求编号

| ID | 要求 | 来源 |
|---|---|---|
| L1 | 用户在微信、QQ 等渠道询问既有工作的状态，不必持续手动追问 | 用户痛点 |
| L2 | 用户明确要求关注后，系统能在被关注会话结束时主动通知 | 用户明确要求 |
| L3 | 具备生命周期事件与订阅机制，至少覆盖结束事件 | 用户方案诉求，需先评估 |
| L4 | 当前渠道 Agent 能依据用户指令建立关注关系 | 用户明确要求 |
| L5 | 编写 Tech Design 前比较合理性、最佳实践和替代方案 | 用户明确要求 |
| L6 | 事件发生后仍能关联原渠道、原请求者、原任务，不依赖询问 Agent 持续运行 | 闭环推导 |
| L7 | 不漏掉注册期间的结束、不误匹配下一轮；重复请求与事件不重复建账 | 正确性推导 |
| L8 | 重启、断网、发送失败、取消和失效均有明确状态，不伪造“已送达” | 可靠性推导 |
| L9 | 不能把回合结束、运行时关闭或暂时空闲冒充业务任务完成 | 语义与安全推导 |
| L10 | 先交付决策建议与设计；用户确认关键决策后实施 | 工作边界 |
| L11 | 关注一次用户输入授权的持续任务运行，包含因果归属的 required 后台工作与结果处理 continuation，不以单个 LLM step 或 driver invocation 结束替代 | 用户澄清；聚合机制为设计推导 |

### 0.3 用户新澄清（原文保留）

> 肯定是一轮持续的任务 而不是一个 llm turn

**已确认的是关注单位，不是算法批准。** 原始需求与澄清要求跟踪持续运行；v0.2 的 execution-only 缩减不再是正式契约。§3 提出薄 **Task Run**：root 输入及它授权、因果归属的 required 工作与结果处理。不是跨项目 Business Ticket，也不假定用户要求跨会话、多天项目管理；不要求模型手动 declare completion。

只问“现在进度怎么样”仍不等于授权主动外发；明确“任务结束告诉我”才建 once Watch。关注绑定一个 Task Run 的最终 ended，既不每个 model step 通知，也不每次 `turn_complete` 通知；业务质量验收与 runtime 正常返回分开。TaskRunCoordinator、适配器覆盖、直接程序通知、scope 和存储仍是待确认的设计建议。

### 0.4 治理规则

1. 保留原文；设计推导、范围缩减及建议偏离原设想必须单列。
2. 现状、拟新增、后续能力分开；不能把旧设计的计划当成已实现。
3. AI 评审只记录为技术意见，不标成用户 Approved。
4. 文档维护需求编号、ADR、待 Review 决策、Review 与修改记录。
5. 源码引用采用工作区相对 `file_path:line_number`；实施前重新核对。

## 1. 进入设计前的决策：是否应该做事件订阅

### 1.1 结论

**方向合理；对“关注一次既有持续任务运行，结束主动告知”来说，事件驱动的持久订阅是合适的默认方案。但“会话让 LLM 自己注册生命周期、结束后再让原 Agent 临场决定是否发消息”不是推荐实现。**

推荐的职责拆分：

```text
用户表达意图 → 渠道 Agent 调用关注工具 → 宿主校验、绑定 Task Run 并持久化
运行时发布事实 → 程序匹配订阅 → 持久通知 Outbox → 原渠道投递
                                      └→ 后续可选 Agent Inbox，进行分析/协调
```

- **发布方是 runtime，不是 LLM**：创建/恢复会话时自动接入生命周期观察器；错误、停止、强制关闭也能覆盖。
- **模型只决定用户在关注什么**：必须返回真正落盘的订阅结果，不能靠一句“我会通知你”作为状态。
- **默认通知走确定性 Fast Path**：Task Run 的 returned、failed、cancelled、limited 等最终事实无需观察者模型再判定。
- **区分两类 continuation**：源任务为处理 required 结果而续跑属于 Task Run 本身，必须纳入首期；任务结束后的观察者分析/协调是另一个动作，后续 AgentInbox 不成为固定通知的必经节点。
- **事件是低延迟信号，保留状态是恢复依据**：用事件触发 + 快照/日志校准，不把易丢的内存回调当保证。

这里的“最佳实践”指与当前约束相匹配的工程取舍，不是说所有 Agent 产品都必须采用同一架构。

### 1.2 备选方案与取舍

| 方案 | 成本 / 优点 | 主要问题 | 本需求决策 |
|---|---|---|---|
| Prompt 承诺“结束我会告诉你” | 零运行时开发 | turn 结束后没有执行者、触发源或可信目的地 | 否决 |
| Agent 建定时任务反复查询 | 可快速验证；外部系统无事件时有用 | 每次查询要推理、延迟取决于周期、清理麻烦；同样要补主动出站与恢复 | 不作默认；用于无事件的外部任务适配器 |
| 程序每 N 秒扫描状态 | 不烧模型；有保留终态时可行 | 仅观察 `idle/running` 会错过短执行、重启和多个回合；必须绑定 Task Run/generation | 低频 reconciliation 兜底，不作主要触发 |
| 把现有 Notify 勾选框开放为工具 | 改动最小、已有 main 监听结束 | 全局通知目标、每会话持续开关、best-effort；不能表达原请求者的一次关注或精确执行 | 可作临时简版，但不作为最终契约 |
| 给被关注会话发“结束时通知我” | 复用 `send_session_message` | 改变被观察任务；排在执行后还会额外启动一轮，依赖目标模型/OCG skill | 否决；观察不应扰动执行 |
| 渠道 Agent 等待目标结束 | 可复用等待思想 | 占用原渠道对话、保留 turn/资源；现有 async wait 是本会话任务结果，不是跨会话关注 | 否决作为关注；需要串行编排时另谈 |
| 内存事件订阅 + 回调 | 低延迟、开发量较小 | 注册竞态、进程退出丢失、无可查询承诺和投递状态 | 只可用于 Demo |
| **薄 Task Run source + 持久订阅 + Outbox** | 低通知模型成本；持续任务精确绑定、可取消、可核对恢复 | 需 coordinator、后台 adapter gate、run identity、来源授权与存储 | **推荐首期主方案；不退回 execution-only** |
| 完整 Router/Worker/Thread/Mandate 平台，Kafka/Temporal | 可支持更广泛主动协作 | 对单机本地结束通知过重；引入运维/迁移和额外权限面 | 不作为前置；保留适配接口 |

**最小但仍闭环的版本**不是“新增一个 subscribe 工具”，而是工具 + 原渠道身份 + 可信执行事实 + 持久关注 + 主动出站。没有其中任何一环，仍会回到必须手动追问。

### 1.3 DeepSeek 机制到底能借鉴什么

前次比较文档 `docs/cn/analysis-deepseek-super-agent-vs-core-ai.md:110`、`:454` 以及 v0.2 核对的文章 §3.2 描述的是：**Worker 对环境 Thread 的多对多订阅，已知归属的后续事件由程序直通，不再逐条经过 Router。**

本需求是它的一个较小实例：

| DeepSeek 概念 | 本设计映射 | 不照搬的部分 |
|---|---|---|
| Source / Trigger | app-server runtime / 生命周期事实 | 不把所有 text/tool chunk 都转为外部触发 |
| Thread | 被关注 session 中一个 Task Run 的事实流 | 不先建立 PR、邮件、IM 话题的统一 Thread 平台 |
| Subscription | 请求者对确定 Task Run 最终 ended 的一次关注 | 不假定订阅必然启动一个 Worker |
| Router Fast Path | Desktop SubscriptionService 的确定性匹配和投递 | 不创建全局常驻 Router LLM |
| Worker / Agent callback | 后续可选 Agent Inbox | 首期固定通知不经模型 |
| Mandate / Policy | 明确关注意图 + 原请求者/渠道 + workspace grant | 不把 OCG 允许发消息等同于能读所有本地会话 |

文章明确是架构探索与原型，不是已普遍验证的生产规范；其合成负载消融数据不能用来承诺本功能的性能收益。

### 1.4 外部工程实践的对应关系

- **Query 与 Signal 分离**：Temporal 区分只读 Queries、异步 Signals 和有确认的 Updates。本设计借鉴这一职责区分；不引入 Temporal。
- **先快照，再带版本关注**：Kubernetes list/watch 通过 `resourceVersion` 避免查完再订阅之间丢事件，历史失效后重新校准。本设计使用固定 Task Run/version 与原子 bind/replay；不复制 Kubernetes API。
- **先持久建账，再外发**：Outbox 模式处理状态已提交而消息未发出的双写问题。但本地 engine 日志、Desktop store 与 OCG 是三个边界，**不能宣称一次跨进程事务或端到端 exactly-once**。

参考链接见 §15。主判断仍依据当前 core-ai 源码与产品边界。

## 2. 现状：已有结束事件，缺关注闭环

### 2.1 当前源码事实

v0.3 重读与行号核对集中于 Agent/session、后台、wait/async、queue、managed/media（如下表新增语义行）；其余 Desktop/OCG 接线与历史设计引用保留 v0.2 证据，检查路径/位置有效性，不声称本次再次评审全部实现。

| 当前事实 | 源码证据 | 对本设计的约束 |
|---|---|---|
| 每 workspace 一个 app-server，main 集中 fan-out 事件 | `core-ai-desktop/src/main/engine/EngineSupervisor.ts:28`、`:118`；`core-ai-desktop/src/main/main.ts:1793` | 主动订阅/发送放 main，不依赖 renderer 当前打开哪个会话 |
| 会话 build 已自动挂 EventBridge 与 transcript recorder | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java:359–370` | 不需要每个模型调用“register lifecycle”；应在同一 runtime 接入点自动安装 |
| `turn_complete/error/status_change/agent_wait/task_status` 已转发 | `core-ai-cli/src/main/java/ai/core/cli/appserver/EventBridge.java:76`、`:121`、`:136` | 缺的是可绑定、可恢复的事实契约，不是再造一个同名 UI 事件 |
| 每个 Agent loop 是 LLM step，一次 run 包含多个 step | `core-ai/src/main/java/ai/core/agent/Agent.java:155–201` | 不把源码 turn 命名误读为用户任务边界 |
| `turn_complete` 发生在完整 `Agent.run` / 结果 continuation 后，且早于 driver finally 收尾 | `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:248–318`、`:195–212`、`:494` | 保留 UI 事件；不是 LLM step end，也不是 Task Run ended |
| 正常返回后后台任务/后续通知仍可继续；hasPendingWork 只是部分投影 | `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:290–318`、`:396–399` | Task Run 必须包含 required 后台与结果处理，不由瞬时 idle / 无 pending 猜终态 |
| BackgroundTaskManager 在 submit 前注册 completion/running；完成时先 remove 再 complete/publish，按 notify 入队 XML 后发 TaskStatus | `core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:137–168`、`:325–339` | remove-before-publish 空窗必须由 coordinator 的 settle+continuation reservation 关闭；TaskStatus 不是 run end |
| wait suppression/consumption 可以抑制自动通知；registry 有 generation 但仅进程内 | `core-ai/src/main/java/ai/core/session/BackgroundTaskManager.java:248–273`；`core-ai/src/main/java/ai/core/tool/wait/TaskCompletionRegistry.java:16–22`、`:48–55`、`:165–176`；`core-ai/src/main/java/ai/core/tool/wait/TaskAwaitCoordinator.java:149–174`、`:224–227` | 抑制 LLM 通知不等于 required 工作已结束或结果已处理；复用 registry，不夸成 durable source |
| Async executor submit 预注册 task，却在工具 pending 返回后才 bindOwner | `core-ai/src/main/java/ai/core/tool/async/AsyncToolTaskExecutor.java:61–75`、`:119–120`；`core-ai/src/main/java/ai/core/tool/ToolExecutor.java:420–440` | 过早完成可先于 run/owner 绑定；launch pre-registration 是可靠 adapter 前置 |
| 队列同 mode 全部合批，变量 putAll 长期保存 | `core-ai/src/main/java/ai/core/session/SessionCommandQueue.java:102–115`；`core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:248–257` | run + origin partition、typed scope 和 finally 清理必须覆盖所有入口 |
| managed async store 支持 poll/listener，plugin video 在远端 launch 后放内存 jobs，刷新时才下载结果 | `core-ai/src/main/java/ai/core/tool/ToolCallAsyncTaskManager.java:63–76`、`:146–180`；`core-ai/src/main/java/ai/core/tool/tools/GenerateVideoTool.java:200–214`；`core-ai-cli/src/main/java/ai/core/cli/appserver/MediaGenerationApi.java:150–201` | 不是现成 Task Run completion adapter；poll producer、归属、下载收尾与恢复必须分别 capability gate |
| 强制 runtime close 会生成 cancelled terminal，且 session 仍可 resume | `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:413`；`core-ai-cli/src/main/java/ai/core/cli/appserver/SessionLifecycle.java:63` | “关闭会话”不是成功，也不是用户永久结束业务任务 |
| 现有 CompletionNotifier 根据按会话 flag 发到 profile 全局 target，内存去重且发送前记 sent | `core-ai-desktop/src/main/notifications/CompletionNotifier.ts:159`、`:177`、`:185`、`:209` | 无 durable outbox；不能直接用它承担一次关注与可靠重试 |
| ChannelBridge 只等其 pending session/turn 的终止，再 reply 当前入站消息 | `core-ai-desktop/src/main/channels/ChannelBridge.ts:149`、`:262`、`:294` | 随后产生的 notification turn 不自动回原 IM；不能仅 enqueue 一条 USER 消息就认为已闭环 |
| 原渠道精确目的地在入站元数据，不在用户文本 | `core-ai-desktop/src/main/channels/OcgCli.ts:15`；`core-ai-desktop/src/main/channels/ChannelBridge.ts:358`、`:454` | channel/accountId/target/chatType/sender/sessionKey 必须由宿主捕获 |
| BridgeStore 只存 sessionKey 映射及 500 条 handled；写失败被吞 | `core-ai-desktop/src/main/channels/BridgeStore.ts:19`、`:45`、`:61` | 不能沿用它的 best-effort save 来承诺订阅成功 |
| DesktopSessionTools 目前有产物、卡片、send_session_message，无本地关注工具 | `core-ai-cli/src/main/java/ai/core/cli/appserver/DesktopSessionTools.java:23` | 拟新增工具；不是已有 server read_session 自动对本机生效 |
| `send_session_message` 只同 workspace、enqueue，不等待；session/send 可能 steering | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionMessageForwarder.java:18`；`core-ai-cli/src/main/java/ai/core/cli/appserver/EngineSessionRegistry.java:229`；`core-ai/src/main/java/ai/core/session/InProcessAgentSession.java:122` | 观察工具不发送新指令；后续 Agent Inbox 不直接使用默认 steering |
| app-server stdio 可传通知，但现有 NotificationSink 无请求/响应契约 | `core-ai-cli/src/main/java/ai/core/cli/appserver/NotificationSink.java:11`；`core-ai-desktop/src/main/engine/RpcClient.ts:162` | Engine 工具请求 Desktop 关注服务需要显式 HostRequestBridge，不靠 custom card 副作用 |
| 本地列表只有粗粒度 running/idle；没 live session 不意味着成功 | `core-ai-cli/src/main/java/ai/core/cli/appserver/SessionTitleResolver.java:38` | 新快照不从这个字段推断 terminal |
| 当前发送路径会 ensureOcgDaemon，不是旧文档的“绝不自动启动” | `core-ai-desktop/src/main/main.ts:248`、`:297` | 复用当前已配置 daemon 恢复路径，不照抄旧设计的过时非目标 |
| 本地 SDK broker 是 engine 的 per-session loopback 工具面，非 Desktop 订阅宿主 | `core-ai-cli/src/main/java/ai/core/cli/appserver/LocalSdkBroker.java:38`、`:278` | 不直接读取 userData，不扩成第二个全局 broker |

### 2.2 根因定位

当前链路是“你问 → 渠道执行 → 回复”；缺少可信的持续 Task Run 聚合来源，也缺少持久关系记录**谁关注了哪个 run、允许发什么、发到哪里、是否投递过**。只有 execution end 与一个 subscribe 工具不能满足 required 后台续跑后的最终结束关注。

现有 UI Notify 是有用的独立功能，但目标为全局配置、影响每个后续 turn，且 best-effort。此次是**通过对话创建一次 Watch**，两种用户意图不能静默互相覆盖。

### 2.3 本次刻意不依赖的旧计划

- 不把前次对比文档的 Task/Thread/Watchdog 建议当作已实现。
- 不把 server 的 `read_session`、`session_notices`、SSE owner replay 当作本地能力。
- 不承诺观察独立 terminal CLI 的运行状态，也不因能看到 `.data` 就认为正在受 Desktop 托管。
- 不从标题、最终回答或 todos 全 completed 推断业务成功。

后续章节均为拟新增设计，除明确标为现状的描述外，未实现。

## 3. 领域语义与首期契约

### 3.1 术语：step、execution、Task Run 不能混用

| 对象 | 精确定义 | 本设计用途 |
|---|---|---|
| Session | 长期上下文/历史容器，可先后运行不同任务 | 发现入口，不定义永久完成 |
| LLM / model step | `core-ai/src/main/java/ai/core/agent/Agent.java:155–201` 的一次 loop：一次模型决策及其工具处理 | 过程，不是关注单位 |
| Execution / driver invocation | driver 实际处理一个合法 batch；root `Agent.run` 或结果续跑可含多个 step | runtime 生成 `execution_id`；用于内部执行、授权、排错，不作外部结束 Watch |
| **Task Run** | 一次 root 用户输入授权的持续运行，加因果归属的 required 子工作与 result-processing continuations | 首期唯一结束关注单位；runtime 生成 `task_run_id` |
| Job / child run | Task Run 发起的后台工具/子 Agent 工作；具有 backend、owner、generation 和父因果关系 | 通过 source adapter 计入 required 账；可递归跟踪，不以裸 taskId 聚合 |
| Business Ticket / project | 跨授权、多项目、多人或跨会话业务目标和验收 | 不在本稿；不据用户澄清扩大系统 |

`turn_complete` 已是完整 `Agent.run` / continuation 的执行终止，**不是一个 LLM step**；但它仍不足以代表 Task Run ended。一个 Task Run 可以经历 root 返回、后台完成、结果续跑、再次启动后台工作、最终正常返回。无需 LLM done 工具；runtime 成功只说明本次授权范围内运行与必要账已收尾，不证明业务质量达标。

### 3.2 Root admission、排队与附加输入

1. 在接受 root 请求时，由 runtime 生成并持久 register `task_run_id`、发起者 typed authority、输入 `request_id→task_run_id` 和 admission 状态；**开放 accepted request receipt、可定位 queued ref 和启动副作用之前完成**。重复的同 principal/入口 request key 返回同 mapping，不新建 run；拒收返回明确错误，不给 accepted receipt。
2. 每个 driver invocation 另生成 `execution_id`，带唯一 run、输入 refs、原因 root/result/steering；现有 caller `turn_id` 保留相关语义，不能当 run/execution ID。未消费的 steering 归属已受 admission gate 固定；重排不得暗中改授权或改绑 run。
3. 默认同 session **一条 active Task Run + 后续 queued 新任务**。active 包括已选为当前任务但 driver 尚未启动、仅 required 后台工作、待结果处理和等待输入；后续 queued run 不属于 current；已接受的 queued 请求也有确定 run ref，可显式关注。排队持久登记不承诺 crash 后自动执行恢复，见 §5.4。
4. 普通后续用户输入默认新 run 排队，不自动延长旧任务；不同 run 不合批。显式 continue/steering/edit 必须指定同 run，并通过以下 state + authority gate，不能仅靠 prompt 猜归属：

| 输入 / run 状态 | admission 结果 |
|---|---|
| 明确 steering/edit；root execution 正在 running、输入窗口开放、相容授权 | attach 同 run；typed request mapping 固定；未消费可同 run 重排 |
| 明确 continue/回答所需输入；awaiting_input、仍有typed待输入请求、相容授权 | attach同run；按wait token的resume_mode选择signal原execution或预留新continuation，二选一且唯一消费（§3.7） |
| `awaiting_async` / draining / queued；无对应可编辑输入窗口 | 不隐式 attach；返回 ATTACH_NOT_ALLOWED，或经调用者明确选择创建新 run |
| terminal、fenced、unknown/untrackable 或 authority 不兼容 | 拒绝 attach；另行明确接受新 root 才新建 run，旧 ref 不复活 |
| 普通新指令（即使 source running 或 wording 相关） | 新 run；只有重复 request key 才重用已存 mapping |

5. 单 session 调度不让后续 queued run 抢在旧 run 的 required 结果处理之前执行；等待用户时可显式终止旧 run 后启动新任务（非正常成功），不能默默合并或无限累积。容量、输入等待和 run deadline 是 policy，需 Q9 确认；不引入项目管理页面。

### 3.3 Once Watch：绑定 Task Run，不是当前 driver

- `scope=current_task_run`：原子取当前 active run。root idle 但 required 工作或 continuation 未完时仍可 active 绑定。
- `scope=explicit_task_run` + `task_run_ref`：绑定 inspect 或 typed request mapping 返回的确定 run，包括 queued、waiting、terminal；已 ended 可立即补发固定事实，不挑下一条。
- 无 active run 且无显式 ref → `NO_ACTIVE_TASK_RUN`，返回最近事实但不自动关注历史/下一轮；从未 admission 的未来任务不能订阅。
- source/adapter 覆盖未知 → `TASK_RUN_UNTRACKABLE` 或 `SOURCE_UNAVAILABLE`，不能回退为 execution Watch。“已可靠关注任务”必须有覆盖证据。
- 关注者 owner/OriginGrant 只授予 observe/notify，**不继承任务发起者的运行权限**；观察不改变源工作。一次关注覆盖本 run 所有最终 outcome，等待状态不会提前 fulfilled。

### 3.4 TaskRunCoordinator：薄账本与线性化收尾

拟新增 coordinator 维护：`task_run_id/run_generation`、root executions 的 starting/running/draining/returned、required job generation 状态、pending/reserved continuations、typed root disposition、coverage、terminal fence 与递增 version。快照只投影这份账，不用 `hasPendingWork()==false` 或短时 idle 猜完成。

**Launch / settle 协议：**

1. 工作默认 required。只有显式长期观察或与本次结果无关的服务经 policy 才 detached，保留原因；`notify=false` 不是 detached。child runtime 获因果 run context 和自身最小执行能力，不获观察者 grant；其后代 required 工作同样登记。child 自身返回不释放未 settled 的后代，父 run 仍须等待它们及必要结果处理。
2. 在工具 side effect、executor submit、远端启动前 reserve/register job（run、backend owner、jobId、generation、parent、processing policy）。未知远端 ID 先用 launch reservation，返回后原子关联；过早 callback 按 reservation 处理。submit 失败 settle 启动失败；crash 的启动歧义不能盲目重放副作用。仅在 pending result 返回后 bindOwner 太迟，必须适配预注册路径。
3. 在 **同一个 coordinator 临界区 / version 转换** 内记录 job settled，并同时预留它的 result continuation 或确定 processing disposition；然后才把执行提交到队列。绝不能先减 pending 再另起 enqueue，留下误 task-end 空窗。队列拒收/提交歧义保留 reservation，进入 attention，不丢账。
4. 通知 envelope 携 typed run/job/generation/continuation ID，XML 只作兼容内容，不解析 prompt/XML 决定授权或归属。continuation 从 reserved→queued→running→draining→settled；其再次启动 required job 仍属同 run。完成/消费/替换幂等按完整 identity，终止 fence 后迟到 callback 只记诊断，不能唤醒旧 run。
5. 复用 `TaskCompletionRegistry` 的 generation 与 completion snapshot 作 adapter，不把其内存 map 当 durable Task Run source；coordinator 与队列按固定锁序 / admission token 交接，临界区外做 I/O、推理与外发。

**正常终态 `returned` 仅在同 version 下全部成立：**

- root 及本 run 最后一次 execution 正常返回，所有 root 执行已完成必要收尾（无 active/draining）；
- 所有 launch reservations 已解决，全部 required generation 已 settled，coverage 完整；
- 无 pending/reserved/queued/running 的本 run 结果处理，无未消费且已 attach 的输入；
- root disposition 为 `returned`，无 `awaiting_input`、`awaiting_async`、unknown 或 unsupported 状态。

`root_disposition=returned|awaiting_input|awaiting_async|unknown` 来自 typed runtime/tool waiting 状态及收尾事实，不是模型读出一句“做完了”。coordinator 保留最后 execution 的正常 return candidate 与具体等待 token：未 settled 的 required/processing 使有效 disposition 为 awaiting_async；none-policy job 成功 settled 可原子清除仅属于它的等待并恢复该 candidate，不必新增 reasoning。none-policy 失败仍按 §3.5 的错误处理/attention policy，不能仅 settled 就静默成功。awaiting_input/unknown 不因无 pending 或另一个 job 完成而清除；明确输入被消费且相关等待解决后，才能由本 run 后续正常收尾恢复 returned。

node completed、todos 全完成、UI idle 或文本 final 均不权威。typed tool/approval/ask-user 的待输入请求保留到解决/取消；若 adapter 无法给出 disposition 或现状相互矛盾则 unknown/attention，不用文本猜验收，也不要求模型额外宣布完成。

### 3.5 Wait consumption、notify=false 与失败策略

| 情况 | coordinator 义务 |
|---|---|
| wait / output read 在本 run 活跃 root 内消耗结果 | 原子 transfer processing reservation 到该 execution；取消重复自动 continuation，不免除 required settle；消费完仍等执行正常收尾 |
| wait 超时/释放，job 尚未结束 | release/reservation 与 root 同步；后续完成仍按 policy 预留结果处理，不把抑制通知当消费成功 |
| `notify=false`，processing policy=`none` | 仍 required，等 settled；成功时无需自动结果推理，root 正常 returned 且其它条件成立可终止；失败且未被消费/接受则 attention，不永久悬挂也不早成功 |
| 需要处理结果但没有自动 continuation / 消费者；消费来源不可核验 | disposition unknown / needs_attention；拒绝早 returned，不谎称“整理完” |
| required job 失败/取消 | 默认先交给已有 root 或预留 continuation 处理，错误被接受、恢复后可正常 returned；没有消费者或处理失败为 attention / fatal policy，不必立即整 run failed |
| root fatal / 显式取消 / limits | 按明确 run policy terminal fencing，分别 failed / cancelled / limited；不能用正常返回覆盖失败 |

processing policy 是 runtime/adapter 显式契约（例如 automatic、root-consumption、none），不是让模型手动 declare completion。业务结果是否令人满意由用户/另一个验收流程判断。

建议异常终止 policy：root fatal、显式 run cancel、硬 limits 在 coordinator 固定异常 outcome/fence，关闭 admission 与结果续跑，将未执行 reservation 标为 cancelled_by_run；临界区外请求停止 jobs/root，不为等待外部确认无限延迟逻辑终态。取消或限额的 run **逻辑终止不等于外部 process 已停止**：记录 cancel_requested、stop_confirmed、timed_out_orphan/unknown；有限停止 grace 由 adapter 声明并经 Q9/G0 确认，超时仅诊断不改成 returned。迟到成功不覆盖异常 ended；新 root 启动前须确认旧 driver 已释放，或隔离/fence 旧 engine，不能在同 Agent 上并行复用未停止执行。已发生的外部副作用无法保证撤回。

### 3.6 状态与核心不变量

| 事实 / 状态 | 对一次 Watch 的含义 |
|---|---|
| queued / running / draining / awaiting_async / awaiting_input | 非终态；source 可查进度，不每 step 外发 |
| returned | 本次授权运行正常收尾，required 工作 settled、按 policy 所需处理已结束；不是业务质量验收 |
| failed / cancelled / limited / 明确 interrupted | 最终异常事实，固定模板说明原因与外部停止确认状态 |
| engine crash，旧 run 缺 ended | unknown / observation unavailable；核对为 interrupted 可留恢复诊断，不合成成功，不自动重跑 |
| adapter 无覆盖 / journal 失败 | attention / untrackable / storage_error；不能宣称可靠 returned，已有 Watch 转观察退化流程 |

- Watch 固定 workspace + session incarnation + task_run_id/run_generation、owner、目的地、grant、TTL；新引擎/new run 不复用旧绑定。
- runtime 自动产生唯一 `task_run.ended`；LLM 不可发布/改写终态。
- 成功登记在 durable bind/watch commit 之后；来源事实与外发解耦，fulfilled 不等于 accepted。
- 停止关注不停止任务、不影响他人/UI Notify；不能承诺设备关机执行或平台一定主动送达。

### 3.7 输入、等待恢复与任务停止的调用方契约（拟新增）

不只增加运行时内部状态；P0必须把用户意图落实到现有composer/ChannelBridge/forward入口的typed参数。新 capability `task_run_input_v1` 下：

| 方法 / 动作 | 必需绑定 | 处理 |
|---|---|---|
| `session/send(action=new_task)` | sessionId、稳定requestId；可选parts | 接受新Task Run并返回taskRunRef、generation、admission=active/queued；不默认steer旧任务 |
| `session/send(action=steer)` | 固定taskRunRef/generation、expectedVersion、requestId | 仅开放输入窗口且authority兼容才attach；当前任务编辑/改方向，不新run；不能因版本变化默默改绑 |
| `session/respondInput` | taskRunRef/generation、waitTokenId、requestId、typed answer/decision | 验证等待token和actor；按resume_mode signal或enqueue，非普通new_task消息 |
| `task_run/cancel` | 固定taskRunRef/generation、expectedVersion、requestId | 取消整条run，包括仅后台活跃；关闭后续结果处理、请求停止required jobs，返回逻辑终态及stop_confirmed/cancel_requested/orphan状态 |

当前`session/send`省略action的旧行为是可能steering。新capability模式下省略action固定为new_task并在receipt显式返回，不靠prompt判断；此行为变化必须随同更新Desktop composer、ChannelBridge、forward/schedule调用方与schema、兼容测试。旧engine未协商时维持旧协议，但不得给出Task Run订阅/任务级停止保证。现有`session/cancel`只取消当前driver，不能冒充task_run/cancel；新UI统一用run cancel，旧接口保留兼容且标明边界。

**待输入token**记录：run/generation、executionId（若有）、kind（approval/ask_user/tool_wait）、resume_mode、requiredActor/scope、state=open/claimed/consumed/cancelled、版本及截止。恢复模式在等待建立时由runtime决定：

- `signal_active_execution`：旧execution仍占运行栈并阻塞（如approval或live wait）。原子claim token后向该execution的专用输入/permission/wait通道传答案，解除原等待；不enqueue另一轮，不创建第二个continuation。解除后同execution继续执行，其正常收尾才能恢复returned候选。
- `enqueue_continuation`：旧execution已返回/挂起且不再占用同Agent driver，runtime已登记恢复义务。原子claim token并预留唯一continuation，durable commit后enqueue同run；它完成后settle等待。不能用此模式处理仍占栈的等待。
- 重复answer request返回已存消费结果；不同actor、错误generation、已取消或fenced token拒绝。token claim、答案接受与恢复义务transfer按一个协调版本仲裁，signal失败/接受结果不明确保留claimed+needs_attention，不盲目同时enqueue。crash后的token disposition不确定时保守查账，未经证明不重复恢复。
- awaiting_input不是某个全局布尔值；解除指定token不能清掉其它等待。输入成功消费也不直接TaskRun ended，仍需本run最后execution正常收尾及required/processing账满足§3.4。

**现有聊天UI（不建新任务平台）**：source session存在active run时composer展示“当前任务”和明确主操作“补充/修改当前任务”（action=steer）、次操作“另开任务”（new_task，可显示queued）。steer在awaiting_async/draining无窗口时禁用并说明，不能偷偷新建；待approval/问答卡片绑定run+token，用respondInput和原恢复模式，不落为普通文本发送。Stop命名“停止当前任务”，绑定显示的run/generation；仅后台活跃仍可点击，提示逻辑取消与外部停止确认不同。queued新任务提供按其明确ref取消，不能取消active旧任务。Channel Agent如代用户steer/停止源run，须显式操作意图与额外运行控制grant，observe/notify权限不能调用这些写动作。

## 4. 总体架构与所有权

### 4.1 拟新增组件

```text
OCG authorized inbound
  → ChannelBridge 捕获 OriginGrant（不进用户文本）
  → 按 run + origin 隔离的渠道 execution
       → inspect_session / watch_session 工具
       → HostRequestBridge（可信 stdio 请求 + 显式结果）
       → Desktop SubscriptionService
            ├→ SessionAccessPolicy + OriginStore
            ├→ WatchStore（prepared → active / matched）
            └→ SourceEngine lifecycle/bind（原子绑定 / 已结束快照）

SourceEngine runtime（受允许 source session）
  → root admission / TaskRunCoordinator / required adapters
  → 自动 Task Run LifecycleJournal / Snapshot
  → session/lifecycle notification（低频）
  → Desktop WatchStore 事务（source fact + matched + outbox）
  → NotificationDispatcher
  → OCG proactive send → 微信 / QQ 等原渠道

主窗口 / 渠道后续询问 → list_session_watches → 关注与投递事实
后续扩展               → AgentInbox + 独立 enqueue / completion sink
```

### 4.2 职责分工

| 层 / 拟新增组件 | 负责 | 禁止承担 |
|---|---|---|
| core-ai runtime `TaskRunCoordinator` / typed observer | root admission、execution 收尾、required job / continuation 账、disposition、版本与 terminal fencing | 管理业务 Ticket；理解 IM 地址；用模型手动声明终态 |
| `TaskRunSourceAdapter` | launch 前登记、generation 因果身份、settle/consume/processing 交接与覆盖证据 | 仅监听 TaskStatus 或返回 taskId 后才补归属 |
| app-server `SessionLifecycleSource` | Task Run 权威快照、atomic bind、低频 journal、pin/replay；自动安装 | 保存 IM 凭据、同步调用 OCG、让模型发布终态 |
| app-server `HostRequestBridge` | 根会话工具请求 main，并得到明确业务结果 | custom event/card 冒充授权；在 RPC dispatch 线程等待自己的响应 |
| Desktop `SubscriptionService` | 校验、固定 scope、建立/取消/列举 Watch、处理 source signal | 模型式轮询、修改源会话工作 |
| Desktop `SessionAccessPolicy` / `OriginStore` | 校验真实来源、workspace 读取与通知 grant、撤销 | 解析 prompt 中“我是管理员”授予权限 |
| Desktop `WatchStore` | 原子保存 Watch、匹配事实与通知 Outbox、操作幂等 | 用 best-effort save 隐藏失败；保存完整会话历史 |
| Desktop `NotificationDispatcher` | 固定模板、按目的地有序发送、重试/blocked/unknown | 自动改地址、自动转群为私聊、调用 LLM 判成功 |
| OCG | 平台 I/O、账号、授权、发送结果；其发送幂等由 OCG 实现 | 认识 core-ai workspace/session/Watch、访问本地会话文件 |
| Renderer | 展示关注和发送状态、显式取消/补发/配置 | 成为生命周期唯一观察者或直接读 store |

**为什么是 main 订阅宿主：** main 已汇聚 workspace engine 事件，并持有真实入站路由和 OCG 客户端；它不随当前窗口切换消失。engine 只管自己的运行事实，必须支持无 renderer、无 LLM 的查询/回放。未来 server 复用相同概念，不复用 Desktop userData 文件。

### 4.3 事件注册不交给模型

用户提出的“会话注册自己的生命周期事件”落为**运行时自动接入**：

- build/resume 根会话时安装 source observer；source 存在不意味着某人已经获准订阅。
- root admission 时生成并持久 register `task_run_id`，driver invocation 才另生成 `execution_id`；job launch 前 reserve 因果身份，都不是模型填写。
- Observer 使用 typed host capability 注入；默认其它 CLI/server 宿主禁用或无-op，不能使所有现有客户端突然获得 Desktop 外发能力。
- 保留旧 `session/event`，新增版本化 `session/lifecycle`；旧 UI Notify 和流式渲染不被替换。
- `status_change=idle` / 旧 execution terminal CAS 只是执行事实；coordinator 聚合 root draining、required/continuation、disposition 与 fence 后才生成一次 task_run.ended，不能只换事件名。

### 4.4 首期边界与扩展点

- **source**：仅 Desktop 托管、具有 `task_run_lifecycle_v1` capability 的本地 app-server；required 后台 adapter 是首期语义前置而非统一后置。独立 CLI/远端 session source 后续；本地 run 发起的远端/media 工作是否可覆盖须逐 adapter gate，不能因不在本地进程就忽略。
- **workspace**：默认仅原渠道绑定 workspace；跨 workspace 要由本机 UI 对该请求者显式授权 `observe + notify_status`，不能由工具传绝对路径扩大范围。
- **destination**：只能原入站消息的同一 channel/account/target/chatType；不要使用 `settings.notifyTarget` 兜底。
- **action**：首期 `notify_origin` 固定文案；预留 `resume_agent` 的内部接口，但不开放 schema、回调 prompt 或任意 webhook。
- **privacy**：首期单聊；群聊默认拒绝关注与跨会话详情，未来显式开启“群内可见”授权再做。

## 5. 权威生命周期、身份与持久事实

### 5.1 标识分离

| 标识 | 生成 / 用途 | 不允许替代它的值 |
|---|---|---|
| workspace_id | canonical workspace 稳定本地 ID | basename、用户路径、lastWorkspace |
| session_incarnation | 新 session 持久随机 ID；删除后不复用 | 标题、可复用文件名 |
| engine_instance_id / run_generation | engine 启动随机 ID；run admission 持久 generation，用于 liveness/fencing | PID、重启后从零开始的裸 counter |
| task_run_id | runtime 接受 root、开放 receipt 前持久 register；Watch 的固定目标 | turn_id、execution_id、业务 Ticket ID |
| execution_id | 每 driver invocation 生成，属于一个 run | caller turn_id / IM message ID |
| request_id→task_run_id | typed、持久、principal/入口 scoped 输入映射，含 queued/attach/dedupe 状态；execution 消费归属另记 | 从正文/当前 running 猜归属 |
| job identity / continuation_id | incarnation/run generation + backend/owner + jobId/generation/parent；预注册 reservation | 只有 taskId；XML 解析结果 |
| lifecycle_seq / run_version | incarnation 下 journal 顺序；coordinator run 状态版本 | text chunk seq、wall-clock |
| watch_id / delivery_id | main 生成；关注/外发业务幂等 | 目标显示名、通知正文 |

workspace canonical 化复用 `CanonicalPath` 原则；key 不用 `workspace::sessionId` 字符串或 basename 判等。同名路径历史归属不明确则拒绝绑定，不假称已解决目录迁移。run ref 对 owner/scope/TTL 有界；它不是可复制的运行授权。

### 5.2 Task Run 快照（拟新增）

```json
{
  "schema_version": 1,
  "workspace_id": "ws-local-1",
  "session_id": "cli-example",
  "session_incarnation": "inc-1",
  "engine_instance_id": "engine-1",
  "lifecycle_seq": 42,
  "runtime_state": "loaded",
  "observation_state": "authoritative",
  "active_task_run": {
    "task_run_id": "run-1",
    "run_generation": "gen-1",
    "version": 8,
    "request_ids": ["request-1"],
    "state": "awaiting_async",
    "root_disposition": "awaiting_async",
    "active_execution": null,
    "required_jobs_unsettled": 1,
    "launch_reservations": 0,
    "pending_continuations": 0,
    "reserved_continuations": 0,
    "coverage": "complete",
    "started_at": "2026-11-20T06:00:00Z"
  },
  "last_ended_task_run": null,
  "queued_task_run_refs": [],
  "detail_handle": "opaque-bounded-handle"
}
```

- `state=queued|running|draining|awaiting_async|awaiting_input|needs_attention|terminal`；execution phase 单独含 starting/running/draining，旧 `turn_complete` 到 finishTurn 之间仍不能 ended run。
- 数量是 coordinator 账的投影，**不能只凭全零终结**；还须 §3.4 disposition、最后正常 execution、coverage 与 fence 条件。state 含仅后台活跃，不要求 driver running。
- `coverage=complete|untrackable|unknown`；`observation_state=authoritative|unavailable|legacy|storage_error`。无 engine、外部 owner 或 legacy 不返伪 idle；不同 engine 的旧 active run 须恢复核查，不当作还在可靠执行。
- `TurnDriver.setOnIdle` 实际是 ownership heartbeat（`core-ai/src/main/java/ai/core/session/TurnDriver.java:79`），不用它作结束 hook。需要 typed admission、launch、settle、consume 和 execution draining hooks。
- 查询 journal 的只读投影与受限 transcript，不 `session/resume`、不拿执行权、不启动 LLM；新增 read-only reader，不能用会修复/创建文件的 `TranscriptStore.open`。idle-evict 后保留 ended 可查；尚有 required work 不应仅因 driver idle 被 eviction，否则明确 interrupted/unavailable。

### 5.3 低频 source 事件（拟新增）

对外发布 `task_run.registered`、`task_run.state_changed`、`task_run.ended` 和观察失效/删除信号；once Watch 只匹配本 run 最后 ended。waiting/running 可供进度查询或后续 attention 订阅，不每个 model step 外发。execution started/ended、job 和 continuation 账变更保留为内部事实，旧 UI `session/event/turn_complete` 不变。

```json
{
  "schema_version": 1,
  "event_id": "inc-1:43",
  "lifecycle_seq": 43,
  "session_incarnation": "inc-1",
  "task_run_id": "run-1",
  "run_generation": "gen-1",
  "run_version": 12,
  "type": "task_run.ended",
  "outcome": "returned",
  "reason": "required_work_settled_and_processing_finished",
  "occurred_at": "2026-11-20T06:15:00Z",
  "duration_ms": 900000,
  "coverage": "complete",
  "external_stop_state": "not_applicable",
  "output_ref": "bounded-reference-only"
}
```

`outcome=returned|failed|cancelled|limited|interrupted`；缺 ended 的 crash 先记 `observation.unavailable` 与 run unknown，不能合成成功。明确中断记录来源与恢复依据，不回放副作用。returned 要 coverage complete；取消可伴 `stop_confirmed|cancel_requested|timed_out_orphan|unknown`，不能写“全部进程已停止”。output_ref 只供获授权的详情读取。

### 5.4 LifecycleJournal 与现有 transcript 的关系

**建议新增 app-server-only 小型低频日志，不将整个 transcript 改造成通用 Event Bus。**

- 新 journal 在 session 目录旁保存 incarnation、Task Run register/state/ended、typed request mapping、execution 边界、required job generation、launch 与 continuation reservation/settlement、seq、观察失效及 bind/pin；带长度/校验，flush/fsync 后才宣告 durable。只记录低频账变更，不记录 model step/chunk。
- 复用 engine/session 单写者锁，不让 Desktop 写源日志；root admission register commit 后开放 accepted receipt。内部内存更新可以先显示 persistence_pending，不冒充 durable。
- launch reservation 在 side effect 前固定并提交 journal；continuation reservation 也持久记录，不能把非 durable SessionCommandQueue 当恢复依据。job settled 与所需 reservation 在同一个账版本/日志转换内，commit 后才交接队列；消费 transfer、root draining 与 terminal 按同序处理。ended 只在 §3.4/§3.5 条件或明确终止 fence 确定后落盘，再发信号。
- **恢复账，不承诺自动恢复执行**：新 engine 先 fence 旧运行权限，核对 queued/root in-flight、launch 歧义、required jobs 和 reserved continuation。有可信外部查询/本地 ended 就更新事实；无证据为 unknown/needs_attention/interrupted，旧 run 不继承新 engine 的 active binding。reserved journal 表示处理义务存在，不证明已经/仍在执行；不 replay 可能已发生的副作用，重新执行须新授权/new run generation。
- snapshot 是 journal 的投影，不作为独立双写真相；原 `.data/.events.jsonl` 用于上下文与 UI 历史，保持原格式。
- 不声称 journal 与 `.data`、业务文件是同一事务：即使 ended 存在，也不代表全部交付产物已验证或磁盘上的所有业务写入成功。
- 通知链写失败不阻塞已运行 driver/dispatcher；source 标 storage_error，拒绝新可靠 Watch，已有关注走退化诊断。**admission/launch 的 durable 前置失败则拒绝接受或启动相应副作用**；不能一边继续未登记 required 工作一边宣称完整 coverage。已有 in-flight 账不可信时 unknown/attention，不产生可靠 returned；非 durable 实时异常事实只能占一个明确“运行状态无法可靠留存”的降级槽，同 run/Watch 的 normal/diagnostic 互斥，重启不重复正常结果。
- 日志恢复只修复末尾不完整帧，不吞掉中间损坏；损坏返回明确诊断并停止可信回放。
- **逐 Watch 持久 lease**：bind 记录 `watch_id→incarnation/task_run_id/run_generation/expiresAt/recoveryUntil`，commit 才 bound；same watch_id 改目标冲突。本 run 的保留截止取未释放 lease 最大 recoveryUntil（建议 expiresAt+24h，最大8天），覆盖 run 终态/恢复所需投影；取消只释放自己的 lease，不能动他人 pin。终态复制到 main 事务后可 ack 提前释放。
- source 按 recoveryUntil 而非 Watch expiresAt 清理 lease/terminal；active run 的 required/continuation 账不受 Watch 历史 TTL 淘汰。无 bind 的 ended 近历史建议7天/每 session 256条；request 去重 mapping 至少覆盖同入口重投/恢复窗口，过期键保留有界去重 tombstone；过期/历史归属不明返回 REQUEST_MAPPING_EXPIRED/UNKNOWN，不自动重接受同 key。容量满拒绝新 admission/launch/watch，不静默丢 pin 或 in-flight 账；具体限额由 G0/Q9 校准。
- **删除fencing**：源session删除前在workspace级持久tombstone日志记录 `incarnation/deletedAt/deletionSeq`（不含标题、正文），设置deleting状态；main在事务内撤销Watch/未claim通知，再允许清理源journal与历史。重复删除幂等，晚到ended不复活已撤销Watch。tombstone至少保留最大恢复窗口+一天，优先于同incarnation其它事实。
- Desktop删除入口先将本地Watch/Outbox revoke再调用源删除；直接source RPC删除也必须先写tombstone并发送低频删除信号。main恢复时先reconcile tombstone/revocation，再开放dispatcher；普通normal-result delivery在dispatch claim前针对固定source验证未删除/未deleting，source不可核验则hold。诊断delivery不要求source仍存活：source_deleted凭可信tombstone、source_unavailable凭已仲裁的不可用事实、expired凭期限/校准记录及仍有效notify grant发送；诊断不带已删正文、不绕过owner或grant撤销。校验后紧接删除、已claim的网络发送可能仍完成，按SEND_MAY_HAVE_STARTED说明边界，不能保证撤回已外发消息。
- 删除清理源数据；main不保留完整输出绕过删除。删除信号/关闭cancel与terminal竞态按tombstone优先级仲裁，未claim的source通知不得随后发送。

相比从 transcript 猜任务边界，journal 增加 admission、job/continuation 账变更及终态的有界低频写入（不是只加一次结束 fsync），但给关注提供稳定身份、pin 与恢复事实，且避免冻结高频 UI transcript 契约。

### 5.5 固定 Task Run 的原子绑定，不是裸 listen callback

`lifecycle/bind` 在 coordinator/source 同一临界区检查 incarnation、run generation、权限 scope 和 coverage，固定 Task Run、注册 watch_id/pin、取版本快照及本 run 已存 ended。queued、仅后台活跃、waiting 和 terminal 都可显式绑定；返回 bound/already_ended/pending 或明确拒绝。

- inspect handle 固定当时 run；新 run 启动不改变旧 ref。无 ref 的 current 只选当前 active run，无则 NO_ACTIVE_TASK_RUN，不偷选历史/下一任务。
- prepared/bind 期间 run ended 可回放；快照与通知可能重复，用 event_id/run terminal identity 去重。
- bind timeout 用同 watch_id/bindStatus 重试，不重选 current。
- source 不持有 coordinator 锁做 HTTP/LLM/fsync，也不在 dispatcher 阻塞存储：短临界区固定 run/version/事件顺序，提交有界单writer I/O队列，逐序落盘；pending 不是 durable/bound。terminal fence 先固定唯一 outcome，再日志 commit；snapshot 可显示 persistence_pending，不冒充 durable。
- I/O deadline 建议2秒，满/超时 source 转 storage_error；已运行代码有界退化，未 commit 的 admission/launch 不启动副作用；OS阻塞fsync本身不能可靠取消，所以隔离writer、停止继续排队且不创建无限替代线程。超时后晚完成仅按固定operation/version记录，不能自动把已失败登记回复改成成功。恢复核对落盘事实后才能重新开放source；G0必须注入disk-hang、fsync失败与late-commit验证，而不只是测试普通写异常。
- NotificationSink只承担传输，事实仍在journal；它的发送不能决定 Task Run 结束。main store同样在隔离单writer中处理短事务，dispatch线程不阻塞数据库I/O；pending/操作超时通过已固定operation查询恢复。

### 5.6 Source adapter 能力 Gate（首期前置）

| Adapter | 当前可复用事实 | 允许宣称 reliable returned 的条件 |
|---|---|---|
| BackgroundTaskManager / child managers | submit 前 completion/running；generation、wait 消费；child 共享 completion 但 running map 各自持有 | 所有 child launch 在副作用前进入同因果账；remove-before-publish 改为 settle+reserve；跨 child 的 required 账完整；notify/consume 不漏 processing |
| AsyncToolTaskExecutor | submit 前 registry/tasks，工具返回后 bindOwner | runtime launch token 预绑定 run/owner/generation；即时完成也能关联；工具 pending/终态路径均闭环，不能只在返回后补登记 |
| tool-managed async manager | 持久 task、poll、terminal listener；不是统一 producer | launch reservation、generation、防重复、host poll/completion producer、输入等待及结果 processing/ack 接入；未安装 producer 不表示无 required 工作 |
| media/video（Agent 工具与 plugin RPC 分别验证） | 远端 job/status，plugin jobs 内存 map、刷新时下载保存 | typed 因果来源、launch 预登记、poll 终态及下载/产物收尾、processing policy、停止确认和 restart 查询；不按 provider completed 提前结束 |
| SDK out-of-turn / 外部 shell 服务 | 不保证处于 root scope，进程可能脱离宿主 | 明确 launch capability 与 causation，或经显式 policy detached；不能借观察 grant 或将未知进程默认无关 |

handshake 暴露 `task_run_lifecycle_v1` 和具体 adapter/coverage capabilities；complete 是本 run 启用的全部 required 启动路径的证据，不是“我们实现了一个 manager”。未支持路径在可预知的 launch 前拒绝可靠跟踪/启动；若既有 run 已发起不可跟踪工作则显式 untrackable/needs_attention，已有 Watch 走观察退化诊断，新可靠任务 Watch 拒绝，**绝不退回 execution ended**。可先交付有完整支持路径的范围，但必须展示限制；不能用 unsupported 作为忽略后台的免责提示。

## 6. 来源授权、Agent 工具与跨进程协议

### 6.1 OriginGrant：原渠道身份是宿主数据

ChannelBridge 在接受入站消息时创建不可由 LLM 改写的 OriginGrant：

```text
origin_id, inbound_message_id, requesting_session_incarnation,
channel, account_id, target, chat_type, sender_id, opaque_session_key,
workspace_grant_id, policy_version, accepted_at, expires_at
```

- 原地址和 sender 仅存 main；engine 收到不透明 origin_id 与执行范围，不把 token/地址塞进 prompt。
- `sessionKey` 是连续性键，不是权限身份。若 OCG 配置 main/shared scope 把不同人合到同一 sessionKey，仍按 account + target + sender + grant 隔离渠道会话；不让不同请求者共享有权限的本地详情。
- **所有入口 origin partition 条件保留**：UI `session/send`、forward/enqueue、schedule、task_notification、steering、SDK 均有 typed input origin（无来源显式 none）与 run causation。batch 只能合并同 run、同授权 scope、compatible principal/grant、同合法 mode 的输入；无 origin / 不相容 origin 不得混入授权 execution，即使同 run 也不例外。steering 同时过 §3.2 state gate 与该兼容闸门。
- `enqueueMessage` 不保证一消息一 execution；需要 run+origin partition / steering admission。request→run 在接收时已固定且 queued 可查；request→execution 只记真正消费的 batch/steering，未消费重排不得假称完成，不得默默换 run。
- 将 **运行发起者 authority、观察者 OriginGrant、run causation** 分为 typed scope。execution 进入时安装且 finally 清除，不放长期 `customVariables.putAll`，不复制 channel capability 给子 Agent；无 origin 清空旧 scope。required continuation 可使用源 run 原始最小 authority（仍有效才执行），不会继承登记 Watch 的 peer grant；源 authority 撤销须 attention/终止，不用观察权限补足。
- ChannelBridge 普通 reply 的 pending 依据 typed request/execution 精确消费收尾；Watch 则等 task_run.ended。无 ID task_notification 不得完成入站 pending，不从 XML 或当前会话身份猜。此归属修正是关注闭环前置，不是只加工具。
- root channel Agent 的关注工具才能使用 OriginGrant；子 Agent、SDK out-of-turn 和 custom_event 不能借此外发。需要 delegated capability 时另做最小授权，不复制父 scope。
- 纯 Desktop UI 会话不带 channel origin；调用 `watch_session` 返回 `NO_ORIGIN`，需要用户显式 UI 选择目标的未来入口，不用全局 target 冒充当前渠道。

### 6.2 Workspace 与请求者权限

OCG 的入站 allowFrom/pairing 证明“消息被网关接受”，**不证明请求者能读任何 workspace 的会话**。Desktop 新策略必须显式给出 `observe_session + notify_status` grant，详情读取另要 `read_session_tail`。

- 首次需要本机 UI 确认该 peer 的可观察 workspace；不自动把已有 last/default routing 升级成跨会话授权。
- search/inspect/watch 统一使用 SessionAccessPolicy；不能 search 无权限标题再在 watch 阶段拒绝。
- 跨 workspace 只可传宿主返回的 opaque source handle；不能接收任意路径。source handle 绑定请求者、workspace、incarnation、Task Run/generation 与短期有效期，不直接当 bearer token 打印。
- 源 workspace restricted、删除、移出 grant 时暂停/撤销关注与出站；不拉起引擎恢复读取权限。
- 关注 TTL 不超过 grant TTL；投递时再次校验 main 的本地通知授权与策略版本。
- OCG 当前没有可用的实时 peer ACL 查询；本地 grant 撤销是首期权威开关。仅在 OCG 外部编辑 allowFrom 时，Desktop 无法保证立即知道撤销；UI 必须说明，不能拿 targets 历史当实时 ACL。未来可给 OCG 加 ingress-policy capability 查询，仍不代替 workspace grant。
- group/channel 首期拒绝；不自动用群 sender_id 构造私聊地址。

### 6.3 首期工具契约（拟新增）

| 工具 | 输入（LLM 可见） | 行为 / 输出 |
|---|---|---|
| `find_sessions` | query?、limit（默认5/最大20） | 仅允许 workspace 的标题/近期摘要、真实观察状态和 opaque session_ref；空 query 列当前受控活跃会话 |
| `inspect_session` | session_ref 或同 scope session_id；request_ref?；tail 可选（默认0/最大6） | Task Run 快照、current/queued/last ended task_run_ref；typed request_ref 精确解析 queued mapping。tail 独立权限、返回硬限8KiB，不暴露 SYSTEM/工具原参数 |
| `watch_session` | session_ref/session_id、scope=`current_task_run` 或 `explicit_task_run`、task_run_ref?、expires_in_seconds? | notify_origin + once；explicit 必填 ref；current 不选后续 queued/下一任务。返回固定 run、coverage、active/matched/registration_pending、TTL 与投递状态 |
| `list_session_watches` | watch_id?、cursor?、limit（默认20/最大50） | 当前 origin owner 的关注、匹配、发送状态；可核对承诺，不只返回模型记忆 |
| `cancel_session_watch` | watch_id | 仅 owner 可取消；停止关注/未发送通知，不取消源执行；发送中告知可能已外发 |

`watch_session` 不允许 channel/to/account/workspace path/任意 event filter/回调 prompt/成功措辞参数。一次关注覆盖 ended 的所有 outcome，不能让模型只听 success 导致失败仍沉默。

成功例：

```text
watch_id: watch-1
registration: active
source: 本地会话「报表任务」 / run-1 / gen-1
scope: current_task_run / once
coverage: complete
notification: original_channel / status_only
expires_at: 2026-11-21T06:00:00Z
condition: task_run.ended, including failure, cancellation or limits
```

注册 pending 时只能说“关注正在确认，暂未完成登记”，不能说已注册；工具可有界等待 host 返回，但绝不等待目标任务结束。

默认 TTL 建议24小时，最大7天，最低60秒；日期由宿主校验与夹限，返回实际值。TTL 到期不改源任务；记录 expired，原渠道一条“关注已到期，未确认所关注任务运行结束”，避免承诺无限悬空。过期/source unavailable/delete诊断使用独立delivery kind和有限投递期限（建议24小时），不能沿用已到期Watch TTL导致刚建就被清理；只有仍有效的本地notify grant允许该诊断。active状态首次终止转换只建一条诊断；授权撤销时不为解释撤销再外发。

### 6.4 HostRequestBridge：复用可信 stdio，显式返回结果

不为了本功能加全局 HTTP daemon，也不让工具通过 send_custom_event 请求外发。

```text
Engine tool worker → notification host/request
  {requestId, operation, rootSessionId, requestingTaskRunId, executionId, originId, typedPayload}
Desktop RpcClient → 非阻塞异步处理 → main SubscriptionService
Desktop → RPC host/respond {requestId, ok, result, error}
Engine dispatch → 完成 pending future → 原工具返回
```

约束：

1. handshake 显式协商 `desktop_host_requests_v1`；老 engine/host 返回 `WATCH_UNSUPPORTED`，不退化为模型轮询。
2. host/respond 在 app-server dispatcher 上**只完成 future**。调用工具在独立 turn/tool worker 上等待短 RPC 结果；如果在 dispatcher 上阻塞等待 main 再调用 source RPC，会死锁，必须拒绝该路径。
3. requestId 是传输相关 ID；watch_id 是持久业务 ID。connection/engine_instance_id fencing 禁止旧引擎响应解开新请求。
4. 超时建议10秒：main 可以先返回 registration_pending；engine 工具超时不默认撤销已登记操作。重试按同 origin/inbound/tool intent 去重并查询既有结果，不创建第二条。
5. main 依据自己的入站 OriginStore 校验 request 的根会话/执行/operation，不信任 payload 中给出的地址或“authorized=true”。
6. JSON payload 最大16KiB、每 root execution 并发请求上限4；明确 allowlist operation；不得暴露任意 main IPC。
7. tool-visible source refs 和数据是模型输入，正文/标题转义且截断；可读数据不含 origin token。
8. 请求 owner 来源取请求者 channel execution 的 typed scope / admission binding；与被观察 source run 的运行发起者 authority 分离，不靠长期 `customVariables.putAll`。

### 6.5 Source RPC（拟新增，仅 main ↔ app-server）

| 方法 / capability | 语义 |
|---|---|
| `task_run_lifecycle_v1` + adapter capabilities | Task Run 自动事实、identity、journal、coverage、readonly snapshot/bind；旧 execution capability 不满足本契约 |
| `lifecycle/inspect` | sessionId / typed requestRef / taskRunRef；只读当前、queued mapping 或固定 run，返回 authority/coverage/unknown |
| `lifecycle/bind` | watchId + incarnation + 固定 taskRunId/runGeneration；原子 pin、版本快照及 ended 回放 |
| `lifecycle/bindStatus` | 同 watchId 查询超时/响应丢失；不重新选择 current |
| `lifecycle/replay` | 仅指定 Watch/Task Run 事实，afterSeq + bounded limit，缺记录返回 gap |
| `lifecycle/unbind` | 仅幂等释放该 Watch lease，不停止运行 |
| `session/lifecycle` | 低频 source event，含 event_id/seq/incarnation/task_run_id/run_generation/version |

main 不要给每个 Watch 建独立 stdout/SSE 连接，按 workspace 复用已有 supervisor transport，按 Task Run/generation 索引扇出。

## 7. WatchStore、匹配事务与注册时序

### 7.1 持久化方案选择

**建议 main 独占一个本地 SQLite store**：`userData/observations/observations.db`。Watch、operation 幂等、已摄入 source facts、outbox 在同一数据库内提交事务。

- 推荐先验证 Electron 自带 Node 对 `node:sqlite` 的可用性、Windows/macOS 打包和并发测试；当前 package.json 没有 SQLite 依赖，不能写成已有能力。
- 这是实施前技术 gate：用实际打包 runtime 验证，不能只在开发机 Node 上成功。gate 不过则提交 ADR 修改（例如评估已打包 SQLite binding），不能静默回退到吞写失败的 JSON。
- 单 writer，事务极短；外发不在事务内。WAL/同步持久性配置采用明确策略，建议 WAL + synchronous=FULL；busy/write/fsync 失败返回错误，不承诺落盘。
- 不复制 `.data`、完整 transcript 或账号凭据。DB 与 sidecar 的 OS 用户访问权限一致；它不构成对本机管理员或已获完整 shell 的恶意代码的安全隔离。
- SQLite 事务只覆盖 main 自己的登记/出站账，不包括 source journal 或 OCG；跨进程窗口由 bind/replay 和出站 unknown 处理。

选 SQLite 而不是沿用 settings.json 的原因是要原子完成“匹配关注 + 创建 Outbox + 去重记账”；增长中的投递队列也不该频繁重写整个 desktop settings。不是为本地通知引入服务端数据库或消息中间件。

**可行的更轻备选**是独立的单writer JSON事务快照：Watch+Outbox+去重键同一文件、写临时文件→flush/fsync→原子替换、严格错误返回、有限条数和恢复校验；并非JSON天然不能原子提交。没有SQLite运行时支持、短期仅几十条关注且不需要长投递历史时可选此路线。本文仍建议SQLite，是为了避免自己维护事务/索引/清理协议；不是为了本需求必须引入数据库。不得沿用现有BridgeStore的吞写异常实现。G0不过时可以提交此轻量方案ADR，保持同样持久化契约，供用户确认。

### 7.2 最小数据模型

| 表 / 主键 | 核心字段 / 约束 |
|---|---|
| `origins` / origin_id | owner tuple、channel/account/target、requesting session、grant/policy、TTL；私有路由数据 |
| `watches` / watch_id | origin_id、source workspace/incarnation/session/task_run_id/run_generation、scope/action、coverage、state、preparedAt/expiresAt、matchedEventId |
| `watch_operations` / operation_key | origin owner + inbound id + source incarnation + scope/action；explicit 请求含固定 run/generation。current 意图先查既有 operation，绑定 run 仅存结果，不因新任务重选；不用随机 functionCall.id 独自去重 |
| `source_facts` / (incarnation,event_id) | bounded Task Run event、run/generation/version、seq、observedAt；去重，不存正文 |
| `deliveries` / delivery_id | origin target snapshot、watch/event、fixed body、state、attempt、nextAttemptAt、unknownReason、receipt、TTL |
| `delivery_attempts` / attempt_id | dispatch before-send 记录、gateway attempt/clientSendId、结果、时间，不打印敏感路由 |

`deliveries` 在 (watch_id,event_id,action) 上唯一；同一事务：插入 fact（或已存跳过）、active → matched、插入 Outbox。无活动 Watch 的事件可以不长期复制，但 source 仍保留有 pin 的事实。

### 7.3 注册协议：prepared 也是可恢复状态

```text
1. 验证 origin、source scope/handle、权限、TTL 和能力。
2. 先查稳定 operation_key（owner + inbound id + source incarnation + scope/action；explicit 含 ref），**先查再选 run**。无既有的 current 请求只读 inspect 固定 active Task Run（仅后台也 active），无则 NO_ACTIVE_TASK_RUN；explicit 解析已接受 request/queued/terminal ref。main 事务写 prepared + **不可变 task_run_id/run_generation** + origin/coverage snapshot。短期 handle 过期不改变已落盘宿主身份，恢复按同固定 ID/bindStatus，不重挑 current。
3. main 调 source lifecycle/bind（固定 watch_id + run/generation）；同临界区校验/lease/快照。inspect 后旧 run ended 则回放它，新 run 不影响。**prepared 不得保存“以后再选 current”的动态目标。**
4. source 返回 bound/already_ended/pending/明确拒绝；pending 经同 bindStatus 等 commit，低频事实可同时到达。
5. main 事务：保存绑定；prepared → active，或 → matched + Outbox。
6. 工具返回真实登记结果；源结束时无需原渠道 Agent 活着。
```

- **只要 main 已 prepared**，之后收到对应 terminal 先暂存 fact，再等确定绑定；不能因还未 active 而丢弃。
- source 成功 bind 后 main 崩溃：启动读取 prepared，按 watch_id 查 bindStatus/replay，继续完成登记。
- source 成功/失败响应丢失：same watch_id 查状态，不能重挑当前 Task Run。
- source 拒绝或 TTL 到期：prepared → failed/expired，释放 pin；记录稳定原因。
- operation 返回前已有 terminal：可以返回 matched/queued，不强行先发送“已关注”再结束通知。
- 原入站消息回复发送失败/重复送达：Watch 仍依据同 operation_key 可查，不再次登记；关注承诺与原消息回执是两个独立账。
- 若持续 registration_pending，恢复器最终给成功/失败状态；首次 deferred 超过10秒须让渠道回复包含 watch_id，便于查询，不假称完成注册。

### 7.4 截止语义与 Watch / Delivery 两套状态机

- `watch_expires_at` 是源事实的匹配截止，`recovery_until=expiresAt+24h` 是晚到事实恢复期限；`delivery_expires_at` 是独立的出站期限。统一匹配口径：runtime在短临界区确定terminal事实的occurred_at不晚于watch_expires_at，并在recoveryUntil内取得该事实的durable journal证明，即可matched；journal实际提交可以晚于Watch截止。seq只确定顺序/去重，不能单独证明时间；不按main收到时刻误判过期。若恢复期内仍无durable证明则按退化/不可用处理，不伪造可靠terminal。
- 到 Watch 截止，先校准固定 Task Run/generation。source可达且确认在截止前无terminal事实、也无该时间范围内的pending journal提交则expired；存在截止前terminal的persistence_pending，或source暂不可达，则进入expiry_pending等待durable证明/恢复，不马上发到期诊断。恢复期限到仍无法核验则source_unavailable并仅生成一条diagnostic；任何诊断仍需有效notify grant。
- 已匹配normal通知的delivery期限建议为min(恢复/匹配后24h, source ended后8天, notify grant截止)。到期/删除/不可用诊断独立delivery期限24h，但不能延长grant。诊断未claim时如果校准获得截止前的可信terminal，撤销诊断并换成正常结果；已claim诊断则只在本地更新事实，不再自动第二次外发，避免矛盾重复；具体情况可查询。
- Watch TTL及grant到期都不能被用户补发绕过；若授权已经失效，只保留本地记录，不承诺原渠道必收到诊断。源时钟大幅回拨/日志时间不可信则返回observation_unavailable，不据此无限延长Watch。



```text
Watch:
prepared → active → matched
         ↘ failed
active/prepared → expiry_pending → matched | expired | source_unavailable
active/prepared/expiry_pending → cancelled | revoked

Delivery:
queued → dispatching → accepted
                  ↘ retry_wait → queued
                  ↘ blocked
                  ↘ unknown | partial | permanent_failed
queued/retry_wait/blocked → cancelled | expired | revoked
```

- matched 仅表示关注条件满足；不表示用户已收到通知。
- source_unavailable 是独立诊断，不能把旧 Task Run 绑定到新 engine 的新任务/generation。
- 用户取消与 dispatcher claim 在同一 store 事务仲裁；claim 前取消阻止外发，claim 后可能已经送出，返回 `SEND_MAY_HAVE_STARTED`。
- 源 session 删除：取消相关 Watch 和未 dispatch 的 source 通知；可以发不含标题/输出的“关注对象已删除”诊断（仅授权仍有效时）。已 accepted 的外部消息无法撤回，不虚假承诺清除。

### 7.5 容量、TTL 与清理

建议首期上限：每 origin owner 50 active、profile 200 active；总未结束 Outbox 1000、单正文<=600字/2KiB；历史保留7天，accepted 后正文建议24小时内删除，仅留状态/脱敏引用。参数是首期建议，需验收后调优。

- 创建Watch时同一事务预留一个终结delivery槽（normal或diagnostic互斥），未匹配前为reserved、不由dispatcher领取；matched/expired/unavailable转换将保留槽转成queued。200个active Watch的预算被包含在1000总队列上限，不会出现接受关注后才因正常Outbox满而丢通知。
- full → WATCH_CAPACITY_EXCEEDED，不能吞或静默淘汰未履约记录。若磁盘/事务失败，Watch不转matched，source lease保留并replay重试；不能先履约状态成功再漏Outbox。人工补发需独立容量预算和授权，不挤掉保留槽。
- 清理按watch_id释放source lease，再删主进程历史；unbind失败待重试，source也按该lease的recoveryUntil清理（不是expiresAt），避免main消失后永久pin或过早丢掉恢复事实。
- store 损坏/不可写 → 暂停新关注和出站，保留文件给诊断；不创建空 DB 冒充不存在关注。
- default24小时/最大7天是 Watch 可见边界；只绑定一个 run，不随后续独立任务永久生效。Watch TTL 不代替 run deadline，也不自动取消 required 工作。

## 8. 主动投递、去重与重启恢复

### 8.1 为什么不能用原入站 reply 长期等结果

关注注册时，原入站消息由 ChannelBridge 正常 reply 一次，说明登记状态。之后的结束通知必须走新的 proactive send：它不是把已 reply 的入站消息再次答复，不能长期占住 mailbox lease/reply handle。

v0.2 已核对的 OCG 源码事实（v0.3 保留证据；不代表本机部署同一版本）：

| 事实 | 证据 | 本设计处理 |
|---|---|---|
| send 明确 stateless，无 queue/retry/idempotency | `D:\openclaw-channel-gateway\src\send-service.ts:19`、`:80` | 不把 clientRef 当幂等；旧网关未知结果不自动再发 |
| 超时返回 uncertain，in-flight 仍可能发送；分片可 partial | `D:\openclaw-channel-gateway\src\send-service.ts:383`、`:650`、`:701` | 保留 unknown/partial，不能压成确定未发送 |
| messageId/receipt 可空，最后成功分片不等于整条已送达 | `D:\openclaw-channel-gateway\src\send-service.ts:682`、`:730` | UI 只说网关接受/插件报告发送，不说对方已读 |
| reply 的 clientReplyId 回执在内存，不能替代 send 幂等 | `D:\openclaw-channel-gateway\src\agent-routes.ts:498`、`:538` | 不复用原 reply 作为持久通知机制 |
| Desktop OcgClient 的通用错误提取会丢 uncertain/partial 等字段 | `core-ai-desktop/src/main/channels/OcgClient.ts:132`、`:284` | 必须先补 typed outbound result，属于本功能前置 |
| OCG targets 是历史已见对端，不是当前授权列表 | `D:\openclaw-channel-gateway\src\agent-routes.ts:730` | 不拿候选目标列表代替 origin grant |

### 8.2 通知格式与内容边界

首期固定中文、无 emoji、status-only，例如：

```text
你关注的「报表任务」这次任务运行已正常收尾。
必要后台工作已结束，按运行策略所需的结果处理已完成。用时：15分。
这不是业务质量验收结论。
任务运行：run-1。关注编号：watch-1。需要详情可以继续询问。
```

失败/取消/限制按最终 outcome 分类；取消明确“本次运行已逻辑终止；外部进程停止尚未确认”等 external_stop_state，不写全部停止。processing=`none` 不声称整理了结果，仅说明 policy 无需自动结果处理；untrackable/unknown 用观察退化诊断，不能套 returned 模板。标题须 metadata 权限，否则用 source alias。detached 服务若仍运行只作可选状态说明，不阻塞本 run；不能把 required 后台未结束放在正常终态文案中免责。

- 正文由 main renderer（模板函数）生成、绑定 event_id，重试不重新叫模型改写。
- 默认不附 final output/error detail；错误只给分类（如模型调用失败），全文在用户明确 read permission 查询后提供。
- 不附 SYSTEM prompt、工具参数/结果、绝对路径、账户/凭据、内部 stack trace。
- 必须标识 source alias/Watch 短号，避免一个渠道关注多项工作后无法辨认。
- 单条尽量不分片；平台限制仍由 adapter 报告，遇 partial 转人工，不盲目重发全部正文。

### 8.3 Dispatcher 与结果分类

1. 外发前校验本地origin/workspace notify grant、delivery期限、账号与固定目标；不因settings.notifyTarget改动而重路由。
2. 按delivery kind校验：normal-result要求固定source未删除/未deleting，不可校验hold；source_deleted/source_unavailable/expired诊断验证对应可信事实与有效notify grant，不要求source仍存活。所有kind都执行owner/grant/出站期限校验；诊断不带源正文。
3. main短事务重查本地grant/version、取消/删除fencing，claim queued delivery并写dispatching+attempt_id；事务后才发网络请求。source外部校验与claim之间的删除竞态受§5.4约束，已claim不能保证撤回。
4. 同destination `(channel,account,target,chatType)` 串行，profile并发建议4；不堵其它目的地。
5. 保存完整typed result：accepted、definitely_not_dispatched、rejected、blocked、unknown、partial，以及code/uncertain/chunks/receipt受限字段。
6. 只有确知未开始发送的transport/preflight失败可自动重试；平台报错不能仅凭HTTP502假定未发送。
7. backoff建议5秒、30秒、2分钟、10分钟、30分钟，jitter ±20%，上限8次且不超过delivery期限；准确失败分类由adapter明确提供。
8. 账号退出、目标无权限、主动消息窗口过期→blocked，等待重新配置/新入站互动后校验；不得后台发送“预热”消息。
9. request已发但返回丢失/uncertain/partial→unknown/partial；这些标记优先于HTTP状态和看似可重试错误码。所有重启遗留dispatching（包括可能未真正开始网络请求）默认unknown，有可信receipt或明确未dispatch证据才能改变；旧网关不自动重试。Desktop显示“可能已发送，补发可能重复”。
10. blocked重新校验原地址、grant、delivery期限、账号/平台；只有之前有明确未dispatch证据才转queued。新入站/配置恢复只是校验触发，不是自动授权重发；unknown/partial不能变blocked后盲目retry。校验失败仍blocked，永久拒绝或超过delivery截止→permanent_failed/expired。

`accepted` 是发送服务返回成功，不是用户收到/已读。用户不在线且平台禁止主动推送时，系统无法替代平台规则；只能留存待处理通知并在下次用户入站时报告。

### 8.4 OCG 可选的最小发送幂等增强

建议 OCG 后续/并行提供 `send-idempotency-v1`，但**首期可以在旧 OCG 上采用保守 unknown 停止策略**，不要求先改网关才能创建关注。

拟新增契约：

```text
POST /ocg/v1/send: clientSendId（独立于现有仅日志 clientRef）
GET  /ocg/v1/send-receipts/{id}: pending/accepted/rejected/unknown/partial
health.features: send-idempotency-v1
```

- 在 OCG 边界持久保存 `(consumer/credential-scope, clientSendId)` → 目标与 payload hash、attempt 状态/结果；复用 ID 改正文或目标返回冲突。
- 相同 ID 并发 single-flight；重复返回原结果、不再次调用平台；存储 TTL 必须覆盖 main 的重试/恢复窗口（至少 Watch 最大 TTL +1天）。
- 发平台前先存 dispatching；重启时 dispatching 无明确 receipt 归 unknown。**持久幂等键仍无法消除平台接受但网关尚未写 receipt 的窗口**。
- known-not-dispatched/rejected 允许通过显式新 attempt 机制重试；same ID 的 lookup 不隐式重发，文档需区分同 delivery 与不同发送尝试。
- Desktop 通过 capabilities 而不是固定版本号协商；无 feature 用旧契约保守行为，不伪装有可靠去重。
- `receipt` 不包含平台秘密，目标/正文日志脱敏；OCG 不存 core-ai session 路径，不负责 Watch 匹配。

该增强属于真正拥有传输契约的 OCG，不把通用幂等逻辑写成 Desktop 对 QQ/微信 host/port 特判。是否同期实施见 Q6。

### 8.5 本地恢复与 reconciliation

| 故障窗口 | 恢复行为 |
|---|---|
| main prepared 后尚未 bind | 按同 watch_id 和不可变 run/generation bind；不重新选 current。短期 handle 不改变已落盘宿主身份；记录缺失/gap 为 unavailable，不绑定下一任务 |
| source 已 bind、main 尚未 active | bindStatus/replay 同 run/generation，完成 main 事务 |
| task_run.ended durable、stdout 丢失 | replay 固定 Watch/run，match+Outbox；不依赖内存 set |
| main match+outbox 后未发 | queued 自动恢复发送（先重校验权限/TTL） |
| main遗留dispatching（任何崩溃窗口，包括请求可能未发出） | 有可信幂等receipt则查；否则unknown，不凭猜测改queued。确定not-dispatched证据才retry；不能只处理已知accepted窗口 |
| source engine 重启，旧 Task Run 有 ended | 固定同 run 已存终态通知，不重复匹配；旧 execution end 不足以替代 |
| source engine 重启，旧 run register/started 无 ended | 先 fence，核对 required/launch/continuation journal 与可查后端；unknown/attention/interrupted 诊断，不合成 success、不续跑副作用、不移绑 new generation |
| queued / reserved continuation 只有 durable 账，没有 durable 执行 receipt | 可核对处理义务，不证明执行已恢复；in-flight 歧义先 attention/interrupted，需新授权才重新发起 |
| 主动取消或撤销 | 不发送未 claim 的通知；发送中结果仍保存并说明不可撤回 |
| Desktop 退出/机器睡眠 | 没有驻留 dispatcher；下次启动先恢复事实和待投递。不可承诺关机继续执行/发送 |

**事件触发为主、低频校准为辅**：启动、引擎实例变更、管道 gap、系统 resume 和每60秒对 active/prepared Watch 合并校准一次。只查已关注 source，按 workspace 合并/分页、指数退避；不扫描所有历史会话，不调用 LLM。

source replay 有 afterSeq/gap；gap 时重新 inspect **同 Task Run/generation**。仍无可信终态则标 observation unavailable，而不是根据 last modified 或空闲时间补造完成事件。

### 8.6 与现有 UI Notify 和 ChannelBridge 的共存

- UI Notify 持续开关、profile target 与旧 execution 结束语义保留；Watch 是 Task Run 最终结束，不相互改写，取消 Watch 不影响 UI。
- 仅在 coordinator 能证明某 UI execution ended 对应同 Task Run 最终 ended、同 destination 时，才可按 Q8 的 common DeliveryCoordinator 合并且履约两账，status-only 优先。较早 root/continuation 的 UI 通知不能 fulfilled Task Run Watch，也不为等待 Task Run 而静默改旧 UI 行为。
- 未统一时说明可能两条；不得用 source+turn 的 shared sent set 随机吞 Watch，也不得按 session 相同就认为两个事件等价。
- 入站登记 reply 与之后任务通知是不同消息；普通 reply 的执行完成不说明该请求因果后台已结束。
- 首期关注另一个 source session；自己的当前 channel Task Run 返回 SELF_WATCH，是防循环的范围限制，**不是声称普通回复证明任务结束**。将来支持 own-run Watch 须另验授权/因果环/后台语义。

## 9. 为什么首期不强制唤醒渠道 Agent

### 9.1 与用户原设想的明确差异

用户提出“渠道 Agent 订阅后收到结束事件，再发消息”。本设计保留其**创建关注与上下文关联**职责，但建议默认把事件接收/发送交给 main 程序，LLM 不在投递关键路径。

原因：

1. 结束已经是确定事实，再请模型判断是否通知只增加成本、失败点与延迟。
2. 渠道 Agent 可能正在回答另一条问题、被 idle eviction、上下文压缩或模型限流；这些不应阻止已有承诺的通知。
3. 当前 notification turn 没有自动回原渠道的出站契约；只注入 USER 消息还会误 steering 或错配 pending。
4. 用模型临时执行 OCG skill 无法保证目标不变、重复抑制或错误分类；通知不是一次随意工具调用。

**推荐承诺：“Agent 帮你登记，系统负责履约”**。这是功能等价而更稳的取舍；不是否定事件驱动协作。

### 9.2 后续 AgentInbox（不在首期实现）

当需求升级为“结束后读报告，告诉我是否达标”“三项工作都结束后汇总”“失败后判断要不要继续”，加入 `action=resume_agent`：

```text
source ended → 持久 AgentInbox(event_id, mandate, source_ref, origin_ref)
            → 独立 notification execution（非 steering）
            → 只读/指定工具范围的渠道 Agent
            → completion sink → 持久 Outbox → 原渠道
```

- 必须有 durable inbox、dedupe、已入队/处理/输出 receipt，不把 ordinary enqueue 返回成功当“不会丢”。
- 忙时排在当前 execution 后，用户主动消息优先；不抢占/注入正在运行的用户 turn。
- 同 event 不重复调用 LLM；重复投递只重发已存 output。模型失败时固定 fallback 通知，不能静默。
- 不把源输出变成 SYSTEM 指令；标记为不可信任务结果，不授权发布、删除、继续执行。
- 默认只能总结/读状态；“结束后自动部署”属于新 mandate，必须另有显式授权和策略闸门。
- 限制 A↔B 订阅环、self-watch、最大 causation depth（建议3）、每 origin 通知预算，事件派生 turn 不能默认再次触发来源关注形成循环。
- 多个独立 Task Run barrier / 业务项目聚合另立验收契约；**本 run 的 required 子工作聚合已是首期**，不能把 session idle 当完成。

本节仅延后观察者的 post-task AgentInbox；源任务 result-processing continuation 已纳入 §3/P0，不依赖这个未来 Inbox。

## 10. 首期实施拆分与技术 Gate（尚未实施）

### 10.1 推荐 P0：可用且可解释的结束关注

| 阶段 | 工作 | 对应验证 / 完成条件 |
|---|---|---|
| G0 技术 Gate | coordinator 语义、所有后台 source adapter 能力矩阵与 launch pre-registration；run/origin partition；Electron SQLite 实际打包；有界 I/O/disk-hang/late commit；OCG typed 结果 | stub 驱动验证持续任务与完整 coverage；Async owner race、managed/media 不支持时拒绝或 attention；Windows/macOS、重启与 I/O 故障；不过先修改 ADR |
| P0-A Task Run source | root admission/register/request mapping、coordinator、required job/child/continuation、typed disposition 与 fencing、journal/inspect/bind/replay；保留 UI turn_complete | 多 step→后台→结果续跑→新 job→最终 ended；settle+reserve 与消费竞争、queued/ref/restart/取消迟到 callback；不能只交 execution observer |
| P0-B 来源与工具 | OriginStore/SessionAccessPolicy、所有入口 run+origin partition、attach state gate、HostRequestBridge、find/inspect/watch/list/cancel | observer 与运行 authority 分离；来源伪造、子 Agent、无 origin、群聊、跨 workspace、重复入站/超时/deadlock |
| P0-C 可靠登记 | SQLite Watch事务、operation key、fact+match+outbox、TTL与prepared恢复 | 注册每个窗口崩溃注入、双事件/双注册、容量及磁盘失败 |
| P0-D 主动出站 | 保留typed OCG结果、固定模板、保守retry/blocked/unknown、sender原路返回 | mock网关/真实授权单聊、partial/timeout/主进程重启/无幂等场景 |
| P0-E 可管理闭环 | 关注列表/投递状态、取消/补发、复用现有Notifications设置入口的诊断区 | 托盘运行、切会话、授权撤销、到期、后续询问和UI可读性E2E |

不把 G0/P0-A 单独交付宣称“痛点已解决”；至少整条 once watch → 原渠道通知 + 可查询失败状态通过，才算功能完成。

### 10.2 后续而非前置

- P1：OCG send-idempotency/receipt、群聊显式授权；扩展更多经过 gate 的后台 adapter（未覆盖路径仍 untrackable，不是静默忽略）。
- P2：观察者 AgentInbox、attention 主动订阅、长期每任务订阅/静默时段/多 run barrier。
- P3：独立 server/CLI/PR source、跨设备持久执行、另立业务 Ticket/项目验收契约（有新需求再做）。

queued request→run、required 后台和源任务结果 continuation 均已是 P0，不推到后续。session quiescent、debounce 或全部 idle 不可替代 Task Run 条件；“薄”是范围有限，不是免掉 coordinator/adapter 工作量。

### 10.3 拟改动落点

| 位置 | 拟改动 |
|---|---|
| `core-ai/src/main/java/ai/core/session/InProcessAgentSession.java`、`TurnDriver.java`、`SessionCommandQueue.java` 及拟新增 coordinator | root admission、run/request/driver identity、typed authority/disposition、run+origin partition、真实收尾；宿主 capability 控制 |
| `BackgroundTaskManager.java`、`tool/wait/TaskCompletionRegistry.java`、`TaskAwaitCoordinator.java`、`tool/async/AsyncToolTaskExecutor.java`、`ToolExecutor.java` 附近 | source adapter、launch 预登记、generation、settle+reserve/consume 交接、owner race 与 terminal fencing；不让所有宿主自动获得外发 |
| `ToolCallAsyncTaskManager.java`、media/video 工具及 `appserver/MediaGenerationApi.java` | 分 adapter gate：producer/poll、pre-registration、结果下载收尾、processing policy、停止/恢复事实；未覆盖不假称 returned |
| `core-ai-cli/.../appserver/EngineSessionRegistry.java`、`DesktopSessionTools.java` | 自动安装 source与关注工具、read-only发现/查询、可信根会话 scope |
| `core-ai-cli/.../appserver/AppServerEngine.java`、`NotificationSink.java` 附近 | capabilities、lifecycle RPC、独立 HostRequestBridge；不把notify本身改为任意RPC执行器 |
| `core-ai-desktop/src/main/engine/RpcClient.ts`、`EngineSupervisor.ts` | host请求/结果、engine instance退出/gap通知、恢复校准；避免事件handler阻塞 |
| `core-ai-desktop/src/main/channels/ChannelBridge.ts`、`BridgeStore.ts` 附近 | origin capture、隔离enqueue、严格归属；原bridge存储不承担watch可靠写 |
| `core-ai-desktop/src/main/channels/OcgClient.ts` | typed outbound result保留uncertain/partial、feature协商 |
| `core-ai-desktop/src/main/observations/`（拟新增） | SourceClient、SubscriptionService、AccessPolicy、OriginStore、SQLite WatchStore、Dispatcher、恢复器 |
| `core-ai-desktop/src/main/notifications/CompletionNotifier.ts` | 共用DeliveryCoordinator兼容路径，现有Notify开关语义不变 |
| preload/renderer composer、待输入卡片、Stop与Notifications诊断区 | task_run_input_v1动作/receipt、当前任务steer与new_task分开、run+wait token回应、任务级取消（仅后台也可停）；关注与投递列表，不新增大型任务平台 |
| OCG `send-service.ts` / receipts（可选） | 通用持久发送幂等，不依赖core-ai session概念 |

本稿不创建这些实现文件，也不修改 VERSION、配置或现有通知运行行为。

## 11. 验证、验收与可观测性

### 11.1 关键行为用例

| ID | 用例 | 必须结果 |
|---|---|---|
| T01 | 微信上询问某会话进度，但没请求后续通知 | 只查/回复，不创建Watch、不外发额外通知 |
| T02 | 明确“盯着这次任务，结束告诉我” | 返回持久 run/generation Watch、coverage 与 TTL，观察不指挥源任务 |
| T03 | 渠道观察者 idle/evict/限流，source Task Run ended | main 仍通知，不为固定外发启动 LLM |
| T04 | inspect run A 后 A ended、B 开始，再 watch(A ref) | 只补发 A；旧 execution 终止不提前匹配 A |
| T05 | prepared/bind/active 各窗口结束/崩溃 | 固定同 run/generation 恢复，operation/Outbox 不重复，不重选 current |
| T06 | 无 active run；只有 required 后台的 active run | 前者 NO_ACTIVE_TASK_RUN；后者可关注，不退回 BACKGROUND_WORK_ONLY |
| T07 | returned/fatal/cancel/limits/明确 close 中断 | 分类真实；业务质量不冒称；取消不称外部全部停止 |
| T08 | root 多 LLM steps 后 turn_complete，后台仍运行；续跑又启动 job | steps/root end 均不通知；等最终 required+processing+收尾后仅一次 run ended |
| T09 | 无turn_id的task_notification与入站pending竞争 | 不误完成原消息；request/execution精确匹配 |
| T10 | 旧engine/独立CLI/同basename不同workspace历史归属不清 | unsupported/unavailable/ambiguous，不伪idle不读取别处 |
| T11 | 同source多个owner、同origin重复指令、重复terminal | 各owner独立授权/投递；同operation重用Watch；唯一Outbox |
| T12 | 假造地址、originId、workspace path、sender、SYSTEM文字 | 拒绝；不因prompt内容扩大路由/可读scope |
| T13 | group/shared sessionKey/不同账号同target/子agent/SDK out-of-turn | origin隔离；未获权拒绝，不能借前一turn的grant |
| T14 | 网关明确preflight失败/平台未知/partial/窗口过期 | 准确retry/unknown/partial/blocked，不能全部retry或全部sent |
| T15 | accepted响应前后Desktop崩溃 | 有receipt查同key；无则unknown，人工补发说明可能重复 |
| T16 | TTL、取消、账号退出、workspace grant撤销、源删除 | 可见终态；取消不取消工作，不送未claim记录 |
| T17 | SQLite/journal写失败、满、末帧损坏 | 不承诺落盘；明确storage_error；源任务不被通知链阻断 |
| T18 | 托盘隐藏、切窗口、系统resume、更新引擎实例 | 不依赖 renderer；校准固定 run/generation，实例变更先 fence，不启动新工作 |
| T19 | host请求执行中main又调用source RPC | 无dispatcher自等死锁；bounded pending可查，重复不另登记 |
| T20 | 同目标 UI execution Notify 与 Task Run Watch 并存 | 只有证明同最终事实才合并履约两账；较早 UI event 不消耗 Watch；未统一说明可能两条 |
| T21 | 同 Task Run 两 owner，取消/到期/解绑其中一个 | 逐watch lease独立，保留到另一lease的recoveryUntil，不能提前删terminal |
| T22 | 删除前tombstone、main撤销、source close/cleanup各窗口崩溃 | 恢复先删/撤销fencing再投递；晚到terminal不复活，未claim旧通知不发 |
| T23 | 截止前ended但截止后恢复；source不可达；grant已失效 | 匹配/恢复/出站截止分离；校准先于expiry；失效grant只本地诊断 |
| T24 | 所有入口no-origin混入授权execution，drain/steering/task-note | partition与steering兼容性闸门拒绝混入，输入实际消费归属可核验 |
| T25 | fsync永远阻塞、SQLite写队列满、超时后late commit | 不堵driver/dispatcher，无无限新线程；storage_error/operation幂等，不假成功 |
| T26 | Outbox满时已接受Watch同时终止 | reserved终结槽可转换；写失败不matched，source pin保留可replay |
| T27 | manager remove-before-publish、settle 与 enqueue 间暂停/拒收 | job settled 同 version 预留 continuation；绝无空窗 returned；队列拒收保留 attention reservation |
| T28 | wait 消费/读取与 callback、root 收尾、超时释放同时竞争 | generation-scoped transfer/consume；不重复续跑，不提前 ended，required 始终计账 |
| T29 | notify=false required；none policy 与需 processing 各一种 | 都等 settled；none 不强推 reasoning 也不永久挂；需处理但无人接手 attention，不能偷 detached |
| T30 | active run A 后收到普通新请求 B，或重复 request B；queued inspect/watch | B 持久映射另 run、重复同 mapping；A 不被延长；queued ref 可绑定，不同 run 不合批 |
| T31 | 显式 steering/edit/continue；awaiting_input/async/draining/terminal | 同 run+相容授权+开放输入窗口才 attach；待用户不 done，terminal 不复活 |
| T32 | required job 失败后 root/continuation 接受错误并恢复；root fatal | 前者可正常 returned；后者 failed/fenced；不能 job 失败立即强判整 run 失败 |
| T33 | cancel/limits 后迟到 job callback、外部进程拒停/timeout | 逻辑终止，不唤醒旧 run；外部 timed_out_orphan/unknown 明示，不称全部停止 |
| T34 | async worker 在工具 result 返回/bindOwner 前立即完成 | launch reservation 先固定 run/owner；completion 可精确归账，无未归属窗口 |
| T35 | child jobId 复用/新 generation；nested child manager 继续 launch | 完整因果 identity 不混账；旧 callback 不消减新 required；子 manager running map 非全局真相 |
| T36 | managed/media/video 不支持 producer/prelaunch，或 provider done 尚未下载 | unsupported→untrackable/拒可靠 Watch；不忽略；支持时等下载及 policy processing，才可 returned |
| T37 | journal 有 reserved continuation、root/launch in-flight 歧义；新 engine 重启 | 恢复查账而不自动 replay effects；unknown/attention/interrupted，旧 bind 不迁到 new run generation |
| T38 | root admission commit 前 crash/拒收、commit 后 receipt 丢失/重试 | durable accepted 状态前不启动工作；receipt 丢失不等于未接收，重试查已存 mapping/状态，不重造 run |
| T39 | peer 仅 observe/notify，却企图 attach/调用源工具；源 authority 撤销 | observer grant 不继承运行权，拒绝；续跑按源最小 authority 校验，撤销 attention/终止 |
| T40 | normal root returned 但 draining 未完 / disposition unknown / coverage 不完整 | 即使账计数全零也不 returned；最后 execution 收尾和明确 disposition/coverage 是必要条件 |
| T41 | 原execution仍阻塞的approval/ask-user答案，同时重复respondInput/cancel | signal_active_execution唯一消费，原栈恢复而不排第二个continuation；cancel fence阻止迟到答案，解除一token不清掉其它 |
| T42 | 已返回等待恢复的execution接受答案；enqueue失败/响应丢失/crash | enqueue_continuation预留一次恢复义务，重复不另执行；不混用signal，claimed不明确attention |
| T43 | 新capability composer/旧调用方省略action、steer窗口关闭、仅后台Stop | new模式省略action为明确new_task receipt；UI分别steer/new_task，不静默换意图；Stop按run取消required而非只停driver |

### 11.2 测试层次

1. **coordinator / adapter 契约**：typed root/run/request、generation、state gate、required/none/consume、settle+reserve 线性化、disposition/terminal fence；每个后台 producer 必须独立 coverage 测试，不以一个 manager 通过代替全覆盖。
2. **登记与投递单元**：origin、工具 schema/拒绝、lease、Watch/Delivery 状态机、固定模板、TTL/Outbox 唯一约束与结果分类。
3. **进程集成与崩溃注入**：stub 多-step 模型 + 真 stdio + 控制后台 adapter/WatchStore；在 admission/launch commit、即时 callback、settle/reserve/consume、队列交接、draining、bind、Outbox、dispatch 各窗口注入；隔离进程验证恢复查账与不 replay effects，不靠 Start-Sleep 碰竞态。
4. **OS实际打包**：Windows与macOS Electron的SQLite/文件原子性、锁、托盘/退出/resume、权限错误。
5. **OCG mock**：缺feature、明确未dispatch、network reset、uncertain/partial、账号失效、主动窗口过期、并发same key、receipt lookup。
6. **真实单聊E2E**：经用户明确授权，在隔离profile运行，不安装/重启正在使用的Desktop、不读私人消息；至少微信与QQ各一次注册→原渠道通知→查询→取消；平台不允许场景如实记录blocked，不假装成功。

真实发送是外部副作用，实施阶段需明确获得测试消息授权。此设计阶段没有执行或声称执行E2E。

### 11.3 操作性指标

建议验收目标（非已有性能结论）：

- source durable ended到Outbox commit健康路径p95<2秒；网关可达、无平台限流时通知提交p95<10秒。网关accept不代表平台delivery。
- Watch 匹配/投递常态零 LLM 调用；**源任务的必要结果续跑仍正常调用模型**，不是整个 Task Run 零推理。Watch reconciliation 每 workspace 最多每60秒合并一次，后台 adapter 自身有界 poll producer 单独计量。
- run state/disposition/version、required generation 数、launch/continuation reservations 与 oldest_age、coverage/attention/fenced late callbacks；另记 prepared/active/matched/expired/revoked、Outbox 分状态/age、unknown/partial/retries、gap/storage_error。
- 注册时延、ended→matched、matched→accepted、恢复耗时、拒绝原因；若需模型摘要另外记LLM开销，不混在通知指标。
- Watch/delivery/attempt/event的脱敏相关ID贯穿日志；不打印target/openid、origin token、正文或完整session history。

### 11.4 可管理体验

- 当前渠道“我让你关注的有哪些”必须调用list，而不是从聊天记忆猜。
- Desktop现有Notifications设置增加“关注与投递”诊断区：source alias、一次关注scope、有效期、matched与发送状态、错误分类。
- [取消关注]、[重新校验]、[补发]：补发是明确外部发送动作，unknown/partial先展示可能重复，用户确认后才生成新attempt；不能在点击查看详情时自动外发。
- 关闭“IM bridge入站”与“主动通知”区分：不应因为停拉消息而悄悄取消既有关注。新增独立outbound pause/master开关；用户停止主动通知则暂停dispatcher，恢复时按TTL/权限重查。退出app时没有dispatcher。
- 模型不能从工具失败仍回复“好的我会通知”；prompt给明确流程，成功承诺依据实际registration字段。UI可把Watch结果展示为卡片，但卡片不是登记真相。

### 11.5 需求验收矩阵

| 需求 | 设计 / 测试覆盖 |
|---|---|
| L1 / L2 / L11 | §3 持续 Task Run + §5.6 required adapter gate + §6–§8 once 最终通知；T02–T08/T27–T40。需求单位已澄清，算法/coverage 尚待确认与实现验证；unsupported 不能宣称全部痛点已覆盖 |
| L3 | §4自动source、§5稳定事件/快照/绑定；T04–T10、T17 |
| L4 | §6origin与HostRequestBridge；T02、T12–T13、T19 |
| L5 | §1备选方案比较、最佳实践边界与DeepSeek映射 |
| L6 | §4main所有权、§6真实来源、§8出站；T03、T11–T13 |
| L7 | §5.5/§7.3注册协议与事务；T04–T05、T09、T11 |
| L8 | §7/§8状态机与恢复；T14–T18 |
| L9 | §3/§5.6 runtime 与业务质量、coverage/fence/恢复边界；T06–T08/T27–T40 |
| L10 | 本文Draft、§13待确认；未实施、未发布、未发送 |

## 12. ADR 决策摘要（AI建议，非用户批准）

| ADR | 建议 | 理由 / 备选取舍 |
|---|---|---|
| A01 | 事件驱动+保留状态，低频reconciliation兜底 | 消除模型轮询；内存pub/sub不足以承诺恢复 |
| A02 | runtime自动发布，LLM只表达关注意图 | 结束/异常/关闭不依赖模型配合；否决register/end工具 |
| A03（替代 v0.2） | current_task_run / explicit_task_run once 最终 ended | L11 持续任务；root 多 step、required 后台及结果续跑均纳入，不退回 execution、不扩业务 Ticket |
| A04（修订） | root admission 前持久 run/request mapping；每 driver 独立 execution_id | queued/ref/dedupe 稳定；caller turn_id 不代表 run；不同 run 不合批 |
| A05 | main持有订阅、原地址与Outbox | 事件/IM/多workspace汇聚；renderer和原LLM生命周期不可靠 |
| A06 | 默认notify_origin固定模板；AgentInbox后续 | 最少模型调用、故障点；明确偏离“必须唤醒原Agent” |
| A07 | app-server低频LifecycleJournal+readonly source | stable身份、bind/pin/replay，不把UI transcript改为总线；代价是低频额外fsync |
| A08 | main单writer SQLite事务，实际Electron验证gate | 原子match+outbox；不是跨进程transaction；JSON/settings best-effort不适合承诺 |
| A09 | typed OriginGrant + 逐执行scope + workspace ACL | OCG授权/targets/sessionKey不能代表读本地会话权限 |
| A10 | host请求通过可信stdio桥并显式结果 | 不新增全局HTTP/任意IPC；不让custom card造成外发副作用 |
| A11 | 默认status-only、失败取消也通知、群聊首期拒绝 | 限制泄露与噪音；UI Notify既有取消不通知语义不被改写 |
| A12 | 保留unknown/partial；只明确未dispatch可自动retry | 无网关/平台端到端exactly-once；不为了送达盲目重复发 |
| A13 | OCG send幂等归OCG；首期无feature保守降级 | 真正契约拥有者负责；不写死特定平台/端口 |
| A14 | Watch不修改Notify flag/全局target；共用投递协调 | 一次关注与持续UI勾选是不同产品意图 |
| A15 | 默认24h、最大7d、bounded store、可查失败 | 有限承诺避免永久残留；窗口过期与离线不能假装持续可达 |
| A16（新增） | TaskRunCoordinator + required 默认、launch 预登记、settle 与 continuation reserve 原子更新 | 关闭 remove-before-publish/owner race；复用 TaskCompletionRegistry generation，不能猜 pending 空就结束 |
| A17（新增） | typed disposition / processing policy；runtime 正常收尾不是质量验收 | wait/notify=false 不免 required；none 不强推 reasoning；失败可恢复，不要求 LLM done |
| A18（新增） | adapter coverage gate、终止 fencing、journal reservation 与保守恢复 | 不支持则 untrackable/attention，不能忽略；crash 不合成 success、不 replay effects；逻辑取消不证明外部停止 |

## 13. 待用户 Review 的关键决策

| ID | 需要确认 | 建议默认 | 影响 |
|---|---|---|---|
| Q1 / CONFIRMED_REQUIREMENT | 用户澄清“肯定是一轮持续的任务 而不是一个 llm turn” | 关注持续任务，不是单 step；v0.3 同时拒绝 execution-only 缩减。薄 Task Run 聚合是 AI 提案，未获算法批准 | 只确认需求单位，不批准 Q2/Q3/Q5/Q9 或扩大到项目管理 |
| Q2 | 简单结束通知由main直接发，还是必须唤醒原渠道Agent | **main直接发**；预留AgentInbox，分析/协调后续再做 | 成本、延迟、可靠性；是相对原设想的关键调整 |
| Q3 | source范围及跨workspace | **仅Desktop托管本地会话**；默认原授权workspace，跨workspace显式grant | 独立CLI/远端会话无法首期订阅 |
| Q4 | 通知内容与群聊 | **status-only中文，包含标题/Watch短号，失败取消也发；单聊首期** | 摘要信息量、隐私与噪音 |
| Q5 | 重启恢复值得引入低频journal和SQLite吗 | **用户已确认（2026-11-20）：引入 SQLite**；Electron 打包 gate 通过后采用；不承诺跨机器执行恢复。后续方向（非本功能范围）：把散落的本地配置逐步集中到 SQLite | 工作量、fsync成本与可持久履约能力 |
| Q6 | OCG持久发送幂等同期做吗 | **可独立增强**；首期旧网关unknown暂停，不自动重发 | 重复概率与无人干预恢复能力 |
| Q7 | TTL与过期/观察中断诊断 | **默认24小时，最大7天**；到期/源中断发一条status-only诊断（权限仍有效时） | 是否满足超长任务、注意力预算 |
| Q8 | UI Notify 与 Task Run Watch 不同边界如何兼容 | **保留 UI execution 语义；只对证明同最终事实的同目标通知合并，status-only 优先** | 不能拿较早 UI turn_complete 履约任务 Watch |
| Q9 / OPEN | 薄 Task Run 算法、attach/state gate、required/detached/processing、cancel/limits/输入等待 policy 与 adapter 首批覆盖 | **§3/§5.6 coordinator 提案**；一 active run+queued、required 默认、none 显式、未知 attention；完整支持的路径才 returned | 是 v0.3 新技术决策，不由 Q1 自动批准；需要独立复核与用户确认 |

Q1 的需求单位已确认，Q5 的 SQLite 选型已由用户确认；最优先仍待确认 **Q9、Q2、Q3**，Q4/Q6/Q7/Q8 也未批准。不得将澄清或“继续修订”解释为批准算法、存储或实现；当前只交付同一设计文档。

## 14. AI Review 与修改记录

### 14.1 版本与评审边界

v0.3由general-purpose文档修订子Agent（继承gpt-6.1-sol）与当前会话共同修订。先读完整v0.2，再小范围核对§2所列Agent/session、后台registry/executor、wait、queue、managed/media源码，全文同步需求、协议与验收；随后由另一个保留历史评审上下文的general-purpose Agent以gpt-6.1-sol独立审阅新Task Run语义。未用decision model、未重新访问外部链接；编写自查与独立审阅分开记录。

历史 v0.1/v0.2 使用的 `explore-agent / deepseek-flash` 仅作事实核对；原 `gpt-6.1-sol` 独立评审记录保留如下。**旧 CLOSED_IN_DESIGN 和复核交付结论仅适用于 v0.2；不证明 v0.3 的 Task Run 语义通过。** 未用 `gemini-4-hydrogen`，未实现或 E2E。

### 14.2 历史 v0.2 AI 技术评审记录（非用户批准）

以下原意见、旧 execution/current 术语、当时状态原样保留，章节引用指当时 v0.2；与 v0.3 正式契约不一致处仅为历史，不作现行承诺。R02 的需求单位已由澄清纠正，但新算法仍待 R10/Q9。

| Review ID | 日期 | 实际模型 / Reviewer | 意见 | 处理 / 影响 | 状态 |
|---|---|---|---|---|---|
| R01 / P0 | 2026-11-20 | `gpt-6.1-sol` 独立审阅 | 独立enqueue不保证execution隔离；无origin UI/forward/schedule可能混入授权turn | §6.1扩展所有入口typed origin、batch partition/steering兼容性与实际消费request归属；T24 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R02 / P1 | 2026-11-20 | 同上 | current execution缩减不一定解决真正后台/跨回合工作跟踪 | §0.2/§11.5明确未完全覆盖，Q1真实工作样例确认作为实施/验收前置 | OPEN（产品范围待用户决定） |
| R03 / P1 | 2026-11-20 | 同上 | prepared/bind(current)可能改绑下一轮；operation key未定死 | §7.2/§7.3先查稳定operation，再冻结具体execution后prepared；§8.5恢复禁止重新选current。此项编写者在评审并行时已修，现记录其评审意见 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R04 / P1 | 2026-11-20 | 同上 | 多owner pin与TTL/恢复窗口保留不一致 | §5.4逐Watch持久lease，聚合最大recoveryUntil，单watch解绑不影响其它；§7.5/T21 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R05 / P1 | 2026-11-20 | 同上 | 删除journal会丢删除事实，重启可能发送旧Outbox | §5.4持久无正文tombstone、删除fencing、恢复先校准再发送、claim竞态边界；T22 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R06 / P1 | 2026-11-20 | 同上 | Watch/晚到事件/诊断delivery的TTL未闭合 | §7.4匹配截止、recoveryUntil与delivery截止分离，expiry_pending，失效grant只本地；T23 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R07 / P1 | 2026-11-20 | 同上 | 遗留dispatching与blocked恢复未完整定义 | §8.3/§8.5所有遗留dispatching默认unknown，可信receipt/not-dispatched才改状态；blocked触发只重校验 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R08 / P1 | 2026-11-20 | 同上 | fsync挂起不能靠RPC timeout保证不阻塞runtime | §5.5/§7.1/G0/T25有界隔离单writer、pending durability、disk-hang/late-commit、禁止无限线程 | CLOSED_IN_DESIGN（AI复核，非用户批准） |
| R09 / P2 | 2026-11-20 | 同上 | 已接受Watch终止时Outbox可能满 | §7.5预留互斥终结delivery槽，写失败不matched并保持pin；T26 | CLOSED_IN_DESIGN（AI复核，非用户批准） |

初次评审结论：总体取舍合理，但需修订，暂不能认定可交付实施契约。第二轮复核关闭R01/R03/R04/R07/R08/R09，要求R05按diagnostic kind区分source存活校验、R06统一匹配截止口径；已修改。第三轮`gpt-6.1-sol`复核确认R05/R06在设计层关闭，**可作为待用户确认的Tech Design交付**。产品范围R02/Q1、其它用户决策与G0仍开放；没有实现/E2E验证，不将AI设计复核冒充需求批准。

### 14.3 v0.3 新语义 Review（独立复核完成，非用户批准）

| Review ID | 日期 | 实际模型 / Reviewer | 意见 / 待复核 | 状态 |
|---|---|---|---|---|
| R10 / v0.3 | 2026-11-20 | 独立Reviewer `gpt-6.1-sol` | 薄Task Run正确区分LLM step/Agent.run/Business Ticket；launch、settle+reserve、wait/none、coverage与fencing有明确保证；但输入/取消调用方契约与待输入恢复模式需小修 | REVIEW_COMPLETED（初审需修，R11/R12已复核关闭） |
| R11 / P1 / v0.3 | 2026-11-20 | 同上 | new_task/steer未落实API与composer，Stop只当前driver不能取消仅后台run | §3.7新增task_run_input_v1动作、固定run/version/receipt、respondInput与任务级cancel；composer/Stop/兼容行为及T43 | CLOSED_IN_DESIGN（gpt-6.1-sol复核，非用户批准） |
| R12 / P1 / v0.3 | 2026-11-20 | 同上 | awaiting_input统一enqueue会堵仍占栈execution或重复处理 | §3.2/§3.7区分signal_active_execution与enqueue_continuation，token唯一消费/失败歧义/fence；T41–T42 | CLOSED_IN_DESIGN（gpt-6.1-sol复核，非用户批准） |

R02的v0.2范围疑问不能再用于回退execution-only；Q1更新为CONFIRMED_REQUIREMENT，Q9与其它决策继续OPEN。v0.3独立审阅与复核均使用gpt-6.1-sol；Reviewer确认R11/R12的typed输入/任务取消与等待恢复模式在设计层关闭，**v0.3可作为待用户确认的Tech Design交付**。本结论针对修订设计，不是批准算法/实施，也不代表G0或运行可靠性已验证。

### 14.4 修改记录

| 版本 | 日期 | 修改人 | 内容 / 原因 | 用户确认 |
|---|---|---|---|---|
| v0.1 | 2026-11-20 | core-ai | 先评估备选再设计；runtime自动source、精确once Watch、真实origin、持久登记/Outbox、保守送达语义与后续AgentInbox | 待Review |
| v0.2 | 2026-11-20 | core-ai | 根据gpt-6.1-sol独立初审/复核补齐所有入口origin、冻结execution、逐watch lease、删除fencing与诊断区分、统一匹配/恢复/出站截止、遗留dispatching unknown、有界I/O及容量预留；保留Q1需求适配开放 | 待用户确认，非AI批准实施 |
| v0.3 | 2026-11-20 | core-ai / `gpt-6.1-sol` | 保留用户持续任务澄清、追加 L11；纠正 LLM step/Agent.run/continuation；全文改为薄 Task Run、root admission/request/queued identity、required/processing coordinator 与 adapter gate、typed origin/state gate、fencing/恢复；迁移原 lease/Outbox/TTL/删除可靠性契约，增加 T27–T40、ADR A16–A18/Q9/R10；仅修改本文 | Q1仅需求单位已确认；算法/其它决策待用户；v0.3已完成独立复核，补typed输入/Stop/等待恢复与T41–T43；未实现 |
| v0.3.1 | 2026-11-20 | core-ai | Q5 用户确认引入 SQLite（首期仅 `userData/observations/observations.db`，仍以 Electron 打包 gate 为前提）；记录后续把散落本地配置集中到 SQLite 的方向，不纳入本功能范围；其它内容未改 | Q5 已确认；Q9/Q2/Q3 等仍待用户 |

## 15. 参考与证据边界

### 15.1 外部参考（v0.2 于 2026-11-20 核对；v0.3 保留引用，未再次访问）

- [DeepSeek Super-agent 架构探索全文镜像](https://blog.eimoon.com/p/deepseek-super-agent-design/)：§3.2订阅直通、§4.2低打扰交互；页面注明是架构探讨与原型，非正式产品能力说明。
- [DeepSeek 原始文章链接（镜像标注）](https://zhuanlan.zhihu.com/p/2099987320443237060)：本次未直接读取，具体内容依据上述镜像，不冒充直接验证原站。
- [Temporal Workflow message passing](https://docs.temporal.io/encyclopedia/workflow-message-passing)：Query/Signal/Update区别；借鉴控制/查询分离，不引入其运行时。
- [Kubernetes API concepts / Efficient detection of changes](https://kubernetes.io/docs/reference/using-api/api-concepts/#efficient-detection-of-changes)：snapshot+resourceVersion watch、断线与410/gap重建；本设计需绑定同 Task Run/generation，不能直接按last modified轮询。
- [AWS Prescriptive Guidance / Transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)：双写、重复与幂等；本地两个存储与OCG并非一个事务，端到端不承诺exactly-once。

### 15.2 本地设计参考

- `docs/cn/analysis-deepseek-super-agent-vs-core-ai.md:110`、`:431`、`:454`：订阅、会话索引、范围与授权建议；部分旧路径已不存在，不作为当前实现证据。
- `docs/cn/design-desktop-session-completion-notify.md:47`、`:254`、`:653`：原Notify目标与best-effort边界；实际代码现已包含daemon恢复启动，不能按旧非目标反推现状。
- `docs/cn/design-agent-async-wait-barrier.md:38`、`:86`：等待本turn的任务结果，与结束后独立事件通知不同。
- `docs/cn/design-desktop-ocg-channels.md:163`、`:484`：OCG与Desktop所有权/回复归属设计；计划中的P1 journal/ACL不能当成已实施。
- `docs/cn/design-sse-directed-delivery.md:123`：owner-held source/replay；server SSE不是本地持久Watch的现成实现。

本文仅是决策与实施契约提案。未修改代码、未运行发送、未建立真实关注；下一步是用户确认范围和关键取舍后实施、测试与真实授权E2E。
