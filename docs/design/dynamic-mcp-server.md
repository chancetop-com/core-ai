# Dynamic MCP Server Design

## Motivation

Support `npx`-based MCP server configurations that start lazily inside a sandbox,
keeping the MCP process isolated and its lifecycle tied to the sandbox. Configs
are stored as a new `transport: sandbox_hosted` variant alongside the existing
STDIO and HTTP/SSE transports.

## Architecture

Two sandbox roles, both lazy:

| Sandbox | Count | Lifecycle | Purpose |
|---|---|---|---|
| Discovery Sandbox | 1 global | created on first tool-browse, 24h idle timeout | Lists tools for the frontend's MCP page (`/api/tools/mcp-servers/:id/tools`) |
| Session Sandbox | 1 per session/run | bound to the session | Runs MCP child processes that the agent's tool calls bridge through |

Each session/run gets its **own** `McpClientManager` instance (kept on `SandboxService.sessionMcpManagers`).
The global `McpClientManager` only holds non-sandbox MCP entries (STDIO/HTTP).
Concurrent sessions using the same sandbox-hosted MCP server id never collide,
because each owns its own MCP child process in its own sandbox and its own
Java-side client.

```
Frontend MCP page                Agent run / Chat session
       │                                  │
       ▼                                  ▼
 ToolRegistryService             ToolRegistryService.resolveToolRefs(refs, sessionId)
   .connectMcpServer / list        │
   .listMcpServerToolDetails       ▼  (only for sandbox_hosted refs)
       │                          per-session McpClientManager
       ▼                                  │
 ensureRegisteredOnDiscovery              ▼
       │                          registerOnSession
       ▼                                  │
 Discovery Sandbox                Session Sandbox
 POST /mcp/start                  POST /mcp/start
 POST /mcp (JSON-RPC bridge)      POST /mcp (JSON-RPC bridge)
```

## Sandbox Runtime API (Go)

Endpoints on `core-ai-sandbox-runtime :8080`:

| Method | Path | Description |
|---|---|---|
| POST | `/mcp/start` | Start an MCP child process. Body: `{"id":"...","command":"npx","args":["-y","@xxx/mcp"],"env":{}}`. Returns once the process survives a 3-second crash-detection window; longer survival monitoring is asynchronous (see `exitWatcher`). |
| GET  | `/mcp`       | List running MCP server ids. |
| POST | `/mcp`       | MCP JSON-RPC bridge. Routes to the target process. Header `X-Mcp-Server-Id` identifies the target. |
| POST | `/mcp/stop`  | Stop the MCP child process. Body: `{"id":"..."}`. |

The HTTP handler returns within ~3s of `cmd.Start()` — long enough to catch
immediate `npm` failures (ECONNRESET, missing package), short enough that the
Java client's 30/60s read timeout never fires on a healthy startup.

## Java Side

### `McpServerConfig`

New `TransportType.SANDBOX_HOSTED`. Config format mirrors STDIO (`command`,
`args`, `env`) but the entry is never registered into the global manager as
STDIO would be — it's resolved on demand by the discovery or session paths.

### `McpServerConnectionManager` (package-private)

Three registration entry points:

- `registerMcpServer(entry)` — skips sandbox-hosted entries entirely. Used by
  `loadDatabaseTools` / `syncDatabaseTools` at startup for plain STDIO/HTTP.
- `ensureRegisteredOnDiscovery(entry)` — idempotent. Starts the MCP process on
  the discovery sandbox and registers an HTTP transport pointing at it in the
  **global** `McpClientManager`. Called by `connectMcpServer` and
  `listMcpServerToolDetails` (the tool-browser flow).
- `registerOnSession(entry, sessionManager, sandbox)` — starts the MCP process
  on the session's sandbox and registers an HTTP transport in the **session-scoped**
  `McpClientManager` passed in. Called by `prepareSessionMcpServers` during
  `resolveToolRefs(refs, sessionId)`.

### `SandboxService`

- `sessionMcpManagers`: `sessionId → McpClientManager` — created lazily by
  `getOrCreateSessionMcpManager(sessionId)`.
- `sessionMcpServerIds`: `sessionId → Set<serverId>` — records which MCP
  processes were started per session so they can be stopped explicitly on
  release (`POST /mcp/stop`).
- `releaseSandbox(sessionId)` closes the session manager and stops the
  recorded MCP processes before closing the sandbox itself.

### `ToolRegistryService.resolveToolRefs(refs, sessionId)`

- If any ref is sandbox-hosted and a session sandbox exists:
  1. `sandboxService.ensureSandboxReady(sessionId)` — materialize the LazySandbox.
  2. `getOrCreateSessionMcpManager(sessionId)`.
  3. For each sandbox-hosted ref entry, `registerOnSession(entry, mgr, sandbox)`
     and record the server id on the session.
- Hands the (possibly null) session manager to `ToolRefResolver.resolve`.

### `ToolRefResolver.resolve(refs, sessionMcpManager)`

When resolving an MCP ref, prefers `sessionMcpManager` over the global one if
it has the target server. So the resulting `McpToolCall` is bound to the
session's manager — and thus to the session sandbox's bridge — for the
lifetime of the session.

## Flow

### Tool Discovery (frontend MCP page)

1. User creates / imports an MCP server (`POST /api/tools/mcp-servers` or `/import`).
   The entity is persisted; nothing is started in any sandbox.
2. User clicks "Connect" → `POST /api/tools/mcp-servers/:id/connect` →
   `ensureRegisteredOnDiscovery` lazily creates the discovery sandbox, starts
   the MCP process there, registers in the global manager.
3. User views tools → `GET /api/tools/mcp-servers/:id/tools` → reads from the
   global manager (already registered).

### Agent Execution

1. `AgentRunner.run()` creates a LazySandbox for the run (`sessionId = runId`).
2. `buildAgent()` calls `toolRegistryService.resolveToolRefs(refs, runId)`.
3. For each sandbox-hosted ref:
   - Sandbox is materialized.
   - MCP child process is started on the session sandbox (`POST /mcp/start`).
   - A session-scoped HTTP transport is registered in the per-session manager.
4. `McpToolCall` instances capture the per-session manager and serverName,
   so subsequent tool calls bridge through the session sandbox.
5. On run release (~60s after completion), `releaseSandbox`:
   - sends `POST /mcp/stop` for each recorded server id;
   - closes the session manager;
   - closes the sandbox (which would reap any orphan child anyway).

Chat sessions go through the same flow via `AgentSessionManager.createSessionFromAgent`
and `SessionRebuildManager.doRebuild`, which both create the LazySandbox before
calling `resolveToolRefs`.

## Concurrency

| Scenario | Behavior |
|---|---|
| Two sessions reference the same sandbox-hosted server | Each gets its own MCP process in its own sandbox; tool calls route to their own bridge — no cross-talk. |
| Session and frontend tool-browser referencing the same server | Discovery sandbox runs one process for browsing; each session has separate processes. They coexist; closing one doesn't affect the other. |
| Session sandbox restart (crash + replace) | Session manager is closed during release. On next `resolveToolRefs`, registration repeats against the new sandbox. |

## Deployment

No changes to Dockerfile — `npx`/`node` already installed in sandbox runtime image.
