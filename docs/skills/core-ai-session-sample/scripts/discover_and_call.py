"""discover_and_call.py - what can this session reach, and one ad-hoc call.

    python discover_and_call.py                          # transport, identity, catalog digest, datasets
    python discover_and_call.py --search reviews --kind mcp
    python discover_and_call.py --describe google-gbp/list_reviews
    python discover_and_call.py --call google-gbp/list_reviews --args '{"location": "ChIJ..."}'
    python discover_and_call.py --call some/client/tool --args -    # JSON on stdin
    python discover_and_call.py --json                   # machine-readable form of any mode

The rule the whole SDK is built around: **discover, never guess**. Search first, read the schema
with --describe, then call. The same script runs inside a sandbox (the session hub) and locally
(core-ai-cli), because `session()` picks the transport from the environment.

Exit codes: 0 ok, 1 the call failed, 2 usage / unknown name, 3 not bound / no credentials.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from typing import Any, Optional

from core_ai_session import (
    CoreAiSessionError,
    NotBoundError,
    Task,
    ToolError,
    ToolNotFoundError,
    session,
)

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_NOT_BOUND = 3
EXIT_RUNNING = 6

MAX_TEXT = 4000
DEFAULT_WAIT = 120

try:  # Windows consoles are not always UTF-8; never let a print kill the report
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - a console without reconfigure still prints
    pass


def _head(text: str, limit: int) -> str:
    text = text or ""
    return text if len(text) <= limit else text[:limit] + f"\n… ({len(text) - limit} more chars)"


def _identity(opened: Any) -> Optional[dict[str, Any]]:
    """`me()` is the session the hub token names; a local run answers a stand-in with no session."""
    try:
        info = opened.me()
    except CoreAiSessionError:
        return None
    return {
        "session_id": info.session_id,
        "agent_name": info.agent_name,
        "sandbox_state": info.sandbox_state,
        "tool_count": info.tool_count,
        "caller": info.caller,
    }


def _datasets(opened: Any) -> tuple[list[dict[str, Any]], Optional[str]]:
    """The datasets this agent mounts; a local run without a session id explains what it needs."""
    try:
        bindings = opened.dataset.list()
    except CoreAiSessionError as error:
        return [], str(error)
    return (
        [
            {"dataset_id": info.dataset_id, "name": info.name, "type": info.type,
             "permission": info.permission, "fields": info.field_names()}
            for info in bindings
        ],
        None,
    )


def overview(opened: Any) -> dict[str, Any]:
    catalog = opened.catalog()
    groups = Counter((entry.kind, entry.group) for entry in catalog.tools)
    datasets, datasets_note = _datasets(opened)
    return {
        "transport": type(opened).__name__,
        "backend": opened.backend,
        "identity": _identity(opened),
        "tools": len(catalog.tools),
        "kinds": dict(Counter(entry.kind for entry in catalog.tools)),
        "groups": [{"kind": kind, "group": group, "tools": count}
                   for (kind, group), count in sorted(groups.items())],
        "catalog_gaps": list(getattr(opened, "catalog_gaps", []) or []),
        "datasets": datasets,
        "datasets_note": datasets_note,
    }


def search(opened: Any, words: str, kind: Optional[str]) -> dict[str, Any]:
    hits = opened.tools(query=words, kind=kind)
    return {
        "query": words,
        "kind": kind,
        "count": len(hits),
        "tools": [{"path": entry.path, "kind": entry.kind, "group": entry.group,
                   "description": entry.description} for entry in hits],
    }


def describe(opened: Any, name: str) -> dict[str, Any]:
    detail = opened.describe(name)
    return {
        "path": detail.path,
        "kind": detail.kind,
        "group": detail.group,
        "ref_id": detail.ref_id,
        "description": detail.description,
        "input_schema": detail.input_schema,
        "timeout_seconds": detail.timeout_seconds,
    }


def call(opened: Any, name: str, raw_args: str, timeout: Optional[int], wait: int) -> dict[str, Any]:
    """One call, bounded. `wait=False` first, so a long call becomes a task we can describe."""
    arguments: dict[str, Any] = {}
    if raw_args.strip():
        text = sys.stdin.read() if raw_args == "-" else raw_args
        arguments = json.loads(text)
        if not isinstance(arguments, dict):
            raise ValueError("--args must be a JSON object")
    result = opened.call(name, arguments, timeout=timeout, wait=False)
    if isinstance(result, Task):
        print(f"pending: task {result.task_id}, waiting up to {wait}s", file=sys.stderr)
        result = result.wait(wait)
    if isinstance(result, Task) or result.status == "pending":
        return {"status": "pending", "task_id": result.task_id, "tool": name, "duration_ms": 0,
                "text": "", "is_error": False, "content_parts": 0}
    usage = result.llm_usage
    return {
        "status": result.status,
        "tool": name,
        "call_id": result.call_id,
        "duration_ms": result.duration_ms,
        "text": result.text,
        "is_error": result.is_error,
        "content_parts": len(result.content),
        "llm_usage": None if usage is None else {
            "model": usage.model, "input_tokens": usage.input_tokens,
            "output_tokens": usage.output_tokens, "cost": usage.cost,
        },
    }


def _print_overview(payload: dict[str, Any]) -> None:
    identity = payload.get("identity") or {}
    print(f"transport : {payload['transport']} (backend={payload['backend']})")
    if identity:
        print(f"identity  : session={identity['session_id'] or '-'} agent={identity['agent_name'] or '-'} "
              f"sandbox={identity['sandbox_state'] or '-'}")
    else:
        print("identity  : (the hub has not answered `me` yet)")
    print(f"catalog   : {payload['tools']} tools {payload['kinds']}")
    for row in payload["groups"][:24]:
        print(f"   {row['kind']:<9} {row['group'] or '-':<28} {row['tools']}")
    gaps = payload.get("catalog_gaps") or []
    print(f"catalog_gaps: {'; '.join(gaps) if gaps else '(none)'}")
    if payload.get("datasets_note"):
        print(f"datasets  : {payload['datasets_note']}")
    for item in payload.get("datasets") or []:
        print(f"   {item['name'] or '(unnamed)':<24} {item['type']:<8} {item['permission']:<6} "
              f"{', '.join(item['fields'])}")


def _print_result(payload: dict[str, Any]) -> None:
    if payload.get("status") == "pending":
        print(f"pending   : task {payload['task_id']} (still running; call again with wait=False)")
        return
    print(f"status    : {payload['status']} ({payload['duration_ms']} ms, "
          f"{payload['content_parts']} content part(s))")
    usage = payload.get("llm_usage")
    if usage:
        print(f"usage     : model={usage['model']} in={usage['input_tokens']} out={usage['output_tokens']}")
    print(_head(payload["text"] or "(no text)", MAX_TEXT))


def main(argv: Optional[list[str]] = None, *, opened: Optional[Any] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="discover_and_call.py",
        description="Inspect this session and try one call (the same command works in both places).",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--search", metavar="WORDS", help="list catalog tools matching all words")
    mode.add_argument("--describe", metavar="PATH", help="show one tool's description and input_schema")
    mode.add_argument("--call", metavar="PATH", help="call one tool (describe it first)")
    parser.add_argument("--kind", help="--search: mcp | api | llm_call | agent | builtin")
    parser.add_argument("--args", default="", help="JSON object for --call; '-' reads it from stdin")
    parser.add_argument("--timeout", type=int, help="max seconds the call may run in the session")
    parser.add_argument("--wait", type=int, default=DEFAULT_WAIT,
                        help=f"seconds to wait for a pending task (default {DEFAULT_WAIT})")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = parser.parse_args(argv)

    if (args.args or args.timeout) and args.call is None:
        print("--args/--timeout only make sense together with --call", file=sys.stderr)
        return EXIT_USAGE
    active: Optional[Any] = opened
    try:
        if active is None:
            active = session()
        if args.search is not None:
            payload: dict[str, Any] = search(active, args.search, args.kind)
        elif args.describe is not None:
            payload = describe(active, args.describe)
        elif args.call is not None:
            payload = call(active, args.call, args.args, args.timeout, args.wait)
        else:
            payload = overview(active)
    except NotBoundError as error:
        print(f"not bound: {error}", file=sys.stderr)
        return EXIT_NOT_BOUND
    except ToolNotFoundError as error:
        print(f"unknown name: {error}", file=sys.stderr)
        return EXIT_USAGE
    except ToolError as error:
        print(f"tool failed: {error.message}", file=sys.stderr)
        return EXIT_FAILED
    except (CoreAiSessionError, ValueError, TypeError) as error:
        print(f"failed: {error}", file=sys.stderr)
        return EXIT_FAILED if isinstance(error, CoreAiSessionError) else EXIT_USAGE
    finally:
        if opened is None and active is not None:
            active.close()

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
        return EXIT_RUNNING if payload.get("status") == "pending" else EXIT_OK
    if args.search is not None:
        for item in payload["tools"][:60]:
            print(f"{item['path']:<56} {item['kind']:<9} {(item['description'] or '').replace(chr(10), ' ')[:70]}")
        print(f"— {payload['count']} match(es)")
    elif args.describe is not None:
        print(f"{payload['path']} ({payload['kind']}, group={payload['group'] or '-'})")
        print(payload["description"] or "(no description)")
        print(json.dumps(payload["input_schema"] or {}, ensure_ascii=False, indent=2)[:3000])
    elif args.call is not None:
        _print_result(payload)
    else:
        _print_overview(payload)
    return EXIT_RUNNING if payload.get("status") == "pending" else EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
