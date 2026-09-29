---
name: my-skill
description: Replace this with one sentence that says what the skill does and when to use it. This
  is the only text an agent sees before deciding to load the skill, so name the trigger conditions
  (the task, the user request, the symptoms) rather than the implementation.
metadata:
  author: your-team
  version: "0.1"
---

# my-skill

> Copy this directory next to it, rename the directory **and** the `name:` field to the same
> lowercase-hyphenated name, then replace every `REPLACE` below.

One paragraph: the workflow this skill teaches, and the outcome it produces.

## When to use

- REPLACE: the user request or symptom that should trigger this skill.
- REPLACE: what it must *not* be used for.

## Workflow

1. REPLACE: discover what the session can reach — `python scripts/main.py --search "<words>"`.
2. REPLACE: the exact command, with a real example of the arguments.
3. REPLACE: what to do with the output, and where it goes next.

```bash
python scripts/main.py --tool <server>/<tool> --args '{"key": "value"}' --json
```

Exit codes: `0` ok, `1` the tool failed, `2` usage / unknown name, `3` not bound / no credentials.

## Rules

- Scripts hold no credentials: one import (`from core_ai_session import session`), no `*_TOKEN` /
  `*_KEY` / `*_MCP_URL`, no hand-rolled HTTP. The same script runs locally (core-ai-cli) and in a
  core-ai-server sandbox (the session hub).
- Discover, never guess: search, read the schema, then call.
- If a capability is missing, say which one and stop — do not work around it with environment
  variables or a second client.

## Testing

```bash
python -m unittest discover -s scripts/tests    # offline, credential-free
python scripts/main.py --tool <path> --args '{}' --dry-run
```

REPLACE: anything the skill needs mounted (MCP servers, API apps, LLM_CALL definitions, datasets) and
how to check it is there.
