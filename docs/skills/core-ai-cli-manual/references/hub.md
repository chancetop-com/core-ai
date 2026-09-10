# core-ai Hub: Using Server-Side Tools, Skills, and Agents from the CLI

core-ai-server is the central registry for MCP tools, skills, API tools, and agents. `core-ai-cli` exposes each registry as an argv subcommand family that any agent or script can call from a shell. These subcommands are **pure HTTP clients**: no LLM, no local MCP, no agent session is started. Cold start is fast and output is machine-readable with `--json`.

Pattern shared by every hub kind:

```
core-ai-cli <kind> search "<what you need>" --json      # discover (names + descriptions only)
core-ai-cli <kind> describe|show <group>/<name> --json  # inspect (schema or full content)
core-ai-cli <kind> call|run|pull <group>/<name> ...     # act
```

Never guess names. Always search, then inspect, then act.

Prerequisites: the `core-ai-cli` binary on PATH (see "Installing core-ai-cli" in SKILL.md) and a login or `CORE_AI_SERVER` + `CORE_AI_API_KEY`.

## Availability

| Kind | Subcommand | Status | Section |
|------|-----------|--------|---------|
| MCP tools | `core-ai-cli mcp …` | **Available** | MCP Hub |
| Skills | `core-ai-cli skill …` | **Available** (CLI ≥ 2.0.8) | Skill Hub |
| API tools | `core-ai-cli api-tool …` | **Available** (CLI ≥ 2.0.8) | API-Tool Hub |
| Agents | `core-ai-cli agent …` | **Available** (CLI ≥ 2.0.10) | Agent Hub |

If your installed CLI reports an unknown subcommand (exit code 2), it predates that hub — upgrade with `core-ai-cli upgrade`, or fall back to the Web UI.

## Shared Conventions

### Authentication

Credentials resolve in this order; the first complete pair wins:

1. Flags `--server <url>` and `--api-key <key>` (accepted by every hub subcommand)
2. Environment variables `CORE_AI_SERVER` and `CORE_AI_API_KEY`
3. `~/.core-ai/auth.json`, the active entry written by `core-ai-cli --login` or the REPL `/login`

If only `--server` is given, the key for that URL is looked up in `auth.json`. Hub commands never write to `auth.json`.

Not logged in: `core-ai-cli --login [server-url]` opens the browser flow (or accepts a pasted API key) and exits.

### Global options (every hub subcommand)

| Option | Effect |
|--------|--------|
| `--json` | Single-line JSON on stdout; errors also go to stdout as `{"error":{"code","message","status"}}` |
| `--raw` | For `call`: print only the tool's text content (pipe-friendly) |
| `--quiet` | Suppress progress and metadata on stderr |
| `--server URL` | Override server |
| `--api-key KEY` | Override key |

Human mode prints tables to stdout and metadata (duration, warnings, saved file paths) to stderr.

### Exit codes

| Code | Meaning | What an agent should do |
|------|---------|------------------------|
| 0 | Success (`call`: tool returned without `is_error`) | Parse stdout |
| 1 | Tool reported a business error (`is_error=true`), or server 5xx / MCP connection failure | Read the message; retry only if transient |
| 2 | Usage error: bad flags, invalid JSON, `--arg` without `=`, unknown subcommand | Fix the command |
| 3 | Not authenticated (401 or no credentials) | Ask the user to run `core-ai-cli --login` |
| 4 | Permission denied (403) | Stop and tell the user; do not retry |
| 5 | Server, tool, skill, or agent not found (404) | Re-run `search`; the name may be wrong or not visible to you |
| 6 | Timeout (client-side or server 504); for `agent run`, the wait limit was hit and the task is still running | Raise `--timeout`, split the work, or poll with `agent status` |
| 7 | `agent run` only: the agent needs input | Inspect `input_request` and answer with `agent reply` |

### Naming

Everything is addressed as `{group}/{name}`: `{server}/{tool}` for MCP tools, `{namespace}/{skill}` for skills. Responses also carry a stable identifier (`ref_id` for tools, `id` for skills); prefer it in stored configuration because display names can be renamed.

