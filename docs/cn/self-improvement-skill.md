# Self-Improvement Skill 设计文档

本文档介绍 Core-AI 的自我改进技能（Self-Improvement Skill）的架构设计、工作流程和消融实验结果。

## 目录

1. [概述](#概述)
2. [核心设计](#核心设计)
3. [目录结构](#目录结构)
4. [学习记录体系](#学习记录体系)
5. [Hook 驱动流程](#hook-驱动流程)
6. [晋升工作流](#晋升工作流)
7. [技能提取](#技能提取)
8. [消融实验](#消融实验)
9. [多 Agent 适配](#多-agent-适配)

## 概述

Self-Improvement Skill 让 Agent 在日常编码协助过程中**自动捕获、分类和沉淀知识**。通过 Hook 机制在每次交互后注入轻量提醒，引导 Agent 判断是否产生了值得记录的知识，并按结构化格式写入学习日志。高价值的学习成果会被晋升到项目级配置或提取为独立 Skill。

```
用户交互 → Hook 注入提醒 → Agent 自评估 → 写入学习日志
                                              ↓
                                    累积 → 模式识别 → 晋升/提取
```

### 设计目标

- **零干扰**：不中断正常工作流，仅在交互后追加轻量提醒（约 50-100 tokens）
- **低误报**：仅在出现非显而易见的知识时触发记录
- **可晋升**：学习成果可从日志逐步晋升到项目配置、最终提取为独立 Skill
- **多 Agent 兼容**：同一套 Skill 适配 Core-AI、Claude Code、Codex、GitHub Copilot

## 核心设计

### 三类学习记录

| 类型 | 文件 | 触发场景 | ID 前缀 |
|------|------|----------|---------|
| 学习 | `.learnings/LEARNINGS.md` | 用户纠正、知识缺口、非显而易见的解决方案 | `LRN` |
| 错误 | `.learnings/ERRORS.md` | 命令失败、异常、非预期行为 | `ERR` |
| 功能请求 | `.learnings/FEATURE_REQUESTS.md` | 用户要求的新功能 | `FEAT` |

### 条目格式

每条记录使用结构化 ID，格式为 `TYPE-YYYYMMDD-XXX`：

```markdown
### [LRN-20260319-001] pytest fixtures 作用域

- **Priority**: medium
- **Status**: pending
- **Area**: tests
- **Context**: 用户指出 conftest.py 中 session 级 fixture 在单测间泄漏状态
- **Learning**: pytest fixtures 默认为 function 作用域；session 级 fixture 共享状态，
  应仅用于只读资源（如数据库连接池）
- **Action**: 优先使用 function 作用域，仅在有明确理由时使用更宽的作用域
```

### 状态流转

```
pending → in_progress → resolved → promoted → promoted_to_skill
                     ↘ wont_fix
```

## 目录结构

```
.core-ai/skills/self-improvement/
├── SKILL.md                          # 主技能文档（Agent 加载入口）
├── _meta.json                        # 元数据（版本、slug）
├── references/
│   ├── examples.md                   # 各类条目的完整示例
│   ├── hooks-setup.md                # 各 Agent 的 Hook 配置指南
│   └── openclaw-integration.md       # OpenClaw 集成指南
├── assets/
│   ├── SKILL-TEMPLATE.md             # 技能提取模板
│   └── LEARNINGS.md                  # 学习日志模板
├── hooks/
│   └── openclaw/
│       ├── HOOK.md                   # Hook 元数据
│       ├── handler.js                # JS 实现
│       └── handler.ts                # TS 实现
├── scripts/
│   ├── activator.sh                  # UserPromptSubmit hook 脚本
│   ├── error-detector.sh             # PostToolUse hook 脚本
│   └── extract-skill.sh              # 技能提取辅助脚本
└── .learnings/
    ├── LEARNINGS.md                   # 学习日志（运行时写入）
    ├── ERRORS.md                      # 错误日志
    └── FEATURE_REQUESTS.md            # 功能请求日志
```

## 学习记录体系

### 优先级定义

| 优先级 | 含义 | 示例 |
|--------|------|------|
| `critical` | 生产事故、数据丢失风险 | 数据库迁移方向错误 |
| `high` | 阻碍工作流、反复出现 | Docker 构建在 M1 上失败 |
| `medium` | 有用但不紧急的知识 | 测试 fixture 作用域选择 |
| `low` | 偏好性或边缘情况 | import 排序风格 |

### 领域标签

`frontend` / `backend` / `infra` / `tests` / `docs` / `config`

### 检测触发条件

Agent 在以下场景应考虑记录：

1. **用户纠正** — "不对，应该用 X" → 记录为 Learning
2. **命令失败** — 工具返回错误/异常 → 记录为 Error
3. **能力缺口** — 需要查文档才能完成的任务 → 记录为 Learning
4. **更优方案** — 发现之前做法有更好的替代 → 记录为 Learning
5. **过时知识** — 依赖了已废弃的 API → 记录为 Learning
6. **用户请求** — "能不能加个 X 功能" → 记录为 Feature Request

## Hook 驱动流程

### 整体流程

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│  用户输入     │───→│ activator.sh │───→│  Agent 响应   │
│              │    │ (注入提醒)    │    │ (含自评估)    │
└──────────────┘    └──────────────┘    └──────┬───────┘
                                               │
                                               ↓ 工具调用
                                    ┌──────────────────┐
                                    │ error-detector.sh │
                                    │ (检测命令失败)     │
                                    └──────────────────┘
```

### activator.sh — 任务后提醒

**事件**：`UserPromptSubmit`（每次用户发送消息时触发）

输出约 50-100 tokens 的提醒，引导 Agent 自我评估：

- 是否出现了非显而易见的解决方案或绕过方式？
- 是否有可以记录到 `.learnings/` 的项目特定模式？
- 高价值学习是否应提取为独立 Skill？

### error-detector.sh — 错误捕获

**事件**：`PostToolUse`（工具调用完成后触发）

检测工具输出中的错误模式：
- `error:`, `failed`, `command not found`
- `Permission denied`, `Exception`, `Traceback`
- `FATAL`, `panic`, `segfault`

匹配到时输出提醒，建议 Agent 将错误记录到 `.learnings/ERRORS.md`。

### OpenClaw Hook 配置

OpenClaw 使用 `HOOK.md` + handler 文件的方式注册 Hook：

```
~/.openclaw/hooks/self-improvement/
├── HOOK.md          # Hook 元数据（事件、描述）
└── handler.ts       # Hook 处理逻辑
```

Hook 在 `agent:bootstrap` 事件触发，在 workspace 文件注入前将自我改进提醒作为虚拟引导文件注入 Agent 上下文。

```bash
# 安装 Hook
cp -r hooks/openclaw ~/.openclaw/hooks/self-improvement
openclaw hooks enable self-improvement

# 验证
openclaw hooks list
```

### 其他 Agent 的 Hook 配置

各平台使用相同的 shell 脚本，仅配置格式不同：

| Agent | 配置文件 | 事件名 |
|-------|----------|--------|
| Core-AI | `.core-ai/hooks.json` | `UserPromptSubmit` / `PostToolUse` |
| Claude Code | `.claude/settings.json` | `UserPromptSubmit` / `PostToolUse` |
| Codex CLI | `.codex/config.json` | `agent.prompt` / `agent.tool.post` |

## 晋升工作流

以 OpenClaw 为例，学习记录从日志逐步晋升到更高层级的知识存储：

### OpenClaw Workspace 结构

```
~/.openclaw/
├── workspace/
│   ├── AGENTS.md              # 多 Agent 协作模式
│   ├── SOUL.md                # 行为准则和沟通风格
│   ├── TOOLS.md               # 工具能力和使用注意事项
│   ├── MEMORY.md              # 长期记忆（主会话）
│   └── memory/                # 每日记忆文件
│       └── YYYY-MM-DD.md
├── skills/
│   └── self-improving-agent/
│       └── .learnings/        # 学习日志
└── hooks/
    └── self-improvement/
```

### 晋升路径

```
.learnings/LEARNINGS.md            ← 原始记录（所有学习）
        ↓ 值得跨会话记住
MEMORY.md / memory/                ← 长期记忆（通用知识）
        ↓ 可归类到特定领域
SOUL.md                            ← 行为准则
TOOLS.md                           ← 工具知识
AGENTS.md                          ← 多 Agent 协作模式
        ↓ 可独立复用
skills/xxx/SKILL.md                ← 提取为独立 Skill
```

### 关于 MEMORY.md 作为晋升目标的补充

当前 Skill 原始设计中晋升目标为 `SOUL.md`、`TOOLS.md`、`AGENTS.md`，**未包含 `MEMORY.md`**。这里建议将其纳入晋升体系，理由如下：

**现有问题**：`.learnings/` 中的记录不会注入 system prompt，Agent 在后续会话中不会自动看到。晋升到 `SOUL.md`/`TOOLS.md`/`AGENTS.md` 要求学习能明确归类到行为、工具或协作某一领域。但实际中大量学习属于**通用事实性知识**，不适合归入任何一个专项文件，例如：

- "本项目的 CI 在周末会跳过 E2E 测试"
- "用户偏好中文回复，代码注释用英文"
- "staging 环境的数据库每周一凌晨重置"

这类知识如果不晋升，就停留在 `.learnings/` 中被遗忘；如果强行归类到 `SOUL.md` 或 `TOOLS.md`，会污染这些文件的聚焦性。

**建议方案**：将 `MEMORY.md`（及 `memory/` 每日记忆文件）作为**第一级晋升目标**，定位为"通用长期记忆"。它在 OpenClaw 中已自动注入 system prompt，天然适合承接这类知识：

| 层级 | 存储位置 | 是否注入 prompt | 定位 |
|------|----------|----------------|------|
| 0 | `.learnings/` | 否（按需读取） | 原始日志，全量记录 |
| 1 | `MEMORY.md` / `memory/` | 是 | 通用长期记忆，不限领域 |
| 2 | `SOUL.md` / `TOOLS.md` / `AGENTS.md` | 是 | 领域专项知识 |
| 3 | `skills/xxx/SKILL.md` | 部分（渐进式） | 独立可复用技能 |

这使得晋升体系形成完整的梯度：**日志 → 记忆 → 专项 → 技能**。

### 晋升决策树

```
该学习是否值得跨会话记住？
├── 否 → 保留在 .learnings/
└── 是 → 能否明确归类到特定领域？
    ├── 行为/风格 → SOUL.md
    ├── 工具相关 → TOOLS.md
    ├── 协作/工作流 → AGENTS.md
    └── 通用事实/偏好/环境信息 → MEMORY.md
                                    ↓ 反复出现 (2+)
                              提取为独立 Skill
```

### 晋升规则

| 条件 | 晋升目标 | 示例 |
|------|----------|------|
| 通用事实、用户偏好、环境信息 | `MEMORY.md` | "staging 数据库每周一凌晨重置" |
| 行为/沟通风格 | `SOUL.md` | "发现错误时立即承认，不加不必要的免责声明" |
| 工具使用注意事项 | `TOOLS.md` | "git push 前先用 `gh auth status` 检查认证" |
| 多 Agent 协作模式 | `AGENTS.md` | "API 客户端变更后需通知其他会话重新生成" |
| 反复出现 (2+ 相关条目) | 独立 Skill | Docker M1 构建修复方案集 |

### 晋升格式示例

**原始学习条目**：
> Git push to GitHub fails without auth configured - triggers desktop prompt

**晋升到 MEMORY.md**（通用记忆）：
```markdown
- GitHub push 需要先配置认证，否则会触发桌面弹窗中断流程
```

**晋升到 TOOLS.md**（工具专项）：
```markdown
## Git
- Don't push without confirming auth is configured
- Use `gh auth status` to check GitHub CLI auth
```

### 跨会话知识共享

OpenClaw 提供会话间通信工具，用于跨会话传播学习成果：

| 工具 | 用途 | 示例 |
|------|------|------|
| `sessions_list` | 查看活跃会话 | `sessions_list(activeMinutes=30)` |
| `sessions_history` | 读取其他会话记录 | `sessions_history(sessionKey="id", limit=50)` |
| `sessions_send` | 向其他会话发送消息 | `sessions_send(sessionKey="id", message="Learning: API requires X-Header")` |
| `sessions_spawn` | 生成后台子 Agent | `sessions_spawn(task="Research X", label="research")` |

### 循环模式检测

当同一领域出现 2 个以上相关条目时，Agent 应：

1. 将相关条目标记为 `promoted`
2. 根据决策树合并到对应的 workspace 文件（SOUL.md / TOOLS.md / AGENTS.md）
3. 如果足够丰富，提取为独立 Skill

## 技能提取

### 提取条件

满足以下任一条件即可考虑提取：

- **反复出现**：2+ 个相关的已解决条目
- **已验证**：状态为 `resolved`，方案经过确认
- **非显而易见**：不是常识性知识
- **广泛适用**：可跨项目复用
- **用户标记**：用户明确要求 "记住这个"

### extract-skill.sh 辅助脚本

```bash
# 从学习条目提取 Skill
.core-ai/skills/self-improvement/scripts/extract-skill.sh docker-m1-fixes

# 预览模式（不写入文件）
.core-ai/skills/self-improvement/scripts/extract-skill.sh --dry-run docker-m1-fixes
```

脚本会创建标准 Skill 目录结构：

```
.core-ai/skills/docker-m1-fixes/
├── SKILL.md        # 从模板生成，包含元数据和指令
└── references/     # 参考文档目录
```

## 消融实验

### 实验设计

研究问题：**UserPromptSubmit Hook 是否能有效提升 Agent 自动触发自我改进技能的概率？**

实验设置：
- **对照组**：无 Hook，仅依赖 Agent 自身判断
- **实验组**：启用 activator.sh Hook 注入提醒
- 每个场景运行 10 次，取触发率均值

### 测试场景

**真阳性场景**（应当触发）：

| 编号 | 场景 | 描述 |
|------|------|------|
| T1 | 命令意外失败 | Gradle 代理 HTTPS 配置问题 |
| T2 | 用户纠正 Agent | 错误的 `@Transactional` 使用 |
| T3 | 能力缺口 | Jacoco 覆盖率阈值配置 |
| T4 | 外部 API 失败 | OpenAI 429 错误误处理 |
| T5 | 过时知识 | 使用已废弃的 `java.util.Date` |
| T6 | 发现更优方案 | ObjectMapper 应注入为 Bean |

**假阳性场景**（不应触发）：

| 编号 | 场景 | 描述 |
|------|------|------|
| FP1-FP6 | 常规查询 | 代码审查、重构、文件读取等日常操作 |

### 实验结果

| 指标 | 对照组（无 Hook） | 实验组（有 Hook） |
|------|-------------------|-------------------|
| 真阳性触发率 | 27% | **67%** |
| 假阳性触发率 | 0% | 0% |
| 平均 Token 消耗（触发时） | 48K | 48K |
| 平均 Token 消耗（未触发） | 8K | 8K |

### 关键发现

1. **强信号无需 Hook**：用户直接纠正（T2）在两组中都能被 Agent 捕获
2. **弱信号显著受益**：命令失败（T1）、能力缺口（T3）等场景，Hook 将触发率从接近 0 提升到 60%+
3. **零误报**：两组均未对常规查询产生误触发
4. **Token 开销**：触发时约 48K tokens（含读取 SKILL.md），未触发时仅 8K；Hook 注入本身约 50-100 tokens

### 结论

Hook 提醒机制是自我改进技能的关键驱动力。没有 Hook 时，Agent 仅在最明显的场景（如用户直接纠正）下触发记录；有 Hook 时，更微妙的学习机会（错误、缺口、更优方案）也能被有效捕获。

## 多 Agent 适配

Self-Improvement Skill 以 OpenClaw 为基准平台设计，通过标准化的文件格式和可配置的 Hook 机制适配其他 Agent：

| Agent | Hook 机制 | 晋升目标文件 |
|-------|-----------|-------------|
| OpenClaw | `HOOK.md` + handler | `SOUL.md` / `TOOLS.md` / `AGENTS.md` |
| Claude Code | `.claude/settings.json` | `CLAUDE.md` |
| Core-AI | `.core-ai/hooks.json` | `instructions.md` / `memory/` |
| Codex CLI | `.codex/config.json` | 项目配置文件 |
| GitHub Copilot | 不支持 Hook | `copilot-instructions.md` |

### 适配要点

- **OpenClaw 独有优势**：workspace 注入（SOUL.md / TOOLS.md / AGENTS.md）提供最细粒度的知识分类，跨会话通信工具支持学习成果实时传播
- **Hook 脚本通用**：`activator.sh` 和 `error-detector.sh` 通过环境变量读取输入，各平台只需映射对应变量名
- **学习文件通用**：`.learnings/` 目录和条目格式与平台无关
- **晋升粒度差异**：OpenClaw 支持四层晋升目标（SOUL / TOOLS / AGENTS / Skill），其他平台通常只有两层（项目配置 / Skill）
