# Core-AI CLI 配置体系增强计划

## 1. 现状对比

### Core-AI CLI vs Claude Code 配置体系对比矩阵

| 配置能力 | Claude Code | Core-AI CLI | 状态 |
|----------|-------------|-------------|------|
| **项目指令** | `CLAUDE.md` (多层级继承) | `.core-ai/instructions.md` (单文件) | ⚠ 基础 |
| **模块规则** | `.claude/rules/*.md` (glob 路径匹配) | 无 | ✗ 缺失 |
| **用户全局指令** | `~/.claude/CLAUDE.md` | 无 | ✗ 缺失 |
| **本地指令** | `CLAUDE.local.md` (自动 gitignore) | 无 | ✗ 缺失 |
| **Auto Memory** | `memory/MEMORY.md` (自动积累跨会话经验) | `MemoryProvider` (仅手动保存) | ⚠ 基础 |
| **Settings 层级** | managed > CLI > local > project > user | `agent.properties` 单文件 | ⚠ 基础 |
| **权限系统** | allow/deny/ask 三级细粒度 | `ToolPermissionStore` 二值判断 | ⚠ 基础 |
| **MCP 配置** | `.mcp.json` 多层级 + 热加载 | 代码注册，无持久化 | ⚠ 基础 |
| **Skill 系统** | frontmatter + 资源 + fork + 工具限制 | frontmatter + 资源 (工具限制未执行) | ⚠ 部分 |
| **Hooks 系统** | 17+ 事件类型，4 种 Hook 类型 | `AbstractLifecycle` 6 个阶段 | ⚠ 基础 |
| **Plugin 系统** | 完整 (市场 + 安装 + 版本管理) | 无 | ✗ 缺失 |
| **Agent 定义** | `.claude/agents/*.md` 声明式 | 硬编码 system prompt | ✗ 缺失 |
| **Rules 引擎** | 路径匹配规则按需加载 | `Termination` 接口 (仅终止条件) | ✗ 缺失 |
| **Slash 命令扩展** | 插件可注册自定义命令 | 硬编码命令列表 | ⚠ 基础 |
| **上下文管理** | compaction + `/context` 查看 | `/compact` 基础压缩 | ⚠ 基础 |

---

## 2. 现有架构分析

### 2.1 System Prompt 构建 (CliAgent.java)

当前 system prompt 由以下部分组成:

```
[硬编码] "You are a helpful AI coding assistant."
[动态]   <workspace> 工作目录信息 </workspace>
[可选]   <project-instructions> .core-ai/instructions.md </project-instructions>
[可选]   <memory> MemoryProvider.load() </memory>
```

**问题:**
- 智能体身份只有一句话，缺少职责边界和行为规范
- 无法按模块注入上下文相关的规则
- 指令来源单一，不支持层级覆盖

### 2.2 Memory 系统 (MemoryProvider)

当前实现:
- `MemoryProvider` 接口: `load()`, `save(content)`, `remove(keyword)`
- `LocalFileMemoryProvider`: 基于文件的存储
- 仅在用户显式请求时保存，无自动积累

**问题:**
- 不会自动积累跨会话经验
- 无主题分类组织能力
- 无记忆容量管理

### 2.3 Skill 系统 (SkillLoader)

当前实现:
- 两级来源: workspace (`.core-ai/skills/`) + user (`~/.core-ai/skills/`)
- YAML frontmatter 解析: name, description, license, allowed-tools
- `SkillTool` 按需加载 skill 内容
- `ManageSkillTool` 支持 create/delete/list

**问题:**
- `allowed-tools` 字段已解析但未执行限制
- 无 `context: fork` 子代理执行支持
- 无 skill 参数传递 (`$ARGUMENTS`)
- 无 skill 启用/禁用控制

### 2.4 Hooks/Lifecycle 系统 (AbstractLifecycle)

当前生命周期阶段:
- `beforeAgentBuild` / `afterAgentBuild`
- `beforeModel` / `afterModel`
- `beforeTool` / `afterTool`
- `beforeAgentRun` / `afterAgentRun` / `afterAgentFailed`

**问题:**
- 只能通过代码注册，不支持配置文件定义
- 无事件拦截/阻断能力 (`beforeTool` 有 TODO 标记)
- 缺少会话级事件: SessionStart, UserPromptSubmit, PreCompact

### 2.5 MCP 集成