### Search results

Search returns two levels: the groups that matched (with a match count) and a diversified list of items, at most 3 per group by default. When a group shows `(+N more, --on-server X)` (or the equivalent `--namespace` for skills), drill in with that flag to see the rest.

## MCP Hub (`core-ai-cli mcp`)

Search and execute MCP tools registered on core-ai-server. Requires the `mcp.call` permission; by default only admins have it until an admin grants it to the `user` role.

| Command | Purpose |
|---------|---------|
| `core-ai-cli mcp servers` | List MCP servers visible to you: name, description, category, connection state, tool count, `stale` flag |
| `core-ai-cli mcp search [query] [--on-server S] [--limit N]` | Search the tool catalog. Omit query to list everything. `--limit` default 20, max 200 |
| `core-ai-cli mcp describe <server>/<tool>` | One tool's description and `input_schema`. Read this before calling |
| `core-ai-cli mcp call <server>/<tool> [args…]` | Execute the tool on the server |
| `core-ai-cli mcp status [server]` | Connection state and tool counts (detailed form of `servers`) |
| `core-ai-cli mcp instructions [--format md\|claude\|codex]` | Print a paste-ready snippet for CLAUDE.md / AGENTS.md |

### Passing arguments to `call`

| Option | Use |
|--------|-----|
| `--args '<json object>'` | Whole argument object as JSON |
| `--args-file <path>` | Read the JSON object from a file; `-` reads stdin |
| `--arg key=value` (repeatable) | Single argument. Values are coerced to `integer`/`number`/`boolean`/`array`/`object` using the tool's `input_schema`; unparseable values stay strings with a warning |
| `--timeout <sec>` | Server-side wait limit, default 60, max 300 |
| `--out-dir <dir>` | Where image content is saved (default: current directory); the path is printed on stderr |
| `--max-output <n>` | Truncate printed text after n chars (default 65536); a truncation notice is appended |

Sources merge in the order `--args`, then `--args-file`, then `--arg` overrides.

### Call response (`--json`)

```json
{"call_id":"…","success":true,"is_error":false,
 "content":[{"type":"text","text":"…"}],
 "text":"…","duration_ms":812,"server_state":"CONNECTED"}
```

`is_error=true` means the MCP tool itself reported a failure (HTTP 200, exit 1). Transport or connection failures come back as HTTP 502 with `server_state` (exit 1). Image parts are written to disk, not printed.

### Examples

```bash
core-ai-cli mcp search "jira issue" --json
core-ai-cli mcp describe jira/create_issue --json
core-ai-cli mcp call jira/create_issue --arg project=CORE --arg summary="SSE timeout" --raw
echo '{"project":"CORE","summary":"x"}' | core-ai-cli mcp call jira/create_issue --args-file - --json
core-ai-cli mcp search google --on-server google-gbp      # drill into one server, no per-server cap
```

### Relationship to local MCP

`core-ai-cli mcp …` talks to servers registered on core-ai-server. It does not see the local MCP servers configured in `mcp.servers.json` / `<workspace>/.core-ai/MCP.json`; those belong only to the local agent (see [mcp.md](mcp.md)). Credentials for hub servers live on the server; the CLI holds only your API key.

## Skill Hub (`core-ai-cli skill`)

Server-side skills (`SKILL.md` + resources, the same format Claude Code and Codex use) become discoverable and installable from the shell. Permission: `skill.view` (the default `user` role has it); `push` needs `skill.manage`.

