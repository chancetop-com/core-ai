---
name: core-ai-cli-manual
description: Operation manual for core-ai-cli, for both users and LLMs. Use when configuring core-ai-cli (agent.properties, LLM providers, memory, hooks, local MCP servers, plugins, custom agents), when using its modes (interactive REPL, headless --prompt, ACP), when logging in to a core-ai-server, or when an agent needs to discover and use company resources through the CLI hub subcommands (core-ai-cli mcp search/describe/call today; skill, api-tool and agent hubs as they ship). Look up exact property names, flags, defaults, exit codes and JSON shapes here before acting.
metadata:
  author: core-ai-team
  version: "2.0"
---

# core-ai-cli Operation Manual

Reference for configuring core-ai-cli and for using it as a shell tool that reaches company resources on core-ai-server.

## Two ways to use this CLI

| Use | Entry point | Needs |
|-----|-------------|-------|
| Run the local agent | `core-ai-cli` (REPL), `core-ai-cli --prompt "…"` (headless), `core-ai-cli --acp-agent` (editor) | An LLM provider: either `agent.properties` or a login to core-ai-server (server acts as LLM proxy) |
| Reach server-side resources from any agent or script | `core-ai-cli mcp …` today; `skill`, `api-tool`, `agent` hubs as they ship | Login (`core-ai-cli --login`) or `CORE_AI_SERVER` + `CORE_AI_API_KEY`. No LLM, no agent session |

Hub subcommands are what other agents (Claude Code, Codex, CI) call. The three-step rule: **search → describe/show → call/run**, always with `--json`, never guessing names. Details: [references/hub.md](references/hub.md).

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `agent.properties` | `~/.core-ai/agent.properties` | Main configuration (global) |
| `agent.properties` | `<workspace>/.core-ai/agent.properties` | Per-project overrides (merges over global) |
| `auth.json` | `~/.core-ai/auth.json` | Server logins (list, one active); written by `--login` / `/login` |
| `instructions.md` | `<workspace>/.core-ai/instructions.md` | Project instructions injected into the system prompt (`/init` creates it) |
| `MCP.json` | `<workspace>/.core-ai/MCP.json` | Per-project local MCP servers |
| `hooks.json` | `<workspace>/.core-ai/hooks.json` | Per-project hook scripts |
| Plugins | `~/.core-ai/plugins/<name>/`, `<workspace>/.core-ai/plugins/<name>/` | Plugin bundles (hooks, skills) |
| Skills | `~/.core-ai/skills/`, `<workspace>/.core-ai/skills/` | SKILL.md packages loaded by the local agent |
| Custom agents | `~/.core-ai/agents/*.md`, `<workspace>/.core-ai/agents/*.md` | Sub-agent profiles (`/agents create <name>`) |
| Tool permissions | `<workspace>/.core-ai/tool-permissions.json` | Remembered tool approvals |
| Sessions | `~/.core-ai/sessions/<workspace-dir-name>/` | Saved sessions for `--continue` / `--resume` |

Workspace-local properties override global properties. For hooks, workspace `hooks.json` has the highest priority, then global plugins, then local plugins.

## Quick Start

```properties
# Memory is on by default; enable the full daily-logs pipeline
agent.memory.daily.logs.enabled=true

# Enable coding mode
agent.coding.enabled=true

# Enable todo v2
agent.todo.v2.enabled=true
```

```bash
core-ai-cli --login https://core-ai.example.com   # once; then the server can also serve as LLM provider
core-ai-cli mcp search "jira" --json               # discover server-side tools without starting an agent
```

## Document Index

| Topic | File |
|-------|------|
| **Hub subcommands: `mcp` (available), `skill` / `api-tool` / `agent` (planned); auth precedence, `--json`, exit codes** | [references/hub.md](references/hub.md) |
| **All agent.properties keys, defaults, and descriptions** | [references/agent-properties.md](references/agent-properties.md) |
| **CLI modes (interactive, headless, ACP, hub), flags, slash commands, custom agents** | [references/cli-modes.md](references/cli-modes.md) |
| **hooks.json format, events, environment variables** | [references/hooks.md](references/hooks.md) |
| **Local MCP server configuration (stdio and HTTP)** | [references/mcp.md](references/mcp.md) |
| **Memory system architecture, properties, and modes** | [references/memory.md](references/memory.md) |
| **LLM provider configuration (server login, LiteLLM, OpenAI, DeepSeek, OpenRouter, Azure)** | [references/providers.md](references/providers.md) |

## Feature Gates (Quick Reference)

| Property | Interactive | Headless | ACP |
|----------|------------|----------|-----|
| `agent.memory.enabled` | `true` | `true` | `true` |
| `agent.memory.daily.logs.enabled` | `false` | `false` | `false` |
| `agent.memory.prompt.extraction` | `false` | `false` | n/a |
| `agent.coding.enabled` | `false` | `false` | `false` |
| `agent.todo.v2.enabled` | `false` | `false` | n/a |

Interactive = `core-ai-cli`, Headless = `core-ai-cli --prompt`, ACP = `core-ai-cli --acp-agent`. Hub subcommands read none of these.

## Writing Configuration

When instructed to add or change a property, edit the appropriate file:
- **Global defaults**: `~/.core-ai/agent.properties`
- **Project-specific overrides**: `<workspace>/.core-ai/agent.properties`

Use edit_file to modify existing files; use write_file only when creating a new file.
Property format: `key=value` (no spaces around `=`). Comments start with `#`.
