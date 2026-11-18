# Desktop 插件机制与宿主重构 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Approved / Implementing（用户 2026-11-17 授权实施；本轮落地 P1 包管理与 P2a 声明式 UI 子集，见 §14） |
| 文档版本 | v0.13（P2d：只读服务 + storage + events + usage + notification） |
| 日期 | 2026-11-17 |
| 作者 | core-ai |
| 需求提出者 / 决策人 | 用户；用户已授权本轮实施，§14 记录实际落地与未完成项 |
| 范围 | Desktop 插件包管理、宿主扩展点、Engine 构件接入、IPC/权限、生命周期、渐进重构与验收 |
| 事实来源 | 当前工作区源码及本次架构评审；历史 desktop 总设计已不存在，不作为当前实现依据 |
| 交付边界 | 设计 + 本轮插件机制产品代码；已本地验证并单独提交（未发布、未部署） |

## 0. 原始需求、编号与文档治理

### 0.1 用户原始需求（原文）

> 你review一下desktop的设计 如果要给它加插件机制 要进行哪些方面的重构

> 落到设计文档

> 开始实现 Desktop 插件机制，依据 `docs/cn/design-desktop-plugin-system.md`（v0.2）。… 用户已于 2026-11-17 明确授权实施；本轮使用 **deepseek/deepseek-v4-flash** 模型。… 设计不明确处选最保守方案并记录

### 0.2 需求分解

| ID | 要求 | 来源 |
|---|---|---|
| R1 | Review desktop 当前设计，指出加入插件机制需要的重构 | 用户明确要求 |
| R2 | 将评审结论落为可实施、可评审的技术设计文档 | 用户明确要求 |
| R3 | 区分 Agent 能力包、Desktop UI 扩展与后台代码扩展，不把现有 CLI 包格式当成桌面插件宿主 | 架构建议 |
| R4 | 保留 Electron 与每 workspace 独立 Engine 的分工，渐进改造而非推倒重写 | 架构建议 |
| R5 | 建立统一包管理与显式作用域；CLI/Desktop 不维护两套分叉解析、安装和状态语义 | 一致性要求 |
| R6 | 插件不能替换可信根、继承主 UI 全量能力或未经同意执行工作区代码 | 安全要求 |
| R7 | 普通更新不误杀 live turn；显式撤销立即阻止后续受控调用；停用清理资源 | 生命周期要求 |
| R8 | 插件缺失、禁用或版本不兼容时历史仍可读，错误不拖垮应用 | 可靠性要求 |
| R9 | 各阶段具有验证与退出条件，不以“写完代码”代替验收 | 工程要求 |
| R10 | 先设计、按 gemini-4-hydrogen 做 AI 评审，等用户确认关键决策后再实施 | 用户已于 2026-11-17 确认实施（§13.3）；未落地项见 §14.2 |
| R11 | 插件可提供独立页面，管理素材并直接调用生成，不经过 agent turn | 用户明确要求（媒体工作台场景） |
| R12 | 预留覆盖非媒体场景的能力/事件/服务契约，能支撑 DSH 生态中的 memory、usage、notifications、sessions、providers、browser 等插件类型 | 用户要求调研 DSH 并预留扩展性 |
| R13 | 扩展性不以复制宿主内任意 React/无沙箱插件为代价；高风险能力按信任等级逐步开放 | 安全与架构建议 |

R3–R9 是本设计推导的要求，不冒充用户原始需求。范围、优先级和安全模式见 §11 待确认决策。

### 0.3 治理规则

- 当前事实、已发现缺口、拟新增设计分别标识；源码是现状的事实来源。
- 本文所有新增类型、路径、方法名、SDK、阈值均是提案，除非明确标注“已有”。
- 代码引用使用仓库相对 `file_path:line_number`；行号基于 2026-11-17 核查，可随代码变化漂移。
- AI Review 是技术建议，不是用户批准；维护 ADR 摘要、待决策、Review 与修改记录。
- 本文不恢复已缺失的旧 `design-core-ai-desktop.md`，不重复其中历史行业数字或安全保证。

## 1. 结论、扩展范围与非目标

### 1.1 推荐结论

**采用“薄可信核心 + 明确贡献点 + 统一插件包管理”，而非“一切皆插件”。**

保留 `Renderer → Preload → Electron Main → 每 workspace 一个 Java app-server Engine`。Agent loop 继续在 Engine；Electron Main 负责可信 OS 服务、生命周期与授权。先将内置功能改为可注册模块，再开放第三方，不把任意代码执行平台作为一期目标。

### 1.2 三类扩展与实际工作量

| 类型 | 例子 | 所属执行层 | 推荐阶段 | 关键限制 |
|---|---|---|---|---|
| Agent 能力包 | skills、已接入的 MCP、hooks；commands/agents/LSP 按实际 adapter 支持 | Java Engine 与外部可执行体 | P1 | 包格式兼容不等于构件均可加载；hooks/stdio MCP 仍会执行宿主代码 |
| UI 贡献 | 插件页（媒体工作台）、Dock/Settings、composer、结果/预览按需 | 宿主 chrome + 独立脚本 view；声明式片段由宿主渲染 | P2a/P2b/P2c | 页面不获得完整window.coreai；RichCard只作输出/fallback |
| 后台代码扩展 | 通道适配、持续监听、复杂自动化 | 独立 PluginHost；运行时信任模式另行确认 | P3，可选 | utilityProcess 只是故障隔离；任意 Node 不属于受限执行模式 |

“内容声明式”与“包内存在 JSON”不是同一概念。skills 可以引导模型调用有副作用的工具；hooks/MCP/LSP 是代码启动器；每个构件必须按真实执行能力审查。

### 1.3 不需要重构的基础

- Electron 与 Engine 的进程分工、stdio JSON-RPC、每 workspace 独立引擎监督。
- 既有 ToolRegistry/ToolProvider、RichCard/form、artifact manifest 与已校验的本地路径服务。
- 用户自有/Hub/内置技能的所有权保护；迁移以 adapter 接入，不破坏原安装来源。
- 现有单窗口产品形态。协议保留 caller/window/workspace scope，但不把多窗口实现作为前置。
- 现有 vanilla TypeScript UI。模块与状态边界优先，不因插件机制迁移 React。

### 1.4 非目标

- 不让插件替换认证、凭据保护、审批裁决、应用/引擎更新、路径放行、EngineSupervisor 等可信根。
- 不把模型定义、agent loop、session persistence 全部改为用户可安装插件。
- 不将 server 管理后台或 server agent 任意代码扩展平台带入 desktop。
- 不让远端 agent 获得本机插件/浏览器/CUA 权限。远端直聊设计与本地插件是独立提案。
- P1 不做插件市场、自动更新、npm 依赖安装或安装脚本；P2 不开放任意后台 Node 模块。
- 不把签名、checksum、静态扫描或一次确认描述为代码安全证明。
- 不承诺同用户权限的任意本地代码无法读改用户文件；真隔离需专门运行时/OS 沙箱。

## 2. 当前实现核查与风险

### 2.1 可以复用的基础

| 已有能力 | 源码依据 | 复用方式 / 限制 |
|---|---|---|
| 每 workspace 独立 Engine，懒启动、握手、关闭 | `core-ai-desktop/src/main/engine/EngineSupervisor.ts:21`、`:54`、`:62` | 保留；cwd 已绑定 workspace，不存在多个 workspace 共用 JVM 单例的事实 |
| Main/Preload 隔离主 UI 的 Node 访问 | `core-ai-desktop/src/main/main.ts:701`；`core-ai-desktop/src/preload/preload.ts:4` | 主 UI 基线，不可直接复用为插件 bridge |
| Provider 变更等待运行中 turn 结束再 reload | `core-ai-desktop/src/main/main.ts:399`、`:423` | 先泛化成变更协调器，避免直接 shutdownAll |
| ToolProvider 注册、撤销、缓存失效、物化 | `core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java:50`、`:68`、`:74`、`:83` | 直接复用；补同 priority 确定性排序、来源与 scope，不另造工具注册表 |
| Generic card/form 实时渲染、只读历史回放 | `core-ai-desktop/src/renderer/app.ts:5711`、`:4486` | 优先用宿主 JSON 词表，插件缺失时用标准 fallback |
| 会话独立 surface | `core-ai-desktop/src/renderer/sessionSurfaces.ts:15`、`:48` | 演进为 scoped surface，不向插件开放内部可变 Map/DOM |
| 内置技能 marker、文件 digest 与所有权保护 | `core-ai-desktop/src/main/skills/managedSkill.ts:8` | 复用保护策略，不把 marker 当来源证明或执行授权 |
| 内嵌 WebContentsView、独立浏览器 partition | `core-ai-desktop/src/main/browser/BrowserManager.ts:331`、`:560` | 参考受限容器构建；插件不得共用浏览器登录态/CDP bridge |

### 2.2 主要耦合点

| 缺口 | 源码依据 | 重构目标 |
|---|---|---|
| 全局 managers、settings、启动关闭和 IPC 集中在 main.ts | `core-ai-desktop/src/main/main.ts:92`、`:820` | Main 是组合入口，按域服务化，建立内置/第三方贡献注册契约 |
| 导航、面板、全局状态在 app.ts 硬编码 | `core-ai-desktop/src/renderer/app.ts:486`、`:496`、`:8969` | Shell、SessionStore、TranscriptRenderer 与功能面板分离 |
| Dock 固定 `none/browser/artifacts` | `core-ai-desktop/src/renderer/sessionSurfaces.ts:10` | 稳定 contribution ID 与统一 bounds/焦点管理 |
| 全局 bridge 任意转发 Engine 方法 | `core-ai-desktop/src/preload/preload.ts:5`；`core-ai-desktop/src/main/main.ts:1254` | 核心桥与插件桥分离，caller-bound 运行时授权 |
| settings:set 接收 patch 并混合触发安装/重启 | `core-ai-desktop/src/main/main.ts:829` | typed settings service；插件配置不进入核心 settings patch |
| 技能启停直接 shutdownAll | `core-ai-desktop/src/main/main.ts:849` | 影响工作区与会话的安全点变更，不全局误杀 |
| 非 media 的 defaultSession 权限放行 | `core-ai-desktop/src/main/main.ts:671` | 插件专用 partition/CSP/权限处理器，默认拒绝 |
| custom event 对未知类型无 card 则无显示 | `core-ai-desktop/src/renderer/app.ts:5728` | 标准只读 fallback 与来源标识 |
| RpcClient 持续累积未换行 buffer | `core-ai-desktop/src/main/engine/RpcClient.ts:151` | 入站帧限制、pending/队列预算、背压 |

当前不存在 `core-ai-desktop/src/shared/ipc.ts`；Main/Preload/Renderer 的通道与 DTO 手工对齐。本文拟新增按域共享契约，不声称已有共享接口库。

### 2.3 CLI 插件与 desktop 接入的真实边界

- `PluginManager.initializeIfNeeded()` 在 `core-ai-cli/src/main/java/ai/core/cli/CliApp.java:202` 调用；`appserver/EngineBootstrap.java:105` 的初始化链未调用。
- `agent/CliAgent.java:72` 仅取得 PluginManager；因此 desktop app-server 的包发现未初始化，插件包内 skills/hooks 不生效。
- 同一 `CliAgent.java:96` 仍读 `HookConfig`；`hook/HookConfig.java:51` 读工作区 hooks；已配 MCP 亦通过 bootstrap/ToolProvider 生效。不能以“包未接入”推导为“desktop 不执行工作区代码”。
- `plugin/PluginManager.java:204` 探测 commands/agents/.mcp.json/.lsp.json 只表示文件存在，不代表这些构件均有完整运行时 adapter。
- `PluginManager.java:46` 为 static singleton、`:69` 依赖隐式 cwd、`:167` 按路径缓存。应显式传 scope、刷新状态并消除隐藏依赖；不是要求改为单 JVM 多 workspace。
- `command/plugins/PluginValidator.java:61` 仅校验 name、目录与 JSON；不能用于能力授权或 API 兼容校验。

### 2.4 必须先于新增 PluginHost 解决的执行/安装风险

1. `command/plugins/PluginInstaller.java:179` 执行 `npm install` 未禁 lifecycle scripts；`:113` 先删除旧目录再复制，`:123` 默认启用。未经信任的安装也可能执行代码，失败还可能破坏旧版。
2. `hook/ScriptHookRunner.java:21` 使用宿主 `sh -c` 继承环境；`:29` 的 `readAllBytes()` 在 `waitFor` 超时之前，stdout/stderr 或子进程可导致超时失效；没有走工具执行审批。
3. GUI 主 bridge 是可信应用界面接口，不是第三方隔离接口；仅将插件载入主页面会放大现有能力面。

这些是源码审查发现，未在本次运行恶意插件或攻击探针；测试修复与攻击边界验证列入 §10。

## 3. 目标架构与 ADR 摘要

### 3.1 分层

```text
Trusted Desktop Core
  Auth / SecretService / Approval / Update / EngineSupervisor
  ExtensionCoordinator + CapabilityBroker + ChangeCoordinator
    ├─ Shared package control plane (pinned Java engine command / app-server facade)
    ├─ Built-in contributions (compiled, trusted, same slot contract)
    ├─ Renderer Shell + declaration renderers
    ├─ Isolated plugin UI (own partition + restricted bridge)
    └─ Optional PluginHost (P3, trust mode explicitly selected)

Java Engine (one process per workspace)
  Shared PluginServices + ScopeResolver + ActivationGate
    ├─ Plugin component adapters → existing ToolRegistry / SkillConfig / HookConfig
    └─ Session/turn snapshots + immediate revocation checks
```

包分发/状态是一套；UI 与 Agent 构件的执行所有权分开。PluginRegistry 管包和实例，ContributionRegistry 管 UI 槽位，现有 ToolRegistry 管工具；三者不是三套安装平台。

### 3.2 信任与责任

