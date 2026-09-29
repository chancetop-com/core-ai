# core_ai_session — API reference

Everything in the package, as the code defines it. For the task-level walkthrough see `../SKILL.md`;
for the authoring loop see `authoring.md`.

```python
from core_ai_session import session            # sync
from core_ai_session import async_session      # async
from core_ai_session.testing import FakeSession
```

## 1. Sessions and transports

`session()` / `async_session()` build a session for **this** environment — a sandbox script never
picks a transport by hand:

| Transport | Class | Chosen when | Calls run as |
|---|---|---|---|
| sandbox hub | `Session` / `AsyncSession` | `CORE_AI_HUB` is set (the runtime sets it) | the agent session's caller |
| local CLI | `CliSession` / `AsyncCliSession` | `CORE_AI_HUB` is not set | your own core-ai-cli login |
| offline | `FakeSession` | constructed explicitly | nobody — scripted answers |

```python
session(hub_url=None, *, timeout=120, script=None, backend=None, cli=None, session_id=None)
async_session(...)   # same arguments; loads the catalog before returning
```

* `backend="hub"` / `"cli"` (aliases: `http`/`session`/`sandbox`, `local`/`core-ai-cli`) names the
  transport outright. While `CORE_AI_HUB` is set, `backend="cli"` is **refused** — a sandbox script
  must not fall back to the reader's identity.
* `hub_url` overrides `CORE_AI_HUB`; `hub_url="cli"` is the shorthand for the local backend.
* `timeout` is the default per-call timeout, clamped to 1–600 s (`MAX_TIMEOUT`).
* `script` names the script in the `X-Core-AI-Script` header (sandbox only; the local backend accepts
  it and ignores it). Without it the SDK sends the running script's file name.
* `session_id` for a **local** run names the session `s.dataset` acts on. Inside a sandbox it is only
  a self-check: a mismatching id is an error, never a redirect.
* `cli` (local) points at the `core-ai-cli` executable, a path, or an argv prefix; the default comes
  from `CORE_AI_CLI`, else `core-ai-cli` on `PATH`.

Session objects:

| Member | Meaning |
|---|---|
| `s.backend` | `"hub"`, `"cli"` or `"fake"` |
| `s.session_id`, `s.agent_name` | filled by `me()` / `catalog()`; empty locally |
| `s.hub_url` | the hub URL in use (hub transport) |
| `s.poll_interval` | seconds between task polls — 2 s (hub), 1 s (CLI), 1 ms (fake) |
| `s.is_async()` | whether this session is the async flavour |
| `s.close()` / `s.aclose()` | release the HTTP client / nothing (the CLI keeps no state) |
| `with session() as s:` / `async with async_session() as s:` | close on exit (async also loads the catalog) |
| `s.catalog_gaps` | (local only) sources the last enumeration could not list fully |

The catalog is fetched lazily on first use; opening a session is cheap. An `AsyncSession` namespace
needs the catalog loaded first (`await async_session()`, the `async with` form, or `await s.refresh()`).

## 2. Discovery

```python
info    = s.me()          # SessionInfo
catalog = s.catalog()     # Catalog (cached); s.refresh() re-reads it
hits    = s.tools("reviews", kind="mcp")      # list[ToolSummary], search across name/path/description
detail  = s.describe("google-gbp/list_reviews")  # ToolDetail — name, path or ref id
entry   = s.find_entry("google-gbp/list_reviews")  # ToolSummary | None (hidden tools answer None)
fn      = s.tool("google_gbp_list_reviews")   # a callable taking **kwargs, described by its docstring
```

`SessionInfo`: `session_id`, `agent_name`, `sandbox_id`, `sandbox_state`, `expires_at`, `tool_count`,
`contract_version`, `caller` (a dict — e.g. `{"external_id": …}` or the local
`{"source": "local-cli", "user": …}`).

`Catalog`: `session_id`, `agent_name`, `sandbox_id`, `sandbox_state`, `expires_at`, `groups`,
`tools`, `datasets`, plus `by_kind(kind)`, `find(name_or_ref)` (matches name, `ref_id` or path) and
`names()`.

