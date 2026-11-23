# Core AI Server 持久化 Workspace — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | DRAFT；待用户 Review；未批准实施 |
| 文档版本 | v0.1 |
| 创建日期 | 2026-11-23 |
| 需求提出者 / Reviewer | 用户；尚未批准方案或实施 |
| 作者 | core-ai |
| 产品入口 | Agent 编辑器 Sandbox 配置、会话内 Workspace 状态、Settings → Workspaces |
| 目标 | 让同一个用户与同一个 Agent 的多次交互共享一个长期存在的可执行环境（文件、依赖、缓存、声明式环境变量），支持“对话驱动开发并持续迭代” |
| 本次交付 | 仅技术设计文档；不修改业务实现、不创建迁移、不部署、不执行真实 workspace |
| 事实与提案 | 附录 B 为设计时源码 / 集群事实；正文为目标设计。所有新增 API、表、字段、默认值均为提案，不代表当前已实现 |

## 结论摘要

1. **持久化的对象不是容器，而是卷 + 声明式配置。** 沙箱（Pod）是可替换的执行器；Workspace 卷、`.core-ai/env`、bootstrap/start 脚本才是长期资产。
2. **Workspace 是一等实体：owner = 用户，environment = Workspace，Agent/Session 是绑定。** 用户的“per user”直觉在单 Agent 场景成立；但环境身份不能只是一个 user，否则同用户多 Agent/项目会互相污染，而 per-agent 全局又会跨用户泄露。
3. **主存储用持久卷（K8s 静态 PVC / Docker host dir），不用现有 tar 快照做主存储。** 快照有 500 MB / 10k 文件 / 2 GB 上限，且部署窗口可能来不及 capture；卷可以做到“部署/崩溃不丢未提交工作”。
4. **单写者由 turn lease + RWO + generation fencing 保证。** 同一 Workspace 同时只有一个 turn 能执行工具；并发会话等待或明确 `WORKSPACE_BUSY`，不做多写者。
5. **`HOME`、包管理器缓存、venv、npm prefix 都放到卷上；`apt` 明确不持久。** 这是“安装的包会保留”能成立的前提；系统级依赖必须走镜像升级。
6. **环境变量用声明式文件 `/workspace/.core-ai/env`，不依赖进程内存。** `export` 只影响当前命令；Agent 必须写文件。Secret 只以引用形式存在，启动时注入，不落卷。
7. **Workspace 会话跳过 per-session snapshot capture/restore，且失败 fail-closed。** 不能沿用“恢复失败就用空环境继续”的语义，否则 Agent 会以为文件消失并覆盖工作。
8. **首版只覆盖 chat session；eval / workflow / API run / scheduled / test / comparison / OCG / ffmpeg / A2A 仍走各自的临时 sandbox。** 这些路径的生命周期与 Workspace 不同，不能假装统一。
9. **K8s 实现用“静态 PVC + 直接 Sandbox CR”，不走 warm pool；冷启动 20–60s，工作期 warm、空闲 sleep。** P3 再评估 v1beta1 suspend 加速恢复。
10. **前置条件：PVC RBAC、StorageClass/node pool 容量、NetworkPolicy 生效。** 没有这些，Workspace 不能安全或稳定地上线；首版需要 feature flag 和 pilot 白名单。

## 阅读指南与目录

- 产品 / Reviewer：先读 §1、§2、§3.1–§3.3、§8、§12，确认“持久化的是什么”、用户流程、成本和待决事项。
- 后端 / 执行平台：读 §3–§7、§9–§10，重点关注状态分层、卷生命周期、租约、与现有 SandboxService / snapshot / MCP / terminal 的接入。
- 前端 / 测试：读 §4、§8、§11，核对 Agent 配置、会话状态、错误文案和验收矩阵。
- 原始需求、行业依据、源码核对、Review 和修改历史保留在本文件附录，不另拆零散记录。

