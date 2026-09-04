# CLI Modes and Commands

## Operation Modes

| Mode | Command | Description |
|------|---------|-------------|
| Interactive | `core-ai-cli` | REPL: type prompts, use slash commands |
| Headless | `core-ai-cli --prompt "query"` | Single prompt, print response, exit |
| ACP | `core-ai-cli --acp-agent` | Stdio mode for editor integration (Agent Client Protocol) |
| Resume | `core-ai-cli --continue` / `--resume` | Resume the most recent session / pick one |
| Login | `core-ai-cli --login [server-url]` | Authenticate with a core-ai-server and exit |
| Hub | `core-ai-cli mcp …` (and `skill` / `api-tool` / `agent` as they ship) | Discover and call server-side resources without an agent session; see [hub.md](hub.md) |

The former remote mode (`--server`, `--api-key`, `--agent-id`, REPL `/remote`) has been removed. Server-side tools are reached through the hub subcommands; server-side agents through the Web UI or, once shipped, `core-ai-cli agent run`.

## Command-Line Flags (root command)

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `-h`, `--help` | boolean | — | Show help |
| `-V`, `--version` | boolean | — | Show version |
| `--debug` | boolean | — | Enable debug output |
| `--model <name>` | String | — | Override LLM model for this run |
| `--prompt <text>` | String | — | Single prompt, exit after response |
| `--config <path>` | Path | `~/.core-ai/agent.properties` | Config file path |
| `--dangerously-skip-permissions` | boolean | — | Skip all tool approval prompts |
| `-c`, `--continue` | boolean | — | Resume most recent session |
| `--resume` | boolean | — | Pick a recent session to resume |
| `--workspace <path>` | Path | `.` | Working directory for the session |
| `--login [server-url]` | String | — | Log in (browser flow or pasted API key) and exit |
| `--acp-agent` | boolean | — | Start in ACP stdio mode |
| `--upgrade` | boolean | — | Download and install the latest CLI version |
| `--upgrade-dir <path>` | Path | current binary dir or `~/.core-ai/bin/` | Install dir for `--upgrade` |
| `--time-limit-seconds <n>` | Integer | — | Wall-clock limit for agent execution; extraction and cleanup still run afterwards |

Root flags such as `--debug` do not apply to hub subcommands; those have their own options (`--json`, `--raw`, `--server`, `--api-key`, `--quiet`).

## Interactive Mode Slash Commands

### Session
| Command | Description |
|---------|-------------|
| `/help` | Show available commands |
| `/clear` | Start new session |
| `/exit` | Quit |
| `/resume` | Switch to a previous session |
| `/compact` | Remove old messages to free context |
| `/undo` | Undo last message and its response |
| `/export [file]` | Export session to markdown |
| `/stats` | Token usage and session stats |
| `/copy` | Copy last response to clipboard |

### Model and reasoning
| Command | Description |
|---------|-------------|
| `/model` | Interactive model picker (`/models` is an alias) |
| `/model <name>` | Switch to model |
| `/thinking` | Interactive reasoning-effort picker |
| `/thinking none\|low\|high\|max\|off` | Set reasoning effort; `off` uses the provider default |
| `/debug` | Toggle debug mode |

### Server login
| Command | Description |
|---------|-------------|
| `/login [server-url]` | Authenticate with a core-ai-server (browser callback or pasted API key); writes `~/.core-ai/auth.json` |
| `/logout` | Log out of the current server |
| `/status` | Show auth status and user info |
| `/server` | List registered servers and pick one to switch to |
| `/server <url\|name>` | Switch active server |

When logged in and no `litellm.api.base` is configured, the CLI uses the server as its LLM provider through `<server>/api/cli/v1`.

### Memory
| Command | Description |
|---------|-------------|
| `/memory` | Memory sub-command menu |
| `/memory edit` | Edit a memory file |
| `/memory search <keyword>` | Search memories |
| `/memory open` | Open memory folder in the file manager |
| `/memory clear` | Delete knowledge wiki pages, recreate structure |
| `/memory enable` / `/memory disable` | Set `agent.memory.enabled` |

### Tools, skills, plugins, agents
| Command | Description |
|---------|-------------|
| `/tools` | List available tools |
| `/skill` (alias `/skills`) | Menu: local skills (upload to server) and server skills (install / update / remove) |
| `/skill <name>` | Load a local skill's content into the conversation |
| `/plugins` | Plugin management; `/plugins help` lists subcommands |
| `/plugins list` | Installed plugins |
| `/plugins install <source> [--local\|--global]` | Sources: `git:<url>`, `github:<owner/repo>`, `npm:<package>`, `./path`. `--global` (default) installs to `~/.core-ai/plugins/`, `--local` to `<workspace>/.core-ai/plugins/` |
| `/plugins uninstall\|enable\|disable\|validate\|info\|reload` | Other plugin operations |
| `/mcp` | Local MCP server connection status (not the server-side hub) |
| `/agents` | List custom sub-agents |
| `/agents create [name]` | Create `<workspace>/.core-ai/agents/<name>.md` from a template |
| `/agents delete <name>` | Delete a custom agent |
| `/init` | Create `<workspace>/.core-ai/instructions.md` |
| `/upgrade` | Check for updates and upgrade |

## Custom Agents

Markdown files with YAML frontmatter, one agent per file. Workspace `.core-ai/agents/` (priority 100) overrides `~/.core-ai/agents/` (priority 50) for the same name.

```markdown
---
name: code-reviewer
description: "Use for reviewing diffs for bugs and style"
# model: sonnet
# temperature: 0.8
# maxTurnNumber: 200
# reasoningEffort: none | low | high | max
# tools:
#   - Read
#   - Bash
#   - Grep
---

You are code-reviewer. Describe what you do and how you should work.
```

The body is the system prompt. A file without frontmatter uses its filename as the name and the whole content as the prompt.

## ACP Mode Commands

Available in ACP mode (`core-ai-cli --acp-agent`):

| Command | Description |
|---------|-------------|
| `/help` | Show available commands |
| `/models` | List available models |
| `/model <name>` | Switch model |
| `/thinking [level]` | Show or set reasoning effort (`none`/`low`/`high`/`max`/`off`) |
| `/debug` | Toggle debug |
| `/init` | Create `.core-ai/instructions.md` |
| `/tools` | List tools |
| `/stats` | Show stats |
| `/undo` | Undo last turn |
| `/compact` | Compact conversation |
| `/export [file]` | Export conversation |
| `/memory`, `/memory search <k>`, `/memory enable\|disable\|clear` | Memory management |
| `/skills` | List installed skill directories |
| `/mcp` | Local MCP status |
| `/sessions` | List saved sessions |
| `/resume <id>` | Resume a saved session |

ACP mode does not run the startup skill provisioner and does not read `agent.todo.v2.enabled` or `agent.memory.prompt.extraction`.

## Headless Mode

```bash
core-ai-cli --prompt "Explain the code in src/main.py"
core-ai-cli --model gpt-4o --prompt "Review this code"
core-ai-cli --time-limit-seconds 300 --prompt "Run the benchmark suite"
```

## Paths

| Item | Location |
|------|----------|
| Global config | `~/.core-ai/agent.properties` (or `--config`) |
| Auth | `~/.core-ai/auth.json` |
| Sessions | `~/.core-ai/sessions/<workspace-dir-name>/` |
| Instructions | `<workspace>/.core-ai/instructions.md` |
| Tool permissions | `<workspace>/.core-ai/tool-permissions.json` |
| Upgrade install dir | `~/.core-ai/bin/` (when the binary dir is not writable) |
