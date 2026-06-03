---
name: core-ai-cli-manual
description: Complete operation manual for core-ai-cli covering all configuration, features, and operational modes. Use when the user asks about configuring core-ai-cli, understanding available features, reading or writing agent.properties, setting up LLM providers, memory system configuration, hooks, MCP servers, CLI commands, ACP mode, serve mode, remote agents, or any aspect of core-ai-cli operation. This skill is for both users and LLMs — use it to look up exact property names, defaults, and configuration patterns before making changes.
---

# core-ai-cli Operation Manual

Comprehensive reference for configuring and operating core-ai-cli.

## Configuration Files

| File | Location | Purpose |
|------|----------|---------|
| `agent.properties` | `~/.core-ai/agent.properties` | Main configuration (global) |
| `agent.properties` | `<workspace>/.core-ai/agent.properties` | Per-project overrides (merges over global) |
| `instructions.md` | `<workspace>/.core-ai/instructions.md` | Project instructions injected into system prompt |
| `MCP.json` | `<workspace>/.core-ai/MCP.json` | Per-project MCP server overrides |
| `hooks.json` | `<workspace>/.core-ai/hooks.json` | Per-project hook scripts |
| Plugin hooks | `~/.core-ai/plugins/<name>/hooks/hooks.json` | Plugin-provided hooks |

Configuration loading chain: workspace-local properties override global properties. For hooks, workspace `hooks.json` has highest priority, then global plugins, then local plugins.

## Quick Start: Enabling Features

```properties
# Minimal: enable memory
agent.memory.enabled=true
agent.memory.daily.logs.enabled=true

# Enable coding mode
agent.coding.enabled=true

# Enable todo v2
agent.todo.v2.enabled=true
```

## Document Index

For detailed reference, read the relevant file:

| Topic | File |
|-------|------|
| **All agent.properties keys, defaults, and descriptions** | [references/agent-properties.md](references/agent-properties.md) |
| **CLI modes (interactive, headless, serve, ACP), flags, and commands** | [references/cli-modes.md](references/cli-modes.md) |
| **hooks.json format, events, environment variables** | [references/hooks.md](references/hooks.md) |
| **MCP server configuration** | [references/mcp.md](references/mcp.md) |
| **Memory system architecture, properties, and modes** | [references/memory.md](references/memory.md) |
| **LLM provider configuration patterns (LiteLLM, OpenAI, DeepSeek, etc.)** | [references/providers.md](references/providers.md) |

## Feature Gates (Quick Reference)

| Property | Interactive | Headless | Serve | ACP |
|----------|------------|----------|-------|-----|
| `agent.memory.enabled` | `false` | `false` | `true` | `false` |
| `agent.memory.daily.logs.enabled` | `false` | `false` | `false` | `false` |
| `agent.memory.prompt.extraction` | `false` | n/a | n/a | n/a |
| `agent.coding.enabled` | `false` | `false` | `false` | `false` |
| `agent.todo.v2.enabled` | `false` | `false` | `false` | n/a |

All boolean feature flags default to `false` unless noted. Interactive mode = `core-ai`, Headless = `core-ai --prompt`, Serve = `core-ai --serve`, ACP = `core-ai --acp-agent`.

## Writing Configuration

When instructed to add or change a property, edit the appropriate file:
- **Global defaults**: `~/.core-ai/agent.properties`
- **Project-specific overrides**: `<workspace>/.core-ai/agent.properties`

Always use edit_file to modify existing files. Only use write_file when creating a new file.
Property format: `key=value` (no spaces around `=`). Comments start with `#`.
