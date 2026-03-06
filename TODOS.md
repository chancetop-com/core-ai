## 9. 待完善列表

> 对标项目: Claude Code (analysis_claude_code) / OpenCode (opencode)

### P0 — 关键 / 基础

| # | 项目                      | 描述 | 对标来源 | 影响范围 |
|---|-------------------------|------|----------|----------|
| 1 | **WriteTodos 会话恢复时加载**  | `WriteTodosTool.loadTodos()` 已实现但未接入任何恢复流程。会话恢复时待办事项丢失。需要在 TodoLifecycle.beforeAgentRun 或 InProcessAgentSession 中增加加载钩子。 | Claude Code: initialTodos | tool/tools, session |
| 4 | **多层权限模型**              | 当前仅有 `ToolCall.needAuth` 布尔标记 + `ToolPermissionStore` 简单存储。需升级为：三级权限（allow/deny/ask）、按工具+路径模式匹配、权限规则优先级合并、Session 级权限覆写。 | Claude Code: sM() 6层验证链 / OpenCode: PermissionNext 三级模型 | tool, session, agent |
| 5 | **Doom Loop 检测**        | LLM 可能陷入重复调用同一工具的死循环。需检测最近 N 次工具调用是否相同（工具名+参数），触发时中断或请求用户确认。 | OpenCode: processor.ts doom loop 检测 | agent |

### P1 — 重要 / 近期

| # | 项目                  | 描述 | 对标来源 | 影响范围 |
|---|---------------------|------|----------|----------|
| 6 | **Agent 模式切换（Plan/Build）** | 支持至少两种模式：Build（全权限，可执行写操作）和 Plan（只读分析，禁止 edit/write/shell）。CLI 通过快捷键切换，API 通过参数指定。 | Claude Code: PlanMode/EditMode / OpenCode: build↔plan Tab 切换 | agent, cli |
| 7 | **（简单实现）Shell 命令安全分析** | ShellCommandTool 当前无安全检查。需实现：危险命令检测（rm -rf, dd 等）、路径遍历防护、可选的 LLM 辅助安全评估。 | Claude Code: uJ1() LLM 安全分析 / OpenCode: tree-sitter AST 命令解析 | tool/tools |
| 8 | **EditFileTool 模糊匹配** | 当前 EditFileTool 的差异匹配策略单一，LLM 生成的编辑片段经常因空白/缩进差异匹配失败。需实现多级模糊匹配策略（精确→去空白→缩进弹性→上下文感知）。 | OpenCode: edit 工具 9 种模糊匹配策略 | tool/tools |
| 12 | **外部目录保护**          | ShellCommandTool、WriteFileTool 等可操作任意路径，缺少项目目录边界检查。需实现：检测文件路径是否超出项目根目录，超出时请求 external_directory 权限。 | Claude Code: 路径遍历防护 / OpenCode: assertExternalDirectory() | tool/tools, session |
| 17 | **Git 深度集成**       | 增强 Git 操作支持：worktree 支持多会话并行开发、自动检测分支切换、diff 展示、commit 辅助。 | Claude Code: worktree 多会话并行 / OpenCode: Parcel Watcher 监控 .git | tool/tools, session |
| 3 | **实时打断与消息排队**      | Agent 执行期间用户无法中断或排队新消息。CLI 的 AgentSessionRunner 仅支持 Thread.interrupt 粗粒度中断。需实现：用户消息异步排队、Agent loop 中检测排队消息、优雅中断当前 LLM 调用。 | Claude Code: h2A 双重缓冲队列 / OpenCode: AbortController + 消息排队 | agent, cli, session |
### P2 — 增强 / 中期

| # | 项目                           | 描述 | 对标来源 | 影响范围 |
|---|------------------------------|------|----------|----------|
| 13 | **Hooks 插件机制**               | 支持用户配置 shell 命令响应工具调用事件（before/after tool execution），类似 Git hooks。配置文件指定事件→命令映射。 | Claude Code: Hooks 插件 / OpenCode: Plugin 7 hook 点 | agent, lifecycle |
| 14 | **多层配置系统**                   | 当前配置散落在各处。需统一为三级优先级：项目级（.core-ai/）> 用户级（~/.core-ai/）> 默认值。支持动态配置热加载。 | Claude Code: local > project > user / OpenCode: 7 级配置优先级 | 全局 |
| 15 | **Snapshot/Revert 系统**       | Agent 执行文件写操作前缺少状态快照。需实现：每个 LLM step 前后创建 git tree snapshot，支持回退到任意 snapshot，自动 GC 过期快照。 | OpenCode: 独立 git 仓库 snapshot，SessionRevert | tool/tools, session |
| 18 | **文件监控系统**                   | 当前无法感知项目文件在 Agent 执行期间被外部修改。需实现文件变更监控，检测冲突并提醒。 | OpenCode: Parcel Watcher (macOS fs-events) | tool/tools |
| 23 | **沙箱隔离执行** | Shell/Python 工具在系统级沙箱中执行，防止恶意操作。macOS 可用 sandbox-exec，Linux 可用 container/namespace。 | Claude Code: macOS sandbox-exec (gZ0) | tool/tools |
| 25 | **Token 精确计费** | 当前 Token 计数仅用于上下文管理。需区分 input/output/cache_read/cache_write 等类别，支持按模型精确成本计算。 | Claude Code: VE()/HY5() 四类 token / OpenCode: Decimal.js 精确成本 | agent, llm |

---