`ToolSummary`: `name`, `kind` (`mcp`/`api`/`llm_call`/`agent`/`builtin`), `group`, `path`, `ref_id`,
`description`, `exposure` (`direct`/`deferred`/`hidden`), and `.callable` (`exposure != "hidden"`).
`ToolDetail` adds `input_schema` (the tool's JSON schema, validated locally before a call) and
`timeout_seconds`.

## 3. Namespaces

`Node` objects resolve lazily against the catalog: attribute and item access walk the path,
`-` and `_` are interchangeable, an unknown key raises `ToolNotFoundError` with suggestions, and a
group that is called as a function says so instead of failing obscurely.

```python
s.mcp["google-gbp"]["list_reviews"](location="…")   # <server>/<tool>
s.api["order-service"]["reviews"]["get_reviews"](…) # <app>/<service>/<operation>
s.llm_call["seo-title-semantics"](query="…")        # LLM_CALL definition; query (+ optional image_url)
s.agent["review-responder"].run("reply to r-1", wait=False)
s.builtin.web_search(query="…")                     # the agent's non-sandboxed builtins
```

* `s.agent[name].run(task, wait=True, timeout=None, **kwargs)` passes `task` as the tool's `query`; a
  sub-agent call defaults to a 300 s timeout (`DEFAULT_AGENT_TIMEOUT`).
* Every node is one tool: `s.mcp["x"]["y"]` and `s.call("x/y", {…})` reach the same call.

## 4. Calling

```python
s.call(name, arguments=None, **kwargs)   # name = catalog name, path or ref id
```

* Arguments follow the tool's own `input_schema`; missing required arguments and wrong types are
  refused **locally** (against `describe`'s schema) with no round trip.
* `timeout`, `wait`, `wait_timeout` are the SDK's controls — unless the tool's schema declares a
  parameter with that name, in which case it belongs to the tool (`run_bash(timeout=5000)` stays the
  sandbox bash timeout).
* `wait=False` returns a `Task` instead of blocking when the call is still pending.
* Local calls cap `timeout` at 300 s (`core-ai-cli`'s limit) with one warning; the work keeps running
  and the call comes back as a `Task`.

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_TIMEOUT` | 120 s | per-call default |
| `MAX_TIMEOUT` | 600 s | hub maximum (a sandbox can wait longer than the CLI) |
| `DEFAULT_AGENT_TIMEOUT` | 300 s | sub-agent calls without an explicit timeout |
| `DEFAULT_WAIT_TIMEOUT` | 1800 s | how long `wait=True` blocks on a pending task before raising |

## 5. Results and tasks

`ToolResult`: `call_id`, `status` (`completed`/`failed`/`pending`), `text` (the tool's text output),
`content` (`list[ContentPart]`), `duration_ms`, `task_id`, `llm_usage`, `is_error`, `error_code`,
`error_message`, `status_code` (upstream HTTP status for API tools; the hub status for rejected
calls). `.data` parses `text` as JSON when it is JSON, else `None`; `str(result) == result.text`.

`ContentPart`: `type`, `text`, `mime_type`, `data` (base64 for image parts).
`LlmUsage`: `model`, `input_tokens`, `output_tokens`, `cost` (when the tool reports them).

```python
task = s.call("long_tool", {…}, wait=False)
task.status            # "pending"
task.poll()            # one poll; sets .status and .result when it finishes
task.wait(600)         # ToolResult, or ToolError(504) if it is still pending after the wait
```

`Task.wait()` raises `ToolError` when the finished run failed, and returns a `pending` result when the
deadline passed. An `AsyncSession` cannot block on a task: use `wait=False` and poll again.

## 6. Errors

```text
CoreAiSessionError
├── NotBoundError       no usable credentials / the sandbox is not bound to a session (retried 3× on 503)
├── ToolNotFoundError   not in this session's catalog, or the session/dataset does not exist
└── ToolError           the tool ran and failed, or the hub rejected the call
```

`ToolError` carries `message`, `tool`, `kind`, `status_code`, `call_id`, and `task_id` (when a
timeout left work running). Hub rejections map as 401 → `NotBoundError`, 404 → `ToolNotFoundError`,
403/408/429/504 → `ToolError`, anything else → `CoreAiSessionError`. The CLI exit codes map the same
way (1 tool error, 3 unauthenticated, 4 forbidden, 5 not found, 6 timeout), so a script that catches
these three writes the same code for both transports.

## 7. Files

```python
url = s.files.publish("/workspace/report.html", title="Weekly report",
                      name="report.html", content_type="text/html",
                      description="…", public=False)   # -> download_url
```

Publishes through the session's `submit_artifacts` builtin and returns the URL to hand to another
system. `public=True` stores the file in public object storage (a permanent URL); without it the
artifact keeps the platform share link. `submit_artifacts` does not exist locally, so a local call
raises `ToolNotFoundError` — that is the transport, not the script.

## 8. Datasets

`s.dataset` reaches the datasets the session's agent mounts. One dataset is one **binding**: an id
plus the permission this session holds (`READ`/`WRITE`/`FULL`), both decided by the agent definition.

```python
s.dataset.list()                 # list[DatasetInfo] — id, name, type, permission, description, schema
node = s.dataset["menu-state"]   # by name when unique, by id always; ambiguity lists the candidate ids
node.dataset_id, node.name, node.type, node.permission, node.schema, node.info
```

| Dataset type | Methods | Notes |
|---|---|---|
| `SESSION` | `state(fields=None)`, `set(data)`, `patch(data)` | one state document per session, ≤256 KB; `state()` is `None` when this session never wrote one |
| `GENERAL` | `records(filter=None, *, fields=None, from_=None, to=None, limit=None, offset=None)`, `insert(data)`, `update(record_id, data)`, `delete(record_id)` | one record per call, newest first; `limit` default 100, max 1000; a filter scans the most recent 10 000 records and warns past that |

* `insert`/`set`/`patch`/`update` accept an object or its JSON text; `records(filter=…)` accepts a
  dict or its JSON text; `fields` accepts `"a,b"` or `["a", "b"]`.
* Writes need `WRITE` (or `FULL`); `delete` needs `FULL`. The checks run before the round trip and
  use the server's own sentences; `records()` logs the server's truncation warning.
* `node.op(name, **kwargs)` returns the raw payload envelope — `total` for paging,
  `updated_fields`/`inserted_fields` for what a write touched. Operation names: `state.get`,
  `state.set`, `state.patch`, `records.query`, `records.insert`, `records.update`, `records.delete`.
* A **local** run must name the session (`session_id=`, `CORE_AI_SESSION_ID`, `--session`); inside a
  sandbox the token already does. Each operation is one call to the matching dataset tool, so the
  permission, error text and payload are identical to the agent's own.

## 9. Testing (`core_ai_session.testing`)

`FakeSession` shares the whole call path (namespaces, validation, dataset checks, result and error
mapping) with the real transports; only the transport is scripted.

```python
fake = FakeSession(catalog={...}, details=[{...}])       # payload shapes as /catalog and /tools answer
fake = FakeSession.from_fixture("catalog.json")          # a source checkout's contract-fixtures

fake.mcp["google-gbp"]["list_reviews"].returns({"reviews": [...]})   # JSON-serialized into .text
fake.llm_call["seo"].raises(ToolError("model overloaded"))           # the last behaviour repeats
fake.files.returns("https://files.example/report.html")              # a publish answer
fake.dataset.returns({"status": "created"}, op="records.insert")     # the payload the server would send

fake.calls                    # list[FakeCall]: tool, kind, arguments, timeout, wait, ref_id
fake.calls_of("…")
fake.dataset_calls            # list[FakeDatasetCall]: op, tool, dataset_id, arguments
fake.dataset_calls_of("records.insert")
fake.script_task("task-1", {"status": "completed", "text": "…"})     # serve one Task poll
```

The fake raises `CoreAiSessionError` for a tool with no scripted answer — a test should never look
like a success by accident. It has no `tools()` / `refresh()` round trips: a test for search or for
task-over-the-wire behaviour drives a real `Session`/`AsyncSession` over `httpx.MockTransport`
(`scripts/tests/test_scripts.py` shows both styles).

## 10. Local-transport details (`CliSession`)

* Needs `core-ai-cli` ≥ 2.0.10 (the `skill`/`api-tool`/`agent` subcommands) and a login; the error
  names the installed version when a command predates a hub.
* One subprocess per call: fine for scripts and CI, not for tight loops. Long arguments travel on
  stdin (`--args-file -`, `--task-file -`) — nothing long ever goes into argv.
* The catalog is one call when the CLI has `catalog` (≥ 2.0.19): the server answers everything the
  user can reach in a single request, the same shape a sandbox script gets from the session hub — no
  per-server fan-out. An older CLI cannot answer it, and the SDK rebuilds the catalog one listing per
  MCP server and per API app (correct, just a process start per source); the attempt is remembered,
  so a session never pays for it twice.
* Sources the server could not refresh appear in `catalog_gaps` as stale snapshots, and a kind these
  credentials cannot read is reported there too — the one-shot answer says which kinds it covers.
* `server=` / `api_key=` override the login for one session (equivalent to `--server` / `--api-key`).
* `record=DIR` (or `CORE_AI_CLI_RECORD`) captures every exchange as `NNNN-<leaf>.json` for fixtures
  and bug reports; it is off by default because a response may contain business data.
* The local catalog is best effort: each source is listed separately, retried once, and a source that
  fails or shrinks is kept but named in `s.catalog_gaps`. The sandbox catalog is authoritative.
* `s.me()` locally is a stand-in (`sandbox_state="local"`, `caller.source="local-cli"`); one warning
  is logged the first time a local session is opened.

## 11. Contract and versioning

* The catalog is the whole contract: no tool name, server name or schema is baked into the SDK, so an
  agent's mount change shows up on the script's next `session()` without an SDK release.
* `me()` reports the hub's `contract_version`; a major mismatch logs a warning (upgrade the sandbox
  image or pin an older SDK). This SDK speaks contract `1.x`.
* `sdk_version()` returns the installed package version; every request carries
  `X-Core-AI-Client: sdk-python/<version>`, which is how the server attributes a script's calls.