| 所有者 | 责任 | 不允许做什么 |
|---|---|---|
| 共享 Java PluginServices | 解析/校验包、来源/digest、安装事务、scope 状态和锁、输出规范 descriptor | 不执行 UI entry，不把 metadata 查询变为自动激活 |
| Desktop ExtensionCoordinator | 内置与插件贡献装配、授权 UI、caller 绑定、UI/Host 生命周期 | 不自行重写 Java 的包解析/覆盖规则，不替代 Engine 工具审批 |
| CapabilityBroker / ActivationGate | 在实际调用和启动处验证授权、scope、digest、撤销版本 | 不信任插件自报 ID/权限，不把 broker 当任意 Node 的 OS 沙箱 |
| Java component adapters | 将支持的构件加载至现有注册机制 | 不因为扫描到未知构件就自动执行或静默忽略 |
| Renderer Shell | 统一导航、Dock、错误和来源呈现；标准卡片与 fallback | 不载入第三方脚本，不持有插件/Provider secrets |

### 3.3 ADR 摘要（均为提议，待 §11 确认）

| ADR | 推荐决策 | 理由 / 代价 |
|---|---|---|
| ADR-01 | 保留 Electron + 每 workspace Engine；薄可信核心 | 复用已验证底座；宿主内部重构而非重写 agent loop |
| ADR-02 | 单包、单解析/安装服务；CLI 与 app-server 共用 | 避免 Java/TS 双份规则；需要无 UI service 与结构化管理入口 |
| ADR-03 | P1 包纳管，P2 受限 UI，P3 代码可选 | 先验证真实扩展需求；安装与 hooks/MCP 风险并不因分期消失 |
| ADR-04 | 插件不替换可信根；内置功能先复用贡献契约 | 可扩展不等于可篡改审批/认证；内置模块无需全部外置 |
| ADR-05 | 主 bridge 与插件 bridge 分离，授权在执行层 | GUI 确认不是边界；需维护 caller/scoped grant |
| ADR-06 | 普通变更安全点应用，撤销立即阻断 | 不误杀正常任务；撤销时可取消/隔离而非等待 turn 结束 |
| ADR-07 | UI/事件具备标准历史 fallback | 卸载后历史可读；付出 schema 与降级信息约束 |
| ADR-08 | Node 可信模式与真受限模式明确分开 | utilityProcess/SDK 不提供权限沙箱；P3 需单独安全验收 |
| ADR-09 | 存量配置不自动grandfather；restricted bootstrap先于工作区覆盖 | 防止先执行再询问；旧CLI不受新协议逆向约束，兼容性需明示 |
| ADR-10 | 持久epoch + 短gate线性化 + quiesce握手从P1实施 | 外部写者撤销与调度竞态不能只靠GUI状态；在途停止best-effort |

## 4. 插件包、身份、状态与统一控制面

### 4.1 包格式与规范 descriptor

保留 `.claude-plugin/plugin.json` 作为兼容输入，增加 namespaced `coreai` 字段，避免另造平行包格式。下面是拟议格式，须经 validator 与兼容测试冻结，不是现有支持：

```json
{
  "name": "usage-panel",
  "version": "1.0.0",
  "description": "Session usage summary",
  "coreai": {
    "schemaVersion": 1,
    "id": "example.usage-panel",
    "desktopApi": "^1.0",
    "engineApi": "^1.0",
    "components": [{"kind": "skills", "path": "skills"}],
    "contributes": {
      "dock": [{"id": "usage", "title": "Usage", "view": "ui/index.html"}]
    },
    "capabilities": {
      "required": ["ui.dock", "session.stats.read"],
      "optional": []
    }
  }
}
```

- `id` 使用稳定命名空间；它是逻辑 ID，不是出版商身份认证。信任还绑定来源、签名主体（如有）与 content digest。
- 所有路径必须为包内规范相对路径，禁止绝对路径、`..`、symlink/junction 逃逸；只有支持的构件 adapter 才可激活。
- 旧格式由共享 parser 产生 legacy descriptor：构件自动探测仅用于展示/校验，不自动执行。旧 author/name 不能被推导为已验证 publisher。
- 新旧格式输出同一 `ResolvedPluginDescriptor`：公共 schema、逻辑 ID、来源、package digest、组件/贡献、兼容范围、能力要求与风险。Java 负责公共 envelope/路径/digest/Engine 构件校验；Desktop 负责 `desktopApi` 与 UI 槽位语义，验证后输出 UI facet report。UI payload 以有界结构透传，不让 Java 承担每个 Dock 字段的产品语义；合并两份报告后才可激活。公共能力 schema/version 必须两端兼容，TS 不重新扫描包或写另一套安装规则。
- 公共 envelope 允许有界普通未知 metadata；未知 required capability、必须构件或所属执行层 API 不兼容必须拒绝激活。Engine-only CLI不能凭自己忽略未知 desktop facet 后标全包可用；可显示 installed、UI validation pending，所属宿主验证完成后才激活 UI。可选能力拒绝时按声明降级。
- 贡献 ID 统一为 `pluginId:localContributionId`；工具名由 adapter 映射为 provider-compatible 唯一名称，保留原名称展示；禁止覆盖核心或其他已信任插件工具。

### 4.2 包、作用域实例与授权分离

三个实体分开：

1. **Package**：不可变字节快照，`pluginId + version + contentDigest + sourceIdentity`；无运行状态。
2. **Scoped installation**：package 在 `user` 或指定 `workspace` 的配置/启停实例；同一包可复用，不能按当前 UI 工作区猜 scope。
3. **Grant**：针对实例、digest、能力集与 scope 的授权记录；安装或 enable 意图不代表获得授权。

拟议存储（不在本次创建）：

| 数据 | 推荐位置 / 所有者 | 规则 |
|---|---|---|
| 托管包字节 | `~/.core-ai/plugin-store/packages/<digest>/`；共享服务 | content-addressed、校验后不可原位修改；包不能自行更新自己 |
| 用户实例/安装记录 | `~/.core-ai/plugin-store/state.json`；共享服务 | 文件锁 + revision + 原子替换，不散写 agent.properties |
| 工作区实例配置 | `<workspace>/.core-ai/plugin-state.json`；共享服务 | 可表达安装意图，不能表达本机信任授权 |
| 工作区信任与授权 | 用户侧 `~/.core-ai/plugin-store/grants.json`；共享授权服务 | 不从仓库文件导入“已授权”；绑定 canonical workspace 和 digest |
| Desktop 私有 UI state | app userData 下插件命名空间 | 只能通过 storage API 访问自己命名空间；不与包一起删除 |
| sensitive config | SecretService 的 secret reference | 不存 core settings/manifest/普通 state，不返 raw credential 给 UI |

文件权限、锁和 checksum 用于正常进程一致性/完整性；同用户任意本地代码能够读改这些文件，不能称为抗同 UID 攻击边界。

### 4.3 Scope 与冲突

- 用户级安装不等于所有工作区默认激活；授权可限制 workspace 集合。
- workspace ID 使用共享 Java canonicalizer 解析绝对/real path，并处理 Windows 大小写、junction 与目录不存在情况；TS 不另造哈希规则。
- 同 ID 的 local/global 安装冲突必须显式选择并显示双方来源，不默默按目录名覆盖高信任全局包。trust 必须绑定所选 digest；换来源或路径失去旧授权。
- 原 `plugin.disabled` 只作为兼容迁移输入；新版停止自动从旧目录加载/解压执行插件，JAR 内置包更新也改走 PluginServices snapshot/事务；工作区 `.core-ai/plugins/` 只是 Discovered 候选，快照纳管、默认禁用、授权后才激活。
- **不能逆向约束已发布旧 CLI**：旧程序仍可能自行改旧目录/执行旧 hooks，与本机制不构成安全互操作。新版 Desktop 只使用满足 safe-bootstrap/extension API 的 pinned binary，启动前查 inert version/features；旧外部引擎不支持则拒绝接入。旧 CLI 混用告警并建议升级，不能声称它会自动遵守新 grants。
- 导入旧目录先 snapshot + 校验 + 审阅，再纳管；不把用户修改的目录自动替换为托管版本。不删除、覆盖既有用户/Hub/内置技能副本。

### 4.4 共享服务从 TerminalUI 解耦

将 PluginInstaller/Validator/Discovery/StateManager 拆为无 UI 服务，CLI 只负责命令输出/确认，app-server 只负责 RPC 转换。两者使用同一 parser、锁、状态事务与来源策略。

**无工作区也能管理插件**：拟新增随应用 pin 的 Java CLI `plugin ... --json` 控制面模式，只做 metadata/安装/状态，不初始化 LLM、MCP、hooks、session、调度或自动 provision。Desktop 从受信任的内置引擎路径直接调用，不经 shell、不读 PATH。Windows packaged native binary直接 spawn；开发态 .bat/.cmd 使用与 EngineSupervisor 相同的 `java -classpath lib/* Main` 直调，不经 cmd.exe，不拼接命令字符串。metadata命令不获取 EngineLock，运行中工作区不阻塞查看/校验；批量 inspect缓存按revision失效并测量冷启动成本，不为优化另起常驻任意执行进程。这样 Settings → Plugins 不需要启动任意工作区完整 Engine。

已有 app-server 增加拟议 `plugins/list|validate|activate|deactivate|changes`，用于所属工作区运行实例；全局安装/授予由共享控制面服务处理。方法名和响应契约经 P1 冻结，不对外承诺仍未实现的 RPC。

- 管理动作返回结构化 `operationId / stateRevision / affectedScopes / pendingApply / failures`，显示“已安装但未激活”“等待安全点”，不把磁盘写入当成已运行。
- global 更新通过锁与 revision 广播/重查；每个工作区 Engine 按 revision 校准，防止多引擎和 CLI 并行失更。
- 校验、准备、用户确认、激活是独立操作；不提供能够在 `list/validate` 中触发执行的 shortcut。

### 4.5 持久 epoch、锁与跨进程撤销（P1 必须实现）

**授权真源在共享持久 store，不在 Desktop UI 内存。** `grants.json` 保持单调递增 `grantEpoch` 和 per-scope grant revision；revoke 与新增授权均由共享服务事务提交。删除 grants 文件、损坏 JSON 或无法锁定不能按无授权状态默认放行。

- 所有 package/state/grant/workspace 引用的变更使用用户侧同一个 `plugin-store/control.lock`；不各文件独立抢锁。事务先写临时数据并持久 commit journal/revision，再发布 atomic snapshot；中断恢复以 journal 为准。
- 调用 gate 在该控制锁下读取当前有效 epoch/引用，并做短暂授权线性化；不持锁等待外部执行。revoke 在提交点之后不允许新的受控调用进入，已经进入的调用视为在途工作并 best-effort 取消。描述“立即”以 commit 点为边界，不声称能倒回外部动作。
- 普通 metadata control mode 不获取 workspace EngineLock；只有需要 Engine 运行实例操作才走 app-server。锁顺序固定 `EngineLock（如需要）→ control.lock`；metadata/GC 只拿 control.lock，绝不在持 control.lock 时等待 Engine/IPC/用户确认。短调用 gate不需要额外逐session锁。
- 所有启动/工具 dispatch/hook/MCP invocation 在 Java ActivationGate 下读当前授权 snapshot；P1 不用只凭mtime/旧缓存绕过gate。后续缓存优化必须证明commit后不会有新授权准入竞态。
- **Desktop Node不自行实现/假定兼容Java FileChannel锁。** P2a session.stats等插件请求通过已绑定的pinned Java Engine（trusted或restricted中的纯授权端点）做短gate与操作，复用常驻RPC，不逐调用spawn CLI。Settings配置/授权变更走共享Java控制事务；Main仅校验caller并请求Java发放一次性admissionId，不拿旧epoch内存缓存自行批准。
- 宿主侧需执行的操作在Java gate线性化后记为已准入在途请求，admissionId绑定instance/resource/参数摘要、单次消费与短期限，未在revocation后重新排队或扩权；取消仍best-effort。跨进程已准入但未执行的窄窗口属于在途范围，不能声称全局commit后没有任何已获准副作用发生。P2a不开放此类外部副作用。
- app级高频插件broker不在P2a范围；P2b若确有需求，可由同一Java PluginServices提供inert常驻stdio授权端点，不初始化workspace/LLM/hooks/MCP。该端点只复用同一store/lock，不另建状态真源；须单独评估进程寿命/冷启动/退出预算。不以Node无锁快照读替代严格授权线性化；fs.watch只用于UI与在途取消加速。
- Desktop 主动 revoke：先持久 commit，再向已知活跃引擎送拟议 `plugins/revoke {scope, revision}`，本地 broker 同步废弃 epoch，取消队列并执行 cancel/停进程。未 ACK 显示“授权已撤销，进程清理待确认”；无法证明 cleanup 不显示全成功。
- 外部 CLI revoke：共享持久 commit 保证各 Engine 下一次受控入口检查即拒绝；Desktop/Engine 用 WatchService/fs.watch + 拟议 ≤1s 有界重查发现需取消的在途工作。watch 是加速非授权真源，漏事件不影响下一次 gate。不承诺外部短命 CLI 提交后所有在途可信本机进程瞬时停止。
- pendingActivate/upgrade 必须在实际激活时校验最新 epoch；旧 revision 通知不能覆盖 revoke。进程间事件只传变化摘要，消费者重读 snapshot。
- P1 GC仅显式执行，在control.lock下核对安装引用、EngineLock与进程身份/存活（含启动时间防PID重用）；存在活跃/无法证明已停的引擎或准备操作时保守拒绝回收，不另加后台心跳线程。P2/P3非Engine UI/Host实例增加持久reference/lease与宿主生命周期校准后再开放对应回收；不因一次超时就删活跃包。
- 该锁/epoch方案保护符合协议的新程序并发，不能阻止旧 CLI或同 UID 任意 OS 代码直接绕过。兼容承诺见 §4.3。

### 4.6 最小管理 UX

v0.5：一级导航 **Plugins** 承载本地包管理、Server registry 与 workspace trust 入口；Settings → Plugins 保留摘要和“Open Plugins page”跳转，避免用户只在设置里找功能。