| Command | Purpose |
|---------|---------|
| `skill search <query> [--namespace NS] [--source upload\|repo] [--limit N]` | Search by name, namespace, description, allowed-tools |
| `skill show <ns>/<name> [--raw]` | Metadata + SKILL.md body. `--raw` prints only the body: feed it straight into your context and follow it, no install needed |
| `skill pull <ns>/<name> [--workspace \| --to DIR] [--force]` | Install. Default `~/.core-ai/skills/<ns>/<name>/`; `--workspace` writes `<workspace>/.core-ai/skills/`; `--to .claude/skills` targets Claude Code |
| `skill list [--workspace]` | Local skills with state: `up-to-date`, `outdated`, `modified`, `local` |
| `skill update [<ns>/<name>… \| --all]` | Re-pull outdated skills; skips locally modified ones unless `--force` |
| `skill remove <ns>/<name>` | Delete a local copy |
| `skill push <dir> [--namespace NS]` | Upload a local skill directory to the server |
| `skill instructions` | Paste-ready snippet |

A bare name without `/` is accepted when it is unique on the server; if ambiguous the CLI exits 2 and lists candidates. Pulled skills carry a `.skill-hub.json` marker (id, digest, server) so `list`/`update` can detect server-side changes.

Inside the REPL, `/skill` still opens the interactive menu for the same operations, and `/skill <name>` loads a local skill into the conversation.

## API-Tool Hub (`core-ai-cli api-tool`)

API tools are internal Service APIs imported into the server from core-ng applications (the same ones exposed at `/api/api-tools/mcp`). One app contains services, each with operations; the operation is the callable unit. Permission: `apitool.call`.

| Command | Purpose |
|---------|---------|
| `api-tool apps` | Visible apps with service and operation counts |
| `api-tool search <query> [--on-app APP] [--service SVC] [--limit N]` | Search operations by app, service, operation name, description, HTTP method, path |
| `api-tool describe <app>/<service>/<operation>` | Method, path, `input_schema`, `output_schema`, example. Also accepts the function form `app_service_operation` |
| `api-tool call <app>/<service>/<operation> [--args JSON \| --args-file F \| --arg k=v …] [--timeout SEC] [--max-output N]` | Execute; same argument flags as `mcp call`. Response adds `status_code`; a backend 4xx/5xx is `is_error=true` with exit 1 |
| `api-tool instructions` | Paste-ready snippet |

Names have three segments (`{app}/{service}/{operation}`). Calls carry your identity to the backend as caller headers, so data-scoping is enforced there.

## Agent Hub (`core-ai-cli agent`)

Run published server-side agents as delegates, replacing the older `delegate_to_remote_agent` A2A path. Only agents that are published (or your own drafts, plus the system default) are visible; the catalog exposes capability summaries, never system prompts or tool lists. Permission: `chat.use`.

| Command | Purpose |
|---------|---------|
| `agent search [query] [--type agent\|llm_call] [--source server\|external] [--limit N]` | Visible agents: id, name, type, description, skills, sub agents |
| `agent show <id \| name>` | Capability summary (id, type, status, source, skills, sub agents, input hint) |
| `agent run <id \| name> --task "…"` | Execute and wait. Options: `--task-file F\|-`, `--context-id ID`, `--attach URL` (repeatable), `--timeout SEC` (default 120, max 300), `--detach`, `--max-output N`, `--json` |
| `agent status <task_id>` | Poll a run that returned `running` (cheap; no new turn) |
| `agent reply <task_id> --approve \| --deny \| --message "…"` | Answer an `input_required` run (tool approval or missing information); resumes the same conversation |
| `agent cancel <task_id>` | Cancel a run |
| `agent instructions [--format md\|claude\|codex]` | Paste-ready snippet |

Result shape (`--json`): `{task_id, context_id, status, output, input_request?, token_usage, duration_ms}`, status ∈ `completed | failed | cancelled | input_required | running`.

Status and exit codes: `completed` → 0, `failed`/`cancelled` → 1, `running` (wait limit hit, task continues) → 6, **`input_required` → 7**. Exit 7 is not a failure: read `input_request`, then `agent reply`. Reuse `context_id` on a later `run` to continue the same conversation. Keep `--task` self-contained; the agent cannot see your local files (pass a URL with `--attach` instead). A bare name is accepted when unique among visible agents; otherwise the CLI exits 2 and lists candidate ids.