| 章节 | 内容 |
|---|---|
| [1. 背景、需求、目标与非目标](#_1-背景、需求、目标与非目标) | 从第一性原理拆解“长期环境”到底是什么 |
| [2. 对用户方案的判断与总体方案](#_2-对用户方案的判断与总体方案) | 哪些判断成立、哪些需要修正，端到端流程 |
| [3. 核心模型与不变量](#_3-核心模型与不变量) | Workspace、Volume、Runtime、Lease、状态机 |
| [4. 状态分层与持久化契约](#_4-状态分层与持久化契约) | 文件、环境变量、包、进程分别怎样持久化 |
| [5. 存储与运行时设计](#_5-存储与运行时设计) | PVC 为主、归档为辅；K8s / Docker 实现 |
| [6. 服务架构与关键流程](#_6-服务架构与关键流程) | provision、attach、sleep、delete、多 pod、崩溃恢复 |
| [7. 数据模型、配额与 API](#_7-数据模型、配额与-api) | 表、内部契约、用户 API、错误码 |
| [8. Agent 配置与用户体验](#_8-agent-配置与用户体验) | 编辑器、会话状态、Settings、Agent 提示词 |
| [9. 安全、隐私与网络](#_9-安全、隐私与网络) | 信任边界、凭证、网络策略、审计 |
| [10. 可观测性、失败处理与运维](#_10-可观测性、失败处理与运维) | 指标、失败矩阵、runbook |
| [11. 验收、实施计划与风险](#_11-验收、实施计划与风险) | 验收矩阵、分阶段、依赖与风险 |
| [12. 关键决策与未决事项](#_12-关键决策与未决事项) | ADR 与待确认问题 |
| [附录 A：用户原始需求与文档治理](#附录-a-用户原始需求与文档治理) | 原文与维护规则 |
| [附录 B：行业依据与设计时源码核对](#附录-b-行业依据与设计时源码核对) | 来源、事实、约束 |
| [附录 C：Review 与修改历史](#附录-c-review-与修改历史) | 意见、处理、版本变化 |

## 1. 背景、需求、目标与非目标

### 1.1 第一性原理：先把“长期环境”拆成状态，再谈怎么持久化

用户的目标不是“让容器不要被销毁”，而是：**同一个 Agent 在多次对话之间，工作成果和环境能力能够累积，从而支持“改代码 → 跑测试 → 看失败 → 再改”的迭代闭环。** 要设计正确，必须先回答“到底有哪些状态需要跨对话存活”，因为不同状态的最优存储方式完全不同。

把目标场景（在 core-ai-server 上建一个开发 core-ai-desktop 的 Agent）拆成状态层：

| 状态层 | 具体内容 | 是否必须跨对话 | 变化频率 | 适合的持久化方式 |
|---|---|---|---|---|
| L1 工作产物 | 源码、测试、脚本、文档、构建产物、数据库文件 | 必须 | 高 | 可随机读写的持久卷（block volume） |
| L2 依赖与缓存 | `node_modules`、Python venv / `~/.local`、pip/npm/uv/gradle 缓存、工具链 | 必须（否则每次重装） | 中 | 与 L1 同一个卷，通过 `HOME` / cache 变量把写入导向卷 |
| L3 配置 | 环境变量、PATH、shell profile、工具配置、启动脚本 | 必须（逻辑上） | 低 | 卷内声明式文件 + Workspace 元数据；**不是进程内存** |
| L4 进程 | dev server、watch、后台任务、测试进程 | 可选，且不能承诺跨重启 | 高 | 仅运行时存活；重启后由 start 脚本拉起 |
| L5 身份与凭据 | 用户身份、Git token、API key | 必须但不可明文落盘 | 低 | Secret reference，启动时注入；不写入卷 |
| L6 对话状态 | 消息、工具轨迹、计划 | 已由会话系统负责 | 高 | 现有 chat session / transcript，不属于 Workspace |

由此得到三个直接结论：

1. **真正需要“持久化”的对象是 L1+L2+L3 组成的文件系统与声明式配置，而不是容器进程。** 容器（Pod）是可以随时被替换的“牛”；卷才是“宠物”。把持久化绑在 Pod 生命周期上，会在部署、节点回收、配额驱逐时不断丢状态。
2. **“安装了哪些包”不是一个可持久化的逻辑对象，而是文件系统上的副作用。** 平台不能可靠地“记住 pip install 过什么”，只能保证包管理器写入的目录在卷上，并让默认写入目标指向卷。`apt-get install` 写的是镜像的只读/可写层，默认不可能跨 Pod 存活，必须显式说明。
3. **“设置的环境变量”同样不是可持久化对象。** Shell 里的 `export FOO=bar` 随着进程结束就消失；能持久化的只有“一个声明式文件 + 每次执行时重新应用的运行时契约”。Agent 必须被明确告知这一点，平台也必须提供稳定的文件位置和读取规则。

### 1.2 需求追踪

| ID | 用户目标 / 补充要求 | 设计落点 |
|---|---|---|
| U1 | 开启持久化 workspace，直观感觉是 per user 级别 | §2.1、§3.1–§3.3：所有权 per user，环境身份是 Workspace；不是“一个用户一个全局环境” |
| U2 | 安装的包、临时写的脚本、设置的环境变量长期存在 | §4：分层契约；包写入卷、脚本在卷内、env 通过声明式文件；明确 apt/进程的边界 |
| U3 | Agent 能自己启动测试并持续迭代，用户只负责对话 | §4.5、§6.3、§8.4：工具链 + 长任务 + start 脚本 + Agent 提示词契约 |
| U4 | 实际场景：在 server 上建一个开发 core-ai-desktop 的 Agent | §4.8、§11.1：以该场景作为验收主线 |
| S1 | 多用户隔离，Agent 定义共享但环境不能串 | §3.2、§9：Workspace 归用户所有；跨用户访问一律拒绝 |
| S2 | 未提交的工作不能因部署/崩溃丢失 | §5、§6.7：卷独立于 Sandbox 生命周期；不依赖 capture-on-shutdown |
| S3 | 成本可控，不能为每个用户常驻一台机器 | §5.1、§7.2、§10：卷常驻、计算休眠；配额与 LRU 驱逐 |
| S4 | 失败可见、可恢复，不能静默变成空环境 | §6.9、§10.2：volume 错误 fail-closed；不沿用 snapshot 的“降级为空” |
| D1 | 只做 tech design，不实现 | 全文；附录 C 记录未授权实施 |
| D2 | 从第一性原理分析，允许推翻用户方案 | §1.1、§2.1 |
| D3 | 查询最佳实践并结合现有架构 | 附录 B.1、全文取舍 |
| D4 | 保留原始需求、设计初衷、Review 与修改记录 | 附录 A / C |

### 1.3 目标与不变量

以下不变量是后续所有实现和验收的硬约束：

1. **所有权不变量**：Workspace 恰好属于一个用户；任何跨用户的读取、挂载、终端、API 都必须被拒绝。Agent 定义可以是共享的，但“这个 Agent 为某个用户创建的环境”绝不共享。
2. **单写者不变量**：同一时刻最多一个 Sandbox 运行时挂载某个 Workspace 卷；同一时刻最多一个 turn lease 持有该 Workspace 的工具执行权。
3. **生命周期不变量**：删除 / 释放 Sandbox **绝不**删除 Workspace 卷；卷的删除只由显式的 Workspace 删除 / 重置 / 过期清理触发。
4. **快照隔离不变量**：Workspace 会话不参与现有 per-session snapshot 的 capture/restore；Workspace 的持久性由卷保证，不能用 500 MB 的 tar 快照冒充。
5. **失败不降级不变量**：卷不可用、挂载失败、启动脚本失败时，不能静默创建一个空 Workspace 继续跑；必须让本次调用失败并给出可操作错误。空环境会让 Agent 误以为文件消失，进而覆盖或重建，破坏用户工作。
6. **环境身份不变量**：Workspace 的镜像 / 工具链是 Workspace 的一部分；修改 Agent 的 sandbox image 不得静默改变已有 Workspace 的环境。升级必须是显式操作。
7. **声明式配置不变量**：环境变量以 Workspace 记录 + 卷内 `.core-ai/env` 为权威；进程内 `export` 不是权威；Secret 只以引用形式存在，不写入卷内普通文件。
8. **幂等与对账不变量**：provision / wake / sleep / delete 都必须幂等，并能被后台对账任务修复；任何“创建了卷但数据库没记录”或“数据库有记录但卷丢了”都必须可见。
9. **配额先于创建不变量**：任何卷创建前先检查用户与平台配额；配额不足时给出明确错误，不能先创建再失败留下孤儿。
10. **Agent 可迁移不变量**：Workspace 提供稳定的路径、HOME、env、bootstrap/start 契约，使 Agent 的“环境知识”可以跨会话、跨模型、甚至跨 Agent 复用；不能要求 Agent 每次重新发现环境。

### 1.4 首版边界与非目标

| 首版纳入 | 首版不纳入 |
|---|---|
| 用户拥有的、可命名/可删除的 Workspace；一个 Agent 默认绑定一个 | 团队共享 Workspace、多人实时协作、跨用户授权 |
| 持久卷（K8s PVC / Docker host dir）+ Sandbox 挂载 | 跨区域复制、跨云迁移、自动灾备 |
| 文件、依赖、缓存、声明式 env、bootstrap/start 脚本 | 进程 / 内存快照、运行中进程跨重启存活、live migration |
| 会话与 Workspace 绑定；turn lease；空闲休眠；按需唤醒 | 多写者并发、同一 Workspace 多 Sandbox 同时挂载 |
| K8s 主路径；Docker 本地开发的等价实现（P1） | 无卷能力 provider 的完整降级（明确返回不支持） |
| 配额、LRU 驱逐、软删除、孤儿对账 | 自动扩容卷、在线缩容、跨 Workspace 迁移数据 |
| 终端接入、文件上传 / 附件、MCP / hub 继续工作 | 端口发布 / 公网预览、桌面 GUI 远程操作 |
| 观测、审计、失败可见、kill switch | 自动 commit / push、自动修复代码、自动发布 |
| 以 core-ai-desktop 开发 Agent 为验收场景 | 作为 eval trial / workflow run / API run / scheduled run / `source=test` / comparison / OCG / ffmpeg / A2A 远程 Agent 的隔离环境（这些路径有各自的 sandbox key 或 fresh 要求，v1 不接入） |
| Chat session（含 subagent 在同一 turn 内共享 Workspace） | Agent comparison 的两个会话共享 Workspace（必须各自独立或禁用） |
| 只读的 session share 投影可以展示 Workspace 状态 | 通过 share 链接以他人身份执行工具 / 访问 Workspace 文件 |

**理由：**首版必须交付“真正不会丢工作”的持久性；不能为了覆盖所有 provider 而把语义降级成“尽量恢复”。进程存活、共享协作、灾备都是独立的大特性，混进来会让首版既不可靠也不可验收。

## 2. 对用户方案的判断与总体方案

### 2.1 用户方案的逐条判断

用户提出的直觉是：“开启持久化 workspace（per user 级别），每次交互拥有一个长期环境，包括安装的包、临时脚本、环境变量；Agent 能启动测试并持续迭代。”逐条分析：

| 用户判断 | 结论 | 分析 | 设计落点 |
|---|---|---|---|
| “per user 级别” | **部分正确** | 所有权、配额、计费确实应 per user；但“环境身份”不能只是 user。同一个用户可能同时有“数据 Agent”“桌面开发 Agent”“另一个项目的开发 Agent”，共用一个环境会互相覆盖依赖、污染 PATH、误删文件。反过来，如果把环境做成 per agent 全局共享，则用户 A 和用户 B 会共享同一份文件，是严重的数据泄露。正确模型是：**owner = user，environment = workspace，agent/session 是绑定关系**。 | §3.1–§3.3 |
| “长期环境” | **正确但需要精确化** | “长期”不应等于“Pod 常驻”。Pod 常驻成本高、会被节点回收、部署滚动、配额驱逐；真正长期的是卷。运行时可以按需唤醒，卷一直在。 | §5.1、§6.2–§6.5 |
| “包括安装的包” | **方向正确，机制要改** | 包不是逻辑对象，而是文件系统副作用。只有把 `HOME`、venv、`node_modules`、包管理器缓存放到卷上，安装才真正持久；`apt-get` 写镜像层，默认不持久，必须显式不支持并给替代方案。 | §4.2–§4.4 |
| “临时写的脚本” | **正确** | 脚本应写在 `/workspace` 下；`/tmp` 是临时区，休眠即丢。 | §4.2 |
| “设置的环境变量” | **需要重新定义** | Shell 里的 `export` 不可能跨进程存活。能持久化的只有声明式 env 文件 + 每次执行时注入。Agent 必须把 env 写到 `.core-ai/env`，而不是依赖 `export`。 | §4.3 |
| “每次交互拥有一个长期环境” | **需要精确化** | 会话是客户端，不是环境所有者。一个 Workspace 可以被多个会话使用（但同一时刻只有一个 turn 持有执行权）；会话关闭不应销毁环境。 | §3.3、§6.3 |
| “Agent 能自己启动测试，持续迭代” | **正确，但要补齐执行契约** | 测试是进程，不需要跨天存活；需要的是工具链、文件、足够的执行时长和后台任务能力。当前同步 bash 上限 600s，长构建应走 `run_in_background` + `async_task_output`；测试进程在 Workspace 休眠时会被终止，由 `start.sh` 重新拉起。 | §4.5、§8.4、§11.1 |
| “我要做的只是和这个 Agent 对话” | **正确，是产品目标** | 平台必须把 provision、唤醒、挂载、env、启动脚本、错误恢复都隐藏在对话之后；用户只在首次确认成本和必要时看到 Workspace 状态。 | §8 |

**最需要修正的一个隐含假设：**“持久化 = 容器不销毁”。这是把状态绑在进程上。正确的对象是**卷 + 声明式配置**；容器是可替换的执行器。接受这一点后，部署、崩溃、配额、节点回收都不再是“持久化失败”，而只是“下次唤醒”。

### 2.2 端到端用户流程

1. Agent 作者在 Agent 编辑器的 Sandbox 配置中开启 **Persistent workspace**，设置容量、空闲休眠时间、网络、可选的 bootstrap/start 脚本，并确认“这会创建一个长期计费的卷”。
2. 用户第一次与该 Agent 对话时，服务端解析该用户的默认 Workspace；不存在则在配额允许下创建一条 Workspace 记录并异步/惰性创建卷（首次创建前显示一次性成本确认；Agent 保存时可预先确认）。
3. 第一次工具调用前，服务端获取该 Workspace 的 turn lease，创建 / 唤醒 Sandbox，把卷挂到 `/workspace`，等待 runtime READY；首次 provision 执行 `bootstrap.sh`，每次唤醒执行 `start.sh`。
4. Agent 执行 shell / python / 文件工具；写入 `/workspace` 的内容、`HOME` 下的依赖与缓存、`.core-ai/env` 中的环境变量都会保留。
5. Turn 结束，释放 turn lease，Sandbox 保持 warm `idleSleepMinutes`；用户继续追问时几乎无感。
6. 空闲超过阈值后，`WorkspaceSleeper` 释放 Sandbox（删除 Pod / CR），**保留卷**；Workspace 状态变成 READY（cold）。
7. 用户下次回来，服务端唤醒：重新创建 Sandbox、挂载同一个卷、执行 `start.sh`，Agent 看到原来的文件、依赖和 env。
8. 服务端部署 / 崩溃：Sandbox 可能消失，卷不受影响；下次工具调用重建并挂载。未提交但已写入卷的文件不会丢。
9. 用户删除 Workspace：软删除保留 7 天（可配置），之后清理卷；重置则立即用新卷替换，旧卷删除。
10. 全程用户只看到“正在准备工作区 / 已就绪 / 正在休眠 / 出错”的状态，不需要理解 Pod、PVC、lease。

### 2.3 总体架构

```text
Agent editor / Session UI / Settings → Workspaces
                    |
              Workspace API
                    |
        WorkspaceService ─────────────── WorkspaceStore (PG)
             |        \                     WorkspaceLeaseStore (PG)
             |         \                    WorkspaceEventStore (PG)
             |          \
   WorkspaceRuntimeService ──── WorkspaceActivity (turns / async / terminal)
             |
   WorkspaceProvisioner
      |               |
 K8s PVC          Docker host dir
      |
 Sandbox CR / Pod  ──mount──▶  /workspace
      |
 sandbox-runtime  (tools, hub, terminal, MCP)
      |
 Agent session (holds turn lease; hub bound to this session)
```

**与现有架构的关系：**

- `SandboxService` 仍然是“会话拿到的 Sandbox 门面”的唯一入口；Workspace 模式新增一个 `WorkspaceSandboxHandle`，它实现 `Sandbox`，但内部委托给按 `workspaceId` 共享的运行时，而不是按 `sessionId` 独占。
- `SandboxManager` 继续管理本 Pod 的运行时配额与 TTL；Workspace 运行时使用独立的配额桶和更长的 TTL，不能被普通 session 的 LRU 驱逐。
- `SandboxSnapshotService` 继续服务普通会话；Workspace 会话显式跳过 capture/restore。
- `SessionMcpProcesses`、`SandboxFileService`、`SandboxTerminalService` 继续按 session 工作，但作用在共享 runtime 上；附件路径增加 session 维度，避免同名覆盖。
- `ChatSession` 增加 `workspace_id`；`SessionRebuildManager` 重建时按它恢复绑定。

### 2.4 状态机

Workspace 用两个正交状态描述，避免“运行时失败”被误解为“卷丢了”：

**卷状态 `volume_status`：**

```text
PROVISIONING ──success──▶ READY ──delete──▶ DELETING ──▶ DELETED
      │                     │
      └──failure──▶ ERROR ◀─┘（卷丢失/不可挂载）
```

**运行时状态 `runtime_status`：**

```text
NONE ──wake──▶ PROVISIONING ──ready──▶ READY ──idle──▶ SLEEPING ──wake──▶ PROVISIONING
                    │                     │
                    └──failure──▶ ERROR ◀─┘（Pod/挂载/启动脚本失败）
```

| 状态 | 含义 | 允许的工具调用 |
|---|---|---|
| `volume_status=PROVISIONING` | 正在创建 PVC / 目录，等待 Bound | 否，返回“工作区准备中” |
| `volume_status=READY` | 卷可用；运行时可能不存在 | 是（会触发 wake） |
| `volume_status=ERROR` | 卷创建失败或丢失 | 否；必须重试或重置，不能空环境继续 |
| `runtime_status=NONE` | 没有运行时 | 是（wake） |
| `runtime_status=PROVISIONING` | 正在创建/挂载 Sandbox | 否，返回“正在唤醒” |
| `runtime_status=READY` | 运行时可用 | 是 |
| `runtime_status=SLEEPING` | 正在释放运行时 | 否，短暂等待 |
| `runtime_status=ERROR` | 运行时失败，卷仍可能完好 | 否；重试 wake，不删除卷 |

**组合展示**：UI 只显示一个合并状态（Preparing / Running / Sleeping / Needs attention / Deleting），但内部保留两个字段用于诊断。`volume_status=ERROR` 与 `runtime_status=ERROR` 的恢复路径不同：前者可能需要重建卷（数据风险，需确认），后者只需重建 Pod。

**硬性转移规则：**

1. `runtime_status` 进入 `READY` 前，卷必须已 `READY` 且挂载成功；start 脚本失败不能进入 READY。
2. `runtime_status=READY` 时才能执行工具；执行时必须持有 turn lease。
3. `SLEEPING` 只能在无 turn、无 in-flight async task、无终端活动时进入。
4. `volume_status=DELETING` 前必须先释放 runtime 并撤销所有 lease。
5. 任何状态都不能从 `DELETED` 回到可用；误删只能新建 Workspace。

## 3. 核心模型与不变量

### 3.1 术语与实体关系

| 对象 | 定义 | 可变性 / 关系 |
|---|---|---|
| `Workspace` | 一个用户拥有的持久环境：名称、owner、默认绑定的 agent、容量、镜像、网络、env、bootstrap/start 脚本、状态 | 可由 owner 编辑元数据；身份不变；删除是软删除 |
| `WorkspaceVolume` | 实际承载 `/workspace` 的持久卷（K8s PVC / Docker host dir），由 provider 创建、由 Workspace 记录引用 | 生命周期独立于 Sandbox；只能由显式删除/重置触发删除 |
| `WorkspaceRuntime` | 当前挂载该卷的 Sandbox 运行时（Pod / CR / Docker container），按 `workspaceId` 唯一 | 可创建、释放、重建；不持久；一个 Workspace 同时最多一个 |
| `WorkspaceBinding` | `workspaceId → sandboxId / provider / runtime_generation` 的持久绑定，用于跨 Pod attach | 随 runtime 变化；有 generation 防陈旧 |
| `WorkspaceLease` | 某个 turn 对 Workspace 执行权的独占租约（跨 Pod） | 短 TTL、可续租、可被过期接管 |
| `WorkspaceSessionLink` | 会话与 Workspace 的绑定（落在 `chat_sessions.workspace_id`） | 创建时确定；重建时读取；不随 Agent 编辑变化 |
| `WorkspaceActivity` | 最近一次 turn / tool / async task / terminal 活动，用于休眠判断 | 派生状态，不要求强一致 |
| `WorkspaceArchive` | （P2）卷的冷归档：blob pointer、hash、size、created_at | 不可变；用于灾备/删除前备份，不是主存储 |

关系：`Workspace 1—1 WorkspaceVolume`；`Workspace 1—0..1 WorkspaceRuntime`；`Workspace 1—N WorkspaceSessionLink`；`Workspace 1—N WorkspaceLease`（历史）；`Workspace 1—N WorkspaceArchive`（P2）。

**理由：**把“环境身份”（Workspace）、“存储”（Volume）、“执行器”（Runtime）、“访问权”（Lease）分开，才能在部署、崩溃、配额驱逐时分别处理，而不是把四种生命周期绑在一个 Pod 上。

### 3.2 Workspace 身份、所有权与 Agent 绑定

**所有权：**

- `Workspace.owner_user_id` 是唯一权限边界。创建时从认证用户取，绝不接受请求体里的 owner。
- 同一用户的多个 Workspace 互相隔离；不同用户绝不共享卷、runtime、terminal、hub binding。
- Agent 定义是共享资源，但 Workspace 不是：用户 A 使用 Agent X 创建的环境，用户 B 看不到，也不能通过改 Agent 配置访问。

**身份与命名：**

- Workspace 是**命名实体**，不是 `(user, agent)` 的隐式推导。默认自动创建一个名为 `default` 的 Workspace，但用户可以创建多个（例如 `desktop-main`、`desktop-experiment`），并在会话开始时选择。
- `agent_id` 是**默认绑定**，不是所有权。一个 Workspace 可以被多个 Agent 显式绑定（例如“开发 Agent”和“审查 Agent”共享同一个 checkout），但这是用户主动选择，UI 必须提示“它们会共享文件、依赖和端口”。
- 同一 Agent 对不同用户各自拥有独立的默认 Workspace。

**对“per user 直觉”的保留：**在只有一个 Agent、一个项目的常见情况下，系统只会自动创建一个默认 Workspace，用户体验就是“我的环境”；Workspace 实体不会强迫用户管理多个环境。只有在用户主动创建第二个 Workspace（例如另一个项目或实验分支）时，才需要选择。这样既保留了 per-user 的简单性，又避免了多 Agent 互相污染。

**为什么不是 per-user 全局：**

| 方案 | 问题 |
|---|---|
| 一个用户一个全局环境 | 不同 Agent / 项目互相污染；一个 Agent 的破坏性操作影响另一个；env / 工具链冲突；无法区分计费与配额 |
| 一个 Agent 一个全局环境 | 跨用户共享文件，严重数据泄露；Agent 定义是共享的，环境不能跟着共享 |
| 一个会话一个环境 | 回到现状：关闭会话就丢；无法“持续迭代” |
| **用户拥有、Workspace 为身份、Agent/Session 绑定** | 隔离正确；可多项目；可复用；可审计 |

**默认创建策略（提案）：**

1. Agent 开启 `persistentWorkspace.enabled` 后，首次会话若用户没有该 Agent 的默认 Workspace，则创建一个（`name=default`，`agent_id=该 Agent`）。
2. 创建前检查配额；配额不足返回 `WORKSPACE_QUOTA_EXCEEDED`，会话可以显式选择“使用临时环境继续”（这是用户选择，不是静默降级）。
3. 用户在 Agent 编辑器保存开关时确认成本提示；首次真正创建时再显示一次“将创建 32 GiB 持久卷”的确认（可勾选“不再提示”）。
4. 默认 Workspace 被删除后，下次会话重新创建；不自动复用同名残留。

**Agent 配置变化的影响：**

- **关闭 persistent workspace**：新会话使用临时环境；已有会话继续使用它们创建时绑定的 Workspace（`workspace_id` 已固化），不会被静默切换；Workspace 仍归用户所有，可在 Settings 删除。
- **删除 Agent**：Workspace 不会级联删除（它属于用户，不是 Agent 的附属物）；UI 显示为 unbound，用户可删除或重新绑定到另一个 Agent。
- **DRAFT / PUBLISHED 差异**：会话创建时使用的可执行配置决定 Workspace 的首次镜像/脚本/网络；之后修改 DRAFT 不会改变已有 Workspace。新会话按当时的 PUBLISHED/DRAFT 解析；如果解析出的配置与已有 Workspace 的 `environmentFingerprint` 不一致，进入 `ENVIRONMENT_DRIFT` 提示，而不是静默替换。
- **Agent comparison**：两个变体会话必须各自独立 Workspace 或禁用持久化；共享一个 Workspace 会让 A/B 互相污染，不能用于比较。

### 3.3 Session、Turn 与 Workspace 的关系

这是本设计最容易被低估的部分。现有系统是“一个 session 一个 sandbox”，而 Workspace 要求“一个 workspace 一个 runtime，多个 session 可能先后/同时使用”。必须明确三层关系：

```text
Workspace (持久，按 workspaceId 唯一 runtime)
   ▲
   │ 绑定（chat_sessions.workspace_id）
Session A ──turn──▶ TurnLease ──▶ 工具执行 / hub binding
Session B ──turn──▶ （等待或 WORKSPACE_BUSY）
```

**规则：**

1. **会话是客户端，不是环境所有者。** 关闭会话不释放 Workspace runtime；只释放该会话的句柄、MCP 进程、附件暂存和 lease。
2. **执行权按 turn 授予，不按 session 常驻。** 一个 turn 开始前获取 `WorkspaceLease`，turn 结束（包括所有 in-flight async task）后释放。这样同一时刻只有一个会话能把 hub binding 指向自己，避免“A 的脚本以 B 的身份调用工具”。
3. **同一 Workspace 的并发 turn 默认拒绝或短暂等待。** 两个会话同时发消息时，第二个等待最多 `leaseWaitSeconds`（默认 30s），仍拿不到则返回 `WORKSPACE_BUSY`，并提示“另一个会话正在使用该工作区”。不做多写者，也不做隐藏的队列无限等待。
4. **空闲会话不阻止新会话。** Session A 空闲（没有运行中的 turn）时，Session B 可以获取 lease；A 下次发消息时重新竞争。runtime 保持 warm，不重建。
5. **异步任务延长租约。** `run_in_background` 的 bash / python 任务如果在 turn 结束后仍在运行，lease 必须保持到任务终态（或显式取消），否则后台进程可能在错误的 session 身份下调用 hub。休眠器也必须看到这个信号。
6. **终端是同一用户的旁路。** 终端连接会增加 Workspace 活动、阻止休眠；终端不获取 turn lease（用户手动操作优先），但与 Agent turn 并发时由用户自己承担冲突风险。UI 必须提示“Agent 可能同时在此工作区执行命令”。
7. **Workspace 绑定在会话创建时固化。** 之后 Agent 的默认 Workspace 改变，不改变已有会话；重建会话读取 `workspace_id`，不会重新解析默认值。
8. **并行 subagent 共享文件系统，但平台不提供文件锁。** 同一 turn 内的多个 subagent 可能并发写 `/workspace`；v1 建议 Agent 串行化写操作，或在 `AGENTS.md` 中约定锁文件；平台不承诺自动合并。需要强隔离的 subagent 应使用临时环境，而不是共享 Workspace。
9. **非 turn 的 Workspace 操作不等于拥有执行权。** MCP 启动、附件 staging、终端、usage 采样可能在 turn 之外发生；它们必须幂等且避免改写用户文件，或使用短时的 maintenance lease。只有 Agent 工具写操作需要 turn lease。

**为什么不做“多 session 共享一个 runtime 且各自绑定 hub”：** 现有 runtime 的 `/bind` 是单绑定（一次 `POST /bind` 替换前一个，见附录 B C15），hub proxy 只持有当前 session token。多绑定需要改 runtime 的鉴权与路由模型，属于独立项目；首版用 turn lease 串行化，语义清晰且安全。

### 3.4 租约、generation 与 fencing

Workspace 是有状态服务，必须像数据库一样处理并发与陈旧写入。

| 机制 | 作用 | 规则 |
|---|---|---|
| `volume_generation` | 卷的代际；重建/重置时递增 | capture/archive/restore 必须匹配 generation，否则丢弃 |
| `runtime_generation` | 每次成功创建 runtime 递增 | 工具调用、terminal ticket、binding 必须匹配；旧 runtime 的晚到结果不能写回 |
| `lease_token` | 一次 turn lease 的唯一 token | acquire / renew / release / 工具执行都校验 |
| `lease_expires_at` | 防止 Pod 崩溃后永久占有 | 默认 TTL 5 分钟，turn 期间每 30s 续租 |
| `binding.updated_at` | 判断 binding 是否陈旧 | 超过 `runtimeMaxAge` 且无 lease 时，允许接管 |

**租约获取（提案伪代码）：**

```text
acquire(workspaceId, sessionId, turnId):
  loop until timeout:
    row = PG.tryInsertOrSteal(
      workspaceId,
      holder = thisPodId,
      token = random,
      sessionId, turnId,
      expiresAt = now + 5m,
      condition = existing.expires_at < now()
                  OR (existing.session_id = sessionId AND existing.turn_id = turnId)
    )
    if row: return Lease(token, runtimeGeneration)
    wait 250ms
  throw WORKSPACE_BUSY
```

**接管（takeover）流程：**

1. 发现 lease 过期或 binding 所属 Pod 已不在（Pod 心跳/注册表）。
2. 先查 binding 指向的 Sandbox 是否仍存在且健康。
3. 如果存在且没有其他活跃 lease：可以 attach（同一 Pod 或跨 Pod 能直接访问 IP），重新 `/bind` 到当前 session；不重建卷。
4. 如果存在但无法确认所有权 / 健康：删除旧 Sandbox（释放卷挂载），等待 Pod 终止，再创建新 Sandbox。**这一步会杀死旧 turn 的进程**；这是崩溃恢复的正确取舍，必须记录审计并在 UI 提示“上次执行已中断”。
5. 新 runtime 的 `runtime_generation` 递增；旧 Pod 的晚到工具调用因 generation 不匹配失败，不得写回。

**租约丢失的处理：**每次工具执行前校验 `lease_token + runtime_generation`；如果租约已被接管，当前 turn 必须立即中止（返回 `WORKSPACE_REPLACED`），不能继续写卷。续租失败要重试；连续失败按租约丢失处理。不能把“续租失败”当成后台任务继续跑的理由。

**为什么 RWO 还不够：** Kubernetes `ReadWriteOnce` 只保证单节点挂载；同一节点上多个 Pod 仍可能同时挂载。必须有平台层 lease 作为单写者闸门。反过来，lease 也不能替代 RWO：跨节点双挂载会直接损坏文件系统。

### 3.5 指纹与兼容性

| 指纹 | 内容 | 用途 |
|---|---|---|
| `workspaceFingerprint` | owner、workspace id、volume generation、size、storage class | 证明是同一个环境；防止同名重建被当成原环境 |
| `environmentFingerprint` | image digest（或 tag + runtime version）、runtime major、security context、env 名称集合、bootstrap/start 脚本 hash | 判断卷内容是否仍与镜像兼容；升级时提示 |
| `configFingerprint` | env 值、网络模式、idle sleep、agent 默认绑定 | 判断配置是否变化；不需要重建卷 |
| `sessionBindingFingerprint` | sessionId、workspaceId、agentId、createdAt | 诊断“为什么这个会话看到的是这个环境” |

**兼容性规则：**

- 镜像 digest / runtime major 变化：允许挂载旧卷，但标记 `ENVIRONMENT_DRIFT`，UI 提示“工作区镜像已变化，安装的依赖可能需要重装”；不自动删除或重建。
- 存储 class / size 变化：不支持在线变更；需要新建 Workspace 并迁移（P2 可提供 archive/restore）。
- bootstrap/start 脚本变化：下一次 wake 生效；不重建卷；记录脚本 hash。
- env 变化：下一次执行生效；不重建 runtime；如果 env 是容器级（Secret 注入），需要重启 runtime。
- Agent 的 sandbox image 变化：不自动应用到已有 Workspace；见 §3.5 与 §8.1。

## 4. 状态分层与持久化契约

### 4.1 持久化矩阵（用户最关心的“什么会留下”）

| 内容 | 首版语义 | 存放位置 | 说明 |
|---|---|---|---|
| 源码 / 测试 / 脚本 / 文档 | 持久 | `/workspace/**` | 休眠、重启、部署后都在 |
| `node_modules`、venv、`~/.local` | 持久（前提是写入卷） | `/workspace/repo/node_modules`、`/workspace/.venv`、`/workspace/home/.local` | 通过 `HOME` / `PIP_USER` / npm prefix 导向 |
| pip / npm / uv / gradle / maven 缓存 | 持久 | `/workspace/home/.cache/**`、`/workspace/.gradle`、`/workspace/.m2` | 加速重装 |
| Shell 环境变量（声明式） | 持久 | `/workspace/.core-ai/env` + Workspace 记录 | 每次执行注入；不是进程内存 |
| Shell aliases / functions | 持久 | `/workspace/.core-ai/profile.sh` | 仅 bash 登录/非交互按契约加载 |
| `apt-get install` 的系统包 | **不持久** | 镜像层 / 容器可写层 | 休眠/重建即丢；需自定义镜像或静态安装到 `/workspace/bin` |
| `/tmp` 下文件 | **不持久** | emptyDir / tmpfs | 休眠/重建即丢；明确告知 Agent |
| `/skill` 下物化内容 | **不持久** | emptyDir | 每次 wake 重新物化；skill 自己安装的依赖应放 `/workspace` |
| 后台进程 / dev server | 仅 runtime 存活期间 | 进程内存 | 休眠、崩溃、部署后终止；由 `start.sh` 重启 |
| Shell 当前目录 / `cd` | **不持久** | 进程状态 | 每次工具调用使用默认 cwd 或显式路径 |
| Git 未提交修改 | 持久（写入卷） | `/workspace/repo/.git` | 但建议 Agent 定期 commit/push |
| Secret（token、key） | 不写入卷 | 启动时注入的 env / hub 能力 | 见 §9.2；轮换需重启 runtime |
| 对话 / 计划 / todo | 由会话系统负责 | transcript / memory | 不属于 Workspace |

**事件 × 状态矩阵（用户视角）：**

| 事件 | `/workspace` 文件 | 进程 / dev server | 声明式 env | `apt` 安装 | 卷本身 |
|---|---|---|---|---|---|
| 空闲 sleep | 保留 | 终止 | 保留 | 丢失（镜像层） | 保留 |
| Server 部署 | 保留 | 可能终止（Pod 被替换） | 保留 | 丢失 | 保留 |
| Pod 崩溃 / 节点回收 | 保留 | 终止 | 保留 | 丢失 | 保留 |
| Workspace reset | 清空 | 终止 | 保留（配置） | 丢失 | 新卷 |
| Workspace delete | 7 天后删除 | 终止 | 记录删除 | 丢失 | 7 天后删除 |

**这张表是产品文案的核心：**“文件在、进程不在、env 声明在、apt 不在、删了会没”。

### 4.2 文件系统布局

```text
/workspace/                         ← 持久卷挂载点（唯一持久根）
├── repo/                           ← git clone / 用户项目（默认 cwd）
├── home/                           ← HOME；dotfiles、.local、.cache、.config
│   ├── .local/                     ← pip --user
│   ├── .cache/{pip,npm,uv,...}
│   └── .config/
├── .core-ai/
│   ├── env                         ← KEY=VALUE，声明式环境变量（非 secret）
│   ├── profile.sh                  ← bash-only；aliases / PATH 扩展
│   ├── bootstrap.sh                ← 首次 provision 运行一次
│   ├── start.sh                    ← 每次 wake 运行
│   ├── logs/                       ← bootstrap/start/后台任务日志
│   ├── NOTES.md                    ← Agent 维护的环境笔记（可选）
│   └── state.json                  ← provision 时间、image、generation（平台写）
├── .venv/                          ← 可选 Python venv
├── .npm-global/                    ← npm 全局前缀
├── .gradle/ .m2/ .cargo/ .sdkman/  ← 可选工具链缓存
├── bin/                            ← 用户/Agent 放的静态可执行文件
├── attachments/<sessionId>/        ← 会话附件（按会话隔离，避免同名覆盖）
└── AGENTS.md                       ← 平台建议 Agent 读取/维护的项目说明
```

**规则：**

- 卷只挂 `/workspace`，不要挂 `/`、`/usr`、`/etc`；镜像保持不可变，避免污染基础工具链。
- `HOME` 指向 `/workspace/home`，不是 `/workspace` 根，避免 dotfiles 与项目文件混杂。
- `.core-ai/` 是平台保留目录；Agent 可以读写 `env` / `profile.sh` / 脚本，但不要删除 `state.json`。
- `attachments/` 按 `sessionId` 分子目录；现有逻辑把附件放在 `/workspace/attachments/<name>`，Workspace 模式必须改，否则两个会话同名文件互相覆盖（附录 B C12）。附件属于用户数据，计入卷用量；首版不做自动清理，用户/Agent 自行删除；P2 可按会话保留期清理。
- 默认 cwd 为 `/workspace/repo`（如果配置了 repo）；否则 `/workspace`。相对路径的文件工具仍以 `/workspace` 为根，避免 Agent 误以为 `repo` 是根。

### 4.3 环境变量与 profile 契约

**权威来源与优先级（从高到低）：**

1. 平台固定键：`WORKSPACE_DIR=/workspace`、`HOME=/workspace/home`、`HUB_PORT`、`PORT`、`MAX_ASYNC_TASKS`、`CORE_AI_*` 身份键。不可被 Agent 覆盖。
2. `/workspace/.core-ai/env`：每行 `KEY=VALUE`，不支持 shell 展开、引号语法或多行；`=` 之后的内容按字面值处理（首尾空白 trim）。每次 bash/python 执行前解析并注入。Agent 通过 `write_file` / `edit_file` 修改，下一条命令生效。
3. `PATH_PREPEND`（可选）：以 `:` 分隔的目录列表，前置到 PATH；这是 Agent 扩展 PATH 的唯一受支持方式，不允许直接写 `PATH`。
4. Workspace 记录中的 `env`（容器级，非 secret）：Pod 创建时注入；适合不常变的值。
5. 镜像内置 env（`NPM_CONFIG_*`、`LANG` 等）：最低优先级。

**Protected / denied 键：** `HOME`、`WORKSPACE_DIR`、`PATH`（只允许通过 `PATH_PREPEND` 追加）、`LD_PRELOAD`、`LD_LIBRARY_PATH`、`SHELL`、`PORT`、`HUB_PORT`、`CORE_AI_HUB`、`CORE_AI_SESSION_ID`、`CORE_AI_AGENT_NAME`、`MAX_ASYNC_TASKS`。写入 `env` 时若包含这些键，跳过并记录 warning；不要让 Agent 通过 env 伪造 hub 身份或劫持动态库。

**PATH 与工具链（建议默认）：**

```text
PATH = /workspace/.local/bin:/workspace/.npm-global/bin:/workspace/bin:/workspace/.venv/bin:$PATH
PIP_USER=1
PIP_CACHE_DIR=/workspace/home/.cache/pip
NPM_CONFIG_PREFIX=/workspace/.npm-global
NPM_CONFIG_CACHE=/workspace/home/.cache/npm
UV_CACHE_DIR=/workspace/home/.cache/uv
CARGO_HOME=/workspace/.cargo
GOPATH=/workspace/go
GRADLE_USER_HOME=/workspace/.gradle
MAVEN_CONFIG=/workspace/.m2
```

**profile 加载：**

- Bash 工具使用非交互 shell。设置 `BASH_ENV=/workspace/.core-ai/profile.sh`，bash 会在启动时自动 source；这样不需要把命令包成 `bash -lc`，也不改变引号/退出码语义。
- 交互式终端（terminal gateway）使用 `$HOME/.bashrc`；`HOME` 在卷上，所以用户可以在这里放自己的交互配置。
- Python 工具不经过 bash，只读 `env` 文件；需要在 Python 里设置 `sys.path` 的，由脚本自己处理。
- `profile.sh` 由 Agent 维护；平台不解析其内容，只在 bash 中 source；失败不能让工具调用失败（`|| true`），但要把 stderr 写入日志。

**Agent 必须知道的语义（写入系统提示词）：**

> `/workspace` 是持久工作区；`/tmp` 和 `/skill` 不是。环境变量请写入 `/workspace/.core-ai/env`（`KEY=VALUE`），不要依赖 `export`；新命令会读取它。`apt-get install` 不会持久，需要系统包请让用户升级 workspace 镜像，或把静态二进制放到 `/workspace/bin`。

### 4.4 包管理与工具链策略

| 工具 | 推荐做法 | 持久化位置 | 备注 |
|---|---|---|---|
| pip | `pip install --user ...` 或 `python -m venv /workspace/.venv` | `$HOME/.local` 或 `.venv` | `PIP_USER=1` 已存在；HOME 必须在卷上 |
| uv | `uv venv /workspace/.venv` / `uv pip install` | `.venv` + `UV_CACHE_DIR` | 快；适合反复重建 |
| npm | 项目内 `npm ci`；全局 `npm i -g --prefix /workspace/.npm-global` | `repo/node_modules` + `.npm-global` | `node_modules` 在卷上，重启不重装 |
| pnpm / yarn | store 指到 `/workspace/.cache` / `.yarn` | 卷内 | 避免默认落到 HOME 外 |
| Gradle / Maven | `GRADLE_USER_HOME` / `MAVEN_CONFIG` 指到卷 | 卷内 | 构建缓存大，但重启后省时间 |
| Cargo / Go | `CARGO_HOME` / `GOPATH` 指到卷 | 卷内 | 同上 |
| apt / dnf / apk | **不支持持久化** | 镜像层 | 需要系统包 → 自定义镜像（Agent sandbox image）或 `micromamba` / 静态二进制放卷内 |
| SDKMAN / nvm / pyenv | 安装目录指到 `/workspace/.sdkman` 等 | 卷内 | 适合需要多版本工具链的 Agent |

**为什么不支持 apt 持久化：** 允许 Agent 以 root 修改系统包会让镜像与卷的边界消失，且 `apt` 的数据库、库文件、服务脚本分散在 `/var`、`/usr`、`/etc`；要持久化就得把大半个根文件系统挂到卷上，破坏镜像不可变性与安全基线。正确做法是把“系统级依赖”变成镜像版本，由 Agent 作者评审；把“项目级依赖”放进卷。

**镜像与工具链的关系：**

- 镜像提供 Node / Python / git / gh / ffmpeg / Chromium 等基础能力；Workspace 卷提供项目依赖。
- Agent 需要不同的系统工具链时，使用自定义 image（现有 `SandboxConfig.image`）；Workspace 记录创建时使用的 image，后续不随 Agent 静默变化。
- 首版不支持在线扩容卷；容量不足时提示用户新建更大的 Workspace 并迁移（P2 可 archive/restore）。

### 4.5 进程、后台任务与长构建

**语义：**

- Workspace `runtime_status=READY` 期间，Agent 启动的后台进程（`nohup ... &`、`run_in_background`）会继续运行，跨 turn、跨 session 关闭存活。
- 休眠、runtime 重建、Pod 驱逐、部署都会终止这些进程；`start.sh` 负责把必要的服务重新拉起。
- 不承诺 shell 的 `cd`、环境 `export`、内存变量、打开的 socket、终端 scrollback 跨重启存活。

**长构建 / 长测试：**

- 同步 `run_bash_command` 的 runtime 上限是 600s（附录 B C10），core-ai-desktop 首次 `npm ci` + `npm test` 可能超过；**不要靠调大同步超时**，应使用 `run_in_background=true` + `async_task_output`（现有工具，见附录 B C21）。
- 建议 Agent 把长输出重定向到 `/workspace/.core-ai/logs/<task>.log`，用 `tail -n 200` 查看，避免 30 KB 输出截断。
- 异步任务必须被 lease 跟踪：turn 结束后任务仍在跑时，lease 不释放；休眠器不睡眠。任务终态或取消后才允许释放。
- `run_in_background` 的进程属于 runtime；runtime 释放即终止。Agent 若需要“下次还在跑”，必须写 `start.sh`，不要假设进程会活过休眠。

**服务 / dev server：**

- 首版不提供端口发布；dev server 只在 sandbox 内可访问，用于本地测试（curl / playwright）。
- 不提供 systemd / supervisord；Agent 可用 `nohup`/`setsid`，并把启动命令写进 `start.sh`。
- 需要 GUI 的 Electron 运行需要 Xvfb；镜像已安装，但不保证所有图形依赖；验收以 `npm test`（headless）为主。

### 4.6 bootstrap、start 与 notes

**三个阶段：**

| 脚本 | 运行时机 | 幂等要求 | 超时 | 失败语义 |
|---|---|---|---|---|
| `bootstrap.sh` | 卷**首次** provision 后，仅一次 | 必须可重复执行（用 marker 判断） | 默认 30 min，可配 | 失败 → `runtime_status=ERROR`，卷保留；用户可重试，不自动重建卷 |
| `start.sh` | **每次** wake 后、READY 前 | 必须可重复执行；不要重复克隆/安装 | 默认 10 min，可配 | 失败 → `runtime_status=ERROR`；卷保留；提示查看日志 |
| `stop.sh`（P2） | sleep 前 | 尽力而为 | 60s | 失败不阻止 sleep，只记录 |

**执行方式：**服务端在 runtime READY 后，通过 runtime `/execute` 运行 `bash /workspace/.core-ai/bootstrap.sh`（带 `WORKSPACE_*` env）。不需要新增 runtime 端点；但需要：
- **bootstrap 必须走 async**：同步 `/execute` 的 bash 上限是 600s（附录 B C10），clone + `npm ci` 很容易超过。服务端用 `run_in_background=true` 提交，轮询 `/tasks/{id}`，总超时由 `bootstrap_timeout_seconds` 控制（默认 1800s），并把 stdout/stderr 写入 `/workspace/.core-ai/logs/bootstrap-<ts>.log`。runtime 的任务保留期当前是 30 分钟（附录 B C21），需要为 workspace bootstrap 提高到至少 2 小时，或由服务端在轮询期间续期。
- 在 workspace 记录里保存 `bootstrap_completed_at` / `bootstrap_script_hash`；hash 变化不自动重跑，提供“重新运行 bootstrap”操作；
- 失败时不要把 runtime 标 READY；保留 runtime 供诊断（用户可以开终端看日志），但标记 ERROR，禁止工具调用，直到重试或重置。
- `start.sh` 默认也用 async，但通常应很短；超时默认 600s，失败同样阻断 READY。

**AGENTS.md / NOTES.md：**

- 平台在首次 provision 时写入一个模板 `/workspace/AGENTS.md`，说明目录、env、测试命令、禁止事项；Agent 可以编辑。repo 自己的 `AGENTS.md`（例如 `/workspace/repo/AGENTS.md`）由项目维护，Agent 应同时读取；平台不覆盖项目文件。
- `/workspace/.core-ai/NOTES.md` 用于 Agent 记录“这个环境装了什么、怎么跑测试、已知坑”；每次会话开始时提示 Agent 先读 `AGENTS.md` 和 `NOTES.md`。
- 这是“环境记忆”的低成本实现：不依赖模型记忆，也不依赖平台解析；下一个人/Agent 打开工作区就能接手。

### 4.7 明确“不持久”的清单

必须写进产品文档和 Agent 提示词，避免用户误判：

1. `/tmp`、`/skill`、容器根文件系统、`apt` 安装、系统服务。
2. 运行中的进程、后台任务、dev server、watch、socket、内存缓存。
3. Shell 的 `cd`、`export`、history（除非 Agent 自己写文件）。
4. 未写入卷的任何编辑器临时文件。
5. Secret 的明文（只保留引用；轮换后旧值失效）。
6. 没有 commit/push 的 Git 历史在卷丢失时无法恢复（卷本身是单点）。

### 4.8 场景走查：开发 core-ai-desktop 的 Agent

**环境：**镜像已含 Node 24、npm、git、gh、Chromium、Xvfb；`core-ai-desktop` 是 Electron + TypeScript，测试命令 `npm test`（build + `node --test`）。

**首次对话：**

1. 服务端创建 `desktop` Workspace（32 GiB，2 CPU / 4 GiB，network on）。
2. `bootstrap.sh`（Agent 作者提供）：
   ```bash
   set -euo pipefail
   if [ ! -d /workspace/repo/.git ]; then
     git clone --branch main <repo-url> /workspace/repo
   fi
   cd /workspace/repo
   npm ci
   ```
3. `start.sh`：
   ```bash
   set -euo pipefail
   cd /workspace/repo
   # 可选：拉起 watch / dev server；测试型 Agent 可以什么都不做
   ```
4. Agent 收到系统提示：工作区路径、repo 路径、测试命令、env 文件位置、长任务用 background。

**迭代：**

- Agent 改 `src/**.ts`，运行 `npm run build` / `npm test`（后台任务 + 轮询），读失败日志，继续改。
- `node_modules` 和 npm 缓存在卷上，第二次运行不需要重装。
- 用户关闭会话去吃午饭；Workspace 保持 warm 15 分钟后休眠，卷保留。
- 用户回来继续；wake 后 `start.sh` 执行，Agent 读 `NOTES.md` 恢复上下文。

**部署：**

- 服务端滚动发布，旧 Pod 上的 Sandbox 可能被删除；卷在 Azure Disk 上不受影响。
- 新 Pod 第一次工具调用：lease → 发现旧 binding → 旧 Sandbox 不在 → 创建新 Sandbox 挂同一个 PVC → start.sh → 继续。
- 如果旧 Sandbox 还在且健康：直接 attach，不重建，不丢进程（如果 Pod 还在）。

**这就是用户要的“我只负责对话”。**

## 5. 存储与运行时设计

### 5.1 主存储选择：为什么是持久卷，不是 tar 快照，也不是常驻机器

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. 扩展现有 per-session tar 快照，key 改成 workspace | 复用 Blob / epoch / 清理；改动小；跨 provider | 500 MB 压缩包上限、10,000 文件、2 GB 解压上限（附录 B C04）；每次 sleep/wake 全量打包/下载；部署中 capture 可能来不及（v1 已知限制）；无进程存活；同一 workspace 不能并发 | **不作主存储**；可作为 P2 冷归档 |
| B. 持久卷（PVC / host dir），Sandbox 按需挂载 | 随机读写、低延迟、大容量；与 Pod 生命周期解耦；部署/崩溃不丢；`node_modules` / 构建缓存可用 | 需要 K8s 存储与 RBAC；Azure Disk 单节点、zonal、挂载 10–30s；节点磁盘挂载数有限；不能跨节点共享 | **主存储** |
| C. 每个用户一台常驻 VM / 长生命周期 Pod | 进程可长期存活；体验最好 | 成本与运维随用户数线性增长；节点维护/升级复杂；安全面大；不适合平台化 | 不作默认；未来“always-on workspace”独立档位 |
| D. Git 作为唯一真相，环境每次重建 | 简单、可审计 | 只保存代码；依赖、构建缓存、未提交实验、数据库都不在；重建慢 | 补充手段，不是替代 |
| E. Azure Files / NFS（RWX） | 多 Pod 可挂载；无 zone 绑定 | 小文件 IO 慢（node_modules / git）；POSIX 锁与 mmap 语义弱；仍需 lease 防并发写 | P2 评估“共享 Workspace”时再用 |

**结论：**Workspace 的持久性由块存储卷承担；tar 归档只用于冷备份/迁移（P2）。计算按需唤醒，卷常驻。这样同时满足 S2（部署不丢工作）与 S3（不为每个用户常驻 CPU）。

**如果 PVC 被平台阻塞（fallback，不推荐作为目标）：**可以做一个“snapshot-backed workspace”试点：把现有 snapshot 的 key 从 session 改成 workspace，扩大 caps（例如 5 GB / 100k 文件），sleep 前 capture、wake 后 restore。它能提供“文件尽量还在”，但仍有：capture/restore 慢、部署窗口可能来不及 capture、无进程存活、并发受限。它不能替代卷；只能作为 PVC/RBAC 未就绪时的临时验证，且必须在 UI 明确标注“可能丢失最近未 capture 的更改”。

**成本直觉：**

- 一个 32 GiB 的卷按月计费，远低于 2 CPU / 4 GiB 常驻一个月的成本。
- 默认空闲 15 分钟休眠，一个用户一天工作 8 小时，实际计算时间约 8 小时 + 唤醒开销，而不是 24 小时。
- 卷是长期成本，必须配额；计算是短期成本，必须休眠。

**延迟直觉：**

- Warm：工具调用几乎无额外延迟。
- Cold wake：PVC 绑定/挂载 10–30s（Azure Disk，需 dev 压测确认）+ Pod 调度/启动 + runtime 健康检查 + `start.sh`，通常 20–60s。
- 因此策略是“工作期间保持 warm，离开后休眠”，而不是每次 turn 都重建。

### 5.2 Kubernetes 实现：静态 PVC + 直接 Sandbox CR

**核心决策：Workspace 使用“预先创建、按名字引用的静态 PVC”，不使用 `volumeClaimTemplates`。**

原因：

1. `volumeClaimTemplates` 创建的 PVC 归 Sandbox 所有，删除 Sandbox 时 PVC 会被 GC（上游文档明确；附录 B C18）。而我们的 sleep 语义是“删除 Sandbox、保留卷”，两者冲突。
2. 静态 PVC 的创建/删除由 server 显式管理，生命周期与 Sandbox 解耦；Sandbox 只是“借用”卷。
3. 静态 PVC 可以被任何新 Sandbox 按名字重新挂载，不依赖 controller 的 StatefulSet 语义。
4. **静态 PVC 不能设置 ownerReference 指向 Sandbox / claim**；否则删除 Sandbox 时 K8s GC 会连带删除卷。PVC 只由 WorkspaceService 管理生命周期。

**形态：**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ws-<workspace-short-id>
  namespace: dev-ai
  labels:
    core-ai/component: workspace
    core-ai/workspace-id: <id>
    core-ai/user-id: <sanitized-user>
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: managed-csi
  resources:
    requests:
      storage: 32Gi
```

```yaml
apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: ws-<short>-<runtime-generation>
  labels:
    core-ai/component: workspace
    core-ai/workspace-id: <id>
    core-ai/user-id: <sanitized-user>
    core-ai/runtime-generation: "<n>"
spec:
  shutdownTime: <now + maxRuntimeSeconds + grace>
  podTemplate:
    spec:
      containers:
        - name: sandbox
          image: <workspace image>
          env:
            - name: WORKSPACE_DIR
              value: /workspace
            - name: HOME
              value: /workspace/home
            # ... 其他 env
          volumeMounts:
            - name: workspace
              mountPath: /workspace
            - name: tmp
              mountPath: /tmp
            - name: skill
              mountPath: /skill
      volumes:
        - name: workspace
          persistentVolumeClaim:
            claimName: ws-<workspace-short-id>
        - name: tmp
          emptyDir: { sizeLimit: 10Gi }
        - name: skill
          emptyDir: { sizeLimit: 50Mi }
```

**说明：**

- 生产当前是 agent-sandbox controller `v0.3.10` / `v1alpha1`；`Sandbox.spec.volumeClaimTemplates` 在 v0.3.10 已存在，但我们不需要它。`podTemplate.spec.volumes[].persistentVolumeClaim` 是标准 Pod spec；**[待验证]** v0.3.10 的 CRD 是否原样透传静态 PVC 引用，dev 必须先验证，若不支持则退回 `volumeClaimTemplates` + 不删除 Sandbox 的方案（见下）。
- 直接 CR 不走 warm pool，因此冷启动；Workspace 本来就按工作时段保持 warm，冷启动只在每天第一次或休眠后发生。
- `shutdownTime` 作为兜底：即使 server 没来得及 sleep，K8s 也会在 `maxRuntimeSeconds + 600s` 后删除 Sandbox；静态 PVC 不受影响。
- 不给 workspace Sandbox 设置 `operatingMode`（v1beta1 才有）；P3 再评估 suspend。
- 安全上下文：首版沿用现有 warm pool template 的姿态（root、可写根文件系统），因为镜像与 OCG/skill 依赖尚未做非 root 回归（附录 B C19）；注意这与 `KubernetesPodSpecBuilder` 的独立 Pod 路径（`readOnlyRootFilesystem=true`）不同，Workspace 用的是直接 CR。Workspace 的隔离主要靠“独立 Pod + 独立 PVC + 每用户所有权 + 网络策略”；把 `runAsNonRoot` / `readOnlyRootFilesystem` 列入 P2 安全加固，不能在本特性里静默改变运行姿态。无论哪种姿态，都必须显式设置 `automountServiceAccountToken: false`，且不要让 Workspace Pod 拿到集群 API 权限。
- 如果后续用 `operatingMode: Suspended`：Sandbox 对象保留、Pod 删除、PVC 由 Sandbox 持有，resume 更快；但需要 v1beta1、TTL 重算、状态机与 attach 改造。首版不做；静态 PVC + 删除 Sandbox 的语义更简单、更稳。

**资源规格必须独立于 chat sandbox。**现有 `SandboxConfig.validate()` 把内存钳到 `MAX_MEMORY_LIMIT_MB=2048`、CPU 钳到 `MAX_CPU_LIMIT_MILLICORES=2000`（附录 B C10）；core-ai-desktop 的 `npm ci` + Electron 构建在 2 GiB 下很容易 OOM。建议：

| Profile | memory | cpu | 用途 |
|---|---|---|---|
| `workspace-small` | 2 GiB | 1 | 轻量脚本 / 数据 |
| `workspace-default` | 4 GiB | 2 | 默认开发 |
| `workspace-build` | 8 GiB | 4 | Electron / Gradle / 大型构建 |

实现上新增 `WorkspaceProfile`（不塞进 `SandboxConfig` 的 chat 默认值），配额按 profile 预留；如果部署暂时只能提供 2 GiB，必须在 UI 明确标注，不能让用户以为可以构建。上限与节点容量由管理员配置，不允许 Agent 作者任意填写超大值。

**用量采样：**runtime 新增 `GET /workspace/stat`（基于 `statfs`，O(1)），返回 used/capacity/inodes；server 在 READY 和每 N 分钟采样，写 `workspace_usage`。`du` 只在用户显式点击“计算目录大小”时异步执行，不能每次工具调用都跑（大卷会很慢）。`df -h` 仍可由 Agent 随时执行。

**为什么不用 warm pool：**

- claim 只能引用 template + warm pool，不能为每个 workspace 指定不同的 PVC 名称。
- template 级 `volumeClaimTemplates` 会为每个 pool 成员预建 PVC，但 PVC 与 Sandbox 绑定，删除 Sandbox 会 GC；且 pool 成员的 PVC 无法对应到某个用户的 Workspace。
- 结论：Workspace 是“少量、长期、按用户”的对象，适合直接 CR；普通聊天是“大量、短命”，继续走 warm pool。两类负载分开，互不拖累。

### 5.3 卷的生命周期与 K8s 运维约束

**创建：**

1. `WorkspaceProvisioner.create(workspaceId, sizeGb)` 幂等：先 GET PVC；不存在则 POST；已存在则校验 labels/size。
2. 等待 `status.phase=Bound`，超时 `provisionTimeoutSeconds`（默认 300s）。
3. 写入 `WorkspaceVolume` 记录（pvc_name、size、storage_class、generation、status=READY）。
4. 失败：记录 ERROR，保留记录供重试；如果 PVC 已创建但 Bound 失败，重试或删除（删除需确认没有数据）。

**删除：**

1. 先释放 runtime（删除 Sandbox CR / Pod），等待 Pod 终止，确保没有挂载。
2. 删除 PVC；等待消失。
3. 删除 Workspace 记录（或标记 DELETED）。
4. 失败：标记 DELETING，由 reconciler 重试；不能留下“记录已删但 PVC 还在”的孤儿。
5. 确认 StorageClass 的 `reclaimPolicy=Delete`；如果是 `Retain`，删除 PVC 只会留下 Released PV / 云盘，需要额外的孤儿清理，否则存储会持续计费。

**Azure Disk 约束：**

| 约束 | 影响 | 应对 |
|---|---|---|
| RWO、单节点挂载 | 同一 workspace 不能同时被两个 Pod 挂载 | 单写者 lease + RWO 双保险 |
| zonal | 卷在哪个 zone 创建/首次挂载后固定；后续 Pod 必须调度到同 zone | StorageClass `volumeBindingMode: WaitForFirstConsumer`；节点池保留多 zone 容量；容量不足时明确报错 |
| 每节点可挂载磁盘数有限 | 一个节点能同时运行的 Workspace 数有上限 | 专用 node pool；`maxRunningWorkspacesPerNode` 配置；超限时调度失败要转成可读错误 |
| 挂载 10–30s | 冷启动慢 | 工作期间保持 warm；UI 显示进度；不要每次 turn 重建 |
| 扩容需要 PVC patch + 文件系统 resize | 首版不做在线扩容 | 新建更大 Workspace + 迁移；P2 再做 |
| 单盘故障/zone 故障 | 卷可能暂时不可用 | Azure Disk 自身副本；P2 归档/快照；明确告知用户“卷是单点，重要工作请 push” |

**fsGroup / 权限：**

- 如果沿用 root 运行：卷内文件由 root 创建，runtime 也是 root，读写一致；不需要 fsGroup。
- 如果 P2 改成非 root：Pod 需 `securityContext.fsGroup: 1001` + `fsGroupChangePolicy: OnRootMismatch`，并且首次挂载可能对大卷做递归 chown（很慢）；需要迁移脚本和压测。
- 首版必须在文档里写明运行用户，避免“今天 root、明天 1001”导致文件不可写。

### 5.4 Docker / 本地开发的等价实现

`DockerSandboxProvider` 现在把宿主机 `workspaceBase` 只读挂到 `/workspace`（附录 B C13），不能写。Workspace 模式增加：

- 宿主机目录 `<workspaceBase>/<workspaceId>`，rw 挂载到 `/workspace`；
- 容器 `WorkingDir=/workspace`，`WORKSPACE_DIR=/workspace`；
- 删除 Workspace 时删除该目录（必须校验路径在 `workspaceBase` 下，防止路径穿越）；
- 其余行为（env、HOME、脚本）与 K8s 一致。

**定位：**Docker 只用于本地开发和测试，不作为生产多租户后端；生产以 K8s 为准。两者共享同一套 `WorkspaceProvisioner` 契约和验收用例（文件、env、sleep/wake、删除）。

### 5.5 归档与恢复（P2，不在首版）

**目标：**在删除 Workspace 前、或长期不用时，把 `/workspace` 打包到 Blob，支持恢复到一个新卷；也用于灾备。

**设计要点：**

- 新增 runtime `POST /workspace/archive`（只打包 `/workspace`，不打包 `/tmp` / `/skill`），流式 tar.gz，支持远大于 500 MB 的包；manifest 含 sha256、size、fileCount、workspaceId、volumeGeneration、image。
- 归档期间 Workspace 必须 quiescent（无 turn、无 async、无终端）；归档不阻塞正常工具调用太久，可作为后台任务。
- Blob key：`workspace/{userId}/{workspaceId}/{generation}/{archiveId}.tar.gz`；私有 container；保留策略独立于 session snapshot。
- 恢复：创建新 PVC → 启动临时 Sandbox 挂载 → `POST /workspace/restore` → 校验 → 进入 READY；失败不能覆盖已有卷。
- 归档不是“时间点恢复”的完整备份：它不包含运行中进程、未 flush 的页缓存；必须明确。
- 首版不做归档；删除是软删除 7 天，期间可以恢复卷（如果尚未 purge）；超过保留期即不可恢复。这是首版的已知限制。

## 6. 服务架构与关键流程

### 6.1 组件与职责

| 组件（拟新增） | 责任 | 不应承担 |
|---|---|---|
| `WorkspaceService` | CRUD、所有权、配额、软删除、审计、默认 Workspace 解析 | 不直接创建沙箱、不执行工具 |
| `WorkspaceStore` / `WorkspaceLeaseStore` | PG 持久化、CAS、租约、binding、活动时间 | 不做 provider 调用 |
| `WorkspaceProvisioner` | 卷的创建/删除/查询；K8s 与 Docker 实现 | 不管理 Sandbox 生命周期 |
| `WorkspaceRuntimeService` | 按 workspaceId 的 runtime attach/wake/sleep、turn lease、hub binding、in-memory registry | 不解析 Agent 配置、不做权限判断 |
| `WorkspaceSandboxHandle` | 实现 `Sandbox`，把工具调用委托给共享 runtime；按 session 隔离附件/MCP/事件 | 不拥有卷、不决定生命周期 |
| `WorkspaceBootstrap` | 运行 bootstrap/start 脚本、日志、hash、失败状态 | 不修改用户文件（除 `.core-ai` 初始化） |
| `WorkspaceSleeper` | 周期扫描空闲 Workspace，释放 runtime，保留卷 | 不删除卷、不中断活跃 turn/async/terminal |
| `WorkspaceReconciler` | 对账 PVC / Sandbox / DB / binding；清理孤儿；修复状态 | 不猜测数据、不静默重建丢数据的卷 |
| `WorkspaceActivity` | 汇总 turn / async / terminal 活动 | 不作为强一致计费依据 |
| `WorkspaceArchiveService`（P2） | 归档/恢复、Blob、保留策略 | 不替代卷的日常读写 |

**依赖方向：**API → WorkspaceService → Store；WorkspaceService → RuntimeService → Provisioner / SandboxService；Sleeper/Reconciler → RuntimeService + Store。不要反过来让 SandboxService 依赖 WorkspaceService 的具体实现，避免循环依赖；通过接口/回调注入。

### 6.2 首次 provision

```text
Session created (agent has persistentWorkspace)
  → WorkspaceService.resolveOrCreate(userId, agentId, requestedWorkspaceId?)
      → quota check
      → insert Workspace(volume_status=PROVISIONING)
      → return workspaceId
  → ChatSession.workspaceId = workspaceId
  → WorkspaceSandboxHandle created (no runtime yet)

First tool call / turn start
  → WorkspaceRuntimeService.acquireTurn(workspaceId, sessionId)
      → ensureVolume():
          if no volume: Provisioner.create() → wait Bound → volume_status=READY
      → ensureRuntime():
          if local runtime: reuse
          else if binding: attach (validate generation/health)
          else: create Sandbox with PVC
      → wait runtime READY
      → Bootstrap.runIfNeeded(): bootstrap.sh (first) + start.sh (every wake)
      → bind hub to session
  → execute tool
```

**关键点：**

- 卷创建与 runtime 创建都在**第一次工具调用**时发生，不在 `POST /api/sessions` 里阻塞；会话创建保持快。UI 可以在会话创建后预取（后台 warm），但不能让创建请求等待 60s。
- **会话创建与 Workspace 创建不是分布式事务。**如果会话创建在 Workspace 记录之后失败，Workspace 记录保留（归用户所有，可在 Settings 删除），唯一约束保证下一次不会创建同名重复 Workspace；不因为它没有会话就自动删除卷（可能已经在后台 provision）。
- 首次 `bootstrap.sh` 可能很久（clone + npm ci）；它属于 turn 的前置工作，会占用 turn 时间。UI 必须显示“正在初始化工作区（首次可能几分钟）”，并允许后台继续（用户离开页面不取消）。
- 如果 bootstrap 失败：runtime 保留、状态 ERROR、本次 turn 失败；用户可以在终端查看日志、重试。不要自动删除卷（可能已经 clone 了一半，但删除更糟）。
- `start.sh` 每次 wake 都跑；如果它很慢，会拖慢每个 turn。Agent 作者应把重活放 bootstrap，start 只做必要拉起。

### 6.3 Turn 执行与 attach

```text
Turn start
  → lease = WorkspaceRuntimeService.acquireTurn(workspaceId, sessionId, turnId)
  → runtime = ensureRuntime(...)
  → hubBinding.bind(runtime, sessionId, userId)   // 单绑定，指向当前 turn
  → execute tools via WorkspaceSandboxHandle
  → if async tasks started: keep lease until terminal
Turn end
  → release lease (if no async)
  → update lastActivity
  → schedule sleep timer (not immediate)
```

**租约与生命周期钩子：**

- 在 `WorkspaceLifecycle.beforeAgentRun` 获取 lease，在 `afterAgentRun` / 异常 / 取消 / 会话关闭路径的 `finally` 中释放；不能只在成功路径释放。
- 租约按 `(workspaceId, sessionId, turnId)` 可重入：同一 turn 的 subagent 共享父租约，不能各自抢租约导致自锁。
- 如果 turn 被用户中断但后台任务仍在跑，租约保持到任务终态；`WorkspaceSleeper` 通过 `WorkspaceActivity` 看到该信号。
- 服务端崩溃时租约靠 TTL 过期；新 Pod 接管时按 §3.4 的 fencing 处理，不能假设旧 turn 会自己释放。

**Hub binding 的时序：**

- 现有 runtime 的 `/bind` 是单绑定；Workspace 模式在**每次 turn 获取 lease 后**重新 bind 到当前 session。
- 如果另一个 session 正在执行 turn，bind 不会发生（lease 拿不到）。
- 终端不改变 binding；终端里的脚本调用 hub 会使用当前绑定的 session 身份。这是可接受的：终端是同一用户，且 UI 提示当前 Agent 会话。如果没有 turn 绑定（例如冷启动后直接开终端），终端脚本调用 hub 会得到 not_bound；需要先发一条消息或显式 wake。
- 后台异步任务在 turn 结束后仍持有 lease，binding 不变；任务结束后释放。
- MCP 启动 / 附件 staging / usage 采样不获取 turn lease；它们不得改写用户文件，且必须能被下一个 turn 安全覆盖/重试。

**事件：**

- `SandboxEvent` 继续按 session 发送；Workspace 模式在 `CREATING` 的 message 里带 “waking workspace”，`READY` 的 message 里带 “workspace ready (cold start 32s)”。
- 新增（提案）`WorkspaceEvent`：`workspaceId`、`state`、`message`、`usageBytes`、`capacityBytes`。前端在会话头部显示 Workspace chip；没有事件时回退到 session 详情里的 workspace 摘要。
- 不要因为 workspace 事件而改变现有 sandbox 事件的前端渲染契约；两者可以并存。

### 6.4 休眠

```text
WorkspaceSleeper (every 1 min)
  for workspace in RUNNING:
    if hasActiveTurn(workspace) continue
    if hasPendingAsyncTask(workspace) continue
    if hasActiveTerminal(workspace) continue
    if now - lastActivity < idleSleepMinutes continue
    if now - runtimeStartedAt < minRuntimeMinutes continue   // 防止刚创建就睡
    try:
      lease = tryAcquire(workspaceId, holder=sleeper)
      release runtime (delete Sandbox CR / container)
      binding.delete / runtime_status=NONE
      volume_status stays READY
    catch: log + retry next cycle
```

**规则：**

- `idleSleepMinutes` 默认 15，范围 5–240；Agent 可配置。
- `minRuntimeMinutes` 默认 5，避免“创建后立刻睡”导致抖动。
- 休眠不 capture 快照；不写 `/workspace`；不删除卷。
- 休眠失败（K8s API 错误）不改变卷状态；下次重试；runtime 可能仍活着，下一次 wake 会 attach 而不是重建。
- 休眠前可选执行 `stop.sh`（P2）；首版直接释放，后台进程随 Pod 终止。
- 服务端部署 / 关闭：不在 shutdown 钩子里 capture；只清理本 Pod 的句柄。Workspace runtime 由 K8s 保留或由新 Pod attach/sleep。
- **最长运行时间**：`runtimeStartedAt` 超过 `maxRuntimeHours` 时，不打断进行中的 turn；标记 `runtime_expired`，在本 turn 结束/空闲时立即 sleep，下一次 turn 重建。防止一个忘记关闭的 Workspace 连续占用 CPU 数天。
- **会话关闭 vs 后台任务**：关闭会话不自动取消 in-flight async task（用户可能只是关掉聊天页）；lease 保持到任务终态，或 `asyncOrphanTimeout`（例如 2h）后取消任务并释放。删除会话则显式取消该会话的 async task 并释放 lease。这样既不会让长构建被误杀，也不会让一个已删除会话永久占住 Workspace。

**为什么不做 capture-on-sleep：**卷已经在磁盘上，capture 只会把同样的数据再复制到 Blob，浪费 IO 和存储；而且 2 GB 上限会失败。卷的持久性不需要额外的“保存”动作。

### 6.5 删除与重置

**删除（软删除）：**

1. 校验 owner；标记 `deleted_at`；撤销所有 lease；禁止新 turn；删除该 Workspace 的 Secret 引用（Secret 本体延迟到 purge，避免正在运行的 runtime 立即失效）。
2. 若有 runtime，先释放（删除 Sandbox）；软删除阶段**保留卷** 7 天，允许恢复；期间不计入“活跃”但计入存储配额。
3. 7 天后（`purgeAfterDays` 可配）由 `WorkspaceReconciler` 删除 PVC + 记录 + 归档（P2）。
4. 用户可选择“立即永久删除”，需输入 Workspace 名称确认。

**重置：**

1. 双重确认（输入名称）。
2. 释放 runtime；递增 `volume_generation`；删除旧 PVC（或先归档）；创建新 PVC；状态 PROVISIONING → READY。
3. 现有会话的 `workspace_id` 不变，但 generation 变化；下一次 turn 会拿到空环境；UI 明确提示“工作区已重置，原有文件不可恢复”。
4. 审计记录 reset 操作者、时间、旧/新 generation。

**删除 vs 重置：**删除是“不要这个环境了”；重置是“我还要这个 Agent/会话，但环境要干净”。两者都不能静默发生。

**Undelete（7 天内）：**只撤销 `deleted_at`，把卷状态恢复为 READY（cold），不重建卷、不自动唤醒；如果 PVC 已被 purge，返回 `410 WORKSPACE_DELETED`。Undelete 不恢复已删除的会话；会话需要重新创建并绑定同一 Workspace。

### 6.6 与现有 SandboxService / LazySandbox 的集成点

| 现有代码 | 现状 | Workspace 模式需要的改动 |
|---|---|---|
| `SandboxService.sessionSandboxes`（附录 B C01） | sessionId → Sandbox | 新增 `WorkspaceRuntimeService` 的 workspaceId → runtime；session 句柄委托，不把 workspace runtime 放进 `sessionSandboxes` 的普通 TTL 管理 |
| `SandboxService.createSessionSandbox` | 每个 session 创建 LazySandbox | workspace 会话返回 `WorkspaceSandboxHandle`；非 workspace 保持原逻辑 |
| `LazySandbox.ensureReady`（C03） | acquire → restoreSnapshot → postAcquire → READY | workspace 句柄不用 LazySandbox 的 snapshot 逻辑；如需复用，必须显式禁用 restore |
| `SandboxSnapshotService.captureBeforeRelease`（C04） | release 前 capture `/tmp`、`/skill`、`/workspace` | workspace 会话直接跳过；否则会 tar 整个卷并失败 |
| `SandboxReleaseSupport.shutdown`（C17） | 关闭所有非 persistent session 的 sandbox | workspace 句柄不能在这里释放共享 runtime；runtime 由 K8s/sleeper 管理 |
| `SandboxManager.cleanupExpired`（C08） | 按 config.timeoutSeconds 释放 | workspace runtime 使用独立的长 TTL；普通 session 的 TTL 不适用 |
| `SandboxEvictionPolicy`（C08） | 用户配额满时驱逐空闲 session | 不驱逐 workspace runtime；Workspace 有自己的 LRU sleep/配额 |
| `SandboxCleanupJob`（C09） | 每 5 分钟清理 + K8s 孤儿 | 需要识别 workspace label，不按 1800s fallback 删除；交给 WorkspaceReconciler |
| `SessionRebuildManager`（C16） | 按 session 的 sandbox_bindings reattach | workspace 会话按 workspaceId 恢复，不按旧 session binding |
| `SandboxFileService` / `SandboxAttachmentPath`（C12） | `/workspace/attachments/<name>` | workspace 模式改为 `/workspace/attachments/<sessionId>/<name>`；避免多会话覆盖 |
| `SessionMcpProcesses` | 每 session 启动/停止 MCP | 可复用；停止只停该 session 的 MCP，不能停别人的 |
| `SandboxTerminalService`（C14） | 按 session 解析 runtime | workspace 会话解析到共享 runtime；**冷 Workspace 打开终端前先 wake**；ticket 增加 workspaceId；终端活动更新 Workspace 活动 |
| `SandboxHubBinding` | 每个 runtime 单绑定 | 每次 turn 重新绑定；lease 保证不会交叉 |
| `AgentSessionManager.cleanupRuntime`（C02） | capture + release session sandbox | workspace 会话：跳过 capture；释放句柄/lease；不释放 runtime |
| `RunSandboxReleaseScheduler`（C24） | run 结束后 60s release `runId` sandbox | workspace 只用于 chat session；`releaseSandbox` 对 workspace key 必须是 no-op/仅 detach，不能释放共享 runtime |
| `SandboxFfmpegRunner`（C25） | 用 `plan.jobKey()` 创建独立 sandbox | v1 不接入 Workspace；媒体渲染仍在临时 sandbox，需在文档说明 |
| `OcgSandboxService`（C26） | 按 config.id 维护 OCG 常驻 sandbox | 与 Workspace 是两套持久化；v1 不合并，避免生命周期冲突 |
| `SessionShare`（C27） | 只读投影，匿名访问 | 不授予 Workspace 文件/工具权限；分享页只显示状态摘要，不执行工具 |
| `SandboxFileService.restoreAttachments` | 按 snapshotCreatedAt 决定是否覆盖 | workspace 没有 session snapshot，跳过该时间比较；附件按 session 子目录写入 |
| Agent comparison（C28） | 两个会话做 A/B | workspace 必须各自独立或禁用；不能共享，否则互相干扰 |
| `AgentSandboxCleanupService`（C09） | 按 1800s fallback 清理 K8s 孤儿 | 必须识别 `core-ai/component=workspace`，用 workspace 的 `maxRuntimeSeconds + grace`，不能被 1800s 误删 |
| `SessionActivityRegistry`（C29） | 跨 Pod 的会话活动 | terminal 活动应更新 Workspace lastActivity，阻止休眠 |
| `SandboxManager` 配额（C08） | per-pod/per-user sandbox 计数 | workspace runtime 用独立配额桶；不能把普通聊天挤掉，也不能被普通聊天挤掉 |

**实现建议：**

- 不要修改 `Sandbox` 公共接口的语义；新增 `WorkspaceSandboxHandle implements Sandbox`，内部持有 `WorkspaceRuntime` 引用和一个 `Sandbox` delegate。
- **`WorkspaceSandboxHandle.close()` 只 detach 会话，不 release 共享 runtime**：停止该会话的 MCP 进程、清理 session 暂存、释放它持有的 lease；runtime 继续 warm，由 sleeper 管理。`SandboxService.releaseSandbox(sessionId)` 对 workspace 会话必须走这条路径。
- `WorkspaceRuntime` 内部可以复用 `LazySandbox` 的 acquire/replace/status 逻辑，但必须注入 `snapshotService=null`，并覆盖 release 语义（不 capture）。
- `SandboxService.getSandbox(sessionId)` 返回 session 的 handle；`SandboxService.getSandboxId(sessionId)` 返回 runtime id（多个 session 相同）。这对终端和诊断是必要的。
- `SandboxService` 不应直接依赖 `WorkspaceService`；用一个 `SessionSandboxResolver` 接口，在 `AgentSessionManager` 创建会话时决定用哪个实现。
- **不要未加区分地把 workspace runtime 注册进 `SandboxManager.activeSandboxes`**：那里的配额、TTL、LRU 驱逐都是为普通 session 设计的，会误杀长期 Workspace。要么由 `WorkspaceRuntimeService` 自己维护 registry 并直接调用 provider，要么给 `SandboxManager` 增加独立的 workspace 分区/配额桶 + 跳过普通 TTL/eviction + 独立 renew。两种都可以，但不能“复用一半”。

### 6.7 多 Pod、跨 Pod attach 与崩溃恢复

**正常多 Pod：**

- 会话可能被任意 Pod 重建；Workspace runtime 可能由另一个 Pod 创建。
- 新 Pod 先查 `workspace_bindings`（workspaceId → sandboxId）；如果 Sandbox 存在且健康，直接 attach（provider 的 `attach` 已支持按 id 获取 IP/FQDN）。
- attach 后必须重新 `/bind` 当前 session（runtime 是单绑定；旧 Pod 的绑定可能还在内存里，但旧 token 可能已过期）。
- 如果 attach 失败（Sandbox 被删/不健康），创建新 Sandbox；卷不变。
- 跨 Pod 的 terminal 也一样：解析到 runtime IP，ticket 交给 gateway。

**崩溃 / 部署：**

1. 旧 Pod 的 lease 在 5 分钟后过期；旧 Pod 可能还活着但 GC 卡住，此时新 turn 的 acquire 会等待/接管。
2. 接管前先确认旧 Sandbox 是否还挂载卷；如果是，删除它（K8s API），等待 Pod 终止；否则新 Sandbox 会因 RWO 挂载失败。
3. 删除旧 Sandbox 会杀死旧 turn 的进程；这是可接受的：旧 turn 已经失去所有权，继续跑会与新的写者冲突。
4. 新 Sandbox 创建时 `runtime_generation++`；旧 Pod 的晚到工具结果因 generation 不匹配被丢弃，不能写回会话/卷。
5. 如果旧 Sandbox 已消失、卷可挂载：直接创建新 Sandbox；`start.sh` 重新拉起服务。

**服务器关闭：**

- `SandboxService.shutdown()` 不应删除 workspace runtime（否则每次部署都冷启动，且可能正在 bootstrap）；只清理本 Pod 的 session 句柄、MCP、临时文件。
- 由 K8s 的 `shutdownTime` 兜底；如果希望部署后保持 warm，可以给 workspace runtime 一个较长的 `shutdownTime`（例如 8h），新 Pod 起来后 attach。
- 不做 capture-on-shutdown（现有 v1 的已知限制）；Workspace 的持久性不依赖它。

### 6.8 配额与驱逐

**配额维度：**

| 维度 | 默认（提案） | 作用 |
|---|---|---|
| 每用户 Workspace 数 | 3 | 防止无限创建 |
| 每用户卷总容量 | 96 GiB | 防止存储成本失控 |
| 每用户同时 RUNNING | 2 | 防止计算/挂载打满 |
| 每 Pod workspace runtime 数 | 由节点磁盘/Nodes 决定，配置 | 防止单节点挂载超限 |
| 每用户创建速率 | 例如 5 次/小时 | 防止反复创建/删除刷 PVC |
| 平台总 PVC 数 / 容量 | 运维配置 | 容量规划 |

**驱逐策略：**

- 配额不足时，优先 sleep 该用户最久未使用的 **RUNNING** Workspace（释放计算与挂载，不删卷），再尝试创建。
- 如果所有 Workspace 都有活跃 turn/async/terminal，拒绝新请求，返回 `WORKSPACE_BUSY` / `WORKSPACE_QUOTA_EXCEEDED`，不抢占别人。
- 不驱逐其他用户的 Workspace；per-user 配额不能变成全局 free-for-all。
- 卷容量配额不能通过 sleep 释放；达到容量上限时只能删除或扩容（P2）。

### 6.9 失败语义与“不静默降级”

| 失败 | 行为 | 用户可见 |
|---|---|---|
| PVC Pending / 容量不足 | `volume_status=ERROR`，重试按钮；不创建空卷 | “工作区存储暂时不可用” + 原因 |
| PVC 丢失（记录有、卷没） | `volume_status=ERROR`；禁止工具调用；建议重置 | “工作区数据卷丢失，需要重置或联系管理员” |
| 挂载失败 / Pod 启动失败 | `runtime_status=ERROR`；卷保留；重试 | “工作区启动失败，正在重试” |
| bootstrap 失败 | runtime 保留、ERROR、日志；不自动删卷 | “初始化脚本失败” + 日志入口 |
| start 失败 | runtime 保留、ERROR；不执行工具 | “启动脚本失败” + 日志入口 |
| 配额满 | 拒绝创建/唤醒 | 明确配额数字与释放建议 |
| 旧 runtime 失联 | 接管；删除旧 Sandbox；记录中断 | “上次执行已中断” |
| 卷满 | 工具报错；`workspace_usage` 显示 | Agent 提示清理；用户可扩容（P2） |
| 镜像/工具链变化 | 允许挂载 + `ENVIRONMENT_DRIFT` 警告 | “镜像已变化，依赖可能需要重装” |

**唯一允许的“降级”**：用户显式选择“本次使用临时环境继续”（不写卷）。系统不能自动替用户做这个选择，因为没有持久化的 Agent 会在空环境里覆盖/重建用户的工作。

## 7. 数据模型、配额与 API

### 7.1 PostgreSQL 表（提案）

首版主存储 PostgreSQL；不新增 Mongo collection（与 messaging / evaluation 的现状一致，附录 B C20）。表名与列名在实施时按当时迁移号核对；本文不猜迁移号。

| 表（拟新增） | 核心字段 / 索引 | 作用 |
|---|---|---|
| `app.workspaces` | id PK、owner_user_id、name、agent_id（默认绑定，可空）、volume_status、runtime_status、image、runtime_version、size_gb、storage_class、pvc_name、mount_path、env_json、bootstrap_script、start_script、idle_sleep_minutes、network_enabled、volume_generation、runtime_generation、created_at、updated_at、last_used_at、last_provisioned_at、error_code、error_message、deleted_at | Workspace 主记录；合并展示状态由 volume/runtime 派生，不单独存储 |
| `app.workspace_volumes` | workspace_id PK、pvc_name UNIQUE、provider、size_gb、storage_class、volume_generation、status、created_at、deleted_at | 卷的独立记录，便于对账；也可合并进 workspaces，但拆分更清晰 |
| `app.workspace_bindings` | workspace_id PK、sandbox_id、provider、runtime_generation、pod_id、runtime_started_at、updated_at | 跨 Pod attach 的快路径；`runtime_started_at` 用于最长运行时间 |
| `app.workspace_leases` | workspace_id PK、holder_id、lease_token、session_id、turn_id、runtime_generation、expires_at、updated_at | turn 级单写者租约 |
| `app.workspace_events` | id、workspace_id、event_type、actor、payload_json、created_at | 审计与诊断（create/sleep/wake/reset/delete/upgrade/env） |
| `app.workspace_archives`（P2） | id、workspace_id、volume_generation、blob_key、sha256、size_bytes、file_count、created_at、expires_at | 冷归档 |
| `app.workspace_usage`（可选） | workspace_id PK、used_bytes、capacity_bytes、inodes_used、inodes_total、sampled_at | 用量快照，避免实时 `df` 阻塞 UI |

**字段权威（避免双写漂移）：**`workspace_volumes.status / pvc_name / size_gb` 是卷的权威；`workspaces.volume_status` 是它的派生缓存（列表查询用）；`workspaces.runtime_status` 是运行时权威；`workspace_bindings` 是当前 runtime 指针的权威；`workspace_leases` 是执行权权威。Reconciler 以权威字段修复缓存字段，不能让两个来源各自独立演进。

**索引：**

- `workspaces(owner_user_id, last_used_at DESC) WHERE deleted_at IS NULL`：用户列表。
- `UNIQUE (owner_user_id, name) WHERE deleted_at IS NULL`：同一用户的 Workspace 名唯一，默认 Workspace 并发创建靠它 + `ON CONFLICT` 重试。
- `workspaces(volume_status, updated_at)` / `workspaces(runtime_status, last_used_at)`：sleeper/reconciler 扫描。
- `workspace_leases(expires_at)`：过期接管。
- `workspace_volumes(pvc_name)` 唯一：防止重复卷。
- `workspace_events(workspace_id, created_at DESC)`：审计查询。

**类型约束：**

- 枚举在 persistence 与 API view 分离（core-ng 约束）；PG 列用 `TEXT` + 明确取值，Java DTO 用独立 enum + `@Property`。
- 动态 env / payload 用 `String` JSON text（`JsonUtil`），不用 `Map<String,Object>` / `Object` / BSON Document。
- 必填默认字段加 `@NotNull`；时间统一 `TIMESTAMPTZ`。

**`chat_sessions` 增加：**

- `workspace_id TEXT NULL`：会话绑定的 Workspace；`NULL` 表示临时环境。
- `workspace_generation BIGINT NULL`：创建时的 generation，用于诊断“会话是否跨过了 reset”。
- 索引 `(workspace_id) WHERE workspace_id IS NOT NULL`：列出某 Workspace 的会话。

**不把 Workspace 放在 Mongo 的理由：**Workspace 需要租约 CAS、配额统计、对账查询；PG 的事务/唯一约束/条件更新比 Mongo 文档更适合。聊天历史仍按现有存储，不迁移。

### 7.2 系统设置与配额

新增 `SystemSettings` 键（命名沿用现有 `sys.*` 风格）：

| 键 | 默认（提案） | 说明 |
|---|---|---|
| `sys.workspace.enabled` | false | 总开关；关闭后不能创建/唤醒，只能读取/删除 |
| `sys.workspace.storageClass` | `managed-csi` | K8s StorageClass |
| `sys.workspace.defaultSizeGb` | 32 | 新建 Workspace 默认容量 |
| `sys.workspace.maxSizeGb` | 100 | 单 Workspace 上限 |
| `sys.workspace.maxPerUser` | 3 | 每用户 Workspace 数 |
| `sys.workspace.maxTotalGbPerUser` | 96 | 每用户卷总容量 |
| `sys.workspace.maxRunningPerUser` | 2 | 同时 RUNNING 数 |
| `sys.workspace.idleSleepMinutes` | 15 | 默认空闲休眠 |
| `sys.workspace.minRuntimeMinutes` | 5 | 最短运行时间，防抖 |
| `sys.workspace.maxRuntimeHours` | 8 | 连续运行上限；到点后只在下个 turn 边界回收 |
| `sys.workspace.provisionTimeoutSeconds` | 300 | PVC Bound 超时 |
| `sys.workspace.wakeTimeoutSeconds` | 180 | Sandbox 创建/挂载到 READY（不含 bootstrap/start） |
| `sys.workspace.bootstrapTimeoutSeconds` | 1800 | bootstrap 总超时（async） |
| `sys.workspace.startTimeoutSeconds` | 600 | start 脚本超时 |
| `sys.workspace.leaseTtlSeconds` | 300 | turn lease TTL |
| `sys.workspace.leaseWaitSeconds` | 30 | 并发 turn 等待 |
| `sys.workspace.asyncOrphanTimeoutMinutes` | 120 | 会话关闭后后台任务的保留时间；超时取消并释放 lease |
| `sys.workspace.purgeAfterDays` | 7 | 软删除后保留天数 |
| `sys.workspace.inactiveDeleteDays` | 0 | 0 = 不自动删；>0 时到期前通知 |
| `sys.workspace.networkEnabledByDefault` | true | Workspace 默认是否联网（受网络策略能力约束） |

**配额检查点：**

1. 创建 Workspace 前：数量、总容量。
2. 唤醒前：同时 RUNNING 数；必要时 LRU sleep。
3. 删除前：无配额检查（释放）。
4. 扩容（P2）：总容量。

**计费归因（P2）：**存储按 owner + size_gb + 时长；计算按 owner + runtime 运行分钟。首版只发指标，不实现账单。

### 7.3 内部执行契约

```java
interface WorkspaceProvisioner {
    boolean supportsVolumes();
    VolumeHandle create(String workspaceId, String userId, int sizeGb); // idempotent
    Optional<VolumeHandle> get(String workspaceId);
    void delete(String workspaceId); // idempotent
    VolumeStatus status(String workspaceId);
}

interface WorkspaceSandboxProvider {
    Sandbox create(WorkspaceSandboxSpec spec); // image, resources, env, pvc, network
    Optional<Sandbox> attach(String sandboxId, WorkspaceSandboxSpec spec);
    void release(Sandbox sandbox); // deletes pod/CR, never the PVC
    SandboxStatus status(Sandbox sandbox);
}
```

**`WorkspaceSandboxSpec` 关键字段：**

- `workspaceId`、`userId`、`sessionId`（仅用于标签/事件）
- `image`、`runtimeVersion`、`memoryMb`、`cpuMillicores`
- `pvcName`（K8s）/ `hostPath`（Docker）
- `env`（非 secret）、`secretRefs`
- `networkEnabled`、`networkPolicyProfile`
- `runtimeGeneration`、`maxRuntimeSeconds`
- `mountPath=/workspace`、`home=/workspace/home`

**`WorkspaceRuntimeService` 关键方法：**

```java
WorkspaceLease acquireTurn(String workspaceId, String sessionId, String turnId);
Sandbox ensureRuntime(String workspaceId, String sessionId, String userId);
void releaseTurn(WorkspaceLease lease);
void sleep(String workspaceId, String reason);
void wake(String workspaceId);
WorkspaceUsage usage(String workspaceId);
```

**错误模型：**内部异常映射到 typed API error；不要把 K8s 原始错误直接暴露给用户（保留 request id / PVC name 供管理员排查）。

### 7.4 用户 API（提案）

**Workspace 管理：**

| API | 契约 | 理由 |
|---|---|---|
| `GET /api/workspaces` | 当前用户的分页列表：id、name、agentId、status、sizeGb、usedBytes、lastUsedAt | Settings / 会话选择器 |
| `POST /api/workspaces` | name、agentId?、sizeGb?、idleSleepMinutes?、networkEnabled?；image 只能来自已注册 profile，不接受任意值 | 显式创建；配额检查；返回 PROVISIONING |
| `GET /api/workspaces/:id` | 详情 + 卷/运行时状态 + 最近错误 | 诊断 |
| `PUT /api/workspaces/:id` | 改名、env、脚本、idleSleepMinutes、network；不可改 size/image（需 upgrade/reset） | 元数据编辑 |
| `DELETE /api/workspaces/:id` | 软删除；`?purge=true` 需二次确认 | 用户主动释放 |
| `POST /api/workspaces/:id/undelete` | 7 天内撤销软删除（卷尚未 purge 时）；否则 410 | 误删恢复 |
| `POST /api/workspaces/:id/sleep` | 立即休眠（幂等） | 手动省资源 |
| `POST /api/workspaces/:id/wake` | 预唤醒（幂等） | 用户提前准备 |
| `POST /api/workspaces/:id/reset` | 需 `confirmName`；重置卷 | 环境坏了/要干净开始 |
| `POST /api/workspaces/:id/restore` | P2：从归档恢复到新卷 | 灾备（区别于 undelete） |
| `GET /api/workspaces/:id/usage` | used/capacity/inodes/top dirs（可选） | UI + Agent 自检 |
| `PUT /api/workspaces/:id/env` | `Map<String,String>` 非 secret | 声明式 env |
| `GET/PUT /api/workspaces/:id/scripts` | bootstrap/start 文本 + hash | Agent 作者配置 |
| `POST /api/workspaces/:id/upgrade` | 目标 image + 确认；P2 可先 checkpoint | 显式升级环境 |
| `GET /api/workspaces/:id/sessions` | 绑定该 Workspace 的会话列表 | 诊断“谁在占用” |

**Agent / Session 集成：**

| API / DTO | 变更 |
|---|---|
| `SandboxConfigView` | 新增 `persistent_workspace_enabled`、`workspace_profile`、`workspace_size_gb`、`workspace_idle_sleep_minutes`、`workspace_network_enabled`、`workspace_bootstrap_script`、`workspace_start_script` |
| `AgentDefinitionView` | 返回上述配置；不返回 Workspace 实例 |
| `SessionConfig` | 新增 `workspaceId`（可选；不传则解析默认）；Workspace 模式下 `workingDirectory` 仅允许 Workspace 内路径，不能逃逸到 `/tmp` / `/etc`；默认用 `/workspace/repo` 或 `/workspace` |
| Session view | 新增 `workspace` 摘要：id、name、status、used/capacity、cold start 提示 |
| `POST /api/sessions`（或创建会话的入口） | 如果 Agent 开启持久化且用户有权限，解析/创建 Workspace；失败不阻塞会话创建，但第一次工具调用会失败并给出原因 |

**权限：**

- `workspace.manage`：创建/编辑/删除自己的 Workspace；**默认只授予 pilot 白名单 / 指定角色**，稳定后再评估放开给所有 `chat.use`。原因是 32 GiB 卷是长期成本，不能让任意用户无门槛创建。
- `workspace.admin`：查看所有用户的 Workspace、配额、孤儿、强制 sleep/delete；仅管理员。
- API user：默认不开放 Workspace 管理；如果 API 触发 Agent，需要显式授权并把 Workspace 绑定到 API user 的 owner（或其配置的 owner），不能借用人类用户的卷。

### 7.5 错误码（提案）

| HTTP | errorCode | 场景 |
|---|---|---|
| 404 | `WORKSPACE_NOT_FOUND` | 不存在或不属于当前用户（不泄露存在性） |
| 409 | `WORKSPACE_BUSY` | 另一个 turn 持有 lease，等待超时 |
| 409 | `WORKSPACE_RESET_IN_PROGRESS` | 正在重置 |
| 409 | `WORKSPACE_IMAGE_MISMATCH` | 需要显式升级 |
| 409 | `WORKSPACE_REPLACED` | runtime/lease 被接管；当前 turn 必须中止 |
| 410 | `WORKSPACE_DELETED` | 已软删除/永久删除 |
| 429 | `WORKSPACE_QUOTA_EXCEEDED` | 数量/容量/运行数超限 |
| 501 | `WORKSPACE_UNSUPPORTED` | provider 无卷能力 / 功能未启用 |
| 503 | `WORKSPACE_PROVISION_FAILED` | PVC 创建/Bound 失败 |
| 503 | `WORKSPACE_WAKE_FAILED` | Sandbox/Pod/start 失败 |
| 503 | `WORKSPACE_VOLUME_UNAVAILABLE` | 卷丢失/不可挂载 |
| 500 | `WORKSPACE_INTERNAL` | 未分类；带 requestId |

**文案原则：**告诉用户“发生了什么、数据是否安全、下一步做什么”。例如“工作区启动失败，文件仍在；可以重试或查看日志”，而不是“sandbox error”。

### 7.6 具体源码改动范围（实施提案）

| 区域 | 拟改动 | 说明 |
|---|---|---|
| `core-ai-api/.../server/workspace/` | 新增 `WorkspaceWebService` 与 typed request/response DTO | 必须通过 core-ng WebService 契约校验；枚举用 `@Property` |
| `core-ai-api/.../server/agent/SandboxConfigView`、`SessionConfig`、session view | 新增 workspace 字段 / 摘要 | 旧客户端忽略新字段；不破坏现有 JSON |
| `core-ai-server/.../workspace/` | `WorkspaceService`、`WorkspaceRuntimeService`、`WorkspaceProvisioner`（K8s/Docker）、`WorkspaceBootstrap`、`WorkspaceSleeper`、`WorkspaceReconciler`、stores | 新包；避免与 `sandbox` 包循环依赖 |
| `core-ai-server/.../sandbox/` | `SandboxProvider` 增加带 volume 的 acquire/attach；`WorkspaceSandboxHandle`；`SandboxService` 的 session→workspace 分流；`SandboxReleaseSupport` / `SandboxCleanupJob` / `SandboxEvictionPolicy` workspace 例外 | 保持普通 session 行为不变；新增回归测试 |
| `core-ai-server/.../session/` | `ChatSession.workspaceId`；`SessionRegistry.SessionRegistration`；`AgentSessionManager` 解析/写入；`SessionRebuildManager` 恢复 | `workspace_id` 在会话创建时固化 |
| `core-ai-server/.../domain/ChatSession`、`PgChatSessionRow`、`ChatSessionStore`（PG/Mongo 兼容） | 新列映射 | Mongo 侧只用于迁移兼容；不新增 Mongo collection |
| `core-ai-server/.../agent/AgentViewHelper`、`AgentPublishedConfig` | workspace 配置的 view / published 映射 | 保证 PUBLISHED 配置包含 workspace 字段 |
| `core-ai-server/.../rbac/PermissionCodes`、`ServerModule`、`ServerApp` | `workspace.manage` / `workspace.admin`；service/store 绑定顺序；PG row view 注册 | 先绑 store 再绑 service |
| `core-ai-server-db-migration/.../db/migration/` | 新表 + `chat_sessions` 列 + 索引 + 权限 seed | 版本号实施时核对；不猜 |
| `core-ai-sandbox-runtime/main.go` | `HOME` 可配置；`.core-ai/env` 注入 + protected keys；`BASH_ENV`；`/health` / `/workspace/stat`；bootstrap async 任务保留期 | 小改动，但必须有 runtime 测试 |
| `core-ai-sandbox-runtime/snapshot.go`（P2） | `/workspace/archive` 独立根、无 500MB 上限 | 不复用 session snapshot 的 caps |
| `core-ai-frontend/src/pages/agents/AgentEditor.tsx` | Persistent workspace 分组 | 复用 `SandboxConfigSection` |
| `core-ai-frontend/src/pages/settings/Workspaces*.tsx`、`src/api/workspace.ts`、`App.tsx`、`Layout.tsx` | Settings 列表/详情、会话 chip、路由 | 复用现有 Settings 布局 |
| `d:\fbr-env-project\...\14-core-ai-server.yaml`（ops） | Role 增加 `persistentvolumeclaims` 权限；如用 Secret 注入凭证，增加 workspace Secret 权限 | 见 C31 |
| `d:\fbr-env-project\...\14-core-ai-server-sandbox-pool.yml` 或新 manifest（ops） | workspace 专用 template / node pool / NetworkPolicy / StorageClass | 不把 workspace 混入普通 warm pool |

### 7.7 迁移与兼容

- `chat_sessions.workspace_id` 为 nullable；历史会话不迁移，继续按临时环境行为。
- 新表只创建，不改现有表结构（除 `chat_sessions` 加列）。
- `SandboxConfigView` / `SessionConfig` 新字段全部可选；旧客户端/旧 Agent 配置不受影响。
- 功能开关默认关闭；开启后只影响显式开启 persistent workspace 的 Agent。
- 旧 server 回滚不安全：旧版本不认识 `workspace_id`，会把 Workspace 会话当普通会话 capture/release。发布顺序：先升级 server，再开启功能；回滚时先关功能并 sleep 所有 runtime（§10.3）。
- 现有 `sandbox_snapshots` 表和数据不变；Workspace 会话不写入该表。
- 现有 `sandbox_bindings(session_id)` 不变；Workspace 使用新的 `workspace_bindings(workspace_id)`。
- 现有 `persistentSessionIds`（OCG）语义不变；Workspace 不复用它。

## 8. Agent 配置与用户体验

### 8.1 Agent 编辑器

在现有 `SandboxConfigSection`（附录 B C22）内新增一个独立分组 **Persistent workspace**，不与镜像/内存/超时混在一起：

| 字段 | 默认 | 说明 |
|---|---|---|
| Enable persistent workspace | off | 开启后显示成本确认 |
| Workspace profile | default | small / default / build（对应 §5.2 的 2/4/8 GiB 与 1/2/4 CPU） |
| Default size | 32 GiB | 16 / 32 / 64 / 100 |
| Sleep after idle | 15 min | 5–240；最后一次活动后保持 warm，超过则释放 runtime、保留卷 |
| Network | on | Workspace 开发需要联网；显示“需要网络策略支持” |
| Bootstrap script | 空 | 首次 provision 运行；提供模板 |
| Start script | 空 | 每次 wake 运行；提供模板 |
| Default workspace name | `default` | 用户可改；多 Workspace 时在会话开始选择 |
| Attach existing workspace | 可选 | 复用已有 Workspace；显示“会共享文件与依赖” |

**成本确认：**保存时弹一次“每个用户会创建一个 32 GiB 的持久卷，直到删除；空闲时只保留存储，不占 CPU”。勾选后不再提示；每个用户首次创建时仍显示一次。

**与现有 Sandbox 字段的关系：**开启 Persistent workspace 后，现有 `memory_limit_mb` / `cpu_limit_millicores` 只影响“临时环境”会话，不作用于 Workspace；UI 应隐藏或在旁边明确标注，避免用户以为把 memory 改成 8192 就能让 Workspace 变大（实际会被 `MAX_MEMORY_LIMIT_MB` 钳制）。Workspace 计算规格只由 `workspace_profile` 决定。

**校验：**

- 开启持久化时，`sandbox.enabled` 必须为 true；否则拒绝保存并提示。
- `workspace_profile` 必须是已注册值；不能用 chat sandbox 的 memory/CPU 字段隐式决定 Workspace 规格（它们会被 2048 MB / 2000m 钳制）。
- `image` 为空时使用默认镜像；Workspace 创建时固化 image。
- bootstrap/start 脚本大小限制（例如 64 KB），保存时只做长度/编码校验；语法错误在首次运行或“测试脚本”操作中暴露，不要假装保存时能可靠 `bash -n`。
- 网络开启但部署不具备 NetworkPolicy 能力时，显示警告；是否允许保存由 `sys.workspace.networkEnabledByDefault` / 管理员策略决定。

### 8.2 会话内体验

**新会话的 Workspace 选择：**

- 如果该 Agent 在该用户下有多个 Workspace，新建会话时显示一个轻量选择器：默认选中最近使用的；可以选“使用临时环境”（不写卷）；不选则用默认。
- 只有一个默认 Workspace 时不打断用户，直接使用。
- 如果 Workspace 正在被另一个 turn 占用，选择器显示“正在使用”，用户仍可创建会话，但第一条消息会等待或提示。

**会话头部 Workspace chip：**

- `Workspace: desktop-main · Running · 12.4/32 GiB`（RUNNING）
- `Workspace: desktop-main · Sleeping · 12.4/32 GiB`（cold）
- `Workspace: desktop-main · Preparing…`（首次/唤醒）
- `Workspace: desktop-main · Needs attention`（ERROR，点击看原因）

**菜单：**

- Sleep now（立即休眠）
- Open terminal（复用现有终端）
- Workspace settings（跳 Settings）
- Reset workspace（危险，二次确认）
- Delete workspace（危险，软删除）

**首次使用：**

- 第一个 turn 前显示一个可折叠的提示：“正在准备工作区；首次需要克隆仓库并安装依赖，可能需要几分钟。”
- 如果超过 60s，显示阶段（创建存储 / 启动环境 / 运行初始化脚本），不要让用户以为卡死。
- bootstrap 日志可在会话中展开（脱敏：只显示最后 N 行；完整日志在终端/文件）。

**占用冲突：**

- 另一个会话正在使用 Workspace 时，输入框上方显示“该工作区正被另一个会话使用；发送后会等待最多 30 秒”。
- 超时后消息不发送，保留草稿；不要静默排队到很久以后。

**错误：**

- 卷错误：红色 banner + “重试 / 重置 / 联系管理员”；明确“文件仍在/可能丢失”。
- start 失败：banner + “查看日志 / 重试 / 跳过启动脚本（仅本次）”。跳过是显式选择，且记录审计。

### 8.3 Settings → Workspaces

新增一个用户级页面（可以从头像菜单或 Settings 进入）：

| 列 | 内容 |
|---|---|
| Name | 可点击进入详情 |
| Agent | 默认绑定的 Agent（可空） |
| Status | Running / Sleeping / Preparing / Error / Deleted |
| Size | used / capacity |
| Last used | 相对时间 |
| Actions | Wake / Sleep / Reset / Delete（已删除的显示 Undelete） |

**详情页：**

- 基本信息：owner、created、last used、image、runtime version、generation。
- 存储：PVC/卷名（管理员可见）、容量、用量、inode。
- 环境：env（非 secret 明文；secret 只显示名称）、bootstrap/start 脚本编辑、脚本 hash。
- 会话：最近使用该 Workspace 的会话列表。
- 事件：create/wake/sleep/reset/delete/upgrade 审计。
- 危险区：reset、delete。

**管理员视图：**

- 所有 Workspace、owner、节点/zone、PVC 状态、孤儿、配额使用。
- 强制 sleep/delete（需审计）；不能浏览文件内容。
- 平台容量：PVC 数、总 GiB、运行中数量、节点挂载余量。

### 8.4 Agent 提示词契约

Workspace 会话的系统提示词必须包含一段固定说明（由 `WorkspaceLifecycle` 注入，类似现有 `SandboxLifecycle` 的 hub 说明）：

```text
# Persistent workspace

/workspace is persistent across conversations. /tmp and /skill are not.
- Put source, scripts, notes and installed project dependencies under /workspace.
- HOME is /workspace/home. pip --user, npm caches and dotfiles live there.
- Environment variables: write KEY=VALUE lines to /workspace/.core-ai/env.
  `export` only affects the current command; it is not persistent.
- apt-get install is NOT persistent. Use a custom workspace image for system
  packages, or put static binaries under /workspace/bin.
- Long builds/tests: use run_in_background=true and poll with async_task_output;
  redirect output to /workspace/.core-ai/logs/*.log and tail it.
- At the start of a session, read /workspace/AGENTS.md and
  /workspace/.core-ai/NOTES.md if they exist; keep NOTES.md updated with
  setup commands, test commands and known issues.
- Do not write secrets into files under /workspace.
- Do not delete /workspace itself or try to unmount it; ask the user to use
  Reset/Delete in the UI when a clean environment is needed.
- The workspace may be reset by the user; do not assume files are permanent
  unless they are committed and pushed.
```

**注入时机：**`WorkspaceLifecycle.beforeAgentRun`，与现有 `SandboxLifecycle` 一样只注入一次（用 marker 判断）。

**工具补充（可选）：**

- `workspace_info`：返回 path、capacity、used、env 键名、bootstrap 状态、start 状态。避免 Agent 反复 `df`/`cat`。
- `workspace_set_env`：写 `.core-ai/env` 的安全封装（校验 protected keys）。不是必须；`write_file` 已够用，但封装能减少错误。
- 首版可以只靠提示词 + 普通文件工具；工具化是 P1 优化。

### 8.5 首次使用、成本与保留

- 每个用户首次创建 Workspace 时显示容量与“直到删除都会计费”的说明。
- Settings 显示总用量；达到 80% 配额时提示。
- 软删除保留 7 天；UI 明确“删除后 7 天内可恢复（如果卷尚未清理）”。
- 不自动删除用户数据；`inactiveDeleteDays=0` 默认关闭。管理员可配置到期通知/删除，但必须提前通知。
- Workspace 不是 artifact：不要在文件面板把它当普通目录暴露；文件下载走现有 file/artifact 通道。

## 9. 安全、隐私与网络

### 9.1 信任边界

| 边界 | 内容 | 控制 |
|---|---|---|
| 用户 ↔ 用户 | Workspace 文件、终端、hub 身份、配额 | owner 校验；卷不跨用户挂载；API 不泄露存在性 |
| 用户 ↔ Agent 作者 | Agent 配置决定 workspace 镜像/脚本/网络，但不能读取用户卷 | Agent 作者只能定义模板；运行时的 owner 是用户；作者不能通过 API 读卷 |
| Agent ↔ 平台 | Agent 在 sandbox 内执行任意代码，可调用 hub 能力 | hub token 按 session 签发、短期、权限受限；Workspace 不额外扩大权限 |
| Workspace ↔ 集群 | 联网的 Workspace 可访问内网 | NetworkPolicy egress 白名单；禁止 metadata endpoint；只允许 server/hub、DNS、包仓库、GitHub 等 |
| 管理员 ↔ 用户数据 | 管理员能管理 Workspace 元数据/生命周期 | 不能浏览卷内容；所有强制操作审计；终端访问只限 owner |

**核心原则：**Workspace 不改变 Agent 已有的能力边界，只改变“文件能活多久”。它不应成为绕过 RBAC、网络策略或工具权限的跳板。

### 9.2 凭证与 Secret

**问题：**开发 Workspace 需要 Git/包仓库凭证；用户可能想在环境里 `git clone` 私有仓库、`npm install` 私有包。凭证明文写入卷会长期留存、被任何 Agent/终端读取、随备份扩散。

**设计：**

1. **Secret 只存引用，不存明文。**Workspace 记录保存 `secretRefs: [{envName, source, key}]`，来源可以是系统 Secret Store / 用户凭证 / hub 能力；具体实现复用现有密钥管理，不新建一套。
2. **启动时注入容器 env，但不要把明文写进 Sandbox CR。**K8s 路径用 per-workspace Secret + `valueFrom.secretKeyRef` 引用；Workspace 记录只存 Secret 名称 / key，不存值。Secret 由 server 创建、轮转、随 Workspace 删除；K8s RBAC 限制读取。Docker 本地开发可以直接 env（仅本地）。不写入 `/workspace/.core-ai/env`，不写入 `.git/config`。
3. **轮换需要重启 runtime。**UI 提示“凭证已更新，下次唤醒生效”；不尝试热更新运行中的进程。
4. **优先使用 hub 能力而不是长期 token。**例如 GitHub 操作可以通过 session hub 的 GitHub App 能力获取短期 token（现有 `RequireGithubInstallationTokenTool` / skill 体系，附录 B C23），脚本不持有长期凭证。首版可以先支持 `GH_TOKEN` / `GITHUB_TOKEN` 注入，长期方案是 hub 凭证助手。
5. **明确残余风险：**容器内任何进程都能读到注入的 env（包括 Agent 的 shell）；这是 sandbox 模型的固有属性。不要把高权限生产凭证放进开发 Workspace；使用最小权限的只读/仓库级 token。
6. **禁止把 Secret 写进卷的普通文件**（`.npmrc`、`.git-credentials`、`.netrc`）。提示词明确要求；可选的 secret 扫描（P2）在写入时告警，但不阻塞。

**Git 认证的具体建议：**

- `gh` 使用 `GH_TOKEN` env；`git` 通过 `GIT_ASKPASS` 或 credential helper 读取同一个 token，不落盘。
- 如果用户坚持 SSH：`~/.ssh` 在卷上会长期留存；建议使用 per-workspace deploy key + 明确警告，或改为 HTTPS + token。
- 不在日志里打印 token；runtime 的 stdout/stderr 会进工具结果，Agent 不应 `env | grep TOKEN`。

### 9.3 网络策略

**现状（附录 B C19）：**当前 AKS `networkPolicy: none`，NetworkPolicy 对象不生效；`networkEnabled` 在直接 Pod / CR 路径主要是一个 label，没有真实 enforcement。普通 sandbox 实际上有网络。

**Workspace 的需求：**开发 Agent 必须能 clone、安装依赖、访问包仓库；默认关闭网络会让特性不可用。因此：

1. **默认 `networkEnabled=true`（Workspace），但 GA 以网络策略生效为前提。**在策略引擎启用前，允许 dev 试点并明确告知“与现有 sandbox 相同的暴露面”，不能宣称隔离。
2. **一个静态 NetworkPolicy 覆盖所有 workspace pod**（按 label `core-ai/component=workspace`），而不是每个 Workspace 动态创建：
   - ingress：只允许 core-ai-server（hub/工具）和 terminal-gateway；
   - egress：DNS、core-ai-server、GitHub、npm/PyPI/Maven 等仓库、企业代理；默认拒绝其他内网；
   - 显式拒绝 `169.254.169.254`（云 metadata）和集群内其他 namespace。
3. **不为每个 Workspace 创建独立 NetworkPolicy**，避免孤儿与对账（与 `design-agent-sandbox-native-adoption` D12 一致）；网络模式是少数几种，用 label + 静态策略表达。
4. **直接 CR 的 policy 覆盖**：直接创建的 Sandbox pod 不带 template 的 label；必须确保 Pod 带 `core-ai/component=workspace`，并让静态策略选中它。上线前用 `kubectl exec` + `curl` 验证真实 enforcement，而不是只看对象存在。
5. **禁止/限制出站到内网服务**：如果 Agent 需要访问内部 MCP / API，应通过 session hub（server 侧鉴权），而不是直接访问内网地址；这样权限仍由 Agent 配置和工具权限控制。

**网络与成本：**允许联网会带来下载流量和潜在滥用；配额与超时/休眠仍是主要控制。可在 P2 增加每 Workspace 的流量观测。

### 9.4 审计与隐私

**审计事件：**

- Workspace 创建、删除、软删除、恢复、重置、升级镜像、修改 env/脚本、修改网络、强制 sleep、管理员访问。
- 记录 actor、workspaceId、owner、时间、请求 id、结果；不记录文件内容、env 值、secret。
- 终端打开可以记录（谁、何时、哪个 Workspace），但不记录按键/输出。

**隐私：**

- Workspace 是用户数据；管理员不能浏览。删除/重置必须审计。
- GDPR/数据删除：永久删除时删除 PVC + 归档 + 事件中的敏感 payload（事件只保留元数据）。
- 用户被删除/停用：其 Workspace 不转移给其他用户；按保留策略由管理员清理或保留只读审计记录。停用用户的 Workspace 不能继续唤醒。
- 备份/归档加密：Azure 存储默认加密；归档 blob 私有；下载需要短期签名。
- 日志脱敏：不打印 env 值、token、文件内容；工具输出可能含敏感信息，按现有 trace 策略处理。

## 10. 可观测性、失败处理与运维

### 10.1 指标与事件

| 指标 | 类型 | 用途 |
|---|---|---|
| `workspace_provision_seconds` | histogram | PVC 创建到 Bound |
| `workspace_wake_seconds` | histogram | 决定冷启动体验；按 image/zone 分组 |
| `workspace_sleep_total` | counter | 休眠次数/原因 |
| `workspace_running` / `workspace_sleeping` | gauge | 容量与成本 |
| `workspace_volume_bytes` | gauge | 存储配额 |
| `workspace_usage_ratio` | gauge | 卷满预警 |
| `workspace_quota_rejected_total` | counter | 配额调优 |
| `workspace_lease_conflicts_total` | counter | 并发/卡死诊断 |
| `workspace_lease_expired_takeover_total` | counter | 崩溃恢复 |
| `workspace_bootstrap_failures_total` | counter | 脚本质量 |
| `workspace_start_failures_total` | counter | 脚本质量 |
| `workspace_orphan_volumes` | gauge | 对账 |
| `workspace_pvc_attach_failures_total` | counter | 存储/zone 问题 |
| `workspace_sandbox_create_failures_total` | counter | 调度/配额 |
| `workspace_archive_*`（P2） | counter/histogram | 归档 |

**事件：**`workspace.created / provisioning / ready / waking / sleeping / error / reset / deleted / upgraded`；写入 `workspace_events` 并（可选）推送到会话 SSE。事件 payload 只含 id、状态、耗时、错误码，不含文件内容。

**日志：**结构化日志带 `workspaceId`、`userId`、`sessionId`、`pvcName`、`sandboxId`、`generation`、`requestId`；不打印 env/secret。K8s 操作失败保留原始错误文本供管理员，但 API 返回脱敏错误。

### 10.2 失败矩阵（运维视角）

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 一直 PROVISIONING | StorageClass 不存在 / 容量不足 / zone 不匹配 | 查 PVC events；扩容或换 StorageClass；不要反复重建 |
| 唤醒超时 | Pod 调度失败 / 镜像拉取慢 / 节点磁盘挂载满 | 查 Sandbox/Pod events；换 node pool；预热镜像 |
| 卷挂载失败 | 旧 Pod 仍挂载（RWO）/ zone 不匹配 / 磁盘损坏 | 删除旧 Sandbox；确认 zone；必要时从归档恢复（P2） |
| 文件权限错误 | 运行用户变化 / fsGroup 未设 | 确认 runAsUser/fsGroup；不要直接 chown 整个大卷（慢） |
| 卷满 | `node_modules` / 构建缓存 / 大文件 | 展示用量；Agent 清理；P2 扩容 |
| 两个会话抢工作区 | lease 未释放 / 长 turn / async 任务 | 查 lease 行与 async 任务；等待或取消；不要手动删 lease 除非确认无写入 |
| 部署后全部冷启动 | 未 attach / shutdownTime 太短 | 确认 binding；新 Pod attach；必要时延长 maxRuntime |
| 孤儿 PVC | 创建后 server 崩溃 / 删除失败 | Reconciler 按 label 对账；超过 grace 删除或告警 |
| 卷丢失但记录在 | 人工误删 / 存储故障 | 标 ERROR；禁止写入；从归档恢复或重置；审计 |
| 网络不通 | NetworkPolicy 过严 / DNS / 代理 | 用 `curl`/`getent` 从 sandbox 验证；按域名/端口放行；不要直接关策略 |

### 10.3 运维 runbook（首版）

**紧急止血：**

1. `sys.workspace.enabled=false`：停止创建/唤醒；已有 runtime 可继续或由 sleeper 释放；用户仍可删除。
2. 强制 sleep 所有 workspace runtime：`WorkspaceSleeper` 一次性扫描 + 释放；不影响卷。
3. 暂停 sleeper/reconciler：仅当怀疑它们误删时；先看审计。
4. 回滚 server：Workspace 记录/卷保留；旧版本不认识 `workspace_id`，会把 Workspace 会话当普通会话 capture/release，因此发布顺序要求先升级 server、再开启功能；回滚时先关功能并 sleep 所有 runtime，不能直接让旧版本接管。

**容量：**

- 监控 PVC 数、总 GiB、每节点挂载数、zone 余量。
- 新增 node pool 时确认 StorageClass zone 绑定；不要跨 zone 复用 PV。
- 大镜像预拉取到 workspace node pool，降低冷启动。

**数据恢复：**

- 首版只有卷本身 + 软删除 7 天；没有归档。卷丢失只能重置（数据不可恢复）。必须在产品文案中写明。
- P2 归档上线后，恢复流程：选择 archive → 新 PVC → restore → 校验 → READY；不能覆盖现有卷。

## 11. 验收、实施计划与风险

### 11.1 验收矩阵

| ID | 验收项 | 方法 | 通过标准 |
|---|---|---|---|
| A1 | 文件持久 | 创建 workspace，写 `/workspace/repo/a.txt`，关闭会话、sleep、wake、重启 server | 文件存在，内容一致 |
| A2 | 依赖持久 | 在 workspace 里 `npm ci` / `pip install --user`，休眠唤醒 | `node_modules` / `~/.local` 存在，命令可直接运行，不重装 |
| A3 | 声明式 env | 写 `.core-ai/env`，新命令读取；`export` 不持久 | `env` 文件键在新命令可见；`export` 后新命令不可见（文档一致） |
| A4 | 不持久清单 | `/tmp` 文件、`apt-get install`、后台进程 | 休眠后消失；UI/提示词明确说明 |
| A5 | 部署不丢 | 写文件后滚动重启 core-ai-server | 文件存在，会话可继续 |
| A6 | 崩溃恢复 | 删除 Sandbox pod / 杀掉 server pod | 卷保留；下次 turn 重建；旧 turn 标中断 |
| A7 | 单写者 | 两个会话同时发消息 | 一个执行，另一个等待/明确 BUSY；hub 身份不串 |
| A8 | 会话隔离 | 两个用户各自 workspace | 互相不可见、不可 attach、不可 terminal |
| A9 | 配额 | 超过数量/容量/运行数 | 明确错误；无孤儿卷；LRU sleep 生效 |
| A10 | 删除/重置 | 软删除、永久删除、reset | 卷按预期保留/删除；审计完整 |
| A11 | 首次初始化 | bootstrap 失败/成功；重试 | 失败可见、卷保留；成功后可执行 |
| A12 | start 脚本 | 每次 wake 执行；失败阻断 | 进程按脚本拉起；失败不进 READY |
| A13 | 镜像漂移 | 改 Agent image 后打开旧 workspace | 不静默换镜像；提示 upgrade/drift |
| A14 | 长任务 | `npm test` 后台运行 + 轮询 | turn 结束不终止任务；sleep 延后；结果可读 |
| A15 | 终端 | 打开终端、执行命令、关闭 | 可用；活动阻止休眠；不影响 lease 语义 |
| A16 | 网络 | clone / npm install / hub 调用 | 成功；metadata endpoint 被拒；内网未授权地址被拒 |
| A17 | 快照隔离 | workspace 会话 sleep | 没有 500MB capture 尝试；无 snapshot 文档 |
| A18 | Docker | 本地 docker provider 同上 | 文件/env/sleep-wake/删除一致 |
| A19 | 观测 | 指标/事件/日志 | 关键路径有数据；错误可追踪 |
| A20 | 性能 | 冷启动、warm 调用、sleep | 冷启动在目标范围（如 P50 < 45s）；warm 无额外延迟 |
| A21 | core-ai-desktop 场景 | clone → npm ci → npm test → 改代码 → 再测 | 全流程由对话驱动；无需人工进容器 |

### 11.2 实施分阶段

| 阶段 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| **P0 前置** | K8s RBAC 增加 PVC 权限；StorageClass / node pool 规划；网络策略评估；runtime env 契约（HOME/env 文件）设计确认 | 平台/运维 | 无权限/无容量会阻塞 |
| **P1 最小闭环** | PG 表 + store；WorkspaceService；K8s 静态 PVC + 直接 CR；WorkspaceRuntimeService；turn lease；session 绑定；sleep；Agent 开关；feature flag | P0 | 只在 K8s；无归档；UI 最小 |
| **P2 体验与安全** | Settings 页面、会话 chip、usage、reset/delete UI；Docker provider；bootstrap/start；env 文件；配额/驱逐；观测；网络策略；安全加固 | P1 | 依赖网络策略引擎 |
| **P3 数据保护** | 归档/恢复；镜像升级/checkpoint；软删除恢复；灾备演练 | P2 | 归档大包/一致性 |
| **P4 优化** | v1beta1 suspend + template/pool 快速 resume；always-on 档位；多 Workspace 选择器 | P3 / controller 升级 | 复杂度/成本 |

**首版建议：**P1 + P2 的一部分（Agent 开关、会话可用、sleep/wake、usage、删除）作为 pilot；在真实用户场景（core-ai-desktop）跑通后再扩大。不要一开始就做归档/共享/always-on。

**更细的 P1 拆分（降低首版风险）：**

- **P1a（证明持久性）**：显式创建 Workspace + 静态 PVC + 单 session 绑定 + wake + 文件/env/bootstrap 持久 + 手动 reset/delete；暂不做 turn lease（P1a 限制一个 Workspace 同时一个活跃会话，避免 hub 绑定串台），不做多 session、自动 sleep。用一个 pilot Agent 在 dev 跑通 core-ai-desktop 场景。
- **P1b（可运营）**：turn lease、并发会话、自动 sleep、配额/驱逐、Settings 页面、usage、审计、feature flag。
- **P2**：Docker、网络策略、Secret 注入、观测、镜像升级。
- **P3**：归档/恢复、checkpoint、软删除恢复、灾备演练。

P1a 不是“可以给所有用户”的版本；它用于验证“卷真的持久、env 真的生效、Agent 真的能迭代”，避免在并发/配额/UI 上过早投入。

### 11.3 风险登记

| 风险 | 概率/影响 | 缓解 |
|---|---|---|
| Azure Disk 挂载慢/zone 不足 | 中/高 | 工作期间 warm；专用 node pool；预热镜像；监控冷启动 |
| 节点磁盘挂载数上限 | 中/高 | `maxRunningWorkspacesPerNode`；LRU sleep；容量规划 |
| 网络策略未生效 | 高/高 | GA 前完成 P0a/P0b；未生效时 pilot 明确告知；不宣称隔离 |
| Workspace 以 root + 可写根文件系统 + 联网运行 | 中/高 | 专用 node pool/namespace；NetworkPolicy；`automountServiceAccountToken: false`；P2 非 root/read-only；评估 Kata |
| 用户误以为 apt/进程持久 | 高/中 | 提示词 + UI 文档 + A4 验收；错误文案明确 |
| 卷丢失/误删 | 低/高 | 软删除 7 天；confirmName；P3 归档；审计 |
| 两会话 hub 身份串 | 中/高 | turn lease 串行化；async 延长 lease；A7 验收 |
| 部署时旧 Pod 仍挂载 | 中/中 | 接管前删除旧 Sandbox；RWO；reconcile |
| 成本失控 | 中/中 | 配额、sleep、maxRuntime、用量告警 |
| 镜像漂移导致依赖坏 | 中/中 | workspace image 固定；显式 upgrade；drift 警告 |
| 首版无归档，数据单点 | 高/中 | 文案明确；建议 commit/push；P3 尽快 |
| 与现有 snapshot/eviction 冲突 | 中/高 | 显式跳过 capture；独立配额/TTL；回归测试 |

## 12. 关键决策与未决事项

### 12.1 ADR 摘要

所有 ADR 当前为 `PROPOSED`，用户尚未批准。正文负责行为定义，本表集中记录备选与代价。

| ID | 决策 | 初衷 / 理由 | 未选备选及原因 | 代价 / 章节 |
|---|---|---|---|---|
| ADR-01 | Workspace 是一等实体：owner=user，身份=workspace，agent/session 绑定 | 解决“per user 太粗、per agent 全局泄密、per session 不持久” | 一个用户一个环境：多项目污染；一个 Agent 一个环境：跨用户泄露 | 多一个实体/UI；§3.2 |
| ADR-02 | 主存储用持久卷（PVC / host dir），tar 快照只做归档 | 随机读写、大容量、部署不丢；快照有 500MB/2GB 上限 | 只用快照：上限+慢+部署窗口；常驻 VM：成本/运维 | K8s 存储/RBAC/zone；§5.1 |
| ADR-03 | 静态 PVC + 直接 Sandbox CR，不用 volumeClaimTemplates | PVC 必须活过 Sandbox 删除；静态卷生命周期独立 | volumeClaimTemplates：随 Sandbox GC；suspend：需 v1beta1/P3 | 不能走 warm pool，冷启动；§5.2 |
| ADR-04 | 单写者：RWO + turn lease + generation fencing | 防止双挂载/双写/hub 身份串 | 允许并发写：文件损坏、身份错乱 | 并发 turn 需等待/拒绝；§3.4、§6.3 |
| ADR-05 | HOME/cache/venv 放卷；apt 不支持持久 | 让“装包”真正持久；保持镜像不可变 | 挂整个根文件系统：破坏镜像/安全；支持 apt：不可控 | 用户需自定义镜像；§4.2–§4.4 |
| ADR-06 | env 用声明式文件 + 每次执行注入，不用进程内存 | `export` 不可能跨进程；文件可审计/可迁移 | 依赖 shell 状态：每次失忆 | Agent 需写文件；§4.3 |
| ADR-07 | bootstrap 一次、start 每次；不自动 pull/改文件 | 可重复、可诊断；避免唤醒时覆盖用户工作 | 每次唤醒 git pull：可能冲突/丢修改 | 脚本质量要求；§4.6 |
| ADR-08 | Workspace 会话跳过 per-session snapshot；卷错误 fail-closed | 快照不适合大卷；空环境会误导 Agent 覆盖工作 | 沿用 snapshot 降级：丢数据/浪费时间 | 需要独立生命周期；§6.6、§6.9 |
| ADR-09 | Workspace 镜像/工具链固定，Agent 改 image 不静默生效 | 环境身份可复现；避免依赖突然坏 | 跟随 Agent：每次改配置环境漂移 | 升级需显式操作；§3.5、§8.1 |
| ADR-10 | 默认工作期 warm + 空闲 sleep，不常驻 | 成本与体验平衡 | 常驻：贵；每次 turn 重建：慢 | 休眠杀进程；§5.1、§6.4 |
| ADR-11 | 首版不做归档/共享/always-on | 先保证“不丢工作”的核心闭环 | 一次做全：范围/风险失控 | 数据单点，需文案；§11.3 |
| ADR-12 | 网络默认开，但 GA 依赖 NetworkPolicy | 开发场景必须联网；不能假装隔离 | 默认关：特性不可用；无策略宣称隔离：误导 | 平台前置工作；§9.3 |
| ADR-13 | Secret 只存引用、启动注入，不落卷 | 长期留存/备份扩散风险 | 写 `.npmrc`/`.git-credentials`：泄露面大 | 轮换需重启；§9.2 |
| ADR-14 | 首版 K8s 主路径 + Docker 等价；其他 provider 明确不支持 | 生产可落地；不把“尽力恢复”伪装成持久化 | 所有 provider 都支持：语义不一致 | Docker 仅本地；§5.4 |
| ADR-15 | 软删除 7 天、默认不自动删、配额先于创建 | 防误删/成本可控；不替用户做删除决定 | 立即删/无限保留：风险 | 存储计费；§6.5、§7.2 |

### 12.2 决策状态台账

| ID | 待确认事项 | 当前建议 | 状态 / 授权来源 | 影响章节 |
|---|---|---|---|---|
| Q1 | Workspace 身份：per user / per agent / 显式实体 | 显式实体，owner=user，agent 默认绑定 | 建议，待用户确认 | §3.2 |
| Q2 | 默认容量与配额 | 32 GiB；3 个/96 GiB/2 running | 建议，待确认 | §7.2 |
| Q3 | 默认空闲休眠 / 最大运行 | 15 min / 8 h | 建议，待确认 | §6.4 |
| Q4 | 首版是否必须归档 | 不必须；软删除 7 天 + 文案 | OPEN | §5.5、§11.2 |
| Q5 | 网络策略未生效前是否允许 pilot | 允许 dev pilot，明确暴露面；GA 前完成策略 | OPEN（平台依赖） | §9.3 |
| Q6 | Secret 注入方式 | v1 env 注入；长期 hub 凭证助手 | 建议，待确认 | §9.2 |
| Q7 | Agent 改 image 的策略 | 固定 workspace image；显式 upgrade | 建议，待确认 | §3.5 |
| Q8 | 是否允许同一 Workspace 多 Agent 绑定 | 允许但提示共享风险 | 建议，待确认 | §3.2 |
| Q9 | 是否给 eval/workflow 用 | 不；eval 必须 fresh | 建议 | §1.4 |
| Q10 | Docker 是否首版必须 | 建议 P2；K8s pilot 先行 | OPEN | §5.4 |
| Q11 | 计费/配额归属 | owner user；首版只观测 | OPEN（产品） | §7.2 |
| Q12 | 在线扩容 | 首版不做；新建+迁移 | 建议 | §5.3 |

**OPEN 仅表示未获得用户决定，不表示缺少建议。**AI 评审与实现选择不能写成用户已批准。

## 附录 A：用户原始需求与文档治理

### A.1 用户原始需求（2026-11-23，原文保留）

> 参考 agent-evaluation的techdesign文档，给core-ai-server设计一个持久化workspace的特性。它的目标就是一个server上的agent，如果开启了持久化workspace（直观感觉应该是per user级别），那么每次用户跟这个agent交互，会拥有一个长期的环境，包括它可能安装的包，临时写的脚本，设置的环境变量等等。一个实际可能的场景就是：我希望在core-ai-server的环境上建一个开发core-ai-desktop的agent，然后它拥有一个持久化的工作空间环境，它能自己在这个环境启动测试，并持续迭代，而我要做的只是和这个agent对话。你要从第一项原理出来来分析这个特性要如何设计，我说的方案可能是错误的也可能是对的，你应该网上查询最佳实践，再结合我们的架构和场景来设计。只做techdesign，不实现。

### A.2 本次采用的结构约束

本设计采用“第一性原理与需求 → 方案判断与总体流程 → 核心模型与不变量 → 状态分层与持久化契约 → 存储与运行时 → 服务架构与流程 → 数据/API → UX → 安全运维 → 验收计划 → 决策 → 历史附录”。原始需求、行业依据、源码核对、Review、修改记录保留在同一文件，不另拆零散记录。

### A.3 维护规则

1. 原始需求原文不覆盖；澄清 / 新增需求使用稳定 ID（U/S/D），映射到正文与验收。
2. 正文为当前设计基线；未实施的目标明确标注；实施后更新正文与 ADR，不只追加日志。
3. 行业原则、源码事实、设计提案、实测结果分别标注；提案默认值不能写成已实现能力。
4. 每个主题有唯一权威定义：状态机在 §2.4，状态分层在 §4，存储实现在 §5，租约在 §3.4，配额在 §7.2，网络在 §9.3。
5. ADR / Q / Review 相互链接；状态区分 PROPOSED、OPEN、AUTHORIZED_DEFAULT、CONFIRMED、SUPERSEDED、DEFERRED；AI 评审不等于用户批准。
6. Review 记录意见、处理与章节；修改记录记版本与原因；实施验证记录日期、环境、镜像、证据和限制，不能替代正文状态。
7. 不创建业务实现、迁移、commit、部署或真实 Workspace，直到用户明确授权。

## 附录 B：行业依据与设计时源码核对

### B.1 行业依据及采用方式

资料获取日期：2026-11-23。以下是官方产品 / 文档，不是统一行业标准；采用其状态分层与生命周期原则，不引入其平台依赖，不照抄 SDK/API。没有实测第三方 SDK。

| 来源 | 采用原则 | 不照搬 / 局限 | 对应设计 |
|---|---|---|---|
| [S1] E2B — Sandbox persistence / Filesystem-only snapshots | pause/resume 可保存文件系统，full snapshot 还能保存内存与进程；filesystem-only 明确“文件在、进程不在”；paused 可长期保留 | 其内存快照/进程恢复依赖 Firecracker 级能力，AKS 上没有；我们不承诺进程恢复；paused 无限保留不符合成本治理 | §4.1、§4.5、§5.1；区分文件与进程 |
| [S2] Kubernetes agent-sandbox — volumeClaimTemplates | 每个 Sandbox 可挂独立 PVC；StatefulSet 语义；支持 warm pool 带状态；failedPodPolicy 重建保留 PVC | PVC 归 Sandbox 所有，删除 Sandbox 会 GC；template 级能力需 v1beta1；我们不使用该生命周期 | §5.2；改用静态 PVC |
| [S3] GitHub Codespaces — lifecycle | stop 保留文件、终止进程；idle timeout；最大连续运行；inactive 自动删除；rebuild 清 `/workspaces` 之外 | 它是完整 VM/容器开发环境，不是多租户 Agent 平台；我们不做 IDE/端口转发 | §4.2、§6.4、§7.2；文件/进程区分与保留策略 |
| [S4] OpenAI Codex Cloud — environments | setup script（一次）+ maintenance script（缓存恢复）；env vs secrets；secrets 只在 setup 阶段；缓存失效条件；共享环境缓存 | 它的 cache 是“环境模板缓存”，不是每用户持久卷；我们不共享用户之间的环境 | §4.6、§9.2；bootstrap/start 与 secret 边界 |
| [S5] OpenAI Agents API — sandbox lifecycle | session 可以比 environment 活得久；应用必须持久化 session→environment 映射；启动/重连要幂等；idle 不是安全的关机信号；复用一个 environment id 不会恢复替换后的文件 | 它把 compute 生命周期交给应用；我们的 server 自己管理卷和 runtime；其“environment id 复用不恢复文件”正好说明必须有独立存储 | §3.3、§6.2、§6.7；binding + 卷 |

**综合结论：**行业实践一致地把“文件系统持久”和“进程/内存持久”分开；把“环境模板缓存”和“用户私有 Workspace”分开；把“session”和“environment”分开。本文采用这三条，但不采用任何一家平台的生命周期实现。

### B.2 设计时源码 / 集群事实与接入约束

核对范围：2026-11-23 的工作区源码与 dev 集群 manifest。工作区存在并行开发，实施前必须重核路径 / 行号与最新行为。以下仅记录当前事实，不代表目标设计已经实现。

| ID | 当前事实 | 源码 / 配置证据（工作区相对路径:行号） | 设计约束 |
|---|---|---|---|
| C01 | sandbox 归属是 session：`sessionSandboxes` 是 sessionId→Sandbox 的 Pod 内存 Map；另有 `persistentSessionIds`（OCG 使用） | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxService.java:48-49`；`:271-284` | Workspace 需要独立的 workspaceId→runtime registry，不能直接塞进 sessionSandboxes |
| C02 | 关闭会话时先 capture 再 release session sandbox | `core-ai-server/src/main/java/ai/core/server/session/AgentSessionManager.java:382-383` | Workspace 会话必须跳过 capture，且 release 只 detach |
| C03 | `LazySandbox.ensureReady()`：acquire → restoreSnapshot → postAcquire → READY；restore 失败降级为空并 warning | `core-ai-server/src/main/java/ai/core/server/sandbox/LazySandbox.java:243-289`；`:336-356` | Workspace 不能复用“降级为空”的语义；需要 fail-closed |
| C04 | 快照上限：压缩包 500 MB、10,000 文件、解压 2 GB；单文件 >200 MB 跳过；capture roots 是 `/tmp`、`/skill`、`/workspace` | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxSnapshotClient.java:31`；`core-ai-sandbox-runtime/snapshot.go:22-25`；`:55-60` | 不能把 tar 快照当 Workspace 主存储 |
| C05 | runtime 的 `WORKSPACE_DIR` 默认 `/workspace`，启动时 mkdir；`minimalEnv()` 硬编码 `HOME=/tmp`、`PATH`、`PIP_USER=1`，并从父进程继承其他 env | `core-ai-sandbox-runtime/main.go:151-152`；`:186-188`；`:219-223`；`:1201-1232` | Workspace 必须改 `HOME`/PATH/env 注入；`HOME=/tmp` 会让 pip/npm 缓存丢失 |
| C06 | 独立 Pod builder：`readOnlyRootFilesystem=true`；`/tmp` 内存 emptyDir 默认 100Mi；`/skill` 50Mi；`HOME=/tmp`；不挂 `/workspace` | `core-ai-server/src/main/java/ai/core/server/sandbox/kubernetes/KubernetesPodSpecBuilder.java:121-127`；`:147-164`；`:186-224` | 该路径不能直接用于 Workspace；需要可写卷与 HOME |
| C07 | 直接 Sandbox CR builder：没有 securityContext / probes；volumes 只有 `tmp` + `skill`；`hasCustomConfig` 只要 image/env 非默认就绕过 warm pool；provider lifetime = timeout + 600s | `core-ai-server/src/main/java/ai/core/server/sandbox/agentsandbox/SandboxCRSpecBuilder.java:100-148`；`AgentSandboxProvider.java:85-98`；`:27-41` | Workspace 直接 CR 需要补卷、资源、安全上下文；不走 warm pool |
| C08 | `SandboxManager.activeSandboxes` 是 Pod 内存；TTL 清理按 `config.timeoutSeconds`；per-user 配额按内存/数量；驱逐只针对同一用户的空闲 session | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxManager.java:34`；`:123-151`；`:283-320`；`SandboxEvictionPolicy.java:27-39` | Workspace runtime 需要独立 TTL、配额桶和休眠策略，不能被普通 session 驱逐 |
| C09 | 清理任务每 5 分钟运行；K8s 孤儿清理的 fallback 是 `DEFAULT_TIMEOUT_SECONDS=1800` | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxCleanupJob.java:37-43`；`SandboxService.java:126-129` | 必须识别 workspace label，使用 workspace 的长 TTL，不能被 1800s 误删 |
| C10 | 默认 sandbox timeout 1800s；service 默认 3900s；`MAX_MEMORY_LIMIT_MB=2048`、`MAX_CPU_LIMIT_MILLICORES=2000`；同步 bash 上限 600s | `core-ai-server/src/main/java/ai/core/sandbox/SandboxConstants.java:39-56`；`SandboxService.java:36-43`；`core-ai-sandbox-runtime/main.go:633-643` | Workspace 需要独立 profile 与长任务策略；不能沿用 chat 的资源钳制 |
| C11 | `AgentSandboxConfig` 有 `gitRepoUrl` / `gitBranch`，但只在 view/mapping 中使用；创建 sandbox 时没有 clone 逻辑 | `core-ai-server/src/main/java/ai/core/server/domain/AgentSandboxConfig.java:49-53`；`AgentViewHelper.java:220-238` | 不能假设现有 git 配置会 clone；Workspace bootstrap 必须自己实现 |
| C12 | 附件根目录是 `/workspace/attachments` 或 `/tmp/attachments`；前端提示也按这个名字 | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxAttachmentPath.java:13-20`；`SandboxLifecycle.java:86-93` | Workspace 多会话共享时要加 session 子目录，避免同名覆盖 |
| C13 | Docker provider 把宿主机 workspace 目录以 `:ro` 挂到 `/workspace`，`workspaceWritable()` 返回 false；WorkingDir 是 `/workspace` | `core-ai-server/src/main/java/ai/core/server/sandbox/docker/DockerSandboxProvider.java:165-169`；`:188`；`:213-216` | Docker 需要新增可写 Workspace 挂载，不能复用只读挂载 |
| C14 | 终端按 session → sandbox 解析 runtime，ticket 只带 sandbox 地址；终端活动会 touch 会话 | `core-ai-server/src/main/java/ai/core/server/sandbox/terminal/SandboxTerminalService.java`；`SandboxTerminalRuntimeResolver.java` | Workspace 终端解析到共享 runtime；活动要计入 Workspace lastActivity |
| C15 | runtime 的 `/bind` 是单绑定：`POST` 会替换已有绑定；hub proxy 只持有当前 session token | `core-ai-sandbox-runtime/README.md:67-86`；`hubproxy.go`（currentBinding） | 不能假设多 session 共享 runtime 时各自有 hub 身份；必须用 turn lease 串行 |
| C16 | 会话重建按 `sandbox_bindings(session_id)` reattach；binding 是 PG 表 | `core-ai-server/src/main/java/ai/core/server/session/SessionRebuildManager.java:311-323`；`PgSandboxBindingStore.java:21-41` | Workspace 需要按 workspaceId 的 binding，旧 session binding 不能复用 |
| C17 | 服务关闭时只关闭非 `persistentSessionIds` 的 sandbox；不做 workspace 特判 | `core-ai-server/src/main/java/ai/core/server/sandbox/SandboxReleaseSupport.java:58-86` | Workspace runtime 不能在 shutdown 被误释放；也不能 capture |
| C18 | dev/UAT 使用 agent-sandbox controller v0.3.10 / `v1alpha1`；template 级 `volumeClaimTemplates` 需要 v1beta1；上游文档与内部设计均说明 PVC 由 Sandbox 拥有并在删除时 GC | `docs/cn/design-agent-sandbox-native-adoption.md:369`；`:478-487`；S2 | 用静态 PVC + 直接 CR；suspend 留到 P3 |
| C19 | dev 的 sandbox template：`HOME=/tmp`、`/tmp` 磁盘 10Gi、`/skill` 50Mi、不挂 `/workspace`、无 securityContext；网络策略引擎未启用，`networkEnabled` 不是真实 enforcement | `d:\fbr-env-project\azure\dev\kube\resource\app\ai\14-core-ai-server-sandbox-pool.yml:1-45`；`docs/cn/design-agent-sandbox-native-adoption.md:246-260` | Workspace 需要可写 `/workspace`、HOME 迁移；网络隔离是 GA 前置 |
| C20 | server 的 messaging 启动要求 `sys.store.mode=pg`，否则拒绝启动；Dataset/trace 仍有独立 backend | `core-ai-server/src/main/java/ai/core/server/MessagingInfrastructureModule.java:36-38` | 新 Workspace store 用 PG；不假设跨系统事务 |
| C21 | runtime 支持 async task（`run_in_background` / `async`），任务保留 30 分钟，`MAX_ASYNC_TASKS` 默认 5；服务端有 `async_task_output` | `core-ai-sandbox-runtime/main.go:409-431`；`:199-206`；`core-ai/src/main/java/ai/core/tool/tools/AsyncTaskOutputTool.java:23-32` | 长构建走 async；bootstrap 需要更长保留期或服务端续期 |
| C22 | Agent 编辑器的 Sandbox 配置区包含 image / env / memory / CPU / timeout / network | `core-ai-frontend/src/pages/agents/AgentEditor.tsx:2122-2133`；`:2939-3120` | 新增 Persistent workspace 分组；不要复用 timeout 表达休眠 |
| C23 | 已有 GitHub installation token 工具（`require_github_installation_token`） | `core-ai/src/main/java/ai/core/tool/tools/RequireGithubInstallationTokenTool.java:18-20` | 长期凭证优先走 hub / 该工具，而不是写入卷 |
| C24 | run 结束后 `RunSandboxReleaseScheduler` 延迟 60s 释放 `runId` sandbox | `core-ai-server/src/main/java/ai/core/server/run/RunSandboxReleaseScheduler.java:31-39` | `releaseSandbox` 对 workspace 必须是 no-op/detach |
| C25 | `SandboxFfmpegRunner` 用 `plan.jobKey()` 创建独立 sandbox，并 release 该 key | `core-ai-server/src/main/java/ai/core/server/render/ffmpeg/SandboxFfmpegRunner.java:61-68`；`:83-86` | v1 媒体渲染不接入 Workspace；文档明确 |
| C26 | `OcgSandboxService` 按 config.id 维护常驻 sandbox，并 release | `core-ai-server/src/main/java/ai/core/server/channel/openclaw/OcgSandboxService.java:84-94` | 与 Workspace 是两套持久化；v1 不合并 |
| C27 | session share 的公开入口是只读投影（token + sanitize）；没有看到写/执行端点 | `core-ai-server/src/main/java/ai/core/server/share/SessionShareProjection.java`；`SessionShareInterceptor.java:31-40` | 分享不授予 Workspace 访问；实施前必须确认不存在借 share 执行工具的路径 |
| C28 | Agent comparison 会创建成对会话，secondary 隐藏 | `core-ai-server/src/main/java/ai/core/server/domain/ChatSession.java:71-81` | Workspace 会话不共享；comparison 必须独立或禁用 |
| C29 | `SessionActivityRegistry` 提供跨 Pod 的会话活动（terminal 等） | `core-ai-server/src/main/java/ai/core/server/session/SessionActivityRegistry.java` | Workspace lastActivity 应合并 turn/async/terminal |
| C30 | `KubernetesClient` 目前只支持 Pod / Service（create/get/wait/delete/list），没有 PVC 方法 | `core-ai-server/src/main/java/ai/core/server/sandbox/kubernetes/KubernetesClient.java:66-194` | 需要新增 PVC CRUD/wait；或引入独立 provisioner client |
| C31 | dev 的 core-ai-server Role 只授权 sandbox/claim/template/pool，没有 `persistentvolumeclaims`；也没有 workspace Secret 所需权限 | `d:\fbr-env-project\azure\dev\kube\resource\app\ai\14-core-ai-server.yaml:183-209` | 必须新增 PVC RBAC；若用 Secret 注入凭证，还要 `secrets` create/get/patch/delete；否则 provision 直接 403 |
| C32 | snapshot 有 kill switch（系统设置 + deployment gate + storage 可用性） | `core-ai-server/src/main/java/ai/core/server/sandbox/snapshot/SandboxSnapshotPolicy.java:20-56` | Workspace 需要类似 `sys.workspace.enabled` 的止血开关 |
| C33 | snapshot restore 失败会 DEGRADED，LazySandbox 发 warning 后用空 sandbox 继续 | `SandboxSnapshotService.java:130-139`；`LazySandbox.java:346-355` | Workspace 不能沿用该降级语义 |
| C34 | `app.chat_sessions` 已有 PG 表；会话有 workspace 之外的所有元数据 | `core-ai-server-db-migration/src/main/resources/db/migration/V12__chat_sessions.sql:1` | 增加 `workspace_id` / `workspace_generation` 列与索引 |
| C35 | `SandboxConfig` / `AgentSandboxConfig` 字段包含 enabled/image/memory/cpu/timeout/network/tmp/env；没有 workspace 字段 | `core-ai/src/main/java/ai/core/sandbox/SandboxConfig.java:23-43`；`AgentSandboxConfig.java:31-62` | 新增 workspace 配置需要 typed view + 映射；不要把 Workspace 实例塞进 Agent 配置 |
| C36 | `workspaceWritable()` 在 SandboxService/Manager 透传；`SandboxAttachmentPath` 用它决定附件根 | `SandboxService.java:221-224`；`SandboxManager.java:76-79`；`SandboxAttachmentPath.java:13-20` | Workspace 模式必须让它返回 true，否则附件仍落到 `/tmp` |

**core-ng 类型约束**同时依据本仓库 `AGENTS.md`：WebService DTO / entity 不能用 `Object`、`Map<String,Object>`、BSON Document；动态数据用 `String` JSON；枚举 persistence/view 分离；PG 表与 store 在 `core-ai-server-db-migration` 的下一个未占用版本实现，本文不猜迁移号。

### B.3 参考资料

- [S1] [E2B — Sandbox persistence](https://e2b.dev/docs/sandbox/persistence)；[Filesystem-only snapshots](https://docs.e2b.dev/sandbox/filesystem-only-snapshots)，2026-11-23 获取。
- [S2] [Kubernetes agent-sandbox — volumeClaimTemplates](https://agent-sandbox.sigs.k8s.io/docs/volumes/volume-claim-template/)；[Sandbox lifecycle](https://agent-sandbox.sigs.k8s.io/docs/sandbox/lifecycle/)，2026-11-23 获取。
- [S3] [GitHub Codespaces — Understanding the codespace lifecycle](https://docs.github.com/en/codespaces/about-codespaces/understanding-the-codespace-lifecycle)，2026-11-23 获取。
- [S4] [OpenAI Codex Cloud — environments](https://developers.openai.com/codex/environments/cloud-environments)（Legacy），2026-11-23 获取。
- [S5] [OpenAI Agents API — Sandbox lifecycle](https://developers.openai.com/api/docs/guides/agents-api/environments/lifecycle)，2026-11-23 获取。
- 内部：[Sandbox Snapshot 机制设计](design-sandbox-snapshot.md)；[Sandbox 沙箱子系统 — 架构与设计](design-sandbox-architecture.md)。
- 内部（未入库）：Agent Sandbox 原生能力 Adopt 设计（`docs/cn/design-agent-sandbox-native-adoption.md`，截至本文提交时尚未随提交提供，故不设链接；文中 C18/C19 及 §2 相关引用以该文档为准，待其入库后补链接）。

## 附录 C：Review 与修改历史

### C.1 用户 Review 与授权

| Review ID | 日期 | Reviewer | 意见 / 范围 | 处理 / 影响章节 | 状态 |
|---|---|---|---|---|---|
| R-U-01 | 2026-11-23 | 用户 | 原始需求见附录 A；要求从第一性原理分析、查最佳实践、结合架构与 core-ai-desktop 场景；只做 tech design | 完整技术设计，需求 U1–U4 / S1–S4；未进入实施 | REQUEST_RECEIVED；待用户 Review |
| R-U-02 | 2026-11-23 | 用户 | 允许推翻原始方案；不以“per user”为不可讨论前提 | §2.1 逐条判断；提出 Workspace 实体 | REQUEST_RECEIVED |

**实施授权：无。**用户提出设计任务不等同于批准本方案、预算、PVC 创建、权限或外部调用。本文件不创建迁移、不改代码、不部署。

### C.2 AI 技术评审（非用户批准）

本节记录设计交付前的 AI 复核发现、处理位置与剩余风险；评审使用当前会话模型（`deepseek/deepseek-v4-flash`），按 2026-11-19 用户决定不使用 `gemini-4-hydrogen`。这是同一会话的自审，不是独立第三方评审；AI 结论不构成实施授权。

| Review ID | 日期 | Reviewer | 意见摘要 | 处理 / 影响章节 | 状态 |
|---|---|---|---|---|---|
| R-AI-01 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | “per user” 不足以定义环境身份：同用户多 Agent/项目会互相污染；per agent 全局又会跨用户泄露 | 引入 Workspace 实体；owner=user、agent 默认绑定；§2.1、§3.2 | IMPLEMENTED_IN_DOC |
| R-AI-02 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 若用 `volumeClaimTemplates`，PVC 随 Sandbox 删除被 GC，与“sleep 删 Sandbox、保留卷”冲突 | 改为静态 PVC + 直接 CR；§5.2、ADR-03 | IMPLEMENTED_IN_DOC |
| R-AI-03 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | runtime `/bind` 是单绑定，多 session 共享会串 hub 身份 | 引入 turn lease；并发 turn 等待/拒绝；§3.3、§3.4、ADR-04 | IMPLEMENTED_IN_DOC |
| R-AI-04 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 现有 snapshot 有 500MB/10k/2GB 上限，且 release 会 capture `/workspace`；Workspace 会话若不跳过会失败/浪费 | 明确跳过 capture/restore；§6.6、ADR-08、C04 | IMPLEMENTED_IN_DOC |
| R-AI-05 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | `HOME=/tmp` 导致 pip/npm/venv 默认不持久；“安装包会保留”不成立 | HOME 移到 `/workspace/home`，cache/prefix 变量；apt 明确不持久；§4.2–§4.4、ADR-05 | IMPLEMENTED_IN_DOC |
| R-AI-06 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 环境变量不能靠进程内存持久；`export` 跨命令失效 | 声明式 `.core-ai/env` + 每次执行注入；§4.3、ADR-06 | IMPLEMENTED_IN_DOC |
| R-AI-07 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 现有 snapshot 失败会“空环境继续”，对 Workspace 是危险误导 | fail-closed；卷/挂载/脚本失败禁止工具调用；§6.9、ADR-08 | IMPLEMENTED_IN_DOC |
| R-AI-08 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 现有 TTL 清理/驱逐/1800s 孤儿清理会把 workspace runtime 当普通 sandbox 杀掉 | 独立配额/TTL/label；Sleeper/Reconciler；§6.6、C08/C09 | IMPLEMENTED_IN_DOC |
| R-AI-09 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | bootstrap（clone + npm ci）会超过同步 bash 600s；不能用同步 `/execute` | bootstrap 走 async + 轮询；提高任务保留期；§4.6、C21 | IMPLEMENTED_IN_DOC |
| R-AI-10 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 现有资源上限 2 GiB / 2 CPU 对 Electron/Gradle 构建不够 | 独立 workspace profile（2/4/8 GiB）；§5.2 | IMPLEMENTED_IN_DOC |
| R-AI-11 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | run/ffmpeg/OCG/comparison/share 都有各自的 sandbox 生命周期，不能假装 Workspace 覆盖全部 | 明确 v1 仅 chat session；其他路径列为非目标/限制；§1.4、§6.6 | IMPLEMENTED_IN_DOC |
| R-AI-12 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 当前网络策略未生效，Workspace 默认联网会扩大暴露面 | GA 依赖 NetworkPolicy；pilot 明确告知；§9.3、ADR-12 | IMPLEMENTED_IN_DOC |
| R-AI-13 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 多 session 共享 `/workspace/attachments/<name>` 会互相覆盖 | 改为 session 子目录；§4.2、§6.6、C12 | IMPLEMENTED_IN_DOC |
| R-AI-14 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | 把 Git/registry 凭证写进卷会长期留存并扩散 | Secret reference + 启动注入；不写 `.git-credentials`；§9.2、ADR-13 | IMPLEMENTED_IN_DOC |
| R-AI-15 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | Azure Disk RWO/zonal/每节点挂载数会限制并发 Workspace | 专用 node pool、LRU sleep、attach 失败可见；§5.3、§10.2 | IMPLEMENTED_IN_DOC |
| R-AI-16 | 2026-11-23 | 会话模型自审（deepseek-v4-flash） | turn 结束但 async 任务仍在跑；若释放 lease/sleep 会中断任务或串 hub 身份 | async 任务延长 lease；Sleeper 检查 async；§3.3、§6.4 | IMPLEMENTED_IN_DOC |

**剩余风险（未在本文解决）：**

1. 网络策略引擎未启用前，Workspace 的“联网隔离”只是声明，不是 enforcement；必须作为 GA 门槛。
2. 首版无归档：卷是单点，Azure Disk 故障/误删只能重置；用户必须知道并 push 重要工作。
3. Azure Disk 的 zonal/挂载延迟/每节点上限依赖具体 node pool 与 StorageClass，需 dev 压测后定默认容量与并发。
4. 运行时安全姿态（root、可写根文件系统）沿用现状；非 root/read-only 是独立加固项目，不能在本特性里静默切换。
5. 没有真实实现/测试；本文的延迟、配额、容量数字是提案，不是实测。

### C.3 修改记录

| 版本 | 日期 | 修改人 | 修改内容 | 原因 / 用户确认 |
|---|---|---|---|---|
| v0.1 | 2026-11-23 | core-ai | 初稿：第一性原理、Workspace/Volume/Runtime/Lease 模型、状态分层、静态 PVC、turn lease、休眠/配额/API/UX、安全与验收、行业依据与源码核对 | 用户请求设计；待 Review，未批准实施 |

### C.4 设计时未执行的事项

- 未创建任何数据库迁移、Java 类、前端页面、K8s 资源或 Blob 容器。
- 未在 dev/UAT/prod 创建 Workspace、PVC 或 Sandbox。
- 未运行压测、未实测冷启动、未验证网络策略。
- 未请求用户批准预算、配额或实施顺序。