- 展示 source/scope/version/digest 摘要、可用构件、风险、授权与实际运行状态。
- 支持目录/zip 导入、校验报告、安装、审阅授权、启停、升级比较、回滚、卸载；P1 不提供市场或 npm 安装。
- 安装同意页明确“会启动本机代码 / 可访问本机 OS”的构件，敏感能力逐项显示。
- 未同意、API 不兼容、依赖缺失、冲突、quarantined、等待安全点各有独立原因。
- enable toggle 是请求，不得先显示已启用再后台忽略失败；撤销与普通 disable 使用明确不同文案。
- 卸载默认保留配置/历史，删除私有数据是额外显式操作；不删除从该插件曾产出的用户交付物。

### 4.7 Server 插件注册中心（v0.5 已实现）

注册中心只做**发现 + 摘要固定**，不托管包字节：条目保存 manifest、`download_url`、`sha256`、size 与能力/构件摘要，包仍放在 GitHub Release/对象存储等 URL。

- API：`GET /api/plugins`（query/offset/limit）、`GET /api/plugins/:id`、`POST /api/plugins`（管理）、`DELETE /api/plugins/:id`（管理）。
- 权限：`plugin.view` 给默认 user；`plugin.manage` 仅 admin（发布可执行包在来源认证/签名落地前不放开）。
- `POST` 请求只收 `manifest + download_url + sha256 + size + vendor/homepage`；id/name/version/required/optional/components 全部从 manifest 派生，避免 registry 与包不一致。
- 校验：coreai schemaVersion=1、id 小写命名空间、能力白名单、sha256 64 hex、size ≤256MB、URL 必须 http(s)。
- 存储：Mongo `plugins` collection 与 PG `app.plugins` 双实现，跟随 `sys.store.mode`；PG migration `V43__plugins.sql`。
- Desktop：主进程用当前登录的 `auth.json` 调 registry；下载只发同源 Authorization，校验 sha256/size 后才交给 `PluginHost.installZip`；包仍 installed-disabled，需用户授权激活。


## 5. IPC、能力授权与可信根

### 5.1 两条独立桥

- **Core bridge**：仅应用主 renderer 的固定 preload 使用；所有 core `ipcMain.handle` 验证 `event.sender === mainWindow.webContents` 且 senderFrame 为该 webContents.mainFrame（未来多窗口只改可信窗口注册表，不放开 frame）。同时验证参数、已知 workspace、method allowlist，插件 WebContents 与子 frame 不能调用。
- **Plugin bridge**：插件专用 preload / MessagePort，仅开放按能力批准的接口；不暴露 `ipcRenderer`、全量 `window.coreai.call`、`settings:set`、`session/approve`、原始 CDP endpoint、CUA token、Provider/apiKey 或 plugin grant 写接口。

拟新增 `src/shared/contracts/`，按 `engine/plugins/ui/settings` 域维护 TS 类型、运行时 schema 和错误码；Java/TS 跨语言 DTO 通过统一 schema fixture/codegen 或契约测试对齐。仅有 TypeScript 类型不是运行时验证。

### 5.2 Caller-bound 请求

可信宿主在创建 UI/Host 时分配 `PluginInstanceId`，绑定 package digest、scope、workspace/session、webContents/frame/MessagePort 和 activation epoch。请求只携带方法、requestId 和参数；身份从受信任连接上下文取得，不允许插件任意自报 ID 或 workspace。

每次实际调用校验：

1. 连接仍属于当前实例；旧页面、重启 Host、关闭 workspace 后的 port 全部失效。
2. 实例处于 active、digest/API 匹配、grant revision 未撤销。
3. method 属于明确 capability；resource 属于允许 scope，路径/域名/会话归属合法。
4. 当前操作符合额度、频率、大小、超时和用户外部副作用策略。
5. 在 commit/start 执行点再次检查授权，避免排队任务在撤销后执行。

插件不能通过“先申请另一个 workspace”“返回核心跳转 URL”“触发用户消息”间接绕过以上约束。内容字段/HTML/事件按不可信输入处理。

### 5.3 安装授权与操作审批是两层

| 层 | 保护什么 | 行为 |
|---|---|---|
| Package/source trust | 是否接受这个来源/digest 的代码与内容 | legacy/local 包必须显式审阅；内容变化导致重新评估 |
| Capability grant / workspace trust | 在何处激活哪些构件、可以请求哪些服务 | 必需能力未同意不能激活；可选拒绝则降级 |
| Per-operation approval | 当前发送/删除/写入/执行等副作用是否允许 | 复用真实工具审批/宿主确认；包安装确认不授予无限副作用 |

一般只读操作在已有 grant 下不重复弹窗；高风险与外部副作用遵守真实执行层策略。插件不能宣布“用户点过按钮”就自己批准操作。GUI 与 headless CLI 使用同一授权规则；非交互式无法取得必需授权则 fail-closed，不自动同意。

### 5.4 能力词表（一期示意，不是全部开放）

| 能力 | 可开放范围 | 默认 / 阶段 | 强制边界适用层 |
|---|---|---|---|
| `ui.navigation/settings/dock` | 验证描述与受限 view | P2a 仅 dock/settings，navigation后置 | 宿主贡献与受限 WebContents |
| `ui.pages` | 应用级/工作区级插件页 + 独立 view | P2c，页面是宿主导航一等项 | 宿主 chrome + 实例 WebContentsView |
| `media.models.read` | 已配置图片/视频模型槽摘要，不含 key | P2c | Engine MediaService（模型目录） |
| `media.assets.read/write` | 页面 scope 的素材 handle；导入/删除需确认 | P2c 不默认 | Engine/Artifact 服务 + 实例协议 |
| `media.generate` | 直接生成图片/视频，返回 jobId；Engine 解析 provider | P2c 计费动作，受预算/确认 | Engine `MediaGenerationService` → 既有 `MediaProvider` |
| `media.jobs.read` | job 列表/状态/取消/订阅 | P2c | 宿主 job 服务，跨页面存活 |
| `session.stats.read` | 绑定会话统计 | P2a 可选只读 | Broker/受限 UI |
| `session.history.read` | 绑定会话分页脱敏记录 | P2b 敏感单独授权 | Broker/受限 UI |
| `workspace.files.read/write` | 指定 handle/realpath资源，排除核心secret/state | P2b 不默认 | 仅broker路径/真受限runtime，不限制任意本机代码 |
| `network.request` | 允许HTTPS域名/方法、SSRF/redirect/DNS防护 | P2b 不默认 | 宿主代理，不限制hooks/MCP直接OS联网 |
| `channel.send` | 指定目标/当次操作 | 后置高风险，发送前审批 | 经过broker的发送 |
| `model.invoke` | 预算/模型槽，返回响应不返key | P2b/P3按需 | 宿主代理，不开放provider配置写入 |
| `hook.execute / mcp.spawn` | 明示信任来源与本机代码启动 | P1专门trust/启动授权 | 只gate受管启动/调用；代码运行时有OS用户权限 |
| `browser.control / computer.control` | 专门受控服务 | 不在P1/P2a第三方开放 | 后续独立安全设计 |

不要把全量 Shell API 作为通用插件能力。任意脚本/MCP 本身获得 OS 用户权限，无法由以上 broker 词表限制其直接 OS 调用；只能在可信模式中明确披露，或换用真受限运行时。

### 5.5 Secret 与可信根

SecretService 属于 P2b/P3 按需新增的可信宿主能力，不作为 P1/P2a 前置；插件设置仅持 opaque reference。P1 不接受任意插件 secret 注入，新增之前先设计 OS vault 与 Engine 获取方式。

当前 `auth.json` 与 provider JSON 仍可能包含明文 key，本设计不声称已有 keychain；迁移现有凭据是独立兼容任务，不能静默删除 CLI 仍依赖的字段。即使迁移 OS vault，也不把同用户可信 Node 的权限等同于隔离。

插件不得替换授权页面、审批裁决或更新验证服务，不得访问 grant 存储和核心配置写接口；禁止用插件 view 承载或冒充核心授权弹窗，宿主的权限提示带固定来源标识。

### 5.6 撤销的可保证范围

- Broker-controlled 调用：撤销立即增加 grant epoch，关闭 port，取消排队工作，执行点拒绝旧 epoch；必要时 cancel turn/stop worker，已提交外部动作不能倒回。
- 受限 UI：卸载 view 与 listeners，并销毁其隔离资源；历史 fallback 仍保留。
- 可信任意 Node / shell / MCP：停止受管进程树是 best-effort，不能证明其派生脱管程序或先前复制的凭据已被消除。严谨的立即隔离保证只适用于经过真受限运行时/OS 沙箱验证的模式。


## 6. Renderer、UI 贡献与会话状态重构

### 6.1 app.ts 按责任拆分

| 拟拆模块 | 所有者 / 责任 | 明确不承担 |
|---|---|---|
| `ShellLayout` | 导航、layout、加载/错误状态、主 UI 路由 | 直接处理工具事件与包安装 |
| `SessionStore` | scoped session 数据、状态、事件归约、分页与快照 | 保存 HTMLElement、暴露可变 Map 给插件 |
| `ConversationView` / `TranscriptRenderer` | 根据状态渲染 live/history、工具卡/标准 fallback | 拥有授权真源或引擎生命周期 |
| `ContributionRegistry` | contribution ID、类型、owner、顺序、可见条件与卸载 | 任意执行插件 render function |
| `DockCoordinator` | HTML/native view bounds、z-order、焦点、fullscreen、最小化/关闭清理 | 让各插件独立覆盖主窗口 bounds |
| `FeaturePanels` | artifacts/skills/memory/schedules/hub/settings 独立模块 | 读取其他模块的全局可变 state |

拆分按现有行为进行，不同期替换 UI 框架/事件语义。先补回归 fixture，迁移一个功能验证，再移下一个，避免 9000 行一次性移动造成不可审阅 diff。

### 6.2 贡献注册契约

P2a 只开放只读 Dock 与声明式 Settings；以下列出目标槽位总览，其余属于 P2b 按实际需求扩展，不是最小交付前置。插件描述只能声明受支持槽位；宿主按固定 schema 构建导航、容器与动作。

| 首批槽位 | 描述内容 | 宿主规则 |
|---|---|---|
| Page | ID、title、icon、view、scope、页面能力 | 宿主导航一等项；页面 chrome/错误/授权由宿主绘制 |
| Navigation | ID、title、内置 icon、view、scope、可见条件 | 核心项保留顺序；插件项分组，禁止冒充 Settings/审批 |
| Settings | section、字段 schema 或受限 view | 核心配置与插件配置分开；sensitive 字段只由宿主采集 |
| Dock | title、view、scope | 注册 ID 替换封闭 union；不直接访问 BrowserManager |
| Composer action | 标题、图标、宿主认可 action | 生成草稿与发送分开；不能隐藏发送/绕过外部副作用审批 |
| Tool-result renderer | 精确 owner/provider/tool/schema 匹配 | 只收该工具脱敏 payload，不能读所有消息；标准卡片 fallback |
| Artifact viewer | MIME/扩展名、优先级、受限 view | 只拿选定 artifact handle；不用 workspace-wide token |

注册需返回 disposable；同 ID 冲突拒绝，顺序使用显式 priority + 稳定 ID tie-break。内置组件可注册受信任函数，第三方 descriptor 只能指向受限 view/标准模板，不能跨越 trusted/untrusted 两种执行模式。

### 6.3 State 与 scope

- 身份采用 `{backend, canonicalWorkspaceId, sessionId}`；为远端直聊保留 backend 字段，不实施该独立提案。
- app 级实例是后台能力，workspace 级在明确 workspace 激活，session view 状态属于绑定会话；切 UI 会话不改变后台实例归属。
- 当前 detached DOM 保活方案逐步迁移，先保留现有行为；不得向插件开放历史 DOM/StreamingState 引用。
- 插件 storage 为自己的 namespace + schemaVersion，限制单项/总量，提供迁移与损坏降级；卸载默认不删数据。
- core 的 session source-of-truth 仍在 Engine。第三方不得直接解析 `.data/titles.json` 作为稳定 API，应使用有权限的分页 DTO。
- 失效导航/Dock ID 回退到安全默认页，不保留失联 view；后台事件只更新所属 scope，不污染当前会话。

### 6.4 UI 隔离与资源供给

**第三方带脚本完整 UI 固定使用独立 WebContentsView。** 它拥有 `sandbox: true`、`contextIsolation: true`、`nodeIntegration: false`、实例专用临时非持久 partition、宿主固定 preload + MessageChannelMain/port。普通主页面 iframe 不能分配独立 Electron partition/preload，不用于此第三方安全边界；主 UI 可继续宿主零脚本 card 或既有只读 artifact iframe，不混用两种模型。

默认拒绝 permission/navigation/window.open/download；禁远程 iframe/脚本，CSP 默认 `connect-src 'none'`，网络经 broker 且受允许列表/SSRF 防护控制。插件 CSS/脚本只作用于自己的 view，不能影响核心主题、授权页面或其他插件。敏感宿主授权使用固定 core UI，不由插件 view 渲染。

资产 scheme privileges 在 app ready 前声明；实际 handler 只注册到实例专用 `session.protocol`，提供当前 digest 已验证包文件，不支持 file:// 任意读；插件私有 storage 与 assets 分开。

**插件 partition 不注册 `coreai-artifact://` 宽读 handler。** 它是可推导 workspace 定位而不是授权；专用 `coreai-artifact-handle://`（拟议）仅识别该 instance/epoch 获授的选定文件 handle，revoked/旧epoch/错误partition返回拒绝。scheme privilege 的全局声明不等于 handler 全局开放；需实际跨 partition probe 验证不可读。插件 view 不共享浏览器 cookie、CDP token 或 defaultSession 权限处理器。

P2 spike 只评估 WebContentsView 的 bounds、焦点、隐藏截图、z-order 与 PDF/媒体能力，不把普通 iframe 作为独立 partition 等价备选。不能只设置 sandbox 字段就通过安全验收。

### 6.5 自定义事件与长期历史

新增事件 envelope 的拟议字段：`pluginId / contributionId / schemaVersion / sourceDigest / scope / data / fallbackCard|fallbackText`。来源由 Engine/host 注入，不能信任工具输出自报。

