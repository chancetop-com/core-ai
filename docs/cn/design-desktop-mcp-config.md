# Desktop MCP 配置能力 — Tech Design

| 项目 | 内容 |
|---|---|
| 状态 | Implemented（开发环境实现并 E2E 验证；未打包发布） |
| 文档版本 | v0.7（v0.6 设计 + §14 实现记录） |
| 日期 | 2026-11-26 |
| 作者 | core-ai |
| 需求提出者 / 决策人 | 用户 |
| 范围 | core-ai-desktop 中按 workspace 隔离的 MCP 配置、密钥、生命周期、权限、UX、IPC、测试与分期 |
| 事实来源 | 2026-11-26 对 `core-ai-desktop`、`core-ai-cli`、`core-ai`、`core-ai-server` 当前工作区的源码核查；行号会随代码变化漂移 |
| 交付边界 | 仅技术设计；不修改代码、不修改数据库、不部署、不发布 |

## 0. 原始需求、编号与文档治理

### 0.1 用户原始需求（原文）

> 为 core-ai-desktop 设计「MCP 配置」能力。只出技术设计，不写实现代码、不改数据库、不部署。
>
> 目标：用户能在 desktop 里添加/编辑/启停 MCP server，不再手改 `.core-ai/MCP.json`；设计与现有 CLI / server hub / workspace trust 机制兼容。
>
> 先探索（必须核对源码，不要凭印象）：
>
> - `core-ai-desktop` 现有的 workspace trust、`.core-ai/MCP.json` 加载、agent.properties、插件/Hub 能力桥接、Settings 页面与 IPC。
> - CLI 的 MCP 配置来源与优先级（`~/.core-ai`、workspace `.core-ai`、`mcp` 子命令、`core-ai-session` SDK）。
> - server 侧 MCP hub / session MCP readiness（如有），以及 agent 配置里的 MCP 引用。
>
> 设计文档写到 `docs/cn/design-desktop-mcp-config.md`，至少覆盖：
>
> 1. 需求与范围：v1 做什么、不做什么；stdio / SSE / HTTP 哪些支持。
> 2. 配置模型与优先级：全局用户级 vs workspace 级 vs agent 级；与现有 `MCP.json` 的迁移/兼容；secret 如何存（Electron safeStorage / OS keychain），不落明文。
> 3. 数据模型与持久化：配置文件 schema、版本、校验、导入/导出（Claude/Cursor 格式可选）。
> 4. 生命周期：何时启动/停止 MCP 进程、按 workspace/session 隔离还是共享、健康检查、重启、崩溃隔离、工具发现缓存。
> 5. 安全与权限：workspace trust、命令/参数确认、环境变量与 secret 注入、工具级审批、只读/危险工具标记、防止恶意 MCP server 读取本地文件或外发。
> 6. UX：Settings 列表、添加/编辑表单、Test connection、工具列表、状态/日志、启停、错误提示；新建会话时如何选择/继承。
> 7. IPC/API 设计：renderer ↔ main 的接口、主进程与 engine 的边界；多窗口/多 workspace。
> 8. 失败与可观测性：启动失败、握手超时、工具冲突、日志位置、用户可见错误。
> 9. 测试与验收：单元/集成/E2E、迁移测试、恶意配置测试。
> 10. 分期与开放决策：P0/P1/P2，明确需要用户拍板的点。
>
> 约束：只出设计；不要修改代码；不要声称用户批准；中文文档，保留用户需求原文与决策台账。完成后汇报文档路径、关键决策、待确认项。

### 0.2 用户补充要求（原文，2026-11-26）

> 重点是mcp不能影响cli或者desktop的状态 另外 我希望是不同的workspace有不同的mcp配置

这两条是本版设计的最高约束，优先于前一版中“与 CLI 共享用户级配置文件”的设想。

### 0.3 需求分解

| ID | 要求 | 来源 |
|---|---|---|
| R1 | Desktop 内完成 MCP server 的添加、编辑、启停、删除 | 用户原文 |
| R2 | **MCP 配置能力不得改变 CLI 的配置、状态和行为**；不得写 CLI 读取的文件，不得让 CLI 因 Desktop 的 MCP 配置而加载/停止服务器 | 用户补充要求 |
| R3 | **MCP 配置能力不得改变 Desktop 的全局配置/状态**；编辑 MCP 不改变 workspace trust 指纹、不重启其他 workspace、不污染 `settings.json` 的其他域 | 用户补充要求 |
| R4 | **不同 workspace 有各自的 MCP 配置**；A 的 server 不出现在 B，A 的进程/secret 不与 B 共享 | 用户补充要求 |
| R5 | 与 workspace trust 兼容：受限 workspace 不执行 MCP；但编辑 MCP 本身不触发重新信任 | 用户原文 + R3 推导 |
| R6 | 与 CLI / server hub 边界清晰：CLI 文件只作为导入来源，server hub 只读/调用，不混为同一配置面 | 用户原文 + 架构推导 |
| R7 | Secret 用 Electron safeStorage / OS keychain，配置文件不落明文 | 用户原文 |
| R8 | 明确 MCP 进程启动/停止、workspace/session 隔离、健康检查、重启、崩溃隔离、工具发现缓存 | 用户原文 |
| R9 | 工具级审批、危险工具标记、恶意 MCP server 的文件读取/外发风险边界 | 用户原文 + 安全推导 |
| R10 | Settings 列表、表单、Test connection、工具列表、状态/日志、启停、错误提示、新会话继承 | 用户原文 |
| R11 | Renderer ↔ Main IPC、Main ↔ Engine 边界；多窗口/多 workspace | 用户原文 |
| R12 | 启动失败、握手超时、工具冲突、日志位置、用户可见错误 | 用户原文 |
| R13 | 单元/集成/E2E、迁移测试、恶意配置测试、验收标准 | 用户原文 |
| R14 | P0/P1/P2 分期，明确需要用户拍板的点 | 用户原文 |
| R15 | 本轮只出设计，不改代码、不改数据库、不部署 | 用户原文 |

R2–R6 是本设计从用户补充要求推导出的硬约束；任何与“共享配置更方便”冲突的方案都应让位于隔离。

### 0.4 文档治理与事实口径

- 源码是现状的唯一事实来源。本文把“已有事实”“已发现缺口”“拟新增设计”分开标注；新增路径、方法名、字段、RPC 均为提案，除非明确写“已有”。
- 代码引用使用仓库相对路径与行号，行号基于 2026-11-26 核查；实现前需重新核对。
- AI Review 只作为技术建议，不构成用户批准；决策台账和 Review 记录分开维护。
- 本轮不修改任何代码或数据库；文中的 JSON、接口、伪代码是设计契约，不是已实现能力。
- UI 文案沿用 desktop 现有英文界面；本文用中文解释语义。

### 0.5 决策台账（摘要）

| ID | 决策 | 提议 | 状态 | 影响 |
|---|---|---|---|---|
| D1 | Desktop MCP 配置归属 | **Desktop 自有、按 workspace 隔离的 store**；不新增 `~/.core-ai/MCP.json`，不写 workspace `.core-ai/MCP.json` | 提议，待确认 | 与 CLI 配置彻底解耦；同一 server 若两端都要用，需要各自配置或导入 |
| D2 | CLI 文件的关系 | CLI 的 `.core-ai/MCP.json` / `agent.properties` 只作为**显式导入来源**；Desktop 运行时不读取、不写入、不监视它们 | 提议，待确认 | CLI 行为完全不变；Desktop 不会“自动跟随”CLI 配置 |
| D3 | Engine 配置注入 | Desktop 模式（`app-server --desktop-mcp` 显式选项）忽略文件型 MCP 源，改由 `mcp/config` + `mcp/secrets` RPC 注入；CLI 模式完全走旧路径 | 提议，待确认 | 用命令行选项而不是环境变量，避免被 Bash/Hook 子进程继承；旧 engine 不传该选项 |
| D4 | Workspace trust | 受限 workspace 不执行 MCP；Desktop MCP 配置在 workspace 外，**编辑不改变 trust 指纹**，不需要重新 Trust | 提议，待确认 | 保持安全门禁，同时避免 MCP 编辑扰动 Desktop 状态 |
| D5 | Secret 模型 | 按 workspace、按 server 分组的 safeStorage 密文；通过 `mcp/secrets` 送入 engine 内存；**不注入 engine `process.env`** | 提议，待确认 | 同 workspace 内不同 server 不共享未显式下发的 secret；不同 workspace 完全隔离 |
| D6 | 作用域 | **只有 per-workspace 一层**；没有用户级/全局 MCP 列表；agent/session 级是 P1 过滤器，不是第二套定义 | 提议，待确认 | 切 workspace 即切配置；复制/模板是显式操作，不是隐式继承 |
| D7 | 生效方式 | P0 只安全点重启**当前 workspace** 的 engine；不 `shutdownAll`，不重启其他 workspace | 提议，待确认 | 编辑 MCP 不会打断其他 workspace 的 turn |
| D8 | 工具名冲突 | 禁止 MCP 覆盖**任何非 MCP 工具**；跨 MCP server 按名称排序确定性获胜；`>64` 名称拒绝 | 提议，需修复核心行为 | 现有 MCP priority=3 会覆盖 builtin；这是安全前置项 |
| D9 | Agent/session 级 | P0 不做；所有会话继承当前 workspace 的启用集合 | 提议 | P1 只做过滤器（disabled/allowlist），不做第二套 server 定义 |
| D10 | Plugin `.mcp.json` | 不启用；Desktop 继续把插件 MCP 组件标为 unsupported | 提议 | 避免绕过 trust/审批的第三条执行路径 |
| D11 | `add_mcp_server` | Desktop（`--desktop-mcp`）模式下不注册该工具；其他 app-server/CLI 模式保留 | 提议，待确认 | 防止模型/子 agent 绕过 Desktop store 启动任意 MCP |
| D12 | SSE | 支持，标为 legacy；stdio 与 Streamable HTTP 为主 | 提议 | 与 engine 现有三传输一致 |
| D13 | CLI 行为 | P0 不改变 CLI 的配置加载、合并、默认值和命令；仅有的共享代码改动是安全修复（D8）与 Desktop-only 分支 | 提议，待确认 | 若要求“零 CLI 行为变化”，D8 也可只作用于 Desktop 模式；但不推荐留下 CLI 漏洞。U5 决定边界 |

## 1. 结论与范围

### 1.1 推荐结论

**Desktop 为每个 workspace 维护一份自己的 MCP 配置；Engine 仍是唯一 MCP 宿主；CLI 完全走自己的旧路径。**

三条隔离线：

1. **文件隔离**：Desktop 只读写 Electron `app.getPath('userData')/mcp/**` 下自己的 store（例如 `%APPDATA%\core-ai-desktop\mcp`）；不写 `~/.core-ai/agent.properties`、`~/.core-ai/MCP.json`、workspace `.core-ai/MCP.json`、`mcp-state.json`，也不创建这些文件。
2. **运行隔离**：每个 workspace 一个 engine、一份 MCP 配置、一组 MCP 进程和 secret；A 的配置/进程/secret 不出现在 B。CLI 进程不读取 Desktop store。
3. **状态隔离**：编辑 MCP 不改变 workspace trust 指纹，不重启其他 workspace，不修改 `settings.json` 的其他域；只对当前 workspace 的 engine 做安全点重启。

### 1.2 v1 做什么

| 能力 | P0 范围 |
|---|---|
| 作用域 | 仅当前 workspace；每个 workspace 一份独立配置；无用户级/全局列表 |
| 传输 | stdio（`command`/`args`/`env`）、Streamable HTTP（`url`/`endpoint`/`headers`）、legacy SSE（`transport: "sse"`） |
| CRUD | 添加、编辑、删除、启用、禁用、复制为 JSON、导入、导出 |
| 密钥 | `${env:NAME}` 占位；safeStorage 按 workspace + server 加密；缺失时 fail closed；Renderer 不可回读明文 |
| 验证 | 保存前字段校验；保存后异步 Test connection；工具列表；连接状态；日志尾部 |
| 生效 | 只对当前 workspace 的 engine 做安全点重启；其他 workspace 不受影响 |
| 信任 | 受限 workspace 不执行 MCP；编辑 MCP 不改变 trust 指纹，不触发重新 Trust |
| 冲突 | 禁止覆盖任何非 MCP 工具；跨 server 同名工具确定性处理并告警；Desktop 模式禁用 `add_mcp_server` |
| 兼容 | 可显式导入 workspace `.core-ai/MCP.json` / legacy `agent.properties`；导入是复制，不是实时共享 |
| 观测 | 结构化错误码；日志位置；启动/握手/工具冲突的用户可见说明 |

### 1.3 v1 不做什么

- 不做 Desktop 与 CLI 的 MCP 配置实时同步/共享；不新增共享的 `~/.core-ai/MCP.json`。
- 不写 workspace `.core-ai/MCP.json`；不改 `agent.properties`；不改 CLI 的 MCP 合并顺序。
- 不做用户级/全局 MCP 列表；不同 workspace 的配置默认不互通。
- 不做 MCP server 的 OS 沙箱、文件/网络 egress 隔离；P0 只能做“用户确认 + 审批 + 不落明文 + restricted 抑制”，不能承诺恶意 server 读不到本地文件或不能外发。
- 不做 server hub 的管理/注册；Desktop 只消费 hub 的只读/调用接口。
- 不启用 plugin 的 `.mcp.json` 组件；不把插件 MCP 作为第四条配置路径。
- 不做远程会话的本地 MCP 注入；远程会话使用 server agent 的工具配置。
- 不做多窗口产品化；但 IPC 从第一天按 workspace 绑定，避免以后返工。
- 不做 MCP 工具名统一前缀（P0 采用“禁止覆盖任何非 MCP 工具 + 跨 server 确定性”）；前缀化会改变模型可见工具名，留到 P1 评估。
- 不做明文 secret 回退；safeStorage 不可用时拒绝保存 secret 字段，而不是降级写文件。

### 1.4 传输支持矩阵

| 传输 | P0 | 依据 / 约束 |
|---|---|---|
| stdio | 支持 | `McpServerConfig.fromMap` 有 `command` 即走 stdio；Windows 下 `npx`/`npm`/`node`/`pnpm`/`yarn`/`bun`/`.cmd`/`.bat` 由 `McpTransportFactory` 包装为 `cmd.exe /c`（`core-ai/src/main/java/ai/core/mcp/client/McpTransportFactory.java:55-83`） |
| Streamable HTTP | 支持 | `url` 无 `transport` 或 `transport` 非 `sse` 时走 `STREAMABLE_HTTP`；URL 以 `/mcp` 结尾且未给 `endpoint` 时自动拆分（`McpTransportFactory.java:85-119`） |
| SSE | 支持，标 legacy | `transport: "sse"`；SDK 仍支持，但 MCP 生态已弃用 SSE，UI 标 legacy（`McpServerConfig.java:87-90`、`McpTransportFactory.java:121-153`） |
| `sandbox_hosted` | 不支持（本地） | 这是 server 侧把 stdio 放进 session sandbox 的传输；本地 CLI 无意义（`docs/skills/core-ai-cli-manual/references/mcp.md:64`） |
| ACP 客户端下发 MCP | 不支持 | `AcpAgentRunner` 声明空 `McpCapabilities` 且不读 `NewSessionRequest.mcpServers`；不属于 desktop 范围 |

### 1.5 与现有三类 MCP 面的边界

| 面 | 执行位置 | 谁管理 | Desktop 本设计的关系 |
|---|---|---|---|
| Desktop MCP（本设计） | 本机各 workspace 的 engine | Desktop，按 workspace | 本设计主体；存储与 CLI 完全分离 |
| CLI 本地 MCP（`MCP.json` / `mcp.servers.json`） | 本机 CLI/engine | 用户/workspace 文件 | **不读 workspace `MCP.json`、不写入**；`agent.properties` 作为常规配置读取但丢弃 `mcp.servers.json` 键；只提供一次性导入 |
| Server MCP Hub（`/api/mcp-hub/*`、`/api/tools/mcp-servers`） | core-ai-server | 管理员/`mcp.manage` | 只读/调用；Desktop 不写 server |
| Plugin `.mcp.json` | 取决于插件运行时 | CLI plugin manager（仅探测，未加载） | 不启用；Desktop 继续标 unsupported |

`core-ai-cli mcp ...` 是 **hub 数据面**，不是本地 MCP 管理命令；它看不到本地 `MCP.json` 的服务器（`docs/skills/core-ai-cli-manual/references/hub.md:126-128`）。Desktop 的 MCP 设置页必须明确写“These servers are stored by core-ai-desktop for this workspace. CLI uses `.core-ai/MCP.json` separately.”。

### 1.6 隔离保证（本设计的验收底线）

| 保证 | 含义 | 验证方式 |
|---|---|---|
| 不写 CLI 文件 | Desktop 不创建/修改/删除 `~/.core-ai/agent.properties`、`~/.core-ai/MCP.json`、`<ws>/.core-ai/MCP.json`、`<ws>/.core-ai/mcp.json`、`~/.core-ai/mcp-state.json` | 文件 mtime/hash 前后对比；E2E 监控 |
| CLI 不读 Desktop store | CLI 进程不访问 `userData/mcp/**`（Desktop 应用数据目录，不在 `~/.core-ai`）；CLI 的 MCP 启动只由旧配置决定 | CLI 启动日志/文件访问断言；CLI 代码不引用该路径 |
| 只读导入 CLI 文件 | Desktop 只在用户点击 Import 时读取 `.core-ai/MCP.json` 一次；`agent.properties` 作为常规配置读取但丢弃 `mcp.servers.json`；不监视、不自动加载、不写回 | 导入前后 CLI 文件 hash 不变；无文件 watcher |
| 不改变 trust 指纹 | 编辑 Desktop MCP 不改变 workspace 的 `.core-ai` 文件，因此 `WorkspaceTrust` 指纹不变 | 保存前后 `workspace/trust` 指纹一致 |
| 不重启其他 workspace | 保存/启停只触发当前 workspace 的 `quiesce + restart`；不调用 `shutdownAll` | 其他 workspace engine pid/启动时间不变 |
| 不污染 `settings.json` | MCP 数据不进 `settings.json`；只存独立的 MCP store | `settings.json` diff 为空 |
| 跨 workspace 隔离 | A 的 server/secret/进程不出现在 B；B 的 engine 不加载 A 的配置 | 两 workspace 分别 status/tools/进程检查 |
| 不改变 CLI 行为 | 无 `--desktop-mcp` 时 engine 走旧路径；CLI 命令/配置语义不变 | CLI 回归测试 + 同一配置前后对比 |

**唯一例外**：U5 若选择把 D8（禁止 MCP 覆盖非 MCP 工具、`>64` 拒绝）全局应用，CLI 的异常工具冲突行为会改变；这是安全修复，不是配置/状态改动，需用户明确确认。若 U5 选择 Desktop-only，CLI 完全不变。

## 2. 现状核查（源码事实）

### 2.1 Desktop 现状

