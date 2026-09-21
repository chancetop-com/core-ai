# Sandbox Runtime

A lightweight HTTP server that executes code execution and file operation tools in an isolated container environment.

## Features

- HTTP API for tool execution
- Health check endpoint
- Timeout support (run_bash_command 120s / run_python_script 60s default)
- Isolated execution environment with restricted environment variables
- Non-root user execution
- Path security (all file operations are restricted to workspace directory)
- **Session hub**: scripts inside the sandbox reach the session agent's configured MCP tools, API tools, LLM_CALL definitions and sub-agents through a loopback-only proxy — without holding any credential (see [Session Hub](#session-hub))

## Supported Tools

### Code Execution
- `run_bash_command` - Execute bash commands
- `run_python_script` - Execute Python scripts

### File Operations
- `read_file` - Read file contents
- `write_file` - Write file contents
- `edit_file` - Edit file contents (replace text)
- `glob_file` - Find files matching pattern
- `grep_file` - Search file contents

## API

### POST /execute

Execute a tool with arguments.

**Request:**
```json
{
  "tool": "read_file",
  "arguments": "{\"file_path\": \"src/main.java\", \"offset\": 0, \"limit\": 100}"
}
```

**Response:**
```json
{
  "status": "completed",
  "result": "package com.example;\n\npublic class Main { ... }",
  "durationMs": 45
}
```

### GET /health

Health check endpoint.

**Response:**
```json
{
  "status": "ok",
  "version": "2.0.12",
  "runtime_version": "2.0.12",
  "bound": true
}
```

`bound` reports whether a session hub binding is active (see `/bind`); `ffmpeg_version` / `ffmpeg_major` appear when the image ships ffmpeg.

### POST | DELETE /bind

Handed to this runtime by core-ai-server once the session's sandbox is ready (and cleared when the sandbox is released). The binding — server URL, session token, session id, agent name — lives in **process memory only**: it is never written into the pod spec, the container env, or a script's environment.

**Request:**
```json
{
  "server_url": "http://core-ai-server:8080",
  "token": "cst_…",
  "session_id": "…",
  "agent_name": "restaurant-seo",
  "expires_at": 1760000000000
}
```

`DELETE /bind` clears it; `POST` on an already bound runtime replaces it. Both answer `204`. There is no separate authentication: whoever can call `/execute` can already run arbitrary code in this sandbox, so binding adds no reach.

### /hub/* (loopback only)

The sandbox-internal endpoint for scripts: `http://127.0.0.1:8081/hub/*`, forwarded (GET/POST only) to `${server_url}/api/sandbox-hub/*` with the session token attached here — scripts never see it. It listens on its own **loopback-only** listener (`127.0.0.1:8081`, `HUB_PORT`) because `:8080` is reachable from the pod network. An `Authorization` header sent by a script is replaced, not forwarded; the SDK's `X-Core-AI-Client` is moved to `X-Core-AI-Sdk` so a call can still be traced back to its client. While nothing is bound, every request answers `503 {"error":{"code":"not_bound",…}}`; an unreachable server answers 502.

## Session Hub

Inside the sandbox a skill script calls whatever the session's agent has configured, with no credentials of its own. Two clients are preinstalled:

```bash
core-ai-sandbox me                       # the bound session and its tool counts
core-ai-sandbox catalog                  # every callable tool, grouped by kind
core-ai-sandbox tools "review" --kind mcp
core-ai-sandbox describe google_gbp_get_reviews
core-ai-sandbox call google_gbp_get_reviews --arg location=locations/123 --json
```

The datasets the session's agent mounts are addressed the same way, through the mounted tools
themselves — a sandbox holds no server credential of its own:

```bash
core-ai-sandbox dataset list
core-ai-sandbox dataset show review-log                                   # type, permission, schema
core-ai-sandbox dataset state get menu-state --fields menuPublished
core-ai-sandbox dataset records query review-log --filter '{"review_id":"r-1"}' --limit 10
core-ai-sandbox dataset records insert review-log --data-file -             # JSON on stdin
```

`<dataset>` is an id or a name unique inside this session; `--session` is refused unless it matches
the sandbox's own session. What a call may do is exactly what the binding allows (READ/WRITE/FULL),
so a refused write prints the server's sentence and exits `1`.

Exit codes: `0` success, `1` tool error, `2` usage, `3` not bound/unauthenticated, `4` forbidden, `5` tool not found, `6` timeout (the task keeps running; the envelope carries `task_id`).

