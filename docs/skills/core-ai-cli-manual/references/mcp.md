# MCP Configuration

MCP (Model Context Protocol) servers extend the agent with external tools and resources.

## Configuration Methods

### Method 1: agent.properties (inline JSON)

```properties
mcp.servers.json={"chrome-devtools":{"command":"npx","args":["-y","chrome-devtools-mcp@latest"]}}
```

All on one line. Suitable for simple single-server setups.

### Method 2: Workspace MCP.json

Create `<workspace>/.core-ai/MCP.json`:

```json
{
  "chrome-devtools": {
    "command": "npx",
    "args": ["-y", "chrome-devtools-mcp@latest"]
  },
  "playwright": {
    "command": "npx",
    "args": ["@playwright/mcp@latest", "--isolated"]
  }
}
```

Recommended for multi-server setups. Workspace MCP.json overrides global `mcp.servers.json` entries with matching keys.

## Server Object Schema

```json
{
  "server-name": {
    "command": "executable",
    "args": ["arg1", "arg2", ...]
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `command` | String | Yes | Executable to launch |
| `args` | Array | No | Command-line arguments |

The server name becomes the tool namespace prefix (e.g., `chrome-devtools_navigate`).

## MCP Command

Use `/mcp` in interactive mode to view MCP server connection status.

## Merging Rules

1. Global `mcp.servers.json` is loaded first
2. Workspace `MCP.json` is loaded and merged
3. Matching keys: workspace overrides global
4. Non-matching keys: both are included

This means you can have base MCP servers in global config and project-specific additions in workspace config.