| 事实 | 源码依据 | 对本设计的意义 |
|---|---|---|
| 每 workspace 一个 app-server engine，懒启动、initialize 握手、退出检测 | `core-ai-desktop/src/main/engine/EngineSupervisor.ts:1-3`、`:71-137` | MCP 进程随 workspace engine 生命周期；天然可按 workspace 隔离 |
| Engine 只传 `app-server --workspace`，没有 MCP 专用 config/env | `EngineSupervisor.ts:120-137`；`core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerCommand.java:17-34` | 需要 Desktop-only 模式开关与 `mcp/config`/`mcp/secrets` 注入 |
| Desktop 已有安全点 reload：quiesce → restart；busy 时延迟到 turn 结束 | `core-ai-desktop/src/main/main.ts:454-514` | MCP 变更复用该机制，只对当前 workspace |
| Settings 是单页大面板，已有 `collapsibleSettingsSection`、`settings-grid`、`settings-actions` | `core-ai-desktop/src/renderer/app.ts:7579-7604`、`:7651-7795` | MCP 设置复用组件，不引入新前端框架 |
| 当前 Settings 没有 MCP 区块；`MCP.json` 只在 trust 文案里出现 | `app.ts:7651-7795`、`:2872-2897`、`:7798-7836` | 需要新增列表/表单/状态 UI |
| Renderer 通过 `window.coreai.*` 调 Main；没有 Node、没有 ipcRenderer | `core-ai-desktop/src/preload/preload.ts:4-194`；`main.ts:954-1671` | 新 MCP API 必须加在 preload 白名单和 Main handler，并做 main-frame guard |
| Workspace trust 由 engine 计算指纹，Desktop 只存用户侧记录 | `core-ai-cli/src/main/java/ai/core/cli/workspace/WorkspaceTrust.java:18-28`、`:50-76`；`core-ai-desktop/src/main/plugins/workspaceTrust.ts:33-101`；`main.ts:999-1020` | Desktop MCP 配置在 workspace 外，编辑不改变指纹 |
| Restricted workspace 会移除 `mcp.servers.json`，包括全局 MCP | `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java:178-198` | Desktop 模式也必须保持“restricted 不执行 MCP” |
| Desktop 没有 MCP 存储；`settings.json` 只放 desktop 偏好 | `main.ts:75-102`、`:202-219` | 新增独立 MCP store，避免污染 settings |
| Desktop 没有 `safeStorage` 使用；provider/auth 密钥是明文 JSON（0600） | `core-ai-desktop/src/main/providers/ProviderStore.ts:110-127`；`core-ai-desktop/src/main/auth/AuthStore.ts:54-57`；全仓 `rg safeStorage` 无结果 | MCP secret 不能沿用明文存储；需要新的 safeStorage 封装 |
| Plugin host 明确不向 engine 传 hook/MCP 路径 | `core-ai-desktop/src/main/plugins/PluginHost.ts:1-7`；`app.ts:7914-7915` | 本设计不改变 plugin 的 MCP 边界 |

### 2.2 CLI / engine 现状

| 事实 | 源码依据 | 对本设计的意义 |
|---|---|---|
| CLI 配置链：全局 `~/.core-ai/agent.properties` → workspace `.core-ai/agent.properties` → workspace `MCP.json` | `core-ai-cli/src/main/java/ai/core/cli/CliAppHelper.java:137-152`；`CliMcpConfig.java:18-51` | **CLI 默认链不变**；Desktop 模式用 `includeWorkspaceMcpFile=false` 跳过 `MCP.json` 合并，并丢弃 `agent.properties` 里的 `mcp.servers.json` 键 |
| Workspace `MCP.json` 同时接受 `MCP.json` 与 `mcp.json`（先 canonical） | `CliMcpConfig.java:46-51` | Desktop 只把这两个文件当导入来源；不写 |
| `mcpServers` 包装与裸 map 都接受 | `CliMcpConfig.java:53-61` | 导入兼容 Claude/Cursor 格式 |
| 一个 server 配置非法会抛异常，`AgentBootstrap` 捕获后整份 MCP 不可用 | `core-ai/src/main/java/ai/core/bootstrap/AgentBootstrap.java:231-254`；`McpClientManager.java:24-34` | P0 需要逐 server 隔离（至少 Desktop 模式） |
| 解析字段：`command`/`args`/`env`/`url`/`endpoint`/`transport`/`headers`/超时/重连/心跳 | `core-ai/src/main/java/ai/core/mcp/client/McpServerConfig.java:25-101`、`:140-205`、`:206-240` | Desktop 表单只暴露子集；编辑时保留未知字段 |
| 传输枚举：STDIO / STREAMABLE_HTTP / SSE / SANDBOX_HOSTED | `core-ai/src/main/java/ai/core/mcp/client/TransportType.java:6-11` | 本地只支持前三者 |
| `mcp.servers.json` 不展开 `${VAR}`；env/header 值原样传给子进程/HTTP | `McpServerConfig.java:166-205`；`McpTransportFactory.java:55-153` | Desktop 模式需要新的 secret map 展开；CLI 保持原样 |
| MCP manager 在 bootstrap 时 `warmup()` 并启动心跳；CLI/app-server 现状是 eager 连接 | `McpClientManager.java:149-165`；`AgentBootstrap.java:238` | Desktop 模式改为延迟 warmup，等 `mcp/config` + `mcp/secrets` + `mcp/finalize` 后连接 |
| 连接状态：NOT_CONNECTED / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED | `McpClientManager.java:411-418` | 可暴露为 `mcp/status`；但当前无 RPC |
| 默认超时：connect 10s、request 60s、init 60s；重连 3 次、5s 起、指数退避、上限 60s | `McpServerConfig.java:226-240`；`McpConnectionMonitor.java` | UI 高级项默认值；Test 超时要覆盖 init + tools |
| 非认证 `FAILED` 会在心跳周期重置并继续重试；只有认证错误永久失败 | `McpConnectionMonitor.java:114-134`；`McpClientManager.java:249-277` | UI 用 `retrying`，不承诺“3 次后永久停止” |
| `McpClientManager.close()` 会 `Schedulers.shutdownNow()`（JVM 全局副作用） | `McpClientManager.java:302-338` | 临时 probe 用一次性 `McpClientService`，不用 manager |
| MCP 工具按 raw name 注册，描述加 `[MCP:server]` 前缀 | `core-ai/src/main/java/ai/core/tool/mcp/McpToolCalls.java:49-60` | 同名跨 server 会覆盖；描述可用于 UI 来源显示 |
| `McpToolProvider` priority=3，`ListToolProvider`=5、`BuiltinToolProvider`=10；`ToolRegistry` 升序 `putIfAbsent` | `McpToolProvider.java:62-64`；`core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java:99-121` | MCP 先注册，可覆盖非 MCP 工具；安全前置项 |
| 工具名超过 64 字符会被截断（取后 64 再裁到第一个 `_` 之后） | `core-ai/src/main/java/ai/core/tool/ToolCall.java:193-209` | P0 在 MCP 注册阶段直接拒绝 >64 名称 |
| MCP tool annotations（readOnlyHint/destructiveHint）未被读取 | `McpToolCalls.java:49-60`；全仓无 `readOnlyHint` 引用 | 只能做“信息展示”，不能当安全边界 |
| 工具审批走通用 `ServerPermissionLifecycle`；子 agent、autoApproveAll、session allow 会跳过 | `core-ai/src/main/java/ai/core/session/ServerPermissionLifecycle.java:108-136`、`:178-188` | MCP 工具不是天然免审批；`full`/子 agent 会跳过，需在文档和 UI 说明 |
| CLI 预允许一批 builtin 工具名（`read_file`、`grep_file` 等） | `core-ai-cli/src/main/java/ai/core/cli/CliAppHelper.java:345-363` | 若 MCP 工具覆盖这些名字，可能继承预允许 |
| `WorkspaceAutoPermissionStore` 自动放行 workspace 内 `write_file`/`edit_file`/`hash_edit_file` | `core-ai-cli/src/main/java/ai/core/cli/appserver/WorkspaceAutoPermissionStore.java:19-47` | 同名覆盖的风险不仅是 builtin 预允许 |
| `AddMcpServerTool` 无条件注册，允许模型传任意 command/url 启动 MCP | `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java:298-305`；`core-ai/src/main/java/ai/core/tool/tools/AddMcpServerTool.java:86-106` | Desktop 模式必须禁用它，否则绕过 store |
| Engine 日志：`~/.core-ai/logs/<workspaceName>_<date>.log`；INFO 只写文件，WARN/ERROR 同时 stderr | `core-ai-cli/src/main/java/ai/core/cli/log/CliLogger.java:33-67`、`:132-150` | Desktop 可读日志尾部；需脱敏与大小上限 |
| App-server 已有 `tools/list`（扁平 name/description），没有 MCP 状态/探测 | `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java:205`、`:318-330` | 需要新增 MCP RPC；不能靠 `tools/list` 区分状态 |
| `AppServer` 单线程读行分发，慢 RPC 会阻塞 approve/cancel/quiesce | `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServer.java:57-70` | `mcp/probe` 必须异步；`mcp/config`/`status` 必须快 |

### 2.3 Server 侧与 agent MCP 引用

| 事实 | 源码依据 | 对本设计的意义 |
|---|---|---|
| MCP Hub 是只读/调用代理，暴露 servers/tools/describe/call 四个路由 | `core-ai-api/src/main/java/ai/core/api/server/McpHubWebService.java:14-35`；`McpHubServiceImpl.java` | Desktop 不写 hub；Hub 路由与本地 MCP 分开 |
| Hub 路由需要 `mcp.call`；默认 `user` 角色没有该权限 | `core-ai-server/src/main/java/ai/core/server/mcphub/McpHubWebServiceImpl.java:25-49`；`RoleRegistry.java:38-49` | Desktop 的 Hub 功能受 server RBAC 约束 |
| Hub 无持久化，工具目录是 30s 刷新的内存快照 | `McpToolCatalogService.java:21-28`、`:186-219` | 不能把 Hub 工具当本地 MCP 状态 |
| Server 侧 MCP server 由 `tool_registry` + 部署配置 `mcp.servers.json` 注册；写操作需 `mcp.manage` | `ToolRegistryEntry.java`；`ToolRegistryService.java:158-194`；`ToolRegistryWebServiceImpl.java:101-139` | Desktop 不做 server 注册 |
| Server agent 用 `ToolRef` 引用 MCP：`mcp-tool:{serverId}:{tool}` 或 server 级 `MCP` | `core-ai-server/src/main/java/ai/core/server/domain/ToolRef.java:10-32`；`ToolRefResolutionService.java:316-361` | 远程 agent 的 MCP 来自 server，不来自 desktop 本地配置 |
| Sandbox-hosted MCP 按 session 启动，首 turn 有 readiness 等待 | `SessionMcpReadinessLifecycle.java:40-52`；`SessionMcpProcesses.java:17-124`；`SandboxConstants.java:74-98` | 这是 server 侧生命周期，不与 Desktop 本地 engine 共用 |
| CLI/app-server 没有 session 级 MCP readiness；MCP manager 是 engine 进程全局 | `AgentBootstrap.java:231-254`；`CliAgent.java:116-123`；`McpToolProvider.java:32-39` | 同一 workspace 的会话共享 MCP 进程；不同 workspace 由不同 engine 隔离 |
| 本地 subagent 通过 tool name allowlist 过滤，不是独立 MCP server 配置 | `core-ai/src/main/java/ai/core/agent/AgentBuilder.java:181-215` | agent 级只能做“选择/过滤”，不能定义新 server |

### 2.4 关键缺口与风险

| ID | 缺口 / 风险 | 证据 | 级别 | 本设计的处理 |
|---|---|---|---|---|
| G1 | Desktop 没有 MCP 管理 UI / IPC / store | `preload.ts:4-194`、`main.ts:954-1671`、`app.ts:7651-7795` | 功能缺口 | P0 新增 `mcp:*` API、Settings 区块、per-workspace store |
| G2 | 一个非法 server 会让整份 MCP 不可用 | `AgentBootstrap.java:231-254`；`McpClientManager.java:24-34` | S1 | Desktop 模式逐 server 隔离；非法项进 status |
| G3 | 配置没有 secret 机制，env/header 只能写明文 | `McpServerConfig.java:166-205`；全仓无 `${env:}` 展开 | S1 | Desktop 模式新增 `${env:NAME}` + safeStorage；缺失 fail closed |
| G4 | MCP 工具可覆盖 builtin 工具名，且 builtin 名称在 CLI 预允许列表里 | `McpToolProvider.java:62-64`；`ToolRegistry.java:99-121`；`CliAppHelper.java:345-363` | S1 | P0 保护所有非 MCP provider；冲突工具不注册并告警 |
| G5 | 跨 MCP server 同名工具由 `ConcurrentHashMap` 顺序决定，不确定 | `McpClientManager.java:99-101`；`McpToolProvider.java:72-90` | S2 | 按 server name 排序注册；冲突记录并告警 |
| G6 | 没有 MCP 状态/探测 RPC；同步 RPC 会阻塞单线程 AppServer | `AppServerEngine.java:205`、`:318-330`；`AppServer.java:57-70` | S1 | P0 新增 `mcp/status` + 异步 `mcp/probe/start/result` |
| G7 | 现有 `AgentBootstrap` 在构造期 eager warmup，无法等 Desktop 配置 | `AgentBootstrap.java:231-254`；`McpClientManager.java:149-165` | S1 | Desktop 模式延迟 warmup，`mcp/config` + `mcp/secrets` 后再连接 |
| G8 | 临时 `McpClientManager.close()` 会关全局 Reactor scheduler 并注册 shutdownHook | `McpClientManager.java:302-338` | S1（实现前置） | probe 用一次性 `McpClientService`；不用临时 manager |
| G9 | MCP 进程与 engine 同用户权限，无文件/网络隔离 | `McpTransportFactory.java:55-83`；`McpClientService.java:228-331` | S2（无法 P0 消除） | UI/文档明确“可执行本地代码”；P2 才评估 OS 沙箱/egress |
| G10 | 把 secret 注入 engine `process.env` 会让 Bash/Skill/Hook 和所有 MCP 子进程读到 | `EngineSupervisor.ts:122-123`；`McpTransportFactory.java:71-73` | S1 | 改为 `mcp/secrets` 内存 map；Desktop 模式禁止回退 process.env |
| G11 | MCP annotations 未读取，无法区分只读/危险 | `McpToolCalls.java:49-60` | S3 | P0 只展示 server 描述；P1 解析 annotations 作 advisory |
| G12 | 日志无轮转，MCP server stderr 未单独归档；同名 workspace 共享日志文件 | `CliLogger.java:33-67` | S3 | P0 日志尾部 + 大小上限；P1 ring buffer/轮转 |
| G13 | `add_mcp_server` 运行时工具可绕过配置/确认/状态 | `CliAgent.java:298-305`；`AddMcpServerTool.java:86-106`；`ServerPermissionLifecycle.java:178-188` | S1 | Desktop（`--desktop-mcp`）模式不注册该工具；其他 app-server/CLI 不变 |
| G14 | `engine:call` 无 main-frame guard/方法白名单/workspace 校验 | `main.ts:1427-1436` | S2 | 加 guard、已知 workspace 校验、方法白名单；拒绝内部 `mcp/*` |
| G15 | `McpServerConfig` 解析失败日志记录原始 headers/env 字符串 | `McpServerConfig.java:166-205` | S2 | P0 前置脱敏：只记录字段名/长度，不打印原值 |
| G16 | Windows `cmd.exe /c` 包装可能被 command/args 中的 `&`/`|`/`%VAR%` 注入 | `McpTransportFactory.java:55-83` | S2 | P0 对 command 本体和 args 硬拒绝危险元字符；展开后的 secret 也复检 |
| G17 | CLI 的 MCP 配置与 Desktop 共享会互相扰动 | 现状无 Desktop store | 架构缺口 | 本设计用独立 store + Desktop-only 注入彻底隔离 |
| G18 | 用户级/全局 MCP 会让 workspace 之间串配置 | 前一版设计 | 架构缺口 | 本设计只做 per-workspace，无全局层 |

## 3. 目标架构与 ADR

### 3.1 分层

```text
Renderer (Settings → MCP servers for this workspace)
  │  window.coreai.mcp*  (workspace-scoped, sanitized DTO)
  ▼
Preload (explicit allowlist)
  ▼
Electron Main
  ├─ McpStore          <userData>/mcp/workspaces/<hash>.json
  ├─ McpSecretStore    safeStorage ciphertext, same workspace partition
  ├─ McpService        IPC, validation, import, safe-point reload
  └─ EngineSupervisor  Desktop mode flag + mcpConfigProvider
        │  engine RPC: mcp/config | mcp/secrets | mcp/finalize | mcp/status | mcp/probe/*
        ▼
core-ai-cli app-server engine (Java, Desktop mode)
  ├─ DesktopMcpConfig (new): per-workspace config from RPC; skips file MCP
  ├─ McpSecretProvider (new): per-server env map from RPC; never process.env
  ├─ McpClientManager (existing): stdio / HTTP / SSE, heartbeat/reconnect
  └─ ToolRegistry (existing): non-MCP providers first, then MCP; conflicts skipped
        ▼
MCP server processes / remote endpoints (one set per workspace engine)
```

### 3.2 ADR-1：Desktop 自有、按 workspace 隔离的 MCP store

**决定**：

- Desktop 的 MCP 定义存 `app.getPath('userData')/mcp/workspaces/<sha256(workspaceKey)>.json`。
- secret 存 `app.getPath('userData')/mcp/secrets/<sha256(workspaceKey)>.json`（safeStorage 密文）。
- 不创建、不修改任何 CLI 的文件（`agent.properties`、`MCP.json`、`mcp-state.json`）；只在用户显式 Import 时读取一次。
- 每个 workspace 一个文件；文件内记录 `workspaceKey` 便于诊断。

**理由**：

- 用户明确要求“不同 workspace 不同配置”和“MCP 不能影响 CLI/Desktop 状态”。共享文件、workspace 文件、`agent.properties` 都会把 MCP 编辑变成对 CLI/工作区状态的写入，违背约束。
- Desktop store 在 workspace 外，编辑不改变 workspace trust 指纹；不会让工作区突然变成 restricted。
- CLI 完全看不到 Desktop store；CLI 的旧配置链不变。

**代价**：

- 同一 server 若 CLI 和 Desktop 都要用，需要在两端各配一次；用“导入”降低摩擦。
- Desktop 不再自动跟随 `.core-ai/MCP.json` 的变更；这是有意的隔离。

### 3.3 ADR-2：Engine 的 Desktop 模式是显式、可关闭的分支

**决定**：

- Desktop spawn engine 时传 `app-server --desktop-mcp`（**不用环境变量**，避免被 Bash/Hook 子进程继承后污染 CLI）。只对 bundled/dev engine 或 manifest 版本支持的 external engine 传；不支持的 external engine 不传，MCP 管理 UI 显示“engine 不支持”。
- **版本门控与回退**：
  - packaged：使用 Desktop 随包的 engine，默认支持；直接传。
  - dev：`resolveEnginePath()` 指向 repo build；默认按当前源码支持；若 build 过旧，走下面的回退。
  - external：`CORE_AI_ENGINE_PATH` 一律按 external 处理；读 `engine.json`/manifest 的版本，>= 首个支持版本才传；无 manifest 或版本未知则不传。
  - 如果带 `--desktop-mcp` 启动失败（exit=2 且 **启动期完整 stderr 缓冲**含 `Unknown option` / `--desktop-mcp`），自动无参重试一次，并标记该 engine “MCP unsupported”；不要让 workspace 卡死。不要只看最后一行 stderr（picocli 会先打 Usage 多行），也不要把 `EngineAlreadyRunning`（同为 exit=2）误判成未知选项；`RpcClient` 需要保留启动期 stderr 缓冲供匹配。
- 该模式下：
  - `EngineBootstrap` 跳过 `MCP.json` 合并（`includeWorkspaceMcpFile=false`），并在 `agent.properties` 合并后丢弃 `mcp.servers.json`，不加载任何文件型 MCP。
  - `AgentBootstrap` 创建空 `McpClientManager` 但不 warmup。
  - `initialize` 返回 `mcp_management_v1`。
  - Desktop 依次调用 `mcp/config`、`mcp/secrets`、`mcp/finalize`；engine 校验后异步 warmup。
  - 启用 `mcp/status`、`mcp/probe/*`；不注册 `add_mcp_server`。
- 没有 `--desktop-mcp` 选项时，engine 完全走旧路径：文件型 MCP、eager warmup、`add_mcp_server` 保持现状。
- 该分支不改变 CLI 的配置加载、合并、默认值或命令。

**理由**：

- 直接改 `CliMcpConfig`/`CliAppHelper` 的 CLI 配置合并链会改变 CLI 行为，违反 R2。
- Desktop 需要一个“只认 Desktop store”的 engine 实例；显式命令行选项是最小、可审计、且不会被子进程继承的开关。
- Capability 让 Desktop 能识别旧 engine 并降级，而不是发送未知 RPC 把旧 engine 打挂。

### 3.4 ADR-3：Workspace trust 是执行门禁，不是配置存储

**决定**：