当前实现:
- `McpClientManagerRegistry` 全局单例
- `AddMcpServerTool` 支持运行时添加
- `CliAgent.configureMcp()` 启动时加载

**问题:**
- 运行时添加的 MCP server 不会持久化
- 无配置文件 (`.core-ai/mcp.json`)
- 无自动重连机制

---

## 3. 增强计划

### Phase 1: 指令层级 + Agent 定义 + Auto Memory (P0)

> 目标: 直接提升智能体对项目的理解深度

#### 3.1 指令层级体系

**目标目录结构:**

```
~/.core-ai/
├── instructions.md              # 用户全局指令 (所有项目共享)
└── skills/                      # 用户全局技能 (已支持)

<workspace>/
├── .core-ai/
│   ├── instructions.md          # 项目指令 (已支持, 团队共享)
│   ├── instructions.local.md    # 本地指令 (gitignore, 个人)
│   ├── rules/                   # 模块级规则 (新增)
│   │   ├── service-layer.md
│   │   ├── api-design.md
│   │   └── testing.md
│   ├── agents/                  # Agent 定义 (新增)
│   │   └── default.md
│   ├── skills/                  # 项目技能 (已支持)
│   └── mcp.json                 # MCP 配置 (新增)
```

**指令加载优先级 (高 → 低):**

1. `instructions.local.md` (个人本地, 不入库)
2. `instructions.md` (项目级, 入库共享)
3. `~/.core-ai/instructions.md` (用户全局)

**实现要点:**
- 修改 `CliAgent.loadProjectInstructions()` 支持多文件加载和合并
- `instructions.local.md` 自动加入 `.gitignore`
- 支持 `@path/to/file` 引用语法

#### 3.2 Rules 引擎

**Rule 文件格式:**

```yaml
# .core-ai/rules/service-layer.md
---
globs: "**/service/**/*.java"
---
Service layer conventions:
- Use @Transactional annotation for write operations
- Return Result<T> wrapper for all public methods
- Throw BusinessException for business logic errors
- Log entry and exit for important operations
```

**实现要点:**
- 新建 `RuleLoader` 类，扫描 `.core-ai/rules/` 目录
- 解析 YAML frontmatter 的 `globs` 字段
- 在 `beforeTool` 生命周期中，当工具操作匹配文件时注入规则到上下文
- 无 `globs` 的规则在会话启动时无条件加载

#### 3.3 Agent 声明式定义

**Agent 文件格式:**

```yaml
# .core-ai/agents/default.md
---
name: core-ai-assistant
description: Java project development assistant
model: anthropic/claude-sonnet-4.6
temperature: 0.8
tools:
  - Read
  - Edit
  - Bash
  - Grep
---
You are a senior Java developer specialized in Spring Boot applications.

## Responsibilities
- Code implementation following project conventions
- Bug diagnosis and resolution
- Code review and quality assurance

## Behavior Guidelines
- Always read existing code before suggesting modifications
- Prefer editing over creating new files
- Run tests after code changes
- Use English for all code comments and commit messages
```

**实现要点:**
- 新建 `AgentDefinitionLoader` 类
- `CliAgent.buildSystemPrompt()` 从 agent 定义文件加载 system prompt
- 支持 `--agent <name>` 命令行参数选择 agent
- 默认使用 `default.md`，不存在时回退到硬编码 prompt

#### 3.4 Auto Memory 增强

**增强方向:**
- 在 `afterTool` 生命周期中，检测构建命令、调试方案等值得记忆的信息
- 自动写入 `~/.core-ai/projects/<project>/memory/MEMORY.md`
- 会话启动时自动加载 MEMORY.md 前 200 行到上下文
- 支持主题文件: `debugging.md`, `patterns.md` 等

**实现要点:**
- 扩展 `MemoryProvider` 接口，增加 `autoSave(topic, content)` 方法
- 新建 `AutoMemoryLifecycle` 生命周期，在关键阶段提取经验
- 新建 `MemoryOrganizer` 按主题组织记忆文件

---

### Phase 2: Rules 引擎 + Hooks 扩展 + MCP 持久化 (P1)

> 目标: 提升可扩展性和自动化能力

#### 3.5 Hooks 配置化

**目标:** 支持通过配置文件定义 hooks，不需要改代码。

**配置格式:**

```json
// .core-ai/hooks.json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "type": "command",
        "command": "echo 'Bash tool invoked'"
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit",
        "type": "command",
        "command": "./scripts/lint-check.sh"
      }
    ],
    "SessionStart": [
      {
        "type": "command",
        "command": "echo 'Session started at $(date)'"
      }
    ]
  }
}
```

