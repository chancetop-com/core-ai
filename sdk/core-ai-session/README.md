# core-ai-session

**One skill script, two environments.** A script written against this SDK runs unchanged on a
workstation where `core-ai-cli` is installed *and* inside a core-ai-server sandbox — the same file,
no branches, **and no credentials in the script**:

| Where the script runs | Transport | Calls run as | What the script holds |
| --- | --- | --- | --- |
| inside a sandbox (`CORE_AI_HUB` set) | `Session` → the runtime's loopback hub → `/api/sandbox-hub/*` | the agent session's caller — same session state, quota and traces as the agent's own tool calls | nothing: the runtime injects the session token and **replaces any `Authorization` header the script sends** |
| on a workstation (`CORE_AI_HUB` unset) | `CliSession` → `core-ai-cli … --json` | you — your own CLI login, permissions, quota, traces | nothing: the CLI holds your login |
| offline tests | `FakeSession` | nobody — in-process contract fixtures | nothing |

Scripts that carry tokens are the anti-pattern this package exists to delete: credentials leak into
shared skill packages, break on rotation, and quietly change identity when a script moves from a
laptop into a pod.

`CORE_AI_HUB` always wins: there is no silent fallback to the local identity, and inside a sandbox
`backend='cli'` is refused outright. Design: `docs/cn/design-sandbox-hub.md`; a complete worked skill
(authoring guide, runnable examples, offline tests, copy-me template):
`docs/skills/core-ai-session-sample/`.

## What it reaches

Everything the session's agent is configured with, and nothing else — the session's catalog is the
contract. No tool name, server name or schema is baked into the SDK, so an agent's mount change shows
up on the script's next `session()` without an SDK release.

| Kind | Python | What it is |
| --- | --- | --- |
| `mcp` | `s.mcp["<server>"]["<tool>"](…)` | the session's MCP servers |
| `api` | `s.api["<app>"]["<service>"]["<operation>"](…)` | Service API operations; the caller identity is forwarded to the backend |
| `llm_call` | `s.llm_call["<definition>"](query=…)` | LLM_CALL definitions — the only LLM path a script has |
| `agent` | `s.agent["<name>"].run("…")` | sub-agent delegation (usually long-running) |
| `builtin` | `s.tool("<name>")(…)` | the agent's non-sandboxed builtin tools |
| files | `s.files.publish(path, title=…)` | publish a file as a session artifact, returns its URL |
| datasets | `s.dataset["<name>"].state()/insert()/…` | the datasets this session's agent mounts |

`s.call("<tool path>", {…})` reaches any of them; the namespaces are the readable form.

## Install

- **Inside the sandbox image: preinstalled system-wide, zero setup.** A script in a sandbox does not
  install anything.
- **On a workstation** — no PyPI package; install from the release wheel that matches the sandbox
  runtime version you target:

  ```bash
  pip install https://github.com/chancetop-com/core-ai/releases/download/sandbox-v1.0.48/core_ai_session-1.0.48-py3-none-any.whl
  ```

  (each `sandbox-v<version>` release attaches `core_ai_session-<version>-py3-none-any.whl`).
- **In a repository checkout** — run this from the repository root:

  ```bash
  pip install -e sdk/core-ai-session
  ```

The wheel is built with the runtime image and carries the same version number as
`core-ai-sandbox-runtime/VERSION`. That file is the only trigger: bumping it rebuilds the image and
republishes the wheel, so an SDK change that should ship needs a bump.

## Quick start

```python
from core_ai_session import session, ToolError, ToolNotFoundError, NotBoundError

s = session()                       # sandbox: the session hub; workstation: core-ai-cli; tests: fake

info = s.me()                       # session_id, agent_name, sandbox_state, tool_count, caller
s.tools("review", kind="mcp")       # search the catalog — discover, never guess
s.describe("google-gbp/list_reviews")   # input_schema, description, timeout

result = s.mcp["google-gbp"]["list_reviews"](location="ChIJ…", limit=5)
result.text                         # the tool's text output
result.data                         # text parsed as JSON when it is JSON, else None
result.duration_ms, result.call_id, result.llm_usage

summary = s.llm_call["seo-title-semantics"](query=result.text[:2000])
url = s.files.publish("/workspace/report.html", title="Weekly report")     # public=True for a permanent URL
```

Long calls come back as a task instead of blocking: `t = s.call(name, args, wait=False)` →
`t.poll()` / `t.wait(600)`. `AsyncSession` (`await async_session()`) mirrors the same API with `await`,
and `session()` works as a context manager (`with session() as s:`).