- 第三方未知事件必须带标准 fallback；加载历史不激活插件、不重跑脚本、不请求外部资源恢复旧状态。
- 插件缺失、禁用、版本不兼容时展示只读 RichCard/文本摘要，标明来源与不可交互原因。
- 历史表单与 action 默认只读，不执行旧副作用。实时交互通过当前 scope broker，callback 过期后明确提示，不自动发送新消息。
- 标准 RichCard 词表只增不乱扩；复杂完整页面使用受限 view，不把任意 HTML 混进 core card schema。
- 终态与审批事件由核心处理，插件不得拦截、吞掉、修改工具审批结果。

### 6.6 插件页面与直连动作面（v0.9 已实现首期，未发布）

**RichCard 是 agent/skill 的输出词表，不是插件展示层。** RichCard 绑定“一条消息/一次工具结果”，适合 transcript 和只读历史；媒体工作台需要导航入口、长期状态、素材管理、任务队列和直接动作，不能靠往对话里塞卡片实现。二者关系是：页面负责工作流，RichCard 只作为页面的输出摘要或历史 fallback。

#### 6.6.1 页面贡献

```json
{
  "contributes": {
    "pages": [{
      "id": "studio",
      "title": "Media Studio",
      "icon": "image",
      "view": "ui/studio.html",
      "scope": "app",
      "capabilities": ["media.assets.read", "media.assets.write", "media.generate", "media.jobs.read"]
    }]
  }
}
```

- 宿主在左侧导航增加插件页；`scope=app` 不绑定会话/工作区，`scope=workspace` 只用当前 workspace 的素材。
- 页面是**宿主 chrome + 插件 view**：导航、标题、错误、授权提示由宿主绘制；插件只拥有内容区。
- 页面实例独立于对话回合；切换会话不销毁，关闭页面/停用插件才释放。生成任务由宿主 job 服务持有，页面关闭不取消任务。

#### 6.6.2 直连动作面，不走 agent loop

页面通过专用 `window.coreaiPlugin` bridge 调用宿主能力，**不调用 `use_skill`、不创建会话、不经过主 agent；也不新增第二套生成器**：

| 能力 | 宿主实现 | 说明 |
|---|---|---|
| `media.assets.read` | `assets.list/get/preview` 返回 host-issued handle | 只读当前页面 scope 的素材；预览走实例协议，不给 workspace 宽 token |
| `media.assets.write` | `assets.import/delete/tag` | 导入走宿主文件选择/staging；删除需确认；写入插件命名空间或 workspace media 目录 |
| `media.models.read` | 返回已配置的图片/视频模型槽摘要 | 只给 model id/能力，不给 provider key/base URL |
| `media.generate` | app-server `media/generate` → 抽取后的 `MediaGenerationService` → 既有 `MediaProvider` | 复用 `GenerateImageTool`/`GenerateVideoTool` 的 provider、reference、artifact 逻辑；宿主解析 credential |
| `media.jobs.read` | `jobs.list/get/cancel/subscribe` | job 状态独立于 agent turn；进度/失败/重试通过事件推送 |

- **现有工具已经有生成能力，缺的是非 agent 调用面。** `MediaGenerationService` 是从 `GenerateImageTool`/`GenerateVideoTool` 内部抽出的共享服务，工具本身改为调用它；不要在页面侧重写 provider、reference 解析或 artifact 落盘。
- **为什么不直接调用工具**：tool 是模型面向的，依赖 `ExecutionContext`、session、tool approval 和 model-facing result；video 任务状态也按 session 保存。页面需要稳定的 job API、跨页面状态和无 agent 的素材结果，直接 `execute` 会绕过审批并复用错误的抽象。
- 首期 `MediaGenerationService` 放在 Engine（provider、`MediaModelCatalog`、reference 解析、`LocalArtifactSink` 和现有 video polling 都在那里）；主进程只做 capability/credential 边界与转发，不解析模型协议。
- app 级页面若不需要 workspace，可先用默认 workspace 的 Engine；后续接 server gateway 时仍在同一 `MediaService` 接口后替换实现，不新增第三种生成路径。
- 生成结果写入 artifact/media 目录并登记 `pluginId/pageId` 归属；页面可以直接列出自己的输出，不必回到某个会话。
- 页面 bridge 的方法、resource、参数摘要、次数/预算都由主进程校验；页面不能自报 pluginId/workspace，也不能申请其他页面的 handle。
- 页面生命周期与能力 epoch 绑定；撤销 `media.generate` 后新任务立即拒绝，进行中的 job 由 Engine job 服务标记并 best-effort 取消。

#### 6.6.3 隔离与 SDK

- 页面 view 仍使用 §6.4 的独立 `WebContentsView` + 每实例 partition + 固定 preload；页面 JS 是任意代码，但不是可信代码：只能通过 bridge 的窄 API 行动，不能读 Node/fs/主 renderer/engine 全量 RPC。
- 页面资产用实例协议提供，CSP 默认 `connect-src 'none'`；素材/生成结果预览用 host handle，不复用 `coreai-artifact://` 的 workspace token。
- SDK 在 `sdk/core-ai-plugin` 增加 `ui` 子入口：`coreaiPlugin` bridge 类型、页面 manifest 类型、资产/job 数据结构；不强制 React，页面可用任意前端框架或原生 JS。
- MCP Apps 可以作为**可选渲染/协议适配**（iframe + `ui/*` bridge），但页面生命周期、导航、素材/job 状态归本设计所有；本地插件不要求先有 MCP server，也不把页面绑定到某次 tool call。

#### 6.6.4 典型媒体工作台流程

1. 用户打开左侧 **Media Studio**（插件页），宿主创建独立 view 并检查 `ui.pages` + media grants。
2. 页面 `assets.list` 展示历史素材；用户 `assets.import` 拖入参考图，宿主返回 handle。
3. 页面选择模型（`models.list`）、填 prompt、选素材 handle，点“生成”；`media.generate` 返回 jobId，页面订阅进度。
4. Engine 的 `MediaGenerationService` 调用既有 `MediaProvider`，复用参考图解析与 artifact sink；页面显示缩略图/播放器，可继续生成、取消、重试或删除自己的素材。
5. 关闭页面后 job 继续；再打开页面通过 `jobs.list` 恢复状态。整个过程不创建会话、不触发 agent turn。
6. 若插件被禁用/撤销，页面立即关闭，新 action 拒绝；历史输出仍可从 Artifacts 访问。

#### 6.6.5 非目标

- 不允许页面直接读任意工作区文件、Provider key、审批裁决或其他会话数据。

#### 6.6.6 首期实现状态（v0.9，未发布）

| 项目 | 状态 |
|---|---|
| `contributes.pages` + `ui.pages` + 页面导航 | 已实现；页面在左侧导航显示，打开时重新校验 active/grant |
| 独立 `WebContentsView` + 每实例 partition + 固定 preload | 已实现；`sandbox`、`contextIsolation`、无 Node、无网络 |
| `coreai-plugin-page://` / `coreai-plugin-asset://` | 已实现；asset 协议只在 `media.assets.read` 授权时安装，且只服务 `.core-ai/media/` |
| `coreaiPlugin.context` / `assets.list` / `assets.import` / `media.models` / `media.generate` / `media.jobs.get/list` | 已实现；每次 bridge 调用重新解析 grant，撤销/禁用后立即拒绝 |
| 非 agent 生成面 | 实现为 `MediaGenerationApi`，直接复用既有 `MediaProvider`；未把 `GenerateImageTool`/`GenerateVideoTool` 的 reference/sink 编排抽成同一个类 |
| 素材导入与参考图 | 已实现 `assets.import`（png/jpg/webp/gif，≤10 MiB，写入 `.core-ai/media/imports`）和 `inputImages`/`inputReferences` 路径解析；参考图只允许 `.core-ai/media/` 下文件 |
| 媒体工作台示例 | `examples/plugins/media-studio/`；只读素材 + 参考图导入 + 单模型图片/视频生成 |
| `media.assets.delete` / rename、`jobs.cancel/subscribe`、跨 Engine 重启 job、mask、reference video/audio | 未实现 |
| app/workspace scope 行为差异 | 未实现；两种 scope 目前都绑定当前 workspace 的 media 目录 |
- 不把 RichCard 扩展成页面协议，也不允许插件向 transcript 注入任意 HTML。
- 首期不允许页面自带任意 Node 后端；需要自定义生成逻辑时走 P3 的受管插件后端/受限运行时。

### 6.7 扩展能力地图（DSH 生态调研，v0.7 设计）

调研对象：`dsh.pub`（14,856 installable）、top-dsh-plugins 热度榜、DSH slots 文档。dsh.pub 的分类量级说明 UI-only 只是少数：Interface & interaction 2,864、General 1,961、Models & prompts 1,044、Model tools 1,007、Code/dev 1,002、Usage/cost 928、Workflow 902、Runtime 872、Sessions 848、Vision/media 795、Memory 688、Data/storage 377、Browsing 304、External 224。

热门插件不是“多几个按钮”，而是持续读写宿主数据、订阅事件或替换运行时 provider。我们要预留的是下面六类能力，而不是只加页面：

| 能力面 | DSH 代表插件 | 建议预留的能力 ID | 风险/阶段 |
|---|---|---|---|
| 展示 | dsh-web-ui-all、DSH-better-sidebar、genui、dsh-full-view、dsh-skin | `ui.pages`、`ui.slots`、`ui.renderers`、`ui.theme` | 中；P2c/P2d |
| 观察 | dsh-notification、dsh-usage-card、dsh-cost-meter、dsh-flowglass、dsh-context | `events.session`、`events.turn`、`events.tool`、`usage.read` | 中；只读事件，P2d |
| 读数据 | hindsight/OpenViking、session insights、dsh-chat-import、DSH-better-sidebar、git graph | `sessions.read`、`workspace.read`、`memory.read`、`models.read`、`jobs.read`、`assets.read` | 低-中；P2d |
| 写动作 | memory 类、dsh-turn-rewind、dsh-chat-import、dsh-notifier、dsh-automation、file edit | `memory.write`、`sessions.write`、`workspace.write`、`notify.send`、`schedules.write`、`artifacts.write` | 高；逐项授权/审批，P2d |
| 运行时 | dsh-plugin-subscriptions、dsh-llm-autorouter、dsh-tool-drawer、dsh-lsp、dsh-browser、agent-teams、mirage | `tools.register`、`providers.register`、`prompt.sections`、`context.strategy`、`hooks.guard`、`browser.control`、`terminal.exec`、`sandbox.provider`、`agents.orchestrate` | 高；可信代码/真沙箱，P3 |
| 平台 | dsh-market、dsh-plugin-deploy、dsh-settings-ui、user-management | `registry.*`、`plugin.storage`、`plugin.jobs`、`plugin.secrets` | 中；P2d |

代表性插件映射（用来说明能力，不是要逐一实现）：

- **memory（hindsight、OpenViking、mnemon）**：需要 memory 读写 + prompt 注入 + session/turn 事件；不是 UI 问题。
- **UI 集合（dsh-web-ui-all、DSH-better-sidebar）**：需要 sidebar 行/底部 action、conversation view tab、right sidebar tab、message action、shell overlay 等 slot，以及主题 token。
- **archify / genui / full-view**：需要 tool + 自定义 message/tool renderer，或在受限 view 中渲染 HTML/React。
- **dsh-market / settings-ui**：需要 registry、安装/更新、插件配置 UI 和 slot 自定义。
- **agent-teams / automation**：需要 session/subagent/job/schedule 与事件订阅，不只是“工具”。
- **TongFlow / dsh-image-gen**：媒体页面 + asset/job/generation；即 §6.6 的场景。
- **provider 类（subscriptions/autorouter）**：需要模型 provider 注册、路由、用量/限速；高风险。
- **BrowserSkill / dsh-browser**：需要 browser session/tab 控制与页面上下文；高风险。
- **cost/usage/notification**：需要 usage 事件、余额/成本读取、通知通道；多为只读 + 一个发送动作。

#### 6.7.1 现在就要冻结的契约（不必现在实现）

1. **稳定 slot ID 与 scope**：`ui.slots[]` 接受 `page`、`sidebar.*`、`composer.*`、`conversation.*`、`settings.*`、`rightSidebar.*`、`shell.overlay`；宿主只渲染已声明的 slot，未知 slot 报错而不是猜。
2. **能力命名空间**：`ui.* / events.* / sessions.* / workspace.* / memory.* / usage.read / models.read / jobs.* / assets.* / notify.send / schedules.* / tools.register / providers.register / prompt.* / context.* / hooks.* / browser.* / terminal.* / sandbox.* / agents.*`；每个能力必须有明确 read/write 与 scope。
3. **`provides` / `requires` 服务声明**：host 插件可提供 `memory.provider`、`notification.channel`、`model.provider`、`usage.reader`、`session.exporter` 等服务，其他插件通过 `requires` 引用；由宿主注入，不直接互相 require。
4. **事件订阅 allowlist**：`session.*`、`turn.*`、`tool.*`、`message.*`、`usage.*`、`job.*`、`workspace.changed`；payload 结构化、去敏、带 scope；不暴露原始 DOM/内部对象。
5. **插件存储/任务/秘密引用**：namespaced KV、job API、secret reference；插件不直接读核心 settings/auth 文件。
6. **三类 UI 承载**：声明式 block（零脚本）、受限 view（页面/面板）、宿主 renderer（tool/message/artifact）；不允许第三方 React 直接进主 renderer。

#### 6.7.2 信任分层与阶段

- **Tier 0（只读）**：events 订阅、sessions/usage/workspace/memory/models/jobs/assets 读、声明式 UI。适合 usage card、context dashboard、notification、session insight。
- **Tier 1（受控写）**：memory write、workspace write、session rename/import/export/handoff、notify.send、schedules.write；逐能力授权 + 当次确认/审计。
- **Tier 2（运行时）**：tools/providers/prompt/context/hooks/browser/terminal/sandbox/agent orchestration；必须可信代码或真受限运行时，P3 单独验收。

**不建议照抄 DSH 的“宿主内 React slot + 无沙箱”模型。** 我们保留同样的扩展点语义，但用独立 view + 窄 bridge + capability gate 承载；这样既能支持热门插件形态，又不把宿主权限直接交给第三方代码。

#### 6.7.3 预留契约草案（v0.8，字段名冻结、运行时未实现）