- Desktop MCP 配置在 workspace 外，不参与 `WorkspaceTrust` 指纹。
- **Desktop 模式使用独立的 trust 指纹**：排除 `.core-ai/MCP.json`、`.core-ai/mcp.json`、根 `MCP.json`/`mcp.json`（Desktop 不加载它们），保留 `hooks.json`、`agent.properties`、`.core-ai/plugins/**`、`.core-ai/skills/**`。CLI 模式仍包含 MCP 文件。trust record key 用 `<canonical>#desktop-mcp`，与 CLI 的 trust record 分开；`trustState` 同时返回 `workspaceKey`（canonical path）供 MCP store 使用。
- 受限 workspace 不执行 MCP：Desktop 不发送 `mcp/config`/`mcp/secrets`（或发送空 map），engine 也拒绝加载。
- 编辑 MCP 不需要重新 Trust；保存后只有当前 workspace 的 engine 在安全点重启。
- 编辑 CLI `.core-ai/MCP.json` 只改变 CLI 的 trust 指纹，**不改变 Desktop 模式的指纹**；Desktop 不会因此变 restricted。CLI 自己仍按原语义要求 Trust。
- 如果 workspace 因为其他可执行输入（hooks/skills/`agent.properties`）变成 restricted，MCP 仍按 restricted 处理；用户 Trust 后恢复。

**理由**：

- 用户要“MCP 不影响 Desktop 状态”；把 MCP 写进 workspace 会让每次编辑都改指纹、触发 restricted/重新 Trust，这是典型的状态扰动。
- 但 MCP server 仍是本机可执行代码，必须保留“受限 workspace 不执行”的安全门禁。
- 配置归属（Desktop）与执行门禁（workspace trust）分离，两者都满足。

### 3.5 ADR-4：P0 只重启当前 workspace，P1 再热更新

**决定**：

- 保存/启停/删除：写 Desktop store → 对当前 workspace `quiesce` → 空闲则 `restart + ensureTrustState`；busy 则标记 pending，turn 结束后重试。
- 不调用 `supervisor.shutdownAll()`；不遍历其他 workspace。
- P1 可选：`mcp/reconnect`、`mcp/add`、`mcp/remove` 热操作；仍只影响当前 engine。

**理由**：

- engine 的 MCP manager 是进程全局；配置变更最可靠的方式是重启该 engine。
- 只影响当前 workspace 是“不影响其他 Desktop 状态”的最小代价。
- 热更新需要处理 manager、tool registry cache、provider 注册、审批规则等多处状态，风险更高。

### 3.6 ADR-5：Secret 按 workspace + server 隔离，走内存 map

**决定**：

- 配置只写 `${env:NAME}`；值用 `safeStorage.encryptString` 加密，按 workspace + server + env 存储。
- Desktop 在 `initialize` 后调用 `mcp/secrets`，传 `{servers: {name: {ENV: value}}}`；engine 只存内存。
- engine 用 `(serverName, ENV)` 解析 `${env:NAME}`，只把值放进该 server 的 `env`/`headers`。
- Desktop 模式**禁止回退 `process.env`**：缺失即该 server 失败；防止恶意配置借 `${env:...}` 读取宿主环境。
- CLI 模式保持现状：**不展开 `${env:NAME}`**，字面量传给 server；本设计不改变 CLI 的 MCP 配置语义。

**理由**：

- 避免 Bash/Skill/Hook 和无关 MCP 子进程读取 secret。
- 不同 workspace 的 secret 不会进入对方的 engine。
- 同 workspace 内不同 server 不共享未显式下发的变量。

### 3.7 被拒绝的替代方案

| 方案 | 拒绝理由 |
|---|---|
| 与 CLI 共享 `~/.core-ai/MCP.json` 或 `mcp-state.json` | 违背 R2；CLI 会读/被写；旧 CLI 不认识 `enabled`，禁用不生效或整表失败 |
| Desktop 直接写 workspace `.core-ai/MCP.json` | 改变 workspace trust 指纹，触发 restricted/重新 Trust；违背 R3；还会被 Git 跟踪 |
| 直接编辑 `agent.properties` 单行 JSON | 破坏用户文件、无法表达 disabled、secret 明文、并发冲突 |
| Desktop 内置 Node MCP client | 两套协议实现；secret/审批/工具注册分叉；无法复用 engine 的进程树清理 |
| 把 server 配置放 SQLite/数据库 | 用户要求不改 DB；CLI 无法读取；破坏文件可移植性 |
| 每次保存都自动信任 workspace | 等于取消指纹语义；外部编辑与 UI 编辑无法区分 |
| 用户级/全局 MCP 列表 | 违背 R4；workspace 之间串配置；用户已明确要 per-workspace |
| 把 secret 注入 engine `process.env` | Bash/Skill/Hook 和所有 MCP 子进程都能读取；改为 `mcp/secrets` 内存 map |
| P0 在同步 RPC 里做 probe | AppServer 单线程分发会被阻塞；必须异步 start/result |
| P0 做 per-session MCP 进程 | engine 当前 manager 是进程全局；改动大，收益低；先用 workspace 隔离 + P1 过滤 |
| 把 Hub server 当本地 server 编辑 | Hub 是管理员注册、server 执行、凭据不出 server；Desktop 不应越权 |

## 4. 配置模型与优先级

### 4.1 只有一个作用域：当前 workspace

| 维度 | P0 行为 |
|---|---|
| 配置来源 | 仅 Desktop store 中当前 workspace 的文件 |
| 优先级 | 没有多层优先级；同一 workspace 内 server name 唯一，重复即校验错误 |
| 用户级 | 不存在 |
| 全局级 | 不存在 |
| Agent 级 | P1 过滤器（禁用/白名单），不是第二套定义 |
| Session 级 | P1 参数（选择子集），不是第二套定义 |
| CLI 文件 | 仅导入来源；不参与运行时 |
| Server Hub | 独立面；只读/调用 |
| Plugin `.mcp.json` | 不启用 |

**切换 workspace 的效果**：UI 列表、secret、engine 配置、MCP 进程全部切到目标 workspace 的那一份；A 的 server 不会出现在 B。

### 4.2 合并与覆盖规则

- 没有跨层合并。一个 workspace 的 effective 配置 = 该 workspace store 中 `enabled=true` 的 server 定义。
- 同一 workspace 内 server name 唯一；保存时若重名，视为编辑已有项或要求改名（由 UI 明确）。
- 禁用不删除定义；`enabled=false` 的项不进 `mcp/config`，但保留在 store 中，便于恢复。
- 导入冲突：Skip / Replace / Rename（默认 Skip）；导入是复制，不是链接。
- 未知字段：保留在 `definition` 中；UI 编辑已知字段时不丢弃。
- 解析失败：单 server 校验失败只跳过该 server；整个 store 文件损坏则视为空并提示，不覆盖原文件。

### 4.3 启用/禁用模型

- 存在 Desktop store 的 `servers[name].enabled`，不是 engine 配置字段。
- 启停只改 Desktop store，不改 CLI 文件，不改 workspace 指纹。
- 生效：当前 workspace 安全点重启；其他 workspace 不变。
- 旧 engine 降级：没有 `mcp_management_v1` 时，Desktop 不提供 MCP 管理（显示“engine 不支持”），也不写任何 CLI 文件。

### 4.4 Restricted workspace

- Engine 计算 trust；restricted 时：
  - Desktop 不发送 `mcp/config`/`mcp/secrets`（或发送空 map）。
  - Engine 即使收到也拒绝加载；`mcp/status` 返回 `restricted: true` 和空 servers（engine 没有 config，不应猜列表）。Desktop 用 store 列表叠加渲染，所有行标 `BLOCKED`。
- Desktop UI：
  - MCP 区块显示 “MCP is disabled until this workspace is trusted”，提供 “Trust this workspace…” 按钮。
  - **允许编辑/保存定义**（store 在 workspace 外，保存不执行任何东西），但**禁用启停/Test/Probe**；保存后保持 disabled/blocked，直到 Trust。
- 编辑 MCP 本身不会让 workspace 变成 restricted；只有 Desktop 模式指纹内的可执行输入变化才会。
- Trust 后，Desktop 重新发送配置并重启当前 engine；其他 workspace 不受影响。
- **CLI MCP 文件不参与 Desktop 指纹**：只有 `.core-ai/MCP.json` 的 workspace 在 Desktop 模式下不会因此 restricted；Desktop 不加载该文件，只把它当导入来源。CLI 自己的 trust 仍包含该文件。
- **模式隔离**：CLI trust record（`<canonical>`）与 Desktop trust record（`<canonical>#desktop-mcp`）分开；在 CLI 里 Trust 不会自动信任 Desktop 的可执行输入，反之亦然。
- **`agent.properties` 例外**：Desktop 模式仍把 `.core-ai/agent.properties` 纳入指纹（它可能包含 provider/hooks 等设置），不做按 key 的拆分。如果该文件只有 `mcp.servers.json`，workspace 仍可能因它变为 restricted；P0 不解析“只有 MCP 的 agent.properties”，P1 可评估按 key 的指纹。
- **存量信任迁移**：Desktop 模式先查 `<canonical>#desktop-mcp`；没有时回退查旧 `<canonical>` 记录，并用**旧模式完整指纹**（含 MCP 文件）校验。匹配则视为已信任，并写入新的 `#desktop-mcp` 记录；不匹配则 restricted。这样已有 Desktop 信任的工作区升级后不会无条件重新 Trust；含 MCP 文件且旧记录匹配的也保留信任。迁移只对文件做 hash（与现有 `WorkspaceTrust` 一致），不解析 MCP 配置、不执行 server。
- **文案**：`app.ts:2906` 的 Trust 弹窗在 Desktop 模式不应把 `MCP.json` 列为“Desktop 将执行的输入”；应写“CLI MCP config is not loaded by Desktop; it only affects the CLI trust”。

### 4.5 与现有 `MCP.json` 的迁移/兼容

**读取边界**：Desktop 不监视、不自动读取这些文件；只有用户点击 Import 时读取一次，用于预览和复制。导入后两边独立，Desktop 不写回、不删除原文件。

| 场景 | 行为 |
|---|---|
| 已有 `.core-ai/MCP.json`（裸 map） | 不自动加载；UI 显示 “CLI config detected” + Import 按钮；导入时兼容裸 map |
| 已有 `.core-ai/MCP.json`（`mcpServers` 包装） | 同上；导入 `mcpServers` |
| 已有 `.core-ai/mcp.json`（小写） | 同上；导入时读取小写文件 |
| 根目录 `MCP.json`/`mcp.json` | 不读取；UI 可提示“root MCP.json affects CLI trust but is not used by Desktop” |
| 旧 `agent.properties#mcp.servers.json` | 不自动加载；提供 “Import from agent.properties”；不修改原文件 |
| 用户级 `~/.core-ai/MCP.json` | 不存在此设计；如果用户手工创建，Desktop 不读取 |
| 导入 Claude Desktop / Cursor `mcpServers` | 支持；粘贴/文件导入；多 server 按 key 命名 |
| 导出 | 输出 `{"mcpServers":{...}}`；secret 保持 `${env:NAME}`，不含明文 |
| 未知传输/字段 | 保留并提示“当前版本不支持，engine 可能忽略”；不静默删除 |
| 双向同步 | 不做；导入后两边独立；需要更新时重新导入 |

**迁移原则**：只读、复制、不删除、不改写。Desktop 不能因为“导入”就修改 CLI 的配置；用户要清理 CLI 文件时自己处理。

### 4.6 Secret 解析与注入

- 语法：`${env:NAME}`，只支持整个值或值内嵌（如 `Bearer ${env:TOKEN}`）；不支持默认值、嵌套、命令替换。
- 展开范围：`args`、`env` 的值、`headers` 的值、`url`、`endpoint`。**`command` 不展开**：可执行文件必须是字面量。
- Desktop 模式：
  - 按当前 workspace、当前 server 从 secret store 取值。
  - `mcp/secrets` 形态：`{servers: {name: {ENV: value}}}`。
  - 缺失变量 → 该 server `INVALID`/`FAILED`；错误只含变量名。
  - **不回退** `process.env`；不同 workspace 的 secret 不进入本 engine。
- CLI 模式：没有 Desktop secret map；保持现状，**不展开 `${env:NAME}`**（字面量传给 server）。如果 CLI 用户需要 secret，使用自己的环境变量或字面值（与今天一致）。
- 拒绝保留名：`PATH`、`HOME`、`USERPROFILE`、`SYSTEMROOT`、`TEMP`、`TMP`、`APPDATA`、`LOCALAPPDATA`、`JAVA_HOME`、`NODE_OPTIONS`、任何 `CORE_AI_*`。
- Windows env name 大小写不敏感：校验/注入按大小写不敏感去重；POSIX 保持大小写敏感。
- 日志：任何路径都不打印值；`mcp/secrets` 请求/响应不进日志/trace。

### 4.7 变更影响矩阵

| 变更 | 写文件 | 改 trust 指纹 | 影响范围 | 需要重启 |
|---|---|---|---|---|
| 添加/编辑/删除当前 workspace server | Desktop store | **否** | 仅当前 workspace | 是（当前 engine，安全点） |
| 启用/禁用当前 workspace server | Desktop store | **否** | 仅当前 workspace | 是（当前 engine，安全点） |
| 修改 secret | Desktop secret store | **否** | 仅当前 workspace | 是（当前 engine，重新注入） |
| 导入 CLI `MCP.json` | Desktop store | **否** | 仅当前 workspace | 是（当前 engine） |
| 编辑 CLI `MCP.json` | 用户/CLI | **是（仅 CLI trust）**；Desktop 模式指纹排除该文件 | CLI 行为；Desktop 不变 restricted | 不影响 Desktop 的 MCP store 或 Desktop trust；见 U17（推荐 B） |
| 切换 workspace | 无 | 否 | UI 切到另一份 store | 目标 engine 懒启动/已有 engine 不变 |
| 信任/撤销 workspace | trust record | 记录变化 | 仅当前 workspace | 是（当前 engine） |

**注意**：如果 CLI 的 MCP 配置是 inline 在 `.core-ai/agent.properties`（`mcp.servers.json`），Desktop 模式仍会把该文件纳入指纹（它可能含其他可执行设置）；此时编辑 inline MCP 可能让 Desktop restricted。用 `.core-ai/MCP.json` 存放 CLI MCP 可以实现“CLI 编辑不影响 Desktop”；inline 方式没有这个保证。

### 4.8 Engine 接入点（提案，全部 Desktop-only）

| 位置 | 变更 |
|---|---|
| `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerCommand.java` | 新增 `--desktop-mcp` 选项；仅该模式启用 Desktop MCP 分支 |
| `core-ai-cli/src/main/java/ai/core/cli/workspace/WorkspaceTrust.java` | 新增 mode 参数：Desktop 模式指纹排除 `.core-ai/MCP.json`/`mcp.json`/根 `MCP.json`/`mcp.json`，trust record key 加 `#desktop-mcp`；CLI 模式不变 |
| `core-ai-cli/src/main/java/ai/core/cli/appserver/EngineBootstrap.java:178-198` | 当 `--desktop-mcp`：调用 `CliAppHelper.mergeWorkspaceConfig(..., includeWorkspaceMcpFile=false)`，**不读 workspace `MCP.json`**；仍合并 `agent.properties` 的其他键；然后 `removeProperty("mcp.servers.json")` 丢弃 inline 值 |
| `core-ai/src/main/java/ai/core/bootstrap/AgentBootstrap.java:231-254` | 新增 `deferMcp` 分支：创建空 `McpClientManager`、注册到 `BootstrapResult` 和 `McpClientManagerRegistry`、不 warmup；**不能被“无 `mcp.servers.json` 就 clear registry”的旧分支清掉**；CLI 默认分支不变 |
| `core-ai-cli/src/main/java/ai/core/cli/appserver/AppServerEngine.java` | 新增 `mcp/config`、`mcp/secrets`、`mcp/finalize`、`mcp/status`、异步 `mcp/probe/start/result/cancel`；Desktop 模式 capability `mcp_management_v1`；**Desktop 模式 `initialize` 不启动 scheduler，由 `mcp/finalize` 启动**；`EngineScheduler.start()` 幂等 |
| `core-ai-cli/src/main/java/ai/core/cli/agent/CliAgent.java:298-305` | Desktop 模式不注册 `AddMcpServerTool`；CLI 交互模式不变 |
| `core-ai/src/main/java/ai/core/mcp/client/McpServerConfig.java` | 新增可选 secret map 参数用于 Desktop 模式 `${env:NAME}` 展开；不传 map 时保持 CLI 现状（不展开，字面量传给 server）；解析异常日志脱敏 |
| `core-ai/src/main/java/ai/core/mcp/client/McpClientManager.java` | 逐 server 错误收集；审计 `close()` 的全局 Reactor 副作用；跨 server 工具名排序/冲突；Desktop 模式 `warmup` 幂等（AtomicBoolean）并用专用 executor，不用 common ForkJoinPool |
| `core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java:99-121` | 两阶段注册：先所有非 MCP provider，再 MCP；MCP 不覆盖非 MCP；>64 名称拒绝 |
| `core-ai/src/main/java/ai/core/tool/mcp/McpToolProvider.java:72-90` | Desktop 模式改为读非阻塞 tool snapshot；`map.put` 改排序后 `putIfAbsent`；CLI 模式保持 `EVERY_TURN` + `safeListTools` |

**明确不改 CLI 默认路径**：`CliMcpConfig.java` 的 CLI 调用、`CliAppHelper.mergeWorkspaceConfig` 的默认 `includeWorkspaceMcpFile=true`、`LocalToolRuntime` 的 MCP 合并、`AcpAgentRunner` 的 MCP 行为、`app-server --config` 语义。只给 `EngineBootstrap` 增加 `includeWorkspaceMcpFile=false` 的 Desktop 分支；CLI 调用仍为 true。

## 5. 数据模型与持久化

### 5.1 文件布局

| 文件 | 权限 | 内容 | 谁读 |
|---|---|---|---|
| `<userData>/mcp/workspaces/<sha256>.json` | 0600（目录 0700） | 单个 workspace 的 server 定义 + enabled + 元数据 | Desktop Main only |
| `<userData>/mcp/secrets/<sha256>.json` | 0600（目录 0700） | 单个 workspace 的 safeStorage 密文；无明文 | Desktop Main only |
| `<userData>/mcp/README`（可选） | — | 说明该目录是 Desktop 私有，CLI 不读 | 用户 |

