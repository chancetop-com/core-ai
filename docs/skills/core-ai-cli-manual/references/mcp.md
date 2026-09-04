# Local MCP Configuration

MCP (Model Context Protocol) servers extend the **local** agent with external tools. This file covers servers the CLI launches or connects to itself. MCP servers registered on core-ai-server are used through `core-ai-cli mcp search/describe/call` instead; see [hub.md](hub.md). The two sets do not overlap: local servers are visible only to the local agent, hub servers are executed on the server.

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

A server entry is either **stdio** (has `command`) or **HTTP** (has `url`).

```json
{
  "local-tool": {
    "command": "npx",
    "args": ["-y", "some-mcp@latest"],
    "env": {"API_TOKEN": "..."}
  },
  "remote-tool": {
    "url": "https://mcp.example.com",
    "endpoint": "/mcp",
    "transport": "sse",
    "headers": {"Authorization": "Bearer ..."}
  }
}
```

| Field | Applies to | Required | Description |
|-------|-----------|----------|-------------|
| `command` | stdio | Yes | Executable to launch |
| `args` | stdio | No | Command-line arguments |
| `env` | stdio | No | Environment variables for the process |
| `url` | HTTP | Yes | Base URL of the MCP server |
| `endpoint` | HTTP | No | Path appended to `url` |
| `transport` | HTTP | No | `sse` for the legacy SSE transport; default is streamable HTTP |
| `headers` | HTTP | No | Request headers, as an object or a JSON string |

`"transport": "sandbox_hosted"` together with `command` is a server-side option and is not meaningful in the CLI.

## MCP Command

Use `/mcp` in interactive mode to view local MCP server connection status.

## Merging Rules

1. Global `mcp.servers.json` is loaded first
2. Workspace `MCP.json` is loaded and merged
3. Matching keys: workspace overrides global
4. Non-matching keys: both are included

This means you can have base MCP servers in global config and project-specific additions in workspace config.