Manifest 预留字段（validator 可识别，未实现的贡献返回 `unsupported_component`，不静默激活）：

```json
{
  "coreai": {
    "provides": [{ "id": "memory.provider", "version": "1.0" }],
    "requires": ["memory.read", "events.session"],
    "contributes": {
      "pages": [{ "id": "studio", "title": "Studio", "view": "ui/studio.html", "scope": "app" }],
      "slots": [{ "slot": "composer.dock", "id": "status", "view": "ui/dock.html", "order": 100 }],
      "renderers": [{ "id": "tool-preview", "kind": "tool-result", "match": { "tool": "generate_image" }, "view": "ui/result.html" }]
    },
    "events": { "subscribe": ["session.turn.completed", "tool.finished", "usage.recorded"] }
  }
}
```

首批 slot ID（稳定、带 scope/kind；宿主只渲染已声明项）：

| Slot | Kind / scope | 用途 |
|---|---|---|
| `page` | single / app/workspace | 独立插件页面 |
| `sidebar.footer.action` | list / root | 侧栏底部动作 |
| `sidebar.session.row` | keyed / session | 会话行徽标/操作 |
| `composer.left` / `composer.right` | list / session | 输入框两侧按钮 |
| `composer.dock` / `composer.overlay` | list / session | 输入框上方/浮层 |
| `conversation.view` | list / session | 会话视图 tab |
| `conversation.session.header.actions` | list / session | 会话标题右侧动作 |
| `conversation.message.actions` | list / message | 单条消息动作 |
| `conversation.turn.tail` | chain / turn | 回合尾部摘要/文件 |
| `conversation.node` | keyed / session | 自定义聊天节点 |
| `settings.section` / `settings.general.item` / `settings.plugins.tab` | list / root | 设置页/行/tab |
| `rightSidebar.tab` | keyed / session | 右侧栏 tab |
| `shell.overlay` | list / root | 全局弹层/Toast |

事件 envelope 统一为：

```json
{
  "type": "session.turn.completed",
  "version": 1,
  "occurredAt": "2026-11-17T00:00:00Z",
  "scope": { "workspaceId": "...", "sessionId": "...", "pluginPageId": "..." },
  "data": {},
  "redacted": true
}
```

- allowlist：`session.started|completed`、`turn.started|completed|failed`、`tool.started|finished|failed`、`message.appended`、`usage.recorded`、`job.updated`、`workspace.changed`；payload 结构化、去敏、带 scope。
- 不提供原始 DOM、完整消息正文、Provider key、审批内部对象；需要正文时由宿主按 capability 返回脱敏分页 DTO。

服务契约（宿主注入，插件不直接互相 import）：

| 服务 | 方法（草案） | 风险 |
|---|---|---|
| `memory.provider` | `search(query, scope)` / `write(entry)` / `list(scope)` | 写 Tier 1 |
| `notification.channel` | `send(target, text, meta)` | 发送 Tier 1，需确认/审计 |
| `usage.reader` | `summary(range, scope)` | 只读 |
| `session.exporter` | `export(sessionId, format)` | 只读/敏感 |
| `model.provider` | `listModels` / `route(request)` | P3 Tier 2 |

能力描述建议统一为 `{id, risk: read|write|execute, scope: app|workspace|session, approval: grant|per-action}`；`read` 授权后免逐次弹窗，`write/execute` 按风险走当次确认或预算。**v0.8 只冻结字段名、ID 与风险分类；P2c/P2d/P3 才分别实现运行时。**

#### 6.7.4 P2d 首期实现状态（v0.11，未实现 usage/写动作）

| 项目 | 状态 |
|---|---|
| `sessions.read` / `memory.read` / `workspace.read` | 页面 bridge 只读：`sessions.list`、`memory.list/read`、`workspace.summary`；summary 只在对应能力已授权时返回计数 |
| `usage.read` | `usage.summary(range)` 从持久化 transcript 聚合已完成、未被 rewind 的 turn 的 input/output/cached tokens 与 cost；默认 all，也支持 7d/30d |
| `plugin.storage` | 页面对 `storage.get/set/delete/keys`；值必须是字符串（≤64 KiB），每插件最多 200 个 key，随 install 状态持久化；不是 secret store |
| `events.read` | `events.subscribe([...])` + `events.on(...)`；宿主按 allowlist 转发 `session.status`、`turn.completed`、`tool.started/finished`、`turn.failed`、`plan.updated`、`task.updated`、`tool.batch_started`、`agent.wait`、`custom`；只给脱敏字段，不给 text/tool args/tool result |
| `notification.send` | `notify.send(text)` 走已配置的 OCG 通知目标；1–2000 字符、每页面 5 次/分钟；未配置目标返回错误 |
| 示例 | `examples/plugins/workspace-insights/`：workspace/session/memory 摘要、storage 计数器、live event feed |
| 仍未实现 | `sessions.write`、`memory.write`、`schedules.*`、通用 slots/renderers、`provides/requires` 服务注册 |

## 7. Engine 接入与生命周期

### 7.1 两阶段 bootstrap 与插件 adapter

**必须重构 bootstrap 时序，不能等 initialize RPC 才补授权。** 当前 `AppServerEngine.java:50` 在收到 `initialize` 前构造完整 `EngineBootstrap`；后者 `:114` 加载配置，`CliAppHelper.java:137` 合并工作区 agent.properties/MCP，`:117` 初始化运行能力。现在的 final 字段装配必须拆为惰性 metadata/read-only 阶段与受信任执行阶段。

推荐显式启动模式（拟新增，不是现有参数）：

1. **restricted（默认）**：在 AppServerEngine 构造前确定；只加载受信任全局最小配置、只读会话文件和插件 metadata。禁止工作区配置覆盖、MCP/Hook/插件激活、LLM 请求、scheduler 启动及 SessionStart。允许历史/状态/校验，不接受执行型 RPC；未知或未批准工作区先走此模式。
2. **trusted**：宿主在metadata risk review与持久trust commit后请求；引擎自己读取最新trust/config digest校验，不能信任启动参数自报授权。模式在执行bootstrap前验证；新增executable/config或grant mismatch固定回落restricted，并在initialize返回trustState/reason，保留只读历史入口。工作区不存在/协议损坏等基础错误另行明确报错，不冒充trust回落。
3. **升级模式**：P1 不将 final ExecutionBootstrap 原位改写；关闭 restricted 实例并启动新的 trusted 实例，统一 resume。后续从 trusted 配置变更/失去信任时经 §7.3 quiesce 或紧急 gate 重新创建上下文。只读查看不触发执行或恢复旧 pending tool call。

Desktop 先对 pinned binary 执行 inert `--version` / metadata feature inspect，再要求支持 safe bootstrap 的最低版本；**不得先启动旧 app-server 再靠 initialize 返回 engineVersion 发现它已执行代码**。包装脚本的直接启动方式见 §4.4。旧版不支持 restricted 时拒绝用它打开未知工作区。

在执行阶段显式注入 workspace/root/state/grant 服务，在任何 MCP、hooks 或构建 Agent 前执行 ActivationGate。P1 就必须有每次调用的 gate，不能延后到 P2。PluginManager 改为 explicit scope 的实例/服务；消除隐式 CWD、static singleton 与无失效路径 cache，保留每 workspace 独立进程。

P1 首先证明 skills/hooks 的完整链路；现有 MCP 的 workspace/user 来源纳入相同 trust gate。包内 .mcp.json/commands/agents/LSP 必须分别提供 adapter、解析/运行/停用测试与 capability 标志，否则返回 unsupported_component，不向用户声称兼容全套 Claude Code 插件。

调用已有 ToolRegistry 注册/撤销/失效功能，补确定性 priority tie-break、稳定 provider identity、plugin/source/scope 元数据。冲突时报错/降级，不静默取一个工具；禁止以插件替换 builtin 的方式修改可信根。

SkillConfig/HookConfig 与工具定义来自同一个已批准组件快照，避免 manifest 清单与实际加载目录不一致。不把包装为技能的包自动获得 Shell/MCP 授权。

### 7.2 激活状态机

```text
Discovered → Validated → InstalledDisabled
  → AwaitingConsent / Incompatible / MissingDependency / Conflict
  → Ready → Activating → Active
  → PendingChange → Draining → Deactivated
  → Failed / Quarantined
```

状态携带 reason 与 activation revision；单插件失败不阻止核心 bootstrap。授权不足不能落 Active；UI 描述必须跟真实 Engine/Host 状态一致。

### 7.3 普通变更与撤销分别处理

| 操作 | 推荐语义 | 用户反馈 |
|---|---|---|
| 安装新包 | 默认不激活；新实例审阅后在安全点应用 | 已安装 / 待授权 / 待应用 |
| 普通升级/配置变更 | 老 package/工具快照继续当前 turn；新 snapshot 下一 turn 或空闲后生效 | 展示目标版本、pending 状态 |
| 普通停用 | 停止接受新 turn/新入口，当前 turn drain 后卸载 | 明确“当前执行结束后停用”；另提供立即停止 |
| 撤销授权/紧急停止 | epoch 立即失效；取消队列、阻断后续受控调用，必要时 cancel/停受管进程树 | 不延迟到 turn 结束；外部已提交副作用不可回滚 |
| 卸载 | 先停用/引用释放，再删托管 package；保留历史和配置 | 活跃引用未释放时 pending，不直接 rm |

P1 用 `ChangeCoordinator` 泛化现有 provider deferred reload，但 **Desktop 监听到空闲不是无竞态保证**：Java scheduler 可以刚开始领任务。拟新增 `engine/quiesce` 握手，在 Engine 同一 intake/调度锁下先暂停接新 turn与新 schedule claim、让已接受任务 drain，再返回 quiesceToken + active=0。只有有效 token 下才切换/重启；超时仍 busy 则维持 pending，不强杀正常 turn。

Channels 与 GUI 新输入在 quiescing 状态显式排队或返回 retryable pending，不默默丢消息；scheduler claim 与推进 nextRun 的语义保持原契约，不让 quiesce 丢已领任务。apply/abort 后显式恢复 intake；重启失去 token 时新 Engine 以当前 state 重新初始化。此 handshake 与调用 gate都从 P1 实施。

按受影响 workspace/所有 running session（含 channels/schedules）协调；空闲后台 session 重建通过统一 resume 路径处理，不仅恢复当前可见会话。

P2 再演进为 turn-level `PluginSnapshot`：descriptor digest、grant epoch、providers/tools/hooks 定义和 prompt 材料统一绑定；不在一个 turn 内替换定义。运行中撤销另走 ActivationGate 检查，不因快照不可变而保留权限。

### 7.4 缓存、并行写者与重启

- 共享 state revision 与 immutable digest 作为 cache key；global 变更由通知加重查校准，不依赖只有一个 GUI 写者。
- pending change 同时存在多个修改时按 revision 收敛，旧通知不能覆盖新状态；删除/撤销优先于延迟激活。
- 新引擎在构建会话前读取当前有效 grant，不回放磁盘会话里旧的“已授权”字段。
- 保留 `.data` 历史与原输入；停用导致工具不可用时明确展示，不自动恢复旧包或执行旧工具调用。
- Metadata 查询不能触发 SessionStart hook；SessionStart 只在新执行上下文首次激活时按已授权定义运行，普通只读 resume/history 不运行；引擎重建后新上下文激活的 SessionStart 带 stable activationId，必须区分 process rebuild 与新用户会话，不能隐式重复外部副作用。需要幂等副作用的 hook由开发者按 activationId保证，平台不承诺任意脚本 exactly-once。

### 7.5 Hook 执行加固

P1 hooks采用“来源/配置digest + workspace/scope + 事件集合”的显式可信代码授权，不假装每个 PreToolUse/PostToolUse/SessionStop 都能弹窗询问。运行前每次检查当前持久epoch；授权未满足则不启动。SessionStop 只允许运行已经获授的受管清理hook，没有UI或授权不足时fail-closed并记录原因，不在退出时首次申请权限。

MCP启动也必须有独立可信来源/配置授权；通过工具dispatch的MCP操作继续沿用真实工具审批策略。任意hook内部直接shell/网络副作用不能被工具审批截获，授权页明示OS用户权限，不能承诺逐内部操作审批。需要逐发送/删除确认的自动化改用受限broker动作，不包装为任意shell hook。

- pre/post hook 不承担核心权限裁决，不得覆盖审批或伪造授权输出；安装确认不是所有事件的默认放行。
- 同时异步排空有界 stdout/stderr，计时从 process.start 前后开始；超时/取消立即清理受管进程树，可靠回收 readers。
- 不固定所有平台 `sh -c`；优先 argv entry 与明确平台 runtime，legacy shell 命令标为可信高风险模式并披露。
- env 最小化、输出脱敏与上限、避免传 key/token/CDP；同 UID 代码仍可直接读用户文件，不声称 env stripping 构成沙箱。
- 明确 exit/timeout/error 的处理：观察性 hook 失败不吞掉核心终态；需要改变输入/结果的 hook 输出有 schema、大小限制和 provenance。权限不足 fail-closed，不偷偷跳过必须守卫后继续执行。

### 7.6 可选 PluginHost（P3）

任意 Node 扩展不在 Electron Main/主 Renderer 执行。优先一插件实例一 host 或明确隔离组，受管 MessagePort 传输；取消、health、backoff 与 quarantine 都按实例管理。

拟议预算须经测量确认：deactivate 2s、terminate 2s、最终受管进程树清理进入应用总退出预算；不要与当前 RpcClient `10s + 5s` 停机时间叠加后被 app failsafe 提前截断。窗口关闭/隐藏、workspace 回收、app quit 分别定义，不把关闭面板当成停止后台服务。

连续启动失败触发限次退避/隔离，不无限重启；清理失败标出真实状态，不标“完全撤销”。utilityProcess 不提供 OS 沙箱，可信模式与受限模式见 §5.6 / §11-D3。


## 8. 安装、工作区信任与供应链

### 8.1 P1 允许的来源

仅本地目录与 zip 导入，统一 snapshot 到托管目录。dev mode同样先生成临时不可变 snapshot，再独立审阅/激活，不直接加载活目录；文件变化使旧epoch失效，经显式重载重新校验，不能沿用生产 grant无提示热变更。git/市场/npm 是后续分发 adapter，不是一期运行依赖。

