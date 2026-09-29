# Authoring a skill with the core_ai_session SDK

How a skill is laid out, what a script must keep, how to test it before trusting it, and how to get
it in front of other agents. The task-level walkthrough is `../SKILL.md`; the API is `sdk-api.md`.

## 1. Layout

```text
my-skill/
├── SKILL.md              # required: YAML frontmatter + the instructions an agent follows
├── scripts/              # optional: the runnable part (this is where the SDK is used)
│   └── main.py
├── references/           # optional: detail an agent loads on demand
└── assets/               # optional: templates, fixtures, config
```

Shipped to the hub with `core-ai-cli skill push <dir>`; installed locally under
`~/.core-ai/skills/<namespace>/<name>/` (or `<workspace>/.core-ai/skills/`), where the agent loads it
by frontmatter. On the server side an agent's `use_skill` materializes the same package into the
sandbox at `/skill/<name>/`, so a script can rely on its siblings being next to it.

### SKILL.md frontmatter

```yaml
---
name: my-skill               # ^[a-z0-9]+(-[a-z0-9]+)*$, max 64 chars, must equal the directory name
description: One sentence that says what it does AND when to use it — this is the only part an agent
  sees before deciding to load the skill.
license: Apache-2.0          # optional
compatibility: needs the core_ai_session SDK — preinstalled in sandboxes; on a workstation install the
  sandbox-v<version> release wheel, or `pip install -e sdk/core-ai-session` from a repository checkout
metadata:
  author: your-team
  version: "1.0"
allowed-tools: ShellCommandTool ReadFileTool   # optional, informational
---
```

Write the body for the reader that will follow it: what the skill does, the workflow in steps, the
exact commands to run, the failure modes to expect, and where the references are. Keep it focused —
one skill, one workflow.

## 2. The rules a script must keep

1. **No credentials, ever.** One import (`from core_ai_session import session`) and no `*_TOKEN`,
   `*_KEY`, `*_MCP_URL`, no hand-rolled HTTP, no vendor SDK. The transport carries the identity:
   inside a sandbox the runtime injects the session token and replaces any `Authorization` header the
   script sends; locally it is your `core-ai-cli` login.
2. **One call site, two environments.** Never branch on "am I in a sandbox"; `session()` decides.
3. **Discover, never guess.** `s.tools(query=…)` → `s.describe(path)` → call. Hardcoding a tool name
   that an agent may not mount turns a mount change into a broken skill.
4. **Fail loudly and map the failure.** Let the exceptions propagate to one `try` that prints and
   exits `1` (`ToolError`), `2` (`ToolNotFoundError` / usage), `3` (`NotBoundError`).
5. **stdout is the answer, stderr is the story.** A `--json` mode is what other agents consume; keep
   it stable. Accept `-` for long arguments (stdin), never for credentials.
6. **Bounded waits.** Every call has a timeout; a `Task` is polled with a bound, and a still-running
   task is reported with its id (exit 6 in the samples) rather than retried blindly.
7. **Writes are explicit.** `--dry-run` where a write is possible; a read-only dataset or a missing
   capability is reported, not worked around.

## 3. A script skeleton

```python
"""one_thing.py - what this does, and the exact command to run it."""
from __future__ import annotations

import argparse
import json
import sys

from core_ai_session import (
    CoreAiSessionError, NotBoundError, Task, ToolError, ToolNotFoundError, session,
)

def main(argv: list[str] | None = None, *, opened=None) -> int:
    args = _parser().parse_args(argv)
    active = opened or session()            # the injectable seam the tests use
    try:
        entry = active.find_entry(args.tool)
        if entry is None:
            print(f"'{args.tool}' is not in this session's catalog", file=sys.stderr)
            return 2
        result = active.call(entry.name, _arguments(args), timeout=args.timeout, wait=False)
        if isinstance(result, Task):        # still running: bound the wait, then report the id
            result = result.wait(args.wait)
            if result.status == "pending":
                print(f"still running: task {result.task_id}", file=sys.stderr)
                return 6
        print(json.dumps({"tool": entry.path, "text": result.text}, ensure_ascii=False)
              if args.json else result.text)
        return 0
    except NotBoundError as error:
        print(f"not bound: {error}", file=sys.stderr)
        return 3
    except ToolNotFoundError as error:
        print(f"unknown name: {error}", file=sys.stderr)
        return 2
    except ToolError as error:
        print(f"tool failed: {error.message}", file=sys.stderr)
        return 1
    except CoreAiSessionError as error:
        print(f"failed: {error}", file=sys.stderr)
        return 1
    finally:
        if opened is None:
            active.close()

if __name__ == "__main__":
    raise SystemExit(main())
```

`assets/skill-template/` in this skill is the same shape with the documentation stubs in place.

## 4. The test loop

**Offline (the default).** `FakeSession` replays a catalog and records every call, so tests assert
the call sequence and the arguments with no credentials and no network:

```python
from core_ai_session.testing import FakeSession
from core_ai_session import ToolError

def test_reports_a_tool_failure():
    fake = FakeSession(catalog=CATALOG)                       # the shape /catalog answers with
    fake.mcp["google-gbp"]["list_reviews"].raises(ToolError("quota exceeded"))
    assert main(["--tool", "google-gbp/list_reviews"], opened=fake) == 1
    assert fake.calls[0].arguments == {"location": "x"}       # what the tool would have received
```

