# Tutorial: Skills System

This tutorial covers Core-AI's Skills system for modularly encapsulating domain expert knowledge as reusable skill packages.

## Table of Contents

1. [Overview](#overview)
2. [Core Concepts](#core-concepts)
3. [Creating a Skill](#creating-a-skill)
4. [Agent Integration](#agent-integration)
5. [Multi-Source Priority](#multi-source-priority)
6. [Architecture](#architecture)
7. [Best Practices](#best-practices)
8. [API Reference](#api-reference)

## Overview

The Skills system allows you to package domain expert knowledge (workflows, best practices, tool orchestration strategies) as independent, reusable Skill packages. Skills inject metadata into system prompts using **progressive disclosure** - only name and description appear in the prompt, while full instructions are loaded on-demand via `ReadFileTool`.

```
+-------------------------------------------------------------+
|                    Skills System Architecture                |
+-------------------------------------------------------------+
|                                                             |
|  Core Capabilities:                                         |
|  - Modular encapsulation of domain knowledge                |
|  - Progressive disclosure (token-efficient)                 |
|  - Multi-source priority overrides                          |
|  - Non-invasive lifecycle integration                       |
|                                                             |
|  Key Idea:                                                  |
|  Skill != Executable Plugin                                 |
|  Skill = Structured Expert Knowledge via Prompt Injection   |
|                                                             |
|  Integration: AbstractLifecycle (no Agent core changes)     |
|                                                             |
+-------------------------------------------------------------+
```

### Why Skills?

| Problem | Solution |
|---------|----------|
| System prompt bloat when handling many domains | Skills inject only name + description (~100 tokens), full instructions loaded on-demand |
| Domain workflows hard to reuse across agents | Skills are self-contained packages, shareable across projects |
| Tools provide "what to do" but lack "how to do" guidance | Skills teach agents best practices for orchestrating tools |

## Core Concepts

### What is a Skill?

A Skill is a **self-contained knowledge package** consisting of:

```
my-skill/
├── SKILL.md              # Required: YAML metadata + Markdown instructions
├── scripts/              # Optional: helper scripts
├── references/           # Optional: reference documents
└── assets/               # Optional: templates, configs
```

### SKILL.md Format

Each skill directory must contain a `SKILL.md` file with YAML frontmatter:

```markdown
---
name: web-research
description: Provides structured web research methodology with multi-source collection and synthesis
license: MIT
compatibility: Requires web_search and write_file tools
metadata:
  author: core-ai-team
  version: "1.0"
allowed-tools: ShellCommandTool WebSearchTool WriteFileTool
---

# Web Research Skill

## When to Use
- User asks for deep research on a topic
- Need to collect and synthesize from multiple sources

## Workflow

### Step 1: Create Research Plan
Identify key aspects of the topic to research.

### Step 2: Search and Collect
Use web_search to find relevant sources.

### Step 3: Synthesize Results
Combine findings into a structured report.
```

### Skill Name Rules

| Rule | Example |
|------|---------|
| Max 64 characters | `web-research` |
| Only lowercase letters, digits, hyphens | `arxiv-search-v2` |
| Cannot start or end with hyphen | ~~`-bad-name`~~ |
| No consecutive hyphens | ~~`web--research`~~ |
| Must match parent directory name | `web-research/SKILL.md` → name must be `web-research` |

### Progressive Disclosure Flow

```
┌──────────────────────────────────────────────────────┐
│                    System Prompt                      │
│                                                      │
│  ## Skills                                           │
│  - web-research: Provides structured web research... │
│    → Read `/skills/web-research/SKILL.md`            │
│  - code-review: Provides code review checklist...    │
│    → Read `/skills/code-review/SKILL.md`             │
│                                                      │
│  (metadata only, ~100 tokens)                        │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼ User request matches "web-research"
┌──────────────────────────────────────────────────────┐
│  Agent reads full SKILL.md via ReadFileTool          │
│  (on-demand, ~2000 tokens)                           │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│  Agent follows SKILL.md workflow                     │
│  Uses existing ToolCalls to complete the task        │
└──────────────────────────────────────────────────────┘
```

## Creating a Skill

### Step 1: Create Directory Structure

```bash
mkdir -p ~/.core-ai/skills/code-review
```

### Step 2: Write SKILL.md

```markdown
---
name: code-review
description: Provides structured code review process with security and quality checklists
license: MIT
metadata:
  author: your-team
  version: "1.0"
---

# Code Review Skill

## When to Use
- User asks for a code review
- Pull request needs evaluation

## Workflow

### Step 1: Understand Context
Read the code and understand its purpose.

### Step 2: Check for Issues
- Security vulnerabilities (injection, XSS, etc.)
- Performance concerns
- Code style and readability
- Error handling completeness

### Step 3: Provide Structured Feedback
Format findings as actionable items with severity levels.
```

## Agent Integration

### Basic Usage

Pass skill directory paths directly:

```java
Agent agent = Agent.builder()
    .name("research-assistant")
    .description("A research assistant with skills")
    .systemPrompt("You are a helpful research assistant.")
    .llmProvider(llmProvider)
    .toolCalls(List.of(new ReadFileTool(), new WebSearchTool()))
    .skills("/home/user/.core-ai/skills")
    .build();

agent.run("Research the latest trends in large language models");
// Agent will:
// 1. See "web-research" skill in system prompt
// 2. Determine the task matches this skill
// 3. Read web-research/SKILL.md via ReadFileTool
// 4. Follow the research workflow from SKILL.md
```

### Full Configuration with SkillConfig

For advanced control over multiple sources and priorities:

```java
Agent agent = Agent.builder()
    .name("dev-assistant")
    .description("A development assistant")
    .systemPrompt("You are a development assistant.")
    .llmProvider(llmProvider)
    .skills(SkillConfig.builder()
        .source("builtin", "/opt/core-ai/skills/", 0)        // Built-in skills (lowest priority)
        .source("user", "/home/user/.core-ai/skills/", 1)     // User skills
        .source("project", "./.core-ai/skills/", 2)           // Project skills (highest priority)
        .maxSkillFileSize(5 * 1024 * 1024)                    // 5MB limit
        .build())
    .build();
```

### Disabling Skills

```java
Agent agent = Agent.builder()
    .name("simple-agent")
    .description("An agent without skills")
    .llmProvider(llmProvider)
    .skills(SkillConfig.disabled())
    .build();
```

## Multi-Source Priority

When multiple sources contain a skill with the same name, the higher priority source wins:

```
Source: builtin (priority=0)     Source: project (priority=2)
├── code-review/                 ├── code-review/        ← WINS (higher priority)
│   └── SKILL.md                 │   └── SKILL.md
├── web-research/                └── deploy/
│   └── SKILL.md                     └── SKILL.md
```

Result: Agent sees `code-review` (from project), `web-research` (from builtin), and `deploy` (from project).

## Architecture

### Lifecycle Ordering

SkillLifecycle integrates via `AbstractLifecycle` without changing the Agent core:

```
agentLifecycles execution order:
┌───────────────────────┐
│ ToolCallPruningLC     │  ← Prune old tool call records
├───────────────────────┤
│ SkillLifecycle        │  ← Inject skill metadata into system prompt
├───────────────────────┤
│ [user lifecycles]     │
├───────────────────────┤
│ CompressionLifecycle  │  ← Compress long message history
├───────────────────────┤
│ MemoryLifecycle       │  ← Inject memory tools
└───────────────────────┘
```

SkillLifecycle runs **before** Compression so that skill metadata in the system prompt is not compressed away.

### Key Classes

| Class | Purpose |
|-------|---------|
| `SkillMetadata` | Skill metadata model (name, description, path, etc.) |
| `SkillSource` | Skill source definition with priority |
| `SkillConfig` | Configuration with builder pattern |
| `SkillLoader` | Directory scanning and YAML frontmatter parsing |
| `SkillPromptFormatter` | System prompt section formatting |
| `SkillLifecycle` | AbstractLifecycle implementation |

## Best Practices

### Skill Design

1. **Keep SKILL.md focused** - Each skill should cover one domain. Avoid mixing unrelated workflows.
2. **Write clear "When to Use" sections** - Help the agent determine when to apply the skill.
3. **Use step-by-step workflows** - Structured steps are easier for agents to follow.
4. **Reference tools by name** - Mention which ToolCalls to use (e.g., "Use `web_search` to find sources").
5. **Include examples** - Show expected inputs/outputs for complex steps.

### Directory Organization

```
~/.core-ai/skills/              # User-level skills
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

### Security Considerations

- Skill files are read from local filesystem only (no remote fetching)
- YAML parsing uses `SafeConstructor` to prevent deserialization attacks
- File size limit (default 10MB) prevents resource exhaustion
- Symlink traversal protection prevents path escape attacks
- Skill names are strictly validated (lowercase, hyphens, max 64 chars)

## API Reference

### SkillConfig.builder()

```java
SkillConfig config = SkillConfig.builder()
    .source("name", "/path/to/skills/", priority)  // Add a skill source
    .enabled(true)                                   // Enable/disable (default: true)
    .maxSkillFileSize(10 * 1024 * 1024)             // Max file size (default: 10MB)
    .build();
```

### SkillConfig.of()

```java
// Shortcut: create config from paths (auto-assigned priorities 0, 1, 2...)
SkillConfig config = SkillConfig.of("/path/a", "/path/b");
```

### AgentBuilder.skills()

```java
// From paths
Agent.builder().skills("/path/to/skills").build();

// From config
Agent.builder().skills(SkillConfig.builder()...build()).build();
```

---

[Back to Tutorials](tutorials.md) | [Back to Documentation](README.md)