P1 禁止任意安装脚本与隐式依赖下载；将旧 npm 安装路径停用/显式不支持。未来若允许必须单独 opt-in，在准备阶段明确 scripts/依赖/网络执行面；`--ignore-scripts` 是必要限制但不是 npm/git 安装安全证明。

### 8.2 安装事务

```text
Untrusted source
  → inert snapshot/staging
  → path/type/size/schema/component validation
  → normalized content digest + compatibility/risk report
  → review exact snapshot (not live source)
  → atomic publish immutable package
  → disabled scoped instance
  → current grant check → explicit activation
```

- zip-slip、绝对路径、设备名/ADS、大小写碰撞、symlink/junction、压缩炸弹、文件数/总大小必须拒绝或限制。
- 复制 source → staging 时逐项 NOFOLLOW/reparse检查，不跟随 symlink/junction；目录源不稳定/文件替换或变形时拒绝并重试，不凭事前一次path检查放行。snapshot完成后校验/哈希最终快照，审阅与实际激活digest一致；content digest标准由共享服务唯一实现。staging按operationId私有化，异常中断后在持control.lock的恢复阶段清理无事务引用、过期残留，不删除活跃准备操作。
- 先准备新版并校验成功，再发布/切换引用；失败保留旧 package/state，不先删旧目录。
- 不允许插件写入托管 store、改 grant、更新自己；宿主只通过事务更改 state。
- 版本与 digest 不一致视为不同内容；同版本换字节重新审阅。更新显示能力/source/content diff；不仅“能力新增”才需要风险检查。
- 回滚回旧 snapshot 仍需当前兼容与有效 grant；被撤销/隔离的 digest 不因回滚恢复授权。
- 包引用/进程未释放前不 GC；GC 和删除私有数据是显式独立操作。

### 8.3 工作区信任门禁

首次从未知 workspace 启动 Engine，按 §7.1 restricted bootstrap 在合并工作区配置和执行构件前获取风险摘要；只读历史不要求信任仓库。

审阅清单必须包括 `.core-ai/agent.properties`（Provider endpoint/key、`mcp.servers.json`、remote agent/执行配置等覆盖）、`.core-ai/hooks.json`、`MCP.json/mcp.json`、`.core-ai/plugins/` 与技能来源/变更。不仅审查插件 manifest。Provider endpoint/TLS/key 出站目的地等核心敏感覆盖须由专门允许列表/用户确认裁决，不能因笼统“信任工作区”把全局 credential 发送到新 endpoint。受信任 workspace 的同 UID 任意代码仍可直接读取文件，此处是正常配置路径保护，不宣传防恶意本机代码。

信任记录在用户侧，绑定 canonical workspace、所选来源、执行配置与 package digest；clone/改路径/新增可执行配置/换命令不能沿用旧“已信任”。已有用户配置也需要迁移展示，而不是为了无提示升级全量 grandfather。

工作区配置只能请求执行能力，不能声明自己已授权。共享 CLI/app-server 非交互模式缺必需授权时返回结构化 trust_required，不在 bootstrap 前静默启动 stdio MCP。控制面 metadata mode 必须不初始化可执行构件。

skills 是可影响模型行为的内容，需来源与变更披露；实际 shell/文件/网络操作仍由工具审批保护。不得把“有 SKILL.md”当成无风险。

### 8.4 签名与企业策略

P1 本地未签名包可在显式信任模式导入，标签必须诚实。checksum 保证所选字节一致，不证明来源合法；签名是 provenance，不证明行为安全。

预留企业策略：允许来源/digest、禁止侧载、必须签名、禁可执行构件/代码插件、能力上限、全局 kill switch。策略由可信根裁决，manifest 与 workspace 文件不能覆盖。市场/远端更新要等待来源认证、pin、digest 与 rollback 已完成；不静默 auto-update。


## 9. API 兼容、开发者接口与观测

### 9.1 版本协商

包 schema、Desktop extension API、Engine component API、app-server transport protocol 是不同维度，不仅检查 `protocolVersion.startsWith("1.")`。

- 加载前同时检验 manifest 支持范围、实际 pinned engine 的 feature/capability version 和 Desktop API；不按仓库 HEAD 假定发布版支持。
- 未知 required feature/能力 fail-closed；不兼容插件禁用并显示原因，核心 UI 继续启动。
- `initialize` 保留旧版兼容字段，新增结构化 extension feature/version；DTO 变更需要协议 fixture 与旧新端兼容测试。
- Scope/capability/version negotiation 在任何 entry 执行前完成；descriptor validate 不执行第三方 module。

### 9.2 开发者接口最小化

P1 文件包不需要 SDK；只提供 manifest schema、validator、pack 与结构化管理命令。P2 的专用 UI bridge 是薄类型封装，不提供 Node/fs/进程 API；P3 是否推出 SDK 取决于信任模式。

v0.5 已在 `sdk/core-ai-plugin/` 提供**作者 SDK**（与 `sdk/core-ai-session` 同级）：manifest TypeScript 类型、`schema/plugin.schema.json`、`definePlugin` 与本地 `validateManifest`。它是编辑期辅助，不是运行时桥；desktop 仍是强制校验方。运行时 UI bridge/PluginHost SDK 仍属于后续阶段。

拟议 `plugin validate|pack|inspect|dev` 命令输出与安装同意页使用同一个规范 descriptor，不维护另一份权限列表。`pack` 不执行用户 build 脚本；需要构建的包由开发者显式预构建，validator 检查最终快照。

建议三个验收样例，P1/P2 随文档与测试一起落地：

1. hello-skill：内容包、无后台进程、不改变核心工具权限。
2. usage-panel：只读绑定会话统计 + Dock，证明不拿历史/secret。
3. tool-result-viewer（P2b）：仅精确匹配的工具结果 + fallback card，卸载后历史可读；不作为 P2a 前置。

这些是拟议 fixtures，不在本次创建，也不承诺已发布 `@core-ai/plugin-sdk`。

### 9.3 错误、限额、背压与退出

建议结构化错误：`trust_required / capability_denied / revoked / incompatible_api / unsupported_component / scope_mismatch / digest_changed / package_conflict / pending_apply / host_failed / quota_exceeded`。错误可关联插件/operationId，日志不包含 secret/raw user data。

- 每实例限制 frame bytes、pending calls、event queue、输出大小和执行时间；control plane 与高频数据事件分离。
- 对 engine stdio 没有换行的超长帧直接终止连接/进程并报协议错，不无限拼接 buffer。
- progress 类允许有序合并/丢弃；审批、error、终态、receipt 不可丢，超预算时断开或隔离实例并由核心校准状态，不让队列无限增长。
- 受管取消/退出要回收 port、listeners、timers、view、文件句柄与进程树；预算在宿主总 quit deadline 内并行协调。
- backoff/quarantine 状态可见，人工 Retry 前再次校验 source/digest/grant。

### 9.4 审计与隐私

记录 install/update/rollback/enable/disable/revoke、scope/digest/授权 revision、能力调用结果和 host failure；输出默认摘要与错误码，不记录 key、完整消息/文件内容。审计有大小/保留期上限，企业导出是独立显式操作。

权限账本与审计帮助正常使用治理，不是可抵御同用户任意 OS 代码篡改的证据系统。插件不能订阅所有 engine:event/onLog 获取其他会话资料。


## 10. 分阶段迁移与验收

### 10.1 完整生命周期计划

P0–P3 是本文拟议实施阶段，不是已完成功能。每阶段先基线/探索、再实现、再验证、最后清理与回归；没有通过退出条件不得开放下一层。

| 阶段 | 实施范围 | 对应验证与退出条件 |
|---|---|---|
| P0：契约与安全基线 | 读取实际 entry/配置链；补基线测试；明确 TCB、scope、trust；停止隐式 npm scripts/先删旧覆盖；可靠 hook timeout；共享无 UI 管理服务草案 | metadata 不启动任何 executable；安装失败旧包仍完整；hook 挂 stdout/stderr 可超时取消；现有会话/内置技能回归通过；冻结 §11 前置决策 |
| P1：Agent 包纳管 | folder/zip事务与legacy adapter；state/grant/control.lock/持久epoch；restricted/trusted bootstrap；skills/hooks与已有MCP gate；首个独立Plugins panel；quiesce+安全点reload | metadata不持EngineLock/不启动代码；CLI/Desktop一致；跨进程撤销与pending激活竞态通过；未批准工作区配置不合入；后台turn不被误杀；构件支持矩阵公开 |
| P2a：只读UI最小闭环 | Main/Renderer按域增量拆分；shared contracts；内置Dock/settings先使用贡献契约；独立WebContentsView bridge/partition；read-only usage-panel、声明式settings、历史fallback | 不改核心分支即可加样例Dock；只获session.stats；跨scope/secret/CDP/宽artifact拒绝；焦点/bounds回归；卸载后历史可读 |
| P2b：按需求扩大贡献 | navigation/composer/tool-result/artifact viewer、history/files/network/model broker、SecretService；每项独立授权/预算/测试 | 每新增能力单独验收、SSRF与scope探针；不把全部6类槽位或模型/网络代理作为P2a前置 |
| P2c：插件页面 + 直连动作面 | 页面贡献与导航、独立 WebContentsView、`coreaiPlugin` bridge、asset/job、**从媒体工具抽取 `MediaGenerationService` + app-server `media/*` RPC**、媒体工作台示例 | 页面不创建会话/不触发 agent；复用既有 `MediaProvider` 而非第二套生成器；素材 handle 隔离；生成凭据不下发；job 跨页面存活；撤销后新动作拒绝 |
| P2d：宿主服务/事件插件 | 稳定 slot/能力 ID、事件 allowlist、插件 storage/job/secret reference、只读 sessions/usage/workspace/memory/models/assets、受控写（memory/notify/schedules）；可信 host 插件后端 | 能支撑 usage card、context dashboard、notification、session insight、memory 面板等热门形态；读默认、写逐项授权；不把宿主内任意 React 当扩展模型 |
| P3：可选后台代码宿主 | 先明确可信 Node 或真受限模式；独立 Host、budget/backpressure/quarantine/退出；开发 SDK/testkit 与来源分发策略 | 不在 main/renderer 执行第三方 module；失败不拖核心；真实模式权限保证经安全探针验证；明示同 UID 代码限制；没有 OS 隔离验收不得宣传沙箱 |

P0 是上线前加固与设计 spike，不要求先把所有功能拆完。P1 不需要第三方 UI/Node Host；P2 可先做一个 read-only Dock，再扩大槽位；P3 可以长期不做。

### 10.2 建议实施批次与改动所有者

| 批次 | 主要改动位置 | 拆分原则 |
|---|---|---|
| B1：共享包服务与安全入口 | `core-ai-cli/.../command/plugins/`、`plugin/`、`hook/`、拟新增 metadata command | 先无 UI service 与 fixtures，CLI 行为对齐后 app-server 接入 |
| B2：工作区gate与Engine adapter | `appserver/EngineBootstrap.java`、`EngineSessionRegistry.java`、`agent/CliAgent.java`、ToolRegistry | 拆restricted/trusted构造路径，配置合并/MCP前trust gate；每调用持久epoch检查；未知required构件拒绝 |
| B3：生命周期与独立Plugins页 | `main/main.ts`、`main/engine/`、shared contracts、拟新增`renderer/panels/plugins/` | 首次就写成独立panel，不先塞进app.ts；quiesce握手补deferred reload竞态；不做框架迁移 |
| B4：UI 模块与贡献 registry | `renderer/app.ts`、`sessionSurfaces.ts`、独立 FeaturePanels | 一功能一批；结构重构与行为新增分开验证 |
| B5：受限 UI bridge 与资源服务 | `preload/`、独立 plugin UI protocol/partition、DockCoordinator | 先恶意 fixture 验证，再开放声明式描述 |
| B6：可选 PluginHost | main plugin host supervision、SDK/testkit | 独立安全模式 ADR；签名/市场不混入首个 Host 实现 |
| B7：插件页面与媒体动作面 | `main/plugins/pageHost`、app-server `media/*` RPC、`MediaGenerationService` 抽取、renderer nav/page slot、`sdk/core-ai-plugin/ui` | 页面先于任意 Node 后端；先做只读素材 + 单模型生成，再扩 job/批量；工具与页面共用生成实现 |
| B8：宿主服务/事件层 | shared capability registry、plugin host backend、event allowlist、plugin storage/job、read-only data adapters | 先只读与声明式 UI；写入和 runtime provider 分层；不把第三方 React 注入主 renderer |

所有批次仅修改本次范围，保留并发工作区变更。版本 bump 与功能/修复一起处理；发布、提交、部署按后续明确授权与仓库流程执行。本次文档不触发这些动作。

### 10.3 测试矩阵与需求追踪

| Test | 覆盖要求 | 场景与判定 |
|---|---|---|
| T01 | R3/R5 | legacy/new manifest 同 descriptor；不支持 required component/API 拒绝；未知 metadata 不执行 |
| T02 | R5/R6 | zip-slip/symlink/junction/ADS/大小写碰撞/zip bomb 被拒；审阅快照与激活 digest 一致 |
| T03 | R5/R7 | CLI与两workspace并发更新/revoke/GC，metadata不争EngineLock；control.lock/epoch线性化无失更，外部revoke后新hook/MCP/broker入口拒绝；pendingActivate不恢复授权 |
| T04 | R6 | metadata/restricted不调用LLM/MCP/hooks/SessionStart；带恶意agent.properties(endpoint/mcp.servers.json)、MCP/hooks/原生plugins目录的工作区只读查看不执行；旧binary启动前版本拒绝；headless fail-closed |
| T05 | R6/R7 | hook 无限 stdout、stderr 填满、永不 EOF、派生进程；可靠超时/取消，无孤立受管进程，说明脱管代码限制 |
| T06 | R4/R7 | 两个后台workspace+channels/scheduler tick与重启同刻竞态；quiesce暂停新领单/输入，已领任务drain，无误杀/丢消息；abort/restart恢复intake与统一resume |
| T07 | R6/R7 | 伪造 pluginId/workspace/session、旧 port/epoch、排队调用撤销、切工作区与关闭 view，全部在执行层拒绝 |
| T08 | R6 | plugin UI 直读 key/core settings/CDP/CUA、访问宽 artifact 协议、file://、loopback/redirect/DNS rebinding；请求被拒 |
| T09 | R4/R8 | nav/dock/settings/composer 内置功能回归；切会话、hidden/native view、焦点/z-order/fullscreen、坏 contribution ID 安全回退 |
| T10 | R8 | 插件未安装/禁用/旧 schema/升级后历史 fallback；不激活插件、不发旧 action；标准 card/form 保持只读 |
| T11 | R7/R8 | queue/无换行超长帧/event flood、Host crash、deactivate 卡死；限额、quarantine、终态不丢、总退出预算内结束 |
| T12 | R5/R6 | 安装中断/磁盘满/损坏 state/rollback revoked digest；旧包与用户副本不被破坏，不恢复旧授权 |
| T13 | R8/R9 | actual packaged pinned Engine 与 Desktop 最低 API 兼容；旧版方法不存在时禁用插件而非核心崩溃 |
| T14 | R4/R9 | Windows/macOS 的实际打包态 smoke：Plugins、hello-skill、read-only usage-panel；新用户/有旧 CLI/离线模式 |

