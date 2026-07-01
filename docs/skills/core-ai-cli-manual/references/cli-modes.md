# CLI Modes and Commands

## Operation Modes

core-ai-cli supports six modes via the `core-ai` command:

| Mode | Command | Description |
|------|---------|-------------|
| Interactive | `core-ai` | REPL mode — type prompts and use slash commands |
| Headless | `core-ai --prompt "query"` | Single prompt, print response, exit |
| Serve | `core-ai --serve` | Start A2A web server (port 9527 by default) |
| ACP | `core-ai --acp-agent` | Stdio mode for editor integration (Agent Client Protocol) |
| Remote | `core-ai --server <url>` | Connect to remote core-ai-server |
| Resume | `core-ai --resume` | Pick a recent session to resume |

## Command-Line Flags

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `-h`, `--help` | boolean | — | Show help |
| `-V`, `--version` | boolean | — | Show version |
| `--debug` | boolean | — | Enable debug output |
| `--model <name>` | String | — | Override LLM model |
| `--prompt <text>` | String | — | Single prompt, exit after response |
| `--config <path>` | Path | `~/.core-ai/agent.properties` | Config file path |
| `--dangerously-skip-permissions` | boolean | — | Skip all tool approval prompts |
| `-c`, `--continue` | boolean | — | Resume most recent session |
| `--resume` | boolean | — | Pick a recent session to resume |
| `--workspace <path>` | Path | `.` | Working directory |
| `--server <url>` | String | — | Remote A2A agent URL |
| `--api-key <token>` | String | — | Bearer token for remote |
| `--agent-id <id>` | String | `default-assistant` | Agent ID on remote server |
| `--serve` | boolean | — | Start A2A web server |
| `--port <num>` | int | `9527` | A2A server port |
| `--headless` | boolean | — | Serve without opening browser |
| `--web-dir <path>` | Path | — | Serve frontend from local directory |
| `--acp-agent` | boolean | — | Start in ACP stdio mode |
| `--upgrade` | boolean | — | Download latest CLI version |
| `--upgrade-dir <path>` | Path | — | Install dir for `--upgrade` |
| `--time-limit-seconds <n>` | Integer | — | Wall-clock time limit |

## Interactive Mode Slash Commands

Available in the REPL (`core-ai` without `--prompt` or `--serve`):

### Session Management
| Command | Description |
|---------|-------------|
| `/clear` | Start new session |
| `/exit` | Quit |
| `/resume` | Switch to a previous session |
| `/compact` | Remove old messages to free context |
| `/undo` | Undo last message and its response |
| `/export [file]` | Export session to markdown |

### Configuration
| Command | Description |
|---------|-------------|
| `/model` | Show current model |
| `/model <name>` | Switch to model |
| `/init` | Create `.core-ai/instructions.md` project config |
| `/debug` | Toggle debug mode |

### Memory
| Command | Description |
|---------|-------------|
| `/memory` | Show memory sub-command menu |
| `/memory edit` | Edit a memory file |
| `/memory search <keyword>` | Search memories by keyword |
| `/memory open` | Open memory folder in file manager |
| `/memory clear` | Delete knowledge wiki pages |
| `/memory enable` | Enable memory (`agent.memory.enabled=true`) |
| `/memory disable` | Disable memory (`agent.memory.enabled=false`) |

### Tools and Plugins
| Command | Description |
|---------|-------------|
| `/tools` | List available tools |
| `/skills` | List loaded skills |
| `/skill <name>` | Load skill content |
| `/plugins` | Manage plugins |
| `/mcp` | Show MCP server status |

### Other
| Command | Description |
|---------|-------------|
| `/stats` | Show token usage and session stats |
| `/copy` | Copy last response to clipboard |
| `/upgrade` | Check for updates |

## ACP Mode Commands

Available in ACP mode (`core-ai --acp-agent`):

| Command | Description |
|---------|-------------|
| `/help` | Show available commands |
| `/models` | List available models |
| `/model <name>` | Switch model |
| `/debug` | Toggle debug |
| `/init` | Create `.core-ai/instructions.md` |
| `/tools` | List tools |
| `/stats` | Show stats |
| `/undo` | Undo last turn |
| `/compact` | Compact conversation |
| `/export [file]` | Export conversation |
| `/memory` | Memory management |
| `/memory search <keyword>` | Search memories |
| `/memory enable` | Enable memory |
| `/memory disable` | Disable memory |
| `/memory clear` | Delete wiki pages |
| `/skills` | List skills |
| `/mcp` | MCP status |
| `/sessions` | List saved sessions |
| `/resume <id>` | Resume session |

## Headless Mode

```bash
# Single prompt, print response to stdout, exit
core-ai --prompt "Explain the code in src/main.py"

# With model override
core-ai --model gpt-4o --prompt "Review this code"

# With time limit
core-ai --time-limit-seconds 300 --prompt "Run the benchmark suite"
```

## Serve Mode

```bash
# Start A2A web server
core-ai --serve

# Custom port, headless
core-ai --serve --port 8080 --headless

# With local frontend for development
core-ai --serve --web-dir ./frontend/dist
```

Serve mode starts an HTTP API server for A2A protocol clients to connect.

## ACP Mode

```bash
# Start in ACP stdio mode (for editor integration)
core-ai --acp-agent
```

ACP mode communicates via stdin/stdout using the Agent Client Protocol. Used by editors and IDEs to integrate with core-ai-cli.

## Configuration File Paths

| File | Default Location |
|------|-----------------|
| Global config | `~/.core-ai/agent.properties` |
| Custom config | `--config /path/to/file` |
| Session data | `~/.core-ai/sessions/<workspace-hash>/` |
| Instructions | `<workspace>/.core-ai/instructions.md` |
| Tool permissions | `<workspace>/.core-ai/tool-permissions.json` |