```python
from core_ai_session import session

s = session()                                      # reads CORE_AI_HUB, injected by this runtime
r = s.mcp["google-gbp"]["get_reviews"](location="locations/123")
r.text, r.data, r.call_id

s.dataset["menu-state"].patch({"menuPublished": True})     # the datasets this session mounts
```

The Python package lives in `sdk/core-ai-session/` (its [README](../sdk/core-ai-session/README.md) covers install, layout and tests); `core-ai-sandbox` below is the runtime's CLI. The same script runs outside a sandbox unchanged: with `CORE_AI_HUB` unset the SDK drives `core-ai-cli` (your own identity) instead, and `FakeSession` covers offline unit tests. `CORE_AI_HUB` always wins — no silent fallback to the local identity.

Layout: `sdk/core-ai-session/core_ai_session/` (wheel built into the image; the same wheel is attached to `sandbox-v<version>` releases, so a local install is `pip install https://github.com/chancetop-com/core-ai/releases/download/sandbox-v<version>/core_ai_session-<version>-py3-none-any.whl`), `sdk/core-ai-session/contract-fixtures/` (payloads shared by the hub, both CLIs and the SDK tests; `recorded/` holds real, anonymised CLI exchanges), `sdk/core-ai-session/tests/` (SDK suite + the live and recording helpers).

## Tool Arguments

### run_bash_command
```json
{
  "command": "ls -la",
  "workspace": "/workspace",
  "timeout": 120000
}
```
`timeout` is in milliseconds (default 120000, max 600000).

### read_file
```json
{
  "file_path": "/path/to/file",
  "offset": 0,
  "limit": 100
}
```

### write_file
```json
{
  "file_path": "/path/to/file",
  "content": "file content here"
}
```

### edit_file
```json
{
  "file_path": "/path/to/file",
  "old_string": "old text",
  "new_string": "new text",
  "replace_all": false
}
```

### glob_file
```json
{
  "path": "/path/to/search",
  "pattern": "**/*.java"
}
```

### grep_file
```json
{
  "path": "/path/to/search",
  "pattern": "class.*Test",
  "output_mode": "content",
  "head_limit": 50,
  "case_insensitive": false
}
```

## Build

```bash
# Build Docker image — context is the repository root, because the SDK lives in sdk/core-ai-session/
docker build -f core-ai-sandbox-runtime/Dockerfile -t core-ai-sandbox:latest .

# Or build Go binary directly
go build -o core-ai-sandbox-runtime main.go
```

## Testing

```bash
# Go: /bind, the loopback proxy, the sandbox CLI, and the shared hub contract fixtures
go vet ./... && go test ./...

# Python SDK: offline and credential-free; every transport is replayed against the same fixtures
python -m unittest discover -s sdk/core-ai-session/tests

# The SDK against a real, logged-in core-ai-cli (read-only unless --call is passed)
python sdk/core-ai-session/tests/verify_live_cli.py --tool google_gbp_get_reviews
```

`sdk/core-ai-session/contract-fixtures/recorded/` is the other half of a real contract: anonymised envelopes a real
CLI printed against a real hub, which `test_recorded.py` re-serves to the SDK through
`replay_cli.py`. Recording a fresh set is two commands (the first is read-only: it lists, describes
and makes one harmless call), and curation fails loudly rather than writing a fixture it cannot
redact:

```bash
python sdk/core-ai-session/tests/record_live_cli.py --record /tmp/exchanges
python sdk/core-ai-session/tests/curate_recordings.py --from /tmp/exchanges --out sdk/core-ai-session/contract-fixtures/recorded
```

CI runs the first two on any change under `core-ai-sandbox-runtime/` (`.github/workflows/sandbox-check.yml`). Bumping `VERSION` builds the image and the matching SDK wheel, published as `sandbox-v<version>` (`.github/workflows/sandbox-build.yml`) — and only a bump does, so an SDK change that should reach skill scripts needs one.

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| PORT | 8080 | HTTP server port (tools API; also the server-facing `/bind`) |
| HUB_PORT | 8081 | Loopback-only session-hub proxy port, advertised to scripts as `CORE_AI_HUB` |
| WORKSPACE_DIR | /workspace | Base directory for file operations |

## Security

- Runs as non-root user (UID 1001)
- Restricted PATH; the session token lives in process memory only — scripts get `CORE_AI_HUB` (loopback) and never a credential
- The hub proxy listens on `127.0.0.1` only; `:8080` stays as trusted as it was (whoever can call `/execute` can already run code here)
- All file operations are restricted to workspace directory
- Path traversal attacks are blocked (../ is not allowed)
- No network access by default (depends on container configuration)
- Read-only filesystem recommended (configured by Kubernetes/Docker)