实施时运行对应 Java plugin/hook/registry/app-server 测试、desktop typecheck/node tests、构建与独立用户目录 E2E；精确命令先按实际 build scripts 核实。本次不运行安装/恶意脚本；不要在用户真实 home 中测试 destructive fixtures。

### 10.4 回退、清理与完成定义

- 功能开关按阶段禁用第三方激活/贡献，不要求删除包/历史；回退不得自动绕过已升级 trust gate。
- 新版应用仅接入支持新state/trust协议的pinned engine；不能阻止用户独立运行旧CLI，也不能承诺旧程序遵守grants。回退只能关闭新第三方入口，不能在应用内换回会忽略grants自动执行的旧Engine；既有终端混用限制必须告知。
- 迁移结束才移除旧 hard-coded 路径；确认 core/内置功能无依赖后再清理兼容 adapter，不预先删除用户目录。
- 完成定义：验收矩阵通过、运行模式与限制诚实展示、文档/协议/测试一致、无未验证安全保证、无误杀 live turn；只有实现和验证都完成才标 completed。

## 11. 待 Review / 用户确认的决策

**2026-11-17 用户已授权实施，并要求「按建议或最保守方案落地并记录」。** 下表保留原提案，并记录本轮落地选择；未实现的部分明确写在 §14，不把未落地项当作已批准实现。

| ID | 决策 | 推荐 | 本轮落地 |
|---|---|---|---|
| D1 | 近期插件范围 | 先 P1 Agent 包纳管 + P2 只读/受限 UI；P3 任意代码非前置 | 采纳：实现 P1 包管理 + P2a 声明式 UI；P3 未做 |
| D2 | 共享控制面与存储所有者 | Java 无 UI PluginServices；pinned metadata command + app-server facade；单 state/lock/grant schema | **部分采纳**：state/control.lock/digest 在 desktop main 单点实现；共享 Java 控制命令与 app-server facade 未做，见 §14.2-4 |
| D3 | 可执行构件/代码信任模式 | P1 hooks/MCP 显式可信本机执行；P2 受限 UI；P3 再选可信 Node 或真受限 runtime/OS 沙箱 | **最保守收窄**：hooks/MCP/commands/agents/LSP 标记 unsupported，不执行；仅 skills 注入 |
| D4 | 存量工作区/config/旧CLI兼容 | 不grandfather；restricted先于agent.properties/MCP/hooks/plugin执行；新版pinned入口预查最低版本，不能约束独立旧CLI | **已实现 engine 侧 trust gate**：`WorkspaceTrust` fingerprint + 用户侧 record；restricted 不合并工作区配置/MCP，不加载 workspace skills/legacy plugins/hooks，不启动 scheduler，拒执行 RPC；provider endpoint 独立 allow-list 仍未做 |
| D5 | 默认启用与更新/撤销 | 新包 installed-disabled；普通变更安全点；显式 revoke 立即 gate，可信本机进程清理 best-effort | 默认 disabled、digest 变更重新授权已实现；**per-call gate 已实现**（`PluginGrantGate` + `SkillTool` guard，读 state.json）；hooks/MCP 不在此范围；在途进程清理仍 best-effort |
| D6 | local/global 同 ID 选择 | 明示 source/digest 冲突，由用户选；不默默 local 覆盖 global | 仅 user scope；同 ID 重装按 digest 覆盖并要求重新授权，无 local/global 冲突 UI |
| D7 | P2 UI容器与槽位 | 带脚本UI使用独立WebContentsView；P2a只读Dock/声明式settings，spike验证布局隔离，P2b能力按需 | 采纳声明式 Dock/Settings/Composer；scripted `view` 标记 unsupported，未建 WebContentsView |
| D8 | 来源与企业控制 | P1 folder/zip，禁隐式 scripts/npm；预留企业 source/capability/kill policy，市场后置 | 采纳 folder/zip；无 npm/git/市场；企业策略未做 |
| D9 | Secret 兼容与插件模型调用 | P1 不接受插件 secret；后续宿主 opaque reference/模型槽；现有 auth/provider 迁移独立设计 | 采纳：无插件 secret、无 model.invoke |
| D10 | 拆分与其他 desktop 提案协调 | 共用 SessionStore/backend scope 与 shared contracts，远端直聊不获得本机扩展权限 | 采用 `src/shared/pluginManifest.ts`；未与远端直聊 SessionStore 合并 |
| D11 | 插件页面与直连动作面 | 宿主导航一等项 + 独立 view + `coreaiPlugin` bridge；页面直接调用 Engine 的 `MediaGenerationService`（复用既有工具/Provider），不经 agent；RichCard只作输出/fallback | **设计采纳，未实现**；见 §6.6 / §14.2-4；实现顺序 P2c |
| D12 | 生态能力面优先级 | P2c 页面/媒体 → P2d 宿主服务/事件（只读优先）→ P3 运行时 provider/tools/hooks/browser/terminal；不复制 DSH 宿主内 React 模型 | **设计采纳，未实现**；见 §6.7；需要先冻结 slot/capability/event ID |

## 12. AI Review 记录

### 12.1 前置架构 Review（2026-11-17）

- 模型：`gemini-4-hydrogen`，基于当前 desktop/CLI 源码；是 AI 技术评审，不是用户批准。
- 核心意见：保留 Electron/Engine 分工；统一包服务、可信根/IPC/作用域、贡献点、生命周期与历史降级；供应链与 hooks 缺口先于新增 PluginHost。
- 已采纳澄清：app-server 未初始化插件包但 workspace hooks/MCP 仍生效；每 workspace 独立进程不等于 JVM 串台；复用 ToolRegistry 与 deferred reload；撤销即时 gate；完整多窗口非前置；utilityProcess/SDK 不提供 OS 权限沙箱。
- 这些意见已写入 §1–10；本记录不声称本设计稿已经通过文档级 AI Review。

### 12.2 本设计稿 Review

| 轮次 | 模型 | 状态 | 结论与处置 |
|---|---|---|---|
| 1 | gemini-4-hydrogen | 已执行；发现需补齐的高优先级闭环 | 工作区bootstrap先执行后授权、跨进程epoch/锁/撤销缺口、iframe与独立partition冲突；另提quiesce竞态、旧CLI边界、P2范围、hook授权和snapshot复制风险 |
| 2 | gemini-4-hydrogen | 已复核v0.2；未发现阻塞交付设计稿的高优先级矛盾 | 原bootstrap/撤销/UI隔离问题已解除；提醒Java/Node文件锁边界、残留iframe用词、trust回落与GC最小化，处置见§12.4；实现仍须用户确认与测试 |

### 12.3 第一轮修订处置（v0.2）

| Review问题 | 处置 | 验证/后续 |
|---|---|---|
| Bootstrap时序与工作区配置遗漏 | §7.1 restricted/trusted两阶段，执行前信任，§8.3包含agent.properties/endpoint与MCP/hooks原生目录 | T04；safe binary预查，不等initialize才识别旧版 |
| 跨进程授权/撤销、文件锁与P1 gate缺失 | §4.5持久epoch、单control.lock/journal、受控入口线性化、GUI revoke RPC、外部写者watch+有界重查；P1每调用gate | T03/T07/T12；在途可信进程取消best-effort不夸大 |
| iframe与partition矛盾 | §6.4固定第三方完整UI独立WebContentsView，专用session.protocol/handle；§5.1 core sender+mainFrame检查 | T08/T09；零脚本core iframe不等价 |
| Scheduler/Channel与空闲reload竞态 | §7.3 P1 quiesce握手与intake/claim drain、abort恢复 | T06 |
| 旧CLI无法被逆向门控 | §4.3/§10.4仅新版入口preflight；旧程序独立执行不在安全承诺；bundled更新也纳管 | T13与兼容文案 |
| P1先写大单体、P2范围膨胀 | B3首个独立Plugins panel；P2a只读Dock/settings，P2b按需能力 | T09/T14，避免一次全量搬迁 |
| Hook授权/OS能力表述模糊 | §7.5按事件scope/digest信任与每次gate，不假装SessionStop逐次弹窗；§5.4列明强制边界层 | T05/T07，承认任意脚本内部副作用不受broker限制 |
| Parser分工/TOCTOU/dev/Windows控制面 | Java校验公共/Engine facet，Desktop UI facet；复制NOFOLLOW、私有staging、dev snapshot、直接binary/Java启动 | T01/T02/T12，测试冷启动与中断恢复 |

上述处置是设计建议的修订，未实施产品代码、未运行攻击/安装测试；用户关键决策保持待确认。

### 12.4 第二轮复核处置

- 复核模型仍为 `gemini-4-hydrogen`；结论是可交付为待用户确认的设计稿，不是产品安全验收或用户批准。
- 修正摘要/表格中的iframe旧说法，P2a不把SecretService/高频app broker列为前置；grant mismatch明确回落restricted并保留只读历史。
- 对Java/Node锁互操作提醒予以采纳；**未采用Node无锁快照作为严格授权gate**，因为它会削弱commit后新调用不准入语义。§4.5将P2a统计/配置请求交给已绑定Java Engine纯授权端点，同一FileChannel gate、常驻RPC不逐调用spawn；Main只绑定caller，Java提供参数/resource绑定的admission。已准入窄窗口属于在途且取消best-effort，P2a不开放外部副作用。
- P1 GC采用显式保守检查EngineLock/进程/引用，避免新增心跳服务；P2/P3非Engine实例需要lease时另行实施/测试。
- 这些是文档层面的闭环设计，Java gate跨进程性能与admission race、bootstrap/trust/quiesce、真实WebContents隔离须经§10验收；本次未运行相关产品代码。

## 13. 修改记录与相关文档

### 13.1 修改记录

| 版本 | 日期 | 修改 | 依据 |
|---|---|---|---|
| v0.1 | 2026-11-17 | 将desktop插件架构评审落为设计；保留原始需求、ADR、现状、分层方案、待决策和验收矩阵 | 用户“落到设计文档”；前置AI架构Review |
| v0.2 | 2026-11-17 | 按文档级AI Review补restricted bootstrap、持久epoch/锁/撤销、quiesce、独立WebContentsView、旧CLI限制；收窄P2a/P2b并补测试 | gemini-4-hydrogen第一轮Review；不代表用户批准 |
| v0.3 | 2026-11-17 | 记录用户实施授权；状态改为 Approved / Implementing；§11 记录决策落地；§14 记录实现范围与偏差 | 用户 2026-11-17 授权实施（deepseek-v4-flash） |
| v0.4 | 2026-11-17 | 补 workspace trust gate/restricted bootstrap、per-call skill 撤销、engine quiesce；更新 §11 D4/D5 与 §14 验证/缺口 | 用户要求优先补信任边界；AI 实现与本地验证 |
| v0.5 | 2026-11-17 | 新增 server 插件注册中心（API/存储/权限/migration）、Desktop 一级 Plugins 入口与 registry 安装、`sdk/core-ai-plugin` 作者 SDK；更新 §4/§9/§14 | 用户要求补插件入口、server registry 与 SDK 放置 |
| v0.6 | 2026-11-17 | 新增 §6.6 插件页面与直连动作面：页面贡献、`coreaiPlugin` bridge、asset/job、从既有媒体工具抽取 `MediaGenerationService`、媒体工作台流程；明确 RichCard 不是插件 UI 架构；新增 P2c/B7/D11 | 用户要求设计能支撑生图/生视频工作台，且不走 agent |
| v0.7 | 2026-11-17 | 新增 §6.7 DSH 生态能力地图：六类能力面、代表性插件、预留能力 ID、Tier 0/1/2；新增 R11–R13、P2d/B8/D12 | 用户要求调研 DSH 热门插件并预留非媒体场景扩展性 |
| v0.8 | 2026-11-17 | 冻结预留契约字段（provides/requires/pages/slots/renderers/events）、slot ID 表、事件 envelope、host service 草案与能力风险分类 | 继续把 §6.7 的能力地图落成可实现契约；运行时仍未实现 |
| v0.9 | 2026-11-18 | P2c 首期实现：页面贡献/导航、实例 WebContentsView、page/asset 协议、`coreaiPlugin` bridge、`MediaGenerationApi` + `media/*` RPC、媒体工作台示例；补充 §6.6.6 与 §14 实现状态 | 继续实现插件页面与直连动作面；明确未做的 assets.write/reference/job 持久化 |
| v0.10 | 2026-11-18 | 增加 `media.assets.write`/`assets.import` 和图片参考图解析（`.core-ai/media/imports` 路径）；媒体工作台支持导入参考图 | 继续让页面支持真实素材工作流 |
| v0.11 | 2026-11-18 | P2d 首期：`sessions.read`/`memory.read`/`workspace.read`/`plugin.storage`/`events.read`；只读 bridge、storage、事件 allowlist、workspace-insights 示例 | 先建立通用宿主服务与事件面，再补 usage/写动作 |
| v0.12 | 2026-11-18 | P2d usage：`usage.read` + engine `usage/summary` + transcript 聚合，workspace-insights 显示 tokens/cost | 补齐 usage/cost 场景；写动作与 slots 仍待做 |
| v0.13 | 2026-11-18 | P2d notification：`notification.send` + `notify.send`，接现有 OCG 目标，5/min/页、2000 字符；workspace-insights 加测试按钮 | 打通通知类插件，仍不开放 memory/schedules/sessions 写 |