```bash
core-ai-cli agent search "code review" --json
core-ai-cli agent run code-reviewer --task "review https://github.com/org/repo/pull/12" --json
core-ai-cli agent run <agent-id> --task-file - --context-id <context_id> < spec.md   # continue a conversation
core-ai-cli agent reply <task_id> --approve --json
```

## Snippet for other agents

Every hub prints the same merged snippet (`mcp | skill | api-tool | agent instructions`, `--format` only changes the heading). Current content:

```text
## Company tools & skills (core-ai Hub)  # paste into CLAUDE.md
Tools:  core-ai-cli mcp search "<what you need>" --json
        core-ai-cli mcp describe <server>/<tool> --json      # read input_schema before calling
        core-ai-cli mcp call <server>/<tool> --args '<json>' --json
Do NOT guess tool names.
API tools: core-ai-cli api-tool search "<need>" --json -> api-tool describe <app>/<svc>/<op> --json -> api-tool call <app>/<svc>/<op> --args '<json>' --json
Exit 1 with status_code >= 400 = the backend rejected the request; read "text" for the error body.
Skills: when a task matches a known workflow, first check for a skill:
  1. Discover: core-ai-cli skill search "<topic>" --json
  2. Read:     core-ai-cli skill show <namespace>/<name> --raw   # prints SKILL.md; follow its instructions
  3. Optional: core-ai-cli skill pull <namespace>/<name> --to .claude/skills   # install for reuse
Agents: when a sub-task fits a specialist better than you, delegate:
  1. core-ai-cli agent search "<capability>" --json
  2. core-ai-cli agent run <id> --task "<self-contained task with all context>" --json
     exit 0 = done (read "output"); exit 7 = it needs input: inspect "input_request", then
     core-ai-cli agent reply <task_id> --approve|--deny|--message "..." --json;
     exit 6 = still running: core-ai-cli agent status <task_id>
  3. Continue the same conversation with --context-id <context_id>.
  Never send local files wholesale; send the excerpt the agent needs.
Exit code 0 = success; parse stdout as JSON. On 4 (permission) stop and tell the user.
```

## Inside a core-ai sandbox (`core-ai-sandbox`) — planned

Code running inside a server agent's sandbox (skill scripts, ad-hoc python) can use **exactly what the agent has configured**: its MCP servers, API tools, LLM_CALL definitions, sub-agents, and non-sandboxed builtin tools. There is no raw LLM endpoint; to use an LLM from a script, the agent author attaches an LLM_CALL definition (prompt, schema, model) and the script calls it. Scripts hold no credentials: the runtime exposes a loopback proxy at `CORE_AI_HUB` (`http://127.0.0.1:8081/hub`) and attaches the session token itself.

- Python: `from core_ai_sandbox import session; s = session()` then `s.mcp["google-gbp"].get_reviews(location=...)`, `s.api["<app>"].<service>.<operation>(...)`, `s.llm_call["<definition>"](query=...)`, `s.agent["<name>"].run("...")`, `s.tool("<function name>")(...)`. Results are `ToolResult` (`.text`, `.data`); failures raise `ToolError`. `s.catalog()` shows everything available.
- bash: `core-ai-sandbox catalog|tools|describe|call` (a Go CLI built into the runtime, not `core-ai-cli`), same `--json` envelope and exit codes as the hub commands above.
- Plain HTTP: `POST $CORE_AI_HUB/tools/<function name>/call` with `{"arguments": {...}}`, no auth header needed.

Calls carry the session user's caller headers automatically, run through the session's own tool executor, and count against the same quota and traces. This is separate from the user-level hubs: `core-ai-cli` is not present in the sandbox, and the session token is rejected outside `/api/sandbox-hub/*`. Design: `docs/cn/design-sandbox-hub.md`.

## MCP-native alternative

Editors and agents that prefer MCP over a shell will be able to point one MCP server at `<server>/api/mcp-hub/mcp` with header `Authorization: Bearer <api key>` (planned; exposes `search_tools`, `describe_tool`, `call_tool`, later `search_skills`/`get_skill`). Today the shipped MCP endpoint is `<server>/api/api-tools/mcp` (API tools only).