Failures always raise, so a script cannot continue on a half-filled result:
`ToolError` (the tool ran and failed), `ToolNotFoundError` (not in this session's catalog),
`NotBoundError` (no credentials, or the sandbox is not bound yet), `CoreAiSessionError` (everything
else). They map one-to-one onto the `core-ai-sandbox` CLI exit codes (1, 5, 3) and the local
`core-ai-cli` ones, so the same `except` block works in both environments.

```python
try:
    s.mcp["jira"]["create_issue"](project="CORE", summary="…")
except ToolError as error:          # the tool ran and failed: message, status_code, call_id, task_id
    print(error.message, file=sys.stderr)
```

## Datasets

`s.dataset` reaches the datasets this session's agent mounts — one state document per session for a
`SESSION` dataset, one record per call for a `GENERAL` one. The same script works in both places:

```python
from core_ai_session import session

s = session()                                 # sandbox: the session hub; locally: core-ai-cli
menu = s.dataset["menu-state"]                # by name when unique, by id always
menu.state(), menu.patch({"menuPublished": True})

log = s.dataset["review-log"]
log.insert({"review_id": "r-1", "replied_at": "2026-09-20T10:00:00Z"})
log.records(filter={"review_id": "r-1"}, limit=10)     # newest first; .op() keeps total/offset
```

- **The binding decides everything.** `s.dataset.list()` gives each dataset's id, name, type,
  permission and schema; a name the session may not write is refused locally, in the server's own
  words, before any round trip.
- **Types are strict.** `state()/set()/patch()` only on `SESSION`, `records()/insert()/update()/delete()`
  only on `GENERAL`; both are rejected client-side with the server's message, not by trial and error.
- **Ambiguity is refused.** Two bindings sharing a name is an error listing the candidate ids —
  pass the canonical id. A local run must also name its session (`session(session_id="…")`,
  `CORE_AI_SESSION_ID`, or `--session`); inside a sandbox the session token already does.

The shell forms are `core-ai-cli dataset …` locally and `core-ai-sandbox dataset …` inside a sandbox;
all three drive the same tools, so the same refusal reads the same wherever a script runs.

## Testing

`FakeSession` replays a catalog you hand it and records every call, so the same script can be tested
without credentials or network:

```python
import json
from core_ai_session.testing import FakeSession

fake = FakeSession(catalog=json.load(open("catalog.json")))   # exported from a real session
fake.mcp["google-gbp"]["list_reviews"].returns({"reviews": [...]})
run_audit(session=fake)                                       # the script takes the session as a parameter
fake.calls                                                    # assert the sequence and the arguments
```

Export a real catalog per agent with the sample skill's `scripts/export_catalog.py` (or
`core-ai-sandbox catalog --json > catalog.json`) so the fake exposes the same namespaces as
production. Inside this repository `FakeSession.from_fixture("catalog.json")` reads the checked-in
`contract-fixtures/`; an installed wheel ships the package but not those files.

```bash
python -m unittest discover -s tests             # offline, credential-free
python tests/verify_live_cli.py --tool <name>    # against your installed core-ai-cli
```

## What deliberately stays different locally

The script API is unified; the identity and the reach are not — and they say so loudly:

| | In a sandbox | Locally |
| --- | --- | --- |
| Catalog means | what the **agent mounts** | what **you can see**, enumerated best effort (`s.catalog_gaps`) |
| `s.me()` | session id, agent name, `contract_version` | a stand-in (`sandbox_state="local"`); one warning is logged |
| Identity | the session's caller; backend data scoping applies | you |
| `builtin` tools | `submit_artifacts`, `web_search`, … | absent — calling one raises `ToolNotFoundError` |
| Call timeout | up to 600 s | capped at 300 s: the call returns a `Task` and keeps running |
| Datasets | the token names the session | pass `session_id=` / `CORE_AI_SESSION_ID` / `--session` |
| `server` / `api_key` | n/a | a direct `CliSession(server=…, api_key=…)` / `AsyncCliSession(…)` for one session, or `CORE_AI_SERVER` / `CORE_AI_API_KEY`, which the CLI resolves itself |

The local backend needs `core-ai-cli` ≥ 2.0.10 and one `core-ai-cli --login`; point the SDK at
another binary with `CORE_AI_CLI=/path/to/cli`.

## Writing a skill with it

The SDK is meant to be used from skill scripts (`SKILL.md` + `scripts/`, the same format Claude Code
and Codex use). The rules:

1. **One import** — `from core_ai_session import session`. No `*_TOKEN` / `*_KEY` / `*_MCP_URL`, no
   hand-rolled HTTP, no vendor SDK.
2. **Discover, never guess** — search, read the schema, then call.
3. **Fail loudly** — map the exceptions onto exit codes and print the message.
4. **stdout is the answer, stderr is the story** — `--json` for agents, `-` for long arguments.

`docs/skills/core-ai-session-sample/` holds the complete worked sample: four runnable scripts
(discovery, an end-to-end read→digest→save→publish pipeline, async fan-out, catalog export), an
offline test suite (including an `httpx.MockTransport` hub), the full API reference, the authoring
guide, and a copy-me template. Publish a skill with
`core-ai-cli skill push <dir>` — see `docs/skills/core-ai-cli-manual/references/hub.md` for the hub
contract and `docs/skills/session-probe/` for a smaller skill built the same way.

## Layout

- `core_ai_session/` — the package (`session()`, namespaces, errors, task polling)
- `tests/` — the offline suite plus the live helpers (recording, verification)
- `contract-fixtures/` — the single source of truth for the hub wire format: the Go runtime
  (`core-ai-sandbox-runtime/hub_contract_test.go`), the Python SDK and the fake CLI all assert
  against the same bytes; `recorded/` holds real, anonymised `core-ai-cli` exchanges
