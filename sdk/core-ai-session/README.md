# core-ai-session

The Python SDK for a session's agent-configured capabilities: MCP tools, Service API tools,
`llm_call` definitions, sub-agents and the session's bound datasets — all reached through one
`call` primitive.

One package, two transports; `session()` picks by environment:

| where | transport | identity |
| --- | --- | --- |
| inside a sandbox (`CORE_AI_HUB` set) | `Session` → the runtime's loopback hub proxy | the session token, held by the runtime, never by the script |
| on a developer machine (`CORE_AI_HUB` unset) | `CliSession` → `core-ai-cli … --json` | your own CLI login |
| offline tests | `FakeSession` | none — in-process contract fixtures |

`CORE_AI_HUB` always wins: there is no silent fallback to the local identity. Scripts never hold
credentials — that is why the two transports exist at all. Bash and other languages use the
runtime's `core-ai-sandbox` CLI instead. Design: `docs/cn/design-sandbox-hub.md`.

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

## Layout

- `core_ai_session/` — the package (`session()`, namespaces, errors, task polling)
- `tests/` — the offline suite plus the live helpers (recording, verification)
- `contract-fixtures/` — the single source of truth for the hub wire format: the Go runtime
  (`core-ai-sandbox-runtime/hub_contract_test.go`), the Python SDK and the fake CLI all assert
  against the same bytes; `recorded/` holds real, anonymised `core-ai-cli` exchanges

## Install

- Inside the sandbox image: preinstalled system-wide, zero setup.
- From a source checkout (repo root): `pip install -e sdk/core-ai-session`
- From a release: the wheel attached to `sandbox-v<version>`
  (`core_ai_session-<version>-py3-none-any.whl`).

The wheel is built with the runtime image and carries the same version number as
`core-ai-sandbox-runtime/VERSION`. That file is the only trigger: bumping it rebuilds the image and
republishes the wheel, so an SDK change that should ship needs a bump.

## Tests

```bash
python -m unittest discover -s tests             # offline, credential-free
python tests/verify_live_cli.py --tool <name>    # against your installed core-ai-cli
```
