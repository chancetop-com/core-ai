---
name: core-ai-session-sample
description: Complete worked sample for writing skill scripts with the core_ai_session Python SDK. One script runs the same way on a workstation (through core-ai-cli, under your own login) and inside a core-ai-server sandbox (through the session hub, as the agent's session) with no credentials anywhere in the script. Use when writing, reviewing, testing or debugging a skill script that must reach the agent's MCP tools, Service API operations, LLM_CALL definitions, sub-agents, datasets or file publishing, or when learning the SDK and its call contract from runnable examples.
metadata:
  author: core-ai-team
  version: "1.0"
---

# core-ai-session: a complete sample skill

This directory is two things at once:

* a **worked example** — four runnable scripts, an offline test suite and a copy-me template, all built
  on the `core_ai_session` Python SDK;
* a **manual** — the contract a skill script must keep, the full API reference, and the
  write → test → publish loop.

Copy the directory, rename it, and you have a skill. Everything below is what the scripts do.

## The one promise: one script, two places, no credentials

A skill script that calls the agent's capabilities must produce the same result wherever it runs:

| Where the script runs | What `session()` reaches | Who the calls run as | What the script holds |
|---|---|---|---|
| a workstation (your laptop, CI) | `core-ai-cli … --json` | **you** — your own login (`~/.core-ai/auth.json`), permissions, quota, traces | nothing |
| inside a core-ai-server sandbox | `127.0.0.1:8081/hub` in the pod, then `/api/sandbox-hub/*` | the **agent session's caller** — same session state, quota and traces as the agent's own tool calls | nothing |
| an offline test | `FakeSession` | nobody | nothing |

That is the whole point of the SDK. `session()` reads the environment (`CORE_AI_HUB` is the sandbox's
marker) and picks the transport; the script never reads `*_TOKEN` / `*_API_KEY` / `*_MCP_URL`, never
imports a vendor SDK, and never talks HTTP by itself. Inside a sandbox the runtime injects the session
token and **replaces any `Authorization` header the script sends** — the token is not the script's to
hold, which is also why a sandbox can never be pointed at another session.

Scripts that hold credentials are the anti-pattern this SDK exists to delete: they leak into
marketplace packages, break on rotation, and silently run as the wrong identity once a script moves
from a laptop into a pod.

## Quick start

```bash
# inside a sandbox the package is preinstalled — nothing to install
# on a workstation, either install the released wheel (matches the sandbox runtime version):
pip install https://github.com/chancetop-com/core-ai/releases/download/sandbox-v1.0.48/core_ai_session-1.0.48-py3-none-any.whl
# ... or, with the core-ai repository checked out, run this from the repository root:
pip install -e sdk/core-ai-session

python scripts/discover_and_call.py                        # what can this session reach?
python scripts/full_pipeline.py --tool kubernetes/namespaces_list --args '{}' --json
python -m unittest discover -s scripts/tests               # offline, no credentials, no network

core-ai-cli skill push docs/skills/core-ai-session-sample   # publish so other agents can read it
```

## What the SDK reaches

Everything the session's agent has configured, and nothing else — the catalog is the contract:

| Kind | Python | What it is |
|---|---|---|
| `mcp` | `s.mcp["<server>"]["<tool>"](...)` | the session's MCP servers |
| `api` | `s.api["<app>"]["<service>"]["<operation>"](...)` | Service API operations; caller identity is forwarded |
| `llm_call` | `s.llm_call["<definition>"](query=…)` | LLM_CALL definitions — the only LLM path a script has |
| `agent` | `s.agent["<name>"].run("…", wait=False)` | sub-agent delegation (usually long-running) |
| `builtin` | `s.tool("<name>")(...)` | the agent's non-sandboxed builtin tools |
| files | `s.files.publish(path, title=…)` | publish a file as a session artifact, returns its URL |
| datasets | `s.dataset["<name>"].state()/insert()/…` | the datasets this session's agent mounts |

A bare `s.call("<tool path>", {…})` reaches any of them — the namespaces are only the readable form.

## The script contract

1. **One import.** `from core_ai_session import session`. No credentials, no vendor SDKs, no HTTP.
2. **Discover, never guess.** `s.tools(query=…)` → `s.describe(path)` → call. A name that is not in
   the catalog is an error the script reports, not a fallback.
3. **Let `session()` choose.** Sandbox, CLI or fake: one call site. Never branch on the environment.
4. **Failures raise.** `ToolError` (the tool ran and failed), `ToolNotFoundError`, `NotBoundError`
   (no credentials / session not bound yet), `CoreAiSessionError`. Map them onto exit codes
   `1 / 2 / 3` and print the message — never continue after a failure.
5. **stdout is the answer, stderr is the story.** Support `--json` for agents; accept `-` to read
   long arguments from stdin (Windows argv limits are real).
6. **`timeout` / `wait` / `wait_timeout` are the SDK's** — unless the tool's own schema declares them
   (`run_bash(timeout=5000)` still means the sandbox's bash timeout). A call that is still running
   comes back as a `Task`: poll it, bound the wait, report the task id.
7. **Write only what the binding allows.** SESSION datasets hold state (`state/set/patch`), GENERAL
   ones hold records (`records/insert/update/delete`); a read-only binding refuses a write in the
   server's own words, before any round trip. A local run needs `--session <id>` (or
   `CORE_AI_SESSION_ID`) because datasets belong to a session, not to you.

## The API in one screen

```python
from core_ai_session import session, ToolError, NotBoundError, ToolNotFoundError, Task

s = session()                      # hub in a sandbox, core-ai-cli locally, FakeSession in tests
try:
    info = s.me()                  # session_id, agent_name, sandbox_state, tool_count, caller
    catalog = s.catalog()          # every callable tool; s.refresh() re-reads it
    hits = s.tools("reviews", kind="mcp")          # search names, paths, descriptions
    detail = s.describe("google-gbp/list_reviews") # .input_schema, .description, .timeout_seconds

    result = s.mcp["google-gbp"]["list_reviews"](location="ChIJ…", limit=5)
    result.text                    # the tool's text output
    result.data                    # text parsed as JSON when it is JSON, else None
    result.call_id, result.duration_ms, result.llm_usage

    summary = s.llm_call["seo-title-semantics"](query=result.text[:2000])
    url = s.files.publish("/workspace/report.html", title="Weekly report", public=True)

    node = s.dataset["review-log"]         # by name when unique, by id always
    node.insert({"source_tool": "google-gbp/list_reviews", "digest": summary.text})
    node.records(filter={"source_tool": "…"}, limit=10)      # newest first
    state = s.dataset["menu-state"].state()                  # SESSION dataset

    task = s.agent["review-responder"].run("reply to review r-1", wait=False)
    task.poll(); task.wait(60)     # or just raise the timeout and let the call block
except NotBoundError as error:     # not logged in, or the sandbox has no session yet
    raise SystemExit(3)
except ToolNotFoundError as error: # not in this session's catalog
    raise SystemExit(2)
except ToolError as error:         # the tool ran and failed (status_code, call_id, task_id)
    raise SystemExit(1)
finally:
    s.close()
```

Async scripts use `async_session()`, `await s.call(...)`, `await s.dataset[…]`, `await s.aclose()`;
tests use `FakeSession` (see `references/sdk-api.md`).

## The worked example

`scripts/full_pipeline.py` is the one to read first: read one tool → digest → save → publish, with
every stage skippable and every failure exit-coded.

```bash
python scripts/full_pipeline.py \
    --tool google-gbp/list_reviews --args '{"location": "ChIJ…"}' \
    --summarize seo-title-semantics \
    --save review-log \
    --publish /workspace/digest.md --public --json
```

| Stage | What it demonstrates |
|---|---|
| resolve | `find_entry` by path/name/ref id; a near-miss lists candidates instead of calling |
| call | `wait=False` → `Task` → bounded `wait()`; a still-running call exits 6 with its task id |
| digest | `s.llm_call[definition](query=…)`; a definition the session does not mount is exit 2 |
| save | SESSION → `patch`, GENERAL → `insert`; a read-only binding is refused before the round trip |
| publish | `s.files.publish(path, title=…, public=…)` returns the URL to hand to other systems |
| report | `--json` for agents, a readable summary otherwise; `--dry-run` writes nothing |

## The scripts in this skill

| Script | Shows |
|---|---|
| `scripts/discover_and_call.py` | identity, catalog, search, describe, one ad-hoc call, dataset listing |
| `scripts/full_pipeline.py` | the end-to-end pattern above; the injectable `opened=` seam tests use |
| `scripts/async_fanout.py` | `AsyncSession`, `asyncio.gather` fan-out, awaited dataset writes |
| `scripts/export_catalog.py` | freeze a real catalog + schemas into JSON for offline tests |
| `scripts/tests/test_scripts.py` | `FakeSession` scripting/assertions, an `httpx.MockTransport` hub, error paths |
| `assets/skill-template/` | the minimum skeleton to copy for a new skill |

## Testing a skill script

1. **Offline** — `FakeSession` and a scripted catalog; assert the call sequence and the arguments:
   `python -m unittest discover -s scripts/tests`. Credential-free, runs anywhere, CI-friendly.
   `FakeSession` replays namespaces, argument validation, dataset checks and results; the transport
   features (`s.tools(query=…)`, tasks over the wire) are tested against `httpx.MockTransport`, as
   `scripts/tests/test_scripts.py` shows.
2. **Locally** — run the script for real through your own login:
   `python scripts/full_pipeline.py --tool … --dry-run`. A local catalog is what *you* can see, not
   what an agent mounts; a `403`, a missing `builtin` tool or a `catalog_gaps` entry is expected
   here and is not a production bug.
3. **In a sandbox** — the authoritative run: through the agent's `run_bash` tool, with the agent's
   mounts. Check the `hub_calls` record afterwards; a script's calls appear exactly like the agent's.

Checklist before publishing: exit codes 0/1/2/3 for success/tool failure/usage/not-bound; `--json`
output stable; no credential env var read anywhere (`grep -E "_TOKEN|_KEY|_URL" scripts/`); the
script says what it needs when a capability is not mounted; nothing writes unless asked.

## Publishing the skill

```bash
core-ai-cli skill push docs/skills/core-ai-session-sample            # into your own namespace
core-ai-cli skill search "skill script sdk"
core-ai-cli skill show Stephen/core-ai-session-sample --raw          # read it without installing
core-ai-cli skill pull Stephen/core-ai-session-sample --workspace
```

`skill push` takes no namespace: a skill is always uploaded into the authenticated account's own
namespace, so the qualified name follows from who you are logged in as. A pulled skill carries a
`.skill-hub.json` marker, so `skill list` / `skill update` track server-side changes. Installing it
puts a copy under `~/.core-ai/skills/<namespace>/<name>/` (or `<workspace>/.core-ai/skills/`), where
the local agent loads it by its frontmatter.

## Sandbox and local are deliberately not identical

| | In a sandbox | Locally |
|---|---|---|
| catalog | what the **agent mounts** | what **you can see**, enumerated best effort (`s.catalog_gaps` names the gaps) |
| identity | the session's caller; backend data scoping applies | you — caller headers, permissions and data scoping are yours |
| `s.me()` | session id, agent name, `contract_version` | a stand-in (`sandbox_state="local"`); one warning is logged |
| `builtin` tools | `submit_artifacts`, `web_search`, … | absent — calling one raises `ToolNotFoundError`, never a fake success |
| call timeout | up to 600 s | capped at 300 s by the CLI: the call returns a `Task` and keeps running |
| datasets | the token names the session | pass `session_id=` / `CORE_AI_SESSION_ID` / `--session` |
| `CORE_AI_SERVER` / key | n/a | resolved flag → env → `~/.core-ai/auth.json` |

`CORE_AI_HUB` always wins: while it is set, `backend="cli"` is refused, so a script inside a sandbox
can never quietly run under the reader's identity.

## Pitfalls

* **`from` and other keywords.** `s.dataset["x"].records(from_="…")` is the SDK's spelling; for a
  tool that declares `from`, pass it as a dict: `s.call(path, {"from": "…"})`.
* **Reserved names.** `timeout`, `wait`, `wait_timeout` are the SDK's unless the tool's schema says
  otherwise — read `describe().input_schema` when in doubt.
* **Hidden tools.** `exposure="hidden"` entries (registry control tools) are listed but not callable.
* **Ambiguous datasets.** Two bindings may share a name; the SDK refuses and lists the ids — address
  the id.
* **A local "tool not found" is often a catalog gap.** Check `s.catalog_gaps` before believing it;
  the sandbox catalog is the authoritative one.
* **Tasks are not failures.** A pending task carries `task_id`; poll it (`wait=False` + `poll`) or
  raise the wait. Do not re-issue the call.

## References

* `references/sdk-api.md` — the complete API: sessions and transports, every namespace, results,
  tasks, errors, datasets, file publishing, testing, constants and limits.
* `references/authoring.md` — skill layout and frontmatter, the authoring rules, the test loop, the
  publish/update workflow, and the migration checklist for scripts that still hold credentials.
* `../session-probe/SKILL.md` — a smaller skill built the same way, for inspecting a live session.
* Repository docs: `docs/cn/design-sandbox-hub.md` (design), `docs/skills/core-ai-cli-manual/references/hub.md`
  (the CLI-side view of the same contract).
