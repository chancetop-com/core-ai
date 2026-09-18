---
name: session-probe
description: Inspect what the current core-ai session can reach — transport, identity, catalog digest, one tool's schema, or an ad-hoc call — through the core_ai_session Python SDK, so the same command runs locally (core-ai-cli) and inside a sandbox (the session hub). Use when verifying a session's mounts, when a tool name must be discovered instead of guessed, when a "tool not found" needs explaining, or when trying a call without hand-writing CLI syntax.
metadata:
  author: core-ai-team
  version: "1.0"
---

# session-probe

One script that answers "what can I call from here?", and one worked example of the
skill-script contract: the same file runs in both environments, because
`core_ai_session` picks its transport from the environment.

```bash
python scripts/session_probe.py                              # transport, identity, catalog digest
python scripts/session_probe.py --search elastic             # catalog tools matching all words
python scripts/session_probe.py --describe kubernetes/pods_list
python scripts/session_probe.py --call kubernetes/namespaces_list
python scripts/session_probe.py --call some/server/tool --args '{"key": "value"}'
python scripts/session_probe.py --call some/server/tool --args -        # JSON on stdin
python scripts/session_probe.py --json                       # machine-readable, any mode
```

Exit codes: `0` ok, `1` the tool failed, `2` usage error / unknown tool, `3` not bound or no
credentials (CLI not logged in, or the sandbox session is not ready yet).

## Rules

- **Discover, never guess.** `--search` first, `--describe` to read `input_schema` before
  passing `--args`. The same three steps in a skill script are `s.tools(query=…)`,
  `s.describe(path)`, `s.call(path, args)` — or the typed form `s.mcp.kubernetes.namespaces_list()`.
- A tool path is globally unique: `<server>/<tool>` for mcp, `<app>/<service>/<operation>`
  for api, a bare name for agents and `llm_call`s.
- `--` and `_` are interchangeable in namespace keys (`list-databases` = `list_databases`).
- Search matches every word as a substring of the tool's name, path and description — pick
  distinctive words (`index` does not match `indices`); use `--json` when an agent must parse.

## Where it runs

| | inside a sandbox | on a workstation |
|---|---|---|
| transport | session hub (`CORE_AI_HUB`) | `CliSession` → `core-ai-cli … --json` |
| identity | the session's caller | your own CLI login |
| setup | preinstalled in the image | `core-ai-cli --login` once + `pip install -e sdk/core-ai-session` (or the `sandbox-v*` release wheel) |

Local-only facts (deliberate, not bugs): the catalog is a best-effort enumeration, and
`catalog_gaps` names any source it could not list in full (the biggest servers can
intermittently fail to enumerate — a retry usually clears it); `builtin` tools
(`run_bash`, `submit_artifacts`) do not exist locally, so `s.files.publish(…)` raises
`ToolNotFoundError`; call timeouts cap at 300s locally (a longer call returns a task to poll);
the first local session logs one warning about running as yourself.

## Worked example: a read-only k8s look

```bash
python scripts/session_probe.py --search kubernetes     # e.g. namespaces_list, pods_list, events_list
python scripts/session_probe.py --describe kubernetes/pods_list
python scripts/session_probe.py --call kubernetes/namespaces_list
```

## Installing and publishing

- Use from this checkout: run the script with any Python that has `core_ai_session` installed.
- Install for the local agent: copy this directory under `~/.core-ai/skills/`, or a
  workspace's `.core-ai/skills/`.
- Publish to the hub: `core-ai-cli skill push docs/skills/session-probe --json`.
