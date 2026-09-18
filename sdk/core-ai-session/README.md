# core-ai-session

The Python SDK for a session's agent-configured capabilities: MCP tools, Service API tools,
`llm_call` definitions and sub-agents — all reached through one `call` primitive.

One package, two transports; `session()` picks by environment:

| where | transport | identity |
| --- | --- | --- |
| inside a sandbox (`CORE_AI_HUB` set) | `Session` → the runtime's loopback hub proxy | the session token, held by the runtime, never by the script |
| on a developer machine (`CORE_AI_HUB` unset) | `CliSession` → `core-ai-cli … --json` | your own CLI login |
| offline tests | `FakeSession` | none — in-process contract fixtures |

`CORE_AI_HUB` always wins: there is no silent fallback to the local identity. Scripts never hold
credentials — that is why the two transports exist at all. Bash and other languages use the
runtime's `core-ai-sandbox` CLI instead. Design: `docs/cn/design-sandbox-hub.md`.

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
