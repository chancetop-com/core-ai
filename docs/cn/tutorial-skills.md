# 教程：Skills 系统

本教程介绍 Core-AI 的 Skills 系统，用于将领域专家知识模块化封装为可复用的技能包。

## 目录

1. [概述](#概述)
2. [核心概念](#核心概念)
3. [创建 Skill](#创建-skill)
4. [与 Agent 集成](#与-agent-集成)
5. [多来源优先级](#多来源优先级)
6. [架构设计](#架构设计)
7. [最佳实践](#最佳实践)
8. [API 参考](#api-参考)

## 概述

Skills 系统允许你将领域专家知识（工作流、最佳实践、工具编排策略）封装为独立的、可复用的 Skill 包。Skills 使用**渐进式披露**模式向系统提示词注入元数据——只在提示词中展示名称和描述，完整指令通过 `ReadFileTool` 按需加载。

```
┌─────────────────────────────────────────────────────────────┐
│                     Skills 系统架构                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  核心能力：                                                  │
│  • 领域知识的模块化封装                                       │
│  • 渐进式披露（节省 token）                                   │
│  • 多来源优先级覆盖                                          │
│  • 基于 Lifecycle 的无侵入集成                                │
│                                                             │
│  关键理念：                                                  │
│  Skill ≠ 可执行插件                                          │
│  Skill = 通过 Prompt 注入的结构化专家知识                      │
│                                                             │
│  集成方式：AbstractLifecycle（不改变 Agent 核心）              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 为什么需要 Skills？

| 问题 | 解决方案 |
|------|----------|
| 处理多领域任务时 System Prompt 膨胀 | Skills 只注入名称+描述（约 100 tokens），完整指令按需加载 |
| 领域工作流难以跨 Agent 复用 | Skills 是自包含的知识包，可跨项目共享 |
| 工具提供"做什么"但缺少"怎么做"的指导 | Skills 教会 Agent 编排工具的最佳实践 |

## 核心概念

### 什么是 Skill？

Skill 是一个**自包含的知识包**，由以下部分组成：

```
my-skill/
├── SKILL.md              # 必需：YAML 元数据 + Markdown 指令
├── scripts/              # 可选：辅助脚本
├── references/           # 可选：参考文档
└── assets/               # 可选：模板、配置等资源
```

### SKILL.md 格式

每个 Skill 目录必须包含一个带有 YAML frontmatter 的 `SKILL.md` 文件：

```markdown
---
name: web-research
description: 提供结构化的 Web 研究方法，支持多源信息采集与合成
license: MIT
compatibility: 需要 web_search 和 write_file 工具
metadata:
  author: core-ai-team
  version: "1.0"
allowed-tools: ShellCommandTool WebSearchTool WriteFileTool
---

# Web Research Skill

## 何时使用
- 用户要求对某个主题做深度调研
- 需要从多个信息源采集并合成内容

## 工作流程

### 步骤 1：制定研究计划
确定需要研究的主题关键方面。

### 步骤 2：搜索与采集
使用 web_search 查找相关信息源。

### 步骤 3：合成研究成果
将发现的信息整合为结构化报告。
```

### Skill 命名规则

| 规则 | 示例 |
|------|------|
| 最长 64 字符 | `web-research` |
| 仅允许小写字母、数字、连字符 | `arxiv-search-v2` |
| 不能以连字符开头或结尾 | ~~`-bad-name`~~ |
| 不能有连续连字符 | ~~`web--research`~~ |
| 必须与父目录名一致 | `web-research/SKILL.md` 中 name 必须是 `web-research` |

### 渐进式披露流程

```
┌──────────────────────────────────────────────────────┐
│                    System Prompt                      │
│                                                      │
│  ## Skills                                           │
│  - web-research: 提供结构化的 Web 研究方法...          │
│    → 读取 `/skills/web-research/SKILL.md`             │
│  - code-review: 提供代码审查检查清单...                │
│    → 读取 `/skills/code-review/SKILL.md`              │
│                                                      │
│  （仅元数据，约 100 tokens）                           │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼ 用户请求匹配 "web-research"
┌──────────────────────────────────────────────────────┐
│  Agent 通过 ReadFileTool 读取完整 SKILL.md             │
│  （按需加载，约 2000 tokens）                          │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│  Agent 按照 SKILL.md 中的工作流执行任务                 │
│  使用已有的 ToolCall 完成具体操作                       │
└──────────────────────────────────────────────────────┘
```

## 创建 Skill

### 步骤 1：创建目录结构

```bash
mkdir -p ~/.core-ai/skills/code-review
```

### 步骤 2：编写 SKILL.md

```markdown
---
name: code-review
description: 提供结构化的代码审查流程与安全、质量检查清单
license: MIT
metadata:
  author: your-team
  version: "1.0"
---

# Code Review Skill

## 何时使用
- 用户要求进行代码审查
- Pull Request 需要评估

## 工作流程

### 步骤 1：理解上下文
阅读代码并理解其用途。

### 步骤 2：检查问题
- 安全漏洞（注入、XSS 等）
- 性能问题
- 代码风格和可读性
- 错误处理完整性

### 步骤 3：提供结构化反馈
将发现格式化为带有严重级别的可操作项。
```

## 与 Agent 集成

### 基础用法

直接传入 Skill 目录路径：

```java
Agent agent = Agent.builder()
    .name("research-assistant")
    .description("A research assistant with skills")
    .systemPrompt("You are a helpful research assistant.")
    .llmProvider(llmProvider)
    .toolCalls(List.of(new ReadFileTool(), new WebSearchTool()))
    .skills("/home/user/.core-ai/skills")
    .build();

agent.run("帮我调研 2025 年大语言模型的最新进展");
// Agent 会：
// 1. 在 system prompt 中看到 "web-research" skill
// 2. 判断任务匹配该 skill
// 3. 使用 ReadFileTool 读取 web-research/SKILL.md
// 4. 按照 SKILL.md 中的研究流程执行
```

### 使用 SkillConfig 完整配置

用于多来源和优先级的高级控制：

```java
Agent agent = Agent.builder()
    .name("dev-assistant")
    .description("A development assistant")
    .systemPrompt("You are a development assistant.")
    .llmProvider(llmProvider)
    .skills(SkillConfig.builder()
        .source("builtin", "/opt/core-ai/skills/", 0)        // 内置 skills（最低优先级）
        .source("user", "/home/user/.core-ai/skills/", 1)     // 用户 skills
        .source("project", "./.core-ai/skills/", 2)           // 项目 skills（最高优先级）
        .maxSkillFileSize(5 * 1024 * 1024)                    // 5MB 限制
        .build())
    .build();
```

### 禁用 Skills

```java
Agent agent = Agent.builder()
    .name("simple-agent")
    .description("An agent without skills")
    .llmProvider(llmProvider)
    .skills(SkillConfig.disabled())
    .build();
```

## 多来源优先级

当多个来源包含同名 Skill 时，优先级高的来源覆盖低的：

```
来源: builtin (priority=0)       来源: project (priority=2)
├── code-review/                 ├── code-review/        ← 胜出（优先级更高）
│   └── SKILL.md                 │   └── SKILL.md
├── web-research/                └── deploy/
│   └── SKILL.md                     └── SKILL.md
```

结果：Agent 看到 `code-review`（来自 project）、`web-research`（来自 builtin）和 `deploy`（来自 project）。

## 架构设计

### Lifecycle 执行顺序

SkillLifecycle 通过 `AbstractLifecycle` 集成，不改变 Agent 核心：

```
agentLifecycles 执行顺序：
┌───────────────────────┐
│ ToolCallPruningLC     │  ← 裁剪旧的 tool call 记录
├───────────────────────┤
│ SkillLifecycle        │  ← 注入 Skill 元数据到 system prompt
├───────────────────────┤
│ [用户自定义 lifecycle]  │
├───────────────────────┤
│ CompressionLifecycle  │  ← 压缩过长的消息历史
├───────────────────────┤
│ MemoryLifecycle       │  ← 注入记忆工具
└───────────────────────┘
```

SkillLifecycle 在 Compression **之前**执行，确保 system prompt 中的 Skill 元数据不会被压缩裁剪。

### 核心类

| 类 | 用途 |
|----|------|
| `SkillMetadata` | Skill 元数据模型（name、description、path 等） |
| `SkillSource` | Skill 来源定义（含优先级） |
| `SkillConfig` | 配置类（Builder 模式） |
| `SkillLoader` | 目录扫描与 YAML frontmatter 解析 |
| `SkillPromptFormatter` | System prompt 格式化 |
| `SkillLifecycle` | AbstractLifecycle 实现 |

## 最佳实践

### Skill 设计原则

1. **保持 SKILL.md 聚焦** — 每个 Skill 覆盖一个领域，避免混合无关的工作流。
2. **写清楚"何时使用"** — 帮助 Agent 判断何时应用该 Skill。
3. **使用分步工作流** — 结构化的步骤更容易被 Agent 遵循。
4. **按名称引用工具** — 明确提到使用哪些 ToolCall（如"使用 `web_search` 查找信息源"）。
5. **包含示例** — 为复杂步骤展示期望的输入/输出。

### 目录组织

```
~/.core-ai/skills/              # 用户级 skills
├── web-research/
│   ├── SKILL.md
│   └── scripts/
│       └── search_helper.py
├── code-review/
│   └── SKILL.md
└── data-analysis/
    ├── SKILL.md
    └── templates/
        └── report.md
```

### 安全考虑

- Skill 文件仅从本地文件系统读取（不支持远程获取）
- YAML 解析使用 `SafeConstructor` 防止反序列化攻击
- 文件大小限制（默认 10MB）防止资源耗尽
- 符号链接穿越保护防止路径逃逸攻击
- Skill 名称严格验证（小写字母、连字符、最大 64 字符）

## API 参考

### SkillConfig.builder()

```java
SkillConfig config = SkillConfig.builder()
    .source("name", "/path/to/skills/", priority)  // 添加 Skill 来源
    .enabled(true)                                   // 启用/禁用（默认 true）
    .maxSkillFileSize(10 * 1024 * 1024)             // 最大文件大小（默认 10MB）
    .build();
```

### SkillConfig.of()

```java
// 快捷方式：从路径创建配置（自动分配优先级 0、1、2...）
SkillConfig config = SkillConfig.of("/path/a", "/path/b");
```

### AgentBuilder.skills()

```java
// 从路径
Agent.builder().skills("/path/to/skills").build();

// 从配置
Agent.builder().skills(SkillConfig.builder()...build()).build();
```

---

[返回教程列表](tutorials.md) | [返回文档首页](README.md)