- 文件名 = `sha256(workspaceKey)`；`workspaceKey` 用 engine 返回的 `trustState.workspaceKey`（`WorkspaceTrust.canonicalKey`：`toRealPath`，失败则 `toAbsolutePath().normalize()`）。不要在 Node 侧另算 canonical path，避免 Windows 盘符大小写/短路径/junction 不一致。
- `trustState.key` 是 **trust record key**（`<workspaceKey>#desktop-mcp`），只用于写/读 `workspace-trust.json`；不要拿它当 MCP store 文件名。
- 读取时校验文件内 `workspaceKey` 与当前 `trustState.workspaceKey` 一致；不一致视为损坏/不匹配，不加载，提示用户。
- 拿不到 `trustState.workspaceKey` 时，禁止写操作；读/删除可回退到 `index.json`（见下）。
- **索引**：`<userData>/mcp/index.json` 保存 `{version, workspaces: {<normalizedPath>: <workspaceKey>}}`，在 `initialize` 拿到 `trustState.workspaceKey` 时即 upsert（Windows key 小写归一），不等第一次保存。engine 不可用时，`McpService` 用 UI 的 normalized path 查索引，允许只读列表/删除/导出；找不到则显示 “engine unavailable; cannot locate this workspace's MCP config”。索引不含 secret。
- **身份统一**：`McpService` 先用 `supervisor.ensureTrustState(workspace)` 拿 `trustState.workspaceKey`（store key）和 `trustState.key`（trust record）；`EngineSupervisor` 仍用 UI 传入的 `workspace` 字符串索引 engine。Desktop 在选择 workspace 时应先 `path.resolve` 归一，避免同一目录的 `C:\WS\` / `C:\ws` 触发两个 engine；MCP store 以 engine 返回的 `workspaceKey` 为准。
- **懒启动边界**：`mcpList` 为解析 `workspaceKey` 可能触发该 workspace 的 engine 懒启动；这不写配置、不重启、不影响其他 workspace。若要求“仅打开 Settings 不启动 engine”，P1 可先用 `index.json` + `path.resolve` 读，缺失 key 时再启动。
- **重启闭环**：`supervisor.restart(workspace)` 只 dispose，不会自动拉起；MCP 保存后必须再 `ensureTrustState(workspace)`，否则 UI 会看到“engine 已停止”。
- 一个 workspace 一个文件；删除 workspace 不自动删除文件（P1 清理），避免误删。

### 5.2 Store Schema

```json
{
  "version": 1,
  "workspaceKey": "C:\\work\\project-a",
  "updatedAt": "2026-11-26T10:00:00.000Z",
  "servers": {
    "github": {
      "enabled": true,
      "definition": {
        "command": "npx",
        "args": ["-y", "@modelcontextprotocol/server-github"],
        "env": {
          "GITHUB_PERSONAL_ACCESS_TOKEN": "${env:GITHUB_PERSONAL_ACCESS_TOKEN}"
        }
      },
      "createdAt": "2026-11-26T09:58:00.000Z",
      "updatedAt": "2026-11-26T10:00:00.000Z"
    },
    "remote-example": {
      "enabled": false,
      "definition": {
        "url": "https://mcp.example.com",
        "endpoint": "/mcp",
        "headers": {
          "Authorization": "Bearer ${env:EXAMPLE_MCP_TOKEN}"
        },
        "connectTimeout": 15,
        "requestTimeout": 90
      }
    }
  }
}
```

字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `version` | number | 是 | 当前 1；不匹配时只读并提示升级，不覆盖 |
| `workspaceKey` | string | 是 | engine 的 canonical key；用于诊断和校验 |
| `servers.<name>.enabled` | boolean | 是 | 启停；不传给 engine 配置文件 |
| `servers.<name>.definition` | object | 是 | engine 兼容的 server 配置；只放无 secret 的值 |
| `servers.<name>.confirmedCommandHash` | string | 否 | 上次用户确认的 command/args/env 名称哈希；不写 `settings.json` |
| `definition.command` | string | stdio 必填 | 不展开 `${env:}` |
| `definition.args` | string[] | 否 | 支持 `${env:NAME}` |
| `definition.env` | `object<string,string>` | 否 | 值支持 `${env:NAME}` |
| `definition.url` | string | HTTP/SSE 必填 | 支持 `${env:NAME}` |
| `definition.endpoint` | string | 否 | 支持 `${env:NAME}` |
| `definition.transport` | string | 否 | `sse` 显式 SSE；其他/缺省 Streamable HTTP |
| `definition.headers` | `object<string,string>` | 否 | 值支持 `${env:NAME}` |
| `definition.connectTimeout` | number | 否 | 秒，默认 10 |
| `definition.requestTimeout` | number | 否 | 秒，默认 60 |
| `definition.initializationTimeout` | number | 否 | 秒，默认 60 |
| `definition.autoReconnect` | boolean | 否 | 默认 true |
| `definition.maxReconnectAttempts` | number | 否 | 默认 3 |
| `definition.reconnectInterval` | number | 否 | 秒，默认 5 |
| `definition.heartbeat` | number/boolean | 否 | 默认开启，30s |
| `definition.heartbeatTimeout` | number | 否 | 秒，默认 10 |
| `description` | string | 否 | Desktop 显示用；不传给 engine |

禁止：

- 不写 `enabled` 到 `definition`（engine 不认识，且会污染 CLI 兼容 schema）。
- 不写 secret 明文；只写 `${env:NAME}`。
- 不写 `sandbox_hosted`。

### 5.3 Secret Schema

```json
{
  "version": 1,
  "workspaceKey": "C:\\work\\project-a",
  "servers": {
    "github": {
      "GITHUB_PERSONAL_ACCESS_TOKEN": {
        "cipher": "base64...",
        "hint": "••••abcd",
        "updatedAt": "2026-11-26T10:01:00.000Z"
      }
    }
  }
}
```

- Key 是 server name；同一 workspace 内一个 server 的 env 名唯一。
- `cipher` = `safeStorage.encryptString(value)` 的 base64；不存明文、不存可逆混淆。
- `hint` 只保留末 4 位；短值显示 `••••`。
- `safeStorage.isEncryptionAvailable()` 为 false：拒绝保存 secret 字段；无 secret 的配置仍可保存/运行。
- Linux 上若 `safeStorage.getSelectedStorageBackend()` 为 `basic_text`，说明没有可用的 keyring，加密强度不足；UI 要警告并建议安装 keyring，而不是把 `basic_text` 当成安全存储。P0 可允许保存但显示明显警告；更严格的做法是拒绝保存，列为 U16。
- 解密失败（换机器、DPAPI 失效）→ 视为缺失；UI 提示重新输入，不自动删除其他 secret。
- secret 文件损坏 → 视为空并提示；不覆盖原文件。

### 5.4 原子写、并发与版本

- 每次保存前记录文件内容 SHA-256（`expectedHash`）。
- Main 写入前重新读取；hash 变化 → `MCP_STORE_CHANGED`，UI 显示 Reload，不覆盖外部编辑。
- 同一进程内按文件串行化写入（Promise queue）。
- 保存顺序：先校验 `expectedHash`；再对本次涉及的 secret 做旧密文快照（覆盖 Replace/Clear），写 secret store；最后写 config store。config 写失败时按快照回滚 Replace/Clear，不只回滚新增。
- 写入：同目录 temp 文件 → `fsync` → rename；失败保留 temp 供诊断，但不得含 secret。
- 备份：仅在导入覆盖/迁移时生成 `.bak-<timestamp>`；普通编辑依赖 Git/用户备份。
- 读取上限：1 MiB/文件；超过拒绝并提示，防止巨大 JSON 卡死 Main/Renderer。
- JSON 格式化：2 空格、UTF-8、末尾换行；server key 保留用户顺序，UI 可排序显示。

### 5.5 导入/导出

导入：

- 接受 `{"mcpServers":{...}}`（Claude Desktop / Cursor）、裸单 server 配置、裸多 server map。
- 单 server 且无 key：要求用户填 name；建议名从 `name`、`args` 首个非 flag token、URL host 推导。
- 多 server：key 为 name；请求里的 name 忽略。
- 冲突：Skip existing / Replace existing / Rename with suffix（默认 Skip）。
- 校验：每个 server 单独校验；全部通过才写（或明确 partial import）。
- Secret：检测到敏感项（`Authorization`、`*TOKEN*`、`*KEY*`、`*SECRET*`、`*PASSWORD*`）必须选择 “Store as secret” 并输入/确认变量名，否则该 server 不能导入；非敏感值可保留明文并标 `non-secret`。
- 导入不会写回 CLI 文件；只写 Desktop store/secret store。

导出：

- 输出当前 workspace 的 `{"mcpServers":{...}}`；保留 `${env:NAME}`，绝不导出 secret 值。
- 可选 “Export for Claude/Cursor”（同形状；字段尽量保留）。
- 导出前提示 “Secrets are not included; set these environment variables on the target machine: …”。

### 5.6 校验规则

| 规则 | 错误码 |
|---|---|
| name 非空，`[A-Za-z0-9][A-Za-z0-9._-]{0,63}` | `MCP_NAME_INVALID` |
| stdio 必须有 `command`，不能同时有 `url` | `MCP_TRANSPORT_INVALID` |
| HTTP/SSE 必须有 `url`，不能同时有 `command` | `MCP_TRANSPORT_INVALID` |
| `args` 必须是字符串数组 | `MCP_ARGS_INVALID` |
| `env`/`headers` 必须是 string→string | `MCP_ENV_INVALID` |
| `${env:NAME}` 的 NAME 匹配 `[A-Za-z_][A-Za-z0-9_]*` | `MCP_SECRET_NAME_INVALID` |
| 不允许保留 env name | `MCP_SECRET_NAME_RESERVED` |
| Desktop 模式下某 server 引用的 `${env:NAME}` 不在其 secret store 中 | `MCP_SECRET_MISSING` |
| 同一 server 的 env 对象出现大小写近似的重复 key（Windows） | `MCP_ENV_DUPLICATE` |
| 超时为正数、上限 600s | `MCP_TIMEOUT_INVALID` |
| URL 必须 `http://` 或 `https://`（本地回环允许 http） | `MCP_URL_INVALID` |
| header 名称/值不得含 `\r`/`\n` | `MCP_HEADER_INVALID` |
| `command`/args 不得含 `\0`/`\r`/`\n`；Windows `cmd.exe` 包装额外拒绝 `command` 本体和 args 中的 `&|<>^%!"` | `MCP_ARG_UNSAFE` |
| 不允许 `sandbox_hosted` | `MCP_TRANSPORT_UNSUPPORTED` |
| 未知字段 | 保留 + `MCP_UNKNOWN_FIELD` 警告（不阻断） |
| 文件过大/JSON 非法 | `MCP_STORE_PARSE_ERROR` |

## 6. 生命周期

### 6.1 启动与停止

- **Engine 启动（Desktop 模式）**：
  1. EngineBootstrap 因 `--desktop-mcp` 调用 `mergeWorkspaceConfig(..., includeWorkspaceMcpFile=false)`，**不读 workspace `MCP.json`**；仍合并 `agent.properties` 的其他键，然后丢弃 `mcp.servers.json`（inline），不创建文件型 MCP manager。
  2. `AgentBootstrap` 创建空 `McpClientManager`，注册到 `BootstrapResult`，不 warmup、不启动心跳。
  3. `initialize` 返回 `mcp_management_v1`、`trustState`；**不启动 scheduler**。Desktop 模式必须在配置/secret 就绪后才启动 scheduler，避免定时任务在 MCP 未就绪时抢跑。
  4. Desktop 在 `ready` 屏障内调用 `mcp/config`（当前 workspace 的 enabled server 定义），再调用 `mcp/secrets`（按 server 分组的 secret，可空），最后调用 `mcp/finalize`。
  5. engine 校验配置、逐 server 隔离错误、存入内存；`mcp/secrets` 立即返回并异步 `warmup()`。
  6. 仅当 `workspaceTrusted` 启动 scheduler；标记 `mcpReady=true`。
  7. **失败不阻塞工作区**：`mcp/config` 解析失败或 `mcp/secrets` 失败时，engine 仍完成 `initialize`/`ready`，MCP 标记 unavailable；会话可正常使用（无 MCP 工具），UI 显示错误。不能因为 MCP 配置错误让整个 workspace 打不开。
  8. **终态信号**：无论 config/secrets 成功、空还是失败，Desktop 都必须调用一次 `mcp/finalize` 作为 MCP 初始化终态；engine 收到后**必须启动 scheduler（仅 trusted，幂等）并置 `mcpConfigured`/`mcpReady`**。`mcp/finalize` 若发现 config 已加载但尚未触发 warmup，用空 secret map 触发一次异步 warmup。若 Desktop 不发 finalize：engine 以“最后一次 MCP RPC 后 10s 静默 / 首次 initialize 后 30s”兜底 finalize。否则定时任务会永久停摆、首 turn 白等。**scheduler 可能在 warmup 完成前触发**：定时任务按 snapshot 的可用子集执行，不阻塞等待完整 MCP；需要完整 MCP 的定时任务在 P1 增加 readiness 等待。
  9. **调用顺序**：`mcp/config` **从未调用**时，`mcp/secrets` 返回 `MCP_CONFIG_NOT_LOADED`；config 已调用但失败时，`mcp/secrets` 接受空 map（no-op）。`mcp/finalize` 任何时候都可调用；`mcp/config`/`mcp/finalize` 可重复调用但内容/结果幂等，不同 config 则 `MCP_CONFIG_ALREADY_LOADED`；空配置合法（表示该 workspace 没有 MCP）。
- **CLI 启动**：没有 `--desktop-mcp`，走旧路径（文件合并 + eager warmup + `add_mcp_server`）。
- **Engine 停止**：`McpClientManager.close()` 关闭所有 client、销毁 stdio 进程树；现有 `McpClientService` 已处理 Windows `taskkill /F /T` 和孤儿进程。
- **Desktop 退出**：`EngineSupervisor.shutdownAll()` 关闭各 workspace engine；MCP 子进程随 engine 退出。
- **禁用/删除**：写 Desktop store → 当前 workspace 安全点重启；旧进程随 engine 退出。

### 6.2 Workspace / session 隔离

| 维度 | P0 行为 | 说明 |
|---|---|---|
| Workspace | 每 workspace 一个 engine、一份配置、一组 MCP 进程/secret | A 的 server 不出现在 B；A 的 secret 不进 B 的 engine |
| Session | 同一 workspace 的所有 session 共享 manager 和进程 | 现有 `CliAgent` 全局 `McpToolProvider`；P0 不做 session 隔离 |
| Agent | 共享 manager；P1 用 tool name allowlist 过滤 | 本地 subagent 的 `tools:` 是名称过滤，不是独立 server 定义 |
| 远程会话 | 不注入本地 MCP | 远程会话使用 server agent 的 ToolRef |
| Server hub | 独立连接与执行 | 不走本地 manager |
| CLI | 不读 Desktop store；不因 Desktop 配置改变 | 两套配置完全独立 |

### 6.3 配置变更的生效

1. 写 Desktop store（原子）。
2. 对当前 workspace `supervisor.quiesce(workspace, 3s)`。
3. 空闲 → `restart` **再 `ensureTrustState`**（`restart` 只是 dispose，不会自动拉起）；busy → 标记 pending，turn 结束后重试（复用 `main.ts:454-514`）。
4. 重启后 `initialize` → `mcp/config` → `mcp/secrets` → `mcp/finalize` → warmup；UI 刷新状态。
5. 不遍历其他 workspace；不调用 `shutdownAll`。
6. 失败：保留 pending，UI 显示 “applies after current turn”；不反复 kill。
7. 如果 workspace 是 restricted：不发送 config/secrets；UI 显示 blocked；用户 Trust 后再执行 2–4。

### 6.4 健康检查

- **连接状态**：`getAllStates()` 暴露 `NOT_CONNECTED/CONNECTING/CONNECTED/DISCONNECTED/RECONNECTING/FAILED`。
- **Warmup 执行器**：Desktop 模式用专用有界 executor（或 virtual threads）做 connect + `listTools`，不要用 `parallelStream()` 的 common ForkJoinPool；否则慢 server 会占满公共池，拖慢其他异步任务。
- **心跳**：默认 30s；`McpConnectionMonitor` 对 CONNECTED 且启用 heartbeat 的 client 做 ping；失败触发重连。
- **重连**：`autoReconnect=true`，单轮最多 3 次，指数退避 5s→60s，jitter ±10%；401/403 等认证错误标记永久失败。非认证 `FAILED` 会在心跳周期重置并再次尝试（`McpConnectionMonitor.java:114-134`）；UI 显示 `retrying`，不承诺“永久停止”。
- **手动刷新**：P0 UI “Refresh status” 调 `mcp/status`；不主动连接。P1 加 `Reconnect`。
- **健康检查不替代工具调用成功**：CONNECTED 只表示握手成功；工具可能仍失败。

### 6.5 崩溃隔离

- 单个 stdio MCP server 崩溃：
  - `McpClientService` 检测进程退出，client 状态 FAILED；engine 不退出。
  - 心跳/重连尝试恢复；单轮超过 3 次后 FAILED，但非认证错误会在后续心跳周期再试；UI 显示 `retrying`。
  - 其他 server/工具不受影响（manager 按 server 隔离 client）。
  - 崩溃循环可能持续占用重连线程/日志；P0 用禁用/删除止损，P1 评估有界重试。
- 单个工具调用超时：
  - 默认 60s `requestTimeout`；同一 server 的工具调用串行（`McpClientService` `synchronized`），其他 server 不受影响。
- 恶意/失控 server 大量输出：
  - SDK 日志被 CLI muted；子进程 stderr 可能进入 engine 日志；P0 加日志大小上限/尾部读取。
- Engine 崩溃：
  - `EngineSupervisor` 检测退出，下次调用重新 spawn；MCP 子进程由 engine 退出清理。
- P0 **不提供** CPU/内存/文件/网络隔离；见 §7.7。

### 6.6 工具发现缓存

- Engine：`McpToolProvider` 默认 `EVERY_TURN`，每 turn 枚举所有 server 的 `listTools`；没有持久化缓存。
- Desktop：只为 UI 缓存最近一次 probe 结果（内存），显示 “last refreshed at”；engine 重启/配置变更后标记 stale。
- **Desktop 模式必须非阻塞**：`tools/list`、`session/create`、`session/resume`、helper 构造都在 `AppServer` 单线程分发循环上（`AppServer.java:57-70`；`AppServerEngine.java:318-330`；`CliAgent.java:364`）。它们不能调用 `McpToolProvider.provide()` → `McpClientManager.safeListTools()` → `getClient()/reconnectNow()`（`McpClientManager.java:204-216`），否则一个慢/坏 server 会卡住 `session/approve`/`cancel`/`quiesce`。
  - Desktop 模式新增 **tool snapshot**：warmup/心跳/显式 refresh 更新每个 server 的 `List<McpSchema.Tool>`；`McpToolProvider` 只读 snapshot，不发起连接/重连。
  - snapshot 缺失的 server 在该 turn 提供 0 个工具，后台刷新；不能阻塞等待。
  - `mcp/status`/`mcp/tools`（P1）也读 snapshot；`mcp/probe` 是唯一允许现场连接的地方，且在工作线程。
  - CLI 模式保留现有 `EVERY_TURN` + `safeListTools` 行为，避免改变 CLI。
- 安全：缓存只用于展示/工具枚举，不用于审批/权限判断；实际工具调用仍由 engine live client 执行。
- 大工具列表：列表截断（如 200 个），提示 refine；不把完整 schema 全量推给 renderer。

### 6.7 Test connection / probe

- P0 只允许探测 **已保存、enabled 的 server name**，不允许把未保存草稿传给 engine：
  - 避免执行未确认的任意命令；
  - 避免 secret 通过 RPC 反复传输；
  - 避免临时注入未保存 secret。
- **异步接口**：`mcp/probe/start {name}` 立即返回 `{probeId}`；engine 用工作线程执行 probe，完成后通知 `mcp/probe/result {probeId, ok, tools, elapsedMs, error}`；`mcp/probe/cancel {probeId}` 只置取消标记并**异步** close/interrupt 后立即返回。不能在 `AppServer` 单线程读行循环里同步等待握手或同步 `close()`（`AppServer.java:57-70`；`McpClientService.close()` 可能等进程树清理 15s+）。
- **实现方式**：一次性 `McpClientService`（可独立构造/关闭，`McpClientService.java:35-52`），禁用 reconnect/heartbeat；构造器同步握手只发生在**工作线程**，绝不占用 AppServer 分发线程；cancel 时置标记并 interrupt/close 该 handle。不要构造临时 `McpClientManager`（会注册 shutdownHook，且 `close()` 调用全局 `Schedulers.shutdownNow()`，`McpClientManager.java:302-338`）。
- 流程：保存为 enabled → `mcp/probe/start` → 结果通知 → 关闭一次性 client。Probe 不注册到 `ToolRegistry`。
- 超时：connect 10s + init 60s + list 30s；工作线程总预算 90s；UI 可取消/超时后忽略结果。
- 失败映射：命令不存在、握手超时、401/403、协议版本不匹配、工具列表超时。
- 受限 workspace / disabled server / config 未加载：拒绝 probe，返回 `MCP_RESTRICTED` / `MCP_DISABLED` / `MCP_CONFIG_NOT_LOADED`。
- P1 才考虑“探测未保存草稿”；需要单独的临时 secret 注入和更严格确认。

### 6.8 关闭与孤儿进程

- Engine 退出：`McpClientManager.close()` → 每个 `McpClientService.close()` → `forceDestroyProcessTree`；Windows 用 `taskkill /F /T /PID`，并对已死父进程做 PowerShell 子进程扫描（`McpClientService.java:228-331`）。
- Desktop crash：engine 作为子进程通常随父进程退出；Windows 无 job object 保证，需依赖 engine shutdown hook/启动时清理。P1 可加 Windows Job Object。
- 不手动 kill 单个 MCP 进程；避免 engine 状态与实际进程不一致。P1 `mcp/reconnect` 由 engine 自己关闭/重建 client。

## 7. 安全与权限

### 7.1 威胁模型