### 13.2 相关材料

- `docs/cn/design-desktop-builtin-skills.md`：内置技能所有权与安装策略，历史章节不能替代当前代码核查。
- `docs/cn/design-desktop-ocg-channels.md`：OCG 是外部 daemon/channel 插件，不是 desktop PluginHost。
- `docs/cn/design-desktop-openai-compatible-providers.md`：Provider 配置与凭据边界。
- `docs/cn/design-desktop-remote-agent-chat.md`：独立提案；UI backend scope 与模块拆分可协调，不带入本机执行权限。
- `.core-ai/knowledge/project/core-ai-desktop.md`：历史意图与当前插件接入/安全澄清。

### 13.3 用户授权与实施记录

- 2026-11-17：用户明确授权开始实现 Desktop 插件机制，依据本设计 v0.2；本轮使用 `deepseek/deepseek-v4-flash`。
- 授权内容仅覆盖实现与本地验证；未授权发布、部署或修改其他会话范围内的功能。
- AI 评审与实现报告不构成用户对未落地部分的批准。

## 14. 实现状态（v0.5，2026-11-17）

### 14.1 本轮已落地

| 模块 | 实现 | 文件 |
|---|---|---|
| Manifest / descriptor | `.claude-plugin/plugin.json` + `coreai` 扩展；legacy 派生 ID；schema/capability/component/contribution 校验；API 版本范围；包内路径安全检查 | `core-ai-desktop/src/shared/pluginManifest.ts` |
| 包存储 | content-addressed `~/.core-ai/plugin-store/packages/<digest>`、私有 staging、nofollow 复制、文件数/大小/设备名/zip-slip 限制、原子发布、旧包保留 | `core-ai-desktop/src/main/plugins/pluginStore.ts`、`zip.ts` |
| 状态与授权 | 单 `state.json` + `control.lock` + revision/grantEpoch；installed-disabled；enable/disable/grant/revoke/remove；digest 变化清空授权 | `pluginStore.ts` |
| Workspace trust | engine 启动时计算 workspace 配置指纹（agent.properties/hooks/MCP/plugins/skills）；用户侧 `workspace-trust.json` 匹配才 trusted。restricted：不合并工作区配置与 MCP，不加载 workspace/legacy-plugin skills、hooks、memory，不启动 scheduler，`session/send|compact|undo`、`schedules/trigger` 返回 `TRUST_REQUIRED`；只读历史仍可用 | `core-ai-cli/.../workspace/WorkspaceTrust.java`、`EngineBootstrap`、`EngineSessionRegistry`、`AppServerEngine`；`core-ai-desktop/src/main/plugins/workspaceTrust.ts` |
| Per-call revocation | engine 每次 `use_skill` 读取 `state.json`，校验 scope/enabled/digest/granted required/epoch；撤销、禁用、digest 变化、epoch 回滚在下一次 skill 调用即拒绝，不依赖重启 | `core-ai-cli/.../plugin/PluginGrantGate.java`、`core-ai/.../SkillTool.java` |
| Quiesce | `engine/quiesce` 暂停 intake + scheduler 领单并等待已接受 turn drain；超时恢复；`engine/resumeIntake`；desktop reload 先 quiesce，busy 则保持 pending | `core-ai-cli/.../appserver/EngineQuiesce.java`、`AppServerEngine`、`EngineScheduler`；`EngineSupervisor.ts`、`main.ts` |
| Engine 接入 | 仅把 active + `engine.skills` granted 的 skills 目录通过 `CORE_AI_PLUGIN_SKILL_ROOTS` 注入；Java 再校验路径必须位于托管 store 内 | `PluginHost.ts`、`PluginSkillRoots.java`、`CliAgent.skillConfig` |
| IPC | 独立 `plugins:*` / `workspace:*` handler，验证 main window 顶层 frame，返回 typed envelope；不向插件暴露 engine 全量 bridge | `main.ts`、`preload.ts` |
| UI 扩展点 | 一级导航 **Plugins**（本地包 + Server registry + trust 入口）、Settings → Plugins 摘要与跳转、受限 workspace banner；宿主声明式 Dock tab、Settings 字段、Composer 插入动作；插件不可注入脚本/DOM | `renderer/pluginView.ts`、`app.ts` |
| Server registry | `GET/POST/DELETE /api/plugins`；manifest 派生 id/version/能力/构件；sha256/size/URL 校验；`plugin.view` 默认 user、`plugin.manage` admin；Mongo `plugins` + PG `app.plugins`（V43） | `core-ai-api/.../PluginRegistryWebService.java`、`core-ai-server/.../plugin/*`、`V43__plugins.sql`、`PermissionCodes` |
| Registry client | 主进程用 active auth 调 registry；下载只对同源发 Authorization，边下边算 sha256，校验 size/digest 后才 `installZip`；临时文件清理 | `core-ai-desktop/src/main/plugins/registryClient.ts`、`main.ts`、`preload.ts` |
| Author SDK | `sdk/core-ai-plugin`（与 `sdk/core-ai-session` 同级）：TS 类型、JSON Schema、`definePlugin`、本地 `validateManifest`；非运行时桥 | `sdk/core-ai-plugin/` |
| 插件页面（P2c 首期） | `contributes.pages`、`ui.pages`、左侧页面导航、实例 `WebContentsView`、`coreai-plugin-page://`/`coreai-plugin-asset://`、固定 preload bridge、每次调用重新校验 grant、插件变更关闭页面 | `pluginManifest.ts`、`PluginPageHost.ts`、`PluginPageProtocol.ts`、`pluginPagePaths.ts`、`pluginPagePreload.ts`、`app.ts`、`styles.css` |
| 非 agent 媒体面 | app-server `media/models`、`media/generate`、`media/jobs/get|list`；`MediaGenerationApi` 复用既有 `MediaProvider`，图片写 `.core-ai/media/images`，视频写 `.core-ai/media/videos`；restricted workspace 拒绝生成 | `AppServerEngine.java`、`MediaGenerationApi.java` |
| 素材导入/参考图 | `assets.import`（capability `media.assets.write`）写 `.core-ai/media/imports`；`inputImages`/`inputReferences` 只解析 `.core-ai/media/` 下的路径为 data URL，支持图片参考 | `PluginPageHost.ts`、`pluginPagePreload.ts`、`MediaGenerationApi.java` |
| P2d 只读宿主服务 | `sessions.read`/`memory.read`/`workspace.read`/`usage.read` 页面 bridge：`sessions.list`、`memory.list/read`、`workspace.summary`（按能力返回计数）、`usage.summary`（transcript 聚合） | `PluginPageHost.ts`、`pluginPagePreload.ts`、`pluginManifest.ts`、`UsageApi.java`、`TranscriptReader.java` |
| P2d storage/events | `plugin.storage` 命名空间字符串存储（≤64 KiB/值、200 key）；`events.read` 允许列表事件转发，只给脱敏字段 | `pluginStore.ts`、`PluginHost.ts`、`PluginPageHost.ts`、`main.ts`、`pluginPagePreload.ts` |
| P2d notification | `notification.send` + `notify.send`；接现有 OCG 目标，5 次/分钟/页、2000 字符上限；无目标明确报错 | `PluginPageHost.ts`、`main.ts`、`pluginPagePreload.ts`、`pluginManifest.ts` |
| 媒体工作台示例 | `examples/plugins/media-studio/`：页面列素材、导入参考图、模型选择、图片/视频生成、视频 job 轮询 | `examples/plugins/media-studio/` |
| 只读洞察示例 | `examples/plugins/workspace-insights/`：workspace/session/memory 摘要、storage 计数器、live event feed | `examples/plugins/workspace-insights/` |
| 生命周期 | 变更后安全点 reload + quiesce；busy 保持 pending，turn 结束重试 | `main.ts`、`EngineSupervisor` |
| 示例 | `hello-plugin`：skill + dock + settings + composer | `core-ai-desktop/examples/plugins/hello-plugin/` |
| 测试 | 插件/信任 node:test（含 workspace trust store、env shape）+ Java `PluginSkillRootsTest` (6)、`PluginGrantGateTest` (5)、`WorkspaceTrustTest` (5)、`CliAgentSkillConfigTest` (2)、`SkillToolGuardTest` (1)、`MediaGenerationApiTest` (5) + Electron page smoke + engine trust E2E | `test/plugin*.test.mjs`、`test/workspaceTrust.test.mjs`、`test/pluginPageHost.smoke.cjs`、`core-ai-cli/src/test/.../plugin|workspace|agent|appserver`、`SkillToolGuardTest.java`、`.core-ai/tasks/desktop-plugin-trust-e2e.mjs` |

验证记录（2026-11-17）：`npm --prefix core-ai-desktop run check`、`build`、`test`（246 pass / 1 skip）；`:core-ai:test --tests SkillToolGuardTest`、`:core-ai-cli:test`（plugin/workspace/agent 子集）与 `checkstyleMain/checkstyleTest` 通过；`:core-ai-server:test --tests PluginRegistryServiceTest`（4 例）通过；SDK `node --test sdk/core-ai-plugin/test/manifest.test.mjs`（6 例）通过；Electron smoke：Plugins 一级入口存在、本地 `Hello Plugin` active、Dock `Hello`、composer action、未登录 registry 显示 sign-in 提示；engine trust E2E：restricted 不启动 workspace MCP、`session/send` 返回 `TRUST_REQUIRED`、写入匹配 record 并重启后 trusted 且 MCP 启动、quiesce/resumeIntake 成功。**全局 server checkstyle 当前被另一会话未提交的 `SystemSettingsService` 改动拖红；本特性新增/修改文件未出现在 checkstyle 报告中。**

验证记录（2026-11-18，P2d + usage/notification）：desktop `check`、`build`、`test`（261 tests：260 pass / 1 skip）；`pluginPageHost.smoke.cjs` 通过（context、assets、media、sessions、memory、summary、usage、storage、脱敏事件、notify + 限流）；SDK 7 例通过；`UsageApiTest` 2 例、`:core-ai-cli:checkstyleMain/checkstyleTest` 通过。**PMD 仍有其他会话的既有告警（`ModelSettingsApi`、`LlmProviderStore`、`PluginGrantGate`），本特性新增文件只修掉了自身告警。**

### 14.2 明确未落地的设计项（不得当作已实现）

1. **共享 Java 无 UI 控制面 / app-server `plugins/*` RPC 未实现。** 桌面负责包安装与授权写入；engine 只读 `state.json`。外部 CLI 写入尚不存在，跨进程撤销通知/journal 恢复未落地（engine 通过每调用读 state 仍能感知外部写入，但没有 CLI 写路径）。
2. **Workspace scope 与 provider endpoint allow-list 未实现。** 只有 user scope；信任记录是整个 workspace 的指纹，不区分“哪些 provider endpoint 可覆盖”的单独确认。
3. **Quiesce 的 UI/channel 排队未实现。** engine 在 quiesce 期间对 `session/send`/`session/create` 返回 `ENGINE_QUIESCING`；desktop/channel 只在 turn 结束后重试 reload，没有把用户新输入排队并自动重发。scheduler 已暂停，已领取任务正常 drain。
4. **scripted UI 只实现了 P2c 首期。** `contributes.pages`、页面导航、实例 WebContentsView、`coreaiPlugin` bridge、`media/models|generate|jobs/get|list`、`assets.import`、图片参考图已落地；`media.assets.delete`/rename、`jobs.cancel/subscribe`、跨 Engine 重启 job、mask、reference video/audio、app/workspace scope 行为差异仍未实现。`contributes.dock[].view` 仍标为 unsupported；RichCard 仍只属于 agent/skill 输出与历史 fallback。
5. **hooks/MCP/commands/agents/LSP adapter 未实现。** 包内这些构件在 descriptor 中标为 unsupported，并拒绝激活相应能力；CLI 既有插件目录行为未迁移到新 store。
6. **签名/来源认证、企业策略、审计、GC、marketplace UI、git/npm 发布、旧 CLI grandfather 策略均未实现。** Server registry 已能发现并固定 digest，但 `download_url` 由发布者提供，registry 不托管/不扫描包，也没有镜像或签名；publish 目前只有管理 API，没有前端发布页。
7. **可信代码权限边界仍是合作协议。** per-call gate 保护 engine 正常 skill 读取路径；同 UID 任意代码、已加载 skill 的文本内容、hook/MCP 内部副作用不受 broker 约束，风险披露与真沙箱仍是后续工作。
8. **SDK 只是作者辅助。** `sdk/core-ai-plugin` 不包含运行时 UI bridge、PluginHost 协议或发布工具；desktop 的强制校验仍是唯一权威。
9. **§6.7 的非媒体能力面只实现了 P2d 首期。** 已有只读 sessions/memory/workspace/usage、`plugin.storage`、events allowlist 和 `notification.send`；仍没有写动作（memory/schedules/sessions）、通用 `ui.slots`/`ui.renderers`、`provides/requires` 服务注册或 host 插件后端。

### 14.3 下一步建议顺序

1. 补共享 state/控制面（CLI `plugin` 命令与 app-server facade）和 quiesce 期间的 desktop/channel 输入排队。
2. 补 P2c 剩余：`media.assets.delete`/rename、`jobs.cancel/subscribe`、跨 Engine 重启 job、mask、reference video/audio，以及把 `MediaGenerationApi` 与 `GenerateImageTool`/`GenerateVideoTool` 的公共编排真正抽成一层（目前共享 `MediaProvider`，但 reference/sink 编排仍在工具侧）。
3. 补 P2d 剩余：受控写（memory/schedules/sessions）、通用 slots/renderers、`provides/requires` 服务注册；最后再接 provider/tools/hooks/browser/terminal（P3）与 workspace scope、provider endpoint/TLS 确认、registry 签名/镜像/企业允许清单。