Point the fake at a **real** catalog so the namespaces match production: run
`python scripts/export_catalog.py --out catalog.json --details details.json` inside a sandbox (the
agent's mounts) and check the files in. For transport behaviour the fake deliberately skips — search,
tasks over the wire, HTTP status mapping — drive a real `Session`/`AsyncSession` over
`httpx.MockTransport`, as `scripts/tests/test_scripts.py` does.

**Locally (the second look).** Run the same script through your own login:

```bash
python scripts/full_pipeline.py --tool kubernetes/namespaces_list --args '{}' --dry-run --json
```

The catalog is what *you* can see, not what an agent mounts, and `builtin` tools do not exist here —
a `ToolNotFoundError` or a `catalog_gaps` entry at this stage is expected. What this run proves is
that the arguments, the result handling and the exit codes work against a live server.

**In a sandbox (the authoritative run).** Have the agent run the script through its `run_bash` tool
(e.g. `/skill/<name>/scripts/main.py`), with the mounts the skill is written for. Afterwards check
the `hub_calls` records: the script's calls appear exactly like the agent's own.

A skill is ready to publish when: the offline suite is green; exit codes 0/1/2/3 are correct; the
`--json` output is stable and free of prose; `grep -rE "_TOKEN|_KEY|_MCP_URL|Authorization" scripts/`
is empty; and every capability the script needs is either mounted or reported in one clear sentence.

## 5. Publishing and updating

```bash
core-ai-cli skill push <dir>                      # upload (needs skill.manage); goes into your namespace
core-ai-cli skill search "<words>" --json         # discover (needs skill.view)
core-ai-cli skill show <ns>/<name> --raw          # print SKILL.md: read it without installing
core-ai-cli skill pull <ns>/<name> --workspace    # install into <workspace>/.core-ai/skills/
core-ai-cli skill list                            # up-to-date / outdated / modified / local
core-ai-cli skill update --all                    # re-pull outdated skills
```

`push` has no namespace flag: a skill is always uploaded into the authenticated account's own
namespace, so `<ns>` is whoever is logged in. A pulled skill carries a `.skill-hub.json` marker
(id, digest, server): `list` and `update` compare digests, so a server-side change is visible without
a version number. Skills installed for a repository's workspace are found by the local agent
automatically — the same package an agent gets through `use_skill` in a sandbox.

## 6. Migrating a script that still holds credentials

This is the checklist that matters most when a skill moves from "works on my machine" to the hub:

| In the script today | Replace with |
|---|---|
| Hand-rolled MCP clients + `MCP_URL` / `*_AUTH_HEADER` env vars | `s.mcp["<server>"]["<tool>"](…)`; delete the client files and the env vars |
| Direct LLM calls with the prompt hardcoded | one LLM_CALL definition attached to the agent; `s.llm_call["<name>"](query=…)` |
| `*_BASE_URL` / `*_TOKEN` for internal services | `s.api["<app>"]["<service>"]["<operation>"](…)` with the app attached to the agent |
| Third-party endpoints with their own keys | register the capability as an MCP server or Service API and attach it (last resort: `sandboxConfig.env`, which also bypasses the warm pool) |
| SKILL.md telling the reader to "check whether these MCPs are configured" | tell it to run `core-ai-sandbox catalog` / `s.catalog()` and to ask the user to attach what is missing |
| Testing only by running in a sandbox | keep the sandbox as the integration test, but develop with `FakeSession` unit tests and a plain `python script.py` locally |

Migration is done when `grep -E "LITELLM|MCP_URL|_TOKEN|_KEY|openai"` over the skill directory finds
nothing and the agent's `sandboxConfig.env` is empty.

## 7. What the failures mean

| Symptom | Meaning | What to do |
|---|---|---|
| `NotBoundError` (exit 3) in a sandbox | the runtime has no session bound yet (503 `not_bound`, retried 3×), or the token was rejected (401) | retry once the session is ready; do not retry a 401 |
| `NotBoundError` locally | `core-ai-cli` is not installed or not logged in | `core-ai-cli --login`; `CORE_AI_CLI` points at a different binary |
| `ToolNotFoundError` (exit 2) | the name is not in this session's catalog | `s.tools(query=…)`; check `s.catalog_gaps` locally — the sandbox catalog is authoritative |
| `ToolError` 403 | this identity may not call it (locally `mcp.call` is admin-only until granted) | report it; a local 403 is not a production 403 |
| `ToolError` 429 | the tool's own rate limit | back off; do not hammer |
| `ToolError` 504 / a `Task` that stays pending | the call is still running server-side | poll with the `task_id`; raise the timeout instead of re-issuing |
| `CoreAiSessionError` mentioning the CLI version | the installed CLI predates a hub subcommand | `core-ai-cli upgrade` (needs ≥ 2.0.10) |
| A dataset operation refused | wrong type or missing permission — the sentence is the server's | read the binding (`s.dataset.list()`): SESSION vs GENERAL, `permission` |
| `s.dataset` says a session id is needed | a local run has no session to act on | `session_id=`, `CORE_AI_SESSION_ID`, or `--session` |