| 威胁 | P0 是否覆盖 | 说明 |
|---|---|---|
| 恶意 workspace 通过 `MCP.json` 影响 Desktop | **是（设计）** | Desktop 不加载 workspace 文件；只在用户显式导入时读取，并展示预览 |
| 恶意 workspace 通过 Desktop store 注入 | **是（设计）** | store 在 workspace 外，workspace 无写权限；Desktop 只接受 UI/受控导入 |
| 恶意用户级配置（本机其他进程写入） | 部分 | Desktop store 同用户可写；保存时有确认；同用户恶意软件本身已能执行代码 |
| MCP server 读取本地文件/外发 | **否**（无法保证） | MCP 子进程与 engine 同用户权限；P0 只做审批/确认/最小 secret，P2 才评估沙箱/egress |
| MCP server 读取其他 server 的 secret | **部分** | 按 server 分组的 secret map；子进程不继承其他 server 的变量。同用户进程内存仍有理论风险 |
| Bash/Skill/Hook 读取 MCP secret | **是（设计）** | secret 不进 engine `process.env`；只走 `mcp/secrets` 内存 map |
| 模型通过 `add_mcp_server` 启动任意 MCP | **是（P0 修复）** | Desktop（`--desktop-mcp`）模式不注册该工具；其他模式不变 |
| MCP 工具覆盖非 MCP 工具并继承预允许规则 | **是（P0 修复）** | 两阶段注册；MCP 冲突不注册 |
| MCP 工具提示注入/恶意描述 | 部分 | 描述作为不可信文本渲染；不自动批准；P1 标注来源 |
| Secret 明文落盘/日志 | 是（设计） | safeStorage 密文；配置只放 `${env:}`；日志脱敏 |
| 通过 Test connection 执行未保存命令 | 是（P0 限制） | 只探测已保存 server；保存前有命令确认 |
| 插件/第三方绕过 MCP 配置 | 是（边界） | Plugin MCP 不启用；Hub 不写本地 |

### 7.2 Workspace trust 协调

- 配置在 workspace 外，编辑 MCP **不改 trust 指纹**。
- 保存/启停/删除后：
  1. 写 Desktop store。
  2. 当前 workspace `quiesce`。
  3. 空闲 → `restart + ensureTrustState`；busy → pending。
  4. 若返回 restricted：不发送 config/secrets；UI 显示 “MCP is disabled until this workspace is trusted”。
- 用户点击 “Trust this workspace…” → 现有 trust 确认 → `workspaceSetTrust`（先 quiesce，失败 `resumeIntake`）。
- 外部编辑 workspace 文件仍按现有 trust 逻辑处理；Desktop 不自动信任。
- 不允许 renderer 直接写 trust record；只能通过 Main 的 `workspaceSetTrust`。

### 7.3 命令/参数确认

- 首次保存或修改 stdio `command`/`args`/`env` 名称时，Main 弹原生确认：
  ```
  Run this MCP server?
  command: npx -y @scope/server
  cwd: <workspace>
  env: GITHUB_TOKEN, NODE_ENV
  Only continue if you trust this executable. MCP servers run with your user permissions.
  ```
- 确认记录：存在 MCP store 的 `servers[name].confirmedCommandHash = sha256(command+args+envNames+sorted)`；同一配置再次保存不重复弹窗；命令/参数/环境变量名变化 → 重新确认。**不写 `settings.json`**，避免 MCP 数据污染 Desktop 全局设置。
- 该记录只是 UI 防误操作，不是安全边界；真正的门禁是 workspace trust 与 OS 用户边界。
- HTTP/SSE：确认 URL/host 与 headers **名称**（不显示值）。
- 不在 renderer 里用 `window.confirm` 作为唯一确认；Main 原生 dialog 才是可信 UI。

### 7.4 Secret 注入

见 §4.6。要点：

- 按当前 workspace、当前 server 取变量；不跨 workspace。
- 缺失 → fail closed；Desktop 模式不回退 `process.env`。
- 不把 secret 放进 store、日志、renderer DTO、错误消息、trace。
- `mcp/probe/start` 不接收 secret 值；使用 engine 内存中已加载的 map。
- engine 只把解析后的值放进目标 server 的 `env`/`headers`；Bash/Skill/Hook 不继承。
- P0 限制：同一 server 显式列出的 env var 该 server 可读；不同 server 之间不共享未显式下发的 secret。

### 7.5 工具级审批与危险标记

- 现有审批链：`ServerPermissionLifecycle.beforeTool` → `ToolApprovalRequestEvent` → Desktop approval bar → `session/approve`（`ServerPermissionLifecycle.java:108-136`；`app.ts:5812-5833`）。
- MCP 工具 `needAuth=false`，但仍走通用审批；`full` 策略、`autoApproveAll`、子 agent 会跳过（`ServerPermissionLifecycle.java:178-188`）。
- **Desktop（`--desktop-mcp`）模式不注册 `add_mcp_server`**（`CliAgent.java:298-305`、`AddMcpServerTool.java:86-106`）；否则模型/子 agent 可绕过 store 启动任意 MCP。其他 app-server/CLI 模式保留。
- P0 UI：
  - 工具列表显示工具名、描述、来源 server；描述前缀 `[MCP:server]` 可用于来源提示。
  - 审批 bar 显示 tool name + arguments；P0 可加 `toolType=mcp` 与 server label。
  - 不提供“按 readOnlyHint 自动批准”；annotations 是 server 自报，不能作为安全边界。
- P1：
  - 解析 `annotations.readOnlyHint`/`destructiveHint`/`idempotentHint`，仅作 badge/提示。
  - 每 server 的 tool allow/deny 列表（用户显式配置）；默认仍 ask。
  - 稳定权限规则 id：`mcp:<server>/<tool>`（避免 raw name 冲突）。
- 建议默认 `approvalPolicy=ask`；UI 在 MCP 区块提示 “Full access auto-approves MCP tool calls”。

### 7.6 工具名冲突

**P0 必须修复**：

- 现状：MCP priority=3，`ListToolProvider`=5、`BuiltinToolProvider`=10；`ToolRegistry` 升序 `putIfAbsent` → MCP 先注册，非 MCP 同名被丢弃（`core-ai/src/main/java/ai/core/tool/registry/ToolRegistry.java:99-121`）。
- 风险：CLI 预允许 `read_file`/`grep_file` 等 builtin 名称（`CliAppHelper.java:345-363`）；`WorkspaceAutoPermissionStore` 还会自动放行 `write_file`/`edit_file`/`hash_edit_file`。恶意 MCP server 提供同名工具可继承预允许，绕过审批。
- 方案：
  1. **两阶段注册**：先收集/注册所有非 MCP provider（builtin、list、skill、plugin、remote agent、helper 等），再注册 MCP。MCP 名称与非 MCP 冲突时直接跳过并记录 `conflict`。这不依赖已知 priority 列表，能覆盖未来新增 provider。
  2. 跨 MCP server 同名：按 server name 排序遍历，第一个获胜；`McpToolProvider` 用 `putIfAbsent`，不要用 `map.put` 覆盖；后续记录 `conflict`，UI 标 degraded。
  3. **长工具名**：`>64` 的 MCP 工具名在注册阶段直接拒绝（工具不注册、server 标 degraded），不做 schema/dispatch 双截断映射（`ToolCall.java:193-209`）。
- 影响：MCP 工具不再能覆盖任何非 MCP 工具；这是行为变更，但属于安全修复。列为 U5，建议全局应用；若要求零 CLI 行为变化，可只作用于 Desktop 模式，但不推荐。
- P1 可选：统一前缀 `server__tool`，彻底消除跨 server 冲突，但会改变模型可见名称和权限规则。

### 7.7 文件/网络边界（诚实限制）

- P0 **不能**阻止一个被信任的 MCP server：
  - 读取用户有权读取的任意文件；
  - 访问网络、外发数据；
  - 启动子进程、读取自己的环境变量；
  - 消耗 CPU/内存。
- **已知环境继承**：`EngineSupervisor` 目前用 `{...process.env, ...extraEnv}` spawn engine（`EngineSupervisor.ts:122-123`），所以 MCP 子进程能读到 Desktop 进程的环境变量（可能含用户/系统 token）。本设计只保证 MCP secret 不额外进入 `process.env`，不清洗继承的宿主环境；P1 评估给 engine 最小化 env 允许列表。P0 文档/UI 不宣称“MCP 看不到任何宿主凭据”。
- 这是因为 MCP stdio server 是普通本机进程，与 engine 同用户权限；`McpTransportFactory` 只是启动命令，不是沙箱。
- P0 可做的缓解：
  - Workspace trust + 保存前命令确认 + 原生 dialog；
  - 默认 `ask` 审批；不自动批准 annotations；
  - Secret 不落明文、按 server 最小下发、保留名拒绝；
  - Restricted workspace 完全禁用 MCP；
  - 禁止 MCP 覆盖任何非 MCP 工具；
  - 日志/错误脱敏；
  - UI/文档明确“只安装你信任的 MCP server”。
- P2 才评估：
  - OS 沙箱（Windows AppContainer/Job Object、macOS sandbox-exec、Linux namespaces）；
  - 网络 egress 允许列表/代理；
  - 文件系统只读根 + 显式挂载；
  - secret 轮换/最小权限/按 server 审计；
  - 签名/来源验证。
- 本文不把“用户确认”描述成沙箱或安全证明。

### 7.8 审计

- P0 本地审计：
  - Main 日志：`[mcp] save workspace=<hash> server=name action=create hash=…`（不含 secret）；
  - Engine 日志：`mcp: server=name state=CONNECTED tools=12`、`mcp: server=name failed: <message>`；
  - Tool call 已有 session transcript/usage 审计；MCP 调用沿用现有工具事件。
- 不做 server 侧 hub_calls 审计（本地 MCP 不经过 hub）。
- P1：可选的本地 `mcp-audit.jsonl`（保存/启停/探测/审批），带轮转；不默认上传。

### 7.9 Plugin / Hub / CLI 边界

- Plugin `.mcp.json` 不启用；PluginHost 明确不向 engine 传 MCP 路径（`PluginHost.ts:1-7`）。
- Hub server 只通过 `hub/*` RPC 使用；Desktop 不把 Hub 凭据写入本地 MCP store。
- Server agent 的 ToolRef 由 server 解析；Desktop 远程会话不受本地 MCP 设置影响。
- CLI 不读取 Desktop store；Desktop 不写 CLI 文件。两者只通过用户显式导入交换定义。
- 如果未来启用 plugin MCP，必须复用本设计的 trust/审批/secret 门禁，不能新增绕过路径。

### 7.10 Windows 启动器与命令注入

- 现状：`McpTransportFactory` 在 Windows 对 `npx`/`npm`/`node`/`pnpm`/`yarn`/`bun`/`.cmd`/`.bat` 使用 `cmd.exe /c <command> ...`（`McpTransportFactory.java:55-83`）。`cmd.exe` 会解释 `&`、`|`、`<`、`>`、`^`、`%VAR%`、`!` 等元字符。
- P0 校验（硬拒绝，不提供“确认后放行”）：
  - 所有平台拒绝 args 中的 `\0`、`\r`、`\n`。
  - Windows 上，对会被 `cmd.exe /c` 包装的 command，**command 本体和 args 都**拒绝含 `&`、`|`、`<`、`>`、`^`、`%`、`!`、`"`；不要只检查 args。
  - `${env:NAME}` 展开后要对最终 args 再跑一次同样的校验；如果 secret 值含危险字符，拒绝该 server，不回显 secret。
  - 不尝试用“转义”解决所有 cmd 解析差异；拒绝比错误转义安全。
- 局限：合法的复杂参数（如含 `%` 的 URL 编码）可能被拒绝；P2 评估直接 `CreateProcess`/安全 launcher 或参数文件。
- 原生确认 dialog 必须显示最终 command + args；用户确认不是 shell 注入防护，P0 的拒绝规则才是。

## 8. UX

### 8.1 入口与信息架构

- Settings 增加 “MCP servers” 可折叠区块（复用 `collapsibleSettingsSection`），位置建议在 “Skill SDK” 之后、“Permission rules” 之前。
- 区块头显示：`MCP servers for <workspace name>`；摘要 `3 enabled · 1 disabled · 2 connected`；受限 workspace 显示 `Blocked by workspace trust`。
- 区块体：
  - 操作行：`Add server`、`Import…`、`Export…`、`Refresh status`、`Open logs`。
  - 列表：当前 workspace 的 server。
  - 受限提示：Trust 按钮 + 说明。
  - 固定说明：“These servers are stored by core-ai-desktop for this workspace. CLI uses `.core-ai/MCP.json` separately.”
- **没有作用域切换器**（没有 User/Workspace 下拉）；当前 workspace 就是唯一作用域。
- 不为 MCP 新增一级导航（P0）；如果列表变长，P1 再拆独立页面。

### 8.2 列表行

| 元素 | 内容 | 交互 |
|---|---|---|
| 状态点 | connected / connecting / failed / disabled / blocked / invalid | hover 显示 state + last error 摘要 |
| 名称 | server name | 点击展开详情 |
| 传输 badge | stdio / HTTP / SSE (legacy) | — |
| 工具数 | `12 tools` 或 `—` | 点击打开工具列表 |
| 启用开关 | on/off | 写 Desktop store + 当前 workspace 安全点重启 |
| 操作菜单 | Edit / Test / View logs / Copy JSON / Delete | 危险操作二次确认 |
| 警告 | 冲突/明文 secret/CLI 文件可导入 | 链接到详情 |

- 排序：启用在前，然后按名称。
- 禁用项显示 `disabled`，仍可编辑/删除。
- 列表只反映当前 workspace；切换 workspace 后整页刷新。
- 空状态：`No MCP servers for this workspace. Add one, or import from .core-ai/MCP.json.`

### 8.3 添加/编辑表单

字段分组：

1. **Basics**
   - Name（必填；保存后重命名 = 新 server，提示会丢失状态/secret 关联）
   - Transport（stdio / HTTP / SSE legacy）
   - Description（可选，仅 UI）
2. **stdio**
   - Command（必填；文件选择器可选）
   - Args（多行，每行一个；或 tag 输入）
   - Working directory? P0 不支持自定义 cwd；engine 以 workspace 为 cwd。
   - Environment variables（name/value 行；每行有 “Store as secret” 复选框）
3. **HTTP/SSE**
   - URL（必填）
   - Endpoint（可选）
   - Headers（name/value 行；Authorization 默认 secret）
4. **Advanced**
   - connectTimeout / requestTimeout / initializationTimeout
   - autoReconnect / maxReconnectAttempts / reconnectInterval
   - heartbeat / heartbeatTimeout
   - 默认值折叠，显示 “Defaults: 10s / 60s / 60s”。
5. **Preview**
   - 只读 JSON 预览（secret 显示 `${env:NAME}`，不显示值）。
   - “This server will run with your user permissions.” 固定提示。

校验：

- 失焦/保存时字段级校验；错误显示在字段下方，不用 alert。
- 保存按钮：`Save` / `Save and test` / `Cancel`。
- `Save and test`：
  - 保存为 enabled → 当前 workspace 安全点重启 → `mcp/probe/start` → 结果事件。
  - **busy / restart pending**：只保存，不 probe 旧 engine（旧 engine 还是旧配置）；显示 `Saved. Test will run after the current turn.`，turn 结束/engine 重启后再 probe。
  - **restricted workspace**：只保存定义，不 probe；提示 Trust，用户 Trust 后再试。
  - 失败不回滚已保存配置，但行显示 failed。
- `Save without enabling`：写定义 + `enabled=false`；P0 不允许 probe disabled。
- 未保存离开：提示 discard。

Secret 输入：

- 默认不显示已存值；输入框 placeholder `Stored in OS keychain (••••abcd)`。
- 操作：Replace / Clear / Keep。
- “Store as secret” 勾选后，保存时由 `mcpSave` 内部加密写 secret store；配置文件写 `${env:NAME}`。`mcpSecretSet` 只用于不改配置的独立轮换。
- **强制 secret**：`Authorization` header，以及变量名匹配 `*TOKEN*`/`*KEY*`/`*SECRET*`/`*PASSWORD*` 的 env 字段，P0 不允许明文保存。
- 其他字段允许明文（如 `NODE_ENV=production`），但 UI 标为 “non-secret”；如果用户把疑似 token 粘贴到 args/普通 env，显示警告并建议移到 secret 字段；P0 不对 args 自动改写。
- safeStorage 不可用：secret 输入禁用；提示 “OS keychain is unavailable; secret values cannot be saved”。

命令确认：

- 首次保存/命令变化时，Main 原生 dialog 显示 command/args/env names/cwd，用户确认后才写文件。
- Renderer 的 “Save” 不是安全边界；Main 可以在 dialog 取消时返回 `MCP_COMMAND_NOT_CONFIRMED`。

### 8.4 Test connection 与工具列表

- 行操作 `Test`：仅对已保存、enabled、**且没有 pendingReload/reloadInFlight** 的 server 可用；调用 `mcp/probe/start`，立即返回 `probeId`，结果通过 `mcp:probe` 事件到达。pendingReload/reloadInFlight 时返回 `MCP_RELOAD_PENDING`，不要 probe 旧 engine 的旧配置。
- 结果面板：
  - 进行中：`Connecting…`，可 Cancel；不阻塞会话审批/取消。
  - 成功：`Connected in 1.2s · 12 tools`
  - 工具表格：name、description（截断）、annotations badge（P1）。
  - 失败：错误码 + message + “View logs” + “Edit config”。
  - 超时：显示实际等待时间，建议增大 `initializationTimeout`。
- `Refresh` 重新 probe；同一 server 同时只允许一个 probe；Cancel 时 engine 置取消标记并异步 close/interrupt，迟到结果按 probeId 丢弃。
- 工具列表为空：`Connected, but this server advertises no tools`。
- 工具名冲突：`3 tools were not registered: read_file (conflicts with a non-MCP tool), search (conflicts with server-b)`。

### 8.5 状态与日志

- 状态来源：`mcp/status`（打开 Settings、保存后、手动 Refresh）；P1 推送。
- 行内状态：
  - `connected` 绿点；`connecting` 黄点/旋转；`failed` 红点；`disabled` 灰点；`blocked` 锁图标；`invalid` 警告图标；`retrying` 循环图标。
- 详情展开：state、last error、transport、timeout、source（Desktop store）、last refreshed。
- `View logs` 打开 modal：
  - 读取 engine 日志尾部（默认 200 行，最多 2000 行），可选按 server name 过滤。
  - 显示路径 + “Open log file”。
  - 提示 “Logs may contain workspace data; MCP secrets are not logged by the MCP layer.”
- `Open config`：用系统编辑器打开 Desktop store 文件（不是 workspace `MCP.json`）；Main 校验路径。
- 不提供 UI 内的原始 JSON 编辑器（避免绕过校验）；外部打开是逃生口。

### 8.6 启停、删除、重命名

- 启用/禁用：开关即时写 Desktop store，显示 “Restarting engine…” 或 “Applies after this turn”；不弹确认（可逆）。
- 删除：
  - 确认框显示 name、transport、command/URL，文案：“This removes the definition from this workspace. Running sessions lose its tools after the engine reloads.”
  - 删除定义；可选 “Also delete unused secrets” 复选框（P1；P0 不自动删 secret）。
  - 不写 workspace 文件，不改 trust 指纹。
- 重命名：
  - 视为删除旧 + 新增新；提示工具名/状态/secret 关联会变化。
  - P0 不做原地改名迁移。
- 复制到另一 workspace：
  - P1；复制定义，不复制 secret（目标 workspace 需要重新输入）。
- 批量：P1 支持多选禁用/删除；P0 单行。

### 8.7 Workspace trust 提示

- 受限工作区：MCP 区块顶部 banner：
  - `This workspace is restricted. MCP servers, hooks and skills are not loaded.`
  - `Trust this workspace…` 按钮；点击显示现有 trust 确认。
- 保存/启停 MCP **不会**改变 trust 指纹，不会弹出 “Review and trust”。
- 如果 workspace 只有 `.core-ai/MCP.json`：Desktop 模式指纹排除该文件，因此不会因此 restricted；Desktop 不加载它，只提示可导入。CLI 模式仍把它算作可执行输入，CLI 自己需要 Trust。
- 如果 workspace 因外部编辑（hooks/skills/`agent.properties`）变成 restricted：
  - 下次 engine 启动显示 restricted；MCP 区块显示 `Workspace files changed outside the app. Trust again to load MCP.`
- 禁止：任何 “auto trust external changes” 或 “MCP edits require re-trust” 设置。

### 8.8 新建会话时的继承

