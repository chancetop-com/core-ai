# Core AI Server Agent 评估 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Review 完成；用户已要求实施，按建议默认进入 P1–P2（仅代码与本地验证，未部署） |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-21 |
| 需求提出者 / Reviewer | 用户；尚未批准方案或实施 |
| 作者 | core-ai |
| 产品入口 | Experiment → Evaluations（评估），独立管理页 |
| 目标 | 对同一个 Agent 的不同候选版本进行可重复、可诊断的离线评估，建立 baseline 并支持持续调优 |
| 本次交付 | 技术设计文档；不修改业务实现、不部署、不执行真实评估 |
| 事实与提案 | 附录 B 为设计时源码事实；正文为目标设计。所有新增 API、类型、预算和默认值均为提案，不代表当前已实现 |

## 阅读指南与目录

- 产品 / Reviewer：先读 §1、§2、§4、§8、§11，确认评估对象、用户流程、数据契约和首版边界。
- 后端 / 执行平台：读 §3–§7、§9，重点关注版本冻结、隔离、任务状态和失败分母。
- 前端 / 测试：读 §4、§8、§10，核对导入、报告、baseline 对比和验收。
- 原始需求、行业依据、源码核对、Review 和修改历史保留在本文件附录，不另拆零散记录。

| 章节 | 内容 |
|---|---|
| [1. 背景、需求、目标与非目标](#1-背景需求目标与非目标) | 从第一性原理定义评估解决什么问题 |
| [2. 总体方案与端到端流程](#2-总体方案与端到端流程) | 创建、冻结、执行、评分、聚合、比较 |
| [3. 核心模型与扩展契约](#3-核心模型与扩展契约) | Evaluation、Dataset、Grader、Run、Case、Trial |
| [4. 数据集与评分器规格](#4-数据集与评分器规格) | 字段、文件格式、脚本 ABI、结果契约 |
| [5. 执行协议、隔离、并发与恢复](#5-执行协议隔离并发与恢复) | 有界异步运行，不承诺外部副作用 exactly-once |
| [6. 指标、聚合与-baseline-比较](#6-指标聚合与-baseline-比较) | 分母、重复试验、配对比较和不确定性 |
| [7. 持久化模型与服务架构](#7-持久化模型与服务架构) | 存储、调度、Agent 执行适配和报告 |
| [8. API、管理页与作者体验](#8-api管理页与作者体验) | 用户可操作的管理和调优闭环 |
| [9. 安全、预算、运维与可观测性](#9-安全预算运维与可观测性) | 脚本、真实工具、隐私、容量和成本 |
| [10. 验收、实施计划与交付状态](#10-验收实施计划与交付状态) | 功能 / 系统验证和 rollout 条件 |
| [11. 关键决策与未决事项](#11-关键决策与未决事项) | 取舍、代价、需用户确认的选择 |
| [附录 A：用户原始需求与文档治理](#附录-a用户原始需求与文档治理) | 原文和维护规则 |
| [附录 B：行业依据与设计时源码核对](#附录-b行业依据与设计时源码核对) | 来源、事实、接入约束 |
| [附录 C：Review 与修改历史](#附录-creview-与修改历史) | 意见、授权和版本变化 |
| [附录 D：实施验证记录](#附录-d实施验证记录) | 当前未实施，不虚构验证 |

## 1. 背景、需求、目标与非目标

### 1.1 第一性原理：评估是一项测量实验，而不是批量聊天

用户要回答的核心问题是：**我修改了 Agent 后，它在目标任务分布上是否变得更好，代价是什么，有没有回退？**

单次调用拿到一段看起来不错的回答，不能回答这个问题。有效测量至少固定以下对象：

1. **被测对象**：确切的 Agent 配置、提示词、模型路由、依赖与执行模式，而不只是一个会继续变化的 agent ID。
2. **测量任务**：有稳定 case ID、明确输入、必要初始环境和成功条件的数据集版本。
3. **测量仪器**：版本固定的 harness、评分器、阈值、聚合方法，以及独立于 Agent 的可信评分输入。
4. **实验条件**：并发、时钟、超时、运行预算、环境重置和重复次数。
5. **实验记录**：每次实际输出、执行轨迹、评分、错误、延迟、费用与未完成项。

因此，把用户流程扩展为：**定义任务与成功条件 → 固定评估规格 → 固定 Agent 候选 → 隔离执行 → 多维评分 → 形成完整报告 → 与同条件 baseline 配对比较 → 补充回归样例。**

**理由：**Agent 输出具有随机性，工具与环境也可能变化。如果不冻结测量条件，分数变化无法归因于 Agent 调优；只保留总分又无法定位退步来源。

### 1.2 需求追踪

| ID | 用户目标 / 补充要求 | 设计落点 |
|---|---|---|
| U1 | core-ai-server 新增评估功能，放在 Experiment，提供管理页 | §2、§8 |
| U2 | 新增时选择一个要评估的 Agent | §3.2、§8.2 |
| U3 | 上传评估数据集，设计 input、预期 output 等字段 | §4.1–§4.3 |
| U4 | 支持评估脚本及脚本输出的评估结果 | §4.4–§4.6；代码与样例分离，结果由平台生成 |
| U5 | 按用户设置的并发调用 Agent，再逐项评估 | §5.2、§5.3 |
| U6 | 得到整个数据集的结果 | §6、§8.3 |
| U7 | 建立 baseline，支持每次调优后的持续演进 | §3.3、§6.4、§8.4 |
| U8 | 可以重构原始流程，保留“评估一个 Agent”的目标 | §1.1、§2.1、§11.1 |
| S1 | 结果可复现到版本与条件，随机性和环境漂移显式披露 | §3.2、§5.1、§6.4 |
| S2 | 区分 Agent 失败、评分失败、平台失败与未知执行结果 | §3.4、§5.4、§6.1 |
| S3 | 任意脚本 / 真实工具不获得隐含权限，不污染生产环境 | §4.5、§5.2、§9.1 |
| D1 | 本轮只交付设计，用户 Review 后才可实施 | §10.3、附录 C |
| D2 | 保留原始需求、设计初衷、备选取舍、Review 与修改记录 | §11、附录 A / C / D |

### 1.3 目标与不变量

- 每个正式 Run 的评估规格和 Agent candidate 均不可变；编辑 Evaluation 不改变历史 Run。
- 每个 case 的每次 trial 从独立会话和重置后的环境启动，不继承上一题的记忆、文件或工具状态。
- Agent 不得收到 reference output、评分脚本、隐藏断言、judge instructions 或测试集其他样例。
- 所有计划 trial 都出现在报告分母中；不通过删除错误、跳过难题或只保留最好一次来提高分数。
- 评分逻辑不由 Agent 自评；模型评分没有业务工具和发布权限，脚本评分没有默认网络、密钥和业务写权限。
- baseline 是一份被明确选定的不可变 Run，不是“当前最新分数”。比较必须检查数据集、评分和执行条件的兼容性。
- 结果可以解释为任务是否成功、哪些能力回退、成本 / 延迟怎样变化；默认不合成掩盖硬失败的万能质量分。
- 未完成、无法可靠评分、外部执行状态未知和不可比较都有一等状态，不能伪装为 Agent 正确或错误。

### 1.4 首版边界与非目标

| 首版纳入 | 首版不纳入 |
|---|---|
| 一个 Evaluation 绑定一个 Agent；不同候选 Run 与 baseline 比较 | 任意多 Agent 排名、排行榜、自动选模 |
| 单轮输入；允许 Agent 内部多步工具执行并记录轨迹 | 多轮用户模拟、动态对抗用户、交互式人类参与 benchmark |
| JSONL 数据集，ZIP 打包附件 / 评分脚本 / fixture；简单 CSV 导入适配 | 在线生产流量采样、自动生成测试集、复杂数据标注平台 |
| 确定性断言、Python 脚本、受限模型评分；人工复核标签 | 任意语言任意依赖、在线脚本插件、人工审批阻塞整个调度 |
| 离线文本模式；通过隔离条件验收的本地沙箱工具模式 | 默认访问真实业务 API / MCP / 生产数据库；任意外部副作用 |
| 可配置并发、重复次数、有界预算、异步状态与取消 | 全自动 Agent 优化、自动修改 / 发布 Agent、无限重试 |
| 冻结配置和可固定的依赖；不能固定的外部条件显式标记 | 宣称 pin 了模型别名就能逐 bit 重现上游模型 |

**理由：**首版必须覆盖真实的 Agent 多步执行，而不仅是字符串比对；但不能把离线评估扩成完整仿真平台。无法保证重置或隔离的能力必须在 preflight 拒绝，不能为了“能跑”偷偷改成另一个 Agent。

## 2. 总体方案与端到端流程

### 2.1 对用户原始流程的调整

| 原始设想 | 采用方式 / 调整 | 初衷与理由 |
|---|---|---|
| 选择 Agent，上传数据集 | 保留；另选择 draft / published 候选，在 Run 创建时冻结 | 调优未发布的草稿，不强迫先发布；历史仍可回看 |
| 每条数据带 input 与预期 output | input 必须；reference 可选，但每条必须有可执行的成功判据 | 开放回答可能没有唯一正确文本；执行任务也可能靠环境断言判断 |
| 每行带 eval 脚本 | 数据行引用 grader ID；脚本在版本化评分器包中定义 | 不把同一代码复制千次，不混淆数据修改和测量仪器修改 |
| 数据集中放 eval 结果 | 导入只接受任务定义；actual output / eval result 由平台生成，另导出 | 防止上传的既有结果被当成本次实际结果 |
| 按并发运行并 eval | 保留；独立的 Agent / grader 容量池和持久任务状态 | 慢评分不无限占 Agent 槽位，用户并发不能绕过平台容量 |
| 最后整个数据集结果 | 保留；额外显示覆盖率、失败类型、分层统计、成本、延迟与逐项证据 | 一个平均分无法证明持续演进 |
| baseline | 用户明确标记有效 Run，后续按 case 配对比较；改变测量条件需 bridge run | 不能拿两把不同的尺子测出的数字直接相减 |

### 2.2 用户主流程

1. 在 Experiment → Evaluations 新建评估，填写名称、选择有权运行的 Agent。
2. 导入数据集，验证格式、case ID、附件、评分器引用与执行环境要求；预览有效 / 无效行，不静默丢行。
3. 配置评分套件和主成功判据；简单任务使用内置模板，代码任务上传受限 Python grader，开放问题选择受限 judge。
4. 设置 Agent 版本来源、运行 profile、并发、重复次数、超时与总预算；先对 1–3 个公开调试样例做 smoke run。
5. 点击开始，服务端做权限 / 安全 / 可用性 / 容量 preflight，冻结全部版本，计算计划工作量和预算，创建异步 Run。
6. 后台为每个 `(case_id, repetition_index)` 创建独立 trial，重置环境，执行同一冻结 Agent candidate。
7. 保存 output、trace、usage 和执行终态；在隔离 grader 环境评分，保存各维结果。评分失败可以在同一 output 上受控重试，不重新调用 Agent。
8. 全部计划 trial 有终态后生成不可变报告；取消或预算耗尽时生成不完整报告，明确未执行项和比较资格。
9. 用户查看失败样例、轨迹、reference 差异与 grader 证据；符合条件的 Run 可以标记 baseline。
10. 调优 Agent 后新建 Run，默认沿用 baseline 的评估规格，与其配对比较；新的缺陷补入下一版数据集，而不是覆盖旧基线。

**理由：**导入、评分定义、smoke test 和正式运行分离，既降低批量昂贵失败，也保留同规格重复测量的闭环。

### 2.3 总体架构

```text
Experiment / Evaluations UI
           |
     Evaluation API ---- Dataset Import / Grader Registry
           |
   Preflight + Snapshot / EvalSpec Freeze
           |
     Durable Evaluation Run / Trial Store
           |
   Scheduler + Lease / Budget / Capacity
           |
   Isolated Agent Trial Runner ---- Agent runtime / sandbox
           |                         (fresh session, fixed candidate)
     Immutable Output + Trace + Artifacts
           |
   Deterministic / Python / Model Graders
           |                         (no agent tools or shared secrets)
       Versioned Grader Results
           |
    Aggregate + Comparability Check
           |
    Dataset Report / Baseline Diff / Export
```

发布质量门禁回答“配置是否允许发布”，本功能回答“此候选在指定任务上的实际表现”。评估草稿不执行语义发布门禁，不产生发布 receipt，不自动改变 Agent 状态；资源和执行权限仍必须校验。

**理由：**调优过程需要测量尚未合格的候选；把发布准入当作评估前置条件会妨碍诊断，也混淆静态检查和任务效果。

## 3. 核心模型与扩展契约

### 3.1 术语与实体关系

| 对象 | 定义 | 可变性 / 关系 |
|---|---|---|
| `Evaluation` | 一个 Agent 的持续评估项目：名称、owner、agentId、默认配置和 baseline 指针 | 管理信息可编辑；不存成千上万条结果 |
| `EvalDatasetRevision` | 完整、验证通过的任务集合：case、附件、fixture、split、内容摘要 | 不可变；数据修改产生新 revision |
| `EvalCase` | 稳定 ID 的一项任务与成功条件 | 属于固定 dataset revision；同 ID 改内容必须改变 case hash |
| `GraderRevision` / `GraderSuiteRevision` | 单个评分器及组合成功策略；代码、模型路由、阈值、参数全部版本化 | 不可变；删除采用 retire，不破坏历史 |
| `EvalSpecRevision` | 测量协议：dataset / grader suite / harness / profile / repetitions / budgets / aggregation / effective clock / immutable case selection | 不含候选 Agent；固定 split、case IDs / hashes，改变条件或 subset 产生新 revision |
| `AgentCandidateSnapshot` | 本次被测 Agent 的可执行冻结对象及可固定依赖图 | 一次构建；Run 内所有 trial 使用同一对象 |
| `EvaluationRun` | 某 Agent candidate 在某 EvalSpec 下的一次完整实验 | `purpose=FORMAL/SMOKE/DIAGNOSTIC`；experimentLabel 可标 A/A / bridge。创建后规格 / purpose 不可变 |
| `EvalTrial` | 一个 case 的一次计划重复：`(runId, caseId, repetitionIndex)` | 计划 ID 唯一；执行输出一旦终态不可覆盖 |
| `TrialAttempt` | 平台执行尝试、lease generation、dispatch token 和诊断 | 不把 transport 重试算成新的统计 trial |
| `GradingRun` | 固定 trial 输出在固定 grader suite 下的一次评分 | 评分重试不覆盖历史；新 grader 的重评分创建新对象 |
| `RunReport` | 全部 trial 结果、聚合指标、覆盖率与比较资格 | 终态报告不可变；人工复核作为附加记录 |
| `BaselineSelection` | 用户指定一个正式有效 Run 为基线 | 指针变更 CAS + 审计；保留历史选定记录 |

关系：`Evaluation -> EvaluationRun -> EvalTrial -> output / trace / GradingRun`；Run 引用 `EvalSpecRevision + AgentCandidateSnapshot`，EvalSpec 引用 `DatasetRevision + GraderSuiteRevision`。

**理由：**项目、数据、仪器、候选和实际运行分开，才可以区分“Agent 变好了”“测试集变了”“评分器变了”；结果不应覆盖任务定义。

### 3.2 Agent candidate 与执行 profile

用户通常选择 `DRAFT` 或 `PUBLISHED` 来源；A/A 与 bridge 使用受控 `SNAPSHOT_REF`，引用该 Evaluation 历史 Run 的 candidateSnapshotId。服务端检查 Agent identity、snapshot ACL 和当前依赖执行权限，禁止引用其他用户 snapshot 扩大权限。preflight 一次性读取并生成 / 复用同内容 snapshot：

- Agent ID、来源 revision、name / description、definition type、实际 executable config。
- 解析后的 managed prompt / input template、variables schema；secret 只记录 credential reference 与版本摘要，不把明文写到普通报告。
- 模型 alias、实际 provider / model、generation parameters、能获得的模型版本；不能固定上游滚动模型时标记 `MODEL_VERSION_UNPINNED`。
- 工具 / skill / dataset / subagent 的选择、权限摘要、可固定内容 / 版本 hash；子 Agent 按 published executable config 冻结，依赖图去重、循环 / 深度超限 preflight 拒绝。
- harness / capability assembler / sandbox image digest、执行 profile、工具开关、memory 策略、网络和环境契约。

snapshot 是评估专用内部对象，不把草稿临时写入 `publishedConfig`。不能让每个 trial 再按 live agent ID 创建普通用户会话；否则中途编辑会改变被测对象。执行器必须有可信内部的 snapshot 入口，不向普通请求暴露任意配置注入。

| Profile | 可执行内容 | 固定 / 重置要求 | 比较资格 |
|---|---|---|---|
| `OFFLINE_TEXT` | AGENT 无工具推理；LLM_CALL 的原生 completion 路径 | 新会话、无记忆；模型与 harness 指纹 | 固定条件后可比较；LLM_CALL 不假装支持 AGENT 的模板渲染 |
| `ISOLATED_SANDBOX` | 已审核本地文件 / 代码工具，受支持的 skill / subagent / 文件型 fixture | 每 trial 新 workspace / sandbox；只挂载 case 公开输入，网络默认关闭 | 环境 capability 验证和 reset 验收通过才开放 |
| `CONTROLLED_INTEGRATION` | 受控测试账户 / 外部只读或可重置工具 | 工具 owner 审批、credential / 服务版本、状态重置与审计 | 首版预留，不开放任意 API / MCP；无可证重置时仅探索运行 |

Agent 配置了 profile 不支持的能力时 preflight 拒绝，并列出缺口。可以显式保存另一个候选配置 / profile 后测试，但 UI 必须说明“执行能力已改变”，不允许静默剥离工具。

正式重复 trial 禁止共享 persistent memory、共享写 dataset、用户 home、业务会话 history。启用 `system.time` 的 AGENT 任务通过 harness `evaluationClock` 注入明确时间：case 有 clock 则使用该值，否则继承 EvalSpec 的 `defaultEvaluationClock`。创建 spec 时默认取一次 UTC 时间并固化；同 spec 的 baseline / candidate 沿用同一值，不能每个 Run 重新取 now。LLM_CALL 没有 AGENT 时间模板渲染，不伪造注入；依赖该能力的 case 必须明确把固定时间放入用户 input，或 preflight 拒绝。真实时间敏感的外部集成不能伪装为离线可重现。

**理由：**固定配置不能固定现实世界。将能力和条件写成 profile，有助于对不支持的执行面诚实拒绝，而不是给同名 Agent 测出不可解释的分数。

### 3.3 指纹与兼容性

| 指纹 | 内容 | 用途 |
|---|---|---|
| `candidateFingerprint` | Agent、prompt、模型 generation 设置、可固定依赖内容 | 证明测了哪个候选；比较时允许变化 |
| `datasetFingerprint` | manifest、按 case ID 归一的 case 内容、附件 / fixture hash、split | 防止同名数据集悄悄变更 |
| `graderFingerprint` | suite、参数、代码 / image、judge instructions / criteria、实际 route | 同一测量仪器 |
| `environmentFingerprint` | harness、profile、sandbox、tool policy、clock 策略及 `defaultEvaluationClock` / 每 case 有效 clock 值、secret version references | 同一执行条件 |
| `evalSpecFingerprint` | 上述非 candidate 条件、固定 case 选择、重复次数、并发、相对预算时长、聚合定义；不含 Run 操作时间戳 / 绝对 deadline | baseline 严格兼容检查 |

hash 使用版本化 canonical JSON：对象 key 排序；set 型 ID 集合排序，消息序列和实际有意义的数组保序；引用文件按 bytes 计算 SHA-256。只存 alias、文件名或 revision 文本不算冻结。

Agent 的目标模型变化属于候选变更，允许比较并报告成本差异；grader 模型路由变化属于测量仪器漂移，不允许严格直接比较。依赖修改若是明确的候选变化可比较；无法 pin 的外部状态变化是混杂因素，必须降级比较资格。

**理由：**不能用单个全量 hash 同时表达“候选应该变化”和“测量条件不应变化”。

### 3.4 执行与评分状态

Run：`QUEUED -> PREFLIGHT -> RUNNING -> FINALIZING -> COMPLETED`；取消路径为 `QUEUED / PREFLIGHT / RUNNING / FINALIZING -> CANCELLING -> CANCELLED`。其他终态为 `COMPLETED_WITH_ERRORS / BUDGET_EXHAUSTED / FAILED`；终态不允许继续进入 RUNNING，晚到证据仅附加诊断与账单。

Trial 执行：`PENDING -> LEASED -> PREPARING -> EXECUTING -> EXECUTION_TERMINAL`，终态结果字段独立为：

| execution result | 含义 | 是否是任务性能信号 |
|---|---|---|
| `SUCCEEDED` | runtime 正常结束并形成最终输出 | 不等于任务成功，仍需评分 |
| `AGENT_FAILED` | Agent 逻辑错误 / 耗尽 turn 或 token 上限等已确认目标失败 | 是；失败计入主指标 |
| `TIMEOUT` | 达到固定 trial deadline 且已确认执行终止 | 是；固定预算下没有成功完成 |
| `PLATFORM_ERROR` | 初始化、调度、存储、transport 等平台故障，目标表现无法测量 | 不应断言 Agent 错；降低覆盖率和 baseline 资格 |
| `OUTCOME_UNKNOWN` | 已 dispatch 后丢失可靠 completion / 外部状态未知 | 不自动重放；人工 / runtime reconcile |
| `CANCELLED` / `NOT_EXECUTED` | 用户取消 / 总预算耗尽，尚未完成或未开始 | 显式保留计划分母 |

评分状态：`PENDING / RUNNING / COMPLETED / ERROR / CANCELLED / NOT_RUN_TARGET_FAILED`；后三者为终态，最后一个表示因已确认目标失败不再启动评分，不代表不适用或通过。单 grader 结果：`PASS / FAIL / INDETERMINATE / NOT_APPLICABLE`；`ERROR` 是评分执行故障，不是低分。`NOT_APPLICABLE` 只能由预声明 applicability 规则产生，不能因脚本崩溃或 Agent 没输出而跳过。

试验任务结果 `SUCCESS / FAILURE / UNMEASURED` 由平台按 suite 计算：preflight 固定每 case 的适用 required graders，必须非空；只有 execution_result=SUCCEEDED 且这些 graders 全部 PASS 才是 SUCCESS。任一适用 required FAIL，或明确 AGENT_FAILED / TIMEOUT，为 FAILURE；否则为 UNMEASURED。required grader 不得在看到实际输出后自行返回 NOT_APPLICABLE；与 preflight applicability 不符记 GRADER_ERROR，禁止“所有规则跳过”的空集合通过。平台 / 评分错误保留，不当成合格；已有确定 FAILURE 时同时展示其他错误。

**理由：**“run 完成”“Agent 返回”“任务成功”“评分成功”是四件不同的事；分开建模才不会丢失失败分母或把基础设施错误归罪于 Agent。

## 4. 数据集与评分器规格

### 4.1 数据集不等于现有业务 Dataset

评估集是任务 / reference / 判据的版本化集合，不能直接复用 Agent 检索或 output dataset 的业务语义。现有 Dataset 可以作为经过权限检查的导入来源或 case fixture，但评估 manifest、隐藏参考、结果必须存到专用 Eval store，不能挂给被测 Agent 自动读取。

每条数据至少有 `case_id + input + grader_refs`。`expected_output` 在界面叫“预期输出”，规范字段采用 `reference.output`；若无唯一答案可以只提供 rubric / assertions，但必须有覆盖该 case 的 required grader。没有判据的行不能进入正式评估，只能作为 smoke 输入。

### 4.2 EvalCase v1 字段

| 字段 | 必需 / 类型 | 语义与校验 | 对 Agent 可见 |
|---|---|---|---|
| `case_id` | 必需，String | 数据集内唯一、稳定；建议业务含义 ID，不用可变行号 | 可作为诊断 ID，不发送 reference |
| `input.text` | 必需，String | 单轮用户输入；非空，不能伪装 system / tool 消息 | 是 |
| `input.variables` | 可选，Map\<String,String\> | 只能覆盖 Agent 声明的评估可输入变量；不能覆盖权限、工具或 secret | 经模板渲染后可见 |
| `input.attachments` | 可选，List\<AttachmentRef\> | 相对资源路径、SHA-256、MIME、受控目标文件名；按原生 runtime 输入方式交付 | 是 |
| `reference.output` | 可选，String | 参考文本；精确匹配 / 语义 grader 使用；可以是合法 JSON 文本 | 否 |
| `reference.answers` | 可选，List\<String\> | 有限合法答案集合；明确 normalization 规则 | 否 |
| `reference.rubric` | 可选，String | 单条任务的验收要求；不是自动证明正确的标准答案 | 否 |
| `reference.assertions` | 可选，List\<TypedAssertion\> | 如文件路径、JSON 字段、数值区间、trace 事件断言；具体类型 DTO | 否；不把 oracle 文件挂给 Agent |
| `grader_refs` | 必需，List\<String\> | suite 中存在的 grader ID；至少一个 required 判据，suite 统一定义 required / optional | 否 |
| `grader_parameters_json` | 可选，String | grader schema 校验后的 JSON；不可修改 grader 代码 / model route | 否 |
| `environment.fixture_ref` | 可选，String | manifest 中公开初始文件 / 测试状态 ID；profile 必须支持 | 仅公开初始状态 |
| `environment.clock` | 可选，ISO-8601 String | 本题运行时钟，覆盖 EvalSpec 固定 defaultEvaluationClock；不使用脚本执行机器的随机当前时间 | 按原生时间模板注入 |
| `metadata.tags` | 可选，List\<String\> | 能力、难度、语言、场景等切片 | 默认否 |
| `metadata.split` | 可选，Enum；缺省 `DEV` | `DEV / VALIDATION / HOLDOUT`；正式 baseline 建议 VALIDATION / HOLDOUT | 否 |
| `metadata.weight` | 可选，Double | 默认 1，正有限数；冻结后不可根据结果调整 | 否 |
| `metadata.source` / `note` | 可选，String | 来源、人工标注依据与注意事项；敏感信息最小化 | 否 |

manifest 统一限制允许字段；额外 JSON 扩展只允许 schema 校验后的 `String` JSON text，不使用 `Map<String,Object>`。持久化 Boolean / Integer / Double 等均为包装类型。输入消息多轮格式另设 schema v2，首版不把任意 messages role 混入 v1。

**理由：**input、reference、environment、measurement metadata 有不同信任边界；字段分组使隐藏答案和工具权限不会因一次序列化被一起送给 Agent。

### 4.3 文件格式、示例与导入

首版正式格式为 JSONL；ZIP 仅打包 JSONL、manifest、grader 与附件。推荐布局：

```text
manifest.json
cases.jsonl
graders/normalize_summary.py
inputs/case-file-001/source.txt
fixtures/file-task-v1/initial-data.csv
```

`manifest.json` 示例（所有版本号为示例，不代表已实现）：

```json
{
  "schema_version": "core-ai-eval/v1",
  "name": "assistant-regression",
  "cases_file": "cases.jsonl",
  "graders": [
    {"id": "answer-exact", "kind": "EXACT", "required": true, "normalization": "TRIM"},
    {"id": "file-summary", "kind": "PYTHON", "required": true, "entrypoint": "graders/normalize_summary.py", "output_schema": "core-ai-grader/v1"}
  ],
  "fixtures": [
    {"id": "file-task-v1", "path": "fixtures/file-task-v1", "profile": "ISOLATED_SANDBOX"}
  ],
  "default_suite": {"grader_ids": ["answer-exact", "file-summary"], "success_policy": "ALL_APPLICABLE_REQUIRED_PASS"}
}
```

`cases.jsonl` 每行一个 JSON 对象；以下两个对象各自为一行：

```jsonl
{"case_id":"text-001","input":{"text":"只输出 2+2 的结果。"},"reference":{"output":"4"},"grader_refs":["answer-exact"],"metadata":{"tags":["arithmetic","zh"],"split":"VALIDATION","weight":1.0}}
{"case_id":"file-001","input":{"text":"读取 /workspace/initial-data.csv，计算 amount 总和，写到 /workspace/summary.json，格式为 {\"total\": 数字}。"},"reference":{"assertions":[{"kind":"JSON_NUMBER_EQUALS","path":"summary.json","json_pointer":"/total","expected_number":120.0,"tolerance":0.000001}]},"grader_refs":["file-summary"],"environment":{"fixture_ref":"file-task-v1","clock":"2026-11-21T00:00:00Z"},"metadata":{"tags":["file","code","zh"],"split":"VALIDATION","weight":1.0}}
```

CSV 是入门适配器：必有 `case_id,input`，可有 `expected_output,tags,split`；创建时选择统一 grader 模板，转换成相同 v1 JSONL。不支持 CSV 单元格任意代码执行；附件与复杂断言使用 ZIP。

导入两阶段：`UPLOAD -> VALIDATING -> READY / INVALID`。校验编码 / 格式 / schema、ID 重复、grader 适用性、JSON reference、路径与 hash、附件 MIME、解压总量 / 文件数 / 压缩比、安全扫描、case budget、必要判据。错误包含文件、行、字段和建议；默认整批原子失败，不静默跳过。用户可明确勾选剔除无效行生成另一完整 revision，并记录剔除清单。

manifest 的 `default_suite` 为导入向导预填，保存后生成正式 GraderSuiteRevision；每 case 的 `grader_refs` 必须是 suite 子集，其非空适用 required 集在 preflight 固定，不要求所有 case 使用全部 suite graders。脚本示例用于演示 ABI，与 `file-summary` 的文件断言算法不是同一个示例。

客户端上传 manifest 的 `modelAlias` 仅是配置候选，必须映射到服务端已授权目录；不能上传 arbitrary HTTP grader endpoint、image URL、pip requirement 或 credential。文件的实际 SHA-256 在服务端计算，并固化到 revision。

**理由：**JSONL 便于流式导入和逐行诊断；评分脚本与附件独立打包可去重、审核、固定版本，又不要求用户先建设专门数据平台。

### 4.4 Grader 类型与组合

| 类型 | 适用任务 | 输入 / 结果 | 首版限制 |
|---|---|---|---|
| `EXACT / CONTAINS / REGEX` | 唯一短答案、关键内容、有限格式 | output 与 reference，PASS / FAIL | normalization 显式固定；regex 使用有界 / 安全引擎，不开放 ReDoS |
| `JSON_ASSERTION` | 结构化输出、字段 / schema / 数值 | schema、typed assertions，逐断言证据 | 不把格式合法等同业务正确 |
| `TRACE_ASSERTION` | 是否调用 / 禁止某工具、必要步骤约束 | 规范化 trace events | 只检查显式事件；不评价隐藏 chain-of-thought，不能把轨迹相似等同任务成功 |
| `PYTHON` | 文件结果、可计算正确性、自定义规则 | 可信 envelope、受控 artifacts，标准结果 JSON | 无网络 / 无 secret，固定镜像 / allowlist 依赖 |
| `MODEL` | 开放回答、语义满足、rubric | output + reference + 原输入，受限 judge | 固定 model route / instructions / thresholds；无工具，可不确定 |

Suite 为 grader 定义 required / optional、applicability、metric name、诊断可见性和聚合；判定区间属于 GraderRevision，不允许不同 suite 为同一 grader 悄悄覆盖阈值。`mode` 是每个 GraderRevision 的属性，不是 suite 全局开关；suite entry 只绑定 grader revision 与 required / optional。每 case 的适用集 = 该 case 的 `grader_refs` ∩ suite entries；适用 required 集 = 适用集中 `required=true` 的项，preflight 必须非空。首版 `success_policy` 只允许 `ALL_APPLICABLE_REQUIRED_PASS`。支持两种模式：`BOOLEAN` 为 verdict-based 模式，接受 PASS / FAIL / INDETERMINATE；PASS / FAIL 的 score 仅 null 或对应 1 / 0，INDETERMINATE 必须 score=null，不把合法低置信判定当协议错误；`BOUNDED_SCORE` 统一 [0,1] 且高为好，固定 `0 <= failMax < passMin <= 1`，`score <= failMax` 为 FAIL、`score >= passMin` 为 PASS、中间为 INDETERMINATE。脚本返回 verdict 必须与声明模式一致，模型 label / 原量程 score 先按 revision 映射到该契约；初始示例 0.2 / 0.8 只是待校准提案，不能把概率当正确率。默认 required 全通过为任务成功；optional 指标不抵消 required 失败。原始业务指标（例如误差、耗时）使用独立 metric 和 direction，不假装为概率。

首版模型评分优先复用结构化 DecisionProvider 的 noul / choice / score。`ModelGraderSpec` 必含 modelAlias / expectedRouteFingerprint、inputSelector allowlist、trusted instructions、questionType、questionName、criteria / levels、mappingVersion 和 uncertaintyPolicy；不接受自由 URL 或 Agent 工具。

- NOUL 明确高值对应满足该 rubric，用上述 failMax / passMin 映射；概率是判定信号，不宣称为事实正确率。
- CHOICE 固定有限 label → PASS / FAIL / INDETERMINATE 映射与 winning-label probability 下限；未知 label / 缺概率为 ERROR。低于下限为 INDETERMINATE；BOOLEAN label verdict 不伪造一个 [0,1] 质量分。
- SCORE 固定有序 levels（至少 2 个），验证原 score 在 `0..levels-1`，以 `raw/(levels-1)` 标准化，再套用固定判定区间；原值 / levels / mapping 保留。
- confidence 未经校准不默认参与放行；若采用必须版本化明确其语义。模型 route 在 run 内漂移记仪器错误，不静默换模型。

证据来自已记录输出、reference 和确定性断言，不能要求 decision 模型生成不存在的解释。需要可读 feedback 的生成式 judge 属于后续扩展；人工复核可以附加说明，不改写机器分数。judge 校准样例、模糊 / 注入样例和 holdout 一并版本化。

**理由：**先采用可确定验收，再用模型处理语义边界；轨迹、答案和实际环境结果是不同维度，不能只提供一个 judge prompt 解决所有任务。

### 4.5 Python grader ABI 与隔离

首版 Python ABI：平台把只读、完成后不再变化的 envelope 写到 `/eval/input.json`，以批准的固定 Python 镜像执行 `python /grader/main.py /eval/input.json`。stdout 必须且只能输出一个 `core-ai-grader/v1` JSON；stderr 为截断诊断。exit 0 + schema 合法才是评分完成；exit 非零、超时、无 JSON、多 JSON、非法 NaN / 越界均为 `GRADER_ERROR`，不计 FAIL。

envelope 字段：`schema_version,case_id,input,reference,actual,execution,trace_summary,artifacts,grader_parameters_json`。`actual` 包含 `output_text / structured_output_json`；`execution` 包含终态、终止原因与 elapsed_ms；artifacts 是受控相对路径、hash、MIME、大小和只读读取位置。脚本输出的 verdict 是按其版本化规则验证的评分输入；脚本自行填写的 agentId / runId / trialId 不是权威身份，平台根据 dispatch context 绑定结果。

- grader 容器与 Agent 容器 / workspace 分离，Agent 不能修改脚本、reference 或 envelope。
- 仅只读挂载 grader 包、reference 和经过清单选取的 artifacts；临时空间单独分配。
- 默认非 root、只读根文件系统、禁止网络、无 cloud metadata / Docker socket / server filesystem / 用户 home / env secret；资源限制由容器 / sandbox enforcement 保证，不靠 prompt。
- 不允许 runtime pip install；依赖只来自批准镜像或平台 allowlist。未具备该隔离能力的环境不得开放脚本入口。
- artifact 导出拒绝绝对路径、`..`、symlink / device、超大小文件；防止 Agent 通过文件读取服务器路径。代码执行图片 / 文本后生成的文件仍是不可信内容。

最小评分脚本示例：

```python
import json
import pathlib
import sys

payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
passed = payload["actual"]["output_text"].strip() == payload["reference"]["output"].strip()
print(json.dumps({
    "schema_version": "core-ai-grader/v1",
    "verdict": "PASS" if passed else "FAIL",
    "score": 1.0 if passed else 0.0,
    "feedback": "Matches reference" if passed else "Does not match reference",
    "metrics": [],
    "evidence": []
}, allow_nan=False))
```

**理由：**eval 脚本是用户上传的不可信代码。普通 Agent 的代码执行能力或个人沙箱不天然满足 grader 隔离；这个 ABI 必须有独立权限和运行限制。

### 4.6 首版 grader 执行后端

首版选择 **Kubernetes 专用 grader Job**，不是给普通 bound sandbox 调一次 `/execute`。服务端 `EvalGraderJobRunner` 用平台批准、digest 固定的 Python image，通过可信 control-plane API 建立 unbound Job；不注入 CORE_AI_HUB / session token / service account token。NetworkPolicy 显式 deny egress，非 root、read-only root、seccomp / capabilities / CPU / memory / deadline 按 §4.5 和 §5.3 enforcement。

staging 由独立可信 staging Job / control-plane 服务先获取指定 input / grader / artifact 对象，完成后将只读 volume 交给 grader Job；不在同一 deny-egress Pod 使用需要联网的 init container。短期单对象凭证只存在 staging 环境，绝不挂给 grader Pod。grader 容器仅看到只读 `/eval`、`/grader`、`/artifacts`、有限结果 volume 和私有 tmp。执行 wrapper 分离并流式限额捕获 stdout / stderr、记录 numeric exitCode、signal、timedOut、stopRequested / stopConfirmed；process group kill 后确认结束，再把结果保存到 Job 专用结果 volume。可信 result collector 从退出后的 volume 收集，不赋予 grader 业务网络权限；输出仍按不可信 schema 验证。

Job UID 由 dispatch token 绑定，create 去重 / get / cancel / phase / exit status 可 reconcile。K8s Job 自身 restart / retry 设置为 0，任何技术重试由 EvalGradingCoordinator 在固定 output 上明确登记。生产集群需验证网络 policy 的实际 enforcement、volume 权限、staging 凭证隔离和 cleanup；仅写 Pod label 不算无网。当前普通 Sandbox API 缺少结构化 process result 时，禁止沿用 textual completed 伪造 exit 0。

Docker / warm-pool 的 grader adapter 首版延后；没有合格 K8s grader backend 的部署仍可用确定性 / model grader，但 Python 标 unavailable，不能宣称完整脚本功能已交付。用户如要求本地 Docker 优先，需要 Review 选择同等级隔离的专用 runner，不更改 ABI。

**理由：**先选一个有可持久查询生命周期的后端实现安全边界，比同时承诺所有 sandbox provider 更可执行；评估调度和 grader ABI 不被绑定到普通 Agent 的权限环境。

### 4.7 评分输出与实际结果契约

```json
{
  "schema_version": "core-ai-grader/v1",
  "verdict": "FAIL",
  "score": 0.0,
  "feedback": "summary.json 的 total 与参考值不一致",
  "metrics": [{"name": "absolute_error", "value": 3.0}],
  "evidence": [{"kind": "ARTIFACT_JSON", "artifact_path": "summary.json", "json_pointer": "/total", "expected": "120", "actual": "117"}]
}
```

- `verdict` 为有限标签；`score` 可 null，仅对支持分数的 grader 必填；与 verdict / threshold 不一致时拒绝结果。
- `metrics` 必须在 GraderRevision schema 中声明，值有限，方向 / 单位 / 聚合固定；禁止动态生成高基数 metric 名。
- `feedback` 有长度上限，纯文本显示；不能包含可执行 HTML / arbitrary links。evidence 只能引用已登记 artifact / trace event / output 坐标，不能信任虚构文件。
- **所有 grader feedback、expected evidence、script stderr 和 oracle-derived diagnostics 默认为 ORACLE_PROTECTED**，按 `evaluation.oracle.view` 在 API、UI、日志下载和 export 一致过滤。任意脚本可以把 reference 复制进反馈，不能只隐藏 reference 面板就宣称答案安全。普通 viewer 仅看到 verdict、coverage、经 suite 明确批准公开的数字 metrics 与实际输出；人工审核安全的静态提示模板可以作为 public feedback，不能自动把模型 / 脚本自由文本脱敏后当绝对安全。
- 平台在外层补充 `grader_id,revision,trial_id,status,duration_ms,usage,route_fingerprint,error_code`，脚本不能篡改。
- 导出的结果 JSONL 包含 `case_id,repetition_index,candidate_fingerprint,execution_result,actual_output,artifact_manifest,grader_results,task_result,timing,usage`；不与输入 JSONL 混用，不默认导出密钥 / 私有原始 trace。

**理由：**用户可以决定怎样评分，但平台必须控制身份、版本、失败归类和接受输出的协议，避免“任意打印一个 pass”成为不可审计结果。

## 5. 执行协议、隔离、并发与恢复

### 5.1 Preflight 与一次冻结

开始操作需要 `Idempotency-Key + expectedEvaluationRevision`。服务端依次：

1. 验证当前操作者对 Evaluation、Agent 来源、私有附件 / fixture / 依赖、judge / script profile 的权限。
2. 检查 dataset READY、每 case 判据完整、grader suite 已通过正反 / 异常 smoke test（测试 receipt 随 GraderSuiteRevision 固化）、sandbox 隔离 capability 就绪。
3. 生成唯一 snapshot，验证 definition type / template / capability 与真实 runtime 一致；目标模型和 judge 分别解析路由。
4. 估算 `cases × repetitions`、最大 turns / tokens / 时间、Agent 与 judge 费用、sandbox 数量；预算未知显式显示，不记成零。
5. 固化 EvalSpec、snapshot、计划 trial IDs、caller credential reference / actor 和预算；创建 Run 与计划分母。
6. 数据库唯一约束保证同 key 同输入只有一个 Run；同 key 不同内容返回 409；重试取回原 Run，不重新快照最新草稿。

权限不能冻结成永久授权。调度前与敏感资源使用前检查当前资源访问，撤权即终止相关任务；snapshot 固定的是内容，不是取消后的权限。run 中路由漂移 / 依赖无法固定时停用严格比较并记录，不能悄悄回退到 chat model 或最新 live config。

**理由：**preflight 尽量阻止批量昂贵失败；鉴权不应因“已经开始评估”而绕过未来的撤权。

### 5.2 独立 trial 环境与工具执行

每 trial 创建 fresh session 和隔离环境，仅注入该 case 的公开 input / fixture。执行顺序为 `prepare -> verify reset -> invoke snapshot -> observe completion -> collect artifacts -> cleanup`。

- fixed snapshot 使用 Agent 原生运行语义，不能为了评估拼一个和 server runtime 不一致的临时 chat prompt。
- Agent 的调优对象可包含工具策略；但 runtime 的 tool authorization、安全 hook、step/token 上限始终有效。
- case 用户输入、附件和 initial fixture 可以被 Agent 读；reference / grader / 其他 case 不进入 Agent session。
- subagent 子会话纳入同一 trial lineage、配额与取消树；不能把子任务漏出调用 / 成本统计。
- sandbox fresh workspace 不等于环境 fresh：home、memory、dataset output、MCP process、共享缓存也需禁写 / 分离。preflight 必须检查这些 capability，未完成 reset contract 不允许正式工具模式。
- runtime completion 必须有权威终态；SSE 断开、浏览器关闭或最后一个文本 chunk 都不等于 Agent 完成。
- artifacts 先计算 hash 并从 Agent 可写环境提取到只读存储，再开始评分；grader 不在线读取仍被 Agent 改写的文件。
- cleanup 失败另记资源泄漏告警；不能把已完成业务评分改成 FAIL。未确认终止的执行不得释放资源槽位到再次启动副本。

**理由：**Agent eval 的最终答案可能不代表实际行为，必须观察权威 outcome；每题重置才能避免跨题污染和重复次数越多“记住答案”的假提升。

### 5.3 两阶段并发与预算

用户 `agent_concurrency` 是本 Run 同时活跃的顶层 trial 上限，不是每个内部 subagent 或 provider call 的上限。另有系统 `grader_concurrency`；两者与平台共享资源上限共同约束。

调度取 `min(userRunLimit, actorLimit, deploymentAvailableCapacity, sandboxCapacity, providerLimit)`，按 actor / Run 公平轮询。Agent、judge、sandbox、subagent 都消耗可追踪的资源 token；不能只限制 HTTP 请求数而内部无限并行。

输出持久化后释放 Agent 执行槽，但评分排队积压超过 `max_pending_grading` 则停止发新 Agent trial，防止百万输出堆积。本地 semaphore 仅控制实例执行资源，部署级并发使用租约 / capacity records 原子获取，不宣称单实例计数是全局限流。

| 参数 | 首版默认 / 上限提案 | 权威定义与说明 |
|---|---|---|
| `agent_concurrency` | 默认 2，用户 1–10 | 本 Run active 顶层 trial；平台可进一步收紧 |
| `grader_concurrency` | 默认 4，平台管理 | Python / model 各有独立子限流 |
| `repetitions` | 默认 3，用户 1–10 | 正式 baseline 建议 ≥3；smoke 默认 1，不合并进正式 Run |
| dataset cases | 默认上限 1,000 | 首版上限，后续按容量压测调整 |
| `trial_deadline_seconds` | 默认 180，上限 900 | 从执行开始计，含子任务，不含队列等待 |
| `trial_max_turns` | 默认 30，上限 100 | AGENT 类型；LLM_CALL 不假造 turn 指标 |
| token / cost budgets | 显式 run / trial cap；未知费用仍以 calls / tokens / time 兜底 | 使用前预留最大允许额度，按实际结算释放 |
| `run_deadline` | 默认 6h，上限 24h | 包含排队、执行、评分；耗尽明确标 BUDGET_EXHAUSTED |
| Python grader | 10s、1 CPU、256 MiB、临时盘 64 MiB | 容器强制限制；复杂任务可申请批准 profile |
| model grader | 单次 30s，每 output / grader 最多 1 次技术故障重试 | 不因低分重试抽样 |
| file imports | 压缩上传 20 MiB、解压 100 MiB、≤1,000 files | JSONL 单行≤1 MiB；独立附件合计上限 100 MiB |
| captured output / trace | output 256 KiB，trace摘要 2 MiB / trial | 超限单独错误，不静默截断用于评分的必需文本 |
| `max_pending_grading` | 默认 2 × effective agent concurrency + 20 | 背压；不是提前丢弃输出 |

这些数值为容量保护起点，不是行业普适推荐或性能承诺；其他章节引用此表，不另写冲突默认值。用户确认启动前展示计划 trials 和预算，并提示并发变化可能影响延迟、配额和 provider 错误率。

**理由：**并发影响成本与环境，是实验条件的一部分；让用户输入 100 并不能使平台凭空拥有 100 个沙箱。

### 5.4 Durable 调度、取消和故障恢复

`EvalTrial` 使用唯一 `(run_id,case_id,repetition_index)`。claim 通过状态 / leaseUntil / generation 条件 CAS；worker 持有 fencing token，所有状态写入校验 generation。调度、start、completion 分别幂等，Run / Trial store 是真源，不依赖进程内 Future 或 SSE。

- 执行前创建 durable dispatch intent 与 execution token；runtime adapter 需支持同 token 查询 / 去重 start 和 durable completion。PG command queue 可承载唤醒 / 路由，不代替 trial 状态。
- lease 过期：尚未 dispatch 的任务可以安全重排；已 dispatch 的先按 session / token 查询，若仍在运行接管观察，不启动第二个。
- runtime 没有去重 / reconcile 能力时，已 dispatch 状态不明标 `OUTCOME_UNKNOWN`，不自动重放。即使离线无业务副作用，自动重跑也会改变随机样本，需显式新 Run。
- transport 的“未发送”技术错误允许有界 retry；已确认 target timeout / 低分 / 任务失败不重试。provider 原生重试必须记录，不能隐藏其耗时和 cost。
- grader 技术故障可对已持久化相同 output 最多重试一次；旧失败 attempt 保留。修改 grader 后的 regrade 不重新执行 Agent，产生新 GradingRun，不能覆盖旧 baseline 报告。
- 用户取消：Run CAS 为 CANCELLING、停止新 claim、取消 session / 子任务 / sandbox；确认 terminal 后计入 CANCELLED。无法确认的仍 OUTCOME_UNKNOWN。
- progress / aggregate 由持久 trial 重算；FINALIZING 可幂等重入；晚到 completion 不复活取消 Run，但保留诊断和实际费用。
- 不承诺外部工具 exactly-once；后续集成 profile 必须由工具 adapter 提供 idempotency / read-only / reset 契约，否则拒绝正式运行。

**理由：**server 重启、多实例或断线后的自动重跑可能重复业务操作，也可能把一次失败悄悄替换成幸运成功。持久状态和 fencing 保证可恢复，但不凭空提供远程执行原子性。

### 5.5 证据完整性、超限与取消终态

- capture manifest 为每个必需 evidence 记录 `AVAILABLE / ABSENT / OVERSIZED / CAPTURE_ERROR`。已确认 Agent 完成且没有按任务要求生成文件是 ABSENT，可评分为 FAILURE；对象提取 / 存储失败是 CAPTURE_ERROR，相关判据 UNMEASURED，不能当文件不存在。
- output 上限若已作为公开 task / profile 契约声明，Agent 超限记 `AGENT_FAILED(OUTPUT_LIMIT)`；仅平台测量缓存不足导致输出无法完整保存记 `PLATFORM_ERROR(EVIDENCE_CAPTURE)`。两者均保留大小 / hash 诊断，不能在评分时静默使用截断的必需 output。
- optional debug trace / feedback 允许明确标记截断；required artifact / trace 断言证据超限则 UNMEASURED，数据 / profile preflight 先检查可预见的大小冲突。judge 的 token contextBudget 独立于 byte limit，必需 state 超上下文时 `GRADER_INPUT_TOO_LARGE`，不分段全 PASS 假装全文通过。
- timeout / cancel 的数据库状态不等于进程已终止：adapter 区分 `stopRequested` 与 `stopConfirmed`。未 confirmed 保留 resource reservation 并 reconcile；达到恢复上限仍未知记 OUTCOME_UNKNOWN，不再 dispatch 同 trial。
- outcome 优先按已确认实际 completion 固化：取消请求前已确认成功 / 失败的结果保留；已确认因 trial deadline 终止为 TIMEOUT；用户取消先发生且确认终止为 CANCELLED。原因、请求 / 确认时间均保留，晚到诊断不覆盖终态。
- CANCELLED / NOT_EXECUTED 的 grading 标 CANCELLED；已确认 AGENT_FAILED / TIMEOUT 不需执行的 required grader 标 `NOT_RUN_TARGET_FAILED`（是执行 disposition，不是 NOT_APPLICABLE）；已有可评分部分可诊断评分但不改变任务 FAILURE。由此 finalizer 不会永远等待不存在的 grader 任务。

**理由：**缺失目标产物和平台丢证据的归因不同；请求取消和真正停止也不同，这些都影响分母、资源和 baseline 资格。

### 5.6 报告结束与重新运行

所有计划 trial 有执行 / 评分终态后结束 Run；COMPLETE 不代表全部成功。部分平台 / grader 错误为 COMPLETED_WITH_ERRORS，取消 / 预算耗尽保留不完整报告。是否可做 baseline 由 §6.4 的 eligibility 决定，而不是只检查 Run status。

“重跑失败样例”创建带明确 subset manifest 的新 Run；不得拼回旧 Run 后把两个样本中的较好结果当一次完整测量。“重评分”保留相同 outputs 与 runtime metrics，明确只更换测量仪器，不能用于证明新 Agent 候选改善。

**理由：**探索性诊断和正式回归可以共享基础设施，但报告身份和统计语义必须分开。

## 6. 指标、聚合与 baseline 比较

### 6.1 分母与缺失结果

设选定 split / subset 有 N 个 case，每 case 重复 R 次，计划 trial 数 `P=N×R`。S 为 SUCCESS，F 为明确 FAILURE，U 为 UNMEASURED，且 `S+F+U=P`。明确失败包括固定预算下的 Agent timeout / turn exhaustion；平台 / grader 错误不假称目标失败，但不从计划分母消失。

| 指标 | 定义 | 展示要求 |
|---|---|---|
| `success_rate_lower_bound` | S / P | 主卡“计划任务成功率下界”；U>0 时不能叫完整准确率 |
| `success_rate_upper_bound` | (S+U) / P | 缺失可能造成的区间，仅是缺失结果边界，不是统计置信区间 |
| `measurement_coverage` | (S+F) / P | 已取得任务结论的比例 |
| `measured_success_rate` | S / (S+F) | 辅助诊断；分母为零显示 N/A；不作为有缺失的正式 baseline 主指标 |
| `completion_rate` | 已确认执行终态 / P | 与任务成功和评分覆盖分开 |
| 各 grader pass / score | 适用且评分成功的结果上计算，另列适用数 / ERROR / 不确定数 | 不把 NOT_APPLICABLE 或 ERROR 默认填 0 / 1 后藏起来 |

主报告显示执行错误分类、评分错误分类、U 的原因以及未开始 / 取消数。已有明确 required FAIL 的 trial 可以归 F，同时保留其他未完成 optional / required graders；baseline eligibility 仍要求全部适用 required grader 完成或仅因目标失败按规则无需评分。

case 权重 w>0 时，trial 权重 `w/R`，按所有计划权重 W 计算成功率上下界；默认所有 case 等权。分层标签重叠时禁止把多个 slice 的分母相加当全量。聚合默认算 case mean 再算全数据 mean，避免有更多 trial 或更多 grader 的 case 被隐式加权。

**理由：**删除基础设施错误会虚高结果，全当 Agent 错又错误归因；同时报告覆盖率和成功率下界，让缺失诚实可见。

### 6.2 重复试验与指标方向

每个 case 记录 R 个独立 trial，默认报告 per-case success fraction、每个 grader 自己的重复均分与失败波动。不默认跨 required graders 算一个平均分；只有显式定义兼容量程 / 权重 / 聚合的版本化 metric 才能组合分数，且不能改变硬成功判据。

- `any_success@R`：某 case 有任一 S 则 true，全部 F 则 false，否则 unknown。它回答“R 次机会中能否成功一次”，不能作为单次可靠性指标。
- `all_success@R`：某 case 有任一 F 则 false，全部 S 则 true，否则 unknown；不是只要出现 U 就忽略已有确定失败。
- 两项均以全部计划 cases / 权重为分母，报告确定 true 比例下界、true + unknown 的上界和 unknown 数；只在无 unknown 时展示单值。空 split / subset（N=0）在 preflight 拒绝，不生成零分或空集合全通过。
- 不默认报告“最佳输出分数”，不在重复中挑最优覆盖 baseline。
- `pass@k` 等名字只在定义、trial 数和采样协议清晰时使用；首版 UI 用 any / all 明确含义，不把观察到的成功率直接幂运算称为实测 pass^k。
- judge 随机性与 Agent 随机性分开：Agent R 次调用分别评分；judge 技术 retry 不算新增 Agent trial。judge 复测 / 校准是独立测试，不以多数投票隐藏不确定。

任务质量与效率并列展示：成功率、各维分数、Agent wall time、queue time、sandbox prepare time、grading time、end-to-end time、tokens、tool calls、cost。延迟 p50 / p95 分别列全部已执行与成功 trial，timeout 保留 capped latency / count，不能只在成功快样本上报告。

**理由：**Agent 经常偶尔成功，但用户需要稳定成功；成本与延迟也属于优化代价，不能只提升通过率而不披露预算变化。

### 6.3 成本、轨迹与切片诊断

- 目标 Agent / subagent / judge / sandbox 成本分开；记录 provider 实际 usage 和价格版本，缺失项标 `cost_unknown`，给已知总额而不是伪精确总价。
- 工具业务费用无法获得时记录 external cost unknown，预算以资源次数上限兜底。
- 规范化 trace 包含公开输入 / 输出、tool name / arguments 摘要 / result / duration / error、子任务 lineage、终止原因；默认脱敏，不承诺提取隐藏 chain-of-thought。
- 按 language / capability / difficulty / split 看结果和样本数；小切片不显示确定性趋势结论。固定 benchmark 之外的探索案例单独呈现。
- 人工复核 `AGREE / DISAGREE / INVALID_CASE` 附加到结果，带 reviewer / 日期 / 理由；不直接修改机器原始评分。确认坏标注后新增 dataset revision 并建立 bridge run。

**理由：**整个数据集分数告诉用户有没有变化，逐 case 轨迹和切片才告诉用户该改哪里。

### 6.4 baseline 资格与兼容性检查

baseline 指定在 Evaluation 内，不能跨 Agent 偷换候选；同 Agent 的 prompt / model / tool 候选变化允许。**基线选定资格与可复现 / 比较等级分开。** 可选基线默认需要：正式 Run、全计划覆盖、无未知执行结果、无必需 grader ERROR / INDETERMINATE、未取消 / 未预算终止、profile 已验收隔离、grader 正反样例有效且无运行中仪器漂移。已确认 AGENT_FAILED / TIMEOUT 可以计为失败并形成基线；全 FAIL 的 0% baseline 也有效，不要求至少一个 PASS。

上游目标模型或 judge 不暴露不可变模型版本时，只要本次测量完整且路由 / grader 配置固定，用户仍可在确认 `MODEL_VERSION_UNPINNED` 警示后选定该 Run 为**现实参考基线**；之后比较标 EXPLORATORY，不承诺完全可复现。smoke / diagnostic subset 不可作为正式全量基线。不能把模型版本不可 pin 等同于“无法建立 baseline”，否则多数实际 provider 会使本功能失去核心价值。

| 对比等级 | 条件 | UI / API 行为 |
|---|---|---|
| `STRICT` | dataset / case hashes / split / graders / harness / profile / repetitions / concurrency / 相对 budgets / clock 策略及全部有效 clock 值一致；操作时间 / 绝对 deadline 不参与；候选差异明确；coverage 完整 | 显示主差值、配对统计、回归样例 |
| `EXPLORATORY` | 轻微条件改变、unversioned provider、部分缺失，或明确外部环境混杂 | 并排查看，醒目标注差异；统计仅供诊断，不宣称严格归因；是否可选基线独立按完整测量资格检查 |
| `INCOMPATIBLE` | 数据集、grader、输入 / reset 契约等测量关键项不一致 | 不计算单一质量提升；提供差异清单和 bridge run 操作 |

同规格但上游模型不暴露固定版本时，`MODEL_VERSION_UNPINNED` 使严格可复现等级降为 exploratory；用户可记录现实可比性的人工确认，但平台不把它标为完全可复现。即使其输出可用，也应保留混杂因素标签。

数据集 / grader 更新后，推荐在新 EvalSpec 下分别运行旧 baseline candidate 与新 candidate，产生 `bridge` 对比；旧规格仍可保留。只对历史 outputs 重评分能隔离 grader 变化，却不能覆盖新的 case、工具执行或 Agent 能力，不等于 bridge 全执行。

baseline 切换使用 expected generation，必须填写原因；不自动选最高分 Run。调优阶段可以看 DEV，验证用 VALIDATION；HOLDOUT 管理权限和 reference 访问分开，不把完整隐藏答案发给只具有运行权限的人。反复针对 HOLDOUT 调优造成过拟合时，需新 holdout，不宣称技术隔离完全杜绝人工数据泄漏。

**理由：**baseline 的价值来自同一尺子和清楚条件，而不是有一个绿色标签；改变尺子必须把旧对象重新量一遍。

### 6.5 配对差异与统计不确定性

以 case ID + case hash 对齐，不按结果行号。每 case 先计算 R 次成功率 / score，再计算候选减 baseline 的差值 Δ；显示胜 / 平 / 负、从全通过变回退的 cases、hard-failure 新增数和分层差异。

- 95% 配对 bootstrap CI 以 case 为重采样单元，固定统计 seed / 方法版本；不把同 case 重复 R 次当 N×R 个独立任务。首版可用 2,000 次重采样，服务器异步计算。
- R=1 二元结果可辅助给 paired contingency / McNemar exact test；样本小、数据相关或标签噪声大时不把 p 值当“真实世界必然更好”。
- 不确定性包含有限测试集抽样，不自动覆盖上游模型漂移、系统性 grader 偏差或线上分布差异。CI 跨 0 显示“未观察到明确提升”；改善也不自动发布。
- 多切片 / 多次调优是探索统计，显示方法和比较次数提示，不对大量 slice 宣称未校正显著性。
- 成本 / latency 比较同时披露 concurrency、失败和 usage 覆盖；改变并发用于性能试验时创建独立 protocol，不和质量严格回归混用。

**理由：**一个微小总分涨幅可能只是随机性；配对能减少 case 难度差异，但不能让有限 benchmark 变成通用智能证明。

## 7. 持久化模型与服务架构

### 7.1 现有能力的复用边界

| 现有能力 | 复用 | 不直接复用的原因 / 新工作 |
|---|---|---|
| Experiment 导航、AgentVersionEditor、Comparison 页面 | 管理页布局、候选编辑 / 选择、状态和结果 UI | Comparison 是在线人类 A/B；saved version 可编辑，不是不可变 baseline |
| AgentExecutableConfigFactory / workflow transientDefinition | 复制草稿 / published config，构建不修改 live Agent 的内部执行定义 | 根配置复制仍有 live skill / subagent 引用；新增 snapshot-aware resolver |
| AgentRunner / AgentRunStore | 原生 AGENT / LLM_CALL 执行、trace / output / usage 和取消入口 | 进程内固定线程池，没有完整 eval lease / execution token；max turns 当前可能写 COMPLETED，需映射真实终止原因 |
| WorkflowRunner / PgWorkflowRunStore | 持久 claim、heartbeat、owner-guarded write、journal 经验 | 复制模式不等于中断 Agent 栈可恢复；eval trial 独立建表和 fencing |
| PgCommandQueue / session ownership | worker 唤醒、run owner 路由与跨 pod cancel | ack 是 enqueue / handler 完成，不是 trial 完成；不是唯一执行凭证 |
| QualityDecisionGateway / DecisionProvider | 结构化 judge、route fingerprint、response 验证、timeout | 分离评估预算 / usage 语义，不耦合 publish policy；missing usage 不能借其零默认伪称 $0 |
| Dataset / FileService / object storage | 原始数据源、上传 / 下载、附件和 artifacts 存储 | Eval case / hidden reference / results 是独立领域，不写入 Agent 业务 output dataset |
| sandbox provider / snapshot | 生命周期、文件 staging 和容器后端 | 现有用户 sandbox 不保证 grader 无网络 / secret / host access；best-effort restore 不是严格 fixture reset |
| ReplayExecutor / benchmark BatchProcessor | trace 展示、局部样例 / 格式参考 | Replay 只调用 LLM span、内存线程池；benchmark 本地文件 resume 无 server ACL / 多实例一致性 |

源码定位见附录 B。复用以服务契约为边界，不把现有设计文档的历史实现状态当事实。

**理由：**新功能不是把 Comparison 改成上传 CSV，也不是在 Replay 外循环几百次；需要真实 Agent 执行与独立持久实验编排。

### 7.2 数据库与对象存储

**首版主存储选择 PostgreSQL。** 当前 messaging 初始化要求 `sys.store.mode=pg`；不为本功能承诺一个 server 当前不能启动的纯 Mongo 部署。新建 `EvaluationStore` 接口与 Pg 实现，按 PG 原子条件更新完成 lease / fencing；若未来正式支持其他 server mode，再补经验证 adapter。现有 trace / Dataset 仍可有独立后端，不因此假设跨系统有统一事务。

| 表（拟新增） | 核心字段 / 索引 | 作用 |
|---|---|---|
| `app.evaluations` | id、owner、agent_id、definition_revision、defaults、baseline_run_id、baseline_generation、archived_at | 项目与 CAS 管理 |
| `app.eval_dataset_revisions` / `app.eval_cases` | evaluation_id + revision 唯一；revision_id + case_id 唯一；content_hash、split、typed body_json | 不可变任务集合、分页与切片 |
| `app.eval_grader_revisions` / `app.eval_spec_revisions` | grader_id + revision；spec fingerprint 唯一或去重；body_json、image / code hash、test_receipt_json | 仪器与测量协议；grader 试测 receipt 随 revision 固化 |
| `app.eval_candidate_snapshots` | fingerprint、source revision、protected snapshot_json、dependency manifest | 被测对象和权限 references |
| `app.eval_runs` | id、evaluation_id、actor、request_key、request_hash、spec_id、snapshot_id、status、counts、deadline、budget、final_report_ref | evaluation + actor + request_key 唯一（重复 key 校验 request_hash）；项目时间索引 |
| `app.eval_trials` | run_id + case_id + repetition_index 唯一；status、execution result、owner、generation、lease_until、dispatch_token、child_run_id、output_ref | status + lease_until claim 索引；每条计划 trial durable 真源 |
| `app.eval_trial_attempts` | trial_id + attempt_no 唯一；dispatch / reconcile / error / usage timeline | 技术重试与未知执行诊断，不隐式覆盖 |
| `app.eval_grading_runs` / `app.eval_grader_results` | trial/output hash + suite + grading generation；grader identity、status、verdict、metrics、evidence refs | 固定输出重评分与 grader retries |
| `app.eval_capacity_reservations` / `app.eval_budget_ledger` | resource key、slot / generation、lease、reserved / settled amount、usage completeness | 全局容量和预算，不靠本地 semaphore |
| `app.eval_baseline_history` / `app.eval_comparison_reports` | evaluation、baseline generation、old/new run、reason；pair / statistics version | 可审计基线切换与对比 |
| `app.eval_artifact_refs` / `app.eval_audit_events` | owner scope、hash、object key、retention、access policy、event type | 对象引用和清理、敏感读取审计 |

调度索引字段独立 SQL columns，业务复杂字段可用 jsonb；Java entity / WebService DTO 必须具体类型，动态 body 以 schema 验证后的 String JSON text 序列化，不用 Object / BSON Document / Map<String,Object>。枚举 persistence 与 API view 分离，所有必需默认字段标 NotNull。

上传 raw、grader 包、fixture、output、trace、artifact 和报告导出进入受控 FileService / object storage。引用含 hash / size / MIME / ACL；大对象不内联到 Run JSON，也不依赖 worker 本地文件。object write 成功、DB commit 前 crash 时由 orphan GC 清理；DB reference 指向不存在对象时显示证据缺失，不默默重建。

schema 使用独立 `core-ai-server-db-migration` Flyway 模块的**下一个未占用版本**，实施时核对；不在本文猜迁移号。正常 claim / terminal / budget write 使用明确 CAS / PG 事务；对象存储、runtime、trace 跨域只提供状态协调，不承诺跨服务事务。

**理由：**数千 trial 的状态、租约和分页必须独立持久化；把整个结果数组 replace 回一个文档既有并发风险，也不利恢复和查询。

### 7.3 服务拆分与内部执行契约

| 组件（拟新增） | 责任 | 不应承担 |
|---|---|---|
| `EvaluationService` | 项目、ACL、defaults、baseline CAS | 不直接循环调用 Agent |
| `EvalDatasetImporter` | 流式验证、normalize、资源清单和 immutable revision | 不执行 grader / Agent |
| `EvalSnapshotBuilder` | 构建一次 candidate、依赖 / route / environment fingerprints | 不发布或 update live Agent |
| `EvalPreflightService` | 安全 capability、输入 / grader 覆盖、权限和预算验证 | 不把校验通过当任务成功 |
| `EvalScheduler` | durable claim、容量 / 公平性、heartbeats、cancel / reconcile | 不自行判 task correctness |
| `EvalAgentRunGateway` | snapshot-only execution、token 去重、状态 / completion、取消树 | 不按 live agentId 重取默认配置 |
| `EvalEnvironmentManager` | 独立 workspace、fixture reset、artifact capture、cleanup | 不默认允许业务网络和 credentials |
| `EvalGradingCoordinator` | 固定输出评分、script / model adapter、schema 验证 | 不覆盖 Agent output 或选择最好一次 |
| `EvalReportBuilder` / `EvalComparator` | 分母、指标、兼容性、paired统计与导出 | 不把 exploratory 对比宣称严格改善 |
| `EvalRecoveryJob` / `EvalRetentionJob` | lease 恢复、未知状态查询、对象留存清理 | 不盲目重放远程 Agent / 非幂等工具 |

`EvalAgentRunGateway.start(snapshotRef, caseInputRef, environmentRef, executionToken, budget, actorContext) -> executionHandle`；`getOutcome(handle)` 返回有固定 provenance 的 durable outcome；`cancel(handle)` 传播到全部必需工作并待确认。

需要对 AgentRunner 做受限增量接入：明确 draft / published 来源、detached config、case input / attachments、eval run/trial trace linkage、可恢复 dispatch token、结构化 terminationReason、budget options、required-async-work completion。保留原普通 run / workflow 行为并增加回归测试，不能顺手重写现有 messaging / session 子系统。

run-level fresh Agent execution不要求复用聊天 history；必要时核心运行上下文仍按 native Agent 执行语义建立。若当前 adapter 无法观察必需后台任务，则 preflight 拒绝该类能力，不以 root TURN_COMPLETE 代替完整完成。可选不影响 outcome 的后台任务必须显式 detach / 禁用，并说明测量范围。

**理由：**评估恢复的是实验计划与可观察执行，不是保存一条文本消息就能恢复整个 Agent 内部栈。

### 7.4 具体源码改动范围（实施提案）

- `core-ai-api/.../server/evaluation/`：typed WebService / view DTO。
- `core-ai-server/.../evaluation/`：models、Pg stores、importer、scheduler、execution / grading adapters、report / compare / recovery。
- `core-ai-server-db-migration/.../db/migration/`：新表、unique / claim 索引与权限 seed；迁移版本实施时确定。
- `core-ai-frontend/src/pages/experiments/evaluation/`、`src/api/evaluation.ts`：管理 / 向导 / report / compare。
- `Layout.tsx` / `App.tsx`：`/experiments/evaluations`、`/:id`、`/:id/runs/:runId`、`/:id/compare` 路由。
- `ServerApp` / store bindings / `PermissionCodes`：store 先于 service、runtime adapters 后于依赖绑定；新 action 显式初始化。
- run / sandbox / decision 的最小 capability seams：snapshot-aware dependency resolver、clock、artifact / grading isolation、termination / token；每个新增 seam 必有现有 runtime parity / 安全回归测试。

这些目录 / 类型为待实施设计，不代表当前存在；本文仅创建文档，不占用 Java 文件、迁移号或改动并行开发的工作区文件。

## 8. API、管理页与作者体验

### 8.1 管理权限与 API

产品在 Experiment 导航组新增 `Evaluations / 评估`，不是在 Agent 编辑器增加一个无法管理数据和历史的临时按钮。Agent 编辑器可以提供跳转入口，不复制评估状态。

首版使用平台 human admin 管理创建 / 数据上传 / script profile 和启动，评估资源有 owner / collaborator ACL；RBAC 与 service-level predicate 都检查，API user 不通过现有 action bypass 自动获得执行任意脚本的能力。后续普通 Agent 作者自助功能按受限 profile 单独授权。Run 以实际发起者的当前 Agent 权限执行，不继承上传者或管理员额外业务权限。

新增 action 名称为提案：`evaluation.manage / evaluation.run / evaluation.view / evaluation.oracle.view / evaluation.export`；上线需明确映射到现有 RBAC，而非假设增加注解就能限制 API user。

| API（提案） | 契约 | 理由 |
|---|---|---|
| `GET /api/evaluations` | 按 ACL 分页、agent/status/tag 过滤，显示 baseline 摘要 | 管理页入口 |
| `POST /api/evaluations` | name、agentId、description；返回 DRAFT 项目 | 数据 / 评分定义与执行分离 |
| `GET / PUT /api/evaluations/:id` | 设置 / ACL / default spec；PUT 需 expected revision | 防多人配置覆盖 |
| `POST /api/evaluations/:id/dataset-imports` | 使用受控上传 token，异步校验，返回 202 + importId | 不在 HTTP 内解压 / 批量验证大包 |
| `GET /api/evaluations/:id/dataset-imports/:importId` | import 状态、行级错误、READY revision | 不静默丢数据 |
| `GET /api/evaluations/:id/datasets` | 数据集 revision 列表（不返回 case body） | 选择 / 审计历史数据 |
| `GET /api/evaluations/:id/datasets/:revision/cases` | 分页预览，oracle 字段受独立权限保护 | 隐藏参考不因运行权限泄露 |
| `POST /api/evaluations/:id/grader-tests` | 评分器版本在固定正反样例上试测，不调用被测 Agent | 测量仪器先验证 |
| `POST /api/evaluations/:id/grader-suites` | 保存不可变 GraderSuiteRevision，附试测 receipt | 评分定义可复现 |
| `GET /api/evaluations/:id/grader-suites` | 评分套件 revision 列表 | 报告 / spec 选择 |
| `POST /api/evaluations/:id/spec-revisions` | dataset / suite / profile / runtime budgets，生成不可变 spec | 正式测量协议 |
| `GET /api/evaluations/:id/spec-revisions` | spec revision 列表 | 比较资格与历史 |
| `GET /api/evaluations/:id/runs/:runId/report` | 终态报告；未终态返回当前进度与 partial counts | 大报告不混入轮询状态端点 |
| `POST /api/evaluations/:id/runs` | Idempotency-Key、expectedEvaluationRevision、candidate {source:DRAFT/PUBLISHED/SNAPSHOT_REF,snapshotId?}、specRevision、purpose、label；202 + runId | durable 异步；UI 断开不停止 |
| `GET /api/evaluations/:id/runs` / `.../:runId` | 历史分页、状态、progress、spec / candidate 摘要、error counts | 历史可追踪 |
| `GET /api/evaluations/:id/runs/:runId/trials` | case/tag/verdict/error 过滤，游标分页 | 不一次返回全部 trace |
| `GET /api/evaluations/:id/runs/:runId/trials/:trialId` | 输出、评分、指标、脱敏 trace / artifact manifest | 逐项诊断 |
| `POST /api/evaluations/:id/runs/:runId/cancel` | 幂等，记录请求 actor；202，等待真实执行终止 | 取消不是直接删数据库状态 |
| `POST /api/evaluations/:id/runs/:runId/regrade` | 固定输出 + 新 suite，生成新 GradingRun | 不把评分变化当 Agent 改善 |
| `PUT /api/evaluations/:id/baseline` | runId、expectedGeneration、reason；检查 eligibility | baseline 可审计且不自动选高分 |
| `POST /api/evaluations/:id/comparisons` | baselineRunId、candidateRunId、统计方法版本，返回兼容性与报告 | 配对比较 / bridge 指引 |
| `POST /api/evaluations/:id/exports` | 显式范围和敏感字段选择；异步生成下载 token | 大报告不直接塞 JSON 响应 |
| `POST /api/evaluations/:id/archive` | 隐藏项目、保留历史，活跃 Run 先取消 / 结束 | 不以删除打断 worker；物理清理由保留政策执行 |

HTTP 错误：400 schema / 配置；403 权限；409 revision / idempotency / 不兼容；422 preflight 不满足或无评分判据；429 平台容量 / 额度；503 runtime / store 不可用。单 case FAIL 是结果，不是 HTTP error。API 使用 typed error `errorCode,message,details`，含 field / case / recommendedAction；引用私有资源时不枚举其内容。

**理由：**正式长运行不能维系在按钮 loading 和一次 HTTP 请求上；错误、取消和历史均需要可重入的资源契约。

### 8.2 页面结构与创建向导

**列表页**：名称、Agent、最近 Run 状态、baseline、成功率 / 覆盖率、创建人、最近运行时间；提供新建、复制配置、归档。没有 baseline 时显示“未建立基线”，不把最新 Run 自动当 baseline。

**详情页 tabs**：Overview、Dataset、Graders、Runs、Compare、Settings。Overview 显示当前默认 spec 与 baseline spec 是否相同；数据 / grader 有变更但未 bridge 时警示。

**创建 / 启动向导**：

1. Agent：有权限的 picker，选择 DRAFT / PUBLISHED，展示配置来源与能力；来源不存在 / 撤权不能继续。
2. 数据：上传 JSONL / ZIP / CSV，提供模板；逐行 preview、split 分布、判据覆盖和错误清单。
3. 评分：内置模板 / Python / Model，展示 required / optional、参数与 output schema；试测正反及异常样例。
4. 环境：OFFLINE_TEXT / ISOLATED_SANDBOX，展示不支持工具、fixture、reset 策略与隐藏 reference 边界。
5. 执行：并发、重复次数、预算；显示 `cases × repetitions`、费用未知项、smoke 建议，确认后创建正式 Run。

**理由：**原始流程的“新增时上传数据，点开始”保留，但把常见失败点提前暴露；默认模板让简单评估不需要写代码。

### 8.3 运行报告

- 顶部显示 Run 身份、Agent source / fingerprint、spec、状态、planned / started / measured / unmeasured，Agent 与 grader 两阶段进度。
- 主卡：成功率下界与缺失上界、覆盖率、any / all success@R、质量分布、延迟、已知成本和 cost_unknown；U>0 显示“不完整测量”。
- case 列表：case ID、tags、R 次结果、输出摘要、required graders、错误类别、tokens / time / cost；支持失败 / timeout / grader error / 不确定过滤。
- 详情分栏：input、actual、reference（独立权限）、grader evidence、公开 trace、artifacts。脚本和模型 feedback 纯文本，文件默认受控下载，不直接渲染活跃 HTML。
- “重跑这些样例”创建新的 exploratory subset Run；“查看新 reference”不覆盖历史；“标记 baseline”在不满足条件时给具体原因。
- Cancel 明确提示已启动任务可能仍在终止，不把状态立即涂绿；执行未知项保留 run / trial token 供管理员 reconcile。

**理由：**用户调优需要定位“哪一题、哪一步、哪一种失败”，而不是只看“80 分”。

### 8.4 baseline 比较与持续演进

Compare 先显示条件差异，再显示 Δ：质量、覆盖率、回归 case、cost / latency；两个 Run 不是严格兼容时必须突出标签，不用绿色箭头暗示进步。

推荐产品闭环：`Baseline -> 改 Agent -> 同 spec 新 Run -> 看回退 / 证据 -> 接受或继续调优 -> 明确切换 baseline`。发现新错误可从结果创建新 case 草稿，经人工确认输入 / reference / split 后提交新数据集版本，并对旧、新候选 bridge run。

支持 A/A 操作：同一 snapshot 与 EvalSpec 再跑一次，估计随机噪声与 grader / platform 稳定性；默认不从评分最大的一次选 baseline。Eval 结果不自动发布 Agent，发布仍通过原有审批 / 门禁。

**理由：**持续演进不仅是分数提高，也包括维护测试集、测量噪声与避免把修好的样例同时当独立验证。

## 9. 安全、预算、运维与可观测性

### 9.1 信任边界与风险控制

| 风险 | 必需控制 | 初衷与理由 |
|---|---|---|
| Agent 偷看 reference / hidden tests | 输入 allowlist、独立对象授权、Agent / grader 环境隔离；不挂整个 eval ZIP | 否则得到的是答题泄漏能力而不是任务能力 |
| Python grader 执行任意系统操作 | §4.5 强隔离、无网络 / secret、固定镜像、资源限制；未满足 capability 不开放 | 不能把管理页变成服务器远程执行入口 |
| prompt injection 欺骗 judge | actual / trace 作为不可信数据；可信 rubric / schema 固定、judge 无工具；对抗校准 | 不能声称加一句“忽略输入指令”即可防住注入 |
| Agent 调用真实支付 / 发消息 / 生产写库 | profile allowlist；首版禁用真实业务变更，test namespace + reset 审批后扩展 | 运行评估不是外部业务授权 |
| 跨 case / Run 污染 | fresh session / workspace，memory 禁写、fixture reset、缓存策略版本化 | 不能靠上一题产生的答案提高分数 |
| 上传 ZIP 越界 / 解压炸弹 / script 依赖投毒 | 路径归一化、拒 symlink / traversal、大小 / 数量 / 压缩比限制、无任意 pip install | 文件导入是高风险入口 |
| private Agent / Dataset / credential 访问扩大 | 当前 actor 鉴权、resource ACL、oracle 独立读取权限；秘密只保存受控 reference | admin 管理评估不等于越权执行任何业务资源 |
| 并发 / 子任务 / judge 成本失控 | §5.3 全局 capacity、资源预留、run / trial caps、背压和取消树 | 避免内层调用绕过顶层并发与预算 |
| HTML output / artifact XSS | 输出纯文本、sandboxed preview、短期签名下载、Content-Disposition 和 MIME 校验 | 评估结果仍是非可信内容 |
| score 伪造 / late completion 覆盖 | 结果 schema、服务端身份绑定、trial generation fencing、终态不可覆盖 | grader 可以定义规则，不能修改 Run 身份和历史 |

**发布质量门禁与 eval 的安全关系：**运行草稿不依赖语义发布 gate PASS，但必须通过 execution authorization 和 profile 安全 preflight。脚本 grader 有独立 capability，不允许用 builtin code tool 绕过其限制。

### 9.2 留存、隐私与审计

提案留存：spec / snapshot / baseline receipt / aggregate report 随项目保留；详细 output / trace 默认 90 天，非 baseline raw artifacts 默认 30 天。被指定 baseline 的必要 evidence 可延长至项目归档后保留期，需管理员确认容量；raw 丢失后标“仅摘要可用”，不能再声称可复核 / regrade。过期数据清理需要实装后台任务与对象引用检查，不能只设置 `expires_at` 后称已清理。

- 敏感 input / output / reference 存储加密并按 ACL 读取，普通日志只记 ID / hash / 统计；对 trace / tool arguments 做脱敏。
- audit 记录创建、数据导入 / 排除、grader 修改 / 测试、Run start / cancel / regrade、baseline 切换、oracle 读取和 export。
- 模型 grader 会接收 input / output / reference；启动前展示数据去向和授权 model route，private data 不发送给未经批准的 provider。
- 删除 / 归档不自动抹掉运行记录；法律 / 隐私强制删除可以清除 raw 内容并留下不可逆脱敏审计摘要，不把不存在的 evidence 装成仍可读取。

**理由：**为了可复核无限保存敏感 trace 并不合理；摘要、可复核证据和可重放输入是不同保留等级。

### 9.3 可观测性与恢复预案

指标包括 Run / trial status、Agent vs grader latency、queue wait、coverage / error、capacity occupancy、lease expiry、duplicate dispatch suppression、OUTCOME_UNKNOWN、sandbox cleanup failures、budget reservation / actual usage、cost_unknown、route drift、artifact size 和 import errors。metric label 不放 case ID / user ID / prompt；这些进入受控结构化日志。

恢复操作：

1. 队列停滞 / worker 故障：停止新 claim，按 lease generation 接管；先查实际 session 终态，不盲目重跑。
2. judge provider 故障：保存 outputs、暂停评分或完成为 errors；恢复后固定 route / suite 做 regrade；不让 Agent 重新跑到碰巧过关。
3. runtime / sandbox 故障：熔断对应 profile，未 dispatch 的保持等待，已 dispatch reconcile；逐项披露 missing。
4. 发现脚本 / 工具风险：retire profile / grader、停止新执行、取消活跃任务、保留审计；不静默修改已有分数。
5. 存储 / 容量不足：暂停 import / dispatch，保护既有 outputs；不能为了释放空间删除 baseline evidence 而不提示。

所有恢复仍需权限和审计，暂停 / 取消的真实效果取决于 runtime cancel 确认；不能通过把数据库状态改为 CANCELLED 假装任务已停止。

**理由：**评估报告在有故障时仍需可信；运维必须能停下来并解释缺失，不能靠再跑一次覆盖问题。

## 10. 验收、实施计划与交付状态

### 10.1 功能验收矩阵

| 需求 / 主题 | 必测场景 | 预期 |
|---|---|---|
| U1 管理页 | Experiment 导航、列表 / 详情 / 新建、ACL | 有独立评估页，未授权不访问 |
| U2 Agent 选择 | DRAFT / PUBLISHED、managed prompt、中途编辑 / 删除 / 撤权 | 一次 snapshot；编辑不改变本 Run，撤权停止后续任务 |
| U3 数据导入 | JSONL / CSV / ZIP、中文 / 英文、重复 ID、缺 reference 但有 rubric、缺所有判据 | 有合法评分判据才 READY；行级错误明确，无静默丢行 |
| U4 脚本 | PASS / FAIL、输出多 JSON、越界 / NaN、非零 exit、timeout、读取 secret / 网络 | 合法结果接收；技术错误独立，越权被隔离层拒绝 |
| U5 并发 | 用户 1 / 2 / 10、多 Run / actor、公平排队、subagent、grader 慢 | active trial 不超过有效容量，子任务 / judge 有预算，背压生效 |
| U6 全集报告 | 全通过、目标失败、平台 / grader error、取消 / 未开始 | `S+F+U=P`；全量覆盖 / 缺失可见，分母不丢项 |
| U7 baseline | 同 spec 新 candidate、换 dataset / grader / 并发、bridge、CAS 切换 | 严格 / 探索 / 不兼容区分，未授权不切 baseline |
| 轨迹 / outcome | Agent 声称写成功但文件不存在、合法不同工具路径 | 依据 artifact / outcome FAIL；不因轨迹不同自动判错 |
| 重复试验 | R=1 / 3、一次成功其他失败、固定输出 regrade | any / all / mean 不混用，regrade 不新增 Agent 样本 |
| 模型评分 | 缺答案、非法标签、低置信、judge 注入、route drift | ERROR / INDETERMINATE，可见 coverage；无静默放行 |
| 结果导出 | 分页、权限、敏感 reference、artifact path | 结果与输入分离，未授权不导出 oracle |
| 非目标 | Eval 后自动 publish / 写生产 / 执行任意 MCP | 不发生，需独立流程 |

### 10.2 系统与统计验证

- **序列化 / 启动**：具体类型、entity / view enum 分离、service bind 顺序；评估 schema / store 只在 PG 模式初始化（`sys.store.mode=pg`），不新增 eval Mongo collection，也不承诺纯 Mongo server 可用。
- **隔离**：Agent 无法读取 reference / graders / 其他 case；Python 无 secret / host FS / network；fresh workspace + memory + dataset policy reset；artifacts traversal / symlink 被拒。
- **一致性**：Run snapshot 改稿不漂移；same Idempotency-Key 不双建；trial CAS / fencing；late completion 不覆盖终态；baseline CAS 不丢更新。
- **故障注入**：dispatch 前 crash / dispatch 后断线 / Agent 完成前 lease expiry / output 持久化后 grader crash / FINALIZING crash / cancel race，状态和账单可解释，无盲目重复执行。
- **统计单测**：S=6,F=2,U=2 时下界 60%、上界 80%、coverage 80%、measured rate 75%；权重 / zero denominator / slice overlap / NOT_APPLICABLE；case-cluster bootstrap 固定 seed。
- **A/A**：固定 candidate / spec 重跑，观察目标与 grader 噪声；不将无显著差异视为等价证明，基于代表性样本校准 judge。
- **性能 / 容量**：1,000 cases × 3 trials 分页管理，背压 / 全局并发 / object storage 压测；API 不返回全量 raw trace，进度不依赖浏览器在线。
- **成本**：父子调用 / judge / runtime retry 纳入 usage；缺失价格标 unknown，预算预留 / 结算与取消后晚到账一致。
- **UI E2E**：必须实际驾驶内置浏览器完成导入→smoke→start→取消 / 完成→报告→baseline→比较；仅前端 build 不算视觉验收。

**理由：**一个“能跑 10 条”的 demo 不足以提供可信 baseline；数据、执行、评分、分母和故障恢复都需要验证。

### 10.3 实施分阶段与当前状态

| 阶段 | 交付 | 对应验证 / 开放条件 |
|---|---|---|
| P0 设计确认 | 关闭 §11 的范围 / 权限 / profile / grader / 默认参数决策 | 已按建议默认进入实施；Q 项逐条见 §11.2，未逐项获得用户确认 |
| P1 契约与存储 | Evaluation / Dataset / Grader / Run / Trial 模型，导入，typed DTO，权限、指纹与幂等 | **已实施（子集）**：PG schema V48、typed API、JSONL 导入、指纹、幂等 run、ACL；无 Mongo / ZIP / CSV |
| P2 最小端到端 | OFFLINE_TEXT，独立 snapshot runner，durable 调度，确定性 grader，报告 | **已实施（子集）**：OFFLINE_TEXT、冻结 snapshot、PG trial 调度、恢复 / 取消、内置确定性 grader、完整分母报告；无 Python / model grader、无 artifact capture |
| P3 工具与评分 | ISOLATED_SANDBOX，Python grader，artifacts、model grader 和受控 regrade | 未实施；suite 校验明确拒绝 PYTHON / MODEL / ISOLATED_SANDBOX |
| P4 产品闭环 | Experiment 管理页、dataset / grader 向导、baseline / compare / exports | **部分实施**：列表 / 详情 / run report、baseline、paired compare；无 export / A/A 快捷操作 / bridge 向导 |
| P5 dev pilot | 小规模受控真实 Agent、代表性 task bank、容量 / cost / failure 演练 | 未开始；未执行真实 Agent 评估 |

**当前交付状态：P1–P2 后端子集与最小 P4 页面已实现，已在 dev 2.0.332 完成真实 Agent E2E（导入、评分、报告、baseline、compare、cancel）；UAT / prod 未部署。** 源码实现与验证记录见附录 D。Python 脚本、模型评分、ISOLATED_SANDBOX、导出、真实配额 / 成本和容量压测仍未交付，不能宣称完整功能。

首版完整目标包含 Python 脚本；如果隔离后端暂未满足条件，应把对应 profile 明确标“不可用 / 待完成”，不能删掉安全要求然后宣称脚本功能交付。后台 recovery、raw retention 清理和 budget enforcement 也是产品完整性的必要项，不仅记录一个字段。

**理由：**每阶段都包含验证，并将危险功能 capability gating；不把计划写成已交付事实。

## 11. 关键决策与未决事项

### 11.1 ADR 摘要

所有 ADR 当前为 `PROPOSED`，用户尚未批准。正文负责行为定义，本表集中记录备选 / 取舍；Review 处理引用 ADR / 章节，不复制第二份规范。

| ID | 决策 | 初衷 / 理由 | 未选备选及原因 | 代价 / 对应正文 |
|---|---|---|---|---|
| ADR-01 | Evaluation 项目 + immutable Spec / Candidate / Run 分离 | 确保调优只改变候选，不混淆仪器变化 | 只存 agentId + dataset 名：中途变化无法归因 | 更多版本实体；§3 |
| ADR-02 | expected output 可选，但可执行成功判据必需 | 开放回答和实际任务可能无唯一文本 | 每题强制字符串答案：测错任务；无判据：不叫正式 eval | rubric / assertion / grader 设计；§4 |
| ADR-03 | 数据行引用 grader，脚本独立版本化，结果平台生成 | 去重、审核、冻结和权限边界清晰 | 每行复制脚本 / 上传预填 score：仪器混杂且不可审计 | manifest / grader UI；§4 |
| ADR-04 | 评分机制与证据目标分开 | 脚本 / 模型均可测答案、轨迹、实际 outcome | 只让 LLM 判最终文本：漏真实工具任务；严格固定轨迹：拒合法路径 | 多维 grader adapter；§4、§6 |
| ADR-05 | Fresh trial + explicit execution profiles | 隔离副作用和跨题状态，固定 reset | 普通 session 批量 fan-out：记忆 / 文件 / 数据集污染 | sandbox / memory / dependency seams；§3、§5 |
| ADR-06 | 固定候选但仍每次验证当前权限 | 可比较且不让快照变成永久越权凭据 | 只固定 ID：漂移；冻结授权：撤权无效 | snapshot resolver / auth；§3、§5 |
| ADR-07 | PG durable trials + bounded dual-stage concurrency | 重启恢复、多实例 fencing、Agent / grader 背压 | Browser Promise.all / Replay thread pool：不可恢复 | tables / scheduler / budget ledger；§5、§7 |
| ADR-08 | 明确 OUTCOME_UNKNOWN，不自动重跑已 dispatch trial | 避免重复外部操作或幸运抽样替换失败 | 超时后无条件重试：副作用与统计失真 | runtime token / reconcile 接入；§5 |
| ADR-09 | Python grader 独立隔离，首版 K8s unbound Job / 固定依赖 | 满足用户脚本目标，同时限制不可信代码；有 durable Job 生命周期 | 服务器内 Python / bound sandbox：secret / 网络风险；多 provider 同时支持：范围过大 | staging / wrapper / result collector；§4.5–§4.6 |
| ADR-10 | required 全通过、缺失分母显式、无万能总分 | 不能以好表达抵消错误动作，也不能隐藏 grader errors | 加权总分 / 丢弃错误：分数虚高 | coverage / 下界和多维 UI；§6 |
| ADR-11 | 默认重复 3 次，报告 mean / any / all | 观察随机性与稳定性 | 默认只跑一次 / 只选最佳：不能测可靠性 | 费用乘 R；§5、§6 |
| ADR-12 | baseline 显式选定、同条件配对与 bridge | 数据 / grader 改动不能直接归因于 Agent | 最新 / 最高分自动 baseline：selection bias | eligibility / compare UI；§6、§8 |
| ADR-13 | draft 可测，eval 不自动 publish | 支持诊断坏候选，任务效果不等于发布准入 | 强制 gate PASS 才测 / 分高自动发布：职责混乱 | execution safety 独立；§2、§9 |
| ADR-14 | 首版 AGENT + 原生 LLM_CALL；单轮 user input | 避免误用模板 / capability，先覆盖常见回归 | 任意 multi-turn simulator / Workflow eval：仿真范围过大 | 多轮另 schema / phase；§3、§4 |
| ADR-15 | 无真实业务写入默认权限，集成 profile 延后 | 离线运行不能自动授权发送 / 支付 | 任意 API / MCP 直接执行：污染生产且不可重置 | 能测范围受限，需后续 test adapters；§3、§9 |

### 11.2 决策状态台账

| ID | 待确认事项 | 当前建议 | 状态 / 授权来源 | 影响章节 |
|---|---|---|---|---|
| Q1 | 管理页首版是否仅 human admin，或普通 Agent 作者自助 | human admin pilot + Evaluation ACL，普通作者后续受限 profile | AUTHORIZED_DEFAULT；新增 evaluation.* 权限码，默认只授予 admin | §8、§9 |
| Q2 | 首版是否同意单轮输入 + Agent 内部多步，不做多轮模拟 | 同意此边界；保留 AGENT / LLM_CALL 原生类型 | AUTHORIZED_DEFAULT；首轮实施 OPEN_TEXT 单轮 AGENT | §1、§3、§4 |
| Q3 | Python grader 是否为必须完整交付；首个后端 / 依赖 | 必须，K8s 专用 unbound Job、标准库先行；Docker adapter 后续，隔离失败不能开放 | OPEN | §4.5–§4.6、§10 |
| Q4 | Agent 配置 API / MCP / 网络 / mutable dataset 时怎么办 | 不静默去工具；未具备受控测试 adapter 则 preflight 拒绝 | AUTHORIZED_DEFAULT；首轮 preflight 拒绝带 tools / skills / subagents / dataset / sandbox 的候选 | §3.2、§5.2 |
| Q5 | 初始 repetitions / concurrency / trial 预算与费用归属 | R=3、并发=2，其余见 §5.3；按实际 caller 计资源，judge 单列治理成本 | AUTHORIZED_DEFAULT；R=3、并发=2；费用结算仍待产品确认 | §5.3、§6 |
| Q6 | 主成功条件是 required 全通过，还是业务专用组合 | 默认全通过；不能任意平均抵消；新策略需版本化 | AUTHORIZED_DEFAULT；首轮固定 ALL_APPLICABLE_REQUIRED_PASS | §4.4、§6 |
| Q7 | baseline 的完整覆盖 / 不确定结果要求是否过严 | 正式基线严格；有错误保留 exploratory 报告，支持 regrade / A/A | AUTHORIZED_DEFAULT；严格 eligibility 按正文 | §6.4 |
| Q8 | 模型 backend 无可固定版本时是否允许现实基线与对比 | 完整有效 Run 经用户确认可选现实参考基线；对比 exploratory，不标完全可复现 | AUTHORIZED_DEFAULT；无版本 pin 时标 MODEL_VERSION_UNPINNED，基线需求用户确认 | §3.2、§6.4 |
| Q9 | 首版 JSONL / ZIP + 简单 CSV 的导入组合 | 采用；结果导出与输入分离，不允许每 CSV 行任意代码 | AUTHORIZED_DEFAULT（子集）；首轮只实现纯 JSONL，ZIP / CSV 后续 | §4 |
| Q10 | raw artifacts / baseline evidence 留存及 oracle 权限 | §9.2 默认提案；baseline 必需证据保留期单独审批 | OPEN | §8、§9 |
| Q11 | saved AgentVersion 是否在首版作为第三候选来源 | 核心 DRAFT / PUBLISHED 先行；可加 VERSION，但 Run 仍冻结内容，不只存 versionId | AUTHORIZED_DEFAULT；首轮只有 DRAFT / PUBLISHED | §3.2、§7.1 |
| Q12 | 是否需要自动晋升 / CI 质量门禁 | 本次不做；将来复用 reports / eligibility，发布仍独立授权 | AUTHORIZED_DEFAULT；不做自动晋升 | §1.4、§8.4 |

OPEN 仅表示未获得用户决定，不表示缺少建议。用户若授权按默认实施，逐项记录 `AUTHORIZED_DEFAULT` 与真实落地差异；不得把 AI Review 或 implementation 选择写成用户已永久批准。

## 附录 A：用户原始需求与文档治理

### A.1 用户原始需求（2026-11-21，原文保留）

> 那你基于你的文档结构写一份techdesign：为core-ai-server新建一个‘评估’功能，放在experiment，也是个管理页，新增的时候，用户选择要eval的agent并上传一个eval的数据集，agent eval的数据集应该有哪些字段需要你来设计（至少应该有input和预期output以及eval的脚本和接受脚本输出的eval结果？），然后用户点击开始eval，就按用户设置的并发来调用agent得到结果，然后eval，最后得到一个整个数据集结果。我说的流程可能是不足的，需要你根据业界的agent eval的流程补充设计，只要保留我的目标是eval一个agent，这样我每次调优agent才会有baseline才能持续演进，根据这个原始需求从第一性原理触发帮我设计，即使推翻我的流程也可以。

### A.2 本次采用的结构约束

本设计采用“目标 → 总体流程 → 核心模型 → 数据 / 评分规格 → 执行协议 → 指标 / baseline → 架构 → API / UX → 安全运维 → 验收状态 → 决策 → 历史附录”。原始需求 / 来源 / Review / 修改 / 验证保留同一文件，但不把当前例外和未完成项埋到历史中。

### A.3 维护规则

1. 原始需求原文不覆盖；澄清 / 新增需求使用稳定 ID，映射对应正文与验收。
2. 正文为当前设计基线；未实施的目标明确标注，实施变更更新对应正文与 ADR，不只追加日志。
3. 行业原则、源码事实、设计提案、测试实测分别标注；本轮的提案默认值不能写成实测能力。
4. 每个主题有唯一权威定义：字段 / grader ABI 在 §4，预算在 §5.3，分母 / eligibility 在 §6，权限在 §8–§9；其他章节引用。
5. Q / ADR / Review 相互链接；状态区分 PROPOSED、OPEN、AUTHORIZED_DEFAULT、CONFIRMED、SUPERSEDED、DEFERRED，不混淆 AI 评审与用户批准。
6. Review 记录意见、处理与章节；修改记录记版本与原因；实施验证记录日期、环境、代码 / 镜像、证据和限制，不能替代正文状态。
7. 数据集 / grader / harness / snapshot / report 版本不可变；文档更新不重写历史 Run。
8. 不创建业务实现、commit、部署或真实评估，直到用户明确授权。

## 附录 B：行业依据与设计时源码核对

### B.1 行业依据及采用方式

资料获取日期：2026-11-21。以下是官方实践 / 产品文档，不是统一行业标准；采用测量原则，不引入其平台依赖，不照抄 SDK / 产品生命周期。没有实测第三方 SDK。

| 来源 | 采用原则 | 不照搬 / 局限 | 对应设计 |
|---|---|---|---|
| [S1] Anthropic Agent evals | task / trial / harness / grader / outcome；fresh trial；实际结果优先；重复可靠性；持续维护 case | 未把 outcome 只简化为最终答案，也未采用所有示例 grader 或宣称离线能替代生产反馈 | §1–§6、§8.4 |
| [S2–S4] LangSmith | inputs / references 分离，dataset versions、experiment metadata / repetitions、baseline per-case deltas | versioned dataset 不自动冻结代码 / tool / judge；response cache 不用于新 trial 随机性测量 | §3、§4、§6 |
| [S5–S6] OpenAI eval / trace grading | 数据 + grader，工具 / handoff / policy 轨迹检查，代码 / 模型评分 | 只采用概念；其平台可用性 / 弃用不成为 core-ai 依赖；input/reference 列必须显式隔离 | §4、§8 |
| [S7] Inspect sandboxing | 每 sample 环境、fixture、cleanup、image digest、非幂等操作不能盲重试 | 容器存在不等于 harness / scorer 都在容器内；network config 需验证真实 enforcement | §5、§9 |
| [S8] Inspect scoring policy | 目标失败、scorer error、unscored 分开，trial-level coverage 保留 | 不只显示排除 errors 后的 conditional accuracy；不允许 epoch reduction 抹掉缺失 | §3.4、§6.1 |
| [S9] MLflow paired evaluation comparison | 配对 difference / CI、小样本限制、A/A 噪声、多重比较谨慎 | 其 API 为 experimental；不能静默丢掉 duplicate keys / unmatched / errors；我们先按 case 聚合重复 trial | §6.5、§8.4 |

评分器机制（code / model / human）与证据目标（response / trajectory / outcome）是两个维度。S1 的原文明确任务可以多 grader、部分信用可用于诊断；本文保留多维 score，但任务成功默认 required 全通过，避免以软质量补偿硬失败。具体阈值和 promotion 策略需业务确认。

### B.2 设计时源码事实与接入约束

核对范围：2026-11-21 的工作区源码。工作区存在并行开发，实施前必须重核路径 / 行号与最新行为。以下仅记录当前事实，不代表目标设计已经实现。

| ID | 当前事实 | 源码证据（工作区相对路径:行号） | 设计约束 |
|---|---|---|---|
| C01 | Experiment 子页包含 Playground / Replay / Memory / Agent Comparison，未有 Evaluation | `core-ai-frontend/src/components/Layout.tsx:128`；`core-ai-frontend/src/App.tsx:278` | 新增独立管理页与路由 / permission |
| C02 | Agent saved version 是可更新配置，非不可变版本 | `core-ai-server/src/main/java/ai/core/server/comparison/AgentVersionService.java:55`；`:129` | 必须冻结实际内容，不只存 versionId |
| C03 | AgentRunBuilder 优先 publishedConfig；top-level owner 执行权限可返回 editable | `core-ai-server/src/main/java/ai/core/server/run/AgentRunBuilder.java:153`；`core-ai-server/src/main/java/ai/core/server/agent/AgentDependencyAccessPolicy.java:105` | 显式 DRAFT / PUBLISHED 选择，detached config，不能默认 run API 测 draft |
| C04 | 已有 config copy 与 workflow transient snapshot 执行模式 | `core-ai-server/src/main/java/ai/core/server/agent/AgentExecutableConfigFactory.java:9`；`core-ai-server/src/main/java/ai/core/server/workflow/MongoAgentRunGateway.java:51`；`:98` | 复用 snapshot 接入理念；不 publish live Agent |
| C05 | Subagent / skill assembly 仍读取现行依赖 | `core-ai-server/src/main/java/ai/core/server/agent/SubAgentAssembler.java:95`；`core-ai-server/src/main/java/ai/core/server/skill/SkillToolAssembler.java:29` | 根快照不足以冻结整个 candidate；新增 snapshot-aware resolver |
| C06 | AgentRunner 固定 10 workers、进程内提交；max turns 可能记 COMPLETED | `core-ai-server/src/main/java/ai/core/server/run/AgentRunner.java:49`；`:134`；`:235` | 用户并发由 eval coordinator 管；termination reason 单独映射 |
| C07 | stale-run cleanup 已存在，会根据 trace reconcile / fail，不续跑中断 Agent | `core-ai-server/src/main/java/ai/core/server/trace/maintenance/StaleRunCleanupService.java:43`；`:63` | 不把旧 gateway 注释“no reaper”当事实；恢复 trial 调度，不承诺 Agent 栈 continuation |
| C08 | Workflow store 有条件 claim / lease / owner terminal write | `core-ai-server/src/main/java/ai/core/server/workflow/PgWorkflowRunStore.java:165`；`:177`；`:189` | 可参考 durable lease 模式；新 trial generation fencing 仍需实现 |
| C09 | PG command ack 不表示 Agent 执行完成，SSE replay 有界且在 pod 内存 | `core-ai-server/src/main/java/ai/core/server/messaging/InProcessCommandHandler.java:164`；`core-ai-server/src/main/java/ai/core/server/web/sse/SessionEventHub.java:109` | 不能用消息 ack / UI stream 作为 trial outcome |
| C10 | 核心 TaskRunCoordinator 有必需后台工作概念，server 未见完整 task observer 接入 | `core-ai/src/main/java/ai/core/session/lifecycle/TaskRunCoordinator.java:267` | 完整任务完成需明确 adapter；不支持的 async capability 暂拒 |
| C11 | Dataset API 没有通用 JSONL / CSV bulk import；record write 受业务 schema / session provenance 约束 | `core-ai-api/src/main/java/ai/core/api/server/DatasetWebService.java:22`；`core-ai-server/src/main/java/ai/core/server/dataset/DatasetRecordWriteRules.java:43`；`core-ai-server-db-migration/src/main/resources/db/migration/V1__dataset.sql:46` | 专用 eval importer / case store，不假装已有导入 |
| C12 | FileService / ObjectStorage 可存文件，但普通下载不自动验证 eval owner；Azure upload SAS 范围不是单 key | `core-ai-server/src/main/java/ai/core/server/file/FileService.java:91`；`core-ai-server/src/main/java/ai/core/server/file/FileDownloadController.java:17`；`core-ai-server/src/main/java/ai/core/server/blob/AzureBlobSasService.java:65` | 新上传 / 下载 token 必须绑定 eval scope 与 object key，不能默认继承 ACL |
| C13 | trigger DTO 有 attachments，但 service 没传；LLM_CALL 只处理首附件 | `core-ai-api/src/main/java/ai/core/api/server/run/TriggerRunRequest.java:10`；`core-ai-server/src/main/java/ai/core/server/run/AgentRunService.java:59`；`core-ai-server/src/main/java/ai/core/server/run/LLMCallExecutor.java:103` | eval gateway 正确转发 / 类型 capability preflight；不能声称现有 trigger 已支持全附件 |
| C14 | session create 有 memory injection；run 可写 output dataset | `core-ai-server/src/main/java/ai/core/server/session/AgentSessionManager.java:244`；`core-ai-server/src/main/java/ai/core/server/run/AgentRunBuilder.java:374` | 隔离需覆盖 session memory 和业务输出，不只 enableMemory=false |
| C15 | Python execute 有 timeout、合并 stdout/stderr，非零退出仍返回 completed；env 继承父进程、支持 bound hub | `core-ai-sandbox-runtime/main.go:697`；`:743`；`:1201`；`core-ai-sandbox-runtime/hubproxy.go:39` | 新 grader execute contract 要独立 env / exit code / 输出 schema，不沿用 textual completed |
| C16 | Docker 有 CPU / memory / capability 限制，但 named network + 整 workspaceBase mount；K8s networkEnabled 主要是 label | `core-ai-server/src/main/java/ai/core/server/sandbox/docker/DockerSandboxProvider.java:197`；`core-ai-server/src/main/java/ai/core/server/sandbox/kubernetes/KubernetesPodSpecBuilder.java:71` | 无网 / 私有 FS / grader unbound 需要真实 enforcement 验收 |
| C17 | DecisionProvider 已提供 route fingerprint / usage，质量 adapter 已严格验证 | `core-ai-server/src/main/java/ai/core/server/gateway/GatewayDecisionProvider.java:48`；`core-ai-server/src/main/java/ai/core/server/quality/QualityDecisionGateway.java:45`；`:81` | 复用 primitives；评估 usage completeness / budgets 另实现 |
| C18 | LLM spans 有 tokens / estimated cost，决策内部调用不自然经过 HTTP audit；run row 不包含所有成本 | `core-ai/src/main/java/ai/core/telemetry/LLMTracer.java:165`；`core-ai-server/src/main/java/ai/core/server/trace/service/ModelPricingService.java:17`；`core-ai-server/src/main/java/ai/core/server/gateway/GatewayDecisionController.java:39`；`core-ai-server/src/main/java/ai/core/server/domain/AgentRun.java:47` | trace lineage / usage collector，缺失成本标 unknown |
| C19 | server 当前 messaging 明确要求 sys.store.mode=pg；Dataset / trace 仍有独立 backend 模式 | `core-ai-server/src/main/java/ai/core/server/MessagingInfrastructureModule.java:36`；`core-ai-server/src/main/java/ai/core/server/DatasetModule.java:45` | 首版 eval PG store；不能从其他旧设计推断 server 仍支持纯 Mongo 启动 |
| C20 | Experiment view 与 billed actions 分开；API user 有 action RBAC bypass | `core-ai-server/src/main/java/ai/core/server/rbac/PermissionCodes.java:56`；`core-ai-server/src/main/java/ai/core/server/apiuser/PermissionService.java:35` | human admin predicate + resource ACL + run/grade quota，不只 nav permission |
| C21 | Replay 是直接 completionStream / 固定内存执行；skill eval 只验证 manifest 不执行 | `core-ai-server/src/main/java/ai/core/server/replay/service/ReplayExecutor.java:49`；`core-ai-server/src/main/java/ai/core/server/skill/quality/SkillEvalDatasetValidator.java:16` | 不复用为正式 Agent 批量评估 engine |
| C22 | Sandbox filesystem snapshot 是 best-effort，恢复可退化为空环境 | `core-ai-server/src/main/java/ai/core/server/sandbox/snapshot/SandboxSnapshotService.java:18`；`core-ai-server/src/main/java/ai/core/server/sandbox/SandboxService.java:239` | 正式 fixture reset 必须严格成功，不以空目录假成功 |

core-ng 的 DTO / Mongo entity 类型约束与 binding / collection 规范同时依据本仓库 `AGENTS.md`，属于实现要求；内部已有动态 Map 不代表新 WebService DTO 可以任意使用 Object。

### B.3 参考资料

- [S1] [Anthropic — Demystifying evals for AI agents](https://www.anthropic.com/research/demystifying-evals-for-ai-agents)，2026-01-09：task / trial / harness / outcome、重复、隔离、grader 校准和持续评估。
- [S2] [LangSmith — Evaluation concepts](https://docs.langchain.com/langsmith/evaluation-concepts)：数据 / reference、experiment、评估类型和 split。
- [S3] [LangSmith — Manage datasets](https://docs.langchain.com/langsmith/manage-datasets)；[Experiment configuration](https://docs.langchain.com/langsmith/experiment-configuration)：dataset version、repetitions / concurrency / cache。
- [S4] [LangSmith — Analyze an experiment](https://docs.langchain.com/langsmith/analyze-an-experiment)：baseline、逐 case 结果、cost / latency / tokens。
- [S5] [OpenAI — Evaluate agent workflows](https://developers.openai.com/api/docs/guides/agent-evals)：轨迹、tool / handoff / policy 评分。
- [S6] [OpenAI — Getting started with datasets](https://developers.openai.com/api/docs/guides/evaluation-getting-started)：数据、代码 / 模型 graders；仅用其方法概念，不依赖产品生命周期。
- [S7] [Inspect AI — Sandboxing](https://inspect.aisi.org.uk/sandboxing.html)：per-sample sandbox、network enforcement、image pin、non-idempotent retry。
- [S8] [Inspect AI — Scoring Policy](https://inspect.aisi.org.uk/scoring-policy.html)：verdict / scoring error / unscored 与 denominator / coverage。
- [S9] [MLflow — Comparing Evaluation Runs](https://mlflow.org/docs/latest/genai/eval-monitor/comparing-evaluation-runs/)：配对统计、CI、A/A、错误 / duplicate matching 与小样本限制。

## 附录 C：Review 与修改历史

### C.1 用户 Review 与授权

| Review ID | 日期 | Reviewer | 意见 / 范围 | 处理 / 影响章节 | 状态 |
|---|---|---|---|---|---|
| R-U-01 | 2026-11-21 | 用户 | 原始需求见附录 A；授权从第一性原理调整流程，目标为评估一个 Agent 并支持 baseline 演进 | 完整技术设计，需求 U1–U8；未进入实施 | REQUEST_RECEIVED；待用户 Review |

**实施授权：无。** 用户提出设计任务不等同于批准本方案、预算、权限或外部调用。

### C.2 AI 技术评审（非用户批准）

本节记录独立 AI 评审发现、处理位置和剩余风险；AI 结论不构成实施授权。评审完成前不得写 Approved。

| Review ID | 日期 | Reviewer | 意见摘要 | 处理 / 影响章节 | 状态 |
|---|---|---|---|---|---|
| R-AI-01 | 2026-11-21 | 独立 AI 设计评审 | 未 pin 模型不应阻止建立基线；oracle 可能经 feedback / evidence 泄露 | 分离基线资格 / 对比等级，完整 Run 可选现实基线；diagnostics 默认 oracle 权限，§4.7、§6.4 | IMPLEMENTED_IN_DOC |
| R-AI-02 | 2026-11-21 | 独立 AI 设计评审 | required applicability / any-all 分母 / 阈值与 MODEL 映射需要精确契约 | 非空 preflight 集、tri-state 计划分母、BOOLEAN / BOUNDED_SCORE、NOUL / CHOICE / SCORE 映射，§3.4、§4.4、§6.2 | IMPLEMENTED_IN_DOC |
| R-AI-03 | 2026-11-21 | 独立 AI 设计评审 | 时钟、历史 candidate 重跑、取消确认、证据缺失需要闭环 | 固定 EvalSpec clock、SNAPSHOT_REF / purpose / selection、CANCELLING / stopConfirmed / evidence states，§3、§5.5、§8 | IMPLEMENTED_IN_DOC |
| R-AI-04 | 2026-11-21 | 独立 AI 设计评审 | Python 隔离要求需首个可实施后端，不只列原则 | K8s 专用 unbound Job、独立 staging / result collection、结构化 exit / stdout / stderr，§4.6 | IMPLEMENTED_IN_DOC |
| R-AI-05 | 2026-11-21 | 独立 AI 二次复核 | 主修订闭环，仍需允许 BOOLEAN 合法不确定和明确有效 clock 参与 hash | BOOLEAN INDETERMINATE score=null；clock 值进 environment / spec fingerprint 与 STRICT equality，§3.3、§4.4、§6.4 | IMPLEMENTED_IN_DOC |

独立二次复核在所检查块未发现新的 P0；其两项 P1 已落入正文。该结论仅为静态设计复核，不是用户批准，也不证明运行时正确性。

### C.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 / 用户确认 |
|---|---|---|---|---|
| v0.1 | 2026-11-21 | core-ai | 初稿及交付前 AI 复核修订：第一性原理、版本冻结、数据 / grader ABI、隔离调度、指标 / baseline、API / UI、源码 / 行业依据；补现实基线、oracle diagnostics、精确判定 / clock / evidence / cancellation 和 K8s grader 后端 | 用户请求设计；待 Review，未批准实施 |
| v0.2 | 2026-11-21 | core-ai | 实施前一致性修订：PG-only 启动验收、grader mode / required 集定义、split 缺省、idempotency 列、grader test receipt、缺失的 suite / spec / dataset / report 读 API；开始 P1–P2 实施 | 用户要求 review 并实施；按建议默认实施，未逐项确认 Q |

### C.4 实施前复核发现（2026-11-21）

| Review ID | 位置 | 发现 | 处理 |
|---|---|---|---|
| R-AI-06 | §10.2 | “Mongo collection 显式注册 / 在支持的存储模式均能初始化”与 §7.2 PG-only 冲突 | 改为评估 schema / store 只在 PG 模式初始化，不新增 eval Mongo collection |
| R-AI-07 | §4.4 | `BOOLEAN` / `BOUNDED_SCORE` 的归属层级、适用 required 集定义不够明确，实施时容易把 mode 当 suite 全局开关 | 明确 mode 属于 GraderRevision；适用集 = case `grader_refs` ∩ suite entries，适用 required 非空 |
| R-AI-08 | §4.2 | `metadata.split` 标注“必需”又写“默认导入 DEV” | 改为可选、缺省 DEV |
| R-AI-09 | §5.1、§7.2 | preflight 要求 grader smoke test，但 schema 没有 receipt 落点；`request_key_hash` 不足以作为唯一幂等键 | grader suite 增加 `test_receipt_json`；run 表增加 `request_key` + `request_hash` |
| R-AI-10 | §8.1 | 缺少 dataset / suite / spec 的读接口，详情页与比较无法获取冻结对象；报告只能从 run 详情读取 | 补充对应 GET 列表与 run report 端点 |
| R-AI-11 | §10.3 | 首轮实施没有 K8s grader 后端，Python / model grader 无法真实交付 | 首轮只开放 JSONL + 内置确定性 grader + OFFLINE_TEXT；Python / model / sandbox profile 返回明确不可用，不伪装已交付 |


## 附录 D：实施验证记录

### D.1 P1–P2 / 最小 P4 实施记录（2026-11-21）

工作区基线：`3894c794b`（尚未提交本次改动，未部署）。本轮完成了首轮可运行子集：

- `core-ai-server-db-migration/src/main/resources/db/migration/V48__agent_evaluation.sql`：evaluations、dataset revisions / cases、grader suites、specs、candidate snapshots、runs、trials、attempts、gradings、baseline history、audit events。
- `core-ai-api/.../server/evaluation/`：`EvaluationWebService` 与 typed request / response DTO；通过 core-ng `WebServiceInterfaceValidator` 与 `WebServiceImplValidator` 校验。
- `core-ai-server/.../evaluation/`：PG store、JSONL parser / importer、grader suite 校验、snapshot freeze、spec freeze、preflight、idempotent run / trial 创建、durable claim / lease、超时与取消恢复、内置确定性 grader、完整分母报告、baseline CAS、paired bootstrap compare。
- `EvaluationModule` / `ServerApp` / `PostgresModule` / `PermissionCodes` / `SpaRoutes`：PG-only 模块接线、row view 注册、`evaluation.*` 权限码、页面路由。
- `core-ai-frontend/src/pages/experiments/EvaluationList|EvaluationDetail|EvaluationRun.tsx`、`src/api/evaluation.ts`、`App.tsx`、`Layout.tsx`：列表 / 数据 / grader / spec / run / report / baseline / compare 页面。

### D.2 已执行验证

| 验证 | 命令 / 方式 | 结果 |
|---|---|---|
| 后端编译 | `./gradlew :core-ai-server:compileJava` | 通过 |
| 测试编译 | `./gradlew :core-ai-server:compileTestJava` | 通过 |
| 单元 / 契约测试 | `./gradlew :core-ai-server:test --tests "ai.core.server.evaluation.*"` | 通过（无 PG 环境变量时 PG store 测试跳过） |
| PG store SQL | 设置 `CORE_AI_PG_TEST_URL=jdbc:postgresql://localhost:5432/core_ai` 后重跑上述测试 | 通过（本地 PG 2.0.x schema + 测试建表） |
| DTO 契约 | `EvaluationWebServiceContractTest` | 通过 |
| 前端构建 | `npm --prefix core-ai-frontend run build` | 通过 |
| SPA 路由 | `./gradlew :core-ai-server:test --tests "ai.core.server.web.SpaRoutesTest"` | 通过 |
| Migration 全链 | 新建 `core_ai_eval_migrate` 库后 `:core-ai-server-db-migration:flywayMigrate -Penv=evaltest` | 通过；V48 创建 12 张 `eval_*` 表 |
| 静态检查 | `:core-ai-server:checkstyleMain/pmdMain/spotbugsMain` 与 `checkstyleTest/pmdTest/spotbugsTest` | 通过 |
| Dev E2E | dev `dev-ai`，镜像 2.0.332，UI 全流程 | 通过；报告见 `docs/validation/2026-11-21-dev-agent-evaluation.md` |

未执行：完整 `ServerApp` 启动 / 部署、真实 Agent 调用、真实模型评分、Python 脚本、UI E2E、容量 / 成本压测、dev / UAT / prod 环境验证。

### D.3 已知偏差与限制

- run 创建在 preflight 完成后直接写 `RUNNING`，没有持久化 `QUEUED / PREFLIGHT` 中间状态；HTTP 响应仍为 202。
- 数据集导入同步完成（≤20 MiB、≤1,000 cases）；没有异步 import job、ZIP / CSV、附件和 fixtures。
- 执行使用进程内 `AgentRunner` 池；跨 pod 接管只做 `OUTCOME_UNKNOWN` 与取消，不做真实 Agent 栈 continuation。
- 只开放 `OFFLINE_TEXT`；工具、skill、subagent、dataset、sandbox 配置在 snapshot 时拒绝。Python / model grader 未实现，suite 校验会拒绝。
- 没有 artifact / trace 捕获，没有 output 大小上限；报告只有 token usage，没有 cost。
- `eval_audit_events` 表已建立但尚未写入；baseline / import / cancel 等审计留待下一轮。
- Evaluation 目前只有 owner + 全局 `evaluation.manage`（admin）可见性，没有 collaborator ACL；API 用户被显式拒绝。
- 没有 AI E2E / 浏览器验收；前端只通过 TypeScript + Vite build。
- 完整 server 测试套件当前有 3 个既有失败（`WorkflowTestModule` 未绑定 `SkillPublishService`），与本功能无直接关系，本轮未修复。

### D.4 Dev E2E（2026-11-21，镜像 2.0.332）

已在 `dev-ai` 使用真实 dev Agent 完成 UI 全流程，详细证据见 `docs/validation/2026-11-21-dev-agent-evaluation.md`：

- 4 条算术 case：导入 v1 → EXACT suite v1 → spec v1（R=1）→ run 1（有缺陷候选，0/4）→ baseline → run 2（同缺陷，0/4）→ 修复 model 后 run 3（4/4）→ compare（EXPLORATORY，Δ=100%，CI [100%,100%]）。
- cancel：spec v2（R=10、并发 1）启动后取消，最终 `CANCELLED`，`1 success + 0 failure + 39 unmeasured`，分母保留。
- 首轮真实运行验证了 target failure 与 grader `NOT_RUN_TARGET_FAILED` 的区隔，以及全失败 run 仍可作为 0% baseline。

Dev E2E 发现并修复：

1. `2.0.330` 启动失败：evaluation 的 `ListRunsRequest` / `ListRunsResponse` / `ListDatasetsResponse` 与现有 WebService bean 简单类名重复；重命名并在契约测试中与 run / dataset 接口共享 `BeanClassValidator`。
2. `2.0.331` 取消被计为 FAILURE：`AgentRun` 以 `FAILED + CancellationException` 结束；`2.0.332` 将其映射为 `CANCELLED`，trial 保持 `UNMEASURED`。
3. 测试 Agent 使用 `gpt-5-mini` 时 provider 默认 temperature 0.7 被模型拒绝；切换 `deepseek-flash` 后成功。该问题属于测试 Agent 配置，不是 eval 分类错误。

UAT / prod 未部署；Python / model grader、ISOLATED_SANDBOX、artifact / trace、export、A/A / bridge 仍未覆盖。

后续迭代必须把性能、隔离、真实模型评分、统计数据有效性和部署证据按日期继续补入本附录，并同步 §10.3。