**新增事件类型:**
- `SessionStart` / `SessionEnd`
- `UserPromptSubmit`
- `PreCompact`

**实现要点:**
- 新建 `HookConfig` 和 `HookRunner`
- 扩展 `AbstractLifecycle` 添加 `beforeTool` 拦截能力 (返回 allow/block)
- 在 `AgentBuilder` 中注册 `HookLifecycle`

#### 3.6 MCP 配置持久化

**配置文件格式:**

```json
// .core-ai/mcp.json (项目级)
{
  "mcpServers": {
    "neo4j": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@neo4j-contrib/mcp-neo4j"],
      "env": {
        "NEO4J_URI": "bolt://localhost:7687"
      }
    }
  }
}
```

**加载层级:**
1. `.core-ai/mcp.json` (项目级)
2. `~/.core-ai/mcp.json` (用户级)

**实现要点:**
- 新建 `McpConfigLoader` 读取配置文件
- 启动时自动连接配置的 MCP servers
- `AddMcpServerTool` 增加 `--save` 参数持久化到配置文件

#### 3.7 权限增强

**目标格式:**

```json
// .core-ai/settings.json
{
  "permissions": {
    "allow": ["Bash(./gradlew *)", "Read(src/**)"],
    "deny": ["Bash(rm -rf *)", "Read(.env)"],
    "ask": ["Bash(curl *)"]
  }
}
```

**实现要点:**
- 扩展 `ToolPermissionStore` 支持三级权限
- 支持 glob 匹配工具参数
- deny > ask > allow 优先级

---

### Phase 3: 命令插件化 + Settings 层级 + Plugin 系统 (P2)

> 目标: 完善生态

#### 3.8 Slash 命令插件化

**从文件自动注册命令:**

```yaml
# .core-ai/commands/review.md
---
name: review
description: Code review current changes
---
Review all staged changes and provide feedback on:
- Code quality and readability
- Potential bugs
- Performance concerns
- Security issues
```

**实现要点:**
- 新建 `CommandLoader` 扫描 `.core-ai/commands/` 目录
- 修改 `SlashCommandRegistry` 支持动态注册
- 支持命令参数 (`$ARGUMENTS`)

#### 3.9 Settings 层级

**文件层级:**

```
~/.core-ai/settings.json              # 用户级
.core-ai/settings.json                # 项目级 (团队共享)
.core-ai/settings.local.json          # 项目级 (个人本地)
```

**合并策略:**
- 对象: 深度合并，更高优先级覆盖
- 数组: 合并去重
- 优先级: local > project > user

#### 3.10 Plugin 系统基础

**Plugin 结构:**

```
my-plugin/
├── plugin.json             # manifest
├── skills/                 # 技能
├── commands/               # 命令
├── hooks.json              # 钩子
└── mcp.json                # MCP 服务器
```

**实现要点:**
- 定义 `plugin.json` manifest 格式
- 支持 `--plugin-dir <path>` 本地插件加载
- 插件内技能命名空间: `/plugin-name:skill-name`

---

## 4. 实施路线图

```
Phase 1 (P0) — 核心增强
├── 3.1 指令层级体系
│   ├── 多文件加载合并
│   ├── instructions.local.md 支持
│   └── 用户全局指令
├── 3.2 Rules 引擎
│   ├── RuleLoader
│   └── glob 匹配 + 按需注入
├── 3.3 Agent 声明式定义
│   ├── AgentDefinitionLoader
│   └── --agent 命令行参数
└── 3.4 Auto Memory 增强
    ├── 自动经验提取
    └── 主题组织

Phase 2 (P1) — 扩展能力
├── 3.5 Hooks 配置化
├── 3.6 MCP 配置持久化
└── 3.7 权限增强

Phase 3 (P2) — 生态完善
├── 3.8 Slash 命令插件化
├── 3.9 Settings 层级
└── 3.10 Plugin 系统基础
```

## 5. 关键设计原则

1. **约定优于配置** — 合理的默认值，零配置即可工作
2. **渐进式增强** — 每个 Phase 独立可用，不阻塞后续
3. **文件即配置** — 所有配置用 markdown/json/yaml，可版本控制
4. **向后兼容** — 现有 `.core-ai/instructions.md` 和 skills 继续工作
5. **最小侵入** — 优先扩展现有接口，避免大规模重构