- P0：新会话自动继承当前 workspace 的 enabled server；没有逐会话选择。
- 新建会话界面（首条消息创建）不增加 MCP 选择器；在 composer 下方显示小字 `MCP: 3 servers`，点击跳到 Settings。
- 会话运行中新增/禁用 server：安全点重启当前 workspace；当前 turn 不改变工具集；下一 turn 生效。
- 切换 workspace：新会话使用目标 workspace 的配置；原 workspace 的会话/进程不受影响。
- P1：
  - 新建会话面板显示 server 列表 + 勾选（默认继承 workspace 设置）。
  - `session/create` 接受 `mcp` 选择；engine 用 allowlist 过滤 `McpToolProvider`。
  - 会话头部显示 `MCP: github, playwright`，可查看但不能在运行中变更。
- Agent 级：P1 在 Desktop store 的 `agents` 下配置过滤器；P0 只显示 “Agents inherit this workspace's MCP servers”。

### 8.9 错误文案

| 场景 | UI 文案（英文，保持现有界面语言） | 操作 |
|---|---|---|
| 命令不存在 | `Command not found: <command>. Check PATH or install it.` | Edit / View logs |
| 握手超时 | `MCP handshake timed out after <n>s. The server may be slow to start; increase Initialization timeout or check its logs.` | Edit advanced |
| 认证失败 | `Authentication failed (401/403). Update the headers or token.` | Edit |
| Secret 缺失 | `Environment variable <NAME> is not set for this workspace. Add it in MCP settings.` | Open secret editor |
| Secret store 不可用 | `OS keychain is unavailable; secret values cannot be saved.` | Docs |
| 工具冲突 | `<tool> conflicts with a non-MCP tool and was not registered.` | Details |
| 配置非法 | `Invalid MCP configuration in this workspace: <field> — <reason>.` | Edit |
| Store 被外部修改 | `The MCP store changed on disk. Reload before saving.` | Reload / Diff |
| 受限工作区 | `MCP is disabled until this workspace is trusted.` | Trust |
| Probe 不支持 disabled | `Enable this server before testing.` | Enable |
| Engine 不可用 | `The local engine is not running. Start it and try again.` | Retry |
| Engine MCP 未就绪 | `MCP servers are still starting. Retry in a moment.` | Retry |
| Secret map 未就绪 | `MCP secrets are not loaded yet; the engine will retry.` | Retry / View logs |
| 旧 engine | `This engine does not support MCP management. Update core-ai-desktop.` | Update |

### 8.10 可访问性与一致性

- 复用现有 `.settings-section`、`.settings-grid`、`.settings-actions`、`.notice` 样式；不引入新 CSS 框架。
- 所有开关有 label；状态点有 `aria-label`；Transport badge 不是唯一信息来源（文字）。
- 表单键盘可达；错误用 `aria-describedby`。
- 不把 secret 放进 `title`/DOM dataset/日志。
- 列表空状态明确区分“没有配置”和“CLI 有配置但未导入”。

## 9. IPC / API 设计

### 9.1 Renderer ↔ Main

Preload 新增（命名空间 `mcp`，全部 workspace-scoped；**没有 scope 参数**）：

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `mcpList` | `workspace` | `McpListView` | 当前 workspace 的 store 摘要；不含 secret |
| `mcpGet` | `workspace, name` | `McpServerDetail` | 详情；不含 secret 值 |
| `mcpSave` | `workspace, draft, options` | `McpSaveResult` | 校验、命令确认、写 store（含 draft 中的 secret，单事务）；不写 trust；reload 异步 |
| `mcpDelete` | `workspace, name` | `McpMutationResult` | 删除定义；不写 trust；reload 异步 |
| `mcpSetEnabled` | `workspace, name, enabled` | `McpMutationResult` | 写 store；reload 当前 workspace |
| `mcpImport` | `workspace, json, mode` | `McpImportResult` | 解析/冲突/写 store |
| `mcpImportFromWorkspace` | `workspace` | `{preview, source}` | 读取 `.core-ai/MCP.json` 供预览；不写 |
| `mcpImportFromAgentProperties` | `workspace` | `{preview, source}` | 读取 legacy 内联；不写 |
| `mcpExport` | `workspace` | `{json, missingSecrets}` | 不导 secret |
| `mcpTest` | `workspace, name` | `{probeId}` | 只对已保存 enabled server 启动异步 probe |
| `mcpProbeCancel` | `workspace, probeId` | `{ok}` | 取消/忽略 probe |
| `mcpStatus` | `workspace` | `McpStatusView` | engine `mcp/status` 代理；不连接 |
| `mcpLogs` | `workspace, name?, lines?` | `{path, lines}` | 读日志尾部；脱敏/限长 |
| `mcpOpenStore` | `workspace` | `{ok, error?}` | 系统编辑器打开 Desktop store；fixed 白名单 + realpath |
| `mcpSecretStatus` | `workspace, name, envName` | `{hasSecret, hint}` | 单个 env name 的状态 |
| `mcpSecretSet` | `workspace, name, envName, value` | `{ok, hint}` | 加密写；不返回明文 |
| `mcpSecretClear` | `workspace, name, envName` | `{ok}` | 删除密文 |
| `mcpReconnect` | `workspace, name` | `McpMutationResult` | P1；P0 返回 `UNSUPPORTED` |
| `mcpCopyToWorkspace` | `source, target, names` | `McpMutationResult` | P1；不复制 secret |
| `onMcpChanged` | callback | unsubscribe | `{workspace, names}` |
| `onMcpProbe` | callback | unsubscribe | `{workspace, probeId, ok, tools, elapsedMs, error}` |

安全：

- 每个 handler 先 `isMainFrameSender(event)`（现有模式，`main.ts:1698-1701`）。
- **收紧现有 `engine:call`**：它目前没有 main-frame guard、workspace 校验和方法白名单（`main.ts:1427-1436`）。P0 必须：
  - 加 `isMainFrameSender`；
  - `workspace` 必须是已知 workspace；
  - **精确方法名白名单**，不要用 `media/*`/`memory/*`/`permissions/*` 宽通配。例如：允许 `media/models`，拒绝 `media/generate`（MCP 不应触发计费生成）；允许 `memory/list`/`memory/read`/`memory/maintain`（UI 已使用 `memory/maintain`），拒绝 `memory/write`；允许 `permissions/get`，拒绝 `permissions/update`。实现前逐项核对 `app.ts` 的 `call(...)` 调用点，生成显式清单；漏项会破坏现有 UI，宽通配会放大 renderer 权限。
  - **拒绝** `mcp/*`（只允许 `mcpStatus` 等显式包装）、`workspace/trust`、`engine/quiesce`、`host/*`、`shutdown` 等内部/敏感方法。
- `workspace` 必须是已知 workspace（`settings.workspaces`、`lastWorkspace`、默认路径、或当前打开）；不接受任意路径。
- `engine:capabilities` / `engine:restart` 也要 `isMainFrameSender` + 已知 workspace 校验；它们目前没有 guard（`main.ts:1138-1155`）。
- `name` 正则；`envName` 正则/保留名。
- `mcpLogs` 只读 `~/.core-ai/logs/<workspaceBaseName>_<date>.log`（今天；为空回退到最近日期），限制 1 MiB/2000 行；剥 ANSI。
- `mcpOpenStore` 只打开 Desktop store 文件；对目标及其父目录做 `realpath` 校验，拒绝 symlink/junction 逃逸；不打开 workspace `MCP.json`。
- 所有返回值不含 secret；错误消息不拼接配置值。
- `mcpSave` 的 `options.confirmCommand` 不是信任凭据；Main 自己弹原生 dialog。
- **配置 + secret 单事务**：`mcpSave` 接收 draft 里的 secret 值，先在内存加密/写 secret store，再写 config store；失败 best-effort 回滚；不接受“config 已写、secret 未写”的半状态。`mcpSecretSet` 只用于“不改配置、只轮换已有 secret”的场景，不参与新建/改 server 的原子提交。
- 同一 server 的 probe/save 串行化；避免并发写/重复启动。

### 9.2 DTO（脱敏）

```ts
type McpTransport = 'stdio' | 'http' | 'sse';
type McpState = 'connected' | 'connecting' | 'disconnected' | 'reconnecting'
  | 'failed' | 'disabled' | 'blocked' | 'invalid' | 'not_connected' | 'retrying';

interface McpServerView {
  name: string;
  enabled: boolean;
  transport: McpTransport;
  summary: string;            // "npx -y ..." or "https://host/path" — 不含 env 值
  state: McpState;
  toolCount?: number;
  lastError?: { code: string; message: string };
  hasSecrets: boolean;
  unknownFields: string[];
}

interface McpServerDraft {
  name: string;
  transport: McpTransport;
  command?: string;
  args?: string[];
  env?: Array<{ name: string; value?: string; secret?: boolean; keep?: boolean }>;
  url?: string;
  endpoint?: string;
  headers?: Array<{ name: string; value?: string; secret?: boolean; keep?: boolean }>;
  connectTimeout?: number;
  requestTimeout?: number;
  initializationTimeout?: number;
  autoReconnect?: boolean;
  maxReconnectAttempts?: number;
  reconnectInterval?: number;
  heartbeat?: number | boolean;
  heartbeatTimeout?: number;
  description?: string;
  expectedHash?: string;
}
```

- `value` 只在“新建/替换”时由 renderer 发给 main；`keep` 表示保留已存 secret；main 不回传 value。
- `unknownFields` 用于提示“保留但当前 UI 未暴露”。
- `summary` 由 main 生成，必须脱敏（URL 建议显示 origin+path，隐藏 query）。
- DTO 不含 `workspaceKey`/文件路径（避免泄漏本机布局）；需要诊断时由 Main 日志记录 hash。

### 9.3 Main ↔ Engine RPC

Initialize 新增 capability：`mcp_management_v1`，**只在 `--desktop-mcp` 时返回**。Desktop 在 `engineCapabilities` 中读取；**只有 capability 存在时**才调用 MCP RPC。缺失时禁用 MCP 管理 UI，**不调用** `mcp/config`/`mcp/secrets`（否则旧 engine 返回 -32601，`initialize` 失败并 kill）。

**CLI 模式不注册这些方法**：`mcp/config`、`mcp/secrets`、`mcp/finalize`、`mcp/status`、`mcp/probe/*` 只在 Desktop 模式加入 `supportedMethods`；没有 `--desktop-mcp` 时返回 `METHOD_NOT_FOUND`，不成为 CLI 的新能力面。

**trustState 扩展**：`initialize`/`workspace/trust` 返回 `{state, key, workspaceKey, fingerprint, hasExecutableInputs, reason, engineRestricted}`。`key` 是 Desktop 模式的 trust record key（`<workspaceKey>#desktop-mcp`），`workspaceKey` 是 canonical workspace path。`src/shared/workspaceTrust.ts` 的 `WorkspaceTrustState` 需要加 `workspaceKey`；Desktop 的 trust store 仍用 `key`，MCP store 用 `workspaceKey`。

调用顺序与失败语义：

1. `initialize` 成功；Desktop 不因 MCP 失败而 kill engine。
2. `mcp/config`（可空）；失败/部分 invalid 只影响 MCP，不影响会话。**失败也视为“config 已尝试”**，允许后续 `mcp/finalize`。
3. 调用 `mcp/secrets`（可空；config 失败时也传空作为 no-op）；失败只影响引用 secret 的 server。
4. **终态信号**：Desktop **总是**调用 `mcp/finalize`（无论 config/secrets 成功、失败或跳过）；engine 启动 scheduler（仅 trusted）、置 `mcpConfigured`/`mcpReady`，并在 config 已加载但 warmup 未触发时用空 secret map 触发一次异步 warmup。若 Desktop 未发，engine 按“最后一次 MCP RPC 后 10s 静默 / 首次 `initialize` 后 30s”兜底 finalize，MCP 标记 unavailable。
5. engine 异步 warmup；`mcp/status` 显示 connecting；`mcpConfigured` 不等待 warmup。
6. Desktop 标记 MCP ready；会话 driver 线程（**不是 AppServer 分发线程**）在枚举工具前最多等 10s 的 `mcpConfigured`（不是完整 warmup）；MCP 不可用/配置失败则跳过等待，`session/send` 照常执行（无 MCP 工具）。
7. `mcp/config` 首调后不同内容 -> `MCP_CONFIG_ALREADY_LOADED`；`mcp/secrets` 首调后不同 map -> `MCP_SECRETS_ALREADY_LOADED`。两者都要求重启 engine。`mcp/finalize` 幂等。

| RPC | 参数 | 返回 | 说明 |
|---|---|---|---|
| `mcp/config` | `{servers: Record<string, object>}` | `{ok, invalid: [...]}` | 当前 workspace 的 enabled server 定义；空对象合法；逐 server 校验；首调后不同内容返回 `MCP_CONFIG_ALREADY_LOADED`，要求重启 |
| `mcp/secrets` | `{servers: Record<string, Record<string,string>>}` | `{ok, loaded}` | 按 server 分组的 secret map；空对象合法；Desktop **总是调用**（无 secret 时传空）；只存内存；**立即返回并异步 warmup**；config **从未调用**时返回 `MCP_CONFIG_NOT_LOADED`，config 已调用但失败时接受空 map；首调后不同 map 返回 `MCP_SECRETS_ALREADY_LOADED` |
| `mcp/finalize` | `{}` | `{ok, configured}` | **终态信号**：无论 config/secrets 成功/失败/为空都调用；启动 scheduler（仅 trusted）、置 `mcpConfigured`/`mcpReady`；若 config 已加载但 warmup 未触发，用空 secret map 触发异步 warmup；幂等；不等待 warmup |
| `mcp/status` | `{}` | `McpStatusView` | 不主动连接；返回 restricted、ready、states、invalid entries、conflicts、toolCount |
| `mcp/probe/start` | `{name, timeoutMs?}` | `{probeId}` | 立即返回；工作线程执行一次性 `McpClientService`；结果走通知 |
| `mcp/probe/cancel` | `{probeId}` | `{ok}` | 置取消标记并异步 close/interrupt，立即返回；工作线程释放并发槽 |
| `mcp/tools` | `{name}` | `McpToolsView` | P1；P0 用 probe 结果；不要把 listTools 放回同步分发 |
| `mcp/reconnect` | `{name}` | `{state}` | P1；调用 `McpClientManager.reconnect` |

通知：`mcp/probe/result {probeId, ok, tools, elapsedMs, error}`；P1 `mcp/status_changed`。

`mcp/status` 返回示例：

```json
{
  "restricted": false,
  "ready": true,
  "capability": "mcp_management_v1",
  "servers": [
    {
      "name": "github",
      "transport": "stdio",
      "state": "CONNECTED",
      "toolCount": 12,
      "lastError": null
    },
    {
      "name": "broken",
      "transport": "http",
      "state": "INVALID",
      "error": { "code": "MCP_CONFIG_INVALID", "message": "missing url" }
    }
  ],
  "conflicts": [
    { "tool": "search", "servers": ["server-a", "server-b"], "winner": "server-a", "skipped": ["server-b"] }
  ]
}
```

- `state` 由 Main 映射为小写 DTO；不要直接透传内部枚举。
- `ready` 表示 **config/finalize 已完成**，不表示所有 server 已连接；连接状态看每个 server 的 `state`。warmup 可能仍在进行。
- `mcp/status` 不发起连接；`toolCount` 可为 null。
- `mcp/probe/result` 失败也返回结构化结果，不抛 JSON-RPC error（除非参数非法）。
- 超时：`mcp/config`/`mcp/secrets`/`mcp/finalize` 10s；`mcp/status` 5s；`mcp/probe/start` 毫秒级；结果事件 90s 内。

### 9.4 Main 模块

| 模块 | 职责 |
|---|---|
| `src/main/mcp/McpStore.ts` | per-workspace store 读取/校验/原子写/hash/导入/导出；不含 secret |
| `src/main/mcp/McpSecretStore.ts` | safeStorage 加解密；按 workspace + server + env；availability；hint；无明文日志 |
| `src/main/mcp/McpService.ts` | IPC handler；校验；命令确认；safe-point reload；日志尾部；import |
| `src/main/mcp/workspacePaths.ts` | workspaceKey → sha256 文件名；固定路径；realpath 校验 |
| `src/main/main.ts`（`workspace:setTrust`） | 写 trust record 前先 `quiesce`；busy 拒绝；失败/异常 `resumeIntake`；不杀 live turn |
| `src/shared/mcp.ts` | DTO、错误码、transport 常量；preload/renderer/main 共用 |
| `src/shared/workspaceTrust.ts` | `WorkspaceTrustState` 增加 `workspaceKey`；`key` 变为 Desktop 模式 trust record key |
| `src/renderer/mcpView.ts`（可选） | 纯渲染/格式化，避免 app.ts 继续膨胀 |
| `src/main/engine/EngineSupervisor.ts` | 新增 `mcpConfigProvider(workspace, workspaceKey)` 选项（由 `main.ts` 注入 `McpStore`/`McpSecretStore` 的纯读取，**不回调 `McpService` IPC，避免与 `ensureTrustState` 循环**；provider 异常用 try/catch 捕获，发空 config/secrets 并照常 `mcp/finalize`）；spawn 时按 engine 版本/能力追加 `--desktop-mcp`（bundled/dev 直接传；external 读 manifest，不支持则不传）；**不设置环境变量**，避免子进程继承；`initialize` 的 `ready` 屏障内用 provider 取配置，调用 `mcp/config` → **总是 `mcp/secrets`（可空）** → **总是 `mcp/finalize`**（仅 capability 存在时，各 10s 超时）；**MCP RPC 失败/超时只记录并标记 MCP unavailable，不能让 `ready` reject 或 kill engine**；转发 `mcp/probe/result` 为 `mcp:probe` |

现有 `main.ts` 只做 wiring：`whenReady` 创建 `McpService`，注入 `supervisor`、`workspaceTrustStore`、`settings`、reload callback；`registerIpc` 调用 `mcpService.register()`。

### 9.5 多窗口 / 多 workspace

- 现状：单窗口 + 单实例锁（`main.ts:1717-1725`）；`isMainFrameSender` 只允许主窗口主 frame。
- P0：MCP IPC 全部显式带 `workspace`；Main 不依赖“当前 workspace”全局；事件 `mcp:changed` 带 workspace。这样即使以后多窗口，也不需要改协议。
- 多 workspace：
  - 每个 workspace 独立 store、secret、engine、进程。
  - 编辑只重启当前 workspace。
  - 切换 workspace 刷新 UI；不迁移/不合并配置。
- 未来多窗口：
  - 每个窗口在打开时绑定 workspace；`isMainFrameSender` 改为“允许的窗口集合”。
  - 同一 workspace 多窗口共享一个 engine；MCP 状态通过事件广播。
  - 不允许两个窗口同时写同一 store：Main 串行化 + hash 冲突检测。
- 远程会话窗口：不调用本地 `mcp:*` 写接口；只读展示 server agent 的工具。

### 9.6 错误 envelope

```ts
interface McpError {
  code: string;
  message: string;
  field?: string;
  details?: Record<string, string | number | boolean | null>;
}
```

- IPC 返回 `{ok:true,...}` / `{ok:false,error:McpError}`；不向 renderer 抛原始异常栈。
- 错误码稳定，UI 可映射文案；message 可英文，不含 secret。
- `details` 只放安全字段（server name、env name、state、workspace hash），不放 command 全量/路径。

### 9.7 超时与取消

- `mcpSave`：只负责校验 + 写 store/secret，RPC 超时 10s；重启/trust 作为异步阶段，通过事件 `mcp:changed` / `engine:reloaded` 通知。
- `mcp/config` / `mcp/secrets` / `mcp/finalize`：各 10s；超时或失败只标记 MCP unavailable，不阻塞 `initialize`/`ready`，不 kill engine。`mcp/finalize` 失败时 engine 侧 30s 兜底启动 scheduler（覆盖 config+secrets 总窗）。
- `mcpTest`：`mcp/probe/start` 毫秒级返回；结果事件 90s 内到达；超时/取消后 Main 丢弃迟到结果。
- `mcpStatus`：5s；失败显示 “status unavailable”，不阻塞 Settings 其他区块。
- `mcpLogs`：2s；失败返回空 + 路径。
- Engine 重启：复用现有 `quiesce` 3s；busy 延迟，不阻塞 UI；只当前 workspace。

## 10. 失败与可观测性

### 10.1 失败矩阵

| 场景 | 检测点 | 用户可见 | 日志 | 恢复 |
|---|---|---|---|---|
| Store JSON 非法 | Main 读取 | 文件级错误 + 路径（不含内容） | `mcp: store parse failed workspace=<hash>` | 修复/重载；不覆盖 |
| 单 server 缺 command/url | Main 校验 / engine | 行 `invalid` + 字段错误 | `mcp: server=name invalid: ...` | 编辑 |
| 命令不存在 | engine client 创建 | `failed` + `Command not found` | `mcp: server=name failed: command not found` | 安装/改 PATH/改 command |
| 握手超时 | engine initialize | `failed` + timeout 秒数 | `mcp: server=name init timeout after Ns` | 增大 timeout/查看 server 日志 |
| 协议版本不匹配 | engine initialize | `failed` + 协议错误 | SDK error（脱敏） | 升级 server |
| HTTP 401/403 | engine | `failed` + auth | `mcp: server=name auth failed` | 更新 header/secret |
| Secret 缺失 | engine 展开 | `invalid/failed` + 变量名 | `mcp: server=name missing env NAME` | 在对应 workspace 设置 secret |
| Secret map 未送达 | engine / ready 屏障 | MCP 显示 not connected；会话照常（无 MCP 工具） | `mcp: secrets not loaded` | Main 重发 `mcp/secrets` / 重启 |
| `mcp/secrets` 在 config 从未调用时 | engine | `MCP_CONFIG_NOT_LOADED` | `mcp: secrets before config` | Desktop 先发 config；config 失败但已调用则接受空 map |
| MCP 配置失败 | engine / Main | MCP unavailable；会话仍可用 | `mcp: config failed` | 修复配置/重试；不 kill engine |
| 工具冲突（非 MCP） | engine 注册 | `degraded` + 工具名 | `mcp: tool conflict tool=... non-mcp` | 改 server/改工具名 |
| 跨 server 冲突 | engine 注册 | 警告 + winner | `mcp: tool conflict ... winner=...` | 禁用其一 |
| 工具名 >64 | engine 注册 | degraded + 工具名 | `mcp: tool name too long; not registered` | 改工具名 |
| 工具调用超时 | engine requestTimeout | 工具结果失败 | `mcp: call timeout server=...` | 增大 timeout/重试 |
| Server 崩溃 | engine client | `failed`；重连中 | `mcp: server=name exited` | 自动重连/手动重启 |
| 单轮重连耗尽（非认证） | engine | `retrying`（心跳周期会再试） | `mcp: reconnect attempts exhausted; periodic retry` | 修复后自动/手动重试；禁用止损 |
| 认证错误 | engine | `failed (stopped)` | `mcp: permanent failure (auth)` | 更新 secret/header 后重启 |
| Engine 不可用 | Main | `Engine is not running` | Main log | 启动/重试 |
| Probe 时 reload pending | Main | `MCP_RELOAD_PENDING`；test 延后 | Main log | 等 turn 结束/重启后再试 |
| Store 外部修改 | Main hash | 保存冲突 | Main log | Reload/diff |
| Workspace 未信任 | engine initialize | `blocked` + Trust 按钮 | `engine ... trust=restricted` | Trust |
| Probe 失败 | engine | 错误面板 | `mcp: probe server=name failed` | Edit/Retry |
| 旧 engine | capability 缺失 | `engine does not support MCP management` | Main log | 更新 Desktop/引擎 |
| 日志过大 | Main | 截断提示 | — | 打开文件 |

### 10.2 日志位置与内容

- Engine 日志：`~/.core-ai/logs/<workspaceBaseName>_<YYYY-MM-DD>.log`（`CliLogger.java:33-67`）。
  - 注意：日志文件名只用 workspace basename，两个不同路径但同名的 workspace 会共享日志文件。P0 `mcpLogs` 只读尾部并提示“可能包含其他同名工作区日志”；P1 在 `CliLogger` 加 workspace hash 后缀。
- Main 日志：当前只到 stdout/stderr；P0 不新增文件日志，MCP 操作通过 `console.error` 前缀 `[mcp]` 输出。P1 可加 `~/.core-ai/desktop/logs/desktop.log`。
- MCP 子进程 stderr：SDK 默认 muted（`CliLogger.isMuted` 对 `io.modelcontextprotocol.`）；P0 不保证捕获所有 server stderr。P1 可在 `McpClientService` 包一层 stderr ring buffer。
- 脱敏规则：
  - 不打印 `env`/`headers` 值；
  - 不打印 `${env:NAME}` 解析后的值；
  - 不打印 `mcp/config`/`mcp/secrets` 请求/响应；
  - **前置修复 G15**：`McpServerConfig` 解析异常目前会记录原始 `headersStr`/`envStr`（`McpServerConfig.java:166-205`）；P0 改为只记录字段名/长度/错误类型；
  - command/args 可打印，但若包含疑似 token（长随机串、`Bearer`），打印前替换为 `***`；UI 另有完整确认 dialog。
  - 错误消息不回显 HTTP header 值。
- 日志尾部读取：
  - 只读文件末尾 1 MiB，按行取最后 N 行；
  - 剥离 ANSI；
  - 可选按 server name 过滤（MCP 日志行约定包含 `server=<name>`）。
- 轮转：现有日志无轮转；P0 文档提示用户清理；P1 加大小上限/轮转。

### 10.3 事件与状态

- P0：`mcp:changed`（Main → Renderer）在保存/删除/启停/trust 变化后触发；`mcp:probe` 传递异步探测结果；renderer 重新拉 `mcpList`/`mcpStatus`。
- P1：engine 推送 `mcp/status_changed`（state/tool count/conflict），Main 转 `mcp:status`；避免轮询。

## 11. 测试与验收

### 11.1 单元测试（Desktop）

| 测试 | 覆盖 |
|---|---|
| `mcpStore.test.mjs` | 每 workspace 独立文件；读写/校验/哈希冲突；enabled；未知字段保留；导入/导出；大小限制；workspaceKey 校验 |
| `mcpSecretStore.test.mjs` | safeStorage 可用/不可用；按 workspace + server 加密/解密；hint；保留名拒绝；损坏密文；无明文日志 |
| `mcpService.test.mjs` | 校验；命令确认（stub dialog）；save/delete/import/export；错误码；只重启当前 workspace；受限工作区拒绝 |
| `mcpPaths.test.mjs` | sha256 文件名；realpath；symlink/junction 拒绝；不同 workspace 不同路径；**Windows 大小写/尾斜杠/junction 归一到同一 engine key** |

### 11.2 Engine 测试

| 测试 | 覆盖 |
|---|---|
| `DesktopMcpModeTest` | `--desktop-mcp` 时忽略 `mcp.servers.json` / workspace `MCP.json`；无该选项时旧行为不变；**子进程 CLI 不继承该模式** |
| `McpConfigApiTest` | `mcp/config` 逐 server 校验；非法项不阻塞其他；首调后不同配置拒绝；restricted 拒绝 |
| `McpSecretsApiTest` | 按 server 分组；无跨 workspace/scope 回退；Desktop 模式不回退 process.env；缺失 fail closed；首调后不同 map 拒绝；不写日志 |
| `McpProbeApiTest` | 异步 start/result；timeout；disabled/restricted 拒绝；一次性 `McpClientService`；不注册 shutdownHook；**probe 期间 `session/approve`/`cancel` 仍可响应** |
| `McpToolCollisionTest` | MCP 不覆盖任何非 MCP 工具；跨 server 排序 winner；>64 拒绝；冲突记录 |
| `McpStatusApiTest` | 不主动连接；状态映射；invalid entries；conflicts；restricted 空列表 |
| `AddMcpServerDisabledTest` | Desktop 模式不注册 `add_mcp_server`；CLI 模式仍注册 |
| `McpNonBlockingTest` | 慢/坏 MCP 连接时 `tools/list`、`session/create`、helper 构造不阻塞；snapshot 缺失返回空工具；`session/approve`/`cancel` 仍响应 |
| `McpTerminalSignalTest` | `mcp/finalize` 在 config/secrets 失败/超时/为空后仍启动 scheduler（trusted）；`EngineScheduler.start()` 幂等；`mcpReady` 释放；定时任务不被 MCP 拖死 |
| `CliSubprocessNoDesktopModeTest` | Bash/Hook 子进程里的 `core-ai-cli` 不继承 `--desktop-mcp`；CLI 仍按文件加载 MCP |

命令示例：

- `./gradlew :core-ai:test --tests "ai.core.mcp.client.*"`
- `./gradlew :core-ai-cli:test --tests "ai.core.cli.*Mcp*"`
- `cd core-ai-desktop && npm run check && npm test`

### 11.3 集成测试（Main + fake engine）

- 使用 `test/fixtures/fake-engine.mjs` 扩展 `mcp/config`、`mcp/secrets`、`mcp/finalize`、`mcp/status`、`mcp/probe/start` + 结果通知：
  - 返回 connected/failed/invalid/conflict 场景；
  - probe 延迟/超时；
  - trustState 可控。
- 验证：
  - `mcpList` 只返回当前 workspace；
  - `mcpSave` 只写当前 workspace store，不写 CLI 文件；
  - busy 时 pending，turn 结束后重试；
  - `mcp:changed` / `mcp:probe` 事件；
  - renderer 不收到 secret 字段；
  - 受限 workspace 允许保存但拒绝 probe/enable；
  - busy pending 时 `Save and test` 不 probe 旧 engine；
  - 旧 engine（无 capability）不调用 MCP RPC，UI 显示不支持；
  - UI 传 `C:\WS\`、engine key 为 `C:\ws` 时，store 仍定位到同一文件；
  - `mcpConfigProvider` 只读 store，不回调 `McpService`；provider 失败时仍发 `mcp/finalize`，engine 不被 kill；
  - fake engine 拒绝 `--desktop-mcp` 时，supervisor 无参重试一次，MCP UI 标 unsupported。

### 11.4 E2E（真实 engine + fixture MCP server）

建议新增：

- `test/fixtures/fake-mcp-server.mjs`：实现 stdio JSON-RPC `initialize`/`tools/list`/`tools/call`，工具：
  - `echo`（返回参数）；
  - `read_env`（返回指定 env 是否存在，不返回值）；
  - `slow`（延迟）；
  - `crash`（退出）；
  - `conflict_read_file`（故意命名为 `read_file`）。
- E2E 脚本 `.core-ai/tasks/desktop-mcp-config-e2e.mjs`（复用现有 CDP/隔离 home 约定）：
  1. 打开 Settings → MCP servers；空状态。
  2. Add stdio server（fixture），保存 → 原生确认（stub/自动接受）→ 当前 workspace 重启 → Test（异步）→ 5 tools。
  3. 新建会话，发消息让 agent 调用 `echo`，确认 approval bar 出现；批准后结果正确。
  4. 让 agent 尝试调用 `add_mcp_server`：Desktop 模式应返回 tool not found/disabled，不得启动新进程。
  5. 让 agent 通过 Bash 执行 `echo $FAKE_TOKEN`：应看不到 MCP secret。
  6. 禁用 server → engine 重启 → 下一会话无该工具。
  7. 重新启用 → 工具恢复。
  8. 编辑 command → 重新确认 → 旧进程停止/新进程启动。
  9. 删除 → 定义消失，工具消失。
  10. 添加 HTTP server（本地 fixture HTTP MCP）→ connected → 工具。
  11. **隔离**：打开 workspace B，MCP 列表为空；B 的 engine 不加载 A 的 server；A 的进程仍在；A 的 secret 不在 B。
  12. **CLI 隔离**：保存前后 `~/.core-ai/agent.properties`、`~/.core-ai/MCP.json`、`<ws>/.core-ai/MCP.json` 的 hash 不变；CLI 仍按其旧配置加载（或保持空）。
  13. **Trust 隔离**：保存 MCP 前后 `workspace/trust` 指纹一致；不出现 restricted 翻转。
  14. **外部 CLI 文件**：在 workspace 放 `.core-ai/MCP.json` → Desktop 不自动加载；显示 Import；导入后两边独立。
  15. 受限 workspace：启动后 MCP 区块 blocked，进程列表无 fixture 子进程；Trust 后恢复。
  16. Secret：设置 `FAKE_TOKEN`，fixture `read_env` 报告存在；store/日志 grep 不含值；workspace B 的 `read_env` 报告不存在。
  17. 缺失 secret：删除密钥 → server invalid/failed，错误只含变量名。
  18. 冲突：fixture 提供 `read_file` → 该工具不注册，non-MCP `read_file` 正常。
  19. 崩溃：调用 `crash` 工具/杀进程 → 行变 failed，不拖垮 engine；重连/重启恢复。
  20. **其他 workspace 不受影响**：在 A 改 MCP 时，B 的 engine pid 与正在运行的 turn 不变。

### 11.5 恶意配置测试

| 用例 | 期望 |
|---|---|
| `command` 指向不存在文件 | 单 server failed；其他 server 正常；错误可读 |
| `command` 为 `powershell -c ...` 读取 sentinel 文件 | 能执行（P0 不沙箱）→ 测试只验证 UI 明确警告/审批，不宣称阻止 |
| `args` 含明文 token | 保存时警告/强制 secret；若用户坚持，文件含明文但 UI 明确风险 |
| `env` 名为 `PATH` | 保存拒绝 `MCP_SECRET_NAME_RESERVED` |
| `${env:MISSING}` | server invalid；不把占位符传给子进程 |
| `${env:HOST_SECRET}` 指向宿主环境变量 | Desktop 模式不回退 process.env；server 失败；不泄漏宿主值 |
| HTTP header 含换行/CRLF | 保存拒绝 `MCP_HEADER_INVALID` |
| URL 为 `file://` / `javascript:` | 保存拒绝 `MCP_URL_INVALID` |
| 工具名 `read_file` | 不注册；non-MCP `read_file` 保留 |
| 两个 server 同名工具 | 排序 winner；另一个 skipped；UI 告警 |
| 巨型 tools/list（10k） | UI 截断；engine 不崩；有上限 |
| 慢握手（>init timeout） | failed；engine 不卡死；probe 不阻塞 approve/cancel |
| 崩溃循环 | 单轮 3 次后 FAILED，心跳周期仍可能重试；UI 显示 retrying；禁用可止损 |
| 模型调用 `add_mcp_server` | Desktop 模式工具不存在/被禁用；不启动进程 |
| Bash 读取 MCP secret | Desktop 模式读不到；CLI 模式没有 MCP secret map（配置字面量，按现状） |
| 外部改 CLI `MCP.json` | Desktop 不自动加载；不影响 Desktop 状态 |
| 外部改 Desktop store | hash 冲突；不覆盖 |
| Windows args 含 `&`/`%VAR%`/`"` | 硬拒绝；不得拼进 `cmd.exe /c`；展开后的 secret 也复检 |
| 另一个 workspace 的 store 损坏 | 当前 workspace 不受影响 |

### 11.6 迁移测试

- 现有 `.core-ai/MCP.json`（包装/裸 map）不被自动加载；Import 预览正确；导入后 CLI 文件 hash 不变。
- 现有 `~/.core-ai/agent.properties#mcp.servers.json` 不被自动加载；Import 预览正确；原属性不删。
- 根目录 `MCP.json` 不导入；UI 提示。
- 旧 engine（无 `mcp_management_v1`）：Desktop 不调用 MCP RPC，UI 显示不支持；CLI 行为不变。
- 升级/降级：旧 Desktop 不写 store；新 Desktop 可读旧 store（version=1）；无整表失效。
- CLI 回归：同一 `MCP.json` 在 Desktop 有/无 store 时，CLI 加载结果一致。
- **存量信任迁移**：
  - 旧 `<canonical>` 记录、无 MCP 文件：Desktop 首次打开直接 trusted，并写 `#desktop-mcp` 记录。
  - 旧 `<canonical>` 记录、含 `MCP.json` 且旧完整指纹匹配：Desktop 视为 trusted，写新记录；不弹重新 Trust。
  - 旧记录过期/不匹配：Desktop restricted，提示重新 Trust。
  - 旧记录不存在：Desktop restricted（若 workspace 有可执行输入）或 trusted（若无输入）。

### 11.7 验收标准

| ID | 验收 |
|---|---|
| AC1 | 用户可在当前 workspace 添加/编辑/删除/启停 stdio、HTTP、SSE server |
| AC2 | 保存前后 CLI 文件（`agent.properties`、`MCP.json`、`mcp-state.json`）hash 不变；CLI 行为不变 |
| AC3 | 无 secret 明文出现在 store、日志、IPC 响应、trace |
| AC4 | Test connection 能显示连接耗时、工具数量、失败原因，且不阻塞会话审批/取消 |
| AC5 | 修改配置只重启当前 workspace；其他 workspace engine pid/turn 不变 |
| AC6 | 不同 workspace 的 server/secret/进程互相隔离；B 看不到 A 的配置 |
| AC7 | 保存 MCP 前后 Desktop trust 指纹不变；编辑 CLI `MCP.json` 也不改变 Desktop 指纹/不出现 restricted 翻转（CLI 自己的指纹可变） |
| AC8 | Restricted workspace 不启动任何 MCP、不加载 secret、UI 显示 blocked |
| AC9 | 一个非法 server 不影响其他 server；状态可见 |
| AC10 | MCP 工具不能覆盖任何非 MCP 工具；冲突可见；>64 名称被拒绝 |
| AC11 | Desktop 模式 `add_mcp_server` 不可用；模型/子 agent 不能绕过 store |
| AC12 | 缺失 secret fail closed，错误只含变量名；不回退宿主环境 |
| AC13 | CLI 的 `MCP.json` 只能显式导入；Desktop 不自动跟随、不写回 |
| AC14 | 崩溃/超时/HTTP 401 不拖垮 engine；状态与日志可定位 |
| AC15 | 安全测试用例通过；明确记录 P0 无法消除的风险 |
| AC16 | 旧 external engine 降级不崩溃：不带 `--desktop-mcp`，或带参失败后无参重试；UI 明确显示不支持 |
| AC17 | 导入/导出不丢字段、不导出 secret；导出可被 Claude/Cursor 读取 |
| AC18 | 单元/集成/E2E 全部通过；CLI 回归测试通过 |

### 11.8 不在本轮验证范围

- OS 沙箱/网络隔离；
- server hub 的管理写操作；
- 远程会话的 MCP 工具；
- 多窗口；
- plugin `.mcp.json`；
- MCP marketplace/签名。

## 12. 分期与开放决策

### 12.1 分期

| 阶段 | 内容 | 退出条件 |
|---|---|---|
| P0 前置 | Desktop-only engine 分支（`app-server --desktop-mcp`）、`mcp/config`/`mcp/secrets`/`mcp/finalize`/`mcp/status`/异步 probe；逐 server 隔离；工具冲突修复；`add_mcp_server` 禁用；日志脱敏；非阻塞 tool snapshot | 核心单测通过；CLI 旧路径无回归；probe/tools-list 不阻塞分发线程 |
| P0 | per-workspace store + secret store；stdio/HTTP/SSE CRUD；启停；safeStorage；Test；状态/日志；导入/导出；trust 门禁；只重启当前 workspace；文档 | AC1–AC18 通过；CLI 文件/行为不变；跨 workspace 隔离 |
| P1 | 热 reconnect/add/remove；状态推送；annotations badge；每 server tool allow/deny；session/agent 过滤器；复制到其他 workspace；单次重启 trust；多窗口；日志 ring buffer/轮转；secret 轮换/审计 | 运行中变更不重启；过滤器可用；性能可接受 |
| P2 | OS 沙箱/egress；MCP 包签名/来源；marketplace/registry；共享 MCP broker；plugin `.mcp.json`；server hub 自助注册（若产品需要） | 安全评审通过；有真实隔离验证 |

### 12.2 需要用户拍板的点

| ID | 问题 | 选项 | 建议 | 影响 |
|---|---|---|---|---|
| U1 | Desktop MCP 配置是否完全自有、按 workspace 隔离？ | A 自有 store（本设计）；B 与 CLI 共享文件 | A | A 满足 R2/R3/R4；B 会写 CLI/workspace 状态 |
| U2 | CLI `MCP.json` 是否只作为导入来源？ | A 只导入；B 运行时自动加载 | A | B 会让 workspace 文件影响 Desktop，违背隔离 |
| U3 | 受限 workspace 是否阻止 Desktop MCP？ | A 阻止（推荐）；B 允许用户级 MCP | A | B 放松现有安全语义 |
| U4 | 保存 MCP 后是否只重启当前 workspace？ | A 是（推荐）；B 重启所有 | A | B 会打断其他 workspace 的 turn |
| U5 | 工具冲突修复是否全局应用？ | A 全局安全修复；B 仅 Desktop 模式 | A | B 保留 CLI 漏洞；A 改变 CLI 的异常行为（但属安全修复） |
| U6 | P0 是否允许探未保存草稿？ | A 否（只 probe 已保存）；B 是 | A | B 需要 secret 传输/临时 engine，风险高 |
| U7 | Secret 是否走 `mcp/secrets` 内存 map？ | A 是（推荐）；B 注入 process.env | A | B 会让 Bash/Skill/Hook 读取 |
| U8 | SSE 是否 P0 支持？ | A 支持并标 legacy；B 仅 stdio+HTTP | A | B 减少兼容面 |
| U9 | 是否允许打开 Desktop store 原始 JSON？ | A 系统编辑器打开（逃生口）；B 只读预览 | A | B 更安全但无法处理未知字段 |
| U10 | Agent/session 级是否 P0？ | A P1；B P0 | A | P0 所有会话继承 workspace |
| U11 | 导入是否自动删除 CLI 原配置？ | A 不删；B 导入后删除 | A | B 会破坏 CLI |
| U12 | 是否在 P0 加入本地审计文件？ | A 否（日志足够）；B 是 | A | B 增加文件/隐私面 |
| U13 | 是否在 Desktop（`--desktop-mcp`）模式禁用 `add_mcp_server`？ | A 禁用；B 保留 | A | B 可被模型/子 agent 绕过；A 不影响其他 app-server/CLI 模式 |
| U14 | 是否接受 Desktop-only engine 分支（`app-server --desktop-mcp`）？ | A 接受；B 改 CLI 配置链 | A | B 违背 R2；A 需要 engine 版本门控 |
| U15 | 同一 server 在 CLI 和 Desktop 各配一次是否可接受？ | A 接受（隔离代价）；B 做同步 | A | B 会引入共享状态/冲突 |
| U16 | Linux 无 keyring（`basic_text`）时是否允许保存 secret？ | A 允许但强警告；B 拒绝保存 | A | B 更安全但 Linux 无 keyring 用户无法用 secret；A 需要明确“弱加密”提示 |
| U17 | 是否从 Desktop 的 trust 指纹排除 `.core-ai/MCP.json`？ | A 保持现有指纹（外部改 CLI 文件会让 Desktop restricted）；B Desktop 模式排除该文件（推荐） | B | B 需要 mode-specific fingerprint + `<canonical>#desktop-mcp` trust key，并按旧 `<canonical>` 记录做一次性迁移；A 保持安全模型不变，但 CLI 编辑会扰动 Desktop |

### 12.3 风险登记

| 风险 | 级别 | 缓解 | 残余 |
|---|---|---|---|
| Desktop 误写 CLI 文件 | S1 | 代码只允许 Desktop store 路径；文件监控测试；AC2 | 同用户恶意软件可写；不在本设计范围 |
| MCP 覆盖非 MCP 工具导致审批绕过 | S1 | 两阶段注册/保留名；AC10 | 跨 server 同名仍可能困惑；P1 前缀化 |
| `add_mcp_server` 绕过配置/确认 | S1 | Desktop 模式不注册；测试覆盖 | CLI 交互模式仍有该工具；不在本设计承诺内 |
| Probe 同步阻塞 AppServer | S1 | 异步 start/result + 一次性 client；并发 approve 测试 | 需要新的异步任务/通知模型 |
| 临时 manager close 关闭全局 Reactor | S1 | 一次性 `McpClientService`；修复 close 全局副作用 | 其他 close 路径需审计 |
| 编辑 MCP 改变 workspace 状态 | S1 | 配置在 workspace 外；AC7 指纹不变 | 无（设计保证） |
| 一个坏配置禁用全部 MCP | S1 | 逐 server 隔离 + 状态 | 旧 engine 仍有该行为；Desktop 内置新 engine |
| Secret 进入 process.env 被 Bash/Skill 读取 | S1 | 内存 map + `mcp/secrets`；不污染 process.env | 需要延迟 warmup；同用户进程内存仍有理论风险 |
| 跨 workspace 串配置 | S1 | 每 workspace 独立 store/secret/engine；AC6 | 用户手动复制除外 |
| Restricted workspace 忘记 trust 导致 MCP 不加载 | S2 | blocked banner + Trust 按钮 | 用户可能忽略；文案要明确 |
| Secret 被同 workspace 其他 server 读取 | S2 | 只下发目标 server 的 env/headers | 同 server 显式列出的其他变量仍可读 |
| 恶意 MCP 读文件/外发 | S2 | trust + 确认 + 审批；文档不夸大 | P0 无法阻止；P2 沙箱 |
| 用户级 store 被外部篡改 | S2 | 保存确认；启动时 hash/警告 | 同用户恶意软件无法防 |
| Probe 执行命令带来副作用 | S2 | 只 probe 已保存；原生确认；超时 | 仍会执行一次命令 |
| 超长 MCP 工具名 | S3 | >64 直接拒绝注册；server 标 degraded | 旧非 MCP 工具仍可能被截断 |
| 日志增长/同名 workspace 共享日志 | S3 | 尾部读取、文档提示 | P0 无轮转 |
| 旧 engine 不支持 MCP 管理 | S3 | capability 检测 + UI 提示 | 用户需更新 |
| CLI 与 Desktop 同一 server 配两次 | S3 | 导入/导出；文档说明 | 用户可能困惑；P1 模板/复制 |

## 13. Review 记录与修改记录

### 13.1 AI Review

评审方式：本机 `agy` CLI，print / 只读模式，模型 `gemini-4-neon`（评审前 `/usage`：Gemini 周额度 97%、5 小时 86%，无需换池）。评审只读文档与源码，未修改文件。AI 意见不构成用户批准。

| 轮次 | 结论摘要 | 主要 S1 / S2 | 处理 |
|---|---|---|---|
| 第一轮（v0.1） | 暂不可进入实现评审 | `add_mcp_server` 绕过；probe 阻塞 AppServer；workspace trust 自动信任 TOCTOU；工具冲突修复不完整；secret 进 process.env；旧多层/`--config`/旧 CLI 兼容；probe 临时 manager 全局副作用；`engine:call` 越权/日志明文/Windows 注入。 | 修订为 v0.2（共享配置方案）。 |
| 第二轮（v0.2） | 仍暂不可进入实现评审 | 延迟 warmup/`mcp/secrets` 时序；secret 跨 scope 覆盖；Windows 校验后门；旧多层 loader 与 `CliAppHelper` 顺序；trust 重启链；>64 策略；`engine:call` 收紧不足。 | 修订为 v0.2 后续。 |
| 第三轮（v0.2） | 无 S1，条件性可进入实现评审 | `--deferred-mcp-warmup` 会让旧 engine 退出；受限 workspace scheduler 守卫；probe cancel 同步 close；secret 文案/备份 key；trust quiesce 顺序。 | 修订为 v0.3。 |
| 第四轮（v0.3） | 暂不可直接进入实现评审 | 首 turn warmup 等待若回分发线程会阻塞；`mcp/secrets` 缺 capability 守卫；quiesce 异常未 `resumeIntake`；>64/P1 残留。 | 修订为 v0.3 后续。 |
| **用户补充要求（2026-11-26）** | **架构方向调整** | 用户明确：MCP 不能影响 CLI/Desktop 状态；不同 workspace 不同配置。前几版的“共享用户级 `MCP.json` / 四层合并 / `mcp-state.json` / workspace `MCP.json` 写入”全部不再适用。 | **重写为 v0.4：Desktop 自有 per-workspace store；CLI 文件只导入；engine Desktop-only 注入。** |
| 第五轮（v0.4） | 无 S1；4 项 S2 | ① `CORE_AI_DESKTOP_MCP` 被 Bash/Hook 子进程继承污染 CLI；② MCP 失败/跳过导致 scheduler 不启动；③ `tools/list`/helper 构造在分发线程同步阻塞 MCP 连接；④ CLI 不展开 `${env:}` 的事实错误；⑤ workspace key 不统一/离线自锁；⑥ CLI `MCP.json` 影响 Desktop trust；⑦ restricted 保存/probe 矛盾、busy probe 旧配置、事务、setTrust quiesce；⑧ `engine:call` 白名单漏项、Windows command 本体。 | 修订为 v0.5：改用 `--desktop-mcp` 命令行选项 + 版本门控/无参回退；`mcp/finalize` 终态 + scheduler 幂等；Desktop 非阻塞 tool snapshot + 专用 warmup executor；明确 CLI 不展开 `${env:}`；`trustState.workspaceKey` + `#desktop-mcp` trust key + `index.json` 离线读；U17 默认 B（Desktop 指纹排除 CLI MCP 文件）；restricted 允许保存禁 probe；`mcpTest` pending 拒绝；`mcpSave` 单事务；精确 `engine:call` 白名单；Windows command 本体校验。 |
| 第六轮（v0.5） | 无 S1；2 项 S2 + 3 项 S3 | S2：`mcp/secrets` 可选与 warmup 触发脱节、10s 兜底与 RPC 总窗竞态；`#desktop-mcp` 导致存量信任一次性失信。S3：ADR-2 残留“环境变量”措辞/无参重试 stderr 匹配；`memory/maintain` 白名单示例；并发/事务/index/文案细节。 | 修订为 v0.6：`mcp/secrets` 总是调用（可空），`mcp/finalize` 在未 warmup 时用空 map 触发，兜底改 30s；存量 `<canonical>` 记录按旧完整指纹一次性迁移；修正 ADR-2 措辞、stderr 缓冲匹配、`memory/maintain` 示例、`index.json` 初始化 upsert、secret 旧密文快照回滚、`mcpTest` 拒绝 reloadInFlight。 |

**当前结论**：v0.6 已按第六轮 S2/S3 修订，**无已知 S1/S2**；尚未做第七轮独立复核。两条最高约束在文档层面均已给出设计保证（文件/运行/状态隔离；per-workspace store/secret/engine），仍需实现与 E2E 证明。是否进入实现评审、以及 §12.2 的 U1–U17 由用户拍板；不能把 AI 评审当作批准。

### 13.2 待 Review 决策

- U1–U17（§12.2）。
- 优先：U1（自有 per-workspace store）、U2（CLI 文件只导入）、U3（restricted 阻止）、U4（只重启当前 workspace）、U5（工具冲突全局修复）、U7（secret 内存 map）、U13（禁用 `add_mcp_server`）、U15（两端各配一次是否接受）、U16（Linux 弱 keyring）、U17（CLI 文件是否影响 Desktop trust 指纹）。

### 13.3 修改记录

| 日期 | 版本 | 修改 |
|---|---|---|
| 2026-11-26 | v0.1 | 初稿：现状核查、配置/状态/secret 模型、生命周期、安全、UX、IPC、测试、分期 |
| 2026-11-26 | v0.2 | 按前几轮评审修订：禁用 `add_mcp_server`；异步 probe；P0 手动 Review & Trust；两阶段注册；secret 内存 map；loader/`--config` 契约；一次性 `McpClientService`；`engine:call` 白名单；日志脱敏；Windows 硬拒绝；realpath |
| 2026-11-26 | v0.3 | 第四轮修订：首 turn warmup 下沉到会话 driver 线程；`mcp/secrets` 仅 capability 存在时调用；trust quiesce 失败/异常 `resumeIntake`；清理 >64 与 P1/P2 残留 |
| 2026-11-26 | v0.5 | 第五轮修订：`--desktop-mcp` 命令行选项 + 版本门控/无参回退；`mcp/finalize` 终态 + scheduler 幂等；Desktop 非阻塞 tool snapshot + 专用 warmup executor；明确 CLI 不展开 `${env:}`；`trustState.workspaceKey` + `#desktop-mcp` trust key + `index.json`；U17 默认 B；restricted 允许保存禁 probe；`mcpTest` pending 拒绝；`mcpSave` 单事务；精确 `engine:call` 白名单；Windows command 本体校验 |
| 2026-11-26 | v0.6 | 第六轮修订：`mcp/secrets` 总是调用（可空）；`mcp/finalize` 在未 warmup 时用空 map 触发；Engine 兜底改 30s；存量 `<canonical>` trust 记录按旧完整指纹一次性迁移；修正 ADR-2 环境变量措辞、无参重试 stderr 缓冲匹配、`memory/maintain` 白名单示例、`index.json` initialize upsert、secret 旧密文快照回滚、`mcpTest` 拒绝 reloadInFlight |
| 2026-11-26 | v0.4 | **按用户补充要求重写**：Desktop 自有、按 workspace 隔离的 MCP store；不新增共享 `~/.core-ai/MCP.json`、不写 workspace `MCP.json`、不写 `mcp-state.json`；CLI 文件只作导入来源；engine 新增 `app-server --desktop-mcp` 分支与 `mcp/config`/`mcp/secrets` 注入；编辑 MCP 不改 trust 指纹、只重启当前 workspace；无用户级/全局作用域；新增隔离保证与 AC2/AC5/AC6/AC7/AC13 |
| 2026-11-27 | v0.7 | 按用户「行吧 开始实现」进入实现；新增 §14 实现记录（已交付范围、实现期决策、验证证据、剩余缺口） |

## 14. 实现记录（2026-11-27，开发环境）

### 14.1 已交付范围

| 层 | 文件 | 内容 |
|---|---|---|
| core | `mcp/client/McpSecretExpander.java` | `${env:NAME}` 解析、缺失 secret 判定、CR/LF 拒绝 |
| core | `mcp/client/McpCommandSafety.java` | Windows `cmd.exe` 元字符、全平台 `\0`/CR/LF、secret 展开后复检 |
| core | `mcp/client/McpServerConfig.java` | secret 展开入口、日志脱敏、`copyForProbe()` |
| core | `mcp/client/McpClientManager.java` | snapshot 模式（非阻塞 tool snapshot）、`desktop()`、`warmupAsync()`、冲突与非法工具名汇总、**按 server 隔离非法定义** |
| core | `tool/mcp/McpToolCalls.java`、`tool/mcp/McpToolProvider.java` | 基于 snapshot 注册；跨 server 同名按 server 名确定性取胜；非法工具名跳过并上报 |
| core | `tool/registry/ToolRegistry.java` | 两阶段注册：非 MCP 优先，MCP 不得覆盖同名非 MCP 工具 |
| core | `bootstrap/AgentBootstrap.java` | `initialize(boolean deferMcp)` + `configureDeferredMcpClient` |
| cli | `workspace/WorkspaceTrust.java` | CLI/`DESKTOP_MCP` 两种模式；`<canonical>#desktop-mcp` key；旧记录按旧指纹一次性迁移；`workspaceKey()` |
| cli | `appserver/EngineBootstrap.java` | `desktopMcp` 标记、Desktop 模式跳过 workspace `MCP.json`、从 bootstrap 结果剥离 `mcp.servers.json` |
| cli | `appserver/McpApi.java` | `mcp/config`、`mcp/secrets`、`mcp/finalize`、`mcp/status`、`mcp/probe/start|cancel|result`；异步 finalize watchdog（quiet 10s / boot 30s）；一次性 probe service + 90s 超时；只对已保存且启用的 server 探活；`status.rejected` 上报被拒 server |
| cli | `appserver/AppServerEngine.java`、`AppServerCommand.java`、`EngineScheduler.java`、`CliAppHelper.java`、`agent/CliAgent.java` | `--desktop-mcp` 选项、`mcp_management_v1` capability、`mcpApi`、幂等 `start()`、Desktop 模式不注册 `add_mcp_server` |
| desktop | `main/mcp/mcpStore.ts` | 按 workspace 的加密 store（`userData/mcp/workspaces|secrets/<sha256>.json` + `index.json`）、原子写、事务回滚快照、`safeStorage` |
| desktop | `main/mcp/mcpService.ts` | list/save/setEnabled/remove/status/test/import；quiesce + 仅重启当前 workspace（5s × 24 重试）；engine ready 后 `syncWorkspace` |
| desktop | `main/mcp/mcpImport.ts` | 一次性导入预览（全局 `agent.properties`、workspace `agent.properties`、workspace `MCP.json`），copy/skip/rename 三种冲突策略 |
| desktop | `main/main.ts` | MCP 服务装配；`isInternalRpc`；`engine:call`/`engine:capabilities`/`engine:restart`/`workspace:*`/`mcp:*` 的 sender + workspace + 方法白名单校验；`mcp:importPreview`/`mcp:import` |
| desktop | `main/engine/EngineSupervisor.ts` | `desktopMcp` 异步探测后决定是否附加 `--desktop-mcp`；`onReady` 回调 |
| desktop | `preload/preload.ts`、`shared/workspaceTrust.ts` | `mcpList/mcpSave/mcpSetEnabled/mcpRemove/mcpStatus/mcpTest/mcpImportPreview/mcpImport`；`workspaceKey` |
| desktop | `renderer/app.ts` | Settings → 「MCP servers for `<workspace>`」（Skill SDK 之后、Permission rules 之前）：列表、表单、启停、删除、Test connection、复制 JSON、导入预览；`rejected` 与被拒工具展示 |

### 14.2 实现期决策（在 v0.6 默认建议上落地）

- **U5 取全局**：MCP 不得覆盖非 MCP 工具、`>64` 工具名拒绝，对 CLI 与 Desktop 同时生效（安全修复，非配置改动）。CLI 侧因此不再可能出现 MCP 覆盖 `read_file` 等预允许工具。
- **按 server 隔离修正原设计**：原设计「一个非法 server 导致整份配置不可用」。实现改为逐 server 校验，非法项进入 `status.rejected`，其余 server 正常连接；probe 被拒 server 返回其拒绝原因。这是对 §4.2 的偏离，理由与 CLI 旧行为缺陷一致（避免单点错误扩大）。
- **engine 能力探测取代版本门控**：`EngineSupervisor` 通过一次无参 `initialize` 探测 `mcp_management_v1`，而非硬编码版本号；旧 engine 直接隐藏 MCP 设置区。
- **导入是一次性拷贝**：导入仅读取 CLI 文件，桌面端不写回；导入后 CLI 文件字节不变（E2E 断言）。冲突策略 copy/skip/rename 由 UI 选择。
- **P0 不做 per-server 工具包含/排除**：`includeTools`/`excludeTools` 已从 store schema 移除，避免出现「配置了但 engine 不执行」的假能力；per-session allowlist 仍为 P1。
- **导出为复制 JSON**：不写文件、不提供下载路径，避免与 CLI 文件混淆。

### 14.3 验证证据（开发环境）

| 项 | 结果 |
|---|---|
| `:core-ai:compileJava` / `:core-ai:jar` | BUILD SUCCESSFUL |
| `:core-ai-cli:compileJava` / `:core-ai-cli:installDist` | BUILD SUCCESSFUL |
| 核心测试（MCP transport/config） | BUILD SUCCESSFUL，含默认 endpoint 去重、header 大小写覆盖、自定义 endpoint 列工具 |
| Engine E2E（`tmp/mcp-e2e/driver.js`，`--desktop-mcp`） | PASS：`mcp_management_v1` 存在；`add_mcp_server` 不注册；trust key `<ws>#desktop-mcp`；`status.rejected.broken` 隔离生效；`fake` CONNECTED 且 `echo` 出现于 `tools/list`；probe ok |
| Engine E2E（`tmp/mcp-e2e/driver-cli.js`，无 `--desktop-mcp`） | PASS：文件型 MCP（全局 `agent.properties`）仍加载 `echo_plain`；`add_mcp_server` 仍注册；无 `mcp_management_v1` |
| Desktop E2E（`tmp/mcp-desktop-e2e/main.js`，Electron 主进程） | PASS：capability 探测、保存、状态 CONNECTED、probe、禁用/启用/删除、跨 workspace 隔离、导入 copy/skip/rename 且 CLI 文件不变 |
| `core-ai-desktop` `tsc --noEmit` | 通过 |

### 14.4 剩余缺口（发布前）

- P0 未实现：MCP 日志尾部查看（`mcp/status` 暂无日志片段）；粘贴 JSON 导入（当前导入只读取 CLI 文件）；真实 UI 手测与打包（含 Desktop 内置 engine 的版本/能力对齐）。
- P1：per-session allowlist、workspace 级状态点、模板/复制。
- 待验证：Linux `safeStorage` 弱 keyring 行为、旧 engine 提示文案、`mcp/probe/cancel` 的真实取消路径、Desktop 端打包后 `--desktop-mcp` 在 `.bat`/lib 目录启动路径下的表现。

